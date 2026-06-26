(ns vibeformer.sample-runner
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.emit.csharp :as csharp]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.kotlin-psi :as kotlin-psi]
            [vibeformer.ingest.source :as source]
            [vibeformer.inventory :as inventory]
            [vibeformer.transform.rules :as rules])
  (:import (java.nio.file Files Path Paths)
           (java.util UUID)))

(def default-sample "java-word-count")
(def csharp-target-framework "net8.0")
(def default-dotnet-command "dotnet")

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

(defn- write-string! [file value]
  (ensure-dir! (.getParent (path file)))
  (spit (str file) value)
  (slash-path (normalize-path file)))

(defn- relative-slash-path [^Path root value]
  (slash-path (.relativize (.normalize root) (.normalize (path value)))))

(defn- xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- csharp-project-file [sample paths]
  (.resolve (:target/csharp paths) (str (:sample/name sample) ".csproj")))

(defn- csharp-project-content [target-dir csharp-files]
  (let [compile-items (->> csharp-files
                           (map #(relative-slash-path target-dir %))
                           (sort)
                           (map #(str "    <Compile Include=\"" (xml-escape %) "\" />\n"))
                           (apply str))]
    (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
         "  <PropertyGroup>\n"
         "    <OutputType>Exe</OutputType>\n"
         "    <TargetFramework>" csharp-target-framework "</TargetFramework>\n"
         "    <ImplicitUsings>disable</ImplicitUsings>\n"
         "    <Nullable>enable</Nullable>\n"
         "    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>\n"
         "  </PropertyGroup>\n"
         "\n"
         "  <ItemGroup>\n"
         compile-items
         "  </ItemGroup>\n"
         "</Project>\n")))

(defn- write-csharp-project! [sample paths emit-result]
  (let [target-dir (:target/csharp paths)
        csharp-files (:csharp/files emit-result)
        project-file (csharp-project-file sample paths)
        content (csharp-project-content target-dir csharp-files)]
    (spit (str project-file) content)
    {:csharp/project (slash-path (normalize-path project-file))
     :csharp/project-target-framework csharp-target-framework
     :csharp/project-files (mapv #(relative-slash-path target-dir %) csharp-files)}))

(def dotnet-diagnostic-pattern
  #"^(.+)\((\d+),(\d+)\):\s+(warning|error)\s+([A-Za-z]+\d+):\s+(.+?)(?:\s+\[.+\])?$")

(defn- parse-dotnet-diagnostic-line [line]
  (when-let [[_ file line-number column severity code message]
             (re-matches dotnet-diagnostic-pattern line)]
    {:file file
     :line (parse-long line-number)
     :column (parse-long column)
     :severity (keyword "diagnostic.severity" severity)
     :code code
     :message message}))

(defn- parse-dotnet-diagnostics [stdout stderr]
  (->> (str/split-lines (str stdout "\n" stderr))
       (keep parse-dotnet-diagnostic-line)
       vec))

(defn- normalized-file [value]
  (let [file (normalize-path value)]
    (slash-path
     (try
       (.toRealPath file (make-array java.nio.file.LinkOption 0))
       (catch java.nio.file.NoSuchFileException _
         file)))))

(defn- point-within-span? [line column span]
  (let [start-line (:start-line span)
        start-column (or (:start-column span) 1)
        end-line (:end-line span)
        end-column (or (:end-column span) Long/MAX_VALUE)]
    (and start-line
         end-line
         (<= start-line line end-line)
         (or (not= line start-line)
             (<= start-column column))
         (or (not= line end-line)
             (<= column end-column)))))

(defn- provenance-span-size [provenance]
  (let [span (:emit/dest-span provenance)]
    [(- (or (:end-line span) Long/MAX_VALUE)
        (or (:start-line span) 0))
     (- (or (:end-column span) Long/MAX_VALUE)
        (or (:start-column span) 0))]))

(defn- matching-provenance [provenance diagnostic]
  (let [file (normalized-file (:file diagnostic))
        line (:line diagnostic)
        column (:column diagnostic)]
    (->> provenance
         (filter #(and (= file (some-> % :emit/dest-file normalized-file))
                       (point-within-span? line column (:emit/dest-span %))))
         (sort-by provenance-span-size)
         first)))

(defn- source-feature-refs [provenance]
  (->> (:source/features provenance)
       (keep :feature/id)
       (mapv (fn [id] [:feature/id id]))))

(defn- diagnostic-id [pass-id diagnostic]
  (str pass-id
       ":"
       (normalized-file (:file diagnostic))
       ":"
       (:line diagnostic)
       ":"
       (:column diagnostic)
       ":"
       (:code diagnostic)))

(defn- diagnostic-fact [pass-id provenance diagnostic]
  (let [source-features (source-feature-refs provenance)
        rule-id (or (get-in provenance [:rule :rule/id])
                    (second (:emit/rule provenance)))
        source-node-id (:source/node-id provenance)]
    (cond-> {:db/id (diagnostic-id pass-id diagnostic)
             :diagnostic/id (diagnostic-id pass-id diagnostic)
             :diagnostic/pass pass-id
             :diagnostic/code (:code diagnostic)
             :diagnostic/message (:message diagnostic)
             :diagnostic/file (normalized-file (:file diagnostic))
             :diagnostic/start-line (:line diagnostic)
             :diagnostic/start-column (:column diagnostic)
             :diagnostic/severity (:severity diagnostic)
             :diagnostic/status :diagnostic.status/open
             :diagnostic/mapping-status (if provenance
                                          :diagnostic.mapping/mapped
                                          :diagnostic.mapping/unmapped)
             :diagnostic/mapping-reason (if provenance
                                          :diagnostic.mapping/provenance-span
                                          :diagnostic.mapping/no-provenance-span)}
      source-node-id
      (assoc :diagnostic/source-node [:node/id source-node-id])
      rule-id
      (assoc :diagnostic/rule [:rule/id rule-id])
      (seq source-features)
      (assoc :diagnostic/source-features source-features))))

(defn- diagnostic-facts [sample paths emit-stage build-stage]
  (let [pass-id (str (:sample/name sample) ":dotnet-build")
        diagnostics (:dotnet/diagnostics build-stage)
        provenance (:csharp/provenance emit-stage)]
    (into
     [(cond-> {:db/id pass-id
               :pass/id pass-id
               :pass/kind :pass.kind/csharp-compile
               :pass/compiler "dotnet build"
               :pass/status (keyword "pass.status" (name (:status build-stage)))
               :pass/project [:project/id (:sample/name sample)]}
        (:target/project build-stage)
        (assoc :pass/target-project (:target/project build-stage)))]
     (map (fn [diagnostic]
            (diagnostic-fact pass-id
                             (matching-provenance provenance diagnostic)
                             diagnostic)))
     diagnostics)))

(defn- diagnostic-query-summary [db]
  (let [rows (d/q '[:find (pull ?diagnostic [:diagnostic/code
                                             :diagnostic/mapping-status
                                             {:diagnostic/rule [:rule/id]}
                                             {:diagnostic/source-node [:node/id]}])
                    :where
                    [?diagnostic :diagnostic/id]]
                  db)]
    (mapv (fn [[diagnostic]]
            {:diagnostic/code (:diagnostic/code diagnostic)
             :diagnostic/mapping-status (:diagnostic/mapping-status diagnostic)
             :diagnostic/rule (get-in diagnostic [:diagnostic/rule :rule/id])
             :diagnostic/source-node (get-in diagnostic [:diagnostic/source-node :node/id])})
          rows)))

(defn- ingest-dotnet-diagnostics! [conn sample paths emit-stage build-stage]
  (let [facts (diagnostic-facts sample paths emit-stage build-stage)
        diagnostic-facts (filter :diagnostic/id facts)]
    (d/transact conn {:tx-data facts})
    (let [summary (diagnostic-query-summary (d/db conn))
          mapped-count (count (filter #(= :diagnostic.mapping/mapped
                                          (:diagnostic/mapping-status %))
                                      diagnostic-facts))
          unmapped-count (- (count diagnostic-facts) mapped-count)]
      (write-edn! (.resolve (:target/diagnostics paths) "dotnet-diagnostic-facts.edn")
                 {:pass (first facts)
                  :diagnostics (vec diagnostic-facts)
                  :query-summary summary})
      {:diagnostic/pass-id (:pass/id (first facts))
       :diagnostic/facts-count (count diagnostic-facts)
       :diagnostic/mapped-count mapped-count
       :diagnostic/unmapped-count unmapped-count
       :diagnostic/query-summary summary})))

(declare skipped-stage throwable-data)

(defn- dotnet-build! [sample paths opts]
  (let [project-file (csharp-project-file sample paths)
        diagnostics-dir (:target/diagnostics paths)
        command (str (or (:dotnet/command opts) default-dotnet-command))
        command-args [command "build" (str project-file) "--nologo"]
        context {:sample/name (:sample/name sample)
                 :source/root (:source/root sample)
                 :target/project (slash-path (normalize-path project-file))
                 :stage :dotnet/build
                 :command command-args}
        enabled? (not= false (:dotnet/enabled? opts))]
    (cond
      (not enabled?)
      (skipped-stage :dotnet/build :dotnet/build-disabled)

      (not (Files/isRegularFile project-file (make-array java.nio.file.LinkOption 0)))
      (skipped-stage :dotnet/build :dotnet/project-not-found)

      :else
      (try
        (let [{:keys [exit out err]} (apply sh/sh (concat command-args [:dir (str (:target/csharp paths))]))
              stdout-file (write-string! (.resolve diagnostics-dir "dotnet-build.stdout.log") out)
              stderr-file (write-string! (.resolve diagnostics-dir "dotnet-build.stderr.log") err)
              diagnostics (parse-dotnet-diagnostics out err)
              diagnostic-report (merge context
                                       {:exit exit
                                        :stdout stdout-file
                                        :stderr stderr-file
                                        :diagnostics diagnostics})
              diagnostics-file (write-edn! (.resolve diagnostics-dir "dotnet-build.edn")
                                           diagnostic-report)]
          (merge context
                 {:status (if (zero? exit) :ok :failed)
                  :dotnet/exit exit
                  :dotnet/stdout stdout-file
                  :dotnet/stderr stderr-file
                  :dotnet/diagnostics-file diagnostics-file
                  :dotnet/diagnostics diagnostics
                  :dotnet/diagnostics-count (count diagnostics)}))
        (catch java.io.IOException e
          (merge (skipped-stage :dotnet/build :dotnet/command-not-found)
                 context
                 {:error (throwable-data e)}))))))

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

(defn- source-langs [source-files]
  (set (keep :file/lang source-files)))

(defn- has-lang? [source-files lang]
  (contains? (source-langs source-files) lang))

(defn- kotlin-sample? [source-files]
  (has-lang? source-files :lang/kotlin))

(defn- registered-rules [source-files]
  (cond-> []
    (has-lang? source-files :lang/java)
    (into rules/initial-java-rules)

    (has-lang? source-files :lang/kotlin)
    (into rules/initial-kotlin-rules)))

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

(defn- coverage-opts [opts]
  (cond-> {}
    (or (:coverage/allow-stubs? opts) (:allow-stubs? opts))
    (assoc :allow-stubs? true)
    (or (:coverage/allow-unsupported? opts) (:allow-unsupported? opts))
    (assoc :allow-unsupported? true)))

(defn- effective-coverage-opts [opts source-files]
  (cond-> (coverage-opts opts)
    (kotlin-sample? source-files)
    (assoc :allow-stubs? true
           :strategy :coverage.strategy/kotlin-facts-only)))

(defn- coverage-stage [coverage-report coverage-opts]
  (let [allow-mode (select-keys coverage-opts [:allow-stubs? :allow-unsupported? :strategy])]
    (cond-> {:stage :coverage/check
             :status (if (:ok? coverage-report) :ok :failed)
             :coverage/ok? (:ok? coverage-report)
             :coverage/failures (count (:failures coverage-report))}
      (seq allow-mode) (assoc :coverage/allow-mode allow-mode))))

(defn- coverage-artifact [coverage-report coverage-opts]
  (let [allow-mode (select-keys coverage-opts [:allow-stubs? :allow-unsupported? :strategy])]
    (cond-> coverage-report
      (seq allow-mode) (assoc :coverage/allow-mode allow-mode))))

(defn- csharp-allow-diagnostics? [opts]
  (boolean (or (:csharp/allow-diagnostics? opts)
               (:allow-csharp-diagnostics? opts))))

(defn- error-diagnostics [diagnostics]
  (->> diagnostics
       (filter #(= :diagnostic.severity/error (:diagnostic/severity %)))
       vec))

(defn- csharp-emit-stage [conn paths opts]
  (let [emit-result (csharp/emit! (d/db conn) (:target/csharp paths))
        project-result (write-csharp-project! (:sample opts) paths emit-result)
        errors (error-diagnostics (:csharp/diagnostics emit-result))
        allow? (csharp-allow-diagnostics? opts)]
    (cond-> (merge emit-result project-result)
      (seq errors)
      (assoc :csharp/error-diagnostics errors
             :csharp/error-diagnostics-count (count errors))

      (and (seq errors) allow?)
      (assoc :csharp/allow-mode {:allow-diagnostics? true})

      (and (seq errors) (not allow?))
      (assoc :status :failed
             :reason :csharp/emit-diagnostics))))

(defn- kotlin-csharp-emission-skipped-stage []
  (assoc (skipped-stage :csharp/emit :pipeline.kotlin/csharp-emission-not-implemented)
         :csharp/strategy :csharp.strategy/kotlin-facts-only))

(defn- dotnet-skipped-after-csharp-stage [emit-stage]
  (assoc (skipped-stage :dotnet/build :dotnet/no-csharp-output)
         :csharp/emit-status (:status emit-stage)
         :csharp/emit-reason (:reason emit-stage)))

(defn- run-analysis-stages [sample paths opts]
  (with-empty-db
    (fn [conn]
      (let [project-id (:sample/name sample)
            source-root (:source/root sample)
            source-opts {:source/root source-root
                         :project/id project-id
                         :project/name project-id}
            source-files (source/source-file-facts source-opts)
            stages []
            stages (run-stage stages sample :schema/install
                              #(do (schema/install! conn)
                                   {:installed? true}))
            stages (run-stage stages sample :source/discover
                              #(do (write-edn! (.resolve (:target/facts paths) "source-files.edn")
                                               source-files)
                                   {:source/files (count source-files)
                                    :source/langs (sort-by str (source-langs source-files))}))
            stages (run-stage stages sample :source/ingest
                              #(source/ingest! conn source-opts))
            stages (if (has-lang? source-files :lang/java)
                     (run-stage stages sample :java/ingest
                                #(java-spoon/ingest! conn {:project/id project-id}))
                     (conj stages (skipped-stage :java/ingest :java/no-source-files)))
            stages (if (has-lang? source-files :lang/kotlin)
                     (run-stage stages sample :kotlin/ingest
                                #(kotlin-psi/ingest! conn {:project/id project-id}))
                     stages)
            stages (if (and (has-lang? source-files :lang/kotlin)
                             (not (stop-after-failure? stages)))
                     (run-stage stages sample :kotlin/enrich
                                #(kotlin-psi/enrich! conn
                                                     (merge {:project/id project-id}
                                                            (select-keys opts [:kotlin/classpath-types]))))
                     stages)
            stages (run-stage stages sample :transform/rules
                              #(let [rule-catalog (registered-rules source-files)]
                                 (rules/register! conn rule-catalog)
                                 {:rules/registered (count rule-catalog)
                                  :rules/langs (sort-by str (source-langs source-files))}))
            stages (if (stop-after-failure? stages)
                     stages
                     (let [db (d/db conn)
                           inventory-report (inventory/summary db)
                           coverage-opts (effective-coverage-opts opts source-files)
                           coverage-report (rules/coverage-report db coverage-opts)]
                       (write-edn! (.resolve (:target/diagnostics paths) "inventory.edn")
                                  inventory-report)
                       (write-edn! (.resolve (:target/diagnostics paths) "coverage.edn")
                                  (coverage-artifact coverage-report coverage-opts))
                       (conj stages
                             {:stage :diagnostics/inventory
                              :status :ok
                              :unsupported/features (count (:unsupported-rankings inventory-report))}
                             (coverage-stage coverage-report coverage-opts))))
            stages (cond-> stages
                     (and (not (stop-after-failure? stages))
                          (not (kotlin-sample? source-files)))
                     (run-stage sample :csharp/emit
                                #(csharp-emit-stage conn paths (assoc opts :sample sample))))
            stages (cond-> stages
                     (and (not (stop-after-failure? stages))
                          (kotlin-sample? source-files))
                     (conj (kotlin-csharp-emission-skipped-stage)))
            emit-stage (some #(when (= :csharp/emit (:stage %)) %) stages)
            stages (cond-> stages
                     (and (not (stop-after-failure? stages))
                          (= :ok (:status emit-stage)))
                     (run-stage sample :dotnet/build
                                #(dotnet-build! sample paths opts)))
            stages (cond-> stages
                     (and (not (stop-after-failure? stages))
                          emit-stage
                          (not= :ok (:status emit-stage))
                          (not (some #(= :dotnet/build (:stage %)) stages)))
                     (conj (dotnet-skipped-after-csharp-stage emit-stage)))
            build-stage (some #(when (= :dotnet/build (:stage %)) %) stages)
            stages (if (and (= :ok (:status emit-stage))
                            build-stage
                            (not= :skipped (:status build-stage)))
                     (conj stages
                           (stage sample :diagnostics/ingest
                                  #(ingest-dotnet-diagnostics! conn sample paths emit-stage build-stage)))
                     stages)]
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
         stages (run-analysis-stages sample paths opts)
         result {:sample/name (:sample/name sample)
                 :sample/root (:sample/root sample)
                 :source/root (:source/root sample)
                 :target/root (slash-path (normalize-path (:target/root paths)))
                 :ok? (not (stop-after-failure? stages))
                 :stages stages}]
     (write-edn! (.resolve (:target/diagnostics paths) "stages.edn") stages)
     (let [emit-stage (some #(when (= :csharp/emit (:stage %)) %) stages)
           coverage-stage (some #(when (= :coverage/check (:stage %)) %) stages)
           coverage-allow-mode (:coverage/allow-mode coverage-stage)]
       (write-edn! (:target/provenance paths)
                  (cond-> {:sample/name (:sample/name sample)
                           :source/root (:source/root sample)
                           :target/csharp (slash-path (normalize-path (:target/csharp paths)))}
                    (seq coverage-allow-mode)
                    (assoc :coverage/allow-mode coverage-allow-mode)

                    (and emit-stage (= :ok (:status emit-stage)))
                    (assoc :status (if (= :ok (:status emit-stage)) :generated :failed)
                           :reason (:reason emit-stage)
                           :csharp/files (:csharp/files emit-stage)
                           :csharp/files-written (:csharp/files-written emit-stage)
                           :csharp/rule-applications (:csharp/rule-applications emit-stage)
                           :csharp/provenance (:csharp/provenance emit-stage)
                           :csharp/diagnostics (:csharp/diagnostics emit-stage)
                           :csharp/error-diagnostics (:csharp/error-diagnostics emit-stage)
                           :csharp/error-diagnostics-count (:csharp/error-diagnostics-count emit-stage)
                           :csharp/allow-mode (:csharp/allow-mode emit-stage)
                           :csharp/helpers (:csharp/helpers emit-stage)
                           :csharp/usings (:csharp/usings emit-stage)
                           :csharp/project (:csharp/project emit-stage)
                           :csharp/project-target-framework (:csharp/project-target-framework emit-stage)
                           :csharp/project-files (:csharp/project-files emit-stage))

                    (and emit-stage (= :skipped (:status emit-stage)))
                    (assoc :status :skipped
                           :reason (:reason emit-stage)
                           :csharp/strategy (:csharp/strategy emit-stage))

                    (and emit-stage (= :failed (:status emit-stage)))
                    (assoc :status :failed
                           :reason (:reason emit-stage)
                           :csharp/files (:csharp/files emit-stage)
                           :csharp/files-written (:csharp/files-written emit-stage)
                           :csharp/rule-applications (:csharp/rule-applications emit-stage)
                           :csharp/provenance (:csharp/provenance emit-stage)
                           :csharp/diagnostics (:csharp/diagnostics emit-stage)
                           :csharp/error-diagnostics (:csharp/error-diagnostics emit-stage)
                           :csharp/error-diagnostics-count (:csharp/error-diagnostics-count emit-stage)
                           :csharp/allow-mode (:csharp/allow-mode emit-stage)
                           :csharp/helpers (:csharp/helpers emit-stage)
                           :csharp/usings (:csharp/usings emit-stage)
                           :csharp/project (:csharp/project emit-stage)
                           :csharp/project-target-framework (:csharp/project-target-framework emit-stage)
                           :csharp/project-files (:csharp/project-files emit-stage))

                    (nil? emit-stage)
                    (assoc :status :skipped
                           :reason :pipeline.stage/not-implemented))))
     result)))

(defn- parse-cli-opts [value]
  (if (nil? value)
    {}
    (let [opts (edn/read-string value)]
      (when-not (map? opts)
        (throw (ex-info "Sample runner options must be an EDN map."
                        {:value value
                         :parsed opts})))
      opts)))

(defn -main [& args]
  (let [[sample-name opts-edn & extra] args]
    (when (seq extra)
      (throw (ex-info "Unexpected sample runner arguments."
                      {:args args
                       :expected "sample-name optionally followed by one EDN options map"})))
    (let [sample-name (or sample-name default-sample)
          result (run-sample (assoc (parse-cli-opts opts-edn) :name sample-name))]
      (println (str "Sample " (:sample/name result)
                    " -> " (if (:ok? result) "ok" "failed")))
      (doseq [{:keys [stage status] :as stage-result} (:stages result)]
        (println (format "%-24s %s" (display-keyword stage) (display-keyword status)))
        (when (= :coverage/check stage)
          (println (format "  coverage ok: %s, failures: %s"
                           (:coverage/ok? stage-result)
                           (:coverage/failures stage-result)))
          (when-let [allow-mode (:coverage/allow-mode stage-result)]
            (println (format "  coverage allow mode: %s" (pr-str allow-mode)))))
        (when (and (= :csharp/emit stage) (:csharp/allow-mode stage-result))
          (println (format "  csharp allow mode: %s"
                           (pr-str (:csharp/allow-mode stage-result))))))
      (shutdown-agents)
      (when-not (:ok? result)
        (System/exit 1)))))
