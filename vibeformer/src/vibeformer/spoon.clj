(ns vibeformer.spoon
  "Builds and validates the live Spoon model consumed by Java translation.

  This namespace deliberately retains Spoon objects.  The occurrence and symbol
  indexes are navigation aids over the frontend model, not a serialized semantic
  fact model or a replacement AST."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.lang.reflect Constructor Field]
           [java.nio.file Path]
           [spoon Launcher]
           [spoon.reflect CtModel]
           [spoon.reflect.code CtThisAccess CtTypeAccess]
           [spoon.reflect.cu SourcePosition]
           [spoon.reflect.declaration CtAnnotation CtClass CtElement CtExecutable
            CtRecord CtRecordComponent CtType CtTypeParameter]
           [spoon.reflect.reference CtArrayTypeReference CtExecutableReference
            CtFieldReference CtIntersectionTypeReference CtTypeParameterReference
            CtTypeReference CtWildcardReference]
           [spoon.reflect.visitor.filter TypeFilter]))

(defrecord ResolvedJavaModel
  [^Launcher launcher
   ^CtModel model
   compilation-units
   project-types
   symbols
   occurrences
   totals])

(defn- canonical-file
  ^String [^File file]
  (.getCanonicalPath file))

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
  (when-let [position (effective-position element)]
    {:file (canonical-file (.getFile position))
     :line (.getLine position)
     :column (.getColumn position)}))

(defn frontend-identity
  "Identifies the exact frontend object used by a resolution diagnostic."
  [^CtElement element]
  {:frontend-class (.getName (class element))
   :role (when (.isParentInitialized element)
           (str (.getRoleInParent element)))
   :rendered (try
               (str element)
               (catch Throwable _ "<frontend rendering failed>"))})

(defn- diagnostic
  [kind ^CtElement element message]
  {:kind kind
   :message message
   :location (source-location element)
   :frontend (frontend-identity element)})

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
      (when-let [^CtExecutableReference owner
                 (parent-of-type reference CtExecutableReference)]
        (str "type-parameter:" (executable-owner-key owner)
             "#" (.getSimpleName reference))))))

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

(defn- resolve-executable
  [project-types ^CtExecutableReference reference]
  (try
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
            (resolved (if (.isConstructor reference) :constructor :executable)
                      (executable-key reference) :project reference declaration
                      :source-declaration)
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
                              (executable-key reference)))})))
          (let [member (if (.isConstructor reference)
                         (.getActualConstructor reference)
                         (.getActualMethod reference))
                ^Class declaring-class (.getDeclaringClass member)]
            (resolved (if (instance? Constructor member) :constructor :executable)
                      (executable-key reference) (class-origin declaring-class)
                      reference member :runtime-member))))
      {:failure (diagnostic :unresolved-executable reference
                            "Executable reference has no declaring type")})
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

(defn- field-key
  ([^CtFieldReference reference]
   (field-key reference (some-> reference .getDeclaringType type-name)))
  ([^CtFieldReference reference owner-name]
   (str "field:" owner-name
        "#" (.getSimpleName reference))))

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

        :else
        (if-let [^CtType owner
                 (project-type-declaration project-types owner-reference reference)]
            (if-let [declaration (or (record-component owner name)
                                     (.getFieldDeclaration reference))]
              (resolved :field (field-key reference (.getQualifiedName owner))
                        :project reference
                        declaration
                        (if (instance? CtRecordComponent declaration)
                          :record-component
                          :source-declaration))
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
                       :frontend (frontend-identity annotation)
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
  [^Launcher launcher]
  (->> (.values (.getMap (.CompilationUnit (.getFactory launcher))))
       (keep #(some-> % .getFile canonical-file))
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
  [^Launcher launcher source-files]
  (let [actual (compilation-unit-files launcher)
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
        results (mapcat
                 (fn [[klass resolver _]]
                   (map (fn [^CtElement element]
                          (if-let [failure (source-position-failure source-files element)]
                            {:failure failure}
                            (resolver element)))
                        (elements-of model klass)))
                 groups)
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

(defn- compiler-failure-diagnostic
  [error]
  (let [message (.getMessage ^Throwable error)
        [_ file line] (when message
                        (re-find #" at (.+):(\d+)$" message))]
    {:kind :frontend-compilation-failed
     :message message
     :location (when file
                 {:file (canonical-file (File. file))
                  :line (Integer/parseInt line)})
     :frontend {:frontend-class (.getName (class error))
                :role "model-builder"}}))

(defn build-resolved-model!
  "Builds a complete, fail-closed Spoon model from Gradle-resolved production
  inputs.  The returned value owns the live Launcher, CtModel, declarations,
  references, and resolved-symbol occurrence index for direct translation."
  [_workspace-root discovery]
  (let [source-files (set (map #(canonical-file (.toFile ^Path %))
                               (:java-sources discovery)))
        launcher (build-launcher discovery)
        model (try
                (.buildModel launcher)
                (catch Throwable error
                  (let [failure (compiler-failure-diagnostic error)]
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
    (let [compilation-units (validate-compilation-units! launcher source-files)
          project-types (project-type-index model source-files)
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
                           symbols occurrences totals))))

(defn summary-line
  [^ResolvedJavaModel resolved-model]
  (let [{:keys [compilation-units project-types type-references
                executable-references constructor-references field-references
                annotations symbols shadow-symbols unresolved-symbols
                ambiguous-symbols fallback-symbols]}
        (:totals resolved-model)]
    (format (str "%d units, %d project types, %d type uses, %d calls, "
                 "%d constructors, %d fields, %d annotations, %d stable symbols; "
                 "shadow=%d unresolved=%d ambiguous=%d fallback=%d")
            compilation-units project-types type-references
            executable-references constructor-references field-references
            annotations symbols shadow-symbols unresolved-symbols
            ambiguous-symbols fallback-symbols)))
