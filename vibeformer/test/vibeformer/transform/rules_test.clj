(ns vibeformer.transform.rules-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.transform.rules :as rules])
  (:import (java.util UUID)))

(defn- with-empty-db [f]
  (let [system (str "vibeformer-rules-test-" (UUID/randomUUID))
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

(def source-fixture
  [{:db/id "project"
    :project/id "fixture"
    :project/name "Fixture"
    :project/root "/workspace/fixture"}
   {:db/id "file"
    :file/id "fixture:src/A.java"
    :file/path "src/A.java"
    :file/lang :lang/java
    :file/hash "sha256:file"
    :file/project "project"
    :file/package "a"}
   {:db/id "class-node"
    :node/id "fixture:src/A.java:class:A"
    :node/lang :lang/java
    :node/kind :java.node/class
    :node/name "A"
    :node/file "file"
    :node/ordinal 0
    :node/start-line 1
    :node/start-column 1
    :node/end-line 3
    :node/end-column 2}
   {:db/id "stream-node"
    :node/id "fixture:src/A.java:call:stream"
    :node/lang :lang/java
    :node/kind :java.node/method-call
    :node/name "stream"
    :node/file "file"
    :node/parent "class-node"
    :node/ordinal 1
    :node/start-line 2
    :node/start-column 5
    :node/end-line 2
    :node/end-column 22}
   {:db/id "class-feature"
    :feature/id "fixture:src/A.java:feature:class"
    :feature/lang :lang/java
    :feature/kind :java.feature/class
    :feature/node "class-node"
    :feature/status :feature.status/supported
    :feature/severity :feature.severity/info}
   {:db/id "stream-feature"
    :feature/id "fixture:src/A.java:feature:stream"
    :feature/lang :lang/java
    :feature/kind :java.feature/stream-api
    :feature/node "stream-node"
    :feature/status :feature.status/unsupported
    :feature/severity :feature.severity/medium}])

(def implemented-rules
  [{:rule/id :java.class-node/to-csharp-class
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/class
    :rule/status :rule.status/implemented
    :rule/version 1}
   {:rule/id :java.method-call/to-csharp-call
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/method-call
    :rule/status :rule.status/implemented
    :rule/version 1}
   {:rule/id :java.class-feature/to-csharp-class
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/class
    :rule/output-feature :csharp.feature/class
    :rule/status :rule.status/implemented
    :rule/version 2}
   {:rule/id :java.stream/to-linq
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/stream-api
    :rule/output-feature :csharp.feature/linq
    :rule/status :rule.status/implemented
    :rule/version 1}])

(defn- install-source! [conn]
  (schema/install! conn)
  (d/transact conn {:tx-data source-fixture}))

(deftest initial-java-rule-catalog-covers-word-count-construct-kinds
  (let [node-rules (filter :rule/input-kind rules/initial-java-rules)
        feature-rules (filter :rule/input-feature rules/initial-java-rules)
        rules-by-kind (group-by :rule/input-kind node-rules)
        rules-by-feature (group-by :rule/input-feature feature-rules)
        special-api-rules #{:java.regex-pattern-compile/to-csharp-regex
                            :java.string-trim/to-csharp-trim
                            :java.string-is-empty/to-csharp-is-null-or-empty
                            :java.regex-split/to-csharp-regex-split
                            :java.printstream-println/to-csharp-console
                            :java.system-exit/to-csharp-environment-exit
                            :java.path-of/to-csharp-string-path
                            :java.files-read-string/to-csharp-file-read-all-text}]
    (is (= #{:java.node/class
             :java.node/array-read
             :java.node/binary-operator
             :java.node/constructor
             :java.node/expression
             :java.node/field
             :java.node/field-read
             :java.node/if-statement
             :java.node/literal
             :java.node/local-variable
             :java.node/method
             :java.node/method-call
             :java.node/return-statement
             :java.node/statement
             :java.node/type-access
             :java.node/variable-read}
           (set (remove nil? (keys rules-by-kind)))))
    (is (= #{:java.feature/class
             :java.feature/field
             :java.feature/package-private-member
             :java.feature/checked-exception
             :java.api/pattern-compile
             :java.api/string-trim
             :java.api/string-is-empty
             :java.api/pattern-split
             :java.api/printstream-println
             :java.api/system-exit
             :java.api/path-of
             :java.api/files-read-string}
           (set (remove nil? (keys rules-by-feature)))))
    (is (every? #(= 1 (count %)) (vals rules-by-kind)))
    (is (every? #(= 1 (count %)) (vals rules-by-feature)))
    (is (= :rule.status/unsupported
           (:rule/status (first (rules-by-feature :java.feature/checked-exception)))))
    (is (= #{:java.statement-node/to-csharp-stub
             :java.expression-node/to-csharp-stub}
           (set (map :rule/id
                     (filter #(= :rule.status/stubbed (:rule/status %))
                             rules/initial-java-rules)))))
    (is (= #{:java.class-node/to-csharp-class
             :java.array-read-node/to-csharp-indexer
             :java.binary-operator-node/to-csharp-binary
             :java.constructor-node/to-csharp-constructor
             :java.field-node/to-csharp-field
             :java.field-read-node/to-csharp-member
             :java.if-statement-node/to-csharp-if
             :java.literal-node/to-csharp-literal
             :java.local-variable-node/to-csharp-local
             :java.method-node/to-csharp-method
             :java.method-call-node/to-csharp-invocation
             :java.return-statement-node/to-csharp-return
             :java.type-access-node/to-csharp-type
             :java.variable-read-node/to-csharp-variable
             :java.class-feature/to-csharp-class
             :java.field-feature/to-csharp-field
             :java.package-private-member/to-csharp-internal}
           (set (map :rule/id
                     (remove #(contains? special-api-rules (:rule/id %))
                             (filter #(= :rule.status/implemented (:rule/status %))
                                     rules/initial-java-rules))))))
    (is (= special-api-rules
           (set (map :rule/id
                     (filter #(contains? special-api-rules (:rule/id %))
                             rules/initial-java-rules)))))
    (is (= (set rules/initial-java-rules)
           (set (map #(dissoc % :rule/version)
                     (rules/tx-data rules/initial-java-rules)))))))

(deftest rules-can-be-registered-and-queried
  (with-empty-db
    (fn [conn]
      (install-source! conn)
      (rules/register! conn implemented-rules)
      (let [db (d/db conn)]
        (is (= [{:rule/id :java.method-call/to-csharp-call
                 :rule/source-lang :lang/java
                 :rule/input-kind :java.node/method-call
                 :rule/status :rule.status/implemented
                 :rule/version 1}]
               (rules/rules-for-node-kind db :lang/java :java.node/method-call)))
        (is (= [{:rule/id :java.stream/to-linq
                 :rule/source-lang :lang/java
                 :rule/input-feature :java.feature/stream-api
                 :rule/output-feature :csharp.feature/linq
                 :rule/status :rule.status/implemented
                 :rule/version 1}]
               (rules/rules-for-feature-kind db :lang/java :java.feature/stream-api)))))))

(deftest coverage-gate-passes-for-implemented-rules
  (with-empty-db
    (fn [conn]
      (install-source! conn)
      (rules/register! conn implemented-rules)
      (is (= {:ok? true :failures []}
             (rules/coverage-report (d/db conn))))
      (is (= {:ok? true :failures []}
             (rules/assert-coverage! (d/db conn)))))))

(deftest coverage-gate-reports-missing-rules-with-source-context
  (with-empty-db
    (fn [conn]
      (install-source! conn)
      (rules/register! conn (remove #(= :java.stream/to-linq (:rule/id %)) implemented-rules))
      (let [{:keys [ok? failures]} (rules/coverage-report (d/db conn))
            missing (first failures)]
        (is (false? ok?))
        (is (= 1 (count failures)))
        (is (= :coverage.reason/missing-rule (:coverage/reason missing)))
        (is (= :coverage.input/feature (:coverage/input missing)))
        (is (= :java.feature/stream-api (:source/kind missing)))
        (is (= {:file/id "fixture:src/A.java"
                :file/path "src/A.java"
                :file/lang :lang/java}
               (:source/file missing)))
        (is (= {:start-line 2
                :start-column 5
                :end-line 2
                :end-column 22}
               (:source/span missing)))))))

(deftest coverage-gate-enforces-explicit-stub-and-unsupported-modes
  (with-empty-db
    (fn [conn]
      (install-source! conn)
      (rules/register! conn (map (fn [rule]
                                   (if (= :java.stream/to-linq (:rule/id rule))
                                     (assoc rule :rule/status :rule.status/stubbed)
                                     rule))
                                 implemented-rules))
      (testing "stubbed rules fail unless stub mode is explicit"
        (let [{:keys [ok? failures]} (rules/coverage-report (d/db conn))]
          (is (false? ok?))
          (is (= [:coverage.reason/stubbed-rule]
                 (mapv :coverage/reason failures)))))
      (testing "stubbed rules pass when allowed"
        (is (= {:ok? true :failures []}
               (rules/coverage-report (d/db conn) {:allow-stubs? true}))))))
  (with-empty-db
    (fn [conn]
      (install-source! conn)
      (rules/register! conn (map (fn [rule]
                                   (if (= :java.stream/to-linq (:rule/id rule))
                                     (assoc rule :rule/status :rule.status/unsupported)
                                     rule))
                                 implemented-rules))
      (testing "unsupported rules fail unless unsupported mode is explicit"
        (let [{:keys [ok? failures]} (rules/coverage-report (d/db conn))]
          (is (false? ok?))
          (is (= [:coverage.reason/unsupported-rule]
                 (mapv :coverage/reason failures)))))
      (testing "unsupported rules pass when allowed"
        (is (= {:ok? true :failures []}
               (rules/coverage-report (d/db conn) {:allow-unsupported? true})))))))

(deftest coverage-gate-reports-ambiguous-rules
  (with-empty-db
    (fn [conn]
      (install-source! conn)
      (rules/register! conn
                       (conj implemented-rules
                             {:rule/id :java.stream/to-linq-alt
                              :rule/source-lang :lang/java
                              :rule/input-feature :java.feature/stream-api
                              :rule/output-feature :csharp.feature/linq
                              :rule/status :rule.status/implemented
                              :rule/version 1}))
      (let [{:keys [ok? failures]} (rules/coverage-report (d/db conn))
            ambiguous (first failures)]
        (is (false? ok?))
        (is (= 1 (count failures)))
        (is (= :coverage.reason/ambiguous-rule (:coverage/reason ambiguous)))
        (is (= #{:java.stream/to-linq :java.stream/to-linq-alt}
               (set (map :rule/id (:coverage/rules ambiguous)))))))))
