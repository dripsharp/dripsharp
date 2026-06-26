(ns vibeformer.ingest.kotlin-psi
  (:require [clojure.string :as str]
            [datomic.client.api :as d])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.security MessageDigest)
           (org.jetbrains.kotlin.com.intellij.openapi.util Disposer)
           (org.jetbrains.kotlin.cli.jvm.compiler EnvironmentConfigFiles KotlinCoreEnvironment)
           (org.jetbrains.kotlin.config CompilerConfiguration)
           (org.jetbrains.kotlin.psi KtBinaryExpression KtCallExpression KtClass KtFile
                                     KtNamedFunction KtNullableType KtObjectDeclaration
                                     KtProperty KtPsiFactory KtSafeQualifiedExpression
                                     KtTypeReference)))

(def ^:private lang :lang/kotlin)

(defn- hex-bytes [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256 [value]
  (let [bytes (.getBytes (str value) StandardCharsets/UTF_8)]
    (str "sha256:" (hex-bytes (.digest (doto (MessageDigest/getInstance "SHA-256")
                                         (.update bytes)))))))

(defn- path [value]
  (if (instance? java.nio.file.Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- file-records [db project-id]
  (mapv (fn [[file]]
          (d/pull db [:file/id :file/path :file/hash :file/package
                      {:file/project [:project/id :project/root]}]
                  file))
        (d/q '[:find ?file
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?file :file/lang :lang/kotlin]]
             db project-id)))

(defn- parse-source [filename source]
  (let [disposable (Disposer/newDisposable)
        environment (KotlinCoreEnvironment/createForProduction
                     disposable
                     (CompilerConfiguration.)
                     EnvironmentConfigFiles/JVM_CONFIG_FILES)
        psi-factory (KtPsiFactory. (.getProject environment) false)]
    {:disposable disposable
     :file (.createFile psi-factory filename source)}))

(defn- walk-elements [root]
  (letfn [(walk [element]
            (lazy-seq
             (cons element (mapcat walk (.getChildren element)))))]
    (walk root)))

(defn- collect-elements [root clazz]
  (filter #(instance? clazz %) (walk-elements root)))

(defn- offset->line-column [source offset]
  (let [offset (max 0 (min offset (count source)))
        lines-before-offset (str/split (subs source 0 offset) #"\n" -1)]
    {:line (count lines-before-offset)
     :column (inc (count (last lines-before-offset)))}))

(defn- source-span [source element]
  (let [range (.getTextRange element)
        start (offset->line-column source (.getStartOffset range))
        end (offset->line-column source (.getEndOffset range))]
    {:node/start-line (:line start)
     :node/start-column (:column start)
     :node/end-line (:line end)
     :node/end-column (:column end)}))

(defn- node-fact [source id kind name file-id ordinal element & {:keys [parent]}]
  (cond-> {:db/id id
           :node/id id
           :node/lang lang
           :node/kind kind
           :node/name name
           :node/file [:file/id file-id]
           :node/ordinal ordinal
           :node/source-hash (sha256 (.getText element))}
    parent (assoc :node/parent parent)
    true (merge (source-span source element))))

(defn- feature-fact [id kind node-id status severity]
  {:db/id id
   :feature/id id
   :feature/lang lang
   :feature/kind kind
   :feature/node node-id
   :feature/status status
   :feature/severity severity})

(defn- supported-feature [id kind node-id]
  (feature-fact id kind node-id :feature.status/supported :feature.severity/info))

(defn- unsupported-feature [id kind node-id severity]
  (feature-fact id kind node-id :feature.status/unsupported severity))

(defn- package-name [^KtFile file]
  (let [package (.asString (.getPackageFqName file))]
    (when-not (str/blank? package)
      package)))

(defn- declaration-owner [package owner-qname]
  (or owner-qname package))

(defn- qualify-name [package owner-qname name]
  (let [owner (declaration-owner package owner-qname)]
    (if (str/blank? owner)
      name
      (str owner "." name))))

(defn- declaration-name [declaration]
  (or (.getName declaration) "<anonymous>"))

(defn- type-syntax [^KtTypeReference type-reference]
  (some-> type-reference .getText str/trim))

(defn- nullable-type? [^KtTypeReference type-reference]
  (boolean
   (when type-reference
     (or (instance? KtNullableType (.getTypeElement type-reference))
         (str/ends-with? (type-syntax type-reference) "?")))))

(defn- type-name [syntax]
  (some-> syntax
          (str/replace #"\s+" "")
          (str/replace #"\?$" "")))

(defn- simple-type-name [type-name]
  (some-> type-name
          (str/replace #"<.*$" "")
          (str/split #"\.")
          last))

(defn- type-id [syntax nullable?]
  (when-let [name (type-name syntax)]
    (str "kotlin:" name (when nullable? "?"))))

(defn- type-fact [^KtTypeReference type-reference]
  (when-let [syntax (type-syntax type-reference)]
    (let [nullable? (nullable-type? type-reference)
          id (type-id syntax nullable?)]
      {:db/id id
       :type/id id
       :type/lang lang
       :type/name (type-name syntax)
       :type/nullable? nullable?})))

(defn- type-ref-facts
  ([node-id role ^KtTypeReference type-reference]
   (type-ref-facts node-id role type-reference nil))
  ([node-id role ^KtTypeReference type-reference source-name]
   (when-let [type-fact (type-fact type-reference)]
     [type-fact
      (cond-> {:db/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
               :ref/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
               :ref/kind :ref.kind/type-use
               :ref/from-node node-id
               :ref/to-type (:type/id type-fact)
               :ref/name (:type/name type-fact)
               :ref/role role
               :ref/resolved? false
               :ref/reason :resolve.reason/syntax-only}
        source-name (assoc :ref/source-name source-name))])))

(defn- nullable-type-feature-facts [node-id type-reference role]
  (when (nullable-type? type-reference)
    [(supported-feature (str node-id ":feature:nullable-type:" (name role))
                        :kotlin.feature/nullable-type
                        node-id)]))

(defn- source-type-fact [type-id name]
  {:db/id type-id
   :type/id type-id
   :type/lang lang
   :type/name name
   :type/nullable? false})

(defn- modifier-keywords [declaration]
  (when-let [modifier-list (.getModifierList declaration)]
    (->> (str/split (.getText modifier-list) #"\s+")
         (remove str/blank?)
         (map #(-> % (str/replace "_" "-") keyword))
         set)))

(defn- object-kind [^KtObjectDeclaration object]
  (if (.isCompanion object)
    :kotlin.node/companion-object
    :kotlin.node/object))

(defn- object-feature-kind [^KtObjectDeclaration object]
  (if (.isCompanion object)
    :kotlin.feature/companion-object
    :kotlin.feature/object))

(defn- declaration-node-kind [declaration]
  (cond
    (instance? KtObjectDeclaration declaration) (object-kind declaration)
    (instance? KtClass declaration) :kotlin.node/class
    (instance? KtNamedFunction declaration) :kotlin.node/function
    (instance? KtProperty declaration) :kotlin.node/property
    :else :kotlin.node/declaration))

(defn- declaration-kind [declaration]
  (cond
    (instance? KtObjectDeclaration declaration) (if (.isCompanion ^KtObjectDeclaration declaration)
                                                  :decl.kind/companion-object
                                                  :decl.kind/object)
    (instance? KtClass declaration) :decl.kind/class
    (instance? KtNamedFunction declaration) :decl.kind/function
    (instance? KtProperty declaration) :decl.kind/property
    :else :decl.kind/declaration))

(defn- declaration-feature-kind [declaration]
  (cond
    (instance? KtObjectDeclaration declaration) (object-feature-kind declaration)
    (instance? KtClass declaration) :kotlin.feature/class
    (instance? KtNamedFunction declaration) :kotlin.feature/function
    (instance? KtProperty declaration) :kotlin.feature/property
    :else :kotlin.feature/declaration))

(defn- declaration-node-id [file-id kind qualified-name]
  (str file-id ":" (name kind) ":" qualified-name))

(defn- declaration-id [kind qualified-name]
  (str "kotlin:" (name kind) ":" qualified-name))

(defn- value-parameter-type-refs [node-id value-parameters]
  (mapcat (fn [ordinal parameter]
            (type-ref-facts node-id
                            (keyword (str "param-" ordinal))
                            (.getTypeReference parameter)
                            (.getName parameter)))
          (range)
          value-parameters))

(defn- value-parameter-nullable-features [node-id value-parameters]
  (mapcat (fn [ordinal parameter]
            (nullable-type-feature-facts node-id
                                         (.getTypeReference parameter)
                                         (keyword (str "param-" ordinal))))
          (range)
          value-parameters))

(defn- declaration-type-facts [node-id declaration]
  (cond
    (instance? KtProperty declaration)
    (let [type-reference (.getTypeReference ^KtProperty declaration)]
      (concat (type-ref-facts node-id :property-type type-reference)
              (nullable-type-feature-facts node-id type-reference :property-type)))

    (instance? KtNamedFunction declaration)
    (let [function ^KtNamedFunction declaration
          return-type (.getTypeReference function)
          value-parameters (.getValueParameters function)]
      (concat (type-ref-facts node-id :return-type return-type)
              (value-parameter-type-refs node-id value-parameters)
              (nullable-type-feature-facts node-id return-type :return-type)
              (value-parameter-nullable-features node-id value-parameters)))))

(defn- declaration-fact [decl-id decl-kind name qualified-name node-id declaration]
  (let [function-return-type (when (instance? KtNamedFunction declaration)
                               (some-> ^KtNamedFunction declaration .getTypeReference type-fact :type/id))
        property-type (when (instance? KtProperty declaration)
                        (some-> ^KtProperty declaration .getTypeReference type-fact :type/id))]
    (cond-> {:db/id decl-id
             :decl/id decl-id
             :decl/lang lang
             :decl/kind decl-kind
             :decl/name name
             :decl/qualified-name qualified-name
             :decl/source-node node-id}
      (seq (modifier-keywords declaration))
      (assoc :decl/modifiers (modifier-keywords declaration))

      function-return-type
      (assoc :decl/return-type function-return-type)

      property-type
      (assoc :decl/type property-type))))

(defn- top-level-feature [node-id]
  (supported-feature (str node-id ":feature:top-level-declaration")
                     :kotlin.feature/top-level-declaration
                     node-id))

(declare declaration-facts)

(defn- call-name [^KtCallExpression call]
  (some-> call .getCalleeExpression .getText))

(defn- call-node-id [file-id parent-node-id ordinal name]
  (str file-id ":call:" parent-node-id ":" ordinal ":" name))

(defn- call-facts [source file-id parent-node-id ordinal ^KtCallExpression call]
  (let [name (or (call-name call) "<call>")
        node-id (call-node-id file-id parent-node-id ordinal name)]
    [(node-fact source node-id :kotlin.node/call-expression name file-id ordinal call
                :parent parent-node-id)
     {:db/id (str node-id ":ref")
      :ref/id (str node-id ":ref")
      :ref/kind :ref.kind/function-call
      :ref/from-node node-id
      :ref/name name
      :ref/resolved? false
      :ref/reason :resolve.reason/syntax-only}
     (supported-feature (str node-id ":feature:call-expression")
                        :kotlin.feature/call-expression
                        node-id)]))

(defn- safe-call-node-id [file-id parent-node-id ordinal]
  (str file-id ":safe-call:" parent-node-id ":" ordinal))

(defn- safe-call-facts [source file-id parent-node-id ordinal ^KtSafeQualifiedExpression expression]
  (let [node-id (safe-call-node-id file-id parent-node-id ordinal)]
    [(node-fact source node-id :kotlin.node/safe-call "?." file-id ordinal expression
                :parent parent-node-id)
     (supported-feature (str node-id ":feature:safe-call")
                        :kotlin.feature/safe-call
                        node-id)]))

(defn- elvis-expression? [^KtBinaryExpression expression]
  (= "?:" (some-> expression .getOperationReference .getText)))

(defn- elvis-node-id [file-id parent-node-id ordinal]
  (str file-id ":elvis:" parent-node-id ":" ordinal))

(defn- elvis-facts [source file-id parent-node-id ordinal ^KtBinaryExpression expression]
  (let [node-id (elvis-node-id file-id parent-node-id ordinal)]
    [(node-fact source node-id :kotlin.node/elvis-expression "?:" file-id ordinal expression
                :parent parent-node-id)
     (supported-feature (str node-id ":feature:elvis-expression")
                        :kotlin.feature/elvis-expression
                        node-id)]))

(defn- expression-facts [source file-id parent-node-id declaration]
  (concat
   (mapcat (fn [ordinal call]
             (call-facts source file-id parent-node-id ordinal call))
           (range)
           (collect-elements declaration KtCallExpression))
   (mapcat (fn [ordinal safe-call]
             (safe-call-facts source file-id parent-node-id ordinal safe-call))
           (range)
           (collect-elements declaration KtSafeQualifiedExpression))
   (mapcat (fn [ordinal elvis]
             (elvis-facts source file-id parent-node-id ordinal elvis))
           (range)
           (filter elvis-expression? (collect-elements declaration KtBinaryExpression)))))

(defn- child-declarations [declaration]
  (cond
    (or (instance? KtClass declaration)
        (instance? KtObjectDeclaration declaration))
    (.getDeclarations declaration)
    :else []))

(defn- function-signature [^KtNamedFunction function]
  (str (.getName function) "("
       (str/join "," (map #(or (type-syntax (.getTypeReference %)) "_")
                          (.getValueParameters function)))
       ")"))

(defn- declaration-qualified-name [package owner-qname declaration]
  (let [name (declaration-name declaration)]
    (cond
      (instance? KtNamedFunction declaration)
      (qualify-name package owner-qname (function-signature declaration))

      (instance? KtObjectDeclaration declaration)
      (if (.isCompanion ^KtObjectDeclaration declaration)
        (qualify-name package owner-qname (str (or name "Companion")))
        (qualify-name package owner-qname name))

      :else
      (qualify-name package owner-qname name))))

(defn- declaration-facts [source file-id package owner-qname parent-node-id ordinal declaration]
  (let [node-kind (declaration-node-kind declaration)
        decl-kind (declaration-kind declaration)
        name (declaration-name declaration)
        qualified-name (declaration-qualified-name package owner-qname declaration)
        node-id (declaration-node-id file-id node-kind qualified-name)
        decl-id (declaration-id decl-kind qualified-name)
        nested-owner-qname (when (or (instance? KtClass declaration)
                                     (instance? KtObjectDeclaration declaration))
                             qualified-name)]
    (concat
     (when (or (instance? KtClass declaration)
               (instance? KtObjectDeclaration declaration))
       [(source-type-fact (str "kotlin:" qualified-name) qualified-name)])
     [(node-fact source node-id node-kind name file-id ordinal declaration
                 :parent parent-node-id)
      (declaration-fact decl-id decl-kind name qualified-name node-id declaration)
      (supported-feature (str node-id ":feature:" (clojure.core/name (declaration-feature-kind declaration)))
                         (declaration-feature-kind declaration)
                         node-id)]
     (when-not parent-node-id
       [(top-level-feature node-id)])
     (declaration-type-facts node-id declaration)
     (when (or (instance? KtNamedFunction declaration)
               (instance? KtProperty declaration))
       (expression-facts source file-id node-id declaration))
     (mapcat (fn [child-ordinal child]
               (declaration-facts source file-id package nested-owner-qname node-id child-ordinal child))
             (range)
             (child-declarations declaration)))))

(defn- package-facts [source file-id package ^KtFile file]
  (when (and package (.getPackageDirective file))
    (let [node-id (str file-id ":package:" package)]
      [(node-fact source node-id :kotlin.node/package package file-id 0 (.getPackageDirective file))
       (supported-feature (str node-id ":feature:package")
                          :kotlin.feature/package
                          node-id)])))

(defn- file-facts [file-record]
  (let [file-id (:file/id file-record)
        project-root (get-in file-record [:file/project :project/root])
        source-path (.resolve (path project-root) (:file/path file-record))
        source (Files/readString source-path StandardCharsets/UTF_8)
        {:keys [disposable file]} (parse-source (str (.getFileName source-path)) source)]
    (try
      (let [package (package-name file)]
        (doall
         (concat
          (package-facts source file-id package file)
          (mapcat (fn [ordinal declaration]
                    (declaration-facts source file-id package nil nil ordinal declaration))
                  (range)
                  (.getDeclarations file)))))
      (finally
        (Disposer/dispose disposable)))))

(defn- unique-key [fact]
  (some (fn [attr] (when-let [value (get fact attr)] [attr value]))
        [:node/id :decl/id :type/id :ref/id :feature/id]))

(defn- dedupe-facts [facts]
  (->> facts
       (filter map?)
       (reduce (fn [acc fact]
                 (if-let [key (unique-key fact)]
                   (if (contains? (:seen acc) key)
                     acc
                     (-> acc
                         (update :seen conj key)
                         (update :facts conj fact)))
                   (update acc :facts conj fact)))
               {:seen #{} :facts []})
       :facts))

(defn extract-project-facts
  "Read Kotlin file records for project-id from db and return normalized Kotlin PSI facts.

  Kotlin file records must already exist, usually from vibeformer.ingest.source."
  [db project-id]
  (dedupe-facts (mapcat file-facts (file-records db project-id))))

(def ^:private kotlin-stdlib-types
  {"Any" "kotlin:Any"
   "Boolean" "kotlin:Boolean"
   "Byte" "kotlin:Byte"
   "Char" "kotlin:Char"
   "Double" "kotlin:Double"
   "Float" "kotlin:Float"
   "Int" "kotlin:Int"
   "Long" "kotlin:Long"
   "Nothing" "kotlin:Nothing"
   "Short" "kotlin:Short"
   "String" "kotlin:String"
   "Unit" "kotlin:Unit"})

(def ^:private absent :vibeformer.query/absent)

(defn- nil-if-absent [value]
  (when-not (= absent value)
    value))

(defn- type-identity [db value]
  (when-let [value (nil-if-absent value)]
    (if (string? value)
      value
      (:type/id (d/pull db [:type/id] value)))))

(defn- project-declarations [db project-id]
  (mapv (fn [[decl-id kind name qualified-name decl-type return-type]]
          {:decl/id decl-id
           :decl/kind kind
           :decl/name name
           :decl/qualified-name qualified-name
           :decl/type (type-identity db decl-type)
           :decl/return-type (type-identity db return-type)})
        (d/q '[:find ?decl-id ?kind ?name ?qualified-name ?decl-type ?return-type
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?node :node/file ?file]
               [?decl :decl/source-node ?node]
               [?decl :decl/id ?decl-id]
               [?decl :decl/kind ?kind]
               [?decl :decl/name ?name]
               [?decl :decl/qualified-name ?qualified-name]
               [(get-else $ ?decl :decl/type :vibeformer.query/absent) ?decl-type]
               [(get-else $ ?decl :decl/return-type :vibeformer.query/absent) ?return-type]]
             db project-id)))

(defn- project-refs [db project-id]
  (mapv (fn [[ref-id kind name to-type node-id]]
          {:ref/id ref-id
           :ref/kind kind
           :ref/name name
           :ref/to-type (type-identity db to-type)
           :node/id node-id})
        (d/q '[:find ?ref-id ?kind ?name ?to-type ?node-id
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?node :node/file ?file]
               [?node :node/id ?node-id]
               [?ref :ref/from-node ?node]
               [?ref :ref/id ?ref-id]
               [?ref :ref/kind ?kind]
               [?ref :ref/name ?name]
               [(get-else $ ?ref :ref/to-type :vibeformer.query/absent) ?to-type]]
             db project-id)))

(defn- normalize-classpath-types [classpath-types]
  (cond
    (map? classpath-types)
    (into {}
          (map (fn [[name type-id]]
                 [(str name) (str type-id)]))
          classpath-types)

    (sequential? classpath-types)
    (into {}
          (map (fn [name]
                 [(str name) (str "kotlin:" name)]))
          classpath-types)

    (set? classpath-types)
    (normalize-classpath-types (seq classpath-types))

    :else {}))

(defn- source-type-index [decls]
  (let [decl-types (keep :decl/type decls)
        source-decl-types (keep (fn [{:decl/keys [kind qualified-name]}]
                                  (when (contains? #{:decl.kind/class
                                                     :decl.kind/object
                                                     :decl.kind/companion-object}
                                                   kind)
                                    (str "kotlin:" qualified-name)))
                                decls)]
    (->> (concat decl-types source-decl-types)
         (map (fn [type-id]
                (let [type-name (str/replace type-id #"^kotlin:" "")]
                  [(simple-type-name type-name) type-id])))
         (into {}))))

(defn- function-index [decls]
  (->> decls
       (filter #(= :decl.kind/function (:decl/kind %)))
       (group-by :decl/name)))

(defn- owner-type-id [source-types {:decl/keys [qualified-name]}]
  (some (fn [[_ type-id]]
          (let [qname (str/replace type-id #"^kotlin:" "")]
            (when (str/starts-with? qualified-name (str qname "."))
              type-id)))
        (sort-by (comp count val) > source-types)))

(defn- current-ref-reason [db ref-id]
  (ffirst (d/q '[:find ?reason
                 :in $ ?ref-id
                 :where
                 [?ref :ref/id ?ref-id]
                 [?ref :ref/reason ?reason]]
               db ref-id)))

(defn- resolved-ref-tx [db ref-id attrs]
  (let [reason (current-ref-reason db ref-id)]
    (cond-> [(assoc attrs
                    :db/id [:ref/id ref-id]
                    :ref/resolved? true)]
      reason (conj [:db/retract [:ref/id ref-id] :ref/reason reason]))))

(defn- unresolved-ref-tx [ref-id reason]
  [{:db/id [:ref/id ref-id]
    :ref/resolved? false
    :ref/reason reason}])

(defn- type-resolution-tx [db type-index {:ref/keys [id name to-type]}]
  (let [resolved-type-id (or (get type-index name)
                             (get type-index (simple-type-name name))
                             to-type)]
    (if (or (get type-index name)
            (get type-index (simple-type-name name)))
      (resolved-ref-tx db id {:ref/to-type [:type/id resolved-type-id]})
      (unresolved-ref-tx id :resolve.reason/missing-classpath))))

(defn- function-resolution-tx [db functions source-types {:ref/keys [id name]}]
  (let [candidates (get functions name)]
    (case (count candidates)
      0 (unresolved-ref-tx id :resolve.reason/missing-classpath)
      1 (let [decl (first candidates)
              owner-type (owner-type-id source-types decl)]
          (resolved-ref-tx db id
                           (cond-> {:ref/to-decl [:decl/id (:decl/id decl)]}
                             (:decl/return-type decl)
                             (assoc :ref/to-type [:type/id (:decl/return-type decl)])

                             owner-type
                             (assoc :ref/owner-type [:type/id owner-type]))))
      (unresolved-ref-tx id :resolve.reason/analysis-api-limitation))))

(defn semantic-resolution-facts
  "Return idempotent tx-data that enriches Kotlin refs with semantic links.

  This is the conservative fallback used until a full Kotlin Analysis API
  session can be created for the source module. It resolves project-local
  functions and known classpath/source types, and records deliberate reasons
  for everything it cannot prove."
  [db project-id opts]
  (let [decls (project-declarations db project-id)
        source-types (source-type-index decls)
        type-index (merge kotlin-stdlib-types
                          (normalize-classpath-types (:kotlin/classpath-types opts))
                          source-types)
        functions (function-index decls)]
    (vec
     (mapcat (fn [ref]
               (case (:ref/kind ref)
                 :ref.kind/type-use
                 (type-resolution-tx db type-index ref)

                 :ref.kind/function-call
                 (function-resolution-tx db functions source-types ref)

                 []))
             (project-refs db project-id)))))

(defn enrich!
  "Enrich existing Kotlin PSI facts with conservative semantic resolution.

  Options:
  - :project/id project identity to enrich
  - :kotlin/classpath-types collection or map of known type names available on
    the analysis classpath. Collection entries resolve to existing syntax type
    facts such as kotlin:Locale; map values may provide explicit type ids."
  [conn {:project/keys [id] :as opts}]
  (let [db (d/db conn)
        tx-data (semantic-resolution-facts db id opts)]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    {:project/id id
     :semantic-refs (count (filter map? tx-data))
     :semantic-tx (count tx-data)}))

(defn ingest!
  "Extract normalized Kotlin facts from ingested Kotlin files and transact them."
  [conn {:project/keys [id]}]
  (let [db (d/db conn)
        files (file-records db id)
        facts (extract-project-facts db id)]
    (when (seq facts)
      (d/transact conn {:tx-data facts}))
    {:project/id id
     :kotlin-files (count files)
     :transacted-facts (count facts)}))
