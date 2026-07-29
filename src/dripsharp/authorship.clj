(ns dripsharp.authorship
  "Deterministic per-file accounting and fail-closed policy for authored C#
  sources compiled into packages."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.charset Charset MalformedInputException]
           [java.nio.file FileVisitOption Files Path]
           [java.util.regex Pattern]))

(def schema-version 3)
(def policy-schema-version 2)
(def source-contract-schema-version 1)
(def spdx-policy-schema-version 2)

(def ^:private source-classes
  #{:mechanical :authored-compat :authored-destination-runtime
    :vendored-third-party})

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

(defn- read-source-text
  [^Path file charset]
  (try
    (Files/readString file)
    (catch MalformedInputException error
      (if charset
        (Files/readString file (Charset/forName charset))
        (throw error)))))

(def ^:private public-type-pattern
  #"(?ms)^[ \t]*public\b(?=[^{};]*?\b(?:class|interface|struct|record|enum|delegate)\b)[^{};]*?(?=[{;])")

(defn public-type-proof
  "Returns a conservative deterministic fingerprint of authored public type
  declarations.

  The normalized declaration headers deliberately include modifiers, generic
  parameters, base clauses, and ambiguous public headers containing a type
  keyword. The conservative match prevents formatting or preprocessor layout
  from hiding a new public type."
  [texts]
  (let [headers
        (->> texts
             (mapcat #(re-seq public-type-pattern %))
             (map #(str/replace (str/trim %) #"\s+" " "))
             sort
             vec)]
    {:count (count headers)
     :sha256 (util/sha256-text (str/join "\n" headers))}))

(defn- source-group-files
  [^Path workspace-root {:keys [kind provenance include-pattern]}]
  (let [source (paths/absolute
                (paths/resolve-path workspace-root provenance))]
    (case kind
      :file
      (if (paths/regular-file? source) [source] [])

      :tree
      (if-not (paths/directory? source)
        []
        (let [pattern (re-pattern include-pattern)]
          (with-open [files
                      (Files/walk source (make-array FileVisitOption 0))]
            (->> (.toArray files)
                 (map #(cast Path %))
                 (filter paths/regular-file?)
                 (filter csharp-path?)
                 (filter
                  #(re-matches
                    pattern
                    (str/replace (str (.relativize source %)) "\\" "/")))
                 (sort-by str)
                 vec))))

      [])))

(defn- source-links
  [^Path source-root]
  (let [candidates
        (cond
          (paths/directory? source-root)
          (with-open [files
                      (Files/walk source-root
                                  (make-array FileVisitOption 0))]
            (->> (.toArray files)
                 (map #(cast Path %))
                 vec))

          (Files/isSymbolicLink source-root)
          [source-root]

          :else
          [])]
    (->> candidates
         (filter #(Files/isSymbolicLink ^Path %))
         vec)))

(defn- unresolved-source-links
  [^Path workspace-root links]
  (->> links
       (remove paths/exists?)
       (mapv #(portable workspace-root %))))

(defn- untraversed-source-directory-links
  [^Path workspace-root links]
  (->> links
       (filter paths/exists?)
       (filter paths/directory?)
       (mapv #(portable workspace-root %))))

(defn- resolved-source-nondirectory-links
  [^Path workspace-root links]
  (->> links
       (filter paths/exists?)
       (remove paths/directory?)
       (mapv #(portable workspace-root %))))

(defn source-observation
  "Observes one contracted source group without trusting its asserted contract."
  [workspace-root source-group]
  (let [workspace-root (paths/absolute workspace-root)
        source-root
        (paths/absolute
         (paths/resolve-path workspace-root (:provenance source-group)))
        _ (when (and (paths/exists? source-root)
                     (not (paths/real-contained?
                           workspace-root source-root)))
            (fail! "Contracted source provenance resolves outside the workspace"
                   {:source (:id source-group)
                    :provenance (:provenance source-group)
                    :reason :outside-workspace}))
        observed-links (source-links source-root)
        unresolved-links
        (unresolved-source-links workspace-root observed-links)
        _ (when (seq unresolved-links)
            (fail! "Contracted source inventory contains unresolved symbolic links"
                   {:source (:id source-group)
                    :provenance (:provenance source-group)
                    :paths unresolved-links
                    :reason :unresolved-symbolic-link}))
        untraversed-directory-links
        (untraversed-source-directory-links workspace-root observed-links)
        _ (when (seq untraversed-directory-links)
            (fail!
             "Contracted source inventory contains untraversed symbolic-link directories"
             {:source (:id source-group)
              :provenance (:provenance source-group)
              :paths untraversed-directory-links
              :reason :untraversed-symbolic-link-directory}))
        resolved-nondirectory-links
        (resolved-source-nondirectory-links workspace-root observed-links)
        _ (when (seq resolved-nondirectory-links)
            (fail!
             "Contracted source inventory contains resolved symbolic links"
             {:source (:id source-group)
              :provenance (:provenance source-group)
              :paths resolved-nondirectory-links
              :reason :resolved-symbolic-link}))
        charset (:charset source-group)
        files (source-group-files workspace-root source-group)
        escaped-files
        (->> files
             (remove #(paths/real-contained? workspace-root %))
             (mapv #(portable workspace-root %)))
        _ (when (seq escaped-files)
            (fail! "Contracted source files resolve outside the workspace"
                   {:source (:id source-group)
                    :provenance (:provenance source-group)
                    :paths escaped-files
                    :reason :outside-workspace}))
        records
        (mapv
         (fn [^Path file]
           (let [text (read-source-text file charset)]
             {:provenance (portable workspace-root file)
              :lines (count (str/split-lines text))
              :text text}))
         files)
        paths (mapv :provenance records)]
    {:files (count records)
     :source-lines (reduce + 0 (map :lines records))
     :source-inventory-sha256 (util/sha256-text (str/join "\n" paths))
     :public-types (public-type-proof (map :text records))
     :paths paths}))

(defn- duplicate-values
  [values]
  (->> values
       frequencies
       (keep (fn [[value count]] (when (< 1 count) value)))
       sort
       vec))

(defn- physical-source-conflicts
  [^Path workspace-root groups]
  (let [entries
        (->> groups
             (mapcat
              (fn [{:keys [id class paths]}]
                (map
                 (fn [path]
                   {:path path :group id :class class
                    :file (paths/resolve-path workspace-root path)})
                 paths)))
             (sort-by :path)
             vec)]
    (->>
     (for [left-index (range (count entries))
           right-index (range (inc left-index) (count entries))
           :let [left (nth entries left-index)
                 right (nth entries right-index)]
           :when (and (not= (:path left) (:path right))
                      (Files/isSameFile ^Path (:file left)
                                        ^Path (:file right)))]
       {:paths [(:path left) (:path right)]
        :usages [(select-keys left [:group :class])
                 (select-keys right [:group :class])]})
     vec)))

(defn- authored?
  [class]
  (contains? #{:authored-compat :authored-destination-runtime} class))

(defn- contracted?
  [class]
  (contains? (disj source-classes :mechanical) class))

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
        third-party-lines
        (reduce + 0
                (map :lines
                     (filter #(= :vendored-third-party (:class %)) files)))
        authored-lines (+ compatibility-lines destination-lines)
        total-lines (+ mechanical-lines authored-lines third-party-lines)]
    {:files (count files)
     :mechanical-lines mechanical-lines
     :authored-compat-lines compatibility-lines
     :authored-destination-runtime-lines destination-lines
     :vendored-third-party-lines third-party-lines
     :authored-lines authored-lines
     :total-lines total-lines
     :authored-fraction
     (if (pos? total-lines)
       (/ (double authored-lines) (double total-lines))
       0.0)}))

(defn- exact-keys!
  [value expected subject]
  (when-not (and (map? value) (= expected (set (keys value))))
    (fail! "Source-accounting record has missing or unexpected fields"
           {:subject subject :expected expected
            :actual (when (map? value) (set (keys value)))}))
  value)

(def ^:private source-contract-keys
  #{:schema-version :scope :class :sources})

(def ^:private source-group-keys
  #{:id :kind :provenance :include-pattern :charset :capability :source-files
    :max-source-lines :max-emitted-lines :source-inventory-sha256
    :public-types})

(def ^:private public-type-proof-keys
  #{:count :sha256})

(defn- valid-sha256?
  [value]
  (boolean (re-matches #"[0-9a-f]{64}" (or value ""))))

(defn- normalized-relative-path?
  [value]
  (when (and (string? value) (not (str/blank? value)))
    (let [path (paths/path value)]
      (and (not (.isAbsolute path))
           (not (str/includes? value "\\"))
           (not-any? #(contains? #{"." ".."} (str %))
                     (iterator-seq (.iterator path)))))))

(def ^:private spdx-policy-keys
  #{:schema-version :decision :license-identifier :file-copyright-text
    :repository-notice})

(def ^:private repository-notice-keys
  #{:path :sha256})

(defn- nonblank-single-line?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not (re-find #"[\r\n\u0000]" value))))

(defn- validate-spdx-policy!
  [policy]
  (exact-keys! policy spdx-policy-keys :authored-spdx-policy)
  (let [{:keys [path sha256] :as notice} (:repository-notice policy)
        notice-path (when (string? path) (paths/path path))]
    (exact-keys! notice repository-notice-keys :repository-notice)
    (when-not
     (and (= spdx-policy-schema-version (:schema-version policy))
          (nonblank-single-line? (:decision policy))
          (nonblank-single-line? (:license-identifier policy))
          (nonblank-single-line? (:file-copyright-text policy))
          (normalized-relative-path? path)
          (= 1 (.getNameCount notice-path))
          (= path (str notice-path))
          (valid-sha256? sha256))
      (fail! "Authored SPDX policy is invalid"
             {:policy policy})))
  policy)

(defn verify-repository-notice!
  "Verifies the exact repository-root legal notice pinned by a human decision."
  [workspace-root policy]
  (let [workspace-root (paths/absolute workspace-root)
        policy (validate-spdx-policy! policy)
        {:keys [path sha256]} (:repository-notice policy)
        notice (paths/absolute (paths/resolve-path workspace-root path))
        actual (when (and (paths/real-contained? workspace-root notice)
                          (paths/regular-file? notice))
                 (util/sha256-file notice))]
    (when-not (= sha256 actual)
      (fail! "Repository legal notice is missing or differs from the approved decision"
             {:path path
              :decision (:decision policy)
              :expected sha256
              :actual actual}))
    {:path path :sha256 actual}))

(defn- spdx-header
  [{:keys [license-identifier file-copyright-text]}]
  (str "// SPDX-FileCopyrightText: " file-copyright-text "\n"
       "// SPDX-License-Identifier: " license-identifier "\n\n"))

(defn- spdx-marker-counts
  [text]
  {:file-copyright-text
   (count (re-seq #"SPDX-FileCopyrightText:" text))
   :license-identifier
   (count (re-seq #"SPDX-License-Identifier:" text))})

(defn verify-authored-spdx-headers!
  "Verifies the exact repository notice and SPDX header on authored sources.

  The caller supplies normalized source-contract groups from
  `validate-source-contract!`. Vendored third-party groups are deliberately
  excluded from DripSharp authorship claims. Mechanical translations are not
  source-contract groups and are therefore outside this operation."
  [workspace-root groups policy]
  (let [workspace-root (paths/absolute workspace-root)
        policy (validate-spdx-policy! policy)
        repository-notice (verify-repository-notice! workspace-root policy)
        groups (vec groups)
        invalid-groups
        (filterv
         #(not (and (qualified-keyword? (:id %))
                    (contracted? (:class %))
                    (vector? (:paths %))
                    (every? normalized-relative-path? (:paths %))))
         groups)]
    (when (seq invalid-groups)
      (fail! "SPDX verification requires normalized source-contract groups"
             {:groups invalid-groups}))
    (let [physical-conflicts
          (physical-source-conflicts workspace-root groups)
          _ (when (seq physical-conflicts)
              (fail! "SPDX source groups contain physical file aliases"
                     {:reason :physical-source-alias
                      :conflicts physical-conflicts}))
          authored-paths
          (->> groups
               (filter #(authored? (:class %)))
               (mapcat
                (fn [{:keys [charset paths]}]
                  (map #(vector % charset) paths)))
               (sort-by first)
               vec)
          duplicates (duplicate-values (map first authored-paths))
          expected-header (spdx-header policy)]
      (when (seq duplicates)
        (fail! "Authored SPDX source inventory contains duplicate paths"
               {:paths duplicates}))
      (doseq [[path charset] authored-paths]
        (let [source
              (paths/absolute
               (paths/resolve-path workspace-root path))
              readable?
              (and (paths/real-contained? workspace-root source)
                   (paths/regular-file? source))
              text (when readable? (read-source-text source charset))
              marker-counts (when text (spdx-marker-counts text))]
          (when-not
           (and text
                (str/starts-with? text expected-header)
                (= {:file-copyright-text 1
                    :license-identifier 1}
                   marker-counts))
            (fail! "Authored source lacks the exact approved SPDX header"
                   {:path path
                    :decision (:decision policy)
                    :license-identifier (:license-identifier policy)
                    :file-copyright-text (:file-copyright-text policy)
                    :spdx-marker-counts marker-counts}))))
      {:schema-version spdx-policy-schema-version
       :decision (:decision policy)
       :license-identifier (:license-identifier policy)
       :file-copyright-text (:file-copyright-text policy)
       :repository-notice repository-notice
       :paths (mapv first authored-paths)})))

(defn- verify-source-observation!
  [{:keys [id source-files max-source-lines source-inventory-sha256
           public-types]}
   observation]
  (when-not (and (= source-files (:files observation))
                 (<= (:source-lines observation) max-source-lines)
                 (= source-inventory-sha256
                    (:source-inventory-sha256 observation))
                 (= public-types (:public-types observation)))
    (fail! "Contracted source group drifted from its reviewed contract"
           {:source id
            :expected
            {:files source-files
             :max-source-lines max-source-lines
             :source-inventory-sha256 source-inventory-sha256
             :public-types public-types}
            :actual observation}))
  observation)

(defn validate-source-contract!
  "Validates a shared or target-owned source contract against the
  current source tree and returns its normalized groups keyed by id."
  [workspace-root expected-scope expected-class contract]
  (let [workspace-root (paths/absolute workspace-root)]
    (exact-keys! contract source-contract-keys :source-contract)
    (when-not (and (= source-contract-schema-version
                      (:schema-version contract))
                   (= expected-scope (:scope contract))
                   (= expected-class (:class contract))
                   (contains? #{:authored-compat
                                :authored-destination-runtime
                                :vendored-third-party}
                              (:class contract))
                   (vector? (:sources contract))
                   (or (contains? #{:authored-destination-runtime
                                    :vendored-third-party}
                                  expected-class)
                       (seq (:sources contract))))
      (fail! "Source contract identity or schema is invalid"
             {:expected-scope expected-scope
              :expected-class expected-class
              :contract
              (select-keys contract [:schema-version :scope :class])}))
    (let [ids (mapv :id (:sources contract))]
      (when-not (and (every? qualified-keyword? ids)
                     (= (count ids) (count (distinct ids))))
        (fail! "Source contract identities are invalid or duplicated"
               {:ids ids})))
    (let [groups
          (mapv
           (fn [{:keys [id kind provenance include-pattern charset capability
                        source-files
                        max-source-lines max-emitted-lines
                        source-inventory-sha256 public-types]
                 :as group}]
             (exact-keys! group source-group-keys id)
             (when-not (and (contains? #{:file :tree} kind)
                            (normalized-relative-path? provenance)
                            (or (= :tree kind)
                                (csharp-path? provenance))
                            (if (= :file kind)
                              (nil? include-pattern)
                              (and (string? include-pattern)
                                   (not (str/blank? include-pattern))
                                   (try
                                     (instance?
                                      Pattern
                                      (re-pattern include-pattern))
                                     (catch RuntimeException _ false))))
                            (if (= :authored-compat expected-class)
                              (keyword? capability)
                              (nil? capability))
                            (or (nil? charset)
                                (and (string? charset)
                                     (not (str/blank? charset))
                                     (Charset/isSupported charset)))
                            (pos-int? source-files)
                            (pos-int? max-source-lines)
                            (pos-int? max-emitted-lines)
                            (<= max-source-lines max-emitted-lines)
                            (valid-sha256? source-inventory-sha256))
               (fail! "Source group contract is invalid"
                      {:source id :group group}))
             (exact-keys! public-types public-type-proof-keys
                          [id :public-types])
             (when-not (and (nat-int? (:count public-types))
                            (valid-sha256? (:sha256 public-types)))
               (fail! "Contracted public-type fingerprint is invalid"
                      {:source id :public-types public-types}))
             (let [source-path
                   (paths/absolute
                    (paths/resolve-path workspace-root provenance))
                   _ (when-not (.startsWith source-path workspace-root)
                       (fail! "Contracted source group escapes the workspace"
                              {:source id :provenance provenance}))
                   observation
                   (verify-source-observation!
                    group (source-observation workspace-root group))]
               (assoc group
                      :class expected-class
                      :paths (:paths observation))))
           (:sources contract))
          path-owners
          (reduce
           (fn [owners {:keys [id paths]}]
             (reduce
              (fn [owners path]
                (if-let [owner (get owners path)]
                  (fail! "Contracted source groups overlap"
                         {:path path :owners [owner id]})
                  (assoc owners path id)))
              owners paths))
           {}
           groups)]
      (when-not (= (count path-owners)
                   (reduce + 0 (map #(count (:paths %)) groups)))
        (fail! "Authored source contract contains overlapping paths"
               {:paths path-owners}))
      {:schema-version source-contract-schema-version
       :scope expected-scope
       :class expected-class
       :sources (into (sorted-map) (map (juxt :id identity)) groups)})))

(def ^:private policy-contract-keys
  #{:schema-version :target :profile :package-id :review :evidence :budget
    :forbidden-identities :compatibility-sources :destination-sources
    :third-party-sources})

(def ^:private budget-keys
  #{:authored-lines :total-lines})

(defn validate-policy-contract!
  "Validates the normalized per-package source-accounting policy."
  [contract]
  (exact-keys! contract policy-contract-keys :authorship-policy)
  (let [{:keys [schema-version target profile package-id review evidence
                budget forbidden-identities compatibility-sources
                destination-sources third-party-sources]}
        contract
        groups (concat (vals compatibility-sources)
                       (vals destination-sources)
                       (vals third-party-sources))
        normalized-source-group-keys
        (conj source-group-keys :class :paths)
        _ (when-not (and (map? compatibility-sources)
                         (map? destination-sources)
                         (map? third-party-sources))
            (fail! "Source-accounting policy selections must be maps"
                   {:compatibility-sources compatibility-sources
                    :destination-sources destination-sources
                    :third-party-sources third-party-sources}))
        _ (doseq [[id group]
                  (concat compatibility-sources destination-sources
                          third-party-sources)]
            (exact-keys! group normalized-source-group-keys id))
        identified-groups?
        (and
         (every? (fn [[id group]] (= id (:id group)))
                 compatibility-sources)
         (every? (fn [[id group]] (= id (:id group)))
                 destination-sources)
         (every? (fn [[id group]] (= id (:id group)))
                 third-party-sources))
        valid?
        (and (= policy-schema-version schema-version)
             (keyword? target)
             (string? profile) (not (str/blank? profile))
             (string? package-id) (not (str/blank? package-id))
             (string? review) (not (str/blank? review))
             (vector? evidence) (seq evidence)
             (= (count evidence) (count (distinct evidence)))
             (every? keyword? evidence)
             (vector? forbidden-identities)
             (seq forbidden-identities)
             (= (count forbidden-identities)
                (count (distinct forbidden-identities)))
             (every? #(and (string? %) (not (str/blank? %)))
                     forbidden-identities)
             (map? compatibility-sources)
             (map? destination-sources)
             (map? third-party-sources)
             (every? qualified-keyword?
                     (concat (keys compatibility-sources)
                             (keys destination-sources)
                             (keys third-party-sources)))
             identified-groups?
             (every? #(= :authored-compat (:class %))
                     (vals compatibility-sources))
             (every? #(= :authored-destination-runtime (:class %))
                     (vals destination-sources))
             (every? #(= :vendored-third-party (:class %))
                     (vals third-party-sources))
             (every?
              (fn [{:keys [paths source-files max-source-lines
                           max-emitted-lines source-inventory-sha256
                           public-types]}]
                (and (vector? paths)
                     (= source-files (count paths))
                     (= (count paths) (count (distinct paths)))
                     (every? normalized-relative-path? paths)
                     (pos-int? source-files)
                     (pos-int? max-source-lines)
                     (pos-int? max-emitted-lines)
                     (<= max-source-lines max-emitted-lines)
                     (valid-sha256? source-inventory-sha256)
                     (map? public-types)
                     (= public-type-proof-keys
                        (set (keys public-types)))
                     (nat-int? (:count public-types))
                     (valid-sha256? (:sha256 public-types))))
              groups)
             (nat-int? (:authored-lines budget))
             (pos-int? (:total-lines budget))
             (<= (:authored-lines budget) (:total-lines budget))
             (= (:authored-lines budget)
                (reduce
                 + 0
                 (map :max-emitted-lines
                      (filter #(authored? (:class %)) groups)))))]
    (exact-keys! budget budget-keys :authorship-budget)
    (when-not valid?
      (fail! "Authorship policy contract is invalid"
             {:contract contract}))
    contract))

(defn- target-identity-match
  [text fragment]
  (let [pattern
        (re-pattern
         (str "(?i)(?<![A-Za-z0-9_])"
              (Pattern/quote fragment)
              "(?![A-Za-z0-9_])"))]
    (boolean (re-find pattern text))))

(defn verify-compatibility-neutrality!
  "Scans every reviewed shared compatibility group for product identities.

  Callers intentionally pass all shared groups, not only the ones selected by
  one package, so a future target extends the neutrality guard over the whole
  compatibility layer."
  [workspace-root groups forbidden-identities context]
  (let [workspace-root (paths/absolute workspace-root)]
    (doseq [{:keys [id class charset paths]} groups
            :when (= :authored-compat class)
            path paths
            :let [text
                  (read-source-text
                   (paths/resolve-path workspace-root path) charset)]
            fragment forbidden-identities
            :when (target-identity-match text fragment)]
      (fail! "Authored compatibility source leaks a target identity"
             (merge context
                    {:source id :path path :fragment fragment}))))
  groups)

(defn- policy-groups
  [contract]
  (sort-by
   (comp str :id)
   (concat (vals (:compatibility-sources contract))
           (vals (:destination-sources contract))
           (vals (:third-party-sources contract)))))

(defn- verify-policy!
  [{:keys [workspace-root project-root configuration contract]} files totals]
  (let [contract (validate-policy-contract! contract)
        _ (when-not (= (:package-id contract)
                       (get-in configuration [:package :id]))
            (fail! "Authorship policy package identity differs from the destination"
                   {:expected (get-in configuration [:package :id])
                    :actual (:package-id contract)}))
        contracted-files (filterv #(contracted? (:class %)) files)
        contracted-provenance (mapv :provenance contracted-files)
        duplicate-provenance (duplicate-values contracted-provenance)
        _ (when (seq duplicate-provenance)
            (fail! "Package contains duplicate contracted source provenance"
                   {:provenance duplicate-provenance}))
        actual-by-provenance (into {} (map (juxt :provenance identity))
                                   contracted-files)
        groups
        (mapv
         (fn [{:keys [id class max-emitted-lines public-types]
               :as group}]
           (let [observation
                 (verify-source-observation!
                  group (source-observation workspace-root group))
                 expected-paths (set (:paths observation))
                 actual-entries
                 (mapv actual-by-provenance (:paths observation))]
             (when (some nil? actual-entries)
               (fail! "Reviewed contracted source is missing from the package ledger"
                      {:source id
                       :missing
                       (vec
                        (sort
                         (set/difference
                          expected-paths
                          (set (keys actual-by-provenance)))))}))
             (let [emitted-lines
                   (reduce + 0 (map :lines actual-entries))
                   emitted-texts
                   (map
                    (fn [{:keys [path]}]
                      (Files/readString
                       (paths/resolve-path project-root path)))
                    actual-entries)
                   emitted-public-types (public-type-proof emitted-texts)]
               (when-not (every? #(= class (:class %)) actual-entries)
                 (fail! "Source class differs from its reviewed contract"
                        {:source id :expected class
                         :actual (mapv :class actual-entries)}))
               (when (> emitted-lines max-emitted-lines)
                 (fail! "Contracted source grew beyond its reviewed line budget"
                        {:source id :expected-at-most max-emitted-lines
                         :actual emitted-lines}))
               (when-not (= public-types emitted-public-types)
                 (fail! "Contracted public type surface differs from its reviewed evidence contract"
                        {:source id :expected public-types
                         :actual emitted-public-types}))
               {:id id
                :class class
                :source-paths (:paths observation)
                :source-inventory-sha256
                (:source-inventory-sha256 observation)
                :emitted-lines emitted-lines
                :max-emitted-lines max-emitted-lines
                :public-types emitted-public-types
                :evidence (:evidence contract)})))
         (policy-groups contract))
        _ (verify-compatibility-neutrality!
           workspace-root
           (vals (:compatibility-sources contract))
           (:forbidden-identities contract)
           {:target (:target contract)})
        expected-provenance (set (mapcat :source-paths groups))
        actual-provenance (set (keys actual-by-provenance))
        budget (:budget contract)
        authored-lines (:authored-lines totals)
        total-lines (:total-lines totals)]
    (when-not (= expected-provenance actual-provenance)
      (fail! "Package contracted sources differ from its reviewed contract"
             {:missing
              (vec (sort (set/difference expected-provenance
                                         actual-provenance)))
              :unexpected
              (vec (sort (set/difference actual-provenance
                                         expected-provenance)))}))
    (when (> authored-lines (:authored-lines budget))
      (fail! "Package authored lines exceed its reviewed budget"
             {:expected-at-most (:authored-lines budget)
              :actual authored-lines}))
    (when (>
           (*' authored-lines (:total-lines budget))
           (*' (:authored-lines budget) total-lines))
      (fail! "Package authored fraction exceeds its reviewed budget"
             {:expected
              {:numerator (:authored-lines budget)
               :denominator (:total-lines budget)}
              :actual {:numerator authored-lines
                       :denominator total-lines}}))
    {:schema-version policy-schema-version
     :target (:target contract)
     :profile (:profile contract)
     :package-id (:package-id contract)
     :review (:review contract)
     :evidence (:evidence contract)
     :budget
     (assoc budget
            :authored-fraction
            (/ (double (:authored-lines budget))
               (double (:total-lines budget))))
     :guarded-compatibility-sources
     (reduce + 0
             (map #(count (:source-paths %))
                  (filter #(= :authored-compat (:class %)) groups)))
     :sources groups}))

(defn- contracted-provenance-path
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
  assembly. When a package policy is supplied, authored inventory, growth,
  public types, evidence, fraction budget, and compatibility neutrality are
  recomputed rather than trusted from the ledger."
  [{:keys [workspace-root project-root source-root mechanical-source
           mechanical-header ledger contract configuration]}]
  (let [workspace-root (paths/absolute workspace-root)
        project-root (paths/absolute project-root)
        source-root (paths/absolute source-root)]
    (exact-keys! ledger #{:schema-version :files :totals :policy} :ledger)
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
                    (contracted-provenance-path workspace-root provenance))
                  actual-hash (util/sha256-file file)]
              (when-not (and provenance-file
                             (paths/regular-file? provenance-file))
                (fail! "Contracted source provenance is missing"
                       {:path path :provenance provenance}))
              (when-not (and (re-matches #"[0-9a-f]{64}"
                                         (or (:sha256 entry) ""))
                             (= actual-hash (:sha256 entry)))
                (fail! "Contracted source SHA-256 differs from the emitted assembly input"
                       {:path path :expected actual-hash
                        :actual (:sha256 entry)}))))))
      (let [expected-totals (totals files)
            expected-policy
            (when contract
              (verify-policy!
               {:workspace-root workspace-root
                :project-root project-root
                :configuration configuration
                :contract contract}
               files expected-totals))]
        (when-not (= expected-totals (:totals ledger))
          (fail! "Authorship ledger totals do not reconcile with its files"
                 {:expected expected-totals :actual (:totals ledger)}))
        (when-not (= expected-policy (:policy ledger))
          (fail! "Authorship ledger policy proof differs from the live package contract"
                 {:expected expected-policy :actual (:policy ledger)}))
        {:schema-version schema-version
         :verified-files (count files)
         :source-paths paths
         :source-inventory-sha256
         (util/sha256-text (str/join "\n" paths))
         :totals expected-totals
         :policy expected-policy}))))

(defn create-ledger!
  "Builds and verifies the schema-versioned ledger for one emitted project."
  [{:keys [workspace-root project-root artifacts mechanical-source contract
           configuration]
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
                   (when-not (contracted? authorship-class)
                     (fail! "Contracted C# artifact lacks an exact source class"
                            {:path file :authorship-class authorship-class}))
                   {:path file
                    :class authorship-class
                    :provenance (:file source)
                    :sha256 (util/sha256-file output)
                    :lines lines}))))
           (sort-by :file csharp-artifacts))
          ledger
          {:schema-version schema-version
           :files files
           :totals (totals files)
           :policy
           (when contract
             (verify-policy!
              {:workspace-root workspace-root
               :project-root project-root
               :configuration configuration
               :contract contract}
              files (totals files)))}]
      (verify-ledger!
       (assoc context
              :workspace-root workspace-root
              :project-root project-root
              :ledger ledger))
      ledger)))
