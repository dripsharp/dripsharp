(ns vibeformer.labeled-control-flow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.pkl.java-body :as java-body]
            [vibeformer.process :as process]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtMethod]
           [spoon.reflect.visitor.filter TypeFilter]))

(def ^:private method-names
  ["labeledBlocksAndIf"
   "nestedLoops"
   "everyLoopKind"
   "singleStatementLoop"
   "disjointLabelReuse"
   "nestedDistinctLabels"
   "tryFinally"
   "branchFromFinally"
   "switchInteraction"])

(defn- fixture-path
  [filename]
  (paths/resolve-path (paths/workspace-root)
                      "vibeformer" "test" "fixtures"
                      "labeled_control_flow" filename))

(defn- discovery
  [source]
  {:java-home (paths/absolute (System/getProperty "java.home"))
   :java-release 17
   :preview-features false
   :java-sources [source]
   :resources []
   :classpath []})

(defn- identifier
  [value]
  (let [clean (-> (str value)
                  (str/replace #"[^A-Za-z0-9_]" "_")
                  (#(if (re-matches #"[0-9].*" %) (str "_" %) %)))]
    (if (contains? #{"base" "break" "case" "continue" "default" "do"
                     "else" "for" "goto" "if" "lock" "return" "switch"
                     "try" "while"}
                   clean)
      (str "@" clean)
      clean)))

(defn- fixture-services
  []
  {:identifier identifier
   :pascal #(let [name (identifier %)]
              (str (str/upper-case (subs name 0 1)) (subs name 1)))
   :method-name #(.getSimpleName ^CtMethod %)
   :record-component-name (fn [_ component] (identifier (.getSimpleName component)))
   :local-name (fn [element]
                 (let [{:keys [line column]} (spoon/source-location element)]
                   (str (identifier (.getSimpleName element))
                        "__" (or line 0) "_" (or column 0))))
   :type-node (fn [reference]
                (csharp/raw
                 (case (.getQualifiedName reference)
                   "boolean" "bool"
                   "int" "int"
                   "int[]" "int[]"
                   "java.lang.Exception" "global::System.Exception"
                   "void" "void"
                   (throw (ex-info "Unexpected labeled-control fixture type"
                                   {:type (.getQualifiedName reference)})))))} )

(defn- fixture-methods
  [resolved-model]
  (let [methods (.getElements (:model resolved-model) (TypeFilter. CtMethod))
        by-name (into {} (map (juxt #(.getSimpleName ^CtMethod %) identity)) methods)]
    (mapv (fn [name]
            (or (get by-name name)
                (throw (ex-info "Labeled-control fixture method is missing"
                                {:method name}))))
          method-names)))

(defn- translate-fixture
  [resolved-model]
  (let [context (java-body/context resolved-model (fixture-services))]
    (mapv (fn [^CtMethod method]
            (let [translation (java-body/translate context (.getBody method))]
              {:name (.getSimpleName method)
               :text (:text translation)
               :coverage (java/coverage-totals translation)}))
          (fixture-methods resolved-model))))

(defn- write-string!
  [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(defn- run-command
  [directory & command]
  (:output (process/run! {:directory directory :command command})))

(defn- run-java-oracle
  [^Path root source]
  (let [classes (paths/resolve-path root "java-classes")]
    (Files/createDirectories classes (make-array FileAttribute 0))
    (run-command root "javac" "-Xlint:all" "-Werror" "-d" classes source)
    (str/trim (run-command root "java" "-cp" classes "LabeledControlFlowFixture"))))

(defn- library-source
  [translations]
  (str "namespace Vibeformer.Runtime\n{\n"
       "  internal sealed class JavaLabeledControlFlowException(int branchId) "
       ": global::System.Exception\n  {\n"
       "    internal int BranchId { get; } = branchId;\n"
       "  }\n}\n\n"
       "public static class LabeledControlFlowFixture\n{\n"
       (str/join "\n\n"
                 (map (fn [{:keys [name text]}]
                        (str "  public static int " name "() "
                             (str/replace text "\n" "\n  ")))
                      translations))
       "\n}\n"))

(defn- package-project
  []
  (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
       "  <PropertyGroup>\n"
       "    <TargetFramework>net8.0</TargetFramework>\n"
       "    <Nullable>enable</Nullable>\n"
       "    <ImplicitUsings>disable</ImplicitUsings>\n"
       "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
       "    <PackageId>Vibeformer.LabeledControlFlow.Fixture</PackageId>\n"
       "    <Version>1.0.0-task</Version>\n"
       "  </PropertyGroup>\n"
       "</Project>\n"))

(defn- consumer-project
  [target-framework]
  (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
       "  <PropertyGroup>\n"
       "    <OutputType>Exe</OutputType>\n"
       "    <TargetFramework>" target-framework "</TargetFramework>\n"
       "    <Nullable>enable</Nullable>\n"
       "    <ImplicitUsings>disable</ImplicitUsings>\n"
       "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       "    <PackageReference Include=\"Vibeformer.LabeledControlFlow.Fixture\" "
       "Version=\"1.0.0-task\" />\n"
       "  </ItemGroup>\n"
       "</Project>\n"))

(defn- consumer-source
  []
  (str "public static class Program\n{\n"
       "  public static void Main()\n  {\n"
       "    global::System.Console.Write(\n"
       (str/join " + \"|\" +\n"
                 (map #(str "      LabeledControlFlowFixture." % "()")
                      method-names))
       ");\n  }\n}\n"))

(defn- run-package-only-consumer
  [^Path root translations]
  (let [runtime-output (run-command root "dotnet" "--list-runtimes")
        runtime-majors (keep (fn [line]
                               (when-let [[_ major]
                                          (re-find #"^Microsoft\.NETCore\.App (\d+)\."
                                                   line)]
                                 (parse-long major)))
                             (str/split-lines runtime-output))
        target-framework (str "net" (apply max runtime-majors) ".0")
        library (paths/resolve-path root "library")
        consumer (paths/resolve-path root "consumer")
        feed (paths/resolve-path root "feed")
        packages (paths/resolve-path root "packages")
        library-project (write-string! (paths/resolve-path library "Fixture.csproj")
                                       (package-project))
        _ (write-string! (paths/resolve-path library "LabeledControlFlowFixture.cs")
                         (library-source translations))
        consumer-project-file
        (write-string! (paths/resolve-path consumer "Consumer.csproj")
                       (consumer-project target-framework))
        _ (write-string! (paths/resolve-path consumer "Program.cs") (consumer-source))]
    (Files/createDirectories feed (make-array FileAttribute 0))
    (Files/createDirectories packages (make-array FileAttribute 0))
    (run-command root "dotnet" "pack" library-project "--nologo"
                 "--configuration" "Release" "--output" feed
                 "--verbosity:quiet" "-warnaserror")
    (run-command root "dotnet" "restore" consumer-project-file "--nologo"
                 "--packages" packages "--source" feed
                 "--ignore-failed-sources" "--verbosity:quiet")
    (run-command root "dotnet" "build" consumer-project-file "--nologo"
                 "--configuration" "Release" "--no-restore"
                 "--verbosity:quiet" "-warnaserror")
    (str/trim
     (run-command root "dotnet"
                  (paths/resolve-path consumer "bin" "Release" target-framework
                                      "Consumer.dll")))))

(defn- equivalent!
  [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Labeled-control differential mismatch"
                    {:kind :labeled-control-differential-mismatch
                     :expected expected
                     :actual actual})))
  actual)

(deftest live-spoon-labeled-control-flow-matches-jvm-through-a-package-only-consumer
  (let [source (fixture-path "LabeledControlFlowFixture.java")
        root (Files/createTempDirectory "vibeformer-labeled-control"
                                        (make-array FileAttribute 0))
        resolved (spoon/build-resolved-model! (paths/workspace-root)
                                              (discovery source))
        translations (translate-fixture resolved)
        java-output (run-java-oracle root source)
        package-output (run-package-only-consumer root translations)
        generated (library-source translations)]
    (is (= "7|30|11|2|3|7|125|137|16" java-output))
    (is (= java-output (equivalent! java-output package-output)))
    (doseq [{:keys [coverage]} translations]
      (is (pos? (:visited coverage)))
      (is (= (:visited coverage) (:covered coverage)))
      (is (= {:blocked 0
              :unsupported-elements 0
              :missing-mappings 0
              :missing-occurrences 0
              :fallback 0}
             (select-keys coverage [:blocked :unsupported-elements
                                    :missing-mappings :missing-occurrences
                                    :fallback]))))
    (is (str/includes? generated "goto __java_continue_0;"))
    (is (str/includes? generated "goto __java_break_0;"))
    (is (str/includes? generated "goto __java_break_1;"))
    (is (not (str/includes? generated "__break_same")))
    (is (= :labeled-control-differential-mismatch
           (:kind
            (ex-data
             (try
               (equivalent! java-output (str package-output "-perturbed"))
               nil
               (catch clojure.lang.ExceptionInfo error error))))))))

(deftest javac-and-spoon-correlate-invalid-nested-label-shadowing
  (let [source (fixture-path "InvalidNestedLabel.java")
        root (Files/createTempDirectory "vibeformer-invalid-label"
                                        (make-array FileAttribute 0))
        classes (paths/resolve-path root "classes")
        _ (Files/createDirectories classes (make-array FileAttribute 0))
        javac-error (try
                      (process/run! {:directory root
                                     :command ["javac" "-XDrawDiagnostics"
                                               "-d" classes source]})
                      nil
                      (catch clojure.lang.ExceptionInfo error error))
        spoon-error (try
                      (spoon/build-resolved-model! (paths/workspace-root)
                                                   (discovery source))
                      nil
                      (catch clojure.lang.ExceptionInfo error error))
        javac-output (:output (ex-data javac-error))
        [_ javac-line] (re-find #"InvalidNestedLabel\.java:(\d+):\d+:" javac-output)
        spoon-location (get-in (ex-data spoon-error) [:failure :location])]
    (testing "javac rejects active nested reuse but the valid fixture keeps disjoint reuse"
      (is (= :command-failed (:kind (ex-data javac-error))))
      (is (str/includes? javac-output "compiler.err.label.already.in.use: duplicate")))
    (testing "the live Spoon frontend fails on the same source line"
      (is (= :spoon-model-build-failed (:kind (ex-data spoon-error))))
      (is (= (parse-long javac-line) (:line spoon-location)))
      (is (= (.getCanonicalPath (.toFile source)) (:file spoon-location))))))
