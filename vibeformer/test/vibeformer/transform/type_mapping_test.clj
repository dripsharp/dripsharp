(ns vibeformer.transform.type-mapping-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.transform.type-mapping :as type-mapping])
  (:import (java.util UUID)))

(defn- type-fact
  ([lang name]
   (type-fact lang name false []))
  ([lang name nullable?]
   (type-fact lang name nullable? []))
  ([lang name nullable? args]
   {:type/lang lang
    :type/name name
    :type/nullable? nullable?
    :type/args (mapv (fn [ordinal arg]
                       {:type.arg/ordinal ordinal
                        :type.arg/type arg})
                     (range)
                     args)}))

(defn- maps-to
  [source-type csharp-type usings helpers]
  (is (= {:csharp/type csharp-type
          :csharp/usings (vec (sort usings))
          :csharp/helpers (vec (sort helpers))}
         (type-mapping/map-type source-type))))

(defn- with-empty-db [f]
  (let [system (str "vibeformer-type-mapping-test-" (UUID/randomUUID))
        db-name (str "facts-" (UUID/randomUUID))
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system system})
        created? (atom false)]
    (try
      (is (true? (d/create-database client {:db-name db-name})))
      (reset! created? true)
      (f (d/connect client {:db-name db-name}))
      (finally
        (when @created?
          (dl/release-db {:system system
                          :storage-dir :mem
                          :db-name db-name}))))))

(deftest maps-java-source-types-to-csharp-types
  (testing "primitives, boxed primitives, strings, numerics, arrays, and exceptions"
    (maps-to (type-fact :lang/java "int") "int" [] [])
    (maps-to (type-fact :lang/java "java.lang.Integer" true) "int?" [] [])
    (maps-to (type-fact :lang/java "java.lang.String") "string" [] [])
    (maps-to (type-fact :lang/java "java.lang.Object") "object" [] [])
    (maps-to (type-fact :lang/java "java.math.BigDecimal") "decimal" [] [])
    (maps-to (type-fact :lang/java "java.math.BigInteger") "BigInteger" ["System.Numerics"] [])
    (maps-to (type-fact :lang/java "java.util.regex.Pattern") "Regex" ["System.Text.RegularExpressions"] [])
    (maps-to (type-fact :lang/java "java.lang.Class") "Type" ["System"] [])
    (maps-to (type-fact :lang/java "java.lang.ClassLoader") "Assembly" ["System.Reflection"] [])
    (maps-to (type-fact :lang/java "java.lang.reflect.Type") "Type" ["System"] [])
    (maps-to (type-fact :lang/java "java.lang.reflect.ParameterizedType") "Type" ["System"] [])
    (maps-to (type-fact :lang/java "java.lang.reflect.Method") "MethodInfo" ["System.Reflection"] [])
    (maps-to (type-fact :lang/java "java.lang.reflect.Constructor") "ConstructorInfo" ["System.Reflection"] [])
    (maps-to (type-fact :lang/java "java.lang.reflect.Parameter") "ParameterInfo" ["System.Reflection"] [])
    (maps-to (type-fact :lang/java "java.lang.annotation.Annotation") "Attribute" ["System"] [])
    (maps-to (type-fact :lang/java "java.net.URI") "Uri" ["System"] [])
    (maps-to (type-fact :lang/java "java.nio.file.Path") "string" [] [])
    (maps-to (type-fact :lang/java "java.lang.String[]") "string[]" [] [])
    (maps-to (type-fact :lang/java "java.lang.IllegalArgumentException") "ArgumentException" ["System"] []))
  (testing "collections and optionals include required usings and helpers"
    (maps-to (type-fact :lang/java
                        "java.util.List"
                        false
                        [(type-fact :lang/java "java.lang.String")])
             "List<string>"
             ["System.Collections.Generic"]
             [])
    (maps-to (type-fact :lang/java
                        "java.util.Map"
                        false
                        [(type-fact :lang/java "java.lang.String")
                         (type-fact :lang/java "java.lang.Integer")])
             "Dictionary<string, int>"
             ["System.Collections.Generic"]
             [])
    (maps-to (type-fact :lang/java
                        "java.util.LinkedHashSet"
                        false
                        [(type-fact :lang/java "java.lang.String")])
             "HashSet<string>"
             ["System.Collections.Generic"]
             [])
    (maps-to (type-fact :lang/java
                        "java.util.Optional"
                        false
                        [(type-fact :lang/java "java.lang.String")])
             "string?"
             []
             [:helper/java-optional]))
  (testing "project-local types keep a namespace using and simple C# type name"
    (maps-to (type-fact :lang/java "com.acme.parser.Greeter")
             "Greeter"
             ["com.acme.parser"]
             [])
    (maps-to (type-fact :lang/java
                        "com.acme.parser.Box"
                        false
                        [(type-fact :lang/java "T")])
             "Box<T>"
             ["com.acme.parser"]
             [])))

(deftest maps-kotlin-source-types-to-csharp-types
  (testing "nullable syntax and Kotlin scalars"
    (maps-to (type-fact :lang/kotlin "Int") "int" [] [])
    (maps-to (type-fact :lang/kotlin "String?") "string?" [] [])
    (maps-to (type-fact :lang/kotlin "kotlin.String" true) "string?" [] []))
  (testing "collections and project-local types"
    (maps-to (type-fact :lang/kotlin
                        "kotlin.collections.List"
                        false
                        [(type-fact :lang/kotlin "String")])
             "List<string>"
             ["System.Collections.Generic"]
             [])
    (maps-to (type-fact :lang/kotlin
                        "List"
                        false
                        [(type-fact :lang/kotlin "URI")])
             "List<Uri>"
             ["System" "System.Collections.Generic"]
             [])
    (maps-to (type-fact :lang/kotlin
                        "MutableMap"
                        false
                        [(type-fact :lang/kotlin "String")
                         (type-fact :lang/kotlin "Int")])
             "Dictionary<string, int>"
             ["System.Collections.Generic"]
             [])
    (maps-to (type-fact :lang/kotlin "com.acme.parser.KotlinGreeter")
             "KotlinGreeter"
             ["com.acme.parser"]
             []))
  (testing "function type syntax maps to C# delegates"
    (maps-to (type-fact :lang/kotlin "(String, Int) -> Boolean")
             "Func<string, int, bool>"
             ["System"]
             [])
    (maps-to (type-fact :lang/kotlin "(String) -> Unit")
             "Action<string>"
             ["System"]
             [])))

(deftest reports-missing-mappings-with-source-context
  (try
    (type-mapping/map-type {:type/id "java:javax.persistence.EntityManager"
                            :type/lang :lang/java
                            :type/name "javax.persistence.EntityManager"
                            :type/nullable? false})
    (is false "Expected an unknown mapping failure.")
    (catch clojure.lang.ExceptionInfo ex
      (is (= "No C# type mapping for javax.persistence.EntityManager."
             (ex-message ex)))
      (is (= {:type/id "java:javax.persistence.EntityManager"
              :type/lang :lang/java
              :type/name "javax.persistence.EntityManager"
              :type/nullable? false
              :mapping/reason :mapping.reason/unknown-type}
             (ex-data ex))))))

(def type-fixture
  [{:db/id "project"
    :project/id "fixture"
    :project/name "Fixture"
    :project/root "/workspace/fixture"}
   {:db/id "string-type"
    :type/id "java.lang.String"
    :type/lang :lang/java
    :type/name "java.lang.String"
    :type/nullable? false}
   {:db/id "integer-type"
    :type/id "java.lang.Integer"
    :type/lang :lang/java
    :type/name "java.lang.Integer"
    :type/nullable? false}
   {:db/id "list-type"
    :type/id "java.util.List<java.lang.String>"
    :type/lang :lang/java
    :type/name "java.util.List"
    :type/nullable? false
    :type/args [{:type.arg/ordinal 0
                 :type.arg/type "string-type"}]}
   {:db/id "kotlin-null-string-type"
    :type/id "kotlin.String?"
    :type/lang :lang/kotlin
    :type/name "String"
    :type/nullable? true}
   {:db/id "map-type"
    :type/id "kotlin.collections.Map<kotlin.String,java.lang.Integer>"
    :type/lang :lang/kotlin
    :type/name "kotlin.collections.Map"
    :type/nullable? false
    :type/args [{:type.arg/ordinal 0
                 :type.arg/type "kotlin-null-string-type"}
                {:type.arg/ordinal 1
                 :type.arg/type "integer-type"}]}])

(deftest mappings-are-queryable-from-datomic-type-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (d/transact conn {:tx-data type-fixture})
      (let [db (d/db conn)]
        (testing "all mappings are sorted by source type id"
          (is (= ["java.lang.Integer"
                  "java.lang.String"
                  "java.util.List<java.lang.String>"
                  "kotlin.String?"
                  "kotlin.collections.Map<kotlin.String,java.lang.Integer>"]
                 (mapv :type/id (type-mapping/mapped-types db)))))
        (testing "nested type arguments are expanded before mapping"
          (is (= {:type/id "kotlin.collections.Map<kotlin.String,java.lang.Integer>"
                  :type/lang :lang/kotlin
                  :type/name "kotlin.collections.Map"
                  :type/nullable? false
                  :csharp/type "Dictionary<string?, int>"
                  :csharp/usings ["System.Collections.Generic"]
                  :csharp/helpers []}
                 (type-mapping/mapped-type db "kotlin.collections.Map<kotlin.String,java.lang.Integer>"))))
        (testing "single-type lookup fails clearly"
          (try
            (type-mapping/mapped-type db "missing.Type")
            (is false "Expected missing type failure.")
            (catch clojure.lang.ExceptionInfo ex
              (is (= {:type/id "missing.Type"
                      :mapping/reason :mapping.reason/type-not-found}
                     (ex-data ex))))))))))
