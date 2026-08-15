(ns dripsharp.sqltrellis.test-suite
  "Complete deterministic adaptation of the governed JSqlParser test tree.

  Emission resolves production and test Java together so test bodies retain the
  same exact project symbols as production generation. Only the selected test
  declarations are emitted. Verification consumes generated ledgers and never
  invokes Maven, Java, Spoon, or a DripSharp checkout."
  (:require [clojure.string :as str]
            [dripsharp.csharp :as csharp]
            [dripsharp.harness :as harness]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as java-project]
            [dripsharp.java-test-adapters :as adapters]
            [dripsharp.java-translate :as java]
            [dripsharp.junit-xunit :as junit]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.spoon :as spoon]
            [dripsharp.sqltrellis.java-project :as sqltrellis]
            [dripsharp.util :as util])
  (:import [java.nio.file CopyOption Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtConstructor CtField CtMethod CtModifiable
            CtParameter CtType ModifierKind]))

(def ^:private revision "8a9479a05c75fcb73d0ed167a822b9b18ab7abaa")
(def ^:private suite-contract-file "adapted-tests/suite-contract.edn")
(def ^:private profile-file "targets/sqltrellis/profiles/core.edn")
(def ^:private build-input-file "targets/sqltrellis/maven-build-inputs.edn")
(def ^:private destination-file "targets/sqltrellis/destinations/core.edn")

(def ^:private benchmark-sources
  #{"src/test/java/net/sf/jsqlparser/benchmark/DynamicParserRunner.java"
    "src/test/java/net/sf/jsqlparser/benchmark/JSQLParserBenchmark.java"
    "src/test/java/net/sf/jsqlparser/benchmark/LatestClasspathRunner.java"
    "src/test/java/net/sf/jsqlparser/benchmark/SqlParserRunner.java"})

(def ^:private parser-grammar
  "src/main/jjtree/net/sf/jsqlparser/parser/JSqlParserCC.jjt")

(defn- fail! [message data]
  (throw
   (ex-info message
            (assoc data :kind :sqltrellis-test-suite-generation-failed))))

(defn- canonicalize [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key child]] [key (canonicalize child)]))
                       value)
    (set? value) (mapv canonicalize (sort-by pr-str value))
    (sequential? value) (mapv canonicalize value)
    :else value))

(defn- stable-text [value]
  (str (pr-str (canonicalize value)) "\n"))

(defn- relative-path [root path]
  (-> (paths/absolute root)
      (.relativize (paths/absolute path))
      str
      (str/replace "\\" "/")))

(defn- copy-file! [source destination]
  (Files/createDirectories (.getParent (paths/path destination))
                           (make-array FileAttribute 0))
  (Files/copy (paths/path source) (paths/path destination)
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
  destination)

(defn- discover-input [workspace-root]
  (let [profile (harness/read-profile workspace-root profile-file)
        project-root (paths/resolve-path workspace-root (:project-root profile))]
    {:profile profile
     :project-root project-root
     :input
     (maven/project-by-id!
      (maven/discover-reactor!
       {:workspace-root workspace-root
        :project-root project-root
        :selected-projects (:maven-selected-projects profile)
        :properties (:maven-properties profile)
        :build-input-contract build-input-file
        :lifecycle-phase (name (:maven-lifecycle-phase profile))})
      "com.github.jsqlparser:jsqlparser:5.3")}))

(defn- selected-test-sources [project-root input]
  (let [all (vec (:test-sources input))
        paths (into {} (map (juxt #(relative-path project-root %) identity)) all)
        observed-benchmarks (set (filter benchmark-sources (keys paths)))
        selected (->> paths
                      (remove (comp benchmark-sources key))
                      (sort-by key)
                      (mapv val))]
    (when-not (= 218 (count all))
      (fail! "SqlTrellis discovered test-source count changed"
             {:reason :sqltrellis-test-source-count-drift
              :expected 218 :actual (count all)}))
    (when-not (= benchmark-sources observed-benchmarks)
      (fail! "SqlTrellis benchmark exclusion inventory changed"
             {:reason :sqltrellis-benchmark-inventory-drift
              :expected (vec (sort benchmark-sources))
              :actual (vec (sort observed-benchmarks))}))
    (when-not (= 214 (count selected))
      (fail! "SqlTrellis selected test-source count changed"
             {:reason :sqltrellis-selected-test-source-count-drift
              :expected 214 :actual (count selected)}))
    selected))

(defn- selected-test-resources [project-root input]
  (let [resources (->> (:test-resources input)
                       (sort-by #(relative-path project-root %))
                       vec)]
    (when-not (= 295 (count resources))
      (fail! "SqlTrellis discovered test-resource count changed"
             {:reason :sqltrellis-test-resource-count-drift
              :expected 295 :actual (count resources)}))
    resources))

(defn- combined-input [input selected]
  (let [ordinary
        (remove
         #(str/ends-with? (str %) "/module-info.java")
         (concat (:production-sources input)
                 (:generated-production-sources input)))]
    {:schema-version 1
     :project-id "sqltrellis-complete-adapted-tests"
     :source-roots (vec (concat (:source-roots input)
                                (:generated-source-roots input)
                                (:test-source-roots input)))
     :resource-roots []
     :production-sources (vec (concat ordinary selected))
     :generated-production-sources []
     :production-resources []
     :test-source-roots []
     :test-resource-roots []
     :test-sources []
     :test-resources []
     :java-toolchain (:java-toolchain input)
     :project-dependencies []
     :external-dependencies (:test-external-dependencies input)
     :classpath-artifacts
     (vec (filter #(= :compile (:scope %))
                  (:test-classpath-artifacts input)))
     :test-project-dependencies []
     :test-external-dependencies []
     :test-classpath-artifacts []}))

(defn- source-entries [project-root selected]
  (mapv (fn [source]
          {:path (relative-path project-root source)
           :sha256 (util/sha256-file source)})
        selected))

(defn- fixture-relative [project-root resource]
  (let [path (relative-path project-root resource)
        prefix "src/test/resources/"]
    (when-not (str/starts-with? path prefix)
      (fail! "SqlTrellis test resource is outside src/test/resources"
             {:reason :sqltrellis-test-resource-path-drift :path path}))
    (subs path (count prefix))))

(defn- fixture-entries [project-root resources]
  (mapv (fn [resource]
          (let [relative (fixture-relative project-root resource)]
            {:source-path (relative-path project-root resource)
             :destination (str "Fixtures/" relative)
             :sha256 (util/sha256-file resource)}))
        resources))

(defn- support-fixture-entries [project-root]
  (let [source (paths/resolve-path project-root parser-grammar)]
    (when-not (paths/regular-file? source)
      (fail! "SqlTrellis parser grammar required by upstream tests is missing"
             {:reason :missing-sqltrellis-test-parser-grammar
              :path (str source)}))
    [{:source-path parser-grammar
      :destination parser-grammar
      :sha256 (util/sha256-file source)}]))

(defn- source-file [element]
  (some-> (spoon/source-location element) :file paths/absolute str))

(defn- selected-root-types [resolved-model selected]
  (let [selected-files (set (map (comp str paths/absolute) selected))
        roots (->> (java/project-roots resolved-model)
                   (filter #(contains? selected-files (source-file %)))
                   (sort-by #(.getQualifiedName ^CtType %))
                   vec)
        represented (set (keep source-file roots))
        missing (sort (remove represented selected-files))]
    (when (seq missing)
      (fail! "Selected SqlTrellis Java tests have no generated declaration root"
             {:reason :sqltrellis-test-source-without-root
              :missing missing}))
    roots))

(defn- method-index [plan]
  (into {}
        (map (juxt :id identity))
        (mapcat :methods (vals (:classes plan)))))

(defn- method-name! [methods id]
  (or (some-> (get methods id) :element ^CtMethod .getSimpleName)
      (fail! "SqlTrellis xUnit wrapper references an absent Java method"
             {:reason :missing-sqltrellis-test-method :method id})))

(defn- csharp-string [value]
  (str "\""
       (-> (str value)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\r" "\\r")
           (str/replace "\n" "\\n"))
       "\""))

(defn- rendered-type [context ^CtParameter parameter]
  (:text (csharp/render
          (java-library/type-node context (.getType parameter)))))

(defn- wrapper-name [test-case]
  (str "__Upstream_" (subs (util/sha256-text (:id test-case)) 0 16)))

(defn- provider-wrapper-name [test-case index]
  (str "__Data_" (subs (util/sha256-text
                        (str (:id test-case) ":" index))
                       0 16)))

(defn- parameter-source-seq [parameters]
  (cond
    (= :composite-sources (:kind parameters)) (:sources parameters)
    parameters [parameters]
    :else []))

(defn- wrapper-attributes [test-case]
  (let [parameters (:parameters test-case)]
    (if (= :member-data (:kind parameters))
      (let [options (cond-> []
                      (:display-name test-case)
                      (conj (str "DisplayName = "
                                 (csharp-string (:display-name test-case))))
                      (:disabled test-case)
                      (conj (str "Skip = "
                                 (csharp-string
                                  (get-in test-case [:disabled :reason])))))]
        [(str "[Xunit.Theory"
              (when (seq options) (str "(" (str/join ", " options) ")"))
              "]")
         (str "[Xunit.MemberData("
              (csharp-string (provider-wrapper-name test-case 0)) ")]")])
      (junit/xunit-attributes test-case))))

(defn- timeout-milliseconds [timeout]
  (when timeout
    (let [value (:value timeout)
          unit (:unit timeout)
          multiplier
          (case unit
            (:milliseconds {:field "field:java.util.concurrent.TimeUnit#MILLISECONDS"}) 1
            (:seconds {:field "field:java.util.concurrent.TimeUnit#SECONDS"}) 1000
            (:minutes {:field "field:java.util.concurrent.TimeUnit#MINUTES"}) 60000
            (:hours {:field "field:java.util.concurrent.TimeUnit#HOURS"}) 3600000
            (fail! "SqlTrellis JUnit timeout unit has no xUnit lowering"
                   {:reason :unsupported-sqltrellis-timeout-unit
                    :timeout timeout}))]
      (* value multiplier))))

(defn- mock-initializers [context test-case]
  (for [{:keys [field type] :as fixture}
        (get-in test-case [:mockito-fixture :fields])]
    (let [field-element (:field-element fixture)
          destination-type
          (:text (csharp/render
                  (java-library/type-node context (.getType field-element))))]
      (str "        this." field " = global::DripSharp.Testing.JavaMockito.Mock<"
           destination-type ">();\n"))))

(defn- render-wrapper [context methods test-case]
  (let [^CtMethod method (:method-element test-case)
        parameters (vec (.getParameters method))
        inline-row-width
        (letfn [(width [source]
                  (case (:kind source)
                    :inline-rows (reduce max 0 (map (comp count :arguments)
                                                    (:rows source)))
                    :composite-sources (reduce max 0 (map width (:sources source)))
                    0))]
          (width (:parameters test-case)))
        synthetic-count (max 0 (- inline-row-width (count parameters)))
        declarations
        (into
         (mapv (fn [^CtParameter parameter]
                 (str (rendered-type context parameter) " "
                      (java-library/identifier (.getSimpleName parameter))))
               parameters)
         (map #(str "object __upstreamArgument" %) (range synthetic-count)))
        arguments (mapv #(java-library/identifier
                          (.getSimpleName ^CtParameter %))
                        parameters)
        before (get-in test-case [:lifecycle :before-each])
        after (get-in test-case [:lifecycle :after-each])
        method-call (str "this." (.getSimpleName method)
                         "(" (str/join ", " arguments) ")")
        invocation
        (if-let [milliseconds (timeout-milliseconds (:timeout test-case))]
          (str "global::DripSharp.SqlTrellis.Tests.Support.RunWithTimeout(() => "
               method-call ", " milliseconds ");")
          (str method-call ";"))]
    (str (str/join "\n" (wrapper-attributes test-case)) "\n"
         "public void " (wrapper-name test-case)
         "(" (str/join ", " declarations) ")\n{\n"
         (apply str (mock-initializers context test-case))
         (apply str
                (map #(str "        this." (method-name! methods %)
                           "();\n")
                     before))
         "        try\n        {\n"
         "            " invocation "\n"
         "        }\n        finally\n        {\n"
         (apply str
                (map #(str "            this." (method-name! methods %)
                           "();\n")
                     after))
         "        }\n}")))

(defn- render-provider-wrapper [context test-case index source]
  (let [providers (:providers source)]
    (when-not (= 1 (count providers))
      (fail! "SqlTrellis @MethodSource must resolve to one provider"
             {:reason :unsupported-sqltrellis-method-source
              :case (:id test-case) :source source}))
    (let [provider (first providers)
          parameters (vec (.getParameters ^CtMethod (:method-element test-case)))
          adapted-arguments
          (map-indexed
           (fn [argument-index ^CtParameter parameter]
             (str "global::DripSharp.SqlTrellis.Tests.Support.TheoryArgument<"
                  (rendered-type context parameter)
                  ">(row[" argument-index "])"))
           parameters)]
      (str "public static global::System.Collections.Generic.IEnumerable<object[]> "
           (provider-wrapper-name test-case index) "()\n{\n"
           "    foreach (var value in " provider "())\n    {\n"
           "        object[] row = ((object?)value is object[] values)\n"
           "            ? values : new object[] { value! };\n"
           "        yield return new object[] { "
           (str/join ", " adapted-arguments) " };\n"
           "    }\n}"))))

(defn- render-class-lifecycle [methods class-plan]
  (let [before-all (get-in class-plan [:lifecycle :before-all])
        after-all (get-in class-plan [:lifecycle :after-all])]
    (when (seq after-all)
      (fail! "SqlTrellis suite requires an unimplemented @AfterAll lowering"
             {:reason :unsupported-sqltrellis-after-all
              :class (:name class-plan) :methods after-all}))
    (when (seq before-all)
      (str "private static readonly bool __UpstreamBeforeAll = "
           "__RunUpstreamBeforeAll();\n\n"
           "private static bool __RunUpstreamBeforeAll()\n{\n"
           (apply str
                  (map #(str "    " (method-name! methods %) "();\n")
                       before-all))
           "    return true;\n}"))))

(defn- adapted-target-member [base-translate-member context ^CtType owner member]
  (cond
    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.test.TestException" (.getQualifiedName owner))
         (= "getCause" (.getSimpleName ^CtMethod member)))
    (csharp/raw
     "public virtual global::System.Exception getCause() => this.cause;")

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.test.TestException" (.getQualifiedName owner))
         (= "printStackTrace" (.getSimpleName ^CtMethod member)))
    (let [parameters (vec (.getParameters ^CtMethod member))]
      (case (count parameters)
        0
        (csharp/raw
         (str "public virtual void printStackTrace() => "
              "this.printStackTrace(global::System.Console.Error);"))

        1
        (if (= "java.io.PrintWriter"
               (.getQualifiedName (.getType ^CtParameter (first parameters))))
          (csharp/raw
           (str "public virtual void printStackTrace("
                "global::System.IO.TextWriter writer) => "
                "global::DripSharp.SqlTrellis.Tests.Support.PrintStackTrace("
                "this, this.cause, writer);"))
          (csharp/raw
           (str "private void printStackTraceToPrintStream("
                "global::System.IO.TextWriter writer) => "
                "this.printStackTrace(writer);")))

        (base-translate-member context owner member)))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.test.TestUtils" (.getQualifiedName owner))
         (= "toReflectionString" (.getSimpleName ^CtMethod member))
         (= 2 (count (.getParameters ^CtMethod member))))
    (csharp/raw
     (str "public static string toReflectionString("
          "global::DripSharp.SqlTrellis.Statement.Statement stmt, "
          "bool includingASTNode) => "
          "global::DripSharp.SqlTrellis.Tests.Support.ReflectionToString("
          "stmt, includingASTNode);"))

    (and (instance? CtField member)
         (= "net.sf.jsqlparser.util.APISanitationTest"
            (.getQualifiedName owner))
         (= "CLASSES" (.getSimpleName ^CtField member)))
    (csharp/raw
     (str "private static readonly "
          "global::System.Collections.Generic.SortedSet<global::System.Type> "
          "CLASSES = new(global::System.Collections.Generic.Comparer<"
          "global::System.Type>.Create((left, right) => "
          "global::System.StringComparer.Ordinal.Compare("
          "left.FullName ?? left.Name, right.FullName ?? right.Name)));"))

    (and (instance? CtType member)
         (= "net.sf.jsqlparser.test.TestUtils" (.getQualifiedName owner))
         (= "ObjectTreeToStringStyle" (.getSimpleName ^CtType member)))
    (csharp/raw
     (str "private sealed class ObjectTreeToStringStyle\n{\n"
          "    public static readonly ObjectTreeToStringStyle Instance = new(false);\n"
          "    public static readonly ObjectTreeToStringStyle InstanceIncludingAst = new(true);\n"
          "    private readonly bool includingAstNode;\n"
          "    private ObjectTreeToStringStyle(bool includingAstNode) => "
          "this.includingAstNode = includingAstNode;\n"
          "    public bool IsNotANode(global::System.Type clazz) => "
          "!typeof(global::DripSharp.SqlTrellis.Parser.Node).IsAssignableFrom(clazz);\n"
          "    public bool IncludingAstNode => includingAstNode;\n}"))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.util.APISanitationTest"
            (.getQualifiedName owner))
         (= "findClasses" (.getSimpleName ^CtMethod member)))
    (case (count (.getParameters ^CtMethod member))
      1
      (csharp/raw
       (str "public static void findClasses(Visitor<string> visitor)\n{\n"
            "    foreach (string className in "
            "global::DripSharp.SqlTrellis.Tests.Support.SqlTrellisJavaTypeNames())\n"
            "    {\n        if (!visitor.visit(className)) return;\n    }\n}"))

      3
      (csharp/raw
       (str "private static bool findClasses(global::System.IO.FileInfo root, "
            "global::System.IO.FileInfo file, Visitor<string> visitor)\n{\n"
            "    foreach (string className in "
            "global::DripSharp.SqlTrellis.Tests.Support.SqlTrellisJavaTypeNames())\n"
            "    {\n        if (!visitor.visit(className)) return false;\n    }\n"
            "    return true;\n}"))

      (base-translate-member context owner member))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.util.APISanitationTest"
            (.getQualifiedName owner))
         (= "fields" (.getSimpleName ^CtMethod member)))
    (csharp/raw
     (str "private static global::DripSharp.Runtime.JavaStream<"
          "global::System.Reflection.FieldInfo> fields()\n{\n"
          "    var fields = new global::System.Collections.Generic.SortedSet<"
          "global::System.Reflection.FieldInfo>("
          "global::System.Collections.Generic.Comparer<"
          "global::System.Reflection.FieldInfo>.Create((left, right) => "
          "global::System.StringComparer.Ordinal.Compare("
          "left.ToString(), right.ToString())));\n"
          "    foreach (global::System.Type clazz in CLASSES)\n    {\n"
          "        if (clazz.IsEnum) continue;\n"
          "        foreach (var field in clazz.GetFields("
          "global::System.Reflection.BindingFlags.Instance | "
          "global::System.Reflection.BindingFlags.Static | "
          "global::System.Reflection.BindingFlags.Public | "
          "global::System.Reflection.BindingFlags.NonPublic | "
          "global::System.Reflection.BindingFlags.DeclaredOnly))\n        {\n"
          "            if ((global::DripSharp.Runtime.JavaCompat."
          "ReflectionFieldModifiers(field) & 16) != 16) fields.Add(field);\n"
          "        }\n    }\n"
          "    return global::DripSharp.Runtime.JavaCompat.Stream(fields);\n}"))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.util.APISanitationTest"
            (.getQualifiedName owner))
         (= "testExpressionList" (.getSimpleName ^CtMethod member)))
    (csharp/raw
     (str "internal virtual void testExpressionList("
          "global::System.Reflection.FieldInfo field)\n{\n"
          "    global::System.Type clazz = field.FieldType;\n"
          "    string fieldName = field.Name;\n"
          "    if (!global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase("
          "fieldName, \"$jacocoData\"))\n    {\n"
          "        bool isExpressionList = false;\n"
          "        foreach (global::System.Type boundClass in EXPRESSION_CLASSES)\n"
          "        {\n"
          "            if (typeof(global::System.Collections.ICollection)."
          "IsAssignableFrom(clazz) && "
          "!global::DripSharp.SqlTrellis.Tests.Support."
          "IsExpressionListType(clazz))\n"
          "                isExpressionList |= "
          "this.testGenericType(field, boundClass);\n"
          "        }\n"
          "        if (isExpressionList)\n"
          "            throwException(field, clazz, "
          "global::DripSharp.Runtime.JavaCompat.Concat("
          "fieldName, \" is an Expression List\"));\n"
          "    }\n}"))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.util.APISanitationTest"
            (.getQualifiedName owner))
         (= "testGenericType" (.getSimpleName ^CtMethod member)))
    (csharp/raw
     (str "internal virtual bool testGenericType("
          "global::System.Reflection.FieldInfo field, "
          "global::System.Type boundClass)\n{\n"
          "    global::System.Type fieldType = field.FieldType;\n"
          "    foreach (global::System.Type argument in "
          "fieldType.GetGenericArguments())\n    {\n"
          "        if (argument.IsAssignableFrom(boundClass)) return true;\n"
          "        if (argument.IsGenericParameter)\n        {\n"
          "            foreach (global::System.Type bound in "
          "argument.GetGenericParameterConstraints())\n"
          "                if (global::System.String.Equals("
          "bound.FullName ?? bound.Name, boundClass.FullName ?? boundClass.Name, "
          "global::System.StringComparison.Ordinal)) return true;\n"
          "        }\n    }\n"
          "    global::System.Type superclass = fieldType.BaseType;\n"
          "    if (superclass is not null)\n"
          "        foreach (global::System.Type argument in "
          "superclass.GetGenericArguments())\n"
          "            if (argument.IsAssignableFrom(boundClass)) return true;\n"
          "    return false;\n}"))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.statement.values.ValuesTest"
            (.getQualifiedName owner))
         (= "testObject" (.getSimpleName ^CtMethod member)))
    (csharp/raw
     (str "public virtual void testObject()\n{\n"
          "    var valuesStatement = new global::DripSharp.SqlTrellis."
          "Statement.Select.Values().addExpressions("
          "new global::DripSharp.SqlTrellis.Expression.StringValue(\"1\"), "
          "new global::DripSharp.SqlTrellis.Expression.StringValue(\"2\"));\n"
          "    valuesStatement.addExpressions("
          "global::DripSharp.Runtime.JavaCompat.AsList<"
          "global::DripSharp.SqlTrellis.Expression.Expression>("
          "new global::DripSharp.SqlTrellis.Expression.StringValue(\"3\"), "
          "new global::DripSharp.SqlTrellis.Expression.StringValue(\"4\")));\n"
          "    ((global::DripSharp.SqlTrellis.Statement.Statement)"
          "valuesStatement).accept<object>("
          "new global::DripSharp.SqlTrellis.Statement."
          "StatementVisitorAdapter<object>());\n}"))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.expression.StructTypeTest"
            (.getQualifiedName owner))
         (= "testStructTypeConstructorDuckDB"
            (.getSimpleName ^CtMethod member)))
    (csharp/raw
     (str "internal virtual void testStructTypeConstructorDuckDB()\n{\n"
          "    string sqlStr = \"SELECT { t:'abc',len:5}\";\n"
          "    var selectItems = global::DripSharp.Runtime.JavaCompat.ListOf<"
          "global::DripSharp.SqlTrellis.Statement.Select.SelectItem<"
          "global::DripSharp.SqlTrellis.Expression.Expression>>("
          "new global::DripSharp.SqlTrellis.Statement.Select.SelectItem<"
          "global::DripSharp.SqlTrellis.Expression.Expression>(\"abc\", \"t\"), "
          "new global::DripSharp.SqlTrellis.Statement.Select.SelectItem<"
          "global::DripSharp.SqlTrellis.Expression.Expression>(5, \"len\"));\n"
          "    var value = new global::DripSharp.SqlTrellis.Expression.StructType("
          "global::DripSharp.SqlTrellis.Expression.StructType.Dialect.DUCKDB, "
          "selectItems);\n"
          "    var select = new global::DripSharp.SqlTrellis.Statement.Select."
          "PlainSelect().withSelectItems(new global::DripSharp.SqlTrellis."
          "Statement.Select.SelectItem<global::DripSharp.SqlTrellis."
          "Expression.Expression>(value));\n"
          "    global::DripSharp.SqlTrellis.Test.TestUtils."
          "assertStatementCanBeDeparsedAs(select, sqlStr, true);\n}"))

    (and (instance? CtMethod member)
         (= "net.sf.jsqlparser.util.SelectUtilsTest"
            (.getQualifiedName owner))
         (= "testTableAliasIssue311" (.getSimpleName ^CtMethod member)))
    (csharp/raw
     (str "public virtual void testTableAliasIssue311()\n{\n"
          "    var table1 = new global::DripSharp.SqlTrellis.Schema.Table("
          "\"mytable1\");\n"
          "    table1.setAlias(new global::DripSharp.SqlTrellis.Expression."
          "Alias(\"tab1\"));\n"
          "    var table2 = new global::DripSharp.SqlTrellis.Schema.Table("
          "\"mytable2\");\n"
          "    table2.setAlias(new global::DripSharp.SqlTrellis.Expression."
          "Alias(\"tab2\"));\n"
          "    var columns = global::DripSharp.Runtime.JavaCompat.AsList<"
          "global::DripSharp.SqlTrellis.Expression.Expression>("
          "new global::DripSharp.SqlTrellis.Schema.Column(table1, \"col1\"), "
          "new global::DripSharp.SqlTrellis.Schema.Column(table1, \"col2\"), "
          "new global::DripSharp.SqlTrellis.Schema.Column(table1, \"col3\"), "
          "new global::DripSharp.SqlTrellis.Schema.Column(table2, \"b1\"), "
          "new global::DripSharp.SqlTrellis.Schema.Column(table2, \"b2\"));\n"
          "    var select = global::DripSharp.SqlTrellis.Util.SelectUtils."
          "buildSelectFromTableAndExpressions(table1, "
          "global::System.Linq.Enumerable.ToArray(columns));\n"
          "    var equalsTo = new global::DripSharp.SqlTrellis.Expression."
          "Operators.Relational.EqualsTo();\n"
          "    equalsTo.setLeftExpression(new global::DripSharp.SqlTrellis."
          "Schema.Column(table1, \"col1\"));\n"
          "    equalsTo.setRightExpression(new global::DripSharp.SqlTrellis."
          "Schema.Column(table2, \"b1\"));\n"
          "    var addJoin = global::DripSharp.SqlTrellis.Util.SelectUtils."
          "addJoin(select, table2, equalsTo);\n"
          "    addJoin.setLeft(true);\n"
          "    global::DripSharp.Testing.JavaAssertions.Equal("
          "\"SELECT tab1.col1, tab1.col2, tab1.col3, tab2.b1, tab2.b2 FROM "
          "mytable1 AS tab1 LEFT JOIN mytable2 AS tab2 ON tab1.col1 = "
          "tab2.b1\", select.ToString(), null);\n}"))

    (and (instance? CtConstructor member)
         (= "net.sf.jsqlparser.statement.AdaptersTest$Pair"
            (.getQualifiedName owner)))
    (csharp/raw
     (str "internal Pair(object left, object right)\n{\n"
          "    this.left = global::DripSharp.Runtime.JavaCompat."
          "CastReference<L>(left);\n"
          "    this.right = global::DripSharp.Runtime.JavaCompat."
          "CastReference<R>(right);\n}"))

    :else
    (base-translate-member context owner member)))

(defn- emitted-members [base-translate-member plan]
  (let [methods (method-index plan)
        cases-by-class (group-by :class (:cases plan))]
    (fn [context ^CtType owner members]
      (let [class-name (.getQualifiedName owner)
            cases (sort-by :id (get cases-by-class class-name))
            class-plan (get-in plan [:classes class-name])
            ordinary
            (mapv
             (fn [member]
               (try
                 (adapted-target-member base-translate-member context owner member)
                 (catch Throwable exception
                   (let [data (ex-data exception)
                         diagnostic (:diagnostic data)]
                     (swap!
                      (:semantic-errors context)
                      conj
                      {:source-file (source-file owner)
                       :type (.getQualifiedName owner)
                       :member (.getSimpleName ^spoon.reflect.declaration.CtNamedElement member)
                       :message (.getMessage exception)
                       :kind (:kind data)
                       :reason (:reason data)
                       :resolved (:resolved diagnostic)
                       :location (:location diagnostic)
                       :diagnostic-message (:message diagnostic)})
                     (csharp/raw "")))))
             members)
            providers
            (mapcat
             (fn [test-case]
               (keep-indexed
                (fn [index source]
                  (when (= :member-data (:kind source))
                    (csharp/raw
                     (render-provider-wrapper context test-case index source))))
                (parameter-source-seq (:parameters test-case))))
             cases)
            wrappers (mapv #(csharp/raw (render-wrapper context methods %)) cases)
            lifecycle (some-> (render-class-lifecycle methods class-plan)
                              csharp/raw)]
        (vec (concat ordinary providers wrappers (when lifecycle [lifecycle])))))))

(defn- mark-test-classes-public! [plan]
  (doseq [class-name (distinct (map :class (:cases plan)))
          :let [^CtType type (get-in plan [:classes class-name :type-element])]
          :when (and type (.isTopLevel type))]
    (.addModifier ^CtModifiable type ModifierKind/PUBLIC))
  plan)

(def ^:private javacc-test-types
  {"java.io.ObjectInputStream"
   ["global::DripSharp.SqlTrellis.Tests.JavaObjectInputStream"
    :sqltrellis-test.type/object-input-stream]
   "java.io.ObjectOutputStream"
   ["global::DripSharp.SqlTrellis.Tests.JavaObjectOutputStream"
    :sqltrellis-test.type/object-output-stream]
   "java.io.InvalidClassException"
   ["global::System.Runtime.Serialization.SerializationException"
    :sqltrellis-test.type/invalid-class-exception]
   "java.lang.reflect.Array"
   ["global::System.Array" :sqltrellis-test.type/reflection-array]
   "java.lang.reflect.Parameter"
   ["global::System.Reflection.ParameterInfo"
    :sqltrellis-test.type/reflection-parameter]
   "java.lang.reflect.Executable"
   ["global::System.Reflection.MethodBase"
    :sqltrellis-test.type/reflection-executable]
   "java.lang.reflect.Modifier"
   ["global::DripSharp.SqlTrellis.Tests.Support"
    :sqltrellis-test.type/reflection-modifier]
   "java.lang.StackTraceElement"
   ["global::DripSharp.SqlTrellis.Tests.JavaStackTraceElement"
    :sqltrellis-test.type/stack-trace-element]
   "java.lang.reflect.ParameterizedType"
   ["global::System.Type" :sqltrellis-test.type/parameterized-type]
   "java.lang.reflect.Type"
   ["global::System.Type" :sqltrellis-test.type/reflection-type]
   "java.lang.reflect.TypeVariable"
   ["global::System.Type" :sqltrellis-test.type/type-variable]
   "java.io.FilenameFilter"
   ["global::System.Func<global::System.IO.FileInfo, string, bool>"
    :sqltrellis-test.type/filename-filter]
   "org.javacc.jjtree.JJTree"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcJjTree"
    :sqltrellis-test.type/javacc-jjtree]
   "org.javacc.parser.JavaCCErrors"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcErrors"
    :sqltrellis-test.type/javacc-errors]
   "org.javacc.parser.JavaCCGlobals"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcGlobals"
    :sqltrellis-test.type/javacc-globals]
   "org.javacc.parser.JavaCCParser"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcParser"
    :sqltrellis-test.type/javacc-parser]
   "org.javacc.parser.RCharacterList"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRCharacterList"
    :sqltrellis-test.type/javacc-character-list]
   "org.javacc.parser.RChoice"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRChoice"
    :sqltrellis-test.type/javacc-choice]
   "org.javacc.parser.RJustName"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRJustName"
    :sqltrellis-test.type/javacc-just-name]
   "org.javacc.parser.ROneOrMore"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcROneOrMore"
    :sqltrellis-test.type/javacc-one-or-more]
   "org.javacc.parser.RSequence"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRSequence"
    :sqltrellis-test.type/javacc-sequence]
   "org.javacc.parser.RStringLiteral"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRStringLiteral"
    :sqltrellis-test.type/javacc-string-literal]
   "org.javacc.parser.RZeroOrMore"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRZeroOrMore"
    :sqltrellis-test.type/javacc-zero-or-more]
   "org.javacc.parser.RZeroOrOne"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRZeroOrOne"
    :sqltrellis-test.type/javacc-zero-or-one]
   "org.javacc.parser.RegularExpression"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcRegularExpression"
    :sqltrellis-test.type/javacc-regular-expression]
   "org.javacc.parser.Semanticize"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcSemanticize"
    :sqltrellis-test.type/javacc-semanticize]
   "org.javacc.parser.Token"
   ["global::DripSharp.SqlTrellis.Tests.JavaCcToken"
    :sqltrellis-test.type/javacc-token]
   "org.apache.commons.lang3.SerializationUtils"
   ["global::DripSharp.SqlTrellis.Tests.Support"
    :sqltrellis-test.type/commons-serialization-utils]
   "org.apache.commons.io.IOUtils"
   ["global::DripSharp.SqlTrellis.Tests.Support"
    :sqltrellis-test.type/commons-io-utils]
   "org.apache.commons.io.FileUtils"
   ["global::DripSharp.SqlTrellis.Tests.Support"
    :sqltrellis-test.type/commons-file-utils]
   "org.apache.commons.lang3.ArrayUtils"
   ["global::DripSharp.SqlTrellis.Tests.Support"
    :sqltrellis-test.type/commons-array-utils]
   "org.apache.commons.lang3.RandomStringUtils"
   ["global::DripSharp.SqlTrellis.Tests.Support"
    :sqltrellis-test.type/commons-random-string-utils]
   "java.util.UUID"
   ["global::System.Guid" :sqltrellis-test.type/uuid]
   "java.lang.ClassNotFoundException"
   ["global::System.TypeLoadException"
    :sqltrellis-test.type/class-not-found-exception]
   "java.sql.DriverManager"
   ["global::DripSharp.SqlTrellis.Tests.Support"
    :sqltrellis-test.type/jdbc-driver-manager]
   "org.opentest4j.AssertionFailedError"
   ["global::Xunit.Sdk.XunitException"
    :sqltrellis-test.type/assertion-failed-error]
   "org.opentest4j.ValueWrapper"
   ["global::DripSharp.SqlTrellis.Tests.JavaAssertionValue"
    :sqltrellis-test.type/assertion-value]
   "org.opentest4j.TestAbortedException"
   ["global::Xunit.Sdk.SkipException"
    :sqltrellis-test.type/test-aborted-exception]})

(def ^:private accepted-test-constructors
  #{"executable:java.io.InvalidClassException#<init>(java.lang.String)"
    "executable:java.io.File#<init>(java.lang.String)"
    "executable:java.io.File#<init>(java.io.File,java.lang.String)"
    "executable:java.io.FileWriter#<init>(java.io.File,boolean)"
    "executable:java.io.ObjectInputStream#<init>(java.io.InputStream)"
    "executable:java.io.ObjectOutputStream#<init>(java.io.OutputStream)"
    "executable:java.lang.ThreadLocal#<init>()"
    "executable:java.lang.StackTraceElement#<init>(java.lang.String,java.lang.String,java.lang.String,int)"
    "executable:java.sql.Time#<init>(long)"
    "executable:java.sql.Timestamp#<init>(long)"
    "executable:java.sql.Date#<init>(long)"
    "executable:org.javacc.jjtree.JJTree#<init>()"
    "executable:org.javacc.parser.JavaCCParser#<init>(java.io.InputStream)"
    "executable:java.io.PrintStream#<init>(java.io.OutputStream,boolean)"
    "executable:java.io.PrintWriter#<init>(java.io.Writer,boolean)"
    "executable:java.text.SimpleDateFormat#<init>(java.lang.String,java.util.Locale)"
    "executable:java.util.Date#<init>()"})

(def ^:private test-resolved-names
  {"field:org.javacc.parser.JavaCCGlobals#rexps_of_tokens" "RexpsOfTokens"
   "field:org.javacc.parser.RegularExpression#lhsTokens" "LhsTokens"
   "field:org.javacc.parser.ROneOrMore#lhsTokens" "LhsTokens"
   "field:org.javacc.parser.RSequence#units" "Units"
   "field:org.javacc.parser.RStringLiteral#image" "Image"
   "field:org.javacc.parser.RZeroOrMore#lhsTokens" "LhsTokens"
   "field:org.javacc.parser.RZeroOrOne#lhsTokens" "LhsTokens"
   "field:org.javacc.parser.RJustName#lhsTokens" "LhsTokens"
   "field:org.javacc.parser.Token#image" "Image"
   "field:java.util.logging.Level#ALL" "All"
   "field:java.util.logging.Level#INFO" "Info"
   "field:java.util.logging.Level#SEVERE" "Severe"
   "executable:org.javacc.jjtree.JJTree#main(java.lang.String[])" "Main"
   "executable:org.javacc.parser.JavaCCErrors#reInit()" "ReInit"
   "executable:org.javacc.parser.JavaCCParser#javacc_input()" "JavaccInput"
   "executable:org.javacc.parser.RChoice#getChoices()" "GetChoices"
   "executable:org.javacc.parser.Semanticize#start()" "Start"
   "executable:org.junit.jupiter.params.provider.Arguments#of(java.lang.Object[])"
   "Of"
   "executable:java.io.PrintStream#println(java.lang.Object)" "WriteLine"
   "executable:java.io.PrintStream#println(long)" "WriteLine"
   "executable:java.io.ObjectInputStream#readObject()" "ReadObject"
   "executable:java.io.ObjectOutputStream#writeObject(java.lang.Object)"
   "WriteObject"
   "executable:java.io.BufferedReader#close()" "Dispose"
   "executable:java.io.File#mkdirs()" "Mkdirs"
   "executable:java.io.File#getParentFile()" "GetParentFile"
   "executable:java.io.File#listFiles(java.io.FilenameFilter)" "ListFiles"
   "executable:java.lang.Class#forName(java.lang.String)" "ForName"
   "executable:java.lang.Class#getComponentType()" "GetComponentType"
   "executable:java.lang.Class#getCanonicalName()" "GetCanonicalName"
   "executable:java.lang.Class#getDeclaredFields()" "GetDeclaredFields"
   "executable:java.lang.Class#getDeclaringClass()" "GetDeclaringClass"
   "executable:java.lang.Class#getGenericSuperclass()" "GetGenericSuperclass"
   "executable:java.lang.Class#getInterfaces()" "GetInterfaces"
   "executable:java.lang.Class#getMethods()" "GetMethods"
   "executable:java.lang.Class#getResource(java.lang.String)" "GetResource"
   "executable:java.lang.Class#getResourceAsStream(java.lang.String)"
   "GetResourceAsStream"
   "executable:java.lang.Class#getSuperclass()" "GetSuperclass"
   "executable:java.lang.Class#isArray()" "IsArray"
   "executable:java.lang.Class#isEnum()" "IsEnum"
   "executable:java.lang.System#gc()" "Gc"
   "executable:java.lang.System#getProperty(java.lang.String)" "GetProperty"
   "executable:java.lang.Thread#getStackTrace()" "GetStackTrace"
   "executable:java.lang.StackTraceElement#getClassName()" "GetClassName"
   "executable:java.lang.StackTraceElement#getMethodName()" "GetMethodName"
   "executable:java.lang.Throwable#setStackTrace(java.lang.StackTraceElement[])"
   "SetStackTrace"
   "executable:java.lang.AbstractStringBuilder#setCharAt(int,char)" "SetCharAt"
   "executable:java.lang.ThreadLocal#get()" "Get"
   "executable:java.lang.ThreadLocal#set(java.lang.Object)" "Set"
   "executable:java.util.Random#nextBoolean()" "NextBoolean"
   "executable:java.util.Random#nextFloat()" "NextSingle"
   "executable:java.util.Random#nextDouble()" "NextDouble"
   "executable:java.util.Random#nextInt(int)" "Next"
   "executable:java.lang.String#concat(java.lang.String)" "Concat"
   "executable:java.util.function.Predicate#test(java.lang.Object)" "Invoke"
   "executable:java.util.logging.Logger#getAnonymousLogger()"
   "GetAnonymousLogger"
   "executable:java.sql.DriverManager#getConnection(java.lang.String)"
   "OpenH2Connection"
   "executable:java.sql.PreparedStatement#execute()" "Execute"
   "executable:java.time.LocalDateTime#now()" "Now"
   "executable:java.util.EnumSet#allOf(java.lang.Class)" "EnumValues"
   "executable:java.text.DateFormat#getDateTimeInstance()" "GetDateTimeInstance"
   "executable:java.lang.reflect.Array#newInstance(java.lang.Class,int)"
   "NewInstance"
   "executable:java.lang.reflect.Field#getDeclaringClass()" "GetDeclaringClass"
   "executable:java.lang.reflect.Field#toString()" "ToString"
   "executable:java.lang.reflect.Field#getGenericType()" "GetGenericType"
   "executable:java.lang.reflect.Field#getName()" "GetName"
   "executable:java.lang.reflect.Field#getType()" "GetType"
   "executable:java.lang.reflect.Method#getDeclaringClass()" "GetDeclaringClass"
   "executable:java.lang.reflect.Method#getName()" "GetName"
   "executable:java.lang.reflect.Method#getParameterCount()" "GetParameterCount"
   "executable:java.lang.reflect.Method#getParameters()" "GetParameters"
   "executable:java.lang.reflect.Executable#getParameters()" "GetParameters"
   "executable:java.lang.reflect.Method#getReturnType()" "GetReturnType"
   "executable:java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[])"
   "Invoke"
   "executable:java.lang.reflect.Method#toGenericString()" "ToGenericString"
   "executable:java.lang.reflect.Parameter#getType()" "GetType"
   "executable:java.lang.reflect.ParameterizedType#getActualTypeArguments()"
   "GetActualTypeArguments"
   "executable:java.lang.reflect.Type#getTypeName()" "GetTypeName"
   "executable:java.lang.reflect.TypeVariable#getBounds()" "GetBounds"
   "executable:java.util.logging.Logger#log(java.util.logging.Level,java.lang.String,java.lang.Object)"
   "LogFormatted"
   "executable:java.util.logging.Logger#log(java.util.logging.Level,java.lang.String,java.lang.Object[])"
   "LogFormatted"
   "executable:java.util.AbstractCollection#size()" "Size"
   "executable:org.apache.commons.lang3.SerializationUtils#clone(java.io.Serializable)"
   "DeepClone"
   "executable:org.apache.commons.io.IOUtils#readLines(java.io.Reader)"
   "ReadLines"
   "executable:org.apache.commons.io.IOUtils#toString(java.io.InputStream,java.nio.charset.Charset)"
   "ReadText"
   "executable:org.apache.commons.io.IOUtils#toString(java.net.URL,java.nio.charset.Charset)"
   "ReadText"
   "executable:org.apache.commons.io.IOUtils#write(java.lang.String,java.io.Writer)"
   "WriteText"
   "executable:org.apache.commons.io.FileUtils#readFileToString(java.io.File,java.nio.charset.Charset)"
   "ReadFileText"
   "executable:org.apache.commons.lang3.ArrayUtils#insert(int,java.lang.Object[],java.lang.Object[])"
   "ArrayInsert"
   "executable:org.apache.commons.lang3.RandomStringUtils#random(int)"
   "RandomString"
   "executable:org.opentest4j.AssertionFailedError#getActual()" "GetActual"
   "executable:org.opentest4j.AssertionFailedError#toString()" "ToString"
   "executable:org.opentest4j.ValueWrapper#getStringRepresentation()"
   "GetStringRepresentation"
   "executable:java.lang.Runtime#freeMemory()" "FreeMemory"
   "executable:java.lang.Runtime#gc()" "Gc"
   "executable:java.lang.Runtime#runFinalization()" "RunFinalization"
   "executable:java.lang.Runtime#availableProcessors()" "ProcessorCount"
   "executable:java.lang.Runtime#totalMemory()" "TotalMemory"
   "executable:java.lang.Throwable#printStackTrace(java.io.PrintStream)"
   "PrintStackTrace"
   "executable:java.text.DateFormat#format(java.util.Date)" "Format"
   "executable:java.util.concurrent.Executors#newCachedThreadPool()"
   "NewCachedThreadPool"
   "executable:java.util.concurrent.Executors#newFixedThreadPool(int)"
   "NewFixedThreadPool"
   "executable:java.util.UUID#randomUUID()" "NewGuid"
   "executable:java.util.UUID#hashCode()" "GetHashCode"})

(defn- enclosing-stream-return-argument [element]
  (loop [current element]
    (cond
      (nil? current) nil
      (instance? CtMethod current)
      (let [reference (.getType ^CtMethod current)]
        (when (= "java.util.stream.Stream" (.getQualifiedName reference))
          (first (.getActualTypeArguments reference))))
      (.isParentInitialized current) (recur (.getParent current))
      :else nil)))

(defn- stream-of-node [destination-context element arguments]
  (let [invocation-argument
        (some-> element .getType .getActualTypeArguments first)
        reference (or (when-not (instance? spoon.reflect.reference.CtTypeParameterReference
                                           invocation-argument)
                        invocation-argument)
                      (enclosing-stream-return-argument element))]
    (if reference
      (let [type-node (java-library/type-node destination-context reference)]
        (csharp/sequence-node
         [(csharp/raw "global::DripSharp.Runtime.JavaCompat.Stream<")
          type-node
          (csharp/raw ">(global::DripSharp.Runtime.JavaCompat.StreamOf<")
          type-node (csharp/raw ">(")
          (csharp/sequence-node arguments ", ")
          (csharp/raw "))")]))
      (csharp/sequence-node
       [(csharp/raw "global::DripSharp.Runtime.JavaCompat.Stream(")
        (csharp/raw "global::DripSharp.Runtime.JavaCompat.StreamOf(")
        (csharp/sequence-node arguments ", ")
        (csharp/raw "))")]))))

(defn- validation-capabilities-node [argument]
  (csharp/invocation
   (csharp/raw
    "global::DripSharp.SqlTrellis.Tests.Support.ValidationCapabilities")
   [argument]))

(defn- test-invocation
  [{:keys [destination-context element occurrence target-node arguments]}]
  (case (:key occurrence)
    "executable:java.lang.Runtime#availableProcessors()"
    (csharp/raw "global::System.Environment.ProcessorCount")

    "executable:java.lang.Runtime#totalMemory()"
    (csharp/raw
     "global::System.GC.GetGCMemoryInfo().TotalAvailableMemoryBytes")

    "executable:java.lang.Runtime#freeMemory()"
    (csharp/raw
     (str "(global::System.GC.GetGCMemoryInfo().TotalAvailableMemoryBytes - "
          "global::System.GC.GetTotalMemory(false))"))

    "executable:java.lang.Runtime#gc()"
    (csharp/invocation (csharp/raw "global::System.GC.Collect") [])

    "executable:java.lang.Runtime#runFinalization()"
    (csharp/invocation
     (csharp/raw "global::System.GC.WaitForPendingFinalizers") [])

    "executable:java.util.concurrent.Executors#newCachedThreadPool()"
    (csharp/invocation
     (csharp/raw "new global::DripSharp.Runtime.JavaExecutorService") [])

    "executable:java.util.concurrent.Executors#newFixedThreadPool(int)"
    (csharp/invocation
     (csharp/raw "new global::DripSharp.Runtime.JavaExecutorService") arguments)

    "executable:java.lang.Throwable#printStackTrace(java.io.PrintStream)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.PrintStackTrace")
     (into [target-node] arguments))

    "executable:org.junit.jupiter.params.provider.Arguments#of(java.lang.Object[])"
    (csharp/sequence-node
     [(csharp/raw "new object[] { ")
      (csharp/sequence-node arguments ", ")
      (csharp/raw " }")])

    "executable:java.lang.Class#forName(java.lang.String)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.ResolveSqlTrellisType")
     arguments)

    "executable:java.lang.System#gc()"
    (csharp/invocation (csharp/raw "global::System.GC.Collect") [])

    "executable:java.lang.System#getProperty(java.lang.String)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.JavaSystemProperty")
     arguments)

    "executable:java.lang.Thread#getStackTrace()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.CurrentStackTrace") [])

    "executable:java.lang.StackTraceElement#getClassName()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.StackClassName")
     [target-node])

    "executable:java.lang.StackTraceElement#getMethodName()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.StackMethodName")
     [target-node])

    "executable:java.lang.Throwable#setStackTrace(java.lang.StackTraceElement[])"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.SetStackTrace")
     (into [(csharp/raw "this")] arguments))

    "executable:java.io.File#listFiles(java.io.FilenameFilter)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.ListFiles")
     (into [target-node] arguments))

    "executable:java.io.File#mkdirs()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.JavaMkdirs")
     [target-node])

    "executable:java.io.File#getParentFile()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.ParentFile")
     [target-node])

    "executable:java.lang.ThreadLocal#get()"
    (csharp/invocation (csharp/member target-node "Get") [])

    "executable:java.lang.ThreadLocal#set(java.lang.Object)"
    (csharp/invocation (csharp/member target-node "Set") arguments)

    "executable:org.apache.commons.lang3.ArrayUtils#insert(int,java.lang.Object[],java.lang.Object[])"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.ArrayInsert")
     arguments)

    "executable:org.apache.commons.lang3.RandomStringUtils#random(int)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.RandomString")
     arguments)

    "executable:java.util.UUID#randomUUID()"
    (csharp/invocation (csharp/raw "global::System.Guid.NewGuid") [])

    "executable:java.util.UUID#hashCode()"
    (csharp/invocation (csharp/member target-node "GetHashCode") [])

    "executable:java.util.Random#nextBoolean()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.RandomBoolean")
     [target-node])

    "executable:java.util.Random#nextFloat()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.RandomFloat")
     [target-node])

    "executable:java.util.Random#nextDouble()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.RandomDouble")
     [target-node])

    "executable:java.util.Random#nextInt(int)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.RandomInt")
     (into [target-node] arguments))

    ("executable:java.util.stream.Stream#of(java.lang.Object)"
     "executable:java.util.stream.Stream#of(java.lang.Object[])")
    (stream-of-node destination-context element arguments)

    "executable:net.sf.jsqlparser.util.validation.Validation#validate(java.util.Collection,java.lang.String[])"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.SqlTrellis.Util.Validation.Validation.validate")
     (into [(validation-capabilities-node (first arguments))]
           (rest arguments)))

    "executable:net.sf.jsqlparser.util.validation.ValidationTestAsserts#validate(java.lang.String,int,int,java.util.Collection)"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.SqlTrellis.Util.Validation.ValidationTestAsserts.validate")
     (assoc (vec arguments) 3
            (validation-capabilities-node (nth arguments 3))))

    "executable:net.sf.jsqlparser.util.validation.Validator#getValidationErrors(net.sf.jsqlparser.util.validation.ValidationCapability[])"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.SqlTrellis.Tests.Support.FilterValidationErrors")
     (into [target-node] arguments))

    "executable:java.lang.String#concat(java.lang.String)"
    (csharp/binary "+" 50 target-node (first arguments))

    "executable:java.util.function.Predicate#test(java.lang.Object)"
    (csharp/invocation target-node arguments)

    "executable:java.util.logging.Logger#getAnonymousLogger()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaLogger.GetLogger")
     [(csharp/raw "\"anonymous\"")])

    "executable:java.sql.DriverManager#getConnection(java.lang.String)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.OpenH2Connection")
     arguments)

    "executable:java.sql.PreparedStatement#execute()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.Execute")
     [target-node])

    "executable:java.time.LocalDateTime#now()"
    (csharp/raw "global::System.DateTime.Now")

    "executable:java.util.EnumSet#allOf(java.lang.Class)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.EnumValues")
     arguments)

    "executable:java.lang.AbstractStringBuilder#setCharAt(int,char)"
    (csharp/sequence-node
     [(csharp/raw "(") target-node (csharp/raw ")[")
      (first arguments) (csharp/raw "] = ") (second arguments)])

    "executable:java.text.DateFormat#getDateTimeInstance()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.DefaultDateTimeFormat")
     [])

    "executable:java.lang.Class#getMethods()"
    (csharp/invocation (csharp/member target-node "GetMethods") [])

    "executable:java.lang.Class#getCanonicalName()"
    (csharp/sequence-node
     [(csharp/raw "(") (csharp/member target-node "FullName")
      (csharp/raw " ?? ") (csharp/member target-node "Name")
      (csharp/raw ")")])

    "executable:java.lang.Class#getResource(java.lang.String)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.ResourceUri")
     (into [target-node] arguments))

    "executable:java.lang.Class#getResourceAsStream(java.lang.String)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.ResourceStream")
     (into [target-node] arguments))

    "executable:java.lang.Class#getDeclaredFields()"
    (csharp/invocation
     (csharp/member target-node "GetFields")
     [(csharp/raw
       (str "global::System.Reflection.BindingFlags.Instance | "
            "global::System.Reflection.BindingFlags.Static | "
            "global::System.Reflection.BindingFlags.Public | "
            "global::System.Reflection.BindingFlags.NonPublic | "
            "global::System.Reflection.BindingFlags.DeclaredOnly"))])

    "executable:java.lang.Class#getInterfaces()"
    (csharp/invocation (csharp/member target-node "GetInterfaces") [])

    "executable:java.lang.Class#getSuperclass()"
    (csharp/member target-node "BaseType")

    "executable:java.lang.Class#getGenericSuperclass()"
    (csharp/member target-node "BaseType")

    "executable:java.lang.Class#getDeclaringClass()"
    (csharp/member target-node "DeclaringType")

    "executable:java.lang.Class#isEnum()"
    (csharp/member target-node "IsEnum")

    "executable:java.lang.Class#isArray()"
    (csharp/member target-node "IsArray")

    "executable:java.lang.Class#getComponentType()"
    (csharp/invocation (csharp/member target-node "GetElementType") [])

    "executable:java.lang.reflect.Array#newInstance(java.lang.Class,int)"
    (csharp/invocation (csharp/raw "global::System.Array.CreateInstance") arguments)

    ("executable:java.lang.reflect.Field#getType()"
     "executable:java.lang.reflect.Field#getGenericType()")
    (csharp/member target-node "FieldType")

    "executable:java.lang.reflect.Field#getName()"
    (csharp/member target-node "Name")

    "executable:java.lang.reflect.Field#getDeclaringClass()"
    (csharp/member target-node "DeclaringType")

    "executable:java.lang.reflect.Field#toString()"
    (csharp/invocation (csharp/member target-node "ToString") [])

    "executable:java.lang.reflect.Method#getName()"
    (csharp/member target-node "Name")

    "executable:java.lang.reflect.Method#getReturnType()"
    (csharp/member target-node "ReturnType")

    "executable:java.lang.reflect.Method#getParameterCount()"
    (csharp/sequence-node
     [(csharp/invocation (csharp/member target-node "GetParameters") [])
      (csharp/raw ".Length")])

    "executable:java.lang.reflect.Method#getParameters()"
    (csharp/invocation (csharp/member target-node "GetParameters") [])

    "executable:java.lang.reflect.Executable#getParameters()"
    (csharp/invocation (csharp/member target-node "GetParameters") [])

    "executable:java.lang.reflect.Method#getDeclaringClass()"
    (csharp/member target-node "DeclaringType")

    "executable:java.lang.reflect.Method#toGenericString()"
    (csharp/invocation (csharp/member target-node "ToString") [])

    "executable:java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[])"
    (csharp/invocation (csharp/member target-node "Invoke") arguments)

    "executable:java.lang.reflect.Parameter#getType()"
    (csharp/member target-node "ParameterType")

    "executable:java.lang.reflect.ParameterizedType#getActualTypeArguments()"
    (csharp/invocation (csharp/member target-node "GetGenericArguments") [])

    "executable:java.lang.reflect.TypeVariable#getBounds()"
    (csharp/invocation
     (csharp/member target-node "GetGenericParameterConstraints") [])

    "executable:java.lang.reflect.Type#getTypeName()"
    (csharp/sequence-node
     [(csharp/raw "(") (csharp/member target-node "FullName")
      (csharp/raw " ?? ") (csharp/member target-node "Name")
      (csharp/raw ")")])

    ("executable:java.util.logging.Logger#log(java.util.logging.Level,java.lang.String,java.lang.Object)"
     "executable:java.util.logging.Logger#log(java.util.logging.Level,java.lang.String,java.lang.Object[])")
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.LogFormatted")
     (into [target-node] arguments))

    "executable:java.util.AbstractCollection#size()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.CollectionCount")
     [target-node])

    "executable:org.opentest4j.AssertionFailedError#getActual()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.AssertionActual")
     [target-node])

    "executable:org.opentest4j.ValueWrapper#getStringRepresentation()"
    (csharp/invocation
     (csharp/member target-node "GetStringRepresentation") [])

    nil))

(defn- target-test-context [context]
  (let [base-name (:destination-resolved-name context)
        base-invocation (:destination-invocation-adapter context)
        base-constructor? (:destination-resolved-constructor? context)
        base-constructor (:destination-constructor-adapter context)]
    (assoc
     context
     :destination-type-mappings
     (merge javacc-test-types (:destination-type-mappings context))
     :destination-field-adaptations
     (merge
      {"field:java.lang.reflect.Modifier#FINAL"
       (fn [_] (csharp/raw "16"))}
      (:destination-field-adaptations context))
     :destination-anonymous-delegate-methods
     (merge {"java.io.FilenameFilter" "accept"
             "java.lang.Runnable" "run"
             "java.util.Comparator" "compare"}
            (:destination-anonymous-delegate-methods context))
     :destination-resolved-name
     (fn [destination-context occurrence reference]
       (or (get test-resolved-names (:key occurrence))
           (when base-name
             (base-name destination-context occurrence reference))))
     :destination-invocation-adapter
     (fn [event]
       (or (test-invocation event)
           (when base-invocation (base-invocation event))))
     :destination-resolved-constructor?
     (fn [destination-context occurrence reference]
       (or (contains? accepted-test-constructors (:key occurrence))
           (when base-constructor?
             (base-constructor? destination-context occurrence reference))))
     :destination-constructor-adapter
     (fn [{:keys [occurrence] :as event}]
       (or
        (case (:key occurrence)
          "executable:java.io.File#<init>(java.lang.String)"
          (csharp/invocation
           (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.TestFile")
           (:arguments event))

          "executable:java.io.File#<init>(java.io.File,java.lang.String)"
          (csharp/invocation
           (csharp/raw "new global::System.IO.FileInfo")
           [(csharp/invocation
             (csharp/raw "global::System.IO.Path.Combine")
             [(csharp/member (first (:arguments event)) "FullName")
              (second (:arguments event))])])

          "executable:java.io.FileWriter#<init>(java.io.File,boolean)"
          (csharp/invocation
           (csharp/raw "new global::System.IO.StreamWriter")
           [(csharp/member (first (:arguments event)) "FullName")
            (second (:arguments event))])

          "executable:java.io.PrintWriter#<init>(java.io.Writer,boolean)"
          (first (:arguments event))

          "executable:java.io.PrintStream#<init>(java.io.OutputStream,boolean)"
          (csharp/sequence-node
           [(csharp/raw "new global::System.IO.StreamWriter(")
            (first (:arguments event))
            (csharp/raw ", global::System.Text.Encoding.UTF8, 1024, true) { AutoFlush = ")
            (second (:arguments event))
            (csharp/raw " }")])

          "executable:java.util.Date#<init>()"
          (csharp/raw "global::System.DateTimeOffset.Now")

          "executable:java.lang.ThreadLocal#<init>()"
          (csharp/raw
           (str "global::DripSharp.Runtime.JavaThreadLocal<"
                "global::System.Collections.Generic.IDictionary<"
                "global::System.Type, object>>.WithInitial(() => default!)"))

          "executable:net.sf.jsqlparser.util.validation.Validation#<init>(java.util.Collection,java.lang.String[])"
          (csharp/invocation
           (csharp/raw
            "new global::DripSharp.SqlTrellis.Util.Validation.Validation")
           (into [(validation-capabilities-node (first (:arguments event)))]
                 (rest (:arguments event))))

          "executable:java.sql.Time#<init>(long)"
          (csharp/invocation
           (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.SqlTimeFromMillis")
           (:arguments event))

          "executable:java.sql.Timestamp#<init>(long)"
          (csharp/invocation
           (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.SqlTimestampFromMillis")
           (:arguments event))

          "executable:java.sql.Date#<init>(long)"
          (csharp/invocation
           (csharp/raw "global::DripSharp.SqlTrellis.Tests.Support.SqlDateFromMillis")
           (:arguments event))
          nil)
        (when base-constructor (base-constructor event)))))))

(defn- create-destination-context [workspace-root resolved-model plan]
  (let [bundle (sqltrellis/rule-bundle)
        configuration (java-project/read-configuration workspace-root
                                                       destination-file)
        create-template
        (get-in bundle [:rules :structural-declarations :create-template])
        create-context
        (get-in bundle [:rules :structural-declarations :create-context])
        base-translate-member
        (get-in bundle [:rules :structural-declarations :translate-member])
        template (create-template
                  resolved-model
                  {:runtime-capabilities (:runtime-capabilities bundle)})
        context
        (create-context
         {:template template
          :configuration configuration
          :resolved-model resolved-model
          :occurrence-index (java/resolved-occurrence-index resolved-model)
          :selected-declarations nil
          :public-api-type-keys #{}
          :public-api-declaration-keys #{}
          :runtime-capabilities (:runtime-capabilities bundle)
          :blocker-start 0
          :emit-members (emitted-members base-translate-member plan)})
        context (assoc context :semantic-errors (atom []))
        context (-> context target-test-context
                    adapters/compose-destination-context)
        holder (:ctx-holder template)
        _ (reset! holder context)
        context (assoc context
                       :body-context
                       (java-library/create-body-context
                        resolved-model holder (:runtime-capabilities bundle)))]
    (reset! holder context)
    {:bundle bundle :configuration configuration :context context}))

(defn- framework-call-rows [project-root resolved-model selected]
  (let [selected-files (set (map (comp str paths/absolute) selected))]
    (->> (:occurrences resolved-model)
         (filter #(contains? selected-files (some-> % :location :file
                                                    paths/absolute str)))
         (filter #(let [key (:key %)]
                    (or (str/starts-with? key "executable:org.junit.")
                        (str/starts-with? key "executable:org.assertj.")
                        (str/starts-with? key "executable:org.hamcrest.")
                        (str/starts-with? key "executable:org.mockito."))))
         (mapv (fn [occurrence]
                 {:key (:key occurrence)
                  :source (relative-path project-root
                                         (get-in occurrence [:location :file]))
                  :line (get-in occurrence [:location :line])
                  :column (get-in occurrence [:location :column])}))
         (sort-by (juxt :source :line :column :key))
         vec)))

(defn- helper-rows [project-root plan selected]
  (let [selected-files (set (map (comp str paths/absolute) selected))]
    (->> (:classes plan)
         vals
         (filter #(contains? selected-files
                             (some-> % :type-element source-file)))
         (mapcat (fn [class-plan]
                   (concat
                    [{:kind :type
                      :id (:name class-plan)
                      :source (relative-path
                               project-root
                               (source-file (:type-element class-plan)))}]
                    (map (fn [method]
                           {:kind :method :id (:id method)
                            :source (relative-path project-root
                                                   (get-in method [:source :file]))})
                         (:methods class-plan)))))
         (sort-by (juxt :source :kind :id))
         vec)))

(defn- accounting [project-root selected resources resolved-model plan]
  (let [sources (source-entries project-root selected)
        fixtures (fixture-entries project-root resources)
        support-fixtures (support-fixture-entries project-root)
        serializable-plan (junit/serializable-plan plan)
        framework-calls
        (framework-call-rows project-root resolved-model selected)
        helpers (helper-rows project-root plan selected)
        sections
        {:sources sources
         :fixtures fixtures
         :support-fixtures support-fixtures
         :plan serializable-plan
         :framework-calls framework-calls
         :helpers helpers}
        digests
        (into (sorted-map)
              (map (fn [[key value]]
                     [key (util/sha256-text (stable-text value))]))
              sections)]
    (assoc sections :digests digests)))

(defn- read-suite-contract [target-root]
  (let [file (paths/resolve-path target-root suite-contract-file)]
    (when-not (paths/regular-file? file)
      (fail! "SqlTrellis adapted-suite contract is missing"
             {:reason :missing-sqltrellis-test-suite-contract
              :path (str file)}))
    (let [contract (util/read-single-edn-string! (slurp (str file)))]
      (when-not (= #{:schema-version :target :revision :source-count
                     :fixture-count :case-count :digests}
                   (set (keys contract)))
        (fail! "SqlTrellis adapted-suite contract has missing or unknown fields"
               {:reason :invalid-sqltrellis-test-suite-contract
                :contract contract}))
      (when-not (and (= 1 (:schema-version contract))
                     (= :sqltrellis (:target contract))
                     (= revision (:revision contract))
                     (= 214 (:source-count contract))
                     (= 295 (:fixture-count contract)))
        (fail! "SqlTrellis adapted-suite contract identity changed"
               {:reason :invalid-sqltrellis-test-suite-contract
                :contract contract}))
      contract)))

(defn- verify-accounting! [contract accounting]
  (when-not (= (:digests contract) (:digests accounting))
    (fail! "SqlTrellis adapted-suite accounting changed"
           {:reason :sqltrellis-test-accounting-drift
            :expected (:digests contract)
            :actual (:digests accounting)}))
  (when-not (= (:case-count contract) (count (get-in accounting [:plan :cases])))
    (fail! "SqlTrellis adapted-suite case count changed"
           {:reason :sqltrellis-test-case-count-drift
            :expected (:case-count contract)
            :actual (count (get-in accounting [:plan :cases]))}))
  accounting)

(defn- namespace-for [namespace-policy context ^CtType type]
  (namespace-policy context type))

(defn- emitted-relative [^CtType type]
  (str "Adapted/"
       (str/replace (.getQualifiedName type) "." "/") ".cs"))

(defn- emit-java-tests! [generated-root roots bundle context]
  (reset! (:semantic-errors context) [])
  (let [emit-root (get-in bundle [:rules :structural-declarations :emit-root-node])
        namespace-policy (get-in bundle [:rules :namespace-policy
                                         :destination-namespace])
        results
        (mapv
         (fn [^CtType type]
           (try
             (let [relative (emitted-relative type)
                   output (paths/resolve-path generated-root relative)
                   node (emit-root context type)
                   text (:text (csharp/render node))
                   namespace (namespace-for namespace-policy context type)]
               (util/write-text!
                output
                (str "// SPDX-FileCopyrightText: 2026 Isak Sky\n"
                     "// SPDX-License-Identifier: Apache-2.0\n\n"
                     "#nullable disable\n"
                     "namespace " namespace ";\n\n" text "\n"))
               {:emission
                {:source-file (source-file type)
                 :type (.getQualifiedName type)
                 :generated relative}})
             (catch Throwable exception
               (let [data (ex-data exception)
                     diagnostic (:diagnostic data)]
                 {:error
                  {:source-file (source-file type)
                   :type (.getQualifiedName type)
                   :message (.getMessage exception)
                   :kind (:kind data)
                   :reason (:reason data)
                   :resolved (:resolved diagnostic)
                   :location (:location diagnostic)
                   :diagnostic-message (:message diagnostic)}}))))
         roots)
        errors (vec (concat @(:semantic-errors context)
                            (keep :error results)))]
    (when (seq errors)
      (fail! "SqlTrellis adapted test sources contain unsupported semantics"
             {:reason :unsupported-sqltrellis-test-semantics
              :error-count (count errors)
              :errors errors}))
    (mapv :emission results)))

(defn- fixture-targets []
  (str "<Project>\n"
       "  <PropertyGroup>\n"
       "    <NoWarn>$(NoWarn);CS0108;CS0168;CS8632;CS8765;xUnit1013;xUnit1014;xUnit1026</NoWarn>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       "    <None Update=\"Fixtures/**/*\">\n"
       "      <TargetPath>%(Identity)</TargetPath>\n"
       "      <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>\n"
       "    </None>\n"
       "    <None Update=\"src/main/jjtree/**/*\">\n"
       "      <TargetPath>%(Identity)</TargetPath>\n"
       "      <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>\n"
       "    </None>\n"
       "  </ItemGroup>\n</Project>\n"))

(defn- render-integrity-test [fixtures support-fixtures]
  (str
   "// SPDX-FileCopyrightText: 2026 Isak Sky\n"
   "// SPDX-License-Identifier: Apache-2.0\n\n"
   "namespace DripSharp.SqlTrellis.Tests;\n\n"
   "public sealed class GeneratedSuiteIntegrityTests\n{\n"
   "    [Xunit.Fact]\n"
   "    public void EveryUpstreamFixtureIsPresentAndPinned()\n    {\n"
   "        (string Path, string Sha256)[] fixtures = new[]\n        {\n"
   (apply str
          (for [{:keys [destination sha256]} fixtures]
            (str "            (" (csharp-string destination) ", "
                 (csharp-string sha256) "),\n")))
   "        };\n"
   "        Xunit.Assert.Equal(295, fixtures.Length);\n"
   "        (string Path, string Sha256)[] supportFixtures = new[]\n        {\n"
   (apply str
          (for [{:keys [destination sha256]} support-fixtures]
            (str "            (" (csharp-string destination) ", "
                 (csharp-string sha256) "),\n")))
   "        };\n"
   "        Xunit.Assert.Single(supportFixtures);\n"
   "        foreach ((string relative, string expected) in "
   "global::System.Linq.Enumerable.Concat(fixtures, supportFixtures))\n        {\n"
   "            string path = global::System.IO.Path.Combine(\n"
   "                global::System.AppContext.BaseDirectory, relative);\n"
   "            Xunit.Assert.True(global::System.IO.File.Exists(path), path);\n"
   "            string actual = global::System.Convert.ToHexString(\n"
   "                global::System.Security.Cryptography.SHA256.HashData(\n"
   "                    global::System.IO.File.ReadAllBytes(path))).ToLowerInvariant();\n"
   "            Xunit.Assert.Equal(expected, actual);\n"
   "        }\n    }\n}\n"))

(defn- render-provenance [project-root source-entries emission]
  (let [by-source (group-by :source-file emission)]
    (str "kind\tupstream-path\tsha256\tidentity\tgenerated-path\n"
         (str/join
          "\n"
          (for [{:keys [path sha256]} source-entries
                :let [source (str (paths/resolve-path project-root path))]
                generated (get by-source source)]
            (str "java-source\t" path "\t" sha256 "\t"
                 (:type generated) "\t" (:generated generated))))
         "\n")))

(defn- emit! [{:keys [workspace-root target-contract project-root]}]
  (let [generated-root project-root
        discovery (discover-input workspace-root)
        profile (:profile discovery)
        upstream-root (:project-root discovery)
        input (:input discovery)
        _ (sqltrellis/validate-project-input!
           {:workspace-root workspace-root
            :profile profile
            :project-input input})
        selected (selected-test-sources upstream-root input)
        resources (selected-test-resources upstream-root input)
        resolved-model (spoon/build-resolved-model!
                        workspace-root (combined-input input selected))
        plan (-> (junit/plan-suite resolved-model
                                   (adapters/junit-plan-options))
                 mark-test-classes-public!)
        accounting (accounting upstream-root selected resources
                               resolved-model plan)
        contract (read-suite-contract (:target-directory target-contract))
        _ (verify-accounting! contract accounting)
        roots (selected-root-types resolved-model selected)
        {:keys [bundle context]} (create-destination-context
                                  workspace-root resolved-model plan)
        emission (emit-java-tests! generated-root roots bundle context)
        fixtures (:fixtures accounting)
        support-fixtures (:support-fixtures accounting)]
    (util/write-text! (paths/resolve-path generated-root "GeneratedSuiteAssembly.cs")
                      (str "[assembly: Xunit.CollectionBehavior("
                           "DisableTestParallelization = true)]\n"))
    (util/write-text! (paths/resolve-path generated-root "JavaTestSupport.cs")
                      (adapters/support-source))
    (copy-file! (paths/resolve-path
                 (:target-directory target-contract)
                 "adapted-tests/SqlTrellisTestSupport.cs")
                (paths/resolve-path generated-root "SqlTrellisTestSupport.cs"))
    (doseq [[resource fixture] (map vector resources fixtures)]
      (copy-file! resource
                  (paths/resolve-path generated-root (:destination fixture))))
    (doseq [{:keys [source-path destination]} support-fixtures]
      (copy-file! (paths/resolve-path upstream-root source-path)
                  (paths/resolve-path generated-root destination)))
    (util/write-text! (paths/resolve-path generated-root "Directory.Build.targets")
                      (fixture-targets))
    (util/write-text! (paths/resolve-path generated-root
                                          "GeneratedSuiteIntegrityTests.cs")
                      (render-integrity-test fixtures support-fixtures))
    (util/write-text! (paths/resolve-path generated-root
                                          "JAVA-TEST-INVENTORY.edn")
                      (stable-text
                       {:schema-version 1
                        :target :sqltrellis
                        :revision revision
                        :source-count (count selected)
                        :fixture-count (count resources)
                        :case-count (count (:cases plan))
                        :accounting accounting
                        :emission (mapv #(dissoc % :source-file) emission)}))
    (util/write-text! (paths/resolve-path generated-root
                                          "JAVA-TEST-PROVENANCE.tsv")
                      (render-provenance upstream-root
                                         (:sources accounting) emission))
    (util/write-text! (paths/resolve-path generated-root "SUITE-CONTRACT.edn")
                      (stable-text contract))
    {:sources (count selected)
     :roots (count roots)
     :cases (count (:cases plan))
     :fixtures (count resources)
     :accounting (:digests accounting)
     :discovery (select-keys discovery [:project-root])}))

(defn- verify-generated! [project-root]
  (let [inventory-file (paths/resolve-path project-root
                                           "JAVA-TEST-INVENTORY.edn")
        contract-file (paths/resolve-path project-root "SUITE-CONTRACT.edn")]
    (when-not (and (paths/regular-file? inventory-file)
                   (paths/regular-file? contract-file))
      (fail! "Generated SqlTrellis suite ledgers are missing"
             {:reason :missing-generated-sqltrellis-test-ledger
              :inventory (str inventory-file)
              :contract (str contract-file)}))
    (let [inventory (util/read-single-edn-string!
                     (slurp (str inventory-file)))
          contract (util/read-single-edn-string!
                    (slurp (str contract-file)))
          accounting (:accounting inventory)]
      (when-not (and (= 1 (:schema-version inventory))
                     (= :sqltrellis (:target inventory))
                     (= revision (:revision inventory))
                     (= 214 (:source-count inventory))
                     (= 295 (:fixture-count inventory)))
        (fail! "Generated SqlTrellis suite inventory identity changed"
               {:reason :invalid-generated-sqltrellis-test-inventory
                :inventory (dissoc inventory :accounting :emission)}))
      (verify-accounting! contract accounting)
      (doseq [{:keys [destination sha256]} (:fixtures accounting)]
        (let [file (paths/resolve-path project-root destination)
              actual (when (paths/regular-file? file)
                       (util/sha256-file file))]
          (when-not (= sha256 actual)
            (fail! "Generated SqlTrellis test fixture is missing or changed"
                   {:reason :generated-sqltrellis-test-fixture-drift
                    :path destination :expected sha256 :actual actual}))))
      (doseq [{:keys [destination sha256]} (:support-fixtures accounting)]
        (let [file (paths/resolve-path project-root destination)
              actual (when (paths/regular-file? file)
                       (util/sha256-file file))]
          (when-not (= sha256 actual)
            (fail! "Generated SqlTrellis support fixture is missing or changed"
                   {:reason :generated-sqltrellis-support-fixture-drift
                    :path destination :expected sha256 :actual actual}))))
      {:sources (:source-count inventory)
       :cases (:case-count inventory)
       :fixtures (:fixture-count inventory)
       :accounting (:digests accounting)})))

(defn strategy!
  "Target-owned complete adapted-upstream strategy."
  [{:keys [phase] :as options}]
  (case phase
    :emit (emit! options)
    :verify (verify-generated! (:project-root options))
    (fail! "SqlTrellis adapted suite received an unsupported phase"
           {:reason :unsupported-sqltrellis-test-suite-phase
            :phase phase})))
