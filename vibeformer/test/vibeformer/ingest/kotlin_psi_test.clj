(ns vibeformer.ingest.kotlin-psi-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.ingest.kotlin-psi :as kotlin-psi]
            [vibeformer.ingest.source :as source]
            [vibeformer.inventory :as inventory])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.util UUID)))

(def kotlin-fixture
  "package com.acme.parser

import java.util.Locale

object Fixture {
  val defaultName: String? = \"world\"
}

class KotlinGreeter(private val initialName: String?) {
  companion object {
    val fallback: String = \"empty\"
  }

  val name: String? = initialName
  val salutation: String = \"Hello\"

  fun greeting(locale: Locale): String {
    val normalized: String? = name?.uppercase(locale)
    return \"$salutation, ${normalized?.trim() ?: Fixture.defaultName}\"
  }
}

fun topLevelMessage(value: String?): String = value?.trim() ?: Fixture.fallback
")

(def kotlin-semantic-fixture
  "package com.acme.semantic

import java.util.Locale

class LocalValue

fun helper(value: String): String = value

fun choose(value: String): String = value
fun choose(value: String?): String = value ?: \"\"

fun usesHelper(value: String, local: LocalValue, locale: Locale, missing: MissingType): String {
  val localAgain: LocalValue = local
  val helped: String = helper(value)
  val unclear: String = choose(value)
  return MissingApi.format(helped)
}
")

(def kotlin-api-call-fixture
  "package com.acme.api

import java.net.URI
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat

fun apiCalls(path: Path): URI {
  val text = \"\"\"
    value
  \"\"\".trimIndent()
  val values = listOf(text)
  assertThat(values.size).isEqualTo(1)
  return URI(\"file:///tmp\").resolve(path.toUri())
}

fun values(root: Path): List<URI> {
  return listOf(root.resolve(\"child\").toUri(), URI(\"https://example.com\"))
}
")

(def kotlin-object-overrides-fixture
  "package com.acme.overrides

import java.net.URI

interface ModuleReader {
  val isLocal: Boolean
  val scheme: String

  fun read(uri: URI): String

  fun listElements(uri: URI): List<String>
}

object FixtureModuleReader : ModuleReader {
  override val isLocal: Boolean = true

  override val scheme: String = \"foo\"

  override fun read(uri: URI): String = \"hello\"

  override fun listElements(uri: URI): List<String> {
    throw NotImplementedError()
  }
}
")

(defn- with-empty-db [f]
  (let [system (str "vibeformer-kotlin-psi-test-" (UUID/randomUUID))
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

(defn- temp-root []
  (Files/createTempDirectory "vibeformer-kotlin-psi-" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(defn- entity-counts [db]
  {:nodes (ffirst (d/q '[:find (count ?node)
                         :where [?node :node/id]]
                       db))
   :decls (ffirst (d/q '[:find (count ?decl)
                         :where [?decl :decl/id]]
                       db))
   :types (ffirst (d/q '[:find (count ?type)
                         :where [?type :type/id]]
                       db))
   :refs (ffirst (d/q '[:find (count ?ref)
                        :where [?ref :ref/id]]
                      db))
   :features (ffirst (d/q '[:find (count ?feature)
                            :where [?feature :feature/id]]
                          db))})

(deftest extracts-normalized-kotlin-facts-with-psi
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/parser/Greeter.kt"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path kotlin-fixture)
        (source/ingest! conn opts)
        (let [first-run (kotlin-psi/ingest! conn {:project/id "fixture"})
              db (d/db conn)
              counts (entity-counts db)]
          (is (= {:project/id "fixture"
                  :kotlin-files 1}
                 (select-keys first-run [:project/id :kotlin-files])))
          (is (pos? (:transacted-facts first-run)))

          (testing "packages, classes, objects, companion objects, top-level functions, and properties are queryable"
            (is (= #{["com.acme.parser"]}
                   (set (d/q '[:find ?node-name
                               :where
                               [?node :node/file [:file/id "fixture:src/main/kotlin/com/acme/parser/Greeter.kt"]]
                               [?node :node/kind :kotlin.node/package]
                               [?node :node/name ?node-name]]
                             db))))
            (is (set/subset?
                 #{[:kotlin.node/object "Fixture" :decl.kind/object "com.acme.parser.Fixture"]
                   [:kotlin.node/class "KotlinGreeter" :decl.kind/class "com.acme.parser.KotlinGreeter"]
                   [:kotlin.node/companion-object "Companion" :decl.kind/companion-object "com.acme.parser.KotlinGreeter.Companion"]
                   [:kotlin.node/property "name" :decl.kind/property "com.acme.parser.KotlinGreeter.name"]
                   [:kotlin.node/function "greeting" :decl.kind/function "com.acme.parser.KotlinGreeter.greeting(Locale)"]
                   [:kotlin.node/function "topLevelMessage" :decl.kind/function "com.acme.parser.topLevelMessage(String?)"]}
                 (set (d/q '[:find ?node-kind ?node-name ?decl-kind ?decl-qname
                             :where
                             [?node :node/file [:file/id "fixture:src/main/kotlin/com/acme/parser/Greeter.kt"]]
                             [?node :node/kind ?node-kind]
                             [?node :node/name ?node-name]
                             [(contains? #{:kotlin.node/package
                                           :kotlin.node/object
                                           :kotlin.node/class
                                           :kotlin.node/companion-object
                                           :kotlin.node/property
                                           :kotlin.node/function}
                                          ?node-kind)]
                             [?decl :decl/source-node ?node]
                             [?decl :decl/kind ?decl-kind]
                             [?decl :decl/qualified-name ?decl-qname]]
                           db)))))

          (testing "nullable source-language type syntax and type-use refs are queryable"
            (is (= #{["kotlin:String?" "String" true]
                     ["kotlin:String" "String" false]
                     ["kotlin:Locale" "Locale" false]}
                   (set (d/q '[:find ?type-id ?type-name ?nullable?
                               :where
                               [?type :type/lang :lang/kotlin]
                               [?type :type/id ?type-id]
                               [?type :type/name ?type-name]
                               [?type :type/nullable? ?nullable?]
                               [(contains? #{"kotlin:String?" "kotlin:String" "kotlin:Locale"} ?type-id)]]
                             db))))
            (is (set/subset?
                 #{["name" "kotlin:String?"]
                   ["greeting" "kotlin:String"]
                   ["topLevelMessage" "kotlin:String?"]}
                 (set (d/q '[:find ?node-name ?type-id
                             :where
                             [?ref :ref/kind :ref.kind/type-use]
                             [?ref :ref/from-node ?node]
                             [?node :node/name ?node-name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [(contains? #{"name" "greeting" "topLevelMessage"} ?node-name)]
                             [(contains? #{"kotlin:String?" "kotlin:String"} ?type-id)]]
                           db)))))

          (testing "call expressions, safe calls, Elvis expressions, and features are queryable"
            (is (= #{["uppercase" false]
                     ["trim" false]}
                   (set (d/q '[:find ?name ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/function-call]
                               [?ref :ref/name ?name]
                               [?ref :ref/resolved? ?resolved?]]
                             db))))
            (is (= #{[:kotlin.feature/class :feature.status/supported]
                     [:kotlin.feature/object :feature.status/supported]
                     [:kotlin.feature/companion-object :feature.status/supported]
                     [:kotlin.feature/top-level-declaration :feature.status/supported]
                     [:kotlin.feature/nullable-type :feature.status/supported]
                     [:kotlin.feature/safe-call :feature.status/supported]
                     [:kotlin.feature/elvis-expression :feature.status/supported]
                     [:kotlin.feature/call-expression :feature.status/supported]}
                   (set (d/q '[:find ?kind ?status
                               :where
                               [?feature :feature/kind ?kind]
                               [?feature :feature/status ?status]
                               [(contains? #{:kotlin.feature/class
                                             :kotlin.feature/object
                                             :kotlin.feature/companion-object
                                             :kotlin.feature/top-level-declaration
                                             :kotlin.feature/nullable-type
                                             :kotlin.feature/safe-call
                                             :kotlin.feature/elvis-expression
                                             :kotlin.feature/call-expression}
                                            ?kind)]]
                             db))))
            (is (= #{[:kotlin.node/safe-call "?."]
                     [:kotlin.node/elvis-expression "?:"]}
                   (set (d/q '[:find ?kind ?name
                               :where
                               [?node :node/kind ?kind]
                               [?node :node/name ?name]
                               [(contains? #{:kotlin.node/safe-call :kotlin.node/elvis-expression} ?kind)]]
                             db)))))

          (testing "source spans are captured from Kotlin PSI offsets"
            (is (= #{[9 1 "KotlinGreeter"]
                     [17 3 "greeting"]}
                   (set (d/q '[:find ?line ?column ?name
                               :where
                               [?node :node/name ?name]
                               [?node :node/start-line ?line]
                               [?node :node/start-column ?column]
                               [(contains? #{"KotlinGreeter" "greeting"} ?name)]]
                             db))))
            (is (every? #(re-find #"^sha256:" %)
                        (map first
                             (d/q '[:find ?hash
                                    :where
                                    [?node :node/lang :lang/kotlin]
                                    [?node :node/source-hash ?hash]]
                                  db)))))

          (testing "unchanged reruns keep logical fact counts stable"
            (kotlin-psi/ingest! conn {:project/id "fixture"})
            (is (= counts (entity-counts (d/db conn))))))))))

(deftest enriches-kotlin-refs-with-conservative-semantic-resolution
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/semantic/Semantics.kt"
            opts {:source/root root
                  :project/id "semantic"
                  :project/name "Semantic Fixture"}]
        (write-file! root file-path kotlin-semantic-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "semantic"})
        (let [before-counts (entity-counts (d/db conn))
              first-run (kotlin-psi/enrich! conn {:project/id "semantic"
                                                  :kotlin/classpath-types #{"Locale"}})
              db (d/db conn)]
          (is (= {:project/id "semantic"}
                 (select-keys first-run [:project/id])))
          (is (pos? (:semantic-refs first-run)))

          (testing "project-local function calls resolve to declaration facts"
            (is (= #{["helper" "helper" true]}
                   (set (d/q '[:find ?ref-name ?decl-name ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/function-call]
                               [?ref :ref/name ?ref-name]
                               [?ref :ref/resolved? ?resolved?]
                               [?ref :ref/to-decl ?decl]
                               [?decl :decl/name ?decl-name]
                               [(= ?ref-name "helper")]]
                             db)))))

          (testing "source and declared classpath types resolve deliberately"
            (is (= #{["LocalValue" true]
                     ["Locale" true]
                     ["String" true]}
                   (set (d/q '[:find ?type-name ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/type-use]
                               [?ref :ref/name ?type-name]
                               [?ref :ref/resolved? ?resolved?]
                               [(contains? #{"LocalValue" "Locale" "String"} ?type-name)]]
                             db)))))

          (testing "nullable type-use refs keep nullability after semantic resolution"
            (is (contains?
                 (set (d/q '[:find ?source-name ?type-id ?nullable?
                             :where
                             [?ref :ref/kind :ref.kind/type-use]
                             [?ref :ref/source-name ?source-name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [?type :type/nullable? ?nullable?]
                             [(= ?source-name "value")]]
                           db))
                 ["value" "kotlin:String?" true])))

          (testing "unresolved refs keep explicit reasons"
            (is (= #{["MissingType" :resolve.reason/missing-classpath]
                     ["format" :resolve.reason/missing-classpath]
                     ["choose" :resolve.reason/analysis-api-limitation]}
                   (set (d/q '[:find ?name ?reason
                               :where
                               [?ref :ref/resolved? false]
                               [?ref :ref/name ?name]
                               [?ref :ref/reason ?reason]
                               [(contains? #{"MissingType" "format" "choose"} ?name)]]
                             db)))))

          (testing "semantic reruns keep logical fact counts stable"
            (kotlin-psi/enrich! conn {:project/id "semantic"
                                      :kotlin/classpath-types #{"Locale"}})
            (is (= before-counts (entity-counts (d/db conn))))))))))

(deftest resolves-known-kotlin-api-call-seeds
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/api/ApiCalls.kt"
            opts {:source/root root
                  :project/id "api-calls"
                  :project/name "API Calls"}]
        (write-file! root file-path kotlin-api-call-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "api-calls"})
        (let [first-run (kotlin-psi/enrich! conn {:project/id "api-calls"})
              db (d/db conn)
              target-names #{"trimIndent"
                             "listOf"
                             "assertThat"
                             "isEqualTo"
                             "URI"
                             "resolve"
                             "toUri"}
              resolved-calls (set (d/q '[:find ?name ?type-id ?owner-id ?resolved?
                                          :in $ ?target-names
                                          :where
                                          [?ref :ref/kind :ref.kind/function-call]
                                          [?ref :ref/name ?name]
                                          [(contains? ?target-names ?name)]
                                          [?ref :ref/resolved? ?resolved?]
                                          [?ref :ref/to-type ?type]
                                          [?type :type/id ?type-id]
                                          [?ref :ref/owner-type ?owner]
                                          [?owner :type/id ?owner-id]]
                                        db
                                        target-names))
              unresolved-targets (d/q '[:find ?name ?reason
                                        :in $ ?target-names
                                        :where
                                        [?ref :ref/resolved? false]
                                        [?ref :ref/name ?name]
                                        [(contains? ?target-names ?name)]
                                        [?ref :ref/reason ?reason]]
                                      db
                                      target-names)
              ranked-targets (filter #(contains? target-names (:name %))
                                     (:unresolved-api-call-rankings (inventory/summary db)))]
          (is (pos? (:type-stubs first-run)))
          (testing "generic Kotlin type-use facts keep ordered type arguments"
            (is (= #{["kotlin:List<kotlin:URI>" "List" 0 "kotlin:URI" "URI"]}
                   (set (d/q '[:find ?type-id ?type-name ?ordinal ?arg-id ?arg-name
                               :where
                               [?type :type/id ?type-id]
                               [?type :type/name ?type-name]
                               [?type :type/args ?arg]
                               [?arg :type.arg/ordinal ?ordinal]
                               [?arg :type.arg/type ?arg-type]
                               [?arg-type :type/id ?arg-id]
                               [?arg-type :type/name ?arg-name]
                               [(= ?type-id "kotlin:List<kotlin:URI>")]]
                             db)))))
          (testing "generic root collection types resolve through Kotlin stdlib aliases"
            (is (contains?
                 (set (d/q '[:find ?name ?type-id ?resolved?
                             :where
                             [?ref :ref/kind :ref.kind/type-use]
                             [?ref :ref/name ?name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [?ref :ref/resolved? ?resolved?]
                             [(= ?name "List")]]
                           db))
                 ["List" "kotlin.collections.List" true])))
          (testing "function body shape is queryable for later deterministic emission"
            (let [body-values (set (d/q '[:find ?kind ?name ?value
                                           :where
                                           [?node :node/kind ?kind]
                                           [?node :node/name ?name]
                                           [?node :node/value ?value]
                                           [(contains? #{:kotlin.node/local-property
                                                         :kotlin.node/return}
                                                        ?kind)]]
                                         db))]
              (is (contains? (set (map (juxt first second) body-values))
                             [:kotlin.node/local-property "text"]))
              (is (contains? body-values
                             [:kotlin.node/return "return" "URI(\"file:///tmp\").resolve(path.toUri())"]))
              (is (some #(and (= :kotlin.node/local-property (first %))
                              (str/includes? (nth % 2) ".trimIndent()"))
                        body-values)))
            (is (set/subset?
                 #{["resolve" :kotlin.node/call-receiver "URI(\"file:///tmp\")"]
                   ["resolve" :kotlin.node/call-argument "path.toUri()"]
                   ["URI" :kotlin.node/call-argument "\"file:///tmp\""]}
                 (set (d/q '[:find ?call-name ?child-kind ?child-value
                             :where
                             [?call :node/kind :kotlin.node/call-expression]
                             [?call :node/name ?call-name]
                             [?child :node/parent ?call]
                             [?child :node/kind ?child-kind]
                             [?child :node/value ?child-value]
                             [(contains? #{"resolve" "URI"} ?call-name)]
                             [(contains? #{:kotlin.node/call-receiver
                                           :kotlin.node/call-argument}
                                          ?child-kind)]]
                           db)))))
          (is (= #{["trimIndent" "kotlin:String" "kotlin.text.StringsKt" true]
                   ["listOf" "kotlin.collections.List" "kotlin.collections.CollectionsKt" true]
                   ["assertThat" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.Assertions" true]
                   ["isEqualTo" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["URI" "java.net.URI" "java.net.URI" true]
                   ["resolve" "java.nio.file.Path" "java.nio.file.Path" true]
                   ["toUri" "java.net.URI" "java.nio.file.Path" true]}
                 resolved-calls))
          (is (empty? unresolved-targets))
          (is (empty? ranked-targets)))))))

(deftest extracts-kotlin-object-interface-override-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/overrides/ObjectOverrides.kt"
            opts {:source/root root
                  :project/id "object-overrides"
                  :project/name "Object Overrides"}]
        (write-file! root file-path kotlin-object-overrides-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "object-overrides"})
        (kotlin-psi/enrich! conn {:project/id "object-overrides"})
        (let [db (d/db conn)]
          (testing "Kotlin interfaces and object implementation refs are normalized"
            (is (= #{[:decl.kind/interface "com.acme.overrides.ModuleReader"]
                     [:decl.kind/object "com.acme.overrides.FixtureModuleReader"]}
                   (set (d/q '[:find ?kind ?qname
                               :where
                               [?decl :decl/lang :lang/kotlin]
                               [?decl :decl/kind ?kind]
                               [?decl :decl/qualified-name ?qname]
                               [(contains? #{"com.acme.overrides.ModuleReader"
                                             "com.acme.overrides.FixtureModuleReader"}
                                            ?qname)]]
                             db))))
            (is (= #{["FixtureModuleReader" "kotlin:com.acme.overrides.ModuleReader" "ModuleReader"]}
                   (set (d/q '[:find ?node-name ?type-id ?ref-name
                               :where
                               [?node :node/name ?node-name]
                               [?ref :ref/from-node ?node]
                               [?ref :ref/kind :ref.kind/implements]
                               [?ref :ref/name ?ref-name]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]]
                             db)))))

          (testing "override modifiers and expression values are queryable"
            (is (= #{"isLocal" "scheme" "read" "listElements"}
                   (set (map first
                             (d/q '[:find ?name
                                    :where
                                    [?decl :decl/modifiers :override]
                                    [?decl :decl/name ?name]]
                                  db)))))
            (is (= #{["isLocal" "true"]
                     ["scheme" "\"foo\""]
                     ["read" "\"hello\""]}
                   (set (d/q '[:find ?name ?value
                               :where
                               [?decl :decl/name ?name]
                               [?decl :decl/source-node ?node]
                               [?node :node/value ?value]
                               [(contains? #{"isLocal" "scheme" "read"} ?name)]]
                             db)))))

          (testing "throw bodies are explicit source nodes for rule coverage"
            (is (= #{["throw" "NotImplementedError()"]}
                   (set (d/q '[:find ?name ?value
                               :where
                               [?node :node/kind :kotlin.node/throw]
                               [?node :node/name ?name]
                               [?node :node/value ?value]]
                             db))))))))))
