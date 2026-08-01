(ns dripsharp.sqltrellis.java-project
  "SqlTrellis registration on the product-neutral Java-library pipeline.

  This namespace owns only target identity and discovery-accounting policy.
  JSqlParser SQL semantics do not belong here or in the shared translator."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.java-library :as java-library]
            [dripsharp.paths :as paths]))

(def ^:private benchmark-sources
  #{"src/test/java/net/sf/jsqlparser/benchmark/DynamicParserRunner.java"
    "src/test/java/net/sf/jsqlparser/benchmark/JSQLParserBenchmark.java"
    "src/test/java/net/sf/jsqlparser/benchmark/LatestClasspathRunner.java"
    "src/test/java/net/sf/jsqlparser/benchmark/SqlParserRunner.java"})

(def ^:private model-statement-source-prefixes
  ["src/main/java/net/sf/jsqlparser/expression/"
   "src/main/java/net/sf/jsqlparser/schema/"
   "src/main/java/net/sf/jsqlparser/statement/"])

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :sqltrellis-registration-failed))))

(defn- relative-to-project [project-root path]
  (-> (paths/absolute project-root)
      (.relativize (paths/absolute path))
      str
      (.replace \\ \/)))

(defn- model-statement-source? [path]
  (some #(str/starts-with? path %) model-statement-source-prefixes))

(defn- model-statement-type-key [path]
  (str "type:"
       (-> path
           (str/replace #"^src/main/java/" "")
           (str/replace #"\.java$" "")
           (str/replace "/" "."))))

(defn model-statement-seeds
  "Derives the exact selected milestone identities from the pinned ordinary
  production graph. The returned identities remain ordinary closure seeds,
  not a source allowlist: Spoon must resolve each one exactly and recursively
  retains every project dependency required by its complete body."
  [project-root project-input]
  (->> (:production-sources project-input)
       (map #(relative-to-project project-root %))
       (filter model-statement-source?)
       (map #(hash-map :key (model-statement-type-key %) :expand :body))
       (sort-by :key)
       vec))

(defn- dependency-hashes [project-input]
  (->> (concat (:classpath-artifacts project-input)
               (:test-classpath-artifacts project-input))
       (keep (fn [{:keys [coordinate sha256]}]
               (when coordinate [coordinate sha256])))
       (into {})))

(defn validate-project-input!
  "Requires the complete pinned production/test graph retained by ingestion."
  [{:keys [workspace-root profile project-input]}]
  (let [record (baseline/read-baseline workspace-root :sqltrellis)
        discovery (get-in record [:contracts :discovery])
        project-root (paths/resolve-path workspace-root (:project-root profile))
        actual
        {:production-resources (count (:production-resources project-input))
         :test-sources (count (:test-sources project-input))
         :test-resources (count (:test-resources project-input))}
        expected (select-keys discovery (keys actual))
        test-paths (set (map #(relative-to-project project-root %)
                             (:test-sources project-input)))
        configured-benchmarks
        (set (get-in record [:contracts :benchmark-exclusions]))
        dependency-identities
        (set (map :coordinate
                  (concat (:external-dependencies project-input)
                          (:test-external-dependencies project-input))))
        hashes (dependency-hashes project-input)
        selected-seeds (model-statement-seeds project-root project-input)]
    (baseline/validate-project-input!
     workspace-root :sqltrellis (:profile profile) project-input)
    (when-not (= expected actual)
      (fail! "SqlTrellis production/test discovery counts changed"
             {:expected expected :actual actual}))
    (when-not (= benchmark-sources configured-benchmarks)
      (fail! "SqlTrellis benchmark classification changed"
             {:expected (sort benchmark-sources)
              :actual (sort configured-benchmarks)}))
    (when-not (set/subset? benchmark-sources test-paths)
      (fail! "A classified SqlTrellis benchmark source is absent from discovery"
             {:missing (sort (set/difference benchmark-sources test-paths))}))
    (when-not (= (set (keys (:artifacts record))) dependency-identities)
      (fail! "SqlTrellis production/test dependency identities changed"
             {:expected (sort (keys (:artifacts record)))
              :actual (sort dependency-identities)}))
    (when-not (= (:artifacts record) hashes)
      (fail! "SqlTrellis production/test dependency bytes changed"
             {:expected (:artifacts record) :actual hashes}))
    (when-not (= selected-seeds (:seeds profile))
      (fail! "SqlTrellis model/statement closure seeds changed"
             {:expected-count (count selected-seeds)
              :actual-count (count (:seeds profile))
              :missing (vec (take 30 (set/difference (set selected-seeds)
                                                     (set (:seeds profile)))))
              :unexpected (vec (take 30 (set/difference (set (:seeds profile))
                                                        (set selected-seeds))))}))
    project-input))

(defn rule-bundle
  "Extends the shared Java-library rules with SqlTrellis identity/accounting."
  []
  (let [base (java-library/rule-bundle)
        base-validator (get-in base [:orchestration :validate-project-input!])
        base-assets (get-in base [:rules :destination-bridges :assets])]
    (-> base
        (assoc :id :sqltrellis :product-family :sqltrellis)
        (assoc-in
         [:orchestration :validate-project-input!]
         (fn [context]
           (base-validator context)
           (validate-project-input! context)))
        (assoc-in
         [:rules :destination-bridges :assets]
         (fn [context]
           ;; Legal assets are still emitted by the shared Java-library policy;
           ;; only the externally visible product family differs.
           (base-assets
            (assoc-in context [:configuration :product-family] :java-library)))))))

(defn public-surface-strategy
  "Uses the shared complete-accessible surface policy under SqlTrellis identity."
  []
  (assoc (java-library/public-surface-strategy)
         :id :sqltrellis-complete-accessible-java-library
         :product-family :sqltrellis))
