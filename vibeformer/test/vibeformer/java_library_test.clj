(ns vibeformer.java-library-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.java-library :as java-library]
            [vibeformer.java-project :as project-emission]
            [vibeformer.java-translate :as java]
            [vibeformer.java-types :as java-types]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util IdentityHashMap]
           [spoon.reflect.declaration CtClass]
           [spoon.reflect.reference CtTypeReference]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-java-library"
                             (make-array FileAttribute 0)))

(defn- model! [sources]
  (let [root (temp-directory)
        source-root (paths/resolve-path root "src/main/java")
        files
        (mapv
         (fn [[relative content]]
           (let [file (paths/resolve-path source-root relative)]
             (Files/createDirectories (.getParent file)
                                      (make-array FileAttribute 0))
             (Files/writeString file content (make-array OpenOption 0))
             file))
         sources)
        discovery {:java-home (paths/absolute (System/getProperty "java.home"))
                   :java-release 17
                   :preview-features false
                   :java-sources files
                   :resource-root (paths/resolve-path root "src/main/resources")
                   :resources []
                   :classpath []}]
    {:root root :discovery discovery
     :model (spoon/build-resolved-model! root discovery)}))

(defn- configuration
  ([] (configuration #{}))
  ([capabilities]
   {:schema-version 1
    :product-family :java-library
    :destination-bundle 'vibeformer.java-library/rule-bundle
    :project {:assembly-name "Example.Java.Library"
              :root-namespace "Example.Java.Library"
              :target-framework "net8.0"
              :nullable "enable"
              :implicit-usings false
              :warnings-as-errors true}
    :package {:id "Example.Java.Library"
              :version "1.0.0"
              :title "Example Java library"
              :description "Reusable ordinary Java declaration fixture."
              :authors "Vibeformer"
              :tags "java fixture"
              :project-url "https://example.invalid/java-library"
              :repository-url "https://example.invalid/java-library.git"
              :repository-type "git"}
    :output {:project-directory "generated/example-java-library"
             :source-directory "src"
             :resource-directory "resources"
             :project-file "Example.Java.Library.csproj"
             :source-map-file "source-map.edn"
             :diagnostics-file "diagnostics.edn"
             :manifest-file "generation-manifest.edn"
             :public-metadata-file "public-metadata.edn"
             :annotation-decisions-file "annotation-decisions.edn"}
    :namespaces {"example" "Example.Java.Library"}
    :namespace-prefixes {}
    :destination-capabilities capabilities
    :resources {}
    :resource-policy {:strategy :embedded-resource-preserve-path}
    :project-dependencies []
    :external-dependencies {}
    :public-surface {:strategy 'vibeformer.java-library/public-surface-strategy}}))

(defn- emit!
  ([fixture workers] (emit! fixture workers #{}))
  ([{:keys [root discovery model]} workers capabilities]
   (concurrency/call-with-executor
    {:worker-count workers}
    #(project-emission/emit-project!
      {:workspace-root (if (seq capabilities) (paths/absolute "..") root)
       :target (temp-directory)
       :discovery discovery
       :resolved-model model
       :configuration (configuration capabilities)
       :rule-bundle (java-library/rule-bundle)}))))

(defn- type-mapper []
  (get-in (java-library/rule-bundle) [:rules :resolved-mappings :type-node]))

(defn- annotation-decider []
  (get-in (java-library/rule-bundle)
          [:rules :resolved-mappings :annotation-decisions]))

(defn- mapping-context [model occurrence-index]
  (let [create-context (get-in (java-library/rule-bundle)
                               [:rules :structural-declarations :create-context])]
    (create-context {:configuration (configuration)
                     :resolved-model model
                     :occurrence-index occurrence-index})))

(defn- caught [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest neutral-declaration-shells-preserve-generics-and-resolved-inheritance
  (let [fixture
        (model! {"example/Marker.java"
                 (str "package example; @FunctionalInterface "
                      "public interface Marker<T> { "
                      "T convert(T value); }\n")
                 "example/Base.java"
                 (str "package example; public abstract class Base<T> "
                      "implements Marker<T> { "
                      "@Override public abstract T convert(T value); }\n")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-root (:project-root first)
        marker (paths/resolve-path first-root
                                   "src/Example/Java/Library/Marker.cs")
        base (paths/resolve-path first-root
                                 "src/Example/Java/Library/Base.cs")
        annotations
        (edn/read-string
         (slurp (str (paths/resolve-path first-root "annotation-decisions.edn"))))]
    (is (= :java-library (:rule-bundle first) (:rule-bundle second)))
    (is (= 2 (get-in first [:summary :compilation-units])
           (get-in second [:summary :compilation-units])))
    (is (= 4 (get-in first [:summary :declarations])
           (get-in second [:summary :declarations])))
    (is (= (slurp (str marker))
           (str "// <auto-generated />\n#nullable enable\n"
                "namespace Example.Java.Library;\n\n"
                "public interface Marker<T> {\n"
                "public T convert(T value);\n"
                "}\n")))
    (is (= (slurp (str base))
           (str "// <auto-generated />\n#nullable enable\n"
                "namespace Example.Java.Library;\n\n"
                "public abstract class Base<T> : "
                "global::Example.Java.Library.Marker<T> {\n"
                "public abstract T convert(T value);\n"
                "}\n")))
    (is (zero? (get-in first [:summary :missing-source-mappings])))
    (is (= #{[:csharp-functional-contract
              "annotation:java.lang.FunctionalInterface"]
             [:csharp-language-semantics "annotation:java.lang.Override"]}
           (set (map (juxt :strategy :resolved-key)
                     (:decisions annotations)))))
    (is (zero? (:exit
                (process/run! {:directory first-root
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest type-mapping-is-exact-product-neutral-and-fails-closed
  (let [fixture
        (model! {"example/Base.java"
                 (str "package example; public abstract class Base "
                      "extends java.util.TimerTask {}\n")})
        model (:model fixture)
        ^CtClass root (first (java/project-roots model))
        ^CtTypeReference superclass (.getSuperclass root)
        mapper (type-mapper)
        exact-context (mapping-context model (java/resolved-occurrence-index model))
        missing-context (mapping-context model (IdentityHashMap.))
        unsupported (caught #(mapper exact-context superclass))
        missing (caught #(mapper missing-context superclass))]
    (testing "an unmapped resolved JDK identity cannot fall back to its spelling"
      (is (= :unsupported-destination-rule (:kind (ex-data unsupported))))
      (is (identical? superclass (:source-element (ex-data unsupported))))
      (is (= "spoon.support.reflect.reference.CtTypeReferenceImpl"
             (get-in (ex-data unsupported) [:source-identity :frontend-class])))
      (is (= "superType"
             (get-in (ex-data unsupported) [:source-identity :role])))
      (is (pos? (get-in (ex-data unsupported) [:source-location :line]))))
    (testing "a live type reference absent from the occurrence index is rejected"
      (is (= :missing-resolved-occurrence (:kind (ex-data missing))))
      (is (identical? superclass (:source-element (ex-data missing))))
      (is (= :type (:expected (ex-data missing)))))
    (testing "the reusable mapping registry contains no destination product identity"
      (is (java-types/product-neutral? "pkl"))
      (is (java-types/product-neutral? "rawhttp"))
      (is (= ["global::System.Net.Sockets.Socket" :dotnet.type/socket]
             (java-types/mapping "java.net.Socket")))
      (is (nil? (java-types/mapping "java.util.TimerTask"))))))

(deftest unknown-resolved-annotation-fails-closed
  (let [fixture
        (model! {"example/Legacy.java"
                 "package example; @Deprecated public interface Legacy {}\n"})
        model (:model fixture)
        context (mapping-context model (java/resolved-occurrence-index model))
        error (caught #((annotation-decider) context))]
    (is (= :unsupported-destination-rule (:kind (ex-data error))))
    (is (= "annotation"
           (get-in (ex-data error) [:source-identity :role])))
    (is (pos? (get-in (ex-data error) [:source-location :line])))))

(deftest neutral-field-initializers-use-live-recursive-and-resolved-rules
  (let [fixture
        (model! {"example/Factory.java"
                 (str "package example; import java.util.Collections; "
                      "import java.util.List; public abstract class Factory { "
                      "public static final Factory VALUE = create().with(\"x\"); "
                      "public static final List<String> EMPTY = Collections.emptyList(); "
                      "public static native Factory create(); "
                      "public abstract Factory with(String value); }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        source (slurp (str (paths/resolve-path (:project-root first)
                                               "src/Example/Java/Library/Factory.cs")))]
    (is (str/includes? source
                       "public static readonly global::Example.Java.Library.Factory VALUE = global::Example.Java.Library.Factory.create().with(\"x\");"))
    (is (str/includes? source
                       "public static readonly global::System.Collections.Generic.IList<string> EMPTY = global::System.Array.Empty<string>();"))
    (is (= source
           (slurp (str (paths/resolve-path (:project-root second)
                                           "src/Example/Java/Library/Factory.cs")))))
    (is (= 2 (get-in first [:summary :executable-roots])
           (get-in second [:summary :executable-roots])))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))))

(deftest unmapped-jdk-field-initializer-fails-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.Collections; "
                      "import java.util.Map; public class Unsupported { "
                      "public static final Map<String, String> VALUE = "
                      "Collections.singletonMap(\"x\", \"y\"); }")})
        error (caught #(emit! fixture 1))]
    (is (some? error))
    (is (str/includes? (ex-message error)
                       "Java library executable or field has no neutral mapping"))))

(deftest neutral-constructors-translate-blocks-assignments-and-branches
  (let [fixture
        (model! {"example/Choice.java"
                 (str "package example; public final class Choice { "
                      "private final String value; "
                      "public Choice(String value, boolean selected) { "
                      "String chosen; "
                      "if (selected) { chosen = value; } "
                      "else { chosen = \"fallback\"; } "
                      "this.value = chosen; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Choice.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Choice.cs")))]
    (is (str/includes? first-source
                       (str "public Choice(string value, bool selected) {\n"
                            "string chosen;\n"
                            "if (selected) {\n"
                            "chosen = value;\n"
                            "} else {\n"
                            "chosen = \"fallback\";\n"
                            "}\n"
                            "this.value = chosen;\n}")))
    (is (= first-source second-source))
    (is (= 1 (get-in first [:summary :executable-roots])
           (get-in second [:summary :executable-roots])))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest unsupported-do-while-constructor-statements-fail-closed
  (let [fixture
        (model! {"example/Loop.java"
                 (str "package example; public final class Loop { "
                      "public Loop(boolean running) { do { } while (running); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= :unsupported-java-element
           (get-in (ex-data error) [:diagnostic :kind])))
    (is (str/includes? (get-in (ex-data error) [:diagnostic :message])
                       "CtDo"))
    (is (pos? (get-in (ex-data error) [:diagnostic :location :line])))))

(deftest unmapped-jdk-constructor-in-body-fails-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.LinkedHashMap; "
                      "public final class Unsupported { public Unsupported() { "
                      "new LinkedHashMap<String, String>(1, 0.75f, true); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= :translation-rule-failed
           (get-in (ex-data error) [:diagnostic :kind])))
    (is (str/includes? (get-in (ex-data error) [:diagnostic :message])
                       "Java library constructor has no neutral mapping"))
    (is (= "executable:java.util.LinkedHashMap#<init>(int,float,boolean)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-empty-collections-use-exact-resolved-jdk-contracts
  (let [fixture
        (model! {"example/Empty.java"
                 (str "package example; import java.util.ArrayList; "
                      "import java.util.Collections; import java.util.LinkedHashMap; "
                      "import java.util.List; import java.util.Map; "
                      "public final class Empty { "
                      "private final Map<String, Integer> immutable = Collections.emptyMap(); "
                      "private final Map<String, Integer> ordered = new LinkedHashMap<>(); "
                      "private final List<String> values = new ArrayList<>(2); "
                      "private final List<String> spare = new ArrayList<>(); "
                      "private final List<String> singleton = Collections.singletonList(\"one\"); "
                      "private static void copy(LinkedHashMap<String, Integer> target, "
                      "List<String> values, List<String> more) { "
                      "target.put(\"x\", 1); values.addAll(more); "
                      "target.getOrDefault(\"missing\", 0); "
                      "target.computeIfAbsent(\"new\", key -> 2); "
                      "values.add(\"item\"); "
                      "values.removeIf(value -> value.equalsIgnoreCase(\"drop\")); "
                      "target.remove(\"x\"); } "
                      "public static void fail() { throw new java.util.NoSuchElementException(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Empty.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Empty.cs")))]
    (is (str/includes?
         first-source
         (str "private readonly global::System.Collections.Generic.IDictionary<string, int> immutable = "
              "global::Vibeformer.Runtime.JavaCompat.EmptyMap<string, int>();")))
    (is (str/includes?
         first-source
         (str "private readonly global::System.Collections.Generic.IDictionary<string, int> ordered = "
              "new global::Vibeformer.Runtime.JavaLinkedHashMap<string, int>();")))
    (is (str/includes?
         first-source
         (str "private readonly global::System.Collections.Generic.IList<string> values = "
              "new global::System.Collections.Generic.List<string>(2);")))
    (is (str/includes?
         first-source
         (str "private readonly global::System.Collections.Generic.IList<string> spare = "
              "new global::System.Collections.Generic.List<string>();")))
    (is (str/includes?
         first-source
         (str "private readonly global::System.Collections.Generic.IList<string> singleton = "
              "global::Vibeformer.Runtime.JavaCompat.ListOf<string>(\"one\");")))
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.MapPut(target, \"x\", 1);\n"
              "global::Vibeformer.Runtime.JavaCompat.AddAll(values, more);\n"
              "global::Vibeformer.Runtime.JavaCompat.MapGetOrDefault(target, \"missing\", 0);\n"
              "global::Vibeformer.Runtime.JavaCompat.ComputeIfAbsent(target, \"new\", (key) => 2);\n"
              "global::Vibeformer.Runtime.JavaCompat.Add(values, \"item\");\n"
              "global::Vibeformer.Runtime.JavaCompat.RemoveIf(values, (value) => "
              "global::Vibeformer.Runtime.JavaCompat.EqualsIgnoreCase(value, \"drop\"));\n"
              "global::Vibeformer.Runtime.JavaCompat.MapRemove(target, \"x\");")))
    (is (str/includes?
         first-source
         "throw new global::System.InvalidOperationException();"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-map-copy-freeze-and-lambda-semantics-are-resolved
  (let [fixture
        (model! {"example/Frozen.java"
                 (str "package example; import java.util.ArrayList; "
                      "import java.util.Collections; import java.util.LinkedHashMap; "
                      "import java.util.List; import java.util.Map; "
                      "public final class Frozen { "
                      "private final Map<String, Item> items; "
                      "private final List<String> names; "
                      "public Frozen(Map<String, Item> source, List<String> names) { "
                      "Map<String, Item> copied = new LinkedHashMap<>(source); "
                      "copied.entrySet().forEach(entry -> "
                      "entry.setValue(entry.getValue().freeze())); "
                      "this.items = Collections.unmodifiableMap(copied); "
                      "this.names = Collections.unmodifiableList(new ArrayList<>(names)); } "
                      "public abstract static class Item { "
                      "public abstract Item freeze(); } }")})
        first (emit! fixture 1 #{:java-compat :java-regex-unicode})
        second (emit! fixture 3 #{:java-compat :java-regex-unicode})
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Frozen.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Frozen.cs")))]
    (is (str/includes?
         first-source
         (str "global::System.Collections.Generic.IDictionary<string, global::Example.Java.Library.Frozen.Item> copied = new global::Vibeformer.Runtime.JavaLinkedHashMap<string, global::Example.Java.Library.Frozen.Item>(source);\n"
              "global::Vibeformer.Runtime.JavaCompat.ForEach(global::Vibeformer.Runtime.JavaCompat.MapEntrySet(copied), (entry) => entry.SetValue(entry.Value.freeze()));\n"
              "this.items = global::Vibeformer.Runtime.JavaCompat.UnmodifiableMap(copied);\n"
              "this.names = global::Vibeformer.Runtime.JavaCompat.UnmodifiableList(new global::System.Collections.Generic.List<string>(names));")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest nearby-unmapped-collection-wrapper-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.Collections; "
                      "import java.util.Map; public final class Unsupported { "
                      "public Unsupported(Map<String, String> values) { "
                      "Collections.synchronizedMap(values); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= :translation-rule-failed
           (get-in (ex-data error) [:diagnostic :kind])))
    (is (= "executable:java.util.Collections#synchronizedMap(java.util.Map)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-map-compute-and-consumer-calls-use-exact-jdk-contracts
  (let [fixture
        (model! {"example/Indexes.java"
                 (str "package example; import java.util.HashMap; "
                      "import java.util.Map; import java.util.function.BiConsumer; "
                      "public final class Indexes { public static void emit("
                      "String key, BiConsumer<String, Integer> consumer) { "
                      "Map<String, Integer> indexes = new HashMap<>(); "
                      "int value = indexes.computeIfAbsent(key, k -> 0); "
                      "consumer.accept(key, value); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Indexes.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Indexes.cs")))]
    (is (str/includes?
         first-source
         (str "global::System.Collections.Generic.IDictionary<string, int> indexes = "
              "new global::System.Collections.Generic.Dictionary<string, int>();\n"
              "int value = global::Vibeformer.Runtime.JavaCompat.ComputeIfAbsent("
              "indexes, key, (k) => 0);\nconsumer(key, value);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-map-hash-and-string-builder-chains-preserve-java-semantics
  (let [fixture
        (model! {"example/Text.java"
                 (str "package example; import java.util.Map; "
                      "public final class Text { public static int hash("
                      "Map<String, String> values) { return values.hashCode(); } "
                      "public static int listHash(java.util.List<String> values) { "
                      "return values.hashCode(); } "
                      "public static String render(String name) { "
                      "StringBuilder builder = new StringBuilder(\"prefix\"); "
                      "builder.append(name).append(\": \"); "
                      "return builder.toString(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Text.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Text.cs")))]
    (is (str/includes?
         first-source
         (str "global::System.Text.StringBuilder builder = "
              "new global::System.Text.StringBuilder(\"prefix\");\n"
              "builder.Append(name).Append(\": \");\n"
              "return builder.ToString();")))
    (is (str/includes?
         first-source
         (str "public static int hash("
              "global::System.Collections.Generic.IDictionary<string, string> values) {\n"
              "return global::Vibeformer.Runtime.JavaCompat.HashCode(values);")))
    (is (str/includes?
         first-source
         (str "public static int listHash("
              "global::System.Collections.Generic.IList<string> values) {\n"
              "return global::Vibeformer.Runtime.JavaCompat.HashCode(values);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-capacity-set-add-and-list-size-use-direct-dotnet-contracts
  (let [fixture
        (model! {"example/Visited.java"
                 (str "package example; import java.util.HashSet; "
                      "import java.util.List; import java.util.Set; "
                      "public final class Visited { public static boolean addFirst("
                      "List<String> names, String name) { "
                      "Set<String> visited = new HashSet<>(names.size()); "
                      "return visited.add(name); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Visited.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Visited.cs")))]
    (is (str/includes?
         first-source
         (str "global::System.Collections.Generic.ISet<string> visited = "
              "new global::System.Collections.Generic.HashSet<string>(names.Count);\n"
              "return visited.Add(name);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-primitive-arrays-for-loops-and-conditionals-are-structural
  (let [fixture
        (model! {"example/Ascii.java"
                 (str "package example; public final class Ascii { "
                      "public static String upper(String text) { "
                      "char[] source = text.toCharArray(); "
                      "char[] result = new char[source.length]; "
                      "for (int i = 0; i < source.length; i++) { "
                      "result[i] = upper(source[i]); } return new String(result); } "
                      "private static char upper(char value) { "
                      "return ('a' <= value && value <= 'z') "
                      "? (char) (value - 32) : value; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Ascii.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Ascii.cs")))]
    (is (str/includes?
         first-source
         (str "char[] source = text.ToCharArray();\n"
              "char[] result = new char[source.Length];\n"
              "for (int i = 0; (i < source.Length); i++) {\n"
              "result[i] = global::Example.Java.Library.Ascii.upper(source[i]);\n}\n"
              "return new string(result);")))
    (is (str/includes?
         first-source
         (str "return ((('a' <= value) && (value <= 'z')) ? "
              "(char)((value - 32)) : value);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-string-encoding-and-output-stream-writes-preserve-java-bytes
  (let [fixture
        (model! {"example/Wire.java"
                 (str "package example; import java.io.ByteArrayInputStream; "
                      "import java.io.IOException; import java.io.InputStream; "
                      "import java.io.OutputStream; import java.nio.charset.StandardCharsets; "
                      "import java.util.OptionalInt; "
                      "public final class Wire { public static void write("
                      "OutputStream output, String value) throws IOException { "
                      "output.write(value.getBytes(StandardCharsets.US_ASCII)); "
                      "output.write(':'); "
                      "output.write(value.getBytes(StandardCharsets.ISO_8859_1)); "
                      "output.write(value.length()); } "
                      "public static InputStream input(String value) { return new "
                      "ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)); } "
                      "public static String roundTrip(String value) throws IOException { "
                      "java.io.ByteArrayOutputStream output = "
                      "new java.io.ByteArrayOutputStream(8); "
                      "output.write(value.getBytes(StandardCharsets.US_ASCII)); "
                      "return new String(output.toByteArray(), StandardCharsets.US_ASCII); } "
                      "public static int require(OptionalInt value) { "
                      "if (value.isPresent()) { return value.getAsInt(); } return -1; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Wire.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Wire.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.OutputStreamWrite(output, "
              "global::Vibeformer.Runtime.JavaCompat.StringGetBytes(value, "
              "global::Vibeformer.Runtime.JavaStandardCharsets.USASCII));\n"
              "global::Vibeformer.Runtime.JavaCompat.OutputStreamWrite(output, ':');\n"
              "global::Vibeformer.Runtime.JavaCompat.OutputStreamWrite(output, "
              "global::Vibeformer.Runtime.JavaCompat.StringGetBytes(value, "
              "global::Vibeformer.Runtime.JavaStandardCharsets.ISO88591));\n"
              "global::Vibeformer.Runtime.JavaCompat.OutputStreamWrite(output, value.Length);")))
    (is (str/includes?
         first-source
         (str "return global::Vibeformer.Runtime.JavaCompat.NewMemoryStream("
              "global::Vibeformer.Runtime.JavaCompat.StringGetBytes(value, "
              "global::Vibeformer.Runtime.JavaStandardCharsets.USASCII));")))
    (is (str/includes?
         first-source
         (str "return global::Vibeformer.Runtime.JavaCompat.NewString("
              "global::Vibeformer.Runtime.JavaCompat.ToSignedBytes(output), "
              "global::Vibeformer.Runtime.JavaStandardCharsets.USASCII);")))
    (is (str/includes?
         first-source
         (str "public static int require(int? value) {\n"
              "if (value.HasValue) {\nreturn value.Value;\n}\nreturn -1;")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-byte-array-output-stream-slice-write-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.IOException; "
                      "import java.io.ByteArrayOutputStream; public final class Unsupported { "
                      "public static void write(ByteArrayOutputStream output, byte[] bytes) "
                      "throws IOException { output.write(bytes, 0, bytes.length); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.ByteArrayOutputStream#write(byte[],int,int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-pipes-gzip-and-single-thread-executor-are-resolved
  (let [fixture
        (model! {"example/Pipes.java"
                 (str "package example; import java.io.IOException; "
                      "import java.io.OutputStream; import java.io.PipedInputStream; "
                      "import java.io.PipedOutputStream; import java.util.concurrent.ExecutorService; "
                      "import java.util.concurrent.Executors; import java.util.concurrent.Future; "
                      "import java.util.concurrent.TimeUnit; import java.util.zip.GZIPInputStream; "
                      "public final class Pipes { static void copy(byte[] compressed, "
                      "OutputStream output) throws Exception { PipedInputStream receiver = "
                      "new PipedInputStream(); PipedOutputStream sink = new PipedOutputStream(); "
                      "sink.connect(receiver); ExecutorService executor = "
                      "Executors.newSingleThreadExecutor(); Future<?> reader = executor.submit(() -> { "
                      "byte[] buffer = new byte[32]; try (GZIPInputStream gzip = "
                      "new GZIPInputStream(receiver)) { int count; while ((count = "
                      "gzip.read(buffer, 0, buffer.length)) >= 0) { output.write(buffer, 0, count); } "
                      "} catch (IOException error) { throw new RuntimeException(error); } }); "
                      "sink.write(compressed, 0, compressed.length); sink.close(); "
                      "reader.get(5, TimeUnit.SECONDS); executor.shutdownNow(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Pipes.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Pipes.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaPipedInputStream receiver = "
              "new global::Vibeformer.Runtime.JavaPipedInputStream();\n"
              "global::Vibeformer.Runtime.JavaPipedOutputStream sink = "
              "new global::Vibeformer.Runtime.JavaPipedOutputStream();\n"
              "sink.Connect(receiver);")))
    (is (str/includes?
         first-source
         "new global::Vibeformer.Runtime.JavaExecutorService(1)"))
    (is (str/includes?
         first-source
         (str "new global::System.IO.Compression.GZipStream(receiver, "
              "global::System.IO.Compression.CompressionMode.Decompress)")))
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaCompat.OutputStreamWrite(sink, compressed, 0, compressed.Length);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-connected-pipe-constructor-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.IOException; "
                      "import java.io.PipedInputStream; import java.io.PipedOutputStream; "
                      "public final class Unsupported { static PipedOutputStream create() "
                      "throws IOException { return new PipedOutputStream(new PipedInputStream()); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.PipedOutputStream#<init>(java.io.PipedInputStream)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-socket-executor-optional-atomic-and-runtime-failures-are-resolved
  (let [fixture
        (model! {"example/Network.java"
                 (str "package example; import java.io.ByteArrayOutputStream; "
                      "import java.io.IOException; "
                      "import java.io.InputStream; import java.io.OutputStream; "
                      "import java.net.Socket; import java.util.HashMap; import java.util.Optional; "
                      "import java.util.concurrent.Callable; import java.util.concurrent.Future; "
                      "import java.util.concurrent.ExecutorService; "
                      "import java.util.concurrent.TimeUnit; "
                      "import java.util.concurrent.atomic.AtomicBoolean; "
                      "public final class Network { static boolean use("
                      "Socket socket, ExecutorService executor, Optional<String> value, "
                      "AtomicBoolean called) throws IOException { "
                      "OutputStream output = socket.getOutputStream(); "
                      "InputStream input = socket.getInputStream(); output.flush(); "
                      "HashMap<String, String> values = new HashMap<>(4); "
                      "ByteArrayOutputStream buffer = new ByteArrayOutputStream(16); "
                      "buffer.writeTo(output); "
                      "executor.submit(() -> { called.compareAndSet(false, true); }); "
                      "if (called.get()) { socket.close(); } "
                      "if (!value.isPresent()) { output.close(); } "
                      "return input != null && values.size() == 0 && value.get().length() > 0; } "
                      "static String await(ExecutorService executor, Callable<String> callable) "
                      "throws Exception { Future<String> future = executor.submit(callable); "
                      "return future.get(5, TimeUnit.SECONDS); } "
                      "static RuntimeException failure(String message, Throwable cause) { "
                      "cause.printStackTrace(); "
                      "if (cause == null) { return new RuntimeException(message); } "
                      "return new RuntimeException(message, cause); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Network.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Network.cs")))]
    (is (str/includes?
         first-source
         (str "global::System.IO.Stream output = "
              "global::Vibeformer.Runtime.JavaCompat.SocketStream(socket);\n"
              "global::System.IO.Stream input = "
              "global::Vibeformer.Runtime.JavaCompat.SocketStream(socket);\n"
              "output.Flush();\n"
              "global::System.Collections.Generic.Dictionary<string, string> values = "
              "new global::System.Collections.Generic.Dictionary<string, string>(4);\n"
              "global::System.IO.MemoryStream buffer = new global::System.IO.MemoryStream(16);\n"
              "global::Vibeformer.Runtime.JavaCompat.MemoryStreamWriteTo(buffer, output);\n"
              "executor.Submit(() => {\n"
              "called.CompareAndSet(false, true);\n});\n"
              "if (called.Get()) {\nsocket.Close();\n}\n"
              "if (!value.IsPresent()) {\noutput.Dispose();\n}\n"
              "return (((input != null) && "
              "(values.Count == 0)) && "
              "(value.Get().Length > 0));")))
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaFuture<string> future = "
              "executor.Submit(callable);\n"
              "return future.Get(5, global::Vibeformer.Runtime.JavaTimeUnit.SECONDS);")))
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.PrintStackTrace(cause);\n"
              "if ((cause == null)) {\n"
              "return new global::System.Exception(message);\n}\n"
              "return new global::System.Exception(message, cause);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-executor-execute-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.concurrent.ExecutorService; "
                      "public final class Unsupported { public static void run("
                      "ExecutorService executor, Runnable action) { executor.execute(action); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.concurrent.Executor#execute(java.lang.Runnable)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-fixed-thread-factory-and-atomic-counter-are-resolved
  (let [fixture
        (model! {"example/Threads.java"
                 (str "package example; import java.util.concurrent.ExecutorService; "
                      "import java.util.concurrent.Executors; "
                      "import java.util.concurrent.atomic.AtomicInteger; "
                      "public final class Threads { "
                      "static ExecutorService create() { "
                      "AtomicInteger count = new AtomicInteger(1); "
                      "return Executors.newFixedThreadPool(2, runnable -> { "
                      "Thread thread = new Thread(runnable); "
                      "thread.setDaemon(true); "
                      "thread.setName(\"worker-\" + count.incrementAndGet()); "
                      "return thread; }); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Threads.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Threads.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaAtomicInteger count = "
              "new global::Vibeformer.Runtime.JavaAtomicInteger(1);\n"
              "return new global::Vibeformer.Runtime.JavaExecutorService(2, (runnable) => {\n"
              "global::Vibeformer.Runtime.JavaThread thread = "
              "new global::Vibeformer.Runtime.JavaThread(runnable);\n"
              "thread.SetDaemon(true);\n"
              "thread.SetName((\"worker-\" + count.IncrementAndGet()));\n"
              "return thread;\n});")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-atomic-get-and-increment-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.concurrent.atomic.AtomicInteger; "
                      "public final class Unsupported { static int next(AtomicInteger value) { "
                      "return value.getAndIncrement(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.concurrent.atomic.AtomicInteger#getAndIncrement()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-server-socket-lifecycle-is-resolved
  (let [fixture
        (model! {"example/Server.java"
                 (str "package example; import java.net.InetAddress; "
                      "import java.net.InetSocketAddress; import java.net.ServerSocket; "
                      "import java.net.Socket; "
                      "public final class Server { static Socket accept(ServerSocket server) "
                      "throws Exception { return server.accept(); } "
                      "static InetAddress remote(Socket socket) { return "
                      "((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress(); } "
                      "static void run() { new Thread(() -> { "
                      "while (true) { break; } }, \"worker\").start(); } "
                      "static boolean open(int port) throws Exception { "
                      "ServerSocket server = new ServerSocket(port); "
                      "if (!server.isClosed()) { server.close(); } "
                      "return !server.isClosed(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Server.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Server.cs")))]
    (is (str/includes?
         first-source
         (str "return server.Accept();\n}\n\n"
              "internal static global::System.Net.IPAddress remote("
              "global::System.Net.Sockets.Socket socket) {\n"
              "return global::Vibeformer.Runtime.JavaCompat.InetSocketAddressAddress("
              "(global::System.Net.IPEndPoint)(socket.RemoteEndPoint!));\n}\n\n"
              "internal static void run() {\n"
              "new global::Vibeformer.Runtime.JavaThread(() => {\n"
              "while (true) {\nbreak;\n}\n}, \"worker\").Start();\n}\n\n"
              "internal static bool open(int port) {\n"
              "global::Vibeformer.Runtime.JavaServerSocket server = "
              "new global::Vibeformer.Runtime.JavaServerSocket(port);\n"
              "if (!server.IsClosed()) {\nserver.Close();\n}\n"
              "return !server.IsClosed();")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-server-socket-backlog-constructor-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.net.ServerSocket; "
                      "public final class Unsupported { static ServerSocket open(int port) "
                      "throws Exception { return new ServerSocket(port, 12); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.net.ServerSocket#<init>(int,int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neighboring-thread-name-only-constructor-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static Thread create() { return new Thread(\"worker\"); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.Thread#<init>(java.lang.String)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-optional-control-flow-and-executor-lifecycle-are-resolved
  (let [fixture
        (model! {"example/Options.java"
                 (str "package example; import java.util.Optional; "
                      "import java.util.concurrent.ExecutorService; "
                      "import java.util.concurrent.TimeUnit; "
                      "public final class Options { static boolean check("
                      "Optional<String> value, boolean current) { "
                      "current |= value.map(\"x\"::equalsIgnoreCase).orElse(false); "
                      "value.ifPresent(text -> { }); "
                      "return value.orElseGet(() -> \"none\").equals(\"x\") && current; } "
                      "static boolean stop(ExecutorService executor) throws Exception { "
                      "executor.shutdown(); boolean stopped = "
                      "executor.awaitTermination(1, TimeUnit.SECONDS); "
                      "if (!stopped) { executor.shutdownNow(); } return stopped; } "
                      "static void interrupt() { Thread.currentThread().interrupt(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Options.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Options.cs")))]
    (is (str/includes?
         first-source
         (str "current |= value.Map((value0) => "
              "global::Vibeformer.Runtime.JavaCompat.EqualsIgnoreCase(\"x\", value0))"
              ".OrElse(false);\n"
              "value.IfPresent((text) => {});\n"
              "return (global::Vibeformer.Runtime.JavaCompat.Equals("
              "value.OrElseGet(() => \"none\"), \"x\") && current);")))
    (is (str/includes?
         first-source
         (str "executor.Shutdown();\n"
              "bool stopped = executor.AwaitTermination(1, "
              "global::Vibeformer.Runtime.JavaTimeUnit.SECONDS);\n"
              "if (!stopped) {\nexecutor.ShutdownNow();\n}\nreturn stopped;")))
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaThread.CurrentThread().Interrupt();"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-optional-filter-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.Optional; "
                      "public final class Unsupported { static Optional<String> filter("
                      "Optional<String> value) { return value.filter(x -> true); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.Optional#filter(java.util.function.Predicate)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-nullable-optional-supplier-and-uri-scheme-are-resolved
  (let [fixture
        (model! {"example/Uris.java"
                 (str "package example; import java.net.URI; import java.util.Optional; "
                      "public final class Uris { static String host(URI uri) { "
                      "if (\"http\".equalsIgnoreCase(uri.getScheme())) { return \"http\"; } "
                      "return Optional.ofNullable(uri.getHost()).orElseThrow(() -> "
                      "new RuntimeException(\"missing host\")); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Uris.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Uris.cs")))]
    (is (str/includes?
         first-source
         (str "if (global::Vibeformer.Runtime.JavaCompat.EqualsIgnoreCase(\"http\", "
              "global::Vibeformer.Runtime.JavaCompat.UriScheme(uri))) {\n"
              "return \"http\";\n}\n"
              "return global::Vibeformer.Runtime.JavaOptional<string>.OfNullable("
              "global::Vibeformer.Runtime.JavaCompat.UriHost(uri)).OrElseThrow("
              "() => new global::System.Exception(\"missing host\"));")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-map-compute-contract-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.Map; "
                      "public final class Unsupported { public static void run("
                      "Map<String, Integer> values) { "
                      "values.compute(\"x\", (key, value) -> 1); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.Map#compute(java.lang.Object,java.util.function.BiFunction)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neutral-method-references-stream-map-and-set-collection-are-resolved
  (let [fixture
        (model! {"example/References.java"
                 (str "package example; import java.util.Map; import java.util.Set; "
                      "import static java.util.stream.Collectors.toSet; "
                      "public final class References { "
                      "public static Set<String> normalize(Set<String> values) { "
                      "return values.stream().map(References::upper).collect(toSet()); } "
                      "private static String upper(String value) { return value.toUpperCase(); } "
                      "public void copy(Map<String, String> values) { values.forEach(this::with); } "
                      "private References with(String key, String value) { return this; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/References.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/References.cs")))]
    (is (str/includes?
         first-source
         (str "return global::Vibeformer.Runtime.JavaCompat.SetOfValues<string>("
              "global::Vibeformer.Runtime.JavaCompat.Map(values, "
              "global::Example.Java.Library.References.upper));")))
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.ForEach(values, "
              "(value0, value1) => { this.with(value0, value1); });")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-method-bodies-use-resolved-invocations-and-operators
  (let [fixture
        (model! {"example/Methods.java"
                 (str "package example; import java.net.URI; "
                      "public final class Methods { private Methods() {} "
                      "public static int normalizedPort(URI uri) { "
                      "if (uri.getPort() < 0 || uri.getPort() == 80) { "
                      "return 0; } else { return uri.getPort(); } } "
                      "public static boolean sameType(Object left, Object right) { "
                      "if (left == null || left.getClass() != right.getClass()) { "
                      "return false; } return true; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Methods.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Methods.cs")))]
    (is (str/includes?
         first-source
         (str "public static int normalizedPort(global::System.Uri uri) {\n"
              "if (((global::Vibeformer.Runtime.JavaCompat.UriPort(uri) < 0) || "
              "(global::Vibeformer.Runtime.JavaCompat.UriPort(uri) == 80))) {\n"
              "return 0;\n} else {\n"
              "return global::Vibeformer.Runtime.JavaCompat.UriPort(uri);\n}\n}")))
    (is (str/includes?
         first-source
         (str "if (((left == null) || (left.GetType() != right.GetType()))) {\n"
              "return false;\n}\nreturn true;")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-try-catch-casts-and-cause-access-are-structural
  (let [fixture
        (model! {"example/Failures.java"
                 (str "package example; import java.io.IOException; "
                      "public final class Failures { public static void unwrap("
                      "RuntimeException failure) throws IOException { "
                      "try { throw failure; } catch (RuntimeException caught) { "
                      "if (caught.getCause() instanceof IOException) { "
                      "throw (IOException) caught.getCause(); } throw caught; } } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Failures.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Failures.cs")))]
    (is (str/includes?
         first-source
         (str "try {\nthrow failure;\n} catch (global::System.Exception caught) {\n"
              "if ((caught.InnerException is global::System.IO.IOException)) {\n"
              "throw (global::System.IO.IOException)(caught.InnerException!);\n}\n"
              "throw caught;\n}")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest single-declared-try-resource-has-deterministic-lifetime
  (let [fixture
        (model! {"example/Resources.java"
                 (str "package example; import java.io.InputStream; "
                      "public final class Resources { public static int run(InputStream stream) throws Exception { "
                      "try (InputStream resource = stream) { return 1; } } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Resources.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Resources.cs")))]
    (is (str/includes? first-source
                       (str "using (global::System.IO.Stream resource = stream) {\n"
                            "return 1;\n}")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest multiple-try-resources-remain-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.InputStream; "
                      "public final class Unsupported { static void run(InputStream first, "
                      "InputStream second) throws Exception { "
                      "try (InputStream a = first; InputStream b = second) { } } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (str/includes? (get-in (ex-data error) [:diagnostic :message])
                       "Java try-with-resources requires one declared resource"))))

(deftest noncapturing-method-local-classes-are-hoisted-by-resolved-identity
  (let [fixture
        (model! {"example/Counter.java"
                 (str "package example; public final class Counter { "
                      "public int next() { class Index { "
                      "private int value = -1; "
                      "int increment() { return ++value; } } "
                      "Index index = new Index(); return index.increment(); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Counter.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Counter.cs")))]
    (is (str/includes?
         first-source
         (str "global::Example.Java.Library.Counter._1Index index = "
              "new global::Example.Java.Library.Counter._1Index();\n"
              "return index.increment();")))
    (is (str/includes?
         first-source
         (str "private class _1Index {\n"
              "private int value = -1;\n\n"
              "internal int increment() {\n"
              "return ++this.value;\n}\n}")))
    (is (= first-source second-source))
    (is (= 3 (get-in first [:summary :executable-roots])
           (get-in second [:summary :executable-roots])))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest capturing-method-local-classes-remain-fail-closed
  (let [fixture
        (model! {"example/Captured.java"
                 (str "package example; public final class Captured { "
                      "public int value(int offset) { class Local { "
                      "int get() { return offset; } } return new Local().get(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :unsupported-local-class-capture (:kind (ex-data error))))
    (is (= "type:example.Captured$1Local"
           (:source-identity (ex-data error))))
    (is (= ["spoon.support.reflect.declaration.CtParameterImpl"]
           (mapv #(get-in % [:identity :frontend-class])
                 (:captures (ex-data error)))))
    (is (= ["parameter"]
           (mapv #(get-in % [:identity :role])
                 (:captures (ex-data error)))))
    (is (pos? (get-in (ex-data error) [:source-location :line])))))

(deftest capturing-anonymous-iterators-are-hoisted-by-resolved-identity
  (let [fixture
        (model! {"example/Sequence.java"
                 (str "package example; import java.util.Iterator; "
                      "public final class Sequence { "
                      "private int advance(int value) { return value + 1; } "
                      "public Iterator<Integer> values(int start) { "
                      "return new Iterator<Integer>() { "
                      "private int current = start; "
                      "public boolean hasNext() { return current < 3; } "
                      "public Integer next() { return advance(current++); } "
                      "}; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Sequence.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Sequence.cs")))]
    (is (str/includes? first-source
                       "return new Anonymous_1_"))
    (is (str/includes? first-source
                       "private sealed class Anonymous_1_"))
    (is (str/includes? first-source
                       ": global::Vibeformer.Runtime.JavaIterator<int>"))
    (is (str/includes? first-source
                       "private readonly global::Example.Java.Library.Sequence __outer;"))
    (is (str/includes? first-source
                       "private readonly int __capture_0;"))
    (is (str/includes? first-source "public bool HasNext()"))
    (is (str/includes? first-source "public int Next()"))
    (is (str/includes? first-source "this.__outer.advance(this.current++)"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-anonymous-interfaces-remain-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "public Runnable value() { return new Runnable() { "
                      "public void run() { } }; } }")})
        error (caught #(emit! fixture 1))]
    (is (= :unsupported-destination-rule (:kind (ex-data error))))
    (is (str/includes? (.getMessage error)
                       "Only exact java.util.Iterator anonymous classes are supported"))))

(deftest signed-input-stream-reads-use-exact-java-eof-semantics
  (let [fixture
        (model! {"example/Reads.java"
                 (str "package example; import java.io.InputStream; "
                      "public final class Reads { public static int read("
                      "InputStream input, byte[] bytes) throws Exception { "
                      "int first = input.read(); "
                      "return first + input.read(bytes, 1, bytes.length - 1); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Reads.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Reads.cs")))]
    (is (str/includes? first-source
                       "int first = global::Vibeformer.Runtime.JavaCompat.InputStreamRead(input);"))
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.InputStreamRead("
              "input, bytes, 1, (bytes.Length - 1))")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-read-n-bytes-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.InputStream; "
                      "public final class Unsupported { static byte[] read("
                      "InputStream input) throws Exception { "
                      "return input.readNBytes(2); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.InputStream#readNBytes(int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest switch-fallthrough-and-labeled-break-preserve-control-flow
  (let [fixture
        (model! {"example/Switches.java"
                 (str "package example; public final class Switches { "
                      "public static int parse(int value) { outer: while (value >= 0) { "
                      "switch (value) { case 0: case 1: value++; break; "
                      "case 2: break outer; default: return value; } } return value; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Switches.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Switches.cs")))]
    (is (str/includes?
         first-source
         (str "case 0:\ncase 1:\nvalue++;\nbreak;\n"
              "case 2:\ngoto __java_break_0;\n"
              "default:\nreturn value;")))
    (is (str/includes? first-source "__java_break_0:;"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest switch-expressions-remain-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static int map(int value) { return switch (value) { "
                      "case 1 -> 2; default -> 3; }; } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (str/includes? (get-in (ex-data error) [:diagnostic :message])
                       "CtSwitchExpressionImpl"))))

(deftest neutral-format-radix-parse-and-java-trim-are-exact
  (let [fixture
        (model! {"example/TextParsing.java"
                 (str "package example; public final class TextParsing { "
                      "public static String render(int value) { "
                      "StringBuilder text = new StringBuilder(\" 0f \" ); "
                      "if (text.length() == 0) { return \"\"; } "
                      "int parsed = Integer.parseInt(text.toString().trim(), 16); "
                      "return String.format(\"%s:%s\", (char) value, parsed); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/TextParsing.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/TextParsing.cs")))]
    (is (str/includes?
         first-source
         (str "int parsed = global::Vibeformer.Runtime.JavaCompat.ParseInt("
              "global::Vibeformer.Runtime.JavaCompat.StringTrim(text.ToString()), 16);")))
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaCompat.JavaStringFormat(\"%s:%s\""))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-string-strip-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static String strip(String value) { return value.strip(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.String#strip()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest char-array-slice-string-construction-is-exact
  (let [fixture
        (model! {"example/Slices.java"
                 (str "package example; public final class Slices { "
                      "static String text(char[] chars, int count) { "
                      "return new String(chars, 0, count); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Slices.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Slices.cs")))]
    (is (str/includes? first-source "return new string(chars, 0, count);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest external-consumer-method-references-use-exact-target-semantics
  (let [fixture
        (model! {"example/Consumers.java"
                 (str "package example; import java.util.List; "
                      "import java.util.concurrent.atomic.AtomicReference; "
                      "import java.util.function.Consumer; "
                      "public final class Consumers { static void use("
                      "List<String> values, AtomicReference<String> reference) { "
                      "Consumer<String> add = values::add; "
                      "Consumer<String> set = reference::set; "
                      "add.accept(\"a\"); set.accept(\"b\"); reference.get(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Consumers.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Consumers.cs")))]
    (is (str/includes? first-source
                       "global::System.Action<string> add = (value0) => { values.Add(value0); };"))
    (is (str/includes? first-source
                       "global::System.Action<string> set = reference.Set;"))
    (is (str/includes? first-source "reference.Get();"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-list-remove-method-reference-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.List; "
                      "import java.util.function.Consumer; "
                      "public final class Unsupported { static Consumer<String> remove("
                      "List<String> values) { return values::remove; } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (str/includes? (get-in (ex-data error) [:diagnostic :resolved :key])
                       "#remove("))))

(deftest list-contains-uses-exact-java-equality-semantics
  (let [fixture
        (model! {"example/Membership.java"
                 (str "package example; import java.util.List; "
                      "public final class Membership { static boolean has("
                      "List<String> values, Object value) { "
                      "return values.contains(value); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Membership.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Membership.cs")))]
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.CollectionContains(values, value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-list-contains-all-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.List; "
                      "public final class Unsupported { static boolean hasAll("
                      "List<String> values, List<String> required) { "
                      "return values.containsAll(required); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.List#containsAll(java.util.Collection)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest radix-default-encoding-and-long-stream-rules-compose
  (let [fixture
        (model! {"example/Chunks.java"
                 (str "package example; import java.io.OutputStream; import java.util.List; "
                      "public final class Chunks { private final int amount; "
                      "Chunks(int amount) { this.amount = amount; } int size() { return amount; } "
                      "static void write(OutputStream output, Chunks chunk) throws Exception { "
                      "output.write(Integer.toString(chunk.size(), 16).getBytes()); "
                      "output.write(Integer.toString(chunk.size()).getBytes()); } "
                      "static long total(List<Chunks> chunks) { "
                      "return chunks.stream().mapToLong(Chunks::size).sum(); } "
                      "static byte[] copy(byte[] source, long length) { "
                      "byte[] result = new byte[Math.toIntExact(length)]; "
                      "System.arraycopy(source, 0, result, 0, source.length); "
                      "return result; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Chunks.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Chunks.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.OutputStreamWrite(output, "
              "global::Vibeformer.Runtime.JavaCompat.StringGetBytes("
              "global::Vibeformer.Runtime.JavaCompat.ToStringRadix(chunk.size(), 16), "
              "global::System.Text.Encoding.UTF8));")))
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.StringGetBytes("
              "global::Vibeformer.Runtime.JavaCompat.StringValueOf(chunk.size()), "
              "global::System.Text.Encoding.UTF8)")))
    (is (str/includes?
         first-source
         (str "return global::Vibeformer.Runtime.JavaCompat.Sum("
              "global::Vibeformer.Runtime.JavaCompat.MapToLong(chunks, "
              "(value0) => value0.size()));")))
    (is (str/includes?
         first-source
         (str "sbyte[] result = new sbyte[global::Vibeformer.Runtime.JavaCompat.ToIntExact(length)];\n"
              "global::Vibeformer.Runtime.JavaCompat.ArrayCopy(source, 0, result, 0, source.Length);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-unsigned-radix-conversion-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static String render(int value) { "
                      "return Integer.toUnsignedString(value, 16); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.Integer#toUnsignedString(int,int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neighboring-exact-math-operation-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static int increment(int value) { "
                      "return Math.incrementExact(value); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.Math#incrementExact(int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest uri-equality-uses-the-reusable-java-uri-contract
  (let [fixture
        (model! {"example/UrisEqual.java"
                 (str "package example; import java.net.URI; "
                      "public final class UrisEqual { static boolean same("
                      "URI left, Object right) { return left.equals(right); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/UrisEqual.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/UrisEqual.cs")))]
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.Equals(left, right);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-uri-comparison-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.net.URI; "
                      "public final class Unsupported { static int compare("
                      "URI left, URI right) { return left.compareTo(right); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.net.URI#compareTo(java.net.URI)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest implicit-jdk-stream-base-constructors-are-omitted
  (let [fixture
        (model! {"example/Sources.java"
                 (str "package example; import java.io.InputStream; "
                      "import java.io.OutputStream; abstract class Source extends InputStream {} "
                      "abstract class Sink extends OutputStream {}")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Source.cs")))
        first-sink
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Sink.cs")))]
    (is (not (str/includes? first-source "<init>")))
    (is (not (str/includes? first-sink "<init>")))
    (is (= first-source
           (slurp (str (paths/resolve-path (:project-root second)
                                           "src/Example/Java/Library/Source.cs")))))
    (is (= first-sink
           (slurp (str (paths/resolve-path (:project-root second)
                                           "src/Example/Java/Library/Sink.cs")))))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-filter-stream-base-constructor-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.FilterOutputStream; "
                      "import java.io.OutputStream; public final class Unsupported "
                      "extends FilterOutputStream { Unsupported(OutputStream out) { "
                      "super(out); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest concrete-byte-array-input-read-preserves-signed-stream-semantics
  (let [fixture
        (model! {"example/MemoryRead.java"
                 (str "package example; import java.io.ByteArrayInputStream; "
                      "public final class MemoryRead { static int read(byte[] bytes) { "
                      "ByteArrayInputStream input = new ByteArrayInputStream(bytes); "
                      "return input.read(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/MemoryRead.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/MemoryRead.cs")))]
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.InputStreamRead(input);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-byte-array-input-skip-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.ByteArrayInputStream; "
                      "public final class Unsupported { static long skip(byte[] bytes) { "
                      "return new ByteArrayInputStream(bytes).skip(1); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.ByteArrayInputStream#skip(long)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest uri-syntax-reason-and-index-preserve-java-diagnostics
  (let [fixture
        (model! {"example/UriFailure.java"
                 (str "package example; import java.net.URISyntaxException; "
                      "public final class UriFailure { static String describe("
                      "URISyntaxException failure) { return failure.getReason() + "
                      "\"@\" + failure.getIndex(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/UriFailure.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/UriFailure.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.UriSyntaxReason(failure)"
              " + \"@\") + global::Vibeformer.Runtime.JavaCompat.UriSyntaxIndex(failure)")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-uri-syntax-input-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.net.URISyntaxException; "
                      "public final class Unsupported { static String input("
                      "URISyntaxException failure) { return failure.getInput(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.net.URISyntaxException#getInput()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest resolved-pattern-matcher-full-match-is-reusable
  (let [fixture
        (model! {"example/Matching.java"
                 (str "package example; import java.util.regex.Pattern; "
                      "public final class Matching { static boolean matches("
                      "String expression, String value) { "
                      "Pattern pattern = Pattern.compile(expression); "
                      "return pattern.matcher(value).matches(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Matching.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Matching.cs")))]
    (is (str/includes?
         first-source
         (str "global::System.Text.RegularExpressions.Regex pattern = "
              "global::Vibeformer.Runtime.JavaCompat.CompileRegex(expression);\n"
              "return global::Vibeformer.Runtime.JavaCompat.RegexMatcher("
              "pattern, value).Matches();")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-matcher-find-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.regex.Pattern; "
                      "public final class Unsupported { static boolean find("
                      "Pattern pattern, String value) { return pattern.matcher(value).find(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.regex.Matcher#find()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest neighboring-pattern-flags-remain-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.regex.Pattern; "
                      "public final class Unsupported { static Pattern compile(String value) { "
                      "return Pattern.compile(value, Pattern.LITERAL); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.regex.Pattern#compile(java.lang.String,int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest optional-long-consumers-and-string-joining-are-exact
  (let [fixture
        (model! {"example/Joining.java"
                 (str "package example; import java.util.List; import java.util.OptionalLong; "
                      "public final class Joining { static String render("
                      "OptionalLong length, List<String> values) { "
                      "StringBuilder result = new StringBuilder(); "
                      "length.ifPresent(value -> result.append(Long.toString(value))); "
                      "return result.append(String.join(\",\", values)).toString(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Joining.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Joining.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.OptionalLongIfPresent(length, "
              "(value) => result.Append(global::Vibeformer.Runtime.JavaCompat.StringValueOf(value)));")))
    (is (str/includes?
         first-source
         (str "return result.Append(global::Vibeformer.Runtime.JavaCompat.StringJoin("
              "\",\", values)).ToString();")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-optional-long-or-else-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.OptionalLong; "
                      "public final class Unsupported { static long value("
                      "OptionalLong input) { return input.orElse(0); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.OptionalLong#orElse(long)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest optional-long-factories-map-to-nullable-values
  (let [fixture
        (model! {"example/LongOptions.java"
                 (str "package example; import java.util.OptionalLong; "
                      "public final class LongOptions { "
                      "static OptionalLong present(long value) { return OptionalLong.of(value); } "
                      "static OptionalLong missing() { return OptionalLong.empty(); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/LongOptions.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/LongOptions.cs")))]
    (is (str/includes? first-source "return value;"))
    (is (str/includes? first-source "return (long?)null;"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest pushback-input-stream-preserves-one-byte-replay
  (let [fixture
        (model! {"example/Pushback.java"
                 (str "package example; import java.io.InputStream; "
                      "import java.io.PushbackInputStream; public final class Pushback { "
                      "static int replay(InputStream source) throws Exception { "
                      "PushbackInputStream input = new PushbackInputStream(source); "
                      "int value = input.read(); input.unread(value); return input.read(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Pushback.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Pushback.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaPushbackInputStream input = "
              "new global::Vibeformer.Runtime.JavaPushbackInputStream(source);")))
    (is (str/includes? first-source "input.Unread(value);"))
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.InputStreamRead(input);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-sized-pushback-construction-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.InputStream; "
                      "import java.io.PushbackInputStream; public final class Unsupported { "
                      "static PushbackInputStream create(InputStream source) { "
                      "return new PushbackInputStream(source, 2); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.PushbackInputStream#<init>(java.io.InputStream,int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest static-initializer-blocks-emit-static-constructors
  (let [fixture
        (model! {"example/Initialized.java"
                 (str "package example; public final class Initialized { "
                      "private static final int VALUE; static { "
                      "int selected = 2; VALUE = selected; } "
                      "static int value() { return VALUE; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Initialized.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Initialized.cs")))]
    (is (str/includes?
         first-source
         (str "static Initialized() {\n"
              "int selected = 2;\n"
              "global::Example.Java.Library.Initialized.VALUE = selected;\n}")))
    (is (= first-source second-source))
    (is (= 1 (get-in first [:summary :declaration-kinds :initializer])))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest instance-initializer-blocks-remain-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "private int value; { value = 1; } }")})
        error (caught #(emit! fixture 1))]
    (is (= :unsupported-destination-rule (:kind (ex-data error))))
    (is (str/includes? (ex-message error)
                       "instance initializer lowering is not implemented"))))
