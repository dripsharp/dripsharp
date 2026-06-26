(ns vibeformer.transform.rules
  (:require [datomic.client.api :as d]))

(def implemented-status :rule.status/implemented)
(def stubbed-status :rule.status/stubbed)
(def unsupported-status :rule.status/unsupported)

(def initial-java-rules
  "Initial Java rule catalog for sample coverage checks.

  These entries deliberately mark not-yet-emitted Java constructs as stubbed
  and known source incompatibilities as unsupported so coverage reports point
  at an explicit rule instead of a missing catalog entry."
  [{:rule/id :java.class-node/to-csharp-class
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/class
    :rule/status implemented-status}
   {:rule/id :java.interface-node/to-csharp-interface
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/interface
    :rule/status implemented-status}
   {:rule/id :java.enum-node/to-csharp-enum
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/enum
    :rule/status implemented-status}
   {:rule/id :java.record-node/to-csharp-record
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/record
    :rule/status implemented-status}
   {:rule/id :java.record-component-node/to-csharp-parameter
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/record-component
    :rule/status implemented-status}
   {:rule/id :java.assignment-node/to-csharp-assignment
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/assignment
    :rule/status implemented-status}
   {:rule/id :java.constructor-node/to-csharp-constructor
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/constructor
    :rule/status implemented-status}
   {:rule/id :java.field-node/to-csharp-field
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/field
    :rule/status implemented-status}
   {:rule/id :java.method-node/to-csharp-method
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/method
    :rule/status implemented-status}
   {:rule/id :java.method-call-node/to-csharp-invocation
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/method-call
    :rule/status implemented-status}
   {:rule/id :java.object-creation-node/to-csharp-new
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/object-creation
    :rule/status implemented-status}
   {:rule/id :java.local-variable-node/to-csharp-local
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/local-variable
    :rule/status implemented-status}
   {:rule/id :java.return-statement-node/to-csharp-return
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/return-statement
    :rule/status implemented-status}
   {:rule/id :java.if-statement-node/to-csharp-if
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/if-statement
    :rule/status implemented-status}
   {:rule/id :java.literal-node/to-csharp-literal
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/literal
    :rule/status implemented-status}
   {:rule/id :java.variable-read-node/to-csharp-variable
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/variable-read
    :rule/status implemented-status}
   {:rule/id :java.field-read-node/to-csharp-member
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/field-read
    :rule/status implemented-status}
   {:rule/id :java.field-write-node/to-csharp-member
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/field-write
    :rule/status implemented-status}
   {:rule/id :java.this-node/to-csharp-this
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/this
    :rule/status implemented-status}
   {:rule/id :java.throw-statement-node/to-csharp-throw
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/throw-statement
    :rule/status implemented-status}
   {:rule/id :java.type-pattern-node/to-csharp-pattern
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/type-pattern
    :rule/status implemented-status}
   {:rule/id :java.type-access-node/to-csharp-type
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/type-access
    :rule/status implemented-status}
   {:rule/id :java.variable-write-node/to-csharp-variable
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/variable-write
    :rule/status implemented-status}
   {:rule/id :java.array-read-node/to-csharp-indexer
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/array-read
    :rule/status implemented-status}
   {:rule/id :java.binary-operator-node/to-csharp-binary
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/binary-operator
    :rule/status implemented-status}
   {:rule/id :java.conditional-expression-node/to-csharp-conditional
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/conditional-expression
    :rule/status implemented-status}
   {:rule/id :java.unary-operator-node/to-csharp-unary
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/unary-operator
    :rule/status implemented-status}
   {:rule/id :java.switch-expression-node/to-csharp-switch
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/switch-expression
    :rule/status implemented-status}
   {:rule/id :java.switch-case-node/to-csharp-switch-arm
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/switch-case
    :rule/status implemented-status}
   {:rule/id :java.regex-pattern-compile/to-csharp-regex
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/pattern-compile
    :rule/output-feature :csharp.api/regex
    :rule/status implemented-status}
   {:rule/id :java.string-trim/to-csharp-trim
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/string-trim
    :rule/output-feature :csharp.api/string-trim
    :rule/status implemented-status}
   {:rule/id :java.string-is-empty/to-csharp-is-null-or-empty
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/string-is-empty
    :rule/output-feature :csharp.api/string-is-null-or-empty
    :rule/status implemented-status}
   {:rule/id :java.regex-split/to-csharp-regex-split
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/pattern-split
    :rule/output-feature :csharp.api/regex-split
    :rule/status implemented-status}
   {:rule/id :java.printstream-println/to-csharp-console
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/printstream-println
    :rule/output-feature :csharp.api/console-write-line
    :rule/status implemented-status}
   {:rule/id :java.system-exit/to-csharp-environment-exit
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/system-exit
    :rule/output-feature :csharp.api/environment-exit
    :rule/status implemented-status}
   {:rule/id :java.path-of/to-csharp-string-path
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/path-of
    :rule/output-feature :csharp.api/string-path
    :rule/status implemented-status}
   {:rule/id :java.files-read-string/to-csharp-file-read-all-text
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/files-read-string
    :rule/output-feature :csharp.api/file-read-all-text
    :rule/status implemented-status}
   {:rule/id :java.integer-to-string/to-csharp-convert-to-string
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/integer-to-string
    :rule/output-feature :csharp.api/convert-to-string
    :rule/status implemented-status}
   {:rule/id :java.statement-node/to-csharp-stub
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/statement
    :rule/status stubbed-status}
   {:rule/id :java.expression-node/to-csharp-stub
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/expression
    :rule/status stubbed-status}
   {:rule/id :java.class-feature/to-csharp-class
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/class
    :rule/output-feature :csharp.feature/class
    :rule/status implemented-status}
   {:rule/id :java.interface-feature/to-csharp-interface
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/interface
    :rule/output-feature :csharp.feature/interface
    :rule/status implemented-status}
   {:rule/id :java.enum-feature/to-csharp-enum
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/enum
    :rule/output-feature :csharp.feature/enum
    :rule/status implemented-status}
   {:rule/id :java.record-feature/to-csharp-record
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/record
    :rule/output-feature :csharp.feature/record
    :rule/status implemented-status}
   {:rule/id :java.record-component-feature/to-csharp-parameter
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/record-component
    :rule/output-feature :csharp.feature/record-parameter
    :rule/status implemented-status}
   {:rule/id :java.generic-method-feature/to-csharp-generic-method
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/generic-method
    :rule/output-feature :csharp.feature/generic-method
    :rule/status implemented-status}
   {:rule/id :java.field-feature/to-csharp-field
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/field
    :rule/output-feature :csharp.feature/field
    :rule/status implemented-status}
   {:rule/id :java.package-private-member/to-csharp-internal
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/package-private-member
    :rule/output-feature :csharp.feature/internal-member
    :rule/status implemented-status}
   {:rule/id :java.checked-exception/to-csharp-unchecked-signature
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/checked-exception
    :rule/output-feature :csharp.feature/unchecked-exception-signature
    :rule/status implemented-status}
   {:rule/id :java.synchronized-method/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/synchronized-method
    :rule/status unsupported-status}])

(defn- require-key [rule k]
  (when-not (contains? rule k)
    (throw (ex-info (str "Transform rule is missing " k ".")
                    {:rule rule
                     :missing-key k}))))

(defn normalize-rule
  "Validates a transform rule map and fills deterministic defaults."
  [rule]
  (doseq [k [:rule/id :rule/source-lang :rule/status]]
    (require-key rule k))
  (let [has-node-kind? (contains? rule :rule/input-kind)
        has-feature? (contains? rule :rule/input-feature)]
    (when (= has-node-kind? has-feature?)
      (throw (ex-info "Transform rule must declare exactly one input selector."
                      {:rule rule
                       :selectors [:rule/input-kind :rule/input-feature]}))))
  (update rule :rule/version #(long (or % 1))))

(defn tx-data
  "Returns normalized Datomic tx-data for transform rule definitions."
  [rules]
  (->> rules
       (map normalize-rule)
       (sort-by :rule/id)
       vec))

(defn register!
  "Transacts transform rule definitions into a Datomic connection."
  [conn rules]
  (d/transact conn {:tx-data (tx-data rules)}))

(defn- pull-rules [db query lang kind]
  (->> (d/q query db lang kind)
       (map first)
       (sort-by :rule/id)
       vec))

(defn rules-for-node-kind
  "Returns transform rules for a source language and node kind."
  [db lang kind]
  (pull-rules db
              '[:find (pull ?rule [:rule/id
                                    :rule/source-lang
                                    :rule/input-kind
                                    :rule/output-feature
                                    :rule/status
                                    :rule/version])
                :in $ ?lang ?kind
                :where
                [?rule :rule/source-lang ?lang]
                [?rule :rule/input-kind ?kind]]
              lang
              kind))

(defn rules-for-feature-kind
  "Returns transform rules for a source language and feature kind."
  [db lang kind]
  (pull-rules db
              '[:find (pull ?rule [:rule/id
                                    :rule/source-lang
                                    :rule/input-feature
                                    :rule/output-feature
                                    :rule/status
                                    :rule/version])
                :in $ ?lang ?kind
                :where
                [?rule :rule/source-lang ?lang]
                [?rule :rule/input-feature ?kind]]
              lang
              kind))

(defn- file-summary [file]
  {:file/id (:file/id file)
   :file/path (:file/path file)
   :file/lang (:file/lang file)})

(defn- source-span [source]
  {:start-line (:node/start-line source)
   :start-column (:node/start-column source)
   :end-line (:node/end-line source)
   :end-column (:node/end-column source)})

(defn- node-constructs [db]
  (->> (d/q '[:find (pull ?node [:node/id
                                  :node/lang
                                  :node/kind
                                  :node/start-line
                                  :node/start-column
                                  :node/end-line
                                  :node/end-column
                                  {:node/file [:file/id :file/path :file/lang]}])
              :where [?node :node/id]]
            db)
       (map (fn [[node]]
              {:coverage/input :coverage.input/node
               :source/id (:node/id node)
               :source/lang (:node/lang node)
               :source/kind (:node/kind node)
               :source/file (file-summary (:node/file node))
               :source/span (source-span node)}))
       (sort-by (juxt :source/lang :source/kind :source/id))
       vec))

(defn- feature-constructs [db]
  (->> (d/q '[:find (pull ?feature [:feature/id
                                      :feature/lang
                                      :feature/kind
                                      :feature/status
                                      {:feature/node [:node/id
                                                      :node/start-line
                                                      :node/start-column
                                                      :node/end-line
                                                      :node/end-column
                                                      {:node/file [:file/id :file/path :file/lang]}]}])
              :where [?feature :feature/id]]
            db)
       (map (fn [[feature]]
              (let [node (:feature/node feature)]
                {:coverage/input :coverage.input/feature
                 :source/id (:feature/id feature)
                 :source/lang (:feature/lang feature)
                 :source/kind (:feature/kind feature)
                 :source/status (:feature/status feature)
                 :source/node-id (:node/id node)
                 :source/file (file-summary (:node/file node))
                 :source/span (source-span node)})))
       (sort-by (juxt :source/lang :source/kind :source/id))
       vec))

(defn source-constructs
  "Returns all node and feature constructs that must be covered before emission."
  [db]
  (vec (concat (node-constructs db) (feature-constructs db))))

(defn- applicable-rules [db {:coverage/keys [input] :source/keys [lang kind]}]
  (case input
    :coverage.input/node (rules-for-node-kind db lang kind)
    :coverage.input/feature (rules-for-feature-kind db lang kind)))

(defn- allowed-statuses [{:keys [allow-stubs? allow-unsupported?]}]
  (cond-> #{implemented-status}
    allow-stubs? (conj stubbed-status)
    allow-unsupported? (conj unsupported-status)))

(defn- failure [construct reason rules]
  (assoc construct
         :coverage/reason reason
         :coverage/rules (mapv #(select-keys % [:rule/id :rule/status :rule/version])
                               rules)))

(defn- coverage-failure [allowed construct rules]
  (cond
    (empty? rules)
    (failure construct :coverage.reason/missing-rule [])

    (< 1 (count rules))
    (failure construct :coverage.reason/ambiguous-rule rules)

    (contains? allowed (:rule/status (first rules)))
    nil

    (= stubbed-status (:rule/status (first rules)))
    (failure construct :coverage.reason/stubbed-rule rules)

    (= unsupported-status (:rule/status (first rules)))
    (failure construct :coverage.reason/unsupported-rule rules)

    :else
    (failure construct :coverage.reason/unimplemented-rule rules)))

(defn coverage-report
  "Checks source constructs against registered transform rules.

  By default only implemented rules pass. Set :allow-stubs? or
  :allow-unsupported? to deliberately cross those mode boundaries."
  ([db]
   (coverage-report db {}))
  ([db opts]
   (let [allowed (allowed-statuses opts)
         failures (->> (source-constructs db)
                       (keep (fn [construct]
                               (coverage-failure allowed
                                                 construct
                                                 (applicable-rules db construct))))
                       vec)]
     {:ok? (empty? failures)
      :failures failures})))

(defn assert-coverage!
  "Throws with an actionable report when transform rule coverage is incomplete."
  ([db]
   (assert-coverage! db {}))
  ([db opts]
   (let [{:keys [ok?] :as report} (coverage-report db opts)]
     (when-not ok?
       (throw (ex-info "Transform rule coverage failed." report)))
     report)))
