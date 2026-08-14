(ns dripsharp.product-staging-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.paths :as paths]
            [dripsharp.product-staging :as product-staging]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory "dripsharp-product-staging-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- write!
  [root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- fixture!
  []
  (let [workspace (temp-directory)
        contract (target-directory/read-target :pkl)
        staging (paths/resolve-path workspace "target/generated/brine")
        project-paths
        (mapv second
              (sort-by key
                       (get-in contract
                               [:publication :profile-projects])))
        project-roots
        (mapv #(paths/resolve-path staging %) project-paths)]
    (doseq [project-root project-roots]
      (write! project-root "src/Legal/LICENSE.txt" "Apache License\n")
      (write! project-root "src/Legal/NOTICE.txt" "Brine notice\n"))
    {:workspace workspace
     :contract contract
     :project-roots project-roots
     :generation
     {:dependency-emissions [{:project-root (first project-roots)}]
      :emission {:project-root (second project-roots)}}}))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest product-root-metadata-is-proved-derived-and-deterministic
  (let [{:keys [workspace contract generation] :as fixture} (fixture!)]
    (try
      (let [first
            (product-staging/emit!
             {:workspace-root workspace
              :target-contract contract
              :generation generation})
            readme (paths/resolve-path (:staging first) "README.md")
            first-readme (Files/readString readme)
            second
            (product-staging/emit!
             {:workspace-root workspace
              :target-contract contract
              :generation generation})]
        (is (= "Apache License\n"
               (Files/readString
                (paths/resolve-path (:staging first) "LICENSE"))))
        (is (= "Brine notice\n"
               (Files/readString
                (paths/resolve-path (:staging first) "NOTICE"))))
        (is (str/includes? first-readme "# Brine — Pkl for .NET"))
        (is (str/includes? first-readme "DripSharp.Brine.Parser"))
        (is (str/includes? first-readme
                           "Every production library has one target framework: `netstandard2.0`"))
        (is (str/includes? first-readme
                           "package consumers execute on `net10.0`"))
        (is (str/includes? first-readme
                           "does not empirically certify net48 runtime behavior"))
        (is (str/includes?
             first-readme
             "dotnet add package DripSharp.Brine --version 0.32.0-alpha.1"))
        (is (str/includes?
             first-readme
             "dotnet add package DripSharp.Brine.Parser --version 0.32.0-alpha.1"))
        (is (str/includes? first-readme "independent .NET translation"))
        (is (str/includes? first-readme
                           "f7cac257ade5775c1dfc255f4fda2eacc296e9d0"))
        (is (= first-readme (Files/readString readme)))
        (is (= (:files first) (:files second)))
        (is (= (util/sha256-file readme)
               (get-in second [:files "README.md"]))))
      (finally
        (delete-tree! (:workspace fixture))))))

(deftest product-root-metadata-rejects-inconsistent-generated-legal-files
  (let [{:keys [workspace contract generation project-roots] :as fixture}
        (fixture!)]
    (try
      (write! (second project-roots) "src/Legal/NOTICE.txt"
              "different notice\n")
      (let [result
            (failure
             #(product-staging/emit!
               {:workspace-root workspace
                :target-contract contract
                :generation generation}))]
        (is (= :inconsistent-generated-legal-file (:reason result)))
        (is (= "NOTICE" (:file result))))
      (finally
        (delete-tree! (:workspace fixture))))))

(deftest product-root-derives-notice-when-upstream-publishes-none
  (let [workspace (temp-directory)
        contract (target-directory/read-target :sqltrellis)
        staging (paths/resolve-path workspace "target/generated/sqltrellis")
        project-relative
        (get-in contract [:publication :profile-projects "sqltrellis"])
        project-root (paths/resolve-path staging project-relative)]
    (try
      (write! project-root "src/Legal/LICENSE.txt" "Apache License\n")
      (let [result
            (product-staging/emit!
             {:workspace-root workspace
              :target-contract contract
              :generation {:dependency-emissions []
                           :emission {:project-root project-root}}})
            notice
            (Files/readString (paths/resolve-path (:staging result) "NOTICE"))]
        (is (str/includes? notice "JSqlParser 5.3"))
        (is (str/includes? notice "independent mechanical .NET translation"))
        (is (str/includes? notice
                           "8a9479a05c75fcb73d0ed167a822b9b18ab7abaa")))
      (finally
        (delete-tree! workspace)))))

(deftest cleanup-removes-only-declared-project-build-artifacts
  (let [{:keys [workspace contract project-roots] :as fixture} (fixture!)
        source (write! (first project-roots) "src/Generated.cs"
                       "namespace DripSharp.Brine;\n")]
    (try
      (doseq [project-root project-roots
              directory ["bin/Release/output.dll"
                         "obj/project.assets.json"]]
        (write! project-root directory "derived\n"))
      (let [result
            (product-staging/clean-build-artifacts!
             {:workspace-root workspace
              :target-contract contract})]
        (is (= 4 (count (:removed result))))
        (is (every? #(not (paths/exists? %))
                    (for [project-root project-roots
                          directory ["bin" "obj"]]
                      (paths/resolve-path project-root directory))))
        (is (paths/regular-file? source)))
      (finally
        (delete-tree! (:workspace fixture))))))
