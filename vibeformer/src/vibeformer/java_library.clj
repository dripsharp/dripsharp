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
           [spoon.reflect.declaration CtAnnotation CtClass CtConstructor CtElement
            CtEnum CtEnumValue CtExecutable CtField CtInterface CtMethod
            CtModifiable CtParameter CtType ModifierKind]
           [spoon.reflect.reference CtArrayTypeReference CtTypeParameterReference
            CtTypeReference CtWildcardReference]))

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
  (some-> type .getPackage .getQualifiedName))

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

(defn- context [options]
  (assoc options
         :emitted (IdentityHashMap.)
         :declarations (atom [])
         :diagnostics (atom [])
         :body-translations (atom [])))

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

(declare type-node)

(defn- mapped-type-base [ctx ^CtTypeReference reference occurrence]
  (let [qualified (.getQualifiedName reference)]
    (cond
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
  (let [visibility (if (.hasModifier ^CtModifiable type ModifierKind/PUBLIC)
                     "public" "internal")]
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

(defn- field-node [ctx ^CtType owner ^CtField field]
  (when-let [initializer (.getDefaultExpression field)]
    (unsupported! "Java library field initializer translation is not implemented"
                  initializer))
  (let [name (identifier (.getSimpleName field))
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
        (raw (str " " name ";"))])
      (source-ref field rule {:declaration-id id :declaration-kind :field}))))

(defn- method-node [ctx ^CtType owner ^CtMethod method]
  (when-let [body (.getBody method)]
    (unsupported! "Java library executable body translation is not implemented" body))
  (let [name (identifier (.getSimpleName method))
        rule :java-library.declaration/method
        id (register-member! ctx owner method name rule)]
    (csharp/with-source
      (sequence-node
       [(raw (str (str/join " " (method-words owner method)) " "))
        (type-node ctx (.getType method))
        (raw (str " " name))
        (executable-formals-node method)
        (raw "(")
        (sequence-node (mapv #(parameter-node ctx %) (.getParameters method)) ", ")
        (raw ");")])
      (source-ref method rule {:declaration-id id :declaration-kind :method}))))

(defn- constructor-node [_ctx _owner ^CtConstructor constructor]
  (unsupported! "Java library constructor body translation is not implemented"
                (or (.getBody constructor) constructor)))

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
