(ns vibeformer.compiler
  "Clean compilation and compiler-to-Spoon diagnostic correlation."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [vibeformer.harness :as harness]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Path]))

(def ^:private diagnostic-pattern
  #"^(.*\.cs)\((\d+),(\d+)\): (warning|error) (CS\d+): (.*?) \[(.*\.csproj)\]$")

(defn parse-diagnostics
  "Parses and de-duplicates ordinary Roslyn/MSBuild diagnostic lines."
  [output]
  (->> (str/split-lines (or output ""))
       (keep (fn [line]
               (when-let [[_ file line-number column severity code message project]
                          (re-matches diagnostic-pattern line)]
                 {:file file
                  :line (parse-long line-number)
                  :column (parse-long column)
                  :severity (keyword severity)
                  :code code
                  :message message
                  :project project})))
       distinct
       vec))

(defn- line-column-offset [text line column]
  (let [lines (str/split text #"\n" -1)]
    (when (and (pos? line) (<= line (count lines))
               (pos? column) (<= column (inc (count (nth lines (dec line))))))
      (+ (reduce + (map #(inc (count %)) (take (dec line) lines)))
         (dec column)))))

(defn map-diagnostic
  "Maps one compiler diagnostic through emitted offsets to its narrowest Spoon
  source element and translation rule."
  [^Path project-root mappings diagnostic]
  (let [file (paths/absolute (:file diagnostic))
        relative (str/replace (str (.relativize project-root file)) "\\" "/")
        text (slurp (str file))
        offset (line-column-offset text (:line diagnostic) (:column diagnostic))
        candidates (when offset
                     (filter (fn [mapping]
                               (let [{:keys [start end]} (:destination mapping)]
                                 (and (= relative (:file mapping))
                                      (<= start offset)
                                      (< offset end))))
                             mappings))
        mapping (first (sort-by (fn [candidate]
                                  (let [{:keys [start end]} (:destination candidate)]
                                    [(- end start) (- start)]))
                                candidates))]
    (cond-> (assoc diagnostic :generated-file relative :generated-offset offset)
      mapping (assoc :source (:source mapping)
                     :translation-rule (get-in mapping [:source :rule])))))

(defn map-diagnostics [project-root source-map diagnostics]
  (mapv #(map-diagnostic project-root (:mappings source-map) %) diagnostics))

(defn verify-clean-build!
  "Regenerates all disposable output and then builds exactly that fresh project.
  Compiler failures retain correlations to live-Spoon source mappings."
  ([] (verify-clean-build! {}))
  ([{:keys [generate-fn run-command!]
     :or {generate-fn harness/generate! run-command! process/run!}}]
   (let [generation (generate-fn)
         emission (:emission generation)
         ^Path project-root (:project-root emission)
         project-file (:project-file emission)
         source-map-file (paths/resolve-path
                          project-root
                          (get-in generation [:destination :output :source-map-file]))
         source-map (edn/read-string (slurp (str source-map-file)))
         command ["dotnet" "build" (str project-file) "--nologo"
                  "--verbosity:minimal" "--no-incremental"
                  "-p:RestoreIgnoreFailedSources=true" "-warnaserror"]]
     (try
       (let [result (run-command! {:command command :directory project-root})
             diagnostics (parse-diagnostics (:output result))]
         (when (seq diagnostics)
           (throw (ex-info "Clean pkl-parser build emitted compiler diagnostics"
                           {:kind :compiler-diagnostics
                            :diagnostics (map-diagnostics project-root source-map diagnostics)
                            :output (:output result)})))
         (println "Clean pkl-parser compilation: 0 warnings, 0 errors")
         {:generation generation :build result :diagnostics []})
       (catch ExceptionInfo error
         (let [data (ex-data error)]
           (if (= :command-failed (:kind data))
             (let [diagnostics (parse-diagnostics (:output data))]
               (throw (ex-info "Clean pkl-parser compilation failed"
                               (assoc data
                                      :kind :compiler-build-failed
                                      :diagnostics (map-diagnostics project-root source-map diagnostics))
                               error)))
             (throw error))))))))
