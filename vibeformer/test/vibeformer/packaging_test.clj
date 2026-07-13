(ns vibeformer.packaging-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.packaging :as packaging])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
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
   :repository-type "git"
   :repository-commit "0123456789abcdef0123456789abcdef01234567"})

(def core-package
  {:id "Pkl.Core"
   :version "0.0.0-development"
   :title "Pkl for .NET"
   :description "Disposable core package."
   :authors "Vibeformer"
   :tags "pkl core vibeformer"
   :project-url "https://example.test/pkl"
   :repository-url "https://example.test/pkl.git"
   :repository-type "git"
   :repository-commit "0123456789abcdef0123456789abcdef01234567"})

(def nuspec-namespace
  "http://schemas.microsoft.com/packaging/2012/06/nuspec.xsd")

(def dependency-nuspec-namespace
  "http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd")

(defn- nuspec []
  (str "<package xmlns=\"" nuspec-namespace "\"><metadata>"
       "<id>" (:id package) "</id>"
       "<version>" (:version package) "</version>"
       "<title>" (:title package) "</title>"
       "<description>" (:description package) "</description>"
       "<authors>" (:authors package) "</authors>"
       "<tags>" (:tags package) "</tags>"
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
      (is (= "net9.0" (:actual (ex-data error)))))))

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

(deftest independent-consumer-dependency-proof-rejects-wrong-artifact-or-extra-version
  (let [root (Files/createTempDirectory "vibeformer-consumer-identity"
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
  (let [root (Files/createTempDirectory "vibeformer-assembly-inspection"
                                         (make-array FileAttribute 0))
        artifact (.resolve root "Pkl.Core.0.0.0-development.nupkg")
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
    (is (= {:types 3 :members 7 :sha256 (apply str (repeat 64 "a"))}
           (:public-surface proof)))))
