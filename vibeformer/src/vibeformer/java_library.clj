(ns vibeformer.java-library
  "Fail-closed destination foundation for ordinary Java libraries.

  This bundle intentionally contains no product identities. It accepts the
  product-neutral structural Java declarations and resolved type identities,
  and rejects every unimplemented shape with its live Spoon identity.
  Subsequent reusable translation work extends these rules; unsupported
  declarations never become generated stubs."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.dotnet-surface :as dotnet-surface]
            [vibeformer.java-project :as project-emission]
            [vibeformer.java-types :as java-types]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Base64 IdentityHashMap]
           [spoon.reflect.code CtArrayRead CtArrayWrite CtAssert CtAssignment
            CtBinaryOperator CtBlock CtBreak CtCase CtCatch CtCatchVariable CtComment
            CtConditional CtConstructorCall CtContinue CtDo CtExecutableReferenceExpression
            CtExpression CtFieldRead CtFieldWrite
            CtFor CtForEach CtIf CtInvocation CtLambda CtLiteral CtLocalVariable
            CtNewArray CtOperatorAssignment CtReturn CtStatement CtSuperAccess CtThisAccess CtThrow CtTry
            CtSwitch CtSynchronized CtTryWithResource CtTypeAccess CtUnaryOperator CtVariableAccess CtWhile
            CtVariableRead CtVariableWrite]
           [spoon.reflect.declaration CtAnnotation CtAnonymousExecutable CtClass CtConstructor CtElement
            CtEnum CtEnumValue CtExecutable CtField CtInterface CtMethod
            CtModifiable CtParameter CtType ModifierKind]
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
                     (get-in ctx [:configuration prefixes-key])))))

(defn- destination-namespace [ctx ^CtType type]
  (let [package (package-name type)]
    (or (mapped-namespace ctx package :namespaces :namespace-prefixes)
        (unsupported! "Java library has no destination namespace mapping" type))))

(declare type-node body-context functional-expression-node)

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
    (when namespace
      (str "global::" namespace "."
           (str/join "." (map #(pascal (.getSimpleName ^CtTypeReference %))
                              (reference-declaring-types reference)))))))

(defn- destination-type-parameter-name [^CtElement parameter]
  (let [base (identifier (.getSimpleName parameter))
        parent (when (.isParentInitialized parameter) (.getParent parameter))
        owner (when (instance? CtExecutable parent)
                (.getDeclaringType ^CtExecutable parent))
        outer-names (when owner
                      (set (map #(.getSimpleName ^CtElement %)
                                (.getFormalCtTypeParameters ^CtType owner))))]
    (if (contains? outer-names (.getSimpleName parameter))
      (str "Method" (pascal base))
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
          (java-types/mapping qualified)
          (unsupported! "Java library resolved type has no neutral mapping"
                        reference)))))

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
                (if (and (empty? actual-arguments)
                         (instance? CtType declaration)
                         (seq (.getFormalCtTypeParameters ^CtType declaration)))
                  (vec (repeat (count (.getFormalCtTypeParameters ^CtType declaration))
                               (raw "object")))
                  (mapv #(type-node ctx %) actual-arguments))]
            (if (seq arguments)
              (csharp/generic-name (raw target) arguments)
              (raw target))))
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

(defn- declaration-type-node [ctx ^CtElement element ^CtTypeReference reference]
  (let [base (type-node ctx reference)]
    (if (and (not (.isPrimitive reference))
             (resolved-annotation?
              ctx element "annotation:javax.annotation.Nullable"))
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

(defn- implicit-member-constructor? [declaration]
  (and (instance? CtConstructor declaration)
       (.isImplicit ^CtConstructor declaration)
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
    (str "Anonymous_" (or line 0) "_" (or column 0))))

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

(defn- anonymous-project-subclass? [^CtConstructorCall call]
  (let [declaration (some-> call .getType .getTypeDeclaration)]
    (and (instance? CtClass declaration)
         (not (instance? CtEnum declaration)))))

(defn- capture-name [ctx ^CtElement declaration]
  (or (when-let [^IdentityHashMap names (:capture-names ctx)]
        (.get names declaration))
      (some (fn [{captured :declaration name :name}]
              (when (or (identical? declaration captured)
                        (= (spoon/declaration-key declaration)
                           (spoon/declaration-key captured)))
                name))
            (:capture-bindings ctx))))

(defn- local-reference-name [ctx reference]
  (if-let [name (or (some-> reference .getDeclaration (capture-name ctx))
                    (let [matches
                          (filter #(= (.getSimpleName ^CtElement (:declaration %))
                                      (.getSimpleName ^CtElement reference))
                                  (:capture-bindings ctx))]
                      (when (= 1 (count matches)) (:name (first matches)))))]
    (str "this." name)
    (identifier (.getSimpleName ^CtElement reference))))

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
  (let [value (.getValue literal)]
    (raw
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
       :else (str value)))))

(declare method-name)

(defn- destination-field-name [ctx ^CtField field]
  (let [base (ordinary-member-name ctx field)
        owner (.getDeclaringType field)
        method-collision?
        (and owner
             (seq (.getMethodsByName ^CtType owner (.getSimpleName field))))]
    (if-not method-collision?
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

(defn- resolved-name [ctx occurrence reference]
  (cond
    (= :project (:origin occurrence))
    (let [declaration (:declaration occurrence)]
      (cond
        (instance? CtField declaration)
        (destination-field-name ctx declaration)

        (instance? CtMethod declaration)
        (method-name ctx (.getDeclaringType ^CtMethod declaration) declaration)

        :else
        (identifier (.getSimpleName ^CtElement reference))))

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
       "executable:java.lang.String#getBytes()"
       "executable:java.lang.String#join(java.lang.CharSequence,java.lang.Iterable)"
       "executable:java.lang.Integer#toString(int)"
       "executable:java.lang.Integer#toString(int,int)"
       "executable:java.lang.Integer#parseInt(java.lang.String)"
       "executable:java.lang.Long#parseLong(java.lang.String)"
       "executable:java.lang.Long#toString(long)"
       "executable:java.lang.Math#min(long,long)"
       "executable:java.lang.Math#min(int,int)"
       "executable:java.lang.Math#toIntExact(long)"
       "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)"
       "executable:java.lang.System#currentTimeMillis()"
       "executable:java.lang.ThreadLocal#withInitial(java.util.function.Supplier)"
       "executable:java.lang.ThreadLocal#get()"
       "executable:java.lang.ThreadLocal#set(java.lang.Object)"
       "executable:java.util.Arrays#equals(byte[],byte[])"
       "executable:java.util.Arrays#hashCode(byte[])"
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
       "executable:java.time.format.DateTimeFormatter#format(java.time.temporal.TemporalAccessor)"
       "executable:java.net.InetAddress#getLoopbackAddress()"
       "executable:java.lang.Thread#sleep(long)"
       "executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)"
       "executable:java.util.Objects#hash(java.lang.Object[])"
       "executable:java.util.Objects#requireNonNull(java.lang.Object)"
       "executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)"
       "executable:java.util.Map#entrySet()"
       "executable:java.util.Map#containsKey(java.lang.Object)"
       "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)"
       "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
       "executable:java.util.Map#forEach(java.util.function.BiConsumer)"
       "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
       "executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)"
       "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#keySet()"
       "executable:java.util.LinkedHashMap#keySet()"
       "executable:java.util.Map#values()"
       "executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#putAll(java.util.Map)"
       "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.HashMap#putAll(java.util.Map)"
       "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#size()"
       "executable:java.util.HashMap#size()"
       "executable:java.util.Map#get(java.lang.Object)"
       "executable:java.util.Map#remove(java.lang.Object)"
       "executable:java.util.HashMap#remove(java.lang.Object)"
       "executable:java.util.LinkedHashMap#remove(java.lang.Object)"
       "executable:java.util.Map#hashCode()"
       "executable:java.util.Map$Entry#getKey()"
       "executable:java.util.Map$Entry#getValue()"
       "executable:java.util.Map$Entry#setValue(java.lang.Object)"
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
       "executable:java.util.Set#contains(java.lang.Object)"
       "executable:java.util.Set#equals(java.lang.Object)"
       "executable:java.util.Set#add(java.lang.Object)"
       "executable:java.util.Set#removeAll(java.util.Collection)"
       "executable:java.util.regex.Pattern#compile(java.lang.String)"
       "executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)"
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
       "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)"}
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

    (= "field:java.time.ZoneOffset#UTC" (:key occurrence))
    "Zero"

    (= "field:java.time.format.DateTimeFormatter#RFC_1123_DATE_TIME" (:key occurrence))
    "Rfc1123"

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

                 (and (= :intrinsic (:origin occurrence))
                      (= :enum-synthetic-constructor (:resolution occurrence)))
                 {:node (raw "<init>")}

                 (contains?
                  #{"executable:java.lang.Object#<init>()"
                    "executable:java.lang.Enum#<init>(java.lang.String,int)"
                    "executable:java.io.InputStream#<init>()"
                    "executable:java.io.OutputStream#<init>()"
                    "executable:java.io.IOException#<init>()"
                    "executable:java.io.IOException#<init>(java.lang.String)"
                    "executable:java.io.IOException#<init>(java.lang.Throwable)"
                    "executable:java.util.NoSuchElementException#<init>()"
                    "executable:java.lang.RuntimeException#<init>()"
                    "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"
                    "executable:java.lang.RuntimeException#<init>(java.lang.String)"
                    "executable:java.lang.RuntimeException#<init>(java.lang.String,java.lang.Throwable)"
                    "executable:java.util.concurrent.TimeoutException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalStateException#<init>(java.lang.String)"
                    "executable:java.lang.IllegalArgumentException#<init>(java.lang.String)"
                    "executable:java.lang.StringBuilder#<init>()"
                    "executable:java.lang.StringBuilder#<init>(int)"
                    "executable:java.lang.StringBuilder#<init>(java.lang.String)"
                    "executable:java.lang.String#<init>(char[])"
                    "executable:java.lang.String#<init>(char[],int,int)"
                    "executable:java.lang.String#<init>(byte[],java.nio.charset.Charset)"
                    "executable:java.io.ByteArrayOutputStream#<init>()"
                    "executable:java.io.ByteArrayOutputStream#<init>(int)"
                    "executable:java.io.ByteArrayInputStream#<init>(byte[])"
                    "executable:java.io.BufferedInputStream#<init>(java.io.InputStream)"
                    "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.io.PipedInputStream#<init>()"
                    "executable:java.io.PipedOutputStream#<init>()"
                    "executable:java.io.PushbackInputStream#<init>(java.io.InputStream)"
                    "executable:java.util.zip.GZIPInputStream#<init>(java.io.InputStream)"
                    "executable:java.util.zip.InflaterOutputStream#<init>(java.io.OutputStream)"
                    "executable:java.net.URI#<init>(java.lang.String)"
                    "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)"
                    "executable:java.util.HashMap#<init>()"
                    "executable:java.util.HashMap#<init>(int)"
                    "executable:java.util.HashSet#<init>(int)"
                    "executable:java.util.LinkedHashSet#<init>(int)"
                    "executable:java.util.ArrayList#<init>()"
                    "executable:java.util.ArrayList#<init>(int)"
                    "executable:java.util.ArrayList#<init>(java.util.Collection)"
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
            (sequence-node [(raw "(") (type-node ctx cast)
                            (raw ")(") inner
                            (raw (if (.isPrimitive cast) ")" "!)"))]))
          node
          (.getTypeCasts expression)))

(defn- assignment-value-node [ctx ^CtExpression assigned ^CtExpression assignment node]
  (if (and (= "byte" (some-> assigned .getType .getQualifiedName))
           (= "char" (some-> assignment .getType .getQualifiedName))
           (instance? CtLiteral assignment))
    (sequence-node [(raw "(") (type-node ctx (.getType assigned))
                    (raw ")") node])
    node))

(defn- collection-element-type [^CtInvocation invocation]
  (or (first (.getActualTypeArguments (.getType invocation)))
      (unsupported! "Generic Java collection invocation has no resolved element type"
                    invocation)))

(defn- compat-call [name arguments]
  (sequence-node
   [(raw (str "global::Vibeformer.Runtime.JavaCompat." name "("))
    (sequence-node arguments ", ")
    (raw ")")]))

(defn- invocation-statement [^CtInvocation invocation node]
  (if (statement-expression? invocation)
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
               (enum-switch-declaration ctx parent))]
    (if (seq expressions)
      (sequence-node
       (mapv #(sequence-node [(raw "case ")
                              (if enum
                                (enum-case-node ctx enum %)
                                (child-node children %))
                              (raw ":")])
             expressions)
       "\n")
      (raw "default:"))))

(defn- body-rules [ctx-holder]
  (java/structural-rules
   [{:id :java-library.expression/invocation
     :class CtInvocation
     :emit
     (fn [{:keys [context ^CtInvocation element children]}]
       (let [target (.getTarget element)
             target-node
             (when target
               (let [node (child-node children target)]
                 (if (and (instance? CtExpression target)
                          (seq (.getTypeCasts ^CtExpression target)))
                   (sequence-node [(raw "(") node (raw ")")])
                   node)))
             arguments (mapv #(child-node children %) (.getArguments element))
             occurrence (invocation-occurrence context element)
             raw-node
             (case (:key occurrence)
               "executable:java.util.Collections#emptyList()"
               (sequence-node
                [(raw "global::System.Array.Empty<")
                 (type-node @ctx-holder (collection-element-type element))
                 (raw ">()")])

               "executable:java.util.Collections#emptyMap()"
               (let [type-arguments (.getActualTypeArguments (.getType element))]
                 (sequence-node
                  [(csharp/generic-name
                    (raw "global::Vibeformer.Runtime.JavaCompat.EmptyMap")
                    (mapv #(type-node @ctx-holder %) type-arguments))
                   (raw "()")]))

               "executable:java.util.Collections#singletonList(java.lang.Object)"
               (sequence-node
                [(csharp/generic-name
                  (raw "global::Vibeformer.Runtime.JavaCompat.ListOf")
                  [(type-node @ctx-holder (collection-element-type element))])
                 (raw "(") (sequence-node arguments ", ") (raw ")")])

               "executable:java.util.Collections#synchronizedMap(java.util.Map)"
               (first arguments)

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

               "executable:java.lang.String#getBytes(java.nio.charset.Charset)"
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

               "executable:java.lang.Long#parseLong(java.lang.String)"
               (compat-call "ParseLong" arguments)

               "executable:java.lang.Long#toString(long)"
               (compat-call "StringValueOf" arguments)

               ("executable:java.lang.Math#min(long,long)"
                "executable:java.lang.Math#min(int,int)")
               (sequence-node [(raw "global::System.Math.Min(")
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
                  (raw "global::Vibeformer.Runtime.JavaThreadLocal")
                  [(type-node @ctx-holder (collection-element-type element))])
                 (raw ".WithInitial(") (sequence-node arguments ", ") (raw ")")])

               "executable:java.lang.ThreadLocal#get()"
               (sequence-node [target-node (raw ".Get()")])

               "executable:java.lang.ThreadLocal#set(java.lang.Object)"
               (sequence-node [target-node (raw ".Set(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:java.util.Arrays#equals(byte[],byte[])"
               (compat-call "ArrayEquals" arguments)

               "executable:java.util.Arrays#hashCode(byte[])"
               (compat-call "ArrayHash" arguments)

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
               (raw "global::Vibeformer.Runtime.JavaKeyStore.GetDefaultType()")

               "executable:java.security.KeyStore#getInstance(java.lang.String)"
               (sequence-node [(raw "global::Vibeformer.Runtime.JavaKeyStore.GetInstance(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:java.security.KeyStore#load(java.io.InputStream,char[])"
               (sequence-node [target-node (raw ".Load(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:javax.net.ssl.KeyManagerFactory#getDefaultAlgorithm()"
               (raw "global::Vibeformer.Runtime.JavaKeyManagerFactory.GetDefaultAlgorithm()")

               "executable:javax.net.ssl.KeyManagerFactory#getInstance(java.lang.String)"
               (sequence-node
                [(raw "global::Vibeformer.Runtime.JavaKeyManagerFactory.GetInstance(")
                 (sequence-node arguments ", ") (raw ")")])

               "executable:javax.net.ssl.KeyManagerFactory#init(java.security.KeyStore,char[])"
               (sequence-node [target-node (raw ".Init(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:javax.net.ssl.KeyManagerFactory#getKeyManagers()"
               (sequence-node [target-node (raw ".GetKeyManagers()")])

               "executable:javax.net.ssl.TrustManagerFactory#getDefaultAlgorithm()"
               (raw "global::Vibeformer.Runtime.JavaTrustManagerFactory.GetDefaultAlgorithm()")

               "executable:javax.net.ssl.TrustManagerFactory#getInstance(java.lang.String)"
               (sequence-node
                [(raw "global::Vibeformer.Runtime.JavaTrustManagerFactory.GetInstance(")
                 (sequence-node arguments ", ") (raw ")")])

               "executable:javax.net.ssl.TrustManagerFactory#init(java.security.KeyStore)"
               (sequence-node [target-node (raw ".Init(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:javax.net.ssl.TrustManagerFactory#getTrustManagers()"
               (sequence-node [target-node (raw ".GetTrustManagers()")])

               "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)"
               (sequence-node [(raw "global::Vibeformer.Runtime.JavaSslContext.GetInstance(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:javax.net.ssl.SSLContext#init(javax.net.ssl.KeyManager[],javax.net.ssl.TrustManager[],java.security.SecureRandom)"
               (sequence-node [target-node (raw ".Init(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:javax.net.ssl.SSLSocketFactory#getDefault()"
               (raw "global::Vibeformer.Runtime.JavaSocketFactory.Default")

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

               "executable:java.io.OutputStream#close()"
               (sequence-node [target-node (raw ".Dispose()")])

               "executable:java.io.Closeable#close()"
               (sequence-node [target-node (raw ".Dispose()")])

               "executable:java.io.InputStream#close()"
               (sequence-node [target-node (raw ".Dispose()")])

               "executable:java.io.File#toPath()"
               (sequence-node [target-node (raw ".FullName")])

               "executable:java.io.File#length()"
               (sequence-node [target-node (raw ".Length")])

               "executable:java.nio.file.Files#newInputStream(java.nio.file.Path,java.nio.file.OpenOption[])"
               (compat-call "OpenInputStream" arguments)

               "executable:java.io.ByteArrayOutputStream#writeTo(java.io.OutputStream)"
               (compat-call "MemoryStreamWriteTo" (into [target-node] arguments))

               "executable:java.io.ByteArrayOutputStream#toByteArray()"
               (compat-call "ToSignedBytes" [target-node])

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

               "executable:java.io.InputStream#read(byte[])"
               (compat-call "InputStreamRead" (into [target-node] arguments))

               "executable:java.io.InputStream#read(byte[],int,int)"
               (compat-call "InputStreamRead" (into [target-node] arguments))

               "executable:java.io.ByteArrayInputStream#read()"
               (compat-call "InputStreamRead" [target-node])

               "executable:java.io.PushbackInputStream#read()"
               (compat-call "InputStreamRead" [target-node])

               "executable:java.io.PushbackInputStream#unread(int)"
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

               "executable:java.lang.StringBuilder#length()"
               (sequence-node [target-node (raw ".Length")])

               "executable:java.lang.AbstractStringBuilder#length()"
               (sequence-node [target-node (raw ".Length")])

               "executable:java.lang.StringBuilder#toString()"
               (sequence-node [target-node (raw ".ToString()")])

               "executable:java.util.Collection#stream()"
               target-node

               "executable:java.lang.Object#getClass()"
               (sequence-node [target-node (raw ".GetType()")])

               "executable:java.lang.Class#getClassLoader()"
               (sequence-node [target-node (raw ".Assembly")])

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

               "executable:java.time.format.DateTimeFormatter#format(java.time.temporal.TemporalAccessor)"
               (sequence-node [target-node (raw ".Format(")
                               (sequence-node arguments ", ") (raw ")")])

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

               "executable:java.util.Map#containsKey(java.lang.Object)"
               (compat-call "MapContainsKey" (into [target-node] arguments))

               "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)"
               (compat-call "ComputeIfAbsent" (into [target-node] arguments))

               "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
               (compat-call "ComputeIfAbsent" (into [target-node] arguments))

               "executable:java.util.Map#forEach(java.util.function.BiConsumer)"
               (compat-call "ForEach" (into [target-node] arguments))

               "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)"
               (compat-call "MapGetOrDefault" (into [target-node] arguments))

               "executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
               (compat-call "MapPutIfAbsent" (into [target-node] arguments))

               "executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)"
               (compat-call "MapPutIfAbsent" (into [target-node] arguments))

               "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)"
               (compat-call "MapGetOrDefault" (into [target-node] arguments))

               "executable:java.util.Map#keySet()"
               (compat-call "MapKeySet" [target-node])

               "executable:java.util.LinkedHashMap#keySet()"
               (compat-call "MapKeySet" [target-node])

               "executable:java.util.Map#values()"
               (sequence-node [target-node (raw ".Values")])

               "executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
               (compat-call "MapPut" (into [target-node] arguments))

               "executable:java.util.Map#putAll(java.util.Map)"
               (compat-call "MapPutAll" (into [target-node] arguments))

               "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"
               (compat-call "MapPut" (into [target-node] arguments))

               "executable:java.util.HashMap#putAll(java.util.Map)"
               (compat-call "MapPutAll" (into [target-node] arguments))

               "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"
               (compat-call "MapPut" (into [target-node] arguments))

               "executable:java.util.Map#size()"
               (compat-call "MapCount" [target-node])

               "executable:java.util.HashMap#size()"
               (sequence-node [target-node (raw ".Count")])

               "executable:java.util.Map#get(java.lang.Object)"
               (compat-call "MapGet" (into [target-node] arguments))

               "executable:java.util.Map#remove(java.lang.Object)"
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

               "executable:java.util.List#isEmpty()"
               (compat-call "ListIsEmpty" [target-node])

               "executable:java.util.ArrayList#isEmpty()"
               (compat-call "ListIsEmpty" [target-node])

               "executable:java.util.List#add(java.lang.Object)"
               (compat-call "Add" (into [target-node] arguments))

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

               "executable:java.util.Iterator#next()"
               (sequence-node [target-node (raw ".Next()")])

               "executable:java.util.Iterator#hasNext()"
               (sequence-node [target-node (raw ".HasNext()")])

               "executable:java.util.Iterator#remove()"
               (sequence-node [target-node (raw ".Remove()")])

               "executable:java.util.Collection#remove(java.lang.Object)"
               (compat-call "CollectionRemove" (into [target-node] arguments))

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

               "executable:java.util.Set#contains(java.lang.Object)"
               (compat-call "CollectionContains" (into [target-node] arguments))

               "executable:java.util.Set#equals(java.lang.Object)"
               (compat-call "Equals" (into [target-node] arguments))

               "executable:java.util.Set#add(java.lang.Object)"
               (sequence-node [target-node (raw ".Add(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:java.util.Set#removeAll(java.util.Collection)"
               (compat-call "RemoveAll" (into [target-node] arguments))

               "executable:java.util.regex.Pattern#compile(java.lang.String)"
               (compat-call "CompileRegex" arguments)

               "executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)"
               (compat-call "RegexMatcher" (into [target-node] arguments))

               "executable:java.util.regex.Matcher#matches()"
               (sequence-node [target-node (raw ".Matches()")])

               "executable:java.util.stream.Stream#of(java.lang.Object[])"
               (compat-call "StreamOf" arguments)

               "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)"
               (sequence-node
                [(csharp/generic-name
                  (raw "global::Vibeformer.Runtime.JavaCompat.LoadServices")
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
               (raw "global::Vibeformer.Runtime.JavaCompat.ToList<object>()")

               "executable:java.util.stream.Collectors#toSet()"
               (sequence-node
                [(csharp/generic-name
                  (raw "global::Vibeformer.Runtime.JavaCompat.ToSet")
                  [(type-node @ctx-holder (collection-element-type element))])
                 (raw "()")])

               "executable:java.util.stream.Collectors#toCollection(java.util.function.Supplier)"
               (compat-call "ToCollection" arguments)

               "executable:java.util.stream.Stream#collect(java.util.stream.Collector)"
               (case (some-> element .getType .getQualifiedName)
                 "java.util.Set"
                 (sequence-node
                  [(csharp/generic-name
                    (raw "global::Vibeformer.Runtime.JavaCompat.SetOfValues")
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
                [(raw "new global::Vibeformer.Runtime.JavaExecutorService(")
                 (sequence-node arguments ", ") (raw ")")])

               "executable:java.util.concurrent.Executors#newSingleThreadExecutor()"
               (raw "new global::Vibeformer.Runtime.JavaExecutorService(1)")

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
               (raw "global::Vibeformer.Runtime.JavaThread.CurrentThread()")

               "executable:java.lang.Thread#interrupt()"
               (sequence-node [target-node (raw ".Interrupt()")])

               "executable:java.lang.Thread#sleep(long)"
               (sequence-node [(raw "global::Vibeformer.Runtime.JavaThread.Sleep(")
                               (sequence-node arguments ", ") (raw ")")])

               ("executable:java.lang.Object#<init>()"
                "executable:java.lang.Enum#<init>(java.lang.String,int)"
                "executable:java.io.InputStream#<init>()"
                "executable:java.io.OutputStream#<init>()"
                "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)")
               (raw "")

               (sequence-node
                [(when target
                   (sequence-node [target-node (raw ".")]))
                 (child-node children (.getExecutable element))
                 (raw "(")
                 (sequence-node arguments ", ")
                 (raw ")")]))
             raw-node
             (if (nullable-declaration? @ctx-holder (:declaration occurrence))
               (sequence-node [raw-node (raw "!")])
               raw-node)
             node (expression-cast-node @ctx-holder element raw-node)]
         {:node
          (if (contains? #{"executable:java.lang.Object#<init>()"
                           "executable:java.lang.Enum#<init>(java.lang.String,int)"
                           "executable:java.io.InputStream#<init>()"
                           "executable:java.io.OutputStream#<init>()"
                           "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"}
                         (:key occurrence))
            node
            (invocation-statement element node))}))}

    {:id :java-library.expression/constructor-call
     :class CtConstructorCall
     :emit
     (fn [{:keys [context ^CtConstructorCall element children]}]
       (let [arguments (mapv #(child-node children %) (.getArguments element))
             occurrence (constructor-occurrence context element)
             arguments (if (implicit-member-constructor? (:declaration occurrence))
                         (conj arguments
                               (if-let [target (.getTarget element)]
                                 (child-node children target)
                                 (raw "this")))
                         arguments)
             anonymous-class (anonymous-class-for-call element)
             owner (when anonymous-class (nearest-enclosing-type element))
             captures (when anonymous-class (anonymous-captures anonymous-class))
             outer? (when anonymous-class
                      (anonymous-uses-outer? anonymous-class owner))]
         {:node
          (cond
            anonymous-class
            (sequence-node
             [(raw (str "new " (anonymous-class-name element) "("))
              (sequence-node
               (vec
                (concat arguments
                        (when outer? [(raw "this")])
                        (map #(raw (identifier (.getSimpleName ^CtElement %)))
                             captures)))
               ", ")
              (raw ")")])

            :else
            (case (:key occurrence)
              "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"
              (sequence-node [(raw "new global::System.Exception(null, ")
                              (first arguments) (raw ")")])

              "executable:java.io.IOException#<init>(java.lang.Throwable)"
              (sequence-node [(raw "new global::System.IO.IOException(null, ")
                              (first arguments) (raw ")")])

              "executable:java.net.Socket#<init>(java.lang.String,int)"
              (sequence-node
               [(raw "global::Vibeformer.Runtime.JavaSocketFactory.Plain.CreateSocket(")
                (sequence-node arguments ", ") (raw ")")])

              "executable:java.net.Socket#<init>(java.net.InetAddress,int)"
              (sequence-node
               [(raw "global::Vibeformer.Runtime.JavaSocketFactory.Plain.CreateSocket(")
                (sequence-node arguments ", ") (raw ")")])

              "executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)"
              (compat-call "NewUri" arguments)

              "executable:java.net.URI#<init>(java.lang.String)"
              (compat-call "NewUri" arguments)

              "executable:java.io.ByteArrayInputStream#<init>(byte[])"
              (sequence-node
               [(raw "global::Vibeformer.Runtime.JavaCompat.NewMemoryStream(")
                (sequence-node arguments ", ") (raw ")")])

              "executable:java.util.zip.GZIPInputStream#<init>(java.io.InputStream)"
              (sequence-node
               [(raw "new global::System.IO.Compression.GZipStream(")
                (sequence-node arguments ", ")
                (raw ", global::System.IO.Compression.CompressionMode.Decompress)")])

              "executable:java.lang.String#<init>(byte[],java.nio.charset.Charset)"
              (compat-call "NewString" arguments)

              (sequence-node
               [(raw "new ") (type-node @ctx-holder (.getType element)) (raw "(")
                (sequence-node arguments ", ")
                (raw ")")])))}))}

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
                        #{"executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
                          "executable:java.lang.Object#toString()"
                          "executable:java.util.List#add(java.lang.Object)"
                          "executable:java.util.ArrayList#<init>()"
                          "executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)"}
                        (:key occurrence)))
           (unsupported! "Java library method reference requires a supported resolved method"
                         element))
         {:node
          (functional-expression-node
           @ctx-holder element
           (cond
             (= "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
                (:key occurrence))
             (sequence-node
              [(raw "(value0) => global::Vibeformer.Runtime.JavaCompat.EqualsIgnoreCase(")
               target (raw ", value0)")])

             (= "executable:java.util.List#add(java.lang.Object)" (:key occurrence))
             (sequence-node
              [(raw "(value0) => { ") target (raw ".Add(value0); }")])

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
             ^CtArrayTypeReference array-type (.getType element)]
         (when-not (and (instance? CtArrayTypeReference array-type)
                        (or (and (= 1 (count dimensions)) (empty? values))
                            (and (empty? dimensions) (seq values))))
           (unsupported! "Java array creation shape requires explicit lowering"
                         element))
         {:node
          (if (seq dimensions)
            (sequence-node [(raw "new ")
                            (type-node @ctx-holder (.getComponentType array-type))
                            (raw "[") (child-node children (first dimensions))
                            (raw "]")])
            (sequence-node [(raw "new ")
                            (type-node @ctx-holder (.getComponentType array-type))
                            (raw "[] { ")
                            (sequence-node (mapv #(child-node children %) values) ", ")
                            (raw " }")]))}))}

    {:id :java-library.expression/binary
     :class CtBinaryOperator
     :emit
     (fn [{:keys [^CtBinaryOperator element children]}]
       (let [left (child-node children (.getLeftHandOperand element))
             right (child-node children (.getRightHandOperand element))
             node
             (if (and (= "PLUS" (str (.getKind element)))
                      (string-expression? element)
                      (contains? (get-in @ctx-holder
                                         [:configuration :destination-capabilities])
                                 :java-compat))
               (compat-call "Concat" [left right])
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
       {:node
        (expression-cast-node
         @ctx-holder element
         (sequence-node [(raw "(")
                         (child-node children (.getCondition element))
                         (raw " ? ")
                         (child-node children (.getThenExpression element))
                         (raw " : ")
                         (child-node children (.getElseExpression element))
                         (raw ")")]))})}

    {:id :java-library.expression/unary
     :class CtUnaryOperator
     :emit
     (fn [{:keys [^CtUnaryOperator element children]}]
       (let [[prefix suffix] (unary-operator element)]
         {:node
          (sequence-node
           [(raw prefix) (child-node children (.getOperand element)) (raw suffix)
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
             (if (and (instance? CtTypeAccess target)
                      (= "class" (.getSimpleName (.getVariable element))))
               (sequence-node [(raw "typeof(") target-node (raw ")")])
               (sequence-node
                [(when target
                   (sequence-node [target-node (raw ".")]))
                 (if (= "field:<array>#length" (:key occurrence))
                   (raw "Length")
                   (child-node children (.getVariable element)))]))]
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
       (let [statement? (statement-expression? element)]
         {:node
          (sequence-node
           [(when-not statement? (raw "("))
            (child-node children (.getAssigned element))
            (raw (str " " (assignment-operator element) "= "))
            (child-node children (.getAssignment element))
            (raw (if statement? ";" ")"))])}))}

    {:id :java-library.expression/assignment
     :class CtAssignment
     :emit
     (fn [{:keys [^CtAssignment element children]}]
       (let [statement? (statement-expression? element)]
         {:node
          (sequence-node
           [(when-not statement? (raw "("))
            (child-node children (.getAssigned element))
            (raw " = ")
            (assignment-value-node
             @ctx-holder (.getAssigned element) (.getAssignment element)
             (child-node children (.getAssignment element)))
            (raw (if statement? ";" ")"))])}))}

    {:id :java-library.expression/array-read
     :class CtArrayRead
     :emit
     (fn [{:keys [^CtArrayRead element children]}]
       {:node
        (sequence-node [(child-node children (.getTarget element))
                        (raw "[")
                        (child-node children (.getIndexExpression element))
                        (raw "]")])})}

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
     (fn [{:keys [^CtBlock element children]}]
       (let [statements (vec (remove #(.isImplicit ^CtElement %)
                                     (.getStatements element)))]
         {:node
          (sequence-node
           [(raw "{")
            (when (seq statements) (raw "\n"))
            (sequence-node (mapv #(child-node children %) statements) "\n")
            (when (seq statements) (raw "\n"))
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
     (fn [{:keys [^CtForEach element children]}]
       (let [variable (.getVariable element)]
         {:node
          (sequence-node
           [(raw "foreach (")
            (type-node @ctx-holder (.getType variable))
            (raw (str " " (identifier (.getSimpleName variable)) " in "))
            (child-node children (.getExpression element))
            (raw ") ")
            (statement-node children (.getBody element))])}))}

    {:id :java-library.statement/for
     :class CtFor
     :emit
     (fn [{:keys [^CtFor element children]}]
       (let [initializers (vec (.getForInit element))
             updates (vec (.getForUpdate element))]
         (when-not (= 1 (count initializers))
           (unsupported! "Java for loop requires one explicit initializer"
                         element))
         (let [^CtLocalVariable initializer (first initializers)]
           (when-not (instance? CtLocalVariable initializer)
             (unsupported! "Java for initializer is not a local declaration"
                           initializer))
           {:node
            (sequence-node
             [(raw "for (")
              (child-node children initializer)
              (raw "; ")
              (child-node children (.getExpression element))
              (raw "; ")
              (sequence-node (mapv #(child-node children %) updates) ", ")
              (raw ") ")
              (statement-node children (.getBody element))])})))}

    {:id :java-library.statement/while
     :class CtWhile
     :emit
     (fn [{:keys [^CtWhile element children]}]
       {:node
        (sequence-node
         [(raw "while (")
          (child-node children (.getLoopingExpression element))
          (raw ") ")
          (statement-node children (.getBody element))])})}

    {:id :java-library.statement/do-while
     :class CtDo
     :emit
     (fn [{:keys [^CtDo element children]}]
       {:node
        (sequence-node
         [(raw "do ")
          (statement-node children (.getBody element))
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
       (let [source-statements (vec (.getStatements element))
             statements (mapv #(child-node children %) source-statements)
             last-semantic (last (remove #(instance? CtComment %) source-statements))]
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
           [(raw "global::Vibeformer.Runtime.JavaCompat.Assert(() => ")
            (child-node children (.getAssertExpression element))
            (when message
              (sequence-node [(raw ", () => ")
                              (child-node children message)]))
            (raw ");")])}))}

    {:id :java-library.statement/return
     :class CtReturn
     :emit
     (fn [{:keys [^CtReturn element children]}]
       (let [returned (.getReturnedExpression element)]
         {:node
          (sequence-node
           [(raw "return")
            (when returned
              (sequence-node [(raw " ") (child-node children returned)]))
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
                    (not (and (= 1 (count resources))
                              (instance? CtLocalVariable resource)
                              (.getDefaultExpression ^CtLocalVariable resource))))
           (unsupported! "Java try-with-resources requires one declared resource"
                         element))
         (let [body (child-node children (.getBody element))
               using-node
               (when resource
                 (sequence-node
                  [(raw "using (")
                   (child-node children resource)
                   (raw ") ") body]))
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
             used?
             (some #(identical? parameter
                                (.getDeclaration ^CtCatchVariableReference %))
                   (.getElements (.getBody element)
                                 (TypeFilter. CtCatchVariableReference)))]
         {:node
          (sequence-node
           [(raw "catch (")
            (if used?
              (child-node children parameter)
              (type-node @ctx-holder (first (.getMultiTypes parameter))))
            (raw ") ")
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
       (let [initializer (.getDefaultExpression element)]
         {:node
          (sequence-node
           [(if (.isInferred element)
              (raw "var")
              (declaration-type-node @ctx-holder element (.getType element)))
            (raw (str " " (identifier (.getSimpleName element))))
            (when initializer
              (sequence-node [(raw " = ")
                              (child-node children initializer)]))
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
         (when-not (= 1 (count (distinct destinations)))
           (unsupported! "Java multi-catch alternatives require one exact destination type"
                         element))
         {:node
          (sequence-node [(type-node @ctx-holder (first types))
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

(defn- register-type! [ctx ^CtType type name rule]
  (let [id (declaration-id type :type)
        entry {:id id :java-key (spoon/declaration-key type) :kind :type
               :owner (some-> type .getDeclaringType spoon/declaration-key)
               :name name :signature (.getQualifiedName type)
               :destination {:assembly (get-in ctx [:configuration :project :assembly-name])
                             :namespace (destination-namespace ctx type)
                             :owner (destination-owner-name ctx type)
                             :kind "type" :name name :parameter-count "0"}
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
   (register-member! ctx owner member name rule nil))
  ([ctx ^CtType owner ^CtElement member name rule parameter-count-override]
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
                              :name (if (= :constructor kind) ".ctor" name)
                              :parameter-count (str parameter-count)}
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
    (visibility member default)))

(defn- type-formals-node [^CtType type]
  (let [parameters (vec (.getFormalCtTypeParameters type))]
    (when (seq parameters)
      (sequence-node [(raw "<")
                      (sequence-node
                       (mapv #(raw (identifier (.getSimpleName ^CtElement %)))
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

(defn- parameter-node [ctx ^CtParameter parameter]
  (let [parent (when (.isParentInitialized parameter) (.getParent parameter))
        object-equals-parameter?
        (and (instance? CtMethod parent)
             (= "equals" (.getSimpleName ^CtMethod parent))
             (= "java.lang.Object" (.getQualifiedName (.getType parameter))))]
    (sequence-node
     [(when (.isVarArgs parameter) (raw "params "))
      (if object-equals-parameter?
        (raw "object?")
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

(defn- base-type-node [ctx ^CtType owner ^CtTypeReference reference]
  (cond
    (and (instance? CtClass owner)
         (contains? functional-interface-types (.getQualifiedName reference)))
    nil

    (contains? (get-in ctx [:configuration :destination-capabilities])
               :java-compat)
    (case (.getQualifiedName reference)
      "java.io.InputStream" (raw "global::Vibeformer.Runtime.JavaInputStream")
      "java.io.OutputStream" (raw "global::Vibeformer.Runtime.JavaOutputStream")
      (type-node ctx reference))

    :else
    (type-node ctx reference)))

(defn- type-words [^CtType type]
  (let [visibility (cond
                     (anonymous-class? type) "private"
                     (local-type? type) "private"
                     (.hasModifier ^CtModifiable type ModifierKind/PUBLIC) "public"
                     :else "internal")]
    (cond
      (instance? CtInterface type) [visibility "interface"]
      (instance? CtEnum type) [visibility "sealed" "class"]
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
  (and (anonymous-class? owner)
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

    (x509-trust-manager-implementation? owner)
    (case (.getSimpleName method)
      "getAcceptedIssuers" "GetAcceptedIssuers"
      "checkServerTrusted" "CheckServerTrusted"
      "checkClientTrusted" "CheckClientTrusted"
      (ordinary-member-name ctx method))

    :else (ordinary-member-name ctx method)))

(defn- destination-object-method? [^CtMethod method]
  (or (and (= "toString" (.getSimpleName method))
           (empty? (.getParameters method)))
      (and (= "hashCode" (.getSimpleName method))
           (empty? (.getParameters method)))
      (and (= "equals" (.getSimpleName method))
           (= 1 (count (.getParameters method))))))

(defn- superclass-method [^CtType owner ^CtMethod method]
  (loop [reference (when (instance? CtClass owner)
                     (.getSuperclass ^CtClass owner))]
    (when reference
      (let [declaration (.getTypeDeclaration ^CtTypeReference reference)]
        (if-not (instance? CtClass declaration)
          false
          (or (some #(when (= (.getSignature method) (.getSignature ^CtMethod %)) %)
                    (.getMethodsByName ^CtClass declaration (.getSimpleName method)))
              (recur (.getSuperclass ^CtClass declaration))))))))

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

(defn- public-override-family? [^CtType owner ^CtMethod method]
  (let [[root-owner root-method] (override-family-root owner method)
        signature (.getSignature ^CtMethod root-method)
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
          (some #(and (= signature (.getSignature ^CtMethod %))
                      (.hasModifier ^CtMethod % ModifierKind/PUBLIC))
                (.getMethodsByName candidate-owner simple-name))))
      all-types))))

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
        abstract? (and (not (instance? CtInterface owner))
                       (.hasModifier method ModifierKind/ABSTRACT))
        super-method (when-not static? (superclass-method owner method))
        generic-return-conflict?
        (and super-method
             (= (.getQualifiedName (.getType method))
                (.getQualifiedName (.getType ^CtMethod super-method)))
             (not= (str (.getType method)) (str (.getType ^CtMethod super-method))))
        widened-override-family? (public-override-family? owner method)
        interface-dispose?
        (and (instance? CtInterface owner)
             (= "close" (.getSimpleName method))
             (empty? (.getParameters method)))
        override? (and (not static?)
                       (or (destination-object-method? method)
                           (and (= "close" (.getSimpleName method))
                                (empty? (.getParameters method))
                                (superclass-implements-closeable? owner))
                           (and (not (and (= "getMessage" (.getSimpleName method))
                                          (empty? (.getParameters method))))
                                super-method
                                (not generic-return-conflict?))))
        virtual? (and (instance? CtClass owner)
                      (not (instance? CtEnum owner))
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

               :else
               (member-visibility owner method
                                  (if (instance? CtInterface owner)
                                    "public"
                                    "internal")))
             (when static? "static")
             (when abstract? "abstract")
             (when override? "override")
             (when (or generic-return-conflict? interface-dispose?) "new")
             (when virtual? "virtual")])))

(declare member-node emit-root emit-anonymous-type)

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

(defn- field-node [ctx ^CtType owner ^CtField field]
  (let [enum-value? (instance? CtEnumValue field)
        initializer (.getDefaultExpression field)
        initializer-node (when (and initializer
                                    (not (:defer-field-initializers? ctx)))
                           (translated-node ctx initializer))
        java-default-null?
        (and (nil? initializer)
             (not enum-value?)
             (not (.isPrimitive (.getType field)))
             (not (resolved-annotation?
                   ctx field "annotation:javax.annotation.Nullable")))
        name (destination-field-name ctx field)
        rule (if enum-value?
               :java-library.declaration/enum-value
               :java-library.declaration/field)
        id (register-member! ctx owner field name rule)]
    (csharp/with-source
      (sequence-node
       [(raw (str (str/join " "
                            (remove nil?
                                    [(if enum-value?
                                       "public"
                                       (member-visibility owner field "internal"))
                                     (when (or enum-value?
                                               (.hasModifier field ModifierKind/STATIC))
                                       "static")
                                     (when (.hasModifier field ModifierKind/VOLATILE)
                                       "volatile")
                                     (when (or enum-value?
                                               (.hasModifier field ModifierKind/FINAL))
                                       "readonly")]))
                  " "))
        (declaration-type-node ctx field (.getType field))
        (raw (str " " name))
        (when initializer-node
          (sequence-node [(raw " = ") initializer-node]))
        (when java-default-null? (raw " = null!"))
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
        id (register-member! ctx owner method name rule)
        method-node
        (csharp/with-source
          (sequence-node
           [(raw (str (str/join " " (method-words owner method)) " "))
            (declaration-type-node ctx method (.getType method))
            (raw (str " " name))
            (executable-formals-node method)
            (raw "(")
            (sequence-node (mapv #(parameter-node ctx %) (.getParameters method)) ", ")
            (raw ")")
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

(defn- constructor-node [ctx ^CtType owner ^CtConstructor constructor]
  (let [local-types (mapv validate-local-type!
                          (executable-local-types constructor))
        anonymous-types (mapv #(emit-anonymous-type ctx owner %)
                              (executable-anonymous-calls constructor))
        name (pascal (.getSimpleName owner))
        rule :java-library.declaration/constructor
        id (register-member! ctx owner constructor name rule)
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
        initializer-kind
        (when constructor-invocation
          (if (and (= :project (:origin constructor-occurrence))
                   (instance? CtConstructor (:declaration constructor-occurrence))
                   (identical? owner
                               (.getDeclaringType
                                ^CtConstructor (:declaration constructor-occurrence))))
            "this"
            "base"))
        deferred-fields (:deferred-field-initializers ctx)
        body-node
        (if (or constructor-invocation (seq deferred-fields))
          (let [initializers
                (mapv
                 (fn [^CtField field]
                   (sequence-node
                    [(raw (str "this." (destination-field-name ctx field) " = "))
                     (translated-node ctx (.getDefaultExpression field))
                     (raw ";")]))
                 deferred-fields)
                statements (remove #(identical? constructor-invocation %)
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
             [(raw (str (member-visibility owner constructor "internal") " " name "("))
              (sequence-node (mapv #(parameter-node ctx %) (.getParameters constructor))
                             ", ")
              (raw ")")
              (when constructor-invocation
                (sequence-node
                 [(raw (str " : " initializer-kind "("))
                  (sequence-node
                   (mapv #(translated-node ctx %)
                         (.getArguments ^CtInvocation constructor-invocation))
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
  (let [name (pascal (.getSimpleName owner))
        rule :java-library.declaration/static-initializer
        id (register-member! ctx owner initializer ".cctor" rule)]
    (csharp/with-source
      (sequence-node [(raw (str "static " name "() "))
                      (translated-node ctx (.getBody initializer))])
      (source-ref initializer rule
                  {:declaration-id id :declaration-kind :initializer}))))

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
  (when (instance? CtInterface type)
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
    (str (project-type-base ctx owner) "." (functional-adapter-name interface))
    (str "global::" (functional-reference-namespace ctx reference) "."
         (functional-adapter-name interface))))

(defn- functional-expression-node [ctx ^CtExpression expression expression-node]
  (let [reference (.getType expression)
        occurrence (when reference (occurrence! ctx reference :type))
        declaration (when (= :project (:origin occurrence))
                      (or (:declaration occurrence)
                          (.getTypeDeclaration ^CtTypeReference reference)))]
    (if (functional-interface-method declaration)
      (let [arguments (vec (.getActualTypeArguments ^CtTypeReference reference))]
        (sequence-node
         [(raw (str "new " (functional-adapter-base ctx reference declaration)))
          (when (seq arguments)
            (sequence-node
             [(raw "<")
              (sequence-node (mapv #(type-node ctx %) arguments) ", ")
              (raw ">")]))
          (raw "(") expression-node (raw ")")]))
      expression-node)))

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
     [(raw (str "internal sealed class " adapter-name))
      (type-formals-node interface)
      (raw " : ") (owner-type-node ctx interface) (raw " {\nprivate readonly ")
      delegate-type (raw " implementation;\n\ninternal ") (raw adapter-name)
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
        rule :java-library.declaration/implicit-member-constructor
        id (register-member! ctx type constructor name rule 1)]
    (csharp/with-source
      (sequence-node
       [(raw "private readonly ") (owner-type-node ctx outer)
        (raw (str " " outer-field-name ";\n\n"))
        (raw (str (member-visibility type constructor "internal") " " name "("))
        (owner-type-node ctx outer) (raw (str " " outer-field-name ") {\nthis."))
        (raw outer-field-name) (raw (str " = " outer-field-name ";\n}"))])
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
                  (anonymous-project-subclass? call))
      (unsupported! "Anonymous class requires exact Iterator, X509TrustManager, or project-class semantics"
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
          overrides (or (:destination-owner-overrides ctx) (IdentityHashMap.))
          _ (.put ^IdentityHashMap overrides anonymous-class
                  (str (destination-owner-name ctx owner) "." name))
          derived (derived-body-context
                   ctx {:capture-names capture-names
                        :capture-bindings capture-bindings
                        :outer-type (when outer? owner)
                        :defer-field-initializers? true
                        :destination-owner-overrides overrides})
          rule :java-library.declaration/anonymous-iterator
          id (register-type! derived anonymous-class name rule)
          members (explicit-members anonymous-class)
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
              (keep (fn [member]
                      (when (and (instance? CtField member)
                                 (.getDefaultExpression ^CtField member))
                        (sequence-node
                         [(raw (str "this."
                                    (identifier (.getSimpleName ^CtField member))
                                    " = "))
                          (translated-node derived
                                           (.getDefaultExpression ^CtField member))
                          (raw ";")])))
                    members)))
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
                strategy
                (case key
                  "annotation:javax.annotation.Nullable"
                  :csharp-nullable-metadata
                  "annotation:javax.annotation.Nonnull"
                  :csharp-nonnullable-metadata
                  "annotation:java.lang.Override"
                  :csharp-language-semantics
                  "annotation:java.lang.FunctionalInterface"
                  :csharp-functional-contract
                  "annotation:java.lang.SuppressWarnings"
                  :source-analysis-only
                  (unsupported! "Java library annotation has no neutral mapping"
                                annotation))]
            {:source (source-ref annotation :java-library.annotation/resolved nil)
             :resolved-key key
             :origin (:origin occurrence)
             :strategy strategy
             :emitted-runtime-attribute false})))))

(defn- emit-root [ctx ^CtType type]
  (when-not (or (instance? CtClass type) (instance? CtInterface type))
    (unsupported! "Java library declaration shape is not implemented" type))
  (let [name (pascal (.getSimpleName type))
        rule (if (instance? CtInterface type)
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
        (when nested-instance-class?
          (->> members
               (filter #(and (instance? CtField %)
                             (not (.hasModifier ^CtField % ModifierKind/STATIC))
                             (some? (.getDefaultExpression ^CtField %))))
               vec))
        explicit-constructors (filter #(instance? CtConstructor %) members)
        _ (when (and (seq deferred-fields)
                     (not= 1 (count explicit-constructors)))
            (unsupported!
             "Java member-class field initialization requires exactly one explicit constructor"
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
        implicit-constructor
        (when inner?
          (some #(when (and (instance? CtConstructor %)
                            (.isImplicit ^CtConstructor %))
                   %)
                (.getTypeMembers type)))
        member-nodes
        (cond-> (vec member-nodes)
          implicit-constructor
          (conj (implicit-member-constructor-node member-ctx type implicit-constructor
                                                  outer-field-name))
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
          (and closeable? (not declares-close?) (instance? CtClass type))
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
          [(raw (str "public static " name "[] values() => "
                     "global::Vibeformer.Runtime.JavaCompat.EnumValues<" name ">();\n"
                     "public static " name " valueOf(string name) => "
                     "global::Vibeformer.Runtime.JavaCompat.EnumValueOf<" name ">(name);"))
           (when-not explicit-enum-to-string?
             (raw (str "public override string ToString() => "
                       "global::Vibeformer.Runtime.JavaCompat.EnumName(this);")))])
        member-nodes (into member-nodes (remove nil? enum-members))
        functional-method (functional-interface-method type)
        source (source-ref type rule
                           {:declaration-id id :declaration-kind :type})]
    (sequence-node
     [(csharp/with-source
        (sequence-node
         [(raw (str (str/join " " (type-words type)) " " name))
          (type-formals-node type)
          (when (seq base-nodes)
            (sequence-node [(raw " : ")
                            (sequence-node base-nodes ", ")]))
          (if (seq member-nodes)
            (sequence-node [(raw " {\n")
                            (sequence-node member-nodes "\n\n")
                            (raw "\n}")])
            (raw " {}"))])
        source)
      (when functional-method
        (project-functional-adapter-node ctx type functional-method))]
     "\n\n")))

(def ^:private bridge-capabilities
  {:java-compat
   {:source "vibeformer/runtime/Vibeformer.JavaCompat.cs"
    :destination "Vibeformer/Runtime/JavaCompat.cs"
    :strategy :reviewable-java-compatibility-source
    :missing-kind :missing-java-compatibility-source
    :missing-message "Java compatibility source is missing"}
   :java-regex-unicode
   {:source "vibeformer/runtime/Vibeformer.JavaRegexUnicodeData.cs"
    :destination "Vibeformer/Runtime/JavaRegexUnicodeData.cs"
    :strategy :generated-java-compatibility-data
    :missing-kind :missing-java-compatibility-source
    :missing-message "Java compatibility source is missing"}})

(defn- bridge-assets [{:keys [configuration]}]
  (mapv (fn [capability]
          (or (get bridge-capabilities capability)
              (fail! "Java library destination selected an unknown capability"
                     {:kind :unknown-java-library-capability
                      :capability capability})))
        (sort (:destination-capabilities configuration))))

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
  "VIBEFORMER_JAVA_LIBRARY_PUBLIC_SURFACE_V1")

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

(defn- parse-surface-row [encoded]
  (let [decoded (String. (.decode (Base64/getDecoder) encoded)
                         StandardCharsets/UTF_8)
        fields (str/split decoded #"\|" -1)
        kind (first fields)
        row
        (case kind
          "type" {:kind kind :owner (nth fields 3) :name ""
                  :parameter-count 0 :visibility (nth fields 2)}
          "field" {:kind kind :owner (nth fields 1) :name (nth fields 4)
                   :parameter-count 0 :visibility (nth fields 2)}
          "method" {:kind kind :owner (nth fields 1) :name (nth fields 5)
                    :parameter-count (parameter-count (nth fields 6))
                    :visibility (nth fields 2)}
          "constructor" {:kind kind :owner (nth fields 1) :name ".ctor"
                         :parameter-count (parameter-count (nth fields 4))
                         :visibility (nth fields 2)}
          (fail! "Java library public-surface row has an unknown kind"
                 {:kind :unknown-java-library-surface-kind :row decoded}))]
    (assoc row :identity decoded)))

(defn- read-surface!
  [workspace {:keys [contract-file compiled-contract-file] :as specification}]
  (when-not (= #{:contract-file :compiled-contract-file}
               (set (keys specification)))
    (fail! "Invalid Java library public-surface specification"
           {:kind :invalid-java-library-surface-specification
            :specification specification}))
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
    (let [[header & lines] (str/split-lines (Files/readString file StandardCharsets/UTF_8))
          rows (mapv (fn [line]
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
      {:contract-file file :compiled-contract-file compiled-file
       :rows rows :seeds []})))

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

(defn- live-surface [resolved-model]
  (->> (java/project-roots resolved-model)
       (mapcat (fn [^CtType root]
                 (tree-seq (fn [^CtType type] (seq (.getNestedTypes type)))
                           (fn [^CtType type] (.getNestedTypes type))
                           root)))
       distinct
       (mapcat (fn [^CtType type]
                 (when (accessible? type)
                   (concat
                    [{:declaration type :shape (live-shape type)}]
                    (map (fn [declaration]
                           {:declaration declaration :shape (live-shape declaration)})
                         (filter #(and (accessible? %)
                                       (or (instance? CtConstructor %)
                                           (instance? CtMethod %)
                                           (instance? CtField %)))
                                 (.getTypeMembers type)))
                    (when (instance? CtEnum type)
                      (concat
                       (map (fn [^CtEnumValue value]
                              {:declaration value :shape (live-shape value)})
                            (.getEnumValues ^CtEnum type))
                       [{:declaration type :synthetic? true
                         :shape {:kind "method"
                                 :owner (canonical-owner (.getQualifiedName type))
                                 :name "values" :parameter-count 0}}
                        {:declaration type :synthetic? true
                         :shape {:kind "method"
                                 :owner (canonical-owner (.getQualifiedName type))
                                 :name "valueOf" :parameter-count 1}}]))))))
       (remove (comp nil? :shape))
       (sort-by (juxt (comp pr-str :shape)
                      #(spoon/declaration-key (:declaration %))))
       vec))

(defn- validate-selected! [_workspace surface resolved-model]
  (let [expected (frequencies (map #(select-keys % [:kind :owner :name :parameter-count])
                                   (:rows surface)))
        live (live-surface resolved-model)
        actual (frequencies (map :shape live))]
    (when-not (= expected actual)
      (fail! "Resolved Java library public surface differs from its explicit contract"
             {:kind :java-library-selected-surface-mismatch
              :missing (vec (take 30 (remove (fn [[shape count]]
                                               (= count (get actual shape))) expected)))
              :unexpected (vec (take 30 (remove (fn [[shape count]]
                                                  (= count (get expected shape))) actual)))}))
    (let [rows-by-shape (group-by #(select-keys % [:kind :owner :name :parameter-count])
                                  (:rows surface))
          live-by-shape (group-by :shape live)
          evidence
          (->> rows-by-shape
               (mapcat
                (fn [[shape rows]]
                  (map (fn [row {:keys [declaration synthetic?]}]
                         {:row row
                          :declaration-key (spoon/declaration-key declaration)
                          :owner-declaration-key
                          (when-not (instance? CtType declaration)
                            (some-> declaration .getDeclaringType
                                    spoon/declaration-key))
                          :implicit? (.isImplicit ^CtElement declaration)
                          :synthetic? (boolean synthetic?)
                          :expansion :body
                          :representation :live-declaration})
                       (sort-by :identity rows)
                       (sort-by #(spoon/declaration-key (:declaration %))
                                (get live-by-shape shape)))))
               (sort-by (juxt (comp :identity :row) :declaration-key))
               vec)]
      (assoc surface :selection-evidence evidence))))

(defn- validate-generated! [surface emission]
  (let [by-key (group-by :java-key (filter :java-key (:declarations emission)))
        rows
        (mapv
         (fn [{:keys [declaration-key owner-declaration-key implicit? synthetic?]
               :as evidence}]
           (let [matches (get by-key declaration-key)
                 implicit-constructor?
                 (and implicit? (= "constructor" (get-in evidence [:row :kind]))
                      (zero? (get-in evidence [:row :parameter-count])))
                 owner-match (when implicit-constructor?
                               (first (get by-key owner-declaration-key)))
                 synthetic-match (when synthetic? (first matches))]
             (when-not (or (= 1 (count matches)) owner-match synthetic-match)
               (fail! "Java library surface declaration did not map to one generated declaration"
                      {:kind :java-library-generated-surface-mismatch
                       :declaration-key declaration-key
                       :match-count (count matches)}))
             (assoc evidence :generated
                    (or (when synthetic?
                          (-> synthetic-match
                              (assoc :representation :java-synthetic-public-member)
                              (assoc :destination
                                     (assoc (:destination synthetic-match)
                                            :kind (get-in evidence [:row :kind])
                                            :name (get-in evidence [:row :name])
                                            :parameter-count
                                            (str (get-in evidence
                                                         [:row :parameter-count]))))))
                        (first matches)
                        (-> owner-match
                            (assoc :representation :implicit-default-constructor)
                            (assoc :destination
                                   (assoc (:destination owner-match)
                                          :kind "constructor" :name ".ctor"
                                          :parameter-count "0")))))))
         (:selection-evidence surface))]
    {:schema-version 1 :strategy :complete-accessible-gradle-library
     :required-rows (count rows) :rows rows
     :compiled-contract-file (str (:compiled-contract-file surface))}))

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
                            (:compiled-contract-file public-metadata))
               (fail! "Clean Java library build lacks its compiled public-surface evidence"
                      {:kind :missing-compiled-java-library-surface
                       :assembly assembly :file (str file)}))
             (assoc (dotnet-surface/verify!
                     workspace (:compiled-contract-file public-metadata)
                     file public-metadata)
                    :assembly assembly :file (str file))))
         emissions)]
    {:strategy :complete-accessible-gradle-library :assemblies audits}))

(defn public-surface-strategy
  "Returns the complete accessible Gradle-library surface strategy."
  []
  {:schema-version 1
   :id :complete-accessible-gradle-library
   :product-family :java-library
   :read! read-surface!
   :validate-selected! validate-selected!
   :validate-generated! validate-generated!
   :verify-compiled! verify-compiled!})
