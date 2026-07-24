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
   "java.lang.Override" ["global::System.Attribute" :dotnet.type/source-annotation]
   "java.lang.SuppressWarnings" ["global::System.Attribute" :dotnet.type/source-annotation]
   "java.lang.FunctionalInterface" ["global::System.Attribute" :dotnet.type/source-annotation]
   "java.lang.Class" ["global::System.Type" :dotnet.type/type]
   "java.lang.ClassLoader" ["object" :dotnet.type/class-loader]
   "java.lang.Enum" ["object" :dotnet.type/enum-base]
   "java.lang.Throwable" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.Exception" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.RuntimeException" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.IllegalArgumentException" ["global::System.ArgumentException"
                                         :dotnet.type/argument-exception]
   "java.lang.IllegalStateException" ["global::System.InvalidOperationException"
                                      :dotnet.type/invalid-operation]
   "java.lang.IllegalAccessException" ["global::System.MemberAccessException"
                                       :dotnet.type/member-access-exception]
   "java.lang.InstantiationException" ["global::System.MemberAccessException"
                                       :dotnet.type/member-access-exception]
   "java.lang.NoSuchMethodException" ["global::System.MissingMethodException"
                                      :dotnet.type/missing-method-exception]
   "java.lang.NoSuchMethodError" ["global::System.MissingMethodException"
                                  :dotnet.type/missing-method-exception]
   "java.lang.ReflectiveOperationException" ["global::System.Reflection.TargetException"
                                             :dotnet.type/reflection-exception]
   "java.lang.SecurityException" ["global::System.Security.SecurityException"
                                  :dotnet.type/security-exception]
   "java.lang.InterruptedException" ["global::System.Threading.ThreadInterruptedException"
                                     :dotnet.type/thread-interrupted]
   "java.lang.IndexOutOfBoundsException" ["global::System.ArgumentOutOfRangeException"
                                          :dotnet.type/argument-out-of-range]
   "java.lang.ClassCastException" ["global::System.InvalidCastException"
                                   :dotnet.type/invalid-cast]
   "java.lang.NullPointerException" ["global::System.NullReferenceException"
                                     :dotnet.type/null-reference]
   "java.lang.UnsupportedOperationException" ["global::System.NotSupportedException"
                                              :dotnet.type/not-supported]
   "java.lang.Deprecated" ["global::System.ObsoleteAttribute"
                           :dotnet.type/source-annotation]
   "java.lang.NumberFormatException" ["global::System.FormatException"
                                      :dotnet.type/format-exception]
   "java.lang.StringBuilder" ["global::System.Text.StringBuilder"
                              :dotnet.type/string-builder]
   "java.lang.AbstractStringBuilder" ["global::System.Text.StringBuilder"
                                      :dotnet.type/string-builder-base]
   "java.lang.Math" ["global::System.Math" :dotnet.type/math]
   "java.lang.System" ["global::Vibeformer.Runtime.JavaCompat"
                       :dotnet.type/java-compat]
   "java.lang.Runtime" ["global::Vibeformer.Runtime.JavaRuntime"
                        :dotnet.type/java-runtime]
   "java.lang.Thread" ["global::Vibeformer.Runtime.JavaThread"
                       :dotnet.type/thread]
   "java.lang.ThreadLocal" ["global::Vibeformer.Runtime.JavaThreadLocal"
                            :dotnet.type/thread-local]
   "java.lang.Runnable" ["global::System.Action" :dotnet.type/action]
   "java.lang.Iterable" ["global::System.Collections.Generic.IEnumerable"
                         :dotnet.type/enumerable]
   "java.lang.AutoCloseable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.lang.Comparable" ["global::System.IComparable" :dotnet.type/comparable]

   "java.io.Closeable" ["global::System.IDisposable" :dotnet.type/disposable]
   "java.io.IOException" ["global::System.IO.IOException" :dotnet.type/io-exception]
   "java.io.InterruptedIOException" ["global::System.IO.IOException"
                                     :dotnet.type/io-exception]
   "java.io.EOFException" ["global::System.IO.EndOfStreamException"
                           :dotnet.type/end-of-stream-exception]
   "java.io.FileNotFoundException" ["global::System.IO.FileNotFoundException"
                                    :dotnet.type/file-not-found-exception]
   "java.io.UnsupportedEncodingException" ["global::System.ArgumentException"
                                           :dotnet.type/argument-exception]
   "java.io.InputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.OutputStream" ["global::System.IO.Stream" :dotnet.type/stream]
   "java.io.Reader" ["global::System.IO.TextReader" :dotnet.type/text-reader]
   "java.io.InputStreamReader" ["global::System.IO.StreamReader"
                                :dotnet.type/stream-reader]
   "java.io.LineNumberReader" ["global::Vibeformer.Runtime.JavaLineNumberReader"
                               :dotnet.type/line-number-reader]
   "java.io.DataOutputStream" ["global::Vibeformer.Runtime.JavaDataOutputStream"
                               :dotnet.type/data-output-stream]
   "java.io.FilterOutputStream" ["global::Vibeformer.Runtime.JavaFilterOutputStream"
                                 :dotnet.type/filter-output-stream]
   "java.io.BufferedInputStream" ["global::System.IO.BufferedStream"
                                  :dotnet.type/buffered-stream]
   "java.io.ByteArrayInputStream" ["global::System.IO.MemoryStream"
                                   :dotnet.type/memory-stream]
   "java.io.ByteArrayOutputStream" ["global::System.IO.MemoryStream"
                                    :dotnet.type/memory-stream]
   "java.io.PipedInputStream" ["global::Vibeformer.Runtime.JavaPipedInputStream"
                               :dotnet.type/piped-input-stream]
   "java.io.PipedOutputStream" ["global::Vibeformer.Runtime.JavaPipedOutputStream"
                                :dotnet.type/piped-output-stream]
   "java.io.PrintStream" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.PushbackInputStream" ["global::Vibeformer.Runtime.JavaPushbackInputStream"
                                  :dotnet.type/pushback-input-stream]
   "java.io.File" ["global::System.IO.FileInfo" :dotnet.type/file]
   "java.io.RandomAccessFile" ["global::Vibeformer.Runtime.JavaRandomAccessFile"
                               :dotnet.type/random-access-file]

   "java.lang.invoke.MethodHandle" ["global::Vibeformer.Runtime.JavaMethodHandle"
                                    :dotnet.type/method-handle]
   "java.lang.invoke.MethodHandles" ["global::Vibeformer.Runtime.JavaMethodHandles"
                                     :dotnet.type/method-handles]
   "java.lang.invoke.MethodHandles$Lookup" ["global::Vibeformer.Runtime.JavaMethodHandlesLookup"
                                            :dotnet.type/method-handles-lookup]
   "java.lang.invoke.MethodType" ["global::Vibeformer.Runtime.JavaMethodType"
                                  :dotnet.type/method-type]
   "java.lang.reflect.Field" ["global::System.Reflection.FieldInfo"
                              :dotnet.type/reflection-field]
   "java.lang.reflect.AccessibleObject" ["global::System.Reflection.MemberInfo"
                                        :dotnet.type/reflection-member]
   "java.lang.reflect.Method" ["global::System.Reflection.MethodInfo"
                               :dotnet.type/reflection-method]
   "java.lang.reflect.Constructor" ["global::System.Reflection.ConstructorInfo"
                                    :dotnet.type/reflection-constructor]
   "java.lang.reflect.InvocationTargetException"
   ["global::System.Reflection.TargetInvocationException"
    :dotnet.type/target-invocation-exception]
   "java.lang.annotation.Annotation" ["global::System.Attribute"
                                      :dotnet.type/attribute]
   "java.lang.annotation.ElementType" ["global::System.AttributeTargets"
                                       :dotnet.type/attribute-targets]
   "java.lang.annotation.Retention" ["global::System.Attribute"
                                     :dotnet.type/source-annotation]
   "java.lang.annotation.RetentionPolicy" ["global::System.AttributeTargets"
                                           :dotnet.type/source-annotation-policy]
   "java.lang.annotation.Target" ["global::System.Attribute"
                                  :dotnet.type/source-annotation]

   "java.net.URI" ["global::System.Uri" :dotnet.type/uri]
   "java.net.URL" ["global::System.Uri" :dotnet.type/uri]
   "java.net.URLDecoder" ["global::Vibeformer.Runtime.JavaCompat"
                          :dotnet.type/java-compat]
   "java.net.URISyntaxException" ["global::System.UriFormatException"
                                  :dotnet.type/uri-format-exception]
   "java.net.InetAddress" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.InetSocketAddress" ["global::System.Net.IPEndPoint"
                                 :dotnet.type/ip-endpoint]
   "java.net.SocketAddress" ["global::System.Net.EndPoint" :dotnet.type/end-point]
   "java.net.Socket" ["global::System.Net.Sockets.Socket" :dotnet.type/socket]
   "java.net.ServerSocket" ["global::Vibeformer.Runtime.JavaServerSocket"
                            :dotnet.type/server-socket]
   "java.net.SocketException" ["global::System.Net.Sockets.SocketException"
                               :dotnet.type/socket-exception]
   "java.net.SocketTimeoutException" ["global::System.TimeoutException"
                                      :dotnet.type/timeout-exception]

   "java.nio.charset.Charset" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.nio.charset.StandardCharsets" ["global::Vibeformer.Runtime.JavaStandardCharsets"
                                        :dotnet.type/standard-charsets]
   "java.nio.BufferUnderflowException" ["global::System.IO.EndOfStreamException"
                                        :dotnet.type/end-of-stream-exception]
   "java.nio.file.Files" ["global::Vibeformer.Runtime.JavaCompat"
                          :dotnet.type/java-compat]
   "java.nio.file.Paths" ["global::Vibeformer.Runtime.JavaCompat"
                          :dotnet.type/java-compat]
   "java.nio.file.Path" ["global::Vibeformer.Runtime.JavaPath"
                         :dotnet.type/path]
   "java.nio.file.OpenOption" ["object" :dotnet.type/open-option]
   "java.nio.Buffer" ["global::Vibeformer.Runtime.JavaByteBuffer"
                      :dotnet.type/byte-buffer]
   "java.nio.ByteBuffer" ["global::Vibeformer.Runtime.JavaByteBuffer"
                          :dotnet.type/byte-buffer]
   "java.nio.MappedByteBuffer" ["global::Vibeformer.Runtime.JavaByteBuffer"
                                :dotnet.type/byte-buffer]
   "java.nio.channels.FileChannel" ["global::Vibeformer.Runtime.JavaFileChannel"
                                    :dotnet.type/file-channel]
   "java.nio.channels.FileChannel$MapMode" ["global::Vibeformer.Runtime.JavaFileChannelMapMode"
                                            :dotnet.type/file-channel-map-mode]
   "java.nio.channels.spi.AbstractInterruptibleChannel" ["global::System.IDisposable"
                                                         :dotnet.type/disposable]
   "java.nio.file.FileSystem" ["global::Vibeformer.Runtime.JavaFileSystem"
                               :dotnet.type/file-system]
   "java.nio.file.FileSystems" ["global::Vibeformer.Runtime.JavaFileSystems"
                                :dotnet.type/file-systems]
   "java.nio.file.FileVisitOption" ["object" :dotnet.type/file-visit-option]
   "java.nio.file.LinkOption" ["object" :dotnet.type/link-option]
   "java.nio.file.StandardOpenOption" ["global::Vibeformer.Runtime.JavaStandardOpenOption"
                                       :dotnet.type/standard-open-option]
   "java.nio.file.attribute.AclEntry" ["global::Vibeformer.Runtime.JavaAclEntry"
                                       :dotnet.type/acl-entry]
   "java.nio.file.attribute.AclEntry$Builder" ["global::Vibeformer.Runtime.JavaAclEntryBuilder"
                                               :dotnet.type/acl-entry-builder]
   "java.nio.file.attribute.AclEntryPermission" ["global::Vibeformer.Runtime.JavaAclEntryPermission"
                                                 :dotnet.type/acl-entry-permission]
   "java.nio.file.attribute.AclEntryType" ["global::Vibeformer.Runtime.JavaAclEntryType"
                                           :dotnet.type/acl-entry-type]
   "java.nio.file.attribute.AclFileAttributeView" ["global::Vibeformer.Runtime.JavaAclFileAttributeView"
                                                   :dotnet.type/acl-file-attribute-view]
   "java.nio.file.attribute.FileAttribute" ["global::Vibeformer.Runtime.JavaFileAttribute"
                                            :dotnet.type/file-attribute]
   "java.nio.file.attribute.FileOwnerAttributeView" ["global::Vibeformer.Runtime.JavaAclFileAttributeView"
                                                     :dotnet.type/file-owner-attribute-view]
   "java.nio.file.attribute.PosixFilePermission" ["global::System.IO.UnixFileMode"
                                                  :dotnet.type/unix-file-mode]
   "java.nio.file.attribute.PosixFilePermissions" ["global::Vibeformer.Runtime.JavaCompat"
                                                   :dotnet.type/java-compat]
   "java.nio.file.attribute.UserPrincipal" ["global::Vibeformer.Runtime.JavaUserPrincipal"
                                            :dotnet.type/user-principal]

   "java.security.AccessController" ["global::Vibeformer.Runtime.JavaCompat"
                                     :dotnet.type/java-compat]
   "java.security.PrivilegedAction" ["global::System.Func"
                                     :dotnet.type/func]

   "java.time.Duration" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.Instant" ["global::System.DateTimeOffset" :dotnet.type/date-time-offset]
   "java.time.ZoneId" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.ZoneOffset" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.ZonedDateTime" ["global::System.DateTimeOffset" :dotnet.type/date-time-offset]
   "java.time.format.DateTimeFormatter" ["global::Vibeformer.Runtime.JavaDateTimeFormatter"
                                         :dotnet.type/date-time-formatter]
   "java.time.format.DateTimeFormatterBuilder"
   ["global::Vibeformer.Runtime.JavaDateTimeFormatterBuilder"
    :dotnet.type/date-time-formatter-builder]
   "java.time.format.DateTimeParseException" ["global::System.FormatException"
                                              :dotnet.type/format-exception]
   "java.time.LocalDateTime" ["global::System.DateTime"
                              :dotnet.type/local-date-time]
   "java.time.temporal.TemporalAccessor" ["global::System.DateTimeOffset"
                                          :dotnet.type/date-time-offset]
   "java.time.temporal.TemporalAmount" ["global::System.TimeSpan" :dotnet.type/time-span]

   "java.util.AbstractMap" ["global::System.Collections.Generic.IDictionary"
                            :dotnet.type/map-interface]
   "java.util.ArrayList" ["global::System.Collections.Generic.List"
                          :dotnet.type/list]
   "java.util.ArrayDeque" ["global::Vibeformer.Runtime.JavaDeque"
                           :dotnet.type/deque]
   "java.util.Arrays" ["global::Vibeformer.Runtime.JavaCompat"
                       :dotnet.type/java-compat]
   "java.util.BitSet" ["global::Vibeformer.Runtime.JavaBitSet"
                       :dotnet.type/bit-set]
   "java.util.Collection" ["global::System.Collections.Generic.ICollection"
                           :dotnet.type/collection]
   "java.util.Collections" ["global::Vibeformer.Runtime.JavaCompat"
                            :dotnet.type/java-compat]
   "java.util.Comparator" ["global::System.Collections.Generic.IComparer"
                           :dotnet.type/comparer]
   "java.util.Deque" ["global::Vibeformer.Runtime.JavaDeque"
                      :dotnet.type/deque]
   "java.util.EnumMap" ["global::System.Collections.Generic.Dictionary"
                        :dotnet.type/dictionary]
   "java.util.EnumSet" ["global::System.Collections.Generic.ISet"
                        :dotnet.type/set-interface]
   "java.util.HashMap" ["global::System.Collections.Generic.Dictionary"
                        :dotnet.type/dictionary]
   "java.util.HashSet" ["global::System.Collections.Generic.HashSet"
                        :dotnet.type/hash-set]
   "java.util.Iterator" ["global::Vibeformer.Runtime.JavaIterator"
                         :dotnet.type/java-iterator]
   "java.util.LinkedHashMap" ["global::Vibeformer.Runtime.JavaLinkedHashMap"
                              :dotnet.type/linked-dictionary]
   "java.util.LinkedHashSet" ["global::System.Collections.Generic.HashSet"
                              :dotnet.type/linked-hash-set]
   "java.util.LinkedList" ["global::System.Collections.Generic.List"
                           :dotnet.type/list]
   "java.util.List" ["global::System.Collections.Generic.IList"
                     :dotnet.type/list-interface]
   "java.util.Map" ["global::System.Collections.Generic.IDictionary"
                    :dotnet.type/map-interface]
   "java.util.Map$Entry" ["global::Vibeformer.Runtime.JavaMapEntry"
                          :dotnet.type/map-entry]
   "java.util.AbstractMap$SimpleEntry" ["global::Vibeformer.Runtime.JavaSimpleEntry"
                                        :dotnet.type/simple-map-entry]
   "java.util.AbstractMap$SimpleImmutableEntry" ["global::Vibeformer.Runtime.JavaSimpleImmutableEntry"
                                                 :dotnet.type/simple-immutable-map-entry]
   "java.util.NoSuchElementException" ["global::System.InvalidOperationException"
                                       :dotnet.type/invalid-operation]
   "java.util.Calendar" ["global::System.DateTimeOffset"
                         :dotnet.type/date-time-offset]
   "java.util.GregorianCalendar" ["global::System.DateTimeOffset"
                                  :dotnet.type/date-time-offset]
   "java.util.Locale" ["global::System.Globalization.CultureInfo"
                       :dotnet.type/culture-info]
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
   "java.util.SortedMap" ["global::System.Collections.Generic.IDictionary"
                          :dotnet.type/map-interface]
   "java.util.SortedSet" ["global::System.Collections.Generic.ISet"
                          :dotnet.type/set-interface]
   "java.util.StringJoiner" ["global::Vibeformer.Runtime.JavaStringJoiner"
                             :dotnet.type/string-joiner]
   "java.util.StringTokenizer" ["global::Vibeformer.Runtime.JavaStringTokenizer"
                                :dotnet.type/string-tokenizer]
   "java.util.TimeZone" ["global::System.TimeZoneInfo"
                         :dotnet.type/time-zone-info]
   "java.util.SimpleTimeZone" ["global::System.TimeZoneInfo"
                               :dotnet.type/time-zone-info]
   "java.util.TreeMap" ["global::System.Collections.Generic.SortedDictionary"
                        :dotnet.type/sorted-dictionary]
   "java.util.TreeSet" ["global::System.Collections.Generic.SortedSet"
                        :dotnet.type/sorted-set]

   "java.util.concurrent.Callable" ["global::System.Func" :dotnet.type/func]
   "java.util.concurrent.ConcurrentHashMap" ["global::System.Collections.Concurrent.ConcurrentDictionary"
                                             :dotnet.type/concurrent-dictionary]
   "java.util.concurrent.ConcurrentMap" ["global::System.Collections.Concurrent.ConcurrentDictionary"
                                         :dotnet.type/concurrent-dictionary]
   "java.util.concurrent.Executor" ["global::Vibeformer.Runtime.JavaExecutorService"
                                    :dotnet.type/executor]
   "java.util.concurrent.ExecutorService" ["global::Vibeformer.Runtime.JavaExecutorService"
                                           :dotnet.type/executor-service]
   "java.util.concurrent.ExecutionException" ["global::System.AggregateException"
                                              :dotnet.type/execution-exception]
   "java.util.concurrent.Executors" ["global::Vibeformer.Runtime.JavaCompat"
                                     :dotnet.type/java-compat]
   "java.util.concurrent.Future" ["global::Vibeformer.Runtime.JavaFuture"
                                  :dotnet.type/future]
   "java.util.concurrent.ThreadFactory" ["global::Vibeformer.Runtime.JavaThreadFactory"
                                         :dotnet.type/thread-factory]
   "java.util.concurrent.TimeUnit" ["global::Vibeformer.Runtime.JavaTimeUnit"
                                    :dotnet.type/time-unit]
   "java.util.concurrent.TimeoutException" ["global::System.TimeoutException"
                                            :dotnet.type/timeout-exception]
   "java.util.concurrent.atomic.AtomicBoolean" ["global::Vibeformer.Runtime.JavaAtomicBoolean"
                                                :dotnet.type/atomic-boolean]
   "java.util.concurrent.atomic.AtomicInteger" ["global::Vibeformer.Runtime.JavaAtomicInteger"
                                                :dotnet.type/atomic-integer]
   "java.util.concurrent.atomic.AtomicReference" ["global::Vibeformer.Runtime.JavaAtomicReference"
                                                  :dotnet.type/atomic-reference]
   "java.util.function.BiConsumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.BiFunction" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Consumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.Function" ["global::System.Func" :dotnet.type/func]
   "java.util.function.LongConsumer" ["global::System.Action<long>"
                                      :dotnet.type/long-consumer]
   "java.util.function.Predicate" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Supplier" ["global::System.Func" :dotnet.type/func]
   "java.util.function.ToLongFunction" ["global::Vibeformer.Runtime.JavaToLongFunction"
                                        :dotnet.type/to-long-function]
   "java.util.regex.Pattern" ["global::System.Text.RegularExpressions.Regex"
                              :dotnet.type/regex]
   "java.util.regex.Matcher" ["global::Vibeformer.Runtime.JavaRegexMatcher"
                              :dotnet.type/regex-matcher]
   "java.util.stream.Stream" ["global::Vibeformer.Runtime.JavaStream"
                              :dotnet.type/java-stream]
   "java.util.stream.LongStream" ["global::System.Collections.Generic.IEnumerable<long>"
                                  :dotnet.type/long-stream]
   "java.util.stream.Collector" ["global::Vibeformer.Runtime.JavaCollector"
                                 :dotnet.type/java-collector]
   "java.util.stream.Collectors" ["global::Vibeformer.Runtime.JavaCompat"
                                  :dotnet.type/java-compat]

   "java.util.zip.GZIPInputStream" ["global::System.IO.Compression.GZipStream"
                                    :dotnet.type/gzip-stream]
   "java.util.zip.InflaterOutputStream" ["global::Vibeformer.Runtime.JavaInflaterOutputStream"
                                         :dotnet.type/inflater-output-stream]

   "javax.annotation.Nullable" ["global::System.Diagnostics.CodeAnalysis.MaybeNullAttribute"
                                :dotnet.type/nullable-annotation]
   "javax.annotation.Nonnull" ["global::System.Diagnostics.CodeAnalysis.NotNullAttribute"
                               :dotnet.type/nonnullable-annotation]
   "javax.xml.XMLConstants" ["global::Vibeformer.Runtime.JavaCompat"
                             :dotnet.type/xml-constants]
   "javax.xml.namespace.QName" ["global::System.Xml.XmlQualifiedName"
                                :dotnet.type/xml-qualified-name]
   "javax.xml.parsers.DocumentBuilder" ["global::System.Xml.XmlReaderSettings"
                                        :dotnet.type/xml-reader-settings]
   "javax.xml.parsers.DocumentBuilderFactory" ["global::System.Xml.XmlReaderSettings"
                                               :dotnet.type/xml-reader-settings]
   "javax.xml.parsers.ParserConfigurationException" ["global::System.Xml.XmlException"
                                                      :dotnet.type/xml-exception]
   "javax.xml.transform.OutputKeys" ["global::Vibeformer.Runtime.JavaCompat"
                                     :dotnet.type/xml-output-keys]
   "javax.xml.transform.Result" ["global::System.IO.Stream"
                                 :dotnet.type/xml-result]
   "javax.xml.transform.Source" ["global::System.Xml.XmlNode"
                                 :dotnet.type/xml-node]
   "javax.xml.transform.Transformer" ["global::System.Xml.XmlWriterSettings"
                                      :dotnet.type/xml-writer-settings]
   "javax.xml.transform.TransformerException" ["global::System.Xml.XmlException"
                                               :dotnet.type/xml-exception]
   "javax.xml.transform.TransformerFactory" ["global::System.Xml.XmlWriterSettings"
                                             :dotnet.type/xml-writer-settings]
   "javax.xml.transform.dom.DOMSource" ["global::System.Xml.XmlNode"
                                       :dotnet.type/xml-node]
   "javax.xml.transform.stream.StreamResult" ["global::System.IO.Stream"
                                              :dotnet.type/xml-result]
   "org.w3c.dom.Attr" ["global::System.Xml.XmlAttribute"
                       :dotnet.type/xml-attribute]
   "org.w3c.dom.Comment" ["global::System.Xml.XmlComment"
                          :dotnet.type/xml-comment]
   "org.w3c.dom.Document" ["global::System.Xml.XmlDocument"
                           :dotnet.type/xml-document]
   "org.w3c.dom.Element" ["global::System.Xml.XmlElement"
                          :dotnet.type/xml-element]
   "org.w3c.dom.NamedNodeMap" ["global::System.Xml.XmlAttributeCollection"
                               :dotnet.type/xml-attribute-collection]
   "org.w3c.dom.Node" ["global::System.Xml.XmlNode"
                       :dotnet.type/xml-node]
   "org.w3c.dom.NodeList" ["global::System.Xml.XmlNodeList"
                           :dotnet.type/xml-node-list]
   "org.w3c.dom.ProcessingInstruction" ["global::System.Xml.XmlProcessingInstruction"
                                        :dotnet.type/xml-processing-instruction]
   "org.w3c.dom.Text" ["global::System.Xml.XmlText"
                       :dotnet.type/xml-text]
   "org.xml.sax.SAXException" ["global::System.Xml.XmlException"
                               :dotnet.type/xml-exception]
   "org.xml.sax.ErrorHandler" ["object" :dotnet.type/xml-error-handler]
   "javax.net.ServerSocketFactory" ["global::Vibeformer.Runtime.JavaSslServerSocketFactory"
                                    :dotnet.type/server-socket-factory]
   "javax.net.SocketFactory" ["global::Vibeformer.Runtime.JavaSocketFactory"
                              :dotnet.type/socket-factory]

   "java.security.KeyManagementException" ["global::System.Security.Cryptography.CryptographicException"
                                           :dotnet.type/cryptographic-exception]
   "java.security.GeneralSecurityException" ["global::System.Security.Cryptography.CryptographicException"
                                             :dotnet.type/cryptographic-exception]
   "java.security.KeyStoreException" ["global::System.Security.Cryptography.CryptographicException"
                                      :dotnet.type/cryptographic-exception]
   "java.security.NoSuchAlgorithmException" ["global::System.Security.Cryptography.CryptographicException"
                                             :dotnet.type/cryptographic-exception]
   "java.security.UnrecoverableKeyException" ["global::System.Security.Cryptography.CryptographicException"
                                              :dotnet.type/cryptographic-exception]
   "java.security.KeyStore" ["global::Vibeformer.Runtime.JavaKeyStore"
                             :dotnet.type/key-store]
   "java.security.SecureRandom" ["object" :dotnet.type/secure-random]
   "java.security.cert.CertificateException" ["global::System.Security.Cryptography.CryptographicException"
                                              :dotnet.type/cryptographic-exception]
   "java.security.cert.X509Certificate" ["global::System.Security.Cryptography.X509Certificates.X509Certificate2"
                                         :dotnet.type/x509-certificate]

   "javax.net.ssl.KeyManager" ["object" :dotnet.type/key-manager]
   "javax.net.ssl.KeyManagerFactory" ["global::Vibeformer.Runtime.JavaKeyManagerFactory"
                                      :dotnet.type/key-manager-factory]
   "javax.net.ssl.SSLContext" ["global::Vibeformer.Runtime.JavaSslContext"
                               :dotnet.type/ssl-context]
   "javax.net.ssl.SSLServerSocketFactory" ["global::Vibeformer.Runtime.JavaSslServerSocketFactory"
                                           :dotnet.type/ssl-server-socket-factory]
   "javax.net.ssl.SSLSocket" ["global::System.Net.Sockets.Socket"
                              :dotnet.type/ssl-socket]
   "javax.net.ssl.SSLSocketFactory" ["global::Vibeformer.Runtime.JavaSocketFactory"
                                     :dotnet.type/ssl-socket-factory]
   "javax.net.ssl.TrustManager" ["object" :dotnet.type/trust-manager]
   "javax.net.ssl.TrustManagerFactory" ["global::Vibeformer.Runtime.JavaTrustManagerFactory"
                                        :dotnet.type/trust-manager-factory]
   "javax.net.ssl.X509TrustManager" ["global::Vibeformer.Runtime.JavaX509TrustManager"
                                     :dotnet.type/x509-trust-manager]})

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
