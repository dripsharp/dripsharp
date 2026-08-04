(ns dripsharp.pdfcube.test-suite
  "Pinned inventory and eventual target-owned emission for PdfCarton's complete
  adapted Apache PDFBox test suite.

  Maven remains the authority for module test source, resource, dependency, and
  classpath discovery. JUnit planning is reusable and resolved-symbol based;
  this namespace owns only the five PdfCarton modules, PDFBox provenance, and
  the classification of PDFBox test conditions."
  (:require [clojure.string :as str]
            [dripsharp.harness :as harness]
            [dripsharp.java-test-adapters :as adapters]
            [dripsharp.junit-xunit :as junit]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.project :as project]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.file Path]
           [spoon.reflect.declaration CtType]))

(def ^:private revision "9286e47d89d6877005c9d2d0f2fd38793a62519a")
(def ^:private suite-contract-file "adapted-tests/suite-contract.edn")

(def ^:private module-specs
  [{:id :io
    :source-directory "io"
    :profile "targets/pdfcube/profiles/io.edn"
    :expected-sources 10
    :expected-fixtures 2}
   {:id :fontbox
    :source-directory "fontbox"
    :profile "targets/pdfcube/profiles/fontbox.edn"
    :expected-sources 44
    :expected-fixtures 27}
   {:id :xmpbox
    :source-directory "xmpbox"
    :profile "targets/pdfcube/profiles/xmpbox.edn"
    :expected-sources 28
    :expected-fixtures 34}
   {:id :pdfbox
    :source-directory "pdfbox"
    :profile "targets/pdfcube/profiles/pdfbox.edn"
    :expected-sources 128
    :expected-fixtures 300}
   {:id :preflight
    :source-directory "preflight"
    :profile "targets/pdfcube/profiles/preflight.edn"
    :expected-sources 23
    :expected-fixtures 8}])

(def ^:private digest-fields
  [:sources :fixtures :cases :parameter-rows :helpers :enablement
   :platform-conditions :framework-calls :dependencies])

(defn- fail! [message data]
  (throw
   (ex-info message (assoc data :kind :pdfcarton-test-suite-generation-failed))))

(defn- canonicalize [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key child]] [key (canonicalize child)]))
                       value)
    (set? value) (mapv canonicalize (sort-by pr-str value))
    (sequential? value) (mapv canonicalize value)
    :else value))

(defn- stable-text [value]
  (str (pr-str (canonicalize value)) "\n"))

(defn- relative-path [root path]
  (-> (paths/absolute root)
      (.relativize (paths/absolute path))
      str
      (str/replace "\\" "/")))

(defn- source-file [element]
  (some-> (spoon/source-location element) :file paths/absolute str))

(defn- containing-root [roots ^Path file]
  (last (sort-by #(.getNameCount ^Path %)
                 (filter #(.startsWith file ^Path %) roots))))

(defn- discover-inputs! [workspace-root]
  (let [profiles (mapv #(assoc % :configuration
                               (harness/read-profile workspace-root
                                                     (:profile %)))
                       module-specs)
        source-root (paths/resolve-path workspace-root "research/pdfbox")
        _ (project/verify-checkout!
           {:workspace-root workspace-root
            :project-root source-root
            :revision revision})
        reactor
        (maven/discover-reactor!
         {:workspace-root workspace-root
          :project-root source-root
          :selected-projects
          (mapv (comp first :maven-selected-projects :configuration) profiles)})]
    {:source-root source-root
     :modules
     (mapv
      (fn [{:keys [configuration] :as specification}]
        (assoc specification
               :input (maven/project-by-id!
                       reactor (:maven-project-id configuration))))
      profiles)}))

(defn- validate-selected-counts! [{:keys [id input expected-sources
                                          expected-fixtures]}]
  (let [source-count (count (:test-sources input))
        fixture-count (count (:test-resources input))]
    (when-not (= expected-sources source-count)
      (fail! "PdfCarton upstream test-source inventory changed"
             {:reason :pdfcarton-test-source-count-drift
              :module id :expected expected-sources :actual source-count}))
    (when-not (= expected-fixtures fixture-count)
      (fail! "PdfCarton upstream test-resource inventory changed"
             {:reason :pdfcarton-test-resource-count-drift
              :module id :expected expected-fixtures :actual fixture-count}))))

(defn- resolved-input [{:keys [id input]}]
  {:schema-version 1
   :project-id (str "pdfcarton-complete-adapted-tests-" (name id))
   :source-roots (vec (distinct (concat (:source-roots input)
                                        (:generated-source-roots input)
                                        (:test-source-roots input))))
   :resource-roots []
   :production-sources
   (vec (distinct (concat (:production-sources input)
                          (:generated-production-sources input)
                          (:test-sources input))))
   :generated-production-sources []
   :production-resources []
   :test-source-roots []
   :test-resource-roots []
   :test-sources []
   :test-resources []
   :java-toolchain (:java-toolchain input)
   :project-dependencies []
   :external-dependencies (:test-external-dependencies input)
   :classpath-artifacts
   (vec (filter #(= :compile (:scope %))
                (:test-classpath-artifacts input)))
   :test-project-dependencies []
   :test-external-dependencies []
   :test-classpath-artifacts []})

(defn- source-entry [source-root module plan ^Path source]
  (let [canonical (str (paths/absolute source))
        cases (filter #(= canonical
                          (some-> % :source :file paths/absolute str))
                      (:cases plan))
        class-plans
        (filter #(= canonical (source-file (:type-element %)))
                (vals (:classes plan)))]
    {:module module
     :source (relative-path source-root source)
     :sha256 (util/sha256-file source)
     :license "Apache-2.0"
     :attribution (str "Apache PDFBox 3.0.8 at " revision ".")
     :classification (if (seq cases) :ordinary-test :test-helper)
     :case-count (count cases)
     :type-count (count class-plans)}))

(defn- fixture-entry [source-root module input ^Path resource]
  (let [root (containing-root (:test-resource-roots input) resource)]
    (when-not root
      (fail! "PdfCarton test resource has no Maven resource root"
             {:reason :pdfcarton-test-resource-root-missing
              :module module :resource (str resource)}))
    {:module module
     :source (relative-path source-root resource)
     :destination (-> (.relativize ^Path root resource) str
                      (str/replace "\\" "/"))
     :sha256 (util/sha256-file resource)
     :license "Apache-2.0"
     :authorship :mechanically-upstream-derived
     :attribution (str "Apache PDFBox 3.0.8 test resource at " revision ".")}))

(defn- selected-file? [selected-files location]
  (contains? selected-files
             (some-> location :file paths/absolute str)))

(defn- occurrence-rows [source-root selected-files resolved-model predicate]
  (->> (:occurrences resolved-model)
       (filter #(selected-file? selected-files (:location %)))
       (filter predicate)
       (map (fn [occurrence]
              {:key (:key occurrence)
               :source (relative-path source-root
                                      (get-in occurrence [:location :file]))
               :line (get-in occurrence [:location :line])
               :column (get-in occurrence [:location :column])}))
       (sort-by (juxt :source :line :column :key))
       vec))

(defn- framework-call? [{:keys [key]}]
  (or (str/starts-with? key "executable:org.junit.")
      (str/starts-with? key "executable:org.mockito.")))

(defn- platform-condition? [{:keys [key]}]
  (or (str/starts-with? key
                        "executable:org.junit.jupiter.api.Assumptions#")
      (str/starts-with? key "executable:java.lang.System#getProperty(")
      (str/starts-with? key "executable:java.lang.System#getenv(")
      (str/starts-with? key "executable:java.io.File#exists(")
      (str/starts-with? key
                        "executable:java.awt.GraphicsEnvironment#")
      (contains?
       #{"executable:java.util.Locale#getDefault()"
         "executable:java.util.Locale#setDefault(java.util.Locale)"
         "executable:java.util.TimeZone#getDefault()"
         "executable:java.util.TimeZone#setDefault(java.util.TimeZone)"}
       key)))

(defn- helper-rows [source-root selected-files plan]
  (let [test-methods (set (map :declaring-method (:cases plan)))]
    (->> (:classes plan)
         vals
         (filter #(contains? selected-files
                             (source-file (:type-element %))))
         (mapcat
          (fn [class-plan]
            (let [source (relative-path source-root
                                        (source-file (:type-element class-plan)))]
              (concat
               [{:kind :type :id (:name class-plan) :source source}]
               (map (fn [method]
                      {:kind (if (contains? test-methods (:id method))
                               :test-method :helper-method)
                       :id (:id method)
                       :source source})
                    (:methods class-plan))
               (map (fn [field]
                      {:kind :field
                       :id (or (:id field) (:name field))
                       :source source})
                    (:fields class-plan))))))
         (sort-by (juxt :source :kind :id))
         vec)))

(defn- parameter-row-records [module cases]
  (->> cases
       (filter :parameters)
       (mapcat
        (fn [test-case]
          (map (fn [row]
                 {:module module
                  :case-id (:id test-case)
                  :row-id row
                  :accounting
                  (if (str/ends-with? row "/runtime")
                    :runtime-member-data
                    :static-row)})
               (junit/row-identities test-case))))
       vec))

(defn- module-inventory [workspace-root source-root specification]
  (validate-selected-counts! specification)
  (let [{:keys [id input]} specification
        sources (vec (sort-by str (:test-sources input)))
        resources (vec (sort-by str (:test-resources input)))
        selected-files (set (map (comp str paths/absolute) sources))
        resolved-model
        (spoon/build-resolved-model! workspace-root
                                     (resolved-input specification))
        plan (junit/plan-suite resolved-model
                               (adapters/junit-plan-options))
        cases (->> (:cases plan)
                   (filter #(selected-file? selected-files (:source %)))
                   (sort-by :id)
                   vec)
        unexpected
        (->> (:cases plan)
             (remove #(selected-file? selected-files (:source %)))
             (mapv :id))
        _ (when (seq unexpected)
            (fail! "PdfCarton JUnit plan includes cases outside test sources"
                   {:reason :pdfcarton-test-case-source-drift
                    :module id :cases unexpected}))
        serializable-cases (:cases (junit/serializable-plan
                                    (assoc plan :cases cases)))
        framework-calls
        (occurrence-rows source-root selected-files resolved-model
                         framework-call?)
        platform-conditions
        (occurrence-rows source-root selected-files resolved-model
                         platform-condition?)]
    {:module id
     :project-id (:project-id input)
     :sources (mapv #(source-entry source-root id plan %) sources)
     :fixtures (mapv #(fixture-entry source-root id input %) resources)
     :cases (mapv #(assoc % :module id) serializable-cases)
     :parameter-rows (parameter-row-records id cases)
     :helpers (mapv #(assoc % :module id)
                    (helper-rows source-root selected-files plan))
     :enablement
     (mapv (fn [test-case]
             {:module id
              :case-id (:id test-case)
              :state (if (:disabled test-case) :disabled :enabled)
              :reason (get-in test-case [:disabled :reason])})
           cases)
     :platform-conditions (mapv #(assoc % :module id)
                                platform-conditions)
     :framework-calls (mapv #(assoc % :module id) framework-calls)
     :dependencies
     {:module id
      :production-projects (vec (sort-by pr-str
                                         (:project-dependencies input)))
      :test-projects (vec (sort-by pr-str
                                   (:test-project-dependencies input)))
      :test-external (vec (sort-by pr-str
                                   (:test-external-dependencies input)))}}))

(defn accounting-digests
  "Returns independent hashes for every loss-sensitive PdfCarton suite
  accounting projection."
  [accounting]
  (into (sorted-map)
        (map (fn [field]
               [field (util/sha256-text (stable-text (get accounting field)))])
             digest-fields)))

(defn inventory!
  "Discovers and inventories the complete pinned ordinary Java test inputs for
  all five selected PdfCarton modules. The returned value contains no live
  Spoon objects and is safe to persist in generated provenance ledgers."
  ([] (inventory! (paths/workspace-root)))
  ([workspace-root]
   (let [workspace-root (paths/absolute workspace-root)
         {:keys [source-root modules]} (discover-inputs! workspace-root)
         inventories
         (mapv #(module-inventory workspace-root source-root %) modules)
         accounting
         (into (sorted-map)
               (map (fn [field]
                      [field (vec (mapcat field inventories))])
                    (remove #{:dependencies} digest-fields)))
         accounting (assoc accounting :dependencies
                           (mapv :dependencies inventories))
         module-counts
         (into
          (sorted-map)
          (map (fn [module]
                 [(:module module)
                  {:source-count (count (:sources module))
                   :fixture-count (count (:fixtures module))
                   :case-count (count (:cases module))
                   :parameter-row-count (count (:parameter-rows module))
                   :helper-count (count (:helpers module))
                   :disabled-count
                   (count (filter #(= :disabled (:state %))
                                  (:enablement module)))
                   :platform-condition-count
                   (count (:platform-conditions module))}])
               inventories))
         totals
         {:source-count (count (:sources accounting))
          :fixture-count (count (:fixtures accounting))
          :case-count (count (:cases accounting))
          :parameter-row-count (count (:parameter-rows accounting))
          :helper-count (count (:helpers accounting))
          :disabled-count
          (count (filter #(= :disabled (:state %))
                         (:enablement accounting)))
          :platform-condition-count (count (:platform-conditions accounting))}]
     {:schema-version 1
      :target :pdfcube
      :revision revision
      :modules module-counts
      :totals totals
      :accounting accounting
      :digests (accounting-digests accounting)})))

(defn read-contract!
  "Reads the target-owned expected accounting for the shipped PdfCarton suite."
  [target-root]
  (let [file (paths/resolve-path target-root suite-contract-file)]
    (when-not (paths/regular-file? file)
      (fail! "PdfCarton adapted-suite contract is missing"
             {:reason :missing-pdfcarton-test-suite-contract
              :path (str file)}))
    (let [contract (util/read-single-edn-string! (slurp (str file)))]
      (when-not (= #{:schema-version :target :revision :modules :totals
                     :digests}
                   (set (keys contract)))
        (fail! "PdfCarton adapted-suite contract has missing or unknown fields"
               {:reason :invalid-pdfcarton-test-suite-contract
                :contract contract}))
      (when-not (and (= 1 (:schema-version contract))
                     (= :pdfcube (:target contract))
                     (= revision (:revision contract)))
        (fail! "PdfCarton adapted-suite contract identity changed"
               {:reason :invalid-pdfcarton-test-suite-contract
                :contract contract}))
      contract)))

(defn verify-inventory!
  "Fails closed when any selected source, fixture, case, helper, enablement,
  condition, framework call, or dependency differs from the pinned contract."
  [contract inventory]
  (doseq [field [:modules :totals :digests]]
    (when-not (= (get contract field) (get inventory field))
      (fail! "PdfCarton adapted-suite accounting changed"
             {:reason :pdfcarton-test-accounting-drift
              :section field
              :expected (get contract field)
              :actual (get inventory field)})))
  inventory)
