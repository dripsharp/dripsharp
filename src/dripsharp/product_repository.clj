(ns dripsharp.product-repository
  "Fail-closed local synchronization for generated product repositories.

  Generation and proof remain in target/generated. This namespace validates an
  already-loaded target publication contract, copies only declared managed
  paths into the intended clean product submodule, and can prepare a local
  product commit plus parent gitlink update. It never creates repositories,
  pushes branches, or opens pull requests."
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.nio.file CopyOption FileVisitOption Files LinkOption Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def ^:private generated-publication-keys
  #{:kind :repository-slug :repository-url :default-branch
    :submodule-path :staging-path :profile-projects :managed-paths
    :excluded-paths :test-suites :nuget :publication-mode})

(defn- fail!
  [message data]
  (throw (ex-info message
                  (assoc data :kind :product-repository-publication-failed))))

(defn- portable
  [value]
  (str/replace (str value) "\\" "/"))

(defn- relative-components
  [value]
  (when (and (string? value)
             (not (str/blank? value))
             (not (str/includes? value "\\"))
             (not (str/starts-with? value "/"))
             (not (re-find #"^[A-Za-z]:" value)))
    (let [components (str/split value #"/" -1)]
      (when (every? #(and (not (str/blank? %))
                          (not (contains? #{"." ".."} %)))
                    components)
        components))))

(defn- relative-path!
  [subject value]
  (or (relative-components value)
      (fail! (str subject " must be a normalized portable relative path")
             {:reason :invalid-relative-path
              :subject subject
              :path value}))
  value)

(defn- relative-under?
  [root candidate]
  (or (= root candidate)
      (str/starts-with? candidate (str root "/"))))

(defn- excluded-path?
  [excluded-paths candidate]
  (some #(relative-under? % candidate) excluded-paths))

(defn- generated-publication!
  [target-contract]
  (let [publication (:publication target-contract)]
    (when-not (= :generated-repository (:kind publication))
      (fail! "Target does not publish a generated product repository"
             {:reason :conformance-only-target
              :target (:target target-contract)
              :publication-kind (:kind publication)}))
    publication))

(defn- resolve-target-contract
  [{:keys [workspace-root target target-contract read-target-fn]
    :or {read-target-fn target-directory/read-target}}]
  (or target-contract
      (when target
        (read-target-fn workspace-root target))
      (fail! "Product repository workflow requires an explicit target"
             {:reason :missing-target-selection})))

(defn- exact-runtime-publication!
  [target-contract]
  (let [publication (generated-publication! target-contract)
        family (:product-family target-contract)
        product-id (some-> family name)
        expected
        {:repository-slug (str "dripsharp/" product-id)
         :repository-url
         (str "https://github.com/dripsharp/" product-id ".git")
         :default-branch "master"
         :submodule-path (str "products/" product-id)
         :staging-path (str "target/generated/" product-id)
         :publication-mode :pull-request}]
    (when-not (= generated-publication-keys
                 (set (keys publication)))
      (fail! "Generated product publication must use its exact metadata variant"
             {:reason :invalid-publication-keys
              :expected (vec (sort generated-publication-keys))
              :actual (vec (sort (keys publication)))}))
    (doseq [[field value] expected]
      (when-not (= value (get publication field))
        (fail! "Generated product publication identity is not canonical"
               {:reason :publication-identity-mismatch
                :target (:target target-contract)
                :field field
                :expected value
                :actual (get publication field)})))
    (let [managed-paths (:managed-paths publication)
          excluded-paths (:excluded-paths publication)
          profile-projects (:profile-projects publication)
          test-suites (:test-suites publication)]
      (when-not (and (vector? managed-paths)
                     (seq managed-paths)
                     (= (count managed-paths)
                        (count (distinct managed-paths))))
        (fail! "Generated product publication has invalid managed paths"
               {:reason :invalid-managed-paths
                :managed-paths managed-paths}))
      (doseq [managed-path managed-paths]
        (relative-path! "Managed publication path" managed-path)
        (when-not (= 1 (count (relative-components managed-path)))
          (fail! "Managed publication path must be top-level"
                 {:reason :nested-managed-path
                  :path managed-path})))
      (when-not (and (vector? excluded-paths)
                     (= (count excluded-paths)
                        (count (distinct excluded-paths))))
        (fail! "Generated product publication has invalid excluded paths"
               {:reason :invalid-excluded-paths
                :excluded-paths excluded-paths}))
      (doseq [excluded-path excluded-paths]
        (relative-path! "Excluded publication path" excluded-path)
        (when-not
         (some #(and (not= % excluded-path)
                     (relative-under? % excluded-path))
               managed-paths)
          (fail! "Excluded publication path must be nested under a managed path"
                 {:reason :unmanaged-excluded-path
                  :path excluded-path
                  :managed-paths managed-paths})))
      (when-not (and (map? profile-projects)
                     (seq profile-projects))
        (fail! "Generated product publication has no profile project mapping"
               {:reason :invalid-profile-projects
                :profile-projects profile-projects}))
      (doseq [[profile project-path] profile-projects]
        (relative-path! "Published profile project path" project-path)
        (when-not
         (some #(or (= % project-path)
                    (str/starts-with? project-path (str % "/")))
               managed-paths)
          (fail! "Published profile project is outside managed paths"
                 {:reason :unmanaged-profile-project
                  :profile profile
                  :path project-path})))
      (when-not (and (some #{"tests"} managed-paths)
                     (map? test-suites))
        (fail! "Generated product publication has no managed test suites"
               {:reason :invalid-consumer-tests
                :managed-paths managed-paths
                :test-suites test-suites})))
    publication))

(defn- command-result
  [run-command! directory command]
  (run-command! {:command command :directory directory}))

(defn- command-output
  [run-command! directory command]
  (str/trim (:output (command-result run-command! directory command))))

(defn- nul-paths
  [output]
  (->> (str/split (or output "") #"\u0000" -1)
       (remove str/blank?)
       vec))

(defn- git-nul-paths
  [run-command! directory command]
  (nul-paths (:output (command-result run-command! directory command))))

(defn- existing-no-follow?
  [path]
  (Files/exists (paths/path path) no-follow))

(defn- directory-no-follow?
  [path]
  (Files/isDirectory (paths/path path) no-follow))

(defn- regular-file-no-follow?
  [path]
  (Files/isRegularFile (paths/path path) no-follow))

(defn- symbolic-link?
  [path]
  (Files/isSymbolicLink (paths/path path)))

(defn- safe-contained-path!
  [root relative subject]
  (relative-path! subject relative)
  (let [root (paths/absolute root)
        value (paths/absolute (paths/resolve-path root relative))]
    (when-not (.startsWith value root)
      (fail! (str subject " escapes its declared root")
             {:reason :path-escape
              :subject subject
              :root (str root)
              :path relative}))
    (loop [candidate value]
      (when (and candidate (.startsWith candidate root))
        (when (symbolic-link? candidate)
          (fail! (str subject " traverses a symbolic link")
                 {:reason :symbolic-link
                  :subject subject
                  :root (str root)
                  :path (str candidate)}))
        (when-not (= candidate root)
          (recur (.getParent candidate)))))
    value))

(defn- walk-entries
  [root subject]
  (let [root (paths/absolute root)]
    (when-not (directory-no-follow? root)
      (fail! (str subject " is missing or is not a directory")
             {:reason :missing-directory
              :subject subject
              :path (str root)}))
    (when (symbolic-link? root)
      (fail! (str subject " must not be a symbolic link")
             {:reason :symbolic-link
              :subject subject
              :path (str root)}))
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (->> (.toArray entries)
           (map #(cast Path %))
           (sort-by #(portable (.relativize root ^Path %)))
           (mapv
            (fn [^Path entry]
              (when (symbolic-link? entry)
                (fail! (str subject " contains a symbolic link")
                       {:reason :symbolic-link
                        :subject subject
                        :path (str entry)}))
              (when-not (or (directory-no-follow? entry)
                            (regular-file-no-follow? entry))
                (fail! (str subject " contains an unsupported filesystem entry")
                       {:reason :unsupported-filesystem-entry
                        :subject subject
                        :path (str entry)}))
              (when-not (paths/real-contained? root entry)
                (fail! (str subject " resolves outside its declared root")
                       {:reason :path-escape
                        :subject subject
                        :path (str entry)}))
              entry))))))

(defn- transient-build-entry?
  [root entry]
  (some #{"bin" "obj"}
        (map str (iterator-seq (.iterator (.relativize ^Path root
                                                       ^Path entry))))))

(defn- validate-managed-sources!
  [staging managed-paths allow-transient-build-artifacts?]
  (mapv
   (fn [relative]
     (let [source (safe-contained-path! staging relative
                                        "Managed staging path")]
       (when-not (existing-no-follow? source)
         (fail! "Managed staging path is missing"
                {:reason :missing-managed-source
                 :path relative
                 :staging-path (str staging)}))
       (if (directory-no-follow? source)
         (let [entries (walk-entries source "Managed staging directory")
               transient
               (->> entries
                    (filter directory-no-follow?)
                    (filter
                     #(contains? #{"bin" "obj"}
                                 (str (.getFileName ^Path %))))
                    (mapv #(portable (.relativize staging ^Path %))))]
           (when (and (seq transient)
                      (not allow-transient-build-artifacts?))
             (fail! "Managed staging contains transient build directories"
                    {:reason :transient-build-artifacts
                     :paths transient}))
           entries)
         (do
           (when (symbolic-link? source)
             (fail! "Managed staging file must not be a symbolic link"
                    {:reason :symbolic-link
                     :path (str source)}))
           (when-not (regular-file-no-follow? source)
             (fail! "Managed staging path is not a regular file or directory"
                    {:reason :unsupported-filesystem-entry
                     :path (str source)}))
           (when-not (paths/real-contained? staging source)
             (fail! "Managed staging file resolves outside staging"
                    {:reason :path-escape
                     :path (str source)}))))
       source))
   managed-paths))

(defn- validate-managed-destinations!
  [product managed-paths]
  (doseq [relative managed-paths]
    (let [destination
          (safe-contained-path! product relative "Managed product path")]
      (when (existing-no-follow? destination)
        (if (directory-no-follow? destination)
          (walk-entries destination "Managed product directory")
          (do
            (when (symbolic-link? destination)
              (fail! "Managed product file must not be a symbolic link"
                     {:reason :symbolic-link
                      :path (str destination)}))
            (when-not (regular-file-no-follow? destination)
              (fail! "Managed product path is not a regular file or directory"
                     {:reason :unsupported-filesystem-entry
                      :path (str destination)}))
            (when-not (paths/real-contained? product destination)
              (fail! "Managed product file resolves outside the product root"
                     {:reason :path-escape
                      :path (str destination)})))))))
  managed-paths)

(defn- managed-inventory
  ([staging managed-paths excluded-paths]
   (managed-inventory staging managed-paths excluded-paths false))
  ([staging managed-paths excluded-paths ignore-transient-build-artifacts?]
   (->> managed-paths
        (mapcat
         (fn [managed]
           (let [source (safe-contained-path! staging managed
                                              "Managed staging path")]
             (if (directory-no-follow? source)
               (for [^Path entry (walk-entries source
                                               "Managed staging directory")
                     :let [relative (portable (.relativize staging entry))]
                     :when (and
                            (not (excluded-path? excluded-paths relative))
                            (not (and ignore-transient-build-artifacts?
                                      (transient-build-entry? staging entry))))]
                 (if (directory-no-follow? entry)
                   [:directory relative nil]
                   [:file relative (util/sha256-file entry)]))
               (when-not (excluded-path? excluded-paths managed)
                 [[:file managed (util/sha256-file source)]])))))
        (sort-by (juxt second first))
        vec)))

(defn- inventory-sha256
  [inventory]
  (util/sha256-text
   (str/join
    "\n"
    (map (fn [[kind path digest]]
           (str (name kind) "\t" path "\t" (or digest "")))
         inventory))))

(defn- unstaged-profile-projects
  [publication inventory]
  (->> (:profile-projects publication)
       vals
       distinct
       (filter
        (fn [project-path]
          (not-any?
           (fn [[_kind path _digest]]
             (or (= project-path path)
                 (relative-under? project-path path)))
           inventory)))
       sort
       vec))

(defn- exclude-project-inventory
  [inventory project-paths]
  (filterv
   (fn [[_kind path _digest]]
     (not-any? #(or (= % path) (relative-under? % path)) project-paths))
   inventory))

(defn- gitmodule-entries
  [workspace-root run-command!]
  (let [output
        (command-output
         run-command! workspace-root
         ["git" "config" "--file" ".gitmodules" "--get-regexp"
          "^submodule\\..*\\.path$"])]
    (mapv
     (fn [line]
       (let [[key path] (str/split line #"\s+" 2)
             prefix (str/replace key #"\.path$" "")
             url (command-output
                  run-command! workspace-root
                  ["git" "config" "--file" ".gitmodules" "--get"
                   (str prefix ".url")])]
         {:key prefix :path path :url url}))
     (remove str/blank? (str/split-lines output)))))

(defn- gitlink
  [workspace-root run-command! submodule-path]
  (let [output
        (command-output run-command! workspace-root
                        ["git" "ls-files" "--stage" "--" submodule-path])
        match (re-matches #"160000 ([0-9a-f]{40,64}) 0\t(.+)" output)]
    (when-not (and match (= submodule-path (nth match 2)))
      (fail! "Declared product path is not one exact parent Git submodule"
             {:reason :invalid-gitlink
              :submodule-path submodule-path
              :git-entry output}))
    (nth match 1)))

(defn- clean-status!
  [run-command! directory subject]
  (let [status
        (command-output run-command! directory
                        ["git" "status" "--porcelain=v1"
                         "--untracked-files=all"])]
    (when-not (str/blank? status)
      (fail! (str subject " contains local changes")
             {:reason :dirty-checkout
              :subject subject
              :path (str directory)
              :status status}))
    status))

(defn- parent-path-clean!
  [workspace-root run-command! submodule-path]
  (let [status
        (command-output
         run-command! workspace-root
         ["git" "status" "--porcelain=v1" "--untracked-files=all"
          "--" submodule-path])]
    (when-not (str/blank? status)
      (fail! "Parent product gitlink is not clean"
             {:reason :dirty-parent-gitlink
              :submodule-path submodule-path
              :status status}))))

(defn- parent-index-clean!
  [workspace-root run-command!]
  (let [staged
        (git-nul-paths run-command! workspace-root
                       ["git" "diff" "--cached" "--name-only" "-z"])]
    (when (seq staged)
      (fail! "Parent repository index contains unrelated staged changes"
             {:reason :dirty-parent-index
              :staged-paths staged}))))

(defn- product-submodule-state
  [workspace-root run-command! {:keys [path]}]
  (let [checkout (paths/resolve-path workspace-root path)
        initialized?
        (and (directory-no-follow? checkout)
             (existing-no-follow? (paths/resolve-path checkout ".git")))]
    (if-not initialized?
      {:path path :initialized? false}
      {:path path
       :initialized? true
       :head (command-output run-command! checkout
                             ["git" "rev-parse" "HEAD"])
       :status (command-output
                run-command! checkout
                ["git" "status" "--porcelain=v1"
                 "--untracked-files=all"])})))

(defn- other-product-states!
  [workspace-root run-command! entries intended-path]
  (into
   (sorted-map)
   (for [entry entries
         :let [path (:path entry)]
         :when (and (str/starts-with? path "products/")
                    (not= path intended-path))]
     (let [state (product-submodule-state workspace-root run-command! entry)]
       (when (and (:initialized? state)
                  (not (str/blank? (:status state))))
         (fail! "Another product submodule contains local changes"
                {:reason :cross-product-changes
                 :submodule-path path
                 :status (:status state)}))
       (parent-path-clean! workspace-root run-command! path)
       [path state]))))

(defn- preflight!
  [{:keys [workspace-root run-command! allow-transient-build-artifacts?]
    :as options
    :or {run-command! process/run!}}]
  (let [workspace-root
        (paths/absolute (or workspace-root (paths/workspace-root)))
        target-contract (resolve-target-contract
                         (assoc options :workspace-root workspace-root))
        publication (exact-runtime-publication! target-contract)
        {:keys [repository-url submodule-path staging-path managed-paths
                excluded-paths]}
        publication
        staging (safe-contained-path! workspace-root staging-path
                                      "Product staging path")
        product (safe-contained-path! workspace-root submodule-path
                                      "Product submodule path")
        _ (when-not (and (directory-no-follow? staging)
                         (paths/real-contained? workspace-root staging))
            (fail! "Product staging directory is missing or escaped"
                   {:reason :invalid-staging-directory
                    :path (str staging)}))
        _ (when-not (and (directory-no-follow? product)
                         (paths/real-contained? workspace-root product)
                         (existing-no-follow?
                          (paths/resolve-path product ".git")))
            (fail! "Product submodule is missing or uninitialized"
                   {:reason :uninitialized-product-submodule
                    :path (str product)}))
        _ (validate-managed-sources! staging managed-paths
                                     allow-transient-build-artifacts?)
        _ (validate-managed-destinations! product managed-paths)
        entries (gitmodule-entries workspace-root run-command!)
        matches (filterv #(= submodule-path (:path %)) entries)
        _ (when-not (= 1 (count matches))
            (fail! "Declared product submodule has no unique .gitmodules entry"
                   {:reason :missing-submodule-declaration
                    :submodule-path submodule-path
                    :matches matches}))
        entry (first matches)
        _ (when-not (= repository-url (:url entry))
            (fail! "Product submodule URL differs from publication metadata"
                   {:reason :submodule-url-mismatch
                    :submodule-path submodule-path
                    :expected repository-url
                    :actual (:url entry)}))
        top-level
        (paths/absolute
         (command-output run-command! product
                         ["git" "rev-parse" "--show-toplevel"]))
        _ (when-not (= (.toRealPath product paths/no-links)
                       (.toRealPath top-level paths/no-links))
            (fail! "Product checkout resolves to a different Git worktree"
                   {:reason :wrong-product-worktree
                    :expected (str product)
                    :actual (str top-level)}))
        origin
        (command-output run-command! product
                        ["git" "remote" "get-url" "origin"])
        _ (when-not (= repository-url origin)
            (fail! "Product checkout origin differs from publication metadata"
                   {:reason :product-origin-mismatch
                    :expected repository-url
                    :actual origin}))
        expected-head (gitlink workspace-root run-command! submodule-path)
        actual-head
        (command-output run-command! product ["git" "rev-parse" "HEAD"])
        _ (when-not (= expected-head actual-head)
            (fail! "Product checkout HEAD differs from the parent gitlink"
                   {:reason :gitlink-head-mismatch
                    :submodule-path submodule-path
                    :expected expected-head
                    :actual actual-head}))
        _ (parent-index-clean! workspace-root run-command!)
        _ (clean-status! run-command! product "Product submodule")
        _ (parent-path-clean! workspace-root run-command! submodule-path)
        other-products
        (other-product-states! workspace-root run-command!
                               entries submodule-path)
        inventory (managed-inventory staging managed-paths excluded-paths
                                     allow-transient-build-artifacts?)]
    {:workspace-root workspace-root
     :target-contract target-contract
     :publication publication
     :staging staging
     :product product
     :gitmodules-entry entry
     :base-commit actual-head
     :other-products other-products
     :inventory inventory
     :source-sha256 (inventory-sha256 inventory)
     :run-command! run-command!}))

(defn verify-synchronized!
  "Proves that clean generated-product HEAD contains exactly the managed staged
  files that will be packed. Declared sibling profile projects absent from this
  generation remain outside this profile-scoped comparison; every staged path
  and every other managed path is still exact. Returns the exact commit and
  repository identity without modifying either repository."
  [{:keys [run-command!] :as options
    :or {run-command! process/run!}}]
  (let [{:keys [publication product inventory base-commit source-sha256]
         :as preflight}
        (preflight! (assoc options
                           :run-command! run-command!
                           :allow-transient-build-artifacts? true))
        product-inventory
        (managed-inventory product (:managed-paths publication)
                           (:excluded-paths publication) true)]
    (let [unstaged-projects
          (unstaged-profile-projects publication inventory)
          comparable-product-inventory
          (exclude-project-inventory product-inventory unstaged-projects)]
      (when-not (= inventory comparable-product-inventory)
        (fail! "Generated product HEAD differs from proved package staging"
               {:reason :stale-generated-product-commit
                :repository (:repository-url publication)
                :commit base-commit
                :unstaged-profile-projects unstaged-projects
                :staging inventory
                :product comparable-product-inventory})))
    {:target (get-in preflight [:target-contract :target])
     :repository-url (:repository-url publication)
     :repository-commit base-commit
     :source-sha256 source-sha256
     :inventory inventory
     :product-root product}))

(defn- delete-tree!
  [path]
  (let [path (paths/absolute path)]
    (when (existing-no-follow? path)
      (if (directory-no-follow? path)
        (with-open [entries (Files/walk path (make-array FileVisitOption 0))]
          (doseq [^Path entry
                  (->> (.toArray entries)
                       (map #(cast Path %))
                       (sort-by #(.getNameCount ^Path %) >))]
            (Files/delete entry)))
        (Files/delete path)))))

(defn- copy-path!
  [staging source destination excluded-paths]
  (if (directory-no-follow? source)
    (let [source (paths/absolute source)
          destination (paths/absolute destination)]
      (doseq [^Path entry (walk-entries source "Managed staging directory")]
        (let [publication-relative
              (portable (.relativize (paths/path staging) entry))]
          (when-not (excluded-path? excluded-paths publication-relative)
            (let [relative (.relativize source entry)
                  target (.resolve destination relative)]
              (if (directory-no-follow? entry)
                (Files/createDirectories target (make-array FileAttribute 0))
                (do
                  (Files/createDirectories (.getParent target)
                                           (make-array FileAttribute 0))
                  (Files/copy
                   entry target
                   (into-array
                    CopyOption
                    [StandardCopyOption/REPLACE_EXISTING])))))))))
    (do
      (Files/createDirectories (.getParent (paths/path destination))
                               (make-array FileAttribute 0))
      (Files/copy
       (paths/path source) (paths/path destination)
       (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(defn- changed-paths
  [run-command! product]
  (-> (set
       (concat
        (git-nul-paths run-command! product
                       ["git" "diff" "--name-only" "-z" "HEAD"])
        (git-nul-paths run-command! product
                       ["git" "ls-files" "--others" "--exclude-standard"
                        "-z"])))
      sort
      vec))

(defn- ignored-paths
  [run-command! product managed-paths]
  (git-nul-paths
   run-command! product
   (into ["git" "ls-files" "--others" "--ignored" "--exclude-standard"
          "-z" "--"]
         managed-paths)))

(defn- managed-change?
  [managed-paths changed]
  (some #(relative-under? % changed) managed-paths))

(defn synchronize!
  "Copies only declared managed paths from proved staging into one clean
  generated-product submodule. This local operation does not commit, push,
  create a repository, or open a pull request."
  [{:keys [run-command!] :as options
    :or {run-command! process/run!}}]
  (let [{:keys [workspace-root publication staging product other-products]
         :as preflight}
        (preflight! (assoc options :run-command! run-command!))
        managed-paths (:managed-paths publication)
        excluded-paths (:excluded-paths publication)]
    (doseq [relative managed-paths]
      (let [source (safe-contained-path! staging relative
                                         "Managed staging path")
            destination (safe-contained-path! product relative
                                              "Managed product path")]
        (delete-tree! destination)
        (copy-path! staging source destination excluded-paths)))
    (let [destination-inventory
          (managed-inventory product managed-paths excluded-paths)
          _ (when-not (= (:inventory preflight) destination-inventory)
              (fail! "Synchronized managed files differ from staged output"
                     {:reason :synchronized-content-mismatch
                      :source (:inventory preflight)
                      :destination destination-inventory}))
          ignored (ignored-paths run-command! product managed-paths)
          changes (changed-paths run-command! product)
          unrelated
          (filterv #(not (managed-change? managed-paths %)) changes)
          other-products-after
          (other-product-states!
           workspace-root run-command!
           (gitmodule-entries workspace-root run-command!)
           (:submodule-path publication))]
      (when (seq ignored)
        (fail! "Managed publication output is ignored by the product repository"
               {:reason :ignored-managed-output
                :paths ignored}))
      (when (seq unrelated)
        (fail! "Synchronization produced changes outside managed paths"
               {:reason :unrelated-product-changes
                :managed-paths managed-paths
                :changes changes
                :unrelated unrelated}))
      (when-not (= other-products other-products-after)
        (fail! "Synchronization changed another product submodule"
               {:reason :cross-product-changes
                :before other-products
                :after other-products-after}))
      {:target (get-in preflight [:target-contract :target])
       :repository-slug (:repository-slug publication)
       :staging-path (:staging-path publication)
       :submodule-path (:submodule-path publication)
       :base-commit (:base-commit preflight)
       :managed-paths managed-paths
       :excluded-paths excluded-paths
       :source-sha256 (:source-sha256 preflight)
       :inventory (:inventory preflight)
       :changes changes
       :external-actions []})))

(defn- branch-name!
  [branch]
  (when-not (and (string? branch)
                 (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]*" branch)
                 (not (str/includes? branch ".."))
                 (not (str/includes? branch "@{"))
                 (not (str/ends-with? branch "/"))
                 (not (str/ends-with? branch ".")))
    (fail! "Product publication branch name is invalid"
           {:reason :invalid-branch-name
            :branch branch}))
  branch)

(defn prepare!
  "Runs local synchronization, creates and commits a local product branch,
  stages the resulting parent gitlink, and returns pull-request metadata.
  It deliberately performs no push and no pull-request creation."
  [{:keys [branch commit-message pull-request-title pull-request-body
           run-command!]
    :or {run-command! process/run!}
    :as options}]
  (branch-name! branch)
  (when-not (and (string? commit-message)
                 (not (str/blank? commit-message)))
    (fail! "Product publication commit message must be non-blank"
           {:reason :invalid-commit-message}))
  (let [preflight (preflight! (assoc options :run-command! run-command!))
        product (:product preflight)
        workspace-root (:workspace-root preflight)
        publication (:publication preflight)
        existing
        (command-output run-command! product
                        ["git" "branch" "--format=%(refname:short)"
                         "--list" branch])]
    (when-not (str/blank? existing)
      (fail! "Product publication branch already exists"
             {:reason :branch-exists
              :branch branch}))
    (let [synchronization
          (synchronize! (assoc options :run-command! run-command!))]
      (when (empty? (:changes synchronization))
        (fail! "Product publication has no generated changes to prepare"
               {:reason :empty-publication
                :target (:target synchronization)}))
      (command-result run-command! product ["git" "switch" "-c" branch])
      (command-result
       run-command! product
       (into ["git" "add" "--"] (:managed-paths publication)))
      (let [staged
            (git-nul-paths run-command! product
                           ["git" "diff" "--cached" "--name-only" "-z"])
            unmanaged
            (filterv #(not (managed-change?
                            (:managed-paths publication) %))
                     staged)]
        (when (or (empty? staged) (seq unmanaged))
          (fail! "Prepared product commit has an invalid staged inventory"
                 {:reason :invalid-staged-product-change
                  :staged staged
                  :unmanaged unmanaged})))
      (command-result run-command! product
                      ["git" "commit" "-m" commit-message])
      (clean-status! run-command! product "Prepared product branch")
      (let [product-commit
            (command-output run-command! product ["git" "rev-parse" "HEAD"])
            submodule-path (:submodule-path publication)]
        (command-result run-command! workspace-root
                        ["git" "add" "--" submodule-path])
        (let [staged-parent
              (git-nul-paths
               run-command! workspace-root
               ["git" "diff" "--cached" "--name-only" "-z"
                "--" submodule-path])
              staged-gitlink
              (gitlink workspace-root run-command! submodule-path)]
          (when-not (= [submodule-path] staged-parent)
            (fail! "Parent gitlink update was not staged exactly once"
                   {:reason :invalid-parent-gitlink-update
                    :expected [submodule-path]
                    :actual staged-parent}))
          (when-not (= product-commit staged-gitlink)
            (fail! "Parent gitlink does not identify the prepared product commit"
                   {:reason :gitlink-commit-mismatch
                    :expected product-commit
                    :actual staged-gitlink}))
          (assoc synchronization
                 :branch branch
                 :product-commit product-commit
                 :parent-gitlink {:path submodule-path
                                  :commit product-commit
                                  :staged? true}
                 :pull-request
                 {:repository (:repository-slug publication)
                  :base (:default-branch publication)
                  :head branch
                  :title (or pull-request-title commit-message)
                  :body (or pull-request-body
                            (str "Prepared from proved DripSharp staging "
                                 (:source-sha256 synchronization) "."))
                  :requires-push true
                  :requires-creation true}
                 :external-actions
                 [:push-required :pull-request-creation-required]))))))

(defn clean-staging!
  "Deletes and recreates only one generated-repository staging directory.
  The products/ checkout is never a cleanup target."
  [{:keys [workspace-root] :as options}]
  (let [workspace-root
        (paths/absolute (or workspace-root (paths/workspace-root)))
        target-contract
        (resolve-target-contract (assoc options :workspace-root workspace-root))
        publication (exact-runtime-publication! target-contract)
        staging (safe-contained-path! workspace-root
                                      (:staging-path publication)
                                      "Product staging path")
        generated-root (paths/resolve-path workspace-root "target/generated")]
    (when (and (existing-no-follow? staging)
               (not (paths/real-contained? generated-root staging)))
      (fail! "Product staging cleanup target escaped target/generated"
             {:reason :cleanup-path-escape
              :path (str staging)}))
    (delete-tree! staging)
    (Files/createDirectories staging (make-array FileAttribute 0))
    {:target (:target target-contract)
     :staging-path (:staging-path publication)
     :cleaned (str staging)}))
