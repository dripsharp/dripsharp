(ns dripsharp.nuget-metadata-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.baseline :as baseline]
            [dripsharp.java-project :as java-project]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:private exact-product-commit
  "0123456789abcdef0123456789abcdef01234567")

(def ^:private base-nuspec-namespace
  "http://schemas.microsoft.com/packaging/2012/06/nuspec.xsd")

(def ^:private dependency-nuspec-namespace
  "http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd")

(defn- xml-escape
  [value]
  (str/escape (str value)
              {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"}))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- package-dependencies
  [baseline-record destination]
  (into
   (mapv (fn [id]
           {:id id
            :version (get-in baseline-record [:packages id :version])})
         (:package-dependencies destination))
   (->> (:runtime-packages destination)
        (sort-by :id)
        (map #(select-keys % [:id :version])))))

(defn- legal-content
  [workspace destination {:keys [kind source]}]
  (let [content (Files/readAllBytes (paths/resolve-path workspace source))]
    (if (and (= :notice kind) (:notice-appendix destination))
      (byte-array
       (concat content
               (.getBytes ^String (:notice-appendix destination)
                          StandardCharsets/UTF_8)))
      content)))

(defn- production-package-contracts
  []
  (let [workspace (paths/workspace-root)]
    (mapv
     identity
     (for [target [:pkl :pdfcube :sqltrellis]
           :let [contract (target-directory/read-target workspace target)
                 baseline-record (get-in contract [:baseline :record])]
           [_ profile] (sort-by key (:profiles contract))
           :let [destination
                 (baseline/hydrate-destination
                  workspace
                  (get-in profile [:destination :configuration]))
                 package
                 (-> (:package destination)
                     (dissoc :repository-commit-policy)
                     (assoc :repository-commit exact-product-commit))
                 legal
                 (mapv
                  (fn [{:keys [kind package-path sha256] :as file}]
                    (let [content (legal-content workspace destination file)]
                      (is (= sha256 (util/sha256-bytes content))
                          (str (:id package) " has stale pinned " package-path))
                      {:kind kind :path package-path :sha256 sha256
                       :content content}))
                  (:legal-files destination))
                 readme-content
                 (str "# " (:id package) "\n\n"
                      (:description package) "\n\n"
                      "Install: `dotnet add package " (:id package)
                      " --version " (:version package) "`.\n")]]
       {:target target
        :publication (:nuget (:publication contract))
        :configured-package (:package destination)
        :mechanical-source (:mechanical-source destination)
        :package package
        :assembly-name (get-in destination [:project :assembly-name])
        :target-framework (get-in destination [:project :target-framework])
        :dependencies (package-dependencies baseline-record destination)
        :files (conj legal
                     {:kind :readme :path (:readme package)
                      :sha256 (util/sha256-text readme-content)
                      :content (.getBytes readme-content
                                          StandardCharsets/UTF_8)})
        :project (java-project/project-text destination [])}))))

(defn- nuspec
  [{:keys [package target-framework dependencies]}]
  (let [namespace (if (seq dependencies)
                    dependency-nuspec-namespace
                    base-nuspec-namespace)]
    (str
     "<package xmlns=\"" namespace "\"><metadata>"
     "<id>" (xml-escape (:id package)) "</id>"
     "<version>" (xml-escape (:version package)) "</version>"
     "<title>" (xml-escape (:title package)) "</title>"
     "<description>" (xml-escape (:description package)) "</description>"
     "<authors>" (xml-escape (:authors package)) "</authors>"
     "<tags>" (xml-escape (:tags package)) "</tags>"
     "<projectUrl>" (xml-escape (:project-url package)) "</projectUrl>"
     "<readme>" (xml-escape (:readme package)) "</readme>"
     "<copyright>" (xml-escape (:copyright package)) "</copyright>"
     "<license type=\"file\">LICENSE.txt</license>"
     "<licenseUrl>https://aka.ms/deprecateLicenseUrl</licenseUrl>"
     "<repository type=\"" (xml-escape (:repository-type package))
     "\" url=\"" (xml-escape (:repository-url package))
     "\" commit=\"" (:repository-commit package) "\" />"
     "<dependencies><group targetFramework=\"" target-framework "\">"
     (apply str
            (for [{:keys [id version]} dependencies]
              (str "<dependency id=\"" (xml-escape id)
                   "\" version=\"" (xml-escape version)
                   "\" exclude=\"Build,Analyzers\" />")))
     "</group></dependencies>"
     "</metadata></package>")))

(defn- content-types
  [files]
  (let [extensions (->> files
                        (map :path)
                        (keep #(second (re-find #"[.]([^./]+)$" %)))
                        set
                        sort)]
    (str
     "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
     "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\" />"
     "<Default Extension=\"psmdcp\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\" />"
     "<Default Extension=\"dll\" ContentType=\"application/octet\" />"
     "<Default Extension=\"nuspec\" ContentType=\"application/octet\" />"
     (apply str
            (for [extension extensions]
              (str "<Default Extension=\"" extension
                   "\" ContentType=\"application/octet\" />")))
     "</Types>")))

(defn- relationships
  [nuspec-name]
  (str
   "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
   "<Relationship Type=\"http://schemas.microsoft.com/packaging/2010/07/manifest\" Target=\"/"
   nuspec-name "\" Id=\"R1\" />"
   "<Relationship Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" "
   "Target=\"/package/services/metadata/core-properties/core-properties.psmdcp\" Id=\"R2\" />"
   "</Relationships>"))

(defn- core-properties
  [{:keys [id version description authors tags]}]
  (str
   "<coreProperties xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
   "xmlns:dcterms=\"http://purl.org/dc/terms/\" "
   "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
   "xmlns=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\">"
   "<dc:creator>" (xml-escape authors) "</dc:creator>"
   "<dc:description>" (xml-escape description) "</dc:description>"
   "<dc:identifier>" (xml-escape id) "</dc:identifier>"
   "<version>" (xml-escape version) "</version>"
   "<keywords>" (xml-escape tags) "</keywords>"
   "<lastModifiedBy>NuGet test writer</lastModifiedBy>"
   "</coreProperties>"))

(defn- package-archive!
  [{:keys [package assembly-name target-framework files] :as contract}]
  (let [directory (Files/createTempDirectory
                   "dripsharp-nuget-metadata-"
                   (make-array FileAttribute 0))
        artifact (.resolve directory (str (:id package) ".nupkg"))
        nuspec-name (str (:id package) ".nuspec")
        entries
        (into
         {nuspec-name (nuspec contract)
          (str "lib/" target-framework "/" assembly-name ".dll") "assembly"
          "[Content_Types].xml" (content-types files)
          "_rels/.rels" (relationships nuspec-name)
          "package/services/metadata/core-properties/core-properties.psmdcp"
          (core-properties package)}
         (map (juxt :path :content))
         files)]
    (with-open [output
                (ZipOutputStream.
                 (Files/newOutputStream artifact (make-array OpenOption 0)))]
      (doseq [[name content] entries
              :let [bytes (if (string? content)
                            (.getBytes ^String content StandardCharsets/UTF_8)
                            content)]]
        (.putNextEntry output (ZipEntry. name))
        (.write output ^bytes bytes)
        (.closeEntry output)))
    artifact))

(deftest all-production-packages-emit-and-inspect-exact-gallery-metadata
  (let [contracts (production-package-contracts)]
    (is (= #{"DripSharp.Brine.Parser" "DripSharp.Brine"
             "DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"
             "DripSharp.PdfCarton.Xmp" "DripSharp.PdfCarton"
             "DripSharp.PdfCarton.Preflight" "DripSharp.SqlTrellis"}
           (set (map #(get-in % [:package :id]) contracts))))
    (is (= #{"Isak Sky"}
           (set (map #(get-in % [:publication :authors]) contracts))))
    (is (= #{"pkl-4m8d.1"}
           (set (map #(get-in % [:publication :decision]) contracts))))
    (is (= #{"DripSharp"}
           (set (map #(get-in % [:publication :owner-organization])
                     contracts))))
    (is (= #{"isaksky"}
           (set (map #(get-in % [:publication :publishing-account])
                     contracts))))
    (is (= #{"https://api.nuget.org/v3/index.json"}
           (set (map #(get-in % [:publication :source]) contracts))))
    (is (= #{:deferred}
           (set (map #(get-in % [:publication :icon :status]) contracts))))
    (is (= 1
           (count
            (set (map #(get-in % [:publication :icon :reason]) contracts)))))
    (doseq [{:keys [target package configured-package mechanical-source
                    assembly-name target-framework dependencies files project]
             :as contract}
            contracts]
      (testing (:id package)
        (is (= :exact-clean-generated-product-commit
               (:repository-commit-policy configured-package)))
        (is (not (contains? configured-package :repository-commit)))
        (is (not= (:repository-url configured-package)
                  (:repository mechanical-source)))
        (is (str/includes? (:description package) "independent"))
        (is (case target
              :pkl (str/includes? (:description package)
                                  "not affiliated with, endorsed by, or sponsored by Apple Inc.")
              :pdfcube (str/includes? (:description package)
                                      "not affiliated with, endorsed by, or sponsored by the Apache Software Foundation.")
              :sqltrellis (str/includes? (:description package)
                                         "not affiliated with or endorsed by the JSqlParser project")))
        (is (every? (set (map :kind files)) [:license :notice :readme]))
        (when (= "DripSharp.PdfCarton" (:id package))
          (is (= 3 (count (filter #(str/starts-with? (:path %) "THIRD-PARTY/")
                                  files)))))
        (is (str/includes? project "<Authors>Isak Sky</Authors>"))
        (is (str/includes? project "<PackageReadmeFile>README.md</PackageReadmeFile>"))
        (is (str/includes? project
                           "<None Include=\"../../README.md\" Pack=\"true\" PackagePath=\"/\" />"))
        (let [artifact (package-archive! contract)
              expected-files (mapv #(dissoc % :content) files)
              inspection
              (packaging/inspect-package!
               artifact package target-framework assembly-name
               dependencies expected-files)
              metadata-drift
              (failure
               #(packaging/inspect-package!
                 artifact (assoc package :authors "Metadata Drift")
                 target-framework assembly-name dependencies expected-files))
              payload-drift
              (failure
               #(packaging/inspect-package!
                 artifact package target-framework assembly-name dependencies
                 (update-in expected-files [(dec (count expected-files)) :sha256]
                            (constantly (apply str (repeat 64 "0"))))))]
          (is (= expected-files (:package-files inspection)))
          (is (= :package-consumption-failed (:kind metadata-drift)))
          (is (= :package-consumption-failed (:kind payload-drift))))))))
