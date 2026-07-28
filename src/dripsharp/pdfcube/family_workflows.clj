(ns dripsharp.pdfcube.family-workflows
  "One-pack, package-only, pinned-Java workflow proof for the complete
  five-package PdfCube family."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.family-packaging :as family-packaging]
            [dripsharp.pdfcube.fontbox-differential :as fontbox]
            [dripsharp.pdfcube.io-differential :as io]
            [dripsharp.pdfcube.pdfbox-differential :as pdfbox]
            [dripsharp.pdfcube.pdfbox-image-differential :as pdfbox-image]
            [dripsharp.pdfcube.pdfbox-interchange-differential
             :as pdfbox-interchange]
            [dripsharp.pdfcube.preflight-differential :as preflight]
            [dripsharp.pdfcube.xmpbox-metadata-differential :as xmpbox]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.file Files OpenOption Path StandardCopyOption
            StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def package-version
  (baseline/package-version :pdfcube "PdfCube.IO"))

(def package-profiles
  {"pdfcube-io" "PdfCube.IO"
   "pdfcube-fontbox" "PdfCube.FontBox"
   "pdfcube-xmpbox" "PdfCube.XmpBox"
   "pdfcube-pdfbox" "PdfCube.PdfBox"
   "pdfcube-preflight" "PdfCube.Preflight"})

(def required-workflows
  #{:create :ordinary-load :malformed-load :encrypted-load :signed-load
    :font-rich-load :image-rich-load :form-load :tagged-load :layered-load
    :metadata-load :edit :reopen :save :render :extract :security :sign
    :pdfa-validation :resource-lifetime :incremental-update
    :deterministic-failure :repeat-execution})

(def verification-slices
  [{:id :io
    :profile "pdfcube-io"
    :workflows #{:resource-lifetime :deterministic-failure}
    :verify io/verify!}
   {:id :io-repeat
    :profile "pdfcube-io"
    :repeat-of :io
    :workflows #{:repeat-execution}
    :verify io/verify!}
   {:id :fontbox
    :profile "pdfcube-fontbox"
    :workflows #{:font-rich-load}
    :verify fontbox/verify!}
   {:id :xmpbox
    :profile "pdfcube-xmpbox"
    :workflows #{:metadata-load}
    :verify xmpbox/verify!}
   {:id :pdfbox
    :profile "pdfcube-pdfbox"
    :workflows
    #{:create :ordinary-load :malformed-load :encrypted-load :signed-load
      :form-load :edit :reopen :save :render :extract :security :sign
      :incremental-update :resource-lifetime :deterministic-failure}
    :verify pdfbox/verify!}
   {:id :pdfbox-image
    :profile "pdfcube-pdfbox"
    :workflows #{:image-rich-load :render :deterministic-failure}
    :verify pdfbox-image/verify!}
   {:id :pdfbox-interchange
    :profile "pdfcube-pdfbox"
    :workflows #{:tagged-load :layered-load :form-load :edit :reopen}
    :verify pdfbox-interchange/verify!}
   {:id :preflight
    :profile "pdfcube-preflight"
    :workflows
    #{:ordinary-load :malformed-load :encrypted-load :metadata-load
      :pdfa-validation :resource-lifetime :deterministic-failure}
    :verify preflight/verify!}])

(def ^:private required-perturbation-slices
  #{:io :fontbox :xmpbox :pdfbox :preflight})

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-family-workflows-failed))))

(def ^:private write-text! util/write-text!)

(defn workflow-coverage
  "Requires the family slices to cover every selected cross-package workflow
  and no invented workflow name."
  [slices]
  (let [covered (reduce set/union #{} (map :workflows slices))
        missing (vec (sort (set/difference required-workflows covered)))
        unexpected (vec (sort (set/difference covered required-workflows)))]
    (when (or (seq missing) (seq unexpected))
      (fail! "PdfCube family workflow proof has incomplete coverage"
             {:missing missing
              :unexpected unexpected
              :covered (vec (sort covered))}))
    covered))

(defn- main-emission-record
  [generation]
  (assoc
   (:emission generation)
   :profile (get-in generation [:generation-profile :profile])
   :dependency-profiles (:dependency-profiles generation)
   :transitive-dependency-profiles
   (or (get-in generation
               [:emission :transitive-dependency-profiles])
       (mapv :profile (:dependency-emissions generation)))
   :source-project (:source-project generation)
   :project-input (:resolved-project-input generation)
   :model-totals (get-in generation [:java-model :totals])
   :public-api-boundary (:public-api-boundary generation)
   :public-surface-strategy (:public-surface-strategy generation)
   :destination (:destination generation)))

(defn- generation-emissions
  [generation]
  (conj (vec (:dependency-emissions generation))
        (main-emission-record generation)))

(defn- profile-generation
  [generation emissions-by-profile profile]
  (let [emission
        (or (get emissions-by-profile profile)
            (fail! "Packed PdfCube family omitted a profile emission"
                   {:profile profile
                    :available (vec (sort (keys emissions-by-profile)))}))
        dependencies
        (mapv
         (fn [dependency]
           (or (get emissions-by-profile dependency)
               (fail! "Packed PdfCube family omitted a dependency emission"
                      {:profile profile :dependency dependency})))
         (:transitive-dependency-profiles emission))]
    (-> generation
        (assoc :generation-profile {:profile profile}
               :source-project (:source-project emission)
               :project-input (:project-input emission)
               :resolved-project-input (:project-input emission)
               :java-model {:totals (:model-totals emission)}
               :public-api-boundary (:public-api-boundary emission)
               :public-surface-strategy
               (:public-surface-strategy emission)
               :destination (:destination emission)
               :dependency-profiles (:dependency-profiles emission)
               :dependency-emissions dependencies
               :emission emission))))

(defn- filtered-public-surface
  [public-surface package-ids]
  (update public-surface :assemblies
          (fn [assemblies]
            (->> assemblies
                 (filter #(contains? package-ids (:assembly %)))
                 vec))))

(defn package-views
  "Builds exact per-package proof views over one five-package pack and its five
  already executed isolated consumers. No view can see project references,
  generated source, packages, or consumer caches outside its dependency
  closure."
  [family-proof]
  (let [package-proof (:package-proof family-proof)
        generation (get-in package-proof [:verification :generation])
        emissions (generation-emissions generation)
        emissions-by-profile (into {} (map (juxt :profile identity)) emissions)
        packages-by-profile (into {} (map (juxt :profile identity))
                                  (:packages package-proof))
        consumers-by-name
        (into {} (map (juxt :consumer-name identity))
              (:consumer-proofs family-proof))
        expected-profiles (set (keys package-profiles))
        actual-profiles (set (keys packages-by-profile))]
    (when-not (= expected-profiles actual-profiles)
      (fail! "Family workflow input does not contain exactly five packages"
             {:expected (vec (sort expected-profiles))
              :actual (vec (sort actual-profiles))}))
    (into
     (sorted-map)
     (for [[profile package-id] package-profiles
           :let [emission (get emissions-by-profile profile)
                 dependency-profiles
                 (set (:transitive-dependency-profiles emission))
                 closure-profiles (conj dependency-profiles profile)
                 closure-package-ids
                 (set (map package-profiles closure-profiles))
                 package (get packages-by-profile profile)
                 consumer
                 (or (get consumers-by-name package-id)
                     (fail! "Family workflow input omitted an isolated consumer"
                            {:profile profile :package package-id}))
                 restored-ids
                 (set (map :id
                           (get-in consumer
                                   [:dependency-proof :packages])))
                 external-packages
                 (->> (:external-packages package-proof)
                      (filter #(contains? restored-ids (:id %)))
                      vec)
                 packages
                 (->> (:packages package-proof)
                      (filter #(contains? closure-profiles (:profile %)))
                      (mapv #(assoc % :primary? (= profile (:profile %)))))
                 target-generation
                 (profile-generation generation emissions-by-profile profile)
                 verification
                 (-> (:verification package-proof)
                     (assoc :generation target-generation)
                     (update :public-surface
                             filtered-public-surface closure-package-ids))
                 direct
                 (set (map :id (:selected-packages consumer)))]]
       (do
         (when-not (= #{package-id} direct)
           (fail! "Family package view has the wrong direct PackageReference"
                  {:profile profile :expected #{package-id} :actual direct}))
         (when-not (= closure-package-ids
                      (set (map #(get-in % [:identity :id]) packages)))
           (fail! "Family package view escaped its translated dependency closure"
                  {:profile profile
                   :expected (vec (sort closure-package-ids))
                   :actual
                   (vec (sort
                         (map #(get-in % [:identity :id]) packages)))}))
         [profile
          {:verification verification
           :artifact (:artifact package)
           :identity (:identity package)
           :inspection (:inspection package)
           :packages packages
           :external-packages external-packages
           :feed (:feed package-proof)
           :packing-summary (:summary package-proof)
           :dependency-proof (:dependency-proof consumer)
           :proof-root (:proof-root package-proof)
           :packages-root (:packages-root consumer)
           :consumer-root (:consumer-root consumer)
           :run (:run consumer)}])))))

(defn- summary-comparisons
  [summary]
  (vec
   (remove
    nil?
    (concat
     [(:canonical-comparison summary)
      (:package-comparison summary)
      (:comparison summary)]
     (map #(get-in % [:comparison])
          (vals (:workflows summary)))))))

(defn- perturbation-proven?
  [summary]
  (boolean
   (or (:perturbation-line summary)
       (get-in summary [:deliberate-mismatch :line]))))

(defn- summary-package-identity
  [summary]
  (let [package (:package summary)]
    {:id (or (:id package) (:package-id package))
     :version (:version package)
     :sha256 (:sha256 package)}))

(defn- summary-observations
  [summary]
  (or (get-in summary [:trace :observations])
      (reduce
       +
       0
       (keep :observations (vals (:workflows summary))))
      0))

(defn- run-slice!
  [^Path root views run-command! {:keys [id profile workflows verify
                                         repeat-of]}]
  (let [view
        (or (get views profile)
            (fail! "Workflow slice selects an unknown package profile"
                   {:slice id :profile profile}))
        package-fn
        (fn [{requested :profile}]
          (when-not (= profile requested)
            (fail! "Workflow slice requested a different package profile"
                   {:slice id :expected profile :actual requested}))
          view)
        summary
        (verify {:workspace-root root
                 :package-fn package-fn
                 :run-command! run-command!})
        expected-package
        (select-keys (:identity view) [:id :version :sha256])
        actual-package (summary-package-identity summary)
        comparisons (summary-comparisons summary)
        mismatches
        (->> comparisons
             (keep :mismatch)
             vec)]
    (when-not (= {:version (baseline/upstream-version :pdfcube)
                  :revision pinned-revision}
                 (:source summary))
      (fail! "Workflow slice used a source outside the synchronized release"
             {:slice id
              :expected {:version (baseline/upstream-version :pdfcube)
                         :revision pinned-revision}
              :actual (:source summary)}))
    (when-not (= expected-package actual-package)
      (fail! "Workflow slice did not use its exact family package identity"
             {:slice id :expected expected-package :actual actual-package}))
    (when-not (= (:dependency-proof view) (:consumer summary))
      (fail! "Workflow slice escaped its isolated package consumer"
             {:slice id}))
    (when-not (seq comparisons)
      (fail! "Workflow slice contains no normalized Java/package comparison"
             {:slice id}))
    (when (seq mismatches)
      (fail! "Workflow slice contains a Java/package mismatch"
             {:slice id :mismatches mismatches}))
    {:id id
     :profile profile
     :package-id (:id expected-package)
     :workflows workflows
     :repeat-of repeat-of
     :comparisons (count comparisons)
     :observations (summary-observations summary)
     :perturbation? (perturbation-proven? summary)
     :summary summary}))

(defn- stable-repeat-observation
  [result]
  (let [summary (:summary result)]
    {:package (summary-package-identity summary)
     :source (:source summary)
     :consumer (:consumer summary)
     :trace (:trace summary)
     :package-comparison (:package-comparison summary)
     :perturbation-line (:perturbation-line summary)}))

(defn validate-slice-results!
  "Requires exact slice execution, all five public package boundaries,
  successful Java/package comparisons, at least five deliberate controls, and
  byte-for-byte stable normalized evidence from the repeated IO execution."
  [slices results]
  (let [expected-ids (mapv :id slices)
        actual-ids (mapv :id results)
        package-ids (set (map :package-id results))
        expected-package-ids (set (vals package-profiles))
        perturbations (set (map :id (filter :perturbation? results)))
        repeat-results (filter :repeat-of results)]
    (when-not (= expected-ids actual-ids)
      (fail! "Family workflow slices did not execute exactly once in order"
             {:expected expected-ids :actual actual-ids}))
    (when-not (= expected-package-ids package-ids)
      (fail! "Family workflow evidence missed a public package boundary"
             {:expected (vec (sort expected-package-ids))
              :actual (vec (sort package-ids))}))
    (when-not (set/subset? required-perturbation-slices perturbations)
      (fail! "Family workflow evidence lacks deliberate mismatch controls"
             {:expected (vec (sort required-perturbation-slices))
              :actual (vec (sort perturbations))}))
    (doseq [{:keys [id repeat-of] :as repeated} repeat-results
            :let [original (first (filter #(= repeat-of (:id %)) results))]]
      (when-not original
        (fail! "Repeated workflow has no original execution"
               {:slice id :repeat-of repeat-of}))
      (when-not (= (stable-repeat-observation original)
                   (stable-repeat-observation repeated))
        (fail! "Repeated workflow produced different normalized evidence"
               {:slice id :repeat-of repeat-of
                :expected (stable-repeat-observation original)
                :actual (stable-repeat-observation repeated)})))
    {:slices (count results)
     :packages (vec (sort package-ids))
     :comparisons (reduce + (map :comparisons results))
     :observations (reduce + (map :observations results))
     :perturbations (vec (sort perturbations))
     :repeated (mapv :id repeat-results)}))

(defn- normalized-family-trace
  [results]
  (apply
   str
   (for [{:keys [id package-id workflows comparisons observations]}
         (sort-by :id results)]
     (str "slice\t" (name id) "\t"
          package-id "|"
          (str/join "," (map name (sort workflows))) "|"
          comparisons "|" observations "\n"))))

(defn prove-mismatch-detection!
  "Deliberately perturbs the normalized family observation trace and requires
  the shared comparator to report the exact mismatch."
  [oracle perturbed]
  (let [oracle (paths/path oracle)
        perturbed (paths/path perturbed)]
    (when-not (paths/regular-file? oracle)
      (fail! "Family mismatch control is missing its normalized trace"
             {:oracle (str oracle)}))
    (Files/createDirectories (.getParent perturbed)
                             (make-array FileAttribute 0))
    (Files/copy oracle perturbed
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (Files/writeString
     perturbed
     "slice\tdeliberate-family-mismatch\tchanged\n"
     (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [comparison (differential/compare-results oracle perturbed)]
      (when-not (:mismatch comparison)
        (fail! "Family comparator missed a deliberate mismatch"
               {:oracle (str oracle) :perturbed (str perturbed)}))
      comparison)))

(defn- runtime-family-consumer!
  [family-proof]
  (let [consumer
        (first
         (filter #(= "complete-family" (:consumer-name %))
                 (:consumer-proofs family-proof)))
        selected
        (set (map :id (:selected-packages consumer)))
        expected (set (vals package-profiles))
        output (get-in consumer [:run :output])]
    (when-not consumer
      (fail! "Five-package runtime consumer evidence is missing" {}))
    (when-not (= expected selected)
      (fail! "Five-package runtime consumer has the wrong direct references"
             {:expected (vec (sort expected))
              :actual (vec (sort selected))}))
    (when-not (str/includes?
               (or output "")
               "Complete PdfCube package family runtime workflow passed.")
      (fail! "Five-package runtime consumer did not execute its workflow"
             {:output output}))
    {:package-references (vec (sort selected))
     :packages-root (str (:packages-root consumer))
     :consumer-root (str (:consumer-root consumer))}))

(defn verify!
  "Packs the five packages once, executes the runtime family consumer, reuses
  exact isolated package views for all representative pinned-Java workflow
  slices, proves repeatability, and runs deliberate mismatch controls."
  ([]
   (verify! {}))
  ([{:keys [workspace-root family-fn run-command! slices]
     :or {family-fn family-packaging/verify!
          run-command! process/run!
          slices verification-slices}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         _ (workflow-coverage slices)
         family-proof
         (family-fn {:workspace-root root
                     :run-command! run-command!})
         runtime-consumer (runtime-family-consumer! family-proof)
         views (package-views family-proof)
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              "pdfcube-family-workflows"))
         results
         (mapv #(run-slice! root views run-command! %) slices)
         execution (validate-slice-results! slices results)
         normalized
         (write-text!
          (paths/resolve-path proof-root "normalized-observations.tsv")
          (normalized-family-trace results))
         perturbation
         (prove-mismatch-detection!
          normalized
          (paths/resolve-path proof-root "deliberate-mismatch.tsv"))
         package-identities
         (into
          (sorted-map)
          (map
           (fn [[profile view]]
             [(get package-profiles profile)
              (select-keys (:identity view) [:version :sha256])]))
          views)
         summary
         {:profile "pdfcube-family-workflows"
          :source {:version (baseline/upstream-version :pdfcube)
                   :revision pinned-revision}
          :packages package-identities
          :runtime-consumer runtime-consumer
          :workflow-coverage (vec (sort required-workflows))
          :execution execution
          :slices
          (into
           (sorted-map)
           (map
            (fn [{:keys [id profile package-id workflows comparisons
                         observations perturbation? repeat-of]}]
              [id
               {:profile profile
                :package-id package-id
                :workflows (vec (sort workflows))
                :comparisons comparisons
                :observations observations
                :perturbation perturbation?
                :repeat-of repeat-of}]))
           results)
          :deliberate-mismatch
          {:line (get-in perturbation [:mismatch :line])
           :expected (get-in perturbation [:mismatch :expected])
           :actual (get-in perturbation [:mismatch :actual])}
          :proof-root proof-root}]
     (write-text! (paths/resolve-path proof-root "summary.edn")
                  (str (pr-str (dissoc summary :proof-root)) "\n"))
     (println
      "Pinned Java/package complete PdfCube family workflows passed:"
      (pr-str
       {:source (:source summary)
        :packages (keys (:packages summary))
        :workflows (count (:workflow-coverage summary))
        :execution (:execution summary)}))
     summary)))
