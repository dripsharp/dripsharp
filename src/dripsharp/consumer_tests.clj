(ns dripsharp.consumer-tests
  "Deterministic consumer-test emission and execution for generated products.

  Target contracts own the checksum-pinned test sources and fixtures. This
  namespace renders the SDK test project and repository-facing documentation
  into disposable product staging; generated product repositories never become
  an authored source of test changes."
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.brine-xunit :as brine-xunit]
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

(defn- project-reference-paths
  [target-contract test-project-directory]
  (let [publication (:publication target-contract)
        profile-projects (:profile-projects publication)]
    (mapv
     (fn [profile-id]
       (let [project-directory (get profile-projects profile-id)
             project-file
             (get-in target-contract
                     [:profiles profile-id :destination :configuration
                      :output :project-file])]
         (when-not (and project-directory project-file)
           (fail! "Consumer tests cannot resolve a published project reference"
                  {:reason :missing-project-reference
                   :profile profile-id}))
         (-> (.relativize
              (paths/path test-project-directory)
              (paths/path (str project-directory "/" project-file)))
             portable)))
     (sort (keys profile-projects)))))

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
  [target-contract]
  (let [consumer-tests (get-in target-contract
                               [:publication :consumer-tests])
        {:keys [directory assembly-name target-framework packages]}
        (:project consumer-tests)
        references
        (cond-> (project-reference-paths target-contract directory)
          (= :pkl (:target target-contract))
          (into ["../DripSharp.Brine.LanguageSnippetRunner/DripSharp.Brine.LanguageSnippetRunner.csproj"
                 "../DripSharp.Brine.CoreTestRunner/DripSharp.Brine.CoreTestRunner.csproj"]))
        fixtures (:fixtures consumer-tests)]
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
  "Returns the consumer test project path relative to its product root."
  [target-contract]
  (let [{:keys [directory assembly-name]}
        (get-in target-contract [:publication :consumer-tests :project])]
    (str directory "/" assembly-name ".csproj")))

(defn- command-line
  [arguments]
  (str/join " " arguments))

(defn- commands
  [target-contract]
  (let [project (project-relative-path target-contract)]
    {:restore ["dotnet" "restore" project]
     :build ["dotnet" "build" project
             "--configuration" "Release" "--no-restore"]
     :test ["dotnet" "test" project
            "--configuration" "Release" "--no-restore" "--no-build"]}))

(defn- render-readme
  [target-contract]
  (let [family (name (:product-family target-contract))
        {:keys [restore build test]} (commands target-contract)]
    (str "# Generated consumer tests\n\n"
         "This focused public-API suite is generated from the authoritative "
         "`dripsharp/dripsharp` target contract. Do not apply durable manual "
         "fixes in a generated product repository.\n\n"
         "From a clean " family " product-repository checkout:\n\n"
         "```sh\n"
         (command-line restore) "\n"
         (command-line build) "\n"
         (command-line test) "\n"
         "```\n\n"
         "The project references only paths within this checkout. "
         "Its test host permits major-version roll-forward so a later .NET "
         "runtime can exercise an earlier-targeted product family. "
         "`SHA256SUMS` inventories every generated test file except the "
         "inventory itself.\n"
         (when (= :pkl (:target target-contract))
           (str "\nThe upstream-derived Brine suite exposes independently named "
                "xUnit rows from the pinned LanguageSnippet and Pkl.Core "
                "contracts. See [`TEST-BOUNDARY.md`](TEST-BOUNDARY.md) for "
                "the mechanical/authored/vendored boundary and "
                "`TEST-PROVENANCE.tsv` for exact hashes.\n")))))

(defn- render-notice
  [target-contract]
  (let [fixtures
        (get-in target-contract [:publication :consumer-tests :fixtures])]
    (str "# Consumer-test fixture attribution\n\n"
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

(defn emit!
  "Recreates a generated product's managed tests/ tree deterministically."
  [{:keys [workspace-root target-contract]}]
  (let [root (paths/absolute workspace-root)
        publication (:publication target-contract)
        consumer-tests (:consumer-tests publication)]
    (when-not (= :generated-repository (:kind publication))
      (fail! "Consumer tests require a generated product publication"
             {:reason :conformance-only-target
              :target (:target target-contract)}))
    (when-not consumer-tests
      (fail! "Generated product publication has no consumer-test contract"
             {:reason :missing-consumer-test-contract
              :target (:target target-contract)}))
    (when-not (some #{"tests"} (:managed-paths publication))
      (fail! "Generated consumer tests are outside managed publication paths"
             {:reason :unmanaged-consumer-tests
              :target (:target target-contract)
              :managed-paths (:managed-paths publication)}))
    (let [staging (paths/resolve-path root (:staging-path publication))
          tests-root (clean-directory! (paths/resolve-path staging "tests"))
          {:keys [directory assembly-name]} (:project consumer-tests)
          project-root (paths/resolve-path staging directory)
          project-file (paths/resolve-path
                        project-root (str assembly-name ".csproj"))]
      (write-text! project-file (render-project target-contract))
      (doseq [[_profile {:keys [source destination]}]
              (sort-by key (:assembly-tests consumer-tests))]
        (copy-file! (paths/resolve-path
                     (:target-directory target-contract) source)
                    (paths/resolve-path project-root destination)))
      (doseq [{:keys [source destination]} (:fixtures consumer-tests)]
        (copy-file! (paths/resolve-path
                     (:target-directory target-contract) source)
                    (paths/resolve-path project-root destination)))
      (write-text! (paths/resolve-path tests-root "README.md")
                   (render-readme target-contract))
      (write-text! (paths/resolve-path tests-root "NOTICE.md")
                   (render-notice target-contract))
      (when (= :pkl (:target target-contract))
        (brine-xunit/emit!
         {:workspace-root root
          :target-contract target-contract
          :tests-root tests-root}))
      (let [inventory-file (paths/resolve-path tests-root "SHA256SUMS")]
        (write-text! inventory-file
                     (render-inventory tests-root inventory-file)))
      (verify-inventory! tests-root)
      {:tests-root tests-root
       :project-file project-file
       :inventory-file (paths/resolve-path tests-root "SHA256SUMS")
       :commands (commands target-contract)})))

(defn verify!
  "Restores, builds, and runs a staged generated consumer-test project."
  [{:keys [workspace-root target-contract run-command! timeout-ms]
    :or {run-command! process/run! timeout-ms 300000}}]
  (let [root (paths/absolute workspace-root)
        run-command! (or run-command! process/run!)
        staging (paths/resolve-path
                 root (get-in target-contract
                              [:publication :staging-path]))
        project (paths/resolve-path staging
                                    (project-relative-path target-contract))
        project-root (.getParent (paths/path project))
        commands (commands target-contract)]
    (when-not (paths/regular-file? project)
      (fail! "Generated consumer test project is missing"
             {:reason :missing-generated-test-project
              :target (:target target-contract)
              :path (str project)}))
    (verify-inventory! (paths/resolve-path staging "tests"))
    (when (= :pkl (:target target-contract))
      (brine-xunit/verify-provenance!
       (paths/resolve-path staging "tests")))
    (try
      (doseq [phase [:restore :build :test]]
        (run-command! {:command (get commands phase)
                       :directory staging
                       :timeout-ms timeout-ms}))
      (finally
        (delete-tree! (paths/resolve-path project-root "bin"))
        (delete-tree! (paths/resolve-path project-root "obj"))
        (delete-build-artifacts! (paths/resolve-path staging "tests"))))
    (verify-inventory! (paths/resolve-path staging "tests"))
    {:project-file project
     :commands commands}))
