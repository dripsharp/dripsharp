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
            [dripsharp.java-project :as project-emission]
            [dripsharp.java-types :as java-types]
            [dripsharp.java-translate :as java]
            [dripsharp.paths :as paths]
            [dripsharp.spoon :as spoon])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Base64 IdentityHashMap WeakHashMap]
           [spoon.reflect.code CtArrayRead CtArrayWrite CtAssert CtAssignment
            CtBinaryOperator CtBlock CtBreak CtCase CtCatch CtCatchVariable CtComment
            CtConditional CtConstructorCall CtContinue CtDo CtExecutableReferenceExpression
            CtExpression CtFieldRead CtFieldWrite
            CtFor CtForEach CtIf CtInvocation CtLambda CtLiteral CtLocalVariable
            CtNewArray CtOperatorAssignment CtReturn CtStatement CtSuperAccess CtThisAccess CtThrow CtTry
            CtSwitch CtSynchronized CtTryWithResource CtTypeAccess CtUnaryOperator CtVariableAccess CtWhile
            CtVariableRead CtVariableWrite]
           [spoon.reflect.declaration CtAnnotation CtAnnotationMethod CtAnnotationType CtAnonymousExecutable CtClass CtConstructor CtElement
            CtEnum CtEnumValue CtExecutable CtField CtInterface CtMethod
            CtModifiable CtParameter CtType CtTypeParameter ModifierKind]
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

(defn- identifier [value]
  (let [clean (str/replace (str value) #"[^A-Za-z0-9_]" "_")
        clean (if (re-matches #"[0-9].*" clean) (str "_" clean) clean)]
    (if (contains? csharp-keywords clean) (str "@" clean) clean)))

(defn- pascal [value]
  (let [value (identifier value)]
    (str (str/upper-case (subs value 0 1)) (subs value 1))))

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

(declare type-node body-context functional-expression-node
         functional-interface-method)

(defn- context [options]
  (let [ctx-holder (atom nil)
        ctx (assoc options
                   :emitted (IdentityHashMap.)
                   :declarations (atom [])
                   :diagnostics (atom [])
                   :body-translations (atom []))
        ctx (assoc ctx :body-context (body-context ctx-holder
                                                   (:resolved-model options)))]
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

(declare type-node)

(def ^:private raw-generic-type-nodes
  {"java.util.Collection" "global::System.Collections.ICollection"
   "java.util.Comparator" "global::System.Collections.IComparer"
   "java.util.List" "global::System.Collections.IList"
   "java.util.Map" "global::System.Collections.IDictionary"
   "java.util.Set" "global::System.Collections.ICollection"})

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

(defn- type-node [ctx ^CtTypeReference reference]
  (let [occurrence (occurrence! ctx reference :type)
        node
        (cond
          (instance? CtArrayTypeReference reference)
          (sequence-node [(type-node ctx (.getComponentType
                                          ^CtArrayTypeReference reference))
                          (raw "[]")])

          (instance? CtWildcardReference reference)
          (if-let [bound (.getBoundingType ^CtWildcardReference reference)]
            (type-node ctx bound)
            (raw "object"))

          :else
          (let [[target _rule] (mapped-type-base ctx reference occurrence)
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
                      [(type-node ctx argument)
                       (type-node ctx argument)
                       (type-node ctx argument)]))

                  (and (empty? actual-arguments)
                       (instance? CtType declaration)
                       (seq (.getFormalCtTypeParameters ^CtType declaration)))
                  (mapv #(raw-project-type-argument-node ctx %)
                        (.getFormalCtTypeParameters ^CtType declaration))

                  :else
                  (mapv #(type-node ctx %) actual-arguments))]
            (if-let [raw-target
                     (when (empty? actual-arguments)
                       (get raw-generic-type-nodes
                            (.getQualifiedName reference)))]
              (raw raw-target)
              (if (and (= :project (:origin occurrence))
                       (instance? CtType declaration))
                (project-reference-node ctx reference declaration)
                (if (seq arguments)
                  (csharp/generic-name (raw target) arguments)
                  (raw target))))))
        [_target rule] (if (or (instance? CtArrayTypeReference reference)
                               (instance? CtWildcardReference reference))
                         [nil (if (instance? CtArrayTypeReference reference)
                                :dotnet.type/array
                                :dotnet.type/wildcard-bound)]
                         (mapped-type-base ctx reference occurrence))]
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

(defn- nullable-declaration? [ctx declaration]
  (and (instance? CtElement declaration)
       (resolved-annotation?
        ctx declaration "annotation:javax.annotation.Nullable")))

(def ^:private boxed-primitive-types
  #{"java.lang.Boolean" "java.lang.Byte" "java.lang.Character"
    "java.lang.Double" "java.lang.Float" "java.lang.Integer"
    "java.lang.Long" "java.lang.Short"})

(defn- boxed-primitive-reference? [^CtTypeReference reference]
  (and reference
       (contains? boxed-primitive-types (.getQualifiedName reference))))

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
     (let [parent (when (.isParentInitialized element) (.getParent element))]
       (not (instance? CtLambda parent)))

     (instance? CtMethod element)
     (let [owner (.getDeclaringType ^CtMethod element)]
       (not (or (anonymous-method? element)
                (and (instance? CtClass owner)
                     (some #(= "java.util.Iterator"
                               (.getQualifiedName ^CtTypeReference %))
                           (.getSuperInterfaces owner))))))

     (instance? CtLocalVariable element)
     (let [initializer (.getDefaultExpression ^CtLocalVariable element)]
       (or (and (instance? CtLiteral initializer)
                (nil? (.getValue ^CtLiteral initializer)))
           (and (instance? CtInvocation initializer)
                (let [occurrence
                      (occurrence! ctx (.getExecutable ^CtInvocation initializer)
                                   :executable)]
                  (or (= "executable:java.util.Map#get(java.lang.Object)"
                         (:key occurrence))
                      (and (= :project (:origin occurrence))
                           (instance? CtMethod (:declaration occurrence))
                           (not (anonymous-method? (:declaration occurrence)))))))))

     :else false)))

(defn- covariant-list-node [ctx ^CtElement element ^CtTypeReference reference]
  (when (and (= "java.util.List" (.getQualifiedName reference))
             (= 1 (count (.getActualTypeArguments reference))))
    (let [argument (first (.getActualTypeArguments reference))]
      (when (and (instance? CtWildcardReference argument)
                 (.isUpper ^CtWildcardReference argument)
                 (.getBoundingType ^CtWildcardReference argument))
        (csharp/generic-name
         (raw (if (instance? CtParameter element)
                "global::System.Collections.Generic.IEnumerable"
                "global::System.Collections.Generic.IReadOnlyList"))
         [(type-node ctx (.getBoundingType ^CtWildcardReference argument))])))))

(defn- declaration-type-node [ctx ^CtElement element ^CtTypeReference reference]
  (let [base (or (when (or (instance? CtParameter element)
                           (instance? CtMethod element))
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
  (if (and (:outer-type ctx)
           (= (.getQualifiedName ^CtType (:outer-type ctx))
              (some-> access .getType .getQualifiedName)))
    (raw (str "this." (or (:outer-field-name ctx) "__outer")))
    (raw "this")))

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

(defn- statement-expression? [^CtElement element]
  (and (instance? CtStatement element)
       (or (= "statement" (role element))
           (and (contains? #{"then" "else"} (role element))
                (.isParentInitialized element)
                (instance? CtIf (.getParent element))))))

(defn- statement-node [children ^CtStatement statement]
  (let [node (child-node children statement)]
    (if (instance? CtBlock statement)
      node
      (sequence-node [(raw "{\n") node (raw "\n}")]))))

(defn- labeled-loop-body-node
  [context children ^CtStatement body ^CtStatement loop]
  (if-not (java/labeled-targeted? context loop :continue)
    (statement-node children body)
    (if (instance? CtBlock body)
      (child-node children body)
      (sequence-node
       [(raw "{\n")
        (child-node children body)
        (raw (str "\n"
                  (java/labeled-target-name context loop :continue)
                  ":;\n}"))]))))

(defn- escape-string [value]
  (str "\""
       (-> (str value)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\r" "\\r")
           (str/replace "\n" "\\n")
           (str/replace "\t" "\\t"))
       "\""))

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
         (str "'" (case value
                    \' "\\'"
                    \\ "\\\\"
                    \newline "\\n"
                    \return "\\r"
                    \tab "\\t"
                    (str value)) "'")
         (instance? Boolean value) (if value "true" "false")
         (instance? Long value) (str value "L")
         (instance? Float value) (str value "F")
         (instance? Double value) (str value "D")
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
    "executable:java.io.StringWriter#write(java.lang.String)"
    "executable:java.io.StringWriter#toString()"
    "executable:java.io.Writer#write(java.lang.String)"
    "executable:java.io.Writer#write(char[])"
    "executable:java.io.Writer#close()"
    "executable:java.util.Random#nextBytes(byte[])"
    "executable:java.util.Random#nextInt()"
    "executable:javax.crypto.Cipher#doFinal()"
    "executable:javax.crypto.Cipher#doFinal(byte[])"
    "executable:javax.crypto.Cipher#getInstance(java.lang.String)"
    "executable:javax.crypto.Cipher#getMaxAllowedKeyLength(java.lang.String)"
    "executable:javax.crypto.Cipher#init(int,java.security.Key)"
    "executable:javax.crypto.Cipher#init(int,java.security.Key,java.security.spec.AlgorithmParameterSpec)"
    "executable:javax.crypto.Cipher#update(byte[],int,int)"
    "executable:java.lang.Boolean#parseBoolean(java.lang.String)"
    "executable:java.lang.Boolean#getBoolean(java.lang.String)"
    "executable:java.lang.Byte#toUnsignedInt(byte)"
    "executable:java.lang.Character#digit(char,int)"
    "executable:java.lang.Character#charCount(int)"
    "executable:java.lang.Character#getName(int)"
    "executable:java.lang.Character#isDefined(int)"
    "executable:java.lang.Character#getType(int)"
    "executable:java.lang.Character#getType(char)"
    "executable:java.lang.Character#isDigit(char)"
    "executable:java.lang.Character#isDigit(int)"
    "executable:java.lang.Character#isBmpCodePoint(int)"
    "executable:java.lang.Character#isMirrored(char)"
    "executable:java.lang.Character#isMirrored(int)"
    "executable:java.lang.Character#isWhitespace(char)"
    "executable:java.lang.Character#isValidCodePoint(int)"
    "executable:java.lang.Character#isSurrogatePair(char,char)"
    "executable:java.lang.Character#toString(char)"
    "executable:java.lang.Class#asSubclass(java.lang.Class)"
    "executable:java.lang.Class#cast(java.lang.Object)"
    "executable:java.lang.Double#compare(double,double)"
    "executable:java.lang.Class#getAnnotation(java.lang.Class)"
    "executable:java.lang.Class#getDeclaredConstructor(java.lang.Class[])"
    "executable:java.lang.Class#getFields()"
    "executable:java.lang.Class#getResourceAsStream(java.lang.String)"
    "executable:java.lang.Double#valueOf(java.lang.String)"
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
    "executable:java.lang.Integer#compare(int,int)"
    "executable:java.lang.Integer#compareTo(java.lang.Integer)"
    "executable:java.lang.Integer#equals(java.lang.Object)"
    "executable:java.lang.Integer#intValue()"
    "executable:java.lang.Integer#longValue()"
    "executable:java.lang.Integer#shortValue()"
    "executable:java.lang.Integer#signum(int)"
    "executable:java.lang.Long#hashCode(long)"
    "executable:java.lang.Long#longValue()"
    "executable:java.lang.Long#equals(java.lang.Object)"
    "executable:java.lang.Double#doubleValue()"
    "executable:java.lang.Boolean#booleanValue()"
    "executable:java.lang.Float#equals(java.lang.Object)"
    "executable:java.lang.Double#equals(java.lang.Object)"
    "executable:java.lang.Integer#highestOneBit(int)"
    "executable:java.lang.Integer#toHexString(int)"
    "executable:java.lang.Long#toHexString(long)"
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
    "executable:java.math.BigInteger#mod(java.math.BigInteger)"
    "executable:java.math.BigInteger#intValue()"
    "executable:java.math.BigInteger#toString(int)"
    "executable:java.math.BigInteger#toByteArray()"
    "executable:java.math.BigInteger#valueOf(long)"
    "executable:java.math.BigDecimal#intValue()"
    "executable:java.math.BigDecimal#setScale(int,java.math.RoundingMode)"
    "executable:java.math.BigDecimal#stripTrailingZeros()"
    "executable:java.math.BigDecimal#toPlainString()"
    "executable:java.math.BigDecimal#valueOf(double)"
    "executable:java.lang.Number#doubleValue()"
    "executable:java.lang.Number#floatValue()"
    "executable:java.lang.Number#intValue()"
    "executable:java.lang.Number#longValue()"
    "executable:java.lang.Object#clone()"
    "executable:java.lang.Object#equals(java.lang.Object)"
    "executable:java.lang.Object#hashCode()"
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
    "executable:java.lang.StringBuilder#deleteCharAt(int)"
    "executable:java.lang.StringBuilder#reverse()"
    "executable:java.lang.StringBuilder#setLength(int)"
    "executable:java.lang.AbstractStringBuilder#setLength(int)"
    "executable:java.lang.System#getProperty(java.lang.String)"
    "executable:java.lang.System#getProperty(java.lang.String,java.lang.String)"
    "executable:java.lang.System#getenv(java.lang.String)"
    "executable:java.lang.System#lineSeparator()"
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
    "executable:java.nio.file.Paths#get(java.lang.String,java.lang.String[])"
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
    "executable:java.util.Arrays#toString(float[])"
    "executable:java.util.Arrays#toString(int[])"
    "executable:java.util.Arrays#toString(java.lang.Object[])"
    "executable:java.util.List#listIterator()"
    "executable:java.util.List#listIterator(int)"
    "executable:java.util.List#containsAll(java.util.Collection)"
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
    "executable:java.text.DecimalFormatSymbols#getInstance(java.util.Locale)"
    "executable:java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)"
    "executable:java.text.NumberFormat#format(long)"
    "executable:java.text.NumberFormat#format(double)"
    "executable:java.text.NumberFormat#getMaximumFractionDigits()"
    "executable:java.text.NumberFormat#getNumberInstance(java.util.Locale)"
    "executable:java.text.NumberFormat#setMaximumFractionDigits(int)"
    "executable:java.text.NumberFormat#setGroupingUsed(boolean)"
    "executable:java.util.TimeZone#getID()"
    "executable:java.util.TimeZone#getOffset(long)"
    "executable:java.util.TimeZone#setRawOffset(int)"
    "executable:java.util.Collection#add(java.lang.Object)"
    "executable:java.util.Collection#isEmpty()"
    "executable:java.util.Collection#removeAll(java.util.Collection)"
    "executable:java.util.Collection#retainAll(java.util.Collection)"
    "executable:java.util.AbstractCollection#removeAll(java.util.Collection)"
    "executable:java.util.AbstractCollection#retainAll(java.util.Collection)"
    "executable:java.util.ArrayList#add(java.lang.Object)"
    "executable:java.util.LinkedList#add(java.lang.Object)"
    "executable:java.util.LinkedList#addFirst(java.lang.Object)"
    "executable:java.util.ArrayList#clear()"
    "executable:java.util.ArrayList#ensureCapacity(int)"
    "executable:java.util.Collections#sort(java.util.List)"
    "executable:java.util.Collections#sort(java.util.List,java.util.Comparator)"
    "executable:java.util.Collections#reverse(java.util.List)"
    "executable:java.util.Base64#getDecoder()"
    "executable:java.util.Base64$Decoder#decode(java.lang.String)"
    "executable:java.util.Collections#max(java.util.Collection)"
    "executable:java.util.Collections#min(java.util.Collection)"
    "executable:java.util.Collections#emptyIterator()"
    "executable:java.util.Collections#emptySet()"
    "executable:java.util.Collections#newSetFromMap(java.util.Map)"
    "executable:java.util.Collections#unmodifiableSet(java.util.Set)"
    "executable:java.util.Comparator#comparing(java.util.function.Function)"
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
    "executable:java.util.regex.Matcher#replaceAll(java.lang.String)"
    "executable:java.util.regex.Matcher#start()"
    "executable:java.util.regex.Pattern#matches(java.lang.String,java.lang.CharSequence)"
    "executable:java.util.stream.Stream#anyMatch(java.util.function.Predicate)"
    "executable:java.util.stream.Stream#allMatch(java.util.function.Predicate)"
    "executable:java.util.stream.Stream#noneMatch(java.util.function.Predicate)"
    "executable:java.util.stream.Stream#distinct()"
    "executable:java.util.stream.Stream#count()"
    "executable:java.util.stream.Stream#reduce(java.util.function.BinaryOperator)"
    "executable:java.util.stream.Stream#findFirst()"
    "executable:java.util.stream.IntStream#toArray()"
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
    "field:java.io.ByteArrayOutputStream#buf"
    "field:java.io.FilterInputStream#in"
    "field:java.io.FilterOutputStream#out"
    "field:java.lang.Boolean#FALSE"
    "field:java.lang.Boolean#TRUE"
    "field:java.lang.Character#MAX_CODE_POINT"
    "field:java.lang.Character#MIN_CODE_POINT"
    "field:java.lang.Character#UNASSIGNED"
    "field:java.lang.Character#NON_SPACING_MARK"
    "field:java.lang.Character#MODIFIER_LETTER"
    "field:java.lang.Character#MODIFIER_SYMBOL"
    "field:java.lang.Float#MAX_VALUE"
    "field:java.lang.Float#MIN_NORMAL"
    "field:java.lang.Float#MIN_VALUE"
    "field:java.lang.Float#NEGATIVE_INFINITY"
    "field:java.lang.Float#POSITIVE_INFINITY"
    "field:java.lang.Integer#MIN_VALUE"
    "field:java.lang.Long#MIN_VALUE"
    "field:java.lang.Long#MAX_VALUE"
    "field:java.lang.Short#MAX_VALUE"
    "field:java.lang.Short#MIN_VALUE"
    "field:java.lang.Short#SIZE"
    "field:java.lang.Math#PI"
    "field:java.math.RoundingMode#CEILING"
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
    "field:java.nio.charset.StandardCharsets#UTF_16"
    "field:java.nio.charset.StandardCharsets#UTF_16BE"
    "field:java.nio.charset.StandardCharsets#UTF_16LE"
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
  (cond
    (and (= :intrinsic (:origin occurrence))
         (= :class-literal (:resolution occurrence)))
    "class"

    (= :project (:origin occurrence))
    (let [declaration (:declaration occurrence)]
      (cond
        (instance? CtField declaration)
        (destination-field-name ctx declaration)

        (instance? CtMethod declaration)
        (method-name ctx (.getDeclaringType ^CtMethod declaration) declaration)

        :else
        (identifier (.getSimpleName ^CtElement reference))))

    (contains? (:destination-invocation-adaptations ctx) (:key occurrence))
    (identifier (.getSimpleName ^CtElement reference))

    (contains? (:destination-field-adaptations ctx) (:key occurrence))
    (identifier (.getSimpleName ^CtElement reference))

    (and (= :dependency (:origin occurrence))
         (some->> reference
                  .getDeclaringType
                  (translated-external-type-base ctx)))
    (csharp-public-name (.getSimpleName ^CtElement reference))

    (and (= :intrinsic (:origin occurrence))
         (= :enum-synthetic-method (:resolution occurrence)))
    (identifier (.getSimpleName ^CtElement reference))

    (or (contains? extended-neutral-executable-keys (:key occurrence))
        (contains? extended-neutral-field-keys (:key occurrence)))
    (identifier (.getSimpleName ^CtElement reference))

    (contains?
     #{"executable:java.lang.Iterable#forEach(java.util.function.Consumer)"
       "executable:java.util.Collections#emptyList()"
       "executable:java.util.Collections#emptyMap()"
       "executable:java.util.Collections#singletonList(java.lang.Object)"
       "executable:java.util.Collections#synchronizedMap(java.util.Map)"
       "executable:java.util.Collections#unmodifiableList(java.util.List)"
       "executable:java.util.Collections#unmodifiableMap(java.util.Map)"
       "executable:java.net.URI#getHost()"
       "executable:java.net.URI#getPort()"
       "executable:java.net.URI#getScheme()"
       "executable:java.net.URI#getUserInfo()"
       "executable:java.net.URI#getRawPath()"
       "executable:java.net.URI#getPath()"
       "executable:java.net.URI#getRawQuery()"
       "executable:java.net.URI#getRawFragment()"
       "executable:java.net.URI#equals(java.lang.Object)"
       "executable:java.net.URI#toString()"
       "executable:java.net.URI#create(java.lang.String)"
       "executable:java.lang.String#toUpperCase()"
       "executable:java.lang.String#toLowerCase()"
       "executable:java.lang.String#format(java.lang.String,java.lang.Object[])"
       "executable:java.lang.String#trim()"
       "executable:java.lang.String#split(java.lang.String)"
       "executable:java.lang.String#split(java.lang.String,int)"
       "executable:java.lang.String#length()"
       "executable:java.lang.String#isEmpty()"
       "executable:java.lang.String#startsWith(java.lang.String)"
       "executable:java.lang.String#endsWith(java.lang.String)"
       "executable:java.lang.String#substring(int)"
       "executable:java.lang.String#substring(int,int)"
       "executable:java.lang.String#indexOf(int)"
       "executable:java.lang.String#indexOf(int,int)"
       "executable:java.lang.String#lastIndexOf(int)"
       "executable:java.lang.String#contains(java.lang.CharSequence)"
       "executable:java.lang.String#matches(java.lang.String)"
       "executable:java.lang.String#hashCode()"
       "executable:java.lang.String#equals(java.lang.Object)"
       "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
       "executable:java.lang.String#toCharArray()"
       "executable:java.lang.String#getBytes(java.nio.charset.Charset)"
       "executable:java.lang.String#getBytes(java.lang.String)"
       "executable:java.lang.String#getBytes()"
       "executable:java.lang.String#join(java.lang.CharSequence,java.lang.Iterable)"
       "executable:java.lang.Integer#toString(int)"
       "executable:java.lang.Integer#toString(int,int)"
       "executable:java.lang.Integer#parseInt(java.lang.String)"
       "executable:java.lang.Long#parseLong(java.lang.String)"
       "executable:java.lang.Long#parseLong(java.lang.String,int)"
       "executable:java.lang.Long#toString(long)"
       "executable:java.lang.Math#min(long,long)"
       "executable:java.lang.Math#min(int,int)"
       "executable:java.lang.Math#min(double,double)"
       "executable:java.lang.Math#min(float,float)"
       "executable:java.lang.Math#toIntExact(long)"
       "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)"
       "executable:java.lang.System#currentTimeMillis()"
       "executable:java.lang.ThreadLocal#withInitial(java.util.function.Supplier)"
       "executable:java.lang.ThreadLocal#get()"
       "executable:java.lang.ThreadLocal#set(java.lang.Object)"
       "executable:java.util.Arrays#equals(byte[],byte[])"
       "executable:java.util.Arrays#equals(int[],int[])"
       "executable:java.util.Arrays#equals(float[],float[])"
       "executable:java.util.Arrays#equals(double[],double[])"
       "executable:java.util.Arrays#hashCode(byte[])"
       "executable:java.util.Arrays#hashCode(int[])"
       "executable:java.util.Arrays#hashCode(float[])"
       "executable:java.net.Socket#getInputStream()"
       "executable:java.net.Socket#getOutputStream()"
       "executable:java.net.Socket#getRemoteSocketAddress()"
       "executable:java.net.Socket#close()"
       "executable:java.net.Socket#isClosed()"
       "executable:java.net.Socket#isConnected()"
       "executable:java.net.Socket#setSoTimeout(int)"
       "executable:java.net.ServerSocket#accept()"
       "executable:java.net.ServerSocket#close()"
       "executable:java.net.ServerSocket#isClosed()"
       "executable:java.net.InetSocketAddress#getAddress()"
       "executable:java.net.URL#openStream()"
       "executable:java.net.URLDecoder#decode(java.lang.String,java.lang.String)"
       "executable:java.security.KeyStore#getDefaultType()"
       "executable:java.security.KeyStore#getInstance(java.lang.String)"
       "executable:java.security.KeyStore#load(java.io.InputStream,char[])"
       "executable:java.security.MessageDigest#digest()"
       "executable:java.security.MessageDigest#digest(byte[])"
       "executable:java.security.MessageDigest#getInstance(java.lang.String)"
       "executable:java.security.MessageDigest#isEqual(byte[],byte[])"
       "executable:java.security.MessageDigest#update(byte)"
       "executable:java.security.MessageDigest#update(byte[])"
       "executable:java.security.MessageDigest#update(byte[],int,int)"
       "executable:javax.net.ssl.KeyManagerFactory#getDefaultAlgorithm()"
       "executable:javax.net.ssl.KeyManagerFactory#getInstance(java.lang.String)"
       "executable:javax.net.ssl.KeyManagerFactory#init(java.security.KeyStore,char[])"
       "executable:javax.net.ssl.KeyManagerFactory#getKeyManagers()"
       "executable:javax.net.ssl.TrustManagerFactory#getDefaultAlgorithm()"
       "executable:javax.net.ssl.TrustManagerFactory#getInstance(java.lang.String)"
       "executable:javax.net.ssl.TrustManagerFactory#init(java.security.KeyStore)"
       "executable:javax.net.ssl.TrustManagerFactory#getTrustManagers()"
       "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)"
       "executable:javax.net.ssl.SSLContext#init(javax.net.ssl.KeyManager[],javax.net.ssl.TrustManager[],java.security.SecureRandom)"
       "executable:javax.net.ssl.SSLSocketFactory#getDefault()"
       "executable:javax.net.ssl.SSLContext#getSocketFactory()"
       "executable:javax.net.ssl.SSLContext#getServerSocketFactory()"
       "executable:javax.net.ServerSocketFactory#createServerSocket(int)"
       "executable:javax.net.SocketFactory#createSocket()"
       "executable:javax.net.SocketFactory#createSocket(java.lang.String,int)"
       "executable:java.io.OutputStream#flush()"
       "executable:java.io.OutputStream#close()"
       "executable:java.io.FilterOutputStream#flush()"
       "executable:java.io.FilterOutputStream#close()"
       "executable:java.io.Closeable#close()"
       "executable:java.io.File#toPath()"
       "executable:java.io.File#length()"
       "executable:java.nio.file.Files#newInputStream(java.nio.file.Path,java.nio.file.OpenOption[])"
       "executable:java.io.ByteArrayOutputStream#writeTo(java.io.OutputStream)"
       "executable:java.io.ByteArrayOutputStream#toByteArray()"
       "executable:java.io.ByteArrayOutputStream#write(int)"
       "executable:java.lang.StringBuilder#append(java.lang.String)"
       "executable:java.lang.StringBuilder#append(char)"
       "executable:java.lang.StringBuilder#append(int)"
       "executable:java.lang.StringBuilder#append(long)"
       "executable:java.lang.StringBuilder#append(float)"
       "executable:java.lang.StringBuilder#append(double)"
       "executable:java.lang.StringBuilder#append(boolean)"
       "executable:java.lang.StringBuilder#insert(int,char)"
       "executable:java.lang.StringBuilder#insert(int,java.lang.String)"
       "executable:java.lang.StringBuilder#appendCodePoint(int)"
       "executable:java.lang.StringBuilder#length()"
       "executable:java.lang.AbstractStringBuilder#length()"
       "executable:java.lang.StringBuilder#toString()"
       "executable:java.lang.Integer#parseInt(java.lang.String,int)"
       "executable:java.util.Collection#stream()"
       "executable:java.lang.Object#getClass()"
       "executable:java.lang.Class#getClassLoader()"
       "executable:java.lang.Object#toString()"
       "executable:java.lang.Enum#name()"
       "executable:java.lang.Enum#ordinal()"
       "executable:java.lang.Throwable#getCause()"
       "executable:java.lang.Throwable#getMessage()"
       "executable:java.net.URISyntaxException#getMessage()"
       "executable:java.net.URISyntaxException#getReason()"
       "executable:java.net.URISyntaxException#getIndex()"
       "executable:java.lang.Throwable#printStackTrace()"
       "executable:java.time.Duration#ofSeconds(long)"
       "executable:java.time.Duration#toMillis()"
       "executable:java.time.Instant#now()"
       "executable:java.time.Instant#plus(java.time.temporal.TemporalAmount)"
       "executable:java.time.Instant#isBefore(java.time.Instant)"
       "executable:java.time.ZonedDateTime#now(java.time.ZoneId)"
       "executable:java.time.ZonedDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)"
       "executable:java.time.LocalDateTime#atZone(java.time.ZoneId)"
       "executable:java.time.LocalDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)"
       "executable:java.time.ZoneId#of(java.lang.String)"
       "executable:java.time.format.DateTimeFormatter#format(java.time.temporal.TemporalAccessor)"
       "executable:java.time.format.DateTimeFormatterBuilder#append(java.time.format.DateTimeFormatter)"
       "executable:java.time.format.DateTimeFormatterBuilder#appendOffset(java.lang.String,java.lang.String)"
       "executable:java.time.format.DateTimeFormatterBuilder#parseCaseInsensitive()"
       "executable:java.time.format.DateTimeFormatterBuilder#parseLenient()"
       "executable:java.time.format.DateTimeFormatterBuilder#parseStrict()"
       "executable:java.time.format.DateTimeFormatterBuilder#toFormatter()"
       "executable:java.net.InetAddress#getLoopbackAddress()"
       "executable:java.lang.Thread#sleep(long)"
       "executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)"
       "executable:java.util.Objects#hash(java.lang.Object[])"
       "executable:java.util.Objects#requireNonNull(java.lang.Object)"
       "executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)"
       "executable:java.util.Map#entrySet()"
       "executable:java.util.Map#containsKey(java.lang.Object)"
       "executable:java.util.TreeMap#containsKey(java.lang.Object)"
       "executable:java.util.Map#containsValue(java.lang.Object)"
       "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)"
       "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
       "executable:java.util.Map#forEach(java.util.function.BiConsumer)"
       "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
       "executable:java.util.concurrent.ConcurrentMap#putIfAbsent(java.lang.Object,java.lang.Object)"
       "executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)"
       "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#keySet()"
       "executable:java.util.LinkedHashMap#keySet()"
       "executable:java.util.TreeMap#keySet()"
       "executable:java.util.Map#values()"
       "executable:java.util.TreeMap#values()"
       "executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#putAll(java.util.Map)"
       "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.HashMap#putAll(java.util.Map)"
       "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.TreeMap#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#size()"
       "executable:java.util.TreeMap#size()"
       "executable:java.util.HashMap#size()"
       "executable:java.util.Map#get(java.lang.Object)"
       "executable:java.util.TreeMap#get(java.lang.Object)"
       "executable:java.util.Map#remove(java.lang.Object)"
       "executable:java.util.TreeMap#remove(java.lang.Object)"
       "executable:java.util.HashMap#remove(java.lang.Object)"
       "executable:java.util.LinkedHashMap#remove(java.lang.Object)"
       "executable:java.util.Map#hashCode()"
       "executable:java.util.Map$Entry#getKey()"
       "executable:java.util.Map$Entry#getValue()"
       "executable:java.util.Map$Entry#setValue(java.lang.Object)"
       "executable:java.util.Map$Entry#comparingByValue()"
       "executable:java.util.List#get(int)"
       "executable:java.util.List#contains(java.lang.Object)"
       "executable:java.util.List#equals(java.lang.Object)"
       "executable:java.util.List#hashCode()"
       "executable:java.util.List#isEmpty()"
       "executable:java.util.List#add(java.lang.Object)"
       "executable:java.util.List#removeIf(java.util.function.Predicate)"
       "executable:java.util.Collection#removeIf(java.util.function.Predicate)"
       "executable:java.util.List#addAll(java.util.Collection)"
       "executable:java.util.List#size()"
       "executable:java.util.List#iterator()"
       "executable:java.util.ArrayList#get(int)"
       "executable:java.util.ArrayList#isEmpty()"
       "executable:java.util.ArrayList#remove(int)"
       "executable:java.util.ArrayList#size()"
       "executable:java.util.Iterator#next()"
       "executable:java.util.Iterator#hasNext()"
       "executable:java.util.Iterator#remove()"
       "executable:java.util.Collection#remove(java.lang.Object)"
       "executable:java.util.Collection#toArray()"
       "executable:java.util.Collection#toArray(java.lang.Object[])"
       "executable:java.util.ArrayList#toArray()"
       "executable:java.util.ArrayList#toArray(java.lang.Object[])"
       "executable:java.util.Set#toArray(java.lang.Object[])"
       "executable:java.util.Optional#empty()"
       "executable:java.util.Optional#of(java.lang.Object)"
       "executable:java.util.Optional#ofNullable(java.lang.Object)"
       "executable:java.util.Optional#get()"
       "executable:java.util.Optional#isPresent()"
       "executable:java.util.Optional#equals(java.lang.Object)"
       "executable:java.util.Optional#map(java.util.function.Function)"
       "executable:java.util.Optional#orElse(java.lang.Object)"
       "executable:java.util.Optional#orElseGet(java.util.function.Supplier)"
       "executable:java.util.Optional#ifPresent(java.util.function.Consumer)"
       "executable:java.util.Optional#orElseThrow(java.util.function.Supplier)"
       "executable:java.util.OptionalInt#isPresent()"
       "executable:java.util.OptionalInt#getAsInt()"
       "executable:java.util.OptionalInt#empty()"
       "executable:java.util.OptionalInt#of(int)"
       "executable:java.util.OptionalLong#empty()"
       "executable:java.util.OptionalLong#of(long)"
       "executable:java.util.OptionalLong#ifPresent(java.util.function.LongConsumer)"
       "executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)"
       "executable:java.util.function.BiFunction#apply(java.lang.Object,java.lang.Object)"
       "executable:java.util.function.Consumer#accept(java.lang.Object)"
       "executable:java.util.function.Supplier#get()"
       "executable:java.util.Collection#contains(java.lang.Object)"
       "executable:java.util.Set#contains(java.lang.Object)"
       "executable:java.util.HashSet#contains(java.lang.Object)"
       "executable:java.util.Set#equals(java.lang.Object)"
       "executable:java.util.Set#add(java.lang.Object)"
       "executable:java.util.HashSet#add(java.lang.Object)"
       "executable:java.util.Set#removeAll(java.util.Collection)"
       "executable:java.util.List#toArray()"
       "executable:java.util.List#toArray(java.lang.Object[])"
       "executable:java.util.regex.Pattern#compile(java.lang.String)"
       "executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)"
       "executable:java.util.regex.Pattern#split(java.lang.CharSequence)"
       "executable:java.util.regex.Matcher#matches()"
       "executable:java.util.stream.Collectors#toList()"
       "executable:java.util.stream.Collectors#toSet()"
       "executable:java.util.stream.Collectors#toCollection(java.util.function.Supplier)"
       "executable:java.util.stream.Stream#collect(java.util.stream.Collector)"
       "executable:java.util.stream.Stream#flatMap(java.util.function.Function)"
       "executable:java.util.stream.Stream#map(java.util.function.Function)"
       "executable:java.util.stream.Stream#mapToLong(java.util.function.ToLongFunction)"
       "executable:java.util.stream.LongStream#sum()"
       "executable:java.util.stream.Stream#of(java.lang.Object[])"
       "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)"
       "executable:java.io.OutputStream#write(byte[])"
       "executable:java.io.OutputStream#write(byte[],int,int)"
       "executable:java.io.OutputStream#write(int)"
       "executable:java.io.PrintStream#println(java.lang.String)"
       "executable:java.io.PipedOutputStream#connect(java.io.PipedInputStream)"
       "executable:java.io.PipedOutputStream#write(byte[],int,int)"
       "executable:java.io.PipedOutputStream#close()"
       "executable:java.io.PipedOutputStream#flush()"
       "executable:java.io.InputStream#read()"
       "executable:java.io.InputStream#read(byte[])"
       "executable:java.io.InputStream#read(byte[],int,int)"
       "executable:java.io.ByteArrayInputStream#read()"
       "executable:java.io.PushbackInputStream#read()"
       "executable:java.io.PushbackInputStream#unread(int)"
       "executable:java.io.PushbackInputStream#unread(byte[],int,int)"
       "executable:java.io.PushbackInputStream#close()"
       "executable:java.io.InputStream#close()"
       "executable:java.util.zip.GZIPInputStream#read(byte[],int,int)"
       "executable:java.util.concurrent.ExecutorService#submit(java.lang.Runnable)"
       "executable:java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)"
       "executable:java.util.concurrent.ExecutorService#shutdown()"
       "executable:java.util.concurrent.ExecutorService#shutdownNow()"
       "executable:java.util.concurrent.ExecutorService#awaitTermination(long,java.util.concurrent.TimeUnit)"
       "executable:java.util.concurrent.Executors#newFixedThreadPool(int,java.util.concurrent.ThreadFactory)"
       "executable:java.util.concurrent.Executors#newSingleThreadExecutor()"
       "executable:java.util.concurrent.Future#get(long,java.util.concurrent.TimeUnit)"
       "executable:java.lang.Thread#setDaemon(boolean)"
       "executable:java.lang.Thread#setName(java.lang.String)"
       "executable:java.lang.Thread#start()"
       "executable:java.lang.Thread#currentThread()"
       "executable:java.lang.Thread#interrupt()"
       "executable:java.util.concurrent.atomic.AtomicBoolean#get()"
       "executable:java.util.concurrent.atomic.AtomicBoolean#compareAndSet(boolean,boolean)"
       "executable:java.util.concurrent.atomic.AtomicInteger#incrementAndGet()"
       "executable:java.util.concurrent.atomic.AtomicReference#get()"
       "executable:java.util.concurrent.atomic.AtomicReference#getAndSet(java.lang.Object)"
       "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)"
       "executable:java.io.File#delete()"
       "executable:java.io.File#exists()"
       "executable:java.io.File#getAbsolutePath()"
       "executable:java.io.File#isDirectory()"
       "executable:java.io.File#setExecutable(boolean,boolean)"
       "executable:java.io.File#setReadable(boolean,boolean)"
       "executable:java.io.File#setWritable(boolean,boolean)"
       "executable:java.io.InputStream#available()"
       "executable:java.io.RandomAccessFile#close()"
       "executable:java.io.RandomAccessFile#length()"
       "executable:java.io.RandomAccessFile#readFully(byte[])"
       "executable:java.io.RandomAccessFile#seek(long)"
       "executable:java.io.RandomAccessFile#setLength(long)"
       "executable:java.io.RandomAccessFile#write(byte[])"
       "executable:java.lang.Class#forName(java.lang.String)"
       "executable:java.lang.Class#getDeclaredField(java.lang.String)"
       "executable:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])"
       "executable:java.lang.Class#getName()"
       "executable:java.lang.Class#getSimpleName()"
       "executable:java.lang.Class#isInstance(java.lang.Object)"
       "executable:java.lang.Math#max(double,double)"
       "executable:java.lang.Math#max(float,float)"
       "executable:java.lang.Math#max(int,int)"
       "executable:java.lang.Math#max(long,long)"
       "executable:java.lang.Runtime#addShutdownHook(java.lang.Thread)"
       "executable:java.lang.Runtime#getRuntime()"
       "executable:java.lang.Thread#getId()"
       "executable:java.lang.invoke.MethodHandle#asType(java.lang.invoke.MethodType)"
       "executable:java.lang.invoke.MethodHandle#bindTo(java.lang.Object)"
       "executable:java.lang.invoke.MethodHandle#invokeExact(java.lang.Object[])"
       "executable:java.lang.invoke.MethodHandle#type()"
       "executable:java.lang.invoke.MethodHandles#constant(java.lang.Class,java.lang.Object)"
       "executable:java.lang.invoke.MethodHandles#dropArguments(java.lang.invoke.MethodHandle,int,java.lang.Class[])"
       "executable:java.lang.invoke.MethodHandles#filterReturnValue(java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle)"
       "executable:java.lang.invoke.MethodHandles#guardWithTest(java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle)"
       "executable:java.lang.invoke.MethodHandles#lookup()"
       "executable:java.lang.invoke.MethodHandles$Lookup#findStatic(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)"
       "executable:java.lang.invoke.MethodHandles$Lookup#findVirtual(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)"
       "executable:java.lang.invoke.MethodHandles$Lookup#unreflect(java.lang.reflect.Method)"
       "executable:java.lang.invoke.MethodType#methodType(java.lang.Class)"
       "executable:java.lang.invoke.MethodType#methodType(java.lang.Class,java.lang.Class)"
       "executable:java.lang.invoke.MethodType#returnType()"
       "executable:java.lang.reflect.Field#get(java.lang.Object)"
       "executable:java.lang.reflect.Field#setAccessible(boolean)"
       "executable:java.lang.reflect.Method#setAccessible(boolean)"
       "executable:java.nio.Buffer#limit()"
       "executable:java.nio.Buffer#position()"
       "executable:java.nio.ByteBuffer#allocate(int)"
       "executable:java.nio.ByteBuffer#array()"
       "executable:java.nio.ByteBuffer#clear()"
       "executable:java.nio.ByteBuffer#duplicate()"
       "executable:java.nio.ByteBuffer#get()"
       "executable:java.nio.ByteBuffer#get(byte[],int,int)"
       "executable:java.nio.ByteBuffer#get(int)"
       "executable:java.nio.ByteBuffer#isDirect()"
       "executable:java.nio.ByteBuffer#limit(int)"
       "executable:java.nio.ByteBuffer#position(int)"
       "executable:java.nio.ByteBuffer#put(byte)"
       "executable:java.nio.ByteBuffer#put(byte[])"
       "executable:java.nio.ByteBuffer#put(byte[],int,int)"
       "executable:java.nio.ByteBuffer#rewind()"
       "executable:java.nio.ByteBuffer#wrap(byte[])"
       "executable:java.nio.channels.FileChannel#map(java.nio.channels.FileChannel$MapMode,long,long)"
       "executable:java.nio.channels.FileChannel#open(java.nio.file.Path,java.nio.file.OpenOption[])"
       "executable:java.nio.channels.FileChannel#open(java.nio.file.Path,java.util.Set,java.nio.file.attribute.FileAttribute[])"
       "executable:java.nio.channels.FileChannel#position(long)"
       "executable:java.nio.channels.FileChannel#read(java.nio.ByteBuffer)"
       "executable:java.nio.channels.FileChannel#size()"
       "executable:java.nio.channels.spi.AbstractInterruptibleChannel#close()"
       "executable:java.nio.file.FileSystem#supportedFileAttributeViews()"
       "executable:java.nio.file.FileSystems#getDefault()"
       "executable:java.nio.file.Files#createTempDirectory(java.lang.String,java.nio.file.attribute.FileAttribute[])"
       "executable:java.nio.file.Files#createTempFile(java.lang.String,java.lang.String,java.nio.file.attribute.FileAttribute[])"
       "executable:java.nio.file.Files#createTempFile(java.nio.file.Path,java.lang.String,java.lang.String,java.nio.file.attribute.FileAttribute[])"
       "executable:java.nio.file.Files#getFileAttributeView(java.nio.file.Path,java.lang.Class,java.nio.file.LinkOption[])"
       "executable:java.nio.file.Files#setPosixFilePermissions(java.nio.file.Path,java.util.Set)"
       "executable:java.nio.file.Files#walk(java.nio.file.Path,java.nio.file.FileVisitOption[])"
       "executable:java.nio.file.Path#toFile()"
       "executable:java.nio.file.attribute.AclEntry#newBuilder()"
       "executable:java.nio.file.attribute.AclEntry$Builder#build()"
       "executable:java.nio.file.attribute.AclEntry$Builder#setPermissions(java.util.Set)"
       "executable:java.nio.file.attribute.AclEntry$Builder#setPrincipal(java.nio.file.attribute.UserPrincipal)"
       "executable:java.nio.file.attribute.AclEntry$Builder#setType(java.nio.file.attribute.AclEntryType)"
       "executable:java.nio.file.attribute.AclFileAttributeView#setAcl(java.util.List)"
       "executable:java.nio.file.attribute.FileOwnerAttributeView#getOwner()"
       "executable:java.nio.file.attribute.PosixFilePermissions#asFileAttribute(java.util.Set)"
       "executable:java.nio.file.attribute.PosixFilePermissions#fromString(java.lang.String)"
       "executable:java.security.AccessController#doPrivileged(java.security.PrivilegedAction)"
       "executable:java.util.Arrays#asList(java.lang.Object[])"
       "executable:java.util.BitSet#clear()"
       "executable:java.util.BitSet#clear(int)"
       "executable:java.util.BitSet#get(int)"
       "executable:java.util.BitSet#nextSetBit(int)"
       "executable:java.util.BitSet#set(int)"
       "executable:java.util.BitSet#set(int,int)"
       "executable:java.util.Collections#synchronizedList(java.util.List)"
       "executable:java.util.Comparator#reverseOrder()"
       "executable:java.util.EnumSet#of(java.lang.Enum)"
       "executable:java.util.Collection#clear()"
       "executable:java.util.List#clear()"
       "executable:java.util.Set#clear()"
       "executable:java.util.HashSet#clear()"
       "executable:java.util.List#remove(java.lang.Object)"
       "executable:java.util.Map#clear()"
       "executable:java.util.TreeMap#clear()"
       "executable:java.util.Objects#nonNull(java.lang.Object)"
       "executable:java.util.stream.Stream#filter(java.util.function.Predicate)"
       "executable:java.util.stream.Stream#forEach(java.util.function.Consumer)"
       "executable:java.util.stream.Stream#sorted()"
       "executable:java.util.stream.Stream#sorted(java.util.Comparator)"}
     (:key occurrence))
    (identifier (.getSimpleName ^CtElement reference))

    (= "field:<array>#length" (:key occurrence))
    "Length"

    (= "field:java.io.FilterOutputStream#out" (:key occurrence))
    "@out"

    (= "field:java.lang.System#out" (:key occurrence))
    "@out"

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

    (= "field:java.lang.Integer#MAX_VALUE" (:key occurrence))
    "MaxValue"

    (and (= :field (:kind occurrence))
         (str/ends-with? (:key occurrence) "#class"))
    "class"

    (contains? #{"field:java.nio.channels.FileChannel$MapMode#READ_ONLY"
                 "field:java.nio.file.StandardOpenOption#READ"
                 "field:java.nio.file.attribute.AclEntryPermission#APPEND_DATA"
                 "field:java.nio.file.attribute.AclEntryPermission#DELETE"
                 "field:java.nio.file.attribute.AclEntryPermission#DELETE_CHILD"
                 "field:java.nio.file.attribute.AclEntryPermission#EXECUTE"
                 "field:java.nio.file.attribute.AclEntryPermission#READ_ACL"
                 "field:java.nio.file.attribute.AclEntryPermission#READ_ATTRIBUTES"
                 "field:java.nio.file.attribute.AclEntryPermission#READ_DATA"
                 "field:java.nio.file.attribute.AclEntryPermission#READ_NAMED_ATTRS"
                 "field:java.nio.file.attribute.AclEntryPermission#SYNCHRONIZE"
                 "field:java.nio.file.attribute.AclEntryPermission#WRITE_ACL"
                 "field:java.nio.file.attribute.AclEntryPermission#WRITE_ATTRIBUTES"
                 "field:java.nio.file.attribute.AclEntryPermission#WRITE_DATA"
                 "field:java.nio.file.attribute.AclEntryPermission#WRITE_NAMED_ATTRS"
                 "field:java.nio.file.attribute.AclEntryType#ALLOW"}
               (:key occurrence))
    (identifier (.getSimpleName ^CtElement reference))

    (contains? #{"field:java.util.concurrent.TimeUnit#NANOSECONDS"
                 "field:java.util.concurrent.TimeUnit#MICROSECONDS"
                 "field:java.util.concurrent.TimeUnit#MILLISECONDS"
                 "field:java.util.concurrent.TimeUnit#SECONDS"
                 "field:java.util.concurrent.TimeUnit#MINUTES"
                 "field:java.util.concurrent.TimeUnit#HOURS"
                 "field:java.util.concurrent.TimeUnit#DAYS"}
               (:key occurrence))
    (identifier (.getSimpleName ^CtElement reference))

    :else
    (unsupported! "Java library executable or field has no neutral mapping"
                  reference)))

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

                 (contains? (:destination-constructor-adaptations @ctx-holder)
                            (:key occurrence))
                 {:node (raw "<init>")}

                 (contains?
                  #{"executable:java.lang.Object#<init>()"
                    "executable:java.lang.Enum#<init>(java.lang.String,int)"
                    "executable:java.io.InputStream#<init>()"
                    "executable:java.io.OutputStream#<init>()"
                    "executable:java.io.IOException#<init>()"
                    "executable:java.io.IOException#<init>(java.lang.String)"
                    "executable:java.io.IOException#<init>(java.lang.Throwable)"
                    "executable:java.io.IOException#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.io.EOFException#<init>()"
                    "executable:java.io.EOFException#<init>(java.lang.String)"
                    "executable:java.io.File#<init>(java.lang.String)"
                    "executable:java.io.File#<init>(java.lang.String,java.lang.String)"
                    "executable:java.io.File#<init>(java.net.URI)"
                    "executable:java.io.FileInputStream#<init>(java.io.File)"
                    "executable:java.io.FileInputStream#<init>(java.lang.String)"
                    "executable:java.io.FileOutputStream#<init>(java.io.File)"
                    "executable:java.io.FileOutputStream#<init>(java.lang.String)"
                    "executable:java.io.RandomAccessFile#<init>(java.io.File,java.lang.String)"
                    "executable:java.math.BigInteger#<init>(int,byte[])"
                    "executable:java.math.BigDecimal#<init>(java.lang.String)"
                    "executable:java.security.SecureRandom#<init>()"
                    "executable:java.util.Random#<init>()"
                    "executable:javax.crypto.CipherInputStream#<init>(java.io.InputStream,javax.crypto.Cipher)"
                    "executable:javax.crypto.spec.IvParameterSpec#<init>(byte[])"
                    "executable:javax.crypto.spec.SecretKeySpec#<init>(byte[],java.lang.String)"
                    "executable:java.util.NoSuchElementException#<init>()"
                    "executable:java.lang.RuntimeException#<init>()"
                    "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"
                    "executable:java.lang.RuntimeException#<init>(java.lang.String)"
                    "executable:java.lang.RuntimeException#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.util.concurrent.TimeoutException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalStateException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalStateException#<init>()"
                    "executable:java.lang.IllegalArgumentException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalArgumentException#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.lang.IllegalArgumentException#<init>(java.lang.Throwable)"
                    "executable:java.lang.IllegalArgumentException#<init>()"
                    "executable:java.lang.ClassCastException#<init>(java.lang.String)"
                    "executable:java.lang.IndexOutOfBoundsException#<init>(java.lang.String)"
                    "executable:java.lang.NullPointerException#<init>(java.lang.String)"
                    "executable:java.lang.UnsupportedOperationException#<init>(java.lang.String)"
                    "executable:java.lang.UnsupportedOperationException#<init>()"
                    "executable:java.lang.AssertionError#<init>(java.lang.Object)"
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
                    "executable:java.io.StringWriter#<init>()"
                    "executable:java.io.DataOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.io.InputStreamReader#<init>(java.io.InputStream,java.nio.charset.Charset)"
                    "executable:java.io.OutputStreamWriter#<init>(java.io.OutputStream,java.nio.charset.Charset)"
                    "executable:java.io.LineNumberReader#<init>(java.io.Reader)"
                    "executable:java.util.zip.GZIPInputStream#<init>(java.io.InputStream)"
                    "executable:java.util.zip.CRC32#<init>()"
                    "executable:java.util.zip.InflaterOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.util.zip.Inflater#<init>(boolean)"
                    "executable:java.util.zip.Deflater#<init>(int)"
                    "executable:java.util.zip.DeflaterOutputStream#<init>(java.io.OutputStream,java.util.zip.Deflater)"
                    "executable:java.net.URI#<init>(java.lang.String)"
                    "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)"
                    "executable:java.util.HashMap#<init>()"
                    "executable:java.util.HashMap#<init>(int)"
                    "executable:java.util.HashMap#<init>(java.util.Map)"
                    "executable:java.util.IdentityHashMap#<init>()"
                    "executable:java.util.WeakHashMap#<init>()"
                    "executable:java.util.Hashtable#<init>()"
                    "executable:java.util.HashSet#<init>(int)"
                    "executable:java.util.HashSet#<init>()"
                    "executable:java.util.HashSet#<init>(java.util.Collection)"
                    "executable:java.util.LinkedHashSet#<init>()"
                    "executable:java.util.LinkedHashSet#<init>(int)"
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
                    "executable:java.text.Bidi#<init>(java.lang.String,int)"
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
                    "executable:java.util.LinkedHashMap#<init>(int,float,boolean)"
                    "executable:java.util.LinkedHashMap#<init>(java.util.Map)"
                    "executable:java.util.AbstractMap$SimpleEntry#<init>(java.lang.Object,java.lang.Object)"
                    "executable:java.util.AbstractMap$SimpleImmutableEntry#<init>(java.lang.Object,java.lang.Object)"
                    "executable:java.util.Iterator#<init>()"
                    "executable:java.lang.Thread#<init>(java.lang.Runnable)"
                    "executable:java.lang.Thread#<init>(java.lang.Runnable,java.lang.String)"
                    "executable:java.util.concurrent.atomic.AtomicBoolean#<init>(boolean)"
                    "executable:java.util.concurrent.atomic.AtomicInteger#<init>(int)"
                    "executable:java.util.concurrent.atomic.AtomicReference#<init>()"
                    "executable:java.util.BitSet#<init>()"
                    "executable:java.util.concurrent.ConcurrentHashMap#<init>()"
                    "executable:java.util.concurrent.ConcurrentHashMap#<init>(int)"
                    "executable:java.time.format.DateTimeFormatterBuilder#<init>()"
                    "executable:java.util.GregorianCalendar#<init>()"
                    "executable:java.util.GregorianCalendar#<init>(java.util.TimeZone)"
                    "executable:java.util.SimpleTimeZone#<init>(int,java.lang.String)"
                    "executable:javax.xml.namespace.QName#<init>(java.lang.String)"
                    "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String)"
                    "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String,java.lang.String)"
                    "executable:javax.xml.transform.dom.DOMSource#<init>(org.w3c.dom.Node)"
                    "executable:javax.xml.transform.stream.StreamResult#<init>(java.io.OutputStream)"
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

                :else
                (sequence-node [(raw "(") (type-node ctx cast)
                                (raw ")(") inner
                                (raw (if (.isPrimitive cast) ")" "!)"))]))))
          node
          (.getTypeCasts expression)))

(declare maybe-unbox-node)

(defn- assignment-value-node [ctx ^CtElement assigned ^CtExpression assignment node]
  (let [assigned-reference (some-> assigned .getType)
        assigned-type (some-> assigned-reference .getQualifiedName)
        assignment-type (some-> assignment .getType .getQualifiedName)
        node (if (and assigned-reference (.isPrimitive assigned-reference))
               (maybe-unbox-node ctx assignment node)
               node)]
    (cond
      (and (= "byte" assigned-type)
           (not= "byte" assignment-type))
      (sequence-node [(raw "unchecked((sbyte)(") node (raw "))")])

      (and (= "char" assigned-type)
           (not= "char" assignment-type))
      (sequence-node [(raw "unchecked((char)(") node (raw "))")])

      :else node)))

(defn- collection-element-type [^CtInvocation invocation]
  (or (first (.getActualTypeArguments (.getType invocation)))
      (unsupported! "Generic Java collection invocation has no resolved element type"
                    invocation)))

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

(defn- nullable-boxed-expression? [ctx ^CtExpression expression]
  (cond
    (instance? CtInvocation expression)
    (let [occurrence (invocation-occurrence ctx expression)
          referenced-declaration
          (some-> expression .getExecutable .getDeclaration)
          declaration
          (if (instance? CtMethod referenced-declaration)
            referenced-declaration
            (:declaration occurrence))]
      (or (and (= "executable:java.util.Map#get(java.lang.Object)"
                  (:key occurrence))
               (boxed-primitive-reference? (.getType expression)))
          (and (instance? CtMethod declaration) (not (.isShadow ^CtMethod declaration)) (nullable-boxed-declaration? ctx declaration (.getType ^CtMethod declaration)))))

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

    :else false))

(defn- maybe-unbox-node [ctx ^CtExpression expression node]
  (if (nullable-boxed-expression? ctx expression)
    (compat-call "Unbox" [node])
    node))

(defn- argument-value-node
  [ctx ^CtExpression argument ^CtTypeReference expected node force-value?]
  (let [expected-name (some-> expected .getQualifiedName)
        argument-name (some-> argument .getType .getQualifiedName)
        value-node (maybe-unbox-node ctx argument node)]
    (cond
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

      :else node)))

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

(defn- terminating-statement? [statement]
  (cond
    (or (instance? CtReturn statement)
        (instance? CtThrow statement)
        (instance? CtBreak statement)
        (instance? CtContinue statement)) true
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

(defn- enum-switch-declaration [ctx ^CtSwitch switch]
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

(defn- case-labels [ctx children ^CtCase case]
  (let [expressions (vec (.getCaseExpressions case))
        parent (when (.isParentInitialized case) (.getParent case))
        enum (when (instance? CtSwitch parent)
               (enum-switch-declaration ctx parent))
        selector (when (instance? CtSwitch parent)
                   (.getSelector ^CtSwitch parent))]
    (if (seq expressions)
      (sequence-node
       (mapv #(sequence-node [(raw "case ")
                              (if enum
                                (enum-case-node ctx enum %)
                                (assignment-value-node
                                 ctx selector % (child-node children %)))
                              (raw ":")])
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

(declare covariant-value-override?)

(defn- executable-parameter-types
  [declaration executable-reference]
  (if (instance? CtExecutable declaration)
    (mapv #(.getType ^CtParameter %)
          (.getParameters ^CtExecutable declaration))
    (vec (.getParameters executable-reference))))

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
               (let [node (child-node children target)]
                 (if (and (instance? CtExpression target)
                          (seq (.getTypeCasts ^CtExpression target)))
                   (sequence-node [(raw "(") node (raw ")")])
                   node)))
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
             (when-let [adaptation
                        (get (:destination-invocation-adaptations @ctx-holder)
                             (:key occurrence))]
               (adaptation target-node arguments))
             raw-node
             (or
              destination-adaptation
              (case (:key occurrence)
                ("executable:java.lang.ref.SoftReference#get()"
                 "executable:java.lang.ref.WeakReference#get()"
                 "executable:java.lang.ref.Reference#get()")
                (sequence-node [target-node (raw ".Get()")])

                "executable:java.lang.ref.Reference#clear()"
                (sequence-node [target-node (raw ".Clear()")])

                "executable:java.io.ByteArrayOutputStream#write(byte[],int,int)"
                (compat-call "OutputStreamWrite" (into [target-node] arguments))

                ("executable:java.io.FilterOutputStream#write(byte[])"
                 "executable:java.io.FilterOutputStream#write(byte[],int,int)"
                 "executable:java.io.FilterOutputStream#write(int)"
                 "executable:java.util.zip.DeflaterOutputStream#write(byte[],int,int)")
                (sequence-node [target-node (raw ".Write(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.zip.DeflaterOutputStream#close()"
                (sequence-node [target-node (raw ".Dispose()")])

                "executable:java.util.zip.Deflater#end()"
                (sequence-node [target-node (raw ".End()")])

                "executable:java.util.zip.Inflater#finished()"
                (sequence-node [target-node (raw ".Finished()")])

                "executable:java.util.zip.Inflater#needsInput()"
                (sequence-node [target-node (raw ".NeedsInput()")])

                "executable:java.util.zip.Inflater#setInput(byte[],int,int)"
                (sequence-node [target-node (raw ".SetInput(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.zip.Inflater#inflate(byte[])"
                (sequence-node [target-node (raw ".Inflate(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.zip.Inflater#end()"
                (sequence-node [target-node (raw ".End()")])

                "executable:java.io.FilterInputStream#close()"
                (sequence-node [target-node (raw ".Dispose()")])

                "executable:java.io.ByteArrayOutputStream#reset()"
                (compat-call "ResetMemoryStream" [target-node])

                "executable:java.io.ByteArrayOutputStream#toString(java.lang.String)"
                (compat-call "MemoryStreamToString" (into [target-node] arguments))

                "executable:java.io.BufferedReader#readLine()"
                (sequence-node [target-node (raw ".ReadLine()")])

                "executable:java.io.BufferedReader#ready()"
                (compat-call "ReaderReady" [target-node])

                "executable:java.io.BufferedWriter#newLine()"
                (sequence-node [target-node (raw ".WriteLine()")])

                "executable:java.io.BufferedWriter#write(java.lang.String)"
                (sequence-node [target-node (raw ".Write(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.BufferedWriter#write(int)"
                (compat-call "WriterWriteCharCode" (into [target-node] arguments))

                "executable:java.io.BufferedWriter#flush()"
                (sequence-node [target-node (raw ".Flush()")])

                "executable:java.io.File#canRead()"
                (compat-call "FileCanRead" [target-node])

                "executable:java.io.File#getName()"
                (sequence-node [target-node (raw ".Name")])

                "executable:java.io.File#getPath()"
                (sequence-node [target-node (raw ".ToString()")])

                "executable:java.io.File#equals(java.lang.Object)"
                (compat-call "FileEquals" (into [target-node] arguments))

                "executable:java.io.File#isHidden()"
                (compat-call "FileIsHidden" [target-node])

                "executable:java.io.File#canWrite()"
                (compat-call "FileCanWrite" [target-node])

                "executable:java.io.File#lastModified()"
                (compat-call "FileLastModified" [target-node])

                "executable:java.io.File#listFiles()"
                (compat-call "FileListFiles" [target-node])

                "executable:java.io.File#toURI()"
                (compat-call "FileToUri" [target-node])

                ("executable:java.io.InputStream#mark(int)"
                 "executable:java.io.BufferedInputStream#mark(int)")
                (compat-call "InputStreamMark" (into [target-node] arguments))

                ("executable:java.io.InputStream#markSupported()"
                 "executable:java.io.BufferedInputStream#markSupported()")
                (compat-call "InputStreamMarkSupported" [target-node])

                ("executable:java.io.InputStream#reset()"
                 "executable:java.io.ByteArrayInputStream#reset()"
                 "executable:java.io.BufferedInputStream#reset()")
                (compat-call "InputStreamReset" [target-node])

                "executable:java.io.InputStream#skip(long)"
                (compat-call "InputStreamSkip" (into [target-node] arguments))

                "executable:java.io.LineNumberReader#readLine()"
                (sequence-node [target-node (raw ".ReadLine()")])

                "executable:java.io.StringWriter#toString()"
                (sequence-node [target-node (raw ".ToString()")])

                ("executable:java.io.Writer#write(java.lang.String)"
                 "executable:java.io.StringWriter#write(java.lang.String)")
                (sequence-node [target-node (raw ".Write(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.Writer#write(char[])"
                (sequence-node [target-node (raw ".Write(")
                                (first arguments) (raw ")")])

                "executable:java.io.Writer#close()"
                (sequence-node [target-node (raw ".Dispose()")])

                "executable:java.lang.Boolean#parseBoolean(java.lang.String)"
                (compat-call "ParseBoolean" arguments)

                "executable:java.lang.Boolean#getBoolean(java.lang.String)"
                (compat-call "GetBoolean" arguments)

                "executable:java.lang.Byte#toUnsignedInt(byte)"
                (compat-call "ToUnsignedInt" arguments)

                "executable:java.lang.Character#digit(char,int)"
                (compat-call "CharacterDigit" arguments)

                "executable:java.lang.Character#charCount(int)"
                (compat-call "CharacterCharCount" arguments)

                "executable:java.lang.Character#getName(int)"
                (compat-call "CharacterName" arguments)

                "executable:java.lang.Character#isDefined(int)"
                (compat-call "CharacterIsDefined" arguments)

                ("executable:java.lang.Character#getType(int)"
                 "executable:java.lang.Character#getType(char)")
                (compat-call "CharacterType" arguments)

                ("executable:java.lang.Character#isDigit(char)"
                 "executable:java.lang.Character#isDigit(int)")
                (compat-call "IsDigit" arguments)

                "executable:java.lang.Character#isBmpCodePoint(int)"
                (compat-call "IsBmpCodePoint" arguments)

                "executable:java.lang.Character#isValidCodePoint(int)"
                (compat-call "IsValidCodePoint" arguments)

                "executable:java.lang.Character#isSurrogatePair(char,char)"
                (sequence-node [(raw "char.IsSurrogatePair(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.lang.Character#isMirrored(char)"
                 "executable:java.lang.Character#isMirrored(int)")
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaBidi.IsMirrored(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Character#isWhitespace(char)"
                (compat-call "IsWhitespace" arguments)

                "executable:java.lang.Character#toString(char)"
                (compat-call "CodePointToString" arguments)

                "executable:java.lang.Class#asSubclass(java.lang.Class)"
                (compat-call "ClassAsSubclass" (into [target-node] arguments))

                "executable:java.lang.Class#cast(java.lang.Object)"
                (let [cast-type
                      (or (some-> target .getType .getActualTypeArguments
                                  first)
                          (.getType element))]
                  (sequence-node
                   [(raw "global::DripSharp.Runtime.JavaCompat.ClassCast<")
                    (type-node @ctx-holder cast-type)
                    (raw ">(") target-node (raw ", ")
                    (sequence-node arguments ", ") (raw ")")]))

                "executable:java.lang.Double#compare(double,double)"
                (compat-call "CompareDouble" arguments)

                "executable:java.lang.Class#getAnnotation(java.lang.Class)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.ClassGetAnnotation<")
                  (type-node @ctx-holder (.getType element))
                  (raw ">(") target-node (raw ", ")
                  (sequence-node arguments ", ") (raw ")!")])

                "executable:java.lang.Class#getDeclaredConstructor(java.lang.Class[])"
                (compat-call "ClassGetDeclaredConstructor"
                             (into [target-node] arguments))

                "executable:java.lang.Class#getFields()"
                (sequence-node [target-node (raw ".GetFields()")])

                "executable:java.lang.Class#getResourceAsStream(java.lang.String)"
                (compat-call "ClassGetResourceAsStream"
                             (into [target-node] arguments))

                "executable:java.lang.Double#valueOf(java.lang.String)"
                (compat-call "ParseDouble" arguments)

                "executable:java.lang.Enum#toString()"
                (sequence-node [target-node (raw ".ToString()")])

                "executable:java.lang.Float#compare(float,float)"
                (compat-call "CompareFloat" arguments)

                ("executable:java.lang.Float#floatToIntBits(float)"
                 "executable:java.lang.Float#hashCode(float)")
                (compat-call "FloatToIntBits" arguments)

                "executable:java.lang.Long#hashCode(long)"
                (compat-call "LongHashCode" arguments)

                "executable:java.lang.Float#isFinite(float)"
                (sequence-node [(raw "float.IsFinite(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Float#isInfinite(float)"
                (sequence-node [(raw "float.IsInfinity(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Float#isNaN(float)"
                (sequence-node [(raw "float.IsNaN(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Integer#compareTo(java.lang.Integer)"
                (sequence-node [target-node (raw ".CompareTo(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Integer#signum(int)"
                (sequence-node [(raw "global::System.Math.Sign(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Float#parseFloat(java.lang.String)"
                (compat-call "ParseFloat" arguments)

                "executable:java.lang.Float#toString(float)"
                (compat-call "StringValueOf" arguments)

                "executable:java.lang.Integer#compare(int,int)"
                (compat-call "CompareInt" arguments)

                ("executable:java.lang.Integer#equals(java.lang.Object)"
                 "executable:java.lang.Long#equals(java.lang.Object)"
                 "executable:java.lang.Float#equals(java.lang.Object)"
                 "executable:java.lang.Double#equals(java.lang.Object)")
                (compat-call "Equals" (into [target-node] arguments))

                "executable:java.lang.Integer#highestOneBit(int)"
                (compat-call "HighestOneBit" arguments)

                ("executable:java.lang.Integer#toHexString(int)"
                 "executable:java.lang.Long#toHexString(long)")
                (compat-call "ToHexString" arguments)

                "executable:java.lang.Integer#valueOf(java.lang.String)"
                (compat-call "ParseInt" (conj arguments (raw "10")))

                "executable:java.lang.Long#compare(long,long)"
                (compat-call "CompareLong" arguments)

                "executable:java.lang.Math#acos(double)"
                (sequence-node [(raw "global::System.Math.Acos(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.lang.Math#abs(double)"
                 "executable:java.lang.Math#abs(float)"
                 "executable:java.lang.Math#abs(int)"
                 "executable:java.lang.Math#abs(long)")
                (sequence-node [(raw "global::System.Math.Abs(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#atan2(double,double)"
                (sequence-node [(raw "global::System.Math.Atan2(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#ceil(double)"
                (let [argument (first (.getArguments element))
                      argument-node
                      (maybe-unbox-node
                       @ctx-holder argument (child-node children argument))]
                  (sequence-node
                   [(raw "global::System.Math.Ceiling(")
                    (raw "(double)(") argument-node (raw ")")
                    (raw ")")]))

                "executable:java.lang.Math#cos(double)"
                (sequence-node [(raw "global::System.Math.Cos(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#floor(double)"
                (sequence-node [(raw "global::System.Math.Floor(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#floorDiv(int,int)"
                (compat-call "FloorDiv" arguments)

                "executable:java.lang.Math#log(double)"
                (sequence-node [(raw "global::System.Math.Log(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#log10(double)"
                (sequence-node [(raw "global::System.Math.Log10(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#pow(double,double)"
                (sequence-node [(raw "global::System.Math.Pow(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#round(double)"
                (compat-call "MathRound" arguments)

                "executable:java.lang.Math#round(float)"
                (compat-call "MathRoundFloat" arguments)

                "executable:java.lang.Math#signum(double)"
                (compat-call "SignumDouble" arguments)

                "executable:java.lang.Math#signum(float)"
                (compat-call "SignumFloat" arguments)

                "executable:java.lang.Math#sin(double)"
                (sequence-node [(raw "global::System.Math.Sin(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#sqrt(double)"
                (sequence-node [(raw "global::System.Math.Sqrt(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#toDegrees(double)"
                (compat-call "ToDegrees" arguments)

                "executable:java.lang.Math#toRadians(double)"
                (compat-call "ToRadians" arguments)

                "executable:java.math.BigInteger#valueOf(long)"
                (sequence-node
                 [(raw "new global::System.Numerics.BigInteger(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.math.BigInteger#toByteArray()"
                (compat-call "BigIntegerToByteArray" [target-node])

                "executable:java.math.BigInteger#mod(java.math.BigInteger)"
                (compat-call "BigIntegerMod" (into [target-node] arguments))

                "executable:java.math.BigInteger#intValue()"
                (compat-call "BigIntegerIntValue" [target-node])

                "executable:java.math.BigInteger#toString(int)"
                (compat-call "ToStringRadix" (into [target-node] arguments))

                "executable:java.math.BigDecimal#valueOf(double)"
                (compat-call "BigDecimalValueOf" arguments)

                "executable:java.math.BigDecimal#setScale(int,java.math.RoundingMode)"
                (compat-call "BigDecimalSetScale" (into [target-node] arguments))

                "executable:java.math.BigDecimal#intValue()"
                (compat-call "BigDecimalIntValue" [target-node])

                "executable:java.math.BigDecimal#stripTrailingZeros()"
                (compat-call "BigDecimalStripTrailingZeros" [target-node])

                "executable:java.math.BigDecimal#toPlainString()"
                (compat-call "BigDecimalToPlainString" [target-node])

                "executable:java.lang.Number#doubleValue()"
                (sequence-node
                 [(raw "global::System.Convert.ToDouble(") target-node
                  (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")])

                "executable:java.lang.Number#floatValue()"
                (sequence-node
                 [(raw "global::System.Convert.ToSingle(") target-node
                  (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")])

                "executable:java.lang.Number#intValue()"
                (sequence-node
                 [(raw "global::System.Convert.ToInt32(") target-node
                  (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")])

                ("executable:java.lang.Float#floatValue()"
                 "executable:java.lang.Integer#intValue()"
                 "executable:java.lang.Long#longValue()"
                 "executable:java.lang.Double#doubleValue()"
                 "executable:java.lang.Boolean#booleanValue()")
                (maybe-unbox-node @ctx-holder target target-node)

                ("executable:java.lang.Integer#longValue()"
                 "executable:java.lang.Number#longValue()")
                (sequence-node
                 [(raw "global::System.Convert.ToInt64(") target-node
                  (raw ", global::System.Globalization.CultureInfo.InvariantCulture)")])

                "executable:java.lang.Integer#shortValue()"
                (sequence-node [(raw "unchecked((short)") target-node (raw ")")])

                "executable:java.lang.Object#clone()"
                (if (instance? CtSuperAccess target)
                  (raw "this.MemberwiseClone()")
                  (compat-call "Clone" [target-node]))

                ("executable:java.lang.Object#equals(java.lang.Object)"
                 "executable:java.lang.Enum#equals(java.lang.Object)"
                 "executable:java.lang.Boolean#equals(java.lang.Object)")
                (compat-call "Equals" (into [target-node] arguments))

                "executable:java.lang.Object#hashCode()"
                (sequence-node [target-node (raw ".GetHashCode()")])

                "executable:java.lang.reflect.Constructor#newInstance(java.lang.Object[])"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.ConstructorInvoke<")
                  (type-node @ctx-holder (.getType element))
                  (raw ">(") target-node (raw ", ")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.reflect.Field#getAnnotation(java.lang.Class)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.FieldGetAnnotation<")
                  (type-node @ctx-holder (.getType element))
                  (raw ">(") target-node (raw ", ")
                  (sequence-node arguments ", ") (raw ")!")])

                ("executable:java.lang.reflect.AccessibleObject#isAnnotationPresent(java.lang.Class)"
                 "executable:java.lang.reflect.Field#isAnnotationPresent(java.lang.Class)")
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCompat.MemberIsAnnotationPresent(")
                  target-node (raw ", ")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.reflect.Field#getModifiers()"
                (compat-call "ReflectionFieldModifiers" [target-node])

                "executable:java.lang.reflect.Modifier#isFinal(int)"
                (compat-call "ReflectionModifierIsFinal" arguments)

                "executable:java.lang.String#charAt(int)"
                (sequence-node [target-node (raw "[") (first arguments) (raw "]")])

                "executable:java.lang.String#codePointAt(int)"
                (compat-call "CodePointAt" (into [target-node] arguments))

                "executable:java.lang.String#codePointCount(int,int)"
                (compat-call "StringCodePointCount" (into [target-node] arguments))

                "executable:java.lang.String#codePoints()"
                (compat-call "StringCodePoints" [target-node])

                "executable:java.lang.String#compareTo(java.lang.String)"
                (compat-call "StringCompareTo" (into [target-node] arguments))

                "executable:java.lang.String#format(java.util.Locale,java.lang.String,java.lang.Object[])"
                (compat-call "JavaStringFormat" arguments)

                "executable:java.lang.String#indexOf(java.lang.String)"
                (sequence-node
                 [target-node (raw ".IndexOf(") (first arguments)
                  (raw ", global::System.StringComparison.Ordinal)")])

                "executable:java.lang.String#replace(char,char)"
                (sequence-node
                 [target-node (raw ".Replace(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.String#replace(java.lang.CharSequence,java.lang.CharSequence)"
                (sequence-node
                 [target-node (raw ".Replace(") (sequence-node arguments ", ")
                  (raw ", global::System.StringComparison.Ordinal)")])

                "executable:java.lang.String#replaceAll(java.lang.String,java.lang.String)"
                (compat-call "StringReplaceAll" (into [target-node] arguments))

                "executable:java.lang.String#replaceFirst(java.lang.String,java.lang.String)"
                (compat-call "StringReplaceFirst" (into [target-node] arguments))

                "executable:java.lang.String#toLowerCase(java.util.Locale)"
                (sequence-node [target-node (raw ".ToLowerInvariant()")])

                "executable:java.lang.String#toUpperCase(java.util.Locale)"
                (sequence-node [target-node (raw ".ToUpperInvariant()")])

                ("executable:java.lang.String#valueOf(boolean)"
                 "executable:java.lang.String#valueOf(char)"
                 "executable:java.lang.String#valueOf(char[])"
                 "executable:java.lang.String#valueOf(double)"
                 "executable:java.lang.String#valueOf(float)"
                 "executable:java.lang.String#valueOf(int)"
                 "executable:java.lang.String#valueOf(java.lang.Object)"
                 "executable:java.lang.String#valueOf(long)")
                (compat-call "StringValueOf" arguments)

                "executable:java.lang.StringBuilder#append(java.lang.Object)"
                (sequence-node
                 [target-node (raw ".Append(")
                  (compat-call "StringValueOf" arguments)
                  (raw ")")])

                "executable:java.lang.StringBuilder#append(java.lang.CharSequence,int,int)"
                (sequence-node
                 [target-node (raw ".Append(")
                  (first arguments) (raw ", ")
                  (second arguments) (raw ", ")
                  (raw "(") (nth arguments 2) (raw " - ")
                  (second arguments) (raw "))")])

                "executable:java.lang.StringBuilder#reverse()"
                (compat-call "Reverse" [target-node])

                "executable:java.lang.StringBuilder#deleteCharAt(int)"
                (sequence-node [target-node (raw ".Remove(")
                                (first arguments) (raw ", 1)")])

                ("executable:java.lang.StringBuilder#setLength(int)"
                 "executable:java.lang.AbstractStringBuilder#setLength(int)")
                (sequence-node [target-node (raw ".Length = ")
                                (first arguments)])

                ("executable:java.lang.StringBuilder#charAt(int)"
                 "executable:java.lang.AbstractStringBuilder#charAt(int)")
                (sequence-node [target-node (raw "[") (first arguments) (raw "]")])

                "executable:java.lang.System#getProperty(java.lang.String)"
                (compat-call "GetProperty" arguments)

                "executable:java.lang.System#getProperty(java.lang.String,java.lang.String)"
                (compat-call "GetProperty" arguments)

                "executable:java.lang.System#getenv(java.lang.String)"
                (compat-call "Getenv" arguments)

                "executable:java.lang.System#lineSeparator()"
                (raw "global::System.Environment.NewLine")

                "executable:java.security.SecureRandom#nextBytes(byte[])"
                (sequence-node [target-node (raw ".NextBytes(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.security.SecureRandom#nextInt()"
                (sequence-node [target-node (raw ".NextInt()")])

                "executable:java.nio.Buffer#hasRemaining()"
                (sequence-node [(raw "(") target-node (raw ".Remaining > 0)")])

                "executable:java.nio.charset.Charset#forName(java.lang.String)"
                (compat-call "CharsetForName" arguments)

                "executable:java.nio.charset.Charset#name()"
                (compat-call "CharsetName" [target-node])

                "executable:java.nio.charset.Charset#newDecoder()"
                (sequence-node
                 [(raw "new global::DripSharp.Runtime.JavaCharsetDecoder(")
                  target-node (raw ")")])

                ("executable:java.nio.charset.CharsetDecoder#onMalformedInput(java.nio.charset.CodingErrorAction)"
                 "executable:java.nio.charset.CharsetDecoder#onUnmappableCharacter(java.nio.charset.CodingErrorAction)")
                (sequence-node [target-node (raw ".ReportErrors(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.nio.charset.CharsetDecoder#decode(java.nio.ByteBuffer)"
                (sequence-node [target-node (raw ".Decode(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.nio.CharBuffer#toString()"
                target-node

                "executable:java.nio.CharBuffer#wrap(char[],int,int)"
                (compat-call "CharBufferWrap" arguments)

                "executable:java.nio.file.Files#readAllBytes(java.nio.file.Path)"
                (compat-call "ReadAllBytes" arguments)

                "executable:java.nio.file.Paths#get(java.lang.String,java.lang.String[])"
                (compat-call "PathOf" arguments)

                ("executable:java.util.Arrays#binarySearch(int[],int)"
                 "executable:java.util.Arrays#binarySearch(java.lang.Object[],java.lang.Object,java.util.Comparator)")
                (compat-call "BinarySearch" arguments)

                ("executable:java.util.Arrays#copyOf(byte[],int)"
                 "executable:java.util.Arrays#copyOf(float[],int)")
                (compat-call "CopyOf" arguments)

                "executable:java.util.Arrays#copyOfRange(byte[],int,int)"
                (compat-call "CopyOfRange" arguments)

                "executable:java.util.Arrays#deepToString(java.lang.Object[])"
                (compat-call "DeepArrayString" arguments)

                ("executable:java.util.Arrays#fill(int[],int)"
                 "executable:java.util.Arrays#fill(byte[],byte)"
                 "executable:java.util.Arrays#fill(byte[],int,int,byte)"
                 "executable:java.util.Arrays#fill(float[],float)"
                 "executable:java.util.Arrays#fill(double[],double)")
                (compat-call "Fill" arguments)

                ("executable:java.util.Arrays#toString(float[])"
                 "executable:java.util.Arrays#toString(int[])"
                 "executable:java.util.Arrays#toString(java.lang.Object[])")
                (compat-call "ArrayToString" arguments)

                "executable:java.text.DecimalFormatSymbols#getInstance(java.util.Locale)"
                (sequence-node [(first arguments) (raw ".NumberFormat")])

                ("executable:java.text.NumberFormat#format(long)"
                 "executable:java.text.NumberFormat#format(double)")
                (sequence-node [target-node (raw ".Format(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.NumberFormat#getMaximumFractionDigits()"
                (sequence-node [target-node (raw ".GetMaximumFractionDigits()")])

                "executable:java.text.NumberFormat#getNumberInstance(java.util.Locale)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaDecimalFormat.GetNumberInstance(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.NumberFormat#setMaximumFractionDigits(int)"
                (sequence-node [target-node (raw ".SetMaximumFractionDigits(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.NumberFormat#setGroupingUsed(boolean)"
                (sequence-node [target-node (raw ".SetGroupingUsed(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Calendar#getInstance(java.util.TimeZone)"
                (compat-call "CalendarInstance" arguments)

                "executable:java.util.Calendar#clear()"
                (sequence-node
                 [target-node (raw " = ")
                  (compat-call "CalendarClear" [target-node])])

                "executable:java.util.Calendar#equals(java.lang.Object)"
                (compat-call "Equals" (into [target-node] arguments))

                "executable:java.util.Calendar#get(int)"
                (compat-call "CalendarGet" (into [target-node] arguments))

                "executable:java.util.Calendar#getTimeInMillis()"
                (sequence-node [target-node (raw ".ToUnixTimeMilliseconds()")])

                ("executable:java.util.Calendar#set(int,int)"
                 "executable:java.util.Calendar#set(int,int,int,int,int,int)")
                (sequence-node
                 [target-node (raw " = ")
                  (compat-call "CalendarSet" (into [target-node] arguments))])

                "executable:java.util.Calendar#setTimeInMillis(long)"
                (sequence-node
                 [target-node
                  (raw " = global::System.DateTimeOffset.FromUnixTimeMilliseconds(")
                  (first arguments) (raw ")")])

                "executable:java.util.Calendar#setLenient(boolean)"
                (compat-call "CalendarSetLenient" (into [target-node] arguments))

                ("executable:java.util.Calendar#setTimeZone(java.util.TimeZone)"
                 "executable:java.util.GregorianCalendar#setTimeZone(java.util.TimeZone)")
                (sequence-node
                 [target-node (raw " = ")
                  (compat-call "CalendarSetTimeZone"
                               (into [target-node] arguments))])

                "executable:java.util.Calendar#getTimeZone()"
                (compat-call "CalendarGetTimeZone" [target-node])

                ("executable:java.util.Calendar#add(int,int)"
                 "executable:java.util.GregorianCalendar#add(int,int)")
                (sequence-node
                 [target-node (raw " = ")
                  (compat-call "CalendarAdd"
                               (into [target-node] arguments))])

                "executable:java.util.GregorianCalendar#from(java.time.ZonedDateTime)"
                (first arguments)

                "executable:java.util.Deque#pop()"
                (sequence-node [target-node (raw ".Pop()")])

                "executable:java.util.Deque#removeFirst()"
                (sequence-node [target-node (raw ".Pop()")])

                "executable:java.util.Deque#push(java.lang.Object)"
                (sequence-node
                 [target-node (raw ".Push(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Deque#add(java.lang.Object)"
                (compat-call "Add" (into [target-node] arguments))

                "executable:java.util.Deque#addAll(java.util.Collection)"
                (compat-call "AddAll" (into [target-node] arguments))

                "executable:java.util.Deque#contains(java.lang.Object)"
                (compat-call "CollectionContains" (into [target-node] arguments))

                "executable:java.util.Deque#isEmpty()"
                (sequence-node [target-node (raw ".IsEmpty()")])

                "executable:java.util.Deque#peek()"
                (compat-call "DequePeek" [target-node])

                "executable:java.util.Deque#size()"
                (sequence-node [target-node (raw ".Count")])

                "executable:java.util.Deque#clear()"
                (sequence-node [target-node (raw ".Clear()")])

                "executable:java.util.PriorityQueue#add(java.lang.Object)"
                (sequence-node [target-node (raw ".Add(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.PriorityQueue#isEmpty()"
                (sequence-node [(raw "(") target-node (raw ".Count == 0)")])

                "executable:java.util.PriorityQueue#peek()"
                (sequence-node [target-node (raw ".Peek()")])

                "executable:java.util.PriorityQueue#poll()"
                (sequence-node [target-node (raw ".Poll()")])

                "executable:java.util.Properties#getProperty(java.lang.String,java.lang.String)"
                (sequence-node [target-node (raw ".GetProperty(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Properties#load(java.io.InputStream)"
                (sequence-node [target-node (raw ".Load(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.AbstractCollection#isEmpty()"
                (sequence-node [(raw "(") target-node (raw ".Count == 0)")])

                "executable:java.util.AbstractQueue#add(java.lang.Object)"
                (sequence-node [target-node (raw ".Add(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Queue#add(java.lang.Object)"
                (sequence-node [target-node (raw ".Add(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Queue#peek()"
                (sequence-node [target-node (raw ".Peek()")])

                "executable:java.util.Queue#poll()"
                (sequence-node [target-node (raw ".Poll()")])

                "executable:java.util.Collection#add(java.lang.Object)"
                (compat-call "Add" (into [target-node] arguments))

                "executable:java.util.Collection#isEmpty()"
                (compat-call "CollectionIsEmpty" [target-node])

                "executable:java.util.Collection#size()"
                (sequence-node [target-node (raw ".Count")])

                ("executable:java.util.Collection#removeAll(java.util.Collection)"
                 "executable:java.util.AbstractCollection#removeAll(java.util.Collection)"
                 "executable:java.util.List#removeAll(java.util.Collection)")
                (compat-call "RemoveAll" (into [target-node] arguments))

                ("executable:java.util.Collection#retainAll(java.util.Collection)"
                 "executable:java.util.AbstractCollection#retainAll(java.util.Collection)"
                 "executable:java.util.List#retainAll(java.util.Collection)")
                (compat-call "RetainAll" (into [target-node] arguments))

                ("executable:java.util.Collections#sort(java.util.List)"
                 "executable:java.util.Collections#sort(java.util.List,java.util.Comparator)")
                (compat-call "SortList" arguments)

                "executable:java.util.Collections#reverse(java.util.List)"
                (compat-call "Reverse" arguments)

                "executable:java.util.Base64#getDecoder()"
                (raw "global::DripSharp.Runtime.JavaBase64.GetDecoder()")

                "executable:java.util.Base64$Decoder#decode(java.lang.String)"
                (sequence-node [target-node (raw ".Decode(")
                                (first arguments) (raw ")")])

                "executable:java.util.Collections#max(java.util.Collection)"
                (compat-call "CollectionMax" arguments)

                "executable:java.util.Collections#min(java.util.Collection)"
                (compat-call "CollectionMin" arguments)

                "executable:java.util.Collections#newSetFromMap(java.util.Map)"
                (compat-call "NewSetFromMap" arguments)

                "executable:java.util.Collections#unmodifiableSet(java.util.Set)"
                (compat-call "UnmodifiableSet" arguments)

                "executable:java.util.Comparator#comparing(java.util.function.Function)"
                (let [comparator-arguments
                      (vec (.getActualTypeArguments (.getType element)))
                      function-arguments
                      (vec
                       (.getActualTypeArguments
                        (.getType ^CtExpression
                         (first (.getArguments element)))))]
                  (if (and (= 1 (count comparator-arguments))
                           (= 2 (count function-arguments)))
                    (sequence-node
                     [(raw "global::DripSharp.Runtime.JavaCompat.ComparatorComparing<")
                      (type-node @ctx-holder (first comparator-arguments))
                      (raw ", ")
                      (type-node @ctx-holder (second function-arguments))
                      (raw ">(") (first arguments) (raw ")")])
                    (compat-call "ComparatorComparing" arguments)))

                "executable:java.util.List#add(int,java.lang.Object)"
                (compat-call "ListAdd" (into [target-node] arguments))

                ("executable:java.util.List#addAll(int,java.util.Collection)"
                 "executable:java.util.ArrayList#addAll(int,java.util.Collection)")
                (compat-call "ListAddAll" (into [target-node] arguments))

                "executable:java.util.List#indexOf(java.lang.Object)"
                (compat-call "ListIndexOf" (into [target-node] arguments))

                "executable:java.util.List#lastIndexOf(java.lang.Object)"
                (compat-call "ListLastIndexOf" (into [target-node] arguments))

                "executable:java.util.List#remove(int)"
                (compat-call "ListRemove" (into [target-node] arguments))

                "executable:java.util.List#set(int,java.lang.Object)"
                (compat-call "ListSet" (into [target-node] arguments))

                "executable:java.util.List#sort(java.util.Comparator)"
                (compat-call "SortList" (into [target-node] arguments))

                "executable:java.util.List#subList(int,int)"
                (compat-call "SubList" (into [target-node] arguments))

                "executable:java.util.Stack#push(java.lang.Object)"
                (sequence-node [target-node (raw ".Push(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Stack#pop()"
                (sequence-node [target-node (raw ".Pop()")])

                "executable:java.util.Stack#peek()"
                (sequence-node [target-node (raw ".Peek()")])

                "executable:java.util.Vector#isEmpty()"
                (sequence-node [target-node (raw ".IsEmpty")])

                "executable:java.util.Vector#size()"
                (sequence-node [target-node (raw ".Count")])

                "executable:java.util.Vector#get(int)"
                (sequence-node [target-node (raw ".Get(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Vector#addAll(java.util.Collection)"
                (sequence-node [target-node (raw ".AddAll(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Vector#subList(int,int)"
                (sequence-node [target-node (raw ".SubList(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.util.Map#isEmpty()"
                 "executable:java.util.TreeMap#isEmpty()")
                (compat-call "MapIsEmpty" [target-node])

                "executable:java.util.Set#addAll(java.util.Collection)"
                (compat-call "AddAll" (into [target-node] arguments))

                "executable:java.util.Set#isEmpty()"
                (compat-call "CollectionIsEmpty" [target-node])

                "executable:java.util.Set#iterator()"
                (compat-call "Iterator" [target-node])

                "executable:java.util.Set#size()"
                (sequence-node [target-node (raw ".Count")])

                ("executable:java.util.SortedMap#entrySet()"
                 "executable:java.util.TreeMap#entrySet()")
                (compat-call "MapEntrySet" [target-node])

                ("executable:java.util.SortedMap#firstKey()"
                 "executable:java.util.TreeMap#firstKey()")
                (compat-call "SortedFirstKey" [target-node])

                ("executable:java.util.SortedMap#lastKey()"
                 "executable:java.util.TreeMap#lastKey()")
                (compat-call "SortedLastKey" [target-node])

                ("executable:java.util.SortedMap#subMap(java.lang.Object,java.lang.Object)"
                 "executable:java.util.TreeMap#subMap(java.lang.Object,java.lang.Object)")
                (compat-call "SortedSubMap" (into [target-node] arguments))

                "executable:java.util.SortedSet#headSet(java.lang.Object)"
                (compat-call "SortedHeadSet" (into [target-node] arguments))

                "executable:java.util.SortedSet#first()"
                (compat-call "SortedFirst" [target-node])

                "executable:java.util.SortedSet#last()"
                (compat-call "SortedLast" [target-node])

                ("executable:java.util.SortedSet#subSet(java.lang.Object,java.lang.Object)"
                 "executable:java.util.TreeSet#subSet(java.lang.Object,java.lang.Object)")
                (compat-call "SortedSubSet" (into [target-node] arguments))

                "executable:java.util.TimeZone#clone()"
                (compat-call "Clone" [target-node])

                "executable:java.util.TimeZone#getTimeZone(java.lang.String)"
                (compat-call "GetTimeZone" arguments)

                "executable:java.util.TimeZone#getRawOffset()"
                (compat-call "TimeZoneRawOffset" [target-node])

                "executable:java.util.TimeZone#setID(java.lang.String)"
                (compat-call "TimeZoneSetId" (into [target-node] arguments))

                "executable:java.util.TimeZone#getID()"
                (compat-call "TimeZoneId" [target-node])

                "executable:java.util.TimeZone#getOffset(long)"
                (compat-call "TimeZoneOffset" (into [target-node] arguments))

                "executable:java.util.TimeZone#setRawOffset(int)"
                (compat-call "TimeZoneSetRawOffset"
                             (into [target-node] arguments))

                "executable:javax.xml.namespace.QName#getLocalPart()"
                (sequence-node [target-node (raw ".Name")])

                "executable:javax.xml.namespace.QName#getNamespaceURI()"
                (sequence-node [target-node (raw ".Namespace")])

                "executable:javax.xml.namespace.QName#getPrefix()"
                (compat-call "XmlQualifiedNamePrefix" [target-node])

                "executable:javax.xml.parsers.DocumentBuilderFactory#newInstance()"
                (raw "global::DripSharp.Runtime.JavaCompat.NewXmlReaderSettings()")

                "executable:javax.xml.parsers.DocumentBuilderFactory#newDocumentBuilder()"
                (compat-call "XmlReaderSettingsClone" [target-node])

                "executable:javax.xml.parsers.DocumentBuilderFactory#setFeature(java.lang.String,boolean)"
                (compat-call "XmlReaderSetFeature" (into [target-node] arguments))

                "executable:javax.xml.parsers.DocumentBuilderFactory#setXIncludeAware(boolean)"
                (compat-call "XmlReaderSetXIncludeAware" (into [target-node] arguments))

                "executable:javax.xml.parsers.DocumentBuilderFactory#setExpandEntityReferences(boolean)"
                (compat-call "XmlReaderSetExpandEntityReferences"
                             (into [target-node] arguments))

                "executable:javax.xml.parsers.DocumentBuilderFactory#setIgnoringComments(boolean)"
                (sequence-node [target-node (raw ".IgnoreComments = ")
                                (first arguments)])

                "executable:javax.xml.parsers.DocumentBuilderFactory#setNamespaceAware(boolean)"
                (compat-call "XmlReaderSetNamespaceAware"
                             (into [target-node] arguments))

                "executable:javax.xml.parsers.DocumentBuilder#newDocument()"
                (raw "new global::System.Xml.XmlDocument()")

                "executable:javax.xml.parsers.DocumentBuilder#parse(java.io.InputStream)"
                (compat-call "XmlParse" (into [target-node] arguments))

                "executable:javax.xml.parsers.DocumentBuilder#setErrorHandler(org.xml.sax.ErrorHandler)"
                (compat-call "XmlSetErrorHandler" (into [target-node] arguments))

                "executable:javax.xml.transform.TransformerFactory#newInstance()"
                (raw "new global::System.Xml.XmlWriterSettings()")

                "executable:javax.xml.transform.TransformerFactory#newTransformer()"
                (compat-call "XmlWriterSettingsClone" [target-node])

                "executable:javax.xml.transform.Transformer#setOutputProperty(java.lang.String,java.lang.String)"
                (compat-call "XmlSetOutputProperty" (into [target-node] arguments))

                "executable:javax.xml.transform.Transformer#transform(javax.xml.transform.Source,javax.xml.transform.Result)"
                (compat-call "XmlTransform" (into [target-node] arguments))

                "executable:org.w3c.dom.NamedNodeMap#getLength()"
                (sequence-node [target-node (raw ".Count")])

                "executable:org.w3c.dom.NamedNodeMap#item(int)"
                (compat-call "XmlAttributeItem" (into [target-node] arguments))

                "executable:org.w3c.dom.NamedNodeMap#getNamedItem(java.lang.String)"
                (sequence-node [target-node (raw ".GetNamedItem(")
                                (first arguments) (raw ")")])

                "executable:org.w3c.dom.Node#getAttributes()"
                (sequence-node [target-node (raw ".Attributes!")])

                "executable:org.w3c.dom.Node#getChildNodes()"
                (sequence-node [target-node (raw ".ChildNodes")])

                "executable:org.w3c.dom.Node#getFirstChild()"
                (sequence-node [target-node (raw ".FirstChild")])

                "executable:org.w3c.dom.Node#getLocalName()"
                (sequence-node [target-node (raw ".LocalName")])

                "executable:org.w3c.dom.Node#getNamespaceURI()"
                (compat-call "XmlNodeNamespaceUri" [target-node])

                "executable:org.w3c.dom.Node#getNextSibling()"
                (sequence-node [target-node (raw ".NextSibling")])

                "executable:org.w3c.dom.Node#getNodeName()"
                (sequence-node [target-node (raw ".Name")])

                "executable:org.w3c.dom.Node#getNodeValue()"
                (sequence-node [target-node (raw ".Value")])

                "executable:org.w3c.dom.Node#getOwnerDocument()"
                (sequence-node [target-node (raw ".OwnerDocument!")])

                "executable:org.w3c.dom.Node#getPrefix()"
                (compat-call "XmlNodePrefix" [target-node])

                "executable:org.w3c.dom.Node#getTextContent()"
                (sequence-node [target-node (raw ".InnerText")])

                "executable:org.w3c.dom.Node#appendChild(org.w3c.dom.Node)"
                (sequence-node [target-node (raw ".AppendChild(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.Node#removeChild(org.w3c.dom.Node)"
                (sequence-node [target-node (raw ".RemoveChild(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.Attr#getValue()"
                (sequence-node [target-node (raw ".Value")])

                "executable:org.w3c.dom.CharacterData#getData()"
                (sequence-node [target-node (raw ".Data")])

                "executable:org.w3c.dom.Document#createElementNS(java.lang.String,java.lang.String)"
                (compat-call "XmlCreateElementNs" (into [target-node] arguments))

                "executable:org.w3c.dom.Document#createElement(java.lang.String)"
                (sequence-node [target-node (raw ".CreateElement(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.Document#createProcessingInstruction(java.lang.String,java.lang.String)"
                (sequence-node [target-node (raw ".CreateProcessingInstruction(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.Document#getDocumentElement()"
                (sequence-node [target-node (raw ".DocumentElement!")])

                "executable:org.w3c.dom.Document#getInputEncoding()"
                (compat-call "XmlInputEncoding" [target-node])

                "executable:org.w3c.dom.Document#getXmlEncoding()"
                (compat-call "XmlEncoding" [target-node])

                "executable:org.w3c.dom.Element#setAttribute(java.lang.String,java.lang.String)"
                (sequence-node [target-node (raw ".SetAttribute(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.Element#setAttributeNS(java.lang.String,java.lang.String,java.lang.String)"
                (compat-call "XmlSetAttributeNs" (into [target-node] arguments))

                "executable:org.w3c.dom.Element#getAttribute(java.lang.String)"
                (sequence-node [target-node (raw ".GetAttribute(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.Element#getElementsByTagName(java.lang.String)"
                (sequence-node [target-node (raw ".GetElementsByTagName(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.Element#getTagName()"
                (sequence-node [target-node (raw ".Name")])

                "executable:org.w3c.dom.Element#getAttributeNodeNS(java.lang.String,java.lang.String)"
                (sequence-node [target-node (raw ".GetAttributeNode(")
                                (second arguments) (raw ", ")
                                (first arguments) (raw ")")])

                "executable:org.w3c.dom.Node#setTextContent(java.lang.String)"
                (sequence-node [target-node (raw ".InnerText = ")
                                (first arguments)])

                "executable:org.w3c.dom.NodeList#getLength()"
                (sequence-node [target-node (raw ".Count")])

                "executable:org.w3c.dom.NodeList#item(int)"
                (sequence-node [target-node (raw ".Item(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:org.w3c.dom.ProcessingInstruction#getData()"
                (sequence-node [target-node (raw ".Data")])

                "executable:javax.xml.xpath.XPathFactory#newInstance()"
                (raw "global::DripSharp.Runtime.JavaXPathFactory.Instance")

                "executable:javax.xml.xpath.XPathFactory#newXPath()"
                (sequence-node [target-node (raw ".NewXPath()")])

                ("executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object)"
                 "executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object,javax.xml.namespace.QName)")
                (sequence-node [target-node (raw ".Evaluate(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.regex.Matcher#end()"
                (sequence-node [target-node (raw ".End()")])

                "executable:java.util.regex.Matcher#find()"
                (sequence-node [target-node (raw ".Find()")])

                "executable:java.util.regex.Matcher#find(int)"
                (sequence-node
                 [target-node (raw ".Find(") (sequence-node arguments ", ")
                  (raw ")")])

                "executable:java.util.regex.Matcher#group()"
                (sequence-node [target-node (raw ".Group()")])

                "executable:java.util.regex.Matcher#group(int)"
                (sequence-node [target-node (raw ".Group(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.regex.Matcher#replaceAll(java.lang.String)"
                (sequence-node [target-node (raw ".ReplaceAll(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.regex.Matcher#start()"
                (sequence-node [target-node (raw ".Start()")])

                "executable:java.util.regex.Pattern#matches(java.lang.String,java.lang.CharSequence)"
                (compat-call "StringMatches" [(second arguments) (first arguments)])

                "executable:java.util.stream.Stream#anyMatch(java.util.function.Predicate)"
                (compat-call "Any" (into [target-node] arguments))

                "executable:java.util.stream.Stream#allMatch(java.util.function.Predicate)"
                (compat-call "AllValues" (into [target-node] arguments))

                "executable:java.util.stream.Stream#noneMatch(java.util.function.Predicate)"
                (compat-call "NoValues" (into [target-node] arguments))

                "executable:java.util.stream.Stream#distinct()"
                (sequence-node
                 [(raw "global::System.Linq.Enumerable.Distinct(")
                  target-node (raw ")")])

                "executable:java.util.stream.Stream#count()"
                (sequence-node
                 [(raw "global::System.Linq.Enumerable.LongCount(") target-node
                  (raw ")")])

                "executable:java.util.stream.Stream#reduce(java.util.function.BinaryOperator)"
                (compat-call "ReduceOptional" (into [target-node] arguments))

                "executable:java.util.stream.Stream#findFirst()"
                (compat-call "FindFirstOptional" [target-node])

                "executable:java.util.stream.IntStream#toArray()"
                (sequence-node
                 [(raw "global::System.Linq.Enumerable.ToArray(")
                  target-node (raw ")")])

                "executable:java.util.zip.CRC32#update(byte[],int,int)"
                (sequence-node [target-node (raw ".Update(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.zip.CRC32#getValue()"
                (sequence-node [target-node (raw ".GetValue()")])

                "executable:java.util.Collections#emptyList()"
                (sequence-node
                 [(raw "global::System.Array.Empty<")
                  (type-node @ctx-holder (collection-element-type element))
                  (raw ">()")])

                "executable:java.util.Collections#emptyIterator()"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.EmptyJavaIterator")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "()")])

                "executable:java.util.Collections#emptyMap()"
                (let [type-arguments (.getActualTypeArguments (.getType element))]
                  (sequence-node
                   [(csharp/generic-name
                     (raw "global::DripSharp.Runtime.JavaCompat.EmptyMap")
                     (mapv #(type-node @ctx-holder %) type-arguments))
                    (raw "()")]))

                "executable:java.util.Collections#emptySet()"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.EmptySet")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "()")])

                "executable:java.util.Collections#singletonList(java.lang.Object)"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.ListOf")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "(") (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Collections#synchronizedMap(java.util.Map)"
                (first arguments)

                "executable:java.util.Collections#synchronizedList(java.util.List)"
                (compat-call "SynchronizedList" arguments)

                "executable:java.util.Collections#unmodifiableList(java.util.List)"
                (compat-call "UnmodifiableList" arguments)

                "executable:java.util.Collections#unmodifiableMap(java.util.Map)"
                (compat-call "UnmodifiableMap" arguments)

                "executable:java.net.URI#getHost()"
                (sequence-node [(compat-call "UriHost" [target-node]) (raw "!")])

                "executable:java.net.URI#getPort()"
                (compat-call "UriPort" [target-node])

                "executable:java.net.URI#getScheme()"
                (sequence-node [(compat-call "UriScheme" [target-node]) (raw "!")])

                "executable:java.net.URI#getUserInfo()"
                (sequence-node [(compat-call "UriUserInfo" [target-node]) (raw "!")])

                "executable:java.net.URI#getRawPath()"
                (sequence-node [(compat-call "UriRawPath" [target-node]) (raw "!")])

                "executable:java.net.URI#getPath()"
                (sequence-node [(compat-call "UriPath" [target-node]) (raw "!")])

                "executable:java.net.URI#getRawQuery()"
                (sequence-node [(compat-call "UriRawQuery" [target-node]) (raw "!")])

                "executable:java.net.URI#getRawFragment()"
                (sequence-node [(compat-call "UriRawFragment" [target-node]) (raw "!")])

                "executable:java.net.URI#equals(java.lang.Object)"
                (compat-call "Equals" (into [target-node] arguments))

                "executable:java.net.URI#toString()"
                (sequence-node [target-node (raw ".OriginalString")])

                "executable:java.net.URI#create(java.lang.String)"
                (sequence-node [(raw "new global::System.Uri(")
                                (sequence-node arguments ", ")
                                (raw ", global::System.UriKind.RelativeOrAbsolute)")])

                "executable:java.lang.String#toUpperCase()"
                (sequence-node [target-node (raw ".ToUpper()")])

                "executable:java.lang.String#toLowerCase()"
                (sequence-node [target-node (raw ".ToLowerInvariant()")])

                "executable:java.lang.Object#toString()"
                (sequence-node [target-node (raw ".ToString()!")])

                "executable:java.lang.CharSequence#toString()"
                target-node

                "executable:java.lang.CharSequence#length()"
                (sequence-node [target-node (raw ".Length")])

                "executable:java.lang.CharSequence#charAt(int)"
                (sequence-node [target-node (raw "[") (first arguments) (raw "]")])

                "executable:java.lang.Throwable#getLocalizedMessage()"
                (sequence-node [target-node (raw ".Message")])

                "executable:java.lang.String#format(java.lang.String,java.lang.Object[])"
                (compat-call "JavaStringFormat" arguments)

                "executable:java.lang.String#trim()"
                (compat-call "StringTrim" [target-node])

                "executable:java.lang.String#split(java.lang.String)"
                (compat-call "StringSplit" (into [target-node] (conj arguments (raw "0"))))

                "executable:java.lang.String#split(java.lang.String,int)"
                (compat-call "StringSplit" (into [target-node] arguments))

                "executable:java.lang.String#length()"
                (sequence-node [target-node (raw ".Length")])

                "executable:java.lang.String#isEmpty()"
                (sequence-node [(raw "(") target-node (raw ".Length == 0)")])

                "executable:java.lang.String#startsWith(java.lang.String)"
                (compat-call "StringStartsWith" (into [target-node] arguments))

                "executable:java.lang.String#startsWith(java.lang.String,int)"
                (compat-call "StringStartsWith" (into [target-node] arguments))

                "executable:java.lang.String#endsWith(java.lang.String)"
                (compat-call "StringEndsWith" (into [target-node] arguments))

                "executable:java.lang.String#substring(int)"
                (sequence-node [target-node (raw ".Substring(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.String#substring(int,int)"
                (compat-call "StringSubstring" (into [target-node] arguments))

                "executable:java.lang.String#indexOf(int)"
                (compat-call "StringIndexOf" (into [target-node] arguments))

                "executable:java.lang.String#indexOf(int,int)"
                (compat-call "StringIndexOf" (into [target-node] arguments))

                "executable:java.lang.String#lastIndexOf(int)"
                (compat-call "StringLastIndexOf" (into [target-node] arguments))

                "executable:java.lang.String#contains(java.lang.CharSequence)"
                (compat-call "StringContains" (into [target-node] arguments))

                "executable:java.lang.String#matches(java.lang.String)"
                (compat-call "StringMatches" (into [target-node] arguments))

                "executable:java.lang.String#hashCode()"
                (compat-call "HashCode" [target-node])

                "executable:java.lang.String#equals(java.lang.Object)"
                (compat-call "Equals" (into [target-node] arguments))

                "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
                (compat-call "EqualsIgnoreCase" (into [target-node] arguments))

                "executable:java.lang.String#toCharArray()"
                (sequence-node [target-node (raw ".ToCharArray()")])

                ("executable:java.lang.String#getBytes(java.nio.charset.Charset)"
                 "executable:java.lang.String#getBytes(java.lang.String)")
                (compat-call "StringGetBytes" (into [target-node] arguments))

                "executable:java.lang.String#getBytes()"
                (compat-call "StringGetBytes"
                             [target-node (raw "global::System.Text.Encoding.UTF8")])

                "executable:java.lang.String#join(java.lang.CharSequence,java.lang.Iterable)"
                (compat-call "StringJoin" arguments)

                "executable:java.lang.Integer#toString(int,int)"
                (compat-call "ToStringRadix" arguments)

                "executable:java.lang.Integer#toString(int)"
                (compat-call "StringValueOf" arguments)

                "executable:java.lang.Integer#parseInt(java.lang.String)"
                (compat-call "ParseInt" (conj arguments (raw "10")))

                ("executable:java.lang.Long#parseLong(java.lang.String)"
                 "executable:java.lang.Long#parseLong(java.lang.String,int)")
                (compat-call "ParseLong" arguments)

                "executable:java.lang.Long#toString(long)"
                (compat-call "StringValueOf" arguments)

                ("executable:java.lang.Math#min(double,double)"
                 "executable:java.lang.Math#min(float,float)"
                 "executable:java.lang.Math#min(long,long)"
                 "executable:java.lang.Math#min(int,int)")
                (sequence-node [(raw "global::System.Math.Min(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.lang.Math#max(double,double)"
                 "executable:java.lang.Math#max(float,float)"
                 "executable:java.lang.Math#max(long,long)"
                 "executable:java.lang.Math#max(int,int)")
                (sequence-node [(raw "global::System.Math.Max(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Math#toIntExact(long)"
                (compat-call "ToIntExact" arguments)

                "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)"
                (compat-call "ArrayCopy" arguments)

                "executable:java.lang.System#currentTimeMillis()"
                (raw "global::System.DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()")

                "executable:java.lang.ThreadLocal#withInitial(java.util.function.Supplier)"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaThreadLocal")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw ".WithInitial(") (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.ThreadLocal#get()"
                (sequence-node [target-node (raw ".Get()")])

                "executable:java.lang.ThreadLocal#set(java.lang.Object)"
                (sequence-node [target-node (raw ".Set(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.util.Arrays#equals(byte[],byte[])"
                 "executable:java.util.Arrays#equals(int[],int[])"
                 "executable:java.util.Arrays#equals(float[],float[])"
                 "executable:java.util.Arrays#equals(double[],double[])")
                (compat-call "ArrayEquals" arguments)

                ("executable:java.util.Arrays#hashCode(byte[])"
                 "executable:java.util.Arrays#hashCode(int[])"
                 "executable:java.util.Arrays#hashCode(float[])")
                (compat-call "ArrayHash" arguments)

                "executable:java.util.Arrays#asList(java.lang.Object[])"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.AsList")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "(") (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Enum#name()"
                (compat-call "EnumName" [target-node])

                "executable:java.lang.Enum#ordinal()"
                (compat-call "EnumOrdinal" [target-node])

                "executable:java.lang.Integer#parseInt(java.lang.String,int)"
                (compat-call "ParseInt" arguments)

                "executable:java.net.Socket#getInputStream()"
                (compat-call "SocketStream" [target-node])

                "executable:java.net.Socket#getOutputStream()"
                (compat-call "SocketStream" [target-node])

                "executable:java.net.Socket#getRemoteSocketAddress()"
                (sequence-node [target-node (raw ".RemoteEndPoint")])

                "executable:java.net.Socket#close()"
                (sequence-node [target-node (raw ".Close()")])

                "executable:java.net.Socket#isClosed()"
                (compat-call "SocketIsClosed" [target-node])

                "executable:java.net.Socket#isConnected()"
                (compat-call "SocketIsConnected" [target-node])

                "executable:java.net.Socket#setSoTimeout(int)"
                (compat-call "SocketSetSoTimeout" (into [target-node] arguments))

                "executable:java.net.ServerSocket#accept()"
                (sequence-node [target-node (raw ".Accept()")])

                "executable:java.net.ServerSocket#close()"
                (sequence-node [target-node (raw ".Close()")])

                "executable:java.net.ServerSocket#isClosed()"
                (sequence-node [target-node (raw ".IsClosed()")])

                "executable:java.net.InetSocketAddress#getAddress()"
                (compat-call "InetSocketAddressAddress" [target-node])

                "executable:java.net.URL#openStream()"
                (compat-call "OpenUrlStream" [target-node])

                "executable:java.net.URLDecoder#decode(java.lang.String,java.lang.String)"
                (compat-call "UrlDecode" arguments)

                "executable:java.security.KeyStore#getDefaultType()"
                (raw "global::DripSharp.Runtime.JavaKeyStore.GetDefaultType()")

                "executable:java.security.KeyStore#getInstance(java.lang.String)"
                (sequence-node [(raw "global::DripSharp.Runtime.JavaKeyStore.GetInstance(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.security.KeyStore#load(java.io.InputStream,char[])"
                (sequence-node [target-node (raw ".Load(")
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

                "executable:java.util.Enumeration#nextElement()"
                (sequence-node [target-node (raw ".Next()")])

                "executable:java.util.Enumeration#hasMoreElements()"
                (sequence-node [target-node (raw ".HasNext()")])

                "executable:java.security.MessageDigest#getInstance(java.lang.String)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaMessageDigest.GetInstance(")
                  (sequence-node arguments ", ") (raw ")")])

                ("executable:java.security.MessageDigest#update(byte)"
                 "executable:java.security.MessageDigest#update(byte[])"
                 "executable:java.security.MessageDigest#update(byte[],int,int)")
                (sequence-node [target-node (raw ".Update(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.security.MessageDigest#digest()"
                 "executable:java.security.MessageDigest#digest(byte[])")
                (sequence-node [target-node (raw ".Digest(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.security.MessageDigest#isEqual(byte[],byte[])"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaMessageDigest.IsEqual(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Random#nextBytes(byte[])"
                (sequence-node [target-node (raw ".NextBytes(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Random#nextInt()"
                (sequence-node [target-node (raw ".NextInt()")])

                "executable:javax.crypto.Cipher#getInstance(java.lang.String)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCipher.GetInstance(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:javax.crypto.Cipher#getMaxAllowedKeyLength(java.lang.String)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaCipher.GetMaxAllowedKeyLength(")
                  (sequence-node arguments ", ") (raw ")")])

                ("executable:javax.crypto.Cipher#init(int,java.security.Key)"
                 "executable:javax.crypto.Cipher#init(int,java.security.Key,java.security.spec.AlgorithmParameterSpec)")
                (sequence-node [target-node (raw ".Init(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:javax.crypto.Cipher#update(byte[],int,int)"
                (sequence-node [target-node (raw ".Update(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:javax.crypto.Cipher#doFinal()"
                 "executable:javax.crypto.Cipher#doFinal(byte[])")
                (sequence-node [target-node (raw ".DoFinal(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.Bidi#getBaseLevel()"
                (sequence-node [target-node (raw ".GetBaseLevel()")])

                "executable:java.text.Bidi#getRunCount()"
                (sequence-node [target-node (raw ".GetRunCount()")])

                "executable:java.text.Bidi#getRunLevel(int)"
                (sequence-node [target-node (raw ".GetRunLevel(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.Bidi#getRunLimit(int)"
                (sequence-node [target-node (raw ".GetRunLimit(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.Bidi#getRunStart(int)"
                (sequence-node [target-node (raw ".GetRunStart(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.Bidi#isMixed()"
                (sequence-node [target-node (raw ".IsMixed()")])

                "executable:java.text.Bidi#reorderVisually(byte[],int,java.lang.Object[],int,int)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaBidi.ReorderVisually(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)"
                (compat-call "Normalize" arguments)

                "executable:javax.net.ssl.KeyManagerFactory#getDefaultAlgorithm()"
                (raw "global::DripSharp.Runtime.JavaKeyManagerFactory.GetDefaultAlgorithm()")

                "executable:javax.net.ssl.KeyManagerFactory#getInstance(java.lang.String)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaKeyManagerFactory.GetInstance(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:javax.net.ssl.KeyManagerFactory#init(java.security.KeyStore,char[])"
                (sequence-node [target-node (raw ".Init(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:javax.net.ssl.KeyManagerFactory#getKeyManagers()"
                (sequence-node [target-node (raw ".GetKeyManagers()")])

                "executable:javax.net.ssl.TrustManagerFactory#getDefaultAlgorithm()"
                (raw "global::DripSharp.Runtime.JavaTrustManagerFactory.GetDefaultAlgorithm()")

                "executable:javax.net.ssl.TrustManagerFactory#getInstance(java.lang.String)"
                (sequence-node
                 [(raw "global::DripSharp.Runtime.JavaTrustManagerFactory.GetInstance(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:javax.net.ssl.TrustManagerFactory#init(java.security.KeyStore)"
                (sequence-node [target-node (raw ".Init(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:javax.net.ssl.TrustManagerFactory#getTrustManagers()"
                (sequence-node [target-node (raw ".GetTrustManagers()")])

                "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)"
                (sequence-node [(raw "global::DripSharp.Runtime.JavaSslContext.GetInstance(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:javax.net.ssl.SSLContext#init(javax.net.ssl.KeyManager[],javax.net.ssl.TrustManager[],java.security.SecureRandom)"
                (sequence-node [target-node (raw ".Init(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:javax.net.ssl.SSLSocketFactory#getDefault()"
                (raw "global::DripSharp.Runtime.JavaSocketFactory.Default")

                "executable:javax.net.ssl.SSLContext#getSocketFactory()"
                (sequence-node [target-node (raw ".GetSocketFactory()")])

                "executable:javax.net.ssl.SSLContext#getServerSocketFactory()"
                (sequence-node [target-node (raw ".GetServerSocketFactory()")])

                "executable:javax.net.ServerSocketFactory#createServerSocket(int)"
                (sequence-node [target-node (raw ".CreateServerSocket(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:javax.net.SocketFactory#createSocket()"
                (sequence-node [target-node (raw ".CreateSocket()")])

                "executable:javax.net.SocketFactory#createSocket(java.lang.String,int)"
                (sequence-node [target-node (raw ".CreateSocket(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.OutputStream#flush()"
                (sequence-node [target-node (raw ".Flush()")])

                "executable:java.io.FilterOutputStream#flush()"
                (sequence-node [target-node (raw ".Flush()")])

                ("executable:java.io.OutputStream#close()"
                 "executable:java.io.FilterOutputStream#close()")
                (sequence-node [target-node (raw ".Dispose()")])

                "executable:java.io.Closeable#close()"
                (sequence-node [target-node (raw ".Dispose()")])

                "executable:java.io.InputStream#close()"
                (sequence-node [target-node (raw ".Dispose()")])

                ("executable:java.io.InputStream#available()"
                 "executable:java.io.ByteArrayInputStream#available()")
                (compat-call "InputStreamAvailable" [target-node])

                "executable:java.io.File#toPath()"
                (sequence-node [(raw "new global::DripSharp.Runtime.JavaPath(")
                                target-node (raw ".FullName)")])

                "executable:java.io.File#length()"
                (sequence-node [target-node (raw ".Length")])

                "executable:java.io.File#delete()"
                (compat-call "FileDelete" [target-node])

                "executable:java.io.File#exists()"
                (compat-call "FileExists" [target-node])

                "executable:java.io.File#getAbsolutePath()"
                (sequence-node [target-node (raw ".FullName")])

                "executable:java.io.File#isDirectory()"
                (compat-call "FileIsDirectory" [target-node])

                "executable:java.io.File#setReadable(boolean,boolean)"
                (compat-call "SetFileReadable" (into [target-node] arguments))

                "executable:java.io.File#setWritable(boolean,boolean)"
                (compat-call "SetFileWritable" (into [target-node] arguments))

                "executable:java.io.File#setExecutable(boolean,boolean)"
                (compat-call "SetFileExecutable" (into [target-node] arguments))

                "executable:java.io.RandomAccessFile#close()"
                (sequence-node [target-node (raw ".Dispose()")])

                "executable:java.nio.file.Files#newInputStream(java.nio.file.Path,java.nio.file.OpenOption[])"
                (compat-call "OpenInputStream" arguments)

                "executable:java.nio.file.Path#toFile()"
                (sequence-node [(raw "new global::System.IO.FileInfo(")
                                target-node (raw ")")])

                "executable:java.io.ByteArrayOutputStream#writeTo(java.io.OutputStream)"
                (compat-call "MemoryStreamWriteTo" (into [target-node] arguments))

                "executable:java.io.ByteArrayOutputStream#toByteArray()"
                (compat-call "ToSignedBytes" [target-node])

                "executable:java.io.ByteArrayOutputStream#size()"
                (sequence-node [(raw "checked((int)") target-node
                                (raw ".Length)")])

                "executable:java.io.ByteArrayOutputStream#write(int)"
                (compat-call "OutputStreamWrite" (into [target-node] arguments))

                "executable:java.io.OutputStream#write(byte[])"
                (compat-call "OutputStreamWrite" (into [target-node] arguments))

                "executable:java.io.OutputStream#write(byte[],int,int)"
                (compat-call "OutputStreamWrite" (into [target-node] arguments))

                "executable:java.io.OutputStream#write(int)"
                (compat-call "OutputStreamWrite" (into [target-node] arguments))

                "executable:java.io.PrintStream#println(java.lang.String)"
                (sequence-node [target-node (raw ".WriteLine(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.PipedOutputStream#connect(java.io.PipedInputStream)"
                (sequence-node [target-node (raw ".Connect(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.PipedOutputStream#write(byte[],int,int)"
                (compat-call "OutputStreamWrite" (into [target-node] arguments))

                "executable:java.io.PipedOutputStream#close()"
                (sequence-node [target-node (raw ".Dispose()")])

                "executable:java.io.PipedOutputStream#flush()"
                (sequence-node [target-node (raw ".Flush()")])

                "executable:java.io.InputStream#read()"
                (compat-call "InputStreamRead" [target-node])

                ("executable:java.io.InputStream#read(byte[])"
                 "executable:java.io.BufferedInputStream#read(byte[])")
                (compat-call "InputStreamRead" (into [target-node] arguments))

                ("executable:java.io.InputStream#read(byte[],int,int)"
                 "executable:java.io.BufferedInputStream#read(byte[],int,int)")
                (compat-call "InputStreamRead" (into [target-node] arguments))

                ("executable:java.io.FilterInputStream#read()"
                 "executable:java.io.FilterInputStream#read(byte[])"
                 "executable:java.io.FilterInputStream#read(byte[],int,int)")
                (if (instance? CtSuperAccess target)
                  (sequence-node [(raw "base.Read(")
                                  (sequence-node arguments ", ")
                                  (raw ")")])
                  (compat-call "InputStreamRead" (into [target-node] arguments)))

                "executable:java.io.FilterInputStream#skip(long)"
                (sequence-node [target-node (raw ".Skip(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.ByteArrayInputStream#read()"
                (compat-call "InputStreamRead" [target-node])

                "executable:java.io.PushbackInputStream#read()"
                (compat-call "InputStreamRead" [target-node])

                "executable:java.io.PushbackInputStream#unread(int)"
                (sequence-node [target-node (raw ".Unread(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.PushbackInputStream#unread(byte[],int,int)"
                (sequence-node [target-node (raw ".Unread(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.io.PushbackInputStream#close()"
                (sequence-node [target-node (raw ".Close()")])

                "executable:java.util.zip.GZIPInputStream#read(byte[],int,int)"
                (compat-call "InputStreamRead" (into [target-node] arguments))

                "executable:java.lang.StringBuilder#append(java.lang.String)"
                (sequence-node [target-node (raw ".Append(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.StringBuilder#append(char)"
                (sequence-node [target-node (raw ".Append(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.StringBuilder#append(int)"
                (sequence-node [target-node (raw ".Append(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.lang.StringBuilder#append(long)"
                 "executable:java.lang.StringBuilder#append(float)"
                 "executable:java.lang.StringBuilder#append(double)"
                 "executable:java.lang.StringBuilder#append(boolean)")
                (compat-call "StringBuilderAppendInvariant"
                             (into [target-node] arguments))

                ("executable:java.lang.StringBuilder#insert(int,char)"
                 "executable:java.lang.StringBuilder#insert(int,java.lang.String)")
                (sequence-node [target-node (raw ".Insert(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.StringBuilder#appendCodePoint(int)"
                (compat-call "AppendCodePoint" (into [target-node] arguments))

                "executable:java.lang.StringBuilder#length()"
                (sequence-node [target-node (raw ".Length")])

                "executable:java.lang.AbstractStringBuilder#length()"
                (sequence-node [target-node (raw ".Length")])

                "executable:java.lang.StringBuilder#toString()"
                (sequence-node [target-node (raw ".ToString()")])

                "executable:java.util.Collection#stream()"
                target-node

                "executable:java.lang.Object#getClass()"
                (sequence-node
                 [(raw "((object)(") target-node (raw ")).GetType()")])

                "executable:java.lang.Class#forName(java.lang.String)"
                (compat-call "ClassForName" arguments)

                "executable:java.lang.Class#getDeclaredField(java.lang.String)"
                (compat-call "GetDeclaredField" (into [target-node] arguments))

                "executable:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])"
                (compat-call "GetMethod" (into [target-node] arguments))

                "executable:java.lang.Class#getName()"
                (sequence-node [(raw "(") target-node (raw ".FullName ?? ")
                                target-node (raw ".Name)")])

                "executable:java.lang.Class#getSimpleName()"
                (sequence-node [target-node (raw ".Name")])

                "executable:java.lang.Class#isInstance(java.lang.Object)"
                (sequence-node [target-node (raw ".IsInstanceOfType(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Class#getClassLoader()"
                (sequence-node [target-node (raw ".Assembly")])

                "executable:java.lang.reflect.Field#get(java.lang.Object)"
                (sequence-node [target-node (raw ".GetValue(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.lang.reflect.Field#setAccessible(boolean)"
                 "executable:java.lang.reflect.Method#setAccessible(boolean)")
                (compat-call "SetAccessible" (into [target-node] arguments))

                "executable:java.lang.Throwable#getCause()"
                (sequence-node
                 [target-node (raw ".InnerException")
                  (when (empty? (.getTypeCasts element)) (raw "!"))])

                "executable:java.lang.Throwable#getMessage()"
                (sequence-node [target-node (raw ".Message")])

                "executable:java.net.URISyntaxException#getMessage()"
                (sequence-node [target-node (raw ".Message")])

                "executable:java.net.URISyntaxException#getReason()"
                (compat-call "UriSyntaxReason" [target-node])

                "executable:java.net.URISyntaxException#getIndex()"
                (compat-call "UriSyntaxIndex" [target-node])

                "executable:java.lang.Throwable#printStackTrace()"
                (compat-call "PrintStackTrace" [target-node])

                "executable:java.time.Duration#ofSeconds(long)"
                (sequence-node [(raw "global::System.TimeSpan.FromSeconds(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.time.Duration#toMillis()"
                (sequence-node [(raw "checked((long)") target-node
                                (raw ".TotalMilliseconds)")])

                "executable:java.time.Instant#now()"
                (raw "global::System.DateTimeOffset.UtcNow")

                "executable:java.time.Instant#plus(java.time.temporal.TemporalAmount)"
                (sequence-node [target-node (raw ".Add(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.time.Instant#isBefore(java.time.Instant)"
                (sequence-node [(raw "(") target-node (raw " < ")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.time.ZonedDateTime#now(java.time.ZoneId)"
                (raw "global::System.DateTimeOffset.UtcNow")

                "executable:java.time.ZonedDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)"
                (compat-call "ParseZonedDateTime" arguments)

                "executable:java.time.LocalDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)"
                (compat-call "ParseLocalDateTime" arguments)

                "executable:java.time.LocalDateTime#atZone(java.time.ZoneId)"
                (compat-call "LocalDateTimeAtZone" (into [target-node] arguments))

                "executable:java.time.ZoneId#of(java.lang.String)"
                (compat-call "ZoneIdOf" arguments)

                "executable:java.time.format.DateTimeFormatter#format(java.time.temporal.TemporalAccessor)"
                (sequence-node [target-node (raw ".Format(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.time.format.DateTimeFormatterBuilder#parseCaseInsensitive()"
                (sequence-node [target-node (raw ".ParseCaseInsensitive()")])

                "executable:java.time.format.DateTimeFormatterBuilder#append(java.time.format.DateTimeFormatter)"
                (sequence-node [target-node (raw ".Append(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.time.format.DateTimeFormatterBuilder#parseLenient()"
                (sequence-node [target-node (raw ".ParseLenient()")])

                "executable:java.time.format.DateTimeFormatterBuilder#appendOffset(java.lang.String,java.lang.String)"
                (sequence-node [target-node (raw ".AppendOffset(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.time.format.DateTimeFormatterBuilder#parseStrict()"
                (sequence-node [target-node (raw ".ParseStrict()")])

                "executable:java.time.format.DateTimeFormatterBuilder#toFormatter()"
                (sequence-node [target-node (raw ".ToFormatter()")])

                "executable:java.net.InetAddress#getLoopbackAddress()"
                (raw "global::System.Net.IPAddress.Loopback")

                "executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)"
                (compat-call "Equals" arguments)

                "executable:java.util.Objects#hash(java.lang.Object[])"
                (compat-call "Hash" arguments)

                "executable:java.util.Objects#requireNonNull(java.lang.Object)"
                (compat-call "RequireNonNull" arguments)

                "executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)"
                (compat-call "RequireNonNull" arguments)

                "executable:java.util.Map#entrySet()"
                (compat-call "MapEntrySet" [target-node])

                ("executable:java.util.Map#containsKey(java.lang.Object)"
                 "executable:java.util.TreeMap#containsKey(java.lang.Object)")
                (compat-call "MapContainsKey" (into [target-node] arguments))

                "executable:java.util.Map#containsValue(java.lang.Object)"
                (compat-call "MapContainsValue" (into [target-node] arguments))

                "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)"
                (compat-call "ComputeIfAbsent" (into [target-node] arguments))

                "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
                (compat-call "ComputeIfAbsent" (into [target-node] arguments))

                "executable:java.util.TreeMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
                (compat-call "ComputeIfAbsent" (into [target-node] arguments))

                "executable:java.util.Map#forEach(java.util.function.BiConsumer)"
                (compat-call "ForEach" (into [target-node] arguments))

                "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)"
                (compat-call "MapGetOrDefault" (into [target-node] arguments))

                ("executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
                 "executable:java.util.concurrent.ConcurrentMap#putIfAbsent(java.lang.Object,java.lang.Object)")
                (compat-call "MapPutIfAbsent" (into [target-node] arguments))

                "executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)"
                (compat-call "MapPutIfAbsent" (into [target-node] arguments))

                "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)"
                (compat-call "MapGetOrDefault" (into [target-node] arguments))

                ("executable:java.util.Map#keySet()"
                 "executable:java.util.TreeMap#keySet()")
                (compat-call "MapKeySet" [target-node])

                "executable:java.util.LinkedHashMap#keySet()"
                (compat-call "MapKeySet" [target-node])

                ("executable:java.util.Map#values()"
                 "executable:java.util.SortedMap#values()"
                 "executable:java.util.TreeMap#values()")
                (sequence-node [target-node (raw ".Values")])

                ("executable:java.util.Map#clear()"
                 "executable:java.util.HashMap#clear()"
                 "executable:java.util.TreeMap#clear()")
                (sequence-node [target-node (raw ".Clear()")])

                ("executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
                 "executable:java.util.TreeMap#put(java.lang.Object,java.lang.Object)")
                (compat-call "MapPut" (into [target-node] arguments))

                "executable:java.util.Map#putAll(java.util.Map)"
                (compat-call "MapPutAll" (into [target-node] arguments))

                "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"
                (compat-call "MapPut" (into [target-node] arguments))

                "executable:java.util.HashMap#putAll(java.util.Map)"
                (compat-call "MapPutAll" (into [target-node] arguments))

                "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"
                (compat-call "MapPut" (into [target-node] arguments))

                ("executable:java.util.Map#size()"
                 "executable:java.util.TreeMap#size()")
                (compat-call "MapCount" [target-node])

                "executable:java.util.HashMap#size()"
                (sequence-node [target-node (raw ".Count")])

                ("executable:java.util.Map#get(java.lang.Object)"
                 "executable:java.util.TreeMap#get(java.lang.Object)")
                (compat-call
                 (if (boxed-primitive-reference? (.getType element))
                   "MapGetNullable"
                   "MapGet")
                 (into [target-node] arguments))

                ("executable:java.util.Map#remove(java.lang.Object)"
                 "executable:java.util.TreeMap#remove(java.lang.Object)")
                (compat-call "MapRemove" (into [target-node] arguments))

                "executable:java.util.HashMap#remove(java.lang.Object)"
                (compat-call "MapRemove" (into [target-node] arguments))

                "executable:java.util.LinkedHashMap#remove(java.lang.Object)"
                (compat-call "MapRemove" (into [target-node] arguments))

                "executable:java.util.Map#hashCode()"
                (compat-call "HashCode" [target-node])

                "executable:java.util.Map$Entry#getKey()"
                (sequence-node [target-node (raw ".Key")])

                "executable:java.lang.Iterable#forEach(java.util.function.Consumer)"
                (compat-call "ForEach" (into [target-node] arguments))

                "executable:java.util.Map$Entry#getValue()"
                (sequence-node [target-node (raw ".Value")])

                "executable:java.util.Map$Entry#setValue(java.lang.Object)"
                (sequence-node [target-node (raw ".SetValue(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Map$Entry#comparingByValue()"
                (let [comparator-arguments
                      (vec (.getActualTypeArguments (.getType element)))
                      comparison
                      (raw
                       (str "(value0, value1) => "
                            "global::DripSharp.Runtime.JavaCompat.CompareNatural("
                            "value0.Value, value1.Value)"))]
                  (if (= 1 (count comparator-arguments))
                    (sequence-node
                     [(raw "global::System.Collections.Generic.Comparer<")
                      (type-node @ctx-holder (first comparator-arguments))
                      (raw ">.Create(") comparison (raw ")")])
                    comparison))

                "executable:java.util.List#isEmpty()"
                (compat-call "ListIsEmpty" [target-node])

                "executable:java.util.ArrayList#isEmpty()"
                (compat-call "ListIsEmpty" [target-node])

                ("executable:java.util.List#add(java.lang.Object)"
                 "executable:java.util.ArrayList#add(java.lang.Object)"
                 "executable:java.util.LinkedList#add(java.lang.Object)")
                (compat-call "Add" (into [target-node] arguments))

                "executable:java.util.LinkedList#addFirst(java.lang.Object)"
                (compat-call "ListAddFirst" (into [target-node] arguments))

                ("executable:java.util.Collection#clear()"
                 "executable:java.util.List#clear()"
                 "executable:java.util.ArrayList#clear()"
                 "executable:java.util.Set#clear()"
                 "executable:java.util.HashSet#clear()")
                (sequence-node [target-node (raw ".Clear()")])

                "executable:java.util.ArrayList#ensureCapacity(int)"
                (sequence-node [target-node (raw ".EnsureCapacity(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.List#remove(java.lang.Object)"
                (compat-call "CollectionRemove" (into [target-node] arguments))

                "executable:java.util.List#removeIf(java.util.function.Predicate)"
                (compat-call "RemoveIf" (into [target-node] arguments))

                "executable:java.util.Collection#removeIf(java.util.function.Predicate)"
                (compat-call "RemoveIf" (into [target-node] arguments))

                "executable:java.util.List#addAll(java.util.Collection)"
                (compat-call "AddAll" (into [target-node] arguments))

                "executable:java.util.List#equals(java.lang.Object)"
                (compat-call "Equals" (into [target-node] arguments))

                "executable:java.util.List#hashCode()"
                (compat-call "HashCode" [target-node])

                "executable:java.util.List#get(int)"
                (compat-call "ListGet" (into [target-node] arguments))

                "executable:java.util.ArrayList#get(int)"
                (compat-call "ListGet" (into [target-node] arguments))

                "executable:java.util.List#contains(java.lang.Object)"
                (compat-call "CollectionContains" (into [target-node] arguments))

                "executable:java.util.List#size()"
                (sequence-node [target-node (raw ".Count")])

                "executable:java.util.ArrayList#size()"
                (sequence-node [target-node (raw ".Count")])

                "executable:java.util.ArrayList#remove(int)"
                (compat-call "ListRemove" (into [target-node] arguments))

                "executable:java.util.List#iterator()"
                (compat-call "Iterator" [target-node])

                "executable:java.util.List#listIterator()"
                (compat-call "ListIterator" [target-node])

                "executable:java.util.List#listIterator(int)"
                (compat-call "ListIterator" (into [target-node] arguments))

                "executable:java.util.List#containsAll(java.util.Collection)"
                (compat-call "ContainsAll" (into [target-node] arguments))

                "executable:java.util.ListIterator#set(java.lang.Object)"
                (sequence-node [target-node (raw ".Set(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.ListIterator#hasPrevious()"
                (sequence-node [target-node (raw ".HasPrevious()")])

                "executable:java.util.ListIterator#previous()"
                (sequence-node [target-node (raw ".Previous()")])

                "executable:java.util.ListIterator#nextIndex()"
                (sequence-node [target-node (raw ".NextIndex()")])

                "executable:java.util.ListIterator#previousIndex()"
                (sequence-node [target-node (raw ".PreviousIndex()")])

                "executable:java.util.ListIterator#add(java.lang.Object)"
                (sequence-node [target-node (raw ".Add(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)"
                (sequence-node [target-node (raw ".Compare(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.util.Iterator#next()"
                 "executable:java.util.ListIterator#next()")
                (sequence-node [target-node (raw ".Next()")])

                ("executable:java.util.Iterator#hasNext()"
                 "executable:java.util.ListIterator#hasNext()")
                (sequence-node [target-node (raw ".HasNext()")])

                ("executable:java.util.Iterator#remove()"
                 "executable:java.util.ListIterator#remove()")
                (sequence-node [target-node (raw ".Remove()")])

                "executable:java.util.Collection#remove(java.lang.Object)"
                (compat-call "CollectionRemove" (into [target-node] arguments))

                ("executable:java.util.Collection#toArray()"
                 "executable:java.util.ArrayList#toArray()"
                 "executable:java.util.List#toArray()")
                (compat-call "ToArray" [target-node])

                ("executable:java.util.Collection#toArray(java.lang.Object[])"
                 "executable:java.util.ArrayList#toArray(java.lang.Object[])"
                 "executable:java.util.List#toArray(java.lang.Object[])"
                 "executable:java.util.Set#toArray(java.lang.Object[])")
                (compat-call "CollectionToArray" (into [target-node] arguments))

                "executable:java.util.Optional#empty()"
                (sequence-node [(type-node @ctx-holder (.getType element))
                                (raw ".Empty()")])

                "executable:java.util.Optional#of(java.lang.Object)"
                (sequence-node [(type-node @ctx-holder (.getType element))
                                (raw ".Of(") (sequence-node arguments ", ")
                                (raw ")")])

                "executable:java.util.Optional#ofNullable(java.lang.Object)"
                (sequence-node [(type-node @ctx-holder (.getType element))
                                (raw ".OfNullable(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Optional#get()"
                (sequence-node [target-node (raw ".Get()")])

                "executable:java.util.Optional#isPresent()"
                (sequence-node [target-node (raw ".IsPresent()")])

                "executable:java.util.Optional#equals(java.lang.Object)"
                (compat-call "Equals" (into [target-node] arguments))

                "executable:java.util.Optional#map(java.util.function.Function)"
                (sequence-node [target-node (raw ".Map(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Optional#orElse(java.lang.Object)"
                (sequence-node [target-node (raw ".OrElse(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Optional#orElseGet(java.util.function.Supplier)"
                (sequence-node [target-node (raw ".OrElseGet(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Optional#ifPresent(java.util.function.Consumer)"
                (sequence-node [target-node (raw ".IfPresent(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Optional#orElseThrow(java.util.function.Supplier)"
                (sequence-node [target-node (raw ".OrElseThrow(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.OptionalInt#isPresent()"
                (sequence-node [target-node (raw ".HasValue")])

                "executable:java.util.OptionalInt#getAsInt()"
                (sequence-node [target-node (raw ".Value")])

                "executable:java.util.OptionalInt#empty()"
                (raw "(int?)null")

                "executable:java.util.OptionalInt#of(int)"
                (first arguments)

                "executable:java.util.OptionalLong#empty()"
                (raw "(long?)null")

                "executable:java.util.OptionalLong#of(long)"
                (first arguments)

                "executable:java.util.OptionalLong#ifPresent(java.util.function.LongConsumer)"
                (compat-call "OptionalLongIfPresent" (into [target-node] arguments))

                "executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)"
                (sequence-node [target-node (raw "(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.function.BiFunction#apply(java.lang.Object,java.lang.Object)"
                (sequence-node [target-node (raw "(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.function.Consumer#accept(java.lang.Object)"
                (sequence-node [target-node (raw "(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.function.Supplier#get()"
                (sequence-node [target-node (raw "()")])

                "executable:java.util.Comparator#reverseOrder()"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.ReverseComparer")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "()")])

                "executable:java.util.EnumSet#of(java.lang.Enum)"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.EnumSetOf")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "(") (sequence-node arguments ", ") (raw ")")])

                ("executable:java.util.Collection#contains(java.lang.Object)"
                 "executable:java.util.Set#contains(java.lang.Object)")
                (compat-call "CollectionContains" (into [target-node] arguments))

                "executable:java.util.HashSet#contains(java.lang.Object)"
                (sequence-node [target-node (raw ".Contains(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Set#equals(java.lang.Object)"
                (compat-call "Equals" (into [target-node] arguments))

                ("executable:java.util.Set#add(java.lang.Object)"
                 "executable:java.util.HashSet#add(java.lang.Object)"
                 "executable:java.util.TreeSet#add(java.lang.Object)")
                (sequence-node [target-node (raw ".Add(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.Set#removeAll(java.util.Collection)"
                (compat-call "RemoveAll" (into [target-node] arguments))

                ("executable:java.util.Set#remove(java.lang.Object)"
                 "executable:java.util.HashSet#remove(java.lang.Object)")
                (sequence-node [target-node (raw ".Remove(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.regex.Pattern#compile(java.lang.String)"
                (compat-call "CompileRegex" arguments)

                "executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)"
                (compat-call "RegexMatcher" (into [target-node] arguments))

                "executable:java.util.regex.Pattern#split(java.lang.CharSequence)"
                (compat-call "RegexSplit"
                             (into [target-node] (conj arguments (raw "0"))))

                "executable:java.util.regex.Matcher#matches()"
                (sequence-node [target-node (raw ".Matches()")])

                "executable:java.util.stream.Stream#of(java.lang.Object[])"
                (compat-call "StreamOf" arguments)

                "executable:java.util.stream.Stream#filter(java.util.function.Predicate)"
                (compat-call "StreamFilter" (into [target-node] arguments))

                ("executable:java.util.stream.Stream#sorted()"
                 "executable:java.util.stream.Stream#sorted(java.util.Comparator)")
                (compat-call "StreamSorted" (into [target-node] arguments))

                "executable:java.util.stream.Stream#forEach(java.util.function.Consumer)"
                (compat-call "ForEach" (into [target-node] arguments))

                "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.LoadServices")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "(") (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.stream.Stream#flatMap(java.util.function.Function)"
                (compat-call "FlatMap" (into [target-node] arguments))

                "executable:java.util.stream.Stream#map(java.util.function.Function)"
                (compat-call "Map" (into [target-node] arguments))

                "executable:java.util.stream.Stream#mapToLong(java.util.function.ToLongFunction)"
                (compat-call "MapToLong" (into [target-node] arguments))

                "executable:java.util.stream.LongStream#sum()"
                (compat-call "Sum" [target-node])

                "executable:java.util.stream.Collectors#toList()"
                (raw "global::DripSharp.Runtime.JavaCompat.ToList<object>()")

                "executable:java.util.stream.Collectors#toSet()"
                (sequence-node
                 [(csharp/generic-name
                   (raw "global::DripSharp.Runtime.JavaCompat.ToSet")
                   [(type-node @ctx-holder (collection-element-type element))])
                  (raw "()")])

                "executable:java.util.stream.Collectors#toCollection(java.util.function.Supplier)"
                (compat-call "ToCollection" arguments)

                "executable:java.util.stream.Stream#collect(java.util.stream.Collector)"
                (case (some-> element .getType .getQualifiedName)
                  "java.util.Set"
                  (sequence-node
                   [(csharp/generic-name
                     (raw "global::DripSharp.Runtime.JavaCompat.SetOfValues")
                     [(type-node @ctx-holder (collection-element-type element))])
                    (raw "(") target-node (raw ")")])

                  "java.util.ArrayList"
                  (sequence-node
                   [(raw "new ") (type-node @ctx-holder (.getType element))
                    (raw "(") target-node (raw ")")])

                  (compat-call "ToListValues" [target-node]))

                "executable:java.util.concurrent.ExecutorService#submit(java.lang.Runnable)"
                (sequence-node [target-node (raw ".Submit(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)"
                (sequence-node [target-node (raw ".Submit(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.concurrent.ExecutorService#shutdown()"
                (sequence-node [target-node (raw ".Shutdown()")])

                "executable:java.util.concurrent.ExecutorService#shutdownNow()"
                (sequence-node [target-node (raw ".ShutdownNow()")])

                "executable:java.util.concurrent.ExecutorService#awaitTermination(long,java.util.concurrent.TimeUnit)"
                (sequence-node [target-node (raw ".AwaitTermination(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.concurrent.Executors#newFixedThreadPool(int,java.util.concurrent.ThreadFactory)"
                (sequence-node
                 [(raw "new global::DripSharp.Runtime.JavaExecutorService(")
                  (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.concurrent.Executors#newSingleThreadExecutor()"
                (raw "new global::DripSharp.Runtime.JavaExecutorService(1)")

                "executable:java.util.concurrent.Future#get(long,java.util.concurrent.TimeUnit)"
                (sequence-node [target-node (raw ".Get(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.concurrent.atomic.AtomicBoolean#get()"
                (sequence-node [target-node (raw ".Get()")])

                "executable:java.util.concurrent.atomic.AtomicBoolean#compareAndSet(boolean,boolean)"
                (sequence-node [target-node (raw ".CompareAndSet(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.concurrent.atomic.AtomicInteger#incrementAndGet()"
                (sequence-node [target-node (raw ".IncrementAndGet()")])

                "executable:java.util.concurrent.atomic.AtomicReference#get()"
                (sequence-node
                 [target-node (raw ".Get()")
                  (when-not (statement-expression? element) (raw "!"))])

                "executable:java.util.concurrent.atomic.AtomicReference#getAndSet(java.lang.Object)"
                (sequence-node [target-node (raw ".GetAndSet(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)"
                (sequence-node [target-node (raw ".Set(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Thread#setDaemon(boolean)"
                (sequence-node [target-node (raw ".SetDaemon(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Thread#setName(java.lang.String)"
                (sequence-node [target-node (raw ".SetName(")
                                (sequence-node arguments ", ") (raw ")")])

                "executable:java.lang.Thread#start()"
                (sequence-node [target-node (raw ".Start()")])

                "executable:java.lang.Thread#currentThread()"
                (raw "global::DripSharp.Runtime.JavaThread.CurrentThread()")

                "executable:java.lang.Thread#interrupt()"
                (sequence-node [target-node (raw ".Interrupt()")])

                "executable:java.lang.Thread#sleep(long)"
                (sequence-node [(raw "global::DripSharp.Runtime.JavaThread.Sleep(")
                                (sequence-node arguments ", ") (raw ")")])

                ("executable:java.lang.Object#<init>()"
                 "executable:java.lang.Enum#<init>(java.lang.String,int)"
                 "executable:java.io.InputStream#<init>()"
                 "executable:java.io.OutputStream#<init>()"
                 "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"
                 "executable:java.io.FilterInputStream#<init>(java.io.InputStream)")
                (raw "")

                (sequence-node
                 [(when target
                    (sequence-node [default-target-node (raw ".")]))
                  (child-node children (.getExecutable element))
                  (when (= :project (:origin occurrence))
                    (project-invocation-type-arguments-node
                     @ctx-holder element declaration))
                  (raw "(")
                  (sequence-node arguments ", ")
                  (raw ")")])))
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
             (if (and
                  (nullable-declaration? @ctx-holder
                                         (:declaration occurrence))
                  (empty? (.getTypeCasts element)))
               (sequence-node [raw-node (raw "!")])
               raw-node)
             node (expression-cast-node @ctx-holder element raw-node)]
         {:node
          (if (contains? #{"executable:java.lang.Object#<init>()"
                           "executable:java.lang.Enum#<init>(java.lang.String,int)"
                           "executable:java.io.InputStream#<init>()"
                           "executable:java.io.OutputStream#<init>()"
                           "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"
                           "executable:java.io.FilterInputStream#<init>(java.io.InputStream)"}
                         (:key occurrence))
            node
            (expression-statement-node element node))}))}

    {:id :java-library.expression/constructor-call
     :class CtConstructorCall
     :emit
     (fn [{:keys [context ^CtConstructorCall element children]}]
       (let [occurrence (constructor-occurrence context element)
             declaration (:declaration occurrence)
             parameter-types
             (executable-parameter-types declaration (.getExecutable element))
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
             arguments (if (member-constructor? (:declaration occurrence))
                         (conj arguments
                               (if-let [target (.getTarget element)]
                                 (child-node children target)
                                 (raw
                                  (if (:outer-type @ctx-holder)
                                    (str "this."
                                         (or (:outer-field-name @ctx-holder)
                                             "__outer"))
                                    "this"))))
                         arguments)
             anonymous-class (anonymous-class-for-call element)
             owner (when anonymous-class (nearest-enclosing-type element))
             captures (when anonymous-class (anonymous-captures anonymous-class))
             outer? (when anonymous-class
                      (anonymous-uses-outer? anonymous-class owner))]
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
             (if-let [adaptation
                      (get (:destination-constructor-adaptations @ctx-holder)
                           (:key occurrence))]
               (adaptation arguments)
               (case (:key occurrence)
                 "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"
                 (sequence-node [(raw "new global::System.Exception(null, ")
                                 (first arguments) (raw ")")])

                 "executable:java.lang.IllegalArgumentException#<init>(java.lang.Throwable)"
                 (sequence-node [(raw "new global::System.ArgumentException(null, ")
                                 (first arguments) (raw ")")])

                 "executable:java.io.IOException#<init>(java.lang.Throwable)"
                 (sequence-node [(raw "new global::System.IO.IOException(null, ")
                                 (first arguments) (raw ")")])

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

                 "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)"
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

                 ("executable:java.io.FileOutputStream#<init>(java.io.File)"
                  "executable:java.io.FileOutputStream#<init>(java.lang.String)")
                 (compat-call "OpenFileOutput" arguments)

                 ("executable:java.io.BufferedReader#<init>(java.io.Reader)"
                  "executable:java.io.BufferedWriter#<init>(java.io.Writer)")
                 (first arguments)

                 "executable:java.io.SequenceInputStream#<init>(java.io.InputStream,java.io.InputStream)"
                 (sequence-node
                  [(raw "new global::DripSharp.Runtime.JavaSequenceInputStream(")
                   (sequence-node arguments ", ") (raw ")")])

                 "executable:java.math.BigInteger#<init>(int,byte[])"
                 (compat-call "NewBigInteger" arguments)

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

                 "executable:java.util.EnumMap#<init>(java.lang.Class)"
                 (sequence-node
                  [(raw "new ") (type-node @ctx-holder (.getType element))
                   (raw "()")])

                 "executable:java.util.concurrent.ConcurrentHashMap#<init>(int)"
                 (sequence-node
                  [(raw "new ") (type-node @ctx-holder (.getType element))
                   (raw "()")])

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
             static? (and (instance? CtMethod declaration)
                          (.hasModifier ^CtMethod declaration ModifierKind/STATIC))
             parameter-count (count (.getParameters (.getExecutable element)))
             parameters (mapv #(raw (str "value" %)) (range parameter-count))
             functional-type (some-> element .getType .getQualifiedName)
             discards-result?
             (and (instance? CtMethod declaration)
                  (not= "void" (.getQualifiedName (.getType ^CtMethod declaration)))
                  (contains? #{"java.util.function.Consumer"
                               "java.util.function.BiConsumer"}
                             functional-type))]
         (when-not (or (and (= :project (:origin occurrence))
                            (or (instance? CtMethod declaration)
                                (instance? CtConstructor declaration)))
                       (contains?
                        #{"executable:java.lang.Class#cast(java.lang.Object)"
                          "executable:java.lang.Class#isInstance(java.lang.Object)"
                          "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
                          "executable:java.lang.Long#max(long,long)"
                          "executable:java.lang.Math#min(float,float)"
                          "executable:java.lang.Math#max(float,float)"
                          "executable:java.lang.Object#toString()"
                          "executable:java.util.List#add(java.lang.Object)"
                          "executable:java.util.List#remove(java.lang.Object)"
                          "executable:java.util.Map$Entry#getKey()"
                          "executable:java.util.Map$Entry#getValue()"
                          "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
                          "executable:java.util.Deque#add(java.lang.Object)"
                          "executable:java.util.Objects#nonNull(java.lang.Object)"
                          "executable:org.w3c.dom.Node#removeChild(org.w3c.dom.Node)"
                          "executable:java.util.StringJoiner#add(java.lang.CharSequence)"
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

             (= "executable:java.lang.Long#max(long,long)" (:key occurrence))
             (raw "global::System.Math.Max")

             (= "executable:java.lang.Math#min(float,float)" (:key occurrence))
             (raw "global::System.MathF.Min")

             (= "executable:java.lang.Math#max(float,float)" (:key occurrence))
             (raw "global::System.MathF.Max")

             (= "executable:java.util.Map$Entry#getKey()" (:key occurrence))
             (raw "(value0) => value0.Key")

             (= "executable:java.util.Map$Entry#getValue()" (:key occurrence))
             (raw "(value0) => value0.Value")

             (= "executable:java.util.List#add(java.lang.Object)" (:key occurrence))
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

             (= "executable:java.lang.Object#toString()" (:key occurrence))
             (raw "(value0) => value0.ToString()!")

             (= "executable:java.util.ArrayList#<init>()" (:key occurrence))
             (sequence-node [(raw "() => new ") target (raw "()")])

             (and (= :project (:origin occurrence))
                  (instance? CtConstructor declaration))
             (sequence-node
              [(raw "(") (sequence-node parameters ", ") (raw ") => new ")
               target (raw "(") (sequence-node parameters ", ") (raw ")")])

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
             ^CtArrayTypeReference array-type (.getType element)
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
                                 (if (and (= "byte"
                                             (.getQualifiedName
                                              initializer-element-type))
                                          (not= "byte"
                                                (some-> ^CtExpression %
                                                        .getType
                                                        .getQualifiedName)))
                                   (sequence-node
                                    [(raw "unchecked((sbyte)(") node (raw "))")])
                                   node))
                              values)
                             ", ")
                            (raw " }")]))}))}

    {:id :java-library.expression/binary
     :class CtBinaryOperator
     :emit
     (fn [{:keys [^CtBinaryOperator element children]}]
       (let [kind (str (.getKind element))
             unbox-operands?
             (or (not (contains? #{"EQ" "NE" "INSTANCEOF"} kind))
                 (and (contains? #{"EQ" "NE"} kind)
                      (or (.isPrimitive (.getType (.getLeftHandOperand element)))
                          (.isPrimitive (.getType (.getRightHandOperand element))))))
             left-expression (.getLeftHandOperand element)
             right-expression (.getRightHandOperand element)
             left-node (child-node children left-expression)
             right-node (child-node children right-expression)
             left (if unbox-operands?
                    (maybe-unbox-node @ctx-holder left-expression left-node)
                    left-node)
             right (if unbox-operands?
                     (maybe-unbox-node @ctx-holder right-expression right-node)
                     right-node)
             generic-null-comparison?
             (and (contains? #{"EQ" "NE"} kind)
                  (or (and (instance? CtTypeParameterReference
                                      (.getType left-expression))
                           (instance? CtLiteral right-expression)
                           (nil? (.getValue ^CtLiteral right-expression)))
                      (and (instance? CtTypeParameterReference
                                      (.getType right-expression))
                           (instance? CtLiteral left-expression)
                           (nil? (.getValue ^CtLiteral left-expression)))))
             node
             (cond
               generic-null-comparison?
               (let [value (if (and (instance? CtLiteral left-expression)
                                    (nil? (.getValue ^CtLiteral left-expression)))
                             right
                             left)]
                 (sequence-node
                  [(raw "(") value
                   (raw (if (= "EQ" kind) " is null)" " is not null)"))]))

               (and (= "PLUS" kind)
                    (string-expression? element)
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
                 (raw ")")]))]
         {:node (expression-cast-node @ctx-holder element node)}))}

    {:id :java-library.expression/conditional
     :class CtConditional
     :emit
     (fn [{:keys [^CtConditional element children]}]
       (let [primitive-result? (.isPrimitive (.getType element))
             branch-node
             (fn [^CtExpression expression]
               (let [node (child-node children expression)]
                 (if primitive-result?
                   (maybe-unbox-node @ctx-holder expression node)
                   node)))]
         {:node
          (expression-cast-node
           @ctx-holder element
           (sequence-node [(raw "(")
                           (child-node children (.getCondition element))
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
               operand-node)]
         {:node
          (sequence-node
           [(raw prefix) operand-node (raw suffix)
            (when (statement-expression? element) (raw ";"))])}))}

    {:id :java-library.expression/type-access
     :class CtTypeAccess
     :emit
     (fn [{:keys [^CtTypeAccess element children]}]
       {:node (child-node children (.getAccessedType element))})}

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

                 ("field:java.lang.Character#MIN_CODE_POINT"
                  "field:java.lang.Character#UNASSIGNED")
                 (raw "0")

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

                 "field:java.lang.Integer#MIN_VALUE"
                 (raw "int.MinValue")

                 "field:java.lang.Long#MIN_VALUE"
                 (raw "long.MinValue")

                 "field:java.lang.Long#MAX_VALUE"
                 (raw "long.MaxValue")

                 "field:java.lang.Short#MAX_VALUE"
                 (raw "short.MaxValue")

                 "field:java.lang.Short#MIN_VALUE"
                 (raw "short.MinValue")

                 "field:java.lang.Short#SIZE"
                 (raw "16")

                 "field:java.lang.Math#PI"
                 (raw "global::System.Math.PI")

                 "field:java.util.Locale#ENGLISH"
                 (raw "global::System.Globalization.CultureInfo.GetCultureInfo(\"en\")")

                 "field:java.math.RoundingMode#CEILING"
                 (raw "global::DripSharp.Runtime.JavaRoundingMode.Ceiling")

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

                 (if (and (instance? CtTypeAccess target)
                          (= "class" (.getSimpleName (.getVariable element))))
                   (sequence-node [(raw "typeof(") target-node (raw ")")])
                   (sequence-node
                    [(when target
                       (sequence-node [target-node (raw ".")]))
                     (if (= "field:<array>#length" (:key occurrence))
                       (raw "Length")
                       (child-node children (.getVariable element)))]))))]
         {:node
          (expression-cast-node @ctx-holder element node)}))}

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
       (let [statements (vec (remove #(or (.isImplicit ^CtElement %)
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
          (sequence-node
           [(raw "{")
            (when (or (seq statements) continue-target) (raw "\n"))
            (sequence-node (mapv #(child-node children %) statements) "\n")
            (when continue-target
              (raw (str (when (seq statements) "\n")
                        (java/labeled-target-name context continue-target :continue)
                        ":;")))
            (when (or (seq statements) continue-target) (raw "\n"))
            (raw "}")])}))}

    {:id :java-library.statement/if
     :class CtIf
     :emit
     (fn [{:keys [^CtIf element children]}]
       {:node
        (sequence-node
         [(raw "if (")
          (child-node children (.getCondition element))
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
             mutable?
             (boolean
              (some
               #(identical? variable
                            (some-> ^CtVariableWrite %
                                    .getVariable
                                    .getDeclaration))
               (.getElements (.getBody element) (TypeFilter. CtVariableWrite))))
             variable-name (local-declaration-name variable)
             iteration-name (str "__foreachValue_" variable-name)]
         {:node
          (sequence-node
           [(raw "foreach (")
            (type-node @ctx-holder (.getType variable))
            (raw (str " " (if mutable? iteration-name variable-name) " in "))
            (child-node children (.getExpression element))
            (raw ") ")
            (if mutable?
              (sequence-node
               [(raw "{\n")
                (type-node @ctx-holder (.getType variable))
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
              (child-node children condition))
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
          (child-node children (.getLoopingExpression element))
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
          (child-node children (.getLoopingExpression element))
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
                               (.isImplicit ^CtElement %))
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
                                   (last (.getCases ^CtSwitch (.getParent element)))))
              (raw "\nbreak;"))])}))}

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
             resource (first resources)
             catches (vec (.getCatchers element))
             finalizer (.getFinalizer element)]
         (when (and (seq resources)
                    (not-every? #(and (instance? CtLocalVariable %)
                                      (.getDefaultExpression ^CtLocalVariable %))
                                resources))
           (unsupported! "Java try-with-resources requires declared resources"
                         element))
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
     (fn [{:keys [^CtCatch element children]}]
       (let [parameter (.getParameter element)
             types (vec (.getMultiTypes parameter))
             destinations
             (mapv (fn [^CtTypeReference type]
                     (first (mapped-type-base
                             @ctx-holder type
                             (occurrence! @ctx-holder type :type))))
                   types)
             widened? (< 1 (count (distinct destinations)))
             filtered? (and widened?
                            (not (contains? (set destinations)
                                            "global::System.Exception")))
             used?
             (some #(identical? parameter
                                (.getDeclaration ^CtCatchVariableReference %))
                   (.getElements (.getBody element)
                                 (TypeFilter. CtCatchVariableReference)))]
         {:node
          (sequence-node
           [(raw "catch (")
            (cond
              (or used? filtered?)
              (child-node children parameter)

              widened?
              (raw "global::System.Exception")

              :else
              (type-node @ctx-holder (first types)))
            (raw ")")
            (when filtered?
              (sequence-node
               [(raw (str " when (" (identifier (.getSimpleName parameter))
                          " is "))
                (sequence-node (mapv #(type-node @ctx-holder %) types) " or ")
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
                 (if (nullable-declaration? @ctx-holder declaration)
                   (sequence-node [node (raw "!")])
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
       (let [types (vec (.getMultiTypes element))
             destinations
             (mapv (fn [^CtTypeReference type]
                     (first (mapped-type-base
                             @ctx-holder type
                             (occurrence! @ctx-holder type :type))))
                   types)]
         {:node
          (sequence-node [(if (= 1 (count (distinct destinations)))
                            (type-node @ctx-holder (first types))
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

(defn- body-context [ctx-holder resolved-model]
  (java/context resolved-model
    {:mode :accepted
     :rules (body-rules ctx-holder)
     :mappings (semantic-mappings resolved-model ctx-holder)}))

(defn- translated-node [ctx ^CtElement element]
  (let [translation (-> (:body-context ctx)
                        (java/translate-element element)
                        java/coverage-gate!)]
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
  (member-visibility owner constructor (emitted-type-visibility owner)))

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
            (.getOperand ^CtUnaryOperator expression)))))

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

(defn- field-node [ctx ^CtType owner ^CtField field]
  (let [enum-value? (instance? CtEnumValue field)
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
          (raw
           (str "[global::DripSharp.Runtime.JavaEnumNameAttribute(\""
                (.getSimpleName field)
                "\")]\n")))
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
              (constraints-node ctx formals))
            (if body-node
              (sequence-node [(raw " ") body-node])
              (raw ";"))])
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
                 (.getStatements body))]
            (sequence-node
             [(raw "{\n")
              (sequence-node initializers "\n")
              (when (and (seq initializers) (seq statements)) (raw "\n"))
              (sequence-node (mapv #(translated-node ctx %) statements) "\n")
              (raw "\n}")]))
          (translated-node ctx body))]
    (when-not body
      (unsupported! "Java library constructor has no body" constructor))
    (let [constructor-declaration
          (csharp/with-source
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
                  (raw ")")]))
              (raw " ")
              body-node])
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
        (sequence-node
         [(raw (str "static " name "() {\n"))
          (sequence-node
           (mapv
            (fn [^CtAnonymousExecutable current]
              (csharp/with-source
                (translated-node ctx (.getBody current))
                (source-ref current rule nil)))
            initializers)
           "\n")
          (raw "\n}")])
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
                       (body-context ctx-holder (:resolved-model ctx)))]
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
    (str (or (when-let [owner-reference (.getDeclaringType reference)]
               (translated-external-type-base ctx owner-reference))
             (project-type-base ctx owner))
         "." (functional-adapter-name interface))
    (str "global::" (functional-reference-namespace ctx reference) "."
         (functional-adapter-name interface))))

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
        arguments (vec (.getActualTypeArguments ^CtTypeReference reference))]
    (cond
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

      (functional-interface-method declaration)
      (let [arguments (vec (.getActualTypeArguments ^CtTypeReference reference))]
        (sequence-node
         [(raw (str "new " (functional-adapter-base ctx reference declaration)))
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
        delegate-type (functional-delegate-type-node ctx method)
        parameters (vec (.getParameters method))
        parameter-nodes (mapv #(parameter-node ctx %) parameters)
        arguments (mapv #(raw (identifier (.getSimpleName ^CtParameter %))) parameters)
        void? (= "void" (.getQualifiedName (.getType method)))]
    (sequence-node
     [(raw (str "public sealed class " adapter-name))
      (type-formals-node interface)
      (raw " : ") (owner-type-node ctx interface) (raw " {\nprivate readonly ")
      delegate-type (raw " implementation;\n\npublic ") (raw adapter-name)
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
        source (source-ref type rule
                           {:declaration-id id :declaration-kind :type})]
    (sequence-node
     [(csharp/with-source
        (sequence-node
         [(project-annotation-attributes-node ctx type)
          (raw (str (str/join " " (type-words type)) " " name))
          (type-formals-node type)
          (when (seq base-nodes)
            (sequence-node [(raw " : ")
                            (sequence-node base-nodes ", ")]))
          (constraints-node ctx (vec (.getFormalCtTypeParameters type)))
          (if (seq member-nodes)
            (sequence-node [(raw " {\n")
                            (sequence-node member-nodes "\n\n")
                            (raw "\n}")])
            (raw " {}"))])
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
     :emit-root-node emit-root
     :translate-member
     member-node
     :merge-context! merge-context!
     :context-results context-results}
    :resolved-mappings
    {:type-node type-node
     :create-body-context (fn [_ _] nil)
     :annotation-decisions annotation-decisions}
    :namespace-policy
    {:destination-namespace destination-namespace
     :destination-file-name
     (fn [_ ^CtType type] (str (identifier (.getSimpleName type)) ".cs"))}
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
                 (public-derived-type? current))
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
                         type-adaptation
                         (when promoted-base?
                           {:kind :java-public-base-type-promotion
                            :identity "java-public-base-type-promotion"})
                         type-shape
                         (assoc (live-shape type)
                                :visibility
                                (if promoted-base?
                                  "public"
                                  (declared-visibility type)))
                         declarations
                         (filter
                          #(and (accessible? %)
                                (or (instance? CtConstructor %)
                                    (instance? CtMethod %)
                                    (instance? CtField %)))
                          (.getTypeMembers type))]
                     (concat
                      [{:declaration type
                        :systematic-adaptation
                        (some-> type-adaptation :kind)
                        :shape type-shape
                        :row (surface-row type type-shape type-adaptation)}]
                      (map
                       (fn [^CtModifiable declaration]
                         (let [shape
                               (assoc (live-shape declaration)
                                      :visibility
                                      (declared-visibility declaration))]
                           {:declaration declaration
                            :shape shape
                            :row (surface-row declaration shape)}))
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
   :java-public-base-type-promotion
   "A package-visible Java base class is public in CLR metadata when required by a public subclass."
   :protected-override-family-widening
   "A protected Java override-family member is widened to public when a public override requires one CLR visibility."
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
    (cond-> {:schema-version 1 :strategy :complete-accessible-java-library :surface-derivation (:derivation surface) :compatibility-namespace (or (get-in emission [:configuration :compatibility-namespace]) "DripSharp.Runtime") :required-rows (count rows) :rows rows :systematic-adaptations (into (sorted-map) (keep (fn [adaptation] (when adaptation [adaptation (get systematic-adaptations adaptation)]))) (map :systematic-adaptation rows))} (:compiled-contract-file surface) (assoc :compiled-contract-file (str (:compiled-contract-file surface))))))

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
