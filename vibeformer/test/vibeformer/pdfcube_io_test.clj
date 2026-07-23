(ns vibeformer.pdfcube-io-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.compiler :as compiler]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.nio.file CopyOption Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Comparator]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-pdfcube-io"
                             (make-array FileAttribute 0)))

(defn- write-string! [^Path file text]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file text (make-array OpenOption 0))
  file)

(defn- delete-tree! [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [entries (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path entry (iterator-seq
                           (.iterator (.sorted entries
                                               (Comparator/reverseOrder))))]
        (Files/deleteIfExists entry)))))

(defn- xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- nuget-global-packages []
  (let [output
        (:output
         (process/run! {:directory (paths/workspace-root)
                        :command ["dotnet" "nuget" "locals"
                                  "global-packages" "--list"]}))]
    (some-> (re-find #"(?m)^global-packages:\s*(.+?)\s*$" output)
            second str/trim paths/path)))

(defn- copy-package! [global-packages packages id version]
  (let [lower-id (str/lower-case id)
        source (paths/resolve-path global-packages lower-id version
                                   (str lower-id "." version ".nupkg"))
        destination (paths/resolve-path packages (.getFileName ^Path source))]
    (Files/copy source destination
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))

(defn- consumer-project [packages package-id package-version]
  (str
   "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
   "  <PropertyGroup>\n"
   "    <OutputType>Exe</OutputType>\n"
   "    <TargetFramework>net10.0</TargetFramework>\n"
   "    <Nullable>enable</Nullable>\n"
   "    <ImplicitUsings>enable</ImplicitUsings>\n"
   "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
   "    <RestoreSources>" (xml-escape packages) "</RestoreSources>\n"
   "    <RestoreIgnoreFailedSources>true</RestoreIgnoreFailedSources>\n"
   "  </PropertyGroup>\n"
   "  <ItemGroup>\n"
   "    <PackageReference Include=\"" (xml-escape package-id)
   "\" Version=\"" (xml-escape package-version) "\" />\n"
   "  </ItemGroup>\n"
   "</Project>\n"))

(deftest complete-pdfcube-io-generates-packs-and-passes-focused-behavior
  (let [verification (compiler/verify-clean-build! {:profile "pdfcube-io"})
        generation (:generation verification)
        summary (get-in generation [:emission :summary])
        coverage (:executable-coverage summary)
        project-file (get-in generation [:emission :project-file])
        destination (:destination generation)
        package-id (get-in destination [:package :id])
        package-version (get-in destination [:package :version])
        root (temp-directory)
        packages (paths/resolve-path root "packages")
        package-cache (paths/resolve-path root "package-cache")
        package-environment {"NUGET_PACKAGES" (str package-cache)}
        consumer (paths/resolve-path root "consumer")
        consumer-project-file (paths/resolve-path consumer "Consumer.csproj")]
    (try
      (testing "clean generation and warnings-as-errors compilation are complete"
        (is (= 18 (:compilation-units summary)))
        (is (= 22 (:generated-files summary)))
        (is (= 321 (:declarations summary)))
        (is (= 0 (:skipped-source-units summary)))
        (is (= 0 (:hard-failures summary)))
        (is (= 0 (:missing-source-mappings summary)))
        (is (= (:visited coverage) (:covered coverage)))
        (is (every? zero?
                    (map #(long (or (get coverage %) 0))
                         [:blocked :unsupported-elements :missing-mappings
                          :missing-occurrences :fallback])))
        (is (empty? (:diagnostics verification)))
        (is (= 177 (get-in verification
                           [:public-surface :assemblies 0 :contract-members]))))

      (Files/createDirectories packages (make-array FileAttribute 0))
      (process/run! {:directory (get-in generation [:emission :project-root])
                     :command ["dotnet" "pack" project-file
                               "--configuration" "Release" "--no-build"
                               "--output" packages "--nologo"
                               "-p:TreatWarningsAsErrors=true"]})
      (let [global-packages (nuget-global-packages)]
        (copy-package! global-packages packages
                       "Microsoft.Extensions.Logging.Abstractions" "10.0.0")
        (copy-package! global-packages packages
                       "Microsoft.Extensions.DependencyInjection.Abstractions" "10.0.0"))

      (testing "the packed package is independently consumable"
        (write-string! consumer-project-file
                       (consumer-project packages package-id package-version))
        (write-string! (paths/resolve-path consumer "Program.cs")
                       (slurp "validation/pdfcube-io/PdfCube.IO.FocusedConsumer.cs"))
        (process/run! {:directory consumer
                       :environment package-environment
                       :command ["dotnet" "restore" consumer-project-file
                                 "--source" packages
                                 "-p:RestoreIgnoreFailedSources=true"]})
        (let [result
              (process/run! {:directory consumer
                             :environment package-environment
                             :command ["dotnet" "run" "--project"
                                       consumer-project-file
                                       "--configuration" "Release"
                                       "--no-restore" "-warnaserror"]})]
          (is (str/includes? (:output result)
                             "PdfCube.IO focused behavior passed."))))
      (finally
        (delete-tree! root)))))
