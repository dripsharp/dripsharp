(ns dripsharp.alpha-release
  "Fail-closed local assembly of DLL-focused GitHub alpha-release assets.

  Release inventory is target-owned. Assembly requires a clean product
  submodule at an exact parent gitlink, byte-for-byte agreement with the proved
  generated staging state, and Release builds whose managed and native output
  matches the inventory exactly. This namespace never creates tags or GitHub
  releases and never uploads or pushes anything."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption FileAlreadyExistsException FileVisitOption
            Files LinkOption OpenOption Path StandardOpenOption]
           [java.nio.file.attribute BasicFileAttributes FileAttribute FileTime]
           [java.util Locale]
           [java.util.zip Deflater ZipEntry ZipFile ZipOutputStream]))

(def schema-version 1)

(def ^:private inventory-keys
  #{:schema-version :kind :product-family :asset-prefix :target-framework
    :entry-assembly :product-assemblies :managed-dependencies :platforms})

(def ^:private product-assembly-keys
  #{:file :project})

(def ^:private managed-dependency-keys
  #{:file :package-id :version})

(def ^:private platform-keys
  #{:id :runtime-identifier :native-assets})

(def ^:private native-asset-keys
  #{:file :package-id :version :package-path})

(def ^:private forbidden-suffixes
  [".nupkg" ".snupkg" ".pdb" ".xml" ".zip" ".tar" ".tgz" ".tar.gz"
   ".gz" ".bz2" ".xz" ".7z"])

(def ^:private native-suffixes
  [".so" ".dylib"])

(def ^:private fixed-zip-time
  (FileTime/fromMillis 315532800000))

(def ^:private json-mapper
  (ObjectMapper.))

(def ^:private nuget-org-v3-source
  "https://api.nuget.org/v3/index.json")

(def ^:private isolated-msbuild-environment
  #{"CustomAfterMicrosoftCommonProps"
    "CustomAfterMicrosoftCommonTargets"
    "CustomBeforeMicrosoftCommonProps"
    "CustomBeforeMicrosoftCommonTargets"
    "MSBuildExtensionsPath"
    "MSBuildExtensionsPath32"
    "MSBuildExtensionsPath64"
    "MSBuildSDKsPath"
    "MSBuildUserExtensionsPath"})

(def ^:private isolated-nuget-config
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
       "<configuration>\n"
       "  <packageSources>\n"
       "    <clear />\n"
       "    <add key=\"nuget.org\" "
       "value=\"" nuget-org-v3-source "\" "
       "protocolVersion=\"3\" />\n"
       "  </packageSources>\n"
       "</configuration>\n"))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :alpha-release-failed))))

(defn- exact-keys!
  [subject expected value]
  (when-not (and (map? value) (= expected (set (keys value))))
    (fail! (str subject " must use its exact typed fields")
           {:reason :invalid-release-inventory
            :subject subject
            :expected (vec (sort expected))
            :actual (if (map? value)
                      (vec (sort (keys value)))
                      value)}))
  value)

(defn- non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not
        (re-find
         #"[\u0000\u000B\u000C\r\n\u0085\u2028\u2029]"
         value))))

(defn- nuget-package-id?
  [value]
  (and (string? value)
       (<= (count value) 100)
       (boolean
        (re-matches
         #"[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*"
         value))))

(defn- exact-package-version?
  [value]
  (and (string? value)
       (<= (count value) 64)
       (boolean
        (re-matches
         #"[0-9]+(?:\.[0-9]+){0,3}(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
         value))))

(defn- windows-reserved-path-component?
  [component]
  (let [basename (first (str/split component #"\." 2))]
    (boolean
     (re-matches
      #"(?i:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])"
      basename))))

(defn- portable-path-component?
  [component]
  (and (non-blank-string? component)
       (not (contains? #{"." ".."} component))
       (not (re-find #"[<>:\"|?*\u0000-\u001F]" component))
       (not (re-find #"[. ]$" component))
       (not (windows-reserved-path-component? component))))

(defn- simple-file!
  [subject value]
  (when-not (and (non-blank-string? value)
                 (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" value)
                 (not (windows-reserved-path-component? value))
                 (not (str/includes? value "/"))
                 (not (str/includes? value "\\")))
    (fail! (str subject " must be one portable file name")
           {:reason :invalid-release-inventory
            :subject subject
            :file value}))
  value)

(defn- relative-components
  [value]
  (when (and (non-blank-string? value)
             (not (str/includes? value "\\"))
             (not (str/starts-with? value "/"))
             (not (re-find #"^[A-Za-z]:" value)))
    (let [components (str/split value #"/" -1)]
      (when (every? portable-path-component? components)
        components))))

(defn- relative-path!
  [subject value]
  (when-not (relative-components value)
    (fail! (str subject " must be a normalized portable relative path")
           {:reason :invalid-release-inventory
            :subject subject
            :path value}))
  value)

(defn- distinct-vector!
  [subject value identity-fn]
  (when-not (and (vector? value)
                 (= (count value)
                    (count (distinct (map identity-fn value)))))
    (fail! (str subject " must be a vector without duplicate identities")
           {:reason :invalid-release-inventory
            :subject subject
            :value value}))
  value)

(defn- portable-lower-case
  [value]
  (.toLowerCase ^String value Locale/ROOT))

(defn- case-insensitive-path-prefix?
  [^Path path ^Path prefix]
  (let [components
        (fn [^Path value]
          (mapv #(portable-lower-case (str %))
                (iterator-seq (.iterator value))))
        path-components (components path)
        prefix-components (components prefix)]
    (and (= (portable-lower-case (str (.getRoot path)))
            (portable-lower-case (str (.getRoot prefix))))
         (<= (count prefix-components) (count path-components))
         (= prefix-components
            (subvec path-components 0 (count prefix-components))))))

(defn- case-insensitive-collisions
  [values identity-fn]
  (into
   (sorted-map)
   (filter (fn [[_ entries]] (< 1 (count entries))))
   (group-by #(portable-lower-case (identity-fn %)) values)))

(defn- case-insensitive-intersection
  [values candidates]
  (let [candidate-identities
        (set (map portable-lower-case candidates))]
    (set
     (filter #(contains? candidate-identities
                         (portable-lower-case %))
             values))))

(defn- forbidden-file?
  [value]
  (let [lower (portable-lower-case value)]
    (some #(str/ends-with? lower %) forbidden-suffixes)))

(defn- native-file?
  [value]
  (let [lower (portable-lower-case value)]
    (or (some #(str/ends-with? lower %) native-suffixes)
        (and (str/ends-with? lower ".dll")
             (str/starts-with? lower "lib")))))

(defn- dll-file?
  [value]
  (str/ends-with? (portable-lower-case value) ".dll"))

(defn- sha256?
  [value]
  (and (string? value)
       (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- semver-version!
  [authorized-tag]
  (let [match
        (when (string? authorized-tag)
          (re-matches
           #"^v?((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-alpha(?:\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)$"
           authorized-tag))]
    (when-not match
      (fail! "Authorized release tag must be an exact SemVer alpha tag"
             {:reason :invalid-alpha-tag
              :authorized-tag authorized-tag
              :example "v0.1.0-alpha.1"}))
    (second match)))

(defn- exact-commit!
  [commit]
  (when-not (and (string? commit)
                 (re-matches #"(?:[0-9a-f]{40}|[0-9a-f]{64})" commit))
    (fail! "Release target must be one exact lowercase product commit"
           {:reason :invalid-product-commit
            :product-commit commit}))
  commit)

(defn- product-projects
  [target-contract]
  (into
   {}
   (for [[profile-id profile] (:profiles target-contract)]
     [(get-in profile [:destination :configuration :project :assembly-name])
      {:profile profile-id
       :project (get-in target-contract
                        [:publication :profile-projects profile-id])
       :target-framework
       (get-in profile
               [:destination :configuration :project :target-framework])}])))

(defn validate-inventory!
  "Validates one target-owned release inventory against its normalized target
  contract and returns the inventory unchanged."
  [target-contract inventory]
  (exact-keys! "Release inventory" inventory-keys inventory)
  (when-not (= schema-version (:schema-version inventory))
    (fail! "Release inventory has an unsupported schema version"
           {:reason :invalid-release-inventory
            :expected schema-version
            :actual (:schema-version inventory)}))
  (when-not (= :github-alpha-zip (:kind inventory))
    (fail! "Release inventory has an unsupported release kind"
           {:reason :invalid-release-inventory
            :actual (:kind inventory)}))
  (when-not (= (:product-family target-contract)
               (:product-family inventory))
    (fail! "Release inventory identifies the wrong product family"
           {:reason :invalid-release-inventory
            :expected (:product-family target-contract)
            :actual (:product-family inventory)}))
  (when-not (= :generated-repository
               (get-in target-contract [:publication :kind]))
    (fail! "Only generated product repositories can assemble alpha releases"
           {:reason :conformance-only-target
            :target (:target target-contract)}))
  (when-not (and (non-blank-string? (:asset-prefix inventory))
                 (re-matches #"[A-Za-z0-9][A-Za-z0-9.-]*"
                             (:asset-prefix inventory)))
    (fail! "Release asset prefix is invalid"
           {:reason :invalid-release-inventory
            :asset-prefix (:asset-prefix inventory)}))
  (when-not (and (string? (:target-framework inventory))
                 (re-matches #"net[1-9][0-9]*\.0"
                             (:target-framework inventory)))
    (fail! "Release target framework is invalid"
           {:reason :invalid-release-inventory
            :target-framework (:target-framework inventory)}))
  (simple-file! "Release entry assembly" (:entry-assembly inventory))
  (let [assemblies
        (distinct-vector! "Release product assemblies"
                          (:product-assemblies inventory) :file)
        managed
        (distinct-vector! "Release managed dependencies"
                          (:managed-dependencies inventory) :file)
        platforms
        (distinct-vector! "Release platforms" (:platforms inventory) :id)
        expected-projects (product-projects target-contract)
        actual-projects
        (into {}
              (map
               (fn [assembly]
                 (exact-keys! "Release product assembly"
                              product-assembly-keys assembly)
                 (let [file (simple-file! "Product assembly file"
                                          (:file assembly))
                       project (relative-path! "Product assembly project"
                                               (:project assembly))]
                   (when-not (dll-file? file)
                     (fail! "Product release assemblies must be DLL files"
                            {:reason :invalid-release-inventory
                             :file file}))
                   [(subs file 0 (- (count file) 4))
                    {:project project}]))
               assemblies))
        expected-project-view
        (into {}
              (map (fn [[assembly {:keys [project]}]]
                     [assembly {:project project}]))
              expected-projects)]
    (when-not (seq assemblies)
      (fail! "Release inventory has no product assemblies"
             {:reason :invalid-release-inventory}))
    (when-not (= expected-project-view actual-projects)
      (fail! "Release product assemblies do not match every generated product project"
             {:reason :invalid-release-inventory
              :expected expected-project-view
              :actual actual-projects}))
    (when-not (contains? (set (map :file assemblies))
                         (:entry-assembly inventory))
      (fail! "Release entry assembly is not a product assembly"
             {:reason :invalid-release-inventory
              :entry-assembly (:entry-assembly inventory)}))
    (let [frameworks (set (map :target-framework (vals expected-projects)))]
      (when-not (= #{(:target-framework inventory)} frameworks)
        (fail! "Release target framework differs from generated product projects"
               {:reason :invalid-release-inventory
                :expected frameworks
                :actual (:target-framework inventory)})))
    (doseq [dependency managed]
      (exact-keys! "Release managed dependency"
                   managed-dependency-keys dependency)
      (let [{:keys [file package-id version]} dependency]
        (simple-file! "Managed dependency file" file)
        (when-not (and (dll-file? file)
                       (= file (str package-id ".dll"))
                       (nuget-package-id? package-id)
                       (exact-package-version? version))
          (fail! "Managed dependency must name its exact non-framework package DLL"
                 {:reason :invalid-release-inventory
                  :dependency dependency}))))
    (when-not (seq platforms)
      (fail! "Release inventory has no platform assets"
             {:reason :invalid-release-inventory}))
    (doseq [platform platforms]
      (exact-keys! "Release platform" platform-keys platform)
      (let [{:keys [id runtime-identifier native-assets]} platform]
        (when-not (and (non-blank-string? id)
                       (re-matches #"[a-z0-9][a-z0-9-]*" id))
          (fail! "Release platform id is invalid"
                 {:reason :invalid-release-inventory :platform id}))
        (when-not (or (nil? runtime-identifier)
                      (and (non-blank-string? runtime-identifier)
                           (= id runtime-identifier)))
          (fail! "Release runtime identifier must equal its platform id"
                 {:reason :invalid-release-inventory
                  :platform id
                  :runtime-identifier runtime-identifier}))
        (distinct-vector! "Release native assets" native-assets :file)
        (when-not (= (nil? runtime-identifier) (empty? native-assets))
          (fail! "Portable releases must have no native assets and native releases must select a runtime"
                 {:reason :invalid-release-inventory
                  :platform id
                  :runtime-identifier runtime-identifier
                  :native-assets native-assets}))
        (doseq [asset native-assets]
          (exact-keys! "Release native asset" native-asset-keys asset)
          (let [{:keys [file package-id version package-path]} asset]
            (simple-file! "Native release file" file)
            (relative-path! "Native dependency package path" package-path)
            (when-not (and (native-file? file)
                           (= file (last (relative-components package-path)))
                           (nuget-package-id? package-id)
                           (exact-package-version? version))
              (fail! "Native asset must identify one exact package runtime file"
                     {:reason :invalid-release-inventory
                      :asset asset}))))
        (let [file-groups
              (concat
               (map #(assoc % :inventory-kind :product)
                    assemblies)
               (map #(assoc % :inventory-kind :managed)
                    managed)
               (map #(assoc % :inventory-kind :native)
                    native-assets))
              collisions (case-insensitive-collisions file-groups :file)]
          (when (seq collisions)
            (fail! "Release dependency file names collide case-insensitively"
                   {:reason :dependency-name-collision
                    :platform id
                    :collisions collisions})))))
    inventory))

(defn read-inventory!
  "Reads and validates the canonical targets/<target>/release.edn contract."
  [target-contract]
  (let [target-root (:target-directory target-contract)
        file (paths/resolve-path target-root "release.edn")]
    (when (Files/isSymbolicLink file)
      (fail! "Target release inventory is a symbolic link"
             {:reason :symbolic-link-release-inventory
              :target (:target target-contract)
              :path (str file)}))
    (when-not (and (paths/regular-file? file)
                   (paths/real-contained? target-root file))
      (fail! "Target release inventory is missing or escaped"
             {:reason :missing-release-inventory
              :target (:target target-contract)
              :path (str file)}))
    (let [inventory
          (try
            (util/read-single-edn-string! (slurp (str file)))
            (catch RuntimeException error
              (throw
               (ex-info "Target release inventory is not exact EDN"
                        {:kind :alpha-release-failed
                         :reason :invalid-release-inventory
                         :path (str file)}
                        error))))]
      (validate-inventory! target-contract inventory))))

(defn asset-filename
  [inventory version platform]
  (str (:asset-prefix inventory) "-" version "-"
       (:target-framework inventory) "-" (:id platform) ".zip"))

(defn validate-request!
  "Validates the explicitly authorized alpha tag and full product commit before
  a caller starts an expensive proof ladder."
  [authorized-tag product-commit]
  {:authorized-tag authorized-tag
   :version (semver-version! authorized-tag)
   :product-commit (exact-commit! product-commit)})

(defn select-platforms!
  "Returns the inventory platforms selected for this preparation. A nil
  selection preserves the all-platform default; an explicit selection must be
  a nonempty vector of unique exact inventory platform ids."
  [inventory platform-ids]
  (if (nil? platform-ids)
    (:platforms inventory)
    (let [available (into {} (map (juxt :id identity)) (:platforms inventory))]
      (when-not (and (vector? platform-ids)
                     (seq platform-ids)
                     (every? non-blank-string? platform-ids)
                     (= (count platform-ids)
                        (count (distinct platform-ids))))
        (fail! "Release platform selection must be a nonempty vector of unique platform ids"
               {:reason :invalid-release-platform-selection
                :platform-ids platform-ids
                :available (vec (sort (keys available)))}))
      (let [unknown (vec (sort (remove #(contains? available %)
                                       platform-ids)))]
        (when (seq unknown)
          (fail! "Release platform selection contains unknown platform ids"
                 {:reason :invalid-release-platform-selection
                  :platform-ids platform-ids
                  :unknown unknown
                  :available (vec (sort (keys available)))})))
      (let [selected (set platform-ids)]
        (filterv #(contains? selected (:id %)) (:platforms inventory))))))

(defn- command-output
  [run-command! directory command]
  (-> (run-command! {:command command :directory directory})
      :output
      str/trim))

(defn- safe-workspace-path!
  [workspace-root relative subject]
  (relative-path! subject relative)
  (let [root (paths/absolute workspace-root)
        value (paths/absolute (paths/resolve-path root relative))
        linked-components
        (when (.startsWith value root)
          (->> (iterate #(.getParent ^Path %) value)
               (take-while #(and % (not= root %)))
               (filter #(Files/isSymbolicLink ^Path %))
               (map #(util/portable-path root ^Path %))
               reverse
               vec))]
    (when (seq linked-components)
      (fail! (str subject " path contains symbolic links")
             {:reason :symbolic-link-release-path
              :subject subject
              :root (str root)
              :path relative
              :symbolic-links linked-components}))
    (when-not (and (.startsWith value root)
                   (paths/real-contained? root value))
      (fail! (str subject " is missing or escaped")
             {:reason :release-path-escape
              :subject subject
              :root (str root)
              :path relative}))
    value))

(defn- safe-output-root!
  [workspace-root target-contract output-root]
  (let [root (paths/absolute workspace-root)
        output (paths/absolute output-root)
        protected-root
        (some
         (fn [[protected-kind relative]]
           (let [protected
                 (paths/absolute (paths/resolve-path root relative))]
             (when (case-insensitive-path-prefix? output protected)
               {:kind protected-kind
                :path protected})))
         [[:product-repository
           (get-in target-contract [:publication :submodule-path])]
          [:proved-staging
           (get-in target-contract [:publication :staging-path])]])
        git-metadata-components
        (when (.startsWith output root)
          (->> (.relativize root output)
               .iterator
               iterator-seq
               (map str)
               (keep #(when (= ".git" (portable-lower-case %))
                        ".git"))
               vec))
        linked-components
        (when (.startsWith output root)
          (->> (iterate #(.getParent ^Path %) output)
               (take-while #(and % (not= root %)))
               (filter #(Files/isSymbolicLink ^Path %))
               (map #(util/portable-path root ^Path %))
               reverse
               vec))]
    (when-not (.startsWith output root)
      (fail! "Release output root must remain inside the workspace"
             {:reason :release-output-path-escape
              :root (str root)
              :path (str output)}))
    (when (seq git-metadata-components)
      (fail! "Release output root must not traverse Git metadata"
             {:reason :git-metadata-release-output
              :root (str root)
              :path (str output)
              :components git-metadata-components}))
    (when (seq linked-components)
      (fail! "Release output root path contains symbolic links"
             {:reason :symbolic-link-release-output
              :root (str root)
              :path (str output)
              :symbolic-links linked-components}))
    (when protected-root
      (fail! "Release output root must not write inside proved product state"
             {:reason :protected-release-output
              :root (str root)
              :path (str output)
              :protected-kind (:kind protected-root)
              :protected-root (str (:path protected-root))}))
    (when (and (Files/exists output (make-array LinkOption 0))
               (not (Files/isDirectory
                     output
                     (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))
      (fail! "Release output root exists but is not a directory"
             {:reason :invalid-release-output-root
              :root (str root)
              :path (str output)}))
    output))

(defn- git-clean!
  [run-command! product]
  (let [status
        (command-output run-command! product
                        ["git" "status" "--porcelain=v1"
                         "--untracked-files=all"])]
    (when-not (str/blank? status)
      (fail! "Product repository contains local generated or manual changes"
             {:reason :dirty-product-repository
              :path (str product)
              :status status}))))

(defn- ignored-build-component?
  [relative]
  (some #{"bin" "obj"} (relative-components relative)))

(defn- managed-file-inventory
  [root managed-paths {:keys [ignore-build-components?]}]
  (let [root (paths/absolute root)]
    (into
     (sorted-map)
     (mapcat
      (fn [managed]
        (let [managed-root (paths/absolute
                            (paths/resolve-path root managed))
              linked-components
              (when (.startsWith managed-root root)
                (->> (iterate #(.getParent ^Path %) managed-root)
                     (take-while #(and % (not= root %)))
                     (filter #(Files/isSymbolicLink ^Path %))
                     (map #(util/portable-path root ^Path %))
                     reverse
                     vec))]
          (when (seq linked-components)
            (fail! "Proved product managed path contains symbolic links"
                   {:reason :proved-state-mismatch
                    :root (str root)
                    :path managed
                    :symbolic-links linked-components}))
          (when-not (Files/exists managed-root (make-array LinkOption 0))
            (fail! "Proved product managed path is missing"
                   {:reason :proved-state-mismatch
                    :root (str root)
                    :path managed}))
          (if (Files/isDirectory managed-root (make-array LinkOption 0))
            (with-open [stream
                        (Files/walk managed-root
                                    (make-array FileVisitOption 0))]
              (doall
               (for [entry (.toArray stream)
                     :let [^Path entry (cast Path entry)
                           relative (util/portable-path root entry)]
                     :when
                     (do
                       (when (Files/isSymbolicLink entry)
                         (fail! "Proved product state contains a symbolic link"
                                {:reason :proved-state-mismatch
                                 :path relative}))
                       (Files/isRegularFile
                        entry (make-array LinkOption 0)))
                     :when (or (not ignore-build-components?)
                               (not (ignored-build-component? relative)))]
                 [relative (util/sha256-file entry)])))
            [[managed (util/sha256-file managed-root)]])))
      managed-paths))))

(defn- inventory-sha256
  [inventory]
  (util/sha256-text
   (str/join "\n" (map (fn [[path digest]]
                         (str path "\t" digest))
                       inventory))))

(defn- exact-product-state!
  [workspace-root target-contract product-commit run-command!]
  (let [publication (:publication target-contract)
        product
        (safe-workspace-path! workspace-root (:submodule-path publication)
                              "Product submodule")
        staging
        (safe-workspace-path! workspace-root (:staging-path publication)
                              "Proved product staging")
        git-marker (paths/resolve-path product ".git")]
    (when-not (Files/exists git-marker (make-array LinkOption 0))
      (fail! "Product repository is not an initialized submodule"
             {:reason :uninitialized-product-submodule
              :path (str product)}))
    (git-clean! run-command! product)
    (let [head
          (command-output run-command! product ["git" "rev-parse" "HEAD"])
          top-level
          (paths/absolute
           (command-output run-command! product
                           ["git" "rev-parse" "--show-toplevel"]))
          origin
          (command-output run-command! product
                          ["git" "remote" "get-url" "origin"])
          gitlink-output
          (command-output
           run-command! workspace-root
           ["git" "ls-files" "--stage" "--"
            (:submodule-path publication)])
          gitlink-match
          (re-matches
           (re-pattern
            (str "160000 ([0-9a-f]{40,64}) 0\\t"
                 (java.util.regex.Pattern/quote
                  (:submodule-path publication))))
           gitlink-output)]
      (when-not (= product-commit head)
        (fail! "Product repository HEAD differs from the authorized product commit"
               {:reason :product-commit-mismatch
                :expected product-commit
                :actual head}))
      (when-not (= (.toRealPath product (make-array LinkOption 0))
                   (.toRealPath top-level (make-array LinkOption 0)))
        (fail! "Product repository resolves to a different Git worktree"
               {:reason :wrong-product-worktree
                :expected (str product)
                :actual (str top-level)}))
      (when-not (= (:repository-url publication) origin)
        (fail! "Product repository origin differs from its publication contract"
               {:reason :product-origin-mismatch
                :expected (:repository-url publication)
                :actual origin}))
      (when-not (= product-commit (second gitlink-match))
        (fail! "Parent gitlink does not target the authorized product commit"
               {:reason :gitlink-commit-mismatch
                :expected product-commit
                :actual (second gitlink-match)
                :git-entry gitlink-output}))
      (let [managed-paths (:managed-paths publication)
            staged
            (managed-file-inventory
             staging managed-paths {:ignore-build-components? true})
            committed
            (managed-file-inventory
             product managed-paths {:ignore-build-components? false})]
        (when-not (= staged committed)
          (fail! "Product commit differs from the exact proved generated state"
                 {:reason :proved-state-mismatch
                  :staging-sha256 (inventory-sha256 staged)
                  :product-sha256 (inventory-sha256 committed)
                  :missing (vec (sort (set/difference
                                       (set (keys staged))
                                       (set (keys committed)))))
                  :unexpected (vec (sort (set/difference
                                          (set (keys committed))
                                          (set (keys staged)))))
                  :changed
                  (vec
                   (sort
                    (for [[path digest] staged
                          :when (and (contains? committed path)
                                     (not= digest (get committed path)))]
                      path)))}))
        {:product product
         :staging staging
         :product-commit product-commit
         :proved-source-sha256 (inventory-sha256 staged)
         :inventory staged}))))

(defn- entry-project
  [product inventory]
  (let [entry (:entry-assembly inventory)
        project
        (:project
         (first (filter #(= entry (:file %))
                        (:product-assemblies inventory))))
        assembly (subs entry 0 (- (count entry) 4))]
    (paths/resolve-path product project (str assembly ".csproj"))))

(defn- default-build!
  [{:keys [product inventory platform build-output packages-root
           run-command!]}]
  (let [project-file (entry-project product inventory)
        _ (when-not (paths/regular-file? project-file)
            (fail! "Release entry project is missing from the product commit"
                   {:reason :missing-entry-project
                    :path (str project-file)}))
        runtime-identifier (:runtime-identifier platform)
        build-directory (.getParent ^Path build-output)
        artifacts-path
        (paths/resolve-path build-directory "artifacts")
        user-extensions-path
        (paths/resolve-path build-directory "user-extensions")
        restore-config-directory
        (paths/resolve-path build-directory "restore-config")
        restore-config
        (paths/resolve-path restore-config-directory "NuGet.Config")
        _ (Files/createDirectories
           restore-config-directory (make-array FileAttribute 0))
        _ (Files/createDirectories
           user-extensions-path (make-array FileAttribute 0))
        _ (Files/writeString
           restore-config isolated-nuget-config StandardCharsets/UTF_8
           (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                   StandardOpenOption/WRITE]))
        restore-command
        (cond->
         ["dotnet" "restore" (str project-file)
          "-noAutoResponse"
          "--verbosity" "minimal"
          "--configfile" (str restore-config)
          "--packages" (str packages-root)
          "--artifacts-path" (str artifacts-path)
          (str "-p:RestoreSources=" nuget-org-v3-source)
          (str "-p:MSBuildUserExtensionsPath=" user-extensions-path)
          "-p:RestoreAdditionalProjectSources="
          "-p:RestoreFallbackFolders="
          "-p:ImportDirectoryBuildProps=false"
          "-p:ImportDirectoryBuildTargets=false"]
          runtime-identifier
          (into ["--runtime" runtime-identifier]))
        restore-result
        (run-command! {:command restore-command
                       :directory build-directory
                       :unset-environment isolated-msbuild-environment})
        command
        (cond->
         ["dotnet" "build" (str project-file)
          "-noAutoResponse"
          "--nologo"
          "--configuration" "Release"
          "--verbosity:minimal"
          "--no-incremental"
          "--no-restore"
          "--artifacts-path" (str artifacts-path)
          "--output" (str build-output)
          (str "-p:RestorePackagesPath=" packages-root)
          (str "-p:MSBuildUserExtensionsPath=" user-extensions-path)
          "-p:CopyLocalLockFileAssemblies=true"
          "-p:ImportDirectoryBuildProps=false"
          "-p:ImportDirectoryBuildTargets=false"
          "-p:DebugType=None"
          "-p:DebugSymbols=false"
          "-p:GenerateDocumentationFile=false"
          "-warnaserror"]
          runtime-identifier
          (into ["--runtime" runtime-identifier
                 "--self-contained" "false"]))]
    {:configuration "Release"
     :runtime-identifier runtime-identifier
     :restore-result restore-result
     :result
     (run-command! {:command command
                    :directory build-directory
                    :unset-environment isolated-msbuild-environment})}))

(defn- direct-files
  [build-root directory platform]
  (when (Files/isSymbolicLink directory)
    (fail! "Release build output directory is a symbolic link"
           {:reason :symbolic-link-build-output
            :platform (:id platform)
            :entries ["."]}))
  (let [root (paths/absolute build-root)
        directory (paths/absolute directory)
        linked-ancestors
        (when (.startsWith directory root)
          (->> (iterate #(.getParent ^Path %) (.getParent directory))
               (take-while #(and % (.startsWith ^Path % root)))
               (filter #(Files/isSymbolicLink ^Path %))
               (map #(if (= root %)
                       "."
                       (util/portable-path root ^Path %)))
               reverse
               vec))]
    (when (seq linked-ancestors)
      (fail! "Release build output path contains symbolic-link ancestors"
             {:reason :symbolic-link-build-output
              :platform (:id platform)
              :symbolic-links linked-ancestors})))
  (when-not (paths/directory? directory)
    (fail! "Release build did not create its isolated output directory"
           {:reason :missing-build-output
            :path (str directory)}))
  (with-open [stream (Files/list directory)]
    (let [entries (mapv #(cast Path %) (.toArray stream))
          links
          (->> entries
               (filter #(Files/isSymbolicLink ^Path %))
               (map #(str (.getFileName ^Path %)))
               sort
               vec)]
      (when (seq links)
        (fail! "Release build output contains symbolic links"
               {:reason :symbolic-link-build-output
                :platform (:id platform)
                :entries links}))
      (->> entries
           (filter #(Files/isRegularFile
                     ^Path %
                     (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
           (sort-by #(str (.getFileName ^Path %)))
           vec))))

(defn- expected-files
  [inventory platform]
  {:product (set (map :file (:product-assemblies inventory)))
   :managed (set (map :file (:managed-dependencies inventory)))
   :native (set (map :file (:native-assets platform)))})

(defn- dependency-file-name
  [inventory]
  (str (subs (:entry-assembly inventory)
             0 (- (count (:entry-assembly inventory)) 4))
       ".deps.json"))

(defn- runtime-target-name
  [inventory platform]
  (str ".NETCoreApp,Version=v"
       (subs (:target-framework inventory) 3)
       (when-let [runtime-identifier (:runtime-identifier platform)]
         (str "/" runtime-identifier))))

(defn- dependency-coordinate
  [{:keys [package-id version]}]
  (str package-id "/" version))

(defn- restored-package-file!
  [packages-root library-path asset-path platform dependency asset-kind]
  (let [{:keys [package-id version]} dependency
        expected-library-path
        (str (portable-lower-case package-id) "/"
             (portable-lower-case version))]
    (when-not (and (= expected-library-path library-path)
                   (relative-components library-path)
                   (relative-components asset-path))
      (fail! "Release dependency has an invalid restored package path"
             {:reason :release-dependency-evidence-mismatch
              :platform (:id platform)
              :asset-kind asset-kind
              :dependency dependency
              :expected-library-path expected-library-path
              :actual-library-path library-path
              :asset-path asset-path}))
    (let [root (paths/absolute packages-root)
          file (paths/absolute
                (paths/resolve-path root library-path asset-path))
          linked-paths
          (when (.startsWith file root)
            (->> (iterate #(.getParent ^Path %) file)
                 (take-while #(and % (.startsWith ^Path % root)))
                 (filter #(Files/isSymbolicLink ^Path %))
                 reverse
                 (mapv str)))]
      (when (seq linked-paths)
        (fail! "Restored release dependency path contains symbolic links"
               {:reason :symbolic-link-restored-package-asset
                :platform (:id platform)
                :asset-kind asset-kind
                :dependency dependency
                :packages-root (str root)
                :path (str file)
                :symbolic-links linked-paths}))
      (when-not (and (.startsWith file root)
                     (Files/isRegularFile
                      file
                      (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                     (paths/real-contained? root file))
        (fail! "Restored release dependency asset is missing or escaped"
               {:reason :missing-restored-package-asset
                :platform (:id platform)
                :asset-kind asset-kind
                :dependency dependency
                :packages-root (str root)
                :path (str file)}))
      file)))

(defn- read-dependency-file!
  [file platform]
  (try
    (.readValue json-mapper (.toFile ^Path file) java.util.Map)
    (catch Exception error
      (throw
       (ex-info
        "Release build dependency evidence is not valid JSON"
        {:kind :alpha-release-failed
         :reason :invalid-release-dependency-evidence
         :platform (:id platform)
         :path (str file)}
        error)))))

(defn- package-evidence!
  [target libraries files packages-root platform dependency asset-kind]
  (let [{:keys [file package-id version package-path]} dependency
        coordinate (dependency-coordinate dependency)
        library (get libraries coordinate)
        library-path (get library "path")
        section (case asset-kind
                  :managed "runtime"
                  :native "native")
        paths (vec (sort (keys (get (get target coordinate) section))))
        matching-paths
        (when (= :managed asset-kind)
          (filterv #(= file (last (relative-components %))) paths))
        expected-path
        (case asset-kind
          :managed
          (when (= 1 (count matching-paths))
            (first matching-paths))
          :native package-path)]
    (when-not (and (= "package" (get library "type"))
                   (some #{expected-path} paths))
      (fail! "Release dependency differs from its exact restored package evidence"
             {:reason :release-dependency-evidence-mismatch
              :platform (:id platform)
              :asset-kind asset-kind
              :dependency dependency
              :coordinate coordinate
              :expected-path (or package-path file)
              :actual-paths paths
              :library-type (get library "type")}))
    (let [restored
          (restored-package-file!
           packages-root library-path expected-path platform dependency
           asset-kind)
          output (first (get files file))
          restored-sha256 (util/sha256-file restored)
          output-sha256 (util/sha256-file output)]
      (when-not (= restored-sha256 output-sha256)
        (fail! "Release dependency bytes differ from the exact restored package asset"
               {:reason :release-dependency-byte-mismatch
                :platform (:id platform)
                :asset-kind asset-kind
                :dependency dependency
                :restored-package-path
                (str library-path "/" expected-path)
                :expected-sha256 restored-sha256
                :actual-sha256 output-sha256}))
      (cond-> {:file file
               :package-id package-id
               :version version
               :restored-package-path
               (str library-path "/" expected-path)
               :sha256 restored-sha256}
        (= :managed asset-kind) (assoc :runtime-path expected-path)
        (= :native asset-kind) (assoc :package-path expected-path)))))

(defn- dependency-evidence!
  [files packages-root inventory platform]
  (let [filename (dependency-file-name inventory)
        dependency-files (get files filename)]
    (when-not (= 1 (count dependency-files))
      (fail! "Release build is missing the entry assembly dependency evidence"
             {:reason :missing-release-dependency-evidence
              :platform (:id platform)
              :filename filename
              :actual (count dependency-files)}))
    (let [document (read-dependency-file! (first dependency-files) platform)
          target-name (runtime-target-name inventory platform)
          actual-target-name (get-in document ["runtimeTarget" "name"])
          target (get (get document "targets") target-name)
          libraries (get document "libraries")]
      (when-not (and (= target-name actual-target-name)
                     (instance? java.util.Map target)
                     (instance? java.util.Map libraries))
        (fail! "Release dependency evidence is missing its exact runtime target"
               {:reason :invalid-release-dependency-evidence
                :platform (:id platform)
                :filename filename
                :expected-runtime-target target-name
                :actual-runtime-target actual-target-name}))
      {:filename filename
       :runtime-target target-name
       :managed
       (mapv #(package-evidence!
               target libraries files packages-root platform % :managed)
             (:managed-dependencies inventory))
       :native
       (mapv #(package-evidence!
               target libraries files packages-root platform % :native)
             (:native-assets platform))})))

(defn- inspect-build-output!
  [build-root build-output packages-root inventory platform
   framework-assemblies]
  (let [expected (expected-files inventory platform)
        expected-all (apply set/union #{} (vals expected))
        direct (direct-files build-root build-output platform)
        by-name
        (group-by #(str (.getFileName ^Path %))
                  direct)
        collisions
        (into (sorted-map)
              (filter (fn [[_ entries]] (< 1 (count entries))))
              by-name)
        binaries
        (set
         (for [[file _] by-name
               :when (or (dll-file? file) (native-file? file))]
           file))
        framework
        (case-insensitive-intersection binaries framework-assemblies)
        missing (set/difference expected-all binaries)
        unexpected (set/difference binaries expected-all)]
    (when (seq collisions)
      (fail! "Release build output has dependency-name collisions"
             {:reason :dependency-name-collision
              :platform (:id platform)
              :collisions (into (sorted-map)
                                (map (fn [[file entries]]
                                       [file (mapv str entries)]))
                                collisions)}))
    (when (seq framework)
      (fail! "Release build output contains framework assemblies"
             {:reason :framework-assembly
              :platform (:id platform)
              :files (vec (sort framework))}))
    (when (or (seq missing) (seq unexpected))
      (fail! "Release build output differs from its exact binary inventory"
             {:reason :build-output-mismatch
              :platform (:id platform)
              :missing (vec (sort missing))
              :unexpected (vec (sort unexpected))
              :actual (vec (sort binaries))}))
    {:files
     (into
      (sorted-map)
      (for [file (sort expected-all)
            :let [source (first (get by-name file))]]
        [file {:source source
               :sha256 (util/sha256-file source)
               :kind
               (cond
                 (contains? (:product expected) file) :product
                 (contains? (:managed expected) file) :managed
                 :else :native)}]))
     :dependency-evidence
     (dependency-evidence! by-name packages-root inventory platform)}))

(defn- ensure-output-available!
  [output]
  (when (Files/exists output
                      (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (fail! "Release output already exists"
           {:reason :release-output-exists
            :path (str output)})))

(defn- create-output!
  [output suffix write-temporary!]
  (ensure-output-available! output)
  (Files/createDirectories (.getParent output)
                           (make-array FileAttribute 0))
  (let [temporary
        (Files/createTempFile
         (.getParent output) ".dripsharp-alpha-" suffix
         (make-array FileAttribute 0))]
    (try
      (write-temporary! temporary)
      (try
        (Files/move temporary output (make-array CopyOption 0))
        (catch FileAlreadyExistsException _
          (fail! "Release output already exists"
                 {:reason :release-output-exists
                  :path (str output)})))
      output
      (finally
        (Files/deleteIfExists temporary)))))

(defn- write-zip!
  [output files]
  (create-output!
   output ".zip"
   (fn [temporary]
     (with-open [raw
                 (Files/newOutputStream
                  temporary
                  (into-array
                   OpenOption [StandardOpenOption/TRUNCATE_EXISTING
                               StandardOpenOption/WRITE]))
                 archive (doto (ZipOutputStream. raw)
                           (.setLevel Deflater/BEST_COMPRESSION))]
       (doseq [[file {:keys [source]}] files]
         (let [entry (doto (ZipEntry. file)
                       (.setLastModifiedTime fixed-zip-time)
                       (.setLastAccessTime fixed-zip-time)
                       (.setCreationTime fixed-zip-time))]
           (.putNextEntry archive entry)
           (Files/copy ^Path source archive)
           (.closeEntry archive)))))))

(defn- output-ownership
  [output]
  (let [attributes
        (Files/readAttributes
         output BasicFileAttributes
         (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
    {:path output
     :file-key (.fileKey attributes)
     :size (.size attributes)
     :sha256 (util/sha256-file output)}))

(defn- owned-output?
  [{:keys [^Path path file-key size sha256]}]
  (when (and (Files/exists
              path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
             (not (Files/isSymbolicLink path)))
    (let [attributes
          (Files/readAttributes
           path BasicFileAttributes
           (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
          current-key (.fileKey attributes)]
      (and (.isRegularFile attributes)
           (or (not (and file-key current-key))
               (= file-key current-key))
           (= size (.size attributes))
           (= sha256 (util/sha256-file path))))))

(defn- delete-owned-output!
  [ownership]
  (when (owned-output? ownership)
    (Files/deleteIfExists (:path ownership))))

(defn- ensure-owned-outputs!
  [ownerships]
  (let [changed
        (->> ownerships
             (remove owned-output?)
             (mapv #(str (:path %))))]
    (when (seq changed)
      (fail! "Prepared release assets changed before the release record was written"
             {:reason :prepared-release-asset-changed
              :paths changed}))))

(defn- zip-records
  [artifact]
  (with-open [archive (ZipFile. (str artifact))]
    (mapv
     (fn [^ZipEntry entry]
       {:name (.getName entry)
        :directory? (.isDirectory entry)
        :sha256
        (when-not (.isDirectory entry)
          (with-open [input (.getInputStream archive entry)]
            (util/digest-input "SHA-256" input)))})
     (enumeration-seq (.entries archive)))))

(defn- safe-artifact-path!
  [artifact-root artifact]
  (let [root (paths/absolute artifact-root)
        artifact (paths/absolute artifact)
        linked-paths
        (when (.startsWith artifact root)
          (->> (iterate #(.getParent ^Path %) artifact)
               (take-while #(and % (.startsWith ^Path % root)))
               (filter #(Files/isSymbolicLink ^Path %))
               reverse
               (mapv str)))]
    (when (seq linked-paths)
      (fail! "Downloaded release asset path contains symbolic links"
             {:reason :symbolic-link-release-asset
              :root (str root)
              :path (str artifact)
              :symbolic-links linked-paths}))
    (when-not (and (.startsWith artifact root)
                   (paths/real-contained? root artifact))
      (fail! "Downloaded release asset is missing or escaped its download root"
             {:reason :release-asset-path-escape
              :root (str root)
              :path (str artifact)}))
    artifact))

(defn verify-asset!
  "Verifies that a ZIP is exactly the product, managed dependency, and
  platform-native inventory. Any framework assembly, forbidden artifact, path,
  duplicate, missing entry, unexpected entry, archive mismatch, or entry byte
  mismatch fails."
  [{:keys [artifact-root artifact inventory platform expected-hashes
           expected-artifact-sha256 framework-assemblies]}]
  (when-not (sha256? expected-artifact-sha256)
    (fail! "Prepared release asset SHA-256 must be one lowercase digest"
           {:reason :invalid-release-artifact-digest
            :expected-artifact-sha256 expected-artifact-sha256}))
  (let [artifact (safe-artifact-path! artifact-root artifact)
        artifact-sha256 (util/sha256-file artifact)
        _ (when-not (= expected-artifact-sha256 artifact-sha256)
            (fail! "Downloaded release asset differs from the prepared ZIP"
                   {:reason :release-artifact-digest-mismatch
                    :path (str artifact)
                    :expected expected-artifact-sha256
                    :actual artifact-sha256}))
        records (zip-records artifact)
        names (mapv :name records)
        name-collisions (case-insensitive-collisions names identity)
        unsafe
        (filterv
         #(or (:directory? %)
              (nil? (relative-components (:name %)))
              (not= 1 (count (relative-components (:name %)))))
         records)
        forbidden (filterv forbidden-file? names)
        framework
        (vec
         (sort
          (case-insensitive-intersection names framework-assemblies)))
        expected (expected-files inventory platform)
        expected-all (apply set/union #{} (vals expected))
        actual (set names)
        missing (set/difference expected-all actual)
        unexpected (set/difference actual expected-all)]
    (when (seq name-collisions)
      (fail! "Release asset contains case-insensitively colliding entries"
             {:reason :dependency-name-collision
              :collisions name-collisions}))
    (when (seq unsafe)
      (fail! "Release asset contains directories or unsafe paths"
             {:reason :unsafe-release-path
              :entries (mapv :name unsafe)}))
    (when (seq forbidden)
      (fail! "Release asset contains forbidden package, symbols, documentation, or source archives"
             {:reason :forbidden-release-file
              :entries forbidden}))
    (when (seq framework)
      (fail! "Release asset contains framework assemblies"
             {:reason :framework-assembly
              :entries framework}))
    (when (or (seq missing) (seq unexpected))
      (fail! "Release asset differs from its exact inventory"
             {:reason :release-asset-mismatch
              :missing-product
              (vec (sort (set/intersection missing (:product expected))))
              :missing-managed
              (vec (sort (set/intersection missing (:managed expected))))
              :missing-native
              (vec (sort (set/intersection missing (:native expected))))
              :unexpected-managed
              (vec (sort (filter dll-file? unexpected)))
              :unexpected-native
              (vec (sort (filter native-file? unexpected)))
              :unrelated
              (vec
               (sort
                (remove #(or (dll-file? %) (native-file? %))
                        unexpected)))}))
    (doseq [{:keys [name sha256]} records]
      (when-not (= (get expected-hashes name) sha256)
        (fail! "Release asset entry differs from the proved Release build"
               {:reason :release-entry-digest-mismatch
                :entry name
                :expected (get expected-hashes name)
                :actual sha256})))
    {:path (str artifact)
     :sha256 artifact-sha256
     :entries
     (into (sorted-map)
           (map (juxt :name :sha256))
           records)}))

(defn- framework-assembly-names!
  [workspace-root run-command!]
  (let [output
        (command-output run-command! workspace-root
                        ["dotnet" "--list-runtimes"])
        directories
        (for [line (str/split-lines output)
              :let [match
                    (re-matches
                     #"Microsoft\.NETCore\.App ([^ ]+) \[(.+)\]"
                     line)]
              :when match]
          (paths/resolve-path (nth match 2) (second match)))
        names
        (into
         #{}
         (mapcat
          (fn [directory]
            (when (paths/directory? directory)
              (with-open [stream (Files/list directory)]
                (doall
                 (for [entry (.toArray stream)
                       :let [^Path entry (cast Path entry)
                             file (str (.getFileName entry))]
                       :when (and (paths/regular-file? entry)
                                  (dll-file? file))]
                   file)))))
          directories))]
    (when (empty? names)
      (fail! "Could not resolve Microsoft.NETCore.App framework assemblies"
             {:reason :missing-framework-inventory
              :runtime-output output}))
    names))

(defn- delete-temp-tree!
  [root]
  (when (and root (Files/exists root (make-array LinkOption 0)))
    (with-open [stream (Files/walk root (make-array FileVisitOption 0))]
      (doseq [entry
              (->> (.toArray stream)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- canonical
  [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (str %1) (str %2)))
          (map (fn [[key item]] [key (canonical item)]))
          value)

    (set? value) (vec (sort-by str (map canonical value)))
    (vector? value) (mapv canonical value)
    (sequential? value) (mapv canonical value)
    :else value))

(defn- release-record-file
  [output-root inventory version]
  (paths/resolve-path
   output-root
   (str (:asset-prefix inventory) "-" version "-release.edn")))

(defn- write-record!
  [output record]
  (create-output!
   output ".edn"
   (fn [temporary]
     (Files/writeString
      temporary
      (str (pr-str (canonical record)) "\n")
      StandardCharsets/UTF_8
      (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING
                              StandardOpenOption/WRITE])))))

(defn- release-notes
  [inventory version product-commit assets]
  (str
   "# " (:asset-prefix inventory) " " version "\n\n"
   "This is an alpha DLL prerelease for "
   (:target-framework inventory) ".\n\n"
   "- Product commit: `" product-commit "`\n"
   "- NuGet publication: none; this release contains DLL ZIP assets only.\n"
   "- Prepared platforms:\n"
   (str/join
    "\n"
    (map
     (fn [{:keys [platform runtime-identifier filename]}]
       (str "  - `" platform "`"
            (if runtime-identifier
              (str " (runtime identifier `" runtime-identifier "`)")
              " (portable; no runtime identifier)")
            ": `" filename "`"))
     assets))
   "\n"))

(defn prepare!
  "Builds and verifies every declared platform asset, then writes dry-run
  GitHub release metadata. The caller must first run the target's complete
  proof. This function checks that proved staging and the exact clean product
  commit still agree before and after all Release builds."
  [{:keys [workspace-root target-contract inventory authorized-tag
           product-commit platform-ids output-root run-command! build-fn
           framework-assemblies]
    :or {run-command! process/run!
         build-fn default-build!}}]
  (let [workspace-root
        (paths/absolute (or workspace-root (paths/workspace-root)))
        inventory
        (validate-inventory! target-contract
                             (or inventory
                                 (read-inventory! target-contract)))
        platforms (select-platforms! inventory platform-ids)
        {:keys [version product-commit]}
        (validate-request! authorized-tag product-commit)
        output-root
        (safe-output-root!
         workspace-root
         target-contract
         (or output-root
             (paths/resolve-path
              workspace-root "target/releases" version
              (name (:product-family inventory)))))
        initial-state
        (exact-product-state! workspace-root target-contract product-commit
                              run-command!)
        record-file (release-record-file output-root inventory version)
        artifact-files
        (into
         {}
         (map
          (fn [platform]
            [(:id platform)
             (paths/resolve-path
              output-root
              (asset-filename inventory version platform))]))
         platforms)
        _ (doseq [output (cons record-file (vals artifact-files))]
            (ensure-output-available! output))
        framework-assemblies
        (set (or framework-assemblies
                 (framework-assembly-names! workspace-root run-command!)))
        created-outputs (atom [])
        temp-root
        (Files/createTempDirectory
         (str "dripsharp-" (name (:product-family inventory)) "-alpha-")
         (make-array FileAttribute 0))]
    (try
      (let [assets
            (mapv
             (fn [platform]
               (let [build-output
                     (paths/resolve-path temp-root (:id platform) "output")
                     packages-root
                     (paths/resolve-path temp-root (:id platform) "packages")
                     _ (Files/createDirectories
                        build-output (make-array FileAttribute 0))
                     build
                     (build-fn
                      {:workspace-root workspace-root
                       :product (:product initial-state)
                       :target-contract target-contract
                       :inventory inventory
                       :platform platform
                       :configuration "Release"
                       :build-output build-output
                       :packages-root packages-root
                       :run-command! run-command!})]
                 (when-not (= "Release" (:configuration build))
                   (fail! "Alpha-release build did not use Release configuration"
                          {:reason :wrong-build-configuration
                           :platform (:id platform)
                           :actual (:configuration build)}))
                 (let [{:keys [files dependency-evidence]}
                       (inspect-build-output!
                        temp-root build-output packages-root inventory platform
                        framework-assemblies)
                       filename (asset-filename inventory version platform)
                       artifact (get artifact-files (:id platform))
                       _ (safe-output-root!
                          workspace-root target-contract output-root)
                       _ (write-zip! artifact files)
                       ownership (output-ownership artifact)
                       _ (swap! created-outputs conj ownership)
                       verification
                       (verify-asset!
                        {:artifact-root output-root
                         :artifact artifact
                         :expected-artifact-sha256 (:sha256 ownership)
                         :inventory inventory
                         :platform platform
                         :expected-hashes
                         (into {}
                               (map (fn [[file record]]
                                      [file (:sha256 record)]))
                               files)
                         :framework-assemblies framework-assemblies})]
                   {:platform (:id platform)
                    :runtime-identifier (:runtime-identifier platform)
                    :filename filename
                    :path (str artifact)
                    :sha256 (:sha256 verification)
                    :entries (:entries verification)
                    :dependency-evidence dependency-evidence
                    :build-configuration "Release"})))
             platforms)
            final-state
            (exact-product-state! workspace-root target-contract product-commit
                                  run-command!)
            _ (when-not (= (:proved-source-sha256 initial-state)
                           (:proved-source-sha256 final-state))
                (fail! "Proved product state changed during release assembly"
                       {:reason :proved-state-changed
                        :before (:proved-source-sha256 initial-state)
                        :after (:proved-source-sha256 final-state)}))
            _ (safe-output-root!
               workspace-root target-contract output-root)
            _ (ensure-owned-outputs! @created-outputs)
            github-release
            {:repository
             (get-in target-contract [:publication :repository-slug])
             :authorized-tag authorized-tag
             :target-commitish product-commit
             :prerelease true
             :latest false
             :notes (release-notes inventory version product-commit assets)
             :assets
             (mapv #(select-keys % [:filename :sha256]) assets)}
            record
            {:schema-version schema-version
             :kind :github-alpha-release-preparation
             :product-family (:product-family inventory)
             :version version
             :target-framework (:target-framework inventory)
             :platforms (mapv :id platforms)
             :product-commit product-commit
             :proved-source-sha256 (:proved-source-sha256 final-state)
             :assets assets
             :github-release github-release
             :external-actions
             [:tag-or-release-creation-requires-authorization
              :asset-upload-requires-authorization
              :push-requires-authorization]}]
        (safe-output-root! workspace-root target-contract output-root)
        (write-record! record-file record)
        (assoc record
               :record-path (str record-file)))
      (catch Throwable error
        (doseq [output (reverse @created-outputs)]
          (try
            (delete-owned-output! output)
            (catch Throwable cleanup-error
              (.addSuppressed error cleanup-error))))
        (throw error))
      (finally
        (delete-temp-tree! temp-root)))))
