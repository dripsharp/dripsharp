(ns vibeformer.packaging
  "Local NuGet packaging and isolated independent-consumer verification."
  (:require [clojure.string :as str]
            [vibeformer.compiler :as compiler]
            [vibeformer.harness :as harness]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
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

(defn- parse-xml! [xml]
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
      (fail! "NuGet package contains invalid nuspec XML"
             {:cause (.getMessage error)}))))

(defn- element-name [^Element element]
  (or (.getLocalName element) (.getNodeName element)))

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

(defn- element-attributes [^Element element names]
  (into {} (map (fn [name] [name (.getAttribute element name)]) names)))

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
    (let [entries
          (->> (enumeration-seq (.entries archive))
               (remove #(.isDirectory ^java.util.zip.ZipEntry %))
               (map (fn [^java.util.zip.ZipEntry entry]
                      (let [name (.getName entry)
                            bytes (with-open [input (.getInputStream archive entry)]
                                    (.readAllBytes input))]
                        [(if (str/starts-with? name core-properties-prefix)
                           canonical-core-properties
                           name)
                         (if (= "_rels/.rels" name)
                           (canonical-relationships bytes)
                           bytes)])))
               (into (sorted-map)))]
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
  (let [document (parse-xml! nuspec)
        root (.getDocumentElement document)]
    (when-not (= "package" (element-name root))
      (fail! "NuGet nuspec root element is not package"
             {:expected "package" :actual (element-name root)}))
    (let [metadata (exactly-one-child! root "metadata" "package")
          configured-elements
          [["id" (:id package)]
           ["version" (:version package)]
           ["title" (:title package)]
           ["description" (:description package)]
           ["authors" (:authors package)]
           ["tags" (:tags package)]
           ["projectUrl" (:project-url package)]]]
      (doseq [[name expected] configured-elements]
        (let [element (exactly-one-child! metadata name "package/metadata")
              actual (.getTextContent element)]
          (when-not (= expected actual)
            (fail! (str "NuGet metadata does not match configured " name)
                   {:element name :expected expected :actual actual}))))
      (let [repository (exactly-one-child! metadata "repository" "package/metadata")
            expected-repository {"type" (:repository-type package)
                                 "url" (:repository-url package)}
            actual-repository (element-attributes repository ["type" "url"])]
        (when-not (= expected-repository actual-repository)
          (fail! "NuGet repository metadata does not match the configured repository"
                 {:expected expected-repository :actual actual-repository})))
      (let [dependency-container
            (exactly-one-child! metadata "dependencies" "package/metadata")
            groups (child-elements dependency-container "group")]
        (when-not (= 1 (count groups))
          (fail! "NuGet dependencies do not contain exactly one target-framework group"
                 {:expected target-framework :groups (count groups)}))
        (let [group (first groups)
              actual-framework (.getAttribute ^Element group "targetFramework")
              dependencies (mapv (fn [^Element dependency]
                                   {:id (.getAttribute dependency "id")
                                    :version (.getAttribute dependency "version")})
                                 (child-elements group "dependency"))
              expected-dependencies
              (mapv #(select-keys % [:id :version]) expected-dependencies)]
          (when-not (= target-framework actual-framework)
            (fail! "NuGet dependency group does not match the configured target framework"
                   {:expected target-framework :actual actual-framework}))
          (when-not (= expected-dependencies dependencies)
            (fail! "NuGet package dependencies do not match the generated project dependency closure"
                   {:expected expected-dependencies :actual dependencies}))
          dependencies)))))

(defn inspect-package!
  "Checks the package payload and metadata without extracting generated sources."
  ([artifact package target-framework assembly-name]
   (inspect-package! artifact package target-framework assembly-name []))
  ([artifact {:keys [id version title description authors tags project-url
                     repository-url repository-type]}
    target-framework assembly-name
    expected-dependencies]
   (with-open [archive (ZipFile. (str artifact))]
     (let [entries (->> (enumeration-seq (.entries archive))
                        (map #(.getName %))
                        sort
                        vec)
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
                            :repository-type repository-type}
                           target-framework expected-dependencies)]
         (when (seq forbidden)
           (fail! "NuGet package contains translator, test, or generated-source internals"
                  {:forbidden forbidden :entries entries}))
         {:entries entries :assembly-entry assembly-entry :nuspec nuspec
          :dependencies dependencies})))))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- sha256 [^Path file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (Files/newInputStream file (make-array java.nio.file.OpenOption 0))]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [read (.read input buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (hex (.digest digest))))

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

(def ^:private consumer-profiles
  {"pkl-parser"
   {:project-file "Pkl.Parser.PackageConsumer.csproj"
    :fixture-file "Program.cs"
    :success-message "Independent Pkl.Parser package consumer passed."}
   "pkl-core-value-model"
   {:project-file "Pkl.Core.PackageConsumer.csproj"
    :fixture-file "Pkl.Core.Program.cs"
    :success-message "Independent Pkl.Core package consumer passed."}})

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
        expected-dependencies (mapv #(get-in % [:destination :package]) dependency-specs)]
    (conj dependency-specs
          {:profile (get-in generation [:generation-profile :profile])
           :emission (:emission generation)
           :destination (:destination generation)
           :expected-dependencies expected-dependencies
           :primary? true})))

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
  [run-command! root artifact assembly-entry expected]
  (let [inspector (paths/resolve-path root "vibeformer" "validation"
                                      "package-inspector" "PackageInspector.csproj")
        result (run-command!
                {:command (into ["dotnet" "run" "--project" (str inspector)
                                 "--configuration" "Release" "--verbosity" "quiet"
                                 "--" (str artifact) assembly-entry]
                                expected)
                 :directory root})]
    (when-not (str/includes? (:output result)
                             (str "Embedded resource inspection passed: " (count expected)))
      (fail! "Package resource inspector did not report the expected manifest"
             {:artifact (str artifact) :expected expected :output (:output result)}))
    (let [[_ types members fingerprint]
          (re-find #"Public surface inspection passed: (\d+) types, (\d+) members, SHA-256 ([0-9a-f]{64})"
                   (:output result))]
      (when-not fingerprint
        (fail! "Package assembly inspector did not report a public-surface fingerprint"
               {:artifact (str artifact) :output (:output result)}))
      {:resources (count expected)
       :public-surface {:types (parse-long types)
                        :members (parse-long members)
                        :sha256 fingerprint}
       :run result})))

(defn pack-verified-profile!
  "Cleanly generates and verifies a profile, then packs its complete declared
  dependency closure twice and publishes byte-identical inspected artifacts to
  a fresh local feed. This does not perform independent package consumption."
  ([] (pack-verified-profile! {}))
  ([{:keys [workspace-root profile verify-fn run-command! inspect-resources-fn]
     :or {verify-fn compiler/verify-clean-build!
          run-command! process/run!
          inspect-resources-fn inspect-package-assembly!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         profile (or profile "pkl-parser")
         verification (verify-fn {:profile profile :build-configuration "Release"})
         generation (:generation verification)
         build-configuration (:build-configuration verification)
         specs (package-specs generation)
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "vibeformer" "target" "package-proof"))
         first-output (doto (paths/resolve-path proof-root "first-pack")
                        (Files/createDirectories
                         (make-array java.nio.file.attribute.FileAttribute 0)))
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
       (pack-project! run-command! build-configuration first-output spec)
       (pack-project! run-command! build-configuration second-output spec))
     (let [packages
           (mapv
            (fn [{:keys [profile emission destination expected-dependencies primary?]}]
              (let [{:keys [id version] :as package} (:package destination)
                    target-framework (get-in destination [:project :target-framework])
                    assembly-name (get-in destination [:project :assembly-name])
                    raw-first (package-artifact! first-output id version)
                    raw-second (package-artifact! second-output id version)
                    filename (str (.getFileName ^Path raw-first))
                    first-artifact (canonicalize-package!
                                    raw-first (paths/resolve-path first-canonical filename))
                    second-artifact (canonicalize-package!
                                     raw-second (paths/resolve-path second-canonical filename))
                    first-hash (sha256 first-artifact)
                    second-hash (sha256 second-artifact)]
                (when-not (= first-hash second-hash)
                  (fail! "Repeated packing of the same verified build was not byte deterministic"
                         {:profile profile :first first-hash :second second-hash}))
                (let [artifact (paths/resolve-path feed (str (.getFileName ^Path first-artifact)))
                      _ (Files/copy first-artifact artifact
                                    (into-array StandardCopyOption
                                                [StandardCopyOption/REPLACE_EXISTING]))
                      inspection (inspect-package! artifact package target-framework
                                                   assembly-name expected-dependencies)
                      expected-resources (->> (:resource-artifacts emission)
                                              (map :logical-name) sort vec)
                      resource-proof (inspect-resources-fn
                                      run-command! root artifact
                                      (:assembly-entry inspection) expected-resources)]
                  {:profile profile :primary? primary? :artifact artifact
                   :identity {:id id :version version :sha256 first-hash
                              :file (str (.getFileName ^Path artifact))}
                   :inspection inspection :resource-proof resource-proof
                   :public-surface (:public-surface resource-proof)
                   :resources expected-resources})))
            specs)
           primary (first (filter :primary? packages))
           summary {:profile profile
                    :packages (mapv :identity packages)
                    :resource-counts (into (sorted-map)
                                           (map (juxt #(get-in % [:identity :id])
                                                      #(count (:resources %))) packages))
                    :public-surfaces (into (sorted-map)
                                           (map (juxt #(get-in % [:identity :id])
                                                      :public-surface) packages))}]
       (println "Deterministic dependency-closed NuGet packing passed:" (pr-str summary))
       {:verification verification :proof-root proof-root :feed feed :packages packages
        :artifact (:artifact primary) :identity (:identity primary)
        :inspection (:inspection primary) :summary summary}))))

(defn verify-package-consumption!
  "Runs clean generation/compilation, packs it, and proves isolated consumption."
  ([] (verify-package-consumption! {}))
  ([{:keys [workspace-root profile verify-fn run-command! pack-fn]
     :or {verify-fn compiler/verify-clean-build!
          pack-fn pack-verified-profile!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         profile (or profile "pkl-parser")
         consumer-profile (or (get consumer-profiles profile)
                              (fail! "Profile has no independent package-consumer fixture"
                                     {:profile profile :supported (sort (keys consumer-profiles))}))
         package-proof (pack-fn {:workspace-root root :profile profile
                                 :verify-fn verify-fn :run-command! run-command!})
         verification (:verification package-proof)
         generation (:generation verification)
         configuration (:destination generation)
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
         consumer-project-file (paths/resolve-path consumer (:project-file consumer-profile))
         nuget-config-file (paths/resolve-path consumer "NuGet.Config")
         consumer-source (paths/resolve-path consumer "Program.cs")
         fixture-source (paths/resolve-path root "vibeformer" "validation"
                                            "package-consumer" (:fixture-file consumer-profile))
         identities (mapv :identity (:packages package-proof))]
     (when-not (paths/regular-file? fixture-source)
       (fail! "Independent package-consumer source is missing"
              {:path (str fixture-source)}))
     (let [artifact (:artifact package-proof)
           inspection (:inspection package-proof)]
       (write-text! consumer-project-file
                    (consumer-project id version consumer-target-framework))
       (write-text! nuget-config-file (nuget-config feed (map :id identities)))
       (Files/copy fixture-source consumer-source
                   (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
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
          :dependency-proof dependency-proof
          :consumer-root consumer
          :run run-result}))))))
