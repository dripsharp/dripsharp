(ns dripsharp.pkl.public-api-contract
  "Executable upstream-to-.NET public surface and behavior contract.

  The upstream extractor is intentionally independent of DripSharp's Spoon
  model. The package probe reads built assemblies through reflection. Policy
  joins those two inventories without redefining product scope: implementation
  internals remain behavior work, and only the user-approved exclusions may be
  classified as excluded shipped surface."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files Path]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtConstructor CtElement CtEnumValue CtField
            CtMethod CtRecordComponent CtType]))

(def pinned-upstream-revision
  (baseline/upstream-revision :pkl))

(def upstream-columns
  ["source-module" "package" "owner" "kind" "name" "parameter-count"
   "signature" "generic-constraints" "nullability" "exceptions" "delegate"
   "lifecycle" "upstream-provenance" "javadoc" "invocation-evidence"])

(def package-columns
  ["assembly" "owner" "kind" "name" "parameter-count" "signature"
   "generic-constraints" "nullability" "exceptions" "delegate" "lifecycle"])

(def policy-columns
  ["rule-id" "priority" "source-module" "package-regex" "owner-regex"
   "kind-regex" "name-regex" "parameter-count-regex" "classification" "area" "behavior-family"
   "policy-evidence" "dotnet-adaptation" "exclusion-evidence"])

(def behavior-columns
  ["case-id" "area" "behavior-family" "comparison" "upstream-source"
   "upstream-needle" "dotnet-invocation" "dotnet-needle" "expected-exceptions"
   "lifecycle" "normalized-expectation"])

(def failing-control-columns
  ["control-id" "expected-owner" "expected-kind" "expected-name"
   "parameter-count" "current-owner" "current-kind" "current-name" "failure"
   "desired-adaptation" "upstream-provenance" "invocation-evidence"])

(def behavior-result-columns ["case-id" "status" "observation"])

(def body-audit-columns ["assembly" "owner" "member" "signature" "finding"])

(def body-review-policy-columns
  ["rule-id" "priority" "owner-regex" "member-regex" "finding-regex"
   "disposition" "evidence" "rationale"])

(def ^:private classifications
  #{"product-api" "adaptation-source" "public-implementation-internal"
    "approved-exclusion"})

(def ^:private approved-exclusion-evidence
  #{"doc/targets/pkl/product-goal.md#User-Approved Product Exclusions:YAML support."
    "doc/targets/pkl/product-goal.md#User-Approved Product Exclusions:MessagePack and Pkl binary transport support."
    "doc/targets/pkl/product-goal.md#User-Approved Product Exclusions:CLI product support, except a small validation harness when useful."
    "doc/targets/pkl/product-goal.md#User-Approved Product Exclusions:Build, benchmark, and test infrastructure as shipped product surface."})

(def ^:private native-config-owners
  #{"Pkl.Core.PklNameAttribute"
    "Pkl.Core.PklQualifiedNameAttribute"
    "Pkl.Core.PklIgnoreAttribute"
    "Pkl.Core.PklRequiredAttribute"
    "Pkl.Core.PklTypeAliasAttribute"
    "Pkl.Core.PklBindException"
    "Pkl.Core.IPklGeneratedLoader`1"
    "Pkl.Core.ConfigBinderOptions"
    "Pkl.Core.ConfigBinder"
    "Pkl.Core.Config"
    "Pkl.Core.NoSuchChildException"
    "Pkl.Core.ConfigEvaluatorBuilder"
    "Pkl.Core.ConfigEvaluator"})

(def ^:private native-codegen-owners
  #{"Pkl.Core.CSharpGenerationException"
    "Pkl.Core.CSharpGeneratorOptions"
    "Pkl.Core.CSharpGenerator"})

(def native-owner-decisions
  {"Pkl.Core.IPklPair"
   {:behavior-family "value.pair"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/Pair.java"
    :dotnet-adaptation "Pair cross-generic equality is internal implementation behavior behind the public Pair API; the helper interface is not product API or an exclusion."}
   "Pkl.Core.PklAnsiBuilder"
   {:behavior-family "diagnostics.rendering"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/AnsiStringBuilder.java"
    :dotnet-adaptation "ANSI state is internal diagnostic-rendering behavior used by the runtime; the helper is not product API or an exclusion."}
   "Pkl.Core.PklAnsiCode"
   {:behavior-family "diagnostics.rendering"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/AnsiStringBuilder.java"
    :dotnet-adaptation "ANSI codes are internal diagnostic-rendering state; the helper enum is not product API or an exclusion."}
   "Pkl.Core.PklClassInfos"
   {:behavior-family "schema.class-info"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/PClassInfo.java"
    :dotnet-adaptation "Exact class checks remain internal behavior behind the authoritative public PClassInfo API; the helper is not product API or an exclusion."}
   "Pkl.Core.PklCommandArgument"
   {:behavior-family "cli.options"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliCommandRunner.kt"
    :dotnet-adaptation "The only non-test upstream consumer is the excluded CLI command runner; no native command facade is shipped."}
   "Pkl.Core.PklCommandBooleanFlag"
   {:behavior-family "cli.options"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliCommandRunner.kt"
    :dotnet-adaptation "The only non-test upstream consumer is the excluded CLI command runner; no native command facade is shipped."}
   "Pkl.Core.PklCommandCountedFlag"
   {:behavior-family "cli.options"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliCommandRunner.kt"
    :dotnet-adaptation "The only non-test upstream consumer is the excluded CLI command runner; no native command facade is shipped."}
   "Pkl.Core.PklCommandFlag"
   {:behavior-family "cli.options"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliCommandRunner.kt"
    :dotnet-adaptation "The only non-test upstream consumer is the excluded CLI command runner; no native command facade is shipped."}
   "Pkl.Core.PklCommandOption"
   {:behavior-family "cli.options"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliCommandRunner.kt"
    :dotnet-adaptation "The only non-test upstream consumer is the excluded CLI command runner; no native command facade is shipped."}
   "Pkl.Core.PklCommandOptionException"
   {:behavior-family "cli.options"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliCommandRunner.kt"
    :dotnet-adaptation "The only non-test upstream consumer is the excluded CLI command runner; no native command facade is shipped."}
   "Pkl.Core.PklCommandSpec"
   {:behavior-family "cli.options"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliCommandRunner.kt"
    :dotnet-adaptation "The only non-test upstream consumer is the excluded CLI command runner; no native command facade is shipped."}
   "Pkl.Core.PklExceptions"
   {:behavior-family "diagnostics.exceptions"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/Exceptions.java"
    :dotnet-adaptation "Root-cause traversal remains internal diagnostic behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklGlob"
   {:behavior-family "loading.glob"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/GlobResolver.java"
    :dotnet-adaptation "Glob compilation remains internal behavior behind globbed import and read evaluation; the helper is not product API or an exclusion."}
   "Pkl.Core.PklHttp"
   {:behavior-family "loading.http"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/HttpUtils.java"
    :dotnet-adaptation "HTTP URI and response checks remain internal loading behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklImportGraphs"
   {:behavior-family "analysis.import-graph"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/ImportGraphUtils.java"
    :dotnet-adaptation "Import-cycle discovery remains internal behavior behind Analyzer and ImportGraph; the helper is not product API or an exclusion."}
   "Pkl.Core.PklParserUtilities"
   {:behavior-family "parser.imports-reads"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/ast/builder/ImportsAndReadsParser.java"
    :dotnet-adaptation "Import/read discovery remains internal parser and analysis behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklPath"
   {:behavior-family "loading.path-resolution"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/PathResolvers.java"
    :dotnet-adaptation "Platform path resolution remains internal loading behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklStrings"
   {:behavior-family "runtime.strings"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/StringUtils.java"
    :dotnet-adaptation "Code-point offset conversion remains internal parser/runtime behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklTextEscaper"
   {:behavior-family "rendering.text-escaping"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/ArrayCharEscaper.java"
    :dotnet-adaptation "Character escaping remains internal renderer behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklTextEscaper$Builder"
   {:behavior-family "rendering.text-escaping"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/ArrayCharEscaper.java"
    :dotnet-adaptation "Character-escape construction remains internal renderer behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklUris"
   {:behavior-family "loading.uri"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/util/IoUtils.java"
    :dotnet-adaptation "URI normalization and resolution remain internal loading behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklValuePathPart"
   {:behavior-family "binding.value-path"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/stdlib/PathSpecParser.java"
    :dotnet-adaptation "Value-path matching remains internal conversion behavior; the helper record is not product API or an exclusion."}
   "Pkl.Core.PklValuePathPartKind"
   {:behavior-family "binding.value-path"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/stdlib/PathSpecParser.java"
    :dotnet-adaptation "Value-path matching remains internal conversion behavior; the helper enum is not product API or an exclusion."}
   "Pkl.Core.PklValuePaths"
   {:behavior-family "binding.value-path"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/stdlib/PathConverterSupport.java"
    :dotnet-adaptation "Value-path parsing and matching remain internal conversion behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklValueRenderer"
   {:behavior-family "runtime.value-rendering"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/runtime/VmValueRenderer.java"
    :dotnet-adaptation "VM value rendering remains internal runtime and diagnostic behavior; the helper is not product API or an exclusion."}
   "Pkl.Core.PklTestReporter"
   {:behavior-family "cli.test-reporting"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliTestRunner.kt"
    :dotnet-adaptation "Upstream report formatting is consumed by excluded CLI and Gradle products; no native reporter facade is shipped."}
   "Pkl.Core.PklTestReporters"
   {:behavior-family "cli.test-reporting"
    :upstream-provenance "research/pkl/pkl-cli/src/main/kotlin/org/pkl/cli/CliTestRunner.kt"
    :dotnet-adaptation "Upstream report formatting is consumed by excluded CLI and Gradle products; no native reporter facade is shipped."}
   "Pkl.Core.Module.AssemblyModuleKeyFactory"
   {:behavior-family "loading.assembly"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/module/ModuleKeyFactories.java"
    :dotnet-adaptation "The concrete .NET assembly factory stays internal behind ModuleKeyFactories.CreateAssembly and its independently consumed ModuleKeyFactory contract."}
   "Pkl.Core.Resource.EmbeddedResourceReader"
   {:behavior-family "loading.embedded-resource"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/resource/ResourceReaders.java"
    :dotnet-adaptation "The concrete embedded reader stays internal behind ResourceReaders.CreateEmbeddedResources and its independently consumed ResourceReader contract."}
   "Pkl.Core.StackFrameTransformerExtensions"
   {:classification "product-api-native"
    :area "core"
    :behavior-family "diagnostics.stack-frames"
    :upstream-provenance "research/pkl/pkl-core/src/main/java/org/pkl/core/StackFrameTransformers.java"
    :dotnet-adaptation "The idiomatic delegate extension preserves upstream StackFrameTransformer.andThen and is source-mapped to that public embedding API member."}})

(def ^:private forbidden-package-metadata
  #"(?i)(?:Pkl[.]Core[.](?:Runtime|Ast|Stdlib|Util|Messaging)(?:[$.<]|$)|DripSharp[.]Runtime|SnakeYaml|MessagePack|Org[.]Msgpack|System[.]Collections[.]Generic[.]I(?:List|Dictionary|Set)<|System[.]SByte(?:\[\])?|\bJava[A-Z][A-Za-z0-9_]*\b|\b(?:ToJavaDuration|GetJavaClass)\b)")

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-public-api-contract)))))

(defn- contract-root
  [workspace]
  (paths/resolve-path (paths/absolute workspace) "validation"
                      "public-api-contract"))

(defn contract-paths
  [workspace]
  (let [root (contract-root workspace)]
    {:root root
     :upstream (paths/resolve-path root "UpstreamSurface.tsv")
     :package (paths/resolve-path root "PackageSurface.tsv")
     :policy (paths/resolve-path root "SurfacePolicy.tsv")
     :behavior (paths/resolve-path root "BehaviorContract.tsv")
     :behavior-evidence (paths/resolve-path root "UpstreamBehavior.tsv")
     :controls (paths/resolve-path root "FailingControls.tsv")
     :body-candidates (paths/resolve-path root "BodyCandidates.tsv")
     :body-review-policy (paths/resolve-path root "BodyReviewPolicy.tsv")
     :upstream-extractor (paths/resolve-path root "PublicApiUpstreamExtractor.java")
     :package-probe (paths/resolve-path root "PublicApiPackageProbe.csproj")
     :contract-compiler
     (paths/resolve-path workspace "validation"
                         "public-contract-compiler" "PublicContractCompiler.csproj")}))

(defn read-tsv
  [file expected-columns]
  (let [file (paths/path file)]
    (when-not (paths/regular-file? file)
      (fail! "Public API contract fixture is missing"
             {:kind :missing-public-api-fixture :file (str file)}))
    (let [lines (str/split-lines (Files/readString file StandardCharsets/UTF_8))
          content (remove #(or (str/blank? %) (str/starts-with? % "#")) lines)
          columns (some-> (first content) (str/split #"\t" -1) vec)]
      (when-not (= expected-columns columns)
        (fail! "Public API contract fixture has the wrong columns"
               {:kind :public-api-columns :file (str file)
                :expected expected-columns :actual columns}))
      {:file file
       :comments (vec (filter #(str/starts-with? % "#") lines))
       :columns columns
       :rows
       (mapv
        (fn [line-number line]
          (let [values (str/split line #"\t" -1)]
            (when-not (= (count columns) (count values))
              (fail! "Public API TSV row has the wrong field count"
                     {:kind :public-api-field-count :file (str file)
                      :line line-number :expected (count columns)
                      :actual (count values)}))
            (assoc (zipmap (map keyword columns) values) :fixture-line line-number)))
        (iterate inc 2)
        (rest content))})))

(def ^:private write-text! util/write-text!)

(defn- command-output
  [request]
  (:output (process/run! request)))

(defn- temp-directory
  [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn extract-upstream!
  "Runs the standalone JDK-tree extractor and writes its deterministic TSV."
  [workspace output]
  (let [workspace (paths/absolute workspace)
        {:keys [upstream-extractor]} (contract-paths workspace)
        classes (temp-directory "dripsharp-public-api-upstream")]
    (command-output {:command ["javac" "-d" classes upstream-extractor]
                     :directory workspace})
    (let [text (process/without-java-tool-options-banner
                (command-output
                 {:command ["java" "-Xmx4g" "-cp" classes
                            "PublicApiUpstreamExtractor" "--workspace" workspace]
                  :directory workspace}))]
      (when-not (str/starts-with? text "# DRIPSHARP_UPSTREAM_PUBLIC_API_V1\n")
        (fail! "Standalone upstream API extractor returned an invalid stream"
               {:kind :invalid-upstream-api-extraction
                :prefix (subs text 0 (min 120 (count text)))}))
      (write-text! (paths/path output) text))))

(defn reflect-packages!
  "Reflects public metadata from one or more package assemblies."
  [workspace assemblies output]
  (let [workspace (paths/absolute workspace)
        {:keys [package-probe]} (contract-paths workspace)
        assemblies (mapv #(paths/absolute %) assemblies)]
    (when-not (seq assemblies)
      (fail! "Package reflection requires at least one assembly"
             {:kind :missing-package-assemblies}))
    (doseq [assembly assemblies]
      (when-not (paths/regular-file? assembly)
        (fail! "Package reflection assembly is missing"
               {:kind :missing-package-assembly :assembly (str assembly)})))
    (command-output {:command ["dotnet" "build" package-probe "--configuration"
                               "Release" "--nologo" "--no-incremental"]
                     :directory workspace})
    (let [text (command-output
                {:command (into ["dotnet" "run" "--project" package-probe
                                 "--configuration" "Release" "--no-build" "--"]
                                assemblies)
                 :directory workspace})]
      (when-not (str/starts-with? text "# DRIPSHARP_DOTNET_PUBLIC_API_V1\n")
        (fail! "Package reflection probe returned an invalid stream"
               {:kind :invalid-package-api-reflection
                :prefix (subs text 0 (min 120 (count text)))}))
      (write-text! (paths/path output) text))))

(def ^:private sha256 util/sha256-text)

(def ^:private executed-behavior-probes
  #{"targets/pkl/validation/probe/PackageProbe.cs"
    "targets/pkl/validation/probe/CorePackageConsumer.cs"
    "validation/loading-contract/LoadingContractDotNetProbe.cs"
    "validation/schema-codegen/GeneratedConsumer.cs"
    "validation/schema-codegen/SchemaGeneratorProbe.cs"})

(defn extract-behavior-evidence!
  "Extracts the exact upstream line and hash backing every behavior row."
  [workspace behavior-file output]
  (let [workspace (paths/absolute workspace)
        cases (:rows (read-tsv behavior-file behavior-columns))
        rows
        (mapv
         (fn [{:keys [case-id upstream-source upstream-needle dotnet-invocation
                      dotnet-needle]}]
           (let [source (paths/resolve-path workspace upstream-source)
                 invocation (paths/resolve-path workspace dotnet-invocation)]
             (when-not (paths/regular-file? source)
               (fail! "Behavior contract upstream source is missing"
                      {:kind :missing-behavior-source :case-id case-id
                       :source upstream-source}))
             (when-not (paths/regular-file? invocation)
               (fail! "Behavior contract .NET invocation evidence is missing"
                      {:kind :missing-dotnet-invocation :case-id case-id
                       :source dotnet-invocation}))
             (when-not (executed-behavior-probes dotnet-invocation)
               (fail! "Behavior contract points at a source that the isolated proof does not execute"
                      {:kind :unexecuted-dotnet-behavior-probe :case-id case-id
                       :source dotnet-invocation}))
             (when-not (str/includes?
                        (Files/readString invocation StandardCharsets/UTF_8)
                        dotnet-needle)
               (fail! "Behavior contract .NET invocation does not call its promised API family"
                      {:kind :missing-dotnet-behavior-call :case-id case-id
                       :source dotnet-invocation :needle dotnet-needle}))
             (let [matches (keep-indexed
                            (fn [index line]
                              (when (str/includes? line upstream-needle)
                                [(inc index) (str/trim line)]))
                            (str/split-lines
                             (Files/readString source StandardCharsets/UTF_8)))]
               (when-not (seq matches)
                 (fail! "Behavior contract needle is absent from its upstream source"
                        {:kind :missing-behavior-needle :case-id case-id
                         :source upstream-source :needle upstream-needle}))
               (let [[line text] (first matches)]
                 {:case-id case-id
                  :upstream-provenance (str upstream-source ":" line)
                  :line-sha256 (sha256 text)
                  :dotnet-invocation dotnet-invocation}))))
         cases)
        header ["case-id" "upstream-provenance" "line-sha256" "dotnet-invocation"]
        text (str "# DRIPSHARP_UPSTREAM_API_BEHAVIOR_V1\n"
                  (str/join "\t" header) "\n"
                  (str/join "\n" (map #(str/join "\t" (map % (map keyword header))) rows))
                  "\n")]
    (write-text! (paths/path output) text)))

(defn- compile-policy
  [row]
  (let [priority (parse-long (:priority row))]
    (when-not priority
      (fail! "Surface policy priority is not an integer"
             {:kind :invalid-surface-policy-priority :rule-id (:rule-id row)
              :priority (:priority row)}))
    (assoc row
           :priority-value priority
           :source-pattern (re-pattern (:source-module row))
           :package-pattern (re-pattern (:package-regex row))
           :owner-pattern (re-pattern (:owner-regex row))
           :kind-pattern (re-pattern (:kind-regex row))
           :name-pattern (re-pattern (:name-regex row))
           :parameter-count-pattern (re-pattern (:parameter-count-regex row)))))

(defn read-policy
  [file]
  (let [rows (:rows (read-tsv file policy-columns))
        ids (map :rule-id rows)]
    (when-not (= (count ids) (count (distinct ids)))
      (fail! "Surface policy contains duplicate rule IDs"
             {:kind :duplicate-surface-policy-rule}))
    (doseq [row rows]
      (when-not (classifications (:classification row))
        (fail! "Surface policy has an unknown classification"
               {:kind :unknown-surface-classification :rule-id (:rule-id row)
                :classification (:classification row)}))
      (if (= "approved-exclusion" (:classification row))
        (when-not (approved-exclusion-evidence (:exclusion-evidence row))
          (fail! "Surface policy uses an unapproved exclusion"
                 {:kind :unapproved-public-api-exclusion :rule-id (:rule-id row)
                  :evidence (:exclusion-evidence row)}))
        (when-not (= "-" (:exclusion-evidence row))
          (fail! "A non-excluded surface policy row carries exclusion evidence"
                 {:kind :implicit-public-api-exclusion :rule-id (:rule-id row)}))))
    (mapv compile-policy (sort-by (juxt #(parse-long (:priority %)) :rule-id) rows))))

(defn- policy-match?
  [policy row]
  (and (re-matches (:source-pattern policy) (:source-module row))
       (re-matches (:package-pattern policy) (:package row))
       (re-matches (:owner-pattern policy) (:owner row))
       (re-matches (:kind-pattern policy) (:kind row))
       (re-matches (:name-pattern policy) (:name row))
       (re-matches (:parameter-count-pattern policy) (:parameter-count row))))

(defn classify-upstream-row
  [policies row]
  (let [matches (filter #(policy-match? % row) policies)]
    (when-not (seq matches)
      (fail! "A public upstream declaration was silently skipped by policy"
             {:kind :unclassified-upstream-public-api
              :owner (:owner row) :member (:name row) :declaration-kind (:kind row)
              :provenance (:upstream-provenance row)}))
    (let [priority (:priority-value (first matches))
          winners (filter #(= priority (:priority-value %)) matches)]
      (when-not (= 1 (count winners))
        (fail! "A public upstream declaration has ambiguous first-match policy"
               {:kind :ambiguous-upstream-public-api-policy
                :owner (:owner row) :member (:name row)
                :rules (mapv :rule-id winners)}))
      (first winners))))

(defn- pascal
  [value]
  (if (or (str/blank? value)
          (and (> (count value) 1)
               (Character/isUpperCase (.charAt ^String value 0))
               (Character/isUpperCase (.charAt ^String value 1))))
    value
    (str (Character/toUpperCase (.charAt ^String value 0)) (subs value 1))))

(defn- dotnet-owner
  [row]
  (let [segments (str/split (:owner row) #"[.]")]
    (when (and (<= 3 (count segments)) (= ["org" "pkl"] (subvec segments 0 2)))
      (str/join "." (concat ["Pkl"] (map pascal (subvec segments 2)))))))

(defn- target-shape
  [row policy]
  (when (= "product-api" (:classification policy))
    (let [kind (:kind row)
          name (:name row)
          stack-frame-composition?
          (and (= "org.pkl.core.StackFrameTransformer" (:owner row))
               (= "method" kind)
               (= "andThen" name))]
      {:dotnet-assembly (if (= "pkl-parser" (:source-module row)) "Pkl.Parser" "Pkl.Core")
       :dotnet-owner (if stack-frame-composition?
                       "Pkl.Core.StackFrameTransformerExtensions"
                       (dotnet-owner row))
       :dotnet-kind (case kind
                      "property" "property"
                      "constructor" "constructor"
                      "enum-value" "enum-value"
                      "field" "field"
                      "type" "type"
                      "method")
       :dotnet-name (case kind
                      "constructor" ".ctor"
                      "type" name
                      "enum-value" name
                      (pascal name))
       :dotnet-parameter-count (if stack-frame-composition?
                                 "2"
                                 (:parameter-count row))})))

(defn contract-rows
  [upstream-rows policies]
  (mapv
   (fn [row]
     (let [policy (classify-upstream-row policies row)]
       (merge row
              (select-keys policy [:rule-id :classification :area :behavior-family
                                   :policy-evidence :dotnet-adaptation
                                   :exclusion-evidence])
              (or (target-shape row policy)
                  {:dotnet-assembly "-" :dotnet-owner "-" :dotnet-kind "-"
                   :dotnet-name "-" :dotnet-parameter-count "-"}))))
   upstream-rows))

(declare implicit-record-constructor?)

(defn generation-surface!
  "Builds the deterministic generation selection for one translated source
  module from the executable contract. Product types, rather than a maintained
  source list, become :public-api closure seeds. Every required member owner
  must itself have one product type row."
  [workspace {:keys [source-module] :as specification}]
  (when-not (and (= #{:source-module} (set (keys specification)))
                 (contains? #{"pkl-parser" "pkl-core"} source-module))
    (fail! "Invalid generation public API contract specification"
           {:kind :invalid-generation-public-api-contract
            :specification specification}))
  (let [{:keys [upstream policy]} (contract-paths workspace)
        upstream-rows (:rows (read-tsv upstream upstream-columns))
        policies (read-policy policy)
        classified (contract-rows upstream-rows policies)
        module-rows (->> classified
                         (filter #(= source-module (:source-module %)))
                         vec)
        required-rows (->> module-rows
                           (filter #(= "product-api" (:classification %)))
                           vec)
        expected-required
        (:public-contract-rows
         (baseline/profile-by-source-module workspace :pkl source-module))
        type-rows (->> required-rows
                       (filter #(= "type" (:kind %)))
                       (sort-by :owner)
                       vec)
        duplicate-types (->> type-rows (group-by :owner) vals
                             (filter #(< 1 (count %)))
                             (mapv #(mapv :upstream-provenance %)))
        type-owners (set (map :owner type-rows))
        missing-owner-types (->> required-rows
                                 (remove #(type-owners (:owner %)))
                                 (map :owner) distinct sort vec)]
    (when-not (seq module-rows)
      (fail! "The public API contract has no rows for a generation source module"
             {:kind :missing-generation-source-module :source-module source-module}))
    (when-not (= expected-required (count required-rows))
      (fail! "The public API row count differs from the reviewed target baseline"
             {:kind :public-api-baseline-count-drift
              :source-module source-module
              :expected expected-required
              :actual (count required-rows)}))
    (when (seq duplicate-types)
      (fail! "The public API contract has duplicate product type rows"
             {:kind :duplicate-generation-product-types :rows duplicate-types}))
    (when (seq missing-owner-types)
      (fail! "A required product member owner has no required product type row"
             {:kind :missing-generation-product-type
              :source-module source-module :owners missing-owner-types}))
    (let [member-selectors
          (->> required-rows
               (remove #(or (= "type" (:kind %))
                            (implicit-record-constructor? %)))
               (group-by :owner)
               (reduce-kv
                (fn [result owner rows]
                  (assoc result owner
                         (set (map (juxt :kind :name :parameter-count) rows))))
                {}))]
      {:source-module source-module
       :classified-rows module-rows
       :required-rows required-rows
       :seeds (mapv (fn [row]
                      {:key (str "type:" (:owner row))
                       :expand :public-api
                       :members (get member-selectors (:owner row) #{})})
                    type-rows)})))

(defn observe-public-contract-counts!
  "Extracts and classifies the live upstream public API without consulting the
  reviewed count expectations. This is intentionally reserved for the
  approval-gated re-baseline preview."
  [workspace output]
  (let [workspace (paths/absolute workspace)
        output (paths/absolute output)
        {:keys [policy]} (contract-paths workspace)
        _ (extract-upstream! workspace output)
        upstream-rows (:rows (read-tsv output upstream-columns))
        classified (contract-rows upstream-rows (read-policy policy))]
    (into
     (sorted-map)
     (for [source-module ["pkl-parser" "pkl-core"]]
       [source-module
        (count
         (filter
          #(and (= source-module (:source-module %))
                (= "product-api" (:classification %)))
          classified))]))))

(defn- parent-type
  [^CtElement element]
  (loop [current element]
    (when (and current (.isParentInitialized current))
      (let [parent (.getParent current)]
        (if (instance? CtType parent)
          parent
          (recur parent))))))

(defn- live-surface-shape
  [^CtElement declaration]
  (let [location (spoon/source-location declaration)
        ^CtType owner (if (instance? CtType declaration)
                        declaration
                        (parent-type declaration))]
    (when (and location owner)
      (cond
        (instance? CtType declaration)
        {:file (:file location) :line (:line location)
         :owner (.getQualifiedName owner) :kind "type"
         :name (.getSimpleName ^CtType declaration) :parameter-count "0"}

        (instance? CtRecordComponent declaration)
        {:file (:file location) :line (:line location)
         :owner (.getQualifiedName owner) :kind "property"
         :name (.getSimpleName ^CtRecordComponent declaration) :parameter-count "0"}

        (instance? CtConstructor declaration)
        {:file (:file location) :line (:line location)
         :owner (.getQualifiedName owner) :kind "constructor" :name ".ctor"
         :parameter-count (str (count (.getParameters ^CtConstructor declaration)))}

        (instance? CtMethod declaration)
        {:file (:file location) :line (:line location)
         :owner (.getQualifiedName owner) :kind "method"
         :name (.getSimpleName ^CtMethod declaration)
         :parameter-count (str (count (.getParameters ^CtMethod declaration)))}

        (instance? CtEnumValue declaration)
        {:file (:file location) :line (:line location)
         :owner (.getQualifiedName owner) :kind "enum-value"
         :name (.getSimpleName ^CtEnumValue declaration) :parameter-count "0"}

        (instance? CtField declaration)
        {:file (:file location) :line (:line location)
         :owner (.getQualifiedName owner) :kind "field"
         :name (.getSimpleName ^CtField declaration) :parameter-count "0"}))))

(defn- broad-shape-key
  [shape]
  (mapv shape [:file :owner :kind :name :parameter-count]))

(defn- contract-row-shape
  [workspace row]
  (let [[_ file line] (re-matches #"^(.*):(\d+)$" (:upstream-provenance row))]
    (when-not (and file line)
      (fail! "A generation contract row has no exact source line"
             {:kind :missing-generation-contract-source-line
              :owner (:owner row) :member (:name row)
              :provenance (:upstream-provenance row)}))
    {:file (str (paths/absolute (paths/resolve-path workspace file)))
     :line (parse-long line)
     :owner (:owner row)
     :kind (:kind row)
     :name (:name row)
     :parameter-count (:parameter-count row)}))

(defn- implicit-record-constructor?
  [row]
  (and (= "constructor" (:kind row))
       (= "implicit-record-canonical" (:javadoc row))))

(defn- selected-extra-classification
  [^CtElement declaration]
  (let [file (:file (spoon/source-location declaration))]
    (cond
      (and file (str/includes? (str/replace file "\\" "/") "/generated/"))
      :generated-implementation-declaration

      (.isImplicit declaration)
      :compiler-implicit-implementation-declaration)))

(defn validate-selected-surface!
  "Joins every required independently extracted row to exactly one selected
  live Spoon declaration. It also rejects any selected public declaration that
  has no policy-classified contract row. Implicit record canonical constructors
  are represented by their selected record declaration and components."
  [workspace surface resolved-model]
  (when-not (and (:declarations resolved-model)
                 (:public-api-declarations resolved-model))
    (fail! "Contract-backed generation requires a selected Spoon closure"
           {:kind :public-api-selection-requires-closure
            :source-module (:source-module surface)}))
  (let [selected (:public-api-declarations resolved-model)
        selected-by-shape
        (->> selected
             vals
             (map (fn [{:keys [declaration] :as entry}]
                    [(some-> declaration live-surface-shape broad-shape-key) entry]))
             (remove (comp nil? first))
             (group-by first))
        classified-shapes
        (->> (:classified-rows surface)
             (map #(broad-shape-key (contract-row-shape workspace %)))
             set)
        extras (->> selected-by-shape
                    (remove #(classified-shapes (key %)))
                    (mapcat (fn [[shape entries]]
                              (map (fn [[_ {:keys [declaration]}]]
                                     {:shape shape
                                      :classification
                                      (selected-extra-classification declaration)
                                      :declaration-key (spoon/declaration-key declaration)
                                      :location (spoon/source-location declaration)})
                                   entries)))
                    vec)
        unclassified-extras (->> extras (remove :classification) (take 50) vec)
        evidence
        (mapv
         (fn [row]
           (let [implicit? (implicit-record-constructor? row)
                 row-shape (contract-row-shape workspace row)
                 constructor-matches (when implicit?
                                       (mapv second
                                             (get selected-by-shape
                                                  (broad-shape-key row-shape))))
                 lookup-shape (if (and implicit? (empty? constructor-matches))
                                (assoc row-shape :kind "type"
                                       :name (last (str/split (:owner row) #"[$.]"))
                                       :parameter-count "0")
                                row-shape)
                 broad-matches (or (seq constructor-matches)
                                   (mapv second
                                         (get selected-by-shape
                                              (broad-shape-key lookup-shape))))
                 distances (when (< 1 (count broad-matches))
                             (group-by #(Math/abs
                                         (long (- (:line row-shape)
                                                  (get-in % [:location :line]))))
                                       broad-matches))
                 matches (if distances
                           (get distances (reduce min (keys distances)))
                           broad-matches)]
             (when-not (= 1 (count matches))
               (fail! "A required contract row does not map to exactly one selected live declaration"
                      {:kind (if (seq matches)
                               :ambiguous-selected-public-api-member
                               :absent-selected-public-api-member)
                       :source-module (:source-module surface)
                       :owner (:owner row) :member (:name row)
                       :declaration-kind (:kind row)
                       :signature (:signature row)
                       :provenance (:upstream-provenance row)
                       :match-count (count matches)}))
             (let [{:keys [declaration expansion]} (first matches)
                   expected-expansion (if (instance? CtType declaration)
                                        :public-api :body)]
               (when (< ({:shell 0 :body 1 :public-api 2} expansion -1)
                        ({:shell 0 :body 1 :public-api 2} expected-expansion))
                 (fail! "A required public API declaration was selected as a shell"
                        {:kind :shell-only-selected-public-api-member
                         :owner (:owner row) :member (:name row)
                         :signature (:signature row) :expansion expansion
                         :expected-expansion expected-expansion}))
               {:row (select-keys row (map keyword upstream-columns))
                :classification (:classification row)
                :declaration-key (spoon/declaration-key declaration)
                :generated-declaration-key (if implicit?
                                             (str "type:" (:owner row))
                                             (spoon/declaration-key declaration))
                :expansion expansion
                :representation (if implicit? :implicit-record-canonical :live-declaration)})))
         (:required-rows surface))]
    (when (seq unclassified-extras)
      (fail! "Selected public Spoon declarations have no contract classification"
             {:kind :extra-unclassified-selected-public-api
              :source-module (:source-module surface)
              :declarations unclassified-extras}))
    (assoc surface :selection-evidence evidence :classified-extras extras)))

(defn validate-generated-surface!
  "Compares selected contract identities with the declaration metadata emitted
  from those exact Spoon objects and rejects coverage counters that make the
  comparison non-authoritative."
  [surface emission]
  (let [declarations (:declarations emission)
        by-java-key (group-by :java-key (filter :java-key declarations))
        ordinary (remove #(= :implicit-record-canonical (:representation %))
                         (:selection-evidence surface))
        collapsed (->> ordinary (group-by :declaration-key)
                       (filter (fn [[_ rows]] (< 1 (count rows))))
                       (mapv (fn [[key rows]]
                               {:declaration-key key
                                :signatures (mapv #(get-in % [:row :signature]) rows)})))
        generated
        (mapv (fn [evidence]
                (let [generated-key (or (:generated-declaration-key evidence)
                                        (:declaration-key evidence))
                      matches (get by-java-key generated-key)]
                  (when-not (= 1 (count matches))
                    (fail! "A selected contract declaration does not map to exactly one generated declaration"
                           {:kind (if (seq matches)
                                    :ambiguous-generated-public-api-member
                                    :absent-generated-public-api-member)
                            :declaration-key generated-key
                            :signature (get-in evidence [:row :signature])
                            :match-count (count matches)}))
                  (let [declaration (first matches)
                        generated (select-keys declaration
                                               [:id :kind :owner :name :signature
                                                :destination :source])]
                    (if (= :implicit-record-canonical (:representation evidence))
                      (assoc evidence :generated
                             (-> generated
                                 (assoc :representation :record-primary-constructor)
                                 (assoc :destination
                                        (assoc (:destination declaration)
                                               :kind "constructor"
                                               :name ".ctor"
                                               :parameter-count
                                               (get-in evidence [:row :parameter-count])))))
                      (assoc evidence :generated generated)))))
              (:selection-evidence surface))
        summary (:summary emission)
        coverage (:executable-coverage summary)
        counters {:missing-source-mappings (:missing-source-mappings summary)
                  :hard-failures (:hard-failures summary)
                  :collisions (:collisions summary)
                  :skipped-source-units (:skipped-source-units summary)
                  :unsupported-elements (:unsupported-elements coverage)
                  :missing-mappings (:missing-mappings coverage)
                  :blocked (:blocked coverage)
                  :fallback (:fallback coverage)}
        nonzero (into (sorted-map) (filter (comp pos? val)) counters)]
    (when (seq collapsed)
      (fail! "Distinct contract signatures collapsed onto one generated declaration"
             {:kind :signature-collapsed-generated-public-api :members collapsed}))
    (when (seq nonzero)
      (fail! "Generated public metadata is not authoritative while generation counters are nonzero"
             {:kind :nonzero-generated-public-api-counters :counters nonzero}))
    {:schema-version 2
     :source-module (:source-module surface)
     :required-rows (count generated)
     :rows generated}))

(defn- normalized-owner
  [owner]
  (str/replace owner #"`[0-9]+" ""))

(defn- package-broad-key
  [row]
  [(normalized-owner (:owner row)) (:kind row) (:name row) (:parameter-count row)])

(defn- target-broad-key
  [row]
  [(normalized-owner (:dotnet-owner row)) (:dotnet-kind row) (:dotnet-name row)
   (:dotnet-parameter-count row)])

(defn classify-package-row
  [target-keys row]
  (let [product-owners (->> target-keys
                            (keep (fn [[owner kind _ _]]
                                    (when (= "type" kind) owner)))
                            set)
        owner (normalized-owner (:owner row))]
    (cond
      (native-config-owners (:owner row))
      {:classification "product-api-native" :area "config-binding"
       :behavior-family "binding.public-api"
       :upstream-provenance "research/pkl/docs/modules/java-binding/pages/pkl-config-java.adoc"
       :dotnet-adaptation "Idiomatic .NET binding API backed by upstream Java/Kotlin consumer behavior."}

      (native-codegen-owners (:owner row))
      {:classification "product-api-native" :area "csharp-generation"
       :behavior-family "codegen.public-api"
       :upstream-provenance "research/pkl/docs/modules/java-binding/pages/codegen.adoc"
       :dotnet-adaptation "Native C# generator API backed by upstream schema/codegen behavior."}

      (native-owner-decisions (:owner row))
      (merge {:classification "public-implementation-internal" :area "implementation"}
             (native-owner-decisions (:owner row)))

      (target-keys (package-broad-key row))
      {:classification "product-api-current" :area (if (= "Pkl.Parser" (:assembly row))
                                                     "parser" "core")
       :behavior-family "translated.public-api"
       :upstream-provenance "joined-by-executable-target-shape"
       :dotnet-adaptation "Exact package metadata is pinned by PackageSurface.tsv."}

      (product-owners owner)
      {:classification "product-api-native" :area (if (= "Pkl.Parser" (:assembly row))
                                                    "parser" "core")
       :behavior-family "translated.native-public-api"
       :upstream-provenance "derived-from-approved-product-type"
       :dotnet-adaptation "CLR-synthesized or idiomatic member on an explicitly selected product type; exact metadata is pinned by PackageSurface.tsv."}

      :else
      {:classification "public-implementation-internal" :area "implementation"
       :behavior-family "implementation.runtime"
       :upstream-provenance "package-reflection"
       :dotnet-adaptation "Not promised as product API; required behavior remains in scope and this is not an exclusion."})))

(defn validate-package-boundary!
  "Rejects consumer metadata that is not part of the explicit product/native
  boundary or that mentions implementation, excluded, mutable, or Java-shaped
  compatibility types."
  [package-rows package-classifications]
  (let [implementation-leaks
        (filterv #(= "public-implementation-internal" (:classification %))
                 package-classifications)
        forbidden-metadata
        (filterv #(re-find forbidden-package-metadata
                           (str (:owner %) " " (:signature %)))
                 package-rows)]
    (when (seq implementation-leaks)
      (fail! "Package metadata exports unapproved implementation types or members"
             {:kind :public-implementation-metadata-leak
              :count (count implementation-leaks)
              :rows (vec (take 30 implementation-leaks))}))
    (when (seq forbidden-metadata)
      (fail! "Package metadata exposes an implementation, excluded, mutable, or Java-shaped signature"
             {:kind :forbidden-public-package-signature
              :count (count forbidden-metadata)
              :rows (vec (take 30 forbidden-metadata))}))
    {:approved (count package-rows)}))

(defn- validate-package-consumer-boundary!
  [workspace]
  (let [source
        (paths/resolve-path workspace "targets" "pkl" "validation" "probe"
                            "CorePackageConsumer.cs")
        text (Files/readString source)]
    (when (re-find #"(?:using Pkl[.]Core[.]Runtime|DripSharp[.]Runtime|\bJava[A-Z][A-Za-z0-9_]*\b)"
                   text)
      (fail! "The package-only consumer imports implementation or Java compatibility APIs"
             {:kind :package-consumer-implementation-api-leak
              :source (str source)}))
    (doseq [contract [": ModuleKeyFactory" ": ResourceReader" ": PklHttpClient"]]
      (when-not (str/includes? text contract)
        (fail! "The package-only consumer does not implement every extension boundary"
               {:kind :missing-package-consumer-extension-contract
                :contract contract :source (str source)})))
    {:source (str source) :extension-contracts 3}))

(defn write-strong-contract-keys!
  "Writes the deterministic package-member key set whose exact signatures must
  be compiled as strongly typed method groups, constructors, properties, and
  fields by the isolated package consumer. All remaining public rows are still
  compiled as type references and verified through exact metadata and hashes."
  [workspace output]
  (let [workspace (paths/absolute workspace)
        {:keys [upstream package policy]} (contract-paths workspace)
        upstream-rows (:rows (read-tsv upstream upstream-columns))
        policies (read-policy policy)
        contract (contract-rows upstream-rows policies)
        target-keys (set (keep #(when (= "product-api" (:classification %))
                                  (target-broad-key %))
                               contract))
        package-rows (:rows (read-tsv package package-columns))
        delegate-owners (->> package-rows
                             (filter #(and (= "type" (:kind %))
                                           (= "delegate" (:delegate %))))
                             (map :owner) set)
        selected
        (filter #(and (contains? #{"product-api-current" "product-api-native"}
                                 (:classification %))
                      (not= "<Clone>$" (:name %))
                      (not (and (= "field" (:kind %))
                                (= "value__" (:name %))))
                      (not (and (delegate-owners (:owner %))
                                (not= "type" (:kind %)))))
                (map #(merge % (classify-package-row target-keys %)) package-rows))
        keys (->> selected
                  (map (fn [row]
                         [(:assembly row) (normalized-owner (:owner row))
                          (:kind row) (:name row) (:parameter-count row)]))
                  distinct sort vec)
        text (str "# DRIPSHARP_STRONGLY_TYPED_PUBLIC_CONTRACT_V1\n"
                  (str/join "\n" (map #(str/join "\t" %) keys)) "\n")]
    (write-text! (paths/path output) text)
    {:rows (count selected) :keys (count keys)}))

(defn- body-audit-key
  [row]
  (mapv row (map keyword body-audit-columns)))

(declare duplicate-values)

(defn compare-body-audit
  "Exact comparator for the reviewed compiled-body candidate snapshot."
  [expected-rows actual-rows]
  (let [duplicates (duplicate-values body-audit-key actual-rows)]
    (if (seq duplicates)
      {:mismatch {:kind :duplicate-public-body-audit-rows
                  :rows (vec (take 20 duplicates))}}
      (let [expected (set (map body-audit-key expected-rows))
            actual (set (map body-audit-key actual-rows))
            missing (sort (set/difference expected actual))
            unexpected (sort (set/difference actual expected))]
        (if (and (empty? missing) (empty? unexpected))
          {:matched (count expected)}
          {:mismatch {:kind :public-body-audit-drift
                      :missing (vec (take 20 missing))
                      :unexpected (vec (take 20 unexpected))
                      :missing-count (count missing)
                      :unexpected-count (count unexpected)}})))))

(def ^:private body-review-dispositions
  #{"source-semantic-default" "runtime-adapter-contract"
    "approved-exclusion-substrate"})

(defn- compile-body-review-policy
  [row]
  (let [priority (parse-long (:priority row))]
    (when-not priority
      (fail! "Public body review policy priority is not an integer"
             {:kind :invalid-public-body-review-priority :rule-id (:rule-id row)}))
    (when-not (body-review-dispositions (:disposition row))
      (fail! "Public body review policy has an unknown disposition"
             {:kind :invalid-public-body-review-disposition
              :rule-id (:rule-id row) :disposition (:disposition row)}))
    (assoc row :priority-value priority
           :owner-pattern (re-pattern (:owner-regex row))
           :member-pattern (re-pattern (:member-regex row))
           :finding-pattern (re-pattern (:finding-regex row)))))

(defn- review-public-body-candidates!
  [workspace candidates policy-file]
  (let [policies (->> (:rows (read-tsv policy-file body-review-policy-columns))
                      (map compile-body-review-policy)
                      (sort-by (juxt :priority-value :rule-id)) vec)
        ids (map :rule-id policies)]
    (when-not (= (count ids) (count (distinct ids)))
      (fail! "Public body review policy has duplicate rule IDs"
             {:kind :duplicate-public-body-review-rule}))
    (doseq [policy policies]
      (let [evidence (paths/resolve-path workspace (:evidence policy))]
        (when-not (paths/regular-file? evidence)
          (fail! "Public body review evidence is missing"
                 {:kind :missing-public-body-review-evidence
                  :rule-id (:rule-id policy) :evidence (:evidence policy)}))))
    (let [reviewed
          (mapv
           (fn [candidate]
             (let [matches (filter #(and (re-matches (:owner-pattern %) (:owner candidate))
                                         (re-matches (:member-pattern %) (:member candidate))
                                         (re-matches (:finding-pattern %) (:finding candidate)))
                                   policies)]
               (when-not (seq matches)
                 (fail! "A compiled public stub candidate has no source-semantic review"
                        {:kind :unreviewed-public-body-candidate
                         :candidate (select-keys candidate
                                                 (map keyword body-audit-columns))}))
               (let [priority (:priority-value (first matches))
                     winners (filter #(= priority (:priority-value %)) matches)]
                 (when-not (= 1 (count winners))
                   (fail! "A compiled public stub candidate has ambiguous review policy"
                          {:kind :ambiguous-public-body-review
                           :candidate (body-audit-key candidate)
                           :rules (mapv :rule-id winners)}))
                 {:candidate candidate :review (first winners)})))
           candidates)
          used (set (map #(get-in % [:review :rule-id]) reviewed))
          unused (->> policies (remove #(used (:rule-id %))) (map :rule-id) sort vec)]
      (when (seq unused)
        (fail! "Public body review policy contains stale rules"
               {:kind :stale-public-body-review-rules :rules unused}))
      reviewed)))

(def ^:private forbidden-public-source-patterns
  [[:translation-error #"#error\s+DRIPSHARP_"]
   [:not-implemented #"\bNotImplementedException\b"]
   [:unsupported-java-placeholder #"\bUnsupportedOperationException\b"]
   [:todo-comment #"(?m)//[^\n]*\b(?:TODO|FIXME|HACK)\b"]
   [:todo-block-comment #"(?s)/\*.*?\b(?:TODO|FIXME|HACK)\b.*?\*/"]])

(defn- csharp-source-files
  [^Path source-root]
  (when-not (paths/directory? source-root)
    (fail! "Generated package source root is missing"
           {:kind :missing-public-source-root :source-root (str source-root)}))
  (with-open [files (Files/walk source-root (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (filter #(str/ends-with? (str %) ".cs"))
         (sort-by str) vec)))

(defn audit-public-surface!
  "Audits every C# source file and every public compiled body in a clean
  dependency-closed generation. Exact candidate drift is rejected, and every
  legitimate default/no-op/unsupported candidate must match a source-semantic
  review rule backed by durable evidence."
  [workspace generation build-configuration]
  (let [workspace (paths/absolute workspace)
        {:keys [body-candidates body-review-policy contract-compiler]}
        (contract-paths workspace)
        main (assoc (:emission generation) :destination (:destination generation))
        emissions (vec (concat (:dependency-emissions generation) [main]))
        mapping-audits
        (mapv (fn [emission]
                {:profile (or (:profile emission)
                              (get-in emission [:destination :package :id]))
                 :source-mappings (get-in emission [:summary :source-mappings] 0)
                 :missing-source-mappings
                 (get-in emission [:summary :missing-source-mappings] 0)
                 :hard-failures (get-in emission [:summary :hard-failures] 0)})
              emissions)
        source-roots (mapv #(paths/resolve-path (:project-root %) "src") emissions)
        source-files (vec (mapcat csharp-source-files source-roots))
        source-findings
        (->> source-files
             (mapcat
              (fn [^Path file]
                (let [source (Files/readString file StandardCharsets/UTF_8)]
                  (keep (fn [[kind pattern]]
                          (when (re-find pattern source)
                            {:file (str file) :kind kind}))
                        forbidden-public-source-patterns))))
             vec)
        packages
        (mapv (fn [{:keys [project-root destination]}]
                (let [assembly (get-in destination [:project :assembly-name])
                      framework (get-in destination [:project :target-framework])]
                  (paths/resolve-path project-root "bin" build-configuration framework
                                      (str assembly ".dll"))))
              emissions)
        actual (.resolve (temp-directory "dripsharp-public-body-audit") "actual.tsv")]
    (when-not (seq source-files)
      (fail! "Whole public source audit found no C# files"
             {:kind :empty-public-source-audit}))
    (when-let [unmapped (first (filter #(or (not (pos? (:source-mappings %)))
                                            (pos? (:missing-source-mappings %))
                                            (pos? (:hard-failures %)))
                                       mapping-audits))]
      (fail! "A clean generated package did not retain complete source mappings"
             (assoc unmapped :kind :incomplete-generated-source-mappings)))
    (when (seq source-findings)
      (fail! "Whole shipped public source contains implementation placeholders"
             {:kind :public-source-placeholders
              :findings (vec (take 50 source-findings))
              :finding-count (count source-findings)}))
    (command-output {:command ["dotnet" "build" contract-compiler
                               "--configuration" "Release" "--nologo"
                               "--no-incremental"]
                     :directory workspace})
    (command-output
     {:command (into ["dotnet" "run" "--project" contract-compiler
                      "--configuration" "Release" "--no-build" "--"
                      "audit" actual]
                     packages)
      :directory workspace})
    (let [expected (:rows (read-tsv body-candidates body-audit-columns))
          actual-rows (:rows (read-tsv actual body-audit-columns))
          comparison (compare-body-audit expected actual-rows)]
      (when-let [mismatch (:mismatch comparison)]
        (fail! "Compiled whole-public-surface body audit drifted"
               mismatch))
      (let [reviewed (review-public-body-candidates!
                      workspace actual-rows body-review-policy)
            mapped (mapcat #(get-in % [:public-metadata :rows]) emissions)
            unmapped
            (->> mapped
                 (filter (fn [row]
                           (or (str/blank? (:declaration-key row))
                               (str/blank? (get-in row [:generated :id]))
                               (str/blank?
                                (get-in row [:generated :source :location :file]))
                               (not (pos? (or (get-in row
                                                      [:generated :source :location :line])
                                              0)))
                               (some (fn [field]
                                       (str/blank?
                                        (str (get-in row
                                                     [:generated :destination field]))))
                                     [:assembly :owner :kind :name :parameter-count]))))
                 (take 30) vec)]
        (when (seq unmapped)
          (fail! "A generated public contract member lost its exact source mapping"
                 {:kind :unmapped-generated-public-member :rows unmapped}))
        {:source-files (count source-files)
         :source-roots (mapv str source-roots)
         :compiled-assemblies (count packages)
         :reviewed-body-candidates (count reviewed)
         :body-findings (frequencies (map :finding actual-rows))
         :mapped-generated-members (count mapped)
         :generated-source-mappings (reduce + (map :source-mappings mapping-audits))
         :source-patterns (mapv first forbidden-public-source-patterns)}))))

(defn- duplicate-values
  [key-fn rows]
  (->> rows (group-by key-fn) (keep (fn [[key values]] (when (< 1 (count values)) key)))
       sort vec))

(defn- upstream-identity
  [row]
  (mapv row (map keyword upstream-columns)))

(defn- package-identity
  [row]
  (mapv row (map keyword package-columns)))

(defn- upstream-sort-key
  [row]
  (mapv row [:owner :kind :name :signature :upstream-provenance]))

(defn- package-sort-key
  [row]
  (mapv row [:owner :kind :name :signature]))

(defn compare-package-surface
  "Exact reflection snapshot comparator. Any missing, duplicate, extra, or
  metadata-perturbed row is a mismatch."
  [expected-rows actual-rows]
  (let [duplicates (duplicate-values package-identity actual-rows)]
    (if (seq duplicates)
      {:mismatch {:kind :duplicate-package-reflection-rows
                  :rows (take 20 duplicates)}}
      (let [expected (set (map package-identity expected-rows))
            actual (set (map package-identity actual-rows))
            missing (sort (set/difference expected actual))
            unexpected (sort (set/difference actual expected))]
        (if (and (empty? missing) (empty? unexpected))
          {:matched (count expected)}
          {:mismatch {:kind :package-public-surface-drift
                      :missing (vec (take 20 missing))
                      :unexpected (vec (take 20 unexpected))
                      :missing-count (count missing)
                      :unexpected-count (count unexpected)}})))))

(defn- generated-package-key
  [{:keys [assembly owner kind name parameter-count]}]
  [assembly (normalized-owner owner) kind name parameter-count])

(defn compare-generated-package-surface
  "Checks that every contract-backed generated declaration is present in the
  reflected package metadata. Multiplicity is significant so same-arity
  overload collapse cannot pass as one member. Unexpected public metadata is
  handled by the exact PackageSurface.tsv comparison, where every row has an
  explicit product/native/implementation classification."
  [public-metadata actual-rows]
  (let [metadata (if (sequential? public-metadata)
                   public-metadata [public-metadata])
        rows (mapcat :rows metadata)
        missing-destinations
        (->> rows
             (keep (fn [row]
                     (let [destination (get-in row [:generated :destination])]
                       (when (some #(str/blank? (str (destination %)))
                                   [:assembly :owner :kind :name :parameter-count])
                         {:declaration-key (:declaration-key row)
                          :signature (get-in row [:row :signature])
                          :destination destination}))))
             (take 20) vec)
        expected (frequencies (map #(generated-package-key
                                     (get-in % [:generated :destination]))
                                   rows))
        actual (frequencies
                (map (fn [row]
                       [(:assembly row) (normalized-owner (:owner row))
                        (:kind row) (:name row) (:parameter-count row)])
                     actual-rows))
        shortages
        (->> expected
             (keep (fn [[key required]]
                     (let [present (get actual key 0)]
                       (when (< present required)
                         {:key key :required required :actual present
                          :failure (if (zero? present)
                                     :absent-compiled-public-api-member
                                     :signature-collapsed-compiled-public-api-member)}))))
             (sort-by :key) vec)]
    (cond
      (seq missing-destinations)
      {:mismatch {:kind :source-unmapped-generated-public-metadata
                  :rows missing-destinations}}

      (seq shortages)
      {:mismatch {:kind :compiled-public-api-contract-mismatch
                  :members (vec (take 30 shortages))
                  :member-count (count shortages)}}

      :else
      {:matched (reduce + (vals expected))
       :distinct-shapes (count expected)})))

(defn verify-generated-packages!
  "Reflects the assemblies produced by a clean generation/build, rejects exact
  package drift, and joins every contract row through generated declaration
  metadata to a live public CLR member."
  [workspace generation build-configuration]
  (let [workspace (paths/absolute workspace)
        main (assoc (:emission generation) :destination (:destination generation))
        emissions (vec (concat (:dependency-emissions generation) [main]))
        packages
        (mapv (fn [{:keys [project-root destination public-metadata] :as emission}]
                (let [assembly (get-in destination [:project :assembly-name])
                      framework (get-in destination [:project :target-framework])]
                  (when-not (and assembly framework public-metadata)
                    (fail! "Clean package verification requires generated public metadata"
                           {:kind :missing-clean-build-public-metadata
                            :profile (:profile emission)
                            :assembly assembly :framework framework}))
                  {:assembly assembly
                   :metadata public-metadata
                   :file (paths/resolve-path project-root "bin" build-configuration
                                             framework (str assembly ".dll"))}))
              emissions)
        actual-file (.resolve (temp-directory "dripsharp-generated-package-api")
                              "actual.tsv")
        _ (reflect-packages! workspace (mapv :file packages) actual-file)
        actual-rows (:rows (read-tsv actual-file package-columns))
        assemblies (set (map :assembly packages))
        expected-rows (->> (:rows (read-tsv (:package (contract-paths workspace))
                                            package-columns))
                           (filter #(assemblies (:assembly %))) vec)
        snapshot (compare-package-surface expected-rows actual-rows)
        generated (compare-generated-package-surface (mapv :metadata packages)
                                                     actual-rows)]
    (when-let [mismatch (:mismatch snapshot)]
      (fail! "Reflected clean-build package public metadata drifted"
             (assoc mismatch :kind :package-public-api-surface-drift)))
    (when-let [mismatch (:mismatch generated)]
      (fail! "A generated contract member is absent from compiled public metadata"
             mismatch))
    {:assemblies (sort assemblies)
     :package-rows (:matched snapshot)
     :contract-members (:matched generated)
     :contract-shapes (:distinct-shapes generated)}))

(defn compare-upstream-surface
  "Exact source-declaration comparator used to reject missing, duplicate, and
  perturbed upstream rows before policy is applied."
  [expected-rows actual-rows]
  (let [duplicates (duplicate-values upstream-identity actual-rows)]
    (if (seq duplicates)
      {:mismatch {:kind :duplicate-upstream-extraction-rows
                  :rows (take 20 duplicates)}}
      (let [expected (set (map upstream-identity expected-rows))
            actual (set (map upstream-identity actual-rows))
            missing (sort (set/difference expected actual))
            unexpected (sort (set/difference actual expected))]
        (if (and (empty? missing) (empty? unexpected))
          {:matched (count expected)}
          {:mismatch {:kind :upstream-public-surface-drift
                      :missing (vec (take 20 missing))
                      :unexpected (vec (take 20 unexpected))
                      :missing-count (count missing)
                      :unexpected-count (count unexpected)}})))))

(defn compare-behavior-results
  "Compares externally produced behavior observations with the contract."
  [behavior-rows actual-rows]
  (let [duplicates (duplicate-values :case-id actual-rows)
        expected-ids (set (map :case-id behavior-rows))
        actual-ids (set (map :case-id actual-rows))]
    (cond
      (seq duplicates)
      {:mismatch {:kind :duplicate-public-api-behavior-results
                  :case-ids duplicates}}

      (not= expected-ids actual-ids)
      {:mismatch {:kind :public-api-behavior-coverage
                  :missing (sort (set/difference expected-ids actual-ids))
                  :unexpected (sort (set/difference actual-ids expected-ids))}}

      :else
      (let [expected (into {} (map (juxt :case-id :normalized-expectation)
                                   behavior-rows))
            unexecuted (->> actual-rows (remove #(= "EXECUTED" (:status %)))
                            (map :case-id) sort vec)
            changed (->> actual-rows
                         (keep (fn [row]
                                 (when-not (= (expected (:case-id row))
                                              (:observation row))
                                   (:case-id row))))
                         sort vec)]
        (cond
          (seq unexecuted)
          {:mismatch {:kind :unexecuted-public-api-behavior
                      :case-ids unexecuted}}
          (seq changed)
          {:mismatch {:kind :public-api-behavior-drift :case-ids changed}}
          :else {:matched (count behavior-rows)})))))

(defn- validate-sorted!
  [rows key-fn kind]
  (let [keys (mapv key-fn rows)]
    (when-not (= keys (vec (sort keys)))
      (fail! "Public API fixture rows are not deterministically sorted"
             {:kind kind}))))

(defn- evidence-path
  [provenance]
  (first (str/split provenance #":" 2)))

(defn- validate-controls!
  [workspace controls package-rows]
  (let [ids (map :control-id controls)
        package-keys (set (map package-broad-key package-rows))]
    (when-not (= (count ids) (count (distinct ids)))
      (fail! "Failing control fixture has duplicate rows"
             {:kind :duplicate-public-api-failing-control}))
    (doseq [row controls]
      (let [provenance (paths/resolve-path workspace
                                           (evidence-path (:upstream-provenance row)))
            expected [(normalized-owner (:expected-owner row)) (:expected-kind row)
                      (:expected-name row) (:parameter-count row)]]
        (when-not (paths/regular-file? provenance)
          (fail! "Failing control has missing upstream provenance"
                 {:kind :missing-failing-control-provenance
                  :control-id (:control-id row)}))
        (when (and (= "missing" (:failure row)) (package-keys expected))
          (fail! "A control marked missing is already present in the package snapshot"
                 {:kind :stale-missing-public-api-control
                  :control-id (:control-id row)}))
        (when (and (not= "missing" (:failure row)) (package-keys expected))
          (fail! "A non-idiomatic control's desired adaptation is already present"
                 {:kind :stale-resolved-public-api-control
                  :control-id (:control-id row)}))
        (when-not (= "-" (:current-owner row))
          (let [current [(normalized-owner (:current-owner row)) (:current-kind row)
                         (:current-name row) (:parameter-count row)]]
            (when-not (package-keys current)
              (fail! "A non-idiomatic control no longer matches package metadata"
                     {:kind :stale-nonidiomatic-public-api-control
                      :control-id (:control-id row)}))))))))

(defn validate-contract!
  "Validates fixture completeness, classification, evidence, current package
  inventory, native .NET adaptations, and expected failing controls."
  [workspace]
  (let [workspace (paths/absolute workspace)
        {:keys [upstream package policy behavior controls behavior-evidence]}
        (contract-paths workspace)
        upstream-rows (:rows (read-tsv upstream upstream-columns))
        package-rows (:rows (read-tsv package package-columns))
        policies (read-policy policy)
        behavior-rows (:rows (read-tsv behavior behavior-columns))
        controls-rows (:rows (read-tsv controls failing-control-columns))
        behavior-evidence-rows
        (:rows (read-tsv behavior-evidence
                         ["case-id" "upstream-provenance" "line-sha256"
                          "dotnet-invocation"]))]
    (when-not (= pinned-upstream-revision
                 (str/trim (command-output {:command ["git" "rev-parse" "HEAD"]
                                            :directory (paths/resolve-path workspace
                                                                           "research" "pkl")})))
      (fail! "The public API contract upstream revision drifted"
             {:kind :public-api-upstream-revision-drift
              :expected pinned-upstream-revision}))
    (let [upstream-duplicates (duplicate-values upstream-identity upstream-rows)
          package-duplicates (duplicate-values package-identity package-rows)]
      (when (seq upstream-duplicates)
        (fail! "Upstream public API fixture has duplicate rows"
               {:kind :duplicate-upstream-public-api :rows (take 20 upstream-duplicates)}))
      (when (seq package-duplicates)
        (fail! "Package public API fixture has duplicate rows"
               {:kind :duplicate-package-public-api :rows (take 20 package-duplicates)})))
    (validate-sorted! upstream-rows upstream-sort-key :unsorted-upstream-public-api)
    (validate-sorted! package-rows package-sort-key :unsorted-package-public-api)
    (let [rows (contract-rows upstream-rows policies)
          target-keys (set (keep #(when (= "product-api" (:classification %))
                                    (target-broad-key %))
                                 rows))
          package-classifications (mapv #(merge % (classify-package-row target-keys %))
                                        package-rows)
          behavior-ids (map :case-id behavior-rows)]
      (validate-package-boundary! package-rows package-classifications)
      (validate-package-consumer-boundary! workspace)
      (when-not (= (count behavior-ids) (count (distinct behavior-ids)))
        (fail! "Behavior contract has duplicate case IDs"
               {:kind :duplicate-public-api-behavior}))
      (when-not (= (set behavior-ids) (set (map :case-id behavior-evidence-rows)))
        (fail! "Behavior evidence extraction has missing or silently skipped rows"
               {:kind :public-api-behavior-evidence-coverage}))
      (doseq [row rows]
        (when (some #(str/blank? (str (row %)))
                    [:classification :area :behavior-family :policy-evidence
                     :dotnet-adaptation :exclusion-evidence :upstream-provenance
                     :generic-constraints :nullability :exceptions :delegate :lifecycle])
          (fail! "Executable public API row has blank required metadata"
                 {:kind :blank-public-api-contract-metadata
                  :owner (:owner row) :name (:name row)})))
      (doseq [row behavior-rows]
        (when-not (#{"parser" "core" "config-binding" "csharp-generation"}
                   (:area row))
          (fail! "Behavior contract has an unknown product area"
                 {:kind :unknown-public-api-behavior-area :case-id (:case-id row)})))
      (let [areas (frequencies (map :area rows))
            native-areas (frequencies (map :area (filter #(= "product-api-native"
                                                             (:classification %))
                                                         package-classifications)))
            classifications (frequencies (map :classification rows))
            package-class-counts (frequencies (map :classification package-classifications))]
        (doseq [area ["parser" "core" "config-binding"]]
          (when-not (pos? (get areas area 0))
            (fail! "An upstream product area has no executable contract rows"
                   {:kind :missing-public-api-area :area area})))
        (doseq [area ["config-binding" "csharp-generation"]]
          (when-not (pos? (get native-areas area 0))
            (fail! "An idiomatic .NET product area has no reflected native API rows"
                   {:kind :missing-native-public-api-area :area area})))
        (validate-controls! workspace controls-rows package-rows)
        {:upstream-rows (count rows)
         :package-rows (count package-rows)
         :behavior-rows (count behavior-rows)
         :failing-controls (count controls-rows)
         :areas areas
         :classifications classifications
         :package-classifications package-class-counts
         :native-areas native-areas}))))

(defn verify-upstream-snapshot!
  [workspace]
  (let [workspace (paths/absolute workspace)
        expected (:upstream (contract-paths workspace))
        actual (.resolve (temp-directory "dripsharp-upstream-api-snapshot") "actual.tsv")]
    (extract-upstream! workspace actual)
    (let [expected-rows (:rows (read-tsv expected upstream-columns))
          actual-rows (:rows (read-tsv actual upstream-columns))
          comparison (compare-upstream-surface expected-rows actual-rows)]
      (when (:mismatch comparison)
        (fail! "Independent upstream public metadata extraction drifted"
               (assoc (:mismatch comparison)
                      :kind :upstream-public-api-surface-drift)))
      comparison)))

(defn verify-package-snapshot!
  [workspace assemblies]
  (let [workspace (paths/absolute workspace)
        expected (:package (contract-paths workspace))
        actual (.resolve (temp-directory "dripsharp-package-api-snapshot") "actual.tsv")]
    (reflect-packages! workspace assemblies actual)
    (let [comparison (compare-package-surface
                      (:rows (read-tsv expected package-columns))
                      (:rows (read-tsv actual package-columns)))]
      (when (:mismatch comparison)
        (fail! "Reflected package public metadata drifted"
               (assoc (:mismatch comparison) :kind :package-public-api-surface-drift)))
      comparison)))

(defn strategy
  "Returns the Pkl-owned public selection and compiled-contract lifecycle.
  Product-neutral orchestration loads this strategy only for destinations that
  explicitly select the Pkl product family."
  []
  {:schema-version 1
   :id :pkl-public-api
   :product-family :pkl
   :read! generation-surface!
   :validate-selected! validate-selected-surface!
   :validate-generated! validate-generated-surface!
   :emission-boundary
   (fn [surface dependency-emissions]
     (update surface :selection-evidence into
             (mapcat #(get-in % [:public-api-boundary :selection-evidence])
                     dependency-emissions)))
   :verify-compiled! verify-generated-packages!})
