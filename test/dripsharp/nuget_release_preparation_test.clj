(ns dripsharp.nuget-release-preparation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.nuget-release-preparation :as preparation]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- write-file!
  [^Path file contents]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file contents (make-array OpenOption 0))
  file)

(defn- target-commit
  [target]
  (apply str (repeat 40 (case target
                          :pdfcube "2"
                          :pkl "1"
                          :sqltrellis "3"))))

(defn- repository-proof
  [contract]
  {:target (:target contract)
   :repository-url (get-in contract [:publication :repository-url])
   :repository-commit (target-commit (:target contract))
   :source-sha256 (util/sha256-text (name (:target contract)))
   :inventory [{:path "src" :sha256 (util/sha256-text "stable")}]})

(defn- profile-dependencies
  [contract profile]
  (set (get-in contract [:profiles profile
                         :configuration :dependency-profiles])))

(defn- profile-closure
  [contract profile]
  (loop [pending [profile] result #{}]
    (if-let [current (peek pending)]
      (if (contains? result current)
        (recur (pop pending) result)
        (recur (into (pop pending)
                     (profile-dependencies contract current))
               (conj result current)))
      result)))

(defn- package-id
  [contract profile]
  (get-in contract [:profiles profile
                    :destination :configuration :package :id]))

(defn- package-version
  [contract profile]
  (get-in contract [:publication :nuget :packages
                    (package-id contract profile) :version]))

(defn- package-dependencies
  [contract profile]
  (let [destination
        (get-in contract [:profiles profile :destination :configuration])]
    (->> (concat
          (for [dependency-profile (profile-dependencies contract profile)]
            {:id (package-id contract dependency-profile)
             :version (package-version contract dependency-profile)})
          (:runtime-packages destination))
         (mapv #(select-keys % [:id :version])))))

(defn- fake-package
  [^Path inputs contract profile]
  (let [id (package-id contract profile)
        version (package-version contract profile)
        destination
        (assoc-in
         (get-in contract [:profiles profile :destination :configuration])
         [:package :version] version)
        symbols? (= :snupkg (get-in destination [:package :symbols]))
        package-file (str id "." version ".nupkg")
        symbol-file (str id "." version ".snupkg")
        artifact (write-file! (paths/resolve-path inputs package-file)
                              (str id "|" version "|package"))
        symbol-artifact
        (when symbols?
          (write-file! (paths/resolve-path inputs symbol-file)
                       (str id "|" version "|symbols")))
        package-sha256 (util/sha256-file artifact)
        symbol-sha256 (some-> symbol-artifact util/sha256-file)
        pdb-entry (str "lib/netstandard2.0/" id ".pdb")
        pdb-sha256 (util/sha256-text (str id "|pdb"))
        source-path (str "src/" id "/Mechanical.cs")
        source-paths [source-path]
        totals
        {:files 1
         :mechanical-lines 10
         :authored-compat-lines 0
         :authored-destination-runtime-lines 0
         :vendored-third-party-lines 0
         :authored-lines 0
         :total-lines 10
         :authored-fraction 0.0}
        policy
        {:schema-version 2
         :target (:target contract)
         :profile profile
         :package-id id
         :review "fixture-review"
         :evidence [(first (map :id (get-in contract [:proof :ladders])))]
         :budget {:authored-lines 0 :total-lines 10 :authored-fraction 0.0}
         :guarded-compatibility-sources 0
         :sources []}
        ledger
        {:schema-version 3
         :files [{:path source-path
                  :class :mechanical
                  :source {:file (str "upstream/" id ".java")
                           :revision (get-in contract
                                             [:baseline :record :upstream
                                              :revision])}
                  :lines 10}]
         :totals totals
         :policy policy}
        verification
        {:schema-version 3
         :verified-files 1
         :source-paths source-paths
         :source-inventory-sha256
         (util/sha256-text (str/join "\n" source-paths))
         :totals totals
         :policy policy
         :assembly-input
         {:include "src/**/*.cs"
          :source-inventory-sha256
          (util/sha256-text (str/join "\n" source-paths))}}]
    (cond->
     {:profile profile
      :artifact artifact
      :destination destination
      :identity {:id id :version version
                 :file package-file :sha256 package-sha256}
      :authorship ledger
      :inspection {:dependencies (package-dependencies contract profile)
                   :authorship verification}}
      symbols?
      (assoc
       :symbol-artifact symbol-artifact
       :symbol {:id id :version version
                :file symbol-file :sha256 symbol-sha256
                :pdb-sha256 pdb-sha256}
       :symbol-inspection {:pdb-entry pdb-entry :pdb-sha256 pdb-sha256}))))

(defn- fake-workflow
  [^Path root]
  (let [source-root (paths/workspace-root)
        contracts
        (into {}
              (for [target [:pdfcube :pkl :sqltrellis]]
                [target (target-directory/read-target source-root target)]))
        _
        (doseq [target [:pdfcube :pkl :sqltrellis]]
          (write-file!
           (paths/resolve-path root "targets" (name target) "target.edn")
           (str (pr-str {:target target
                         :publication {:kind :generated-repository}})
                "\n")))
        _
        (write-file!
         (paths/resolve-path root "targets" "rawhttp" "target.edn")
         (str (pr-str {:target :rawhttp
                       :publication {:kind :conformance-only}})
              "\n"))
        inputs (doto (paths/resolve-path root "target" "fixture-inputs")
                 (Files/createDirectories (make-array FileAttribute 0)))
        proof-calls (atom [])
        reduced-proof-calls (atom [])
        package-calls (atom [])
        repository-calls (atom [])
        proof-fn
        (fn [{:keys [target]}]
          (let [contract (get contracts (keyword target))]
            (swap! proof-calls conj target)
            (mapv (fn [{:keys [id]}] {:id id :result :passed})
                  (get-in contract [:proof :ladders]))))
        repository-proof-fn
        (fn [{:keys [target-contract]}]
          (swap! repository-calls conj (:target target-contract))
          (repository-proof target-contract))
        package-fn
        (fn [{:keys [target profile]}]
          (let [contract (get contracts (keyword target))
                closure (profile-closure contract profile)
                packages (mapv #(fake-package inputs contract %)
                               (sort closure))
                primary (first (filter #(= profile (:profile %)) packages))
                feed (doto (paths/resolve-path root "target" "fixture-feeds"
                                               target profile)
                       (Files/createDirectories
                        (make-array FileAttribute 0)))]
            (swap! package-calls conj [target profile])
            {:verification {:build-configuration "Release"}
             :packing-summary
             {:clean-builds 2
              :repository-commit (target-commit (keyword target))}
             :identity (:identity primary)
             :packages packages
             :feed feed
             :dependency-proof {:packages (mapv :identity packages)}
             :run {:exit 0}}))
        bundle-fn
        (fn [{:keys [plan package-result]}]
          (let [bundle (:bundle plan)
                components (:packages package-result)
                component-ids (set (:component-package-ids bundle))
                base (first (filter #(= (:package-id bundle)
                                        (get-in % [:identity :id]))
                                    components))
                dependencies
                (->> components
                     (mapcat #(get-in % [:inspection :dependencies]))
                     (remove #(contains? component-ids (:id %)))
                     distinct
                     (sort-by (juxt :id :version))
                     vec)
                pdbs
                (->> components
                     (map (fn [package]
                            (sorted-map
                             :entry (get-in package
                                            [:symbol-inspection :pdb-entry])
                             :sha256 (get-in package
                                             [:symbol-inspection :pdb-sha256]))))
                     (sort-by :entry)
                     vec)]
            {:packages
             [(-> base
                  (assoc :profile (:profile bundle)
                         :destination
                         (get-in plan
                                 [:contract :profiles (:profile bundle)
                                  :destination :configuration])
                         :inspection (assoc (:inspection base)
                                            :dependencies dependencies)
                         :symbol-inspection {:pdbs pdbs}))]}))]
    {:proof-calls proof-calls
     :reduced-proof-calls reduced-proof-calls
     :package-calls package-calls
     :repository-calls repository-calls
     :contracts contracts
     :options {:workspace-root root
               :selection "all"
               :read-target-fn
               (fn [_ target]
                 (get contracts (keyword target)))
               :proof-fn proof-fn
               :reduced-proof-fn
               (fn [{:keys [target]}]
                 (swap! reduced-proof-calls conj target)
                 {:exit 0 :output ""})
               :bundle-fn bundle-fn
               :package-fn package-fn
               :test-suite-report-fn
               (fn [_ contract repository-commit]
                 {:target (:target contract)
                  :product-family (:product-family contract)
                  :repository-commit repository-commit
                  :upstream-revision
                  (get-in contract [:baseline :record :upstream :revision])
                  :mechanical-upstream-inputs 1
                  :cases 1
                  :fixtures 0
                  :authored-files []
                  :authored-lines 0
                  :evidence (mapv :id (get-in contract [:proof :ladders]))})
               :repository-proof-fn repository-proof-fn
               :getenv-fn {}}}))

(deftest discovery-selects-only-complete-production-package-catalogs
  (let [products (preparation/discover-products!)
        ids (mapv :target products)
        packages
        (into #{}
              (mapcat #(keys (get-in % [:publication :nuget :packages])))
              products)]
    (is (= [:pdfcube :pkl :sqltrellis] ids))
    (is (= #{"DripSharp.Brine.Parser"
             "DripSharp.Brine"
             "DripSharp.PdfCarton.IO"
             "DripSharp.PdfCarton.Fonts"
             "DripSharp.PdfCarton.Xmp"
             "DripSharp.PdfCarton"
             "DripSharp.PdfCarton.Preflight"
             "DripSharp.SqlTrellis"}
           packages))
    (is (not-any? #{:rawhttp} ids))))

(deftest repository-stability-is-bound-to-the-clean-product-commit
  (let [initial {:target :pdfcube
                 :repository-url "https://github.com/dripsharp/pdfcarton.git"
                 :repository-commit (target-commit :pdfcube)
                 :source-sha256 "full-target"
                 :inventory [[:file "src/Complete.cs" "full"]]}
        profile (assoc initial
                       :source-sha256 "profile-only"
                       :inventory [[:file "src/Profile.cs" "profile"]])]
    (is (= profile
           (#'preparation/exact-stable-repository! initial profile)))
    (is (= :nuget-release-preparation-failed
           (:kind
            (failure-data
             #(#'preparation/exact-stable-repository!
               initial
               (assoc profile :repository-commit
                      (apply str (repeat 40 "f"))))))))))

(deftest one-product-preparation-emits-one-public-pdfcarton-package
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-preparation-pdfcarton-test-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        result (preparation/prepare!
                (assoc (:options fixture) :selection "pdfcube"))]
    (is (= 1 (get-in result [:manifest :product-count])))
    (is (= 1 (get-in result [:manifest :package-count])))
    (is (= ["DripSharp.PdfCarton"]
           (get-in result [:manifest :publish-order])))
    (is (= [["pdfcube" "pdfcube-preflight"]]
           @(:package-calls fixture)))))

(deftest github-actions-can-explicitly-skip-exhaustive-release-tests
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-skip-tests-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        result
        (preparation/prepare!
         (assoc (:options fixture)
                :selection "sqltrellis"
                :getenv-fn
                {"DRIPSHARP_NUGET_RELEASE_SKIP_TESTS" "1"
                 "GITHUB_ACTIONS" "true"}))]
    (is (= :skipped-for-github-free-runner
           (get-in result [:manifest :test-verification])))
    (is (empty? @(:proof-calls fixture)))
    (is (= [["sqltrellis" "sqltrellis"]]
           @(:package-calls fixture)))))

(deftest brine-releases-use-the-product-owned-bounded-verifier
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-reduced-brine-tests-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        result
        (preparation/prepare!
         (assoc (:options fixture)
                :selection "pkl"
                :getenv-fn
                {"BRINE_RELEASE_REDUCED_TESTS" "1"}))]
    (is (= :reduced-brine-release
           (get-in result [:manifest :test-verification])))
    (is (empty? @(:proof-calls fixture)))
    (is (= ["pkl"] @(:reduced-proof-calls fixture)))
    (is (= 2 (count @(:package-calls fixture))))))

(deftest the-legacy-whole-ladder-skip-is-disabled-for-brine
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-brine-old-skip-tests-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        failure
        (failure-data
         #(preparation/prepare!
           (assoc (:options fixture)
                  :selection "pkl"
                  :getenv-fn
                  {"DRIPSHARP_NUGET_RELEASE_SKIP_TESTS" "1"
                   "GITHUB_ACTIONS" "true"})))]
    (is (= :brine-whole-ladder-skip-disabled (:reason failure)))
    (is (empty? @(:proof-calls fixture)))
    (is (empty? @(:reduced-proof-calls fixture)))
    (is (empty? @(:package-calls fixture)))))

(deftest reduced-brine-mode-rejects-a-non-heavy-proof-ladder
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-brine-non-heavy-tests-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        contract
        (-> (get (:contracts fixture) :pkl)
            (assoc-in [:proof :ladders 0 :kind] :target-validations)
            (assoc-in [:proof :ladders 0 :resource-class] :standard))
        failure
        (failure-data
         #(preparation/prepare!
           (assoc (:options fixture)
                  :selection "pkl"
                  :getenv-fn
                  {"BRINE_RELEASE_REDUCED_TESTS" "1"}
                  :read-target-fn (fn [_ _] contract))))]
    (is (= :non-heavy-proof-ladder-in-reduced-mode (:reason failure)))
    (is (empty? @(:proof-calls fixture)))
    (is (empty? @(:reduced-proof-calls fixture)))
    (is (empty? @(:package-calls fixture)))))

(deftest a-brine-release-smoke-failure-stays-fatal-in-reduced-mode
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-brine-smoke-failure-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        failure
        (failure-data
         #(preparation/prepare!
           (assoc (:options fixture)
                  :selection "pkl"
                  :getenv-fn
                  {"BRINE_RELEASE_REDUCED_TESTS" "1"}
                  :reduced-proof-fn
                  (fn [_]
                    (throw
                     (ex-info "Mandatory Brine release smoke failed"
                              {:kind :command-failed :exit 23}))))))]
    (is (= :command-failed (:kind failure)))
    (is (= 23 (:exit failure)))
    (is (empty? @(:proof-calls fixture)))
    (is (empty? @(:package-calls fixture)))))

(deftest brine-release-verifier-command-contract-is-fail-closed
  (let [root (paths/workspace-root)
        product (paths/resolve-path root "products/brine")
        result
        (process/run!
         {:command ["bash" "eng/test-verify-release.sh"]
          :directory product})]
    (is (= 0 (:exit result)))
    (is (str/includes? (:output result)
                       "Brine release-verification controls passed."))))

(deftest release-test-skipping-is-restricted-to-github-actions
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-local-skip-tests-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        failure
        (failure-data
         #(preparation/prepare!
           (assoc (:options fixture)
                  :selection "sqltrellis"
                  :getenv-fn
                  {"DRIPSHARP_NUGET_RELEASE_SKIP_TESTS" "1"})))]
    (is (= :skip-tests-outside-github-actions (:reason failure)))
    (is (empty? @(:proof-calls fixture)))))

(deftest aggregate-preparation-is-deterministic-and-credential-free
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-preparation-test-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        options (:options fixture)
        first-result (preparation/prepare! options)
        first-manifest-bytes
        (Files/readAllBytes ^Path (:manifest-file first-result))
        first-artifact-hashes
        (into
         (sorted-map)
         (for [^Path file
               (with-open [entries
                           (Files/list ^Path (:artifact-directory first-result))]
                 (->> (.toArray entries)
                      (map #(cast Path %))
                      (filter paths/regular-file?)
                      vec))]
           [(str (.getFileName file)) (util/sha256-file file)]))
        expected-symbol-statuses
        (frequencies
         (for [[_ contract] (:contracts fixture)
               [_ profile] (:profiles contract)]
           (if (= :snupkg
                  (get-in profile
                          [:destination :configuration :package :symbols]))
             :paired
             :absent)))]
    (is (= {:paired 8} expected-symbol-statuses))
    (is (= ["pdfcube" "pkl" "sqltrellis"] @(:proof-calls fixture)))
    (is (= 4 (count @(:package-calls fixture))))
    (is (= 7 (count @(:repository-calls fixture))))
    (is (= 4 (get-in first-result [:manifest :package-count])))
    (is (= 3 (get-in first-result [:manifest :product-count])))
    (is (= [] (get-in first-result [:manifest :network-mutations])))
    (is (= :complete (get-in first-result [:manifest :test-verification])))
    (is (= :not-checked
           (get-in first-result [:manifest :remote-availability])))
    (is (false? (get-in first-result
                        [:manifest :publication-credentials-accepted])))
    (is (= (:manifest first-result)
           (util/read-single-edn-string!
            (Files/readString ^Path (:manifest-file first-result)))))
    (is (paths/regular-file?
         (get-in first-result [:authorship-report :markdown])))
    (is (= 8
           (count
            (mapcat :packages
                    (get-in first-result
                            [:authorship-report :report :products])))))
    (is (= 9
           (count first-artifact-hashes)))
    (is (= {:paired 4}
           (frequencies
            (map #(get-in % [:symbol-pairing :status])
                 (get-in first-result [:manifest :packages])))))
    (let [order (zipmap (get-in first-result [:manifest :publish-order])
                        (range))]
      (is (< (order "DripSharp.Brine.Parser")
             (order "DripSharp.Brine")))
      (is (contains? order "DripSharp.PdfCarton")))
    (reset! (:proof-calls fixture) [])
    (reset! (:package-calls fixture) [])
    (reset! (:repository-calls fixture) [])
    (let [second-result (preparation/prepare! options)
          second-manifest-bytes
          (Files/readAllBytes ^Path (:manifest-file second-result))
          second-artifact-hashes
          (into
           (sorted-map)
           (for [^Path file
                 (with-open [entries
                             (Files/list
                              ^Path (:artifact-directory second-result))]
                   (->> (.toArray entries)
                        (map #(cast Path %))
                        (filter paths/regular-file?)
                        vec))]
             [(str (.getFileName file)) (util/sha256-file file)]))]
      (is (= (seq first-manifest-bytes) (seq second-manifest-bytes)))
      (is (= first-artifact-hashes second-artifact-hashes))
      (is (= (:manifest-sha256 first-result)
             (:manifest-sha256 second-result)))
      (is (= (get-in first-result [:authorship-report :markdown-sha256])
             (get-in second-result [:authorship-report :markdown-sha256]))))
    (testing "identical inputs reject mutation of a retained artifact"
      (let [package-file
            (get-in first-result [:manifest :packages 0 :files :package
                                  :filename])]
        (write-file!
         (paths/resolve-path (:artifact-directory first-result) package-file)
         "tampered retained package")
        (is (= :nuget-release-preparation-failed
               (:kind (failure-data #(preparation/prepare! options)))))))
    (testing "credential-shaped options fail before any proof"
      (reset! (:proof-calls fixture) [])
      (is (= #{:api-key}
             (set (:forbidden-options
                   (failure-data
                    #(preparation/prepare!
                      (assoc options :api-key "not-accepted")))))))
      (is (empty? @(:proof-calls fixture))))))
