(ns vibeformer.java-types
  "Product-neutral Java type mappings used by ordinary destination bundles.

  Entries in this registry describe only reusable Java/JDK contracts with a
  direct .NET representation or an existing generic Vibeformer compatibility
  type. Product identities and destination-library package names do not belong
  here. Resolved occurrence identity remains the caller's dispatch key."
  (:require [clojure.string :as str]))

(def ^:private mappings
  {"void" ["void" :dotnet.type/void]
   "boolean" ["bool" :dotnet.type/boolean]
   "byte" ["sbyte" :dotnet.type/sbyte]
   "short" ["short" :dotnet.type/int16]
   "int" ["int" :dotnet.type/int32]
   "long" ["long" :dotnet.type/int64]
   "char" ["char" :dotnet.type/char]
   "float" ["float" :dotnet.type/single]
   "double" ["double" :dotnet.type/double]

   "java.lang.Object" ["object" :dotnet.type/object]
   "java.lang.String" ["string" :dotnet.type/string]
   "java.lang.CharSequence" ["string" :dotnet.type/string]
   "java.lang.Boolean" ["bool" :dotnet.type/boolean]
   "java.lang.Byte" ["sbyte" :dotnet.type/sbyte]
   "java.lang.Short" ["short" :dotnet.type/int16]
   "java.lang.Integer" ["int" :dotnet.type/int32]
   "java.lang.Long" ["long" :dotnet.type/int64]
   "java.lang.Character" ["char" :dotnet.type/char]
   "java.lang.Float" ["float" :dotnet.type/single]
   "java.lang.Double" ["double" :dotnet.type/double]
   "java.lang.Number" ["global::System.IConvertible" :dotnet.type/number]
   "java.lang.Void" ["object" :dotnet.type/void-marker]
   "java.lang.Class" ["global::System.Type" :dotnet.type/type]
   "java.lang.Enum" ["object" :dotnet.type/enum-base]
   "java.lang.Throwable" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.Exception" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.RuntimeException" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.IllegalArgumentException" ["global::System.ArgumentException"
                                         :dotnet.type/argument-exception]
   "java.lang.IllegalStateException" ["global::System.InvalidOperationException"
                                      :dotnet.type/invalid-operation]
   "java.lang.IndexOutOfBoundsException" ["global::System.ArgumentOutOfRangeException"
                                          :dotnet.type/argument-out-of-range]
   "java.lang.ClassCastException" ["global::System.InvalidCastException"
                                   :dotnet.type/invalid-cast]
   "java.lang.NullPointerException" ["global::System.NullReferenceException"
                                     :dotnet.type/null-reference]
   "java.lang.UnsupportedOperationException" ["global::System.NotSupportedException"
                                              :dotnet.type/not-supported]
   "java.lang.NumberFormatException" ["global::System.FormatException"
                                      :dotnet.type/format-exception]
   "java.lang.StringBuilder" ["global::System.Text.StringBuilder"
                              :dotnet.type/string-builder]
   "java.lang.Math" ["global::System.Math" :dotnet.type/math]
   "java.lang.System" ["global::Vibeformer.Runtime.JavaCompat"
                       :dotnet.type/java-compat]
   "java.lang.Iterable" ["global::System.Collections.Generic.IEnumerable"
                         :dotnet.type/enumerable]
   "java.lang.AutoCloseable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.lang.Comparable" ["global::System.IComparable" :dotnet.type/comparable]

   "java.io.Closeable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.io.IOException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.io.InterruptedIOException" ["global::System.IO.IOException"
                                     :dotnet.type/io-exception]
   "java.io.UnsupportedEncodingException" ["global::System.ArgumentException"
                                           :dotnet.type/argument-exception]
   "java.io.InputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.OutputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.FilterOutputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.BufferedInputStream" ["global::System.IO.BufferedStream"
                                  :dotnet.type/buffered-stream]
   "java.io.ByteArrayInputStream" ["global::System.IO.MemoryStream"
                                   :dotnet.type/memory-stream]
   "java.io.ByteArrayOutputStream" ["global::System.IO.MemoryStream"
                                    :dotnet.type/memory-stream]
   "java.io.PipedInputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.PipedOutputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.PushbackInputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.File" ["string" :dotnet.type/path]

   "java.net.URI" ["global::System.Uri" :dotnet.type/uri]
   "java.net.URL" ["global::System.Uri" :dotnet.type/uri]
   "java.net.URISyntaxException" ["global::System.UriFormatException"
                                  :dotnet.type/uri-format-exception]
   "java.net.InetAddress" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.InetSocketAddress" ["global::System.Net.IPEndPoint"
                                 :dotnet.type/ip-endpoint]
   "java.net.Socket" ["global::System.Net.Sockets.Socket" :dotnet.type/socket]
   "java.net.ServerSocket" ["global::System.Net.Sockets.TcpListener"
                            :dotnet.type/tcp-listener]
   "java.net.SocketException" ["global::System.Net.Sockets.SocketException"
                               :dotnet.type/socket-exception]
   "java.net.SocketTimeoutException" ["global::System.TimeoutException"
                                      :dotnet.type/timeout-exception]

   "java.nio.charset.Charset" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.nio.charset.StandardCharsets" ["global::Vibeformer.Runtime.JavaStandardCharsets"
                                        :dotnet.type/standard-charsets]
   "java.nio.file.Files" ["global::Vibeformer.Runtime.JavaCompat"
                          :dotnet.type/java-compat]

   "java.time.Duration" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.Instant" ["global::System.DateTimeOffset" :dotnet.type/date-time-offset]
   "java.time.ZoneOffset" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.ZonedDateTime" ["global::System.DateTimeOffset"
                              :dotnet.type/date-time-offset]

   "java.util.AbstractMap" ["global::System.Collections.Generic.IDictionary"
                            :dotnet.type/map-interface]
   "java.util.ArrayList" ["global::System.Collections.Generic.List"
                          :dotnet.type/list]
   "java.util.Arrays" ["global::Vibeformer.Runtime.JavaCompat"
                       :dotnet.type/java-compat]
   "java.util.Collection" ["global::System.Collections.Generic.ICollection"
                           :dotnet.type/collection]
   "java.util.Collections" ["global::Vibeformer.Runtime.JavaCompat"
                            :dotnet.type/java-compat]
   "java.util.HashMap" ["global::System.Collections.Generic.Dictionary"
                        :dotnet.type/dictionary]
   "java.util.HashSet" ["global::System.Collections.Generic.HashSet"
                        :dotnet.type/hash-set]
   "java.util.Iterator" ["global::System.Collections.Generic.IEnumerator"
                         :dotnet.type/enumerator]
   "java.util.LinkedHashMap" ["global::Vibeformer.Runtime.JavaLinkedHashMap"
                              :dotnet.type/linked-dictionary]
   "java.util.LinkedHashSet" ["global::System.Collections.Generic.HashSet"
                              :dotnet.type/linked-hash-set]
   "java.util.List" ["global::System.Collections.Generic.IList"
                     :dotnet.type/list-interface]
   "java.util.Map" ["global::System.Collections.Generic.IDictionary"
                    :dotnet.type/map-interface]
   "java.util.Map$Entry" ["global::Vibeformer.Runtime.JavaMapEntry"
                          :dotnet.type/map-entry]
   "java.util.NoSuchElementException" ["global::System.InvalidOperationException"
                                       :dotnet.type/invalid-operation]
   "java.util.Objects" ["global::Vibeformer.Runtime.JavaCompat"
                        :dotnet.type/java-compat]
   "java.util.Optional" ["global::Vibeformer.Runtime.JavaOptional"
                         :dotnet.type/optional]
   "java.util.OptionalInt" ["int?" :dotnet.type/nullable-int]
   "java.util.OptionalLong" ["long?" :dotnet.type/nullable-long]
   "java.util.ServiceLoader" ["global::System.Collections.Generic.IEnumerable"
                              :dotnet.type/service-loader]
   "java.util.Set" ["global::System.Collections.Generic.ISet"
                    :dotnet.type/set-interface]

   "java.util.concurrent.Callable" ["global::System.Func" :dotnet.type/func]
   "java.util.concurrent.ExecutionException" ["global::System.AggregateException"
                                              :dotnet.type/execution-exception]
   "java.util.concurrent.Executors" ["global::Vibeformer.Runtime.JavaCompat"
                                     :dotnet.type/java-compat]
   "java.util.concurrent.Future" ["global::Vibeformer.Runtime.JavaFuture"
                                  :dotnet.type/future]
   "java.util.concurrent.TimeUnit" ["global::Vibeformer.Runtime.JavaTimeUnit"
                                    :dotnet.type/time-unit]
   "java.util.concurrent.TimeoutException" ["global::System.TimeoutException"
                                            :dotnet.type/timeout-exception]
   "java.util.function.BiConsumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.BiFunction" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Consumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.Supplier" ["global::System.Func" :dotnet.type/func]
   "java.util.regex.Pattern" ["global::System.Text.RegularExpressions.Regex"
                              :dotnet.type/regex]
   "java.util.stream.Stream" ["global::System.Collections.Generic.IEnumerable"
                              :dotnet.type/enumerable]

   "java.util.zip.GZIPInputStream" ["global::System.IO.Compression.GZipStream"
                                    :dotnet.type/gzip-stream]
   "java.util.zip.InflaterOutputStream" ["global::System.IO.Compression.DeflateStream"
                                         :dotnet.type/deflate-stream]

   "java.security.KeyManagementException" ["global::System.Security.Cryptography.CryptographicException"
                                           :dotnet.type/cryptographic-exception]
   "java.security.KeyStoreException" ["global::System.Security.Cryptography.CryptographicException"
                                      :dotnet.type/cryptographic-exception]
   "java.security.NoSuchAlgorithmException" ["global::System.Security.Cryptography.CryptographicException"
                                             :dotnet.type/cryptographic-exception]
   "java.security.UnrecoverableKeyException" ["global::System.Security.Cryptography.CryptographicException"
                                              :dotnet.type/cryptographic-exception]
   "java.security.KeyStore" ["global::System.Security.Cryptography.X509Certificates.X509Certificate2Collection"
                             :dotnet.type/certificate-collection]
   "java.security.cert.CertificateException" ["global::System.Security.Cryptography.CryptographicException"
                                              :dotnet.type/cryptographic-exception]
   "java.security.cert.X509Certificate" ["global::System.Security.Cryptography.X509Certificates.X509Certificate2"
                                         :dotnet.type/x509-certificate]

   "javax.net.ssl.KeyManager" ["object" :dotnet.type/key-manager]
   "javax.net.ssl.KeyManagerFactory" ["object" :dotnet.type/key-manager-factory]
   "javax.net.ssl.SSLContext" ["global::System.Net.Security.SslStream"
                               :dotnet.type/ssl-context]
   "javax.net.ssl.SSLSocket" ["global::System.Net.Sockets.Socket"
                              :dotnet.type/ssl-socket]
   "javax.net.ssl.SSLSocketFactory" ["object" :dotnet.type/ssl-socket-factory]
   "javax.net.ssl.TrustManager" ["object" :dotnet.type/trust-manager]
   "javax.net.ssl.TrustManagerFactory" ["object" :dotnet.type/trust-manager-factory]
   "javax.net.ssl.X509TrustManager" ["object" :dotnet.type/x509-trust-manager]})

(defn mapping
  "Returns [destination-type mapping-rule] for an exact Java qualified name."
  [qualified-name]
  (get mappings qualified-name))

(defn mapped-identities
  "Returns the stable sorted Java identities covered by the neutral registry."
  []
  (vec (sort (keys mappings))))

(defn product-neutral?
  "Checks that registry text contains no supplied destination identity fragment."
  [fragment]
  (let [fragment (str/lower-case (str fragment))]
    (not-any? #(str/includes? (str/lower-case (pr-str %)) fragment) mappings)))
