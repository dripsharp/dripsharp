(ns dripsharp.java-library-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as project-emission]
            [dripsharp.java-translate :as java]
            [dripsharp.java-types :as java-types]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.spoon :as spoon])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util IdentityHashMap]
           [javax.tools ToolProvider]
           [spoon Launcher]
           [spoon.reflect.declaration CtClass]
           [spoon.reflect.reference CtTypeReference]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-java-library"
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
    :destination-bundle 'dripsharp.java-library/rule-bundle
    :mechanical-source
    {:repository "https://example.invalid/upstream/java-library.git"
     :revision "2222222222222222222222222222222222222222"
     :notice-reference "NOTICE.txt"}
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
              :authors "DripSharp"
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
    :public-surface {:strategy 'dripsharp.java-library/public-surface-strategy}}))

(defn- emit!
  ([fixture workers] (emit! fixture workers #{}))
  ([fixture workers capabilities] (emit! fixture workers capabilities {}))
  ([{:keys [root discovery model]} workers capabilities configuration-overrides]
   (concurrency/call-with-executor
    {:worker-count workers}
    #(project-emission/emit-project!
      {:workspace-root (if (seq capabilities) (paths/absolute "..") root)
       :target (temp-directory)
       :project-input discovery
       :resolved-model model
       :configuration (merge (configuration capabilities)
                             configuration-overrides)
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

(deftest literal-node-preserves-java-literal-semantics
  (let [factory (.getFactory (Launcher.))
        emit-literal (ns-resolve 'dripsharp.java-library 'literal-node)
        render (fn [value]
                 (->> value
                      (.createLiteral (.Code factory))
                      emit-literal
                      :text))]
    (doseq [[label value expected]
            [[:byte (byte 7) "unchecked((sbyte)7)"]
             [:nil nil "default!"]
             [:string "line\n\"quoted\"" "\"line\\n\\\"quoted\\\"\""]
             [:character \newline "'\\n'"]
             [:boolean-true true "true"]
             [:boolean-false false "false"]
             [:integer (int 42) "42"]
             [:long (long 42) "42L"]
             [:float (float 1.25) "1.25F"]
             [:double (double 1.25) "1.25D"]
             [:fallback (short 12) "12"]]]
      (testing (name label)
        (is (= expected (render value)))))))

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
    (is (= 5 (get-in first [:summary :declarations])
           (get-in second [:summary :declarations])))
    (is (= (slurp (str marker))
           (str "// <auto-generated />\n"
                "// Mechanically translated from: example/Marker.java\n"
                "// Upstream repository: https://example.invalid/upstream/java-library.git\n"
                "// Upstream revision: 2222222222222222222222222222222222222222\n"
                "// Translator: DripSharp 0.1.0\n"
                "// IMPORTANT: This mechanically translated derivative has been changed "
                "from the upstream source.\n"
                "// Applicable upstream notices: see NOTICE.txt.\n"
                "#nullable enable\n"
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
           (str "// <auto-generated />\n"
                "// Mechanically translated from: example/Base.java\n"
                "// Upstream repository: https://example.invalid/upstream/java-library.git\n"
                "// Upstream revision: 2222222222222222222222222222222222222222\n"
                "// Translator: DripSharp 0.1.0\n"
                "// IMPORTANT: This mechanically translated derivative has been changed "
                "from the upstream source.\n"
                "// Applicable upstream notices: see NOTICE.txt.\n"
                "#nullable enable\n"
                "namespace Example.Java.Library;\n\n"
                "public abstract class Base<T> : "
                "global::Example.Java.Library.Marker<T> {\n"
                "public abstract T convert(T value);\n"
                "\npublic Base() {}\n"
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

(deftest resolved-deprecated-annotation-is-recorded-as-source-metadata
  (let [fixture
        (model! {"example/Legacy.java"
                 "package example; @Deprecated public interface Legacy {}\n"})
        model (:model fixture)
        context (mapping-context model (java/resolved-occurrence-index model))
        decisions ((annotation-decider) context)]
    (is (= [["annotation:java.lang.Deprecated" :source-deprecation-metadata]]
           (mapv (juxt :resolved-key :strategy) decisions)))
    (is (false? (:emitted-runtime-attribute (first decisions))))))

(deftest project-runtime-annotations-preserve-reflection-semantics
  (let [fixture
        (model!
         {"example/Mode.java"
          "package example; public enum Mode { A, B }\n"
          "example/Structured.java"
          (str "package example; import java.lang.annotation.*; "
               "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) "
               "public @interface Structured { String ns(); "
               "Mode mode() default Mode.A; }\n")
          "example/Property.java"
          (str "package example; import java.lang.annotation.*; "
               "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD) "
               "public @interface Property { Mode mode() default Mode.A; }\n")
          "example/Annotated.java"
          (str "package example; import java.lang.reflect.Field; "
               "@Structured(ns=\"urn:test\", mode=Mode.B) "
               "public class Annotated { "
               "@Property public static final String VALUE=\"value\"; "
               "public static boolean annotationsWork() { "
               "Structured structured=Annotated.class.getAnnotation(Structured.class); "
               "Field field=Annotated.class.getFields()[0]; "
               "return structured.ns().equals(\"urn:test\") "
               "&& structured.mode().equals(Mode.B) "
               "&& field.isAnnotationPresent(Property.class) "
               "&& field.getAnnotation(Property.class).mode().equals(Mode.A); "
               "} }\n")})
        emission (emit! fixture 1 #{:java-compat :java-regex-unicode})
        project-root (:project-root emission)
        annotated
        (slurp
         (str (paths/resolve-path
               project-root "src/Example/Java/Library/Annotated.cs")))
        property
        (slurp
         (str (paths/resolve-path
               project-root "src/Example/Java/Library/Property.cs")))
        consumer-root (temp-directory)
        generated-project
        (paths/resolve-path project-root (:project-file emission))
        _ (write-sources!
           consumer-root
           {"Consumer.csproj"
            (str "<Project Sdk=\"Microsoft.NET.Sdk\">"
                 "<PropertyGroup><OutputType>Exe</OutputType>"
                 "<TargetFramework>net10.0</TargetFramework>"
                 "<ImplicitUsings>disable</ImplicitUsings>"
                 "<Nullable>enable</Nullable>"
                 "<TreatWarningsAsErrors>true</TreatWarningsAsErrors>"
                 "</PropertyGroup><ItemGroup><ProjectReference Include=\""
                 generated-project
                 "\" /></ItemGroup></Project>")
            "Program.cs"
            (str "return global::Example.Java.Library.Annotated"
                 ".annotationsWork() ? 0 : 1;\n")})
        result
        (process/run! {:directory consumer-root
                       :command ["dotnet" "run" "--project" "Consumer.csproj"
                                 "--configuration" "Release"
                                 "--verbosity:quiet"]})]
    (is (str/includes?
         annotated
         "[global::Example.Java.Library.StructuredAttribute(\"urn:test\", \"B\")]"))
    (is (str/includes?
         annotated
         "[global::Example.Java.Library.PropertyAttribute(\"A\")]"))
    (is (str/includes?
         property
         "internal sealed class PropertyAttribute : global::System.Attribute, Property"))
    (is (zero? (:exit result)))))

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

(deftest declaration-type-guard-preserves-kind-specific-covariant-lists
  (let [fixture
        (model! {"example/Variance.java"
                 (str "package example; import java.util.List; "
                      "public final class Variance { "
                      "private List<? extends String> field; "
                      "public List<? extends String> values() { return null; } "
                      "public void accept(List<? extends String> input) {} "
                      "public void copy() { "
                      "List<? extends String> local = field; field = local; } }")})
        emission (emit! fixture 1)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Variance.cs")))]
    (is (str/includes?
         source
         "private global::System.Collections.Generic.IList<string> field"))
    (is (str/includes?
         source
         "public global::System.Collections.Generic.IReadOnlyList<string> values()"))
    (is (str/includes?
         source
         "public void accept(global::System.Collections.Generic.IEnumerable<string> input)"))
    (is (str/includes?
         source
         "global::System.Collections.Generic.IList<string> local = this.field;"))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
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

(deftest erased-generic-collection-casts-use-runtime-element-conversion
  (let [fixture
        (model! {"example/GenericCasts.java"
                 (str "package example; import java.util.List; import java.util.Map; "
                      "public final class GenericCasts { "
                      "@SuppressWarnings(\"unchecked\") "
                      "public static List<byte[]> list(Object value) { "
                      "return (List<byte[]>) value; } "
                      "@SuppressWarnings(\"unchecked\") "
                      "public static Map<String, byte[]> map(Object value) { "
                      "return (Map<String, byte[]>) value; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/GenericCasts.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/GenericCasts.cs")))]
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.CastList<sbyte[]>(value);"))
    (is (str/includes?
         first-source
         (str "return global::DripSharp.Runtime.JavaCompat."
              "CastDictionary<string, sbyte[]>(value);")))
    (is (= first-source second-source))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest boxed-primitives-preserve-java-null
  (let [fixture
        (model! {"example/Boxed.java"
                 (str "package example; import java.util.List; "
                      "public final class Boxed { "
                      "private Boolean state = null; "
                      "public Integer maybe(boolean present) { "
                      "return present ? 7 : null; } "
                      "public int required() { return maybe(true); } "
                      "public void add(List<Integer> values) { "
                      "values.add(maybe(true)); } "
                      "public Boolean state() { return state; } "
                      "public void state(Boolean value) { state = value; } }")})
        emitted (emit! fixture 1 #{:java-compat :java-regex-unicode})
        source
        (slurp (str (paths/resolve-path (:project-root emitted)
                                        "src/Example/Java/Library/Boxed.cs")))]
    (is (str/includes? source "private bool? __field_state = default!;"))
    (is (str/includes? source "public int? maybe(bool present)"))
    (is (str/includes?
         source
         (str "return global::DripSharp.Runtime.JavaCompat.Unbox("
              "this.maybe(true));")))
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaCompat.Add(values, "
              "global::DripSharp.Runtime.JavaCompat.Unbox(this.maybe(true)));")))
    (is (str/includes? source "public bool? state()"))
    (is (str/includes? source "public void state(bool? value)"))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emitted)
                               :command ["dotnet" "build" (:project-file emitted)
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
                       "internal const string value = \"value\";"))
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
    (is (= 3 (get-in first [:summary :executable-roots])
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
         "new global::DripSharp.Runtime.JavaLinkedHashMap<string, string>(1, 0.75F, true);"))
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
              "global::DripSharp.Runtime.JavaCompat.EmptyMap<string, int>();")))
    (is (str/includes?
         first-source
         (str "private readonly global::System.Collections.Generic.IDictionary<string, int> ordered = "
              "new global::DripSharp.Runtime.JavaLinkedHashMap<string, int>();")))
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
              "global::DripSharp.Runtime.JavaCompat.ListOf<string>(\"one\");")))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaCompat.MapPut(target, \"x\", 1);\n"
              "global::DripSharp.Runtime.JavaCompat.AddAll(values, more);\n"
              "global::DripSharp.Runtime.JavaCompat.MapGetOrDefault(target, \"missing\", 0);\n"
              "global::DripSharp.Runtime.JavaCompat.ComputeIfAbsent(target, \"new\", (key) => 2);\n"
              "global::DripSharp.Runtime.JavaCompat.Add(values, \"item\");\n"
              "global::DripSharp.Runtime.JavaCompat.RemoveIf(values, (value) => "
              "global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase(value, \"drop\"));\n"
              "global::DripSharp.Runtime.JavaCompat.MapRemove(target, \"x\");")))
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
         (str "global::System.Collections.Generic.IDictionary<string, global::Example.Java.Library.Frozen.Item> copied = new global::DripSharp.Runtime.JavaLinkedHashMap<string, global::Example.Java.Library.Frozen.Item>(source);\n"
              "global::DripSharp.Runtime.JavaCompat.ForEach(global::DripSharp.Runtime.JavaCompat.MapEntrySet(copied), (entry) => entry.SetValue(entry.Value.freeze()));\n"
              "this.items = global::DripSharp.Runtime.JavaCompat.UnmodifiableMap(copied);\n"
              "this.names = global::DripSharp.Runtime.JavaCompat.UnmodifiableList(new global::System.Collections.Generic.List<string>(names));")))
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
              "int value = global::DripSharp.Runtime.JavaCompat.ComputeIfAbsent("
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
         (str "global::DripSharp.Runtime.JavaMapEntry<string, string> entry = "
              "new global::DripSharp.Runtime.JavaSimpleEntry<string, string>(key, value);")))
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
                 (str "package example; import java.util.Calendar; import java.util.Map; import java.util.Optional; "
                      "public final class Text { public static int hash("
                      "Map<String, String> values) { return values.hashCode(); } "
                      "public static int listHash(java.util.List<String> values) { "
                      "return values.hashCode(); } "
                      "public static boolean objectEquals(Object left, Object right) { "
                      "return left.equals(right); } "
                      "public static boolean calendarEquals(Calendar left, Calendar right) { "
                      "return left.equals(right); } "
                      "public static Calendar calendarClear(Calendar value) { "
                      "value.clear(); return value; } "
                      "public static String objectText(Object value) { return value.toString(); } "
                      "static String optionalText(Optional<Object> value) { "
                      "return value.map(Object::toString).orElse(\"\"); } "
                      "public static String render(String name) { "
                      "StringBuilder builder = new StringBuilder(\"prefix\"); "
                      "builder.append(name).append(\": \"); "
                      "return builder.toString(); } "
                      "public static String slice(String value, int start, int end) { "
                      "return new StringBuilder().append(value, start, end).toString(); } "
                      "public static String reverse(String value) { "
                      "return new StringBuilder(value).reverse().toString(); } }")})
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
         "return global::DripSharp.Runtime.JavaCompat.Equals(left, right);"))
    (is (= 2 (count (re-seq
                     #"return global::DripSharp\.Runtime\.JavaCompat\.Equals\(left, right\);"
                     first-source))))
    (is (str/includes?
         first-source
         (str "value = global::DripSharp.Runtime.JavaCompat.CalendarClear(value);\n"
              "return value;")))
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
              "return global::DripSharp.Runtime.JavaCompat.HashCode(values);")))
    (is (str/includes?
         first-source
         (str "public static int listHash("
              "global::System.Collections.Generic.IList<string> values) {\n"
              "return global::DripSharp.Runtime.JavaCompat.HashCode(values);")))
    (is (str/includes? first-source "return value.ToString()!;"))
    (is (str/includes?
         first-source
         "return value.Map((value0) => value0.ToString()!).OrElse(\"\");"))
    (is (str/includes?
         first-source
         "return new global::System.Text.StringBuilder().Append(value, start, (end - start)).ToString();"))
    (is (str/includes?
         first-source
         (str "return global::DripSharp.Runtime.JavaCompat.Reverse("
              "new global::System.Text.StringBuilder(value)).ToString();")))
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
                 (str "package example; import java.util.Collections; import java.util.Comparator; "
                      "import java.util.HashSet; import java.util.LinkedHashSet; "
                      "import java.util.List; import java.util.Set; "
                      "public final class Visited { public static boolean addFirst("
                      "List<String> names, String name) { "
                      "Set<String> visited = new HashSet<>(names.size()); "
                      "return visited.add(name); } "
                      "public static boolean addConcrete(String name) { "
                      "HashSet<String> visited = new HashSet<>(); "
                      "visited.add(name); return visited.contains(name); } "
                      "public static Set<String> ordered(int capacity) { "
                      "return new LinkedHashSet<>(capacity); } "
                      "public static String[] copy(List<String> names) { "
                      "return names.toArray(new String[names.size()]); } "
                      "public static Object[] objects(List<String> names) { "
                      "return names.toArray(); } "
                      "public static void sort(List<String> names, Comparator<String> comparator) { "
                      "Collections.sort(names, comparator); } "
                      "public static int limits(Set<Integer> values) { "
                      "return Collections.min(values) + Collections.max(values); } "
                      "public static Set<String> none() { return Collections.emptySet(); } "
                      "public static void clearConcrete() { "
                      "java.util.ArrayList<String> names = new java.util.ArrayList<>(); "
                      "java.util.HashMap<String, String> values = new java.util.HashMap<>(); "
                      "names.clear(); values.clear(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
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
         (str "global::System.Collections.Generic.HashSet<string> visited = "
              "new global::System.Collections.Generic.HashSet<string>();\n"
              "visited.Add(name);\n"
              "return visited.Contains(name);")))
    (is (str/includes?
         first-source
         (str "return new global::System.Collections.Generic.HashSet<string>("
              "capacity);")))
    (is (str/includes?
         first-source
         (str "return global::DripSharp.Runtime.JavaCompat.CollectionToArray("
              "names, new string[names.Count]);")))
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.ToArray(names);"))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.SortList(names, comparator);"))
    (is (str/includes?
         first-source
         (str "return (global::DripSharp.Runtime.JavaCompat.CollectionMin(values) + "
              "global::DripSharp.Runtime.JavaCompat.CollectionMax(values));")))
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.EmptySet<string>();"))
    (is (= 2 (count (re-seq #"\.Clear\(\);" first-source))))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-float-limit-fields-preserve-java-values
  (let [fixture
        (model! {"example/Limits.java"
                 (str "package example; public final class Limits { "
                      "public static float max() { return Float.MAX_VALUE; } "
                      "public static float min() { return Float.MIN_VALUE; } "
                      "public static float normal() { return Float.MIN_NORMAL; } "
                      "public static float positiveInfinity() { "
                      "return Float.POSITIVE_INFINITY; } "
                      "public static float negativeInfinity() { "
                      "return Float.NEGATIVE_INFINITY; } "
                      "public static boolean finite(float value) { "
                      "return Float.isFinite(value); } "
                      "public static boolean infinite(float value) { "
                      "return Float.isInfinite(value); } "
                      "public static boolean nan(float value) { "
                      "return Float.isNaN(value); } "
                      "public static int hash(float value) { "
                      "return Float.hashCode(value); } }")})
        result (emit! fixture 1 #{:java-compat :java-regex-unicode})
        source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Limits.cs")))]
    (is (str/includes? source "return float.MaxValue;"))
    (is (str/includes? source "return float.Epsilon;"))
    (is (str/includes? source "return 1.17549435E-38f;"))
    (is (str/includes? source "return float.PositiveInfinity;"))
    (is (str/includes? source "return float.NegativeInfinity;"))
    (is (str/includes? source "return float.IsFinite(value);"))
    (is (str/includes? source "return float.IsInfinity(value);"))
    (is (str/includes? source "return float.IsNaN(value);"))
    (is (str/includes?
         source
         "return global::DripSharp.Runtime.JavaCompat.FloatToIntBits(value);"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-bidi-maps-to-the-pinned-uax9-runtime
  (let [fixture
        (model! {"example/Directions.java"
                 (str "package example; import java.text.Bidi; import java.text.Normalizer; "
                      "public final class Directions { public static int summary("
                      "String word) { "
                      "Bidi bidi = new Bidi(word, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT); "
                      "int count = bidi.getRunCount(); "
                      "int result = bidi.getBaseLevel() * 1000000 + count * 100000; "
                      "if (count > 0) { result += bidi.getRunLimit(0) * 1000; "
                      "result += bidi.getRunLevel(0); } "
                      "if (bidi.isMixed()) { result += bidi.getRunStart(1) * 100; "
                      "result += bidi.getRunLimit(1) * 10 + bidi.getRunLevel(1); } "
                      "return result; } "
                      "public static int reordered() { "
                      "byte[] levels = { 0, 1, 1 }; Integer[] runs = { 1, 2, 3 }; "
                      "Bidi.reorderVisually(levels, 0, runs, 0, runs.length); "
                      "return runs[0] * 100 + runs[1] * 10 + runs[2] "
                      "+ Bidi.DIRECTION_LEFT_TO_RIGHT * 0 "
                      "+ Bidi.DIRECTION_RIGHT_TO_LEFT * 0 "
                      "+ Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT * 0; } "
                      "public static boolean mirrored() { "
                      "return Character.isMirrored('('); } "
                      "public static String normalized(String value) { "
                      "return Normalizer.normalize(value, Normalizer.Form.NFKC); } }")})
        capabilities #{:java-bidi :java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        project-root (:project-root emission)
        source
        (slurp (str (paths/resolve-path project-root
                                        "src/Example/Java/Library/Directions.cs")))]
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaBidi bidi = "
              "new global::DripSharp.Runtime.JavaBidi(word, "
              "global::DripSharp.Runtime.JavaBidi.DirectionDefaultLeftToRight);")))
    (is (str/includes? source "bidi.GetRunCount()"))
    (is (str/includes? source "bidi.IsMixed()"))
    (is (str/includes? source "bidi.GetBaseLevel()"))
    (is (str/includes? source "bidi.GetRunLevel(0)"))
    (is (str/includes? source "bidi.GetRunStart(1)"))
    (is (str/includes? source "bidi.GetRunLimit(0)"))
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaBidi.ReorderVisually("
              "levels, 0, runs, 0, runs.Length);")))
    (is (str/includes?
         source
         "return global::DripSharp.Runtime.JavaBidi.IsMirrored('(');"))
    (is (str/includes?
         source
         (str "return global::DripSharp.Runtime.JavaCompat.Normalize(value, "
              "global::System.Text.NormalizationForm.FormKC);")))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))
    (let [consumer-root (temp-directory)
          generated-project
          (paths/resolve-path project-root (:project-file emission))
          _ (write-sources!
             consumer-root
             {"Consumer.csproj"
              (str "<Project Sdk=\"Microsoft.NET.Sdk\">"
                   "<PropertyGroup><OutputType>Exe</OutputType>"
                   "<TargetFramework>net10.0</TargetFramework>"
                   "<ImplicitUsings>disable</ImplicitUsings>"
                   "<Nullable>enable</Nullable>"
                   "<TreatWarningsAsErrors>true</TreatWarningsAsErrors>"
                   "</PropertyGroup><ItemGroup><ProjectReference Include=\""
                   generated-project
                   "\" /></ItemGroup></Project>")
              "Program.cs"
              (str "if (global::Example.Java.Library.Directions"
                   ".summary(\"abc \\u05d0\\u05d1\\u05d2\") != 204471) return 1;\n"
                   "if (global::Example.Java.Library.Directions"
                   ".summary(\"\\u05d0\\u05d1\\u05d2\") != 1103001) return 2;\n"
                   "if (!global::Example.Java.Library.Directions"
                   ".mirrored()) return 3;\n"
                   "if (global::Example.Java.Library.Directions"
                   ".normalized(\"\\ufb01\") != \"fi\") return 4;\n"
                   "return global::Example.Java.Library.Directions"
                   ".reordered() == 132 ? 0 : 5;\n")})
          result
          (process/run! {:directory consumer-root
                         :command ["dotnet" "run" "--project" "Consumer.csproj"
                                   "--configuration" "Release"
                                   "--verbosity:quiet"]})]
      (is (zero? (:exit result))))))

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

(deftest neutral-deque-collection-operations-and-add-reference-compose
  (let [fixture
        (model! {"example/Queues.java"
                 (str "package example; import java.util.ArrayDeque; "
                      "import java.util.Deque; import java.util.List; "
                      "public final class Queues { "
                      "public static String drain(List<String> values) { "
                      "Deque<String> queue = new ArrayDeque<>(); "
                      "queue.addAll(values); values.forEach(queue::add); "
                      "if (queue.contains(\"first\") && !queue.isEmpty()) { "
                      "return queue.removeFirst(); } "
                      "queue.push(\"fallback\"); return queue.pop(); } "
                      "static String peek(Deque<String> queue) { "
                      "return queue.peek(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Queues.cs")))]
    (is (str/includes?
         source
         "global::DripSharp.Runtime.JavaCompat.AddAll(queue, values);"))
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaCompat.ForEach(values, "
              "(value0) => { global::DripSharp.Runtime.JavaCompat.Add("
              "queue, value0); });")))
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaCompat.CollectionContains("
              "queue, \"first\")")))
    (is (str/includes? source "return queue.Pop();"))
    (is (str/includes?
         source
         "return global::DripSharp.Runtime.JavaCompat.DequePeek(queue);"))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-sorted-map-range-uses-java-half-open-bounds
  (let [fixture
        (model! {"example/Ranges.java"
                 (str "package example; import java.util.SortedMap; import java.util.TreeMap; "
                      "import java.util.TreeSet; "
                      "public final class Ranges { static boolean empty("
                      "TreeMap<Float, String> values, float lower, float upper) { "
                      "SortedMap<Float, String> matches = values.subMap(lower, upper); "
                      "return matches.values().isEmpty(); } "
                      "static String ensure(TreeMap<Float, String> values, float key) { "
                      "return values.computeIfAbsent(key, k -> \"value\"); } "
                      "static boolean setEmpty(TreeSet<Float> values, float lower, float upper) { "
                      "values.add(lower); "
                      "return values.subSet(lower, upper).isEmpty(); } "
                      "static TreeSet<String> reverseSet() { "
                      "return new TreeSet<>((left, right) -> right.compareTo(left)); } "
                      "static TreeMap<String, Integer> reverseMap() { "
                      "return new TreeMap<>((left, right) -> right.compareTo(left)); } }")})
        emission (emit! fixture 1 #{:java-compat :java-regex-unicode})
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Ranges.cs")))]
    (is (str/includes?
         source
         (str "return global::DripSharp.Runtime.JavaCompat.CollectionIsEmpty("
              "matches.Values);")))
    (is (str/includes?
         source
         (str "global::System.Collections.Generic.IDictionary<float, string> matches = "
              "global::DripSharp.Runtime.JavaCompat.SortedSubMap("
              "values, lower, upper);")))
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaCompat.CollectionIsEmpty("
              "global::DripSharp.Runtime.JavaCompat.SortedSubSet("
              "values, lower, upper))")))
    (is (str/includes? source "values.Add(lower);"))
    (is (str/includes?
         source
         (str "return global::DripSharp.Runtime.JavaCompat.ComputeIfAbsent("
              "values, key, (k) => \"value\");")))
    (is (str/includes?
         source
         (str "new global::System.Collections.Generic.SortedSet<string>("
              "global::System.Collections.Generic.Comparer<string>.Create(")))
    (is (str/includes?
         source
         (str "new global::System.Collections.Generic.SortedDictionary<string, int>("
              "global::System.Collections.Generic.Comparer<string>.Create(")))
    (is (not (str/includes?
              source
              ".Create(global::System.Collections.Generic.Comparer<string>.Create(")))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest external-nested-dependency-types-use-the-configured-project-namespace
  (let [fixture
        (model!
         {"example/Consumer.java"
          (str "package example; "
               "import external.api.Container.Factory; "
               "public final class Consumer { "
               "public Factory identity(Factory value) { return value; } }")}
         {"external/api/Container.java"
          (str "package external.api; "
               "public final class Container { "
               "public interface Factory { Object create(); } }")})
        emission
        (emit! fixture 1 #{} {:external-namespace-prefixes
                              {"external.api" "Example.External"}})
        source
        (slurp (str (paths/resolve-path
                     (:project-root emission)
                     "src/Example/Java/Library/Consumer.cs")))]
    (is (str/includes?
         source
         (str "public global::Example.External.Container.Factory identity("
              "global::Example.External.Container.Factory value)")))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))))

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
        external-prefixes
        {:external-namespace-prefixes
         {"external.unused" "Example.External"}}
        first (emit! fixture 1 #{} external-prefixes)
        second (emit! fixture 3 #{} external-prefixes)
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
                      "import java.io.OutputStream; import java.io.SequenceInputStream; "
                      "import java.io.StringWriter; import java.io.Writer; "
                      "import java.nio.charset.StandardCharsets; "
                      "import java.util.OptionalInt; "
                      "public final class Wire { public static void write("
                      "OutputStream output, String value) throws IOException { "
                      "output.write(value.getBytes(StandardCharsets.US_ASCII)); "
                      "output.write(':'); "
                      "output.write(value.getBytes(StandardCharsets.ISO_8859_1)); "
                      "output.write(value.length()); } "
                      "public static InputStream input(String value) { return new "
                      "ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)); } "
                      "public static InputStream concat(byte[] first, byte[] second) { "
                      "return new SequenceInputStream(new ByteArrayInputStream(first), "
                      "new ByteArrayInputStream(second)); } "
                      "public static void text(Writer writer, String value) "
                      "throws IOException { writer.write(value); } "
                      "public static String text(String value) throws IOException { "
                      "StringWriter writer = new StringWriter(); "
                      "writer.write(value); return writer.toString(); } "
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
         (str "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite(output, "
              "global::DripSharp.Runtime.JavaCompat.StringGetBytes(value, "
              "global::DripSharp.Runtime.JavaStandardCharsets.USASCII));\n"
              "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite(output, ':');\n"
              "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite(output, "
              "global::DripSharp.Runtime.JavaCompat.StringGetBytes(value, "
              "global::DripSharp.Runtime.JavaStandardCharsets.ISO88591));\n"
              "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite(output, value.Length);")))
    (is (str/includes?
         first-source
         (str "return global::DripSharp.Runtime.JavaCompat.NewMemoryStream("
              "global::DripSharp.Runtime.JavaCompat.StringGetBytes(value, "
              "global::DripSharp.Runtime.JavaStandardCharsets.USASCII));")))
    (is (str/includes?
         first-source
         (str "return new global::DripSharp.Runtime.JavaSequenceInputStream("
              "global::DripSharp.Runtime.JavaCompat.NewMemoryStream(first), "
              "global::DripSharp.Runtime.JavaCompat.NewMemoryStream(second));")))
    (is (str/includes? first-source "writer.Write(value);"))
    (is (str/includes?
         first-source
         (str "global::System.IO.StringWriter writer = "
              "new global::System.IO.StringWriter();\n"
              "writer.Write(value);\nreturn writer.ToString();")))
    (is (str/includes?
         first-source
         (str "return global::DripSharp.Runtime.JavaCompat.NewString("
              "global::DripSharp.Runtime.JavaCompat.ToSignedBytes(output), "
              "global::DripSharp.Runtime.JavaStandardCharsets.USASCII);")))
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

(deftest byte-array-output-stream-slice-write-uses-the-reusable-stream-helper
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.IOException; "
                      "import java.io.ByteArrayOutputStream; public final class Unsupported { "
                      "public static void write(ByteArrayOutputStream output, byte[] bytes) "
                      "throws IOException { output.write(bytes, 0, bytes.length); } }")})
        emission (emit! fixture 1 #{:java-compat :java-regex-unicode})
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Unsupported.cs")))]
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite("
              "output, bytes, 0, bytes.Length);")))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest linked-list-add-first-uses-reusable-list-ordering
  (let [fixture
        (model! {"example/Prepending.java"
                 (str "package example; import java.util.LinkedList; "
                      "public final class Prepending { "
                      "static LinkedList<String> values(String value) { "
                      "LinkedList<String> values = new LinkedList<>(); "
                      "values.addFirst(value); return values; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Prepending.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Prepending.cs")))]
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.ListAddFirst(values, value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest class-cast-exception-uses-the-managed-invalid-cast-type
  (let [fixture
        (model! {"example/Rejecting.java"
                 (str "package example; public final class Rejecting { "
                      "static RuntimeException reject() { "
                      "return new ClassCastException(\"wrong type\"); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Rejecting.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Rejecting.cs")))]
    (is (str/includes?
         first-source
         "return new global::System.InvalidCastException(\"wrong type\");"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest filter-input-stream-super-reads-use-reusable-stream-contracts
  (let [fixture
        (model! {"example/Filtering.java"
                 (str "package example; import java.io.FilterInputStream; "
                      "import java.io.InputStream; public final class Filtering "
                      "extends FilterInputStream { Filtering(InputStream input) { super(input); } "
                      "public int readOnce() throws java.io.IOException { return super.read(); } "
                      "public int readChunk(byte[] values, int offset, int count) "
                      "throws java.io.IOException { return super.read(values, offset, count); } "
                      "public long skipChunk(long count) throws java.io.IOException { "
                      "return super.skip(count); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Filtering.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Filtering.cs")))]
    (is (str/includes?
         first-source
         "return base.Read();"))
    (is (str/includes?
         first-source
         "return base.Read(values, offset, count);"))
    (is (str/includes?
         first-source
         "return base.Skip(count);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
         (str "global::DripSharp.Runtime.JavaPipedInputStream receiver = "
              "new global::DripSharp.Runtime.JavaPipedInputStream();\n"
              "global::DripSharp.Runtime.JavaPipedOutputStream sink = "
              "new global::DripSharp.Runtime.JavaPipedOutputStream();\n"
              "sink.Connect(receiver);")))
    (is (str/includes?
         first-source
         "new global::DripSharp.Runtime.JavaExecutorService(1)"))
    (is (str/includes?
         first-source
         (str "new global::System.IO.Compression.GZipStream(receiver, "
              "global::System.IO.Compression.CompressionMode.Decompress)")))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite(sink, compressed, 0, compressed.Length);"))
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
              "global::DripSharp.Runtime.JavaCompat.SocketStream(socket);\n"
              "global::System.IO.Stream input = "
              "global::DripSharp.Runtime.JavaCompat.SocketStream(socket);\n"
              "output.Flush();\n"
              "global::System.Collections.Generic.Dictionary<string, string> values = "
              "new global::System.Collections.Generic.Dictionary<string, string>(4);\n"
              "global::System.IO.MemoryStream buffer = new global::System.IO.MemoryStream(16);\n"
              "global::DripSharp.Runtime.JavaCompat.MemoryStreamWriteTo(buffer, output);\n"
              "executor.Submit(() => {\n"
              "called.CompareAndSet(false, true);\n});\n"
              "if (called.Get()) {\nsocket.Close();\n}\n"
              "if (!value.IsPresent()) {\noutput.Dispose();\n}\n"
              "return (((input != default!) && "
              "(values.Count == 0)) && "
              "(value.Get().Length > 0));")))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaFuture<string> future = "
              "executor.Submit(callable);\n"
              "return future.Get(5, global::DripSharp.Runtime.JavaTimeUnit.SECONDS);")))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaCompat.PrintStackTrace(cause);\n"
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
         (str "global::DripSharp.Runtime.JavaAtomicInteger count = "
              "new global::DripSharp.Runtime.JavaAtomicInteger(1);\n"
              "return new global::DripSharp.Runtime.JavaExecutorService(2, (runnable) => {\n"
              "global::DripSharp.Runtime.JavaThread thread = "
              "new global::DripSharp.Runtime.JavaThread(runnable);\n"
              "thread.SetDaemon(true);\n"
              "thread.SetName(global::DripSharp.Runtime.JavaCompat.Concat("
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
              "return global::DripSharp.Runtime.JavaCompat.InetSocketAddressAddress("
              "((global::System.Net.IPEndPoint)(socket.RemoteEndPoint!)));\n}\n\n"
              "internal static void run() {\n"
              "new global::DripSharp.Runtime.JavaThread(() => {\n"
              "while (true) {\nbreak;\n}\n}, \"worker\").Start();\n}\n\n"
              "internal static bool open(int port) {\n"
              "global::DripSharp.Runtime.JavaServerSocket server = "
              "new global::DripSharp.Runtime.JavaServerSocket(port);\n"
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
              "global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase(\"x\", value0))"
              ".OrElse(false);\n"
              "value.IfPresent((text) => {});\n"
              "return (global::DripSharp.Runtime.JavaCompat.Equals("
              "value.OrElseGet(() => \"none\"), \"x\") && current);")))
    (is (str/includes?
         first-source
         (str "executor.Shutdown();\n"
              "bool stopped = executor.AwaitTermination(1, "
              "global::DripSharp.Runtime.JavaTimeUnit.SECONDS);\n"
              "if (!stopped) {\nexecutor.ShutdownNow();\n}\nreturn stopped;")))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaThread.CurrentThread().Interrupt();"))
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
         (str "if (global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase(\"http\", "
              "global::DripSharp.Runtime.JavaCompat.UriScheme(uri)!)) {\n"
              "return \"http\";\n}\n"
              "return global::DripSharp.Runtime.JavaOptional<string>.OfNullable("
              "global::DripSharp.Runtime.JavaCompat.UriHost(uri)!).OrElseThrow("
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

(deftest neutral-hashtable-construction-uses-the-synchronized-null-rejecting-map
  (let [fixture
        (model! {"example/Tables.java"
                 (str "package example; import java.util.Hashtable; "
                      "import java.util.Map; public final class Tables { "
                      "public static Map<String, String> create() { "
                      "Map<String, String> result = new Hashtable<>(); "
                      "result.put(\"key\", \"value\"); return result; } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Tables.cs")))]
    (is (str/includes?
         source
         (str "global::System.Collections.Generic.IDictionary<string, string> "
              "result = new global::DripSharp.Runtime.JavaHashtable<string, string>();")))
    (is (str/includes?
         source
         "global::DripSharp.Runtime.JavaCompat.MapPut(result, \"key\", \"value\");"))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-method-references-stream-map-and-set-collection-are-resolved
  (let [fixture
        (model! {"example/References.java"
                 (str "package example; import java.util.List; "
                      "import java.util.Map; import java.util.Set; "
                      "import static java.util.stream.Collectors.toList; "
                      "import static java.util.stream.Collectors.toSet; "
                      "public final class References { "
                      "public static Set<String> normalize(Set<String> values) { "
                      "return values.stream().map(References::upper).sorted()"
                      ".collect(toSet()); } "
                      "private static String upper(String value) { return value.toUpperCase(); } "
                      "public void copy(Map<String, String> values) { values.forEach(this::with); } "
                      "public void copyMissing(Map<String, String> source, "
                      "Map<String, String> target) { "
                      "source.forEach(target::putIfAbsent); } "
                      "public static List<String> strings(List<Object> values) { "
                      "return values.stream().filter(String.class::isInstance)"
                      ".map(String.class::cast).collect(toList()); } "
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
         (str "return global::DripSharp.Runtime.JavaCompat.SetOfValues<string>("
              "global::DripSharp.Runtime.JavaCompat.StreamSorted("
              "global::DripSharp.Runtime.JavaCompat.Map(values, "
              "global::Example.Java.Library.References.upper)));")))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaCompat.ForEach(values, "
              "(value0, value1) => { this.with(value0, value1); });")))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaCompat.ForEach(source, "
              "(value0, value1) => { "
              "global::DripSharp.Runtime.JavaCompat.MapPutIfAbsent("
              "target, value0, value1); });")))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaCompat.StreamFilter(values, "
              "(value0) => typeof(string).IsInstanceOfType(value0))")))
    (is (str/includes?
         first-source
         (str "(value0) => global::DripSharp.Runtime.JavaCompat.ClassCast<string>("
              "typeof(string), value0)")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (get-in second [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest neutral-stream-reduce-expands-binary-operator-and-static-max
  (let [fixture
        (model! {"example/References.java"
                 (str "package example; import java.util.Map; "
                      "import java.util.Optional; "
                      "public final class References { "
                      "public static Optional<Long> maximum(Map<Long, String> values) { "
                      "return values.keySet().stream().reduce(Long::max); } }")})
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
         (str "return global::DripSharp.Runtime.JavaCompat.ReduceOptional("
              "global::DripSharp.Runtime.JavaCompat.MapKeySet(values), "
              "global::System.Math.Max);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
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
              "if (((global::DripSharp.Runtime.JavaCompat.UriPort(uri) < 0) || "
              "(global::DripSharp.Runtime.JavaCompat.UriPort(uri) == 80))) {\n"
              "return 0;\n} else {\n"
              "return global::DripSharp.Runtime.JavaCompat.UriPort(uri);\n}\n}")))
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

(deftest multiple-try-resources-emit-nested-using-guards
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.InputStream; "
                      "public final class Unsupported { static void run(InputStream first, "
                      "InputStream second) throws Exception { "
                      "try (InputStream a = first; InputStream b = second) { } } }")})
        emission (emit! fixture 1)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Unsupported.cs")))]
    (is (str/includes?
         source
         (str "using (global::System.IO.Stream a = first) "
              "using (global::System.IO.Stream b = second)")))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
                       ": global::DripSharp.Runtime.JavaIterator<int>"))
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
    (is (str/includes? first-source "protected internal virtual string select("))
    (is (str/includes? first-source "protected internal override string select("))
    (is (str/includes? first-source "return base.select(enabled, value);"))
    (is (str/includes? first-source "private readonly string __capture_0;"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest standalone-constructor-calls-remain-valid-expression-statements
  (let [fixture
        (model! {"example/Construct.java"
                 (str "package example; import java.util.ArrayList; "
                      "public final class Construct { public void discard() { "
                      "new ArrayList<String>(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        result (emit! fixture 2 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Construct.cs")))]
    (is (str/includes?
         source
         "new global::System.Collections.Generic.List<string>();"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest nested-static-generic-types-close-their-clr-declaring-types
  (let [fixture
        (model! {"example/Outer.java"
                 (str "package example; public final class Outer<T> { "
                      "static final class Node<T> { T value; } "
                      "private Node<T> root = new Node<T>(); "
                      "public T value() { return root.value; } }")})
        result (emit! fixture 2)
        source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Outer.cs")))]
    (is (str/includes? source "class Node<NestedT>"))
    (is (str/includes?
         source
         "global::Example.Java.Library.Outer<object>.Node<T>"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest java-iterable-and-map-entry-contracts-compile-through-runtime-adapters
  (let [fixture
        (model!
         {"example/Values.java"
          (str "package example; import java.util.*; "
               "public final class Values implements Iterable<String> { "
               "private final List<String> values = new ArrayList<>(); "
               "public Iterator<String> iterator() { return values.iterator(); } }")
          "example/Entry.java"
          (str "package example; import java.util.Map; "
               "public final class Entry<K,V> implements Map.Entry<K,V> { "
               "private final K key; private V value; "
               "public Entry(K key, V value) { this.key = key; this.value = value; } "
               "public K getKey() { return key; } "
               "public V getValue() { return value; } "
               "public V setValue(V replacement) { V old = value; value = replacement; return old; } "
               "}")})
        capabilities #{:java-compat :java-regex-unicode}
        result (emit! fixture 2 capabilities)
        iterable-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Values.cs")))
        entry-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Entry.cs")))]
    (is (str/includes?
         iterable-source
         "global::DripSharp.Runtime.JavaIterableContract<string>"))
    (is (str/includes?
         entry-source
         "global::DripSharp.Runtime.JavaMapEntry<K, V>"))
    (is (str/includes? entry-source "public override K Key => this.GetKey();"))
    (is (str/includes? entry-source "public override V Value => this.GetValue();"))
    (is (str/includes? entry-source "public override V SetValue("))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest concrete-subclasses-override-synthesized-abstract-interface-contracts
  (let [fixture
        (model! {"example/Glyph.java"
                 (str "package example; public interface Glyph { "
                      "String path(String name); }")
                 "example/Base.java"
                 "package example; public abstract class Base implements Glyph {}"
                 "example/Child.java"
                 (str "package example; public final class Child extends Base { "
                      "public String path(String name) { return name; } }")})
        result (emit! fixture 2)
        base-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Base.cs")))
        child-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Child.cs")))]
    (is (str/includes? base-source "public abstract string path(string name);"))
    (is (str/includes? child-source "public override string path(string name)"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest overloads-do-not-become-clr-overrides
  (let [fixture
        (model! {"example/Base.java"
                 (str "package example; public class Base { "
                      "public void setValue(String value) {} }")
                 "example/Child.java"
                 (str "package example; public final class Child extends Base { "
                      "public void setValue(int value) {} }")})
        result (emit! fixture 2)
        child-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Child.cs")))]
    (is (str/includes? child-source "public void setValue(int value)"))
    (is (not (str/includes? child-source "override void setValue(int value)")))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest override-families-use-one-clr-accessibility
  (let [fixture
        (model! {"example/Base.java"
                 (str "package example; public abstract class Base { "
                      "abstract int value(); }")
                 "example/Child.java"
                 (str "package example; public final class Child extends Base { "
                      "@Override protected int value() { return 1; } }")})
        result (emit! fixture 2)
        base-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Base.cs")))
        child-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Child.cs")))]
    (is (str/includes?
         base-source "protected internal abstract int value();"))
    (is (str/includes?
         child-source "protected internal override int value()"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest exposed-signature-types-are-promoted-transitively
  (let [fixture
        (model! {"example/Api.java"
                 (str "package example; public final class Api { "
                      "public Base create() { return new Base(); } }")
                 "example/Base.java"
                 (str "package example; class Base { "
                      "protected Leaf leaf() { return new Leaf(); } }")
                 "example/Leaf.java"
                 "package example; class Leaf {}"})
        result (emit! fixture 2)
        base-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Base.cs")))
        leaf-source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Leaf.cs")))]
    (is (str/includes? base-source "public class Base"))
    (is (str/includes? leaf-source "public class Leaf"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest nested-signature-types-use-their-canonical-project-declarations
  (let [fixture
        (model! {"example/Formatter.java"
                 (str "package example; public final class Formatter { "
                      "enum Alignment { LEFT, RIGHT } "
                      "public static final class Builder { "
                      "public Builder align(Alignment value) { return this; } } }")})
        capabilities #{:java-compat :java-regex-unicode}
        result (emit! fixture 2 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Formatter.cs")))]
    (is (str/includes? source "public sealed class Alignment"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
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
                       "int first = global::DripSharp.Runtime.JavaCompat.InputStreamRead(input);"))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaCompat.InputStreamRead("
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

(deftest dom-named-node-map-items-use-java-name-order
  (let [fixture
        (model! {"example/XmlAttributes.java"
                 (str "package example; import org.w3c.dom.Element; "
                      "import org.w3c.dom.NamedNodeMap; import org.w3c.dom.Node; "
                      "public final class XmlAttributes { "
                      "public static Node first(Element element) { "
                      "NamedNodeMap attributes = element.getAttributes(); "
                      "return attributes.item(0); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/XmlAttributes.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/XmlAttributes.cs")))]
    (is (str/includes?
         first-source
         (str "return global::DripSharp.Runtime.JavaCompat.XmlAttributeItem("
              "attributes, 0);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
         (str "int parsed = global::DripSharp.Runtime.JavaCompat.ParseInt("
              "global::DripSharp.Runtime.JavaCompat.StringTrim(text.ToString()), 16);")))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.JavaStringFormat(\"%s:%s\""))
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
    (is (str/includes? first-source "char result = (char)((char)(value));"))
    (is (str/includes?
         first-source "result[0] = unchecked((sbyte)('\\r'));"))
    (is (str/includes?
         first-source "result[1] = unchecked((sbyte)('\\n'));"))
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

(deftest list-remove-method-reference-discards-java-boolean-result
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.util.List; "
                      "import java.util.function.Consumer; "
                      "public final class Unsupported { static Consumer<String> remove("
                      "List<String> values) { return values::remove; } }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Unsupported.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Unsupported.cs")))]
    (is (str/includes?
         first-source
         "return (value0) => { values.Remove(value0); };"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
         "return global::DripSharp.Runtime.JavaCompat.CollectionContains(values, value);"))
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
         (str "return global::DripSharp.Runtime.JavaCompat.ArrayEquals("
              "left, right);")))
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.ArrayHash(value);"))
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
         "[global::DripSharp.Runtime.JavaEnumNameAttribute(\"ALPHA\")]"))
    (is (str/includes?
         first-source
         "public static readonly global::Example.Java.Library.Mode ALPHA = new global::Example.Java.Library.Mode(1);"))
    (is (str/includes?
         first-source
         "public static Mode[] values() => global::DripSharp.Runtime.JavaCompat.EnumValues<Mode>();"))
    (is (str/includes?
         first-source
         (str "return (global::DripSharp.Runtime.JavaCompat.EnumOrdinal(this) < "
              "global::DripSharp.Runtime.JavaCompat.EnumOrdinal(other));")))
    (is (str/includes?
         first-source
         (str "switch (global::DripSharp.Runtime.JavaCompat.EnumOrdinal(this)) {\n"
              "case 0:\nreturn 1;\ncase 1:\nbreak;\n}")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest enum-constant-specific-classes-use-valid-stable-derived-type-names
  (let [fixture
        (model! {"example/ImageType.java"
                 (str "package example; public enum ImageType { "
                      "BINARY { @Override int code() { return 1; } }; "
                      "abstract int code(); }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/ImageType.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/ImageType.cs")))]
    (is (str/includes? first-source "public abstract class ImageType"))
    (is (str/includes? first-source "private sealed class Anonymous_"))
    (is (not (re-find #"Anonymous_[0-9]+_-" first-source)))
    (is (= 1 (count (re-seq #"private ImageType\(\) \{\}" first-source))))
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

(deftest neutral-number-format-mapping-preserves-fixed-width-and-fraction-settings
  (let [fixture
        (model! {"example/Formats.java"
                 (str "package example; import java.text.DecimalFormat; "
                      "import java.text.DecimalFormatSymbols; "
                      "import java.text.NumberFormat; import java.util.Locale; "
                      "public final class Formats { "
                      "private static final NumberFormat FIXED = new DecimalFormat("
                      "\"00000\", DecimalFormatSymbols.getInstance(Locale.US)); "
                      "private static final NumberFormat DECIMAL = "
                      "NumberFormat.getNumberInstance(Locale.US); "
                      "public static String fixed(long value) { return FIXED.format(value); } "
                      "public static String decimal(double value) { "
                      "DECIMAL.setMaximumFractionDigits(4); "
                      "DECIMAL.setGroupingUsed(false); "
                      "return DECIMAL.format(value); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Formats.cs")))]
    (is (str/includes?
         source
         (str "new global::DripSharp.Runtime.JavaDecimalFormat(\"00000\", "
              "global::System.Globalization.CultureInfo.GetCultureInfo("
              "\"en-US\").NumberFormat)")))
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaDecimalFormat.GetNumberInstance("
              "global::System.Globalization.CultureInfo.GetCultureInfo(\"en-US\"))")))
    (is (str/includes?
         source
         "return global::Example.Java.Library.Formats.FIXED.Format(value);"))
    (is (str/includes?
         source
         (str "global::Example.Java.Library.Formats.DECIMAL."
              "SetMaximumFractionDigits(4);\n"
              "global::Example.Java.Library.Formats.DECIMAL."
              "SetGroupingUsed(false);\n"
              "return global::Example.Java.Library.Formats.DECIMAL.Format(value);")))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))
    (let [consumer-root (temp-directory)
          generated-project
          (paths/resolve-path (:project-root emission) (:project-file emission))
          _ (write-sources!
             consumer-root
             {"Consumer.csproj"
              (str "<Project Sdk=\"Microsoft.NET.Sdk\">"
                   "<PropertyGroup><OutputType>Exe</OutputType>"
                   "<TargetFramework>net10.0</TargetFramework>"
                   "<ImplicitUsings>disable</ImplicitUsings>"
                   "<Nullable>enable</Nullable>"
                   "<TreatWarningsAsErrors>true</TreatWarningsAsErrors>"
                   "</PropertyGroup><ItemGroup><ProjectReference Include=\""
                   generated-project
                   "\" /></ItemGroup></Project>")
              "Program.cs"
              (str "return global::Example.Java.Library.Formats"
                   ".@decimal(1234.56789) == \"1234.5679\" ? 0 : 1;\n")})
          result
          (process/run! {:directory consumer-root
                         :command ["dotnet" "run" "--project" "Consumer.csproj"
                                   "--configuration" "Release"
                                   "--verbosity:quiet"]})]
      (is (zero? (:exit result))))))

(deftest neutral-message-digest-mapping-covers-incremental-and-one-shot-hashes
  (let [fixture
        (model! {"example/Digests.java"
                 (str "package example; import java.security.MessageDigest; "
                      "public final class Digests { "
                      "public static byte[] hash(byte[] input) throws Exception { "
                      "MessageDigest digest = MessageDigest.getInstance(\"SHA-256\"); "
                      "digest.update(input); digest.update(input, 0, 1); "
                      "digest.update((byte) 1); return digest.digest(input); } "
                      "public static boolean equal(byte[] left, byte[] right) { "
                      "return MessageDigest.isEqual(left, right); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Digests.cs")))]
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaMessageDigest digest = "
              "global::DripSharp.Runtime.JavaMessageDigest.GetInstance(\"SHA-256\");")))
    (is (str/includes?
         source
         (str "digest.Update(input);\n"
              "digest.Update(input, 0, 1);\n"
              "digest.Update(1);\n"
              "return digest.Digest(input);")))
    (is (str/includes?
         source
         "return global::DripSharp.Runtime.JavaMessageDigest.IsEqual(left, right);"))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest secure-random-maps-to-the-cryptographic-runtime
  (let [fixture
        (model! {"example/RandomBytes.java"
                 (str "package example; import java.security.SecureRandom; "
                      "import java.util.Random; public final class RandomBytes { "
                      "public static int fill(byte[] destination) { "
                      "Random random = new SecureRandom(); "
                      "random.nextBytes(destination); return random.nextInt(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/RandomBytes.cs")))
        runtime-source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/DripSharp/Runtime/JavaCompat.cs")))]
    (is (str/includes?
         source
         "global::DripSharp.Runtime.JavaRandom random = new global::DripSharp.Runtime.JavaRandom();"))
    (is (str/includes? source "random.NextBytes(destination);"))
    (is (str/includes? source "return random.NextInt();"))
    (is (str/includes?
         runtime-source
         "RandomNumberGenerator.Fill(MemoryMarshal.AsBytes(destination.AsSpan()));"))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest aes-cipher-maps-to-the-managed-cryptography-runtime
  (let [fixture
        (model! {"example/Aes.java"
                 (str "package example; import javax.crypto.Cipher; "
                      "import javax.crypto.spec.IvParameterSpec; "
                      "import javax.crypto.spec.SecretKeySpec; "
                      "public final class Aes { public static byte[] encrypt("
                      "byte[] key, byte[] iv, byte[] value) throws Exception { "
                      "Cipher cipher = Cipher.getInstance(\"AES/CBC/NoPadding\"); "
                      "cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, \"AES\"), "
                      "new IvParameterSpec(iv)); return cipher.doFinal(value); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        project-root (:project-root emission)
        source
        (slurp (str (paths/resolve-path project-root
                                        "src/Example/Java/Library/Aes.cs")))]
    (is (str/includes?
         source
         (str "global::DripSharp.Runtime.JavaCipher cipher = "
              "global::DripSharp.Runtime.JavaCipher.GetInstance("
              "\"AES/CBC/NoPadding\");")))
    (is (str/includes? source "cipher.Init(global::DripSharp.Runtime.JavaCipher.ENCRYPT_MODE"))
    (is (str/includes? source "return cipher.DoFinal(value);"))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory project-root
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))
    (let [consumer-root (temp-directory)
          generated-project
          (paths/resolve-path project-root (:project-file emission))
          _ (write-sources!
             consumer-root
             {"Consumer.csproj"
              (str "<Project Sdk=\"Microsoft.NET.Sdk\">"
                   "<PropertyGroup><OutputType>Exe</OutputType>"
                   "<TargetFramework>net10.0</TargetFramework>"
                   "<ImplicitUsings>disable</ImplicitUsings>"
                   "<Nullable>enable</Nullable>"
                   "<TreatWarningsAsErrors>true</TreatWarningsAsErrors>"
                   "</PropertyGroup><ItemGroup><ProjectReference Include=\""
                   generated-project
                   "\" /></ItemGroup></Project>")
              "Program.cs"
              (str "var zero = new sbyte[16];\n"
                   "var encrypted = global::Example.Java.Library.Aes"
                   ".encrypt(zero, zero, zero);\n"
                   "var expected = new sbyte[] { 0x66, unchecked((sbyte)0xe9), "
                   "0x4b, unchecked((sbyte)0xd4), unchecked((sbyte)0xef), "
                   "unchecked((sbyte)0x8a), 0x2c, 0x3b, unchecked((sbyte)0x88), "
                   "0x4c, unchecked((sbyte)0xfa), 0x59, unchecked((sbyte)0xca), "
                   "0x34, 0x2b, 0x2e };\n"
                   "return global::System.Linq.Enumerable.SequenceEqual("
                   "encrypted, expected) ? 0 : 1;\n")})
          result
          (process/run! {:directory consumer-root
                         :command ["dotnet" "run" "--project" "Consumer.csproj"
                                   "--configuration" "Release"
                                   "--verbosity:quiet"]})]
      (is (zero? (:exit result))))))

(deftest primitive-array-copy-rules-share-the-generic-java-runtime-contract
  (let [fixture
        (model! {"example/Copies.java"
                 (str "package example; import java.util.Arrays; "
                      "public final class Copies { "
                      "static byte[] bytes(byte[] value) { return Arrays.copyOf(value, 16); } "
                      "static float[] floats(float[] value) { return Arrays.copyOf(value, 4); } "
                      "static byte[] range(byte[] value) { "
                      "return Arrays.copyOfRange(value, 2, 12); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Copies.cs")))]
    (is (str/includes?
         source
         "return global::DripSharp.Runtime.JavaCompat.CopyOf(value, 16);"))
    (is (str/includes?
         source
         "return global::DripSharp.Runtime.JavaCompat.CopyOf(value, 4);"))
    (is (str/includes?
         source
         "return global::DripSharp.Runtime.JavaCompat.CopyOfRange(value, 2, 12);"))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest big-integer-preserves-java-byte-order-and-positive-modulo
  (let [fixture
        (model! {"example/BigNumbers.java"
                 (str "package example; import java.math.BigInteger; "
                      "public final class BigNumbers { "
                      "public static byte[] bytes(long value) { "
                      "return BigInteger.valueOf(value).toByteArray(); } "
                      "public static int remainder(byte[] magnitude) { "
                      "BigInteger value = new BigInteger(1, magnitude); "
                      "return value.mod(BigInteger.valueOf(3)).intValue(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        emission (emit! fixture 1 capabilities)
        project-root (:project-root emission)
        source
        (slurp (str (paths/resolve-path project-root
                                        "src/Example/Java/Library/BigNumbers.cs")))]
    (is (str/includes? source "JavaCompat.BigIntegerToByteArray("))
    (is (str/includes? source "JavaCompat.NewBigInteger(1, magnitude)"))
    (is (str/includes? source "JavaCompat.BigIntegerMod("))
    (is (zero? (get-in emission [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory project-root
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
                      "static float smaller(float left, float right) { "
                      "return Math.min(left, right); } "
                      "static float larger(float left, float right) { "
                      "return Math.max(left, right); } "
                      "static long distance(long left, long right) { "
                      "return Math.abs(left - right); } "
                      "static float magnitude(float value) { return Math.abs(value); } "
                      "static float direction(float value) { return Math.signum(value); } "
                      "static double functions(double value) { "
                      "return Math.acos(value) + Math.atan2(value, value) + Math.ceil(value) "
                      "+ Math.cos(value) + Math.log10(value) + Math.sin(value) "
                      "+ Math.sqrt(value) + Math.toDegrees(value) + Math.toRadians(value); } "
                      "static long rounded(double value) { return Math.round(value); } "
                      "static int floorDivision(int left, int right) { "
                      "return Math.floorDiv(left, right); } "
                      "static String decimal(long value) { return String.valueOf(value); } "
                      "static String newline() { return System.lineSeparator(); } "
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
         (str "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite(output, "
              "global::DripSharp.Runtime.JavaCompat.StringGetBytes("
              "global::DripSharp.Runtime.JavaCompat.ToStringRadix(chunk.size(), 16), "
              "global::System.Text.Encoding.UTF8));")))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaCompat.StringGetBytes("
              "global::DripSharp.Runtime.JavaCompat.StringValueOf(chunk.size()), "
              "global::System.Text.Encoding.UTF8)")))
    (is (str/includes?
         first-source
         (str "return global::DripSharp.Runtime.JavaCompat.Sum("
              "global::DripSharp.Runtime.JavaCompat.MapToLong(chunks, "
              "(value0) => value0.size()));")))
    (is (str/includes?
         first-source
         "return global::System.Math.Min(left, right);"))
    (is (str/includes?
         first-source
         "return global::System.Math.Max(left, right);"))
    (is (str/includes?
         first-source
         "return global::System.Math.Abs((left - right));"))
    (is (str/includes?
         first-source
         "return global::System.Math.Abs(value);"))
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.SignumFloat(value);"))
    (is (str/includes? first-source "global::System.Math.Acos(value)"))
    (is (str/includes? first-source "global::System.Math.Atan2(value, value)"))
    (is (str/includes? first-source "global::System.Math.Ceiling(value)"))
    (is (str/includes? first-source "global::System.Math.Cos(value)"))
    (is (str/includes? first-source "global::System.Math.Log10(value)"))
    (is (str/includes? first-source "global::System.Math.Sin(value)"))
    (is (str/includes? first-source "global::System.Math.Sqrt(value)"))
    (is (str/includes? first-source "global::DripSharp.Runtime.JavaCompat.ToDegrees(value)"))
    (is (str/includes? first-source "global::DripSharp.Runtime.JavaCompat.ToRadians(value)"))
    (is (str/includes? first-source "return global::DripSharp.Runtime.JavaCompat.MathRound(value);"))
    (is (str/includes? first-source "return global::DripSharp.Runtime.JavaCompat.FloorDiv(left, right);"))
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.StringValueOf(value);"))
    (is (str/includes?
         first-source
         "return global::System.Environment.NewLine;"))
    (is (str/includes?
         first-source
         (str "sbyte[] result = new sbyte[global::DripSharp.Runtime.JavaCompat.ToIntExact(length)];\n"
              "global::DripSharp.Runtime.JavaCompat.ArrayCopy(source, 0, result, 0, source.Length);")))
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
         "return global::DripSharp.Runtime.JavaCompat.Equals(left, right);"))
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
         "return global::DripSharp.Runtime.JavaCompat.InputStreamRead(input);"))
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
         (str "global::DripSharp.Runtime.JavaCompat.Concat("
              "global::DripSharp.Runtime.JavaCompat.Concat("
              "global::DripSharp.Runtime.JavaCompat.UriSyntaxReason(failure), \"@\"), "
              "global::DripSharp.Runtime.JavaCompat.UriSyntaxIndex(failure))")))
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
              "global::DripSharp.Runtime.JavaCompat.CompileRegex(expression);\n"
              "return global::DripSharp.Runtime.JavaCompat.RegexMatcher("
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
         (str "return global::DripSharp.Runtime.JavaCompat.StringMatches("
              "value, \"^http(s)?://.*\");")))
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.UriPath(value)!;"))
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
         (str "global::DripSharp.Runtime.JavaCompat.OptionalLongIfPresent(length, "
              "(value) => result.Append(global::DripSharp.Runtime.JavaCompat.StringValueOf(value)));")))
    (is (str/includes?
         first-source
         (str "return result.Append(global::DripSharp.Runtime.JavaCompat.StringJoin("
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
         (str "global::DripSharp.Runtime.JavaPushbackInputStream input = "
              "new global::DripSharp.Runtime.JavaPushbackInputStream(source);")))
    (is (str/includes? first-source "input.Unread(value);"))
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.InputStreamRead(input);"))
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
         (str "return new global::DripSharp.Runtime.JavaInflaterOutputStream("
              "output);")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest inflater-uses-the-reusable-streaming-compatibility-type
  (let [fixture
        (model! {"example/Inflating.java"
                 (str "package example; import java.util.zip.Inflater; "
                      "public final class Inflating { static int decode(byte[] input, byte[] output) "
                      "throws Exception { Inflater inflater = new Inflater(true); "
                      "inflater.setInput(input, 0, input.length); "
                      "if (inflater.finished() || inflater.needsInput()) return -1; "
                      "int count = inflater.inflate(output); inflater.end(); return count; } }")})
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
         (str "global::DripSharp.Runtime.JavaInflater inflater = "
              "new global::DripSharp.Runtime.JavaInflater(true);")))
    (is (str/includes? first-source "inflater.SetInput(input, 0, input.Length);"))
    (is (str/includes? first-source "inflater.Finished() || inflater.NeedsInput()"))
    (is (str/includes? first-source "int count = inflater.Inflate(output);"))
    (is (str/includes? first-source "inflater.End();"))
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
              "{\n"
              "int selected = 2;\n"
              "global::Example.Java.Library.Initialized.VALUE = selected;\n"
              "}\n}")))
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
         (str "global::DripSharp.Runtime.JavaKeyStore store = "
              "global::DripSharp.Runtime.JavaKeyStore.GetInstance("
              "global::DripSharp.Runtime.JavaKeyStore.GetDefaultType());")))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.OpenUrlStream(location)"))
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
         "return global::DripSharp.Runtime.JavaCompat.ParseInt(value, 10);"))
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
         (str "return global::DripSharp.Runtime.JavaCompat.NewUri("
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
         "return global::DripSharp.Runtime.JavaCompat.NewUri(value);"))
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
         "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite(output, value);"))
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
                      "return values.remove(values.size() - 1) + value; } "
                      "static void append(ArrayList<String> values, String value) { "
                      "values.add(value); } }")})
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
         "global::DripSharp.Runtime.JavaCompat.ListIsEmpty(values)"))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.ListGet(values, (values.Count - 1))"))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.ListRemove(values, (values.Count - 1))"))
    (is (str/includes?
         first-source
         "global::DripSharp.Runtime.JavaCompat.Add(values, value);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest concrete-array-list-capacity-operation-uses-dotnet-capacity
  (let [fixture
        (model! {"example/Lists.java"
                 (str "package example; import java.util.ArrayList; "
                      "public final class Lists { static void reserve("
                      "ArrayList<String> values) { values.ensureCapacity(10); } }")})
        result (emit! fixture 1 #{:java-compat :java-regex-unicode})
        source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Lists.cs")))]
    (is (str/includes? source "values.EnsureCapacity(10);"))
    (is (zero? (get-in result [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
         "return global::DripSharp.Runtime.JavaCompat.StringLastIndexOf(value, ' ');"))
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
         "return global::DripSharp.Runtime.JavaCompat.StringSplit(value, \"\\\\s\", 3);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest string-replace-all-uses-the-reusable-java-regex-contract
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; public final class Unsupported { "
                      "static String replace(String value) { "
                      "return value.replaceAll(\"x\", \"y\"); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Unsupported.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Unsupported.cs")))]
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.StringReplaceAll(value, \"x\", \"y\");"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest string-replace-first-uses-the-reusable-java-regex-contract
  (let [fixture
        (model! {"example/Replacing.java"
                 (str "package example; public final class Replacing { "
                      "static String replace(String value) { "
                      "return value.replaceFirst(\"x\", \"y\"); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Replacing.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Replacing.cs")))]
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.StringReplaceFirst(value, \"x\", \"y\");"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
         (str "global::DripSharp.Runtime.JavaThreadLocal<long>.WithInitial("
              "() => 0L)")))
    (is (str/includes?
         first-source
         "long now = global::System.DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();"))
    (is (str/includes?
         first-source
         (str "global::DripSharp.Runtime.JavaDateTimeFormatter.Rfc1123.Format("
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
         ": global::DripSharp.Runtime.JavaX509TrustManager"))
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

(deftest multi-catch-with-distinct-destination-types-uses-a-filtered-system-exception
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.IOException; "
                      "import java.security.NoSuchAlgorithmException; "
                      "public final class Unsupported { static void first() throws IOException {} "
                      "static void second() throws NoSuchAlgorithmException {} static void run() { "
                      "try { first(); second(); } catch (IOException | NoSuchAlgorithmException failure) {} "
                      "} }")})
        first (emit! fixture 1)
        second (emit! fixture 3)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Unsupported.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Unsupported.cs")))]
    (is (str/includes?
         first-source
         (str "catch (global::System.Exception failure) when (failure is "
              "global::System.IO.IOException or "
              "global::System.Security.Cryptography.CryptographicException)")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
              "global::DripSharp.Runtime.JavaCompat.OpenInputStream("
              "new global::DripSharp.Runtime.JavaPath(file.FullName)))")))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest file-delete-uses-reusable-file-compatibility-semantics
  (let [fixture
        (model! {"example/Unsupported.java"
                 (str "package example; import java.io.File; public final class Unsupported { "
                      "static boolean delete(File file) { return file.delete(); } }")})
        capabilities #{:java-compat :java-regex-unicode}
        first (emit! fixture 1 capabilities)
        second (emit! fixture 3 capabilities)
        first-source
        (slurp (str (paths/resolve-path (:project-root first)
                                        "src/Example/Java/Library/Unsupported.cs")))
        second-source
        (slurp (str (paths/resolve-path (:project-root second)
                                        "src/Example/Java/Library/Unsupported.cs")))]
    (is (str/includes?
         first-source
         "return global::DripSharp.Runtime.JavaCompat.FileDelete(file);"))
    (is (= first-source second-source))
    (is (zero? (get-in first [:summary :executable-coverage :blocked])))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

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
         (str "global::DripSharp.Runtime.JavaCompat.LoadServices<"
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
         (str "new global::DripSharp.Runtime.JavaSimpleImmutableEntry<"
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
         "return global::DripSharp.Runtime.JavaCompat.UrlDecode(value, \"UTF-8\");"))
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

(deftest explicit-member-class-constructors-carry-their-java-outer-instance
  (let [fixture
        (model! {"example/Outer.java"
                 (str "package example; public final class Outer { "
                      "private final int value; public Outer(int value) { this.value = value; } "
                      "public Inner child(int offset) { return new Inner(offset); } "
                      "public class Inner { private final int offset; "
                      "Inner() { this(0); } Inner(int offset) { this.offset = offset; } "
                      "public int value() { return Outer.this.value + offset; } } }")})
        result (emit! fixture 2)
        source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Outer.cs")))]
    (is (str/includes?
         source
         "new global::Example.Java.Library.Outer.Inner(offset, this)"))
    (is (str/includes?
         source
         "internal Inner(int offset, global::Example.Java.Library.Outer __outer)"))
    (is (str/includes? source "this.__outer = __outer;"))
    (is (str/includes? source "internal Inner(global::Example.Java.Library.Outer __outer) : this(0, __outer)"))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest byte-literals-and-char-array-elements-use-unchecked-java-narrowing
  (let [fixture
        (model! {"example/Bytes.java"
                 (str "package example; public final class Bytes { "
                      "static byte[] values() { byte[] values = "
                      "new byte[] { (byte) 0xff, 'A' }; "
                      "values[0] = (byte) 0xfe; return values; } "
                      "static byte[][] nested() { return new byte[][] { "
                      "new byte[1], new byte[] { (byte) 0xfd } }; } }")})
        result (emit! fixture 2)
        source
        (slurp (str (paths/resolve-path (:project-root result)
                                        "src/Example/Java/Library/Bytes.cs")))]
    (is (str/includes? source "unchecked((sbyte)(255))"))
    (is (str/includes? source "unchecked((sbyte)('A'))"))
    (is (str/includes? source "unchecked((sbyte)(254))"))
    (is (str/includes?
         source
         "new sbyte[][] { new sbyte[1], new sbyte[] { unchecked((sbyte)(253)) } }"))
    (is (zero? (:exit
                (process/run! {:directory (:project-root result)
                               :command ["dotnet" "build" (:project-file result)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))

(deftest byte-compound-assignment-preserves-java-narrowing-and-single-evaluation
  (let [fixture
        (model! {"example/Bytes.java"
                 (str "package example; public final class Bytes { "
                      "public static byte add(byte[] values, int index, int amount) { "
                      "return values[index] += amount; } }")})
        emission (emit! fixture 2 #{:java-compat :java-regex-unicode})
        source
        (slurp (str (paths/resolve-path (:project-root emission)
                                        "src/Example/Java/Library/Bytes.cs")))]
    (is (str/includes?
         source
         (str "return (global::DripSharp.Runtime.JavaCompat.AddAssign("
              "ref values[index], amount));")))
    (is (zero? (:exit
                (process/run! {:directory (:project-root emission)
                               :command ["dotnet" "build" (:project-file emission)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))))
