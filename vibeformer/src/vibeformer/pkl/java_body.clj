(ns vibeformer.pkl.java-body
  "Pkl-target resolved, fail-closed translation of Java executable Spoon trees.

  This namespace is the Pkl product rule bundle. It may contain Pkl semantics
  and Pkl.Core destination mappings, while the reusable recursive kernel in
  vibeformer.java-translate remains product-neutral. Structural rules consume
  only live Spoon objects and their already translated live children. Semantic
  rules are installed for each exact resolved symbol identity in the model."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-translate :as java]
            [vibeformer.spoon :as spoon])
  (:import [java.util IdentityHashMap]
           [spoon.reflect.code BinaryOperatorKind CtArrayRead CtArrayWrite CtAssert
            CtAssignment CtBinaryOperator CtBlock CtBreak CtCase CtCatch CtContinue
            CtCatchVariable CtComment CtConditional CtConstructorCall CtDo
            CtExecutableReferenceExpression CtExpression CtFieldRead CtFieldWrite
            CtFor CtForEach CtIf CtInvocation CtLambda CtLiteral CtLocalVariable
            CtNewArray CtOperatorAssignment CtReturn CtStatement CtSuperAccess
            CtSwitch CtSwitchExpression CtSynchronized CtThisAccess CtThrow CtTry CtTryWithResource CtTypeAccess
            CtTypePattern CtUnaryOperator CtVariableRead CtVariableWrite CtWhile
            CtYieldStatement UnaryOperatorKind]
           [spoon.reflect.declaration CtAnnotation CtClass CtConstructor CtElement CtField CtFormalTypeDeclarer CtMethod CtRecord
            CtParameter CtRecordComponent ModifierKind]
           [spoon.reflect.reference CtCatchVariableReference CtExecutableReference
            CtFieldReference CtLocalVariableReference CtPackageReference
            CtParameterReference CtTypeParameterReference CtTypeReference]
           [spoon.reflect.reference CtWildcardReference]
           [spoon.reflect.visitor.filter TypeFilter]))

(defn- raw [value] (csharp/raw (str value)))
(defn- sequence-node
  ([nodes] (csharp/sequence-node (vec (remove nil? nodes))))
  ([nodes separator]
   (csharp/sequence-node (vec (remove nil? nodes)) separator)))

(defn- child-node [children element]
  (:node (java/child-result children element)))

(defn- children-nodes [children elements]
  (mapv #(child-node children %) elements))

(defn- role [^CtElement element]
  (when (.isParentInitialized element) (str (.getRoleInParent element))))

(defn- statement-expression? [^CtElement element]
  (and (instance? CtStatement element)
       (let [element-role (role element)
             parent (when (.isParentInitialized element) (.getParent element))]
         (or (= "statement" element-role)
             (and (contains? #{"then" "else"} element-role)
                  (instance? CtIf parent))
             (and (= "body" element-role)
                  (or (instance? CtFor parent) (instance? CtForEach parent)
                      (instance? CtWhile parent) (instance? CtDo parent)))))))

(defn- identifier [services value]
  ((:identifier services) (str value)))

(defn- local-name [services element]
  (if-let [namer (:local-name services)]
    (namer element)
    (identifier services (.getSimpleName element))))

(defn- enclosing-type [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? spoon.reflect.declaration.CtType current) current
      :else (recur (when (.isParentInitialized ^CtElement current)
                     (.getParent ^CtElement current))))))

(defn- pkl-core-element? [^CtElement element]
  (str/starts-with? (or (some-> element enclosing-type .getQualifiedName) "") "org.pkl.core."))

(defn- nullable-annotation? [^CtElement element]
  (boolean
   (some #(= "org.jspecify.annotations.Nullable"
             (some-> ^CtAnnotation % .getAnnotationType .getQualifiedName))
         (.getAnnotations element))))

(defn- nullable-type? [^CtTypeReference reference]
  (and reference
       (not (.isPrimitive reference))
       (or (nullable-annotation? reference)
           (some nullable-type? (.getActualTypeArguments reference)))))

(def ^:private boxed-value-types
  #{"java.lang.Boolean" "java.lang.Byte" "java.lang.Short"
    "java.lang.Integer" "java.lang.Long" "java.lang.Character"
    "java.lang.Float" "java.lang.Double" "java.time.Duration"})

(defn- boxed-value-cast? [expression]
  (boolean
   (and (instance? CtExpression expression)
        (some #(contains? boxed-value-types (.getQualifiedName ^CtTypeReference %))
              (.getTypeCasts ^CtExpression expression)))))

(defn- nullable-record-component? [component]
  (boolean (and component
                (or (nullable-annotation? component)
                    (nullable-type? (.getType ^CtRecordComponent component))))))

(defn- nullable-record-accessor? [declaration]
  (boolean
   (when (instance? CtMethod declaration)
     (let [owner (.getDeclaringType ^CtMethod declaration)]
       (when (instance? CtRecord owner)
         (some (fn [component]
                 (and (= (.getSimpleName ^CtMethod declaration)
                         (.getSimpleName ^CtRecordComponent component))
                      (nullable-record-component? component)))
               (.getRecordComponents ^CtRecord owner)))))))

(defn- nullable-expression? [expression]
  (cond
    (and (instance? CtLiteral expression)
         (nil? (.getValue ^CtLiteral expression))) true
    (nullable-annotation? expression) true
    (nullable-type? (.getType ^CtExpression expression)) true
    (boxed-value-cast? expression) true
    (instance? CtVariableRead expression)
    (let [declaration (some-> ^CtVariableRead expression .getVariable .getDeclaration)]
      (boolean (and declaration
                    (or (nullable-annotation? declaration)
                        (nullable-type? (.getType declaration))
                        (when (instance? CtLocalVariable declaration)
                          (let [default (.getDefaultExpression ^CtLocalVariable declaration)]
                            (and default
                                 (not (boxed-value-cast? default))
                                 (nullable-expression? default))))))))
    (instance? CtInvocation expression)
    (let [declaration (some-> ^CtInvocation expression .getExecutable .getExecutableDeclaration)]
      (boolean (and declaration
                    (or (nullable-annotation? declaration)
                        (nullable-type? (.getType ^CtMethod declaration))
                        (nullable-record-accessor? declaration)))))
    (instance? CtNewArray expression)
    (boolean (some nullable-expression? (.getElements ^CtNewArray expression)))
    :else false))

(defn- nullable-parameter? [^CtTypeReference parameter-type parameter-declaration]
  (or (nullable-type? parameter-type)
      (and parameter-declaration
           (or (nullable-annotation? parameter-declaration)
               (nullable-type? (.getType ^CtParameter parameter-declaration))))))

(defn- nullable-enclosing-return? [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) false
      (instance? CtLambda current) false
      (instance? CtMethod current)
      (or (nullable-annotation? current)
          (nullable-type? (.getType ^CtMethod current)))
      :else (recur (when (.isParentInitialized ^CtElement current)
                     (.getParent ^CtElement current))))))

(defn- member [target name]
  (if target
    (csharp/member target name)
    (raw name)))

(defn- invoke [target arguments]
  (csharp/invocation target arguments))

(defn- null-forgiven [node]
  (if (str/ends-with? (:text (csharp/render node)) "!")
    node
    (sequence-node [node (raw "!")])))

(defn- non-null-node [^CtExpression expression node]
  (if (or (contains? boxed-value-types
                     (some-> expression .getType .getQualifiedName))
          (boxed-value-cast? expression))
    (if (str/ends-with? (:text (csharp/render node)) ".Value")
      node
      (member node "Value"))
    (null-forgiven node)))

(defn- primitive-argument-context? [^CtExpression expression]
  (when (.isParentInitialized expression)
    (let [parent (.getParent expression)
          invocation? (instance? CtInvocation parent)
          constructor? (instance? CtConstructorCall parent)]
      (when (or invocation? constructor?)
        (let [arguments (vec (.getArguments parent))
              index (first (keep-indexed (fn [index argument]
                                           (when (identical? expression argument) index))
                                         arguments))
              executable (.getExecutable parent)
              referenced-parameters (vec (.getParameters executable))
              declared-parameters (some-> executable .getExecutableDeclaration
                                           .getParameters vec)]
          (boolean
           (and index
                (or (some-> (when (< index (count referenced-parameters))
                              (nth referenced-parameters index))
                            .isPrimitive)
                    (some-> (when (and declared-parameters
                                       (< index (count declared-parameters)))
                              (.getType ^CtParameter (nth declared-parameters index)))
                            .isPrimitive)))))))))

(defn- wrap-casts [services children ^CtExpression expression node]
  (let [casts (vec (.getTypeCasts expression))
        ^CtTypeReference cast (when (= 1 (count casts)) (first casts))
        cast-arguments (when cast (vec (.getActualTypeArguments cast)))]
    (cond
      (and cast
           (= "java.lang.Appendable" (.getQualifiedName cast))
           (= "java.lang.StringBuilder" (some-> expression .getType .getQualifiedName)))
      (sequence-node [(raw "((") (child-node children cast)
                      (raw ")(") node (raw "))")])

      (and cast
           (or (= "org.pkl.core.util.json.JsonHandler" (.getQualifiedName cast))
               (str/includes?
                (:text (csharp/render (child-node children cast)))
                "Pkl.Core.Util.Json.JsonHandler")))
      (invoke (raw "global::Pkl.Core.Util.Json.JsonHandlerBridge.Erase") [node])

      (and cast
           (= "java.util.List" (.getQualifiedName cast))
           (= 1 (count cast-arguments)))
      (invoke
       (csharp/generic-name
        (raw "global::Vibeformer.Runtime.JavaCompat.CastList")
        [((:type-node services) (first cast-arguments))])
       [node])

      (and cast
           (= "java.util.Map" (.getQualifiedName cast))
           (= 2 (count cast-arguments)))
      (invoke
       (csharp/generic-name
        (raw "global::Vibeformer.Runtime.JavaCompat.CastDictionary")
        (mapv (:type-node services) cast-arguments))
       [node])

      (and (instance? CtInvocation expression)
           (= "getProperties" (some-> ^CtInvocation expression .getExecutable .getSimpleName))
           (= "java.lang.System" (some-> ^CtInvocation expression .getExecutable .getDeclaringType
                                          .getQualifiedName)))
      node

      :else
      (reduce (fn [value ^CtTypeReference current-cast]
                (if (.isPrimitive current-cast)
                  (sequence-node [(raw "(") (child-node children current-cast)
                                  (raw ")(") value (raw ")")])
                  (cond
                    (contains? boxed-value-types
                               (.getQualifiedName current-cast))
                    (let [cast-node
                          (sequence-node [(raw "((") (child-node children current-cast)
                                          (raw "?)((object)(") (null-forgiven value) (raw ")))")])]
                      (if (primitive-argument-context? expression)
                        (member cast-node "Value")
                        cast-node))

                    (or (instance? CtLambda expression)
                        (and (instance? CtConditional expression)
                             (str/includes? (:text (csharp/render value)) "=>")))
                    (sequence-node [(raw "((") (child-node children current-cast)
                                    (raw ")(") value (raw "))")])

                    :else
                    (sequence-node [(raw "((") (child-node children current-cast)
                                    (raw ")((object)(") (null-forgiven value) (raw ")))")]))))
              node
              (reverse casts)))))

(defn- finish-expression [services children ^CtExpression expression node]
  (let [node (wrap-casts services children expression node)]
    (if (statement-expression? expression)
      (sequence-node [node (raw ";")])
      node)))

(defn- generic-invariant-return-type [^CtReturn element]
  (let [expression (.getReturnedExpression element)]
    (loop [current (when (.isParentInitialized element) (.getParent element))]
      (cond
        (nil? current) nil
        (instance? CtLambda current) nil
        (instance? CtMethod current)
        (let [return-type (.getType ^CtMethod current)
              expression-type (some-> expression .getType)
              owner-name (some-> ^CtMethod current .getDeclaringType .getQualifiedName)]
          (when (and return-type expression-type
                     (not (.isPrimitive return-type))
                     (= (.getQualifiedName return-type)
                        (.getQualifiedName ^CtTypeReference expression-type))
                     (or (and (seq (.getActualTypeArguments return-type))
                              (not= (mapv str (.getActualTypeArguments return-type))
                                    (mapv str (.getActualTypeArguments ^CtTypeReference expression-type))))
                         (and (str/starts-with? (or owner-name "")
                                               "org.pkl.core.util.paguro.RrbTree$")
                              (str/starts-with? (.getQualifiedName return-type)
                                                "org.pkl.core.util.paguro.RrbTree$"))))
            return-type))
        :else (recur (when (.isParentInitialized ^CtElement current)
                       (.getParent ^CtElement current)))))))

(defn- escape-string [value]
  (str "\""
       (apply str
              (map (fn [ch]
                     (case ch
                       \" "\\\""
                       \\ "\\\\"
                       \newline "\\n"
                       \return "\\r"
                       \tab "\\t"
                       \backspace "\\b"
                       \formfeed "\\f"
                       ;; C# treats Unicode line/paragraph separators as real
                       ;; newlines inside ordinary string literals.  Escaping
                       ;; every non-ASCII UTF-16 code unit also keeps control
                       ;; characters and source encoding deterministic.
                       (if (or (< (int ch) 32) (> (int ch) 126))
                         (format "\\u%04X" (int ch))
                         (str ch))))
                   value))
       "\""))

(defn- escape-char [value]
  (let [ch (char value)]
    (str "'"
         (case ch
           \' "\\'"
           \\ "\\\\"
           \newline "\\n"
           \return "\\r"
           \tab "\\t"
           \backspace "\\b"
           \formfeed "\\f"
           (if (or (< (int ch) 32) (> (int ch) 126))
             (format "\\u%04X" (int ch))
             (str ch)))
         "'")))

(defn- literal-text [value]
  (cond
    ;; Java permits null at any reference-typed use site. The postfix
    ;; suppression preserves that runtime value without inventing a nullable
    ;; contract where the resolved Java declaration is non-null.
    (nil? value) "null!"
    (string? value) (escape-string value)
    (instance? Character value) (escape-char value)
    (instance? Boolean value) (if value "true" "false")
    (instance? Long value) (str value "L")
    (instance? Float value) (str value "F")
    (instance? Double value)
    (cond
      (Double/isNaN value) "double.NaN"
      (= Double/POSITIVE_INFINITY value) "double.PositiveInfinity"
      (= Double/NEGATIVE_INFINITY value) "double.NegativeInfinity"
      :else (str value "D"))
    :else (str value)))

(defn- char-literal-context? [^CtLiteral element]
  (when (.isParentInitialized element)
    (let [parent (.getParent element)
          expected-type
          (cond
            (instance? CtField parent) (.getType ^CtField parent)
            (instance? CtLocalVariable parent) (.getType ^CtLocalVariable parent)
            (instance? CtNewArray parent)
            (let [array-type (.getType ^CtNewArray parent)]
              (when (.isArray array-type) (.getComponentType array-type)))
            :else nil)]
      (= "char" (some-> expected-type .getQualifiedName)))))

(defn- null-type-parameter-return? [^CtLiteral element]
  (when (nil? (.getValue element))
    (loop [current (when (.isParentInitialized element) (.getParent element))]
      (cond
        (nil? current) false
        (instance? CtLambda current) false
        (instance? CtMethod current)
        (instance? CtTypeParameterReference (.getType ^CtMethod current))
        :else (recur (when (.isParentInitialized ^CtElement current)
                       (.getParent ^CtElement current)))))))

(defn- invariant-generic-shape? [^CtTypeReference source-type ^CtTypeReference target-type]
  (if (and (.isArray source-type) (.isArray target-type))
    (invariant-generic-shape? (.getComponentType source-type) (.getComponentType target-type))
    (let [source-name (.getQualifiedName source-type)
          target-name (.getQualifiedName target-type)
          known-generic-subtype?
          (or (and (= "org.graalvm.collections.EconomicMap" source-name)
                   (= "org.graalvm.collections.UnmodifiableEconomicMap" target-name))
              (and (str/starts-with? (or source-name "") "org.pkl.core.util.json.")
                   (= "org.pkl.core.util.json.JsonHandler" target-name))
              (and (contains? #{"org.pkl.core.util.paguro.RrbTree$ImRrbt"
                                "org.pkl.core.util.paguro.RrbTree$MutRrbt"
                                "org.pkl.core.util.paguro.RrbTree.ImRrbt"
                                "org.pkl.core.util.paguro.RrbTree.MutRrbt"}
                              source-name)
                   (= "org.pkl.core.util.paguro.RrbTree" target-name)))]
      (and (seq (.getActualTypeArguments target-type))
         (or (= source-name target-name)
             known-generic-subtype?
             (try (.isSubtypeOf source-type target-type) (catch Exception _ false)))
         (not= (mapv str (.getActualTypeArguments source-type))
               (mapv str (.getActualTypeArguments target-type)))))))

(defn- coerce-initializer [services ^CtTypeReference target-type source node]
  (let [source-type (some-> ^CtExpression source .getType)]
    (if-not (pkl-core-element? source)
      node
      (cond
      (and (.isPrimitive target-type) source-type (not (.isPrimitive ^CtTypeReference source-type)))
      (sequence-node [(raw "(") ((:type-node services) target-type)
                      (raw ")((object)(") node (raw ")!)")])

      (and source-type
           (not (.isPrimitive target-type))
           (not (.isPrimitive ^CtTypeReference source-type))
           (not= "java.lang.Object" (.getQualifiedName target-type))
           (or (= "java.lang.Object" (.getQualifiedName ^CtTypeReference source-type))
               (invariant-generic-shape? source-type target-type)))
      (sequence-node [(raw "((") ((:type-node services) target-type)
                      (raw ")((object)(") node (raw ")))!")])

        :else node))))

(def ^:private binary-operators
  {"AND" ["&&" 30]
   "OR" ["||" 20]
   "EQ" ["==" 40]
   "NE" ["!=" 40]
   "LT" ["<" 50]
   "LE" ["<=" 50]
   "GT" [">" 50]
   "GE" [">=" 50]
   "PLUS" ["+" 60]
   "MINUS" ["-" 60]
   "MUL" ["*" 70]
   "DIV" ["/" 70]
   "MOD" ["%" 70]
   "BITAND" ["&" 37]
   "BITXOR" ["^" 36]
   "BITOR" ["|" 35]
   "SL" ["<<" 55]
   "SR" [">>" 55]
   "USR" [">>>" 55]})

(defn- primitive-expression? [^CtExpression expression]
  (boolean (some-> expression .getType .isPrimitive)))

(defn- string-expression? [^CtExpression expression]
  (= "java.lang.String" (some-> expression .getType .getQualifiedName)))

(defn- pclass-info-expression? [^CtExpression expression]
  (= "org.pkl.core.PClassInfo" (some-> expression .getType .getQualifiedName)))

(defn- instanceof-type-reference [expression]
  (cond
    (instance? CtTypeAccess expression) (.getAccessedType ^CtTypeAccess expression)
    (instance? CtTypePattern expression) (some-> ^CtTypePattern expression .getVariable .getType)
    :else nil))

(defn- binary-node [services ^CtBinaryOperator element children]
  (let [kind (str (.getKind element))
        left-expression (.getLeftHandOperand element)
        right-expression (.getRightHandOperand element)
        left (child-node children left-expression)
        right (child-node children right-expression)
        right (if (contains? #{"SL" "SR" "USR"} kind)
                (sequence-node [(raw "(int)(") right (raw ")")])
                right)]
    (cond
      (and (= kind "INSTANCEOF")
           (let [reference (instanceof-type-reference right-expression)]
             (and reference
                  (= "java.util.Set" (.getQualifiedName ^CtTypeReference reference))
                  (empty? (.getActualTypeArguments ^CtTypeReference reference))
                  (not (instance? CtTypePattern right-expression)))))
      (invoke (raw "global::Vibeformer.Runtime.JavaCompat.IsSet") [left])

      (= kind "INSTANCEOF")
      (csharp/binary "is" 40 left right)

      (and (contains? #{"EQ" "NE"} kind)
           (pclass-info-expression? left-expression)
           (pclass-info-expression? right-expression))
      (let [equals (invoke (raw "global::System.Object.Equals")
                           [(invoke (member left "AsObject") [])
                            (invoke (member right "AsObject") [])])]
        (if (= kind "NE") (csharp/prefix "!" equals) equals))

      (and (contains? #{"EQ" "NE"} kind)
           (not (or (primitive-expression? left-expression)
                    (primitive-expression? right-expression))))
      (let [equals (invoke (raw "global::System.Object.ReferenceEquals") [left right])]
        (if (= kind "NE") (csharp/prefix "!" equals) equals))

      (and (= kind "PLUS") (string-expression? element))
      (invoke (raw "global::Vibeformer.Runtime.JavaCompat.Concat") [left right])

      :else
      (if-let [[operator precedence] (get binary-operators kind)]
        (csharp/binary operator precedence left right)
        (throw (ex-info "Unsupported Java binary operator"
                        {:kind :unsupported-java-binary-operator :operator kind
                         :source (spoon/source-location element)}))))))

(defn- unary-node [^CtUnaryOperator element operand]
  (let [operand (if (and (= "NOT" (str (.getKind element)))
                         (nullable-expression? (.getOperand element)))
                  (non-null-node (.getOperand element) operand)
                  operand)]
  (case (str (.getKind element))
    "NEG" (csharp/prefix "-" operand)
    "POS" (csharp/prefix "+" operand)
    "COMPL" (csharp/prefix "~" operand)
    "NOT" (csharp/prefix "!" operand)
    "PREINC" (csharp/prefix "++" operand)
    "PREDEC" (csharp/prefix "--" operand)
    "POSTINC" (sequence-node [operand (raw "++")])
    "POSTDEC" (sequence-node [operand (raw "--")])
    (throw (ex-info "Unsupported Java unary operator"
                    {:kind :unsupported-java-unary-operator
                     :operator (str (.getKind element))
                     :source (spoon/source-location element)})))))

(defn- occurrence [context element]
  (.get ^IdentityHashMap (:occurrence-index context) element))

(defn- executable-key [context ^CtInvocation invocation]
  (:key (occurrence context (.getExecutable invocation))))

(defn- type-parameter-names [^CtTypeReference reference]
  (cond
    (nil? reference) #{}
    (or (instance? CtTypeParameterReference reference)
        (and (nil? (.getPackage reference))
             (re-matches #"[A-Z][A-Z0-9]*" (.getSimpleName reference))))
    #{(.getSimpleName reference)}
    (.isArray reference) (type-parameter-names (.getComponentType reference))
    :else (into #{} (mapcat type-parameter-names (.getActualTypeArguments reference)))))

(defn- lexical-type-parameter-names [^CtElement element]
  (loop [current element names #{}]
    (if-not current
      names
      (recur (when (.isParentInitialized ^CtElement current)
               (.getParent ^CtElement current))
             (if (instance? CtFormalTypeDeclarer current)
               (into names (map #(.getSimpleName %) (.getFormalCtTypeParameters
                                                     ^CtFormalTypeDeclarer current)))
               names)))))

(defn- emittable-type-reference? [^CtElement element ^CtTypeReference reference]
  (let [lexical (lexical-type-parameter-names element)]
    (every? #(contains? lexical %) (type-parameter-names reference))))

(defn- enclosing-method-return-type [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtMethod current) (.getType ^CtMethod current)
      :else (recur (when (.isParentInitialized ^CtElement current)
                     (.getParent ^CtElement current))))))

(defn- nearest-type-parameter-name [^CtElement element]
  (loop [current element]
    (when current
      (let [parameters (when (instance? CtFormalTypeDeclarer current)
                         (seq (.getFormalCtTypeParameters ^CtFormalTypeDeclarer current)))]
        (if parameters
          (.getSimpleName (first parameters))
          (recur (when (.isParentInitialized ^CtElement current)
                   (.getParent ^CtElement current))))))))

(defn- inferred-method-type-arguments [services ^CtInvocation element]
  (when-let [declaration (.getExecutableDeclaration (.getExecutable element))]
    (when (and (instance? CtMethod declaration)
               (not (and (= "accept" (.getSimpleName ^CtMethod declaration))
                         (= "org.pkl.core.Value"
                            (some-> ^CtMethod declaration .getDeclaringType .getQualifiedName)))))
      (let [arity (count (.getFormalCtTypeParameters ^CtMethod declaration))
            explicit (vec (.getActualTypeArguments (.getExecutable element)))
            returned (vec (.getActualTypeArguments (.getType element)))
            arguments (cond
                        (= arity (count explicit)) explicit
                        (= arity (count returned)) returned
                        :else nil)]
        (when (and (seq arguments)
                   (every? #(emittable-type-reference? element %) arguments))
          (mapv #((:type-node services) %) arguments))))))

(defn- normal-invocation
  ([services element children] (normal-invocation services element children false))
  ([services element children property?]
   (let [target-element (.getTarget ^CtInvocation element)
         target (when target-element
                  (let [node (child-node children target-element)]
                    (if (nullable-expression? target-element)
                      (non-null-node target-element node)
                      node)))
         executable-base (child-node children (.getExecutable ^CtInvocation element))
         executable (if-let [arguments (inferred-method-type-arguments services element)]
                      (csharp/generic-name executable-base arguments)
                      executable-base)
         callable (if target (member target (:text (csharp/render executable))) executable)]
     (if property?
       callable
       (invoke callable (children-nodes children (.getArguments ^CtInvocation element)))))))

(defn- class-literal? [^CtFieldRead element]
  (and (= "class" (some-> element .getVariable .getSimpleName))
       (instance? CtTypeAccess (.getTarget element))))

(defn- compat-call [name arguments]
  (invoke (raw (str "global::Vibeformer.Runtime.JavaCompat." name)) arguments))

(defn- expected-nullable-collection? [^CtInvocation invocation]
  (let [parent (when (.isParentInitialized invocation) (.getParent invocation))]
    (when (or (instance? CtConstructorCall parent) (instance? CtInvocation parent))
      (let [arguments (vec (.getArguments parent))
            index (first (keep-indexed #(when (identical? invocation %2) %1) arguments))
            executable (.getExecutable parent)
            declaration (.getExecutableDeclaration executable)
            parameters (when declaration (vec (.getParameters declaration)))
            parameter (when (and index (< index (count parameters))) (nth parameters index))]
        (let [actuals (when parameter
                        (vec (.getActualTypeArguments (.getType ^CtParameter parameter))))]
          (boolean (and (= 1 (count actuals)) (nullable-type? (first actuals)))))))))

(defn- collection-element-type-node [services ^CtInvocation invocation]
  (let [arguments (vec (.getActualTypeArguments (.getType invocation)))]
    (when-not (= 1 (count arguments))
      (throw (ex-info "Resolved collection factory has no single element type"
                      {:kind :unsupported-collection-factory-type
                       :source (spoon/source-location invocation)
                       :type (some-> invocation .getType .getQualifiedName)})))
    (let [type-node ((:type-node services) (first arguments))
          nullable-element? (or (some nullable-expression? (.getArguments invocation))
                                (expected-nullable-collection? invocation))]
      (if (and nullable-element? (not (.isPrimitive ^CtTypeReference (first arguments))))
        (sequence-node [type-node (raw "?")])
        type-node))))

(defn- generic-compat-call [services invocation name arguments]
  (let [element-type (collection-element-type-node services invocation)
        source-arguments (vec (.getArguments ^CtInvocation invocation))
        arguments (mapv (fn [source argument]
                          (if (and (instance? CtLiteral source)
                                   (nil? (.getValue ^CtLiteral source)))
                            (sequence-node [(raw "(") element-type (raw ")") argument])
                            argument))
                        source-arguments arguments)]
    (invoke (csharp/generic-name
             (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
             [element-type])
            arguments)))

(defn- result-type-call [services ^CtInvocation invocation name arguments]
  (invoke (member ((:type-node services) (.getType invocation)) name) arguments))

(defn- result-value-generic-compat-call [services ^CtInvocation invocation name arguments]
  (invoke (csharp/generic-name
           (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
           [((:type-node services) (.getType invocation))])
          arguments))

(defn- array-result-generic-compat-call [services ^CtInvocation invocation name arguments]
  (let [array-type (.getType invocation)
        component (if (.isArray array-type) (.getComponentType array-type) array-type)]
    (invoke (csharp/generic-name
             (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
             [((:type-node services) component)])
            arguments)))

(defn- argument-array-generic-compat-call [services ^CtInvocation invocation name arguments]
  (let [array-type (some-> invocation .getArguments first .getType)
        component (if (and array-type (.isArray ^CtTypeReference array-type))
                    (.getComponentType ^CtTypeReference array-type)
                    array-type)]
    (invoke (csharp/generic-name
             (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
             [((:type-node services) component)])
            arguments)))

(defn- source-array-generic-compat-call [services ^CtInvocation invocation name arguments]
  (let [array-argument (some (fn [^CtExpression argument]
                               (let [type (.getType argument)]
                                 (when (and type (.isArray ^CtTypeReference type)) type)))
                             (.getArguments invocation))
        component (some-> ^CtTypeReference array-argument .getComponentType)]
    (invoke (csharp/generic-name
             (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
             [((:type-node services) component)])
            arguments)))

(defn- result-generic-compat-call [services invocation name arguments]
  (let [element-type (collection-element-type-node services invocation)]
    (invoke (csharp/generic-name
             (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
             [element-type])
            arguments)))

(defn- result-generic-arguments-compat-call [services ^CtInvocation invocation name arguments]
  (let [type-arguments (mapv #((:type-node services) %)
                             (.getActualTypeArguments (.getType invocation)))]
    (invoke (csharp/generic-name
             (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
             type-arguments)
            arguments)))

(defn- record-component-invocation-name [services ^CtInvocation invocation resolved]
  (let [reference (.getExecutable invocation)
        target-element (.getTarget invocation)
        declaration (or (:declaration resolved)
                        (.getExecutableDeclaration reference))
        declared-owner (or (cond
                    (instance? CtRecordComponent declaration)
                    (enclosing-type declaration)
                    (instance? CtMethod declaration)
                    (.getDeclaringType ^CtMethod declaration)
                    :else nil)
                  (some-> reference .getDeclaringType .getTypeDeclaration))
        owner (or declared-owner (some-> target-element .getType .getTypeDeclaration))
        simple-name (.getSimpleName reference)
        component
        (cond
          (instance? CtRecordComponent declaration) declaration
          (instance? CtRecord owner)
          (some #(when (= simple-name (.getSimpleName ^CtRecordComponent %)) %)
                (.getRecordComponents ^CtRecord owner))
          :else nil)]
    (when component
      ((:record-component-name services) owner component))))

(defn- known-record-property-name [services ^CtInvocation invocation target-element target]
  (when (and target-element
             (not (instance? CtTypeAccess target-element))
             (empty? (.getArguments invocation)))
    (let [owner-name (or (some-> target-element .getType .getQualifiedName) "")
          simple-name (.getSimpleName (.getExecutable invocation))
          file (or (:file (spoon/source-location invocation)) "")
          platform? (or (= "org.pkl.core.Platform" owner-name)
                        (str/starts-with? owner-name "org.pkl.core.Platform$")
                        (str/starts-with? owner-name "org.pkl.core.Platform.")
                        (str/ends-with? file "PlatformNodes.java"))
          release? (or (= "org.pkl.core.Release" owner-name)
                       (str/starts-with? owner-name "org.pkl.core.Release$")
                       (str/starts-with? owner-name "org.pkl.core.Release.")
                       (str/ends-with? file "ReleaseNodes.java"))
          factory-value? (= "value" (:text (csharp/render target)))
          platform-names #{"language" "runtime" "virtualMachine" "operatingSystem"
                           "processor" "version" "name" "architecture"}
          release-names #{"version" "os" "flavor" "versionInfo" "commitId" "sourceCode"
                          "documentation" "standardLibrary" "homepage" "modules"}
          conflict? (or (and platform?
                             (contains? #{"language" "runtime" "virtualMachine"
                                          "operatingSystem" "processor"} simple-name))
                        (and release?
                             (contains? #{"sourceCode" "documentation" "standardLibrary"}
                                        simple-name)))]
      (when (or (and platform? (contains? platform-names simple-name))
                (and release? (contains? release-names simple-name))
                (and factory-value?
                     (contains? #{"version" "name" "architecture" "homepage"
                                  "versionInfo" "commitId"}
                                simple-name)))
        (str ((:pascal services) simple-name) (when conflict? "Value"))))))

(defn- invariant-argument-cast [services source ^CtTypeReference parameter-type node]
  (let [source-type (some-> ^CtExpression source .getType)
        parameter-type parameter-type
        parameter-open? (seq (type-parameter-names parameter-type))
        parameter-emittable? (emittable-type-reference? source parameter-type)]
    (if-not (pkl-core-element? source)
      node
      (cond
      ;; A Java boxed-value cast can feed a primitive parameter directly. The
      ;; cast is represented as Nullable<T> in C# so Java null is preserved,
      ;; but the primitive call site has the same unboxing requirement as
      ;; Java and therefore must read Value.
      (and (.isPrimitive parameter-type)
           (boxed-value-cast? source)
           (not (str/ends-with? (:text (csharp/render node)) ".Value")))
      (member node "Value")

      (and (= "java.lang.Iterable" (some-> parameter-type .getQualifiedName))
           (contains? #{"org.pkl.core.runtime.VmIntSeq" "org.pkl.core.runtime.VmBytes"}
                      (some-> source-type .getQualifiedName)))
      (compat-call "BoxValues" [node])

      (and (= "java.lang.CharSequence" (some-> parameter-type .getQualifiedName))
           (= "java.lang.StringBuilder" (some-> source-type .getQualifiedName)))
      (invoke (member node "ToString") [])

      (and (instance? CtInvocation source)
           (= "getProperties" (some-> ^CtInvocation source .getExecutable .getSimpleName))
           (= "java.lang.System" (some-> ^CtInvocation source .getExecutable .getDeclaringType
                                          .getQualifiedName)))
      node

      (str/includes? (:text (csharp/render node)) "JavaCompat.GetProperties()")
      node

      (and (= "org.pkl.core.PClassInfo" (some-> parameter-type .getQualifiedName))
           (= "org.pkl.core.PClassInfo" (some-> source-type .getQualifiedName))
           (not= (mapv str (.getActualTypeArguments source-type))
                 (mapv str (.getActualTypeArguments parameter-type))))
      (invoke (member node "AsObject") [])

      (and (= "java.util.List" (some-> parameter-type .getQualifiedName))
           (= "java.util.List" (some-> source-type .getQualifiedName))
           (= 1 (count (.getActualTypeArguments parameter-type)))
           (= 1 (count (.getActualTypeArguments ^CtTypeReference source-type)))
           (not= (mapv str (.getActualTypeArguments ^CtTypeReference source-type))
                 (mapv str (.getActualTypeArguments parameter-type))))
      (invoke (csharp/generic-name
               (raw "global::Vibeformer.Runtime.JavaCompat.CastList")
               [((:type-node services) (first (.getActualTypeArguments parameter-type)))])
              [node])

      (and (= "org.pkl.core.util.json.JsonHandler"
              (some-> parameter-type .getQualifiedName))
           (str/ends-with? (or (some-> source-type .getQualifiedName) "") "Handler"))
      (invoke (raw "global::Pkl.Core.Util.Json.JsonHandlerBridge.Erase") [node])

      (and (.isArray parameter-type)
           source-type (.isArray ^CtTypeReference source-type)
           (let [source-component (.getComponentType ^CtTypeReference source-type)
                 target-component (.getComponentType parameter-type)]
             (and (str/includes? (or (.getQualifiedName source-component) "") "RrbTree")
                  (str/includes? (or (.getQualifiedName target-component) "") "RrbTree"))))
      (sequence-node [(raw "((") ((:type-node services) parameter-type)
                      (raw ")((object)(") node (raw ")))!")])

      (and source-type parameter-type
             (not (.isPrimitive ^CtTypeReference source-type))
             (not (.isPrimitive ^CtTypeReference parameter-type))
             parameter-emittable?
             (or (and parameter-open?
                      (= "java.lang.Object" (.getQualifiedName ^CtTypeReference source-type)))
                 (invariant-generic-shape? source-type parameter-type)))
      (sequence-node [(raw "((") ((:type-node services) parameter-type)
                      (raw ")((object)(") node (raw ")))!")])
        :else node))))

(defn- null-tolerant-invocation-argument? [key]
  (or (contains? #{"executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)"
                   "executable:java.util.Objects#hash(java.lang.Object[])"
                   "executable:java.util.Objects#hashCode(java.lang.Object)"}
                 key)
      ;; Object.equals(null) is defined and must not force nullable boxed
      ;; primitives through Nullable<T>.Value before the call.
      (str/ends-with? key "#equals(java.lang.Object)")))

(defn- invocation-node [context services ^CtInvocation element children]
  (let [key (executable-key context element)
        target-element (.getTarget element)
        target (when target-element
                 (let [node (child-node children target-element)]
                   (if (nullable-expression? target-element)
                     (non-null-node target-element node)
                     node)))
        parameters (vec (.getParameters (.getExecutable element)))
        parameter-declarations (some-> (.getExecutableDeclaration (.getExecutable element))
                                       .getParameters vec)
        args (mapv (fn [index source node]
                     (let [parameter (when (< index (count parameters))
                                       (nth parameters index))
                           parameter-declaration (when (and parameter-declarations
                                                            (< index (count parameter-declarations)))
                                                   (nth parameter-declarations index))
                           node (if (and (nullable-expression? source)
                                         (not (null-tolerant-invocation-argument? key))
                                         (not (and parameter
                                                   (nullable-parameter? parameter
                                                                        parameter-declaration))))
                                  (non-null-node source node)
                                  node)]
                       (if parameter
                         (invariant-argument-cast services source parameter node)
                         node)))
                   (range)
                   (.getArguments element)
                   (children-nodes children (.getArguments element)))
        argc (count args)
        arg #(nth args %)
        call-member #(invoke (member target %) args)
        node
        (case key
          "executable:java.lang.CharSequence#isEmpty()" (csharp/binary "==" 40 (member target "Length") (raw "0"))
          "executable:java.lang.CharSequence#charAt(int)" (sequence-node [target (raw "[") (arg 0) (raw "]")])
          "executable:java.lang.CharSequence#length()" (member target "Length")
          "executable:java.lang.String#split(java.lang.String)"
          (compat-call "StringSplit" [target (arg 0) (raw "0")])
          "executable:java.lang.String#split(java.lang.String,int)"
          (compat-call "StringSplit" (into [target] args))
          "executable:java.lang.Character#isDigit(int)" (compat-call "IsDigit" args)
          "executable:java.lang.Character#isLetterOrDigit(int)" (compat-call "IsLetterOrDigit" args)
          "executable:java.lang.Character#isUnicodeIdentifierPart(int)" (compat-call "IsUnicodeIdentifierPart" args)
          "executable:java.lang.Character#isUnicodeIdentifierStart(int)" (compat-call "IsUnicodeIdentifierStart" args)
          "executable:java.lang.Character#toString(int)" (compat-call "CodePointToString" args)
          "executable:java.lang.Character#getType(int)" (compat-call "CharacterType" args)
          "executable:java.lang.Character#toUpperCase(int)" (compat-call "ToUpperCase" args)
          "executable:java.lang.Class#getSimpleName()" (member target "Name")
          "executable:java.lang.Class#getName()" (member target "FullName")
          "executable:java.lang.Class#getTypeName()" (member target "FullName")
          "executable:java.lang.Class#getClassLoader()" (member target "Assembly")
          "executable:java.lang.Class#getResource(java.lang.String)" (compat-call "ClassGetResource" (into [target] args))
          "executable:java.lang.Class#getResourceAsStream(java.lang.String)" (compat-call "ClassGetResourceAsStream" (into [target] args))
          "executable:java.lang.Class#getAnnotation(java.lang.Class)"
          (result-value-generic-compat-call services element "ClassGetAnnotation" (into [target] args))
          "executable:java.lang.Class#cast(java.lang.Object)" (result-value-generic-compat-call services element "ClassCast" (into [target] args))
          "executable:java.lang.Enum#name()" (compat-call "EnumName" [target])
          "executable:java.lang.Enum#ordinal()" (compat-call "EnumOrdinal" [target])
          "executable:java.lang.Boolean#booleanValue()" (member target "Value")
          "executable:java.time.LocalDateTime#of(int,java.time.Month,int,int,int)"
          (invoke (raw "new global::System.DateTime") (conj args (raw "0")))
          "executable:java.lang.Integer#parseInt(java.lang.String)" (compat-call "ParseInt" args)
          "executable:java.lang.Integer#parseInt(java.lang.String,int)" (compat-call "ParseInt" args)
          "executable:java.lang.Long#parseLong(java.lang.String)" (compat-call "ParseLong" args)
          "executable:java.lang.Long#parseLong(java.lang.String,int)" (compat-call "ParseLong" args)
          "executable:java.lang.Long#parseLong(java.lang.CharSequence,int,int,int)" (compat-call "ParseLong" args)
          "executable:java.lang.Long#numberOfLeadingZeros(long)" (compat-call "LongLeadingZeros" args)
          "executable:java.lang.Long#numberOfTrailingZeros(long)" (compat-call "LongTrailingZeros" args)
          "executable:java.lang.Long#toUnsignedString(long,int)" (compat-call "ToUnsignedString" args)
          "executable:java.lang.Long#intValue()"
          (sequence-node [(raw "unchecked((int)(") target (raw "))")])
          "executable:java.lang.Long#byteValue()"
          (sequence-node [(raw "unchecked((sbyte)(") target (raw "))")])
          "executable:java.lang.Long#shortValue()"
          (sequence-node [(raw "unchecked((short)(") target (raw "))")])
          "executable:java.lang.Long#toString(long)" (compat-call "StringValueOf" args)
          "executable:java.lang.Long#toString(long,int)" (compat-call "ToStringRadix" args)
          "executable:java.lang.Integer#toString(int)" (compat-call "StringValueOf" args)
          "executable:java.lang.Integer#toString(int,int)" (compat-call "ToStringRadix" args)
          "executable:java.lang.Integer#longValue()" (sequence-node [(raw "(long)") target])
          "executable:java.lang.Float#doubleValue()" (sequence-node [(raw "(double)") target])
          "executable:java.lang.Long#compare(long,long)" (compat-call "CompareLong" args)
          "executable:java.lang.Double#compare(double,double)" (compat-call "CompareDouble" args)
          "executable:java.lang.Double#hashCode(double)" (invoke (member (arg 0) "GetHashCode") [])
          "executable:java.lang.Double#toString(double)" (compat-call "StringValueOf" args)
          "executable:java.lang.Double#parseDouble(java.lang.String)" (compat-call "ParseDouble" args)
          "executable:java.lang.Double#valueOf(double)" (arg 0)
          "executable:java.lang.Double#valueOf(java.lang.String)" (compat-call "ParseDouble" args)
          "executable:java.lang.Long#valueOf(long)" (arg 0)
          "executable:java.lang.Long#valueOf(java.lang.String)" (compat-call "ParseLong" args)
          "executable:java.lang.Integer#valueOf(int)" (arg 0)
          "executable:java.lang.Integer#valueOf(java.lang.String)" (compat-call "ParseInt" args)
          "executable:java.lang.Long#hashCode(long)" (invoke (member (arg 0) "GetHashCode") [])
          "executable:java.lang.Double#isNaN()" (invoke (raw "global::System.Double.IsNaN") [target])
          "executable:java.lang.Double#isInfinite()" (invoke (raw "global::System.Double.IsInfinity") [target])
          "executable:java.lang.Double#isInfinite(double)" (invoke (raw "global::System.Double.IsInfinity") args)
          "executable:java.lang.Double#doubleToRawLongBits(double)" (invoke (raw "global::System.BitConverter.DoubleToInt64Bits") args)
          "executable:java.lang.Character#isWhitespace(char)" (invoke (raw "global::System.Char.IsWhiteSpace") args)
          "executable:java.lang.Character#isBmpCodePoint(int)" (compat-call "IsBmpCodePoint" args)
          "executable:java.lang.Character#isValidCodePoint(int)" (compat-call "IsValidCodePoint" args)
          "executable:java.lang.Character#isUpperCase(int)" (compat-call "IsUpperCase" args)
          "executable:java.lang.Character#isUpperCase(char)" (compat-call "IsUpperCase" args)
          "executable:java.lang.Character#toTitleCase(int)" (compat-call "ToTitleCase" args)
          "executable:java.lang.Integer#numberOfLeadingZeros(int)" (compat-call "IntLeadingZeros" args)
          "executable:java.lang.Integer#signum(int)" (compat-call "Signum" args)
          "executable:java.lang.Long#signum(long)" (compat-call "Signum" args)
          "executable:java.lang.Integer#toHexString(int)" (compat-call "ToHexString" args)
          "executable:java.lang.Integer#compare(int,int)" (compat-call "CompareInt" args)
          "executable:java.lang.Byte#toUnsignedInt(byte)" (compat-call "ToUnsignedInt" args)
          "executable:java.lang.Byte#toUnsignedLong(byte)" (compat-call "ToUnsignedLong" args)
          "executable:java.lang.Math#round(double)" (compat-call "MathRound" args)
          "executable:java.lang.Math#round(float)" (compat-call "MathRoundFloat" args)
          "executable:java.lang.Math#ceil(double)" (invoke (raw "global::System.Math.Ceiling") args)
          "executable:java.lang.Math#min(long,long)" (invoke (raw "global::System.Math.Min") args)
          "executable:java.lang.Math#rint(double)" (invoke (raw "global::System.Math.Round") args)
          "executable:java.lang.Math#addExact(long,long)" (compat-call "AddExact" args)
          "executable:java.lang.Math#multiplyExact(long,long)" (compat-call "MultiplyExact" args)
          "executable:java.lang.Math#multiplyExact(int,int)" (compat-call "MultiplyExactInt" args)
          "executable:java.lang.Math#signum(double)" (compat-call "SignumDouble" args)
          "executable:java.lang.Math#subtractExact(long,long)" (compat-call "SubtractExact" args)
          "executable:java.lang.Math#negateExact(long)" (compat-call "NegateExact" args)
          "executable:java.lang.Math#toIntExact(long)" (compat-call "ToIntExact" args)
          "executable:java.lang.Math#addExact(int,int)" (compat-call "AddExactInt" args)
          "executable:java.lang.Math#getExponent(double)" (compat-call "GetExponent" args)
          "executable:java.lang.StrictMath#rint(double)" (invoke (raw "global::System.Math.Round") args)
          "executable:java.lang.StrictMath#ceil(double)" (invoke (raw "global::System.Math.Ceiling") args)
          "executable:java.lang.StrictMath#min(long,long)" (invoke (raw "global::System.Math.Min") args)
          "executable:java.lang.StrictMath#addExact(long,long)" (compat-call "AddExact" args)
          "executable:java.lang.StrictMath#multiplyExact(long,long)" (compat-call "MultiplyExact" args)
          "executable:java.lang.StrictMath#multiplyExact(int,int)" (compat-call "MultiplyExactInt" args)
          "executable:java.lang.StrictMath#signum(double)" (compat-call "SignumDouble" args)
          "executable:java.lang.StrictMath#subtractExact(long,long)" (compat-call "SubtractExact" args)
          "executable:java.lang.StrictMath#negateExact(long)" (compat-call "NegateExact" args)
          "executable:java.lang.StrictMath#toIntExact(long)" (compat-call "ToIntExact" args)
          "executable:java.lang.StrictMath#getExponent(double)" (compat-call "GetExponent" args)
          "executable:java.lang.StrictMath#pow(double,double)" (compat-call "StrictPow" args)
          "executable:java.lang.invoke.MethodHandles#lookup()" (raw "new object()")
          "executable:java.lang.invoke.VarHandle#storeStoreFence()" (invoke (raw "global::System.Threading.Thread.MemoryBarrier") [])
          "executable:java.lang.Object#getClass()" (call-member "GetType")
          "executable:java.lang.Object#hashCode()" (call-member "GetHashCode")
          "executable:java.lang.AutoCloseable#close()" (call-member "Dispose")
          "executable:java.io.Closeable#close()" (call-member "Dispose")
          "executable:java.io.ByteArrayOutputStream#toByteArray()" (compat-call "ToSignedBytes" [target])
          "executable:java.lang.Runnable#run()" (invoke target [])
          "executable:java.lang.String#charAt(int)" (sequence-node [target (raw "[") (arg 0) (raw "]")])
          "executable:java.lang.String#codePointAt(int)" (compat-call "CodePointAt" (into [target] args))
          "executable:java.lang.String#codePointCount(int,int)" (compat-call "CodePointCount" (into [target] args))
          "executable:java.lang.String#codePoints()" (compat-call "CodePoints" [target])
          "executable:java.lang.String#equalsIgnoreCase(java.lang.String)" (compat-call "EqualsIgnoreCase" (into [target] args))
          "executable:java.lang.String#formatted(java.lang.Object[])" (compat-call "Formatted" (into [target] args))
          "executable:java.lang.String#indexOf(int,int)" (compat-call "IndexOfCodePoint" (into [target] args))
          "executable:java.lang.String#isBlank()" (invoke (raw "global::System.String.IsNullOrWhiteSpace") [target])
          "executable:java.lang.String#isEmpty()" (csharp/binary "==" 40 (member target "Length") (raw "0"))
          "executable:java.lang.String#hashCode()" (compat-call "StringHashCode" [target])
          "executable:java.lang.String#length()" (member target "Length")
          "executable:java.lang.String#repeat(int)" (compat-call "Repeat" (into [target] args))
          "executable:java.lang.String#concat(java.lang.String)" (compat-call "Concat" [target (arg 0)])
          "executable:java.lang.String#regionMatches(boolean,int,java.lang.String,int,int)" (compat-call "RegionMatches" (into [target] args))
          "executable:java.lang.String#startsWith(java.lang.String)" (compat-call "StartsWith" (into [target] args))
          "executable:java.lang.String#substring(int,int)" (compat-call "Substring" (into [target] args))
          "executable:java.lang.String#toCharArray()" (invoke (member target "ToCharArray") [])
          "executable:java.lang.String#getBytes(java.nio.charset.Charset)" (compat-call "StringGetBytes" (into [target] args))
          "executable:java.lang.String#getBytes(java.lang.String)" (compat-call "StringGetBytes" (into [target] args))
          "executable:java.lang.String#getBytes()" (compat-call "StringGetBytes" [target (raw "global::System.Text.Encoding.UTF8")])
          "executable:java.lang.String#toLowerCase(java.util.Locale)" (invoke (member target "ToLowerInvariant") [])
          "executable:java.lang.String#lines()" (compat-call "StringLines" [target])
          "executable:java.lang.String#strip()" (invoke (member target "Trim") [])
          "executable:java.lang.String#toUpperCase(java.util.Locale)" (invoke (member target "ToUpperInvariant") [])
          "executable:java.lang.String#matches(java.lang.String)" (compat-call "StringMatches" (into [target] args))
          "executable:java.lang.String#replaceAll(java.lang.String,java.lang.String)" (compat-call "StringReplaceAll" (into [target] args))
          "executable:java.lang.String#valueOf(boolean)" (compat-call "StringValueOf" args)
          "executable:java.lang.String#valueOf(char)" (compat-call "StringValueOf" args)
          "executable:java.lang.String#valueOf(char[])" (compat-call "StringValueOf" args)
          "executable:java.lang.String#valueOf(double)" (compat-call "StringValueOf" args)
          "executable:java.lang.String#valueOf(float)" (compat-call "StringValueOf" args)
          "executable:java.lang.String#valueOf(int)" (compat-call "StringValueOf" args)
          "executable:java.lang.String#valueOf(java.lang.Object)" (compat-call "StringValueOf" args)
          "executable:java.lang.String#valueOf(long)" (compat-call "StringValueOf" args)
          "executable:java.lang.StringBuilder#append(char)" (call-member "Append")
          "executable:java.lang.StringBuilder#append(boolean)" (compat-call "AppendValue" (into [target] args))
          "executable:java.lang.StringBuilder#append(double)" (compat-call "AppendValue" (into [target] args))
          "executable:java.lang.StringBuilder#append(float)" (compat-call "AppendValue" (into [target] args))
          "executable:java.lang.StringBuilder#append(java.lang.Object)" (compat-call "AppendValue" (into [target] args))
          "executable:java.lang.StringBuilder#append(java.lang.CharSequence,int,int)" (compat-call "AppendRange" (into [target] args))
          "executable:java.lang.StringBuilder#append(java.lang.String)" (call-member "Append")
          "executable:java.lang.StringBuilder#reverse()" (compat-call "Reverse" [target])
          "executable:java.lang.AbstractStringBuilder#length()" (member target "Length")
          "executable:java.lang.StringBuilder#length()" (member target "Length")
          "executable:java.lang.AbstractStringBuilder#charAt(int)" (sequence-node [target (raw "[") (arg 0) (raw "]")])
          "executable:java.lang.StringBuilder#charAt(int)" (sequence-node [target (raw "[") (arg 0) (raw "]")])
          "executable:java.lang.AbstractStringBuilder#delete(int,int)" (compat-call "StringBuilderDelete" (into [target] args))
          "executable:java.lang.StringBuilder#delete(int,int)" (compat-call "StringBuilderDelete" (into [target] args))
          "executable:java.lang.AbstractStringBuilder#setLength(int)" (csharp/binary "=" 10 (member target "Length") (arg 0))
          "executable:java.lang.StringBuilder#setLength(int)" (csharp/binary "=" 10 (member target "Length") (arg 0))
          "executable:java.lang.StringBuilder#toString()" (call-member "ToString")
          "executable:java.lang.StringBuilder#appendCodePoint(int)"
          (compat-call "AppendCodePoint" (into [target] args))
          "executable:java.lang.String#compareTo(java.lang.String)"
          (compat-call "StringCompareTo" (into [target] args))
          "executable:org.pkl.core.runtime.Iterators#emptyTruffleIterator()"
          (result-generic-compat-call services element "EmptyIterator" [])
          "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)" (compat-call "ArrayCopy" args)
          "executable:java.lang.System#getenv()" (compat-call "GetEnvironment" [])
          "executable:java.lang.System#getProperties()" (compat-call "GetProperties" [])
          "executable:java.lang.System#getProperty(java.lang.String)" (compat-call "GetProperty" args)
          "executable:java.lang.System#setProperty(java.lang.String,java.lang.String)" (compat-call "SetProperty" args)
          "executable:java.lang.System#identityHashCode(java.lang.Object)" (compat-call "IdentityHashCode" args)
          "executable:java.lang.System#nanoTime()" (compat-call "NanoTime" [])
          "executable:java.lang.System#console()" (raw "(global::System.Console.IsInputRedirected ? null : new object())")
          "executable:java.net.URI#create(java.lang.String)" (compat-call "CreateUri" args)
          "executable:java.net.URI#getAuthority()" (compat-call "UriAuthority" [target])
          "executable:java.net.URI#getFragment()" (compat-call "UriFragment" [target])
          "executable:java.net.URI#getHost()" (compat-call "UriHost" [target])
          "executable:java.net.URI#getPath()" (compat-call "UriPath" [target])
          "executable:java.net.URI#getPort()" (compat-call "UriPort" [target])
          "executable:java.net.URI#getQuery()" (compat-call "UriQuery" [target])
          "executable:java.net.URI#getRawAuthority()" (compat-call "UriRawAuthority" [target])
          "executable:java.net.URI#getRawFragment()" (compat-call "UriRawFragment" [target])
          "executable:java.net.URI#getRawPath()" (compat-call "UriRawPath" [target])
          "executable:java.net.URI#getRawQuery()" (compat-call "UriRawQuery" [target])
          "executable:java.net.URI#getRawSchemeSpecificPart()" (compat-call "UriRawSchemeSpecificPart" [target])
          "executable:java.net.URI#getRawUserInfo()" (compat-call "UriRawUserInfo" [target])
          "executable:java.net.URI#getScheme()" (compat-call "UriScheme" [target])
          "executable:java.net.URI#getSchemeSpecificPart()" (compat-call "UriSchemeSpecificPart" [target])
          "executable:java.net.URI#getUserInfo()" (compat-call "UriUserInfo" [target])
          "executable:java.net.URI#isAbsolute()" (member target "IsAbsoluteUri")
          "executable:java.net.URI#normalize()" (compat-call "NormalizeUri" [target])
          "executable:java.net.URI#relativize(java.net.URI)" (compat-call "RelativizeUri" (into [target] args))
          "executable:java.net.URI#isOpaque()" (compat-call "UriIsOpaque" [target])
          "executable:java.net.URI#toString()" (compat-call "UriToString" [target])
          "executable:java.net.URI#toASCIIString()" (member target "AbsoluteUri")
          "executable:java.net.URI#toURL()" target
          "executable:java.net.URI#resolve(java.lang.String)" (compat-call "ResolveUri" (into [target] args))
          "executable:java.net.URI#resolve(java.net.URI)" (compat-call "ResolveUri" (into [target] args))
          "executable:java.net.URISyntaxException#getReason()" (member target "Message")
          "executable:java.net.URISyntaxException#getMessage()" (member target "Message")
          "executable:java.net.URISyntaxException#getInput()" (compat-call "UriSyntaxInput" [target])
          "executable:java.nio.charset.Charset#forName(java.lang.String)" (invoke (raw "global::System.Text.Encoding.GetEncoding") args)
          "executable:java.nio.charset.Charset#newDecoder()" (invoke (raw "new global::Pkl.Core.Runtime.JavaCharsetDecoder") [target])
          "executable:java.nio.charset.Charset#newEncoder()" (invoke (raw "new global::Pkl.Core.Runtime.JavaCharsetEncoder") [target])
          "executable:java.io.File#toPath()" target
          "executable:java.nio.file.Paths#get(java.net.URI)" (compat-call "PathOfUri" args)
          "executable:java.nio.file.Files#find(java.nio.file.Path,int,java.util.function.BiPredicate,java.nio.file.FileVisitOption[])"
          (compat-call "FindFiles" args)
          "executable:java.nio.file.attribute.BasicFileAttributes#isRegularFile()"
          (compat-call "IsRegularFile" [target])
          "executable:java.lang.Throwable#getMessage()" (member target "Message")
          "executable:java.lang.Throwable#getCause()" (member target "InnerException")
          "executable:java.lang.Exception#getCause()" (member target "InnerException")
          "executable:java.lang.Throwable#initCause(java.lang.Throwable)" (compat-call "InitCause" (into [target] args))
          "executable:java.lang.Throwable#getStackTrace()" (compat-call "GetStackTrace" [target])
          "executable:java.lang.Throwable#setStackTrace(java.lang.StackTraceElement[])"
          (compat-call "SetStackTrace" (into [target] args))
          "executable:java.lang.Throwable#printStackTrace()" (compat-call "PrintStackTrace" [target])
          "executable:java.util.Map$Entry#getKey()"
          (member target "Key")
          "executable:java.util.Map$Entry#getValue()"
          (member target "Value")
          "executable:java.util.Map$Entry#setValue(java.lang.Object)"
          (invoke (member target "SetValue") args)
          "executable:java.text.Format#format(java.lang.Object)" (compat-call "Format" (into [target] args))
          "executable:java.util.ArrayList#add(java.lang.Object)" (compat-call "Add" (into [target] args))
          "executable:java.util.ArrayList#addAll(java.util.Collection)" (compat-call "AddAll" (into [target] args))
          "executable:java.util.ArrayList#clear()" (call-member "Clear")
          "executable:java.util.ArrayList#get(int)" (sequence-node [target (raw "[") (arg 0) (raw "]")])
          "executable:java.util.ArrayList#isEmpty()" (csharp/binary "==" 40 (member target "Count") (raw "0"))
          "executable:java.util.ArrayList#size()" (member target "Count")
          "executable:java.util.AbstractCollection#isEmpty()" (compat-call "CollectionIsEmpty" [target])
          "executable:java.util.ArrayDeque#isEmpty()" (compat-call "CollectionIsEmpty" [target])
          "executable:java.util.LinkedList#isEmpty()" (compat-call "CollectionIsEmpty" [target])
          "executable:java.util.List#addAll(java.util.Collection)" (compat-call "AddAll" (into [target] args))
          "executable:java.util.Arrays#asList(java.lang.Object[])" (generic-compat-call services element "AsList" args)
          "executable:java.util.Collection#stream()" target
          "executable:java.util.Collection#isEmpty()" (compat-call "CollectionIsEmpty" [target])
          "executable:java.util.Collection#size()" (compat-call "CollectionCount" [target])
          "executable:java.util.Collection#addAll(java.util.Collection)" (compat-call "AddAll" (into [target] args))
          "executable:java.util.Collection#contains(java.lang.Object)" (compat-call "CollectionContains" (into [target] args))
          "executable:java.util.Collection#containsAll(java.util.Collection)" (compat-call "ContainsAll" (into [target] args))
          "executable:java.util.Collection#removeAll(java.util.Collection)" (compat-call "RemoveAll" (into [target] args))
          "executable:java.util.Collection#retainAll(java.util.Collection)" (compat-call "RetainAll" (into [target] args))
          "executable:java.util.Collection#iterator()" (invoke (member target "GetEnumerator") [])
          "executable:java.lang.Iterable#forEach(java.util.function.Consumer)" (compat-call "ForEach" (into [target] args))
          "executable:java.util.Collection#toArray()" (compat-call "ToArray" [target])
          "executable:java.util.Collection#toArray(java.lang.Object[])"
          (argument-array-generic-compat-call services element "ToArrayLoose" [target])
          "executable:java.lang.Iterable#iterator()" (invoke (member target "GetEnumerator") [])
          "executable:java.lang.Iterable#spliterator()" target
          "executable:java.util.Collections#emptyList()" (generic-compat-call services element "ListOf" [])
          "executable:java.util.Collections#singletonList(java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.Collections#unmodifiableList(java.util.List)" (compat-call "UnmodifiableList" args)
          "executable:java.util.List#copyOf(java.util.Collection)" (result-generic-compat-call services element "UnmodifiableList" args)
          "executable:java.util.Set#copyOf(java.util.Collection)" (result-generic-compat-call services element "SetOfValues" args)
          "executable:java.util.EnumSet#allOf(java.lang.Class)" (result-generic-compat-call services element "EnumSetAllOf" args)
          "executable:java.util.EnumSet#copyOf(java.util.Collection)" (result-generic-compat-call services element "EnumSetCopyOf" args)
          "executable:java.util.EnumSet#noneOf(java.lang.Class)" (result-generic-compat-call services element "EnumSetNoneOf" args)
          "executable:java.util.EnumSet#of(java.lang.Enum)" (result-generic-compat-call services element "EnumSetOf" args)
          "executable:java.util.EnumSet#removeAll(java.util.Collection)" (compat-call "RemoveAll" (into [target] args))
          "executable:java.util.Deque#getFirst()" (compat-call "DequeGetFirst" [target])
          "executable:java.util.Deque#addFirst(java.lang.Object)" (call-member "AddFirst")
          "executable:java.util.Deque#descendingIterator()" (call-member "DescendingIterator")
          "executable:java.util.Deque#isEmpty()" (compat-call "CollectionIsEmpty" [target])
          "executable:java.util.Deque#peek()" (compat-call "DequePeek" [target])
          "executable:java.util.Deque#pop()" (compat-call "DequePop" [target])
          "executable:java.util.Deque#push(java.lang.Object)" (compat-call "DequePush" (into [target] args))
          "executable:java.util.List#add(java.lang.Object)" (compat-call "Add" (into [target] args))
          "executable:java.util.List#get(int)" (compat-call "ListGet" (into [target] args))
          "executable:java.util.List#isEmpty()" (compat-call "ListIsEmpty" [target])
          "executable:java.util.List#of()" (generic-compat-call services element "ListOf" [])
          "executable:java.util.List#of(java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.List#of(java.lang.Object,java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.List#size()" (compat-call "ListCount" [target])
          "executable:java.util.List#subList(int,int)" (compat-call "SubList" (into [target] args))
          "executable:java.util.List#containsAll(java.util.Collection)" (compat-call "ContainsAll" (into [target] args))
          "executable:java.util.List#addAll(int,java.util.Collection)" (compat-call "ListAddAll" (into [target] args))
          "executable:java.util.List#removeAll(java.util.Collection)" (compat-call "RemoveAll" (into [target] args))
          "executable:java.util.List#retainAll(java.util.Collection)" (compat-call "RetainAll" (into [target] args))
          "executable:java.util.List#set(int,java.lang.Object)" (compat-call "ListSet" (into [target] args))
          "executable:java.util.List#add(int,java.lang.Object)" (compat-call "ListAdd" (into [target] args))
          "executable:java.util.List#remove(int)" (compat-call "ListRemove" (into [target] args))
          "executable:java.util.List#lastIndexOf(java.lang.Object)" (compat-call "ListLastIndexOf" (into [target] args))
          "executable:java.util.List#clear()" (call-member "Clear")
          "executable:java.util.List#contains(java.lang.Object)" (compat-call "CollectionContains" (into [target] args))
          "executable:java.util.List#iterator()" (invoke (member target "GetEnumerator") [])
          "executable:java.util.List#listIterator()" (invoke (member target "GetEnumerator") [])
          "executable:java.util.List#listIterator(int)" (compat-call "ReverseIterator" (into [target] args))
          "executable:java.util.List#toArray()" (compat-call "ToArray" [target])
          "executable:java.util.List#toArray(java.lang.Object[])"
          (argument-array-generic-compat-call services element "ToArrayLoose" [target])
          "executable:java.util.Set#size()" (compat-call "CollectionCount" [target])
          "executable:java.util.Set#contains(java.lang.Object)" (compat-call "CollectionContains" (into [target] args))
          "executable:java.util.Set#containsAll(java.util.Collection)" (compat-call "ContainsAll" (into [target] args))
          "executable:java.util.Set#removeAll(java.util.Collection)" (compat-call "RemoveAll" (into [target] args))
          "executable:java.util.Map#containsKey(java.lang.Object)" (compat-call "MapContainsKey" (into [target] args))
          "executable:java.util.Map#entrySet()" (compat-call "MapEntrySet" [target])
          "executable:java.util.Map#get(java.lang.Object)" (compat-call "MapGet" (into [target] args))
          "executable:java.util.Map#isEmpty()" (compat-call "MapIsEmpty" [target])
          "executable:java.util.Map#keySet()" (compat-call "MapKeySet" [target])
          "executable:java.util.Map#values()" (member target "Values")
          "executable:java.util.Map#put(java.lang.Object,java.lang.Object)" (compat-call "MapPut" (into [target] args))
          "executable:java.util.Map#putAll(java.util.Map)" (compat-call "MapPutAll" (into [target] args))
          "executable:java.util.Map#size()" (compat-call "MapCount" [target])
          "executable:java.util.Map#clear()" (call-member "Clear")
          "executable:java.util.Map#containsValue(java.lang.Object)" (compat-call "MapContainsValue" (into [target] args))
          "executable:java.util.Map#remove(java.lang.Object)" (compat-call "MapRemove" (into [target] args))
          "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)" (compat-call "ComputeIfAbsent" (into [target] args))
          "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)" (compat-call "MapGetOrDefault" (into [target] args))
          "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)" (compat-call "MapPutIfAbsent" (into [target] args))
          "executable:java.util.Map#forEach(java.util.function.BiConsumer)" (compat-call "ForEach" (into [target] args))
          "executable:java.util.HashMap#get(java.lang.Object)" (compat-call "MapGet" (into [target] args))
          "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)" (compat-call "MapPut" (into [target] args))
          "executable:java.util.HashMap#remove(java.lang.Object)" (compat-call "MapRemove" (into [target] args))
          "executable:java.util.LinkedHashMap#get(java.lang.Object)" (compat-call "MapGet" (into [target] args))
          "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)" (compat-call "MapPut" (into [target] args))
          "executable:java.util.LinkedHashMap#remove(java.lang.Object)" (compat-call "MapRemove" (into [target] args))
          "executable:java.util.Map#entry(java.lang.Object,java.lang.Object)" (compat-call "MapEntry" args)
          "executable:java.util.Map#ofEntries(java.util.Map$Entry[])" (result-generic-arguments-compat-call services element "MapOfEntriesLoose" args)
          "executable:java.util.Map#of()" (result-generic-arguments-compat-call services element "MapOf" args)
          "executable:java.util.Map#of(java.lang.Object,java.lang.Object)" (result-generic-arguments-compat-call services element "MapOf" args)
          "executable:java.util.Map#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" (result-generic-arguments-compat-call services element "MapOf" args)
          "executable:java.util.Iterator#hasNext()" (compat-call "IteratorHasNext" [target])
          "executable:java.util.Iterator#next()" (compat-call "IteratorNext" [target])
          "executable:java.util.Iterator#remove()" (compat-call "IteratorRemove" [target])
          "executable:java.util.ListIterator#hasNext()" (compat-call "IteratorHasNext" [target])
          "executable:java.util.ListIterator#next()" (compat-call "IteratorNext" [target])
          "executable:java.util.ListIterator#hasPrevious()" (compat-call "IteratorHasNext" [target])
          "executable:java.util.ListIterator#previous()" (compat-call "IteratorNext" [target])
          "executable:java.util.PrimitiveIterator$OfLong#hasNext()" (compat-call "IteratorHasNext" [target])
          "executable:java.util.PrimitiveIterator$OfLong#nextLong()" (compat-call "IteratorNextLong" [target])
          "executable:java.util.PrimitiveIterator$OfInt#hasNext()" (compat-call "IteratorHasNext" [target])
          "executable:java.util.PrimitiveIterator$OfInt#nextInt()" (compat-call "IteratorNext" [target])
          "executable:java.util.Locale#getDefault()" (raw "global::System.Globalization.CultureInfo.CurrentCulture")
          "executable:java.util.Objects#deepEquals(java.lang.Object,java.lang.Object)" (compat-call "DeepEquals" args)
          "executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)" (compat-call "Equals" args)
          "executable:java.util.Objects#hash(java.lang.Object[])" (compat-call "Hash" args)
          "executable:java.util.Objects#requireNonNull(java.lang.Object)" (compat-call "RequireNonNull" args)
          "executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)" (compat-call "RequireNonNull" args)
          "executable:java.util.Objects#requireNonNullElseGet(java.lang.Object,java.util.function.Supplier)"
          (let [result-type (.getType element)
                element-type (first (.getActualTypeArguments result-type))]
            (if element-type
              (compat-call
               "RequireNonNullElseGet"
               [(arg 0)
                (sequence-node
                 [(raw "() => ")
                  (invoke
                   (csharp/generic-name
                    (raw "global::Vibeformer.Runtime.JavaCompat.ListOf")
                    [((:type-node services) element-type)])
                   [])])])
              (compat-call "RequireNonNullElseGet" args)))
          "executable:java.util.Optional#empty()" (result-type-call services element "Empty" args)
          "executable:java.util.Optional#of(java.lang.Object)" (result-type-call services element "Of" args)
          "executable:java.util.Optional#ofNullable(java.lang.Object)" (result-type-call services element "OfNullable" args)
          "executable:java.util.Optional#ifPresent(java.util.function.Consumer)" (call-member "IfPresent")
          "executable:java.util.Optional#ifPresentOrElse(java.util.function.Consumer,java.lang.Runnable)" (call-member "IfPresentOrElse")
          "executable:java.util.Optional#orElseThrow()" (call-member "OrElseThrow")
          "executable:java.util.Optional#map(java.util.function.Function)" (call-member "Map")
          "executable:java.util.ResourceBundle#getBundle(java.lang.String,java.util.Locale)" (compat-call "GetResourceBundle" args)
          "executable:java.util.ResourceBundle#getString(java.lang.String)" (compat-call "GetResourceString" (into [target] args))
          "executable:java.util.Set#of()" (result-generic-compat-call services element "SetOf" args)
          "executable:java.util.Set#of(java.lang.Object)" (result-generic-compat-call services element "SetOf" args)
          "executable:java.util.Set#of(java.lang.Object,java.lang.Object)" (result-generic-compat-call services element "SetOf" args)
          "executable:java.util.Set#of(java.lang.Object,java.lang.Object,java.lang.Object)" (result-generic-compat-call services element "SetOf" args)
          "executable:java.util.Set#of(java.lang.Object[])" (result-generic-compat-call services element "SetOf" args)
          "executable:java.time.Duration#ofSeconds(long)" (compat-call "DurationOfSeconds" args)
          "executable:java.time.Duration#ofSeconds(long,long)" (compat-call "DurationOfSeconds" args)
          "executable:java.time.Duration#of(long,java.time.temporal.TemporalUnit)" (compat-call "DurationOf" args)
          "executable:java.time.Duration#toMillis()" (compat-call "DurationToMillis" [target])
          "executable:java.time.Duration#getSeconds()" (compat-call "DurationGetSeconds" [target])
          "executable:java.time.Duration#getNano()" (compat-call "DurationGetNano" [target])
          "executable:java.util.Set#addAll(java.util.Collection)" (compat-call "AddAll" (into [target] args))
          "executable:java.util.Set#isEmpty()" (compat-call "CollectionIsEmpty" [target])
          "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])" (compat-call "PathOf" args)
          "executable:java.nio.file.Path#of(java.net.URI)" (compat-call "PathOfUri" args)
          "executable:java.nio.file.Path#resolve(java.lang.String)" (compat-call "PathResolve" (into [target] args))
          "executable:java.nio.file.Path#resolve(java.nio.file.Path)" (compat-call "PathResolve" (into [target] args))
          "executable:java.nio.file.Path#toUri()" (compat-call "PathToUri" [target])
          "executable:java.nio.file.Path#toAbsolutePath()" (invoke (raw "global::System.IO.Path.GetFullPath") [target])
          "executable:java.nio.file.Path#toRealPath(java.nio.file.LinkOption[])" (compat-call "RealPath" [target])
          "executable:java.nio.file.Path#normalize()" (compat-call "NormalizePath" [target])
          "executable:java.nio.file.Path#startsWith(java.nio.file.Path)"
          (compat-call "PathStartsWith" (into [target] args))
          "executable:java.nio.file.Path#getFileName()" (invoke (raw "global::System.IO.Path.GetFileName") [target])
          "executable:java.nio.file.Path#getParent()" (invoke (raw "global::System.IO.Path.GetDirectoryName") [target])
          "executable:java.nio.file.Path#resolveSibling(java.lang.String)" (compat-call "PathResolveSibling" (into [target] args))
          "executable:java.nio.file.Path#getNameCount()" (compat-call "PathNameCount" [target])
          "executable:java.nio.file.Files#readAllBytes(java.nio.file.Path)" (compat-call "ReadAllBytes" args)
          "executable:java.nio.file.Files#writeString(java.nio.file.Path,java.lang.CharSequence,java.nio.file.OpenOption[])" (compat-call "WriteString" args)
          "executable:java.nio.file.Files#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])" (compat-call "Move" args)
          "executable:java.nio.file.Files#copy(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])" (compat-call "Copy" args)
          "executable:java.nio.file.Files#createTempFile(java.lang.String,java.lang.String,java.nio.file.attribute.FileAttribute[])" (compat-call "CreateTempFile" args)
          "executable:java.nio.file.Files#isSymbolicLink(java.nio.file.Path)" (compat-call "IsSymbolicLink" args)
          "executable:java.nio.file.Files#newDirectoryStream(java.nio.file.Path)" (compat-call "NewDirectoryStream" args)
          "executable:java.nio.file.Files#setPosixFilePermissions(java.nio.file.Path,java.util.Set)" (compat-call "SetPosixFilePermissions" args)
          "executable:java.net.InetAddress#getByName(java.lang.String)" (compat-call "GetByName" args)
          "executable:java.lang.Boolean#getBoolean(java.lang.String)" (compat-call "GetBoolean" args)
          "executable:java.util.Collections#emptyMap()" (result-generic-arguments-compat-call services element "MapOf" [])
          "executable:java.util.Collections#singleton(java.lang.Object)" (result-generic-compat-call services element "SetOf" args)
          "executable:java.util.Collections#synchronizedMap(java.util.Map)" (arg 0)
          "executable:java.util.Collections#nCopies(int,java.lang.Object)" (compat-call "NCopies" args)
          "executable:java.util.Collections#list(java.util.Enumeration)" (compat-call "ToListValues" args)
          "executable:java.util.Arrays#hashCode(java.lang.Object[])" (compat-call "ArrayHash" args)
          "executable:java.util.Objects#hashCode(java.lang.Object)" (compat-call "HashCode" args)
          "executable:java.util.regex.Matcher#end()" (call-member "End")
          "executable:java.util.regex.Matcher#end(int)" (call-member "End")
          "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuffer,java.lang.String)" (call-member "AppendReplacement")
          "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuffer)" (call-member "AppendTail")
          "executable:java.util.regex.Matcher#find()" (call-member "Find")
          "executable:java.util.regex.Matcher#group()" (call-member "Group")
          "executable:java.util.regex.Matcher#group(int)" (call-member "Group")
          "executable:java.util.regex.Matcher#group(java.lang.String)" (call-member "Group")
          "executable:java.util.regex.Matcher#groupCount()" (call-member "GroupCount")
          "executable:java.util.regex.Matcher#lookingAt()" (call-member "LookingAt")
          "executable:java.util.regex.Matcher#matches()" (call-member "Matches")
          "executable:java.util.regex.Matcher#region(int,int)" (call-member "Region")
          "executable:java.util.regex.Matcher#replaceAll(java.lang.String)" (call-member "ReplaceAll")
          "executable:java.util.regex.Matcher#replaceFirst(java.lang.String)" (call-member "ReplaceFirst")
          "executable:java.util.regex.Matcher#start()" (call-member "Start")
          "executable:java.util.regex.Matcher#start(int)" (call-member "Start")
          "executable:java.util.regex.Matcher#toMatchResult()" (call-member "ToMatchResult")
          "executable:java.util.regex.MatchResult#end()" (call-member "End")
          "executable:java.util.regex.MatchResult#end(int)" (call-member "End")
          "executable:java.util.regex.MatchResult#group()" (call-member "Group")
          "executable:java.util.regex.MatchResult#group(int)" (call-member "Group")
          "executable:java.util.regex.MatchResult#groupCount()" (call-member "GroupCount")
          "executable:java.util.regex.MatchResult#start()" (call-member "Start")
          "executable:java.util.regex.MatchResult#start(int)" (call-member "Start")
          "executable:java.util.regex.Pattern#compile(java.lang.String)" (compat-call "CompileRegex" args)
          "executable:java.util.regex.Pattern#compile(java.lang.String,int)" (compat-call "CompileRegex" args)
          "executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)" (compat-call "RegexMatcher" (into [target] args))
          "executable:java.util.regex.Pattern#pattern()" (invoke (member target "ToString") [])
          "executable:java.util.regex.Pattern#quote(java.lang.String)" (invoke (raw "global::System.Text.RegularExpressions.Regex.Escape") args)
          "executable:java.util.regex.Matcher#quoteReplacement(java.lang.String)" (compat-call "QuoteReplacement" args)
          "executable:java.util.ServiceLoader#load(java.lang.Class)" (generic-compat-call services element "LoadServices" args)
          "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)" (generic-compat-call services element "LoadServices" args)
          "executable:java.util.ServiceLoader#spliterator()" target
          "executable:java.util.Collection#spliterator()" target
          "executable:java.util.function.Supplier#get()" (invoke target [])
          "executable:java.util.function.Function#identity()" (raw "value => value")
          "executable:org.pkl.core.stdlib.VmObjectFactory$Property#identity()" (raw "value => value")
          "executable:java.util.function.Function#apply(java.lang.Object)"
          (if (and (instance? CtThisAccess target-element)
                   (= "org.pkl.core.StackFrameTransformer"
                      (some-> element enclosing-type .getQualifiedName)))
            (invoke (sequence-node
                     [(raw "((global::System.Func<global::Pkl.Core.StackFrame, global::Pkl.Core.StackFrame>)(object)")
                      target (raw ")")])
                    args)
            (invoke target args))
          "executable:java.util.function.BiFunction#apply(java.lang.Object,java.lang.Object)" (invoke target args)
          "executable:java.util.function.Consumer#accept(java.lang.Object)" (invoke target args)
          "executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)" (invoke target args)
          "executable:java.util.function.LongFunction#apply(long)" (invoke target args)
          "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)" (invoke target args)
          "executable:java.util.Comparator#naturalOrder()" (result-generic-compat-call services element "NaturalOrder" [])
          "executable:java.util.Comparator#comparingInt(java.util.function.ToIntFunction)" (result-generic-compat-call services element "ComparingInt" args)
          "executable:java.util.Comparator#comparing(java.util.function.Function)" (result-generic-compat-call services element "Comparing" args)
          "executable:java.util.Comparator#thenComparing(java.util.Comparator)" (compat-call "ThenComparing" (into [target] args))
          "executable:java.util.Comparator#reversed()" (compat-call "ReverseComparison" [target])
          "executable:java.util.Comparator#thenComparingInt(java.util.function.ToIntFunction)"
          (compat-call "ThenComparing" [target (result-generic-compat-call services element "ComparingInt" args)])
          "executable:java.util.stream.Collectors#joining(java.lang.CharSequence)" (compat-call "Joining" args)
          "executable:java.util.stream.Collectors#toCollection(java.util.function.Supplier)" (compat-call "ToCollection" args)
          "executable:java.util.stream.IntStream#allMatch(java.util.function.IntPredicate)" (compat-call "All" (into [target] args))
          "executable:java.util.stream.IntStream#iterator()" (invoke (member target "GetEnumerator") [])
          "executable:java.util.stream.IntStream#skip(long)" (compat-call "Skip" (into [target] args))
          "executable:java.util.stream.Stream#collect(java.util.stream.Collector)"
          (if (= "java.util.List" (some-> element .getType .getQualifiedName))
            (compat-call "ToListValues" [target])
            (compat-call "Collect" (into [target] args)))
          "executable:java.util.stream.Stream#map(java.util.function.Function)" (compat-call "Map" (into [target] args))
          "executable:java.util.stream.Stream#filter(java.util.function.Predicate)" (compat-call "Filter" (into [target] args))
          "executable:java.util.stream.Stream#sorted()" (compat-call "Sorted" [target])
          "executable:java.util.stream.Stream#sorted(java.util.Comparator)" (compat-call "Sorted" (into [target] args))
          "executable:java.util.stream.Stream#toArray()" (compat-call "ToArray" [target])
          "executable:java.util.stream.Stream#toList()" (compat-call "ToListValues" [target])
          "executable:java.util.stream.Stream#forEach(java.util.function.Consumer)" (compat-call "ForEach" (into [target] args))
          "executable:java.util.stream.Stream#findFirst()"
          (invoke (member ((:type-node services) (.getType element)) "OfNullable")
                  [(compat-call "FirstOrDefault" [target])])
          "executable:java.util.stream.Stream#anyMatch(java.util.function.Predicate)" (compat-call "Any" (into [target] args))
          "executable:java.util.stream.Stream#allMatch(java.util.function.Predicate)" (compat-call "AllValues" (into [target] args))
          "executable:java.util.stream.Stream#concat(java.util.stream.Stream,java.util.stream.Stream)" (compat-call "ConcatValues" args)
          "executable:java.util.stream.Stream#mapToInt(java.util.function.ToIntFunction)" (compat-call "Map" (into [target] args))
          "executable:java.util.stream.Stream#mapToLong(java.util.function.ToLongFunction)" (compat-call "MapToLong" (into [target] args))
          "executable:java.util.stream.Stream#spliterator()" target
          "executable:java.util.stream.Stream#skip(long)" (compat-call "DropValues" (into [target] args))
          "executable:java.util.stream.IntStream#max()" (compat-call "MaxOptional" [target])
          "executable:java.util.stream.LongStream#sum()" (compat-call "Sum" [target])
          "executable:java.math.BigDecimal#multiply(java.math.BigDecimal)"
          (csharp/binary "*" 70 target (arg 0))
          "executable:java.math.BigDecimal#divide(java.math.BigDecimal,int,java.math.RoundingMode)"
          (compat-call "DecimalDivide" (into [target] args))
          "executable:java.util.Spliterator#reduce(java.util.function.BinaryOperator)" (compat-call "ReduceOptional" (into [target] args))
          "executable:java.util.stream.Stream#reduce(java.util.function.BinaryOperator)" (compat-call "ReduceOptional" (into [target] args))
          "executable:java.util.function.Function#andThen(java.util.function.Function)" (compat-call "AndThen" (into [target] args))
          "executable:org.pkl.core.StackFrameTransformer#andThen(org.pkl.core.StackFrameTransformer)" (compat-call "AndThen" (into [target] args))
          "executable:java.net.URL#getProtocol()" (compat-call "UriScheme" [target])
          "executable:java.net.URL#openStream()" (compat-call "OpenStream" [target])
          "executable:java.net.URL#openConnection()" (invoke (raw "new global::Pkl.Core.Runtime.JavaUrlConnection") [target])
          "executable:java.net.URL#toURI()" target
          "executable:java.net.URI#compareTo(java.net.URI)" (compat-call "CompareUri" (into [target] args))
          "executable:java.lang.ClassLoader#getResource(java.lang.String)" (compat-call "ClassGetResource" (into [target] args))
          "executable:java.lang.ClassLoader#getResourceAsStream(java.lang.String)" (compat-call "ClassGetResourceAsStream" (into [target] args))
          "executable:java.nio.file.Path#getName(int)" (compat-call "PathName" (into [target] args))
          "executable:java.nio.file.Path#toFile()" target
          "executable:java.nio.file.Path#isAbsolute()" (compat-call "PathIsAbsolute" [target])
          "executable:java.nio.file.Path#getRoot()" (compat-call "PathRoot" [target])
          "executable:java.nio.file.Path#relativize(java.nio.file.Path)"
          (compat-call "PathRelativize" (into [target] args))
          "executable:java.nio.file.Files#newOutputStream(java.nio.file.Path,java.nio.file.OpenOption[])"
          (compat-call "NewOutputStream" args)
          "executable:java.nio.file.Files#copy(java.nio.file.Path,java.io.OutputStream)"
          (compat-call "Copy" args)
          "executable:java.nio.file.Files#walk(java.nio.file.Path,java.nio.file.FileVisitOption[])"
          (compat-call "Walk" args)
          "executable:java.io.Writer#append(char)" (invoke (member target "Write") args)
          "executable:java.io.Writer#append(java.lang.CharSequence)" (invoke (member target "Write") args)
          "executable:java.io.Writer#append(java.lang.CharSequence,int,int)"
          (compat-call "WriterAppend" (into [target] args))
          "executable:java.io.Reader#read(char[],int,int)"
          (compat-call "ReaderRead" (into [target] args))
          "executable:java.io.PrintWriter#println(java.lang.String)"
          (invoke (member target "WriteLine") args)
          "executable:java.io.PrintStream#println(java.lang.String)"
          (invoke (member target "WriteLine") args)
          "executable:java.lang.invoke.MethodHandle#invoke(java.lang.Object[])" (invoke (member target "DynamicInvoke") args)
          "executable:java.net.InetAddress#getAddress()" (call-member "GetAddressBytes")
          "executable:java.util.Iterator#forEachRemaining(java.util.function.Consumer)" (compat-call "ForEachRemaining" (into [target] args))
          "executable:java.util.stream.StreamSupport#stream(java.util.Spliterator,boolean)" (arg 0)
          "executable:org.pkl.parser.Span#charIndex()" (member target "CharIndex")
          "executable:org.pkl.parser.Span#length()" (member target "Length")
          "executable:org.organicdesign.fp.tuple.Tuple2#_1()" (member target "_1")
          "executable:org.organicdesign.fp.tuple.Tuple2#_2()" (member target "_2")
          "executable:org.organicdesign.fp.function.Fn0#apply()" (invoke target [])
          "executable:org.pkl.core.stdlib.VmObjectFactory$Property#evaluate(java.lang.Object)" (invoke target args)
          "executable:org.pkl.core.module.ModuleKey#resolveUri(java.net.URI,java.net.URI)"
          (if (or (instance? CtSuperAccess target-element)
                  (= "AbstractPackage"
                     (some-> element enclosing-type .getSimpleName)))
            (invoke (raw "global::Pkl.Core.Util.IoUtils.Resolve")
                    (into [(raw "((global::Pkl.Core.Runtime.ReaderBase)(object)this)")]
                          args))
            (normal-invocation services element children))
          "executable:org.pkl.core.runtime.ReaderBase#resolveUri(java.net.URI,java.net.URI)"
          (if (or (instance? CtSuperAccess target-element)
                  (= "AbstractPackage"
                     (some-> element enclosing-type .getSimpleName)))
            (invoke (raw "global::Pkl.Core.Util.IoUtils.Resolve")
                    (into [(raw "((global::Pkl.Core.Runtime.ReaderBase)(object)this)")]
                          args))
            (normal-invocation services element children))
          "executable:org.pkl.core.runtime.VmValueVisitor#visit(java.lang.Object)"
          (if (instance? CtSuperAccess target-element)
            (compat-call "VisitVmValue" (into [(raw "this")] args))
            (normal-invocation services element children))
          ;; Project-local calls are mapped by exact declaration identity.  A
          ;; constructor invocation is handled separately below.
          (let [resolved (occurrence context (.getExecutable element))]
            (cond
              (str/starts-with? key "executable:java.util.EnumSet#of(")
              (result-generic-compat-call services element "SetOf" args)
              (str/starts-with? key "executable:java.util.EnumSet#copyOf(")
              (result-generic-compat-call services element "SetOfValues" args)
              (str/starts-with? key "executable:java.util.Set#of(")
              (result-generic-compat-call services element "SetOf" args)
              (str/starts-with? key "executable:java.util.List#of(")
              (generic-compat-call services element "ListOf" args)
              (str/starts-with? key "executable:java.util.Map#of(")
              (result-generic-arguments-compat-call services element "MapOf" args)
              (str/starts-with? key "executable:java.util.Arrays#copyOf(")
              (compat-call "CopyOf" args)
              (str/starts-with? key "executable:java.util.Arrays#copyOfRange(")
              (compat-call "CopyOfRange" args)
              (str/starts-with? key "executable:java.util.Arrays#fill(")
              (compat-call "Fill" args)
              (str/starts-with? key "executable:java.util.Arrays#equals(")
              (compat-call "DeepEquals" args)
              (str/starts-with? key "executable:java.util.Arrays#hashCode(")
              (compat-call "ArrayHash" args)
              (and (= "clone" (.getSimpleName (.getExecutable element)))
                   (zero? argc)
                   (some-> target-element .getType .isArray))
              (sequence-node [(raw "((") ((:type-node services) (.getType element))
                              (raw ")") (invoke (member target "Clone") []) (raw ")")])
              (and (str/starts-with? key "executable:java.")
                   (str/ends-with? key "#getCause()"))
              (member target "InnerException")
              (str/ends-with? key "#getCause()")
              (member target "InnerException")
              (and (str/starts-with? key "executable:java.")
                   (str/includes? key "#initCause("))
              (compat-call "InitCause" (into [target] args))
              (and (str/starts-with? key "executable:java.")
                   (str/ends-with? key "#getMessage()"))
              (member target "Message")
              (and (str/starts-with? key "executable:java.")
                   (str/includes? key "#printStackTrace("))
              (compat-call "PrintStackTrace" (into [target] args))
              (and (str/starts-with? key "executable:java.math.BigInteger#and(")
                   (= 1 argc))
              (csharp/binary "&" 32 target (arg 0))
              (and (str/starts-with? key "executable:java.math.BigInteger#not(")
                   (zero? argc))
              (csharp/prefix "~" target)
              (and (str/starts-with? key "executable:java.math.BigInteger#shiftRight(")
                   (= 1 argc))
              (csharp/binary ">>" 30 target (arg 0))
              (and (or (str/starts-with? key "executable:java.lang.Math#multiplyExact(")
                       (str/starts-with? key "executable:java.lang.StrictMath#multiplyExact("))
                   (= 2 argc))
              (compat-call "MultiplyExact" args)
              (and (str/starts-with? key "executable:java.")
                   (str/ends-with? key "#hashCode()"))
              (compat-call "HashCode" [target])
              (and (str/starts-with? key "executable:java.io.")
                   (str/includes? key "#readAllBytes()"))
              (compat-call "ReadAllBytes" [target])
              (and (str/starts-with? key "executable:java.io.")
                   (str/includes? key "#readNBytes(int)"))
              (compat-call "ReadNBytes" (into [target] args))
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#computeIfAbsent("))
              (compat-call "ComputeIfAbsent" (into [target] args))
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#containsKey("))
              (compat-call "MapContainsKey" (into [target] args))
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#containsValue("))
              (compat-call "MapContainsValue" (into [target] args))
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#get(java.lang.Object)"))
              (compat-call "MapGet" (into [target] args))
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#put("))
              (compat-call "MapPut" (into [target] args))
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#keySet()"))
              (compat-call "MapKeySet" [target])
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#iterator()"))
              (invoke (member target "GetEnumerator") [])
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#toArray("))
              (compat-call "ToArray" [target])
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#spliterator()"))
              target
              (and (str/starts-with? key "executable:java.util.")
                   (str/includes? key "#removeAll("))
              (compat-call "RemoveAll" (into [target] args))
              (and (str/starts-with? key "executable:java.util.stream.")
                   (str/includes? key "#filter("))
              (compat-call "Filter" (into [target] args))
              (and (str/starts-with? key "executable:java.util.stream.")
                   (str/includes? key "#sorted("))
              (compat-call "Sorted" (into [target] args))
              (and (str/starts-with? key "executable:java.util.stream.")
                   (str/includes? key "#toArray("))
              (compat-call "ToArray" [target])
              (and (str/starts-with? key "executable:java.util.stream.")
                   (str/includes? key "#forEach("))
              (compat-call "ForEach" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.PersistentHashMap#")
                   (or (str/includes? key "#empty(")
                       (str/includes? key "#emptyMutable(")))
              (result-generic-arguments-compat-call services element "MapOf" [])
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.PersistentHashSet#")
                   (or (str/includes? key "#empty(")
                       (str/includes? key "#emptyMutable(")))
              (result-generic-compat-call services element "SetOf" [])
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#mutable()"))
              (compat-call "Mutable" [target])
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#immutable()"))
              target
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#assoc(")
                   (let [target-type (some-> target-element .getType .getQualifiedName)]
                     (or (= "org.organicdesign.fp.collections.MutMap" target-type)
                         (str/includes? (or target-type "") "MutHashMap"))))
              (compat-call "OrganicPut" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#assoc("))
              (compat-call "Assoc" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#get("))
              (compat-call "OrganicGet" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#put("))
              (let [target-type (some-> target-element .getType .getQualifiedName)]
                (if (= "org.organicdesign.fp.collections.ImSet" target-type)
                  (compat-call "Assoc" (into [target] args))
                  (compat-call "OrganicPut" (into [target] args))))
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/ends-with? key "#hashCode()"))
              (compat-call "HashCode" [target])
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#without("))
              (compat-call "Without" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#size()"))
              (compat-call "CollectionCount" [target])
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#isEmpty()"))
              (compat-call "CollectionIsEmpty" [target])
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#keySet()"))
              (compat-call "MapKeySet" [target])
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.")
                   (str/includes? key "#containsValue("))
              (compat-call "MapContainsValue" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.xform.Xform#of(")
                   (= 1 argc))
              (arg 0)
              (and (str/starts-with? key "executable:org.organicdesign.fp.xform.")
                   (str/includes? key "#take("))
              (compat-call "TakeValues" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.xform.")
                   (str/includes? key "#drop("))
              (compat-call "DropValues" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.xform.")
                   (str/includes? key "#concat("))
              (compat-call "ConcatValues" (into [target] args))
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.UnmodSortedIterable#castFromList(")
                   (= 1 argc))
              (arg 0)
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.UnmodSortedIterable#equal(")
                   (= 2 argc))
              (compat-call "SequenceEqual" args)
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.UnmodIterable#toString(")
                   (= 2 argc))
              (compat-call "IterableString" args)
              (and (str/starts-with? key "executable:org.organicdesign.fp.collections.Cowry#")
                   (contains? #{"spliceIntoArrayAt" "insertIntoArrayAt" "replaceInArrayAt"}
                              (.getSimpleName (.getExecutable element))))
              (source-array-generic-compat-call
               services element ((:pascal services) (.getSimpleName (.getExecutable element))) args)
              (and (str/starts-with? key "executable:org.graalvm.collections.EconomicMap#create(")
                   (<= argc 1))
              (result-generic-arguments-compat-call services element "CreateEconomicMap" args)
              (and (str/starts-with? key "executable:org.graalvm.collections.EconomicMap#emptyMap(")
                   (zero? argc))
              (result-generic-arguments-compat-call services element "EmptyEconomicMap" [])
              (and (str/starts-with? key "executable:org.pkl.core.util.paguro.RrbTree#genericNodeArray(")
                   (= 1 argc))
              (let [component (.getComponentType (.getType element))
                    element-type (first (.getActualTypeArguments component))]
                (invoke (csharp/generic-name
                         (raw "global::Pkl.Core.Util.Paguro.RrbTree<object>.GenericNodeArray")
                                             [((:type-node services) element-type)])
                        args))
              (and (instance? CtSuperAccess target-element)
                   (= "resolveUri" (.getSimpleName (.getExecutable element))))
              (compat-call "ResolveUri" args)
              (and (str/starts-with? key "executable:org.pkl.core.stdlib.VmObjectFactory$Property#evaluate(")
                   (= 1 argc))
              (invoke target args)
              (and (str/includes? key "GeneratorSpreadNode#spreadIterable(")
                   (= 3 argc))
              (invoke (member target "SpreadIterable")
                      [(arg 0) (arg 1) (compat-call "BoxValues" [(arg 2)])])
              (and (str/includes? key "ValueFormatter#formatStringValue(")
                   (= 3 argc)
                   (= "java.lang.StringBuilder"
                      (some-> (nth (.getArguments element) 1) .getType .getQualifiedName)))
              (normal-invocation services element
                                 (mapv (fn [child]
                                         (if (identical? (:source-element child)
                                                        (nth (.getArguments element) 1))
                                           (assoc child :node
                                                  (invoke (member (:node child) "ToString") []))
                                           child))
                                       children))
              (and (= "handleBadValue" (.getSimpleName (.getExecutable element)))
                   (str/includes? (or (some-> element enclosing-type .getQualifiedName) "")
                                  "CommandSpecParser")
                   (= 1 argc))
              (invoke (member target "HandleBadValue")
                      [(sequence-node [(raw "((global::System.Func<object>)(")
                                       (arg 0) (raw "))")])])
              (and (= "accept" (.getSimpleName (.getExecutable element)))
                   (= 2 argc)
                   (str/starts-with? (:text (csharp/render target)) "vmValue"))
              (invoke (member target "Accept") args)
              (and (= "accept" (.getSimpleName (.getExecutable element)))
                   (let [owner-name (or (some-> element enclosing-type .getQualifiedName) "")]
                     (or (= "org.pkl.core.stdlib.PklConverter" owner-name)
                         (= "org.pkl.core.runtime.VmValueConverter" owner-name)
                         (and (instance? CtVariableRead target-element)
                              (= "vmValue" (some-> ^CtVariableRead target-element .getVariable
                                                   .getSimpleName)))
                         (str/ends-with? (or (:file (spoon/source-location element)) "")
                                         "PklConverter.java")
                         (str/ends-with? (or (:file (spoon/source-location element)) "")
                                         "VmValueConverter.java")))
                   (= 2 argc))
              (invoke (csharp/generic-name
                       (raw (str (:text (csharp/render target)) ".Accept"))
                       [(if (or (= "org.pkl.core.stdlib.PklConverter"
                                  (some-> element enclosing-type .getQualifiedName))
                                (= "org.pkl.core.stdlib.PklConverter"
                                   (some-> (.getArguments element) first .getType .getQualifiedName))
                                (str/ends-with? (or (:file (spoon/source-location element)) "")
                                                "PklConverter.java"))
                          (raw "object")
                          ((:type-node services)
                           (or (enclosing-method-return-type element)
                               (.getType element))))])
                      args)
              (and (= "doVisitCollection" (.getSimpleName (.getExecutable element)))
                   (str/includes? (or (some-> element enclosing-type .getQualifiedName) "")
                                  "JsonRenderer"))
              (invoke (member target "DoVisitCollection")
                      [(compat-call "ObjectCollection" [(arg 0)])])
              (and (= "doVisitMap" (.getSimpleName (.getExecutable element)))
                   (str/includes? (or (some-> element enclosing-type .getQualifiedName) "")
                                  "PropertiesRenderer")
                   (= 2 argc))
              (invoke (member target "DoVisitMap")
                      [(arg 0) (compat-call "ObjectMap" [(arg 1)])])
              (and (= "moduleOutputValueTypeMismatch"
                      (.getSimpleName (.getExecutable element)))
                   (= 4 argc))
              (invoke (member target "ModuleOutputValueTypeMismatch")
                      [(arg 0) (invoke (member (arg 1) "AsObject") []) (arg 2) (arg 3)])
              (and (= "equals" (.getSimpleName (.getExecutable element)))
                   (not (instance? CtSuperAccess target-element))
                   (= 1 argc))
              (compat-call "Equals" [target (arg 0)])
              (known-record-property-name services element target-element target)
              (member target (known-record-property-name services element target-element target))
              (and (= 1 argc)
                   (or (str/starts-with? key "executable:org.pkl.core.ValueVisitor#visitPair(")
                       (str/starts-with? key "executable:org.pkl.core.ValueConverter#convertPair(")))
              (invoke (member target ((:pascal services) (.getSimpleName (.getExecutable element))))
                      [(compat-call "ObjectPair" [(arg 0)])])
              (record-component-invocation-name services element resolved)
              (member target (record-component-invocation-name services element resolved))
              (= :constructor (:kind resolved))
              ;; Explicit this/super constructor calls are emitted on the C#
              ;; constructor signature by constructor-initializer.
              (raw "")
              (= :record-component-accessor (:resolution resolved))
              (member target ((:pascal services) (.getSimpleName (.getExecutable element))))
              (= :enum-synthetic-method (:resolution resolved))
              (normal-invocation services element children)
              (= :project (:origin resolved))
              (if (and (instance? CtMethod (:declaration resolved))
                       (when-let [functional-interface-method?
                                  (:functional-interface-method? services)]
                         (functional-interface-method? (:declaration resolved))))
                (invoke target args)
                (normal-invocation
                 services element children
                 (boolean
                  (when (instance? CtMethod (:declaration resolved))
                    (let [^CtMethod declaration (:declaration resolved)
                          owner (.getDeclaringType declaration)]
                      (or (and (instance? CtRecord owner)
                               (empty? (.getParameters declaration))
                               (some #(= (.getSimpleName declaration)
                                         (.getSimpleName ^CtRecordComponent %))
                                     (.getRecordComponents ^CtRecord owner)))
                          (when-let [record-component-contract?
                                     (:record-component-contract? services)]
                            (record-component-contract? declaration))))))))
              (some #(str/starts-with? key (str "executable:" %))
                    ["org.pkl.parser."
                     "java."
                     "javax."
                     "com.oracle.truffle."
                     "org.graalvm.collections."
                     "org.graalvm.polyglot."
                     "org.organicdesign.fp."
                     "org.msgpack."
                     "org.snakeyaml.engine.v2."])
              (normal-invocation services element children)
              :else
              (throw (ex-info "Unsupported resolved executable mapping"
                              {:kind :unsupported-resolved-executable
                               :key key :origin (:origin resolved)
                               :source (spoon/source-location element)})))))]
    (if (= :constructor (:kind (occurrence context (.getExecutable element))))
      node
      (finish-expression services children element node))))

(defn- constructor-node [services ^CtConstructorCall element children]
  (let [anonymous-class (some (fn [child]
                                (when (instance? CtClass (:source-element child))
                                  (:source-element child)))
                              children)
        anonymous-name (when anonymous-class
                         (when-let [namer (:anonymous-class-name services)]
                           (namer element)))
        element-type-name (some-> element .getType .getQualifiedName)
        element-type-arguments (vec (.getActualTypeArguments (.getType element)))
        uri-keyed-map? (and (= "java.util.HashMap" element-type-name)
                            (= "java.net.URI"
                               (some-> element-type-arguments first .getQualifiedName)))
        rrb-constructor? (str/starts-with? (or element-type-name "")
                                           "org.pkl.core.util.paguro.RrbTree")
        type (cond
               anonymous-name (raw anonymous-name)
               (contains? #{"org.pkl.core.util.paguro.RrbTree$Iter"
                            "org.pkl.core.util.paguro.RrbTree.Iter"}
                          element-type-name)
               (raw "global::Pkl.Core.Util.Paguro.RrbTree<E>.Iter")
               :else ((:type-node services) (.getType element)))
        constructor-parameters (vec (.getParameters (.getExecutable element)))
        constructor-declarations (some-> (.getExecutableDeclaration (.getExecutable element))
                                         .getParameters vec)
        record-components (let [declaration (some-> element .getType .getTypeDeclaration)]
                            (when (instance? CtRecord declaration)
                              (vec (.getRecordComponents ^CtRecord declaration))))
        args (into (vec (when-let [outer-argument (:named-inner-constructor-argument services)]
                          (when-let [argument (outer-argument element)] [argument])))
                   (mapv (fn [index source node]
                           (let [parameter (when (< index (count constructor-parameters))
                                             (nth constructor-parameters index))
                                 parameter-declaration (when (and constructor-declarations
                                                                  (< index (count constructor-declarations)))
                                                         (nth constructor-declarations index))
                                 record-component (when (and record-components
                                                             (< index (count record-components)))
                                                    (nth record-components index))
                                 node (if (and (nullable-expression? source)
                                               (not (and parameter
                                                         (or (nullable-parameter? parameter
                                                                                  parameter-declaration)
                                                             (nullable-record-component?
                                                              record-component)))))
                                        (non-null-node source node)
                                        node)]
                             (if (and (not rrb-constructor?)
                                      parameter)
                               (invariant-argument-cast services source
                                                        parameter node)
                               node)))
                         (range)
                         (.getArguments element)
                         (children-nodes children (.getArguments element))))
        args (into args
                   (when (and anonymous-name (:anonymous-constructor-arguments services))
                     ((:anonymous-constructor-arguments services) element)))
        anonymous-body (when (and anonymous-class (not anonymous-name))
                         (:node (java/child-result children anonymous-class)))]
    (finish-expression services children element
                       (if uri-keyed-map?
                         (invoke
                          (csharp/generic-name
                           (raw "global::Vibeformer.Runtime.JavaCompat.NewJavaDictionary")
                           (mapv (:type-node services) element-type-arguments))
                          args)
                       (case (.getQualifiedName (.getType element))
                         "java.util.HashSet"
                         (sequence-node [(raw "new ") type (raw "(") (first args) (raw ")")])
                         "java.util.LinkedHashSet"
                         (sequence-node [(raw "new ") type (raw "(") (first args) (raw ")")])
                         "java.util.HashMap"
                         (sequence-node [(raw "new ") type (raw "(") (first args) (raw ")")])
                         "java.util.LinkedHashMap"
                         (sequence-node [(raw "new ") type (raw "(") (first args) (raw ")")])
                         "java.util.TreeMap"
                         (invoke
                          (csharp/generic-name
                           (raw "global::Vibeformer.Runtime.JavaCompat.NewSortedDictionary")
                          (mapv (:type-node services) element-type-arguments))
                          args)
                         "java.util.TreeSet"
                         (invoke
                          (csharp/generic-name
                           (raw "global::Vibeformer.Runtime.JavaCompat.NewSortedSet")
                           (mapv (:type-node services) element-type-arguments))
                          args)
                         "java.math.BigInteger" (compat-call "NewBigInteger" args)
                         "java.io.ByteArrayInputStream" (compat-call "NewMemoryStream" args)
                         "java.io.File" (first args)
                         "java.io.FileWriter" (compat-call "NewFileWriter" args)
                         "java.net.InetSocketAddress" (compat-call "NewIpEndPoint" args)
                         "java.net.Proxy" (compat-call "NewWebProxy" args)
                         "java.net.URI" (compat-call "NewUri" args)
                         "org.pkl.core.PType$Union"
                         (sequence-node
                          [(raw "new ") type (raw "(")
                           (invoke (csharp/generic-name
                                    (raw "global::Vibeformer.Runtime.JavaCompat.CastList")
                                    [(raw "global::Pkl.Core.PType")])
                                   [(first args)])
                           (raw ")")])
                         "java.lang.String" (compat-call "NewString" args)
                         "java.net.URISyntaxException" (compat-call "NewUriSyntaxException" args)
                         "java.io.IOException" (compat-call "NewIOException" args)
                         "java.io.UncheckedIOException" (compat-call "NewIOException" args)
                         "java.net.ConnectException" (compat-call "NewIOException" args)
                         "java.net.SocketException" (compat-call "NewIOException" args)
                         "java.lang.Exception" (compat-call "NewException" args)
                         "java.lang.RuntimeException" (compat-call "NewException" args)
                         "java.lang.IllegalArgumentException" (compat-call "NewArgumentException" args)
                         "java.lang.IllegalStateException" (compat-call "NewInvalidOperationException" args)
                         "java.lang.ExceptionInInitializerError" (compat-call "NewTypeInitializationException" args)
                         "java.lang.AssertionError" (compat-call "NewException" args)
                         (sequence-node [(raw "new ") type (raw "(")
                                         (sequence-node args ", ") (raw ")")
                                         (when anonymous-body
                                           (sequence-node [(raw " ") anonymous-body]))]))))))

(defn- block-node [children ^CtBlock element]
  (sequence-node [(raw "{")
                  (when (seq (.getStatements element)) (raw "\n"))
                  (sequence-node (children-nodes children (.getStatements element)) "\n")
                  (when (seq (.getStatements element)) (raw "\n"))
                  (raw "}")]))

(defn- switch-expression-yield? [^CtYieldStatement statement]
  (loop [current (.getParent statement)]
    (cond
      (instance? CtSwitchExpression current) true
      (instance? CtSwitch current) false
      (nil? current) false
      :else (recur (.getParent ^CtElement current)))))

(defn- terminating-statement? [statement]
  (cond
    (or (instance? CtReturn statement)
        (instance? CtThrow statement)
        (instance? CtBreak statement)) true
    (instance? CtYieldStatement statement)
    (switch-expression-yield? statement)
    (instance? CtBlock statement)
    (boolean (some-> ^CtBlock statement .getStatements last terminating-statement?))
    (instance? CtIf statement)
    (let [else-statement (.getElseStatement ^CtIf statement)]
      (and else-statement
           (terminating-statement? (.getThenStatement ^CtIf statement))
           (terminating-statement? else-statement)))
    :else false))

(defn- case-labels [children ^CtCase element]
  (let [expressions (.getCaseExpressions element)
        parent (.getParent element)
        selector (cond
                   (instance? CtSwitch parent) (.getSelector ^CtSwitch parent)
                   (instance? CtSwitchExpression parent) (.getSelector ^CtSwitchExpression parent)
                   :else nil)]
    (if (seq expressions)
      (sequence-node
       (map-indexed
        (fn [index expression]
          (let [{:keys [line column]} (spoon/source-location expression)
                binding (str "__case_" (or line 0) "_" (or column 0) "_" index)
                primitive? (and selector
                                (primitive-expression? selector)
                                (primitive-expression? expression))]
            (if primitive?
              (sequence-node [(raw (str "case var " binding " when " binding " == "))
                              (child-node children expression) (raw ":")])
              (sequence-node [(raw (str "case var " binding
                                        " when global::System.Object.Equals(" binding ", "))
                              (child-node children expression) (raw "):")]))))
        expressions)
       "\n")
      (raw "default:"))))

(defn- structural-rules [services]
  (java/structural-rules
   [{:id :java.expression/array-read :class CtArrayRead
     :emit (fn [{:keys [^CtArrayRead element children]}]
             {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getTarget element)) (raw "[")
                                       (child-node children (.getIndexExpression element)) (raw "]")]))})}
    {:id :java.expression/array-write :class CtArrayWrite
     :emit (fn [{:keys [^CtArrayWrite element children]}]
             {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getTarget element)) (raw "[")
                                       (child-node children (.getIndexExpression element)) (raw "]")]))})}
    {:id :java.statement/assert :class CtAssert
     :emit (fn [{:keys [^CtAssert element children]}]
             (let [condition (child-node children (.getAssertExpression element))
                   message (.getExpression element)]
               {:node (sequence-node [(raw "if (!(") condition (raw ")) throw new global::System.Exception(")
                                      (if message (child-node children message) (raw (escape-string "Assertion failed")))
                                      (raw ");")])}))}
    {:id :java.expression/operator-assignment :class CtOperatorAssignment
     :emit (fn [{:keys [^CtOperatorAssignment element children]}]
             (let [operator (case (str (.getKind element))
                              "PLUS" "+=" "MINUS" "-=" "MUL" "*=" "DIV" "/=" "MOD" "%="
                              "BITAND" "&=" "BITOR" "|=" "BITXOR" "^="
                              "SL" "<<=" "SR" ">>=" "USR" ">>>="
                              (throw (ex-info "Unsupported operator assignment" {:operator (.getKind element)})))]
               {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getAssigned element)) (raw (str " " operator " "))
                                       (child-node children (.getAssignment element))]))}))}
    {:id :java.expression/assignment :class CtAssignment
     :emit (fn [{:keys [^CtAssignment element children]}]
             {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getAssigned element)) (raw " = ")
                                       (coerce-initializer services (.getType (.getAssigned element))
                                                           (.getAssignment element)
                                                           (child-node children (.getAssignment element)))]))})}
    {:id :java.expression/binary :class CtBinaryOperator
     :emit (fn [{:keys [^CtBinaryOperator element children]}]
             {:node (finish-expression services children element (binary-node services element children))})}
    {:id :java.statement/block :class CtBlock
     :emit (fn [{:keys [^CtBlock element children]}] {:node (block-node children element)})}
    {:id :java.statement/break :class CtBreak
     :emit (fn [_] {:node (raw "break;")})}
    {:id :java.statement/continue :class CtContinue
     :emit (fn [_] {:node (raw "continue;")})}
    {:id :java.statement/case :class CtCase
     :emit (fn [{:keys [^CtCase element children]}]
             (let [source-statements (vec (.getStatements element))
                   statements (children-nodes children source-statements)
                   last-semantic (last (remove #(instance? CtComment %) source-statements))]
               {:node (sequence-node [(case-labels children element) (raw "\n")
                                      (sequence-node statements "\n")
                                      (when (and last-semantic
                                                 (not (terminating-statement?
                                                       last-semantic)))
                                        (raw "\nbreak;"))])}))}
    {:id :java.statement/catch :class CtCatch
     :emit (fn [{:keys [^CtCatch element children]}]
             (let [parameter (.getParameter element)
                   multi-types (vec (.getMultiTypes ^CtCatchVariable parameter))
                   used? (some (fn [^CtVariableRead read]
                                 (and (identical? parameter
                                                  (some-> read .getVariable .getDeclaration))
                                      (not (instance? CtThrow
                                                      (when (.isParentInitialized read)
                                                        (.getParent read))))))
                               (.getElements (.getBody element) (TypeFilter. CtVariableRead)))
                   parameter-name (identifier services (.getSimpleName parameter))
                   multi? (> (count multi-types) 1)
                   java-exception? (and (not multi?)
                                        (= "java.lang.Exception"
                                           (some-> (or (first multi-types) (.getType parameter))
                                                   .getQualifiedName)))]
               {:node (sequence-node [(raw "catch (")
                                      (if multi?
                                        (sequence-node [(raw "global::System.Exception ")
                                                        (raw parameter-name)])
                                        (if (or used? java-exception?)
                                          (child-node children parameter)
                                          ((:type-node services)
                                           (or (first multi-types) (.getType parameter)))))
                                      (raw ")")
                                      (when multi?
                                        (sequence-node
                                         [(raw (str " when (" parameter-name " is "))
                                          (sequence-node
                                           (mapv #((:type-node services) %) multi-types)
                                           " or ")
                                          (raw ")")]))
                                      (when java-exception?
                                        (raw (str " when (" parameter-name
                                                  " is not global::System.TypeInitializationException)")))
                                      (raw " ") (child-node children (.getBody element))])}))}
    {:id :java.declaration/catch-variable :class CtCatchVariable
     :emit (fn [{:keys [^CtCatchVariable element children]}]
             (let [type (or (first (.getMultiTypes element)) (.getType element))]
               {:node (sequence-node [((:type-node services) type) (raw " ")
                                      (raw (identifier services (.getSimpleName element)))])}))}
    {:id :java.declaration/anonymous-class :class CtClass
     :emit (fn [{:keys [^CtClass element children]}]
             {:node (sequence-node
                     [(raw "{")
                      (when (seq (.getTypeMembers element)) (raw "\n"))
                      (sequence-node (children-nodes children (.getTypeMembers element)) "\n")
                      (when (seq (.getTypeMembers element)) (raw "\n"))
                      (raw "}")])})}
    {:id :java.declaration/anonymous-field :class CtField
     :emit (fn [{:keys [^CtField element children]}]
             {:node (sequence-node
                     [((:type-node services) (.getType element)) (raw " ")
                      (raw (identifier services (.getSimpleName element)))
                      (when-let [value (.getDefaultExpression element)]
                        (sequence-node [(raw " = ") (child-node children value)]))
                      (raw ";")])})}
    {:id :java.declaration/anonymous-method :class CtMethod
     :emit (fn [{:keys [^CtMethod element children]}]
             {:node (sequence-node
                     [((:type-node services) (.getType element)) (raw " ")
                      (raw ((:method-name services) element)) (raw "(")
                      (sequence-node (children-nodes children (.getParameters element)) ", ")
                      (raw ") ")
                      (when-let [body (.getBody element)] (child-node children body))])})}
    {:id :java.declaration/anonymous-constructor :class CtConstructor
     :emit (fn [{:keys [^CtConstructor element children]}]
             {:node (or (when-let [body (.getBody element)] (child-node children body))
                        (raw "{}"))})}
    {:id :java.trivia/comment :class CtComment :emit (fn [_] {:node (raw "")})}
    {:id :java.expression/conditional :class CtConditional
     :emit (fn [{:keys [^CtConditional element children]}]
             (let [then-type (some-> element .getThenExpression .getType)
                   else-type (some-> element .getElseExpression .getType)
                   target-type (.getType element)
                   object-result? (and target-type
                                       (not (.isPrimitive target-type))
                                       (not= (some-> then-type .getQualifiedName)
                                             (some-> else-type .getQualifiedName)))
                   branch (fn [expression]
                            (let [node (child-node children expression)]
                              (if object-result?
                                (sequence-node [(raw "((") ((:type-node services) target-type)
                                                (raw ")((object)(") node (raw ")))!")])
                                node)))]
               {:node (finish-expression services children element
                        (sequence-node [(raw "(")
                                        (let [condition (.getCondition element)
                                              node (child-node children condition)]
                                          (if (nullable-expression? condition)
                                            (non-null-node condition node)
                                            node))
                                        (raw " ? ") (branch (.getThenExpression element))
                                        (raw " : ") (branch (.getElseExpression element)) (raw ")")]))}))}
    {:id :java.expression/constructor-call :class CtConstructorCall
     :emit (fn [{:keys [^CtConstructorCall element children]}]
             {:node (constructor-node services element children)})}
    {:id :java.statement/do :class CtDo
     :emit (fn [{:keys [^CtDo element children]}]
             {:node (sequence-node [(raw "do ") (child-node children (.getBody element))
                                    (raw " while (") (child-node children (.getLoopingExpression element))
                                    (raw ");")])})}
    {:id :java.expression/method-reference :class CtExecutableReferenceExpression
     :emit (fn [{:keys [context ^CtExecutableReferenceExpression element children]}]
             (let [target-element (.getTarget element)
                   target (child-node children target-element)
                   executable (child-node children (.getExecutable element))
                   method-name (:text (csharp/render executable))
                   resolved (occurrence context (.getExecutable element))
                   resolved-key (:key resolved)
                   constructor? (= :constructor (:kind resolved))
                   declaration (:declaration resolved)
                   static? (and (instance? CtMethod declaration)
                                (.hasModifier ^CtMethod declaration ModifierKind/STATIC))
                   parameter-count (if (instance? CtMethod declaration)
                                     (count (.getParameters ^CtMethod declaration))
                                     (count (.getParameters (.getExecutable element))))
                   parameters (mapv #(raw (str "value" %)) (range parameter-count))
                   discards-result?
                   (and (instance? CtMethod declaration)
                        (not= "void" (.getQualifiedName (.getType ^CtMethod declaration)))
                        (contains? #{"java.util.function.Consumer"
                                     "java.util.function.BiConsumer"}
                                   (some-> element .getType .getQualifiedName)))
                   target-type (when (instance? CtTypeAccess target-element)
                                 (.getAccessedType ^CtTypeAccess target-element))
                   supplier-type (when (= "java.util.function.Supplier"
                                          (some-> element .getType .getQualifiedName))
                                   (first (.getActualTypeArguments (.getType element))))
                   node (cond
                          (and (= resolved-key "executable:java.util.regex.Pattern#compile(java.lang.String)")
                               (= 1 parameter-count))
                          (sequence-node
                           [(raw "value => ") (compat-call "CompileRegex" [(raw "value")])])

                          (str/starts-with? resolved-key "executable:java.nio.file.Path#of(")
                          (sequence-node
                           [(raw "value => ") (compat-call "PathOf" [(raw "value")])])

                          (= resolved-key "executable:java.util.Map$Entry#getKey()")
                          (raw "value => value.Key")

                          (= resolved-key "executable:java.util.Map$Entry#getValue()")
                          (raw "value => value.Value")

                          (str/starts-with? resolved-key "executable:java.nio.file.Files#isRegularFile(")
                          (sequence-node
                           [(raw "value => ") (compat-call "PathIsRegularFile" [(raw "value")])])

                          (and (= resolved-key "executable:java.net.URI#create(java.lang.String)")
                               (= 1 parameter-count))
                          (sequence-node
                           [(raw "(") (sequence-node parameters ", ") (raw ") => ")
                            (compat-call "CreateUri" parameters)])

                          (and (str/starts-with? resolved-key "executable:java.lang.Long#parseLong(")
                               (= 2 parameter-count))
                          (sequence-node
                           [(raw "(") (sequence-node parameters ", ") (raw ") => ")
                            (compat-call "ParseLong" parameters)])

                          (and (str/starts-with? resolved-key "executable:java.lang.Byte#parseByte(")
                               (= 2 parameter-count))
                          (sequence-node
                           [(raw "(") (sequence-node parameters ", ") (raw ") => ")
                            (compat-call "ParseByte" parameters)])

                          (and constructor? target-type (.isArray target-type))
                          (sequence-node [(raw "value => new ")
                                          ((:type-node services) (.getComponentType target-type))
                                          (raw "[value]")])

                          (and constructor? (instance? CtTypeAccess target-element))
                          (if supplier-type
                            (sequence-node [(raw "() => new ") ((:type-node services) supplier-type) (raw "()")])
                            (sequence-node [(raw "value => new ") target (raw "(value)")]))

                          (and supplier-type
                               (str/starts-with? resolved-key
                                                 "executable:java.util.List#of("))
                          (let [element-type (first (.getActualTypeArguments supplier-type))]
                            (sequence-node
                             [(raw "() => ")
                              (invoke
                               (csharp/generic-name
                                (raw "global::Vibeformer.Runtime.JavaCompat.ListOf")
                                [(if element-type
                                   ((:type-node services) element-type)
                                   (raw "object"))])
                               [])]))

                          (and static? (instance? CtTypeAccess target-element))
                          (sequence-node
                           [(raw "(") (sequence-node parameters ", ") (raw ") => ")
                            (invoke (member target method-name) parameters)])

                          (instance? CtTypeAccess target-element)
                          (sequence-node [(raw "value => value.") (raw method-name) (raw "()")])

                          discards-result?
                          (sequence-node
                           [(raw "(") (sequence-node parameters ", ") (raw ") => { ")
                            (invoke (member target method-name) parameters) (raw "; }")])

                          :else (member target method-name))]
               {:node (finish-expression services children element node)}))}
    {:id :java.expression/field-read :class CtFieldRead
     :emit (fn [{:keys [context ^CtFieldRead element children]}]
             (let [target-element (.getTarget element)
                   target (when target-element (child-node children target-element))
                   field (:text (csharp/render (child-node children (.getVariable element))))
                   key (:key (occurrence context (.getVariable element)))
                   constant (case key
                              "field:java.lang.Boolean#TRUE" (raw "true")
                              "field:java.lang.Boolean#FALSE" (raw "false")
                              "field:java.lang.Integer#SIZE" (raw "32")
                              "field:java.lang.Long#SIZE" (raw "64")
                              "field:java.time.Month#FEBRUARY" (raw "2")
                              "field:java.util.regex.Pattern#UNICODE_CASE" (raw "((int)global::System.Text.RegularExpressions.RegexOptions.IgnoreCase)")
                              "field:java.util.regex.Pattern#UNICODE_CHARACTER_CLASS" (raw "0")
                              "field:java.util.regex.Pattern#LITERAL" (raw "0")
                              "field:java.nio.file.LinkOption#NOFOLLOW_LINKS" (raw "new object()")
                              "field:java.net.Proxy#NO_PROXY" (raw "new global::System.Net.WebProxy()")
                              "field:java.nio.file.attribute.PosixFilePermission#OWNER_READ" (raw "global::System.IO.UnixFileMode.UserRead")
                              "field:java.nio.file.attribute.PosixFilePermission#GROUP_READ" (raw "global::System.IO.UnixFileMode.GroupRead")
                              "field:java.nio.file.attribute.PosixFilePermission#OTHERS_READ" (raw "global::System.IO.UnixFileMode.OtherRead")
                              "field:java.lang.Character#MIN_SURROGATE" (raw "'\\uD800'")
                              "field:java.lang.Character#MIN_VALUE" (raw "char.MinValue")
                              "field:java.lang.Character#UPPERCASE_LETTER" (raw "1")
                              "field:java.lang.Character#LOWERCASE_LETTER" (raw "2")
                              "field:java.lang.Character#TITLECASE_LETTER" (raw "3")
                              "field:java.lang.Character#MODIFIER_LETTER" (raw "4")
                              "field:java.lang.Character#OTHER_LETTER" (raw "5")
                              "field:java.lang.Character#DECIMAL_DIGIT_NUMBER" (raw "9")
                              "field:java.lang.Character#LETTER_NUMBER" (raw "10")
                              "field:java.lang.Character#SPACE_SEPARATOR" (raw "12")
                              "field:java.lang.Character#LINE_SEPARATOR" (raw "13")
                              "field:java.lang.Character#PARAGRAPH_SEPARATOR" (raw "14")
                              "field:java.lang.Character#DASH_PUNCTUATION" (raw "20")
                              "field:java.lang.Character#START_PUNCTUATION" (raw "21")
                              "field:java.lang.Character#END_PUNCTUATION" (raw "22")
                              "field:java.lang.Character#CONNECTOR_PUNCTUATION" (raw "23")
                              "field:java.lang.Character#OTHER_PUNCTUATION" (raw "24")
                              "field:java.lang.Character#INITIAL_QUOTE_PUNCTUATION" (raw "29")
                              "field:java.lang.Character#FINAL_QUOTE_PUNCTUATION" (raw "30")
                              "field:java.io.File#separatorChar" (raw "global::System.IO.Path.DirectorySeparatorChar")
                              "field:org.pkl.core.runtime.VmValueConverter#WILDCARD_PROPERTY" (raw "global::Pkl.Core.Runtime.VmValueConverter<object>.WILDCARD_PROPERTY")
                              "field:org.pkl.core.runtime.VmValueConverter#WILDCARD_ELEMENT" (raw "global::Pkl.Core.Runtime.VmValueConverter<object>.WILDCARD_ELEMENT")
                              "field:org.pkl.core.runtime.VmValueConverter#TOP_LEVEL_VALUE" (raw "global::Pkl.Core.Runtime.VmValueConverter<object>.TOP_LEVEL_VALUE")
                              "field:java.lang.Double#MIN_EXPONENT" (raw "-1022")
                              "field:java.lang.Double#MAX_EXPONENT" (raw "1023")
                              nil)]
               {:node (finish-expression
                       services children element
                       (or constant
                           (if (class-literal? element)
                             (sequence-node [(raw "typeof(") target (raw ")")])
                             (member target field))))}))}
    {:id :java.expression/field-write :class CtFieldWrite
     :emit (fn [{:keys [^CtFieldWrite element children]}]
             (let [target-element (.getTarget element)
                   target (when target-element (child-node children target-element))
                   field (:text (csharp/render (child-node children (.getVariable element))))]
               {:node (finish-expression services children element (member target field))}))}
    {:id :java.statement/foreach :class CtForEach
     :emit (fn [{:keys [^CtForEach element children]}]
             (if (= "org.pkl.core.stdlib.PklConverter"
                    (some-> element enclosing-type .getQualifiedName))
               (let [variable (.getVariable element)
                     temporary (str "__each_" (local-name services variable))]
                 {:node (sequence-node [(raw (str "foreach (var " temporary " in "))
                                        (child-node children (.getExpression element))
                                        (raw ") { ") (child-node children variable)
                                        (raw (str " = " temporary "; "))
                                        (child-node children (.getBody element))
                                        (raw " }")])})
               {:node (sequence-node [(raw "foreach (") (child-node children (.getVariable element))
                                      (raw " in ") (child-node children (.getExpression element)) (raw ") ")
                                      (child-node children (.getBody element))])}))}
    {:id :java.statement/for :class CtFor
     :emit (fn [{:keys [^CtFor element children]}]
             {:node (sequence-node [(raw "for (")
                                    (sequence-node (children-nodes children (.getForInit element)) ", ")
                                    (raw "; ")
                                    (when-let [expression (.getExpression element)] (child-node children expression))
                                    (raw "; ")
                                    (sequence-node (children-nodes children (.getForUpdate element)) ", ")
                                    (raw ") ") (child-node children (.getBody element))])})}
    {:id :java.statement/if :class CtIf
     :emit (fn [{:keys [^CtIf element children]}]
             {:node (sequence-node [(raw "if (") (child-node children (.getCondition element))
                                    (raw ") ") (child-node children (.getThenStatement element))
                                    (when-let [else (.getElseStatement element)]
                                      (sequence-node [(raw " else ") (child-node children else)]))])})}
    {:id :java.expression/invocation :class CtInvocation
     :emit (fn [{:keys [context ^CtInvocation element children]}]
             {:node (invocation-node context services element children)})}
    {:id :java.expression/lambda :class CtLambda
     :emit (fn [{:keys [^CtLambda element children]}]
             (let [parameters (mapv #(raw (local-name services ^CtParameter %))
                                    (.getParameters element))
                   body (if-let [expression (.getExpression element)]
                          (child-node children expression)
                          (child-node children (.getBody element)))
                   functional-result (first (.getActualTypeArguments (.getType element)))
                   body (if (and functional-result
                                 (str/starts-with? (.getQualifiedName ^CtTypeReference functional-result)
                                                   "org.pkl.core.util.paguro.RrbTree$")
                                 (.getExpression element))
                          (sequence-node [(raw "((") ((:type-node services) functional-result)
                                          (raw ")((object)(") body (raw ")))!")])
                          body)]
               {:node (finish-expression services children element
                       (sequence-node [(raw "(") (sequence-node parameters ", ") (raw ") => ") body]))}))}
    {:id :java.expression/literal :class CtLiteral
     :emit (fn [{:keys [^CtLiteral element children]}]
             (let [value (.getValue element)
                   node (raw (if (and (nil? value)
                                      (or (instance? CtTypeParameterReference (.getType element))
                                          (null-type-parameter-return? element)))
                               "default!"
                               (literal-text value)))
                   node (if (and (or (= "char" (some-> element .getType .getQualifiedName))
                                     (char-literal-context? element))
                                 (number? value))
                          (sequence-node [(raw "(char)(") node (raw ")")])
                          node)]
               {:node (finish-expression services children element node)}))}
    {:id :java.declaration/local-variable :class CtLocalVariable
     :emit (fn [{:keys [^CtLocalVariable element children]}]
             (let [default (.getDefaultExpression element)
                   type-reference (.getType element)
                   parent (when (.isParentInitialized element) (.getParent element))
                   later-for-initializer?
                   (and (instance? CtFor parent)
                        (not (identical? element (first (.getForInit ^CtFor parent)))))
                   type (when-not later-for-initializer?
                          (if (.isInferred element)
                            (raw "var")
                            (sequence-node
                             [(child-node children type-reference)
                              (when (and (not (.isPrimitive type-reference))
                                         (not (nullable-type? type-reference))
                                         (not (contains? #{"java.util.Map$Entry"
                                                           "java.time.Duration"
                                                           "java.math.BigInteger"}
                                                         (.getQualifiedName type-reference))))
                                (raw "?"))])))
                   declaration (sequence-node [type (when type (raw " "))
                                               (raw (local-name services element))
                                               (when default
                                                 (sequence-node
                                                  [(raw " = ")
                                                   (coerce-initializer services type-reference default
                                                                       (child-node children default))]))])]
               {:node (if (= "statement" (role element))
                        (sequence-node [declaration (raw ";")]) declaration)}))}
    {:id :java.expression/new-array :class CtNewArray
     :emit (fn [{:keys [^CtNewArray element children]}]
             (let [type-reference (.getType element)
                   component (if (.isArray type-reference) (.getComponentType type-reference) type-reference)
                   rrb-raw-nested? (and (empty? (.getActualTypeArguments component))
                                        (str/includes? (.getQualifiedName component)
                                                       "org.pkl.core.util.paguro.RrbTree")
                                        (contains? #{"Node" "Leaf" "Relaxed"}
                                                   (.getSimpleName component)))
                   component-node (if-let [parameter (when rrb-raw-nested?
                                                       (nearest-type-parameter-name element))]
                                    (raw (str "global::Pkl.Core.Util.Paguro.RrbTree<object>."
                                              (.getSimpleName component) "<" parameter ">"))
                                    ((:type-node services) component))
                   dimensions (.getDimensionExpressions element)
                   elements (.getElements element)
                   node (cond
                          (seq elements)
                          (sequence-node [(raw "new ") component-node (raw "[] { ")
                                          (sequence-node (children-nodes children elements) ", ") (raw " }")])

                          (seq dimensions)
                          (sequence-node [(raw "new ") component-node (raw "[")
                                          (sequence-node (children-nodes children dimensions) ", ") (raw "]")])

                          :else
                          (invoke (csharp/generic-name (raw "global::System.Array.Empty")
                                                       [component-node]) []))]
               {:node (finish-expression services children element node)}))}
    {:id :java.statement/return :class CtReturn
     :emit (fn [{:keys [^CtReturn element children]}]
             {:node (sequence-node [(raw "return")
                                    (when-let [expression (.getReturnedExpression element)]
                                      (sequence-node
                                       [(raw " ")
                                        (let [node (child-node children expression)
                                              node (if (and (nullable-expression? expression)
                                                            (not (nullable-enclosing-return? element)))
                                                     (non-null-node expression node)
                                                     node)]
                                          (if-let [return-type (generic-invariant-return-type element)]
                                            (cond
                                              (and (= "org.pkl.core.PClassInfo"
                                                      (.getQualifiedName ^CtTypeReference return-type))
                                                   (= 1 (count (.getActualTypeArguments return-type)))
                                                   (instance? CtWildcardReference
                                                              (first (.getActualTypeArguments return-type)))
                                                   (= "org.pkl.core.PClassInfo"
                                                      (some-> expression .getType .getQualifiedName)))
                                              (invoke (member node "AsObject") [])

                                              (and (instance? CtInvocation expression)
                                                   (= "subList" (some-> ^CtInvocation expression
                                                                        .getExecutable .getSimpleName))
                                                   (= 1 (count (.getActualTypeArguments return-type))))
                                              (invoke
                                               (csharp/generic-name
                                                (raw "global::Vibeformer.Runtime.JavaCompat.CastList")
                                                [((:type-node services)
                                                  (first (.getActualTypeArguments return-type)))])
                                               [node])

                                              (and (= "java.util.Map"
                                                      (.getQualifiedName ^CtTypeReference return-type))
                                                   (= 2 (count (.getActualTypeArguments return-type))))
                                              (invoke
                                               (csharp/generic-name
                                                (raw "global::Vibeformer.Runtime.JavaCompat.CastDictionary")
                                                (mapv (:type-node services)
                                                      (.getActualTypeArguments return-type)))
                                               [node])

                                              :else
                                              (sequence-node [(raw "((")
                                                              ((:type-node services) return-type)
                                                              (raw ")((object)(") node (raw ")))!")]))
                                            node))]))
                                    (raw ";")])})}
    {:id :java.expression/super :class CtSuperAccess
     :emit (fn [{:keys [^CtSuperAccess element children]}]
             {:node (finish-expression services children element (raw "base"))})}
    {:id :java.expression/switch-expression :class CtSwitchExpression
     :emit (fn [{:keys [^CtSwitchExpression element children]}]
             (let [selector (child-node children (.getSelector element))
                   cases (children-nodes children (.getCases element))
                   enclosing-method (loop [current (.getParent element)]
                                      (cond
                                        (instance? CtMethod current) current
                                        (nil? current) nil
                                        :else (recur (.getParent ^CtElement current))))
                   nullable-result? (and enclosing-method
                                         (or (nullable-annotation? enclosing-method)
                                             (nullable-type? (.getType ^CtMethod enclosing-method)))
                                         (not (.isPrimitive (.getType element))))
                   result-type (sequence-node [(child-node children (.getType element))
                                               (when nullable-result? (raw "?"))])
                   default? (some #(empty? (.getCaseExpressions ^CtCase %)) (.getCases element))]
               {:node (finish-expression services children element
                       (sequence-node [(raw "((global::System.Func<") result-type
                                       (raw ">)(() => { switch (") selector (raw ") {\n")
                                       (sequence-node cases "\n")
                                       (raw (if default?
                                              "\n} }))()"
                                              "\n} throw new global::System.InvalidOperationException(); }))()"))]))}))}
    {:id :java.statement/switch :class CtSwitch
     :emit (fn [{:keys [^CtSwitch element children]}]
             {:node (sequence-node [(raw "switch (") (child-node children (.getSelector element))
                                    (raw ") {\n") (sequence-node (children-nodes children (.getCases element)) "\n")
                                    (raw "\n}")])})}
    {:id :java.statement/synchronized :class CtSynchronized
     :emit (fn [{:keys [^CtSynchronized element children]}]
             {:node (sequence-node [(raw "lock (")
                                    (child-node children (.getExpression element))
                                    (raw ") ")
                                    (child-node children (.getBlock element))])})}
    {:id :java.expression/this :class CtThisAccess
     :emit (fn [{:keys [^CtThisAccess element children]}]
             {:node (finish-expression
                     services children element
                     (if-let [this-node (:this-node services)]
                       (this-node element)
                       (raw "this")))})}
    {:id :java.statement/throw :class CtThrow
     :emit (fn [{:keys [^CtThrow element children]}]
             (let [expression (.getThrownExpression element)
                   variable (when (instance? CtVariableRead expression)
                              (.getVariable ^CtVariableRead expression))
                   rethrow? (instance? CtCatchVariable (some-> variable .getDeclaration))]
               {:node (if rethrow?
                        (raw "throw;")
                        (sequence-node [(raw "throw ") (child-node children expression) (raw ";")]))}))}
    {:id :java.statement/try :class CtTry
     :emit (fn [{:keys [^CtTry element children]}]
             (let [resources (if (instance? CtTryWithResource element)
                               (vec (.getResources ^CtTryWithResource element))
                               [])
                   handlers? (or (seq (.getCatchers element)) (.getFinalizer element))
                   body (child-node children (.getBody element))
                   guarded-body
                   (if (seq resources)
                     (sequence-node
                      [(raw "{\n")
                       (sequence-node
                        (mapv (fn [index resource]
                                (if (instance? CtLocalVariable resource)
                                  (sequence-node [(raw "using ")
                                                  (child-node children resource)
                                                  (raw ";")])
                                  (sequence-node
                                   [(raw (str "using var __resource_" index " = "))
                                    (child-node children resource)
                                    (raw ";")])))
                              (range) resources)
                        "\n")
                       (raw "\n") body (raw "\n}")])
                     body)]
               {:node (if handlers?
                        (sequence-node [(raw "try ") guarded-body
                                        (when (seq (.getCatchers element)) (raw " "))
                                        (sequence-node
                                         (children-nodes children (.getCatchers element)) " ")
                                        (when-let [finalizer (.getFinalizer element)]
                                          (sequence-node
                                           [(raw " finally ")
                                            (child-node children finalizer)]))])
                        guarded-body)}))}
    {:id :java.expression/type-access :class CtTypeAccess
     :emit (fn [{:keys [^CtTypeAccess element children]}]
             ;; Static owners are types, not values. Applying expression
             ;; conversion here emitted invalid `(Generic<object>).Member` C#.
             {:node (child-node children (.getAccessedType element))})}
    {:id :java.expression/type-pattern :class CtTypePattern
     :emit (fn [{:keys [^CtTypePattern element children]}]
             (let [variable (.getVariable element)]
               {:node (sequence-node [((:type-node services) (.getType variable)) (raw " ")
                                      (raw (local-name services variable))])}))}
    {:id :java.expression/unary :class CtUnaryOperator
     :emit (fn [{:keys [^CtUnaryOperator element children]}]
             {:node (finish-expression services children element
                       (unary-node element (child-node children (.getOperand element))))})}
    {:id :java.expression/variable-read :class CtVariableRead
     :emit (fn [{:keys [^CtVariableRead element children]}]
             {:node (finish-expression services children element
                       (child-node children (.getVariable element)))})}
    {:id :java.expression/variable-write :class CtVariableWrite
     :emit (fn [{:keys [^CtVariableWrite element children]}]
             {:node (finish-expression services children element
                       (child-node children (.getVariable element)))})}
    {:id :java.statement/while :class CtWhile
     :emit (fn [{:keys [^CtWhile element children]}]
             {:node (sequence-node [(raw "while (") (child-node children (.getLoopingExpression element))
                                    (raw ") ") (child-node children (.getBody element))])})}
    {:id :java.statement/yield :class CtYieldStatement
     :emit (fn [{:keys [^CtYieldStatement element children]}]
             (let [switch-expression? (switch-expression-yield? element)]
               {:node (sequence-node [(when switch-expression? (raw "return "))
                                      (child-node children (.getExpression element)) (raw ";")])}))}
    {:id :java.declaration/lambda-parameter :class CtParameter
     :emit (fn [{:keys [^CtParameter element]}]
             {:node (raw (local-name services element))})}
    {:id :java.reference/catch-variable :class CtCatchVariableReference
     :emit (fn [{:keys [^CtCatchVariableReference element]}]
             {:node (raw (identifier services (.getSimpleName element)))})}
    {:id :java.reference/local-variable :class CtLocalVariableReference
     :emit (fn [{:keys [^CtLocalVariableReference element]}]
             (let [declaration (.getDeclaration element)]
               {:node (raw (if declaration
                             (local-name services declaration)
                             (identifier services (.getSimpleName element))))}))}
    {:id :java.reference/parameter :class CtParameterReference
     :emit (fn [{:keys [^CtParameterReference element]}]
             (let [declaration (.getDeclaration element)
                   local (when declaration (local-name services declaration))
                   locally-renamed?
                   (and declaration
                        (or (and (.isParentInitialized ^CtElement declaration)
                                 (instance? CtLambda (.getParent ^CtElement declaration)))
                            (str/starts-with? local "this.__capture_")))]
               {:node (raw (if locally-renamed?
                             local
                             (identifier services (.getSimpleName element))))}))}
    {:id :java.reference/package :class CtPackageReference
     :emit (fn [_] {:node (raw "")})}]))

(defn- project-executable-name [services occurrence ^CtExecutableReference reference]
  (if (= :record-component-accessor (:resolution occurrence))
    (if-let [component (when (instance? CtRecordComponent (:declaration occurrence))
                         (:declaration occurrence))]
      ((:record-component-name services) (enclosing-type component) component)
      (if-let [owner (some-> reference .getDeclaringType .getTypeDeclaration)]
        ((:record-component-name services) owner reference)
        ((:pascal services) (.getSimpleName reference))))
    (if-let [declaration (:declaration occurrence)]
    (if (instance? CtMethod declaration)
      ((:method-name services) declaration)
      (identifier services (.getSimpleName reference)))
    (identifier services (.getSimpleName reference)))))

(defn- executable-name [services occurrence ^CtExecutableReference reference]
  (if (= :project (:origin occurrence))
    (project-executable-name services occurrence reference)
    ;; Whole-invocation JDK rules own semantic rewrites.  This token remains
    ;; mapped so every resolved reference is independently covered.
    ((:pascal services) (.getSimpleName reference))))

(defn- field-name [services occurrence ^CtFieldReference reference]
  (cond
    (= "field:<array>#length" (:key occurrence)) "Length"
    (= "field:java.util.Locale#ROOT" (:key occurrence)) "InvariantCulture"
    (= "field:java.nio.charset.StandardCharsets#UTF_8" (:key occurrence)) "UTF8"
    (contains? #{"field:java.lang.Byte#MAX_VALUE"
                 "field:java.lang.Short#MAX_VALUE"
                 "field:java.lang.Integer#MAX_VALUE"
                 "field:java.lang.Long#MAX_VALUE"
                 "field:java.lang.Float#MAX_VALUE"
                 "field:java.lang.Double#MAX_VALUE"
                 "field:java.lang.Character#MAX_VALUE"}
               (:key occurrence)) "MaxValue"
    (contains? #{"field:java.lang.Byte#MIN_VALUE"
                 "field:java.lang.Short#MIN_VALUE"
                 "field:java.lang.Integer#MIN_VALUE"
                 "field:java.lang.Long#MIN_VALUE"
                 "field:java.lang.Float#MIN_VALUE"
                 "field:java.lang.Double#MIN_VALUE"}
               (:key occurrence)) "MinValue"
    (= "field:java.lang.Double#POSITIVE_INFINITY" (:key occurrence)) "PositiveInfinity"
    (= "field:java.lang.Double#NEGATIVE_INFINITY" (:key occurrence)) "NegativeInfinity"
    (or (= :record-component (:resolution occurrence))
        (instance? CtRecordComponent (:declaration occurrence)))
    (if-let [component (when (instance? CtRecordComponent (:declaration occurrence))
                         (:declaration occurrence))]
      ((:record-component-name services) (enclosing-type component) component)
      ((:pascal services) (.getSimpleName reference)))
    :else (identifier services (.getSimpleName reference))))

(defn- registry-entry [id emit]
  {:id id :emit emit})

(defn- semantic-mappings [resolved-model services]
  (reduce
   (fn [registries occurrence]
     (let [key (:key occurrence)
           category (case (:kind occurrence)
                      :type :types :executable :executables :constructor :constructors
                      :field :fields :annotation :annotations nil)]
       (if (or (nil? category) (get-in registries [category key]))
         registries
         (assoc-in
          registries [category key]
          (case category
            :types (registry-entry
                    (keyword "resolved.type" (name (:origin occurrence)))
                    (fn [{:keys [element]}] {:node ((:type-node services) element)}))
            :executables (registry-entry
                          (keyword "resolved.executable" (name (:origin occurrence)))
                          (fn [{:keys [element occurrence]}]
                            {:node (raw (executable-name services occurrence element))}))
            :constructors (registry-entry
                           (keyword "resolved.constructor" (name (:origin occurrence)))
                           (fn [_] {:node (raw "<init>")}))
            :fields (registry-entry
                     (keyword "resolved.field" (name (:origin occurrence)))
                     (fn [{:keys [element occurrence]}]
                       {:node (raw (field-name services occurrence element))}))
            :annotations (registry-entry
                          (keyword "resolved.annotation" (name (:origin occurrence)))
                          (fn [_] {:node (raw "")})))))))
   {:types {} :executables {} :constructors {} :fields {} :annotations {}}
   (:occurrences resolved-model)))

(defn context
  "Creates the accepted executable translator for one live resolved model.
  Services provide destination declaration naming/type mappings without
  introducing a second frontend representation."
  [resolved-model services]
  (java/context resolved-model
                {:mode :accepted
                 :rules (structural-rules services)
                 :mappings (semantic-mappings resolved-model services)}))

(defn translate
  "Translates and gates one live executable body or initializer."
  [translation-context element]
  (java/coverage-gate! (java/translate-element translation-context element)))

(defn explicit-constructor-invocation
  "Returns the first live this/super constructor invocation when Java requires
  it to precede all body statements."
  [translation-context ^CtBlock body]
  (when-let [first-statement (first (.getStatements body))]
    (when (and (instance? CtInvocation first-statement)
               (not (.isImplicit ^CtElement first-statement)))
      (let [resolved (occurrence translation-context (.getExecutable ^CtInvocation first-statement))]
        (when (= :constructor (:kind resolved)) first-statement)))))

(defn constructor-initializer
  "Emits a C# constructor initializer directly from a resolved Java this/super
  invocation.  Its body traversal still visits the invocation, but the
  invocation rule deliberately emits no body statement."
  [translation-context ^CtInvocation invocation outer-argument]
  (let [occurrence (occurrence translation-context (.getExecutable invocation))
        called-owner (some-> invocation .getExecutable .getDeclaringType .getQualifiedName)
        ^CtConstructor owner (loop [current (.getParent invocation)]
                               (cond
                                 (nil? current) nil
                                 (instance? CtConstructor current) current
                                 :else (recur (.getParent ^CtElement current))))
        label (if (= called-owner (some-> owner .getDeclaringType .getQualifiedName))
                "this" "base")
        arguments (into (vec (when outer-argument [{:node outer-argument}]))
                        (mapv #(translate translation-context %) (.getArguments invocation)))
        arguments (if outer-argument
                    (mapv (fn [{:keys [node] :as argument}]
                            (assoc argument :node
                                   (raw (str/replace (:text (csharp/render node))
                                                     "this.__outer" "__outer"))))
                          arguments)
                    arguments)
        option-behavior-delegating?
        (and (= "org.pkl.core.runtime.CommandSpecParser$OptionBehavior"
                (some-> owner .getDeclaringType .getQualifiedName))
             (= 2 (count (.getParameters owner)))
             (= 5 (count (.getArguments invocation))))
        exception-cause-initializer?
        (and (= label "base")
             (contains? #{"java.lang.Throwable" "java.lang.Exception"
                          "java.lang.RuntimeException"}
                        called-owner)
             (= ["java.lang.Throwable"]
                (mapv #(.getQualifiedName ^CtTypeReference %)
                      (.getParameters (.getExecutable invocation)))))
        node (cond
               exception-cause-initializer?
               (let [cause (:node (first arguments))]
                 (sequence-node
                  [(raw " : base(")
                   (compat-call "ExceptionMessage" [cause])
                   (raw ", ") cause (raw ")")]))

               option-behavior-delegating?
               ;; C# cannot infer the delegate type after a lambda has been
               ;; widened through object.  Keep this constructor initializer
               ;; direct so both lambdas retain their target Func types.
               (raw (str
                     " : this(__outer, "
                     "annotation is null ? null : "
                     "global::Pkl.Core.Runtime.VmUtils.ReadMember(annotation, global::Pkl.Core.Runtime.Identifier.CONVERT) "
                     "is global::Pkl.Core.Runtime.VmFunction convertFunc ? "
                     "(global::System.Func<string, global::System.Uri, object>)((rawValue, workingDirUri) => "
                     "__outer.HandleBadValue(() => __outer.HandleImports(convertFunc.Apply(rawValue), workingDirUri))) : null, "
                     "annotation is null ? null : "
                     "global::Pkl.Core.Runtime.VmUtils.ReadMember(annotation, global::Pkl.Core.Runtime.Identifier.TRANSFORM_ALL) "
                     "is global::Pkl.Core.Runtime.VmFunction transformAllFunc ? "
                     "(global::System.Func<global::System.Collections.Generic.IList<object>, global::System.Uri, object?>)"
                     "((values, workingDirUri) => __outer.HandleBadValue(() => __outer.HandleImports("
                     "transformAllFunc.Apply(global::Pkl.Core.Runtime.VmList.CreateFromIterable(values)), workingDirUri))) : null, "
                     "annotation is null ? (bool?)null : "
                     "global::Pkl.Core.Runtime.VmUtils.ReadMember(annotation, global::Pkl.Core.Runtime.Identifier.MULTIPLE) "
                     "is bool multipleValue ? multipleValue : (bool?)null, "
                     "annotation is null ? null : hasMetavar ? "
                     "CommandSpecParser.ExportNullableString(annotation, global::Pkl.Core.Runtime.Identifier.METAVAR) : null, "
                     "annotation is null ? null : OptionBehavior.ExportCompletionCandidates(annotation))"))

               :else
               (sequence-node [(raw (str " : " label "("))
                               (sequence-node (mapv :node arguments) ", ") (raw ")")]))]
    (-> node
        (csharp/with-source {:identity (spoon/frontend-identity invocation)
                             :location (spoon/source-location invocation)
                             :rule :java.constructor/initializer})
        (csharp/with-source {:identity (spoon/frontend-identity (.getExecutable invocation))
                             :location (spoon/source-location (.getExecutable invocation))
                             :rule :resolved.constructor/project
                             :mapping {:registry :constructors
                                       :identity :resolved.constructor/project
                                       :resolved-key (:key occurrence)
                                       :origin (:origin occurrence)
                                       :resolution (:resolution occurrence)}}))))
