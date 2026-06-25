(ns vibeformer.ingest.java-spoon-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.source :as source])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.util UUID)))

(def java-fixture
  "package com.acme.parser;

import java.util.Locale;

class Base {}

interface GreeterApi {
  String greeting(Locale locale);
}

enum GreetingKind {
  FORMAL
}

public final class Greeter extends Base implements GreeterApi {
  private final String name;
  MissingType missing;

  public Greeter(String name) {
    this.name = name;
  }

  @Override
  public String greeting(Locale locale) {
    String normalized = name.toUpperCase(locale);
    missing.doWork();
    return normalized.trim();
  }
}
")

(def canonical-method-call-fixture
  "package com.acme.calls;

public final class Calls {
  private String name;

  public void chain() {
    name.toUpperCase().trim();
  }

  public long streamCount() {
    return java.util.Arrays.asList(name.trim()).stream().count();
  }

  public Class<?> reflected() throws Exception {
    return Class.forName(name);
  }
}
")

(defn- with-empty-db [f]
  (let [system (str "vibeformer-java-spoon-test-" (UUID/randomUUID))
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
  (Files/createTempDirectory "vibeformer-java-spoon-" (make-array java.nio.file.attribute.FileAttribute 0)))

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

(deftest extracts-normalized-java-facts-with-spoon
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/parser/Greeter.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path java-fixture)
        (source/ingest! conn opts)
        (let [first-run (java-spoon/ingest! conn {:project/id "fixture"})
              db (d/db conn)
              counts (entity-counts db)]
          (is (= {:project/id "fixture"
                  :java-files 1}
                 (select-keys first-run [:project/id :java-files])))
          (is (pos? (:transacted-facts first-run)))

          (testing "classes, interfaces, enums, fields, constructors, and methods are queryable"
            (is (set/subset?
                 #{[:java.node/class "Base" :decl.kind/class "com.acme.parser.Base"]
                   [:java.node/class "Greeter" :decl.kind/class "com.acme.parser.Greeter"]
                   [:java.node/interface "GreeterApi" :decl.kind/interface "com.acme.parser.GreeterApi"]
                   [:java.node/enum "GreetingKind" :decl.kind/enum "com.acme.parser.GreetingKind"]
                   [:java.node/field "name" :decl.kind/field "com.acme.parser.Greeter.name"]
                   [:java.node/field "missing" :decl.kind/field "com.acme.parser.Greeter.missing"]
                   [:java.node/constructor "<init>" :decl.kind/constructor "com.acme.parser.Greeter"]}
                 (set (d/q '[:find ?node-kind ?node-name ?decl-kind ?decl-qname
                             :where
                             [?node :node/file [:file/id "fixture:src/main/java/com/acme/parser/Greeter.java"]]
                             [?node :node/kind ?node-kind]
                             [?node :node/name ?node-name]
                             [?decl :decl/source-node ?node]
                             [?decl :decl/kind ?decl-kind]
                             [?decl :decl/qualified-name ?decl-qname]
                             [(contains? #{:java.node/class
                                           :java.node/interface
                                           :java.node/enum
                                           :java.node/field
                                           :java.node/constructor}
                                          ?node-kind)]]
                           db)))))

          (testing "modifiers, return types, and inheritance refs are queryable"
            (is (= #{[:public] [:final]}
                   (set (d/q '[:find ?modifier
                               :where
                               [?decl :decl/id "java:com.acme.parser.Greeter"]
                               [?decl :decl/modifiers ?modifier]]
                             db))))
            (is (= #{["greeting" "java.lang.String"]}
                   (set (d/q '[:find ?name ?type-id
                               :where
                               [?decl :decl/id "java:com.acme.parser.Greeter#greeting(java.util.Locale)"]
                               [?decl :decl/name ?name]
                               [?decl :decl/return-type ?type]
                               [?type :type/id ?type-id]]
                             db))))
            (is (= #{[:ref.kind/extends "com.acme.parser.Base" true]
                     [:ref.kind/implements "com.acme.parser.GreeterApi" true]}
                   (set (d/q '[:find ?kind ?type-id ?resolved?
                               :where
                               [?ref :ref/from-node ?node]
                               [?node :node/name "Greeter"]
                               [?ref :ref/kind ?kind]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]
                               [?ref :ref/resolved? ?resolved?]
                               [(contains? #{:ref.kind/extends :ref.kind/implements} ?kind)]]
                             db)))))

          (testing "parameter type refs retain roles and source names for emission"
            (is (= #{[:param-0 "name" "java.lang.String"]
                     [:param-0 "locale" "java.util.Locale"]}
                   (set (d/q '[:find ?role ?source-name ?type-id
                               :where
                               [?ref :ref/kind :ref.kind/type-use]
                               [?ref :ref/role ?role]
                               [?ref :ref/source-name ?source-name]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]
                               [(contains? #{:param-0} ?role)]]
                             db)))))

          (testing "method calls and unresolved missing-classpath references are explicit"
            (is (= #{["toUpperCase" true]
                     ["trim" true]
                     ["doWork" false]}
                   (set (d/q '[:find ?name ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/method-call]
                               [?ref :ref/name ?name]
                               [?ref :ref/resolved? ?resolved?]]
                             db))))
            (is (= #{["doWork" :resolve.reason/missing-classpath]
                     ["com.acme.parser.MissingType" :resolve.reason/missing-classpath]}
                   (set (d/q '[:find ?name ?reason
                               :where
                               [?ref :ref/resolved? false]
                               [?ref :ref/name ?name]
                               [?ref :ref/reason ?reason]]
                             db)))))

          (testing "feature facts cover the Java declarations"
            (is (= #{[:java.feature/class :feature.status/supported]
                     [:java.feature/interface :feature.status/supported]
                     [:java.feature/enum :feature.status/supported]
                     [:java.feature/field :feature.status/supported]
                     [:java.feature/package-private-member :feature.status/supported]}
                   (set (d/q '[:find ?kind ?status
                               :where
                               [?feature :feature/kind ?kind]
                               [?feature :feature/status ?status]]
                             db)))))

          (testing "unchanged reruns keep logical fact counts stable"
            (java-spoon/ingest! conn {:project/id "fixture"})
            (is (= counts (entity-counts (d/db conn))))))))))

(deftest canonicalizes-method-call-source-nodes
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/calls/Calls.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path canonical-method-call-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              expected-call-counts #{["asList" 1]
                                     ["count" 1]
                                     ["forName" 1]
                                     ["stream" 1]
                                     ["toUpperCase" 1]
                                     ["trim" 2]}]
          (testing "each source invocation has one canonical method-call node"
            (is (= expected-call-counts
                   (set (d/q '[:find ?name (count ?node)
                               :where
                               [?node :node/kind :java.node/method-call]
                               [?node :node/name ?name]]
                             db))))
            (is (empty?
                 (d/q '[:find ?node-id ?name
                        :where
                        [?node :node/kind :java.node/method-call]
                        [?node :node/id ?node-id]
                        [?node :node/name ?name]
                        (not [?node :node/role])]
                      db))))

          (testing "method-call refs attach to the canonical structural nodes"
            (is (= expected-call-counts
                   (set (d/q '[:find ?name (count ?ref)
                               :where
                               [?node :node/kind :java.node/method-call]
                               [?node :node/name ?name]
                               [?ref :ref/from-node ?node]
                               [?ref :ref/kind :ref.kind/method-call]]
                             db)))))

          (testing "invocation feature facts attach to canonical method-call nodes"
            (is (set/subset?
                 #{[:java.feature/reflection "forName" :java.node/method-call]
                   [:java.feature/stream-api "stream" :java.node/method-call]}
                 (set (d/q '[:find ?kind ?node-name ?node-kind
                             :where
                             [?feature :feature/kind ?kind]
                             [(contains? #{:java.feature/reflection
                                           :java.feature/stream-api}
                                          ?kind)]
                             [?feature :feature/node ?node]
                             [?node :node/name ?node-name]
                             [?node :node/kind ?node-kind]]
                           db))))))))))
