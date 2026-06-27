(ns vibeformer.datomic.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema])
  (:import (java.util UUID)))

(def unique-ident-fixture
  [{:db/id "project"
    :project/id "project-a"
    :project/name "Project A"
    :project/root "/workspace/project-a"}
   {:db/id "file"
    :file/id "project-a:src/A.java"
    :file/path "src/A.java"
    :file/lang :lang/java
    :file/hash "sha256:file-a"
    :file/project "project"
    :file/package "a"}
   {:db/id "type"
    :type/id "java.lang.String"
    :type/lang :lang/java
    :type/name "java.lang.String"
    :type/nullable? false}
   {:db/id "node"
    :node/id "project-a:src/A.java:class:A"
    :node/lang :lang/java
    :node/kind :java.node/class
    :node/name "A"
    :node/file "file"
    :node/ordinal 0
    :node/start-line 1
    :node/start-column 1
    :node/end-line 3
    :node/end-column 2
    :node/source-hash "sha256:node-a"}
   {:db/id "decl"
    :decl/id "java:a.A"
    :decl/lang :lang/java
    :decl/kind :decl.kind/class
    :decl/name "A"
    :decl/qualified-name "a.A"
    :decl/source-node "node"
    :decl/type "type"
    :decl/modifiers #{:public}}
   {:db/id "ref"
    :ref/id "project-a:src/A.java:ref:String"
    :ref/kind :ref.kind/type-use
    :ref/from-node "node"
    :ref/to-decl "decl"
    :ref/to-type "type"
    :ref/name "String"
    :ref/role :return-type
    :ref/source-name "value"
    :ref/owner-type "type"
    :ref/resolved? true}
   {:db/id "feature"
    :feature/id "project-a:src/A.java:feature:class"
    :feature/lang :lang/java
    :feature/kind :java.feature/class
    :feature/node "node"
    :feature/status :feature.status/supported
    :feature/severity :feature.severity/info}])

(defn- with-empty-db [f]
  (let [system (str "vibeformer-schema-test-" (UUID/randomUUID))
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

(deftest schema-transacts-into-fresh-datomic-local-db
  (with-empty-db
    (fn [conn]
      (is (:db-after (schema/install! conn)))
      (let [idents (set (map first (d/q '[:find ?ident
                                          :in $ [?ident ...]
                                          :where
                                          [_ :db/ident ?ident]]
                                        (d/db conn)
	                                        [:project/id
	                                         :file/id
	                                         :node/id
	                                         :decl/id
	                                         :type-param/id
	                                         :type/id
	                                         :ref/id
	                                         :feature/id
	                                         :rule/id
	                                         :dest.project/id
	                                         :dest.item/id
	                                         :dest.dependency/id])))]
	        (is (= #{:project/id
	                 :file/id
	                 :node/id
	                 :decl/id
	                 :type-param/id
	                 :type/id
	                 :ref/id
	                 :feature/id
	                 :rule/id
	                 :dest.project/id
	                 :dest.item/id
	                 :dest.dependency/id}
	               idents))))))

(deftest unique-identity-attrs-support-lookup-refs
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (d/transact conn {:tx-data unique-ident-fixture})
      (let [db (d/db conn)]
        (is (= "Project A" (:project/name (d/pull db [:project/name] [:project/id "project-a"]))))
        (is (= "src/A.java" (:file/path (d/pull db [:file/path] [:file/id "project-a:src/A.java"]))))
        (is (= "A" (:node/name (d/pull db [:node/name] [:node/id "project-a:src/A.java:class:A"]))))
        (is (= "a.A" (:decl/qualified-name (d/pull db [:decl/qualified-name] [:decl/id "java:a.A"]))))
        (is (= "java.lang.String" (:type/name (d/pull db [:type/name] [:type/id "java.lang.String"]))))
        (is (= {:ref/name "String"
                :ref/role :return-type
                :ref/source-name "value"}
               (d/pull db [:ref/name :ref/role :ref/source-name]
                       [:ref/id "project-a:src/A.java:ref:String"])))
        (is (= :java.feature/class (:feature/kind (d/pull db [:feature/kind] [:feature/id "project-a:src/A.java:feature:class"]))))))))

(deftest identity-retransacts-update-logical-records
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (d/transact conn {:tx-data unique-ident-fixture})
      (d/transact conn {:tx-data [{:file/id "project-a:src/A.java"
                                   :file/hash "sha256:file-a-updated"}
                                  {:node/id "project-a:src/A.java:class:A"
                                   :node/name "RenamedA"}]})
      (let [db (d/db conn)]
        (is (= 1 (ffirst (d/q '[:find (count ?file)
                                :where
                                [?file :file/id "project-a:src/A.java"]]
                              db))))
        (is (= "sha256:file-a-updated"
               (:file/hash (d/pull db [:file/hash] [:file/id "project-a:src/A.java"]))))
        (is (= "RenamedA"
               (:node/name (d/pull db [:node/name] [:node/id "project-a:src/A.java:class:A"]))))))))

(deftest ordinal-fields-preserve-ordered-query-shapes
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (d/transact conn
                  {:tx-data [{:db/id "project"
                              :project/id "project-a"}
                             {:db/id "file"
                              :file/id "project-a:src/Box.java"
                              :file/path "src/Box.java"
                              :file/lang :lang/java
                              :file/project "project"}
                             {:db/id "node-a"
                              :node/id "project-a:src/Box.java:field:first"
                              :node/lang :lang/java
                              :node/kind :java.node/field
                              :node/name "first"
                              :node/file "file"
                              :node/ordinal 0}
                             {:db/id "node-b"
                              :node/id "project-a:src/Box.java:field:second"
                              :node/lang :lang/java
                              :node/kind :java.node/field
                              :node/name "second"
                              :node/file "file"
                              :node/ordinal 1}
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
                             {:db/id "map-type"
                              :type/id "java.util.Map<java.lang.String,java.lang.Integer>"
                              :type/lang :lang/java
                              :type/name "java.util.Map"
                              :type/nullable? false
                              :type/args [{:type.arg/ordinal 0
                                           :type.arg/type "string-type"}
                                          {:type.arg/ordinal 1
                                           :type.arg/type "integer-type"}]}
                             {:db/id "box-decl"
                              :decl/id "java:Box"
                              :decl/lang :lang/java
                              :decl/kind :decl.kind/class
                              :decl/name "Box"
                              :decl/qualified-name "Box"
                              :decl/source-node "node-a"
                              :decl/type-params [{:type-param/id "java:Box:type-param:0:T"
                                                  :type-param/ordinal 0
                                                  :type-param/name "T"}
                                                 {:type-param/id "java:Box:type-param:1:U"
                                                  :type-param/ordinal 1
                                                  :type-param/name "U"}]}]})
      (let [db (d/db conn)
            nodes (sort-by first (d/q '[:find ?ordinal ?name
                                        :where
                                        [?node :node/file [:file/id "project-a:src/Box.java"]]
                                        [?node :node/ordinal ?ordinal]
                                        [?node :node/name ?name]]
                                      db))
            type-args (sort-by first (d/q '[:find ?ordinal ?type-name
                                            :where
                                            [?type :type/id "java.util.Map<java.lang.String,java.lang.Integer>"]
                                            [?type :type/args ?arg]
                                            [?arg :type.arg/ordinal ?ordinal]
                                            [?arg :type.arg/type ?arg-type]
                                            [?arg-type :type/name ?type-name]]
                                          db))
            type-params (sort-by first (d/q '[:find ?ordinal ?name
                                              :where
                                              [?decl :decl/id "java:Box"]
                                              [?decl :decl/type-params ?param]
                                              [?param :type-param/ordinal ?ordinal]
                                              [?param :type-param/name ?name]]
                                            db))]
        (is (= [[0 "first"] [1 "second"]] nodes))
        (is (= [[0 "java.lang.String"] [1 "java.lang.Integer"]] type-args))
        (is (= [[0 "T"] [1 "U"]] type-params))))))
