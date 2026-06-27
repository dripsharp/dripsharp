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
                            :java.string-length/to-csharp-length
                            :java.string-code-points/to-csharp-rune-values
                            :java.regex-split/to-csharp-regex-split
                            :java.printstream-println/to-csharp-console
                            :java.system-exit/to-csharp-environment-exit
                            :java.path-of/to-csharp-string-path
                            :java.files-read-string/to-csharp-file-read-all-text
                            :java.integer-to-string/to-csharp-convert-to-string
                            :java.objects-require-non-null/to-csharp-null-check
                            :java.objects-equals/to-csharp-object-equals
                            :java.objects-hash/to-csharp-hash-code-combine
                            :java.math-round/to-csharp-java-round
                            :java.math-min/to-csharp-math-min
                            :java.math-max/to-csharp-math-max
                            :java.double-hash-code/to-csharp-get-hash-code
                            :java.class-type-literal/to-csharp-typeof
                            :java.class-get-type-name/to-csharp-full-name
                            :java.class-get-name/to-csharp-full-name
                            :java.class-get-simple-name/to-csharp-name
                            :java.class-get-modifiers/to-csharp-attributes
                            :java.class-is-assignable-from/to-csharp-is-assignable-from
                            :java.class-is-array/to-csharp-is-array
                            :java.class-is-primitive/to-csharp-is-primitive
                            :java.class-get-generic-superclass/to-csharp-base-type
                            :java.class-get-type-parameters/to-csharp-generic-arguments
                            :java.class-get-component-type/to-csharp-element-type
                            :java.class-is-enum/to-csharp-is-enum
                            :java.class-get-class-loader/to-csharp-assembly
                            :java.class-cast/to-csharp-cast
                            :java.class-get-resource-as-stream/to-csharp-manifest-resource-stream
                            :java.class-get-declared-methods/to-csharp-get-methods
                            :java.class-get-declared-constructors/to-csharp-get-constructors
                            :java.class-for-name/to-csharp-get-type
                            :java.type-get-type-name/to-csharp-full-name
                            :java.parameterized-type-get-actual-type-arguments/to-csharp-generic-arguments
                            :java.parameterized-type-get-raw-type/to-csharp-type
                            :java.parameterized-type-get-owner-type/to-csharp-declaring-type
                            :java.reflection-executable-get-parameters/to-csharp-get-parameters
                            :java.reflection-constructor-get-parameter-count/to-csharp-parameter-count
                            :java.reflection-parameter-is-name-present/to-csharp-name-check
                            :java.reflection-parameter-get-name/to-csharp-name
                            :java.modifier-is-abstract/to-csharp-type-attributes
                            :java.stream-source/to-csharp-enumerable
                            :java.stream-map/to-csharp-select
                            :java.stream-filter/to-csharp-where
                            :java.stream-flat-map/to-csharp-select-many
                            :java.stream-map-to-int/to-csharp-select
                            :java.stream-map-to-long/to-csharp-select
                            :java.stream-to-list/to-csharp-to-list
                            :java.stream-to-array/to-csharp-to-array
                            :java.stream-count/to-csharp-count
                            :java.stream-sum/to-csharp-sum
                            :java.stream-min/to-csharp-min
                            :java.stream-max/to-csharp-max
                            :java.stream-skip/to-csharp-skip
                            :java.stream-any-match/to-csharp-any
                            :java.stream-all-match/to-csharp-all
                            :java.stream-none-match/to-csharp-not-any
                            :java.stream-find-first/to-csharp-first-or-default
                            :java.stream-distinct/to-csharp-distinct
                            :java.stream-sorted/to-csharp-order-by
                            :java.stream-iterator/to-csharp-enumerator
                            :java.iterator-has-next/to-csharp-move-next
                            :java.primitive-iterator-next-int/to-csharp-current
                            :java.stream-collector-to-list/to-csharp-to-list
                            :java.stream-collector-to-set/to-csharp-to-hash-set
                            :java.stream-collector-joining/to-csharp-string-join
                            :java.stream-collect-to-list/to-csharp-to-list
                            :java.stream-collect-to-set/to-csharp-to-hash-set
                            :java.stream-collect-joining/to-csharp-string-join
                            :java.optional-or-else/to-csharp-default-if-empty-max}]
    (is (= #{:java.node/class
             :java.node/annotation
             :java.node/assignment
             :java.node/array-read
             :java.node/binary-operator
             :java.node/conditional-expression
             :java.node/constructor
             :java.node/enum
             :java.node/expression
             :java.node/field
             :java.node/field-read
             :java.node/field-write
             :java.node/foreach-statement
             :java.node/if-statement
             :java.node/interface
             :java.node/catch-clause
             :java.node/lambda
             :java.node/literal
             :java.node/local-variable
             :java.node/method
             :java.node/method-call
             :java.node/method-reference
             :java.node/object-creation
             :java.node/record
             :java.node/record-component
             :java.node/return-statement
             :java.node/statement
             :java.node/synchronized-block
             :java.node/switch-case
             :java.node/switch-expression
             :java.node/this
             :java.node/throw-statement
             :java.node/try-statement
             :java.node/type-pattern
             :java.node/type-access
             :java.node/type-cast
             :java.node/unary-operator
             :java.node/variable-read
             :java.node/variable-write}
           (set (remove nil? (keys rules-by-kind)))))
    (is (= #{:java.feature/class
             :java.feature/annotation
             :java.feature/field
             :java.feature/generic-method
             :java.feature/interface
             :java.feature/enum
             :java.feature/record
             :java.feature/record-component
             :java.feature/package-private-member
             :java.feature/checked-exception
             :java.feature/lambda
             :java.feature/native-method
             :java.feature/reflection
             :java.feature/stream-api
             :java.feature/synchronized-block
             :java.feature/synchronized-method
             :java.reflection.class/type-literal
             :java.reflection.class/get-type-name
             :java.reflection.class/get-name
             :java.reflection.class/get-simple-name
             :java.reflection.class/get-modifiers
             :java.reflection.class/is-assignable-from
             :java.reflection.class/is-array
             :java.reflection.class/is-primitive
             :java.reflection.class/get-generic-superclass
             :java.reflection.class/get-type-parameters
             :java.reflection.class/get-component-type
             :java.reflection.class/is-enum
             :java.reflection.class/cast
             :java.reflection.class/get-resource-as-stream
             :java.reflection.type/get-type-name
             :java.reflection.parameterized-type/get-actual-type-arguments
             :java.reflection.parameterized-type/get-raw-type
             :java.reflection.parameterized-type/get-owner-type
             :java.reflection.executable/get-parameters
             :java.reflection.parameter/is-name-present
             :java.reflection.parameter/get-name
             :java.reflection.modifier/is-abstract
             :java.reflection.class/for-name
             :java.reflection.class/get-method
             :java.reflection.class/get-declared-method
             :java.reflection.class/get-declared-methods
             :java.reflection.class/get-declared-constructors
             :java.reflection.class/get-class-loader
             :java.reflection.class/get-annotation
             :java.reflection.method/invoke
             :java.reflection.constructor/new-instance
             :java.reflection.constructor/get-annotation
             :java.reflection.parameter/get-annotation
             :java.reflection.wildcard-type/get-lower-bounds
             :java.reflection.wildcard-type/get-upper-bounds
             :java.reflection.constructor/get-parameter-count
             :java.stream/source-to-enumerable
             :java.stream/map
             :java.stream/filter
             :java.stream/flat-map
             :java.stream/map-to-int
             :java.stream/map-to-long
             :java.stream/to-list
             :java.stream/to-array
             :java.stream/count
             :java.stream/sum
             :java.stream/min
             :java.stream/max
             :java.stream/skip
             :java.stream/any-match
             :java.stream/all-match
             :java.stream/none-match
             :java.stream/find-first
             :java.stream/distinct
             :java.stream/sorted
             :java.stream/iterator
             :java.iterator/has-next
             :java.primitive-iterator/next-int
             :java.stream.collector/to-list
             :java.stream.collector/to-set
             :java.stream.collector/joining
             :java.stream.collector/to-map
             :java.stream.collector/to-collection
             :java.stream/collect-to-list
             :java.stream/collect-to-set
             :java.stream/collect-joining
             :java.stream/collect-to-map
             :java.stream/collect-to-collection
             :java.stream/collect
             :java.optional/or-else
             :java.collection/size
             :java.collection/is-empty
             :java.collection/contains
             :java.collection/add
             :java.list/get
             :java.map/get
             :java.map/put
             :java.map/get-or-default
             :java.map/contains-key
             :java.map/contains-value
             :java.map/entry-set
             :java.map/key-set
             :java.map/values
             :java.map-entry/get-key
             :java.map-entry/get-value
             :java.api/pattern-compile
             :java.api/string-trim
             :java.api/string-is-empty
             :java.api/string-length
             :java.api/string-code-points
             :java.api/pattern-split
             :java.api/printstream-println
             :java.api/system-exit
             :java.api/path-of
             :java.api/files-read-string
             :java.api/integer-to-string
             :java.api/objects-require-non-null
             :java.api/objects-equals
             :java.api/objects-hash
             :java.api/math-round
             :java.api/math-min
             :java.api/math-max
             :java.api/double-hash-code}
           (set (remove nil? (keys rules-by-feature)))))
    (is (every? #(= 1 (count %)) (vals rules-by-kind)))
    (is (every? #(= 1 (count %)) (vals rules-by-feature)))
    (is (= :rule.status/implemented
           (:rule/status (first (rules-by-feature :java.feature/checked-exception)))))
    (is (= :rule.status/implemented
           (:rule/status (first (rules-by-feature :java.feature/synchronized-method)))))
    (is (= :rule.status/implemented
           (:rule/status (first (rules-by-feature :java.feature/synchronized-block)))))
    (is (= :rule.status/unsupported
           (:rule/status (first (rules-by-feature :java.feature/native-method)))))
    (is (= :rule.status/implemented
           (:rule/status (first (rules-by-feature :java.reflection.class/for-name)))))
    (doseq [feature [:java.stream.collector/to-map
                     :java.stream.collector/to-collection
                     :java.reflection.class/get-class-loader
                     :java.reflection.class/get-declared-methods
                     :java.reflection.class/get-declared-constructors
                     :java.reflection.class/for-name
                     :java.stream/map-to-int
                     :java.stream/min
                     :java.stream/max
                     :java.stream/skip
                     :java.optional/or-else
                     :java.reflection.wildcard-type/get-lower-bounds
                     :java.reflection.wildcard-type/get-upper-bounds
                     :java.reflection.constructor/get-parameter-count
                     :java.stream/collect-to-map
                     :java.stream/collect-to-collection]]
      (is (= :rule.status/implemented
             (:rule/status (first (rules-by-feature feature))))))
    (doseq [feature [:java.reflection.class/get-declared-method
                     :java.reflection.class/get-method
                     :java.reflection.class/get-annotation
                     :java.reflection.method/invoke
                     :java.reflection.constructor/new-instance
                     :java.reflection.constructor/get-annotation
                     :java.reflection.parameter/get-annotation
                     :java.feature/reflection
                     :java.feature/stream-api
                     :java.stream/collect]]
      (is (= :rule.status/unsupported
             (:rule/status (first (rules-by-feature feature))))))
    (is (= #{:java.statement-node/to-csharp-stub
             :java.expression-node/to-csharp-stub}
           (set (map :rule/id
                     (filter #(= :rule.status/stubbed (:rule/status %))
                             rules/initial-java-rules)))))
    (is (= #{:java.class-node/to-csharp-class
             :java.annotation-node/to-csharp-attribute
             :java.assignment-node/to-csharp-assignment
             :java.array-read-node/to-csharp-indexer
             :java.binary-operator-node/to-csharp-binary
             :java.conditional-expression-node/to-csharp-conditional
             :java.constructor-node/to-csharp-constructor
             :java.enum-node/to-csharp-enum
             :java.field-node/to-csharp-field
             :java.field-read-node/to-csharp-member
             :java.field-write-node/to-csharp-member
             :java.foreach-statement-node/to-csharp-foreach
             :java.generic-method-feature/to-csharp-generic-method
             :java.if-statement-node/to-csharp-if
             :java.interface-node/to-csharp-interface
             :java.catch-clause-node/to-csharp-catch
             :java.lambda-node/to-csharp-lambda
             :java.literal-node/to-csharp-literal
             :java.local-variable-node/to-csharp-local
             :java.method-node/to-csharp-method
             :java.method-call-node/to-csharp-invocation
             :java.method-reference-node/to-csharp-method-reference
             :java.object-creation-node/to-csharp-new
             :java.record-node/to-csharp-record
             :java.record-component-node/to-csharp-parameter
             :java.return-statement-node/to-csharp-return
             :java.switch-case-node/to-csharp-switch-arm
             :java.switch-expression-node/to-csharp-switch
             :java.synchronized-block-node/to-csharp-lock
             :java.synchronized-block/to-csharp-lock
             :java.synchronized-method/to-csharp-lock
             :java.this-node/to-csharp-this
             :java.throw-statement-node/to-csharp-throw
             :java.try-statement-node/to-csharp-try
             :java.type-pattern-node/to-csharp-pattern
             :java.type-access-node/to-csharp-type
             :java.type-cast-node/to-csharp-cast
             :java.unary-operator-node/to-csharp-unary
             :java.variable-read-node/to-csharp-variable
             :java.variable-write-node/to-csharp-variable
             :java.class-feature/to-csharp-class
             :java.annotation-feature/to-csharp-attribute
             :java.field-feature/to-csharp-field
             :java.checked-exception/to-csharp-unchecked-signature
             :java.enum-feature/to-csharp-enum
             :java.interface-feature/to-csharp-interface
             :java.record-feature/to-csharp-record
             :java.record-component-feature/to-csharp-parameter
             :java.package-private-member/to-csharp-internal
             :java.lambda-feature/to-csharp-lambda
             :java.wildcard-type-get-lower-bounds/to-csharp-generic-parameter-constraints
             :java.wildcard-type-get-upper-bounds/to-csharp-generic-parameter-constraints
             :java.collection-size/to-csharp-count
             :java.collection-is-empty/to-csharp-count-check
             :java.collection-contains/to-csharp-contains
             :java.collection-add/to-csharp-add
             :java.list-get/to-csharp-indexer
             :java.map-get/to-csharp-indexer
             :java.map-put/to-csharp-indexer-assignment
             :java.map-get-or-default/to-csharp-get-value-or-default
             :java.map-contains-key/to-csharp-contains-key
             :java.map-contains-value/to-csharp-contains-value
             :java.map-entry-set/to-csharp-dictionary-enumeration
             :java.map-key-set/to-csharp-keys
             :java.map-values/to-csharp-values
             :java.map-entry-get-key/to-csharp-key
             :java.map-entry-get-value/to-csharp-value
             :java.stream-collector-to-map/to-csharp-to-dictionary
             :java.stream-collector-to-collection/to-csharp-collection-constructor
             :java.stream-collect-to-map/to-csharp-to-dictionary
             :java.stream-collect-to-collection/to-csharp-collection-constructor}
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
