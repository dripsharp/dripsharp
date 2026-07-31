(ns dripsharp.pkl.brine-xunit
  "Generates Brine's disposable upstream-derived xUnit suite.

  Normalized case data and vendored fixtures remain mechanically distinct from
  authored package adapters and deterministic project/wrapper glue."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.core-test-contract :as core-contract]
            [dripsharp.pkl.language-snippet-contract :as language-contract]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files LinkOption OpenOption Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private provenance-columns
  ["path" "class" "upstream-revision" "source-path" "source-sha256"
   "transformation" "emitted-sha256" "license" "notice"
   "durable-source" "authored-lines" "review-evidence" "line-budget"])

(def ^:private provenance-classes
  #{"mechanically-upstream-derived"
    "vendored-third-party"
    "dripsharp-authored-test-infrastructure"
    "deterministic-generated-wrapper"})

(def ^:private excluded-ledger-paths
  #{"SHA256SUMS" "TEST-PROVENANCE.tsv"})

(def ^:private authorship-columns
  ["source-path" "sha256" "lines" "line-budget" "review-evidence" "role"])

(def ^:private required-authored-sources
  #{"src/dripsharp/consumer_tests.clj"
    "src/dripsharp/pkl/brine_xunit.clj"
    "targets/pkl/consumer-tests/CoreConsumerTests.cs"
    "targets/pkl/consumer-tests/ParserConsumerTests.cs"
    "targets/pkl/consumer-tests/UpstreamContractTests.cs"
    "targets/pkl/consumer-tests/fixtures/sample.pkl"
    "validation/language-snippet-runner/LanguageSnippetPackageRunner.cs"
    "validation/pkl-core-corpus/PklCorePackageCorpusRunner.cs"
    "validation/pkl-core-corpus/fixtures/module.pkl"
    "validation/pkl-core-corpus/fixtures/payload.txt"})

(defn- fail!
  [message data]
  (throw (ex-info message
                  (assoc data :kind :brine-xunit-generation-failed))))

(defn- portable
  [value]
  (str/replace (str value) "\\" "/"))

(defn- write-text!
  [path text]
  (Files/createDirectories (.getParent (paths/path path))
                           (make-array FileAttribute 0))
  (Files/writeString (paths/path path) text StandardCharsets/UTF_8
                     (make-array OpenOption 0))
  path)

(defn- copy-file!
  [source destination]
  (Files/createDirectories (.getParent (paths/path destination))
                           (make-array FileAttribute 0))
  (Files/copy (paths/path source) (paths/path destination)
              (into-array StandardCopyOption
                          [StandardCopyOption/REPLACE_EXISTING]))
  destination)

(defn- regular-files
  ([root]
   (regular-files root false))
  ([root follow-links?]
   (let [options (if follow-links?
                   (into-array FileVisitOption [FileVisitOption/FOLLOW_LINKS])
                   (make-array FileVisitOption 0))]
     (with-open [entries (Files/walk (paths/path root) options)]
       (->> (.toArray entries)
            (map #(cast Path %))
            (filter #(Files/isRegularFile
                      ^Path %
                      (if follow-links?
                        (make-array LinkOption 0)
                        (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))
            (sort-by #(portable (.relativize (paths/path root) ^Path %)))
            vec)))))

(defn- symbolic-links
  [root]
  (with-open [entries (Files/walk (paths/path root)
                                  (make-array FileVisitOption 0))]
    (->> (.toArray entries)
         (map #(cast Path %))
         (filter #(Files/isSymbolicLink ^Path %))
         (sort-by #(portable (.relativize (paths/path root) ^Path %)))
         vec)))

(defn- line-count
  [file]
  (count (str/split-lines
          (Files/readString (paths/path file) StandardCharsets/UTF_8))))

(defn- read-authorship-inventory!
  [root]
  (let [path (paths/resolve-path root "targets/pkl/consumer-test-authorship.edn")
        inventory (edn/read-string (Files/readString path StandardCharsets/UTF_8))]
    (when-not (and (= 1 (:schema-version inventory))
                   (= :pkl-generated-xunit (:scope inventory))
                   (vector? (:sources inventory))
                   (seq (:sources inventory)))
      (fail! "Brine test authorship inventory has an unknown schema"
             {:reason :invalid-test-authorship-inventory :path (str path)}))
    (let [entries
          (mapv
           (fn [entry]
             (when-not (= #{:path :sha256 :lines :line-budget
                            :review-evidence :role}
                          (set (keys entry)))
               (fail! "Brine test authorship entry has unknown fields"
                      {:reason :invalid-test-authorship-entry :entry entry}))
             (let [source (paths/resolve-path root (:path entry))
                   actual-sha (util/sha256-file source)
                   actual-lines (line-count source)]
               (when-not (and (= actual-sha (:sha256 entry))
                              (= actual-lines (:lines entry))
                              (<= actual-lines (:line-budget entry))
                              (= "beads:pkl-nk5q" (:review-evidence entry))
                              (keyword? (:role entry)))
                 (fail! "Brine authored test source drifted from its reviewed budget"
                        {:reason :test-authorship-drift
                         :path (:path entry)
                         :expected entry
                         :actual {:sha256 actual-sha :lines actual-lines}}))
               entry))
           (:sources inventory))
          paths (mapv :path entries)]
      (when-not (= (count paths) (count (distinct paths)))
        (fail! "Brine test authorship inventory contains duplicate paths"
               {:reason :duplicate-test-authorship-source :paths paths}))
      (when-not (= required-authored-sources (set paths))
        (fail! "Brine test authorship inventory does not cover the exact source boundary"
               {:reason :test-authorship-coverage
                :expected (vec (sort required-authored-sources))
                :actual (vec (sort paths))}))
      {:path path :entries entries :by-path (into {} (map (juxt :path identity) entries))})))

(defn- render-authorship
  [entries]
  (str
   (str/join "\t" authorship-columns) "\n"
   (apply str
          (for [entry (sort-by :path entries)]
            (str (str/join "\t"
                           [(:path entry) (:sha256 entry) (:lines entry)
                            (:line-budget entry) (:review-evidence entry)
                            (name (:role entry))])
                 "\n")))))

(defn- relative
  [root file]
  (portable (.relativize (paths/path root) (paths/path file))))

(defn- generated-row
  [tests-root generator source-path output]
  {"path" (relative tests-root output)
   "class" "deterministic-generated-wrapper"
   "upstream-revision" "-"
   "source-path" source-path
   "source-sha256" (util/sha256-file generator)
   "transformation" "dripsharp.pkl.brine-xunit/v1"
   "emitted-sha256" (util/sha256-file output)
   "license" "-"
   "notice" "-"
   "durable-source" (portable source-path)
   "authored-lines" "-"
   "review-evidence" "beads:pkl-nk5q"
   "line-budget" "-"})

(defn- authored-row
  [root tests-root source output]
  (let [source-path (relative root source)
        lines (line-count source)]
    {"path" (relative tests-root output)
     "class" "dripsharp-authored-test-infrastructure"
     "upstream-revision" "-"
     "source-path" source-path
     "source-sha256" (util/sha256-file source)
     "transformation" "byte-for-byte-copy"
     "emitted-sha256" (util/sha256-file output)
     "license" "-"
     "notice" "-"
     "durable-source" source-path
     "authored-lines" (str lines)
     "review-evidence" "beads:pkl-nk5q"
     "line-budget" (str lines)}))

(defn- mechanical-row
  [tests-root revision source-path source-sha transformation output]
  {"path" (relative tests-root output)
   "class" "mechanically-upstream-derived"
   "upstream-revision" revision
   "source-path" source-path
   "source-sha256" source-sha
   "transformation" transformation
   "emitted-sha256" (util/sha256-file output)
   "license" "Apache-2.0"
   "notice" "Derived from Apple Pkl at the pinned revision."
   "durable-source" "-"
   "authored-lines" "-"
   "review-evidence" "-"
   "line-budget" "-"})

(defn- vendored-row
  [tests-root revision upstream source output]
  {"path" (relative tests-root output)
   "class" "vendored-third-party"
   "upstream-revision" revision
   "source-path" (relative upstream source)
   "source-sha256" (util/sha256-file source)
   "transformation" "materialized-byte-for-byte-fixture-copy"
   "emitted-sha256" (util/sha256-file output)
   "license" "Apache-2.0"
   "notice" "Copyright Apple Inc.; vendored from apple/pkl under Apache-2.0."
   "durable-source" "-"
   "authored-lines" "-"
   "review-evidence" "-"
   "line-budget" "-"})

(defn- render-runner-project
  [assembly-name embedded-fixtures?]
  (str
   "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
   "  <PropertyGroup>\n"
   "    <OutputType>Exe</OutputType>\n"
   "    <TargetFramework>net10.0</TargetFramework>\n"
   "    <AssemblyName>" assembly-name "</AssemblyName>\n"
   "    <ImplicitUsings>enable</ImplicitUsings>\n"
   "    <Nullable>enable</Nullable>\n"
   "    <RollForward>Major</RollForward>\n"
   "  </PropertyGroup>\n"
   "  <ItemGroup>\n"
   "    <ProjectReference Include=\"../../src/DripSharp.Brine/DripSharp.Brine.csproj\" />\n"
   "    <ProjectReference Include=\"../../src/DripSharp.Brine.Parser/DripSharp.Brine.Parser.csproj\" />\n"
   "  </ItemGroup>\n"
   (when embedded-fixtures?
     (str
      "  <ItemGroup>\n"
      "    <EmbeddedResource Include=\"Fixtures/payload.txt\" "
      "LogicalName=\"Corpus.Resources.payload.txt\" />\n"
      "    <EmbeddedResource Include=\"Fixtures/module.pkl\" "
      "LogicalName=\"Corpus.Resources.module.pkl\" />\n"
      "  </ItemGroup>\n"))
   "</Project>\n"))

(defn- render-boundary-report
  [rows language-count core-count]
  (let [counts (frequencies (map #(get % "class") rows))]
    (str
     "# Generated Brine test boundary\n\n"
     "This report is generated by DripSharp. The product checkout contains "
     language-count " in-scope language-snippet xUnit rows and " core-count
     " Pkl.Core product-contract rows (523 apply on non-Windows hosts and all "
     "524 apply on Windows).\n\n"
     "| Provenance class | Files |\n"
     "| --- | ---: |\n"
     (apply str
            (for [class (sort provenance-classes)]
              (str "| `" class "` | " (get counts class 0) " |\n")))
     "\nMechanically derived contracts, vendored upstream fixtures, authored "
     "DripSharp adapters, and deterministic wrapper/glue are kept in separate "
     "files. `TEST-PROVENANCE.tsv` binds every classified file to its source "
     "and emitted SHA-256, while `TEST-AUTHORSHIP.tsv` records the reviewed "
     "source hashes and line budgets for generators and authored adapters. "
     "Durable fixes belong in `dripsharp/dripsharp`, not "
     "in this generated checkout.\n")))

(defn- render-ledger
  [rows]
  (str
   (str/join "\t" provenance-columns) "\n"
   (apply str
          (for [row (sort-by #(get % "path") rows)]
            (str
             (str/join
              "\t"
              (for [column provenance-columns]
                (let [value (get row column)]
                  (when (or (nil? value)
                            (str/includes? value "\t")
                            (str/includes? value "\n"))
                    (fail! "Test provenance field is missing or unsafe"
                           {:path (get row "path") :column column :value value}))
                  value)))
             "\n")))))

(defn- parse-ledger!
  [ledger]
  (let [lines (str/split-lines
               (Files/readString (paths/path ledger) StandardCharsets/UTF_8))
        columns (some-> (first lines) (str/split #"\t" -1) vec)]
    (when-not (= provenance-columns columns)
      (fail! "Generated test provenance has an unknown schema"
             {:reason :test-provenance-schema
              :expected provenance-columns :actual columns}))
    (mapv
     (fn [index line]
       (let [fields (str/split line #"\t" -1)]
         (when-not (= (count columns) (count fields))
           (fail! "Generated test provenance row is malformed"
                  {:reason :malformed-test-provenance-row
                   :line (+ index 2)}))
         (zipmap columns fields)))
     (range)
     (rest lines))))

(defn verify-provenance!
  "Rejects missing, duplicate, contradictory, unknown, falsely mechanical, or
  hash-drifted generated test provenance."
  [tests-root]
  (let [tests-root (paths/absolute tests-root)
        ledger (paths/resolve-path tests-root "TEST-PROVENANCE.tsv")]
    (when-not (paths/regular-file? ledger)
      (fail! "Generated Brine test provenance ledger is missing"
             {:reason :missing-test-provenance :path (str ledger)}))
    (let [rows (parse-ledger! ledger)
          paths (mapv #(get % "path") rows)
          duplicates (->> paths frequencies
                          (keep (fn [[path n]] (when (> n 1) path)))
                          sort vec)
          actual-paths
          (->> (regular-files tests-root)
               (map #(relative tests-root %))
               (remove excluded-ledger-paths)
               sort vec)]
      (when (seq duplicates)
        (fail! "Generated test provenance contains duplicate paths"
               {:reason :duplicate-test-provenance :paths duplicates}))
      (when-not (= actual-paths (vec (sort paths)))
        (fail! "Generated test provenance does not classify every test file exactly once"
               {:reason :test-provenance-coverage
                :expected actual-paths :actual (vec (sort paths))}))
      (doseq [row rows]
        (let [class (get row "class")
              output (paths/resolve-path tests-root (get row "path"))]
          (when-not (provenance-classes class)
            (fail! "Generated test provenance contains an unknown class"
                   {:reason :unknown-test-provenance-class
                    :path (get row "path") :class class}))
          (when-not (= (get row "emitted-sha256")
                       (util/sha256-file output))
            (fail! "Generated test provenance emitted hash drifted"
                   {:reason :test-provenance-hash-drift
                    :path (get row "path")}))
          (when (#{"mechanically-upstream-derived" "vendored-third-party"} class)
            (when-not (and (= language-contract/pinned-upstream-revision
                              (get row "upstream-revision"))
                           (re-matches #"[0-9a-f]{64}"
                                       (get row "source-sha256"))
                           (not= "-" (get row "source-path"))
                           (not= "-" (get row "transformation"))
                           (= "-" (get row "durable-source"))
                           (= "-" (get row "authored-lines"))
                           (= "-" (get row "review-evidence"))
                           (= "-" (get row "line-budget")))
              (fail! "Upstream-derived test material lacks exact provenance"
                     {:reason :invalid-mechanical-test-provenance :row row})))
          (when (= "dripsharp-authored-test-infrastructure" class)
            (when-not (and (= "-" (get row "upstream-revision"))
                           (every? #(not= "-" (get row %))
                                   ["durable-source" "authored-lines"
                                    "review-evidence" "line-budget"])
                           (= (get row "authored-lines")
                              (get row "line-budget")))
              (fail! "Authored test infrastructure is unbudgeted or falsely mechanical"
                     {:reason :invalid-authored-test-provenance :row row})))))
      {:ledger ledger :rows rows :counts (frequencies (map #(get % "class") rows))})))

(defn- copy-tree!
  [tests-root upstream revision source-root destination-root rows]
  (doseq [source (regular-files source-root false)]
    (let [output (paths/resolve-path
                  destination-root (relative source-root source))]
      (copy-file! source output)
      (swap! rows conj
             (vendored-row tests-root revision upstream source output)))))

(defn- add-existing-rows!
  [root tests-root generator rows]
  (let [authored
        [["targets/pkl/consumer-tests/CoreConsumerTests.cs"
          "DripSharp.Brine.Tests/CoreConsumerTests.cs"]
         ["targets/pkl/consumer-tests/ParserConsumerTests.cs"
          "DripSharp.Brine.Tests/ParserConsumerTests.cs"]
         ["targets/pkl/consumer-tests/fixtures/sample.pkl"
          "DripSharp.Brine.Tests/Fixtures/sample.pkl"]]
        generated
        [["consumer-test-project" "DripSharp.Brine.Tests/DripSharp.Brine.Tests.csproj"]
         ["consumer-test-readme" "README.md"]
         ["consumer-test-notice" "NOTICE.md"]]]
    (doseq [[source output] authored]
      (swap! rows conj
             (authored-row root tests-root
                           (paths/resolve-path root source)
                           (paths/resolve-path tests-root output))))
    (doseq [[source-path output] generated]
      (swap! rows conj
             (generated-row tests-root generator source-path
                            (paths/resolve-path tests-root output))))))

(defn emit!
  "Adds the normalized upstream-derived Brine suite to an already-created
  generated tests tree."
  [{:keys [workspace-root target-contract tests-root]}]
  (when-not (= :pkl (:target target-contract))
    (fail! "The Brine xUnit generator only accepts the Pkl target"
           {:reason :wrong-target :target (:target target-contract)}))
  (let [root (paths/workspace-root (:target-directory target-contract))
        tests-root (paths/absolute tests-root)
        upstream (paths/resolve-path root "research" "pkl")
        revision language-contract/pinned-upstream-revision
        language-manifest (paths/resolve-path
                           root "validation/language-snippet-contract"
                           "LanguageSnippetContract.tsv")
        core-manifest (paths/resolve-path
                       root "validation/pkl-core-test-contract"
                       "PklCoreTestContract.tsv")
        language (language-contract/validate-manifest! root language-manifest)
        core (core-contract/validate-manifest! root core-manifest)
        authorship (read-authorship-inventory! root)
        generator (paths/resolve-path root "src/dripsharp/pkl/brine_xunit.clj")
        rows (atom [])
        contracts (paths/resolve-path tests-root "Contracts")
        test-project (paths/resolve-path tests-root "DripSharp.Brine.Tests")
        language-runner
        (paths/resolve-path tests-root "DripSharp.Brine.LanguageSnippetRunner")
        core-runner (paths/resolve-path tests-root "DripSharp.Brine.CoreTestRunner")
        language-contract-output
        (paths/resolve-path contracts "LanguageSnippetContract.tsv")
        core-contract-output
        (paths/resolve-path contracts "PklCoreTestContract.tsv")
        expected-output
        (paths/resolve-path contracts "LanguageSnippetExpected.tsv")
        symlinks (atom [])]
    (add-existing-rows! root tests-root generator rows)
    (doseq [[source output source-path transformation]
            [[language-manifest language-contract-output
              "validation/language-snippet-contract/LanguageSnippetContract.tsv"
              "validated-normalized-contract-copy-v1"]
             [core-manifest core-contract-output
              "validation/pkl-core-test-contract/PklCoreTestContract.tsv"
              "validated-normalized-contract-copy-v1"]]]
      (copy-file! source output)
      (swap! rows conj
             (mechanical-row tests-root revision source-path
                             (util/sha256-file source) transformation output)))
    (language-contract/write-expected-results! language expected-output)
    (swap! rows conj
           (mechanical-row
            tests-root revision "contract-cases:language-snippet"
            (util/sha256-file language-manifest)
            "language-contract/write-expected-results-v1" expected-output))
    (let [harness-source
          (paths/resolve-path root
                              "targets/pkl/consumer-tests/UpstreamContractTests.cs")
          harness-output (paths/resolve-path test-project "UpstreamContractTests.cs")]
      (copy-file! harness-source harness-output)
      (swap! rows conj
             (authored-row root tests-root harness-source harness-output)))
    (doseq [[source-path runner project-name embedded?]
            [["validation/language-snippet-runner/LanguageSnippetPackageRunner.cs"
              language-runner "DripSharp.Brine.LanguageSnippetRunner" false]
             ["validation/pkl-core-corpus/PklCorePackageCorpusRunner.cs"
              core-runner "DripSharp.Brine.CoreTestRunner" true]]]
      (let [source (paths/resolve-path root source-path)
            output (paths/resolve-path runner "Program.cs")
            project (paths/resolve-path runner (str project-name ".csproj"))]
        (copy-file! source output)
        (swap! rows conj (authored-row root tests-root source output))
        (write-text! project (render-runner-project project-name embedded?))
        (swap! rows conj
               (generated-row tests-root generator
                              (str "runner-project:" project-name) project))))
    (doseq [file ["payload.txt" "module.pkl"]]
      (let [source (paths/resolve-path root "validation/pkl-core-corpus/fixtures" file)
            output (paths/resolve-path core-runner "Fixtures" file)]
        (copy-file! source output)
        (swap! rows conj (authored-row root tests-root source output))))
    (let [fixture-root (paths/resolve-path tests-root "Fixtures" "pkl")]
      (doseq [[source-relative destination-relative]
              [["pkl-core/src/test/files/LanguageSnippetTests/input"
                "pkl-core/src/test/files/LanguageSnippetTests/input"]
               ["pkl-core/src/test/files/LanguageSnippetTests/input-helper"
                "pkl-core/src/test/files/LanguageSnippetTests/input-helper"]
               ["pkl-core/src/test/resources" "pkl-core/src/test/resources"]
               ["pkl-commons-test/build/keystore"
                "pkl-commons-test/build/keystore"]
               ["pkl-commons-test/build/test-packages"
                "pkl-commons-test/build/test-packages"]]]
        (let [source (paths/resolve-path upstream source-relative)]
          (when-not (paths/exists? source)
            (fail! "Required standalone Brine fixture tree is missing"
                   {:reason :missing-brine-xunit-fixtures
                    :path (str source)}))
          (copy-tree! tests-root upstream revision source
                      (paths/resolve-path fixture-root destination-relative)
                      rows)
          (doseq [link (symbolic-links source)]
            (let [link-relative (relative source link)
                  target (portable (Files/readSymbolicLink link))]
              (swap! symlinks conj
                     {:path (portable
                             (str destination-relative "/" link-relative))
                      :target target
                      :source-path (relative upstream link)
                      :source-sha256 (util/sha256-text target)})))))
      (let [symlink-output (paths/resolve-path fixture-root "SYMLINKS.tsv")
            content
            (str
             "path\ttarget\tsource-path\tsource-sha256\n"
             (apply str
                    (for [{:keys [path target source-path source-sha256]}
                          (sort-by :path @symlinks)]
                      (str path "\t" target "\t" source-path "\t"
                           source-sha256 "\n"))))]
        (when-not (= 1 (count @symlinks))
          (fail! "Pinned language fixtures have an unexpected symlink boundary"
                 {:reason :fixture-symlink-boundary-drift
                  :symlinks @symlinks}))
        (write-text! symlink-output content)
        (swap! rows conj
               (mechanical-row
                tests-root revision "fixture-symlink-boundary"
                (util/sha256-text
                 (str/join "\n"
                           (map (juxt :source-path :target) @symlinks)))
                "materialized-symlink-manifest-v1" symlink-output))))
    (let [authorship-output
          (paths/resolve-path tests-root "TEST-AUTHORSHIP.tsv")]
      (write-text! authorship-output
                   (render-authorship (:entries authorship)))
      (swap! rows conj
             (generated-row tests-root generator
                            "targets/pkl/consumer-test-authorship.edn"
                            authorship-output)))
    (let [boundary (paths/resolve-path tests-root "TEST-BOUNDARY.md")]
      (write-text! boundary
                   (render-boundary-report @rows 909 524))
      (swap! rows conj
             (generated-row tests-root generator "test-boundary-report" boundary)))
    (let [ledger (paths/resolve-path tests-root "TEST-PROVENANCE.tsv")]
      (write-text! ledger (render-ledger @rows))
      (let [proof (verify-provenance! tests-root)]
        {:language-cases (count (:cases language))
         :language-in-scope
         (count (remove #(= "outside-epic-approved-exclusion"
                            (:product-scope %))
                        (:cases language)))
         :core-cases (count (:cases core))
         :core-product-cases
         (count (filter #(= "complete-pkl-core-runner"
                            (:execution-owner %))
                        (:cases core)))
         :provenance proof}))))
