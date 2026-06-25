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
    :rule/status stubbed-status}
   {:rule/id :java.class-feature/to-csharp-class
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/class
    :rule/output-feature :csharp.feature/class
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
   {:rule/id :java.checked-exception/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/checked-exception
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
