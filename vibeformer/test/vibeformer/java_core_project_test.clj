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
      (is (= 657 (:compilation-units summary)))
      (is (= 661 (:generated-files summary)))
      (is (= 28 (:resources summary)))
      (is (= 0 (:skipped-source-units summary)))
      (is (= 0 (:collisions summary)))
      (is (= 0 (:missing-source-mappings summary)))
      (is (= 30709 (:declarations summary)))
      (is (= {:constructor 1173
              :enum-value 101
              :field 3577
              :initializer 7
              :method 9226
              :parameter 14045
              :record-component 227
              :type 2202
              :type-parameter 151}
             (:declaration-kinds summary)))
      (is (= 657 (count (:sources manifest))))
      (is (= 28 (count (:resources manifest))))
      (is (empty? diagnostics)))

    (testing "every executable root has accepted recursive Spoon coverage"
      (is (= 11056 (:executable-roots summary)))
      (is (= 0 (:hard-failures summary)))
      (is (= {:semantic 451472
              :fallback 0
              :visited 1044371
              :missing-mappings 0
              :unsupported-elements 0
              :missing-occurrences 0
              :structural 592899
              :blocked 0
              :covered 1044371}
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
              file-system-manager (slurp (str (paths/resolve-path
                                                source-root "Runtime"
                                                "FileSystemManager.cs")))
              loading-runtime (slurp (str (paths/resolve-path
                                            source-root "Runtime" "Substrate"
                                            "Pkl.Core.Loading.cs")))
              selected-api [evaluator-builder module-source security-managers
                            evaluator-settings loading-runtime]
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
            ;; The one exact null body is the intentional upstream default for
            ;; custom managers that do not configure root-path resolution.
            (is (= [["ResolveSecurePath" "null!"]]
                   (mapv #(vec (rest %)) (re-seq exact-stub security-manager))))
            (is (not (str/includes? evaluator-settings "NoCache.Value")))
            (is (str/includes? security-managers "JavaCompat.RealPath"))
            (is (str/includes? security-managers "JavaCompat.NormalizePath"))
            (is (str/includes? security-managers "JavaCompat.PathStartsWith"))
            (is (str/includes? file-system-manager
                               "JavaFileSystemAlreadyExistsException"))
            (is (str/includes? loading-runtime "CreateAssembly"))
            (is (str/includes? loading-runtime "CreateEmbeddedResources"))
            (is (str/includes? loading-runtime "static Platform()"))
            (is (str/includes? loading-runtime "static JdkHttpClient()"))))))

    (testing "two independent closures emit byte-for-byte identical projects"
      (is (= (directory-bytes (:project-root first-emission))
             (directory-bytes (:project-root second-emission)))))))
