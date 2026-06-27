(ns vibeformer.ingest.kotlin-analysis-api
  (:require [datomic.client.api :as d]))

(def analysis-api-session-class
  "org.jetbrains.kotlin.analysis.api.KaSession")

(defn- class-available? [class-name]
  (try
    (Class/forName class-name false (.. Thread currentThread getContextClassLoader))
    true
    (catch ClassNotFoundException _
      false)))

(defn available?
  "Return true when Kotlin Analysis API classes are available on the runtime classpath."
  []
  (class-available? analysis-api-session-class))

(defn- project-record [db project-id]
  (d/pull db [:project/id :project/name :project/root] [:project/id project-id]))

(defn- source-files [db project-id]
  (mapv (fn [[file]]
          (d/pull db [:file/id :file/path :file/hash :file/package] file))
        (d/q '[:find ?file
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?file :file/lang :lang/kotlin]]
             db
             project-id)))

(defn setup-descriptor
  "Build a stable description of the Analysis API module/session setup inputs.

  This value is plain data. It must not contain KaSession, symbol, type, or other
  lifetime-owned Analysis API objects."
  [db project-id opts]
  (let [project (project-record db project-id)
        files (source-files db project-id)
        available? (available?)]
    {:project/id project-id
     :project/root (:project/root project)
     :analysis-api/session-class analysis-api-session-class
     :analysis-api/available? available?
     :analysis-api/status (if available?
                            :analysis-api.status/available
                            :analysis-api.status/unavailable)
     :analysis-api/reason (when-not available?
                            :analysis-api.reason/classes-not-on-classpath)
     :analysis-api/module {:module/name project-id
                           :module/kind :kotlin.module.kind/source
                           :source/files (count files)
                           :source/file-ids (mapv :file/id files)
                           :classpath/types (sort (map str (:kotlin/classpath-types opts)))
                           :classpath/roots (sort (map str (:kotlin/classpath-roots opts)))}}))

(defn pass-id [project-id]
  (str project-id ":kotlin-analysis-api-prototype"))

(defn setup-facts
  "Return stable Datomic facts that record the Analysis API setup attempt."
  [db project-id opts]
  (let [{:analysis-api/keys [available? reason] :as setup} (setup-descriptor db project-id opts)
        pass-id (pass-id project-id)
        pass-status (if available?
                      :pass.status/ok
                      :pass.status/skipped)
        pass-fact {:db/id pass-id
                   :pass/id pass-id
                   :pass/kind :pass.kind/kotlin-analysis-api-prototype
                   :pass/compiler "Kotlin Analysis API"
                   :pass/status pass-status
                   :pass/project [:project/id project-id]}
        diagnostic-fact (when-not available?
                          {:db/id (str pass-id ":unavailable")
                           :diagnostic/id (str pass-id ":unavailable")
                           :diagnostic/pass pass-id
                           :diagnostic/code "KOTLIN_ANALYSIS_API_UNAVAILABLE"
                           :diagnostic/message (str "Kotlin Analysis API class "
                                                    analysis-api-session-class
                                                    " is not on the runtime classpath; "
                                                    "semantic enrichment used conservative fallback facts.")
                           :diagnostic/severity :diagnostic.severity/warning
                           :diagnostic/status :diagnostic.status/open
                           :diagnostic/mapping-status :diagnostic.mapping/unmapped
                           :diagnostic/mapping-reason :diagnostic.mapping/no-provenance-span})]
    {:setup setup
     :tx-data (cond-> [pass-fact]
                diagnostic-fact (conj diagnostic-fact))
     :status (if available?
               :analysis-api.prototype/available
               :analysis-api.prototype/unavailable)
     :reason reason}))
