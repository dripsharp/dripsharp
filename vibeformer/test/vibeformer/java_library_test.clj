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
           [javax.tools ToolProvider]
           [spoon.reflect.declaration CtClass]
           [spoon.reflect.reference CtTypeReference]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-java-library"
                             (make-array FileAttribute 0)))

(defn- write-sources! [source-root sources]
  (mapv
   (fn [[relative content]]
     (let [file (paths/resolve-path source-root relative)]
       (Files/createDirectories (.getParent file)
                                (make-array FileAttribute 0))
       (Files/writeString file content (make-array OpenOption 0))
       file))
   sources))

(defn- model!
  ([sources] (model! sources {}))
  ([sources dependency-sources]
   (let [root (temp-directory)
         files (write-sources! (paths/resolve-path root "src/main/java") sources)
         dependency-files
         (write-sources! (paths/resolve-path root "dependency-src")
                         dependency-sources)
         classpath-root (paths/resolve-path root "dependency-classes")
         _ (when (seq dependency-files)
             (Files/createDirectories classpath-root
                                      (make-array FileAttribute 0))
             (let [exit (.run (ToolProvider/getSystemJavaCompiler)
                              nil nil nil
                              (into-array
                               String
                               (concat ["-d" (str classpath-root)]
                                       (map str dependency-files))))]
               (when-not (zero? exit)
                 (throw (ex-info "Fixture dependency compilation failed"
                                 {:kind :fixture-dependency-compilation-failed
                                  :exit exit})))))
         discovery
         {:schema-version 1
          :project-id "java-library-fixture"
          :source-roots [(paths/resolve-path root "src/main/java")]
          :resource-roots []
          :production-sources files
          :generated-production-sources []
          :production-resources []
          :java-toolchain
          {:home (paths/absolute (System/getProperty "java.home"))
           :release 17
           :preview-features? false}
          :project-dependencies []
          :external-dependencies []
          :classpath-artifacts
          (if (seq dependency-files)
            [{:scope :compile :path classpath-root}]
            [])}]
     {:root root :discovery discovery
      :model (spoon/build-resolved-model! root discovery)})))

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
       :project-input discovery
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
                "}\n\n"
                "internal sealed class __MarkerFunctionalAdapter<T> : "
                "global::Example.Java.Library.Marker<T> {\n"
                "private readonly global::System.Func<T, T> implementation;\n\n"
                "internal __MarkerFunctionalAdapter(global::System.Func<T, T> implementation) {\n"
                "this.implementation = implementation;\n"
                "}\n\n"
                "public T convert(T value) {\n"
                "return this.implementation(value);\n"
                "}\n"
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

(deftest project-functional-interfaces-adapt-lambdas-and-method-references
  (let [fixture
        (model! {"example/Handler.java"
                 (str "package example; @FunctionalInterface public interface Handler { "
                      "String apply(String value); }")
                 "example/Handlers.java"
                 (str "package example; public final class Handlers { "
                      "static Handler suffix(String suffix) { "
                      "return value -> value + suffix; } "
                      "static String decorate(String value) { return value + \"!\"; } "
                      "static Handler decorator() { return Handlers::decorate; } "
                      "static String run() { return suffix(\"?\").apply(\"a\") "
                      "+ decorator().apply(\"b\"); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-handler
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Handler.cs")))
        first-handlers
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Handlers.cs")))
        second-handlers
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Handlers.cs")))]
    (is (str/includes? first-handler
                       "internal sealed class __HandlerFunctionalAdapter"))
    (is (str/includes?
         first-handlers
         (str "return new global::Example.Java.Library.__HandlerFunctionalAdapter("
              "(value) => (value + suffix));")))
    (is (str/includes?
         first-handlers
         (str "return new global::Example.Java.Library.__HandlerFunctionalAdapter("
              "global::Example.Java.Library.Handlers.decorate);")))
    (is (= first-handlers second-handlers))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
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

(deftest resolved-nullable-annotations-emit-csharp-nullable-types
  (let [fixture
        (model! {"example/NullableValues.java"
                 (str "package example; import javax.annotation.Nullable; "
                      "public final class NullableValues { "
                      "@Nullable private String value; "
                      "public NullableValues(@Nullable String value) { "
                      "@Nullable String copy = value; this.value = copy; } "
                      "@Nullable public String get() { return value; } }")}
                {"javax/annotation/Nullable.java"
                 (str "package javax.annotation; import java.lang.annotation.*; "
                      "@Target({ElementType.FIELD, ElementType.METHOD, "
                      "ElementType.PARAMETER, ElementType.LOCAL_VARIABLE}) "
                      "public @interface Nullable {}")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/NullableValues.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/NullableValues.cs")))]
    (is (str/includes? first-source "private string? value;"))
    (is (str/includes? first-source "public NullableValues(string? value)"))
    (is (str/includes? first-source "string? copy = value!;"))
    (is (str/includes? first-source "public string? get()"))
    (is (= first-source second-source))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest java-null-literals-and-reference-field-defaults-preserve-jvm-state
  (let [fixture
        (model! {"example/NullState.java"
                 (str "package example; public final class NullState { "
                      "private String value; public NullState() {} "
                      "public String get() { return value; } "
                      "public void clear() { value = null; } "
                      "public static String absent() { return null; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/NullState.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/NullState.cs")))]
    (is (str/includes? first-source "private string value = null!;"))
    (is (str/includes? first-source "this.value = default!;"))
    (is (str/includes? first-source "return default!;"))
    (is (= first-source second-source))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest generic-null-and-cast-field-access-use-csharp-safe-representations
  (let [fixture
        (model! {"example/GenericNull.java"
                 (str "package example; public final class GenericNull { "
                      "static final class Holder { Exception cause; } "
                      "static <T> T absent() { return null; } "
                      "static Exception cause(Object value) { "
                      "return ((Holder) value).cause; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/GenericNull.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/GenericNull.cs")))]
    (is (str/includes? first-source "return default!;"))
    (is (str/includes?
         first-source
         "return ((global::Example.Java.Library.GenericNull.Holder)(value!)).cause;"))
    (is (= first-source second-source))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest private-nested-members-widen-for-java-nestmate-access
  (let [fixture
        (model! {"example/Nest.java"
                 (str "package example; public final class Nest { "
                      "public static String read() { "
                      "return new Holder().get() + Holder.value; } "
                      "private static final class Holder { "
                      "private static final String value = \"value\"; "
                      "private Holder() {} private String get() { return value; } } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Nest.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Nest.cs")))]
    (is (str/includes? first-source
                       "internal static readonly string value = \"value\";"))
    (is (str/includes? first-source "internal Holder()"))
    (is (str/includes? first-source "internal string get()"))
    (is (= first-source second-source))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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

(deftest do-while-statements-lower-structurally
  (let [fixture
        (model! {"example/Loop.java"
                 (str "package example; public final class Loop { "
                      "static int count(int limit) { int count = 0; "
                      "do { count++; } while (count < limit); return count; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Loop.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Loop.cs")))]
    (is (str/includes?
         first-source
         (str "do {\n"
              "count++;\n"
              "} while ((count < limit));")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest linked-hash-map-access-order-constructor-is-neutral-and-deterministic
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.LinkedHashMap; "
                      "import java.util.Map; "
                      "public final class Unsupported { public Unsupported() { "
                      "Map<String, String> value = "
                      "new LinkedHashMap<String, String>(1, 0.75f, true); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        relative "src/Example/Java/Library/Unsupported.cs"
        first-source (slurp (str (paths/resolve-path (:project-root first) relative)))
        second-source (slurp (str (paths/resolve-path (:project-root second) relative)))]
    (is (str/includes?
         first-source
         "new global::Vibeformer.Runtime.JavaLinkedHashMap<string, string>(1, 0.75F, true);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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

(deftest synchronized-map-wrapper-is-a-neutral-identity-adaptation
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.Collections; "
                      "import java.util.Map; public final class Unsupported { "
                      "private final Map<String, String> values; "
                      "public Unsupported(Map<String, String> values) { "
                      "this.values = Collections.synchronizedMap(values); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        relative "src/Example/Java/Library/Unsupported.cs"
        first-source (slurp (str (paths/resolve-path (:project-root first) relative)))
        second-source (slurp (str (paths/resolve-path (:project-root second) relative)))]
    (is (str/includes? first-source "this.values = values;"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-map-compute-and-consumer-calls-use-exact-jdk-contracts
  (let [fixture
        (model! {"example/Indexes.java"
                 (str "package example; import java.util.HashMap; "
                      "import java.util.Map; import java.util.function.BiConsumer; "
                      "import java.util.function.BiFunction; "
                      "public final class Indexes { public static void emit("
                      "String key, BiConsumer<String, Integer> consumer) { "
                      "Map<String, Integer> indexes = new HashMap<>(); "
                      "int value = indexes.computeIfAbsent(key, k -> 0); "
                      "consumer.accept(key, value); } "
                      "public static int apply(String key, int value, "
                      "BiFunction<String, Integer, Integer> function) { "
                      "return function.apply(key, value); } }")})
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
    (is (str/includes? first-source "return function(key, value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-bifunction-composition-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.function.BiFunction; "
                      "import java.util.function.Function; public final class Unsupported { "
                      "static BiFunction<String, Integer, String> compose("
                      "BiFunction<String, Integer, Integer> source, Function<Integer, String> next) { "
                      "return source.andThen(next); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.function.BiFunction#andThen(java.util.function.Function)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest mutable-simple-map-entries-use-a-reusable-reference-entry
  (let [fixture
        (model! {"example/Entries.java"
                 (str "package example; import java.util.AbstractMap; import java.util.Map; "
                      "public final class Entries { static Map.Entry<String, String> create("
                      "String key, String value) { Map.Entry<String, String> entry = "
                      "new AbstractMap.SimpleEntry<>(key, value); entry.setValue(value); "
                      "return entry; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Entries.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Entries.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaMapEntry<string, string> entry = "
              "new global::Vibeformer.Runtime.JavaSimpleEntry<string, string>(key, value);")))
    (is (str/includes? first-source "entry.SetValue(value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-map-hash-and-string-builder-chains-preserve-java-semantics
  (let [fixture
        (model! {"example/Text.java"
                 (str "package example; import java.util.Map; import java.util.Optional; "
                      "public final class Text { public static int hash("
                      "Map<String, String> values) { return values.hashCode(); } "
                      "public static int listHash(java.util.List<String> values) { "
                      "return values.hashCode(); } "
                      "public static String objectText(Object value) { return value.toString(); } "
                      "static String optionalText(Optional<Object> value) { "
                      "return value.map(Object::toString).orElse(\"\"); } "
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
    (is (str/includes? first-source "return value.ToString()!;"))
    (is (str/includes?
         first-source
         "return value.Map((value0) => value0.ToString()!).OrElse(\"\");"))
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
                 (str "package example; import java.util.HashSet; import java.util.LinkedHashSet; "
                      "import java.util.List; import java.util.Set; "
                      "public final class Visited { public static boolean addFirst("
                      "List<String> names, String name) { "
                      "Set<String> visited = new HashSet<>(names.size()); "
                      "return visited.add(name); } "
                      "public static Set<String> ordered(int capacity) { "
                      "return new LinkedHashSet<>(capacity); } }")})
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
    (is (str/includes?
         first-source
         (str "return new global::System.Collections.Generic.HashSet<string>("
              "capacity);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-load-factor-linked-set-construction-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.LinkedHashSet; "
                      "public final class Unsupported { "
                      "static LinkedHashSet<String> create(int capacity) { "
                      "return new LinkedHashSet<>(capacity, 0.75f); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.LinkedHashSet#<init>(int,float)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

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
              "return (((input != default!) && "
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
              "if ((cause == default!)) {\n"
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
              "thread.SetName(global::Vibeformer.Runtime.JavaCompat.Concat("
              "\"worker-\", count.IncrementAndGet()));\n"
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
              "((global::System.Net.IPEndPoint)(socket.RemoteEndPoint!)));\n}\n\n"
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
              "global::Vibeformer.Runtime.JavaCompat.UriScheme(uri)!)) {\n"
              "return \"http\";\n}\n"
              "return global::Vibeformer.Runtime.JavaOptional<string>.OfNullable("
              "global::Vibeformer.Runtime.JavaCompat.UriHost(uri)!).OrElseThrow("
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
         (str "if (((left == default!) || (left.GetType() != right.GetType()))) {\n"
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
              "if ((caught.InnerException! is global::System.IO.IOException)) {\n"
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

(deftest unused-java-catch-bindings-emit-identifierless-csharp-catches
  (let [fixture
        (model! {"example/IgnoredFailure.java"
                 (str "package example; public final class IgnoredFailure { "
                      "public static int run() { try { "
                      "throw new RuntimeException(\"failure\"); "
                      "} catch (RuntimeException ignored) { return 1; } } "
                      "public static int nested() { try { return run(); } "
                      "catch (RuntimeException outer) { try { return 2; } "
                      "catch (RuntimeException inner) { throw inner; } } } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/IgnoredFailure.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/IgnoredFailure.cs")))]
    (is (str/includes? first-source
                       "catch (global::System.Exception) {"))
    (is (not (str/includes? first-source "Exception ignored")))
    (is (not (str/includes? first-source "Exception outer")))
    (is (str/includes? first-source "Exception inner"))
    (is (= first-source second-source))
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
              "internal int value = -1;\n\n"
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
                       "Anonymous class requires exact Iterator, X509TrustManager, or project-class semantics"))))

(deftest capturing-anonymous-project-subclasses-preserve-virtual-dispatch-and-super
  (let [fixture
        (model! {"example/Options.java"
                 (str "package example; public class Options { "
                      "protected String select(boolean enabled, String value) { "
                      "return enabled ? value : \"default\"; } "
                      "public static Options withPrefix(String prefix) { "
                      "return new Options() { @Override protected String select("
                      "boolean enabled, String value) { if (enabled) { return prefix + value; } "
                      "return super.select(enabled, value); } }; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Options.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Options.cs")))]
    (is (str/includes? first-source "protected virtual string select("))
    (is (str/includes? first-source "protected override string select("))
    (is (str/includes? first-source "return base.select(enabled, value);"))
    (is (str/includes? first-source "private readonly string __capture_0;"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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

(deftest explicit-primitive-casts-and-constant-byte-assignments-are-preserved
  (let [fixture
        (model! {"example/PrimitiveConversions.java"
                 (str "package example; public final class PrimitiveConversions { "
                      "static char asChar(int value) { char result = (char) value; "
                      "return result; } static byte[] markers() { "
                      "byte[] result = new byte[2]; result[0] = '\\r'; "
                      "result[1] = '\\n'; return result; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/PrimitiveConversions.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/PrimitiveConversions.cs")))]
    (is (str/includes? first-source "char result = (char)(value);"))
    (is (str/includes? first-source "result[0] = (sbyte)'\\r';"))
    (is (str/includes? first-source "result[1] = (sbyte)'\\n';"))
    (is (= first-source second-source))
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

(deftest byte-array-equality-uses-exact-java-null-and-element-semantics
  (let [fixture
        (model! {"example/BytesEqual.java"
                 (str "package example; import java.util.Arrays; "
                      "public final class BytesEqual { static boolean same("
                      "byte[] left, byte[] right) { return Arrays.equals(left, right); } "
                      "static int hash(byte[] value) { return Arrays.hashCode(value); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/BytesEqual.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/BytesEqual.cs")))]
    (is (str/includes?
         first-source
         (str "return global::Vibeformer.Runtime.JavaCompat.ArrayEquals("
              "left, right);")))
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.ArrayHash(value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-ranged-byte-array-equality-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.Arrays; "
                      "public final class Unsupported { static boolean same("
                      "byte[] left, byte[] right) { "
                      "return Arrays.equals(left, 0, left.length, right, 0, right.length); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.Arrays#equals(byte[],int,int,byte[],int,int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest ordinary-java-enums-lower-with-values-identity-and-ordinals
  (let [fixture
        (model! {"example/Mode.java"
                 (str "package example; public enum Mode { ALPHA(1), BETA(2); "
                      "private final int codeValue; Mode(int code) { this.codeValue = code; } "
                      "int code() { return codeValue; } "
                      "boolean before(Mode other) { return ordinal() < other.ordinal(); } "
                      "int selected() { switch (this) { case ALPHA: return 1; "
                      "case BETA: } return 2; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Mode.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Mode.cs")))]
    (is (str/includes? first-source "public sealed class Mode"))
    (is (str/includes?
         first-source
         "public static readonly global::Example.Java.Library.Mode ALPHA = new global::Example.Java.Library.Mode(1);"))
    (is (str/includes?
         first-source
         "public static Mode[] values() => global::Vibeformer.Runtime.JavaCompat.EnumValues<Mode>();"))
    (is (str/includes?
         first-source
         (str "return (global::Vibeformer.Runtime.JavaCompat.EnumOrdinal(this) < "
              "global::Vibeformer.Runtime.JavaCompat.EnumOrdinal(other));")))
    (is (str/includes?
         first-source
         (str "switch (global::Vibeformer.Runtime.JavaCompat.EnumOrdinal(this)) {\n"
              "case 0:\nreturn 1;\ncase 1:\nbreak;\n}")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-enum-comparison-remains-fail-closed
  (let [fixture
        (model! {"example/Mode.java"
                 (str "package example; public enum Mode { ALPHA, BETA; "
                      "int compare(Mode other) { return compareTo(other); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.Enum#compareTo(java.lang.Enum)"
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
                      "static int smaller(int left, int right) { return Math.min(left, right); } "
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
         "return global::System.Math.Min(left, right);"))
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

(deftest filter-stream-base-construction-uses-a-csharp-constructor-initializer
  (let [fixture
        (model! {"example/Filtering.java"
                 (str "package example; import java.io.FilterOutputStream; "
                      "import java.io.OutputStream; public final class Filtering "
                      "extends FilterOutputStream { Filtering(OutputStream out) { "
                      "super(out); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Filtering.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Filtering.cs")))]
    (is (str/includes? first-source "Filtering(global::System.IO.Stream @out) : base(@out)"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
         (str "global::Vibeformer.Runtime.JavaCompat.Concat("
              "global::Vibeformer.Runtime.JavaCompat.Concat("
              "global::Vibeformer.Runtime.JavaCompat.UriSyntaxReason(failure), \"@\"), "
              "global::Vibeformer.Runtime.JavaCompat.UriSyntaxIndex(failure))")))
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

(deftest string-matches-and-decoded-uri-path-use-reusable-java-semantics
  (let [fixture
        (model! {"example/Paths.java"
                 (str "package example; import java.net.URI; "
                      "public final class Paths { static boolean absolute(String value) { "
                      "return value.matches(\"^http(s)?://.*\"); } "
                      "static String path(URI value) { return value.getPath(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Paths.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Paths.cs")))]
    (is (str/includes?
         first-source
         (str "return global::Vibeformer.Runtime.JavaCompat.StringMatches("
              "value, \"^http(s)?://.*\");")))
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.UriPath(value)!;"))
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

(deftest optional-int-factories-map-to-nullable-values
  (let [fixture
        (model! {"example/IntOptions.java"
                 (str "package example; import java.util.OptionalInt; "
                      "public final class IntOptions { "
                      "static OptionalInt present(int value) { return OptionalInt.of(value); } "
                      "static OptionalInt missing() { return OptionalInt.empty(); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/IntOptions.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/IntOptions.cs")))]
    (is (str/includes? first-source "return value;"))
    (is (str/includes? first-source "return (int?)null;"))
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

(deftest inflater-output-stream-uses-the-reusable-zlib-compatibility-stream
  (let [fixture
        (model! {"example/Inflating.java"
                 (str "package example; import java.io.OutputStream; "
                      "import java.util.zip.InflaterOutputStream; "
                      "public final class Inflating { static OutputStream decode(OutputStream output) { "
                      "return new InflaterOutputStream(output); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Inflating.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Inflating.cs")))]
    (is (str/includes?
         first-source
         (str "return new global::Vibeformer.Runtime.JavaInflaterOutputStream("
              "output);")))
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

(deftest ssl-context-socket-factory-uses-the-reusable-tls-client-factory
  (let [fixture
        (model! {"example/TlsClient.java"
                 (str "package example; import java.net.Socket; "
                      "import javax.net.ssl.SSLContext; public final class TlsClient { "
                      "static Socket connect(SSLContext context, String host, int port) "
                      "throws Exception { return context.getSocketFactory()"
                      ".createSocket(host, port); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/TlsClient.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/TlsClient.cs")))]
    (is (str/includes?
         first-source
         "return context.GetSocketFactory().CreateSocket(host, port);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-ssl-context-protocol-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import javax.net.ssl.SSLContext; "
                      "public final class Unsupported { static String protocol("
                      "SSLContext context) { return context.getProtocol(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:javax.net.ssl.SSLContext#getProtocol()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest ssl-context-server-socket-factory-preserves-the-tls-listener-boundary
  (let [fixture
        (model! {"example/TlsServer.java"
                 (str "package example; import java.net.ServerSocket; "
                      "import javax.net.ssl.SSLContext; public final class TlsServer { "
                      "static ServerSocket listen(SSLContext context, int port) "
                      "throws Exception { return context.getServerSocketFactory()"
                      ".createServerSocket(port); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/TlsServer.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/TlsServer.cs")))]
    (is (str/includes?
         first-source
         (str "return context.GetServerSocketFactory()"
              ".CreateServerSocket(port);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest keystore-managers-initialize-a-reusable-ssl-context
  (let [fixture
        (model! {"example/TlsContexts.java"
                 (str "package example; import java.io.InputStream; import java.net.URL; "
                      "import java.security.KeyStore; import javax.net.ssl.KeyManager; "
                      "import javax.net.ssl.KeyManagerFactory; import javax.net.ssl.SSLContext; "
                      "import javax.net.ssl.TrustManager; import javax.net.ssl.TrustManagerFactory; "
                      "public final class TlsContexts { static SSLContext create("
                      "URL location, String password) throws Exception { "
                      "KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); "
                      "try (InputStream input = location.openStream()) { "
                      "store.load(input, password.toCharArray()); } "
                      "KeyManagerFactory keys = KeyManagerFactory.getInstance("
                      "KeyManagerFactory.getDefaultAlgorithm()); "
                      "keys.init(store, password.toCharArray()); "
                      "KeyManager[] keyManagers = keys.getKeyManagers(); "
                      "TrustManagerFactory trusts = TrustManagerFactory.getInstance("
                      "TrustManagerFactory.getDefaultAlgorithm()); trusts.init(store); "
                      "TrustManager[] trustManagers = trusts.getTrustManagers(); "
                      "SSLContext context = SSLContext.getInstance(\"TLS\"); "
                      "context.init(keyManagers, trustManagers, null); return context; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/TlsContexts.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/TlsContexts.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaKeyStore store = "
              "global::Vibeformer.Runtime.JavaKeyStore.GetInstance("
              "global::Vibeformer.Runtime.JavaKeyStore.GetDefaultType());")))
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaCompat.OpenUrlStream(location)"))
    (is (str/includes?
         first-source
         "context.Init(keyManagers, trustManagers, default!);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-keystore-size-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.security.KeyStore; "
                      "public final class Unsupported { static int size(KeyStore store) "
                      "throws Exception { return store.size(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.security.KeyStore#size()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest decimal-integer-parsing-uses-the-reusable-java-parser
  (let [fixture
        (model! {"example/Numbers.java"
                 (str "package example; public final class Numbers { "
                      "static int parse(String value) { return Integer.parseInt(value); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Numbers.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Numbers.cs")))]
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.ParseInt(value, 10);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-unsigned-integer-parsing-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static int parse(String value) { "
                      "return Integer.parseUnsignedInt(value); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.Integer#parseUnsignedInt(java.lang.String)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest component-uri-construction-uses-the-reusable-java-uri-contract
  (let [fixture
        (model! {"example/Uris.java"
                 (str "package example; import java.net.URI; public final class Uris { "
                      "static URI create(String scheme, String user, String host, int port, "
                      "String path, String query, String fragment) throws Exception { "
                      "return new URI(scheme, user, host, port, path, query, fragment); } }")})
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
         (str "return global::Vibeformer.Runtime.JavaCompat.NewUri("
              "scheme, user, host, port, path, query, fragment);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest single-string-uri-construction-uses-the-reusable-java-uri-contract
  (let [fixture
        (model! {"example/Uris.java"
                 (str "package example; import java.net.URI; public final class Uris { "
                      "static URI create(String value) throws Exception { "
                      "return new URI(value); } }")})
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
         "return global::Vibeformer.Runtime.JavaCompat.NewUri(value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-authority-uri-construction-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.net.URI; public final class Unsupported { "
                      "static URI create(String scheme, String authority, String path, "
                      "String query, String fragment) throws Exception { "
                      "return new URI(scheme, authority, path, query, fragment); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= (str "executable:java.net.URI#<init>(java.lang.String,java.lang.String,"
                "java.lang.String,java.lang.String,java.lang.String)")
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest concrete-byte-array-output-single-byte-write-is-reusable
  (let [fixture
        (model! {"example/Bytes.java"
                 (str "package example; import java.io.ByteArrayOutputStream; "
                      "public final class Bytes { static byte[] write(int value) { "
                      "ByteArrayOutputStream output = new ByteArrayOutputStream(); "
                      "output.write(value); return output.toByteArray(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Bytes.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Bytes.cs")))]
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaCompat.OutputStreamWrite(output, value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-byte-array-output-write-bytes-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.ByteArrayOutputStream; "
                      "public final class Unsupported { static void write("
                      "ByteArrayOutputStream output, byte[] value) { "
                      "output.writeBytes(value); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.ByteArrayOutputStream#writeBytes(byte[])"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest concrete-array-list-operations-use-reusable-list-semantics
  (let [fixture
        (model! {"example/Lists.java"
                 (str "package example; import java.util.ArrayList; "
                      "public final class Lists { "
                      "static String takeLast(ArrayList<String> values) { "
                      "if (values.isEmpty()) return \"\"; "
                      "String value = values.get(values.size() - 1); "
                      "return values.remove(values.size() - 1) + value; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Lists.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Lists.cs")))]
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaCompat.ListIsEmpty(values)"))
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaCompat.ListGet(values, (values.Count - 1))"))
    (is (str/includes?
         first-source
         "global::Vibeformer.Runtime.JavaCompat.ListRemove(values, (values.Count - 1))"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-array-list-capacity-operation-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.ArrayList; "
                      "public final class Unsupported { static void reserve("
                      "ArrayList<String> values) { values.ensureCapacity(10); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.ArrayList#ensureCapacity(int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest collector-suppliers-materialize-concrete-array-lists
  (let [fixture
        (model! {"example/Collecting.java"
                 (str "package example; import java.util.ArrayList; import java.util.List; "
                      "import static java.util.stream.Collectors.toCollection; "
                      "public final class Collecting { static ArrayList<String> copy("
                      "List<String> values) { return values.stream()"
                      ".collect(toCollection(ArrayList::new)); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Collecting.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Collecting.cs")))]
    (is (str/includes?
         first-source
         "return new global::System.Collections.Generic.List<string>(values);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-unmodifiable-list-collector-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.List; "
                      "import static java.util.stream.Collectors.toUnmodifiableList; "
                      "public final class Unsupported { static List<String> copy("
                      "List<String> values) { return values.stream()"
                      ".collect(toUnmodifiableList()); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.stream.Collectors#toUnmodifiableList()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest string-last-index-search-uses-java-character-semantics
  (let [fixture
        (model! {"example/Search.java"
                 (str "package example; public final class Search { "
                      "static int space(String value) { return value.lastIndexOf(' '); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Search.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Search.cs")))]
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.StringLastIndexOf(value, ' ');"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-string-last-index-from-offset-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static int search(String value) { return value.lastIndexOf('x', 2); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.String#lastIndexOf(int,int)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest bounded-string-split-uses-the-reusable-java-regex-contract
  (let [fixture
        (model! {"example/Splitting.java"
                 (str "package example; public final class Splitting { "
                      "static String[] parts(String value) { return value.split(\"\\\\s\", 3); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Splitting.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Splitting.cs")))]
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.StringSplit(value, \"\\\\s\", 3);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-string-replace-all-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static String replace(String value) { "
                      "return value.replaceAll(\"x\", \"y\"); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.String#replaceAll(java.lang.String,java.lang.String)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest thread-local-rfc1123-date-state-uses-reusable-time-semantics
  (let [fixture
        (model! {"example/Dates.java"
                 (str "package example; import java.time.ZoneOffset; "
                      "import java.time.ZonedDateTime; import java.time.format.DateTimeFormatter; "
                      "public final class Dates { private static final ThreadLocal<Long> LAST = "
                      "ThreadLocal.withInitial(() -> 0L); static String current() { "
                      "long previous = LAST.get(); long now = System.currentTimeMillis(); "
                      "LAST.set(now); return DateTimeFormatter.RFC_1123_DATE_TIME.format("
                      "ZonedDateTime.now(ZoneOffset.UTC)) + previous; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Dates.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Dates.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaThreadLocal<long>.WithInitial("
              "() => 0L)")))
    (is (str/includes?
         first-source
         "long now = global::System.DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();"))
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaDateTimeFormatter.Rfc1123.Format("
              "global::System.DateTimeOffset.UtcNow)")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-thread-local-remove-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static void clear(ThreadLocal<String> value) { value.remove(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.lang.ThreadLocal#remove()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest ordinary-supplier-get-invokes-the-resolved-delegate
  (let [fixture
        (model! {"example/Supplied.java"
                 (str "package example; import java.util.function.Supplier; "
                      "public final class Supplied { static String value("
                      "Supplier<String> supplier) { return supplier.get(); } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Supplied.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Supplied.cs")))]
    (is (str/includes? first-source "return supplier();"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest anonymous-x509-trust-managers-preserve-the-throw-to-reject-contract
  (let [fixture
        (model! {"example/UnsafeTls.java"
                 (str "package example; import java.security.cert.X509Certificate; "
                      "import javax.net.ssl.SSLContext; import javax.net.ssl.TrustManager; "
                      "import javax.net.ssl.X509TrustManager; public final class UnsafeTls { "
                      "static SSLContext create() throws Exception { TrustManager[] managers = "
                      "new TrustManager[]{ new X509TrustManager() { "
                      "public X509Certificate[] getAcceptedIssuers() { "
                      "return new X509Certificate[0]; } "
                      "public void checkServerTrusted(X509Certificate[] chain, String authType) {} "
                      "public void checkClientTrusted(X509Certificate[] chain, String authType) {} "
                      "} }; SSLContext context = SSLContext.getInstance(\"SSL\"); "
                      "context.init(null, managers, null); return context; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/UnsafeTls.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/UnsafeTls.cs")))]
    (is (str/includes? first-source "private sealed class Anonymous_1_"))
    (is (str/includes?
         first-source
         ": global::Vibeformer.Runtime.JavaX509TrustManager"))
    (is (str/includes? first-source "void CheckServerTrusted("))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest synchronized-blocks-use-the-dotnet-monitor-contract
  (let [fixture
        (model! {"example/Locked.java"
                 (str "package example; public final class Locked { "
                      "static int update(Object monitor, int value) { "
                      "synchronized (monitor) { return value + 1; } } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Locked.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Locked.cs")))]
    (is (str/includes?
         first-source
         (str "lock (monitor) {\n"
              "return (value + 1);\n"
              "}")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest multi-catch-collapses-only-equal-destination-exception-types
  (let [fixture
        (model! {"example/Security.java"
                 (str "package example; import java.security.KeyManagementException; "
                      "import java.security.NoSuchAlgorithmException; import javax.net.ssl.SSLContext; "
                      "public final class Security { static boolean initialize() { try { "
                      "SSLContext context = SSLContext.getInstance(\"TLS\"); "
                      "context.init(null, null, null); return true; "
                      "} catch (KeyManagementException | NoSuchAlgorithmException failure) { "
                      "return failure.getMessage() == null; } } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Security.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Security.cs")))]
    (is (str/includes?
         first-source
         "catch (global::System.Security.Cryptography.CryptographicException failure)"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest multi-catch-with-distinct-destination-types-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.IOException; "
                      "import java.security.NoSuchAlgorithmException; "
                      "public final class Unsupported { static void first() throws IOException {} "
                      "static void second() throws NoSuchAlgorithmException {} static void run() { "
                      "try { first(); second(); } catch (IOException | NoSuchAlgorithmException failure) {} "
                      "} }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (str/includes? (get-in (ex-data error) [:diagnostic :message])
                       "multi-catch alternatives require one exact destination type"))))

(deftest no-argument-ssl-socket-factories-preserve-deferred-tls-state
  (let [fixture
        (model! {"example/Sockets.java"
                 (str "package example; import java.net.Socket; "
                      "import javax.net.ssl.SSLSocketFactory; public final class Sockets { "
                      "static Socket create(SSLSocketFactory factory) throws Exception { "
                      "return factory.createSocket(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Sockets.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Sockets.cs")))]
    (is (str/includes? first-source "return factory.CreateSocket();"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest ordinary-continue-statements-lower-structurally
  (let [fixture
        (model! {"example/Continuing.java"
                 (str "package example; public final class Continuing { "
                      "static int odds(int limit) { int count = 0; "
                      "for (int value = 0; value < limit; value++) { "
                      "if (value % 2 == 0) continue; count++; } return count; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Continuing.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Continuing.cs")))]
    (is (str/includes? first-source "if (((value % 2) == 0)) {\ncontinue;\n}"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest file-length-and-buffered-input-use-reusable-io-semantics
  (let [fixture
        (model! {"example/Files.java"
                 (str "package example; import java.io.BufferedInputStream; import java.io.File; "
                      "import java.io.InputStream; public final class Files { "
                      "static long length(File file) { return file.length(); } "
                      "static InputStream open(File file) throws Exception { "
                      "return new BufferedInputStream(java.nio.file.Files.newInputStream(file.toPath())); "
                      "} }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Files.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Files.cs")))]
    (is (str/includes?
         first-source
         "return file.Length;"))
    (is (str/includes?
         first-source
         (str "new global::System.IO.BufferedStream("
              "global::Vibeformer.Runtime.JavaCompat.OpenInputStream(file.FullName))")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-file-delete-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.File; public final class Unsupported { "
                      "static boolean delete(File file) { return file.delete(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.io.File#delete()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest service-loader-class-scope-and-lowercase-are-reusable
  (let [fixture
        (model! {"example/Decoder.java"
                 "package example; public interface Decoder {}"
                 "example/Registry.java"
                 (str "package example; import java.util.ServiceLoader; "
                      "public final class Registry { static Iterable<Decoder> load() { "
                      "return ServiceLoader.load(Decoder.class, Registry.class.getClassLoader()); } "
                      "static String normalize(String value) { return value.toLowerCase(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Registry.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Registry.cs")))]
    (is (str/includes?
         first-source
         (str "global::Vibeformer.Runtime.JavaCompat.LoadServices<"
              "global::Example.Java.Library.Decoder>(typeof(global::Example.Java.Library.Decoder), "
              "typeof(global::Example.Java.Library.Registry).Assembly)")))
    (is (str/includes? first-source "return value.ToLowerInvariant();"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-service-loader-reload-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.ServiceLoader; "
                      "public final class Unsupported { static void reload("
                      "ServiceLoader<Runnable> services) { services.reload(); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.util.ServiceLoader#reload()"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest simple-immutable-map-entries-use-a-read-only-entry-carrier
  (let [fixture
        (model! {"example/Entries.java"
                 (str "package example; import java.util.AbstractMap; import java.util.Map; "
                      "public final class Entries { static Map.Entry<String, String> create("
                      "String key, String value) { "
                      "return new AbstractMap.SimpleImmutableEntry<>(key, value); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Entries.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Entries.cs")))]
    (is (str/includes?
         first-source
         (str "new global::Vibeformer.Runtime.JavaSimpleImmutableEntry<"
              "string, string>(key, value)")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest project-constructor-references-lower-to-arity-exact-lambdas
  (let [fixture
        (model! {"example/Failure.java"
                 (str "package example; public final class Failure { "
                      "Failure(String message, int line) {} }")
                 "example/Factories.java"
                 (str "package example; import java.util.function.BiFunction; "
                      "public final class Factories { static BiFunction<String, Integer, Failure> "
                      "factory() { return Failure::new; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Factories.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Factories.cs")))]
    (is (str/includes?
         first-source
         (str "return (value0, value1) => new global::Example.Java.Library.Failure("
              "value0, value1);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest utf8-url-form-decoding-uses-the-reusable-java-contract
  (let [fixture
        (model! {"example/Urls.java"
                 (str "package example; import java.net.URLDecoder; "
                      "public final class Urls { static String decode(String value) "
                      "throws Exception { return URLDecoder.decode(value, \"UTF-8\"); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Urls.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Urls.cs")))]
    (is (str/includes?
         first-source
         "return global::Vibeformer.Runtime.JavaCompat.UrlDecode(value, \"UTF-8\");"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neighboring-url-decoder-charset-overload-remains-fail-closed
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.net.URLDecoder; "
                      "import java.nio.charset.StandardCharsets; public final class Unsupported { "
                      "static String decode(String value) { "
                      "return URLDecoder.decode(value, StandardCharsets.UTF_8); } }")})
        error (caught #(emit! fixture 1))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= "executable:java.net.URLDecoder#decode(java.lang.String,java.nio.charset.Charset)"
           (get-in (ex-data error) [:diagnostic :resolved :key])))))

(deftest java-field-and-method-name-collisions-get-stable-private-field-names
  (let [fixture
        (model! {"example/Options.java"
                 (str "package example; public final class Options { "
                      "private boolean enabled; private int __field_enabled = 1; "
                      "public boolean enabled() { return enabled; } "
                      "public Options enabled(boolean value) { this.enabled = value; return this; } "
                      "int generatedNameGuard() { return __field_enabled; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Options.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Options.cs")))]
    (is (str/includes? first-source "private bool __field_enabled2;"))
    (is (str/includes? first-source "private int __field_enabled = 1;"))
    (is (str/includes? first-source "public bool enabled()"))
    (is (str/includes? first-source "return this.__field_enabled2;"))
    (is (str/includes? first-source "this.__field_enabled2 = value;"))
    (is (= first-source second-source))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest constructor-chaining-and-invocation-conditionals-remain-expressions
  (let [fixture
        (model! {"example/Chains.java"
                 (str "package example; public class Chains { private int value; "
                      "public Chains() { this(1); } "
                      "public Chains(int value) { super(); this.value = value; } "
                      "String left() { return \"left\"; } String right() { return \"right\"; } "
                      "String choose(boolean left) { return left ? left() : right(); } }")})
        emission (emit! fixture 2)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Chains.cs")))]
    (is (str/includes? source "public Chains() : this(1)"))
    (is (str/includes? source "public Chains(int value) : base()"))
    (is (str/includes? source "return (left ? this.left() : this.right());"))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest non-static-member-classes-carry-their-java-outer-instance
  (let [fixture
        (model! {"example/Outer.java"
                 (str "package example; public final class Outer { "
                      "private Inner child = new Inner(); private Outer() {} "
                      "public static Outer create() { return new Outer(); } "
                      "public Inner child() { return child; } "
                      "public class Inner { public Outer done() { return Outer.this; } } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Outer.cs")))]
    (is (str/includes? source "this.__field_child = new global::Example.Java.Library.Outer.Inner(this);"))
    (is (str/includes? source "private readonly global::Example.Java.Library.Outer __outer;"))
    (is (str/includes? source "public Inner(global::Example.Java.Library.Outer __outer)"))
    (is (str/includes? source "return this.__outer;"))
    (is (= source
           (slurp (str (paths/resolve-path (:project-root second)
                                           "src/Example/Java/Library/Outer.cs")))))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))
