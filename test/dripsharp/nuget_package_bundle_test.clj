(ns dripsharp.nuget-package-bundle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.nuget-package-bundle :as bundle]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(defn- zip-file!
  [^Path file entries]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (with-open [output
              (ZipOutputStream.
               (Files/newOutputStream file (make-array OpenOption 0)))]
    (doseq [[name value] (sort-by key entries)]
      (.putNextEntry output (doto (ZipEntry. name) (.setTime 0)))
      (.write output (.getBytes (str value) StandardCharsets/UTF_8))
      (.closeEntry output)))
  file)

(defn- nuspec
  [id dependencies]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
       "<package xmlns=\"http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd\">\n"
       "  <metadata>\n"
       "    <id>" id "</id>\n"
       "    <version>1.2.3-alpha.1</version>\n"
       "    <dependencies>\n"
       "      <group targetFramework=\"net10.0\">\n"
       (apply str
              (for [{:keys [id version]} dependencies]
                (str "        <dependency id=\"" id "\" version=\""
                     version "\" exclude=\"Build,Analyzers\" />\n")))
       "      </group>\n"
       "    </dependencies>\n"
       "  </metadata>\n"
       "</package>"))

(defn- component!
  [feed id dependencies]
  (let [version "1.2.3-alpha.1"
        package-file (str id "." version ".nupkg")
        symbol-file (str id "." version ".snupkg")
        nuspec-entry (str id ".nuspec")
        assembly-entry (str "lib/net10.0/" id ".dll")
        pdb-entry (str "lib/net10.0/" id ".pdb")
        artifact
        (zip-file! (paths/resolve-path feed package-file)
                   {nuspec-entry (nuspec id dependencies)
                    assembly-entry (str id "|assembly")})
        symbol-artifact
        (zip-file! (paths/resolve-path feed symbol-file)
                   {nuspec-entry (nuspec id dependencies)
                    pdb-entry (str id "|pdb")})]
    {:profile id
     :artifact artifact
     :destination {:project {:target-framework "net10.0"}}
     :identity {:id id :version version :file package-file
                :sha256 (util/sha256-file artifact)}
     :inspection {:assembly-entry assembly-entry
                  :dependencies dependencies}
     :symbol-artifact symbol-artifact
     :symbol {:id id :version version :file symbol-file
              :sha256 (util/sha256-file symbol-artifact)}
     :symbol-inspection {:pdb-entry pdb-entry
                         :pdb-sha256
                         (util/sha256-text (str id "|pdb"))}}))

(defn- archive-names
  [artifact]
  (with-open [archive (ZipFile. (str artifact))]
    (->> (enumeration-seq (.entries archive))
         (remove #(.isDirectory ^ZipEntry %))
         (mapv #(.getName ^ZipEntry %)))))

(defn- archive-text
  [artifact entry]
  (with-open [archive (ZipFile. (str artifact))]
    (with-open [input (.getInputStream archive (.getEntry archive entry))]
      (String. (.readAllBytes input) StandardCharsets/UTF_8))))

(deftest component-assemblies-and-symbols-become-one-public-package
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-package-bundle-test-"
              (make-array FileAttribute 0))
        feed (doto (paths/resolve-path root "feed")
               (Files/createDirectories (make-array FileAttribute 0)))
        logging {:id "Example.Logging" :version "9.0.0"}
        support (component! feed "Example.Support" [logging])
        core (component! feed "Example.Core"
                         [{:id "Example.Support" :version "1.2.3-alpha.1"}
                          logging])
        consumer-call (atom nil)
        result
        (bundle/bundle!
         {:workspace-root root
          :plan
          {:bundle
           {:package-id "Example.Core"
            :profile "complete"
            :component-package-ids ["Example.Support" "Example.Core"]}
           :contract
           {:publication
            {:nuget
             {:bundle
              {:package-id "Example.Core"
               :profile "complete"
               :component-package-ids
               ["Example.Support" "Example.Core"]}}}
            :profiles
            {"complete"
             {:destination
              {:configuration
               {:project {:target-framework "net10.0"}
                :package-consumer
                {:strategy :compile-only
                 :project-file "Consumer.csproj"
                 :compile-types ["Example.Core.Type"]
                 :success-message "passed"}}}}}}}
          :package-result
          {:packages [support core]
           :external-packages
           [{:id "Example.Logging" :version "9.0.0"
             :sha256 (util/sha256-text "external")}]
           :feed feed
           :proof-root (paths/resolve-path root "proof")}
          :consumer-fn
          (fn [options]
            (reset! consumer-call options)
            {:dependency-proof :passed :run {:exit 0}})})
        package (first (:packages result))
        package-names (archive-names (:artifact package))
        symbol-names (archive-names (:symbol-artifact package))
        package-nuspec (archive-text (:artifact package)
                                     "Example.Core.nuspec")]
    (is (= "Example.Core" (get-in package [:identity :id])))
    (is (= ["lib/net10.0/Example.Support.dll"
            "lib/net10.0/Example.Core.dll"]
           (get-in package [:inspection :assembly-entries])))
    (is (= #{"lib/net10.0/Example.Support.dll"
             "lib/net10.0/Example.Core.dll"}
           (set (filter #(str/ends-with? % ".dll") package-names))))
    (is (= #{"lib/net10.0/Example.Support.pdb"
             "lib/net10.0/Example.Core.pdb"}
           (set (filter #(str/ends-with? % ".pdb") symbol-names))))
    (is (str/includes? package-nuspec "Example.Logging"))
    (is (not (str/includes? package-nuspec
                            "<dependency id=\"Example.Support\"")))
    (is (= [{:id "Example.Core" :version "1.2.3-alpha.1"}]
           (:selected-packages @consumer-call)))
    (is (= #{{:id "Example.Core" :version "1.2.3-alpha.1"}
             {:id "Example.Logging" :version "9.0.0"}}
           (set (:expected-packages @consumer-call))))))
