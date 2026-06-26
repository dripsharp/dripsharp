(ns vibeformer.transform.type-mapping
  (:require [clojure.string :as str]
            [datomic.client.api :as d]))

(def collection-using "System.Collections.Generic")
(def numerics-using "System.Numerics")
(def system-using "System")
(def io-using "System.IO")

(def java-primitives
  {"boolean" "bool"
   "byte" "sbyte"
   "short" "short"
   "int" "int"
   "long" "long"
   "float" "float"
   "double" "double"
   "char" "char"
   "void" "void"})

(def java-boxed
  {"java.lang.Boolean" "bool"
   "java.lang.Byte" "sbyte"
   "java.lang.Short" "short"
   "java.lang.Integer" "int"
   "java.lang.Long" "long"
   "java.lang.Float" "float"
   "java.lang.Double" "double"
   "java.lang.Character" "char"
   "java.lang.Void" "void"})

(def kotlin-primitives
  {"Boolean" "bool"
   "kotlin.Boolean" "bool"
   "Byte" "sbyte"
   "kotlin.Byte" "sbyte"
   "Short" "short"
   "kotlin.Short" "short"
   "Int" "int"
   "kotlin.Int" "int"
   "Long" "long"
   "kotlin.Long" "long"
   "Float" "float"
   "kotlin.Float" "float"
   "Double" "double"
   "kotlin.Double" "double"
   "Char" "char"
   "kotlin.Char" "char"
   "Unit" "void"
   "kotlin.Unit" "void"})

(def java-scalars
  (merge java-primitives
         java-boxed
         {"java.lang.Object" "object"
          "Object" "object"
          "java.lang.String" "string"
          "String" "string"
          "java.math.BigDecimal" "decimal"}))

(def kotlin-scalars
  (merge kotlin-primitives
         {"String" "string"
          "kotlin.String" "string"}))

(def java-exceptions
  {"java.lang.Exception" {:csharp/type "Exception"
                          :csharp/usings #{system-using}}
   "java.lang.RuntimeException" {:csharp/type "Exception"
                                 :csharp/usings #{system-using}}
   "java.lang.IllegalArgumentException" {:csharp/type "ArgumentException"
                                         :csharp/usings #{system-using}}
   "java.lang.IllegalStateException" {:csharp/type "InvalidOperationException"
                                      :csharp/usings #{system-using}}
   "java.io.IOException" {:csharp/type "IOException"
                          :csharp/usings #{io-using}}})

(def java-known-types
  {"java.util.regex.Pattern" {:csharp/type "Regex"
                              :csharp/usings #{"System.Text.RegularExpressions"}}
   "java.lang.Class" {:csharp/type "Type"
                      :csharp/usings #{system-using}}
   "java.lang.reflect.Type" {:csharp/type "Type"
                             :csharp/usings #{system-using}}
   "java.lang.reflect.ParameterizedType" {:csharp/type "Type"
                                          :csharp/usings #{system-using}}
   "java.lang.reflect.Method" {:csharp/type "MethodInfo"
                               :csharp/usings #{"System.Reflection"}}
   "java.lang.reflect.Constructor" {:csharp/type "ConstructorInfo"
                                    :csharp/usings #{"System.Reflection"}}
   "java.lang.reflect.Parameter" {:csharp/type "ParameterInfo"
                                  :csharp/usings #{"System.Reflection"}}
   "java.lang.annotation.Annotation" {:csharp/type "Attribute"
                                      :csharp/usings #{system-using}}
   "java.nio.file.Path" {:csharp/type "string"
                         :csharp/usings #{}}})

(def collection-mappings
  {"java.util.Collection" ["ICollection" #{collection-using}]
   "java.util.List" ["List" #{collection-using}]
   "java.util.ArrayList" ["List" #{collection-using}]
   "java.util.LinkedList" ["LinkedList" #{collection-using}]
   "java.util.Set" ["HashSet" #{collection-using}]
   "java.util.HashSet" ["HashSet" #{collection-using}]
   "java.util.Map" ["Dictionary" #{collection-using}]
   "java.util.HashMap" ["Dictionary" #{collection-using}]
   "java.lang.Iterable" ["IEnumerable" #{collection-using}]
   "java.util.Iterator" ["IEnumerator" #{collection-using}]
   "java.util.Optional" ["Optional" #{}]
   "java.util.OptionalInt" ["OptionalInt" #{}]
   "java.util.OptionalLong" ["OptionalLong" #{}]
   "java.util.OptionalDouble" ["OptionalDouble" #{}]
   "kotlin.collections.Collection" ["ICollection" #{collection-using}]
   "Collection" ["ICollection" #{collection-using}]
   "kotlin.collections.List" ["List" #{collection-using}]
   "List" ["List" #{collection-using}]
   "kotlin.collections.MutableList" ["List" #{collection-using}]
   "MutableList" ["List" #{collection-using}]
   "kotlin.collections.Set" ["HashSet" #{collection-using}]
   "Set" ["HashSet" #{collection-using}]
   "kotlin.collections.MutableSet" ["HashSet" #{collection-using}]
   "MutableSet" ["HashSet" #{collection-using}]
   "kotlin.collections.Map" ["Dictionary" #{collection-using}]
   "Map" ["Dictionary" #{collection-using}]
   "kotlin.collections.MutableMap" ["Dictionary" #{collection-using}]
   "MutableMap" ["Dictionary" #{collection-using}]
   "kotlin.sequences.Sequence" ["IEnumerable" #{collection-using}]
   "Sequence" ["IEnumerable" #{collection-using}]})

(defn- normalize-type-name [s]
  (when s
    (str/trim s)))

(defn- sorted-values [xs]
  (->> xs (remove nil?) set sort vec))

(defn- result
  ([csharp-type]
   (result csharp-type #{} #{}))
  ([csharp-type usings helpers]
   {:csharp/type csharp-type
    :csharp/usings (sorted-values usings)
    :csharp/helpers (sorted-values helpers)}))

(defn- combine
  [csharp-type children usings helpers]
  (result csharp-type
          (into usings (mapcat :csharp/usings children))
          (into helpers (mapcat :csharp/helpers children))))

(defn- source-context [source-type]
  (select-keys source-type [:type/id :type/lang :type/name :type/nullable?]))

(defn- fail!
  [source-type reason]
  (throw (ex-info (str "No C# type mapping for " (:type/name source-type) ".")
                  (assoc (source-context source-type)
                         :mapping/reason reason))))

(defn- with-nullability
  [source-type mapped]
  (let [csharp-type (:csharp/type mapped)]
    (cond
      (not (:type/nullable? source-type))
      mapped

      (= "void" csharp-type)
      mapped

      (str/ends-with? csharp-type "?")
      mapped

      :else
      (update mapped :csharp/type str "?"))))

(defn- array-name? [type-name]
  (str/ends-with? type-name "[]"))

(defn- array-element-name [type-name]
  (subs type-name 0 (- (count type-name) 2)))

(declare map-type)

(defn- map-array
  [source-type lang type-name]
  (let [element (map-type (assoc source-type
                                 :type/name (array-element-name type-name)
                                 :type/lang lang
                                 :type/nullable? false
                                 :type/args []))]
    (combine (str (:csharp/type element) "[]") [element] #{} #{})))

(defn- local-type?
  [lang type-name]
  (and (not (str/blank? type-name))
       (not (contains? collection-mappings type-name))
       (not (contains? java-scalars type-name))
       (not (contains? kotlin-scalars type-name))
       (not (contains? java-exceptions type-name))
       (not (str/starts-with? type-name "java."))
       (not (str/starts-with? type-name "javax."))
       (not (str/starts-with? type-name "kotlin."))
       (contains? #{:lang/java :lang/kotlin} lang)))

(declare type-args generic-result)

(defn- local-type-result
  [source-type type-name]
  (let [segments (str/split type-name #"\.")
        simple-name (last segments)
        namespace (when (< 1 (count segments))
                    (str/join "." (butlast segments)))
        args (type-args source-type)]
    (if (seq args)
      (generic-result source-type simple-name (cond-> #{} namespace (conj namespace)))
      (result simple-name (cond-> #{} namespace (conj namespace)) #{}))))

(defn- type-args
  [source-type]
  (->> (:type/args source-type)
       (sort-by #(or (:type.arg/ordinal %) 0))
       (mapv :type.arg/type)))

(defn- generic-result
  [source-type target-name target-usings]
  (let [args (type-args source-type)]
    (when (empty? args)
      (fail! source-type :mapping.reason/missing-type-args))
    (let [mapped-args (mapv map-type args)
          csharp-type (format "%s<%s>"
                              target-name
                              (str/join ", " (map :csharp/type mapped-args)))]
      (combine csharp-type mapped-args target-usings #{}))))

(defn- java-optional-result
  [source-type]
  (let [args (type-args source-type)]
    (when (not= 1 (count args))
      (fail! source-type :mapping.reason/optional-arity))
    (let [mapped (map-type (assoc (first args) :type/nullable? false))]
      (combine (str (:csharp/type mapped) "?")
               [mapped]
               #{}
               #{:helper/java-optional}))))

(defn- java-primitive-optional-result
  [source-type csharp-type]
  (combine (str csharp-type "?")
           []
           #{}
           #{:helper/java-optional}))

(defn- split-top-level
  [s separator]
  (loop [chars (seq s)
         depth 0
         token []
         out []]
    (if-let [ch (first chars)]
      (cond
        (= ch \() (recur (next chars) (inc depth) (conj token ch) out)
        (= ch \)) (recur (next chars) (dec depth) (conj token ch) out)
        (and (= ch separator) (zero? depth))
        (recur (next chars) depth [] (conj out (str/trim (apply str token))))
        :else (recur (next chars) depth (conj token ch) out))
      (conj out (str/trim (apply str token))))))

(defn- function-type? [type-name]
  (str/includes? type-name "->"))

(defn- strip-parens [s]
  (let [trimmed (str/trim s)]
    (if (and (str/starts-with? trimmed "(")
             (str/ends-with? trimmed ")"))
      (subs trimmed 1 (dec (count trimmed)))
      trimmed)))

(defn- map-function-type
  [source-type type-name]
  (let [[params return] (map str/trim (str/split type-name #"->" 2))]
    (when (nil? return)
      (fail! source-type :mapping.reason/function-syntax))
    (let [param-names (let [inside (strip-parens params)]
                        (if (str/blank? inside)
                          []
                          (split-top-level inside \,)))
          param-types (mapv #(map-type {:type/lang :lang/kotlin
                                        :type/name %
                                        :type/nullable? false
                                        :type/args []})
                            param-names)
          return-type (map-type {:type/lang :lang/kotlin
                                 :type/name return
                                 :type/nullable? false
                                 :type/args []})
          void-return? (= "void" (:csharp/type return-type))
          delegate-name (if void-return? "Action" "Func")
          delegate-args (cond-> param-types
                          (not void-return?) (conj return-type))]
      (if (empty? delegate-args)
        (combine delegate-name
                 (cond-> param-types (not void-return?) (conj return-type))
                 #{system-using}
                 #{})
        (combine (format "%s<%s>"
                         delegate-name
                         (str/join ", " (map :csharp/type delegate-args)))
                 delegate-args
                 #{system-using}
                 #{})))))

(defn- language-scalars
  [lang]
  (case lang
    :lang/java java-scalars
    :lang/kotlin (merge java-scalars kotlin-scalars)
    {}))

(defn- strip-null-suffix
  [source-type type-name]
  (if (str/ends-with? type-name "?")
    [(assoc source-type :type/nullable? true) (subs type-name 0 (dec (count type-name)))]
    [source-type type-name]))

(defn map-type
  "Maps a normalized Java or Kotlin source type fact to a deterministic C# type map.

  The return value contains :csharp/type plus sorted :csharp/usings and
  :csharp/helpers vectors. Unknown mappings throw ex-info with source type
  context so the missing source shape can be added explicitly."
  [source-type]
  (let [{:type/keys [lang]} source-type
        raw-name (normalize-type-name (:type/name source-type))
        [source-type type-name] (strip-null-suffix source-type raw-name)]
    (when (str/blank? type-name)
      (fail! source-type :mapping.reason/missing-type-name))
    (with-nullability
      source-type
      (cond
        (array-name? type-name)
        (map-array source-type lang type-name)

        (function-type? type-name)
        (map-function-type source-type type-name)

        (= "java.math.BigInteger" type-name)
        (result "BigInteger" #{numerics-using} #{})

        (contains? (language-scalars lang) type-name)
        (result (get (language-scalars lang) type-name))

        (contains? java-exceptions type-name)
        (let [{:csharp/keys [type usings]} (get java-exceptions type-name)]
          (result type usings #{}))

        (contains? java-known-types type-name)
        (let [{:csharp/keys [type usings]} (get java-known-types type-name)]
          (result type usings #{}))

        (= "java.util.Optional" type-name)
        (java-optional-result source-type)

        (= "java.util.OptionalInt" type-name)
        (java-primitive-optional-result source-type "int")

        (= "java.util.OptionalLong" type-name)
        (java-primitive-optional-result source-type "long")

        (= "java.util.OptionalDouble" type-name)
        (java-primitive-optional-result source-type "double")

        (contains? collection-mappings type-name)
        (let [[target usings] (get collection-mappings type-name)]
          (generic-result source-type target usings))

        (local-type? lang type-name)
        (local-type-result source-type type-name)

        :else
        (fail! source-type :mapping.reason/unknown-type)))))

(defn- pulled-types
  [db]
  (->> (d/q '[:find (pull ?type [:type/id
                                  :type/lang
                                  :type/name
                                  :type/nullable?
                                  {:type/args [:type.arg/ordinal
                                               {:type.arg/type [:type/id]}]}])
              :where
              [?type :type/id]]
            db)
       (map first)))

(defn- inflate-type
  [type-by-id type]
  (letfn [(inflate [t]
            (update t :type/args
                    (fn [args]
                      (->> args
                           (sort-by #(or (:type.arg/ordinal %) 0))
                           (mapv (fn [arg]
                                   (update arg :type.arg/type
                                           #(inflate (get type-by-id (:type/id %))))))))))]
    (inflate type)))

(defn source-types
  "Returns Datomic source type facts with type arguments expanded in ordinal order."
  [db]
  (let [types (pulled-types db)
        type-by-id (into {} (map (juxt :type/id identity) types))]
    (->> types
         (map #(inflate-type type-by-id %))
         (sort-by :type/id)
         vec)))

(defn mapped-types
  "Returns queryable C# mappings for every source type fact in a Datomic db."
  [db]
  (->> (source-types db)
       (map (fn [source-type]
              (merge (source-context source-type)
                     (map-type source-type))))
       (sort-by :type/id)
       vec))

(defn mapped-type
  "Returns the C# mapping for one source type id in a Datomic db."
  [db type-id]
  (or (some #(when (= type-id (:type/id %)) %) (mapped-types db))
      (throw (ex-info (str "Source type not found: " type-id)
                      {:type/id type-id
                       :mapping/reason :mapping.reason/type-not-found}))))
