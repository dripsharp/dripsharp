(ns dripsharp.packaging-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.authorship :as authorship]
            [dripsharp.java-project :as java-project]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path Paths StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util.zip ZipEntry ZipOutputStream]))

(def package
  {:id "Pkl.Parser"
   :version "0.0.0-development"
   :title "Pkl parser for .NET"
   :description "Disposable parser package."
   :authors "DripSharp"
   :tags "pkl parser dripsharp"
   :license-expression "Apache-2.0"
   :project-url "https://example.test/pkl"
   :repository-url "https://example.test/pkl.git"
   :repository-type "git"
   :repository-commit "0123456789abcdef0123456789abcdef01234567"})

(def core-package
  {:id "Pkl.Core"
   :version "0.0.0-development"
   :title "Pkl for .NET"
   :description "Disposable core package."
   :authors "DripSharp"
   :tags "pkl core dripsharp"
   :project-url "https://example.test/pkl"
   :repository-url "https://example.test/pkl.git"
   :repository-type "git"
   :repository-commit "0123456789abcdef0123456789abcdef01234567"})

(def nuspec-namespace
  "http://schemas.microsoft.com/packaging/2012/06/nuspec.xsd")

(def dependency-nuspec-namespace
  "http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd")

(def dc-elements-namespace
  "http://purl.org/dc/elements/1.1/")

(defn- nuspec []
  (str "<package xmlns=\"" nuspec-namespace "\"><metadata>"
       "<id>" (:id package) "</id>"
       "<version>" (:version package) "</version>"
       "<title>" (:title package) "</title>"
       "<description>" (:description package) "</description>"
       "<authors>" (:authors package) "</authors>"
       "<tags>" (:tags package) "</tags>"
       "<license type=\"expression\">" (:license-expression package) "</license>"
       "<licenseUrl>https://licenses.nuget.org/" (:license-expression package)
       "</licenseUrl>"
       "<projectUrl>" (:project-url package) "</projectUrl>"
       "<repository type=\"" (:repository-type package) "\" url=\""
       (:repository-url package) "\" commit=\"0123456789abcdef0123456789abcdef01234567\" />"
       "<dependencies><group targetFramework=\"net8.0\" /></dependencies>"
       "</metadata></package>"))

(defn- core-nuspec []
  (str "<package xmlns=\"" dependency-nuspec-namespace "\"><metadata>"
       "<id>" (:id core-package) "</id>"
       "<version>" (:version core-package) "</version>"
       "<title>" (:title core-package) "</title>"
       "<description>" (:description core-package) "</description>"
       "<authors>" (:authors core-package) "</authors>"
       "<tags>" (:tags core-package) "</tags>"
       "<projectUrl>" (:project-url core-package) "</projectUrl>"
       "<repository type=\"" (:repository-type core-package) "\" url=\""
       (:repository-url core-package) "\" commit=\"0123456789abcdef0123456789abcdef01234567\" />"
       "<dependencies><group targetFramework=\"net8.0\">"
       "<dependency id=\"Pkl.Parser\" version=\"0.0.0-development\" exclude=\"Build,Analyzers\" />"
       "</group></dependencies>"
       "</metadata></package>"))

(defn- archive! [entries]
  (let [directory (Files/createTempDirectory "dripsharp-package-test"
                                             (make-array FileAttribute 0))
        archive (.resolve directory "Pkl.Parser.0.0.0-development.nupkg")]
    (with-open [output (ZipOutputStream. (Files/newOutputStream archive
                                                                (make-array OpenOption 0)))]
      (doseq [[name contents] entries]
        (.putNextEntry output (ZipEntry. name))
        (.write output (.getBytes contents StandardCharsets/UTF_8))
        (.closeEntry output)))
    archive))

(defn- content-types []
  (str "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
       "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\" />"
       "<Default Extension=\"psmdcp\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\" />"
       "<Default Extension=\"dll\" ContentType=\"application/octet\" />"
       "<Default Extension=\"nuspec\" ContentType=\"application/octet\" />"
       "</Types>"))

(defn- relationships [nuspec-name]
  (str "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
       "<Relationship Type=\"http://schemas.microsoft.com/packaging/2010/07/manifest\" Target=\"/"
       nuspec-name "\" Id=\"R1\" />"
       "<Relationship Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" "
       "Target=\"/package/services/metadata/core-properties/core-properties.psmdcp\" Id=\"R2\" />"
       "</Relationships>"))

(defn- core-properties [metadata]
  (str "<coreProperties xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
       "xmlns:dcterms=\"http://purl.org/dc/terms/\" "
       "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
       "xmlns=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\">"
       "<dc:creator>" (:authors metadata) "</dc:creator>"
       "<dc:description>" (:description metadata) "</dc:description>"
       "<dc:identifier>" (:id metadata) "</dc:identifier>"
       "<version>" (:version metadata) "</version>"
       "<keywords>" (:tags metadata) "</keywords>"
       "<lastModifiedBy>NuGet test writer</lastModifiedBy>"
       "</coreProperties>"))

(defn- package-archive! [entries]
  (let [nuspec-name (first (filter #(str/ends-with? % ".nuspec") (keys entries)))
        metadata (if (= "Pkl.Core.nuspec" nuspec-name) core-package package)]
    (archive! (merge {"[Content_Types].xml" (content-types)
                      "_rels/.rels" (relationships nuspec-name)
                      "package/services/metadata/core-properties/core-properties.psmdcp"
                      (core-properties metadata)}
                     entries))))

(defn- write-file! [^Path file contents]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file contents (make-array OpenOption 0))
  file)

(defn- sha256 [^Path file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (Files/readAllBytes file))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- sha256-text [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes value StandardCharsets/UTF_8))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- reproducibility-fixture [^Path root divergent?]
  (let [verification-count (atom 0)
        project-root (.resolve root "generated/pkl-parser")
        source-root (.resolve project-root "src")
        project-file (.resolve project-root "Pkl.Parser.csproj")
        generated-source (.resolve project-root "src/Pkl/Parser/Parser.cs")
        assembly (.resolve project-root "bin/Release/net8.0/Pkl.Parser.dll")
        configuration
        {:mechanical-source
         {:repository "https://github.com/apple/pkl.git"
          :revision "f7cac257ade5775c1dfc255f4fda2eacc296e9d0"
          :notice-reference "NOTICE.txt"}
         :output {:source-directory "src"
                  :manifest-file "generation-manifest.edn"}}
        mechanical-header
        (java-project/mechanical-source-header
         (:mechanical-source configuration)
         "org/pkl/parser/Parser.java")
        mechanical-proof
        {:schema-version 1
         :translator "DripSharp"
         :translator-version "0.1.0"
         :verified-files 1}
        destination {:project {:assembly-name "Pkl.Parser"
                               :target-framework "net8.0"}
                     :package package}
        verify-fn
        (fn [_]
          (let [build (swap! verification-count inc)]
            (write-file!
             project-file
             (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
                  "  <ItemGroup>\n"
                  "    <Compile Include=\"src/**/*.cs\" />\n"
                  "  </ItemGroup>\n"
                  "</Project>\n"))
            (write-file! generated-source
                         (str mechanical-header
                              "#nullable enable\nnamespace Pkl.Parser;\n"))
            (let [artifacts
                  [{:file "src/Pkl/Parser/Parser.cs"
                    :upstream-source "org/pkl/parser/Parser.java"
                    :mechanical-source-header mechanical-header}]
                  ledger
                  (authorship/create-ledger!
                   {:workspace-root root
                    :project-root project-root
                    :source-root source-root
                    :artifacts artifacts
                    :mechanical-source (:mechanical-source configuration)
                    :mechanical-header
                    java-project/mechanical-source-header})
                  manifest-file
                  (write-file!
                   (.resolve project-root "generation-manifest.edn")
                   (str (pr-str {:authorship ledger}) "\n"))]
              (write-file! assembly
                           (if (and divergent? (= 2 build))
                             "different second clean build"
                             "reproducible clean build"))
              {:generation
               {:generation-profile {:profile "pkl-parser"}
                :dependency-emissions []
                :emission
                {:workspace-root root
                 :project-root project-root
                 :source-root source-root
                 :project-file project-file
                 :manifest-file manifest-file
                 :resource-artifacts []
                 :configuration configuration
                 :artifacts artifacts
                 :authorship ledger
                 :mechanical-source-header-proof mechanical-proof}
                :destination destination}
               :build-configuration "Release"})))
        run-command!
        (fn [{:keys [command]}]
          (cond
            (= ["git" "rev-parse" "--verify" "HEAD"] command)
            {:output (str (:repository-commit package) "\n")}

            (= "dotnet" (first command))
            (let [output-index (.indexOf ^java.util.List command "--output")
                  output (Paths/get (nth command (inc output-index))
                                    (make-array String 0))
                  packed (package-archive!
                          {"Pkl.Parser.nuspec" (nuspec)
                           "lib/net8.0/Pkl.Parser.dll" (Files/readString assembly)})
                  artifact (.resolve output
                                     "Pkl.Parser.0.0.0-development.nupkg")]
              (Files/createDirectories output (make-array FileAttribute 0))
              (Files/move packed artifact
                          (into-array StandardCopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))
              {:output "packed"})

            :else
            (throw (ex-info "Unexpected reproducibility fixture command"
                            {:command command}))))]
    {:verification-count verification-count
     :verify-fn verify-fn
     :run-command! run-command!}))

(deftest package-reproducibility-requires-two-independent-clean-builds
  (let [root (Files/createTempDirectory "dripsharp-clean-build-reproducibility"
                                        (make-array FileAttribute 0))
        {:keys [verification-count verify-fn run-command!]}
        (reproducibility-fixture root false)
        proof (packaging/pack-verified-profile!
               {:workspace-root root
                :profile "fixture"
                :verify-fn verify-fn
                :run-command! run-command!
                :inspect-resources-fn
                (fn [& _]
                  {:public-surface {:types 1 :members 1
                                    :sha256 (apply str (repeat 64 "a"))}})})]
    (is (= 2 @verification-count))
    (is (= 2 (get-in proof [:summary :clean-builds])))
    (is (= {:schema-version 1
            :translator "DripSharp"
            :translator-version "0.1.0"
            :verified-files 1}
           (get-in proof [:summary :mechanical-source-headers "Pkl.Parser"])
           (get-in proof [:packages 0 :mechanical-source-headers])))
    (is (= {:files 1
            :mechanical-lines 9
            :authored-compat-lines 0
            :authored-destination-runtime-lines 0
            :authored-lines 0
            :total-lines 9
            :authored-fraction 0.0}
           (get-in proof [:summary :authorship "Pkl.Parser"])
           (get-in proof [:packages 0 :authorship :totals])
           (get-in proof [:inspection :authorship :totals])))
    (is (= ["src/Pkl/Parser/Parser.cs"]
           (get-in proof [:inspection :authorship :source-paths])))
    (is (= {:include "src/**/*.cs"
            :source-inventory-sha256
            (get-in proof
                    [:inspection :authorship
                     :source-inventory-sha256])}
           (get-in proof
                   [:inspection :authorship :assembly-input])))
    (is (= {:edn "release-evidence/mechanical-authored-boundary.edn"
            :markdown "release-evidence/mechanical-authored-boundary.md"}
           (get-in proof
                   [:summary :mechanical-authored-boundary :files])))
    (is (= {:mechanical-lines 9
            :authored-compat-lines 0
            :authored-destination-runtime-lines 0
            :authored-lines 0
            :total-lines 9}
           (-> (:boundary-report proof)
               :edn
               Files/readString
               edn/read-string
               (get-in [:packages 0 :lines]))))
    (is (str/includes?
         (Files/readString ^Path
          (get-in proof [:boundary-report :markdown]))
         "# Mechanical / Authored Boundary Release Evidence"))
    (is (Files/isRegularFile ^Path (:artifact proof)
                             (make-array java.nio.file.LinkOption 0)))))

(deftest package-source-inspection-reconciles-the-ledger-with-actual-inputs
  (let [root (Files/createTempDirectory "dripsharp-ledger-reconciliation"
                                        (make-array FileAttribute 0))
        {:keys [verify-fn]} (reproducibility-fixture root false)
        emission (get-in (verify-fn {}) [:generation :emission])
        unlisted (write-file!
                  (paths/resolve-path (:source-root emission)
                                      "Pkl/Parser/Unlisted.cs")
                  "namespace Pkl.Parser;\n")
        _ unlisted
        inventory-error
        (try
          (#'packaging/verified-authorship! emission)
          nil
          (catch clojure.lang.ExceptionInfo caught caught))
        _ (Files/delete unlisted)
        _ (write-file!
           (:project-file emission)
           (str "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup>"
                "<Compile Include=\"src/**/*.cs\" />"
                "<Compile Include=\"../Authored.cs\" />"
                "</ItemGroup></Project>"))
        assembly-input-error
        (try
          (#'packaging/verified-authorship! emission)
          nil
          (catch clojure.lang.ExceptionInfo caught caught))
        _ (write-file!
           (:project-file emission)
           (str "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup>"
                "<Compile Include=\"src/**/*.cs\" />"
                "</ItemGroup></Project>"))
        _ (write-file!
           (:manifest-file emission)
           (str
            (pr-str
             {:authorship
              (assoc-in (:authorship emission)
                        [:totals :mechanical-lines] 0)})
            "\n"))
        manifest-error
        (try
          (#'packaging/verified-authorship! emission)
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-authorship-ledger
           (:kind (ex-data inventory-error))))
    (is (= ["src/Pkl/Parser/Unlisted.cs"]
           (:missing (ex-data inventory-error))))
    (is (= :package-consumption-failed
           (:kind (ex-data assembly-input-error))))
    (is (= :package-consumption-failed
           (:kind (ex-data manifest-error))))))

(deftest package-reproducibility-rejects-divergent-clean-builds
  (let [root (Files/createTempDirectory "dripsharp-clean-build-divergence"
                                        (make-array FileAttribute 0))
        {:keys [verification-count verify-fn run-command!]}
        (reproducibility-fixture root true)
        error (try
                (packaging/pack-verified-profile!
                 {:workspace-root root
                  :profile "fixture"
                  :verify-fn verify-fn
                  :run-command! run-command!
                  :inspect-resources-fn (fn [& _] {})})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= 2 @verification-count))
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (not= (:first (ex-data error)) (:second (ex-data error))))))

(defn- dag-package-destination
  [profile dependencies references]
  {:project {:assembly-name (str "Package." profile)
             :target-framework "net10.0"}
   :package {:id (str "Package." profile)
             :version "1.0.0"}
   :package-dependencies (mapv #(str "Package." %) dependencies)
   :project-references references
   :runtime-packages []
   :legal-files []})

(deftest package-plan-preserves-direct-edges-and-includes-the-transitive-dag
  (let [emission
        (fn [profile dependencies references]
          {:profile profile
           :dependency-profiles dependencies
           :destination
           (dag-package-destination profile dependencies references)
           :project-root (Paths/get (str "/generated/" profile)
                                    (make-array String 0))
           :project-file
           (Paths/get (str "/generated/" profile "/" profile ".csproj")
                      (make-array String 0))
           :resource-artifacts []
           :artifacts []
           :mechanical-source-header-proof
           {:schema-version 1
            :translator "DripSharp"
            :translator-version "0.1.0"
            :verified-files 0}
           :authorship
           {:schema-version 2
            :files []
            :totals
            {:files 0
             :mechanical-lines 0
             :authored-compat-lines 0
             :authored-destination-runtime-lines 0
             :authored-lines 0
             :total-lines 0
             :authored-fraction 0.0}
            :policy nil}})
        dependency-emissions
        [(emission "io" [] [])
         (emission "fontbox" ["io"] ["../io/io.csproj"])
         (emission "pdfbox" ["io" "fontbox"]
                   ["../io/io.csproj" "../fontbox/fontbox.csproj"])
         (emission "xmpbox" [] [])]
        main
        (emission "preflight" ["pdfbox" "xmpbox"]
                  ["../pdfbox/pdfbox.csproj" "../xmpbox/xmpbox.csproj"])
        specs
        (#'packaging/package-specs
         {:generation-profile {:profile "preflight"}
          :dependency-profiles ["pdfbox" "xmpbox"]
          :dependency-emissions dependency-emissions
          :destination (:destination main)
          :emission (dissoc main :profile :dependency-profiles
                            :destination)})
        dependency-ids
        (into
         {}
         (map
          (fn [{:keys [profile expected-dependencies]}]
            [profile (mapv :id expected-dependencies)]))
         specs)
        assembly-dependencies
        (into
         {}
         (map
          (fn [{:keys [profile expected-assembly-dependencies]}]
            [profile (mapv :package-id expected-assembly-dependencies)]))
         specs)]
    (is (= ["io" "fontbox" "pdfbox" "xmpbox" "preflight"]
           (mapv :profile specs)))
    (is (= {"io" []
            "fontbox" ["Package.io"]
            "pdfbox" ["Package.fontbox" "Package.io"]
            "xmpbox" []
            "preflight" ["Package.pdfbox" "Package.xmpbox"]}
           dependency-ids))
    (is (= {"io" []
            "fontbox" ["Package.io"]
            "pdfbox" ["Package.fontbox" "Package.io"]
            "xmpbox" []
            "preflight" ["Package.fontbox" "Package.io"
                         "Package.pdfbox" "Package.xmpbox"]}
           assembly-dependencies))
    (is (= [false false false false true]
           (mapv :primary? specs)))))

(deftest package-inspection-requires-exact-release-layout
  (let [artifact (package-archive! {"Pkl.Parser.nuspec" (nuspec)
                                    "lib/net8.0/Pkl.Parser.dll" "assembly"})
        inspection (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")]
    (is (= "lib/net8.0/Pkl.Parser.dll" (:assembly-entry inspection)))
    (is (= 5 (count (:entries inspection)))))
  (doseq [[label entries]
          [["missing OPC envelope"
            {"Pkl.Parser.nuspec" (nuspec)
             "lib/net8.0/Pkl.Parser.dll" "assembly"}]
           ["unexpected package payload"
            {"Pkl.Parser.nuspec" (nuspec)
             "lib/net8.0/Pkl.Parser.dll" "assembly"
             "tools/install.ps1" "unexpected"}]]]
    (let [artifact ((if (= label "missing OPC envelope") archive! package-archive!) entries)
          error (try
                  (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (testing label
        (is (= :package-consumption-failed (:kind (ex-data error))))
        (is (vector? (:expected (ex-data error))))))))

(deftest package-inspection-requires-the-nuspec-namespace
  (doseq [[label wrong-namespace]
          [["wrong root schema"
            (str/replace (nuspec) nuspec-namespace "https://example.test/shadow")]
           ["namespace-shadowed metadata"
            (str/replace (nuspec) "<metadata>"
                         "<metadata xmlns=\"https://example.test/shadow\">")]]]
    (let [artifact (package-archive! {"Pkl.Parser.nuspec" wrong-namespace
                                      "lib/net8.0/Pkl.Parser.dll" "assembly"})
          error (try
                  (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (testing label
        (is (= :package-consumption-failed (:kind (ex-data error))))
        (is (= nuspec-namespace (:expected (ex-data error))))
        (is (= "https://example.test/shadow" (:actual (ex-data error))))))))

(deftest package-inspection-requires-an-exact-opc-envelope
  (doseq [[label entry alter]
          [["content-type declaration"
            "[Content_Types].xml"
            #(str/replace % "application/octet" "application/x-shadow")]
           ["nuspec relationship"
            "_rels/.rels"
            #(str/replace % "/Pkl.Parser.nuspec" "/Shadow.nuspec")]
           ["mirrored core metadata"
            "package/services/metadata/core-properties/core-properties.psmdcp"
            #(str/replace % (:description package) "misleading description")]]]
    (let [base {"Pkl.Parser.nuspec" (nuspec)
                "lib/net8.0/Pkl.Parser.dll" "assembly"}
          artifact (package-archive!
                    (assoc base entry
                           (alter (get (merge {"[Content_Types].xml" (content-types)
                                               "_rels/.rels" (relationships "Pkl.Parser.nuspec")
                                               "package/services/metadata/core-properties/core-properties.psmdcp"
                                               (core-properties package)}
                                              base)
                                       entry))))
          error (try
                  (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (testing label
        (is (= :package-consumption-failed (:kind (ex-data error))))))))

(deftest package-inspection-rejects-generated-source
  (let [artifact (package-archive! {"Pkl.Parser.nuspec" (nuspec)
                                    "lib/net8.0/Pkl.Parser.dll" "assembly"
                                    "src/Parser.cs" "generated source"})
        error (try
                (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (testing "translator and generated-source implementation details cannot ship"
      (is (= :package-consumption-failed (:kind (ex-data error))))
      (is (= ["src/Parser.cs"] (:forbidden (ex-data error)))))))

(deftest package-inspection-rejects-ambiguous-or-unsafe-archive-paths
  (doseq [[shadow-path expected-key]
          [["pkl.parser.nuspec" :case-collisions]
           ["metadata/../Pkl.Parser.nuspec" :unsafe]]]
    (let [artifact (package-archive! {"Pkl.Parser.nuspec" (nuspec)
                                      shadow-path "shadow metadata"
                                      "lib/net8.0/Pkl.Parser.dll" "assembly"})
          error (try
                  (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (testing (str "archive entry " shadow-path " cannot shadow package metadata")
        (is (= :package-consumption-failed (:kind (ex-data error))))
        (is (seq (expected-key (ex-data error))))))))

(deftest package-inspection-pins-dependency-closure-without-bundling-it
  (let [artifact (package-archive! {"Pkl.Core.nuspec" (core-nuspec)
                                    "lib/net8.0/Pkl.Core.dll" "assembly"})
        renamed (.resolve (.getParent artifact) "Pkl.Core.0.0.0-development.nupkg")
        _ (Files/move artifact renamed (make-array java.nio.file.CopyOption 0))
        inspection (packaging/inspect-package!
                    renamed core-package "net8.0" "Pkl.Core"
                    [{:id "Pkl.Parser" :version "0.0.0-development"}])]
    (is (= [{:id "Pkl.Parser" :version "0.0.0-development"}]
           (:dependencies inspection)))))

(deftest package-inspection-pins-file-license-and-notice-payloads
  (let [file-package (dissoc package :license-expression)
        file-nuspec
        (str "<package xmlns=\"" nuspec-namespace "\"><metadata>"
             "<id>" (:id file-package) "</id>"
             "<version>" (:version file-package) "</version>"
             "<title>" (:title file-package) "</title>"
             "<description>" (:description file-package) "</description>"
             "<authors>" (:authors file-package) "</authors>"
             "<tags>" (:tags file-package) "</tags>"
             "<license type=\"file\">LICENSE.txt</license>"
             "<licenseUrl>https://aka.ms/deprecateLicenseUrl</licenseUrl>"
             "<projectUrl>" (:project-url file-package) "</projectUrl>"
             "<repository type=\"" (:repository-type file-package) "\" url=\""
             (:repository-url file-package) "\" commit=\""
             (:repository-commit file-package) "\" />"
             "<dependencies><group targetFramework=\"net8.0\" /></dependencies>"
             "</metadata></package>")
        license "licensed payload"
        notice "notice payload"
        content-types-with-text
        (str/replace (content-types)
                     "</Types>"
                     "<Default Extension=\"txt\" ContentType=\"application/octet\" /></Types>")
        artifact
        (archive!
         {"Pkl.Parser.nuspec" file-nuspec
          "lib/net8.0/Pkl.Parser.dll" "assembly"
          "LICENSE.txt" license
          "NOTICE.txt" notice
          "[Content_Types].xml" content-types-with-text
          "_rels/.rels" (relationships "Pkl.Parser.nuspec")
          "package/services/metadata/core-properties/core-properties.psmdcp"
          (core-properties file-package)})
        files [{:kind :license :path "LICENSE.txt"
                :sha256 (sha256-text license)}
               {:kind :notice :path "NOTICE.txt"
                :sha256 (sha256-text notice)}]
        inspection
        (packaging/inspect-package!
         artifact file-package "net8.0" "Pkl.Parser" [] files)]
    (is (= files (:package-files inspection)))
    (let [error
          (try
            (packaging/inspect-package!
             artifact file-package "net8.0" "Pkl.Parser" []
             (assoc-in files [1 :sha256] (apply str (repeat 64 "0"))))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :package-consumption-failed (:kind (ex-data error))))
      (is (= "NOTICE.txt" (:path (ex-data error)))))))

(deftest pkl-core-package-inspection-requires-pinned-license-and-notice
  (let [workspace (paths/workspace-root)
        destination
        (java-project/read-configuration
         workspace "targets/pkl/destinations/core.edn")
        package
        (assoc (:package destination)
               :repository-commit
               "0123456789abcdef0123456789abcdef01234567")
        license (Files/readString
                 (paths/resolve-path workspace "research/pkl/LICENSE.txt"))
        upstream-notice
        (Files/readString
         (paths/resolve-path workspace "research/pkl/NOTICE.txt"))
        notice (str upstream-notice (:notice-appendix destination))
        nuspec
        (str "<package xmlns=\"" dependency-nuspec-namespace "\"><metadata>"
             "<id>" (:id package) "</id>"
             "<version>" (:version package) "</version>"
             "<title>" (:title package) "</title>"
             "<description>" (:description package) "</description>"
             "<authors>" (:authors package) "</authors>"
             "<tags>" (:tags package) "</tags>"
             "<license type=\"file\">LICENSE.txt</license>"
             "<licenseUrl>https://aka.ms/deprecateLicenseUrl</licenseUrl>"
             "<projectUrl>" (:project-url package) "</projectUrl>"
             "<repository type=\"" (:repository-type package)
             "\" url=\"" (:repository-url package)
             "\" commit=\"" (:repository-commit package) "\" />"
             "<dependencies><group targetFramework=\"net8.0\">"
             "<dependency id=\"Pkl.Parser\" version=\"0.0.0-development\" "
             "exclude=\"Build,Analyzers\" />"
             "</group></dependencies>"
             "</metadata></package>")
        content-types-with-text
        (str/replace
         (content-types)
         "</Types>"
         "<Default Extension=\"txt\" ContentType=\"application/octet\" /></Types>")
        base-entries
        {"Pkl.Core.nuspec" nuspec
         "lib/net8.0/Pkl.Core.dll" "assembly"
         "LICENSE.txt" license
         "[Content_Types].xml" content-types-with-text
         "_rels/.rels" (relationships "Pkl.Core.nuspec")
         "package/services/metadata/core-properties/core-properties.psmdcp"
         (core-properties package)}
        artifact (archive! (assoc base-entries "NOTICE.txt" notice))
        expected-files
        (mapv (fn [{:keys [kind package-path sha256]}]
                {:kind kind :path package-path :sha256 sha256})
              (:legal-files destination))
        dependencies [{:id "Pkl.Parser" :version "0.0.0-development"}]
        inspection
        (packaging/inspect-package!
         artifact package "net8.0" "Pkl.Core" dependencies expected-files)
        missing-notice (archive! base-entries)
        error
        (try
          (packaging/inspect-package!
           missing-notice package "net8.0" "Pkl.Core"
           dependencies expected-files)
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= expected-files (:package-files inspection)))
    (is (str/starts-with? notice upstream-notice))
    (is (= (:notice-appendix destination)
           (subs notice (count upstream-notice))))
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (some #{"NOTICE.txt"} (:expected (ex-data error))))))

(deftest package-inspection-rejects-a-bundled-project-dependency-assembly
  (let [artifact (package-archive! {"Pkl.Core.nuspec" (core-nuspec)
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
  (let [artifact (package-archive! {"Pkl.Parser.nuspec" (nuspec)
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
                                                (:repository-url package)
                                                "\" commit=\"0123456789abcdef0123456789abcdef01234567\" />")
                                           (str "<repository type=\"svn\" url=\"https://wrong.test/repo\" "
                                                "commit=\"0123456789abcdef0123456789abcdef01234567\" />")))
        artifact (package-archive! {"Pkl.Parser.nuspec" misleading-nuspec
                                    "lib/net8.0/Pkl.Parser.dll" "assembly"})
        error (try
                (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (= {"type" (:repository-type package)
            "url" (:repository-url package)
            "commit" (:repository-commit package)}
           (:expected (ex-data error))))))

(deftest package-inspection-requires-exact-scalar-metadata-and-dependency-group
  (testing "duplicate scalar metadata cannot hide behind a matching value"
    (let [duplicate-title (str/replace
                           (nuspec)
                           (str "<title>" (:title package) "</title>")
                           (str "<title>" (:title package) "</title>"
                                "<title>misleading duplicate</title>"))
          artifact (package-archive! {"Pkl.Parser.nuspec" duplicate-title
                                      "lib/net8.0/Pkl.Parser.dll" "assembly"})
          error (try
                  (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :package-consumption-failed (:kind (ex-data error))))
      (is (= "title" (:element (ex-data error))))
      (is (= 2 (:count (ex-data error))))))
  (testing "dependencies must be scoped to the configured target framework"
    (let [wrong-framework (str/replace (nuspec)
                                       "targetFramework=\"net8.0\""
                                       "targetFramework=\"net9.0\"")
          artifact (package-archive! {"Pkl.Parser.nuspec" wrong-framework
                                      "lib/net8.0/Pkl.Parser.dll" "assembly"})
          error (try
                  (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :package-consumption-failed (:kind (ex-data error))))
      (is (= "net8.0" (:expected (ex-data error))))
      (is (= "net9.0" (:actual (ex-data error))))))
  (testing "license URL must be the exact canonical expression URL"
    (let [wrong-license-url (str/replace
                             (nuspec)
                             "https://licenses.nuget.org/Apache-2.0"
                             "https://example.test/Apache-2.0")
          artifact (package-archive! {"Pkl.Parser.nuspec" wrong-license-url
                                      "lib/net8.0/Pkl.Parser.dll" "assembly"})
          error (try
                  (packaging/inspect-package! artifact package "net8.0" "Pkl.Parser")
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :package-consumption-failed (:kind (ex-data error))))
      (is (= "https://licenses.nuget.org/Apache-2.0"
             (:expected (ex-data error))))))
  (testing "copyright metadata is exact when configured"
    (let [copyright "Portions Copyright upstream contributors."
          copyrighted (assoc package :copyright copyright)
          with-copyright
          (str/replace (nuspec)
                       (str "<authors>" (:authors package) "</authors>")
                       (str "<authors>" (:authors package) "</authors>"
                            "<copyright>" copyright "</copyright>"))
          artifact
          (package-archive! {"Pkl.Parser.nuspec" with-copyright
                             "lib/net8.0/Pkl.Parser.dll" "assembly"})
          inspection
          (packaging/inspect-package!
           artifact copyrighted "net8.0" "Pkl.Parser")
          error
          (try
            (packaging/inspect-package!
             artifact (assoc copyrighted :copyright "wrong")
             "net8.0" "Pkl.Parser")
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= [] (:dependencies inspection)))
      (is (= :package-consumption-failed (:kind (ex-data error))))
      (is (= "wrong" (:expected (ex-data error))))
      (is (= copyright (:actual (ex-data error)))))))

(deftest package-inspection-requires-the-configured-non-affiliation-description
  (let [disclaimer
        "This package is an independent translation and is not affiliated with, endorsed by, or sponsored by Apple Inc."
        description (str (:description package) " " disclaimer)
        expected-package (assoc package :description description)]
    (testing "the nuspec cannot omit the configured disclaimer"
      (let [artifact
            (package-archive!
             {"Pkl.Parser.nuspec" (nuspec)
              "lib/net8.0/Pkl.Parser.dll" "assembly"})
            error
            (try
              (packaging/inspect-package!
               artifact expected-package "net8.0" "Pkl.Parser")
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :package-consumption-failed (:kind (ex-data error))))
        (is (= "description" (:element (ex-data error))))
        (is (= description (:expected (ex-data error))))
        (is (= (:description package) (:actual (ex-data error))))))
    (testing "the OPC core properties must mirror the disclaimer exactly"
      (let [with-disclaimer
            (str/replace
             (nuspec)
             (str "<description>" (:description package) "</description>")
             (str "<description>" description "</description>"))
            artifact
            (package-archive!
             {"Pkl.Parser.nuspec" with-disclaimer
              "lib/net8.0/Pkl.Parser.dll" "assembly"})
            error
            (try
              (packaging/inspect-package!
               artifact expected-package "net8.0" "Pkl.Parser")
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :package-consumption-failed (:kind (ex-data error))))
        (is (some #{[dc-elements-namespace "description"]}
                  (keys (:expected (ex-data error)))))
        (is (= description
               (get (:expected (ex-data error))
                    [dc-elements-namespace "description"])))
        (is (= (:description package)
               (get (:actual (ex-data error))
                    [dc-elements-namespace "description"])))))))

(deftest package-inspection-rejects-unexpected-metadata-and-attributes
  (doseq [[label altered]
          [["unexpected metadata element"
            (str/replace (nuspec) "</metadata>" "<owners>shadow owner</owners></metadata>")]
           ["unexpected package attribute"
            (str/replace (nuspec)
                         (str "xmlns=\"" nuspec-namespace "\"")
                         (str "xmlns=\"" nuspec-namespace "\" shadow=\"true\""))]
           ["unverifiable repository commit"
            (str/replace (nuspec)
                         "commit=\"0123456789abcdef0123456789abcdef01234567\""
                         "commit=\"not-a-commit\"")]
           ["different valid repository commit"
            (str/replace (nuspec)
                         "commit=\"0123456789abcdef0123456789abcdef01234567\""
                         "commit=\"abcdef0123456789abcdef0123456789abcdef01\"")]
           ["dependency asset override"
            (str/replace (core-nuspec)
                         "exclude=\"Build,Analyzers\""
                         "exclude=\"Build\" include=\"All\"")]]]
    (let [core? (= label "dependency asset override")
          artifact (package-archive! {(if core? "Pkl.Core.nuspec" "Pkl.Parser.nuspec") altered
                                      (if core?
                                        "lib/net8.0/Pkl.Core.dll"
                                        "lib/net8.0/Pkl.Parser.dll") "assembly"})
          artifact (if core?
                     (let [renamed (.resolve (.getParent artifact)
                                             "Pkl.Core.0.0.0-development.nupkg")]
                       (Files/move artifact renamed
                                   (make-array java.nio.file.CopyOption 0))
                       renamed)
                     artifact)
          error (try
                  (packaging/inspect-package!
                   artifact (if core? core-package package) "net8.0"
                   (if core? "Pkl.Core" "Pkl.Parser")
                   (if core?
                     [{:id "Pkl.Parser" :version "0.0.0-development"}]
                     []))
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (testing label
        (is (= :package-consumption-failed (:kind (ex-data error))))))))

(deftest independent-consumer-dependency-proof-pins-package-only-closure
  (let [root (Files/createTempDirectory "dripsharp-consumer-proof"
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
        package-files (into {}
                            (for [id ["Pkl.Parser" "Pkl.Core"]
                                  :let [version "0.0.0-development"
                                        lower (.toLowerCase ^String id)
                                        file (write-file!
                                              (.resolve packages
                                                        (str lower "/" version "/" lower "."
                                                             version ".nupkg"))
                                              (str id " package"))]]
                              [id file]))
        identities (mapv (fn [id]
                           {:id id :version "0.0.0-development"
                            :sha256 (sha256 (get package-files id))})
                         ["Pkl.Parser" "Pkl.Core"])
        proof (packaging/inspect-consumer-dependencies!
               project assets packages (second identities) identities)]
    (is (= ["Pkl.Core" "0.0.0-development"] (:package-reference proof)))
    (is (= identities (:packages proof)))))

(deftest independent-consumer-dependency-proof-rejects-project-reference
  (let [root (Files/createTempDirectory "dripsharp-consumer-leak"
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

(deftest independent-consumer-dependency-proof-rejects-wrong-artifact-or-extra-version
  (let [root (Files/createTempDirectory "dripsharp-consumer-identity"
                                        (make-array FileAttribute 0))
        project (write-file!
                 (.resolve root "Consumer.csproj")
                 (str "<Project><ItemGroup>"
                      "<PackageReference Include=\"Pkl.Core\" Version=\"0.0.0-development\" />"
                      "</ItemGroup></Project>"))
        assets (write-file!
                (.resolve root "obj/project.assets.json")
                "{\"libraries\":{\"Pkl.Core/0.0.0-development\":{\"type\":\"package\"}}}")
        packages (.resolve root "packages")
        artifact (write-file!
                  (.resolve packages
                            "pkl.core/0.0.0-development/pkl.core.0.0.0-development.nupkg")
                  "restored package")
        identity {:id "Pkl.Core" :version "0.0.0-development"
                  :sha256 (sha256 artifact)}]
    (write-file! (.resolve packages "pkl.core/0.0.1/pkl.core.0.0.1.nupkg")
                 "unexpected version")
    (let [version-error (try
                          (packaging/inspect-consumer-dependencies!
                           project assets packages identity [identity])
                          nil
                          (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :package-consumption-failed (:kind (ex-data version-error))))
      (is (= ["0.0.0-development"] (:expected (ex-data version-error))))
      (is (= ["0.0.0-development" "0.0.1"] (:actual (ex-data version-error)))))
    (Files/delete (.resolve packages "pkl.core/0.0.1/pkl.core.0.0.1.nupkg"))
    (Files/delete (.resolve packages "pkl.core/0.0.1"))
    (let [hash-error (try
                       (packaging/inspect-consumer-dependencies!
                        project assets packages (assoc identity :sha256 "wrong")
                        [(assoc identity :sha256 "wrong")])
                       nil
                       (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :package-consumption-failed (:kind (ex-data hash-error))))
      (is (= "wrong" (:expected (ex-data hash-error))))
      (is (= (:sha256 identity) (:actual (ex-data hash-error)))))))

(deftest package-assembly-inspection-binds-generated-package-identities
  (let [root (Files/createTempDirectory "dripsharp-assembly-inspection"
                                        (make-array FileAttribute 0))
        packed (archive! {"lib/net8.0/Pkl.Core.dll" "verified assembly"})
        artifact (.resolve root "Pkl.Core.0.0.0-development.nupkg")
        _ (Files/move packed artifact (make-array java.nio.file.CopyOption 0))
        verified-assembly (write-file! (.resolve root "bin/Pkl.Core.dll")
                                       "verified assembly")
        dependency-artifact
        (write-file! (.resolve root "Pkl.Parser.0.0.0-development.nupkg")
                     "dependency package")
        request (atom nil)
        run-command! (fn [value]
                       (reset! request value)
                       {:output (str "Assembly identity inspection passed: Pkl.Core, "
                                     "Version=0.0.0.0, Culture=neutral, PublicKeyToken=null; "
                                     "dependency references [Pkl.Parser, Version=0.0.0.0, "
                                     "Culture=neutral, PublicKeyToken=null]\n"
                                     "Embedded resource inspection passed: 1\n"
                                     "Public surface inspection passed: 3 types, 7 members, "
                                     "SHA-256 " (apply str (repeat 64 "a")) "\n")})
        proof (#'packaging/inspect-package-assembly!
               run-command! root artifact "lib/net8.0/Pkl.Core.dll" "Pkl.Core"
               verified-assembly
               ["Pkl.Parser" "Pkl.Core"]
               [{:assembly-name "Pkl.Parser"
                 :package-id "Pkl.Parser"
                 :version "0.0.0-development"
                 :target-framework "net8.0"}]
               ["org.pkl.core.Release.properties"])
        inspector-arguments (vec (drop-while #(not= "--" %) (:command @request)))]
    (is (= ["--" (str artifact) "lib/net8.0/Pkl.Core.dll" "Pkl.Core"
            "2" "Pkl.Parser" "Pkl.Core" "1" "Pkl.Parser"
            (str dependency-artifact) "lib/net8.0/Pkl.Parser.dll"
            "org.pkl.core.Release.properties"]
           inspector-arguments))
    (is (= {:name "Pkl.Core" :version "0.0.0.0"
            :dependency-assemblies ["Pkl.Parser"]}
           (:assembly-identity proof)))
    (is (= {:sha256 (sha256 verified-assembly)
            :verified-assembly (str verified-assembly)}
           (:assembly-artifact proof)))
    (is (= {:types 3 :members 7 :sha256 (apply str (repeat 64 "a"))}
           (:public-surface proof)))))

(deftest package-assembly-inspection-rejects-substituted-build-output
  (let [root (Files/createTempDirectory "dripsharp-assembly-substitution"
                                        (make-array FileAttribute 0))
        packed (archive! {"lib/net8.0/Pkl.Core.dll" "substituted assembly"})
        artifact (.resolve root "Pkl.Core.0.0.0-development.nupkg")
        _ (Files/move packed artifact (make-array java.nio.file.CopyOption 0))
        verified-assembly (write-file! (.resolve root "bin/Pkl.Core.dll")
                                       "verified assembly")
        error (try
                (#'packaging/verify-packaged-assembly!
                 artifact "lib/net8.0/Pkl.Core.dll" verified-assembly)
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (= (sha256 verified-assembly) (:expected (ex-data error))))
    (is (not= (:expected (ex-data error)) (:actual (ex-data error))))))
