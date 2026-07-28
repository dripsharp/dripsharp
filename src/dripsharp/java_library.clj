(ns dripsharp.java-library
  "Fail-closed destination foundation for ordinary Java libraries.

  This bundle intentionally contains no product identities. It accepts the
  product-neutral structural Java declarations and resolved type identities,
  and rejects every unimplemented shape with its live Spoon identity.
  Subsequent reusable translation work extends these rules; unsupported
  declarations never become generated stubs."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.csharp :as csharp]
            [dripsharp.dotnet-surface :as dotnet-surface]
            [dripsharp.java-library-mappings :as library-mappings]
            [dripsharp.java-mapping-registry :as mapping-registry]
            [dripsharp.java-project :as project-emission]
            [dripsharp.java-types :as java-types]
            [dripsharp.java-translate :as java]
            [dripsharp.paths :as paths]
            [dripsharp.spoon :as spoon])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.lang.reflect Method]
           [java.util Base64 IdentityHashMap WeakHashMap]
           [spoon.reflect.code CtAbstractSwitch CtArrayRead CtArrayWrite CtAssert CtAssignment
            CtBinaryOperator CtBlock CtBreak CtCase CtCatch CtCatchVariable CtComment
            CtConditional CtConstructorCall CtContinue CtDo CtExecutableReferenceExpression
            CtExpression CtFieldRead CtFieldWrite
            CtFor CtForEach CtIf CtInvocation CtLambda CtLiteral CtLocalVariable
            CtNewArray CtOperatorAssignment CtReturn CtStatement CtSuperAccess CtThisAccess CtThrow CtTry
            CtSwitch CtSwitchExpression CtSynchronized CtTryWithResource CtTypeAccess CtTypePattern
            CtUnaryOperator
            CtVariableAccess CtVariableRead CtVariableWrite CtWhile CtYieldStatement]
           [spoon.reflect.declaration CtAnnotation CtAnnotationMethod CtAnnotationType CtAnonymousExecutable CtClass CtConstructor CtElement
            CtEnum CtEnumValue CtExecutable CtField CtInterface CtMethod
            CtModifiable CtParameter CtRecordComponent CtType CtTypedElement
            CtTypeParameter ModifierKind]
           [spoon.reflect.reference CtArrayTypeReference CtCatchVariableReference
            CtLocalVariableReference CtPackageReference CtParameterReference
            CtTypeParameterReference CtTypeReference CtWildcardReference]
           [spoon.reflect.visitor.filter TypeFilter]))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-java-library-contract)))))

(defn- unsupported! [message ^CtElement element]
  (throw (ex-info message
                  {:kind :unsupported-destination-rule
                   :source-element element
                   :source-identity (or (spoon/declaration-key element)
                                        (spoon/frontend-identity element))
                   :source-location (spoon/source-location element)})))

(defn- interface-type? [value]
  (or (instance? CtInterface value)
      (instance? CtAnnotationType value)))

(defn- source-ref [^CtElement element rule extra]
  (merge {:frontend-class (.getName (class element))
          :role (when (.isParentInitialized element)
                  (str (.getRoleInParent element)))
          :location (spoon/source-location element)
          :rule rule}
         extra))

(def ^:private csharp-keywords
  #{"abstract" "as" "base" "bool" "break" "byte" "case" "catch" "char"
    "checked" "class" "const" "continue" "decimal" "default" "delegate"
    "do" "double" "else" "enum" "event" "explicit" "extern" "false"
    "finally" "fixed" "float" "for" "foreach" "goto" "if" "implicit"
    "in" "int" "interface" "internal" "is" "lock" "long" "namespace"
    "new" "null" "object" "operator" "out" "override" "params" "private"
    "protected" "public" "readonly" "ref" "return" "sbyte" "sealed"
    "short" "sizeof" "stackalloc" "static" "string" "struct" "switch"
    "this" "throw" "true" "try" "typeof" "uint" "ulong" "unchecked"
    "unsafe" "ushort" "using" "virtual" "void" "volatile" "while"})

(defn identifier
  "Returns a deterministic C# identifier for a Java source name."
  [value]
  (let [clean (str/replace (str value) #"[^A-Za-z0-9_]" "_")
        clean (if (re-matches #"[0-9].*" clean) (str "_" clean) clean)]
    (if (contains? csharp-keywords clean) (str "@" clean) clean)))

(defn pascal
  "Returns the shared Pascal-cased C# identifier while preserving keyword
  escaping."
  [value]
  (let [value (identifier value)
        prefix (when (str/starts-with? value "@") "@")
        body (if prefix (subs value 1) value)]
    (str prefix (str/upper-case (subs body 0 1)) (subs body 1))))

(defn- csharp-public-name [value]
  (let [escaped (identifier value)
        prefix (when (str/starts-with? escaped "@") "@")
        value (if prefix (subs escaped 1) escaped)
        words (if (str/includes? value "_")
                (remove str/blank? (str/split value #"_+"))
                [value])
        cased (apply str
                     (map (fn [word]
                            (let [word (if (re-matches #"[A-Z0-9]+" word)
                                         (str/lower-case word)
                                         word)]
                              (str (str/upper-case (subs word 0 1))
                                   (subs word 1))))
                          words))]
    (str prefix cased)))

(defn- csharp-public-names? [ctx]
  (= :csharp (get-in ctx [:configuration :name-policy :public-identifiers])))

(defn- accessible-member? [member]
  (and (instance? CtModifiable member)
       (or (.hasModifier ^CtModifiable member ModifierKind/PUBLIC)
           (.hasModifier ^CtModifiable member ModifierKind/PROTECTED))))

(defn- ordinary-member-name [ctx member]
  (let [source-name (.getSimpleName ^CtElement member)]
    (if (and (csharp-public-names? ctx)
             (or (accessible-member? member)
                 (instance? CtEnumValue member)))
      (csharp-public-name source-name)
      (identifier source-name))))

(defn- sequence-node
  ([nodes] (csharp/sequence-node (vec (remove nil? nodes))))
  ([nodes separator]
   (csharp/sequence-node (vec (remove nil? nodes)) separator)))

(defn- raw [text]
  (csharp/raw text))

(defn- package-name [^CtType type]
  (loop [current type]
    (when current
      (let [package (some-> current .getPackage .getQualifiedName)]
        (if (str/blank? package)
          (recur (.getDeclaringType ^CtType current))
          package)))))

(defn- mapped-namespace [ctx package exact-key prefixes-key]
  (when package
    (or (get-in ctx [:configuration exact-key package])
        (some (fn [[source destination]]
                (when (or (= package source)
                          (str/starts-with? package (str source ".")))
                  (let [suffix (subs package (count source))
                        segments (remove str/blank? (str/split suffix #"\."))]
                    (str destination
                         (when (seq segments)
                           (str "." (str/join "." (map pascal segments))))))))
              (sort-by (comp - count key)
                       (get-in ctx [:configuration prefixes-key]))))))

(defn- destination-namespace [ctx ^CtType type]
  (let [package (package-name type)]
    (or (mapped-namespace ctx package :namespaces :namespace-prefixes)
        (unsupported! "Java library has no destination namespace mapping" type))))

(defn- destination-file-name [_ ^CtType type]
  (str (identifier (.getSimpleName type)) ".cs"))

(declare type-node create-body-context functional-expression-node
         functional-interface-method)

(defn- context [options]
  (let [ctx-holder (atom nil)
        ctx (assoc options
                   :emitted (IdentityHashMap.)
                   :declarations (atom [])
                   :diagnostics (atom [])
                   :body-translations (atom []))
        ctx (assoc ctx :body-context (create-body-context
                                      (:resolved-model options)
                                      ctx-holder))]
    (reset! ctx-holder ctx)
    ctx))

(defn- merge-context! [target source]
  (doseq [entry (.entrySet ^IdentityHashMap (:emitted source))]
    (let [element (.getKey ^java.util.Map$Entry entry)
          declaration (.getValue ^java.util.Map$Entry entry)]
      (when (.containsKey ^IdentityHashMap (:emitted target) element)
        (fail! "A Java library declaration was emitted more than once"
               {:kind :duplicate-source-declaration :declaration declaration}))
      (.put ^IdentityHashMap (:emitted target) element declaration)))
  (swap! (:declarations target) into @(:declarations source))
  (swap! (:diagnostics target) into @(:diagnostics source))
  (swap! (:body-translations target) into @(:body-translations source)))

(defn- context-results [ctx]
  {:declarations @(:declarations ctx)
   :diagnostics @(:diagnostics ctx)
   :body-translations @(:body-translations ctx)})

(defn- occurrence! [ctx ^CtElement element expected-kind]
  (let [occurrence (.get ^IdentityHashMap (:occurrence-index ctx) element)]
    (when-not occurrence
      (throw (ex-info "Live Spoon object is absent from the resolved occurrence index"
                      {:kind :missing-resolved-occurrence
                       :expected expected-kind
                       :source-element element
                       :source-identity (spoon/frontend-identity element)
                       :source-location (spoon/source-location element)})))
    (when-not (= expected-kind (:kind occurrence))
      (throw (ex-info "Resolved occurrence has the wrong semantic kind"
                      {:kind :resolved-occurrence-kind-mismatch
                       :expected expected-kind :actual (:kind occurrence)
                       :key (:key occurrence)
                       :source-element element
                       :source-identity (spoon/frontend-identity element)
                       :source-location (spoon/source-location element)})))
    occurrence))

(defn- declaring-types [^CtType type]
  (loop [current type result ()]
    (if current
      (recur (.getDeclaringType current) (conj result current))
      (vec result))))

(defn- project-type-base [ctx ^CtType declaration]
  (str "global::" (destination-namespace ctx declaration) "."
       (str/join "." (map #(pascal (.getSimpleName ^CtType %))
                          (declaring-types declaration)))))

(defn- reference-declaring-types [^CtTypeReference reference]
  (loop [current reference result ()]
    (if current
      (recur (.getDeclaringType current) (conj result current))
      (vec result))))

(defn- translated-external-type-base [ctx ^CtTypeReference reference]
  (let [package (some-> reference .getPackage .getQualifiedName)
        namespace (mapped-namespace ctx package
                                    :external-namespaces
                                    :external-namespace-prefixes)]
    (or
     (when namespace
       (str "global::" namespace "."
            (str/join "." (map #(pascal (.getSimpleName ^CtTypeReference %))
                               (reference-declaring-types reference)))))
     (let [qualified (.getQualifiedName reference)
           mappings (merge (get-in ctx [:configuration :external-namespaces])
                           (get-in ctx
                                   [:configuration
                                    :external-namespace-prefixes]))]
       (some
        (fn [[source destination]]
          (when (or (= qualified source)
                    (str/starts-with? qualified (str source "."))
                    (str/starts-with? qualified (str source "$")))
            (let [suffix (subs qualified (count source))
                  segments (remove str/blank?
                                   (str/split suffix #"[.$]+"))]
              (when (seq segments)
                (str "global::" destination "."
                     (str/join "." (map pascal segments)))))))
        (sort-by (comp - count key) mappings))))))

(defn- destination-type-parameter-name [^CtElement parameter]
  (let [base (identifier (.getSimpleName parameter))
        parent (when (.isParentInitialized parameter) (.getParent parameter))
        executable-owner (when (instance? CtExecutable parent)
                           (.getDeclaringType ^CtExecutable parent))
        type-owner (when (instance? CtType parent)
                     (.getDeclaringType ^CtType parent))
        owner (or executable-owner type-owner)
        outer-names (when owner
                      (set (map #(.getSimpleName ^CtElement %)
                                (.getFormalCtTypeParameters ^CtType owner))))]
    (if (contains? outer-names (.getSimpleName parameter))
      (str (if executable-owner "Method" "Nested") (pascal base))
      base)))

(defn- mapped-type-base [ctx ^CtTypeReference reference occurrence]
  (let [qualified (.getQualifiedName reference)]
    (cond
      (= :null-type (:resolution occurrence))
      ["object" :dotnet.type/null]

      (instance? CtTypeParameterReference reference)
      [(destination-type-parameter-name
        (or (:declaration occurrence) reference))
       :dotnet.type/type-parameter]

      (= :project (:origin occurrence))
      (if-let [declaration (.getTypeDeclaration reference)]
        [(project-type-base ctx declaration) :dotnet.type/project]
        (unsupported! "Resolved project type has no live declaration" reference))

      :else
      (or (when-let [destination (translated-external-type-base ctx reference)]
            [destination :dotnet.type/translated-project])
          (get (:destination-type-mappings ctx) qualified)
          (java-types/mapping qualified)
          (unsupported! "Java library resolved type has no neutral mapping"
                        reference)))))

(def ^:private raw-generic-type-nodes
  {"java.util.Collection" "global::System.Collections.ICollection"
   "java.util.Comparator" "global::System.Collections.IComparer"
   "java.util.List" "global::System.Collections.IList"
   "java.util.Map" "global::System.Collections.IDictionary"
   "java.util.Set" "global::System.Collections.ICollection"
   "java.util.Stack" "global::DripSharp.Runtime.JavaStack<object>"})

(defn- type-parameter-bound-references [^CtTypeParameter parameter]
  (vec
   (remove nil?
           (concat [(.getSuperclass parameter)]
                   (.getSuperInterfaces parameter)))))

(defn- raw-project-type-argument-node
  [ctx ^CtTypeParameter parameter]
  (if-let [bound
           (first
            (remove
             #(= "java.lang.Object" (.getQualifiedName ^CtTypeReference %))
             (type-parameter-bound-references parameter)))]
    (type-node ctx bound)
    (raw "object")))

(defn- project-reference-node
  [ctx ^CtTypeReference reference ^CtType declaration]
  (let [references (reference-declaring-types reference)
        declarations (declaring-types declaration)]
    (if (= (count references) (count declarations))
      (sequence-node
       (into
        [(raw (str "global::" (destination-namespace ctx declaration) "."))]
        (mapcat
         (fn [index ^CtTypeReference part-reference ^CtType part-declaration]
           (let [actual-arguments (vec (.getActualTypeArguments part-reference))
                 formal-count
                 (count (.getFormalCtTypeParameters part-declaration))
                 arguments
                 (if (and (empty? actual-arguments) (pos? formal-count))
                   (mapv #(raw-project-type-argument-node ctx %)
                         (.getFormalCtTypeParameters part-declaration))
                   (mapv #(type-node ctx %) actual-arguments))]
             [(when (pos? index) (raw "."))
              (raw (pascal (.getSimpleName part-declaration)))
              (when (seq arguments)
                (csharp/generic-name (raw "") arguments))]))
         (range)
         references
         declarations)))
      (raw (project-type-base ctx declaration)))))

(defn- neutral-type-shape
  [ctx ^CtTypeReference reference occurrence recur-node]
  (cond
    (instance? CtArrayTypeReference reference)
    [(sequence-node [(recur-node (.getComponentType
                                  ^CtArrayTypeReference reference))
                     (raw "[]")])
     :dotnet.type/array]

    (instance? CtWildcardReference reference)
    [(if-let [bound (.getBoundingType ^CtWildcardReference reference)]
       (recur-node bound)
       (raw "object"))
     :dotnet.type/wildcard-bound]

    :else
    (let [[target rule] (mapped-type-base ctx reference occurrence)
          declaration (when (= :project (:origin occurrence))
                        (or (:declaration occurrence)
                            (.getTypeDeclaration reference)))
          actual-arguments (vec (.getActualTypeArguments reference))
          arguments
          (cond
            (contains?
             #{"java.lang.Class" "java.lang.reflect.Constructor"}
             (.getQualifiedName reference))
            []

            (= "java.util.function.BinaryOperator"
               (.getQualifiedName reference))
            (let [argument (first actual-arguments)]
              (when argument
                [(recur-node argument)
                 (recur-node argument)
                 (recur-node argument)]))

            (and (empty? actual-arguments)
                 (instance? CtType declaration)
                 (seq (.getFormalCtTypeParameters ^CtType declaration)))
            (mapv #(raw-project-type-argument-node ctx %)
                  (.getFormalCtTypeParameters ^CtType declaration))

            :else
            (mapv recur-node actual-arguments))]
      [(if-let [raw-target
                (when (empty? actual-arguments)
                  (get raw-generic-type-nodes
                       (.getQualifiedName reference)))]
         (raw raw-target)
         (if (and (= :project (:origin occurrence))
                  (instance? CtType declaration))
           (project-reference-node ctx reference declaration)
           (if (seq arguments)
             (csharp/generic-name (raw target) arguments)
             (raw target))))
       rule])))

(defn type-node
  "Emits one resolved type through the shared occurrence, recursion, and
  source-evidence contract. Destinations may supply a
  `:resolved-type-policy` in the context with an `:emit-shape` hook and an
  optional `:decorate-node` hook; those hooks own only destination-specific
  type shape and decoration."
  [ctx ^CtTypeReference reference]
  (let [policy (:resolved-type-policy ctx)
        occurrence (occurrence! ctx reference :type)
        recur-node #(type-node ctx %)
        emit-shape (or (:emit-shape policy) neutral-type-shape)
        [node rule] (emit-shape ctx reference occurrence recur-node)
        node (if-let [decorate-node (:decorate-node policy)]
               (decorate-node ctx reference node)
               node)]
    (csharp/with-source
      node
      (source-ref reference rule
                  {:mapping {:registry :types
                             :identity rule
                             :resolved-key (:key occurrence)
                             :origin (:origin occurrence)
                             :resolution (:resolution occurrence)}}))))

(defn- resolved-annotation? [ctx ^CtElement element resolved-key]
  (boolean
   (some (fn [^CtAnnotation annotation]
           (= resolved-key (:key (occurrence! ctx annotation :annotation))))
         (.getAnnotations element))))

(def ^:private nullable-annotation-names
  #{"javax.annotation.Nullable"
    "org.jspecify.annotations.Nullable"})

(defn- directly-nullable? [_ctx element]
  (and
   (instance? CtElement element)
   (boolean
    (some
     #(contains?
       nullable-annotation-names
       (some-> ^CtAnnotation % .getAnnotationType .getQualifiedName))
     (.getAnnotations ^CtElement element)))))

(defn- nullable-type-reference? [ctx ^CtTypeReference reference]
  (boolean
   (and reference
        (not (.isPrimitive reference))
        (directly-nullable? ctx reference))))

(defn- nullable-type-argument? [ctx ^CtTypeReference reference]
  (boolean
   (when reference
     (or
      (nullable-type-reference? ctx reference)
      (and (instance? CtWildcardReference reference)
           (nullable-type-argument?
            ctx (.getBoundingType ^CtWildcardReference reference)))
      (some #(nullable-type-argument? ctx %)
            (.getActualTypeArguments reference))))))

(declare nullable-expression?)

(defn- nullable-record-accessor? [ctx declaration]
  (boolean
   (when (instance? CtMethod declaration)
     (let [owner (.getDeclaringType ^CtMethod declaration)]
       (when (instance? spoon.reflect.declaration.CtRecord owner)
         (some
          (fn [^CtRecordComponent component]
            (and
             (= (.getSimpleName ^CtMethod declaration)
                (.getSimpleName component))
             (or (directly-nullable? ctx component)
                 (nullable-type-reference? ctx (.getType component)))))
          (.getRecordComponents ^spoon.reflect.declaration.CtRecord owner)))))))

(defn- nullable-declaration? [ctx declaration]
  (boolean
   (and
    (instance? CtElement declaration)
    (or
     (directly-nullable? ctx declaration)
     (and
      (instance? CtTypedElement declaration)
      (nullable-type-reference?
       ctx (.getType ^CtTypedElement declaration)))
     (nullable-record-accessor? ctx declaration)
     (and
      (instance? CtLocalVariable declaration)
      (nullable-expression?
       ctx (.getDefaultExpression ^CtLocalVariable declaration)))))))

(defn- nullable-expression? [ctx expression]
  (boolean
   (cond
     (nil? expression)
     false

     (and (instance? CtLiteral expression)
          (nil? (.getValue ^CtLiteral expression)))
     true

     (nullable-type-reference? ctx (.getType ^CtExpression expression))
     true

     (instance? CtVariableAccess expression)
     (nullable-declaration?
      ctx (some-> ^CtVariableAccess expression .getVariable .getDeclaration))

     (instance? CtInvocation expression)
     (let [invocation ^CtInvocation expression
           declaration (some-> invocation
                               .getExecutable
                               .getExecutableDeclaration)
           key (:key (occurrence! ctx (.getExecutable invocation) :executable))
           target-expression (.getTarget invocation)
           target-declaration
           (when (instance? CtVariableAccess target-expression)
             (some-> ^CtVariableAccess target-expression
                     .getVariable
                     .getDeclaration))
           target-type
           (or (when (and
                      (instance? CtLocalVariable target-declaration)
                      (.isInferred ^CtLocalVariable target-declaration))
                 (some-> ^CtLocalVariable target-declaration
                         .getDefaultExpression
                         .getType))
               (some-> target-declaration .getType)
               (some-> target-expression .getType))
           element-type (some-> target-type .getActualTypeArguments first)]
       (or
        (nullable-declaration? ctx declaration)
        (and
         (contains?
          #{"executable:java.util.List#get(int)"
            "executable:java.util.ArrayList#get(int)"
            "executable:java.util.LinkedList#get(int)"}
          key)
         (nullable-type-argument? ctx element-type))))

     (instance? CtNewArray expression)
     (some #(nullable-expression? ctx %)
           (.getElements ^CtNewArray expression))

     :else
     false)))

(defn- null-forgiven-node [node]
  (if (str/ends-with? (:text (csharp/render node)) "!")
    node
    (sequence-node [node (raw "!")])))

(declare covariant-value-override?)

(def ^:private boxed-primitive-types
  #{"java.lang.Boolean" "java.lang.Byte" "java.lang.Character"
    "java.lang.Double" "java.lang.Float" "java.lang.Integer"
    "java.lang.Long" "java.lang.Short"})

(defn- boxed-primitive-reference? [^CtTypeReference reference]
  (and reference
       (contains? boxed-primitive-types (.getQualifiedName reference))))

(declare nullable-boxed-collection-expression?)

(def ^:private nonnullable-boxed-factory-keys
  #{"executable:java.lang.Double#valueOf(java.lang.String)"
    "executable:java.lang.Integer#valueOf(java.lang.String)"})

(defn- nullable-runtime-boxed-primitive-method? [occurrence]
  (let [declaration (:declaration occurrence)]
    (and (instance? Method declaration)
         (not (contains? nonnullable-boxed-factory-keys (:key occurrence)))
         (contains? boxed-primitive-types
                    (.getName (.getReturnType ^Method declaration))))))

(defn- anonymous-method? [^CtElement element]
  (and (instance? CtMethod element)
       (let [owner (.getDeclaringType ^CtMethod element)]
         (and (instance? CtClass owner)
              (.isAnonymous ^CtClass owner)))))

(defn- nullable-boxed-declaration?
  [ctx ^CtElement element ^CtTypeReference reference]
  (and
   (boxed-primitive-reference? reference)
   (cond
     (instance? CtField element)
     true

     (instance? CtParameter element)
     (or
      (directly-nullable? ctx element)
      (and
       (not (:destination-nonnull-boxed-by-default? ctx))
       (let [parent (when (.isParentInitialized element) (.getParent element))]
         (not (instance? CtLambda parent)))))

     (instance? CtMethod element)
     (or
      (directly-nullable? ctx element)
      (and
       (not (:destination-nonnull-boxed-by-default? ctx))
       (let [owner (.getDeclaringType ^CtMethod element)]
         (not (or (anonymous-method? element)
                  (and (instance? CtClass owner)
                       (some #(= "java.util.Iterator"
                                 (.getQualifiedName ^CtTypeReference %))
                             (.getSuperInterfaces owner))))))))

     (instance? CtLocalVariable element)
     (let [initializer (.getDefaultExpression ^CtLocalVariable element)]
       (or (and (instance? CtLiteral initializer)
                (nil? (.getValue ^CtLiteral initializer)))
           (and (instance? CtInvocation initializer)
                (let [occurrence
                      (occurrence! ctx (.getExecutable ^CtInvocation initializer)
                                   :executable)
                      referenced-declaration
                      (some-> initializer .getExecutable .getDeclaration)
                      declaration
                      (if (instance? CtMethod referenced-declaration)
                        referenced-declaration
                        (:declaration occurrence))]
                  (or (contains?
                       #{"executable:java.util.Map#get(java.lang.Object)"
                         "executable:java.util.TreeMap#get(java.lang.Object)"}
                       (:key occurrence))
                      (and
                       (= "executable:java.util.List#get(int)"
                          (:key occurrence))
                       (nullable-boxed-collection-expression?
                        (.getTarget ^CtInvocation initializer) []))
                      (nullable-runtime-boxed-primitive-method? occurrence)
                      (and (instance? CtMethod declaration)
                           (not (.isShadow ^CtMethod declaration))
                           (not (anonymous-method? declaration))))))))

     :else false)))

(defn- covariant-list-node [ctx ^CtElement element ^CtTypeReference reference]
  (when (and (= "java.util.List" (.getQualifiedName reference))
             (= 1 (count (.getActualTypeArguments reference))))
    (let [argument (first (.getActualTypeArguments reference))
          bound
          (when (instance? CtWildcardReference argument)
            (.getBoundingType ^CtWildcardReference argument))
          unbounded?
          (and (instance? CtWildcardReference argument)
               (= "?" (.getSimpleName argument))
               (or
                (nil? bound)
                (= "java.lang.Object" (.getQualifiedName bound))))]
      (when (and
             (instance? CtWildcardReference argument)
             (or unbounded?
                 (and (.isUpper ^CtWildcardReference argument) bound)))
        (csharp/generic-name
         (raw (if (or (instance? CtParameter element)
                      (and
                       (instance? CtLocalVariable element)
                       unbounded?))
                "global::System.Collections.Generic.IEnumerable"
                "global::System.Collections.Generic.IReadOnlyList"))
         [(if unbounded?
            (raw "object")
            (type-node ctx bound))])))))

(defn- unbounded-wildcard-reference? [^CtTypeReference reference]
  (and (instance? CtWildcardReference reference)
       (= "?" (.getSimpleName reference))
       (= "java.lang.Object"
          (some-> reference
                  ^CtWildcardReference
                  .getBoundingType
                  .getQualifiedName))))

(defn- unbounded-wildcard-collection-reference?
  [^CtTypeReference reference]
  (and (= "java.util.Collection" (some-> reference .getQualifiedName))
       (= 1 (count (.getActualTypeArguments reference)))
       (unbounded-wildcard-reference?
        (first (.getActualTypeArguments reference)))))

(defn- unbounded-wildcard-map-reference?
  [^CtTypeReference reference]
  (and (= "java.util.Map" (some-> reference .getQualifiedName))
       (= 2 (count (.getActualTypeArguments reference)))
       (some unbounded-wildcard-reference?
             (.getActualTypeArguments reference))))

(defn- boxed-primitive-collection-element
  [^CtTypeReference reference]
  (when (and (contains? #{"java.util.Collection" "java.util.List"}
                        (some-> reference .getQualifiedName))
             (= 1 (count (.getActualTypeArguments reference))))
    (let [element (first (.getActualTypeArguments reference))]
      (when (boxed-primitive-reference? element) element))))

(defn- enclosing-executable-element [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtExecutable current) current
      (.isParentInitialized ^CtElement current)
      (recur (.getParent ^CtElement current))
      :else nil)))

(defn- null-adding-boxed-collection-local?
  [^CtLocalVariable local]
  (when (boxed-primitive-collection-element (.getType local))
    (when-let [executable (enclosing-executable-element local)]
      (some
       (fn [^CtInvocation invocation]
         (let [target (.getTarget invocation)]
           (and (= "add" (.getSimpleName (.getExecutable invocation)))
                (instance? CtVariableAccess target)
                (identical?
                 local
                 (some-> target .getVariable .getDeclaration))
                (some #(and (instance? CtLiteral %)
                            (nil? (.getValue ^CtLiteral %)))
                      (.getArguments invocation)))))
       (.getElements executable (TypeFilter. CtInvocation))))))

(declare nullable-boxed-collection-declaration?)

(defn- nullable-boxed-collection-expression?
  [^CtExpression expression seen]
  (cond
    (nil? expression)
    false

    (instance? CtConditional expression)
    (or (nullable-boxed-collection-expression?
         (.getThenExpression ^CtConditional expression) seen)
        (nullable-boxed-collection-expression?
         (.getElseExpression ^CtConditional expression) seen))

    (instance? CtInvocation expression)
    (let [declaration (some-> expression .getExecutable .getDeclaration)]
      (and (instance? CtMethod declaration)
           (nullable-boxed-collection-declaration?
            declaration (.getType ^CtMethod declaration) seen)))

    (instance? CtVariableAccess expression)
    (let [declaration (some-> expression .getVariable .getDeclaration)]
      (and (instance? CtElement declaration)
           (nullable-boxed-collection-declaration?
            declaration (.getType declaration) seen)))

    :else false))

(defn- nullable-boxed-collection-declaration?
  ([^CtElement element ^CtTypeReference reference]
   (nullable-boxed-collection-declaration? element reference []))
  ([^CtElement element ^CtTypeReference reference seen]
   (let [declaration
         (if (instance? CtVariableAccess element)
           (or (some-> element .getVariable .getDeclaration) element)
           element)]
     (and
      (boxed-primitive-collection-element reference)
      (not-any? #(identical? declaration %) seen)
      (let [seen (conj seen declaration)]
        (cond
          (instance? CtLocalVariable declaration)
          (or
           (null-adding-boxed-collection-local? declaration)
           (nullable-boxed-collection-expression?
            (.getDefaultExpression ^CtLocalVariable declaration) seen))

          (instance? CtField declaration)
          (let [owner (.getDeclaringType ^CtField declaration)]
            (some
             (fn [^CtAssignment assignment]
               (let [assigned (.getAssigned assignment)]
                 (and
                  (instance? CtVariableAccess assigned)
                  (identical?
                   declaration
                   (some-> assigned .getVariable .getDeclaration))
                  (nullable-boxed-collection-expression?
                   (.getAssignment assignment) seen))))
             (.getElements owner (TypeFilter. CtAssignment))))

          (instance? CtMethod declaration)
          (when-let [body (.getBody ^CtMethod declaration)]
            (some
             (fn [^CtReturn return]
               (nullable-boxed-collection-expression?
                (.getReturnedExpression return) seen))
             (.getElements body (TypeFilter. CtReturn))))

          :else false))))))

(defn- nullable-boxed-collection-node
  [ctx ^CtTypeReference reference]
  (let [[target _rule]
        (mapped-type-base ctx reference (occurrence! ctx reference :type))
        element (boxed-primitive-collection-element reference)]
    (csharp/generic-name
     (raw target)
     [(sequence-node [(type-node ctx element) (raw "?")])])))

(defn- declaration-type-node [ctx ^CtElement element ^CtTypeReference reference]
  (let [base (or (when (nullable-boxed-collection-declaration?
                        element reference)
                   (nullable-boxed-collection-node ctx reference))
                 (when (or (instance? CtParameter element)
                           (instance? CtMethod element)
                           (and (instance? CtLocalVariable element)
                                (instance?
                                 CtInvocation
                                 (.getDefaultExpression
                                  ^CtLocalVariable element))))
                   (covariant-list-node ctx element reference))
                 (type-node ctx reference))]
    (if (and (not (.isPrimitive reference))
             (or (resolved-annotation?
                  ctx element "annotation:javax.annotation.Nullable")
                 (nullable-boxed-declaration? ctx element reference)))
      (sequence-node [base (raw "?")])
      base)))

(defn- child-node [children ^CtElement element]
  (:node (java/child-result children element)))

(defn- role [^CtElement element]
  (when (.isParentInitialized element)
    (str (.getRoleInParent element))))

(defn- local-type? [^CtType type]
  (and (not (.isTopLevel type))
       (= "statement" (role type))))

(defn- non-static-member-class? [^CtType type]
  (and (instance? CtClass type)
       (some? (.getDeclaringType type))
       (not (local-type? type))
       (not (.isAnonymous ^CtClass type))
       (not (.hasModifier ^CtModifiable type ModifierKind/STATIC))))

(defn- member-constructor? [declaration]
  (and (instance? CtConstructor declaration)
       (non-static-member-class? (.getDeclaringType ^CtConstructor declaration))))

(defn- anonymous-class? [^CtType type]
  (and (instance? CtClass type)
       (.isAnonymous ^CtClass type)))

(defn- anonymous-class-for-call [^CtConstructorCall call]
  (some #(when (and (instance? CtClass %)
                    (.isAnonymous ^CtClass %))
           %)
        (.getDirectChildren call)))

(defn- anonymous-class-name [^CtConstructorCall call]
  (let [{:keys [line column]} (spoon/source-location call)]
    (identifier (str "Anonymous_" (or line 0) "_" (or column 0)))))

(defn- descendant-of? [^CtElement element ^CtElement ancestor]
  (loop [current element]
    (cond
      (nil? current) false
      (identical? current ancestor) true
      (.isParentInitialized current) (recur (.getParent current))
      :else false)))

(defn- captured-local-declarations [^CtType local-type]
  (->> (.getElements local-type (TypeFilter. CtVariableAccess))
       (keep (fn [^CtVariableAccess access]
               (let [declaration (some-> access .getVariable .getDeclaration)]
                 (when (and declaration
                            (not (descendant-of? declaration local-type))
                            (not (and (instance? CtField declaration)
                                      (.hasModifier ^CtField declaration
                                                    ModifierKind/STATIC))))
                   declaration))))
       distinct
       (sort-by #(str (spoon/declaration-key %)))
       vec))

(defn- anonymous-captures [^CtClass anonymous-class]
  (->> (.getElements anonymous-class (TypeFilter. CtVariableAccess))
       (keep (fn [^CtVariableAccess access]
               (let [declaration (some-> access .getVariable .getDeclaration)]
                 (when (and (or (instance? CtLocalVariable declaration)
                                (instance? CtParameter declaration))
                            (not (descendant-of? declaration anonymous-class)))
                   declaration))))
       (reduce (fn [captures declaration]
                 (if (some #(identical? declaration %) captures)
                   captures
                   (conj captures declaration))) [])
       (sort-by (fn [^CtElement declaration]
                  (let [{:keys [file line column]}
                        (spoon/source-location declaration)]
                    [file line column])))
       vec))

(defn- nearest-enclosing-type [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtType current) current
      (.isParentInitialized ^CtElement current)
      (recur (.getParent ^CtElement current))
      :else nil)))

(defn- anonymous-uses-outer? [^CtClass anonymous-class ^CtType owner]
  (boolean
   (some #(= (.getQualifiedName owner)
             (some-> ^CtThisAccess % .getType .getQualifiedName))
         (.getElements anonymous-class (TypeFilter. CtThisAccess)))))

(defn- anonymous-iterator? [^CtConstructorCall call]
  (= "java.util.Iterator" (some-> call .getType .getQualifiedName)))

(defn- anonymous-x509-trust-manager? [^CtConstructorCall call]
  (= "javax.net.ssl.X509TrustManager"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-filter-output-stream? [^CtConstructorCall call]
  (= "java.io.FilterOutputStream"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-linked-hash-map? [^CtConstructorCall call]
  (= "java.util.LinkedHashMap"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-project-type? [^CtConstructorCall call]
  (let [declaration (some-> call .getType .getTypeDeclaration)]
    (and (or (instance? CtEnum declaration)
             (instance? CtClass declaration)
             (and (interface-type? declaration)
                  (not (.isShadow ^CtType declaration))))
         (not (.isShadow ^CtType declaration)))))

(defn- capture-name [ctx ^CtElement declaration]
  (or (when-let [^IdentityHashMap names (:capture-names ctx)]
        (.get names declaration))
      (some (fn [{captured :declaration name :name}]
              (when (or (identical? declaration captured)
                        (= (spoon/declaration-key declaration)
                           (spoon/declaration-key captured)))
                name))
            (:capture-bindings ctx))))

(defn- local-variable-owner [^CtLocalVariable variable]
  (loop [current (when (.isParentInitialized variable)
                   (.getParent variable))]
    (cond
      (nil? current) nil
      (instance? CtExecutable current) current
      (.isParentInitialized ^CtElement current)
      (recur (.getParent ^CtElement current))
      :else nil)))

(defn- local-declaration-name [^CtElement declaration]
  (if-not (instance? CtLocalVariable declaration)
    (identifier (.getSimpleName declaration))
    (let [base (identifier (.getSimpleName declaration))
          owner (local-variable-owner declaration)
          duplicates
          (when owner
            (filter #(= (.getSimpleName declaration)
                        (.getSimpleName ^CtLocalVariable %))
                    (.getElements owner (TypeFilter. CtLocalVariable))))]
      (if (< 1 (count duplicates))
        (let [{:keys [line column]} (spoon/source-location declaration)]
          (str base "__" (or line 0) "_" (or column 0)))
        base))))

(defn- local-reference-name [ctx reference]
  (if-let [name (or (some-> reference .getDeclaration (capture-name ctx))
                    (let [matches
                          (filter #(= (.getSimpleName ^CtElement (:declaration %))
                                      (.getSimpleName ^CtElement reference))
                                  (:capture-bindings ctx))]
                      (when (= 1 (count matches)) (:name (first matches)))))]
    (str "this." name)
    (if-let [declaration (some-> reference .getDeclaration)]
      (local-declaration-name declaration)
      (identifier (.getSimpleName ^CtElement reference)))))

(defn- this-node [ctx ^CtThisAccess access]
  (or
   (when-let [destination-this-node (get-in ctx [:services :this-node])]
     (destination-this-node access))
   (if (and (:outer-type ctx)
            (= (.getQualifiedName ^CtType (:outer-type ctx))
               (some-> access .getType .getQualifiedName)))
     (raw (str "this." (or (:outer-field-name ctx) "__outer")))
     (raw "this"))))

(defn- validate-local-type! [^CtType local-type]
  (when-let [captures (seq (captured-local-declarations local-type))]
    (throw
     (ex-info
      "Java local class captures declarations that cannot be hoisted safely"
      {:kind :unsupported-local-class-capture
       :source-element local-type
       :source-identity (spoon/declaration-key local-type)
       :source-location (spoon/source-location local-type)
       :captures (mapv (fn [^CtElement declaration]
                         {:identity (or (spoon/declaration-key declaration)
                                        (spoon/frontend-identity declaration))
                          :location (spoon/source-location declaration)})
                       captures)})))
  local-type)

(declare switch-expression-yield?)

(defn- statement-expression? [^CtElement element]
  (and (instance? CtStatement element)
       (or (= "statement" (role element))
           (and (contains? #{"then" "else"} (role element))
                (.isParentInitialized element)
                (instance? CtIf (.getParent element)))
           (and (.isParentInitialized element)
                (instance? CtYieldStatement (.getParent element))
                (not (switch-expression-yield?
                      ^CtYieldStatement (.getParent element)))))))

(defn- statement-node [children ^CtStatement statement]
  (let [node (child-node children statement)]
    (if (instance? CtBlock statement)
      node
      (csharp/block [node]))))

(defn- labeled-loop-body-node
  [context children ^CtStatement body ^CtStatement loop]
  (if-not (java/labeled-targeted? context loop :continue)
    (statement-node children body)
    (if (instance? CtBlock body)
      (child-node children body)
      (csharp/block
       [(child-node children body)
        (raw (str (java/labeled-target-name context loop :continue) ":;"))]))))

(defn- escape-string [value]
  (str "\""
       (apply
        str
        (map
         (fn [character]
           (case character
             \" "\\\""
             \\ "\\\\"
             \newline "\\n"
             \return "\\r"
             \tab "\\t"
             \backspace "\\b"
             \formfeed "\\f"
             (if (or (< (int character) 32)
                     (> (int character) 126))
               (format "\\u%04X" (int character))
               (str character))))
         (str value)))
       "\""))

(defn- escape-character [value]
  (let [character (char value)]
    (str
     "'"
     (case character
       \' "\\'"
       \\ "\\\\"
       \newline "\\n"
       \return "\\r"
       \tab "\\t"
       \backspace "\\b"
       \formfeed "\\f"
       (if (or (< (int character) 32)
               (> (int character) 126))
         (format "\\u%04X" (int character))
         (str character)))
     "'")))

(defn- literal-node [^CtLiteral literal]
  (let [value (.getValue literal)
        byte-literal?
        (and (= "byte" (some-> literal .getType .getQualifiedName))
             (instance? Number value))]
    (raw
     (if byte-literal?
       (str "unchecked((sbyte)" value ")")
       (cond
         (nil? value) "default!"
         (string? value) (escape-string value)
         (instance? Character value)
         (escape-character value)
         (instance? Boolean value) (if value "true" "false")
         (instance? Long value) (str value "L")
         (instance? Float value) (str value "F")
         (instance? Double value)
         (cond
           (Double/isNaN value) "double.NaN"
           (= Double/POSITIVE_INFINITY value) "double.PositiveInfinity"
           (= Double/NEGATIVE_INFINITY value) "double.NegativeInfinity"
           :else (str value "D"))
         :else (str value))))))

(declare method-name)

(defn- destination-field-name [ctx ^CtField field]
  (let [owner (.getDeclaringType field)
        normalized (ordinary-member-name ctx field)
        normalized-peers
        (when owner
          (filter #(= normalized (ordinary-member-name ctx %))
                  (.getFields ^CtType owner)))
        ;; Java public fields can differ only by source casing while the C#
        ;; public-name policy intentionally normalizes acronym casing. Preserve
        ;; the exact source identifier for every member of such a collision so
        ;; both declarations remain accessible and references stay one-to-one.
        base (if (< 1 (count normalized-peers))
               (identifier (.getSimpleName field))
               normalized)
        type-collision?
        (and owner
             (= base (pascal (.getSimpleName ^CtType owner))))
        method-collision?
        (and owner
             (seq (.getMethodsByName ^CtType owner (.getSimpleName field))))]
    (if-not (or type-collision? method-collision?)
      base
      (let [reserved (->> (concat (.getFields ^CtType owner)
                                  (.getMethods ^CtType owner))
                          (remove #(identical? field %))
                          (map #(cond
                                  (instance? CtMethod %)
                                  (method-name ctx owner %)

                                  (instance? CtField %)
                                  (ordinary-member-name ctx %)

                                  :else
                                  (identifier (.getSimpleName ^CtElement %))))
                          set)]
        (loop [suffix nil]
          (let [candidate (str "__field_" base suffix)]
            (if (contains? reserved candidate)
              (recur (if suffix (inc suffix) 2))
              candidate)))))))

(def ^:private extended-neutral-executable-keys
  #{"executable:java.io.ByteArrayOutputStream#write(byte[],int,int)"
    "executable:java.io.ByteArrayOutputStream#close()"
    "executable:java.io.ByteArrayOutputStream#size()"
    "executable:java.io.ByteArrayInputStream#available()"
    "executable:java.io.ByteArrayInputStream#reset()"
    "executable:java.lang.ref.SoftReference#get()"
    "executable:java.lang.ref.WeakReference#get()"
    "executable:java.lang.ref.Reference#get()"
    "executable:java.lang.ref.Reference#clear()"
    "executable:java.io.ByteArrayOutputStream#reset()"
    "executable:java.io.ByteArrayOutputStream#toString(java.lang.String)"
    "executable:java.io.BufferedReader#readLine()"
    "executable:java.io.BufferedReader#ready()"
    "executable:java.io.BufferedWriter#newLine()"
    "executable:java.io.BufferedWriter#write(int)"
    "executable:java.io.BufferedWriter#write(java.lang.String)"
    "executable:java.io.BufferedWriter#flush()"
    "executable:java.io.DataOutputStream#flush()"
    "executable:java.io.DataOutputStream#write(byte[],int,int)"
    "executable:java.io.DataOutputStream#writeByte(int)"
    "executable:java.io.DataOutputStream#writeInt(int)"
    "executable:java.io.DataOutputStream#writeLong(long)"
    "executable:java.io.DataOutputStream#writeShort(int)"
    "executable:java.io.File#canRead()"
    "executable:java.io.File#getName()"
    "executable:java.io.File#getPath()"
    "executable:java.io.File#equals(java.lang.Object)"
    "executable:java.io.File#isHidden()"
    "executable:java.io.File#canWrite()"
    "executable:java.io.File#lastModified()"
    "executable:java.io.File#listFiles()"
    "executable:java.io.File#isFile()"
    "executable:java.io.File#toURI()"
    "executable:java.io.FilterOutputStream#write(byte[])"
    "executable:java.io.FilterOutputStream#write(byte[],int,int)"
    "executable:java.io.FilterOutputStream#write(int)"
    "executable:java.io.FilterInputStream#close()"
    "executable:java.util.zip.Inflater#finished()"
    "executable:java.util.zip.Inflater#needsInput()"
    "executable:java.util.zip.Inflater#setInput(byte[],int,int)"
    "executable:java.util.zip.Inflater#inflate(byte[])"
    "executable:java.util.zip.Inflater#end()"
    "executable:java.util.zip.Deflater#end()"
    "executable:java.util.zip.DeflaterOutputStream#write(byte[],int,int)"
    "executable:java.util.zip.DeflaterOutputStream#close()"
    "executable:java.io.InputStream#mark(int)"
    "executable:java.io.InputStream#markSupported()"
    "executable:java.io.InputStream#reset()"
    "executable:java.io.BufferedInputStream#mark(int)"
    "executable:java.io.BufferedInputStream#markSupported()"
    "executable:java.io.BufferedInputStream#read(byte[])"
    "executable:java.io.BufferedInputStream#read(byte[],int,int)"
    "executable:java.io.BufferedInputStream#reset()"
    "executable:java.io.FilterInputStream#read()"
    "executable:java.io.FilterInputStream#read(byte[])"
    "executable:java.io.FilterInputStream#read(byte[],int,int)"
    "executable:java.io.FilterInputStream#skip(long)"
    "executable:java.io.InputStream#skip(long)"
    "executable:java.io.LineNumberReader#readLine()"
    "executable:java.io.Reader#read(char[],int,int)"
    "executable:java.io.StringWriter#write(java.lang.String)"
    "executable:java.io.StringWriter#toString()"
    "executable:java.io.Writer#write(int)"
    "executable:java.io.Writer#write(java.lang.String)"
    "executable:java.io.Writer#write(char[])"
    "executable:java.io.Writer#append(java.lang.CharSequence)"
    "executable:java.io.Writer#append(char)"
    "executable:java.io.Writer#flush()"
    "executable:java.io.Writer#close()"
    "executable:java.io.PrintWriter#close()"
    "executable:java.io.PrintWriter#println(java.lang.String)"
    "executable:java.io.PrintWriter#flush()"
    "executable:java.io.PrintStream#print(java.lang.String)"
    "executable:java.io.PrintStream#println()"
    "executable:java.io.PrintStream#flush()"
    "executable:java.lang.Process#isAlive()"
    "executable:java.lang.Process#getInputStream()"
    "executable:java.lang.Process#getOutputStream()"
    "executable:java.lang.Process#waitFor(long,java.util.concurrent.TimeUnit)"
    "executable:java.lang.Process#destroyForcibly()"
    "executable:java.lang.ProcessBuilder#directory(java.io.File)"
    "executable:java.lang.ProcessBuilder#redirectError(java.lang.ProcessBuilder$Redirect)"
    "executable:java.lang.ProcessBuilder#start()"
    "executable:java.util.Random#nextBytes(byte[])"
    "executable:java.util.Random#nextInt()"
    "executable:java.util.Random#nextLong()"
    "executable:javax.crypto.Cipher#doFinal()"
    "executable:javax.crypto.Cipher#doFinal(byte[])"
    "executable:javax.crypto.Cipher#getInstance(java.lang.String)"
    "executable:javax.crypto.Cipher#getMaxAllowedKeyLength(java.lang.String)"
    "executable:javax.crypto.Cipher#init(int,java.security.Key)"
    "executable:javax.crypto.Cipher#init(int,java.security.Key,java.security.spec.AlgorithmParameterSpec)"
    "executable:javax.crypto.Cipher#update(byte[],int,int)"
    "executable:java.lang.Boolean#parseBoolean(java.lang.String)"
    "executable:java.lang.Boolean#getBoolean(java.lang.String)"
    "executable:java.lang.Boolean#toString()"
    "executable:java.lang.Byte#parseByte(java.lang.String,int)"
    "executable:java.lang.Byte#toUnsignedInt(byte)"
    "executable:java.lang.Byte#toUnsignedLong(byte)"
    "executable:java.lang.Character#digit(char,int)"
    "executable:java.lang.Character#charCount(int)"
    "executable:java.lang.Character#getName(int)"
    "executable:java.lang.Character#isDefined(int)"
    "executable:java.lang.Character#getType(int)"
    "executable:java.lang.Character#getType(char)"
    "executable:java.lang.Character#isHighSurrogate(char)"
    "executable:java.lang.Character#isLowSurrogate(char)"
    "executable:java.lang.Character#toTitleCase(int)"
    "executable:java.lang.Character#isTitleCase(int)"
    "executable:java.lang.Character#isUpperCase(char)"
    "executable:java.lang.Character#isUpperCase(int)"
    "executable:java.lang.Character#toUpperCase(int)"
    "executable:java.lang.Character#isDigit(char)"
    "executable:java.lang.Character#isDigit(int)"
    "executable:java.lang.Character#isBmpCodePoint(int)"
    "executable:java.lang.Character#isMirrored(char)"
    "executable:java.lang.Character#isMirrored(int)"
    "executable:java.lang.Character#isWhitespace(char)"
    "executable:java.lang.Character#isValidCodePoint(int)"
    "executable:java.lang.Character#isSurrogatePair(char,char)"
    "executable:java.lang.Character#toString(char)"
    "executable:java.lang.Character#toString()"
    "executable:java.lang.Class#asSubclass(java.lang.Class)"
    "executable:java.lang.Class#cast(java.lang.Object)"
    "executable:java.lang.Class#isAssignableFrom(java.lang.Class)"
    "executable:java.lang.Double#compare(double,double)"
    "executable:java.lang.Double#hashCode(double)"
    "executable:java.lang.Class#getAnnotation(java.lang.Class)"
    "executable:java.lang.Class#getDeclaredConstructor(java.lang.Class[])"
    "executable:java.lang.Class#getFields()"
    "executable:java.lang.Class#getResourceAsStream(java.lang.String)"
    "executable:java.lang.Double#parseDouble(java.lang.String)"
    "executable:java.lang.Double#valueOf(java.lang.String)"
    "executable:java.lang.Double#isFinite(double)"
    "executable:java.lang.Double#isInfinite(double)"
    "executable:java.lang.Double#isInfinite()"
    "executable:java.lang.Double#isNaN(double)"
    "executable:java.lang.Double#isNaN()"
    "executable:java.lang.Double#toString()"
    "executable:java.lang.Double#toString(double)"
    "executable:java.lang.Enum#toString()"
    "executable:java.lang.Float#compare(float,float)"
    "executable:java.lang.Float#floatToIntBits(float)"
    "executable:java.lang.Float#hashCode(float)"
    "executable:java.lang.Float#isFinite(float)"
    "executable:java.lang.Float#isInfinite(float)"
    "executable:java.lang.Float#isNaN(float)"
    "executable:java.lang.Float#parseFloat(java.lang.String)"
    "executable:java.lang.Float#toString(float)"
    "executable:java.lang.Float#floatValue()"
    "executable:java.lang.Float#doubleValue()"
    "executable:java.lang.Integer#compare(int,int)"
    "executable:java.lang.Integer#compareTo(java.lang.Integer)"
    "executable:java.lang.Integer#equals(java.lang.Object)"
    "executable:java.lang.Integer#intValue()"
    "executable:java.lang.Integer#longValue()"
    "executable:java.lang.Integer#shortValue()"
    "executable:java.lang.Integer#numberOfLeadingZeros(int)"
    "executable:java.lang.Integer#signum(int)"
    "executable:java.lang.Long#numberOfLeadingZeros(long)"
    "executable:java.lang.Long#numberOfTrailingZeros(long)"
    "executable:java.lang.Long#signum(long)"
    "executable:java.lang.Long#hashCode(long)"
    "executable:java.lang.Long#byteValue()"
    "executable:java.lang.Long#shortValue()"
    "executable:java.lang.Long#intValue()"
    "executable:java.lang.Long#longValue()"
    "executable:java.lang.Long#equals(java.lang.Object)"
    "executable:java.lang.Double#doubleValue()"
    "executable:java.lang.Boolean#booleanValue()"
    "executable:java.lang.Float#equals(java.lang.Object)"
    "executable:java.lang.Double#equals(java.lang.Object)"
    "executable:java.lang.Integer#highestOneBit(int)"
    "executable:java.lang.Integer#toHexString(int)"
    "executable:java.lang.Long#toHexString(long)"
    "executable:java.lang.Long#valueOf(java.lang.String)"
    "executable:java.lang.Integer#valueOf(int)"
    "executable:java.lang.Integer#valueOf(java.lang.String)"
    "executable:java.lang.Long#compare(long,long)"
    "executable:java.lang.Long#max(long,long)"
    "executable:java.lang.Math#acos(double)"
    "executable:java.lang.Math#abs(double)"
    "executable:java.lang.Math#abs(float)"
    "executable:java.lang.Math#abs(int)"
    "executable:java.lang.Math#abs(long)"
    "executable:java.lang.Math#atan2(double,double)"
    "executable:java.lang.Math#ceil(double)"
    "executable:java.lang.Math#cos(double)"
    "executable:java.lang.Math#floor(double)"
    "executable:java.lang.Math#floorDiv(int,int)"
    "executable:java.lang.Math#log(double)"
    "executable:java.lang.Math#log10(double)"
    "executable:java.lang.Math#pow(double,double)"
    "executable:java.lang.Math#round(double)"
    "executable:java.lang.Math#round(float)"
    "executable:java.lang.Math#signum(double)"
    "executable:java.lang.Math#signum(float)"
    "executable:java.lang.Math#sin(double)"
    "executable:java.lang.Math#sqrt(double)"
    "executable:java.lang.Math#toDegrees(double)"
    "executable:java.lang.Math#toRadians(double)"
    "executable:java.lang.Math#addExact(long,long)"
    "executable:java.lang.Math#addExact(int,int)"
    "executable:java.lang.Math#negateExact(int)"
    "executable:java.lang.Math#negateExact(long)"
    "executable:java.lang.Math#incrementExact(int)"
    "executable:java.lang.Math#incrementExact(long)"
    "executable:java.lang.Math#decrementExact(int)"
    "executable:java.lang.Math#decrementExact(long)"
    "executable:java.lang.Math#multiplyExact(long,long)"
    "executable:java.lang.Math#multiplyExact(long,int)"
    "executable:java.lang.Math#multiplyExact(int,int)"
    "executable:java.lang.Math#subtractExact(long,long)"
    "executable:java.lang.StrictMath#addExact(long,long)"
    "executable:java.lang.StrictMath#addExact(int,int)"
    "executable:java.lang.StrictMath#negateExact(int)"
    "executable:java.lang.StrictMath#negateExact(long)"
    "executable:java.lang.StrictMath#incrementExact(int)"
    "executable:java.lang.StrictMath#incrementExact(long)"
    "executable:java.lang.StrictMath#decrementExact(int)"
    "executable:java.lang.StrictMath#decrementExact(long)"
    "executable:java.lang.StrictMath#toIntExact(long)"
    "executable:java.lang.StrictMath#abs(double)"
    "executable:java.lang.StrictMath#abs(long)"
    "executable:java.lang.StrictMath#acos(double)"
    "executable:java.lang.StrictMath#asin(double)"
    "executable:java.lang.StrictMath#atan(double)"
    "executable:java.lang.StrictMath#atan2(double,double)"
    "executable:java.lang.StrictMath#cbrt(double)"
    "executable:java.lang.StrictMath#ceil(double)"
    "executable:java.lang.StrictMath#copySign(double,double)"
    "executable:java.lang.StrictMath#cos(double)"
    "executable:java.lang.StrictMath#exp(double)"
    "executable:java.lang.StrictMath#floor(double)"
    "executable:java.lang.StrictMath#getExponent(double)"
    "executable:java.lang.Math#getExponent(double)"
    "executable:java.lang.Double#doubleToRawLongBits(double)"
    "executable:java.lang.StrictMath#log(double)"
    "executable:java.lang.StrictMath#log10(double)"
    "executable:java.lang.StrictMath#max(double,double)"
    "executable:java.lang.StrictMath#max(long,long)"
    "executable:java.lang.StrictMath#min(double,double)"
    "executable:java.lang.StrictMath#min(long,long)"
    "executable:java.lang.StrictMath#pow(double,double)"
    "executable:java.lang.StrictMath#rint(double)"
    "executable:java.lang.StrictMath#signum(double)"
    "executable:java.lang.StrictMath#sin(double)"
    "executable:java.lang.StrictMath#sqrt(double)"
    "executable:java.lang.StrictMath#tan(double)"
    "executable:java.lang.StrictMath#multiplyExact(long,long)"
    "executable:java.lang.StrictMath#multiplyExact(long,int)"
    "executable:java.lang.StrictMath#multiplyExact(int,int)"
    "executable:java.lang.StrictMath#subtractExact(long,long)"
    "executable:java.math.BigInteger#mod(java.math.BigInteger)"
    "executable:java.math.BigInteger#not()"
    "executable:java.math.BigInteger#shiftRight(int)"
    "executable:java.math.BigInteger#and(java.math.BigInteger)"
    "executable:java.math.BigInteger#equals(java.lang.Object)"
    "executable:java.math.BigInteger#intValue()"
    "executable:java.math.BigInteger#toString(int)"
    "executable:java.math.BigInteger#toByteArray()"
    "executable:java.math.BigInteger#valueOf(long)"
    "executable:java.math.BigDecimal#divide(java.math.BigDecimal,int,java.math.RoundingMode)"
    "executable:java.math.BigDecimal#intValue()"
    "executable:java.math.BigDecimal#multiply(java.math.BigDecimal)"
    "executable:java.math.BigDecimal#setScale(int,java.math.RoundingMode)"
    "executable:java.math.BigDecimal#stripTrailingZeros()"
    "executable:java.math.BigDecimal#toPlainString()"
    "executable:java.math.BigDecimal#toString()"
    "executable:java.math.BigDecimal#valueOf(double)"
    "executable:java.lang.Number#doubleValue()"
    "executable:java.lang.Number#floatValue()"
    "executable:java.lang.Number#intValue()"
    "executable:java.lang.Number#longValue()"
    "executable:java.lang.Object#clone()"
    "executable:java.lang.Object#equals(java.lang.Object)"
    "executable:java.lang.Record#equals(java.lang.Object)"
    "executable:java.nio.file.Path#equals(java.lang.Object)"
    "executable:java.lang.Object#hashCode()"
    "executable:java.lang.Record#hashCode()"
    "executable:java.lang.Enum#hashCode()"
    "executable:java.lang.Record#toString()"
    "executable:java.lang.Enum#equals(java.lang.Object)"
    "executable:java.lang.Boolean#equals(java.lang.Object)"
    "executable:java.lang.reflect.Constructor#newInstance(java.lang.Object[])"
    "executable:java.lang.reflect.AccessibleObject#isAnnotationPresent(java.lang.Class)"
    "executable:java.lang.reflect.Field#getAnnotation(java.lang.Class)"
    "executable:java.lang.reflect.Field#isAnnotationPresent(java.lang.Class)"
    "executable:java.lang.reflect.Field#getModifiers()"
    "executable:java.lang.reflect.Modifier#isFinal(int)"
    "executable:java.lang.String#charAt(int)"
    "executable:java.lang.String#codePointAt(int)"
    "executable:java.lang.String#codePointCount(int,int)"
    "executable:java.lang.String#codePoints()"
    "executable:java.lang.String#compareTo(java.lang.String)"
    "executable:java.lang.String#format(java.util.Locale,java.lang.String,java.lang.Object[])"
    "executable:java.lang.String#indexOf(java.lang.String)"
    "executable:java.lang.String#replace(char,char)"
    "executable:java.lang.String#replace(java.lang.CharSequence,java.lang.CharSequence)"
    "executable:java.lang.String#replaceAll(java.lang.String,java.lang.String)"
    "executable:java.lang.String#replaceFirst(java.lang.String,java.lang.String)"
    "executable:java.lang.String#toLowerCase(java.util.Locale)"
    "executable:java.lang.String#toUpperCase(java.util.Locale)"
    "executable:java.lang.CharSequence#toString()"
    "executable:java.lang.CharSequence#length()"
    "executable:java.lang.CharSequence#charAt(int)"
    "executable:java.lang.Throwable#getLocalizedMessage()"
    "executable:java.lang.String#startsWith(java.lang.String,int)"
    "executable:java.lang.String#valueOf(boolean)"
    "executable:java.lang.String#valueOf(char)"
    "executable:java.lang.String#valueOf(char[])"
    "executable:java.lang.String#valueOf(double)"
    "executable:java.lang.String#valueOf(float)"
    "executable:java.lang.String#valueOf(int)"
    "executable:java.lang.String#valueOf(java.lang.Object)"
    "executable:java.lang.String#valueOf(long)"
    "executable:java.lang.StringBuilder#append(java.lang.CharSequence,int,int)"
    "executable:java.lang.StringBuilder#append(java.lang.Object)"
    "executable:java.lang.StringBuilder#charAt(int)"
    "executable:java.lang.AbstractStringBuilder#charAt(int)"
    "executable:java.lang.StringBuilder#delete(int,int)"
    "executable:java.lang.AbstractStringBuilder#delete(int,int)"
    "executable:java.lang.StringBuilder#deleteCharAt(int)"
    "executable:java.lang.StringBuilder#reverse()"
    "executable:java.lang.StringBuilder#setLength(int)"
    "executable:java.lang.AbstractStringBuilder#setLength(int)"
    "executable:java.lang.System#getProperty(java.lang.String)"
    "executable:java.lang.System#getProperty(java.lang.String,java.lang.String)"
    "executable:java.lang.System#getenv()"
    "executable:java.lang.System#getenv(java.lang.String)"
    "executable:java.lang.System#getProperties()"
    "executable:java.lang.System#identityHashCode(java.lang.Object)"
    "executable:java.lang.System#lineSeparator()"
    "executable:java.lang.System#exit(int)"
    "executable:java.security.SecureRandom#nextBytes(byte[])"
    "executable:java.security.SecureRandom#nextInt()"
    "executable:java.security.KeyStore#size()"
    "executable:java.security.KeyStore#aliases()"
    "executable:java.security.KeyStore#containsAlias(java.lang.String)"
    "executable:java.security.KeyStore#getCertificate(java.lang.String)"
    "executable:java.security.KeyStore#getKey(java.lang.String,char[])"
    "executable:java.util.Enumeration#nextElement()"
    "executable:java.util.Enumeration#hasMoreElements()"
    "executable:java.nio.Buffer#hasRemaining()"
    "executable:java.nio.charset.Charset#forName(java.lang.String)"
    "executable:java.nio.charset.Charset#name()"
    "executable:java.nio.charset.Charset#newDecoder()"
    "executable:java.nio.charset.CharsetDecoder#decode(java.nio.ByteBuffer)"
    "executable:java.nio.charset.CharsetDecoder#onMalformedInput(java.nio.charset.CodingErrorAction)"
    "executable:java.nio.charset.CharsetDecoder#onUnmappableCharacter(java.nio.charset.CodingErrorAction)"
    "executable:java.nio.CharBuffer#toString()"
    "executable:java.nio.CharBuffer#wrap(char[],int,int)"
    "executable:java.nio.ByteBuffer#get(byte[])"
    "executable:java.nio.ByteBuffer#mark()"
    "executable:java.nio.ByteBuffer#reset()"
    "executable:java.nio.file.Files#readAllBytes(java.nio.file.Path)"
    "executable:java.nio.file.Files#find(java.nio.file.Path,int,java.util.function.BiPredicate,java.nio.file.FileVisitOption[])"
    "executable:java.nio.file.attribute.BasicFileAttributes#isRegularFile()"
    "executable:java.nio.file.Paths#get(java.lang.String,java.lang.String[])"
    "executable:java.nio.file.Paths#get(java.net.URI)"
    "executable:java.util.Arrays#binarySearch(int[],int)"
    "executable:java.util.Arrays#binarySearch(java.lang.Object[],java.lang.Object,java.util.Comparator)"
    "executable:java.util.Arrays#copyOf(byte[],int)"
    "executable:java.util.Arrays#copyOf(float[],int)"
    "executable:java.util.Arrays#copyOfRange(byte[],int,int)"
    "executable:java.util.Arrays#deepToString(java.lang.Object[])"
    "executable:java.util.Arrays#fill(int[],int)"
    "executable:java.util.Arrays#fill(byte[],byte)"
    "executable:java.util.Arrays#fill(byte[],int,int,byte)"
    "executable:java.util.Arrays#fill(float[],float)"
    "executable:java.util.Arrays#fill(double[],double)"
    "executable:java.util.Arrays#stream(java.lang.Object[])"
    "executable:java.util.Arrays#toString(float[])"
    "executable:java.util.Arrays#toString(int[])"
    "executable:java.util.Arrays#toString(java.lang.Object[])"
    "executable:java.util.List#listIterator()"
    "executable:java.util.List#listIterator(int)"
    "executable:java.util.List#containsAll(java.util.Collection)"
    "executable:java.util.Collection#containsAll(java.util.Collection)"
    "executable:java.util.AbstractCollection#containsAll(java.util.Collection)"
    "executable:java.util.Set#containsAll(java.util.Collection)"
    "executable:java.util.Collection#size()"
    "executable:java.util.ListIterator#set(java.lang.Object)"
    "executable:java.util.ListIterator#next()"
    "executable:java.util.ListIterator#hasNext()"
    "executable:java.util.ListIterator#remove()"
    "executable:java.util.ListIterator#hasPrevious()"
    "executable:java.util.ListIterator#previous()"
    "executable:java.util.ListIterator#nextIndex()"
    "executable:java.util.ListIterator#previousIndex()"
    "executable:java.util.ListIterator#add(java.lang.Object)"
    "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)"
    "executable:java.util.Calendar#getInstance(java.util.TimeZone)"
    "executable:java.util.Calendar#clear()"
    "executable:java.util.Calendar#compareTo(java.util.Calendar)"
    "executable:java.util.Calendar#equals(java.lang.Object)"
    "executable:java.util.Calendar#get(int)"
    "executable:java.util.Calendar#getTimeInMillis()"
    "executable:java.util.Calendar#set(int,int)"
    "executable:java.util.Calendar#set(int,int,int,int,int,int)"
    "executable:java.util.Calendar#setTimeInMillis(long)"
    "executable:java.util.Calendar#setLenient(boolean)"
    "executable:java.util.Calendar#setTimeZone(java.util.TimeZone)"
    "executable:java.util.GregorianCalendar#setTimeZone(java.util.TimeZone)"
    "executable:java.util.Calendar#getTimeZone()"
    "executable:java.util.Calendar#add(int,int)"
    "executable:java.util.GregorianCalendar#add(int,int)"
    "executable:java.util.GregorianCalendar#from(java.time.ZonedDateTime)"
    "executable:java.text.Bidi#getBaseLevel()"
    "executable:java.text.Bidi#getRunCount()"
    "executable:java.text.Bidi#getRunLevel(int)"
    "executable:java.text.Bidi#getRunLimit(int)"
    "executable:java.text.Bidi#getRunStart(int)"
    "executable:java.text.Bidi#isMixed()"
    "executable:java.text.Bidi#reorderVisually(byte[],int,java.lang.Object[],int,int)"
    "executable:java.text.DecimalFormat#setDecimalFormatSymbols(java.text.DecimalFormatSymbols)"
    "executable:java.text.DecimalFormatSymbols#getInstance(java.util.Locale)"
    "executable:java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)"
    "executable:java.text.NumberFormat#format(long)"
    "executable:java.text.NumberFormat#format(double)"
    "executable:java.text.NumberFormat#getMaximumFractionDigits()"
    "executable:java.text.NumberFormat#getNumberInstance(java.util.Locale)"
    "executable:java.text.DecimalFormat#setMinimumFractionDigits(int)"
    "executable:java.text.NumberFormat#setMinimumFractionDigits(int)"
    "executable:java.text.DecimalFormat#setMaximumFractionDigits(int)"
    "executable:java.text.NumberFormat#setMaximumFractionDigits(int)"
    "executable:java.text.DecimalFormat#setGroupingUsed(boolean)"
    "executable:java.text.NumberFormat#setGroupingUsed(boolean)"
    "executable:java.util.TimeZone#getID()"
    "executable:java.util.TimeZone#getOffset(long)"
    "executable:java.util.TimeZone#setRawOffset(int)"
    "executable:java.util.Collection#add(java.lang.Object)"
    "executable:java.util.AbstractCollection#add(java.lang.Object)"
    "executable:java.util.Collection#isEmpty()"
    "executable:java.util.AbstractCollection#contains(java.lang.Object)"
    "executable:java.util.Collection#removeAll(java.util.Collection)"
    "executable:java.util.Collection#retainAll(java.util.Collection)"
    "executable:java.util.AbstractCollection#removeAll(java.util.Collection)"
    "executable:java.util.AbstractCollection#retainAll(java.util.Collection)"
    "executable:java.util.AbstractSet#removeAll(java.util.Collection)"
    "executable:java.util.ArrayList#add(java.lang.Object)"
    "executable:java.util.LinkedList#add(java.lang.Object)"
    "executable:java.util.LinkedList#addFirst(java.lang.Object)"
    "executable:java.util.Deque#addFirst(java.lang.Object)"
    "executable:java.util.ArrayDeque#addFirst(java.lang.Object)"
    "executable:java.util.ArrayList#clear()"
    "executable:java.util.ArrayList#ensureCapacity(int)"
    "executable:java.util.Collections#sort(java.util.List)"
    "executable:java.util.Collections#sort(java.util.List,java.util.Comparator)"
    "executable:java.util.Collections#reverse(java.util.List)"
    "executable:java.util.Base64#getDecoder()"
    "executable:java.util.Base64#getEncoder()"
    "executable:java.util.Base64$Decoder#decode(java.lang.String)"
    "executable:java.util.Base64$Encoder#encodeToString(byte[])"
    "executable:java.util.Collections#max(java.util.Collection)"
    "executable:java.util.Collections#min(java.util.Collection)"
    "executable:java.util.Collections#emptyIterator()"
    "executable:java.util.Collections#emptySet()"
    "executable:java.util.Collections#newSetFromMap(java.util.Map)"
    "executable:java.util.Collections#unmodifiableSet(java.util.Set)"
    "executable:java.util.Comparator#naturalOrder()"
    "executable:java.util.Comparator#comparingInt(java.util.function.ToIntFunction)"
    "executable:java.util.Comparator#comparing(java.util.function.Function)"
    "executable:java.util.Comparator#thenComparingInt(java.util.function.ToIntFunction)"
    "executable:java.util.Comparator#thenComparing(java.util.Comparator)"
    "executable:java.util.Deque#pop()"
    "executable:java.util.Deque#push(java.lang.Object)"
    "executable:java.util.Deque#add(java.lang.Object)"
    "executable:java.util.Deque#addAll(java.util.Collection)"
    "executable:java.util.Deque#contains(java.lang.Object)"
    "executable:java.util.Deque#isEmpty()"
    "executable:java.util.Deque#peek()"
    "executable:java.util.Deque#removeFirst()"
    "executable:java.util.Deque#size()"
    "executable:java.util.Deque#clear()"
    "executable:java.util.PriorityQueue#add(java.lang.Object)"
    "executable:java.util.PriorityQueue#isEmpty()"
    "executable:java.util.PriorityQueue#peek()"
    "executable:java.util.PriorityQueue#poll()"
    "executable:java.util.Properties#getProperty(java.lang.String)"
    "executable:java.util.Properties#getProperty(java.lang.String,java.lang.String)"
    "executable:java.util.Properties#load(java.io.InputStream)"
    "executable:java.util.AbstractCollection#isEmpty()"
    "executable:java.util.AbstractQueue#add(java.lang.Object)"
    "executable:java.util.Queue#add(java.lang.Object)"
    "executable:java.util.Queue#peek()"
    "executable:java.util.Queue#poll()"
    "executable:java.util.List#add(int,java.lang.Object)"
    "executable:java.util.List#addAll(int,java.util.Collection)"
    "executable:java.util.ArrayList#addAll(int,java.util.Collection)"
    "executable:java.util.List#indexOf(java.lang.Object)"
    "executable:java.util.List#lastIndexOf(java.lang.Object)"
    "executable:java.util.List#remove(int)"
    "executable:java.util.List#set(int,java.lang.Object)"
    "executable:java.util.List#sort(java.util.Comparator)"
    "executable:java.util.ArrayList#sort(java.util.Comparator)"
    "executable:java.util.List#subList(int,int)"
    "executable:java.util.List#removeAll(java.util.Collection)"
    "executable:java.util.List#retainAll(java.util.Collection)"
    "executable:java.util.HashMap#clear()"
    "executable:java.util.Map#isEmpty()"
    "executable:java.util.TreeMap#isEmpty()"
    "executable:java.util.Set#addAll(java.util.Collection)"
    "executable:java.util.Set#isEmpty()"
    "executable:java.util.Set#remove(java.lang.Object)"
    "executable:java.util.HashSet#remove(java.lang.Object)"
    "executable:java.util.Set#iterator()"
    "executable:java.util.Set#size()"
    "executable:java.util.SortedMap#entrySet()"
    "executable:java.util.TreeMap#entrySet()"
    "executable:java.util.SortedMap#firstKey()"
    "executable:java.util.SortedMap#lastKey()"
    "executable:java.util.TreeMap#firstKey()"
    "executable:java.util.TreeMap#lastKey()"
    "executable:java.util.SortedMap#subMap(java.lang.Object,java.lang.Object)"
    "executable:java.util.SortedMap#values()"
    "executable:java.util.TreeMap#subMap(java.lang.Object,java.lang.Object)"
    "executable:java.util.TreeMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
    "executable:java.util.SortedSet#headSet(java.lang.Object)"
    "executable:java.util.SortedSet#first()"
    "executable:java.util.SortedSet#last()"
    "executable:java.util.SortedSet#subSet(java.lang.Object,java.lang.Object)"
    "executable:java.util.TreeSet#subSet(java.lang.Object,java.lang.Object)"
    "executable:java.util.TreeSet#add(java.lang.Object)"
    "executable:java.util.StringJoiner#add(java.lang.CharSequence)"
    "executable:java.util.StringJoiner#toString()"
    "executable:java.util.Stack#push(java.lang.Object)"
    "executable:java.util.Stack#pop()"
    "executable:java.util.Stack#peek()"
    "executable:java.util.Vector#isEmpty()"
    "executable:java.util.Vector#size()"
    "executable:java.util.Vector#get(int)"
    "executable:java.util.Vector#addAll(java.util.Collection)"
    "executable:java.util.Vector#clear()"
    "executable:java.util.Vector#subList(int,int)"
    "executable:java.util.StringTokenizer#countTokens()"
    "executable:java.util.StringTokenizer#hasMoreTokens()"
    "executable:java.util.StringTokenizer#nextToken()"
    "executable:java.util.TimeZone#clone()"
    "executable:java.util.TimeZone#getTimeZone(java.lang.String)"
    "executable:java.util.TimeZone#getRawOffset()"
    "executable:java.util.TimeZone#setID(java.lang.String)"
    "executable:java.util.regex.Matcher#end()"
    "executable:java.util.regex.Matcher#find()"
    "executable:java.util.regex.Matcher#find(int)"
    "executable:java.util.regex.Matcher#group()"
    "executable:java.util.regex.Matcher#group(int)"
    "executable:java.util.regex.Matcher#group(java.lang.String)"
    "executable:java.util.regex.Matcher#groupCount()"
    "executable:java.util.regex.Matcher#lookingAt()"
    "executable:java.util.regex.Matcher#region(int,int)"
    "executable:java.util.regex.Matcher#replaceFirst(java.lang.String)"
    "executable:java.util.regex.Matcher#start(int)"
    "executable:java.util.regex.Matcher#end(int)"
    "executable:java.util.regex.Matcher#toMatchResult()"
    "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuffer,java.lang.String)"
    "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuilder,java.lang.String)"
    "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuffer)"
    "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuilder)"
    "executable:java.util.regex.Matcher#quoteReplacement(java.lang.String)"
    "executable:java.util.regex.Matcher#replaceAll(java.lang.String)"
    "executable:java.util.regex.Matcher#start()"
    "executable:java.util.regex.MatchResult#end()"
    "executable:java.util.regex.MatchResult#end(int)"
    "executable:java.util.regex.MatchResult#group()"
    "executable:java.util.regex.MatchResult#group(int)"
    "executable:java.util.regex.MatchResult#groupCount()"
    "executable:java.util.regex.MatchResult#start()"
    "executable:java.util.regex.MatchResult#start(int)"
    "executable:java.util.regex.Pattern#matches(java.lang.String,java.lang.CharSequence)"
    "executable:java.util.stream.Stream#anyMatch(java.util.function.Predicate)"
    "executable:java.util.stream.Stream#allMatch(java.util.function.Predicate)"
    "executable:java.util.stream.Stream#noneMatch(java.util.function.Predicate)"
    "executable:java.util.stream.Stream#distinct()"
    "executable:java.util.stream.Stream#count()"
    "executable:java.util.stream.Stream#reduce(java.util.function.BinaryOperator)"
    "executable:java.util.stream.Stream#findFirst()"
    "executable:java.util.stream.Stream#spliterator()"
    "executable:java.util.stream.Stream#toList()"
    "executable:java.util.stream.StreamSupport#stream(java.util.Spliterator,boolean)"
    "executable:java.util.stream.IntStream#toArray()"
    "executable:java.util.stream.IntStream#max()"
    "executable:java.util.stream.Stream#toArray(java.util.function.IntFunction)"
    "executable:java.util.stream.IntStream#forEach(java.util.function.IntConsumer)"
    "executable:java.util.zip.CRC32#getValue()"
    "executable:java.util.zip.CRC32#update(byte[],int,int)"
    "executable:javax.xml.namespace.QName#getLocalPart()"
    "executable:javax.xml.namespace.QName#getNamespaceURI()"
    "executable:javax.xml.namespace.QName#getPrefix()"
    "executable:javax.xml.parsers.DocumentBuilderFactory#newInstance()"
    "executable:javax.xml.parsers.DocumentBuilderFactory#newDocumentBuilder()"
    "executable:javax.xml.parsers.DocumentBuilderFactory#setExpandEntityReferences(boolean)"
    "executable:javax.xml.parsers.DocumentBuilderFactory#setFeature(java.lang.String,boolean)"
    "executable:javax.xml.parsers.DocumentBuilderFactory#setIgnoringComments(boolean)"
    "executable:javax.xml.parsers.DocumentBuilderFactory#setNamespaceAware(boolean)"
    "executable:javax.xml.parsers.DocumentBuilderFactory#setXIncludeAware(boolean)"
    "executable:javax.xml.parsers.DocumentBuilder#newDocument()"
    "executable:javax.xml.parsers.DocumentBuilder#parse(java.io.InputStream)"
    "executable:javax.xml.parsers.DocumentBuilder#setErrorHandler(org.xml.sax.ErrorHandler)"
    "executable:javax.xml.xpath.XPathFactory#newInstance()"
    "executable:javax.xml.xpath.XPathFactory#newXPath()"
    "executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object)"
    "executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object,javax.xml.namespace.QName)"
    "executable:javax.xml.transform.TransformerFactory#newInstance()"
    "executable:javax.xml.transform.TransformerFactory#newTransformer()"
    "executable:javax.xml.transform.Transformer#setOutputProperty(java.lang.String,java.lang.String)"
    "executable:javax.xml.transform.Transformer#transform(javax.xml.transform.Source,javax.xml.transform.Result)"
    "executable:org.w3c.dom.Attr#getValue()"
    "executable:org.w3c.dom.CharacterData#getData()"
    "executable:org.w3c.dom.Document#createElement(java.lang.String)"
    "executable:org.w3c.dom.Document#createElementNS(java.lang.String,java.lang.String)"
    "executable:org.w3c.dom.Document#createProcessingInstruction(java.lang.String,java.lang.String)"
    "executable:org.w3c.dom.Document#getDocumentElement()"
    "executable:org.w3c.dom.Document#getInputEncoding()"
    "executable:org.w3c.dom.Document#getXmlEncoding()"
    "executable:org.w3c.dom.Element#setAttribute(java.lang.String,java.lang.String)"
    "executable:org.w3c.dom.Element#setAttributeNS(java.lang.String,java.lang.String,java.lang.String)"
    "executable:org.w3c.dom.Element#getAttribute(java.lang.String)"
    "executable:org.w3c.dom.Element#getElementsByTagName(java.lang.String)"
    "executable:org.w3c.dom.Element#getTagName()"
    "executable:org.w3c.dom.Element#getAttributeNodeNS(java.lang.String,java.lang.String)"
    "executable:org.w3c.dom.NamedNodeMap#getLength()"
    "executable:org.w3c.dom.NamedNodeMap#item(int)"
    "executable:org.w3c.dom.NamedNodeMap#getNamedItem(java.lang.String)"
    "executable:org.w3c.dom.Node#getAttributes()"
    "executable:org.w3c.dom.Node#getChildNodes()"
    "executable:org.w3c.dom.Node#getFirstChild()"
    "executable:org.w3c.dom.Node#getLocalName()"
    "executable:org.w3c.dom.Node#getNamespaceURI()"
    "executable:org.w3c.dom.Node#getNextSibling()"
    "executable:org.w3c.dom.Node#getNodeName()"
    "executable:org.w3c.dom.Node#getNodeValue()"
    "executable:org.w3c.dom.Node#getOwnerDocument()"
    "executable:org.w3c.dom.Node#getPrefix()"
    "executable:org.w3c.dom.Node#getTextContent()"
    "executable:org.w3c.dom.Node#appendChild(org.w3c.dom.Node)"
    "executable:org.w3c.dom.Node#removeChild(org.w3c.dom.Node)"
    "executable:org.w3c.dom.Node#setTextContent(java.lang.String)"
    "executable:org.w3c.dom.NodeList#getLength()"
    "executable:org.w3c.dom.NodeList#item(int)"
    "executable:org.w3c.dom.ProcessingInstruction#getData()"})

(def ^:private extended-neutral-field-keys
  #{"field:java.io.File#separator"
    "field:java.io.File#separatorChar"
    "field:java.io.ByteArrayOutputStream#buf"
    "field:java.io.FilterInputStream#in"
    "field:java.io.FilterOutputStream#out"
    "field:java.lang.Boolean#FALSE"
    "field:java.lang.Boolean#TRUE"
    "field:java.lang.Character#MAX_CODE_POINT"
    "field:java.lang.Character#MIN_CODE_POINT"
    "field:java.lang.Character#MAX_VALUE"
    "field:java.lang.Character#MIN_VALUE"
    "field:java.lang.Character#MIN_SURROGATE"
    "field:java.lang.Character#UNASSIGNED"
    "field:java.lang.Character#UPPERCASE_LETTER"
    "field:java.lang.Character#LOWERCASE_LETTER"
    "field:java.lang.Character#TITLECASE_LETTER"
    "field:java.lang.Character#NON_SPACING_MARK"
    "field:java.lang.Character#MODIFIER_LETTER"
    "field:java.lang.Character#OTHER_LETTER"
    "field:java.lang.Character#DECIMAL_DIGIT_NUMBER"
    "field:java.lang.Character#LETTER_NUMBER"
    "field:java.lang.Character#SPACE_SEPARATOR"
    "field:java.lang.Character#LINE_SEPARATOR"
    "field:java.lang.Character#PARAGRAPH_SEPARATOR"
    "field:java.lang.Character#DASH_PUNCTUATION"
    "field:java.lang.Character#START_PUNCTUATION"
    "field:java.lang.Character#END_PUNCTUATION"
    "field:java.lang.Character#CONNECTOR_PUNCTUATION"
    "field:java.lang.Character#OTHER_PUNCTUATION"
    "field:java.lang.Character#CURRENCY_SYMBOL"
    "field:java.lang.Character#MODIFIER_SYMBOL"
    "field:java.lang.Character#INITIAL_QUOTE_PUNCTUATION"
    "field:java.lang.Character#FINAL_QUOTE_PUNCTUATION"
    "field:java.lang.Float#MAX_VALUE"
    "field:java.lang.Float#MIN_NORMAL"
    "field:java.lang.Float#MIN_VALUE"
    "field:java.lang.Float#NEGATIVE_INFINITY"
    "field:java.lang.Float#POSITIVE_INFINITY"
    "field:java.lang.Double#NaN"
    "field:java.lang.Double#NEGATIVE_INFINITY"
    "field:java.lang.Double#POSITIVE_INFINITY"
    "field:java.lang.Double#MAX_VALUE"
    "field:java.lang.Double#MIN_VALUE"
    "field:java.lang.Double#MAX_EXPONENT"
    "field:java.lang.Double#MIN_EXPONENT"
    "field:java.lang.Integer#MIN_VALUE"
    "field:java.lang.Integer#SIZE"
    "field:java.lang.Long#MIN_VALUE"
    "field:java.lang.Long#MAX_VALUE"
    "field:java.lang.Long#SIZE"
    "field:java.lang.Byte#SIZE"
    "field:java.lang.Short#MAX_VALUE"
    "field:java.lang.Short#MIN_VALUE"
    "field:java.lang.Short#SIZE"
    "field:java.lang.Math#PI"
    "field:java.lang.Math#E"
    "field:java.lang.StrictMath#PI"
    "field:java.lang.StrictMath#E"
    "field:java.math.RoundingMode#UP"
    "field:java.math.RoundingMode#DOWN"
    "field:java.math.RoundingMode#CEILING"
    "field:java.math.RoundingMode#FLOOR"
    "field:java.math.RoundingMode#HALF_UP"
    "field:java.math.RoundingMode#HALF_DOWN"
    "field:java.math.RoundingMode#HALF_EVEN"
    "field:java.math.RoundingMode#UNNECESSARY"
    "field:java.util.zip.Deflater#DEFAULT_COMPRESSION"
    "field:java.util.zip.Deflater#BEST_COMPRESSION"
    "field:java.text.Bidi#DIRECTION_DEFAULT_LEFT_TO_RIGHT"
    "field:java.text.Bidi#DIRECTION_DEFAULT_RIGHT_TO_LEFT"
    "field:java.text.Bidi#DIRECTION_LEFT_TO_RIGHT"
    "field:java.text.Bidi#DIRECTION_RIGHT_TO_LEFT"
    "field:java.text.Normalizer$Form#NFC"
    "field:java.text.Normalizer$Form#NFD"
    "field:java.text.Normalizer$Form#NFKC"
    "field:java.text.Normalizer$Form#NFKD"
    "field:java.time.Month#FEBRUARY"
    "field:java.nio.charset.StandardCharsets#UTF_16"
    "field:java.nio.charset.StandardCharsets#UTF_16BE"
    "field:java.nio.charset.StandardCharsets#UTF_16LE"
    "field:java.nio.file.LinkOption#NOFOLLOW_LINKS"
    "field:java.nio.file.attribute.PosixFilePermission#OWNER_READ"
    "field:java.nio.file.attribute.PosixFilePermission#OWNER_WRITE"
    "field:java.nio.file.attribute.PosixFilePermission#OWNER_EXECUTE"
    "field:java.nio.file.attribute.PosixFilePermission#GROUP_READ"
    "field:java.nio.file.attribute.PosixFilePermission#GROUP_WRITE"
    "field:java.nio.file.attribute.PosixFilePermission#GROUP_EXECUTE"
    "field:java.nio.file.attribute.PosixFilePermission#OTHERS_READ"
    "field:java.nio.file.attribute.PosixFilePermission#OTHERS_WRITE"
    "field:java.nio.file.attribute.PosixFilePermission#OTHERS_EXECUTE"
    "field:java.nio.file.StandardCopyOption#REPLACE_EXISTING"
    "field:java.nio.file.StandardCopyOption#COPY_ATTRIBUTES"
    "field:java.nio.file.StandardCopyOption#ATOMIC_MOVE"
    "field:java.net.http.HttpClient$Version#HTTP_2"
    "field:java.nio.charset.CodingErrorAction#REPORT"
    "field:javax.crypto.Cipher#DECRYPT_MODE"
    "field:javax.crypto.Cipher#ENCRYPT_MODE"
    "field:java.util.Calendar#MILLISECOND"
    "field:java.util.Calendar#YEAR"
    "field:java.util.Calendar#MONTH"
    "field:java.util.Calendar#DAY_OF_MONTH"
    "field:java.util.Calendar#HOUR_OF_DAY"
    "field:java.util.Calendar#MINUTE"
    "field:java.util.Calendar#SECOND"
    "field:java.util.Calendar#ZONE_OFFSET"
    "field:java.util.Calendar#DST_OFFSET"
    "field:java.util.Locale#ENGLISH"
    "field:java.util.Locale#US"
    "field:java.util.regex.Pattern#UNIX_LINES"
    "field:java.util.regex.Pattern#CASE_INSENSITIVE"
    "field:java.util.regex.Pattern#COMMENTS"
    "field:java.util.regex.Pattern#MULTILINE"
    "field:java.util.regex.Pattern#LITERAL"
    "field:java.util.regex.Pattern#DOTALL"
    "field:java.util.regex.Pattern#UNICODE_CASE"
    "field:java.util.regex.Pattern#CANON_EQ"
    "field:java.util.regex.Pattern#UNICODE_CHARACTER_CLASS"
    "field:java.time.format.DateTimeFormatter#ISO_LOCAL_DATE_TIME"
    "field:javax.xml.XMLConstants#XMLNS_ATTRIBUTE"
    "field:javax.xml.XMLConstants#XMLNS_ATTRIBUTE_NS_URI"
    "field:javax.xml.XMLConstants#XML_NS_PREFIX"
    "field:javax.xml.XMLConstants#XML_NS_URI"
    "field:javax.xml.transform.OutputKeys#ENCODING"
    "field:javax.xml.transform.OutputKeys#INDENT"
    "field:javax.xml.transform.OutputKeys#OMIT_XML_DECLARATION"
    "field:org.w3c.dom.Node#COMMENT_NODE"
    "field:org.w3c.dom.Node#TEXT_NODE"
    "field:javax.xml.xpath.XPathConstants#NODE"
    "field:javax.xml.xpath.XPathConstants#NODESET"})

(defn- resolved-name [ctx occurrence reference]
  (if-let [destination-name (when-let [resolve-name (:destination-resolved-name ctx)] (resolve-name ctx occurrence reference))] destination-name (cond (and (= :intrinsic (:origin occurrence)) (= :class-literal (:resolution occurrence))) "class" (= :project (:origin occurrence)) (if-let [destination-name (:destination-project-resolved-name ctx)] (destination-name ctx occurrence reference) (let [declaration (:declaration occurrence)] (cond (instance? CtField declaration) (destination-field-name ctx declaration) (instance? CtMethod declaration) (method-name ctx (.getDeclaringType declaration) declaration) :else (identifier (.getSimpleName reference))))) (contains? (:destination-invocation-adaptations ctx) (:key occurrence)) (identifier (.getSimpleName reference)) (contains? (:destination-field-adaptations ctx) (:key occurrence)) (identifier (.getSimpleName reference)) (and (= :dependency (:origin occurrence)) (some->> reference .getDeclaringType (translated-external-type-base ctx))) (csharp-public-name (.getSimpleName reference)) (and (= :intrinsic (:origin occurrence)) (= :enum-synthetic-method (:resolution occurrence))) (identifier (.getSimpleName reference)) (and (= :inherited-runtime-member (:resolution occurrence)) (or (str/ends-with? (:key occurrence) "#equals(java.lang.Object)") (str/ends-with? (:key occurrence) "#hashCode()") (str/ends-with? (:key occurrence) "#toString()"))) (identifier (.getSimpleName reference)) (or (contains? extended-neutral-executable-keys (:key occurrence)) (contains? extended-neutral-field-keys (:key occurrence))) (identifier (.getSimpleName reference)) (contains? #{"executable:java.time.format.DateTimeFormatterBuilder#toFormatter()" "executable:java.lang.StringBuilder#append(char[])" "executable:java.security.DigestInputStream#getMessageDigest()" "executable:java.util.ResourceBundle#getString(java.lang.String)" "executable:java.lang.Integer#toString(int,int)" "executable:java.nio.file.FileSystem#getPath(java.lang.String,java.lang.String[])" "executable:java.net.URI#getAuthority()" "executable:java.lang.Thread#interrupt()" "executable:java.util.List#toArray(java.lang.Object[])" "executable:java.util.LinkedHashMap#remove(java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.lang.StringBuilder#append(java.lang.CharSequence)" "executable:java.lang.Long#toString(long,int)" "executable:java.io.OutputStream#write(byte[],int,int)" "executable:java.net.Socket#isClosed()" "executable:java.lang.Object#getClass()" "executable:java.lang.Integer#parseInt(java.lang.String)" "executable:java.nio.channels.FileChannel#position(long)" "executable:java.util.Optional#orElseGet(java.util.function.Supplier)" "executable:java.util.zip.ZipEntry#setTimeLocal(java.time.LocalDateTime)" "executable:java.net.InetSocketAddress#getAddress()" "executable:java.io.RandomAccessFile#seek(long)" "executable:java.nio.ByteBuffer#position(int)" "executable:java.net.URI#getRawSchemeSpecificPart()" "executable:java.lang.Long#toString(long)" "executable:java.util.ArrayList#toArray()" "executable:java.lang.invoke.MethodType#methodType(java.lang.Class,java.lang.Class)" "executable:java.util.concurrent.Future#get()" "executable:java.net.URI#isAbsolute()" "executable:java.lang.String#getBytes(java.nio.charset.Charset)" "executable:java.net.URI#create(java.lang.String)" "executable:java.lang.String#equals(java.lang.Object)" "executable:java.util.stream.Stream#skip(long)" "executable:java.net.URISyntaxException#getIndex()" "executable:javax.net.ssl.KeyManagerFactory#init(java.security.KeyStore,char[])" "executable:java.time.format.DateTimeFormatter#format(java.time.temporal.TemporalAccessor)" "executable:java.time.format.DateTimeFormatterBuilder#parseStrict()" "executable:java.util.zip.GZIPInputStream#read(byte[],int,int)" "executable:java.security.KeyStore#getInstance(java.lang.String)" "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)" "executable:java.net.ServerSocket#isClosed()" "executable:java.io.InputStream#readAllBytes()" "executable:java.nio.file.FileSystem#close()" "executable:java.nio.ByteBuffer#get(byte[],int,int)" "executable:java.lang.String#lines()" "executable:java.net.http.HttpRequest$Builder#timeout(java.time.Duration)" "executable:java.util.Map#forEach(java.util.function.BiConsumer)" "executable:java.nio.file.FileSystem#provider()" "executable:java.util.Collections#synchronizedList(java.util.List)" "executable:java.lang.String#split(java.lang.String)" "executable:java.util.Arrays#hashCode(float[])" "executable:javax.net.ssl.KeyManagerFactory#getDefaultAlgorithm()" "executable:java.util.List#toArray()" "executable:java.util.List#equals(java.lang.Object)" "executable:java.nio.file.Files#deleteIfExists(java.nio.file.Path)" "executable:java.lang.System#console()" "executable:java.util.Objects#requireNonNull(java.lang.Object)" "executable:java.lang.StringBuilder#append(long)" "executable:java.lang.Math#toIntExact(long)" "executable:java.lang.StringBuilder#append(boolean)" "executable:java.net.URI#getRawFragment()" "executable:java.time.format.DateTimeFormatterBuilder#append(java.time.format.DateTimeFormatter)" "executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)" "executable:java.util.Set#equals(java.lang.Object)" "executable:java.util.ArrayList#toArray(java.lang.Object[])" "executable:java.util.concurrent.atomic.AtomicInteger#incrementAndGet()" "executable:java.lang.Iterable#iterator()" "executable:java.nio.file.Path#resolve(java.lang.String)" "executable:java.lang.Character#toString(int)" "executable:java.time.Duration#getSeconds()" "executable:java.util.stream.IntStream#iterator()" "executable:java.util.Objects#hash(java.lang.Object[])" "executable:java.security.cert.CertificateFactory#generateCertificates(java.io.InputStream)" "executable:java.util.List#of(java.lang.Object,java.lang.Object)" "executable:java.io.ByteArrayOutputStream#toByteArray()" "executable:java.lang.String#join(java.lang.CharSequence,java.lang.Iterable)" "executable:java.lang.Class#getTypeName()" "executable:java.util.concurrent.atomic.AtomicReference#get()" "executable:java.lang.Thread#sleep(long)" "executable:java.net.http.HttpRequest#method()" "executable:java.net.http.HttpRequest#bodyPublisher()" "executable:java.util.concurrent.ExecutorService#awaitTermination(long,java.util.concurrent.TimeUnit)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.nio.channels.FileChannel#map(java.nio.channels.FileChannel$MapMode,long,long)" "executable:java.time.format.DateTimeFormatterBuilder#parseCaseInsensitive()" "executable:java.lang.Integer#toString()" "executable:java.nio.file.Path#toRealPath(java.nio.file.LinkOption[])" "executable:java.nio.channels.FileChannel#open(java.nio.file.Path,java.nio.file.OpenOption[])" "executable:java.util.regex.Pattern#split(java.lang.CharSequence,int)" "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)" "executable:java.lang.invoke.MethodHandle#invokeExact(java.lang.Object[])" "executable:java.lang.ThreadLocal#withInitial(java.util.function.Supplier)" "executable:java.io.InputStream#close()" "executable:java.util.List#of(java.lang.Object[])" "executable:java.net.ServerSocket#close()" "executable:java.lang.invoke.MethodHandles$Lookup#unreflect(java.lang.reflect.Method)" "executable:java.util.HashSet#add(java.lang.Object)" "executable:java.io.File#length()" "executable:java.util.concurrent.atomic.AtomicBoolean#getAndSet(boolean)" "executable:java.util.TreeMap#size()" "executable:java.nio.file.FileSystem#supportedFileAttributeViews()" "executable:java.nio.file.Files#isRegularFile(java.nio.file.Path,java.nio.file.LinkOption[])" "executable:java.nio.file.Path#relativize(java.nio.file.Path)" "executable:java.util.AbstractSequentialList#iterator()" "executable:java.net.http.HttpRequest#newBuilder(java.net.URI)" "executable:java.io.InputStream#read(byte[],int,int)" "executable:java.time.format.DateTimeFormatterBuilder#appendOffset(java.lang.String,java.lang.String)" "executable:java.lang.String#toLowerCase()" "executable:javax.net.ssl.KeyManagerFactory#getInstance(java.lang.String)" "executable:java.util.TreeMap#clear()" "executable:java.lang.StringBuilder#toString()" "executable:java.util.regex.Pattern#quote(java.lang.String)" "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)" "executable:java.lang.Iterable#forEach(java.util.function.Consumer)" "executable:java.nio.file.Files#copy(java.nio.file.Path,java.io.OutputStream)" "executable:java.net.Socket#getOutputStream()" "executable:java.lang.StringBuilder#append(char)" "executable:java.io.InputStream#read(byte[])" "executable:java.nio.ByteBuffer#wrap(byte[])" "executable:java.time.Duration#ofSeconds(long)" "executable:java.nio.file.FileSystem#getUserPrincipalLookupService()" "executable:java.net.URI#resolve(java.lang.String)" "executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)" "executable:java.net.URI#isOpaque()" "executable:java.lang.String#formatted(java.lang.Object[])" "executable:java.lang.Runnable#run()" "executable:java.io.File#getAbsolutePath()" "executable:java.util.Collection#toArray(java.lang.Object[])" "executable:java.net.URI#resolve(java.net.URI)" "executable:java.nio.ByteBuffer#put(byte[])" "executable:java.util.zip.ZipOutputStream#closeEntry()" "executable:java.lang.StringBuilder#append(int)" "executable:java.lang.invoke.MethodType#methodType(java.lang.Class)" "executable:java.util.function.BiFunction#apply(java.lang.Object,java.lang.Object)" "executable:java.util.concurrent.ExecutorService#submit(java.lang.Runnable)" "executable:java.nio.file.Files#isDirectory(java.nio.file.Path,java.nio.file.LinkOption[])" "executable:java.util.List#contains(java.lang.Object)" "executable:java.util.TreeMap#keySet()" "executable:java.lang.invoke.MethodHandle#type()" "executable:java.net.URI#normalize()" "executable:java.util.Iterator#hasNext()" "executable:java.nio.ByteBuffer#limit(int)" "executable:java.util.Deque#getFirst()" "executable:java.lang.Long#parseLong(java.lang.String,int)" "executable:java.util.Iterator#remove()" "executable:java.io.FilterOutputStream#close()" "executable:java.time.LocalDateTime#atZone(java.time.ZoneId)" "executable:java.util.concurrent.CompletableFuture#complete(java.lang.Object)" "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)" "executable:java.util.BitSet#clear()" "executable:java.lang.Throwable#getCause()" "executable:java.util.ArrayList#size()" "executable:java.util.Arrays#hashCode(byte[])" "executable:java.util.zip.ZipOutputStream#putNextEntry(java.util.zip.ZipEntry)" "executable:java.util.Collections#synchronizedMap(java.util.Map)" "executable:java.util.Map#putAll(java.util.Map)" "executable:java.lang.ThreadLocal#get()" "executable:java.nio.file.FileSystem#getFileStores()" "executable:java.lang.StringBuilder#append(java.lang.String)" "executable:java.util.ArrayList#addAll(java.util.Collection)" "executable:java.lang.String#substring(int,int)" "executable:java.lang.StringBuilder#append(float)" "executable:java.util.TreeMap#values()" "executable:java.util.EnumSet#copyOf(java.util.Collection)" "executable:java.lang.reflect.Field#setAccessible(boolean)" "executable:java.lang.Throwable#toString()" "executable:java.net.http.HttpRequest#expectContinue()" "executable:java.lang.String#repeat(int)" "executable:java.nio.file.Files#list(java.nio.file.Path)" "executable:java.util.Collection#removeIf(java.util.function.Predicate)" "executable:java.nio.file.Files#getFileAttributeView(java.nio.file.Path,java.lang.Class,java.nio.file.LinkOption[])" "executable:java.io.File#setExecutable(boolean,boolean)" "executable:java.util.concurrent.ExecutorService#shutdown()" "executable:java.nio.file.attribute.AclEntry$Builder#setPrincipal(java.nio.file.attribute.UserPrincipal)" "executable:java.nio.file.Files#createTempFile(java.lang.String,java.lang.String,java.nio.file.attribute.FileAttribute[])" "executable:javax.net.ssl.SSLContext#getDefault()" "executable:java.util.Locale#getDefault()" "executable:java.lang.invoke.MethodHandle#invoke(java.lang.Object[])" "executable:java.lang.Math#min(long,long)" "executable:java.nio.file.attribute.AclEntry$Builder#setPermissions(java.util.Set)" "executable:java.lang.String#getBytes()" "executable:java.security.MessageDigest#update(byte[])" "executable:java.util.Optional#ifPresentOrElse(java.util.function.Consumer,java.lang.Runnable)" "executable:java.lang.Character#isLetterOrDigit(int)" "executable:java.util.List#get(int)" "executable:java.util.Arrays#equals(byte[],byte[])" "executable:javax.net.ssl.SSLContext#init(javax.net.ssl.KeyManager[],javax.net.ssl.TrustManager[],java.security.SecureRandom)" "executable:java.util.regex.PatternSyntaxException#getMessage()" "executable:java.util.HashSet#contains(java.lang.Object)" "executable:java.net.ServerSocket#accept()" "executable:java.util.TreeMap#remove(java.lang.Object)" "executable:java.lang.Long#parseLong(java.lang.String)" "executable:java.util.List#copyOf(java.util.Collection)" "executable:java.net.http.HttpRequest$Builder#header(java.lang.String,java.lang.String)" "executable:java.util.OptionalLong#ifPresent(java.util.function.LongConsumer)" "executable:java.util.Arrays#equals(char[],char[])" "executable:java.net.http.HttpRequest$Builder#DELETE()" "executable:java.lang.Class#getDeclaredField(java.lang.String)" "executable:java.util.Optional#orElseThrow()" "executable:java.util.regex.Pattern#compile(java.lang.String)" "executable:javax.net.ssl.KeyManagerFactory#getKeyManagers()" "executable:java.security.MessageDigest#isEqual(byte[],byte[])" "executable:java.lang.AbstractStringBuilder#length()" "executable:java.util.Collections#nCopies(int,java.lang.Object)" "executable:java.nio.file.attribute.AclEntry$Builder#setType(java.nio.file.attribute.AclEntryType)" "executable:java.net.URL#openConnection()" "executable:java.util.HashMap#remove(java.lang.Object)" "executable:java.util.List#of(java.lang.Object)" "executable:java.net.URLEncoder#encode(java.lang.String,java.nio.charset.Charset)" "executable:java.util.concurrent.atomic.AtomicBoolean#compareAndSet(boolean,boolean)" "executable:java.lang.invoke.MethodHandles#guardWithTest(java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.stream.LongStream#iterator()" "executable:java.util.Map#containsKey(java.lang.Object)" "executable:java.nio.file.FileSystem#getSeparator()" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.net.InetAddress#getByName(java.lang.String)" "executable:java.net.URI#getScheme()" "executable:java.lang.invoke.MethodType#returnType()" "executable:java.net.http.HttpRequest#headers()" "executable:java.nio.ByteBuffer#putLong(long)" "executable:java.lang.String#trim()" "executable:java.nio.file.FileSystem#isOpen()" "executable:java.util.concurrent.ExecutorService#shutdownNow()" "executable:java.util.stream.Stream#forEachOrdered(java.util.function.Consumer)" "executable:java.util.function.Function#andThen(java.util.function.Function)" "executable:java.util.HashMap#putAll(java.util.Map)" "executable:java.net.http.HttpRequest$Builder#version(java.net.http.HttpClient$Version)" "executable:java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)" "executable:java.net.http.HttpResponse#request()" "executable:java.nio.file.FileSystem#isReadOnly()" "executable:java.nio.file.Path#getName(int)" "executable:java.util.OptionalLong#of(long)" "executable:java.util.Collection#toArray()" "executable:java.lang.StringBuilder#insert(int,char)" "executable:java.nio.file.Files#createDirectories(java.nio.file.Path,java.nio.file.attribute.FileAttribute[])" "executable:java.lang.String#format(java.lang.String,java.lang.Object[])" "executable:java.net.http.HttpResponse#statusCode()" "executable:java.lang.Math#min(int,int)" "executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)" "executable:java.net.Socket#getRemoteSocketAddress()" "executable:java.nio.file.attribute.FileOwnerAttributeView#getOwner()" "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)" "executable:java.net.http.HttpRequest$Builder#method(java.lang.String,java.net.http.HttpRequest$BodyPublisher)" "executable:java.lang.String#length()" "executable:java.nio.ByteBuffer#allocate(int)" "executable:java.net.URL#openStream()" "executable:java.util.Arrays#equals(int[],int[])" "executable:java.util.stream.IntStream#skip(long)" "executable:java.lang.Throwable#initCause(java.lang.Throwable)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.util.stream.Stream#forEach(java.util.function.Consumer)" "executable:java.util.Map$Entry#getValue()" "executable:java.util.HashSet#clear()" "executable:java.util.List#hashCode()" "executable:java.util.Map#size()" "executable:java.util.Objects#requireNonNullElseGet(java.lang.Object,java.util.function.Supplier)" "executable:java.nio.file.Files#newInputStream(java.nio.file.Path,java.nio.file.OpenOption[])" "executable:java.util.Deque#descendingIterator()" "executable:java.util.ServiceLoader#spliterator()" "executable:java.lang.StringBuilder#length()" "executable:java.util.function.LongFunction#apply(long)" "executable:java.nio.file.Files#readString(java.nio.file.Path,java.nio.charset.Charset)" "executable:java.util.Map#keySet()" "executable:java.nio.file.Path#toFile()" "executable:java.nio.file.Files#createTempFile(java.nio.file.Path,java.lang.String,java.lang.String,java.nio.file.attribute.FileAttribute[])" "executable:java.io.RandomAccessFile#close()" "executable:java.util.Optional#map(java.util.function.Function)" "executable:java.nio.file.FileSystems#getFileSystem(java.net.URI)" "executable:java.util.regex.Pattern#toString()" "executable:java.util.Arrays#equals(float[],float[])" "executable:java.io.InputStream#readNBytes(int)" "executable:java.util.zip.ZipEntry#getName()" "executable:java.io.PushbackInputStream#close()" "executable:java.util.Map$Entry#getKey()" "executable:java.util.regex.Pattern#flags()" "executable:java.lang.String#lastIndexOf(java.lang.String)" "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)" "executable:javax.net.SocketFactory#createSocket()" "executable:java.lang.String#matches(java.lang.String)" "executable:java.nio.channels.FileChannel#open(java.nio.file.Path,java.util.Set,java.nio.file.attribute.FileAttribute[])" "executable:java.util.Map#hashCode()" "executable:java.net.URLConnection#setUseCaches(boolean)" "executable:java.util.List#remove(java.lang.Object)" "executable:java.nio.file.Files#createTempDirectory(java.lang.String,java.nio.file.attribute.FileAttribute[])" "executable:java.nio.ByteBuffer#array()" "executable:java.util.function.Supplier#get()" "executable:java.util.concurrent.CompletableFuture#completeExceptionally(java.lang.Throwable)" "executable:java.util.Collections#singletonList(java.lang.Object)" "executable:java.lang.invoke.MethodHandles#constant(java.lang.Class,java.lang.Object)" "executable:java.nio.ByteBuffer#getInt()" "executable:java.net.http.HttpRequest$Builder#build()" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.Map$Entry#comparingByValue()" "executable:java.net.Socket#getInputStream()" "executable:java.net.http.HttpRequest#uri()" "executable:java.lang.Math#max(long,long)" "executable:java.util.Collection#stream()" "executable:java.util.Map#entrySet()" "executable:java.lang.String#indexOf(int,int)" "executable:java.io.InputStream#read()" "executable:java.util.zip.ZipEntry#isDirectory()" "executable:java.util.BitSet#nextSetBit(int)" "executable:java.nio.file.FileSystem#getPathMatcher(java.lang.String)" "executable:java.io.PushbackInputStream#unread(byte[],int,int)" "executable:java.lang.Class#forName(java.lang.String)" "executable:java.net.URI#getRawQuery()" "executable:java.net.URI#hashCode()" "executable:java.net.URI#compareTo(java.net.URI)" "executable:java.time.ZonedDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)" "executable:java.util.Collections#unmodifiableList(java.util.List)" "executable:java.io.File#exists()" "executable:java.util.PrimitiveIterator$OfLong#hasNext()" "executable:java.time.LocalDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)" "executable:java.nio.file.Path#startsWith(java.nio.file.Path)" "executable:java.util.Objects#hashCode(java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.lang.String#hashCode()" "executable:java.lang.Enum#name()" "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)" "executable:java.net.Socket#close()" "executable:java.lang.System#currentTimeMillis()" "executable:java.util.zip.ZipInputStream#readAllBytes()" "executable:java.util.stream.IntStream#allMatch(java.util.function.IntPredicate)" "executable:java.net.URI#getPath()" "executable:java.nio.file.Path#isAbsolute()" "executable:java.util.Optional#get()" "executable:java.lang.String#isBlank()" "executable:java.util.EnumSet#copyOf(java.util.EnumSet)" "executable:java.lang.String#toUpperCase()" "executable:java.util.BitSet#get(int)" "executable:java.net.http.HttpRequest#version()" "executable:java.util.Arrays#asList(java.lang.Object[])" "executable:java.nio.ByteBuffer#get(int)" "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)" "executable:java.lang.invoke.MethodHandles$Lookup#findVirtual(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)" "executable:java.nio.channels.FileChannel#read(java.nio.ByteBuffer)" "executable:java.net.URL#toURI()" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.lang.Throwable#getMessage()" "executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)" "executable:java.time.ZoneId#of(java.lang.String)" "executable:java.util.Optional#ifPresent(java.util.function.Consumer)" "executable:java.util.regex.Pattern#compile(java.lang.String,int)" "executable:java.util.Arrays#equals(double[],double[])" "executable:java.net.URLConnection#connect()" "executable:java.io.PrintStream#println(java.lang.String)" "executable:java.security.MessageDigest#getInstance(java.lang.String)" "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)" "executable:java.util.EnumSet#noneOf(java.lang.Class)" "executable:java.net.URLDecoder#decode(java.lang.String,java.lang.String)" "executable:java.util.function.Consumer#accept(java.lang.Object)" "executable:java.lang.Math#min(double,double)" "executable:java.lang.ClassLoader#getResource(java.lang.String)" "executable:java.nio.ByteBuffer#get()" "executable:java.security.DigestOutputStream#getMessageDigest()" "executable:java.net.URI#getRawUserInfo()" "executable:java.nio.file.Path#toAbsolutePath()" "executable:java.util.Comparator#reverseOrder()" "executable:java.util.Set#contains(java.lang.Object)" "executable:java.util.Optional#equals(java.lang.Object)" "executable:java.lang.Runtime#addShutdownHook(java.lang.Thread)" "executable:java.lang.invoke.MethodHandles$Lookup#findStatic(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)" "executable:java.util.Map#put(java.lang.Object,java.lang.Object)" "executable:java.util.LinkedHashMap#keySet()" "executable:java.util.Optional#of(java.lang.Object)" "executable:java.time.LocalDateTime#of(int,java.time.Month,int,int,int)" "executable:java.lang.invoke.MethodHandles#filterReturnValue(java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle)" "executable:java.text.Format#format(java.lang.Object)" "executable:java.io.OutputStream#close()" "executable:java.nio.file.FileSystems#getDefault()" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.security.KeyStore#load(java.io.InputStream,char[])" "executable:javax.net.ssl.SSLSocketFactory#getDefault()" "executable:java.io.ByteArrayOutputStream#writeTo(java.io.OutputStream)" "executable:java.nio.file.attribute.PosixFilePermissions#asFileAttribute(java.util.Set)" "executable:java.lang.String#startsWith(java.lang.String)" "executable:java.security.KeyStore#load(java.security.KeyStore$LoadStoreParameter)" "executable:java.nio.file.attribute.AclEntry$Builder#build()" "executable:java.util.Set#toArray(java.lang.Object[])" "executable:java.util.stream.Stream#map(java.util.function.Function)" "executable:java.lang.Long#parseLong(java.lang.CharSequence,int,int,int)" "executable:java.nio.file.Path#getParent()" "executable:java.net.URI#toURL()" "executable:java.security.MessageDigest#digest()" "executable:java.nio.ByteBuffer#clear()" "executable:java.util.concurrent.atomic.AtomicReference#getAndSet(java.lang.Object)" "executable:java.io.PipedOutputStream#write(byte[],int,int)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum)" "executable:java.lang.reflect.Field#get(java.lang.Object)" "executable:java.util.Collection#contains(java.lang.Object)" "executable:java.net.URI#equals(java.lang.Object)" "executable:java.util.Map#containsValue(java.lang.Object)" "executable:java.util.OptionalLong#empty()" "executable:java.lang.Thread#start()" "executable:java.util.OptionalInt#of(int)" "executable:java.lang.Integer#parseInt(java.lang.String,int)" "executable:java.io.InputStream#available()" "executable:java.util.HashMap#size()" "executable:java.nio.channels.FileChannel#size()" "executable:java.lang.AbstractStringBuilder#substring(int,int)" "executable:java.util.stream.Collectors#joining(java.lang.CharSequence)" "executable:java.net.URI#toString()" "executable:java.util.stream.Stream#sorted()" "executable:java.nio.file.Path#endsWith(java.nio.file.Path)" "executable:java.net.URI#getFragment()" "executable:java.lang.Throwable#getStackTrace()" "executable:java.nio.file.Files#setPosixFilePermissions(java.nio.file.Path,java.util.Set)" "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)" "executable:java.util.Collections#emptyMap()" "executable:java.security.MessageDigest#update(byte)" "executable:java.net.http.HttpRequest#newBuilder()" "executable:java.io.File#toPath()" "executable:java.net.InetAddress#getAddress()" "executable:java.util.List#iterator()" "executable:java.lang.invoke.MethodHandle#asType(java.lang.invoke.MethodType)" "executable:java.util.Map#get(java.lang.Object)" "executable:java.util.concurrent.atomic.AtomicBoolean#get()" "executable:java.util.List#isEmpty()" "executable:java.time.Duration#ofSeconds(long,long)" "executable:java.io.ByteArrayOutputStream#write(int)" "executable:java.time.Instant#isBefore(java.time.Instant)" "executable:java.io.OutputStream#write(byte[])" "executable:java.nio.file.FileSystem#newWatchService()" "executable:java.io.File#setWritable(boolean,boolean)" "executable:java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)" "executable:javax.net.ssl.TrustManagerFactory#getInstance(java.lang.String)" "executable:java.net.http.HttpResponse#body()" "executable:java.lang.System#nanoTime()" "executable:java.util.stream.Stream#mapToLong(java.util.function.ToLongFunction)" "executable:java.lang.Class#getName()" "executable:java.util.Map#entry(java.lang.Object,java.lang.Object)" "executable:java.lang.invoke.MethodHandles#dropArguments(java.lang.invoke.MethodHandle,int,java.lang.Class[])" "executable:java.time.Instant#now()" "executable:java.lang.invoke.MethodHandle#bindTo(java.lang.Object)" "executable:java.nio.ByteBuffer#put(byte[],int,int)" "executable:java.lang.invoke.VarHandle#storeStoreFence()" "executable:java.io.OutputStream#flush()" "executable:java.net.URI#getRawAuthority()" "executable:java.lang.ThreadLocal#set(java.lang.Object)" "executable:java.lang.String#regionMatches(boolean,int,java.lang.String,int,int)" "executable:java.lang.Math#max(int,int)" "executable:java.io.File#delete()" "executable:java.net.URI#getUserInfo()" "executable:java.util.concurrent.Future#get(long,java.util.concurrent.TimeUnit)" "executable:java.lang.String#lastIndexOf(int)" "executable:java.nio.file.Files#readString(java.nio.file.Path)" "executable:java.nio.channels.spi.AbstractInterruptibleChannel#close()" "executable:java.util.TreeMap#put(java.lang.Object,java.lang.Object)" "executable:java.nio.ByteBuffer#isDirect()" "executable:java.lang.String#lastIndexOf(int,int)" "executable:java.util.PrimitiveIterator$OfInt#nextInt()" "executable:java.nio.file.Path#toUri()" "executable:java.time.ZonedDateTime#now(java.time.ZoneId)" "executable:java.lang.CharSequence#isEmpty()" "executable:java.lang.Object#toString()" "executable:java.util.stream.Stream#sorted(java.util.Comparator)" "executable:java.util.OptionalInt#getAsInt()" "executable:java.io.RandomAccessFile#setLength(long)" "executable:java.io.File#setReadable(boolean,boolean)" "executable:java.io.RandomAccessFile#readFully(byte[])" "executable:java.net.http.HttpResponse#version()" "executable:java.net.URISyntaxException#getInput()" "executable:java.lang.String#getBytes(java.lang.String)" "executable:java.io.RandomAccessFile#length()" "executable:java.nio.file.attribute.AclEntry#newBuilder()" "executable:java.lang.String#regionMatches(int,java.lang.String,int,int)" "executable:java.security.MessageDigest#digest(byte[])" "executable:java.nio.ByteBuffer#put(byte)" "executable:java.nio.file.Path#of(java.net.URI)" "executable:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])" "executable:java.lang.Enum#ordinal()" "executable:java.util.stream.Stream#filter(java.util.function.Predicate)" "executable:java.lang.String#contains(java.lang.CharSequence)" "executable:java.util.stream.Collectors#toSet()" "executable:java.util.zip.ZipInputStream#closeEntry()" "executable:java.util.Map#remove(java.lang.Object)" "executable:java.util.OptionalInt#empty()" "executable:java.net.URLConnection#getInputStream()" "executable:java.util.PrimitiveIterator$OfInt#hasNext()" "executable:java.util.List#size()" "executable:java.util.concurrent.Executors#newFixedThreadPool(int,java.util.concurrent.ThreadFactory)" "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])" "executable:java.lang.AutoCloseable#close()" "executable:java.lang.Integer#toString(int)" "executable:java.net.URI#toASCIIString()" "executable:javax.net.ssl.TrustManagerFactory#getDefaultAlgorithm()" "executable:java.net.http.HttpResponse#headers()" "executable:java.util.Optional#ofNullable(java.lang.Object)" "executable:java.lang.StringBuilder#substring(int,int)" "executable:java.security.KeyStore#getDefaultType()" "executable:java.util.regex.Pattern#pattern()" "executable:java.net.URLConnection#getURL()" "executable:java.lang.Thread#setDaemon(boolean)" "executable:java.util.EnumSet#allOf(java.lang.Class)" "executable:java.util.stream.Stream#flatMap(java.util.function.Function)" "executable:java.nio.file.Path#getRoot()" "executable:java.nio.file.attribute.PosixFilePermissions#fromString(java.lang.String)" "executable:java.util.Collection#remove(java.lang.Object)" "executable:java.time.Duration#getNano()" "executable:java.nio.Buffer#limit()" "executable:java.security.KeyStore#setCertificateEntry(java.lang.String,java.security.cert.Certificate)" "executable:java.util.stream.Collectors#toCollection(java.util.function.Supplier)" "executable:java.util.Map#merge(java.lang.Object,java.lang.Object,java.util.function.BiFunction)" "executable:java.lang.StringBuilder#insert(int,java.lang.String)" "executable:java.util.List#addAll(java.util.Collection)" "executable:java.net.URISyntaxException#getMessage()" "executable:java.net.http.HttpRequest$Builder#GET()" "executable:java.net.http.HttpResponse#uri()" "executable:java.util.List#add(java.lang.Object)" "executable:java.util.OptionalInt#orElse(int)" "executable:java.io.PipedOutputStream#close()" "executable:java.net.URI#getQuery()" "executable:java.util.Set#clear()" "executable:java.lang.Character#isUnicodeIdentifierStart(int)" "executable:java.net.http.HttpRequest#timeout()" "executable:java.nio.ByteBuffer#duplicate()" "executable:java.nio.Buffer#position()" "executable:java.io.PipedOutputStream#flush()" "executable:java.util.concurrent.Executors#newSingleThreadExecutor()" "executable:java.net.http.HttpResponse$BodyHandlers#ofInputStream()" "executable:java.lang.Throwable#printStackTrace()" "executable:java.lang.StringBuilder#appendCodePoint(int)" "executable:java.net.URI#getRawPath()" "executable:java.lang.StringBuilder#append(double)" "executable:java.lang.String#substring(int)" "executable:java.util.stream.LongStream#sum()" "executable:java.util.stream.Stream#collect(java.util.stream.Collector)" "executable:java.util.Map#values()" "executable:java.time.Instant#plus(java.time.temporal.TemporalAmount)" "executable:java.nio.file.Files#walk(java.nio.file.Path,java.nio.file.FileVisitOption[])" "executable:java.util.function.Function#identity()" "executable:java.lang.Math#max(float,float)" "executable:java.util.ArrayList#remove(int)" "executable:java.util.Collection#clear()" "executable:java.time.Duration#toMillis()" "executable:java.io.RandomAccessFile#write(byte[])" "executable:java.util.Iterator#next()" "executable:java.lang.String#toCharArray()" "executable:java.nio.file.FileSystems#newFileSystem(java.net.URI,java.util.Map)" "executable:java.util.Optional#orElse(java.lang.Object)" "executable:java.net.http.HttpHeaders#map()" "executable:java.nio.file.Path#resolveSibling(java.lang.String)" "executable:java.util.Map#clear()" "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)" "executable:java.nio.ByteBuffer#rewind()" "executable:java.nio.file.Path#getFileName()" "executable:java.net.http.HttpRequest$Builder#expectContinue(boolean)" "executable:java.nio.file.Files#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])" "executable:java.nio.file.Path#getNameCount()" "executable:java.security.DigestInputStream#readAllBytes()" "executable:javax.net.ssl.TrustManagerFactory#init(java.security.KeyStore)" "executable:java.util.List#of()" "executable:java.io.PipedOutputStream#connect(java.io.PipedInputStream)" "executable:java.util.Set#removeAll(java.util.Collection)" "executable:java.lang.String#indexOf(int)" "executable:java.nio.file.Files#writeString(java.nio.file.Path,java.lang.CharSequence,java.nio.file.OpenOption[])" "executable:java.net.URI#getSchemeSpecificPart()" "executable:java.lang.String#isEmpty()" "executable:java.nio.file.Files#isSymbolicLink(java.nio.file.Path)" "executable:java.lang.Thread#getId()" "executable:java.util.Map#equals(java.lang.Object)" "executable:java.util.Collections#emptyList()" "executable:java.lang.Math#min(float,float)" "executable:java.lang.Math#max(double,double)" "executable:java.util.BitSet#set(int)" "executable:java.lang.Throwable#printStackTrace(java.io.PrintWriter)" "executable:java.util.function.Function#apply(java.lang.Object)" "executable:java.lang.Thread#currentThread()" "executable:java.util.Objects#nonNull(java.lang.Object)" "executable:java.util.Arrays#hashCode(int[])" "executable:java.lang.StringBuilder#append(char[],int,int)" "executable:java.nio.file.attribute.AclFileAttributeView#setAcl(java.util.List)" "executable:java.lang.Character#isUnicodeIdentifierPart(int)" "executable:java.net.URI#getHost()" "executable:java.util.EnumSet#of(java.lang.Enum)" "executable:java.io.FilterOutputStream#flush()" "executable:java.util.Optional#isPresent()" "executable:java.lang.invoke.MethodHandles#lookup()" "executable:java.util.OptionalInt#isPresent()" "executable:java.lang.reflect.Method#setAccessible(boolean)" "executable:java.util.regex.Matcher#matches()" "executable:java.util.zip.ZipInputStream#getNextEntry()" "executable:java.security.MessageDigest#update(byte[],int,int)" "executable:java.util.Iterator#forEachRemaining(java.util.function.Consumer)" "executable:java.net.URISyntaxException#getReason()" "executable:java.util.Objects#deepEquals(java.lang.Object,java.lang.Object)" "executable:java.io.PushbackInputStream#unread(int)" "executable:java.net.URL#getProtocol()" "executable:java.util.TreeMap#containsKey(java.lang.Object)" "executable:java.util.ArrayList#isEmpty()" "executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)" "executable:java.net.http.HttpResponse#previousResponse()" "executable:javax.net.SocketFactory#createSocket(java.lang.String,int)" "executable:java.lang.Thread#setName(java.lang.String)" "executable:java.util.Collections#unmodifiableMap(java.util.Map)" "executable:java.util.stream.Collectors#toList()" "executable:java.lang.ClassLoader#getResourceAsStream(java.lang.String)" "executable:java.io.PushbackInputStream#read()" "executable:java.util.Set#hashCode()" "executable:java.util.stream.Stream#of(java.lang.Object[])" "executable:javax.net.ssl.SSLContext#getSocketFactory()" "executable:java.util.Collection#spliterator()" "executable:javax.net.ServerSocketFactory#createServerSocket(int)" "executable:java.net.URLEncoder#encode(java.lang.String,java.lang.String)" "executable:java.util.Map$Entry#setValue(java.lang.Object)" "executable:java.io.Closeable#close()" "executable:java.util.List#removeIf(java.util.function.Predicate)" "executable:java.security.AccessController#doPrivileged(java.security.PrivilegedAction)" "executable:java.io.File#isDirectory()" "executable:java.util.Collections#singleton(java.lang.Object)" "executable:java.util.List#clear()" "executable:java.lang.Integer#sum(int,int)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum[])" "executable:java.nio.file.Files#newOutputStream(java.nio.file.Path,java.nio.file.OpenOption[])" "executable:java.net.Socket#isConnected()" "executable:java.util.ResourceBundle#getBundle(java.lang.String,java.util.Locale)" "executable:java.util.BitSet#clear(int)" "executable:java.security.cert.CertificateFactory#getInstance(java.lang.String)" "executable:java.util.Set#add(java.lang.Object)" "executable:java.lang.String#strip()" "executable:java.lang.String#endsWith(java.lang.String)" "executable:java.nio.file.Path#resolve(java.nio.file.Path)" "executable:javax.net.ssl.SSLContext#getServerSocketFactory()" "executable:java.net.InetAddress#getLoopbackAddress()" "executable:javax.net.ssl.TrustManagerFactory#getTrustManagers()" "executable:java.io.OutputStream#write(int)" "executable:java.time.format.DateTimeFormatterBuilder#parseLenient()" "executable:java.util.Optional#isEmpty()" "executable:java.nio.file.Path#normalize()" "executable:java.nio.file.Files#exists(java.nio.file.Path,java.nio.file.LinkOption[])" "executable:java.lang.Class#isInstance(java.lang.Object)" "executable:java.util.stream.Stream#mapToInt(java.util.function.ToIntFunction)" "executable:java.nio.file.Files#copy(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])" "executable:java.lang.String#split(java.lang.String,int)" "executable:java.nio.file.Path#endsWith(java.lang.String)" "executable:java.util.Optional#orElseThrow(java.util.function.Supplier)" "executable:java.nio.file.Path#toString()" "executable:java.lang.Throwable#setStackTrace(java.lang.StackTraceElement[])" "executable:java.lang.Long#toString()" "executable:java.net.http.HttpResponse$BodyHandlers#ofByteArray()" "executable:java.net.http.HttpRequest$BodyPublishers#noBody()" "executable:java.util.ArrayList#get(int)" "executable:java.lang.Iterable#spliterator()" "executable:java.lang.Class#getSimpleName()" "executable:java.lang.Class#getClassLoader()" "executable:java.net.http.HttpRequest$Builder#uri(java.net.URI)" "executable:java.util.concurrent.ConcurrentMap#putIfAbsent(java.lang.Object,java.lang.Object)" "executable:java.util.PrimitiveIterator$OfLong#nextLong()" "executable:java.util.ServiceLoader#load(java.lang.Class)" "executable:java.util.BitSet#set(int,int)" "executable:java.net.http.HttpHeaders#firstValue(java.lang.String)" "executable:java.net.URI#getPort()" "executable:java.net.URI#relativize(java.net.URI)" "executable:java.io.ByteArrayInputStream#read()" "executable:java.nio.file.Files#copy(java.io.InputStream,java.nio.file.Path,java.nio.file.CopyOption[])" "executable:java.util.regex.Pattern#split(java.lang.CharSequence)" "executable:java.net.Socket#setSoTimeout(int)" "executable:java.lang.Runtime#getRuntime()" "executable:java.lang.String#equalsIgnoreCase(java.lang.String)" "executable:java.util.TreeMap#get(java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.Optional#empty()" "executable:java.nio.file.Files#newDirectoryStream(java.nio.file.Path)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.net.http.HttpRequest$Builder#setHeader(java.lang.String,java.lang.String)"} (:key occurrence)) (identifier (.getSimpleName reference)) (= "field:<array>#length" (:key occurrence)) "Length" (= "field:java.io.FilterOutputStream#out" (:key occurrence)) "@out" (= "field:java.lang.System#out" (:key occurrence)) "@out" (= "field:java.lang.System#err" (:key occurrence)) "err" (= "field:java.lang.ProcessBuilder$Redirect#INHERIT" (:key occurrence)) "INHERIT" (= "field:java.nio.charset.StandardCharsets#US_ASCII" (:key occurrence)) "USASCII" (= "field:java.nio.charset.StandardCharsets#UTF_8" (:key occurrence)) "UTF8" (= "field:java.nio.charset.StandardCharsets#ISO_8859_1" (:key occurrence)) "ISO88591" (= "field:java.nio.charset.StandardCharsets#UTF_16LE" (:key occurrence)) "UTF16LE" (= "field:java.time.ZoneOffset#UTC" (:key occurrence)) "Zero" (= "field:java.time.format.DateTimeFormatter#RFC_1123_DATE_TIME" (:key occurrence)) "Rfc1123" (= "field:java.util.Locale#ROOT" (:key occurrence)) "InvariantCulture" (contains? #{"field:java.lang.Integer#MAX_VALUE" "field:java.lang.Byte#MAX_VALUE"} (:key occurrence)) "MaxValue" (= "field:java.lang.Byte#MIN_VALUE" (:key occurrence)) "MinValue" (and (= :field (:kind occurrence)) (str/ends-with? (:key occurrence) "#class")) "class" (contains? #{"field:java.nio.file.attribute.AclEntryPermission#WRITE_ACL" "field:java.nio.file.attribute.AclEntryPermission#WRITE_NAMED_ATTRS" "field:java.nio.file.attribute.AclEntryType#ALLOW" "field:java.nio.file.attribute.AclEntryPermission#SYNCHRONIZE" "field:java.nio.file.attribute.AclEntryPermission#DELETE_CHILD" "field:java.nio.file.StandardOpenOption#READ" "field:java.nio.file.attribute.AclEntryPermission#DELETE" "field:java.nio.file.attribute.AclEntryPermission#APPEND_DATA" "field:java.nio.file.attribute.AclEntryPermission#READ_NAMED_ATTRS" "field:java.nio.channels.FileChannel$MapMode#READ_ONLY" "field:java.nio.file.attribute.AclEntryPermission#READ_ATTRIBUTES" "field:java.nio.file.attribute.AclEntryPermission#WRITE_ATTRIBUTES" "field:java.nio.file.attribute.AclEntryPermission#READ_DATA" "field:java.nio.file.attribute.AclEntryPermission#EXECUTE" "field:java.nio.file.attribute.AclEntryPermission#READ_ACL" "field:java.nio.file.attribute.AclEntryPermission#WRITE_DATA"} (:key occurrence)) (identifier (.getSimpleName reference)) (str/starts-with? (:key occurrence) "executable:java.util.Map#of(") (identifier (.getSimpleName reference)) (str/starts-with? (:key occurrence) "executable:java.util.Set#of(") (identifier (.getSimpleName reference)) (or (and (str/starts-with? (:key occurrence) "executable:java.util.Arrays#copyOf(") (str/ends-with? (:key occurrence) ",int)")) (and (str/starts-with? (:key occurrence) "executable:java.util.Arrays#copyOfRange(") (str/ends-with? (:key occurrence) ",int,int)"))) (identifier (.getSimpleName reference)) (contains? #{"field:java.util.concurrent.TimeUnit#DAYS" "field:java.util.concurrent.TimeUnit#MICROSECONDS" "field:java.util.concurrent.TimeUnit#HOURS" "field:java.util.concurrent.TimeUnit#MILLISECONDS" "field:java.util.concurrent.TimeUnit#NANOSECONDS" "field:java.util.concurrent.TimeUnit#SECONDS" "field:java.util.concurrent.TimeUnit#MINUTES"} (:key occurrence)) (identifier (.getSimpleName reference)) :else (unsupported! "Java library executable or field has no neutral mapping" reference))))

(defn- registry-entry [id emit]
  {:id id :emit emit})

(defn- semantic-mappings [resolved-model ctx-holder]
  (reduce
   (fn [registries occurrence]
     (let [category (case (:kind occurrence)
                      :type :types
                      :executable :executables
                      :constructor :constructors
                      :field :fields
                      :annotation :annotations
                      nil)
           key (:key occurrence)]
       (if (or (nil? category) (get-in registries [category key]))
         registries
         (assoc-in
          registries [category key]
          (case category
            :types
            (registry-entry
             (keyword "java-library.resolved.type" (name (:origin occurrence)))
             (fn [{:keys [element]}]
               {:node (type-node @ctx-holder element)}))

            :executables
            (registry-entry
             (keyword "java-library.resolved.executable"
                      (name (:origin occurrence)))
             (fn [{:keys [element occurrence]}]
               {:node (raw (resolved-name @ctx-holder occurrence element))}))

            :constructors
            (registry-entry
             (keyword "java-library.resolved.constructor"
                      (name (:origin occurrence)))
             (fn [{:keys [element occurrence]}]
               (cond
                 (= :project (:origin occurrence))
                 {:node (raw "<init>")}

                 (and (= :dependency (:origin occurrence))
                      (some->> element
                               .getDeclaringType
                               (translated-external-type-base @ctx-holder)))
                 {:node (raw "<init>")}

                 (and (= :intrinsic (:origin occurrence))
                      (= :enum-synthetic-constructor (:resolution occurrence)))
                 {:node (raw "<init>")}

                 (and (= :intrinsic (:origin occurrence))
                      (= :array-constructor (:resolution occurrence)))
                 {:node (raw "<init>")}

                 (contains? (:destination-constructor-adaptations @ctx-holder)
                            (:key occurrence))
                 {:node (raw "<init>")}

                 (when-let [resolved-constructor?
                            (:destination-resolved-constructor? @ctx-holder)]
                   (resolved-constructor?
                    @ctx-holder occurrence element))
                 {:node (raw "<init>")}

                 (contains?
                  #{"executable:java.lang.Object#<init>()"
                    "executable:java.lang.Record#<init>()"
                    "executable:java.lang.Enum#<init>(java.lang.String,int)"
                    "executable:java.lang.Throwable#<init>()"
                    "executable:java.io.InputStream#<init>()"
                    "executable:java.io.OutputStream#<init>()"
                    "executable:java.io.Writer#<init>()"
                    "executable:java.io.IOException#<init>()"
                    "executable:java.io.IOException#<init>(java.lang.String)"
                    "executable:java.io.IOException#<init>(java.lang.Throwable)"
                    "executable:java.io.IOException#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.io.FileNotFoundException#<init>()"
                    "executable:java.io.EOFException#<init>()"
                    "executable:java.io.EOFException#<init>(java.lang.String)"
                    "executable:java.io.File#<init>(java.lang.String)"
                    "executable:java.io.File#<init>(java.lang.String,java.lang.String)"
                    "executable:java.io.File#<init>(java.net.URI)"
                    "executable:java.io.FileInputStream#<init>(java.io.File)"
                    "executable:java.io.FileInputStream#<init>(java.lang.String)"
                    "executable:java.io.FileReader#<init>(java.io.File)"
                    "executable:java.io.FileOutputStream#<init>(java.io.File)"
                    "executable:java.io.FileOutputStream#<init>(java.lang.String)"
                    "executable:java.io.RandomAccessFile#<init>(java.io.File,java.lang.String)"
                    "executable:java.nio.file.FileSystem#<init>()"
                    "executable:java.math.BigInteger#<init>(int,byte[])"
                    "executable:java.math.BigDecimal#<init>(int)"
                    "executable:java.math.BigDecimal#<init>(java.lang.String)"
                    "executable:java.security.SecureRandom#<init>()"
                    "executable:java.util.Random#<init>()"
                    "executable:java.util.concurrent.CompletableFuture#<init>()"
                    "executable:javax.crypto.CipherInputStream#<init>(java.io.InputStream,javax.crypto.Cipher)"
                    "executable:javax.crypto.spec.IvParameterSpec#<init>(byte[])"
                    "executable:javax.crypto.spec.SecretKeySpec#<init>(byte[],java.lang.String)"
                    "executable:java.util.NoSuchElementException#<init>()"
                    "executable:java.util.NoSuchElementException#<init>(java.lang.String)"
                    "executable:java.lang.Exception#<init>()"
                    "executable:java.lang.Exception#<init>(java.lang.String)"
                    "executable:java.lang.Exception#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.lang.Exception#<init>(java.lang.Throwable)"
                    "executable:java.lang.RuntimeException#<init>()"
                    "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"
                    "executable:java.lang.RuntimeException#<init>(java.lang.String)"
                    "executable:java.lang.RuntimeException#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.lang.ExceptionInInitializerError#<init>(java.lang.Throwable)"
                    "executable:java.util.concurrent.TimeoutException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalStateException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalStateException#<init>()"
                    "executable:java.lang.IllegalArgumentException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalArgumentException#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.lang.IllegalArgumentException#<init>(java.lang.Throwable)"
                    "executable:java.lang.IllegalArgumentException#<init>()"
                    "executable:java.lang.ArithmeticException#<init>()"
                    "executable:java.lang.ArithmeticException#<init>(java.lang.String)"
                    "executable:java.lang.ClassCastException#<init>(java.lang.String)"
                    "executable:java.lang.IndexOutOfBoundsException#<init>(java.lang.String)"
                    "executable:java.lang.NullPointerException#<init>(java.lang.String)"
                    "executable:java.lang.UnsupportedOperationException#<init>(java.lang.String)"
                    "executable:java.lang.UnsupportedOperationException#<init>()"
                    "executable:java.lang.AssertionError#<init>()"
                    "executable:java.lang.AssertionError#<init>(java.lang.Object)"
                    "executable:java.lang.AssertionError#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.lang.StringBuilder#<init>()"
                    "executable:java.lang.StringBuilder#<init>(int)"
                    "executable:java.lang.StringBuilder#<init>(java.lang.String)"
                    "executable:java.lang.String#<init>(char[])"
                    "executable:java.lang.String#<init>(char[],int,int)"
                    "executable:java.lang.String#<init>(int[],int,int)"
                    "executable:java.lang.String#<init>(byte[],java.nio.charset.Charset)"
                    "executable:java.lang.String#<init>(byte[],int,int,java.nio.charset.Charset)"
                    "executable:java.lang.ref.SoftReference#<init>(java.lang.Object)"
                    "executable:java.lang.ref.WeakReference#<init>(java.lang.Object)"
                    "executable:java.io.ByteArrayOutputStream#<init>()"
                    "executable:java.io.ByteArrayOutputStream#<init>(int)"
                    "executable:java.io.ByteArrayInputStream#<init>(byte[])"
                    "executable:java.io.ByteArrayInputStream#<init>(byte[],int,int)"
                    "executable:java.io.BufferedInputStream#<init>(java.io.InputStream)"
                    "executable:java.io.BufferedOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.io.BufferedReader#<init>(java.io.Reader)"
                    "executable:java.io.BufferedWriter#<init>(java.io.Writer)"
                    "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.io.FilterInputStream#<init>(java.io.InputStream)"
                    "executable:java.io.PipedInputStream#<init>()"
                    "executable:java.io.PipedOutputStream#<init>()"
                    "executable:java.io.PushbackInputStream#<init>(java.io.InputStream)"
                    "executable:java.io.PushbackInputStream#<init>(java.io.InputStream,int)"
                    "executable:java.io.SequenceInputStream#<init>(java.io.InputStream,java.io.InputStream)"
                    "executable:java.io.StringReader#<init>(java.lang.String)"
                    "executable:java.io.StringWriter#<init>()"
                    "executable:java.io.PrintWriter#<init>(java.io.Writer)"
                    "executable:java.io.FileWriter#<init>(java.io.File,java.nio.charset.Charset)"
                    "executable:java.io.DataOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.io.InputStreamReader#<init>(java.io.InputStream,java.nio.charset.Charset)"
                    "executable:java.io.OutputStreamWriter#<init>(java.io.OutputStream,java.nio.charset.Charset)"
                    "executable:java.io.LineNumberReader#<init>(java.io.Reader)"
                    "executable:java.security.DigestInputStream#<init>(java.io.InputStream,java.security.MessageDigest)"
                    "executable:java.security.DigestOutputStream#<init>(java.io.OutputStream,java.security.MessageDigest)"
                    "executable:java.util.zip.GZIPInputStream#<init>(java.io.InputStream)"
                    "executable:java.util.zip.ZipInputStream#<init>(java.io.InputStream)"
                    "executable:java.util.zip.ZipOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.util.zip.ZipEntry#<init>(java.lang.String)"
                    "executable:java.util.zip.CRC32#<init>()"
                    "executable:java.util.zip.InflaterOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.util.zip.Inflater#<init>(boolean)"
                    "executable:java.util.zip.Deflater#<init>(int)"
                    "executable:java.util.zip.DeflaterOutputStream#<init>(java.io.OutputStream,java.util.zip.Deflater)"
                    "executable:java.net.URI#<init>(java.lang.String)"
                    "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String)"
                    "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String)"
                    "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)"
                    "executable:java.net.URISyntaxException#<init>(java.lang.String,java.lang.String)"
                    "executable:java.net.URISyntaxException#<init>(java.lang.String,java.lang.String,int)"
                    "executable:java.util.HashMap#<init>()"
                    "executable:java.util.HashMap#<init>(int)"
                    "executable:java.util.HashMap#<init>(int,float)"
                    "executable:java.util.HashMap#<init>(java.util.Map)"
                    "executable:java.util.IdentityHashMap#<init>()"
                    "executable:java.util.WeakHashMap#<init>()"
                    "executable:java.util.Hashtable#<init>()"
                    "executable:java.util.HashSet#<init>(int)"
                    "executable:java.util.HashSet#<init>(int,float)"
                    "executable:java.util.HashSet#<init>()"
                    "executable:java.util.HashSet#<init>(java.util.Collection)"
                    "executable:java.util.LinkedHashSet#<init>()"
                    "executable:java.util.LinkedHashSet#<init>(int)"
                    "executable:java.util.LinkedHashSet#<init>(int,float)"
                    "executable:java.util.LinkedHashSet#<init>(java.util.Collection)"
                    "executable:java.util.ArrayList#<init>()"
                    "executable:java.util.ArrayList#<init>(int)"
                    "executable:java.util.ArrayList#<init>(java.util.Collection)"
                    "executable:java.util.ArrayDeque#<init>()"
                    "executable:java.util.ArrayDeque#<init>(int)"
                    "executable:java.util.PriorityQueue#<init>()"
                    "executable:java.util.PriorityQueue#<init>(int)"
                    "executable:java.util.Properties#<init>()"
                    "executable:java.util.EnumMap#<init>(java.lang.Class)"
                    "executable:java.util.LinkedList#<init>()"
                    "executable:java.util.StringJoiner#<init>(java.lang.CharSequence,java.lang.CharSequence,java.lang.CharSequence)"
                    "executable:java.util.Stack#<init>()"
                    "executable:java.text.MessageFormat#<init>(java.lang.String)"
                    "executable:java.text.MessageFormat#<init>(java.lang.String,java.util.Locale)"
                    "executable:java.text.Bidi#<init>(java.lang.String,int)"
                    "executable:java.text.DecimalFormat#<init>()"
                    "executable:java.text.DecimalFormat#<init>(java.lang.String,java.text.DecimalFormatSymbols)"
                    "executable:java.util.StringTokenizer#<init>(java.lang.String)"
                    "executable:java.util.StringTokenizer#<init>(java.lang.String,java.lang.String)"
                    "executable:java.util.TreeMap#<init>()"
                    "executable:java.util.TreeMap#<init>(java.util.Comparator)"
                    "executable:java.util.TreeSet#<init>()"
                    "executable:java.util.TreeSet#<init>(java.util.Collection)"
                    "executable:java.util.TreeSet#<init>(java.util.Comparator)"
                    "executable:java.util.LinkedHashMap#<init>()"
                    "executable:java.util.LinkedHashMap#<init>(int)"
                    "executable:java.util.LinkedHashMap#<init>(int,float)"
                    "executable:java.util.LinkedHashMap#<init>(int,float,boolean)"
                    "executable:java.util.LinkedHashMap#<init>(java.util.Map)"
                    "executable:java.util.AbstractMap$SimpleEntry#<init>(java.lang.Object,java.lang.Object)"
                    "executable:java.util.AbstractMap$SimpleImmutableEntry#<init>(java.lang.Object,java.lang.Object)"
                    "executable:java.util.Iterator#<init>()"
                    "executable:java.lang.Thread#<init>(java.lang.Runnable)"
                    "executable:java.lang.Thread#<init>(java.lang.Runnable,java.lang.String)"
                    "executable:java.lang.ProcessBuilder#<init>(java.util.List)"
                    "executable:java.util.concurrent.atomic.AtomicBoolean#<init>()"
                    "executable:java.util.concurrent.atomic.AtomicBoolean#<init>(boolean)"
                    "executable:java.util.concurrent.atomic.AtomicInteger#<init>(int)"
                    "executable:java.util.concurrent.atomic.AtomicReference#<init>()"
                    "executable:java.util.BitSet#<init>()"
                    "executable:java.util.concurrent.ConcurrentHashMap#<init>()"
                    "executable:java.util.concurrent.ConcurrentHashMap#<init>(int)"
                    "executable:java.util.concurrent.ConcurrentHashMap#<init>(int,float)"
                    "executable:java.time.format.DateTimeFormatterBuilder#<init>()"
                    "executable:java.util.GregorianCalendar#<init>()"
                    "executable:java.util.GregorianCalendar#<init>(java.util.TimeZone)"
                    "executable:java.util.SimpleTimeZone#<init>(int,java.lang.String)"
                    "executable:javax.xml.namespace.QName#<init>(java.lang.String)"
                    "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String)"
                    "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String,java.lang.String)"
                    "executable:javax.xml.transform.dom.DOMSource#<init>(org.w3c.dom.Node)"
                    "executable:javax.xml.transform.stream.StreamResult#<init>(java.io.OutputStream)"
                    "executable:javax.xml.transform.stream.StreamResult#<init>(java.io.File)"
                    "executable:java.awt.geom.GeneralPath#<init>()"
                    "executable:java.awt.geom.Point2D$Float#<init>(float,float)"
                    "executable:java.net.ServerSocket#<init>(int)"
                    "executable:java.net.Socket#<init>(java.net.InetAddress,int)"
                    "executable:java.net.Socket#<init>(java.lang.String,int)"}
                  (:key occurrence))
                 {:node (raw "")}

                 :else
                 (unsupported! "Java library constructor has no neutral mapping"
                               element))))

            :fields
            (registry-entry
             (keyword "java-library.resolved.field"
                      (name (:origin occurrence)))
             (fn [{:keys [element occurrence]}]
               {:node (raw (resolved-name @ctx-holder occurrence element))}))

            :annotations
            (registry-entry
             :java-library.resolved.annotation/source-only
             (fn [_] {:node (raw "")})))))))
   {:types {} :executables {} :constructors {} :fields {} :annotations {}}
   (:occurrences resolved-model)))

(defn- invocation-occurrence [translation-context ^CtInvocation invocation]
  (.get ^IdentityHashMap (:occurrence-index translation-context)
        (.getExecutable invocation)))

(defn- constructor-occurrence [translation-context ^CtConstructorCall call]
  (.get ^IdentityHashMap (:occurrence-index translation-context)
        (.getExecutable call)))

(defn- field-occurrence [translation-context ^CtVariableAccess access]
  (.get ^IdentityHashMap (:occurrence-index translation-context)
        (.getVariable access)))

(defn- expression-cast-node [ctx ^CtExpression expression node]
  (reduce (fn [inner ^CtTypeReference cast]
            (let [qualified-name (.getQualifiedName cast)
                  arguments (vec (.getActualTypeArguments cast))]
              (cond
                (and (= "java.util.List" qualified-name)
                     (= 1 (count arguments)))
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.CastList<")
                  (type-node ctx (first arguments))
                  (raw ">(") inner (raw ")")])

                (and (= "java.util.Map" qualified-name)
                     (= 2 (count arguments)))
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.CastDictionary<")
                  (type-node ctx (first arguments))
                  (raw ", ")
                  (type-node ctx (second arguments))
                  (raw ">(") inner (raw ")")])

                (and (= "java.util.Map" qualified-name)
                     (empty? arguments))
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.CastRawDictionary(")
                  inner (raw ")")])

                (and (= "java.util.Comparator" qualified-name)
                     (empty? arguments))
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.EraseComparer(")
                  inner (raw ")")])

                (boxed-primitive-reference? cast)
                (sequence-node
                 [(raw "(") (type-node ctx cast)
                  (raw "?)(") inner (raw ")")])

                (and (not (.isPrimitive cast))
                     (instance?
                      CtTypeParameterReference
                      (.getType expression)))
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.CastReference<")
                  (type-node ctx cast) (raw ">(") inner (raw ")")])

                :else
                (sequence-node [(raw "(") (type-node ctx cast)
                                (raw ")(") inner
                                (raw (if (.isPrimitive cast) ")" "!)"))]))))
          node
          (.getTypeCasts expression)))

(declare maybe-unbox-node compat-call)

(defn- unbox-object-as-node [ctx ^CtTypeReference target-reference node]
  (sequence-node
   [(raw "global::DripSharp.Runtime.JavaCompat.UnboxObject<")
    (type-node ctx target-reference)
    (raw ">(") node (raw ")")]))

(defn- destination-value-node
  [ctx kind source target-reference target node]
  (if-let [adapt (:destination-value-adapter ctx)]
    (or
     (adapt
      {:destination-context ctx
       :kind kind
       :source source
       :target target
       :target-reference target-reference
       :node node})
     node)
    node))

(defn- assignment-value-node [ctx ^CtElement assigned ^CtExpression assignment node]
  (let [assigned-reference (some-> assigned .getType)
        assigned-type (some-> assigned-reference .getQualifiedName)
        assignment-type (some-> assignment .getType .getQualifiedName)
        node
        (if (and assigned-reference (.isPrimitive assigned-reference))
          (if (boxed-primitive-reference? (.getType assignment))
            (unbox-object-as-node ctx assigned-reference node)
            (maybe-unbox-node ctx assignment node))
          node)]
    (destination-value-node
     ctx :assignment assignment assigned-reference assigned
     (cond (nullable-boxed-collection-declaration? assigned assigned-reference) (let [element (boxed-primitive-collection-element assigned-reference)] (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.CastList<") (type-node ctx element) (raw "?>(") node (raw ")")])) (and (instance? CtMethod assigned) (covariant-list-node ctx assigned assigned-reference) (= "java.util.List" assignment-type) (= 1 (count (.getActualTypeArguments (.getType assignment)))) (not= (some-> (first (.getActualTypeArguments (.getType assignment))) .getQualifiedName) (some-> (first (.getActualTypeArguments assigned-reference)) .getBoundingType .getQualifiedName))) (let [bound (.getBoundingType (first (.getActualTypeArguments assigned-reference)))] (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.ToReadOnlyList<") (type-node ctx bound) (raw ">(") node (raw ")")])) (unbounded-wildcard-map-reference? assigned-reference) (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.CastDictionary<object, object>(") node (raw ")")]) (and (= "byte" assigned-type) (not= "byte" assignment-type)) (sequence-node [(raw "unchecked((sbyte)(") node (raw "))")]) (and (= "char" assigned-type) (not= "char" assignment-type)) (sequence-node [(raw "unchecked((char)(") node (raw "))")]) :else node))))

(defn- collection-element-type [^CtInvocation invocation]
  (or (first (.getActualTypeArguments (.getType invocation)))
      (unsupported! "Generic Java collection invocation has no resolved element type"
                    invocation)))

(defn- nullable-node [node]
  (if (str/ends-with? (:text (csharp/render node)) "?")
    node
    (sequence-node [node (raw "?")])))

(defn- collection-element-type-node [ctx ^CtInvocation invocation]
  (let [reference (collection-element-type invocation)
        nullable?
        (or
         (nullable-type-argument? ctx reference)
         (some #(nullable-expression? ctx %) (.getArguments invocation)))
        node (type-node ctx reference)]
    (if (and nullable? (not (.isPrimitive reference)))
      (nullable-node node)
      node)))

(defn- collection-factory-argument-nodes
  [ctx ^CtInvocation invocation arguments element-node]
  (mapv
   (fn [source argument]
     (if (and (instance? CtLiteral source)
              (nil? (.getValue ^CtLiteral source)))
       (sequence-node [(raw "(") element-node (raw ")default!")])
       argument))
   (.getArguments invocation)
   arguments))

(defn- project-invocation-type-arguments-node
  [ctx ^CtInvocation invocation declaration]
  (when (and (instance? CtMethod declaration)
             (seq (.getFormalCtTypeParameters ^CtMethod declaration)))
    (let [actual (vec (.getActualTypeArguments (.getExecutable invocation)))
          inferred
          (if (seq actual)
            actual
            (when (and (= 1 (count (.getFormalCtTypeParameters
                                    ^CtMethod declaration)))
                       (instance? CtTypeParameterReference
                                  (.getType ^CtMethod declaration))
                       (not (instance? CtTypeParameterReference
                                       (.getType invocation))))
              [(.getType invocation)]))]
      (when (seq inferred)
        (sequence-node
         [(raw "<")
          (sequence-node (mapv #(type-node ctx %) inferred) ", ")
          (raw ">")])))))

(defn- compat-call [name arguments]
  (sequence-node
   [(raw (str "global::DripSharp.Runtime.JavaCompat." name "("))
    (sequence-node arguments ", ")
    (raw ")")]))

(defn- stream-collect-mapping
  [{:keys [destination-context element target arguments]}]
  {:node
   (case (some-> ^CtInvocation element .getType .getQualifiedName)
     "java.lang.String"
     (compat-call "Collect" (into [target] arguments))

     "java.util.Map"
     (compat-call "Collect" (into [target] arguments))

     ("java.util.Set" "java.util.HashSet" "java.util.LinkedHashSet")
     (sequence-node
      [(csharp/generic-name
        (raw "global::DripSharp.Runtime.JavaCompat.SetOfValues")
        [(type-node destination-context (collection-element-type element))])
       (raw "(") target (raw ")")])

     "java.util.ArrayList"
     (sequence-node
      [(raw "new ")
       (type-node destination-context (.getType ^CtInvocation element))
       (raw "(") target (raw ")")])

     (compat-call "ToListValues" [target]))})

(defn- atomic-reference-get-mapping
  [{:keys [element target]}]
  {:node
   (sequence-node
    [target
     (raw ".Get()")
     (when-not (statement-expression? element) (raw "!"))])})

(def ^:private declarative-shared-mappings
  (library-mappings/compile-registry
   {:java-library.mapping/stream-collect stream-collect-mapping
    :java-library.mapping/atomic-reference-get atomic-reference-get-mapping}))

(defn- declarative-shared-invocation-node
  [ctx ^CtInvocation element occurrence target arguments]
  (when (mapping-registry/registry-entry
         declarative-shared-mappings
         (:key occurrence))
    (:node
     (mapping-registry/interpret
      declarative-shared-mappings
      (:key occurrence)
      {:target target
       :arguments arguments
       :element element
       :destination-context ctx}))))

(defn- nullable-boxed-expression? [ctx ^CtExpression expression]
  (or
   (some boxed-primitive-reference? (.getTypeCasts expression))
   (cond
     (instance? CtInvocation expression)
     (let [occurrence (invocation-occurrence ctx expression)
           referenced-declaration
           (some-> expression .getExecutable .getDeclaration)
           declaration
           (if (instance? CtMethod referenced-declaration)
             referenced-declaration
             (:declaration occurrence))]
       (or (and (contains?
                 #{"executable:java.util.Map#get(java.lang.Object)"
                   "executable:java.util.TreeMap#get(java.lang.Object)"}
                 (:key occurrence))
                (boxed-primitive-reference? (.getType expression)))
           (and (= "executable:java.util.List#get(int)"
                   (:key occurrence))
                (boxed-primitive-reference? (.getType expression))
                (nullable-boxed-collection-expression?
                 (.getTarget ^CtInvocation expression) []))
           (and (boxed-primitive-reference? (.getType expression))
                (nullable-runtime-boxed-primitive-method? occurrence))
           (and (instance? CtMethod declaration)
                (not (.isShadow ^CtMethod declaration))
                (nullable-boxed-declaration?
                 ctx declaration (.getType ^CtMethod declaration)))))

     (instance? CtVariableRead expression)
     (let [declaration (some-> expression .getVariable .getDeclaration)]
       (and (boxed-primitive-reference? (.getType expression))
            declaration
            (nullable-boxed-declaration?
             ctx declaration (.getType declaration))))

     (instance? CtFieldRead expression)
     (let [declaration (some-> expression .getVariable .getDeclaration)]
       (and (boxed-primitive-reference? (.getType expression))
            declaration
            (nullable-boxed-declaration?
             ctx declaration (.getType declaration))))

     (instance? CtConditional expression)
     (boxed-primitive-reference? (.getType expression))

     :else false)))

(defn- covariant-boxed-invocation?
  [ctx ^CtExpression expression]
  (when (instance? CtInvocation expression)
    (let [declaration (some-> expression .getExecutable .getDeclaration)
          occurrence
          (occurrence! ctx (.getExecutable ^CtInvocation expression)
                       :executable)]
      (or
       (contains? (:destination-boxed-covariant-executables ctx)
                  (:key occurrence))
       (and
        (instance? CtMethod declaration)
        (boxed-primitive-reference? (.getType ^CtMethod declaration))
        (covariant-value-override?
         (.getDeclaringType ^CtMethod declaration)
         declaration))))))

(defn- maybe-unbox-node [ctx ^CtExpression expression node]
  (cond
    (not (nullable-boxed-expression? ctx expression))
    node

    (covariant-boxed-invocation? ctx expression)
    (sequence-node
     [(raw "(") (type-node ctx (.getType expression))
      (raw ")(") node (raw ")")])

    :else
    (compat-call "Unbox" [node])))

(defn- boolean-condition-node [ctx ^CtExpression expression node]
  (if (= "java.lang.Boolean"
         (some-> expression .getType .getQualifiedName))
    (compat-call "Unbox" [node])
    (maybe-unbox-node ctx expression node)))

(defn- argument-value-node
  [ctx ^CtExpression argument ^CtTypeReference expected node force-value?]
  (let [expected-name (some-> expected .getQualifiedName)
        argument-name (some-> argument .getType .getQualifiedName)
        value-node (maybe-unbox-node ctx argument node)]
    (destination-value-node
     ctx :argument argument expected nil
     (cond
       (unbounded-wildcard-collection-reference? expected)
       (compat-call "CastObjects" [node])

       (unbounded-wildcard-map-reference? expected)
       (let [key-reference (first (.getActualTypeArguments expected))]
         (sequence-node
          [(raw "global::DripSharp.Runtime.JavaCompat.CastDictionary<")
           (type-node ctx key-reference) (raw ", object>(") node (raw ")")]))

       (and (= "java.util.Collection" expected-name)
            (= "java.util.List" argument-name)
            (covariant-list-node ctx argument (.getType argument)))
       (let [bound
             (.getBoundingType
              ^CtWildcardReference
              (first (.getActualTypeArguments (.getType argument))))]
         (sequence-node
          [(raw "global::DripSharp.Runtime.JavaCompat.ToListValues<")
           (type-node ctx bound) (raw ">(") node (raw ")")]))

       (and (= "byte" expected-name)
            (not= "byte" argument-name))
       (sequence-node [(raw "unchecked((sbyte)(") value-node (raw "))")])

       (and (= "char" expected-name)
            (not= "char" argument-name))
       (sequence-node [(raw "unchecked((char)(") value-node (raw "))")])

       (and (= "java.lang.CharSequence" expected-name)
            (= "java.lang.StringBuilder" argument-name))
       (sequence-node [node (raw ".ToString()")])

       (and (= "java.util.Comparator" expected-name)
            (or (instance? CtLambda argument)
                (instance? CtExecutableReferenceExpression argument))
            (empty? (.getActualTypeArguments (.getType argument)))
            (= 1 (count (.getActualTypeArguments expected))))
       (sequence-node
        [(raw "global::System.Collections.Generic.Comparer<")
         (type-node ctx (first (.getActualTypeArguments expected)))
         (raw ">.Create(") node (raw ")")])

       (and (or (instance? CtLambda argument)
                (instance? CtExecutableReferenceExpression argument))
            (translated-external-type-base ctx expected)
            (not
             (functional-interface-method
              (some-> argument .getType .getTypeDeclaration))))
       (let [target (translated-external-type-base ctx expected)
             separator (.lastIndexOf ^String target ".")
             owner (subs target 0 separator)
             adapter
             (str owner ".__" (pascal (.getSimpleName expected))
                  "FunctionalAdapter")
             arguments (vec (.getActualTypeArguments expected))]
         (sequence-node
          [(raw (str "new " adapter))
           (when (seq arguments)
             (sequence-node
              [(raw "<")
               (sequence-node (mapv #(type-node ctx %) arguments) ", ")
               (raw ">")]))
           (raw "(") node (raw ")")]))

       (and expected
            (not force-value?)
            (instance? CtLiteral argument)
            (nil? (.getValue ^CtLiteral argument))
            (boxed-primitive-reference? expected))
       (sequence-node
        [(raw "(") (type-node ctx expected) (raw "?)default!")])

       (and expected
            (not force-value?)
            (instance? CtLiteral argument)
            (nil? (.getValue ^CtLiteral argument))
            (not (.isPrimitive expected)))
       (sequence-node
        [(raw "(") (type-node ctx expected) (raw ")default!")])

       (and expected
            (.isPrimitive expected)
            (contains? #{"double" "float" "long" "int" "short"}
                       expected-name)
            (not= expected-name argument-name))
       (sequence-node
        [(raw (str "("
                   (get {"double" "double"
                         "float" "float"
                         "long" "long"
                         "int" "int"
                         "short" "short"}
                        expected-name)
                   ")("))
         value-node
         (raw ")")])

       (or force-value?
           (and expected
                (or (.isPrimitive expected)
                    (instance? CtTypeParameterReference expected))))
       value-node

       :else node))))

(defn- enclosing-method [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtMethod current) current
      (.isParentInitialized ^CtElement current)
      (recur (.getParent ^CtElement current))
      :else nil)))

(defn- expression-statement-node [^CtExpression expression node]
  (if (statement-expression? expression)
    (sequence-node [node (raw ";")])
    node))

(defn- string-expression? [^CtExpression expression]
  (= "java.lang.String" (some-> expression .getType .getQualifiedName)))

(def ^:private java-small-integral-types
  #{"byte" "char" "short"})

(defn- promoted-comparison-operand-node
  [^CtExpression expression node]
  (if (contains? java-small-integral-types
                 (some-> expression .getType .getQualifiedName))
    (sequence-node [(raw "(int)(") node (raw ")")])
    node))

(defn- promoted-division-operand-node
  [^CtExpression expression node]
  (let [effective-type
        (or (some-> expression .getTypeCasts last .getQualifiedName)
            (some-> expression .getType .getQualifiedName))]
    (case effective-type
      ("double" "java.lang.Double")
      (sequence-node [(raw "(double)(") node (raw ")")])

      ("float" "java.lang.Float")
      (sequence-node [(raw "(float)(") node (raw ")")])

      node)))

(defn- type-parameter-expression? [^CtExpression expression]
  (or (instance? CtTypeParameterReference (.getType expression))
      (and (instance? CtInvocation expression)
           (instance?
            CtTypeParameterReference
            (some-> expression .getExecutable .getDeclaration .getType)))))

(defn- binary-operator [^CtBinaryOperator expression]
  (case (str (.getKind expression))
    "OR" "||"
    "AND" "&&"
    "BITOR" "|"
    "BITXOR" "^"
    "BITAND" "&"
    "EQ" "=="
    "NE" "!="
    "LT" "<"
    "LE" "<="
    "GT" ">"
    "GE" ">="
    "SL" "<<"
    "SR" ">>"
    "USR" ">>>"
    "PLUS" "+"
    "MINUS" "-"
    "MUL" "*"
    "DIV" "/"
    "MOD" "%"
    "INSTANCEOF" "is"
    (unsupported! "Java library binary operator has no neutral mapping"
                  expression)))

(defn- assignment-operator [^CtOperatorAssignment expression]
  (case (str (.getKind expression))
    "PLUS" "+"
    "MINUS" "-"
    "MUL" "*"
    "DIV" "/"
    "MOD" "%"
    "BITAND" "&"
    "BITOR" "|"
    "BITXOR" "^"
    "SL" "<<"
    "SR" ">>"
    "USR" ">>>"
    (unsupported! "Java library operator assignment has no neutral mapping"
                  expression)))

(defn- unary-operator [^CtUnaryOperator expression]
  (case (str (.getKind expression))
    "NOT" ["!" ""]
    "NEG" ["-" ""]
    "POS" ["+" ""]
    "COMPL" ["~" ""]
    "PREINC" ["++" ""]
    "PREDEC" ["--" ""]
    "POSTINC" ["" "++"]
    "POSTDEC" ["" "--"]
    (unsupported! "Java library unary operator has no neutral mapping"
                  expression)))

(defn- switch-expression-yield? [^CtYieldStatement statement]
  (loop [current (.getParent statement)]
    (cond
      (instance? CtSwitchExpression current) true
      (instance? CtSwitch current) false
      (nil? current) false
      :else (recur (.getParent ^CtElement current)))))

(defn- ignorable-implicit-statement? [statement]
  (and (.isImplicit ^CtElement statement)
       (not (instance? CtYieldStatement statement))))

(defn- terminating-statement? [statement]
  (cond
    (or (instance? CtReturn statement)
        (instance? CtThrow statement)
        (instance? CtBreak statement)
        (instance? CtContinue statement)) true
    (instance? CtYieldStatement statement)
    (switch-expression-yield? statement)
    (instance? CtBlock statement)
    (boolean (some-> ^CtBlock statement .getStatements last terminating-statement?))
    (instance? CtIf statement)
    (let [else-statement (.getElseStatement ^CtIf statement)]
      (and else-statement
           (terminating-statement? (.getThenStatement ^CtIf statement))
           (terminating-statement? else-statement)))
    (instance? CtTry statement)
    (let [body (.getBody ^CtTry statement)
          catchers (vec (.getCatchers ^CtTry statement))
          finalizer (.getFinalizer ^CtTry statement)]
      (or (and finalizer (terminating-statement? finalizer))
          (and (terminating-statement? body)
               (every? #(terminating-statement?
                         (.getBody ^CtCatch %))
                       catchers))))
    :else false))

(defn- enum-switch-declaration [ctx ^CtAbstractSwitch switch]
  (when-let [reference (some-> switch .getSelector .getType)]
    (let [occurrence (occurrence! ctx reference :type)
          declaration (:declaration occurrence)]
      (when (and (= :project (:origin occurrence))
                 (instance? CtEnum declaration))
        declaration))))

(defn- enum-case-node [ctx ^CtEnum enum ^CtExpression expression]
  (let [occurrence (when (instance? CtFieldRead expression)
                     (field-occurrence ctx expression))
        declaration (:declaration occurrence)
        ordinal (first
                 (keep-indexed
                  (fn [index ^CtEnumValue value]
                    (when (identical? value declaration) index))
                  (.getEnumValues enum)))]
    (when-not (and (= :project (:origin occurrence))
                   (instance? CtEnumValue declaration)
                   (some? ordinal))
      (unsupported! "Java enum case has no exact resolved ordinal" expression))
    (csharp/with-source
      (raw (str ordinal))
      (source-ref expression :java-library.expression/enum-case-ordinal
                  {:mapping {:resolved-key (:key occurrence)
                             :ordinal ordinal}}))))

(defn- inferred-instanceof-type-node
  [ctx ^CtBinaryOperator expression children]
  (let [right (.getRightHandOperand expression)
        reference (when (instance? CtTypeAccess right)
                    (.getAccessedType ^CtTypeAccess right))
        occurrence (when reference (occurrence! ctx reference :type))
        declaration (when (= :project (:origin occurrence))
                      (or (:declaration occurrence)
                          (.getTypeDeclaration ^CtTypeReference reference)))
        left-arguments (vec (.getActualTypeArguments
                             (.getType (.getLeftHandOperand expression))))
        formal-count (if (instance? CtType declaration)
                       (count (.getFormalCtTypeParameters ^CtType declaration))
                       0)]
    (if (and (instance? CtType declaration)
             (empty? (.getActualTypeArguments ^CtTypeReference reference))
             (pos? formal-count)
             (= formal-count (count left-arguments)))
      (csharp/generic-name (raw (project-type-base ctx declaration))
                           (mapv #(type-node ctx %) left-arguments))
      (child-node children right))))

(defn- primitive-expression? [^CtExpression expression]
  (boolean (some-> expression .getType .isPrimitive)))

(defn- neutral-binary-node
  [kind right-expression left]
  (when
   (and (= "INSTANCEOF" kind)
        (instance? CtTypeAccess right-expression))
    (let [reference (.getAccessedType ^CtTypeAccess right-expression)]
      (when
       (and (= "java.util.Set" (.getQualifiedName reference))
            (empty? (.getActualTypeArguments reference)))
        (compat-call "IsSet" [left])))))

(defn- case-labels [ctx children ^CtCase case]
  (let [expressions (vec (.getCaseExpressions case))
        parent (when (.isParentInitialized case) (.getParent case))
        enum (when (instance? CtAbstractSwitch parent)
               (enum-switch-declaration ctx parent))
        selector (when (instance? CtAbstractSwitch parent)
                   (.getSelector ^CtAbstractSwitch parent))]
    (if (seq expressions)
      (sequence-node
       (map-indexed
        (fn [index expression]
          (if enum
            (sequence-node [(raw "case ")
                            (enum-case-node ctx enum expression)
                            (raw ":")])
            (let [{:keys [line column]} (spoon/source-location expression)
                  binding (str "__case_" (or line 0) "_" (or column 0)
                               "_" index)
                  value (assignment-value-node
                         ctx selector expression (child-node children expression))
                  primitive? (and selector
                                  (primitive-expression? selector)
                                  (primitive-expression? expression))]
              (if primitive?
                (sequence-node
                 [(raw (str "case var " binding " when " binding " == "))
                  value (raw ":")])
                (sequence-node
                 [(raw (str "case var " binding
                            " when global::System.Object.Equals(" binding ", "))
                  value (raw "):")])))))
        expressions)
       "\n")
      (raw "default:"))))

(def ^:private generic-value-argument-executables
  #{"executable:java.util.Collection#add(java.lang.Object)"
    "executable:java.util.ArrayList#add(java.lang.Object)"
    "executable:java.util.LinkedList#add(java.lang.Object)"
    "executable:java.util.LinkedList#addFirst(java.lang.Object)"
    "executable:java.util.Deque#push(java.lang.Object)"
    "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"
    "executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)"
    "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"
    "executable:java.util.List#add(int,java.lang.Object)"
    "executable:java.util.List#add(java.lang.Object)"
    "executable:java.util.List#set(int,java.lang.Object)"
    "executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
    "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
    "executable:java.util.SortedSet#headSet(java.lang.Object)"})

(defn- supplemental-neutral-invocation-node
  [ctx ^CtInvocation element key target-node arguments]
  (cond (or (and (str/starts-with? key "executable:java.util.function.") (or (str/includes? key "#accept(") (str/includes? key "#apply(") (str/includes? key "#get()"))) (= key "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)") (= key "executable:java.lang.invoke.MethodHandles#lookup()")) (cond (str/starts-with? key "executable:java.util.function.") (csharp/invocation target-node arguments) (= key "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)") (csharp/invocation (csharp/member target-node "Compare") arguments) :else (raw "global::DripSharp.Runtime.JavaMethodHandles.lookup()")) (= key "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)") (csharp/invocation target-node arguments) (str/starts-with? key "executable:java.util.Set#of(") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) (str/starts-with? key "executable:java.util.Map#of(") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.MapOf") (mapv (fn* [p1__477#] (type-node ctx p1__477#)) (.getActualTypeArguments (.getType element)))) (raw "(") (sequence-node arguments ", ") (raw ")")]) (and (str/starts-with? key "executable:java.util.Arrays#copyOf(") (str/ends-with? key ",int)")) (let [component (some-> (first (.getArguments element)) .getType .getComponentType)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.CopyOf") [(type-node ctx component)]) (raw "(") (sequence-node arguments ", ") (raw ")")])) (and (str/starts-with? key "executable:java.util.Arrays#copyOfRange(") (str/ends-with? key ",int,int)")) (let [component (some-> (first (.getArguments element)) .getType .getComponentType)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.CopyOfRange") [(type-node ctx component)]) (raw "(") (sequence-node arguments ", ") (raw ")")])) :else (case key ("executable:java.lang.Character#isLetterOrDigit(int)" "executable:java.lang.Character#isUnicodeIdentifierPart(int)" "executable:java.lang.Character#isUnicodeIdentifierStart(int)") (compat-call (case key "executable:java.lang.Character#isLetterOrDigit(int)" "IsLetterOrDigit" "executable:java.lang.Character#isUnicodeIdentifierPart(int)" "IsUnicodeIdentifierPart" "IsUnicodeIdentifierStart") arguments) "executable:java.lang.Character#isHighSurrogate(char)" (sequence-node [(raw "char.IsHighSurrogate(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Character#isLowSurrogate(char)" (sequence-node [(raw "char.IsLowSurrogate(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Character#toString(int)" (compat-call "CodePointToString" arguments) "executable:java.lang.Character#toTitleCase(int)" (compat-call "ToTitleCase" arguments) "executable:java.lang.Character#isTitleCase(int)" (compat-call "IsTitleCase" arguments) ("executable:java.lang.Character#isUpperCase(char)" "executable:java.lang.Character#isUpperCase(int)") (compat-call "IsUpperCase" arguments) "executable:java.lang.Character#toUpperCase(int)" (compat-call "ToUpperCase" arguments) "executable:java.lang.CharSequence#isEmpty()" (csharp/binary "==" 40 (sequence-node [target-node (raw ".Length")]) (raw "0")) "executable:java.lang.String#formatted(java.lang.Object[])" (compat-call "Formatted" (into [target-node] arguments)) "executable:java.lang.String#isBlank()" (sequence-node [(raw "global::System.String.IsNullOrWhiteSpace(") target-node (raw ")")]) "executable:java.lang.String#repeat(int)" (compat-call "Repeat" (into [target-node] arguments)) "executable:java.lang.String#regionMatches(boolean,int,java.lang.String,int,int)" (compat-call "RegionMatches" (into [target-node] arguments)) "executable:java.lang.String#regionMatches(int,java.lang.String,int,int)" (compat-call "RegionMatches" (into [target-node (raw "false")] arguments)) "executable:java.lang.String#lastIndexOf(int,int)" (compat-call "StringLastIndexOf" (into [target-node] arguments)) "executable:java.lang.String#lastIndexOf(java.lang.String)" (sequence-node [target-node (raw ".LastIndexOf(") (first arguments) (raw ", global::System.StringComparison.Ordinal)")]) "executable:java.util.Deque#getFirst()" (compat-call "DequeGetFirst" [target-node]) "executable:java.util.Deque#descendingIterator()" (csharp/invocation (csharp/member target-node "DescendingIterator") []) ("executable:java.util.List#of()" "executable:java.util.List#of(java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object[])") (let [element-node (collection-element-type-node ctx element)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ListOf") [element-node]) (raw "(") (sequence-node (collection-factory-argument-nodes ctx element arguments element-node) ", ") (raw ")")])) "executable:java.util.List#copyOf(java.util.Collection)" (compat-call "ListCopyOf" arguments) ("executable:java.util.List#addAll(java.util.Collection)" "executable:java.util.ArrayList#addAll(java.util.Collection)") (compat-call "AddAll" (into [target-node] arguments)) "executable:java.util.Locale#getDefault()" (raw "global::System.Globalization.CultureInfo.CurrentCulture") "executable:java.util.Collections#singleton(java.lang.Object)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.SetOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Objects#deepEquals(java.lang.Object,java.lang.Object)" (compat-call "DeepEquals" arguments) "executable:java.util.Map#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) ("executable:java.util.function.Function#apply(java.lang.Object)" "executable:java.util.function.LongFunction#apply(long)" "executable:java.lang.Runnable#run()") (csharp/invocation target-node arguments) "executable:java.util.function.Function#identity()" (raw "value => value") "executable:java.util.function.Function#andThen(java.util.function.Function)" (compat-call "AndThen" (into [target-node] arguments)) "executable:java.util.ResourceBundle#getBundle(java.lang.String,java.util.Locale)" (compat-call "GetResourceBundle" arguments) "executable:java.util.ResourceBundle#getString(java.lang.String)" (compat-call "GetResourceString" (into [target-node] arguments)) "executable:java.util.stream.IntStream#allMatch(java.util.function.IntPredicate)" (compat-call "All" (into [target-node] arguments)) "executable:java.util.stream.IntStream#skip(long)" (compat-call "Skip" (into [target-node] arguments)) "executable:java.lang.Iterable#iterator()" (compat-call "Iterator" [target-node]) ("executable:java.util.stream.IntStream#iterator()" "executable:java.util.stream.LongStream#iterator()") (compat-call "Iterator" [target-node]) "executable:java.util.stream.Collectors#joining(java.lang.CharSequence)" (compat-call "Joining" arguments) "executable:java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)" (let [key-selector-type (.getType (first (.getArguments element))) value-selector-type (.getType (second (.getArguments element))) input-type (first (.getActualTypeArguments key-selector-type)) key-type (second (.getActualTypeArguments key-selector-type)) value-type (second (.getActualTypeArguments value-selector-type))] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ToMap") (mapv (fn* [p1__479#] (type-node ctx p1__479#)) [input-type key-type value-type])) (raw "(") (sequence-node arguments ", ") (raw ")")])) "executable:java.util.regex.Matcher#end()" (sequence-node [target-node (raw ".End()")]) "executable:java.util.regex.Matcher#find()" (sequence-node [target-node (raw ".Find()")]) "executable:java.util.regex.Matcher#find(int)" (sequence-node [target-node (raw ".Find(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.regex.Matcher#group()" (sequence-node [target-node (raw ".Group()")]) "executable:java.util.regex.Matcher#group(int)" (sequence-node [target-node (raw ".Group(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.regex.Matcher#group(java.lang.String)" "executable:java.util.regex.Matcher#groupCount()" "executable:java.util.regex.Matcher#lookingAt()" "executable:java.util.regex.Matcher#region(int,int)" "executable:java.util.regex.Matcher#replaceFirst(java.lang.String)" "executable:java.util.regex.Matcher#start(int)" "executable:java.util.regex.Matcher#end(int)" "executable:java.util.regex.Matcher#toMatchResult()" "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuffer,java.lang.String)" "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuilder,java.lang.String)" "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuffer)" "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuilder)") (let [name (get {"executable:java.util.regex.Matcher#groupCount()" "GroupCount", "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuilder)" "AppendTail", "executable:java.util.regex.Matcher#replaceFirst(java.lang.String)" "ReplaceFirst", "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuffer,java.lang.String)" "AppendReplacement", "executable:java.util.regex.Matcher#group(java.lang.String)" "Group", "executable:java.util.regex.Matcher#start(int)" "Start", "executable:java.util.regex.Matcher#end(int)" "End", "executable:java.util.regex.Matcher#toMatchResult()" "ToMatchResult", "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuilder,java.lang.String)" "AppendReplacement", "executable:java.util.regex.Matcher#lookingAt()" "LookingAt", "executable:java.util.regex.Matcher#region(int,int)" "Region", "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuffer)" "AppendTail"} key)] (sequence-node [target-node (raw (str "." name "(")) (sequence-node arguments ", ") (raw ")")])) "executable:java.util.regex.Matcher#quoteReplacement(java.lang.String)" (compat-call "QuoteReplacement" arguments) ("executable:java.util.regex.MatchResult#end()" "executable:java.util.regex.MatchResult#end(int)" "executable:java.util.regex.MatchResult#group()" "executable:java.util.regex.MatchResult#group(int)" "executable:java.util.regex.MatchResult#groupCount()" "executable:java.util.regex.MatchResult#start()" "executable:java.util.regex.MatchResult#start(int)") (let [name (get {"executable:java.util.regex.MatchResult#end()" "End", "executable:java.util.regex.MatchResult#end(int)" "End", "executable:java.util.regex.MatchResult#group()" "Group", "executable:java.util.regex.MatchResult#group(int)" "Group", "executable:java.util.regex.MatchResult#groupCount()" "GroupCount", "executable:java.util.regex.MatchResult#start()" "Start", "executable:java.util.regex.MatchResult#start(int)" "Start"} key)] (sequence-node [target-node (raw (str "." name "(")) (sequence-node arguments ", ") (raw ")")])) "executable:java.util.regex.Matcher#replaceAll(java.lang.String)" (sequence-node [target-node (raw ".ReplaceAll(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.regex.Matcher#start()" (sequence-node [target-node (raw ".Start()")]) "executable:java.util.regex.Pattern#matches(java.lang.String,java.lang.CharSequence)" (compat-call "StringMatches" [(second arguments) (first arguments)]) "executable:java.util.regex.Pattern#compile(java.lang.String,int)" (compat-call "CompileRegex" arguments) ("executable:java.util.regex.Pattern#pattern()" "executable:java.util.regex.Pattern#toString()") (compat-call "RegexPattern" [target-node]) "executable:java.util.regex.Pattern#flags()" (compat-call "RegexFlags" [target-node]) "executable:java.util.regex.Pattern#quote(java.lang.String)" (compat-call "QuoteRegex" arguments) "executable:java.util.regex.Pattern#split(java.lang.CharSequence,int)" (compat-call "RegexSplit" (into [target-node] arguments)) "executable:java.util.stream.Stream#anyMatch(java.util.function.Predicate)" (compat-call "Any" (into [target-node] arguments)) "executable:java.util.stream.Stream#allMatch(java.util.function.Predicate)" (compat-call "AllValues" (into [target-node] arguments)) "executable:java.util.stream.Stream#noneMatch(java.util.function.Predicate)" (compat-call "NoValues" (into [target-node] arguments)) "executable:java.util.stream.Stream#distinct()" (sequence-node [(raw "global::System.Linq.Enumerable.Distinct(") target-node (raw ")")]) "executable:java.util.stream.Stream#count()" (sequence-node [(raw "global::System.Linq.Enumerable.LongCount(") target-node (raw ")")]) "executable:java.util.stream.Stream#reduce(java.util.function.BinaryOperator)" (compat-call "ReduceOptional" (into [target-node] arguments)) "executable:java.util.stream.Stream#findFirst()" (compat-call "FindFirstOptional" [target-node]) "executable:java.util.stream.StreamSupport#stream(java.util.Spliterator,boolean)" (first arguments) ("executable:java.lang.Iterable#spliterator()" "executable:java.util.Collection#spliterator()" "executable:java.util.ServiceLoader#spliterator()" "executable:java.util.stream.Stream#spliterator()") target-node "executable:java.util.stream.Stream#toList()" (compat-call "ToListValues" [target-node]) "executable:java.util.stream.IntStream#toArray()" (sequence-node [(raw "global::System.Linq.Enumerable.ToArray(") target-node (raw ")")]) "executable:java.util.stream.IntStream#max()" (compat-call "MaxOptionalInt" [target-node]) "executable:java.util.stream.IntStream#forEach(java.util.function.IntConsumer)" (compat-call "ForEach" (into [target-node] arguments)) "executable:java.util.Optional#empty()" (sequence-node [(type-node ctx (.getType element)) (raw ".Empty()")]) "executable:java.util.Optional#of(java.lang.Object)" (sequence-node [(type-node ctx (.getType element)) (raw ".Of(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#ofNullable(java.lang.Object)" (sequence-node [(type-node ctx (.getType element)) (raw ".OfNullable(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#get()" (sequence-node [target-node (raw ".Get()")]) "executable:java.util.Optional#isPresent()" (sequence-node [target-node (raw ".IsPresent()")]) "executable:java.util.Optional#isEmpty()" (sequence-node [target-node (raw ".IsEmpty()")]) "executable:java.util.Optional#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.util.Optional#map(java.util.function.Function)" (sequence-node [target-node (raw ".Map(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#orElse(java.lang.Object)" (sequence-node [target-node (raw ".OrElse(") (if (and (= 1 (count (.getArguments element))) (instance? CtLiteral (first (.getArguments element))) (nil? (.getValue (first (.getArguments element))))) (raw "default!") (sequence-node arguments ", ")) (raw ")")]) "executable:java.util.Optional#orElseGet(java.util.function.Supplier)" (sequence-node [target-node (raw ".OrElseGet(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#ifPresent(java.util.function.Consumer)" (sequence-node [target-node (raw ".IfPresent(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#ifPresentOrElse(java.util.function.Consumer,java.lang.Runnable)" (sequence-node [target-node (raw ".IfPresentOrElse(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.Optional#orElseThrow()" "executable:java.util.Optional#orElseThrow(java.util.function.Supplier)") (sequence-node [target-node (raw ".OrElseThrow(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.OptionalInt#isPresent()" (sequence-node [target-node (raw ".HasValue")]) "executable:java.util.OptionalInt#getAsInt()" (sequence-node [target-node (raw ".Value")]) "executable:java.util.OptionalInt#empty()" (raw "(int?)null") "executable:java.util.OptionalInt#of(int)" (first arguments) "executable:java.util.OptionalInt#orElse(int)" (sequence-node [target-node (raw ".GetValueOrDefault(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.OptionalLong#empty()" (raw "(long?)null") "executable:java.util.OptionalLong#of(long)" (first arguments) "executable:java.util.OptionalLong#ifPresent(java.util.function.LongConsumer)" (compat-call "OptionalLongIfPresent" (into [target-node] arguments)) ("executable:java.lang.Math#acos(double)" "executable:java.lang.Math#abs(double)" "executable:java.lang.Math#abs(float)" "executable:java.lang.Math#abs(int)" "executable:java.lang.Math#abs(long)" "executable:java.lang.Math#atan2(double,double)" "executable:java.lang.Math#cos(double)" "executable:java.lang.Math#floor(double)" "executable:java.lang.Math#log(double)" "executable:java.lang.Math#log10(double)" "executable:java.lang.Math#pow(double,double)" "executable:java.lang.Math#sin(double)" "executable:java.lang.Math#sqrt(double)") (let [name (get {"executable:java.lang.Math#abs(double)" "Abs", "executable:java.lang.Math#pow(double,double)" "Pow", "executable:java.lang.Math#log10(double)" "Log10", "executable:java.lang.Math#floor(double)" "Floor", "executable:java.lang.Math#cos(double)" "Cos", "executable:java.lang.Math#abs(int)" "Abs", "executable:java.lang.Math#abs(long)" "Abs", "executable:java.lang.Math#log(double)" "Log", "executable:java.lang.Math#abs(float)" "Abs", "executable:java.lang.Math#acos(double)" "Acos", "executable:java.lang.Math#atan2(double,double)" "Atan2", "executable:java.lang.Math#sqrt(double)" "Sqrt", "executable:java.lang.Math#sin(double)" "Sin"} key)] (sequence-node [(raw (str "global::System.Math." name "(")) (sequence-node arguments ", ") (raw ")")])) ("executable:java.lang.Math#floorDiv(int,int)" "executable:java.lang.Math#round(double)" "executable:java.lang.Math#round(float)" "executable:java.lang.Math#signum(double)" "executable:java.lang.Math#signum(float)" "executable:java.lang.Math#toDegrees(double)" "executable:java.lang.Math#toRadians(double)") (compat-call (get {"executable:java.lang.Math#floorDiv(int,int)" "FloorDiv", "executable:java.lang.Math#round(double)" "MathRound", "executable:java.lang.Math#round(float)" "MathRoundFloat", "executable:java.lang.Math#signum(double)" "SignumDouble", "executable:java.lang.Math#signum(float)" "SignumFloat", "executable:java.lang.Math#toDegrees(double)" "ToDegrees", "executable:java.lang.Math#toRadians(double)" "ToRadians"} key) arguments) ("executable:java.lang.Math#addExact(long,long)" "executable:java.lang.StrictMath#addExact(long,long)") (compat-call "AddExact" arguments) ("executable:java.lang.Math#addExact(int,int)" "executable:java.lang.StrictMath#addExact(int,int)") (compat-call "AddExactInt" arguments) ("executable:java.lang.Math#negateExact(int)" "executable:java.lang.Math#negateExact(long)" "executable:java.lang.StrictMath#negateExact(int)" "executable:java.lang.StrictMath#negateExact(long)") (compat-call "NegateExact" arguments) ("executable:java.lang.Math#incrementExact(int)" "executable:java.lang.Math#incrementExact(long)" "executable:java.lang.StrictMath#incrementExact(int)" "executable:java.lang.StrictMath#incrementExact(long)") (compat-call "IncrementExact" arguments) ("executable:java.lang.Math#decrementExact(int)" "executable:java.lang.Math#decrementExact(long)" "executable:java.lang.StrictMath#decrementExact(int)" "executable:java.lang.StrictMath#decrementExact(long)") (compat-call "DecrementExact" arguments) "executable:java.lang.StrictMath#toIntExact(long)" (compat-call "ToIntExact" arguments) ("executable:java.lang.StrictMath#abs(double)" "executable:java.lang.StrictMath#abs(long)" "executable:java.lang.StrictMath#acos(double)" "executable:java.lang.StrictMath#asin(double)" "executable:java.lang.StrictMath#atan(double)" "executable:java.lang.StrictMath#atan2(double,double)" "executable:java.lang.StrictMath#cbrt(double)" "executable:java.lang.StrictMath#ceil(double)" "executable:java.lang.StrictMath#copySign(double,double)" "executable:java.lang.StrictMath#cos(double)" "executable:java.lang.StrictMath#exp(double)" "executable:java.lang.StrictMath#floor(double)" "executable:java.lang.StrictMath#log(double)" "executable:java.lang.StrictMath#log10(double)" "executable:java.lang.StrictMath#max(double,double)" "executable:java.lang.StrictMath#max(long,long)" "executable:java.lang.StrictMath#min(double,double)" "executable:java.lang.StrictMath#min(long,long)" "executable:java.lang.StrictMath#pow(double,double)" "executable:java.lang.StrictMath#rint(double)" "executable:java.lang.StrictMath#sin(double)" "executable:java.lang.StrictMath#sqrt(double)" "executable:java.lang.StrictMath#tan(double)") (let [method-name (get {"executable:java.lang.StrictMath#exp(double)" "Exp", "executable:java.lang.StrictMath#floor(double)" "Floor", "executable:java.lang.StrictMath#asin(double)" "Asin", "executable:java.lang.StrictMath#cbrt(double)" "Cbrt", "executable:java.lang.StrictMath#atan2(double,double)" "Atan2", "executable:java.lang.StrictMath#cos(double)" "Cos", "executable:java.lang.StrictMath#sqrt(double)" "Sqrt", "executable:java.lang.StrictMath#max(double,double)" "Max", "executable:java.lang.StrictMath#min(long,long)" "Min", "executable:java.lang.StrictMath#abs(long)" "Abs", "executable:java.lang.StrictMath#log10(double)" "Log10", "executable:java.lang.StrictMath#pow(double,double)" "Pow", "executable:java.lang.StrictMath#copySign(double,double)" "CopySign", "executable:java.lang.StrictMath#ceil(double)" "Ceiling", "executable:java.lang.StrictMath#sin(double)" "Sin", "executable:java.lang.StrictMath#acos(double)" "Acos", "executable:java.lang.StrictMath#min(double,double)" "Min", "executable:java.lang.StrictMath#rint(double)" "Round", "executable:java.lang.StrictMath#max(long,long)" "Max", "executable:java.lang.StrictMath#atan(double)" "Atan", "executable:java.lang.StrictMath#tan(double)" "Tan", "executable:java.lang.StrictMath#log(double)" "Log", "executable:java.lang.StrictMath#abs(double)" "Abs"} key)] (csharp/invocation (raw (str "global::System.Math." method-name)) arguments)) ("executable:java.lang.StrictMath#getExponent(double)" "executable:java.lang.Math#getExponent(double)") (compat-call "GetExponent" arguments) "executable:java.lang.Double#doubleToRawLongBits(double)" (sequence-node [(raw "global::System.BitConverter.DoubleToInt64Bits(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StrictMath#signum(double)" (compat-call "SignumDouble" arguments) ("executable:java.lang.Math#multiplyExact(long,long)" "executable:java.lang.Math#multiplyExact(long,int)" "executable:java.lang.StrictMath#multiplyExact(long,int)" "executable:java.lang.StrictMath#multiplyExact(long,long)") (compat-call "MultiplyExact" arguments) ("executable:java.lang.Math#multiplyExact(int,int)" "executable:java.lang.StrictMath#multiplyExact(int,int)") (compat-call "MultiplyExactInt" arguments) ("executable:java.lang.Math#subtractExact(long,long)" "executable:java.lang.StrictMath#subtractExact(long,long)") (compat-call "SubtractExact" arguments) ("executable:java.text.Bidi#getBaseLevel()" "executable:java.text.Bidi#getRunCount()" "executable:java.text.Bidi#getRunLevel(int)" "executable:java.text.Bidi#getRunLimit(int)" "executable:java.text.Bidi#getRunStart(int)" "executable:java.text.Bidi#isMixed()") (let [name (get {"executable:java.text.Bidi#getBaseLevel()" "GetBaseLevel", "executable:java.text.Bidi#getRunCount()" "GetRunCount", "executable:java.text.Bidi#getRunLevel(int)" "GetRunLevel", "executable:java.text.Bidi#getRunLimit(int)" "GetRunLimit", "executable:java.text.Bidi#getRunStart(int)" "GetRunStart", "executable:java.text.Bidi#isMixed()" "IsMixed"} key)] (sequence-node [target-node (raw (str "." name "(")) (sequence-node arguments ", ") (raw ")")])) "executable:java.text.Bidi#reorderVisually(byte[],int,java.lang.Object[],int,int)" (sequence-node [(raw "global::DripSharp.Runtime.JavaBidi.ReorderVisually(") (sequence-node arguments ", ") (raw ")")]) "executable:java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)" (compat-call "Normalize" arguments) "executable:javax.net.ssl.KeyManagerFactory#getDefaultAlgorithm()" (raw "global::DripSharp.Runtime.JavaKeyManagerFactory.GetDefaultAlgorithm()") "executable:javax.net.ssl.KeyManagerFactory#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaKeyManagerFactory.GetInstance(") (sequence-node arguments ", ") (raw ")")]) ("executable:javax.net.ssl.KeyManagerFactory#init(java.security.KeyStore,char[])" "executable:javax.net.ssl.TrustManagerFactory#init(java.security.KeyStore)" "executable:javax.net.ssl.SSLContext#init(javax.net.ssl.KeyManager[],javax.net.ssl.TrustManager[],java.security.SecureRandom)") (sequence-node [target-node (raw ".Init(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.ssl.KeyManagerFactory#getKeyManagers()" (sequence-node [target-node (raw ".GetKeyManagers()")]) "executable:javax.net.ssl.TrustManagerFactory#getDefaultAlgorithm()" (raw "global::DripSharp.Runtime.JavaTrustManagerFactory.GetDefaultAlgorithm()") "executable:javax.net.ssl.TrustManagerFactory#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaTrustManagerFactory.GetInstance(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.ssl.TrustManagerFactory#getTrustManagers()" (sequence-node [target-node (raw ".GetTrustManagers()")]) "executable:javax.net.ssl.SSLContext#getDefault()" (raw "global::DripSharp.Runtime.JavaSslContext.GetDefault()") "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaSslContext.GetInstance(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.ssl.SSLSocketFactory#getDefault()" (raw "global::DripSharp.Runtime.JavaSocketFactory.Default") "executable:javax.net.ssl.SSLContext#getSocketFactory()" (sequence-node [target-node (raw ".GetSocketFactory()")]) "executable:javax.net.ssl.SSLContext#getServerSocketFactory()" (sequence-node [target-node (raw ".GetServerSocketFactory()")]) "executable:java.lang.String#lines()" (compat-call "StringLines" [target-node]) "executable:java.lang.String#strip()" (sequence-node [target-node (raw ".Trim()")]) "executable:java.util.stream.Stream#skip(long)" (compat-call "DropValues" (into [target-node] arguments)) "executable:java.util.Objects#requireNonNullElseGet(java.lang.Object,java.util.function.Supplier)" (compat-call "RequireNonNullElseGet" arguments) "executable:java.util.Random#nextLong()" (sequence-node [target-node (raw ".NextLong()")]) "executable:java.util.concurrent.CompletableFuture#complete(java.lang.Object)" (sequence-node [target-node (raw ".Complete(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.CompletableFuture#completeExceptionally(java.lang.Throwable)" (sequence-node [target-node (raw ".CompleteExceptionally(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.Future#get()" (sequence-node [target-node (raw ".Get()")]) "executable:java.lang.StringBuilder#append(char[])" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StringBuilder#append(char[],int,int)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StringBuilder#append(java.lang.CharSequence)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Long#byteValue()" (sequence-node [(raw "unchecked((sbyte)(") target-node (raw "))")]) "executable:java.lang.Long#shortValue()" (sequence-node [(raw "unchecked((short)(") target-node (raw "))")]) "executable:java.lang.Long#intValue()" (sequence-node [(raw "unchecked((int)(") target-node (raw "))")]) "executable:java.lang.Float#doubleValue()" (sequence-node [(raw "(double)(") target-node (raw ")")]) "executable:java.net.URI#getAuthority()" (sequence-node [(compat-call "UriAuthority" [target-node]) (raw "!")]) "executable:java.net.URI#getFragment()" (sequence-node [(compat-call "UriFragment" [target-node]) (raw "!")]) "executable:java.net.URI#getQuery()" (sequence-node [(compat-call "UriQuery" [target-node]) (raw "!")]) "executable:java.net.URI#getSchemeSpecificPart()" (sequence-node [(compat-call "UriSchemeSpecificPart" [target-node]) (raw "!")]) "executable:java.net.URI#getRawAuthority()" (sequence-node [(compat-call "UriRawAuthority" [target-node]) (raw "!")]) "executable:java.net.URI#getRawSchemeSpecificPart()" (sequence-node [(compat-call "UriRawSchemeSpecificPart" [target-node]) (raw "!")]) "executable:java.net.URI#getRawUserInfo()" (sequence-node [(compat-call "UriRawUserInfo" [target-node]) (raw "!")]) "executable:java.net.URI#isAbsolute()" (csharp/member target-node "IsAbsoluteUri") "executable:java.net.URI#isOpaque()" (compat-call "UriIsOpaque" [target-node]) "executable:java.net.URI#normalize()" (compat-call "NormalizeUri" [target-node]) "executable:java.net.URI#relativize(java.net.URI)" (compat-call "RelativizeUri" (into [target-node] arguments)) ("executable:java.net.URI#resolve(java.lang.String)" "executable:java.net.URI#resolve(java.net.URI)") (compat-call "ResolveUri" (into [target-node] arguments)) "executable:java.net.URI#toASCIIString()" (csharp/member target-node "AbsoluteUri") "executable:java.net.URI#toURL()" target-node "executable:java.net.URI#compareTo(java.net.URI)" (compat-call "CompareUri" (into [target-node] arguments)) "executable:java.net.URL#toURI()" target-node "executable:java.net.URL#getProtocol()" (compat-call "UriScheme" [target-node]) "executable:java.net.URLConnection#connect()" (csharp/invocation (csharp/member target-node "Connect") arguments) "executable:java.net.URLConnection#setUseCaches(boolean)" (csharp/invocation (csharp/member target-node "SetUseCaches") arguments) "executable:java.net.URLConnection#getInputStream()" (csharp/invocation (csharp/member target-node "GetInputStream") arguments) "executable:java.net.URLConnection#getURL()" (csharp/invocation (csharp/member target-node "GetURL") arguments) "executable:java.lang.invoke.VarHandle#storeStoreFence()" (csharp/invocation (raw "global::System.Threading.Thread.MemoryBarrier") []) "executable:java.nio.file.Path#toString()" (sequence-node [target-node (raw ".ToString()!")]) "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])" (compat-call "PathOf" arguments) "executable:java.nio.file.Path#of(java.net.URI)" (compat-call "PathOfUri" arguments) "executable:java.nio.file.Path#getRoot()" (compat-call "PathRoot" [target-node]) "executable:java.nio.file.Path#isAbsolute()" (compat-call "PathIsAbsolute" [target-node]) "executable:java.nio.file.Path#normalize()" (compat-call "NormalizePath" [target-node]) "executable:java.nio.file.Path#startsWith(java.nio.file.Path)" (compat-call "PathStartsWith" (into [target-node] arguments)) ("executable:java.nio.file.Path#endsWith(java.lang.String)" "executable:java.nio.file.Path#endsWith(java.nio.file.Path)") (compat-call "PathEndsWith" (into [target-node] arguments)) "executable:java.nio.file.Path#getFileName()" (csharp/invocation (raw "global::System.IO.Path.GetFileName") [target-node]) "executable:java.nio.file.Path#getParent()" (csharp/invocation (raw "global::System.IO.Path.GetDirectoryName") [target-node]) "executable:java.nio.file.Path#resolveSibling(java.lang.String)" (compat-call "PathResolveSibling" (into [target-node] arguments)) "executable:java.nio.file.Path#getNameCount()" (compat-call "PathNameCount" [target-node]) "executable:java.nio.file.Path#getName(int)" (compat-call "PathName" (into [target-node] arguments)) "executable:java.nio.file.Path#relativize(java.nio.file.Path)" (compat-call "PathRelativize" (into [target-node] arguments)) ("executable:java.nio.file.Path#resolve(java.lang.String)" "executable:java.nio.file.Path#resolve(java.nio.file.Path)") (compat-call "PathResolve" (into [target-node] arguments)) "executable:java.nio.file.Path#toAbsolutePath()" (csharp/invocation (raw "global::System.IO.Path.GetFullPath") [target-node]) "executable:java.nio.file.Path#toRealPath(java.nio.file.LinkOption[])" (compat-call "RealPath" [target-node]) "executable:java.nio.file.Path#toUri()" (compat-call "PathToUri" [target-node]) "executable:java.nio.file.Files#exists(java.nio.file.Path,java.nio.file.LinkOption[])" (compat-call "Exists" [(first arguments)]) "executable:java.nio.file.Files#createDirectories(java.nio.file.Path,java.nio.file.attribute.FileAttribute[])" (compat-call "CreateDirectories" [(first arguments)]) "executable:java.nio.file.Files#newOutputStream(java.nio.file.Path,java.nio.file.OpenOption[])" (compat-call "NewOutputStream" arguments) "executable:java.nio.file.Files#deleteIfExists(java.nio.file.Path)" (compat-call "DeleteIfExists" arguments) ("executable:java.nio.file.Files#readString(java.nio.file.Path)" "executable:java.nio.file.Files#readString(java.nio.file.Path,java.nio.charset.Charset)") (compat-call "ReadString" arguments) ("executable:java.nio.file.Files#copy(java.io.InputStream,java.nio.file.Path,java.nio.file.CopyOption[])" "executable:java.nio.file.Files#copy(java.nio.file.Path,java.io.OutputStream)" "executable:java.nio.file.Files#copy(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])") (compat-call "Copy" arguments) "executable:java.nio.file.Files#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])" (compat-call "Move" arguments) "executable:java.nio.file.Files#writeString(java.nio.file.Path,java.lang.CharSequence,java.nio.file.OpenOption[])" (compat-call "WriteString" arguments) "executable:java.nio.file.Files#isSymbolicLink(java.nio.file.Path)" (compat-call "IsSymbolicLink" arguments) "executable:java.nio.file.Files#newDirectoryStream(java.nio.file.Path)" (compat-call "NewDirectoryStream" arguments) "executable:java.nio.file.Files#isDirectory(java.nio.file.Path,java.nio.file.LinkOption[])" (compat-call "IsDirectory" [(first arguments)]) "executable:java.nio.file.Files#isRegularFile(java.nio.file.Path,java.nio.file.LinkOption[])" (compat-call "PathIsRegularFile" [(first arguments)]) "executable:java.nio.file.Files#list(java.nio.file.Path)" (compat-call "List" arguments) "executable:java.nio.file.FileSystem#getPath(java.lang.String,java.lang.String[])" (csharp/invocation (csharp/member target-node "GetPath") arguments) "executable:java.nio.file.FileSystem#provider()" (csharp/invocation (csharp/member target-node "Provider") arguments) "executable:java.nio.file.FileSystem#isOpen()" (csharp/invocation (csharp/member target-node "IsOpen") arguments) "executable:java.nio.file.FileSystem#isReadOnly()" (csharp/invocation (csharp/member target-node "IsReadOnly") arguments) "executable:java.nio.file.FileSystem#getSeparator()" (csharp/invocation (csharp/member target-node "GetSeparator") arguments) "executable:java.nio.file.FileSystem#getFileStores()" (csharp/invocation (csharp/member target-node "GetFileStores") arguments) "executable:java.nio.file.FileSystem#supportedFileAttributeViews()" (csharp/invocation (csharp/member target-node "SupportedFileAttributeViews") arguments) "executable:java.nio.file.FileSystem#getPathMatcher(java.lang.String)" (csharp/invocation (csharp/member target-node "GetPathMatcher") arguments) "executable:java.nio.file.FileSystem#getUserPrincipalLookupService()" (csharp/invocation (csharp/member target-node "GetUserPrincipalLookupService") arguments) "executable:java.nio.file.FileSystem#newWatchService()" (csharp/invocation (csharp/member target-node "NewWatchService") arguments) "executable:java.nio.file.FileSystem#close()" (csharp/invocation (csharp/member target-node "Close") arguments) "executable:java.nio.file.FileSystems#getDefault()" (csharp/invocation (csharp/member target-node "GetDefault") arguments) "executable:java.nio.file.FileSystems#getFileSystem(java.net.URI)" (csharp/invocation (csharp/member target-node "GetFileSystem") arguments) "executable:java.nio.file.FileSystems#newFileSystem(java.net.URI,java.util.Map)" (csharp/invocation (csharp/member target-node "NewFileSystem") arguments) "executable:java.util.EnumSet#noneOf(java.lang.Class)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetNoneOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.EnumSet#allOf(java.lang.Class)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetAllOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.EnumSet#copyOf(java.util.EnumSet)" "executable:java.util.EnumSet#copyOf(java.util.Collection)") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetCopyOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum[])") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.SetOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.net.http.HttpRequest#newBuilder()" "executable:java.net.http.HttpRequest#newBuilder(java.net.URI)") (csharp/invocation (csharp/member target-node "NewBuilder") arguments) "executable:java.net.http.HttpRequest#uri()" (csharp/invocation (csharp/member target-node "Uri") arguments) "executable:java.net.http.HttpRequest#headers()" (csharp/invocation (csharp/member target-node "Headers") arguments) "executable:java.net.http.HttpRequest#expectContinue()" (csharp/invocation (csharp/member target-node "ExpectContinue") arguments) "executable:java.net.http.HttpRequest#method()" (csharp/invocation (csharp/member target-node "Method") arguments) "executable:java.net.http.HttpRequest#timeout()" (csharp/invocation (csharp/member target-node "Timeout") arguments) "executable:java.net.http.HttpRequest#version()" (csharp/invocation (csharp/member target-node "Version") arguments) "executable:java.net.http.HttpRequest#bodyPublisher()" (csharp/invocation (csharp/member target-node "BodyPublisher") arguments) "executable:java.net.http.HttpRequest$Builder#uri(java.net.URI)" (csharp/invocation (csharp/member target-node "Uri") arguments) "executable:java.net.http.HttpRequest$Builder#timeout(java.time.Duration)" (csharp/invocation (csharp/member target-node "Timeout") arguments) "executable:java.net.http.HttpRequest$Builder#version(java.net.http.HttpClient$Version)" (csharp/invocation (csharp/member target-node "Version") arguments) "executable:java.net.http.HttpRequest$Builder#header(java.lang.String,java.lang.String)" (csharp/invocation (csharp/member target-node "Header") arguments) "executable:java.net.http.HttpRequest$Builder#setHeader(java.lang.String,java.lang.String)" (csharp/invocation (csharp/member target-node "SetHeader") arguments) "executable:java.net.http.HttpRequest$Builder#expectContinue(boolean)" (csharp/invocation (csharp/member target-node "ExpectContinue") arguments) "executable:java.net.http.HttpRequest$Builder#method(java.lang.String,java.net.http.HttpRequest$BodyPublisher)" (csharp/invocation (csharp/member target-node "Method") arguments) "executable:java.net.http.HttpRequest$Builder#GET()" (csharp/invocation (csharp/member target-node "GET") arguments) "executable:java.net.http.HttpRequest$Builder#DELETE()" (csharp/invocation (csharp/member target-node "DELETE") arguments) "executable:java.net.http.HttpRequest$Builder#build()" (csharp/invocation (csharp/member target-node "Build") arguments) "executable:java.net.http.HttpRequest$BodyPublishers#noBody()" (csharp/invocation (csharp/member target-node "NoBody") arguments) "executable:java.net.http.HttpResponse#statusCode()" (csharp/invocation (csharp/member target-node "StatusCode") arguments) "executable:java.net.http.HttpResponse#body()" (csharp/invocation (csharp/member target-node "Body") arguments) "executable:java.net.http.HttpResponse#request()" (csharp/invocation (csharp/member target-node "Request") arguments) "executable:java.net.http.HttpResponse#previousResponse()" (csharp/invocation (csharp/member target-node "PreviousResponse") arguments) "executable:java.net.http.HttpResponse#uri()" (csharp/invocation (csharp/member target-node "Uri") arguments) "executable:java.net.http.HttpResponse#headers()" (csharp/invocation (csharp/member target-node "Headers") arguments) "executable:java.net.http.HttpResponse#version()" (csharp/invocation (csharp/member target-node "Version") arguments) "executable:java.net.http.HttpHeaders#firstValue(java.lang.String)" (csharp/invocation (csharp/member target-node "FirstValue") arguments) "executable:java.net.http.HttpHeaders#map()" (csharp/invocation (csharp/member target-node "Map") arguments) "executable:java.net.http.HttpResponse$BodyHandlers#ofInputStream()" (csharp/invocation (csharp/member target-node "OfInputStream") arguments) "executable:java.net.http.HttpResponse$BodyHandlers#ofByteArray()" (csharp/invocation (csharp/member target-node "OfByteArray") arguments) "executable:java.util.zip.ZipInputStream#getNextEntry()" (csharp/invocation (csharp/member target-node "GetNextEntry") arguments) "executable:java.util.zip.ZipInputStream#readAllBytes()" (csharp/invocation (csharp/member target-node "ReadAllBytes") arguments) "executable:java.util.zip.ZipInputStream#closeEntry()" (csharp/invocation (csharp/member target-node "CloseEntry") arguments) "executable:java.util.zip.ZipOutputStream#putNextEntry(java.util.zip.ZipEntry)" (csharp/invocation (csharp/member target-node "PutNextEntry") arguments) "executable:java.util.zip.ZipOutputStream#closeEntry()" (csharp/invocation (csharp/member target-node "CloseEntry") arguments) "executable:java.util.zip.ZipEntry#getName()" (csharp/invocation (csharp/member target-node "GetName") arguments) "executable:java.util.zip.ZipEntry#isDirectory()" (csharp/invocation (csharp/member target-node "IsDirectory") arguments) "executable:java.util.zip.ZipEntry#setTimeLocal(java.time.LocalDateTime)" (csharp/invocation (csharp/member target-node "SetTimeLocal") arguments) "executable:java.util.Arrays#stream(java.lang.Object[])" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.StreamOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) nil)))

(defn- reflection-invocation-node
  [key target-node arguments]
  (case key
    "executable:java.lang.reflect.Field#get(java.lang.Object)"
    (sequence-node [target-node (raw ".GetValue(")
                    (sequence-node arguments ", ") (raw ")")])

    ("executable:java.lang.reflect.Field#setAccessible(boolean)"
     "executable:java.lang.reflect.Method#setAccessible(boolean)")
    (compat-call "SetAccessible" (into [target-node] arguments))

    nil))

(defn- decimal-invocation-node
  [key target-node arguments]
  (case key
    "executable:java.math.BigDecimal#valueOf(double)"
    (compat-call "BigDecimalValueOf" arguments)

    "executable:java.math.BigDecimal#multiply(java.math.BigDecimal)"
    (compat-call "BigDecimalMultiply" (into [target-node] arguments))

    "executable:java.math.BigDecimal#divide(java.math.BigDecimal,int,java.math.RoundingMode)"
    (compat-call "BigDecimalDivide" (into [target-node] arguments))

    "executable:java.math.BigDecimal#setScale(int,java.math.RoundingMode)"
    (compat-call "BigDecimalSetScale" (into [target-node] arguments))

    "executable:java.math.BigDecimal#intValue()"
    (compat-call "BigDecimalIntValue" [target-node])

    "executable:java.math.BigDecimal#stripTrailingZeros()"
    (compat-call "BigDecimalStripTrailingZeros" [target-node])

    "executable:java.math.BigDecimal#toPlainString()"
    (compat-call "BigDecimalToPlainString" [target-node])

    "executable:java.math.BigDecimal#toString()"
    (compat-call "BigDecimalToString" [target-node])

    nil))

(defn- security-invocation-node
  [key target-node arguments]
  (case key
    "executable:java.security.KeyStore#getDefaultType()"
    (raw "global::DripSharp.Runtime.JavaKeyStore.GetDefaultType()")

    "executable:java.security.KeyStore#getInstance(java.lang.String)"
    (sequence-node [(raw "global::DripSharp.Runtime.JavaKeyStore.GetInstance(")
                    (sequence-node arguments ", ") (raw ")")])

    ("executable:java.security.KeyStore#load(java.io.InputStream,char[])"
     "executable:java.security.KeyStore#load(java.security.KeyStore$LoadStoreParameter)")
    (sequence-node [target-node (raw ".Load(")
                    (sequence-node arguments ", ") (raw ")")])

    "executable:java.security.KeyStore#setCertificateEntry(java.lang.String,java.security.cert.Certificate)"
    (sequence-node [target-node (raw ".SetCertificateEntry(")
                    (sequence-node arguments ", ") (raw ")")])

    "executable:java.security.KeyStore#size()"
    (sequence-node [target-node (raw ".Size()")])

    "executable:java.security.KeyStore#aliases()"
    (sequence-node [target-node (raw ".Aliases()")])

    "executable:java.security.KeyStore#containsAlias(java.lang.String)"
    (sequence-node [target-node (raw ".ContainsAlias(")
                    (sequence-node arguments ", ") (raw ")")])

    "executable:java.security.KeyStore#getCertificate(java.lang.String)"
    (sequence-node [target-node (raw ".GetCertificate(")
                    (sequence-node arguments ", ") (raw ")")])

    "executable:java.security.KeyStore#getKey(java.lang.String,char[])"
    (sequence-node [target-node (raw ".GetKey(")
                    (sequence-node arguments ", ") (raw ")")])

    "executable:java.security.cert.CertificateFactory#getInstance(java.lang.String)"
    (sequence-node
     [(raw "global::DripSharp.Runtime.JavaCertificateFactory.GetInstance(")
      (sequence-node arguments ", ") (raw ")")])

    "executable:java.security.cert.CertificateFactory#generateCertificates(java.io.InputStream)"
    (sequence-node [target-node (raw ".GenerateCertificates(")
                    (sequence-node arguments ", ") (raw ")")])

    nil))

(defn- inherited-runtime-object-invocation-node
  [occurrence target-node arguments]
  (when (= :inherited-runtime-member (:resolution occurrence))
    (cond
      (str/ends-with? (:key occurrence) "#equals(java.lang.Object)")
      (compat-call "Equals" (into [target-node] arguments))

      (str/ends-with? (:key occurrence) "#hashCode()")
      (sequence-node [target-node (raw ".GetHashCode()")])

      (str/ends-with? (:key occurrence) "#toString()")
      (sequence-node [target-node (raw ".ToString()")])

      :else nil)))

(defn- executable-parameter-types
  [declaration executable-reference]
  (if (instance? CtExecutable declaration)
    (mapv #(.getType ^CtParameter %)
          (.getParameters ^CtExecutable declaration))
    (vec (.getParameters executable-reference))))

(defn- constructor-call-parameter-types
  [declaration ^CtConstructorCall call]
  (let [parameter-types
        (executable-parameter-types declaration (.getExecutable call))
        owner
        (when (instance? CtConstructor declaration)
          (.getDeclaringType ^CtConstructor declaration))
        formals
        (when owner
          (vec (.getFormalCtTypeParameters ^CtType owner)))
        actuals
        (vec (.getActualTypeArguments (.getType call)))
        substitutions
        (when (= (count formals) (count actuals))
          (zipmap (map #(.getSimpleName ^CtTypeParameter %) formals)
                  actuals))]
    (mapv
     (fn [^CtTypeReference reference]
       (if (instance? CtTypeParameterReference reference)
         (get substitutions (.getSimpleName reference) reference)
         reference))
     parameter-types)))

(defn- invocation-target-node
  [ctx children ^CtExpression target declaration executable-reference]
  (let [static-declaration?
        (and (instance? CtMethod declaration)
             (.hasModifier ^CtMethod declaration ModifierKind/STATIC))
        node
        (cond
          static-declaration?
          (type-node
           ctx
           (.getDeclaringType executable-reference))

          (instance? CtTypeAccess target)
          (type-node ctx (.getAccessedType ^CtTypeAccess target))

          :else
          (child-node children target))
        node-text (:text (csharp/render node))
        redundant-static-cast
        (or
         (re-matches
          #"^\((global::[^)]+)\)\1$"
          node-text)
         (re-matches
          #"^\(\((global::[^)]+)\)\1\)$"
          node-text))
        node
        (if redundant-static-cast
          (raw (second redundant-static-cast))
          node)]
    (if (and (not redundant-static-cast)
             (not static-declaration?)
             (not (instance? CtTypeAccess target))
             (seq (.getTypeCasts target)))
      (sequence-node [(raw "(") node (raw ")")])
      node)))

(defn- normalize-redundant-static-casts [node]
  (let [text (:text (csharp/render node))
        normalized
        (str/replace
         text
         #"\(\((global::[^)]+)\)\1\)"
         "$1")]
    (if (= text normalized) node (raw normalized))))

(defn- shared-invocation-adaptation-node
  [occurrence target target-node arguments]
  (case (:key occurrence)
    "executable:java.lang.Object#toString()"
    (when-not (instance? CtSuperAccess target)
      (compat-call "StringValueOf" [target-node]))

    "executable:java.net.URI#create(java.lang.String)"
    (compat-call "CreateUri" arguments)

    "executable:java.net.URI#toString()"
    (compat-call "UriToString" [target-node])

    "executable:java.lang.Throwable#getMessage()"
    (if (instance? CtSuperAccess target)
      (sequence-node [target-node (raw ".Message")])
      (compat-call "ExceptionMessage" [target-node]))

    "executable:java.lang.StrictMath#sin(double)"
    (sequence-node [(raw "global::DripSharp.Runtime.JavaStrictMath.Sin(")
                    (sequence-node arguments ", ")
                    (raw ")")])

    "executable:java.lang.StrictMath#cos(double)"
    (sequence-node [(raw "global::DripSharp.Runtime.JavaStrictMath.Cos(")
                    (sequence-node arguments ", ")
                    (raw ")")])

    "executable:java.lang.StrictMath#atan2(double,double)"
    (sequence-node [(raw "global::DripSharp.Runtime.JavaStrictMath.Atan2(")
                    (sequence-node arguments ", ")
                    (raw ")")])

    "executable:java.lang.StrictMath#log10(double)"
    (sequence-node [(raw "global::DripSharp.Runtime.JavaStrictMath.Log10(")
                    (sequence-node arguments ", ")
                    (raw ")")])

    "executable:java.lang.StrictMath#pow(double,double)"
    (sequence-node [(raw "global::DripSharp.Runtime.JavaStrictMath.Pow(")
                    (sequence-node arguments ", ")
                    (raw ")")])

    nil))

(defn- body-rules [ctx-holder]
  (java/structural-rules
   [{:id :java-library.expression/invocation
     :class CtInvocation
     :emit
     (fn [{:keys [context ^CtInvocation element children]}]
       (let [target (.getTarget element)
             occurrence (invocation-occurrence context element)
             declaration (:declaration occurrence)
             target-node
             (when target
               (invocation-target-node
                @ctx-holder children target declaration
                (.getExecutable element)))
             parameter-types
             (executable-parameter-types declaration (.getExecutable element))
             arguments
             (mapv
              (fn [index ^CtExpression argument]
                (let [node (child-node children argument)
                      expected
                      (when (seq parameter-types)
                        (nth parameter-types
                             (min index (dec (count parameter-types)))))]
                  (argument-value-node
                   @ctx-holder argument expected node
                   (contains? generic-value-argument-executables
                              (:key occurrence)))))
              (range)
              (.getArguments element))
             default-interface?
             (and target
                  (= :project (:origin occurrence))
                  (instance? CtMethod declaration)
                  (interface-type?
                   (.getDeclaringType ^CtMethod declaration))
                  (some? (.getBody ^CtMethod declaration)))
             default-target-node
             (if default-interface?
               (sequence-node
                [(raw "((")
                 (raw (project-type-base
                       @ctx-holder
                       (.getDeclaringType ^CtMethod declaration)))
                 (raw ")") target-node (raw ")")])
               target-node)
             destination-adaptation
             (or
              (shared-invocation-adaptation-node
               occurrence target target-node arguments)
              (when-let [adaptation
                         (get (:destination-invocation-adaptations @ctx-holder)
                              (:key occurrence))]
                (adaptation target-node arguments))
              (when-let [adapt
                         (:destination-invocation-adapter @ctx-holder)]
                (adapt
                 {:context context
                  :destination-context @ctx-holder
                  :element element
                  :children children
                  :occurrence occurrence
                  :target target
                  :target-node target-node
                  :arguments arguments}))
              (when
               (= "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)"
                  (:key occurrence))
                (compat-call "ComparatorCompare"
                             (into [target-node] arguments)))
              (declarative-shared-invocation-node
               @ctx-holder element occurrence target-node arguments))
             raw-node
             (if (= :constructor (:kind occurrence))
               (raw "")
               (or destination-adaptation (inherited-runtime-object-invocation-node occurrence target-node arguments) (reflection-invocation-node (:key occurrence) target-node arguments) (decimal-invocation-node (:key occurrence) target-node arguments) (security-invocation-node (:key occurrence) target-node arguments) (supplemental-neutral-invocation-node (clojure.core/deref ctx-holder) element (:key occurrence) target-node arguments) (case (:key occurrence) ("executable:java.lang.ref.SoftReference#get()" "executable:java.lang.ref.WeakReference#get()" "executable:java.lang.ref.Reference#get()") (sequence-node [target-node (raw ".Get()")]) "executable:java.lang.ref.Reference#clear()" (sequence-node [target-node (raw ".Clear()")]) "executable:java.io.ByteArrayOutputStream#write(byte[],int,int)" (compat-call "OutputStreamWrite" (into [target-node] arguments)) "executable:java.io.ByteArrayOutputStream#close()" (sequence-node [target-node (raw ".Dispose()")]) ("executable:java.io.FilterOutputStream#write(byte[])" "executable:java.io.FilterOutputStream#write(byte[],int,int)" "executable:java.io.FilterOutputStream#write(int)" "executable:java.util.zip.DeflaterOutputStream#write(byte[],int,int)") (sequence-node [target-node (raw ".Write(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.zip.DeflaterOutputStream#close()" (sequence-node [target-node (raw ".Dispose()")]) "executable:java.util.zip.Deflater#end()" (sequence-node [target-node (raw ".End()")]) "executable:java.util.zip.Inflater#finished()" (sequence-node [target-node (raw ".Finished()")]) "executable:java.util.zip.Inflater#needsInput()" (sequence-node [target-node (raw ".NeedsInput()")]) "executable:java.util.zip.Inflater#setInput(byte[],int,int)" (sequence-node [target-node (raw ".SetInput(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.zip.Inflater#inflate(byte[])" (sequence-node [target-node (raw ".Inflate(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.zip.Inflater#end()" (sequence-node [target-node (raw ".End()")]) "executable:java.io.FilterInputStream#close()" (sequence-node [target-node (raw ".Dispose()")]) "executable:java.io.ByteArrayOutputStream#reset()" (compat-call "ResetMemoryStream" [target-node]) "executable:java.io.ByteArrayOutputStream#toString(java.lang.String)" (compat-call "MemoryStreamToString" (into [target-node] arguments)) "executable:java.io.BufferedReader#readLine()" (sequence-node [target-node (raw ".ReadLine()")]) "executable:java.io.BufferedReader#ready()" (compat-call "ReaderReady" [target-node]) "executable:java.io.BufferedWriter#newLine()" (sequence-node [target-node (raw ".WriteLine()")]) "executable:java.io.BufferedWriter#write(java.lang.String)" (sequence-node [target-node (raw ".Write(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.io.BufferedWriter#write(int)" "executable:java.io.Writer#write(int)") (compat-call "WriterWriteCharCode" (into [target-node] arguments)) "executable:java.io.BufferedWriter#flush()" (sequence-node [target-node (raw ".Flush()")]) "executable:java.io.File#canRead()" (compat-call "FileCanRead" [target-node]) "executable:java.io.File#getName()" (sequence-node [target-node (raw ".Name")]) "executable:java.io.File#getPath()" (sequence-node [target-node (raw ".ToString()")]) "executable:java.io.File#equals(java.lang.Object)" (compat-call "FileEquals" (into [target-node] arguments)) "executable:java.io.File#isHidden()" (compat-call "FileIsHidden" [target-node]) "executable:java.io.File#canWrite()" (compat-call "FileCanWrite" [target-node]) "executable:java.io.File#lastModified()" (compat-call "FileLastModified" [target-node]) "executable:java.io.File#listFiles()" (compat-call "FileListFiles" [target-node]) "executable:java.io.File#isFile()" (compat-call "FileIsFile" [target-node]) "executable:java.io.File#toURI()" (compat-call "FileToUri" [target-node]) ("executable:java.io.InputStream#mark(int)" "executable:java.io.BufferedInputStream#mark(int)") (compat-call "InputStreamMark" (into [target-node] arguments)) ("executable:java.io.InputStream#markSupported()" "executable:java.io.BufferedInputStream#markSupported()") (compat-call "InputStreamMarkSupported" [target-node]) ("executable:java.io.InputStream#reset()" "executable:java.io.ByteArrayInputStream#reset()" "executable:java.io.BufferedInputStream#reset()") (compat-call "InputStreamReset" [target-node]) "executable:java.io.InputStream#skip(long)" (compat-call "InputStreamSkip" (into [target-node] arguments)) "executable:java.io.LineNumberReader#readLine()" (sequence-node [target-node (raw ".ReadLine()")]) "executable:java.io.Reader#read(char[],int,int)" (compat-call "ReaderRead" (into [target-node] arguments)) "executable:java.io.StringWriter#toString()" (sequence-node [target-node (raw ".ToString()")]) ("executable:java.io.Writer#write(java.lang.String)" "executable:java.io.StringWriter#write(java.lang.String)") (sequence-node [target-node (raw ".Write(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.Writer#write(char[])" (sequence-node [target-node (raw ".Write(") (first arguments) (raw ")")]) ("executable:java.io.Writer#append(java.lang.CharSequence)" "executable:java.io.Writer#append(char)") (compat-call "WriterAppend" (into [target-node] arguments)) "executable:java.io.Writer#flush()" (sequence-node [target-node (raw ".Flush()")]) "executable:java.io.Writer#close()" (sequence-node [target-node (raw ".Dispose()")]) "executable:java.io.PrintWriter#close()" (sequence-node [target-node (raw ".Dispose()")]) "executable:java.io.PrintWriter#println(java.lang.String)" (sequence-node [target-node (raw ".WriteLine(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.PrintWriter#flush()" (sequence-node [target-node (raw ".Flush()")]) "executable:java.io.PrintStream#print(java.lang.String)" (sequence-node [target-node (raw ".Write(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.PrintStream#println()" (sequence-node [target-node (raw ".WriteLine()")]) "executable:java.io.PrintStream#flush()" (sequence-node [target-node (raw ".Flush()")]) ("executable:java.lang.Process#isAlive()" "executable:java.lang.Process#getInputStream()" "executable:java.lang.Process#getOutputStream()" "executable:java.lang.Process#waitFor(long,java.util.concurrent.TimeUnit)" "executable:java.lang.Process#destroyForcibly()" "executable:java.lang.ProcessBuilder#directory(java.io.File)" "executable:java.lang.ProcessBuilder#redirectError(java.lang.ProcessBuilder$Redirect)" "executable:java.lang.ProcessBuilder#start()") (sequence-node [target-node (raw ".") (raw (pascal (.getSimpleName (.getExecutable element)))) (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Boolean#parseBoolean(java.lang.String)" (compat-call "ParseBoolean" arguments) "executable:java.lang.Boolean#getBoolean(java.lang.String)" (compat-call "GetBoolean" arguments) "executable:java.lang.Boolean#toString()" (compat-call "StringValueOf" [target-node]) "executable:java.lang.Byte#toUnsignedInt(byte)" (compat-call "ToUnsignedInt" arguments) "executable:java.lang.Byte#toUnsignedLong(byte)" (compat-call "ToUnsignedLong" arguments) "executable:java.lang.Character#digit(char,int)" (compat-call "CharacterDigit" arguments) "executable:java.lang.Character#charCount(int)" (compat-call "CharacterCharCount" arguments) "executable:java.lang.Character#getName(int)" (compat-call "CharacterName" arguments) "executable:java.lang.Character#isDefined(int)" (compat-call "CharacterIsDefined" arguments) ("executable:java.lang.Character#getType(int)" "executable:java.lang.Character#getType(char)") (compat-call "CharacterType" arguments) ("executable:java.lang.Character#isDigit(char)" "executable:java.lang.Character#isDigit(int)") (compat-call "IsDigit" arguments) "executable:java.lang.Character#isBmpCodePoint(int)" (compat-call "IsBmpCodePoint" arguments) "executable:java.lang.Character#isValidCodePoint(int)" (compat-call "IsValidCodePoint" arguments) "executable:java.lang.Character#isSurrogatePair(char,char)" (sequence-node [(raw "char.IsSurrogatePair(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.lang.Character#isMirrored(char)" "executable:java.lang.Character#isMirrored(int)") (sequence-node [(raw "global::DripSharp.Runtime.JavaBidi.IsMirrored(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Character#isWhitespace(char)" (compat-call "IsWhitespace" arguments) "executable:java.lang.Character#toString(char)" (compat-call "CodePointToString" arguments) "executable:java.lang.Character#toString()" (compat-call "StringValueOf" [target-node]) "executable:java.lang.Class#asSubclass(java.lang.Class)" (compat-call "ClassAsSubclass" (into [target-node] arguments)) "executable:java.lang.Class#cast(java.lang.Object)" (let [cast-type (or (some-> target .getType .getActualTypeArguments first) (.getType element))] (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.ClassCast<") (type-node (clojure.core/deref ctx-holder) cast-type) (raw ">(") target-node (raw ", ") (sequence-node arguments ", ") (raw ")")])) "executable:java.lang.Double#compare(double,double)" (compat-call "CompareDouble" arguments) "executable:java.lang.Double#hashCode(double)" (compat-call "DoubleHashCode" arguments) "executable:java.lang.Class#getAnnotation(java.lang.Class)" (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.ClassGetAnnotation<") (type-node (clojure.core/deref ctx-holder) (.getType element)) (raw ">(") target-node (raw ", ") (sequence-node arguments ", ") (raw ")!")]) "executable:java.lang.Class#getDeclaredConstructor(java.lang.Class[])" (compat-call "ClassGetDeclaredConstructor" (into [target-node] arguments)) "executable:java.lang.Class#getFields()" (sequence-node [target-node (raw ".GetFields()")]) "executable:java.lang.Class#getResourceAsStream(java.lang.String)" (compat-call "ClassGetResourceAsStream" (into [target-node] arguments)) ("executable:java.lang.Double#parseDouble(java.lang.String)" "executable:java.lang.Double#valueOf(java.lang.String)") (compat-call "ParseDouble" arguments) "executable:java.lang.Enum#toString()" (sequence-node [target-node (raw ".ToString()")]) "executable:java.lang.Float#compare(float,float)" (compat-call "CompareFloat" arguments) ("executable:java.lang.Float#floatToIntBits(float)" "executable:java.lang.Float#hashCode(float)") (compat-call "FloatToIntBits" arguments) "executable:java.lang.Long#hashCode(long)" (compat-call "LongHashCode" arguments) "executable:java.lang.Float#isFinite(float)" (sequence-node [(raw "float.IsFinite(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Double#isFinite(double)" (sequence-node [(raw "double.IsFinite(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Float#isInfinite(float)" (sequence-node [(raw "float.IsInfinity(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Double#isInfinite(double)" (sequence-node [(raw "double.IsInfinity(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Double#isInfinite()" (sequence-node [(raw "double.IsInfinity(") target-node (raw ")")]) "executable:java.lang.Float#isNaN(float)" (sequence-node [(raw "float.IsNaN(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Double#isNaN(double)" (sequence-node [(raw "double.IsNaN(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Double#isNaN()" (sequence-node [(raw "double.IsNaN(") target-node (raw ")")]) "executable:java.lang.Double#toString()" (compat-call "StringValueOf" [target-node]) "executable:java.lang.Double#toString(double)" (compat-call "StringValueOf" arguments) "executable:java.lang.Integer#compareTo(java.lang.Integer)" (let [value-target (maybe-unbox-node (clojure.core/deref ctx-holder) target target-node) value-arguments (mapv (fn [argument node] (maybe-unbox-node (clojure.core/deref ctx-holder) argument node)) (.getArguments element) arguments)] (sequence-node [value-target (raw ".CompareTo(") (sequence-node value-arguments ", ") (raw ")")])) "executable:java.lang.Integer#signum(int)" (sequence-node [(raw "global::System.Math.Sign(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Integer#numberOfLeadingZeros(int)" (compat-call "IntLeadingZeros" arguments) "executable:java.lang.Long#numberOfLeadingZeros(long)" (compat-call "LongLeadingZeros" arguments) "executable:java.lang.Long#numberOfTrailingZeros(long)" (compat-call "LongTrailingZeros" arguments) "executable:java.lang.Long#signum(long)" (sequence-node [(raw "global::System.Math.Sign(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Float#parseFloat(java.lang.String)" (compat-call "ParseFloat" arguments) "executable:java.lang.Float#toString(float)" (compat-call "StringValueOf" arguments) "executable:java.lang.Integer#compare(int,int)" (compat-call "CompareInt" arguments) ("executable:java.lang.Integer#equals(java.lang.Object)" "executable:java.lang.Long#equals(java.lang.Object)" "executable:java.lang.Float#equals(java.lang.Object)" "executable:java.lang.Double#equals(java.lang.Object)") (compat-call "Equals" (into [target-node] arguments)) "executable:java.lang.Integer#highestOneBit(int)" (compat-call "HighestOneBit" arguments) ("executable:java.lang.Integer#toHexString(int)" "executable:java.lang.Long#toHexString(long)") (compat-call "ToHexString" arguments) "executable:java.lang.Long#valueOf(java.lang.String)" (compat-call "ParseLong" (conj arguments (raw "10"))) "executable:java.lang.Integer#valueOf(int)" (first arguments) "executable:java.lang.Integer#valueOf(java.lang.String)" (compat-call "ParseInt" (conj arguments (raw "10"))) "executable:java.lang.Long#compare(long,long)" (compat-call "CompareLong" arguments) "executable:java.lang.Math#ceil(double)" (let [argument (first (.getArguments element)) argument-node (maybe-unbox-node (clojure.core/deref ctx-holder) argument (child-node children argument))] (sequence-node [(raw "global::System.Math.Ceiling(") (raw "(double)(") argument-node (raw ")") (raw ")")])) "executable:java.math.BigInteger#valueOf(long)" (sequence-node [(raw "new global::System.Numerics.BigInteger(") (sequence-node arguments ", ") (raw ")")]) "executable:java.math.BigInteger#toByteArray()" (compat-call "BigIntegerToByteArray" [target-node]) "executable:java.math.BigInteger#mod(java.math.BigInteger)" (compat-call "BigIntegerMod" (into [target-node] arguments)) "executable:java.math.BigInteger#not()" (sequence-node [(raw "(~") target-node (raw ")")]) "executable:java.math.BigInteger#shiftRight(int)" (compat-call "BigIntegerShiftRight" (into [target-node] arguments)) "executable:java.math.BigInteger#and(java.math.BigInteger)" (sequence-node [(raw "(") target-node (raw " & ") (first arguments) (raw ")")]) "executable:java.math.BigInteger#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.math.BigInteger#intValue()" (compat-call "BigIntegerIntValue" [target-node]) "executable:java.math.BigInteger#toString(int)" (compat-call "ToStringRadix" (into [target-node] arguments)) "executable:java.lang.Number#doubleValue()" (sequence-node [(raw "global::System.Convert.ToDouble(") target-node (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")]) "executable:java.lang.Number#floatValue()" (sequence-node [(raw "global::System.Convert.ToSingle(") target-node (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")]) "executable:java.lang.Number#intValue()" (sequence-node [(raw "global::System.Convert.ToInt32(") target-node (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")]) ("executable:java.lang.Float#floatValue()" "executable:java.lang.Integer#intValue()" "executable:java.lang.Long#longValue()" "executable:java.lang.Double#doubleValue()" "executable:java.lang.Boolean#booleanValue()") (maybe-unbox-node (clojure.core/deref ctx-holder) target target-node) ("executable:java.lang.Integer#longValue()" "executable:java.lang.Number#longValue()") (sequence-node [(raw "global::System.Convert.ToInt64(") target-node (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")]) "executable:java.lang.Integer#shortValue()" (sequence-node [(raw "unchecked((short)") target-node (raw ")")]) "executable:java.lang.Object#clone()" (if (instance? CtSuperAccess target) (raw "this.MemberwiseClone()") (compat-call "Clone" [target-node])) ("executable:java.lang.Object#equals(java.lang.Object)" "executable:java.lang.Record#equals(java.lang.Object)" "executable:java.lang.Enum#equals(java.lang.Object)" "executable:java.lang.Boolean#equals(java.lang.Object)" "executable:java.nio.file.Path#equals(java.lang.Object)") (compat-call "Equals" (into [target-node] arguments)) ("executable:java.lang.Object#hashCode()" "executable:java.lang.Record#hashCode()" "executable:java.lang.Enum#hashCode()") (sequence-node [target-node (raw ".GetHashCode()")]) "executable:java.lang.Record#toString()" (sequence-node [target-node (raw ".ToString()")]) "executable:java.lang.reflect.Constructor#newInstance(java.lang.Object[])" (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.ConstructorInvoke<") (type-node (clojure.core/deref ctx-holder) (.getType element)) (raw ">(") target-node (when (seq arguments) (raw ", ")) (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.reflect.Field#getAnnotation(java.lang.Class)" (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.FieldGetAnnotation<") (type-node (clojure.core/deref ctx-holder) (.getType element)) (raw ">(") target-node (raw ", ") (sequence-node arguments ", ") (raw ")!")]) ("executable:java.lang.reflect.AccessibleObject#isAnnotationPresent(java.lang.Class)" "executable:java.lang.reflect.Field#isAnnotationPresent(java.lang.Class)") (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.MemberIsAnnotationPresent(") target-node (raw ", ") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.reflect.Field#getModifiers()" (compat-call "ReflectionFieldModifiers" [target-node]) "executable:java.lang.reflect.Modifier#isFinal(int)" (compat-call "ReflectionModifierIsFinal" arguments) "executable:java.lang.String#charAt(int)" (sequence-node [target-node (raw "[") (first arguments) (raw "]")]) "executable:java.lang.String#codePointAt(int)" (compat-call "CodePointAt" (into [target-node] arguments)) "executable:java.lang.String#codePointCount(int,int)" (compat-call "StringCodePointCount" (into [target-node] arguments)) "executable:java.lang.String#codePoints()" (compat-call "StringCodePoints" [target-node]) "executable:java.lang.String#compareTo(java.lang.String)" (compat-call "StringCompareTo" (into [target-node] arguments)) "executable:java.lang.String#format(java.util.Locale,java.lang.String,java.lang.Object[])" (compat-call "JavaStringFormat" arguments) "executable:java.lang.String#indexOf(java.lang.String)" (sequence-node [target-node (raw ".IndexOf(") (first arguments) (raw ", global::System.StringComparison.Ordinal)")]) "executable:java.lang.String#replace(char,char)" (sequence-node [target-node (raw ".Replace(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.String#replace(java.lang.CharSequence,java.lang.CharSequence)" (sequence-node [target-node (raw ".Replace(") (sequence-node arguments ", ") (raw ", global::System.StringComparison.Ordinal)")]) "executable:java.lang.String#replaceAll(java.lang.String,java.lang.String)" (compat-call "StringReplaceAll" (into [target-node] arguments)) "executable:java.lang.String#replaceFirst(java.lang.String,java.lang.String)" (compat-call "StringReplaceFirst" (into [target-node] arguments)) "executable:java.lang.String#toLowerCase(java.util.Locale)" (sequence-node [target-node (raw ".ToLowerInvariant()")]) "executable:java.lang.String#toUpperCase(java.util.Locale)" (sequence-node [target-node (raw ".ToUpperInvariant()")]) ("executable:java.lang.String#valueOf(boolean)" "executable:java.lang.String#valueOf(char)" "executable:java.lang.String#valueOf(char[])" "executable:java.lang.String#valueOf(double)" "executable:java.lang.String#valueOf(float)" "executable:java.lang.String#valueOf(int)" "executable:java.lang.String#valueOf(java.lang.Object)" "executable:java.lang.String#valueOf(long)") (compat-call "StringValueOf" arguments) "executable:java.lang.StringBuilder#append(java.lang.Object)" (sequence-node [target-node (raw ".Append(") (compat-call "StringValueOf" arguments) (raw ")")]) "executable:java.lang.StringBuilder#append(java.lang.CharSequence,int,int)" (sequence-node [target-node (raw ".Append(") (first arguments) (raw ", ") (second arguments) (raw ", ") (raw "(") (nth arguments 2) (raw " - ") (second arguments) (raw "))")]) "executable:java.lang.StringBuilder#reverse()" (compat-call "Reverse" [target-node]) "executable:java.lang.StringBuilder#deleteCharAt(int)" (sequence-node [target-node (raw ".Remove(") (first arguments) (raw ", 1)")]) ("executable:java.lang.StringBuilder#delete(int,int)" "executable:java.lang.AbstractStringBuilder#delete(int,int)") (compat-call "StringBuilderDelete" (into [target-node] arguments)) ("executable:java.lang.StringBuilder#setLength(int)" "executable:java.lang.AbstractStringBuilder#setLength(int)") (sequence-node [target-node (raw ".Length = ") (first arguments)]) ("executable:java.lang.StringBuilder#charAt(int)" "executable:java.lang.AbstractStringBuilder#charAt(int)") (sequence-node [target-node (raw "[") (first arguments) (raw "]")]) "executable:java.lang.System#getProperty(java.lang.String)" (compat-call "GetProperty" arguments) "executable:java.lang.System#getProperty(java.lang.String,java.lang.String)" (compat-call "GetProperty" arguments) "executable:java.lang.System#getenv()" (compat-call "GetEnvironment" arguments) "executable:java.lang.System#getenv(java.lang.String)" (compat-call "Getenv" arguments) "executable:java.lang.System#getProperties()" (compat-call "GetProperties" []) "executable:java.lang.System#identityHashCode(java.lang.Object)" (compat-call "IdentityHashCode" arguments) "executable:java.lang.System#lineSeparator()" (raw "global::System.Environment.NewLine") "executable:java.lang.System#exit(int)" (sequence-node [(raw "global::System.Environment.Exit(") (first arguments) (raw ")")]) "executable:java.security.SecureRandom#nextBytes(byte[])" (sequence-node [target-node (raw ".NextBytes(") (sequence-node arguments ", ") (raw ")")]) "executable:java.security.SecureRandom#nextInt()" (sequence-node [target-node (raw ".NextInt()")]) "executable:java.nio.Buffer#hasRemaining()" (sequence-node [(raw "(") target-node (raw ".Remaining > 0)")]) "executable:java.nio.charset.Charset#forName(java.lang.String)" (compat-call "CharsetForName" arguments) "executable:java.nio.charset.Charset#name()" (compat-call "CharsetName" [target-node]) "executable:java.nio.charset.Charset#newDecoder()" (sequence-node [(raw "new global::DripSharp.Runtime.JavaCharsetDecoder(") target-node (raw ")")]) ("executable:java.nio.charset.CharsetDecoder#onMalformedInput(java.nio.charset.CodingErrorAction)" "executable:java.nio.charset.CharsetDecoder#onUnmappableCharacter(java.nio.charset.CodingErrorAction)") (sequence-node [target-node (raw ".ReportErrors(") (sequence-node arguments ", ") (raw ")")]) "executable:java.nio.charset.CharsetDecoder#decode(java.nio.ByteBuffer)" (sequence-node [target-node (raw ".Decode(") (sequence-node arguments ", ") (raw ")")]) "executable:java.nio.CharBuffer#toString()" target-node "executable:java.nio.CharBuffer#wrap(char[],int,int)" (compat-call "CharBufferWrap" arguments) "executable:java.nio.file.Files#readAllBytes(java.nio.file.Path)" (compat-call "ReadAllBytes" arguments) "executable:java.nio.file.Files#find(java.nio.file.Path,int,java.util.function.BiPredicate,java.nio.file.FileVisitOption[])" (compat-call "FindFiles" arguments) "executable:java.nio.file.attribute.BasicFileAttributes#isRegularFile()" (compat-call "IsRegularFile" [target-node]) "executable:java.nio.file.Paths#get(java.lang.String,java.lang.String[])" (compat-call "PathOf" arguments) "executable:java.nio.file.Paths#get(java.net.URI)" (compat-call "PathOfUri" arguments) ("executable:java.util.Arrays#binarySearch(int[],int)" "executable:java.util.Arrays#binarySearch(java.lang.Object[],java.lang.Object,java.util.Comparator)") (compat-call "BinarySearch" arguments) ("executable:java.util.Arrays#copyOf(byte[],int)" "executable:java.util.Arrays#copyOf(float[],int)") (compat-call "CopyOf" arguments) "executable:java.util.Arrays#copyOfRange(byte[],int,int)" (compat-call "CopyOfRange" arguments) "executable:java.util.Arrays#deepToString(java.lang.Object[])" (compat-call "DeepArrayString" arguments) ("executable:java.util.Arrays#fill(int[],int)" "executable:java.util.Arrays#fill(byte[],byte)" "executable:java.util.Arrays#fill(byte[],int,int,byte)" "executable:java.util.Arrays#fill(float[],float)" "executable:java.util.Arrays#fill(double[],double)") (compat-call "Fill" arguments) ("executable:java.util.Arrays#toString(float[])" "executable:java.util.Arrays#toString(int[])" "executable:java.util.Arrays#toString(java.lang.Object[])") (compat-call "ArrayToString" arguments) "executable:java.text.DecimalFormatSymbols#getInstance(java.util.Locale)" (sequence-node [(first arguments) (raw ".NumberFormat")]) "executable:java.text.DecimalFormat#setDecimalFormatSymbols(java.text.DecimalFormatSymbols)" (sequence-node [target-node (raw ".SetDecimalFormatSymbols(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.text.NumberFormat#format(long)" "executable:java.text.NumberFormat#format(double)") (sequence-node [target-node (raw ".Format(") (sequence-node arguments ", ") (raw ")")]) "executable:java.text.Format#format(java.lang.Object)" (compat-call "Format" (into [target-node] arguments)) "executable:java.text.NumberFormat#getMaximumFractionDigits()" (sequence-node [target-node (raw ".GetMaximumFractionDigits()")]) "executable:java.text.NumberFormat#getNumberInstance(java.util.Locale)" (sequence-node [(raw "global::DripSharp.Runtime.JavaDecimalFormat.GetNumberInstance(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.text.DecimalFormat#setMinimumFractionDigits(int)" "executable:java.text.NumberFormat#setMinimumFractionDigits(int)") (sequence-node [target-node (raw ".SetMinimumFractionDigits(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.text.DecimalFormat#setMaximumFractionDigits(int)" "executable:java.text.NumberFormat#setMaximumFractionDigits(int)") (sequence-node [target-node (raw ".SetMaximumFractionDigits(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.text.DecimalFormat#setGroupingUsed(boolean)" "executable:java.text.NumberFormat#setGroupingUsed(boolean)") (sequence-node [target-node (raw ".SetGroupingUsed(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Calendar#getInstance(java.util.TimeZone)" (compat-call "CalendarInstance" arguments) "executable:java.util.Calendar#clear()" (sequence-node [target-node (raw " = ") (compat-call "CalendarClear" [target-node])]) "executable:java.util.Calendar#compareTo(java.util.Calendar)" (sequence-node [target-node (raw ".CompareTo(") (first arguments) (raw ")")]) "executable:java.util.Calendar#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.util.Calendar#get(int)" (compat-call "CalendarGet" (into [target-node] arguments)) "executable:java.util.Calendar#getTimeInMillis()" (sequence-node [target-node (raw ".ToUnixTimeMilliseconds()")]) ("executable:java.util.Calendar#set(int,int)" "executable:java.util.Calendar#set(int,int,int,int,int,int)") (sequence-node [target-node (raw " = ") (compat-call "CalendarSet" (into [target-node] arguments))]) "executable:java.util.Calendar#setTimeInMillis(long)" (sequence-node [target-node (raw " = global::System.DateTimeOffset.FromUnixTimeMilliseconds(") (first arguments) (raw ")")]) "executable:java.util.Calendar#setLenient(boolean)" (compat-call "CalendarSetLenient" (into [target-node] arguments)) ("executable:java.util.Calendar#setTimeZone(java.util.TimeZone)" "executable:java.util.GregorianCalendar#setTimeZone(java.util.TimeZone)") (sequence-node [target-node (raw " = ") (compat-call "CalendarSetTimeZone" (into [target-node] arguments))]) "executable:java.util.Calendar#getTimeZone()" (compat-call "CalendarGetTimeZone" [target-node]) ("executable:java.util.Calendar#add(int,int)" "executable:java.util.GregorianCalendar#add(int,int)") (sequence-node [target-node (raw " = ") (compat-call "CalendarAdd" (into [target-node] arguments))]) "executable:java.util.GregorianCalendar#from(java.time.ZonedDateTime)" (first arguments) "executable:java.util.Deque#pop()" (sequence-node [target-node (raw ".Pop()")]) "executable:java.util.Deque#removeFirst()" (sequence-node [target-node (raw ".Pop()")]) "executable:java.util.Deque#push(java.lang.Object)" (compat-call "DequePush" (into [target-node] arguments)) "executable:java.util.Deque#add(java.lang.Object)" (compat-call "Add" (into [target-node] arguments)) "executable:java.util.Deque#addAll(java.util.Collection)" (compat-call "AddAll" (into [target-node] arguments)) "executable:java.util.Deque#contains(java.lang.Object)" (compat-call "CollectionContains" (into [target-node] arguments)) "executable:java.util.Deque#isEmpty()" (sequence-node [target-node (raw ".IsEmpty()")]) "executable:java.util.Deque#peek()" (compat-call "DequePeek" [target-node]) "executable:java.util.Deque#size()" (sequence-node [target-node (raw ".Count")]) "executable:java.util.Deque#clear()" (sequence-node [target-node (raw ".Clear()")]) "executable:java.util.PriorityQueue#add(java.lang.Object)" (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.PriorityQueue#isEmpty()" (sequence-node [(raw "(") target-node (raw ".Count == 0)")]) "executable:java.util.PriorityQueue#peek()" (sequence-node [target-node (raw ".Peek()")]) "executable:java.util.PriorityQueue#poll()" (sequence-node [target-node (raw ".Poll()")]) ("executable:java.util.Properties#getProperty(java.lang.String)" "executable:java.util.Properties#getProperty(java.lang.String,java.lang.String)") (sequence-node [target-node (raw ".GetProperty(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Properties#load(java.io.InputStream)" (sequence-node [target-node (raw ".Load(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.AbstractCollection#isEmpty()" (sequence-node [(raw "(") target-node (raw ".Count == 0)")]) "executable:java.util.AbstractQueue#add(java.lang.Object)" (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Queue#add(java.lang.Object)" (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Queue#peek()" (sequence-node [target-node (raw ".Peek()")]) "executable:java.util.Queue#poll()" (sequence-node [target-node (raw ".Poll()")]) "executable:java.util.Collection#add(java.lang.Object)" (compat-call "Add" (into [target-node] arguments)) "executable:java.util.Collection#isEmpty()" (compat-call "CollectionIsEmpty" [target-node]) "executable:java.util.Collection#size()" (compat-call "CollectionCount" [target-node]) ("executable:java.util.Collection#removeAll(java.util.Collection)" "executable:java.util.AbstractCollection#removeAll(java.util.Collection)" "executable:java.util.List#removeAll(java.util.Collection)") (compat-call "RemoveAll" (into [target-node] arguments)) ("executable:java.util.Collection#retainAll(java.util.Collection)" "executable:java.util.AbstractCollection#retainAll(java.util.Collection)" "executable:java.util.List#retainAll(java.util.Collection)") (compat-call "RetainAll" (into [target-node] arguments)) ("executable:java.util.Collections#sort(java.util.List)" "executable:java.util.Collections#sort(java.util.List,java.util.Comparator)") (compat-call "SortList" arguments) "executable:java.util.Collections#reverse(java.util.List)" (compat-call "Reverse" arguments) "executable:java.util.Base64#getDecoder()" (raw "global::DripSharp.Runtime.JavaBase64.GetDecoder()") "executable:java.util.Base64#getEncoder()" (raw "global::DripSharp.Runtime.JavaBase64.GetEncoder()") "executable:java.util.Base64$Decoder#decode(java.lang.String)" (sequence-node [target-node (raw ".Decode(") (first arguments) (raw ")")]) "executable:java.util.Base64$Encoder#encodeToString(byte[])" (sequence-node [target-node (raw ".EncodeToString(") (first arguments) (raw ")")]) "executable:java.util.Collections#max(java.util.Collection)" (compat-call "CollectionMax" arguments) "executable:java.util.Collections#min(java.util.Collection)" (compat-call "CollectionMin" arguments) "executable:java.util.Collections#newSetFromMap(java.util.Map)" (compat-call "NewSetFromMap" arguments) "executable:java.util.Collections#unmodifiableSet(java.util.Set)" (compat-call "UnmodifiableSet" arguments) "executable:java.util.Comparator#naturalOrder()" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.NaturalOrder") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "()")]) "executable:java.util.Comparator#comparingInt(java.util.function.ToIntFunction)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ComparingInt") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Comparator#thenComparingInt(java.util.function.ToIntFunction)" (compat-call "ThenComparingInt" (into [target-node] arguments)) "executable:java.util.Comparator#thenComparing(java.util.Comparator)" (compat-call "ThenComparing" (into [target-node] arguments)) "executable:java.util.Comparator#comparing(java.util.function.Function)" (let [comparator-arguments (vec (.getActualTypeArguments (.getType element))) function-arguments (vec (.getActualTypeArguments (.getType (first (.getArguments element)))))] (if (and (= 1 (count comparator-arguments)) (= 2 (count function-arguments))) (sequence-node [(raw "global::DripSharp.Runtime.JavaCompat.ComparatorComparing<") (type-node (clojure.core/deref ctx-holder) (first comparator-arguments)) (raw ", ") (type-node (clojure.core/deref ctx-holder) (second function-arguments)) (raw ">(") (first arguments) (raw ")")]) (compat-call "ComparatorComparing" arguments))) "executable:java.util.List#add(int,java.lang.Object)" (compat-call "ListAdd" (into [target-node] arguments)) ("executable:java.util.List#addAll(int,java.util.Collection)" "executable:java.util.ArrayList#addAll(int,java.util.Collection)") (compat-call "ListAddAll" (into [target-node] arguments)) "executable:java.util.List#indexOf(java.lang.Object)" (compat-call "ListIndexOf" (into [target-node] arguments)) "executable:java.util.List#lastIndexOf(java.lang.Object)" (compat-call "ListLastIndexOf" (into [target-node] arguments)) "executable:java.util.List#remove(int)" (compat-call "ListRemove" (into [target-node] arguments)) "executable:java.util.List#set(int,java.lang.Object)" (compat-call "ListSet" (into [target-node] arguments)) ("executable:java.util.List#sort(java.util.Comparator)" "executable:java.util.ArrayList#sort(java.util.Comparator)") (compat-call "SortList" (into [target-node] arguments)) "executable:java.util.List#subList(int,int)" (compat-call "SubList" (into [target-node] arguments)) "executable:java.util.Stack#push(java.lang.Object)" (sequence-node [target-node (raw ".Push(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Stack#pop()" (sequence-node [target-node (raw ".Pop()")]) "executable:java.util.Stack#peek()" (sequence-node [target-node (raw ".Peek()")]) "executable:java.util.Vector#isEmpty()" (sequence-node [target-node (raw ".IsEmpty")]) "executable:java.util.Vector#size()" (sequence-node [target-node (raw ".Count")]) "executable:java.util.Vector#get(int)" (sequence-node [target-node (raw ".Get(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Vector#addAll(java.util.Collection)" (sequence-node [target-node (raw ".AddAll(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Vector#clear()" (sequence-node [target-node (raw ".Clear()")]) "executable:java.util.Vector#subList(int,int)" (sequence-node [target-node (raw ".SubList(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.Map#isEmpty()" "executable:java.util.TreeMap#isEmpty()") (compat-call "MapIsEmpty" [target-node]) "executable:java.util.Set#addAll(java.util.Collection)" (compat-call "AddAll" (into [target-node] arguments)) "executable:java.util.Set#isEmpty()" (compat-call "CollectionIsEmpty" [target-node]) "executable:java.util.Set#iterator()" (compat-call "Iterator" [target-node]) "executable:java.util.Set#size()" (sequence-node [target-node (raw ".Count")]) ("executable:java.util.SortedMap#entrySet()" "executable:java.util.TreeMap#entrySet()") (compat-call "MapEntrySet" [target-node]) ("executable:java.util.SortedMap#firstKey()" "executable:java.util.TreeMap#firstKey()") (compat-call "SortedFirstKey" [target-node]) ("executable:java.util.SortedMap#lastKey()" "executable:java.util.TreeMap#lastKey()") (compat-call "SortedLastKey" [target-node]) ("executable:java.util.SortedMap#subMap(java.lang.Object,java.lang.Object)" "executable:java.util.TreeMap#subMap(java.lang.Object,java.lang.Object)") (compat-call "SortedSubMap" (into [target-node] arguments)) "executable:java.util.SortedSet#headSet(java.lang.Object)" (compat-call "SortedHeadSet" (into [target-node] arguments)) "executable:java.util.SortedSet#first()" (compat-call "SortedFirst" [target-node]) "executable:java.util.SortedSet#last()" (compat-call "SortedLast" [target-node]) ("executable:java.util.SortedSet#subSet(java.lang.Object,java.lang.Object)" "executable:java.util.TreeSet#subSet(java.lang.Object,java.lang.Object)") (compat-call "SortedSubSet" (into [target-node] arguments)) "executable:java.util.TimeZone#clone()" (compat-call "Clone" [target-node]) "executable:java.util.TimeZone#getTimeZone(java.lang.String)" (compat-call "GetTimeZone" arguments) "executable:java.util.TimeZone#getRawOffset()" (compat-call "TimeZoneRawOffset" [target-node]) "executable:java.util.TimeZone#setID(java.lang.String)" (compat-call "TimeZoneSetId" (into [target-node] arguments)) "executable:java.util.TimeZone#getID()" (compat-call "TimeZoneId" [target-node]) "executable:java.util.TimeZone#getOffset(long)" (compat-call "TimeZoneOffset" (into [target-node] arguments)) "executable:java.util.TimeZone#setRawOffset(int)" (compat-call "TimeZoneSetRawOffset" (into [target-node] arguments)) "executable:javax.xml.namespace.QName#getLocalPart()" (sequence-node [target-node (raw ".Name")]) "executable:javax.xml.namespace.QName#getNamespaceURI()" (sequence-node [target-node (raw ".Namespace")]) "executable:javax.xml.namespace.QName#getPrefix()" (compat-call "XmlQualifiedNamePrefix" [target-node]) "executable:javax.xml.parsers.DocumentBuilderFactory#newInstance()" (raw "global::DripSharp.Runtime.JavaCompat.NewXmlReaderSettings()") "executable:javax.xml.parsers.DocumentBuilderFactory#newDocumentBuilder()" (compat-call "XmlReaderSettingsClone" [target-node]) "executable:javax.xml.parsers.DocumentBuilderFactory#setFeature(java.lang.String,boolean)" (compat-call "XmlReaderSetFeature" (into [target-node] arguments)) "executable:javax.xml.parsers.DocumentBuilderFactory#setXIncludeAware(boolean)" (compat-call "XmlReaderSetXIncludeAware" (into [target-node] arguments)) "executable:javax.xml.parsers.DocumentBuilderFactory#setExpandEntityReferences(boolean)" (compat-call "XmlReaderSetExpandEntityReferences" (into [target-node] arguments)) "executable:javax.xml.parsers.DocumentBuilderFactory#setIgnoringComments(boolean)" (sequence-node [target-node (raw ".IgnoreComments = ") (first arguments)]) "executable:javax.xml.parsers.DocumentBuilderFactory#setNamespaceAware(boolean)" (compat-call "XmlReaderSetNamespaceAware" (into [target-node] arguments)) "executable:javax.xml.parsers.DocumentBuilder#newDocument()" (raw "new global::System.Xml.XmlDocument()") "executable:javax.xml.parsers.DocumentBuilder#parse(java.io.InputStream)" (compat-call "XmlParse" (into [target-node] arguments)) "executable:javax.xml.parsers.DocumentBuilder#setErrorHandler(org.xml.sax.ErrorHandler)" (compat-call "XmlSetErrorHandler" (into [target-node] arguments)) "executable:javax.xml.transform.TransformerFactory#newInstance()" (raw "new global::System.Xml.XmlWriterSettings()") "executable:javax.xml.transform.TransformerFactory#newTransformer()" (compat-call "XmlWriterSettingsClone" [target-node]) "executable:javax.xml.transform.Transformer#setOutputProperty(java.lang.String,java.lang.String)" (compat-call "XmlSetOutputProperty" (into [target-node] arguments)) "executable:javax.xml.transform.Transformer#transform(javax.xml.transform.Source,javax.xml.transform.Result)" (compat-call "XmlTransform" (into [target-node] arguments)) "executable:org.w3c.dom.NamedNodeMap#getLength()" (sequence-node [target-node (raw ".Count")]) "executable:org.w3c.dom.NamedNodeMap#item(int)" (compat-call "XmlAttributeItem" (into [target-node] arguments)) "executable:org.w3c.dom.NamedNodeMap#getNamedItem(java.lang.String)" (sequence-node [target-node (raw ".GetNamedItem(") (first arguments) (raw ")")]) "executable:org.w3c.dom.Node#getAttributes()" (sequence-node [target-node (raw ".Attributes!")]) "executable:org.w3c.dom.Node#getChildNodes()" (sequence-node [target-node (raw ".ChildNodes")]) "executable:org.w3c.dom.Node#getFirstChild()" (sequence-node [target-node (raw ".FirstChild")]) "executable:org.w3c.dom.Node#getLocalName()" (sequence-node [target-node (raw ".LocalName")]) "executable:org.w3c.dom.Node#getNamespaceURI()" (compat-call "XmlNodeNamespaceUri" [target-node]) "executable:org.w3c.dom.Node#getNextSibling()" (sequence-node [target-node (raw ".NextSibling")]) "executable:org.w3c.dom.Node#getNodeName()" (sequence-node [target-node (raw ".Name")]) "executable:org.w3c.dom.Node#getNodeValue()" (sequence-node [target-node (raw ".Value")]) "executable:org.w3c.dom.Node#getOwnerDocument()" (sequence-node [target-node (raw ".OwnerDocument!")]) "executable:org.w3c.dom.Node#getPrefix()" (compat-call "XmlNodePrefix" [target-node]) "executable:org.w3c.dom.Node#getTextContent()" (sequence-node [target-node (raw ".InnerText")]) "executable:org.w3c.dom.Node#appendChild(org.w3c.dom.Node)" (sequence-node [target-node (raw ".AppendChild(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.Node#removeChild(org.w3c.dom.Node)" (sequence-node [target-node (raw ".RemoveChild(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.Attr#getValue()" (sequence-node [target-node (raw ".Value")]) "executable:org.w3c.dom.CharacterData#getData()" (sequence-node [target-node (raw ".Data")]) "executable:org.w3c.dom.Document#createElementNS(java.lang.String,java.lang.String)" (compat-call "XmlCreateElementNs" (into [target-node] arguments)) "executable:org.w3c.dom.Document#createElement(java.lang.String)" (sequence-node [target-node (raw ".CreateElement(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.Document#createProcessingInstruction(java.lang.String,java.lang.String)" (sequence-node [target-node (raw ".CreateProcessingInstruction(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.Document#getDocumentElement()" (sequence-node [target-node (raw ".DocumentElement!")]) "executable:org.w3c.dom.Document#getInputEncoding()" (compat-call "XmlInputEncoding" [target-node]) "executable:org.w3c.dom.Document#getXmlEncoding()" (compat-call "XmlEncoding" [target-node]) "executable:org.w3c.dom.Element#setAttribute(java.lang.String,java.lang.String)" (sequence-node [target-node (raw ".SetAttribute(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.Element#setAttributeNS(java.lang.String,java.lang.String,java.lang.String)" (compat-call "XmlSetAttributeNs" (into [target-node] arguments)) "executable:org.w3c.dom.Element#getAttribute(java.lang.String)" (sequence-node [target-node (raw ".GetAttribute(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.Element#getElementsByTagName(java.lang.String)" (sequence-node [target-node (raw ".GetElementsByTagName(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.Element#getTagName()" (sequence-node [target-node (raw ".Name")]) "executable:org.w3c.dom.Element#getAttributeNodeNS(java.lang.String,java.lang.String)" (sequence-node [target-node (raw ".GetAttributeNode(") (second arguments) (raw ", ") (first arguments) (raw ")")]) "executable:org.w3c.dom.Node#setTextContent(java.lang.String)" (sequence-node [target-node (raw ".InnerText = ") (first arguments)]) "executable:org.w3c.dom.NodeList#getLength()" (sequence-node [target-node (raw ".Count")]) "executable:org.w3c.dom.NodeList#item(int)" (sequence-node [target-node (raw ".Item(") (sequence-node arguments ", ") (raw ")")]) "executable:org.w3c.dom.ProcessingInstruction#getData()" (sequence-node [target-node (raw ".Data")]) "executable:javax.xml.xpath.XPathFactory#newInstance()" (raw "global::DripSharp.Runtime.JavaXPathFactory.Instance") "executable:javax.xml.xpath.XPathFactory#newXPath()" (sequence-node [target-node (raw ".NewXPath()")]) ("executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object)" "executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object,javax.xml.namespace.QName)") (sequence-node [target-node (raw ".Evaluate(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.zip.CRC32#update(byte[],int,int)" (sequence-node [target-node (raw ".Update(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.zip.CRC32#getValue()" (sequence-node [target-node (raw ".GetValue()")]) "executable:java.util.Collections#emptyList()" (sequence-node [(raw "global::System.Array.Empty<") (type-node (clojure.core/deref ctx-holder) (collection-element-type element)) (raw ">()")]) "executable:java.util.Collections#emptyIterator()" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EmptyJavaIterator") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "()")]) "executable:java.util.Collections#emptyMap()" (let [type-arguments (.getActualTypeArguments (.getType element))] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EmptyMap") (mapv (fn* [p1__476#] (type-node (clojure.core/deref ctx-holder) p1__476#)) type-arguments)) (raw "()")])) "executable:java.util.Collections#emptySet()" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EmptySet") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "()")]) "executable:java.util.Collections#singletonList(java.lang.Object)" (let [element-node (collection-element-type-node (clojure.core/deref ctx-holder) element)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ListOf") [element-node]) (raw "(") (sequence-node (collection-factory-argument-nodes (clojure.core/deref ctx-holder) element arguments element-node) ", ") (raw ")")])) "executable:java.util.Collections#nCopies(int,java.lang.Object)" (compat-call "NCopies" arguments) "executable:java.util.Collections#synchronizedMap(java.util.Map)" (first arguments) "executable:java.util.Collections#synchronizedList(java.util.List)" (compat-call "SynchronizedList" arguments) "executable:java.util.Collections#unmodifiableList(java.util.List)" (compat-call "UnmodifiableList" arguments) "executable:java.util.Collections#unmodifiableMap(java.util.Map)" (compat-call "UnmodifiableMap" arguments) "executable:java.net.URI#getHost()" (sequence-node [(compat-call "UriHost" [target-node]) (raw "!")]) "executable:java.net.URI#getPort()" (compat-call "UriPort" [target-node]) "executable:java.net.URI#getScheme()" (sequence-node [(compat-call "UriScheme" [target-node]) (raw "!")]) "executable:java.net.URI#getUserInfo()" (sequence-node [(compat-call "UriUserInfo" [target-node]) (raw "!")]) "executable:java.net.URI#getRawPath()" (sequence-node [(compat-call "UriRawPath" [target-node]) (raw "!")]) "executable:java.net.URI#getPath()" (sequence-node [(compat-call "UriPath" [target-node]) (raw "!")]) "executable:java.net.URI#getRawQuery()" (sequence-node [(compat-call "UriRawQuery" [target-node]) (raw "!")]) "executable:java.net.URI#getRawFragment()" (sequence-node [(compat-call "UriRawFragment" [target-node]) (raw "!")]) "executable:java.net.URI#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.net.URI#hashCode()" (compat-call "HashCode" [target-node]) "executable:java.net.URI#toString()" (sequence-node [target-node (raw ".OriginalString")]) "executable:java.net.URI#create(java.lang.String)" (sequence-node [(raw "new global::System.Uri(") (sequence-node arguments ", ") (raw ", global::System.UriKind.RelativeOrAbsolute)")]) "executable:java.lang.String#toUpperCase()" (sequence-node [target-node (raw ".ToUpper()")]) "executable:java.lang.String#toLowerCase()" (sequence-node [target-node (raw ".ToLowerInvariant()")]) "executable:java.lang.Object#toString()" (sequence-node [target-node (raw ".ToString()!")]) "executable:java.lang.CharSequence#toString()" target-node "executable:java.lang.CharSequence#length()" (sequence-node [target-node (raw ".Length")]) "executable:java.lang.CharSequence#charAt(int)" (sequence-node [target-node (raw "[") (first arguments) (raw "]")]) "executable:java.lang.Throwable#getLocalizedMessage()" (sequence-node [target-node (raw ".Message")]) "executable:java.lang.String#format(java.lang.String,java.lang.Object[])" (compat-call "JavaStringFormat" arguments) "executable:java.lang.String#trim()" (compat-call "StringTrim" [target-node]) "executable:java.lang.String#split(java.lang.String)" (compat-call "StringSplit" (into [target-node] (conj arguments (raw "0")))) "executable:java.lang.String#split(java.lang.String,int)" (compat-call "StringSplit" (into [target-node] arguments)) "executable:java.lang.String#length()" (sequence-node [target-node (raw ".Length")]) "executable:java.lang.String#isEmpty()" (sequence-node [(raw "(") target-node (raw ".Length == 0)")]) "executable:java.lang.String#startsWith(java.lang.String)" (compat-call "StringStartsWith" (into [target-node] arguments)) "executable:java.lang.String#startsWith(java.lang.String,int)" (compat-call "StringStartsWith" (into [target-node] arguments)) "executable:java.lang.String#endsWith(java.lang.String)" (compat-call "StringEndsWith" (into [target-node] arguments)) "executable:java.lang.String#substring(int)" (sequence-node [target-node (raw ".Substring(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.String#substring(int,int)" (compat-call "StringSubstring" (into [target-node] arguments)) "executable:java.lang.String#indexOf(int)" (compat-call "StringIndexOf" (into [target-node] arguments)) "executable:java.lang.String#indexOf(int,int)" (compat-call "StringIndexOf" (into [target-node] arguments)) "executable:java.lang.String#lastIndexOf(int)" (compat-call "StringLastIndexOf" (into [target-node] arguments)) "executable:java.lang.String#contains(java.lang.CharSequence)" (compat-call "StringContains" (into [target-node] arguments)) "executable:java.lang.String#matches(java.lang.String)" (compat-call "StringMatches" (into [target-node] arguments)) "executable:java.lang.String#hashCode()" (compat-call "HashCode" [target-node]) "executable:java.lang.String#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.lang.String#equalsIgnoreCase(java.lang.String)" (compat-call "EqualsIgnoreCase" (into [target-node] arguments)) "executable:java.lang.String#toCharArray()" (sequence-node [target-node (raw ".ToCharArray()")]) ("executable:java.lang.String#getBytes(java.nio.charset.Charset)" "executable:java.lang.String#getBytes(java.lang.String)") (compat-call "StringGetBytes" (into [target-node] arguments)) "executable:java.lang.String#getBytes()" (compat-call "StringGetBytes" [target-node (raw "global::System.Text.Encoding.UTF8")]) "executable:java.lang.String#join(java.lang.CharSequence,java.lang.Iterable)" (compat-call "StringJoin" arguments) "executable:java.lang.Integer#toString()" (compat-call "StringValueOf" [target-node]) "executable:java.lang.Integer#toString(int,int)" (compat-call "ToStringRadix" arguments) "executable:java.lang.Integer#toString(int)" (compat-call "StringValueOf" arguments) "executable:java.lang.Integer#sum(int,int)" (compat-call "SumInt" arguments) "executable:java.lang.Integer#parseInt(java.lang.String)" (compat-call "ParseInt" (conj arguments (raw "10"))) ("executable:java.lang.Long#parseLong(java.lang.String)" "executable:java.lang.Long#parseLong(java.lang.String,int)" "executable:java.lang.Long#parseLong(java.lang.CharSequence,int,int,int)") (compat-call "ParseLong" arguments) "executable:java.lang.Long#toString()" (compat-call "StringValueOf" [target-node]) "executable:java.lang.Long#toString(long)" (compat-call "StringValueOf" arguments) "executable:java.lang.Long#toString(long,int)" (compat-call "ToStringRadix" arguments) ("executable:java.lang.Math#min(double,double)" "executable:java.lang.Math#min(float,float)" "executable:java.lang.Math#min(long,long)" "executable:java.lang.Math#min(int,int)") (sequence-node [(raw "global::System.Math.Min(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.lang.Math#max(double,double)" "executable:java.lang.Math#max(float,float)" "executable:java.lang.Math#max(long,long)" "executable:java.lang.Math#max(int,int)") (sequence-node [(raw "global::System.Math.Max(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Math#toIntExact(long)" (compat-call "ToIntExact" arguments) "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)" (compat-call "ArrayCopy" arguments) "executable:java.lang.System#currentTimeMillis()" (raw "global::System.DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()") "executable:java.lang.System#nanoTime()" (compat-call "NanoTime" []) "executable:java.lang.System#console()" (compat-call "ConsoleInstance" []) "executable:java.lang.ThreadLocal#withInitial(java.util.function.Supplier)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaThreadLocal") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw ".WithInitial(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.ThreadLocal#get()" (sequence-node [target-node (raw ".Get()")]) "executable:java.lang.ThreadLocal#set(java.lang.Object)" (sequence-node [target-node (raw ".Set(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.Arrays#equals(byte[],byte[])" "executable:java.util.Arrays#equals(char[],char[])" "executable:java.util.Arrays#equals(int[],int[])" "executable:java.util.Arrays#equals(float[],float[])" "executable:java.util.Arrays#equals(double[],double[])") (compat-call "ArrayEquals" arguments) ("executable:java.util.Arrays#hashCode(byte[])" "executable:java.util.Arrays#hashCode(int[])" "executable:java.util.Arrays#hashCode(float[])") (compat-call "ArrayHash" arguments) "executable:java.util.Arrays#asList(java.lang.Object[])" (let [element-node (collection-element-type-node (clojure.core/deref ctx-holder) element)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.AsList") [element-node]) (raw "(") (sequence-node (collection-factory-argument-nodes (clojure.core/deref ctx-holder) element arguments element-node) ", ") (raw ")")])) "executable:java.lang.Enum#name()" (compat-call "EnumName" [target-node]) "executable:java.lang.Enum#ordinal()" (compat-call "EnumOrdinal" [target-node]) "executable:java.lang.Integer#parseInt(java.lang.String,int)" (compat-call "ParseInt" arguments) "executable:java.net.Socket#getInputStream()" (compat-call "SocketStream" [target-node]) "executable:java.net.Socket#getOutputStream()" (compat-call "SocketStream" [target-node]) "executable:java.net.Socket#getRemoteSocketAddress()" (sequence-node [target-node (raw ".RemoteEndPoint")]) "executable:java.net.Socket#close()" (sequence-node [target-node (raw ".Close()")]) "executable:java.net.Socket#isClosed()" (compat-call "SocketIsClosed" [target-node]) "executable:java.net.Socket#isConnected()" (compat-call "SocketIsConnected" [target-node]) "executable:java.net.Socket#setSoTimeout(int)" (compat-call "SocketSetSoTimeout" (into [target-node] arguments)) "executable:java.net.ServerSocket#accept()" (sequence-node [target-node (raw ".Accept()")]) "executable:java.net.ServerSocket#close()" (sequence-node [target-node (raw ".Close()")]) "executable:java.net.ServerSocket#isClosed()" (sequence-node [target-node (raw ".IsClosed()")]) "executable:java.net.InetSocketAddress#getAddress()" (compat-call "InetSocketAddressAddress" [target-node]) "executable:java.net.URL#openStream()" (compat-call "OpenUrlStream" [target-node]) "executable:java.net.URLDecoder#decode(java.lang.String,java.lang.String)" (compat-call "UrlDecode" arguments) ("executable:java.net.URLEncoder#encode(java.lang.String,java.lang.String)" "executable:java.net.URLEncoder#encode(java.lang.String,java.nio.charset.Charset)") (compat-call "UrlEncode" arguments) "executable:java.util.Enumeration#nextElement()" (sequence-node [target-node (raw ".Next()")]) "executable:java.util.Enumeration#hasMoreElements()" (sequence-node [target-node (raw ".HasNext()")]) "executable:java.security.MessageDigest#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaMessageDigest.GetInstance(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.security.MessageDigest#update(byte)" "executable:java.security.MessageDigest#update(byte[])" "executable:java.security.MessageDigest#update(byte[],int,int)") (sequence-node [target-node (raw ".Update(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.security.MessageDigest#digest()" "executable:java.security.MessageDigest#digest(byte[])") (sequence-node [target-node (raw ".Digest(") (sequence-node arguments ", ") (raw ")")]) "executable:java.security.MessageDigest#isEqual(byte[],byte[])" (sequence-node [(raw "global::DripSharp.Runtime.JavaMessageDigest.IsEqual(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Random#nextBytes(byte[])" (sequence-node [target-node (raw ".NextBytes(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Random#nextInt()" (sequence-node [target-node (raw ".NextInt()")]) "executable:javax.crypto.Cipher#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaCipher.GetInstance(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.crypto.Cipher#getMaxAllowedKeyLength(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaCipher.GetMaxAllowedKeyLength(") (sequence-node arguments ", ") (raw ")")]) ("executable:javax.crypto.Cipher#init(int,java.security.Key)" "executable:javax.crypto.Cipher#init(int,java.security.Key,java.security.spec.AlgorithmParameterSpec)") (sequence-node [target-node (raw ".Init(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.crypto.Cipher#update(byte[],int,int)" (sequence-node [target-node (raw ".Update(") (sequence-node arguments ", ") (raw ")")]) ("executable:javax.crypto.Cipher#doFinal()" "executable:javax.crypto.Cipher#doFinal(byte[])") (sequence-node [target-node (raw ".DoFinal(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.ServerSocketFactory#createServerSocket(int)" (sequence-node [target-node (raw ".CreateServerSocket(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.SocketFactory#createSocket()" (sequence-node [target-node (raw ".CreateSocket()")]) "executable:javax.net.SocketFactory#createSocket(java.lang.String,int)" (sequence-node [target-node (raw ".CreateSocket(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.OutputStream#flush()" (sequence-node [target-node (raw ".Flush()")]) "executable:java.io.FilterOutputStream#flush()" (sequence-node [target-node (raw ".Flush()")]) ("executable:java.io.OutputStream#close()" "executable:java.io.FilterOutputStream#close()") (sequence-node [target-node (raw ".Dispose()")]) ("executable:java.io.Closeable#close()" "executable:java.lang.AutoCloseable#close()") (sequence-node [target-node (raw ".Dispose()")]) "executable:java.io.InputStream#close()" (sequence-node [target-node (raw ".Dispose()")]) ("executable:java.io.InputStream#available()" "executable:java.io.ByteArrayInputStream#available()") (compat-call "InputStreamAvailable" [target-node]) "executable:java.io.File#toPath()" (sequence-node [(raw "new global::DripSharp.Runtime.JavaPath(") target-node (raw ".FullName)")]) "executable:java.io.File#length()" (sequence-node [target-node (raw ".Length")]) "executable:java.io.File#delete()" (compat-call "FileDelete" [target-node]) "executable:java.io.File#exists()" (compat-call "FileExists" [target-node]) "executable:java.io.File#getAbsolutePath()" (sequence-node [target-node (raw ".FullName")]) "executable:java.io.File#isDirectory()" (compat-call "FileIsDirectory" [target-node]) "executable:java.io.File#setReadable(boolean,boolean)" (compat-call "SetFileReadable" (into [target-node] arguments)) "executable:java.io.File#setWritable(boolean,boolean)" (compat-call "SetFileWritable" (into [target-node] arguments)) "executable:java.io.File#setExecutable(boolean,boolean)" (compat-call "SetFileExecutable" (into [target-node] arguments)) "executable:java.io.RandomAccessFile#close()" (sequence-node [target-node (raw ".Dispose()")]) "executable:java.nio.file.Files#newInputStream(java.nio.file.Path,java.nio.file.OpenOption[])" (compat-call "OpenInputStream" arguments) "executable:java.nio.file.Path#toFile()" (sequence-node [(raw "new global::System.IO.FileInfo(") target-node (raw ")")]) "executable:java.io.ByteArrayOutputStream#writeTo(java.io.OutputStream)" (compat-call "MemoryStreamWriteTo" (into [target-node] arguments)) "executable:java.io.ByteArrayOutputStream#toByteArray()" (compat-call "ToSignedBytes" [target-node]) "executable:java.io.ByteArrayOutputStream#size()" (sequence-node [(raw "checked((int)") target-node (raw ".Length)")]) "executable:java.io.ByteArrayOutputStream#write(int)" (compat-call "OutputStreamWrite" (into [target-node] arguments)) "executable:java.io.OutputStream#write(byte[])" (compat-call "OutputStreamWrite" (into [target-node] arguments)) "executable:java.io.OutputStream#write(byte[],int,int)" (compat-call "OutputStreamWrite" (into [target-node] arguments)) "executable:java.io.OutputStream#write(int)" (compat-call "OutputStreamWrite" (into [target-node] arguments)) "executable:java.io.PrintStream#println(java.lang.String)" (sequence-node [target-node (raw ".WriteLine(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.PipedOutputStream#connect(java.io.PipedInputStream)" (sequence-node [target-node (raw ".Connect(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.PipedOutputStream#write(byte[],int,int)" (compat-call "OutputStreamWrite" (into [target-node] arguments)) "executable:java.io.PipedOutputStream#close()" (sequence-node [target-node (raw ".Dispose()")]) "executable:java.io.PipedOutputStream#flush()" (sequence-node [target-node (raw ".Flush()")]) "executable:java.io.InputStream#read()" (compat-call "InputStreamRead" [target-node]) ("executable:java.io.InputStream#read(byte[])" "executable:java.io.BufferedInputStream#read(byte[])") (compat-call "InputStreamRead" (into [target-node] arguments)) ("executable:java.io.InputStream#read(byte[],int,int)" "executable:java.io.BufferedInputStream#read(byte[],int,int)") (compat-call "InputStreamRead" (into [target-node] arguments)) ("executable:java.io.InputStream#readAllBytes()" "executable:java.security.DigestInputStream#readAllBytes()") (compat-call "ReadAllBytes" [target-node]) "executable:java.io.InputStream#readNBytes(int)" (compat-call "ReadNBytes" (into [target-node] arguments)) ("executable:java.security.DigestInputStream#getMessageDigest()" "executable:java.security.DigestOutputStream#getMessageDigest()") (sequence-node [target-node (raw ".GetMessageDigest()")]) ("executable:java.io.FilterInputStream#read()" "executable:java.io.FilterInputStream#read(byte[])" "executable:java.io.FilterInputStream#read(byte[],int,int)") (if (instance? CtSuperAccess target) (sequence-node [(raw "base.Read(") (sequence-node arguments ", ") (raw ")")]) (compat-call "InputStreamRead" (into [target-node] arguments))) "executable:java.io.FilterInputStream#skip(long)" (sequence-node [target-node (raw ".Skip(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.ByteArrayInputStream#read()" (compat-call "InputStreamRead" [target-node]) "executable:java.io.PushbackInputStream#read()" (compat-call "InputStreamRead" [target-node]) "executable:java.io.PushbackInputStream#unread(int)" (sequence-node [target-node (raw ".Unread(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.PushbackInputStream#unread(byte[],int,int)" (sequence-node [target-node (raw ".Unread(") (sequence-node arguments ", ") (raw ")")]) "executable:java.io.PushbackInputStream#close()" (sequence-node [target-node (raw ".Close()")]) "executable:java.util.zip.GZIPInputStream#read(byte[],int,int)" (compat-call "InputStreamRead" (into [target-node] arguments)) "executable:java.lang.StringBuilder#append(java.lang.String)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StringBuilder#append(char)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StringBuilder#append(int)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.lang.StringBuilder#append(long)" "executable:java.lang.StringBuilder#append(float)" "executable:java.lang.StringBuilder#append(double)" "executable:java.lang.StringBuilder#append(boolean)") (compat-call "StringBuilderAppendInvariant" (into [target-node] arguments)) ("executable:java.lang.StringBuilder#insert(int,char)" "executable:java.lang.StringBuilder#insert(int,java.lang.String)") (sequence-node [target-node (raw ".Insert(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StringBuilder#appendCodePoint(int)" (compat-call "AppendCodePoint" (into [target-node] arguments)) "executable:java.lang.StringBuilder#length()" (sequence-node [target-node (raw ".Length")]) "executable:java.lang.AbstractStringBuilder#length()" (sequence-node [target-node (raw ".Length")]) ("executable:java.lang.StringBuilder#substring(int,int)" "executable:java.lang.AbstractStringBuilder#substring(int,int)") (compat-call "StringSubstring" (into [(sequence-node [target-node (raw ".ToString()")])] arguments)) "executable:java.lang.StringBuilder#toString()" (sequence-node [target-node (raw ".ToString()")]) "executable:java.util.Collection#stream()" target-node "executable:java.lang.Object#getClass()" (sequence-node [(raw "((object)(") target-node (raw ")).GetType()")]) "executable:java.lang.Class#forName(java.lang.String)" (compat-call "ClassForName" arguments) "executable:java.lang.Class#getDeclaredField(java.lang.String)" (compat-call "GetDeclaredField" (into [target-node] arguments)) "executable:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])" (compat-call "GetMethod" (into [target-node] arguments)) ("executable:java.lang.Class#getName()" "executable:java.lang.Class#getTypeName()") (sequence-node [(raw "(") target-node (raw ".FullName ?? ") target-node (raw ".Name)")]) "executable:java.lang.Class#getSimpleName()" (sequence-node [target-node (raw ".Name")]) "executable:java.lang.Class#isInstance(java.lang.Object)" (sequence-node [target-node (raw ".IsInstanceOfType(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Class#isAssignableFrom(java.lang.Class)" (sequence-node [target-node (raw ".IsAssignableFrom(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Class#getClassLoader()" (sequence-node [target-node (raw ".Assembly")]) "executable:java.lang.ClassLoader#getResource(java.lang.String)" (compat-call "ClassGetResource" (into [target-node] arguments)) "executable:java.lang.ClassLoader#getResourceAsStream(java.lang.String)" (compat-call "ClassGetResourceAsStream" (into [target-node] arguments)) "executable:java.lang.Throwable#getCause()" (sequence-node [(compat-call "GetCause" [target-node]) (when (empty? (.getTypeCasts element)) (raw "!"))]) "executable:java.lang.Throwable#initCause(java.lang.Throwable)" (compat-call "InitCause" (into [target-node] arguments)) "executable:java.lang.Throwable#getMessage()" (sequence-node [target-node (raw ".Message")]) "executable:java.lang.Throwable#toString()" (compat-call "ExceptionToString" [target-node]) "executable:java.lang.Throwable#getStackTrace()" (compat-call "GetStackTrace" [target-node]) "executable:java.lang.Throwable#setStackTrace(java.lang.StackTraceElement[])" (compat-call "SetStackTrace" (into [target-node] arguments)) ("executable:java.net.URISyntaxException#getMessage()" "executable:java.util.regex.PatternSyntaxException#getMessage()") (sequence-node [target-node (raw ".Message")]) "executable:java.net.URISyntaxException#getInput()" (compat-call "UriSyntaxInput" [target-node]) "executable:java.net.URISyntaxException#getReason()" (compat-call "UriSyntaxReason" [target-node]) "executable:java.net.URISyntaxException#getIndex()" (compat-call "UriSyntaxIndex" [target-node]) ("executable:java.lang.Throwable#printStackTrace()" "executable:java.lang.Throwable#printStackTrace(java.io.PrintWriter)") (compat-call "PrintStackTrace" (into [target-node] arguments)) ("executable:java.time.Duration#ofSeconds(long)" "executable:java.time.Duration#ofSeconds(long,long)") (compat-call "DurationOfSeconds" arguments) "executable:java.time.Duration#toMillis()" (sequence-node [(raw "checked((long)") target-node (raw ".TotalMilliseconds)")]) "executable:java.time.Duration#getSeconds()" (compat-call "DurationGetSeconds" [target-node]) "executable:java.time.Duration#getNano()" (compat-call "DurationGetNano" [target-node]) "executable:java.time.Instant#now()" (raw "global::System.DateTimeOffset.UtcNow") "executable:java.time.Instant#plus(java.time.temporal.TemporalAmount)" (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")]) "executable:java.time.Instant#isBefore(java.time.Instant)" (sequence-node [(raw "(") target-node (raw " < ") (sequence-node arguments ", ") (raw ")")]) "executable:java.time.ZonedDateTime#now(java.time.ZoneId)" (raw "global::System.DateTimeOffset.UtcNow") "executable:java.time.ZonedDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)" (compat-call "ParseZonedDateTime" arguments) "executable:java.time.LocalDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)" (compat-call "ParseLocalDateTime" arguments) "executable:java.time.LocalDateTime#of(int,java.time.Month,int,int,int)" (sequence-node [(raw "new global::System.DateTime(") (sequence-node (conj arguments (raw "0")) ", ") (raw ")")]) "executable:java.time.LocalDateTime#atZone(java.time.ZoneId)" (compat-call "LocalDateTimeAtZone" (into [target-node] arguments)) "executable:java.time.ZoneId#of(java.lang.String)" (compat-call "ZoneIdOf" arguments) "executable:java.time.format.DateTimeFormatter#format(java.time.temporal.TemporalAccessor)" (sequence-node [target-node (raw ".Format(") (sequence-node arguments ", ") (raw ")")]) "executable:java.time.format.DateTimeFormatterBuilder#parseCaseInsensitive()" (sequence-node [target-node (raw ".ParseCaseInsensitive()")]) "executable:java.time.format.DateTimeFormatterBuilder#append(java.time.format.DateTimeFormatter)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.time.format.DateTimeFormatterBuilder#parseLenient()" (sequence-node [target-node (raw ".ParseLenient()")]) "executable:java.time.format.DateTimeFormatterBuilder#appendOffset(java.lang.String,java.lang.String)" (sequence-node [target-node (raw ".AppendOffset(") (sequence-node arguments ", ") (raw ")")]) "executable:java.time.format.DateTimeFormatterBuilder#parseStrict()" (sequence-node [target-node (raw ".ParseStrict()")]) "executable:java.time.format.DateTimeFormatterBuilder#toFormatter()" (sequence-node [target-node (raw ".ToFormatter()")]) "executable:java.net.InetAddress#getLoopbackAddress()" (raw "global::System.Net.IPAddress.Loopback") "executable:java.net.InetAddress#getByName(java.lang.String)" (compat-call "GetByName" arguments) "executable:java.net.InetAddress#getAddress()" (compat-call "GetAddressBytes" [target-node]) "executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)" (compat-call "Equals" arguments) "executable:java.util.Objects#hashCode(java.lang.Object)" (compat-call "HashCode" arguments) "executable:java.util.Objects#hash(java.lang.Object[])" (compat-call "Hash" arguments) "executable:java.util.Objects#requireNonNull(java.lang.Object)" (compat-call "RequireNonNull" arguments) "executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)" (compat-call "RequireNonNull" arguments) "executable:java.util.Map#entry(java.lang.Object,java.lang.Object)" (compat-call "MapEntry" arguments) "executable:java.util.Map#entrySet()" (compat-call "MapEntrySet" [target-node]) ("executable:java.util.Map#containsKey(java.lang.Object)" "executable:java.util.TreeMap#containsKey(java.lang.Object)") (compat-call "MapContainsKey" (into [target-node] arguments)) "executable:java.util.Map#containsValue(java.lang.Object)" (compat-call "MapContainsValue" (into [target-node] arguments)) "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)" (compat-call "ComputeIfAbsent" (into [target-node] arguments)) "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)" (compat-call "ComputeIfAbsent" (into [target-node] arguments)) "executable:java.util.TreeMap#computeIfAbsent(java.lang.Object,java.util.function.Function)" (compat-call "ComputeIfAbsent" (into [target-node] arguments)) "executable:java.util.Map#forEach(java.util.function.BiConsumer)" (compat-call "ForEach" (into [target-node] arguments)) "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)" (compat-call "MapGetOrDefault" (into [target-node] arguments)) "executable:java.util.Map#merge(java.lang.Object,java.lang.Object,java.util.function.BiFunction)" (compat-call "MapMerge" (into [target-node] arguments)) ("executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)" "executable:java.util.concurrent.ConcurrentMap#putIfAbsent(java.lang.Object,java.lang.Object)") (compat-call "MapPutIfAbsent" (into [target-node] arguments)) "executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)" (compat-call "MapPutIfAbsent" (into [target-node] arguments)) "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)" (compat-call "MapGetOrDefault" (into [target-node] arguments)) ("executable:java.util.Map#keySet()" "executable:java.util.TreeMap#keySet()") (compat-call "MapKeySet" [target-node]) "executable:java.util.LinkedHashMap#keySet()" (compat-call "MapKeySet" [target-node]) ("executable:java.util.Map#values()" "executable:java.util.SortedMap#values()" "executable:java.util.TreeMap#values()") (sequence-node [target-node (raw ".Values")]) ("executable:java.util.Map#clear()" "executable:java.util.HashMap#clear()" "executable:java.util.TreeMap#clear()") (sequence-node [target-node (raw ".Clear()")]) ("executable:java.util.Map#put(java.lang.Object,java.lang.Object)" "executable:java.util.TreeMap#put(java.lang.Object,java.lang.Object)") (compat-call "MapPut" (into [target-node] arguments)) "executable:java.util.Map#putAll(java.util.Map)" (compat-call "MapPutAll" (into [target-node] arguments)) "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)" (compat-call "MapPut" (into [target-node] arguments)) "executable:java.util.HashMap#putAll(java.util.Map)" (compat-call "MapPutAll" (into [target-node] arguments)) "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)" (compat-call "MapPut" (into [target-node] arguments)) ("executable:java.util.Map#size()" "executable:java.util.TreeMap#size()") (compat-call "MapCount" [target-node]) "executable:java.util.HashMap#size()" (sequence-node [target-node (raw ".Count")]) ("executable:java.util.Map#get(java.lang.Object)" "executable:java.util.TreeMap#get(java.lang.Object)") (compat-call (if (boxed-primitive-reference? (.getType element)) "MapGetNullable" "MapGet") (into [target-node] arguments)) ("executable:java.util.Map#remove(java.lang.Object)" "executable:java.util.TreeMap#remove(java.lang.Object)") (compat-call "MapRemove" (into [target-node] arguments)) "executable:java.util.HashMap#remove(java.lang.Object)" (compat-call "MapRemove" (into [target-node] arguments)) "executable:java.util.LinkedHashMap#remove(java.lang.Object)" (compat-call "MapRemove" (into [target-node] arguments)) "executable:java.util.Map#hashCode()" (compat-call "HashCode" [target-node]) "executable:java.util.Map$Entry#getKey()" (sequence-node [target-node (raw ".Key")]) "executable:java.lang.Iterable#forEach(java.util.function.Consumer)" (compat-call "ForEach" (into [target-node] arguments)) "executable:java.util.Map$Entry#getValue()" (sequence-node [target-node (raw ".Value")]) "executable:java.util.Map$Entry#setValue(java.lang.Object)" (sequence-node [target-node (raw ".SetValue(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Map$Entry#comparingByValue()" (let [comparator-arguments (vec (.getActualTypeArguments (.getType element))) comparison (raw (str "(value0, value1) => " "global::DripSharp.Runtime.JavaCompat.CompareNatural(" "value0.Value, value1.Value)"))] (if (= 1 (count comparator-arguments)) (sequence-node [(raw "global::System.Collections.Generic.Comparer<") (type-node (clojure.core/deref ctx-holder) (first comparator-arguments)) (raw ">.Create(") comparison (raw ")")]) comparison)) "executable:java.util.List#isEmpty()" (compat-call "ListIsEmpty" [target-node]) "executable:java.util.ArrayList#isEmpty()" (compat-call "ListIsEmpty" [target-node]) ("executable:java.util.List#add(java.lang.Object)" "executable:java.util.ArrayList#add(java.lang.Object)" "executable:java.util.LinkedList#add(java.lang.Object)") (compat-call "Add" (into [target-node] arguments)) "executable:java.util.LinkedList#addFirst(java.lang.Object)" (compat-call "ListAddFirst" (into [target-node] arguments)) ("executable:java.util.Deque#addFirst(java.lang.Object)" "executable:java.util.ArrayDeque#addFirst(java.lang.Object)") (csharp/invocation (csharp/member target-node "AddFirst") arguments) ("executable:java.util.Collection#clear()" "executable:java.util.List#clear()" "executable:java.util.ArrayList#clear()" "executable:java.util.Set#clear()" "executable:java.util.HashSet#clear()") (sequence-node [target-node (raw ".Clear()")]) "executable:java.util.ArrayList#ensureCapacity(int)" (sequence-node [target-node (raw ".EnsureCapacity(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.List#remove(java.lang.Object)" (compat-call "CollectionRemove" (into [target-node] arguments)) "executable:java.util.List#removeIf(java.util.function.Predicate)" (compat-call "RemoveIf" (into [target-node] arguments)) "executable:java.util.Collection#removeIf(java.util.function.Predicate)" (compat-call "RemoveIf" (into [target-node] arguments)) "executable:java.util.List#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.util.List#hashCode()" (compat-call "HashCode" [target-node]) "executable:java.util.List#get(int)" (compat-call "ListGet" (into [target-node] arguments)) "executable:java.util.ArrayList#get(int)" (compat-call "ListGet" (into [target-node] arguments)) "executable:java.util.List#contains(java.lang.Object)" (compat-call "CollectionContains" (into [target-node] arguments)) "executable:java.util.List#size()" (compat-call "CollectionCount" [target-node]) "executable:java.util.ArrayList#size()" (compat-call "CollectionCount" [target-node]) "executable:java.util.ArrayList#remove(int)" (compat-call "ListRemove" (into [target-node] arguments)) ("executable:java.util.List#iterator()" "executable:java.util.AbstractSequentialList#iterator()") (compat-call "Iterator" [target-node]) "executable:java.util.List#listIterator()" (compat-call "ListIterator" [target-node]) "executable:java.util.List#listIterator(int)" (compat-call "ListIterator" (into [target-node] arguments)) ("executable:java.util.List#containsAll(java.util.Collection)" "executable:java.util.Collection#containsAll(java.util.Collection)" "executable:java.util.AbstractCollection#containsAll(java.util.Collection)" "executable:java.util.Set#containsAll(java.util.Collection)") (compat-call "ContainsAll" (into [target-node] arguments)) "executable:java.util.ListIterator#set(java.lang.Object)" (sequence-node [target-node (raw ".Set(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.ListIterator#hasPrevious()" (sequence-node [target-node (raw ".HasPrevious()")]) "executable:java.util.ListIterator#previous()" (sequence-node [target-node (raw ".Previous()")]) "executable:java.util.ListIterator#nextIndex()" (sequence-node [target-node (raw ".NextIndex()")]) "executable:java.util.ListIterator#previousIndex()" (sequence-node [target-node (raw ".PreviousIndex()")]) "executable:java.util.ListIterator#add(java.lang.Object)" (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)" (sequence-node [target-node (raw ".Compare(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.Iterator#next()" "executable:java.util.ListIterator#next()" "executable:java.util.PrimitiveIterator$OfInt#nextInt()" "executable:java.util.PrimitiveIterator$OfLong#nextLong()") (sequence-node [target-node (raw ".Next()")]) ("executable:java.util.Iterator#hasNext()" "executable:java.util.ListIterator#hasNext()" "executable:java.util.PrimitiveIterator$OfInt#hasNext()" "executable:java.util.PrimitiveIterator$OfLong#hasNext()") (sequence-node [target-node (raw ".HasNext()")]) "executable:java.util.Iterator#forEachRemaining(java.util.function.Consumer)" (compat-call "ForEachRemaining" (into [target-node] arguments)) ("executable:java.util.Iterator#remove()" "executable:java.util.ListIterator#remove()") (sequence-node [target-node (raw ".Remove()")]) "executable:java.util.Collection#remove(java.lang.Object)" (compat-call "CollectionRemove" (into [target-node] arguments)) ("executable:java.util.Collection#toArray()" "executable:java.util.ArrayList#toArray()" "executable:java.util.List#toArray()") (compat-call "ToObjectArray" [target-node]) ("executable:java.util.Collection#toArray(java.lang.Object[])" "executable:java.util.ArrayList#toArray(java.lang.Object[])" "executable:java.util.List#toArray(java.lang.Object[])" "executable:java.util.Set#toArray(java.lang.Object[])") (compat-call "CollectionToArray" (into [target-node] arguments)) "executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)" (sequence-node [target-node (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.function.BiFunction#apply(java.lang.Object,java.lang.Object)" (sequence-node [target-node (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.function.Consumer#accept(java.lang.Object)" (sequence-node [target-node (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.function.Supplier#get()" (sequence-node [target-node (raw "()")]) "executable:java.util.Comparator#reverseOrder()" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ReverseComparer") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "()")]) "executable:java.util.EnumSet#of(java.lang.Enum)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetOf") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.Collection#contains(java.lang.Object)" "executable:java.util.AbstractCollection#contains(java.lang.Object)" "executable:java.util.Set#contains(java.lang.Object)") (compat-call "CollectionContains" (into [target-node] arguments)) "executable:java.util.HashSet#contains(java.lang.Object)" (sequence-node [target-node (raw ".Contains(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Set#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.util.Set#hashCode()" (compat-call "HashCode" [target-node]) ("executable:java.util.Set#add(java.lang.Object)" "executable:java.util.AbstractCollection#add(java.lang.Object)" "executable:java.util.HashSet#add(java.lang.Object)" "executable:java.util.TreeSet#add(java.lang.Object)") (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.Set#removeAll(java.util.Collection)" "executable:java.util.AbstractSet#removeAll(java.util.Collection)") (compat-call "RemoveAll" (into [target-node] arguments)) ("executable:java.util.Set#remove(java.lang.Object)" "executable:java.util.HashSet#remove(java.lang.Object)") (sequence-node [target-node (raw ".Remove(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.regex.Pattern#compile(java.lang.String)" (compat-call "CompileRegex" arguments) "executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)" (compat-call "RegexMatcher" (into [target-node] arguments)) "executable:java.util.regex.Pattern#split(java.lang.CharSequence)" (compat-call "RegexSplit" (into [target-node] (conj arguments (raw "0")))) "executable:java.util.regex.Matcher#matches()" (sequence-node [target-node (raw ".Matches()")]) "executable:java.util.stream.Stream#of(java.lang.Object[])" (compat-call "StreamOf" arguments) "executable:java.util.stream.Stream#filter(java.util.function.Predicate)" (compat-call "StreamFilter" (into [target-node] arguments)) ("executable:java.util.stream.Stream#sorted()" "executable:java.util.stream.Stream#sorted(java.util.Comparator)") (compat-call "StreamSorted" (into [target-node] arguments)) ("executable:java.util.stream.Stream#forEach(java.util.function.Consumer)" "executable:java.util.stream.Stream#forEachOrdered(java.util.function.Consumer)") (compat-call "ForEach" (into [target-node] arguments)) "executable:java.util.stream.Stream#toArray(java.util.function.IntFunction)" (compat-call "ToArray" [target-node]) ("executable:java.util.ServiceLoader#load(java.lang.Class)" "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.LoadServices") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.stream.Stream#flatMap(java.util.function.Function)" (compat-call "FlatMap" (into [target-node] arguments)) "executable:java.util.stream.Stream#map(java.util.function.Function)" (compat-call "Map" (into [target-node] arguments)) "executable:java.util.stream.Stream#mapToInt(java.util.function.ToIntFunction)" (compat-call "Map" (into [target-node] arguments)) "executable:java.util.stream.Stream#mapToLong(java.util.function.ToLongFunction)" (compat-call "MapToLong" (into [target-node] arguments)) "executable:java.util.stream.LongStream#sum()" (compat-call "Sum" [target-node]) "executable:java.util.stream.Collectors#toList()" (raw "global::DripSharp.Runtime.JavaCompat.ToList<object>()") "executable:java.util.stream.Collectors#toSet()" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ToSet") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "()")]) "executable:java.util.stream.Collectors#toCollection(java.util.function.Supplier)" (compat-call "ToCollection" arguments) "executable:java.util.stream.Stream#collect(java.util.stream.Collector)" (case (some-> element .getType .getQualifiedName) "java.lang.String" (compat-call "Collect" (into [target-node] arguments)) "java.util.Map" (compat-call "Collect" (into [target-node] arguments)) "java.util.Set" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.SetOfValues") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "(") target-node (raw ")")]) "java.util.HashSet" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.SetOfValues") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "(") target-node (raw ")")]) "java.util.LinkedHashSet" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.SetOfValues") [(type-node (clojure.core/deref ctx-holder) (collection-element-type element))]) (raw "(") target-node (raw ")")]) "java.util.ArrayList" (sequence-node [(raw "new ") (type-node (clojure.core/deref ctx-holder) (.getType element)) (raw "(") target-node (raw ")")]) (compat-call "ToListValues" [target-node])) "executable:java.util.concurrent.ExecutorService#submit(java.lang.Runnable)" (sequence-node [target-node (raw ".Submit(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)" (sequence-node [target-node (raw ".Submit(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.ExecutorService#shutdown()" (sequence-node [target-node (raw ".Shutdown()")]) "executable:java.util.concurrent.ExecutorService#shutdownNow()" (sequence-node [target-node (raw ".ShutdownNow()")]) "executable:java.util.concurrent.ExecutorService#awaitTermination(long,java.util.concurrent.TimeUnit)" (sequence-node [target-node (raw ".AwaitTermination(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.Executors#newFixedThreadPool(int,java.util.concurrent.ThreadFactory)" (sequence-node [(raw "new global::DripSharp.Runtime.JavaExecutorService(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.Executors#newSingleThreadExecutor()" (raw "new global::DripSharp.Runtime.JavaExecutorService(1)") "executable:java.util.concurrent.Future#get(long,java.util.concurrent.TimeUnit)" (sequence-node [target-node (raw ".Get(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.atomic.AtomicBoolean#get()" (sequence-node [target-node (raw ".Get()")]) "executable:java.util.concurrent.atomic.AtomicBoolean#getAndSet(boolean)" (sequence-node [target-node (raw ".GetAndSet(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.atomic.AtomicBoolean#compareAndSet(boolean,boolean)" (sequence-node [target-node (raw ".CompareAndSet(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.atomic.AtomicInteger#incrementAndGet()" (sequence-node [target-node (raw ".IncrementAndGet()")]) "executable:java.util.concurrent.atomic.AtomicReference#get()" (sequence-node [target-node (raw ".Get()") (when-not (statement-expression? element) (raw "!"))]) "executable:java.util.concurrent.atomic.AtomicReference#getAndSet(java.lang.Object)" (sequence-node [target-node (raw ".GetAndSet(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)" (sequence-node [target-node (raw ".Set(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Thread#setDaemon(boolean)" (sequence-node [target-node (raw ".SetDaemon(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Thread#setName(java.lang.String)" (sequence-node [target-node (raw ".SetName(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Thread#start()" (sequence-node [target-node (raw ".Start()")]) "executable:java.lang.Thread#currentThread()" (raw "global::DripSharp.Runtime.JavaThread.CurrentThread()") "executable:java.lang.Thread#interrupt()" (sequence-node [target-node (raw ".Interrupt()")]) "executable:java.lang.Thread#sleep(long)" (sequence-node [(raw "global::DripSharp.Runtime.JavaThread.Sleep(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.lang.Object#<init>()" "executable:java.lang.Record#<init>()" "executable:java.lang.Enum#<init>(java.lang.String,int)" "executable:java.io.InputStream#<init>()" "executable:java.io.OutputStream#<init>()" "executable:java.io.Writer#<init>()" "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)" "executable:java.io.FilterInputStream#<init>(java.io.InputStream)") (raw "") (sequence-node [(when target (sequence-node [default-target-node (raw ".")])) (child-node children (.getExecutable element)) (when (= :project (:origin occurrence)) (project-invocation-type-arguments-node (clojure.core/deref ctx-holder) element declaration)) (raw "(") (sequence-node arguments ", ") (raw ")")]))))
             raw-node
             (if (and (= :project (:origin occurrence))
                      (instance? CtMethod declaration)
                      (covariant-value-override?
                       (.getDeclaringType ^CtMethod declaration)
                       declaration))
               (sequence-node
                [(raw "(")
                 (type-node @ctx-holder (.getType ^CtMethod declaration))
                 (raw ")(") raw-node (raw ")")])
               raw-node)
             raw-node
             (if (= :project (:origin occurrence))
               (normalize-redundant-static-casts raw-node)
               raw-node)
             raw-node
             (if (and
                  (nullable-declaration? @ctx-holder
                                         (:declaration occurrence))
                  (not (statement-expression? element))
                  (empty? (.getTypeCasts element)))
               (null-forgiven-node raw-node)
               raw-node)
             node (expression-cast-node @ctx-holder element raw-node)]
         {:node
          (if (or (= :constructor (:kind occurrence))
                  (contains? #{"executable:java.lang.Object#<init>()"
                               "executable:java.lang.Record#<init>()"
                               "executable:java.lang.Enum#<init>(java.lang.String,int)"
                               "executable:java.io.InputStream#<init>()"
                               "executable:java.io.OutputStream#<init>()"
                               "executable:java.io.Writer#<init>()"
                               "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"
                               "executable:java.io.FilterInputStream#<init>(java.io.InputStream)"}
                             (:key occurrence)))
            node
            (expression-statement-node element node))}))}

    {:id :java-library.expression/constructor-call
     :class CtConstructorCall
     :emit
     (fn [{:keys [context ^CtConstructorCall element children]}]
       (let [occurrence (constructor-occurrence context element)
             declaration (:declaration occurrence)
             parameter-types
             (constructor-call-parameter-types declaration element)
             arguments
             (mapv
              (fn [index ^CtExpression argument]
                (let [expected
                      (when (seq parameter-types)
                        (nth parameter-types
                             (min index (dec (count parameter-types)))))]
                  (argument-value-node
                   @ctx-holder argument expected
                   (child-node children argument) false)))
              (range)
              (.getArguments element))
             destination-outer-argument
             (when-let [adapt
                        (get-in @ctx-holder
                                [:services
                                 :named-inner-constructor-argument])]
               (adapt element))
             arguments
             (cond
               destination-outer-argument
               (into [destination-outer-argument] arguments)

               (member-constructor? (:declaration occurrence))
               (conj arguments
                     (if-let [target (.getTarget element)]
                       (child-node children target)
                       (raw
                        (if (:outer-type @ctx-holder)
                          (str "this."
                               (or (:outer-field-name @ctx-holder)
                                   "__outer"))
                          "this"))))

               :else arguments)
             anonymous-class (anonymous-class-for-call element)
             owner (when anonymous-class (nearest-enclosing-type element))
             captures (when anonymous-class (anonymous-captures anonymous-class))
             outer? (when anonymous-class
                      (anonymous-uses-outer? anonymous-class owner))
             destination-adaptation
             (or
              (when-let [adaptation
                         (get (:destination-constructor-adaptations @ctx-holder)
                              (:key occurrence))]
                (adaptation arguments))
              (when-let [adapt (:destination-constructor-adapter @ctx-holder)]
                (adapt
                 {:destination-context @ctx-holder
                  :element element
                  :occurrence occurrence
                  :arguments arguments})))]
         {:node
          (expression-statement-node
           element
           (cond
             anonymous-class
             (sequence-node
              [(raw (str "new " (anonymous-class-name element) "("))
               (sequence-node
                (vec
                 (concat arguments
                         (when outer? [(raw "this")])
                         (map #(raw (local-declaration-name %))
                              captures)))
                ", ")
               (raw ")")])

             :else
             (if destination-adaptation
               destination-adaptation
               (case (:key occurrence)
                 ("executable:java.lang.Throwable#<init>()"
                  "executable:java.lang.Exception#<init>()")
                 (raw "global::DripSharp.Runtime.JavaCompat.NewThrowable()")

                 ("executable:java.lang.Exception#<init>(java.lang.Throwable)"
                  "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)")
                 (sequence-node [(raw "new global::System.Exception(null, ")
                                 (first arguments) (raw ")")])

                 "executable:java.lang.ExceptionInInitializerError#<init>(java.lang.Throwable)"
                 (compat-call "NewTypeInitializationException" arguments)

                 "executable:java.lang.IllegalArgumentException#<init>(java.lang.Throwable)"
                 (sequence-node [(raw "new global::System.ArgumentException(null, ")
                                 (first arguments) (raw ")")])

                 "executable:java.io.IOException#<init>(java.lang.Throwable)"
                 (sequence-node [(raw "new global::System.IO.IOException(null, ")
                                 (first arguments) (raw ")")])

                 "executable:java.io.FileNotFoundException#<init>()"
                 (compat-call "NewFileNotFoundException" [])

                 "executable:java.awt.print.PrinterIOException#<init>(java.io.IOException)"
                 (sequence-node [(raw "new global::System.IO.IOException(null, ")
                                 (first arguments) (raw ")")])

                 "executable:java.net.Socket#<init>(java.lang.String,int)"
                 (sequence-node
                  [(raw "global::DripSharp.Runtime.JavaSocketFactory.Plain.CreateSocket(")
                   (sequence-node arguments ", ") (raw ")")])

                 "executable:java.net.Socket#<init>(java.net.InetAddress,int)"
                 (sequence-node
                  [(raw "global::DripSharp.Runtime.JavaSocketFactory.Plain.CreateSocket(")
                   (sequence-node arguments ", ") (raw ")")])

                 ("executable:java.net.URISyntaxException#<init>(java.lang.String,java.lang.String)"
                  "executable:java.net.URISyntaxException#<init>(java.lang.String,java.lang.String,int)")
                 (compat-call "NewUriSyntaxException" arguments)

                 "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)"
                 (compat-call "NewUri" arguments)

                 "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String)"
                 (compat-call "NewUri" arguments)

                 "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String)"
                 (compat-call "NewUri" arguments)

                 "executable:java.net.URI#<init>(java.lang.String)"
                 (compat-call "NewUri" arguments)

                 "executable:java.io.File#<init>(java.net.URI)"
                 (compat-call "NewFileInfo" arguments)

                 "executable:java.io.File#<init>(java.lang.String,java.lang.String)"
                 (compat-call "NewFileInfo" arguments)

                 "executable:java.io.ByteArrayInputStream#<init>(byte[])"
                 (sequence-node
                  [(raw "global::DripSharp.Runtime.JavaCompat.NewMemoryStream(")
                   (sequence-node arguments ", ") (raw ")")])

                 "executable:java.io.ByteArrayInputStream#<init>(byte[],int,int)"
                 (sequence-node
                  [(raw "global::DripSharp.Runtime.JavaCompat.NewMemoryStream(")
                   (sequence-node arguments ", ") (raw ")")])

                 ("executable:java.io.FileInputStream#<init>(java.io.File)"
                  "executable:java.io.FileInputStream#<init>(java.lang.String)")
                 (compat-call "OpenFileInput" arguments)

                 "executable:java.io.FileReader#<init>(java.io.File)"
                 (compat-call "OpenFileReader" arguments)

                 ("executable:java.io.FileOutputStream#<init>(java.io.File)"
                  "executable:java.io.FileOutputStream#<init>(java.lang.String)")
                 (compat-call "OpenFileOutput" arguments)

                 "executable:java.io.FileWriter#<init>(java.io.File,java.nio.charset.Charset)"
                 (compat-call "NewFileWriter" arguments)

                 ("executable:java.io.BufferedReader#<init>(java.io.Reader)"
                  "executable:java.io.BufferedWriter#<init>(java.io.Writer)"
                  "executable:java.io.PrintWriter#<init>(java.io.Writer)")
                 (first arguments)

                 "executable:java.io.SequenceInputStream#<init>(java.io.InputStream,java.io.InputStream)"
                 (sequence-node
                  [(raw "new global::DripSharp.Runtime.JavaSequenceInputStream(")
                   (sequence-node arguments ", ") (raw ")")])

                 "executable:java.math.BigInteger#<init>(int,byte[])"
                 (compat-call "NewBigInteger" arguments)

                 "executable:java.math.BigDecimal#<init>(int)"
                 (sequence-node
                  [(raw "new decimal(")
                   (sequence-node arguments ", ") (raw ")")])

                 "executable:java.math.BigDecimal#<init>(java.lang.String)"
                 (compat-call "BigDecimalParse" arguments)

                 "executable:java.util.zip.GZIPInputStream#<init>(java.io.InputStream)"
                 (sequence-node
                  [(raw "new global::System.IO.Compression.GZipStream(")
                   (sequence-node arguments ", ")
                   (raw ", global::System.IO.Compression.CompressionMode.Decompress)")])

                 ("executable:java.lang.String#<init>(byte[],java.nio.charset.Charset)"
                  "executable:java.lang.String#<init>(byte[],int,int,java.nio.charset.Charset)")
                 (compat-call "NewString" arguments)

                 "executable:java.lang.String#<init>(int[],int,int)"
                 (compat-call "NewString" arguments)

                 "executable:java.util.GregorianCalendar#<init>()"
                 (raw "global::System.DateTimeOffset.Now")

                 "executable:java.util.GregorianCalendar#<init>(java.util.TimeZone)"
                 (compat-call "CalendarInstance" arguments)

                 "executable:java.text.DecimalFormat#<init>()"
                 (raw "new global::DripSharp.Runtime.JavaDecimalFormat()")

                 "executable:java.text.DecimalFormat#<init>(java.lang.String,java.text.DecimalFormatSymbols)"
                 (sequence-node
                  [(raw "new global::DripSharp.Runtime.JavaDecimalFormat(")
                   (sequence-node arguments ", ") (raw ")")])

                 "executable:java.util.SimpleTimeZone#<init>(int,java.lang.String)"
                 (compat-call "NewSimpleTimeZone" arguments)

                 ("executable:javax.xml.namespace.QName#<init>(java.lang.String)"
                  "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String)"
                  "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String,java.lang.String)")
                 (compat-call "NewXmlQualifiedName" arguments)

                 ("executable:javax.xml.transform.dom.DOMSource#<init>(org.w3c.dom.Node)"
                  "executable:javax.xml.transform.stream.StreamResult#<init>(java.io.OutputStream)")
                 (first arguments)

                 "executable:javax.xml.transform.stream.StreamResult#<init>(java.io.File)"
                 (compat-call "OpenFileOutput" arguments)

                 "executable:java.util.EnumMap#<init>(java.lang.Class)"
                 (sequence-node
                  [(raw "new ") (type-node @ctx-holder (.getType element))
                   (raw "()")])

                 ("executable:java.util.HashMap#<init>()"
                  "executable:java.util.HashMap#<init>(int)"
                  "executable:java.util.HashMap#<init>(int,float)"
                  "executable:java.util.HashMap#<init>(java.util.Map)")
                 (let [types
                       (vec (.getActualTypeArguments (.getType element)))
                       effective-arguments
                       (if (= "executable:java.util.HashMap#<init>(int,float)"
                              (:key occurrence))
                         [(first arguments)]
                         arguments)]
                   (if (= 2 (count types))
                     (sequence-node
                      [(raw "global::DripSharp.Runtime.JavaCompat.NewJavaDictionary<")
                       (type-node @ctx-holder (first types))
                       (raw ", ")
                       (type-node @ctx-holder (second types))
                       (raw ">(")
                       (sequence-node effective-arguments ", ")
                       (raw ")")])
                     (sequence-node
                      [(raw "new ") (type-node @ctx-holder (.getType element))
                       (raw "(")
                       (sequence-node effective-arguments ", ")
                       (raw ")")])))

                 "executable:java.util.concurrent.ConcurrentHashMap#<init>(int)"
                 (sequence-node
                  [(raw "new ") (type-node @ctx-holder (.getType element))
                   (raw "()")])

                 ("executable:java.util.HashSet#<init>(int,float)"
                  "executable:java.util.LinkedHashSet#<init>(int,float)"
                  "executable:java.util.LinkedHashMap#<init>(int,float)"
                  "executable:java.util.concurrent.ConcurrentHashMap#<init>(int,float)")
                 (sequence-node
                  [(raw "new ") (type-node @ctx-holder (.getType element))
                   (raw "(") (first arguments) (raw ")")])

                 "executable:java.util.TreeMap#<init>()"
                 (let [types
                       (vec (.getActualTypeArguments (.getType element)))]
                   (if (= 2 (count types))
                     (sequence-node
                      [(raw "global::DripSharp.Runtime.JavaCompat.NewSortedDictionary<")
                       (type-node @ctx-holder (first types))
                       (raw ", ")
                       (type-node @ctx-holder (second types))
                       (raw ">()")])
                     (sequence-node
                      [(raw "new ") (type-node @ctx-holder (.getType element))
                       (raw "()")])))

                 "executable:java.util.TreeSet#<init>()"
                 (let [types
                       (vec (.getActualTypeArguments (.getType element)))]
                   (if (= 1 (count types))
                     (sequence-node
                      [(raw "global::DripSharp.Runtime.JavaCompat.NewSortedSet<")
                       (type-node @ctx-holder (first types))
                       (raw ">()")])
                     (sequence-node
                      [(raw "new ") (type-node @ctx-holder (.getType element))
                       (raw "()")])))

                 ("executable:java.util.TreeMap#<init>(java.util.Comparator)"
                  "executable:java.util.TreeSet#<init>(java.util.Comparator)")
                 (sequence-node
                  [(raw "new ") (type-node @ctx-holder (.getType element))
                   (raw "(") (first arguments) (raw ")")])

                 (sequence-node
                  [(raw "new ") (type-node @ctx-holder (.getType element)) (raw "(")
                   (sequence-node arguments ", ")
                   (raw ")")])))))}))}

    {:id :java-library.expression/lambda
     :class CtLambda
     :emit
     (fn [{:keys [^CtLambda element children]}]
       (let [body (or (.getExpression element) (.getBody element))]
         {:node
          (functional-expression-node
           @ctx-holder element
           (sequence-node
            [(raw "(")
             (sequence-node
              (mapv #(child-node children %) (.getParameters element)) ", ")
             (raw ") => ")
             (child-node children body)]))}))}

    {:id :java-library.expression/method-reference
     :class CtExecutableReferenceExpression
     :emit
     (fn [{:keys [context ^CtExecutableReferenceExpression element children]}]
       (let [target-element (.getTarget element)
             target (child-node children target-element)
             executable (child-node children (.getExecutable element))
             occurrence (.get ^IdentityHashMap (:occurrence-index context)
                              (.getExecutable element))
             declaration (:declaration occurrence)
             constructor? (= :constructor (:kind occurrence))
             static? (and (instance? CtMethod declaration)
                          (.hasModifier ^CtMethod declaration ModifierKind/STATIC))
             parameter-count (count (.getParameters (.getExecutable element)))
             parameters (mapv #(raw (str "value" %)) (range parameter-count))
             functional-type (some-> element .getType .getQualifiedName)
             destination-method-reference?
             (:destination-method-reference? @ctx-holder)
             discards-result?
             (and (instance? CtMethod declaration)
                  (not= "void" (.getQualifiedName (.getType ^CtMethod declaration)))
                  (contains? #{"java.util.function.Consumer"
                               "java.util.function.BiConsumer"}
                             functional-type))]
         (when-not (or (and (= :project (:origin occurrence))
                            (or (instance? CtMethod declaration)
                                (instance? CtRecordComponent declaration)
                                (instance? CtConstructor declaration)))
                       constructor?
                       (and destination-method-reference?
                            (destination-method-reference?
                             {:context context
                              :destination-context @ctx-holder
                              :element element
                              :occurrence occurrence
                              :reference (.getExecutable element)
                              :target target-element}))
                       (contains?
                        #{"executable:java.lang.Class#cast(java.lang.Object)"
                          "executable:java.lang.Class#isInstance(java.lang.Object)"
                          "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
                          "executable:java.lang.Integer#sum(int,int)"
                          "executable:java.lang.Long#max(long,long)"
                          "executable:java.lang.Long#parseLong(java.lang.String,int)"
                          "executable:java.lang.Byte#parseByte(java.lang.String,int)"
                          "executable:java.net.URI#create(java.lang.String)"
                          "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])"
                          "executable:java.nio.file.Path#of(java.net.URI)"
                          "executable:java.nio.file.Files#isRegularFile(java.nio.file.Path,java.nio.file.LinkOption[])"
                          "executable:java.util.regex.Pattern#compile(java.lang.String)"
                          "executable:java.lang.Math#min(float,float)"
                          "executable:java.lang.Math#max(float,float)"
                          "executable:java.lang.Object#toString()"
                          "executable:java.util.List#add(java.lang.Object)"
                          "executable:java.util.ArrayList#add(java.lang.Object)"
                          "executable:java.util.List#remove(java.lang.Object)"
                          "executable:java.util.Map$Entry#getKey()"
                          "executable:java.util.Map$Entry#getValue()"
                          "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
                          "executable:java.util.Deque#add(java.lang.Object)"
                          "executable:java.util.Objects#nonNull(java.lang.Object)"
                          "executable:org.w3c.dom.Node#removeChild(org.w3c.dom.Node)"
                          "executable:java.util.StringJoiner#add(java.lang.CharSequence)"
                          "executable:java.util.List#of()"
                          "executable:java.util.ArrayList#<init>()"
                          "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)"}
                        (:key occurrence)))
           (unsupported! "Java library method reference requires a supported resolved method"
                         element))
         {:node
          (functional-expression-node
           @ctx-holder element
           (cond
             (= "executable:java.lang.Class#isInstance(java.lang.Object)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => ") target
               (raw ".IsInstanceOfType(value0)")])

             (= "executable:java.lang.Class#cast(java.lang.Object)"
                (:key occurrence))
             (let [result-type
                   (second (.getActualTypeArguments (.getType element)))]
               (sequence-node
                [(raw "(value0) => global::DripSharp.Runtime.JavaCompat.ClassCast<")
                 (type-node @ctx-holder result-type)
                 (raw ">(") target (raw ", value0)")]))

             (= "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase(")
               target (raw ", value0)")])

             (= "executable:java.lang.Integer#sum(int,int)" (:key occurrence))
             (raw "global::DripSharp.Runtime.JavaCompat.SumInt")

             (= "executable:java.lang.Long#max(long,long)" (:key occurrence))
             (raw "global::System.Math.Max")

             (= "executable:java.lang.Long#parseLong(java.lang.String,int)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0, value1) => ")
               (compat-call "ParseLong" [(raw "value0") (raw "value1")])])

             (= "executable:java.lang.Byte#parseByte(java.lang.String,int)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0, value1) => ")
               (compat-call "ParseByte" [(raw "value0") (raw "value1")])])

             (= "executable:java.net.URI#create(java.lang.String)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => ")
               (compat-call "CreateUri" [(raw "value0")])])

             (= "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => ")
               (compat-call "PathOf" [(raw "value0")])])

             (= "executable:java.nio.file.Path#of(java.net.URI)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => ")
               (compat-call "PathOfUri" [(raw "value0")])])

             (= "executable:java.nio.file.Files#isRegularFile(java.nio.file.Path,java.nio.file.LinkOption[])"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => ")
               (compat-call "PathIsRegularFile" [(raw "value0")])])

             (= "executable:java.util.regex.Pattern#compile(java.lang.String)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => ")
               (compat-call "CompileRegex" [(raw "value0")])])

             (= "executable:java.lang.Math#min(float,float)" (:key occurrence))
             (raw "global::System.MathF.Min")

             (= "executable:java.lang.Math#max(float,float)" (:key occurrence))
             (raw "global::System.MathF.Max")

             (= "executable:java.util.Map$Entry#getKey()" (:key occurrence))
             (raw "(value0) => value0.Key")

             (= "executable:java.util.Map$Entry#getValue()" (:key occurrence))
             (raw "(value0) => value0.Value")

             (contains? #{"executable:java.util.List#add(java.lang.Object)"
                          "executable:java.util.ArrayList#add(java.lang.Object)"}
                        (:key occurrence))
             (sequence-node
              [(raw "(value0) => { ") target (raw ".Add(value0); }")])

             (= "executable:java.util.List#remove(java.lang.Object)" (:key occurrence))
             (sequence-node
              [(raw "(value0) => { ") target (raw ".Remove(value0); }")])

             (= "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0, value1) => { ")
               (compat-call "MapPutIfAbsent"
                            [target (raw "value0") (raw "value1")])
               (raw "; }")])

             (= "executable:java.util.Deque#add(java.lang.Object)" (:key occurrence))
             (sequence-node
              [(raw "(value0) => { ")
               (compat-call "Add" [target (raw "value0")])
               (raw "; }")])

             (= "executable:java.util.Objects#nonNull(java.lang.Object)"
                (:key occurrence))
             (raw "(value0) => value0 is not null")

             (= "executable:org.w3c.dom.Node#removeChild(org.w3c.dom.Node)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => { ") target (raw ".RemoveChild(value0); }")])

             (= "executable:java.util.StringJoiner#add(java.lang.CharSequence)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => { ") target (raw ".add(value0); }")])

             (= "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)"
                (:key occurrence))
             (sequence-node [target (raw ".Set")])

             (= "executable:java.util.List#of()" (:key occurrence))
             (let [parent
                   (when (.isParentInitialized element)
                     (.getParent element))
                   parent-result-type
                   (when (instance? CtInvocation parent)
                     (.getType ^CtInvocation parent))
                   list-type
                   (first (.getActualTypeArguments (.getType element)))
                   element-type
                   (or
                    (some-> ^CtTypeReference list-type
                            .getActualTypeArguments
                            first)
                    (some-> ^CtTypeReference parent-result-type
                            .getActualTypeArguments
                            first))]
               (sequence-node
                [(raw "() => ")
                 (csharp/generic-name
                  (raw "global::DripSharp.Runtime.JavaCompat.ListOf")
                  [(type-node @ctx-holder element-type)])
                 (raw "()")]))

             (= "executable:java.lang.Object#toString()" (:key occurrence))
             (raw
              "(value0) => global::DripSharp.Runtime.JavaCompat.StringValueOf(value0)")

             (= "executable:java.util.ArrayList#<init>()" (:key occurrence))
             (sequence-node [(raw "() => new ") target (raw "()")])

             constructor?
             (let [target-type
                   (when (instance? CtTypeAccess target-element)
                     (.getAccessedType ^CtTypeAccess target-element))
                   supplier-type
                   (when (= "java.util.function.Supplier" functional-type)
                     (first (.getActualTypeArguments (.getType element))))]
               (cond
                 (and target-type (.isArray ^CtTypeReference target-type))
                 (sequence-node
                  [(raw "value0 => new ")
                   (type-node @ctx-holder
                              (.getComponentType ^CtTypeReference target-type))
                   (raw "[value0]")])

                 supplier-type
                 (sequence-node
                  [(raw "() => new ")
                   (type-node @ctx-holder supplier-type)
                   (raw "()")])

                 :else
                 (sequence-node
                  [(raw "(") (sequence-node parameters ", ") (raw ") => new ")
                   target (raw "(")
                   (sequence-node parameters ", ")
                   (raw ")")])))

             (and (= :record-component-accessor (:resolution occurrence))
                  (instance? CtTypeAccess target-element))
             (sequence-node [(raw "(value0) => value0.") executable])

             (and (instance? CtTypeAccess target-element) (not static?))
             (let [receiver (raw "value0")
                   invocation-parameters
                   (mapv #(raw (str "value" %)) (range 1 (inc parameter-count)))]
               (sequence-node
                (vec
                 (concat
                  [(raw "(") receiver]
                  (when (seq invocation-parameters)
                    [(raw ", ") (sequence-node invocation-parameters ", ")])
                  [(raw ") => ") receiver (raw ".") executable (raw "(")
                   (sequence-node invocation-parameters ", ") (raw ")")]))))

             discards-result?
             (sequence-node
              [(raw "(") (sequence-node parameters ", ") (raw ") => { ")
               target (raw ".") executable (raw "(")
               (sequence-node parameters ", ") (raw "); }")])
             :else
             (sequence-node [target (raw ".") executable])))}))}

    {:id :java-library.expression/literal
     :class CtLiteral
     :emit (fn [{:keys [element]}] {:node (literal-node element)})}

    {:id :java-library.expression/new-array
     :class CtNewArray
     :emit
     (fn [{:keys [^CtNewArray element children]}]
       (let [dimensions (vec (.getDimensionExpressions element))
             values (vec (.getElements element))
             ^CtArrayTypeReference source-array-type (.getType element)
             ^CtArrayTypeReference array-type
             (or
              (some
               (fn [^CtTypeReference cast]
                 (when (instance? CtArrayTypeReference cast)
                   cast))
               (reverse (.getTypeCasts element)))
              source-array-type)
             references
             (loop [reference array-type result []]
               (if (instance? CtArrayTypeReference reference)
                 (recur (.getComponentType ^CtArrayTypeReference reference)
                        (conj result reference))
                 {:leaf reference :depth (count result)}))
             leaf (:leaf references)
             depth (:depth references)
             initializer-element-type (.getComponentType array-type)]
         (when-not (and (instance? CtArrayTypeReference array-type)
                        (or (and (<= 1 (count dimensions) 2)
                                 (empty? values)
                                 (<= (count dimensions) depth))
                            (empty? dimensions)))
           (unsupported! "Java array creation shape requires explicit lowering"
                         element))
         {:node
          (let [node
                (cond
                  (= 2 (count dimensions))
                  (sequence-node
                   [(csharp/generic-name
                     (raw "global::DripSharp.Runtime.JavaCompat.NewJaggedArray")
                     [(type-node @ctx-holder leaf)])
                    (raw "(")
                    (sequence-node (mapv #(child-node children %) dimensions) ", ")
                    (raw ")")])

                  (seq dimensions)
                  (sequence-node
                   (vec
                    (concat
                     [(raw "new ") (type-node @ctx-holder leaf)]
                     (mapcat (fn [dimension]
                               [(raw "[") (child-node children dimension) (raw "]")])
                             dimensions)
                     (repeat (- depth (count dimensions)) (raw "[]")))))

                  :else
                  (sequence-node [(raw "new ")
                                  (type-node @ctx-holder leaf)
                                  (sequence-node (repeat depth (raw "[]")))
                                  (raw " { ")
                                  (sequence-node
                                   (mapv
                                    #(let [node (child-node children %)]
                                       (cond
                                         (and (= "byte"
                                                 (.getQualifiedName
                                                  initializer-element-type))
                                              (not= "byte"
                                                    (some-> ^CtExpression %
                                                            .getType
                                                            .getQualifiedName)))
                                         (sequence-node
                                          [(raw "unchecked((sbyte)(") node (raw "))")])

                                         (and (= "char"
                                                 (.getQualifiedName
                                                  initializer-element-type))
                                              (not= "char"
                                                    (some-> ^CtExpression %
                                                            .getType
                                                            .getQualifiedName)))
                                         (sequence-node
                                          [(raw "unchecked((char)(") node (raw "))")])

                                         :else node))
                                    values)
                                   ", ")
                                  (raw " }")]))]
            (expression-cast-node
             @ctx-holder
             element
             (or
              (when-let [adapt (:destination-new-array-adapter @ctx-holder)]
                (adapt
                 {:destination-context @ctx-holder
                  :element element
                  :children children
                  :node node}))
              node)))}))}

    {:id :java-library.expression/binary
     :class CtBinaryOperator
     :emit
     (fn [{:keys [^CtBinaryOperator element children]}]
       (let [kind (str (.getKind element))
             string-concat?
             (and (= "PLUS" kind)
                  (string-expression? element))
             unbox-operands?
             (and
              (not string-concat?)
              (or (not (contains? #{"EQ" "NE" "INSTANCEOF"} kind))
                  (and (contains? #{"EQ" "NE"} kind)
                       (or (.isPrimitive
                            (.getType (.getLeftHandOperand element)))
                           (.isPrimitive
                            (.getType (.getRightHandOperand element)))))))
             left-expression (.getLeftHandOperand element)
             right-expression (.getRightHandOperand element)
             left-node (child-node children left-expression)
             right-node (child-node children right-expression)
             left-reference (.getType left-expression)
             right-reference (.getType right-expression)
             left-value
             (cond
               (and unbox-operands?
                    (boxed-primitive-reference? left-reference))
               (maybe-unbox-node @ctx-holder left-expression left-node)

               (and unbox-operands?
                    (.isPrimitive right-reference)
                    (not (.isPrimitive left-reference)))
               (unbox-object-as-node @ctx-holder right-reference left-node)

               unbox-operands?
               (maybe-unbox-node @ctx-holder left-expression left-node)

               :else left-node)
             right-value
             (cond
               (and unbox-operands?
                    (boxed-primitive-reference? right-reference))
               (maybe-unbox-node @ctx-holder right-expression right-node)

               (and unbox-operands?
                    (.isPrimitive left-reference)
                    (not (.isPrimitive right-reference)))
               (unbox-object-as-node @ctx-holder left-reference right-node)

               unbox-operands?
               (maybe-unbox-node @ctx-holder right-expression right-node)

               :else right-node)
             right-value
             (if (contains? #{"SL" "SR" "USR"} kind)
               (sequence-node
                [(raw "unchecked((int)(") right-value (raw "))")])
               right-value)
             division? (= "DIV" kind)
             numeric-comparison?
             (contains? #{"EQ" "NE" "LT" "LE" "GT" "GE"} kind)
             left (cond
                    division?
                    (promoted-division-operand-node left-expression left-value)

                    numeric-comparison?
                    (promoted-comparison-operand-node left-expression left-value)

                    :else left-value)
             right (cond
                     division?
                     (promoted-division-operand-node right-expression right-value)

                     numeric-comparison?
                     (promoted-comparison-operand-node right-expression right-value)

                     :else right-value)
             generic-null-comparison?
             (and (contains? #{"EQ" "NE"} kind)
                  (or (and (type-parameter-expression? left-expression)
                           (instance? CtLiteral right-expression)
                           (nil? (.getValue ^CtLiteral right-expression)))
                      (and (type-parameter-expression? right-expression)
                           (instance? CtLiteral left-expression)
                           (nil? (.getValue ^CtLiteral left-expression)))))
             destination-node
             (or
              (neutral-binary-node kind right-expression left)
              (when-let [adapt (:destination-binary-adapter @ctx-holder)]
                (adapt
                 {:destination-context @ctx-holder
                  :element element
                  :kind kind
                  :left-expression left-expression
                  :right-expression right-expression
                  :left left
                  :right right})))
             node
             (or
              destination-node
              (cond
                generic-null-comparison?
                (let [value (if (and (instance? CtLiteral left-expression)
                                     (nil? (.getValue ^CtLiteral left-expression)))
                              right
                              left)]
                  (sequence-node
                   [(raw "(") value
                    (raw (if (= "EQ" kind) " is null)" " is not null)"))]))

                (and string-concat?
                     (contains? (get-in @ctx-holder
                                        [:configuration :destination-capabilities])
                                :java-compat))
                (compat-call "Concat" [left right])

                :else
                (sequence-node
                 [(raw "(")
                  left
                  (raw (str " " (binary-operator element) " "))
                  (if (= "INSTANCEOF" (str (.getKind element)))
                    (inferred-instanceof-type-node @ctx-holder element children)
                    right)
                  (raw ")")])))]
         {:node (expression-cast-node @ctx-holder element node)}))}

    {:id :java-library.expression/conditional
     :class CtConditional
     :emit
     (fn [{:keys [^CtConditional element children]}]
       (let [primitive-result? (.isPrimitive (.getType element))
             result-reference (.getType element)
             branch-node
             (fn [^CtExpression expression]
               (let [node (child-node children expression)]
                 (cond
                   primitive-result?
                   (maybe-unbox-node @ctx-holder expression node)

                   (and result-reference
                        (not= (.getQualifiedName result-reference)
                              (some-> expression .getType .getQualifiedName)))
                   (sequence-node
                    [(raw "(") (type-node @ctx-holder result-reference)
                     (raw ")(") node (raw ")")])

                   :else node)))]
         {:node
          (expression-cast-node
           @ctx-holder element
           (sequence-node [(raw "(")
                           (boolean-condition-node
                            @ctx-holder
                            (.getCondition element)
                            (child-node children (.getCondition element)))
                           (raw " ? ")
                           (branch-node (.getThenExpression element))
                           (raw " : ")
                           (branch-node (.getElseExpression element))
                           (raw ")")]))}))}

    {:id :java-library.expression/unary
     :class CtUnaryOperator
     :emit
     (fn [{:keys [^CtUnaryOperator element children]}]
       (let [kind (str (.getKind element))
             [prefix suffix] (unary-operator element)
             operand (.getOperand element)
             operand-node (child-node children operand)
             operand-node
             (if (contains? #{"NOT" "NEG" "POS" "COMPL"} kind)
               (maybe-unbox-node @ctx-holder operand operand-node)
               operand-node)
             expression-node
             (if (str/blank? prefix)
               (sequence-node [operand-node (raw suffix)])
               (csharp/prefix prefix operand-node))
             expression-node
             (if (instance? CtCase (.getParent element))
               expression-node
               (expression-cast-node @ctx-holder element expression-node))]
         {:node
          (if (statement-expression? element)
            (sequence-node [expression-node (raw ";")])
            expression-node)}))}

    {:id :java-library.expression/type-access
     :class CtTypeAccess
     :emit
     (fn [{:keys [^CtTypeAccess element children]}]
       {:node (child-node children (.getAccessedType element))})}

    {:id :java-library.expression/type-pattern
     :class CtTypePattern
     :emit
     (fn [{:keys [^CtTypePattern element]}]
       (let [variable (.getVariable element)]
         {:node
          (sequence-node
           [(type-node @ctx-holder (.getType variable))
            (raw " ")
            (raw (local-declaration-name variable))])}))}

    {:id :java-library.expression/field-read
     :class CtFieldRead
     :emit
     (fn [{:keys [context ^CtFieldRead element children]}]
       (let [target (.getTarget element)
             occurrence (field-occurrence context element)
             target-node
             (when target
               (let [node (child-node children target)]
                 (if (and (instance? CtExpression target)
                          (seq (.getTypeCasts ^CtExpression target)))
                   (sequence-node [(raw "(") node (raw ")")])
                   node)))
             node
             (if-let [adaptation
                      (get (:destination-field-adaptations @ctx-holder)
                           (:key occurrence))]
               (adaptation target-node)
               (case (:key occurrence)
                 "field:java.io.File#separator"
                 (raw "global::System.IO.Path.DirectorySeparatorChar.ToString()")

                 "field:java.io.File#separatorChar"
                 (raw "global::System.IO.Path.DirectorySeparatorChar")

                 "field:java.io.ByteArrayOutputStream#buf"
                 (compat-call
                  "ToSignedBytes"
                  [(if (instance? CtSuperAccess target)
                     (raw "this")
                     target-node)])

                 "field:java.io.FilterInputStream#in"
                 (raw "@in")

                 "field:java.io.FilterOutputStream#out"
                 (raw "@out")

                 "field:java.lang.Boolean#FALSE"
                 (raw "false")

                 "field:java.lang.Boolean#TRUE"
                 (raw "true")

                 "field:java.lang.Character#MAX_CODE_POINT"
                 (raw "0x10ffff")

                 "field:java.lang.Character#MAX_VALUE"
                 (raw "char.MaxValue")

                 "field:java.lang.Character#MIN_VALUE"
                 (raw "char.MinValue")

                 "field:java.lang.Character#MIN_SURROGATE"
                 (raw "'\\uD800'")

                 ("field:java.lang.Character#MIN_CODE_POINT"
                  "field:java.lang.Character#UNASSIGNED")
                 (raw "0")

                 ("field:java.lang.Character#UPPERCASE_LETTER"
                  "field:java.lang.Character#LOWERCASE_LETTER"
                  "field:java.lang.Character#TITLECASE_LETTER"
                  "field:java.lang.Character#OTHER_LETTER"
                  "field:java.lang.Character#DECIMAL_DIGIT_NUMBER"
                  "field:java.lang.Character#LETTER_NUMBER"
                  "field:java.lang.Character#SPACE_SEPARATOR"
                  "field:java.lang.Character#LINE_SEPARATOR"
                  "field:java.lang.Character#PARAGRAPH_SEPARATOR"
                  "field:java.lang.Character#DASH_PUNCTUATION"
                  "field:java.lang.Character#START_PUNCTUATION"
                  "field:java.lang.Character#END_PUNCTUATION"
                  "field:java.lang.Character#CONNECTOR_PUNCTUATION"
                  "field:java.lang.Character#OTHER_PUNCTUATION"
                  "field:java.lang.Character#CURRENCY_SYMBOL"
                  "field:java.lang.Character#INITIAL_QUOTE_PUNCTUATION"
                  "field:java.lang.Character#FINAL_QUOTE_PUNCTUATION")
                 (raw
                  (get
                   {"field:java.lang.Character#UPPERCASE_LETTER" "1"
                    "field:java.lang.Character#LOWERCASE_LETTER" "2"
                    "field:java.lang.Character#TITLECASE_LETTER" "3"
                    "field:java.lang.Character#OTHER_LETTER" "5"
                    "field:java.lang.Character#DECIMAL_DIGIT_NUMBER" "9"
                    "field:java.lang.Character#LETTER_NUMBER" "10"
                    "field:java.lang.Character#SPACE_SEPARATOR" "12"
                    "field:java.lang.Character#LINE_SEPARATOR" "13"
                    "field:java.lang.Character#PARAGRAPH_SEPARATOR" "14"
                    "field:java.lang.Character#DASH_PUNCTUATION" "20"
                    "field:java.lang.Character#START_PUNCTUATION" "21"
                    "field:java.lang.Character#END_PUNCTUATION" "22"
                    "field:java.lang.Character#CONNECTOR_PUNCTUATION" "23"
                    "field:java.lang.Character#OTHER_PUNCTUATION" "24"
                    "field:java.lang.Character#CURRENCY_SYMBOL" "26"
                    "field:java.lang.Character#INITIAL_QUOTE_PUNCTUATION" "29"
                    "field:java.lang.Character#FINAL_QUOTE_PUNCTUATION" "30"}
                   (:key occurrence)))

                 "field:java.lang.Character#NON_SPACING_MARK"
                 (raw "6")

                 "field:java.lang.Character#MODIFIER_LETTER"
                 (raw "4")

                 "field:java.lang.Character#MODIFIER_SYMBOL"
                 (raw "27")

                 "field:java.lang.Float#MAX_VALUE"
                 (raw "float.MaxValue")

                 "field:java.lang.Float#MIN_NORMAL"
                 (raw "1.17549435E-38f")

                 "field:java.lang.Float#MIN_VALUE"
                 (raw "float.Epsilon")

                 "field:java.lang.Float#NEGATIVE_INFINITY"
                 (raw "float.NegativeInfinity")

                 "field:java.lang.Float#POSITIVE_INFINITY"
                 (raw "float.PositiveInfinity")

                 "field:java.lang.Double#NaN"
                 (raw "double.NaN")

                 "field:java.lang.Double#NEGATIVE_INFINITY"
                 (raw "double.NegativeInfinity")

                 "field:java.lang.Double#POSITIVE_INFINITY"
                 (raw "double.PositiveInfinity")

                 "field:java.lang.Double#MAX_VALUE"
                 (raw "double.MaxValue")

                 "field:java.lang.Double#MIN_VALUE"
                 (raw "double.Epsilon")

                 "field:java.lang.Double#MAX_EXPONENT"
                 (raw "1023")

                 "field:java.lang.Double#MIN_EXPONENT"
                 (raw "-1022")

                 "field:java.lang.Integer#MIN_VALUE"
                 (raw "int.MinValue")

                 "field:java.lang.Integer#SIZE"
                 (raw "32")

                 "field:java.lang.Long#MIN_VALUE"
                 (raw "long.MinValue")

                 "field:java.lang.Long#MAX_VALUE"
                 (raw "long.MaxValue")

                 "field:java.lang.Long#SIZE"
                 (raw "64")

                 "field:java.lang.Byte#SIZE"
                 (raw "8")

                 "field:java.lang.Short#MAX_VALUE"
                 (raw "short.MaxValue")

                 "field:java.lang.Short#MIN_VALUE"
                 (raw "short.MinValue")

                 "field:java.lang.Short#SIZE"
                 (raw "16")

                 ("field:java.lang.Math#PI"
                  "field:java.lang.StrictMath#PI")
                 (raw "global::System.Math.PI")

                 ("field:java.lang.Math#E"
                  "field:java.lang.StrictMath#E")
                 (raw "global::System.Math.E")

                 ("field:java.util.regex.Pattern#UNIX_LINES"
                  "field:java.util.regex.Pattern#CASE_INSENSITIVE"
                  "field:java.util.regex.Pattern#COMMENTS"
                  "field:java.util.regex.Pattern#MULTILINE"
                  "field:java.util.regex.Pattern#LITERAL"
                  "field:java.util.regex.Pattern#DOTALL"
                  "field:java.util.regex.Pattern#UNICODE_CASE"
                  "field:java.util.regex.Pattern#CANON_EQ"
                  "field:java.util.regex.Pattern#UNICODE_CHARACTER_CLASS")
                 (raw
                  (get
                   {"field:java.util.regex.Pattern#UNIX_LINES" "1"
                    "field:java.util.regex.Pattern#CASE_INSENSITIVE" "2"
                    "field:java.util.regex.Pattern#COMMENTS" "4"
                    "field:java.util.regex.Pattern#MULTILINE" "8"
                    "field:java.util.regex.Pattern#LITERAL" "16"
                    "field:java.util.regex.Pattern#DOTALL" "32"
                    "field:java.util.regex.Pattern#UNICODE_CASE" "64"
                    "field:java.util.regex.Pattern#CANON_EQ" "128"
                    "field:java.util.regex.Pattern#UNICODE_CHARACTER_CLASS" "256"}
                   (:key occurrence)))

                 "field:java.util.Locale#ENGLISH"
                 (raw "global::System.Globalization.CultureInfo.GetCultureInfo(\"en\")")

                 "field:java.time.Month#FEBRUARY"
                 (raw "2")

                 "field:java.nio.file.LinkOption#NOFOLLOW_LINKS"
                 (raw "new object()")

                 ("field:java.nio.file.attribute.PosixFilePermission#OWNER_READ"
                  "field:java.nio.file.attribute.PosixFilePermission#OWNER_WRITE"
                  "field:java.nio.file.attribute.PosixFilePermission#OWNER_EXECUTE"
                  "field:java.nio.file.attribute.PosixFilePermission#GROUP_READ"
                  "field:java.nio.file.attribute.PosixFilePermission#GROUP_WRITE"
                  "field:java.nio.file.attribute.PosixFilePermission#GROUP_EXECUTE"
                  "field:java.nio.file.attribute.PosixFilePermission#OTHERS_READ"
                  "field:java.nio.file.attribute.PosixFilePermission#OTHERS_WRITE"
                  "field:java.nio.file.attribute.PosixFilePermission#OTHERS_EXECUTE")
                 (raw
                  (str
                   "global::System.IO.UnixFileMode."
                   (case (:key occurrence)
                     "field:java.nio.file.attribute.PosixFilePermission#OWNER_READ" "UserRead"
                     "field:java.nio.file.attribute.PosixFilePermission#OWNER_WRITE" "UserWrite"
                     "field:java.nio.file.attribute.PosixFilePermission#OWNER_EXECUTE" "UserExecute"
                     "field:java.nio.file.attribute.PosixFilePermission#GROUP_READ" "GroupRead"
                     "field:java.nio.file.attribute.PosixFilePermission#GROUP_WRITE" "GroupWrite"
                     "field:java.nio.file.attribute.PosixFilePermission#GROUP_EXECUTE" "GroupExecute"
                     "field:java.nio.file.attribute.PosixFilePermission#OTHERS_READ" "OtherRead"
                     "field:java.nio.file.attribute.PosixFilePermission#OTHERS_WRITE" "OtherWrite"
                     "field:java.nio.file.attribute.PosixFilePermission#OTHERS_EXECUTE" "OtherExecute")))

                 ("field:java.math.RoundingMode#UP"
                  "field:java.math.RoundingMode#DOWN"
                  "field:java.math.RoundingMode#CEILING"
                  "field:java.math.RoundingMode#FLOOR"
                  "field:java.math.RoundingMode#HALF_UP"
                  "field:java.math.RoundingMode#HALF_DOWN"
                  "field:java.math.RoundingMode#HALF_EVEN"
                  "field:java.math.RoundingMode#UNNECESSARY")
                 (raw
                  (str
                   "global::DripSharp.Runtime.JavaRoundingMode."
                   (case (:key occurrence)
                     "field:java.math.RoundingMode#UP" "Up"
                     "field:java.math.RoundingMode#DOWN" "Down"
                     "field:java.math.RoundingMode#CEILING" "Ceiling"
                     "field:java.math.RoundingMode#FLOOR" "Floor"
                     "field:java.math.RoundingMode#HALF_UP" "HalfUp"
                     "field:java.math.RoundingMode#HALF_DOWN" "HalfDown"
                     "field:java.math.RoundingMode#HALF_EVEN" "HalfEven"
                     "field:java.math.RoundingMode#UNNECESSARY" "Unnecessary")))

                 "field:java.util.zip.Deflater#DEFAULT_COMPRESSION"
                 (raw "global::DripSharp.Runtime.JavaDeflater.DEFAULT_COMPRESSION")

                 "field:java.util.zip.Deflater#BEST_COMPRESSION"
                 (raw "global::DripSharp.Runtime.JavaDeflater.BEST_COMPRESSION")

                 "field:java.text.Bidi#DIRECTION_DEFAULT_LEFT_TO_RIGHT"
                 (raw "global::DripSharp.Runtime.JavaBidi.DirectionDefaultLeftToRight")

                 "field:java.text.Bidi#DIRECTION_DEFAULT_RIGHT_TO_LEFT"
                 (raw "global::DripSharp.Runtime.JavaBidi.DirectionDefaultRightToLeft")

                 "field:java.text.Bidi#DIRECTION_LEFT_TO_RIGHT"
                 (raw "global::DripSharp.Runtime.JavaBidi.DirectionLeftToRight")

                 "field:java.text.Bidi#DIRECTION_RIGHT_TO_LEFT"
                 (raw "global::DripSharp.Runtime.JavaBidi.DirectionRightToLeft")

                 "field:java.text.Normalizer$Form#NFC"
                 (raw "global::System.Text.NormalizationForm.FormC")

                 "field:java.text.Normalizer$Form#NFD"
                 (raw "global::System.Text.NormalizationForm.FormD")

                 "field:java.text.Normalizer$Form#NFKC"
                 (raw "global::System.Text.NormalizationForm.FormKC")

                 "field:java.text.Normalizer$Form#NFKD"
                 (raw "global::System.Text.NormalizationForm.FormKD")

                 "field:java.nio.charset.StandardCharsets#UTF_16"
                 (raw "global::DripSharp.Runtime.JavaStandardCharsets.UTF16")

                 "field:java.nio.charset.StandardCharsets#UTF_16BE"
                 (raw "global::DripSharp.Runtime.JavaStandardCharsets.UTF16BE")

                 "field:java.nio.charset.StandardCharsets#UTF_16LE"
                 (raw "global::DripSharp.Runtime.JavaStandardCharsets.UTF16LE")

                 "field:java.nio.charset.CodingErrorAction#REPORT"
                 (raw "global::DripSharp.Runtime.JavaCodingErrorAction.Report")

                 "field:java.util.Calendar#MILLISECOND"
                 (raw "14")

                 "field:java.util.Calendar#YEAR"
                 (raw "1")

                 "field:java.util.Calendar#MONTH"
                 (raw "2")

                 "field:java.util.Calendar#DAY_OF_MONTH"
                 (raw "5")

                 "field:java.util.Calendar#HOUR_OF_DAY"
                 (raw "11")

                 "field:java.util.Calendar#MINUTE"
                 (raw "12")

                 "field:java.util.Calendar#SECOND"
                 (raw "13")

                 "field:java.util.Calendar#ZONE_OFFSET"
                 (raw "15")

                 "field:java.util.Calendar#DST_OFFSET"
                 (raw "16")

                 "field:java.util.Locale#US"
                 (raw "global::System.Globalization.CultureInfo.GetCultureInfo(\"en-US\")")

                 "field:java.time.format.DateTimeFormatter#ISO_LOCAL_DATE_TIME"
                 (raw "global::DripSharp.Runtime.JavaDateTimeFormatter.IsoLocalDateTime")

                 "field:javax.xml.XMLConstants#XMLNS_ATTRIBUTE"
                 (raw "\"xmlns\"")

                 "field:javax.xml.XMLConstants#XMLNS_ATTRIBUTE_NS_URI"
                 (raw "\"http://www.w3.org/2000/xmlns/\"")

                 "field:javax.xml.XMLConstants#XML_NS_PREFIX"
                 (raw "\"xml\"")

                 "field:javax.xml.XMLConstants#XML_NS_URI"
                 (raw "\"http://www.w3.org/XML/1998/namespace\"")

                 "field:javax.xml.transform.OutputKeys#ENCODING"
                 (raw "\"encoding\"")

                 "field:javax.xml.transform.OutputKeys#INDENT"
                 (raw "\"indent\"")

                 "field:javax.xml.transform.OutputKeys#OMIT_XML_DECLARATION"
                 (raw "\"omit-xml-declaration\"")

                 "field:org.w3c.dom.Node#COMMENT_NODE"
                 (raw "global::System.Xml.XmlNodeType.Comment")

                 "field:org.w3c.dom.Node#TEXT_NODE"
                 (raw "global::System.Xml.XmlNodeType.Text")

                 "field:javax.xml.xpath.XPathConstants#NODE"
                 (raw "global::DripSharp.Runtime.JavaXPathConstants.NODE")

                 "field:javax.xml.xpath.XPathConstants#NODESET"
                 (raw "global::DripSharp.Runtime.JavaXPathConstants.NODESET")

                 (if (and (instance? CtTypeAccess target) (= "class" (.getSimpleName (.getVariable element)))) (sequence-node [(raw "typeof(") target-node (raw ")")]) (sequence-node [(when target (sequence-node [target-node (raw ".")])) (if (= "field:<array>#length" (:key occurrence)) (raw "Length") (child-node children (.getVariable element)))]))))]
         {:node
          (expression-cast-node
           @ctx-holder element
           (if (and
                (nullable-declaration?
                 @ctx-holder (:declaration occurrence))
                (empty? (.getTypeCasts element)))
             (null-forgiven-node node)
             node))}))}

    {:id :java-library.expression/field-write
     :class CtFieldWrite
     :emit
     (fn [{:keys [^CtFieldWrite element children]}]
       (let [target (.getTarget element)]
         {:node
          (sequence-node
           [(when target
              (sequence-node [(child-node children target) (raw ".")]))
            (child-node children (.getVariable element))])}))}

    {:id :java-library.expression/operator-assignment
     :class CtOperatorAssignment
     :emit
     (fn [{:keys [^CtOperatorAssignment element children]}]
       (let [statement? (statement-expression? element)
             for-clause? (contains? #{"forInit" "forUpdate"}
                                    (role element))
             assigned (.getAssigned element)
             operator (assignment-operator element)
             assigned-type (some-> assigned .getType .getQualifiedName)
             assignment-type (some-> element .getAssignment .getType .getQualifiedName)
             helper
             (when (or (= "byte" assigned-type)
                       (and (= "int" assigned-type)
                            (contains? #{"long" "float" "double"} assignment-type)))
               (get {"+" "AddAssign"
                     "-" "SubtractAssign"
                     "*" "MultiplyAssign"
                     "/" "DivideAssign"
                     "%" "RemainderAssign"
                     "&" "AndAssign"
                     "|" "OrAssign"
                     "^" "XorAssign"
                     "<<" "ShiftLeftAssign"
                     ">>" "ShiftRightAssign"
                     ">>>" "UnsignedShiftRightAssign"}
                    operator))
             node
             (if helper
               (compat-call
                helper
                [(sequence-node [(raw "ref ")
                                 (child-node children assigned)])
                 (child-node children (.getAssignment element))])
               (sequence-node
                [(child-node children assigned)
                 (raw (str " " operator "= "))
                 (child-node children (.getAssignment element))]))]
         {:node
          (sequence-node
           [(when-not (or statement? for-clause?) (raw "("))
            node
            (raw (cond statement? ";"
                       for-clause? ""
                       :else ")"))])}))}

    {:id :java-library.expression/assignment
     :class CtAssignment
     :emit
     (fn [{:keys [^CtAssignment element children]}]
       (let [statement? (statement-expression? element)
             for-clause? (contains? #{"forInit" "forUpdate"}
                                    (role element))]
         {:node
          (sequence-node
           [(when-not (or statement? for-clause?) (raw "("))
            (child-node children (.getAssigned element))
            (raw " = ")
            (assignment-value-node
             @ctx-holder (.getAssigned element) (.getAssignment element)
             (child-node children (.getAssignment element)))
            (raw (cond statement? ";"
                       for-clause? ""
                       :else ")"))])}))}

    {:id :java-library.expression/array-read
     :class CtArrayRead
     :emit
     (fn [{:keys [^CtArrayRead element children]}]
       (let [node
             (sequence-node [(child-node children (.getTarget element))
                             (raw "[")
                             (child-node children (.getIndexExpression element))
                             (raw "]")])]
         {:node (expression-cast-node @ctx-holder element node)}))}

    {:id :java-library.expression/array-write
     :class CtArrayWrite
     :emit
     (fn [{:keys [^CtArrayWrite element children]}]
       {:node
        (sequence-node [(child-node children (.getTarget element))
                        (raw "[")
                        (child-node children (.getIndexExpression element))
                        (raw "]")])})}

    {:id :java-library.statement/block
     :class CtBlock
     :emit
     (fn [{:keys [context ^CtBlock element children]}]
       (let [statements (vec (remove #(or (ignorable-implicit-statement? %)
                                          (instance? CtComment %))
                                     (.getStatements element)))
             parent (when (.isParentInitialized element) (.getParent element))
             continue-target
             (when (and (or (instance? CtFor parent)
                            (instance? CtForEach parent)
                            (instance? CtWhile parent)
                            (instance? CtDo parent))
                        (java/labeled-targeted? context parent :continue))
               parent)]
         {:node
          (csharp/block
           (cond-> (mapv #(child-node children %) statements)
             continue-target
             (conj
              (raw
               (str (java/labeled-target-name context continue-target :continue)
                    ":;")))))}))}

    {:id :java-library.statement/if
     :class CtIf
     :emit
     (fn [{:keys [^CtIf element children]}]
       {:node
        (sequence-node
         [(raw "if (")
          (maybe-unbox-node
           @ctx-holder
           (.getCondition element)
           (child-node children (.getCondition element)))
          (raw ") ")
          (statement-node children (.getThenStatement element))
          (when-let [else-statement (.getElseStatement element)]
            (sequence-node [(raw " else ")
                            (statement-node children else-statement)]))])})}

    {:id :java-library.statement/foreach
     :class CtForEach
     :emit
     (fn [{:keys [context ^CtForEach element children]}]
       (let [variable (.getVariable element) mutable? (boolean (some (fn [^CtVariableWrite candidate] (identical? variable (some-> candidate .getVariable .getDeclaration))) (.getElements (.getBody element) (TypeFilter. CtVariableWrite)))) variable-name (local-declaration-name variable) iteration-name (str "__foreachValue_" variable-name) variable-type-node (if (.isInferred variable) (raw "var") (declaration-type-node @ctx-holder variable (.getType variable)))]
         {:node
          (sequence-node
           [(raw "foreach (")
            variable-type-node
            (raw (str " " (if mutable? iteration-name variable-name) " in "))
            (child-node children (.getExpression element))
            (raw ") ")
            (if mutable?
              (sequence-node
               [(raw "{\n")
                variable-type-node
                (raw (str " " variable-name " = " iteration-name ";\n"))
                (labeled-loop-body-node context children
                                        (.getBody element) element)
                (raw "\n}")])
              (labeled-loop-body-node context children
                                      (.getBody element) element))])}))}

    {:id :java-library.statement/for
     :class CtFor
     :emit
     (fn [{:keys [context ^CtFor element children]}]
       (let [initializers (vec (.getForInit element))
             updates (vec (.getForUpdate element))
             local-declarations? (every? #(instance? CtLocalVariable %)
                                         initializers)
             declaration-types
             (when (seq initializers)
               (->> initializers
                    (map #(some-> ^CtLocalVariable % .getType .getQualifiedName))
                    set))]
         (when (and local-declarations?
                    (> (count declaration-types) 1))
           (unsupported! "Java for loop local declarations require one shared type"
                         element))
         {:node
          (sequence-node
           [(raw "for (")
            (cond
              (empty? initializers)
              nil

              local-declarations?
              (let [initializer-nodes
                    (mapv #(child-node children %) initializers)
                    declarator-node
                    (fn [node]
                      (when-not (and (= :sequence (:kind node))
                                     (<= 2 (count (:nodes node)))
                                     (= :raw (:kind (second (:nodes node)))))
                        (unsupported!
                         "Java for loop local declaration has an unsupported destination shape"
                         element))
                      (let [name-node (second (:nodes node))]
                        (assoc node
                               :nodes
                               (assoc (subvec (:nodes node) 1)
                                      0
                                      (update name-node :text str/triml)))))]
                (sequence-node
                 (into [(first initializer-nodes)]
                       (map declarator-node (rest initializer-nodes)))
                 ", "))

              :else
              (sequence-node (mapv #(child-node children %) initializers) ", "))
            (raw "; ")
            (when-let [condition (.getExpression element)]
              (maybe-unbox-node
               @ctx-holder condition (child-node children condition)))
            (raw "; ")
            (sequence-node (mapv #(child-node children %) updates) ", ")
            (raw ") ")
            (labeled-loop-body-node context children
                                    (.getBody element) element)])}))}

    {:id :java-library.statement/while
     :class CtWhile
     :emit
     (fn [{:keys [context ^CtWhile element children]}]
       {:node
        (sequence-node
         [(raw "while (")
          (maybe-unbox-node
           @ctx-holder
           (.getLoopingExpression element)
           (child-node children (.getLoopingExpression element)))
          (raw ") ")
          (labeled-loop-body-node context children
                                  (.getBody element) element)])})}

    {:id :java-library.statement/do-while
     :class CtDo
     :emit
     (fn [{:keys [context ^CtDo element children]}]
       {:node
        (sequence-node
         [(raw "do ")
          (labeled-loop-body-node context children
                                  (.getBody element) element)
          (raw " while (")
          (maybe-unbox-node
           @ctx-holder
           (.getLoopingExpression element)
           (child-node children (.getLoopingExpression element)))
          (raw ");")])})}

    {:id :java-library.statement/synchronized
     :class CtSynchronized
     :emit
     (fn [{:keys [^CtSynchronized element children]}]
       {:node
        (sequence-node
         [(raw "lock (")
          (child-node children (.getExpression element))
          (raw ") ")
          (statement-node children (.getBlock element))])})}

    {:id :java-library.statement/break
     :class CtBreak
     :emit
     (fn [{:keys [context ^CtBreak element]}]
       {:node (if (.getTargetLabel element)
                (java/labeled-branch-node context element :break)
                (raw "break;"))})}

    {:id :java-library.statement/continue
     :class CtContinue
     :emit
     (fn [{:keys [context ^CtContinue element]}]
       {:node (if (.getTargetLabel element)
                (java/labeled-branch-node context element :continue)
                (raw "continue;"))})}

    {:id :java-library.statement/case
     :class CtCase
     :emit
     (fn [{:keys [^CtCase element children]}]
       (let [source-statements
             (vec (remove #(or (instance? CtComment %)
                               (ignorable-implicit-statement? %))
                          (.getStatements element)))
             statements (mapv #(child-node children %) source-statements)
             last-semantic (last source-statements)]
         {:node
          (sequence-node
           [(case-labels @ctx-holder children element)
            (when (seq statements) (raw "\n"))
            (sequence-node statements "\n")
            (when (and last-semantic
                       (not (terminating-statement? last-semantic)))
              (raw "\nbreak;"))
            (when (and (empty? statements)
                       (identical? element
                                   (last (.getCases
                                          ^CtAbstractSwitch
                                          (.getParent element)))))
              (raw "\nbreak;"))])}))}

    {:id :java-library.expression/switch
     :class CtSwitchExpression
     :emit
     (fn [{:keys [^CtSwitchExpression element children]}]
       (let [selector (child-node children (.getSelector element))
             default? (some #(empty? (.getCaseExpressions ^CtCase %))
                            (.getCases element))
             node
             (sequence-node
              [(raw "((global::System.Func<")
               (child-node children (.getType element))
               (raw ">)(() => { switch (")
               (if (enum-switch-declaration @ctx-holder element)
                 (compat-call "EnumOrdinal" [selector])
                 selector)
               (raw ") {\n")
               (sequence-node
                (mapv #(child-node children %) (.getCases element))
                "\n")
               (raw
                (if default?
                  "\n} }))()"
                  (str "\n} throw new global::System.InvalidOperationException(); "
                       "}))()")))])]
         {:node (expression-cast-node @ctx-holder element node)}))}

    {:id :java-library.statement/switch
     :class CtSwitch
     :emit
     (fn [{:keys [^CtSwitch element children]}]
       (let [selector (child-node children (.getSelector element))]
         {:node
          (sequence-node
           [(raw "switch (")
            (if (enum-switch-declaration @ctx-holder element)
              (compat-call "EnumOrdinal" [selector])
              selector)
            (raw ") {\n")
            (sequence-node (mapv #(child-node children %) (.getCases element)) "\n")
            (raw "\n}")])}))}

    {:id :java-library.statement/yield
     :class CtYieldStatement
     :emit
     (fn [{:keys [^CtYieldStatement element children]}]
       (let [switch-expression? (switch-expression-yield? element)]
         {:node
          (sequence-node
           [(when switch-expression? (raw "return "))
            (child-node children (.getExpression element))
            (when switch-expression? (raw ";"))])}))}

    {:id :java-library.statement/assert
     :class CtAssert
     :emit
     (fn [{:keys [^CtAssert element children]}]
       (let [message (.getExpression element)]
         {:node
          (sequence-node
           [(raw "global::DripSharp.Runtime.JavaCompat.Assert(() => ")
            (child-node children (.getAssertExpression element))
            (when message
              (sequence-node [(raw ", () => ")
                              (child-node children message)]))
            (raw ");")])}))}

    {:id :java-library.statement/return
     :class CtReturn
     :emit
     (fn [{:keys [^CtReturn element children]}]
       (let [returned (.getReturnedExpression element)
             method (enclosing-method element)
             returned-node
             (when returned
               (let [node (child-node children returned)]
                 (if method
                   (assignment-value-node @ctx-holder method returned node)
                   node)))]
         {:node
          (sequence-node
           [(raw "return")
            (when returned
              (sequence-node [(raw " ") returned-node]))
            (raw ";")])}))}

    {:id :java-library.statement/try
     :class CtTry
     :emit
     (fn [{:keys [^CtTry element children]}]
       (let [resources (if (instance? CtTryWithResource element)
                         (vec (.getResources ^CtTryWithResource element))
                         [])
             catches (vec (.getCatchers element))
             finalizer (.getFinalizer element)]
         (let [body (child-node children (.getBody element))
               using-node
               (when (seq resources)
                 (reduce
                  (fn [guarded resource]
                    (sequence-node
                     [(raw "using (")
                      (child-node children resource)
                      (raw ") ") guarded]))
                  body
                  (reverse resources)))
               guarded-body
               (if (and using-node (or (seq catches) finalizer))
                 (sequence-node [(raw "{\n") using-node (raw "\n}")])
                 (or using-node body))]
           {:node
            (sequence-node
             [(when (or (empty? resources) (seq catches) finalizer)
                (raw "try "))
              guarded-body
              (sequence-node
               (mapv #(sequence-node [(raw " ") (child-node children %)])
                     catches))
              (when finalizer
                (sequence-node [(raw " finally ")
                                (child-node children finalizer)]))])})))}

    {:id :java-library.statement/catch
     :class CtCatch
     :emit
     (fn [{:keys [context ^CtCatch element children]}]
       (let [parameter (.getParameter element)
             multi-types (vec (.getMultiTypes parameter))
             catch-types (if (seq multi-types)
                           multi-types
                           [(.getType parameter)])
             catch-type-nodes (mapv #(type-node @ctx-holder %) catch-types)
             distinct-catch-type-nodes
             (->> catch-type-nodes
                  (reduce
                   (fn [{:keys [seen nodes] :as result} node]
                     (let [destination (get (csharp/render node) :text)]
                       (if (contains? seen destination)
                         result
                         {:seen (conj seen destination)
                          :nodes (conj nodes node)})))
                   {:seen #{} :nodes []})
                  :nodes)
             catch-destinations
             (set (map #(get (csharp/render %) :text)
                       distinct-catch-type-nodes))
             later-catch-destinations
             (let [parent (when (.isParentInitialized element)
                            (.getParent element))]
               (if (instance? CtTry parent)
                 (->> (.getCatchers ^CtTry parent)
                      (drop-while #(not (identical? element %)))
                      rest
                      (mapcat
                       (fn [^CtCatch later]
                         (let [later-parameter (.getParameter later)
                               later-multi-types
                               (vec (.getMultiTypes later-parameter))]
                           (if (seq later-multi-types)
                             later-multi-types
                             [(.getType later-parameter)]))))
                      (map #(get (csharp/render
                                  (type-node @ctx-holder %))
                                 :text))
                      set)
                 #{}))
             preserve-filter-for-catch-order?
             (and (< 1 (count multi-types))
                  (seq (set/intersection catch-destinations
                                         later-catch-destinations)))
             multi? (or (< 1 (count distinct-catch-type-nodes))
                        preserve-filter-for-catch-order?)
             used?
             (some
              (fn [^CtVariableRead read]
                (identical?
                 parameter
                 (some-> read .getVariable .getDeclaration)))
              (.getElements
               (.getBody element)
               (TypeFilter. CtVariableRead)))
             parameter-name (identifier (.getSimpleName parameter))
             java-exception?
             (and
              (not multi?)
              (= "java.lang.Exception"
                 (.getQualifiedName ^CtTypeReference (first catch-types))))
             labeled-flow-exception?
             (and
              (java/labeled-finally-flow? context)
              (some
               #(contains?
                 #{"java.lang.Throwable"
                   "java.lang.Exception"
                   "java.lang.RuntimeException"
                   "java.lang.AssertionError"
                   "java.lang.Error"}
                 (.getQualifiedName ^CtTypeReference %))
               catch-types))
             filter-conditions
             (remove
              nil?
              [(when multi?
                 (sequence-node
                  [(raw (str parameter-name " is "))
                   (sequence-node
                    distinct-catch-type-nodes
                    " or ")]))
               (when java-exception?
                 (raw
                  (str parameter-name
                       " is not global::System.TypeInitializationException")))
               (when labeled-flow-exception?
                 (raw
                  (str parameter-name
                       " is not global::DripSharp.Runtime."
                       "JavaLabeledControlFlowException")))])]
         {:node
          (sequence-node
           [(raw "catch (")
            (cond
              multi?
              (sequence-node
               [(raw "global::System.Exception ")
                (raw parameter-name)])

              (or used? java-exception? labeled-flow-exception?)
              (child-node children parameter)

              :else
              (first distinct-catch-type-nodes))
            (raw ")")
            (when (seq filter-conditions)
              (sequence-node
               [(raw " when (")
                (sequence-node filter-conditions " && ")
                (raw ")")]))
            (raw " ")
            (child-node children (.getBody element))])}))}

    {:id :java-library.statement/throw
     :class CtThrow
     :emit
     (fn [{:keys [^CtThrow element children]}]
       {:node
        (sequence-node [(raw "throw ")
                        (child-node children (.getThrownExpression element))
                        (raw ";")])})}

    {:id :java-library.declaration/local-variable
     :class CtLocalVariable
     :emit
     (fn [{:keys [^CtLocalVariable element children]}]
       (let [initializer (.getDefaultExpression element)
             initializer-node
             (when initializer
               (let [node (child-node children initializer)]
                 (assignment-value-node @ctx-holder element initializer node)))]
         {:node
          (sequence-node
           [(if (.isInferred element)
              (raw "var")
              (declaration-type-node @ctx-holder element (.getType element)))
            (raw (str " " (local-declaration-name element)))
            (when initializer
              (sequence-node [(raw " = ")
                              initializer-node]))
            (when-not (or (= "forInit" (role element))
                          (and (.isParentInitialized element)
                               (instance? CtTryWithResource (.getParent element))))
              (raw ";"))])}))}

    {:id :java-library.expression/super
     :class CtSuperAccess
     :emit (fn [_] {:node (raw "base")})}

    {:id :java-library.expression/variable-read
     :class CtVariableRead
     :emit
     (fn [{:keys [^CtVariableRead element children]}]
       (let [declaration (some-> element .getVariable .getDeclaration)
             node (child-node children (.getVariable element))]
         {:node (expression-cast-node
                 @ctx-holder element
                 (if (and
                      (nullable-declaration? @ctx-holder declaration)
                      (empty? (.getTypeCasts element)))
                   (null-forgiven-node node)
                   node))}))}

    {:id :java-library.expression/variable-write
     :class CtVariableWrite
     :emit
     (fn [{:keys [^CtVariableWrite element children]}]
       {:node (child-node children (.getVariable element))})}

    {:id :java-library.reference/parameter
     :class CtParameterReference
     :emit (fn [{:keys [^CtParameterReference element]}]
             {:node (raw (local-reference-name @ctx-holder element))})}

    {:id :java-library.declaration/lambda-parameter
     :class CtParameter
     :emit (fn [{:keys [^CtParameter element]}]
             {:node (raw (identifier (.getSimpleName element)))})}

    {:id :java-library.reference/local-variable
     :class CtLocalVariableReference
     :emit (fn [{:keys [^CtLocalVariableReference element]}]
             {:node (raw (local-reference-name @ctx-holder element))})}

    {:id :java-library.declaration/catch-variable
     :class CtCatchVariable
     :emit
     (fn [{:keys [^CtCatchVariable element]}]
       (let [multi-types (vec (.getMultiTypes element))
             types (if (seq multi-types)
                     multi-types
                     [(.getType element)])
             type-nodes (mapv #(type-node @ctx-holder %) types)
             destinations (mapv #(get (csharp/render %) :text) type-nodes)]
         {:node
          (sequence-node [(if (= 1 (count (distinct destinations)))
                            (first type-nodes)
                            (raw "global::System.Exception"))
                          (raw (str " " (identifier (.getSimpleName element))))])}))}

    {:id :java-library.reference/catch-variable
     :class CtCatchVariableReference
     :emit (fn [{:keys [^CtCatchVariableReference element]}]
             {:node (raw (identifier (.getSimpleName element)))})}

    {:id :java-library.expression/this
     :class CtThisAccess
     :emit (fn [{:keys [^CtThisAccess element]}]
             {:node (expression-cast-node
                     @ctx-holder element
                     (this-node @ctx-holder element))})}

    {:id :java-library.declaration/local-class
     :class CtClass
     :emit
     (fn [{:keys [^CtClass element]}]
       (if (or (local-type? element) (anonymous-class? element))
         {:node (raw "")}
         (unsupported! "Only method-local or anonymous classes are valid body declarations"
                       element)))}

    {:id :java-library.declaration/local-constructor-shell
     :class CtConstructor
     :emit (fn [_] {:node (raw "")})}

    {:id :java-library.declaration/local-field-shell
     :class CtField
     :emit (fn [_] {:node (raw "")})}

    {:id :java-library.declaration/local-method-shell
     :class CtMethod
     :emit (fn [_] {:node (raw "")})}

    {:id :java-library.trivia/comment
     :class CtComment
     :emit (fn [_] {:node (raw "")})}

    {:id :java-library.reference/package
     :class CtPackageReference
     :emit (fn [_] {:node (raw "")})}]))

(defn create-body-context
  "Creates the shared accepted body translator for a resolved Java model.
  `ctx-holder` contains the destination emission context so target bundles can
  supply explicit type-shape and resolved-symbol adaptations without replacing
  ordinary Java structural or standard-library rules."
  [resolved-model ctx-holder]
  (java/context resolved-model
    {:mode :accepted
     :rules (body-rules ctx-holder)
     :mappings (semantic-mappings resolved-model ctx-holder)}))

(defn translate-body
  "Translates and gates one executable body or initializer with a shared Java
  library body context."
  [translation-context element]
  (java/coverage-gate! (java/translate-element translation-context element)))

(defn explicit-constructor-invocation
  "Returns the first live this/super constructor invocation when Java requires
  it to precede all body statements."
  [translation-context ^CtBlock body]
  (when-let [first-statement (first (.getStatements body))]
    (when (and (instance? CtInvocation first-statement)
               (not (.isImplicit ^CtElement first-statement)))
      (let [resolved (invocation-occurrence translation-context first-statement)]
        (when (= :constructor (:kind resolved)) first-statement)))))

(defn- translated-node [ctx ^CtElement element]
  (let [translation (translate-body (:body-context ctx) element)]
    (swap! (:body-translations ctx) conj translation)
    (:node translation)))

(defn- declaration-id [^CtElement element kind]
  (let [{:keys [file line column]} (spoon/source-location element)]
    (str (name kind) ":" (or file "implicit") ":" (or line 0) ":"
         (or column 0) ":" (.getName (class element)))))

(defn- destination-owner-name [ctx ^CtType type]
  (or (some-> ^IdentityHashMap (:destination-owner-overrides ctx) (.get type))
      (str (destination-namespace ctx type) "."
           (str/join "." (map #(pascal (.getSimpleName ^CtType %))
                              (declaring-types type))))))

(defn- public-derived-type? [^CtType type]
  (let [qualified-name (.getQualifiedName type)
        all-types (.getAllTypes (.getModel (.getFactory type)))]
    (boolean
     (some
      (fn [^CtType candidate]
        (when (and (instance? CtClass candidate)
                   (.hasModifier ^CtModifiable candidate ModifierKind/PUBLIC))
          (loop [reference (.getSuperclass ^CtClass candidate)]
            (when reference
              (or (= qualified-name (.getQualifiedName ^CtTypeReference reference))
                  (when-let [declaration
                             (.getTypeDeclaration ^CtTypeReference reference)]
                    (when (instance? CtClass declaration)
                      (recur (.getSuperclass ^CtClass declaration)))))))))
      all-types))))

(def ^:private public-signature-types-cache (WeakHashMap.))

(declare base-type-references)

(defn- reference-type-declaration
  [types-by-name ^CtTypeReference reference]
  (or (get types-by-name (.getQualifiedName reference))
      (.getTypeDeclaration reference)))

(defn- collect-reference-types!
  [^IdentityHashMap result types-by-name ^CtTypeReference reference]
  (when reference
    (when-let [declaration (reference-type-declaration types-by-name reference)]
      (when (and (instance? CtType declaration)
                 (not (.isShadow ^CtType declaration)))
        (.put result declaration true)))
    (when (instance? CtArrayTypeReference reference)
      (collect-reference-types!
       result types-by-name
       (.getComponentType ^CtArrayTypeReference reference)))
    (doseq [argument (.getActualTypeArguments reference)]
      (collect-reference-types! result types-by-name argument)))
  result)

(defn- signature-references [^CtElement member]
  (cond
    (instance? CtField member)
    [(.getType ^CtField member)]

    (instance? CtMethod member)
    (into [(.getType ^CtMethod member)]
          (map #(.getType ^CtParameter %)
               (.getParameters ^CtMethod member)))

    (instance? CtConstructor member)
    (mapv #(.getType ^CtParameter %)
          (.getParameters ^CtConstructor member))

    :else []))

(defn- public-signature-type? [^CtType candidate]
  (let [model (.getModel (.getFactory candidate))]
    (locking public-signature-types-cache
      (let [^IdentityHashMap result
            (or
             (.get public-signature-types-cache model)
             (let [all-types
                   (->> (.getAllTypes model)
                        (mapcat
                         #(tree-seq
                           (fn [^CtType type] (seq (.getNestedTypes type)))
                           (fn [^CtType type] (.getNestedTypes type))
                           %))
                        vec)
                   types-by-name
                   (into {} (map (juxt #(.getQualifiedName ^CtType %) identity)
                                 all-types))
                   result (IdentityHashMap.)
                   seeds
                   (filterv
                    #(or (.hasModifier ^CtModifiable % ModifierKind/PUBLIC)
                         (public-derived-type? %))
                    all-types)]
               (doseq [^CtType seed seeds]
                 (.put result seed true))
               (loop [pending seeds
                      index 0]
                 (when (< index (count pending))
                   (let [^CtType owner (nth pending index)
                         referenced (IdentityHashMap.)]
                     (doseq [reference (base-type-references owner)]
                       (collect-reference-types!
                        referenced types-by-name reference))
                     (doseq [^CtElement member (.getTypeMembers owner)
                             :when (and (instance? CtModifiable member)
                                        (accessible-member? member))
                             reference (signature-references member)]
                       (collect-reference-types!
                        referenced types-by-name reference))
                     (let [new-types
                           (->> referenced
                                keys
                                (remove #(.containsKey result %))
                                vec)]
                       (doseq [^CtType type new-types]
                         (.put result type true))
                       (recur (into pending new-types) (inc index))))))
               (.put public-signature-types-cache model result)
               result))]
        (.containsKey result candidate)))))

(defn- emitted-type-visibility [^CtType type]
  (cond
    (or (anonymous-class? type) (local-type? type)) "private"
    (or (.hasModifier ^CtModifiable type ModifierKind/PUBLIC)
        (public-derived-type? type)
        (public-signature-type? type))
    "public"
    (.hasModifier ^CtModifiable type ModifierKind/PROTECTED)
    "protected internal"
    :else "internal"))

(defn- canonical-visibility [visibility]
  (str/replace visibility " " "-"))

(defn- metadata-identifier [value]
  (if (str/starts-with? value "@")
    (subs value 1)
    value))

(defn- executable-implementation [^CtType owner ^CtElement member]
  (cond
    (instance? CtConstructor member)
    (if (.getBody ^CtConstructor member)
      :translated-body
      :public-stub)

    (instance? CtMethod member)
    (cond
      (.getBody ^CtMethod member) :translated-body
      (or (interface-type? owner)
          (.hasModifier ^CtMethod member ModifierKind/ABSTRACT))
      :abstract-contract
      :else :public-stub)

    :else :declaration))

(defn- register-type! [ctx ^CtType type name rule]
  (let [id (declaration-id type :type)
        entry {:id id :java-key (spoon/declaration-key type) :kind :type
               :owner (some-> type .getDeclaringType spoon/declaration-key)
               :name name :signature (.getQualifiedName type)
               :implementation :declaration
               :destination {:assembly (get-in ctx [:configuration :project :assembly-name])
                             :namespace (destination-namespace ctx type)
                             :owner (destination-owner-name ctx type)
                             :kind "type" :name name :parameter-count "0"
                             :visibility
                             (canonical-visibility
                              (emitted-type-visibility type))}
               :source (source-ref type rule nil)}]
    (when (.containsKey ^IdentityHashMap (:emitted ctx) type)
      (fail! "A Java library declaration was emitted more than once"
             {:kind :duplicate-source-declaration :declaration entry}))
    (.put ^IdentityHashMap (:emitted ctx) type entry)
    (swap! (:declarations ctx) conj entry)
    id))

(defn- member-kind [^CtElement member]
  (cond
    (instance? CtConstructor member) :constructor
    (instance? CtMethod member) :method
    (instance? CtField member) :field
    (instance? CtType member) :type
    (instance? CtAnonymousExecutable member) :initializer
    :else :member))

(defn- register-member!
  ([ctx ^CtType owner ^CtElement member name rule]
   (register-member! ctx owner member name rule nil nil))
  ([ctx ^CtType owner ^CtElement member name rule parameter-count-override]
   (register-member! ctx owner member name rule parameter-count-override nil))
  ([ctx ^CtType owner ^CtElement member name rule parameter-count-override
    emitted-visibility]
   (let [kind (member-kind member)
         id (declaration-id member kind)
         parameter-count (or parameter-count-override
                             (if (instance? CtExecutable member)
                               (count (.getParameters ^CtExecutable member))
                               0))
         entry {:id id :java-key (spoon/declaration-key member) :kind kind
                :owner (spoon/declaration-key owner) :name name
                :signature (or (when (instance? CtExecutable member)
                                 (.getSignature ^CtExecutable member))
                               (.getSimpleName member))
                :implementation (executable-implementation owner member)
                :destination {:assembly (get-in ctx [:configuration :project :assembly-name])
                              :namespace (destination-namespace ctx owner)
                              :owner (destination-owner-name ctx owner)
                              :kind (case kind
                                      :constructor "constructor"
                                      :method "method"
                                      :field "field"
                                      :type "type"
                                      :initializer "initializer"
                                      "member")
                              :name (if (= :constructor kind)
                                      ".ctor"
                                      (metadata-identifier name))
                              :parameter-count (str parameter-count)
                              :visibility
                              (some-> emitted-visibility canonical-visibility)}
                :source (source-ref member rule nil)}]
     (when (.containsKey ^IdentityHashMap (:emitted ctx) member)
       (fail! "A Java library declaration was emitted more than once"
              {:kind :duplicate-source-declaration :declaration entry}))
     (.put ^IdentityHashMap (:emitted ctx) member entry)
     (swap! (:declarations ctx) conj entry)
     id)))

(defn- explicit-members [^CtType type]
  (vec
   (remove #(.isImplicit ^CtElement %)
           (concat (when (instance? CtEnum type)
                     (.getEnumValues ^CtEnum type))
                   (.getTypeMembers type)))))

(defn- visibility [^CtModifiable element default]
  (cond
    (.hasModifier element ModifierKind/PUBLIC) "public"
    (.hasModifier element ModifierKind/PROTECTED) "protected"
    (.hasModifier element ModifierKind/PRIVATE) "private"
    :else default))

(defn- member-visibility [^CtType owner ^CtModifiable member default]
  (if (and (.getDeclaringType owner)
           (.hasModifier member ModifierKind/PRIVATE))
    "internal"
    (if (.hasModifier member ModifierKind/PROTECTED)
      "protected internal"
      (visibility member default))))

(defn- implicit-constructor-visibility
  [^CtType owner ^CtConstructor constructor]
  (member-visibility
   owner constructor
   (cond
     (.hasModifier owner ModifierKind/PUBLIC) "public"
     (.hasModifier owner ModifierKind/PROTECTED) "protected internal"
     (.hasModifier owner ModifierKind/PRIVATE) "private"
     :else "internal")))

(defn- type-formals-node [^CtType type]
  (let [parameters (vec (.getFormalCtTypeParameters type))]
    (when (seq parameters)
      (sequence-node [(raw "<")
                      (sequence-node
                       (mapv #(raw (destination-type-parameter-name %))
                             parameters)
                       ", ")
                      (raw ">")]))))

(defn- executable-formals-node [^CtExecutable executable]
  (let [parameters (vec (.getFormalCtTypeParameters executable))]
    (when (seq parameters)
      (sequence-node [(raw "<")
                      (sequence-node
                       (mapv #(raw (destination-type-parameter-name %))
                             parameters)
                       ", ")
                      (raw ">")]))))

(defn- constraints-node [ctx parameters]
  (let [clauses
        (keep
         (fn [^CtTypeParameter parameter]
           (let [bounds
                 (remove
                  #(= "java.lang.Object"
                      (.getQualifiedName ^CtTypeReference %))
                  (type-parameter-bound-references parameter))]
             (when (seq bounds)
               (sequence-node
                [(raw (str " where "
                           (destination-type-parameter-name parameter)
                           " : "))
                 (sequence-node (mapv #(type-node ctx %) bounds) ", ")]))))
         parameters)]
    (when (seq clauses)
      (sequence-node clauses))))

(defn- parameter-node [ctx ^CtParameter parameter]
  (let [parent (when (.isParentInitialized parameter) (.getParent parameter))
        object-equals-parameter?
        (and (instance? CtMethod parent)
             (= "equals" (.getSimpleName ^CtMethod parent))
             (= "java.lang.Object" (.getQualifiedName (.getType parameter))))]
    (sequence-node
     [(when (.isVarArgs parameter) (raw "params "))
      (if object-equals-parameter?
        (raw (if (= "enable" (get-in ctx [:configuration :project :nullable]))
               "object?"
               "object"))
        (declaration-type-node ctx parameter (.getType parameter)))
      (raw (str " " (identifier (.getSimpleName parameter))))])))

(defn- base-type-references [^CtType type]
  (vec
   (concat
    (when (and (instance? CtClass type)
               (not (instance? CtEnum type)))
      (when-let [superclass (.getSuperclass ^CtClass type)]
        (when-not (= "java.lang.Object" (.getQualifiedName superclass))
          [superclass])))
    (sort-by #(.getQualifiedName ^CtTypeReference %)
             (.getSuperInterfaces type)))))

(def ^:private functional-interface-types
  #{"java.util.concurrent.Callable" "java.util.function.Supplier"})

(defn- java-contract-base-node
  [ctx ^CtTypeReference reference target]
  (let [arguments (vec (.getActualTypeArguments reference))
        arity (if (= "java.util.Map" (.getQualifiedName reference)) 2 1)
        arguments (if (seq arguments)
                    (mapv #(type-node ctx %) arguments)
                    (vec (repeat arity (raw "object"))))]
    (csharp/generic-name (raw target) arguments)))

(defn- base-type-node [ctx ^CtType owner ^CtTypeReference reference]
  (cond
    (and (instance? CtClass owner)
         (contains? functional-interface-types (.getQualifiedName reference)))
    nil

    (contains? (get-in ctx [:configuration :destination-capabilities])
               :java-compat)
    (case (.getQualifiedName reference)
      "java.io.InputStream" (raw "global::DripSharp.Runtime.JavaInputStream")
      "java.io.OutputStream" (raw "global::DripSharp.Runtime.JavaOutputStream")
      "java.lang.Iterable"
      (java-contract-base-node
       ctx reference "global::DripSharp.Runtime.JavaIterableContract")
      "java.util.List"
      (java-contract-base-node
       ctx reference "global::DripSharp.Runtime.JavaListContract")
      "java.util.Map"
      (java-contract-base-node
       ctx reference "global::DripSharp.Runtime.JavaMapContract")
      (type-node ctx reference))

    :else
    (type-node ctx reference)))

(declare field-anonymous-calls)

(defn- enum-constant-specific-class? [^CtEnum type]
  (boolean (some #(seq (field-anonymous-calls %)) (.getEnumValues type))))

(defn- type-words [^CtType type]
  (let [visibility (emitted-type-visibility type)]
    (cond
      (interface-type? type) [visibility "interface"]
      (instance? CtEnum type)
      (remove nil?
              [visibility
               (when (.hasModifier ^CtModifiable type ModifierKind/ABSTRACT)
                 "abstract")
               (when-not (enum-constant-specific-class? type) "sealed")
               "class"])
      (instance? CtClass type)
      (remove nil?
              [visibility
               (when (.hasModifier ^CtModifiable type ModifierKind/ABSTRACT)
                 "abstract")
               (when (.hasModifier ^CtModifiable type ModifierKind/FINAL)
                 "sealed")
               (when (anonymous-class? type) "sealed")
               "class"])
      :else nil)))

(defn- iterator-implementation? [^CtType owner]
  (and (instance? CtClass owner)
       (some #(= "java.util.Iterator" (.getQualifiedName ^CtTypeReference %))
             (.getSuperInterfaces owner))))

(defn- x509-trust-manager-implementation? [^CtType owner]
  (and (anonymous-class? owner)
       (some #(= "javax.net.ssl.X509TrustManager"
                 (.getQualifiedName ^CtTypeReference %))
             (.getSuperInterfaces owner))))

(defn- java-stream-subclass? [^CtType owner]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (let [qualified (.getQualifiedName ^CtTypeReference reference)]
        (or (contains? #{"java.io.InputStream" "java.io.OutputStream"
                         "java.io.FilterOutputStream"}
                       qualified)
            (when-let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
              (when (instance? CtClass declaration)
                (recur (.getSuperclass ^CtClass declaration)))))))))

(defn- java-linked-hash-map-subclass? [^CtType owner]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (let [qualified (.getQualifiedName ^CtTypeReference reference)]
        (or (= "java.util.LinkedHashMap" qualified)
            (when-let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
              (when (instance? CtClass declaration)
                (recur (.getSuperclass ^CtClass declaration)))))))))

(declare superclass-method)

(def ^:private java-contract-interface-types
  #{"java.lang.Iterable" "java.util.List" "java.util.Map"
    "java.util.Map$Entry"})

(defn- java-contract-implementation? [^CtType owner]
  (boolean
   (some #(contains? java-contract-interface-types
                     (.getQualifiedName ^CtTypeReference %))
         (.getSuperInterfaces owner))))

(defn- ordinary-method-name [ctx ^CtType owner ^CtMethod method]
  (let [normalized (ordinary-member-name ctx method)]
    (if (= normalized (pascal (.getSimpleName owner)))
      (identifier (.getSimpleName method))
      normalized)))

(defn- method-name [ctx ^CtType owner ^CtMethod method]
  (cond
    (and (= "removeEldestEntry" (.getSimpleName method))
         (= 1 (count (.getParameters method)))
         (java-linked-hash-map-subclass? owner))
    "RemoveEldestEntry"

    (and (= "close" (.getSimpleName method))
         (empty? (.getParameters method))
         (not (java-stream-subclass? owner)))
    "Dispose"

    (and (= "toString" (.getSimpleName method))
         (empty? (.getParameters method)))
    "ToString"

    (and (= "hashCode" (.getSimpleName method))
         (empty? (.getParameters method)))
    "GetHashCode"

    (and (= "equals" (.getSimpleName method))
         (= 1 (count (.getParameters method))))
    "Equals"

    (iterator-implementation? owner)
    (case (.getSimpleName method)
      "hasNext" "HasNext"
      "next" "Next"
      "remove" "Remove"
      (ordinary-member-name ctx method))

    (java-contract-implementation? owner)
    (csharp-public-name (.getSimpleName method))

    (x509-trust-manager-implementation? owner)
    (case (.getSimpleName method)
      "getAcceptedIssuers" "GetAcceptedIssuers"
      "checkServerTrusted" "CheckServerTrusted"
      "checkClientTrusted" "CheckClientTrusted"
      (ordinary-method-name ctx owner method))

    (superclass-method owner method)
    (let [^CtMethod parent (superclass-method owner method)]
      (method-name ctx (.getDeclaringType parent) parent))

    :else (ordinary-method-name ctx owner method)))

(defn- destination-object-method? [^CtMethod method]
  (or (and (= "toString" (.getSimpleName method))
           (empty? (.getParameters method)))
      (and (= "hashCode" (.getSimpleName method))
           (empty? (.getParameters method)))
      (and (= "equals" (.getSimpleName method))
           (= 1 (count (.getParameters method))))))

(defn- override-compatible-type?
  [^CtTypeReference left ^CtTypeReference right]
  (or (= (.getQualifiedName left) (.getQualifiedName right))
      (instance? CtTypeParameterReference left)
      (instance? CtTypeParameterReference right)
      (instance? CtWildcardReference left)
      (instance? CtWildcardReference right)
      (and (instance? CtArrayTypeReference left)
           (instance? CtArrayTypeReference right)
           (override-compatible-type?
            (.getComponentType ^CtArrayTypeReference left)
            (.getComponentType ^CtArrayTypeReference right)))))

(defn- override-compatible-method?
  [^CtMethod left ^CtMethod right]
  (let [left-parameters (vec (.getParameters left))
        right-parameters (vec (.getParameters right))]
    (and (= (.getSimpleName left) (.getSimpleName right))
         (= (count left-parameters) (count right-parameters))
         (every?
          true?
          (map
           #(override-compatible-type? (.getType ^CtParameter %1)
                                       (.getType ^CtParameter %2))
           left-parameters
           right-parameters)))))

(defn- inherited-superclass-method?
  [^CtType owner ^CtMethod candidate]
  (and (not (.hasModifier candidate ModifierKind/PRIVATE))
       (not (.hasModifier candidate ModifierKind/STATIC))
       (or (.hasModifier candidate ModifierKind/PUBLIC)
           (.hasModifier candidate ModifierKind/PROTECTED)
           (= (package-name owner)
              (package-name (.getDeclaringType candidate))))))

(defn- superclass-method [^CtType owner ^CtMethod method]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
        (if-not (instance? CtClass declaration)
          false
          (let [methods
                (vec
                 (filter
                  #(inherited-superclass-method? owner %)
                  (.getMethodsByName
                   ^CtClass declaration (.getSimpleName method))))]
            (or (some
                 #(when (or (.isOverriding method ^CtMethod %)
                            (= (.getSignature method)
                               (.getSignature ^CtMethod %))
                            (override-compatible-method? method %))
                    %)
                 methods)
                (recur (.getSuperclass ^CtClass declaration)))))))))

(def ^:private clr-value-type-covariant-returns
  #{"java.lang.Boolean" "java.lang.Byte" "java.lang.Character"
    "java.lang.Double" "java.lang.Float" "java.lang.Integer"
    "java.lang.Long" "java.lang.Short" "java.util.Calendar"
    "java.time.Instant" "java.time.LocalDateTime"
    "java.time.OffsetDateTime" "java.time.ZonedDateTime"})

(defn- covariant-value-override?
  [^CtType owner ^CtMethod method]
  (when-let [super-method (superclass-method owner method)]
    (and
     (= "java.lang.Object"
        (.getQualifiedName (.getType ^CtMethod super-method)))
     (contains? clr-value-type-covariant-returns
                (.getQualifiedName (.getType method))))))

(defn- emitted-method-return-type
  [^CtType owner ^CtMethod method]
  (if (covariant-value-override? owner method)
    (.getType ^CtMethod (superclass-method owner method))
    (.getType method)))

(defn- interface-methods [^CtType type]
  (letfn [(methods-for [^CtTypeReference reference]
            (when-let [declaration (.getTypeDeclaration reference)]
              (when (and (interface-type? declaration)
                         (not (.isShadow ^CtType declaration)))
                (concat
                 (map (fn [^CtMethod method] [reference method])
                      (.getMethods ^CtInterface declaration))
                 (mapcat methods-for (.getSuperInterfaces ^CtInterface declaration))))))]
    (->> (.getSuperInterfaces type)
         (mapcat methods-for)
         (sort-by (fn [[^CtTypeReference reference ^CtMethod method]]
                    [(.getQualifiedName reference) (.getSignature method)]))
         vec)))

(defn- inherited-abstract-interface-method
  [^CtType owner ^CtMethod method]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
        (when (instance? CtClass declaration)
          (or
           (when (.hasModifier ^CtModifiable declaration ModifierKind/ABSTRACT)
             (some
              (fn [[_ ^CtMethod candidate]]
                (when (and (nil? (.getBody candidate)) (not (.hasModifier candidate ModifierKind/STATIC)) (or (.isOverriding method candidate) (= (.getSignature method) (.getSignature candidate)) (override-compatible-method? method candidate))) candidate))
              (interface-methods declaration)))
           (recur (.getSuperclass ^CtClass declaration))))))))

(def ^:private runtime-abstract-interface-types
  #{"java.awt.Paint" "java.awt.PaintContext"})

(defn- java-map-entry-implementation? [^CtType owner]
  (boolean
   (some #(= "java.util.Map$Entry"
             (.getQualifiedName ^CtTypeReference %))
         (.getSuperInterfaces owner))))

(defn- runtime-interface-method-shape?
  [interface-name ^CtMethod method]
  (let [name (.getSimpleName method)
        arity (count (.getParameters method))]
    (case interface-name
      "java.awt.Paint"
      (or (and (= "createContext" name) (= 5 arity))
          (and (= "getTransparency" name) (zero? arity)))

      "java.awt.PaintContext"
      (and (= "getRaster" name) (= 4 arity))

      false)))

(defn- inherited-runtime-interface-method [^CtType owner ^CtMethod method]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (when-let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
        (when (instance? CtClass declaration)
          (or
           (some
            #(runtime-interface-method-shape?
              (.getQualifiedName ^CtTypeReference %) method)
            (.getSuperInterfaces ^CtClass declaration))
           (recur (.getSuperclass ^CtClass declaration))))))))

(defn- direct-interface-method [^CtType owner ^CtMethod method]
  (some
   (fn [[_ ^CtMethod candidate]]
     (when (or (= (.getSignature method) (.getSignature candidate))
               (override-compatible-method? method candidate))
       candidate))
   (interface-methods owner)))

(defn- subtype-of? [^CtType candidate ^CtType ancestor]
  (loop [reference (when (instance? CtClass candidate)
                     (.getSuperclass ^CtClass candidate))]
    (when reference
      (or (= (.getQualifiedName ancestor)
             (.getQualifiedName ^CtTypeReference reference))
          (when-let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
            (when (instance? CtClass declaration)
              (recur (.getSuperclass ^CtClass declaration))))))))

(defn- override-family-root [^CtType owner ^CtMethod method]
  (loop [current-owner owner current-method method]
    (if-let [parent-method (superclass-method current-owner current-method)]
      (recur (.getDeclaringType ^CtMethod parent-method) parent-method)
      [current-owner current-method])))

(defn- override-family-has-modifier?
  [^CtType owner ^CtMethod method modifier]
  (let [[root-owner root-method] (override-family-root owner method)
        simple-name (.getSimpleName ^CtMethod root-method)
        all-types
        (mapcat (fn [^CtType root]
                  (tree-seq (fn [^CtType type] (seq (.getNestedTypes type)))
                            (fn [^CtType type] (.getNestedTypes type))
                            root))
                (.getAllTypes (.getModel (.getFactory owner))))]
    (boolean
     (some
      (fn [^CtType candidate-owner]
        (when (or (= (.getQualifiedName root-owner)
                     (.getQualifiedName candidate-owner))
                  (subtype-of? candidate-owner root-owner))
          (some #(and (override-compatible-method? root-method %)
                      (.hasModifier ^CtMethod % modifier))
                (.getMethodsByName candidate-owner simple-name))))
      all-types))))

(defn- public-override-family? [^CtType owner ^CtMethod method]
  (override-family-has-modifier? owner method ModifierKind/PUBLIC))

(defn- protected-override-family? [^CtType owner ^CtMethod method]
  (override-family-has-modifier? owner method ModifierKind/PROTECTED))

(defn- superclass-implements-closeable? [^CtType owner]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
        (when (instance? CtClass declaration)
          (or (some #(= "java.io.Closeable" (.getQualifiedName ^CtTypeReference %))
                    (.getSuperInterfaces ^CtClass declaration))
              (recur (.getSuperclass ^CtClass declaration))))))))

(defn- method-words [^CtType owner ^CtMethod method]
  (let [static? (.hasModifier method ModifierKind/STATIC)
        abstract? (and (not (interface-type? owner))
                       (.hasModifier method ModifierKind/ABSTRACT))
        super-method (when-not static? (superclass-method owner method))
        inherited-runtime-interface-method
        (when-not static? (inherited-runtime-interface-method owner method))
        inherited-abstract-interface-method
        (when-not static?
          (inherited-abstract-interface-method owner method))
        redeclared-interface-method
        (when (interface-type? owner)
          (direct-interface-method owner method))
        widened-override-family? (public-override-family? owner method)
        protected-override-family? (protected-override-family? owner method)
        interface-dispose?
        (and (interface-type? owner)
             (= "close" (.getSimpleName method))
             (empty? (.getParameters method)))
        override? (and (not static?)
                       (or (destination-object-method? method)
                           (and (java-map-entry-implementation? owner)
                                (= "setValue" (.getSimpleName method))
                                (= 1 (count (.getParameters method))))
                           (and (= "close" (.getSimpleName method))
                                (empty? (.getParameters method))
                                (superclass-implements-closeable? owner))
                           (and (not (and (= "getMessage" (.getSimpleName method))
                                          (empty? (.getParameters method))))
                                super-method)
                           inherited-abstract-interface-method
                           inherited-runtime-interface-method))
        virtual? (and (instance? CtClass owner)
                      (or (not (instance? CtEnum owner))
                          (enum-constant-specific-class? owner))
                      (not (anonymous-class? owner))
                      (not (local-type? owner))
                      (not (.hasModifier ^CtModifiable owner ModifierKind/FINAL))
                      (not static?)
                      (not abstract?)
                      (not override?)
                      (not (.hasModifier method ModifierKind/PRIVATE))
                      (not (.hasModifier method ModifierKind/FINAL)))]
    (remove nil?
            [(cond
               (and (= "removeEldestEntry" (.getSimpleName method))
                    (= 1 (count (.getParameters method)))
                    (java-linked-hash-map-subclass? owner))
               "protected internal"

               widened-override-family? "public"

               protected-override-family? "protected internal"

               :else
               (member-visibility owner method
                                  (if (interface-type? owner)
                                    "public"
                                    "internal")))
             (when static? "static")
             (when abstract? "abstract")
             (when override? "override")
             (when (or interface-dispose?
                       redeclared-interface-method
                       (and (= "getType" (.getSimpleName method))
                            (empty? (.getParameters method))
                            (not (interface-type? owner))))
               "new")
             (when virtual? "virtual")])))

(declare member-node emit-root emit-anonymous-type owner-type-node)

(defn- enclosing-executable [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtExecutable current) current
      (.isParentInitialized ^CtElement current) (recur (.getParent ^CtElement current))
      :else nil)))

(defn- executable-local-types [^CtExecutable executable]
  (->> (.getElements executable (TypeFilter. CtType))
       (filter local-type?)
       (filter #(identical? executable (enclosing-executable %)))
       (sort-by (fn [^CtType type]
                  (let [{:keys [file line column]} (spoon/source-location type)]
                    [file line column (.getQualifiedName type)])))
       vec))

(defn- executable-anonymous-calls [^CtExecutable executable]
  (->> (.getElements executable (TypeFilter. CtConstructorCall))
       (filter anonymous-class-for-call)
       (filter #(identical? executable (enclosing-executable %)))
       (sort-by (fn [^CtElement call]
                  (let [{:keys [file line column]} (spoon/source-location call)]
                    [file line column])))
       vec))

(defn- field-anonymous-calls [^CtField field]
  (if-let [initializer (.getDefaultExpression field)]
    (->> (.getElements initializer (TypeFilter. CtConstructorCall))
         (filter anonymous-class-for-call)
         (sort-by (fn [^CtElement call]
                    (let [{:keys [file line column]} (spoon/source-location call)]
                      [file line column])))
         vec)
    []))

(defn- initializer-uses-this? [^CtField field]
  (boolean
   (when-let [initializer (.getDefaultExpression field)]
     (seq (.getElements initializer (TypeFilter. CtThisAccess))))))

(defn- annotation-methods [^CtAnnotationType annotation-type]
  (->> (.getAnnotationMethods annotation-type)
       (sort-by
        (fn [^CtAnnotationMethod method]
          (let [{:keys [file line column]} (spoon/source-location method)]
            [file line column (.getSimpleName method)])))
       vec))

(defn- project-annotation-declaration
  [ctx ^CtAnnotation annotation]
  (let [occurrence (occurrence! ctx annotation :annotation)
        declaration (:declaration occurrence)]
    (when (and (= :project (:origin occurrence))
               (instance? CtAnnotationType declaration))
      declaration)))

(defn- enum-reference? [^CtTypeReference reference]
  (instance? CtEnum (.getTypeDeclaration reference)))

(defn- annotation-value-node
  [ctx ^CtAnnotation annotation ^CtAnnotationMethod method]
  (let [value (.getValue annotation (.getSimpleName method))]
    (when-not value
      (unsupported! "Java annotation value and default are both absent" annotation))
    (if (enum-reference? (.getType method))
      (if (instance? CtVariableAccess value)
        (raw (pr-str (.getSimpleName
                      (.getVariable ^CtVariableAccess value))))
        (unsupported! "Java enum annotation value is not a resolved enum constant"
                      value))
      (translated-node ctx value))))

(defn- annotation-attribute-name-node
  [ctx ^CtAnnotationType annotation-type]
  (raw
   (str "global::" (destination-namespace ctx annotation-type) "."
        (pascal (.getSimpleName annotation-type)) "Attribute")))

(defn- project-annotation-node
  [ctx ^CtAnnotation annotation]
  (when-let [annotation-type
             (project-annotation-declaration ctx annotation)]
    (sequence-node
     [(raw "[")
      (annotation-attribute-name-node ctx annotation-type)
      (raw "(")
      (sequence-node
       (mapv #(annotation-value-node ctx annotation %)
             (annotation-methods annotation-type))
       ", ")
      (raw ")]\n")])))

(defn- project-annotation-attributes-node [ctx ^CtElement element]
  (sequence-node
   (keep #(project-annotation-node ctx %) (.getAnnotations element))))

(defn- java-serialization-uid? [^CtField field]
  (and (= "serialVersionUID" (.getSimpleName field))
       (= "long" (.getQualifiedName (.getType field)))
       (.hasModifier field ModifierKind/STATIC)
       (.hasModifier field ModifierKind/FINAL)))

(defn- compile-time-constant-expression?
  [expression]
  (or (instance? CtLiteral expression)
      (and (instance? CtUnaryOperator expression)
           (contains? #{"NEG" "POS" "COMPL" "NOT"}
                      (str (.getKind ^CtUnaryOperator expression)))
           (compile-time-constant-expression?
            (.getOperand ^CtUnaryOperator expression)))
      (and (instance? CtBinaryOperator expression)
           (contains? #{"OR" "AND" "BITOR" "BITXOR" "BITAND"
                        "EQ" "NE" "LT" "LE" "GT" "GE"
                        "SL" "SR" "USR" "PLUS" "MINUS" "MUL" "DIV" "MOD"}
                      (str (.getKind ^CtBinaryOperator expression)))
           (compile-time-constant-expression?
            (.getLeftHandOperand ^CtBinaryOperator expression))
           (compile-time-constant-expression?
            (.getRightHandOperand ^CtBinaryOperator expression)))))

(def ^:private mapped-source-value-types
  #{"java.awt.Rectangle"
    "java.awt.geom.AffineTransform"
    "java.awt.geom.Rectangle2D"
    "java.awt.geom.Rectangle2D$Double"
    "java.awt.geom.Rectangle2D$Float"})

(defn- superclass-field
  [ctx ^CtType owner ^CtField field]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
        (when (and (instance? CtClass declaration)
                   (not (.isShadow ^CtClass declaration)))
          (or
           (some
            #(when (and
                    (not (.hasModifier ^CtField % ModifierKind/PRIVATE))
                    (= (destination-field-name ctx field)
                       (destination-field-name ctx ^CtField %)))
               %)
            (.getFields ^CtClass declaration))
           (recur (.getSuperclass ^CtClass declaration))))))))

(defn- enum-value-ordinal [^CtEnum enum ^CtEnumValue value]
  (first
   (keep-indexed
    (fn [index ^CtEnumValue candidate]
      (when (identical? candidate value) index))
    (.getEnumValues enum))))

(defn- field-node [ctx ^CtType owner ^CtField field]
  (let [enum-value? (instance? CtEnumValue field)
        enum-ordinal (when enum-value?
                       (enum-value-ordinal ^CtEnum owner ^CtEnumValue field))
        initializer (.getDefaultExpression field)
        compile-time-constant?
        (and (not enum-value?)
             (.hasModifier field ModifierKind/STATIC)
             (.hasModifier field ModifierKind/FINAL)
             (compile-time-constant-expression? initializer)
             (or (.isPrimitive (.getType field))
                 (= "java.lang.String" (.getQualifiedName (.getType field)))))
        deferred?
        (boolean
         (some #(identical? field %)
               (:deferred-field-initializers ctx)))
        initializer-node
        (when (and initializer (not deferred?))
          (assignment-value-node
           ctx field initializer (translated-node ctx initializer)))
        java-default-null?
        (and (nil? initializer)
             (not enum-value?)
             (not (.isPrimitive (.getType field)))
             (not (instance? CtTypeParameterReference (.getType field)))
             (not (contains? mapped-source-value-types
                             (.getQualifiedName (.getType field))))
             (not (contains?
                   #{"java.lang.Boolean" "java.lang.Byte" "java.lang.Character"
                     "java.lang.Double" "java.lang.Float" "java.lang.Integer"
                     "java.lang.Long" "java.lang.Short" "java.util.Calendar"}
                   (.getQualifiedName (.getType field))))
             (not (resolved-annotation?
                   ctx field "annotation:javax.annotation.Nullable")))
        java-default-value?
        (and (nil? initializer)
             (or
              (instance? CtTypeParameterReference (.getType field))
              (contains? mapped-source-value-types
                         (.getQualifiedName (.getType field)))
              (contains?
               #{"java.lang.Boolean" "java.lang.Byte" "java.lang.Character"
                 "java.lang.Double" "java.lang.Float" "java.lang.Integer"
                 "java.lang.Long" "java.lang.Short" "java.util.Calendar"}
               (.getQualifiedName (.getType field)))))
        name (destination-field-name ctx field)
        rule (if enum-value?
               :java-library.declaration/enum-value
               :java-library.declaration/field)
        emitted-visibility
        (cond
          enum-value? "public"
          (java-serialization-uid? field) "internal"
          :else (member-visibility owner field "internal"))
        id (register-member! ctx owner field name rule nil emitted-visibility)]
    (csharp/with-source
      (sequence-node
       [(when enum-value?
          (sequence-node
           [(raw
             (str "[global::DripSharp.Runtime.JavaEnumNameAttribute(\""
                  (.getSimpleName field)
                  "\")]\n"))
            (raw
             (str "[global::DripSharp.Runtime.JavaEnumOrdinalAttribute("
                  enum-ordinal
                  ")]\n"))]))
        (project-annotation-attributes-node ctx field)
        (raw (str (str/join " "
                            (remove nil?
                                    [(if enum-value?
                                       "public"
                                       emitted-visibility)
                                     (when (or enum-value?
                                               (.hasModifier field ModifierKind/STATIC))
                                       (when-not compile-time-constant? "static"))
                                     (when (and (not enum-value?)
                                                (superclass-field ctx owner field))
                                       "new")
                                     (when (.hasModifier field ModifierKind/VOLATILE)
                                       "volatile")
                                     (when compile-time-constant? "const")
                                     (when (and (not compile-time-constant?)
                                                (or enum-value?
                                                    (.hasModifier field ModifierKind/FINAL)))
                                       "readonly")]))
                  " "))
        (declaration-type-node ctx field (.getType field))
        (raw (str " " name))
        (when initializer-node
          (sequence-node [(raw " = ") initializer-node]))
        (when java-default-null? (raw " = null!"))
        (when java-default-value?
          (raw (if (instance? CtTypeParameterReference (.getType field))
                 " = default!"
                 " = default")))
        (raw ";")])
      (source-ref field rule {:declaration-id id :declaration-kind :field}))))

(defn- method-node [ctx ^CtType owner ^CtMethod method]
  (let [local-types (mapv validate-local-type!
                          (executable-local-types method))
        anonymous-types (mapv #(emit-anonymous-type ctx owner %)
                              (executable-anonymous-calls method))
        body (.getBody method)
        body-node (when body (translated-node ctx body))
        name (method-name ctx owner method)
        rule :java-library.declaration/method
        words (method-words owner method)
        formals (vec (.getFormalCtTypeParameters method))
        id (register-member! ctx owner method name rule nil (first words))
        method-node
        (csharp/with-source
          (csharp/declaration
           (sequence-node
            [(raw (str (str/join " " words) " "))
             (declaration-type-node ctx method
                                    (emitted-method-return-type owner method))
             (raw (str " " name))
             (executable-formals-node method)
             (raw "(")
             (sequence-node (mapv #(parameter-node ctx %) (.getParameters method)) ", ")
             (raw ")")
             (when-not (some #{"override"} words)
               (constraints-node ctx formals))])
           body-node
           {:declaration-kind :method
            :name name
            :source-name (.getSimpleName method)
            :source-qualified-name (.getQualifiedName owner)
            :parameter-count (count (.getParameters method))})
          (source-ref method rule
                      {:declaration-id id :declaration-kind :method}))]
    (sequence-node
     (into [method-node]
           (concat (map #(emit-root ctx %) local-types)
                   anonymous-types))
     "\n\n")))

(defn- matching-class-method [^CtType owner ^CtMethod interface-method]
  (or
   (some
    #(when (= (.getSignature interface-method) (.getSignature ^CtMethod %)) %)
    (.getMethodsByName owner (.getSimpleName interface-method)))
   (superclass-method owner interface-method)))

(defn- interface-contract-nodes [ctx ^CtType owner]
  (when (instance? CtClass owner)
    (->> (interface-methods owner)
         (keep
          (fn [[^CtTypeReference interface-reference ^CtMethod interface-method]]
            (when (and (nil? (.getBody interface-method))
                       (not (.hasModifier interface-method ModifierKind/STATIC)))
              (let [implementation
                    (matching-class-method owner interface-method)
                    interface-return
                    (.getQualifiedName (.getType interface-method))
                    implementation-return
                    (some-> ^CtMethod implementation .getType .getQualifiedName)
                    name (method-name ctx
                                      (.getDeclaringType interface-method)
                                      interface-method)]
                (cond
                  (and implementation
                       (not= interface-return implementation-return))
                  (let [parameters (vec (.getParameters interface-method))]
                    (sequence-node
                     [(declaration-type-node ctx
                                             interface-method
                                             (.getType interface-method))
                      (raw " ")
                      (type-node ctx interface-reference)
                      (raw (str "." name))
                      (executable-formals-node interface-method)
                      (raw "(")
                      (sequence-node (mapv #(parameter-node ctx %) parameters) ", ")
                      (raw ") => this.")
                      (raw (method-name ctx owner implementation))
                      (raw "(")
                      (sequence-node
                       (mapv #(raw (identifier (.getSimpleName ^CtParameter %)))
                             parameters)
                       ", ")
                      (raw ");")]))

                  implementation
                  nil

                  (.hasModifier ^CtModifiable owner ModifierKind/ABSTRACT)
                  (let [parameters (vec (.getParameters interface-method))]
                    (sequence-node
                     [(raw "public abstract ")
                      (declaration-type-node ctx
                                             interface-method
                                             (.getType interface-method))
                      (raw (str " " name))
                      (executable-formals-node interface-method)
                      (raw "(")
                      (sequence-node (mapv #(parameter-node ctx %) parameters) ", ")
                      (raw ");")]))

                  :else
                  (unsupported!
                   "Concrete Java class has no resolved interface implementation"
                   owner))))))
         vec)))

(defn- runtime-interface-contract-nodes [^CtType owner]
  (when (and (instance? CtClass owner)
             (.hasModifier ^CtModifiable owner ModifierKind/ABSTRACT))
    (->> (.getSuperInterfaces owner)
         (mapcat
          (fn [^CtTypeReference reference]
            (case (.getQualifiedName reference)
              "java.awt.Paint"
              (remove
               nil?
               [(when (empty? (.getMethodsByName owner "createContext"))
                  (raw
                   (str
                    "public abstract global::DripSharp.Runtime.JavaPaintContext "
                    "CreateContext("
                    "global::DripSharp.Runtime.JavaColorModel colorModel, "
                    "global::SkiaSharp.SKRectI deviceBounds, "
                    "global::SkiaSharp.SKRect userBounds, "
                    "global::SkiaSharp.SKMatrix transform, "
                    "global::DripSharp.Runtime.PdfCubeRenderingHints hints);")))
                (when (empty? (.getMethodsByName owner "getTransparency"))
                  (raw "public abstract int GetTransparency();"))])

              "java.awt.PaintContext"
              (remove
               nil?
               [(when (empty? (.getMethodsByName owner "getRaster"))
                  (raw
                   (str
                    "public abstract global::DripSharp.Runtime.JavaRaster "
                    "GetRaster(int x, int y, int width, int height);")))])

              [])))
         vec)))

(defn- java-map-entry-contract-nodes [ctx ^CtType owner]
  (when-let [^CtTypeReference reference
             (some #(when (= "java.util.Map$Entry"
                             (.getQualifiedName ^CtTypeReference %))
                      %)
                   (.getSuperInterfaces owner))]
    (let [arguments (vec (.getActualTypeArguments reference))
          arguments (if (= 2 (count arguments))
                      (mapv #(type-node ctx %) arguments)
                      [(raw "object") (raw "object")])
          get-key (first (.getMethodsByName owner "getKey"))
          get-value (first (.getMethodsByName owner "getValue"))]
      (when-not (and get-key get-value)
        (unsupported!
         "Java Map.Entry implementation is missing getKey() or getValue()"
         owner))
      [(sequence-node
        [(raw "public override ")
         (first arguments)
         (raw (str " Key => this."
                   (method-name ctx owner get-key)
                   "();"))])
       (sequence-node
        [(raw "public override ")
         (second arguments)
         (raw (str " Value => this."
                   (method-name ctx owner get-value)
                   "();"))])])))

(defn- constructor-node [ctx ^CtType owner ^CtConstructor constructor]
  (let [local-types (mapv validate-local-type!
                          (executable-local-types constructor))
        anonymous-types (mapv #(emit-anonymous-type ctx owner %)
                              (executable-anonymous-calls constructor))
        name (pascal (.getSimpleName owner))
        rule :java-library.declaration/constructor
        emitted-visibility
        (if (.isImplicit constructor)
          (implicit-constructor-visibility owner constructor)
          (member-visibility owner constructor "internal"))
        id (register-member! ctx owner constructor name rule nil emitted-visibility)
        body (.getBody constructor)
        first-statement (some-> body .getStatements first)
        constructor-invocation
        (when (and (instance? CtInvocation first-statement)
                   (not (.isImplicit ^CtInvocation first-statement))
                   (str/includes?
                    (:key (invocation-occurrence ctx first-statement))
                    "#<init>("))
          first-statement)
        constructor-occurrence
        (when constructor-invocation
          (invocation-occurrence ctx constructor-invocation))
        constructor-parameter-types
        (when constructor-invocation
          (executable-parameter-types
           (:declaration constructor-occurrence)
           (.getExecutable ^CtInvocation constructor-invocation)))
        constructor-arguments
        (when constructor-invocation
          (mapv
           (fn [index ^CtExpression argument]
             (argument-value-node
              ctx argument
              (when (seq constructor-parameter-types)
                (nth constructor-parameter-types
                     (min index (dec (count constructor-parameter-types)))))
              (translated-node ctx argument)
              false))
           (range)
           (.getArguments ^CtInvocation constructor-invocation)))
        initializer-kind
        (when constructor-invocation
          (if (and (= :project (:origin constructor-occurrence))
                   (instance? CtConstructor (:declaration constructor-occurrence))
                   (identical? owner
                               (.getDeclaringType
                                ^CtConstructor (:declaration constructor-occurrence))))
            "this"
            "base"))
        inner? (non-static-member-class? owner)
        outer-field-name (:outer-field-name ctx)
        outer-parameter-node
        (when inner?
          (sequence-node
           [(owner-type-node ctx (.getDeclaringType owner))
            (raw (str " " outer-field-name))]))
        deferred-fields (:deferred-field-initializers ctx)
        body-node
        (if (or inner? constructor-invocation (seq deferred-fields))
          (let [initializers
                (if (= "this" initializer-kind)
                  []
                  (into
                   (if inner?
                     [(raw (str "this." outer-field-name " = "
                                outer-field-name ";"))]
                     [])
                   (map
                    (fn [^CtField field]
                      (sequence-node
                       [(raw (str "this." (destination-field-name ctx field) " = "))
                        (translated-node ctx (.getDefaultExpression field))
                        (raw ";")]))
                    deferred-fields)))
                statements
                (remove
                 #(or (identical? constructor-invocation %)
                      (and (instance? CtInvocation %)
                           (.isImplicit ^CtInvocation %)
                           (str/includes?
                            (:key (invocation-occurrence ctx %))
                            "#<init>(")))
                 (.getStatements body))
                body-statements
                (vec
                 (concat
                  initializers
                  (when (and (seq initializers) (seq statements)) [(raw "")])
                  (map #(translated-node ctx %) statements)))]
            (csharp/block
             (if (seq body-statements) body-statements [(raw "")])))
          (translated-node ctx body))]
    (when-not body
      (unsupported! "Java library constructor has no body" constructor))
    (let [constructor-declaration
          (csharp/with-source
            (csharp/declaration
             (sequence-node
              [(raw (str emitted-visibility " " name "("))
               (sequence-node
                (cond-> (mapv #(parameter-node ctx %)
                              (.getParameters constructor))
                  outer-parameter-node (conj outer-parameter-node))
                ", ")
               (raw ")")
               (when constructor-invocation
                 (sequence-node
                  [(raw (str " : " initializer-kind "("))
                   (sequence-node
                    (cond->
                     constructor-arguments
                      (and inner? (= "this" initializer-kind))
                      (conj (raw outer-field-name)))
                    ", ")
                   (raw ")")]))])
             body-node
             {:declaration-kind :constructor
              :name name
              :source-qualified-name (.getQualifiedName owner)})
            (source-ref constructor rule
                        {:declaration-id id :declaration-kind :constructor}))]
      (sequence-node
       (into [constructor-declaration]
             (concat (map #(emit-root ctx %) local-types)
                     anonymous-types))
       "\n\n"))))

(defn- static-initializer-node
  [ctx ^CtType owner ^CtAnonymousExecutable initializer]
  (when-not (.hasModifier initializer ModifierKind/STATIC)
    (unsupported! "Java library instance initializer lowering is not implemented"
                  initializer))
  (let [initializers
        (->> (explicit-members owner)
             (filter #(instance? CtAnonymousExecutable %))
             vec)
        first-initializer (first initializers)
        name (pascal (.getSimpleName owner))
        rule :java-library.declaration/static-initializer
        id (register-member! ctx owner initializer ".cctor" rule)]
    (if-not (identical? initializer first-initializer)
      (csharp/with-source
        (raw "/* merged static initializer */")
        (source-ref initializer rule
                    {:declaration-id id :declaration-kind :initializer}))
      (csharp/with-source
        (csharp/declaration
         (raw (str "static " name "()"))
         (csharp/block
          (mapv
           (fn [^CtAnonymousExecutable current]
             (csharp/with-source
               (translated-node ctx (.getBody current))
               (source-ref current rule nil)))
           initializers))
         {:declaration-kind :static-constructor
          :name name
          :source-qualified-name (.getQualifiedName owner)})
        (source-ref initializer rule
                    {:declaration-id id :declaration-kind :initializer})))))

(defn- member-node [ctx ^CtType owner member]
  (cond
    (instance? CtEnumValue member) (field-node ctx owner member)
    (instance? CtField member) (field-node ctx owner member)
    (instance? CtMethod member) (method-node ctx owner member)
    (instance? CtConstructor member) (constructor-node ctx owner member)
    (instance? CtType member) (emit-root ctx member)
    (instance? CtAnonymousExecutable member)
    (static-initializer-node ctx owner member)
    :else (unsupported! "Java library member shape is not implemented" member)))

(defn- derived-body-context [ctx additions]
  (let [ctx-holder (atom nil)
        derived (merge ctx additions)
        derived (assoc derived :body-context
                       (create-body-context (:resolved-model ctx) ctx-holder))]
    (reset! ctx-holder derived)
    derived))

(defn- owner-type-node [ctx ^CtType owner]
  (let [parameters (vec (.getFormalCtTypeParameters owner))]
    (if (seq parameters)
      (csharp/generic-name
       (raw (project-type-base ctx owner))
       (mapv #(raw (identifier (.getSimpleName ^CtElement %))) parameters))
      (raw (project-type-base ctx owner)))))

(defn- abstract-interface-methods [^CtInterface interface]
  (->> (.getAllMethods interface)
       (filter #(and (instance? CtMethod %)
                     (not (.hasModifier ^CtMethod % ModifierKind/STATIC))
                     (not (.hasModifier ^CtMethod % ModifierKind/PRIVATE))
                     (nil? (.getBody ^CtMethod %))))
       (reduce (fn [methods ^CtMethod method]
                 (assoc methods (.getSignature method) method))
               (sorted-map))
       vals
       vec))

(defn- functional-interface-method [^CtType type]
  (when (interface-type? type)
    (let [methods (abstract-interface-methods type)]
      (when (= 1 (count methods))
        (first methods)))))

(defn- functional-adapter-name [^CtType interface]
  (str "__" (pascal (.getSimpleName interface)) "FunctionalAdapter"))

(defn- functional-reference-namespace [ctx ^CtTypeReference reference]
  (let [package (some-> reference .getPackage .getQualifiedName)]
    (or (mapped-namespace ctx package :namespaces :namespace-prefixes)
        (unsupported!
         (str "Java functional reference has no destination namespace mapping for "
              (.getQualifiedName reference) " in package " (pr-str package))
         reference))))

(defn- functional-adapter-base
  [ctx ^CtTypeReference reference ^CtType interface]
  (if-let [owner (.getDeclaringType interface)]
    (sequence-node
     [(if-let [owner-reference (.getDeclaringType reference)]
        (type-node ctx owner-reference)
        (owner-type-node ctx owner))
      (raw (str "." (functional-adapter-name interface)))])
    (raw
     (str "global::" (functional-reference-namespace ctx reference) "."
          (functional-adapter-name interface)))))

(defn- functional-expression-node [ctx ^CtExpression expression expression-node]
  (let [reference (.getType expression)
        occurrence (when reference (occurrence! ctx reference :type))
        external-target
        (when reference (translated-external-type-base ctx reference))
        declaration
        (when (or (= :project (:origin occurrence)) external-target)
          (let [resolved-declaration (:declaration occurrence)]
            (if (instance? CtType resolved-declaration)
              resolved-declaration
              (.getTypeDeclaration ^CtTypeReference reference))))
        qualified-name (some-> reference .getQualifiedName)
        arguments (vec (.getActualTypeArguments ^CtTypeReference reference))
        functional-method (functional-interface-method declaration)
        destination-delegate?
        (or
         (when-let [delegate-reference?
                    (get-in ctx [:services :functional-reference-delegate?])]
           (delegate-reference? reference))
         (and
          functional-method
          (when-let [delegate-method?
                     (get-in ctx [:services :functional-interface-method?])]
            (delegate-method? functional-method))))]
    (cond
      destination-delegate?
      expression-node

      (and (= "java.util.Comparator" qualified-name)
           (= 1 (count arguments)))
      (sequence-node
       [(raw "global::System.Collections.Generic.Comparer<")
        (type-node ctx (first arguments))
        (raw ">.Create(") expression-node (raw ")")])

      (and (= "java.lang.Iterable" qualified-name)
           (= 1 (count arguments)))
      (sequence-node
       [(raw "new global::DripSharp.Runtime.JavaIterableAdapter<")
        (type-node ctx (first arguments))
        (raw ">(") expression-node (raw ")")])

      (= "java.awt.Stroke" qualified-name)
      (sequence-node
       [(raw "new global::DripSharp.Runtime.JavaStrokeAdapter(")
        expression-node (raw ")")])

      functional-method
      (let [arguments (vec (.getActualTypeArguments ^CtTypeReference reference))]
        (sequence-node
         [(raw "new ") (functional-adapter-base ctx reference declaration)
          (when (seq arguments)
            (sequence-node
             [(raw "<")
              (sequence-node (mapv #(type-node ctx %) arguments) ", ")
              (raw ">")]))
          (raw "(") expression-node (raw ")")]))
      :else expression-node)))

(defn- functional-delegate-type-node [ctx ^CtMethod method]
  (let [parameters (vec (.getParameters method))
        return-type (.getType method)
        void? (= "void" (.getQualifiedName return-type))
        arguments (cond-> (mapv #(declaration-type-node ctx % (.getType ^CtParameter %))
                                parameters)
                    (not void?) (conj (declaration-type-node ctx method return-type)))]
    (cond
      (and void? (empty? arguments)) (raw "global::System.Action")
      void? (csharp/generic-name (raw "global::System.Action") arguments)
      :else (csharp/generic-name (raw "global::System.Func") arguments))))

(defn- project-functional-adapter-node
  [ctx ^CtInterface interface ^CtMethod method]
  (let [adapter-name (functional-adapter-name interface)
        visibility (emitted-type-visibility interface)
        delegate-type (functional-delegate-type-node ctx method)
        parameters (vec (.getParameters method))
        parameter-nodes (mapv #(parameter-node ctx %) parameters)
        arguments (mapv #(raw (identifier (.getSimpleName ^CtParameter %))) parameters)
        void? (= "void" (.getQualifiedName (.getType method)))]
    (sequence-node
     [(raw (str visibility " sealed class " adapter-name))
      (type-formals-node interface)
      (raw " : ") (owner-type-node ctx interface) (raw " {\nprivate readonly ")
      delegate-type (raw (str " implementation;\n\n" visibility " "))
      (raw adapter-name)
      (raw "(") delegate-type (raw " implementation) {\nthis.implementation = implementation;\n}\n\npublic ")
      (declaration-type-node ctx method (.getType method))
      (raw (str " " (method-name ctx interface method) "("))
      (sequence-node parameter-nodes ", ")
      (raw ") {\n")
      (when-not void? (raw "return "))
      (raw "this.implementation(") (sequence-node arguments ", ") (raw ");\n}\n}")])))

(defn- generated-outer-field-name [^CtType type]
  (let [reserved (->> (concat (.getFields type) (.getMethods type))
                      (map #(identifier (.getSimpleName ^CtElement %)))
                      set)]
    (loop [suffix nil]
      (let [candidate (str "__outer" suffix)]
        (if (contains? reserved candidate)
          (recur (if suffix (inc suffix) 2))
          candidate)))))

(defn- implicit-member-constructor-node
  [ctx ^CtType type ^CtConstructor constructor outer-field-name]
  (let [name (pascal (.getSimpleName type))
        outer (.getDeclaringType type)
        deferred-fields (:deferred-field-initializers ctx)
        rule :java-library.declaration/implicit-member-constructor
        emitted-visibility (implicit-constructor-visibility type constructor)
        id (register-member! ctx type constructor name rule 1 emitted-visibility)]
    (csharp/with-source
      (sequence-node
       [(raw (str emitted-visibility " " name "("))
        (owner-type-node ctx outer) (raw (str " " outer-field-name ") {\nthis."))
        (raw outer-field-name) (raw (str " = " outer-field-name ";\n"))
        (sequence-node
         (mapv
          (fn [^CtField field]
            (sequence-node
             [(raw (str "this." (destination-field-name ctx field) " = "))
              (translated-node ctx (.getDefaultExpression field))
              (raw ";")]))
          deferred-fields)
         "\n")
        (when (seq deferred-fields) (raw "\n"))
        (raw "}")])
      (source-ref constructor rule
                  {:declaration-id id :declaration-kind :constructor}))))

(defn- functional-adapter-node [ctx ^CtType type ^CtTypeReference interface]
  (let [method-name (case (.getQualifiedName interface)
                      "java.util.concurrent.Callable"
                      (if (csharp-public-names? ctx) "Call" "call")
                      "java.util.function.Supplier"
                      (if (csharp-public-names? ctx) "Get" "get"))]
    (sequence-node
     [(raw "public static implicit operator ")
      (type-node ctx interface)
      (raw "(") (owner-type-node ctx type)
      (raw (str " value) => value." method-name ";"))])))

(defn- emit-anonymous-type [ctx ^CtType owner ^CtConstructorCall call]
  (let [^CtClass anonymous-class (anonymous-class-for-call call)]
    (when-not (or (anonymous-iterator? call)
                  (anonymous-x509-trust-manager? call)
                  (anonymous-filter-output-stream? call)
                  (anonymous-linked-hash-map? call)
                  (anonymous-project-type? call))
      (unsupported! (str "Anonymous class base type "
                         (pr-str (some-> call .getType .getQualifiedName))
                         " at " (pr-str (spoon/source-location anonymous-class))
                         " requires exact Iterator, X509TrustManager, FilterOutputStream, LinkedHashMap, or project-class semantics")
                    anonymous-class))
    (when (and (or (anonymous-iterator? call)
                   (anonymous-x509-trust-manager? call))
               (seq (.getArguments call)))
      (unsupported! "Anonymous java.util.Iterator construction cannot have base arguments"
                    call))
    (let [name (anonymous-class-name call)
          base-arguments (vec (.getArguments call))
          captures (anonymous-captures anonymous-class)
          outer? (anonymous-uses-outer? anonymous-class owner)
          capture-names (IdentityHashMap.)
          _ (doseq [[index declaration] (map-indexed vector captures)]
              (.put capture-names declaration (str "__capture_" index)))
          capture-bindings
          (mapv (fn [index declaration]
                  {:declaration declaration :name (str "__capture_" index)})
                (range) captures)
          members (explicit-members anonymous-class)
          deferred-anonymous-fields
          (->> members
               (filter #(and (instance? CtField %)
                             (not (.hasModifier ^CtField % ModifierKind/STATIC))
                             (some? (.getDefaultExpression ^CtField %))))
               vec)
          overrides (or (:destination-owner-overrides ctx) (IdentityHashMap.))
          _ (.put ^IdentityHashMap overrides anonymous-class
                  (str (destination-owner-name ctx owner) "." name))
          derived (derived-body-context
                   ctx {:capture-names capture-names
                        :capture-bindings capture-bindings
                        :outer-type (when outer? owner)
                        :defer-field-initializers?
                        (boolean (seq deferred-anonymous-fields))
                        :deferred-field-initializers deferred-anonymous-fields
                        :destination-owner-overrides overrides})
          rule :java-library.declaration/anonymous-iterator
          id (register-type! derived anonymous-class name rule)
          unsupported-member (some #(when-not (or (instance? CtField %)
                                                  (instance? CtMethod %))
                                      %)
                                   members)]
      (when unsupported-member
        (unsupported! "Anonymous java.util.Iterator member shape is not implemented"
                      unsupported-member))
      (let [capture-fields
            (vec
             (concat
              (when outer?
                [(sequence-node [(raw "private readonly ")
                                 (owner-type-node ctx owner)
                                 (raw " __outer;")])])
              (map (fn [^CtElement declaration]
                     (sequence-node
                      [(raw "private readonly ")
                       (type-node ctx (.getType ^spoon.reflect.declaration.CtTypedElement
                                       declaration))
                       (raw (str " " (.get capture-names declaration) ";"))]))
                   captures)))
            constructor-parameters
            (vec
             (concat
              (map-indexed
               (fn [index ^CtExpression argument]
                 (sequence-node
                  [(type-node ctx (.getType argument))
                   (raw (str " baseArgument" index))]))
               base-arguments)
              (when outer?
                [(sequence-node [(owner-type-node ctx owner) (raw " __outer")])])
              (map (fn [^CtElement declaration]
                     (sequence-node
                      [(type-node ctx (.getType ^spoon.reflect.declaration.CtTypedElement
                                       declaration))
                       (raw (str " " (.get capture-names declaration)))]))
                   captures)))
            constructor-assignments
            (vec
             (concat
              (when outer? [(raw "this.__outer = __outer;")])
              (map (fn [^CtElement declaration]
                     (let [capture (.get capture-names declaration)]
                       (raw (str "this." capture " = " capture ";"))))
                   captures)
              (map (fn [^CtField field]
                     (sequence-node
                      [(raw (str "this."
                                 (identifier (.getSimpleName field))
                                 " = "))
                       (translated-node derived (.getDefaultExpression field))
                       (raw ";")]))
                   deferred-anonymous-fields)))
            constructor
            (sequence-node
             [(raw (str "public " name "("))
              (sequence-node constructor-parameters ", ")
              (raw ")")
              (when (seq base-arguments)
                (sequence-node
                 [(raw " : base(")
                  (sequence-node
                   (mapv #(raw (str "baseArgument" %))
                         (range (count base-arguments)))
                   ", ")
                  (raw ")")]))
              (raw " {")
              (when (seq constructor-assignments) (raw "\n"))
              (sequence-node constructor-assignments "\n")
              (when (seq constructor-assignments) (raw "\n"))
              (raw "}")])
            member-nodes (mapv #(member-node derived anonymous-class %) members)
            declaration
            (csharp/with-source
              (sequence-node
               [(raw (str "private sealed class " name " : "))
                (type-node ctx (.getType call))
                (raw " {\n")
                (sequence-node
                 (vec (concat capture-fields [constructor] member-nodes))
                 "\n\n")
                (raw "\n}")])
              (source-ref anonymous-class rule
                          {:declaration-id id :declaration-kind :type}))]
        declaration))))

(defn- annotation-decisions [ctx]
  (->> (:occurrences (:resolved-model ctx))
       (filter #(= :annotation (:kind %)))
       (map :reference)
       (sort-by (fn [^CtAnnotation annotation]
                  (let [{:keys [file line column]}
                        (spoon/source-location annotation)]
                    [file line column
                     (.getQualifiedName (.getAnnotationType annotation))])))
       (mapv
        (fn [^CtAnnotation annotation]
          (let [occurrence (occurrence! ctx annotation :annotation)
                key (:key occurrence)
                project-annotation?
                (and (= :project (:origin occurrence))
                     (instance? CtAnnotationType (:declaration occurrence)))
                strategy
                (if project-annotation?
                  :csharp-runtime-attribute
                  (case key
                    "annotation:javax.annotation.Nullable"
                    :csharp-nullable-metadata
                    "annotation:javax.annotation.Nonnull"
                    :csharp-nonnullable-metadata
                    "annotation:java.lang.Override"
                    :csharp-language-semantics
                    "annotation:java.lang.FunctionalInterface"
                    :csharp-functional-contract
                    "annotation:java.lang.Deprecated"
                    :source-deprecation-metadata
                    "annotation:java.lang.SuppressWarnings"
                    :source-analysis-only
                    "annotation:java.lang.annotation.Retention"
                    :csharp-attribute-retention
                    "annotation:java.lang.annotation.Target"
                    :csharp-attribute-target
                    (unsupported! "Java library annotation has no neutral mapping"
                                  annotation)))]
            {:source (source-ref annotation :java-library.annotation/resolved nil)
             :resolved-key key
             :origin (:origin occurrence)
             :strategy strategy
             :emitted-runtime-attribute project-annotation?})))))

(defn- annotation-by-name
  [^CtAnnotationType annotation-type qualified-name]
  (some
   (fn [^CtAnnotation annotation]
     (when (= qualified-name
              (.getQualifiedName (.getAnnotationType annotation)))
       annotation))
   (.getAnnotations annotation-type)))

(defn- annotation-enum-value-names
  [^CtAnnotation annotation value-name]
  (when-let [value (.getValue annotation value-name)]
    (->> (concat
          (when (instance? CtVariableAccess value) [value])
          (.getElements value (TypeFilter. CtVariableAccess)))
         (map #(.getSimpleName
                (.getVariable ^CtVariableAccess %)))
         distinct
         vec)))

(def ^:private annotation-targets
  {"ANNOTATION_TYPE" ["Interface"]
   "CONSTRUCTOR" ["Constructor"]
   "FIELD" ["Field"]
   "METHOD" ["Method"]
   "PARAMETER" ["Parameter"]
   "TYPE" ["Class" "Interface" "Struct" "Enum" "Delegate"]})

(defn- annotation-targets-node [^CtAnnotationType annotation-type]
  (let [target-annotation
        (annotation-by-name annotation-type
                            "java.lang.annotation.Target")
        java-targets
        (when target-annotation
          (annotation-enum-value-names target-annotation "value"))
        unsupported-targets
        (remove #(contains? annotation-targets %) java-targets)]
    (when (seq unsupported-targets)
      (unsupported!
       (str "Java runtime annotation targets have no CLR metadata target: "
            (str/join ", " unsupported-targets))
       target-annotation))
    (let [targets (distinct (mapcat annotation-targets java-targets))]
      (raw
       (if (seq targets)
         (str/join
          " | "
          (map #(str "global::System.AttributeTargets." %) targets))
         "global::System.AttributeTargets.All")))))

(defn- annotation-meta-present?
  [^CtAnnotationType annotation-type qualified-name]
  (boolean (annotation-by-name annotation-type qualified-name)))

(defn- annotation-storage-type-node
  [ctx ^CtAnnotationMethod method]
  (if (enum-reference? (.getType method))
    (raw "string")
    (declaration-type-node ctx method (.getType method))))

(defn- annotation-storage-name [^CtAnnotationMethod method]
  (identifier (str "__" (.getSimpleName method))))

(defn- annotation-companion-method-node
  [ctx ^CtAnnotationType annotation-type ^CtAnnotationMethod method]
  (let [storage-name (annotation-storage-name method)
        enum? (enum-reference? (.getType method))]
    (sequence-node
     [(raw "public ")
      (declaration-type-node ctx method (.getType method))
      (raw (str " " (method-name ctx annotation-type method) "() {\nreturn "))
      (when enum?
        (sequence-node
         [(declaration-type-node ctx method (.getType method))
          (raw ".valueOf(")]))
      (raw (str "this." storage-name))
      (when enum? (raw ")"))
      (raw ";\n}")])))

(defn- annotation-companion-node
  [ctx ^CtAnnotationType annotation-type]
  (let [name (pascal (.getSimpleName annotation-type))
        methods (annotation-methods annotation-type)
        parameters
        (mapv
         (fn [^CtAnnotationMethod method]
           (sequence-node
            [(annotation-storage-type-node ctx method)
             (raw (str " " (identifier (.getSimpleName method))))]))
         methods)
        fields
        (mapv
         (fn [^CtAnnotationMethod method]
           (sequence-node
            [(raw "private readonly ")
             (annotation-storage-type-node ctx method)
             (raw (str " " (annotation-storage-name method) ";"))]))
         methods)
        assignments
        (mapv
         (fn [^CtAnnotationMethod method]
           (raw
            (str "this." (annotation-storage-name method) " = "
                 (identifier (.getSimpleName method)) ";")))
         methods)
        inherited?
        (annotation-meta-present? annotation-type
                                  "java.lang.annotation.Inherited")
        repeatable?
        (annotation-meta-present? annotation-type
                                  "java.lang.annotation.Repeatable")]
    (sequence-node
     [(raw "[global::System.AttributeUsage(")
      (annotation-targets-node annotation-type)
      (raw (str ", AllowMultiple = " (if repeatable? "true" "false")
                ", Inherited = " (if inherited? "true" "false") ")]\n"
                "internal sealed class " name
                "Attribute : global::System.Attribute, " name " {\n"))
      (sequence-node fields "\n")
      (when (seq fields) (raw "\n\n"))
      (raw (str "public " name "Attribute("))
      (sequence-node parameters ", ")
      (raw ") {\n")
      (sequence-node assignments "\n")
      (raw "\n}")
      (when (seq methods) (raw "\n\n"))
      (sequence-node
       (mapv #(annotation-companion-method-node ctx annotation-type %) methods)
       "\n\n")
      (raw "\n}")])))

(defn- emit-root [ctx ^CtType type]
  (when-not (or (instance? CtClass type) (interface-type? type))
    (unsupported! "Java library declaration shape is not implemented" type))
  (let [name (pascal (.getSimpleName type))
        rule (if (interface-type? type)
               :java-library.declaration/interface
               :java-library.declaration/class)
        id (register-type! ctx type name rule)
        bases (base-type-references type)
        base-nodes (vec (keep #(base-type-node ctx type %) bases))
        functional-bases
        (when (instance? CtClass type)
          (filter #(contains? functional-interface-types (.getQualifiedName ^CtTypeReference %))
                  bases))
        members (explicit-members type)
        inner? (non-static-member-class? type)
        outer-field-name (when inner? (generated-outer-field-name type))
        nested-instance-class?
        (some #(and (instance? CtType %)
                    (non-static-member-class? %))
              members)
        deferred-fields
        (->> members
             (filter #(and (instance? CtField %)
                           (not (.hasModifier ^CtField % ModifierKind/STATIC))
                           (some? (.getDefaultExpression ^CtField %))
                           (or nested-instance-class?
                               (initializer-uses-this? %)
                               (seq (field-anonymous-calls %)))))
             vec)
        explicit-constructors (filter #(instance? CtConstructor %) members)
        implicit-constructor
        (some #(when (and (instance? CtConstructor %)
                          (.isImplicit ^CtConstructor %))
                 %)
              (.getTypeMembers type))
        emit-implicit-constructor?
        (and (not inner?)
             implicit-constructor
             (empty? explicit-constructors)
             (or (seq deferred-fields)
                 (and (instance? CtClass type)
                      (.hasModifier ^CtModifiable type
                                    ModifierKind/ABSTRACT))
                 (.hasModifier ^CtModifiable type ModifierKind/PROTECTED)
                 (public-derived-type? type)))
        _ (when (and (seq deferred-fields)
                     (empty? explicit-constructors)
                     (nil? implicit-constructor))
            (unsupported!
             "Java instance-context field initialization requires an explicit constructor"
             type))
        member-ctx (derived-body-context
                    ctx
                    {:outer-type (when inner? (.getDeclaringType type))
                     :outer-field-name outer-field-name
                     :defer-field-initializers? (boolean (seq deferred-fields))
                     :deferred-field-initializers deferred-fields})
        member-nodes (if-let [emit-members (:emit-members ctx)]
                       (emit-members member-ctx type members)
                       (mapv #(member-node member-ctx type %) members))
        interface-contracts (interface-contract-nodes member-ctx type)
        runtime-interface-contracts
        (runtime-interface-contract-nodes type)
        java-map-entry-contracts
        (java-map-entry-contract-nodes member-ctx type)
        field-anonymous-types
        (mapv #(emit-anonymous-type member-ctx type %)
              (mapcat #(if (instance? CtField %)
                         (field-anonymous-calls %)
                         [])
                      members))
        member-nodes
        (cond-> (into (vec member-nodes)
                      (concat interface-contracts
                              runtime-interface-contracts
                              java-map-entry-contracts))
          inner?
          (conj
           (sequence-node
            [(raw "private readonly ")
             (owner-type-node member-ctx (.getDeclaringType type))
             (raw (str " " outer-field-name ";"))]))

          (and inner? implicit-constructor)
          (conj (implicit-member-constructor-node member-ctx type implicit-constructor
                                                  outer-field-name))
          emit-implicit-constructor?
          (conj (constructor-node member-ctx type implicit-constructor))
          (seq functional-bases)
          (into (mapv #(functional-adapter-node member-ctx type %) functional-bases)))
        closeable? (some #(= "java.io.Closeable" (.getQualifiedName ^CtTypeReference %))
                         (.getSuperInterfaces type))
        declares-close? (some #(and (instance? CtMethod %)
                                    (= "close" (.getSimpleName ^CtMethod %))
                                    (empty? (.getParameters ^CtMethod %)))
                              members)
        member-nodes
        (cond-> member-nodes
          (and closeable?
               (not declares-close?)
               (not (superclass-implements-closeable? type))
               (instance? CtClass type))
          (conj (raw "public abstract void Dispose();")))
        explicit-enum-to-string?
        (and (instance? CtEnum type)
             (some (fn [member]
                     (and (instance? CtMethod member)
                          (= "toString" (.getSimpleName ^CtMethod member))
                          (empty? (.getParameters ^CtMethod member))))
                   members))
        enum-members
        (when (instance? CtEnum type)
          [(when (and (empty? explicit-constructors)
                      (not emit-implicit-constructor?))
             (raw (str "private " name "() {}\n")))
           (raw (str "public static " name "[] values() => "
                     "global::DripSharp.Runtime.JavaCompat.EnumValues<" name ">();\n"
                     "public static " name " valueOf(string name) => "
                     "global::DripSharp.Runtime.JavaCompat.EnumValueOf<" name ">(name);"))
           (when-not explicit-enum-to-string?
             (raw (str "public override string ToString() => "
                       "global::DripSharp.Runtime.JavaCompat.EnumName(this);")))])
        member-nodes (into member-nodes
                           (concat field-anonymous-types
                                   (remove nil? enum-members)))
        functional-method (functional-interface-method type)
        type-constraints
        (constraints-node ctx (vec (.getFormalCtTypeParameters type)))
        source (source-ref type rule
                           {:declaration-id id :declaration-kind :type})]
    (sequence-node
     [(csharp/with-source
        (csharp/declaration
         (sequence-node
          [(project-annotation-attributes-node ctx type)
           (raw (str (str/join " " (type-words type)) " " name))
           (type-formals-node type)
           (when (seq base-nodes)
             (sequence-node [(raw " : ")
                             (sequence-node base-nodes ", ")]))
           type-constraints])
         (csharp/block
          (csharp/statement-list member-nodes "\n\n"))
         {:declaration-kind :type
          :name name
          :source-qualified-name (.getQualifiedName type)
          :has-base-types? (boolean (seq base-nodes))
          :has-constraints? (boolean type-constraints)})
        source)
      (when functional-method
        (project-functional-adapter-node ctx type functional-method))
      (when (instance? CtAnnotationType type)
        (annotation-companion-node ctx type))]
     "\n\n")))

(def ^:private bridge-capabilities
  {:java-bidi
   (mapv
    (fn [[source destination strategy]]
      {:source source
       :destination destination
       :strategy strategy
       :missing-kind :missing-java-compatibility-source
       :missing-message "Java bidirectional compatibility source is missing"})
    [["runtime/DripSharp.JavaBidi.cs"
      "DripSharp/Runtime/JavaBidi.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.Algorithm.cs"
      "DripSharp/Runtime/JavaBidi/Algorithm.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.ArrayBuilder.cs"
      "DripSharp/Runtime/JavaBidi/ArrayBuilder.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.ArraySlice.cs"
      "DripSharp/Runtime/JavaBidi/ArraySlice.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.Class.cs"
      "DripSharp/Runtime/JavaBidi/BidiClass.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.Data.cs"
      "DripSharp/Runtime/JavaBidi/BidiData.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.Dictionary.cs"
      "DripSharp/Runtime/JavaBidi/BidiDictionary.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.ImmutableEnumerator.cs"
      "DripSharp/Runtime/JavaBidi/ImmutableEnumerator.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.MappedArraySlice.cs"
      "DripSharp/Runtime/JavaBidi/MappedArraySlice.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.MirroringData.cs"
      "DripSharp/Runtime/JavaBidi/MirroringData.cs"
      :generated-java-compatibility-data]
     ["runtime/DripSharp.JavaBidi.PairedBracketType.cs"
      "DripSharp/Runtime/JavaBidi/BidiPairedBracketType.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.TrieData.cs"
      "DripSharp/Runtime/JavaBidi/BidiTrieData.cs"
      :generated-java-compatibility-data]
     ["runtime/DripSharp.JavaBidi.UnicodeData.cs"
      "DripSharp/Runtime/JavaBidi/UnicodeData.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.UnicodeTrie.cs"
      "DripSharp/Runtime/JavaBidi/UnicodeTrie.cs"
      :reviewable-java-compatibility-source]
     ["runtime/DripSharp.JavaBidi.UnicodeTrieConstants.cs"
      "DripSharp/Runtime/JavaBidi/UnicodeTrieConstants.cs"
      :reviewable-java-compatibility-source]])
   :java-compat
   {:source "runtime/DripSharp.JavaCompat.cs"
    :destination "DripSharp/Runtime/JavaCompat.cs"
    :strategy :reviewable-java-compatibility-source
    :missing-kind :missing-java-compatibility-source
    :missing-message "Java compatibility source is missing"}
   :java-regex-unicode
   {:source "runtime/DripSharp.JavaRegexUnicodeData.cs"
    :destination "DripSharp/Runtime/JavaRegexUnicodeData.cs"
    :strategy :generated-java-compatibility-data
    :missing-kind :missing-java-compatibility-source
    :missing-message "Java compatibility source is missing"}})

(defn- bridge-assets [{:keys [configuration]}]
  (let [destination-capabilities
        (set (:destination-capabilities configuration))
        embedded-capabilities
        (set (or (:bridge-capabilities configuration)
                 destination-capabilities))
        inherited-capabilities
        (set/difference embedded-capabilities destination-capabilities)]
    (when (seq inherited-capabilities)
      (fail! "Java library bridge capabilities must be enabled destination capabilities"
             {:kind :invalid-java-library-bridge-capabilities
              :destination-capabilities (sort destination-capabilities)
              :bridge-capabilities (sort embedded-capabilities)
              :invalid-capabilities (sort inherited-capabilities)}))
    (vec
     (mapcat
      (fn [capability]
        (let [assets
              (or (get bridge-capabilities capability)
                  (fail! "Java library destination selected an unknown capability"
                         {:kind :unknown-java-library-capability
                          :capability capability}))]
          (if (vector? assets) assets [assets])))
      (sort embedded-capabilities)))))

(defn- dependency-scopes [project-input coordinate]
  (->> (:external-dependencies project-input)
       (filter #(= coordinate (:coordinate %)))
       (map :scope) set))

(defn- validate-project-input! [{:keys [project-input configuration]}]
  (let [expected-projects (set (:project-dependencies configuration))
        actual-projects (set (map :project-id
                                  (:project-dependencies project-input)))]
    (when-not (= expected-projects actual-projects)
      (fail! "Source project dependencies differ from the destination contract"
             {:kind :source-project-dependency-mismatch
              :expected (sort expected-projects) :actual (sort actual-projects)})))
  (let [expected (or (:external-dependencies configuration) {})
        actual-coordinates
        (set (map :coordinate (:external-dependencies project-input)))]
    (when-not (= (set (keys expected)) actual-coordinates)
      (fail! "Source external dependencies differ from the destination contract"
             {:kind :source-external-dependency-mismatch
              :expected (sort (keys expected)) :actual (sort actual-coordinates)}))
    (doseq [[coordinate {:keys [source-scope artifact-sha256]}] expected]
      (let [scopes (dependency-scopes project-input coordinate)
            required (case source-scope
                       :compile-only #{:compile}
                       :compile-runtime #{:compile :runtime})]
        (when-not (= required scopes)
          (fail! "Source dependency scope differs from the destination contract"
                 {:kind :source-external-dependency-scope-mismatch
                  :coordinate coordinate :expected required :actual scopes})))
      (when artifact-sha256
        (let [hashes (->> (:classpath-artifacts project-input)
                          (filter #(= coordinate (:coordinate %)))
                          (map :sha256) set)]
          (when-not (= #{artifact-sha256} hashes)
            (fail! "Source dependency artifact differs from the destination contract"
                   {:kind :source-external-artifact-mismatch
                    :coordinate coordinate :expected artifact-sha256
                    :actual (sort hashes)}))))))
  project-input)

(defn emit-declaration-root-node
  "Dispatches a root declaration through the shared structural contract.
  Product bundles may place an `:emit-root-node` hook under
  `:structural-declaration-policy` in their emission context."
  [ctx ^CtType type]
  (if-let [emit-root-node
           (get-in ctx [:structural-declaration-policy :emit-root-node])]
    (emit-root-node ctx type)
    (emit-root ctx type)))

(defn translate-declaration-member
  "Dispatches a member declaration through the shared structural contract.
  Product bundles may place a `:translate-member` hook under
  `:structural-declaration-policy` in their emission context."
  [ctx ^CtType owner member]
  (if-let [translate-member
           (get-in ctx [:structural-declaration-policy :translate-member])]
    (translate-member ctx owner member)
    (member-node ctx owner member)))

(defn rule-bundle
  "Returns the ordinary Java-library destination bundle."
  []
  {:schema-version 1
   :id :java-library
   :product-family :java-library
   :orchestration {:validate-project-input! validate-project-input!}
   :rules
   {:structural-declarations
    {:create-template (fn [_ _] {})
     :create-context context
     :emit-root-node emit-declaration-root-node
     :translate-member translate-declaration-member
     :merge-context! merge-context!
     :context-results context-results}
    :resolved-mappings
    {:type-node type-node
     :create-body-context create-body-context
     :annotation-decisions annotation-decisions}
    :namespace-policy
    {:destination-namespace destination-namespace
     :destination-file-name destination-file-name}
    :project-policy project-emission/common-project-policy
    :resource-policy project-emission/common-resource-policy
    :destination-bridges {:assets bridge-assets}}})

(def ^:private surface-header
  "DRIPSHARP_JAVA_LIBRARY_PUBLIC_SURFACE_V1")

(def ^:private surface-shape-keys
  [:kind :owner :name :parameter-count :visibility])

(defn- parameter-count [value]
  (let [value (str/replace value #"^parameters=" "")]
    (if (str/blank? value)
      0
      (loop [characters (seq value) depth 0 count 1]
        (if-let [character (first characters)]
          (recur (next characters)
                 (case character \< (inc depth) \> (dec depth) depth)
                 (if (and (= \, character) (zero? depth)) (inc count) count))
          count)))))

(defn- declared-visibility [^CtModifiable declaration]
  (cond
    (.hasModifier declaration ModifierKind/PUBLIC) "public"
    (.hasModifier declaration ModifierKind/PROTECTED) "protected"
    (.hasModifier declaration ModifierKind/PRIVATE) "private"
    :else "package"))

(defn- encoded-visibility [value]
  (or (some #(when (str/includes? (str " " value " ") (str " " % " ")) %)
            ["public" "protected" "private"])
      "package"))

(defn- parse-surface-row [encoded]
  (let [decoded (String. (.decode (Base64/getDecoder) encoded)
                         StandardCharsets/UTF_8)
        fields (str/split decoded #"\|" -1)
        kind (first fields)
        row
        (case kind
          "type" {:kind kind :owner (nth fields 3) :name ""
                  :parameter-count 0
                  :visibility (encoded-visibility (nth fields 2))}
          "field" {:kind kind :owner (nth fields 1) :name (nth fields 4)
                   :parameter-count 0
                   :visibility (encoded-visibility (nth fields 2))}
          "method" {:kind kind :owner (nth fields 1) :name (nth fields 5)
                    :parameter-count (parameter-count (nth fields 6))
                    :visibility (encoded-visibility (nth fields 2))}
          "constructor" {:kind kind :owner (nth fields 1) :name ".ctor"
                         :parameter-count (parameter-count (nth fields 4))
                         :visibility (encoded-visibility (nth fields 2))}
          (fail! "Java library public-surface row has an unknown kind"
                 {:kind :unknown-java-library-surface-kind :row decoded}))]
    (assoc row :identity decoded)))

(defn- read-surface!
  [workspace {:keys [contract-file compiled-contract-file] :as specification}]
  (let [keys (set (keys specification))]
    (when-not (or (empty? keys)
                  (= #{:contract-file :compiled-contract-file} keys))
      (fail! "Invalid Java library public-surface specification"
             {:kind :invalid-java-library-surface-specification
              :specification specification}))
    (if (empty? keys)
      {:derivation :resolved-spoon-model :rows nil :seeds []}
      (let [workspace (paths/absolute workspace)
            file (paths/resolve-path workspace contract-file)
            compiled-file (paths/resolve-path workspace compiled-contract-file)]
        (when-not (paths/regular-file? file)
          (fail! "Java library public-surface contract is missing"
                 {:kind :missing-java-library-surface :file (str file)}))
        (when-not (paths/regular-file? compiled-file)
          (fail! "Java library compiled public-surface contract is missing"
                 {:kind :missing-compiled-java-library-surface-contract
                  :file (str compiled-file)}))
        (let [[header & lines]
              (str/split-lines (Files/readString file StandardCharsets/UTF_8))
              rows
              (mapv
               (fn [line]
                 (let [[record encoded extra] (str/split line #"\t" -1)]
                   (when-not (and (= "surface" record)
                                  (not (str/blank? encoded))
                                  (nil? extra))
                     (fail! "Malformed Java library public-surface row"
                            {:kind :malformed-java-library-surface-row
                             :line line}))
                   (parse-surface-row encoded)))
               (remove str/blank? lines))
              identities (mapv :identity rows)]
          (when-not (= surface-header header)
            (fail! "Unsupported Java library public-surface contract"
                   {:kind :unsupported-java-library-surface :header header}))
          (when-not (and (seq rows)
                         (= (count identities) (count (distinct identities)))
                         (= identities (vec (sort identities))))
            (fail! "Java library public-surface rows are empty, duplicate, or unsorted"
                   {:kind :nondeterministic-java-library-surface
                    :rows (count rows)}))
          {:derivation :retained-contract
           :contract-file file
           :compiled-contract-file compiled-file
           :rows rows
           :seeds []})))))

(defn- canonical-owner [value]
  (str/replace (str value) "$" "."))

(defn- live-shape [^CtElement declaration]
  (cond
    (instance? CtType declaration)
    {:kind "type" :owner (canonical-owner (.getQualifiedName ^CtType declaration))
     :name "" :parameter-count 0}

    (instance? CtConstructor declaration)
    {:kind "constructor"
     :owner (canonical-owner (.getQualifiedName (.getDeclaringType ^CtConstructor declaration)))
     :name ".ctor"
     :parameter-count
     (+ (count (.getParameters ^CtConstructor declaration))
        (if (and (.isImplicit declaration)
                 (some? (.getDeclaringType (.getDeclaringType ^CtConstructor declaration)))
                 (not (.hasModifier ^CtModifiable (.getDeclaringType ^CtConstructor declaration)
                                    ModifierKind/STATIC)))
          1 0))}

    (instance? CtMethod declaration)
    {:kind "method"
     :owner (canonical-owner (.getQualifiedName (.getDeclaringType ^CtMethod declaration)))
     :name (.getSimpleName ^CtMethod declaration)
     :parameter-count (count (.getParameters ^CtMethod declaration))}

    (instance? CtField declaration)
    {:kind "field"
     :owner (canonical-owner (.getQualifiedName (.getDeclaringType ^CtField declaration)))
     :name (.getSimpleName ^CtField declaration) :parameter-count 0}))

(defn- accessible? [^CtElement declaration]
  (and (instance? CtModifiable declaration)
       (or (.hasModifier ^CtModifiable declaration ModifierKind/PUBLIC)
           (.hasModifier ^CtModifiable declaration ModifierKind/PROTECTED))))

(defn- effectively-accessible-type? [^CtType type]
  (loop [current type]
    (or (nil? current)
        (and (or (accessible? current)
                 (public-derived-type? current)
                 (public-signature-type? current))
             (recur (.getDeclaringType ^CtType current))))))

(defn- surface-row
  ([^CtElement declaration shape]
   (surface-row declaration shape nil))
  ([^CtElement declaration shape systematic-adaptation]
   (let [key (spoon/declaration-key declaration)
         adaptation-kind
         (if (map? systematic-adaptation)
           (:kind systematic-adaptation)
           systematic-adaptation)
         adaptation-identity
         (if (map? systematic-adaptation)
           (:identity systematic-adaptation)
           (some-> systematic-adaptation name))
         identity (if adaptation-kind
                    (str key "|systematic-adaptation="
                         adaptation-identity)
                    key)]
     (when-not (and (string? identity) (not (str/blank? identity)))
       (fail! "Accessible Spoon declaration has no stable source identity"
              {:kind :missing-accessible-surface-identity
               :source-element declaration
               :source-location (spoon/source-location declaration)}))
     (assoc shape :identity identity))))

(defn- synthetic-surface-entry
  [^CtType type name parameter-count systematic-adaptation]
  (let [adaptation-kind
        (if (map? systematic-adaptation)
          (:kind systematic-adaptation)
          systematic-adaptation)
        shape
        {:kind "method"
         :owner (canonical-owner (.getQualifiedName type))
         :name name
         :parameter-count parameter-count
         :visibility "public"}]
    {:declaration type
     :synthetic? true
     :systematic-adaptation adaptation-kind
     :shape shape
     :row (surface-row type shape systematic-adaptation)}))

(defn- runtime-interface-surface-entries [^CtClass type]
  (mapcat
   (fn [^CtTypeReference reference]
     (let [interface-name (.getQualifiedName reference)
           methods
           (case interface-name
             "java.awt.Paint"
             [["createContext" "CreateContext" 5]
              ["getTransparency" "GetTransparency" 0]]

             "java.awt.PaintContext"
             [["getRaster" "GetRaster" 4]]

             [])]
       (keep
        (fn [[source-name destination-name parameter-count]]
          (when (empty? (.getMethodsByName type source-name))
            (synthetic-surface-entry
             type destination-name parameter-count
             {:kind :java-runtime-abstract-interface-contract
              :identity
              (str "java-runtime-abstract-interface-contract:"
                   interface-name "#" source-name "/" parameter-count)})))
        methods)))
   (filter
    #(contains? runtime-abstract-interface-types
                (.getQualifiedName ^CtTypeReference %))
    (.getSuperInterfaces type))))

(defn- live-surface [resolved-model]
  (->> (java/project-roots resolved-model)
       (mapcat (fn [^CtType root]
                 (tree-seq (fn [^CtType type] (seq (.getNestedTypes type)))
                           (fn [^CtType type] (.getNestedTypes type))
                           root)))
       distinct
       (mapcat (fn [^CtType type]
                 (when (effectively-accessible-type? type)
                   (let [promoted-base?
                         (and (not (accessible? type))
                              (public-derived-type? type))
                         promoted-signature?
                         (and (not (accessible? type))
                              (not promoted-base?)
                              (public-signature-type? type))
                         type-adaptation
                         (cond
                           promoted-base?
                           {:kind :java-public-base-type-promotion
                            :identity "java-public-base-type-promotion"}

                           promoted-signature?
                           {:kind :java-public-signature-type-promotion
                            :identity "java-public-signature-type-promotion"})
                         type-shape
                         (assoc (live-shape type)
                                :visibility
                                (if (or promoted-base?
                                        promoted-signature?)
                                  "public"
                                  (declared-visibility type)))
                         declarations
                         (keep
                          (fn [^CtModifiable declaration]
                            (when (or (instance? CtConstructor declaration)
                                      (instance? CtMethod declaration)
                                      (instance? CtField declaration))
                              (let [adaptation
                                    (when (and
                                           (instance? CtMethod declaration)
                                           (not (accessible? declaration)))
                                      (cond
                                        (public-override-family?
                                         type declaration)
                                        :package-public-override-family-widening

                                        (protected-override-family?
                                         type declaration)
                                        :package-protected-override-family-widening))]
                                (when (or (accessible? declaration) adaptation)
                                  {:declaration declaration
                                   :systematic-adaptation adaptation}))))
                          (.getTypeMembers type))]
                     (concat
                      [{:declaration type
                        :systematic-adaptation
                        (some-> type-adaptation :kind)
                        :shape type-shape
                        :row (surface-row type type-shape type-adaptation)}]
                      (map
                       (fn [{:keys [^CtModifiable declaration
                                    systematic-adaptation]}]
                         (let [shape
                               (assoc
                                (live-shape declaration)
                                :visibility
                                (if systematic-adaptation
                                  (canonical-visibility
                                   (first
                                    (method-words
                                     type ^CtMethod declaration)))
                                  (declared-visibility declaration)))]
                           {:declaration declaration
                            :systematic-adaptation systematic-adaptation
                            :shape shape
                            :row
                            (surface-row declaration shape
                                         systematic-adaptation)}))
                       declarations)
                      (when (instance? CtEnum type)
                        (concat
                         (map
                          (fn [^CtEnumValue value]
                            (let [shape
                                  (assoc (live-shape value)
                                         :visibility "public")]
                              {:declaration value
                               :shape shape
                               :row (surface-row value shape)}))
                          (.getEnumValues ^CtEnum type))
                         (map (fn [[name parameter-count adaptation]]
                                (synthetic-surface-entry
                                 type name parameter-count adaptation))
                              (cond-> [["values" 0 :java-enum-values] ["valueOf" 1 :java-enum-value-of]] (not (some #(and (instance? CtMethod %) (= "toString" (.getSimpleName ^CtMethod %)) (empty? (.getParameters ^CtMethod %))) (.getTypeMembers type))) (conj ["ToString" 0 :java-enum-name-to-string])))))
                      (when (instance? CtClass type)
                        (concat
                         (map
                          (fn [^CtTypeReference reference]
                            (synthetic-surface-entry
                             type "op_Implicit" 1
                             {:kind :java-functional-implicit-operator
                              :identity
                              (str "java-functional-implicit-operator:"
                                   (.getQualifiedName reference))}))
                          (filter
                           #(contains? functional-interface-types
                                       (.getQualifiedName ^CtTypeReference %))
                           (.getSuperInterfaces type)))
                         (when (.hasModifier ^CtModifiable type
                                             ModifierKind/ABSTRACT)
                           (runtime-interface-surface-entries type))
                         (when (.hasModifier ^CtModifiable type
                                             ModifierKind/ABSTRACT)
                           (keep
                            (fn [[^CtTypeReference interface-reference
                                  ^CtMethod interface-method]]
                              (when (and (nil? (.getBody interface-method))
                                         (not (.hasModifier interface-method
                                                            ModifierKind/STATIC))
                                         (nil? (matching-class-method
                                                type interface-method)))
                                (assoc
                                 (synthetic-surface-entry
                                  type
                                  (.getSimpleName interface-method)
                                  (count (.getParameters interface-method))
                                  {:kind :java-abstract-interface-contract
                                   :identity
                                   (str "java-abstract-interface-contract:"
                                        (.getQualifiedName interface-reference)
                                        "#" (.getSignature interface-method))})
                                 :interface-method interface-method)))
                            (interface-methods type))))))))))
       (remove (comp nil? :shape))
       (sort-by (juxt (comp pr-str :shape)
                      #(spoon/declaration-key (:declaration %))))
       vec))

(defn- validate-selected! [_workspace surface resolved-model]
  (let [live (live-surface resolved-model)
        actual (frequencies (map :shape live))
        derived? (= :resolved-spoon-model (:derivation surface))
        rows (if derived?
               (mapv :row live)
               (:rows surface))
        expected (frequencies (map #(select-keys % surface-shape-keys) rows))
        identities (mapv :identity rows)]
    (when-not (seq rows)
      (fail! "Resolved Java library has no accessible declarations"
             {:kind :empty-java-library-selected-surface
              :derivation (:derivation surface)}))
    (when-not (= (count identities) (count (distinct identities)))
      (fail! "Resolved Java library public surface has duplicate source identities"
             {:kind :duplicate-java-library-surface-identities
              :duplicates
              (->> identities frequencies
                   (keep (fn [[identity count]]
                           (when (< 1 count) identity)))
                   sort vec)}))
    (when-not (= expected actual)
      (fail! (if derived?
               "Resolved Java library public surface derivation is incomplete"
               "Resolved Java library public surface differs from its retained contract")
             {:kind :java-library-selected-surface-mismatch
              :derivation (:derivation surface)
              :missing (vec (take 30 (remove (fn [[shape count]]
                                               (= count (get actual shape))) expected)))
              :unexpected (vec (take 30 (remove (fn [[shape count]]
                                                  (= count (get expected shape))) actual)))}))
    (let [rows-by-shape (group-by #(select-keys % surface-shape-keys) rows)
          live-by-shape (group-by :shape live)
          evidence
          (->> rows-by-shape
               (mapcat
                (fn [[shape rows]]
                  (map (fn [row {:keys [declaration synthetic?
                                        systematic-adaptation interface-method]}]
                         {:row row
                          :declaration-key (spoon/declaration-key declaration)
                          :owner-declaration-key
                          (when-not (instance? CtType declaration)
                            (some-> declaration .getDeclaringType
                                    spoon/declaration-key))
                          :implicit? (.isImplicit ^CtElement declaration)
                          :synthetic? (boolean synthetic?)
                          :interface-method interface-method
                          :systematic-adaptation systematic-adaptation
                          :expansion :body
                          :representation :live-declaration})
                       (sort-by :identity rows)
                       (sort-by #(spoon/declaration-key (:declaration %))
                                (get live-by-shape shape)))))
               (sort-by (juxt (comp :identity :row) :declaration-key))
               vec)]
      (assoc surface :rows (vec (sort-by :identity rows))
             :selection-evidence evidence))))

(def ^:private systematic-adaptations
  {:java-implicit-default-constructor
   "A Java implicit default constructor is represented by the CLR default constructor."
   :java-enum-values
   "Java enum values() is emitted as the reusable CLR enum-values adaptation."
   :java-enum-value-of
   "Java enum valueOf(String) is emitted as the reusable CLR enum-name adaptation."
   :java-enum-name-to-string
   "A Java enum without an override receives the CLR ToString enum-name adaptation."
   :java-functional-implicit-operator
   "A supported Java functional base receives a CLR implicit delegate conversion."
   :java-abstract-interface-contract
   "A Java abstract class receives CLR abstract declarations for unimplemented interface members."
   :java-runtime-abstract-interface-contract
   "A Java abstract class receives CLR abstract declarations for mapped runtime-interface members."
   :java-public-base-type-promotion
   "A package-visible Java base class is public in CLR metadata when required by a public subclass."
   :java-public-signature-type-promotion
   "A package-visible Java type is public in CLR metadata when required by an accessible signature."
   :protected-override-family-widening
   "A protected Java override-family member is widened to public when a public override requires one CLR visibility."
   :package-public-override-family-widening
   "A package-visible Java override-family root is widened to public when a public override requires one CLR visibility."
   :package-protected-override-family-widening
   "A package-visible Java override-family root is widened to protected-internal when a protected override requires one CLR visibility."
   :protected-package-visibility
   "Java protected package access is represented as CLR protected-internal for the reusable linked-map hook."})

(def ^:private blocking-coverage-keys
  [:blocked :unsupported-elements :missing-mappings :missing-occurrences
   :fallback])

(defn- validate-emission-coverage! [emission]
  (let [summary (:summary emission)
        coverage (:executable-coverage summary)
        blockers
        (cond-> {}
          (not (map? summary))
          (assoc :summary :missing)

          (pos? (long (or (:hard-failures summary) 0)))
          (assoc :hard-failures (:hard-failures summary))

          (pos? (long (or (:missing-source-mappings summary) 0)))
          (assoc :missing-source-mappings (:missing-source-mappings summary))

          (seq (:diagnostics emission))
          (assoc :diagnostics (count (:diagnostics emission)))

          (some #(pos? (long (or (get coverage %) 0)))
                blocking-coverage-keys)
          (assoc :executable-coverage
                 (select-keys coverage blocking-coverage-keys)))]
    (when (seq blockers)
      (fail! "Unsupported translation or incomplete source coverage blocks the public surface"
             {:kind :incomplete-java-library-public-surface
              :blockers blockers})))
  emission)

(defn- visibility-adaptation! [source-visibility destination-visibility evidence]
  (cond
    (= source-visibility destination-visibility) nil
    (and (= "protected" source-visibility)
         (= "public" destination-visibility))
    :protected-override-family-widening
    (and (= "protected" source-visibility)
         (= "protected-internal" destination-visibility))
    :protected-package-visibility
    :else
    (fail! "Generated declaration changed accessible visibility without a systematic adaptation"
           {:kind :java-library-generated-visibility-mismatch
            :source-visibility source-visibility
            :destination-visibility destination-visibility
            :source-declaration (get-in evidence [:row :identity])})))

(defn- compatibility-source-files [emission]
  (->> (:artifacts emission)
       (keep
        (fn [{:keys [strategy source]}]
          (let [file (:file source)]
            (when (and strategy
                       (string? file)
                       (str/ends-with? file ".cs"))
              file))))
       distinct
       sort
       vec))

(defn- validate-generated! [surface emission]
  (validate-emission-coverage! emission)
  (let [by-key (group-by :java-key (filter :java-key (:declarations emission)))
        rows
        (mapv
         (fn [{:keys [declaration-key owner-declaration-key implicit? synthetic?
                      interface-method]
               :as evidence}]
           (let [matches (get by-key declaration-key)
                 implicit-constructor?
                 (and implicit? (= "constructor" (get-in evidence [:row :kind]))
                      (zero? (get-in evidence [:row :parameter-count])))
                 owner-matches (when implicit-constructor?
                                 (get by-key owner-declaration-key))
                 owner-match (when (= 1 (count owner-matches))
                               (first owner-matches))
                 synthetic-match (when (and synthetic? (= 1 (count matches)))
                                   (first matches))
                 direct-match (when (= 1 (count matches)) (first matches))]
             (when-not (or direct-match owner-match synthetic-match)
               (fail! "Java library surface declaration did not map to one generated declaration"
                      {:kind :java-library-generated-surface-mismatch
                       :declaration-key declaration-key
                       :match-count (count matches)
                       :owner-match-count (count owner-matches)}))
             (let [generated
                   (or
                    (when synthetic?
                      (-> synthetic-match
                          (assoc :representation
                                 :java-synthetic-public-member
                                 :implementation :systematic-adaptation)
                          (assoc :destination
                                 (assoc (:destination synthetic-match)
                                        :kind (get-in evidence [:row :kind])
                                        :name
                                        (if interface-method
                                          (method-name
                                           {:configuration
                                            (:configuration emission)}
                                           (.getDeclaringType
                                            ^CtMethod interface-method)
                                           interface-method)
                                          (get-in evidence [:row :name]))
                                        :parameter-count
                                        (str (get-in evidence
                                                     [:row :parameter-count]))))))
                    direct-match
                    (-> owner-match
                        (assoc :representation
                               :implicit-default-constructor
                               :implementation :systematic-adaptation)
                        (assoc :destination
                               (assoc (:destination owner-match)
                                      :kind "constructor" :name ".ctor"
                                      :parameter-count "0"))))
                   visibility-adaptation
                   (visibility-adaptation!
                    (get-in evidence [:row :visibility])
                    (get-in generated [:destination :visibility])
                    evidence)
                   adaptation
                   (or (:systematic-adaptation evidence)
                       (when implicit-constructor?
                         :java-implicit-default-constructor)
                       visibility-adaptation)]
               (when (= :public-stub (:implementation generated))
                 (fail! "Accessible Java declaration generated an implementation stub"
                        {:kind :public-java-library-stub
                         :source-declaration (get-in evidence [:row :identity])
                         :destination (:destination generated)}))
               (cond-> (assoc (dissoc evidence :interface-method)
                              :source-mapping
                              (if adaptation
                                :documented-systematic-adaptation
                                :one-to-one)
                              :generated generated)
                 adaptation
                 (assoc :systematic-adaptation adaptation)))))
         (:selection-evidence surface))]
    (cond-> {:schema-version 1 :strategy :complete-accessible-java-library :surface-derivation (:derivation surface) :compatibility-namespace (or (get-in emission [:configuration :compatibility-namespace]) "DripSharp.Runtime") :compatibility-sources (compatibility-source-files emission) :required-rows (count rows) :rows rows :systematic-adaptations (into (sorted-map) (keep (fn [adaptation] (when adaptation [adaptation (get systematic-adaptations adaptation)]))) (map :systematic-adaptation rows))} (:compiled-contract-file surface) (assoc :compiled-contract-file (str (:compiled-contract-file surface))))))

(defn- verify-compiled! [workspace generation build-configuration]
  (let [emissions (concat (:dependency-emissions generation)
                          [(assoc (:emission generation)
                                  :destination (:destination generation))])
        audits
        (mapv
         (fn [{:keys [project-root destination public-metadata]}]
           (let [assembly (get-in destination [:project :assembly-name])
                 framework (get-in destination [:project :target-framework])
                 file (paths/resolve-path project-root "bin" build-configuration framework
                                          (str assembly ".dll"))]
             (when-not (and (paths/regular-file? file) public-metadata
                            (seq (:rows public-metadata)))
               (fail! "Clean Java library build lacks its compiled public-surface evidence"
                      {:kind :missing-compiled-java-library-surface
                       :assembly assembly :file (str file)}))
             (assoc ((if-let [contract-file
                              (:compiled-contract-file public-metadata)]
                       #(dotnet-surface/verify!
                         workspace contract-file % public-metadata)
                       #(dotnet-surface/verify-generated!
                         workspace % public-metadata))
                     file)
                    :assembly assembly :file (str file))))
         emissions)]
    {:strategy :complete-accessible-java-library :assemblies audits}))

(defn public-surface-strategy
  "Returns the complete accessible Java-library surface strategy."
  []
  {:schema-version 1
   :id :complete-accessible-java-library
   :product-family :java-library
   :read! read-surface!
   :validate-selected! validate-selected!
   :validate-generated! validate-generated!
   :verify-compiled! verify-compiled!})
