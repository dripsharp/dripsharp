(ns vibeformer.destination-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.destination :as destination])
  (:import (java.nio.file Paths)))

(deftest sample-project-mapping-drives-csproj-content
  (let [target-dir (Paths/get "/tmp/sample/target/csharp" (make-array String 0))
        source-file (.resolve target-dir "com/example/Hello.cs")
        project-map (destination/sample-project-map
                     {:sample {:sample/name "hello"}
                      :target-csharp-dir target-dir
                      :csharp-files [source-file
                                     (.resolve target-dir "Vibeformer/Runtime/JavaOptional.cs")]
                      :helper-files [{:helper/project-path "Vibeformer/Runtime/JavaOptional.cs"}]})
        content (destination/csharp-project-content project-map)]
    (is (= "hello:csharp" (:dest.project/id project-map)))
    (is (= "net8.0" (:dest.project/target-framework project-map)))
    (is (= [{:dest.item/id "hello:csharp:helper:Vibeformer/Runtime/JavaOptional.cs"
             :dest.item/kind :dest.item.kind/helper
             :dest.item/path "Vibeformer/Runtime/JavaOptional.cs"}
            {:dest.item/id "hello:csharp:compile:com/example/Hello.cs"
             :dest.item/kind :dest.item.kind/compile
             :dest.item/path "com/example/Hello.cs"}]
           (:dest.project/items project-map)))
    (is (str/includes? content "<TargetFramework>net8.0</TargetFramework>"))
    (is (str/includes? content "<Compile Include=\"com/example/Hello.cs\" />"))
    (is (str/includes? content "<Compile Include=\"Vibeformer/Runtime/JavaOptional.cs\" />"))))

(deftest research-classpath-maps-to-destination-projects-references-and-packages
  (let [classpath-report {:project/id "research-pkl"
                          :projects [{:project/path ":"
                                      :project/name "root"
                                      :project/dir "/repo"
                                      :source/roots []
                                      :dependencies []}
                                     {:project/path ":app"
                                      :project/name "app"
                                      :project/dir "/repo/app"
                                      :source/roots [{:source/kind :source.kind/resources
                                                      :source/relative-path "src/main/resources"}]
                                      :dependencies [{:dependency/configuration "implementation"
                                                      :dependency/kind :dependency.kind/project-accessor
                                                      :dependency/project ":lib:core"
                                                      :dependency/expression "projects.lib.core"}
                                                     {:dependency/configuration "implementation"
                                                      :dependency/kind :dependency.kind/version-catalog
                                                      :dependency/catalog-alias "msgpack"
                                                      :dependency/expression "libs.msgpack"}]}
                                     {:project/path ":lib:core"
                                      :project/name "core"
                                      :project/dir "/repo/lib/core"
                                      :source/roots []
                                      :dependencies []}]
                          :version-catalog {:catalog/libraries [{:catalog/alias "msgpack"
                                                                 :catalog/group "org.msgpack"
                                                                 :catalog/name "msgpack-core"
                                                                 :catalog/version "0.9.12"}]}}
        report (destination/research-mapping classpath-report
                                             {:destination/root "/tmp/research/csharp"})
        app (first (filter #(= "research-pkl.app" (:dest.project/id %))
                           (:projects report)))]
    (is (= :vibeformer.report/destination-mapping (:report/type report)))
    (is (= 3 (:projects/count report)))
    (is (= 1 (:project-references/count report)))
    (is (= 1 (:packages/count report)))
    (is (= 1 (:resources/count report)))
    (testing "project references and version-catalog packages are explicit facts"
      (is (= #{{:dest.dependency/kind :dest.dependency.kind/project-reference
                :dest.dependency/include "research-pkl.lib.core"
                :dest.dependency/path "../research-pkl.lib.core/research-pkl.lib.core.csproj"}
               {:dest.dependency/kind :dest.dependency.kind/package
                :dest.dependency/include "org.msgpack:msgpack-core"
                :dest.dependency/version "0.9.12"}}
             (set (map #(select-keys % [:dest.dependency/kind
                                         :dest.dependency/include
                                         :dest.dependency/version
                                         :dest.dependency/path])
                       (:dest.project/dependencies app))))))))
