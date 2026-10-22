(ns dripsharp.pdfcube.generated-project
  "Local mode shared by the pdfbox-* differentials: compares the existing
  target/generated/pdfcarton staging through a checked-in project-reference
  probe instead of a packed NuGet consumer. It claims neither clean
  generation nor package consumption."
  (:require [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Path]))

(def generate-command
  "Generates the staging that the local generated-project mode compares."
  ["clojure" "-J-Xmx28g" "-M:run" "generate" "pdfcube" "pdfcube-pdfbox"])

(def default-maven-manifest
  "Maven discovery manifest written by the same generation."
  "target/maven-reactor-inputs-0.tsv")

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn proof
  "Selects the existing generated PdfCarton project and the Maven manifest of
  the same generation, and requires research/pdfbox at the pinned revision.
  Returns a proof shaped like a package proof's :verification so oracle code
  can read the generation from the same path. fail! receives a message and
  data and must throw."
  [{:keys [run-command! workspace-root maven-manifest fail!]}]
  (let [root (paths/absolute workspace-root)
        manifest (configured-path root (or maven-manifest
                                           default-maven-manifest))
        project (paths/resolve-path root "target" "generated" "pdfcarton"
                                    "src" "DripSharp.PdfCarton"
                                    "DripSharp.PdfCarton.csproj")]
    (doseq [path [manifest project]]
      (when-not (paths/regular-file? path)
        (fail! "Generated PdfCarton project is missing; generate it first"
               {:path (str path)
                :command (str/join " " generate-command)})))
    (let [input (maven/project-by-id!
                 (maven/read-reactor-manifest manifest)
                 (str "org.apache.pdfbox:pdfbox:"
                      (baseline/upstream-version :pdfcube)))
          pinned (baseline/upstream-revision :pdfcube)
          revision
          (str/trim
           (:output
            (run-command!
             {:command ["git" "rev-parse" "HEAD"]
              :directory (paths/resolve-path root "research" "pdfbox")
              :timeout-ms 10000})))]
      (when-not (= revision pinned)
        (fail! "PDFBox checkout differs from the pinned oracle revision"
               {:expected pinned :actual revision}))
      {:mode :generated-project
       :verification {:generation (harness/configuration root revision input)}
       :project project
       :manifest manifest})))

(defn package-summary
  "The :package entry of a differential summary: the packed identity, or the
  generated project in generated-project mode."
  [proof]
  (if (= :generated-project (:mode proof))
    {:boundary :generated-project
     :project (str (:project proof))
     :maven-manifest (str (:manifest proof))}
    {:id (get-in proof [:identity :id])
     :version (get-in proof [:identity :version])
     :sha256 (get-in proof [:identity :sha256])}))

(defn consumer-summary
  "The :consumer entry of a differential summary: the packed consumer's
  dependency proof, or the project-reference probe in generated-project mode."
  [proof]
  (if (= :generated-project (:mode proof))
    {:strategy :public-project-reference
     :project (str (:probe proof))
     :target-framework "net10.0"}
    (:dependency-proof proof)))

(defn probe-project
  "Resolves a checked-in probe project under validation/."
  ^Path [^Path root directory project-file]
  (paths/resolve-path root "validation" directory project-file))

(defn build-probe!
  "Builds a project-reference probe, and with it the generated PdfCarton
  project, warnings as errors."
  [run-command! ^Path root ^Path project]
  (run-command! {:command ["dotnet" "build" (str project)
                           "--configuration" "Release"
                           "--nologo" "--verbosity:minimal"
                           "--no-incremental" "-warnaserror"
                           "-p:NuGetAudit=false"]
                 :directory root
                 :timeout-ms 900000}))

(defn run-probe!
  "Runs a probe built by build-probe! with the given arguments."
  [run-command! ^Path root ^Path project arguments]
  (run-command! {:command
                 (into ["dotnet" "run" "--project" (str project)
                        "--configuration" "Release"
                        "--no-build" "--no-restore" "--"]
                       (map str arguments))
                 :directory root
                 :timeout-ms 300000}))
