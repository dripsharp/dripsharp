(ns vibeformer.packaging-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.packaging :as packaging])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipOutputStream]))

(def package
  {:id "Pkl.Parser"
   :version "0.0.0-development"
   :title "Pkl parser for .NET"
   :description "Disposable parser package."
   :authors "Vibeformer"
   :tags "pkl parser vibeformer"
   :project-url "https://example.test/pkl"
   :repository-url "https://example.test/pkl.git"
   :repository-type "git"})

(def core-package
  {:id "Pkl.Core"
   :version "0.0.0-development"
   :title "Pkl for .NET"
   :description "Disposable core package."
   :authors "Vibeformer"
   :tags "pkl core vibeformer"
   :project-url "https://example.test/pkl"
   :repository-url "https://example.test/pkl.git"
   :repository-type "git"})

(defn- nuspec []
  (str "<package><metadata>"
       "<id>" (:id package) "</id>"
       "<version>" (:version package) "</version>"
       "<title>" (:title package) "</title>"
       "<description>" (:description package) "</description>"
       "<authors>" (:authors package) "</authors>"
       "<tags>" (:tags package) "</tags>"
       "<projectUrl>" (:project-url package) "</projectUrl>"
       "<repository type=\"" (:repository-type package) "\" url=\""
       (:repository-url package) "\" />"
       "</metadata></package>"))

(defn- core-nuspec []
  (str "<package><metadata>"
       "<id>" (:id core-package) "</id>"
       "<version>" (:version core-package) "</version>"
       "<title>" (:title core-package) "</title>"
       "<description>" (:description core-package) "</description>"
       "<authors>" (:authors core-package) "</authors>"
       "<tags>" (:tags core-package) "</tags>"
       "<projectUrl>" (:project-url core-package) "</projectUrl>"
       "<repository type=\"" (:repository-type core-package) "\" url=\""
       (:repository-url core-package) "\" />"
       "<dependencies><group targetFramework=\"net8.0\">"
       "<dependency id=\"Pkl.Parser\" version=\"0.0.0-development\" exclude=\"Build,Analyzers\" />"
       "</group></dependencies>"
       "</metadata></package>"))

(defn- archive! [entries]
  (let [directory (Files/createTempDirectory "vibeformer-package-test"
                                              (make-array FileAttribute 0))
        archive (.resolve directory "Pkl.Parser.0.0.0-development.nupkg")]
    (with-open [output (ZipOutputStream. (Files/newOutputStream archive
                                                               (make-array OpenOption 0)))]
      (doseq [[name contents] entries]
        (.putNextEntry output (ZipEntry. name))
        (.write output (.getBytes contents StandardCharsets/UTF_8))
        (.closeEntry output)))
    archive))

(defn- write-file! [^Path file contents]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file contents (make-array OpenOption 0))
  file)

(deftest package-inspection-requires-assembly-and-metadata-without-source-internals
  (let [artifact (archive! {"Pkl.Parser.nuspec" (nuspec)
                            "lib/net8.0/Pkl.Parser.dll" "assembly"})
        inspection (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")]
    (is (= "lib/net8.0/Pkl.Parser.dll" (:assembly-entry inspection)))
    (is (= 2 (count (:entries inspection))))))

(deftest package-inspection-rejects-generated-source
  (let [artifact (archive! {"Pkl.Parser.nuspec" (nuspec)
                            "lib/net8.0/Pkl.Parser.dll" "assembly"
                            "src/Parser.cs" "generated source"})
        error (try
                (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (testing "translator and generated-source implementation details cannot ship"
      (is (= :package-consumption-failed (:kind (ex-data error))))
      (is (= ["src/Parser.cs"] (:forbidden (ex-data error)))))))

(deftest package-inspection-pins-dependency-closure-without-bundling-it
  (let [artifact (archive! {"Pkl.Core.nuspec" (core-nuspec)
                            "lib/net8.0/Pkl.Core.dll" "assembly"})
        renamed (.resolve (.getParent artifact) "Pkl.Core.0.0.0-development.nupkg")
        _ (Files/move artifact renamed (make-array java.nio.file.CopyOption 0))
        inspection (packaging/inspect-package!
                    renamed core-package "net8.0" "Pkl.Core"
                    [{:id "Pkl.Parser" :version "0.0.0-development"}])]
    (is (= [{:id "Pkl.Parser" :version "0.0.0-development"}]
           (:dependencies inspection)))))

(deftest package-inspection-rejects-a-bundled-project-dependency-assembly
  (let [artifact (archive! {"Pkl.Core.nuspec" (core-nuspec)
                            "lib/net8.0/Pkl.Core.dll" "assembly"
                            "lib/net8.0/Pkl.Parser.dll" "leaked dependency"})
        renamed (.resolve (.getParent artifact) "Pkl.Core.0.0.0-development.nupkg")
        _ (Files/move artifact renamed (make-array java.nio.file.CopyOption 0))
        error (try
                (packaging/inspect-package!
                 renamed core-package "net8.0" "Pkl.Core"
                 [{:id "Pkl.Parser" :version "0.0.0-development"}])
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (= ["lib/net8.0/Pkl.Core.dll" "lib/net8.0/Pkl.Parser.dll"]
           (:assemblies (ex-data error))))))

(deftest package-inspection-rejects-assemblies-outside-the-configured-library-path
  (let [artifact (archive! {"Pkl.Parser.nuspec" (nuspec)
                            "lib/net8.0/Pkl.Parser.dll" "assembly"
                            "lib/net9.0/Pkl.Parser.dll" "other target"
                            "ref/net8.0/Pkl.Parser.dll" "reference assembly"})
        error (try
                (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (= ["lib/net8.0/Pkl.Parser.dll"
            "lib/net9.0/Pkl.Parser.dll"
            "ref/net8.0/Pkl.Parser.dll"]
           (:assemblies (ex-data error))))))

(deftest package-inspection-requires-one-exact-repository-element
  (let [misleading-nuspec (-> (nuspec)
                              (str/replace "<package>"
                                           (str "<package type=\"" (:repository-type package)
                                                "\" url=\"" (:repository-url package) "\">"))
                              (str/replace (str "<repository type=\""
                                                (:repository-type package) "\" url=\""
                                                (:repository-url package) "\" />")
                                           "<repository type=\"svn\" url=\"https://wrong.test/repo\" />"))
        artifact (archive! {"Pkl.Parser.nuspec" misleading-nuspec
                            "lib/net8.0/Pkl.Parser.dll" "assembly"})
        error (try
                (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (= {"type" (:repository-type package)
            "url" (:repository-url package)}
           (:expected (ex-data error))))))

(deftest independent-consumer-dependency-proof-pins-package-only-closure
  (let [root (Files/createTempDirectory "vibeformer-consumer-proof"
                                         (make-array FileAttribute 0))
        project (write-file!
                 (.resolve root "Consumer.csproj")
                 (str "<Project><ItemGroup>"
                      "<PackageReference Include=\"Pkl.Core\" Version=\"0.0.0-development\" />"
                      "</ItemGroup></Project>"))
        assets (write-file!
                (.resolve root "obj/project.assets.json")
                "{\"libraries\":{\"Pkl.Core/0.0.0-development\":{\"type\":\"package\"},\"Pkl.Parser/0.0.0-development\":{\"type\":\"package\"}}}")
        packages (.resolve root "packages")
        identities [{:id "Pkl.Parser" :version "0.0.0-development" :sha256 "parser"}
                    {:id "Pkl.Core" :version "0.0.0-development" :sha256 "core"}]
        _ (doseq [{:keys [id version]} identities]
            (let [lower (.toLowerCase ^String id)]
              (write-file! (.resolve packages
                                     (str lower "/" version "/" lower "." version ".nupkg"))
                           "package")))
        proof (packaging/inspect-consumer-dependencies!
               project assets packages (second identities) identities)]
    (is (= ["Pkl.Core" "0.0.0-development"] (:package-reference proof)))
    (is (= identities (:packages proof)))))

(deftest independent-consumer-dependency-proof-rejects-project-reference
  (let [root (Files/createTempDirectory "vibeformer-consumer-leak"
                                         (make-array FileAttribute 0))
        project (write-file!
                 (.resolve root "Consumer.csproj")
                 (str "<Project><ItemGroup>"
                      "<PackageReference Include=\"Pkl.Core\" Version=\"0.0.0-development\" />"
                      "<ProjectReference Include=\"../generated/Pkl.Core.csproj\" />"
                      "</ItemGroup></Project>"))
        error (try
                (packaging/inspect-consumer-dependencies!
                 project (.resolve root "obj/project.assets.json") (.resolve root "packages")
                 {:id "Pkl.Core" :version "0.0.0-development"} [])
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (seq (:forbidden (ex-data error))))))
