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
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
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
         functional-interface-method method-name owner-type-node translated-node)

(defn- context [options]
  (let [template (:template options)
        ctx-holder (or (:ctx-holder template) (atom nil))
        ctx (assoc options
                   :emitted (IdentityHashMap.)
                   :declarations (atom [])
                   :diagnostics (atom [])
                   :body-translations (atom []))
        ctx (assoc ctx :body-context
                   (or (:body-context template)
                       (create-body-context
                        (:resolved-model options)
                        ctx-holder
                        (:runtime-capabilities options))))]
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

(defn- interface-static-companion-name [^CtType interface]
  (str (pascal (.getSimpleName interface)) "Statics"))

(defn- project-interface-static-companion-base [ctx ^CtType interface]
  (let [types (declaring-types interface)]
    (str "global::" (destination-namespace ctx interface) "."
         (str/join
          "."
          (concat (map #(pascal (.getSimpleName ^CtType %)) (butlast types))
                  [(interface-static-companion-name interface)])))))

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

(def ^:dynamic *destination-type-parameter-overrides* nil)

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
    (or
     (when *destination-type-parameter-overrides*
       (get *destination-type-parameter-overrides* (.getSimpleName parameter)))
     (if (contains? outer-names (.getSimpleName parameter))
       (str (if executable-owner "Method" "Nested") (pascal base))
       base))))

(declare lexical-type-parameter?)

(defn- mapped-type-base [ctx ^CtTypeReference reference occurrence]
  (let [qualified (.getQualifiedName reference)]
    (cond
      (= :null-type (:resolution occurrence))
      ["object" :dotnet.type/null]

      (instance? CtTypeParameterReference reference)
      (let [declaration
            (try
              (.getDeclaration ^CtTypeParameterReference reference)
              (catch Throwable _ nil))]
        ;; Spoon occasionally represents an inferred java.lang.Void method
        ;; argument as an undeclared type-parameter reference.  It is still
        ;; the JDK marker type, not a new C# generic parameter.  A real Java
        ;; type parameter named Void always has a live declaration and must be
        ;; preserved (JSqlParser itself has one such public class parameter).
        (if (and (= "Void" (.getSimpleName reference))
                 (not (lexical-type-parameter? reference declaration)))
          ["object" :dotnet.type/void-marker]
          [(destination-type-parameter-name (or declaration reference))
           :dotnet.type/type-parameter]))

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

(defn- lexical-type-parameter?
  [^CtTypeParameterReference reference declaration]
  (loop [current reference]
    (cond
      (nil? current) false
      (and (or (instance? CtMethod current)
               (instance? CtConstructor current)
               (instance? CtType current))
           (some #(identical? declaration %)
                 (.getFormalCtTypeParameters current)))
      true

      (.isParentInitialized ^CtElement current)
      (recur (.getParent ^CtElement current))

      :else false)))

(defn- type-parameter-bound-references [^CtTypeParameter parameter]
  (vec
   (remove nil?
           (concat [(.getSuperclass parameter)]
                   (.getSuperInterfaces parameter)))))

(defn- raw-project-type-argument-node
  [ctx ^CtTypeParameter parameter]
  (if-let [bound (first
                  (remove
                   #(= "java.lang.Object"
                       (.getQualifiedName ^CtTypeReference %))
                   (type-parameter-bound-references parameter)))]
    (type-node ctx bound)
    (raw "object")))

(defn- raw-project-type-argument-reference
  "Returns the effective Java erasure argument for a raw project type. Java
  erases a type parameter to its left-most bound, while the CLR still needs an
  explicit constructed generic argument."
  [^CtTypeParameter parameter]
  (or (first
       (remove
        #(= "java.lang.Object" (.getQualifiedName ^CtTypeReference %))
        (type-parameter-bound-references parameter)))
      (first (type-parameter-bound-references parameter))))

(defn- unbounded-wildcard-reference?
  [^CtTypeReference reference]
  (and (instance? CtWildcardReference reference)
       (= "?" (.getSimpleName reference))
       (= "java.lang.Object"
          (some-> reference
                  ^CtWildcardReference
                  .getBoundingType
                  .getQualifiedName))))

(defn- project-type-argument-nodes
  [ctx actual-arguments formal-parameters]
  (cond
    (and (empty? actual-arguments) (seq formal-parameters))
    (mapv #(raw-project-type-argument-node ctx %) formal-parameters)

    (= (count actual-arguments) (count formal-parameters))
    (mapv
     (fn [^CtTypeReference argument ^CtTypeParameter parameter]
       (if (unbounded-wildcard-reference? argument)
         (raw-project-type-argument-node ctx parameter)
         (type-node ctx argument)))
     actual-arguments
     formal-parameters)

    :else
    (mapv #(type-node ctx %) actual-arguments)))

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
                 formal-parameters
                 (vec (.getFormalCtTypeParameters part-declaration))
                 arguments (project-type-argument-nodes
                            ctx actual-arguments formal-parameters)]
             [(when (pos? index) (raw "."))
              (raw (pascal (.getSimpleName part-declaration)))
              (when (seq arguments)
                (csharp/generic-name (raw "") arguments))]))
         (range)
         references
         declarations)))
      (raw (project-type-base ctx declaration)))))

(defn- wildcard-generic-reference?
  [^CtTypeReference reference]
  (or
   (instance? CtWildcardReference reference)
   (some wildcard-generic-reference?
         (.getActualTypeArguments reference))))

(defn- generic-erasure-node
  [ctx ^CtTypeReference reference occurrence]
  (when
   (= :project (:origin occurrence))
    (when-let
     [destination
      (get-in ctx
              [:configuration :generic-erasure-mappings
               (.getQualifiedName reference)])]
      (let [arguments (vec (.getActualTypeArguments reference))
            default-eligible?
            (or (empty? arguments)
                (some wildcard-generic-reference? arguments))
            eligible?
            (if-let [generic-erasure?
                     (get-in ctx [:resolved-type-policy :generic-erasure?])]
              (generic-erasure?
               {:context ctx
                :reference reference
                :occurrence occurrence
                :default-eligible? default-eligible?})
              default-eligible?)]
        (when
         eligible?
          [(raw destination)
           :dotnet.type/raw-or-wildcard-generic-erasure])))))

(declare nullable-boxed-collection-expression?
         nullable-boxed-collection-node)

(defn- enclosing-invocation
  [^CtElement element]
  (loop [current element]
    (cond
      (instance? CtInvocation current) current
      (and current (.isParentInitialized current))
      (recur (.getParent current))
      :else nil)))

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
    (or
     (generic-erasure-node ctx reference occurrence)
     (let [[target rule] (mapped-type-base ctx reference occurrence)
           declaration (when (= :project (:origin occurrence))
                         (or (:declaration occurrence)
                             (.getTypeDeclaration reference)))
           actual-arguments (vec (.getActualTypeArguments reference))
           arguments
           (cond
             (and
              (= "java.util.Optional" (.getQualifiedName reference))
              (= 1 (count actual-arguments))
              (let [invocation (enclosing-invocation reference)
                    source (when invocation
                             (first (.getArguments invocation)))]
                (and invocation
                     source
                     (nullable-boxed-collection-expression? source []))))
             [(nullable-boxed-collection-node ctx (first actual-arguments))]

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

             (= "java.util.function.Predicate"
                (.getQualifiedName reference))
             (let [argument (first actual-arguments)]
               (when argument
                 [(recur-node argument) (raw "bool")]))

             (= "java.util.function.IntFunction"
                (.getQualifiedName reference))
             (let [result (first actual-arguments)]
               (when result
                 [(raw "int") (recur-node result)]))

             (= "java.util.function.UnaryOperator"
                (.getQualifiedName reference))
             (let [argument (first actual-arguments)]
               (when argument
                 [(recur-node argument) (recur-node argument)]))

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
        rule]))))

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
        [node rule]
        (if-let [adapt-shape (:adapt-shape policy)]
          (or (adapt-shape {:context ctx
                            :reference reference
                            :occurrence occurrence
                            :node node
                            :rule rule})
              [node rule])
          [node rule])
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
    (assoc (sequence-node [node (raw "!")])
           ::value-adaptation :null-forgiven)))

(defn- assignment-target-node [node]
  (if (= :null-forgiven (::value-adaptation node))
    (update (first (:nodes node)) :sources (fnil into []) (:sources node))
    node))

(defn- value-type-mutation-node [target-node value-node]
  (sequence-node
   [(assignment-target-node target-node)
    (raw " = ")
    value-node]))

(declare covariant-value-override? netstandard-covariant-override?
         netstandard-public-covariant-hiding?
         wildcard-generic-covariant-override?
         interface-static-companion-member?
         inherited-default-interface-method?
         inherited-abstract-interface-method)

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
           (and (instance? CtConditional initializer)
                (boxed-primitive-reference? (.getType initializer)))
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

(defn- unbounded-wildcard-collection-reference?
  [^CtTypeReference reference]
  (let [qualified-name (some-> reference .getQualifiedName)
        parent (when (.isParentInitialized reference) (.getParent reference))
        method (when (instance? CtParameter parent)
                 (some-> ^CtParameter parent .getParent))
        project-method?
        (and (instance? CtMethod method)
             (some-> ^CtMethod method .getDeclaringType .isShadow not))]
    (and (or (= "java.util.Collection" qualified-name)
             (and (= "java.util.List" qualified-name) project-method?))
         (= 1 (count (.getActualTypeArguments reference)))
         (unbounded-wildcard-reference?
          (first (.getActualTypeArguments reference))))))

(defn- unbounded-wildcard-map-reference?
  [^CtTypeReference reference]
  (and (= "java.util.Map" (some-> reference .getQualifiedName))
       (= 2 (count (.getActualTypeArguments reference)))
       (some unbounded-wildcard-reference?
             (.getActualTypeArguments reference))))

(defn- upper-bounded-wildcard-map-value
  [^CtTypeReference reference]
  (when (and (= "java.util.Map" (some-> reference .getQualifiedName))
             (= 2 (count (.getActualTypeArguments reference))))
    (let [value (second (.getActualTypeArguments reference))]
      (when (and (instance? CtWildcardReference value)
                 (.isUpper ^CtWildcardReference value))
        (.getBoundingType ^CtWildcardReference value)))))

(defn- concrete-map-type-arguments
  [^CtTypeReference reference]
  (let [arguments (some-> reference .getActualTypeArguments vec)
        resolved
        (mapv
         (fn [argument]
           (if (instance? CtWildcardReference argument)
             (when (.isUpper ^CtWildcardReference argument)
               (.getBoundingType ^CtWildcardReference argument))
             argument))
         arguments)]
    (when (and (= 2 (count arguments))
               (every? some? resolved))
      resolved)))

(defn- resolved-wildcard-map-component
  [^CtTypeReference expected ^CtTypeReference argument]
  (let [candidate
        (if (and (instance? CtWildcardReference expected)
                 (.isUpper ^CtWildcardReference expected))
          (.getBoundingType ^CtWildcardReference expected)
          expected)]
    (if (instance? CtTypeParameterReference candidate)
      (or argument candidate)
      candidate)))

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

(defn- explicitly-nullable-expression?
  [^CtExpression expression]
  (cond
    (nil? expression)
    false

    (instance? CtLiteral expression)
    (nil? (.getValue ^CtLiteral expression))

    (instance? CtConditional expression)
    (or (explicitly-nullable-expression?
         (.getThenExpression ^CtConditional expression))
        (explicitly-nullable-expression?
         (.getElseExpression ^CtConditional expression)))

    :else false))

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
                (some #(explicitly-nullable-expression? %)
                      (.getArguments invocation)))))
       (.getElements executable (TypeFilter. CtInvocation))))))

(declare nullable-boxed-collection-declaration?)

(defn- accessed-declaration
  [^CtExpression expression]
  (when (instance? CtVariableAccess expression)
    (some-> ^CtVariableAccess expression .getVariable .getDeclaration)))

(defn- null-literal-expression?
  [^CtExpression expression]
  (and (instance? CtLiteral expression)
       (nil? (.getValue ^CtLiteral expression))))

(defn- variable-null-comparison?
  [^CtBinaryOperator binary ^CtElement variable]
  (let [left (.getLeftHandOperand binary)
        right (.getRightHandOperand binary)]
    (or (and (identical? variable (accessed-declaration left))
             (null-literal-expression? right))
        (and (null-literal-expression? left)
             (identical? variable (accessed-declaration right))))))

(defn- null-compared-boxed-collection-field?
  [^CtField field]
  (boolean
   (some
    (fn [^CtForEach foreach]
      (and
       (identical? field (accessed-declaration (.getExpression foreach)))
       (let [variable (.getVariable foreach)]
         (some #(variable-null-comparison? % variable)
               (.getElements (.getBody foreach)
                             (TypeFilter. CtBinaryOperator))))))
    (.getElements (.getDeclaringType field)
                  (TypeFilter. CtForEach)))))

(defn- nullable-boxed-collection-parameter?
  [^CtParameter parameter seen]
  (let [executable (when (.isParentInitialized parameter)
                     (.getParent parameter))]
    (boolean
     (when (instance? CtExecutable executable)
       (or
        (some
         (fn [^CtAssignment assignment]
           (let [target (accessed-declaration (.getAssigned assignment))]
             (and
              (identical? parameter
                          (accessed-declaration (.getAssignment assignment)))
              (instance? CtTypedElement target)
              (nullable-boxed-collection-declaration?
               target (.getType ^CtTypedElement target) seen))))
         (.getElements executable (TypeFilter. CtAssignment)))
        (some
         (fn [^CtInvocation invocation]
           (let [target (.getDeclaration (.getExecutable invocation))
                 parameters (when (instance? CtExecutable target)
                              (vec (.getParameters ^CtExecutable target)))]
             (some
              identity
              (map-indexed
               (fn [index ^CtExpression argument]
                 (when (and
                        (identical? parameter (accessed-declaration argument))
                        (< index (count parameters)))
                   (let [target-parameter (nth parameters index)]
                     (nullable-boxed-collection-declaration?
                      target-parameter
                      (.getType ^CtParameter target-parameter)
                      seen))))
               (.getArguments invocation)))))
         (.getElements executable (TypeFilter. CtInvocation))))))))

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
      (or
       (and (instance? CtMethod declaration)
            (nullable-boxed-collection-declaration?
             declaration (.getType ^CtMethod declaration) seen))
       (nullable-boxed-collection-expression?
        (.getTarget ^CtInvocation expression) seen)
       (some #(nullable-boxed-collection-expression? % seen)
             (.getArguments ^CtInvocation expression))))

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
            (or
             (null-compared-boxed-collection-field? declaration)
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
              (.getElements owner (TypeFilter. CtAssignment)))))

          (instance? CtParameter declaration)
          (nullable-boxed-collection-parameter? declaration seen)

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

(defn- project-wildcard-reference?
  [^CtTypeReference reference]
  (let [declaration (.getTypeDeclaration reference)]
    (and (instance? CtType declaration)
         (not (.isShadow ^CtType declaration))
         (some #(instance? CtWildcardReference %)
               (.getActualTypeArguments reference)))))

(defn- declaration-type-node [ctx ^CtElement element ^CtTypeReference reference]
  (let [initializer
        (when (instance? CtLocalVariable element)
          (.getDefaultExpression ^CtLocalVariable element))
        initializer-reference
        (when (instance? CtLocalVariable element)
          (some-> initializer .getType))
        null-initializer?
        (and (instance? CtLiteral initializer)
             (nil? (.getValue ^CtLiteral initializer)))
        inferred-raw-local-reference
        (when (and initializer-reference
                   (empty? (.getActualTypeArguments reference))
                   (= (.getQualifiedName reference)
                      (.getQualifiedName ^CtTypeReference initializer-reference))
                   (seq (.getActualTypeArguments
                         ^CtTypeReference initializer-reference)))
          initializer-reference)
        inferred-wildcard-local?
        (and (instance? CtLocalVariable element)
             (project-wildcard-reference? reference)
             (or (and initializer-reference (not null-initializer?))
                 (and (.isParentInitialized element)
                      (instance? CtForEach (.getParent element)))))
        base (or (when inferred-wildcard-local? (raw "var"))
                 (when (nullable-boxed-collection-declaration?
                        element reference)
                   (nullable-boxed-collection-node ctx reference))
                 (when inferred-raw-local-reference
                   (type-node ctx inferred-raw-local-reference))
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

(defn- non-static-member-superclass? [^CtType type]
  (when (instance? CtClass type)
    (let [superclass (.getSuperclass ^CtClass type)
          declaration (some-> superclass .getTypeDeclaration)]
      (and (instance? CtType declaration)
           (non-static-member-class? declaration)
           (= (some-> type .getDeclaringType .getQualifiedName)
              (some-> ^CtType declaration .getDeclaringType
                      .getQualifiedName))))))

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

(defn- anonymous-callable? [^CtConstructorCall call]
  (= "java.util.concurrent.Callable"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-delegate-method [ctx ^CtConstructorCall call]
  (let [qualified (some-> call .getType .getQualifiedName)]
    (or (when (= "java.util.concurrent.Callable" qualified) "call")
        (when (= "java.util.Comparator" qualified) "compare")
        (get (:destination-anonymous-delegate-methods ctx) qualified))))

(defn- anonymous-delegate? [ctx ^CtConstructorCall call]
  (some? (anonymous-delegate-method ctx call)))

(defn- anonymous-delegate-node [ctx ^CtConstructorCall call]
  (let [^CtClass anonymous-class (anonymous-class-for-call call)
        method-name (anonymous-delegate-method ctx call)
        methods (vec (.getMethodsByName anonymous-class method-name))
        method (first methods)]
    (when (seq (.getArguments call))
      (unsupported! "Anonymous functional-interface construction cannot have base arguments"
                    call))
    (when-not (and (= 1 (count methods))
                   (some? (.getBody ^CtMethod method)))
      (unsupported! "Anonymous functional interface requires one body-bearing method"
                    anonymous-class))
    (sequence-node [(raw "(")
                    (sequence-node
                     (mapv (fn [^CtParameter parameter]
                             (raw (identifier (.getSimpleName parameter))))
                           (.getParameters ^CtMethod method))
                     ", ")
                    (raw ") => ")
                    (translated-node ctx (.getBody ^CtMethod method))])))

(defn- anonymous-x509-trust-manager? [^CtConstructorCall call]
  (= "javax.net.ssl.X509TrustManager"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-filter-output-stream? [^CtConstructorCall call]
  (= "java.io.FilterOutputStream"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-byte-array-output-stream? [^CtConstructorCall call]
  (= "java.io.ByteArrayOutputStream"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-linked-hash-map? [^CtConstructorCall call]
  (= "java.util.LinkedHashMap"
     (some-> call .getType .getQualifiedName)))

(defn- anonymous-project-type? [ctx ^CtConstructorCall call]
  (let [reference (.getType call)
        declaration (some-> reference .getTypeDeclaration)]
    (or (and (or (instance? CtEnum declaration)
                 (instance? CtClass declaration)
                 (and (interface-type? declaration)
                      (not (.isShadow ^CtType declaration))))
             (not (.isShadow ^CtType declaration)))
        (some? (translated-external-type-base ctx reference)))))

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
        literal-type (some-> literal .getType .getQualifiedName)
        byte-literal?
        (and (= "byte" literal-type)
             (instance? Number value))]
    (raw
     (if byte-literal?
       (str "unchecked((sbyte)" value ")")
       (if (and (= "short" literal-type) (instance? Number value))
         (str "unchecked((short)" value ")")
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
         :else (str value)))))))

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
        enum-synthetic-method-collision?
        (and (instance? CtEnum owner)
             (contains? #{"values" "valueOf"} (.getSimpleName field)))
        method-collision?
        (and owner
             (seq (.getMethodsByName ^CtType owner (.getSimpleName field))))]
    (if-not (or type-collision?
                enum-synthetic-method-collision?
                method-collision?)
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

(defn-
  resolved-name
  [ctx occurrence reference]
  (if-let
   [destination-name
    (when-let
     [resolve-name (:destination-resolved-name ctx)]
      (resolve-name ctx occurrence reference))]
    destination-name
    (cond
      (and (= :intrinsic (:origin occurrence)) (= :class-literal (:resolution occurrence)))
      "class"
      (= :project (:origin occurrence))
      (if-let
       [destination-name (:destination-project-resolved-name ctx)]
        (destination-name ctx occurrence reference)
        (let
         [declaration (:declaration occurrence)]
          (cond
            (instance? CtField declaration)
            (destination-field-name ctx declaration)
            (instance? CtMethod declaration)
            (method-name ctx (.getDeclaringType declaration) declaration)
            :else
            (identifier (.getSimpleName reference)))))
      (contains? (:destination-invocation-adaptations ctx) (:key occurrence))
      (identifier (.getSimpleName reference))
      (contains? (:destination-field-adaptations ctx) (:key occurrence))
      (identifier (.getSimpleName reference))
      (and
       (= :dependency (:origin occurrence))
       (some->> reference .getDeclaringType (translated-external-type-base ctx)))
      (csharp-public-name (.getSimpleName reference))
      (and (= :intrinsic (:origin occurrence)) (= :enum-synthetic-method (:resolution occurrence)))
      (identifier (.getSimpleName reference))
      (and
       (= :inherited-runtime-member (:resolution occurrence))
       (or
        (str/ends-with? (:key occurrence) "#equals(java.lang.Object)")
        (str/ends-with? (:key occurrence) "#hashCode()")
        (str/ends-with? (:key occurrence) "#toString()")))
      (identifier (.getSimpleName reference))
      (or
       (contains? library-mappings/executable-keys (:key occurrence))
       (contains? library-mappings/field-keys (:key occurrence)))
      (identifier (.getSimpleName reference))
      (= "field:<array>#length" (:key occurrence))
      "Length"
      (= "field:java.io.FilterOutputStream#out" (:key occurrence))
      "@out"
      (= "field:java.lang.System#out" (:key occurrence))
      "@out"
      (= "field:java.lang.System#err" (:key occurrence))
      "err"
      (= "field:java.lang.ProcessBuilder$Redirect#INHERIT" (:key occurrence))
      "INHERIT"
      (= "field:java.nio.charset.StandardCharsets#US_ASCII" (:key occurrence))
      "USASCII"
      (= "field:java.nio.charset.StandardCharsets#UTF_8" (:key occurrence))
      "UTF8"
      (= "field:java.nio.charset.StandardCharsets#ISO_8859_1" (:key occurrence))
      "ISO88591"
      (= "field:java.nio.charset.StandardCharsets#UTF_16LE" (:key occurrence))
      "UTF16LE"
      (= "field:java.time.ZoneOffset#UTC" (:key occurrence))
      "Zero"
      (= "field:java.time.format.DateTimeFormatter#RFC_1123_DATE_TIME" (:key occurrence))
      "Rfc1123"
      (= "field:java.util.Locale#ROOT" (:key occurrence))
      "InvariantCulture"
      (contains?
       #{"field:java.lang.Integer#MAX_VALUE" "field:java.lang.Byte#MAX_VALUE"}
       (:key occurrence))
      "MaxValue"
      (= "field:java.lang.Byte#MIN_VALUE" (:key occurrence))
      "MinValue"
      (and (= :field (:kind occurrence)) (str/ends-with? (:key occurrence) "#class"))
      "class"
      (contains?
       #{"field:java.nio.file.attribute.AclEntryPermission#WRITE_ACL"
         "field:java.nio.file.attribute.AclEntryPermission#WRITE_NAMED_ATTRS"
         "field:java.nio.file.attribute.AclEntryType#ALLOW"
         "field:java.nio.file.attribute.AclEntryPermission#SYNCHRONIZE"
         "field:java.nio.file.attribute.AclEntryPermission#DELETE_CHILD"
         "field:java.nio.file.StandardOpenOption#READ"
         "field:java.nio.file.attribute.AclEntryPermission#DELETE"
         "field:java.nio.file.attribute.AclEntryPermission#APPEND_DATA"
         "field:java.nio.file.attribute.AclEntryPermission#READ_NAMED_ATTRS"
         "field:java.nio.channels.FileChannel$MapMode#READ_ONLY"
         "field:java.nio.file.attribute.AclEntryPermission#READ_ATTRIBUTES"
         "field:java.nio.file.attribute.AclEntryPermission#WRITE_ATTRIBUTES"
         "field:java.nio.file.attribute.AclEntryPermission#READ_DATA"
         "field:java.nio.file.attribute.AclEntryPermission#EXECUTE"
         "field:java.nio.file.attribute.AclEntryPermission#READ_ACL"
         "field:java.nio.file.attribute.AclEntryPermission#WRITE_DATA"}
       (:key occurrence))
      (identifier (.getSimpleName reference))
      (str/starts-with? (:key occurrence) "executable:java.util.Map#of(")
      (identifier (.getSimpleName reference))
      (str/starts-with? (:key occurrence) "executable:java.util.Set#of(")
      (identifier (.getSimpleName reference))
      (or
       (and
        (str/starts-with? (:key occurrence) "executable:java.util.Arrays#copyOf(")
        (str/ends-with? (:key occurrence) ",int)"))
       (and
        (str/starts-with? (:key occurrence) "executable:java.util.Arrays#copyOfRange(")
        (str/ends-with? (:key occurrence) ",int,int)")))
      (identifier (.getSimpleName reference))
      (contains?
       #{"field:java.util.concurrent.TimeUnit#DAYS"
         "field:java.util.concurrent.TimeUnit#MICROSECONDS"
         "field:java.util.concurrent.TimeUnit#HOURS"
         "field:java.util.concurrent.TimeUnit#MILLISECONDS"
         "field:java.util.concurrent.TimeUnit#NANOSECONDS"
         "field:java.util.concurrent.TimeUnit#SECONDS"
         "field:java.util.concurrent.TimeUnit#MINUTES"}
       (:key occurrence))
      (identifier (.getSimpleName reference))
      :else
      (unsupported! "Java library executable or field has no neutral mapping" reference))))

(defn- registry-entry [id emit]
  {:id id :emit emit})

(defn-
  semantic-mappings
  [resolved-model ctx-holder]
  (reduce
   (fn
     [registries occurrence]
     (let
      [category
       (case
        (:kind occurrence)
         :type
         :types
         :executable
         :executables
         :constructor
         :constructors
         :field
         :fields
         :annotation
         :annotations
         nil)
       key
       (:key occurrence)]
       (if
        (or (nil? category) (get-in registries [category key]))
         registries
         (assoc-in
          registries
          [category key]
          (case
           category
            :types
            (registry-entry
             (keyword "java-library.resolved.type" (name (:origin occurrence)))
             (fn [{:keys [element]}] {:node (type-node @ctx-holder element)}))
            :executables
            (registry-entry
             (keyword "java-library.resolved.executable" (name (:origin occurrence)))
             (fn
               [{:keys [element occurrence]}]
               {:node (raw (resolved-name @ctx-holder occurrence element))}))
            :constructors
            (registry-entry
             (keyword "java-library.resolved.constructor" (name (:origin occurrence)))
             (fn
               [{:keys [element occurrence]}]
               (cond
                 (= :project (:origin occurrence))
                 {:node (raw "<init>")}
                 (and
                  (= :dependency (:origin occurrence))
                  (some->> element .getDeclaringType (translated-external-type-base @ctx-holder)))
                 {:node (raw "<init>")}
                 (and
                  (= :intrinsic (:origin occurrence))
                  (= :enum-synthetic-constructor (:resolution occurrence)))
                 {:node (raw "<init>")}
                 (and (= :intrinsic (:origin occurrence)) (= :array-constructor (:resolution occurrence)))
                 {:node (raw "<init>")}
                 (contains? (:destination-constructor-adaptations @ctx-holder) (:key occurrence))
                 {:node (raw "<init>")}
                 (when-let
                  [resolved-constructor? (:destination-resolved-constructor? @ctx-holder)]
                   (resolved-constructor? @ctx-holder occurrence element))
                 {:node (raw "<init>")}
                 (contains? library-mappings/constructor-keys (:key occurrence))
                 {:node (raw "")}
                 :else
                 (unsupported! "Java library constructor has no neutral mapping" element))))
            :fields
            (registry-entry
             (keyword "java-library.resolved.field" (name (:origin occurrence)))
             (fn
               [{:keys [element occurrence]}]
               {:node (raw (resolved-name @ctx-holder occurrence element))}))
            :annotations
            (registry-entry
             :java-library.resolved.annotation/source-only
             (fn [_] {:node (raw "")})))))))
   {:types {}, :executables {}, :constructors {}, :fields {}, :annotations {}}
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

(defn- project-wildcard-interface-view?
  [^CtExpression expression ^CtTypeReference cast]
  (let [arguments (vec (.getActualTypeArguments cast))
        cast-declaration (.getTypeDeclaration cast)]
    (when (and (seq arguments)
               (some #(instance? CtWildcardReference %) arguments)
               (interface-type? cast-declaration)
               (not (.isShadow ^CtType cast-declaration)))
      (letfn [(implements? [^CtTypeReference reference seen]
                (when (and reference
                           (not (contains? seen (.getQualifiedName reference))))
                  (or (= (.getQualifiedName cast)
                         (.getQualifiedName reference))
                      (when-let [declaration (.getTypeDeclaration reference)]
                        (when (and (instance? CtType declaration)
                                   (not (.isShadow ^CtType declaration)))
                          (let [seen (conj seen (.getQualifiedName reference))]
                            (or
                             (some #(implements? % seen)
                                   (.getSuperInterfaces ^CtType declaration))
                             (when (instance? CtClass declaration)
                               (implements?
                                (.getSuperclass ^CtClass declaration)
                                seen)))))))))]
        (implements? (.getType expression) #{})))))

(defn- expression-cast-node [ctx ^CtExpression expression node]
  (reduce (fn [inner ^CtTypeReference cast]
            (let [qualified-name (.getQualifiedName cast)
                  arguments (vec (.getActualTypeArguments cast))]
              (cond
                (project-wildcard-interface-view? expression cast)
                inner

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

                (contains? #{"byte" "short" "char"} qualified-name)
                (sequence-node
                 [(raw "unchecked((") (type-node ctx cast)
                  (raw ")(") inner (raw "))")])

                (and (not (.isPrimitive cast))
                     (or (instance? CtTypeParameterReference cast)
                         (instance?
                          CtTypeParameterReference
                          (.getType expression))))
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.CastReference<")
                  (type-node ctx cast) (raw ">(") inner (raw ")")])

                :else
                (sequence-node [(raw "(") (type-node ctx cast)
                                (raw ")(") inner
                                (raw (if (.isPrimitive cast) ")" "!)"))]))))
          node
          (reverse (.getTypeCasts expression))))

(declare maybe-unbox-node compat-call)

(defn- unbox-object-as-node [ctx ^CtTypeReference target-reference node]
  (sequence-node
   [(raw "global::DripSharp.Runtime.JavaCompat.UnboxObject<")
    (type-node ctx target-reference)
    (raw ">(") node (raw ")")]))

(defn- destination-value-node
  ([ctx kind source target-reference target node]
   (destination-value-node
    ctx kind source target-reference target node nil))
  ([ctx kind source target-reference target node destination-metadata]
   (if-let [adapt (:destination-value-adapter ctx)]
     (or
      (adapt
       (merge
        {:destination-context ctx
         :kind kind
         :source source
         :target target
         :target-reference target-reference
         :node node}
        destination-metadata))
      node)
     node)))

(defn- assignment-value-node [ctx ^CtElement assigned ^CtExpression assignment node]
  (let [assigned-declaration
        (if (instance? CtVariableAccess assigned)
          (or (some-> ^CtVariableAccess assigned .getVariable .getDeclaration)
              assigned)
          assigned)
        assigned-reference
        (if (instance? CtArrayWrite assigned)
          (some-> ^CtArrayWrite assigned .getTarget .getType .getComponentType)
          (some-> assigned .getType))
        assigned-type (some-> assigned-reference .getQualifiedName)
        assignment-type (some-> assignment .getType .getQualifiedName)
        node
        (cond
          (and assigned-reference (.isPrimitive assigned-reference))
          (if (boxed-primitive-reference? (.getType assignment))
            (unbox-object-as-node ctx assigned-reference node)
            (maybe-unbox-node ctx assignment node))

          (and (boxed-primitive-reference? assigned-reference)
               (not (nullable-boxed-declaration?
                     ctx assigned-declaration assigned-reference)))
          (maybe-unbox-node ctx assignment node)

          :else node)]
    (destination-value-node
     ctx :assignment assignment assigned-reference assigned
     (cond
       (nullable-boxed-collection-declaration? assigned assigned-reference)
       (let [element (boxed-primitive-collection-element assigned-reference)]
         (sequence-node
          [(raw "global::DripSharp.Runtime.JavaCompat.CastList<")
           (type-node ctx element) (raw "?>(") node (raw ")")]))

       (and (instance? CtMethod assigned)
            (covariant-list-node ctx assigned assigned-reference)
            (= "java.util.List" assignment-type)
            (= 1 (count (.getActualTypeArguments (.getType assignment)))))
       (let [bound
             (.getBoundingType
              ^CtWildcardReference
              (first (.getActualTypeArguments assigned-reference)))]
         (sequence-node
          [(raw "global::DripSharp.Runtime.JavaCompat.ToReadOnlyList<")
           (type-node ctx bound) (raw ">(") node (raw ")")]))

       (unbounded-wildcard-map-reference? assigned-reference)
       (sequence-node
        [(raw "global::DripSharp.Runtime.JavaCompat.CastDictionary<object, object>(")
         node (raw ")")])

       (and (contains? #{"java.util.Queue" "java.util.Deque"} assigned-type)
            (= "java.util.LinkedList" assignment-type)
            (instance? CtConstructorCall assignment)
            (= 1 (count (.getActualTypeArguments assigned-reference))))
       (sequence-node
        [(raw "new global::DripSharp.Runtime.JavaDeque<")
         (type-node ctx (first (.getActualTypeArguments assigned-reference)))
         (raw ">()")])

       (and (= "java.util.Collection" assigned-type)
            (= 1 (count (.getActualTypeArguments assigned-reference)))
            (= "java.util.Collection" assignment-type)
            (empty? (.getActualTypeArguments (.getType assignment))))
       (sequence-node
        [(raw "global::DripSharp.Runtime.JavaCompat.CastCollection<")
         (type-node ctx (first (.getActualTypeArguments assigned-reference)))
         (raw ">(") node (raw ")")])

       (and (= "byte" assigned-type)
            (or (instance? CtArrayWrite assigned)
                (not= "byte" assignment-type)))
       (sequence-node [(raw "unchecked((sbyte)(") node (raw "))")])

       (and (= "short" assigned-type) (not= "short" assignment-type))
       (sequence-node [(raw "unchecked((short)(") node (raw "))")])

       (and (= "char" assigned-type) (not= "char" assignment-type))
       (sequence-node [(raw "unchecked((char)(") node (raw "))")])

       :else node))))

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

(defn- type-parameter-declaration
  [^CtTypeParameterReference reference]
  (try
    (.getDeclaration reference)
    (catch Throwable _ nil)))

(defn- type-reference-contains-formal?
  [^CtTypeReference reference ^CtTypeParameter formal]
  (boolean
   (or
    (and
     (instance? CtTypeParameterReference reference)
     (= formal
        (type-parameter-declaration
         ^CtTypeParameterReference reference)))
    (and
     (instance? CtArrayTypeReference reference)
     (type-reference-contains-formal?
      (.getComponentType ^CtArrayTypeReference reference)
      formal))
    (some #(type-reference-contains-formal? % formal)
          (.getActualTypeArguments reference)))))

(defn- inferable-formal-type-arguments?
  [^CtMethod declaration actual]
  (let [formals (vec (.getFormalCtTypeParameters declaration))
        parameters (mapv #(.getType ^CtParameter %)
                         (.getParameters declaration))]
    (and
     (= (count formals) (count actual))
     (every?
      true?
      (map
       (fn [^CtTypeParameter formal ^CtTypeReference argument]
         (and
          (= formal
             (when (instance? CtTypeParameterReference argument)
               (type-parameter-declaration
                ^CtTypeParameterReference argument)))
          (some #(and (not (contains?
                            #{"java.lang.Class"
                              "java.lang.reflect.Constructor"}
                            (.getQualifiedName ^CtTypeReference %)))
                      (type-reference-contains-formal? % formal))
                parameters)))
       formals
       actual)))))

(defn- wildcard-method-type-parameters
  [^CtMethod method]
  (if-not (contains? #{"accept" "visit"} (.getSimpleName method))
    []
    (vec
     (mapcat
      (fn [parameter-index ^CtParameter parameter]
        (let [reference (.getType parameter)
              declaration (.getTypeDeclaration ^CtTypeReference reference)
              actuals (vec (.getActualTypeArguments reference))
              formals (when (instance? CtType declaration)
                        (vec (.getFormalCtTypeParameters ^CtType declaration)))]
          (when (and (instance? CtType declaration)
                     (not (.isShadow ^CtType declaration)))
            (keep-indexed
             (fn [argument-index ^CtTypeReference argument]
               (when (instance? CtWildcardReference argument)
                 (let [wildcard-bound
                       (.getBoundingType ^CtWildcardReference argument)
                       formal-bound
                       (some->> (nth formals argument-index nil)
                                type-parameter-bound-references
                                (remove #(= "java.lang.Object"
                                            (.getQualifiedName ^CtTypeReference %)))
                                first)
                       bound
                       (if (and wildcard-bound
                                (not= "java.lang.Object"
                                      (.getQualifiedName wildcard-bound)))
                         wildcard-bound
                         formal-bound)]
                   {:argument-index argument-index
                    :bound bound
                    :name (str "TWildcard" parameter-index "_" argument-index)
                    :parameter parameter
                    :parameter-index parameter-index
                    :reference reference})))
             actuals))))
      (range)
      (.getParameters method)))))

(defn- enclosing-method-for-element
  [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtMethod current) current
      (.isParentInitialized ^CtElement current)
      (recur (.getParent ^CtElement current))
      :else nil)))

(defn- current-wildcard-variable-node
  [^CtExpression argument argument-index]
  (let [parameter-declaration
        (when (instance? CtVariableAccess argument)
          (some-> ^CtVariableAccess argument .getVariable .getDeclaration))
        current-method (enclosing-method-for-element argument)]
    (when (and current-method parameter-declaration)
      (some
       #(when (and (identical? parameter-declaration (:parameter %))
                   (= argument-index (:argument-index %)))
          (raw (:name %)))
       (wildcard-method-type-parameters current-method)))))

(defn- invocation-formal-wildcard-overrides
  [^CtInvocation invocation ^CtMethod declaration]
  (into
   {}
   (keep-indexed
    (fn [formal-index ^CtTypeParameter formal]
      (first
       (keep-indexed
        (fn [parameter-index ^CtParameter parameter]
          (let [reference (.getType parameter)]
            (first
             (keep-indexed
              (fn [argument-index ^CtTypeReference argument]
                (when (type-reference-contains-formal? argument formal)
                  (when-let [node
                             (some-> (nth (vec (.getArguments invocation))
                                          parameter-index nil)
                                     (current-wildcard-variable-node argument-index))]
                    [formal-index node])))
              (.getActualTypeArguments reference)))))
        (.getParameters declaration))))
    (.getFormalCtTypeParameters declaration))))

(defn- direct-generic-supertype-argument
  [^CtTypeReference actual-reference expected-qualified argument-index]
  (let [actual-declaration (.getTypeDeclaration actual-reference)]
    (when (instance? CtType actual-declaration)
      (let [owner-formals
            (vec (.getFormalCtTypeParameters ^CtType actual-declaration))
            owner-actuals
            (let [actuals (vec (.getActualTypeArguments actual-reference))]
              (if (and (empty? actuals) (seq owner-formals))
                (mapv raw-project-type-argument-reference owner-formals)
                actuals))
            owner-substitutions
            (when (= (count owner-formals) (count owner-actuals))
              (zipmap (map #(.getSimpleName ^CtTypeParameter %) owner-formals)
                      owner-actuals))
            references
            (concat (.getSuperInterfaces ^CtType actual-declaration)
                    (when (instance? CtClass actual-declaration)
                      [(.getSuperclass ^CtClass actual-declaration)]))]
        (some
         (fn [^CtTypeReference reference]
           (when (= expected-qualified (.getQualifiedName reference))
             (when-let [^CtTypeReference argument
                        (nth (vec (.getActualTypeArguments reference))
                             argument-index nil)]
               (if (instance? CtTypeParameterReference argument)
                 (get owner-substitutions (.getSimpleName argument) argument)
                 argument))))
         (remove nil? references))))))

(defn- generic-inference-reference
  [^CtExpression expression]
  (let [cast-reference (some-> expression .getTypeCasts last)]
    (if (and cast-reference
             (not (wildcard-generic-reference? cast-reference)))
      cast-reference
      (.getType expression))))

(defn- formal-reference-path
  [^CtTypeReference reference ^CtTypeParameter formal]
  (cond
    (and (instance? CtTypeParameterReference reference)
         (= formal
            (type-parameter-declaration
             ^CtTypeParameterReference reference)))
    []

    (instance? CtArrayTypeReference reference)
    (when-let [path
               (formal-reference-path
                (.getComponentType ^CtArrayTypeReference reference)
                formal)]
      (into [:component] path))

    :else
    (some
     (fn [[index ^CtTypeReference argument]]
       (when-let [path (formal-reference-path argument formal)]
         (into [index] path)))
     (map-indexed vector (.getActualTypeArguments reference)))))

(defn- reference-at-path
  [^CtTypeReference reference path]
  (reduce
   (fn [^CtTypeReference current step]
     (when current
       (if (= :component step)
         (when (instance? CtArrayTypeReference current)
           (.getComponentType ^CtArrayTypeReference current))
         (nth (vec (.getActualTypeArguments current)) step nil))))
   reference
   path))

(defn- invocation-formal-result-overrides
  [ctx ^CtInvocation invocation ^CtMethod declaration]
  (let [declared-result (.getType declaration)
        inferred-result (.getType invocation)]
    (when (and declared-result
               inferred-result
               (not (statement-expression? invocation)))
      (into
       {}
       (keep-indexed
        (fn [formal-index ^CtTypeParameter formal]
          (when-let [path (formal-reference-path declared-result formal)]
            (when (seq path)
              (when-let [reference (reference-at-path inferred-result path)]
                [formal-index (type-node ctx reference)]))))
        (.getFormalCtTypeParameters declaration))))))

(defn- invocation-formal-argument-overrides
  [ctx ^CtInvocation invocation ^CtMethod declaration]
  (let [arguments (vec (.getArguments invocation))
        parameters (vec (.getParameters declaration))]
    (into
     {}
     (keep-indexed
      (fn [formal-index ^CtTypeParameter formal]
        (some
         (fn [[parameter-index ^CtParameter parameter]]
           (let [parameter-reference (.getType parameter)
                 argument (nth arguments parameter-index nil)
                 argument-reference
                 (some-> argument generic-inference-reference)]
             (when (and argument-reference
                        (type-reference-contains-formal?
                         parameter-reference formal))
               (let [inferred
                     (if (instance? CtTypeParameterReference
                                    parameter-reference)
                       argument-reference
                       (first
                        (keep-indexed
                         (fn [argument-index ^CtTypeReference nested]
                           (when (type-reference-contains-formal? nested formal)
                             (if (= (.getQualifiedName parameter-reference)
                                    (.getQualifiedName argument-reference))
                               (nth (vec (.getActualTypeArguments
                                          argument-reference))
                                    argument-index nil)
                               (direct-generic-supertype-argument
                                argument-reference
                                (.getQualifiedName parameter-reference)
                                argument-index))))
                         (.getActualTypeArguments parameter-reference))))]
                 (when inferred
                   [formal-index (type-node ctx inferred)])))))
         (map-indexed vector parameters)))
      (.getFormalCtTypeParameters declaration)))))

(defn- lifted-wildcard-invocation-argument-node
  [ctx ^CtInvocation invocation {:keys [argument-index bound parameter-index]}]
  (let [argument (nth (vec (.getArguments invocation)) parameter-index nil)
        argument-reference (some-> argument .getType)
        actual (when argument-reference
                 (nth (vec (.getActualTypeArguments
                            ^CtTypeReference argument-reference))
                      argument-index nil))
        current-lift-node
        (when argument
          (current-wildcard-variable-node argument argument-index))]
    (cond
      current-lift-node current-lift-node
      (and actual (not (instance? CtWildcardReference actual)))
      (type-node ctx actual)
      bound (type-node ctx bound)
      :else (raw "object"))))

(defn- project-invocation-type-arguments-node
  [ctx ^CtInvocation invocation declaration]
  (when (instance? CtMethod declaration)
    (let [formals (vec (.getFormalCtTypeParameters ^CtMethod declaration))
          lifted (wildcard-method-type-parameters declaration)
          actual (vec (.getActualTypeArguments (.getExecutable invocation)))
          formal-overrides
          (merge
           (invocation-formal-argument-overrides ctx invocation declaration)
           (invocation-formal-wildcard-overrides invocation declaration)
           (invocation-formal-result-overrides ctx invocation declaration))
          inferable? (inferable-formal-type-arguments? declaration actual)
          inferred
          (cond
            inferable?
            nil

            (seq actual)
            actual

            (and (= 1 (count (.getFormalCtTypeParameters
                              ^CtMethod declaration)))
                 (instance? CtTypeParameterReference
                            (.getType ^CtMethod declaration)))
            [(.getType invocation)])
          actual-nodes
          (mapv
           (fn [index ^CtTypeReference reference]
             (or (when (or (instance? CtTypeParameterReference reference)
                           (.isImplicit reference))
                   (get formal-overrides index))
                 (type-node ctx reference)))
           (range)
           actual)
          emitted
          (if (seq lifted)
            (concat
             (if (= (count formals) (count actual))
               actual-nodes
               (mapv #(type-node ctx %) (or inferred actual)))
             (mapv #(lifted-wildcard-invocation-argument-node
                     ctx invocation %)
                   lifted))
            (if (and (seq formal-overrides) (not inferable?))
              actual-nodes
              (mapv #(type-node ctx %) inferred)))]
      (when (seq emitted)
        (sequence-node
         [(raw "<")
          (sequence-node emitted ", ")
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

(defn- byte-array-output-stream-buffer-mapping
  [{:keys [target-element target]}]
  {:node
   (compat-call
    "ToSignedBytes"
    [(if (instance? CtSuperAccess target-element)
       (raw "this")
       target)])})

(def ^:private declarative-shared-handlers
  {:java-library.mapping/stream-collect stream-collect-mapping
   :java-library.mapping/atomic-reference-get atomic-reference-get-mapping
   :java-library.mapping/byte-array-output-stream-buffer
   byte-array-output-stream-buffer-mapping})

(defn- declarative-shared-invocation-node
  [registry ctx ^CtInvocation element occurrence target-element target
   default-target arguments children declaration]
  (when (mapping-registry/registry-entry
         registry
         (:key occurrence))
    (:node
     (mapping-registry/interpret
      registry
      (:key occurrence)
      {:target target
       :target-node target
       :target-element target-element
       :default-target-node default-target
       :arguments arguments
       :children children
       :occurrence occurrence
       :declaration declaration
       :element element
       :destination-context ctx}))))

(defn- declarative-shared-constructor-node
  [registry ctx ^CtConstructorCall element occurrence arguments]
  (when (mapping-registry/registry-entry registry (:key occurrence))
    (:node
     (mapping-registry/interpret
      registry
      (:key occurrence)
      {:arguments arguments
       :occurrence occurrence
       :element element
       :destination-context ctx}))))

(defn- declarative-shared-field-node
  [registry element occurrence target-element target-node]
  (when (mapping-registry/registry-entry
         registry
         (:key occurrence))
    (:node
     (mapping-registry/interpret
      registry
      (:key occurrence)
      {:target target-node
       :target-element target-element
       :element element}))))

(defn- target-declarative-node
  [ctx-holder mapping-fn]
  (some mapping-fn (:target-mapping-registries @ctx-holder)))

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

(defn- unbox-nullable-boxed-invocation-arguments
  [ctx ^CtInvocation invocation arguments indexes]
  (let [sources (vec (.getArguments invocation))]
    (mapv
     (fn [index node]
       (let [source (nth sources index)]
         (if (and (contains? indexes index)
                  (nullable-boxed-expression? ctx source))
           (maybe-unbox-node ctx source node)
           node)))
     (range (count arguments))
     arguments)))

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

(defn- raw-list-argument-adaptation
  [ctx ^CtExpression argument ^CtTypeReference expected node]
  (when (and expected
             (= "java.util.List" (.getQualifiedName expected))
             (= 1 (count (.getActualTypeArguments expected)))
             (contains? #{"java.util.List" "java.util.ArrayList"}
                        (some-> argument .getType .getQualifiedName))
             (empty? (.getActualTypeArguments (.getType argument))))
    (let [element-type (first (.getActualTypeArguments expected))]
      (if (and (instance? CtConstructorCall argument)
               (= "java.util.ArrayList"
                  (some-> argument .getType .getQualifiedName)))
        (if (empty? (.getArguments ^CtConstructorCall argument))
          (sequence-node
           [(raw "new global::System.Collections.Generic.List<")
            (type-node ctx element-type) (raw ">()")])
          node)
        (sequence-node
         [(raw "global::DripSharp.Runtime.JavaCompat.CastList<")
          (type-node ctx element-type) (raw ">(") node (raw ")")])))))

(defn- raw-list-constructor-for-generic-owner-node
  [ctx ^CtExpression argument ^CtTypeReference expected declaration target node]
  (when (and (instance? CtConstructorCall argument)
             (= "java.util.ArrayList"
                (some-> argument .getType .getQualifiedName))
             (= "java.util.List" (some-> expected .getQualifiedName))
             (= 1 (count (.getActualTypeArguments expected)))
             (instance? CtMethod declaration)
             target)
    (let [element-type (first (.getActualTypeArguments expected))
          owner (.getDeclaringType ^CtMethod declaration)
          formals (vec (.getFormalCtTypeParameters ^CtType owner))
          actuals (vec (.getActualTypeArguments (.getType ^CtExpression target)))
          formal-index
          (when (instance? CtTypeParameterReference element-type)
            (first
             (keep-indexed
              #(when (= (.getSimpleName ^CtTypeParameter %2)
                        (.getSimpleName ^CtTypeReference element-type))
                 %1)
              formals)))
          resolved (if (some? formal-index)
                     (nth actuals formal-index nil)
                     element-type)]
      (when resolved
        (if (empty? (.getArguments ^CtConstructorCall argument))
          (sequence-node
           [(raw "new global::System.Collections.Generic.List<")
            (type-node ctx resolved) (raw ">()")])
          node)))))

(defn- argument-value-node
  [ctx ^CtExpression argument ^CtTypeReference expected
   ^CtTypeReference declared-target-reference node force-value?]
  (let [expected-name (some-> expected .getQualifiedName)
        argument-name (some-> argument .getType .getQualifiedName)
        argument-declaration
        (when (instance? CtVariableAccess argument)
          (some-> ^CtVariableAccess argument .getVariable .getDeclaration))
        emitted-argument-reference
        (or (when (instance? CtTypedElement argument-declaration)
              (let [declared (.getType ^CtTypedElement argument-declaration)
                    initializer
                    (when (instance? CtLocalVariable argument-declaration)
                      (some-> ^CtLocalVariable argument-declaration
                              .getDefaultExpression .getType))]
                (if (and initializer
                         (empty? (.getActualTypeArguments declared))
                         (seq (.getActualTypeArguments
                               ^CtTypeReference initializer)))
                  initializer
                  declared)))
            (.getType argument))
        value-node (maybe-unbox-node ctx argument node)]
    (destination-value-node
     ctx :argument argument expected nil
     (cond
       (raw-list-argument-adaptation ctx argument expected node)
       (raw-list-argument-adaptation ctx argument expected node)

       (and expected
            (= "java.util.List" expected-name)
            (empty? (.getActualTypeArguments expected))
            (= "java.util.List" argument-name)
            (seq (.getActualTypeArguments
                  ^CtTypeReference emitted-argument-reference)))
       (sequence-node
        [(raw "(global::System.Collections.IList)(object)(") node (raw ")")])

       (unbounded-wildcard-collection-reference? expected)
       (compat-call "CastObjects" [node])

       (unbounded-wildcard-map-reference? expected)
       (let [key-reference (first (.getActualTypeArguments expected))]
         (sequence-node
          [(raw "global::DripSharp.Runtime.JavaCompat.CastDictionary<")
           (type-node ctx key-reference) (raw ", object>(") node (raw ")")]))

       (upper-bounded-wildcard-map-value expected)
       (let [[argument-key argument-value]
             (concrete-map-type-arguments emitted-argument-reference)
             key-reference
             (resolved-wildcard-map-component
              (first (.getActualTypeArguments expected)) argument-key)
             value-reference
             (resolved-wildcard-map-component
              (second (.getActualTypeArguments expected)) argument-value)]
         (sequence-node
          [(raw "global::DripSharp.Runtime.JavaCompat.CastDictionary<")
           (type-node ctx key-reference) (raw ", ")
           (type-node ctx value-reference) (raw ">(") node (raw ")")]))

       (and (contains? #{"java.util.Collection" "java.util.List"}
                       expected-name)
            (= "java.util.List" argument-name)
            (covariant-list-node ctx argument (.getType argument)))
       (let [argument-declaration
             (when (instance? CtVariableAccess argument)
               (some-> ^CtVariableAccess argument .getVariable .getDeclaration))
             argument-type
             (or (some-> argument-declaration .getType)
                 (.getType argument))
             argument-reference
             (first (.getActualTypeArguments ^CtTypeReference argument-type))
             bound
             (.getBoundingType
              ^CtWildcardReference
              argument-reference)
             element-node (type-node ctx bound)]
         (sequence-node
          [(raw "global::DripSharp.Runtime.JavaCompat.ToListValues<")
           element-node (raw ">(") node (raw ")")]))

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
       (if (= "short" expected-name)
         (sequence-node [(raw "unchecked((short)(") value-node (raw "))")])
         (sequence-node
          [(raw (str "("
                     (get {"double" "double"
                           "float" "float"
                           "long" "long"
                           "int" "int"}
                          expected-name)
                     ")("))
           value-node
           (raw ")")]))

       (and expected
            (instance? CtTypeParameterReference expected)
            (not= expected-name argument-name))
       (sequence-node
        [(raw "global::DripSharp.Runtime.JavaCompat.CastReference<")
         (type-node ctx expected) (raw ">(") node (raw ")")])

       (or force-value?
           (and expected
                (or (.isPrimitive expected)
                    (instance? CtTypeParameterReference expected))))
       value-node

       :else node)
     {:declared-target-reference declared-target-reference})))

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

(defn- wildcard-generic-conditional-result?
  [^CtConditional expression]
  (let [result-reference (.getType expression)
        then-reference (some-> expression .getThenExpression .getType)
        else-reference (some-> expression .getElseExpression .getType)]
    (and result-reference
         (not (.isPrimitive result-reference))
         ;; java.lang.Class<?> erases to the non-generic System.Type. Its
         ;; conditional branches must retain that mapped type instead of the
         ;; object convergence required by heterogeneous generic interfaces.
         (not= "java.lang.Class" (.getQualifiedName result-reference))
         (some #(instance? CtWildcardReference %)
               (.getActualTypeArguments result-reference))
         (not= (some-> then-reference .getQualifiedName)
               (some-> else-reference .getQualifiedName)))))

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
    "executable:java.util.ArrayList#add(int,java.lang.Object)"
    "executable:java.util.LinkedList#add(int,java.lang.Object)"
    "executable:java.util.List#add(java.lang.Object)"
    "executable:java.util.List#set(int,java.lang.Object)"
    "executable:java.util.ArrayList#set(int,java.lang.Object)"
    "executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
    "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
    "executable:java.util.SortedSet#headSet(java.lang.Object)"})

(defn- netstandard-math-invocation-node
  [key arguments]
  (cond
    (contains? #{"executable:java.lang.StrictMath#cbrt(double)"
                 "executable:java.lang.Math#cbrt(double)"}
               key)
    (compat-call "Cbrt" arguments)

    (contains? #{"executable:java.lang.StrictMath#copySign(double,double)"
                 "executable:java.lang.Math#copySign(double,double)"}
               key)
    (compat-call "CopySign" arguments)

    :else nil))

(defn- supplemental-neutral-invocation-node
  [ctx ^CtInvocation element key target-node arguments]
  (cond (or (and (str/starts-with? key "executable:java.util.function.") (or (str/includes? key "#accept(") (str/includes? key "#apply(") (str/includes? key "#get()"))) (= key "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)") (= key "executable:java.lang.invoke.MethodHandles#lookup()")) (cond (str/starts-with? key "executable:java.util.function.") (csharp/invocation target-node arguments) (= key "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)") (csharp/invocation (csharp/member target-node "Compare") arguments) :else (raw "global::DripSharp.Runtime.JavaMethodHandles.lookup()")) (= key "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)") (csharp/invocation target-node arguments) (str/starts-with? key "executable:java.util.Set#of(") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) (str/starts-with? key "executable:java.util.Map#of(") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.MapOf") (mapv (fn* [p1__477#] (type-node ctx p1__477#)) (.getActualTypeArguments (.getType element)))) (raw "(") (sequence-node arguments ", ") (raw ")")]) (and (str/starts-with? key "executable:java.util.Arrays#copyOf(") (str/ends-with? key ",int)")) (let [component (some-> (first (.getArguments element)) .getType .getComponentType)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.CopyOf") [(type-node ctx component)]) (raw "(") (sequence-node arguments ", ") (raw ")")])) (and (str/starts-with? key "executable:java.util.Arrays#copyOfRange(") (str/ends-with? key ",int,int)")) (let [component (some-> (first (.getArguments element)) .getType .getComponentType)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.CopyOfRange") [(type-node ctx component)]) (raw "(") (sequence-node arguments ", ") (raw ")")])) :else (case key ("executable:java.lang.Character#isLetterOrDigit(int)" "executable:java.lang.Character#isUnicodeIdentifierPart(int)" "executable:java.lang.Character#isUnicodeIdentifierStart(int)") (compat-call (case key "executable:java.lang.Character#isLetterOrDigit(int)" "IsLetterOrDigit" "executable:java.lang.Character#isUnicodeIdentifierPart(int)" "IsUnicodeIdentifierPart" "IsUnicodeIdentifierStart") arguments) "executable:java.lang.Character#isHighSurrogate(char)" (sequence-node [(raw "char.IsHighSurrogate(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Character#isLowSurrogate(char)" (sequence-node [(raw "char.IsLowSurrogate(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Character#toString(int)" (compat-call "CodePointToString" arguments) "executable:java.lang.Character#toTitleCase(int)" (compat-call "ToTitleCase" arguments) "executable:java.lang.Character#isTitleCase(int)" (compat-call "IsTitleCase" arguments) ("executable:java.lang.Character#isUpperCase(char)" "executable:java.lang.Character#isUpperCase(int)") (compat-call "IsUpperCase" arguments) "executable:java.lang.Character#toUpperCase(int)" (compat-call "ToUpperCase" arguments) "executable:java.lang.CharSequence#isEmpty()" (csharp/binary "==" 40 (sequence-node [target-node (raw ".Length")]) (raw "0")) "executable:java.lang.String#formatted(java.lang.Object[])" (compat-call "Formatted" (into [target-node] arguments)) "executable:java.lang.String#isBlank()" (sequence-node [(raw "global::System.String.IsNullOrWhiteSpace(") target-node (raw ")")]) "executable:java.lang.String#repeat(int)" (compat-call "Repeat" (into [target-node] arguments)) "executable:java.lang.String#regionMatches(boolean,int,java.lang.String,int,int)" (compat-call "RegionMatches" (into [target-node] arguments)) "executable:java.lang.String#regionMatches(int,java.lang.String,int,int)" (compat-call "RegionMatches" (into [target-node (raw "false")] arguments)) "executable:java.lang.String#lastIndexOf(int,int)" (compat-call "StringLastIndexOf" (into [target-node] arguments)) "executable:java.lang.String#lastIndexOf(java.lang.String)" (sequence-node [target-node (raw ".LastIndexOf(") (first arguments) (raw ", global::System.StringComparison.Ordinal)")]) "executable:java.util.Deque#getFirst()" (compat-call "DequeGetFirst" [target-node]) "executable:java.util.Deque#descendingIterator()" (csharp/invocation (csharp/member target-node "DescendingIterator") []) ("executable:java.util.List#of()" "executable:java.util.List#of(java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)" "executable:java.util.List#of(java.lang.Object[])") (let [element-node (collection-element-type-node ctx element)] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ListOf") [element-node]) (raw "(") (sequence-node (collection-factory-argument-nodes ctx element arguments element-node) ", ") (raw ")")])) "executable:java.util.List#copyOf(java.util.Collection)" (compat-call "ListCopyOf" arguments) ("executable:java.util.List#addAll(java.util.Collection)" "executable:java.util.ArrayList#addAll(java.util.Collection)") (compat-call "AddAll" (into [target-node] arguments)) "executable:java.util.Locale#getDefault()" (raw "global::System.Globalization.CultureInfo.CurrentCulture") "executable:java.util.Collections#singleton(java.lang.Object)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.SetOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Objects#deepEquals(java.lang.Object,java.lang.Object)" (compat-call "DeepEquals" arguments) "executable:java.util.Map#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) ("executable:java.util.function.Function#apply(java.lang.Object)" "executable:java.util.function.LongFunction#apply(long)" "executable:java.lang.Runnable#run()") (csharp/invocation target-node arguments) "executable:java.util.function.Function#identity()" (raw "value => value") "executable:java.util.function.Function#andThen(java.util.function.Function)" (compat-call "AndThen" (into [target-node] arguments)) "executable:java.util.ResourceBundle#getBundle(java.lang.String,java.util.Locale)" (compat-call "GetResourceBundle" arguments) "executable:java.util.ResourceBundle#getString(java.lang.String)" (compat-call "GetResourceString" (into [target-node] arguments)) "executable:java.util.stream.IntStream#allMatch(java.util.function.IntPredicate)" (compat-call "All" (into [target-node] arguments)) "executable:java.util.stream.IntStream#skip(long)" (compat-call "Skip" (into [target-node] arguments)) "executable:java.lang.Iterable#iterator()" (compat-call "Iterator" [target-node]) ("executable:java.util.stream.IntStream#iterator()" "executable:java.util.stream.LongStream#iterator()") (compat-call "Iterator" [target-node]) "executable:java.util.stream.Collectors#joining(java.lang.CharSequence)" (compat-call "Joining" arguments) "executable:java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)" (let [key-selector-type (.getType (first (.getArguments element))) value-selector-type (.getType (second (.getArguments element))) input-type (first (.getActualTypeArguments key-selector-type)) key-type (second (.getActualTypeArguments key-selector-type)) value-type (second (.getActualTypeArguments value-selector-type))] (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ToMap") (mapv (fn* [p1__479#] (type-node ctx p1__479#)) [input-type key-type value-type])) (raw "(") (sequence-node arguments ", ") (raw ")")])) "executable:java.util.regex.Matcher#end()" (sequence-node [target-node (raw ".End()")]) "executable:java.util.regex.Matcher#find()" (sequence-node [target-node (raw ".Find()")]) "executable:java.util.regex.Matcher#find(int)" (sequence-node [target-node (raw ".Find(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.regex.Matcher#group()" (sequence-node [target-node (raw ".Group()")]) "executable:java.util.regex.Matcher#group(int)" (sequence-node [target-node (raw ".Group(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.regex.Matcher#group(java.lang.String)" "executable:java.util.regex.Matcher#groupCount()" "executable:java.util.regex.Matcher#lookingAt()" "executable:java.util.regex.Matcher#region(int,int)" "executable:java.util.regex.Matcher#replaceFirst(java.lang.String)" "executable:java.util.regex.Matcher#start(int)" "executable:java.util.regex.Matcher#end(int)" "executable:java.util.regex.Matcher#toMatchResult()" "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuffer,java.lang.String)" "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuilder,java.lang.String)" "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuffer)" "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuilder)") (let [name (get {"executable:java.util.regex.Matcher#groupCount()" "GroupCount", "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuilder)" "AppendTail", "executable:java.util.regex.Matcher#replaceFirst(java.lang.String)" "ReplaceFirst", "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuffer,java.lang.String)" "AppendReplacement", "executable:java.util.regex.Matcher#group(java.lang.String)" "Group", "executable:java.util.regex.Matcher#start(int)" "Start", "executable:java.util.regex.Matcher#end(int)" "End", "executable:java.util.regex.Matcher#toMatchResult()" "ToMatchResult", "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuilder,java.lang.String)" "AppendReplacement", "executable:java.util.regex.Matcher#lookingAt()" "LookingAt", "executable:java.util.regex.Matcher#region(int,int)" "Region", "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuffer)" "AppendTail"} key)] (sequence-node [target-node (raw (str "." name "(")) (sequence-node arguments ", ") (raw ")")])) "executable:java.util.regex.Matcher#quoteReplacement(java.lang.String)" (compat-call "QuoteReplacement" arguments) ("executable:java.util.regex.MatchResult#end()" "executable:java.util.regex.MatchResult#end(int)" "executable:java.util.regex.MatchResult#group()" "executable:java.util.regex.MatchResult#group(int)" "executable:java.util.regex.MatchResult#groupCount()" "executable:java.util.regex.MatchResult#start()" "executable:java.util.regex.MatchResult#start(int)") (let [name (get {"executable:java.util.regex.MatchResult#end()" "End", "executable:java.util.regex.MatchResult#end(int)" "End", "executable:java.util.regex.MatchResult#group()" "Group", "executable:java.util.regex.MatchResult#group(int)" "Group", "executable:java.util.regex.MatchResult#groupCount()" "GroupCount", "executable:java.util.regex.MatchResult#start()" "Start", "executable:java.util.regex.MatchResult#start(int)" "Start"} key)] (sequence-node [target-node (raw (str "." name "(")) (sequence-node arguments ", ") (raw ")")])) "executable:java.util.regex.Matcher#replaceAll(java.lang.String)" (sequence-node [target-node (raw ".ReplaceAll(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.regex.Matcher#start()" (sequence-node [target-node (raw ".Start()")]) "executable:java.util.regex.Pattern#matches(java.lang.String,java.lang.CharSequence)" (compat-call "StringMatches" [(second arguments) (first arguments)]) "executable:java.util.regex.Pattern#compile(java.lang.String,int)" (compat-call "CompileRegex" arguments) ("executable:java.util.regex.Pattern#pattern()" "executable:java.util.regex.Pattern#toString()") (compat-call "RegexPattern" [target-node]) "executable:java.util.regex.Pattern#flags()" (compat-call "RegexFlags" [target-node]) "executable:java.util.regex.Pattern#quote(java.lang.String)" (compat-call "QuoteRegex" arguments) "executable:java.util.regex.Pattern#split(java.lang.CharSequence,int)" (compat-call "RegexSplit" (into [target-node] arguments)) "executable:java.util.stream.Stream#anyMatch(java.util.function.Predicate)" (compat-call "Any" (into [target-node] arguments)) "executable:java.util.stream.Stream#allMatch(java.util.function.Predicate)" (compat-call "AllValues" (into [target-node] arguments)) "executable:java.util.stream.Stream#noneMatch(java.util.function.Predicate)" (compat-call "NoValues" (into [target-node] arguments)) "executable:java.util.stream.Stream#distinct()" (sequence-node [(raw "global::System.Linq.Enumerable.Distinct(") target-node (raw ")")]) "executable:java.util.stream.Stream#count()" (sequence-node [(raw "global::System.Linq.Enumerable.LongCount(") target-node (raw ")")]) "executable:java.util.stream.Stream#reduce(java.util.function.BinaryOperator)" (compat-call "ReduceOptional" (into [target-node] arguments)) "executable:java.util.stream.Stream#findFirst()" (compat-call "FindFirstOptional" [target-node]) "executable:java.util.stream.StreamSupport#stream(java.util.Spliterator,boolean)" (first arguments) ("executable:java.lang.Iterable#spliterator()" "executable:java.util.Collection#spliterator()" "executable:java.util.ServiceLoader#spliterator()" "executable:java.util.stream.Stream#spliterator()") target-node "executable:java.util.stream.Stream#toList()" (compat-call "ToListValues" [target-node]) "executable:java.util.stream.IntStream#toArray()" (sequence-node [(raw "global::System.Linq.Enumerable.ToArray(") target-node (raw ")")]) "executable:java.util.stream.IntStream#max()" (compat-call "MaxOptionalInt" [target-node]) "executable:java.util.stream.IntStream#forEach(java.util.function.IntConsumer)" (compat-call "ForEach" (into [target-node] arguments)) "executable:java.util.Optional#empty()" (sequence-node [(type-node ctx (.getType element)) (raw ".Empty()")]) "executable:java.util.Optional#of(java.lang.Object)" (sequence-node [(type-node ctx (.getType element)) (raw ".Of(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#ofNullable(java.lang.Object)" (sequence-node [(type-node ctx (.getType element)) (raw ".OfNullable(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#get()" (sequence-node [target-node (raw ".Get()")]) "executable:java.util.Optional#isPresent()" (sequence-node [target-node (raw ".IsPresent()")]) "executable:java.util.Optional#isEmpty()" (sequence-node [target-node (raw ".IsEmpty()")]) "executable:java.util.Optional#equals(java.lang.Object)" (compat-call "Equals" (into [target-node] arguments)) "executable:java.util.Optional#map(java.util.function.Function)" (sequence-node [target-node (raw ".Map(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#orElse(java.lang.Object)" (sequence-node [target-node (raw ".OrElse(") (if (and (= 1 (count (.getArguments element))) (instance? CtLiteral (first (.getArguments element))) (nil? (.getValue (first (.getArguments element))))) (raw "default!") (sequence-node arguments ", ")) (raw ")")]) "executable:java.util.Optional#orElseGet(java.util.function.Supplier)" (sequence-node [target-node (raw ".OrElseGet(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#ifPresent(java.util.function.Consumer)" (sequence-node [target-node (raw ".IfPresent(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.Optional#ifPresentOrElse(java.util.function.Consumer,java.lang.Runnable)" (sequence-node [target-node (raw ".IfPresentOrElse(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.Optional#orElseThrow()" "executable:java.util.Optional#orElseThrow(java.util.function.Supplier)") (sequence-node [target-node (raw ".OrElseThrow(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.OptionalInt#isPresent()" (sequence-node [target-node (raw ".HasValue")]) "executable:java.util.OptionalInt#getAsInt()" (sequence-node [target-node (raw ".Value")]) "executable:java.util.OptionalInt#empty()" (raw "(int?)null") "executable:java.util.OptionalInt#of(int)" (first arguments) "executable:java.util.OptionalInt#orElse(int)" (sequence-node [target-node (raw ".GetValueOrDefault(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.OptionalLong#empty()" (raw "(long?)null") "executable:java.util.OptionalLong#of(long)" (first arguments) "executable:java.util.OptionalLong#ifPresent(java.util.function.LongConsumer)" (compat-call "OptionalLongIfPresent" (into [target-node] arguments)) ("executable:java.lang.Math#acos(double)" "executable:java.lang.Math#abs(double)" "executable:java.lang.Math#abs(float)" "executable:java.lang.Math#abs(int)" "executable:java.lang.Math#abs(long)" "executable:java.lang.Math#atan2(double,double)" "executable:java.lang.Math#cos(double)" "executable:java.lang.Math#floor(double)" "executable:java.lang.Math#log(double)" "executable:java.lang.Math#log10(double)" "executable:java.lang.Math#pow(double,double)" "executable:java.lang.Math#sin(double)" "executable:java.lang.Math#sqrt(double)") (let [name (get {"executable:java.lang.Math#abs(double)" "Abs", "executable:java.lang.Math#pow(double,double)" "Pow", "executable:java.lang.Math#log10(double)" "Log10", "executable:java.lang.Math#floor(double)" "Floor", "executable:java.lang.Math#cos(double)" "Cos", "executable:java.lang.Math#abs(int)" "Abs", "executable:java.lang.Math#abs(long)" "Abs", "executable:java.lang.Math#log(double)" "Log", "executable:java.lang.Math#abs(float)" "Abs", "executable:java.lang.Math#acos(double)" "Acos", "executable:java.lang.Math#atan2(double,double)" "Atan2", "executable:java.lang.Math#sqrt(double)" "Sqrt", "executable:java.lang.Math#sin(double)" "Sin"} key)] (sequence-node [(raw (str "global::System.Math." name "(")) (sequence-node arguments ", ") (raw ")")])) ("executable:java.lang.Math#floorDiv(int,int)" "executable:java.lang.Math#round(double)" "executable:java.lang.Math#round(float)" "executable:java.lang.Math#signum(double)" "executable:java.lang.Math#signum(float)" "executable:java.lang.Math#toDegrees(double)" "executable:java.lang.Math#toRadians(double)") (compat-call (get {"executable:java.lang.Math#floorDiv(int,int)" "FloorDiv", "executable:java.lang.Math#round(double)" "MathRound", "executable:java.lang.Math#round(float)" "MathRoundFloat", "executable:java.lang.Math#signum(double)" "SignumDouble", "executable:java.lang.Math#signum(float)" "SignumFloat", "executable:java.lang.Math#toDegrees(double)" "ToDegrees", "executable:java.lang.Math#toRadians(double)" "ToRadians"} key) arguments) ("executable:java.lang.Math#addExact(long,long)" "executable:java.lang.StrictMath#addExact(long,long)") (compat-call "AddExact" arguments) ("executable:java.lang.Math#addExact(int,int)" "executable:java.lang.StrictMath#addExact(int,int)") (compat-call "AddExactInt" arguments) ("executable:java.lang.Math#negateExact(int)" "executable:java.lang.Math#negateExact(long)" "executable:java.lang.StrictMath#negateExact(int)" "executable:java.lang.StrictMath#negateExact(long)") (compat-call "NegateExact" arguments) ("executable:java.lang.Math#incrementExact(int)" "executable:java.lang.Math#incrementExact(long)" "executable:java.lang.StrictMath#incrementExact(int)" "executable:java.lang.StrictMath#incrementExact(long)") (compat-call "IncrementExact" arguments) ("executable:java.lang.Math#decrementExact(int)" "executable:java.lang.Math#decrementExact(long)" "executable:java.lang.StrictMath#decrementExact(int)" "executable:java.lang.StrictMath#decrementExact(long)") (compat-call "DecrementExact" arguments) "executable:java.lang.StrictMath#toIntExact(long)" (compat-call "ToIntExact" arguments) ("executable:java.lang.StrictMath#abs(double)" "executable:java.lang.StrictMath#abs(long)" "executable:java.lang.StrictMath#acos(double)" "executable:java.lang.StrictMath#asin(double)" "executable:java.lang.StrictMath#atan(double)" "executable:java.lang.StrictMath#atan2(double,double)" "executable:java.lang.StrictMath#cbrt(double)" "executable:java.lang.StrictMath#ceil(double)" "executable:java.lang.StrictMath#copySign(double,double)" "executable:java.lang.StrictMath#cos(double)" "executable:java.lang.StrictMath#exp(double)" "executable:java.lang.StrictMath#floor(double)" "executable:java.lang.StrictMath#log(double)" "executable:java.lang.StrictMath#log10(double)" "executable:java.lang.StrictMath#max(double,double)" "executable:java.lang.StrictMath#max(long,long)" "executable:java.lang.StrictMath#min(double,double)" "executable:java.lang.StrictMath#min(long,long)" "executable:java.lang.StrictMath#pow(double,double)" "executable:java.lang.StrictMath#rint(double)" "executable:java.lang.StrictMath#sin(double)" "executable:java.lang.StrictMath#sqrt(double)" "executable:java.lang.StrictMath#tan(double)") (let [method-name (get {"executable:java.lang.StrictMath#exp(double)" "Exp", "executable:java.lang.StrictMath#floor(double)" "Floor", "executable:java.lang.StrictMath#asin(double)" "Asin", "executable:java.lang.StrictMath#cbrt(double)" "Cbrt", "executable:java.lang.StrictMath#atan2(double,double)" "Atan2", "executable:java.lang.StrictMath#cos(double)" "Cos", "executable:java.lang.StrictMath#sqrt(double)" "Sqrt", "executable:java.lang.StrictMath#max(double,double)" "Max", "executable:java.lang.StrictMath#min(long,long)" "Min", "executable:java.lang.StrictMath#abs(long)" "Abs", "executable:java.lang.StrictMath#log10(double)" "Log10", "executable:java.lang.StrictMath#pow(double,double)" "Pow", "executable:java.lang.StrictMath#copySign(double,double)" "CopySign", "executable:java.lang.StrictMath#ceil(double)" "Ceiling", "executable:java.lang.StrictMath#sin(double)" "Sin", "executable:java.lang.StrictMath#acos(double)" "Acos", "executable:java.lang.StrictMath#min(double,double)" "Min", "executable:java.lang.StrictMath#rint(double)" "Round", "executable:java.lang.StrictMath#max(long,long)" "Max", "executable:java.lang.StrictMath#atan(double)" "Atan", "executable:java.lang.StrictMath#tan(double)" "Tan", "executable:java.lang.StrictMath#log(double)" "Log", "executable:java.lang.StrictMath#abs(double)" "Abs"} key)] (csharp/invocation (raw (str "global::System.Math." method-name)) arguments)) ("executable:java.lang.StrictMath#getExponent(double)" "executable:java.lang.Math#getExponent(double)") (compat-call "GetExponent" arguments) "executable:java.lang.Double#doubleToRawLongBits(double)" (sequence-node [(raw "global::System.BitConverter.DoubleToInt64Bits(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StrictMath#signum(double)" (compat-call "SignumDouble" arguments) ("executable:java.lang.Math#multiplyExact(long,long)" "executable:java.lang.Math#multiplyExact(long,int)" "executable:java.lang.StrictMath#multiplyExact(long,int)" "executable:java.lang.StrictMath#multiplyExact(long,long)") (compat-call "MultiplyExact" arguments) ("executable:java.lang.Math#multiplyExact(int,int)" "executable:java.lang.StrictMath#multiplyExact(int,int)") (compat-call "MultiplyExactInt" arguments) ("executable:java.lang.Math#subtractExact(long,long)" "executable:java.lang.StrictMath#subtractExact(long,long)") (compat-call "SubtractExact" arguments) ("executable:java.text.Bidi#getBaseLevel()" "executable:java.text.Bidi#getRunCount()" "executable:java.text.Bidi#getRunLevel(int)" "executable:java.text.Bidi#getRunLimit(int)" "executable:java.text.Bidi#getRunStart(int)" "executable:java.text.Bidi#isMixed()") (let [name (get {"executable:java.text.Bidi#getBaseLevel()" "GetBaseLevel", "executable:java.text.Bidi#getRunCount()" "GetRunCount", "executable:java.text.Bidi#getRunLevel(int)" "GetRunLevel", "executable:java.text.Bidi#getRunLimit(int)" "GetRunLimit", "executable:java.text.Bidi#getRunStart(int)" "GetRunStart", "executable:java.text.Bidi#isMixed()" "IsMixed"} key)] (sequence-node [target-node (raw (str "." name "(")) (sequence-node arguments ", ") (raw ")")])) "executable:java.text.Bidi#reorderVisually(byte[],int,java.lang.Object[],int,int)" (sequence-node [(raw "global::DripSharp.Runtime.JavaBidi.ReorderVisually(") (sequence-node arguments ", ") (raw ")")]) "executable:java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)" (compat-call "Normalize" arguments) "executable:javax.net.ssl.KeyManagerFactory#getDefaultAlgorithm()" (raw "global::DripSharp.Runtime.JavaKeyManagerFactory.GetDefaultAlgorithm()") "executable:javax.net.ssl.KeyManagerFactory#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaKeyManagerFactory.GetInstance(") (sequence-node arguments ", ") (raw ")")]) ("executable:javax.net.ssl.KeyManagerFactory#init(java.security.KeyStore,char[])" "executable:javax.net.ssl.TrustManagerFactory#init(java.security.KeyStore)" "executable:javax.net.ssl.SSLContext#init(javax.net.ssl.KeyManager[],javax.net.ssl.TrustManager[],java.security.SecureRandom)") (sequence-node [target-node (raw ".Init(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.ssl.KeyManagerFactory#getKeyManagers()" (sequence-node [target-node (raw ".GetKeyManagers()")]) "executable:javax.net.ssl.TrustManagerFactory#getDefaultAlgorithm()" (raw "global::DripSharp.Runtime.JavaTrustManagerFactory.GetDefaultAlgorithm()") "executable:javax.net.ssl.TrustManagerFactory#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaTrustManagerFactory.GetInstance(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.ssl.TrustManagerFactory#getTrustManagers()" (sequence-node [target-node (raw ".GetTrustManagers()")]) "executable:javax.net.ssl.SSLContext#getDefault()" (raw "global::DripSharp.Runtime.JavaSslContext.GetDefault()") "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)" (sequence-node [(raw "global::DripSharp.Runtime.JavaSslContext.GetInstance(") (sequence-node arguments ", ") (raw ")")]) "executable:javax.net.ssl.SSLSocketFactory#getDefault()" (raw "global::DripSharp.Runtime.JavaSocketFactory.Default") "executable:javax.net.ssl.SSLContext#getSocketFactory()" (sequence-node [target-node (raw ".GetSocketFactory()")]) "executable:javax.net.ssl.SSLContext#getServerSocketFactory()" (sequence-node [target-node (raw ".GetServerSocketFactory()")]) "executable:java.lang.String#lines()" (compat-call "StringLines" [target-node]) "executable:java.lang.String#strip()" (sequence-node [target-node (raw ".Trim()")]) "executable:java.util.stream.Stream#skip(long)" (compat-call "DropValues" (into [target-node] arguments)) "executable:java.util.Objects#requireNonNullElseGet(java.lang.Object,java.util.function.Supplier)" (compat-call "RequireNonNullElseGet" arguments) "executable:java.util.Random#nextLong()" (sequence-node [target-node (raw ".NextLong()")]) "executable:java.util.concurrent.CompletableFuture#complete(java.lang.Object)" (sequence-node [target-node (raw ".Complete(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.CompletableFuture#completeExceptionally(java.lang.Throwable)" (sequence-node [target-node (raw ".CompleteExceptionally(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.concurrent.Future#get()" (sequence-node [target-node (raw ".Get()")]) "executable:java.lang.StringBuilder#append(char[])" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StringBuilder#append(char[],int,int)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.StringBuilder#append(java.lang.CharSequence)" (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")]) "executable:java.lang.Long#byteValue()" (sequence-node [(raw "unchecked((sbyte)(") target-node (raw "))")]) "executable:java.lang.Long#shortValue()" (sequence-node [(raw "unchecked((short)(") target-node (raw "))")]) "executable:java.lang.Long#intValue()" (sequence-node [(raw "unchecked((int)(") target-node (raw "))")]) "executable:java.lang.Float#doubleValue()" (sequence-node [(raw "(double)(") target-node (raw ")")]) "executable:java.net.URI#getAuthority()" (sequence-node [(compat-call "UriAuthority" [target-node]) (raw "!")]) "executable:java.net.URI#getFragment()" (sequence-node [(compat-call "UriFragment" [target-node]) (raw "!")]) "executable:java.net.URI#getQuery()" (sequence-node [(compat-call "UriQuery" [target-node]) (raw "!")]) "executable:java.net.URI#getSchemeSpecificPart()" (sequence-node [(compat-call "UriSchemeSpecificPart" [target-node]) (raw "!")]) "executable:java.net.URI#getRawAuthority()" (sequence-node [(compat-call "UriRawAuthority" [target-node]) (raw "!")]) "executable:java.net.URI#getRawSchemeSpecificPart()" (sequence-node [(compat-call "UriRawSchemeSpecificPart" [target-node]) (raw "!")]) "executable:java.net.URI#getRawUserInfo()" (sequence-node [(compat-call "UriRawUserInfo" [target-node]) (raw "!")]) "executable:java.net.URI#isAbsolute()" (csharp/member target-node "IsAbsoluteUri") "executable:java.net.URI#isOpaque()" (compat-call "UriIsOpaque" [target-node]) "executable:java.net.URI#normalize()" (compat-call "NormalizeUri" [target-node]) "executable:java.net.URI#relativize(java.net.URI)" (compat-call "RelativizeUri" (into [target-node] arguments)) ("executable:java.net.URI#resolve(java.lang.String)" "executable:java.net.URI#resolve(java.net.URI)") (compat-call "ResolveUri" (into [target-node] arguments)) "executable:java.net.URI#toASCIIString()" (csharp/member target-node "AbsoluteUri") "executable:java.net.URI#toURL()" target-node "executable:java.net.URI#compareTo(java.net.URI)" (compat-call "CompareUri" (into [target-node] arguments)) "executable:java.net.URL#toURI()" target-node "executable:java.net.URL#getProtocol()" (compat-call "UriScheme" [target-node]) "executable:java.net.URLConnection#connect()" (csharp/invocation (csharp/member target-node "Connect") arguments) "executable:java.net.URLConnection#setUseCaches(boolean)" (csharp/invocation (csharp/member target-node "SetUseCaches") arguments) "executable:java.net.URLConnection#getInputStream()" (csharp/invocation (csharp/member target-node "GetInputStream") arguments) "executable:java.net.URLConnection#getURL()" (csharp/invocation (csharp/member target-node "GetURL") arguments) "executable:java.lang.invoke.VarHandle#storeStoreFence()" (csharp/invocation (raw "global::System.Threading.Thread.MemoryBarrier") []) "executable:java.nio.file.Path#toString()" (sequence-node [target-node (raw ".ToString()!")]) "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])" (compat-call "PathOf" arguments) "executable:java.nio.file.Path#of(java.net.URI)" (compat-call "PathOfUri" arguments) "executable:java.nio.file.Path#getRoot()" (compat-call "PathRoot" [target-node]) "executable:java.nio.file.Path#isAbsolute()" (compat-call "PathIsAbsolute" [target-node]) "executable:java.nio.file.Path#normalize()" (compat-call "NormalizePath" [target-node]) "executable:java.nio.file.Path#startsWith(java.nio.file.Path)" (compat-call "PathStartsWith" (into [target-node] arguments)) ("executable:java.nio.file.Path#endsWith(java.lang.String)" "executable:java.nio.file.Path#endsWith(java.nio.file.Path)") (compat-call "PathEndsWith" (into [target-node] arguments)) "executable:java.nio.file.Path#getFileName()" (csharp/invocation (raw "global::System.IO.Path.GetFileName") [target-node]) "executable:java.nio.file.Path#getParent()" (compat-call "PathParent" [target-node]) "executable:java.nio.file.Path#resolveSibling(java.lang.String)" (compat-call "PathResolveSibling" (into [target-node] arguments)) "executable:java.nio.file.Path#getNameCount()" (compat-call "PathNameCount" [target-node]) "executable:java.nio.file.Path#getName(int)" (compat-call "PathName" (into [target-node] arguments)) "executable:java.nio.file.Path#relativize(java.nio.file.Path)" (compat-call "PathRelativize" (into [target-node] arguments)) ("executable:java.nio.file.Path#resolve(java.lang.String)" "executable:java.nio.file.Path#resolve(java.nio.file.Path)") (compat-call "PathResolve" (into [target-node] arguments)) "executable:java.nio.file.Path#toAbsolutePath()" (csharp/invocation (raw "global::System.IO.Path.GetFullPath") [target-node]) "executable:java.nio.file.Path#toRealPath(java.nio.file.LinkOption[])" (compat-call "RealPath" [target-node]) "executable:java.nio.file.Path#toUri()" (compat-call "PathToUri" [target-node]) "executable:java.nio.file.Files#exists(java.nio.file.Path,java.nio.file.LinkOption[])" (compat-call "Exists" [(first arguments)]) "executable:java.nio.file.Files#createDirectories(java.nio.file.Path,java.nio.file.attribute.FileAttribute[])" (compat-call "CreateDirectories" [(first arguments)]) "executable:java.nio.file.Files#newOutputStream(java.nio.file.Path,java.nio.file.OpenOption[])" (compat-call "NewOutputStream" arguments) "executable:java.nio.file.Files#deleteIfExists(java.nio.file.Path)" (compat-call "DeleteIfExists" arguments) ("executable:java.nio.file.Files#readString(java.nio.file.Path)" "executable:java.nio.file.Files#readString(java.nio.file.Path,java.nio.charset.Charset)") (compat-call "ReadString" arguments) ("executable:java.nio.file.Files#copy(java.io.InputStream,java.nio.file.Path,java.nio.file.CopyOption[])" "executable:java.nio.file.Files#copy(java.nio.file.Path,java.io.OutputStream)" "executable:java.nio.file.Files#copy(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])") (compat-call "Copy" arguments) "executable:java.nio.file.Files#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])" (compat-call "Move" arguments) "executable:java.nio.file.Files#writeString(java.nio.file.Path,java.lang.CharSequence,java.nio.file.OpenOption[])" (compat-call "WriteString" arguments) "executable:java.nio.file.Files#isSymbolicLink(java.nio.file.Path)" (compat-call "IsSymbolicLink" arguments) "executable:java.nio.file.Files#newDirectoryStream(java.nio.file.Path)" (compat-call "NewDirectoryStream" arguments) "executable:java.nio.file.Files#isDirectory(java.nio.file.Path,java.nio.file.LinkOption[])" (compat-call "IsDirectory" [(first arguments)]) "executable:java.nio.file.Files#isRegularFile(java.nio.file.Path,java.nio.file.LinkOption[])" (compat-call "PathIsRegularFile" [(first arguments)]) "executable:java.nio.file.Files#list(java.nio.file.Path)" (compat-call "List" arguments) "executable:java.nio.file.FileSystem#getPath(java.lang.String,java.lang.String[])" (csharp/invocation (csharp/member target-node "GetPath") arguments) "executable:java.nio.file.FileSystem#provider()" (csharp/invocation (csharp/member target-node "Provider") arguments) "executable:java.nio.file.FileSystem#isOpen()" (csharp/invocation (csharp/member target-node "IsOpen") arguments) "executable:java.nio.file.FileSystem#isReadOnly()" (csharp/invocation (csharp/member target-node "IsReadOnly") arguments) "executable:java.nio.file.FileSystem#getSeparator()" (csharp/invocation (csharp/member target-node "GetSeparator") arguments) "executable:java.nio.file.FileSystem#getFileStores()" (csharp/invocation (csharp/member target-node "GetFileStores") arguments) "executable:java.nio.file.FileSystem#supportedFileAttributeViews()" (csharp/invocation (csharp/member target-node "SupportedFileAttributeViews") arguments) "executable:java.nio.file.FileSystem#getPathMatcher(java.lang.String)" (csharp/invocation (csharp/member target-node "GetPathMatcher") arguments) "executable:java.nio.file.FileSystem#getUserPrincipalLookupService()" (csharp/invocation (csharp/member target-node "GetUserPrincipalLookupService") arguments) "executable:java.nio.file.FileSystem#newWatchService()" (csharp/invocation (csharp/member target-node "NewWatchService") arguments) "executable:java.nio.file.FileSystem#close()" (csharp/invocation (csharp/member target-node "Close") arguments) "executable:java.nio.file.FileSystems#getDefault()" (csharp/invocation (csharp/member target-node "GetDefault") arguments) "executable:java.nio.file.FileSystems#getFileSystem(java.net.URI)" (csharp/invocation (csharp/member target-node "GetFileSystem") arguments) "executable:java.nio.file.FileSystems#newFileSystem(java.net.URI,java.util.Map)" (csharp/invocation (csharp/member target-node "NewFileSystem") arguments) "executable:java.util.EnumSet#noneOf(java.lang.Class)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetNoneOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) "executable:java.util.EnumSet#allOf(java.lang.Class)" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetAllOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.EnumSet#copyOf(java.util.EnumSet)" "executable:java.util.EnumSet#copyOf(java.util.Collection)") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.EnumSetCopyOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)" "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum[])") (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.SetOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) ("executable:java.net.http.HttpRequest#newBuilder()" "executable:java.net.http.HttpRequest#newBuilder(java.net.URI)") (csharp/invocation (csharp/member target-node "NewBuilder") arguments) "executable:java.net.http.HttpRequest#uri()" (csharp/invocation (csharp/member target-node "Uri") arguments) "executable:java.net.http.HttpRequest#headers()" (csharp/invocation (csharp/member target-node "Headers") arguments) "executable:java.net.http.HttpRequest#expectContinue()" (csharp/invocation (csharp/member target-node "ExpectContinue") arguments) "executable:java.net.http.HttpRequest#method()" (csharp/invocation (csharp/member target-node "Method") arguments) "executable:java.net.http.HttpRequest#timeout()" (csharp/invocation (csharp/member target-node "Timeout") arguments) "executable:java.net.http.HttpRequest#version()" (csharp/invocation (csharp/member target-node "Version") arguments) "executable:java.net.http.HttpRequest#bodyPublisher()" (csharp/invocation (csharp/member target-node "BodyPublisher") arguments) "executable:java.net.http.HttpRequest$Builder#uri(java.net.URI)" (csharp/invocation (csharp/member target-node "Uri") arguments) "executable:java.net.http.HttpRequest$Builder#timeout(java.time.Duration)" (csharp/invocation (csharp/member target-node "Timeout") arguments) "executable:java.net.http.HttpRequest$Builder#version(java.net.http.HttpClient$Version)" (csharp/invocation (csharp/member target-node "Version") arguments) "executable:java.net.http.HttpRequest$Builder#header(java.lang.String,java.lang.String)" (csharp/invocation (csharp/member target-node "Header") arguments) "executable:java.net.http.HttpRequest$Builder#setHeader(java.lang.String,java.lang.String)" (csharp/invocation (csharp/member target-node "SetHeader") arguments) "executable:java.net.http.HttpRequest$Builder#expectContinue(boolean)" (csharp/invocation (csharp/member target-node "ExpectContinue") arguments) "executable:java.net.http.HttpRequest$Builder#method(java.lang.String,java.net.http.HttpRequest$BodyPublisher)" (csharp/invocation (csharp/member target-node "Method") arguments) "executable:java.net.http.HttpRequest$Builder#GET()" (csharp/invocation (csharp/member target-node "GET") arguments) "executable:java.net.http.HttpRequest$Builder#DELETE()" (csharp/invocation (csharp/member target-node "DELETE") arguments) "executable:java.net.http.HttpRequest$Builder#build()" (csharp/invocation (csharp/member target-node "Build") arguments) "executable:java.net.http.HttpRequest$BodyPublishers#noBody()" (csharp/invocation (csharp/member target-node "NoBody") arguments) "executable:java.net.http.HttpResponse#statusCode()" (csharp/invocation (csharp/member target-node "StatusCode") arguments) "executable:java.net.http.HttpResponse#body()" (csharp/invocation (csharp/member target-node "Body") arguments) "executable:java.net.http.HttpResponse#request()" (csharp/invocation (csharp/member target-node "Request") arguments) "executable:java.net.http.HttpResponse#previousResponse()" (csharp/invocation (csharp/member target-node "PreviousResponse") arguments) "executable:java.net.http.HttpResponse#uri()" (csharp/invocation (csharp/member target-node "Uri") arguments) "executable:java.net.http.HttpResponse#headers()" (csharp/invocation (csharp/member target-node "Headers") arguments) "executable:java.net.http.HttpResponse#version()" (csharp/invocation (csharp/member target-node "Version") arguments) "executable:java.net.http.HttpHeaders#firstValue(java.lang.String)" (csharp/invocation (csharp/member target-node "FirstValue") arguments) "executable:java.net.http.HttpHeaders#map()" (csharp/invocation (csharp/member target-node "Map") arguments) "executable:java.net.http.HttpResponse$BodyHandlers#ofInputStream()" (csharp/invocation (csharp/member target-node "OfInputStream") arguments) "executable:java.net.http.HttpResponse$BodyHandlers#ofByteArray()" (csharp/invocation (csharp/member target-node "OfByteArray") arguments) "executable:java.util.zip.ZipInputStream#getNextEntry()" (csharp/invocation (csharp/member target-node "GetNextEntry") arguments) "executable:java.util.zip.ZipInputStream#readAllBytes()" (csharp/invocation (csharp/member target-node "ReadAllBytes") arguments) "executable:java.util.zip.ZipInputStream#closeEntry()" (csharp/invocation (csharp/member target-node "CloseEntry") arguments) "executable:java.util.zip.ZipOutputStream#putNextEntry(java.util.zip.ZipEntry)" (csharp/invocation (csharp/member target-node "PutNextEntry") arguments) "executable:java.util.zip.ZipOutputStream#closeEntry()" (csharp/invocation (csharp/member target-node "CloseEntry") arguments) "executable:java.util.zip.ZipEntry#getName()" (csharp/invocation (csharp/member target-node "GetName") arguments) "executable:java.util.zip.ZipEntry#isDirectory()" (csharp/invocation (csharp/member target-node "IsDirectory") arguments) "executable:java.util.zip.ZipEntry#setTimeLocal(java.time.LocalDateTime)" (csharp/invocation (csharp/member target-node "SetTimeLocal") arguments) "executable:java.util.Arrays#stream(java.lang.Object[])" (sequence-node [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.StreamOf") [(type-node ctx (collection-element-type element))]) (raw "(") (sequence-node arguments ", ") (raw ")")]) nil)))

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

(defn- substituted-executable-type-reference
  [^CtTypeReference reference substitutions]
  (if (instance? CtTypeParameterReference reference)
    (get substitutions (.getSimpleName reference) reference)
    reference))

(defn- reference-contains-type-parameter?
  [^CtTypeReference reference]
  (or (instance? CtTypeParameterReference reference)
      (some reference-contains-type-parameter?
            (.getActualTypeArguments reference))))

(defn- executable-parameter-types
  ([declaration executable-reference]
   (executable-parameter-types declaration executable-reference nil))
  ([declaration executable-reference target-reference]
   (if (instance? CtExecutable declaration)
     (let [parameters
           (mapv #(.getType ^CtParameter %)
                 (.getParameters ^CtExecutable declaration))
           resolved-parameters (vec (.getParameters executable-reference))
           method-formals
           (vec (.getFormalCtTypeParameters ^CtExecutable declaration))
           method-actuals (vec (.getActualTypeArguments executable-reference))
           owner (some-> ^CtExecutable declaration .getDeclaringType)
           owner-formals (when owner
                           (vec (.getFormalCtTypeParameters ^CtType owner)))
           owner-actuals
           (when target-reference
             (let [actuals (vec (.getActualTypeArguments
                                 ^CtTypeReference target-reference))]
               (if (and (empty? actuals) (seq owner-formals))
                 (mapv raw-project-type-argument-reference owner-formals)
                 actuals)))
           owner-substitutions
           (when (= (count owner-formals) (count owner-actuals))
             (zipmap (map #(.getSimpleName ^CtTypeParameter %) owner-formals)
                     owner-actuals))
           method-substitutions
           (when (= (count method-formals) (count method-actuals))
             (zipmap (map #(.getSimpleName ^CtTypeParameter %) method-formals)
                     method-actuals))
           substitutions (merge owner-substitutions method-substitutions)]
       (mapv
        (fn [^CtTypeReference parameter ^CtTypeReference resolved]
          (let [substituted
                (substituted-executable-type-reference parameter substitutions)]
            (if (and (identical? substituted parameter)
                     resolved
                     (= (.getQualifiedName parameter)
                        (.getQualifiedName resolved))
                     (seq (.getActualTypeArguments resolved))
                     (not (reference-contains-type-parameter? resolved)))
              resolved
              substituted)))
        parameters
        resolved-parameters))
     (vec (.getParameters executable-reference)))))

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
        (let [actuals (vec (.getActualTypeArguments (.getType call)))]
          (if (and (empty? actuals) (seq formals))
            (mapv raw-project-type-argument-reference formals)
            actuals))
        substitutions
        (when (= (count formals) (count actuals))
          (zipmap (map #(.getSimpleName ^CtTypeParameter %) formals)
                  actuals))
        formal-names (set (map #(.getSimpleName ^CtTypeParameter %) formals))
        inferred-substitutions
        (letfn [(infer [bindings ^CtTypeReference parameter
                        ^CtTypeReference argument]
                  (cond
                    (or (nil? parameter) (nil? argument))
                    bindings

                    (and (instance? CtTypeParameterReference parameter)
                         (contains? formal-names (.getSimpleName parameter)))
                    (assoc bindings (.getSimpleName parameter) argument)

                    (= (.getQualifiedName parameter)
                       (.getQualifiedName argument))
                    (reduce
                     (fn [current [nested-parameter nested-argument]]
                       (infer current nested-parameter nested-argument))
                     bindings
                     (map vector
                          (.getActualTypeArguments parameter)
                          (.getActualTypeArguments argument)))

                    :else bindings))]
          (reduce
           (fn [bindings [^CtTypeReference parameter ^CtExpression argument]]
             (infer bindings parameter (.getType argument)))
           {}
           (map vector parameter-types (.getArguments call))))
        ;; Explicit owner arguments are authoritative. Inference fills raw
        ;; calls only; a null actual must not replace `MutableReference<T>`'s
        ;; declared T with Spoon's null/Object marker.
        substitutions (merge inferred-substitutions substitutions)]
    (mapv
     (fn [^CtTypeReference reference]
       (substituted-executable-type-reference reference substitutions))
     parameter-types)))

(defn- call-parameter-type
  "Returns the Java parameter type governing one call argument. For expanded
  varargs calls, the component type governs each trailing argument; treating
  every trailing value as the array type produces invalid CLR null and generic
  conversions."
  [declaration parameter-types argument-count index ^CtExpression argument]
  (when (seq parameter-types)
    (let [parameter-index (min index (dec (count parameter-types)))
          expected (nth parameter-types parameter-index)
          parameters (when (instance? CtExecutable declaration)
                       (vec (.getParameters ^CtExecutable declaration)))
          ^CtParameter last-parameter (last parameters)
          expanded-varargs?
          (and last-parameter
               (.isVarArgs last-parameter)
               (= parameter-index (dec (count parameter-types)))
               (instance? CtArrayTypeReference expected)
               (not (instance? CtTypeParameterReference
                               (.getComponentType
                                ^CtArrayTypeReference expected)))
               (or (> argument-count (count parameter-types))
                   (not (instance? CtArrayTypeReference (.getType argument)))))]
      (if expanded-varargs?
        (.getComponentType ^CtArrayTypeReference expected)
        expected))))

(defn- declared-call-parameter-type
  "Returns the original declaration-owned parameter reference for destination
  adapters. Unlike the substituted call parameter, this reference retains its
  declaration parent and therefore any target-owned public-boundary policy."
  [declaration argument-count index ^CtExpression argument]
  (when (instance? CtExecutable declaration)
    (call-parameter-type
     declaration
     (mapv #(.getType ^CtParameter %)
           (.getParameters ^CtExecutable declaration))
     argument-count index argument)))

(defn- invocation-target-node
  [ctx children ^CtExpression target declaration executable-reference]
  (let [static-declaration?
        (and (instance? CtMethod declaration)
             (.hasModifier ^CtMethod declaration ModifierKind/STATIC))
        default-interface-method?
        (and (instance? CtMethod declaration)
             (some? (.getBody ^CtMethod declaration))
             (interface-type? (.getDeclaringType ^CtMethod declaration)))
        interface-self-target?
        (and default-interface-method?
             (instance? CtThisAccess target)
             (or
              (= :direct (:default-interface-self-dispatch ctx))
              (and (not (:inlined-default-interface-body? ctx))
                   (= (some-> target .getType .getQualifiedName)
                      (some-> ^CtMethod declaration
                              .getDeclaringType
                              .getQualifiedName)))))
        node
        (cond
          static-declaration?
          (type-node
           ctx
           (.getDeclaringType executable-reference))

          (instance? CtTypeAccess target)
          (type-node ctx (.getAccessedType ^CtTypeAccess target))

          (and default-interface-method? (not interface-self-target?))
          (sequence-node
           [(raw "((")
            (type-node ctx (.getDeclaringType executable-reference))
            (raw ")(")
            (child-node children target)
            (raw "))")])

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

(defn-
  executable-mapping-handlers
  [ctx-holder]
  {:java-library.mapping/supplemental-collection-factory
   (fn
     [{:keys [element occurrence target-node arguments]}]
     {:node
      (supplemental-neutral-invocation-node
       @ctx-holder element (:key occurrence) target-node arguments)})
   :java-library.mapping/enum-value-of
   (fn
     [{:keys [arguments element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.EnumValueOf")
         [(type-node @ctx-holder (.getType element))])
        (raw "(")
        (second arguments)
        (raw ")")])})
   :java-library.mapping/array-list-spliterator
   (fn [{:keys [target-node]}] {:node target-node})
   :java-library.mapping/string-index-of-from
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node
        (raw ".IndexOf(")
        (first arguments)
        (raw ", ")
        (second arguments)
        (raw ", global::System.StringComparison.Ordinal)")])})
   :java-library.mapping.executable/handler-0493
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringBuilderAppendInvariant" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0014
   (fn [{:keys [target-node]}] {:node (compat-call "ResetMemoryStream" [target-node])}),
   :java-library.mapping.executable/handler-0375
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringStartsWith" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0359
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".OriginalString")])}),
   :java-library.mapping.executable/handler-0422
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SocketSetSoTimeout" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0618
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CollectionRemove" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0440
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Init(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0183
   (fn [{:keys [arguments]}] {:node (sequence-node [(first arguments) (raw ".NumberFormat")])}),
   :java-library.mapping.executable/handler-0669
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".SetName(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0051
   (fn [{:keys [arguments]}] {:node (compat-call "ParseBoolean" arguments)}),
   :java-library.mapping.executable/handler-0372
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Length")])}),
   :java-library.mapping.executable/handler-0277
   (fn [{:keys [arguments]}] {:node (compat-call "GetTimeZone" arguments)}),
   :java-library.mapping.executable/handler-0010
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".SetInput(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0485
   (fn [{:keys [target-node]}] {:node (compat-call "InputStreamRead" [target-node])}),
   :java-library.mapping.executable/handler-0610
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".NextIndex()")])}),
   :java-library.mapping.executable/handler-0073
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCompat.ClassGetAnnotation<")
        (type-node @ctx-holder (.getType element))
        (raw ">(")
        target-node
        (raw ", ")
        (sequence-node arguments ", ")
        (raw ")!")])}),
   :java-library.mapping.executable/handler-0055
   (fn [{:keys [arguments]}] {:node (compat-call "ToUnsignedLong" arguments)}),
   :java-library.mapping.executable/handler-0567
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Clear()")])}),
   :java-library.mapping.executable/handler-0239
   (fn [{:keys [arguments]}] {:node (compat-call "NewSetFromMap" arguments)}),
   :java-library.mapping.executable/handler-0457
   (fn [{:keys [target-node]}] {:node (compat-call "FileIsDirectory" [target-node])}),
   :java-library.mapping.executable/handler-0594
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "RemoveIf" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0180
   (fn [{:keys [arguments]}] {:node (compat-call "DeepArrayString" arguments)}),
   :java-library.mapping.executable/handler-0491
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0569
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapPutAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0398
   (fn [{:keys [arguments]}] {:node (compat-call "StringValueOf" arguments)}),
   :java-library.mapping.executable/handler-0108
   (fn
     [{:keys [element children]}]
     {:node
      (let
       [argument
        (first (.getArguments element))
        argument-node
        (maybe-unbox-node @ctx-holder argument (child-node children argument))]
        (sequence-node
         [(raw "global::System.Math.Ceiling(")
          (raw "(double)(")
          argument-node
          (raw ")")
          (raw ")")]))}),
   :java-library.mapping.executable/handler-0568
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapPut" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0353
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(compat-call "UriRawPath" [target-node]) (raw "!")])}),
   :java-library.mapping.executable/handler-0193
   (fn
     [{:keys [target-node]}]
     {:node (value-type-mutation-node target-node (compat-call "CalendarClear" [target-node]))}),
   :java-library.mapping.executable/handler-0166
   (fn [{:keys [target-node]}] {:node (compat-call "CharsetName" [target-node])}),
   :java-library.mapping.executable/handler-0292
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlReaderSetNamespaceAware" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0553
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapContainsKey" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0461
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0651
   (fn [{:keys [arguments]}] {:node (compat-call "ToCollection" arguments)}),
   :java-library.mapping.executable/handler-0072
   (fn [{:keys [arguments]}] {:node (compat-call "DoubleHashCode" arguments)}),
   :java-library.mapping.executable/handler-0442
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".DoFinal(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0418
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".RemoteEndPoint")])}),
   :java-library.mapping.executable/handler-0258
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Count")])}),
   :java-library.mapping.executable/handler-0218
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Poll()")])}),
   :java-library.mapping.executable/handler-0287
   (fn [{:keys [target-node]}] {:node (compat-call "XmlReaderSettingsClone" [target-node])}),
   :java-library.mapping.executable/handler-0423
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Accept()")])}),
   :java-library.mapping.executable/handler-0496
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Length")])}),
   :java-library.mapping.executable/handler-0109
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::System.Numerics.BigInteger(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0062
   (fn [{:keys [arguments]}] {:node (compat-call "IsBmpCodePoint" arguments)}),
   :java-library.mapping.executable/handler-0192
   (fn [{:keys [arguments]}] {:node (compat-call "CalendarInstance" arguments)}),
   :java-library.mapping.executable/handler-0064
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node [(raw "char.IsSurrogatePair(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0246
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListAdd" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0565
   (fn [{:keys [target-node]}] {:node (compat-call "MapKeySet" [target-node])}),
   :java-library.mapping.executable/handler-0602
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListRemove" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0330
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".InnerText = ") (first arguments)])}),
   :java-library.mapping.executable/handler-0316
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Value")])}),
   :java-library.mapping.executable/handler-0524
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "checked((long)") target-node (raw ".TotalMilliseconds)")])}),
   :java-library.mapping.executable/handler-0086
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "double.IsInfinity(") target-node (raw ")")])}),
   :java-library.mapping.executable/handler-0592
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CollectionRemove" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0650
   (fn
     [{:keys [element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.ToSet")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "()")])}),
   :java-library.mapping.executable/handler-0487
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Unread(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0293
   (fn [_] {:node (raw "new global::System.Xml.XmlDocument()")}),
   :java-library.mapping.executable/handler-0260
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".AddAll(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0219
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".GetProperty(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0232
   (fn [{:keys [arguments]}] {:node (compat-call "Reverse" arguments)}),
   :java-library.mapping.executable/handler-0320
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node
        (raw ".CreateProcessingInstruction(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0371
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringSplit" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0433
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Update(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0526
   (fn [{:keys [target-node]}] {:node (compat-call "DurationGetNano" [target-node])}),
   :java-library.mapping.executable/handler-0250
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListRemove" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0088
   (fn
     [{:keys [arguments]}]
     {:node (sequence-node [(raw "double.IsNaN(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0555
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (compat-call
       "ComputeIfAbsent"
       (into
        [target-node]
        (unbox-nullable-boxed-invocation-arguments
         @ctx-holder element arguments #{0})))}),
   :java-library.mapping.executable/handler-0508
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".IsAssignableFrom(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0391
   (fn [{:keys [target-node]}] {:node (compat-call "StringValueOf" [target-node])}),
   :java-library.mapping.executable/handler-0015
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MemoryStreamToString" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0185
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Format(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0198
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (value-type-mutation-node
       target-node
       (compat-call "CalendarSet" (into [target-node] arguments)))}),
   :java-library.mapping.executable/handler-0424
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Close()")])}),
   :java-library.mapping.executable/handler-0624
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw "()")])}),
   :java-library.mapping.executable/handler-0455
   (fn [{:keys [target-node]}] {:node (compat-call "FileExists" [target-node])}),
   :java-library.mapping.executable/handler-0077
   (fn [{:keys [arguments]}] {:node (compat-call "ParseDouble" arguments)}),
   :java-library.mapping.executable/handler-0397
   (fn [{:keys [target-node]}] {:node (compat-call "StringValueOf" [target-node])}),
   :java-library.mapping.executable/handler-0522
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "PrintStackTrace" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0138
   (fn [{:keys [arguments]}] {:node (compat-call "JavaStringFormat" arguments)}),
   :java-library.mapping.executable/handler-0268
   (fn [{:keys [target-node]}] {:node (compat-call "MapEntrySet" [target-node])}),
   :java-library.mapping.executable/handler-0474
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0638
   (fn [{:keys [arguments]}] {:node (compat-call "StreamOf" arguments)}),
   :java-library.mapping.executable/handler-0470
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "OutputStreamWrite" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0446
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Flush()")])}),
   :java-library.mapping.executable/handler-0479
   (fn [{:keys [target-node]}] {:node (compat-call "ReadAllBytes" [target-node])}),
   :java-library.mapping.executable/handler-0653
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Submit(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0503
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "GetDeclaredField" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0605
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListIterator" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0143
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringReplaceFirst" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0533
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::System.DateTime(")
        (sequence-node (conj arguments (raw "0")) ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0438
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCipher.GetInstance(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0523
   (fn [{:keys [arguments]}] {:node (compat-call "DurationOfSeconds" arguments)}),
   :java-library.mapping.executable/handler-0333
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Data")])}),
   :java-library.mapping.executable/handler-0186
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Format" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0454
   (fn [{:keys [target-node]}] {:node (compat-call "FileDelete" [target-node])}),
   :java-library.mapping.executable/handler-0284
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Namespace")])}),
   :java-library.mapping.executable/handler-0483
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Skip(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0058
   (fn [{:keys [arguments]}] {:node (compat-call "CharacterName" arguments)}),
   :java-library.mapping.executable/handler-0335
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".NewXPath()")])}),
   :java-library.mapping.executable/handler-0056
   (fn [{:keys [arguments]}] {:node (compat-call "CharacterDigit" arguments)}),
   :java-library.mapping.executable/handler-0139
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node
        (raw ".IndexOf(")
        (first arguments)
        (raw ", global::System.StringComparison.Ordinal)")])}),
   :java-library.mapping.executable/handler-0528
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0619
   (fn [{:keys [target-node]}] {:node (compat-call "ToObjectArray" [target-node])}),
   :java-library.mapping.executable/handler-0520
   (fn [{:keys [target-node]}] {:node (compat-call "UriSyntaxReason" [target-node])}),
   :java-library.mapping.executable/handler-0007
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".End()")])}),
   :java-library.mapping.executable/handler-0006
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0211
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".IsEmpty()")])}),
   :java-library.mapping.executable/handler-0112
   (fn [{:keys [target-node]}] {:node (sequence-node [(raw "(~") target-node (raw ")")])}),
   :java-library.mapping.executable/handler-0396
   (fn [{:keys [arguments]}] {:node (compat-call "ParseLong" arguments)}),
   :java-library.mapping.executable/handler-0321
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".DocumentElement!")])}),
   :java-library.mapping.executable/handler-0194
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CalendarCompareTo"
                         [target-node (first arguments)])}),
   :java-library.mapping.executable/handler-0449
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0063
   (fn [{:keys [arguments]}] {:node (compat-call "IsValidCodePoint" arguments)}),
   :java-library.mapping.executable/handler-0122
   (fn
     [{:keys [target-node]}]
     {:node
      (sequence-node
       [(raw "global::System.Convert.ToInt64(")
        target-node
        (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")])}),
   :java-library.mapping.executable/handler-0626
   (fn
     [{:keys [arguments element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.EnumSetOf")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0092
   (fn
     [{:keys [target-node arguments element], target :target-element}]
     {:node
      (let
       [value-target
        (maybe-unbox-node @ctx-holder target target-node)
        value-arguments
        (mapv
         (fn [argument node] (maybe-unbox-node @ctx-holder argument node))
         (.getArguments element)
         arguments)]
        (sequence-node
         [value-target (raw ".CompareTo(") (sequence-node value-arguments ", ") (raw ")")]))}),
   :java-library.mapping.executable/handler-0554
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapContainsValue" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0290
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlReaderSetExpandEntityReferences" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0188
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaDecimalFormat.GetNumberInstance(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0262
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".SubList(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0214
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Clear()")])}),
   :java-library.mapping.executable/handler-0465
   (fn [{:keys [target-node]}] {:node (compat-call "ToSignedBytes" [target-node])}),
   :java-library.mapping.executable/handler-0513
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "InitCause" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0368
   (fn [{:keys [arguments]}] {:node (compat-call "JavaStringFormat" arguments)}),
   :java-library.mapping.executable/handler-0281
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "TimeZoneOffset" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0358
   (fn [{:keys [target-node]}] {:node (compat-call "HashCode" [target-node])}),
   :java-library.mapping.executable/handler-0542
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToFormatter()")])}),
   :java-library.mapping.executable/handler-0298
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlSetOutputProperty" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0498
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (compat-call
       "StringSubstring"
       (into [(sequence-node [target-node (raw ".ToString()")])] arguments))}),
   :java-library.mapping.executable/handler-0314
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".AppendChild(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0081
   (fn [{:keys [arguments]}] {:node (compat-call "LongHashCode" arguments)}),
   :java-library.mapping.executable/handler-0585
   (fn [{:keys [target-node]}] {:node (compat-call "ListIsEmpty" [target-node])}),
   :java-library.mapping.executable/handler-0502
   (fn [{:keys [arguments]}] {:node (compat-call "ClassForName" arguments)}),
   :java-library.mapping.executable/handler-0535
   (fn [{:keys [arguments]}] {:node (compat-call "ZoneIdOf" arguments)}),
   :java-library.mapping.executable/handler-0127
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToString()")])}),
   :java-library.mapping.executable/handler-0162
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".NextBytes(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0616
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ForEachRemaining" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0620
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CollectionToArray" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0404
   (fn [_] {:node (raw "global::System.DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()")}),
   :java-library.mapping.executable/handler-0591
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "EnsureCapacity" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0453
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Length")])}),
   :java-library.mapping.executable/handler-0022
   (fn [{:keys [target-node]}] {:node (compat-call "FileCanRead" [target-node])}),
   :java-library.mapping.executable/handler-0126
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".GetHashCode()")])}),
   :java-library.mapping.executable/handler-0217
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Peek()")])}),
   :java-library.mapping.executable/handler-0344
   (fn [{:keys [arguments]}] {:node (compat-call "NCopies" arguments)}),
   :java-library.mapping.executable/handler-0659
   (fn [_] {:node (raw "new global::DripSharp.Runtime.JavaExecutorService(1)")}),
   :java-library.mapping.executable/handler-0172
   (fn [{:keys [arguments]}] {:node (compat-call "ReadAllBytes" arguments)}),
   :java-library.mapping.executable/handler-0543
   (fn [_] {:node (raw "global::System.Net.IPAddress.Loopback")}),
   :java-library.mapping.executable/handler-0445
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".CreateSocket(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0106
   (fn [{:keys [arguments]}] {:node (compat-call "ParseInt" (conj arguments (raw "10")))}),
   :java-library.mapping.executable/handler-0251
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListSet" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0420
   (fn [{:keys [target-node]}] {:node (compat-call "SocketIsClosed" [target-node])}),
   :java-library.mapping.executable/handler-0486
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Unread(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0271
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SortedSubMap" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0363
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToString()!")])}),
   :java-library.mapping.executable/handler-0648
   (fn [{:keys [target-node]}] {:node (compat-call "Sum" [target-node])}),
   :java-library.mapping.executable/handler-0038
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToString()")])}),
   :java-library.mapping.executable/handler-0144
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToLowerInvariant()")])}),
   :java-library.mapping.executable/handler-0020
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "WriterWriteCharCode" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0527
   (fn [_] {:node (raw "global::System.DateTimeOffset.UtcNow")}),
   :java-library.mapping.executable/handler-0178
   (fn [{:keys [arguments]}] {:node (compat-call "CopyOf" arguments)}),
   :java-library.mapping.executable/handler-0660
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Get(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0360
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::System.Uri(")
        (sequence-node arguments ", ")
        (raw ", global::System.UriKind.RelativeOrAbsolute)")])}),
   :java-library.mapping.executable/handler-0525
   (fn [{:keys [target-node]}] {:node (compat-call "DurationGetSeconds" [target-node])}),
   :java-library.mapping.executable/handler-0548
   (fn [{:keys [arguments]}] {:node (compat-call "Hash" arguments)}),
   :java-library.mapping.executable/handler-0673
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaThread.Sleep(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0557
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (compat-call
       "ComputeIfAbsent"
       (into
        [target-node]
        (unbox-nullable-boxed-invocation-arguments
         @ctx-holder element arguments #{0})))}),
   :java-library.mapping.executable/handler-0580
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Key")])}),
   :java-library.mapping.executable/handler-0347
   (fn [{:keys [arguments]}] {:node (compat-call "UnmodifiableList" arguments)}),
   :java-library.mapping.executable/handler-0389
   (fn
     [{:keys [target-node]}]
     {:node (compat-call "StringGetBytes" [target-node (raw "global::System.Text.Encoding.UTF8")])}),
   :java-library.mapping.executable/handler-0174
   (fn [{:keys [target-node]}] {:node (compat-call "IsRegularFile" [target-node])}),
   :java-library.mapping.executable/handler-0634
   (fn [{:keys [arguments]}] {:node (compat-call "CompileRegex" arguments)}),
   :java-library.mapping.executable/handler-0609
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Previous()")])}),
   :java-library.mapping.executable/handler-0140
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Replace(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0026
   (fn [{:keys [target-node]}] {:node (compat-call "FileIsHidden" [target-node])}),
   :java-library.mapping.executable/handler-0409
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Set(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0385
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0319
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".CreateElement(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0589
   (fn
     [{:keys [target-node arguments]}]
     {:node (csharp/invocation (csharp/member target-node "AddFirst") arguments)}),
   :java-library.mapping.executable/handler-0480
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ReadNBytes" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0566
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Values")])}),
   :java-library.mapping.executable/handler-0390
   (fn [{:keys [arguments]}] {:node (compat-call "StringJoin" arguments)}),
   :java-library.mapping.executable/handler-0278
   (fn [{:keys [target-node]}] {:node (compat-call "TimeZoneRawOffset" [target-node])}),
   :java-library.mapping.executable/handler-0003
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "OutputStreamWrite" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0427
   (fn [{:keys [target-node]}] {:node (compat-call "OpenUrlStream" [target-node])}),
   :java-library.mapping.executable/handler-0274
   (fn [{:keys [target-node]}] {:node (compat-call "SortedLast" [target-node])}),
   :java-library.mapping.executable/handler-0161
   (fn
     [{:keys [arguments]}]
     {:node (sequence-node [(raw "global::System.Environment.Exit(") (first arguments) (raw ")")])}),
   :java-library.mapping.executable/handler-0364 (fn [{:keys [target-node]}] {:node target-node}),
   :java-library.mapping.executable/handler-0460
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SetFileExecutable" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0400
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node [(raw "global::System.Math.Min(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0612
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0647
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapToLong" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0008
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Finished()")])}),
   :java-library.mapping.executable/handler-0545
   (fn [{:keys [target-node]}] {:node (compat-call "GetAddressBytes" [target-node])}),
   :java-library.mapping.executable/handler-0203
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (value-type-mutation-node
       target-node
       (compat-call "CalendarAdd" (into [target-node] arguments)))}),
   :java-library.mapping.executable/handler-0367
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Message")])}),
   :java-library.mapping.executable/handler-0666
   (fn
     [{:keys [element target-node arguments]}]
     {:node
      (sequence-node
       [target-node
        (raw ".GetAndSet(")
        (if (and
             (= 1 (count (.getArguments ^CtInvocation element)))
             (instance? CtLiteral (first (.getArguments ^CtInvocation element)))
             (nil? (.getValue
                    ^CtLiteral
                    (first (.getArguments ^CtInvocation element)))))
          (raw "default!")
          (sequence-node arguments ", "))
        (raw ")")])}),
   :java-library.mapping.executable/handler-0029
   (fn [{:keys [target-node]}] {:node (compat-call "FileListFiles" [target-node])}),
   :java-library.mapping.executable/handler-0256
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Peek()")])}),
   :java-library.mapping.executable/handler-0419
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Close()")])}),
   :java-library.mapping.executable/handler-0331
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Count")])}),
   :java-library.mapping.executable/handler-0338
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".GetValue()")])}),
   :java-library.mapping.executable/handler-0169
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Decode(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0642
   (fn [{:keys [target-node]}] {:node (compat-call "ToArray" [target-node])}),
   :java-library.mapping.executable/handler-0362
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToLowerInvariant()")])}),
   :java-library.mapping.executable/handler-0562
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapPutIfAbsent" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0646
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Map" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0510
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ClassGetResource" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0334
   (fn [_] {:node (raw "global::DripSharp.Runtime.JavaXPathFactory.Instance")}),
   :java-library.mapping.executable/handler-0245
   (fn
     [{:keys [arguments element]}]
     {:node
      (let
       [comparator-arguments
        (vec (.getActualTypeArguments (.getType element)))
        function-arguments
        (vec (.getActualTypeArguments (.getType (first (.getArguments element)))))]
        (if
         (and (= 1 (count comparator-arguments)) (= 2 (count function-arguments)))
          (sequence-node
           [(raw "global::DripSharp.Runtime.JavaCompat.ComparatorComparing<")
            (type-node @ctx-holder (first comparator-arguments))
            (raw ", ")
            (type-node @ctx-holder (second function-arguments))
            (raw ">(")
            (first arguments)
            (raw ")")])
          (compat-call "ComparatorComparing" arguments)))}),
   :java-library.mapping.executable/handler-0668
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".SetDaemon(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0613
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Compare(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0604
   (fn [{:keys [target-node]}] {:node (compat-call "ListIterator" [target-node])}),
   :java-library.mapping.executable/handler-0201
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (value-type-mutation-node
       target-node
       (compat-call "CalendarSetTimeZone" (into [target-node] arguments)))}),
   :java-library.mapping.executable/handler-0025
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "FileEquals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0322
   (fn [{:keys [target-node]}] {:node (compat-call "XmlInputEncoding" [target-node])}),
   :java-library.mapping.executable/handler-0117
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ToStringRadix" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0142
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringReplaceAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0534
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "LocalDateTimeAtZone" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0667
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Set(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0354
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(compat-call "UriPath" [target-node]) (raw "!")])}),
   :java-library.mapping.executable/handler-0593
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "RemoveIf" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0556
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (compat-call
       "ComputeIfAbsent"
       (into
        [target-node]
        (unbox-nullable-boxed-invocation-arguments
         @ctx-holder element arguments #{0})))}),
   :java-library.mapping.executable/handler-0572
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapPut" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0414
   (fn [{:keys [target-node]}] {:node (compat-call "EnumOrdinal" [target-node])}),
   :java-library.mapping.executable/handler-0339
   (fn
     [{:keys [element]}]
     {:node
      (sequence-node
       [(raw "global::System.Array.Empty<")
        (type-node @ctx-holder (collection-element-type element))
        (raw ">()")])}),
   :java-library.mapping.executable/handler-0637
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Matches()")])}),
   :java-library.mapping.executable/handler-0267
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Count")])}),
   :java-library.mapping.executable/handler-0627
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CollectionContains" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0299
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlTransform" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0345
   (fn [{:keys [arguments]}] {:node (first arguments)}),
   :java-library.mapping.executable/handler-0324
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".SetAttribute(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0402
   (fn [{:keys [arguments]}] {:node (compat-call "ToIntExact" arguments)}),
   :java-library.mapping.executable/handler-0294
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlParse" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0133
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw "[") (first arguments) (raw "]")])}),
   :java-library.mapping.executable/handler-0603
   (fn [{:keys [target-node]}] {:node (compat-call "Iterator" [target-node])}),
   :java-library.mapping.executable/handler-0401
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node [(raw "global::System.Math.Max(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0384
   (fn [{:keys [target-node]}] {:node (compat-call "HashCode" [target-node])}),
   :java-library.mapping.executable/handler-0175
   (fn [{:keys [arguments]}] {:node (compat-call "PathOf" arguments)}),
   :java-library.mapping.executable/handler-0340
   (fn
     [{:keys [element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.EmptyJavaIterator")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "()")])}),
   :java-library.mapping.executable/handler-0302
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".GetNamedItem(") (first arguments) (raw ")")])}),
   :java-library.mapping.executable/handler-0386
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "EqualsIgnoreCase" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0235
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Decode(") (first arguments) (raw ")")])}),
   :java-library.mapping.executable/handler-0521
   (fn [{:keys [target-node]}] {:node (compat-call "UriSyntaxIndex" [target-node])}),
   :java-library.mapping.executable/handler-0310
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Value")])}),
   :java-library.mapping.executable/handler-0066
   (fn [{:keys [arguments]}] {:node (compat-call "IsWhitespace" arguments)}),
   :java-library.mapping.executable/handler-0336
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Evaluate(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0628
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Contains(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0141
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ReplaceOrdinal" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0688
   (fn [{:keys [arguments]}] {:node (compat-call "Cbrt" arguments)}),
   :java-library.mapping.executable/handler-0689
   (fn [{:keys [arguments]}] {:node (compat-call "CopySign" arguments)}),
   :java-library.mapping.executable/handler-0394
   (fn [{:keys [arguments]}] {:node (compat-call "SumInt" arguments)}),
   :java-library.mapping.executable/handler-0672
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Interrupt()")])}),
   :java-library.mapping.executable/handler-0366
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw "[") (first arguments) (raw "]")])}),
   :java-library.mapping.executable/handler-0488
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Close()")])}),
   :java-library.mapping.executable/handler-0382
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringContains" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0504
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "GetMethod" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0541
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ParseStrict()")])}),
   :java-library.mapping.executable/handler-0229
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "RemoveAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0095
   (fn [{:keys [arguments]}] {:node (compat-call "LongLeadingZeros" arguments)}),
   :java-library.mapping.executable/handler-0428
   (fn [{:keys [arguments]}] {:node (compat-call "UrlDecode" arguments)}),
   :java-library.mapping.executable/handler-0093
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node [(raw "global::System.Math.Sign(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0588
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListAddFirst" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0516
   (fn [{:keys [target-node]}] {:node (compat-call "GetStackTrace" [target-node])}),
   :java-library.mapping.executable/handler-0089
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "double.IsNaN(") target-node (raw ")")])}),
   :java-library.mapping.executable/handler-0494
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Insert(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0459
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SetFileWritable" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0165
   (fn [{:keys [arguments]}] {:node (compat-call "CharsetForName" arguments)}),
   :java-library.mapping.executable/handler-0349
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(compat-call "UriHost" [target-node]) (raw "!")])}),
   :java-library.mapping.executable/handler-0559
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapGetOrDefault" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0416
   (fn [{:keys [target-node]}] {:node (compat-call "SocketStream" [target-node])}),
   :java-library.mapping.executable/handler-0171
   (fn [{:keys [arguments]}] {:node (compat-call "CharBufferWrap" arguments)}),
   :java-library.mapping.executable/handler-0164
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "(") target-node (raw ".Remaining > 0)")])}),
   :java-library.mapping.executable/handler-0560
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapMerge" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0160
   (fn [_] {:node (raw "global::System.Environment.NewLine")}),
   :java-library.mapping.executable/handler-0538
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0571
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapPutAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0065
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaBidi.IsMirrored(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0380
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringIndexOf" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0241
   (fn
     [{:keys [element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.NaturalOrder")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "()")])}),
   :java-library.mapping.executable/handler-0094
   (fn [{:keys [arguments]}] {:node (compat-call "IntLeadingZeros" arguments)}),
   :java-library.mapping.executable/handler-0530
   (fn [_] {:node (raw "global::System.DateTimeOffset.UtcNow")}),
   :java-library.mapping.executable/handler-0123
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "unchecked((short)") target-node (raw ")")])}),
   :java-library.mapping.executable/handler-0085
   (fn
     [{:keys [arguments]}]
     {:node (sequence-node [(raw "double.IsInfinity(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0617
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Remove()")])}),
   :java-library.mapping.executable/handler-0272
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SortedHeadSet" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0467
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "OutputStreamWrite" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0383
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringMatches" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0584
   (fn
     [{:keys [element]}]
     {:node
      (let
       [comparator-arguments
        (vec (.getActualTypeArguments (.getType element)))
        comparison
        (raw
         (str
          "(value0, value1) => "
          "global::DripSharp.Runtime.JavaCompat.CompareNatural("
          "value0.Value, value1.Value)"))]
        (if
         (= 1 (count comparator-arguments))
          (sequence-node
           [(raw "global::System.Collections.Generic.Comparer<")
            (type-node @ctx-holder (first comparator-arguments))
            (raw ">.Create(")
            comparison
            (raw ")")])
          comparison))}),
   :java-library.mapping.executable/handler-0444
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".CreateSocket()")])}),
   :java-library.mapping.executable/handler-0313
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".InnerText")])}),
   :java-library.mapping.executable/handler-0276
   (fn [{:keys [target-node]}] {:node (compat-call "Clone" [target-node])}),
   :java-library.mapping.executable/handler-0622
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw "(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0332
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Item(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0075
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".GetFields()")])}),
   :java-library.mapping.executable/handler-0463
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "new global::System.IO.FileInfo(") target-node (raw ")")])}),
   :java-library.mapping.executable/handler-0301
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlAttributeItem" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0536
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Format(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0130
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCompat.MemberIsAnnotationPresent(")
        target-node
        (raw ", ")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0373
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "(") target-node (raw ".Length == 0)")])}),
   :java-library.mapping.executable/handler-0168
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".ReportErrors(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0227
   (fn [{:keys [target-node]}] {:node (compat-call "CollectionIsEmpty" [target-node])}),
   :java-library.mapping.executable/handler-0060
   (fn [{:keys [arguments]}] {:node (compat-call "CharacterType" arguments)}),
   :java-library.mapping.executable/handler-0286
   (fn [_] {:node (raw "global::DripSharp.Runtime.JavaCompat.NewXmlReaderSettings()")}),
   :java-library.mapping.executable/handler-0311
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".OwnerDocument!")])}),
   :java-library.mapping.executable/handler-0495
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "AppendCodePoint" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0154
   (fn [{:keys [arguments]}] {:node (compat-call "GetProperty" arguments)}),
   :java-library.mapping.executable/handler-0413
   (fn [{:keys [target-node]}] {:node (compat-call "EnumName" [target-node])}),
   :java-library.mapping.executable/handler-0155
   (fn [{:keys [arguments]}] {:node (compat-call "GetProperty" arguments)}),
   :java-library.mapping.executable/handler-0273
   (fn [{:keys [target-node]}] {:node (compat-call "SortedFirst" [target-node])}),
   :java-library.mapping.executable/handler-0509
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Assembly")])}),
   :java-library.mapping.executable/handler-0041
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "WriterAppend" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0587
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Add" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0343
   (fn
     [{:keys [arguments element]}]
     {:node
      (let
       [element-node (collection-element-type-node @ctx-holder element)]
        (sequence-node
         [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.ListOf") [element-node])
          (raw "(")
          (sequence-node
           (collection-factory-argument-nodes @ctx-holder element arguments element-node)
           ", ")
          (raw ")")]))}),
   :java-library.mapping.executable/handler-0328
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Name")])}),
   :java-library.mapping.executable/handler-0184
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".SetDecimalFormatSymbols(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0629
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0456
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".FullName")])}),
   :java-library.mapping.executable/handler-0590
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Clear()")])}),
   :java-library.mapping.executable/handler-0326
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".GetAttribute(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0004
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0518
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Message")])}),
   :java-library.mapping.executable/handler-0327
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".GetElementsByTagName(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0204
   (fn [{:keys [arguments]}] {:node (first arguments)}),
   :java-library.mapping.executable/handler-0482
   (fn
     [{:keys [target-node arguments], target :target-element}]
     {:node
      (if
       (instance? CtSuperAccess target)
        (sequence-node [(raw "base.Read(") (sequence-node arguments ", ") (raw ")")])
        (compat-call "InputStreamRead" (into [target-node] arguments)))}),
   :java-library.mapping.executable/handler-0295
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlSetErrorHandler" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0369
   (fn [{:keys [target-node]}] {:node (compat-call "StringTrim" [target-node])}),
   :java-library.mapping.executable/handler-0212
   (fn [{:keys [target-node]}] {:node (compat-call "DequePeek" [target-node])}),
   :java-library.mapping.executable/handler-0361
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToUpper()")])}),
   :java-library.mapping.executable/handler-0544
   (fn [{:keys [arguments]}] {:node (compat-call "GetByName" arguments)}),
   :java-library.mapping.executable/handler-0586
   (fn [{:keys [target-node]}] {:node (compat-call "ListIsEmpty" [target-node])}),
   :java-library.mapping.executable/handler-0376
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringEndsWith" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0561
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapPutIfAbsent" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0575
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (compat-call
       (if (boxed-primitive-reference? (.getType element)) "MapGetNullable" "MapGet")
       (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0466
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "checked((int)") target-node (raw ".Length)")])}),
   :java-library.mapping.executable/handler-0033
   (fn [{:keys [target-node]}] {:node (compat-call "InputStreamMarkSupported" [target-node])}),
   :java-library.mapping.executable/handler-0578
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapRemove" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0083
   (fn
     [{:keys [arguments]}]
     {:node (compat-call "IsFinite" arguments)}),
   :java-library.mapping.executable/handler-0517
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SetStackTrace" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0306
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".LocalName")])}),
   :java-library.mapping.executable/handler-0136
   (fn [{:keys [target-node]}] {:node (compat-call "StringCodePoints" [target-node])}),
   :java-library.mapping.executable/handler-0097
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node [(raw "global::System.Math.Sign(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0300
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Count")])}),
   :java-library.mapping.executable/handler-0240
   (fn [{:keys [arguments]}] {:node (compat-call "UnmodifiableSet" arguments)}),
   :java-library.mapping.executable/handler-0115
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0447
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Flush()")])}),
   :java-library.mapping.executable/handler-0197
   (fn
     [{:keys [target-node]}]
     {:node (compat-call "CalendarGetTimeInMillis" [target-node])}),
   :java-library.mapping.executable/handler-0207
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "DequePush" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0225
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Poll()")])}),
   :java-library.mapping.executable/handler-0406
   (fn [_] {:node (compat-call "ConsoleInstance" [])}),
   :java-library.mapping.executable/handler-0405 (fn [_] {:node (compat-call "NanoTime" [])}),
   :java-library.mapping.executable/handler-0307
   (fn [{:keys [target-node]}] {:node (compat-call "XmlNodeNamespaceUri" [target-node])}),
   :java-library.mapping.executable/handler-0411
   (fn [{:keys [arguments]}] {:node (compat-call "ArrayHash" arguments)}),
   :java-library.mapping.executable/handler-0275
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SortedSubSet" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0506
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Name")])}),
   :java-library.mapping.executable/handler-0069
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ClassAsSubclass" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0216
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "(") target-node (raw ".Count == 0)")])}),
   :java-library.mapping.executable/handler-0163
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".NextInt()")])}),
   :java-library.mapping.executable/handler-0034
   (fn [{:keys [target-node]}] {:node (compat-call "InputStreamReset" [target-node])}),
   :java-library.mapping.executable/handler-0131
   (fn [{:keys [target-node]}] {:node (compat-call "ReflectionFieldModifiers" [target-node])}),
   :java-library.mapping.executable/handler-0248
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListIndexOf" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0124
   (fn
     [{:keys [target-node], target :target-element}]
     {:node
      (if
       (instance? CtSuperAccess target)
        (raw "this.MemberwiseClone()")
        (compat-call "Clone" [target-node]))}),
   :java-library.mapping.executable/handler-0257
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".IsEmpty")])}),
   :java-library.mapping.executable/handler-0098
   (fn [{:keys [arguments]}] {:node (compat-call "ParseFloat" arguments)}),
   :java-library.mapping.executable/handler-0082
   (fn
     [{:keys [arguments]}]
     {:node (compat-call "IsFinite" arguments)}),
   :java-library.mapping.executable/handler-0632
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "RemoveAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0346
   (fn [{:keys [arguments]}] {:node (compat-call "SynchronizedList" arguments)}),
   :java-library.mapping.executable/handler-0312
   (fn [{:keys [target-node]}] {:node (compat-call "XmlNodePrefix" [target-node])}),
   :java-library.mapping.executable/handler-0489
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "InputStreamRead" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0179
   (fn [{:keys [arguments]}] {:node (compat-call "CopyOfRange" arguments)}),
   :java-library.mapping.executable/handler-0582
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Value")])}),
   :java-library.mapping.executable/handler-0156
   (fn [{:keys [arguments]}] {:node (compat-call "GetEnvironment" arguments)}),
   :java-library.mapping.executable/handler-0032
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "InputStreamMark" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0583
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".SetValue(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0305
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".FirstChild")])}),
   :java-library.mapping.executable/handler-0458
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SetFileReadable" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0304
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ChildNodes")])}),
   :java-library.mapping.executable/handler-0476
   (fn [{:keys [target-node]}] {:node (compat-call "InputStreamRead" [target-node])}),
   :java-library.mapping.executable/handler-0018
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".WriteLine()")])}),
   :java-library.mapping.executable/handler-0606
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ContainsAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0514
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Message")])}),
   :java-library.mapping.executable/handler-0078
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToString()")])}),
   :java-library.mapping.executable/handler-0230
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "RetainAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0374
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringStartsWith" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0623
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw "(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0132
   (fn [{:keys [arguments]}] {:node (compat-call "ReflectionModifierIsFinal" arguments)}),
   :java-library.mapping.executable/handler-0329
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node
        (raw ".GetAttributeNode(")
        (second arguments)
        (raw ", ")
        (first arguments)
        (raw ")")])}),
   :java-library.mapping.executable/handler-0182
   (fn [{:keys [arguments]}] {:node (compat-call "ArrayToString" arguments)}),
   :java-library.mapping.executable/handler-0202
   (fn [{:keys [target-node]}] {:node (compat-call "CalendarGetTimeZone" [target-node])}),
   :java-library.mapping.executable/handler-0387
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToCharArray()")])}),
   :java-library.mapping.executable/handler-0213
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Count")])}),
   :java-library.mapping.executable/handler-0269
   (fn [{:keys [target-node]}] {:node (compat-call "SortedFirstKey" [target-node])}),
   :java-library.mapping.executable/handler-0102
   (fn [{:keys [arguments]}] {:node (compat-call "HighestOneBit" arguments)}),
   :java-library.mapping.executable/handler-0012
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".End()")])}),
   :java-library.mapping.executable/handler-0574
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Count")])}),
   :java-library.mapping.executable/handler-0412
   (fn
     [{:keys [arguments element]}]
     {:node
      (let
       [element-node (collection-element-type-node @ctx-holder element)]
        (sequence-node
         [(csharp/generic-name (raw "global::DripSharp.Runtime.JavaCompat.AsList") [element-node])
          (raw "(")
          (sequence-node
           (collection-factory-argument-nodes @ctx-holder element arguments element-node)
           ", ")
          (raw ")")]))}),
   :java-library.mapping/executable-default
   (fn
     [{:keys [arguments element children occurrence default-target-node declaration],
       target :target-element}]
     {:node
      (case (:key occurrence)
        ("executable:java.lang.StrictMath#cbrt(double)"
         "executable:java.lang.Math#cbrt(double)")
        (compat-call "Cbrt" arguments)

        ("executable:java.lang.StrictMath#copySign(double,double)"
         "executable:java.lang.Math#copySign(double,double)")
        (compat-call "CopySign" arguments)

        (sequence-node
         [(when target (sequence-node [default-target-node (raw ".")]))
          (child-node children (.getExecutable element))
          (when
           (= :project (:origin occurrence))
            (project-invocation-type-arguments-node @ctx-holder element declaration))
          (raw "(")
          (sequence-node arguments ", ")
          (raw ")")]))}),
   :java-library.mapping.executable/handler-0196
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CalendarGet" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0002
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Clear()")])}),
   :java-library.mapping.executable/handler-0318
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlCreateElementNs" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0249
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListLastIndexOf" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0425
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".IsClosed()")])}),
   :java-library.mapping.executable/handler-0152
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Length = ") (first arguments)])}),
   :java-library.mapping.executable/handler-0477
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "InputStreamRead" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0208
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Add" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0199
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (value-type-mutation-node
       target-node
       (sequence-node
        [(raw "global::System.DateTimeOffset.FromUnixTimeMilliseconds(")
         (first arguments)
         (raw ")")]))}),
   :java-library.mapping.executable/handler-0393
   (fn [{:keys [arguments]}] {:node (compat-call "StringValueOf" arguments)}),
   :java-library.mapping.executable/handler-0189
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".SetMinimumFractionDigits(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0270
   (fn [{:keys [target-node]}] {:node (compat-call "SortedLastKey" [target-node])}),
   :java-library.mapping.executable/handler-0595
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0355
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(compat-call "UriRawQuery" [target-node]) (raw "!")])}),
   :java-library.mapping.executable/handler-0019
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Write(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0596
   (fn [{:keys [target-node]}] {:node (compat-call "HashCode" [target-node])}),
   :java-library.mapping.executable/handler-0325
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlSetAttributeNs" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0255
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Pop()")])}),
   :java-library.mapping.executable/handler-0671
   (fn [_] {:node (raw "global::DripSharp.Runtime.JavaThread.CurrentThread()")}),
   :java-library.mapping.executable/handler-0550
   (fn [{:keys [arguments]}] {:node (compat-call "RequireNonNull" arguments)}),
   :java-library.mapping.executable/handler-0149
   (fn [{:keys [target-node]}] {:node (compat-call "Reverse" [target-node])}),
   :java-library.mapping.executable/handler-0546
   (fn [{:keys [arguments]}] {:node (compat-call "Equals" arguments)}),
   :java-library.mapping.executable/handler-0176
   (fn [{:keys [arguments]}] {:node (compat-call "PathOfUri" arguments)}),
   :java-library.mapping.executable/handler-0265
   (fn [{:keys [target-node]}] {:node (compat-call "CollectionIsEmpty" [target-node])}),
   :java-library.mapping.executable/handler-0059
   (fn [{:keys [arguments]}] {:node (compat-call "CharacterIsDefined" arguments)}),
   :java-library.mapping.executable/handler-0649
   (fn [_] {:node (raw "global::DripSharp.Runtime.JavaCompat.ToList<object>()")}),
   :java-library.mapping.executable/handler-0243
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ThenComparingInt" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0135
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringCodePointCount" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0167
   (fn
     [{:keys [target-node]}]
     {:node
      (sequence-node
       [(raw "new global::DripSharp.Runtime.JavaCharsetDecoder(") target-node (raw ")")])}),
   :java-library.mapping.executable/handler-0254
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Push(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0224
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Peek()")])}),
   :java-library.mapping.executable/handler-0205
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Pop()")])}),
   :java-library.mapping.executable/handler-0231
   (fn [{:keys [arguments]}] {:node (compat-call "SortList" arguments)}),
   :java-library.mapping.executable/handler-0266
   (fn [{:keys [target-node]}] {:node (compat-call "Iterator" [target-node])}),
   :java-library.mapping.executable/handler-0608
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".HasPrevious()")])}),
   :java-library.mapping.executable/handler-0263
   (fn [{:keys [target-node]}] {:node (compat-call "MapIsEmpty" [target-node])}),
   :java-library.mapping.executable/handler-0451
   (fn [{:keys [target-node]}] {:node (compat-call "InputStreamAvailable" [target-node])}),
   :java-library.mapping.executable/handler-0191
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".SetGroupingUsed(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0464
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MemoryStreamWriteTo" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0515
   (fn [{:keys [target-node]}] {:node (compat-call "ExceptionToString" [target-node])}),
   :java-library.mapping.executable/handler-0070
   (fn
     [{:keys [target-node arguments element], target :target-element}]
     {:node
      (let
       [cast-type (or (some-> target .getType .getActualTypeArguments first) (.getType element))]
        (sequence-node
         [(raw "global::DripSharp.Runtime.JavaCompat.ClassCast<")
          (type-node @ctx-holder cast-type)
          (raw ">(")
          target-node
          (raw ", ")
          (sequence-node arguments ", ")
          (raw ")")]))}),
   :java-library.mapping.executable/handler-0118
   (fn
     [{:keys [target-node]}]
     {:node
      (sequence-node
       [(raw "global::System.Convert.ToDouble(")
        target-node
        (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")])}),
   :java-library.mapping.executable/handler-0436
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".NextBytes(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0350
   (fn [{:keys [target-node]}] {:node (compat-call "UriPort" [target-node])}),
   :java-library.mapping.executable/handler-0247
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ListAddAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0670
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Start()")])}),
   :java-library.mapping.executable/handler-0080
   (fn [{:keys [arguments]}] {:node (compat-call "FloatToIntBits" arguments)}),
   :java-library.mapping.executable/handler-0341
   (fn
     [{:keys [element]}]
     {:node
      (let
       [type-arguments (.getActualTypeArguments (.getType element))]
        (sequence-node
         [(csharp/generic-name
           (raw "global::DripSharp.Runtime.JavaCompat.EmptyMap")
           (mapv (fn* [p1__476#] (type-node @ctx-holder p1__476#)) type-arguments))
          (raw "()")]))}),
   :java-library.mapping.executable/handler-0119
   (fn
     [{:keys [target-node]}]
     {:node
      (sequence-node
       [(raw "global::System.Convert.ToSingle(")
        target-node
        (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")])}),
   :java-library.mapping.executable/handler-0481
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".GetMessageDigest()")])}),
   :java-library.mapping.executable/handler-0234
   (fn [_] {:node (raw "global::DripSharp.Runtime.JavaBase64.GetEncoder()")}),
   :java-library.mapping.executable/handler-0492
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0103
   (fn [{:keys [arguments]}] {:node (compat-call "ToHexString" arguments)}),
   :java-library.mapping.executable/handler-0035
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "InputStreamSkip" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0399
   (fn [{:keys [arguments]}] {:node (compat-call "ToStringRadix" arguments)}),
   :java-library.mapping.executable/handler-0226
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Add" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0410
   (fn [{:keys [arguments]}] {:node (compat-call "ArrayEquals" arguments)}),
   :java-library.mapping.executable/handler-0113
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "BigIntegerShiftRight" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0129
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCompat.FieldGetAnnotation<")
        (type-node @ctx-holder (.getType element))
        (raw ">(")
        target-node
        (raw ", ")
        (sequence-node arguments ", ")
        (raw ")!")])}),
   :java-library.mapping.executable/handler-0105
   (fn [{:keys [arguments]}] {:node (first arguments)}),
   :java-library.mapping.executable/handler-0228
   (fn [{:keys [target-node]}] {:node (compat-call "CollectionCount" [target-node])}),
   :java-library.mapping.executable/handler-0377
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Substring(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0209
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "AddAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0430
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Next()")])}),
   :java-library.mapping.executable/handler-0021
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Flush()")])}),
   :java-library.mapping.executable/handler-0497
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Length")])}),
   :java-library.mapping.executable/handler-0511
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ClassGetResourceAsStream" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0490
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Append(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0111
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "BigIntegerMod" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0084
   (fn
     [{:keys [arguments]}]
     {:node (sequence-node [(raw "float.IsInfinity(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0475
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Flush()")])}),
   :java-library.mapping.executable/handler-0121
   (fn
     [{:keys [target-node], target :target-element}]
     {:node (maybe-unbox-node @ctx-holder target target-node)}),
   :java-library.mapping.executable/handler-0469
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "OutputStreamWrite" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0323
   (fn [{:keys [target-node]}] {:node (compat-call "XmlEncoding" [target-node])}),
   :java-library.mapping.executable/handler-0499
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToString()")])}),
   :java-library.mapping.executable/handler-0264
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "AddAll" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0576
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapRemove" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0027
   (fn [{:keys [target-node]}] {:node (compat-call "FileCanWrite" [target-node])}),
   :java-library.mapping.executable/handler-0261
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Clear()")])}),
   :java-library.mapping.executable/handler-0052
   (fn [{:keys [arguments]}] {:node (compat-call "GetBoolean" arguments)}),
   :java-library.mapping.executable/handler-0283
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Name")])}),
   :java-library.mapping.executable/handler-0289
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlReaderSetXIncludeAware" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0150
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Remove(") (first arguments) (raw ", 1)")])}),
   :java-library.mapping.executable/handler-0303
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Attributes!")])}),
   :java-library.mapping.executable/handler-0452
   (fn
     [{:keys [target-node]}]
     {:node
      (sequence-node
       [(raw "new global::DripSharp.Runtime.JavaPath(") target-node (raw ".FullName)")])}),
   :java-library.mapping.executable/handler-0645
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Map" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0050
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (sequence-node
       [target-node
        (raw ".")
        (raw (pascal (.getSimpleName (.getExecutable element))))
        (raw "(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0630
   (fn [{:keys [target-node]}] {:node (compat-call "HashCode" [target-node])}),
   :java-library.mapping.executable/handler-0351
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(compat-call "UriScheme" [target-node]) (raw "!")])}),
   :java-library.mapping.executable/handler-0448
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0024
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToString()")])}),
   :java-library.mapping.executable/handler-0564
   (fn [{:keys [target-node]}] {:node (compat-call "MapKeySet" [target-node])}),
   :java-library.mapping.executable/handler-0643
   (fn
     [{:keys [arguments element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.LoadServices")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0253
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SubList" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0549
   (fn [{:keys [arguments]}] {:node (compat-call "RequireNonNull" arguments)}),
   :java-library.mapping.executable/handler-0280
   (fn [{:keys [target-node]}] {:node (compat-call "TimeZoneId" [target-node])}),
   :java-library.mapping.executable/handler-0532
   (fn [{:keys [arguments]}] {:node (compat-call "ParseLocalDateTime" arguments)}),
   :java-library.mapping.executable/handler-0037
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ReaderRead" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0296
   (fn [_] {:node (raw "new global::System.Xml.XmlWriterSettings()")}),
   :java-library.mapping.executable/handler-0558
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ForEach" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0238
   (fn [{:keys [arguments]}] {:node (compat-call "CollectionMin" arguments)}),
   :java-library.mapping.executable/handler-0365
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Length")])}),
   :java-library.mapping.executable/handler-0381
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringLastIndexOf" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0478
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "InputStreamRead" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0087
   (fn
     [{:keys [arguments]}]
     {:node (sequence-node [(raw "float.IsNaN(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0120
   (fn
     [{:keys [target-node]}]
     {:node (compat-call "NumberIntValue" [target-node])}),
   :java-library.mapping.executable/handler-0288
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "XmlReaderSetFeature" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0190
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".SetMaximumFractionDigits(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0429
   (fn [{:keys [arguments]}] {:node (compat-call "UrlEncode" arguments)}),
   :java-library.mapping.executable/handler-0173
   (fn [{:keys [arguments]}] {:node (compat-call "FindFiles" arguments)}),
   :java-library.mapping.executable/handler-0450
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0220
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Load(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0074
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ClassGetDeclaredConstructor" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0395
   (fn [{:keys [arguments]}] {:node (compat-call "ParseInt" (conj arguments (raw "10")))}),
   :java-library.mapping.executable/handler-0099
   (fn [{:keys [arguments]}] {:node (compat-call "StringValueOf" arguments)}),
   :java-library.mapping.executable/handler-0656
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ShutdownNow()")])}),
   :java-library.mapping.executable/handler-0148
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node
        (raw ".Append(")
        (first arguments)
        (raw ", ")
        (second arguments)
        (raw ", ")
        (raw "(")
        (nth arguments 2)
        (raw " - ")
        (second arguments)
        (raw "))")])}),
   :java-library.mapping.executable/handler-0005
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Write(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0352
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(compat-call "UriUserInfo" [target-node]) (raw "!")])}),
   :java-library.mapping.executable/handler-0540
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".AppendOffset(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0379
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringIndexOf" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0237
   (fn [{:keys [arguments]}] {:node (compat-call "CollectionMax" arguments)}),
   :java-library.mapping.executable/handler-0537
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [target-node (raw ".ParseCaseInsensitive()")])}),
   :java-library.mapping.executable/handler-0421
   (fn [{:keys [target-node]}] {:node (compat-call "SocketIsConnected" [target-node])}),
   :java-library.mapping.executable/handler-0116
   (fn [{:keys [target-node]}] {:node (compat-call "BigIntegerIntValue" [target-node])}),
   :java-library.mapping.executable/handler-0441
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Update(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0181
   (fn [{:keys [arguments]}] {:node (compat-call "Fill" arguments)}),
   :java-library.mapping.executable/handler-0067
   (fn [{:keys [arguments]}] {:node (compat-call "CodePointToString" arguments)}),
   :java-library.mapping.executable/handler-0417
   (fn [{:keys [target-node]}] {:node (compat-call "SocketStream" [target-node])}),
   :java-library.mapping.executable/handler-0437
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".NextInt()")])}),
   :java-library.mapping.executable/handler-0030
   (fn [{:keys [target-node]}] {:node (compat-call "FileIsFile" [target-node])}),
   :java-library.mapping.executable/handler-0631
   (fn
     [{:keys [target-node arguments element]}]
     (let [arguments
           (unbox-nullable-boxed-invocation-arguments
            @ctx-holder element arguments #{0})]
       {:node
        (sequence-node
         [target-node (raw ".Add(")
          (sequence-node arguments ", ") (raw ")")])})),
   :java-library.mapping.executable/handler-0170 (fn [{:keys [target-node]}] {:node target-node}),
   :java-library.mapping.executable/handler-0159
   (fn [{:keys [arguments]}] {:node (compat-call "IdentityHashCode" arguments)}),
   :java-library.mapping.executable/handler-0644
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "FlatMap" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0462
   (fn [{:keys [arguments]}] {:node (compat-call "OpenInputStream" arguments)}),
   :java-library.mapping.executable/handler-0633
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Remove(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0570
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapPut" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0259
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Get(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0468
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "OutputStreamWrite" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0285
   (fn [{:keys [target-node]}] {:node (compat-call "XmlQualifiedNamePrefix" [target-node])}),
   :java-library.mapping.executable/handler-0187
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [target-node (raw ".GetMaximumFractionDigits()")])}),
   :java-library.mapping.executable/handler-0153
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw "[") (first arguments) (raw "]")])}),
   :java-library.mapping.executable/handler-0392
   (fn [{:keys [arguments]}] {:node (compat-call "ToStringRadix" arguments)}),
   :java-library.mapping.executable/handler-0210
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CollectionContains" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0061
   (fn [{:keys [arguments]}] {:node (compat-call "IsDigit" arguments)}),
   :java-library.mapping.executable/handler-0057
   (fn [{:keys [arguments]}] {:node (compat-call "CharacterCharCount" arguments)}),
   :java-library.mapping.executable/handler-0342
   (fn
     [{:keys [element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.EmptySet")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "()")])}),
   :java-library.mapping.executable/handler-0223
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0242
   (fn
     [{:keys [arguments element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.ComparingInt")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0519
   (fn [{:keys [target-node]}] {:node (compat-call "UriSyntaxInput" [target-node])}),
   :java-library.mapping.executable/handler-0625
   (fn
     [{:keys [element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaCompat.ReverseComparer")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw "()")])}),
   :java-library.mapping.executable/handler-0378
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringSubstring" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0415
   (fn [{:keys [arguments]}] {:node (compat-call "ParseInt" arguments)}),
   :java-library.mapping.executable/handler-0563
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapGetOrDefault" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0431
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".HasNext()")])}),
   :java-library.mapping.executable/handler-0206
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Pop()")])}),
   :java-library.mapping.executable/handler-0426
   (fn [{:keys [target-node]}] {:node (compat-call "InetSocketAddressAddress" [target-node])}),
   :java-library.mapping.executable/handler-0054
   (fn [{:keys [arguments]}] {:node (compat-call "ToUnsignedInt" arguments)}),
   :java-library.mapping.executable/handler-0195
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0432
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaMessageDigest.GetInstance(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0101
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0579
   (fn [{:keys [target-node]}] {:node (compat-call "HashCode" [target-node])}),
   :java-library.mapping.executable/handler-0370
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringSplit" (into [target-node] (conj arguments (raw "0"))))}),
   :java-library.mapping.executable/handler-0573
   (fn [{:keys [target-node]}] {:node (compat-call "MapCount" [target-node])}),
   :java-library.mapping.executable/handler-0403
   (fn [{:keys [arguments]}] {:node (compat-call "ArrayCopy" arguments)}),
   :java-library.mapping.executable/handler-0252
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "SortList" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0308
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".NextSibling")])}),
   :java-library.mapping.executable/handler-0158 (fn [_] {:node (compat-call "GetProperties" [])}),
   :java-library.mapping.executable/handler-0439
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCipher.GetMaxAllowedKeyLength(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0529
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [(raw "(") target-node (raw " < ") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0472
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Connect(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0658
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::DripSharp.Runtime.JavaExecutorService(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0443
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".CreateServerSocket(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0500
   (fn [{:keys [target-node]}] {:node (compat-call "Stream" [target-node])}),
   :java-library.mapping.executable/handler-0011
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Inflate(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0531
   (fn [{:keys [arguments]}] {:node (compat-call "ParseZonedDateTime" arguments)}),
   :java-library.mapping.executable/handler-0110
   (fn [{:keys [target-node]}] {:node (compat-call "BigIntegerToByteArray" [target-node])}),
   :java-library.mapping.executable/handler-0028
   (fn [{:keys [target-node]}] {:node (compat-call "FileLastModified" [target-node])}),
   :java-library.mapping.executable/handler-0657
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".AwaitTermination(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0125
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0114
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [(raw "(") target-node (raw " & ") (first arguments) (raw ")")])}),
   :java-library.mapping.executable/handler-0547
   (fn [{:keys [arguments]}] {:node (compat-call "HashCode" arguments)}),
   :java-library.mapping.executable/handler-0539
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ParseLenient()")])}),
   :java-library.mapping.executable/handler-0408
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Get()")])}),
   :java-library.mapping.executable/handler-0615
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".HasNext()")])}),
   :java-library.mapping.executable/handler-0145
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".ToUpperInvariant()")])}),
   :java-library.mapping.executable/handler-0244
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ThenComparing" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0581
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ForEach" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0317
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Data")])}),
   :java-library.mapping.executable/handler-0357
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "Equals" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0236
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".EncodeToString(") (first arguments) (raw ")")])}),
   :java-library.mapping.executable/handler-0177
   (fn [{:keys [arguments]}] {:node (compat-call "BinarySearch" arguments)}),
   :java-library.mapping.executable/handler-0636
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "RegexSplit" (into [target-node] (conj arguments (raw "0"))))}),
   :java-library.mapping.executable/handler-0147
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".Append(") (compat-call "StringValueOf" arguments) (raw ")")])}),
   :java-library.mapping.executable/handler-0635
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "RegexMatcher" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0471
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".WriteLine(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0512
   (fn
     [{:keys [target-node element]}]
     {:node
      (sequence-node
       [(compat-call "GetCause" [target-node]) (when (empty? (.getTypeCasts element)) (raw "!"))])}),
   :java-library.mapping.executable/handler-0484
   (fn [{:keys [target-node]}] {:node (compat-call "InputStreamRead" [target-node])}),
   :java-library.mapping.executable/handler-0315
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".RemoveChild(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0507
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".IsInstanceOfType(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0157
   (fn [{:keys [arguments]}] {:node (compat-call "Getenv" arguments)}),
   :java-library.mapping.executable/handler-0611
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".PreviousIndex()")])}),
   :java-library.mapping.executable/handler-0655
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Shutdown()")])}),
   :java-library.mapping.executable/handler-0297
   (fn [{:keys [target-node]}] {:node (compat-call "XmlWriterSettingsClone" [target-node])}),
   :java-library.mapping.executable/handler-0221
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "(") target-node (raw ".Count == 0)")])}),
   :java-library.mapping.executable/handler-0137
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringCompareTo" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0146
   (fn [{:keys [arguments]}] {:node (compat-call "StringValueOf" arguments)}),
   :java-library.mapping.executable/handler-0388
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringGetBytes" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0091
   (fn [{:keys [arguments]}] {:node (compat-call "StringValueOf" arguments)}),
   :java-library.mapping.executable/handler-0151
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "StringBuilderDelete" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0473
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "OutputStreamWrite" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0279
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "TimeZoneSetId" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0407
   (fn
     [{:keys [arguments element]}]
     {:node
      (sequence-node
       [(csharp/generic-name
         (raw "global::DripSharp.Runtime.JavaThreadLocal")
         [(type-node @ctx-holder (collection-element-type element))])
        (raw ".WithInitial(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0291
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".IgnoreComments = ") (first arguments)])}),
   :java-library.mapping.executable/handler-0128
   (fn
     [{:keys [target-node arguments element]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCompat.ConstructorInvoke<")
        (type-node @ctx-holder (.getType element))
        (raw ">(")
        target-node
        (when (seq arguments) (raw ", "))
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0551
   (fn [{:keys [arguments]}] {:node (compat-call "MapEntry" arguments)}),
   :java-library.mapping.executable/handler-0031
   (fn [{:keys [target-node]}] {:node (compat-call "FileToUri" [target-node])}),
   :java-library.mapping.executable/handler-0654
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Submit(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0674
   (fn [_] {:node (raw "value => value")}),
   :java-library.mapping.executable/handler-0675
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node
       [target-node (raw ".CopyTo(")
        (first arguments) (raw ", ")
        (nth arguments 2) (raw ", ")
        (nth arguments 3) (raw ", ")
        (csharp/binary "-" 60 (second arguments) (first arguments))
        (raw ")")])}),
   :java-library.mapping.executable/handler-0676
   (fn [{:keys [target-node]}] {:node target-node}),
   :java-library.mapping.executable/handler-0677
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CharsetCanEncode"
                         (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0678
   (fn [{:keys [target-node]}]
     {:node (compat-call "FileCreateNewFile" [target-node])}),
   :java-library.mapping.executable/handler-0679
   (fn [_] {:node (raw "global::System.Text.Encoding.Default")}),
   :java-library.mapping.executable/handler-0680
   (fn [{:keys [arguments]}]
     {:node (compat-call "WriteAllBytes" arguments)}),
   :java-library.mapping.executable/handler-0681
   (fn [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Cancel(")
                      (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0682
   (fn [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Offer(")
                      (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0683
   (fn [{:keys [target-node arguments]}]
     {:node (compat-call "PrepareStatement" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0684
   (fn [{:keys [target-node]}]
     {:node (compat-call "GetDatabaseMetaData" [target-node])}),
   :java-library.mapping.executable/handler-0685
   (fn [{:keys [target-node]}]
     {:node (compat-call "PreparedStatementGetMetaData" [target-node])}),
   :java-library.mapping.executable/handler-0686
   (fn [{:keys [arguments]}]
     {:node (compat-call "Joining" arguments)}),
   :java-library.mapping.executable/handler-0687
   (fn [{:keys [target-node arguments]}]
     {:node
      (csharp/invocation (csharp/member target-node "AddLast") arguments)}),
   :java-library.mapping.executable/handler-0001
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Get()")])}),
   :java-library.mapping.executable/handler-0233
   (fn [_] {:node (raw "global::DripSharp.Runtime.JavaBase64.GetDecoder()")}),
   :java-library.mapping.executable/handler-0096
   (fn [{:keys [arguments]}] {:node (compat-call "LongTrailingZeros" arguments)}),
   :java-library.mapping.executable/handler-0104
   (fn [{:keys [arguments]}] {:node (compat-call "ParseLong" (conj arguments (raw "10")))}),
   :java-library.mapping.executable/handler-0577
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "MapRemove" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0337
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Update(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0348
   (fn [{:keys [arguments]}] {:node (compat-call "UnmodifiableMap" arguments)}),
   :java-library.mapping.executable/handler-0435
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaMessageDigest.IsEqual(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.executable/handler-0552
   (fn [{:keys [target-node]}] {:node (compat-call "MapEntrySet" [target-node])}),
   :java-library.mapping.executable/handler-0215
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0013
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Dispose()")])}),
   :java-library.mapping.executable/handler-0607
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Set(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0621
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw "(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0356
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(compat-call "UriRawFragment" [target-node]) (raw "!")])}),
   :java-library.mapping.executable/handler-0282
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "TimeZoneSetRawOffset" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0068
   (fn [{:keys [target-node]}] {:node (compat-call "StringValueOf" [target-node])}),
   :java-library.mapping.executable/handler-0009
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".NeedsInput()")])}),
   :java-library.mapping.executable/handler-0017
   (fn [{:keys [target-node]}] {:node (compat-call "ReaderReady" [target-node])}),
   :java-library.mapping.executable/handler-0434
   (fn
     [{:keys [target-node arguments]}]
     {:node
      (sequence-node [target-node (raw ".Digest(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0505
   (fn
     [{:keys [target-node]}]
     {:node
      (sequence-node [(raw "(") target-node (raw ".FullName ?? ") target-node (raw ".Name)")])}),
   :java-library.mapping.executable/handler-0222
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Add(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.executable/handler-0614
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Next()")])}),
   :java-library.mapping.executable/handler-0134
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CodePointAt" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0040
   (fn
     [{:keys [target-node arguments]}]
     {:node (sequence-node [target-node (raw ".Write(") (first arguments) (raw ")")])}),
   :java-library.mapping.executable/handler-0501
   (fn
     [{:keys [target-node]}]
     {:node (sequence-node [(raw "((object)(") target-node (raw ")).GetType()")])}),
   :java-library.mapping.executable/handler-0200
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "CalendarSetLenient" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0076
   (fn
     [{:keys [target-node arguments]}]
     {:node (compat-call "ClassGetResourceAsStream" (into [target-node] arguments))}),
   :java-library.mapping.executable/handler-0309
   (fn [{:keys [target-node]}] {:node (sequence-node [target-node (raw ".Name")])})})

(defn-
  constructor-mapping-handlers
  [ctx-holder]
  {:java-library.mapping/constructor-default
   (fn
     [{:keys [arguments element]}]
     {:node
      (sequence-node
       [(raw "new ")
        (type-node @ctx-holder (.getType element))
        (raw "(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.constructor/handler-0001
   (fn [_] {:node (raw "global::DripSharp.Runtime.JavaCompat.NewThrowable()")}),
   :java-library.mapping.constructor/handler-0002
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node [(raw "new global::System.Exception(null, ") (first arguments) (raw ")")])}),
   :java-library.mapping.constructor/handler-0003
   (fn [{:keys [arguments]}] {:node (compat-call "NewTypeInitializationException" arguments)}),
   :java-library.mapping.constructor/handler-0004
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::System.ArgumentException(null, ") (first arguments) (raw ")")])}),
   :java-library.mapping.constructor/handler-0005
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::System.IO.IOException(null, ") (first arguments) (raw ")")])}),
   :java-library.mapping.constructor/handler-0006
   (fn
     [{:keys [arguments]}]
     {:node
      (if (seq arguments)
        (sequence-node
         [(raw "new global::System.IO.FileNotFoundException(")
          (first arguments) (raw ")")])
        (compat-call "NewFileNotFoundException" []))}),
   :java-library.mapping.constructor/handler-0007
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::System.IO.IOException(null, ") (first arguments) (raw ")")])}),
   :java-library.mapping.constructor/handler-0008
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaSocketFactory.Plain.CreateSocket(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.constructor/handler-0009
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaSocketFactory.Plain.CreateSocket(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.constructor/handler-0010
   (fn [{:keys [arguments]}] {:node (compat-call "NewUriSyntaxException" arguments)}),
   :java-library.mapping.constructor/handler-0011
   (fn [{:keys [arguments]}] {:node (compat-call "NewUri" arguments)}),
   :java-library.mapping.constructor/handler-0012
   (fn [{:keys [arguments]}] {:node (compat-call "NewUri" arguments)}),
   :java-library.mapping.constructor/handler-0013
   (fn [{:keys [arguments]}] {:node (compat-call "NewUri" arguments)}),
   :java-library.mapping.constructor/handler-0014
   (fn [{:keys [arguments]}] {:node (compat-call "NewUri" arguments)}),
   :java-library.mapping.constructor/handler-0015
   (fn [{:keys [arguments]}] {:node (compat-call "NewFileInfo" arguments)}),
   :java-library.mapping.constructor/handler-0016
   (fn [{:keys [arguments]}] {:node (compat-call "NewFileInfo" arguments)}),
   :java-library.mapping.constructor/handler-0017
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCompat.NewMemoryStream(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.constructor/handler-0018
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "global::DripSharp.Runtime.JavaCompat.NewMemoryStream(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.constructor/handler-0019
   (fn [{:keys [arguments]}] {:node (compat-call "OpenFileInput" arguments)}),
   :java-library.mapping.constructor/handler-0020
   (fn [{:keys [arguments]}] {:node (compat-call "OpenFileReader" arguments)}),
   :java-library.mapping.constructor/handler-0021
   (fn [{:keys [arguments]}] {:node (compat-call "OpenFileOutput" arguments)}),
   :java-library.mapping.constructor/handler-0022
   (fn [{:keys [arguments]}] {:node (compat-call "NewFileWriter" arguments)}),
   :java-library.mapping.constructor/handler-0023
   (fn [{:keys [arguments]}] {:node (first arguments)}),
   :java-library.mapping.constructor/handler-0024
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::DripSharp.Runtime.JavaSequenceInputStream(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.constructor/handler-0025
   (fn [{:keys [arguments]}] {:node (compat-call "NewBigInteger" arguments)}),
   :java-library.mapping.constructor/handler-0046
   (fn [{:keys [arguments]}] {:node (compat-call "BigIntegerParse" arguments)}),
   :java-library.mapping.constructor/handler-0047
   (fn [{:keys [arguments]}] {:node (compat-call "NewInputStreamReader" arguments)}),
   :java-library.mapping.constructor/handler-0026
   (fn
     [{:keys [arguments]}]
     {:node (sequence-node [(raw "new decimal(") (sequence-node arguments ", ") (raw ")")])}),
   :java-library.mapping.constructor/handler-0027
   (fn [{:keys [arguments]}] {:node (compat-call "BigDecimalParse" arguments)}),
   :java-library.mapping.constructor/handler-0028
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::System.IO.Compression.GZipStream(")
        (sequence-node arguments ", ")
        (raw ", global::System.IO.Compression.CompressionMode.Decompress)")])}),
   :java-library.mapping.constructor/handler-0029
   (fn [{:keys [arguments]}] {:node (compat-call "NewString" arguments)}),
   :java-library.mapping.constructor/handler-0030
   (fn [{:keys [arguments]}] {:node (compat-call "NewString" arguments)}),
   :java-library.mapping.constructor/handler-0031
   (fn [_] {:node (raw "global::System.DateTimeOffset.Now")}),
   :java-library.mapping.constructor/handler-0032
   (fn [{:keys [arguments]}] {:node (compat-call "CalendarInstance" arguments)}),
   :java-library.mapping.constructor/handler-0033
   (fn [_] {:node (raw "new global::DripSharp.Runtime.JavaDecimalFormat()")}),
   :java-library.mapping.constructor/handler-0034
   (fn
     [{:keys [arguments]}]
     {:node
      (sequence-node
       [(raw "new global::DripSharp.Runtime.JavaDecimalFormat(")
        (sequence-node arguments ", ")
        (raw ")")])}),
   :java-library.mapping.constructor/handler-0035
   (fn [{:keys [arguments]}] {:node (compat-call "NewSimpleTimeZone" arguments)}),
   :java-library.mapping.constructor/handler-0036
   (fn [{:keys [arguments]}] {:node (compat-call "NewXmlQualifiedName" arguments)}),
   :java-library.mapping.constructor/handler-0037
   (fn [{:keys [arguments]}] {:node (first arguments)}),
   :java-library.mapping.constructor/handler-0038
   (fn [{:keys [arguments]}] {:node (compat-call "OpenFileOutput" arguments)}),
   :java-library.mapping.constructor/handler-0039
   (fn
     [{:keys [element]}]
     {:node (sequence-node [(raw "new ") (type-node @ctx-holder (.getType element)) (raw "()")])}),
   :java-library.mapping.constructor/handler-0040
   (fn
     [{:keys [arguments element occurrence]}]
     {:node
      (let
       [types
        (vec (.getActualTypeArguments (.getType element)))
        effective-arguments
        (if
         (= "executable:java.util.HashMap#<init>(int,float)" (:key occurrence))
          [(first arguments)]
          arguments)]
        (if
         (= 2 (count types))
          (sequence-node
           [(raw "global::DripSharp.Runtime.JavaCompat.NewJavaDictionary<")
            (type-node @ctx-holder (first types))
            (raw ", ")
            (type-node @ctx-holder (second types))
            (raw ">(")
            (sequence-node effective-arguments ", ")
            (raw ")")])
          (sequence-node
           [(raw "new ")
            (type-node @ctx-holder (.getType element))
            (raw "(")
            (sequence-node effective-arguments ", ")
            (raw ")")])))}),
   :java-library.mapping.constructor/handler-0041
   (fn
     [{:keys [element]}]
     {:node (sequence-node [(raw "new ") (type-node @ctx-holder (.getType element)) (raw "()")])}),
   :java-library.mapping.constructor/handler-0042
   (fn
     [{:keys [arguments element]}]
     {:node
      (let [source-type (.getQualifiedName (.getType element))]
        (sequence-node
         [(raw "new ")
          (type-node @ctx-holder (.getType element))
          (raw "(")
          (when-not (contains? #{"java.util.HashSet" "java.util.LinkedHashSet"}
                               source-type)
            (first arguments))
          (raw ")")]))}),
   :java-library.mapping.constructor/handler-0043
   (fn
     [{:keys [element]}]
     {:node
      (let
       [types (vec (.getActualTypeArguments (.getType element)))]
        (if
         (= 2 (count types))
          (sequence-node
           [(raw "global::DripSharp.Runtime.JavaCompat.NewSortedDictionary<")
            (type-node @ctx-holder (first types))
            (raw ", ")
            (type-node @ctx-holder (second types))
            (raw ">()")])
          (sequence-node [(raw "new ") (type-node @ctx-holder (.getType element)) (raw "()")])))}),
   :java-library.mapping.constructor/handler-0044
   (fn
     [{:keys [element]}]
     {:node
      (let
       [types (vec (.getActualTypeArguments (.getType element)))]
        (if
         (= 1 (count types))
          (sequence-node
           [(raw "global::DripSharp.Runtime.JavaCompat.NewSortedSet<")
            (type-node @ctx-holder (first types))
            (raw ">()")])
          (sequence-node [(raw "new ") (type-node @ctx-holder (.getType element)) (raw "()")])))}),
   :java-library.mapping.constructor/handler-0045
   (fn
     [{:keys [arguments element]}]
     {:node
      (sequence-node
       [(raw "new ")
        (type-node @ctx-holder (.getType element))
        (raw "(")
        (first arguments)
        (raw ")")])})})

(defn-
  shared-mapping-registry
  [ctx-holder]
  (library-mappings/compile-registry
   (merge
    declarative-shared-handlers
    (executable-mapping-handlers ctx-holder)
    (constructor-mapping-handlers ctx-holder))))

(def ^:private reporting-shared-mapping-registry
  (delay (shared-mapping-registry (atom nil))))

(defn declarative-mapping-registries
  "Returns the validated shared registries used for selected-target batch
  mapping analysis. The member registry handlers are never invoked by the
  report join; they are compiled here so the same entry and handler contracts
  fail closed before translation."
  [_mapping-context]
  {:java-types java-types/registry
   :java-members @reporting-shared-mapping-registry})

(defn jdk-mapping-candidate?
  "Returns true for resolved JDK identities whose ownership a selected product
  must classify as either declarative or target-specific."
  [occurrence]
  (let [reference (:reference occurrence)
        role (when reference
               (str/lower-case (str (.getRoleInParent ^CtElement reference))))
        within-annotation?
        (loop [element reference]
          (when (and element
                     (.isParentInitialized ^CtElement element))
            (let [parent (.getParent ^CtElement element)]
              (or (instance? CtAnnotation parent)
                  (recur parent)))))]
    (boolean
     (and (= :jdk (:origin occurrence))
          (contains? #{:type :executable :constructor :field}
                     (:kind occurrence))
          (re-matches #"^(?:type|executable|field):javax?\..+"
                      (:key occurrence))
          (not= :class-literal (:resolution occurrence))
          (not (and (= :type (:kind occurrence))
                    (= "thrown" role)))
          (not (and (= :field (:kind occurrence))
                    within-annotation?))))))

(defn declarative-mapping-required?
  "Returns true for occurrences owned by an ordinary shared declarative
  registry. Product bundles widen this to all JDK candidates after excluding
  their explicit target-specific adaptations."
  [{:keys [registries]} occurrence]
  (boolean
   (and (jdk-mapping-candidate? occurrence)
        (some #(mapping-registry/registry-entry % (:key occurrence))
              (vals registries)))))

(defn- default-interface-fluent-super-node
  [ctx target ^CtMethod declaration arguments]
  (let [body (.getBody declaration)
        statements (when body (vec (.getStatements body)))
        setter (first statements)
        returned (second statements)]
    (when (and (instance? CtSuperAccess target)
               (= 2 (count statements))
               (instance? CtInvocation setter)
               (instance? CtReturn returned)
               (instance? CtThisAccess
                          (.getReturnedExpression ^CtReturn returned))
               (= (count arguments)
                  (count (.getArguments ^CtInvocation setter))))
      (sequence-node
       [(raw "((global::System.Func<")
        (type-node ctx (.getType declaration))
        (raw ">)(() => { this.")
        (raw (identifier
              (.getSimpleName (.getExecutable ^CtInvocation setter))))
        (raw "(") (sequence-node arguments ", ")
        (raw "); return this; }))()")]))))

(defn- default-interface-context-super-node
  "Lowers the visitor-style Java default method pattern
  `Interface.super.visit(value)` where the selected default method delegates to
  `this.visit(value, null)`. The CLR cannot directly invoke a particular
  default-interface body, and casting `this` would redispatch to the override,
  so emit the behavior of that one-line default explicitly."
  [ctx target ^CtMethod declaration arguments]
  (let [body (.getBody declaration)
        statements (when body (vec (.getStatements body)))
        delegated (first statements)
        delegated-arguments
        (when (instance? CtInvocation delegated)
          (vec (.getArguments ^CtInvocation delegated)))
        delegated-declaration
        (when (instance? CtInvocation delegated)
          (some-> ^CtInvocation delegated .getExecutable .getDeclaration))
        parameters (vec (.getParameters declaration))
        lifted (wildcard-method-type-parameters declaration)]
    (when (and (instance? CtSuperAccess target)
               (= 1 (count statements))
               (instance? CtInvocation delegated)
               (instance? CtMethod delegated-declaration)
               (= (.getSimpleName declaration)
                  (.getSimpleName ^CtMethod delegated-declaration))
               (= (inc (count parameters)) (count delegated-arguments))
               (= (count parameters) (count arguments))
               (instance? CtLiteral (peek delegated-arguments))
               (nil? (.getValue ^CtLiteral (peek delegated-arguments))))
      (let [generic-arguments
            (concat
             (repeat (count (.getFormalCtTypeParameters
                             ^CtMethod delegated-declaration))
                     (raw "object"))
             (mapv #(raw (:name %)) lifted))]
        (sequence-node
         [(raw "this.")
          (raw (method-name ctx
                            (.getDeclaringType ^CtMethod delegated-declaration)
                            delegated-declaration))
          (when (seq generic-arguments)
            (sequence-node
             [(raw "<")
              (sequence-node (vec generic-arguments) ", ")
              (raw ">")]))
          (raw "(")
          (sequence-node
           (conj (vec arguments) (raw "(object)default!")) ", ")
          (raw ")")])))))

(defn- default-interface-return-super-node
  "Inlines a parameterless one-expression Java interface default selected with
  `Interface.super`. CLR interface casts would redispatch to the concrete
  override, so the selected Java body must remain explicit."
  [ctx target ^CtMethod declaration arguments]
  (let [body (.getBody declaration)
        statements (when body (vec (.getStatements body)))
        returned (first statements)]
    (when (and (instance? CtSuperAccess target)
               (empty? arguments)
               (= 1 (count statements))
               (instance? CtReturn returned))
      (translated-node ctx (.getReturnedExpression ^CtReturn returned)))))

(defn- body-rules [ctx-holder]
  (let [shared-mappings (shared-mapping-registry ctx-holder)]
    (java/structural-rules
     [{:id :java-library.expression/invocation
       :class CtInvocation
       :emit
       (fn [{:keys [context ^CtInvocation element children]}]
         (let [target (.getTarget element)
               occurrence (invocation-occurrence context element)
               declaration (:declaration occurrence)
               static-interface-owner
               (when (and (= :project (:origin occurrence))
                          (instance? CtMethod declaration)
                          (.hasModifier ^CtMethod declaration
                                        ModifierKind/STATIC)
                          (interface-type?
                           (.getDeclaringType ^CtMethod declaration))
                          (interface-static-companion-member?
                           @ctx-holder declaration))
                 (.getDeclaringType ^CtMethod declaration))
               target-node
               (if static-interface-owner
                 (raw (project-interface-static-companion-base
                       @ctx-holder static-interface-owner))
                 (when target
                   (invocation-target-node
                    @ctx-holder children target declaration
                    (.getExecutable element))))
               parameter-types
               (executable-parameter-types
                declaration (.getExecutable element) (some-> target .getType))
               arguments
               (mapv
                (fn [index ^CtExpression argument]
                  (let [node (child-node children argument)
                        expected
                        (call-parameter-type
                         declaration parameter-types
                         (count (.getArguments element)) index argument)]
                    (or
                     (raw-list-constructor-for-generic-owner-node
                      @ctx-holder argument expected declaration target node)
                     (argument-value-node
                      @ctx-holder argument expected
                      (declared-call-parameter-type
                       declaration (count (.getArguments element))
                       index argument)
                      node
                      (and
                       (contains? generic-value-argument-executables
                                  (:key occurrence))
                       (not
                        (nullable-boxed-collection-expression?
                         target [])))))))
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
               target-node
               destination-adaptation
               (or
                (when default-interface?
                  (or
                   (default-interface-context-super-node
                    @ctx-holder target declaration arguments)
                   (default-interface-fluent-super-node
                    @ctx-holder target declaration arguments)
                   (default-interface-return-super-node
                    @ctx-holder target declaration arguments)))
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
                    :source-target-node
                    (when target
                      (if (instance? CtThisAccess target)
                        (this-node @ctx-holder ^CtThisAccess target)
                        (child-node children target)))
                    :arguments arguments}))
                (when
                 (= "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)"
                    (:key occurrence))
                  (compat-call "ComparatorCompare"
                               (into [target-node] arguments))))
               raw-node
               (if (= :constructor (:kind occurrence))
                 (raw "")
                 (or
                  destination-adaptation
                  (inherited-runtime-object-invocation-node
                   occurrence target-node arguments)
                  (reflection-invocation-node
                   (:key occurrence) target-node arguments)
                  (decimal-invocation-node
                   (:key occurrence) target-node arguments)
                  (security-invocation-node
                   (:key occurrence) target-node arguments)
                  (netstandard-math-invocation-node
                   (:key occurrence) arguments)
                  (supplemental-neutral-invocation-node
                   @ctx-holder element (:key occurrence) target-node arguments)
                  (or
                   (target-declarative-node
                    ctx-holder
                    #(declarative-shared-invocation-node
                      %
                      @ctx-holder
                      element
                      occurrence
                      target
                      target-node
                      default-target-node
                      arguments
                      children
                      declaration))
                   (declarative-shared-invocation-node
                    shared-mappings
                    @ctx-holder
                    element
                    occurrence
                    target
                    target-node
                    default-target-node
                    arguments
                    children
                    declaration)
                   (sequence-node
                    [(when target-node
                       (sequence-node [default-target-node (raw ".")]))
                     (child-node children (.getExecutable element))
                     (when (= :project (:origin occurrence))
                       (project-invocation-type-arguments-node
                        @ctx-holder element declaration))
                     (raw "(")
                     (sequence-node arguments ", ")
                     (raw ")")]))))
               covariant-cast-node
               (when (and (= :project (:origin occurrence))
                          (instance? CtMethod declaration)
                          (not (statement-expression? element)))
                 (or
                  (when-let [normalized-covariant-cast
                             (get-in @ctx-holder
                                     [:services
                                      :normalized-covariant-invocation-cast-node])]
                    (normalized-covariant-cast element declaration))
                  (when (and
                         (netstandard-covariant-override?
                          (.getDeclaringType ^CtMethod declaration)
                          declaration)
                         (not
                          (netstandard-public-covariant-hiding?
                           (.getDeclaringType ^CtMethod declaration)
                           declaration)))
                    (type-node @ctx-holder (.getType ^CtMethod declaration)))))
               raw-node
               (if covariant-cast-node
                 (sequence-node
                  [(raw "((") covariant-cast-node
                   (raw ")(") raw-node (raw "))")])
                 raw-node)
               raw-node
               (if (= :project (:origin occurrence))
                 (normalize-redundant-static-casts raw-node)
                 raw-node)
               raw-node
               (if (and
                    (or
                     (nullable-declaration? @ctx-holder
                                            (:declaration occurrence))
                     (= "executable:java.util.Iterator#next()"
                        (:key occurrence)))
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
                        (call-parameter-type
                         declaration parameter-types
                         (count (.getArguments element)) index argument)]
                    (argument-value-node
                     @ctx-holder argument expected
                     (declared-call-parameter-type
                      declaration (count (.getArguments element))
                      index argument)
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
                        (or (anonymous-uses-outer? anonymous-class owner)
                            (some-> element .getType .getTypeDeclaration
                                    non-static-member-class?)))
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
               (and anonymous-class (anonymous-delegate? @ctx-holder element))
               (anonymous-delegate-node @ctx-holder element)

               anonymous-class
               (sequence-node
                [(raw (str "new " (anonymous-class-name element) "("))
                 (sequence-node
                  (vec
                   (concat arguments
                           (when outer? [(raw "this")])
                           (map #(raw (if-let [capture (capture-name @ctx-holder %)]
                                        (str "this." capture)
                                        (local-declaration-name %)))
                                captures)))
                  ", ")
                 (raw ")")])

               :else
               (if destination-adaptation
                 destination-adaptation
                 (or
                  (target-declarative-node
                   ctx-holder
                   #(declarative-shared-constructor-node
                     % @ctx-holder element occurrence arguments))
                  (declarative-shared-constructor-node
                   shared-mappings @ctx-holder element occurrence arguments)
                  (sequence-node
                   [(raw "new ")
                    (type-node @ctx-holder (.getType element))
                    (raw "(")
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
               static? (or (and (instance? CtMethod declaration)
                                (.hasModifier ^CtMethod declaration
                                              ModifierKind/STATIC))
                           (and (instance? Method declaration)
                                (java.lang.reflect.Modifier/isStatic
                                 (.getModifiers ^Method declaration))))
               parameter-count (count (.getParameters (.getExecutable element)))
               parameters (mapv #(raw (str "value" %)) (range parameter-count))
               functional-type (some-> element .getType .getQualifiedName)
               functional-arguments
               (vec (.getActualTypeArguments (.getType element)))
               generic-return-inference
               (when (and (instance? CtMethod declaration)
                          (= 1 (count (.getFormalCtTypeParameters
                                       ^CtMethod declaration)))
                          (instance? CtTypeParameterReference
                                     (.getType ^CtMethod declaration))
                          (empty? (.getActualTypeArguments
                                   (.getExecutable element))))
                 (case functional-type
                   "java.util.function.Function"
                   (second functional-arguments)
                   "java.util.function.Supplier"
                   (first functional-arguments)
                   "java.util.function.UnaryOperator"
                   (first functional-arguments)
                   nil))
               file-delete-reference?
               (and (= "delete" (.getSimpleName (.getExecutable element)))
                    (instance? CtTypeAccess target-element)
                    (= "java.io.File"
                       (some-> ^CtTypeAccess target-element
                               .getAccessedType
                               .getQualifiedName)))
               destination-method-reference?
               (:destination-method-reference? @ctx-holder)
               discards-result?
               (and (or (and (instance? CtMethod declaration)
                             (not= "void"
                                   (.getQualifiedName
                                    (.getType ^CtMethod declaration))))
                        (and (instance? Method declaration)
                             (not= Void/TYPE
                                   (.getReturnType ^Method declaration))))
                    (contains? #{"java.util.function.Consumer"
                                 "java.util.function.BiConsumer"
                                 "org.junit.jupiter.api.function.Executable"
                                 "org.assertj.core.api.ThrowableAssert$ThrowingCallable"}
                               functional-type))]
           (when-not (or file-delete-reference?
                         (and (= :project (:origin occurrence))
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
                            "executable:java.lang.String#trim()"
                            "executable:java.lang.String#toUpperCase()"
                            "executable:java.lang.String#toLowerCase()"
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
                            "executable:java.lang.Throwable#getMessage()"
                            "executable:java.lang.StringBuilder#append(java.lang.Object)"
                            "executable:java.util.List#add(java.lang.Object)"
                            "executable:java.util.ArrayList#add(java.lang.Object)"
                            "executable:java.util.List#remove(java.lang.Object)"
                            "executable:java.util.Set#remove(java.lang.Object)"
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
               file-delete-reference?
               (raw "(value0) => { value0.Delete(); }")

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

               (= "executable:java.lang.String#trim()" (:key occurrence))
               (raw "(value0) => global::DripSharp.Runtime.JavaCompat.StringTrim(value0)")

               (= "executable:java.lang.String#toUpperCase()" (:key occurrence))
               (raw "(value0) => value0.ToUpper()")

               (= "executable:java.lang.String#toLowerCase()" (:key occurrence))
               (raw "(value0) => value0.ToLowerInvariant()")

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
               (raw "global::System.Math.Min")

               (= "executable:java.lang.Math#max(float,float)" (:key occurrence))
               (raw "global::System.Math.Max")

               (= "executable:java.util.Map$Entry#getKey()" (:key occurrence))
               (raw "(value0) => value0.Key")

               (= "executable:java.util.Map$Entry#getValue()" (:key occurrence))
               (raw "(value0) => value0.Value")

               (contains? #{"executable:java.util.List#add(java.lang.Object)"
                            "executable:java.util.ArrayList#add(java.lang.Object)"}
                          (:key occurrence))
               (sequence-node
                [(raw "(value0) => { ") target (raw ".Add(value0); }")])

               (contains? #{"executable:java.util.List#remove(java.lang.Object)"
                            "executable:java.util.Set#remove(java.lang.Object)"}
                          (:key occurrence))
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

               (= "executable:java.lang.StringBuilder#append(java.lang.Object)"
                  (:key occurrence))
               (sequence-node
                [(raw "(value0) => { ") target (raw ".Append(value0); }")])

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

               (= "executable:java.lang.Throwable#getMessage()" (:key occurrence))
               (raw "(value0) => value0.Message")

               (= "executable:java.util.ArrayList#<init>()" (:key occurrence))
               (let [parent
                     (when (.isParentInitialized element)
                       (.getParent element))
                     parent-result-type
                     (when (instance? CtInvocation parent)
                       (.getType ^CtInvocation parent))
                     supplier-type
                     (when (= "java.util.function.Supplier" functional-type)
                       (first (.getActualTypeArguments (.getType element))))
                     element-type
                     (or
                      (some-> ^CtTypeReference supplier-type
                              .getActualTypeArguments
                              first)
                      (some-> ^CtTypeReference parent-result-type
                              .getActualTypeArguments
                              first))
                     nullable-element?
                     (and
                      element-type
                      (boxed-primitive-reference? element-type)
                      (instance? CtInvocation parent)
                      (nullable-boxed-collection-expression?
                       (.getTarget ^CtInvocation parent) []))]
                 (sequence-node
                  [(raw "() => new ")
                   (if element-type
                     (csharp/generic-name
                      (raw "global::System.Collections.Generic.List")
                      [(if nullable-element?
                         (sequence-node
                          [(type-node @ctx-holder element-type) (raw "?")])
                         (type-node @ctx-holder element-type))])
                     target)
                   (raw "()")]))

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

               generic-return-inference
               (sequence-node
                [(raw "(") (sequence-node parameters ", ") (raw ") => ")
                 target (raw ".") executable (raw "<")
                 (type-node @ctx-holder generic-return-inference)
                 (raw ">(") (sequence-node parameters ", ") (raw ")")])

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
               nullable-boxed-result?
               (and (boxed-primitive-reference? result-reference)
                    (nullable-boxed-expression? @ctx-holder element))
               object-result? (wildcard-generic-conditional-result? element)
               branch-node
               (fn [^CtExpression expression]
                 (let [node (child-node children expression)]
                   (cond
                     primitive-result?
                     (maybe-unbox-node @ctx-holder expression node)

                     nullable-boxed-result?
                     (sequence-node
                      [(raw "(")
                       (nullable-node (type-node @ctx-holder result-reference))
                       (raw ")(") node (raw ")")])

                     object-result?
                     (sequence-node [(raw "(object)(") node (raw ")")])

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
               field-declaration (:declaration occurrence)
               interface-static-owner
               (when (and (instance? CtTypeAccess target)
                          (= :project (:origin occurrence))
                          (instance? CtField field-declaration)
                          (.hasModifier ^CtField field-declaration ModifierKind/STATIC)
                          (interface-type?
                           (.getDeclaringType ^CtField field-declaration)))
                 (.getDeclaringType ^CtField field-declaration))
               target-node
               (cond
                 (and interface-static-owner
                      (interface-static-companion-member?
                       @ctx-holder field-declaration))
                 (raw (project-interface-static-companion-base
                       @ctx-holder interface-static-owner))

                 interface-static-owner
                 (raw (project-type-base @ctx-holder interface-static-owner))

                 target
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
                 (or
                  (target-declarative-node
                   ctx-holder
                   #(declarative-shared-field-node
                     % element occurrence target target-node))
                  (declarative-shared-field-node
                   shared-mappings element occurrence target target-node)
                  (if (and (instance? CtTypeAccess target)
                           (= "class" (.getSimpleName (.getVariable element))))
                    (sequence-node [(raw "typeof(") target-node (raw ")")])
                    (sequence-node
                     [(when target (sequence-node [target-node (raw ".")]))
                      (if (= "field:<array>#length" (:key occurrence))
                        (raw "Length")
                        (child-node children (.getVariable element)))]))))]
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
         (let [variable (.getVariable element)
               mutable? (boolean (some (fn [^CtVariableWrite candidate] (identical? variable (some-> candidate .getVariable .getDeclaration))) (.getElements (.getBody element) (TypeFilter. CtVariableWrite))))
               variable-name (local-declaration-name variable)
               iteration-name (str "__foreachValue_" variable-name)
               nullable-boxed-element?
               (and (boxed-primitive-reference? (.getType variable))
                    (nullable-boxed-collection-expression?
                     (.getExpression element) []))
               variable-type-node
               (cond
                 (.isInferred variable) (raw "var")
                 nullable-boxed-element?
                 (sequence-node
                  [(type-node @ctx-holder (.getType variable)) (raw "?")])
                 :else
                 (declaration-type-node
                  @ctx-holder variable (.getType variable)))]
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
                  (let [parent (when (.isParentInitialized read)
                                 (.getParent read))]
                    (and
                     (identical?
                      parameter
                      (some-> read .getVariable .getDeclaration))
                     (not (and (instance? CtThrow parent)
                               (identical?
                                read
                                (.getThrownExpression ^CtThrow parent)))))))
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
                   (sequence-node
                    (map
                     #(raw (str parameter-name " is not " %))
                     (cons
                      "global::System.TypeInitializationException"
                      (remove #{"global::System.Exception"}
                              (sort later-catch-destinations))))
                    " && "))
                 (when labeled-flow-exception?
                   (raw
                    (str parameter-name " is not "
                         (java/runtime-type-identity
                          context :labeled-control-flow))))])]
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
         (let [expression (.getThrownExpression element)
               catch-variable?
               (and
                (instance? CtVariableAccess expression)
                (instance?
                 CtCatchVariable
                 (some-> ^CtVariableAccess expression
                         .getVariable
                         .getDeclaration)))]
           {:node
            (if catch-variable?
              (raw "throw;")
              (sequence-node [(raw "throw ")
                              (child-node children expression)
                              (raw ";")]))}))}

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

      {:id :java-library.declaration/local-type-parameter-shell
       :class CtTypeParameter
       :emit (fn [_] {:node (raw "")})}

      {:id :java-library.trivia/comment
       :class CtComment
       :emit (fn [_] {:node (raw "")})}

      {:id :java-library.reference/package
       :class CtPackageReference
       :emit (fn [_] {:node (raw "")})}])))

(defn create-body-context
  "Creates the shared accepted body translator for a resolved Java model.
  `ctx-holder` contains the destination emission context so target bundles can
  supply explicit type-shape and resolved-symbol adaptations without replacing
  ordinary Java structural or standard-library rules."
  ([resolved-model ctx-holder]
   (create-body-context resolved-model ctx-holder
                        (:runtime-capabilities @ctx-holder)))
  ([resolved-model ctx-holder runtime-capabilities]
   (java/context resolved-model
     {:mode :accepted
      :rules (body-rules ctx-holder)
      :mappings (semantic-mappings resolved-model ctx-holder)
      :runtime-capabilities runtime-capabilities})))

(defn- emission-template
  "Builds the immutable resolved-symbol translation registry once per worker.
  The holder is reset for each sequential root on that worker so mapping rules
  still observe the current declaration context."
  [resolved-model {:keys [runtime-capabilities]}]
  (let [ctx-holder (atom nil)]
    {:ctx-holder ctx-holder
     :body-context (create-body-context resolved-model ctx-holder
                                        runtime-capabilities)}))

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
    ;; Project emission only consumes aggregate executable coverage. Retaining
    ;; the completed translation here also retains its rendered text, source
    ;; mappings, visit ledger, and destination node until every source root has
    ;; finished. Generated parsers make that otherwise-linear accounting hold
    ;; several complete copies of their largest method trees in the live heap.
    (swap! (:body-translations ctx) conj (java/coverage-totals translation))
    (:node translation)))

(defn- declaration-id [^CtElement element kind]
  (let [{:keys [file line column]} (spoon/source-location element)]
    (str (name kind) ":" (or file "implicit") ":" (or line 0) ":"
         (or column 0) ":" (.getName (class element)))))

(defn- destination-owner-name [ctx ^CtType type]
  (or (some-> ^IdentityHashMap (:destination-owner-overrides ctx) (.get type))
      (let [owner
            (str (destination-namespace ctx type) "."
                 (str/join "." (map #(pascal (.getSimpleName ^CtType %))
                                    (declaring-types type))))]
        (if (and (:interface-static-companion? ctx)
                 (interface-type? type))
          (str owner "Statics")
          owner))))

(def ^:private model-all-types-cache (WeakHashMap.))

(defn- model-all-types [model]
  (locking model-all-types-cache
    (or (.get model-all-types-cache model)
        (let [all-types
              (->> (.getAllTypes model)
                   (mapcat
                    #(tree-seq
                      (fn [^CtType type] (seq (.getNestedTypes type)))
                      (fn [^CtType type] (.getNestedTypes type))
                      %))
                   vec)]
          (.put model-all-types-cache model all-types)
          all-types))))

(defn- public-derived-type? [^CtType type]
  (let [qualified-name (.getQualifiedName type)
        all-types (model-all-types (.getModel (.getFactory type)))]
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
             (let [all-types (model-all-types model)
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

(defn- type-formals-node [ctx ^CtType type]
  (let [parameters (vec (.getFormalCtTypeParameters type))]
    (when (seq parameters)
      (sequence-node [(raw "<")
                      (sequence-node
                       (mapv
                        #(sequence-node
                          [(when (and (interface-type? type)
                                      (contains? (:covariant-interface-types ctx)
                                                 (.getQualifiedName type)))
                             (raw "out "))
                           (raw (destination-type-parameter-name %))])
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

(defn- method-formals-node
  [^CtMethod method lifted]
  (let [parameters
        (concat
         (map destination-type-parameter-name
              (.getFormalCtTypeParameters method))
         (map :name lifted))]
    (when (seq parameters)
      (sequence-node
       [(raw "<")
        (sequence-node (mapv raw parameters) ", ")
        (raw ">")]))))

(defn- materialized-method-type-parameter-overrides
  [^CtType owner ^CtMethod method]
  (let [owner-names
        (set (map #(.getSimpleName ^CtElement %)
                  (.getFormalCtTypeParameters owner)))
        parameters (.getFormalCtTypeParameters method)]
    (into
     {}
     (keep
      (fn [^CtTypeParameter parameter]
        (let [name (.getSimpleName parameter)]
          (when (contains? owner-names name)
            [name (str "Method" (pascal name))])))
      parameters))))

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

(defn- lifted-wildcard-constraints-node
  [ctx lifted]
  (let [clauses
        (keep
         (fn [{:keys [name bound]}]
           (when bound
             (sequence-node
              [(raw (str " where " name " : "))
               (type-node ctx bound)])))
         lifted)]
    (when (seq clauses)
      (sequence-node clauses))))

(defn- lifted-wildcard-parameter-type-node
  [ctx ^CtParameter parameter lifted]
  (let [matches (filterv #(identical? parameter (:parameter %)) lifted)]
    (when (seq matches)
      (let [reference (.getType parameter)
            declaration (.getTypeDeclaration ^CtTypeReference reference)
            replacements (into {} (map (juxt :argument-index :name) matches))
            arguments
            (mapv
             (fn [argument-index ^CtTypeReference argument]
               (if-let [name (get replacements argument-index)]
                 (raw name)
                 (type-node ctx argument)))
             (range)
             (.getActualTypeArguments reference))]
        (csharp/generic-name
         (raw (project-type-base ctx declaration))
         arguments)))))

(defn- parameter-node
  ([ctx ^CtParameter parameter]
   (parameter-node ctx parameter []))
  ([ctx ^CtParameter parameter lifted]
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
         (or (lifted-wildcard-parameter-type-node ctx parameter lifted)
             (declaration-type-node ctx parameter (.getType parameter))))
       (raw (str " " (identifier (.getSimpleName parameter))))]))))

(defn- interface-constant-container? [^CtType type]
  (and (interface-type? type)
       (seq (.getFields type))
       (not-any? #(and (instance? CtMethod %)
                       (not (.hasModifier ^CtMethod % ModifierKind/STATIC)))
                 (.getMethods type))))

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
  #{"java.lang.Runnable"
    "java.util.concurrent.Callable"
    "java.util.function.Supplier"
    "java.util.function.UnaryOperator"})

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
    (= "java.io.Serializable" (.getQualifiedName reference))
    nil

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

(def ^:private java-stream-method-names
  {"available" "Available"
   "flush" "Flush"
   "mark" "Mark"
   "markSupported" "MarkSupported"
   "read" "Read"
   "reset" "Reset"
   "skip" "Skip"
   "write" "Write"})

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
    (and (java-stream-subclass? owner)
         (superclass-method owner method)
         (contains? java-stream-method-names (.getSimpleName method)))
    (get java-stream-method-names (.getSimpleName method))

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

(defn- superclass-reference-to
  [^CtType owner ^CtType ancestor]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (if (= (.getQualifiedName ancestor)
             (.getQualifiedName ^CtTypeReference reference))
        reference
        (when-let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
          (when (instance? CtClass declaration)
            (recur (.getSuperclass ^CtClass declaration))))))))

(defn- substituted-owner-reference
  [^CtTypeReference owner-reference ^CtTypeReference member-reference]
  (if-not (instance? CtTypeParameterReference member-reference)
    member-reference
    (let [owner (.getTypeDeclaration owner-reference)
          formals (when (instance? CtType owner)
                    (vec (.getFormalCtTypeParameters ^CtType owner)))
          actuals (vec (.getActualTypeArguments owner-reference))]
      (if (= (count formals) (count actuals))
        (or
         (some
          (fn [[^CtTypeParameter formal ^CtTypeReference actual]]
            (when (= (.getSimpleName formal)
                     (.getSimpleName member-reference))
              actual))
          (map vector formals actuals))
         member-reference)
        member-reference))))

(defn- override-family-return-type
  [^CtType owner ^CtMethod method]
  (if-let [^CtMethod super-method (superclass-method owner method)]
    (let [super-owner (.getDeclaringType super-method)
          parent-return (override-family-return-type super-owner super-method)]
      (if-let [reference (superclass-reference-to owner super-owner)]
        (substituted-owner-reference reference parent-return)
        parent-return))
    (if-let [^CtMethod interface-method
             (inherited-abstract-interface-method owner method)]
      (.getType interface-method)
      (.getType method))))

(defn- override-family-root [^CtType owner ^CtMethod method]
  (loop [current-owner owner current-method method]
    (if-let [parent-method (superclass-method current-owner current-method)]
      (recur (.getDeclaringType ^CtMethod parent-method) parent-method)
      [current-owner current-method])))

(defn- netstandard-covariant-override?
  [^CtType owner ^CtMethod method]
  (let [source (.getType method)
        destination (override-family-return-type owner method)]
    (and (or (superclass-method owner method)
             (inherited-abstract-interface-method owner method))
         (not (wildcard-generic-covariant-override? owner method))
         (or (not= (.getQualifiedName source)
                   (.getQualifiedName destination))
             (not= (mapv str (.getActualTypeArguments source))
                   (mapv str (.getActualTypeArguments destination)))))))

(defn- netstandard-public-reference-covariant-override?
  [^CtType owner ^CtMethod method]
  (let [^CtTypeReference source (.getType method)
        ^CtTypeReference destination (override-family-return-type owner method)]
    (and (netstandard-covariant-override? owner method)
         (.hasModifier method ModifierKind/PUBLIC)
         (not (.isPrimitive source))
         (not (.isPrimitive destination))
         (not= (.getQualifiedName source)
               (.getQualifiedName destination)))))

(defn- same-override-family?
  [^CtType root-owner ^CtMethod root-method
   ^CtType candidate-owner ^CtMethod candidate-method]
  (let [[candidate-root-owner candidate-root-method]
        (override-family-root candidate-owner candidate-method)]
    (and (= (.getQualifiedName root-owner)
            (.getQualifiedName ^CtType candidate-root-owner))
         (= (.getSignature root-method)
            (.getSignature ^CtMethod candidate-root-method)))))

(defn- abstract-base-covariant-bridge-family?
  [^CtType owner ^CtMethod method]
  (let [[^CtType root-owner ^CtMethod root-method]
        (override-family-root owner method)]
    (and (.hasModifier root-method ModifierKind/ABSTRACT)
         (boolean
          (some
           (fn [^CtType candidate-owner]
             (some
              (fn [^CtMethod candidate-method]
                (and
                 (netstandard-public-reference-covariant-override?
                  candidate-owner candidate-method)
                 (same-override-family? root-owner root-method
                                        candidate-owner candidate-method)))
              (.getMethodsByName candidate-owner
                                 (.getSimpleName root-method))))
           (model-all-types (.getModel (.getFactory owner))))))))

(defn- netstandard-public-covariant-hiding?
  [^CtType owner ^CtMethod method]
  ;; netstandard2.0 cannot encode CLR covariant override metadata.  For a
  ;; concrete base member, retain its virtual slot for base-typed dispatch.  An
  ;; abstract family uses a generated invariant hook for that slot instead.
  ;; Either shape can expose the Java-facing return contract through an
  ;; explicitly hidden member.
  (let [^CtMethod super-method (superclass-method owner method)
        [_ ^CtMethod root-method] (override-family-root owner method)]
    (and (netstandard-public-reference-covariant-override? owner method)
         (some? super-method)
         (or (not (.hasModifier super-method ModifierKind/ABSTRACT))
             (.hasModifier root-method ModifierKind/ABSTRACT)))))

(defn- concrete-generic-return-reference
  [^CtTypeReference declared ^CtExpression expression]
  (let [references
        (remove
         nil?
         [(some-> expression .getType)
          (when (instance? CtInvocation expression)
            (some-> ^CtInvocation expression .getTarget .getType))])]
    (some
     (fn [^CtTypeReference reference]
       (let [arguments (vec (.getActualTypeArguments reference))]
         (when (and (= (.getQualifiedName declared)
                       (.getQualifiedName reference))
                    (seq arguments)
                    (not-any? #(instance? CtWildcardReference %) arguments))
           reference)))
     references)))

(defn- concrete-wildcard-method-return
  [^CtMethod method]
  (let [^CtTypeReference declared (.getType method)
        arguments (vec (.getActualTypeArguments declared))
        returns (when-let [body (.getBody method)]
                  (vec (.getElements body (TypeFilter. CtReturn))))
        concrete
        (when (and (not (str/starts-with?
                         (.getQualifiedName declared) "java."))
                   (some #(instance? CtWildcardReference %) arguments)
                   (seq returns))
          (mapv
           #(some->> (.getReturnedExpression ^CtReturn %)
                     (concrete-generic-return-reference declared))
           returns))]
    (when (and (seq concrete)
               (every? some? concrete)
               (apply = (map str concrete)))
      (first concrete))))

(defn- wildcard-generic-covariant-override?
  [^CtType owner ^CtMethod method]
  (when-let [super-method (superclass-method owner method)]
    (let [^CtTypeReference parent-return (.getType ^CtMethod super-method)
          ^CtTypeReference child-return (.getType method)
          parent-arguments (vec (.getActualTypeArguments parent-return))
          child-arguments (vec (.getActualTypeArguments child-return))]
      (and (= (.getQualifiedName parent-return)
              (.getQualifiedName child-return))
           (= (count parent-arguments) (count child-arguments))
           (some #(instance? CtWildcardReference %) parent-arguments)
           (not= (mapv str parent-arguments)
                 (mapv str child-arguments))))))

(defn- emitted-method-return-type
  [^CtType owner ^CtMethod method]
  (if (and (netstandard-covariant-override? owner method)
           (not (netstandard-public-covariant-hiding? owner method)))
    (override-family-return-type owner method)
    (or (concrete-wildcard-method-return method)
        (.getType method))))

(defn- self-generic-method-return?
  [^CtType owner ^CtMethod method]
  (let [^CtTypeReference reference (.getType method)
        arguments (vec (.getActualTypeArguments reference))]
    (and (= (.getQualifiedName owner) (.getQualifiedName reference))
         (not (.hasModifier method ModifierKind/STATIC))
         (or (empty? arguments)
             (every? #(instance? CtWildcardReference %) arguments)))))

(defn- override-family-return-type-node
  [ctx ^CtType owner ^CtMethod method]
  (let [^CtMethod super-method (superclass-method owner method)
        super-owner (some-> super-method .getDeclaringType)
        [root-owner] (override-family-root owner method)
        super-owner (or super-owner root-owner)
        owner-reference (when super-owner
                          (superclass-reference-to owner super-owner))
        formals (if super-owner
                  (vec (.getFormalCtTypeParameters ^CtType super-owner))
                  [])
        actuals (if owner-reference
                  (vec (.getActualTypeArguments ^CtTypeReference owner-reference))
                  [])
        overrides
        (when (= (count formals) (count actuals))
          (into {}
                (map (fn [[^CtTypeParameter formal ^CtTypeReference actual]]
                       [(.getSimpleName formal)
                        (:text (csharp/render (type-node ctx actual)))]))
                (map vector formals actuals)))]
    (binding [*destination-type-parameter-overrides* overrides]
      (declaration-type-node ctx method
                             (override-family-return-type owner method)))))

(defn- method-return-type-node
  [ctx ^CtType owner ^CtMethod method]
  (cond
    (and (netstandard-covariant-override? owner method)
         (not (netstandard-public-covariant-hiding? owner method)))
    (override-family-return-type-node ctx owner method)
    (self-generic-method-return? owner method)
    (owner-type-node ctx owner)
    :else
    (declaration-type-node ctx method
                           (emitted-method-return-type owner method))))

(defn- interface-reference-subtype?
  [^CtTypeReference candidate ^CtTypeReference ancestor]
  (let [ancestor-name (.getQualifiedName ancestor)]
    (loop [pending
           (some-> candidate .getTypeDeclaration .getSuperInterfaces seq)
           seen #{}]
      (when-let [^CtTypeReference reference (first pending)]
        (let [qualified-name (.getQualifiedName reference)]
          (cond
            (= ancestor-name qualified-name) true
            (contains? seen qualified-name)
            (recur (next pending) seen)
            :else
            (recur
             (concat (next pending)
                     (or (some-> reference
                                 .getTypeDeclaration
                                 .getSuperInterfaces
                                 seq)
                         []))
             (conj seen qualified-name))))))))

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
         (reduce
          (fn [methods [^CtTypeReference reference
                        ^CtMethod method :as entry]]
            ;; One Java default can be reachable through several parent
            ;; interfaces. C# needs one materialized implementation per
            ;; signature, not one per inheritance path. Prefer the contract
            ;; reached through the most-specific interface regardless of the
            ;; order of an implementor's direct interface list.
            (let [signature [(.getSignature method) (str (.getType method))]
                  entries (get methods signature [])
                  more-specific-existing?
                  (some
                   (fn [[^CtTypeReference existing-reference]]
                     (or (= (.getQualifiedName existing-reference)
                            (.getQualifiedName reference))
                         (interface-reference-subtype?
                          existing-reference reference)))
                   entries)]
              (if more-specific-existing?
                methods
                (assoc
                 methods signature
                 (conj
                  (filterv
                   (fn [[^CtTypeReference existing-reference]]
                     (not (interface-reference-subtype?
                           reference existing-reference)))
                   entries)
                  entry)))))
          (sorted-map))
         vals
         (mapcat identity)
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
  (some->
   (some
    (fn [[_ ^CtMethod candidate :as entry]]
      (when (or (= (.getSignature method) (.getSignature candidate))
                (override-compatible-method? method candidate))
        entry))
    (interface-methods owner))
   second))

(defn- direct-interface-method-entry [^CtType owner ^CtMethod method]
  (some
   (fn [[_ ^CtMethod candidate :as entry]]
     (when (or (= (.getSignature method) (.getSignature candidate))
               (override-compatible-method? method candidate))
       entry))
   (interface-methods owner)))

(def ^:private destination-non-overridable-methods
  #{["java.lang.Throwable" "fillInStackTrace()"]})

(defn- destination-overridable-super-method? [^CtMethod method]
  (not
   (contains?
    destination-non-overridable-methods
    [(.getQualifiedName (.getDeclaringType method)) (.getSignature method)])))

(defn- subtype-of? [^CtType candidate ^CtType ancestor]
  (loop [reference (when (instance? CtClass candidate)
                     (.getSuperclass ^CtClass candidate))]
    (when reference
      (or (= (.getQualifiedName ancestor)
             (.getQualifiedName ^CtTypeReference reference))
          (when-let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
            (when (instance? CtClass declaration)
              (recur (.getSuperclass ^CtClass declaration))))))))

(defn- override-family-has-modifier?
  [^CtType owner ^CtMethod method modifier]
  (let [[root-owner root-method] (override-family-root owner method)
        simple-name (.getSimpleName ^CtMethod root-method)
        all-types (model-all-types (.getModel (.getFactory owner)))]
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

(defn- method-words
  ([^CtType owner ^CtMethod method]
   (method-words nil owner method))
  ([ctx ^CtType owner ^CtMethod method]
   (let [static? (.hasModifier method ModifierKind/STATIC)
         source-abstract? (and (not (interface-type? owner))
                               (.hasModifier method ModifierKind/ABSTRACT))
         abstract-covariant-bridge?
         (and source-abstract?
              (abstract-base-covariant-bridge-family? owner method))
         abstract? (and source-abstract?
                        (not abstract-covariant-bridge?))
         superclass-reference (when (instance? CtClass owner)
                                (.getSuperclass ^CtClass owner))
         superclass-declaration (some-> superclass-reference
                                         .getTypeDeclaration)
         external-superclass?
         (and superclass-reference
              (or (nil? superclass-declaration)
                  (.isShadow ^CtType superclass-declaration))
              (some? (translated-external-type-base
                      ctx superclass-reference)))
         super-method (when-not static? (superclass-method owner method))
         inherited-runtime-interface-method
         (when-not static? (inherited-runtime-interface-method owner method))
         inherited-abstract-interface-method
         (when-not static?
           (inherited-abstract-interface-method owner method))
         inherited-default-interface-method
         (when-not static?
           (inherited-default-interface-method? owner method))
         redeclared-interface-method
         (when (interface-type? owner)
           (direct-interface-method owner method))
         widened-override-family? (public-override-family? owner method)
         protected-override-family? (protected-override-family? owner method)
         wildcard-generic-covariant-override?
         (wildcard-generic-covariant-override? owner method)
         netstandard-public-covariant-hiding?
         (netstandard-public-covariant-hiding? owner method)
         source-override-annotation?
         (some #(= "java.lang.Override"
                   (some-> ^CtAnnotation %
                           .getAnnotationType
                           .getQualifiedName))
               (.getAnnotations method))
         external-source-override?
         (and source-override-annotation? external-superclass?)
         override? (and (not static?)
                        (not wildcard-generic-covariant-override?)
                        (not netstandard-public-covariant-hiding?)
                        (or external-source-override?
                            (destination-object-method? method)
                            (and (java-map-entry-implementation? owner)
                                 (= "setValue" (.getSimpleName method))
                                 (= 1 (count (.getParameters method))))
                            (and (= "close" (.getSimpleName method))
                                 (empty? (.getParameters method))
                                 (superclass-implements-closeable? owner))
                            (and (not (and (= "getMessage" (.getSimpleName method))
                                           (empty? (.getParameters method))))
                                 super-method
                                 (destination-overridable-super-method?
                                  super-method))
                            inherited-default-interface-method
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

                (and (:destination-external-override-protected? ctx)
                     override?
                     external-superclass?
                     (.hasModifier method ModifierKind/PROTECTED))
                "protected"

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
              (when (or redeclared-interface-method
                        wildcard-generic-covariant-override?
                        netstandard-public-covariant-hiding?
                        (and (= "getType" (.getSimpleName method))
                             (empty? (.getParameters method))
                             (csharp-public-names? ctx)
                             (not (interface-type? owner))))
                "new")
              (when virtual? "virtual")]))))

(declare member-node emit-root emit-anonymous-type owner-type-node
         derived-body-context abstract-covariant-forwarding-body-node)

(defn- enclosing-executable [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtLambda current)
      (recur (when (.isParentInitialized ^CtElement current)
               (.getParent ^CtElement current)))
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

(defn- executable-anonymous-calls [ctx ^CtExecutable executable]
  (->> (.getElements executable (TypeFilter. CtConstructorCall))
       (filter anonymous-class-for-call)
       (remove #(anonymous-delegate? ctx %))
       (filter #(identical? executable (enclosing-executable %)))
       (sort-by (fn [^CtElement call]
                  (let [{:keys [file line column]} (spoon/source-location call)]
                    [file line column])))
       vec))

(defn- field-anonymous-calls
  ([^CtField field]
   (field-anonymous-calls nil field))
  ([ctx ^CtField field]
   (if-let [initializer (.getDefaultExpression field)]
     (->> (.getElements initializer (TypeFilter. CtConstructorCall))
          (filter anonymous-class-for-call)
          (remove #(if ctx
                     (anonymous-delegate? ctx %)
                     (anonymous-callable? %)))
          (sort-by (fn [^CtElement call]
                     (let [{:keys [file line column]} (spoon/source-location call)]
                       [file line column])))
          vec)
     [])))

(defn- initializer-uses-this? [^CtField field]
  (boolean
   (when-let [initializer (.getDefaultExpression field)]
     (seq (.getElements initializer (TypeFilter. CtThisAccess))))))

(defn- instance-initialization-node
  [ctx member]
  (cond
    (instance? CtField member)
    (let [^CtField field member
          initializer (.getDefaultExpression field)]
      (sequence-node
       [(raw (str "this." (destination-field-name ctx field) " = "))
        (assignment-value-node
         ctx field initializer (translated-node ctx initializer))
        (raw ";")]))

    (instance? CtAnonymousExecutable member)
    (csharp/with-source
      (translated-node ctx (.getBody ^CtAnonymousExecutable member))
      (source-ref member :java-library.declaration/instance-initializer nil))

    :else
    (unsupported! "Java instance initialization member is not implemented"
                  member)))

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

(defn- compile-time-constant-field? [^CtField field]
  (let [initializer (.getDefaultExpression field)]
    (and (.hasModifier field ModifierKind/STATIC)
         (.hasModifier field ModifierKind/FINAL)
         (compile-time-constant-expression? initializer)
         (or (.isPrimitive (.getType field))
             (= "java.lang.String" (.getQualifiedName (.getType field)))))))

(defn- interface-static-companion-member?
  ([member]
   (interface-static-companion-member? nil member))
  ([ctx member]
   (let [owner (cond
                 (instance? CtMethod member)
                 (.getDeclaringType ^CtMethod member)
                 (instance? CtField member)
                 (.getDeclaringType ^CtField member)
                 :else nil)
         companion? (get-in ctx [:services :interface-static-companion?])]
     (and owner
          (interface-type? owner)
          (.hasModifier ^CtModifiable member ModifierKind/STATIC)
          (or (nil? companion?) (companion? owner))
          (or (instance? CtMethod member)
              (instance? CtField member))))))

(defn- compile-time-string-value [expression]
  (cond
    (instance? CtLiteral expression)
    (str (.getValue ^CtLiteral expression))

    (and (instance? CtBinaryOperator expression)
         (= "PLUS" (str (.getKind ^CtBinaryOperator expression))))
    (str (compile-time-string-value
          (.getLeftHandOperand ^CtBinaryOperator expression))
         (compile-time-string-value
          (.getRightHandOperand ^CtBinaryOperator expression)))

    :else
    (unsupported! "Java string constant has an unsupported expression"
                  expression)))

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
                    (= (destination-field-name ctx field)
                       (destination-field-name ctx ^CtField %))
                    (or (not (.hasModifier ^CtField % ModifierKind/PRIVATE))
                        (some? (.getDeclaringType ^CtClass declaration))
                        (java-serialization-uid? ^CtField %)))
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
        (and (not enum-value?) (compile-time-constant-field? field))
        deferred?
        (boolean
         (some #(identical? field %)
               (:deferred-field-initializers ctx)))
        initializer-node
        (when (and initializer (not deferred?))
          (if (and compile-time-constant?
                   (= "java.lang.String" (.getQualifiedName (.getType field))))
            (raw (escape-string (compile-time-string-value initializer)))
            (assignment-value-node
             ctx field initializer (translated-node ctx initializer))))
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
              (.isPrimitive (.getType field))
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
                                       (when-not
                                        (and (interface-type? owner)
                                             (not (interface-constant-container?
                                                   owner))
                                             (not (:interface-static-companion?
                                                   ctx))
                                             compile-time-constant?)
                                         emitted-visibility))
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
                              (executable-anonymous-calls ctx method))
        body (.getBody method)
        abstract-covariant-bridge?
        (and (.hasModifier method ModifierKind/ABSTRACT)
             (abstract-base-covariant-bridge-family? owner method))
        ;; netstandard2.0 cannot carry default-interface implementations.  The
        ;; same Java body is materialized on each concrete implementor by
        ;; interface-contract-nodes below, while the interface retains the
        ;; ordinary abstract contract that net48 and modern runtimes share.
        body-node
        (cond
          abstract-covariant-bridge?
          (abstract-covariant-forwarding-body-node ctx owner method)

          (and body
               (not (and (interface-type? owner)
                         (not (.hasModifier method ModifierKind/STATIC)))))
          (translated-node ctx body))
        name (method-name ctx owner method)
        rule :java-library.declaration/method
        words (method-words ctx owner method)
        formals (vec (.getFormalCtTypeParameters method))
        lifted (wildcard-method-type-parameters method)
        id (register-member! ctx owner method name rule nil (first words))
        method-node
        (csharp/with-source
          (csharp/declaration
           (sequence-node
            [(raw (str (str/join " " words) " "))
             (method-return-type-node ctx owner method)
             (raw (str " " name))
             (method-formals-node method lifted)
             (raw "(")
             (sequence-node
              (mapv #(parameter-node ctx % lifted) (.getParameters method)) ", ")
             (raw ")")
             (when-not (some #{"override"} words)
               (sequence-node
                [(constraints-node ctx formals)
                 (lifted-wildcard-constraints-node ctx lifted)]))])
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
    #(when (or (= (.getSignature interface-method) (.getSignature ^CtMethod %))
               (override-compatible-method? interface-method %))
       %)
    (.getMethodsByName owner (.getSimpleName interface-method)))
   (superclass-method owner interface-method)))

(defn- matching-default-interface-method
  [^CtType owner ^CtMethod interface-method]
  (some
   (fn [[^CtTypeReference reference ^CtMethod candidate]]
     (when (and (some? (.getBody candidate))
                (not (.hasModifier candidate ModifierKind/STATIC))
                (or (= (.getSignature interface-method)
                       (.getSignature candidate))
                    (override-compatible-method? interface-method candidate)))
       [reference candidate]))
   (interface-methods owner)))

(defn- inherited-default-interface-method?
  [^CtType owner ^CtMethod interface-method]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (when-let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
        (when (instance? CtClass declaration)
          (or
           (some
            (fn [[_ ^CtMethod candidate]]
              (and (some? (.getBody candidate))
                   (not (.hasModifier candidate ModifierKind/STATIC))
                   (override-compatible-method? interface-method candidate)))
            (interface-methods declaration))
           (recur (.getSuperclass ^CtClass declaration))))))))

(defn- substituted-interface-reference
  [^CtTypeReference interface-reference ^CtTypeReference member-reference]
  (substituted-owner-reference interface-reference member-reference))

(defn- interface-contract-nodes [ctx ^CtType owner]
  (when (instance? CtClass owner)
    (->> (interface-methods owner)
         (keep
          (fn [[^CtTypeReference interface-reference ^CtMethod interface-method]]
            (when-not (.hasModifier interface-method ModifierKind/STATIC)
              (let [implementation
                    (matching-class-method owner interface-method)
                    default-implementation
                    (matching-default-interface-method owner interface-method)
                    inherited-default-implementation?
                    (inherited-default-interface-method?
                     owner interface-method)
                    interface-return-reference
                    (substituted-interface-reference
                     interface-reference (.getType interface-method))
                    implementation-return-reference
                    (when implementation
                      (emitted-method-return-type owner implementation))
                    name (method-name ctx
                                      (.getDeclaringType interface-method)
                                      interface-method)]
                (cond
                  (and implementation
                       (not (override-compatible-type?
                             interface-return-reference
                             implementation-return-reference)))
                  (let [parameters (vec (.getParameters interface-method))]
                    (sequence-node
                     [(declaration-type-node
                       ctx interface-method interface-return-reference)
                      (raw " ")
                      (type-node ctx interface-reference)
                      (raw (str "." name))
                      (executable-formals-node interface-method)
                      (raw "(")
                      (sequence-node (mapv #(parameter-node ctx %) parameters) ", ")
                      (raw ") => (")
                      (declaration-type-node
                       ctx interface-method interface-return-reference)
                      (raw ")(this.")
                      (raw (method-name ctx owner implementation))
                      (raw "(")
                      (sequence-node
                       (mapv #(raw (identifier (.getSimpleName ^CtParameter %)))
                             parameters)
                       ", ")
                      (raw "));")]))

                  implementation
                  nil

                  inherited-default-implementation?
                  nil

                  default-implementation
                  (let [[^CtTypeReference default-interface-reference
                         ^CtMethod default-method]
                        default-implementation
                        default-body-ctx
                        (derived-body-context
                         ctx {:inlined-default-interface-body? true})
                        parameters (vec (.getParameters interface-method))
                        formals (vec (.getFormalCtTypeParameters interface-method))
                        lifted (wildcard-method-type-parameters interface-method)
                        overrides
                        (materialized-method-type-parameter-overrides
                         owner interface-method)]
                    (binding [*destination-type-parameter-overrides* overrides]
                      (sequence-node
                       [(raw "public ")
                        (when-not (.hasModifier ^CtModifiable owner
                                                ModifierKind/FINAL)
                          (raw "virtual "))
                        (declaration-type-node
                         ctx interface-method interface-return-reference)
                        (raw (str " " name))
                        (method-formals-node interface-method lifted)
                        (raw "(")
                        (sequence-node
                         (mapv #(parameter-node ctx % lifted) parameters) ", ")
                        (raw ")")
                        (constraints-node ctx formals)
                        (lifted-wildcard-constraints-node ctx lifted)
                        (raw " ")
                        (translated-node default-body-ctx
                                         (.getBody default-method))])))

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
                   (str "Concrete Java class "
                        (pr-str (.getQualifiedName owner))
                        " has no resolved implementation for "
                        (pr-str (.getQualifiedName interface-reference))
                        " method "
                        (pr-str (.getSignature interface-method)))
                   owner))))))
         vec)))

(defn- runtime-interface-contract-nodes [ctx ^CtType owner]
  (when (instance? CtClass owner)
    (->> (.getSuperInterfaces owner)
         (mapcat
          (fn [^CtTypeReference reference]
            (case (.getQualifiedName reference)
              "java.lang.Iterable"
              (let [arguments (vec (.getActualTypeArguments reference))
                    element-type (if (= 1 (count arguments))
                                   (type-node ctx (first arguments))
                                   (raw "object"))]
                [(sequence-node
                  [(raw "global::System.Collections.Generic.IEnumerator<")
                   element-type
                   (raw "> global::System.Collections.Generic.IEnumerable<")
                   element-type
                   (raw ">.GetEnumerator() {")
                   (raw "return global::DripSharp.Runtime.JavaCompat.AsEnumerator(this.Iterator());")
                   (raw "}")])
                 (sequence-node
                  [(raw
                    (str
                     "global::System.Collections.IEnumerator "
                     "global::System.Collections.IEnumerable.GetEnumerator() {"
                     "return ((global::System.Collections.Generic.IEnumerable<"))
                   element-type
                   (raw ">)this).GetEnumerator();}")])])

              "java.util.Iterator"
              (when (empty? (.getMethodsByName owner "remove"))
                [(raw
                  (str
                   "public void Remove() {"
                   "throw new global::System.NotSupportedException("
                   "\"Iterator removal is not supported.\");}"))])

              "java.util.List"
              (let [arguments (vec (.getActualTypeArguments reference))
                    element (if (= 1 (count arguments))
                              (:text (csharp/render
                                      (type-node ctx (first arguments))))
                              "object")]
                [(raw
                  (format
                   (str
                    "int global::System.Collections.Generic.ICollection<%1$s>.Count => this.Size();\n"
                    "bool global::System.Collections.Generic.ICollection<%1$s>.IsReadOnly => false;\n"
                    "%1$s global::System.Collections.Generic.IList<%1$s>.this[int index] { get => this.Get(index); set => this.Set(index, value); }\n"
                    "void global::System.Collections.Generic.ICollection<%1$s>.Add(%1$s item) => this.Add(item);\n"
                    "bool global::System.Collections.Generic.ICollection<%1$s>.Contains(%1$s item) => this.Contains(item);\n"
                    "void global::System.Collections.Generic.ICollection<%1$s>.CopyTo(%1$s[] array, int arrayIndex) { global::DripSharp.Runtime.JavaCompat.ThrowIfNull(array, nameof(array)); foreach (var item in (global::System.Collections.Generic.IEnumerable<%1$s>)this) array[arrayIndex++] = item; }\n"
                    "bool global::System.Collections.Generic.ICollection<%1$s>.Remove(%1$s item) => this.Remove(item);\n"
                    "int global::System.Collections.Generic.IList<%1$s>.IndexOf(%1$s item) => this.IndexOf(item);\n"
                    "void global::System.Collections.Generic.IList<%1$s>.Insert(int index, %1$s item) => this.Add(index, item);\n"
                    "void global::System.Collections.Generic.IList<%1$s>.RemoveAt(int index) => this.Remove(index);\n"
                    "global::System.Collections.Generic.IEnumerator<%1$s> global::System.Collections.Generic.IEnumerable<%1$s>.GetEnumerator() { return global::DripSharp.Runtime.JavaCompat.AsEnumerator(this.Iterator()); }\n"
                    "global::System.Collections.IEnumerator global::System.Collections.IEnumerable.GetEnumerator() => ((global::System.Collections.Generic.IEnumerable<%1$s>)this).GetEnumerator();")
                   element))])

              "java.util.Map"
              (let [arguments (vec (.getActualTypeArguments reference))
                    key-type (if (= 2 (count arguments))
                               (:text (csharp/render
                                       (type-node ctx (first arguments))))
                               "object")
                    value-type (if (= 2 (count arguments))
                                 (:text (csharp/render
                                         (type-node ctx (second arguments))))
                                 "object")]
                [(raw
                  (format
                   (str
                    "%2$s global::System.Collections.Generic.IDictionary<%1$s, %2$s>.this[%1$s key] { get => this.Get(key); set => this.Put(key, value); }\n"
                    "global::System.Collections.Generic.ICollection<%1$s> global::System.Collections.Generic.IDictionary<%1$s, %2$s>.Keys => this.KeySet();\n"
                    "global::System.Collections.Generic.ICollection<%2$s> global::System.Collections.Generic.IDictionary<%1$s, %2$s>.Values => this.Values();\n"
                    "int global::System.Collections.Generic.ICollection<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>.Count => this.Size();\n"
                    "bool global::System.Collections.Generic.ICollection<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>.IsReadOnly => false;\n"
                    "void global::System.Collections.Generic.IDictionary<%1$s, %2$s>.Add(%1$s key, %2$s value) => this.Put(key, value);\n"
                    "bool global::System.Collections.Generic.IDictionary<%1$s, %2$s>.ContainsKey(%1$s key) => this.ContainsKey(key);\n"
                    "bool global::System.Collections.Generic.IDictionary<%1$s, %2$s>.Remove(%1$s key) { if (!this.ContainsKey(key)) return false; this.Remove(key); return true; }\n"
                    "bool global::System.Collections.Generic.IDictionary<%1$s, %2$s>.TryGetValue(%1$s key, out %2$s value) { if (this.ContainsKey(key)) { value = this.Get(key); return true; } value = default!; return false; }\n"
                    "void global::System.Collections.Generic.ICollection<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>.Add(global::System.Collections.Generic.KeyValuePair<%1$s, %2$s> item) => this.Put(item.Key, item.Value);\n"
                    "bool global::System.Collections.Generic.ICollection<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>.Contains(global::System.Collections.Generic.KeyValuePair<%1$s, %2$s> item) => this.ContainsKey(item.Key) && global::DripSharp.Runtime.JavaCompat.Equals(this.Get(item.Key), item.Value);\n"
                    "void global::System.Collections.Generic.ICollection<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>.CopyTo(global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>[] array, int arrayIndex) { global::DripSharp.Runtime.JavaCompat.ThrowIfNull(array, nameof(array)); foreach (var item in (global::System.Collections.Generic.IEnumerable<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>)this) array[arrayIndex++] = item; }\n"
                    "bool global::System.Collections.Generic.ICollection<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>.Remove(global::System.Collections.Generic.KeyValuePair<%1$s, %2$s> item) { if (!((global::System.Collections.Generic.ICollection<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>)this).Contains(item)) return false; this.Remove(item.Key); return true; }\n"
                    "global::System.Collections.Generic.IEnumerator<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>> global::System.Collections.Generic.IEnumerable<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>.GetEnumerator() { foreach (var entry in this.EntrySet()) yield return new global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>(entry.Key, entry.Value); }\n"
                    "global::System.Collections.IEnumerator global::System.Collections.IEnumerable.GetEnumerator() => ((global::System.Collections.Generic.IEnumerable<global::System.Collections.Generic.KeyValuePair<%1$s, %2$s>>)this).GetEnumerator();")
                   key-type value-type))])

              "java.awt.Paint"
              (when (.hasModifier ^CtModifiable owner ModifierKind/ABSTRACT)
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
                      "global::DripSharp.Runtime.PdfCartonRenderingHints hints);")))
                  (when (empty? (.getMethodsByName owner "getTransparency"))
                    (raw "public abstract int GetTransparency();"))]))

              "java.awt.PaintContext"
              (when (.hasModifier ^CtModifiable owner ModifierKind/ABSTRACT)
                (remove
                 nil?
                 [(when (empty? (.getMethodsByName owner "getRaster"))
                    (raw
                     (str
                      "public abstract global::DripSharp.Runtime.JavaRaster "
                      "GetRaster(int x, int y, int width, int height);")))]))

              [])))
         vec)))

(defn- interface-redeclaration-contract-nodes [_ctx ^CtType owner]
  ;; A redeclared abstract method already satisfies its inherited interface
  ;; contract. An explicit forwarding body would be a default interface
  ;; implementation, which netstandard2.0 cannot encode.
  (when (interface-type? owner) []))

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
                              (executable-anonymous-calls ctx constructor))
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
        constructor-declaration (:declaration constructor-occurrence)
        constructor-target-reference
        (when (and (instance? CtClass owner)
                   (instance? CtConstructor constructor-declaration)
                   (not (identical?
                         owner
                         (.getDeclaringType
                          ^CtConstructor constructor-declaration))))
          (.getSuperclass ^CtClass owner))
        constructor-parameter-types
        (when constructor-invocation
          (executable-parameter-types
           constructor-declaration
           (.getExecutable ^CtInvocation constructor-invocation)
           constructor-target-reference))
        constructor-arguments
        (when constructor-invocation
          (mapv
           (fn [index ^CtExpression argument]
             (argument-value-node
              ctx argument
              (when (seq constructor-parameter-types)
                (nth constructor-parameter-types
                     (min index (dec (count constructor-parameter-types)))))
              (declared-call-parameter-type
               (:declaration constructor-occurrence)
               (count (.getArguments ^CtInvocation constructor-invocation))
               index argument)
              (translated-node ctx argument)
              false))
           (range)
           (.getArguments ^CtInvocation constructor-invocation)))
        base-outer? (non-static-member-superclass? owner)
        initializer-kind
        (cond
          constructor-invocation
          (if (and (= :project (:origin constructor-occurrence))
                   (instance? CtConstructor (:declaration constructor-occurrence))
                   (identical? owner
                               (.getDeclaringType
                                ^CtConstructor (:declaration constructor-occurrence))))
            "this"
            "base")
          base-outer? "base")
        inner? (non-static-member-class? owner)
        outer-field-name (:outer-field-name ctx)
        outer-parameter-node
        (when inner?
          (sequence-node
           [(owner-type-node ctx (.getDeclaringType owner))
            (raw (str " " outer-field-name))]))
        initialization-events (:instance-initialization-events ctx)
        body-node
        (if (or inner? constructor-invocation (seq initialization-events))
          (let [initializers
                (if (= "this" initializer-kind)
                  []
                  (into
                   (if inner?
                     [(raw (str "this." outer-field-name " = "
                                outer-field-name ";"))]
                     [])
                   (map #(instance-initialization-node ctx %)
                        initialization-events)))
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
               (when (or constructor-invocation base-outer?)
                 (sequence-node
                  [(raw (str " : " initializer-kind "("))
                   (sequence-node
                    (cond->
                     (vec constructor-arguments)
                      (or (and inner? (= "this" initializer-kind))
                          (and base-outer? (= "base" initializer-kind)))
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
             (filter #(and (instance? CtAnonymousExecutable %)
                           (.hasModifier ^CtAnonymousExecutable %
                                         ModifierKind/STATIC)))
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

(defn- instance-initializer-node
  [ctx ^CtType owner ^CtAnonymousExecutable initializer]
  (let [rule :java-library.declaration/instance-initializer
        id (register-member! ctx owner initializer ".initializer" rule)]
    (csharp/with-source
      (raw "/* merged instance initializer */")
      (source-ref initializer rule
                  {:declaration-id id :declaration-kind :initializer}))))

(defn- initializer-node
  [ctx ^CtType owner ^CtAnonymousExecutable initializer]
  (if (.hasModifier initializer ModifierKind/STATIC)
    (static-initializer-node ctx owner initializer)
    (instance-initializer-node ctx owner initializer)))

(defn- abstract-covariant-bridge-name
  [_ctx ^CtType owner ^CtMethod method]
  (let [[^CtType root-owner ^CtMethod root-method]
        (override-family-root owner method)
        emitted-name (pascal (.getSimpleName root-method))
        base (str "__DripSharpCovariantBridge" emitted-name)
        reserved
        (->> (model-all-types (.getModel (.getFactory owner)))
             (mapcat
              (fn [^CtType candidate-owner]
                (mapcat
                 (fn [^CtMethod candidate-method]
                   (let [source-name (.getSimpleName candidate-method)]
                     [(identifier source-name) (pascal source-name)]))
                 (.getMethods candidate-owner))))
             set)]
    (loop [suffix nil]
      (let [candidate (str base suffix)]
        (if (contains? reserved candidate)
          (recur (if suffix (inc suffix) 2))
          candidate)))))

(defn- method-argument-nodes [^CtMethod method]
  (mapv #(raw (identifier (.getSimpleName ^CtParameter %)))
        (.getParameters method)))

(defn- abstract-covariant-forwarding-body-node
  [ctx ^CtType owner ^CtMethod method]
  (let [lifted (wildcard-method-type-parameters method)]
    (csharp/block
     [(sequence-node
       [(raw "return ((")
        (method-return-type-node ctx owner method)
        (raw ")(this.")
        (raw (abstract-covariant-bridge-name ctx owner method))
        (method-formals-node method lifted)
        (raw "(")
        (sequence-node (method-argument-nodes method) ", ")
        (raw ")));")])])))

(defn- abstract-covariant-bridge-hook-node
  [ctx ^CtType owner ^CtMethod method]
  (let [source-abstract? (.hasModifier method ModifierKind/ABSTRACT)
        override? (some? (superclass-method owner method))
        lifted (wildcard-method-type-parameters method)
        formals (vec (.getFormalCtTypeParameters method))
        signature
        (sequence-node
         [(raw (str "protected "
                    (when source-abstract? "abstract ")
                    (when override? "override ")))
          (override-family-return-type-node ctx owner method)
          (raw (str " " (abstract-covariant-bridge-name ctx owner method)))
          (method-formals-node method lifted)
          (raw "(")
          (sequence-node
           (mapv #(parameter-node ctx % lifted) (.getParameters method)) ", ")
          (raw ")")
          (when-not override?
            (sequence-node
             [(constraints-node ctx formals)
              (lifted-wildcard-constraints-node ctx lifted)]))])
        body
        (when-not source-abstract?
          (csharp/block
           [(sequence-node
             [(raw "return this.")
              (raw (method-name ctx owner method))
              (method-formals-node method lifted)
              (raw "(")
              (sequence-node (method-argument-nodes method) ", ")
              (raw ");")])]))]
    (csharp/declaration
     signature body
     {:declaration-kind :covariant-bridge
      :name (abstract-covariant-bridge-name ctx owner method)
      :source-name (.getSimpleName method)
      :source-qualified-name (.getQualifiedName owner)
      :parameter-count (count (.getParameters method))})))

(defn- member-node [ctx ^CtType owner member]
  (cond
    (instance? CtEnumValue member) (field-node ctx owner member)
    (instance? CtField member) (field-node ctx owner member)
    (instance? CtMethod member) (method-node ctx owner member)
    (instance? CtConstructor member) (constructor-node ctx owner member)
    (instance? CtType member) (emit-root ctx member)
    (instance? CtAnonymousExecutable member)
    (initializer-node ctx owner member)
    :else (unsupported! "Java library member shape is not implemented" member)))

(defn- derived-body-context [ctx additions]
  (let [ctx-holder (atom nil)
        derived (merge ctx additions)
        derived (assoc derived :body-context
                       (create-body-context
                        (:resolved-model ctx) ctx-holder
                        (:runtime-capabilities ctx)))]
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
      (when (and (= 1 (count methods))
                 (empty? (.getFormalCtTypeParameters
                          ^CtMethod (first methods))))
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
        void? (= "void" (.getQualifiedName (.getType method)))
        default-body-ctx
        (derived-body-context ctx {:inlined-default-interface-body? true})
        default-methods
        (->> (concat
              (map (fn [^CtMethod candidate]
                     [(.getReference interface) candidate])
                   (.getMethods interface))
              (interface-methods interface))
             (filter
              (fn [[_ ^CtMethod candidate]]
                (and (some? (.getBody candidate))
                     (not (.hasModifier candidate ModifierKind/STATIC))
                     (not= (.getSignature method)
                           (.getSignature candidate)))))
             (reduce
              (fn [methods [_ ^CtMethod candidate :as entry]]
                (assoc methods (.getSignature candidate) entry))
              (sorted-map))
             vals)
        default-nodes
        (mapv
         (fn [[^CtTypeReference interface-reference ^CtMethod candidate]]
           (let [candidate-parameters (vec (.getParameters candidate))
                 formals (vec (.getFormalCtTypeParameters candidate))
                 lifted (wildcard-method-type-parameters candidate)]
             (sequence-node
              [(raw "public ")
               (declaration-type-node
                ctx candidate
                (substituted-interface-reference
                 interface-reference (.getType candidate)))
               (raw (str " " (method-name ctx
                                           (.getDeclaringType candidate)
                                           candidate)))
               (method-formals-node candidate lifted)
               (raw "(")
               (sequence-node
                (mapv #(parameter-node ctx % lifted) candidate-parameters) ", ")
               (raw ")")
               (constraints-node ctx formals)
               (lifted-wildcard-constraints-node ctx lifted)
               (raw " ")
               (translated-node default-body-ctx (.getBody candidate))])))
         default-methods)]
    (sequence-node
     [(raw (str visibility " sealed class " adapter-name))
      (type-formals-node ctx interface)
      (raw " : ") (owner-type-node ctx interface) (raw " {\nprivate readonly ")
      delegate-type (raw (str " implementation;\n\n" visibility " "))
      (raw adapter-name)
      (raw "(") delegate-type (raw " implementation) {\nthis.implementation = implementation;\n}\n\npublic ")
      (declaration-type-node ctx method (.getType method))
      (raw (str " " (method-name ctx interface method) "("))
      (sequence-node parameter-nodes ", ")
      (raw ") {\n")
      (when-not void? (raw "return "))
      (raw "this.implementation(") (sequence-node arguments ", ") (raw ");\n}")
      (when (seq default-nodes)
        (sequence-node [(raw "\n\n")
                        (sequence-node default-nodes "\n\n")]))
      (raw "\n}")])))

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
        initialization-events (:instance-initialization-events ctx)
        rule :java-library.declaration/implicit-member-constructor
        emitted-visibility (implicit-constructor-visibility type constructor)
        id (register-member! ctx type constructor name rule 1 emitted-visibility)]
    (csharp/with-source
      (sequence-node
       [(raw (str emitted-visibility " " name "("))
        (owner-type-node ctx outer) (raw (str " " outer-field-name ")"))
        (when (non-static-member-superclass? type)
          (raw (str " : base(" outer-field-name ")")))
        (raw " {\nthis.")
        (raw outer-field-name) (raw (str " = " outer-field-name ";\n"))
        (sequence-node
         (mapv #(instance-initialization-node ctx %)
               initialization-events)
         "\n")
        (when (seq initialization-events) (raw "\n"))
        (raw "}")])
      (source-ref constructor rule
                  {:declaration-id id :declaration-kind :constructor}))))

(defn- functional-adapter-node [ctx ^CtType type ^CtTypeReference interface]
  (let [method-name (case (.getQualifiedName interface)
                      "java.lang.Runnable"
                      (if (csharp-public-names? ctx) "Run" "run")
                      "java.util.concurrent.Callable"
                      (if (csharp-public-names? ctx) "Call" "call")
                      "java.util.function.Supplier"
                      (if (csharp-public-names? ctx) "Get" "get")
                      "java.util.function.UnaryOperator"
                      (if (csharp-public-names? ctx) "Apply" "apply"))]
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
                  (anonymous-byte-array-output-stream? call)
                  (anonymous-linked-hash-map? call)
                  (anonymous-project-type? ctx call))
      (unsupported! (str "Anonymous class base type "
                         (pr-str (some-> call .getType .getQualifiedName))
                         " at " (pr-str (spoon/source-location anonymous-class))
                         " requires exact Callable, Iterator, X509TrustManager, FilterOutputStream, ByteArrayOutputStream, LinkedHashMap, or project-class semantics")
                    anonymous-class))
    (when (and (or (anonymous-iterator? call)
                   (anonymous-x509-trust-manager? call))
               (seq (.getArguments call)))
      (unsupported! "Anonymous java.util.Iterator construction cannot have base arguments"
                    call))
    (let [name (anonymous-class-name call)
          base-arguments (vec (.getArguments call))
          base-parameter-types
          (constructor-call-parameter-types
           (some-> call .getExecutable .getDeclaration) call)
          captures (anonymous-captures anonymous-class)
          base-outer? (some-> call .getType .getTypeDeclaration
                              non-static-member-class?)
          outer? (or (anonymous-uses-outer? anonymous-class owner)
                     base-outer?)
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
                  [(type-node ctx (or (nth base-parameter-types index nil)
                                      (.getType argument)))
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
              (when (or (seq base-arguments) base-outer?)
                (sequence-node
                 [(raw " : base(")
                  (sequence-node
                   (cond->
                    (mapv #(raw (str "baseArgument" %))
                          (range (count base-arguments)))
                     base-outer? (conj (raw "__outer")))
                   ", ")
                  (raw ")")]))
              (raw " {")
              (when (seq constructor-assignments) (raw "\n"))
              (sequence-node constructor-assignments "\n")
              (when (seq constructor-assignments) (raw "\n"))
              (raw "}")])
            member-nodes (mapv #(member-node derived anonymous-class %) members)
            iterator-default-nodes
            (when (and (anonymous-iterator? call)
                       (empty? (.getMethodsByName anonymous-class "remove")))
              [(raw
                (str
                 "public void Remove() {"
                 "throw new global::System.NotSupportedException("
                 "\"Iterator removal is not supported.\");}"))])
            declaration
            (csharp/with-source
              (sequence-node
               [(raw (str "private sealed class " name " : "))
                (type-node ctx (.getType call))
                (raw " {\n")
                (sequence-node
                 (vec (concat capture-fields
                              [constructor]
                              member-nodes
                              iterator-default-nodes))
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
                    "annotation:java.lang.SafeVarargs"
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
        base-nodes
        (into
         (vec (keep #(base-type-node ctx type %) bases))
         (when-let [extra-base-nodes (:destination-base-type-nodes ctx)]
           (extra-base-nodes ctx type)))
        functional-bases
        (when (instance? CtClass type)
          (filter #(contains? functional-interface-types (.getQualifiedName ^CtTypeReference %))
                  bases))
        all-members (explicit-members type)
        selected-declarations (:selected-declarations ctx)
        type-selection
        (when selected-declarations
          (.get ^IdentityHashMap selected-declarations type))
        members
        (if (or (nil? selected-declarations)
                (= :body (:expansion type-selection)))
          all-members
          (filterv
           #(.containsKey ^IdentityHashMap selected-declarations %)
           all-members))
        static-companion-members
        (filterv #(interface-static-companion-member? ctx %) members)
        members
        (filterv #(not (interface-static-companion-member? ctx %)) members)
        inner? (non-static-member-class? type)
        outer-field-name (when inner? (generated-outer-field-name type))
        nested-instance-class?
        (some #(and (instance? CtType %)
                    (non-static-member-class? %))
              members)
        instance-initializers
        (->> members
             (filter #(and (instance? CtAnonymousExecutable %)
                           (not (.hasModifier ^CtAnonymousExecutable %
                                              ModifierKind/STATIC))))
             vec)
        deferred-fields
        (->> members
             (filter #(and (instance? CtField %)
                           (not (.hasModifier ^CtField % ModifierKind/STATIC))
                           (some? (.getDefaultExpression ^CtField %))
                           (or (seq instance-initializers)
                               nested-instance-class?
                               (initializer-uses-this? %)
                               (seq (field-anonymous-calls ctx %)))))
             vec)
        initialization-events
        (if (seq instance-initializers)
          (->> members
               (filter #(or
                         (and (instance? CtField %)
                              (not (.hasModifier ^CtField % ModifierKind/STATIC))
                              (some? (.getDefaultExpression ^CtField %)))
                         (and (instance? CtAnonymousExecutable %)
                              (not (.hasModifier ^CtAnonymousExecutable %
                                                 ModifierKind/STATIC)))))
               vec)
          deferred-fields)
        explicit-constructors (filter #(instance? CtConstructor %) members)
        implicit-constructor
        (some #(when (and (instance? CtConstructor %)
                          (.isImplicit ^CtConstructor %))
                 %)
              (.getTypeMembers type))
        selected-implicit-constructor?
        (and selected-declarations
             implicit-constructor
             (.containsKey ^IdentityHashMap selected-declarations
                           implicit-constructor))
        emit-implicit-constructor?
        (and (or (nil? selected-declarations)
                 (= :body (:expansion type-selection)))
             (not inner?)
             implicit-constructor
             (empty? explicit-constructors)
             (or (seq initialization-events)
                 (and (instance? CtClass type)
                      (.hasModifier ^CtModifiable type
                                    ModifierKind/ABSTRACT))
                 (.hasModifier ^CtModifiable type ModifierKind/PROTECTED)
                 (public-derived-type? type)))
        suppress-shell-default-constructor?
        (and selected-declarations
             (= :shell (:expansion type-selection))
             (instance? CtClass type)
             (not inner?)
             (empty? explicit-constructors)
             (not selected-implicit-constructor?))
        _ (when (and (seq initialization-events)
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
                     :deferred-field-initializers deferred-fields
                     :instance-initialization-events initialization-events})
        member-nodes (if-let [emit-members (:emit-members ctx)]
                       (emit-members member-ctx type members)
                       (mapv #(member-node member-ctx type %) members))
        abstract-covariant-bridge-hook-nodes
        (->> members
             (filter #(and (instance? CtMethod %)
                           (abstract-base-covariant-bridge-family?
                            type ^CtMethod %)))
             (mapv #(abstract-covariant-bridge-hook-node
                     member-ctx type ^CtMethod %)))
        static-companion-member-nodes
        (mapv #(member-node
                (assoc member-ctx :interface-static-companion? true)
                type %)
              static-companion-members)
        static-companion-anonymous-types
        (mapv #(emit-anonymous-type
                (assoc member-ctx :interface-static-companion? true)
                type %)
              (mapcat #(if (instance? CtField %)
                         (field-anonymous-calls member-ctx %)
                         [])
                      static-companion-members))
        interface-contracts (interface-contract-nodes member-ctx type)
        interface-redeclaration-contracts
        (interface-redeclaration-contract-nodes member-ctx type)
        runtime-interface-contracts
        (runtime-interface-contract-nodes member-ctx type)
        java-map-entry-contracts
        (java-map-entry-contract-nodes member-ctx type)
        field-anonymous-types
        (mapv #(emit-anonymous-type member-ctx type %)
              (mapcat #(if (instance? CtField %)
                         (field-anonymous-calls member-ctx %)
                         [])
                      members))
        member-nodes
        (cond-> (into (vec member-nodes)
                      (concat abstract-covariant-bridge-hook-nodes
                              interface-contracts
                              interface-redeclaration-contracts
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
          suppress-shell-default-constructor?
          ;; A public CLR class with no emitted constructor acquires a public
          ;; parameterless constructor even when the selected Java dependency
          ;; shell has no constructor in its surface. Keep that compiler-only
          ;; member inside the generated assembly.
          (conj (raw (str "internal " name "() {}")))
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
           (type-formals-node ctx type)
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
      (when (seq static-companion-member-nodes)
        (sequence-node
         [(raw (str (emitted-type-visibility type) " static class "
                    (interface-static-companion-name type) " {\n"))
          (sequence-node
           (into static-companion-member-nodes
                 static-companion-anonymous-types)
           "\n\n")
          (raw "\n}")]))
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
   (mapv
    (fn [area]
      {:source (str "runtime/DripSharp.JavaCompat." area ".cs")
       :destination (str "DripSharp/Runtime/JavaCompat/" area ".cs")
       :strategy :reviewable-java-compatibility-source
       :missing-kind :missing-java-compatibility-source
       :missing-message "Java compatibility source is missing"})
    ["Java.IO"
     "Java.Lang"
     "Java.Math"
     "Java.Net"
     "Java.Nio"
     "Java.Sql"
     "Java.Security"
     "Java.Text"
     "Java.Time"
     "Java.Util"
     "Java.Util.Concurrent"
     "Java.Util.Regex"
     "Java.Xml"
     "NetStandard"])
   :java-regex-unicode
   {:source "runtime/DripSharp.JavaRegexUnicodeData.cs"
    :destination "DripSharp/Runtime/JavaRegexUnicodeData.cs"
    :strategy :generated-java-compatibility-data
    :missing-kind :missing-java-compatibility-source
    :missing-message "Java compatibility source is missing"}})

(defn- legal-assets
  [workspace-root configuration]
  (mapv
   (fn [{:keys [kind source destination source-sha256]}]
     (let [file (paths/resolve-path (paths/absolute workspace-root) source)
           actual (when (paths/regular-file? file)
                    (util/sha256-file file))]
       (when-not (= source-sha256 actual)
         (fail! "Configured Java-library legal input is missing or changed"
                {:kind :java-library-legal-input-mismatch
                 :package-id (get-in configuration [:package :id])
                 :legal-kind kind
                 :path (str file)
                 :expected source-sha256
                 :actual actual}))
       {:source source
        :destination destination
        :strategy (keyword "java-library.legal" (name kind))
        :missing-kind :missing-java-library-legal-input
        :missing-message
        "Configured Java-library legal input is missing"}))
   (:legal-files configuration)))

(defn- bridge-assets [{:keys [workspace-root configuration]}]
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
    (into
     (vec
      (mapcat
       (fn [capability]
         (let [assets
               (or (get bridge-capabilities capability)
                   (fail! "Java library destination selected an unknown capability"
                          {:kind :unknown-java-library-capability
                           :capability capability}))]
           (if (vector? assets) assets [assets])))
       (sort embedded-capabilities)))
     (when (= :java-library (:product-family configuration))
       (legal-assets workspace-root configuration)))))

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
   :runtime-capabilities
   {:labeled-control-flow
    {:exception-type
     "global::DripSharp.Runtime.JavaLabeledControlFlowException"}}
   :orchestration {:validate-project-input! validate-project-input!}
   :rules
   {:structural-declarations
    {:create-template emission-template
     :create-context context
     :emit-root-node emit-declaration-root-node
     :translate-member translate-declaration-member
     :merge-context! merge-context!
     :context-results context-results}
    :resolved-mappings
    {:type-node type-node
     :create-body-context create-body-context
     :annotation-decisions annotation-decisions
     :declarative-mapping-registries declarative-mapping-registries
     :declarative-mapping-required? declarative-mapping-required?}
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

(defn- read-retained-surface!
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

(defn- read-surface!
  [workspace {:keys [compiled-contract-file] :as specification}]
  (if (= #{:compiled-contract-file} (set (keys specification)))
    (let [compiled-file (paths/resolve-path (paths/absolute workspace)
                                            compiled-contract-file)]
      (when-not (paths/regular-file? compiled-file)
        (fail! "Java library compiled public-surface contract is missing"
               {:kind :missing-compiled-java-library-surface-contract
                :file (str compiled-file)}))
      {:derivation :resolved-spoon-model
       :compiled-contract-file compiled-file
       :rows nil
       :seeds []})
    (read-retained-surface! workspace specification)))

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
  ([^CtType type name parameter-count systematic-adaptation]
   (synthetic-surface-entry
    type name parameter-count "public" systematic-adaptation))
  ([^CtType type name parameter-count visibility systematic-adaptation]
   (let [adaptation-kind
         (if (map? systematic-adaptation)
           (:kind systematic-adaptation)
           systematic-adaptation)
         shape
         {:kind "method"
          :owner (canonical-owner (.getQualifiedName type))
          :name name
          :parameter-count parameter-count
          :visibility visibility}]
     {:declaration type
      :synthetic? true
      :systematic-adaptation adaptation-kind
      :shape shape
      :row (surface-row type shape systematic-adaptation)})))

(defn- closure-declaration-selected?
  [resolved-model ^CtElement declaration]
  (if-let [declarations (:declarations resolved-model)]
    (contains? declarations (spoon/declaration-key declaration))
    true))

(defn- closure-type-body-selected?
  [resolved-model ^CtType type]
  (if-let [declarations (:declarations resolved-model)]
    (= :body (:expansion
              (get declarations (spoon/declaration-key type))))
    true))

(defn- closure-type-members
  [resolved-model ^CtType type]
  (let [members (vec (.getTypeMembers type))]
    (if (closure-type-body-selected? resolved-model type)
      members
      (filterv #(closure-declaration-selected? resolved-model %) members))))

(defn- closure-enum-values
  [resolved-model ^CtEnum type]
  (let [values (vec (.getEnumValues type))]
    (if (closure-type-body-selected? resolved-model type)
      values
      (filterv #(closure-declaration-selected? resolved-model %) values))))

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
       (filter #(closure-declaration-selected? resolved-model %))
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
                          (closure-type-members resolved-model type))]
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
                          (closure-enum-values resolved-model ^CtEnum type))
                         (map (fn [[name parameter-count adaptation]]
                                (synthetic-surface-entry
                                 type name parameter-count adaptation))
                              (cond-> [["values" 0 :java-enum-values] ["valueOf" 1 :java-enum-value-of]] (not (some #(and (instance? CtMethod %) (= "toString" (.getSimpleName ^CtMethod %)) (empty? (.getParameters ^CtMethod %))) (.getTypeMembers type))) (conj ["ToString" 0 :java-enum-name-to-string])))))
                      (when (instance? CtClass type)
                        (concat
                         (map
                          (fn [^CtMethod method]
                            (synthetic-surface-entry
                             type
                             (abstract-covariant-bridge-name nil type method)
                             (count (.getParameters method))
                             "protected"
                             {:kind :netstandard-abstract-covariant-return-bridge
                              :identity
                              (str
                               "netstandard-abstract-covariant-return-bridge:"
                               (spoon/declaration-key method))}))
                          (filter
                           #(and
                             (instance? CtMethod %)
                             (abstract-base-covariant-bridge-family?
                              type ^CtMethod %))
                           (closure-type-members resolved-model type)))
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
                         (keep
                          (fn [[^CtTypeReference interface-reference
                                ^CtMethod interface-method]]
                            (let [default-method
                                  (matching-default-interface-method
                                   type interface-method)]
                              (when (and (not (.hasModifier
                                               interface-method
                                               ModifierKind/STATIC))
                                         (nil? (matching-class-method
                                                type interface-method))
                                         (not (inherited-default-interface-method?
                                               type interface-method))
                                         (or default-method
                                             (and
                                              (nil? (.getBody interface-method))
                                              (.hasModifier
                                               ^CtModifiable type
                                               ModifierKind/ABSTRACT))))
                                (assoc
                                 (synthetic-surface-entry
                                  type
                                  (.getSimpleName interface-method)
                                  (count (.getParameters interface-method))
                                  {:kind
                                   (if default-method
                                     :java-inherited-default-interface-contract
                                     :java-abstract-interface-contract)
                                   :identity
                                   (str
                                    (if default-method
                                      "java-inherited-default-interface-contract:"
                                      "java-abstract-interface-contract:")
                                    (.getQualifiedName interface-reference)
                                    "#" (.getSignature interface-method))})
                                 :interface-method interface-method))))
                          (interface-methods type)))))))))
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
   :java-inherited-default-interface-contract
   "A Java class receives a public CLR method containing an inherited Java interface default body."
   :java-runtime-abstract-interface-contract
   "A Java abstract class receives CLR abstract declarations for mapped runtime-interface members."
   :netstandard-abstract-covariant-return-bridge
   "A Java covariant return over an abstract base member receives a protected invariant CLR dispatch hook for netstandard2.0."
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
                                        :visibility
                                        (get-in evidence [:row :visibility])
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
