(ns vibeformer.emit.csharp
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [vibeformer.transform.type-mapping :as type-mapping])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path Paths)))

(defn- path [value]
  (if (instance? Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- slash-path [value]
  (str/replace (str value) \\ \/))

(defn- normalize-path [value]
  (.normalize (.toAbsolutePath (path value))))

(defn- ensure-dir! [^Path dir]
  (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
  dir)

(defn- clear-directory! [^Path dir]
  (ensure-dir! dir)
  (let [paths (with-open [stream (Files/walk dir (make-array java.nio.file.FileVisitOption 0))]
                (doall (iterator-seq (.iterator stream))))]
    (doseq [entry (sort-by #(count (str %)) > paths)]
      (when-not (= dir entry)
        (Files/deleteIfExists entry)))))

(defn- write-string! [^Path file content]
  (ensure-dir! (.getParent file))
  (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
  file)

(defn- modifiers [decl]
  (set (:decl/modifiers decl)))

(defn- visibility [mods]
  (cond
    (contains? mods :public) "public"
    (contains? mods :private) "private"
    (contains? mods :protected) "protected"
    :else "internal"))

(defn- simple-name [type-name]
  (some-> type-name
          (str/replace #"\[\]$" "")
          (str/replace #"<.*$" "")
          (str/split #"\.")
          last))

(defn- fallback-type [source-type]
  (let [type-name (:type/name source-type)
        array? (str/ends-with? (or type-name "") "[]")
        base-name (or (simple-name type-name) "object")]
    {:csharp/type (str base-name (when array? "[]"))
     :csharp/usings []
     :csharp/helpers []}))

(defn- map-type [source-type]
  (try
    (type-mapping/map-type source-type)
    (catch clojure.lang.ExceptionInfo _
      (fallback-type source-type))))

(defn- type-name [source-type]
  (:csharp/type (map-type source-type)))

(defn- type-params [decl]
  (->> (:decl/type-params decl)
       (sort-by #(or (:type-param/ordinal %) 0))
       (mapv :type-param/name)))

(defn- type-param-suffix [decl]
  (when-let [params (seq (type-params decl))]
    (str "<" (str/join ", " params) ">")))

(defn- csharp-namespace [class-decl]
  (let [file-package (get-in class-decl [:decl/source-node :node/file :file/package])
        qualified-name (:decl/qualified-name class-decl)]
    (or file-package
        (when (str/includes? qualified-name ".")
          (str/join "." (butlast (str/split qualified-name #"\.")))))))

(defn- class-output-path [^Path target-dir class-decl]
  (let [namespace (csharp-namespace class-decl)
        dir (reduce (fn [^Path current segment] (.resolve current segment))
                    target-dir
                    (if (str/blank? namespace)
                      []
                      (str/split namespace #"\.")))]
    (.resolve dir (str (:decl/name class-decl) ".cs"))))

(defn- type-decls [db]
  (->> (d/q '[:find (pull ?decl [:db/id
                                  :decl/id
                                  :decl/kind
                                  :decl/name
                                  :decl/qualified-name
                                  :decl/modifiers
                                  {:decl/type-params [:type-param/id
                                                      :type-param/ordinal
                                                      :type-param/name]}
                                  {:decl/source-node [:db/id
                                                      :node/id
                                                      :node/kind
                                                      :node/name
                                                      :node/ordinal
                                                      {:node/file [:file/id
                                                                   :file/path
                                                                   :file/package]}]}])
              :where
              [?decl :decl/lang :lang/java]
              [?decl :decl/kind ?kind]
              [(contains? #{:decl.kind/class :decl.kind/interface :decl.kind/enum} ?kind)]]
            db)
       (map first)
       (sort-by :decl/qualified-name)
       vec))

(defn- member-decls [db class-decl]
  (let [parent-eid (get-in class-decl [:decl/source-node :db/id])]
    (->> (d/q '[:find (pull ?decl [:db/id
                                    :decl/id
                                    :decl/kind
                                    :decl/name
                                    :decl/qualified-name
                                    :decl/modifiers
                                    {:decl/type [:type/id
                                                 :type/lang
                                                 :type/name
                                                 :type/nullable?
                                                 {:type/args [:type.arg/ordinal
                                                              {:type.arg/type [:type/id
                                                                               :type/lang
                                                                               :type/name
                                                                               :type/nullable?]}]}]}
                                    {:decl/return-type [:type/id
                                                        :type/lang
                                                        :type/name
                                                        :type/nullable?
                                                        {:type/args [:type.arg/ordinal
                                                                     {:type.arg/type [:type/id
                                                                                      :type/lang
                                                                                      :type/name
                                                                                      :type/nullable?]}]}]}
                                    {:decl/type-params [:type-param/id
                                                        :type-param/ordinal
                                                        :type-param/name]}
                                    {:decl/source-node [:db/id
                                                        :node/id
                                                        :node/kind
                                                        :node/name
                                                        :node/ordinal
                                                        :node/start-line
                                                        :node/start-column
                                                        :node/end-line
                                                        :node/end-column
                                                        {:node/file [:file/id
                                                                     :file/path
                                                                     :file/package]}]}])
                :in $ ?parent
                :where
                [?node :node/parent ?parent]
                [?decl :decl/source-node ?node]
                [?decl :decl/lang :lang/java]]
              db
              parent-eid)
         (map first)
         vec)))

(defn- member-groups [db class-decl]
  (group-by :decl/kind (member-decls db class-decl)))

(defn- type-ref-params [db executable-decl]
  (let [node-eid (get-in executable-decl [:decl/source-node :db/id])]
    (->> (d/q '[:find (pull ?ref [:ref/role
                                   :ref/source-name
                                   {:ref/to-type [:type/id
                                                  :type/lang
                                                  :type/name
                                                  :type/nullable?
                                                  {:type/args [:type.arg/ordinal
                                                               {:type.arg/type [:type/id
                                                                                :type/lang
                                                                                :type/name
                                                                                :type/nullable?]}]}]}])
                :in $ ?node
                :where
                [?ref :ref/from-node ?node]
                [?ref :ref/kind :ref.kind/type-use]
                [?ref :ref/role]]
              db
              node-eid)
         (map first)
         (keep (fn [{:ref/keys [role source-name to-type]}]
                 (when (str/starts-with? (name role) "param-")
                   {:role role
                    :source-name source-name
                    :source-type to-type})))
         (sort-by (comp parse-long second #(re-matches #"param-(\d+)" (name (:role %)))))
         vec)))

(defn- param-name [index param]
  (let [source-name (:source-name param)]
    (if (str/blank? source-name)
      (str "arg" index)
      source-name)))

(defn- param-list [db executable-decl]
  (->> (type-ref-params db executable-decl)
       (map-indexed (fn [index param]
                      (str (type-name (:source-type param))
                           " "
                           (param-name index param))))
       (str/join ", ")))

(defn- node-pull-pattern []
  [:db/id
   :node/id
   :node/lang
   :node/kind
   :node/name
   :node/role
   :node/value
   :node/ordinal
   :node/start-line
   :node/start-column
   :node/end-line
   :node/end-column
   {:node/file [:file/id :file/path :file/lang]}])

(defn- child-nodes
  ([db parent-eid]
   (->> (d/q '[:find (pull ?node pattern)
               :in $ ?parent pattern
               :where [?node :node/parent ?parent]]
             db
             parent-eid
             (node-pull-pattern))
        (map first)
        (sort-by (juxt #(or (:node/ordinal %) 0) :node/id))
        vec))
  ([db parent-eid role]
   (->> (child-nodes db parent-eid)
        (filter #(= role (:node/role %)))
        vec)))

(defn- child-node [db parent-eid role]
  (first (child-nodes db parent-eid role)))

(defn- node-type-ref [db node role]
  (ffirst
   (d/q '[:find (pull ?type [:type/id
                             :type/lang
                             :type/name
                             :type/nullable?
                             {:type/args [:type.arg/ordinal
                                          {:type.arg/type [:type/id
                                                           :type/lang
                                                           :type/name
                                                           :type/nullable?]}]}])
          :in $ ?node ?role
          :where
          [?ref :ref/from-node ?node]
          [?ref :ref/kind :ref.kind/type-use]
          [?ref :ref/role ?role]
          [?ref :ref/to-type ?type]]
        db
        (:db/id node)
        role)))

(defn- type-pull-pattern []
  [:type/id
   :type/lang
   :type/name
   :type/nullable?
   {:type/args [:type.arg/ordinal
                {:type.arg/type [:type/id
                                 :type/lang
                                 :type/name
                                 :type/nullable?]}]}])

(defn- parent-node [db node]
  (ffirst
   (d/q '[:find (pull ?parent pattern)
          :in $ ?node pattern
          :where
          [?node :node/parent ?parent]]
        db
        (:db/id node)
        (node-pull-pattern))))

(defn- enclosing-executable-node [db node]
  (loop [current node]
    (when-let [parent (parent-node db current)]
      (if (contains? #{:java.node/method :java.node/constructor} (:node/kind parent))
        parent
        (recur parent)))))

(defn- method-call-ref [db node]
  (ffirst
   (d/q '[:find (pull ?ref [:ref/id
                            :ref/name
                            :ref/resolved?
                            :ref/reason
                            {:ref/to-type [:type/id :type/lang :type/name :type/nullable?]}
                            {:ref/owner-type [:type/id :type/lang :type/name :type/nullable?]}
                            {:ref/to-decl [:decl/id
                                           :decl/name
                                           :decl/qualified-name
                                           {:decl/source-node [:db/id :node/id :node/kind :node/name]}]}])
          :in $ ?node
          :where
          [?ref :ref/from-node ?node]
          [?ref :ref/kind :ref.kind/method-call]]
        db
        (:db/id node))))

(defn- constructor-call-ref [db node]
  (ffirst
   (d/q '[:find (pull ?ref [:ref/id
                            :ref/name
                            :ref/resolved?
                            :ref/reason
                            {:ref/to-type [:type/id
                                           :type/lang
                                           :type/name
                                           :type/nullable?
                                           {:type/args [:type.arg/ordinal
                                                        {:type.arg/type [:type/id
                                                                         :type/lang
                                                                         :type/name
                                                                         :type/nullable?]}]}]}
                            {:ref/to-decl [:decl/id
                                           :decl/name
                                           :decl/qualified-name
                                           {:decl/source-node [:db/id :node/id :node/kind :node/name]}]}])
          :in $ ?node
          :where
          [?ref :ref/from-node ?node]
          [?ref :ref/kind :ref.kind/constructor-call]]
        db
        (:db/id node))))

(defn- variable-read-type [db node]
  (when-let [name (:node/name node)]
    (when-let [executable (enclosing-executable-node db node)]
      (ffirst
       (d/q '[:find (pull ?type pattern)
              :in $ ?executable ?name pattern
              :where
              [?ref :ref/from-node ?executable]
              [?ref :ref/kind :ref.kind/type-use]
              [?ref :ref/source-name ?name]
              [?ref :ref/to-type ?type]]
            db
            (:db/id executable)
            name
            (type-pull-pattern))))))

(defn- expression-type [ctx node]
  (let [db (:db ctx)]
    (case (:node/kind node)
      :java.node/method-call
      (get-in (method-call-ref db node) [:ref/to-type])

      :java.node/object-creation
      (get-in (constructor-call-ref db node) [:ref/to-type])

      :java.node/variable-read
      (variable-read-type db node)

      :java.node/local-variable
      (node-type-ref db node :local-type)

      nil)))

(defn- array-type? [source-type]
  (str/ends-with? (or (:type/name source-type) "") "[]"))

(defn- project-local-call? [call-ref]
  (boolean (get-in call-ref [:ref/to-decl :decl/source-node :node/id])))

(def supported-java-exception-constructors
  #{"java.lang.Exception"
    "java.lang.RuntimeException"
    "java.lang.IllegalArgumentException"
    "java.lang.IllegalStateException"
    "java.io.IOException"})

(defn- supported-java-exception-constructor? [source-type]
  (contains? supported-java-exception-constructors (:type/name source-type)))

(defn- source-context [node]
  {:source/node-id (:node/id node)
   :source/node-kind (:node/kind node)
   :source/file (get-in node [:node/file :file/path])
   :source/span {:start-line (:node/start-line node)
                 :start-column (:node/start-column node)
                 :end-line (:node/end-line node)
                 :end-column (:node/end-column node)}})

(defn- rule-app [node rule status]
  {:rule-app/rule [:rule/id rule]
   :rule-app/source-node [:node/id (:node/id node)]
   :rule-app/status status})

(defn- provenance-entry [node rule status text]
  (cond-> {:emit/source-node [:node/id (:node/id node)]
           :emit/rule [:rule/id rule]
           :emit/status status}
    (some? text) (assoc :emit/start-offset 0
                        :emit/end-offset (count text))))

(defn- apply-rule [result node rule status]
  (-> result
      (update :rule-applications conj (rule-app node rule status))
      (update :provenance conj (provenance-entry node rule status (:text result)))))

(defn- shift-provenance [offset provenance]
  (mapv (fn [entry]
          (cond-> entry
            (:emit/start-offset entry) (update :emit/start-offset + offset)
            (:emit/end-offset entry) (update :emit/end-offset + offset)))
        provenance))

(defn- emitted
  ([text node rule]
   (emitted text node rule {}))
  ([text node rule {:keys [usings helpers diagnostics rule-applications]}]
   (apply-rule {:text text
                :usings (set usings)
                :helpers (set helpers)
                :diagnostics (vec diagnostics)
                :rule-applications (vec rule-applications)
                :provenance []}
               node
               rule
               :rule-app.status/success)))

(defn- unsupported-placeholder [node context]
  (let [message (str "Unsupported Java node " (name (:node/kind node)))]
    (if (= :statement (:context context))
      (str "throw new System.NotImplementedException(\"" message "\");")
      (str "default! /* " message " */"))))

(defn- unsupported [node rule context]
  (let [text (unsupported-placeholder node context)]
    (apply-rule {:text text
                 :usings #{}
                 :helpers #{}
                 :diagnostics [(assoc (source-context node)
                                      :diagnostic/severity :diagnostic.severity/error
                                      :diagnostic/message (str "Unsupported Java node " (:node/kind node))
                                      :rule/id rule
                                      :rule/context context)]
                 :rule-applications []
                 :provenance []}
                node
                rule
                :rule-app.status/failed)))

(defn- merge-emits
  ([parts]
   (merge-emits "" parts))
  ([separator parts]
   (let [parts (vec (remove nil? parts))]
     (loop [remaining parts
            offset 0
            text []
            usings #{}
            helpers #{}
            diagnostics []
            rule-applications []
            provenance []]
       (if-let [part (first remaining)]
         (let [part-text (or (:text part) "")
               separator-text (when (seq text) separator)
               prefix-length (count (or separator-text ""))
               part-offset (+ offset prefix-length)]
           (recur (next remaining)
                  (+ part-offset (count part-text))
                  (cond-> text
                    separator-text (conj separator-text)
                    true (conj part-text))
                  (into usings (:usings part))
                  (into helpers (:helpers part))
                  (into diagnostics (:diagnostics part))
                  (into rule-applications (:rule-applications part))
                  (into provenance (shift-provenance part-offset (:provenance part)))))
         {:text (apply str text)
          :usings usings
          :helpers helpers
          :diagnostics (vec diagnostics)
          :rule-applications (vec rule-applications)
          :provenance (vec provenance)})))))

(defn- with-text [result text]
  (let [old-text (:text result)
        offset (when (and (seq old-text)
                          (str/includes? text old-text))
                 (str/index-of text old-text))
        provenance (if (some? offset)
                     (shift-provenance offset (:provenance result))
                     (mapv #(dissoc % :emit/start-offset :emit/end-offset)
                           (:provenance result)))]
    (assoc result
           :text text
           :provenance provenance)))

(defn- offset->line-column [text offset]
  (let [bounded-offset (max 0 (min offset (count text)))]
    (loop [index 0
           line 1
           column 1]
      (if (= index bounded-offset)
        {:line line :column column}
        (let [ch (.charAt text index)]
          (if (= \newline ch)
            (recur (inc index) (inc line) 1)
            (recur (inc index) line (inc column))))))))

(defn- dest-span [text start-offset end-offset]
  (let [start (offset->line-column text start-offset)
        end (offset->line-column text end-offset)]
    {:start-line (:line start)
     :start-column (:column start)
     :end-line (:line end)
     :end-column (:column end)}))

(defn- source-span-summary [node]
  {:start-line (:node/start-line node)
   :start-column (:node/start-column node)
   :end-line (:node/end-line node)
   :end-column (:node/end-column node)})

(defn- source-node-summary [node]
  {:source/node-id (:node/id node)
   :source/lang (:node/lang node)
   :source/kind (:node/kind node)
   :source/name (:node/name node)
   :source/file (get-in node [:node/file :file/path])
   :source/span (source-span-summary node)})

(defn- declaration-summary [decl]
  (select-keys decl [:decl/id :decl/kind :decl/name :decl/qualified-name]))

(defn- feature-summary [feature]
  (select-keys feature [:feature/id :feature/lang :feature/kind :feature/status :feature/severity]))

(defn- rule-summary [rule-id rule]
  {:rule/id rule-id
   :rule/version (long (or (:rule/version rule) 1))
   :rule/status (:rule/status rule)
   :rule/source-lang (:rule/source-lang rule)
   :rule/input-kind (:rule/input-kind rule)
   :rule/input-feature (:rule/input-feature rule)
   :rule/output-feature (:rule/output-feature rule)})

(defn- source-node-index [db]
  (->> (d/q '[:find (pull ?node [:node/id
                                  :node/lang
                                  :node/kind
                                  :node/name
                                  :node/start-line
                                  :node/start-column
                                  :node/end-line
                                  :node/end-column
                                  {:node/file [:file/id :file/path :file/lang :file/package]}])
              :where
              [?node :node/id]]
            db)
       (map first)
       (map (juxt :node/id identity))
       (into {})))

(defn- declarations-by-node [db]
  (->> (d/q '[:find ?node-id (pull ?decl [:decl/id
                                           :decl/kind
                                           :decl/name
                                           :decl/qualified-name])
              :where
              [?decl :decl/source-node ?node]
              [?node :node/id ?node-id]]
            db)
       (reduce (fn [acc [node-id decl]]
                 (update acc node-id (fnil conj []) (declaration-summary decl)))
               {})
       (map (fn [[node-id decls]]
              [node-id (vec (sort-by (juxt :decl/kind :decl/qualified-name :decl/id) decls))]))
       (into {})))

(defn- features-by-node [db]
  (->> (d/q '[:find ?node-id (pull ?feature [:feature/id
                                              :feature/lang
                                              :feature/kind
                                              :feature/status
                                              :feature/severity])
              :where
              [?feature :feature/node ?node]
              [?node :node/id ?node-id]]
            db)
       (reduce (fn [acc [node-id feature]]
                 (update acc node-id (fnil conj []) (feature-summary feature)))
               {})
       (map (fn [[node-id features]]
              [node-id (vec (sort-by (juxt :feature/kind :feature/id) features))]))
       (into {})))

(defn- rule-index [db]
  (->> (d/q '[:find (pull ?rule [:rule/id
                                  :rule/source-lang
                                  :rule/input-kind
                                  :rule/input-feature
                                  :rule/output-feature
                                  :rule/status
                                  :rule/version])
              :where
              [?rule :rule/id]]
            db)
       (map first)
       (map (juxt :rule/id identity))
       (into {})))

(defn- provenance-indexes [db]
  {:nodes (source-node-index db)
   :declarations (declarations-by-node db)
   :features (features-by-node db)
   :rules (rule-index db)})

(defn- finalize-provenance [indexes dest-file text provenance]
  (->> provenance
       (map-indexed
        (fn [index entry]
          (let [source-node-id (second (:emit/source-node entry))
                rule-id (second (:emit/rule entry))
                source-node (get-in indexes [:nodes source-node-id])
                rule (get-in indexes [:rules rule-id])
                span (when (and (:emit/start-offset entry)
                                (:emit/end-offset entry))
                       (dest-span text (:emit/start-offset entry) (:emit/end-offset entry)))]
            (cond-> (-> entry
                        (dissoc :emit/start-offset :emit/end-offset)
                        (assoc :emit/id (str dest-file "#" (format "%04d" (inc index)))
                               :emit/dest-file dest-file
                               :rule (rule-summary rule-id rule)))
              span (assoc :emit/dest-span span)
              source-node (merge (source-node-summary source-node))
              (seq (get-in indexes [:declarations source-node-id]))
              (assoc :source/declarations (get-in indexes [:declarations source-node-id]))
              (seq (get-in indexes [:features source-node-id]))
              (assoc :source/features (get-in indexes [:features source-node-id]))))))
       (sort-by (juxt :emit/dest-file
                      #(get-in % [:emit/dest-span :start-line] Long/MAX_VALUE)
                      #(get-in % [:emit/dest-span :start-column] Long/MAX_VALUE)
                      #(get-in % [:rule :rule/id])
                      :source/node-id
                      :emit/id))
       vec))

(defn- csharp-string [value]
  (pr-str value))

(defn- literal-text [node]
  (let [value (edn/read-string (:node/value node))]
    (cond
      (nil? value) "null"
      (string? value) (csharp-string value)
      (char? value) (str "'" value "'")
      :else (str value))))

(defn- csharp-type-access [source-name]
  (case source-name
    "java.lang.System" "System"
    "java.nio.file.Files" "System.IO.File"
    "java.nio.file.Path" "System.IO.Path"
    "java.util.regex.Pattern" "Regex"
    (or (simple-name source-name) source-name)))

(defn- binary-operator [operator-name]
  (case operator-name
    "eq" "=="
    "ne" "!="
    "lt" "<"
    "le" "<="
    "gt" ">"
    "ge" ">="
    "and" "&&"
    "or" "||"
    "instanceof" "is"
    "plus" "+"
    "minus" "-"
    "mul" "*"
    "div" "/"
    "mod" "%"
    nil))

(declare emit-expression emit-statement method-name)

(defn- emit-child-expression [ctx node role]
  (if-let [child (child-node (:db ctx) (:db/id node) role)]
    (emit-expression ctx child)
    (unsupported node :java.expression/missing-child {:missing-role role})))

(defn- emit-arguments [ctx node]
  (merge-emits ", " (mapv #(emit-expression ctx %)
                          (child-nodes (:db ctx) (:db/id node) :argument))))

(defn- emit-method-call [ctx node]
  (let [db (:db ctx)
        call-ref (method-call-ref db node)
        owner (get-in call-ref [:ref/owner-type :type/name])
        source-method-name (or (:ref/name call-ref) (:node/name node))
        target (child-node db (:db/id node) :target)
        target-result (when target (emit-expression ctx target))
        args-result (emit-arguments ctx node)
        args (:text args-result)
        combined (merge-emits [target-result args-result])
        target-text (:text target-result)]
    (cond
      (and (= "java.util.regex.Pattern" owner) (= "compile" source-method-name))
      (-> combined
          (with-text (str "new Regex(" args ")"))
          (update :usings conj "System.Text.RegularExpressions")
          (apply-rule node :java.regex-pattern-compile/to-csharp-regex :rule-app.status/success))

      (and (= "java.lang.String" owner) (= "trim" source-method-name) target)
      (-> combined
          (with-text (str target-text ".Trim()"))
          (apply-rule node :java.string-trim/to-csharp-trim :rule-app.status/success))

      (and (= "java.lang.String" owner) (= "isEmpty" source-method-name) target)
      (-> combined
          (with-text (str "string.IsNullOrEmpty(" target-text ")"))
          (apply-rule node :java.string-is-empty/to-csharp-is-null-or-empty :rule-app.status/success))

      (and (= "java.util.regex.Pattern" owner) (= "split" source-method-name) target)
      (-> combined
          (with-text (str target-text ".Split(" args ")"))
          (apply-rule node :java.regex-split/to-csharp-regex-split :rule-app.status/success))

      (and (= "java.io.PrintStream" owner) (= "println" source-method-name))
      (-> combined
          (with-text (if (= "System.Console.Error" target-text)
                       (str "System.Console.Error.WriteLine(" args ")")
                       (str "System.Console.WriteLine(" args ")")))
          (apply-rule node :java.printstream-println/to-csharp-console :rule-app.status/success))

      (and (= "java.lang.System" owner) (= "exit" source-method-name))
      (-> combined
          (with-text (str "System.Environment.Exit(" args ")"))
          (apply-rule node :java.system-exit/to-csharp-environment-exit :rule-app.status/success))

      (and (= "java.nio.file.Path" owner) (= "of" source-method-name) (= 1 (count (child-nodes db (:db/id node) :argument))))
      (-> args-result
          (apply-rule node :java.path-of/to-csharp-string-path :rule-app.status/success))

      (and (= "java.nio.file.Files" owner) (= "readString" source-method-name))
      (-> args-result
          (with-text (str "System.IO.File.ReadAllText(" args ")"))
          (apply-rule node :java.files-read-string/to-csharp-file-read-all-text :rule-app.status/success))

      (and (= "java.lang.Integer" owner) (= "toString" source-method-name) (= 1 (count (child-nodes db (:db/id node) :argument))))
      (-> args-result
          (with-text (str "System.Convert.ToString(" args ")"))
          (apply-rule node :java.integer-to-string/to-csharp-convert-to-string :rule-app.status/success))

      (and (= "java.lang.Integer" owner) (= "toString" source-method-name))
      (unsupported node
                   :java.integer-to-string/to-csharp-convert-to-string
                   {:method source-method-name
                    :owner owner
                    :arity (count (child-nodes db (:db/id node) :argument))
                    :reason :emit.reason/unsupported-overload})

      (not (:ref/resolved? call-ref))
      (unsupported node
                   :java.method-call-node/to-csharp-invocation
                   {:method source-method-name
                    :owner owner
                    :reason (or (:ref/reason call-ref)
                                :resolve.reason/missing-method-call-ref)})

      (not (project-local-call? call-ref))
      (unsupported node
                   :java.method-call-node/to-csharp-invocation
                   {:method source-method-name
                    :owner owner
                    :reason :emit.reason/unsupported-external-method})

      :else
      (let [call-text (str (when target (str target-text "."))
                           (method-name {:decl/name source-method-name})
                           "(" args ")")]
        (-> combined
            (with-text call-text)
            (apply-rule node :java.method-call-node/to-csharp-invocation :rule-app.status/success))))))

(defn- emit-object-creation [ctx node]
  (let [db (:db ctx)
        call-ref (constructor-call-ref db node)
        args-result (emit-arguments ctx node)
        args (:text args-result)
        source-type (:ref/to-type call-ref)
        mapped-type (when source-type (map-type source-type))
        target-type (:csharp/type mapped-type)]
    (cond
      (not (:ref/resolved? call-ref))
      (unsupported node
                   :java.object-creation-node/to-csharp-new
                   {:type (:ref/name call-ref)
                    :reason (or (:ref/reason call-ref)
                                :resolve.reason/missing-constructor-call-ref)})

      (and (not (project-local-call? call-ref))
           (not (supported-java-exception-constructor? source-type)))
      (unsupported node
                   :java.object-creation-node/to-csharp-new
                   {:type (:ref/name call-ref)
                    :reason :emit.reason/unsupported-external-constructor})

      :else
      (-> args-result
          (update :usings into (:csharp/usings mapped-type))
          (with-text (str "new " target-type "(" args ")"))
          (apply-rule node :java.object-creation-node/to-csharp-new :rule-app.status/success)))))

(defn- emit-field-read [ctx node]
  (let [target (child-node (:db ctx) (:db/id node) :target)
        target-result (when target (emit-expression ctx target))
        target-text (:text target-result)
        field-name (:node/name node)
        system-target? (and (= :java.node/type-access (:node/kind target))
                            (= "java.lang.System" (:node/value target)))
        target-type (when target (expression-type ctx target))
        text (cond
               (and system-target? (= "err" field-name)) "System.Console.Error"
               (and system-target? (= "out" field-name)) "System.Console"
               (and target (= "length" field-name) (array-type? target-type)) (str target-text ".Length")
               (and target (= "length" field-name)) nil
               target (str target-text "." field-name)
               :else field-name)]
    (if text
      (-> (or target-result (merge-emits []))
          (with-text text)
          (apply-rule node :java.field-read-node/to-csharp-member :rule-app.status/success))
      (unsupported node
                   :java.field-read-node/to-csharp-member
                   {:field field-name
                    :target-type (:type/name target-type)
                    :reason :emit.reason/unsupported-length-target}))))

(defn- emit-field-write [ctx node]
  (let [target (child-node (:db ctx) (:db/id node) :target)
        target-result (when target (emit-expression ctx target))
        target-text (:text target-result)
        field-name (:node/name node)
        text (if target
               (str target-text "." field-name)
               field-name)]
    (-> (or target-result (merge-emits []))
        (with-text text)
        (apply-rule node :java.field-write-node/to-csharp-member :rule-app.status/success))))

(defn- emit-assignment-expression [ctx node]
  (let [left (emit-child-expression ctx node :left)
        right (emit-child-expression ctx node :right)]
    (-> (merge-emits [left right])
        (with-text (str (:text left) " = " (:text right)))
        (apply-rule node :java.assignment-node/to-csharp-assignment :rule-app.status/success))))

(defn- emit-type-pattern [ctx node]
  (if-let [source-type (node-type-ref (:db ctx) node :pattern-type)]
    (emitted (str (type-name source-type) " " (:node/name node))
             node
             :java.type-pattern-node/to-csharp-pattern)
    (unsupported node
                 :java.type-pattern-node/to-csharp-pattern
                 {:reason :emit.reason/missing-pattern-type})))

(defn- emit-expression [ctx node]
  (case (:node/kind node)
    :java.node/literal
    (emitted (literal-text node) node :java.literal-node/to-csharp-literal)

    :java.node/variable-read
    (emitted (:node/name node) node :java.variable-read-node/to-csharp-variable)

    :java.node/variable-write
    (emitted (:node/name node) node :java.variable-write-node/to-csharp-variable)

    :java.node/this
    (emitted "this" node :java.this-node/to-csharp-this)

    :java.node/type-access
    (emitted (csharp-type-access (:node/value node)) node :java.type-access-node/to-csharp-type)

    :java.node/type-pattern
    (emit-type-pattern ctx node)

    :java.node/field-read
    (emit-field-read ctx node)

    :java.node/field-write
    (emit-field-write ctx node)

    :java.node/assignment
    (emit-assignment-expression ctx node)

    :java.node/array-read
    (let [target (emit-child-expression ctx node :target)
          index (emit-child-expression ctx node :index)]
      (-> (merge-emits [target index])
          (with-text (str (:text target) "[" (:text index) "]"))
          (apply-rule node :java.array-read-node/to-csharp-indexer :rule-app.status/success)))

    :java.node/binary-operator
    (let [left (emit-child-expression ctx node :left)
          right (emit-child-expression ctx node :right)
          op (binary-operator (:node/value node))]
      (if op
        (-> (merge-emits [left right])
            (with-text (str (:text left) " " op " " (:text right)))
            (apply-rule node :java.binary-operator-node/to-csharp-binary :rule-app.status/success))
        (unsupported node :java.binary-operator-node/to-csharp-binary {:operator (:node/value node)})))

    :java.node/switch-expression
    (unsupported node
                 :java.switch-expression-node/to-csharp-switch
                 {:reason :emit.reason/unsupported-switch-expression})

    :java.node/method-call
    (emit-method-call ctx node)

    :java.node/object-creation
    (emit-object-creation ctx node)

    (unsupported node :java.expression-node/to-csharp-stub {:context :expression})))

(defn- indent [level]
  (apply str (repeat level "    ")))

(defn- indent-lines [level text]
  (let [prefix (indent level)]
    (->> (str/split-lines text)
         (map #(str prefix %))
         (str/join "\n"))))

(defn- emit-statement [ctx node indent-level]
  (case (:node/kind node)
    :java.node/local-variable
    (let [source-type (node-type-ref (:db ctx) node :local-type)
          initializer (emit-child-expression ctx node :initializer)
          text (str (type-name source-type) " " (:node/name node) " = " (:text initializer) ";")]
      (-> initializer
          (with-text (indent-lines indent-level text))
          (apply-rule node :java.local-variable-node/to-csharp-local :rule-app.status/success)))

    :java.node/return-statement
    (let [expr (emit-child-expression ctx node :return-expression)]
      (-> expr
          (with-text (indent-lines indent-level (str "return " (:text expr) ";")))
          (apply-rule node :java.return-statement-node/to-csharp-return :rule-app.status/success)))

    :java.node/if-statement
    (let [condition (emit-child-expression ctx node :condition)
          then-statements (mapv #(emit-statement ctx % (inc indent-level))
                                (child-nodes (:db ctx) (:db/id node) :then))
          else-statements (mapv #(emit-statement ctx % (inc indent-level))
                                (child-nodes (:db ctx) (:db/id node) :else))
          then-result (merge-emits "\n" then-statements)
          else-result (merge-emits "\n" else-statements)
          text (str (indent-lines indent-level (str "if (" (:text condition) ")")) "\n"
                    (indent-lines indent-level "{") "\n"
                    (:text then-result) "\n"
                    (indent-lines indent-level "}")
                    (when (seq else-statements)
                      (str "\n" (indent-lines indent-level "else") "\n"
                           (indent-lines indent-level "{") "\n"
                           (:text else-result) "\n"
                           (indent-lines indent-level "}"))))]
      (-> (merge-emits [condition then-result else-result])
          (with-text text)
          (apply-rule node :java.if-statement-node/to-csharp-if :rule-app.status/success)))

    :java.node/throw-statement
    (let [expr (emit-child-expression ctx node :thrown-expression)]
      (-> expr
          (with-text (indent-lines indent-level (str "throw " (:text expr) ";")))
          (apply-rule node :java.throw-statement-node/to-csharp-throw :rule-app.status/success)))

    :java.node/method-call
    (let [expr (emit-expression ctx node)]
      (-> expr
          (with-text (indent-lines indent-level (str (:text expr) ";")))))

    :java.node/assignment
    (let [expr (emit-assignment-expression ctx node)]
      (-> expr
          (with-text (indent-lines indent-level (str (:text expr) ";")))))

    (let [result (unsupported node :java.statement-node/to-csharp-stub {:context :statement})]
      (with-text result (indent-lines indent-level (:text result))))))

(defn- emit-body [db executable-decl indent-level]
  (let [statements (child-nodes db (get-in executable-decl [:decl/source-node :db/id]) :body)]
    (if (seq statements)
      (merge-emits "\n" (mapv #(emit-statement {:db db} % indent-level) statements))
      {:text (indent-lines indent-level "throw new System.NotImplementedException();")
       :usings #{}
       :helpers #{}
       :diagnostics []
       :rule-applications []
       :provenance []})))

(defn- inherited-types [db type-decl]
  (let [node-eid (get-in type-decl [:decl/source-node :db/id])]
    (->> (d/q '[:find ?kind (pull ?type pattern)
                :in $ ?node pattern
                :where
                [?ref :ref/from-node ?node]
                [?ref :ref/kind ?kind]
                [?ref :ref/to-type ?type]
                [(contains? #{:ref.kind/extends :ref.kind/implements} ?kind)]]
              db
              node-eid
              (type-pull-pattern))
         (sort-by (fn [[kind source-type]]
                    [(if (= :ref.kind/extends kind) 0 1) (:type/id source-type)]))
         (mapv second))))

(defn- base-list [db type-decl]
  (when-let [bases (seq (map type-name (inherited-types db type-decl)))]
    (str " : " (str/join ", " bases))))

(defn- type-line [db type-decl]
  (let [mods (modifiers type-decl)]
    (if (= :decl.kind/interface (:decl/kind type-decl))
      (str "    " (visibility mods) " interface " (:decl/name type-decl)
           (type-param-suffix type-decl)
           (base-list db type-decl)
           "\n")
      (str "    "
           (str/join " " (cond-> [(visibility mods)]
                           (contains? mods :final) (conj "sealed")
                           true (conj "class")
                           true (conj (str (:decl/name type-decl)
                                           (type-param-suffix type-decl)))))
           (base-list db type-decl)
           "\n"))))

(defn- field-line [db field-decl]
  (let [mods (modifiers field-decl)]
    (when (:decl/type field-decl)
      (let [initializer-node (child-node db (get-in field-decl [:decl/source-node :db/id]) :initializer)
            initializer (when initializer-node (emit-expression {:db db} initializer-node))
            field-modifiers (cond-> [(visibility mods)]
                              (contains? mods :static) (conj "static")
                              (contains? mods :final) (conj "readonly"))
            text (str "        "
                      (str/join " " (conj field-modifiers
                                          (type-name (:decl/type field-decl))
                                          (:decl/name field-decl)))
                      (when initializer (str " = " (:text initializer)))
                      ";\n")]
        (-> (or initializer (merge-emits []))
            (with-text text)
            (apply-rule (:decl/source-node field-decl)
                        :java.field-node/to-csharp-field
                        :rule-app.status/success))))))

(defn- method-name [method-decl]
  (if (= "main" (:decl/name method-decl))
    "Main"
    (:decl/name method-decl)))

(defn- signature-modifiers [decl]
  (let [mods (modifiers decl)]
    (cond-> [(visibility mods)]
      (contains? mods :static) (conj "static"))))

(defn- method-signature [db method-decl interface?]
  (let [return-type (type-name (:decl/return-type method-decl))
        prefix (if interface?
                 []
                 (signature-modifiers method-decl))]
    (str/join " " (concat prefix
                          [return-type
                           (str (method-name method-decl)
                                (type-param-suffix method-decl)
                                "(" (param-list db method-decl) ")")]))))

(defn- constructor-block [db class-name ctor-decl]
  (let [body (emit-body db ctor-decl 3)]
    (-> body
        (with-text (str "        "
                        (str/join " " (conj (signature-modifiers ctor-decl) class-name))
                        "(" (param-list db ctor-decl) ")\n"
                        "        {\n"
                        (:text body) "\n"
                        "        }\n"))
        (apply-rule (:decl/source-node ctor-decl)
                    :java.constructor-node/to-csharp-constructor
                    :rule-app.status/success))))

(defn- method-block [db method-decl interface?]
  (if interface?
    (let [body-statements (child-nodes db (get-in method-decl [:decl/source-node :db/id]) :body)
          signature (method-signature db method-decl true)]
      (if (seq body-statements)
        (let [body (emit-body db method-decl 3)]
          (-> body
              (with-text (str "        "
                              signature
                              "\n"
                              "        {\n"
                              (:text body) "\n"
                              "        }\n"))
              (apply-rule (:decl/source-node method-decl)
                          :java.method-node/to-csharp-method
                          :rule-app.status/success)))
        (emitted (str "        " signature ";\n")
                 (:decl/source-node method-decl)
                 :java.method-node/to-csharp-method)))
    (let [body (emit-body db method-decl 3)]
      (-> body
          (with-text (str "        "
                          (method-signature db method-decl false)
                          "\n"
                          "        {\n"
                          (:text body) "\n"
                          "        }\n"))
          (apply-rule (:decl/source-node method-decl)
                      :java.method-node/to-csharp-method
                      :rule-app.status/success)))))

(defn- emitted-field-decls [db members]
  (->> (get members :decl.kind/field)
       (sort-by (juxt #(get-in % [:decl/source-node :node/ordinal]) :decl/name))
       (keep #(field-line db %))
       vec))

(defn- emitted-ctor-decls [db class-name members]
  (->> (get members :decl.kind/constructor)
       (sort-by (juxt #(get-in % [:decl/source-node :node/ordinal]) :decl/name))
       (mapv #(constructor-block db class-name %))))

(defn- emitted-method-decls [db members interface?]
  (->> (get members :decl.kind/method)
       (sort-by (juxt #(get-in % [:decl/source-node :node/ordinal]) :decl/name))
       (mapv #(method-block db % interface?))))

(defn- unsupported-declaration [decl rule reason]
  (apply-rule {:text ""
               :usings #{}
               :helpers #{}
               :diagnostics [(assoc (source-context (:decl/source-node decl))
                                    :diagnostic/severity :diagnostic.severity/error
                                    :diagnostic/message "Unsupported Java enum member"
                                    :rule/id rule
                                    :rule/context {:reason reason})]
               :rule-applications []
               :provenance []}
              (:decl/source-node decl)
              rule
              :rule-app.status/failed))

(defn- bool-return-type? [method-decl]
  (= "bool" (type-name (:decl/return-type method-decl))))

(defn- bool-literal-node? [node]
  (and (= :java.node/literal (:node/kind node))
       (boolean? (edn/read-string (:node/value node)))))

(defn- enum-switch-case-supported? [db case-node]
  (let [labels (child-nodes db (:db/id case-node) :case-label)
        results (child-nodes db (:db/id case-node) :case-result)]
    (and (= "arrow" (:node/value case-node))
         (= 1 (count results))
         (every? #(= :java.node/field-read (:node/kind %)) labels)
         (bool-literal-node? (first results)))))

(defn- enum-switch-method-shape [db method-decl]
  (let [statements (child-nodes db (get-in method-decl [:decl/source-node :db/id]) :body)
        return-node (first statements)
        switch-node (when (= :java.node/return-statement (:node/kind return-node))
                      (child-node db (:db/id return-node) :return-expression))
        selector (when (= :java.node/switch-expression (:node/kind switch-node))
                   (child-node db (:db/id switch-node) :selector))
        cases (when switch-node
                (child-nodes db (:db/id switch-node) :case))
        mods (modifiers method-decl)]
    (when (and (contains? mods :public)
               (not (contains? mods :static))
               (bool-return-type? method-decl)
               (= 1 (count statements))
               (= :java.node/this (:node/kind selector))
               (seq cases)
               (every? #(enum-switch-case-supported? db %) cases))
      {:return return-node
       :switch switch-node
       :cases cases})))

(defn- enum-constant? [enum-decl field-decl]
  (let [mods (modifiers field-decl)]
    (and (= (:decl/qualified-name enum-decl)
            (get-in field-decl [:decl/type :type/name]))
         (contains? mods :static)
         (contains? mods :final))))

(defn- enum-constant-line [field-decl final?]
  (emitted (str "        " (:decl/name field-decl) (if final? "\n" ",\n"))
           (:decl/source-node field-decl)
           :java.field-node/to-csharp-field))

(defn- enum-switch-arm-pattern [db enum-decl case-node]
  (let [labels (child-nodes db (:db/id case-node) :case-label)]
    (if (seq labels)
      (->> labels
           (map #(str (:decl/name enum-decl) "." (:node/name %)))
           (str/join " or "))
      "_")))

(defn- enum-switch-case-arm [db enum-decl case-node]
  (let [result-node (first (child-nodes db (:db/id case-node) :case-result))
        result (emit-expression {:db db} result-node)
        text (str (indent 4)
                  (enum-switch-arm-pattern db enum-decl case-node)
                  " => "
                  (:text result)
                  ",\n")]
    (-> result
        (with-text text)
        (apply-rule case-node
                    :java.switch-case-node/to-csharp-switch-arm
                    :rule-app.status/success))))

(defn- enum-switch-expression [db enum-decl switch-node cases]
  (let [arms (mapv #(enum-switch-case-arm db enum-decl %) cases)
        arms-result (merge-emits arms)
        text (str "value switch\n"
                  (indent 3) "{\n"
                  (:text arms-result)
                  (indent 3) "}")]
    (-> arms-result
        (with-text text)
        (apply-rule switch-node
                    :java.switch-expression-node/to-csharp-switch
                    :rule-app.status/success))))

(defn- enum-extension-method [db enum-decl method-decl shape]
  (let [switch-result (enum-switch-expression db enum-decl (:switch shape) (:cases shape))
        signature (str "public static "
                       (type-name (:decl/return-type method-decl))
                       " "
                       (method-name method-decl)
                       "(this "
                       (:decl/name enum-decl)
                       " value)")
        text (str "        " signature "\n"
                  "        {\n"
                  (indent 3) "return " (:text switch-result) ";\n"
                  "        }\n")]
    (-> switch-result
        (with-text text)
        (apply-rule (:decl/source-node method-decl)
                    :java.method-node/to-csharp-method
                    :rule-app.status/success))))

(defn- enum-extension-content [db enum-decl method-shapes]
  (when (seq method-shapes)
    (let [methods (mapv (fn [[method-decl shape]]
                          (enum-extension-method db enum-decl method-decl shape))
                        method-shapes)
          method-result (merge-emits "\n" methods)
          text (str "\n"
                    "    public static class " (:decl/name enum-decl) "Extensions\n"
                    "    {\n"
                    (:text method-result)
                    "\n"
                    "    }\n")]
      (with-text method-result text))))

(defn- enum-content [db enum-decl]
  (let [namespace (csharp-namespace enum-decl)
        members (member-groups db enum-decl)
        constants (->> (get members :decl.kind/field)
                       (filter #(enum-constant? enum-decl %))
                       (sort-by (juxt #(get-in % [:decl/source-node :node/ordinal]) :decl/name))
                       vec)
        enum-methods (->> (get members :decl.kind/method)
                          (sort-by (juxt #(get-in % [:decl/source-node :node/ordinal]) :decl/name))
                          vec)
        method-shapes (->> enum-methods
                           (map (fn [method-decl]
                                  [method-decl (enum-switch-method-shape db method-decl)])))
        supported-methods (->> method-shapes
                               (filter second)
                               vec)
        unsupported-methods (->> method-shapes
                                 (remove second)
                                 (map first)
                                 vec)
        constant-lines (mapv (fn [field-decl index]
                               (enum-constant-line field-decl (= index (dec (count constants)))))
                             constants
                             (range))
        unsupported-members (mapv #(unsupported-declaration %
                                                            :java.method-node/to-csharp-method
                                                            :emit.reason/unsupported-enum-method)
                                  unsupported-methods)
        body-metadata (merge-emits constant-lines)
        body (:text body-metadata)
        enum-section (with-text body-metadata
                       (str "    " (visibility (modifiers enum-decl)) " enum " (:decl/name enum-decl) "\n"
                            "    {\n"
                            body
                            (when (seq body) "\n")
                            "    }\n"))
        extension-section (enum-extension-content db enum-decl supported-methods)
        content-metadata (merge-emits (concat [enum-section]
                                              (when extension-section
                                                [extension-section])
                                              unsupported-members))
        text (str "// <auto-generated>\n"
                  "// Generated by Vibeformer. Changes under target/csharp are disposable.\n"
                  "// </auto-generated>\n\n"
                  (when-not (str/blank? namespace)
                    (str "namespace " namespace "\n{\n"))
                  (:text content-metadata)
                  (when-not (str/blank? namespace)
                    "}\n"))]
    (-> content-metadata
        (with-text text)
        (apply-rule (:decl/source-node enum-decl)
                    :java.enum-node/to-csharp-enum
                    :rule-app.status/success))))

(defn- declaration-usings [db namespace type-decl members]
  (let [field-types (keep :decl/type (get members :decl.kind/field))
        method-return-types (keep :decl/return-type (get members :decl.kind/method))
        inherited (inherited-types db type-decl)
        param-types (mapcat #(map :source-type (type-ref-params db %))
                            (concat (get members :decl.kind/constructor)
                                    (get members :decl.kind/method)))]
    (->> (concat inherited field-types method-return-types param-types)
         (mapcat (comp :csharp/usings map-type))
         (remove #(= namespace %))
         set
         sort
         vec)))

(defn- class-content [db class-decl]
  (let [namespace (csharp-namespace class-decl)
        interface? (= :decl.kind/interface (:decl/kind class-decl))
        members (member-groups db class-decl)
        usings (declaration-usings db namespace class-decl members)
        fields (emitted-field-decls db members)
        ctors (when-not interface?
                (emitted-ctor-decls db (:decl/name class-decl) members))
        methods (emitted-method-decls db members interface?)
        body-sections (->> [fields ctors methods]
                           (remove empty?)
                           (mapv #(merge-emits "\n" %)))
        body-metadata (merge-emits "\n\n" body-sections)
        body (:text body-metadata)
        all-usings (->> (concat usings (:usings body-metadata))
                        (remove #(= namespace %))
                        set
                        sort
                        vec)
        text (str "// <auto-generated>\n"
                  "// Generated by Vibeformer. Changes under target/csharp are disposable.\n"
                  "// </auto-generated>\n\n"
                  (when (seq all-usings)
                    (str (str/join "\n" (map #(str "using " % ";") all-usings)) "\n\n"))
                  (when-not (str/blank? namespace)
                    (str "namespace " namespace "\n{\n"))
                  (type-line db class-decl)
                  "    {\n"
                  body
                  (when (seq body) "\n")
                  "    }\n"
                  (when-not (str/blank? namespace)
                    "}\n"))]
    (-> body-metadata
        (with-text text)
        (apply-rule (:decl/source-node class-decl)
                    (if interface?
                      :java.interface-node/to-csharp-interface
                      :java.class-node/to-csharp-class)
                    :rule-app.status/success))))

(defn- type-content [db type-decl]
  (if (= :decl.kind/enum (:decl/kind type-decl))
    (enum-content db type-decl)
    (class-content db type-decl)))

(defn emit!
  "Regenerate disposable C# declaration skeletons for Java class facts."
  [db target-dir]
  (let [target-dir (path target-dir)
        classes (type-decls db)
        indexes (provenance-indexes db)]
    (clear-directory! target-dir)
    (let [emitted-classes (mapv (fn [class-decl]
                                  (let [file (class-output-path target-dir class-decl)
                                        result (type-content db class-decl)
                                        file-path (slash-path (normalize-path file))
                                        text (:text result)
                                        provenance (finalize-provenance indexes
                                                                        file-path
                                                                        text
                                                                        (:provenance result))]
                                    (write-string! file text)
                                    (assoc result
                                           :dest-file file-path
                                           :provenance provenance
                                           :text nil)))
                                classes)
          files (mapv :dest-file emitted-classes)
          metadata (merge-emits emitted-classes)]
      {:csharp/files-written (count files)
       :csharp/files files
       :csharp/rule-applications (:rule-applications metadata)
       :csharp/provenance (:provenance metadata)
       :csharp/diagnostics (:diagnostics metadata)
       :csharp/helpers (vec (sort (:helpers metadata)))
       :csharp/usings (vec (sort (:usings metadata)))})))
