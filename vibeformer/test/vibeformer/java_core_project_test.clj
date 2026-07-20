(ns vibeformer.java-core-project-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.complete-core-closure-fixture :as fixture]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.pkl.java-project :as java-project]
            [vibeformer.paths :as paths])
  (:import [java.nio.file FileVisitOption Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-java-core-project"
                             (make-array FileAttribute 0)))

(defn- directory-bytes [^Path root]
  (with-open [files (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (map (fn [^Path file]
                [(str (.relativize root file)) (vec (Files/readAllBytes file))]))
         (into (sorted-map)))))

(defn- public-method-frequencies [source]
  (frequencies
   (map second
        (re-seq #"(?m)^public .*? ([A-Z][A-Za-z0-9_]*)\(" source))))

(def ^:private evaluator-builder-methods
  (frequencies
   ["Preconfigured" "Unconfigured" "SetColor" "GetColor"
    "SetStackFrameTransformer" "GetStackFrameTransformer"
    "SetSecurityManager" "UnsetSecurityManager" "GetSecurityManager"
    "SetAllowedModules" "GetAllowedModules" "SetAllowedResources"
    "GetAllowedResources" "SetRootDir" "GetRootDir" "SetLogger"
    "GetLogger" "SetHttpClient" "GetHttpClient" "AddModuleKeyFactory"
    "AddModuleKeyFactories" "SetModuleKeyFactories" "GetModuleKeyFactories"
    "AddResourceReader" "AddResourceReaders" "SetResourceReaders"
    "GetResourceReaders" "AddEnvironmentVariable" "AddEnvironmentVariables"
    "SetEnvironmentVariables" "GetEnvironmentVariables" "AddExternalProperty"
    "AddExternalProperties" "SetExternalProperties" "GetExternalProperties"
    "SetTimeout" "GetTimeout" "SetModuleCacheDir" "GetModuleCacheDir"
    "SetOutputFormat" "SetOutputFormat" "GetOutputFormat"
    "SetProjectDependencies" "GetProjectDependencies" "SetTraceMode"
    "GetTraceMode" "SetPowerAssertionsEnabled" "GetPowerAssertionsEnabled"
    "ApplyFromProject" "Build"]))

(def ^:private module-source-methods
  (frequencies
   ["Create" "PathFromPath" "PathFromString" "Text" "FileFromString"
    "FileFromFile" "Uri" "Uri" "ModulePath" "GetUri" "GetContents"]))

(def ^:private standard-security-methods
  (frequencies
   ["CreateStandard" "CreateStandardBuilder" "ResolveSecurePath"
    "ResolveSecurePath" "CheckResolveModule" "CheckResolveResource"
    "CheckReadResource" "CheckImportModule" "AddAllowedModule"
    "AddAllowedModules" "SetAllowedModules" "GetAllowedModules"
    "AddAllowedResource" "AddAllowedResources" "SetAllowedResources"
    "GetAllowedResources" "SetRootDir" "GetRootDir" "Build"]))

(defn- emit! [target resolved-model worker-count]
  (let [{:keys [root discovery]} (fixture/models)]
    (concurrency/call-with-executor
     {:worker-count worker-count}
     #(java-project/emit-project!
       {:workspace-root root
        :target target
        :discovery discovery
        :resolved-model resolved-model
        :configuration
        (java-project/read-configuration
         root "vibeformer/config/pkl-core-value-model-destination.edn")}))))

(deftest complete-core-value-model-emission-is-zero-failure-and-stable
  (let [{:keys [first second]} (fixture/models)
        first-emission (emit! (temp-directory) first 1)
        second-emission (emit! (temp-directory) second 4)
        summary (:summary first-emission)
        first-profile (:emission-profile first-emission)
        second-profile (:emission-profile second-emission)
        project-root (:project-root first-emission)
        manifest (edn/read-string (slurp (str (:manifest-file first-emission))))
        diagnostics (:diagnostics first-emission)]
    (testing "the dominant core root is split deterministically across workers"
      (is (= {:name "org.pkl.core.stdlib.base.ListNodesFactory"
              :weight 72524
              :member-count 96}
             (:largest-root first-profile)
             (:largest-root second-profile)))
      (is (some? (:dominant-root first-profile)))
      (is (= {:name "org.pkl.core.stdlib.base.ListNodesFactory"
              :weight 72524
              :member-count 95
              :member-weight 72486
              :largest-member-weight 1688}
             (dissoc (:dominant-root first-profile)
                     :worker-threads :worker-participation :elapsed-millis)))
      (is (= 1 (get-in first-profile [:dominant-root :worker-participation])))
      (is (< 1 (get-in second-profile [:dominant-root :worker-participation])))
      (is (= (dissoc (:dominant-root first-profile)
                     :worker-threads :worker-participation :elapsed-millis)
             (dissoc (:dominant-root second-profile)
                     :worker-threads :worker-participation :elapsed-millis))))

    (testing "the entire selected declaration and body closure is accounted for"
      (is (= 658 (:compilation-units summary)))
      (is (= 663 (:generated-files summary)))
      (is (= 28 (:resources summary)))
      (is (= 0 (:skipped-source-units summary)))
      (is (= 0 (:collisions summary)))
      (is (= 0 (:missing-source-mappings summary)))
      (is (= 30979 (:declarations summary)))
      (is (= {:constructor 1179
              :enum-value 101
              :field 3601
              :initializer 24
              :method 9361
              :parameter 14114
              :record-component 232
              :type 2214
              :type-parameter 153}
             (:declaration-kinds summary)))
      (is (= 658 (count (:sources manifest))))
      (is (= 28 (count (:resources manifest))))
      (is (empty? diagnostics)))

    (testing "every executable root has accepted recursive Spoon coverage"
      (is (= 11233 (:executable-roots summary)))
      (is (= 0 (:hard-failures summary)))
      (is (= {:semantic 468712
              :fallback 0
              :visited 1077183
              :missing-mappings 0
              :unsupported-elements 0
              :missing-occurrences 0
              :structural 608471
              :blocked 0
              :covered 1077183}
             (:executable-coverage summary)))
      (let [sources (->> (:artifacts manifest)
                         (filter #(nil? (:strategy %)))
                         (map :file)
                         (filter #(str/ends-with? % ".cs"))
                         (map #(slurp (str (paths/resolve-path project-root %)))))]
        (is (not-any? #(re-find #"#error VIBEFORMER_|NotImplementedException" %)
                      sources))
        (is (not-any? #(str/includes? % "value => value.Of()") sources))
        (is (some #(str/includes? % "unchecked((sbyte)(") sources))
        (is (some #(str/includes? % "JavaCompat.OrganicPut") sources))
        (is (some #(str/includes? % "((string[])JsonEscaper.REPLACEMENTS.Clone())")
                  sources))
        (is (some #(str/includes? %
                                 "LoadModule(global::Vibeformer.Runtime.JavaCompat.CreateUri(\"pkl:math\")")
                  sources))
        (doseq [stdlib-module ["pkl:analyze" "pkl:Benchmark" "pkl:Command"
                               "pkl:jsonnet" "pkl:pklbinary" "pkl:release"
                               "pkl:xml"]]
          (is (some #(str/includes? %
                                   (str "LoadModule(global::Vibeformer.Runtime.JavaCompat.CreateUri(\""
                                        stdlib-module "\")"))
                    sources)))
        (doseq [stdlib-module ["pkl:platform" "pkl:reflect"]]
          (is (some #(str/includes? %
                                   (str "LoadModule(global::Vibeformer.Runtime.JavaCompat.CreateUri(\""
                                        stdlib-module "\")"))
                    sources)))
        (is (some #(str/includes? %
                                 "LoadModule(global::Vibeformer.Runtime.JavaCompat.CreateUri(\"pkl:test\")")
                  sources))
        (is (some #(str/includes? %
                                 "NodeInfo(\"&&\")")
                  sources))
        (is (some #(str/includes? % "StdLibModule.LoadModule(global::Pkl.Core.PClassInfo<object>.pklProjectUri")
                  sources))
        (is (some #(str/includes? % "StdLibModule.LoadModule(global::Pkl.Core.PClassInfo<object>.pklSemverUri")
                  sources))
        (is (some #(str/includes? % "StdLibModule.LoadModule(global::Pkl.Core.PClassInfo<object>.pklSettingsUri")
                  sources))
        (is (not-any? #(str/includes? % "global::System.Func.Identity<") sources))
        (let [source-root (paths/resolve-path project-root "src" "Pkl" "Core")
              evaluator-builder (slurp (str (paths/resolve-path source-root
                                                                 "EvaluatorBuilder.cs")))
              module-source (slurp (str (paths/resolve-path source-root
                                                             "ModuleSource.cs")))
              security-managers (slurp (str (paths/resolve-path source-root
                                                                  "SecurityManagers.cs")))
              security-manager (slurp (str (paths/resolve-path source-root
                                                                 "SecurityManager.cs")))
              message (slurp (str (paths/resolve-path source-root
                                                       "Messaging" "Message.cs")))
              imports-parser (slurp (str (paths/resolve-path
                                           source-root "Ast" "Builder"
                                           "ImportsAndReadsParser.cs")))
              evaluator-settings (slurp (str (paths/resolve-path
                                               source-root "EvaluatorSettings"
                                               "PklEvaluatorSettings.cs")))
              project-settings (slurp (str (paths/resolve-path
                                             source-root "Project" "Project.cs")))
              import-analyzer (slurp (str (paths/resolve-path
                                            source-root "Runtime" "VmImportAnalyzer.cs")))
              file-system-manager (slurp (str (paths/resolve-path
                                                source-root "Runtime"
                                                "FileSystemManager.cs")))
              loading-runtime (slurp (str (paths/resolve-path
                                            source-root "Runtime" "Substrate"
                                            "Pkl.Core.Loading.cs")))
              java-compat (slurp (str (paths/resolve-path
                                        project-root "src" "Vibeformer" "Runtime"
                                        "JavaCompat.cs")))
              identifier (slurp (str (paths/resolve-path source-root
                                                           "Runtime" "Identifier.cs")))
              member-lookup-suggestions
              (slurp (str (paths/resolve-path source-root
                                               "Runtime" "MemberLookupSuggestions.cs")))
              vm-exception-builder
              (slurp (str (paths/resolve-path source-root
                                               "Runtime" "VmExceptionBuilder.cs")))
              substrate (slurp (str (paths/resolve-path
                                      source-root "Runtime" "Substrate"
                                      "Pkl.Core.Substrate.cs")))
              http-client (slurp (str (paths/resolve-path source-root
                                                           "Http" "HttpClient.cs")))
              package-uri (slurp (str (paths/resolve-path source-root
                                                           "Packages" "PackageUri.cs")))
              package-asset-uri (slurp (str (paths/resolve-path
                                              source-root "Packages"
                                              "PackageAssetUri.cs")))
              checksums (slurp (str (paths/resolve-path source-root
                                                         "Packages" "Checksums.cs")))
              dependency (slurp (str (paths/resolve-path source-root
                                                          "Packages" "Dependency.cs")))
              dependency-metadata (slurp (str (paths/resolve-path
                                                source-root "Packages"
                                                "DependencyMetadata.cs")))
              json (slurp (str (paths/resolve-path source-root
                                                    "Util" "Json" "Json.cs")))
              module-keys (slurp (str (paths/resolve-path source-root
                                                           "Module" "ModuleKeys.cs")))
              module-cache (slurp (str (paths/resolve-path source-root
                                                            "Runtime" "ModuleCache.cs")))
              selected-api [evaluator-builder module-source security-managers
                            evaluator-settings project-settings loading-runtime http-client
                            package-uri package-asset-uri checksums dependency
                            dependency-metadata]
              exact-stub #"(?ms)^public[^\n{]+ ([A-Z][A-Za-z0-9_]*)\([^)]*\) \{\nreturn (null!|default!);\n\}"]
          (testing "the evaluator, source, and standard-policy public closure is exact"
            (is (= evaluator-builder-methods
                   (public-method-frequencies evaluator-builder)))
            (is (= module-source-methods
                   (public-method-frequencies module-source)))
            (is (= standard-security-methods
                   ;; Standard is a public constructor on a private nested
                   ;; implementation, not part of the exported method surface.
                   (dissoc (public-method-frequencies security-managers)
                           "Standard"))))
          (testing "nested declarations are fully resolved in C# base clauses"
            (is (str/includes? message
                               "global::Pkl.Core.Messaging.Message.Response"))
            (is (not (str/includes? message "interface Response : Client, Response")))
            (is (str/includes?
                 imports-parser
                 "global::Pkl.Core.Ast.Builder.ImportsAndReadsParser.Entry")))
          (testing "the selected public API fails closed on implementation stubs"
            (is (not-any? #(re-find #"#error VIBEFORMER_|NotImplementedException|TODO" %)
                          selected-api))
            (is (empty? (mapcat #(re-seq exact-stub %) selected-api)))
            (is (str/includes? package-asset-uri
                               "public PackageAssetUri Resolve(string path)"))
            (is (str/includes? dependency-metadata
                               "public static DependencyMetadata Parse(string input)"))
            (is (str/includes? json "JsonHandlerBridge.Erase(handler"))
            (is (str/includes? package-uri "JavaCompat.StringSplit(path"))
            (is (str/includes? module-keys "JavaCompat.CastDictionary<string"))
            (is (str/includes? module-keys
                               "Pkl.Core.Util.IoUtils.Resolve(((global::Pkl.Core.Runtime.ReaderBase)(object)this)"))
            (is (str/includes? module-cache "JavaCompat.NewJavaDictionary<global::System.Uri"))
            ;; The one exact null body is the intentional upstream default for
            ;; custom managers that do not configure root-path resolution.
            (is (= [["ResolveSecurePath" "null!"]]
                   (mapv #(vec (rest %)) (re-seq exact-stub security-manager))))
            (is (not (str/includes? evaluator-settings "NoCache.Value")))
            (is (not (str/includes? project-settings "NoCache.Value")))
            (is (str/includes? project-settings
                               "var cycles = Project.FindImportCycle(moduleSource)"))
            (is (str/includes? project-settings
                               "var onlyDirectSelfCycle = global::Vibeformer.Runtime.JavaCompat.ListCount(cycles) == 1"))
            (is (str/includes? project-settings
                               "&& !onlyDirectSelfCycle"))
            (is (str/includes? dependency
                               "JavaCompat.ResolveLocalDependencyUri(projectBaseUri"))
            (is (str/includes? substrate
                               "internal SourceSection CreateSection(int line)"))
            (is (str/includes? substrate
                               "Instrumenter.InstrumentActive(root)"))
            (is (str/includes? substrate
                               "if (location is not null || caller is not null)"))
            (is (str/includes? substrate
                               "if (child is RootNode) return;"))
            (is (str/includes? substrate
                               "vmException.GetSourceSection() is null"))
            (is (str/includes? import-analyzer "JavaCompat.NewSortedDictionary<"))
            (is (str/includes? import-analyzer "JavaCompat.NewSortedSet<"))
            (is (str/includes?
                 identifier
                 (str "public sealed partial class Identifier : "
                      "global::System.IComparable<global::Pkl.Core.Runtime.Identifier>")))
            (is (str/includes?
                 member-lookup-suggestions
                 (str "public sealed partial class Candidate : "
                      "global::System.IComparable<global::Pkl.Core.Runtime."
                      "MemberLookupSuggestions.Candidate>")))
            (is (str/includes? java-compat
                               "values.OrderBy(value => value, Comparer<T>.Create(JavaCompare))"))
            (is (str/includes? java-compat
                               "internal static Comparison<T> NaturalOrder<T>() => JavaCompare;"))
            (is (str/includes? java-compat
                               "StringBuilder AppendValue(StringBuilder builder, object? value)"))
            (is (str/includes? java-compat
                               "internal static double StrictPow(double x, double y)"))
            (is (str/includes? java-compat
                               "sealed class JavaLinkedList<T> : IList<T>"))
            (is (str/includes? java-compat
                               "if (value.OriginalString.Length == 0) value = CreateUri(\".\")"))
            (is (str/includes? java-compat
                               "var pathUri = new Uri(Path.GetFullPath(path))"))
            (is (str/includes? java-compat
                               "SingleSlashFileUris.TryGetValue(value, out _)"))
            (is (str/includes? java-compat
                               "Regex.IsMatch(value, @\"(?i)^file:[^/]\")"))
            (is (str/includes? java-compat
                               "Uri uri => UriToString(uri)"))
            (is (str/includes? java-compat
                               "ResolveLocalDependencyUri(Uri basis, Uri value)"))
            (is (str/includes? java-compat
                               "private static string TranslateJavaRegex(string pattern)"))
            (is (str/includes? java-compat
                               "internal static int StringCompareTo(string left, string right)"))
            (is (str/includes?
                 vm-exception-builder
                 "JavaCompat.Sorted(result"))
            (is (str/includes?
                 vm-exception-builder
                 ".Sort(global::Vibeformer.Runtime.JavaCompat.NaturalOrder<global::Pkl.Core.Runtime.Identifier>())"))
            (is (str/includes? security-managers "JavaCompat.RealPath"))
            (is (str/includes? security-managers "JavaCompat.NormalizePath"))
            (is (str/includes? security-managers "JavaCompat.PathStartsWith"))
            (is (str/includes? file-system-manager
                               "JavaFileSystemAlreadyExistsException"))
            (is (str/includes? loading-runtime "CreateAssembly"))
            (is (str/includes? loading-runtime "CreateEmbeddedResources"))
            (is (str/includes? loading-runtime "static Platform()"))
            (is (str/includes? loading-runtime "static JdkHttpClient()"))
            (is (str/includes? java-compat
                               "colon + 1 == original.Length || original[colon + 1] != '/'"))
            (is (str/includes? substrate
                               "NewDefaultBufferPacker() => new();"))
            (is (str/includes? substrate
                               "NewDefaultPacker(System.IO.Stream stream) => new(stream);"))
            (is (str/includes? substrate
                               "class JavaPrintWriter : System.IO.TextWriter"))
            (is (str/includes? substrate
                               "DecoderFallback.ExceptionFallback"))
            (is (str/includes? substrate
                               "public sealed class Instrumenter"))
            (is (str/includes? substrate
                               "stream ?? throw Excluded()"))))))

    (testing "two independent closures emit byte-for-byte identical projects"
      (is (= (directory-bytes (:project-root first-emission))
             (directory-bytes (:project-root second-emission)))))))
