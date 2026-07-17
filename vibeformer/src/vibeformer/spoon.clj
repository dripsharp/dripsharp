(ns vibeformer.spoon
  "Builds and validates the live Spoon model consumed by Java translation.

  This namespace deliberately retains Spoon objects.  The occurrence and symbol
  indexes are navigation aids over the frontend model, not a serialized semantic
  fact model or a replacement AST."
  (:require [clojure.string :as str]
            [vibeformer.concurrency :as concurrency])
  (:import [java.io File]
           [java.lang.reflect Constructor Field]
           [java.nio.file Path]
           [java.util Collections IdentityHashMap WeakHashMap]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong]
           [java.util.function Function]
           [spoon Launcher]
           [spoon.processing FactoryAccessor]
           [spoon.reflect CtModel]
           [spoon.reflect.code CtInvocation CtThisAccess CtTypeAccess]
           [spoon.reflect.cu SourcePosition]
           [spoon.reflect.declaration CtAnnotation CtAnonymousExecutable CtClass CtElement CtEnum CtEnumValue
            CtExecutable CtField CtInterface CtMethod CtModifiable CtRecord CtRecordComponent CtType CtTypeMember
            CtTypeParameter ModifierKind]
           [spoon.reflect.reference CtArrayTypeReference CtExecutableReference
            CtFieldReference CtIntersectionTypeReference CtTypeParameterReference
            CtTypeReference CtWildcardReference]
           [spoon.reflect.visitor.filter TypeFilter]))

(defrecord JavaFrontendModel
  [^Launcher launcher
   ^CtModel model
   compilation-units
   project-types
   source-files
   totals])

(defrecord ResolvedJavaModel
  [^Launcher launcher
   ^CtModel model
   compilation-units
   project-types
   symbols
   occurrences
   totals])

(defrecord ResolvedJavaClosure
  [^JavaFrontendModel frontend
   seeds
   declarations
   source-inputs
   public-api-declarations
   symbols
   occurrences
   totals])

(defrecord CanonicalSourceCache
  [^ConcurrentHashMap paths
   ^AtomicLong canonical-requests
   ^AtomicLong canonical-computations
   ^AtomicLong source-location-calls
   ^AtomicLong frontend-renderings])

(def ^{:private true :tag java.util.Map} frontend-source-caches
  ;; A cache belongs to one Spoon factory/frontend lifecycle. Weak keys keep a
  ;; completed frontend collectible without imposing an explicit close API.
  (Collections/synchronizedMap (WeakHashMap.)))

(defn- new-canonical-source-cache []
  (->CanonicalSourceCache (ConcurrentHashMap.)
                          (AtomicLong.) (AtomicLong.) (AtomicLong.) (AtomicLong.)))

(defn- register-source-cache!
  [^Launcher launcher cache]
  (.put frontend-source-caches (.getFactory launcher) cache)
  cache)

(defn- source-cache-for-element
  [^CtElement element]
  (when-let [factory (.getFactory ^FactoryAccessor element)]
    (.get frontend-source-caches factory)))

(defn- canonical-file
  (^String [^File file]
   (.getCanonicalPath file))
  (^String [^CanonicalSourceCache cache ^File file]
   (let [^AtomicLong requests (:canonical-requests cache)]
     (.incrementAndGet requests))
   (let [key (-> file .getAbsoluteFile .toPath .normalize str)]
     (.computeIfAbsent
      ^ConcurrentHashMap (:paths cache)
      key
      (reify Function
        (apply [_ _]
          (let [^AtomicLong computations (:canonical-computations cache)]
            (.incrementAndGet computations))
          (.getCanonicalPath file)))))))

(defn- effective-position
  ^SourcePosition [^CtElement element]
  (loop [current element]
    (when current
      (let [position (.getPosition current)]
        (if (and position (.isValidPosition position))
          position
          (when (.isParentInitialized current)
            (recur (.getParent current))))))))

(defn source-location
  "Returns a stable source location for a Spoon object, using its closest
  positioned frontend parent for references whose own position is implicit."
  [^CtElement element]
  (let [cache (source-cache-for-element element)]
    (when cache
      (let [^AtomicLong calls (:source-location-calls cache)]
        (.incrementAndGet calls)))
    (when-let [position (effective-position element)]
      {:file (if cache
               (canonical-file cache (.getFile position))
               (canonical-file (.getFile position)))
       :line (.getLine position)
       :column (.getColumn position)})))

(defn source-location-cache-stats
  "Returns instrumentation for the frontend-scoped canonical source cache."
  [frontend-or-resolved-model]
  (when-let [^CanonicalSourceCache cache
             (when-let [^Launcher launcher (:launcher frontend-or-resolved-model)]
               (.get frontend-source-caches (.getFactory launcher)))]
    (let [^AtomicLong location-calls (:source-location-calls cache)
          ^AtomicLong canonical-requests (:canonical-requests cache)
          ^AtomicLong canonical-computations (:canonical-computations cache)
          ^AtomicLong frontend-renderings (:frontend-renderings cache)]
      {:source-location-calls (.get location-calls)
       :canonical-requests (.get canonical-requests)
       :canonical-computations (.get canonical-computations)
       :cached-source-identities (.size ^ConcurrentHashMap (:paths cache))
       :frontend-renderings (.get frontend-renderings)})))

(defn frontend-identity
  "Returns stable, cheap identity metadata for a live Spoon element. Full
  Spoon pretty-printing is deliberately excluded because accepted translation
  records this metadata for every visited element."
  [^CtElement element]
  {:frontend-class (.getName (class element))
   :role (when (.isParentInitialized element)
           (str (.getRoleInParent element)))})

(defn frontend-diagnostic
  "Adds the expensive Spoon rendering used only when reporting a failure."
  [^CtElement element]
  (when-let [^CanonicalSourceCache cache (source-cache-for-element element)]
    (let [^AtomicLong renderings (:frontend-renderings cache)]
      (.incrementAndGet renderings)))
  (assoc (frontend-identity element)
         :rendered (try
                     (str element)
                     (catch Throwable _ "<frontend rendering failed>"))))

(defn- diagnostic
  [kind ^CtElement element message]
  {:kind kind
   :message message
   :location (source-location element)
   :frontend (frontend-diagnostic element)})

(defn- parent-of-type
  [^CtElement element klass]
  (loop [current element]
    (when (and current (.isParentInitialized current))
      (let [parent (.getParent current)]
        (if (instance? klass parent)
          parent
          (recur parent))))))

(defn- type-name
  [^CtTypeReference reference]
  (cond
    (instance? CtArrayTypeReference reference)
    (str (type-name (.getComponentType ^CtArrayTypeReference reference)) "[]")

    (instance? CtWildcardReference reference)
    (let [^CtWildcardReference wildcard reference]
      (str "?" (when-let [bound (.getBoundingType wildcard)]
                 (str (if (.isUpper wildcard) " extends " " super ")
                      (type-name bound)))))

    (instance? CtIntersectionTypeReference reference)
    (str/join "&" (map type-name (.getBounds ^CtIntersectionTypeReference reference)))

    :else
    (.getQualifiedName reference)))

(defn- executable-owner-key
  [^CtExecutableReference reference]
  (when-let [owner (.getDeclaringType reference)]
    (str (type-name owner) "#" (.getSignature reference))))

(defn- formal-declarer-key
  [declarer]
  (cond
    (instance? CtType declarer)
    (.getQualifiedName ^CtType declarer)

    (instance? CtExecutable declarer)
    (let [^CtExecutable executable declarer
          ^CtType owner (parent-of-type executable CtType)]
      (str (when owner (.getQualifiedName owner)) "#" (.getSignature executable)))

    :else nil))

(defn- type-parameter-key
  [^CtTypeParameterReference reference]
  (if (instance? CtWildcardReference reference)
    (str "type:" (type-name reference))
    (if-let [^CtTypeParameter declaration
             (try (.getDeclaration reference) (catch Throwable _ nil))]
      (when-let [declarer-key
                 (try
                   (formal-declarer-key (.getTypeParameterDeclarer declaration))
                   (catch Throwable _ nil))]
        (str "type-parameter:" declarer-key
             "#" (.getSimpleName reference)))
      ;; Spoon represents formal variables nested in a resolved library method
      ;; signature (for example List<? extends T>) without attaching a shadow
      ;; CtTypeParameter.  Their exact owner is still the resolved executable
      ;; reference, which is the stable semantic identity used here.
      (if-let [^CtExecutableReference owner
               (or (parent-of-type reference CtExecutableReference)
                   (some-> (parent-of-type reference CtInvocation) .getExecutable))]
        (str "type-parameter:" (executable-owner-key owner)
             "#" (.getSimpleName reference))
        (when-let [^CtFieldReference field (parent-of-type reference CtFieldReference)]
          (str "type-parameter:"
               (some-> field .getDeclaringType type-name) "#"
               (.getSimpleName field) "#" (.getSimpleName reference)))))))

(defn- jdk-class?
  [^Class klass]
  (let [module-name (some-> klass .getModule .getName)]
    (or (and module-name
             (or (str/starts-with? module-name "java.")
                 (str/starts-with? module-name "jdk.")))
        (nil? (some-> klass .getProtectionDomain .getCodeSource)))))

(defn- class-origin
  [^Class klass]
  (if (jdk-class? klass) :jdk :dependency))

(defn- resolved
  [kind key origin reference declaration resolution]
  {:kind kind
   :key key
   :origin origin
   :reference reference
   :declaration declaration
   :resolution resolution
   :location (source-location reference)})

(defn- indexed-project-type?
  [project-types candidate]
  (boolean (some #(identical? candidate %) (vals project-types))))

(defn- implicit-record-self-declaration
  [^CtTypeReference reference ^CtElement context]
  (let [parent (when (.isParentInitialized reference) (.getParent reference))
        role (str (.getRoleInParent reference))
        enclosing (parent-of-type context CtType)
        self-access? (or (and (= "type" role)
                              (instance? CtThisAccess parent))
                         (and (= "accessedType" role)
                              (instance? CtTypeAccess parent))
                         (and (= "declaringType" role)
                              (instance? CtFieldReference parent)))]
    (when (and (.isImplicit reference)
               self-access?
               (instance? CtRecord enclosing))
      enclosing)))

(defn- project-type-declaration
  "Finds a source type through Spoon declarations.  Spoon models self-accesses
  in synthesized record accessors with unqualified implicit references; their
  structural CtThisAccess/CtTypeAccess/CtFieldReference role identifies the live
  enclosing CtRecord without a name or source-text guess."
  [project-types ^CtTypeReference reference ^CtElement context]
  (or (get project-types (.getQualifiedName reference))
      (let [declaration (try (.getTypeDeclaration reference)
                             (catch Throwable _ nil))]
        (when (and declaration (indexed-project-type? project-types declaration))
          declaration))
      (let [enclosing (implicit-record-self-declaration reference context)]
        (when (and enclosing (indexed-project-type? project-types enclosing))
          enclosing))))

(defn- resolve-type
  [project-types ^CtTypeReference reference]
  (try
    (cond
      (= CtTypeReference/NULL_TYPE_NAME (.getSimpleName reference))
      (resolved :type "type:<null>" :intrinsic reference nil :null-type)

      (.isPrimitive reference)
      (resolved :type (str "type:" (.getQualifiedName reference))
                :intrinsic reference nil :primitive)

      (instance? CtArrayTypeReference reference)
      (resolved :type (str "type:" (type-name reference))
                :intrinsic reference nil :array)

      (instance? CtIntersectionTypeReference reference)
      (resolved :type (str "type:" (type-name reference))
                :intrinsic reference nil :intersection)

      (instance? CtTypeParameterReference reference)
      (if-let [key (type-parameter-key reference)]
        (resolved :type key :type-parameter reference
                  (when-not (instance? CtWildcardReference reference)
                    (try (.getDeclaration ^CtTypeParameterReference reference)
                         (catch Throwable _ nil)))
                  (if (instance? CtWildcardReference reference)
                    :wildcard
                    :formal-type-parameter))
        {:failure (diagnostic
                   :unresolved-type-parameter reference
                   (str "Cannot identify formal type parameter "
                        (.getSimpleName reference)))})

      :else
      (if-let [^CtType declaration
               (project-type-declaration project-types reference reference)]
          (resolved :type (str "type:" (.getQualifiedName declaration)) :project
                    reference declaration :source-declaration)
          (let [actual-class (.getActualClass reference)]
            (resolved :type (str "type:" (.getName actual-class))
                      (class-origin actual-class) reference actual-class
                      :runtime-class))))
    (catch Throwable error
      {:failure (diagnostic
                 :unresolved-type reference
                 (str "Cannot resolve type " (type-name reference) ": "
                      (.getMessage error)))})))

(defn- parameter-signature
  [^CtExecutableReference reference]
  (str/join "," (map type-name (.getParameters reference))))

(defn- executable-key
  [^CtExecutableReference reference]
  (let [owner (some-> reference .getDeclaringType type-name)
        name (if (.isConstructor reference) "<init>" (.getSimpleName reference))]
    (str "executable:" owner "#" name "(" (parameter-signature reference) ")")))

(defn- implicit-constructor-resolution
  [^CtType owner ^CtExecutableReference reference]
  (cond
    (instance? CtRecord owner)
    (let [parameters (mapv type-name (.getParameters reference))
          components (mapv #(type-name (.getType ^CtRecordComponent %))
                           (.getRecordComponents ^CtRecord owner))]
      (when (= parameters components) :record-canonical-constructor))

    (and (instance? CtClass owner)
         (empty? (.getConstructors ^CtClass owner))
         (empty? (.getParameters reference)))
    :default-constructor

    :else nil))

(declare record-component)

(defn- same-executable-signature?
  [^CtExecutableReference left ^CtExecutableReference right]
  (and (= (.getSimpleName left) (.getSimpleName right))
       (= (mapv type-name (.getParameters left))
          (mapv type-name (.getParameters right)))))

(defn- inherited-executable-resolution
  [^CtType owner ^CtExecutableReference reference]
  (when-let [^CtExecutableReference inherited
             (some #(when (same-executable-signature? reference %) %)
                   (.getAllExecutables owner))]
    (let [declaration (.getExecutableDeclaration inherited)]
      (if (and declaration
               (not (.isShadow ^CtType (.getDeclaringType ^CtExecutable declaration))))
        (resolved (if (.isConstructor reference) :constructor :executable)
                  (executable-key reference) :project reference declaration
                  :inherited-source-declaration)
        (let [member (if (.isConstructor inherited)
                       (.getActualConstructor inherited)
                       (.getActualMethod inherited))
              ^Class declaring-class (.getDeclaringClass member)]
          (resolved (if (instance? Constructor member) :constructor :executable)
                    (executable-key reference) (class-origin declaring-class)
                    reference member :inherited-runtime-member))))))

(defn- resolve-executable
  [project-types ^CtExecutableReference reference]
  (try
    (if (and (.isConstructor reference)
             (instance? CtArrayTypeReference (.getDeclaringType reference)))
      (resolved :constructor (executable-key reference) :intrinsic reference nil
                :array-constructor)
      (if-let [owner-reference (.getDeclaringType reference)]
        (let [owner-name (type-name owner-reference)]
          (if-let [owner (get project-types owner-name)]
            (if-let [component (when (and (instance? CtRecord owner)
                                          (not (.isConstructor reference))
                                          (empty? (.getParameters reference)))
                                 (record-component owner (.getSimpleName reference)))]
              (resolved :executable (executable-key reference) :project reference
                        component :record-component-accessor)
              (if-let [declaration (.getExecutableDeclaration reference)]
                (if (and (instance? CtEnum owner)
                         (.isImplicit ^CtElement declaration))
                  (resolved (if (.isConstructor reference)
                              :constructor
                              :executable)
                            (executable-key reference) :intrinsic reference owner
                            (if (.isConstructor reference)
                              :enum-synthetic-constructor
                              :enum-synthetic-method))
                  (resolved (if (.isConstructor reference) :constructor :executable)
                            (executable-key reference) :project reference declaration
                            :source-declaration))
                (or (inherited-executable-resolution owner reference)
                    (if-let [implicit-resolution
                             (when (.isConstructor reference)
                               (implicit-constructor-resolution owner reference))]
                      ;; Java records and ordinary classes may have an implicit canonical
                      ;; or default constructor.  The resolved constructor reference and
                      ;; its live CtType declaration are the frontend identity; no
                      ;; constructor AST is reconstructed here.
                      (resolved :constructor (executable-key reference) :project
                                reference owner implicit-resolution)
                      {:failure (diagnostic
                                 :unresolved-executable reference
                                 (str "Cannot resolve project executable "
                                      (executable-key reference)))}))))
            (let [member (if (.isConstructor reference)
                           (.getActualConstructor reference)
                           (.getActualMethod reference))
                  ^Class declaring-class (.getDeclaringClass member)]
              (resolved (if (instance? Constructor member) :constructor :executable)
                        (executable-key reference) (class-origin declaring-class)
                        reference member :runtime-member))))
        {:failure (diagnostic :unresolved-executable reference
                              "Executable reference has no declaring type")}))
    (catch Throwable error
      {:failure (diagnostic
                 :unresolved-executable reference
                 (str "Cannot resolve executable " (executable-key reference)
                      ": " (.getMessage error)))})))

(defn- record-component
  [^CtType owner name]
  (when (instance? CtRecord owner)
    (some (fn [^CtRecordComponent component]
            (when (= name (.getSimpleName component)) component))
          (.getRecordComponents ^CtRecord owner))))

(defn- inherited-field-declaration [^CtType owner name]
  (some (fn [^CtFieldReference candidate]
          (when (= name (.getSimpleName candidate))
            (.getFieldDeclaration candidate)))
        (.getAllFields owner)))

(defn- field-key
  ([^CtFieldReference reference]
   (field-key reference (some-> reference .getDeclaringType type-name)))
  ([^CtFieldReference reference owner-name]
   (str "field:" owner-name
        "#" (.getSimpleName reference))))

(defn declaration-key
  "Returns the stable resolved-symbol identity for a live project declaration.
  This is an index key over Spoon objects, not a reconstructed declaration."
  [^CtElement declaration]
  (cond
    (instance? CtAnonymousExecutable declaration)
    (let [^CtType owner (parent-of-type declaration CtType)
          {:keys [line column]} (source-location declaration)]
      (str "initializer:" (.getQualifiedName owner)
           "#" (if (.hasModifier ^CtModifiable declaration ModifierKind/STATIC)
                  "static" "instance")
           "@" line ":" column))

    (and (instance? CtType declaration)
         (not (instance? CtTypeParameter declaration)))
    (str "type:" (.getQualifiedName ^CtType declaration))

    (instance? CtExecutable declaration)
    (executable-key (.getReference ^CtExecutable declaration))

    (instance? CtField declaration)
    (field-key (.getReference ^CtField declaration))

    (instance? CtRecordComponent declaration)
    (when-let [^CtType owner (parent-of-type declaration CtType)]
      (str "field:" (.getQualifiedName owner)
           "#" (.getSimpleName ^CtRecordComponent declaration)))

    :else nil))

(defn- resolve-field
  [project-types ^CtFieldReference reference]
  (try
    (let [owner-reference (.getDeclaringType reference)
          name (.getSimpleName reference)]
      (cond
        (and (= "length" name)
             (instance? CtArrayTypeReference owner-reference))
        (resolved :field "field:<array>#length" :intrinsic reference nil
                  :array-length)

        (nil? owner-reference)
        {:failure (diagnostic :unresolved-field reference
                              "Field reference has no declaring type")}

        (= "class" name)
        (let [type-result (resolve-type project-types owner-reference)]
          (if-let [failure (:failure type-result)]
            {:failure (assoc failure
                             :kind :unresolved-class-literal
                             :frontend (frontend-diagnostic reference)
                             :location (source-location reference))}
            (resolved :field (field-key reference)
                      (:origin type-result) reference (:declaration type-result)
                      :class-literal)))

        :else
        (if-let [^CtType owner
                 (project-type-declaration project-types owner-reference reference)]
            (if-let [declaration (or (record-component owner name)
                                     (.getFieldDeclaration reference)
                                     (inherited-field-declaration owner name))]
              (let [declaring-owner (or (parent-of-type declaration CtType) owner)]
                (resolved :field (field-key reference (.getQualifiedName ^CtType declaring-owner))
                        :project reference
                        declaration
                        (if (instance? CtRecordComponent declaration)
                          :record-component
                          :source-declaration)))
              {:failure (diagnostic
                         :unresolved-field reference
                         (str "Cannot resolve project field "
                              (field-key reference (.getQualifiedName owner))))})
            (let [^Field field (.getActualField reference)
                  ^Class declaring-class (.getDeclaringClass field)]
              (resolved :field (field-key reference)
                        (class-origin declaring-class) reference field
                        :runtime-member)))))
    (catch Throwable error
      {:failure (diagnostic
                 :unresolved-field reference
                 (str "Cannot resolve field " (field-key reference) ": "
                      (.getMessage error)))})))

(defn- resolve-annotation
  [project-types ^CtAnnotation annotation]
  (let [type-result (resolve-type project-types (.getAnnotationType annotation))]
    (if-let [failure (:failure type-result)]
      {:failure (assoc failure
                       :kind :unresolved-annotation
                       :frontend (frontend-diagnostic annotation)
                       :location (source-location annotation))}
      (resolved :annotation
                (str "annotation:" (subs (:key type-result) (count "type:")))
                (:origin type-result) annotation (:declaration type-result)
                (:resolution type-result)))))

(defn- source-position-failure
  [source-files ^CtElement element]
  (let [location (source-location element)]
    (cond
      (nil? location)
      (diagnostic :missing-source-position element
                  "Semantic frontend object has no source position")

      (not (contains? source-files (:file location)))
      (diagnostic :foreign-source-position element
                  (str "Semantic frontend object points outside the production source set: "
                       (:file location)))

      :else nil)))

(defn- elements-of
  [^CtModel model klass]
  (vec (.getElements model (TypeFilter. klass))))

(defn- child-elements-of
  [^CtElement element klass]
  (vec (.getElements element (TypeFilter. klass))))

(defn- project-type-index
  [^CtModel model source-files]
  (->> (elements-of model CtType)
       (filter (fn [^CtType type]
                 (when-let [location (source-location type)]
                   (contains? source-files (:file location)))))
       (remove #(instance? CtTypeParameter %))
       (map (juxt #(.getQualifiedName ^CtType %) identity))
       (into (sorted-map))))

(defn- compilation-unit-files
  [^Launcher launcher cache]
  (->> (.values (.getMap (.CompilationUnit (.getFactory launcher))))
       (keep #(some->> % .getFile (canonical-file cache)))
       set))

(defn- build-launcher
  [discovery]
  (let [launcher (Launcher.)
        environment (.getEnvironment launcher)]
    (.setNoClasspath environment false)
    (.setIgnoreSyntaxErrors environment false)
    (.setComplianceLevel environment (:java-release discovery))
    (.setPreviewFeaturesEnabled environment (:preview-features discovery))
    (.setSourceClasspath environment
                         (into-array String (map str (:classpath discovery))))
    (doseq [source (:java-sources discovery)]
      (.addInputResource launcher (str source)))
    launcher))

(defn- validate-compilation-units!
  [^Launcher launcher source-files cache]
  (let [actual (compilation-unit-files launcher cache)
        missing (sort (remove actual source-files))
        unexpected (sort (remove source-files actual))]
    (when (or (seq missing) (seq unexpected))
      (throw (ex-info
              "Spoon compilation units do not exactly match the Gradle production source set"
              {:kind :compilation-unit-mismatch
               :missing missing
               :unexpected unexpected})))
    actual))

(defn- validate-references!
  [^CtModel model project-types source-files]
  (let [groups [[CtTypeReference #(resolve-type project-types %) :type]
                [CtExecutableReference #(resolve-executable project-types %) :executable]
                [CtFieldReference #(resolve-field project-types %) :field]
                [CtAnnotation #(resolve-annotation project-types %) :annotation]]
        inputs (mapcat (fn [[klass resolver kind]]
                         (map #(vector kind resolver %) (elements-of model klass)))
                       groups)
        results (concurrency/mapv-ordered
                 :complete-model-reference-resolution
                 (fn [[_kind resolver ^CtElement element]]
                   (if-let [failure (source-position-failure source-files element)]
                     {:failure failure}
                     (resolver element)))
                 inputs)
        failures (vec (keep :failure results))
        occurrences (vec (remove :failure results))]
    (when (seq failures)
      (throw (ex-info
              (str "Spoon semantic resolution failed for " (count failures)
                   " frontend object" (when-not (= 1 (count failures)) "s"))
              {:kind :semantic-resolution-failed
               :failure-count (count failures)
               :failures (vec (take 100 failures))})))
    occurrences))

(def ^:private expansion-rank {:shell 0 :body 1 :public-api 2})

(defn- declaration-kind
  [^CtElement declaration]
  (cond
    (instance? CtType declaration) :type
    (instance? CtRecordComponent declaration) :record-component
    (instance? CtField declaration) :field
    (instance? CtExecutable declaration)
    (if (.isConstructor (.getReference ^CtExecutable declaration))
      :constructor
      :executable)
    :else :unknown))

(defn- project-declaration?
  [source-files ^CtElement declaration]
  (when-let [location (source-location declaration)]
    (contains? source-files (:file location))))

(defn- declaration-index
  [^CtModel model project-types source-files]
  (let [declarations (concat (vals project-types)
                             (elements-of model CtExecutable)
                             (elements-of model CtField)
                             (elements-of model CtRecordComponent))]
    (reduce
     (fn [index ^CtElement declaration]
       (if (and (project-declaration? source-files declaration)
                (not (and (instance? CtField declaration)
                          (when-let [^CtType owner (parent-of-type declaration CtType)]
                            (record-component owner
                                              (.getSimpleName ^CtField declaration))))))
         (if-let [key (declaration-key declaration)]
           (update index key
                   (fn [matches]
                     (let [matches (or matches [])]
                       (if (some #(identical? declaration %) matches)
                         matches
                         (conj matches declaration)))))
           index)
         index))
     (sorted-map)
     declarations)))

(defn- exact-declaration!
  [index key context]
  (let [matches (get index key)]
    (when-not (= 1 (count matches))
      (throw (ex-info
              (str "Expected exactly one live Spoon declaration for " key)
              {:kind (if (seq matches)
                       :ambiguous-project-declaration
                       :missing-project-declaration)
               :context context
               :key key
               :match-count (count matches)
               :locations (mapv source-location matches)})))
    (first matches)))

(defn- public-api-declaration?
  [^CtElement declaration]
  (let [declared-public? (or (and (instance? CtModifiable declaration)
                                  (.hasModifier ^CtModifiable declaration
                                                ModifierKind/PUBLIC))
                             ;; Java enum constants are implicitly public even
                             ;; when Spoon does not materialize a PUBLIC token.
                             (instance? CtEnumValue declaration)
                             (instance? CtRecordComponent declaration))]
    (and declared-public?
         (loop [current declaration]
           (if-let [^CtType owner (parent-of-type current CtType)]
             (and (.hasModifier ^CtModifiable owner ModifierKind/PUBLIC)
                  (recur owner))
             true)))))

(defn- direct-public-members
  [^CtType type]
  (->> (concat (when (instance? CtEnum type)
                 (.getEnumValues ^CtEnum type))
               (.getTypeMembers type))
       (remove #(and (.isImplicit ^CtElement %)
                     (not (instance? CtEnumValue %))))
       (filter public-api-declaration?)
       (sort-by declaration-key)))

(defn- type-shell-element?
  [^CtType root ^CtElement element]
  (loop [current element]
    (cond
      (identical? current root) true
      (not (.isParentInitialized current)) false
      :else
      (let [parent (.getParent current)]
        (cond
          (identical? parent root) true
          (and (instance? CtTypeMember parent)
               (not (instance? CtTypeParameter parent))) false
          :else (recur parent))))))

(defn- closure-elements
  [^CtElement declaration expansion klass]
  (let [elements (child-elements-of declaration klass)]
    (if (and (= :shell expansion) (instance? CtType declaration))
      (filterv #(type-shell-element? declaration %) elements)
      elements)))

(defn- occurrence-sort-key
  [^CtElement element]
  (let [{:keys [file line column]} (source-location element)]
    [(or file "") (or line 0) (or column 0)
     (.getName (class element))
     (str (.getRoleInParent element))]))

(defn- resolve-closure-occurrences!
  [project-types source-files ^CtElement declaration expansion]
  (let [groups [[CtTypeReference #(resolve-type project-types %)]
                [CtExecutableReference #(resolve-executable project-types %)]
                [CtFieldReference #(resolve-field project-types %)]
                [CtAnnotation #(resolve-annotation project-types %)]]
        elements (->> groups
                      (mapcat (fn [[klass resolver]]
                                (map #(vector % resolver)
                                     (closure-elements declaration expansion klass))))
                      (sort-by (comp occurrence-sort-key first)))
        results (concurrency/mapv-ordered
                 :closure-reference-resolution
                 (fn [[^CtElement element resolver]]
                   (if-let [failure (source-position-failure source-files element)]
                     {:failure failure}
                     (resolver element)))
                 elements)
        failures (vec (keep :failure results))]
    (when (seq failures)
      (let [first-failure (first failures)]
        (throw (ex-info
              (str "Spoon semantic resolution failed inside closure declaration "
                   (declaration-key declaration) ": " (:message first-failure)
                   (when-let [file (get-in first-failure [:location :file])]
                     (str " at " file
                          (when-let [line (get-in first-failure [:location :line])]
                            (str ":" line)))))
              {:kind :closure-semantic-resolution-failed
               :declaration-key (declaration-key declaration)
               :failure-count (count failures)
               :failures failures}))))
    (vec (remove :failure results))))

(defn- owner-type-items
  [^CtElement declaration]
  (loop [current declaration result []]
    (if-let [^CtType owner (parent-of-type current CtType)]
      (recur owner (conj result {:key (declaration-key owner)
                                 :declaration owner
                                 :expand :shell}))
      result)))

(defn- dependency-items
  [occurrence]
  (when (= :project (:origin occurrence))
    (let [^CtElement declaration (:declaration occurrence)]
      (when-not (instance? CtElement declaration)
        (throw (ex-info
                "Resolved project occurrence does not retain a live Spoon declaration"
                {:kind :missing-live-project-declaration
                 :occurrence (dissoc occurrence :reference :declaration)})))
      (let [key (declaration-key declaration)]
        (when-not key
          (throw (ex-info
                  "Resolved project occurrence points to an unindexable Spoon declaration"
                  {:kind :unindexable-project-declaration
                   :occurrence (dissoc occurrence :reference :declaration)
                   :declaration (frontend-diagnostic declaration)})))
        (into [{:key key
                :declaration declaration
                :expand (if (instance? CtType declaration) :shell :body)}]
              (owner-type-items declaration))))))

(defn- member-items
  [^CtType declaration expansion]
  (when (= :public-api expansion)
    (mapv (fn [^CtElement member]
            {:key (declaration-key member)
             :declaration member
             :expand (if (instance? CtType member) :public-api :body)})
          (direct-public-members declaration))))

(defn- compilation-obligation-items
  "Selects real frontend bodies required for a selected type to remain a
  concrete implementation in C#. Java permits those bodies to sit outside the
  call graph that first reached the type, but the destination compiler still
  requires every abstract/interface contract. Default interface bodies are
  retained for the same reason."
  [^CtType declaration]
  (->> (.getMethods declaration)
       (remove #(.isImplicit ^CtMethod %))
       (mapcat
        (fn [^CtMethod method]
          (let [all-definitions (.getTopDefinitions method)
                definitions (->> all-definitions
                                 (remove #(.isShadow ^CtType (.getDeclaringType ^CtMethod %))))
                override-annotation?
                (some #(= "java.lang.Override"
                          (some-> ^CtAnnotation % .getAnnotationType .getQualifiedName))
                      (.getAnnotations method))
                own-obligation? (or (nil? (.getBody method))
                                    (and (instance? CtInterface declaration)
                                         (some? (.getBody method)))
                                    override-annotation?
                                    (seq all-definitions))]
            (concat
             (when own-obligation?
               [{:key (declaration-key method)
                 :declaration method
                 :expand :body}])
             ;; Spoon exposes override ancestry through live top-definition
             ;; methods rather than ordinary reference occurrences. Pull
             ;; project-owned definitions into the closure so C# override
             ;; declarations always have the base member they name.
             (map (fn [^CtMethod definition]
                    {:key (declaration-key definition)
                     :declaration definition
                     :expand :body})
                  definitions)))))
       (sort-by :key)
       vec))

(defn- stronger-expansion
  [left right]
  (if (> (expansion-rank right) (expansion-rank left)) right left))

(defn- declaration-entry
  [^CtElement declaration expansion]
  {:key (declaration-key declaration)
   :kind (declaration-kind declaration)
   :expansion expansion
   :location (source-location declaration)
   :declaration declaration})

(defn- add-distinct-occurrences
  [^IdentityHashMap seen occurrences additions]
  (reduce
   (fn [result occurrence]
     (let [reference (:reference occurrence)]
       (if (.containsKey seen reference)
         result
         (do (.put seen reference true)
             (conj result occurrence)))))
   occurrences
   additions))

(defn- source-input-index
  [declarations]
  (reduce
   (fn [index [key {:keys [location]}]]
     (update index (:file location)
             (fn [entry]
               (-> (or entry {:path (:file location) :declarations []})
                   (update :declarations conj key)))))
   (sorted-map)
   declarations))

(defn select-resolved-closure!
  "Selects a dependency-closed set of live Spoon declarations from a complete
  frontend. Seeds are exact stable declaration keys with :shell, :body, or
  :public-api expansion. Project references recursively enqueue their resolved
  declarations; no source-file list or secondary AST participates."
  [^JavaFrontendModel frontend seed-specs]
  (let [{:keys [model project-types source-files]} frontend
        index (declaration-index model project-types source-files)
        seed-specs (->> seed-specs
                        (map #(update % :expand (fnil identity :body)))
                        (sort-by :key)
                        vec)
        _ (when-not (= (count seed-specs) (count (distinct (map :key seed-specs))))
            (throw (ex-info "Closure seed identities must be unique"
                            {:kind :duplicate-closure-seed})))
        _ (doseq [{:keys [key expand]} seed-specs]
            (when-not (contains? expansion-rank expand)
              (throw (ex-info "Invalid closure seed expansion"
                              {:kind :invalid-closure-expansion
                               :key key :expand expand}))))
        seeds (mapv (fn [{:keys [key expand]}]
                      (let [declaration (exact-declaration! index key :closure-seed)]
                        {:key key :expand expand :declaration declaration
                         :location (source-location declaration)}))
                    seed-specs)
        queue (mapv #(select-keys % [:key :expand :declaration]) seeds)
        seen (IdentityHashMap.)]
    (loop [queue queue
           processed (sorted-map)
           declarations (sorted-map)
           occurrences []]
      (if-let [{:keys [key expand declaration]} (first queue)]
        (let [previous (get processed key)
              remaining (subvec queue 1)]
          (if (and previous
                   (>= (expansion-rank previous) (expansion-rank expand)))
            (recur remaining processed declarations occurrences)
            (let [indexed (exact-declaration! index key :closure-dependency)
                  _ (when-not (identical? indexed declaration)
                      (throw (ex-info
                              "Closure dependency changed Spoon declaration identity"
                              {:kind :project-declaration-identity-mismatch :key key})))
                  resolved (resolve-closure-occurrences!
                            project-types source-files declaration
                            (if (= :public-api expand) :shell expand))
                  occurrences (add-distinct-occurrences seen occurrences resolved)
                  dependencies (mapcat dependency-items resolved)
                  members (if (instance? CtType declaration)
                            (member-items declaration expand)
                            nil)
                  obligations (when (instance? CtType declaration)
                                (compilation-obligation-items declaration))
                  owners (owner-type-items declaration)
                  additions (->> (concat dependencies members obligations owners)
                                 (remove #(nil? (:key %)))
                                 (sort-by (juxt :key #(expansion-rank (:expand %))))
                                 vec)
                  prior-entry (get declarations key)
                  strongest (if prior-entry
                              (stronger-expansion (:expansion prior-entry) expand)
                              expand)]
              (recur (into remaining additions)
                     (assoc processed key expand)
                     (assoc declarations key (declaration-entry declaration strongest))
                     occurrences))))
        (let [source-inputs (source-input-index declarations)
              public-api (into (sorted-map)
                               (filter (fn [[_ {:keys [declaration]}]]
                                         (public-api-declaration? declaration)))
                               declarations)
              symbols (reduce (fn [result occurrence]
                                (update result (:key occurrence) (fnil conj []) occurrence))
                              (sorted-map)
                              occurrences)
              kind-counts (frequencies (map :kind occurrences))
              origin-counts (frequencies (map :origin occurrences))
              totals {:seeds (count seeds)
                      :declarations (count declarations)
                      :source-inputs (count source-inputs)
                      :public-api-declarations (count public-api)
                      :type-references (get kind-counts :type 0)
                      :executable-references (get kind-counts :executable 0)
                      :constructor-references (get kind-counts :constructor 0)
                      :field-references (get kind-counts :field 0)
                      :annotations (get kind-counts :annotation 0)
                      :symbols (count symbols)
                      :project-occurrences (get origin-counts :project 0)
                      :jdk-occurrences (get origin-counts :jdk 0)
                      :dependency-occurrences (get origin-counts :dependency 0)
                      :intrinsic-occurrences (get origin-counts :intrinsic 0)
                      :type-parameter-occurrences (get origin-counts :type-parameter 0)
                      :shadow-symbols 0
                      :unresolved-symbols 0
                      :ambiguous-symbols 0
                      :fallback-symbols 0
                      :guessed-symbols 0}]
          (->ResolvedJavaClosure frontend seeds declarations source-inputs
                                 public-api symbols occurrences totals))))))

(defn- compiler-failure-diagnostic
  [cache error]
  (let [message (.getMessage ^Throwable error)
        [_ file line] (when message
                        (re-find #" at (.+):(\d+)$" message))]
    {:kind :frontend-compilation-failed
     :message message
     :location (when file
                 {:file (canonical-file cache (File. file))
                  :line (Integer/parseInt line)})
     :frontend {:frontend-class (.getName (class error))
                :role "model-builder"}}))

(defn build-frontend-model!
  "Builds the complete live Spoon frontend from Gradle-resolved inputs with
  classpath resolution enabled. Semantic acceptance is performed separately so
  callers may validate either the whole project or an exact declaration closure."
  [_workspace-root discovery]
  (let [launcher (build-launcher discovery)
        cache (register-source-cache! launcher (new-canonical-source-cache))
        source-files (set (map #(canonical-file cache (.toFile ^Path %))
                               (:java-sources discovery)))
        model (try
                (.buildModel launcher)
                (catch Throwable error
                  (let [failure (compiler-failure-diagnostic cache error)]
                    (throw (ex-info
                            (str "Spoon could not build the resolved production model: "
                                 (.getMessage error))
                            {:kind :spoon-model-build-failed
                             :java-release (:java-release discovery)
                             :preview-features (:preview-features discovery)
                             :failure failure}
                            error)))))
        environment (.getEnvironment launcher)]
    (when (or (.getNoClasspath environment)
              (pos? (.getErrorCount environment)))
      (throw (ex-info
              "Spoon accepted an invalid classpath-mode model"
              {:kind :invalid-spoon-environment
               :no-classpath (.getNoClasspath environment)
               :error-count (.getErrorCount environment)})))
    (let [compilation-units (validate-compilation-units! launcher source-files cache)
          project-types (project-type-index model source-files)
          totals {:compilation-units (count compilation-units)
                  :project-types (count project-types)}]
      (->JavaFrontendModel launcher model compilation-units project-types
                           source-files totals))))

(defn resolve-complete-model!
  "Fail-closed semantic acceptance for every reference in a frontend model."
  [^JavaFrontendModel frontend]
  (let [{:keys [launcher model compilation-units project-types source-files]}
        frontend
        occurrences (validate-references! model project-types source-files)
          symbols (reduce (fn [index occurrence]
                            (update index (:key occurrence) (fnil conj []) occurrence))
                          (sorted-map)
                          occurrences)
          kind-counts (frequencies (map :kind occurrences))
          origin-counts (frequencies (map :origin occurrences))
          totals {:compilation-units (count compilation-units)
                  :project-types (count project-types)
                  :type-references (get kind-counts :type 0)
                  :executable-references (get kind-counts :executable 0)
                  :constructor-references (get kind-counts :constructor 0)
                  :field-references (get kind-counts :field 0)
                  :annotations (get kind-counts :annotation 0)
                  :symbols (count symbols)
                  :project-occurrences (get origin-counts :project 0)
                  :jdk-occurrences (get origin-counts :jdk 0)
                  :dependency-occurrences (get origin-counts :dependency 0)
                  :intrinsic-occurrences (get origin-counts :intrinsic 0)
                  :type-parameter-occurrences (get origin-counts :type-parameter 0)
                  :shadow-symbols 0
                  :unresolved-symbols 0
                  :ambiguous-symbols 0
                  :fallback-symbols 0}]
    (->ResolvedJavaModel launcher model compilation-units project-types
                         symbols occurrences totals)))

(defn build-resolved-model!
  "Builds and accepts a complete, fail-closed Spoon model from Gradle-resolved
  production inputs. The returned value retains the live frontend objects."
  [workspace-root discovery]
  (resolve-complete-model! (build-frontend-model! workspace-root discovery)))

(defn build-resolved-closure!
  "Builds the complete production frontend and accepts only the exact resolved
  declaration closure reached from seed-specs."
  [workspace-root discovery seed-specs]
  (select-resolved-closure!
   (build-frontend-model! workspace-root discovery)
   seed-specs))

(defn summary-line
  [resolved-model]
  (let [{:keys [compilation-units project-types type-references
                executable-references constructor-references field-references
                annotations symbols shadow-symbols unresolved-symbols
                ambiguous-symbols fallback-symbols]}
        (:totals resolved-model)]
    (if (instance? ResolvedJavaClosure resolved-model)
      (format (str "%d selected declarations in %d source inputs, %d type uses, "
                   "%d calls, %d constructors, %d fields, %d annotations, %d stable symbols; "
                   "shadow=%d unresolved=%d ambiguous=%d fallback=%d")
              (:declarations (:totals resolved-model))
              (:source-inputs (:totals resolved-model))
              type-references executable-references constructor-references
              field-references annotations symbols shadow-symbols unresolved-symbols
              ambiguous-symbols fallback-symbols)
      (format (str "%d units, %d project types, %d type uses, %d calls, "
                 "%d constructors, %d fields, %d annotations, %d stable symbols; "
                 "shadow=%d unresolved=%d ambiguous=%d fallback=%d")
            compilation-units project-types type-references
            executable-references constructor-references field-references
            annotations symbols shadow-symbols unresolved-symbols
            ambiguous-symbols fallback-symbols))))
