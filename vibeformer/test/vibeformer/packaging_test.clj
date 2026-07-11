(ns vibeformer.packaging-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.packaging :as packaging])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipOutputStream]))

(def package
  {:id "Pkl.Parser"
   :version "0.0.0-development"
   :description "Disposable parser package."
   :authors "Vibeformer"
   :tags "pkl parser vibeformer"})

(def core-package
  {:id "Pkl.Core"
   :version "0.0.0-development"
   :description "Disposable core package."
   :authors "Vibeformer"
   :tags "pkl core vibeformer"})

(defn- nuspec []
  (str "<package><metadata>"
       "<id>" (:id package) "</id>"
       "<version>" (:version package) "</version>"
       "<description>" (:description package) "</description>"
       "<authors>" (:authors package) "</authors>"
       "<tags>" (:tags package) "</tags>"
       "</metadata></package>"))

(defn- core-nuspec []
  (str "<package><metadata>"
       "<id>" (:id core-package) "</id>"
       "<version>" (:version core-package) "</version>"
       "<description>" (:description core-package) "</description>"
       "<authors>" (:authors core-package) "</authors>"
       "<tags>" (:tags core-package) "</tags>"
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
