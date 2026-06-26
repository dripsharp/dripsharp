(ns vibeformer.research-inventory
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.kotlin-psi :as kotlin-psi]
            [vibeformer.ingest.source :as source]
            [vibeformer.inventory :as inventory]
            [vibeformer.transform.rules :as rules])
  (:import (java.nio.file Files Path Paths)
           (java.util UUID)))

(def default-project-id "research-pkl")

(defn- path [value]
  (if (instance? Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- normalize-path [value]
  (.normalize (.toAbsolutePath (path value))))

(defn- slash-path [value]
  (str/replace (str value) \\ \/))

(defn- ensure-dir! [^Path dir]
  (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
  dir)

(defn- write-edn! [file value]
  (ensure-dir! (.getParent (path file)))
  (spit (str file) (str (pr-str value) "\n"))
  (slash-path (normalize-path file)))

(defn- default-research-root [project-root]
  (.resolve (.getParent (normalize-path project-root)) "research/pkl"))

(defn- report-file [project-root opts]
  (normalize-path
   (or (:out opts)
       (.resolve (normalize-path project-root) "target/research-pkl/inventory.edn"))))

(defn- with-empty-db [f]
  (let [system (str "vibeformer-research-inventory-" (UUID/randomUUID))
        db-name (str "facts-" (UUID/randomUUID))
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system system})
        created? (atom false)]
    (try
      (d/create-database client {:db-name db-name})
      (reset! created? true)
      (f (d/connect client {:db-name db-name}))
      (finally
        (when @created?
          (dl/release-db {:system system
                          :storage-dir :mem
                          :db-name db-name}))))))

(defn- source-counts [source-files]
  (->> source-files
       (group-by :file/lang)
       (map (fn [[lang files]]
              {:lang lang
               :count (count files)}))
       (sort-by :lang)
       vec))

(def lookup-ref-attrs
  {:decl/return-type :type/id
   :decl/source-node :node/id
   :decl/type :type/id
   :decl/type-params :type-param/id
   :diagnostic/rule :rule/id
   :diagnostic/source-features :feature/id
   :diagnostic/source-node :node/id
   :feature/node :node/id
   :node/parent :node/id
   :ref/from-node :node/id
   :ref/owner-type :type/id
   :ref/to-decl :decl/id
   :ref/to-type :type/id})

(defn- lookup-ref-value [attr value]
  (let [lookup-attr (lookup-ref-attrs attr)]
    (cond
      (nil? lookup-attr) value
      (and (vector? value)
           (= 2 (count value))
           (keyword? (first value))) value
      (sequential? value) (mapv #(lookup-ref-value attr %) value)
      :else [lookup-attr value])))

(defn- lookup-ref-fact [fact]
  (reduce-kv (fn [acc attr value]
               (if (= :type/args attr)
                 acc
                 (assoc acc attr (lookup-ref-value attr value))))
             {}
             fact))

(defn- java-fact-group [fact]
  (cond
    (:type/id fact) :type
    (:type-param/id fact) :type-param
    (:node/id fact) :node
    (:decl/id fact) :decl
    (:ref/id fact) :ref
    (:feature/id fact) :feature
    :else :other))

(def java-fact-group-order
  [:type :type-param :node :decl :ref :feature :other])

(def batch-size 100)

(defn- raw-ref-id [value]
  (cond
    (nil? value) nil
    (vector? value) (second value)
    :else value))

(defn- node-depths [nodes]
  (let [by-id (into {} (map (juxt :node/id identity) nodes))
        depths (atom {})]
    (letfn [(depth [node]
              (let [node-id (:node/id node)]
                (if-let [known (get @depths node-id)]
                  known
                  (let [parent-id (raw-ref-id (:node/parent node))
                        value (if-let [parent (get by-id parent-id)]
                                (inc (depth parent))
                                0)]
                    (swap! depths assoc node-id value)
                    value))))]
      (doseq [node nodes]
        (depth node))
      @depths)))

(def type-ref-attrs
  [:decl/return-type
   :decl/type
   :ref/owner-type
   :ref/to-type])

(defn- referenced-type-ids [facts]
  (set
   (concat
    (mapcat (fn [fact]
              (keep #(raw-ref-id (get fact %)) type-ref-attrs))
            facts)
    (mapcat (fn [fact]
              (->> (:type/args fact)
                   (keep (comp raw-ref-id :type.arg/type))))
            facts))))

(defn- type-lang [type-id]
  (cond
    (str/starts-with? type-id "kotlin:") :lang/kotlin
    (str/starts-with? type-id "java:") :lang/java
    :else :lang/java))

(defn- type-stub [type-id]
  {:db/id type-id
   :type/id type-id
   :type/lang (type-lang type-id)
   :type/name type-id
   :type/nullable? false})

(defn- with-missing-type-stubs [facts]
  (let [defined-type-ids (set (keep :type/id facts))
        missing-type-ids (->> (referenced-type-ids facts)
                              (remove defined-type-ids)
                              sort)]
    (into facts (map type-stub missing-type-ids))))

(defn- fact-batches [group-name group-facts]
  (case group-name
    :node (let [depths (node-depths group-facts)]
            (->> group-facts
                 (group-by #(get depths (:node/id %) 0))
                 (sort-by key)
                 (mapcat (comp #(partition-all batch-size %) vec val))))
    (partition-all batch-size group-facts)))

(defn- transact-fact-batches! [conn facts]
  (let [facts (->> facts
                   vec
                   with-missing-type-stubs
                   (mapv lookup-ref-fact)
                   with-missing-type-stubs)
        grouped (group-by java-fact-group facts)]
    (reduce
     (fn [stats group-name]
       (let [group-facts (get grouped group-name)]
         (if (seq group-facts)
           (let [batches (fact-batches group-name group-facts)]
             (doseq [[index batch] (map-indexed vector batches)]
               (try
                 (d/transact conn {:tx-data (vec batch)})
                 (catch Throwable t
                   (throw (ex-info "Research inventory batch transaction failed."
                                   {:group group-name
                                    :batch-index index
                                    :batch-size (count batch)}
                                   t)))))
             (assoc stats group-name {:facts (count group-facts)
                                      :batches (count batches)}))
           stats)))
     {}
     java-fact-group-order)))

(defn- ingest-java-batched! [conn project-id source-files]
  (let [facts (java-spoon/extract-project-facts (d/db conn) project-id)
        stats (transact-fact-batches! conn facts)]
    {:project/id project-id
     :java-files (count (filter #(= :lang/java (:file/lang %)) source-files))
     :transacted-facts (count facts)
     :batches stats}))

(defn- ingest-kotlin-batched! [conn project-id source-files]
  (let [facts (kotlin-psi/extract-project-facts (d/db conn) project-id)
        stats (transact-fact-batches! conn facts)]
    {:project/id project-id
     :kotlin-files (count (filter #(= :lang/kotlin (:file/lang %)) source-files))
     :transacted-facts (count facts)
     :batches stats}))

(defn- existing-type-ids [db type-ids]
  (set
   (map first
        (d/q '[:find ?type-id
               :in $ [?type-id ...]
               :where
               [_ :type/id ?type-id]]
             db
             (vec type-ids)))))

(defn- missing-type-stubs-for-db [db facts]
  (let [facts (filter map? facts)
        defined-type-ids (set (keep :type/id facts))
        referenced-type-ids (referenced-type-ids facts)
        existing-type-ids (existing-type-ids db referenced-type-ids)]
    (->> referenced-type-ids
         (remove defined-type-ids)
         (remove existing-type-ids)
         sort
         (mapv type-stub))))

(defn- ingest-kotlin-enrichment-batched! [conn project-id opts]
  (let [tx-data (kotlin-psi/semantic-resolution-facts (d/db conn) project-id opts)
        type-stubs (missing-type-stubs-for-db (d/db conn) tx-data)]
    (when (seq type-stubs)
      (doseq [batch (partition-all batch-size type-stubs)]
        (d/transact conn {:tx-data (vec batch)})))
    (when (seq tx-data)
      (doseq [batch (partition-all batch-size tx-data)]
        (d/transact conn {:tx-data (vec batch)})))
    {:project/id project-id
     :semantic-refs (count (filter map? tx-data))
     :semantic-tx (count tx-data)
     :type-stubs (count type-stubs)
     :batches {:type-stubs (count (partition-all batch-size type-stubs))
               :semantic-tx (count (partition-all batch-size tx-data))}}))

(defn- coverage-summary [coverage-report]
  {:ok? (:ok? coverage-report)
   :failure-count (count (:failures coverage-report))
   :failure-rankings
   (->> (:failures coverage-report)
        (group-by (juxt :coverage/reason
                        :coverage/input
                        :source/lang
                        :source/kind
                        :source/status))
        (map (fn [[[reason input lang kind status] failures]]
               (cond-> {:reason reason
                        :input input
                        :lang lang
                        :kind kind
                        :count (count failures)}
                 status (assoc :status status))))
        (sort-by (juxt (comp - :count)
                       :reason
                       :input
                       :lang
                       :kind
                       :status))
        vec)})

(defn run-inventory
  "Analyze ../research/pkl read-only and write a deterministic inventory report."
  ([] (run-inventory {}))
  ([opts]
   (let [project-root (normalize-path (or (:project-root opts)
                                          (System/getProperty "user.dir")))
         research-root (normalize-path (or (:research/root opts)
                                           (:research-root opts)
                                           (default-research-root project-root)))
         output-file (report-file project-root opts)
         project-id (or (:project/id opts) default-project-id)
         source-opts {:source/root (slash-path research-root)
                      :project/id project-id
                      :project/name "Research Pkl"}]
     (with-empty-db
       (fn [conn]
         (schema/install! conn)
         (let [source-files (source/source-file-facts source-opts)
               source-result (source/ingest! conn source-opts)
               java-result (ingest-java-batched! conn project-id source-files)
               kotlin-result (ingest-kotlin-batched! conn project-id source-files)
               kotlin-enrich-result (ingest-kotlin-enrichment-batched! conn project-id {})
               _ (rules/register! conn rules/initial-java-rules)
               db (d/db conn)
               coverage-report (rules/coverage-report db {:allow-stubs? true
                                                           :allow-unsupported? true})
               report {:report/type :vibeformer.report/research-inventory
                       :project/id project-id
                       :project/root (slash-path project-root)
                       :research/root (slash-path research-root)
                       :source/counts (source-counts source-files)
                       :source/files (count source-files)
                       :ingest/source source-result
                       :ingest/java java-result
                       :ingest/kotlin kotlin-result
                       :ingest/kotlin-enrich kotlin-enrich-result
                       :rules/registered (count rules/initial-java-rules)
                       :coverage (coverage-summary coverage-report)
                       :inventory (inventory/summary db)}
               output (write-edn! output-file report)]
           (assoc report :report/file output)))))))

(defn- parse-cli-opts [value]
  (if (nil? value)
    {}
    (let [opts (edn/read-string value)]
      (when-not (map? opts)
        (throw (ex-info "Research inventory options must be an EDN map."
                        {:value value
                         :parsed opts})))
      opts)))

(defn -main [& args]
  (let [[opts-edn & extra] args]
    (when (seq extra)
      (throw (ex-info "Unexpected research inventory arguments."
                      {:args args
                       :expected "optional EDN options map"})))
    (let [result (run-inventory (parse-cli-opts opts-edn))]
      (println (str "Research inventory -> " (:report/file result)))
      (println (format "source files: %s" (:source/files result)))
      (println (format "coverage ok: %s, failures: %s"
                       (get-in result [:coverage :ok?])
                       (get-in result [:coverage :failure-count])))
      (shutdown-agents))))
