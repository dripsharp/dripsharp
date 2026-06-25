(ns vibeformer.emit.csharp
  (:require [clojure.string :as str]
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

(defn- class-line [class-decl]
  (let [mods (modifiers class-decl)]
    (str "    "
         (str/join " " (cond-> [(visibility mods)]
                         (contains? mods :final) (conj "sealed")
                         true (conj "class")
                         true (conj (:decl/name class-decl))))
         "\n")))

(defn- field-line [field-decl]
  (let [mods (modifiers field-decl)]
    (when (and (contains? mods :static)
               (contains? mods :final)
               (:decl/type field-decl))
      (str "        "
           (str/join " " [(visibility mods)
                          "static"
                          "readonly"
                          (type-name (:decl/type field-decl))
                          (:decl/name field-decl)])
           ";\n"))))

(defn- method-name [method-decl]
  (if (= "main" (:decl/name method-decl))
    "Main"
    (:decl/name method-decl)))

(defn- signature-modifiers [decl]
  (let [mods (modifiers decl)]
    (cond-> [(visibility mods)]
      (contains? mods :static) (conj "static"))))

(defn- constructor-block [db class-name ctor-decl]
  (str "        "
       (str/join " " (conj (signature-modifiers ctor-decl) class-name))
       "(" (param-list db ctor-decl) ")\n"
       "        {\n"
       "            throw new System.NotImplementedException();\n"
       "        }\n"))

(defn- method-block [db method-decl]
  (let [return-type (type-name (:decl/return-type method-decl))]
    (str "        "
         (str/join " " (conj (signature-modifiers method-decl)
                             return-type
                             (method-name method-decl)))
         "(" (param-list db method-decl) ")\n"
         "        {\n"
         "            throw new System.NotImplementedException();\n"
         "        }\n")))

(defn- emitted-field-decls [members]
  (->> (get members :decl.kind/field)
       (sort-by (juxt #(get-in % [:decl/source-node :node/ordinal]) :decl/name))
       (keep field-line)
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
        fields (emitted-field-decls members)
        ctors (emitted-ctor-decls db (:decl/name class-decl) members)
        methods (emitted-method-decls db members)
        body (join-sections [fields ctors methods])]
    (str "// <auto-generated>\n"
         "// Generated by Vibeformer. Changes under target/csharp are disposable.\n"
         "// </auto-generated>\n\n"
         (when (seq usings)
           (str (str/join "\n" (map #(str "using " % ";") usings)) "\n\n"))
         (when-not (str/blank? namespace)
           (str "namespace " namespace "\n{\n"))
         (class-line class-decl)
         "    {\n"
         body
         (when (seq body) "\n")
         "    }\n"
         (when-not (str/blank? namespace)
           "}\n"))))

(defn emit!
  "Regenerate disposable C# declaration skeletons for Java class facts."
  [db target-dir]
  (let [target-dir (path target-dir)
        classes (class-decls db)]
    (clear-directory! target-dir)
    (let [files (mapv (fn [class-decl]
                        (let [file (class-output-path target-dir class-decl)]
                          (write-string! file (class-content db class-decl))
                          (slash-path (normalize-path file))))
                      classes)]
      {:csharp/files-written (count files)
       :csharp/files files})))
