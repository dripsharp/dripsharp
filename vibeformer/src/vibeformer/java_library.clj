(ns vibeformer.java-library
  "Fail-closed destination foundation for ordinary Java libraries.

  This bundle intentionally contains no product identities. It accepts the
  product-neutral structural Java declarations and resolved type identities,
  and rejects every unimplemented shape with its live Spoon identity.
  Subsequent reusable translation work extends these rules; unsupported
  declarations never become generated stubs."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-project :as project-emission]
            [vibeformer.java-types :as java-types]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Base64 IdentityHashMap]
           [spoon.reflect.code CtArrayRead CtArrayWrite CtAssignment
            CtBinaryOperator CtBlock CtCatch CtCatchVariable CtComment
            CtConditional CtConstructorCall CtExecutableReferenceExpression
            CtExpression CtFieldRead CtFieldWrite
            CtFor CtForEach CtIf CtInvocation CtLambda CtLiteral CtLocalVariable
            CtNewArray CtReturn CtStatement CtThisAccess CtThrow CtTry
            CtTryWithResource CtTypeAccess CtUnaryOperator CtVariableAccess
            CtVariableRead CtVariableWrite]
           [spoon.reflect.declaration CtAnnotation CtClass CtConstructor CtElement
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

(defn- identifier [value]
  (let [clean (str/replace (str value) #"[^A-Za-z0-9_]" "_")]
    (if (re-matches #"[0-9].*" clean) (str "_" clean) clean)))

(defn- pascal [value]
  (let [value (identifier value)]
    (str (str/upper-case (subs value 0 1)) (subs value 1))))

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

(defn- destination-namespace [ctx ^CtType type]
  (let [package (package-name type)]
    (or (get-in ctx [:configuration :namespaces package])
        (some (fn [[source destination]]
                (when (or (= package source)
                          (str/starts-with? package (str source ".")))
                  (let [suffix (subs package (count source))
                        segments (remove str/blank? (str/split suffix #"\."))]
                    (str destination
                         (when (seq segments)
                           (str "." (str/join "." (map pascal segments))))))))
              (sort-by (comp - count key)
                       (get-in ctx [:configuration :namespace-prefixes])))
        (unsupported! "Java library has no destination namespace mapping" type))))

(declare type-node body-context)

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

(defn- mapped-type-base [ctx ^CtTypeReference reference occurrence]
  (let [qualified (.getQualifiedName reference)]
    (cond
      (= :null-type (:resolution occurrence))
      ["object" :dotnet.type/null]

      (instance? CtTypeParameterReference reference)
      [(identifier (.getSimpleName reference)) :dotnet.type/type-parameter]

      (= :project (:origin occurrence))
      (if-let [declaration (.getTypeDeclaration reference)]
        [(project-type-base ctx declaration) :dotnet.type/project]
        (unsupported! "Resolved project type has no live declaration" reference))

      :else
      (or (java-types/mapping qualified)
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
                arguments (vec (.getActualTypeArguments reference))]
            (if (seq arguments)
              (csharp/generic-name (raw target)
                                   (mapv #(type-node ctx %) arguments))
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

(defn- child-node [children ^CtElement element]
  (:node (java/child-result children element)))

(defn- role [^CtElement element]
  (when (.isParentInitialized element)
    (str (.getRoleInParent element))))

(defn- local-type? [^CtType type]
  (and (not (.isTopLevel type))
       (= "statement" (role type))))

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
       (contains? #{"statement" "then" "else"} (role element))))

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
       (nil? value) "null"
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

(defn- resolved-name [occurrence reference]
  (cond
    (= :project (:origin occurrence))
    (identifier (.getSimpleName ^CtElement reference))

    (contains?
     #{"executable:java.lang.Iterable#forEach(java.util.function.Consumer)"
       "executable:java.util.Collections#emptyList()"
       "executable:java.util.Collections#emptyMap()"
       "executable:java.util.Collections#singletonList(java.lang.Object)"
       "executable:java.util.Collections#unmodifiableList(java.util.List)"
       "executable:java.util.Collections#unmodifiableMap(java.util.Map)"
       "executable:java.net.URI#getHost()"
       "executable:java.net.URI#getPort()"
       "executable:java.lang.String#toUpperCase()"
       "executable:java.lang.String#split(java.lang.String)"
       "executable:java.lang.String#length()"
       "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
       "executable:java.lang.String#toCharArray()"
       "executable:java.lang.String#getBytes(java.nio.charset.Charset)"
       "executable:java.lang.StringBuilder#append(java.lang.String)"
       "executable:java.lang.StringBuilder#toString()"
       "executable:java.util.Collection#stream()"
       "executable:java.lang.Object#getClass()"
       "executable:java.lang.Throwable#getCause()"
       "executable:java.util.Map#entrySet()"
       "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)"
       "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
       "executable:java.util.Map#forEach(java.util.function.BiConsumer)"
       "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)"
       "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#keySet()"
       "executable:java.util.LinkedHashMap#keySet()"
       "executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"
       "executable:java.util.Map#size()"
       "executable:java.util.Map#get(java.lang.Object)"
       "executable:java.util.Map#remove(java.lang.Object)"
       "executable:java.util.HashMap#remove(java.lang.Object)"
       "executable:java.util.LinkedHashMap#remove(java.lang.Object)"
       "executable:java.util.Map#hashCode()"
       "executable:java.util.Map$Entry#getKey()"
       "executable:java.util.Map$Entry#getValue()"
       "executable:java.util.Map$Entry#setValue(java.lang.Object)"
       "executable:java.util.List#get(int)"
       "executable:java.util.List#equals(java.lang.Object)"
       "executable:java.util.List#isEmpty()"
       "executable:java.util.List#add(java.lang.Object)"
       "executable:java.util.List#removeIf(java.util.function.Predicate)"
       "executable:java.util.Collection#removeIf(java.util.function.Predicate)"
       "executable:java.util.List#addAll(java.util.Collection)"
       "executable:java.util.List#size()"
       "executable:java.util.Optional#empty()"
       "executable:java.util.Optional#of(java.lang.Object)"
       "executable:java.util.OptionalInt#isPresent()"
       "executable:java.util.OptionalInt#getAsInt()"
       "executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)"
       "executable:java.util.function.Consumer#accept(java.lang.Object)"
       "executable:java.util.Set#contains(java.lang.Object)"
       "executable:java.util.Set#equals(java.lang.Object)"
       "executable:java.util.Set#add(java.lang.Object)"
       "executable:java.util.Set#removeAll(java.util.Collection)"
       "executable:java.util.stream.Collectors#toList()"
       "executable:java.util.stream.Collectors#toSet()"
       "executable:java.util.stream.Stream#collect(java.util.stream.Collector)"
       "executable:java.util.stream.Stream#flatMap(java.util.function.Function)"
       "executable:java.util.stream.Stream#map(java.util.function.Function)"
       "executable:java.util.stream.Stream#of(java.lang.Object[])"
       "executable:java.io.OutputStream#write(byte[])"
       "executable:java.io.OutputStream#write(int)"}
     (:key occurrence))
    (identifier (.getSimpleName ^CtElement reference))

    (= "field:<array>#length" (:key occurrence))
    "Length"

    (= "field:java.nio.charset.StandardCharsets#US_ASCII" (:key occurrence))
    "USASCII"

    (= "field:java.nio.charset.StandardCharsets#ISO_8859_1" (:key occurrence))
    "ISO88591"

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
               {:node (raw (resolved-name occurrence element))}))

            :constructors
            (registry-entry
             (keyword "java-library.resolved.constructor"
                      (name (:origin occurrence)))
             (fn [{:keys [element occurrence]}]
               (cond
                 (= :project (:origin occurrence))
                 {:node (raw "<init>")}

                 (contains?
                  #{"executable:java.lang.Object#<init>()"
                    "executable:java.io.IOException#<init>()"
                    "executable:java.util.NoSuchElementException#<init>()"
                    "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"
                    "executable:java.lang.StringBuilder#<init>()"
                    "executable:java.lang.String#<init>(char[])"
                    "executable:java.util.HashMap#<init>()"
                    "executable:java.util.HashSet#<init>(int)"
                    "executable:java.util.ArrayList#<init>()"
                    "executable:java.util.ArrayList#<init>(int)"
                    "executable:java.util.ArrayList#<init>(java.util.Collection)"
                    "executable:java.util.LinkedHashMap#<init>()"
                    "executable:java.util.LinkedHashMap#<init>(int)"
                    "executable:java.util.LinkedHashMap#<init>(java.util.Map)"}
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
               {:node (raw (resolved-name occurrence element))}))

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

(defn- body-rules [ctx-holder]
  (java/structural-rules
   [{:id :java-library.expression/invocation
     :class CtInvocation
     :emit
     (fn [{:keys [context ^CtInvocation element children]}]
       (let [target (.getTarget element)
             target-node (when target (child-node children target))
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

               "executable:java.util.Collections#unmodifiableList(java.util.List)"
               (compat-call "UnmodifiableList" arguments)

               "executable:java.util.Collections#unmodifiableMap(java.util.Map)"
               (compat-call "UnmodifiableMap" arguments)

               "executable:java.net.URI#getHost()"
               (compat-call "UriHost" [target-node])

               "executable:java.net.URI#getPort()"
               (compat-call "UriPort" [target-node])

               "executable:java.lang.String#toUpperCase()"
               (sequence-node [target-node (raw ".ToUpper()")])

               "executable:java.lang.String#split(java.lang.String)"
               (compat-call "StringSplit" (into [target-node] (conj arguments (raw "0"))))

               "executable:java.lang.String#length()"
               (sequence-node [target-node (raw ".Length")])

               "executable:java.lang.String#equalsIgnoreCase(java.lang.String)"
               (compat-call "EqualsIgnoreCase" (into [target-node] arguments))

               "executable:java.lang.String#toCharArray()"
               (sequence-node [target-node (raw ".ToCharArray()")])

               "executable:java.lang.String#getBytes(java.nio.charset.Charset)"
               (compat-call "StringGetBytes" (into [target-node] arguments))

               "executable:java.io.OutputStream#write(byte[])"
               (compat-call "OutputStreamWrite" (into [target-node] arguments))

               "executable:java.io.OutputStream#write(int)"
               (compat-call "OutputStreamWrite" (into [target-node] arguments))

               "executable:java.lang.StringBuilder#append(java.lang.String)"
               (sequence-node [target-node (raw ".Append(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:java.lang.StringBuilder#toString()"
               (sequence-node [target-node (raw ".ToString()")])

               "executable:java.util.Collection#stream()"
               target-node

               "executable:java.lang.Object#getClass()"
               (sequence-node [target-node (raw ".GetType()")])

               "executable:java.lang.Throwable#getCause()"
               (sequence-node [target-node (raw ".InnerException")])

               "executable:java.util.Map#entrySet()"
               (compat-call "MapEntrySet" [target-node])

               "executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)"
               (compat-call "ComputeIfAbsent" (into [target-node] arguments))

               "executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"
               (compat-call "ComputeIfAbsent" (into [target-node] arguments))

               "executable:java.util.Map#forEach(java.util.function.BiConsumer)"
               (compat-call "ForEach" (into [target-node] arguments))

               "executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)"
               (compat-call "MapGetOrDefault" (into [target-node] arguments))

               "executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)"
               (compat-call "MapGetOrDefault" (into [target-node] arguments))

               "executable:java.util.Map#keySet()"
               (compat-call "MapKeySet" [target-node])

               "executable:java.util.LinkedHashMap#keySet()"
               (compat-call "MapKeySet" [target-node])

               "executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
               (compat-call "MapPut" (into [target-node] arguments))

               "executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"
               (compat-call "MapPut" (into [target-node] arguments))

               "executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"
               (compat-call "MapPut" (into [target-node] arguments))

               "executable:java.util.Map#size()"
               (compat-call "MapCount" [target-node])

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

               "executable:java.util.List#get(int)"
               (compat-call "ListGet" (into [target-node] arguments))

               "executable:java.util.List#size()"
               (sequence-node [target-node (raw ".Count")])

               "executable:java.util.Optional#empty()"
               (sequence-node [(type-node @ctx-holder (.getType element))
                               (raw ".Empty()")])

               "executable:java.util.Optional#of(java.lang.Object)"
               (sequence-node [(type-node @ctx-holder (.getType element))
                               (raw ".Of(") (sequence-node arguments ", ")
                               (raw ")")])

               "executable:java.util.OptionalInt#isPresent()"
               (sequence-node [target-node (raw ".HasValue")])

               "executable:java.util.OptionalInt#getAsInt()"
               (sequence-node [target-node (raw ".Value")])

               "executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)"
               (sequence-node [target-node (raw "(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:java.util.function.Consumer#accept(java.lang.Object)"
               (sequence-node [target-node (raw "(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:java.util.Set#contains(java.lang.Object)"
               (compat-call "CollectionContains" (into [target-node] arguments))

               "executable:java.util.Set#equals(java.lang.Object)"
               (compat-call "Equals" (into [target-node] arguments))

               "executable:java.util.Set#add(java.lang.Object)"
               (sequence-node [target-node (raw ".Add(")
                               (sequence-node arguments ", ") (raw ")")])

               "executable:java.util.Set#removeAll(java.util.Collection)"
               (compat-call "RemoveAll" (into [target-node] arguments))

               "executable:java.util.stream.Stream#of(java.lang.Object[])"
               (compat-call "StreamOf" arguments)

               "executable:java.util.stream.Stream#flatMap(java.util.function.Function)"
               (compat-call "FlatMap" (into [target-node] arguments))

               "executable:java.util.stream.Stream#map(java.util.function.Function)"
               (compat-call "Map" (into [target-node] arguments))

               "executable:java.util.stream.Collectors#toList()"
               (raw "global::Vibeformer.Runtime.JavaCompat.ToList<object>()")

               "executable:java.util.stream.Collectors#toSet()"
               (sequence-node
                [(csharp/generic-name
                  (raw "global::Vibeformer.Runtime.JavaCompat.ToSet")
                  [(type-node @ctx-holder (collection-element-type element))])
                 (raw "()")])

               "executable:java.util.stream.Stream#collect(java.util.stream.Collector)"
               (if (= "java.util.Set" (some-> element .getType .getQualifiedName))
                 (sequence-node
                  [(csharp/generic-name
                    (raw "global::Vibeformer.Runtime.JavaCompat.SetOfValues")
                    [(type-node @ctx-holder (collection-element-type element))])
                   (raw "(") target-node (raw ")")])
                 (compat-call "ToListValues" [target-node]))

               "executable:java.lang.Object#<init>()"
               (raw "")

               (sequence-node
                [(when target
                   (sequence-node [target-node (raw ".")]))
                 (child-node children (.getExecutable element))
                 (raw "(")
                 (sequence-node arguments ", ")
                 (raw ")")]))
             node (expression-cast-node @ctx-holder element raw-node)]
         {:node
          (if (= "executable:java.lang.Object#<init>()" (:key occurrence))
            node
            (invocation-statement element node))}))}

    {:id :java-library.expression/constructor-call
     :class CtConstructorCall
     :emit
     (fn [{:keys [context ^CtConstructorCall element children]}]
       (let [arguments (mapv #(child-node children %) (.getArguments element))
             occurrence (constructor-occurrence context element)]
         {:node
          (case (:key occurrence)
            "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"
            (sequence-node [(raw "new global::System.Exception(null, ")
                            (first arguments) (raw ")")])

            (sequence-node
             [(raw "new ") (type-node @ctx-holder (.getType element)) (raw "(")
              (sequence-node arguments ", ")
              (raw ")")]))}))}

    {:id :java-library.expression/lambda
     :class CtLambda
     :emit
     (fn [{:keys [^CtLambda element children]}]
       (let [body (or (.getExpression element) (.getBody element))]
         {:node
          (sequence-node
           [(raw "(")
            (sequence-node
             (mapv #(child-node children %) (.getParameters element)) ", ")
            (raw ") => ")
            (child-node children body)])}))}

    {:id :java-library.expression/method-reference
     :class CtExecutableReferenceExpression
     :emit
     (fn [{:keys [context ^CtExecutableReferenceExpression element children]}]
       (let [target (child-node children (.getTarget element))
             executable (child-node children (.getExecutable element))
             occurrence (.get ^IdentityHashMap (:occurrence-index context)
                              (.getExecutable element))
             declaration (:declaration occurrence)
             parameter-count (count (.getParameters (.getExecutable element)))
             parameters (mapv #(raw (str "value" %)) (range parameter-count))
             functional-type (some-> element .getType .getQualifiedName)
             discards-result?
             (and (instance? CtMethod declaration)
                  (not= "void" (.getQualifiedName (.getType ^CtMethod declaration)))
                  (contains? #{"java.util.function.Consumer"
                               "java.util.function.BiConsumer"}
                             functional-type))]
         (when-not (and (= :project (:origin occurrence))
                        (instance? CtMethod declaration))
           (unsupported! "Java library method reference requires a resolved project method"
                         element))
         {:node
          (if discards-result?
            (sequence-node
             [(raw "(") (sequence-node parameters ", ") (raw ") => { ")
              target (raw ".") executable (raw "(")
              (sequence-node parameters ", ") (raw "); }")])
            (sequence-node [target (raw ".") executable]))}))}

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
       {:node
        (expression-cast-node
         @ctx-holder element
         (sequence-node
          [(raw "(")
           (child-node children (.getLeftHandOperand element))
           (raw (str " " (binary-operator element) " "))
           (child-node children (.getRightHandOperand element))
           (raw ")")]))})}

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
             occurrence (field-occurrence context element)]
         {:node
          (sequence-node
           [(when target
              (sequence-node [(child-node children target) (raw ".")]))
            (if (= "field:<array>#length" (:key occurrence))
              (raw "Length")
              (child-node children (.getVariable element)))])}))}

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

    {:id :java-library.expression/assignment
     :class CtAssignment
     :emit
     (fn [{:keys [^CtAssignment element children]}]
       {:node
        (sequence-node
         [(child-node children (.getAssigned element))
          (raw " = ")
          (child-node children (.getAssignment element))
          (when (statement-expression? element) (raw ";"))])})}

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
       (when (and (instance? CtTryWithResource element)
                  (seq (.getResources ^CtTryWithResource element)))
         (unsupported! "Java try-with-resources requires explicit lifetime lowering"
                       element))
       {:node
        (sequence-node
         [(raw "try ")
          (child-node children (.getBody element))
          (sequence-node
           (mapv #(sequence-node [(raw " ") (child-node children %)])
                 (.getCatchers element)))
          (when-let [finalizer (.getFinalizer element)]
            (sequence-node [(raw " finally ")
                            (child-node children finalizer)]))])})}

    {:id :java-library.statement/catch
     :class CtCatch
     :emit
     (fn [{:keys [^CtCatch element children]}]
       {:node
        (sequence-node
         [(raw "catch (")
          (child-node children (.getParameter element))
          (raw ") ")
          (child-node children (.getBody element))])})}

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
              (child-node children (.getType element)))
            (raw (str " " (identifier (.getSimpleName element))))
            (when initializer
              (sequence-node [(raw " = ")
                              (child-node children initializer)]))
            (when-not (= "forInit" (role element))
              (raw ";"))])}))}

    {:id :java-library.expression/variable-read
     :class CtVariableRead
     :emit
     (fn [{:keys [^CtVariableRead element children]}]
       {:node (child-node children (.getVariable element))})}

    {:id :java-library.expression/variable-write
     :class CtVariableWrite
     :emit
     (fn [{:keys [^CtVariableWrite element children]}]
       {:node (child-node children (.getVariable element))})}

    {:id :java-library.reference/parameter
     :class CtParameterReference
     :emit (fn [{:keys [^CtParameterReference element]}]
             {:node (raw (identifier (.getSimpleName element)))})}

    {:id :java-library.declaration/lambda-parameter
     :class CtParameter
     :emit (fn [{:keys [^CtParameter element]}]
             {:node (raw (identifier (.getSimpleName element)))})}

    {:id :java-library.reference/local-variable
     :class CtLocalVariableReference
     :emit (fn [{:keys [^CtLocalVariableReference element]}]
             {:node (raw (identifier (.getSimpleName element)))})}

    {:id :java-library.declaration/catch-variable
     :class CtCatchVariable
     :emit
     (fn [{:keys [^CtCatchVariable element]}]
       (let [types (vec (.getMultiTypes element))]
         (when-not (= 1 (count types))
           (unsupported! "Java multi-catch requires explicit union lowering"
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
     :emit (fn [_] {:node (raw "this")})}

    {:id :java-library.declaration/local-class
     :class CtClass
     :emit
     (fn [{:keys [^CtClass element]}]
       (if (local-type? element)
         {:node (raw "")}
         (unsupported! "Only method-local classes are valid body declarations"
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
  (str (destination-namespace ctx type) "."
       (str/join "." (map #(pascal (.getSimpleName ^CtType %))
                          (declaring-types type)))))

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
    :else :member))

(defn- register-member! [ctx ^CtType owner ^CtElement member name rule]
  (let [kind (member-kind member)
        id (declaration-id member kind)
        parameter-count (if (instance? CtExecutable member)
                          (count (.getParameters ^CtExecutable member))
                          0)
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
                                     "member")
                             :name (if (= :constructor kind) ".ctor" name)
                             :parameter-count (str parameter-count)}
               :source (source-ref member rule nil)}]
    (when (.containsKey ^IdentityHashMap (:emitted ctx) member)
      (fail! "A Java library declaration was emitted more than once"
             {:kind :duplicate-source-declaration :declaration entry}))
    (.put ^IdentityHashMap (:emitted ctx) member entry)
    (swap! (:declarations ctx) conj entry)
    id))

(defn- explicit-members [^CtType type]
  (vec (remove #(.isImplicit ^CtElement %) (.getTypeMembers type))))

(defn- visibility [^CtModifiable element default]
  (cond
    (.hasModifier element ModifierKind/PUBLIC) "public"
    (.hasModifier element ModifierKind/PROTECTED) "protected"
    (.hasModifier element ModifierKind/PRIVATE) "private"
    :else default))

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
                       (mapv #(raw (identifier (.getSimpleName ^CtElement %)))
                             parameters)
                       ", ")
                      (raw ">")]))))

(defn- parameter-node [ctx ^CtParameter parameter]
  (sequence-node
   [(when (.isVarArgs parameter) (raw "params "))
    (type-node ctx (.getType parameter))
    (raw (str " " (identifier (.getSimpleName parameter))))]))

(defn- base-type-references [^CtType type]
  (vec
   (concat
    (when (instance? CtClass type)
      (when-let [superclass (.getSuperclass ^CtClass type)]
        (when-not (= "java.lang.Object" (.getQualifiedName superclass))
          [superclass])))
    (sort-by #(.getQualifiedName ^CtTypeReference %)
             (.getSuperInterfaces type)))))

(defn- type-words [^CtType type]
  (let [visibility (cond
                     (local-type? type) "private"
                     (.hasModifier ^CtModifiable type ModifierKind/PUBLIC) "public"
                     :else "internal")]
    (cond
      (instance? CtInterface type) [visibility "interface"]
      (instance? CtClass type)
      (remove nil?
              [visibility
               (when (.hasModifier ^CtModifiable type ModifierKind/ABSTRACT)
                 "abstract")
               (when (.hasModifier ^CtModifiable type ModifierKind/FINAL)
                 "sealed")
               "class"])
      :else nil)))

(defn- method-words [^CtType owner ^CtMethod method]
  (remove nil?
          [(visibility method (if (instance? CtInterface owner) "public" "internal"))
           (when (.hasModifier method ModifierKind/STATIC) "static")
           (when (and (not (instance? CtInterface owner))
                      (.hasModifier method ModifierKind/ABSTRACT))
             "abstract")]))

(declare member-node emit-root)

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

(defn- field-node [ctx ^CtType owner ^CtField field]
  (let [initializer (.getDefaultExpression field)
        initializer-node (when initializer (translated-node ctx initializer))
        name (identifier (.getSimpleName field))
        rule :java-library.declaration/field
        id (register-member! ctx owner field name rule)]
    (csharp/with-source
      (sequence-node
       [(raw (str (str/join " "
                            (remove nil?
                                    [(visibility field "internal")
                                     (when (.hasModifier field ModifierKind/STATIC)
                                       "static")
                                     (when (.hasModifier field ModifierKind/FINAL)
                                       "readonly")]))
                  " "))
        (type-node ctx (.getType field))
        (raw (str " " name))
        (when initializer-node
          (sequence-node [(raw " = ") initializer-node]))
        (raw ";")])
      (source-ref field rule {:declaration-id id :declaration-kind :field}))))

(defn- method-node [ctx ^CtType owner ^CtMethod method]
  (let [local-types (mapv validate-local-type!
                          (executable-local-types method))
        body (.getBody method)
        body-node (when body (translated-node ctx body))
        name (identifier (.getSimpleName method))
        rule :java-library.declaration/method
        id (register-member! ctx owner method name rule)
        method-node
        (csharp/with-source
          (sequence-node
           [(raw (str (str/join " " (method-words owner method)) " "))
            (type-node ctx (.getType method))
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
           (map #(emit-root ctx %) local-types))
     "\n\n")))

(defn- constructor-node [ctx ^CtType owner ^CtConstructor constructor]
  (let [name (pascal (.getSimpleName owner))
        rule :java-library.declaration/constructor
        id (register-member! ctx owner constructor name rule)
        body (.getBody constructor)]
    (when-not body
      (unsupported! "Java library constructor has no body" constructor))
    (csharp/with-source
      (sequence-node
       [(raw (str (visibility constructor "internal") " " name "("))
        (sequence-node (mapv #(parameter-node ctx %) (.getParameters constructor))
                       ", ")
        (raw ") ")
        (translated-node ctx body)])
      (source-ref constructor rule
                  {:declaration-id id :declaration-kind :constructor}))))

(defn- member-node [ctx ^CtType owner member]
  (cond
    (instance? CtField member) (field-node ctx owner member)
    (instance? CtMethod member) (method-node ctx owner member)
    (instance? CtConstructor member) (constructor-node ctx owner member)
    (instance? CtType member) (emit-root ctx member)
    :else (unsupported! "Java library member shape is not implemented" member)))

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
        members (explicit-members type)
        member-nodes (if-let [emit-members (:emit-members ctx)]
                       (emit-members ctx type members)
                       (mapv #(member-node ctx type %) members))
        source (source-ref type rule
                           {:declaration-id id :declaration-kind :type})]
    (csharp/with-source
      (sequence-node
       [(raw (str (str/join " " (type-words type)) " " name))
        (type-formals-node type)
        (when (seq bases)
          (sequence-node [(raw " : ")
                          (sequence-node (mapv #(type-node ctx %) bases) ", ")]))
        (if (seq member-nodes)
          (sequence-node [(raw " {\n")
                          (sequence-node member-nodes "\n\n")
                          (raw "\n}")])
          (raw " {}"))])
      source)))

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

(defn- dependency-scopes [discovery coordinate]
  (->> (:external-dependencies discovery)
       (filter #(= coordinate (:coordinate %)))
       (map :scope) set))

(defn- validate-discovery! [{:keys [discovery configuration]}]
  (let [expected-projects (set (:project-dependencies configuration))
        actual-projects (set (map :project (:project-dependencies discovery)))]
    (when-not (= expected-projects actual-projects)
      (fail! "Gradle project dependencies differ from the destination contract"
             {:kind :source-project-dependency-mismatch
              :expected (sort expected-projects) :actual (sort actual-projects)})))
  (let [expected (or (:external-dependencies configuration) {})
        actual-coordinates (set (map :coordinate (:external-dependencies discovery)))]
    (when-not (= (set (keys expected)) actual-coordinates)
      (fail! "Gradle external dependencies differ from the destination contract"
             {:kind :source-external-dependency-mismatch
              :expected (sort (keys expected)) :actual (sort actual-coordinates)}))
    (doseq [[coordinate {:keys [source-scope artifact-sha256]}] expected]
      (let [scopes (dependency-scopes discovery coordinate)
            required (case source-scope
                       :compile-only #{:compile}
                       :compile-runtime #{:compile :runtime})]
        (when-not (= required scopes)
          (fail! "Gradle dependency scope differs from the destination contract"
                 {:kind :source-external-dependency-scope-mismatch
                  :coordinate coordinate :expected required :actual scopes})))
      (when artifact-sha256
        (let [hashes (->> (:external-artifacts discovery)
                          (filter #(= coordinate (:coordinate %)))
                          (map :sha256) set)]
          (when-not (= #{artifact-sha256} hashes)
            (fail! "Gradle dependency artifact differs from the destination contract"
                   {:kind :source-external-artifact-mismatch
                    :coordinate coordinate :expected artifact-sha256
                    :actual (sort hashes)}))))))
  discovery)

(defn rule-bundle
  "Returns the ordinary Java-library destination bundle."
  []
  {:schema-version 1
   :id :java-library
   :product-family :java-library
   :orchestration {:validate-discovery! validate-discovery!}
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

(defn- read-surface! [workspace {:keys [contract-file] :as specification}]
  (when-not (= #{:contract-file} (set (keys specification)))
    (fail! "Invalid Java library public-surface specification"
           {:kind :invalid-java-library-surface-specification
            :specification specification}))
  (let [file (paths/resolve-path (paths/absolute workspace) contract-file)]
    (when-not (paths/regular-file? file)
      (fail! "Java library public-surface contract is missing"
             {:kind :missing-java-library-surface :file (str file)}))
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
      {:contract-file file :rows rows :seeds []})))

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
     :required-rows (count rows) :rows rows}))

(defn- verify-compiled! [_workspace generation build-configuration]
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
                            (pos? (:required-rows public-metadata)))
               (fail! "Clean Java library build lacks its compiled public-surface evidence"
                      {:kind :missing-compiled-java-library-surface
                       :assembly assembly :file (str file)}))
             {:assembly assembly :contract-members (:required-rows public-metadata)}))
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
