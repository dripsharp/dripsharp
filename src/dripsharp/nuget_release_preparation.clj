(ns dripsharp.nuget-release-preparation
  "Credential-free local preparation of the complete production NuGet set.

  Product and package selection comes exclusively from validated target
  contracts. The workflow runs complete target proofs and the existing
  twice-clean pack, exact inspection, fresh-feed, and isolated-consumer gate.
  It writes local artifacts and a deterministic manifest; it has no publishing,
  tagging, upload, ownership, or remote-mutation operation."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.authorship-report :as authorship-report]
            [dripsharp.harness :as harness]
            [dripsharp.nuget-package-bundle :as nuget-package-bundle]
            [dripsharp.paths :as paths]
            [dripsharp.product-repository :as product-repository]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.target-execution :as target-execution]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def schema-version 3)

(def ^:private commit-pattern #"[0-9a-f]{40}|[0-9a-f]{64}")
(def ^:private sha256-pattern #"[0-9a-f]{64}")
(def ^:private selection-pattern #"[a-z][a-z0-9-]*")
(def ^:private forbidden-project-components
  #{"test" "tests" "validation" "research" "vendor"})
(def ^:private forbidden-project-fragment
  #"(?i)(^|[._-])(tests?|validation|research|vendor)([._-]|$)")
(def ^:private forbidden-option-keys
  #{:api-key :credential :credentials :nuget-api-key :password :token})

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :nuget-release-preparation-failed))))

(defn- regular-files
  [^Path directory]
  (if-not (paths/directory? directory)
    []
    (with-open [entries (Files/list directory)]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (sort-by str)
           vec))))

(defn- child-directories
  [^Path directory]
  (if-not (paths/directory? directory)
    []
    (with-open [entries (Files/list directory)]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/directory?)
           (sort-by str)
           vec))))

(defn- delete-tree!
  [^Path directory]
  (when (paths/exists? directory)
    (with-open [entries (Files/walk directory (make-array FileVisitOption 0))]
      (doseq [^Path entry (->> (.toArray entries)
                               (map #(cast Path %))
                               (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- generated-product-target-id
  [^Path directory]
  (let [manifest-file (paths/resolve-path directory "target.edn")
        manifest
        (try
          (util/read-single-edn-string!
           (Files/readString manifest-file StandardCharsets/UTF_8))
          (catch RuntimeException error
            (throw
             (ex-info "Target discovery manifest is not exact EDN"
                      {:kind :nuget-release-preparation-failed
                       :path (str manifest-file)}
                      error))))
        directory-id (str (.getFileName directory))]
    (when (= :generated-repository (get-in manifest [:publication :kind]))
      (when-not (= (keyword directory-id) (:target manifest))
        (fail! "Generated-product target directory and manifest identity disagree"
               {:directory directory-id :target (:target manifest)}))
      directory-id)))

(defn discover-products!
  "Discovers publishable production targets from direct `targets/*/target.edn`
  contracts. Conformance-only targets and arbitrary projects below products,
  research, vendor, tests, or validation directories are never inventory
  candidates."
  ([] (discover-products! {}))
  ([{:keys [workspace-root read-target-fn]
     :or {read-target-fn target-directory/read-target}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         targets-root (paths/resolve-path root "targets")]
     (when-not (paths/directory? targets-root)
       (fail! "Target contract directory is missing"
              {:path (str targets-root)}))
     (->> (child-directories targets-root)
          (filter #(paths/regular-file? (paths/resolve-path % "target.edn")))
          (keep generated-product-target-id)
          (map #(read-target-fn root %))
          (filter #(map? (get-in % [:publication :nuget :packages])))
          (sort-by (comp name :target))
          vec))))

(defn- selected-products!
  [products selection]
  (let [selection (some-> selection str str/trim)
        by-id (into {} (map (juxt (comp name :target) identity)) products)]
    (when-not (and (string? selection)
                   (or (= "all" selection)
                       (re-matches selection-pattern selection)))
      (fail! "NuGet release preparation selection is invalid"
             {:selection selection
              :available (into ["all"] (sort (keys by-id)))}))
    (if (= "all" selection)
      (if (seq products)
        products
        (fail! "No publishable production targets were discovered"
               {:selection selection}))
      (if-let [product (get by-id selection)]
        [product]
        (fail! "NuGet release preparation selected an unavailable product target"
               {:selection selection
                :available (into ["all"] (sort (keys by-id)))})))))

(defn- forbidden-production-project?
  [project-path]
  (let [components (str/split (str project-path) #"[/\\]+")]
    (boolean
     (some #(or (contains? forbidden-project-components (str/lower-case %))
                (re-find forbidden-project-fragment %))
           components))))

(defn- package-profile!
  [contract profile-id]
  (let [profile (get-in contract [:profiles profile-id])
        project-path
        (get-in contract [:publication :profile-projects profile-id])
        destination (get-in profile [:destination :configuration])
        package (:package destination)
        package-id (:id package)]
    (when-not profile
      (fail! "NuGet publication selects an unavailable production profile"
             {:target (:target contract) :profile profile-id}))
    (when-not (and (string? project-path)
                   (str/starts-with? project-path "src/")
                   (not (forbidden-production-project? project-path)))
      (fail! "NuGet publication project is not a production source project"
             {:target (:target contract)
              :profile profile-id
              :project project-path}))
    (when-not (and (string? package-id)
                   (not (str/blank? package-id))
                   (not (re-find forbidden-project-fragment package-id))
                   (= package-id
                      (get-in destination [:project :assembly-name])))
      (fail! "NuGet production profile has no exact package/assembly identity"
             {:target (:target contract)
              :profile profile-id
              :package package-id
              :assembly (get-in destination [:project :assembly-name])}))
    {:profile profile-id
     :project project-path
     :package-id package-id
     :version (:version package)
     :dependency-profiles
     (set (get-in profile [:configuration :dependency-profiles]))
     :destination destination}))

(defn- topological-profile-order!
  [target records]
  (let [by-profile (into {} (map (juxt :profile identity)) records)
        available (set (keys by-profile))]
    (doseq [{:keys [profile dependency-profiles]} records]
      (when-not (set/subset? dependency-profiles available)
        (fail! "NuGet production profile depends on a non-publishable profile"
               {:target target
                :profile profile
                :missing (vec (sort (set/difference dependency-profiles
                                                    available)))})))
    (loop [remaining
           (into (sorted-map)
                 (map (juxt :profile :dependency-profiles)) records)
           result []]
      (if (empty? remaining)
        result
        (let [ready (->> remaining
                         (keep (fn [[profile dependencies]]
                                 (when (empty? dependencies) profile)))
                         sort
                         vec)]
          (when (empty? ready)
            (fail! "NuGet production profile graph contains a cycle"
                   {:target target
                    :remaining
                    (into (sorted-map)
                          (map (fn [[profile dependencies]]
                                 [profile (vec (sort dependencies))]))
                          remaining)}))
          (let [ready-set (set ready)]
            (recur
             (into (sorted-map)
                   (for [[profile dependencies] remaining
                         :when (not (contains? ready-set profile))]
                     [profile (set/difference dependencies ready-set)]))
             (into result ready))))))))

(defn- production-plan!
  [contract]
  (let [publication (:publication contract)
        profile-projects (:profile-projects publication)
        catalog (get-in publication [:nuget :packages])
        bundle (get-in publication [:nuget :bundle])
        records (mapv #(package-profile! contract %)
                      (sort (keys profile-projects)))
        actual-ids (set (map :package-id records))
        catalog-ids (set (keys catalog))
        duplicate-ids (->> records
                           (map :package-id)
                           frequencies
                           (keep (fn [[id count]] (when (< 1 count) id)))
                           sort
                           vec)
        order (topological-profile-order! (:target contract) records)
        by-profile (into {} (map (juxt :profile identity)) records)]
    (when (seq duplicate-ids)
      (fail! "NuGet production inventory contains duplicate package identities"
             {:target (:target contract) :duplicates duplicate-ids}))
    (when-not (= catalog-ids actual-ids)
      (fail! "NuGet catalog and target-owned production profiles disagree"
             {:target (:target contract)
              :catalog (vec (sort catalog-ids))
              :profiles (vec (sort actual-ids))
              :missing (vec (sort (set/difference catalog-ids actual-ids)))
              :unexpected (vec (sort (set/difference actual-ids catalog-ids)))}))
    {:bundle bundle
     :component-package-ids catalog-ids
     :contract contract
     :profiles (mapv by-profile order)
     :release-profiles
     (if bundle
       [(or (get by-profile (:profile bundle))
            (fail! "NuGet bundle selects an unavailable release profile"
                   {:target (:target contract)
                    :profile (:profile bundle)}))]
       (mapv by-profile order))
     :package-ids (if bundle #{(:package-id bundle)} catalog-ids)}))

(defn- profile-closure
  [profiles profile-id]
  (let [by-profile (into {} (map (juxt :profile identity)) profiles)]
    (loop [pending [profile-id] result #{}]
      (if-let [profile (peek pending)]
        (if (contains? result profile)
          (recur (pop pending) result)
          (let [record (or (get by-profile profile)
                           (fail! "Production profile closure is incomplete"
                                  {:profile profile-id :missing profile}))]
            (recur (into (pop pending) (:dependency-profiles record))
                   (conj result profile))))
        result))))

(defn- exact-proof!
  [contract proof]
  (let [expected (mapv :id (get-in contract [:proof :ladders]))
        actual (when (sequential? proof) (mapv :id proof))]
    (when-not (= expected actual)
      (fail! "Complete target proof did not return every declared ladder"
             {:target (:target contract)
              :expected expected
              :actual actual}))
    proof))

(defn- exact-repository-proof!
  [contract proof]
  (let [expected-target (:target contract)
        expected-url (get-in contract [:publication :repository-url])]
    (when-not (and (= expected-target (:target proof))
                   (= expected-url (:repository-url proof))
                   (re-matches commit-pattern
                               (or (:repository-commit proof) ""))
                   (re-matches sha256-pattern
                               (or (:source-sha256 proof) "")))
      (fail! "Generated product repository proof is incomplete or inconsistent"
             {:target expected-target
              :expected-repository expected-url
              :actual (select-keys proof
                                   [:target :repository-url
                                    :repository-commit :source-sha256])}))
    proof))

(defn- exact-dependencies!
  [package-id dependencies]
  (let [dependencies
        (mapv #(select-keys % [:id :version]) dependencies)
        invalid
        (filterv
         (fn [{:keys [id version] :as dependency}]
           (or (not= #{:id :version} (set (keys dependency)))
               (not (and (string? id) (not (str/blank? id))))
               (not (and (string? version) (not (str/blank? version))))))
         dependencies)
        duplicates (->> dependencies
                        (map :id)
                        frequencies
                        (keep (fn [[id count]] (when (< 1 count) id)))
                        sort
                        vec)]
    (when (or (seq invalid) (seq duplicates))
      (fail! "Inspected NuGet package dependencies are not exact"
             {:package package-id
              :invalid invalid
              :duplicates duplicates}))
    (->> dependencies
         (sort-by (juxt :id :version))
         (mapv #(sorted-map :id (:id %) :version (:version %))))))

(defn- simple-artifact!
  [artifact filename extension expected-sha256]
  (let [artifact (some-> artifact paths/path)
        actual-filename (some-> artifact .getFileName str)]
    (when-not (and artifact
                   (paths/regular-file? artifact)
                   (= filename actual-filename)
                   (str/ends-with? (str/lower-case filename) extension)
                   (= filename (str (paths/path filename)))
                   (re-matches sha256-pattern (or expected-sha256 "")))
      (fail! "Prepared NuGet artifact identity is invalid"
             {:artifact (some-> artifact str)
              :filename filename
              :extension extension
              :sha256 expected-sha256}))
    (let [actual-sha256 (util/sha256-file artifact)]
      (when-not (= expected-sha256 actual-sha256)
        (fail! "Prepared NuGet artifact differs from its inspected digest"
               {:artifact (str artifact)
                :expected expected-sha256
                :actual actual-sha256})))
    artifact))

(defn- package-record!
  [plan repository-proof package]
  (let [contract (:contract plan)
        target (:target contract)
        product-family (:product-family contract)
        package-id (get-in package [:identity :id])
        catalog-package
        (get-in contract [:publication :nuget :packages package-id])
        version (get-in package [:identity :version])
        package-file (get-in package [:identity :file])
        package-sha256 (get-in package [:identity :sha256])
        symbol (:symbol package)
        symbol-file (:file symbol)
        symbol-sha256 (:sha256 symbol)
        symbols? (boolean (or symbol (:symbol-artifact package)
                              (:symbol-inspection package)))
        package-artifact
        (simple-artifact! (:artifact package) package-file ".nupkg"
                          package-sha256)
        symbol-artifact
        (when symbols?
          (simple-artifact! (:symbol-artifact package) symbol-file ".snupkg"
                            symbol-sha256))
        target-framework
        (get-in package [:destination :project :target-framework])
        pdb-entry (get-in package [:symbol-inspection :pdb-entry])
        pdb-sha256 (get-in package [:symbol-inspection :pdb-sha256])
        pdbs
        (when symbols?
          (or (get-in package [:symbol-inspection :pdbs])
              (when (and pdb-entry pdb-sha256)
                [(sorted-map :entry pdb-entry :sha256 pdb-sha256)])))
        dependencies
        (exact-dependencies! package-id
                             (get-in package [:inspection :dependencies]))
        source-commit (get-in contract [:baseline :record :upstream :revision])
        product-commit (:repository-commit repository-proof)]
    (when-not catalog-package
      (fail! "Packed an artifact outside the target-owned NuGet catalog"
             {:target target :package package-id}))
    (when-not (= (:version catalog-package) version)
      (fail! "Packed NuGet version differs from the target-owned catalog"
             {:target target :package package-id
              :expected (:version catalog-package) :actual version}))
    (when-not (and (or (not symbols?)
                       (and (= package-id (:id symbol))
                            (= version (:version symbol))
                            (vector? pdbs)
                            (seq pdbs)
                            (= (count pdbs)
                               (count (distinct (map :entry pdbs))))
                            (every?
                             (fn [{:keys [entry sha256]}]
                               (and (string? entry)
                                    (str/ends-with? (str/lower-case entry) ".pdb")
                                    (re-matches sha256-pattern (or sha256 ""))))
                             pdbs)))
                   (string? target-framework)
                   (re-matches #"net(?:standard2[.]0|[1-9][0-9]*[.]0)"
                               target-framework)
                   (re-matches commit-pattern (or source-commit "")))
      (fail! "NuGet package lacks an exact symbol, framework, or source pairing"
             {:target target :package package-id
              :symbol (select-keys symbol [:id :version :file :sha256])
              :target-framework target-framework
              :pdbs pdbs
              :source-commit source-commit}))
    {:record
     (sorted-map
      :dependencies dependencies
      :files
      (cond->
       (sorted-map
        :package (sorted-map :filename package-file :sha256 package-sha256))
        symbols?
        (assoc :symbols
               (sorted-map :filename symbol-file :sha256 symbol-sha256)))
      :id package-id
      :product-commit product-commit
      :product-family product-family
      :profile (:profile package)
      :source-commit source-commit
      :symbol-pairing
      (if symbols?
        (sorted-map :package-filename package-file
                    :pdbs (vec (sort-by :entry pdbs))
                    :status :paired
                    :symbol-filename symbol-file)
        (sorted-map :status :absent))
      :target target
      :target-framework target-framework
      :version version)
     :artifacts
     (cond-> [{:path package-artifact
               :filename package-file
               :sha256 package-sha256}]
       symbols?
       (conj {:path symbol-artifact
              :filename symbol-file
              :sha256 symbol-sha256}))}))

(defn- copy-artifact!
  [^Path staging {:keys [^Path path filename sha256]}]
  (let [destination (paths/resolve-path staging filename)]
    (if (paths/exists? destination)
      (when-not (= sha256 (util/sha256-file destination))
        (fail! "Repeated package closures produced different artifact bytes"
               {:filename filename
                :expected (util/sha256-file destination)
                :actual sha256}))
      (Files/copy path destination
                  (make-array StandardCopyOption 0)))
    destination))

(defn- merge-package!
  [packages record]
  (let [package-id (:id record)]
    (if-let [existing (get packages package-id)]
      (if (= existing record)
        packages
        (fail! "Repeated package closures produced inconsistent package evidence"
               {:package package-id
                :first existing
                :second record}))
      (assoc packages package-id record))))

(defn- boundary-package
  [package]
  {:profile (:profile package)
   :identity (:identity package)
   :ledger (:authorship package)
   :verification (get-in package [:inspection :authorship])})

(defn- merge-boundary-package!
  [packages package]
  (let [package-id (get-in package [:identity :id])
        record (boundary-package package)]
    (if-let [existing (get packages package-id)]
      (if (= existing record)
        packages
        (fail! "Repeated package closures produced inconsistent authorship evidence"
               {:package package-id}))
      (assoc packages package-id record))))

(defn- portfolio-products!
  [product-records boundary-packages]
  (mapv
   (fn [{:keys [target product-family product-commit component-package-ids]}]
     (let [packages (mapv boundary-packages component-package-ids)]
       (when (some nil? packages)
         (fail! "Aggregate authorship report is missing a production package"
                {:target target
                 :expected component-package-ids
                 :available (vec (sort (keys boundary-packages)))}))
       {:target target
        :product-family product-family
        :repository-commit product-commit
        :packages packages}))
   product-records))

(defn- authorship-output-root
  [workspace-root selection]
  (let [base (paths/absolute
              (paths/resolve-path workspace-root "target" "authorship-report"))
        output (paths/absolute (paths/resolve-path base selection))]
    (when-not (and (.startsWith output base) (not= output base))
      (fail! "Authorship report directory is outside its deterministic target path"
             {:output (str output)}))
    (doseq [^Path candidate
            (take-while #(and % (.startsWith ^Path % workspace-root))
                        (iterate #(.getParent ^Path %) output))]
      (when (Files/isSymbolicLink candidate)
        (fail! "Authorship report path contains a symbolic link"
               {:path (str candidate) :output (str output)})))
    output))

(defn- exact-package-result!
  [plan profile-record repository-proof result]
  (let [profile (:profile profile-record)
        expected-closure (profile-closure (:profiles plan) profile)
        actual-closure (set (map :profile (:packages result)))
        expected-primary (:package-id profile-record)
        actual-primary (get-in result [:identity :id])
        summary (:packing-summary result)]
    (when-not (and (= "Release"
                      (get-in result [:verification :build-configuration]))
                   (= 2 (:clean-builds summary))
                   (= (:repository-commit repository-proof)
                      (:repository-commit summary))
                   (= expected-closure actual-closure)
                   (= expected-primary actual-primary)
                   (paths/directory? (:feed result))
                   (map? (:dependency-proof result))
                   (:run result))
      (fail! "NuGet profile preparation omitted a required proof stage"
             {:target (get-in plan [:contract :target])
              :profile profile
              :expected
              {:configuration "Release"
               :clean-builds 2
               :repository-commit (:repository-commit repository-proof)
               :profiles (vec (sort expected-closure))
               :primary expected-primary
               :fresh-feed true
               :isolated-consumer true}
              :actual
              {:configuration
               (get-in result [:verification :build-configuration])
               :clean-builds (:clean-builds summary)
               :repository-commit (:repository-commit summary)
               :profiles (vec (sort actual-closure))
               :primary actual-primary
               :fresh-feed (paths/directory? (:feed result))
               :isolated-consumer
               (boolean (and (map? (:dependency-proof result))
                             (:run result)))}}))
    result))

(defn- exact-stable-repository!
  [initial final]
  (when-not (= (select-keys initial
                            [:target :repository-url :repository-commit])
               (select-keys final
                            [:target :repository-url :repository-commit]))
    (fail! "Generated product state changed during NuGet release preparation"
           {:before (select-keys initial
                                 [:target :repository-url :repository-commit])
            :after (select-keys final
                                [:target :repository-url :repository-commit])}))
  final)

(defn- topological-publish-order!
  [packages]
  (let [package-ids (set (keys packages))
        dependencies
        (into
         (sorted-map)
         (for [[package-id package] packages]
           [package-id
            (into #{}
                  (keep
                   (fn [{dependency-id :id dependency-version :version}]
                     (when (contains? package-ids dependency-id)
                       (let [actual-version (:version (get packages dependency-id))]
                         (when-not (= actual-version dependency-version)
                           (fail! "Internal NuGet dependency version is not exact"
                                  {:package package-id
                                   :dependency dependency-id
                                   :expected actual-version
                                   :actual dependency-version}))
                         dependency-id))))
                  (:dependencies package))]))]
    (loop [remaining dependencies result []]
      (if (empty? remaining)
        result
        (let [ready (->> remaining
                         (keep (fn [[package-id required]]
                                 (when (empty? required) package-id)))
                         sort
                         vec)]
          (when (empty? ready)
            (fail! "NuGet package dependency graph contains a cycle"
                   {:remaining
                    (into (sorted-map)
                          (map (fn [[id required]]
                                 [id (vec (sort required))]))
                          remaining)}))
          (let [ready-set (set ready)]
            (recur
             (into (sorted-map)
                   (for [[package-id required] remaining
                         :when (not (contains? ready-set package-id))]
                     [package-id (set/difference required ready-set)]))
             (into result ready))))))))

(defn- product-record
  [plan repository-proof]
  (let [contract (:contract plan)]
    (sorted-map
     :component-package-ids (mapv :package-id (:profiles plan))
     :package-ids
     (if-let [bundle (:bundle plan)]
       [(:package-id bundle)]
       (mapv :package-id (:release-profiles plan)))
     :product-commit (:repository-commit repository-proof)
     :product-family (:product-family contract)
     :proof-ladders (mapv :id (get-in contract [:proof :ladders]))
     :repository-url (get-in contract [:publication :repository-url])
     :source-commit (get-in contract [:baseline :record :upstream :revision])
     :source-repository
     (get-in contract [:baseline :record :upstream :repository])
     :target (:target contract))))

(defn- deterministic-manifest
  [selection product-records packages]
  (let [publish-order (topological-publish-order! packages)
        ordered-packages
        (mapv
         (fn [[index package-id]]
           (assoc (get packages package-id) :publish-order index))
         (map-indexed vector publish-order))]
    (sorted-map
     :configuration "Release"
     :kind :credential-free-nuget-release-preparation
     :network-mutations []
     :package-count (count ordered-packages)
     :packages ordered-packages
     :product-count (count product-records)
     :products (vec product-records)
     :publication-credentials-accepted false
     :publish-order publish-order
     :remote-availability :not-checked
     :schema-version schema-version
     :selection selection)))

(defn- output-root!
  [^Path workspace-root selection output-root]
  (let [base (paths/absolute
              (paths/resolve-path workspace-root "target" "nuget-release"))
        expected (paths/absolute (paths/resolve-path base selection))
        output (paths/absolute (or output-root expected))]
    (when-not (and (= expected output)
                   (.startsWith output base)
                   (not= output base))
      (fail! "NuGet release artifact directory is outside its deterministic target path"
             {:expected (str expected) :actual (str output)}))
    (doseq [^Path candidate
            (take-while #(and % (.startsWith ^Path % workspace-root))
                        (iterate #(.getParent ^Path %) output))]
      (when (Files/isSymbolicLink candidate)
        (fail! "NuGet release artifact path contains a symbolic link"
               {:path (str candidate) :output (str output)})))
    output))

(defn- file-inventory
  [^Path directory]
  (into
   (sorted-map)
   (for [^Path file (regular-files directory)]
     [(str (.getFileName file)) (util/sha256-file file)])))

(defn- manifest-input
  [manifest]
  (select-keys manifest
               [:schema-version :kind :configuration :selection :products]))

(defn- existing-manifest
  [^Path output]
  (let [manifest-file (paths/resolve-path output "release-manifest.edn")]
    (when (paths/regular-file? manifest-file)
      (try
        (util/read-single-edn-string!
         (Files/readString manifest-file StandardCharsets/UTF_8))
        (catch RuntimeException error
          (throw
           (ex-info "Existing NuGet release manifest is not exact EDN"
                    {:kind :nuget-release-preparation-failed
                     :path (str manifest-file)}
                    error)))))))

(defn- publish-staging!
  [^Path staging ^Path output manifest]
  (let [expected-files (file-inventory staging)
        previous (existing-manifest output)
        identical-inputs? (and previous
                               (= (manifest-input previous)
                                  (manifest-input manifest)))]
    (if identical-inputs?
      (let [actual-files (file-inventory output)]
        (when-not (and (= previous manifest)
                       (= expected-files actual-files))
          (fail! "Repeated NuGet preparation changed identical release inputs"
                 {:expected-manifest manifest
                  :actual-manifest previous
                  :expected-files expected-files
                  :actual-files actual-files})))
      (do
        (harness/clean-directory! output)
        (doseq [^Path source (regular-files staging)]
          (Files/copy source
                      (paths/resolve-path output (str (.getFileName source)))
                      (make-array StandardCopyOption 0)))
        (let [actual-files (file-inventory output)]
          (when-not (= expected-files actual-files)
            (fail! "Deterministic NuGet artifact directory has an inexact inventory"
                   {:expected expected-files :actual actual-files})))))
    output))

(defn- publish-authorship-report!
  [staged output]
  (harness/clean-directory! output)
  (let [edn (paths/resolve-path output "product-authorship-report.edn")
        markdown (paths/resolve-path output "product-authorship-report.md")]
    (Files/copy (:edn staged) edn (make-array StandardCopyOption 0))
    (Files/copy (:markdown staged) markdown (make-array StandardCopyOption 0))
    (when-not (and (= (:edn-sha256 staged) (util/sha256-file edn))
                   (= (:markdown-sha256 staged)
                      (util/sha256-file markdown)))
      (fail! "Published authorship report differs from its verified staging bytes"
             {:output (str output)}))
    (assoc staged :edn edn :markdown markdown)))

(defn prepare!
  "Runs aggregate credential-free NuGet release preparation for one target or
  `all`. The CLI supplies only `selection`; dependency injection options exist
  for focused verification and do not add publication operations."
  ([] (prepare! {}))
  ([{:keys [workspace-root selection output-root read-target-fn proof-fn
            bundle-fn package-fn repository-proof-fn run-command!
            test-suite-report-fn]
     :or {read-target-fn target-directory/read-target
          bundle-fn nuget-package-bundle/bundle!
          proof-fn target-execution/proof!
          package-fn target-execution/package!
          repository-proof-fn product-repository/verify-synchronized!
          test-suite-report-fn authorship-report/test-suite-report!}
     :as options}]
   (let [credential-options (set/intersection forbidden-option-keys
                                              (set (keys options)))]
     (when (seq credential-options)
       (fail! "NuGet release preparation accepts no publication credential"
              {:forbidden-options (vec (sort credential-options))})))
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         selection (some-> (or selection "all") str str/trim)
         products
         (selected-products!
          (discover-products! {:workspace-root root
                               :read-target-fn read-target-fn})
          selection)
         plans (mapv production-plan! products)
         output (output-root! root selection output-root)
         staging
         (Files/createTempDirectory
          "dripsharp-nuget-release-preparation-"
          (make-array FileAttribute 0))]
     (try
       (let [{:keys [packages product-records boundary-packages]}
             (reduce
              (fn [{:keys [packages product-records boundary-packages]} plan]
                (let [contract (:contract plan)
                      target (name (:target contract))
                      invoke
                      (fn [f value]
                        (f (cond-> value
                             run-command! (assoc :run-command! run-command!))))
                      proof
                      (exact-proof!
                       contract
                       (invoke proof-fn
                               {:workspace-root root :target target}))
                      _ proof
                      initial-repository
                      (exact-repository-proof!
                       contract
                       (invoke repository-proof-fn
                               {:workspace-root root
                                :target-contract contract}))
                      prepared
                      (reduce
                       (fn [{:keys [package-records boundary-records]}
                            profile-record]
                         (let [result
                               (exact-package-result!
                                plan profile-record initial-repository
                                (invoke package-fn
                                        {:workspace-root root
                                         :target target
                                         :profile (:profile profile-record)}))
                               final-repository
                               (exact-repository-proof!
                                contract
                                (invoke repository-proof-fn
                                        {:workspace-root root
                                         :target-contract contract}))
                               _ (exact-stable-repository!
                                  initial-repository final-repository)
                               public-result
                               (if (:bundle plan)
                                 (invoke bundle-fn
                                         {:workspace-root root
                                          :plan plan
                                          :package-result result})
                                 {:packages (:packages result)})
                               boundary-records
                               (reduce merge-boundary-package!
                                       boundary-records
                                       (:packages result))]
                           (reduce
                            (fn [{:keys [package-records boundary-records]}
                                 package]
                              (let [{:keys [record artifacts]}
                                    (package-record! plan final-repository package)]
                                (doseq [artifact artifacts]
                                  (copy-artifact! staging artifact))
                                {:package-records
                                 (merge-package! package-records record)
                                 :boundary-records boundary-records}))
                            {:package-records package-records
                             :boundary-records boundary-records}
                            (:packages public-result))))
                       {:package-records packages
                        :boundary-records boundary-packages}
                       (:release-profiles plan))]
                  (when-not (= (:package-ids plan)
                               (set (for [[_ package] (:package-records prepared)
                                          :when (= (:target contract)
                                                   (:target package))]
                                      (:id package))))
                    (fail! "Prepared target package inventory is incomplete"
                           {:target (:target contract)
                            :expected (vec (sort (:package-ids plan)))
                            :actual
                            (vec
                             (sort
                              (for [[_ package] (:package-records prepared)
                                    :when (= (:target contract)
                                             (:target package))]
                                (:id package))))}))
                  {:packages (:package-records prepared)
                   :boundary-packages (:boundary-records prepared)
                   :product-records
                   (conj product-records
                         (product-record plan initial-repository))}))
              {:packages (sorted-map)
               :boundary-packages (sorted-map)
               :product-records []}
              plans)
             manifest
             (deterministic-manifest selection product-records packages)
             manifest-file (paths/resolve-path staging "release-manifest.edn")
             _ (util/write-text! manifest-file (str (pr-str manifest) "\n"))
             test-suites
             (mapv
              (fn [plan]
                (let [contract (:contract plan)
                      product
                      (first (filter #(= (:target contract) (:target %))
                                     product-records))]
                  (test-suite-report-fn root contract
                                        (:product-commit product))))
              plans)
             authorship-root (authorship-output-root root selection)
             staged-authorship-result
             (authorship-report/write-portfolio-report!
              {:workspace-root root
               :output-root (paths/resolve-path staging "authorship-report")
               :link-root authorship-root
               :products (portfolio-products! product-records
                                              boundary-packages)
               :test-suites test-suites})
             _ (publish-staging! staging output manifest)
             authorship-result
             (publish-authorship-report! staged-authorship-result
                                         authorship-root)
             published-manifest
             (paths/resolve-path output "release-manifest.edn")
             manifest-sha256 (util/sha256-file published-manifest)
             summary
             {:artifact-directory (str output)
              :manifest (str published-manifest)
              :manifest-sha256 manifest-sha256
              :authorship-report (str (:markdown authorship-result))
              :authorship-report-sha256 (:markdown-sha256 authorship-result)
              :products (:product-count manifest)
              :packages (:package-count manifest)
              :publish-order (:publish-order manifest)}]
         (println "Credential-free NuGet release preparation passed:"
                  (pr-str summary))
         {:artifact-directory output
          :manifest-file published-manifest
          :manifest-sha256 manifest-sha256
          :authorship-report authorship-result
          :manifest manifest})
       (finally
         (delete-tree! staging))))))
