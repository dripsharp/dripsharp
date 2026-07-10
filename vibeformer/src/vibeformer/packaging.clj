(ns vibeformer.packaging
  "Local NuGet packaging and isolated independent-consumer verification."
  (:require [clojure.string :as str]
            [vibeformer.compiler :as compiler]
            [vibeformer.harness :as harness]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.util.zip ZipFile]))

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

(defn- package-artifact! [^Path feed package-id version]
  (let [packages (filter #(and (str/ends-with? (str %) ".nupkg")
                               (not (str/ends-with? (str %) ".snupkg")))
                         (regular-files feed))]
    (when-not (= 1 (count packages))
      (fail! "Packing must produce exactly one NuGet package"
             {:feed (str feed) :artifacts (mapv str packages)}))
    (let [artifact (first packages)
          expected (str/lower-case (str package-id "." version ".nupkg"))]
      (when-not (= expected (str/lower-case (str (.getFileName artifact))))
        (fail! "NuGet artifact name does not match configured identity"
               {:expected expected :actual (str (.getFileName artifact))}))
      artifact)))

(defn- zip-text [^ZipFile archive entry-name]
  (when-let [entry (.getEntry archive entry-name)]
    (with-open [input (.getInputStream archive entry)]
      (String. (.readAllBytes input) StandardCharsets/UTF_8))))

(defn inspect-package!
  "Checks the package payload and metadata without extracting generated sources."
  [artifact {:keys [id version description authors tags]} target-framework assembly-name]
  (with-open [archive (ZipFile. (str artifact))]
    (let [entries (->> (enumeration-seq (.entries archive))
                       (map #(.getName %))
                       sort
                       vec)
          nuspec-name (str id ".nuspec")
          assembly-entry (str "lib/" target-framework "/" assembly-name ".dll")
          nuspec (zip-text archive nuspec-name)
          forbidden (filterv #(or (re-find #"(?i)(^|/)(src|test|translator)(/|$)" %)
                                  (re-find #"(?i)\.(cs|clj|edn|java|kt)$" %)
                                  (re-find #"(?i)(source-map|diagnostics|generation-manifest)" %))
                             entries)]
      (when-not (some #{assembly-entry} entries)
        (fail! "NuGet package does not contain the generated parser assembly"
               {:required assembly-entry :entries entries}))
      (when-not nuspec
        (fail! "NuGet package does not contain its nuspec metadata"
               {:required nuspec-name :entries entries}))
      (doseq [[element value] [["id" id]
                               ["version" version]
                               ["description" description]
                               ["authors" authors]
                               ["tags" tags]]]
        (when-not (str/includes? nuspec (str "<" element ">" (xml-escape value)
                                            "</" element ">"))
          (fail! (str "NuGet metadata is missing configured " element)
                 {:element element :value value :nuspec nuspec})))
      (when (seq forbidden)
        (fail! "NuGet package contains translator, test, or generated-source internals"
               {:forbidden forbidden :entries entries}))
      {:entries entries :assembly-entry assembly-entry :nuspec nuspec})))

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

(defn- nuget-config [^Path feed package-id]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
       "<configuration>\n"
       "  <packageSources>\n"
       "    <clear />\n"
       "    <add key=\"vibeformer-local\" value=\"" (xml-escape feed) "\" />\n"
       "    <add key=\"nuget.org\" value=\"https://api.nuget.org/v3/index.json\" />\n"
       "  </packageSources>\n"
       "  <packageSourceMapping>\n"
       "    <packageSource key=\"vibeformer-local\">\n"
       "      <package pattern=\"" (xml-escape package-id) "\" />\n"
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

(defn verify-package-consumption!
  "Runs clean generation/compilation, packs it, and proves isolated consumption."
  ([] (verify-package-consumption! {}))
  ([{:keys [workspace-root verify-fn run-command!]
     :or {verify-fn compiler/verify-clean-build!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         verification (verify-fn)
         generation (:generation verification)
         configuration (:destination generation)
         project-root (get-in generation [:emission :project-root])
         project-file (get-in generation [:emission :project-file])
         {:keys [id version] :as package} (:package configuration)
         target-framework (get-in configuration [:project :target-framework])
         consumer-target-framework
         (installed-runtime-target! run-command! root target-framework)
         assembly-name (get-in configuration [:project :assembly-name])
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "vibeformer" "target" "package-proof"))
         feed (doto (paths/resolve-path proof-root "feed")
                (Files/createDirectories
                 (make-array java.nio.file.attribute.FileAttribute 0)))
         consumer (doto (paths/resolve-path proof-root "consumer")
                    (Files/createDirectories
                     (make-array java.nio.file.attribute.FileAttribute 0)))
         packages (doto (paths/resolve-path proof-root "packages")
                    (Files/createDirectories
                     (make-array java.nio.file.attribute.FileAttribute 0)))
         consumer-project-file (paths/resolve-path consumer "Pkl.Parser.PackageConsumer.csproj")
         nuget-config-file (paths/resolve-path consumer "NuGet.Config")
         consumer-source (paths/resolve-path consumer "Program.cs")
         fixture-source (paths/resolve-path root "vibeformer" "validation"
                                            "package-consumer" "Program.cs")]
     (when-not (paths/regular-file? fixture-source)
       (fail! "Independent package-consumer source is missing"
              {:path (str fixture-source)}))
     (run-command! {:command ["dotnet" "pack" (str project-file)
                              "--nologo" "--verbosity:minimal"
                              "--configuration" "Debug" "--no-build" "--no-restore"
                              "--output" (str feed)]
                    :directory project-root})
     (let [artifact (package-artifact! feed id version)
           inspection (inspect-package! artifact package target-framework assembly-name)]
       (write-text! consumer-project-file
                    (consumer-project id version consumer-target-framework))
       (write-text! nuget-config-file (nuget-config feed id))
       (Files/copy fixture-source consumer-source
                   (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
       (run-command! {:command ["dotnet" "restore" (str consumer-project-file)
                                "--configfile" (str nuget-config-file)
                                "--packages" (str packages)
                                "--no-cache" "--force" "--force-evaluate"]
                      :directory consumer})
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
                                  "Independent Pkl.Parser package consumer passed.")
           (fail! "Independent package consumer did not report successful behavior checks"
                  {:output (:output run-result)}))
         (println "Independent NuGet consumption passed:" (pr-str identity))
         {:verification verification
          :artifact artifact
          :identity identity
          :inspection inspection
          :consumer-root consumer
          :run run-result})))))
