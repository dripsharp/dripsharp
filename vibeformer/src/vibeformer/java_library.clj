(ns vibeformer.java-library
  "Fail-closed destination foundation for ordinary Java libraries.

  This bundle intentionally contains no product identities. It accepts the
  simplest structural Java class today and rejects every unimplemented shape
  with its live Spoon identity. Subsequent reusable translation work extends
  these rules; unsupported declarations never become generated stubs."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-project :as project-emission]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Base64]
           [spoon.reflect.declaration CtClass CtConstructor CtElement CtEnum
            CtEnumValue CtField CtMethod CtModifiable CtType ModifierKind]))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-java-library-contract)))))

(defn- unsupported! [message ^CtElement element]
  (throw (ex-info message
                  {:kind :unsupported-destination-rule
                   :source-element element
                   :source-identity (spoon/declaration-key element)
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
         :declarations (atom [])
         :diagnostics (atom [])
         :body-translations (atom [])))

(defn- merge-context! [target source]
  (swap! (:declarations target) into @(:declarations source))
  (swap! (:diagnostics target) into @(:diagnostics source))
  (swap! (:body-translations target) into @(:body-translations source)))

(defn- context-results [ctx]
  {:declarations @(:declarations ctx)
   :diagnostics @(:diagnostics ctx)
   :body-translations @(:body-translations ctx)})

(defn- supported-empty-class? [^CtType type]
  (and (instance? CtClass type)
       (empty? (.getFormalCtTypeParameters type))
       (or (nil? (.getSuperclass ^CtClass type))
           (= "java.lang.Object" (.getQualifiedName (.getSuperclass ^CtClass type))))
       (empty? (.getSuperInterfaces type))
       (empty? (remove #(.isImplicit ^CtElement %) (.getTypeMembers type)))))

(defn- emit-root [ctx ^CtType type]
  (when-not (supported-empty-class? type)
    (unsupported! "Java library declaration shape is not implemented" type))
  (let [qualified (.getQualifiedName type)
        name (identifier (.getSimpleName type))
        id (str "type:" qualified)
        namespace (destination-namespace ctx type)
        assembly (get-in ctx [:configuration :project :assembly-name])
        visibility (if (.hasModifier ^CtModifiable type ModifierKind/PUBLIC)
                     "public" "internal")
        source (source-ref type :java-library.declaration/class
                           {:declaration-id id :declaration-kind :type})]
    (swap! (:declarations ctx)
           conj {:id id :java-key id :kind :type :owner nil :name name
                 :signature qualified
                 :destination {:assembly assembly :namespace namespace
                               :owner (str namespace "." name)
                               :kind "type" :name name :parameter-count "0"}
                 :source (source-ref type :java-library.declaration/class nil)})
    (csharp/with-source
      (csharp/raw (str visibility " sealed class " name " {}"))
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
     (fn [_ _ ^CtElement member]
       (unsupported! "Java library member shape is not implemented" member))
     :merge-context! merge-context!
     :context-results context-results}
    :resolved-mappings
    {:type-node
     (fn [_ ^CtElement reference]
       (unsupported! "Java library resolved type mapping is not implemented" reference))
     :create-body-context (fn [_ _] nil)
     :annotation-decisions (constantly [])}
    :namespace-policy
    {:destination-namespace destination-namespace
     :destination-file-name
     (fn [_ ^CtType type] (str (identifier (.getSimpleName type)) ".cs"))}
    :project-policy project-emission/common-project-policy
    :resource-policy project-emission/common-resource-policy
    :destination-bridges {:assets bridge-assets}}})

(def ^:private surface-headers
  #{"VIBEFORMER_JAVA_LIBRARY_PUBLIC_SURFACE_V1"
    "VIBEFORMER_RAWHTTP_PUBLIC_SURFACE_V1"})

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
      (when-not (contains? surface-headers header)
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
