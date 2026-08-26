(ns dripsharp.authorship-portfolio
  "Cross-product reporting over existing local proof and package evidence.

  This namespace does not prepare or publish a release. It runs each selected
  product's complete proof, obtains the dependency-closed package evidence
  already produced by the shared packaging path, and writes only the
  consolidated authorship report."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.authorship-report :as authorship-report]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.target-execution :as target-execution]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def ^:private selection-pattern #"[a-z][a-z0-9-]*")

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :authorship-portfolio-failed))))

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
                      {:kind :authorship-portfolio-failed
                       :path (str manifest-file)}
                      error))))
        directory-id (str (.getFileName directory))]
    (when (= :generated-repository (get-in manifest [:publication :kind]))
      (when-not (= (keyword directory-id) (:target manifest))
        (fail! "Generated-product target directory and manifest identity disagree"
               {:directory directory-id :target (:target manifest)}))
      directory-id)))

(defn discover-products!
  "Discovers generated products with package metadata from direct target
  contracts. Conformance-only targets are not authorship portfolio inputs."
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
      (fail! "Authorship portfolio selection is invalid"
             {:selection selection
              :available (into ["all"] (sort (keys by-id)))}))
    (if (= "all" selection)
      (if (seq products)
        products
        (fail! "No packaged generated products were discovered"
               {:selection selection}))
      (if-let [product (get by-id selection)]
        [product]
        (fail! "Authorship portfolio selected an unavailable product target"
               {:selection selection
                :available (into ["all"] (sort (keys by-id)))})))))

(defn- package-profile!
  [contract profile-id]
  (let [profile (get-in contract [:profiles profile-id])
        package-id
        (get-in profile [:destination :configuration :package :id])
        dependencies
        (set (get-in profile [:configuration :dependency-profiles]))]
    (when-not (and profile
                   (string? package-id)
                   (not (str/blank? package-id)))
      (fail! "Packaged product profile has no package identity"
             {:target (:target contract) :profile profile-id}))
    {:profile profile-id
     :package-id package-id
     :dependencies dependencies}))

(defn- dependency-closure
  [profiles roots]
  (loop [pending (vec roots) result #{}]
    (if-let [profile (peek pending)]
      (if (contains? result profile)
        (recur (pop pending) result)
        (let [record
              (or (get profiles profile)
                  (fail! "Packaged product profile dependency is missing"
                         {:profile profile}))]
          (recur (into (pop pending) (:dependencies record))
                 (conj result profile))))
      result)))

(defn- product-plan!
  [contract]
  (let [profile-ids
        (set (keys (get-in contract [:publication :profile-projects])))
        profiles
        (into {}
              (map (fn [profile-id]
                     [profile-id (package-profile! contract profile-id)]))
              profile-ids)
        package-ids (set (map :package-id (vals profiles)))
        catalog-ids
        (set (keys (get-in contract [:publication :nuget :packages])))
        dependencies (into #{} (mapcat :dependencies) (vals profiles))
        missing-dependencies (set/difference dependencies profile-ids)
        root-profiles (vec (sort (set/difference profile-ids dependencies)))
        covered-profiles (dependency-closure profiles root-profiles)]
    (when-not (= (count package-ids) (count profile-ids))
      (fail! "Packaged product profiles contain duplicate package identities"
             {:target (:target contract)}))
    (when-not (= package-ids catalog-ids)
      (fail! "Package catalog and product profiles disagree"
             {:target (:target contract)
              :catalog (vec (sort catalog-ids))
              :profiles (vec (sort package-ids))}))
    (when (seq missing-dependencies)
      (fail! "Packaged product depends on an unpublished profile"
             {:target (:target contract)
              :profiles (vec (sort missing-dependencies))}))
    (when-not (= profile-ids covered-profiles)
      (fail! "Packaged product profile graph is cyclic or incomplete"
             {:target (:target contract)
              :roots root-profiles
              :covered (vec (sort covered-profiles))
              :expected (vec (sort profile-ids))}))
    {:contract contract
     :package-ids package-ids
     :root-profiles root-profiles}))

(defn- boundary-package
  [package]
  {:profile (:profile package)
   :identity (:identity package)
   :ledger (:authorship package)
   :verification (get-in package [:inspection :authorship])})

(defn- merge-package!
  [packages package]
  (let [package-id (get-in package [:identity :id])
        record (boundary-package package)]
    (when-not (and (string? package-id) (not (str/blank? package-id)))
      (fail! "Package evidence has no identity" {:package package}))
    (if-let [existing (get packages package-id)]
      (if (= existing record)
        packages
        (fail! "Repeated package closures produced inconsistent authorship evidence"
               {:package package-id}))
      (assoc packages package-id record))))

(defn- product-evidence!
  [root plan proof-fn package-fn test-suite-report-fn run-command!]
  (let [contract (:contract plan)
        target (name (:target contract))
        invoke
        (fn [f options]
          (f (cond-> options
               run-command! (assoc :run-command! run-command!))))
        _ (invoke proof-fn {:workspace-root root :target target})
        package-results
        (mapv
         (fn [profile]
           (invoke package-fn
                   {:workspace-root root :target target :profile profile}))
         (:root-profiles plan))
        commits (set (map #(get-in % [:packing-summary :repository-commit])
                          package-results))
        packages (reduce merge-package! (sorted-map)
                         (mapcat :packages package-results))
        actual-package-ids (set (keys packages))]
    (when-not (= 1 (count commits))
      (fail! "Product package evidence does not identify one repository commit"
             {:target (:target contract) :commits (vec (sort commits))}))
    (when-not (= (:package-ids plan) actual-package-ids)
      (fail! "Product package evidence is incomplete"
             {:target (:target contract)
              :expected (vec (sort (:package-ids plan)))
              :actual (vec (sort actual-package-ids))}))
    (let [repository-commit (first commits)]
      {:product
       {:target (:target contract)
        :product-family (:product-family contract)
        :repository-commit repository-commit
        :packages (mapv packages (sort actual-package-ids))}
       :test-suite
       (test-suite-report-fn root contract repository-commit)})))

(defn- report-output-root!
  [root selection]
  (let [base (paths/absolute
              (paths/resolve-path root "target" "authorship-report"))
        output (paths/absolute (paths/resolve-path base selection))]
    (when-not (and (.startsWith output base) (not= output base))
      (fail! "Authorship report directory is outside its target path"
             {:output (str output)}))
    (doseq [^Path candidate
            (take-while #(and % (.startsWith ^Path % root))
                        (iterate #(.getParent ^Path %) output))]
      (when (Files/isSymbolicLink candidate)
        (fail! "Authorship report path contains a symbolic link"
               {:path (str candidate) :output (str output)})))
    (harness/clean-directory! output)))

(defn write!
  "Runs complete local product proofs and writes one authorship portfolio for
  a selected packaged product or `all`. No release artifact or publication
  operation is produced."
  ([] (write! {}))
  ([{:keys [workspace-root selection read-target-fn proof-fn package-fn
            test-suite-report-fn write-report-fn run-command!]
     :or {read-target-fn target-directory/read-target
          proof-fn target-execution/proof!
          package-fn target-execution/package!
          test-suite-report-fn authorship-report/test-suite-report!
          write-report-fn authorship-report/write-portfolio-report!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         selection (some-> (or selection "all") str str/trim)
         products
         (selected-products!
          (discover-products! {:workspace-root root
                               :read-target-fn read-target-fn})
          selection)
         evidence
         (mapv #(product-evidence! root (product-plan! %)
                                   proof-fn package-fn
                                   test-suite-report-fn run-command!)
               products)
         output (report-output-root! root selection)
         report
         (write-report-fn
          {:workspace-root root
           :output-root output
           :link-root output
           :products (mapv :product evidence)
           :test-suites (mapv :test-suite evidence)})
         summary
         {:output (str output)
          :products (count evidence)
          :packages (reduce + 0 (map #(count (get-in % [:product :packages]))
                                     evidence))}]
     (println "Product authorship portfolio passed:" (pr-str summary))
     (assoc report :selection selection :summary summary))))
