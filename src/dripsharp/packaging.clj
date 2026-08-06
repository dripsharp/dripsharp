(ns dripsharp.packaging
  "Local NuGet packaging and isolated independent-consumer verification."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.authorship :as authorship]
            [dripsharp.authorship-report :as authorship-report]
            [dripsharp.compiler :as compiler]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.io ByteArrayInputStream PushbackReader StringReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files Path StandardCopyOption]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Element Node]))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :package-consumption-failed))))

(defn- read-single-edn!
  [^Path file]
  (let [eof (Object.)
        [value trailing]
        (try
          (with-open [reader
                      (PushbackReader.
                       (StringReader.
                        (Files/readString file StandardCharsets/UTF_8)))]
            [(edn/read {:eof eof} reader)
             (edn/read {:eof eof} reader)])
          (catch RuntimeException error
            (throw
             (ex-info "Generation manifest is not valid EDN"
                      {:kind :package-consumption-failed
                       :manifest (str file)
                       :reason :invalid-edn}
                      error))))]
    (when (identical? eof value)
      (fail! "Generation manifest is empty"
             {:manifest (str file)
              :reason :empty-edn}))
    (when-not (identical? eof trailing)
      (fail! "Generation manifest contains trailing EDN data"
             {:manifest (str file)
              :reason :trailing-data}))
    value))

(def ^:private xml-escape util/xml-escape)

(def ^:private write-text! util/write-text!)

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

(defn- artifact!
  [^Path feed package-id version extension]
  (let [packages (filter #(str/ends-with?
                           (str/lower-case (str %))
                           (str "." extension))
                         (regular-files feed))
        expected (str/lower-case (str package-id "." version "." extension))
        matches (filter #(= expected (str/lower-case (str (.getFileName ^Path %))))
                        packages)]
    (when-not (= 1 (count matches))
      (fail! "NuGet feed does not contain exactly one artifact for the configured identity"
             {:feed (str feed) :expected expected :extension extension
              :artifacts (mapv str packages)}))
    (first matches)))

(defn- package-artifact! [^Path feed package-id version]
  (artifact! feed package-id version "nupkg"))

(defn- symbol-artifact! [^Path feed package-id version]
  (artifact! feed package-id version "snupkg"))

(defn- zip-text [^ZipFile archive entry-name]
  (when-let [entry (.getEntry archive entry-name)]
    (with-open [input (.getInputStream archive entry)]
      (String. (.readAllBytes input) StandardCharsets/UTF_8))))

(def ^:private sha256 util/sha256-file)

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
                     (util/digest-input "SHA-256" input))]
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
          (cond->
           [["id" (:id package)]
            ["version" (:version package)]
            ["title" (:title package)]
            ["description" (:description package)]
            ["authors" (:authors package)]
            ["tags" (:tags package)]
            ["projectUrl" (:project-url package)]]
            (:readme package)
            (conj ["readme" (:readme package)])
            (:copyright package)
            (conj ["copyright" (:copyright package)]))]
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
               (when (or (:license-expression package) (:license-file package))
                 ["license" "licenseUrl"])
               ["repository" "dependencies"])
       "package/metadata")
      (when-let [expected-license (or (:license-expression package)
                                      (:license-file package))]
        (let [license (exactly-one-child! metadata "license" "package/metadata")
              license-url (exactly-one-child! metadata "licenseUrl" "package/metadata")
              file-license? (boolean (:license-file package))
              expected-url (if file-license?
                             "https://aka.ms/deprecateLicenseUrl"
                             (str "https://licenses.nuget.org/" expected-license))]
          (require-exact-attributes! license
                                     {"type" (if file-license? "file" "expression")}
                                     "package/metadata/license")
          (require-exact-children! license [] "package/metadata/license")
          (when-not (= expected-license (.getTextContent license))
            (fail! "NuGet license metadata does not match the configured expression"
                   {:expected expected-license :actual (.getTextContent license)}))
          (require-exact-attributes! license-url {} "package/metadata/licenseUrl")
          (require-exact-children! license-url [] "package/metadata/licenseUrl")
          (when-not (= expected-url (.getTextContent license-url))
            (fail! "NuGet license URL does not match the configured expression"
                   {:expected expected-url :actual (.getTextContent license-url)}))))
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

(defn- package-extension [path]
  (some-> (re-find #"\.([^.\/]+)$" path) second str/lower-case))

(defn- inspect-content-types!
  ([xml package-files]
   (inspect-content-types! xml "dll" package-files))
  ([xml payload-extension package-files]
   (let [document (parse-xml! xml :content-types)
         root (.getDocumentElement document)
         defaults (child-elements root "Default")
         expected
         (into {"rels" "application/vnd.openxmlformats-package.relationships+xml"
                "psmdcp" "application/vnd.openxmlformats-package.core-properties+xml"
                payload-extension "application/octet"
                "nuspec" "application/octet"}
               (for [{:keys [path]} package-files
                     :let [extension (package-extension path)]
                     :when extension]
                 [extension "application/octet"]))]
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
                {:expected expected :actual actual}))))))

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

(defn- inspect-opc-envelope!
  ([archive nuspec-name package package-files]
   (inspect-opc-envelope! archive nuspec-name package "dll" package-files))
  ([archive nuspec-name package payload-extension package-files]
   (inspect-content-types! (zip-text archive "[Content_Types].xml")
                           payload-extension package-files)
   (inspect-relationships! (zip-text archive "_rels/.rels") nuspec-name)
   (inspect-core-properties! (zip-text archive canonical-core-properties) package)))

(def ^:private sha256-bytes util/sha256-bytes)

(defn- inspect-symbol-nuspec!
  [nuspec package target-framework expected-dependencies]
  (let [document (parse-xml! nuspec :symbol-nuspec)
        root (.getDocumentElement document)
        expected-namespace (if (seq expected-dependencies)
                             dependency-nuspec-namespace
                             base-nuspec-namespace)]
    (when-not (and (= "package" (element-name root))
                   (= expected-namespace (.getNamespaceURI root)))
      (fail! "NuGet symbol package uses the wrong nuspec root or namespace"
             {:expected [expected-namespace "package"]
              :actual [(.getNamespaceURI root) (element-name root)]}))
    (require-exact-attributes! root {"xmlns" expected-namespace} "package")
    (require-exact-children! root ["metadata"] "package")
    (let [metadata (exactly-one-child! root "metadata" "package")
          configured-elements
          (cond->
           [["id" (:id package)]
            ["version" (:version package)]
            ["title" (:title package)]
            ["projectUrl" (:project-url package)]
            ["description" (:description package)]
            ["tags" (:tags package)]]
            (:readme package)
            (conj ["readme" (:readme package)])
            (:copyright package)
            (conj ["copyright" (:copyright package)]))]
      (require-exact-attributes! metadata {} "package/metadata")
      (doseq [[name expected] configured-elements]
        (let [element (exactly-one-child! metadata name "package/metadata")]
          (require-exact-attributes! element {} (str "package/metadata/" name))
          (require-exact-children! element [] (str "package/metadata/" name))
          (when-not (= expected (.getTextContent element))
            (fail! "NuGet symbol metadata differs from the configured package"
                   {:element name :expected expected
                    :actual (.getTextContent element)}))))
      (require-exact-children!
       metadata
       (concat (map first configured-elements)
               ["packageTypes" "repository" "dependencies"])
       "package/metadata")
      (let [package-types
            (exactly-one-child! metadata "packageTypes" "package/metadata")
            package-type
            (exactly-one-child! package-types "packageType"
                                "package/metadata/packageTypes")]
        (require-exact-attributes! package-types {}
                                   "package/metadata/packageTypes")
        (require-exact-children! package-types ["packageType"]
                                 "package/metadata/packageTypes")
        (require-exact-children! package-type []
                                 "package/metadata/packageTypes/packageType")
        (require-exact-attributes!
         package-type {"name" "SymbolsPackage"}
         "package/metadata/packageTypes/packageType"))
      (let [repository
            (exactly-one-child! metadata "repository" "package/metadata")
            expected {"type" (:repository-type package)
                      "url" (:repository-url package)
                      "commit" (:repository-commit package)}]
        (require-exact-children! repository [] "package/metadata/repository")
        (require-exact-attributes! repository expected
                                   "package/metadata/repository"))
      (let [container
            (exactly-one-child! metadata "dependencies" "package/metadata")
            group (exactly-one-child! container "group"
                                      "package/metadata/dependencies")
            dependencies
            (mapv
             (fn [^Element dependency]
               (require-exact-children!
                dependency [] "package/metadata/dependencies/group/dependency")
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
        (require-exact-attributes! container {}
                                   "package/metadata/dependencies")
        (require-exact-children! container ["group"]
                                 "package/metadata/dependencies")
        (require-exact-attributes! group {"targetFramework" target-framework}
                                   "package/metadata/dependencies/group")
        (require-exact-children!
         group (repeat (count expected-dependencies) "dependency")
         "package/metadata/dependencies/group")
        (when-not (= target-framework (.getAttribute ^Element group
                                                     "targetFramework"))
          (fail! "NuGet symbol dependency group targets the wrong framework"
                 {:expected target-framework
                  :actual (.getAttribute ^Element group "targetFramework")}))
        (when-not (= expected-dependencies dependencies)
          (fail! "NuGet symbol dependencies differ from the release package"
                 {:expected expected-dependencies :actual dependencies}))
        dependencies))))

(defn inspect-symbol-package!
  "Requires one exact portable PDB symbol payload whose bytes match the clean
  build output, plus the exact package identity and dependency metadata."
  [artifact package target-framework assembly-name expected-dependencies
   verified-pdb]
  (when-not (paths/regular-file? verified-pdb)
    (fail! "Verified clean-build portable PDB is missing"
           {:artifact (str artifact) :verified-pdb (str verified-pdb)}))
  (with-open [archive (ZipFile. (str artifact))]
    (let [entries (->> (enumeration-seq (.entries archive))
                       (map #(.getName %))
                       sort
                       vec)
          _ (validate-entry-layout! entries :inspected-symbol-package)
          nuspec-name (str (:id package) ".nuspec")
          pdb-entry (str "lib/" target-framework "/" assembly-name ".pdb")
          expected-entries (sort ["[Content_Types].xml"
                                  "_rels/.rels"
                                  nuspec-name
                                  pdb-entry
                                  canonical-core-properties])
          nuspec (zip-text archive nuspec-name)]
      (when-not (= (vec expected-entries) entries)
        (fail! "NuGet symbol package layout differs from the exact release payload"
               {:expected (vec expected-entries) :actual entries}))
      (when-not nuspec
        (fail! "NuGet symbol package does not contain its nuspec metadata"
               {:required nuspec-name :entries entries}))
      (let [dependencies
            (inspect-symbol-nuspec!
             nuspec package target-framework expected-dependencies)
            entry (.getEntry archive pdb-entry)
            bytes (when entry
                    (with-open [input (.getInputStream archive entry)]
                      (.readAllBytes input)))
            expected-hash (sha256 verified-pdb)
            actual-hash (some-> bytes sha256-bytes)]
        (when-not (= expected-hash actual-hash)
          (fail! "Packaged portable PDB does not match the verified clean build"
                 {:pdb-entry pdb-entry :verified-pdb (str verified-pdb)
                  :expected expected-hash :actual actual-hash}))
        (when-not (and bytes
                       (<= 4 (alength ^bytes bytes))
                       (= "BSJB" (String. ^bytes bytes 0 4
                                          StandardCharsets/US_ASCII)))
          (fail! "NuGet symbol package does not contain a portable PDB"
                 {:pdb-entry pdb-entry :magic
                  (when bytes
                    (util/hex (take (min 4 (alength ^bytes bytes)) bytes)))}))
        (inspect-opc-envelope!
         archive nuspec-name
         package
         "pdb" [])
        {:entries entries :pdb-entry pdb-entry :pdb-sha256 actual-hash
         :nuspec nuspec :dependencies dependencies}))))

(defn inspect-package!
  "Checks the package payload and metadata without extracting generated sources."
  ([artifact package target-framework assembly-name]
   (inspect-package! artifact package target-framework assembly-name [] []))
  ([artifact package target-framework assembly-name
    expected-dependencies]
   (inspect-package! artifact package target-framework assembly-name
                     expected-dependencies []))
  ([artifact {:keys [id version title description authors tags project-url
                     repository-url repository-type repository-commit
                     license-expression copyright readme]}
    target-framework assembly-name
    expected-dependencies expected-package-files]
   (with-open [archive (ZipFile. (str artifact))]
     (let [entries (->> (enumeration-seq (.entries archive))
                        (map #(.getName %))
                        sort
                        vec)
           expected-package-files
           (mapv (fn [{:keys [kind path sha256]}]
                   {:kind kind :path path :sha256 sha256})
                 expected-package-files)
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
                           (assoc
                            {:id id :version version :title title
                             :description description :authors authors :tags tags
                             :project-url project-url :repository-url repository-url
                             :repository-type repository-type
                             :repository-commit repository-commit
                             :license-expression license-expression
                             :copyright copyright :readme readme}
                            :license-file
                            (some #(when (= :license (:kind %)) (:path %))
                                  expected-package-files))
                           target-framework expected-dependencies)]
         (when (seq forbidden)
           (fail! "NuGet package contains translator, test, or generated-source internals"
                  {:forbidden forbidden :entries entries}))
         (let [expected-entries (->> ["[Content_Types].xml"
                                      "_rels/.rels"
                                      nuspec-name
                                      assembly-entry
                                      canonical-core-properties]
                                     (concat (map :path expected-package-files))
                                     sort
                                     vec)]
           (when-not (= expected-entries entries)
             (fail! "NuGet package layout differs from the exact release payload"
                    {:expected expected-entries :actual entries})))
         (doseq [{:keys [path sha256]} expected-package-files]
           (let [entry (.getEntry archive path)
                 actual
                 (when entry
                   (with-open [input (.getInputStream archive entry)]
                     (sha256-bytes (.readAllBytes input))))]
             (when-not (= sha256 actual)
               (fail! "NuGet package legal or notice payload does not match its pinned input"
                      {:path path :expected sha256 :actual actual}))))
         (inspect-opc-envelope! archive nuspec-name
                                {:id id :version version :description description
                                 :authors authors :tags tags}
                                expected-package-files)
         {:entries entries :assembly-entry assembly-entry :nuspec nuspec
          :dependencies dependencies
          :package-files expected-package-files})))))

(defn- consumer-project [package-identities target-framework]
  (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
       "  <PropertyGroup>\n"
       "    <OutputType>Exe</OutputType>\n"
       "    <TargetFramework>" (xml-escape target-framework) "</TargetFramework>\n"
       "    <Nullable>enable</Nullable>\n"
       "    <ImplicitUsings>disable</ImplicitUsings>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       (apply
        str
        (for [{:keys [id version]} (sort-by :id package-identities)]
          (str "    <PackageReference Include=\"" (xml-escape id)
               "\" Version=\"" (xml-escape version) "\" />\n")))
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
        primary-identities (if (map? primary-identity)
                             [primary-identity]
                             (vec primary-identity))
        expected-references
        (mapv (juxt :id :version) (sort-by :id primary-identities))
        forbidden-project (->> [#"<ProjectReference\b" #"<Compile\b"
                                #"<Reference\b" #"(?i)target/generated"
                                #"(?i)\.\./.*\.csproj"]
                               (filter #(re-find % project))
                               (mapv str))]
    (when-not (= expected-references
                 (mapv #(vec (rest %)) package-references))
      (fail! "Independent consumer does not reference exactly its selected packages"
             {:project-file (str project-file) :expected expected-references
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
      {:package-reference (when (= 1 (count expected-references))
                            (first expected-references))
       :package-references expected-references
       :packages (mapv #(select-keys % [:id :version :sha256]) identities)
       :assets-file (str assets-file)})))

(defn- nuget-config [^Path feed package-ids]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
       "<configuration>\n"
       "  <packageSources>\n"
       "    <clear />\n"
       "    <add key=\"dripsharp-local\" value=\"" (xml-escape feed) "\" />\n"
       "  </packageSources>\n"
       "  <packageSourceMapping>\n"
       "    <packageSource key=\"dripsharp-local\">\n"
       (apply str (for [package-id (sort package-ids)]
                    (str "      <package pattern=\"" (xml-escape package-id) "\" />\n")))
       "    </packageSource>\n"
       "  </packageSourceMapping>\n"
       "</configuration>\n"))

(defn- restored-package-assets [^Path assets-file]
  (when-not (paths/regular-file? assets-file)
    (fail! "Verified project restore assets are missing"
           {:assets-file (str assets-file)}))
  (let [assets (Files/readString assets-file)
        libraries-start (.indexOf assets "\"libraries\": {")
        libraries-end (.indexOf assets "\"projectFileDependencyGroups\": {")
        _ (when-not (and (<= 0 libraries-start)
                         (< libraries-start libraries-end))
            (fail! "Restore assets do not contain a bounded package library section"
                   {:assets-file (str assets-file)}))
        libraries (subs assets libraries-start libraries-end)
        package-count (count (re-seq #"\"type\"\s*:\s*\"package\"" libraries))
        packages
        (->> (re-seq
              #"(?m)^    \"([^\"/]+)/([^\"/]+)\": \{\r?\n      \"sha512\": \"[^\"]+\",\r?\n      \"type\": \"package\",\r?\n      \"path\": \"([^\"]+)\""
              libraries)
             (map (fn [[_ id version path]]
                    {:id id :version version :cache-path path}))
             (sort-by (juxt #(str/lower-case (:id %)) :version))
             vec)]
    (when-not (= package-count (count packages))
      (fail! "Could not recover the exact external package closure from restore assets"
             {:assets-file (str assets-file)
              :expected package-count :actual (count packages)}))
    packages))

(defn- global-packages-root! [run-command! root]
  (let [result (run-command! {:command ["dotnet" "nuget" "locals"
                                        "global-packages" "--list"]
                              :directory root})
        path (some-> (re-find #"(?m)^global-packages:\s*(.+?)\s*$"
                              (:output result))
                     second str/trim paths/path)]
    (when-not (and path (paths/directory? path))
      (fail! "Could not locate the restored global NuGet package cache"
             {:output (:output result)}))
    path))

(defn- publish-external-packages!
  [run-command! root feed specs]
  (let [packages
        (->> specs
             (filter #(seq (get-in % [:destination :runtime-packages])))
             (mapcat
              (fn [{:keys [emission]}]
                (restored-package-assets
                 (paths/resolve-path (:project-root emission)
                                     "obj" "project.assets.json"))))
             (reduce
              (fn [by-identity {:keys [id version] :as package}]
                (let [identity [(str/lower-case id) version]]
                  (if-let [existing (get by-identity identity)]
                    (if (= existing package)
                      by-identity
                      (fail! "Restore assets disagree about an external package identity"
                             {:identity identity :first existing :second package}))
                    (assoc by-identity identity package))))
              (sorted-map))
             vals
             vec)]
    (if-not (seq packages)
      []
      (let [global-packages (global-packages-root! run-command! root)]
        (mapv
         (fn [{:keys [id version cache-path]}]
           (let [lower-id (str/lower-case id)
                 filename (str lower-id "." version ".nupkg")
                 source (paths/resolve-path global-packages cache-path filename)
                 artifact (paths/resolve-path feed filename)]
             (when-not (paths/regular-file? source)
               (fail! "Restored external package archive is missing from the global cache"
                      {:identity [id version] :artifact (str source)}))
             (Files/copy source artifact
                         (into-array
                          StandardCopyOption
                          [StandardCopyOption/REPLACE_EXISTING]))
             {:id id :version version :sha256 (sha256 artifact)
              :file filename :external? true}))
         packages)))))

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

(defn- resource-notice-attribution!
  [profile emission destination]
  (let [attribution
        (or (:resource-notice-attribution destination)
            {:legal-sets [] :package-paths []})
        expected-keys #{:legal-sets :package-paths}
        actual-keys (set (keys attribution))
        legal-sets (:legal-sets attribution)
        package-paths (:package-paths attribution)
        resources (vec (:resource-artifacts emission))
        notice-paths
        (set
         (keep #(when (= :notice (:kind %)) (:package-path %))
               (:legal-files destination)))
        missing-paths
        (vec (sort (set/difference (set package-paths) notice-paths)))]
    (when-not (= expected-keys actual-keys)
      (fail! "Resource NOTICE attribution has an invalid shape"
             {:profile profile
              :expected (vec (sort expected-keys))
              :actual (vec (sort actual-keys))}))
    (when-not (and (vector? legal-sets)
                   (= (count legal-sets) (count (distinct legal-sets)))
                   (every? keyword? legal-sets)
                   (vector? package-paths)
                   (= (count package-paths) (count (distinct package-paths)))
                   (every? string? package-paths)
                   (= (boolean (seq legal-sets))
                      (boolean (seq package-paths))))
      (fail! "Resource NOTICE attribution is malformed"
             {:profile profile :attribution attribution}))
    (when (and (seq legal-sets) (empty? resources))
      (fail! "Resource NOTICE attribution selects legal sets but the profile emitted no resources"
             {:profile profile :attribution attribution}))
    (when (seq missing-paths)
      (fail! "Attributed production resources lack their declared NOTICE package files"
             {:profile profile
              :resources (mapv :logical-name resources)
              :legal-sets legal-sets
              :expected package-paths
              :missing missing-paths}))
    {:legal-sets legal-sets
     :package-paths package-paths
     :resources (mapv :logical-name resources)}))

(defn- package-specs [generation]
  (let [primary-profile (get-in generation [:generation-profile :profile])
        dependency-emissions (:dependency-emissions generation)
        main-emission
        (assoc (:emission generation)
               :profile primary-profile
               :destination (:destination generation)
               :dependency-profiles
               (or (:dependency-profiles generation)
                   (get-in generation
                           [:generation-profile :dependency-profiles])
                   (mapv :profile dependency-emissions))
               :transitive-dependency-profiles
               (or (:transitive-dependency-profiles generation)
                   (mapv :profile dependency-emissions)))
        emissions (conj (vec dependency-emissions) main-emission)
        by-profile
        (reduce
         (fn [result {:keys [profile destination] :as emission}]
           (when-not (and (string? profile) destination)
             (fail! "Project emission is missing its profile or destination configuration"
                    {:profile profile :emission (keys emission)}))
           (when (contains? result profile)
             (fail! "Package plan contains a duplicate project emission"
                    {:profile profile}))
           (assoc result profile emission))
         {}
         emissions)]
    (mapv
     (fn [{:keys [profile destination dependency-profiles] :as emission}]
       (let [dependency-profiles (or dependency-profiles [])
             _ (when-not (:mechanical-source-header-proof emission)
                 (fail! "Package plan is missing mechanical source header evidence"
                        {:profile profile}))
             _ (when-not (= authorship/schema-version
                            (get-in emission [:authorship :schema-version]))
                 (fail! "Package plan is missing the schema-versioned authorship ledger"
                        {:profile profile
                         :expected authorship/schema-version
                         :actual (get-in emission
                                         [:authorship :schema-version])}))
             mechanical-source-headers
             (java-project/verify-mechanical-source-headers! emission)
             resource-notice-attribution
             (resource-notice-attribution!
              profile emission destination)
             dependency-emissions
             (mapv
              (fn [dependency-profile]
                (or (get by-profile dependency-profile)
                    (fail! "Package plan is missing a direct project dependency"
                           {:profile profile
                            :dependency-profile dependency-profile
                            :available (vec (sort (keys by-profile)))})))
              dependency-profiles)
             assembly-dependency-emissions
             (->> (or (:transitive-dependency-profiles emission)
                      dependency-profiles)
                  (map
                   (fn [dependency-profile]
                     (or (get by-profile dependency-profile)
                         (fail! "Package plan is missing a transitive assembly dependency"
                                {:profile profile
                                 :dependency-profile dependency-profile
                                 :available (vec (sort (keys by-profile)))}))))
                  (sort-by #(get-in % [:destination :project :assembly-name]))
                  vec)
             project-references (:project-references destination)
             dependency-emissions
             (if (= (count dependency-emissions)
                    (count project-references))
               (->> (map vector project-references dependency-emissions)
                    (sort-by first)
                    (mapv second))
               dependency-emissions)
             translated-dependencies
             (mapv #(get-in % [:destination :package])
                   dependency-emissions)
             runtime-dependencies
             (->> (:runtime-packages destination)
                  (sort-by :id)
                  (mapv #(select-keys % [:id :version])))
             expected-dependencies
             (into (mapv #(select-keys % [:id :version])
                         translated-dependencies)
                   runtime-dependencies)
             expected-package-ids (mapv :id translated-dependencies)]
         (when (and (contains? destination :package-dependencies)
                    (not= (set expected-package-ids)
                          (set (:package-dependencies destination))))
           (fail! "Package dependencies differ from the resolved destination graph"
                  {:profile profile
                   :expected expected-package-ids
                   :actual (:package-dependencies destination)}))
         {:profile profile
          :emission emission
          :destination destination
          :authorship (:authorship emission)
          :mechanical-source-headers mechanical-source-headers
          :resource-notice-attribution resource-notice-attribution
          :expected-dependencies expected-dependencies
          :expected-package-files
          (let [legal
                (mapv (fn [{:keys [kind package-path sha256]}]
                        {:kind kind :path package-path :sha256 sha256})
                      (:legal-files destination))
                readme (get-in destination [:package :readme])]
            (if readme
              (let [source
                    (paths/resolve-path
                     (:project-root emission)
                     (:package-readme-source destination))]
                (when-not (paths/regular-file? source)
                  (fail! "Package README source is missing from generated product staging"
                         {:profile profile
                          :package (get-in destination [:package :id])
                          :source (str source)}))
                (conj legal
                      {:kind :readme :path readme
                       :sha256 (sha256 source)}))
              legal))
          :expected-assembly-dependencies
          (mapv
           (fn [{dependency-destination :destination}]
             {:assembly-name
              (get-in dependency-destination [:project :assembly-name])
              :package-id (get-in dependency-destination [:package :id])
              :version (get-in dependency-destination [:package :version])
              :target-framework
              (get-in dependency-destination [:project :target-framework])})
           assembly-dependency-emissions)
          :primary? (= profile primary-profile)}))
     emissions)))

(defn- package-reproducibility-plan [specs]
  (mapv (fn [{:keys [profile destination authorship
                     mechanical-source-headers
                     resource-notice-attribution
                     expected-dependencies
                     expected-package-files expected-assembly-dependencies
                     primary?]}]
          {:profile profile
           :destination destination
           :authorship authorship
           :mechanical-source-headers mechanical-source-headers
           :resource-notice-attribution resource-notice-attribution
           :expected-dependencies
           (mapv #(select-keys % [:id :version]) expected-dependencies)
           :expected-package-files expected-package-files
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

(defn- resolved-package-repository!
  [run-command! root specs repository-proof-fn]
  (let [packages (mapv #(get-in % [:destination :package]) specs)
        commits (set (keep :repository-commit packages))
        policies (set (keep :repository-commit-policy packages))
        repository-urls (set (map :repository-url packages))]
    (when (or (< 1 (count commits))
              (< 1 (count policies))
              (and (seq commits) (seq policies)))
      (fail! "NuGet package family has inconsistent RepositoryCommit metadata"
             {:commits commits :policies policies}))
    (if (= #{:exact-clean-generated-product-commit} policies)
      (do
        (when-not (ifn? repository-proof-fn)
          (fail! "Generated-product packaging requires a synchronized repository proof"
                 {:policy :exact-clean-generated-product-commit
                  :repositories repository-urls}))
        (let [{:keys [repository-url repository-commit] :as proof}
              (repository-proof-fn)]
          (when-not (= #{repository-url} repository-urls)
            (fail! "Generated-product repository proof identifies the wrong repository"
                   {:expected repository-urls :actual repository-url}))
          (when-not (boolean
                     (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}"
                                 (or repository-commit "")))
            (fail! "Generated-product repository proof has no exact Git commit"
                   {:expected "<40-or-64-character-lowercase-hex>"
                    :actual repository-commit}))
          {:commit repository-commit :proof proof}))
      {:commit (or (first commits) (repository-commit! run-command! root))
       :proof nil})))

(defn- verified-authorship!
  [emission]
  (let [project-root (:project-root emission)
        project-file (:project-file emission)
        configuration (:configuration emission)
        manifest-file
        (or (:manifest-file emission)
            (paths/resolve-path
             project-root
             (get-in configuration [:output :manifest-file])))
        _ (when-not (and manifest-file
                         (paths/regular-file? manifest-file))
            (fail! "Package source inspection cannot read the generation manifest"
                   {:manifest (some-> manifest-file str)}))
        manifest
        (read-single-edn! manifest-file)
        ledger (:authorship manifest)
        expected (:authorship emission)
        _ (when-not (= expected ledger)
            (fail! "Generation manifest authorship ledger differs from the emitted package plan"
                   {:expected expected :actual ledger
                    :manifest (str manifest-file)}))
        _ (when (and (pos? (get-in ledger [:totals :authored-lines] 0))
                     (not (seq (get-in ledger [:policy :evidence]))))
            (fail! "Package authorship ledger has no reviewed green behavior evidence"
                   {:package (get-in configuration [:package :id])
                    :profile (:profile emission)}))
        workspace-root
        (or (:workspace-root emission)
            (paths/workspace-root project-root))
        source-root
        (or (:source-root emission)
            (paths/resolve-path
             project-root
             (get-in configuration [:output :source-directory])))
        proof
        (authorship/verify-ledger!
         {:workspace-root workspace-root
          :project-root project-root
          :source-root source-root
          :mechanical-source (:mechanical-source configuration)
          :mechanical-header java-project/mechanical-source-header
          :configuration configuration
          :contract (:authorship configuration)
          :ledger ledger})
        _ (when-not (paths/regular-file? project-file)
            (fail! "Package source inspection cannot read the generated project"
                   {:project (some-> project-file str)}))
        project-document
        (parse-xml!
         (Files/readString project-file StandardCharsets/UTF_8)
         :generated-project)
        project-root-element (.getDocumentElement project-document)
        compile-items
        (->> (child-elements project-root-element "ItemGroup")
             (mapcat #(child-elements % "Compile"))
             vec)
        actual-compile-inputs
        (mapv element-attributes compile-items)
        expected-compile-inputs
        [{"Include"
          (str (get-in configuration [:output :source-directory])
               "/**/*.cs")}]
        _ (when-not (= expected-compile-inputs actual-compile-inputs)
            (fail! "Generated project assembly inputs do not match the authorship source inventory"
                   {:project (str project-file)
                    :expected expected-compile-inputs
                    :actual actual-compile-inputs}))
        _ (doseq [^Element compile-item compile-items]
            (when (seq (child-elements compile-item))
              (fail! "Generated project compile input contains unsupported metadata"
                     {:project (str project-file)
                      :input (element-attributes compile-item)})))
        proof
        (assoc proof
               :assembly-input
               {:include (get (first actual-compile-inputs) "Include")
                :source-inventory-sha256
                (:source-inventory-sha256 proof)})]
    {:ledger ledger :proof proof :manifest manifest-file}))

(defn- pack-project! [run-command! build-configuration repository-commit
                      ^Path output
                      {:keys [emission]}]
  (let [project-root (:project-root emission)
        project-file (:project-file emission)
        _ (verified-authorship! emission)]
    (run-command! {:command ["dotnet" "pack" (str project-file)
                             "--nologo" "--verbosity:minimal"
                             "--configuration" build-configuration
                             "--no-build" "--no-restore"
                             (str "-p:RepositoryCommit=" repository-commit)
                             "--output" (str output)]
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
        inspector (paths/resolve-path root "validation"
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
  ([{:keys [workspace-root profile verify-fn run-command! inspect-resources-fn
            repository-proof-fn]
     :or {verify-fn compiler/verify-clean-build!
          run-command! process/run!
          inspect-resources-fn inspect-package-assembly!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         profile
         (or profile
             (fail! "Packaging requires an explicit profile selection"
                    {:kind :missing-generation-profile-selection}))
         first-verification
         (verify-fn {:workspace-root root :profile profile
                     :build-configuration "Release"})
         first-build-configuration (:build-configuration first-verification)
         first-specs (package-specs (:generation first-verification))
         first-repository
         (resolved-package-repository!
          run-command! root first-specs repository-proof-fn)
         repository-commit (:commit first-repository)
         first-output (Files/createTempDirectory
                       "dripsharp-first-clean-pack-"
                       (make-array java.nio.file.attribute.FileAttribute 0))]
     (try
       (doseq [spec first-specs]
         (pack-project! run-command! first-build-configuration
                        repository-commit first-output spec))
       (let [verification
             (verify-fn {:workspace-root root :profile profile
                         :build-configuration "Release"})
             generation (:generation verification)
             build-configuration (:build-configuration verification)
             specs (package-specs generation)
             second-repository
             (resolved-package-repository!
              run-command! root specs repository-proof-fn)
             first-plan (package-reproducibility-plan first-specs)
             second-plan (package-reproducibility-plan specs)]
         (when-not (and (= first-build-configuration build-configuration)
                        (= first-plan second-plan)
                        (= first-repository second-repository))
           (fail! "Independent clean builds produced different NuGet package plans"
                  {:profile profile
                   :first-build-configuration first-build-configuration
                   :second-build-configuration build-configuration
                   :first first-plan :second second-plan
                   :first-repository first-repository
                   :second-repository second-repository}))
         (let [package-assembly-names
               (mapv #(get-in % [:destination :project :assembly-name]) specs)
               proof-root (harness/clean-directory!
                           (paths/resolve-path root "target" "package-proof"))
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
             (pack-project! run-command! build-configuration
                            repository-commit second-output spec))
           (let [packages
                 (mapv
                  (fn [{:keys [profile emission destination authorship
                               mechanical-source-headers
                               resource-notice-attribution
                               expected-dependencies
                               expected-package-files
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
                          verified-pdb
                          (paths/resolve-path (:project-root emission) "bin"
                                              build-configuration target-framework
                                              (str assembly-name ".pdb"))
                          symbols? (= :snupkg (:symbols package))
                          raw-first (package-artifact! first-output id version)
                          raw-second (package-artifact! second-output id version)
                          raw-first-symbol
                          (when symbols?
                            (symbol-artifact! first-output id version))
                          raw-second-symbol
                          (when symbols?
                            (symbol-artifact! second-output id version))
                          filename (str (.getFileName ^Path raw-first))
                          symbol-filename
                          (some-> raw-first-symbol .getFileName str)
                          first-artifact
                          (canonicalize-package!
                           raw-first (paths/resolve-path first-canonical filename))
                          second-artifact
                          (canonicalize-package!
                           raw-second (paths/resolve-path second-canonical filename))
                          first-symbol
                          (when symbols?
                            (canonicalize-package!
                             raw-first-symbol
                             (paths/resolve-path first-canonical symbol-filename)))
                          second-symbol
                          (when symbols?
                            (canonicalize-package!
                             raw-second-symbol
                             (paths/resolve-path second-canonical symbol-filename)))
                          first-hash (sha256 first-artifact)
                          second-hash (sha256 second-artifact)
                          first-symbol-hash (some-> first-symbol sha256)
                          second-symbol-hash (some-> second-symbol sha256)]
                      (when-not (= authorship (:authorship emission))
                        (fail! "Package authorship ledger drifted after the reproducibility plan"
                               {:profile profile
                                :expected authorship
                                :actual (:authorship emission)}))
                      (when-not (= first-hash second-hash)
                        (fail! "Independent clean builds did not produce byte-identical NuGet packages"
                               {:profile profile :first first-hash :second second-hash}))
                      (when-not (= first-symbol-hash second-symbol-hash)
                        (fail! "Independent clean builds did not produce byte-identical NuGet symbol packages"
                               {:profile profile :first first-symbol-hash
                                :second second-symbol-hash}))
                      (let [artifact
                            (paths/resolve-path
                             feed (str (.getFileName ^Path first-artifact)))
                            _ (Files/copy
                               first-artifact artifact
                               (into-array
                                StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
                            symbol-artifact
                            (when symbols?
                              (let [destination
                                    (paths/resolve-path feed symbol-filename)]
                                (Files/copy
                                 first-symbol destination
                                 (into-array
                                  StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))
                                destination))
                            source-inspection
                            (verified-authorship! emission)
                            inspection
                            (assoc
                             (inspect-package!
                              artifact
                              (assoc package
                                     :repository-commit repository-commit)
                              target-framework assembly-name
                              expected-dependencies expected-package-files)
                             :authorship (:proof source-inspection))
                            expected-resources
                            (->> (:resource-artifacts emission)
                                 (map :logical-name) sort vec)
                            resource-proof
                            (inspect-resources-fn
                             run-command! root artifact
                             (:assembly-entry inspection) assembly-name
                             verified-assembly package-assembly-names
                             (or expected-assembly-dependencies [])
                             expected-resources)
                            symbol-inspection
                            (when symbols?
                              (inspect-symbol-package!
                               symbol-artifact
                               (assoc package
                                      :repository-commit repository-commit)
                               target-framework assembly-name
                               expected-dependencies verified-pdb))]
                        {:profile profile :primary? primary? :artifact artifact
                         :destination destination
                         :identity {:id id :version version :sha256 first-hash
                                    :file (str (.getFileName ^Path artifact))}
                         :symbol-artifact symbol-artifact
                         :symbol
                         (when symbols?
                           {:id id :version version :sha256 first-symbol-hash
                            :file symbol-filename
                            :pdb-sha256 (:pdb-sha256 symbol-inspection)})
                         :mechanical-source-headers mechanical-source-headers
                         :resource-notice-attribution
                         resource-notice-attribution
                         :authorship authorship
                         :inspection inspection :resource-proof resource-proof
                         :symbol-inspection symbol-inspection
                         :public-surface (:public-surface resource-proof)
                         :resources expected-resources})))
                  specs)
                 external-packages
                 (publish-external-packages! run-command! root feed specs)
                 primary (first (filter :primary? packages))
                 boundary-report
                 (authorship-report/write-report!
                  {:workspace-root root
                   :output-root
                   (paths/resolve-path proof-root "release-evidence")
                   :repository-commit repository-commit
                   :packages
                   (mapv
                    (fn [{:keys [profile identity authorship inspection]}]
                      {:profile profile
                       :identity identity
                       :ledger authorship
                       :verification (:authorship inspection)})
                    packages)})
                 boundary-summary
                 {:schema-version (:schema-version boundary-report)
                  :files
                  {:edn (util/portable-path proof-root
                                            (:edn boundary-report))
                   :markdown
                   (util/portable-path proof-root
                                       (:markdown boundary-report))}
                  :sha256
                  {:edn (:edn-sha256 boundary-report)
                   :markdown (:markdown-sha256 boundary-report)}}
                 summary
                 {:profile profile
                  :clean-builds 2
                  :repository-commit repository-commit
                  :packages (mapv :identity packages)
                  :symbols (mapv :symbol (filter :symbol packages))
                  :external-packages external-packages
                  :mechanical-source-headers
                  (into (sorted-map)
                        (map (juxt #(get-in % [:identity :id])
                                   :mechanical-source-headers) packages))
                  :authorship
                  (into
                   (sorted-map)
                   (map
                    (juxt #(get-in % [:identity :id])
                          #(get-in % [:authorship :totals]))
                    packages))
                  :mechanical-authored-boundary boundary-summary
                  :resource-counts
                  (into (sorted-map)
                        (map (juxt #(get-in % [:identity :id])
                                   #(count (:resources %))) packages))
                  :resource-notice-attribution
                  (into (sorted-map)
                        (map (juxt #(get-in % [:identity :id])
                                   :resource-notice-attribution) packages))
                  :public-surfaces
                  (into (sorted-map)
                        (map (juxt #(get-in % [:identity :id])
                                   :public-surface) packages))}]
             (println "Reproducible dependency-closed NuGet packing passed:"
                      (pr-str summary))
             {:verification verification :proof-root proof-root :feed feed
              :packages packages :artifact (:artifact primary)
              :identity (:identity primary) :inspection (:inspection primary)
              :external-packages external-packages
              :boundary-report boundary-report
              :summary summary})))
       (finally
         (delete-tree! first-output))))))

(defn- available-identities
  [package-proof]
  (into (mapv :identity (:packages package-proof))
        (:external-packages package-proof)))

(defn- exact-identities!
  [available expected]
  (let [by-identity
        (into {}
              (map (fn [{:keys [id version] :as identity}]
                     [[(str/lower-case id) version] identity]))
              available)
        expected
        (sort-by (juxt #(str/lower-case (:id %)) :version) expected)
        selected
        (mapv
         (fn [{:keys [id version]}]
           (or (get by-identity [(str/lower-case id) version])
               (fail! "Isolated consumer contract selects an artifact outside the fresh feed"
                      {:identity [id version]
                       :available
                       (mapv #(select-keys % [:id :version]) available)})))
         expected)]
    (when-not (= (count expected) (count (set (map (juxt :id :version)
                                                   expected))))
      (fail! "Isolated consumer contract contains duplicate package identities"
             {:expected expected}))
    selected))

(defn- consumer-fixture-source
  [root consumer-profile]
  (when (= :source-file (:strategy consumer-profile))
    (if-let [source-path (:source-path consumer-profile)]
      (paths/resolve-path root source-path)
      (paths/resolve-path root "validation" "package-consumer"
                          (:fixture-file consumer-profile)))))

(defn verify-packed-consumer!
  "Restores and executes one fresh package-reference-only consumer against an
  already packed local feed. `selected-packages` are direct PackageReferences;
  `expected-packages` is the exact dependency-closed restore result."
  [{:keys [workspace-root package-proof consumer-name consumer-profile
           selected-packages expected-packages target-framework run-command!]
    :or {run-command! process/run!}}]
  (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
        _ (when-not (and (string? consumer-name)
                         (re-matches #"[A-Za-z0-9_.-]+" consumer-name))
            (fail! "Independent consumer name is invalid"
                   {:consumer-name consumer-name}))
        available (available-identities package-proof)
        selected (exact-identities! available selected-packages)
        identities (exact-identities! available expected-packages)
        expected-set (set (map (juxt :id :version) identities))
        selected-set (set (map (juxt :id :version) selected))
        _ (when-not (set/subset? selected-set expected-set)
            (fail! "Independent consumer direct references are outside its exact closure"
                   {:selected selected-set :expected expected-set}))
        target-framework
        (or target-framework
            (some-> (:packages package-proof) first :destination
                    (get-in [:project :target-framework]))
            (fail! "Independent consumer target framework is missing"
                   {:consumer-name consumer-name}))
        consumer-target-framework
        (installed-runtime-target! run-command! root target-framework)
        proof-root (:proof-root package-proof)
        feed (:feed package-proof)
        consumer
        (harness/clean-directory!
         (paths/resolve-path proof-root "consumers" consumer-name))
        cache (doto (paths/resolve-path consumer "cache")
                (Files/createDirectories
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        packages (doto (paths/resolve-path cache "packages")
                   (Files/createDirectories
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        http-cache (paths/resolve-path cache "http")
        plugins-cache (paths/resolve-path cache "plugins")
        scratch (paths/resolve-path cache "scratch")
        cli-home (paths/resolve-path cache "dotnet-home")
        _ (doseq [directory [http-cache plugins-cache scratch cli-home]]
            (Files/createDirectories
             directory (make-array java.nio.file.attribute.FileAttribute 0)))
        environment
        {"NUGET_PACKAGES" (str packages)
         "NUGET_HTTP_CACHE_PATH" (str http-cache)
         "NUGET_PLUGINS_CACHE_PATH" (str plugins-cache)
         "NUGET_SCRATCH" (str scratch)
         "DOTNET_CLI_HOME" (str cli-home)
         "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" "1"
         "DOTNET_CLI_TELEMETRY_OPTOUT" "1"
         "DOTNET_NOLOGO" "1"}
        consumer-project-file
        (paths/resolve-path consumer (:project-file consumer-profile))
        nuget-config-file (paths/resolve-path consumer "NuGet.Config")
        consumer-source (paths/resolve-path consumer "Program.cs")
        fixture-source (consumer-fixture-source root consumer-profile)]
    (when-not (contains? #{:source-file :compile-only}
                         (:strategy consumer-profile))
      (fail! "Independent consumer strategy is unsupported"
             {:consumer-name consumer-name
              :strategy (:strategy consumer-profile)}))
    (when (and fixture-source (not (paths/regular-file? fixture-source)))
      (fail! "Independent package-consumer source is missing"
             {:path (str fixture-source)}))
    (write-text! consumer-project-file
                 (consumer-project selected consumer-target-framework))
    (write-text! nuget-config-file
                 (nuget-config feed (map :id identities)))
    (if fixture-source
      (Files/copy fixture-source consumer-source
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING]))
      (write-text!
       consumer-source
       (compile-only-consumer (:success-message consumer-profile)
                              (:compile-types consumer-profile))))
    (run-command!
     {:command ["dotnet" "restore" (str consumer-project-file)
                "--configfile" (str nuget-config-file)
                "--packages" (str packages)
                "--no-cache" "--force" "--force-evaluate"
                "-p:RestoreNoCache=true"
                "-p:RestoreIgnoreFailedSources=false"
                "-p:RestoreFallbackFolders="]
      :directory consumer
      :environment environment})
    (let [dependency-proof
          (inspect-consumer-dependencies!
           consumer-project-file
           (paths/resolve-path consumer "obj" "project.assets.json")
           packages selected identities)]
      (run-command!
       {:command ["dotnet" "build" (str consumer-project-file)
                  "--nologo" "--verbosity:minimal" "--no-restore"
                  "--no-incremental" "-warnaserror"]
        :directory consumer
        :environment environment})
      (let [run-result
            (run-command!
             {:command ["dotnet" "run"
                        "--project" (str consumer-project-file)
                        "--no-build" "--no-restore"]
              :directory consumer
              :environment environment})]
        (when-not (str/includes? (:output run-result)
                                 (:success-message consumer-profile))
          (fail! "Independent package consumer did not report successful behavior checks"
                 {:consumer-name consumer-name :output (:output run-result)}))
        {:consumer-name consumer-name
         :selected-packages
         (mapv #(select-keys % [:id :version :sha256]) selected)
         :dependency-proof dependency-proof
         :consumer-root consumer
         :packages-root packages
         :environment-roots
         {:packages packages :http-cache http-cache :plugins-cache plugins-cache
          :scratch scratch :dotnet-home cli-home}
         :run run-result}))))

(defn verify-package-consumption!
  "Runs clean generation/compilation, packs it, and proves isolated consumption."
  ([] (verify-package-consumption! {}))
  ([{:keys [workspace-root profile verify-fn run-command! pack-fn]
     :or {verify-fn compiler/verify-clean-build!
          pack-fn pack-verified-profile!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         profile
         (or profile
             (fail! "Package consumption requires an explicit profile selection"
                    {:kind :missing-generation-profile-selection}))
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
         identities (available-identities package-proof)
         consumer-proof
         (verify-packed-consumer!
          {:workspace-root root
           :package-proof package-proof
           :consumer-name "primary"
           :consumer-profile consumer-profile
           :selected-packages [{:id id :version version}]
           :expected-packages
           (mapv #(select-keys % [:id :version]) identities)
           :target-framework target-framework
           :run-command! run-command!})
         artifact (:artifact package-proof)
         identity {:id id :version version :sha256 (sha256 artifact)
                   :file (str (.getFileName ^Path artifact))}]
     (println "Independent NuGet consumption passed:" (pr-str identity))
     {:verification verification
      :artifact artifact
      :identity identity
      :inspection (:inspection package-proof)
      :packages (:packages package-proof)
      :external-packages (:external-packages package-proof)
      :feed (:feed package-proof)
      :packing-summary (:summary package-proof)
      :boundary-report (:boundary-report package-proof)
      :dependency-proof (:dependency-proof consumer-proof)
      :proof-root (:proof-root package-proof)
      :packages-root (:packages-root consumer-proof)
      :consumer-root (:consumer-root consumer-proof)
      :run (:run consumer-proof)})))
