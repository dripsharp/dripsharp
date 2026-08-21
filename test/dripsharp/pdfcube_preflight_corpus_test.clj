(ns dripsharp.pdfcube-preflight-corpus-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.preflight-corpus :as corpus]
            [dripsharp.util :as util])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  []
  (Files/createTempDirectory
   "pdfcube-preflight-corpus-test"
   (make-array FileAttribute 0)))

(defn- write!
  [^Path path contents]
  (Files/createDirectories (.getParent path)
                           (make-array FileAttribute 0))
  (Files/writeString path contents StandardCharsets/UTF_8
                     (make-array OpenOption 0))
  path)

(defn- validated-manifest
  []
  (let [root (paths/workspace-root)]
    (corpus/validate-manifest!
     root
     (paths/resolve-path
      root "validation" "pdfcube-preflight-corpus" "corpus.edn"))))

(defn- conformant-rows
  [validated origin]
  (mapv
   (fn [{:keys [id format expected-outcome payload-sha256]}]
     (let [valid? (= :valid expected-outcome)]
       {:case-id id
        :origin origin
        :format (name format)
        :expected-outcome (name expected-outcome)
        :input-sha256 payload-sha256
        :status "PASS"
        :valid (str valid?)
        :error-count (if valid? "0" "1")
        :error-codes (if valid? "" "1.0")
        :warnings (if valid? "" "false")
        :pages (if valid? "" "null")
        :details (if valid? "" "normalized detail")
        :source-closed "true"
        :document-closed "true"
        :diagnostic ""}))
   (:cases validated)))

(def ^:private exact-preflight-assembly-dependencies
  [{:assembly-name "DripSharp.PdfCarton"
    :package-id "DripSharp.PdfCarton"
    :version "3.0.8-alpha.2"
    :target-framework "netstandard2.0"}
   {:assembly-name "DripSharp.PdfCarton.Fonts"
    :package-id "DripSharp.PdfCarton.Fonts"
    :version "3.0.8-alpha.2"
    :target-framework "netstandard2.0"}
   {:assembly-name "DripSharp.PdfCarton.IO"
    :package-id "DripSharp.PdfCarton.IO"
    :version "3.0.8-alpha.2"
    :target-framework "netstandard2.0"}
   {:assembly-name "DripSharp.PdfCarton.Xmp"
    :package-id "DripSharp.PdfCarton.Xmp"
    :version "3.0.8-alpha.2"
    :target-framework "netstandard2.0"}])

(def ^:private exact-preflight-framework-omissions
  [{:id "Microsoft.Bcl.AsyncInterfaces" :version "10.0.0"}
   {:id "Microsoft.Bcl.Cryptography" :version "10.0.0"}])

(def ^:private preflight-version "3.0.8-alpha.2")

(def ^:private product-package-ids
  ["DripSharp.PdfCarton"
   "DripSharp.PdfCarton.Fonts"
   "DripSharp.PdfCarton.IO"
   "DripSharp.PdfCarton.Preflight"
   "DripSharp.PdfCarton.Xmp"])

(def ^:private external-package-versions
  {"Microsoft.Bcl.AsyncInterfaces" "10.0.0"
   "Microsoft.Bcl.Cryptography" "10.0.0"
   "Microsoft.CSharp" "4.7.0"
   "Microsoft.Extensions.DependencyInjection.Abstractions" "10.0.0"
   "Microsoft.Extensions.Logging.Abstractions" "10.0.0"
   "Microsoft.NETCore.Platforms" "1.1.0"
   "NETStandard.Library" "2.0.3"
   "SkiaSharp" "4.150.1"
   "SkiaSharp.NativeAssets.Linux" "4.150.1"
   "SkiaSharp.NativeAssets.macOS" "4.150.1"
   "SkiaSharp.NativeAssets.Win32" "4.150.1"
   "System.Buffers" "4.6.1"
   "System.Diagnostics.DiagnosticSource" "10.0.0"
   "System.Formats.Asn1" "10.0.0"
   "System.Memory" "4.6.3"
   "System.Numerics.Vectors" "4.6.1"
   "System.Runtime.CompilerServices.Unsafe" "6.1.2"
   "System.Security.Cryptography.Cng" "5.0.0"
   "System.Security.Cryptography.Pkcs" "10.0.0"
   "System.Text.Encoding.CodePages" "10.0.0"
   "System.Threading.Tasks.Extensions" "4.6.3"})

(def ^:private feed-only-package-ids
  #{"Microsoft.NETCore.Platforms" "NETStandard.Library"})

(def ^:private preflight-dependency-ids
  {"DripSharp.PdfCarton.Preflight"
   ["DripSharp.PdfCarton.Xmp" "DripSharp.PdfCarton"
    "Microsoft.CSharp" "Microsoft.Extensions.Logging.Abstractions"
    "SkiaSharp" "System.Memory" "System.Text.Encoding.CodePages"]
   "DripSharp.PdfCarton"
   ["DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.IO"
    "Microsoft.CSharp" "Microsoft.Extensions.Logging.Abstractions"
    "SkiaSharp" "System.Memory" "System.Security.Cryptography.Pkcs"
    "System.Text.Encoding.CodePages"]
   "DripSharp.PdfCarton.Fonts"
   ["DripSharp.PdfCarton.IO" "Microsoft.CSharp"
    "Microsoft.Extensions.Logging.Abstractions" "SkiaSharp"
    "SkiaSharp.NativeAssets.Linux" "System.Formats.Asn1" "System.Memory"
    "System.Text.Encoding.CodePages"]
   "DripSharp.PdfCarton.IO"
   ["Microsoft.CSharp" "Microsoft.Extensions.Logging.Abstractions"
    "System.Memory" "System.Text.Encoding.CodePages"]
   "DripSharp.PdfCarton.Xmp"
   ["Microsoft.CSharp" "Microsoft.Extensions.Logging.Abstractions"
    "System.Memory" "System.Text.Encoding.CodePages"]
   "Microsoft.Extensions.Logging.Abstractions"
   ["Microsoft.Extensions.DependencyInjection.Abstractions"
    "System.Diagnostics.DiagnosticSource" "System.Buffers" "System.Memory"]
   "Microsoft.Extensions.DependencyInjection.Abstractions"
   ["Microsoft.Bcl.AsyncInterfaces" "System.Threading.Tasks.Extensions"]
   "System.Diagnostics.DiagnosticSource"
   ["Microsoft.Bcl.AsyncInterfaces"]
   "SkiaSharp"
   ["SkiaSharp.NativeAssets.macOS" "SkiaSharp.NativeAssets.Win32"
    "System.Memory"]
   "System.Formats.Asn1"
   ["System.Buffers" "System.Memory"]
   "System.Memory"
   ["System.Buffers" "System.Numerics.Vectors"
    "System.Runtime.CompilerServices.Unsafe"]
   "System.Security.Cryptography.Pkcs"
   ["Microsoft.Bcl.Cryptography" "System.Buffers"
    "System.Runtime.CompilerServices.Unsafe" "System.Security.Cryptography.Cng"]
   "Microsoft.Bcl.Cryptography"
   ["System.Formats.Asn1" "System.Memory"]
   "System.Text.Encoding.CodePages"
   ["System.Memory" "System.Runtime.CompilerServices.Unsafe"]})

(def ^:private resolved-package-ids
  #{"DripSharp.PdfCarton"
    "DripSharp.PdfCarton.Fonts"
    "DripSharp.PdfCarton.IO"
    "DripSharp.PdfCarton.Preflight"
    "DripSharp.PdfCarton.Xmp"
    "Microsoft.Extensions.DependencyInjection.Abstractions"
    "Microsoft.Extensions.Logging.Abstractions"
    "SkiaSharp"
    "SkiaSharp.NativeAssets.Linux"
    "SkiaSharp.NativeAssets.macOS"
    "SkiaSharp.NativeAssets.Win32"
    "System.Security.Cryptography.Pkcs"})

(def ^:private pruned-package-ids
  #{"Microsoft.CSharp"
    "System.Buffers"
    "System.Diagnostics.DiagnosticSource"
    "System.Formats.Asn1"
    "System.Memory"
    "System.Numerics.Vectors"
    "System.Runtime.CompilerServices.Unsafe"
    "System.Security.Cryptography.Cng"
    "System.Text.Encoding.CodePages"
    "System.Threading.Tasks.Extensions"})

(def ^:private test-json-mapper (ObjectMapper.))

(defn- package-version
  [id]
  (if (str/starts-with? id "DripSharp.PdfCarton")
    preflight-version
    (get external-package-versions id)))

(defn- package-identity
  [id]
  {:id id :version (package-version id)})

(defn- dependency-identities
  [id]
  (mapv package-identity (get preflight-dependency-ids id [])))

(defn- consumer-assets
  [^Path packages resolved pruned]
  (let [target-framework "net10.0"
        package-entries
        (into {}
              (for [{:keys [id version]} resolved]
                [(str id "/" version)
                 {"type" "package"
                  "compile" {(str "lib/netstandard2.0/" id ".dll") {}}}]))
        libraries
        (into {}
              (for [{:keys [id version]} resolved]
                [(str id "/" version)
                 {"type" "package"
                  "path" (str (str/lower-case id) "/" version)}]))
        packages-path (str (paths/absolute packages))]
    {"version" 3
     "targets" {target-framework package-entries}
     "libraries" libraries
     "packageFolders" {packages-path {}}
     "project"
     {"restore"
      {"packagesPath" packages-path
       "originalTargetFrameworks" [target-framework]
       "frameworks" {target-framework {"targetAlias" target-framework}}}
      "frameworks"
      {target-framework
       {"targetAlias" target-framework
        "packagesToPrune"
        (into {}
              (map (fn [id] [id "(,9999.0.0]"]))
              pruned)}}}}))

(defn- corpus-consumer-fixture
  [proof-shape]
  (let [root (temp-dir)
        packages (.resolve root "packages")
        primary-id "DripSharp.PdfCarton.Preflight"
        published-ids (into product-package-ids
                            (sort (keys external-package-versions)))
        identities
        (mapv
         (fn [id]
           (let [identity (package-identity id)
                 artifact
                 (when (resolved-package-ids id)
                   (write!
                    (.resolve
                     packages
                     (str (str/lower-case id) "/" (:version identity) "/"
                          (str/lower-case id) "." (:version identity) ".nupkg"))
                    (str id "/" (:version identity))))]
             (assoc identity
                    :sha256 (if artifact
                              (util/sha256-file artifact)
                              (apply str (repeat 64 "a")))
                    :dependencies (dependency-identities id))))
         published-ids)
        by-id (into {} (map (juxt :id identity)) identities)
        primary (get by-id primary-id)
        product-packages
        (mapv
         (fn [id]
           {:identity (dissoc (get by-id id) :dependencies)
            :inspection {:dependencies (dependency-identities id)}})
         product-package-ids)
        external
        (mapv by-id (sort (keys external-package-versions)))
        exact-closure
        (mapv by-id
              (sort (set/difference (set published-ids)
                                    feed-only-package-ids)))
        resolved (mapv by-id (sort resolved-package-ids))
        project
        (write!
         (.resolve root "DripSharp.PdfCarton.Preflight.CorpusRunner.csproj")
         (str "<Project><PropertyGroup><TargetFramework>net10.0</TargetFramework>"
              "</PropertyGroup><ItemGroup><PackageReference Include=\""
              primary-id "\" Version=\"" preflight-version
              "\" /></ItemGroup></Project>"))
        assets-file
        (write!
         (.resolve root "obj/project.assets.json")
         (.writeValueAsString test-json-mapper
                              (consumer-assets packages resolved
                                               pruned-package-ids)))
        direct-package-proof
        {:identity primary
         :packages product-packages
         :external-packages external
         :verification
         {:generation
          {:destination
           {:project {:target-framework "netstandard2.0"}
            :package-consumer
            {:framework-omitted-packages
             exact-preflight-framework-omissions}}}}}
        package-proof
        (case proof-shape
          :direct-pack direct-package-proof
          :reused-verified-package
          (assoc direct-package-proof
                 :external-packages
                 (filterv #(not (feed-only-package-ids (:id %))) external)
                 :dependency-proof
                 {:expected-packages exact-closure
                  :resolved-packages resolved
                  :framework-omitted-packages
                  exact-preflight-framework-omissions}))]
    {:assets-file assets-file
     :by-id by-id
     :exact-closure exact-closure
     :identities identities
     :package-proof package-proof
     :packages packages
     :project project
     :resolved resolved}))

(defn- inspect-corpus-consumer
  [{:keys [project assets-file packages package-proof]}]
  (#'corpus/inspect-corpus-consumer-dependencies!
   project assets-file packages package-proof))

(defn- write-consumer-assets!
  [{:keys [assets-file packages]} resolved pruned]
  (write! assets-file
          (.writeValueAsString test-json-mapper
                               (consumer-assets packages resolved pruned))))

(defn- update-package-dependency
  [package-proof package-id f]
  (update
   package-proof :packages
   (fn [packages]
     (mapv
      (fn [package]
        (if (= package-id (get-in package [:identity :id]))
          (update-in package [:inspection :dependencies] f)
          package))
      packages))))

(defn- preflight-dependencies
  [expected]
  (#'corpus/preflight-assembly-dependencies
   "DripSharp.PdfCarton.Preflight" expected))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest packed-preflight-dependency-entries-are-exactly-netstandard
  (let [actual (preflight-dependencies exact-preflight-assembly-dependencies)]
    (is (= exact-preflight-assembly-dependencies actual))
    (is (= ["lib/netstandard2.0/DripSharp.PdfCarton.dll"
            "lib/netstandard2.0/DripSharp.PdfCarton.Fonts.dll"
            "lib/netstandard2.0/DripSharp.PdfCarton.IO.dll"
            "lib/netstandard2.0/DripSharp.PdfCarton.Xmp.dll"]
           (mapv
            (fn [{:keys [assembly-name target-framework]}]
              (str "lib/" target-framework "/" assembly-name ".dll"))
            actual)))))

(deftest packed-preflight-dependency-contract-fails-closed
  (testing "the former IO and Fonts net10.0 package paths are rejected"
    (doseq [assembly-name
            ["DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"]]
      (let [stale
            (mapv
             (fn [dependency]
               (if (= assembly-name (:assembly-name dependency))
                 (assoc dependency :target-framework "net10.0")
                 dependency))
             exact-preflight-assembly-dependencies)
            error (caught #(preflight-dependencies stale))]
        (is (= :invalid-preflight-assembly-dependencies
               (:kind (ex-data error)))
            assembly-name)
        (is (= stale (:actual (ex-data error))) assembly-name))))
  (testing "missing, extra, and otherwise incorrect dependency evidence is rejected"
    (doseq [[label changed]
            [["missing" (pop exact-preflight-assembly-dependencies)]
             ["extra"
              (conj exact-preflight-assembly-dependencies
                    {:assembly-name "DripSharp.PdfCarton.Unexpected"
                     :package-id "DripSharp.PdfCarton.Unexpected"
                     :version "3.0.8-alpha.2"
                     :target-framework "netstandard2.0"})]
             ["framework"
              (assoc-in exact-preflight-assembly-dependencies
                        [0 :target-framework] "net9.0")]
             ["package"
              (assoc-in exact-preflight-assembly-dependencies
                        [0 :package-id] "DripSharp.PdfCarton.Unexpected")]]]
      (let [error (caught #(preflight-dependencies changed))]
        (is (= :invalid-preflight-assembly-dependencies
               (:kind (ex-data error)))
            label)
        (is (= exact-preflight-assembly-dependencies
               (:expected (ex-data error)))
            label)))))

(deftest preflight-corpus-derives-the-exact-published-dependency-closure
  (doseq [proof-shape [:direct-pack :reused-verified-package]]
    (let [fixture (corpus-consumer-fixture proof-shape)
          proof (inspect-corpus-consumer fixture)
          expected-identities
          (set (map (juxt :id :version) (:exact-closure fixture)))]
      (testing (name proof-shape)
        (is (= (if (= :direct-pack proof-shape) 21 19)
               (count (get-in fixture [:package-proof :external-packages]))))
        (is (= "net10.0" (:target-framework proof)))
        (is (= 24 (count (:expected-packages proof))))
        (is (= expected-identities
               (set (map (juxt :id :version) (:expected-packages proof)))))
        (is (not-any? feed-only-package-ids
                      (map :id (:expected-packages proof))))
        (is (= resolved-package-ids
               (set (map :id (:resolved-packages proof)))))
        (is (= pruned-package-ids
               (set (map :id (:pruned-packages proof)))))
        (is (every? #(= "(,9999.0.0]" (:prune-range %))
                    (:pruned-packages proof)))
        (is (= exact-preflight-framework-omissions
               (mapv #(select-keys % [:id :version])
                     (:framework-omitted-packages proof))))))))

(deftest preflight-corpus-regression-reproduces-the-former-all-feed-failure
  (let [{:keys [project assets-file packages package-proof identities]}
        (corpus-consumer-fixture :direct-pack)
        error
        (caught
         #(packaging/inspect-consumer-dependencies!
           project assets-file packages (:identity package-proof) identities
           exact-preflight-framework-omissions))]
    (is (= :package-consumption-failed (:kind (ex-data error))))
    (is (= "Microsoft.NETCore.Platforms/1.1.0"
           (:identity (ex-data error))))))

(deftest preflight-corpus-exact-closure-evidence-fails-closed
  (testing "missing and duplicate published feed identities are rejected"
    (let [fixture (corpus-consumer-fixture :direct-pack)
          missing
          (update-in fixture [:package-proof :external-packages]
                     (fn [packages]
                       (filterv #(not= "System.Memory" (:id %)) packages)))
          duplicate
          (update-in fixture [:package-proof :external-packages]
                     conj (get-in fixture [:by-id "System.Memory"]))]
      (doseq [[label changed] [["missing" missing] ["duplicate" duplicate]]]
        (let [error (caught #(inspect-corpus-consumer changed))]
          (is (= :package-consumption-failed (:kind (ex-data error))) label)
          (is (some? error) label)))))
  (testing "substituted and wrong-version published dependencies are rejected"
    (let [fixture (corpus-consumer-fixture :direct-pack)
          replace-memory
          (fn [replacement]
            (update-in
             fixture [:package-proof]
             update-package-dependency "DripSharp.PdfCarton.Preflight"
             (fn [dependencies]
               (mapv #(if (= "System.Memory" (:id %)) replacement %)
                     dependencies))))
          substituted (replace-memory {:id "Substituted.Package"
                                       :version "4.6.3"})
          wrong-version (replace-memory {:id "System.Memory"
                                         :version "4.6.2"})]
      (doseq [[label changed]
              [["substituted" substituted] ["wrong-version" wrong-version]]]
        (let [error (caught #(inspect-corpus-consumer changed))]
          (is (= :package-consumption-failed (:kind (ex-data error))) label)
          (is (some? error) label)))))
  (testing "missing, extra, substituted, and wrong-version restore rows are rejected"
    (doseq [[label transform]
            [["missing"
              #(filterv (fn [identity]
                          (not= "Microsoft.Extensions.Logging.Abstractions"
                                (:id identity)))
                        %)]
             ["extra"
              #(conj % {:id "Microsoft.NETCore.Platforms"
                        :version "1.1.0"
                        :sha256 (apply str (repeat 64 "a"))})]
             ["substituted"
              #(mapv (fn [identity]
                       (if (= "Microsoft.Extensions.Logging.Abstractions"
                              (:id identity))
                         (assoc identity :id "Substituted.Package")
                         identity))
                     %)]
             ["wrong-version"
              #(mapv (fn [identity]
                       (if (= "Microsoft.Extensions.Logging.Abstractions"
                              (:id identity))
                         (assoc identity :version "9.0.0")
                         identity))
                     %)]]]
      (let [fixture (corpus-consumer-fixture :direct-pack)
            changed (transform (:resolved fixture))
            _ (write-consumer-assets! fixture changed pruned-package-ids)
            error (caught #(inspect-corpus-consumer fixture))]
        (is (= :package-consumption-failed (:kind (ex-data error))) label)
        (is (some? error) label))))
  (testing "only the exact two framework-group omissions are accepted"
    (doseq [[label omissions]
            [["missing" (pop exact-preflight-framework-omissions)]
             ["extra"
              (conj exact-preflight-framework-omissions
                    {:id "Unexpected.Package" :version "1.0.0"})]
             ["duplicate"
              (conj exact-preflight-framework-omissions
                    (first exact-preflight-framework-omissions))]
             ["wrong-version"
              (assoc-in exact-preflight-framework-omissions
                        [0 :version] "9.0.0")]]]
      (let [fixture (assoc-in
                     (corpus-consumer-fixture :direct-pack)
                     [:package-proof :verification :generation :destination
                      :package-consumer :framework-omitted-packages]
                     omissions)
            error (caught #(inspect-corpus-consumer fixture))]
        (is (= :package-consumption-failed (:kind (ex-data error))) label)
        (is (some? error) label)))))

(deftest durable-corpus-covers-the-supported-preflight-contract
  (let [validated (validated-manifest)
        coverage (:coverage validated)
        staging (corpus/stage-corpus!
                 validated (.resolve (temp-dir) "corpus"))
        execution (corpus/write-execution-manifest!
                   validated (.resolve (temp-dir) "manifest.tsv"))]
    (is (= [:pdf-a1a :pdf-a1b] (:formats coverage)))
    (is (= [:invalid :malformed :valid] (:outcomes coverage)))
    (is (= 11 (:cases coverage)))
    (is (= 8 (:source-fixtures coverage)))
    (is (every? #(Files/isRegularFile
                  (.resolve staging (:staged-file %))
                  (make-array java.nio.file.LinkOption 0))
                (:cases validated)))
    (is (str/starts-with?
         (Files/readString execution StandardCharsets/UTF_8)
         "DRIPSHARP_PDFCARTON_PREFLIGHT_CORPUS_MANIFEST_V1\n"))
    (is (= "Apache-2.0"
           (get-in validated [:redistribution :license])))
    (is (not (str/blank?
              (get-in validated [:redistribution :constraint]))))))

(deftest corpus-comparator-and-failure-controls-fail-closed
  (let [validated (validated-manifest)
        root (temp-dir)
        upstream (write!
                  (.resolve root "upstream.tsv")
                  (corpus/render-results
                   (conformant-rows validated "upstream-java")))
        package (write!
                 (.resolve root "package.tsv")
                 (corpus/render-results
                  (conformant-rows validated "package-dotnet")))
        comparison (corpus/compare-results validated upstream package)
        controls (corpus/prove-fail-closed-controls!
                  validated upstream (.resolve root "controls"))]
    (is (= 11 (:total comparison)))
    (is (= 11 (:matched comparison)))
    (is (zero? (:mismatched comparison)))
    (is (= #{:perturbation :timeout :crash :leak :missing :nondeterminism}
           (set (keys controls))))
    (is (every? true? (vals controls)))))

(deftest corpus-provenance-checksums-are-enforced
  (let [root (paths/workspace-root)
        original
        (Files/readString
         (paths/resolve-path
          root "validation" "pdfcube-preflight-corpus" "corpus.edn")
         StandardCharsets/UTF_8)
        changed
        (str/replace-first
         original
         "640168ad8f5ec872ec082992664fd92a7df1c2e53e12af45f134c8be951ed342"
         (apply str (repeat 64 "0")))
        manifest (write! (.resolve (temp-dir) "corpus.edn") changed)
        error
        (try
          (corpus/validate-manifest! root manifest)
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :preflight-corpus-checksum-mismatch
           (:kind (ex-data error))))))

(deftest corpus-manifest-requires-exactly-one-edn-value
  (let [root (paths/workspace-root)
        original
        (Files/readString
         (paths/resolve-path
          root "validation" "pdfcube-preflight-corpus" "corpus.edn")
         StandardCharsets/UTF_8)]
    (doseq [[label contents reason]
            [["empty" "" :empty-edn]
             ["invalid" "{" :invalid-edn]
             ["trailing" (str original "\n{}") :trailing-data]]]
      (let [manifest
            (write! (.resolve (temp-dir) (str label "-corpus.edn")) contents)
            error
            (try
              (corpus/validate-manifest! root manifest)
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :invalid-preflight-corpus-manifest
               (:kind (ex-data error)))
            label)
        (is (= reason (:reason (ex-data error))) label)))))

(deftest package-runner-declares-timeout-crash-leak-and-isolation-controls
  (let [root (paths/workspace-root)
        runner
        (Files/readString
         (paths/resolve-path
          root "validation" "pdfcube-preflight-corpus"
          "PdfCartonPreflightCorpusRunner.cs")
         StandardCharsets/UTF_8)
        gate
        (Files/readString
         (paths/resolve-path
          root "src" "dripsharp" "pdfcube" "preflight_corpus.clj")
         StandardCharsets/UTF_8)]
    (doseq [required ["WaitForExitAsync" "entireProcessTree: true"
                      "\"TIMEOUT\"" "\"CRASH\"" "\"LEAK\""
                      "VerifyLoadedAssemblies"]]
      (is (str/includes? runner required)))
    (doseq [required ["--packages" "--no-cache" "--force-evaluate"
                      "NUGET_PACKAGES" "DOTNET_CLI_HOME"
                      "inspect-consumer-dependencies!"
                      "write-packed-assembly-manifest!"]]
      (is (str/includes? gate required)))
    (is (not (str/includes? runner "ProjectReference")))
    (is (not (str/includes? runner "target/generated")))))
