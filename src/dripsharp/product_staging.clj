(ns dripsharp.product-staging
  "Deterministic repository-root metadata for generated product staging.

  Generated projects remain the source of the proved legal payloads. This
  namespace validates those payloads across every project emitted in one
  generation and promotes them, plus a target-contract-derived README, to the
  disposable product-family root."
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files Path]))

(def ^:private root-files
  ["LICENSE" "NOTICE" "README.md"])

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :product-staging-emission-failed))))

(defn- delete-tree!
  [directory]
  (when (paths/exists? directory)
    (with-open [entries
                (Files/walk (paths/path directory)
                            (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- profile-records
  [target-contract staging]
  (let [publication (:publication target-contract)]
    (mapv
     (fn [[profile-id project-path]]
       (let [profile (get-in target-contract [:profiles profile-id])
             destination (get-in profile [:destination :configuration])
             output (:output destination)]
         {:profile profile-id
          :project-root (paths/absolute
                         (paths/resolve-path staging project-path))
          :source-directory (:source-directory output)
          :project-file (:project-file output)
          :assembly-name (get-in destination [:project :assembly-name])
          :target-framework (get-in destination [:project :target-framework])
          :package-title (get-in destination [:package :title])
          :package-description (get-in destination [:package :description])
          :repository-path project-path}))
     (sort-by key (:profile-projects publication)))))

(defn- emitted-project-roots
  [generation]
  (->> (concat (:dependency-emissions generation)
               [(:emission generation)])
       (keep :project-root)
       (mapv paths/absolute)))

(defn- selected-records!
  [records generation staging]
  (let [by-root (into {} (map (juxt :project-root identity)) records)
        emitted (emitted-project-roots generation)]
    (when-not (seq emitted)
      (fail! "Generated product staging has no emitted projects"
             {:reason :missing-emitted-projects}))
    (when-not (= (count emitted) (count (distinct emitted)))
      (fail! "Generated product staging repeats an emitted project"
             {:reason :duplicate-emitted-project
              :projects (mapv str emitted)}))
    (mapv
     (fn [project-root]
       (when-not (.startsWith ^Path project-root ^Path staging)
         (fail! "Generated product project escapes its staging root"
                {:reason :project-root-escape
                 :staging (str staging)
                 :project-root (str project-root)}))
       (or
        (get by-root project-root)
        (fail! "Generated product project is not declared for publication"
               {:reason :undeclared-emitted-project
                :project-root (str project-root)
                :declared (mapv (comp str :project-root) records)})))
     emitted)))

(defn- generated-legal-content!
  [selected file-name]
  (let [entries
        (mapv
         (fn [{:keys [profile project-root source-directory]}]
           (let [file (paths/resolve-path project-root source-directory
                                          "Legal" (str file-name ".txt"))]
             (when-not (paths/regular-file? file)
               (fail! "Generated product legal file is missing"
                      {:reason :missing-generated-legal-file
                       :profile profile
                       :path (str file)}))
             {:profile profile
              :path file
              :sha256 (util/sha256-file file)
              :content (Files/readString file StandardCharsets/UTF_8)}))
         selected)
        hashes (set (map :sha256 entries))]
    (when-not (= 1 (count hashes))
      (fail! "Generated product projects disagree on repository legal content"
             {:reason :inconsistent-generated-legal-file
              :file file-name
              :projects
              (mapv #(select-keys % [:profile :path :sha256]) entries)}))
    (:content (first entries))))

(defn- project-line
  [{:keys [assembly-name package-title target-framework repository-path
           project-file]}]
  (str "- [`" assembly-name "`](" repository-path "/" project-file ") — "
       package-title " (`" target-framework "`)\n"))

(defn- render-readme
  [target-contract records]
  (let [primary (first (sort-by (comp count :assembly-name) records))
        publication (:publication target-contract)
        upstream (get-in target-contract [:baseline :record :upstream])
        tests (get-in publication [:consumer-tests :project])
        test-project (str (:directory tests) "/" (:assembly-name tests)
                          ".csproj")
        source-url "https://github.com/dripsharp/dripsharp"
        upstream-url (str/replace (:repository upstream) #"[.]git$" "")
        revision (:revision upstream)]
    (str "# " (:package-title primary) "\n\n"
         (:package-description primary) "\n\n"
         "This is a generated publication repository. Durable source, "
         "translation, runtime, and test changes belong in "
         "[`dripsharp/dripsharp`](" source-url ") and must be regenerated; "
         "do not apply durable manual fixes to generated C# or generated "
         "tests here.\n\n"
         "## Projects\n\n"
         (apply str (map project-line
                         (sort-by :assembly-name records)))
         "\n## Build and test\n\n"
         "From a clean checkout:\n\n"
         "```sh\n"
         "dotnet restore " test-project "\n"
         "dotnet build " test-project
         " --configuration Release --no-restore\n"
         "dotnet test " test-project
         " --configuration Release --no-restore --no-build\n"
         "```\n\n"
         "The focused suite references only this checkout. See "
         "[`tests/README.md`](tests/README.md) for its generated inventory "
         "and execution contract.\n\n"
         "## Upstream\n\n"
         "This generated family translates " (:name upstream) " "
         (:version upstream) " at commit [`" revision "`]("
         upstream-url "/tree/" revision "). Upstream identity and attribution "
         "are preserved; this independent .NET translation is not developed, "
         "endorsed, or supported by the upstream project.\n\n"
         "## License and notices\n\n"
         "See [`LICENSE`](LICENSE) for the license and [`NOTICE`](NOTICE) for "
         "upstream attribution and the DripSharp translation notice.\n")))

(defn emit!
  "Writes the declared repository-root metadata into generated staging."
  [{:keys [workspace-root target-contract generation]}]
  (let [root (paths/absolute workspace-root)
        publication (:publication target-contract)
        managed (set (:managed-paths publication))
        staging (paths/absolute
                 (paths/resolve-path root (:staging-path publication)))]
    (when-not (= :generated-repository (:kind publication))
      (fail! "Product staging metadata requires a generated repository"
             {:reason :conformance-only-target
              :target (:target target-contract)}))
    (when-not (every? managed root-files)
      (fail! "Generated repository does not manage its root metadata"
             {:reason :unmanaged-root-metadata
              :required root-files
              :managed (:managed-paths publication)}))
    (let [records (profile-records target-contract staging)
          selected (selected-records! records generation staging)
          content
          {"LICENSE" (generated-legal-content! selected "LICENSE")
           "NOTICE" (generated-legal-content! selected "NOTICE")
           "README.md" (render-readme target-contract records)}]
      (doseq [[relative text] content]
        (util/write-text! (paths/resolve-path staging relative) text))
      {:staging staging
       :files
       (into
        (sorted-map)
        (map
         (fn [relative]
           [relative
            (util/sha256-file (paths/resolve-path staging relative))]))
        root-files)})))

(defn clean-build-artifacts!
  "Removes only transient bin/ and obj/ trees below declared product projects."
  [{:keys [workspace-root target-contract]}]
  (let [root (paths/absolute workspace-root)
        publication (:publication target-contract)
        staging (paths/absolute
                 (paths/resolve-path root (:staging-path publication)))
        records (profile-records target-contract staging)
        candidates
        (for [{:keys [project-root]} records
              name ["bin" "obj"]]
          (paths/resolve-path project-root name))
        existing (filterv paths/exists? candidates)]
    (when-not (= :generated-repository (:kind publication))
      (fail! "Build-artifact cleanup requires a generated repository"
             {:reason :conformance-only-target
              :target (:target target-contract)}))
    (doseq [artifact existing]
      (when-not (.startsWith ^Path (paths/absolute artifact) ^Path staging)
        (fail! "Generated build artifact escapes product staging"
               {:reason :build-artifact-escape
                :staging (str staging)
                :artifact (str artifact)}))
      (delete-tree! artifact))
    {:removed
     (mapv #(str/replace
             (str (.relativize ^Path staging ^Path (paths/absolute %)))
             "\\" "/")
           existing)}))
