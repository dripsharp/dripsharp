(ns vibeformer.pkl.java-project
  "Pkl-target declaration and disposable project emission from live Spoon objects.

  This namespace owns Pkl-specific declaration shapes, destination mappings,
  and runtime bridges. The emitted fragments are destination C# structure, not
  a reconstructed Java AST. Every declaration is reached recursively through
  its live Spoon owner, and every type is selected through the resolver's exact
  occurrence identity."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.csharp :as csharp]
            [vibeformer.pkl.java-body :as java-body]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.util IdentityHashMap]
           [spoon.reflect.code CtConstructorCall CtExpression CtLambda CtLiteral CtLocalVariable
            CtThisAccess CtVariableAccess]
           [spoon.reflect.declaration CtAnnotation CtAnonymousExecutable CtClass
            CtConstructor CtElement CtEnum CtEnumValue CtExecutable CtField
            CtFormalTypeDeclarer CtInterface CtMethod CtModifiable CtParameter CtRecord
            CtRecordComponent CtType CtTypeParameter ModifierKind]
           [spoon.reflect.reference CtArrayTypeReference CtIntersectionTypeReference
            CtTypeParameterReference CtTypeReference CtWildcardReference]
           [spoon.reflect.visitor.filter TypeFilter]))

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

(defn- project-reference! [value]
  (let [value (str value)
        path (paths/path value)
        segments (mapv str (iterator-seq (.iterator path)))]
    (when (or (str/blank? value) (.isAbsolute path)
              (some #(= ".." %) (rest segments))
              (not (str/ends-with? value ".csproj")))
      (destination-error "Project reference must name a sibling or child csproj"
                         {:field :project-references :value value}))
    value))

(defn validate-configuration!
  [configuration]
  (when-not (= 1 (:schema-version configuration))
    (destination-error "Unsupported destination configuration schema"
                       {:schema-version (:schema-version configuration)}))
  (doseq [[section keys] [[:project [:assembly-name :root-namespace
                                    :target-framework :nullable :implicit-usings]]
                          [:package [:id :version :title :description :authors :tags
                                     :project-url :repository-url :repository-type]]
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
  (doseq [key [:id :version :title :description :authors :tags
               :project-url :repository-url :repository-type]]
    (let [value (get-in configuration [:package key])]
      (when-not (and (string? value) (not (str/blank? value)))
        (destination-error "Destination package metadata must be a non-blank string"
                           {:section :package :setting key :value value}))))
  (doseq [key [:project-directory :source-directory :resource-directory
               :project-file :source-map-file :diagnostics-file :manifest-file
               :annotation-decisions-file]]
    (relative-path! (get-in configuration [:output key]) (name key)))
  (when-not (contains? #{"enable" "disable"}
                       (get-in configuration [:project :nullable]))
    (destination-error "Destination nullable setting must be enable or disable"
                       {:nullable (get-in configuration [:project :nullable])}))
  (when-not (or (nil? (get-in configuration [:project :define-constants]))
                (and (vector? (get-in configuration [:project :define-constants]))
                     (every? #(and (string? %)
                                   (re-matches #"[A-Za-z_][A-Za-z0-9_]*" %))
                             (get-in configuration [:project :define-constants]))))
    (destination-error "Destination define constants must be C# identifiers"
                       {:define-constants (get-in configuration [:project :define-constants])}))
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
  (when-not (or (nil? (:project-references configuration))
                (and (vector? (:project-references configuration))
                     (every? project-reference! (:project-references configuration))))
    (destination-error "Invalid destination project references"
                       {:project-references (:project-references configuration)}))
  (when-not (or (nil? (:runtime-sources configuration))
                (and (vector? (:runtime-sources configuration))
                     (every? #(relative-path! % "runtime source")
                             (:runtime-sources configuration))))
    (destination-error "Invalid destination runtime sources"
                       {:runtime-sources (:runtime-sources configuration)}))
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

(defn- same-type? [^CtType left ^CtType right]
  (and left right (= (.getQualifiedName left) (.getQualifiedName right))))

(defn- common-declaring-prefix [left right]
  (loop [left left right right result []]
    (if (and (seq left) (seq right) (same-type? (first left) (first right)))
      (recur (next left) (next right) (conj result (first left)))
      result)))

(defn- raw-generic-segment [^CtType type]
  (let [arity (count (.getFormalCtTypeParameters type))]
    (str (pascal (.getSimpleName type))
         (when (pos? arity)
           (str "<" (str/join ", " (repeat arity "object")) ">")))))

(defn- project-type-base
  "Emits the resolved project declaration path up to, but not including, the
  reference's own type arguments. Java static nested types do not capture a
  generic declaring type, whereas C# nests them in every constructed declaring
  type. References within the same lexical owner can use the nested name
  directly; references from elsewhere select a stable object-closed owner."
  [ctx ^CtType declaration]
  (let [declarations (declaring-types declaration)
        current (some-> (:current-type ctx) declaring-types)
        current-type (last current)
        ;; A nested base can have the same simple name as its derived nested
        ;; subtype (Message.Response and Message.Client.Response). A relative
        ;; `Response` reference binds to the subtype in C# and creates a cycle;
        ;; force the resolved sibling declaration through its full owner path.
        shadowed-by-current?
        (and current-type
             (not (same-type? declaration current-type))
             (= (.getSimpleName declaration) (.getSimpleName ^CtType current-type)))
        ;; C# resolves a base clause before the derived type's nested members
        ;; enter scope, so nested type arguments there also need their full
        ;; owner path.
        common (if (or (:base-clause? ctx) shadowed-by-current?)
                 []
                 (common-declaring-prefix declarations current))
        relative (drop (count common) declarations)
        ;; A reference to the current type or one of its lexical ancestors has
        ;; no remaining relative segment. Keep its own declaration name.
        relative (if (seq relative) relative [declaration])
        prefix (when (empty? common)
                 (str "global::" (destination-namespace ctx declaration) "."))
        owners (butlast relative)
        leaf (last relative)]
    (if (and (.getDeclaringType declaration)
             (str/starts-with? (.getQualifiedName declaration)
                               "org.pkl.core.util.paguro.RrbTree$"))
      ;; Java's RRB helper types are static nested declarations and therefore
      ;; do not capture RrbTree<E>.  C# nested types do capture their generic
      ;; owner, so select one stable object-closed owner at every reference.
      (str "global::" (destination-namespace ctx declaration) ".RrbTree<object>."
           (str/join "." (concat (map raw-generic-segment (rest (butlast declarations)))
                                  [(pascal (.getSimpleName ^CtType leaf))])))
      (str prefix
           (str/join "." (concat (map raw-generic-segment owners)
                                  [(pascal (.getSimpleName ^CtType leaf))]))))))

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
   "java.lang.Number" ["global::System.IConvertible" :dotnet.type/number]
   "java.lang.Character" ["char" :dotnet.type/char]
   "java.lang.Class" ["global::System.Type" :dotnet.type/type]
   "java.lang.ClassLoader" ["global::System.Reflection.Assembly" :dotnet.type/assembly]
   "java.lang.Enum" ["object" :dotnet.type/enum-base]
   "java.lang.Record" ["object" :dotnet.type/record-base]
   "java.lang.Float" ["float" :dotnet.type/single]
   "java.lang.Double" ["double" :dotnet.type/double]
   "java.lang.NumberFormatException" ["global::System.FormatException" :dotnet.type/format-exception]
   "java.lang.AbstractStringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.StringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.Appendable" ["global::Pkl.Core.Runtime.JavaAppendable" :pkl-core.type/appendable]
   "java.lang.Math" ["global::System.Math" :dotnet.type/math]
   "java.lang.StrictMath" ["global::System.Math" :dotnet.type/math]
   "java.lang.System" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.lang.Thread" ["global::Pkl.Core.Runtime.JavaThread" :pkl-core.type/thread]
   "java.lang.Process" ["global::Vibeformer.Runtime.JavaProcess" :dotnet.type/process]
   "java.lang.ProcessBuilder" ["global::Vibeformer.Runtime.JavaProcessBuilder" :dotnet.type/process-builder]
   "java.lang.ProcessBuilder$Redirect" ["global::Vibeformer.Runtime.JavaProcessRedirect" :dotnet.type/process-redirect]
   "java.lang.invoke.MethodHandles" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.lang.invoke.MethodHandles$Lookup" ["object" :dotnet.type/method-lookup-marker]
   "java.lang.invoke.MethodHandle" ["global::System.Delegate" :dotnet.type/delegate]
   "java.lang.invoke.MethodType" ["object" :dotnet.type/method-type-marker]
   "java.lang.invoke.VarHandle" ["object" :dotnet.type/var-handle-marker]
   "java.math.BigInteger" ["global::System.Numerics.BigInteger" :dotnet.type/big-integer]
   "java.math.BigDecimal" ["decimal" :dotnet.type/decimal]
   "java.math.RoundingMode" ["global::Pkl.Core.Runtime.JavaRoundingMode" :pkl-core.type/rounding-mode]
   ;; java.lang.Void is a reference-type marker (not the Java `void`
   ;; primitive).  Mapping it to System.Object keeps it legal in generic
   ;; positions such as PClassInfo<Void>; primitive void is handled by the
   ;; intrinsic registry below.
   "java.lang.Void" ["object" :dotnet.type/void-marker]
   "java.lang.Throwable" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.Exception" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.RuntimeException" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.IllegalArgumentException" ["global::System.ArgumentException" :dotnet.type/argument-exception]
   "java.lang.IllegalStateException" ["global::System.InvalidOperationException" :dotnet.type/invalid-operation]
   "java.lang.IndexOutOfBoundsException" ["global::System.ArgumentOutOfRangeException" :dotnet.type/argument-out-of-range]
   "java.lang.ArrayIndexOutOfBoundsException" ["global::System.IndexOutOfRangeException" :dotnet.type/index-out-of-range]
   "java.lang.ClassCastException" ["global::System.InvalidCastException" :dotnet.type/invalid-cast]
   "java.lang.NullPointerException" ["global::System.NullReferenceException" :dotnet.type/null-reference]
   "java.lang.NegativeArraySizeException" ["global::System.ArgumentOutOfRangeException" :dotnet.type/argument-out-of-range]
   "java.lang.InterruptedException" ["global::System.Threading.ThreadInterruptedException" :dotnet.type/thread-interrupted]
   "java.lang.AssertionError" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.Error" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.StackOverflowError" ["global::System.StackOverflowException" :dotnet.type/stack-overflow]
   "java.lang.OutOfMemoryError" ["global::System.OutOfMemoryException" :dotnet.type/out-of-memory]
   "java.lang.ArithmeticException" ["global::System.ArithmeticException" :dotnet.type/arithmetic-exception]
   "java.lang.ExceptionInInitializerError" ["global::System.TypeInitializationException" :dotnet.type/type-initialization-exception]
   "java.lang.UnsupportedOperationException" ["global::System.NotSupportedException" :dotnet.type/not-supported-exception]
   "java.lang.StackTraceElement" ["global::System.Diagnostics.StackFrame" :dotnet.type/stack-frame]
   "java.lang.Runnable" ["global::System.Action" :dotnet.type/action]
   "java.lang.AutoCloseable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.io.Closeable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.lang.Comparable" ["global::System.IComparable" :dotnet.type/comparable]
   "java.io.IOException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.io.InputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.OutputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.ByteArrayInputStream" ["global::System.IO.MemoryStream" :dotnet.type/memory-stream]
   "java.io.ObjectInputStream" ["global::System.IO.BinaryReader" :dotnet.type/binary-reader]
   "java.io.OutputStreamWriter" ["global::System.IO.StreamWriter" :dotnet.type/stream-writer]
   "java.io.FileWriter" ["global::System.IO.StreamWriter" :dotnet.type/stream-writer]
   "java.io.BufferedWriter" ["global::Pkl.Core.Runtime.JavaBufferedWriter" :pkl-core.type/buffered-writer]
   "java.io.File" ["string" :dotnet.type/path]
   "java.io.Flushable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.io.UnsupportedEncodingException" ["global::System.ArgumentException" :dotnet.type/argument-exception]
   "java.io.Console" ["object" :dotnet.type/console-marker]
   "java.io.PrintStream" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.FileNotFoundException" ["global::System.IO.FileNotFoundException" :dotnet.type/file-not-found-exception]
   "java.io.UncheckedIOException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.io.Reader" ["global::System.IO.TextReader" :dotnet.type/text-reader]
   "java.io.StringReader" ["global::System.IO.StringReader" :dotnet.type/string-reader]
   "java.io.Writer" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.PrintWriter" ["global::Pkl.Core.Runtime.JavaPrintWriter" :pkl-core.type/print-writer]
   "java.io.Serializable" ["object" :dotnet.type/serializable-marker]
   "java.io.Serial" ["object" :dotnet.annotation/compile-time-metadata]
   "java.net.URI" ["global::System.Uri" :dotnet.type/uri]
   "java.net.URL" ["global::System.Uri" :dotnet.type/uri]
   "java.net.ConnectException" ["global::System.Net.Sockets.SocketException" :dotnet.type/socket-exception]
   "java.net.UnknownHostException" ["global::System.Net.Sockets.SocketException" :dotnet.type/socket-exception]
   "java.net.Inet4Address" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.Inet6Address" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.InetAddress" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.InetSocketAddress" ["global::System.Net.IPEndPoint" :dotnet.type/ip-endpoint]
   "java.net.SocketAddress" ["global::System.Net.EndPoint" :dotnet.type/endpoint]
   "java.net.Proxy" ["global::System.Net.WebProxy" :dotnet.type/web-proxy]
   "java.net.Proxy$Type" ["global::Pkl.Core.Runtime.JavaProxyType" :pkl-core.type/proxy-type]
   "java.net.ProxySelector" ["global::Pkl.Core.Runtime.JavaProxySelector" :pkl-core.type/proxy-selector]
   "java.net.JarURLConnection" ["global::Pkl.Core.Runtime.JavaJarConnection" :pkl-core.type/jar-connection]
   "java.net.URLConnection" ["global::Pkl.Core.Runtime.JavaUrlConnection" :pkl-core.type/url-connection]
   "java.net.URISyntaxException" ["global::System.UriFormatException" :dotnet.type/uri-format-exception]
   "java.net.URLEncoder" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.net.http.HttpClient" ["global::Pkl.Core.Runtime.JavaHttpClient" :pkl-core.type/http-client]
   "java.net.http.HttpClient$Builder" ["global::Pkl.Core.Runtime.JavaHttpClient.Builder" :pkl-core.type/http-client-builder]
   "java.net.http.HttpClient$Redirect" ["global::Pkl.Core.Runtime.JavaHttpRedirect" :pkl-core.type/http-redirect]
   "java.net.http.HttpClient$Version" ["global::Pkl.Core.Runtime.JavaHttpVersion" :pkl-core.type/http-version]
   "java.net.http.HttpRequest" ["global::Pkl.Core.Runtime.JavaHttpRequest" :pkl-core.type/http-request]
   "java.net.http.HttpRequest$Builder" ["global::Pkl.Core.Runtime.JavaHttpRequest.Builder" :pkl-core.type/http-request-builder]
   "java.net.http.HttpRequest$BodyPublisher" ["object" :pkl-core.type/http-body-publisher]
   "java.net.http.HttpRequest$BodyPublishers" ["global::Pkl.Core.Runtime.JavaHttpBodyPublishers" :pkl-core.type/http-body-publishers]
   "java.net.http.HttpHeaders" ["global::Pkl.Core.Runtime.JavaHttpHeaders" :pkl-core.type/http-headers]
   "java.net.http.HttpResponse" ["global::Pkl.Core.Runtime.JavaHttpResponse" :pkl-core.type/http-response]
   "java.net.http.HttpResponse$BodyHandler" ["global::Pkl.Core.Runtime.JavaHttpBodyHandler" :pkl-core.type/http-body-handler]
   "java.net.http.HttpResponse$BodyHandlers" ["global::Pkl.Core.Runtime.JavaHttpBodyHandlers" :pkl-core.type/http-body-handlers]
   "java.net.http.HttpTimeoutException" ["global::System.Threading.Tasks.TaskCanceledException" :dotnet.type/http-timeout]
   "java.nio.ByteBuffer" ["global::Pkl.Core.Runtime.JavaByteBuffer" :pkl-core.type/byte-buffer]
   "java.nio.CharBuffer" ["string" :dotnet.type/string]
   "java.nio.charset.Charset" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.nio.charset.CharsetDecoder" ["global::Pkl.Core.Runtime.JavaCharsetDecoder" :pkl-core.type/charset-decoder]
   "java.nio.charset.CharsetEncoder" ["global::Pkl.Core.Runtime.JavaCharsetEncoder" :pkl-core.type/charset-encoder]
   "java.nio.charset.CharacterCodingException" ["global::System.Text.DecoderFallbackException" :dotnet.type/decoder-exception]
   "java.nio.charset.StandardCharsets" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.security.GeneralSecurityException" ["global::System.Security.Cryptography.CryptographicException" :dotnet.type/cryptographic-exception]
   "java.security.NoSuchAlgorithmException" ["global::System.Security.Cryptography.CryptographicException" :dotnet.type/cryptographic-exception]
   "java.security.SecureRandom" ["global::Pkl.Core.Runtime.JavaSecureRandom" :pkl-core.type/secure-random]
   "java.security.MessageDigest" ["global::Pkl.Core.Runtime.JavaMessageDigest" :pkl-core.type/message-digest]
   "java.security.DigestInputStream" ["global::Pkl.Core.Runtime.JavaDigestInputStream" :pkl-core.type/digest-input-stream]
   "java.security.DigestOutputStream" ["global::Pkl.Core.Runtime.JavaDigestOutputStream" :pkl-core.type/digest-output-stream]
   "java.security.KeyStore" ["global::Pkl.Core.Runtime.JavaKeyStore" :pkl-core.type/key-store]
   "java.security.KeyStore$LoadStoreParameter" ["object" :pkl-core.type/key-store-load-parameter]
   "java.security.cert.Certificate" ["global::System.Security.Cryptography.X509Certificates.X509Certificate2" :dotnet.type/x509-certificate]
   "java.security.cert.X509Certificate" ["global::System.Security.Cryptography.X509Certificates.X509Certificate2" :dotnet.type/x509-certificate]
   "java.security.cert.CertificateException" ["global::System.Security.Cryptography.CryptographicException" :dotnet.type/cryptographic-exception]
   "java.security.cert.CertificateFactory" ["global::Pkl.Core.Runtime.JavaCertificateFactory" :pkl-core.type/certificate-factory]
   "javax.net.ssl.SSLContext" ["global::Pkl.Core.Runtime.JavaSslContext" :pkl-core.type/ssl-context]
   "javax.net.ssl.SSLException" ["global::System.Net.Http.HttpRequestException" :dotnet.type/http-request-exception]
   "javax.net.ssl.SSLHandshakeException" ["global::System.Security.Authentication.AuthenticationException" :dotnet.type/authentication-exception]
   "javax.net.ssl.TrustManagerFactory" ["global::Pkl.Core.Runtime.JavaTrustManagerFactory" :pkl-core.type/trust-manager-factory]
   "javax.net.ssl.TrustManager" ["object" :pkl-core.type/trust-manager]
   "javax.net.ssl.KeyManager" ["object" :pkl-core.type/key-manager]
   "java.nio.file.Path" ["string" :dotnet.type/path]
   "java.nio.file.LinkOption" ["object" :dotnet.type/link-option-marker]
   "java.nio.file.OpenOption" ["object" :dotnet.type/open-option-marker]
   "java.nio.file.CopyOption" ["object" :dotnet.type/copy-option-marker]
   "java.nio.file.FileVisitOption" ["object" :dotnet.type/file-visit-option-marker]
   "java.nio.file.attribute.FileAttribute" ["object" :dotnet.type/file-attribute-marker]
   "java.nio.file.Files" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.nio.file.DirectoryStream" ["global::Vibeformer.Runtime.JavaDirectoryStream" :dotnet.type/directory-stream]
   "java.nio.file.DirectoryStream$Filter" ["global::System.Predicate" :dotnet.type/predicate]
   "java.nio.file.FileStore" ["global::System.IO.DriveInfo" :dotnet.type/drive-info]
   "java.nio.file.FileSystem" ["global::Pkl.Core.Runtime.JavaFileSystem" :pkl-core.type/file-system]
   "java.nio.file.FileSystems" ["global::Pkl.Core.Runtime.JavaFileSystems" :pkl-core.type/file-systems]
   "java.nio.file.FileVisitResult" ["global::Pkl.Core.Runtime.JavaFileVisitResult" :pkl-core.type/file-visit-result]
   "java.nio.file.PathMatcher" ["global::System.Predicate<string>" :pkl-core.type/path-matcher]
   "java.nio.file.Paths" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.nio.file.SimpleFileVisitor" ["global::Pkl.Core.Runtime.JavaSimpleFileVisitor" :pkl-core.type/file-visitor]
   "java.nio.file.StandardCopyOption" ["global::Pkl.Core.Runtime.JavaCopyOption" :pkl-core.type/copy-option]
   "java.nio.file.WatchService" ["global::Pkl.Core.Runtime.JavaWatchService" :pkl-core.type/watch-service]
   "java.nio.file.AccessDeniedException" ["global::System.UnauthorizedAccessException" :dotnet.type/unauthorized]
   "java.nio.file.NotDirectoryException" ["global::System.IO.DirectoryNotFoundException" :dotnet.type/directory-not-found]
   "java.nio.file.FileSystemAlreadyExistsException" ["global::Pkl.Core.Runtime.JavaFileSystemAlreadyExistsException" :dotnet.type/file-system-already-exists]
   "java.nio.file.FileSystemNotFoundException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.nio.file.attribute.BasicFileAttributes" ["global::System.IO.FileSystemInfo" :dotnet.type/file-info]
   "java.nio.file.attribute.PosixFilePermission" ["global::System.IO.UnixFileMode" :dotnet.type/unix-file-mode]
   "java.nio.file.attribute.UserPrincipalLookupService" ["object" :pkl-core.type/user-principal-lookup]
   "java.nio.file.spi.FileSystemProvider" ["global::Pkl.Core.Runtime.JavaFileSystemProvider" :pkl-core.type/file-system-provider]
   "java.nio.file.spi.FileTypeDetector" ["global::Pkl.Core.Runtime.JavaFileTypeDetector" :pkl-core.type/file-type-detector]
   "java.nio.file.NoSuchFileException" ["global::Vibeformer.Runtime.NoSuchFileException" :dotnet.type/no-such-file-exception]
   "java.time.Duration" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.LocalDateTime" ["global::System.DateTime" :dotnet.type/date-time]
   "java.time.Month" ["int" :dotnet.type/month-number]
   "java.time.temporal.TemporalUnit" ["global::Pkl.Core.Runtime.JavaTemporalUnit" :pkl-core.type/temporal-unit]
   "java.time.temporal.ChronoUnit" ["global::Pkl.Core.Runtime.JavaTemporalUnit" :pkl-core.type/temporal-unit]
   "java.lang.Iterable" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.Collection" ["global::System.Collections.Generic.ICollection" :dotnet.type/collection]
   "java.util.List" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "java.util.ArrayList" ["global::System.Collections.Generic.List" :dotnet.type/list]
   "java.util.Set" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "java.util.HashSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/hash-set]
   "java.util.LinkedHashSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/linked-hash-set]
   "java.util.LinkedList" ["global::Vibeformer.Runtime.JavaLinkedList" :dotnet.type/linked-list]
   "java.util.EnumSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/enum-set]
   "java.util.AbstractCollection" ["global::System.Collections.Generic.ICollection" :dotnet.type/collection]
   "java.util.AbstractList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "java.util.AbstractSequentialList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "java.util.AbstractMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "java.util.AbstractSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "java.util.Map" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "java.util.HashMap" ["global::System.Collections.Generic.Dictionary" :dotnet.type/dictionary]
   "java.util.IdentityHashMap" ["global::Pkl.Core.Runtime.JavaIdentityDictionary" :pkl-core.type/identity-map]
   "java.util.LinkedHashMap" ["global::System.Collections.Generic.Dictionary" :dotnet.type/linked-dictionary]
   "java.util.WeakHashMap" ["global::System.Collections.Generic.Dictionary" :dotnet.type/weak-map]
   "java.util.TreeMap" ["global::System.Collections.Generic.SortedDictionary" :dotnet.type/sorted-dictionary]
   "java.util.TreeSet" ["global::System.Collections.Generic.SortedSet" :dotnet.type/sorted-set]
   "java.util.Map$Entry" ["global::Vibeformer.Runtime.JavaMapEntry" :dotnet.type/map-entry]
   "java.util.Comparator" ["global::System.Comparison" :dotnet.type/comparison]
   "java.util.Deque" ["global::Vibeformer.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.ArrayDeque" ["global::Vibeformer.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.Iterator" ["global::System.Collections.Generic.IEnumerator" :dotnet.type/enumerator]
   "java.util.ListIterator" ["global::System.Collections.Generic.IEnumerator" :dotnet.type/enumerator]
   "java.util.PrimitiveIterator" ["global::System.Collections.IEnumerator" :dotnet.type/enumerator]
   "java.util.PrimitiveIterator$OfInt" ["global::System.Collections.Generic.IEnumerator<int>" :dotnet.type/int-enumerator]
   "java.util.PrimitiveIterator$OfLong" ["global::System.Collections.Generic.IEnumerator<long>" :dotnet.type/long-enumerator]
   "java.util.ServiceLoader" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/service-loader]
   "java.util.Spliterator" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.Optional" ["global::Pkl.Core.Runtime.JavaOptional" :pkl-core.type/optional]
   "java.util.OptionalInt" ["int?" :dotnet.type/nullable-int]
   "java.util.Random" ["global::Vibeformer.Runtime.JavaRandom" :dotnet.type/random]
   "java.util.Properties" ["global::Vibeformer.Runtime.JavaProperties" :dotnet.type/properties]
   "java.util.NoSuchElementException" ["global::System.InvalidOperationException" :dotnet.type/invalid-operation]
   "java.util.Arrays" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.Base64" ["global::Pkl.Core.Runtime.JavaBase64" :pkl-core.type/base64]
   "java.util.Base64$Encoder" ["global::Pkl.Core.Runtime.JavaBase64Encoder" :pkl-core.type/base64-encoder]
   "java.util.Base64$Decoder" ["global::Pkl.Core.Runtime.JavaBase64Decoder" :pkl-core.type/base64-decoder]
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
   "java.util.function.BiPredicate" ["global::Vibeformer.Runtime.JavaBiPredicate" :dotnet.type/bi-predicate]
   "java.util.function.BinaryOperator" ["global::Pkl.Core.Runtime.JavaBinaryOperator" :pkl-core.type/binary-operator]
   "java.util.function.IntFunction" ["global::Vibeformer.Runtime.JavaIntFunction" :dotnet.type/int-function]
   "java.util.function.IntConsumer" ["global::System.Action<int>" :dotnet.type/int-consumer]
   "java.util.function.LongFunction" ["global::Pkl.Core.Runtime.JavaLongFunction" :pkl-core.type/long-function]
   "java.util.function.LongConsumer" ["global::System.Action<long>" :dotnet.type/long-consumer]
   "java.util.function.LongPredicate" ["global::System.Predicate<long>" :dotnet.type/long-predicate]
   "java.util.function.ToIntFunction" ["global::Vibeformer.Runtime.JavaToIntFunction" :dotnet.type/to-int-function]
   "java.util.function.ToLongFunction" ["global::Vibeformer.Runtime.JavaToLongFunction" :dotnet.type/to-long-function]
   "java.util.function.IntPredicate" ["global::System.Predicate<int>" :dotnet.type/int-predicate]
   "java.util.stream.Stream" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.stream.StreamSupport" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.stream.IntStream" ["global::System.Collections.Generic.IEnumerable<int>" :dotnet.type/int-enumerable]
   "java.util.stream.LongStream" ["global::System.Collections.Generic.IEnumerable<long>" :dotnet.type/long-enumerable]
   "java.util.stream.Collector" ["global::Vibeformer.Runtime.JavaCollector" :dotnet.type/collector]
   "java.util.stream.Collectors" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.text.Format" ["global::Vibeformer.Runtime.JavaFormat" :dotnet.type/format]
   "java.text.MessageFormat" ["global::Vibeformer.Runtime.JavaMessageFormat" :dotnet.type/message-format]
   "java.text.DecimalFormat" ["global::Pkl.Core.Runtime.JavaDecimalFormat" :pkl-core.type/decimal-format]
   "java.text.NumberFormat" ["global::Pkl.Core.Runtime.JavaDecimalFormat" :pkl-core.type/decimal-format]
   "java.text.DecimalFormatSymbols" ["global::System.Globalization.NumberFormatInfo" :dotnet.type/number-format]
   "java.util.zip.ZipEntry" ["global::Pkl.Core.Runtime.JavaZipEntry" :pkl-core.type/zip-entry]
   "java.util.zip.ZipInputStream" ["global::Pkl.Core.Runtime.JavaZipInputStream" :pkl-core.type/zip-input-stream]
   "java.util.zip.ZipOutputStream" ["global::Pkl.Core.Runtime.JavaZipOutputStream" :pkl-core.type/zip-output-stream]
   "java.util.regex.Matcher" ["global::Vibeformer.Runtime.JavaRegexMatcher" :dotnet.type/regex-matcher]
   "java.util.regex.MatchResult" ["global::Vibeformer.Runtime.JavaRegexMatcher" :dotnet.type/regex-matcher]
   "java.util.regex.Pattern" ["global::System.Text.RegularExpressions.Regex" :dotnet.type/regex]
   "java.util.regex.PatternSyntaxException" ["global::System.ArgumentException" :dotnet.type/argument-exception]
   "java.util.concurrent.ConcurrentHashMap" ["global::System.Collections.Concurrent.ConcurrentDictionary" :dotnet.type/concurrent-dictionary]
   "java.util.concurrent.Future" ["global::Vibeformer.Runtime.JavaFuture" :dotnet.type/future]
   "java.util.concurrent.CompletableFuture" ["global::Vibeformer.Runtime.JavaFuture" :dotnet.type/completable-future]
   "java.util.concurrent.ExecutionException" ["global::System.AggregateException" :dotnet.type/execution-exception]
   "java.util.concurrent.Executors" ["global::Pkl.Core.Runtime.JavaConcurrency" :pkl-core.type/concurrency]
   "java.util.concurrent.ExecutorService" ["global::Pkl.Core.Runtime.JavaScheduledExecutor" :pkl-core.type/executor]
   "java.util.concurrent.ThreadFactory" ["global::System.Func<global::System.Action, global::Pkl.Core.Runtime.JavaThread>" :pkl-core.type/thread-factory]
   "java.util.concurrent.ScheduledExecutorService" ["global::Pkl.Core.Runtime.JavaScheduledExecutor" :pkl-core.type/scheduled-executor]
   "java.util.concurrent.ScheduledFuture" ["global::System.Threading.Tasks.Task" :dotnet.type/task]
   "java.util.concurrent.TimeUnit" ["global::Vibeformer.Runtime.JavaTimeUnit" :dotnet.type/time-unit]
   "java.util.concurrent.atomic.AtomicBoolean" ["global::Pkl.Core.Runtime.JavaAtomicBoolean" :pkl-core.type/atomic-boolean]
   "java.util.concurrent.atomic.AtomicLong" ["global::Pkl.Core.Runtime.JavaAtomicLong" :pkl-core.type/atomic-long]
   "java.util.concurrent.atomic.AtomicReference" ["global::Pkl.Core.Runtime.JavaAtomicReference" :pkl-core.type/atomic-reference]
   "org.organicdesign.fp.collections.BaseList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "org.organicdesign.fp.collections.BaseMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "org.organicdesign.fp.collections.BaseSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "org.organicdesign.fp.collections.ImList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "org.organicdesign.fp.collections.MutList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "org.organicdesign.fp.collections.PersistentVector" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "org.organicdesign.fp.collections.ImMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "org.organicdesign.fp.collections.MutMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "org.organicdesign.fp.collections.PersistentHashMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "org.organicdesign.fp.collections.PersistentHashMap$MutHashMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "org.organicdesign.fp.collections.ImSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "org.organicdesign.fp.collections.MutSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "org.organicdesign.fp.collections.PersistentHashSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "org.organicdesign.fp.collections.PersistentHashSet$MutHashSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "org.organicdesign.fp.collections.PersistentVector$MutVector" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "org.organicdesign.fp.collections.UnmodCollection" ["global::System.Collections.Generic.ICollection" :dotnet.type/collection]
   "org.organicdesign.fp.collections.UnmodList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "org.organicdesign.fp.collections.UnmodMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "org.organicdesign.fp.collections.UnmodMap$UnEntry" ["global::System.Collections.Generic.KeyValuePair" :dotnet.type/map-entry]
   "org.organicdesign.fp.collections.UnmodSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "org.organicdesign.fp.collections.UnmodIterable" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "org.organicdesign.fp.collections.UnmodSortedIterable" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "org.organicdesign.fp.collections.UnmodSortedIterator" ["global::System.Collections.Generic.IEnumerator" :dotnet.type/enumerator]
   "org.organicdesign.fp.collections.Cowry" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "org.organicdesign.fp.function.Fn0" ["global::System.Func" :dotnet.type/func]
   "org.organicdesign.fp.function.Fn1" ["global::System.Func" :dotnet.type/func]
   "org.organicdesign.fp.indent.IndentUtils" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "org.organicdesign.fp.indent.Indented" ["object" :dotnet.type/marker]
   "org.organicdesign.fp.oneOf.Option" ["global::Pkl.Core.Runtime.JavaOptional" :pkl-core.type/optional]
   "org.organicdesign.fp.tuple.Tuple2" ["global::Pkl.Core.Runtime.JavaTuple2" :pkl-core.type/tuple2]
   "org.organicdesign.fp.tuple.Tuple4" ["global::Pkl.Core.Runtime.JavaTuple4" :pkl-core.type/tuple4]
   "org.organicdesign.fp.xform.Xform" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "org.organicdesign.fp.xform.Transformable" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "org.msgpack.core.MessageBufferPacker" ["global::Pkl.Core.Runtime.ExcludedMessagePackPacker" :excluded.messagepack/packer]
   "org.msgpack.core.MessagePacker" ["global::Pkl.Core.Runtime.ExcludedMessagePackPacker" :excluded.messagepack/packer]
   "org.msgpack.core.MessageUnpacker" ["global::Pkl.Core.Runtime.ExcludedMessagePackUnpacker" :excluded.messagepack/unpacker]
   "org.msgpack.core.MessagePack" ["global::Pkl.Core.Runtime.ExcludedMessagePack" :excluded.messagepack/factory]
   "org.msgpack.core.MessagePackException" ["global::System.NotSupportedException" :excluded.messagepack/exception]
   "org.msgpack.core.MessageTypeException" ["global::System.NotSupportedException" :excluded.messagepack/exception]
   "org.msgpack.core.MessageInsufficientBufferException" ["global::System.NotSupportedException" :excluded.messagepack/exception]
   "org.msgpack.value.Value" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ExtensionValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.FloatValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.NilValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.NumberValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.RawValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.TimestampValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableArrayValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableBinaryValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableBooleanValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableFloatValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableIntegerValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableMapValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableNilValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableRawValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableStringValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ImmutableTimestampValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.ArrayValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.BinaryValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.BooleanValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.IntegerValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.MapValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.StringValue" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.msgpack.value.impl.ImmutableStringValueImpl" ["global::Pkl.Core.Runtime.ExcludedMessagePackValue" :excluded.messagepack/value]
   "org.jspecify.annotations.Nullable" ["object" :dotnet.annotation/nullable]
   "org.jspecify.annotations.NonNull" ["object" :dotnet.annotation/non-null]
   "org.jspecify.annotations.NullMarked" ["object" :dotnet.annotation/null-marked]})

(defn- dotted-external-type
  [prefix destination qualified-name rule & [segment-name]]
  (when (or (= qualified-name prefix)
            (str/starts-with? qualified-name (str prefix ".")))
    (let [suffix (subs qualified-name (count prefix))
          parts (->> (str/split suffix #"[.$]") (remove str/blank?))]
      [(str "global::" destination
            (when (seq parts)
              (str "." (str/join "." (map (or segment-name identifier) parts)))))
       rule])))

(defn- derived-external-type-mapping
  "Maps resolved external product/substrate identities without reconstructing
  source syntax. Parser types target the separately generated Pkl.Parser
  package; Truffle and Graal identities target explicit compatibility owners
  pending their native product implementations. Compile-time annotations are
  erased only after their exact resolved package identity is known."
  [qualified-name]
  (or (dotted-external-type "org.pkl.parser" "Pkl.Parser" qualified-name
                            :dotnet.type/pkl-parser-package pascal)
      (dotted-external-type "com.oracle.truffle" "Pkl.Core.Runtime.Truffle" qualified-name
                            :pkl-core.type/truffle-substrate)
      (dotted-external-type "org.graalvm.collections" "Pkl.Core.Runtime.GraalCollections" qualified-name
                            :pkl-core.type/graal-collections-substrate)
      (dotted-external-type "org.graalvm.polyglot" "Pkl.Core.Runtime.Polyglot" qualified-name
                            :pkl-core.type/polyglot-substrate)
      (dotted-external-type "org.snakeyaml.engine.v2" "Pkl.Core.Runtime.SnakeYaml" qualified-name
                            :pkl-core.type/snakeyaml-substrate)
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
    (csharp/generic-name (raw base) arguments)
    (raw base)))

(def ^:private raw-close-derived-type-rules
  #{:dotnet.type/pkl-parser-package
    :pkl-core.type/truffle-substrate
    :pkl-core.type/graal-collections-substrate
    :pkl-core.type/polyglot-substrate
    :pkl-core.type/snakeyaml-substrate})

(defn- formal-type-arity [declaration]
  (if (instance? CtFormalTypeDeclarer declaration)
    (count (.getFormalCtTypeParameters ^CtFormalTypeDeclarer declaration))
    0))

(defn- type-parameter-name [parameter]
  (let [name (if (instance? CtTypeParameterReference parameter)
               (.getSimpleName ^CtTypeParameterReference parameter)
               (.getSimpleName ^CtTypeParameter parameter))
        declaration (if (instance? CtTypeParameterReference parameter)
                      (.getDeclaration ^CtTypeParameterReference parameter)
                      parameter)
        owner (when (and declaration (.isParentInitialized ^CtElement declaration))
                (.getParent ^CtElement declaration))
        outer (when (instance? CtType owner) (.getDeclaringType ^CtType owner))
        shadows-outer?
        (and outer
             (some #(= name (.getSimpleName ^CtTypeParameter %))
                   (.getFormalCtTypeParameters ^CtType outer)))]
    (identifier (if (and shadows-outer?
                         (not (str/starts-with? (or (some-> outer .getQualifiedName) "")
                                                "org.pkl.core.util.paguro.RrbTree")))
                  (str name "Nested")
                  name))))

(defn- mapped-type-base [ctx ^CtTypeReference reference occurrence]
  (cond
    (= :project (:origin occurrence))
    [(project-type-base ctx ^CtType (:declaration occurrence)) :dotnet.type/project]

    (= :type-parameter (:origin occurrence))
    [(type-parameter-name (or (:declaration occurrence) reference))
     :dotnet.type/type-parameter]

    (= :intrinsic (:origin occurrence))
    (or (when (= :null-type (:resolution occurrence))
          ["object" :dotnet.type/null])
        (get primitive-type-mappings (.getQualifiedName reference))
        (throw (ex-info (str "Unsupported intrinsic declaration type " (:key occurrence))
                        {:kind :unsupported-declaration-type :occurrence (dissoc occurrence :reference :declaration)})))

    :else
    (or (when (empty? (.getActualTypeArguments reference))
          (when-let [base (get {"java.lang.Comparable" "object"
                                "java.lang.Iterable" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.util.Collection" "global::System.Collections.Generic.ICollection<object>"
                                "java.util.List" "global::System.Collections.Generic.IList<object>"
                                "java.util.ArrayList" "global::System.Collections.Generic.List<object>"
                                "java.util.Set" "global::System.Collections.Generic.ISet<object>"
                                "java.util.HashSet" "global::System.Collections.Generic.HashSet<object>"
                                "java.util.LinkedHashSet" "global::System.Collections.Generic.HashSet<object>"
                                "java.util.EnumSet" "global::System.Collections.Generic.HashSet<object>"
                                "java.util.TreeSet" "global::System.Collections.Generic.SortedSet<object>"
                                "java.util.Map" "global::System.Collections.Generic.IDictionary<object, object>"
                                "java.util.HashMap" "global::System.Collections.Generic.Dictionary<object, object>"
                                "java.util.LinkedHashMap" "global::System.Collections.Generic.Dictionary<object, object>"
                                "java.util.Map$Entry" "global::Vibeformer.Runtime.JavaMapEntry<object, object>"
                                "java.util.Iterator" "global::System.Collections.Generic.IEnumerator<object>"
                                "java.util.Comparator" "global::System.Comparison<object>"
                                "java.util.Spliterator" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.util.ServiceLoader" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.util.stream.Stream" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.nio.file.DirectoryStream" "global::Vibeformer.Runtime.JavaDirectoryStream<string>"
                                "org.organicdesign.fp.tuple.Tuple2" "global::Pkl.Core.Runtime.JavaTuple2<object, object>"
                                "org.organicdesign.fp.collections.UnmodIterable" "global::System.Collections.Generic.IEnumerable<object>"
                                "org.organicdesign.fp.collections.UnmodSortedIterable" "global::System.Collections.Generic.IEnumerable<object>"}
                               (.getQualifiedName reference))]
            [base :dotnet.type/raw-generic]))
        (when (and (= "java.util.List" (.getQualifiedName reference))
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
          ;; Comparable<?> is commonly used as an erased local carrier (for
          ;; example, JSON parser member names may be String or Identifier).
          ;; Keep declaration bases generic, but erase value-site occurrences
          ;; to object so CLR generic invariance does not introduce casts that
          ;; do not exist on the JVM.
          (and (= "java.lang.Comparable" (.getQualifiedName reference))
               (not (:base-clause? ctx)))
          [(raw "object") :dotnet.type/comparable-erased-value]

          (= "org.pkl.core.stdlib.VmObjectFactory$Property" (.getQualifiedName reference))
          [(generic-node "global::System.Func"
                         (mapv #(type-node ctx %) (.getActualTypeArguments reference)))
           :pkl-core.type/property-function]

          (= "org.pkl.core.StackFrameTransformer" (.getQualifiedName reference))
          [(raw "global::System.Func<global::Pkl.Core.StackFrame, global::Pkl.Core.StackFrame>")
           :pkl-core.type/stack-frame-transformer]

          (and (= "org.pkl.core.runtime.VmCollection$Builder" (.getQualifiedName reference))
               (some #(instance? CtWildcardReference %)
                     (.getActualTypeArguments reference)))
          [(generic-node "global::Pkl.Core.Runtime.VmCollection.Builder"
                         [(raw "global::Pkl.Core.Runtime.VmCollection")])
           :pkl-core.type/vm-collection-builder-bound]

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
                ;; System.Type is non-generic even though java.lang.Class<T>
                ;; carries a type argument.  Its resolved T remains visited by
                ;; the recursive translator, but is erased at this mapping.
                arguments (cond
                            (= "java.lang.Class" (.getQualifiedName reference))
                            []

                            (and (= :project (:origin occurrence))
                                 (empty? (.getActualTypeArguments reference)))
                            (repeat (formal-type-arity (:declaration occurrence))
                                    (raw "object"))

                            (and (contains? raw-close-derived-type-rules mapping-rule)
                                 (empty? (.getActualTypeArguments reference)))
                            (repeat (formal-type-arity (.getTypeDeclaration reference))
                                    (raw "object"))

                            :else
                            (mapv #(type-node ctx %) (.getActualTypeArguments reference)))]
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

(defn- node-info-attribute [^CtType type]
  (when-let [short-name
             (some (fn [^CtAnnotation annotation]
                     (when (= "com.oracle.truffle.api.nodes.NodeInfo"
                              (some-> annotation .getAnnotationType .getQualifiedName))
                       (let [value (.getValue annotation "shortName")]
                         (cond
                           (string? value) value
                           (instance? CtLiteral value) (.getValue ^CtLiteral value)
                           :else nil))))
                   (.getAnnotations type))]
    (let [escaped (-> (str short-name)
                      (str/replace "\\" "\\\\")
                      (str/replace "\"" "\\\""))]
      (raw (str "[global::Pkl.Core.Runtime.Truffle.api.nodes.NodeInfo(\""
                escaped "\")]\n")))))

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

(defn- parameter-node
  ([ctx owner parameter] (parameter-node ctx owner parameter nil))
  ([ctx owner ^CtParameter parameter forced-type]
  (let [name (identifier (.getSimpleName parameter))
        type (or forced-type (type-node ctx (.getType parameter)))
        prefix (when (.isVarArgs parameter) "params ")
        node (sequence-node [(raw (or prefix "")) type (raw (str " " name))])]
    (attach-declaration ctx node parameter :parameter owner name nil
                        :java.declaration/parameter))))

(defn- formal-node [ctx owner ^CtTypeParameter parameter]
  (let [name (type-parameter-name parameter)
        emitted-name (if (= "org.pkl.core.runtime.VmCollection$Builder" owner)
                       (str "out " name)
                       name)]
    (attach-declaration ctx (raw emitted-name) parameter :type-parameter owner name nil
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
                      (sequence-node [(raw (str " where " (type-parameter-name parameter) " : "))
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


(defn- current-body-context [ctx]
  ;; The complete semantic mapping registry is intentionally built once. Its
  ;; type service consults this holder so each sequential member translation
  ;; can retain lexical ownership without rebuilding the million-occurrence
  ;; registry for every body.
  (when-let [ctx-holder (:ctx-holder ctx)]
    (reset! ctx-holder ctx))
  (:body-context ctx))

(defn- translated-node [ctx ^CtElement element]
  (let [translation (java-body/translate (current-body-context ctx) element)]
    (swap! (:body-translations ctx) conj translation)
    (:node translation)))

(defn- executable-owner [^CtExecutable executable]
  (let [type (.getDeclaringType executable)]
    (str (.getQualifiedName type) "#" (.getSignature executable))))

(defn- destination-type-key [^CtTypeReference reference]
  (let [component (when (.isArray reference) (.getComponentType reference))
        qualified (.getQualifiedName reference)
        base (if component
               (str (destination-type-key component) "[]")
               (or (first (get external-type-mappings qualified)) qualified))
        arguments (when-not component (.getActualTypeArguments reference))]
    (if (seq arguments)
      (str base "<" (str/join "," (map destination-type-key arguments)) ">")
      base)))

(defn- type-parameter-component? [^CtTypeReference reference]
  (or (instance? CtTypeParameterReference reference)
      (some type-parameter-component? (.getActualTypeArguments reference))))

(defn- substituted-direct-base-return [^CtType owner-type ^CtMethod definition]
  (let [return-reference (.getType definition)
        base-owner (.getDeclaringType definition)
        superclass (when (instance? CtClass owner-type)
                     (.getSuperclass ^CtClass owner-type))]
    (when (and (instance? CtTypeParameterReference return-reference)
               superclass
               (= (.getQualifiedName base-owner)
                  (some-> superclass .getTypeDeclaration .getQualifiedName)))
      (let [formals (vec (.getFormalCtTypeParameters base-owner))
            actuals (vec (.getActualTypeArguments superclass))
            parameter-name (.getSimpleName return-reference)]
        (some (fn [[formal actual]]
                (when (= parameter-name (.getSimpleName ^CtTypeParameter formal)) actual))
              (map vector formals actuals))))))

(defn- destination-parameter-key [^CtMethod method]
  (mapv #(destination-type-key (.getType ^CtParameter %)) (.getParameters method)))

(defn- direct-methods [^CtType type]
  (filter #(instance? CtMethod %) (.getTypeMembers type)))

(declare top-definitions)

(defn- destination-overload-collision? [ctx ^CtMethod method]
  (let [simple-name (.getSimpleName method)
        destination-key (destination-parameter-key method)
        owners (distinct
                (keep #(.getDeclaringType ^CtMethod %)
                      (cons method (top-definitions ctx method))))]
    (boolean
     (some (fn [^CtType owner]
             (< 1 (count (filter #(and (= simple-name (.getSimpleName ^CtMethod %))
                                       (= destination-key (destination-parameter-key %)))
                                 (direct-methods owner)))))
           owners))))

(defn- overload-source-suffix [^CtMethod method]
  (let [parts (map (fn [^CtParameter parameter]
                     (let [reference (.getType parameter)
                           simple (if (.isArray reference)
                                    (str (.getSimpleName (.getComponentType reference)) "Array")
                                    (.getSimpleName reference))]
                       (pascal (str/replace simple #"[$.]" "_"))))
                   (.getParameters method))]
    (str "From" (str/join "And" parts))))

(defn- method-name [ctx ^CtMethod method]
  (let [simple-name (.getSimpleName method)
        owner (.getDeclaringType method)
        owner-name (some-> owner .getQualifiedName)
        base-name (cond
                    (and (= owner-name "org.pkl.core.module.ModuleKeys")
                         (= simple-name "synthetic"))
                    "CreateSynthetic"
                    (and (= owner-name "org.pkl.core.module.ResolvedModuleKeys")
                         (= simple-name "virtual"))
                    "CreateVirtual"
                    (= simple-name "toString") "ToString"
                    (= simple-name "hashCode") "GetHashCode"
                    (= simple-name "equals") "Equals"
                    :else (pascal simple-name))
        destination-contract-owners
        (distinct (keep #(.getDeclaringType ^CtMethod %)
                        (cons method (top-definitions ctx method))))
        nested-type-names
        (set (map #(pascal (.getSimpleName ^CtType %))
                  (mapcat #(.getNestedTypes ^CtType %)
                          destination-contract-owners)))]
    ;; Java permits a method and nested type to share a name; C# does not.
    ;; Derive the destination factory name from the live declaring type so the
    ;; declaration and all resolved project call sites take the same path.
    (let [base-name (if (contains? nested-type-names base-name)
                      (str "Create" base-name)
                      base-name)]
      (if (destination-overload-collision? ctx method)
        (str base-name (overload-source-suffix method))
        base-name))))

(defn- top-definitions [ctx ^CtMethod method]
  (let [^IdentityHashMap cache (:top-definitions-cache ctx)]
    (if (.containsKey cache method)
      (.get cache method)
      (let [definitions (vec (.getTopDefinitions method))]
        (.put cache method definitions)
        definitions))))

(defn- class-definition [ctx ^CtMethod method]
  (some #(when-not (instance? CtInterface (.getDeclaringType ^CtMethod %)) %)
        (top-definitions ctx method)))

(defn- interface-definition [ctx ^CtMethod method]
  (some #(when (instance? CtInterface (.getDeclaringType ^CtMethod %)) %)
        (top-definitions ctx method)))

(defn- superclass-method-definition [^CtType owner-type ^CtMethod method]
  (loop [superclass (when (instance? CtClass owner-type)
                      (.getSuperclass ^CtClass owner-type))]
    (when-let [^CtType declaration (some-> superclass .getTypeDeclaration)]
      (let [methods (vec (.getMethods declaration))
            name-and-arity (filterv #(and (= (.getSimpleName method)
                                               (.getSimpleName ^CtMethod %))
                                          (= (count (.getParameters method))
                                             (count (.getParameters ^CtMethod %))))
                                    methods)]
        (or (some (fn [^CtMethod candidate]
                    (when (or (.isOverriding method candidate)
                              (and (= (.getSimpleName method) (.getSimpleName candidate))
                                   (= (.getSignature method) (.getSignature candidate))))
                      candidate))
                  methods)
            ;; Spoon occasionally cannot prove overriding after generic owner
            ;; substitution. A unique same-name/arity member in the resolved
            ;; superclass is nevertheless the only Java dispatch target.
            (when (= 1 (count name-and-arity)) (first name-and-arity))
            (recur (when (instance? CtClass declaration)
                     (.getSuperclass ^CtClass declaration))))))))

(declare destination-overridable-definition?)

(defn- inherited-interface-contract? [ctx ^CtType owner-type ^CtMethod method]
  (let [interface-types
        (keep (fn [^CtMethod definition]
                (let [owner (.getDeclaringType definition)]
                  (when (and (instance? CtInterface owner)
                             (destination-overridable-definition? definition))
                    (.getReference owner))))
              (top-definitions ctx method))]
    (boolean
     (when (seq interface-types)
       (loop [superclass (when (instance? CtClass owner-type)
                           (.getSuperclass ^CtClass owner-type))]
         (when superclass
           (or (some #(.isSubtypeOf superclass ^CtTypeReference %) interface-types)
               (recur (some-> superclass .getTypeDeclaration .getSuperclass)))))))))

(defn- destination-overridable-definition? [^CtMethod definition]
  (let [owner (some-> definition .getDeclaringType .getQualifiedName)
        name (.getSimpleName definition)
        parameter-types (mapv #(.getQualifiedName (.getType ^CtParameter %))
                              (.getParameters definition))]
    (not
     (or (and (= "java.lang.Throwable" owner)
              (contains? #{"getMessage" "getLocalizedMessage" "fillInStackTrace"} name))
         (and (= "java.io.Writer" owner)
              (or (= "append" name)
                  (and (= "write" name)
                       (= ["java.lang.String" "int" "int"] parameter-types))))))))

(defn- destination-internal-type? [^CtTypeReference reference]
  (when reference
    (let [qualified-name (.getQualifiedName reference)]
      (or (contains? #{"java.util.Deque" "java.util.ArrayDeque"
                       "org.pkl.core.externalreader.ExternalModuleResolver"
                       "org.pkl.core.externalreader.ExternalResourceResolver"}
                     qualified-name)
          (str/starts-with? qualified-name "org.pkl.core.messaging.")
          (when (.isArray reference)
            (destination-internal-type? (.getComponentType reference)))
          (some destination-internal-type? (.getActualTypeArguments reference))))))

(defn- java-object-override? [^CtMethod method]
  (let [name (.getSimpleName method)
        parameters (vec (.getParameters method))]
    (or (and (contains? #{"toString" "hashCode"} name) (empty? parameters))
        (and (= "equals" name)
             (= 1 (count parameters))
             (= "java.lang.Object" (.getQualifiedName (.getType ^CtParameter (first parameters))))))))

(defn- false-destination-override? [^CtType owner-type ^CtMethod method]
  (let [owner (.getQualifiedName owner-type)
        name (.getSimpleName method)
        arity (count (.getParameters method))]
    (or (and (= "org.pkl.core.runtime.VmObjectLike" owner)
             (= "force" name) (= 2 arity))
        (and (= "org.pkl.core.util.StringBuilderWriter" owner)
             (= "append" name)))))

(defn- forced-anonymous-override? [^CtType owner-type ^CtMethod method]
  (and (instance? CtClass owner-type)
       (.isAnonymous ^CtClass owner-type)
       (contains? #{"getScalarResolver" "getSchemaTagConstructors"}
                  (.getSimpleName method))))

(declare private-type-component?)

(defn- method-modifiers [ctx ^CtType owner-type ^CtMethod method body name]
  (let [interface? (instance? CtInterface owner-type)
        superclass-definition (superclass-method-definition owner-type method)
        interface-contract-definition (interface-definition ctx method)
        base-definition (or superclass-definition
                            (class-definition ctx method))
        overridable-base (when (and base-definition
                                    (destination-overridable-definition? base-definition))
                           base-definition)
        sealed-owner? (or (modifier? owner-type ModifierKind/FINAL)
                          (and (instance? CtClass owner-type)
                               (.isAnonymous ^CtClass owner-type))
                          (instance? CtRecord owner-type)
                          (instance? CtEnum owner-type))
        static? (modifier? method ModifierKind/STATIC)
        private? (modifier? method ModifierKind/PRIVATE)
        final? (modifier? method ModifierKind/FINAL)
        abstract? (and (not interface?) (nil? body))
        override? (and (not static?)
                       (not (false-destination-override? owner-type method))
                       (or (java-object-override? method)
                           overridable-base
                           (inherited-interface-contract? ctx owner-type method)
                           (forced-anonymous-override? owner-type method)))
        base-owner (some-> overridable-base .getDeclaringType .getQualifiedName)
        base-member-visibility
        (when overridable-base
          (let [base-type (.getDeclaringType ^CtMethod overridable-base)]
            (cond
              ;; Apply the same interface-contract promotion used when the
              ;; base declaration itself is emitted.
              (and base-type (interface-definition ctx overridable-base))
              "public"

              (and base-type
                   (not (.isTopLevel ^CtType base-type))
                   (modifier? base-type ModifierKind/PRIVATE))
              "internal"

              (or (private-type-component? (.getType ^CtMethod overridable-base))
                  (some #(private-type-component? (.getType ^CtParameter %))
                        (.getParameters ^CtMethod overridable-base)))
              "private"

              (and (modifier? overridable-base ModifierKind/PRIVATE)
                   base-type
                   (not (.isTopLevel ^CtType base-type)))
              "internal"

              (or (destination-internal-type? (.getType ^CtMethod overridable-base))
                  (some #(destination-internal-type? (.getType ^CtParameter %))
                        (.getParameters ^CtMethod overridable-base)))
              "internal"

              (and (modifier? overridable-base ModifierKind/PROTECTED)
                   (str/starts-with? (or base-owner "") "org.pkl.parser."))
              "protected"

              :else
              (visibility overridable-base "internal"))))
        member-visibility (cond
                            ;; Unlike Java, C# does not allow an override to
                            ;; widen accessibility.  Reproduce the visibility
                            ;; the base declaration receives after all of the
                            ;; destination-specific accessibility adjustments.
                            (and override? overridable-base)
                            base-member-visibility

                            ;; C# requires every implicit interface
                            ;; implementation to be public.  The containing
                            ;; Java type can still be private/package-local;
                            ;; demoting its public methods would sever the
                            ;; interface contract in the generated program.
                            (and (not interface?) interface-contract-definition)
                            "public"

                            (and (not override?)
                                 (not interface?)
                                 (not (.isTopLevel owner-type))
                                 (modifier? owner-type ModifierKind/PRIVATE))
                            "internal"

                            (and (not override?)
                                 (not interface?)
                                 (or (private-type-component? (.getType method))
                                     (some #(private-type-component? (.getType ^CtParameter %))
                                           (.getParameters method))))
                            "private"

                            (and private? (not (.isTopLevel owner-type)))
                            "internal"

                            (or (destination-internal-type? (.getType method))
                                (some #(destination-internal-type? (.getType ^CtParameter %))
                                      (.getParameters method)))
                            "internal"

                            :else
                            (if (and overridable-base
                                     (modifier? overridable-base ModifierKind/PROTECTED)
                                     (str/starts-with? (or base-owner "") "org.pkl.parser."))
                              "protected"
                              (visibility (or overridable-base method)
                                          (if interface? "public" "internal"))))
        destination-hiding? (and (= "GetType" name) (not override?))]
    [member-visibility
     (when destination-hiding? "new")
     (when static? "static")
     (when abstract? "abstract")
     (when (and (not abstract?) final? override?) "sealed")
     (when override? "override")
     (when (and (not interface?) (not static?) (not private?)
                (not= "private" member-visibility) (not final?)
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

(defn- substituted-interface-return [^CtType owner-type ^CtMethod method]
  (let [return-reference (.getType method)]
    (when (instance? CtTypeParameterReference return-reference)
      (let [interface-owner (.getDeclaringType method)
            interface-reference
            (some #(when (= (.getQualifiedName interface-owner)
                            (some-> ^CtTypeReference % .getTypeDeclaration .getQualifiedName))
                     %)
                  (.getSuperInterfaces owner-type))
            formals (vec (.getFormalCtTypeParameters interface-owner))
            actuals (vec (some-> ^CtTypeReference interface-reference .getActualTypeArguments))
            parameter-name (.getSimpleName return-reference)]
        (some (fn [[formal actual]]
                (when (= parameter-name (.getSimpleName ^CtTypeParameter formal)) actual))
              (map vector formals actuals))))))

(defn- deferred-interface-method-node [ctx ^CtType owner-type ^CtMethod method]
  (let [owner (executable-owner method)
        name (method-name ctx method)
        {:keys [parameters node]} (synthetic-formals-node method)
        body (.getBody method)
        return-reference (or (substituted-interface-return owner-type method)
                             (.getType method))
        sealed-owner? (or (modifier? owner-type ModifierKind/FINAL)
                          (instance? CtRecord owner-type)
                          (instance? CtEnum owner-type))
        params (mapv (fn [^CtParameter parameter]
                       (sequence-node [(type-node ctx (.getType parameter))
                                       (raw (str " " (identifier (.getSimpleName parameter))))]))
                     (.getParameters method))
        declaration
        (sequence-node
         [(raw (cond
                 (nil? body) "public abstract "
                 sealed-owner? "public "
                 :else "public virtual "))
          (type-node ctx return-reference) (raw (str " " name)) node
          (raw "(") (sequence-node params ", ") (raw ")")
          (constraints-node ctx parameters)
          (if body
            (sequence-node [(raw " ") (:node (java-body/translate (:body-context ctx) body))])
            (raw ";"))])]
    (with-source declaration method (if body
                                      :dotnet.interface/inherited-default-contract
                                      :dotnet.interface/deferred-abstract-contract)
      {:owner owner :signature (.getSignature method)})))

(defn- record-component-contract? [ctx ^CtMethod method]
  (let [owner (.getDeclaringType method)]
    (boolean
     (and (instance? CtInterface owner)
          (empty? (.getParameters method))
          (some (fn [declaration]
                  (when (and (instance? CtRecord declaration)
                             (.isSubtypeOf (.getReference ^CtRecord declaration)
                                           (.getReference ^CtInterface owner)))
                    (some #(= (.getSimpleName method)
                              (.getSimpleName ^CtRecordComponent %))
                          (.getRecordComponents ^CtRecord declaration))))
                (when-let [^IdentityHashMap selected (:selected-declarations ctx)]
                  (.keySet selected)))))))

(defn- missing-interface-contracts [ctx ^CtType type]
  (when (instance? CtClass type)
    (let [own-methods (vec (.getMethods type))
          interface-closure
          (fn interface-closure [^CtType interface]
            (cons interface
                  (mapcat (fn [^CtTypeReference parent]
                            (when-let [declaration (.getTypeDeclaration parent)]
                              (interface-closure declaration)))
                          (.getSuperInterfaces interface))))]
      (->> (.getSuperInterfaces type)
           (keep #(.getTypeDeclaration ^CtTypeReference %))
           (mapcat interface-closure)
           distinct
           ;; External/synthetic interface declarations are not part of the
           ;; selected occurrence-closed product slice.  Only synthesize
           ;; deferred members from the transitive closure of live selected
           ;; project interfaces whose signatures were resolved and indexed.
           ;; C# does not let an abstract class merely defer an inherited
           ;; interface member the way Java does: it must redeclare that member
           ;; abstract before a concrete subclass can override it.
           (filter #(selected-declaration? ctx %))
           (mapcat #(.getMethods ^CtType %))
           (filter #(selected-declaration? ctx %))
           (reduce (fn [methods ^CtMethod method]
                     (if (some #(= (.getSignature ^CtMethod %)
                                   (.getSignature method))
                               methods)
                       methods
                       (conj methods method)))
                   [])
           (remove (fn [^CtMethod contract]
                     (some #(.isOverriding ^CtMethod % contract) own-methods)))
           (filter (fn [^CtMethod contract]
                     (or (.getBody contract)
                         (modifier? type ModifierKind/ABSTRACT))))
           (sort-by #(.getSignature ^CtMethod %))
           (mapv #(deferred-interface-method-node ctx type %))))))

(defn- method-node [ctx owner-type ^CtMethod method]
  (let [owner (executable-owner method)
        name (method-name ctx method)
        record-object-equals?
        (and (instance? CtRecord owner-type)
             (= "equals" (.getSimpleName method))
             (= ["java.lang.Object"]
                (mapv #(.getQualifiedName (.getType ^CtParameter %))
                      (.getParameters method))))
        {:keys [parameters node]} (formals ctx owner method)
        params (mapv #(parameter-node
                       ctx owner %
                       (when record-object-equals?
                         (sequence-node [(raw (identifier (.getSimpleName owner-type)))
                                         (raw "?")])))
                     (.getParameters method))
        body (.getBody method)
        words (method-modifiers ctx owner-type method body name)
        signature (str name "(" (str/join "," (map #(.getQualifiedName (.getType ^CtParameter %))
                                                    (.getParameters method))) ")")
        ;; The declaration reference carries Spoon's resolved substitution for
        ;; inherited generic contracts (for example ParserVisitor<Result>
        ;; implemented as BaseParserVisitor<T>).  Top-definition references
        ;; retain the interface's unsubstituted parameter name and therefore
        ;; cannot be emitted directly in the implementing owner.
        base-definition (or (superclass-method-definition owner-type method)
                            (class-definition ctx method))
        covariant-class-return?
        (when base-definition
          (let [method-return (.getType method)
                base-return (.getType ^CtMethod base-definition)]
            (and (not (.isPrimitive method-return))
                 (not (contains? #{"java.lang.Boolean" "java.lang.Byte"
                                   "java.lang.Short" "java.lang.Integer"
                                   "java.lang.Long" "java.lang.Character"
                                   "java.lang.Float" "java.lang.Double"}
                                 (.getQualifiedName method-return)))
                 (or (instance? CtClass (.getTypeDeclaration method-return))
                     (instance? CtInterface (.getTypeDeclaration method-return)))
                 (or (= "java.lang.Object" (.getQualifiedName base-return))
                     (try (.isSubtypeOf method-return base-return)
                          (catch Exception _ false))))))
        ;; Java permits covariant returns for primitives, invariant generic
        ;; instantiations, and other shapes that C# cannot use for an override.
        ;; Emit the inherited class contract's resolved return shape whenever
        ;; it differs; Java return statements remain assignment-compatible and
        ;; C# performs the corresponding boxing/upcast.
        substituted-return (when (and base-definition (not covariant-class-return?))
                             (substituted-direct-base-return owner-type base-definition))
        rrb-nested-split? (and (str/starts-with? (.getQualifiedName owner-type)
                                                "org.pkl.core.util.paguro.RrbTree$")
                               (= "split" (.getSimpleName method))
                               (= 1 (count (.getParameters method))))
        return-contract (when-not (or rrb-nested-split? covariant-class-return?)
                          (some (fn [^CtMethod definition]
                                (when (and definition
                                           (selected-declaration? ctx definition)
                                           (not (type-parameter-component?
                                                 (.getType definition)))
                                           (.containsKey ^IdentityHashMap (:occurrence-index ctx)
                                                         (.getType definition))
                                           (not= (destination-type-key (.getType method))
                                                 (destination-type-key (.getType definition))))
                                  definition))
                                (cons base-definition (top-definitions ctx method))))
        external-object-interface-contract?
        (some (fn [^CtMethod definition]
                (let [declaring-type (.getDeclaringType definition)]
                  (and declaring-type
                       (.isInterface declaring-type)
                       (= "java.lang.Object"
                          (.getQualifiedName (.getType definition))))))
              (top-definitions ctx method))
        return-reference (cond
                           substituted-return substituted-return
                           return-contract (.getType ^CtMethod return-contract)
                           :else (.getType method))
        forced-return
        (cond
          rrb-nested-split?
          (let [element-name (type-parameter-name
                              (first (.getFormalCtTypeParameters owner-type)))]
            (raw (str "global::Pkl.Core.Runtime.JavaTuple2<"
                      "global::Pkl.Core.Util.Paguro.RrbTree<" element-name ">, "
                      "global::Pkl.Core.Util.Paguro.RrbTree<" element-name ">>")))

          (and (= "org.pkl.core.ast.builder.AstBuilder" (.getQualifiedName owner-type))
               (= "visitModifier" (.getSimpleName method)))
          (raw "object")

          :else nil)
        return-type (or forced-return
                        (if (and external-object-interface-contract?
                                 (not= "java.lang.Object" (.getQualifiedName (.getType method))))
                          (raw "object")
                          (type-node ctx return-reference)))
        return-type (if (and (nullable-annotation? method)
                             (not (.isPrimitive (.getType method))))
                      (sequence-node [return-type (raw "?")])
                      return-type)
        translated-body (when body (translated-node ctx body))
        ;; Java's anonymous Iterator implementation in Pair has no direct C#
        ;; anonymous-class equivalent.  Keep recursively translating its live
        ;; Spoon body for coverage, then map this exact resolved product method
        ;; to the equivalent disposable C# enumerator expression.
        translated-body
        (cond
          rrb-nested-split?
          (sequence-node [(raw "{\nreturn base.Split(splitIndex);\n}")])

          (= "executable:org.pkl.core.externalreader.ExternalReaderProcessImpl#getTransport()"
             (spoon/declaration-key method))
          ;; The JVM process/pipe lifecycle relies on daemon-thread and stream
          ;; behavior that does not safely carry over to .NET. Keep process
          ;; construction generated from the selected Java declaration, but
          ;; retain the receive thread so close/failure can quiesce it.
          (raw
           "{\nlock (this.@lock) {\nif (this.closed) throw global::Vibeformer.Runtime.JavaCompat.NewInvalidOperationException(\"External reader process has already been closed.\");\nif (this.process is not null) {\nif (!this.process.IsAlive()) throw new global::Pkl.Core.Externalreader.ExternalReaderProcessException(global::Pkl.Core.Util.ErrorMessages.Create(\"externalReaderAlreadyTerminated\"));\nif (this.transport is null) throw new global::System.Exception(\"Assertion failed\");\nreturn this.transport;\n}\nvar command = new global::System.Collections.Generic.List<string> { this.spec.Executable };\nif (this.spec.Arguments is not null) command.AddRange(this.spec.Arguments);\nvar builder = new global::Vibeformer.Runtime.JavaProcessBuilder(command);\nif (this.spec.WorkingDir is not null) builder.Directory(this.spec.WorkingDir);\nbuilder.RedirectError(global::Vibeformer.Runtime.JavaProcessRedirect.INHERIT);\ntry {\nthis.process = builder.Start();\n} catch (global::System.IO.IOException error) {\nthrow new global::Pkl.Core.Externalreader.ExternalReaderProcessException(error);\n}\nthis.transport = global::Pkl.Core.Messaging.MessageTransports.Stream(new global::Pkl.Core.Externalreader.ExternalReaderMessagePackDecoder(this.process.GetInputStream()), new global::Pkl.Core.Externalreader.ExternalReaderMessagePackEncoder(this.process.GetOutputStream()), this.Log);\nthis.StartDestinationTransportThread(this.transport);\nreturn this.transport;\n}\n}")

          (= "executable:org.pkl.core.externalreader.ExternalReaderProcessImpl#runTransport(org.pkl.core.messaging.MessageTransport)"
             (spoon/declaration-key method))
          (raw
           "{\nglobal::System.Exception failure;\ntry {\ntransport.Start((message) => { throw new global::Pkl.Core.Messaging.ProtocolException(global::Vibeformer.Runtime.JavaCompat.Concat(\"Unexpected incoming one-way message: \", message)); }, (message) => { throw new global::Pkl.Core.Messaging.ProtocolException(global::Vibeformer.Runtime.JavaCompat.Concat(\"Unexpected incoming request message: \", message)); });\nfailure = new global::System.IO.EndOfStreamException(\"External reader process closed its output stream.\");\n} catch (global::System.Exception error) {\nfailure = error;\n}\nthis.FinishDestinationTransport(transport, failure);\n}")

          (= "executable:org.pkl.core.externalreader.ExternalReaderProcessImpl#close()"
             (spoon/declaration-key method))
          (raw "{\nthis.CloseDestinationProcess();\n}")

          (= "executable:org.pkl.core.messaging.MessageTransports$AbstractMessageTransport#accept(org.pkl.core.messaging.Message)"
             (spoon/declaration-key method))
          (raw
           "{\nthis.Log(\"Received message: {0}\", message);\nif (message is global::Pkl.Core.Messaging.Message.OneWay oneWay) {\nthis.oneWayHandler(oneWay);\n} else if (message is global::Pkl.Core.Messaging.Message.Request request) {\nthis.requestHandler(request);\n} else if (message is global::Pkl.Core.Messaging.Message.Response response) {\nvar handler = this.TakeResponseHandler(response.RequestId);\nif (handler is null) throw new global::Pkl.Core.Messaging.ProtocolException(global::Pkl.Core.Util.ErrorMessages.Create(\"unknownRequestId\", message.GetType().Name, response.RequestId));\nhandler(response);\n}\n}")

          (= "executable:org.pkl.core.messaging.MessageTransports$AbstractMessageTransport#close()"
             (spoon/declaration-key method))
          (raw "{\nthis.Log(\"Closing transport: {0}\", this);\nthis.CloseSafely();\n}")

          (= "executable:org.pkl.core.messaging.MessageTransports$AbstractMessageTransport#send(org.pkl.core.messaging.Message$Request,org.pkl.core.messaging.MessageTransport$ResponseHandler)"
             (spoon/declaration-key method))
          (raw
           "{\nthis.Log(\"Sending message: {0}\", message);\nthis.SendRequestSafely(message, responseHandler);\n}")

          (= "executable:org.pkl.core.EvaluatorImpl#doEvaluate(java.util.function.Supplier)"
             (spoon/declaration-key method))
          ;; Graal cancellation is an Error rather than an Exception and its
          ;; context close waits for the active evaluation to leave. On .NET,
          ;; capture the destination cancellation signal, leave the installed
          ;; context first, and only then resolve the timeout race and surface
          ;; the stable public Pkl diagnostic.
          (raw
           "{\nglobal::Pkl.Core.EvaluatorImpl.TimeoutTask? timeoutTask = null;\nthis.logger.Clear();\nif (this.timeout is not null) {\nif (this.timeoutExecutor is null) throw new global::System.Exception(\"Assertion failed\");\ntimeoutTask = new global::Pkl.Core.EvaluatorImpl.TimeoutTask(this);\nthis.timeoutExecutor.Schedule(timeoutTask, global::Vibeformer.Runtime.JavaCompat.DurationToMillis(this.timeout.Value), global::Vibeformer.Runtime.JavaTimeUnit.MILLISECONDS);\n}\nthis.polyglotContext.Enter();\nT? evalResult = default;\nglobal::System.Exception? failure = null;\ntry {\nevalResult = supplier();\n} catch (global::System.Exception error) {\nfailure = error;\n} finally {\ntry {\nthis.polyglotContext.Leave();\n} catch (global::System.InvalidOperationException) {\n}\n}\nif (failure is not null) {\nvar cancelled = this.polyglotContext.IsCancellationRequested && (failure is global::Vibeformer.Runtime.JavaCancellationException || failure is global::System.Threading.ThreadInterruptedException || failure is global::System.OperationCanceledException || failure is global::System.ObjectDisposedException || (failure is global::Pkl.Core.Runtime.Polyglot.PolyglotException polyglotFailure && polyglotFailure.IsCancelled()));\nif (cancelled) {\nthis.HandleTimeout(timeoutTask);\nthrow new global::Pkl.Core.PklException(\"Evaluation was cancelled because the evaluator was closed.\", failure);\n}\nif (failure is global::Pkl.Core.Runtime.VmStackOverflowException stackOverflow) {\nif (global::Pkl.Core.Runtime.VmUtils.IsPklBug(stackOverflow)) {\nthrow (new global::Pkl.Core.Runtime.VmExceptionBuilder()).Bug(\"Stack overflow\").WithCause(stackOverflow.InnerException).Build().ToPklException(this.frameTransformer, this.color);\n}\nthis.HandleTimeout(timeoutTask);\nthrow stackOverflow.ToPklException(this.frameTransformer, this.color);\n}\nif (failure is global::Pkl.Core.Runtime.VmException vmFailure) {\nthis.HandleTimeout(timeoutTask);\nthrow vmFailure.ToPklException(this.frameTransformer, this.color);\n}\nif (failure is global::Pkl.Core.PklException pklFailure) throw pklFailure;\nif (failure is global::System.TypeInitializationException initializationFailure) {\nif (initializationFailure.InnerException is not global::Pkl.Core.Runtime.VmException initializationVmFailure) throw new global::Pkl.Core.PklBugException(initializationFailure);\nvar pklException = initializationVmFailure.ToPklException(this.frameTransformer, this.color);\nvar error = global::Vibeformer.Runtime.JavaCompat.NewTypeInitializationException(pklException);\nglobal::Vibeformer.Runtime.JavaCompat.SetStackTrace(error, global::Vibeformer.Runtime.JavaCompat.GetStackTrace(initializationFailure));\nthrow new global::Pkl.Core.PklBugException(error);\n}\nthrow new global::Pkl.Core.PklBugException(failure);\n}\nthis.HandleTimeout(timeoutTask);\nreturn evalResult!;\n}")

          (= "executable:org.pkl.core.EvaluatorImpl#handleTimeout(org.pkl.core.EvaluatorImpl$TimeoutTask)"
             (spoon/declaration-key method))
          (raw
           "{\nif (timeoutTask is null || timeoutTask.Cancel()) return;\nif (this.timeout is null) throw new global::System.Exception(\"Assertion failed\");\nthis.timeoutExecutor?.WaitFor(timeoutTask);\nthrow new global::Pkl.Core.PklException(global::Pkl.Core.Util.ErrorMessages.Create(\"evaluationTimedOut\", global::Vibeformer.Runtime.JavaCompat.DurationGetSeconds(this.timeout.Value) + global::Vibeformer.Runtime.JavaCompat.DurationGetNano(this.timeout.Value) / 1.0E9D));\n}")

          (= "executable:org.pkl.core.EvaluatorImpl$TimeoutTask#cancel()"
             (spoon/declaration-key method))
          ;; Java's synchronized method modifier is represented explicitly so
          ;; cancellation cannot race the scheduled Run invocation.
          (raw
           "{\nlock (this) {\nif (this.started) return false;\nthis.cancelled = true;\n}\nreturn this.__outer.timeoutExecutor is null || this.__outer.timeoutExecutor.Cancel(this);\n}")

          (= "executable:org.pkl.core.PClassInfo#equals(java.lang.Object)"
             (spoon/declaration-key method))
          (sequence-node
           [(raw "{\nreturn global::Vibeformer.Runtime.JavaCompat.Equals(this, obj);\n}")])

          (= "executable:org.pkl.core.packages.Dependency$LocalDependency#resolveAssetUri(java.net.URI,org.pkl.core.packages.PackageAssetUri)"
             (spoon/declaration-key method))
          ;; System.Uri canonicalizes Java's file:/ spelling to file:///.
          ;; Preserve that spelling only for local-dependency resource URIs,
          ;; where Resource.uri makes the original Java form observable.
          (raw
           "{\nvar assetPath = packageAssetUri.GetAssetPath().Substring(1);\nvar resolvedPath = global::Vibeformer.Runtime.JavaCompat.PathResolve(this.path, assetPath);\nvar normalized = global::Pkl.Core.Util.IoUtils.ToNormalizedPathString(resolvedPath);\ntry {\nvar relativeUri = global::Vibeformer.Runtime.JavaCompat.NewUri(null, null, normalized, null);\nreturn global::Vibeformer.Runtime.JavaCompat.ResolveLocalDependencyUri(projectBaseUri, relativeUri);\n} catch (global::System.UriFormatException) {\nthrow global::Pkl.Core.PklBugException.UnreachableCode();\n}\n}")

          (= "executable:org.pkl.core.project.Project#load(org.pkl.core.Evaluator,org.pkl.core.ModuleSource)"
             (spoon/declaration-key method))
          ;; A CLR stack overflow terminates the process and cannot serve as
          ;; the catchable cycle signal used by the JVM implementation.
          ;; Analyze first so project cycles retain the upstream diagnostics.
          (raw
           "{\nvar cycles = Project.FindImportCycle(moduleSource);\nvar hasDirectSelfCycle = global::Vibeformer.Runtime.JavaCompat.Any(cycles, cycle => global::Vibeformer.Runtime.JavaCompat.ListCount(cycle) == 1);\nif (!global::Vibeformer.Runtime.JavaCompat.ListIsEmpty(cycles) && !hasDirectSelfCycle) {\nglobal::Pkl.Core.Runtime.VmException vmException;\nif (global::Vibeformer.Runtime.JavaCompat.ListCount(cycles) == 1) {\nvmException = (new global::Pkl.Core.Runtime.VmExceptionBuilder()).EvalError(\"cannotHaveCircularProjectDependenciesSingle\", Project.RenderCycle(global::Vibeformer.Runtime.JavaCompat.ListGet(global::Vibeformer.Runtime.JavaCompat.ToListValues(cycles), 0))).Build();\n} else {\nvar renderedCycles = Project.RenderMultipleCycles(cycles);\nvmException = (new global::Pkl.Core.Runtime.VmExceptionBuilder()).EvalError(\"cannotHaveCircularProjectDependenciesMultiple\", renderedCycles).Build();\n}\nthrow vmException.ToPklException(global::Pkl.Core.StackFrameTransformers.defaultTransformer, false);\n}\ntry {\nvar output = evaluator.EvaluateOutputValueAs<global::Pkl.Core.PObject>(moduleSource, global::Pkl.Core.PClassInfo<object>.Project);\nreturn Project.ParseProject(output);\n} catch (global::System.UriFormatException e) {\nthrow new global::Pkl.Core.PklException(e.Message, e);\n}\n}")

          (= "executable:org.pkl.core.Pair#iterator()"
             (spoon/declaration-key method))
          (sequence-node [(raw "{") (raw "\nreturn ((global::System.Collections.Generic.IEnumerable<object?>)new object?[] { this.first, this.second }).GetEnumerator();\n") (raw "}")])

          (= "executable:org.pkl.core.util.paguro.RrbTree#empty()"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<object>.ImRrbt<T>(global::System.Array.Empty<T>(), 0, new global::Pkl.Core.Util.Paguro.RrbTree<object>.Leaf<T>(global::System.Array.Empty<T>()), 0);\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree#emptyMutable()"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<object>.MutRrbt<T>(global::System.Array.Empty<T>(), 0, 0, new global::Pkl.Core.Util.Paguro.RrbTree<object>.Leaf<T>(global::System.Array.Empty<T>()), 0);\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree#emptyLeaf()"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<object>.Leaf<T>(global::System.Array.Empty<T>());\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree#genericNodeArray(int)"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<object>.Node<T>[size];\n}")

          (= "executable:org.pkl.core.stdlib.base.StringNodes#patternOf(java.lang.String)"
             (spoon/declaration-key method))
          (raw "{\nreturn global::Vibeformer.Runtime.JavaCompat.CompileLiteralRegex(regex);\n}")

          (= "executable:org.pkl.core.PClassInfo#forValue(java.lang.Object)"
             (spoon/declaration-key method))
          (raw "{\nreturn global::Pkl.Core.PClassInfo<object>.ForValueCompat(value);\n}")

          (= "executable:org.pkl.core.runtime.VmList#repeat(long)"
             (spoon/declaration-key method))
          (raw
           "{\nif (n == 0) return global::Pkl.Core.Runtime.VmList.EMPTY;\nif (n == 1) return this;\nglobal::Pkl.Core.Runtime.VmCollection.CheckPositive(n);\nvar remaining = n;\nvar result = global::Pkl.Core.Util.Paguro.RrbTree<object>.Empty<object>();\nvar factor = this.rrbt;\nwhile (remaining > 0) {\nif ((remaining & 1L) != 0) result = result.Join(factor);\nremaining >>= 1;\nif (remaining > 0) factor = factor.Join(factor);\n}\nreturn global::Pkl.Core.Runtime.VmList.Create(result);\n}")

          :else translated-body)
        declaration
        (cond
          (= "executable:org.pkl.core.project.CanonicalPackageUri#equals(java.lang.Object)"
             (spoon/declaration-key method))
          ;; C# positional records synthesize the same value equality contract
          ;; as this explicit Java override; emitting both collides with the
          ;; record-generated Equals(object) member.
          (sequence-node
           [(raw "/* Java equals(") (sequence-node params ", ")
            (raw ") is supplied by C# positional-record value equality. */")])

          record-object-equals?
          (sequence-node
           [(raw "public bool Equals(") (sequence-node params ", ")
            (raw ") ") translated-body])

          (record-component-contract? ctx method)
          (sequence-node [(raw "public ") return-type
                          (raw (str " " name " { get; }"))])

          :else
          (sequence-node
           [(raw (join-words words))
            return-type (raw (str " " name)) node
            (raw "(") (sequence-node params ", ") (raw ")")
            (constraints-node ctx parameters)
            (if body (sequence-node [(raw " ") translated-body]) (raw ";"))]))]
    (attach-declaration ctx declaration method :method (.getQualifiedName owner-type)
                        name signature :java.declaration/method)))

(declare named-inner-class?)

(defn- constructor-node [ctx ^CtType owner-type ^CtConstructor constructor]
  (let [owner (executable-owner constructor)
        name (pascal (.getSimpleName owner-type))
        {:keys [parameters node]} (formals ctx owner constructor)
        inner-owner (when (named-inner-class? owner-type) (.getDeclaringType owner-type))
        outer-type-node (when inner-owner
                          (generic-node
                           (project-type-base ctx inner-owner)
                           (mapv #(raw (identifier (.getSimpleName ^CtTypeParameter %)))
                                 (.getFormalCtTypeParameters inner-owner))))
        params (into (vec (when outer-type-node
                            [(sequence-node [outer-type-node (raw " __outer")])]))
                     (mapv #(parameter-node ctx owner %) (.getParameters constructor)))
        body (.getBody constructor)
        signature (str ".ctor(" (str/join "," (map #(.getQualifiedName (.getType ^CtParameter %))
                                                   (.getParameters constructor))) ")")
        body-context (current-body-context ctx)
        explicit-invocation (when body
                              (java-body/explicit-constructor-invocation
                               body-context body))
        initializer (when explicit-invocation
                      (java-body/constructor-initializer
                       body-context explicit-invocation
                       (when outer-type-node (raw "__outer"))))
        constructor-visibility (if (and (modifier? constructor ModifierKind/PRIVATE)
                                        (not (.isTopLevel owner-type)))
                                 "internal"
                                 (visibility constructor "internal"))
        declaration
        (sequence-node
         [(raw (join-words [constructor-visibility]))
          (raw name) node (raw "(") (sequence-node params ", ") (raw ")") initializer
          (constraints-node ctx parameters)
          (if body
            (if outer-type-node
              (sequence-node [(raw " {\nthis.__outer = __outer;\n")
                              (translated-node ctx body)
                              (raw "\n}")])
              (sequence-node [(raw " ") (translated-node ctx body)]))
            (raw ";"))])]
    (attach-declaration ctx declaration constructor :constructor
                        (.getQualifiedName owner-type) name signature
                        :java.declaration/constructor)))

(defn- field-name [^CtField field]
  (identifier (.getSimpleName field)))

(defn- private-type-component? [^CtTypeReference reference]
  (when reference
    (or (some-> reference .getTypeDeclaration
                (modifier? ModifierKind/PRIVATE))
        (and (.isArray reference)
             (private-type-component? (.getComponentType reference)))
        (some (fn [^CtTypeReference argument]
                (private-type-component? argument))
              (.getActualTypeArguments reference)))))

(defn- field-node [ctx ^CtType owner-type ^CtField field]
  (let [owner (.getQualifiedName owner-type)
        enum-value? (instance? CtEnumValue field)
        name (if enum-value? (identifier (.getSimpleName field)) (field-name field))
        initializer (.getDefaultExpression field)
        initializer-node (when initializer (translated-node ctx initializer))
        record-factory-field?
        (contains? #{"languageFactory" "runtimeFactory" "virtualMachineFactory"
                     "operatingSystemFactory" "processorFactory" "sourceCodeFactory"
                     "documentationFactory" "releaseFactory"}
                   (.getSimpleName field))
        initializer-node
        (if (and initializer-node record-factory-field?)
          (raw (reduce (fn [text property]
                         (str/replace text (str "." property "()") (str "." property)))
                       (:text (csharp/render initializer-node))
                       ["Version" "Name" "Architecture" "Homepage" "VersionInfo" "CommitId"]))
          initializer-node)
        deferred-instance-initializer?
        (or (contains? #{["org.pkl.core.runtime.VmLanguage" "localContext"]
                         ["org.pkl.core.stdlib.base.AnyNodes$GetClass" "receiverClassNode"]
                         ["org.pkl.core.stdlib.base.AnyNodes.GetClass" "receiverClassNode"]}
                       [owner (.getSimpleName field)])
            (= "receiverClassNode" (.getSimpleName field)))
        ;; Java erases generic arguments when checking member accessibility,
        ;; while C# includes them.  Cap a field at private when its closed type
        ;; mentions a private nested declaration (as Truffle cache updaters do).
        field-visibility (cond
                           enum-value? "public"
                           (and (not (.isTopLevel owner-type))
                                (modifier? owner-type ModifierKind/PRIVATE)) "internal"
                           (private-type-component? (.getType field)) "private"
                           (destination-internal-type? (.getType field)) "internal"
                           (and (modifier? field ModifierKind/PRIVATE)
                                (not (.isTopLevel owner-type))) "internal"
                           :else (visibility field (if enum-value? "public" "internal")))
        words [field-visibility
               (when (or enum-value? (modifier? field ModifierKind/STATIC)) "static")
               (when (or enum-value? (modifier? field ModifierKind/FINAL)) "readonly")
               (when (modifier? field ModifierKind/VOLATILE) "volatile")]
        declaration
        (sequence-node
         [(raw (join-words words)) (type-node ctx (.getType field))
          (raw (str " " name))
          (if (and initializer
                   (or (not (:defer-field-initializers? ctx))
                       (modifier? field ModifierKind/STATIC))
                   (not deferred-instance-initializer?))
            (sequence-node [(raw " = ") initializer-node])
            (when-not (.isPrimitive (.getType field))
              (raw " = default!")))
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

(defn- record-component-property-node [ctx ^CtType owner-type ^CtRecordComponent component]
  (let [owner (.getQualifiedName owner-type)
        name (record-component-name owner-type component)
        node (sequence-node [(raw "public ") (type-node ctx (.getType component))
                             (raw (str " " name " { get; }"))])]
    (attach-declaration ctx node component :record-component owner name nil
                        :java.declaration/record-component-property)))

(defn- explicit-record-constructor? [^CtRecord type]
  (boolean
   (some (fn [^CtConstructor constructor]
           (and (not (.isImplicit constructor))
                (= (mapv #(.getQualifiedName (.getType ^CtParameter %))
                         (.getParameters constructor))
                   (mapv #(.getQualifiedName (.getType ^CtRecordComponent %))
                         (.getRecordComponents type)))))
         (.getConstructors type))))

(defn- base-types [^CtType type]
  (let [superclass (when (instance? CtClass type) (.getSuperclass ^CtClass type))
        implicit-base? #(contains? #{"java.lang.Object" "java.lang.Record" "java.lang.Enum"}
                                   (some-> ^CtTypeReference % .getQualifiedName))
        external-jvm-interface?
        (fn [^CtTypeReference reference]
          (let [qualified (.getQualifiedName reference)
                declaration (.getTypeDeclaration reference)]
            (and (instance? CtInterface declaration)
                 (not (contains? #{"java.util.Iterator"
                                   "java.util.ListIterator"
                                   "java.util.PrimitiveIterator$OfLong"
                                   "java.lang.Iterable"
                                   "java.lang.Comparable"
                                   "java.lang.AutoCloseable"
                                   "java.io.Closeable"
                                   "com.oracle.truffle.api.instrumentation.InstrumentableNode$WrapperNode"}
                                 qualified))
                 (some #(str/starts-with? qualified %)
                       ["java." "com.oracle.truffle." "org.graalvm."]))))
        object-marker?
        (fn [^CtTypeReference reference]
          (= "object" (first (get external-type-mappings (.getQualifiedName reference)))))]
    (vec (remove #(or (nil? %) (implicit-base? %) (external-jvm-interface? %)
                      (object-marker? %))
                 (concat [superclass] (.getSuperInterfaces type))))))

(declare type-node-declaration)

(defn- anonymous-class-name [^CtConstructorCall call]
  (let [{:keys [line column]} (spoon/source-location call)]
    (str "Anonymous_" (or line 0) "_" (or column 0))))

(defn- anonymous-class-for-call [^CtConstructorCall call]
  (some #(when (instance? CtClass %) %) (.getDirectChildren call)))

(defn- nearest-enclosing-type [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtType current) current
      :else (recur (when (.isParentInitialized ^CtElement current)
                     (.getParent ^CtElement current))))))

(defn- inside? [^CtElement element ^CtElement ancestor]
  (loop [current element]
    (cond
      (nil? current) false
      (identical? current ancestor) true
      :else (recur (when (.isParentInitialized current) (.getParent current))))))

(defn- anonymous-captures [^CtClass anonymous-class]
  (->> (.getElements anonymous-class (TypeFilter. CtVariableAccess))
       (keep (fn [^CtVariableAccess access]
               (let [reference (.getVariable access)
                     declaration (.getDeclaration reference)]
                 (when (and declaration
                            (or (instance? CtLocalVariable declaration)
                                (instance? CtParameter declaration))
                            (not (inside? declaration anonymous-class)))
                   {:declaration declaration
                    :type-reference (.getType reference)}))))
       (reduce (fn [result {:keys [declaration] :as capture}]
                 (if (some #(identical? declaration (:declaration %)) result)
                   result
                   (conj result capture))) [])
       (sort-by (fn [{:keys [^CtElement declaration]}]
                  (let [{:keys [file line column]} (spoon/source-location declaration)]
                    [file line column])))
       vec))

(defn- anonymous-uses-outer? [^CtClass anonymous-class ^CtType owner-type]
  (let [owner-name (.getQualifiedName owner-type)]
    (boolean
     (some #(= owner-name (some-> ^CtThisAccess % .getType .getQualifiedName))
           (.getElements anonymous-class (TypeFilter. CtThisAccess))))))

(defn- capture-type-node [ctx {:keys [^CtElement declaration]}]
  (let [reference (.getType ^spoon.reflect.declaration.CtTypedElement declaration)
        qualified-name (.getQualifiedName ^CtTypeReference reference)
        location (spoon/source-location declaration)
        candidates
        (->> (:occurrences (:resolved-model ctx))
             (filter #(= :type (:kind %)))
             (map :reference)
             (filter #(= qualified-name (.getQualifiedName ^CtTypeReference %)))
             (sort-by (fn [^CtTypeReference candidate]
                        (let [candidate-location (spoon/source-location candidate)]
                          [(if (= (:file location) (:file candidate-location)) 0 1)
                           (Math/abs (long (- (or (:line location) 0)
                                              (or (:line candidate-location) 0))))
                           (Math/abs (long (- (or (:column location) 0)
                                              (or (:column candidate-location) 0))))]))))
        resolved-reference (first candidates)]
    (when-not resolved-reference
      (throw (ex-info "Captured variable has no resolved declaration type occurrence"
                      {:kind :missing-captured-variable-type
                       :type qualified-name
                       :source (source-ref declaration :java.capture/type)})))
    (type-node ctx resolved-reference)))

(defn- anonymous-context [ctx ^CtClass anonymous-class ^CtType owner-type captures outer?]
  (let [capture-declarations (mapv :declaration captures)
        capture-names (mapv #(str "__capture_" %2)
                            captures (range))
        original-services (:services ctx)
        services
        (assoc original-services
               :local-name
               (fn [^CtElement element]
                 (if-let [index (first (keep-indexed
                                       (fn [index declaration]
                                         (when (or (identical? element declaration)
                                                   (= (.getSimpleName element)
                                                      (.getSimpleName ^CtElement declaration)))
                                           index))
                                       capture-declarations))]
                   (str "this." (nth capture-names index))
                   ((:local-name original-services) element)))
               :this-node
               (fn [^CtThisAccess access]
                 (if (and outer?
                          (= (.getQualifiedName owner-type)
                             (some-> access .getType .getQualifiedName)))
                   (raw "this.__outer")
                   (raw "this"))))]
    (let [services (assoc services :type-node #(type-node ctx %))
          body-context (java-body/context (:resolved-model ctx) services)]
      {:ctx (assoc ctx :body-context body-context
                   :services services)
       :capture-names capture-names})))

(defn- named-inner-class? [^CtType type]
  (and (instance? CtClass type)
       (.getDeclaringType type)
       (not (.isAnonymous ^CtClass type))
       (not (modifier? type ModifierKind/STATIC))))

(defn- type-body-context [ctx ^CtType type]
  ctx)

(declare iterator-bridge-members iterator-bridge-members-for-reference)

(defn- anonymous-type-node [ctx ^CtType owner-type ^CtConstructorCall call]
  (let [^CtClass anonymous-class (anonymous-class-for-call call)
        name (anonymous-class-name call)
        owner-type-node (with-source
                          (generic-node
                           (project-type-base ctx owner-type)
                           (mapv #(raw (identifier (.getSimpleName ^CtTypeParameter %)))
                                 (.getFormalCtTypeParameters owner-type)))
                          owner-type
                          :dotnet.type/project-declaration
                          {:mapping {:registry :types
                                     :identity :dotnet.type/project-declaration
                                     :resolved-key (spoon/declaration-key owner-type)
                                     :origin :project
                                     :resolution :declaration}})
        captures (anonymous-captures anonymous-class)
        outer? (anonymous-uses-outer? anonymous-class owner-type)
        {:keys [ctx capture-names]}
        (anonymous-context ctx anonymous-class owner-type captures outer?)
        constructor-arguments (vec (.getArguments call))
        constructor-parameters
        (mapv (fn [index ^CtExpression argument]
                (sequence-node [(type-node ctx (.getType argument))
                                (raw (str " __base_" index))]))
              (range) constructor-arguments)
        capture-parameters
        (concat
         (when outer?
           [(sequence-node [owner-type-node
                            (raw " __outer")])])
         (mapv (fn [capture capture-name]
                 (sequence-node [(capture-type-node ctx capture)
                                 (raw (str " " capture-name))]))
               captures capture-names))
        constructor-parameter-nodes (vec (concat constructor-parameters capture-parameters))
        constructor-assignments
        (concat
         (when outer? [(raw "this.__outer = __outer;")])
         (mapv (fn [capture-name]
                 (raw (str "this." capture-name " = " capture-name ";")))
               capture-names)
         (keep (fn [^CtElement member]
                 (when (and (instance? CtField member)
                            (not (modifier? member ModifierKind/STATIC))
                            (.getDefaultExpression ^CtField member))
                   (sequence-node
                    [(raw (str "this." (field-name member) " = "))
                     (translated-node ctx (.getDefaultExpression ^CtField member))
                     (raw ";")])) )
               (.getTypeMembers anonymous-class)))
        base-reference (.getType call)
        base-name (.getQualifiedName ^CtTypeReference base-reference)
        base-node (when-not (= "java.lang.Object" base-name) (type-node ctx base-reference))
        base-declaration (some-> base-reference .getTypeDeclaration)
        base-interface? (instance? CtInterface base-declaration)
        base-initializer (when (and (seq constructor-arguments) (not base-interface?))
                           (sequence-node
                            [(raw " : base(")
                             (sequence-node
                              (mapv #(raw (str "__base_" %))
                                    (range (count constructor-arguments))) ", ")
                             (raw ")")]))
        capture-fields
        (concat
         (when outer?
           [(sequence-node [(raw "private readonly ")
                            owner-type-node
                            (raw " __outer;")])])
         (mapv (fn [capture capture-name]
                 (sequence-node [(raw "private readonly ")
                                 (capture-type-node ctx capture)
                                 (raw (str " " capture-name ";"))]))
               captures capture-names))
        raw-members (->> (.getTypeMembers anonymous-class)
                         (remove #(.isImplicit ^CtElement %))
                         (sort-by (fn [^CtElement member]
                                    (let [{:keys [file line column]}
                                          (spoon/source-location member)]
                                      [file line column]))))
        members (mapv (fn [member]
                        (cond
                          (instance? CtField member) (field-node (assoc ctx :defer-field-initializers? true)
                                                                 anonymous-class member)
                          (instance? CtMethod member) (method-node ctx anonymous-class member)
                          (instance? CtType member) (type-node-declaration ctx member)
                          :else
                          (throw (ex-info "Unsupported anonymous-class member"
                                          {:kind :unsupported-anonymous-class-member
                                           :class name
                                           :source (source-ref member :java.declaration/anonymous-member)}))))
                      raw-members)
        members (into (vec (or (iterator-bridge-members ctx anonymous-class)
                               (iterator-bridge-members-for-reference ctx base-reference)))
                      members)
        constructor
        (sequence-node
         [(raw (str "public " name "("))
          (sequence-node constructor-parameter-nodes ", ") (raw ")")
          base-initializer (raw " {\n")
          (sequence-node constructor-assignments "\n")
          (raw "\n}")])
        declaration
        (sequence-node
         [(raw (str "private sealed class " name))
          (when base-node (sequence-node [(raw " : ") base-node]))
          (raw "\n{\n")
          (sequence-node (vec (concat capture-fields [constructor] members)) "\n\n")
          (raw "\n}")])]
    (attach-declaration ctx declaration anonymous-class :type
                        (.getQualifiedName owner-type) name name
                        :java.declaration/anonymous-class-hoist)))

(defn- direct-owner-member [^CtElement element ^CtType owner-type]
  (loop [current element]
    (when (and current (.isParentInitialized current))
      (let [parent (.getParent current)]
        (if (identical? parent owner-type)
          current
          (recur parent))))))

(defn- owner-anonymous-calls [ctx ^CtType owner-type]
  (->> (.getElements owner-type (TypeFilter. CtConstructorCall))
       (filter anonymous-class-for-call)
       (filter #(identical? owner-type (nearest-enclosing-type %)))
       (filter #(selected-declaration? ctx (direct-owner-member % owner-type)))
       (sort-by (fn [^CtElement call]
                  (let [{:keys [file line column]} (spoon/source-location call)]
                    [file line column])))
       vec))

(defn- anonymous-constructor-arguments [services ^CtConstructorCall call]
  (let [anonymous-class (anonymous-class-for-call call)
        owner-type (nearest-enclosing-type call)
        captures (anonymous-captures anonymous-class)
        outer? (anonymous-uses-outer? anonymous-class owner-type)]
    (vec
     (concat
      (when outer? [(raw "this")])
      (map (fn [{:keys [declaration]}]
             (raw (if (and (instance? CtParameter declaration)
                           (not (and (.isParentInitialized ^CtElement declaration)
                                     (instance? CtLambda (.getParent ^CtElement declaration)))))
                    (identifier (.getSimpleName ^CtParameter declaration))
                    ((:local-name services) declaration))))
           captures)))))

(defn- iterator-element-from-reference [^CtTypeReference reference]
  (let [qualified (.getQualifiedName reference)]
    (cond
      (= "java.util.PrimitiveIterator$OfLong" qualified) :long
      (contains? #{"java.util.Iterator" "java.util.ListIterator"
                   "org.organicdesign.fp.collections.UnmodSortedIterator"}
                 qualified)
      (or (first (.getActualTypeArguments reference))
          :object))))

(defn- iterator-element-reference [^CtType type]
  (some iterator-element-from-reference (.getSuperInterfaces type)))

(defn- iterable-element-reference [^CtType type]
  (some (fn [^CtTypeReference reference]
          (when (= "java.lang.Iterable" (.getQualifiedName reference))
            (or (first (.getActualTypeArguments reference)) :object)))
        (.getSuperInterfaces type)))

(defn- collection-iterable-element-reference [^CtType type]
  (some (fn [^CtTypeReference reference]
          (when (contains? #{"java.util.Collection" "java.util.List" "java.util.Set"}
                           (.getQualifiedName reference))
            (or (first (.getActualTypeArguments reference)) :object)))
        (.getSuperInterfaces type)))

(defn- iterator-bridge-members-for-element [ctx element]
  (let [element-node (case element
                       :long (raw "long")
                       :object (raw "object")
                       (type-node ctx element))]
    [(sequence-node
      [(raw "private ") element-node (raw " __iteratorCurrent = default!;\n")
       (raw "public ") element-node (raw " Current => this.__iteratorCurrent;\n")
       (raw "object global::System.Collections.IEnumerator.Current => this.__iteratorCurrent!;\n")
       (raw (if (= :long element)
              "public bool MoveNext() { if (!this.HasNext()) return false; this.__iteratorCurrent = this.NextLong(); return true; }\n"
              "public bool MoveNext() { if (!this.HasNext()) return false; this.__iteratorCurrent = this.Next(); return true; }\n"))
       (raw "public void Reset() => throw new global::System.NotSupportedException();\n")
       (raw "public void Dispose() { }")])]))

(defn- iterator-bridge-members-for-reference [ctx reference]
  (when-let [element (iterator-element-from-reference reference)]
    (iterator-bridge-members-for-element ctx element)))

(defn- iterator-bridge-members [ctx ^CtType type]
  (when-let [element (iterator-element-reference type)]
    (iterator-bridge-members-for-element ctx element)))

(defn- iterable-bridge-members [ctx ^CtType type]
  (when-let [element (iterable-element-reference type)]
    (let [element-node (case element
                         :object (raw "object")
                         (type-node ctx element))
          owns-iterator? (some #(and (instance? CtMethod %)
                                     (= "iterator" (.getSimpleName ^CtMethod %))
                                     (empty? (.getParameters ^CtMethod %)))
                               (.getTypeMembers type))]
      [(sequence-node
        [(when (and (instance? CtClass type)
                    (modifier? type ModifierKind/ABSTRACT)
                    (not owns-iterator?))
           (sequence-node [(raw "public abstract global::System.Collections.Generic.IEnumerator<")
                           element-node (raw "> Iterator();\n")]))
         (raw "public global::System.Collections.Generic.IEnumerator<")
         element-node (raw "> GetEnumerator() => this.Iterator();\n")
         (raw "global::System.Collections.IEnumerator global::System.Collections.IEnumerable.GetEnumerator() => this.GetEnumerator();")])])))

(defn- collection-iterable-bridge-members [ctx ^CtType type]
  (when-let [element (collection-iterable-element-reference type)]
    (let [element-node (case element
                         :object (raw "object")
                         (type-node ctx element))]
      [(sequence-node
        [(raw "public global::System.Collections.Generic.IEnumerator<")
         element-node (raw "> GetEnumerator() => this.Iterator();")])])))

(defn- rrb-tree-list-bridge-members [^CtType type]
  (when (= "org.pkl.core.util.paguro.RrbTree" (.getQualifiedName type))
    [(raw (str
           "public int Count => this.Size();\n"
           "public bool IsReadOnly => true;\n"
           "public E this[int index] { get => this.Get(index); set => throw new global::System.NotSupportedException(); }\n"
           "public int IndexOf(E item) { for (var i = 0; i < this.Size(); i++) if (global::System.Collections.Generic.EqualityComparer<E>.Default.Equals(this.Get(i), item)) return i; return -1; }\n"
           "void global::System.Collections.Generic.IList<E>.Insert(int index, E item) => throw new global::System.NotSupportedException();\n"
           "public void RemoveAt(int index) => throw new global::System.NotSupportedException();\n"
           "void global::System.Collections.Generic.ICollection<E>.Add(E item) => throw new global::System.NotSupportedException();\n"
           "public void Clear() => throw new global::System.NotSupportedException();\n"
           "public bool Contains(E item) => this.IndexOf(item) >= 0;\n"
           "public void CopyTo(E[] array, int arrayIndex) { for (var i = 0; i < this.Size(); i++) array[arrayIndex + i] = this.Get(i); }\n"
           "public bool Remove(E item) => throw new global::System.NotSupportedException();\n"
           "public bool IsEmpty() => this.Size() == 0;\n"
           "public global::System.Collections.Generic.IEnumerable<E> Drop(int count) => global::System.Linq.Enumerable.Skip(this, count);\n"
           "public global::System.Collections.Generic.IEnumerable<E> Take(int count) => global::System.Linq.Enumerable.Take(this, count);\n"
           "public global::System.Collections.Generic.IEnumerable<E> Drop(long count) => global::System.Linq.Enumerable.Skip(this, checked((int)count));\n"
           "public global::System.Collections.Generic.IEnumerable<E> Take(long count) => global::System.Linq.Enumerable.Take(this, checked((int)count));\n"
           "public global::System.Collections.Generic.IEnumerable<E> SubList(int fromIndex, int toIndex) => global::System.Linq.Enumerable.Take(global::System.Linq.Enumerable.Skip(this, fromIndex), toIndex - fromIndex);\n"
           "public global::Pkl.Core.Util.Paguro.RrbTree<E> Concat(global::System.Collections.Generic.IEnumerable<E> values) { global::Pkl.Core.Util.Paguro.RrbTree<E> result = this; foreach (var value in values) result = result.Append(value); return result; }\n"
           "public int LastIndexOf(object item) { for (var i = this.Size() - 1; i >= 0; i--) if (global::System.Object.Equals(this.Get(i), item)) return i; return -1; }\n"
           "public E[] ToArray() => global::System.Linq.Enumerable.ToArray(this);\n"
           "public global::System.Collections.Generic.ISet<E> ToImSet() => new global::System.Collections.Generic.HashSet<E>(this);\n"
           "public global::System.Collections.Generic.IEnumerator<E> GetEnumerator() => this.Iterator();\n"
           "global::System.Collections.IEnumerator global::System.Collections.IEnumerable.GetEnumerator() => this.GetEnumerator();"))]))

(defn- inherits-interface? [^CtType type qualified-names]
  (loop [pending (seq (.getSuperInterfaces type))
         seen #{}]
    (when-let [^CtTypeReference reference (first pending)]
      (let [qualified (.getQualifiedName reference)]
        (cond
          (contains? qualified-names qualified) true
          (contains? seen qualified) (recur (next pending) seen)
          :else
          (recur (concat (next pending)
                         (some-> reference .getTypeDeclaration .getSuperInterfaces))
                 (conj seen qualified)))))))

(defn- destination-bridge-members [ctx ^CtType type]
  (let [superclass (when (instance? CtClass type) (.getSuperclass ^CtClass type))
        disposable? (inherits-interface? type #{"java.lang.AutoCloseable" "java.io.Closeable"})
        wrapper? (inherits-interface?
                  type
                  #{"com.oracle.truffle.api.instrumentation.InstrumentableNode$WrapperNode"})]
    (vec
     (concat
      (when (= "java.io.Writer" (some-> superclass .getQualifiedName))
        [(raw "public override global::System.Text.Encoding Encoding => global::System.Text.Encoding.Unicode;")])
      (when (= "org.pkl.core.runtime.VmValue" (.getQualifiedName type))
        [(raw "public override int GetHashCode() => base.GetHashCode();")])
      (when (instance? CtEnum type)
        (let [name (identifier (.getSimpleName type))]
          [(raw (str "public static " name " ValueOf(string name) => "
                     "global::Vibeformer.Runtime.JavaCompat.EnumValueOf<" name ">(name);\n"
                     "public static " name "[] Values() => "
                     "global::Vibeformer.Runtime.JavaCompat.EnumValues<" name ">();"))]))
      (when (= "org.pkl.core.runtime.VmLanguage" (.getQualifiedName type))
        [(raw "public VmLanguage() { this.localContext = this.locals.CreateContextThreadLocal<VmLocalContext>((ignoredCtx, ignoredThread) => new VmLocalContext()); }")])
      (when (or (contains? #{"org.pkl.core.stdlib.base.AnyNodes$GetClass"
                             "org.pkl.core.stdlib.base.AnyNodes.GetClass"}
                           (.getQualifiedName type))
                (and (= "GetClass" (.getSimpleName type))
                     (= "AnyNodes" (some-> type .getDeclaringType .getSimpleName)))
                (some #(= "receiverClassNode" (.getSimpleName ^CtField %))
                      (.getFields type)))
        [(raw "protected GetClass() { this.receiverClassNode = global::Pkl.Core.Ast.@Internal.GetClassNodeGen.Create(this.GetReceiverNode()); }")])
      (when disposable?
        [(raw (if (and (instance? CtInterface type)
                       (not (some #(= "close" (.getSimpleName ^CtMethod %))
                                  (.getMethods type))))
                "public void Close();\npublic void Dispose() => this.Close();"
                "public void Dispose() => this.Close();"))])
      (when wrapper?
        [(raw (str
               "global::Pkl.Core.Runtime.Truffle.api.nodes.Node global::Pkl.Core.Runtime.Truffle.api.instrumentation.InstrumentableNode.WrapperNode.GetDelegateNode() => this.GetDelegateNode();\n"
               "global::Pkl.Core.Runtime.Truffle.api.instrumentation.ProbeNode global::Pkl.Core.Runtime.Truffle.api.instrumentation.InstrumentableNode.WrapperNode.GetProbeNode() => this.GetProbeNode();"))])
      (when (= "org.pkl.core.util.paguro.RrbTree$Node" (.getQualifiedName type))
        [(raw "public string IndentedStr(int indent);")])
      (when (= "org.pkl.core.util.paguro.RrbTree$ImRrbt" (.getQualifiedName type))
        [(raw (str
               "public new global::Pkl.Core.Util.Paguro.RrbTree<object>.ImRrbt<E> SubList(int fromIndex, int toIndex) { var result = global::Pkl.Core.Util.Paguro.RrbTree<object>.Empty<E>(); foreach (var value in global::System.Linq.Enumerable.Take(global::System.Linq.Enumerable.Skip(this, fromIndex), toIndex - fromIndex)) result = result.Append(value); return result; }\n"
               "public global::Pkl.Core.Util.Paguro.RrbTree<object>.ImRrbt<E> Reverse() { var result = global::Pkl.Core.Util.Paguro.RrbTree<object>.Empty<E>(); foreach (var value in global::System.Linq.Enumerable.Reverse(this)) result = result.Append(value); return result; }"))])
      (when (or (= "org.pkl.core.util.paguro.RrbTree$Relaxed" (.getQualifiedName type))
                (= "org.pkl.core.util.paguro.RrbTree.Relaxed" (.getQualifiedName type))
                (and (= "Relaxed" (.getSimpleName type))
                     (= "RrbTree" (some-> type .getDeclaringType .getSimpleName))))
        [(raw "internal static int[] MakeSizeArray<TItem>(global::Pkl.Core.Util.Paguro.RrbTree<object>.Node<TItem>[] newNodes) { var result = new int[newNodes.Length]; var total = 0; for (var i = 0; i < newNodes.Length; i++) { total += newNodes[i].Size(); result[i] = total; } return result; }")])
      (iterator-bridge-members ctx type)
      (iterable-bridge-members ctx type)
      (collection-iterable-bridge-members ctx type)
      (rrb-tree-list-bridge-members type)))))

(defn- member-node [ctx ^CtType owner member]
  (cond
    (instance? CtEnumValue member) (field-node ctx owner member)
    (instance? CtField member) (field-node ctx owner member)
    (instance? CtMethod member) (method-node ctx owner member)
    (instance? CtConstructor member) (constructor-node ctx owner member)
    (instance? CtType member) (type-node-declaration ctx member)
    (instance? CtAnonymousExecutable member)
    (if (modifier? member ModifierKind/STATIC)
      (let [name (pascal (.getSimpleName owner))
            signature ".cctor()"
            declaration (sequence-node [(raw (str "static " name "() "))
                                        (translated-node ctx (.getBody ^CtAnonymousExecutable member))])]
        (attach-declaration ctx declaration member :initializer
                            (.getQualifiedName owner) name signature
                            :java.declaration/static-initializer))
      (let [id (blocker! ctx member :unsupported-instance-initializer-block
                         (.getQualifiedName owner))]
        (with-source
          (raw (str "#error " id " Java instance initializer block requires direct Spoon translation"))
          member :java.executable/pending {:diagnostic-id id})))
    :else
    (throw (ex-info (str "Unsupported live Spoon type member " (.getName (class member)))
                    {:kind :unsupported-declaration-member
                     :owner (.getQualifiedName owner)
                     :source (source-ref member :java.declaration/member)}))))

(defn- public-nested-subtype? [^CtType type]
  (when-let [owner (.getDeclaringType type)]
    (some (fn [^CtType candidate]
            (and (modifier? candidate ModifierKind/PUBLIC)
                 (instance? CtClass candidate)
                 (identical? type
                             (some-> (.getSuperclass ^CtClass candidate)
                                     .getTypeDeclaration))))
          (.getNestedTypes owner))))

(defn- implemented-interface? [ctx ^CtInterface interface]
  (boolean
   (some (fn [declaration]
           (and (instance? CtClass declaration)
                (some #(identical? interface (.getTypeDeclaration ^CtTypeReference %))
                      (.getSuperInterfaces ^CtClass declaration))))
         (when-let [^IdentityHashMap selected (:selected-declarations ctx)]
           (.keySet selected)))))

(defn- functional-interface-method [ctx ^CtType type]
  ;; A Java single-abstract-method interface is represented by a C# delegate
  ;; when the interface owns no additional members or parent contracts. This
  ;; preserves lambda conversion and invocation without inventing adapters.
  ;; Interfaces with default/static helpers remain ordinary interfaces.
  (when (and (instance? CtInterface type)
             (not= "org.pkl.core.stdlib.LanguageAwareNode" (.getQualifiedName type))
             (empty? (.getSuperInterfaces type))
             (not (implemented-interface? ctx type)))
    (let [members (->> (.getTypeMembers type)
                       (remove #(.isImplicit ^CtElement %))
                       vec)]
      (when (and (= 1 (count members))
                 (instance? CtMethod (first members))
                 (nil? (.getBody ^CtMethod (first members)))
                 (empty? (.getFormalCtTypeParameters ^CtMethod (first members)))
                 (not (modifier? (first members) ModifierKind/STATIC)))
        (first members)))))

(defn- type-words [^CtType type]
  (let [;; Java allows a public nested class to extend a less-visible sibling.
        ;; C# exposes the base type in the derived type's metadata and rejects
        ;; that shape. Promote the selected base declaration to the visibility
        ;; already exposed by its public subtype.
        declaring-type (.getDeclaringType type)
        visibility (cond
                     ;; MessagePack transport is a user-approved exclusion.
                     ;; The transport and configured-process resolver that
                     ;; depends on it remain assembly-internal implementation
                     ;; detail; the public evaluator settings stay selected.
                     (or (str/starts-with? (.getQualifiedName type)
                                           "org.pkl.core.messaging.")
                         (contains? #{"org.pkl.core.externalreader.ExternalModuleResolver"
                                      "org.pkl.core.externalreader.ExternalResourceResolver"}
                                    (.getQualifiedName type)))
                     "internal"
                     (public-nested-subtype? type) "public"
                     (and declaring-type
                          (modifier? type ModifierKind/PROTECTED)
                          (modifier? declaring-type ModifierKind/FINAL))
                     "internal"
                     :else (visibility type "internal"))]
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

(defn- source-order-key [^CtElement element]
  (let [{:keys [file line column]} (spoon/source-location element)]
    [file line column]))

(defn- distinct-selected-members [ctx ^CtType type]
  (->> (concat (when (instance? CtEnum type)
                 (.getEnumValues ^CtEnum type))
               (.getTypeMembers type))
       (reduce (fn [result member]
                 (if (some #(identical? member %) result)
                   result
                   (conj result member))) [])
       (remove #(.isImplicit ^CtElement %))
       (filter #(selected-declaration? ctx %))
       (sort-by source-order-key)
       vec))

(defn- type-node-declaration [ctx ^CtType type]
  (let [owner (some-> type .getDeclaringType .getQualifiedName)
        name (pascal (.getSimpleName type))
        qualified (.getQualifiedName type)
        functional-method (functional-interface-method ctx type)
        member-ctx (type-body-context (assoc ctx :current-type type) type)
        {:keys [parameters node]} (formals ctx qualified type)
        explicit-record-constructor? (and (instance? CtRecord type)
                                          (explicit-record-constructor? type))
        components (when (and (instance? CtRecord type)
                              (not explicit-record-constructor?))
                     (mapv #(record-component-node ctx type %)
                           (.getRecordComponents ^CtRecord type)))
        inner-owner (when (named-inner-class? type) (.getDeclaringType type))
        outer-capture-type-node
        (when inner-owner
          (generic-node
           (project-type-base member-ctx inner-owner)
           (mapv #(raw (identifier (.getSimpleName ^CtTypeParameter %)))
                 (.getFormalCtTypeParameters inner-owner))))
        outer-capture (when outer-capture-type-node
                        (sequence-node [(raw "private readonly ") outer-capture-type-node
                                        (raw " __outer = default!;")]))
        ;; Base references are translated in the declaration's lexical type
        ;; context just like member signatures. This is significant when a
        ;; nested subtype shadows a sibling base name (for example an inner
        ;; Response extending its owner's Response contract).
        bases (mapv #(type-node (assoc member-ctx :base-clause? true) %)
                    (base-types type))
        raw-members (concat (when (instance? CtEnum type)
                              (.getEnumValues ^CtEnum type))
                            (.getTypeMembers type))
        record-components (when (instance? CtRecord type)
                            (vec (.getRecordComponents ^CtRecord type)))
        explicit-record-equals?
        (and (instance? CtRecord type)
             (some (fn [member]
                     (and (instance? CtMethod member)
                          (not (.isImplicit ^CtMethod member))
                          (= "equals" (.getSimpleName ^CtMethod member))
                          (= ["java.lang.Object"]
                             (mapv #(.getQualifiedName (.getType ^CtParameter %))
                                   (.getParameters ^CtMethod member)))))
                   raw-members))
        explicit-enum-to-string?
        (and (instance? CtEnum type)
             (some (fn [member]
                     (and (instance? CtMethod member)
                          (not (.isImplicit ^CtMethod member))
                          (= "toString" (.getSimpleName ^CtMethod member))
                          (empty? (.getParameters ^CtMethod member))))
                   raw-members))
        selected-members (distinct-selected-members ctx type)
        members (if functional-method
                  []
                  (if-let [emit-members (:emit-members ctx)]
                    (emit-members ctx type selected-members)
                    (mapv #(member-node member-ctx type %) selected-members)))
        members (if explicit-record-constructor?
                  (into (mapv #(record-component-property-node ctx type %)
                              (.getRecordComponents ^CtRecord type))
                        members)
                  members)
        implicit-inner-constructor
        (when (and outer-capture-type-node
                   (not-any? #(and (instance? CtConstructor %)
                                   (not (.isImplicit ^CtElement %))
                                   (selected-declaration? ctx %))
                             raw-members))
          (sequence-node [(raw "internal ") (raw name) (raw "(") outer-capture-type-node
                          (raw " __outer) { this.__outer = __outer; }")]))
        members (into (vec (remove nil? [outer-capture implicit-inner-constructor])) members)
        members (into (vec (missing-interface-contracts member-ctx type)) members)
        members (into (vec (destination-bridge-members member-ctx type)) members)
        members (into members (mapv #(anonymous-type-node member-ctx type %)
                                    (owner-anonymous-calls member-ctx type)))
        record-value-semantics
        (when (and (instance? CtRecord type) (not explicit-record-equals?))
          (let [self-type (generic-node
                           name
                           (mapv #(raw (type-parameter-name ^CtTypeParameter %)) parameters))
                component-names (mapv #(record-component-name type %) record-components)
                comparisons
                (mapv (fn [component-name]
                        (csharp/invocation
                         (raw "global::Vibeformer.Runtime.JavaCompat.Equals")
                         [(csharp/member (raw "this") component-name)
                          (csharp/member (raw "other") component-name)]))
                      component-names)
                equality (if (seq comparisons)
                           (sequence-node comparisons " &&\n            ")
                           (raw "true"))
                values (mapv #(csharp/member (raw "this") %) component-names)]
            (sequence-node
             [(raw "public bool Equals(") self-type (raw "? other) {\n")
              (raw "if (global::System.Object.ReferenceEquals(this, other)) return true;\n")
              (raw "return other is not null &&\n            ") equality (raw ";\n}\n\n")
              (raw "public override int GetHashCode() {\nreturn ")
              (csharp/invocation (raw "global::Vibeformer.Runtime.JavaCompat.Hash") values)
              (raw ";\n}")])))
        members (cond-> members record-value-semantics (conj record-value-semantics))
        enum-to-string
        (when (and (instance? CtEnum type) (not explicit-enum-to-string?))
          (raw "public override string ToString() {\nreturn global::Vibeformer.Runtime.JavaCompat.EnumName(this);\n}"))
        members (cond-> members enum-to-string (conj enum-to-string))
        header (sequence-node
                (remove nil?
                        [(node-info-attribute type)
                         (raw (join-words (type-words type))) (raw name) node
                         (when components
                           (sequence-node [(raw "(") (sequence-node components ", ") (raw ")")]))
                         (when (seq bases)
                           (sequence-node [(raw " : ") (sequence-node bases ", ")]))
                         (constraints-node ctx parameters)]))
        declaration
        (if functional-method
          (let [method-owner (executable-owner functional-method)
                method-name (method-name ctx functional-method)
                signature (.getSignature functional-method)
                params (mapv #(parameter-node ctx method-owner %)
                             (.getParameters functional-method))
                delegate-node
                (sequence-node
                 [(raw (join-words [(visibility type "internal")
                                    "delegate"]))
                  (type-node ctx (.getType functional-method))
                  (raw (str " " name)) node
                  (raw "(") (sequence-node params ", ") (raw ")")
                  (constraints-node ctx parameters)
                  (raw ";")])]
            (attach-declaration ctx delegate-node functional-method :method
                                qualified method-name signature
                                :dotnet.declaration/functional-interface-method))
          (sequence-node
           [header (raw "\n{\n")
            (sequence-node members "\n\n")
            (raw "\n}")]))]
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
         (when (seq (:no-warn project))
           (str "    <NoWarn>" (xml-escape (str/join ";" (sort (:no-warn project)))) "</NoWarn>\n"))
         (when (seq (:define-constants project))
           (str "    <DefineConstants>$(DefineConstants);"
                (xml-escape (str/join ";" (sort (:define-constants project))))
                "</DefineConstants>\n"))
         "    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>\n"
         "    <AssemblyName>" (xml-escape (:assembly-name project)) "</AssemblyName>\n"
         "    <RootNamespace>" (xml-escape (:root-namespace project)) "</RootNamespace>\n"
         "    <Deterministic>true</Deterministic>\n"
         "    <ContinuousIntegrationBuild>true</ContinuousIntegrationBuild>\n"
         "    <PackageId>" (xml-escape (:id package)) "</PackageId>\n"
         "    <Version>" (xml-escape (:version package)) "</Version>\n"
         "    <Title>" (xml-escape (:title package)) "</Title>\n"
         "    <Description>" (xml-escape (:description package)) "</Description>\n"
         "    <Authors>" (xml-escape (:authors package)) "</Authors>\n"
         "    <PackageTags>" (xml-escape (:tags package)) "</PackageTags>\n"
         "    <PackageProjectUrl>" (xml-escape (:project-url package)) "</PackageProjectUrl>\n"
         "    <RepositoryUrl>" (xml-escape (:repository-url package)) "</RepositoryUrl>\n"
         "    <RepositoryType>" (xml-escape (:repository-type package)) "</RepositoryType>\n"
         "    <PackageRequireLicenseAcceptance>false</PackageRequireLicenseAcceptance>\n"
         "    <IsPackable>true</IsPackable>\n"
         "  </PropertyGroup>\n"
         "  <ItemGroup>\n"
         "    <Compile Include=\"" (xml-escape (:source-directory output)) "/**/*.cs\" />\n"
         (apply str
                (for [reference (sort (:project-references configuration))]
                  (str "    <ProjectReference Include=\"" (xml-escape reference) "\" />\n")))
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

(defn- emission-template
  [resolved-model]
  (let [ctx-holder (atom nil)
        top-definitions-cache (IdentityHashMap.)
        base-services {:identifier identifier
                       :pascal pascal
                       :method-name (fn [method] (method-name @ctx-holder method))
                       :anonymous-class-name anonymous-class-name
                       :record-component-name record-component-name
                       :local-name (fn [^CtElement element]
                                     (let [{:keys [line column]}
                                           (spoon/source-location element)]
                                       (str (identifier
                                             (.getSimpleName
                                              ^spoon.reflect.declaration.CtNamedElement element))
                                            "__" (or line 0) "_" (or column 0))))
                       :type-node (fn [reference] (type-node @ctx-holder reference))}
        base-services (assoc base-services :record-component-contract?
                             #(record-component-contract? @ctx-holder %))
        base-services (assoc base-services :functional-interface-method?
                             (fn [^CtMethod method]
                               (identical? method
                                           (some-> method .getDeclaringType
                                                   (#(functional-interface-method
                                                      @ctx-holder %))))))
        services
        (assoc base-services
               :anonymous-constructor-arguments
               #(anonymous-constructor-arguments base-services %)
               :this-node
               (fn [^CtThisAccess access]
                 (let [current (nearest-enclosing-type access)
                       outer (when (and current (named-inner-class? current))
                               (.getDeclaringType current))]
                   (if (and outer
                            (= (.getQualifiedName outer)
                               (some-> access .getType .getQualifiedName)))
                     (raw "this.__outer")
                     (raw "this"))))
               :named-inner-constructor-argument
               (fn [^CtConstructorCall call]
                 (let [current (nearest-enclosing-type call)
                       current-outer (when (and current (named-inner-class? current))
                                       (.getDeclaringType current))]
                   (when-let [^CtType declaration (some-> call .getType .getTypeDeclaration)]
                     (when (named-inner-class? declaration)
                       (let [call-owner (.getDeclaringType declaration)]
                         (cond
                           (same-type? current call-owner) (raw "this")
                           (and current
                                (try (.isSubtypeOf (.getReference current)
                                                   (.getReference call-owner))
                                     (catch Exception _ false))) (raw "this")
                           (same-type? current-outer call-owner) (raw "this.__outer")
                           :else nil)))))))
        body-context (java-body/context resolved-model services)]
    {:ctx-holder ctx-holder
     :top-definitions-cache top-definitions-cache
     :services services
     :body-context body-context}))

(defn- root-emission-context
  [template configuration resolved-model occurrence-index selected-declarations blocker-start]
  (let [ctx {:configuration configuration
             :resolved-model resolved-model
             :ctx-holder (:ctx-holder template)
             :top-definitions-cache (:top-definitions-cache template)
             :occurrence-index occurrence-index
             :selected-declarations selected-declarations
             :emitted (IdentityHashMap.)
             :declarations (atom [])
             :diagnostics (atom [])
             :blocker-counter (atom blocker-start)
             :body-translations (atom [])
             :body-context (:body-context template)
             :services (:services template)}]
    (reset! (:ctx-holder template) ctx)
    ctx))

(defn- element-weight [^CtElement element]
  (count (.getElements element (TypeFilter. CtElement))))

(defn- merge-emission-context! [target source]
  (doseq [entry (.entrySet ^IdentityHashMap (:emitted source))]
    (let [element (.getKey ^java.util.Map$Entry entry)
          declaration (.getValue ^java.util.Map$Entry entry)]
      (when (.containsKey ^IdentityHashMap (:emitted target) element)
        (throw (ex-info "A live Spoon declaration was emitted by multiple member jobs"
                        {:kind :duplicate-source-declaration
                         :declaration declaration})))
      (.put ^IdentityHashMap (:emitted target) element declaration)))
  (swap! (:declarations target) into @(:declarations source))
  (swap! (:diagnostics target) into @(:diagnostics source))
  (swap! (:body-translations target) into @(:body-translations source)))

(defn- balanced-work-order
  "Places largest jobs at the front of separate executor chunks while keeping
  the returned order deterministic. Results are reassembled by canonical job
  indexes, so this ordering affects scheduling only."
  [jobs]
  (let [jobs (vec (sort-by (juxt (comp - :weight) :kind :index) jobs))
        chunk-size (max 1 (long (Math/ceil
                                 (/ (double (count jobs))
                                    (* 16.0 (concurrency/current-worker-count))))))
        chunk-count (max 1 (long (Math/ceil (/ (double (count jobs)) chunk-size))))
        chunks (reduce (fn [result [index job]]
                         (update result (mod index chunk-count) conj job))
                       (vec (repeat chunk-count []))
                       (map-indexed vector jobs))]
    (vec (mapcat identity chunks))))

(defn emit-project!
  "Emits declaration-complete, body-blocked C# project inputs from a live model."
  [{:keys [workspace-root target discovery resolved-model configuration]}]
  (let [configuration (validate-configuration! configuration)
        root (paths/absolute workspace-root)
        project-root (paths/resolve-path target (get-in configuration [:output :project-directory]))
        source-root (paths/resolve-path project-root (get-in configuration [:output :source-directory]))
        occurrence-index (java/resolved-occurrence-index resolved-model)
        selected-declarations (selected-declaration-index resolved-model)
        roots (java/project-roots resolved-model)
        scheduled-roots
        (->> roots
             (map-indexed
              (fn [index ^CtType type]
                {:index index
                 :type type
                 :weight (element-weight type)
                 :member-count (+ (count (.getTypeMembers type))
                                  (if (instance? CtEnum type)
                                    (count (.getEnumValues ^CtEnum type))
                                    0))}))
             vec)
        average-root-weight (if (seq scheduled-roots)
                              (/ (double (reduce + (map :weight scheduled-roots)))
                                 (count scheduled-roots))
                              0.0)
        dominant-root
        (let [candidate (first (sort-by (juxt (comp - :weight) :index) scheduled-roots))]
          (when (and candidate
                     (<= 8 (:member-count candidate))
                     (<= (* 4.0 average-root-weight) (:weight candidate)))
            candidate))
        worker-template
        (proxy [ThreadLocal] []
          (initialValue [] (emission-template resolved-model)))
        emission-profile (atom {:root-count (count scheduled-roots)
                                :average-root-weight average-root-weight
                                :largest-root
                                (when-let [{:keys [^CtType type weight member-count]}
                                           (first (sort-by (juxt (comp - :weight) :index)
                                                           scheduled-roots))]
                                  {:name (.getQualifiedName type)
                                   :weight weight
                                   :member-count member-count})
                                :dominant-root nil})
        declaration-results
        (let [ordinary-results (atom [])]
          (letfn [(emit-root!
                    [{:keys [index type]} emit-members]
                    (let [^CtType type type
                          template (.get ^ThreadLocal worker-template)
                          base-ctx (root-emission-context
                                    template configuration resolved-model occurrence-index
                                    selected-declarations (* index 1000000000))
                          ctx (cond-> base-ctx emit-members (assoc :emit-members emit-members))
                          _ (reset! (:ctx-holder template) ctx)
                          namespace (destination-namespace ctx type)
                          relative (str (str/replace namespace "." "/") "/"
                                        (identifier (.getSimpleName type)) ".cs")
                          file (paths/resolve-path source-root relative)
                          node (sequence-node
                                [(raw (str "// <auto-generated />\n#nullable "
                                           (get-in configuration [:project :nullable])
                                           "\nnamespace " namespace ";\n\n"))
                                 (type-node-declaration ctx type) (raw "\n")])
                          rendered (csharp/render node)]
                      (write-text! file (:text rendered))
                      {:index index
                       :artifact {:file (portable project-root file)
                                  :source (spoon/source-location type)
                                  :mappings
                                  (mapv #(assoc % :file (portable project-root file))
                                        (:mappings rendered))}
                       :declarations @(:declarations ctx)
                       :diagnostics @(:diagnostics ctx)
                       :body-translations @(:body-translations ctx)}))
                  (translate-member!
                    [root-index ^CtType owner index member]
                    (let [template (.get ^ThreadLocal worker-template)
                          ctx (root-emission-context
                               template configuration resolved-model occurrence-index
                               selected-declarations
                               (+ (* root-index 1000000000) (* (inc index) 1000000)))
                          member-ctx (type-body-context (assoc ctx :current-type owner) owner)
                          node (member-node member-ctx owner member)]
                      {:kind :member
                       :index index
                       :node node
                       :ctx ctx
                       :thread (.getName (Thread/currentThread))}))
                  (emit-dominant-members
                    [dominant-ctx ^CtType owner members]
                    (let [started (System/nanoTime)
                          root-index (:index dominant-root)
                          member-jobs
                          (mapv (fn [index member]
                                  {:kind :member :index index :member member
                                   :weight (element-weight member)})
                                (range) members)
                          root-jobs
                          (->> scheduled-roots
                               (remove #(= root-index (:index %)))
                               (mapv #(assoc % :kind :root)))
                          jobs (balanced-work-order (into root-jobs member-jobs))
                          results
                          (concurrency/mapv-ordered
                           :root-and-member-translation
                           (fn [{:keys [kind index member] :as job}]
                             (case kind
                               :root {:kind :root :index index
                                      :result (emit-root! job nil)}
                               :member (translate-member! root-index owner index member)))
                           jobs)
                          member-results (sort-by :index (filter #(= :member (:kind %)) results))
                          roots (mapv :result (sort-by :index (filter #(= :root (:kind %)) results)))
                          threads (->> member-results (map :thread) set sort vec)
                          elapsed (- (System/nanoTime) started)]
                      (reset! ordinary-results roots)
                      (doseq [{member-ctx :ctx} member-results]
                        (merge-emission-context! dominant-ctx member-ctx))
                      ;; Single-worker execution runs the jobs on this same
                      ;; thread and therefore changes its template holder.
                      (reset! (:ctx-holder dominant-ctx) dominant-ctx)
                      (swap! emission-profile assoc
                             :dominant-root
                             {:name (.getQualifiedName owner)
                              :weight (:weight dominant-root)
                              :member-count (count members)
                              :member-weight (reduce + (map :weight member-jobs))
                              :largest-member-weight (reduce max 0 (map :weight member-jobs))
                              :worker-threads threads
                              :worker-participation (count threads)
                              :elapsed-millis (/ elapsed 1000000.0)})
                      (mapv :node member-results)))]
            (if dominant-root
              (let [dominant-result (emit-root! dominant-root emit-dominant-members)]
                (conj @ordinary-results dominant-result))
              (concurrency/mapv-ordered
               :declaration-translation-and-emission
               #(emit-root! % nil)
               (balanced-work-order
                (mapv #(assoc % :kind :root) scheduled-roots))))))
        declaration-results (vec (sort-by :index declaration-results))
        declaration-artifacts (mapv :artifact declaration-results)
        ctx {:configuration configuration
             :resolved-model resolved-model
             :occurrence-index occurrence-index
             :selected-declarations selected-declarations
             :declarations (atom (vec (mapcat :declarations declaration-results)))
             :diagnostics (atom (vec (mapcat :diagnostics declaration-results)))
             :body-translations (atom (vec (mapcat :body-translations declaration-results)))}
        helper-source (paths/resolve-path root "vibeformer/runtime/Vibeformer.JavaCompat.cs")
        helper-file (paths/resolve-path source-root "Vibeformer/Runtime/JavaCompat.cs")
        _ (Files/createDirectories (.getParent helper-file)
                                   (make-array java.nio.file.attribute.FileAttribute 0))
        _ (Files/copy helper-source helper-file
                      (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
        runtime-artifacts
        (mapv (fn [relative]
                (let [source (paths/resolve-path root relative)
                      destination (paths/resolve-path source-root "Pkl/Core/Runtime/Substrate"
                                                      (str (.getFileName ^Path source)))]
                  (when-not (paths/regular-file? source)
                    (throw (ex-info "Configured runtime source is missing"
                                    {:kind :missing-runtime-source :source relative})))
                  (Files/createDirectories (.getParent destination)
                                           (make-array java.nio.file.attribute.FileAttribute 0))
                  (Files/copy source destination
                              (into-array java.nio.file.CopyOption
                                          [StandardCopyOption/REPLACE_EXISTING]))
                  {:file (portable project-root destination)
                   :source {:file (portable root source) :line 1 :column 1}
                   :mappings []
                   :strategy :reviewable-product-runtime-source}))
              (:runtime-sources configuration))
        artifacts (into (conj declaration-artifacts
                              {:file (portable project-root helper-file)
                               :source {:file (portable root helper-source) :line 1 :column 1}
                               :mappings []
                               :strategy :reviewable-java-compatibility-source})
                        runtime-artifacts)
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
       :emission-profile @emission-profile
       :diagnostics @(:diagnostics ctx)
       :artifacts artifacts
       :source-accounts accounts
       :resource-artifacts resource-artifacts})))
