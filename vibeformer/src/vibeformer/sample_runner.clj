(ns vibeformer.sample-runner
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.emit.csharp :as csharp]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.source :as source]
            [vibeformer.inventory :as inventory]
            [vibeformer.transform.rules :as rules])
  (:import (java.nio.file Files Path Paths)
           (java.util UUID)))

(def default-sample "java-word-count")

(defn- path
  ([value]
   (if (instance? Path value)
     value
     (Paths/get (str value) (make-array String 0))))
  ([first & more]
   (Paths/get (str first) (into-array String (map str more)))))

(defn- normalize-path [value]
  (.normalize (.toAbsolutePath (path value))))

(defn- directory? [value]
  (Files/isDirectory (path value) (make-array java.nio.file.LinkOption 0)))

(defn- slash-path [value]
  (str/replace (str value) \\ \/))

(defn- project-root [opts]
  (normalize-path (or (:project-root opts)
                      (System/getProperty "user.dir"))))

(defn samples-root
  "Return the sample-projects directory for a Vibeformer checkout."
  [root]
  (.resolve (normalize-path root) "sample-projects"))

(defn discover-samples
  "Return committed sample projects with a source/ root."
  [root]
  (let [samples-dir (samples-root root)]
    (if-not (directory? samples-dir)
      []
      (with-open [paths (Files/list samples-dir)]
        (->> (iterator-seq (.iterator paths))
             (filter directory?)
             (keep (fn [sample-root]
                     (let [source-root (.resolve sample-root "source")]
                       (when (directory? source-root)
                         {:sample/name (str (.getFileName sample-root))
                          :sample/root (slash-path (normalize-path sample-root))
                          :source/root (slash-path (normalize-path source-root))}))))
             (sort-by :sample/name)
             vec)))))

(defn sample-project
  "Resolve one sample project by name."
  [root sample-name]
  (or (some #(when (= sample-name (:sample/name %)) %)
            (discover-samples root))
      (throw (ex-info "Sample project not found."
                      {:sample/name sample-name
                       :samples/root (slash-path (samples-root root))
                       :available-samples (mapv :sample/name (discover-samples root))}))))

(defn- ensure-dir! [^Path dir]
  (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
  dir)

(defn- target-paths [sample]
  (let [target (.resolve (path (:sample/root sample)) "target")]
    {:target/root target
     :target/csharp (.resolve target "csharp")
     :target/diagnostics (.resolve target "diagnostics")
     :target/facts (.resolve target "facts")
     :target/provenance (.resolve target "provenance.edn")}))

(defn- ensure-target! [sample]
  (let [paths (target-paths sample)]
    (doseq [dir (map paths [:target/root :target/csharp :target/diagnostics :target/facts])]
      (ensure-dir! dir))
    paths))

(defn- write-edn! [file value]
  (ensure-dir! (.getParent (path file)))
  (spit (str file) (str (pr-str value) "\n"))
  (slash-path (normalize-path file)))

(defn- throwable-data [^Throwable throwable]
  (cond-> {:class (some-> throwable class .getName)
           :message (ex-message throwable)}
    (ex-data throwable) (assoc :data (ex-data throwable))))

(defn- stage [sample stage-name f]
  (let [started (System/nanoTime)]
    (try
      (let [result (f)
            duration-ms (long (/ (- (System/nanoTime) started) 1000000))]
        (merge {:stage stage-name
                :status :ok
                :duration-ms duration-ms}
               result))
      (catch Throwable t
        {:stage stage-name
         :status :failed
         :duration-ms (long (/ (- (System/nanoTime) started) 1000000))
         :sample/name (:sample/name sample)
         :source/root (:source/root sample)
         :error (throwable-data t)}))))

(defn- skipped-stage [stage-name reason]
  {:stage stage-name
   :status :skipped
   :reason reason})

(defn- display-keyword [value]
  (subs (str value) 1))

(defn- with-empty-db [f]
  (let [system (str "vibeformer-sample-run-" (UUID/randomUUID))
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

(defn- stop-after-failure? [stages]
  (some #(= :failed (:status %)) stages))

(defn- run-stage [stages sample stage-name f]
  (if (stop-after-failure? stages)
    stages
    (conj stages (stage sample stage-name f))))

(defn- run-analysis-stages [sample paths]
  (with-empty-db
    (fn [conn]
      (let [project-id (:sample/name sample)
            source-root (:source/root sample)
            opts {:source/root source-root
                  :project/id project-id
                  :project/name project-id}
            stages []
            stages (run-stage stages sample :schema/install
                              #(do (schema/install! conn)
                                   {:installed? true}))
            stages (run-stage stages sample :source/discover
                              #(let [source-files (source/source-file-facts opts)]
                                 (write-edn! (.resolve (:target/facts paths) "source-files.edn")
                                            source-files)
                                 {:source/files (count source-files)}))
            stages (run-stage stages sample :source/ingest
                              #(source/ingest! conn opts))
            stages (run-stage stages sample :java/ingest
                              #(java-spoon/ingest! conn {:project/id project-id}))
            stages (run-stage stages sample :transform/rules
                              #(do (rules/register! conn rules/initial-java-rules)
                                   {:rules/registered (count rules/initial-java-rules)}))
            stages (if (stop-after-failure? stages)
                     stages
                     (let [db (d/db conn)
                           inventory-report (inventory/summary db)
                           coverage-report (rules/coverage-report db)]
                       (write-edn! (.resolve (:target/diagnostics paths) "inventory.edn")
                                  inventory-report)
                       (write-edn! (.resolve (:target/diagnostics paths) "coverage.edn")
                                  coverage-report)
                       (conj stages
                             {:stage :diagnostics/inventory
                              :status :ok
                              :unsupported/features (count (:unsupported-rankings inventory-report))}
                             {:stage :coverage/check
                              :status :ok
                              :coverage/ok? (:ok? coverage-report)
                              :coverage/failures (count (:failures coverage-report))})))
            stages (cond-> stages
                     (not (stop-after-failure? stages))
                     (run-stage sample :csharp/emit
                                #(csharp/emit! (d/db conn) (:target/csharp paths))))
            stages (cond-> stages
                     (not (stop-after-failure? stages))
                     (conj (skipped-stage :dotnet/build :pipeline.stage/not-implemented)))]
        stages))))

(defn run-sample
  "Run one sample project through the currently supported pipeline stages.

  Options:
  - :project-root Vibeformer checkout root, defaults to the current directory
  - :name sample name, defaults to java-word-count"
  ([] (run-sample {}))
  ([opts]
   (let [root (project-root opts)
         sample-name (or (:name opts) default-sample)
         sample (sample-project root sample-name)
         paths (ensure-target! sample)
         stages (run-analysis-stages sample paths)
         result {:sample/name (:sample/name sample)
                 :sample/root (:sample/root sample)
                 :source/root (:source/root sample)
                 :target/root (slash-path (normalize-path (:target/root paths)))
                 :ok? (not (stop-after-failure? stages))
                 :stages stages}]
     (write-edn! (.resolve (:target/diagnostics paths) "stages.edn") stages)
     (let [emit-stage (some #(when (= :csharp/emit (:stage %)) %) stages)]
       (write-edn! (:target/provenance paths)
                  (cond-> {:sample/name (:sample/name sample)
                           :source/root (:source/root sample)
                           :target/csharp (slash-path (normalize-path (:target/csharp paths)))}
                    (= :ok (:status emit-stage))
                    (assoc :status :generated
                           :csharp/files (:csharp/files emit-stage)
                           :csharp/files-written (:csharp/files-written emit-stage)
                           :csharp/rule-applications (:csharp/rule-applications emit-stage)
                           :csharp/provenance (:csharp/provenance emit-stage)
                           :csharp/diagnostics (:csharp/diagnostics emit-stage)
                           :csharp/helpers (:csharp/helpers emit-stage)
                           :csharp/usings (:csharp/usings emit-stage))

                    (not= :ok (:status emit-stage))
                    (assoc :status :skipped
                           :reason :pipeline.stage/not-implemented))))
     result)))

(defn -main [& args]
  (let [sample-name (or (first args) default-sample)
        result (run-sample {:name sample-name})]
    (println (str "Sample " (:sample/name result)
                  " -> " (if (:ok? result) "ok" "failed")))
    (doseq [{:keys [stage status] :as stage-result} (:stages result)]
      (println (format "%-24s %s" (display-keyword stage) (display-keyword status)))
      (when (= :coverage/check stage)
        (println (format "  coverage ok: %s, failures: %s"
                         (:coverage/ok? stage-result)
                         (:coverage/failures stage-result)))))
    (when-not (:ok? result)
      (System/exit 1))))
