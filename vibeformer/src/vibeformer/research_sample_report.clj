(ns vibeformer.research-sample-report
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.file Files Path Paths)))

(def default-project-id "research-pkl")
(def default-output-file "target/research-pkl/sample-selection.edn")
(def default-inventory-file "target/research-pkl/inventory.edn")
(def default-dry-run-file "target/research-pkl/dry-run.edn")
(def default-samples-root "sample-projects")
(def default-top 10)

(defn- path [value]
  (if (instance? Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- normalize-path [value]
  (.normalize (.toAbsolutePath (path value))))

(defn- resolve-path [project-root value]
  (let [p (path value)]
    (normalize-path
     (if (.isAbsolute p)
       p
       (.resolve project-root p)))))

(defn- slash-path [value]
  (str/replace (str value) \\ \/))

(defn- ensure-dir! [^Path dir]
  (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
  dir)

(defn- regular-file? [^Path file]
  (Files/isRegularFile file (make-array java.nio.file.LinkOption 0)))

(defn- read-edn-file [file]
  (when (regular-file? file)
    (edn/read-string (slurp (str file)))))

(defn- write-edn! [file value]
  (ensure-dir! (.getParent (path file)))
  (spit (str file) (str (pr-str value) "\n"))
  (slash-path (normalize-path file)))

(defn- top-n [n xs]
  (->> xs
       (sort-by (juxt (comp - long #(or (:count %) 0))
                      (comp - long #(or (:file-count %) 0))
                      #(str (:lang %))
                      #(str (:kind %))
                      #(str (:name %))
                      #(str (:reason %))))
       (take n)
       vec))

(defn- keyword-tail [value]
  (if (keyword? value)
    (name value)
    (str value)))

(defn- keyword-path [value]
  (if (keyword? value)
    (subs (str value) 1)
    (str value)))

(defn- compact-key [value]
  (-> (keyword-path value)
      (str/replace #"[^A-Za-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")
      str/lower-case))

(defn- candidate-id [& parts]
  (->> parts
       (remove nil?)
       (map compact-key)
       (remove str/blank?)
       (str/join ":")))

(defn- title-value [value fallback]
  (let [value (str value)]
    (if (str/blank? value)
      fallback
      value)))

(defn- suggested-bead [category title labels description]
  {:type :task
   :priority 2
   :labels (vec (distinct (concat ["samples" "vibeformer"] labels)))
   :title title
   :description description
   :source :research-sample-selection})

(defn- unresolved-candidate [entry]
  (let [lang (:lang entry)
        kind (:kind entry)
        name (title-value (:name entry) "<unnamed>")
        title (format "Add %s %s unresolved-reference sample for %s"
                      (keyword-tail lang)
                      (keyword-tail kind)
                      name)]
    (assoc entry
           :candidate/id (candidate-id "unresolved" lang kind name (:reason entry))
           :candidate/category :sample.category/unresolved-ref
           :candidate/source :research.inventory/unresolved-ref-detail
           :sample/focus (format "%s %s `%s` unresolved as %s across %s file(s)."
                                 (keyword-tail lang)
                                 (keyword-tail kind)
                                 name
                                 (keyword-tail (:reason entry))
                                 (or (:file-count entry) 0))
           :suggested-bead
           (suggested-bead :sample.category/unresolved-ref
                           title
                           [(keyword-tail lang) "resolution"]
                           (format "Create a focused sample that reproduces the `%s` unresolved reference from the research inventory, then fix the analyzer/model/rules so it resolves durably."
                                   name)))))

(defn- unresolved-api-candidate [entry]
  (let [lang (:lang entry)
        name (title-value (:name entry) "<unnamed>")
        owner (title-value (:owner entry) "<unowned>")
        title (format "Add %s API-call mapping sample for %s.%s"
                      (keyword-tail lang)
                      owner
                      name)]
    (assoc entry
           :candidate/id (candidate-id "unresolved-api" lang owner name (:reason entry))
           :candidate/category :sample.category/unresolved-api-call
           :candidate/source :research.inventory/unresolved-api-call
           :sample/focus (format "%s API call `%s.%s` unresolved as %s across %s file(s)."
                                 (keyword-tail lang)
                                 owner
                                 name
                                 (keyword-tail (:reason entry))
                                 (or (:file-count entry) 0))
           :suggested-bead
           (suggested-bead :sample.category/unresolved-api-call
                           title
                           [(keyword-tail lang) "api" "resolution"]
                           (format "Create a focused sample for `%s.%s`, then implement the durable semantic resolution and C# mapping path."
                                   owner
                                   name)))))

(defn- unsupported-candidate [entry]
  (let [lang (:lang entry)
        kind (:kind entry)
        title (format "Add %s unsupported-feature sample for %s"
                      (keyword-tail lang)
                      (keyword-tail kind))]
    (assoc entry
           :candidate/id (candidate-id "unsupported" lang kind)
           :candidate/category :sample.category/unsupported-construct
           :candidate/source :research.inventory/unsupported-ranking
           :sample/focus (format "%s construct `%s` is unsupported in %s file(s)."
                                 (keyword-tail lang)
                                 (keyword-tail kind)
                                 (or (:file-count entry) 0))
           :suggested-bead
           (suggested-bead :sample.category/unsupported-construct
                           title
                           [(keyword-tail lang) "coverage"]
                           (format "Create a focused sample for `%s` and replace the unsupported/stubbed pipeline behavior with durable facts, rules, or helpers."
                                   (keyword-tail kind))))))

(defn- coverage-candidate [entry]
  (let [lang (:lang entry)
        feature (or (:feature/kind entry)
                    (:kind entry)
                    (:rule/id entry)
                    (:feature/id entry)
                    (:node/kind entry))
        status (or (:feature/status entry)
                   (:rule/status entry)
                   (:status entry))]
    (assoc entry
           :candidate/id (candidate-id "coverage" lang feature status)
           :candidate/category :sample.category/coverage-gap
           :candidate/source :research.inventory/coverage-failure
           :sample/focus (format "Coverage gap `%s` remains %s with %s occurrence(s)."
                                 (keyword-tail feature)
                                 (keyword-tail status)
                                 (or (:count entry) 0))
           :suggested-bead
           (suggested-bead :sample.category/coverage-gap
                           (format "Add coverage sample for %s" (keyword-tail feature))
                           [(keyword-tail lang) "coverage"]
                           (format "Create a sample for `%s`, then fix the missing or stubbed rule path in the analyzer/model/rules."
                                   (keyword-tail feature))))))

(defn- diagnostic-files [samples-root]
  (if (Files/isDirectory samples-root (make-array java.nio.file.LinkOption 0))
    (with-open [stream (Files/walk samples-root (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator stream))
           (filter #(= "dotnet-diagnostic-facts.edn" (str (.getFileName ^Path %))))
           (sort-by str)
           vec))
    []))

(defn- provenance-files [samples-root]
  (if (Files/isDirectory samples-root (make-array java.nio.file.LinkOption 0))
    (with-open [stream (Files/walk samples-root (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator stream))
           (filter #(= "provenance.edn" (str (.getFileName ^Path %))))
           (sort-by str)
           vec))
    []))

(defn- sample-name-from-artifact [^Path file]
  (some-> file .getParent .getParent .getParent .getFileName str))

(defn- rank-diagnostic [sample-name entry]
  (assoc entry
         :sample/name sample-name))

(defn- diagnostic-rankings [samples-root]
  (->> (diagnostic-files samples-root)
       (mapcat (fn [file]
                 (let [report (read-edn-file file)
                       sample-name (sample-name-from-artifact file)
                       rankings (or (get-in report [:mapping-quality :unmapped-rankings])
                                    (:unmapped-rankings report)
                                    [])]
                   (map #(rank-diagnostic sample-name %) rankings))))
       (reduce (fn [acc entry]
                 (let [k (select-keys entry [:sample/name
                                             :diagnostic/code
                                             :diagnostic/severity
                                             :diagnostic/message
                                             :diagnostic/mapping-reason])
                       count (long (or (:count entry) 1))
                       file-count (long (or (:file-count entry) 1))]
                   (-> acc
                       (update-in [k :count] (fnil + 0) count)
                       (update-in [k :file-count] (fnil + 0) file-count))))
               {})
       (mapv (fn [[k v]] (merge k v)))))

(defn- compiler-diagnostic-candidate [entry]
  (let [code (title-value (:diagnostic/code entry) "<no-code>")
        sample-name (title-value (:sample/name entry) "<unknown-sample>")
        title (format "Add diagnostic regression sample for %s in %s"
                      code
                      sample-name)]
    (assoc entry
           :candidate/id (candidate-id "compiler-diagnostic"
                                       sample-name
                                       code
                                       (:diagnostic/mapping-reason entry))
           :candidate/category :sample.category/compiler-diagnostic
           :candidate/source :sample.diagnostics/unmapped-dotnet
           :sample/focus (format "%s diagnostic `%s` in `%s` is unmapped: %s"
                                 (keyword-tail (:diagnostic/severity entry))
                                 code
                                 sample-name
                                 (:diagnostic/message entry))
           :suggested-bead
           (suggested-bead :sample.category/compiler-diagnostic
                           title
                           ["diagnostics" "dotnet"]
                           (format "Create or update a sample that reproduces `%s`, then ingest and map the diagnostic through provenance instead of patching generated C#."
                                   code)))))

(defn- diagnostic-type-name [diagnostic]
  (or (get-in diagnostic [:rule/context :type/name])
      (get-in diagnostic [:context :type/name])
      (:type/name diagnostic)
      (:diagnostic/type diagnostic)
      (:source/name diagnostic)
      (:diagnostic/message diagnostic)))

(defn- missing-mapping-rankings [samples-root]
  (->> (provenance-files samples-root)
       (mapcat (fn [file]
                 (let [report (read-edn-file file)
                       sample-name (sample-name-from-artifact file)]
                   (->> (:csharp/diagnostics report)
                        (filter #(or (= :type-mapping/unknown (:rule/id %))
                                     (= :mapping.reason/unknown-type
                                        (get-in % [:rule/context :mapping/reason]))
                                     (str/includes? (str (:diagnostic/message %))
                                                    "No C# type mapping")))
                        (map #(assoc %
                                     :sample/name sample-name
                                     :type/name (diagnostic-type-name %)))))))
       (reduce (fn [acc entry]
                 (let [k (select-keys entry [:sample/name :type/name :rule/id])]
                   (update acc k (fnil inc 0))))
               {})
       (mapv (fn [[k count]]
               (assoc k
                      :count count
                      :file-count 1)))))

(defn- missing-mapping-candidate [entry]
  (let [type-name (title-value (:type/name entry) "<unknown-type>")
        sample-name (title-value (:sample/name entry) "<unknown-sample>")
        title (format "Add type-mapping sample for %s" type-name)]
    (assoc entry
           :candidate/id (candidate-id "missing-mapping" sample-name type-name)
           :candidate/category :sample.category/missing-mapping
           :candidate/source :sample.provenance/type-mapping-diagnostic
           :sample/focus (format "`%s` in `%s` has no durable C# type mapping."
                                 type-name
                                 sample-name)
           :suggested-bead
           (suggested-bead :sample.category/missing-mapping
                           title
                           ["type-mapping"]
                           (format "Create a focused sample for `%s`, then fix type mapping in the analyzer/model/rules/helpers path."
                                   type-name)))))

(defn- attach-priority [candidates]
  (mapv (fn [idx candidate]
          (assoc candidate :candidate/priority (inc idx)))
        (range)
        candidates))

(defn report
  "Build a research-driven sample selection report from inventory and sample artifacts."
  [inventory-report dry-run-report samples-root opts]
  (let [top (long (or (:top opts) default-top))
        sections {:unresolved-refs (mapv unresolved-candidate
                                         (top-n top (get-in inventory-report
                                                            [:inventory :unresolved-ref-detail-rankings])))
                  :unresolved-api-calls (mapv unresolved-api-candidate
                                              (top-n top (get-in inventory-report
                                                                 [:inventory :unresolved-api-call-rankings])))
                  :unsupported-constructs (mapv unsupported-candidate
                                                (top-n top (get-in inventory-report
                                                                   [:inventory :unsupported-rankings])))
                  :coverage-gaps (mapv coverage-candidate
                                       (top-n top (get-in inventory-report
                                                          [:coverage :failure-rankings])))
                  :missing-mappings (mapv missing-mapping-candidate
                                          (top-n top (missing-mapping-rankings samples-root)))
                  :compiler-diagnostics (mapv compiler-diagnostic-candidate
                                              (top-n top (diagnostic-rankings samples-root)))}
        candidates (->> [:unsupported-constructs
                         :coverage-gaps
                         :unresolved-refs
                         :unresolved-api-calls
                         :missing-mappings
                         :compiler-diagnostics]
                        (mapcat sections)
                        attach-priority)]
    {:report/type :vibeformer.report/research-sample-selection
     :project/id (or (:project/id opts)
                     (:project/id inventory-report)
                     (:project/id dry-run-report)
                     default-project-id)
     :source/files (:source/files inventory-report)
     :dry-run/mode (:dry-run/mode dry-run-report)
     :unresolved/total (some #(when (= :unresolved-reference-gate (:stage %))
                                (:unresolved/total %))
                             (:stages dry-run-report))
     :candidate/count (count candidates)
     :candidates candidates
     :sections sections}))

(defn run-report
  ([] (run-report {}))
  ([opts]
   (let [project-root (normalize-path (or (:project-root opts)
                                          (System/getProperty "user.dir")))
         inventory-file (resolve-path project-root
                                      (or (:inventory opts)
                                          (:inventory/file opts)
                                          default-inventory-file))
         dry-run-file (resolve-path project-root
                                    (or (:dry-run opts)
                                        (:dry-run/file opts)
                                        default-dry-run-file))
         samples-root (resolve-path project-root
                                    (or (:samples/root opts)
                                        (:samples-root opts)
                                        default-samples-root))
         output-file (resolve-path project-root
                                   (or (:sample-report/out opts)
                                       (:out opts)
                                       default-output-file))
         inventory-report (or (read-edn-file inventory-file)
                              (throw (ex-info "Research inventory report not found."
                                              {:inventory/file (slash-path inventory-file)})))
         dry-run-report (or (read-edn-file dry-run-file) {})
         report (assoc (report inventory-report dry-run-report samples-root opts)
                       :project/root (slash-path project-root)
                       :artifacts {:inventory (slash-path inventory-file)
                                   :dry-run (slash-path dry-run-file)
                                   :samples/root (slash-path samples-root)
                                   :sample-selection (slash-path output-file)})]
     (write-edn! output-file report)
     (assoc report :report/file (slash-path output-file)))))

(defn- parse-cli-opts [value]
  (if (nil? value)
    {}
    (let [opts (edn/read-string value)]
      (when-not (map? opts)
        (throw (ex-info "Research sample report options must be an EDN map."
                        {:value value
                         :parsed opts})))
      opts)))

(defn -main [& args]
  (let [[opts-edn & extra] args]
    (when (seq extra)
      (throw (ex-info "Unexpected research sample report arguments."
                      {:args args
                       :expected "optional EDN options map"})))
    (let [result (run-report (parse-cli-opts opts-edn))]
      (println (str "Research sample selection -> " (:report/file result)))
      (println (format "candidates: %s" (:candidate/count result)))
      (doseq [[section candidates] (:sections result)]
        (println (format "%-24s %s" (keyword-tail section) (count candidates))))
      (shutdown-agents))))
