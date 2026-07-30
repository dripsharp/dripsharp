(ns dripsharp.pdfcube.host-matrix
  "Fail-closed required-host evidence gate for the complete PdfCarton package
  family."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.paths :as paths])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(def supported-hosts
  [{:os "windows" :architecture "x64"}
   {:os "windows" :architecture "arm64"}
   {:os "linux" :architecture "x64"}
   {:os "linux" :architecture "arm64"}
   {:os "macos" :architecture "x64"}
   {:os "macos" :architecture "arm64"}])

(def required-hosts
  [{:os "macos" :architecture "x64"}
   {:os "macos" :architecture "arm64"}])

(def package-ids
  ["DripSharp.PdfCarton.IO"
   "DripSharp.PdfCarton.Fonts"
   "DripSharp.PdfCarton.Xmp"
   "DripSharp.PdfCarton"
   "DripSharp.PdfCarton.Preflight"])

(def capability-ids
  ["clean-restore"
   "clean-build"
   "family-workflow"
   "file-memory-mapping"
   "font-discovery"
   "xml"
   "cryptography"
   "cpu-rendering"
   "page-layout"
   "preflight"])

(defn evidence-file-name
  [{:keys [os architecture]}]
  (str "pdfcube-family-" os "-" architecture ".tsv"))

(defn expected-observations
  "Returns the exact successful evidence contract for one supported host."
  [{:keys [os architecture]}]
  (into
   {["schema" "version"] "pdfcube-family-host-v1"
    ["host" "os"] os
    ["host" "architecture"] architecture
    ["result" "status"] "passed"
    ["native-assets" "SkiaSharp"] "loaded"
    ["native-assets" "HarfBuzzSharp"] "not-selected"
    ["rendering" "backend"] "cpu"
    ["normalization" "policy"] "canonical-exact"}
   (concat
    (map (fn [package-id]
           [["package" package-id] "consumed"])
         package-ids)
    (map (fn [capability-id]
           [["capability" capability-id] "passed"])
         capability-ids))))

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-family-host-matrix-failed))))

(defn- evidence-files
  [^Path directory]
  (if-not (paths/directory? directory)
    []
    (with-open [entries (Files/list directory)]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (filter #(str/ends-with? (str (.getFileName ^Path %)) ".tsv"))
           (sort-by #(str (.getFileName ^Path %)))
           vec))))

(defn- parse-evidence
  [^Path file]
  (let [lines (vec (Files/readAllLines file StandardCharsets/UTF_8))]
    (when-not (seq lines)
      (fail! "PdfCarton host evidence is empty"
             {:file (str file)}))
    (reduce
     (fn [rows [index line]]
       (let [fields (str/split line #"\t" 3)]
         (when-not (and (= 3 (count fields))
                        (every? (complement str/blank?) fields))
           (fail! "PdfCarton host evidence contains a malformed row"
                  {:file (str file)
                   :line (inc index)
                   :value line}))
         (let [identity (subvec fields 0 2)]
           (when (contains? rows identity)
             (fail! "PdfCarton host evidence contains a duplicate observation"
                    {:file (str file)
                     :line (inc index)
                     :identity identity}))
           (assoc rows identity (nth fields 2)))))
     {}
     (map-indexed vector lines))))

(defn- observation-differences
  [expected actual]
  (let [expected-identities (set (keys expected))
        actual-identities (set (keys actual))]
    {:missing
     (vec (sort (set/difference expected-identities actual-identities)))
     :unexpected
     (vec (sort (set/difference actual-identities expected-identities)))
     :different
     (->> (set/intersection expected-identities actual-identities)
          (keep
           (fn [identity]
             (let [expected-value (get expected identity)
                   actual-value (get actual identity)]
               (when-not (= expected-value actual-value)
                 {:identity identity
                  :expected expected-value
                  :actual actual-value}))))
          (sort-by :identity)
          vec)}))

(defn- inspect-host
  [^Path evidence-root host]
  (let [file-name (evidence-file-name host)
        file (paths/resolve-path evidence-root file-name)]
    (if-not (paths/regular-file? file)
      {:host host
       :file file-name
       :status :missing
       :message "No evidence artifact was recorded for this matrix entry."}
      (try
        (let [actual (parse-evidence file)
              expected (expected-observations host)
              status (get actual ["result" "status"])
              differences (observation-differences expected actual)]
          (cond
            (= "failed" status)
            {:host host
             :file file-name
             :status :failed
             :message
             (or (get actual ["failure" "message"])
                 "The host smoke reported failure.")
             :observations (count actual)}

            (not= "passed" status)
            {:host host
             :file file-name
             :status :invalid
             :message "Host evidence has no recognized terminal status."
             :actual-status status
             :observations (count actual)}

            (some seq (vals differences))
            (merge
             {:host host
              :file file-name
              :status :invalid
              :message "Successful host evidence violates the exact contract."
              :observations (count actual)}
             differences)

            :else
            {:host host
             :file file-name
             :status :passed
             :observations (count actual)}))
        (catch clojure.lang.ExceptionInfo error
          {:host host
           :file file-name
           :status :invalid
           :message (.getMessage error)
           :error (dissoc (ex-data error) :kind)})
        (catch Throwable error
          {:host host
           :file file-name
           :status :invalid
           :message (.getMessage error)})))))

(defn validate-matrix
  "Inspects the exact required macOS evidence matrix. Missing, failed,
  malformed, stale, or invented entries remain explicit non-passing results.
  Evidence for other supported destination hosts is permitted but does not
  affect completion."
  [evidence-root]
  (let [root (paths/absolute (paths/path evidence-root))
        supported-files (set (map evidence-file-name supported-hosts))
        required-files (set (map evidence-file-name required-hosts))
        actual-files
        (set (map #(str (.getFileName ^Path %)) (evidence-files root)))
        unexpected-files
        (vec (sort (set/difference actual-files supported-files)))
        nonrequired-files
        (vec
         (sort
          (set/intersection
           actual-files
           (set/difference supported-files required-files))))
        hosts (mapv #(inspect-host root %) required-hosts)
        by-status (group-by :status hosts)
        passed (count (get by-status :passed))
        complete? (and (= (count required-hosts) passed)
                       (empty? unexpected-files))]
    {:schema :pdfcube-family-host-matrix-v2
     :supported-hosts (count supported-hosts)
     :expected-hosts (count required-hosts)
     :passed-hosts passed
     :complete complete?
     :missing-hosts (mapv :host (get by-status :missing))
     :failed-hosts (mapv :host (get by-status :failed))
     :invalid-hosts (mapv :host (get by-status :invalid))
     :nonrequired-files nonrequired-files
     :unexpected-files unexpected-files
     :hosts hosts}))

(defn- write-summary!
  [^Path output-root summary]
  (Files/createDirectories output-root (make-array FileAttribute 0))
  (Files/writeString
   (paths/resolve-path output-root "summary.edn")
   (str (pr-str summary) "\n")
   (make-array OpenOption 0))
  (Files/writeString
   (paths/resolve-path output-root "summary.tsv")
   (apply
    str
    (cons
     "os\tarchitecture\tstatus\tmessage\n"
     (for [{:keys [host status message]} (:hosts summary)]
       (str (:os host) "\t"
            (:architecture host) "\t"
            (name status) "\t"
            (str/replace (or message "") #"\s+" " ")
            "\n"))))
   (make-array OpenOption 0))
  summary)

(defn verify!
  "Writes a durable required-host summary and fails unless both exact macOS
  matrix entries have complete successful evidence."
  [evidence-root output-root]
  (let [summary (validate-matrix evidence-root)
        output (paths/absolute (paths/path output-root))]
    (write-summary! output summary)
    (when-not (:complete summary)
      (fail! "PdfCarton required-host matrix has missing or failed evidence"
             {:summary summary
              :report (str (paths/resolve-path output "summary.edn"))}))
    (println
     "Complete PdfCarton required-host matrix passed:"
     (pr-str
      {:hosts (:passed-hosts summary)
       :packages package-ids
       :capabilities capability-ids}))
    (assoc summary :proof-root output)))
