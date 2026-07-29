(ns dripsharp.authorship-report
  "Generated release evidence for the mechanical/authored package boundary."
  (:require [clojure.string :as str]
            [dripsharp.authorship :as authorship]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Path]
           [java.util Locale]))

(def schema-version 2)

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
