(ns dripsharp.project-input
  "Build-tool-neutral, fail-closed Java project inputs.

  Discovery backends adapt their native project model into this schema before
  orchestration, Spoon, or destination rules consume it."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Files Path]
           [java.security MessageDigest]))

(def ^:private input-keys
  #{:schema-version
    :project-id
    :project-root
    :source-roots
    :resource-roots
    :production-sources
    :generated-production-sources
    :production-resources
    :java-toolchain
    :project-dependencies
    :external-dependencies
    :classpath-artifacts})

(def ^:private required-input-keys
  (disj input-keys :project-root))

(def ^:private scopes #{:compile :runtime})

(defn- invalid!
  [message data]
  (throw (ex-info message (assoc data :kind :invalid-project-input))))

(defn- exact-keys!
  [field value required allowed]
  (when-not (map? value)
    (invalid! "Java project input contains a non-map record"
              {:field field :value value}))
  (let [actual (set (keys value))
        missing (sort (remove actual required))
        unknown (sort (remove allowed actual))]
    (when (or (seq missing) (seq unknown))
      (invalid! "Java project input record has missing or unknown fields"
                {:field field :missing-fields missing :unknown-fields unknown
                 :value value})))
  value)

(defn- nonblank-string!
  [field value]
  (when-not (and (string? value) (not (str/blank? value)))
    (invalid! "Java project input identity is blank or not a string"
              {:field field :value value}))
  value)

(defn- scope!
  [field value]
  (when-not (contains? scopes value)
    (invalid! "Java project input has an unsupported dependency scope"
              {:field field :scope value :supported (sort scopes)}))
  value)

(defn- file-sha256
  [^Path input]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (Files/newInputStream
                        input
                        (make-array java.nio.file.OpenOption 0))]
      (let [buffer (byte-array 8192)]
        (loop [read (.read stream buffer)]
          (when-not (neg? read)
            (when (pos? read)
              (.update digest buffer 0 read))
            (recur (.read stream buffer))))))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- path-vector!
  [field values predicate missing-kind]
  (when-not (vector? values)
    (invalid! "Java project input path collection must be a vector"
              {:field field :value values}))
  (let [normalized
        (mapv
         (fn [value]
           (when-not (instance? Path value)
             (invalid! "Java project input path is not a java.nio.file.Path"
                       {:field field :value value}))
           (let [path (paths/absolute value)]
             (when-not (predicate path)
               (invalid! "Java project input path is missing or has the wrong kind"
                         {:field field :path (str path)
                          :missing-kind missing-kind}))
             path))
         values)
        ordered (vec (sort-by str normalized))]
    (when-not (= (count ordered) (count (distinct ordered)))
      (invalid! "Java project input path collection contains duplicates"
                {:field field :paths (mapv str ordered)}))
    ordered))

(defn- validate-dependencies!
  [field values identity-key]
  (when-not (vector? values)
    (invalid! "Java project dependencies must be a vector"
              {:field field :value values}))
  (let [records
        (mapv
         (fn [value]
           (exact-keys! field value #{:scope identity-key}
                        #{:scope identity-key})
           {:scope (scope! field (:scope value))
            identity-key (nonblank-string! identity-key
                                           (get value identity-key))})
         values)
        ordered (vec (sort-by (juxt identity-key :scope) records))]
    (when-not (= (count ordered) (count (distinct ordered)))
      (invalid! "Java project dependencies contain duplicate records"
                {:field field :records ordered}))
    ordered))

(defn- validate-classpath-artifacts!
  [values]
  (when-not (vector? values)
    (invalid! "Java project classpath artifacts must be a vector"
              {:field :classpath-artifacts :value values}))
  (let [records
        (mapv
         (fn [value]
           (exact-keys! :classpath-artifacts value #{:scope :path}
                        #{:scope :path :project-id :coordinate :sha256})
           (when (and (contains? value :project-id)
                      (contains? value :coordinate))
             (invalid! "Classpath artifact cannot have both project and external identities"
                       {:field :classpath-artifacts :value value}))
           (let [path (:path value)]
             (when-not (instance? Path path)
               (invalid! "Classpath artifact path is not a java.nio.file.Path"
                         {:field :classpath-artifacts :value path}))
             (let [path (paths/absolute path)
                   file? (Files/isRegularFile path paths/no-links)
                   directory? (Files/isDirectory path paths/no-links)
                   sha256 (:sha256 value)]
               (when-not (or file? directory?)
                 (invalid! "Classpath artifact is missing"
                           {:field :classpath-artifacts :path (str path)}))
               (when (contains? value :project-id)
                 (nonblank-string! :project-id (:project-id value)))
               (when (contains? value :coordinate)
                 (nonblank-string! :coordinate (:coordinate value)))
               (when (and sha256
                          (not (and file?
                                    (re-matches #"[0-9a-f]{64}" sha256))))
                 (invalid! "Classpath artifact has an invalid SHA-256 hash"
                           {:field :classpath-artifacts :path (str path)
                            :sha256 sha256}))
               (when sha256
                 (let [actual (file-sha256 path)]
                   (when-not (= sha256 actual)
                     (invalid! "Classpath artifact SHA-256 does not match its contents"
                               {:field :classpath-artifacts :path (str path)
                                :expected sha256 :actual actual}))))
               (when (and (contains? value :coordinate) (nil? sha256))
                 (invalid! "External classpath artifact is missing its SHA-256 hash"
                           {:field :classpath-artifacts :path (str path)
                            :coordinate (:coordinate value)}))
               (cond-> {:scope (scope! :classpath-artifacts (:scope value))
                        :path path}
                 (:project-id value) (assoc :project-id (:project-id value))
                 (:coordinate value) (assoc :coordinate (:coordinate value))
                 sha256 (assoc :sha256 sha256)))))
         values)
        ordered (vec (sort-by (juxt (comp str :path) :scope
                                    #(or (:project-id %) "")
                                    #(or (:coordinate %) "")
                                    #(or (:sha256 %) ""))
                              records))]
    (when-not (= (count ordered) (count (distinct ordered)))
      (invalid! "Java project classpath contains duplicate artifacts"
                {:field :classpath-artifacts :artifacts ordered}))
    ordered))

(defn- validate-toolchain!
  [toolchain]
  (exact-keys! :java-toolchain toolchain
               #{:home :release :preview-features?}
               #{:home :release :preview-features?})
  (let [home (:home toolchain)
        release (:release toolchain)
        preview? (:preview-features? toolchain)]
    (when-not (instance? Path home)
      (invalid! "Java toolchain home is not a java.nio.file.Path"
                {:field :java-toolchain :value home}))
    (let [home (paths/absolute home)]
      (when-not (paths/directory? home)
        (invalid! "Java toolchain home is missing"
                  {:field :java-toolchain :path (str home)}))
      (when-not (and (integer? release) (pos? release))
        (invalid! "Java toolchain release is not a positive integer"
                  {:field :java-toolchain :release release}))
      (when-not (boolean? preview?)
        (invalid! "Java preview-feature setting is not boolean"
                  {:field :java-toolchain :preview-features? preview?}))
      {:home home :release release :preview-features? preview?})))

(defn- inside-any-root?
  [roots ^Path input]
  (some #(.startsWith (.normalize input) (.normalize ^Path %)) roots))

(defn- validate-root-membership!
  [field roots inputs]
  (when (seq roots)
    (doseq [^Path input inputs
            :when (not (inside-any-root? roots input))]
      (invalid! "Java project input is outside every configured root"
                {:field field :path (str input)
                 :roots (mapv str roots)}))))

(defn validate!
  "Validates and deterministically canonicalizes a neutral Java project input.

  The optional `:project-root` is backend execution context rather than project
  identity. Source and resource root collections may both be empty."
  [input]
  (exact-keys! :project-input input required-input-keys input-keys)
  (when-not (= 1 (:schema-version input))
    (invalid! "Unsupported Java project-input schema version"
              {:field :schema-version :value (:schema-version input)}))
  (let [project-id (nonblank-string! :project-id (:project-id input))
        project-root
        (when-let [value (:project-root input)]
          (when-not (instance? Path value)
            (invalid! "Java project root is not a java.nio.file.Path"
                      {:field :project-root :value value}))
          (let [path (paths/absolute value)]
            (when-not (paths/directory? path)
              (invalid! "Java project root is missing"
                        {:field :project-root :path (str path)}))
            path))
        source-roots
        (path-vector! :source-roots (:source-roots input)
                      paths/directory? :source-root-missing)
        resource-roots
        (path-vector! :resource-roots (:resource-roots input)
                      paths/directory? :resource-root-missing)
        sources
        (path-vector! :production-sources (:production-sources input)
                      paths/regular-file? :production-source-missing)
        generated-sources
        (path-vector! :generated-production-sources
                      (:generated-production-sources input)
                      paths/regular-file? :generated-production-source-missing)
        resources
        (path-vector! :production-resources (:production-resources input)
                      paths/regular-file? :production-resource-missing)
        project-dependencies
        (validate-dependencies! :project-dependencies
                                (:project-dependencies input) :project-id)
        external-dependencies
        (validate-dependencies! :external-dependencies
                                (:external-dependencies input) :coordinate)
        classpath-artifacts
        (validate-classpath-artifacts! (:classpath-artifacts input))]
    (when (seq (set/intersection (set sources)
                                 (set generated-sources)))
      (invalid! "Generated production sources also appear as ordinary sources"
                {:field :generated-production-sources
                 :overlap (mapv str
                                (sort-by str
                                         (set/intersection
                                          (set sources)
                                          (set generated-sources))))}))
    (validate-root-membership!
     :production-sources source-roots (into sources generated-sources))
    (validate-root-membership! :production-resources resource-roots resources)
    (when (some #(= project-id (:project-id %)) project-dependencies)
      (invalid! "Java project input contains a self dependency"
                {:field :project-dependencies :project-id project-id}))
    (cond-> {:schema-version 1
             :project-id project-id
             :source-roots source-roots
             :resource-roots resource-roots
             :production-sources sources
             :generated-production-sources generated-sources
             :production-resources resources
             :java-toolchain (validate-toolchain! (:java-toolchain input))
             :project-dependencies project-dependencies
             :external-dependencies external-dependencies
             :classpath-artifacts classpath-artifacts}
      project-root (assoc :project-root project-root))))

(defn production-source-files
  "Returns ordinary and generated production sources in deterministic order."
  [project-input]
  (->> (concat (:production-sources project-input)
               (:generated-production-sources project-input))
       distinct
       (sort-by str)
       vec))

(defn compile-classpath
  "Returns the distinct compile-scope paths consumed by Java frontends."
  [project-input]
  (->> (:classpath-artifacts project-input)
       (filter #(= :compile (:scope %)))
       (map :path)
       distinct
       (sort-by str)
       vec))
