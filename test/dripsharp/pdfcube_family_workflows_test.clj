(ns dripsharp.pdfcube-family-workflows-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.family-workflows :as family-workflows])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private profile-order
  ["pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
   "pdfcube-pdfbox" "pdfcube-preflight"])

(def ^:private dependencies
  {"pdfcube-io" []
   "pdfcube-fontbox" ["pdfcube-io"]
   "pdfcube-xmpbox" []
   "pdfcube-pdfbox" ["pdfcube-io" "pdfcube-fontbox"]
   "pdfcube-preflight"
   ["pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox" "pdfcube-pdfbox"]})

(defn- emission
  [profile]
  {:profile profile
   :dependency-profiles
   (case profile
     "pdfcube-fontbox" ["pdfcube-io"]
     "pdfcube-pdfbox" ["pdfcube-io" "pdfcube-fontbox"]
     "pdfcube-preflight" ["pdfcube-pdfbox" "pdfcube-xmpbox"]
     [])
   :transitive-dependency-profiles (get dependencies profile)
   :source-project {:revision family-workflows/pinned-revision}
   :project-input {:project-id profile :java-toolchain {:release 21}}
   :model-totals {:types 1}
   :public-api-boundary {:profile profile}
   :public-surface-strategy :complete-accessible-java-library
   :destination
   {:package {:id (get family-workflows/package-profiles profile)}
    :project {:target-framework "netstandard2.0"}}
   :summary {:declarations 1}
   :public-metadata {:rows []}})

(defn- raw-generation
  []
  (let [records (mapv emission profile-order)
        main (last records)]
    {:generation-profile {:profile "pdfcube-preflight"}
     :dependency-profiles (:dependency-profiles main)
     :dependency-emissions (vec (butlast records))
     :source-project (:source-project main)
     :resolved-project-input (:project-input main)
     :java-model {:totals (:model-totals main)}
     :public-api-boundary (:public-api-boundary main)
     :public-surface-strategy (:public-surface-strategy main)
     :destination (:destination main)
     :emission
     (select-keys
      main
      [:summary :public-metadata])}))

(defn- package
  [profile]
  (let [id (get family-workflows/package-profiles profile)]
    {:profile profile
     :primary? (= profile "pdfcube-preflight")
     :artifact (paths/path (str "/proof/" id ".nupkg"))
     :identity
     {:id id :version family-workflows/package-version
      :sha256 (str profile "-hash")}
     :inspection {:dependencies []}
     :resource-proof {:assembly-identity {:name id}}
     :resources []}))

(defn- consumer
  [index profile]
  (let [id (get family-workflows/package-profiles profile)
        closure-ids
        (set
         (map family-workflows/package-profiles
              (conj (set (get dependencies profile)) profile)))]
    {:consumer-name id
     :selected-packages
     [{:id id :version family-workflows/package-version
       :sha256 (str profile "-hash")}]
     :dependency-proof
     {:package-references [[id family-workflows/package-version]]
      :packages
      (mapv
       (fn [package-id]
         {:id package-id :version family-workflows/package-version
          :sha256 (str package-id "-hash")})
       (sort (conj closure-ids "External.Common")))}
     :consumer-root (paths/path (str "/proof/consumer-" index))
     :packages-root (paths/path (str "/proof/cache-" index))
     :run {:output (str id " passed")}}))

(defn- family-proof
  []
  (let [packages (mapv package profile-order)
        consumers (mapv consumer (range) profile-order)]
    {:package-proof
     {:verification
      {:generation (raw-generation)
       :public-surface
       {:strategy :complete-accessible-java-library
        :assemblies
        (mapv
         (fn [profile]
           {:assembly
            (get family-workflows/package-profiles profile)})
         profile-order)}}
      :packages packages
      :external-packages
      [{:id "External.Common" :version "1.0.0" :sha256 "external"}]
      :feed (paths/path "/proof/feed")
      :proof-root (paths/path "/proof")
      :summary {:clean-builds 2}}
     :consumer-proofs consumers}))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch ExceptionInfo error error)))

(deftest workflow-inventory-is-exact-and-fail-closed
  (is (= family-workflows/required-workflows
         (family-workflows/workflow-coverage
          family-workflows/verification-slices)))
  (testing "a missing workflow is rejected"
    (let [slices
          (mapv
           #(update % :workflows disj :signed-load)
           family-workflows/verification-slices)
          error
          (caught #(family-workflows/workflow-coverage slices))]
      (is (= :pdfcube-family-workflows-failed
             (:kind (ex-data error))))
      (is (= [:signed-load] (:missing (ex-data error))))))
  (testing "an invented workflow is rejected"
    (let [slices
          (update-in family-workflows/verification-slices
                     [0 :workflows] conj :invented)
          error
          (caught #(family-workflows/workflow-coverage slices))]
      (is (= [:invented] (:unexpected (ex-data error)))))))

(deftest one-family-pack-produces-five-exact-isolated-package-views
  (let [views (family-workflows/package-views (family-proof))]
    (is (= (set profile-order) (set (keys views))))
    (doseq [[profile view] views
            :let [expected-profiles
                  (conj (set (get dependencies profile)) profile)
                  expected-ids
                  (set
                   (map family-workflows/package-profiles
                        expected-profiles))
                  packages (:packages view)
                  generation (get-in view [:verification :generation])]]
      (is (= expected-ids
             (set (map #(get-in % [:identity :id]) packages))))
      (is (= [(get family-workflows/package-profiles profile)]
             (mapv #(get-in % [:identity :id])
                   (filter :primary? packages))))
      (is (= profile
             (get-in generation [:generation-profile :profile])))
      (is (= (set (get dependencies profile))
             (set (map :profile (:dependency-emissions generation)))))
      (is (= ["External.Common"]
             (mapv :id (:external-packages view)))))
    (is (= 5
           (count
            (set (map #(str (:packages-root %)) (vals views))))))
    (is (= #{"DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.Xmp"
             "DripSharp.PdfCarton" "DripSharp.PdfCarton.Preflight"}
           (set
            (map #(get-in % [:identity :id]) (vals views)))))))

(defn- result
  [slice]
  (let [id (:id slice)
        package-id
        (get family-workflows/package-profiles (:profile slice))
        repeated? (= id :io-repeat)
        base-id (if repeated? :io id)
        perturbation?
        (contains? #{:io :fontbox :xmpbox :pdfbox :preflight} base-id)]
    {:id id
     :profile (:profile slice)
     :package-id package-id
     :workflows (:workflows slice)
     :repeat-of (:repeat-of slice)
     :comparisons 2
     :observations 7
     :perturbation? perturbation?
     :summary
     {:source {:version "3.0.8"
               :revision family-workflows/pinned-revision}
      :package {:id package-id
                :version family-workflows/package-version
                :sha256 (str (:profile slice) "-hash")}
      :consumer {:packages [{:id package-id}]}
      :trace {:observations 7 :families ["representative"]}
      :package-comparison {:matched-lines 7}
      :perturbation-line (when perturbation? 8)}}))

(deftest aggregate-results-require-all-packages-controls-and-repeatability
  (let [results (mapv result family-workflows/verification-slices)
        summary
        (family-workflows/validate-slice-results!
         family-workflows/verification-slices results)]
    (is (= 8 (:slices summary)))
    (is (= 16 (:comparisons summary)))
    (is (= [:io-repeat] (:repeated summary)))
    (is (= [:fontbox :io :io-repeat :pdfbox :preflight :xmpbox]
           (:perturbations summary))))
  (testing "changed repeated evidence is rejected"
    (let [results (mapv result family-workflows/verification-slices)
          repeat-index
          (first
           (keep-indexed
            (fn [index value]
              (when (= :io-repeat (:id value)) index))
            results))
          changed
          (assoc-in results
                    [repeat-index :summary :trace :observations]
                    8)
          error
          (caught
           #(family-workflows/validate-slice-results!
             family-workflows/verification-slices changed))]
      (is (= :pdfcube-family-workflows-failed
             (:kind (ex-data error)))))))

(deftest consumer-output-is-released-after-a-profiles-last-slice
  (let [[io io-repeat fontbox]
        (take 3 family-workflows/verification-slices)]
    (is (false?
         (#'family-workflows/last-profile-slice?
          io [io-repeat fontbox])))
    (is
     (#'family-workflows/last-profile-slice?
      io-repeat [fontbox]))
    (is
     (#'family-workflows/last-profile-slice?
      fontbox []))))

(deftest aggregate-accepts-established-package-summary-identities
  (is (= {:id "DripSharp.PdfCarton.IO"
          :version family-workflows/package-version
          :sha256 "io-hash"}
         (#'family-workflows/summary-package-identity
          {:package
           {:package-id "DripSharp.PdfCarton.IO"
            :version family-workflows/package-version
            :sha256 "io-hash"}})))
  (is (= {:id "DripSharp.PdfCarton.Preflight"
          :version family-workflows/package-version
          :sha256 "preflight-hash"}
         (#'family-workflows/summary-package-identity
          {:package
           {:id "DripSharp.PdfCarton.Preflight"
            :version family-workflows/package-version
            :sha256 "preflight-hash"}}))))

(deftest normalized-family-comparator-detects-perturbation
  (let [oracle
        (Files/createTempFile
         "pdfcube-family-oracle-" ".tsv"
         (make-array FileAttribute 0))
        perturbed
        (Files/createTempFile
         "pdfcube-family-perturbed-" ".tsv"
         (make-array FileAttribute 0))]
    (Files/writeString
     oracle
     "slice\tio\tDripSharp.PdfCarton.IO|resource-lifetime|2|7\n"
     (make-array OpenOption 0))
    (let [comparison
          (family-workflows/prove-mismatch-detection!
           oracle perturbed)]
      (is (:mismatch comparison))
      (is (= 2 (get-in comparison [:mismatch :line]))))))
