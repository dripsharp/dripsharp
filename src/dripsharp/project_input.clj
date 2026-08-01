(ns dripsharp.project-input
  "Build-tool-neutral, fail-closed Java project inputs.

  Discovery backends adapt their native project model into this schema before
  orchestration, Spoon, or destination rules consume it."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files Path]))

(def ^:private input-keys
  #{:schema-version
    :project-id
    :project-root
    :source-roots
    :resource-roots
    :production-sources
    :generated-production-sources
    :production-resources
    :test-source-roots
    :test-resource-roots
    :test-sources
    :test-resources
    :java-toolchain
    :project-dependencies
    :external-dependencies
    :classpath-artifacts
    :test-project-dependencies
    :test-external-dependencies
    :test-classpath-artifacts
    :generation-executions
    :build-input-artifacts})

(def ^:private required-input-keys
  (apply disj input-keys
         [:project-root
          :test-source-roots :test-resource-roots
          :test-sources :test-resources
          :test-project-dependencies :test-external-dependencies
          :test-classpath-artifacts :generation-executions
          :build-input-artifacts]))

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

(def ^:private file-sha256 util/sha256-file)

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
  [field values]
  (when-not (vector? values)
    (invalid! "Java project classpath artifacts must be a vector"
              {:field field :value values}))
  (let [records
        (mapv
         (fn [value]
           (exact-keys! field value #{:scope :path}
                        #{:scope :path :project-id :coordinate :sha256})
           (when (and (contains? value :project-id)
                      (contains? value :coordinate))
             (invalid! "Classpath artifact cannot have both project and external identities"
                       {:field field :value value}))
           (let [path (:path value)]
             (when-not (instance? Path path)
               (invalid! "Classpath artifact path is not a java.nio.file.Path"
                         {:field field :value path}))
             (let [path (paths/absolute path)
                   file? (Files/isRegularFile path paths/no-links)
                   directory? (Files/isDirectory path paths/no-links)
                   sha256 (:sha256 value)]
               (when-not (or file? directory?)
                 (invalid! "Classpath artifact is missing"
                           {:field field :path (str path)}))
               (when (contains? value :project-id)
                 (nonblank-string! :project-id (:project-id value)))
               (when (contains? value :coordinate)
                 (nonblank-string! :coordinate (:coordinate value)))
               (when (and sha256
                          (not (and file?
                                    (re-matches #"[0-9a-f]{64}" sha256))))
                 (invalid! "Classpath artifact has an invalid SHA-256 hash"
                           {:field field :path (str path)
                            :sha256 sha256}))
               (when sha256
                 (let [actual (file-sha256 path)]
                   (when-not (= sha256 actual)
                     (invalid! "Classpath artifact SHA-256 does not match its contents"
                               {:field field :path (str path)
                                :expected sha256 :actual actual}))))
               (when (and (contains? value :coordinate) (nil? sha256))
                 (invalid! "External classpath artifact is missing its SHA-256 hash"
                           {:field field :path (str path)
                            :coordinate (:coordinate value)}))
               (cond-> {:scope (scope! field (:scope value))
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
                {:field field :artifacts ordered}))
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

(defn- validate-generation-executions!
  [values]
  (when-not (vector? values)
    (invalid! "Java project generation executions must be a vector"
              {:field :generation-executions :value values}))
  (let [records
        (mapv
         (fn [value]
           (exact-keys! :generation-executions value #{:owner :goal}
                        #{:owner :goal})
           {:owner (nonblank-string! :owner (:owner value))
            :goal (nonblank-string! :goal (:goal value))})
         values)
        ordered (vec (sort-by (juxt :owner :goal) records))]
    (when-not (= (count ordered) (count (distinct ordered)))
      (invalid! "Java project generation executions contain duplicates"
                {:field :generation-executions :records ordered}))
    ordered))

(defn- validate-build-input-artifacts!
  [values]
  (when-not (vector? values)
    (invalid! "Java project build-input artifacts must be a vector"
              {:field :build-input-artifacts :value values}))
  (let [records
        (mapv
         (fn [value]
           (exact-keys! :build-input-artifacts value
                        #{:owner :coordinate :path :sha256}
                        #{:owner :coordinate :path :sha256})
           (let [path (:path value)
                 expected (:sha256 value)]
             (when-not (instance? Path path)
               (invalid! "Build-input artifact path is not a java.nio.file.Path"
                         {:field :build-input-artifacts :value path}))
             (let [path (paths/absolute path)]
               (when-not (paths/regular-file? path)
                 (invalid! "Build-input artifact is missing"
                           {:field :build-input-artifacts :path (str path)}))
               (when-not (and (string? expected)
                              (re-matches #"[0-9a-f]{64}" expected))
                 (invalid! "Build-input artifact has an invalid SHA-256 hash"
                           {:field :build-input-artifacts :path (str path)
                            :sha256 expected}))
               (let [actual (file-sha256 path)]
                 (when-not (= expected actual)
                   (invalid! "Build-input artifact SHA-256 does not match its contents"
                             {:field :build-input-artifacts :path (str path)
                              :expected expected :actual actual})))
               {:owner (nonblank-string! :owner (:owner value))
                :coordinate (nonblank-string! :coordinate (:coordinate value))
                :path path
                :sha256 expected})))
         values)
        ordered (vec (sort-by (juxt :owner :coordinate (comp str :path)) records))]
    (when-not (= (count ordered) (count (distinct ordered)))
      (invalid! "Java project build-input artifacts contain duplicates"
                {:field :build-input-artifacts :records ordered}))
    ordered))

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

(defn- validate-production-test-separation!
  [production-field production test-field tests]
  (let [overlap (set/intersection (set production) (set tests))]
    (when (seq overlap)
      (invalid! "Java project production and test inputs overlap"
                {:field test-field
                 :production-field production-field
                 :overlap (mapv str (sort-by str overlap))}))))

(defn- validate-root-separation!
  [production-field production-roots test-field test-roots]
  (let [overlap
        (for [^Path production-root production-roots
              ^Path test-root test-roots
              :when (or (.startsWith production-root test-root)
                        (.startsWith test-root production-root))]
          {:production-root (str production-root)
           :test-root (str test-root)})]
    (when (seq overlap)
      (invalid! "Java project production and test roots overlap"
                {:field test-field
                 :production-field production-field
                 :overlap (vec overlap)}))))

(defn validate!
  "Validates and deterministically canonicalizes a neutral Java project input.

  The optional `:project-root` is backend execution context rather than project
  identity. Test collections are optional for compatibility with authored
  production-only inputs and canonicalize to empty vectors. `:test-sources`
  contains every Java compilation unit in test roots, including helper types."
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
        test-input (merge {:test-source-roots []
                           :test-resource-roots []
                           :test-sources []
                           :test-resources []
                           :test-project-dependencies []
                           :test-external-dependencies []
                           :test-classpath-artifacts []
                           :generation-executions []
                           :build-input-artifacts []}
                          input)
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
        test-source-roots
        (path-vector! :test-source-roots (:test-source-roots test-input)
                      paths/directory? :test-source-root-missing)
        test-resource-roots
        (path-vector! :test-resource-roots (:test-resource-roots test-input)
                      paths/directory? :test-resource-root-missing)
        test-sources
        (path-vector! :test-sources (:test-sources test-input)
                      paths/regular-file? :test-source-missing)
        test-resources
        (path-vector! :test-resources (:test-resources test-input)
                      paths/regular-file? :test-resource-missing)
        project-dependencies
        (validate-dependencies! :project-dependencies
                                (:project-dependencies input) :project-id)
        external-dependencies
        (validate-dependencies! :external-dependencies
                                (:external-dependencies input) :coordinate)
        classpath-artifacts
        (validate-classpath-artifacts! :classpath-artifacts
                                       (:classpath-artifacts input))
        test-project-dependencies
        (validate-dependencies! :test-project-dependencies
                                (:test-project-dependencies test-input)
                                :project-id)
        test-external-dependencies
        (validate-dependencies! :test-external-dependencies
                                (:test-external-dependencies test-input)
                                :coordinate)
        test-classpath-artifacts
        (validate-classpath-artifacts! :test-classpath-artifacts
                                       (:test-classpath-artifacts test-input))
        generation-executions
        (validate-generation-executions! (:generation-executions test-input))
        build-input-artifacts
        (validate-build-input-artifacts! (:build-input-artifacts test-input))]
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
    (validate-root-membership! :test-sources test-source-roots test-sources)
    (validate-root-membership! :test-resources test-resource-roots test-resources)
    (validate-root-separation!
     :source-roots source-roots :test-source-roots test-source-roots)
    (validate-root-separation!
     :resource-roots resource-roots :test-resource-roots test-resource-roots)
    (validate-production-test-separation!
     :production-sources (into sources generated-sources)
     :test-sources test-sources)
    (validate-production-test-separation!
     :production-resources resources :test-resources test-resources)
    (when (some #(= project-id (:project-id %)) project-dependencies)
      (invalid! "Java project input contains a self dependency"
                {:field :project-dependencies :project-id project-id}))
    (when (some #(= project-id (:project-id %)) test-project-dependencies)
      (invalid! "Java project test input contains a self dependency"
                {:field :test-project-dependencies :project-id project-id}))
    (cond-> {:schema-version 1
             :project-id project-id
             :source-roots source-roots
             :resource-roots resource-roots
             :production-sources sources
             :generated-production-sources generated-sources
             :production-resources resources
             :test-source-roots test-source-roots
             :test-resource-roots test-resource-roots
             :test-sources test-sources
             :test-resources test-resources
             :java-toolchain (validate-toolchain! (:java-toolchain input))
             :project-dependencies project-dependencies
             :external-dependencies external-dependencies
             :classpath-artifacts classpath-artifacts
             :test-project-dependencies test-project-dependencies
             :test-external-dependencies test-external-dependencies
             :test-classpath-artifacts test-classpath-artifacts
             :generation-executions generation-executions
             :build-input-artifacts build-input-artifacts}
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

(defn test-source-files
  "Returns every Java test or helper source in deterministic order."
  [project-input]
  (->> (:test-sources project-input)
       distinct
       (sort-by str)
       vec))

(defn test-classpath
  "Returns the distinct effective test classpath for `scope`."
  [project-input scope]
  (scope! :test-classpath scope)
  (->> (:test-classpath-artifacts project-input)
       (filter #(= scope (:scope %)))
       (map :path)
       distinct
       (sort-by str)
       vec))
