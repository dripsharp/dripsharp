(ns dripsharp.pkl.java-project
  "Pkl-target declaration and disposable project emission from live Spoon objects.

  This namespace owns Pkl-specific declaration shapes, destination mappings,
  and runtime bridges. The emitted fragments are destination C# structure, not
  a reconstructed Java AST. Every declaration is reached recursively through
  its live Spoon owner, and every type is selected through the resolver's exact
  occurrence identity."
  (:require [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as project-emission]
            [dripsharp.java-types :as java-types]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.java-body :as java-body]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.util IdentityHashMap]
           [spoon.reflect.code CtConstructorCall CtExpression CtLambda CtLiteral CtLocalVariable
            CtThisAccess CtVariableAccess]
           [spoon.reflect.declaration CtAnnotation CtAnonymousExecutable CtClass
            CtConstructor CtElement CtEnum CtEnumValue CtExecutable CtField
            CtFormalTypeDeclarer CtInterface CtMethod CtModifiable CtParameter CtRecord
            CtRecordComponent CtType CtTypeParameter ModifierKind]
           [spoon.reflect.reference CtArrayTypeReference CtIntersectionTypeReference
            CtExecutableReference CtTypeParameterReference
            CtTypeReference CtWildcardReference]
           [spoon.reflect.visitor.filter TypeFilter]))

(def ^:private core-profile "pkl-core-value-model")

(def ^:private source-revision
  (baseline/upstream-revision :pkl))

(def ^:private mechanical-source
  (baseline/mechanical-source :pkl))

(def ^:private core-legal-files
  (baseline/legal-files :pkl [:core]))

(def ^:private notice-appendix
  (:notice-appendix (baseline/read-baseline :pkl)))

(def ^:private identifier java-library/identifier)

(def ^:private pascal java-library/pascal)

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

(defn- exported-product-type?
  "Returns true when a translated type is part of the executable shipped
  product boundary. A nil boundary preserves the reusable translator's normal
  Java-derived visibility for non-Pkl projects and focused translator tests."
  [ctx ^CtType type]
  (let [boundary (:public-api-type-keys ctx)]
    (or (nil? boundary)
        (contains? boundary (spoon/declaration-key type)))))

(defn- exported-product-declaration?
  [ctx ^CtElement declaration]
  (let [boundary (:public-api-declaration-keys ctx)]
    (or (nil? boundary)
        (contains? boundary (spoon/declaration-key declaration)))))

(defn- cap-product-visibility
  [ctx ^CtElement declaration visibility]
  (let [owner (cond
                (instance? CtType declaration) declaration
                (instance? CtExecutable declaration)
                (.getDeclaringType ^CtExecutable declaration)
                (instance? CtField declaration)
                (.getDeclaringType ^CtField declaration)
                :else nil)]
    (if (or (and owner (not (exported-product-type? ctx owner)))
            (exported-product-declaration? ctx declaration)
            (contains? #{"private" "protected" "protected internal"} visibility))
      visibility
      "internal")))

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

(declare type-parameter-name)

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
        root (first declarations)
        root-current?
        (boolean (some #(same-type? root %) current))
        live-root-parameters
        (when root-current?
          (.getFormalCtTypeParameters ^CtType root))
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
      ;; do not capture RrbTree<E>. C# nested types do capture their generic
      ;; owner. Within an emitted nested helper, its physical C# owner remains
      ;; the live RrbTree<E>; references from root static methods or elsewhere
      ;; use the stable object closure used at call sites.
      (str "global::" (destination-namespace ctx declaration) ".RrbTree<"
           (if (seq live-root-parameters)
             (str/join ", "
                       (map type-parameter-name live-root-parameters))
             "object")
           ">."
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
   "java.lang.AbstractStringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.StringBuilder" ["global::System.Text.StringBuilder" :dotnet.type/string-builder]
   "java.lang.Appendable" ["global::Pkl.Core.Runtime.JavaAppendable" :pkl-core.type/appendable]
   "java.lang.System" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.lang.Thread" ["global::Pkl.Core.Runtime.JavaThread" :pkl-core.type/thread]
   "java.lang.invoke.VarHandle" ["object" :dotnet.type/var-handle-marker]
   "java.math.BigInteger" ["global::System.Numerics.BigInteger" :dotnet.type/big-integer]
   "java.math.BigDecimal" ["decimal" :dotnet.type/decimal]
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
   "java.lang.Error" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.StackOverflowError" ["global::System.StackOverflowException" :dotnet.type/stack-overflow]
   "java.lang.OutOfMemoryError" ["global::System.OutOfMemoryException" :dotnet.type/out-of-memory]
   "java.lang.NoClassDefFoundError" ["global::System.TypeLoadException" :dotnet.type/type-load-exception]
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
   "java.io.PrintStream" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.FileNotFoundException" ["global::System.IO.FileNotFoundException" :dotnet.type/file-not-found-exception]
   "java.io.UncheckedIOException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.io.Reader" ["global::System.IO.TextReader" :dotnet.type/text-reader]
   "java.io.StringReader" ["global::System.IO.StringReader" :dotnet.type/string-reader]
   "java.io.Writer" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   ;; PrintWriter is a formatting facade over Writer. TextWriter already owns
   ;; the corresponding WriteLine/Flush contract and is the consumer-facing
   ;; .NET abstraction, so public logger factories must not leak a translated
   ;; Java wrapper type.
   "java.io.PrintWriter" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.Serializable" ["object" :dotnet.type/serializable-marker]
   "java.io.Serial" ["object" :dotnet.annotation/compile-time-metadata]
   "java.net.URI" ["global::System.Uri" :dotnet.type/uri]
   "java.net.URL" ["global::System.Uri" :dotnet.type/uri]
   "java.net.ConnectException" ["global::System.Net.Sockets.SocketException" :dotnet.type/socket-exception]
   "java.net.UnknownHostException" ["global::System.Net.Sockets.SocketException" :dotnet.type/socket-exception]
   "java.net.InetSocketAddress" ["global::System.Net.IPEndPoint" :dotnet.type/ip-endpoint]
   "java.net.SocketAddress" ["global::System.Net.EndPoint" :dotnet.type/endpoint]
   "java.net.Proxy" ["global::System.Net.WebProxy" :dotnet.type/web-proxy]
   "java.net.Proxy$Type" ["global::Pkl.Core.Runtime.JavaProxyType" :pkl-core.type/proxy-type]
   "java.net.ProxySelector" ["global::Pkl.Core.Runtime.JavaProxySelector" :pkl-core.type/proxy-selector]
   "java.net.JarURLConnection" ["global::Pkl.Core.Runtime.JavaJarConnection" :pkl-core.type/jar-connection]
   "java.net.URLConnection" ["global::Pkl.Core.Runtime.JavaUrlConnection" :pkl-core.type/url-connection]
   "java.net.URISyntaxException" ["global::System.UriFormatException" :dotnet.type/uri-format-exception]
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
   "java.nio.CharBuffer" ["string" :dotnet.type/string]
   "java.nio.charset.Charset" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.nio.charset.CharsetDecoder" ["global::Pkl.Core.Runtime.JavaCharsetDecoder" :pkl-core.type/charset-decoder]
   "java.nio.charset.CharsetEncoder" ["global::Pkl.Core.Runtime.JavaCharsetEncoder" :pkl-core.type/charset-encoder]
   "java.nio.charset.CharacterCodingException" ["global::System.Text.DecoderFallbackException" :dotnet.type/decoder-exception]
   "java.nio.charset.StandardCharsets" ["global::DripSharp.Runtime.JavaStandardCharsets" :pkl-core.type/standard-charsets]
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
   "java.nio.file.Files" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.nio.file.DirectoryStream" ["global::DripSharp.Runtime.JavaDirectoryStream" :dotnet.type/directory-stream]
   "java.nio.file.DirectoryStream$Filter" ["global::System.Predicate" :dotnet.type/predicate]
   "java.nio.file.FileStore" ["global::System.IO.DriveInfo" :dotnet.type/drive-info]
   "java.nio.file.FileSystem" ["global::Pkl.Core.Runtime.JavaFileSystem" :pkl-core.type/file-system]
   "java.nio.file.FileSystems" ["global::Pkl.Core.Runtime.JavaFileSystems" :pkl-core.type/file-systems]
   "java.nio.file.FileVisitResult" ["global::Pkl.Core.Runtime.JavaFileVisitResult" :pkl-core.type/file-visit-result]
   "java.nio.file.PathMatcher" ["global::System.Predicate<string>" :pkl-core.type/path-matcher]
   "java.nio.file.Paths" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.nio.file.SimpleFileVisitor" ["global::Pkl.Core.Runtime.JavaSimpleFileVisitor" :pkl-core.type/file-visitor]
   "java.nio.file.StandardCopyOption" ["global::Pkl.Core.Runtime.JavaCopyOption" :pkl-core.type/copy-option]
   "java.nio.file.WatchService" ["global::Pkl.Core.Runtime.JavaWatchService" :pkl-core.type/watch-service]
   "java.nio.file.AccessDeniedException" ["global::System.UnauthorizedAccessException" :dotnet.type/unauthorized]
   "java.nio.file.NotDirectoryException" ["global::System.IO.DirectoryNotFoundException" :dotnet.type/directory-not-found]
   "java.nio.file.FileSystemAlreadyExistsException" ["global::Pkl.Core.Runtime.JavaFileSystemAlreadyExistsException" :dotnet.type/file-system-already-exists]
   "java.nio.file.FileSystemNotFoundException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.nio.file.attribute.PosixFilePermission" ["global::System.IO.UnixFileMode" :dotnet.type/unix-file-mode]
   "java.nio.file.attribute.UserPrincipalLookupService" ["object" :pkl-core.type/user-principal-lookup]
   "java.nio.file.spi.FileSystemProvider" ["global::Pkl.Core.Runtime.JavaFileSystemProvider" :pkl-core.type/file-system-provider]
   "java.nio.file.spi.FileTypeDetector" ["global::Pkl.Core.Runtime.JavaFileTypeDetector" :pkl-core.type/file-type-detector]
   "java.nio.file.NoSuchFileException" ["global::DripSharp.Runtime.NoSuchFileException" :dotnet.type/no-such-file-exception]
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
   "java.util.LinkedList" ["global::DripSharp.Runtime.JavaLinkedList" :dotnet.type/linked-list]
   "java.util.EnumSet" ["global::System.Collections.Generic.HashSet" :dotnet.type/enum-set]
   "java.util.AbstractCollection" ["global::System.Collections.Generic.ICollection" :dotnet.type/collection]
   "java.util.AbstractList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "java.util.AbstractSequentialList" ["global::System.Collections.Generic.IList" :dotnet.type/list-interface]
   "java.util.AbstractMap" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "java.util.AbstractSet" ["global::System.Collections.Generic.ISet" :dotnet.type/set-interface]
   "java.util.Map" ["global::System.Collections.Generic.IDictionary" :dotnet.type/map-interface]
   "java.util.HashMap" ["global::System.Collections.Generic.Dictionary" :dotnet.type/dictionary]
   "java.util.IdentityHashMap" ["global::Pkl.Core.Runtime.JavaIdentityDictionary" :pkl-core.type/identity-map]
   "java.util.LinkedHashMap" ["global::DripSharp.Runtime.JavaLinkedHashMap" :dotnet.type/linked-dictionary]
   "java.util.WeakHashMap" ["global::System.Collections.Generic.Dictionary" :dotnet.type/weak-map]
   "java.util.TreeMap" ["global::System.Collections.Generic.SortedDictionary" :dotnet.type/sorted-dictionary]
   "java.util.TreeSet" ["global::System.Collections.Generic.SortedSet" :dotnet.type/sorted-set]
   "java.util.Map$Entry" ["global::DripSharp.Runtime.JavaMapEntry" :dotnet.type/map-entry]
   "java.util.Comparator" ["global::System.Comparison"
                           :dotnet.type/comparison]
   "java.util.Deque" ["global::DripSharp.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.ArrayDeque" ["global::DripSharp.Runtime.JavaDeque" :dotnet.type/deque]
   "java.util.ServiceLoader" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/service-loader]
   "java.util.Spliterator" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.Optional" ["global::Pkl.Core.Runtime.JavaOptional" :pkl-core.type/optional]
   "java.util.Random" ["global::DripSharp.Runtime.JavaRandom" :dotnet.type/random]
   "java.util.Properties" ["global::DripSharp.Runtime.JavaProperties" :dotnet.type/properties]
   "java.util.NoSuchElementException" ["global::System.InvalidOperationException" :dotnet.type/invalid-operation]
   "java.util.Arrays" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.Base64" ["global::Pkl.Core.Runtime.JavaBase64" :pkl-core.type/base64]
   "java.util.Base64$Encoder" ["global::Pkl.Core.Runtime.JavaBase64Encoder" :pkl-core.type/base64-encoder]
   "java.util.Base64$Decoder" ["global::Pkl.Core.Runtime.JavaBase64Decoder" :pkl-core.type/base64-decoder]
   "java.util.Collections" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.Objects" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.ResourceBundle" ["global::DripSharp.Runtime.JavaResourceBundle" :dotnet.type/resource-bundle]
   "java.util.Locale" ["global::System.Globalization.CultureInfo" :dotnet.type/culture-info]
   "java.util.function.Supplier" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Function" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Consumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.Predicate" ["global::System.Predicate" :dotnet.type/predicate]
   "java.util.function.BiConsumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.BiFunction" ["global::System.Func" :dotnet.type/func]
   "java.util.function.BiPredicate" ["global::DripSharp.Runtime.JavaBiPredicate" :dotnet.type/bi-predicate]
   "java.util.function.BinaryOperator" ["global::Pkl.Core.Runtime.JavaBinaryOperator" :pkl-core.type/binary-operator]
   "java.util.function.IntFunction" ["global::DripSharp.Runtime.JavaIntFunction" :dotnet.type/int-function]
   "java.util.function.IntConsumer" ["global::System.Action<int>" :dotnet.type/int-consumer]
   "java.util.function.LongFunction" ["global::Pkl.Core.Runtime.JavaLongFunction" :pkl-core.type/long-function]
   "java.util.function.LongConsumer" ["global::System.Action<long>" :dotnet.type/long-consumer]
   "java.util.function.LongPredicate" ["global::System.Predicate<long>" :dotnet.type/long-predicate]
   "java.util.function.ToIntFunction" ["global::DripSharp.Runtime.JavaToIntFunction" :dotnet.type/to-int-function]
   "java.util.function.ToLongFunction" ["global::DripSharp.Runtime.JavaToLongFunction" :dotnet.type/to-long-function]
   "java.util.function.IntPredicate" ["global::System.Predicate<int>" :dotnet.type/int-predicate]
   "java.util.stream.Stream" ["global::System.Collections.Generic.IEnumerable" :dotnet.type/enumerable]
   "java.util.stream.StreamSupport" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.util.stream.LongStream" ["global::System.Collections.Generic.IEnumerable<long>" :dotnet.type/long-enumerable]
   "java.util.stream.Collector" ["global::DripSharp.Runtime.JavaCollector" :dotnet.type/collector]
   "java.util.stream.Collectors" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "java.text.Format" ["global::DripSharp.Runtime.JavaFormat" :dotnet.type/format]
   "java.text.MessageFormat" ["global::DripSharp.Runtime.JavaMessageFormat" :dotnet.type/message-format]
   "java.text.DecimalFormatSymbols" ["global::System.Globalization.NumberFormatInfo" :dotnet.type/number-format]
   "java.util.zip.ZipEntry" ["global::Pkl.Core.Runtime.JavaZipEntry" :pkl-core.type/zip-entry]
   "java.util.zip.ZipInputStream" ["global::Pkl.Core.Runtime.JavaZipInputStream" :pkl-core.type/zip-input-stream]
   "java.util.zip.ZipOutputStream" ["global::Pkl.Core.Runtime.JavaZipOutputStream" :pkl-core.type/zip-output-stream]
   "java.util.regex.Matcher" ["global::DripSharp.Runtime.JavaRegexMatcher" :dotnet.type/regex-matcher]
   "java.util.regex.MatchResult" ["global::DripSharp.Runtime.JavaRegexMatcher" :dotnet.type/regex-matcher]
   "java.util.regex.Pattern" ["global::System.Text.RegularExpressions.Regex" :dotnet.type/regex]
   "java.util.regex.PatternSyntaxException" ["global::System.ArgumentException" :dotnet.type/argument-exception]
   "java.util.concurrent.ConcurrentHashMap" ["global::System.Collections.Concurrent.ConcurrentDictionary" :dotnet.type/concurrent-dictionary]
   "java.util.concurrent.Future" ["global::DripSharp.Runtime.JavaFuture" :dotnet.type/future]
   "java.util.concurrent.ExecutionException" ["global::System.AggregateException" :dotnet.type/execution-exception]
   "java.util.concurrent.Executors" ["global::Pkl.Core.Runtime.JavaConcurrency" :pkl-core.type/concurrency]
   "java.util.concurrent.ExecutorService" ["global::Pkl.Core.Runtime.JavaScheduledExecutor" :pkl-core.type/executor]
   "java.util.concurrent.ThreadFactory" ["global::System.Func<global::System.Action, global::Pkl.Core.Runtime.JavaThread>" :pkl-core.type/thread-factory]
   "java.util.concurrent.ScheduledExecutorService" ["global::Pkl.Core.Runtime.JavaScheduledExecutor" :pkl-core.type/scheduled-executor]
   "java.util.concurrent.ScheduledFuture" ["global::System.Threading.Tasks.Task" :dotnet.type/task]
   "java.util.concurrent.TimeUnit" ["global::DripSharp.Runtime.JavaTimeUnit" :dotnet.type/time-unit]
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
   "org.organicdesign.fp.collections.UnmodSortedIterator"
   ["global::DripSharp.Runtime.JavaIterator"
    :pkl-core.type/organic-java-iterator]
   "org.organicdesign.fp.collections.Cowry" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
   "org.organicdesign.fp.function.Fn0" ["global::System.Func" :dotnet.type/func]
   "org.organicdesign.fp.function.Fn1" ["global::System.Func" :dotnet.type/func]
   "org.organicdesign.fp.indent.IndentUtils" ["global::DripSharp.Runtime.JavaCompat" :dotnet.type/java-compat]
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
   "org.pkl.executor.spi.v1.ExecutorSpi" ["global::Pkl.Core.Service.IExecutorSpi" :pkl-core.type/executor-spi]
   "org.pkl.executor.spi.v1.ExecutorSpiException" ["global::Pkl.Core.Service.ExecutorSpiException" :pkl-core.type/executor-spi-exception]
   "org.pkl.executor.spi.v1.ExecutorSpiOptions" ["global::Pkl.Core.Service.ExecutorSpiOptions" :pkl-core.type/executor-spi-options]
   "org.pkl.executor.spi.v1.ExecutorSpiOptions2" ["global::Pkl.Core.Service.ExecutorSpiOptions2" :pkl-core.type/executor-spi-options-2]
   "org.pkl.executor.spi.v1.ExecutorSpiOptions3" ["global::Pkl.Core.Service.ExecutorSpiOptions3" :pkl-core.type/executor-spi-options-3]
   "org.pkl.executor.spi.v1.ExecutorSpiOptions4" ["global::Pkl.Core.Service.ExecutorSpiOptions4" :pkl-core.type/executor-spi-options-4]
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

(def ^:private idiomatic-byte-array-declarations
  #{"field:org.pkl.core.PClassInfo#Bytes"
    "record-component:org.pkl.core.resource.Resource#bytes"
    "executable:org.pkl.core.Evaluator#evaluateOutputBytes(org.pkl.core.ModuleSource)"
    "executable:org.pkl.core.EvaluatorImpl#evaluateOutputBytes(org.pkl.core.ModuleSource)"
    "executable:org.pkl.core.EvaluatorImpl#evaluateOutputBytes(org.pkl.core.runtime.VmTyped)"
    "executable:org.pkl.core.FileOutput#getBytes()"
    "executable:org.pkl.core.FileOutputImpl#getBytes()"
    "executable:org.pkl.core.http.HttpClient$Builder#addCertificates(byte[])"
    "executable:org.pkl.core.http.HttpClientBuilder#addCertificates(byte[])"
    "executable:org.pkl.core.JsonRenderer$Visitor#visitBytes(byte[])"
    "executable:org.pkl.core.PListRenderer$Visitor#visitBytes(byte[])"
    "executable:org.pkl.core.PcfRenderer$Visitor#visitBytes(byte[])"
    "executable:org.pkl.core.PropertiesRenderer$Visitor#convertBytes(byte[])"
    "executable:org.pkl.core.ValueConverter#convertBytes(byte[])"
    "executable:org.pkl.core.ValueVisitor#visit(byte[])"
    "executable:org.pkl.core.ValueVisitor#visit(java.lang.Object)"
    "executable:org.pkl.core.ValueVisitor#visitBytes(byte[])"
    "executable:org.pkl.core.resource.Resource#getBytes()"
    "executable:org.pkl.core.runtime.VmBytes#export()"})

(def ^:private idiomatic-list-dispatch-declarations
  #{"executable:org.pkl.core.JsonRenderer$Visitor#visitList(java.util.List)"
    "executable:org.pkl.core.PListRenderer$Visitor#visitList(java.util.List)"
    "executable:org.pkl.core.PcfRenderer$Visitor#visitList(java.util.List)"
    "executable:org.pkl.core.PropertiesRenderer$Visitor#convertList(java.util.List)"
    "executable:org.pkl.core.ValueConverter#convert(java.lang.Object)"
    "executable:org.pkl.core.ValueConverter#convertList(java.util.List)"
    "executable:org.pkl.core.ValueVisitor#visit(java.lang.Object)"
    "executable:org.pkl.core.ValueVisitor#visitList(java.util.List)"})

(defn- enclosing-declaration-key [^CtElement element]
  (loop [current element]
    (cond
      (or (instance? CtExecutable current) (instance? CtField current))
      (spoon/declaration-key current)

      (instance? CtRecordComponent current)
      (let [parent (.getParent ^CtRecordComponent current)]
        (str "record-component:"
             (when (instance? CtType parent)
               (.getQualifiedName ^CtType parent))
             "#" (.getSimpleName ^CtRecordComponent current)))

      (and current (.isParentInitialized current))
      (recur (.getParent current))

      :else nil)))

(defn- primitive-byte-array? [^CtTypeReference reference]
  (and (instance? CtArrayTypeReference reference)
       (= "byte" (some-> ^CtArrayTypeReference reference .getComponentType
                         .getQualifiedName))))

(defn- idiomatic-byte-array-reference? [^CtTypeReference reference]
  (and (primitive-byte-array? reference)
       (contains? idiomatic-byte-array-declarations
                  (enclosing-declaration-key reference))))

(defn- idiomatic-list-dispatch-reference? [^CtTypeReference reference]
  (and (= "java.util.List" (.getQualifiedName reference))
       (contains? idiomatic-list-dispatch-declarations
                  (enclosing-declaration-key reference))))

(defn- signature-declaration-key [^CtTypeReference reference]
  (loop [current reference]
    (cond
      (instance? CtTypeReference current)
      (when (.isParentInitialized ^CtElement current)
        (recur (.getParent ^CtElement current)))

      (instance? CtParameter current)
      (some-> ^CtParameter current .getParent spoon/declaration-key)

      (instance? CtRecordComponent current)
      (let [parent (.getParent ^CtRecordComponent current)]
        (str "record-component:"
             (when (instance? CtType parent)
               (.getQualifiedName ^CtType parent))
             "#" (.getSimpleName ^CtRecordComponent current)))

      (or (instance? CtExecutable current) (instance? CtField current))
      (spoon/declaration-key current)

      :else nil)))

(defn- exported-product-signature-reference?
  [ctx ^CtTypeReference reference]
  (let [boundary (:public-api-declaration-keys ctx)]
    (and boundary
         (not (:suppress-product-signature? ctx))
         (or (:force-product-signature? ctx)
             (contains? boundary (signature-declaration-key reference))))))

(def ^:private read-only-collection-type-bases
  {"java.util.Collection" "global::System.Collections.Generic.IReadOnlyCollection"
   "java.util.List" "global::System.Collections.Generic.IReadOnlyList"
   "java.util.Map" "global::System.Collections.Generic.IReadOnlyDictionary"
   "java.util.Set" "global::System.Collections.Generic.IReadOnlySet"})

(def ^:private read-only-collection-adaptations
  {"java.util.Collection" :read-only-product-collection
   "java.util.List" :read-only-product-list
   "java.util.Map" :read-only-product-map
   "java.util.Set" :read-only-product-set})

(declare top-definitions)

(defn- exact-product-signature-collection-adaptation
  [ctx ^CtTypeReference reference]
  (when (or (exported-product-signature-reference? ctx reference)
            (when (.isParentInitialized reference)
              (let [parent (.getParent reference)]
                (cond
                  (instance? CtRecordComponent parent)
                  (exported-product-type?
                   ctx (.getParent ^CtRecordComponent parent))

                  (and (instance? CtParameter parent)
                       (.isParentInitialized ^CtParameter parent)
                       (instance? CtConstructor
                                  (.getParent ^CtParameter parent)))
                  (exported-product-declaration?
                   ctx (.getParent ^CtParameter parent))

                  :else false))))
    (get read-only-collection-adaptations (.getQualifiedName reference))))

(defn- product-signature-collection-adaptation
  [ctx ^CtTypeReference reference]
  (or (exact-product-signature-collection-adaptation ctx reference)
      (let [parameter (when (and (.isParentInitialized reference)
                                 (instance? CtParameter (.getParent reference)))
                        (.getParent reference))
            method (when (and parameter (.isParentInitialized ^CtParameter parameter)
                              (instance? CtMethod (.getParent ^CtParameter parameter)))
                     (.getParent ^CtParameter parameter))
            parameters (when method (vec (.getParameters ^CtMethod method)))
            index (when parameter
                    (first (keep-indexed #(when (identical? parameter %2) %1)
                                         parameters)))]
        (when (some? index)
          (some (fn [^CtMethod definition]
                  (let [definition-parameters (vec (.getParameters definition))]
                    (when (< index (count definition-parameters))
                      (exact-product-signature-collection-adaptation
                       ctx (.getType ^CtParameter
                            (nth definition-parameters index))))))
                (top-definitions ctx method))))))

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
    :pkl-core.type/snakeyaml-substrate
    :pkl-core.type/tuple2
    :pkl-core.type/tuple4})

(defn- formal-type-arity [declaration]
  (if (instance? CtFormalTypeDeclarer declaration)
    (count (.getFormalCtTypeParameters ^CtFormalTypeDeclarer declaration))
    0))

(def ^:private raw-pkl-type-arities
  {"org.pkl.core.ValueConverter" 1
   "org.pkl.core.stdlib.VmObjectFactory" 1
   "org.pkl.core.runtime.VmValueConverter" 1})

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
    (identifier (if shadows-outer?
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
                                "java.util.Map$Entry" "global::DripSharp.Runtime.JavaMapEntry<object, object>"
                                "java.util.Iterator" "global::DripSharp.Runtime.JavaIterator<object>"
                                "java.util.Comparator" "global::System.Comparison<object>"
                                "java.util.Spliterator" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.util.ServiceLoader" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.util.stream.Stream" "global::System.Collections.Generic.IEnumerable<object>"
                                "java.nio.file.DirectoryStream" "global::DripSharp.Runtime.JavaDirectoryStream<string>"
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
        (java-types/mapping (.getQualifiedName reference))
        (throw (ex-info (str "No declaration type mapping for " (:key occurrence))
                        {:kind :unsupported-declaration-type
                         :occurrence (dissoc occurrence :reference :declaration)})))))

(defn- pkl-type-shape
  [ctx ^CtTypeReference reference occurrence recur-node]
  (cond
          ;; Public Pkl APIs expose immutable collection views. Mutable Java
          ;; collection contracts remain available only inside translated
          ;; implementation bodies and adapters.
    (and (exported-product-signature-reference? ctx reference)
         (contains? read-only-collection-type-bases
                    (.getQualifiedName reference)))
    [(generic-node (get read-only-collection-type-bases
                        (.getQualifiedName reference))
                   (let [arguments (.getActualTypeArguments reference)]
                     (if (seq arguments)
                       (mapv recur-node arguments)
                       [(raw "object")])))
     :pkl-core.type/read-only-product-collection]

          ;; Java byte is signed, so internal arithmetic continues to use
          ;; sbyte. At exported value/data boundaries, however, byte[] is the
          ;; idiomatic CLR representation. Keep this adaptation declaration-
          ;; scoped so generic Java translation semantics are unchanged.
    (or (idiomatic-byte-array-reference? reference)
        (and (primitive-byte-array? reference)
             (exported-product-signature-reference? ctx reference)))
    [(raw "byte[]") :pkl-core.type/idiomatic-byte-array]

          ;; Java's RrbTree helpers are static nested generic types. C# nested
          ;; types capture their generic owner, so close the synthetic owner
          ;; with the helper's element argument as well as applying that
          ;; argument to the helper itself. This keeps Node<T>, MutRrbt<T>,
          ;; and ImRrbt<T> independent of the lexical RrbTree<E> instance.
    (and (= :project (:origin occurrence))
         (str/starts-with? (.getQualifiedName reference)
                           "org.pkl.core.util.paguro.RrbTree$")
         (instance? CtType (:declaration occurrence))
         (not
          (and
           (contains? #{"MutRrbt" "ImRrbt"}
                      (.getSimpleName ^CtType (:declaration occurrence)))
           (str/starts-with?
            (or (some-> ^CtType (:current-type ctx) .getQualifiedName) "")
            "org.pkl.core.util.paguro.RrbTree$"))))
    (let [declaration ^CtType (:declaration occurrence)
          actuals (vec (.getActualTypeArguments reference))
          current-type ^CtType (:current-type ctx)
          current-declarations
          (when current-type (declaring-types current-type))
          current-root ^CtType (first current-declarations)
          current-leaf-parameter
          (when (and current-type
                     (str/starts-with?
                      (.getQualifiedName current-type)
                      "org.pkl.core.util.paguro.RrbTree"))
            (first (.getFormalCtTypeParameters current-type)))
          current-root-parameter
          (when (and current-root
                     (= "org.pkl.core.util.paguro.RrbTree"
                        (.getQualifiedName current-root)))
            (first (.getFormalCtTypeParameters current-root)))
          current-parameter
          (or current-leaf-parameter current-root-parameter)
          arguments
          (if (seq actuals)
            (mapv recur-node actuals)
            (repeat
             (formal-type-arity declaration)
             (if current-parameter
               (raw (type-parameter-name current-parameter))
               (raw "object"))))
          owner-argument
          (or (first arguments)
              (when current-parameter
                (raw (type-parameter-name current-parameter)))
              (raw "object"))]
      [(sequence-node
        [(generic-node
          (str "global::" (destination-namespace ctx declaration) ".RrbTree")
          [owner-argument])
         (raw ".")
         (generic-node (pascal (.getSimpleName declaration)) arguments)])
       :pkl-core.type/static-nested-rrb-helper])

          ;; List<?> was historically widened to IEnumerable<object> to model
          ;; Java wildcard covariance. That makes a Set<object> satisfy the
          ;; list branch in ValueVisitor/ValueConverter dispatch. Preserve the
          ;; distinct upstream List/Set/Map cases at these public boundaries.
    (idiomatic-list-dispatch-reference? reference)
    [(raw "global::System.Collections.Generic.IList<object>")
     :pkl-core.type/idiomatic-list-dispatch]

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
                   (mapv recur-node (.getActualTypeArguments reference)))
     :pkl-core.type/property-function]

    (= "org.pkl.core.StackFrameTransformer" (.getQualifiedName reference))
    [(raw "global::Pkl.Core.StackFrameTransformer")
     :pkl-core.type/stack-frame-transformer]

    (and (= "org.pkl.core.runtime.VmCollection$Builder" (.getQualifiedName reference))
         (some #(instance? CtWildcardReference %)
               (.getActualTypeArguments reference)))
    [(generic-node "global::Pkl.Core.Runtime.VmCollection.Builder"
                   [(raw "global::Pkl.Core.Runtime.VmCollection")])
     :pkl-core.type/vm-collection-builder-bound]

    (instance? CtArrayTypeReference reference)
    [(sequence-node [(recur-node (.getComponentType ^CtArrayTypeReference reference))
                     (raw "[]")])
     :dotnet.type/array]

    (instance? CtWildcardReference reference)
    [(if-let [bound (.getBoundingType ^CtWildcardReference reference)]
       (recur-node bound)
       (raw "object"))
     :dotnet.type/wildcard-bound]

    (instance? CtIntersectionTypeReference reference)
    [(recur-node (first (.getBounds ^CtIntersectionTypeReference reference)))
     :dotnet.type/intersection-primary]

    :else
    (let [[base mapping-rule] (mapped-type-base ctx reference occurrence)
          raw-pkl-reference?
          (and (str/starts-with? (.getQualifiedName reference)
                                 "org.pkl.core.")
               (empty? (.getActualTypeArguments reference)))
          declaration-arity
          (if raw-pkl-reference?
            (max (get raw-pkl-type-arities
                      (.getQualifiedName reference)
                      0)
                 (formal-type-arity (:declaration occurrence))
                 (formal-type-arity (.getTypeDeclaration reference)))
            0)
                ;; System.Type is non-generic even though java.lang.Class<T>
                ;; carries a type argument.  Its resolved T remains visited by
                ;; the recursive translator, but is erased at this mapping.
          arguments (cond
                      (= "java.lang.Class" (.getQualifiedName reference))
                      []

                      (and raw-pkl-reference? (pos? declaration-arity))
                      (repeat declaration-arity (raw "object"))

                      (and (= :project (:origin occurrence))
                           (empty? (.getActualTypeArguments reference)))
                      (repeat (formal-type-arity (:declaration occurrence))
                              (raw "object"))

                      (and (contains? raw-close-derived-type-rules mapping-rule)
                           (empty? (.getActualTypeArguments reference)))
                      (repeat (formal-type-arity (.getTypeDeclaration reference))
                              (raw "object"))

                      :else
                      (mapv recur-node (.getActualTypeArguments reference)))]
      [(generic-node base arguments) mapping-rule])))

(defn- decorate-pkl-type-node
  [_ctx ^CtTypeReference reference node]
  (if (and (nullable-annotation? reference)
           (not (.isPrimitive reference))
           (not= "void" (.getQualifiedName reference)))
    (sequence-node [node (raw "?")])
    node))

(def ^:private resolved-type-policy
  {:emit-shape pkl-type-shape
   :decorate-node decorate-pkl-type-node})

(defn- pkl-owned-mapping?
  [occurrence]
  (let [key (:key occurrence)]
    (case (:kind occurrence)
      :type
      (and (str/starts-with? key "type:")
           (contains? external-type-mappings
                      (subs key (count "type:"))))

      :executable
      (java-body/adapted-invocation-key? key)

      :constructor
      (java-body/adapted-constructor-key? key)

      :field
      (or
       (contains? java-body/field-adaptations key)
       (str/starts-with?
        key
        "field:java.time.temporal.ChronoUnit#")
       (contains?
        #{"field:java.lang.ProcessBuilder$Redirect#INHERIT"
          "field:java.util.concurrent.TimeUnit#DAYS"
          "field:java.util.concurrent.TimeUnit#MICROSECONDS"
          "field:java.util.concurrent.TimeUnit#HOURS"
          "field:java.util.concurrent.TimeUnit#MILLISECONDS"
          "field:java.util.concurrent.TimeUnit#NANOSECONDS"
          "field:java.util.concurrent.TimeUnit#SECONDS"
          "field:java.util.concurrent.TimeUnit#MINUTES"}
        key))

      false)))

(defn- declarative-mapping-required?
  [_mapping-context occurrence]
  (boolean
   (and (java-library/jdk-mapping-candidate? occurrence)
        (not (pkl-owned-mapping? occurrence)))))

(defn- type-node [ctx ^CtTypeReference reference]
  (java-library/type-node
   (assoc ctx :resolved-type-policy resolved-type-policy)
   reference))

(defn- declaration-id [^CtElement element kind]
  (let [{:keys [file line column]} (spoon/source-location element)]
    (str (name kind) ":" (or file "implicit") ":" (or line 0) ":" (or column 0)
         ":" (.getName (class element)))))

(defn- declaration-owner-type
  [^CtElement element]
  (cond
    (instance? CtType element) element
    (instance? CtExecutable element) (.getDeclaringType ^CtExecutable element)
    (instance? CtField element) (.getDeclaringType ^CtField element)
    :else
    (loop [current element]
      (when (and current (.isParentInitialized current))
        (let [parent (.getParent current)]
          (if (instance? CtType parent)
            parent
            (recur parent)))))))

(defn- destination-owner-name
  [ctx ^CtElement element]
  (when-let [^CtType owner (declaration-owner-type element)]
    (str (destination-namespace ctx owner) "."
         (str/join "$" (map #(pascal (.getSimpleName ^CtType %))
                            (declaring-types owner))))))

(defn- public-static-property-field?
  [^CtElement element]
  (and (instance? CtField element)
       (not (instance? CtEnumValue element))
       (= "org.pkl.core.StackFrameTransformers"
          (some-> ^CtField element .getDeclaringType .getQualifiedName))
       (modifier? element ModifierKind/PUBLIC)
       (modifier? element ModifierKind/STATIC)
       (modifier? element ModifierKind/FINAL)))

(defn- destination-declaration
  [ctx ^CtElement element kind name rule]
  (let [stack-frame-composition?
        (and (instance? CtMethod element)
             (= "org.pkl.core.StackFrameTransformer"
                (some-> ^CtMethod element .getDeclaringType .getQualifiedName))
             (= "andThen" (.getSimpleName ^CtMethod element)))]
    {:assembly (get-in ctx [:configuration :project :assembly-name])
     :owner (if stack-frame-composition?
              "Pkl.Core.StackFrameTransformerExtensions"
              (destination-owner-name ctx element))
     :kind (case kind
             :type "type"
             :constructor "constructor"
             :record-component "property"
             :enum-value "field"
             :field (if (public-static-property-field? element)
                      "property"
                      "field")
             :method "method"
             nil)
     :name (cond
             (= :constructor kind) ".ctor"
             (= :dotnet.declaration/functional-interface-method rule) "Invoke"
             :else name)
     :parameter-count (str (if stack-frame-composition?
                             2
                             (if (instance? CtExecutable element)
                               (count (.getParameters ^CtExecutable element))
                               0)))}))

(defn- register! [ctx ^CtElement element kind owner name signature rule]
  (let [id (declaration-id element kind)
        entry {:id id :kind kind :owner owner :name name :signature signature
               :java-key (spoon/declaration-key element)
               :destination (destination-declaration ctx element kind name rule)
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

(defn- current-body-context [ctx]
  ;; The complete semantic mapping registry is intentionally built once. Its
  ;; type service consults this holder so each sequential member translation
  ;; can retain lexical ownership without rebuilding the million-occurrence
  ;; registry for every body.
  (when-let [ctx-holder (:ctx-holder ctx)]
    (reset! ctx-holder ctx))
  (:body-context ctx))

(defn- translated-node [ctx ^CtElement element]
  (let [translation (java-library/translate-body
                     (current-body-context ctx) element)]
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

(declare method-signature-adaptation)

(defn- method-name [ctx ^CtMethod method]
  (let [simple-name (.getSimpleName method)
        owner (.getDeclaringType method)
        owner-name (some-> owner .getQualifiedName)
        base-name (cond
                    (and (= :http-send-compatibility
                            (method-signature-adaptation ctx method))
                         (not= "org.pkl.core.http.HttpClient" owner-name))
                    "SendCompatibility"
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

(def ^:private module-key-factory-create-key
  "executable:org.pkl.core.module.ModuleKeyFactory#create(java.net.URI)")

(def ^:private nullable-resource-reader-contract-keys
  #{"executable:org.pkl.core.resource.ResourceReader#read(java.net.URI)"
    "executable:org.pkl.core.externalreader.ExternalResourceResolver#read(java.net.URI)"})

(def ^:private reader-list-contract-key
  "executable:org.pkl.core.runtime.ReaderBase#listElements(org.pkl.core.SecurityManager,java.net.URI)")

(def ^:private http-send-contract-key
  "executable:org.pkl.core.http.HttpClient#send(java.net.http.HttpRequest,java.net.http.HttpResponse$BodyHandler,org.pkl.core.http.HttpClient$HttpRequestChecker)")

(def ^:private http-proxy-selector-contract-key
  "executable:org.pkl.core.http.HttpClient$Builder#setProxySelector(java.net.ProxySelector)")

(def ^:private pair-iterator-contract-key
  "executable:org.pkl.core.Pair#iterator()")

(defn- method-signature-adaptation [ctx ^CtMethod method]
  (let [definitions (cons method (top-definitions ctx method))
        keys (set (keep #(when % (spoon/declaration-key %)) definitions))]
    (cond
      (contains? keys module-key-factory-create-key) :nullable-module-key
      (some keys nullable-resource-reader-contract-keys) :nullable-resource
      (or (contains? keys reader-list-contract-key)
          (= "listElements" (.getSimpleName method)))
      :read-only-path-elements
      (contains? keys http-send-contract-key) :http-send-compatibility
      (contains? keys http-proxy-selector-contract-key) :http-proxy-selector-compatibility
      (contains? keys pair-iterator-contract-key) :nullable-object-enumerator
      (some #(product-signature-collection-adaptation ctx (.getType ^CtMethod %))
            definitions)
      (some #(product-signature-collection-adaptation ctx (.getType ^CtMethod %))
            definitions)
      (some #(and (primitive-byte-array? (.getType ^CtMethod %))
                  (exported-product-signature-reference? ctx (.getType ^CtMethod %)))
            definitions)
      :idiomatic-byte-array
      :else nil)))

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

(defn- public-messaging-contract?
  [qualified-name]
  (or (= "org.pkl.core.messaging.MessageTransport" qualified-name)
      (str/starts-with? qualified-name "org.pkl.core.messaging.MessageTransport$")
      (= "org.pkl.core.messaging.Message" qualified-name)
      (str/starts-with? qualified-name "org.pkl.core.messaging.Message$")
      (= "org.pkl.core.messaging.ProtocolException" qualified-name)))

(defn- destination-internal-type? [^CtTypeReference reference]
  (when reference
    (let [qualified-name (.getQualifiedName reference)]
      (or (contains? #{"java.util.Deque" "java.util.ArrayDeque"}
                     qualified-name)
          (and (str/starts-with? qualified-name "org.pkl.core.messaging.")
               (not (public-messaging-contract? qualified-name)))
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
        member-visibility (cap-product-visibility ctx method member-visibility)
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
        signature-adaptation (method-signature-adaptation ctx method)
        {:keys [parameters node]} (synthetic-formals-node method)
        body (.getBody method)
        return-reference (or (substituted-interface-return owner-type method)
                             (.getType method))
        return-type (or (case signature-adaptation
                          :nullable-module-key
                          (raw "global::Pkl.Core.Module.ModuleKey?")
                          :nullable-resource
                          (raw "object?")
                          :read-only-path-elements
                          (raw (str "global::System.Collections.Generic.IReadOnlyList<"
                                    "global::Pkl.Core.Module.PathElement>"))
                          :idiomatic-byte-array
                          (raw "byte[]")
                          nil)
                        (type-node ctx return-reference))
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
          return-type (raw (str " " name)) node
          (raw "(") (sequence-node params ", ") (raw ")")
          (constraints-node ctx parameters)
          (if body
            (sequence-node
             [(raw " ")
              (translated-node (assoc ctx
                                      :signature-adaptation signature-adaptation
                                      :product-return-reference return-reference)
                               body)])
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

(defn- nullable-resource-body-node [node]
  (let [text (:text (csharp/render node))
        normalized
        (str/replace
         text
         #"\(([^;\n]*?\.Read\([^;\n]*?\))\)\.OrElse\(default!\)"
         "$1")]
    (if (= text normalized) node (raw normalized))))

(defn- method-node [ctx owner-type ^CtMethod method]
  (let [owner (executable-owner method)
        name (method-name ctx method)
        signature-adaptation (method-signature-adaptation ctx method)
        loggers-stream?
        (= "executable:org.pkl.core.Loggers#stream(java.io.PrintStream)"
           (spoon/declaration-key method))
        record-object-equals?
        (and (instance? CtRecord owner-type)
             (= "equals" (.getSimpleName method))
             (= ["java.lang.Object"]
                (mapv #(.getQualifiedName (.getType ^CtParameter %))
                      (.getParameters method))))
        {:keys [parameters node]} (formals ctx owner method)
        product-contract (some #(when (exported-product-declaration? ctx %) %)
                               (top-definitions ctx method))
        product-contract-parameters (when product-contract
                                      (vec (.getParameters ^CtMethod product-contract)))
        params (mapv (fn [index ^CtParameter parameter]
                       (let [contract-reference
                             (when (and product-contract-parameters
                                        (< index (count product-contract-parameters)))
                               (.getType ^CtParameter
                                (nth product-contract-parameters index)))
                             adapted-contract?
                             (and contract-reference
                                  (or (product-signature-collection-adaptation
                                       ctx contract-reference)
                                      (and (primitive-byte-array? contract-reference)
                                           (exported-product-signature-reference?
                                            ctx contract-reference))))]
                         (parameter-node
                          ctx owner parameter
                          (cond
                            loggers-stream? (raw "global::System.IO.Stream")
                            record-object-equals?
                            (sequence-node [(raw (identifier (.getSimpleName owner-type)))
                                            (raw "?")])
                            adapted-contract? (type-node ctx contract-reference)
                            :else nil))))
                     (range) (.getParameters method))
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
        return-type (or (case signature-adaptation
                          :nullable-module-key
                          (raw "global::Pkl.Core.Module.ModuleKey?")
                          :nullable-resource
                          (raw "object?")
                          :read-only-path-elements
                          (raw (str "global::System.Collections.Generic.IReadOnlyList<"
                                    "global::Pkl.Core.Module.PathElement>"))
                          :idiomatic-byte-array
                          (raw "byte[]")
                          :nullable-object-enumerator
                          (raw "global::System.Collections.Generic.IEnumerator<object?>")
                          (:read-only-product-list :read-only-product-map
                                                   :read-only-product-set :read-only-product-collection)
                          (type-node ctx (.getType ^CtMethod
                                          (or product-contract method)))
                          nil)
                        forced-return
                        (if (and external-object-interface-contract?
                                 (not= "java.lang.Object" (.getQualifiedName (.getType method))))
                          (raw "object")
                          (type-node ctx return-reference)))
        return-type (if (and (nil? signature-adaptation)
                             (nullable-annotation? method)
                             (not (.isPrimitive (.getType method))))
                      (sequence-node [return-type (raw "?")])
                      return-type)
        translated-body (when body
                          (translated-node
                           (assoc ctx
                                  :signature-adaptation signature-adaptation
                                  :product-return-reference
                                  (or (some-> ^CtMethod return-contract .getType)
                                      substituted-return
                                      (some-> ^CtMethod product-contract .getType)
                                      (.getType method)))
                           body))
        translated-body
        (if (and translated-body
                 (= :nullable-resource signature-adaptation))
          (nullable-resource-body-node translated-body)
          translated-body)
        ;; Java's anonymous Iterator implementation in Pair has no direct C#
        ;; anonymous-class equivalent.  Keep recursively translating its live
        ;; Spoon body for coverage, then map this exact resolved product method
        ;; to the equivalent disposable C# enumerator expression.
        translated-body
        (cond
          rrb-nested-split?
          (sequence-node [(raw "{\nreturn base.Split(splitIndex);\n}")])

          (= "executable:org.pkl.core.Loggers#stdErr()"
             (spoon/declaration-key method))
          (raw "{\nreturn Loggers.Writer(global::System.Console.Error);\n}")

          loggers-stream?
          (raw
           "{\nvar writer = new global::System.IO.StreamWriter(stream, new global::System.Text.UTF8Encoding(false), 1024, true) { AutoFlush = true };\nreturn Loggers.Writer(writer);\n}")

          (= "executable:org.pkl.core.externalreader.ExternalReaderProcessImpl#getTransport()"
             (spoon/declaration-key method))
          ;; The JVM process/pipe lifecycle relies on daemon-thread and stream
          ;; behavior that does not safely carry over to .NET. Keep process
          ;; construction generated from the selected Java declaration, but
          ;; retain the receive thread so close/failure can quiesce it.
          (raw
           "{\nlock (this.@lock) {\nif (this.closed) throw global::DripSharp.Runtime.JavaCompat.NewInvalidOperationException(\"External reader process has already been closed.\");\nif (this.process is not null) {\nif (!this.process.IsAlive()) throw new global::Pkl.Core.Externalreader.ExternalReaderProcessException(global::Pkl.Core.Util.ErrorMessages.Create(\"externalReaderAlreadyTerminated\"));\nif (this.transport is null) throw new global::System.Exception(\"Assertion failed\");\nreturn this.transport;\n}\nvar command = new global::System.Collections.Generic.List<string> { this.spec.Executable };\nif (this.spec.Arguments is not null) command.AddRange(this.spec.Arguments);\nvar builder = new global::DripSharp.Runtime.JavaProcessBuilder(command);\nif (this.spec.WorkingDir is not null) builder.Directory(this.spec.WorkingDir);\nbuilder.RedirectError(global::DripSharp.Runtime.JavaProcessRedirect.INHERIT);\ntry {\nthis.process = builder.Start();\n} catch (global::System.IO.IOException error) {\nthrow new global::Pkl.Core.Externalreader.ExternalReaderProcessException(error);\n}\nthis.transport = global::Pkl.Core.Messaging.MessageTransports.Stream(new global::Pkl.Core.Externalreader.ExternalReaderMessagePackDecoder(this.process.GetInputStream()), new global::Pkl.Core.Externalreader.ExternalReaderMessagePackEncoder(this.process.GetOutputStream()), this.Log);\nthis.StartDestinationTransportThread(this.transport);\nreturn this.transport;\n}\n}")

          (= "executable:org.pkl.core.externalreader.ExternalReaderProcessImpl#runTransport(org.pkl.core.messaging.MessageTransport)"
             (spoon/declaration-key method))
          (raw
           "{\nglobal::System.Exception failure;\ntry {\ntransport.Start((message) => { throw new global::Pkl.Core.Messaging.ProtocolException(global::DripSharp.Runtime.JavaCompat.Concat(\"Unexpected incoming one-way message: \", message)); }, (message) => { throw new global::Pkl.Core.Messaging.ProtocolException(global::DripSharp.Runtime.JavaCompat.Concat(\"Unexpected incoming request message: \", message)); });\nfailure = new global::System.IO.EndOfStreamException(\"External reader process closed its output stream.\");\n} catch (global::System.Exception error) {\nfailure = error;\n}\nthis.FinishDestinationTransport(transport, failure);\n}")

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

          (= "executable:org.pkl.core.FileOutputImpl#getText()"
             (spoon/declaration-key method))
          (raw
           "{\ntry {\nreturn this.evaluator.EvaluateOutputText(this.fileOutput);\n} catch (global::Pkl.Core.Runtime.Polyglot.PolyglotException e) {\nif (e.IsCancelled()) throw new global::Pkl.Core.PklException(\"The evaluator is no longer available\", e);\nthrow new global::Pkl.Core.PklBugException(e);\n} catch (global::System.ObjectDisposedException e) {\nthrow new global::Pkl.Core.PklException(\"The evaluator is no longer available\", e);\n}\n}")

          (= "executable:org.pkl.core.FileOutputImpl#getBytes()"
             (spoon/declaration-key method))
          (raw
           "{\ntry {\nreturn global::DripSharp.Runtime.JavaCompat.ToUnsignedBytes(this.evaluator.EvaluateOutputBytes(this.fileOutput));\n} catch (global::Pkl.Core.Runtime.Polyglot.PolyglotException e) {\nif (e.IsCancelled()) throw new global::Pkl.Core.PklException(\"The evaluator is no longer available\", e);\nthrow new global::Pkl.Core.PklBugException(e);\n} catch (global::System.ObjectDisposedException e) {\nthrow new global::Pkl.Core.PklException(\"The evaluator is no longer available\", e);\n}\n}")

          (= "executable:org.pkl.core.EvaluatorImpl#doEvaluate(java.util.function.Supplier)"
             (spoon/declaration-key method))
          ;; Graal cancellation is an Error rather than an Exception and its
          ;; context close waits for the active evaluation to leave. On .NET,
          ;; capture the destination cancellation signal, leave the installed
          ;; context first, and only then resolve the timeout race and surface
          ;; the stable public Pkl diagnostic.
          (raw
           "{\nglobal::Pkl.Core.EvaluatorImpl.TimeoutTask? timeoutTask = null;\nthis.logger.Clear();\nif (this.timeout is not null) {\nif (this.timeoutExecutor is null) throw new global::System.Exception(\"Assertion failed\");\ntimeoutTask = new global::Pkl.Core.EvaluatorImpl.TimeoutTask(this);\nthis.timeoutExecutor.Schedule(timeoutTask, global::DripSharp.Runtime.JavaCompat.DurationToMillis(this.timeout.Value), global::DripSharp.Runtime.JavaTimeUnit.MILLISECONDS);\n}\nthis.polyglotContext.Enter();\nT? evalResult = default;\nglobal::System.Exception? failure = null;\ntry {\nevalResult = supplier();\n} catch (global::System.Exception error) {\nfailure = error;\n} finally {\ntry {\nthis.polyglotContext.Leave();\n} catch (global::System.InvalidOperationException) {\n}\n}\nif (failure is not null) {\nvar cancelled = this.polyglotContext.IsCancellationRequested && (failure is global::DripSharp.Runtime.JavaCancellationException || failure is global::System.Threading.ThreadInterruptedException || failure is global::System.OperationCanceledException || failure is global::System.ObjectDisposedException || (failure is global::Pkl.Core.Runtime.Polyglot.PolyglotException polyglotFailure && polyglotFailure.IsCancelled()));\nif (cancelled) {\nthis.HandleTimeout(timeoutTask);\nthrow new global::Pkl.Core.PklException(\"Evaluation was cancelled because the evaluator was closed.\", failure);\n}\nif (failure is global::Pkl.Core.Runtime.VmStackOverflowException stackOverflow) {\nif (global::Pkl.Core.Runtime.VmUtils.IsPklBug(stackOverflow)) {\nthrow (new global::Pkl.Core.Runtime.VmExceptionBuilder()).Bug(\"Stack overflow\").WithCause(stackOverflow.InnerException).Build().ToPklException(this.frameTransformer, this.color);\n}\nthis.HandleTimeout(timeoutTask);\nthrow stackOverflow.ToPklException(this.frameTransformer, this.color);\n}\nif (failure is global::Pkl.Core.Runtime.VmException vmFailure) {\nthis.HandleTimeout(timeoutTask);\nthrow vmFailure.ToPklException(this.frameTransformer, this.color);\n}\nif (failure is global::Pkl.Core.PklException pklFailure) throw pklFailure;\nif (failure is global::System.TypeInitializationException initializationFailure) {\nif (initializationFailure.InnerException is not global::Pkl.Core.Runtime.VmException initializationVmFailure) throw new global::Pkl.Core.PklBugException(initializationFailure);\nvar pklException = initializationVmFailure.ToPklException(this.frameTransformer, this.color);\nvar error = global::DripSharp.Runtime.JavaCompat.NewTypeInitializationException(pklException);\nglobal::DripSharp.Runtime.JavaCompat.SetStackTrace(error, global::DripSharp.Runtime.JavaCompat.GetStackTrace(initializationFailure));\nthrow new global::Pkl.Core.PklBugException(error);\n}\nthrow new global::Pkl.Core.PklBugException(failure);\n}\nthis.HandleTimeout(timeoutTask);\nreturn evalResult!;\n}")

          (= "executable:org.pkl.core.EvaluatorImpl#handleTimeout(org.pkl.core.EvaluatorImpl$TimeoutTask)"
             (spoon/declaration-key method))
          (raw
           "{\nif (timeoutTask is null || timeoutTask.Cancel()) return;\nif (this.timeout is null) throw new global::System.Exception(\"Assertion failed\");\nthis.timeoutExecutor?.WaitFor(timeoutTask);\nthrow new global::Pkl.Core.PklException(global::Pkl.Core.Util.ErrorMessages.Create(\"evaluationTimedOut\", global::DripSharp.Runtime.JavaCompat.DurationGetSeconds(this.timeout.Value) + global::DripSharp.Runtime.JavaCompat.DurationGetNano(this.timeout.Value) / 1.0E9D));\n}")

          (= "executable:org.pkl.core.EvaluatorImpl$TimeoutTask#cancel()"
             (spoon/declaration-key method))
          ;; Java's synchronized method modifier is represented explicitly so
          ;; cancellation cannot race the scheduled Run invocation.
          (raw
           "{\nlock (this) {\nif (this.started) return false;\nthis.cancelled = true;\n}\nreturn this.__outer.timeoutExecutor is null || this.__outer.timeoutExecutor.Cancel(this);\n}")

          (= "executable:org.pkl.core.PClassInfo#equals(java.lang.Object)"
             (spoon/declaration-key method))
          (sequence-node
           [(raw "{\nreturn global::Pkl.Core.Runtime.PklRuntimeBridge.PClassInfoEquals(this, obj);\n}")])

          (= "executable:org.pkl.core.Pair#equals(java.lang.Object)"
             (spoon/declaration-key method))
          (raw
           "{\nif (global::System.Object.ReferenceEquals(this, obj)) return true;\nreturn global::Pkl.Core.PairEquality.EqualsPair(this.first, this.second, obj);\n}")

          (= "executable:org.pkl.core.runtime.VmBytes#export()"
             (spoon/declaration-key method))
          (raw
           "{\nreturn global::DripSharp.Runtime.JavaCompat.ToUnsignedBytes(this.GetBytes());\n}")

          (= "executable:org.pkl.core.packages.Dependency$LocalDependency#resolveAssetUri(java.net.URI,org.pkl.core.packages.PackageAssetUri)"
             (spoon/declaration-key method))
          ;; System.Uri canonicalizes Java's file:/ spelling to file:///.
          ;; Preserve that spelling only for local-dependency resource URIs,
          ;; where Resource.uri makes the original Java form observable.
          (raw
           "{\nvar assetPath = packageAssetUri.GetAssetPath().Substring(1);\nvar resolvedPath = global::DripSharp.Runtime.JavaCompat.PathResolve(this.path, assetPath);\nvar normalized = global::Pkl.Core.Util.IoUtils.ToNormalizedPathString(resolvedPath);\ntry {\nvar relativeUri = global::DripSharp.Runtime.JavaCompat.NewUri(null, null, normalized, null);\nreturn global::DripSharp.Runtime.JavaCompat.ResolveLocalDependencyUri(projectBaseUri, relativeUri);\n} catch (global::System.UriFormatException) {\nthrow global::Pkl.Core.PklBugException.UnreachableCode();\n}\n}")

          (= "executable:org.pkl.core.project.ProjectDeps#equals(java.lang.Object)"
             (spoon/declaration-key method))
          ;; The JVM method intentionally accepts raw EconomicMap values. Keep
          ;; their actual key/value types on CLR so lookup uses the canonical
          ;; package-URI comparer instead of erased object-map identity.
          (raw
           "{\nif (global::System.Object.ReferenceEquals(this, o)) return true;\nif (o is not global::Pkl.Core.Project.ProjectDeps that) return false;\nreturn global::DripSharp.Runtime.JavaCompat.EconomicMapEquals(this.resolvedDependencies, that.resolvedDependencies);\n}")

          (= "executable:org.pkl.core.project.Project#findImportCycle(org.pkl.core.ModuleSource)"
             (spoon/declaration-key method))
          ;; Project.load must support caller-supplied module factories. The
          ;; proactive CLR cycle check can only analyze schemes owned by the
          ;; standalone project analyzer; other schemes proceed to the supplied
          ;; evaluator instead of failing before evaluation starts.
          (raw
           "{\nvar scheme = global::DripSharp.Runtime.JavaCompat.UriScheme(moduleSource.GetUri());\nif (!global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase(scheme, \"file\") && !global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase(scheme, \"package\")) {\nreturn new global::System.Collections.Generic.List<global::System.Collections.Generic.IList<global::System.Uri>>();\n}\nvar builder = Project.EvaluatorBuilder();\nvar analyzer = new global::Pkl.Core.Analyzer(global::Pkl.Core.StackFrameTransformers.DefaultTransformer, builder.GetColor(), global::Pkl.Core.SecurityManagers.DefaultManager, global::DripSharp.Runtime.JavaCompat.ToReadOnly<global::System.Collections.Generic.IReadOnlyCollection<global::Pkl.Core.Module.ModuleKeyFactory>>(global::DripSharp.Runtime.JavaCompat.ToListValues(builder.GetModuleKeyFactories())), builder.GetModuleCacheDir(), builder.GetProjectDependencies(), builder.GetHttpClient(), builder.GetTraceMode());\nvar importGraph = analyzer.ImportGraph(moduleSource.GetUri());\nvar ret = global::Pkl.Core.Util.ImportGraphUtils.FindImportCycles(importGraph);\nreturn global::DripSharp.Runtime.JavaCompat.ToListValues(global::DripSharp.Runtime.JavaCompat.Filter(ret, cycle => global::DripSharp.Runtime.JavaCompat.Any(cycle, uri => global::DripSharp.Runtime.JavaCompat.EqualsIgnoreCase(global::DripSharp.Runtime.JavaCompat.UriScheme(uri), scheme))));\n}")

          (= "executable:org.pkl.core.project.Project#load(org.pkl.core.Evaluator,org.pkl.core.ModuleSource)"
             (spoon/declaration-key method))
          ;; A CLR stack overflow terminates the process and cannot serve as
          ;; the catchable cycle signal used by the JVM implementation.
          ;; Analyze first so project cycles retain the upstream diagnostics.
          (raw
           "{\nvar cycles = Project.FindImportCycle(moduleSource);\nvar onlyDirectSelfCycle = global::DripSharp.Runtime.JavaCompat.ListCount(cycles) == 1 && global::DripSharp.Runtime.JavaCompat.ListCount(global::DripSharp.Runtime.JavaCompat.ListGet(global::DripSharp.Runtime.JavaCompat.ToListValues(cycles), 0)) == 1;\nif (!global::DripSharp.Runtime.JavaCompat.ListIsEmpty(cycles) && !onlyDirectSelfCycle) {\nglobal::Pkl.Core.Runtime.VmException vmException;\nif (global::DripSharp.Runtime.JavaCompat.ListCount(cycles) == 1) {\nvmException = (new global::Pkl.Core.Runtime.VmExceptionBuilder()).EvalError(\"cannotHaveCircularProjectDependenciesSingle\", Project.RenderCycle(global::DripSharp.Runtime.JavaCompat.ListGet(global::DripSharp.Runtime.JavaCompat.ToListValues(cycles), 0))).Build();\n} else {\nvar renderedCycles = Project.RenderMultipleCycles(cycles);\nvmException = (new global::Pkl.Core.Runtime.VmExceptionBuilder()).EvalError(\"cannotHaveCircularProjectDependenciesMultiple\", renderedCycles).Build();\n}\nthrow vmException.ToPklException(global::Pkl.Core.StackFrameTransformers.DefaultTransformer, false);\n}\ntry {\nvar output = evaluator.EvaluateOutputValueAs<global::Pkl.Core.PObject>(moduleSource, global::Pkl.Core.PClassInfo<object>.Project);\nreturn Project.ParseProject(output);\n} catch (global::System.UriFormatException e) {\nthrow new global::Pkl.Core.PklException(e.Message, e);\n}\n}")

          (= "executable:org.pkl.core.Pair#iterator()"
             (spoon/declaration-key method))
          (sequence-node [(raw "{") (raw "\nreturn ((global::System.Collections.Generic.IEnumerable<object?>)new object?[] { this.first, this.second }).GetEnumerator();\n") (raw "}")])

          (= "executable:org.pkl.core.runtime.Iterators#emptyTruffleIterator()"
             (spoon/declaration-key method))
          (raw "{\nreturn global::DripSharp.Runtime.JavaCompat.EmptyJavaIterator<T>();\n}")

          (= "executable:org.pkl.core.runtime.CommandSpecParser$OptionBehavior#getMultiple()"
             (spoon/declaration-key method))
          (raw
           "{\nglobal::DripSharp.Runtime.JavaCompat.Assert(() => !global::System.Object.Equals(this.multiple, default!));\nreturn global::DripSharp.Runtime.JavaCompat.Unbox(this.multiple);\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree#empty()"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<T>.ImRrbt<T>(global::System.Array.Empty<T>(), 0, new global::Pkl.Core.Util.Paguro.RrbTree<T>.Leaf<T>(global::System.Array.Empty<T>()), 0);\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree#emptyMutable()"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<T>.MutRrbt<T>(global::System.Array.Empty<T>(), 0, 0, new global::Pkl.Core.Util.Paguro.RrbTree<T>.Leaf<T>(global::System.Array.Empty<T>()), 0);\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree#emptyLeaf()"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<T>.Leaf<T>(global::System.Array.Empty<T>());\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree#genericNodeArray(int)"
             (spoon/declaration-key method))
          (raw "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<T>.Node<T>[size];\n}")

          (= "executable:org.pkl.core.util.paguro.RrbTree$MutRrbt#mt()"
             (spoon/declaration-key method))
          (let [element-name
                (type-parameter-name
                 (first (.getFormalCtTypeParameters owner-type)))
                root-name
                (type-parameter-name
                 (first (.getFormalCtTypeParameters
                         (.getDeclaringType owner-type))))]
            (raw
             (str "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<"
                  root-name ">.MutRrbt<" element-name
                  ">(global::System.Array.Empty<" element-name
                  ">(), 0, 0, new global::Pkl.Core.Util.Paguro.RrbTree<"
                  element-name ">.Leaf<" element-name
                  ">(global::System.Array.Empty<" element-name
                  ">()), 0);\n}")))

          (= "executable:org.pkl.core.util.paguro.RrbTree$ImRrbt#mt()"
             (spoon/declaration-key method))
          (let [element-name
                (type-parameter-name
                 (first (.getFormalCtTypeParameters owner-type)))
                root-name
                (type-parameter-name
                 (first (.getFormalCtTypeParameters
                         (.getDeclaringType owner-type))))]
            (raw
             (str "{\nreturn new global::Pkl.Core.Util.Paguro.RrbTree<"
                  root-name ">.ImRrbt<" element-name
                  ">(global::System.Array.Empty<" element-name
                  ">(), 0, new global::Pkl.Core.Util.Paguro.RrbTree<"
                  element-name ">.Leaf<" element-name
                  ">(global::System.Array.Empty<" element-name
                  ">()), 0);\n}")))

          (= "executable:org.pkl.core.stdlib.base.StringNodes#patternOf(java.lang.String)"
             (spoon/declaration-key method))
          (raw "{\nreturn global::DripSharp.Runtime.JavaCompat.CompileLiteralRegex(regex);\n}")

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
          (and (= :http-send-compatibility signature-adaptation)
               (= "org.pkl.core.http.HttpClient"
                  (.getQualifiedName owner-type)))
          (sequence-node
           [(raw "/* Java HttpClient.send") node (raw "(")
            (sequence-node params ", ")
            (raw ") is supplied by the internal compatibility adapter. */")])

          (and (= :http-proxy-selector-compatibility signature-adaptation)
               (= "org.pkl.core.http.HttpClient$Builder"
                  (.getQualifiedName owner-type)))
          (sequence-node
           [(raw "/* Java ProxySelector configuration ") node (raw "(")
            (sequence-node params ", ")
            (raw ") remains an internal HTTP builder adapter. */")])

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

(declare named-inner-class? source-order-key)

(defn- instance-initializer-blocks [^CtType owner-type]
  (->> (.getTypeMembers owner-type)
       (filter #(and (instance? CtAnonymousExecutable %)
                     (not (.isImplicit ^CtElement %))
                     (not (modifier? % ModifierKind/STATIC))))
       (sort-by source-order-key)
       vec))

(defn- delegates-to-this-constructor? [^CtType owner-type explicit-invocation]
  (and explicit-invocation
       (= (.getQualifiedName owner-type)
          (some-> explicit-invocation .getExecutable .getDeclaringType .getQualifiedName))))

(defn- instance-initializer-nodes [ctx ^CtType owner-type]
  (mapv #(translated-node ctx (.getBody ^CtAnonymousExecutable %))
        (instance-initializer-blocks owner-type)))

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
                              (java-library/explicit-constructor-invocation
                               body-context body))
        initializer (when explicit-invocation
                      (java-body/constructor-initializer
                       body-context explicit-invocation
                       (when outer-type-node (raw "__outer"))))
        instance-initializers
        (when-not (delegates-to-this-constructor? owner-type explicit-invocation)
          (instance-initializer-nodes ctx owner-type))
        constructor-visibility (cap-product-visibility
                                ctx constructor
                                (if (and (modifier? constructor ModifierKind/PRIVATE)
                                         (not (.isTopLevel owner-type)))
                                  "internal"
                                  (visibility constructor "internal")))
        declaration
        (sequence-node
         [(raw (join-words [constructor-visibility]))
          (raw name) node (raw "(") (sequence-node params ", ") (raw ")") initializer
          (constraints-node ctx parameters)
          (if body
            (if (or outer-type-node (seq instance-initializers))
              (sequence-node
               [(raw " {\n")
                (when outer-type-node (raw "this.__outer = __outer;\n"))
                (when (seq instance-initializers)
                  (sequence-node instance-initializers "\n"))
                (translated-node ctx body)
                (raw "\n}")])
              (sequence-node [(raw " ") (translated-node ctx body)]))
            (raw ";"))])]
    (attach-declaration ctx declaration constructor :constructor
                        (.getQualifiedName owner-type) name signature
                        :java.declaration/constructor)))

(defn- field-name [^CtField field]
  (if (public-static-property-field? field)
    (pascal (.getSimpleName field))
    (identifier (.getSimpleName field))))

(def ^:private body-substrate-rules
  #{:dotnet.type/pkl-parser-package
    :pkl-core.type/truffle-substrate
    :pkl-core.type/graal-collections-substrate
    :pkl-core.type/polyglot-substrate
    :pkl-core.type/snakeyaml-substrate})

(defn- body-substrate-reference? [reference]
  (let [owner-name (some-> reference .getDeclaringType .getQualifiedName)
        owner-mapping
        (when owner-name
          (or (get external-type-mappings owner-name)
              (derived-external-type-mapping owner-name)))]
    (contains? body-substrate-rules (second owner-mapping))))

(defn- body-resolved-name
  [ctx occurrence reference]
  (let [declaration (:declaration occurrence)]
    (cond
      (contains? #{:record-component :record-component-accessor}
                 (:resolution occurrence))
      (if-let [component (when (instance? CtRecordComponent declaration)
                           declaration)]
        (record-component-name
         (cast CtType (.getParent ^CtElement component))
         component)
        (if-let [owner (some-> ^CtExecutableReference reference
                               .getDeclaringType
                               .getTypeDeclaration)]
          (record-component-name owner reference)
          (pascal (.getSimpleName ^CtExecutableReference reference))))

      (instance? CtMethod declaration)
      (method-name ctx declaration)

      (instance? CtField declaration)
      (field-name declaration)

      (and
       (= :dependency (:origin occurrence))
       (or
        (str/starts-with?
         (:key occurrence)
         "field:org.organicdesign.fp.tuple.Tuple2#")
        (str/starts-with?
         (:key occurrence)
         "field:org.organicdesign.fp.tuple.Tuple4#")))
      (identifier (.getSimpleName ^CtElement reference))

      (and
       (= :jdk (:origin occurrence))
       (str/starts-with?
        (:key occurrence)
        "field:java.time.temporal.ChronoUnit#"))
      (identifier (.getSimpleName ^CtElement reference))

      (and (= :dependency (:origin occurrence))
           (body-substrate-reference? reference))
      (pascal (.getSimpleName ^CtElement reference))

      (and (= :executable (:kind occurrence))
           (java-body/adapted-invocation-key? (:key occurrence)))
      (identifier (.getSimpleName ^CtElement reference))

      (= :enum-synthetic-method (:resolution occurrence))
      (pascal (.getSimpleName ^CtElement reference))

      (= :project (:origin occurrence))
      (pascal (.getSimpleName ^CtElement reference))

      :else nil)))

(defn- body-resolved-constructor?
  [_ occurrence reference]
  (or
   (= "executable:java.io.UncheckedIOException#<init>(java.io.IOException)"
      (:key occurrence))
   (contains?
    #{"executable:java.net.InetSocketAddress#<init>(java.lang.String,int)"
      "executable:java.net.Proxy#<init>(java.net.Proxy$Type,java.net.SocketAddress)"
      "executable:java.net.ProxySelector#<init>()"
      "executable:java.net.ConnectException#<init>(java.lang.String)"
      "executable:javax.net.ssl.SSLHandshakeException#<init>(java.lang.String)"
      "executable:javax.net.ssl.SSLException#<init>(java.lang.String)"}
    (:key occurrence))
   (and
    (= :dependency (:origin occurrence))
    (or
     (body-substrate-reference? reference)
     (str/starts-with?
      (:key occurrence)
      "executable:org.msgpack.")
     (str/starts-with?
      (:key occurrence)
      "executable:org.organicdesign.fp.tuple.Tuple2#<init>(")
     (str/starts-with?
      (:key occurrence)
      "executable:org.organicdesign.fp.tuple.Tuple4#<init>(")))))

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
        enum-ordinal
        (when enum-value?
          (first
           (keep-indexed
            (fn [index ^CtEnumValue candidate]
              (when (identical? candidate field) index))
            (.getEnumValues ^CtEnum owner-type))))
        name (if enum-value? (identifier (.getSimpleName field)) (field-name field))
        rrb-im-empty?
        (and (= "org.pkl.core.util.paguro.RrbTree$ImRrbt" owner)
             (= "EMPTY_IM_RRBT" (.getSimpleName field)))
        rrb-leaf-empty?
        (and (= "org.pkl.core.util.paguro.RrbTree" owner)
             (= "EMPTY_LEAF" (.getSimpleName field)))
        owner-parameter-name
        (some-> owner-type .getFormalCtTypeParameters first
                type-parameter-name)
        root-parameter-name
        (some-> (first (declaring-types owner-type))
                .getFormalCtTypeParameters first
                type-parameter-name)
        initializer (.getDefaultExpression field)
        initializer-node (when initializer (translated-node ctx initializer))
        initializer-node
        (if (and initializer-node
                 (= "char" (some-> field .getType .getQualifiedName))
                 (not= "char" (some-> initializer .getType .getQualifiedName)))
          (sequence-node
           [(raw "unchecked((char)(") initializer-node (raw "))")])
          initializer-node)
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
        initializer-node
        (cond
          rrb-im-empty?
          (raw
           (str "new global::Pkl.Core.Util.Paguro.RrbTree<"
                root-parameter-name ">.ImRrbt<" owner-parameter-name
                ">(global::System.Array.Empty<" owner-parameter-name
                ">(), 0, new global::Pkl.Core.Util.Paguro.RrbTree<"
                owner-parameter-name ">.Leaf<" owner-parameter-name
                ">(global::System.Array.Empty<" owner-parameter-name
                ">()), 0)"))

          rrb-leaf-empty?
          (raw
           (str "new global::Pkl.Core.Util.Paguro.RrbTree<"
                root-parameter-name ">.Leaf<" root-parameter-name
                ">(global::System.Array.Empty<" root-parameter-name ">())"))

          :else initializer-node)
        initializer-node
        (if (and initializer-node
                 (= "java.util.Comparator"
                    (some-> field .getType .getQualifiedName)))
          (java-body/value-adapter
           {:destination-context ctx
            :kind :assignment
            :source initializer
            :target-reference (.getType field)
            :target field
            :node initializer-node})
          initializer-node)
        field-type-node
        (cond
          rrb-im-empty?
          (raw
           (str "global::Pkl.Core.Util.Paguro.RrbTree<"
                root-parameter-name ">.ImRrbt<" owner-parameter-name ">"))

          rrb-leaf-empty?
          (raw
           (str "global::Pkl.Core.Util.Paguro.RrbTree<"
                root-parameter-name ">.Leaf<" root-parameter-name ">"))

          :else
          (type-node ctx (.getType field)))
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
        field-visibility (cap-product-visibility ctx field field-visibility)
        property? (public-static-property-field? field)
        words [field-visibility
               (when (or enum-value? (modifier? field ModifierKind/STATIC)) "static")
               (when (and (not property?)
                          (or enum-value? (modifier? field ModifierKind/FINAL))) "readonly")
               (when (modifier? field ModifierKind/VOLATILE) "volatile")]
        declaration
        (sequence-node
         [(when enum-value?
            (sequence-node
             [(raw
               (str "[global::DripSharp.Runtime.JavaEnumNameAttribute(\""
                    (.getSimpleName field)
                    "\")]\n"))
              (raw
               (str "[global::DripSharp.Runtime.JavaEnumOrdinalAttribute("
                    enum-ordinal
                    ")]\n"))]))
          (raw (join-words words)) field-type-node
          (raw (str " " name))
          (when property? (raw " { get; }"))
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
        signature-ctx (assoc ctx :force-product-signature?
                             (exported-product-type? ctx owner-type))
        node (sequence-node [(type-node signature-ctx (.getType component))
                             (raw (str " " name))])]
    (attach-declaration ctx node component :record-component owner name nil
                        :java.declaration/record-component)))

(defn- record-component-property-node [ctx ^CtType owner-type ^CtRecordComponent component]
  (let [owner (.getQualifiedName owner-type)
        name (record-component-name owner-type component)
        signature-ctx (assoc ctx :force-product-signature?
                             (exported-product-type? ctx owner-type))
        node (sequence-node [(raw "public ") (type-node signature-ctx (.getType component))
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
          capture-bindings
          (mapv (fn [declaration name]
                  {:declaration declaration :name name})
                capture-declarations
                capture-names)
          body-context
          (assoc
           (java-library/create-body-context
            (:resolved-model ctx)
            (:ctx-holder ctx)
            (:runtime-capabilities ctx))
           :services services
           :capture-bindings capture-bindings)]
      {:ctx (assoc ctx :body-context body-context
                   :services services
                   :capture-bindings capture-bindings)
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
                     (raw ";")])))
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
      :else nil)))

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
    (if (= :long element)
      [(raw "public long Next() => this.NextLong();")]
      [(sequence-node
        [(raw "private ") element-node (raw " __iteratorCurrent = default!;\n")
         (raw "public ") element-node (raw " Current => this.__iteratorCurrent;\n")
         (raw "object global::System.Collections.IEnumerator.Current => this.__iteratorCurrent!;\n")
         (raw "public bool MoveNext() { if (!this.HasNext()) return false; this.__iteratorCurrent = this.Next(); return true; }\n")
         (raw "public void Reset() => throw new global::System.NotSupportedException();\n")
         (raw "public void Dispose() { }")])])))

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
          iterator-method (some #(when (and (instance? CtMethod %)
                                            (= "iterator" (.getSimpleName ^CtMethod %))
                                            (empty? (.getParameters ^CtMethod %)))
                                   %)
                                (.getTypeMembers type))
          direct-enumerator?
          (= :nullable-object-enumerator
             (when iterator-method
               (method-signature-adaptation ctx iterator-method)))]
      [(sequence-node
        [(when (and (instance? CtClass type)
                    (modifier? type ModifierKind/ABSTRACT)
                    (not iterator-method))
           (sequence-node [(raw "public abstract global::DripSharp.Runtime.JavaIterator<")
                           element-node (raw "> Iterator();\n")]))
         (raw "public global::System.Collections.Generic.IEnumerator<")
         element-node
         (raw (if direct-enumerator?
                "> GetEnumerator() => this.Iterator();\n"
                "> GetEnumerator() => global::DripSharp.Runtime.JavaCompat.AsEnumerator(this.Iterator());\n"))
         (raw "global::System.Collections.IEnumerator global::System.Collections.IEnumerable.GetEnumerator() => this.GetEnumerator();")])])))

(defn- collection-iterable-bridge-members [ctx ^CtType type]
  (when-let [element (collection-iterable-element-reference type)]
    (let [element-node (case element
                         :object (raw "object")
                         (type-node ctx element))]
      [(sequence-node
        [(raw "public global::System.Collections.Generic.IEnumerator<")
         element-node
         (raw "> GetEnumerator() => global::DripSharp.Runtime.JavaCompat.AsEnumerator(this.Iterator());")])])))

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
           "public global::System.Collections.Generic.IEnumerator<E> GetEnumerator() => global::DripSharp.Runtime.JavaCompat.AsEnumerator(this.Iterator());\n"
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
                     "global::DripSharp.Runtime.JavaCompat.EnumValueOf<" name ">(name);\n"
                     "public static " name "[] Values() => "
                     "global::DripSharp.Runtime.JavaCompat.EnumValues<" name ">();"))]))
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
        (let [element-name
              (type-parameter-name
               (first (.getFormalCtTypeParameters type)))]
          [(raw
            (str
             "public new global::Pkl.Core.Util.Paguro.RrbTree<" element-name
             ">.ImRrbt<" element-name
             "> SubList(int fromIndex, int toIndex) { var result = global::Pkl.Core.Util.Paguro.RrbTree<"
             element-name ">.Empty<" element-name
             ">(); foreach (var value in global::System.Linq.Enumerable.Take(global::System.Linq.Enumerable.Skip(this, fromIndex), toIndex - fromIndex)) result = result.Append(value); return result; }\n"
             "public global::Pkl.Core.Util.Paguro.RrbTree<" element-name
             ">.ImRrbt<" element-name
             "> Reverse() { var result = global::Pkl.Core.Util.Paguro.RrbTree<"
             element-name ">.Empty<" element-name
             ">(); foreach (var value in global::System.Linq.Enumerable.Reverse(this)) result = result.Append(value); return result; }"))]))
      (when (or (= "org.pkl.core.util.paguro.RrbTree$Relaxed" (.getQualifiedName type))
                (= "org.pkl.core.util.paguro.RrbTree.Relaxed" (.getQualifiedName type))
                (and (= "Relaxed" (.getSimpleName type))
                     (= "RrbTree" (some-> type .getDeclaringType .getSimpleName))))
        [(raw "internal static int[] MakeSizeArray<TItem>(global::Pkl.Core.Util.Paguro.RrbTree<TItem>.Node<TItem>[] newNodes) { var result = new int[newNodes.Length]; var total = 0; for (var i = 0; i < newNodes.Length; i++) { total += newNodes[i].Size(); result[i] = total; } return result; }")])
      (iterator-bridge-members ctx type)
      (iterable-bridge-members ctx type)
      (collection-iterable-bridge-members ctx type)
      (rrb-tree-list-bridge-members type)))))

(defn- member-node [ctx ^CtType owner member]
  (let [member-ctx (assoc ctx :current-member member)]
    (cond
      (instance? CtEnumValue member) (field-node member-ctx owner member)
      (instance? CtField member) (field-node member-ctx owner member)
      (instance? CtMethod member) (method-node member-ctx owner member)
      (instance? CtConstructor member) (constructor-node member-ctx owner member)
      (instance? CtType member)
      (type-node-declaration (dissoc member-ctx :current-member) member)
      (instance? CtAnonymousExecutable member)
      (if (modifier? member ModifierKind/STATIC)
        (let [name (pascal (.getSimpleName owner))
              signature ".cctor()"
              declaration
              (sequence-node
               [(raw (str "static " name "() "))
                (translated-node member-ctx
                                 (.getBody ^CtAnonymousExecutable member))])]
          (attach-declaration member-ctx declaration member :initializer
                              (.getQualifiedName owner) name signature
                              :java.declaration/static-initializer))
        (attach-declaration
         member-ctx
         (raw "/* Java instance initializer is emitted in each non-delegating constructor. */")
         member :initializer (.getQualifiedName owner)
         (pascal (.getSimpleName owner))
         (str ".iinit@" (:line (spoon/source-location member)) ":"
              (:column (spoon/source-location member)))
         :java.declaration/instance-initializer))
      :else
      (throw
       (ex-info (str "Unsupported live Spoon type member "
                     (.getName (class member)))
                {:kind :unsupported-declaration-member
                 :owner (.getQualifiedName owner)
                 :source (source-ref member :java.declaration/member)})))))

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

(defn- type-words [ctx ^CtType type]
  (let [;; Java allows a public nested class to extend a less-visible sibling.
        ;; C# exposes the base type in the derived type's metadata and rejects
        ;; that shape. Promote the selected base declaration to the visibility
        ;; already exposed by its public subtype.
        declaring-type (.getDeclaringType type)
        visibility (cond
                     (not (exported-product-type? ctx type))
                     "internal"

                     ;; MessagePack transport is a user-approved exclusion.
                     ;; The transport remains assembly-internal implementation
                     ;; detail. Public external-reader contracts are independent
                     ;; of that excluded wire format and stay publicly visible.
                     (and (str/starts-with? (.getQualifiedName type)
                                            "org.pkl.core.messaging.")
                          (not (public-messaging-contract?
                                (.getQualifiedName type))))
                     "internal"
                     (public-nested-subtype? type) "public"
                     (and declaring-type
                          (modifier? type ModifierKind/PROTECTED)
                          (modifier? declaring-type ModifierKind/FINAL))
                     "internal"
                     :else (visibility type "internal"))]
    (cond
      (= "org.pkl.core.StackFrameTransformers" (.getQualifiedName type))
      [visibility "static" "partial" "class"]

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
        stack-frame-transformer? (= "org.pkl.core.StackFrameTransformer" qualified)
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
        bases (->> (base-types type)
                   (remove #(and (exported-product-type? ctx type)
                                 (= "org.pkl.core.runtime.ReaderBase"
                                    (.getQualifiedName ^CtTypeReference %))))
                   (mapv #(type-node (assoc member-ctx :base-clause? true) %)))
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
        members (if (or functional-method stack-frame-transformer?)
                  []
                  (if-let [emit-members (:emit-members ctx)]
                    (emit-members ctx type selected-members)
                    (mapv #(member-node member-ctx type %) selected-members)))
        members (if explicit-record-constructor?
                  (into (mapv #(record-component-property-node ctx type %)
                              (.getRecordComponents ^CtRecord type))
                        members)
                  members)
        explicit-selected-constructor?
        (some #(and (instance? CtConstructor %)
                    (not (.isImplicit ^CtElement %))
                    (selected-declaration? ctx %))
              raw-members)
        instance-initializers (instance-initializer-blocks type)
        implicit-constructor
        (when (and (not explicit-selected-constructor?)
                   (or outer-capture-type-node (seq instance-initializers)))
          (sequence-node
           [(raw (if outer-capture-type-node "internal "
                     (str (visibility type "internal") " ")))
            (raw name) (raw "(")
            (when outer-capture-type-node
              (sequence-node [outer-capture-type-node (raw " __outer")]))
            (raw ") {\n")
            (when outer-capture-type-node (raw "this.__outer = __outer;\n"))
            (when (seq instance-initializers)
              (sequence-node (instance-initializer-nodes member-ctx type) "\n"))
            (raw "\n}")]))
        members (into (vec (remove nil? [outer-capture implicit-constructor])) members)
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
                         (raw "global::DripSharp.Runtime.JavaCompat.Equals")
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
              (csharp/invocation (raw "global::DripSharp.Runtime.JavaCompat.Hash") values)
              (raw ";\n}")])))
        members (cond-> members record-value-semantics (conj record-value-semantics))
        enum-to-string
        (when (and (instance? CtEnum type) (not explicit-enum-to-string?))
          (raw "public override string ToString() {\nreturn global::DripSharp.Runtime.JavaCompat.EnumName(this);\n}"))
        members (cond-> members enum-to-string (conj enum-to-string))
        header (sequence-node
                (remove nil?
                        [(node-info-attribute type)
                         (raw (join-words (type-words ctx type))) (raw name) node
                         (when components
                           (sequence-node [(raw "(") (sequence-node components ", ") (raw ")")]))
                         (when (seq bases)
                           (sequence-node [(raw " : ") (sequence-node bases ", ")]))
                         (constraints-node ctx parameters)]))
        declaration
        (cond
          stack-frame-transformer?
          (let [^CtMethod and-then
                (some #(when (and (instance? CtMethod %)
                                  (= "andThen" (.getSimpleName ^CtMethod %)))
                         %)
                      selected-members)
                _ (when-not and-then
                    (throw (ex-info "StackFrameTransformer composition contract is missing"
                                    {:kind :missing-stack-frame-composition-contract})))
                extension
                (attach-declaration
                 ctx
                 (raw (str
                       "public static class StackFrameTransformerExtensions\n"
                       "{\n"
                       "public static StackFrameTransformer AndThen(\n"
                       "    this StackFrameTransformer transformer,\n"
                       "    StackFrameTransformer next)\n"
                       "{\n"
                       "global::System.ArgumentNullException.ThrowIfNull(transformer);\n"
                       "global::System.ArgumentNullException.ThrowIfNull(next);\n"
                       "return frame => next(transformer(frame));\n"
                       "}\n"
                       "}"))
                 and-then :method qualified "AndThen" (.getSignature and-then)
                 :dotnet.declaration/stack-frame-transformer-extension)]
            (sequence-node
             [(raw "public delegate global::Pkl.Core.StackFrame StackFrameTransformer(global::Pkl.Core.StackFrame frame);\n\n")
              extension]))

          functional-method
          (let [method-owner (executable-owner functional-method)
                method-name (method-name ctx functional-method)
                signature (.getSignature functional-method)
                params (mapv #(parameter-node ctx method-owner %)
                             (.getParameters functional-method))
                delegate-node
                (sequence-node
                 [(raw (join-words [(if (exported-product-type? ctx type)
                                      (visibility type "internal")
                                      "internal")
                                    "delegate"]))
                  (type-node ctx (.getType functional-method))
                  (raw (str " " name)) node
                  (raw "(") (sequence-node params ", ") (raw ")")
                  (constraints-node ctx parameters)
                  (raw ";")])]
            (attach-declaration ctx delegate-node functional-method :method
                                qualified method-name signature
                                :dotnet.declaration/functional-interface-method))
          :else
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

(defn- emission-template
  [resolved-model resolved-mappings]
  (let [shared-type-node (:type-node resolved-mappings)
        resolved-type-policy (:type-policy resolved-mappings)
        runtime-capabilities (:runtime-capabilities resolved-mappings)
        destination-type-node
        (fn [ctx reference]
          (shared-type-node
           (assoc ctx :resolved-type-policy resolved-type-policy)
           reference))
        ctx-holder (atom nil)
        top-definitions-cache (IdentityHashMap.)
        base-services {:identifier identifier
                       :pascal pascal
                       :type-parameter-name type-parameter-name
                       :method-name (fn [method] (method-name @ctx-holder method))
                       :current-signature-adaptation
                       (fn [] (:signature-adaptation @ctx-holder))
                       :current-product-return-reference
                       (fn [] (:product-return-reference @ctx-holder))
                       :product-boundary?
                       (fn [] (some? (:public-api-declaration-keys @ctx-holder)))
                       :method-signature-adaptation
                       (fn [method]
                         (when (instance? CtMethod method)
                           (method-signature-adaptation @ctx-holder method)))
                       :exported-product-declaration?
                       (fn [declaration]
                         (exported-product-declaration? @ctx-holder declaration))
                       :product-signature-collection-adaptation
                       (fn [reference]
                         (when (instance? CtTypeReference reference)
                           (product-signature-collection-adaptation
                            @ctx-holder reference)))
                       :read-only-product-type-node
                       (fn [reference]
                         (destination-type-node
                          (assoc @ctx-holder :force-product-signature? true)
                          reference))
                       :mutable-product-type-node
                       (fn [reference]
                         (destination-type-node
                          (assoc @ctx-holder :suppress-product-signature? true)
                          reference))
                       :anonymous-class-name anonymous-class-name
                       :record-component-name record-component-name
                       :local-name (fn [^CtElement element]
                                     (let [{:keys [line column]}
                                           (spoon/source-location element)]
                                       (str (identifier
                                             (.getSimpleName
                                              ^spoon.reflect.declaration.CtNamedElement element))
                                            "__" (or line 0) "_" (or column 0))))
                       :type-node
                       (fn [reference]
                         (destination-type-node @ctx-holder reference))}
        base-services (assoc base-services :record-component-contract?
                             #(record-component-contract? @ctx-holder %))
        base-services
        (assoc
         base-services
         :functional-reference-delegate?
         (fn [^CtTypeReference reference]
           (contains?
            #{"org.pkl.core.StackFrameTransformer"
              "org.pkl.core.stdlib.VmObjectFactory$Property"}
            (.getQualifiedName reference)))
         :functional-interface-method?
         (fn [^CtMethod method]
           (when-let [^CtMethod functional
                      (some-> method .getDeclaringType
                              (#(functional-interface-method @ctx-holder %)))]
             (and (= (.getSignature method) (.getSignature functional))
                  (= (some-> method .getDeclaringType .getQualifiedName)
                     (some-> functional .getDeclaringType
                             .getQualifiedName))))))
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
        body-context
        (assoc
         ((:create-body-context resolved-mappings)
          resolved-model ctx-holder runtime-capabilities)
         :services services)]
    {:ctx-holder ctx-holder
     :top-definitions-cache top-definitions-cache
     :services services
     :body-context body-context
     :runtime-capabilities runtime-capabilities
     :resolved-type-policy resolved-type-policy
     :structural-declaration-policy
     {:emit-root-node type-node-declaration
      :translate-member
      (fn [ctx owner member]
        (let [member-ctx (type-body-context
                          (assoc ctx :current-type owner) owner)]
          (member-node member-ctx owner member)))}}))

(defn- root-emission-context
  [{:keys [template configuration resolved-model occurrence-index
           selected-declarations public-api-type-keys
           public-api-declaration-keys blocker-start emit-members]}]
  (let [ctx (cond-> {:configuration configuration
                     :resolved-model resolved-model
                     :ctx-holder (:ctx-holder template)
                     :top-definitions-cache (:top-definitions-cache template)
                     :occurrence-index occurrence-index
                     :selected-declarations selected-declarations
                     :public-api-type-keys public-api-type-keys
                     :public-api-declaration-keys public-api-declaration-keys
                     :emitted (IdentityHashMap.)
                     :declarations (atom [])
                     :diagnostics (atom [])
                     :blocker-counter (atom blocker-start)
                     :body-translations (atom [])
                     :body-context (:body-context template)
                     :runtime-capabilities (:runtime-capabilities template)
                     :resolved-type-policy (:resolved-type-policy template)
                     :destination-nonnull-boxed-by-default? true
                     :destination-resolved-name body-resolved-name
                     :destination-resolved-constructor?
                     body-resolved-constructor?
                     :destination-constructor-adapter
                     java-body/constructor-adapter
                     :destination-field-adaptations
                     java-body/field-adaptations
                     :destination-invocation-adapter
                     java-body/invocation-adapter
                     :destination-method-reference?
                     (fn [{:keys [reference]}]
                       (body-substrate-reference? reference))
                     :destination-value-adapter java-body/value-adapter
                     :destination-binary-adapter java-body/binary-adapter
                     :structural-declaration-policy
                     (:structural-declaration-policy template)
                     :services (:services template)}
              emit-members (assoc :emit-members emit-members))]
    (reset! (:ctx-holder template) ctx)
    ctx))

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
  (swap! (:body-translations target) into @(:body-translations source))
  ;; A single-worker job can replace the shared holder while translating a
  ;; member. Restore the owning context before its root continues rendering.
  (reset! (:ctx-holder target) target))

(defn- context-results [ctx]
  {:declarations @(:declarations ctx)
   :diagnostics @(:diagnostics ctx)
   :body-translations @(:body-translations ctx)})

(defn- fail! [message data]
  (throw (ex-info message data)))

(def ^:private digest-file util/sha256-file)

(defn- core-destination? [configuration]
  (= "Pkl.Core" (get-in configuration [:package :id])))

(defn- validate-core-legal-configuration! [configuration]
  (when (core-destination? configuration)
    (when-not (= core-legal-files (:legal-files configuration))
      (fail! "Pkl.Core legal files differ from the pinned package contract"
             {:kind :invalid-pkl-core-legal-configuration
              :expected core-legal-files
              :actual (:legal-files configuration)}))
    (when-not (= notice-appendix (:notice-appendix configuration))
      (fail! "Pkl.Core NOTICE appendix differs from the translation contract"
             {:kind :invalid-pkl-core-notice-appendix
              :expected notice-appendix
              :actual (:notice-appendix configuration)}))
    (when (get-in configuration [:package :license-expression])
      (fail! "Pkl.Core must use its packed license file instead of a license expression"
             {:kind :conflicting-pkl-core-license-metadata
              :license-expression
              (get-in configuration [:package :license-expression])})))
  configuration)

(defn- validate-mechanical-source! [configuration]
  (when-not (= mechanical-source (:mechanical-source configuration))
    (fail! "Pkl mechanical-source provenance differs from its pinned source contract"
           {:kind :invalid-pkl-mechanical-source
            :expected mechanical-source
            :actual (:mechanical-source configuration)}))
  configuration)

(defn- validate-configuration! [configuration]
  (-> configuration
      project-emission/validate-configuration!
      validate-mechanical-source!
      validate-core-legal-configuration!))

(defn- validate-legal-inputs! [workspace-root configuration]
  (when (core-destination? configuration)
    (validate-core-legal-configuration! configuration)
    (doseq [{:keys [kind source source-sha256]}
            (:legal-files configuration)]
      (let [file (paths/resolve-path (paths/absolute workspace-root) source)]
        (when-not (paths/regular-file? file)
          (fail! "Configured Pkl.Core license or notice input is missing"
                 {:kind :missing-pkl-core-legal-input
                  :legal-kind kind
                  :path (str file)}))
        (let [actual (digest-file file)]
          (when-not (= source-sha256 actual)
            (fail! "Configured Pkl.Core license or notice input changed"
                   {:kind :pkl-core-legal-input-mismatch
                    :legal-kind kind
                    :path (str file)
                    :expected source-sha256
                    :actual actual}))))))
  configuration)

(defn- validate-profile! [{:keys [workspace-root profile configuration]}]
  (let [configuration (validate-configuration! configuration)]
    (if-not (core-destination? configuration)
      configuration
      (do
        (let [expected {:profile core-profile
                        :product-family :pkl
                        :project-root "research/pkl"
                        :revision source-revision
                        :gradle-project ":pkl-core"}
              actual (select-keys profile (keys expected))]
          (when-not (= expected actual)
            (fail! "Pkl.Core generation profile differs from its pinned source contract"
                   {:kind :invalid-pkl-core-profile
                    :expected expected
                    :actual actual})))
        (validate-legal-inputs! workspace-root configuration)
        configuration))))

(defn- validate-project-input!
  [{:keys [workspace-root profile project-input]}]
  (baseline/validate-project-input!
   workspace-root :pkl (:profile profile) project-input))

(defn- legal-assets [{:keys [workspace-root configuration]}]
  (if-not (core-destination? configuration)
    []
    (do
      (validate-legal-inputs! workspace-root configuration)
      (mapv
       (fn [{:keys [kind source destination]}]
         (let [base {:source source
                     :destination destination
                     :strategy (keyword "pkl-core.legal" (name kind))
                     :missing-kind :missing-pkl-core-legal-input
                     :missing-message
                     "Configured Pkl.Core license or notice input is missing"}]
           (if (= :notice kind)
             (let [upstream (Files/readString
                             (paths/resolve-path
                              (paths/absolute workspace-root)
                              source))]
               (assoc base
                      :text-replacements
                      {upstream (str upstream (:notice-appendix configuration))}))
             base)))
       (:legal-files configuration)))))

(defn- product-runtime-assets
  [{:keys [configuration] :as context}]
  (into
   (mapv
    (fn [relative]
      {:source relative
       :destination (str "Pkl/Core/Runtime/Substrate/"
                         (.getFileName (paths/path relative)))
       :strategy :reviewable-product-runtime-source
       :missing-kind :missing-runtime-source
       :missing-message "Configured runtime source is missing"})
    (:runtime-sources configuration))
   (legal-assets context)))

(defn rule-bundle
  "Returns the Pkl destination policy composed over the shared Java-library
  bundle contract. Pkl-specific declaration and type-shape policies preserve
  the product while ordinary body translation and standard-library mappings
  are inherited from the shared bundle."
  []
  (let [base (java-library/rule-bundle)]
    (-> base
        (assoc :id :pkl
               :product-family :pkl
               :orchestration
               {:validate-profile! validate-profile!
                :validate-project-input! validate-project-input!})
        (update-in
         [:rules :structural-declarations]
         assoc
         :create-template emission-template
         :create-context root-emission-context
         :merge-context! merge-emission-context!
         :context-results context-results)
        (update-in [:rules :resolved-mappings]
                   assoc
                   :type-policy resolved-type-policy
                   :annotation-decisions annotation-decisions
                   :declarative-mapping-required?
                   declarative-mapping-required?)
        (assoc-in [:rules :project-policy]
                  (assoc project-emission/common-project-policy
                         :validate-configuration! validate-configuration!))
        (assoc-in [:rules :product-runtime-assets :assets]
                  product-runtime-assets))))
