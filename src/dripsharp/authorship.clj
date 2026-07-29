(ns dripsharp.authorship
  "Deterministic per-file accounting for the C# sources compiled into packages."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file FileVisitOption Files Path]))

(def schema-version 1)

(def ^:private source-classes
  #{:mechanical :authored-compat :authored-destination-runtime})

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :invalid-authorship-ledger))))

(defn- csharp-path?
  [value]
  (str/ends-with? (str/lower-case (str value)) ".cs"))

(defn- portable
  [^Path root value]
  (util/portable-or-absolute-path root (paths/absolute value)))

(defn- csharp-files
  [^Path source-root]
  (if-not (paths/directory? source-root)
    []
    (with-open [files (Files/walk source-root (make-array FileVisitOption 0))]
      (->> (.toArray files)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (filter csharp-path?)
           (sort-by str)
           vec))))

(defn- line-count
  [^Path file]
  (count (str/split-lines (Files/readString file))))

(defn- duplicate-values
  [values]
  (->> values
       frequencies
       (keep (fn [[value count]] (when (< 1 count) value)))
       sort
       vec))

(defn- authored?
  [class]
  (contains? #{:authored-compat :authored-destination-runtime} class))

(defn- totals
  [files]
  (let [mechanical-lines
        (reduce + 0 (map :lines (filter #(= :mechanical (:class %)) files)))
        compatibility-lines
        (reduce + 0 (map :lines (filter #(= :authored-compat (:class %)) files)))
        destination-lines
        (reduce + 0
                (map :lines
                     (filter #(= :authored-destination-runtime (:class %))
                             files)))
        authored-lines (+ compatibility-lines destination-lines)
        total-lines (+ mechanical-lines authored-lines)]
    {:files (count files)
     :mechanical-lines mechanical-lines
     :authored-compat-lines compatibility-lines
     :authored-destination-runtime-lines destination-lines
     :authored-lines authored-lines
     :total-lines total-lines
     :authored-fraction
     (if (pos? total-lines)
       (/ (double authored-lines) (double total-lines))
       0.0)}))

(defn- exact-keys!
  [value expected subject]
  (when-not (and (map? value) (= expected (set (keys value))))
    (fail! "Authorship ledger record has missing or unexpected fields"
           {:subject subject :expected expected
            :actual (when (map? value) (set (keys value)))}))
  value)

(defn- authored-provenance-path
  [^Path workspace-root provenance]
  (let [path (paths/path provenance)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path workspace-root path)))))

(defn verify-ledger!
  "Reconciles a ledger against the actual C# compile source directory.

  Mechanical entries are checked against the configured upstream revision and
  exact generated header. Authored entries are checked against their durable
  provenance path and the SHA-256 of the emitted source that entered the
  assembly."
  [{:keys [workspace-root project-root source-root mechanical-source
           mechanical-header ledger]}]
  (let [workspace-root (paths/absolute workspace-root)
        project-root (paths/absolute project-root)
        source-root (paths/absolute source-root)]
    (exact-keys! ledger #{:schema-version :files :totals} :ledger)
    (when-not (= schema-version (:schema-version ledger))
      (fail! "Authorship ledger schema is unsupported"
             {:expected schema-version :actual (:schema-version ledger)}))
    (when-not (vector? (:files ledger))
      (fail! "Authorship ledger files must be an ordered vector"
             {:actual (type (:files ledger))}))
    (let [files (:files ledger)
          paths (mapv :path files)
          duplicates (duplicate-values paths)
          actual-files (csharp-files source-root)
          actual-paths (mapv #(portable project-root %) actual-files)]
      (when (seq duplicates)
        (fail! "Authorship ledger contains duplicate file paths"
               {:duplicates duplicates}))
      (when-not (= (vec (sort paths)) paths)
        (fail! "Authorship ledger file paths are not deterministic"
               {:expected (vec (sort paths)) :actual paths}))
      (when-not (= actual-paths paths)
        (fail! "Authorship ledger does not equal the assembly source inventory"
               {:expected actual-paths :actual paths
                :missing (vec (sort (set/difference (set actual-paths)
                                                    (set paths))))
                :unexpected (vec (sort (set/difference (set paths)
                                                       (set actual-paths))))}))
      (doseq [{:keys [path class lines] :as entry} files]
        (when-not (contains? source-classes class)
          (fail! "Authorship ledger contains an unsupported source class"
                 {:path path :class class :allowed source-classes}))
        (exact-keys!
         entry
         (if (= :mechanical class)
           #{:path :class :source :lines}
           #{:path :class :provenance :sha256 :lines})
         path)
        (let [file (paths/absolute (paths/resolve-path project-root path))]
          (when-not (and (.startsWith file source-root)
                         (paths/regular-file? file))
            (fail! "Authorship ledger path is outside the compile source inventory"
                   {:path path :source-root (str source-root)}))
          (let [actual-lines (line-count file)]
            (when-not (= actual-lines lines)
              (fail! "Authorship ledger line total differs from the emitted source"
                     {:path path :expected actual-lines :actual lines})))
          (if (= :mechanical class)
            (let [source (:source entry)]
              (exact-keys! source #{:file :revision} [path :source])
              (when-not (and (string? (:file source))
                             (not (str/blank? (:file source)))
                             (= (:revision mechanical-source)
                                (:revision source)))
                (fail! "Mechanical authorship provenance differs from the pinned upstream source"
                       {:path path
                        :expected-revision (:revision mechanical-source)
                        :actual source}))
              (let [expected (when mechanical-header
                               (mechanical-header mechanical-source
                                                  (:file source)))
                    actual (Files/readString file)]
                (when-not (and expected (str/starts-with? actual expected))
                  (fail! "Mechanical authorship entry does not match its exact source header"
                         {:path path :source source}))))
            (let [provenance (:provenance entry)
                  provenance-file
                  (when (and (string? provenance)
                             (not (str/blank? provenance)))
                    (authored-provenance-path workspace-root provenance))
                  actual-hash (util/sha256-file file)]
              (when-not (and provenance-file
                             (paths/regular-file? provenance-file))
                (fail! "Authored source provenance is missing"
                       {:path path :provenance provenance}))
              (when-not (and (re-matches #"[0-9a-f]{64}"
                                         (or (:sha256 entry) ""))
                             (= actual-hash (:sha256 entry)))
                (fail! "Authored source SHA-256 differs from the emitted assembly input"
                       {:path path :expected actual-hash
                        :actual (:sha256 entry)}))))))
      (let [expected-totals (totals files)]
        (when-not (= expected-totals (:totals ledger))
          (fail! "Authorship ledger totals do not reconcile with its files"
                 {:expected expected-totals :actual (:totals ledger)}))
        {:schema-version schema-version
         :verified-files (count files)
         :source-paths paths
         :source-inventory-sha256
         (util/sha256-text (str/join "\n" paths))
         :totals expected-totals}))))

(defn create-ledger!
  "Builds and verifies the schema-versioned ledger for one emitted project."
  [{:keys [workspace-root project-root artifacts mechanical-source]
    :as context}]
  (let [workspace-root (paths/absolute workspace-root)
        project-root (paths/absolute project-root)
        csharp-artifacts (filterv #(csharp-path? (:file %)) artifacts)
        duplicates (duplicate-values (map :file csharp-artifacts))]
    (when (seq duplicates)
      (fail! "Emission contains duplicate C# artifacts"
             {:duplicates duplicates}))
    (let [files
          (mapv
           (fn [{:keys [file upstream-source mechanical-source-header
                        authorship-class source]}]
             (let [output (paths/resolve-path project-root file)
                   lines (line-count output)]
               (if mechanical-source-header
                 (do
                   (when-not (and (nil? authorship-class)
                                  (string? upstream-source)
                                  (not (str/blank? upstream-source)))
                     (fail! "Mechanical source has invalid authorship metadata"
                            {:path file :upstream-source upstream-source
                             :authorship-class authorship-class}))
                   {:path file
                    :class :mechanical
                    :source {:file upstream-source
                             :revision (:revision mechanical-source)}
                    :lines lines})
                 (do
                   (when-not (authored? authorship-class)
                     (fail! "Authored C# artifact lacks an exact authorship class"
                            {:path file :authorship-class authorship-class}))
                   {:path file
                    :class authorship-class
                    :provenance (:file source)
                    :sha256 (util/sha256-file output)
                    :lines lines}))))
           (sort-by :file csharp-artifacts))
          ledger {:schema-version schema-version
                  :files files
                  :totals (totals files)}]
      (verify-ledger!
       (assoc context
              :workspace-root workspace-root
              :project-root project-root
              :ledger ledger))
      ledger)))
