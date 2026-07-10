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
           [spoon.reflect.code CtConstructorCall CtExpression CtLocalVariable CtThisAccess
            CtVariableAccess]
           [spoon.reflect.declaration CtAnnotation CtAnonymousExecutable CtClass
            CtConstructor CtElement CtEnum CtEnumValue CtExecutable CtField
            CtInterface CtMethod CtModifiable CtParameter CtRecord
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

(defn- type-path [ctx ^CtType type]
  (str "global::" (destination-namespace ctx type) "."
       (str/join "." (map #(pascal (.getSimpleName ^CtType %))
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
   "java.lang.ClassLoader" ["global::System.Reflection.Assembly" :dotnet.type/assembly]
   "java.lang.Enum" ["object" :dotnet.type/enum-base]
   "java.lang.Record" ["object" :dotnet.type/record-base]
   "java.lang.Float" ["float" :dotnet.type/single]
   "java.lang.Double" ["double" :dotnet.type/double]
   "java.lang.NumberFormatException" ["global::System.FormatException" :dotnet.type/format-exception]
   "java.lang.AbstractStringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.StringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.Appendable" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.Math" ["global::System.Math" :dotnet.type/math]
   "java.lang.StrictMath" ["global::System.Math" :dotnet.type/math]
   "java.lang.System" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.lang.Thread" ["global::Pkl.Core.Runtime.JavaThread" :pkl-core.type/thread]
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
   "java.net.ConnectException" ["global::System.Net.Http.HttpRequestException" :dotnet.type/http-request-exception]
   "java.net.UnknownHostException" ["global::System.Net.Sockets.SocketException" :dotnet.type/socket-exception]
   "java.net.Inet4Address" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.Inet6Address" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.InetAddress" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.InetSocketAddress" ["global::System.Net.IPEndPoint" :dotnet.type/ip-endpoint]
   "java.net.SocketAddress" ["global::System.Net.EndPoint" :dotnet.type/endpoint]
   "java.net.Proxy" ["global::System.Net.WebProxy" :dotnet.type/web-proxy]
   "java.net.Proxy$Type" ["global::Pkl.Core.Runtime.JavaProxyType" :pkl-core.type/proxy-type]
   "java.net.ProxySelector" ["global::System.Net.IWebProxy" :dotnet.type/web-proxy-interface]
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
   "java.nio.file.DirectoryStream" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
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
   "java.nio.file.FileSystemAlreadyExistsException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.nio.file.FileSystemNotFoundException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.nio.file.attribute.BasicFileAttributes" ["global::System.IO.FileSystemInfo" :dotnet.type/file-info]
   "java.nio.file.attribute.PosixFilePermission" ["global::System.IO.UnixFileMode" :dotnet.type/unix-file-mode]
   "java.nio.file.attribute.UserPrincipalLookupService" ["object" :pkl-core.type/user-principal-lookup]
   "java.nio.file.spi.FileSystemProvider" ["global::Pkl.Core.Runtime.JavaFileSystemProvider" :pkl-core.type/file-system-provider]
   "java.nio.file.spi.FileTypeDetector" ["global::Pkl.Core.Runtime.JavaFileTypeDetector" :pkl-core.type/file-type-detector]
   "java.nio.file.NoSuchFileException" ["global::System.IO.FileNotFoundException" :dotnet.type/file-not-found-exception]
   "java.time.Duration" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.temporal.TemporalUnit" ["global::Pkl.Core.Runtime.JavaTemporalUnit" :pkl-core.type/temporal-unit]
   "java.time.temporal.ChronoUnit" ["global::Pkl.Core.Runtime.JavaTemporalUnit" :pkl-core.type/temporal-unit]
   "java.lang.Iterable" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.Collection" ["global::System.Collections.Generic.ICollection" :dotnet.type/collection]
   "java.util.List" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "java.util.ArrayList" ["global::System.Collections.Generic.List" :dotnet.type/list]
   "java.util.Set" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "java.util.HashSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/hash-set]
   "java.util.LinkedHashSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/linked-hash-set]
   "java.util.LinkedList" ["global::System.Collections.Generic.LinkedList" :dotnet.type/linked-list]
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
   "java.util.Map$Entry" ["global::System.Collections.Generic.KeyValuePair" :dotnet.type/map-entry]
   "java.util.Comparator" ["global::System.Collections.Generic.IComparer" :dotnet.type/comparer]
   "java.util.Deque" ["global::Vibeformer.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.ArrayDeque" ["global::Vibeformer.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.Iterator" ["global::System.Collections.Generic.IEnumerator" :dotnet.type/enumerator]
   "java.util.ListIterator" ["global::System.Collections.Generic.IEnumerator" :dotnet.type/enumerator]
   "java.util.PrimitiveIterator" ["global::System.Collections.IEnumerator" :dotnet.type/enumerator]
   "java.util.PrimitiveIterator$OfLong" ["global::System.Collections.Generic.IEnumerator<long>" :dotnet.type/long-enumerator]
   "java.util.ServiceLoader" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/service-loader]
   "java.util.Spliterator" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.Optional" ["global::Pkl.Core.Runtime.JavaOptional" :pkl-core.type/optional]
   "java.util.OptionalInt" ["int?" :dotnet.type/nullable-int]
   "java.util.Properties" ["global::System.Collections.Generic.IDictionary<object, object>" :dotnet.type/properties]
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
   "java.util.function.BinaryOperator" ["global::Pkl.Core.Runtime.JavaBinaryOperator" :pkl-core.type/binary-operator]
   "java.util.function.IntFunction" ["global::Vibeformer.Runtime.JavaIntFunction" :dotnet.type/int-function]
   "java.util.function.IntConsumer" ["global::System.Action<int>" :dotnet.type/int-consumer]
   "java.util.function.LongFunction" ["global::Pkl.Core.Runtime.JavaLongFunction" :pkl-core.type/long-function]
   "java.util.function.LongConsumer" ["global::System.Action<long>" :dotnet.type/long-consumer]
   "java.util.function.LongPredicate" ["global::System.Predicate<long>" :dotnet.type/long-predicate]
   "java.util.function.ToIntFunction" ["global::Vibeformer.Runtime.JavaToIntFunction" :dotnet.type/to-int-function]
   "java.util.function.IntPredicate" ["global::System.Predicate<int>" :dotnet.type/int-predicate]
   "java.util.stream.Stream" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.stream.StreamSupport" ["global::Vibeformer.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.stream.IntStream" ["global::System.Collections.Generic.IEnumerable<int>" :dotnet.type/int-enumerable]
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
   "java.util.regex.Matcher" ["global::System.Text.RegularExpressions.Match" :dotnet.type/regex-match]
   "java.util.regex.MatchResult" ["global::System.Text.RegularExpressions.Match" :dotnet.type/regex-match]
   "java.util.regex.Pattern" ["global::System.Text.RegularExpressions.Regex" :dotnet.type/regex]
   "java.util.regex.PatternSyntaxException" ["global::System.ArgumentException" :dotnet.type/argument-exception]
   "java.util.concurrent.ConcurrentHashMap" ["global::System.Collections.Concurrent.ConcurrentDictionary" :dotnet.type/concurrent-dictionary]
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
   "org.msgpack.value.Value" ["object" :excluded.messagepack/value]
   "org.msgpack.value.impl.ImmutableStringValueImpl" ["object" :excluded.messagepack/value]
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
    (or (when (empty? (.getActualTypeArguments reference))
          (when-let [base (get {"java.lang.Comparable" "global::System.IComparable<object>"
                                "java.lang.Iterable" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.util.Collection" "global::System.Collections.Generic.ICollection<object>"
                                "java.util.List" "global::System.Collections.Generic.IList<object>"
                                "java.util.ArrayList" "global::System.Collections.Generic.List<object>"
                                "java.util.Set" "global::System.Collections.Generic.ISet<object>"
                                "java.util.HashSet" "global::System.Collections.Generic.HashSet<object>"
                                "java.util.LinkedHashSet" "global::System.Collections.Generic.HashSet<object>"
                                "java.util.Map" "global::System.Collections.Generic.IDictionary<object, object>"
                                "java.util.HashMap" "global::System.Collections.Generic.Dictionary<object, object>"
                                "java.util.LinkedHashMap" "global::System.Collections.Generic.Dictionary<object, object>"
                                "java.util.Iterator" "global::System.Collections.Generic.IEnumerator<object>"}
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
                arguments (if (= "java.lang.Class" (.getQualifiedName reference))
                            []
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
        nested-type-names (when owner
                            (set (map #(pascal (.getSimpleName ^CtType %))
                                      (.getNestedTypes ^CtType owner))))]
    ;; Java permits a method and nested type to share a name; C# does not.
    ;; Derive the destination factory name from the live declaring type so the
    ;; declaration and all resolved project call sites take the same path.
    (if (contains? nested-type-names base-name)
      (str "Create" base-name)
      base-name)))

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
                          (and (instance? CtClass owner-type)
                               (.isAnonymous ^CtClass owner-type))
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
        translated-body (when body (translated-node ctx body))
        ;; Java's anonymous Iterator implementation in Pair has no direct C#
        ;; anonymous-class equivalent.  Keep recursively translating its live
        ;; Spoon body for coverage, then map this exact resolved product method
        ;; to the equivalent disposable C# enumerator expression.
        translated-body
        (if (= "executable:org.pkl.core.Pair#iterator()"
               (spoon/declaration-key method))
          (sequence-node [(raw "{") (raw "\nreturn ((global::System.Collections.Generic.IEnumerable<object?>)new object?[] { this.first, this.second }).GetEnumerator();\n") (raw "}")])
          translated-body)
        declaration
        (sequence-node
         [(raw (join-words words))
          return-type (raw (str " " name)) node
          (raw "(") (sequence-node params ", ") (raw ")")
          (constraints-node ctx parameters)
          (if body (sequence-node [(raw " ") translated-body]) (raw ";"))])]
    (attach-declaration ctx declaration method :method (.getQualifiedName owner-type)
                        name signature :java.declaration/method)))

(defn- constructor-node [ctx ^CtType owner-type ^CtConstructor constructor]
  (let [owner (executable-owner constructor)
        name (pascal (.getSimpleName owner-type))
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

(defn- private-type-component? [^CtTypeReference reference]
  (when reference
    (some (fn [^CtTypeReference argument]
            (or (some-> argument .getTypeDeclaration
                        (modifier? ModifierKind/PRIVATE))
                (private-type-component? argument)))
          (.getActualTypeArguments reference))))

(defn- field-node [ctx ^CtType owner-type ^CtField field]
  (let [owner (.getQualifiedName owner-type)
        enum-value? (instance? CtEnumValue field)
        name (if enum-value? (identifier (.getSimpleName field)) (field-name field))
        initializer (.getDefaultExpression field)
        ;; Java erases generic arguments when checking member accessibility,
        ;; while C# includes them.  Cap a field at private when its closed type
        ;; mentions a private nested declaration (as Truffle cache updaters do).
        field-visibility (if (private-type-component? (.getType field))
                           "private"
                           (visibility field (if enum-value? "public" "internal")))
        words [field-visibility
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
                                   (some-> ^CtTypeReference % .getQualifiedName))
        external-jvm-interface?
        (fn [^CtTypeReference reference]
          (let [qualified (.getQualifiedName reference)
                declaration (.getTypeDeclaration reference)]
            (and (instance? CtInterface declaration)
                 (some #(str/starts-with? qualified %)
                       ["java." "com.oracle.truffle." "org.graalvm."]))))]
    (vec (remove #(or (nil? %) (implicit-base? %) (external-jvm-interface? %))
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
                                         (when (identical? element declaration) index))
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
    {:ctx (assoc ctx :body-context (java-body/context (:resolved-model ctx) services))
     :capture-names capture-names}))

(defn- anonymous-type-node [ctx ^CtType owner-type ^CtConstructorCall call]
  (let [^CtClass anonymous-class (anonymous-class-for-call call)
        name (anonymous-class-name call)
        owner-type-node (with-source (raw (type-path ctx owner-type)) owner-type
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
               capture-names))
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
                          (instance? CtField member) (field-node ctx anonymous-class member)
                          (instance? CtMethod member) (method-node ctx anonymous-class member)
                          (instance? CtType member) (type-node-declaration ctx member)
                          :else
                          (throw (ex-info "Unsupported anonymous-class member"
                                          {:kind :unsupported-anonymous-class-member
                                           :class name
                                           :source (source-ref member :java.declaration/anonymous-member)}))))
                      raw-members)
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
             (raw ((:local-name services) declaration)))
           captures)))))

(defn- destination-bridge-members [^CtType type]
  (let [superclass (when (instance? CtClass type) (.getSuperclass ^CtClass type))]
    (cond-> []
      (= "java.io.Writer" (some-> superclass .getQualifiedName))
      (conj (raw "public override global::System.Text.Encoding Encoding => global::System.Text.Encoding.Unicode;")))))

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
        name (pascal (.getSimpleName type))
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
        members (into (vec (destination-bridge-members type)) members)
        members (into members (mapv #(anonymous-type-node ctx type %)
                                    (owner-anonymous-calls ctx type)))
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
         (when (seq (:no-warn project))
           (str "    <NoWarn>" (xml-escape (str/join ";" (sort (:no-warn project)))) "</NoWarn>\n"))
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
        base-services {:identifier identifier
                  :pascal pascal
                  :method-name method-name
                  :anonymous-class-name anonymous-class-name
                  :record-component-name record-component-name
                  :local-name (fn [^CtElement element]
                                (let [{:keys [line column]} (spoon/source-location element)]
                                  (str (identifier (.getSimpleName ^spoon.reflect.declaration.CtNamedElement element))
                                       "__" (or line 0) "_" (or column 0))))
                  :type-node (fn [reference] (type-node @ctx-holder reference))}
        services (assoc base-services :anonymous-constructor-arguments
                        #(anonymous-constructor-arguments base-services %))
        body-context (java-body/context resolved-model services)
        ctx (assoc base-context :body-context body-context :services services)
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
       :diagnostics @(:diagnostics ctx)
       :artifacts artifacts
       :source-accounts accounts
       :resource-artifacts resource-artifacts})))
