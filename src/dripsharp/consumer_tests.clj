(ns dripsharp.consumer-tests
  "Deterministic generated test-suite emission and execution for products.

  Target contracts own exact test projects, policies, strategy handlers,
  checksum-pinned sources, and fixtures. This namespace owns shared
  containment, project rendering, staging, inventory, cleanup, and command
  execution; target semantics remain behind qualified strategy dispatch."
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.project-xml :as project-xml]
            [dripsharp.util :as util])
  (:import [java.nio.file CopyOption FileVisitOption Files OpenOption Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :consumer-test-generation-failed))))

(defn- portable
  [value]
  (str/replace (str value) "\\" "/"))

(defn- delete-tree!
  [directory]
  (let [directory (paths/absolute directory)]
    (when (paths/exists? directory)
      (with-open [entries (Files/walk directory
                                      (make-array FileVisitOption 0))]
        (doseq [^Path entry
                (->> (.toArray entries)
                     (map #(cast Path %))
                     (sort-by #(.getNameCount ^Path %) >))]
          (Files/delete entry))))))

(defn- clean-directory!
  [directory]
  (let [directory (paths/absolute directory)]
    (delete-tree! directory)
    (Files/createDirectories directory (make-array FileAttribute 0))
    directory))

(defn- write-text!
  [path text]
  (Files/createDirectories (.getParent (paths/path path))
                           (make-array FileAttribute 0))
  (Files/writeString (paths/path path) text (make-array OpenOption 0))
  path)

(defn- copy-file!
  [source destination]
  (Files/createDirectories (.getParent (paths/path destination))
                           (make-array FileAttribute 0))
  (Files/copy (paths/path source)
              (paths/path destination)
              (into-array CopyOption
                          [StandardCopyOption/REPLACE_EXISTING]))
  destination)

(defn- suite-contract
  [target-contract]
  (get-in target-contract [:publication :test-suites]))

(defn- projects
  [target-contract]
  (:projects (suite-contract target-contract)))

(defn- strategies
  [target-contract]
  (:strategies (suite-contract target-contract)))

(defn- project-by-id!
  [target-contract project-id]
  (or (first (filter #(= project-id (:id %)) (projects target-contract)))
      (fail! "Test-suite strategy references an unknown project"
             {:reason :unknown-test-suite-project
              :project project-id})))

(defn- contained-path!
  [root path subject]
  (let [root (paths/absolute root)
        path (paths/absolute path)]
    (when-not (.startsWith ^Path path ^Path root)
      (fail! (str subject " escapes generated test staging")
             {:reason :test-suite-path-escape
              :root (str root) :path (str path)}))
    path))

(defn- project-reference-paths
  [target-contract {:keys [directory profile-references project-references]}]
  (let [publication (:publication target-contract)
        profile-projects (:profile-projects publication)
        profiles
        (mapv
         (fn [profile-id]
           (let [project-directory (get profile-projects profile-id)
                 project-file
                 (get-in target-contract
                         [:profiles profile-id :destination :configuration
                          :output :project-file])]
             (when-not (and project-directory project-file)
               (fail! "Test suite cannot resolve a published project reference"
                      {:reason :missing-project-reference
                       :profile profile-id}))
             (str project-directory "/" project-file)))
         profile-references)]
    (->> (concat profiles project-references)
         (map (fn [reference]
                (-> (.relativize (paths/path directory)
                                 (paths/path reference))
                    portable)))
         distinct
         vec)))

(defn- package-reference-node
  [{:keys [id version]}]
  (project-xml/element
   "PackageReference"
   [["Include" id] ["Version" version] ["PrivateAssets" "all"]]
   []))

(defn- project-reference-node
  [path]
  (project-xml/element "ProjectReference" [["Include" path]] []))

(defn- fixture-node
  [{:keys [destination]}]
  (project-xml/element
   "None"
   [["Update" destination] ["CopyToOutputDirectory" "PreserveNewest"]]
   []))

(defn- render-project
  [target-contract
   {:keys [assembly-name target-framework packages] :as project}]
  (let [references (project-reference-paths target-contract project)
        fixtures
        (->> (strategies target-contract)
             (filter #(= assembly-name (:project %)))
             (mapcat :fixtures)
             (sort-by :destination)
             vec)]
    (project-xml/render
     (project-xml/element
      "Project"
      [["Sdk" "Microsoft.NET.Sdk"]]
      [(project-xml/element
        "PropertyGroup"
        [(project-xml/element "TargetFramework"
                              [(project-xml/text target-framework)])
         (project-xml/element "AssemblyName"
                              [(project-xml/text assembly-name)])
         (project-xml/element "RootNamespace"
                              [(project-xml/text assembly-name)])
         (project-xml/element "ImplicitUsings"
                              [(project-xml/text "enable")])
         (project-xml/element "Nullable"
                              [(project-xml/text "enable")])
         (project-xml/element "IsPackable"
                              [(project-xml/text "false")])
         (project-xml/element "IsTestProject"
                              [(project-xml/text "true")])
         (project-xml/element "RollForward"
                              [(project-xml/text "Major")])])
       (project-xml/element
        "ItemGroup"
        (mapv package-reference-node packages))
       (project-xml/element
        "ItemGroup"
        (mapv project-reference-node references))
       (project-xml/element
        "ItemGroup"
        (mapv fixture-node fixtures))]))))

(defn project-relative-path
  "Returns the sole test project path for compatibility with existing callers."
  [target-contract]
  (let [declared (projects target-contract)]
    (when-not (= 1 (count declared))
      (fail! "Test-suite contract does not contain exactly one project"
             {:reason :ambiguous-test-suite-project
              :projects (mapv :id declared)}))
    (let [{:keys [directory assembly-name]} (first declared)]
      (str directory "/" assembly-name ".csproj"))))

(defn- project-relative-path*
  [{:keys [directory assembly-name]}]
  (str directory "/" assembly-name ".csproj"))

(defn- solution-relative-path!
  [target-contract]
  (when (some :solution-inclusion (projects target-contract))
    (let [candidates
          (->> (get-in target-contract [:publication :managed-paths])
               (filter #(re-matches #"(?i)[^/]+[.]slnx" %))
               vec)]
      (when-not (= 1 (count candidates))
        (fail! "Solution-included test projects require one managed .slnx file"
               {:reason :invalid-generated-solution-paths
                :paths candidates}))
      (first candidates))))

(defn- solution-project-paths
  [target-contract]
  (let [profile-paths
        (for [[profile-id directory]
              (sort-by key (get-in target-contract
                                   [:publication :profile-projects]))]
          (str directory "/"
               (get-in target-contract
                       [:profiles profile-id :destination :configuration
                        :output :project-file])))
        test-paths
        (->> (projects target-contract)
             (filter :solution-inclusion)
             (map project-relative-path*))]
    (->> (concat profile-paths test-paths)
         distinct
         sort
         vec)))

(defn- render-solution
  [target-contract]
  (project-xml/render
   (project-xml/element
    "Solution"
    (mapv (fn [path]
            (project-xml/element "Project" [["Path" path]] []))
          (solution-project-paths target-contract)))))

(defn- verify-solution!
  [staging target-contract]
  (when-let [relative (solution-relative-path! target-contract)]
    (let [file (paths/resolve-path staging relative)
          expected (render-solution target-contract)
          actual (when (paths/regular-file? file) (slurp (str file)))]
      (when-not (= expected actual)
        (fail! "Generated solution is missing or changed"
               {:reason :generated-solution-drift
                :path relative
                :expected expected
                :actual actual}))
      file)))

(defn- command-line
  [arguments]
  (str/join " " arguments))

(defn- project-commands
  [project]
  (let [project (project-relative-path* project)]
    {:restore ["dotnet" "restore" project]
     :build ["dotnet" "build" project
             "--configuration" "Release" "--no-restore"]
     :test ["dotnet" "test" project
            "--configuration" "Release" "--no-restore" "--no-build"]}))

(defn- commands
  [target-contract]
  (into (sorted-map)
        (map (fn [project]
               [(:id project) (project-commands project)]))
        (projects target-contract)))

(defn- render-readme
  [target-contract]
  (let [family (name (:product-family target-contract))
        project-commands (commands target-contract)]
    (str "# Generated test suites\n\n"
         "These test suites are generated from the authoritative "
         "`dripsharp/dripsharp` target contract. Do not apply durable manual "
         "fixes in a generated product repository.\n\n"
         "From a clean " family " product-repository checkout:\n\n"
         (apply
          str
          (for [[project-id {:keys [restore build test]}] project-commands]
            (str "### `" project-id "`\n\n"
                 "```sh\n"
                 (command-line restore) "\n"
                 (command-line build) "\n"
                 (command-line test) "\n"
                 "```\n\n")))
         "The project references only paths within this checkout. "
         "Its test host permits major-version roll-forward so a later .NET "
         "runtime can exercise an earlier-targeted product family. "
         "`SHA256SUMS` inventories every generated test file except the "
         "inventory itself.\n"
         "Each declared strategy records whether its output is shipped or "
         "validation-only; validation-only project paths are excluded from "
         "publication by the target contract.\n")))

(defn- render-notice
  [target-contract]
  (let [fixtures
        (->> (strategies target-contract)
             (mapcat :fixtures)
             (sort-by :destination)
             vec)]
    (str "# Test-suite fixture attribution\n\n"
         (if (seq fixtures)
           (apply
            str
            (map
             (fn [{:keys [destination license attribution]}]
               (str "- `" destination "` — " attribution
                    " License: `" license "`.\n"))
             fixtures))
           "This suite contains no external fixtures.\n"))))

(defn- regular-files
  [directory]
  (with-open [entries (Files/walk (paths/path directory)
                                  (make-array FileVisitOption 0))]
    (->> (.toArray entries)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (sort-by str)
         vec)))

(defn- delete-build-artifacts!
  [tests-root]
  (let [tests-root (paths/absolute tests-root)]
    (doseq [directory
            (with-open [entries (Files/walk tests-root
                                            (make-array FileVisitOption 0))]
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (filter paths/directory?)
                   (filter #(contains? #{"bin" "obj"}
                                       (str (.getFileName ^Path %))))
                   (sort-by #(.getNameCount ^Path %) >)
                   vec))]
      (delete-tree! directory))))

(defn- render-inventory
  [tests-root inventory-file]
  (->> (regular-files tests-root)
       (remove #(= (paths/absolute inventory-file) (paths/absolute %)))
       (map
        (fn [file]
          (str (util/sha256-file file)
               "  "
               (portable (.relativize (paths/path tests-root) file)))))
       (str/join "\n")
       (#(str % "\n"))))

(defn- inventory-entries!
  [inventory-file]
  (let [lines (str/split-lines (slurp (str inventory-file)))
        entries
        (mapv
         (fn [line]
           (or
            (when-let [[_ sha256 relative]
                       (re-matches #"([0-9a-f]{64})  (.+)" line)]
              [relative sha256])
            (fail! "Generated consumer test inventory is malformed"
                   {:reason :malformed-test-inventory
                    :path (str inventory-file)
                    :line line})))
         lines)]
    (when-not (= (count entries) (count (distinct (map first entries))))
      (fail! "Generated consumer test inventory contains duplicate paths"
             {:reason :duplicate-test-inventory-path
              :path (str inventory-file)}))
    (into (sorted-map) entries)))

(defn verify-inventory!
  "Rejects missing, added, or changed files in one generated tests/ tree."
  [tests-root]
  (let [tests-root (paths/absolute tests-root)
        inventory-file (paths/resolve-path tests-root "SHA256SUMS")]
    (when-not (paths/regular-file? inventory-file)
      (fail! "Generated consumer test inventory is missing"
             {:reason :missing-test-inventory
              :path (str inventory-file)}))
    (let [expected (inventory-entries! inventory-file)
          actual
          (into
           (sorted-map)
           (map
            (fn [file]
              [(portable (.relativize (paths/path tests-root) file))
               (util/sha256-file file)]))
           (remove #(= (paths/absolute inventory-file)
                       (paths/absolute %))
                   (regular-files tests-root)))]
      (when-not (= expected actual)
        (fail! "Generated consumer test files differ from SHA256SUMS"
               {:reason :test-inventory-mismatch
                :path (str tests-root)
                :expected expected
                :actual actual}))
      {:inventory-file inventory-file
       :entries expected})))

(defn focused-consumer-strategy!
  "Copies one target-owned focused public-consumer source set and its fixtures."
  [{:keys [phase target-contract strategy project-root]}]
  (case phase
    :emit
    (do
      (doseq [[_profile {:keys [source destination]}]
              (:profile-tests strategy)]
        (copy-file! (paths/resolve-path
                     (:target-directory target-contract) source)
                    (paths/resolve-path project-root destination)))
      (doseq [{:keys [source destination]} (:fixtures strategy)]
        (copy-file! (paths/resolve-path
                     (:target-directory target-contract) source)
                    (paths/resolve-path project-root destination)))
      {:sources (count (:profile-tests strategy))
       :fixtures (count (:fixtures strategy))})

    :verify
    {:verified :checksum-pinned-at-contract-load}

    (fail! "Focused-consumer strategy received an unsupported phase"
           {:reason :unsupported-test-suite-phase :phase phase})))

(defn- invoke-strategy!
  [{:keys [handler id project] :as strategy} options]
  (let [handler-fn
        (try
          (requiring-resolve handler)
          (catch RuntimeException error
            (throw
             (ex-info "Test-suite strategy handler cannot be resolved"
                      {:kind :consumer-test-generation-failed
                       :reason :test-suite-handler-resolution
                       :strategy id :handler handler}
                      error))))]
    (when-not (ifn? handler-fn)
      (fail! "Test-suite strategy handler is not callable"
             {:reason :invalid-test-suite-handler
              :strategy id :handler handler}))
    (handler-fn
     (assoc options
            :strategy strategy
            :project (project-by-id! (:target-contract options) project)))))

(defn emit!
  "Recreates every declared generated test project deterministically."
  [{:keys [workspace-root target-contract]}]
  (let [root (paths/absolute workspace-root)
        publication (:publication target-contract)
        test-suites (:test-suites publication)]
    (when-not (= :generated-repository (:kind publication))
      (fail! "Test suites require a generated product publication"
             {:reason :conformance-only-target
              :target (:target target-contract)}))
    (when-not test-suites
      (fail! "Generated product publication has no test-suite contract"
             {:reason :missing-test-suite-contract
              :target (:target target-contract)}))
    (when-not (some #{"tests"} (:managed-paths publication))
      (fail! "Generated test suites are outside managed publication paths"
             {:reason :unmanaged-consumer-tests
              :target (:target target-contract)
              :managed-paths (:managed-paths publication)}))
    (let [staging (paths/resolve-path root (:staging-path publication))
          tests-root (clean-directory! (paths/resolve-path staging "tests"))
          project-records
          (mapv
           (fn [{:keys [id directory assembly-name] :as project}]
             (let [project-root
                   (contained-path!
                    tests-root (paths/resolve-path staging directory)
                    "Test-suite project directory")
                   project-file
                   (contained-path!
                    project-root
                    (paths/resolve-path project-root
                                        (str assembly-name ".csproj"))
                    "Test-suite project file")]
               (write-text! project-file
                            (render-project target-contract project))
               {:id id :project project :project-root project-root
                :project-file project-file}))
           (projects target-contract))
          projects-by-id (into {} (map (juxt :id identity)) project-records)]
      (write-text! (paths/resolve-path tests-root "README.md")
                   (render-readme target-contract))
      (write-text! (paths/resolve-path tests-root "NOTICE.md")
                   (render-notice target-contract))
      (let [strategy-results
            (mapv
             (fn [{:keys [id project] :as strategy}]
               (let [{:keys [project-root]}
                     (or (get projects-by-id project)
                         (fail! "Test-suite strategy project was not staged"
                                {:reason :missing-staged-test-project
                                 :strategy id :project project}))]
                 {:id id
                  :kind (:kind strategy)
                  :policy (:policy strategy)
                  :project project
                  :result
                  (invoke-strategy!
                   strategy
                   {:phase :emit
                    :workspace-root root
                    :target-contract target-contract
                    :tests-root tests-root
                    :project-root project-root})}))
             (strategies target-contract))]
        (let [inventory-file (paths/resolve-path tests-root "SHA256SUMS")]
          (write-text! inventory-file
                       (render-inventory tests-root inventory-file)))
        (verify-inventory! tests-root)
        (let [solution-file
              (when-let [relative (solution-relative-path! target-contract)]
                (write-text! (paths/resolve-path staging relative)
                             (render-solution target-contract)))]
          {:tests-root tests-root
           :project-files
           (into (sorted-map) (map (juxt :id :project-file)) project-records)
           :project-file (when (= 1 (count project-records))
                           (:project-file (first project-records)))
           :solution-file solution-file
           :inventory-file (paths/resolve-path tests-root "SHA256SUMS")
           :strategies strategy-results
           :commands (commands target-contract)})))))

(defn verify!
  "Restores, builds, and runs every staged generated test project."
  [{:keys [workspace-root target-contract run-command! timeout-ms]
    :or {run-command! process/run! timeout-ms 300000}}]
  (let [root (paths/absolute workspace-root)
        run-command! (or run-command! process/run!)
        staging (paths/resolve-path
                 root (get-in target-contract
                              [:publication :staging-path]))
        project-records
        (mapv
         (fn [project]
           (let [path (contained-path!
                       staging
                       (paths/resolve-path staging
                                           (project-relative-path* project))
                       "Generated test project")]
             (when-not (paths/regular-file? path)
               (fail! "Generated test-suite project is missing"
                      {:reason :missing-generated-test-project
                       :target (:target target-contract)
                       :project (:id project)
                       :path (str path)}))
             {:id (:id project) :project project :project-file path
              :project-root (.getParent (paths/path path))}))
         (projects target-contract))
        projects-by-id (into {} (map (juxt :id identity)) project-records)
        commands (commands target-contract)]
    (verify-solution! staging target-contract)
    (verify-inventory! (paths/resolve-path staging "tests"))
    (doseq [{:keys [id project] :as strategy} (strategies target-contract)]
      (let [{:keys [project-root]}
            (or (get projects-by-id project)
                (fail! "Test-suite strategy project is not staged"
                       {:reason :missing-staged-test-project
                        :strategy id :project project}))]
        (invoke-strategy!
         strategy
         {:phase :verify
          :workspace-root root
          :target-contract target-contract
          :tests-root (paths/resolve-path staging "tests")
          :project-root project-root})))
    (try
      (doseq [[_project phases] commands
              phase [:restore :build :test]]
        (run-command! {:command (get phases phase)
                       :directory staging
                       :timeout-ms timeout-ms}))
      (finally
        (delete-build-artifacts! (paths/resolve-path staging "tests"))))
    (verify-inventory! (paths/resolve-path staging "tests"))
    (verify-solution! staging target-contract)
    {:project-files
     (into (sorted-map) (map (juxt :id :project-file)) project-records)
     :project-file (when (= 1 (count project-records))
                     (:project-file (first project-records)))
     :commands commands}))
