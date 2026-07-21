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

(defn- configuration []
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
   :destination-capabilities #{}
   :resources {}
   :resource-policy {:strategy :embedded-resource-preserve-path}
   :project-dependencies []
   :external-dependencies {}
   :public-surface {:strategy 'vibeformer.java-library/public-surface-strategy}})

(defn- emit! [{:keys [root discovery model]} workers]
  (concurrency/call-with-executor
   {:worker-count workers}
   #(project-emission/emit-project!
     {:workspace-root root
      :target (temp-directory)
      :discovery discovery
      :resolved-model model
      :configuration (configuration)
      :rule-bundle (java-library/rule-bundle)})))

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
                      "import java.util.List; public class Unsupported { "
                      "public static final List<String> VALUE = "
                      "Collections.singletonList(\"x\"); }")})
        error (caught #(emit! fixture 1))]
    (is (some? error))
    (is (str/includes? (ex-message error)
                       "Java library executable or field has no neutral mapping"))))
