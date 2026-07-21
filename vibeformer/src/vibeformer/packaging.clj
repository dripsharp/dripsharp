(ns vibeformer.packaging
  "Local NuGet packaging and isolated independent-consumer verification."
  (:require [clojure.string :as str]
            [vibeformer.compiler :as compiler]
            [vibeformer.harness :as harness]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Element Node]))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :package-consumption-failed))))

(defn- xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- write-text! [^Path file value]
  (Files/createDirectories (.getParent file)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (Files/writeString file value (make-array java.nio.file.OpenOption 0))
  file)

(defn- regular-files [^Path directory]
  (if-not (paths/directory? directory)
    []
    (with-open [entries (Files/list directory)]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (sort-by str)
           vec))))

(defn- child-directories [^Path directory]
  (if-not (paths/directory? directory)
    []
    (with-open [entries (Files/list directory)]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/directory?)
           (sort-by str)
           vec))))

(defn- delete-tree! [^Path directory]
  (when (paths/exists? directory)
    (with-open [entries (Files/walk directory (make-array FileVisitOption 0))]
      (doseq [^Path entry (->> (.toArray entries)
                               (map #(cast Path %))
                               (sort-by #(.getNameCount ^Path %) >))]
        (Files/deleteIfExists entry)))))

(defn- package-artifact! [^Path feed package-id version]
  (let [packages (filter #(and (str/ends-with? (str %) ".nupkg")
                               (not (str/ends-with? (str %) ".snupkg")))
                         (regular-files feed))
        expected (str/lower-case (str package-id "." version ".nupkg"))
        matches (filter #(= expected (str/lower-case (str (.getFileName ^Path %))))
                        packages)]
    (when-not (= 1 (count matches))
      (fail! "NuGet feed does not contain exactly one artifact for the configured identity"
             {:feed (str feed) :expected expected :artifacts (mapv str packages)}))
    (first matches)))

(defn- zip-text [^ZipFile archive entry-name]
  (when-let [entry (.getEntry archive entry-name)]
    (with-open [input (.getInputStream archive entry)]
      (String. (.readAllBytes input) StandardCharsets/UTF_8))))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- sha256-input [input]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (loop []
      (let [read (.read input buffer)]
        (when (pos? read)
          (.update digest buffer 0 read)
          (recur))))
    (hex (.digest digest))))

(defn- sha256 [^Path file]
  (with-open [input (Files/newInputStream
                     file (make-array java.nio.file.OpenOption 0))]
    (sha256-input input)))

(defn- verify-packaged-assembly!
  [artifact assembly-entry ^Path verified-assembly]
  (when-not (paths/regular-file? verified-assembly)
    (fail! "Verified clean-build assembly is missing"
           {:artifact (str artifact) :assembly-entry assembly-entry
            :verified-assembly (str verified-assembly)}))
  (with-open [archive (ZipFile. (str artifact))]
    (let [entry (.getEntry archive assembly-entry)]
      (when-not entry
        (fail! "NuGet package assembly entry is missing during build-artifact verification"
               {:artifact (str artifact) :assembly-entry assembly-entry}))
      (let [expected (sha256 verified-assembly)
            actual (with-open [input (.getInputStream archive entry)]
                     (sha256-input input))]
        (when-not (= expected actual)
          (fail! "Packaged assembly does not match the verified clean-build artifact"
                 {:artifact (str artifact) :assembly-entry assembly-entry
                  :verified-assembly (str verified-assembly)
                  :expected expected :actual actual}))
        {:sha256 actual :verified-assembly (str verified-assembly)}))))

(defn- validate-entry-layout!
  "Rejects archive paths whose identity changes across ZIP consumers or when
  normalized onto a filesystem. NuGet identities and paths are
  case-insensitive, so a release artifact must have one unambiguous spelling
  for every payload entry."
  [entry-names stage]
  (let [unsafe
        (->> entry-names
             (keep (fn [name]
                     (let [segments (str/split name #"/" -1)]
                       (when (or (str/blank? name)
                                 (str/starts-with? name "/")
                                 (str/includes? name "\\")
                                 (some #{"." ".."} segments))
                         name))))
             distinct
             sort
             vec)
        duplicates (->> entry-names frequencies
                        (keep (fn [[name count]] (when (> count 1) name)))
                        sort
                        vec)
        case-collisions
        (->> entry-names
             (group-by str/lower-case)
             vals
             (map #(vec (sort (distinct %))))
             (filter #(< 1 (count %)))
             (sort-by first)
             vec)]
    (when (or (seq unsafe) (seq duplicates) (seq case-collisions))
      (fail! "NuGet package contains ambiguous or unsafe archive paths"
             {:stage stage :unsafe unsafe :duplicates duplicates
              :case-collisions case-collisions}))
    entry-names))

(defn- parse-xml! [xml part]
  (try
    (let [factory (doto (DocumentBuilderFactory/newInstance)
                    (.setNamespaceAware true)
                    (.setXIncludeAware false)
                    (.setExpandEntityReferences false))]
      (.setFeature factory "http://apache.org/xml/features/disallow-doctype-decl" true)
      (.setFeature factory "http://xml.org/sax/features/external-general-entities" false)
      (.setFeature factory "http://xml.org/sax/features/external-parameter-entities" false)
      (with-open [input (ByteArrayInputStream.
                         (.getBytes ^String xml StandardCharsets/UTF_8))]
        (.parse (.newDocumentBuilder factory) input)))
    (catch Exception error
      (fail! "NuGet package contains invalid XML metadata"
             {:part part :cause (.getMessage error)}))))

(defn- element-name [^Element element]
  (or (.getLocalName element) (.getNodeName element)))

(def ^:private base-nuspec-namespace
  "http://schemas.microsoft.com/packaging/2012/06/nuspec.xsd")

(def ^:private dependency-nuspec-namespace
  "http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd")

(def ^:private content-types-namespace
  "http://schemas.openxmlformats.org/package/2006/content-types")

(def ^:private relationships-namespace
  "http://schemas.openxmlformats.org/package/2006/relationships")

(def ^:private core-properties-namespace
  "http://schemas.openxmlformats.org/package/2006/metadata/core-properties")

(def ^:private dc-namespace
  "http://purl.org/dc/elements/1.1/")

(def ^:private dcterms-namespace
  "http://purl.org/dc/terms/")

(def ^:private xsi-namespace
  "http://www.w3.org/2001/XMLSchema-instance")

(defn- require-nuspec-namespace! [^Element element path]
  (let [root (.getDocumentElement (.getOwnerDocument element))
        expected (.getNamespaceURI root)
        actual (.getNamespaceURI element)]
    (when-not (= expected actual)
      (fail! "NuGet metadata element is outside the required nuspec namespace"
             {:path path :element (element-name element)
              :expected expected :actual actual}))))

(defn- child-elements
  ([^Node parent]
   (let [children (.getChildNodes parent)]
     (->> (range (.getLength children))
          (map #(.item children %))
          (filter #(instance? Element %))
          vec)))
  ([^Node parent name]
   (filterv #(= name (element-name %)) (child-elements parent))))

(defn- exactly-one-child! [^Node parent name path]
  (let [elements (child-elements parent name)]
    (when-not (= 1 (count elements))
      (fail! "NuGet metadata does not contain exactly one required element"
             {:element name :path path :count (count elements)}))
    (first elements)))

(defn- element-attributes [^Element element]
  (let [attributes (.getAttributes element)]
    (into (sorted-map)
          (for [index (range (.getLength attributes))
                :let [attribute (.item attributes index)]]
            [(.getNodeName attribute) (.getNodeValue attribute)]))))

(defn- require-exact-children! [^Node parent expected path]
  (let [elements (child-elements parent)
        _ (doseq [^Element element elements]
            (require-nuspec-namespace!
             element (str path "/" (element-name element))))
        actual (mapv element-name elements)]
    (when-not (= (frequencies expected) (frequencies actual))
      (fail! "NuGet metadata contains missing, duplicate, or unexpected elements"
             {:path path :expected (vec expected) :actual actual}))))

(defn- require-exact-attributes! [^Element element expected path]
  (let [actual (element-attributes element)]
    (when-not (= expected actual)
      (fail! "NuGet metadata attributes do not match the required package contract"
             {:path path :expected expected :actual actual}))))

(def ^:private core-properties-prefix
  "package/services/metadata/core-properties/")

(def ^:private canonical-core-properties
  (str core-properties-prefix "core-properties.psmdcp"))

(defn- canonical-relationships [bytes]
  (let [next-id (atom 0)]
    (-> (String. bytes StandardCharsets/UTF_8)
        (str/replace #"/package/services/metadata/core-properties/[^\"]+\.psmdcp"
                     (str "/" canonical-core-properties))
        (str/replace #"Id=\"[^\"]+\""
                     (fn [_] (str "Id=\"R" (swap! next-id inc) "\"")))
        (.getBytes StandardCharsets/UTF_8))))

(defn- canonicalize-package!
  "Rewrites NuGet's random OPC relationship identifiers and core-properties
  filename, then emits a stable entry order and timestamps. Package payload
  bytes and declared package metadata are otherwise preserved."
  [source ^Path destination]
  (with-open [archive (ZipFile. (str source))]
    (let [source-entries (->> (enumeration-seq (.entries archive))
                              (remove #(.isDirectory ^java.util.zip.ZipEntry %))
                              vec)
          _ (validate-entry-layout! (mapv #(.getName ^ZipEntry %) source-entries)
                                    :raw-package)
          canonical-entries
          (mapv (fn [^ZipEntry entry]
                  (let [name (.getName entry)
                        bytes (with-open [input (.getInputStream archive entry)]
                                (.readAllBytes input))]
                    [(if (str/starts-with? name core-properties-prefix)
                       canonical-core-properties
                       name)
                     (if (= "_rels/.rels" name)
                       (canonical-relationships bytes)
                       bytes)]))
                source-entries)
          _ (validate-entry-layout! (mapv first canonical-entries)
                                    :canonical-package)
          entries (into (sorted-map) canonical-entries)]
      (Files/createDirectories (.getParent destination)
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (with-open [output (ZipOutputStream. (Files/newOutputStream
                                            destination
                                            (make-array java.nio.file.OpenOption 0)))]
        (doseq [[name bytes] entries]
          (let [entry (doto (ZipEntry. name) (.setTime 0))]
            (.putNextEntry output entry)
            (.write output bytes)
            (.closeEntry output))))
      destination)))

(defn- inspect-nuspec!
  [nuspec package target-framework expected-dependencies]
  (let [document (parse-xml! nuspec :nuspec)
        root (.getDocumentElement document)
        expected-namespace (if (seq expected-dependencies)
                             dependency-nuspec-namespace
                             base-nuspec-namespace)]
    (when-not (= "package" (element-name root))
      (fail! "NuGet nuspec root element is not package"
             {:expected "package" :actual (element-name root)}))
    (when-not (= expected-namespace (.getNamespaceURI root))
      (fail! "NuGet package uses the wrong nuspec schema namespace"
             {:expected expected-namespace :actual (.getNamespaceURI root)}))
    (require-exact-attributes! root {"xmlns" expected-namespace} "package")
    (require-exact-children! root ["metadata"] "package")
    (let [metadata (exactly-one-child! root "metadata" "package")
          configured-elements
          [["id" (:id package)]
           ["version" (:version package)]
           ["title" (:title package)]
           ["description" (:description package)]
           ["authors" (:authors package)]
           ["tags" (:tags package)]
           ["projectUrl" (:project-url package)]]]
      (require-exact-attributes! metadata {} "package/metadata")
      (doseq [[name expected] configured-elements]
        (let [element (exactly-one-child! metadata name "package/metadata")
              actual (.getTextContent element)]
          (require-exact-attributes! element {} (str "package/metadata/" name))
          (require-exact-children! element [] (str "package/metadata/" name))
          (when-not (= expected actual)
            (fail! (str "NuGet metadata does not match configured " name)
                   {:element name :expected expected :actual actual}))))
      (require-exact-children!
       metadata
       (concat (map first configured-elements)
               (when (:license-expression package) ["license"])
               ["repository" "dependencies"])
       "package/metadata")
      (when-let [expected-license (:license-expression package)]
        (let [license (exactly-one-child! metadata "license" "package/metadata")]
          (require-exact-attributes! license {"type" "expression"}
                                     "package/metadata/license")
          (require-exact-children! license [] "package/metadata/license")
          (when-not (= expected-license (.getTextContent license))
            (fail! "NuGet license metadata does not match the configured expression"
                   {:expected expected-license :actual (.getTextContent license)}))))
      (let [repository (exactly-one-child! metadata "repository" "package/metadata")
            expected-repository {"type" (:repository-type package)
                                 "url" (:repository-url package)
                                 "commit" (:repository-commit package)}
            actual-repository (element-attributes repository)]
        (require-exact-children! repository [] "package/metadata/repository")
        (when-not (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}"
                                       (or (:repository-commit package) "")))
          (fail! "Configured repository commit is not an exact Git object identity"
                 {:expected "<40-or-64-character-lowercase-hex>"
                  :actual (:repository-commit package)}))
        (when-not (= expected-repository actual-repository)
          (fail! "NuGet repository metadata does not match the configured repository"
                 {:expected expected-repository
                  :actual actual-repository})))
      (let [dependency-container
            (exactly-one-child! metadata "dependencies" "package/metadata")
            groups (child-elements dependency-container "group")]
        (require-exact-attributes! dependency-container {}
                                   "package/metadata/dependencies")
        (require-exact-children! dependency-container ["group"]
                                 "package/metadata/dependencies")
        (when-not (= 1 (count groups))
          (fail! "NuGet dependencies do not contain exactly one target-framework group"
                 {:expected target-framework :groups (count groups)}))
        (let [group (first groups)
              actual-framework (.getAttribute ^Element group "targetFramework")
              dependencies (mapv (fn [^Element dependency]
                                   (require-exact-children!
                                    dependency []
                                    "package/metadata/dependencies/group/dependency")
                                   (require-exact-attributes!
                                    dependency
                                    {"id" (.getAttribute dependency "id")
                                     "version" (.getAttribute dependency "version")
                                     "exclude" "Build,Analyzers"}
                                    "package/metadata/dependencies/group/dependency")
                                   {:id (.getAttribute dependency "id")
                                    :version (.getAttribute dependency "version")})
                                 (child-elements group "dependency"))
              expected-dependencies
              (mapv #(select-keys % [:id :version]) expected-dependencies)]
          (when-not (= target-framework actual-framework)
            (fail! "NuGet dependency group does not match the configured target framework"
                   {:expected target-framework :actual actual-framework}))
          (require-exact-attributes! group {"targetFramework" target-framework}
                                     "package/metadata/dependencies/group")
          (require-exact-children!
           group (repeat (count expected-dependencies) "dependency")
           "package/metadata/dependencies/group")
          (when-not (= expected-dependencies dependencies)
            (fail! "NuGet package dependencies do not match the generated project dependency closure"
                   {:expected expected-dependencies :actual dependencies}))
          dependencies)))))

(defn- inspect-content-types! [xml]
  (let [document (parse-xml! xml :content-types)
        root (.getDocumentElement document)
        defaults (child-elements root "Default")
        expected {"rels" "application/vnd.openxmlformats-package.relationships+xml"
                  "psmdcp" "application/vnd.openxmlformats-package.core-properties+xml"
                  "dll" "application/octet"
                  "nuspec" "application/octet"}]
    (when-not (and (= "Types" (element-name root))
                   (= content-types-namespace (.getNamespaceURI root)))
      (fail! "NuGet content-types metadata has the wrong root"
             {:expected [content-types-namespace "Types"]
              :actual [(.getNamespaceURI root) (element-name root)]}))
    (require-exact-attributes! root {"xmlns" content-types-namespace}
                               "[Content_Types].xml/Types")
    (require-exact-children! root (repeat (count expected) "Default")
                             "[Content_Types].xml/Types")
    (let [actual
          (into {}
                (for [^Element default defaults
                      :let [attributes (element-attributes default)]]
                  (do
                    (require-exact-children! default []
                                             "[Content_Types].xml/Types/Default")
                    (when-not (= #{"Extension" "ContentType"}
                                 (set (keys attributes)))
                      (fail! "NuGet content-type declaration has unexpected attributes"
                             {:expected #{"Extension" "ContentType"}
                              :actual attributes}))
                    [(get attributes "Extension") (get attributes "ContentType")])))]
      (when-not (and (= (count expected) (count actual)) (= expected actual))
        (fail! "NuGet content-type declarations differ from the exact package payload"
               {:expected expected :actual actual})))))

(defn- inspect-relationships! [xml nuspec-name]
  (let [document (parse-xml! xml :relationships)
        root (.getDocumentElement document)
        expected #{{"Type" "http://schemas.microsoft.com/packaging/2010/07/manifest"
                    "Target" (str "/" nuspec-name) "Id" "R1"}
                   {"Type" (str relationships-namespace "/metadata/core-properties")
                    "Target" (str "/" canonical-core-properties) "Id" "R2"}}]
    (when-not (and (= "Relationships" (element-name root))
                   (= relationships-namespace (.getNamespaceURI root)))
      (fail! "NuGet relationship metadata has the wrong root"
             {:expected [relationships-namespace "Relationships"]
              :actual [(.getNamespaceURI root) (element-name root)]}))
    (require-exact-attributes! root {"xmlns" relationships-namespace}
                               "_rels/.rels/Relationships")
    (require-exact-children! root (repeat (count expected) "Relationship")
                             "_rels/.rels/Relationships")
    (let [actual
          (set
           (for [^Element relationship (child-elements root "Relationship")]
             (do
               (require-exact-children! relationship []
                                        "_rels/.rels/Relationships/Relationship")
               (element-attributes relationship))))]
      (when-not (and (= (count expected) (count actual)) (= expected actual))
        (fail! "NuGet package relationships do not target the exact release metadata"
               {:expected expected :actual actual})))))

(defn- inspect-core-properties! [xml package]
  (let [document (parse-xml! xml :core-properties)
        root (.getDocumentElement document)
        expected-elements
        {[dc-namespace "creator"] (:authors package)
         [dc-namespace "description"] (:description package)
         [dc-namespace "identifier"] (:id package)
         [core-properties-namespace "version"] (:version package)
         [core-properties-namespace "keywords"] (:tags package)}]
    (when-not (and (= "coreProperties" (element-name root))
                   (= core-properties-namespace (.getNamespaceURI root)))
      (fail! "NuGet core-properties metadata has the wrong root"
             {:expected [core-properties-namespace "coreProperties"]
              :actual [(.getNamespaceURI root) (element-name root)]}))
    (require-exact-attributes!
     root
     {"xmlns" core-properties-namespace
      "xmlns:dc" dc-namespace
      "xmlns:dcterms" dcterms-namespace
      "xmlns:xsi" xsi-namespace}
     "core-properties/coreProperties")
    (let [children (child-elements root)
          actual-elements
          (into {}
                (for [^Element element children
                      :let [identity [(.getNamespaceURI element)
                                      (element-name element)]]
                      :when (not= [core-properties-namespace "lastModifiedBy"] identity)]
                  (do
                    (require-exact-attributes! element {}
                                               "core-properties/coreProperties/value")
                    (require-exact-children! element []
                                             "core-properties/coreProperties/value")
                    [identity (.getTextContent element)])))
          last-modified (filterv #(and (= core-properties-namespace
                                          (.getNamespaceURI ^Element %))
                                       (= "lastModifiedBy" (element-name %)))
                                 children)]
      (when-not (= (inc (count expected-elements)) (count children))
        (fail! "NuGet core properties contain missing, duplicate, or unexpected elements"
               {:expected (inc (count expected-elements))
                :actual (count children)}))
      (when-not (and (= (count expected-elements) (count actual-elements))
                     (= expected-elements actual-elements))
        (fail! "NuGet core properties do not mirror the configured package metadata"
               {:expected expected-elements :actual actual-elements}))
      (when-not (= 1 (count last-modified))
        (fail! "NuGet core properties do not contain exactly one package-writer identity"
               {:expected 1 :actual (count last-modified)}))
      (let [^Element element (first last-modified)]
        (require-exact-attributes! element {}
                                   "core-properties/coreProperties/lastModifiedBy")
        (require-exact-children! element []
                                 "core-properties/coreProperties/lastModifiedBy")
        (when (str/blank? (.getTextContent element))
          (fail! "NuGet core-properties package-writer identity is blank"
                 {:actual (.getTextContent element)}))))))

(defn- inspect-opc-envelope! [archive nuspec-name package]
  (inspect-content-types! (zip-text archive "[Content_Types].xml"))
  (inspect-relationships! (zip-text archive "_rels/.rels") nuspec-name)
  (inspect-core-properties! (zip-text archive canonical-core-properties) package))

(defn inspect-package!
  "Checks the package payload and metadata without extracting generated sources."
  ([artifact package target-framework assembly-name]
   (inspect-package! artifact package target-framework assembly-name []))
  ([artifact {:keys [id version title description authors tags project-url
                     repository-url repository-type repository-commit]}
    target-framework assembly-name
    expected-dependencies]
   (with-open [archive (ZipFile. (str artifact))]
     (let [entries (->> (enumeration-seq (.entries archive))
                        (map #(.getName %))
                        sort
                        vec)
           _ (validate-entry-layout! entries :inspected-package)
           nuspec-name (str id ".nuspec")
           assembly-entry (str "lib/" target-framework "/" assembly-name ".dll")
           package-assemblies (filterv #(re-find #"(?i)\.dll$" %) entries)
           nuspec (zip-text archive nuspec-name)
           forbidden (filterv #(or (re-find #"(?i)(^|/)(src|test|translator)(/|$)" %)
                                   (re-find #"(?i)\.(cs|clj|edn|java|kt|csproj)$" %)
                                   (re-find #"(?i)(source-map|diagnostics|generation-manifest)" %))
                              entries)]
       (when-not (= [assembly-entry] package-assemblies)
         (fail! "NuGet package library payload does not contain exactly its configured assembly"
                {:required assembly-entry :assemblies package-assemblies :entries entries}))
       (when-not nuspec
         (fail! "NuGet package does not contain its nuspec metadata"
                {:required nuspec-name :entries entries}))
       (let [dependencies (inspect-nuspec!
                           nuspec
                           {:id id :version version :title title :description description
                            :authors authors :tags tags :project-url project-url
                            :repository-url repository-url
                            :repository-type repository-type
                            :repository-commit repository-commit}
                           target-framework expected-dependencies)]
         (when (seq forbidden)
           (fail! "NuGet package contains translator, test, or generated-source internals"
                  {:forbidden forbidden :entries entries}))
         (let [expected-entries (->> ["[Content_Types].xml"
                                      "_rels/.rels"
                                      nuspec-name
                                      assembly-entry
                                      canonical-core-properties]
                                     sort
                                     vec)]
           (when-not (= expected-entries entries)
             (fail! "NuGet package layout differs from the exact release payload"
                    {:expected expected-entries :actual entries})))
         (inspect-opc-envelope! archive nuspec-name
                                {:id id :version version :description description
                                 :authors authors :tags tags})
         {:entries entries :assembly-entry assembly-entry :nuspec nuspec
          :dependencies dependencies})))))

(defn- consumer-project [package-id version target-framework]
  (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
       "  <PropertyGroup>\n"
       "    <OutputType>Exe</OutputType>\n"
       "    <TargetFramework>" (xml-escape target-framework) "</TargetFramework>\n"
       "    <Nullable>enable</Nullable>\n"
       "    <ImplicitUsings>disable</ImplicitUsings>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       "    <PackageReference Include=\"" (xml-escape package-id)
       "\" Version=\"" (xml-escape version) "\" />\n"
       "  </ItemGroup>\n"
       "</Project>\n"))

(defn- csharp-string [value]
  (str "\""
       (-> (str value)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\r" "\\r")
           (str/replace "\n" "\\n"))
       "\""))

(defn- compile-only-consumer [success-message compile-types]
  (str "using System;\n\n"
       "internal static class Program\n"
       "{\n"
       "    private static void Main()\n"
       "    {\n"
       (apply str
              (for [type (sort compile-types)]
                (str "        _ = typeof(global::" type ");\n")))
       "        Console.WriteLine(" (csharp-string success-message) ");\n"
       "    }\n"
       "}\n"))

(defn inspect-consumer-dependencies!
  "Proves that the generated consumer project has one package reference, no
  source/project reference escape hatch, and that restore populated only the
  exact dependency-closed package identities in its isolated cache."
  [^Path project-file ^Path assets-file ^Path packages primary-identity identities]
  (let [project (Files/readString project-file)
        package-references (re-seq #"<PackageReference\s+Include=\"([^\"]+)\"\s+Version=\"([^\"]+)\"\s*/>"
                                   project)
        expected-reference [(:id primary-identity) (:version primary-identity)]
        forbidden-project (->> [#"<ProjectReference\b" #"<Compile\b"
                                #"<Reference\b" #"(?i)target/generated"
                                #"(?i)\.\./.*\.csproj"]
                               (filter #(re-find % project))
                               (mapv str))]
    (when-not (= [expected-reference] (mapv #(vec (rest %)) package-references))
      (fail! "Independent consumer does not reference exactly the primary package"
             {:project-file (str project-file) :expected expected-reference
              :actual (mapv #(vec (rest %)) package-references)}))
    (when (seq forbidden-project)
      (fail! "Independent consumer contains a source, assembly, or project-reference escape hatch"
             {:project-file (str project-file) :forbidden forbidden-project}))
    (when-not (paths/regular-file? assets-file)
      (fail! "Independent consumer restore did not produce a dependency graph"
             {:assets-file (str assets-file)}))
    (let [assets (Files/readString assets-file)
          project-libraries (re-seq #"\"type\"\s*:\s*\"project\"" assets)
          expected-cache-roots (->> identities (map :id) (map str/lower-case) sort vec)
          actual-cache-roots (->> (child-directories packages)
                                  (map #(str/lower-case (str (.getFileName ^Path %))))
                                  sort vec)]
      (when (seq project-libraries)
        (fail! "Restored package graph leaked a project dependency"
               {:assets-file (str assets-file) :project-library-count (count project-libraries)}))
      (doseq [{:keys [id version] :as identity} identities]
        (let [package-root (paths/resolve-path packages (str/lower-case id))
              actual-versions (->> (child-directories package-root)
                                   (map #(str (.getFileName ^Path %)))
                                   sort
                                   vec)
              key (str id "/" version)
              artifact (paths/resolve-path package-root version
                                           (str (str/lower-case id) "." version ".nupkg"))]
          (when-not (str/includes? assets (str "\"" key "\""))
            (fail! "Restored package graph is missing an exact package identity"
                   {:identity key :assets-file (str assets-file)}))
          (when-not (= [version] actual-versions)
            (fail! "Isolated package cache contains versions outside the packed dependency closure"
                   {:identity key :expected [version] :actual actual-versions
                    :package-root (str package-root)}))
          (when-not (paths/regular-file? artifact)
            (fail! "Isolated package cache is missing an exact package artifact"
                   {:identity key :artifact (str artifact)}))
          (let [actual-hash (sha256 artifact)]
            (when-not (= (:sha256 identity) actual-hash)
              (fail! "Restored package artifact does not match the deterministic packed artifact"
                     {:identity key
                      :expected (:sha256 identity)
                      :actual actual-hash
                      :artifact (str artifact)})))))
      (when-not (= expected-cache-roots actual-cache-roots)
        (fail! "Isolated package cache contains packages outside the packed dependency closure"
               {:expected expected-cache-roots :actual actual-cache-roots
                :packages (str packages)}))
      {:package-reference expected-reference
       :packages (mapv #(select-keys % [:id :version :sha256]) identities)
       :assets-file (str assets-file)})))

(defn- nuget-config [^Path feed package-ids]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
       "<configuration>\n"
       "  <packageSources>\n"
       "    <clear />\n"
       "    <add key=\"vibeformer-local\" value=\"" (xml-escape feed) "\" />\n"
       "    <add key=\"nuget.org\" value=\"https://api.nuget.org/v3/index.json\" />\n"
       "  </packageSources>\n"
       "  <packageSourceMapping>\n"
       "    <packageSource key=\"vibeformer-local\">\n"
       (apply str (for [package-id (sort package-ids)]
                    (str "      <package pattern=\"" (xml-escape package-id) "\" />\n")))
       "    </packageSource>\n"
       "    <packageSource key=\"nuget.org\">\n"
       "      <package pattern=\"Microsoft.*\" />\n"
       "    </packageSource>\n"
       "  </packageSourceMapping>\n"
       "</configuration>\n"))

(defn- installed-runtime-target! [run-command! root library-target]
  (let [result (run-command! {:command ["dotnet" "--list-runtimes"]
                              :directory root})
        versions (->> (str/split-lines (:output result))
                      (keep #(when-let [[_ major minor]
                                        (re-find #"^Microsoft\.NETCore\.App (\d+)\.(\d+)\." %)]
                               [(parse-long major) (parse-long minor)]))
                      sort
                      vec)
        [_ library-major library-minor]
        (re-matches #"net(\d+)\.(\d+)" library-target)
        minimum [(parse-long library-major) (parse-long library-minor)]
        selected (last (filter #(not (neg? (compare % minimum))) versions))]
    (when-not selected
      (fail! "No installed .NET runtime can execute a consumer of the generated package"
             {:library-target library-target :installed versions :output (:output result)}))
    (str "net" (first selected) "." (second selected))))

(defn- package-specs [generation]
  (let [dependency-specs
        (mapv (fn [{:keys [profile destination] :as emission}]
                (when-not destination
                  (fail! "Dependency emission is missing its destination configuration"
                         {:profile profile :emission (keys emission)}))
                {:profile profile :emission emission :destination destination
                 :expected-dependencies []})
              (:dependency-emissions generation))
        expected-dependencies (mapv #(get-in % [:destination :package]) dependency-specs)
        expected-assembly-dependencies
        (mapv (fn [{:keys [destination]}]
                {:assembly-name (get-in destination [:project :assembly-name])
                 :package-id (get-in destination [:package :id])
                 :version (get-in destination [:package :version])
                 :target-framework (get-in destination [:project :target-framework])})
              dependency-specs)]
    (conj dependency-specs
          {:profile (get-in generation [:generation-profile :profile])
           :emission (:emission generation)
           :destination (:destination generation)
           :expected-dependencies expected-dependencies
           :expected-assembly-dependencies expected-assembly-dependencies
           :primary? true})))

(defn- package-reproducibility-plan [specs]
  (mapv (fn [{:keys [profile destination expected-dependencies
                     expected-assembly-dependencies primary?]}]
          {:profile profile
           :destination destination
           :expected-dependencies
           (mapv #(select-keys % [:id :version]) expected-dependencies)
           :expected-assembly-dependencies expected-assembly-dependencies
           :primary? (boolean primary?)})
        specs))

(defn- repository-commit! [run-command! root]
  (let [result (run-command! {:command ["git" "rev-parse" "--verify" "HEAD"]
                              :directory root})
        commit (str/trim (:output result))]
    (when-not (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" commit))
      (fail! "Could not determine the exact Git commit for NuGet repository metadata"
             {:expected "<40-or-64-character-lowercase-hex>"
              :actual commit :output (:output result)}))
    commit))

(defn- pack-project! [run-command! build-configuration ^Path output
                      {:keys [emission]}]
  (let [project-root (:project-root emission)
        project-file (:project-file emission)]
    (run-command! {:command ["dotnet" "pack" (str project-file)
                             "--nologo" "--verbosity:minimal"
                             "--configuration" build-configuration
                             "--no-build" "--no-restore" "--output" (str output)]
                   :directory project-root})))

(defn- inspect-package-assembly!
  [run-command! root artifact assembly-entry assembly-name verified-assembly
   package-assembly-names expected-dependency-assemblies expected-resources]
  (let [assembly-artifact
        (verify-packaged-assembly! artifact assembly-entry verified-assembly)
        dependency-arguments
        (mapcat (fn [{:keys [assembly-name package-id version target-framework]}]
                  [assembly-name
                   (str (package-artifact! (.getParent ^Path artifact)
                                           package-id version))
                   (str "lib/" target-framework "/" assembly-name ".dll")])
                expected-dependency-assemblies)
        inspector (paths/resolve-path root "vibeformer" "validation"
                                      "package-inspector" "PackageInspector.csproj")
        result (run-command!
                {:command (into
                           ["dotnet" "run" "--project" (str inspector)
                            "--configuration" "Release" "--verbosity" "quiet"
                            "--" (str artifact) assembly-entry assembly-name
                            (str (count package-assembly-names))]
                           (concat package-assembly-names
                                   [(str (count expected-dependency-assemblies))]
                                   dependency-arguments
                                   expected-resources))
                 :directory root})]
    (when-not (str/includes? (:output result)
                             (str "Embedded resource inspection passed: "
                                  (count expected-resources)))
      (fail! "Package resource inspector did not report the expected manifest"
             {:artifact (str artifact) :expected expected-resources
              :output (:output result)}))
    (let [[_ inspected-assembly assembly-version]
          (re-find #"Assembly identity inspection passed: ([^,\r\n]+), Version=([0-9.]+)"
                   (:output result))
          [_ types members fingerprint]
          (re-find #"Public surface inspection passed: (\d+) types, (\d+) members, SHA-256 ([0-9a-f]{64})"
                   (:output result))]
      (when-not (= assembly-name inspected-assembly)
        (fail! "Package assembly inspector did not report the expected assembly identity"
               {:artifact (str artifact) :expected assembly-name
                :actual inspected-assembly :output (:output result)}))
      (when-not fingerprint
        (fail! "Package assembly inspector did not report a public-surface fingerprint"
               {:artifact (str artifact) :output (:output result)}))
      {:resources (count expected-resources)
       :assembly-artifact assembly-artifact
       :assembly-identity {:name inspected-assembly :version assembly-version
                           :dependency-assemblies
                           (mapv :assembly-name expected-dependency-assemblies)}
       :public-surface {:types (parse-long types)
                        :members (parse-long members)
                        :sha256 fingerprint}
       :run result})))

(defn pack-verified-profile!
  "Cleanly generates, compiles, and packs a profile twice, then publishes the
  byte-identical inspected dependency closure to a fresh local feed. This does
  not perform independent package consumption."
  ([] (pack-verified-profile! {}))
  ([{:keys [workspace-root profile verify-fn run-command! inspect-resources-fn]
     :or {verify-fn compiler/verify-clean-build!
          run-command! process/run!
          inspect-resources-fn inspect-package-assembly!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         profile (or profile "pkl-parser")
         first-verification
         (verify-fn {:workspace-root root :profile profile
                     :build-configuration "Release"})
         first-build-configuration (:build-configuration first-verification)
         first-specs (package-specs (:generation first-verification))
         first-output (Files/createTempDirectory
                       "vibeformer-first-clean-pack-"
                       (make-array java.nio.file.attribute.FileAttribute 0))]
     (try
       (doseq [spec first-specs]
         (pack-project! run-command! first-build-configuration first-output spec))
       (let [verification
             (verify-fn {:workspace-root root :profile profile
                         :build-configuration "Release"})
             generation (:generation verification)
             build-configuration (:build-configuration verification)
             specs (package-specs generation)
             first-plan (package-reproducibility-plan first-specs)
             second-plan (package-reproducibility-plan specs)]
         (when-not (and (= first-build-configuration build-configuration)
                        (= first-plan second-plan))
           (fail! "Independent clean builds produced different NuGet package plans"
                  {:profile profile
                   :first-build-configuration first-build-configuration
                   :second-build-configuration build-configuration
                   :first first-plan :second second-plan}))
         (let [package-assembly-names
               (mapv #(get-in % [:destination :project :assembly-name]) specs)
               repository-commit
               (or (get-in generation [:destination :package :repository-commit])
                   (repository-commit! run-command! root))
               proof-root (harness/clean-directory!
                           (paths/resolve-path root "vibeformer" "target" "package-proof"))
               second-output (doto (paths/resolve-path proof-root "second-pack")
                               (Files/createDirectories
                                (make-array java.nio.file.attribute.FileAttribute 0)))
               first-canonical (doto (paths/resolve-path proof-root "first-canonical")
                                 (Files/createDirectories
                                  (make-array java.nio.file.attribute.FileAttribute 0)))
               second-canonical (doto (paths/resolve-path proof-root "second-canonical")
                                  (Files/createDirectories
                                   (make-array java.nio.file.attribute.FileAttribute 0)))
               feed (doto (paths/resolve-path proof-root "feed")
                      (Files/createDirectories
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
           (doseq [spec specs]
             (pack-project! run-command! build-configuration second-output spec))
           (let [packages
                 (mapv
                  (fn [{:keys [profile emission destination expected-dependencies
                               expected-assembly-dependencies primary?]}]
                    (let [{:keys [id version] :as package} (:package destination)
                          target-framework
                          (get-in destination [:project :target-framework])
                          assembly-name
                          (get-in destination [:project :assembly-name])
                          verified-assembly
                          (paths/resolve-path (:project-root emission) "bin"
                                              build-configuration target-framework
                                              (str assembly-name ".dll"))
                          raw-first (package-artifact! first-output id version)
                          raw-second (package-artifact! second-output id version)
                          filename (str (.getFileName ^Path raw-first))
                          first-artifact
                          (canonicalize-package!
                           raw-first (paths/resolve-path first-canonical filename))
                          second-artifact
                          (canonicalize-package!
                           raw-second (paths/resolve-path second-canonical filename))
                          first-hash (sha256 first-artifact)
                          second-hash (sha256 second-artifact)]
                      (when-not (= first-hash second-hash)
                        (fail! "Independent clean builds did not produce byte-identical NuGet packages"
                               {:profile profile :first first-hash :second second-hash}))
                      (let [artifact
                            (paths/resolve-path
                             feed (str (.getFileName ^Path first-artifact)))
                            _ (Files/copy
                               first-artifact artifact
                               (into-array
                                StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
                            inspection
                            (inspect-package!
                             artifact
                             (assoc package :repository-commit repository-commit)
                             target-framework assembly-name expected-dependencies)
                            expected-resources
                            (->> (:resource-artifacts emission)
                                 (map :logical-name) sort vec)
                            resource-proof
                            (inspect-resources-fn
                             run-command! root artifact
                             (:assembly-entry inspection) assembly-name
                             verified-assembly package-assembly-names
                             (or expected-assembly-dependencies [])
                             expected-resources)]
                        {:profile profile :primary? primary? :artifact artifact
                         :identity {:id id :version version :sha256 first-hash
                                    :file (str (.getFileName ^Path artifact))}
                         :inspection inspection :resource-proof resource-proof
                         :public-surface (:public-surface resource-proof)
                         :resources expected-resources})))
                  specs)
                 primary (first (filter :primary? packages))
                 summary
                 {:profile profile
                  :clean-builds 2
                  :repository-commit repository-commit
                  :packages (mapv :identity packages)
                  :resource-counts
                  (into (sorted-map)
                        (map (juxt #(get-in % [:identity :id])
                                   #(count (:resources %))) packages))
                  :public-surfaces
                  (into (sorted-map)
                        (map (juxt #(get-in % [:identity :id])
                                   :public-surface) packages))}]
             (println "Reproducible dependency-closed NuGet packing passed:"
                      (pr-str summary))
             {:verification verification :proof-root proof-root :feed feed
              :packages packages :artifact (:artifact primary)
              :identity (:identity primary) :inspection (:inspection primary)
              :summary summary})))
       (finally
         (delete-tree! first-output))))))

(defn verify-package-consumption!
  "Runs clean generation/compilation, packs it, and proves isolated consumption."
  ([] (verify-package-consumption! {}))
  ([{:keys [workspace-root profile verify-fn run-command! pack-fn]
     :or {verify-fn compiler/verify-clean-build!
          pack-fn pack-verified-profile!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         profile (or profile "pkl-parser")
         package-proof (pack-fn {:workspace-root root :profile profile
                                 :verify-fn verify-fn :run-command! run-command!})
         verification (:verification package-proof)
         generation (:generation verification)
         configuration (:destination generation)
         consumer-profile (or (:package-consumer configuration)
                              (fail! "Destination has no independent package-consumer contract"
                                     {:profile profile}))
         {:keys [id version]} (:package configuration)
         target-framework (get-in configuration [:project :target-framework])
         consumer-target-framework
         (installed-runtime-target! run-command! root target-framework)
         proof-root (:proof-root package-proof)
         feed (:feed package-proof)
         consumer (doto (paths/resolve-path proof-root "consumer")
                    (Files/createDirectories
                     (make-array java.nio.file.attribute.FileAttribute 0)))
         packages (doto (paths/resolve-path proof-root "packages")
                    (Files/createDirectories
                     (make-array java.nio.file.attribute.FileAttribute 0)))
         consumer-project-file (paths/resolve-path consumer
                                                   (:project-file consumer-profile))
         nuget-config-file (paths/resolve-path consumer "NuGet.Config")
         consumer-source (paths/resolve-path consumer "Program.cs")
         fixture-source (when (= :source-file (:strategy consumer-profile))
                          (paths/resolve-path root "vibeformer" "validation"
                                              "package-consumer"
                                              (:fixture-file consumer-profile)))
         identities (mapv :identity (:packages package-proof))]
     (when (and fixture-source (not (paths/regular-file? fixture-source)))
       (fail! "Independent package-consumer source is missing"
              {:path (str fixture-source)}))
     (let [artifact (:artifact package-proof)
           inspection (:inspection package-proof)]
       (write-text! consumer-project-file
                    (consumer-project id version consumer-target-framework))
       (write-text! nuget-config-file (nuget-config feed (map :id identities)))
       (if fixture-source
         (Files/copy fixture-source consumer-source
                     (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
         (write-text! consumer-source
                      (compile-only-consumer (:success-message consumer-profile)
                                             (:compile-types consumer-profile))))
       (run-command! {:command ["dotnet" "restore" (str consumer-project-file)
                                "--configfile" (str nuget-config-file)
                                "--packages" (str packages)
                                "--no-cache" "--force" "--force-evaluate"]
                      :directory consumer})
       (let [dependency-proof
             (inspect-consumer-dependencies!
              consumer-project-file (paths/resolve-path consumer "obj" "project.assets.json")
              packages (:identity package-proof) identities)]
         (run-command! {:command ["dotnet" "build" (str consumer-project-file)
                                  "--nologo" "--verbosity:minimal" "--no-restore"
                                  "--no-incremental" "-warnaserror"]
                        :directory consumer})
         (let [run-result
               (run-command! {:command ["dotnet" "run"
                                        "--project" (str consumer-project-file)
                                        "--no-build" "--no-restore"]
                              :directory consumer})
               identity {:id id :version version :sha256 (sha256 artifact)
                         :file (str (.getFileName ^Path artifact))}]
           (when-not (str/includes? (:output run-result)
                                    (:success-message consumer-profile))
             (fail! "Independent package consumer did not report successful behavior checks"
                    {:output (:output run-result)}))
           (println "Independent NuGet consumption passed:" (pr-str identity))
           {:verification verification
            :artifact artifact
            :identity identity
            :inspection inspection
            :packages (:packages package-proof)
            :feed feed
            :packing-summary (:summary package-proof)
            :dependency-proof dependency-proof
            :proof-root proof-root
            :packages-root packages
            :consumer-root consumer
            :run run-result}))))))
