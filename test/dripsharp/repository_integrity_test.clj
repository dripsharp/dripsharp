(ns dripsharp.repository-integrity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.repository-integrity :as repository-integrity])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:private representative-build-artifacts
  ["targets/sqltrellis/validation/package-consumer/bin/Release/net10.0/DripSharp.SqlTrellis.dll"
   "targets/sqltrellis/validation/package-consumer/bin/Release/net10.0/SqlTrellis.PackageConsumer.pdb"
   "targets/sqltrellis/validation/package-consumer/bin/Release/net10.0/SqlTrellis.PackageConsumer.runtimeconfig.json"
   "targets/sqltrellis/validation/package-consumer/obj/project.assets.json"
   "targets/sqltrellis/validation/package-consumer/obj/SqlTrellis.PackageConsumer.csproj.nuget.g.props"
   "targets/sqltrellis/validation/package-consumer/obj/Release/net10.0/SqlTrellis.PackageConsumer.GeneratedMSBuildEditorConfig.editorconfig"])

(defn- temp-directory
  []
  (Files/createTempDirectory "dripsharp-repository-integrity-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root paths/no-links)
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- write!
  [^Path root relative content]
  (let [file (.resolve root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- git!
  [directory & command]
  (process/run! {:command (into ["git"] command)
                 :directory directory}))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest durable-superproject-target-inputs-have-no-build-artifacts
  (is (= {:tracked-target-build-artifacts []}
         (repository-integrity/verify-target-inputs!
          (paths/workspace-root)))))

(deftest target-validation-build-output-is-ignored-and-rejected-if-force-added
  (let [workspace (temp-directory)
        repository-root (paths/workspace-root)]
    (try
      (git! workspace "init" "-b" "master")
      (write! workspace ".gitignore"
              (slurp (str (paths/resolve-path repository-root ".gitignore"))))
      (write! workspace
              "targets/sqltrellis/validation/package-consumer/SqlTrellis.PackageConsumer.csproj"
              "<Project Sdk=\"Microsoft.NET.Sdk\" />\n")
      (git! workspace "add" "--all")
      (doseq [artifact representative-build-artifacts]
        (write! workspace artifact "ordinary build output\n"))
      (testing "root rules ignore production outputs and NuGet/MSBuild intermediates"
        (doseq [artifact representative-build-artifacts]
          (let [result (git! workspace "check-ignore" "-v" "--no-index"
                             "--" artifact)]
            (is (str/includes? (:output result) artifact)))))
      (let [force-added [(first representative-build-artifacts)
                         (nth representative-build-artifacts 3)]]
        (apply git! workspace "add" "--force" "--" force-added)
        (let [result
              (failure
               #(repository-integrity/verify-target-inputs! workspace))]
          (is (= :tracked-target-build-artifacts (:reason result)))
          (is (= (vec (sort force-added)) (:paths result)))))
      (finally
        (delete-tree! workspace)))))
