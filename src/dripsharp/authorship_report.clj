(ns dripsharp.authorship-report
  "Generated release evidence for the mechanical/authored package boundary."
  (:require [clojure.string :as str]
            [dripsharp.authorship :as authorship]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files Path]
           [java.util Locale]))

(def schema-version 2)
(def portfolio-schema-version 1)

(def ^:private line-keys
  [:mechanical-lines :authored-compat-lines
   :authored-destination-runtime-lines :vendored-third-party-lines
   :authored-lines :total-lines])

(defn- fail!
  [message data]
  (throw (ex-info message
                  (assoc data :kind :invalid-authorship-boundary-report))))

(defn- repository-commit?
  [value]
  (boolean
   (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" (or value ""))))

(defn- authored?
  [entry]
  (contains? #{:authored-compat :authored-destination-runtime}
             (:class entry)))

(defn- verified-package!
  [{:keys [profile identity ledger verification] :as package}]
  (let [files (:files ledger)
        source-paths (mapv :path files)
        inventory-hash
        (util/sha256-text (str/join "\n" source-paths))
        policy (:policy ledger)]
    (when-not (and (string? profile)
                   (not (str/blank? profile))
                   (map? identity)
                   (string? (:id identity))
                   (not (str/blank? (:id identity)))
                   (string? (:version identity))
                   (not (str/blank? (:version identity)))
                   (repository-commit? (:sha256 identity))
                   (string? (:file identity))
                   (not (str/blank? (:file identity)))
                   (= authorship/schema-version (:schema-version ledger))
                   (vector? files)
                   (map? (:totals ledger))
                   (map? verification)
                   (= authorship/schema-version (:schema-version verification))
                   (= (count files) (:verified-files verification))
                   (= source-paths (:source-paths verification))
                   (= inventory-hash
                      (:source-inventory-sha256 verification))
                   (= (:totals ledger) (:totals verification))
                   (= policy (:policy verification)))
      (fail! "Boundary report input is not an exact verified authorship ledger"
             {:package (:id identity)
              :profile profile
              :ledger-schema (:schema-version ledger)
              :verification-schema (:schema-version verification)}))
    (when (and policy
               (not (and (= profile (:profile policy))
                         (= (:id identity) (:package-id policy)))))
      (fail! "Boundary report package identity differs from its verified policy"
             {:package (:id identity)
              :profile profile
              :policy
              (select-keys policy [:package-id :profile])}))
    (when (and (some authored? files)
               (not (seq (:evidence policy))))
      (fail! "Boundary report cannot publish authored files without linked proofs"
             {:package (:id identity) :profile profile}))
    package))

(defn- mechanical-provenance
  [files]
  (->> files
       (filter #(= :mechanical (:class %)))
       (group-by #(get-in % [:source :revision]))
       (map
        (fn [[revision entries]]
          (let [sources
                (->> entries
                     (map #(get-in % [:source :file]))
                     distinct
                     sort
                     vec)]
            {:revision revision
             :generated-files (count entries)
             :upstream-source-files (count sources)
             :upstream-source-inventory-sha256
             (util/sha256-text (str/join "\n" sources))})))
       (sort-by :revision)
       vec))

(defn- package-report
  [{:keys [profile identity ledger verification]}]
  (let [totals (:totals ledger)
        policy (:policy ledger)
        authored-files
        (->> (:files ledger)
             (filter authored?)
             (mapv #(select-keys
                     % [:path :class :lines :provenance :sha256])))
        third-party-files
        (->> (:files ledger)
             (filter #(= :vendored-third-party (:class %)))
             (mapv #(select-keys
                     % [:path :class :lines :provenance :sha256])))]
    {:package identity
     :target (:target policy)
     :profile profile
     :ledger-schema-version (:schema-version ledger)
     :verification
     (select-keys verification
                  [:verified-files :source-inventory-sha256 :assembly-input])
     :lines
     (select-keys totals
                  [:mechanical-lines :authored-compat-lines
                   :authored-destination-runtime-lines
                   :vendored-third-party-lines :authored-lines
                   :total-lines])
     :authored-fraction (:authored-fraction totals)
     :authored-ratio
     {:numerator (:authored-lines totals)
      :denominator (:total-lines totals)}
     :mechanical-provenance (mechanical-provenance (:files ledger))
     :authored-files authored-files
     :third-party-files third-party-files
     :linked-proofs (vec (:evidence policy))
     :review (:review policy)}))

(defn- proof-index
  [packages]
  (->> packages
       (mapcat
        (fn [{:keys [target profile package linked-proofs review]}]
          (map
           (fn [proof]
             {:id proof
              :target target
              :contract
              (when target
                (str "targets/" (name target) "/target.edn"))
              :profile profile
              :package (:id package)
              :review review})
           linked-proofs)))
       (group-by (juxt :target :id))
       (map
        (fn [[[target id] entries]]
          {:id id
           :target target
           :contract (:contract (first entries))
           :profiles (vec (sort (distinct (map :profile entries))))
           :packages (vec (sort (distinct (map :package entries))))
           :reviews (vec (sort (distinct (keep :review entries))))}))
       (sort-by (juxt (comp str :target) (comp str :id)))
       vec))

(defn build-report
  "Builds a deterministic report only from ledgers already reconciled by
  package source inspection."
  [repository-commit packages]
  (when-not (repository-commit? repository-commit)
    (fail! "Boundary report requires the exact release repository commit"
           {:repository-commit repository-commit}))
  (let [packages
        (->> packages
             (map verified-package!)
             (sort-by (juxt #(get-in % [:identity :id])
                            #(get-in % [:identity :version])))
             (mapv package-report))]
    (when-not (seq packages)
      (fail! "Boundary report requires at least one verified package" {}))
    {:schema-version schema-version
     :report :mechanical-authored-boundary
     :repository-commit repository-commit
     :derived-from
     {:authorship-ledger-schema-version authorship/schema-version
      :verification :package-source-inspection}
     :packages packages
     :proof-index (proof-index packages)}))

(defn- sum-lines
  [packages]
  (let [lines
        (into (sorted-map)
              (for [key line-keys]
                [key (reduce + 0 (map #(get-in % [:lines key] 0) packages))]))]
    (assoc lines
           :authored-fraction
           (if (pos? (:total-lines lines))
             (/ (double (:authored-lines lines))
                (double (:total-lines lines)))
             0.0))))

(defn- unique-authored-inputs
  [packages]
  (->> packages
       (mapcat
        (fn [{:keys [package target authored-files]}]
          (for [file authored-files]
            (assoc file :package (:id package) :target target))))
       (group-by (juxt :class :provenance :sha256 :lines))
       (map
        (fn [[[class provenance sha256 lines] entries]]
          {:class class
           :provenance provenance
           :sha256 sha256
           :lines lines
           :targets (vec (sort (distinct (map (comp name :target) entries))))
           :packages (vec (sort (distinct (map :package entries))))
           :emitted-files (vec (sort (distinct (map :path entries))))}))
       (sort-by (juxt (comp str :class) :provenance :sha256))
       vec))

(defn build-portfolio-report
  "Builds one deterministic cross-product report from verified package-ledger
  groups. Each product carries its own generated-product repository commit;
  shared authored inputs are deduplicated separately from the package compile
  footprint."
  [products test-suites]
  (let [products
        (mapv
         (fn [{:keys [target product-family repository-commit packages]}]
           (when-not (and (keyword? target)
                          (keyword? product-family)
                          (repository-commit? repository-commit))
             (fail! "Portfolio report product identity is incomplete"
                    {:target target
                     :product-family product-family
                     :repository-commit repository-commit}))
           (let [report (build-report repository-commit packages)
                 reported (:packages report)]
             (when-let [package
                        (first (remove #(= target (:target %)) reported))]
               (fail! "Portfolio report package belongs to another target"
                      {:product target
                       :package (get-in package [:package :id])
                       :package-target (:target package)}))
             {:target target
              :product-family product-family
              :repository-commit repository-commit
              :packages reported}))
         (sort-by (comp name :target) products))
        packages (vec (mapcat :packages products))
        package-ids (mapv #(get-in % [:package :id]) packages)
        duplicates (->> package-ids frequencies
                        (keep (fn [[id count]] (when (< 1 count) id)))
                        sort vec)
        authored-inputs (unique-authored-inputs packages)
        product-commits (into {} (map (juxt :target :repository-commit)) products)
        test-suites
        (mapv
         (fn [{:keys [target repository-commit authored-files authored-lines]
               :as suite}]
           (let [valid-files?
                 (and (vector? authored-files)
                      (every? #(and (string? (:path %))
                                    (nat-int? (:lines %))
                                    (repository-commit? (:sha256 %))
                                    (keyword? (:role %)))
                              authored-files))]
             (when-not (and (= (get product-commits target)
                               repository-commit)
                            valid-files?
                            (nat-int? authored-lines)
                            (= authored-lines
                               (reduce + 0 (map :lines authored-files))))
               (fail! "Portfolio test-suite evidence is incomplete or belongs to another product commit"
                      {:target target
                       :repository-commit repository-commit
                       :expected-commit (get product-commits target)})))
           suite)
         (sort-by (comp name :target) test-suites))]
    (when-not (seq products)
      (fail! "Portfolio report requires at least one product" {}))
    (when (seq duplicates)
      (fail! "Portfolio report contains duplicate package identities"
             {:duplicates duplicates}))
    (when-not (= (set (keys product-commits)) (set (map :target test-suites)))
      (fail! "Portfolio report test-suite coverage differs from its product set"
             {:products (vec (sort (keys product-commits)))
              :test-suites (vec (sort (map :target test-suites)))}))
    {:schema-version portfolio-schema-version
     :report :dripsharp-product-authorship
     :derived-from
     {:authorship-ledger-schema-version authorship/schema-version
      :verification :package-source-inspection
      :authored-meaning :dripsharp-non-mechanical-source}
     :products products
     :package-footprint (sum-lines packages)
     :unique-authored-inputs authored-inputs
     :unique-authored-lines (reduce + 0 (map :lines authored-inputs))
     :test-suites test-suites
     :proof-index (proof-index packages)}))

(defn- tsv-rows!
  [file expected-columns]
  (when-not (paths/regular-file? file)
    (fail! "Generated test authorship evidence is missing"
           {:path (str file)}))
  (let [[header & rows] (str/split-lines (Files/readString file))
        columns (str/split header #"\t" -1)]
    (when-not (= expected-columns columns)
      (fail! "Generated test authorship evidence has an unknown schema"
             {:path (str file) :expected expected-columns :actual columns}))
    (mapv
     (fn [line]
       (let [values (str/split line #"\t" -1)]
         (when-not (= (count columns) (count values))
           (fail! "Generated test authorship evidence has a malformed row"
                  {:path (str file) :line line}))
         (zipmap columns values)))
     rows)))

(defn- source-lines
  [file]
  (count (str/split-lines (Files/readString file))))

(defn- source-record!
  [workspace-root target-directory relative role expected-sha256]
  (let [file (paths/resolve-path target-directory relative)
        sha256 (when (paths/regular-file? file) (util/sha256-file file))]
    (when-not (and sha256
                   (or (nil? expected-sha256) (= expected-sha256 sha256)))
      (fail! "Authored test input is missing or changed"
             {:path (str file)
              :expected expected-sha256
              :actual sha256}))
    {:path (util/portable-path workspace-root file)
     :lines (source-lines file)
     :sha256 sha256
     :role role}))

(defn- configured-authored-test-inputs!
  [workspace-root contract]
  (let [target-directory (:target-directory contract)
        focused
        (filter #(= :focused-consumer (:kind %))
                (get-in contract [:publication :test-suites :strategies]))
        configured
        (mapcat
         (fn [strategy]
           (concat
            (for [[_ {:keys [source sha256]}] (:profile-tests strategy)]
              (source-record! workspace-root target-directory source
                              :authored-focused-consumer sha256))
            (for [{:keys [source sha256]} (:fixtures strategy)]
              (source-record! workspace-root target-directory source
                              :authored-test-fixture sha256))))
         focused)
        adapted-directory (paths/resolve-path target-directory "adapted-tests")
        adapted
        (if-not (paths/directory? adapted-directory)
          []
          (with-open [entries (Files/list adapted-directory)]
            (->> (.toArray entries)
                 (map #(cast Path %))
                 (filter paths/regular-file?)
                 (filter #(str/ends-with? (str/lower-case (str %)) ".cs"))
                 (sort-by str)
                 (mapv
                  (fn [file]
                    {:path (util/portable-path workspace-root file)
                     :lines (source-lines file)
                     :sha256 (util/sha256-file file)
                     :role :authored-adapted-test-support})))))]
    (->> (concat configured adapted)
         (sort-by :path)
         vec)))

(defn- brine-test-suite-report!
  [workspace-root contract repository-commit product-root]
  (let [tests-root (paths/resolve-path product-root "tests")
        authorship-file (paths/resolve-path tests-root "TEST-AUTHORSHIP.tsv")
        provenance-file (paths/resolve-path tests-root "TEST-PROVENANCE.tsv")
        authorship-rows
        (tsv-rows!
         authorship-file
         ["source-path" "sha256" "lines" "line-budget" "review-evidence"
          "role"])
        provenance-rows
        (tsv-rows!
         provenance-file
         ["path" "class" "upstream-revision" "source-path" "source-sha256"
          "transformation" "emitted-sha256" "license" "notice"
          "durable-source" "authored-lines" "review-evidence" "line-budget"])
        contract-files
        ["Contracts/LanguageSnippetContract.tsv"
         "Contracts/PklCoreTestContract.tsv"
         "Contracts/PklParserTestContract.tsv"]
        cases
        (reduce
         + 0
         (for [relative contract-files
               :let [file (paths/resolve-path tests-root relative)]]
           (do
             (when-not (paths/regular-file? file)
               (fail! "Brine adapted test contract is missing"
                      {:path (str file)}))
             (max 0 (dec (count (str/split-lines (Files/readString file))))))))
        revisions
        (->> provenance-rows
             (keep #(let [value (get % "upstream-revision")]
                      (when-not (= "-" value) value)))
             distinct sort vec)
        authored-files
        (mapv
         (fn [row]
           {:path (get row "source-path")
            :lines (parse-long (get row "lines"))
            :sha256 (get row "sha256")
            :role (keyword (get row "role"))})
         authorship-rows)]
    {:target (:target contract)
     :product-family (:product-family contract)
     :repository-commit repository-commit
     :upstream-revision (when (= 1 (count revisions)) (first revisions))
     :mechanical-upstream-inputs
     (count (filter #(= "mechanically-upstream-derived" (get % "class"))
                    provenance-rows))
     :cases cases
     :fixtures
     (count (filter #(= "vendored-third-party" (get % "class"))
                    provenance-rows))
     :authored-files authored-files
     :authored-lines (reduce + 0 (map :lines authored-files))
     :evidence (vec (sort (distinct (map #(get % "review-evidence")
                                         authorship-rows))))
     :ledger-hashes
     {:authorship (util/sha256-file authorship-file)
      :provenance (util/sha256-file provenance-file)}}))

(defn- adapted-java-test-suite-report!
  [workspace-root contract repository-commit product-root]
  (let [project (first (get-in contract [:publication :test-suites :projects]))
        project-root (paths/resolve-path product-root (:directory project))
        inventory-file (paths/resolve-path project-root "JAVA-TEST-INVENTORY.edn")
        provenance-file (paths/resolve-path project-root "JAVA-TEST-PROVENANCE.tsv")
        inventory
        (when (paths/regular-file? inventory-file)
          (util/read-single-edn-string! (Files/readString inventory-file)))
        _ (when-not (and (= 1 (:schema-version inventory))
                         (= (:target contract) (:target inventory)))
            (fail! "Generated adapted test inventory is missing or inconsistent"
                   {:target (:target contract) :path (str inventory-file)}))
        totals (:totals inventory)
        accounting (:accounting inventory)
        source-count (or (:source-count inventory)
                         (:source-count totals)
                         (count (:sources accounting)))
        fixture-count (or (:fixture-count inventory)
                          (:fixture-count totals)
                          (count (:fixtures accounting)))
        case-count (or (:case-count inventory)
                       (:case-count totals)
                       (count (get-in accounting [:plan :cases])))
        authored-files
        (configured-authored-test-inputs! workspace-root contract)]
    (when-not (paths/regular-file? provenance-file)
      (fail! "Generated adapted test provenance is missing"
             {:target (:target contract) :path (str provenance-file)}))
    {:target (:target contract)
     :product-family (:product-family contract)
     :repository-commit repository-commit
     :upstream-revision (:revision inventory)
     :mechanical-upstream-inputs source-count
     :cases case-count
     :fixtures fixture-count
     :authored-files authored-files
     :authored-lines (reduce + 0 (map :lines authored-files))
     :evidence (mapv :id (get-in contract [:proof :ladders]))
     :ledger-hashes
     {:inventory (util/sha256-file inventory-file)
      :provenance (util/sha256-file provenance-file)}}))

(defn test-suite-report!
  "Builds the separate generated-test boundary from synchronized product
  ledgers and durable authored test inputs."
  [workspace-root contract repository-commit]
  (let [workspace-root (paths/absolute workspace-root)
        product-root
        (paths/resolve-path workspace-root
                            (get-in contract [:publication :submodule-path]))]
    (if (= :pkl (:target contract))
      (brine-test-suite-report! workspace-root contract repository-commit
                                product-root)
      (adapted-java-test-suite-report! workspace-root contract
                                       repository-commit product-root))))

(defn- table-text
  [value]
  (-> (str value)
      (str/replace "\\" "\\\\")
      (str/replace "|" "\\|")
      (str/replace #"\s+" " ")))

(defn- code
  [value]
  (str "`" (table-text value) "`"))

(defn- percentage
  [value]
  (String/format Locale/ROOT "%.6f%%"
                 (to-array [(* 100.0 (double value))])))

(defn- proof-anchor
  [target id]
  (-> (str "proof-" (some-> target name) "-" (name id))
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- proof-links
  [{:keys [target linked-proofs]}]
  (if (seq linked-proofs)
    (str/join
     ", "
     (map
      #(str "[" (code %) "](#" (proof-anchor target %) ")")
      linked-proofs))
    "—"))

(defn- relative-contract-link
  [^Path workspace-root ^Path output-root contract]
  (when contract
    (util/portable-path
     output-root
     (paths/resolve-path workspace-root contract))))

(defn- render-package
  [{:keys [package profile lines authored-fraction authored-ratio
           verification mechanical-provenance authored-files linked-proofs
           third-party-files target]}]
  (str
   "## " (:id package) "\n\n"
   "- Profile: " (code profile) "\n"
   "- Artifact: " (code (:file package)) " (`SHA-256 "
   (:sha256 package) "`)\n"
   "- Verified source inventory: "
   (get verification :verified-files) " files (`SHA-256 "
   (:source-inventory-sha256 verification) "`)\n"
   "- Exact authored ratio: "
   (get authored-ratio :numerator) " / "
   (get authored-ratio :denominator) "\n"
   "- Linked proofs: "
   (proof-links {:target target :linked-proofs linked-proofs}) "\n\n"
   "### Lines by class\n\n"
   "| Class | Lines |\n"
   "| --- | ---: |\n"
   "| Mechanical | " (:mechanical-lines lines) " |\n"
   "| Authored compatibility | " (:authored-compat-lines lines) " |\n"
   "| Authored destination runtime | "
   (:authored-destination-runtime-lines lines) " |\n"
   "| Vendored third-party | "
   (:vendored-third-party-lines lines) " |\n"
   "| **Authored total** | **" (:authored-lines lines) "** |\n"
   "| **Package total** | **" (:total-lines lines) "** |\n"
   "| **Authored fraction** | **" (percentage authored-fraction) "** |\n\n"
   "### Mechanical provenance\n\n"
   (if (seq mechanical-provenance)
     (str
      "| Upstream revision | Generated files | Upstream source files | "
      "Source inventory SHA-256 |\n"
      "| --- | ---: | ---: | --- |\n"
      (apply
       str
       (map
        (fn [{:keys [revision generated-files upstream-source-files
                     upstream-source-inventory-sha256]}]
          (str "| " (code revision)
               " | " generated-files
               " | " upstream-source-files
               " | " (code upstream-source-inventory-sha256) " |\n"))
        mechanical-provenance)))
     "_No mechanically translated source files._\n")
   "\n### Authored files\n\n"
   (if (seq authored-files)
     (str
      "| Class | Emitted file | Lines | Durable provenance | SHA-256 |\n"
      "| --- | --- | ---: | --- | --- |\n"
      (apply
       str
       (map
        (fn [{:keys [class path lines provenance sha256]}]
          (str "| " (code class)
               " | " (code path)
               " | " lines
               " | " (code provenance)
               " | " (code sha256) " |\n"))
        authored-files)))
     "_No authored source files._\n")
   "\n### Vendored third-party files\n\n"
   (if (seq third-party-files)
     (str
      "| Emitted file | Lines | Pinned provenance | SHA-256 |\n"
      "| --- | ---: | --- | --- |\n"
      (apply
       str
       (map
        (fn [{:keys [path lines provenance sha256]}]
          (str "| " (code path)
               " | " lines
               " | " (code provenance)
               " | " (code sha256) " |\n"))
        third-party-files)))
     "_No vendored third-party source files._\n")
   "\n"))

(defn render-markdown
  "Renders the human-readable companion for a built report."
  [workspace-root output-root report]
  (let [workspace-root (paths/absolute workspace-root)
        output-root (paths/absolute output-root)]
    (str
     "# Mechanical / Authored Boundary Release Evidence\n\n"
     "Generated from package source inspection of authorship-ledger schema "
     (get-in report [:derived-from :authorship-ledger-schema-version])
     " at repository commit " (code (:repository-commit report)) ". "
     "This bounded release report does not alter target scope, exclusions, "
     "or project-completion contracts.\n\n"
     "## Package summary\n\n"
     "| Package | Mechanical lines | Authored compatibility | "
     "Authored destination | Vendored third-party | Authored total | Total lines | "
     "Authored fraction | Linked proofs |\n"
     "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n"
     (apply
      str
      (map
       (fn [{:keys [package lines authored-fraction] :as package-report}]
         (str "| " (code (:id package))
              " | " (:mechanical-lines lines)
              " | " (:authored-compat-lines lines)
              " | " (:authored-destination-runtime-lines lines)
              " | " (:vendored-third-party-lines lines)
              " | " (:authored-lines lines)
              " | " (:total-lines lines)
              " | " (percentage authored-fraction)
              " | " (proof-links package-report) " |\n"))
       (:packages report)))
     "\n"
     (apply str (map render-package (:packages report)))
     "## Linked proof index\n\n"
     (if (seq (:proof-index report))
       (str
        "| Proof | Target | Contract | Profiles | Packages | Reviews |\n"
        "| --- | --- | --- | --- | --- | --- |\n"
        (apply
         str
         (map
          (fn [{:keys [id target contract profiles packages reviews]}]
            (str "<a id=\"" (proof-anchor target id) "\"></a>"
                 "| " (code id)
                 " | " (code target)
                 " | [" (code contract) "]("
                 (relative-contract-link workspace-root output-root contract)
                 ")"
                 " | " (str/join ", " (map code profiles))
                 " | " (str/join ", " (map code packages))
                 " | " (str/join ", " (map code reviews))
                 " |\n"))
          (:proof-index report))))
       "_No authored proof links were required by these package ledgers._\n"))))

(defn- render-portfolio-package-row
  [{:keys [package target profile lines authored-fraction]}]
  (str "| " (code target)
       " | " (code (:id package))
       " | " (code profile)
       " | " (:mechanical-lines lines)
       " | " (:authored-compat-lines lines)
       " | " (:authored-destination-runtime-lines lines)
       " | " (:vendored-third-party-lines lines)
       " | " (:authored-lines lines)
       " | " (:total-lines lines)
       " | " (percentage authored-fraction) " |\n"))

(defn- render-test-suite
  [{:keys [target product-family repository-commit upstream-revision
           mechanical-upstream-inputs cases fixtures authored-files
           authored-lines evidence]}]
  (str
   "### " (code product-family) " tests\n\n"
   "- Target: " (code target) "\n"
   "- Product commit: " (code repository-commit) "\n"
   "- Upstream revision: " (if upstream-revision
                             (code upstream-revision) "—") "\n"
   "- Mechanically adapted upstream test inputs: "
   (or mechanical-upstream-inputs 0) "\n"
   "- Adapted test cases: " (or cases 0) "\n"
   "- Governed fixtures: " (or fixtures 0) "\n"
   "- Explicit authored test inputs: " (count authored-files)
   " files, " (or authored-lines 0) " lines\n"
   "- Evidence: " (if (seq evidence)
                    (str/join ", " (map code evidence)) "—") "\n\n"
   (if (seq authored-files)
     (str
      "| Durable authored test input | Lines | SHA-256 | Role |\n"
      "| --- | ---: | --- | --- |\n"
      (apply
       str
       (map
        (fn [{:keys [path lines sha256 role]}]
          (str "| " (code path)
               " | " lines
               " | " (code sha256)
               " | " (code role) " |\n"))
        authored-files)))
     "_No explicit authored test inputs were declared._\n")
   "\n"))

(defn render-portfolio-markdown
  "Renders the cross-product human-readable report. Package totals describe
  compiled package footprint and therefore count a shared compatibility input
  once for every package that compiles it; the unique-input section removes
  that duplication."
  [workspace-root output-root report]
  (let [workspace-root (paths/absolute workspace-root)
        output-root (paths/absolute output-root)
        packages (vec (mapcat :packages (:products report)))
        footprint (:package-footprint report)]
    (str
     "# DripSharp Product Authorship Report\n\n"
     "This report compares mechanically translated production code with "
     "explicit DripSharp-authored adaptation code across the proved product "
     "set. **Authored** means reviewed, non-mechanical DripSharp source; it "
     "does not distinguish LLM typing from human typing. Systematic changes "
     "made by deterministic translation rules remain mechanical because every "
     "emitted declaration retains upstream source-map provenance.\n\n"
     "Production figures come from package source inspection of authorship-ledger "
     "schema "
     (get-in report [:derived-from :authorship-ledger-schema-version])
     ". Test figures are reported separately and never enter production-line "
     "percentages.\n\n"
     "## Production package summary\n\n"
     "| Target | Package | Profile | Mechanical | Authored compatibility | "
     "Authored destination | Vendored third-party | Authored total | Total | "
     "Authored fraction |\n"
     "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"
     (apply str (map render-portfolio-package-row packages))
     "\nThe package-footprint total is **"
     (:mechanical-lines footprint) " mechanical lines** and **"
     (:authored-lines footprint) " authored lines** across "
     (:total-lines footprint) " compiled lines ("
     (percentage (:authored-fraction footprint))
     " authored). Shared compatibility files are counted in each package that "
     "compiles them.\n\n"
     "## Unique authored source inputs\n\n"
     "The deduplicated durable authored inventory contains **"
     (:unique-authored-lines report) " lines** across "
     (count (:unique-authored-inputs report)) " source inputs.\n\n"
     "| Class | Durable source | Lines | Targets | Packages | SHA-256 |\n"
     "| --- | --- | ---: | --- | --- | --- |\n"
     (apply
      str
      (map
       (fn [{:keys [class provenance lines targets packages sha256]}]
         (str "| " (code class)
              " | " (code provenance)
              " | " lines
              " | " (str/join ", " (map code targets))
              " | " (str/join ", " (map code packages))
              " | " (code sha256) " |\n"))
       (:unique-authored-inputs report)))
     "\n## Generated test-suite boundary\n\n"
     (if (seq (:test-suites report))
       (apply str (map render-test-suite (:test-suites report)))
       "_No generated test-suite evidence was supplied._\n\n")
     "## Package details\n\n"
     (apply str (map render-package packages))
     "## Linked proof index\n\n"
     (if (seq (:proof-index report))
       (str
        "| Proof | Target | Contract | Profiles | Packages | Reviews |\n"
        "| --- | --- | --- | --- | --- | --- |\n"
        (apply
         str
         (map
          (fn [{:keys [id target contract profiles packages reviews]}]
            (str "<a id=\"" (proof-anchor target id) "\"></a>"
                 "| " (code id)
                 " | " (code target)
                 " | [" (code contract) "]("
                 (relative-contract-link workspace-root output-root contract)
                 ")"
                 " | " (str/join ", " (map code profiles))
                 " | " (str/join ", " (map code packages))
                 " | " (str/join ", " (map code reviews))
                 " |\n"))
          (:proof-index report))))
       "_No authored proof links were required._\n"))))

(defn write-report!
  "Writes deterministic EDN and Markdown release evidence beside a package
  proof and returns their exact paths and hashes."
  [{:keys [workspace-root output-root repository-commit packages]}]
  (let [workspace-root (paths/absolute workspace-root)
        output-root (paths/absolute output-root)
        report (build-report repository-commit packages)
        edn-file (paths/resolve-path output-root
                                     "mechanical-authored-boundary.edn")
        markdown-file (paths/resolve-path
                       output-root "mechanical-authored-boundary.md")]
    (util/write-text! edn-file (str (pr-str report) "\n"))
    (util/write-text!
     markdown-file
     (render-markdown workspace-root output-root report))
    {:schema-version schema-version
     :edn edn-file
     :markdown markdown-file
     :edn-sha256 (util/sha256-file edn-file)
     :markdown-sha256 (util/sha256-file markdown-file)
     :report report}))

(defn write-portfolio-report!
  "Writes the consolidated EDN and Markdown product report."
  [{:keys [workspace-root output-root link-root products test-suites]}]
  (let [workspace-root (paths/absolute workspace-root)
        output-root (paths/absolute output-root)
        link-root (paths/absolute (or link-root output-root))
        report (build-portfolio-report products test-suites)
        edn-file (paths/resolve-path output-root
                                     "product-authorship-report.edn")
        markdown-file (paths/resolve-path output-root
                                          "product-authorship-report.md")]
    (util/write-text! edn-file (str (pr-str report) "\n"))
    (util/write-text!
     markdown-file
     (render-portfolio-markdown workspace-root link-root report))
    {:schema-version portfolio-schema-version
     :edn edn-file
     :markdown markdown-file
     :edn-sha256 (util/sha256-file edn-file)
     :markdown-sha256 (util/sha256-file markdown-file)
     :report report}))
