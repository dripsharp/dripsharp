(ns vibeformer.java-project
  "Direct declaration and disposable project emission from live Spoon objects.

  The emitted fragments are destination C# structure, not a reconstructed Java
  AST. Every declaration is reached recursively through its live Spoon owner,
  and every type is selected through the resolver's exact occurrence identity."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-body :as java-body]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.util IdentityHashMap]
           [spoon.reflect.code CtExpression]
           [spoon.reflect.declaration CtAnnotation CtAnonymousExecutable CtClass
            CtConstructor CtElement CtEnum CtEnumValue CtExecutable CtField
           CtInterface CtMethod CtModifiable CtParameter CtRecord
            CtRecordComponent CtType CtTypeParameter ModifierKind]
           [spoon.reflect.reference CtArrayTypeReference CtIntersectionTypeReference
            CtTypeParameterReference CtTypeReference CtWildcardReference]))

(def ^:private default-config-file "vibeformer/config/pkl-parser.edn")

(def ^:private csharp-keywords
  #{"abstract" "as" "base" "bool" "break" "byte" "case" "catch" "char"
    "checked" "class" "const" "continue" "decimal" "default" "delegate"
    "do" "double" "else" "enum" "event" "explicit" "extern" "false"
    "finally" "fixed" "float" "for" "foreach" "goto" "if" "implicit"
    "in" "int" "interface" "internal" "is" "lock" "long" "namespace"
    "new" "null" "object" "operator" "out" "override" "params" "private"
    "protected" "public" "readonly" "ref" "return" "sbyte" "sealed"
    "short" "sizeof" "stackalloc" "static" "string" "struct" "switch"
    "this" "throw" "true" "try" "typeof" "uint" "ulong" "unchecked"
    "unsafe" "ushort" "using" "virtual" "void" "volatile" "while"})

(defn- destination-error [message data]
  (throw (ex-info message (assoc data :kind :invalid-destination-configuration))))

(defn- relative-path! [value label]
  (let [value (str value)
        path (paths/path value)]
    (when (or (str/blank? value) (.isAbsolute path)
              (some #(= ".." (str %)) (iterator-seq (.iterator path))))
      (destination-error (str label " must be a safe relative path")
                         {:field label :value value}))
    value))

(defn validate-configuration!
  [configuration]
  (when-not (= 1 (:schema-version configuration))
    (destination-error "Unsupported destination configuration schema"
                       {:schema-version (:schema-version configuration)}))
  (doseq [[section keys] [[:project [:assembly-name :root-namespace
                                    :target-framework :nullable :implicit-usings]]
                          [:package [:id :version :description :authors :tags]]
                          [:output [:project-directory :source-directory
                                    :resource-directory :project-file
                                    :source-map-file :diagnostics-file
                                    :manifest-file :annotation-decisions-file]]]]
    (when-not (map? (get configuration section))
      (destination-error (str "Missing destination " (name section) " section")
                         {:section section}))
    (doseq [key keys]
      (when-not (contains? (get configuration section) key)
        (destination-error (str "Missing destination setting " section "/" key)
                           {:section section :setting key}))))
  (doseq [key [:project-directory :source-directory :resource-directory
               :project-file :source-map-file :diagnostics-file :manifest-file
               :annotation-decisions-file]]
    (relative-path! (get-in configuration [:output key]) (name key)))
  (when-not (contains? #{"enable" "disable"}
                       (get-in configuration [:project :nullable]))
    (destination-error "Destination nullable setting must be enable or disable"
                       {:nullable (get-in configuration [:project :nullable])}))
  (when-not (and (map? (:namespaces configuration))
                 (every? #(and (string? %) (not (str/blank? %)))
                         (mapcat identity (:namespaces configuration))))
    (destination-error "Destination namespace mappings must be non-blank strings"
                       {:namespaces (:namespaces configuration)}))
  (when-not (or (nil? (:namespace-prefixes configuration))
                (and (map? (:namespace-prefixes configuration))
                     (every? #(and (string? %) (not (str/blank? %)))
                             (mapcat identity (:namespace-prefixes configuration)))))
    (destination-error "Destination namespace-prefix mappings must be non-blank strings"
                       {:namespace-prefixes (:namespace-prefixes configuration)}))
  (when-not (and (map? (:resources configuration))
                 (every? (fn [[source {:keys [strategy destination logical-name]}]]
                           (and (= :embedded-resource strategy)
                                (string? source) (string? logical-name)
                                (relative-path! destination "resource destination")))
                         (:resources configuration)))
    (destination-error "Invalid destination resource mapping"
                       {:resources (:resources configuration)}))
  (when-not (or (nil? (:resource-policy configuration))
                (= {:strategy :embedded-resource-preserve-path}
                   (:resource-policy configuration)))
    (destination-error "Invalid destination resource policy"
                       {:resource-policy (:resource-policy configuration)}))
  configuration)

(defn read-configuration
  ([workspace-root]
   (read-configuration workspace-root default-config-file))
  ([workspace-root config-file]
   (let [file (paths/resolve-path (paths/absolute workspace-root) config-file)]
     (when-not (paths/regular-file? file)
       (destination-error "Destination configuration is missing" {:path (str file)}))
     (validate-configuration! (edn/read-string (slurp (str file)))))))

(defn- canonicalize [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (canonicalize item)]) value))
    (set? value) (mapv canonicalize (sort-by pr-str value))
    (sequential? value) (mapv canonicalize value)
    :else value))

(defn- edn-text [value]
  (str (pr-str (canonicalize value)) "\n"))

(defn- write-text! [^Path file text]
  (Files/createDirectories (.getParent file)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (Files/writeString file text (make-array java.nio.file.OpenOption 0))
  file)

(defn- source-ref
  ([^CtElement element rule]
   (source-ref element rule nil))
  ([^CtElement element rule extra]
   (merge {:frontend-class (.getName (class element))
           :role (when (.isParentInitialized element)
                   (str (.getRoleInParent element)))
           :location (spoon/source-location element)
           :rule rule}
          extra)))

(defn- with-source [node element rule extra]
  (csharp/with-source node (source-ref element rule extra)))

(defn- sequence-node
  ([nodes] (csharp/sequence-node (vec (remove nil? nodes))))
  ([nodes separator]
   (csharp/sequence-node (vec (remove nil? nodes)) separator)))

(defn- raw [text] (csharp/raw text))

(defn- modifier? [^CtModifiable element modifier]
  (.hasModifier element modifier))

(defn- identifier [name]
  (let [clean (-> (str name)
                  (str/replace #"[^A-Za-z0-9_]" "_")
                  (#(if (re-matches #"[0-9].*" %) (str "_" %) %)))]
    (if (contains? csharp-keywords clean) (str "@" clean) clean)))

(defn- pascal [name]
  (let [name (identifier name)
        prefix (if (str/starts-with? name "@") "@" "")
        body (if (str/starts-with? name "@") (subs name 1) name)]
    (str prefix (str/upper-case (subs body 0 1)) (subs body 1))))

(defn- record-component-name
  [^CtType owner-type component]
  (let [candidate (pascal (.getSimpleName ^CtElement component))
        nested-names (set (map #(.getSimpleName ^CtType %) (.getNestedTypes owner-type)))]
    (if (contains? nested-names candidate) (str candidate "Value") candidate)))

(defn- package-name [^CtType type]
  (some-> type .getPackage .getQualifiedName))

(defn- declaring-types [^CtType type]
  (loop [current type result ()]
    (if current
      (recur (.getDeclaringType current) (conj result current))
      (vec result))))

(defn- destination-namespace [ctx ^CtType type]
  (let [package (package-name (first (declaring-types type)))]
    (or (get-in ctx [:configuration :namespaces package])
        (some (fn [[source destination]]
                (when (or (= package source)
                          (str/starts-with? package (str source ".")))
                  (let [suffix (subs package (count source))
                        segments (remove str/blank? (str/split suffix #"\."))]
                    (str destination
                         (when (seq segments)
                           (str "." (str/join "." (map pascal segments))))))))
              (sort-by (comp - count key)
                       (get-in ctx [:configuration :namespace-prefixes])))
        (throw (ex-info (str "No destination namespace mapping for " package)
                        {:kind :missing-namespace-mapping :package package})))))

(defn- selected-declaration?
  [ctx ^CtElement declaration]
  (or (nil? (:selected-declarations ctx))
      (.containsKey ^IdentityHashMap (:selected-declarations ctx) declaration)))

(defn- type-path [ctx ^CtType type]
  (str "global::" (destination-namespace ctx type) "."
       (str/join "." (map #(identifier (.getSimpleName ^CtType %))
                           (declaring-types type)))))

(defn- occurrence! [ctx ^CtElement element expected-kind]
  (let [occurrence (.get ^IdentityHashMap (:occurrence-index ctx) element)]
    (when-not occurrence
      (throw (ex-info "Live Spoon object is absent from the resolved occurrence index"
                      {:kind :missing-resolved-occurrence
                       :expected expected-kind
                       :source (source-ref element :resolution/occurrence)})))
    (when-not (= expected-kind (:kind occurrence))
      (throw (ex-info "Resolved occurrence has the wrong semantic kind"
                      {:kind :resolved-occurrence-kind-mismatch
                       :expected expected-kind
                       :actual (:kind occurrence)
                       :key (:key occurrence)})))
    occurrence))

(defn- nullable-annotation? [^CtElement element]
  (boolean
   (some #(= "org.jspecify.annotations.Nullable"
             (some-> ^CtAnnotation % .getAnnotationType .getQualifiedName))
         (.getAnnotations element))))

(def ^:private external-type-mappings
  {"java.lang.Object" ["object" :dotnet.type/object]
   "java.lang.String" ["string" :dotnet.type/string]
   "java.lang.CharSequence" ["string" :dotnet.type/string]
   "java.lang.Boolean" ["bool" :dotnet.type/boolean]
   "java.lang.Byte" ["sbyte" :dotnet.type/sbyte]
   "java.lang.Short" ["short" :dotnet.type/int16]
   "java.lang.Integer" ["int" :dotnet.type/int32]
   "java.lang.Long" ["long" :dotnet.type/int64]
   "java.lang.Character" ["char" :dotnet.type/char]
   "java.lang.Class" ["global::System.Type" :dotnet.type/type]
   "java.lang.Enum" ["object" :dotnet.type/enum-base]
   "java.lang.Float" ["float" :dotnet.type/single]
   "java.lang.Double" ["double" :dotnet.type/double]
   "java.lang.NumberFormatException" ["global::System.FormatException" :dotnet.type/format-exception]
   "java.lang.AbstractStringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.StringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.Math" ["global::System.Math" :dotnet.type/math]
   "java.lang.StrictMath" ["global::System.Math" :dotnet.type/math]
   "java.lang.System" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.lang.Void" ["void" :dotnet.type/void]
   "java.lang.Throwable" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.Exception" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.RuntimeException" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.IllegalArgumentException" ["global::System.ArgumentException" :dotnet.type/argument-exception]
   "java.lang.IllegalStateException" ["global::System.InvalidOperationException" :dotnet.type/invalid-operation]
   "java.lang.AssertionError" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.ArithmeticException" ["global::System.ArithmeticException" :dotnet.type/arithmetic-exception]
   "java.lang.ExceptionInInitializerError" ["global::System.TypeInitializationException" :dotnet.type/type-initialization-exception]
   "java.lang.UnsupportedOperationException" ["global::System.NotSupportedException" :dotnet.type/not-supported-exception]
   "java.lang.StackTraceElement" ["global::System.Diagnostics.StackFrame" :dotnet.type/stack-frame]
   "java.lang.Runnable" ["global::System.Action" :dotnet.type/action]
   "java.lang.AutoCloseable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.lang.Comparable" ["global::System.IComparable" :dotnet.type/comparable]
   "java.io.IOException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.io.FileNotFoundException" ["global::System.IO.FileNotFoundException" :dotnet.type/file-not-found-exception]
   "java.io.UncheckedIOException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.io.Reader" ["global::System.IO.TextReader" :dotnet.type/text-reader]
   "java.io.StringReader" ["global::System.IO.StringReader" :dotnet.type/string-reader]
   "java.io.Writer" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.PrintWriter" ["global::Vibeformer.Runtime.JavaPrintWriter" :dotnet.type/print-writer]
   "java.io.Serializable" ["object" :dotnet.type/serializable-marker]
   "java.net.URI" ["global::System.Uri" :dotnet.type/uri]
   "java.net.URISyntaxException" ["global::System.UriFormatException" :dotnet.type/uri-format-exception]
   "java.net.URLEncoder" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.nio.charset.Charset" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.nio.charset.StandardCharsets" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.nio.file.Path" ["string" :dotnet.type/path]
   "java.nio.file.NoSuchFileException" ["global::System.IO.FileNotFoundException" :dotnet.type/file-not-found-exception]
   "java.time.Duration" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.temporal.TemporalUnit" ["global::Vibeformer.Runtime.JavaTemporalUnit" :dotnet.type/temporal-unit]
   "java.time.temporal.ChronoUnit" ["global::Vibeformer.Runtime.JavaTemporalUnit" :dotnet.type/temporal-unit]
   "java.lang.Iterable" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.Collection" ["global::System.Collections.Generic.ICollection" :dotnet.type/collection]
   "java.util.List" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "java.util.ArrayList" ["global::System.Collections.Generic.List" :dotnet.type/list]
   "java.util.Set" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "java.util.HashSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/hash-set]
   "java.util.LinkedHashSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/linked-hash-set]
   "java.util.EnumSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/enum-set]
   "java.util.AbstractCollection" ["global::System.Collections.Generic.ICollection" :dotnet.type/collection]
   "java.util.AbstractSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "java.util.Map" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "java.util.HashMap" ["global::System.Collections.Generic.Dictionary" :dotnet.type/dictionary]
   "java.util.LinkedHashMap" ["global::System.Collections.Generic.Dictionary" :dotnet.type/linked-dictionary]
   "java.util.Map$Entry" ["global::System.Collections.Generic.KeyValuePair" :dotnet.type/map-entry]
   "java.util.Comparator" ["global::System.Collections.Generic.IComparer" :dotnet.type/comparer]
   "java.util.Deque" ["global::Vibeformer.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.ArrayDeque" ["global::Vibeformer.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.Iterator" ["global::System.Collections.Generic.IEnumerator" :dotnet.type/enumerator]
   "java.util.Optional" ["global::System.Nullable" :dotnet.type/nullable-value]
   "java.util.OptionalInt" ["int?" :dotnet.type/nullable-int]
   "java.util.NoSuchElementException" ["global::System.InvalidOperationException" :dotnet.type/invalid-operation]
   "java.util.Arrays" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.Collections" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.Objects" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.ResourceBundle" ["global::Vibeformer.Runtime.JavaResourceBundle" :dotnet.type/resource-bundle]
   "java.util.Locale" ["global::System.Globalization.CultureInfo" :dotnet.type/culture-info]
   "java.util.function.Supplier" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Function" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Consumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.Predicate" ["global::System.Predicate" :dotnet.type/predicate]
   "java.util.function.BiConsumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.BiFunction" ["global::System.Func" :dotnet.type/func]
   "java.util.function.IntFunction" ["global::Vibeformer.Runtime.JavaIntFunction" :dotnet.type/int-function]
   "java.util.function.ToIntFunction" ["global::Vibeformer.Runtime.JavaToIntFunction" :dotnet.type/to-int-function]
   "java.util.function.IntPredicate" ["global::System.Predicate<int>" :dotnet.type/int-predicate]
   "java.util.stream.Stream" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.stream.IntStream" ["global::System.Collections.Generic.IEnumerable<int>" :dotnet.type/int-enumerable]
   "java.util.stream.Collector" ["global::Vibeformer.Runtime.JavaCollector" :dotnet.type/collector]
   "java.util.stream.Collectors" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.text.Format" ["global::Vibeformer.Runtime.JavaFormat" :dotnet.type/format]
   "java.text.MessageFormat" ["global::Vibeformer.Runtime.JavaMessageFormat" :dotnet.type/message-format]
   "java.util.regex.Matcher" ["global::System.Text.RegularExpressions.Match" :dotnet.type/regex-match]
   "java.util.regex.Pattern" ["global::System.Text.RegularExpressions.Regex" :dotnet.type/regex]
   "java.util.concurrent.ConcurrentHashMap" ["global::System.Collections.Concurrent.ConcurrentDictionary" :dotnet.type/concurrent-dictionary]
   "java.util.concurrent.ScheduledExecutorService" ["global::Vibeformer.Runtime.JavaScheduledExecutor" :dotnet.type/scheduled-executor]
   "java.util.concurrent.ScheduledFuture" ["global::System.Threading.Tasks.Task" :dotnet.type/task]
   "java.util.concurrent.TimeUnit" ["global::Vibeformer.Runtime.JavaTimeUnit" :dotnet.type/time-unit]
   "org.jspecify.annotations.Nullable" ["object" :dotnet.annotation/nullable]
   "org.jspecify.annotations.NullMarked" ["object" :dotnet.annotation/null-marked]})

(defn- dotted-external-type
  [prefix destination qualified-name rule]
  (when (or (= qualified-name prefix)
            (str/starts-with? qualified-name (str prefix ".")))
    (let [suffix (subs qualified-name (count prefix))
          parts (->> (str/split suffix #"[.$]") (remove str/blank?))]
      [(str "global::" destination
            (when (seq parts) (str "." (str/join "." (map identifier parts)))))
       rule])))

(defn- derived-external-type-mapping
  "Maps resolved external product/substrate identities without reconstructing
  source syntax. Parser types target the separately generated Pkl.Parser
  package; Truffle and Graal identities target explicit compatibility owners
  pending their native product implementations. Compile-time annotations are
  erased only after their exact resolved package identity is known."
  [qualified-name]
  (or (dotted-external-type "org.pkl.parser" "Pkl.Parser" qualified-name
                            :dotnet.type/pkl-parser-package)
      (dotted-external-type "com.oracle.truffle" "Pkl.Core.Runtime.Truffle" qualified-name
                            :pkl-core.type/truffle-substrate)
      (dotted-external-type "org.graalvm.collections" "Pkl.Core.Runtime.GraalCollections" qualified-name
                            :pkl-core.type/graal-collections-substrate)
      (dotted-external-type "org.graalvm.polyglot" "Pkl.Core.Runtime.Polyglot" qualified-name
                            :pkl-core.type/polyglot-substrate)
      (when (or (str/starts-with? qualified-name "com.google.errorprone.annotations.")
                (str/starts-with? qualified-name "java.lang.annotation."))
        ["object" :dotnet.annotation/compile-time-metadata])
      (when (contains? #{"java.lang.FunctionalInterface" "java.lang.Override"
                         "java.lang.SuppressWarnings"} qualified-name)
        ["object" :dotnet.annotation/compile-time-metadata])))

(def ^:private primitive-type-mappings
  {"<null>" ["object" :dotnet.type/null]
   "void" ["void" :dotnet.type/void]
   "boolean" ["bool" :dotnet.type/boolean]
   "byte" ["sbyte" :dotnet.type/sbyte]
   "short" ["short" :dotnet.type/int16]
   "int" ["int" :dotnet.type/int32]
   "long" ["long" :dotnet.type/int64]
   "char" ["char" :dotnet.type/char]
   "float" ["float" :dotnet.type/single]
   "double" ["double" :dotnet.type/double]})

(declare type-node)

(defn- generic-node [base arguments]
  (if (seq arguments)
    (sequence-node [(raw base) (raw "<") (sequence-node arguments ", ") (raw ">")])
    (raw base)))

(defn- mapped-type-base [ctx ^CtTypeReference reference occurrence]
  (cond
    (= :project (:origin occurrence))
    [(type-path ctx ^CtType (:declaration occurrence)) :dotnet.type/project]

    (= :type-parameter (:origin occurrence))
    [(identifier (.getSimpleName reference)) :dotnet.type/type-parameter]

    (= :intrinsic (:origin occurrence))
    (or (when (= :null-type (:resolution occurrence))
          ["object" :dotnet.type/null])
        (get primitive-type-mappings (.getQualifiedName reference))
        (throw (ex-info (str "Unsupported intrinsic declaration type " (:key occurrence))
                        {:kind :unsupported-declaration-type :occurrence (dissoc occurrence :reference :declaration)})))

    :else
    (or (when (and (= "java.util.List" (.getQualifiedName reference))
                   (some #(instance? CtWildcardReference %)
                         (.getActualTypeArguments reference)))
          ["global::System.Collections.Generic.IEnumerable"
           :dotnet.type/covariant-enumerable])
        (get external-type-mappings (.getQualifiedName reference))
        (derived-external-type-mapping (.getQualifiedName reference))
        (throw (ex-info (str "No declaration type mapping for " (:key occurrence))
                        {:kind :unsupported-declaration-type
                         :occurrence (dissoc occurrence :reference :declaration)})))))

(defn- type-node [ctx ^CtTypeReference reference]
  (let [occurrence (occurrence! ctx reference :type)
        [node rule]
        (cond
          (instance? CtArrayTypeReference reference)
          [(sequence-node [(type-node ctx (.getComponentType ^CtArrayTypeReference reference))
                           (raw "[]")])
           :dotnet.type/array]

          (instance? CtWildcardReference reference)
          [(if-let [bound (.getBoundingType ^CtWildcardReference reference)]
             (type-node ctx bound)
             (raw "object"))
           :dotnet.type/wildcard-bound]

          (instance? CtIntersectionTypeReference reference)
          [(type-node ctx (first (.getBounds ^CtIntersectionTypeReference reference)))
           :dotnet.type/intersection-primary]

          :else
          (let [[base mapping-rule] (mapped-type-base ctx reference occurrence)
                arguments (mapv #(type-node ctx %) (.getActualTypeArguments reference))]
            [(generic-node base arguments) mapping-rule]))
        nullable? (and (nullable-annotation? reference)
                       (not (.isPrimitive reference))
                       (not= "void" (.getQualifiedName reference)))
        node (if nullable? (sequence-node [node (raw "?")]) node)]
    (with-source node reference rule
      {:mapping {:registry :types
                 :identity rule
                 :resolved-key (:key occurrence)
                 :origin (:origin occurrence)
                 :resolution (:resolution occurrence)}})))

(defn- declaration-id [^CtElement element kind]
  (let [{:keys [file line column]} (spoon/source-location element)]
    (str (name kind) ":" (or file "implicit") ":" (or line 0) ":" (or column 0)
         ":" (.getName (class element)))))

(defn- register! [ctx ^CtElement element kind owner name signature rule]
  (let [id (declaration-id element kind)
        entry {:id id :kind kind :owner owner :name name :signature signature
               :source (source-ref element rule)}]
    (when (.containsKey ^IdentityHashMap (:emitted ctx) element)
      (throw (ex-info "A live Spoon declaration was emitted more than once"
                      {:kind :duplicate-source-declaration :declaration entry})))
    (.put ^IdentityHashMap (:emitted ctx) element entry)
    (swap! (:declarations ctx) conj entry)
    id))

(defn- annotated-sources [ctx ^CtElement element]
  (mapv
   (fn [^CtAnnotation annotation]
     (let [occurrence (occurrence! ctx annotation :annotation)
           key (:key occurrence)
           rule (cond
                  (= key "annotation:org.jspecify.annotations.Nullable")
                  :dotnet.annotation/nullable-metadata
                  (= key "annotation:org.jspecify.annotations.NullMarked")
                  :dotnet.annotation/nullable-context
                  (= key "annotation:java.lang.Override")
                  :dotnet.annotation/language-override
                  (= key "annotation:java.lang.SuppressWarnings")
                  :dotnet.annotation/compiler-warning
                  (contains?
                   #{"annotation:com.google.errorprone.annotations.Immutable"
                     "annotation:com.google.errorprone.annotations.concurrent.GuardedBy"
                     "annotation:com.oracle.truffle.api.CompilerDirectives$CompilationFinal"
                     "annotation:com.oracle.truffle.api.CompilerDirectives$TruffleBoundary"
                     "annotation:com.oracle.truffle.api.CompilerDirectives$ValueType"
                     "annotation:com.oracle.truffle.api.TruffleLanguage$Registration"
                     "annotation:com.oracle.truffle.api.dsl.GeneratedBy"
                     "annotation:com.oracle.truffle.api.dsl.ImportStatic"
                     "annotation:com.oracle.truffle.api.dsl.NeverDefault"
                     "annotation:com.oracle.truffle.api.dsl.NodeChild"
                     "annotation:com.oracle.truffle.api.dsl.TypeSystem"
                     "annotation:com.oracle.truffle.api.dsl.TypeSystemReference"
                     "annotation:com.oracle.truffle.api.instrumentation.GenerateWrapper"
                     "annotation:com.oracle.truffle.api.instrumentation.ProvidedTags"
                     "annotation:com.oracle.truffle.api.instrumentation.Tag$Identifier"
                     "annotation:com.oracle.truffle.api.nodes.Node$Child"
                     "annotation:com.oracle.truffle.api.nodes.Node$Children"
                     "annotation:com.oracle.truffle.api.nodes.NodeInfo"
                     "annotation:java.lang.FunctionalInterface"
                     "annotation:java.lang.annotation.Retention"
                     "annotation:java.lang.annotation.Target"}
                   key)
                  :dotnet.annotation/resolved-compile-time-metadata
                  (= :project (:origin occurrence))
                  :pkl-core.annotation/resolved-product-metadata
                  :else :dotnet.annotation/resolved-external-metadata)]
       (source-ref annotation rule
                   {:mapping {:registry :annotations
                              :identity rule
                              :resolved-key key
                              :origin (:origin occurrence)
                              :resolution (:resolution occurrence)}})))
   (.getAnnotations element)))

(defn- attach-declaration [ctx node element kind owner name signature rule]
  (let [id (register! ctx element kind owner name signature rule)
        node (with-source node element rule {:declaration-id id :declaration-kind kind})]
    (reduce #(csharp/with-source %1 %2) node (annotated-sources ctx element))))

(defn- visibility [^CtModifiable element default]
  (cond
    (modifier? element ModifierKind/PUBLIC) "public"
    (modifier? element ModifierKind/PROTECTED) "protected internal"
    (modifier? element ModifierKind/PRIVATE) "private"
    :else default))

(defn- join-words [words]
  (str (str/join " " (remove str/blank? words)) " "))

(defn- parameter-node [ctx owner ^CtParameter parameter]
  (let [name (identifier (.getSimpleName parameter))
        type (type-node ctx (.getType parameter))
        prefix (when (.isVarArgs parameter) "params ")
        node (sequence-node [(raw (or prefix "")) type (raw (str " " name))])]
    (attach-declaration ctx node parameter :parameter owner name nil
                        :java.declaration/parameter)))

(defn- formal-node [ctx owner ^CtTypeParameter parameter]
  (let [name (identifier (.getSimpleName parameter))]
    (attach-declaration ctx (raw name) parameter :type-parameter owner name nil
                        :java.declaration/type-parameter)))

(defn- formals [ctx owner declarer]
  (let [parameters (vec (.getFormalCtTypeParameters declarer))]
    {:parameters parameters
     :node (when (seq parameters)
             (sequence-node [(raw "<")
                             (sequence-node (mapv #(formal-node ctx owner %) parameters) ", ")
                             (raw ">")]))}))

(defn- constraint-types [^CtTypeParameter parameter]
  (vec (remove nil? (concat [(.getSuperclass parameter)]
                            (.getSuperInterfaces parameter)))))

(defn- constraints-node [ctx parameters]
  (when (seq parameters)
    (let [clauses
          (keep (fn [^CtTypeParameter parameter]
                  (let [bounds (remove #(= "java.lang.Object" (.getQualifiedName ^CtTypeReference %))
                                       (constraint-types parameter))]
                    (when (seq bounds)
                      (sequence-node [(raw (str " where " (identifier (.getSimpleName parameter)) " : "))
                                      (sequence-node (mapv #(type-node ctx %) bounds) ", ")]))))
                parameters)]
      (when (seq clauses) (sequence-node clauses)))))

(defn- blocker! [ctx ^CtElement element blocker-kind owner]
  (let [number (swap! (:blocker-counter ctx) inc)
        id (format "VIBEFORMER_%s_%04d" (-> blocker-kind name str/upper-case (str/replace "-" "_")) number)
        diagnostic {:id id :severity :error :blocking? true :kind blocker-kind
                    :message "Executable Java semantics are pending direct recursive translation"
                    :owner owner :source (source-ref element :java.executable/pending)}]
    (swap! (:diagnostics ctx) conj diagnostic)
    id))

(defn- translated-node [ctx ^CtElement element]
  (let [translation (java-body/translate (:body-context ctx) element)]
    (swap! (:body-translations ctx) conj translation)
    (:node translation)))

(defn- executable-owner [^CtExecutable executable]
  (let [type (.getDeclaringType executable)]
    (str (.getQualifiedName type) "#" (.getSignature executable))))

(defn- method-name [^CtMethod method]
  (let [simple-name (.getSimpleName method)
        owner (some-> method .getDeclaringType .getQualifiedName)]
    (cond
      (and (= owner "org.pkl.core.module.ModuleKeys") (= simple-name "synthetic"))
      "CreateSynthetic"
      (= simple-name "toString") "ToString"
      (= simple-name "hashCode") "GetHashCode"
      (= simple-name "equals") "Equals"
      :else (pascal simple-name))))

(defn- top-definitions [^CtMethod method]
  (vec (.getTopDefinitions method)))

(defn- class-definition? [^CtMethod method]
  (some #(not (instance? CtInterface (.getDeclaringType ^CtMethod %)))
        (top-definitions method)))

(defn- java-object-override? [^CtMethod method]
  (let [name (.getSimpleName method)
        parameters (vec (.getParameters method))]
    (or (and (contains? #{"toString" "hashCode"} name) (empty? parameters))
        (and (= "equals" name)
             (= 1 (count parameters))
             (= "java.lang.Object" (.getQualifiedName (.getType ^CtParameter (first parameters))))))))

(defn- inherited-interface-contract? [^CtType owner-type ^CtMethod method]
  (let [interface-types
        (keep (fn [^CtMethod definition]
                (let [owner (.getDeclaringType definition)]
                  (when (instance? CtInterface owner)
                    (.getReference owner))))
              (top-definitions method))]
    (boolean
     (when (seq interface-types)
       (loop [superclass (when (instance? CtClass owner-type)
                           (.getSuperclass ^CtClass owner-type))]
         (when superclass
           (or (some #(.isSubtypeOf superclass ^CtTypeReference %) interface-types)
               (recur (some-> superclass .getTypeDeclaration .getSuperclass)))))))))

(defn- method-modifiers [^CtType owner-type ^CtMethod method body name]
  (let [interface? (instance? CtInterface owner-type)
        sealed-owner? (or (modifier? owner-type ModifierKind/FINAL)
                          (instance? CtRecord owner-type)
                          (instance? CtEnum owner-type))
        static? (modifier? method ModifierKind/STATIC)
        private? (modifier? method ModifierKind/PRIVATE)
        final? (modifier? method ModifierKind/FINAL)
        abstract? (and (not interface?) (nil? body))
        override? (and (not static?)
                       (or (java-object-override? method)
                           (class-definition? method)
                           (inherited-interface-contract? owner-type method)))
        destination-hiding? (and (= "GetType" name) (not override?))]
    [(visibility method (if interface? "public" "internal"))
     (when destination-hiding? "new")
     (when static? "static")
     (when abstract? "abstract")
     (when (and (not abstract?) final? override?) "sealed")
     (when override? "override")
     (when (and (not interface?) (not static?) (not private?) (not final?)
                (not sealed-owner?) (not abstract?) (not override?))
       "virtual")]))

(defn- synthetic-formals-node [^CtMethod method]
  (let [parameters (vec (.getFormalCtTypeParameters method))]
    {:parameters parameters
     :node (when (seq parameters)
             (sequence-node
              [(raw "<")
               (sequence-node
                (mapv #(with-source (raw (identifier (.getSimpleName ^CtTypeParameter %)))
                                    % :dotnet.interface/deferred-type-parameter {})
                      parameters)
                ", ")
               (raw ">")]))}))

(defn- deferred-interface-method-node [ctx ^CtMethod method]
  (let [owner (executable-owner method)
        name (method-name method)
        {:keys [parameters node]} (synthetic-formals-node method)
        body (.getBody method)
        params (mapv (fn [^CtParameter parameter]
                       (sequence-node [(type-node ctx (.getType parameter))
                                       (raw (str " " (identifier (.getSimpleName parameter))))]))
                     (.getParameters method))
        declaration
        (sequence-node
         [(raw (if body "public virtual " "public abstract "))
          (type-node ctx (.getType method)) (raw (str " " name)) node
          (raw "(") (sequence-node params ", ") (raw ")")
          (constraints-node ctx parameters)
          (if body
            (sequence-node [(raw " ") (:node (java-body/translate (:body-context ctx) body))])
            (raw ";"))])]
    (with-source declaration method (if body
                                      :dotnet.interface/inherited-default-contract
                                      :dotnet.interface/deferred-abstract-contract)
      {:owner owner :signature (.getSignature method)})))

(defn- missing-interface-contracts [ctx ^CtType type]
  (when (and (nil? (:selected-declarations ctx))
             (instance? CtClass type)
             (modifier? type ModifierKind/ABSTRACT))
    (let [own-methods (vec (.getMethods type))]
      (->> (.getSuperInterfaces type)
           (keep #(.getTypeDeclaration ^CtTypeReference %))
           (mapcat #(.getMethods ^CtType %))
           (remove (fn [^CtMethod contract]
                     (some #(.isOverriding ^CtMethod % contract) own-methods)))
           (sort-by #(.getSignature ^CtMethod %))
           (mapv #(deferred-interface-method-node ctx %))))))

(defn- method-node [ctx owner-type ^CtMethod method]
  (let [owner (executable-owner method)
        name (method-name method)
        {:keys [parameters node]} (formals ctx owner method)
        params (mapv #(parameter-node ctx owner %) (.getParameters method))
        body (.getBody method)
        words (method-modifiers owner-type method body name)
        signature (str name "(" (str/join "," (map #(.getQualifiedName (.getType ^CtParameter %))
                                                    (.getParameters method))) ")")
        return-type (type-node ctx (.getType method))
        return-type (if (and (nullable-annotation? method)
                             (not (.isPrimitive (.getType method))))
                      (sequence-node [return-type (raw "?")])
                      return-type)
        declaration
        (sequence-node
         [(raw (join-words words))
          return-type (raw (str " " name)) node
          (raw "(") (sequence-node params ", ") (raw ")")
          (constraints-node ctx parameters)
          (if body (sequence-node [(raw " ") (translated-node ctx body)]) (raw ";"))])]
    (attach-declaration ctx declaration method :method (.getQualifiedName owner-type)
                        name signature :java.declaration/method)))

(defn- constructor-node [ctx ^CtType owner-type ^CtConstructor constructor]
  (let [owner (executable-owner constructor)
        name (identifier (.getSimpleName owner-type))
        {:keys [parameters node]} (formals ctx owner constructor)
        params (mapv #(parameter-node ctx owner %) (.getParameters constructor))
        body (.getBody constructor)
        signature (str ".ctor(" (str/join "," (map #(.getQualifiedName (.getType ^CtParameter %))
                                                   (.getParameters constructor))) ")")
        explicit-invocation (when body
                              (java-body/explicit-constructor-invocation
                               (:body-context ctx) body))
        initializer (when explicit-invocation
                      (java-body/constructor-initializer
                       (:body-context ctx) explicit-invocation))
        constructor-visibility (if (and (modifier? constructor ModifierKind/PRIVATE)
                                        (not (.isTopLevel owner-type)))
                                 "internal"
                                 (visibility constructor "internal"))
        declaration
        (sequence-node
         [(raw (join-words [constructor-visibility]))
          (raw name) node (raw "(") (sequence-node params ", ") (raw ")") initializer
          (constraints-node ctx parameters)
          (if body (sequence-node [(raw " ") (translated-node ctx body)]) (raw ";"))])]
    (attach-declaration ctx declaration constructor :constructor
                        (.getQualifiedName owner-type) name signature
                        :java.declaration/constructor)))

(defn- field-name [^CtField field]
  (identifier (.getSimpleName field)))

(defn- field-node [ctx ^CtType owner-type ^CtField field]
  (let [owner (.getQualifiedName owner-type)
        enum-value? (instance? CtEnumValue field)
        name (if enum-value? (identifier (.getSimpleName field)) (field-name field))
        initializer (.getDefaultExpression field)
        words [(visibility field (if enum-value? "public" "internal"))
               (when (or enum-value? (modifier? field ModifierKind/STATIC)) "static")
               (when (or enum-value? (modifier? field ModifierKind/FINAL)) "readonly")
               (when (modifier? field ModifierKind/VOLATILE) "volatile")]
        declaration
        (sequence-node
         [(raw (join-words words)) (type-node ctx (.getType field))
          (raw (str " " name))
          (when initializer (sequence-node [(raw " = ") (translated-node ctx initializer)]))
          (raw ";")])]
    (attach-declaration ctx declaration field
                        (if enum-value? :enum-value :field) owner name nil
                        (if enum-value? :java.declaration/enum-value :java.declaration/field))))

(defn- record-component-node [ctx ^CtType owner-type ^CtRecordComponent component]
  (let [owner (.getQualifiedName owner-type)
        name (record-component-name owner-type component)
        node (sequence-node [(type-node ctx (.getType component)) (raw (str " " name))])]
    (attach-declaration ctx node component :record-component owner name nil
                        :java.declaration/record-component)))

(defn- base-types [^CtType type]
  (let [superclass (when (instance? CtClass type) (.getSuperclass ^CtClass type))
        implicit-base? #(contains? #{"java.lang.Object" "java.lang.Record" "java.lang.Enum"}
                                   (some-> ^CtTypeReference % .getQualifiedName))]
    (vec (remove #(or (nil? %) (implicit-base? %))
                 (concat [superclass] (.getSuperInterfaces type))))))

(declare type-node-declaration)

(defn- member-node [ctx ^CtType owner member]
  (cond
    (instance? CtEnumValue member) (field-node ctx owner member)
    (instance? CtField member) (field-node ctx owner member)
    (instance? CtMethod member) (method-node ctx owner member)
    (instance? CtConstructor member) (constructor-node ctx owner member)
    (instance? CtType member) (type-node-declaration ctx member)
    (instance? CtAnonymousExecutable member)
    (let [id (blocker! ctx member :unsupported-initializer-block (.getQualifiedName owner))]
      (with-source (raw (str "#error " id " Java initializer block requires direct Spoon translation"))
        member :java.executable/pending {:diagnostic-id id}))
    :else
    (throw (ex-info (str "Unsupported live Spoon type member " (.getName (class member)))
                    {:kind :unsupported-declaration-member
                     :owner (.getQualifiedName owner)
                     :source (source-ref member :java.declaration/member)}))))

(defn- type-words [^CtType type]
  (let [visibility (visibility type (if (.isTopLevel type) "internal" "private"))]
    (cond
      (instance? CtInterface type) [visibility "partial" "interface"]
      (instance? CtRecord type) [visibility "sealed" "partial" "record" "class"]
      (instance? CtEnum type) [visibility "sealed" "partial" "class"]
      :else [visibility
             (when (modifier? type ModifierKind/SEALED) "/* Java sealed hierarchy */")
             (when (modifier? type ModifierKind/NON_SEALED) "/* Java non-sealed hierarchy */")
             (when (modifier? type ModifierKind/ABSTRACT) "abstract")
             (when (modifier? type ModifierKind/FINAL) "sealed")
             "partial" "class"])))

(defn- type-node-declaration [ctx ^CtType type]
  (let [owner (some-> type .getDeclaringType .getQualifiedName)
        name (identifier (.getSimpleName type))
        qualified (.getQualifiedName type)
        {:keys [parameters node]} (formals ctx qualified type)
        components (when (instance? CtRecord type)
                     (mapv #(record-component-node ctx type %)
                           (.getRecordComponents ^CtRecord type)))
        bases (mapv #(type-node ctx %) (base-types type))
        raw-members (concat (when (instance? CtEnum type)
                              (.getEnumValues ^CtEnum type))
                            (.getTypeMembers type))
        members (->> raw-members
                     (reduce (fn [result member]
                               (if (some #(identical? member %) result)
                                 result
                                 (conj result member))) [])
                     (remove #(.isImplicit ^CtElement %))
                     (filter #(selected-declaration? ctx %))
                     (sort-by (fn [^CtElement member]
                                (let [{:keys [file line column]} (spoon/source-location member)]
                                  [file line column])))
                     (mapv #(member-node ctx type %)))
        members (into (vec (missing-interface-contracts ctx type)) members)
        header (sequence-node
                [(raw (join-words (type-words type))) (raw name) node
                 (when components
                   (sequence-node [(raw "(") (sequence-node components ", ") (raw ")")]))
                 (when (seq bases)
                   (sequence-node [(raw " : ") (sequence-node bases ", ")]))
                 (constraints-node ctx parameters)])
        declaration (sequence-node
                     [header (raw "\n{\n")
                      (sequence-node members "\n\n")
                      (raw "\n}")])]
    (attach-declaration ctx declaration type :type owner name qualified
                        (cond
                          (instance? CtInterface type) :java.declaration/interface
                          (instance? CtRecord type) :java.declaration/record
                          (instance? CtEnum type) :java.declaration/enum
                          :else :java.declaration/class))))

(defn- collision-errors [declarations]
  (let [nested-types (filter #(and (= :type (:kind %)) (:owner %)) declarations)
        values (filter #(contains? #{:field :enum-value :record-component} (:kind %)) declarations)
        methods (filter #(= :method (:kind %)) declarations)
        constructors (filter #(= :constructor (:kind %)) declarations)
        parameters (filter #(= :parameter (:kind %)) declarations)
        type-parameters (filter #(= :type-parameter (:kind %)) declarations)
        non-callable (concat nested-types values)
        non-callable-names (set (map (juxt :owner :name) non-callable))
        duplicate-groups
        (concat
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name) non-callable)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name :signature) methods)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :signature) constructors)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name) parameters)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name) type-parameters)))
         (map (fn [method]
                [method {:kind :conflicting-non-callable
                         :owner (:owner method) :name (:name method)}])
              (filter #(contains? non-callable-names [(:owner %) (:name %)]) methods)))]
    (mapv #(mapv (fn [entry] (select-keys entry [:id :kind :owner :name :signature])) %)
          duplicate-groups)))

(defn- annotation-decisions [ctx]
  (->> (:occurrences (:resolved-model ctx))
       (filter #(= :annotation (:kind %)))
       (map :reference)
       (sort-by (fn [^CtAnnotation annotation]
                  (let [{:keys [file line column]} (spoon/source-location annotation)]
                    [file line column (.getQualifiedName (.getAnnotationType annotation))])))
       (mapv (fn [^CtAnnotation annotation]
               (let [occurrence (occurrence! ctx annotation :annotation)
                     key (:key occurrence)
                     strategy (cond
                                (= key "annotation:org.jspecify.annotations.Nullable")
                                :csharp-nullable-metadata
                                (= key "annotation:org.jspecify.annotations.NullMarked")
                                :project-nullable-context
                                (= key "annotation:java.lang.Override")
                                :csharp-language-semantics
                                (= key "annotation:java.lang.SuppressWarnings")
                                :source-analysis-only
                                (= :project (:origin occurrence))
                                :resolved-product-metadata
                                :else :resolved-external-metadata)]
                 {:source (source-ref annotation :java.annotation/resolved)
                  :resolved-key key :origin (:origin occurrence)
                  :strategy strategy :emitted-runtime-attribute false})))))

(defn- xml-escape [value]
  (-> (str value) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- project-text [configuration resource-artifacts]
  (let [project (:project configuration)
        package (:package configuration)
        output (:output configuration)]
    (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
         "  <PropertyGroup>\n"
         "    <TargetFramework>" (xml-escape (:target-framework project)) "</TargetFramework>\n"
         "    <Nullable>" (xml-escape (:nullable project)) "</Nullable>\n"
         "    <ImplicitUsings>" (if (:implicit-usings project) "enable" "disable") "</ImplicitUsings>\n"
         "    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>\n"
         "    <AssemblyName>" (xml-escape (:assembly-name project)) "</AssemblyName>\n"
         "    <RootNamespace>" (xml-escape (:root-namespace project)) "</RootNamespace>\n"
         "    <PackageId>" (xml-escape (:id package)) "</PackageId>\n"
         "    <Version>" (xml-escape (:version package)) "</Version>\n"
         "    <Description>" (xml-escape (:description package)) "</Description>\n"
         "    <Authors>" (xml-escape (:authors package)) "</Authors>\n"
         "    <PackageTags>" (xml-escape (:tags package)) "</PackageTags>\n"
         "    <PackageRequireLicenseAcceptance>false</PackageRequireLicenseAcceptance>\n"
         "    <IsPackable>true</IsPackable>\n"
         "  </PropertyGroup>\n"
         "  <ItemGroup>\n"
         "    <Compile Include=\"" (xml-escape (:source-directory output)) "/**/*.cs\" />\n"
         (apply str
                (for [{:keys [destination logical-name]}
                      (sort-by :destination resource-artifacts)]
                  (str "    <EmbeddedResource Include=\""
                       (xml-escape destination)
                       "\" LogicalName=\"" (xml-escape logical-name) "\" />\n")))
         "  </ItemGroup>\n"
         "</Project>\n")))

(defn- resource-relative [^Path resource-root ^Path resource]
  (let [root (.normalize resource-root)
        resource (.normalize resource)]
    (when-not (.startsWith resource root)
      (throw (ex-info "Production resource is outside the Gradle resource output root"
                      {:kind :unmapped-production-resource
                       :root (str root)
                       :path (str resource)})))
    (str/replace (str (.relativize root resource)) "\\" "/")))

(defn- portable [^Path root value]
  (let [path (paths/absolute value)]
    (str/replace (if (.startsWith path root) (str (.relativize root path)) (str path)) "\\" "/")))

(defn- source-accounting [ctx workspace-root files]
  (let [root (paths/absolute workspace-root)
        diagnostics @(:diagnostics ctx)
        by-file (group-by #(get-in % [:source :location :file]) diagnostics)
        outputs-by-file
        (group-by #(get-in % [:source :location :file])
                  (filter #(and (= :type (:kind %)) (nil? (:owner %)))
                          @(:declarations ctx)))]
    (mapv
     (fn [source]
       (let [canonical (.getCanonicalPath (.toFile ^Path source))
             types (get outputs-by-file canonical)
             package-info? (= "package-info.java" (str (.getFileName ^Path source)))]
         (when-not (or (seq types) package-info?)
           (throw (ex-info "Production source has no emitted declaration or package mapping"
                           {:kind :unaccounted-production-source :path canonical})))
         {:source (portable root source)
          :strategy (if package-info? :package-nullability-metadata :generated-csharp)
          :top-level-declarations (mapv :name types)
          :hard-failures (count (get by-file canonical))}))
     (sort-by str files))))

(defn- selected-source-files [resolved-model discovery]
  (if-let [source-inputs (:source-inputs resolved-model)]
    (mapv (comp paths/path key) source-inputs)
    (:java-sources discovery)))

(defn- selected-declaration-index [resolved-model]
  (when-let [declarations (:declarations resolved-model)]
    (let [index (IdentityHashMap.)]
      (doseq [[_ {:keys [declaration]}] declarations]
        (.put index declaration true))
      index)))

(defn- resource-mapping [configuration relative]
  (or (get-in configuration [:resources relative])
      (when (= :embedded-resource-preserve-path
               (get-in configuration [:resource-policy :strategy]))
        {:strategy :embedded-resource
         :destination relative
         :logical-name (str/replace relative "/" ".")})))

(defn emit-project!
  "Emits declaration-complete, body-blocked C# project inputs from a live model."
  [{:keys [workspace-root target discovery resolved-model configuration]}]
  (let [configuration (validate-configuration! configuration)
        root (paths/absolute workspace-root)
        project-root (paths/resolve-path target (get-in configuration [:output :project-directory]))
        source-root (paths/resolve-path project-root (get-in configuration [:output :source-directory]))
        ctx-holder (atom nil)
        base-context {:configuration configuration
                      :resolved-model resolved-model
                      :occurrence-index (java/resolved-occurrence-index resolved-model)
                      :selected-declarations (selected-declaration-index resolved-model)
                      :emitted (IdentityHashMap.)
                      :declarations (atom [])
                      :diagnostics (atom [])
                      :blocker-counter (atom 0)
                      :body-translations (atom [])}
        services {:identifier identifier
                  :pascal pascal
                  :method-name method-name
                  :record-component-name record-component-name
                  :local-name (fn [^CtElement element]
                                (let [{:keys [line column]} (spoon/source-location element)]
                                  (str (identifier (.getSimpleName ^spoon.reflect.declaration.CtNamedElement element))
                                       "__" (or line 0) "_" (or column 0))))
                  :type-node (fn [reference] (type-node @ctx-holder reference))}
        body-context (java-body/context resolved-model services)
        ctx (assoc base-context :body-context body-context)
        _ (reset! ctx-holder ctx)
        roots (java/project-roots resolved-model)
        declaration-artifacts
        (mapv
         (fn [^CtType type]
           (let [namespace (destination-namespace ctx type)
                 relative (str (str/replace namespace "." "/") "/"
                               (identifier (.getSimpleName type)) ".cs")
                 file (paths/resolve-path source-root relative)
                 node (sequence-node [(raw (str "// <auto-generated />\n#nullable "
                                                (get-in configuration [:project :nullable])
                                                "\nnamespace " namespace ";\n\n"))
                                      (type-node-declaration ctx type) (raw "\n")])
                 rendered (csharp/render node)]
             (write-text! file (:text rendered))
             {:file (portable project-root file)
              :source (spoon/source-location type)
              :mappings (mapv #(assoc % :file (portable project-root file))
                              (:mappings rendered))}))
         roots)
        helper-source (paths/resolve-path root "vibeformer/runtime/Vibeformer.JavaCompat.cs")
        helper-file (paths/resolve-path source-root "Vibeformer/Runtime/JavaCompat.cs")
        _ (Files/createDirectories (.getParent helper-file)
                                   (make-array java.nio.file.attribute.FileAttribute 0))
        _ (Files/copy helper-source helper-file
                      (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
        artifacts (conj declaration-artifacts
                        {:file (portable project-root helper-file)
                         :source {:file (portable root helper-source) :line 1 :column 1}
                         :mappings []
                         :strategy :reviewable-java-compatibility-source})
        artifact-collisions (->> artifacts (group-by :file) vals (filter #(< 1 (count %))) vec)
        declaration-collisions (collision-errors @(:declarations ctx))]
    (when (or (seq artifact-collisions) (seq declaration-collisions))
      (throw (ex-info "Generated declaration names or files collide"
                      {:kind :generated-declaration-collision
                       :file-collisions (mapv #(mapv :file %) artifact-collisions)
                       :declaration-collisions declaration-collisions})))
    (let [resource-artifacts
          (mapv
           (fn [^Path source]
             (let [relative (resource-relative (:resource-root discovery) source)
                   mapping (resource-mapping configuration relative)]
               (when-not mapping
                 (throw (ex-info "Production resource has no explicit destination mapping"
                                 {:kind :unmapped-production-resource :resource relative})))
               (let [destination (paths/resolve-path project-root
                                                     (get-in configuration [:output :resource-directory])
                                                     (:destination mapping))]
                 (Files/createDirectories (.getParent destination)
                                          (make-array java.nio.file.attribute.FileAttribute 0))
                 (Files/copy source destination
                             (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
                 {:source (portable root source)
                  :destination (portable project-root destination)
                  :strategy (:strategy mapping)
                  :logical-name (:logical-name mapping)})))
           (sort-by str (:resources discovery)))
          project-file (paths/resolve-path project-root (get-in configuration [:output :project-file]))
          source-map-file (paths/resolve-path project-root (get-in configuration [:output :source-map-file]))
          diagnostics-file (paths/resolve-path project-root (get-in configuration [:output :diagnostics-file]))
          manifest-file (paths/resolve-path project-root (get-in configuration [:output :manifest-file]))
          annotations-file (paths/resolve-path project-root (get-in configuration [:output :annotation-decisions-file]))
          mappings (vec (mapcat :mappings artifacts))
          declaration-ids (set (map :id @(:declarations ctx)))
          mapped-declaration-ids (set (keep #(get-in % [:source :declaration-id]) mappings))
          missing-mappings (sort (remove mapped-declaration-ids declaration-ids))
          accounts (source-accounting ctx root
                                      (selected-source-files resolved-model discovery))
          counts (frequencies (map :kind @(:declarations ctx)))
          body-results @(:body-translations ctx)
          body-coverage (reduce (fn [totals result]
                                  (merge-with + totals (java/coverage-totals result)))
                                {:visited 0 :covered 0 :blocked 0 :structural 0
                                 :semantic 0 :unsupported-elements 0
                                 :missing-mappings 0 :missing-occurrences 0 :fallback 0}
                                body-results)
          summary {:compilation-units (count accounts)
                   :generated-files (count artifacts)
                   :resources (count resource-artifacts)
                   :declarations (count @(:declarations ctx))
                   :declaration-kinds (into (sorted-map) counts)
                   :source-mappings (count mappings)
                   :missing-source-mappings (count missing-mappings)
                   :hard-failures (count @(:diagnostics ctx))
                   :executable-roots (count body-results)
                   :executable-coverage body-coverage
                   :collisions 0
                   :skipped-source-units 0}]
      (when (seq missing-mappings)
        (throw (ex-info "Generated declarations are missing Spoon source mappings"
                        {:kind :missing-declaration-source-mapping
                         :declaration-ids missing-mappings})))
      (write-text! project-file (project-text configuration resource-artifacts))
      (write-text! source-map-file (edn-text {:schema-version 1 :mappings mappings}))
      (write-text! diagnostics-file (edn-text {:schema-version 1 :diagnostics @(:diagnostics ctx)}))
      (write-text! annotations-file
                   (edn-text {:schema-version 1 :decisions (annotation-decisions ctx)}))
      (write-text! manifest-file
                   (edn-text {:schema-version 1
                              :configuration configuration
                              :sources accounts
                              :resources resource-artifacts
                              :artifacts (mapv #(dissoc % :mappings) artifacts)
                              :summary summary}))
      {:project-root project-root
       :project-file project-file
       :manifest-file manifest-file
       :summary summary
       :diagnostics @(:diagnostics ctx)
       :artifacts artifacts
       :source-accounts accounts
       :resource-artifacts resource-artifacts})))
