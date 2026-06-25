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

(defn- class-decls [db]
  (->> (d/q '[:find (pull ?decl [:db/id
                                  :decl/id
                                  :decl/name
                                  :decl/qualified-name
                                  :decl/modifiers
                                  {:decl/source-node [:db/id
                                                      :node/id
                                                      :node/name
                                                      :node/ordinal
                                                      {:node/file [:file/id
                                                                   :file/path
                                                                   :file/package]}]}])
              :where
              [?decl :decl/lang :lang/java]
              [?decl :decl/kind :decl.kind/class]]
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
                                    {:decl/source-node [:db/id
                                                        :node/id
                                                        :node/kind
                                                        :node/name
                                                        :node/ordinal]}])
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

(defn- method-call-ref [db node]
  (ffirst
   (d/q '[:find (pull ?ref [:ref/id
                            :ref/name
                            :ref/resolved?
                            {:ref/to-type [:type/id :type/lang :type/name :type/nullable?]}
                            {:ref/owner-type [:type/id :type/lang :type/name :type/nullable?]}
                            {:ref/to-decl [:decl/id :decl/name :decl/qualified-name]}])
          :in $ ?node
          :where
          [?ref :ref/from-node ?node]
          [?ref :ref/kind :ref.kind/method-call]]
        db
        (:db/id node))))

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

(defn- emitted
  ([text node rule]
   (emitted text node rule {}))
  ([text node rule {:keys [usings helpers diagnostics rule-applications]}]
   {:text text
    :usings (set usings)
    :helpers (set helpers)
    :diagnostics (vec diagnostics)
    :rule-applications (conj (vec rule-applications)
                             (rule-app node rule :rule-app.status/success))}))

(defn- unsupported [node rule context]
  {:text (str "throw new System.NotImplementedException(\"Unsupported Java node "
              (name (:node/kind node)) "\");")
   :usings #{}
   :helpers #{}
   :diagnostics [(assoc (source-context node)
                        :diagnostic/severity :diagnostic.severity/error
                        :diagnostic/message (str "Unsupported Java node " (:node/kind node))
                        :rule/id rule
                        :rule/context context)]
   :rule-applications [(rule-app node rule :rule-app.status/failed)]})

(defn- merge-emits
  ([parts]
   (merge-emits "" parts))
  ([separator parts]
   {:text (str/join separator (map :text parts))
    :usings (set (mapcat :usings parts))
    :helpers (set (mapcat :helpers parts))
    :diagnostics (vec (mapcat :diagnostics parts))
    :rule-applications (vec (mapcat :rule-applications parts))}))

(defn- with-text [result text]
  (assoc result :text text))

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
          (update :rule-applications conj (rule-app node :java.regex-pattern-compile/to-csharp-regex :rule-app.status/success)))

      (and (= "java.lang.String" owner) (= "trim" source-method-name) target)
      (-> combined
          (with-text (str target-text ".Trim()"))
          (update :rule-applications conj (rule-app node :java.string-trim/to-csharp-trim :rule-app.status/success)))

      (and (= "java.lang.String" owner) (= "isEmpty" source-method-name) target)
      (-> combined
          (with-text (str "string.IsNullOrEmpty(" target-text ")"))
          (update :rule-applications conj (rule-app node :java.string-is-empty/to-csharp-is-null-or-empty :rule-app.status/success)))

      (and (= "java.util.regex.Pattern" owner) (= "split" source-method-name) target)
      (-> combined
          (with-text (str target-text ".Split(" args ")"))
          (update :rule-applications conj (rule-app node :java.regex-split/to-csharp-regex-split :rule-app.status/success)))

      (and (= "java.io.PrintStream" owner) (= "println" source-method-name))
      (-> combined
          (with-text (if (= "System.Console.Error" target-text)
                       (str "System.Console.Error.WriteLine(" args ")")
                       (str "System.Console.WriteLine(" args ")")))
          (update :rule-applications conj (rule-app node :java.printstream-println/to-csharp-console :rule-app.status/success)))

      (and (= "java.lang.System" owner) (= "exit" source-method-name))
      (-> combined
          (with-text (str "System.Environment.Exit(" args ")"))
          (update :rule-applications conj (rule-app node :java.system-exit/to-csharp-environment-exit :rule-app.status/success)))

      (and (= "java.nio.file.Path" owner) (= "of" source-method-name) (= 1 (count (child-nodes db (:db/id node) :argument))))
      (-> args-result
          (update :rule-applications conj (rule-app node :java.path-of/to-csharp-string-path :rule-app.status/success)))

      (and (= "java.nio.file.Files" owner) (= "readString" source-method-name))
      (-> args-result
          (with-text (str "System.IO.File.ReadAllText(" args ")"))
          (update :rule-applications conj (rule-app node :java.files-read-string/to-csharp-file-read-all-text :rule-app.status/success)))

      :else
      (let [call-text (str (when target (str target-text "."))
                           (method-name {:decl/name source-method-name})
                           "(" args ")")]
        (-> combined
            (with-text call-text)
            (update :rule-applications conj (rule-app node :java.method-call-node/to-csharp-invocation :rule-app.status/success)))))))

(defn- emit-field-read [ctx node]
  (let [target (child-node (:db ctx) (:db/id node) :target)
        target-result (when target (emit-expression ctx target))
        target-text (:text target-result)
        field-name (:node/name node)
        text (cond
               (and (= "System" target-text) (= "err" field-name)) "System.Console.Error"
               (and (= "System" target-text) (= "out" field-name)) "System.Console"
               (and target (= "length" field-name)) (str target-text ".Length")
               target (str target-text "." field-name)
               :else field-name)]
    (-> (or target-result (merge-emits []))
        (with-text text)
        (update :rule-applications conj (rule-app node :java.field-read-node/to-csharp-member :rule-app.status/success)))))

(defn- emit-expression [ctx node]
  (case (:node/kind node)
    :java.node/literal
    (emitted (literal-text node) node :java.literal-node/to-csharp-literal)

    :java.node/variable-read
    (emitted (:node/name node) node :java.variable-read-node/to-csharp-variable)

    :java.node/type-access
    (emitted (csharp-type-access (:node/value node)) node :java.type-access-node/to-csharp-type)

    :java.node/field-read
    (emit-field-read ctx node)

    :java.node/array-read
    (let [target (emit-child-expression ctx node :target)
          index (emit-child-expression ctx node :index)]
      (-> (merge-emits [target index])
          (with-text (str (:text target) "[" (:text index) "]"))
          (update :rule-applications conj (rule-app node :java.array-read-node/to-csharp-indexer :rule-app.status/success))))

    :java.node/binary-operator
    (let [left (emit-child-expression ctx node :left)
          right (emit-child-expression ctx node :right)
          op (binary-operator (:node/value node))]
      (if op
        (-> (merge-emits [left right])
            (with-text (str (:text left) " " op " " (:text right)))
            (update :rule-applications conj (rule-app node :java.binary-operator-node/to-csharp-binary :rule-app.status/success)))
        (unsupported node :java.binary-operator-node/to-csharp-binary {:operator (:node/value node)})))

    :java.node/method-call
    (emit-method-call ctx node)

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
          (update :rule-applications conj (rule-app node :java.local-variable-node/to-csharp-local :rule-app.status/success))))

    :java.node/return-statement
    (let [expr (emit-child-expression ctx node :return-expression)]
      (-> expr
          (with-text (indent-lines indent-level (str "return " (:text expr) ";")))
          (update :rule-applications conj (rule-app node :java.return-statement-node/to-csharp-return :rule-app.status/success))))

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
          (update :rule-applications conj (rule-app node :java.if-statement-node/to-csharp-if :rule-app.status/success))))

    :java.node/method-call
    (let [expr (emit-expression ctx node)]
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
       :rule-applications []})))

(defn- class-line [class-decl]
  (let [mods (modifiers class-decl)]
    (str "    "
         (str/join " " (cond-> [(visibility mods)]
                         (contains? mods :final) (conj "sealed")
                         true (conj "class")
                         true (conj (:decl/name class-decl))))
         "\n")))

(defn- field-line [db field-decl]
  (let [mods (modifiers field-decl)]
    (when (and (contains? mods :static)
               (contains? mods :final)
               (:decl/type field-decl))
      (let [initializer-node (child-node db (get-in field-decl [:decl/source-node :db/id]) :initializer)
            initializer (when initializer-node (emit-expression {:db db} initializer-node))
            text (str "        "
                      (str/join " " [(visibility mods)
                                     "static"
                                     "readonly"
                                     (type-name (:decl/type field-decl))
                                     (:decl/name field-decl)])
                      (when initializer (str " = " (:text initializer)))
                      ";\n")]
        (-> (or initializer (merge-emits []))
            (with-text text)
            (update :rule-applications conj (rule-app (:decl/source-node field-decl)
                                                      :java.field-node/to-csharp-field
                                                      :rule-app.status/success)))))))

(defn- method-name [method-decl]
  (if (= "main" (:decl/name method-decl))
    "Main"
    (:decl/name method-decl)))

(defn- signature-modifiers [decl]
  (let [mods (modifiers decl)]
    (cond-> [(visibility mods)]
      (contains? mods :static) (conj "static"))))

(defn- constructor-block [db class-name ctor-decl]
  (let [body (emit-body db ctor-decl 3)]
    (-> body
        (with-text (str "        "
                        (str/join " " (conj (signature-modifiers ctor-decl) class-name))
                        "(" (param-list db ctor-decl) ")\n"
                        "        {\n"
                        (:text body) "\n"
                        "        }\n"))
        (update :rule-applications conj (rule-app (:decl/source-node ctor-decl)
                                                  :java.constructor-node/to-csharp-constructor
                                                  :rule-app.status/success)))))

(defn- method-block [db method-decl]
  (let [return-type (type-name (:decl/return-type method-decl))
        body (emit-body db method-decl 3)]
    (-> body
        (with-text (str "        "
                        (str/join " " (conj (signature-modifiers method-decl)
                                            return-type
                                            (method-name method-decl)))
                        "(" (param-list db method-decl) ")\n"
                        "        {\n"
                        (:text body) "\n"
                        "        }\n"))
        (update :rule-applications conj (rule-app (:decl/source-node method-decl)
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

(defn- emitted-method-decls [db members]
  (->> (get members :decl.kind/method)
       (sort-by (juxt #(get-in % [:decl/source-node :node/ordinal]) :decl/name))
       (mapv #(method-block db %))))

(defn- declaration-usings [db namespace members]
  (let [field-types (keep :decl/type (get members :decl.kind/field))
        method-return-types (keep :decl/return-type (get members :decl.kind/method))
        param-types (mapcat #(map :source-type (type-ref-params db %))
                            (concat (get members :decl.kind/constructor)
                                    (get members :decl.kind/method)))]
    (->> (concat field-types method-return-types param-types)
         (mapcat (comp :csharp/usings map-type))
         (remove #(= namespace %))
         set
         sort
         vec)))

(defn- join-sections [sections]
  (->> sections
       (remove empty?)
       (map #(str/join "\n" %))
       (str/join "\n\n")))

(defn- class-content [db class-decl]
  (let [namespace (csharp-namespace class-decl)
        members (member-groups db class-decl)
        usings (declaration-usings db namespace members)
        fields (emitted-field-decls db members)
        ctors (emitted-ctor-decls db (:decl/name class-decl) members)
        methods (emitted-method-decls db members)
        body-results (vec (concat fields ctors methods))
        body (join-sections [(mapv :text fields)
                             (mapv :text ctors)
                             (mapv :text methods)])
        body-metadata (merge-emits body-results)
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
                  (class-line class-decl)
                  "    {\n"
                  body
                  (when (seq body) "\n")
                  "    }\n"
                  (when-not (str/blank? namespace)
                    "}\n"))]
    (-> body-metadata
        (with-text text)
        (update :rule-applications conj (rule-app (:decl/source-node class-decl)
                                                  :java.class-node/to-csharp-class
                                                  :rule-app.status/success)))))

(defn emit!
  "Regenerate disposable C# declaration skeletons for Java class facts."
  [db target-dir]
  (let [target-dir (path target-dir)
        classes (class-decls db)]
    (clear-directory! target-dir)
    (let [emitted-classes (mapv (fn [class-decl]
                                  (let [file (class-output-path target-dir class-decl)
                                        result (class-content db class-decl)
                                        file-path (slash-path (normalize-path file))]
                                    (write-string! file (:text result))
                                    (assoc result
                                           :dest-file file-path
                                           :text nil)))
                                classes)
          files (mapv :dest-file emitted-classes)
          metadata (merge-emits emitted-classes)]
      {:csharp/files-written (count files)
       :csharp/files files
       :csharp/rule-applications (:rule-applications metadata)
       :csharp/diagnostics (:diagnostics metadata)
       :csharp/helpers (vec (sort (:helpers metadata)))
       :csharp/usings (vec (sort (:usings metadata)))})))
