(ns vibeformer.research-dry-run
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [vibeformer.diagnostics.unresolved-refs :as unresolved-refs]
            [vibeformer.destination :as destination]
            [vibeformer.research-classpath :as research-classpath]
            [vibeformer.research-inventory :as research-inventory])
  (:import (java.nio.file Files Path Paths)))

(def default-project-id "research-pkl")
(def default-output-root "target/research-pkl")

(def supported-modes
  #{:facts-only
    :emit-only
    :compile-capable})

(def non-goals
  ["does not modify the research Pkl checkout"
   "does not patch generated C#"
   "does not claim full-project emission before unresolved-reference gates pass"
   "does not run dotnet build until a generated destination project exists"])

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

(defn- output-root [project-root opts]
  (normalize-path
   (or (:dry-run/out opts)
       (:out-dir opts)
       (.resolve (normalize-path project-root) default-output-root))))

(defn- parse-mode [opts]
  (let [mode (or (:dry-run/mode opts)
                 (:mode opts)
                 :facts-only)]
    (when-not (supported-modes mode)
      (throw (ex-info "Unsupported research dry-run mode."
                      {:mode mode
                       :supported-modes (sort-by name supported-modes)})))
    mode))

(defn- ok-stage
  ([stage-name]
   (ok-stage stage-name {}))
  ([stage-name data]
   (merge {:stage stage-name
           :status :ok}
          data)))

(defn- skipped-stage [stage-name reason]
  {:stage stage-name
   :status :skipped
   :reason reason})

(defn- source-langs [inventory-report]
  (->> (:source/counts inventory-report)
       (keep :lang)
       (sort-by str)
       vec))

(defn- ingest-stage [stage-name result count-key no-source-reason]
  (if (pos? (long (or (get result count-key) 0)))
    (ok-stage stage-name result)
    (skipped-stage stage-name no-source-reason)))

(defn- coverage-stage [inventory-report]
  (let [coverage (:coverage inventory-report)]
    {:stage :coverage/check
     :status (if (:ok? coverage) :ok :failed)
     :coverage/ok? (:ok? coverage)
     :coverage/failures (:failure-count coverage)
     :coverage/allow-mode {:allow-stubs? true
                           :allow-unsupported? true
                           :strategy :coverage.strategy/full-pkl-facts-inventory}}))

(defn- emit-stage [mode]
  (case mode
    :facts-only
    (skipped-stage :csharp/emit :dry-run/facts-only-mode)

    (:emit-only :compile-capable)
    (assoc (skipped-stage :csharp/emit :pipeline/full-project-emission-not-implemented)
           :blocked-by [:unresolved-reference-gate
                        :csharp/full-project-emission])))

(defn- dotnet-stage [mode]
  (case mode
    :facts-only
    (skipped-stage :dotnet/build :dry-run/facts-only-mode)

    :emit-only
    (skipped-stage :dotnet/build :dry-run/emit-only-mode)

    :compile-capable
    (assoc (skipped-stage :dotnet/build :dotnet/no-csharp-project)
           :blocked-by [:csharp/full-project-emission])))

(defn- unresolved-gate-opts [mode]
  (case mode
    :facts-only
    {:unresolved/warn-over 0}

    (:emit-only :compile-capable)
    {:unresolved/fail-over 0
     :unresolved/warn-over 0}))

(defn- classpath-stage [classpath-report]
  (ok-stage :classpath/discover
            {:projects/count (:projects/count classpath-report)
             :source-roots/count (:source-roots/count classpath-report)
             :dependencies/count (:dependencies/count classpath-report)}))

(defn- destination-stage [destination-report]
  (ok-stage :destination/mapping
            {:projects/count (:projects/count destination-report)
             :project-references/count (:project-references/count destination-report)
             :packages/count (:packages/count destination-report)
             :resources/count (:resources/count destination-report)
             :helpers/count (:helpers/count destination-report)}))

(defn- dry-run-stages [mode inventory-report classpath-report destination-report unresolved-stage]
  [(ok-stage :source/discover
             {:source/files (:source/files inventory-report)
              :source/counts (:source/counts inventory-report)})
   (classpath-stage classpath-report)
   (destination-stage destination-report)
   (ok-stage :source/ingest (:ingest/source inventory-report))
   (ingest-stage :java/ingest
                 (:ingest/java inventory-report)
                 :java-files
                 :java/no-source-files)
   (ingest-stage :kotlin/ingest
                 (:ingest/kotlin inventory-report)
                 :kotlin-files
                 :kotlin/no-source-files)
   (ingest-stage :kotlin/enrich
                 (:ingest/kotlin-enrich inventory-report)
                 :semantic-tx
                 :kotlin/no-semantic-facts)
   (ok-stage :transform/rules
             {:rules/registered (:rules/registered inventory-report)
              :rules/langs (:rules/langs inventory-report)})
   (ok-stage :diagnostics/inventory
             {:unsupported/features (count (get-in inventory-report
                                                   [:inventory :unsupported-rankings]))})
   (coverage-stage inventory-report)
   unresolved-stage
   (emit-stage mode)
   (dotnet-stage mode)
   (skipped-stage :diagnostics/ingest :dotnet/no-diagnostics)
   (ok-stage :provenance/write)])

(defn- failed-stage? [stage]
  (= :failed (:status stage)))

(defn run-dry-run
  "Run a staged, read-only full-Pkl dry-run report under target/research-pkl."
  ([] (run-dry-run {}))
  ([opts]
   (let [project-root (normalize-path (or (:project-root opts)
                                          (System/getProperty "user.dir")))
         research-root (normalize-path (or (:research/root opts)
                                           (:research-root opts)
                                           (default-research-root project-root)))
         output-root (output-root project-root opts)
         diagnostics-root (.resolve output-root "diagnostics")
         csharp-root (.resolve output-root "csharp")
	         inventory-file (.resolve output-root "inventory.edn")
	         classpath-file (.resolve output-root "classpath.edn")
	         destination-file (.resolve output-root "destination.edn")
	         unresolved-refs-file (.resolve diagnostics-root "unresolved-refs.edn")
         dry-run-file (.resolve output-root "dry-run.edn")
         provenance-file (.resolve output-root "provenance.edn")
         mode (parse-mode opts)
         project-id (or (:project/id opts) default-project-id)]
     (doseq [dir [output-root diagnostics-root csharp-root]]
       (ensure-dir! dir))
     (let [classpath-report (research-classpath/run-classpath-inventory
	                         (merge opts
	                                {:project-root project-root
	                                 :research/root research-root
	                                 :project/id project-id
	                                 :out classpath-file}))
           inventory-report (research-inventory/run-inventory
                             (merge opts
                                    {:project-root project-root
                                     :research/root research-root
                                     :project/id project-id
                                     :java/classpath-package-roots (:java/classpath-package-roots classpath-report)
                                     :kotlin/classpath-types (:kotlin/classpath-types classpath-report)
                                     :out inventory-file}))
	           destination-report (assoc (destination/research-mapping
	                                      classpath-report
	                                      {:destination/root csharp-root})
	                                     :report/file (slash-path (normalize-path destination-file)))
	           unresolved-report (assoc (unresolved-refs/report inventory-report
	                                                            (unresolved-gate-opts mode))
	                                    :report/file (slash-path (normalize-path unresolved-refs-file)))
           unresolved-stage (assoc (unresolved-refs/stage inventory-report
                                                          (unresolved-gate-opts mode))
                                   :report/file (:report/file unresolved-report))
	           stages (dry-run-stages mode inventory-report classpath-report destination-report unresolved-stage)
	           artifacts {:inventory (:report/file inventory-report)
	                      :classpath (:report/file classpath-report)
	                      :destination (:report/file destination-report)
	                      :diagnostics/unresolved-refs (:report/file unresolved-report)
                      :dry-run (slash-path (normalize-path dry-run-file))
                      :diagnostics/root (slash-path (normalize-path diagnostics-root))
                      :provenance (slash-path (normalize-path provenance-file))
                      :csharp/root (slash-path (normalize-path csharp-root))}
           provenance {:project/id project-id
                       :research/root (slash-path research-root)
                       :dry-run/mode mode
	                       :source/files (:source/files inventory-report)
	                       :source/langs (source-langs inventory-report)
	                       :destination/projects (:projects/count destination-report)
	                       :stages (mapv #(select-keys % [:stage :status :reason])
	                                     stages)}
           report {:report/type :vibeformer.report/research-dry-run
                   :project/id project-id
                   :project/root (slash-path project-root)
                   :research/root (slash-path research-root)
                   :dry-run/mode mode
                   :dry-run/non-goals non-goals
                   :ok? (not-any? failed-stage? stages)
                   :source/files (:source/files inventory-report)
                   :source/counts (:source/counts inventory-report)
                   :inventory/report (:report/file inventory-report)
                   :artifacts artifacts
                   :stages stages}]
	       (write-edn! unresolved-refs-file unresolved-report)
	       (write-edn! destination-file destination-report)
	       (write-edn! provenance-file provenance)
       (write-edn! dry-run-file report)
       (assoc report
              :report/file (slash-path (normalize-path dry-run-file))
              :provenance/file (slash-path (normalize-path provenance-file)))))))

(defn- parse-cli-opts [value]
  (if (nil? value)
    {}
    (let [opts (edn/read-string value)]
      (when-not (map? opts)
        (throw (ex-info "Research dry-run options must be an EDN map."
                        {:value value
                         :parsed opts})))
      opts)))

(defn- display-keyword [value]
  (subs (str value) 1))

(defn -main [& args]
  (let [[opts-edn & extra] args]
    (when (seq extra)
      (throw (ex-info "Unexpected research dry-run arguments."
                      {:args args
                       :expected "optional EDN options map"})))
    (let [result (run-dry-run (parse-cli-opts opts-edn))]
      (println (str "Research dry-run -> " (:report/file result)))
      (println (format "mode: %s, source files: %s"
                       (display-keyword (:dry-run/mode result))
                       (:source/files result)))
      (doseq [{:keys [stage status reason]} (:stages result)]
        (println (format "%-24s %s%s"
                         (display-keyword stage)
                         (display-keyword status)
                         (if reason
                           (str " (" (display-keyword reason) ")")
                           ""))))
      (shutdown-agents)
      (when-not (:ok? result)
        (System/exit 1)))))
