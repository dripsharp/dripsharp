(ns dripsharp.java-types
  "Product-neutral Java type mappings used by ordinary destination bundles.

  Entries in this registry describe only reusable Java/JDK contracts with a
  direct .NET representation or an existing generic DripSharp compatibility
  type. Product identities and destination-library package names do not belong
  here. Resolved occurrence identity remains the caller's dispatch key."
  (:require [clojure.string :as str]
            [dripsharp.java-mapping-registry :as mapping-registry]))

(def ^:private mapping-specifications
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
   "java.lang.Cloneable" ["global::DripSharp.Runtime.JavaCloneable"
                          :dotnet.type/cloneable]
   "java.io.Serializable" ["object" :dotnet.type/serializable]
   "java.io.Console" ["object" :dotnet.type/console-marker]
   "java.lang.constant.Constable" ["object" :dotnet.type/constable]
   "java.lang.constant.ConstantDesc" ["object" :dotnet.type/constant-description]
   "java.lang.ClassLoader" ["object" :dotnet.type/class-loader]
   "java.lang.Process" ["global::DripSharp.Runtime.JavaProcess"
                        :dotnet.type/process]
   "java.lang.ProcessBuilder" ["global::DripSharp.Runtime.JavaProcessBuilder"
                               :dotnet.type/process-builder]
   "java.lang.ProcessBuilder$Redirect" ["global::DripSharp.Runtime.JavaProcessRedirect"
                                        :dotnet.type/process-redirect]
   "java.lang.Enum" ["object" :dotnet.type/enum-base]
   "java.lang.Record" ["object" :dotnet.type/record-base]
   "java.lang.Throwable" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.Exception" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.RuntimeException" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.Error" ["global::System.Exception" :dotnet.type/exception]
   "java.lang.ExceptionInInitializerError"
   ["global::System.TypeInitializationException"
    :dotnet.type/type-initialization-exception]
   "java.lang.StackTraceElement" ["global::System.Diagnostics.StackFrame"
                                  :dotnet.type/stack-frame]
   "java.lang.IllegalArgumentException" ["global::System.ArgumentException"
                                         :dotnet.type/argument-exception]
   "java.lang.IllegalStateException" ["global::System.InvalidOperationException"
                                      :dotnet.type/invalid-operation]
   "java.lang.ArithmeticException" ["global::System.ArithmeticException"
                                    :dotnet.type/arithmetic-exception]
   "java.lang.CloneNotSupportedException" ["global::System.NotSupportedException"
                                           :dotnet.type/not-supported]
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
   "java.lang.ArrayIndexOutOfBoundsException"
   ["global::System.IndexOutOfRangeException" :dotnet.type/index-out-of-range]
   "java.lang.NegativeArraySizeException"
   ["global::System.OverflowException" :dotnet.type/negative-array-size]
   "java.lang.ClassCastException" ["global::System.InvalidCastException"
                                   :dotnet.type/invalid-cast]
   "java.lang.NullPointerException" ["global::System.NullReferenceException"
                                     :dotnet.type/null-reference]
   "java.lang.UnsupportedOperationException" ["global::System.NotSupportedException"
                                              :dotnet.type/not-supported]
   "java.lang.AssertionError" ["global::DripSharp.Runtime.JavaAssertionError"
                               :dotnet.type/assertion-error]
   "java.lang.UnsatisfiedLinkError" ["global::System.DllNotFoundException"
                                     :dotnet.type/missing-native-library]
   "java.lang.InternalError" ["global::System.SystemException"
                              :dotnet.type/internal-error]
   "java.awt.geom.NoninvertibleTransformException"
   ["global::System.InvalidOperationException"
    :dotnet.type/noninvertible-transform]
   "java.lang.Deprecated" ["global::System.ObsoleteAttribute"
                           :dotnet.type/source-annotation]
   "java.lang.NumberFormatException" ["global::DripSharp.Runtime.JavaNumberFormatException"
                                      :dotnet.type/number-format-exception]
   "java.lang.StringBuilder" ["global::System.Text.StringBuilder"
                              :dotnet.type/string-builder]
   "java.lang.AbstractStringBuilder" ["global::System.Text.StringBuilder"
                                      :dotnet.type/string-builder-base]
   "java.lang.Math" ["global::System.Math" :dotnet.type/math]
   "java.lang.StrictMath" ["global::System.Math" :dotnet.type/math]
   "java.lang.System" ["global::DripSharp.Runtime.JavaCompat"
                       :dotnet.type/java-compat]
   "java.lang.Runtime" ["global::DripSharp.Runtime.JavaRuntime"
                        :dotnet.type/java-runtime]
   "java.lang.Thread" ["global::DripSharp.Runtime.JavaThread"
                       :dotnet.type/thread]
   "java.lang.ThreadLocal" ["global::DripSharp.Runtime.JavaThreadLocal"
                            :dotnet.type/thread-local]
   "java.lang.ref.SoftReference" ["global::DripSharp.Runtime.JavaSoftReference"
                                  :dotnet.type/soft-reference]
   "java.lang.ref.WeakReference" ["global::DripSharp.Runtime.JavaWeakReference"
                                  :dotnet.type/weak-reference]
   "java.lang.ref.Reference" ["global::DripSharp.Runtime.JavaReference"
                              :dotnet.type/reference]
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
   "java.io.StringReader" ["global::System.IO.StringReader"
                           :dotnet.type/string-reader]
   "java.io.Writer" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.FileWriter" ["global::System.IO.StreamWriter"
                         :dotnet.type/stream-writer]
   "java.io.InputStreamReader" ["global::System.IO.StreamReader"
                                :dotnet.type/stream-reader]
   "java.io.OutputStreamWriter" ["global::System.IO.StreamWriter"
                                 :dotnet.type/stream-writer]
   "java.io.BufferedReader" ["global::System.IO.TextReader"
                             :dotnet.type/buffered-reader]
   "java.io.BufferedWriter" ["global::System.IO.TextWriter"
                             :dotnet.type/buffered-writer]
   "java.io.LineNumberReader" ["global::DripSharp.Runtime.JavaLineNumberReader"
                               :dotnet.type/line-number-reader]
   "java.io.DataOutputStream" ["global::DripSharp.Runtime.JavaDataOutputStream"
                               :dotnet.type/data-output-stream]
   "java.io.FilterOutputStream" ["global::DripSharp.Runtime.JavaFilterOutputStream"
                                 :dotnet.type/filter-output-stream]
   "java.io.FilterInputStream" ["global::DripSharp.Runtime.JavaFilterInputStream"
                                :dotnet.type/filter-input-stream]
   "java.io.FileInputStream" ["global::System.IO.Stream"
                              :dotnet.type/file-input-stream]
   "java.io.FileReader" ["global::System.IO.TextReader"
                         :dotnet.type/file-reader]
   "java.io.FileOutputStream" ["global::System.IO.Stream"
                               :dotnet.type/file-output-stream]
   "java.io.BufferedInputStream" ["global::System.IO.BufferedStream"
                                  :dotnet.type/buffered-stream]
   "java.io.BufferedOutputStream" ["global::System.IO.BufferedStream"
                                   :dotnet.type/buffered-stream]
   "java.io.ByteArrayInputStream" ["global::System.IO.MemoryStream"
                                   :dotnet.type/memory-stream]
   "java.io.ByteArrayOutputStream"
   ["global::DripSharp.Runtime.JavaByteArrayOutputStream"
    :dotnet.type/byte-array-output-stream]
   "java.io.PipedInputStream" ["global::DripSharp.Runtime.JavaPipedInputStream"
                               :dotnet.type/piped-input-stream]
   "java.io.PipedOutputStream" ["global::DripSharp.Runtime.JavaPipedOutputStream"
                                :dotnet.type/piped-output-stream]
   "java.io.PrintStream" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.PrintWriter" ["global::System.IO.TextWriter" :dotnet.type/text-writer]
   "java.io.PushbackInputStream" ["global::DripSharp.Runtime.JavaPushbackInputStream"
                                  :dotnet.type/pushback-input-stream]
   "java.io.SequenceInputStream" ["global::System.IO.Stream"
                                  :dotnet.type/stream]
   "java.io.StringWriter" ["global::System.IO.StringWriter"
                           :dotnet.type/string-writer]
   "java.io.File" ["global::System.IO.FileInfo" :dotnet.type/file]
   "java.io.RandomAccessFile" ["global::DripSharp.Runtime.JavaRandomAccessFile"
                               :dotnet.type/random-access-file]

   "java.lang.invoke.MethodHandle" ["global::DripSharp.Runtime.JavaMethodHandle"
                                    :dotnet.type/method-handle]
   "java.lang.invoke.MethodHandles" ["global::DripSharp.Runtime.JavaMethodHandles"
                                     :dotnet.type/method-handles]
   "java.lang.invoke.MethodHandles$Lookup" ["global::DripSharp.Runtime.JavaMethodHandlesLookup"
                                            :dotnet.type/method-handles-lookup]
   "java.lang.invoke.MethodType" ["global::DripSharp.Runtime.JavaMethodType"
                                  :dotnet.type/method-type]
   "java.lang.reflect.Field" ["global::System.Reflection.FieldInfo"
                              :dotnet.type/reflection-field]
   "java.lang.reflect.Modifier" ["global::DripSharp.Runtime.JavaCompat"
                                 :dotnet.type/reflection-modifier]
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
   "java.math.BigInteger" ["global::System.Numerics.BigInteger"
                           :dotnet.type/big-integer]
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
   "java.net.URLDecoder" ["global::DripSharp.Runtime.JavaCompat"
                          :dotnet.type/java-compat]
   "java.net.URLEncoder" ["global::DripSharp.Runtime.JavaCompat"
                          :dotnet.type/java-compat]
   "java.net.URISyntaxException" ["global::System.UriFormatException"
                                  :dotnet.type/uri-format-exception]
   "java.net.InetAddress" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.Inet4Address" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.Inet6Address" ["global::System.Net.IPAddress" :dotnet.type/ip-address]
   "java.net.InetSocketAddress" ["global::System.Net.IPEndPoint"
                                 :dotnet.type/ip-endpoint]
   "java.net.SocketAddress" ["global::System.Net.EndPoint" :dotnet.type/end-point]
   "java.net.Socket" ["global::System.Net.Sockets.Socket" :dotnet.type/socket]
   "java.net.ServerSocket" ["global::DripSharp.Runtime.JavaServerSocket"
                            :dotnet.type/server-socket]
   "java.net.SocketException" ["global::System.Net.Sockets.SocketException"
                               :dotnet.type/socket-exception]
   "java.net.SocketTimeoutException" ["global::System.TimeoutException"
                                      :dotnet.type/timeout-exception]

   "java.nio.charset.Charset" ["global::System.Text.Encoding" :dotnet.type/encoding]
   "java.nio.charset.CharsetDecoder" ["global::DripSharp.Runtime.JavaCharsetDecoder"
                                      :dotnet.type/charset-decoder]
   "java.nio.charset.CodingErrorAction" ["global::DripSharp.Runtime.JavaCodingErrorAction"
                                         :dotnet.type/coding-error-action]
   "java.nio.charset.CharacterCodingException" ["global::System.Text.DecoderFallbackException"
                                                :dotnet.type/decoder-fallback-exception]
   "java.nio.CharBuffer" ["string" :dotnet.type/char-buffer]
   "java.nio.charset.StandardCharsets" ["global::DripSharp.Runtime.JavaStandardCharsets"
                                        :dotnet.type/standard-charsets]
   "java.nio.BufferUnderflowException" ["global::System.IO.EndOfStreamException"
                                        :dotnet.type/end-of-stream-exception]
   "java.nio.file.Files" ["global::DripSharp.Runtime.JavaCompat"
                          :dotnet.type/java-compat]
   "java.nio.file.Paths" ["global::DripSharp.Runtime.JavaCompat"
                          :dotnet.type/java-compat]
   "java.nio.file.Path" ["global::DripSharp.Runtime.JavaPath"
                         :dotnet.type/path]
   "java.nio.file.OpenOption" ["object" :dotnet.type/open-option]
   "java.nio.Buffer" ["global::DripSharp.Runtime.JavaByteBuffer"
                      :dotnet.type/byte-buffer]
   "java.nio.ByteBuffer" ["global::DripSharp.Runtime.JavaByteBuffer"
                          :dotnet.type/byte-buffer]
   "java.nio.MappedByteBuffer" ["global::DripSharp.Runtime.JavaByteBuffer"
                                :dotnet.type/byte-buffer]
   "java.nio.channels.FileChannel" ["global::DripSharp.Runtime.JavaFileChannel"
                                    :dotnet.type/file-channel]
   "java.nio.channels.FileChannel$MapMode" ["global::DripSharp.Runtime.JavaFileChannelMapMode"
                                            :dotnet.type/file-channel-map-mode]
   "java.nio.channels.spi.AbstractInterruptibleChannel" ["global::System.IDisposable"
                                                         :dotnet.type/disposable]
   "java.nio.file.FileSystem" ["global::DripSharp.Runtime.JavaFileSystem"
                               :dotnet.type/file-system]
   "java.nio.file.FileSystems" ["global::DripSharp.Runtime.JavaFileSystems"
                                :dotnet.type/file-systems]
   "java.nio.file.FileStore" ["global::System.IO.DriveInfo"
                              :dotnet.type/drive-info]
   "java.nio.file.PathMatcher" ["global::System.Predicate<string>"
                                :dotnet.type/path-matcher]
   "java.nio.file.WatchService" ["global::DripSharp.Runtime.JavaWatchService"
                                 :dotnet.type/watch-service]
   "java.nio.file.attribute.UserPrincipalLookupService"
   ["object" :dotnet.type/user-principal-lookup]
   "java.nio.file.spi.FileSystemProvider"
   ["global::DripSharp.Runtime.JavaFileSystemProvider"
    :dotnet.type/file-system-provider]
   "java.nio.file.FileVisitOption" ["object" :dotnet.type/file-visit-option]
   "java.nio.file.LinkOption" ["object" :dotnet.type/link-option]
   "java.nio.file.StandardOpenOption" ["global::DripSharp.Runtime.JavaStandardOpenOption"
                                       :dotnet.type/standard-open-option]
   "java.nio.file.attribute.AclEntry" ["global::DripSharp.Runtime.JavaAclEntry"
                                       :dotnet.type/acl-entry]
   "java.nio.file.attribute.BasicFileAttributes" ["global::System.IO.FileSystemInfo"
                                                  :dotnet.type/file-info]
   "java.nio.file.attribute.AclEntry$Builder" ["global::DripSharp.Runtime.JavaAclEntryBuilder"
                                               :dotnet.type/acl-entry-builder]
   "java.nio.file.attribute.AclEntryPermission" ["global::DripSharp.Runtime.JavaAclEntryPermission"
                                                 :dotnet.type/acl-entry-permission]
   "java.nio.file.attribute.AclEntryType" ["global::DripSharp.Runtime.JavaAclEntryType"
                                           :dotnet.type/acl-entry-type]
   "java.nio.file.attribute.AclFileAttributeView" ["global::DripSharp.Runtime.JavaAclFileAttributeView"
                                                   :dotnet.type/acl-file-attribute-view]
   "java.nio.file.attribute.FileAttribute" ["global::DripSharp.Runtime.JavaFileAttribute"
                                            :dotnet.type/file-attribute]
   "java.nio.file.attribute.FileOwnerAttributeView" ["global::DripSharp.Runtime.JavaAclFileAttributeView"
                                                     :dotnet.type/file-owner-attribute-view]
   "java.nio.file.attribute.PosixFilePermission" ["global::System.IO.UnixFileMode"
                                                  :dotnet.type/unix-file-mode]
   "java.nio.file.attribute.PosixFilePermissions" ["global::DripSharp.Runtime.JavaCompat"
                                                   :dotnet.type/java-compat]
   "java.nio.file.attribute.UserPrincipal" ["global::DripSharp.Runtime.JavaUserPrincipal"
                                            :dotnet.type/user-principal]

   "java.security.AccessController" ["global::DripSharp.Runtime.JavaCompat"
                                     :dotnet.type/java-compat]
   "java.security.PrivilegedAction" ["global::System.Func"
                                     :dotnet.type/func]

   "java.sql.Date" ["global::DripSharp.Runtime.JavaSqlDate"
                    :dotnet.type/java-sql-date]
   "java.sql.Time" ["global::DripSharp.Runtime.JavaSqlTime"
                    :dotnet.type/java-sql-time]
   "java.sql.Timestamp" ["global::DripSharp.Runtime.JavaSqlTimestamp"
                         :dotnet.type/java-sql-timestamp]

   "java.time.Duration" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.Instant" ["global::System.DateTimeOffset" :dotnet.type/date-time-offset]
   "java.time.ZoneId" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.ZoneOffset" ["global::System.TimeSpan" :dotnet.type/time-span]
   "java.time.ZonedDateTime" ["global::System.DateTimeOffset" :dotnet.type/date-time-offset]
   "java.time.format.DateTimeFormatter" ["global::DripSharp.Runtime.JavaDateTimeFormatter"
                                         :dotnet.type/date-time-formatter]
   "java.time.format.DateTimeFormatterBuilder"
   ["global::DripSharp.Runtime.JavaDateTimeFormatterBuilder"
    :dotnet.type/date-time-formatter-builder]
   "java.time.format.DateTimeParseException" ["global::System.FormatException"
                                              :dotnet.type/format-exception]
   "java.time.LocalDateTime" ["global::System.DateTime"
                              :dotnet.type/local-date-time]
   "java.time.Month" ["int" :dotnet.type/month-number]
   "java.time.temporal.TemporalAccessor" ["global::System.DateTimeOffset"
                                          :dotnet.type/date-time-offset]
   "java.time.temporal.TemporalAmount" ["global::System.TimeSpan" :dotnet.type/time-span]

   "java.math.BigDecimal" ["decimal" :dotnet.type/decimal]
   "java.math.RoundingMode" ["global::DripSharp.Runtime.JavaRoundingMode"
                             :dotnet.type/rounding-mode]

   "java.text.Bidi" ["global::DripSharp.Runtime.JavaBidi"
                     :dotnet.type/bidi]
   "java.text.DecimalFormat" ["global::DripSharp.Runtime.JavaDecimalFormat"
                              :dotnet.type/decimal-format]
   "java.text.DecimalFormatSymbols" ["global::System.Globalization.NumberFormatInfo"
                                     :dotnet.type/number-format]
   "java.text.NumberFormat" ["global::DripSharp.Runtime.JavaDecimalFormat"
                             :dotnet.type/decimal-format]
   "java.text.Normalizer" ["global::DripSharp.Runtime.JavaCompat"
                           :dotnet.type/java-compat]
   "java.text.Normalizer$Form" ["global::System.Text.NormalizationForm"
                                :dotnet.type/normalization-form]
   "java.text.DateFormat" ["global::DripSharp.Runtime.JavaSimpleDateFormat"
                           :dotnet.type/date-format]
   "java.text.SimpleDateFormat" ["global::DripSharp.Runtime.JavaSimpleDateFormat"
                                 :dotnet.type/simple-date-format]
   "java.text.ParsePosition" ["global::DripSharp.Runtime.JavaParsePosition"
                              :dotnet.type/parse-position]

   "java.util.AbstractMap" ["global::System.Collections.Generic.IDictionary"
                            :dotnet.type/map-interface]
   "java.util.ArrayList" ["global::System.Collections.Generic.List"
                          :dotnet.type/list]
   "java.util.ArrayDeque" ["global::DripSharp.Runtime.JavaDeque"
                           :dotnet.type/deque]
   "java.util.Arrays" ["global::DripSharp.Runtime.JavaCompat"
                       :dotnet.type/java-compat]
   "java.util.AbstractCollection" ["global::System.Collections.Generic.ICollection"
                                   :dotnet.type/collection]
   "java.util.AbstractQueue" ["global::DripSharp.Runtime.JavaPriorityQueue"
                              :dotnet.type/priority-queue]
   "java.util.BitSet" ["global::DripSharp.Runtime.JavaBitSet"
                       :dotnet.type/bit-set]
   "java.util.Collection" ["global::System.Collections.Generic.ICollection"
                           :dotnet.type/collection]
   "java.util.Collections" ["global::DripSharp.Runtime.JavaCompat"
                            :dotnet.type/java-compat]
   "java.util.Comparator" ["global::System.Collections.Generic.IComparer"
                           :dotnet.type/comparer]
   "java.util.Deque" ["global::DripSharp.Runtime.JavaDeque"
                      :dotnet.type/deque]
   "java.util.EnumMap" ["global::System.Collections.Generic.Dictionary"
                        :dotnet.type/dictionary]
   "java.util.EnumSet" ["global::System.Collections.Generic.ISet"
                        :dotnet.type/set-interface]
   "java.util.Enumeration" ["global::DripSharp.Runtime.JavaIterator"
                            :dotnet.type/java-enumeration]
   "java.util.HashMap" ["global::System.Collections.Generic.Dictionary"
                        :dotnet.type/dictionary]
   "java.util.HashSet" ["global::System.Collections.Generic.HashSet"
                        :dotnet.type/hash-set]
   "java.util.Hashtable" ["global::DripSharp.Runtime.JavaHashtable"
                          :dotnet.type/hashtable]
   "java.util.Iterator" ["global::DripSharp.Runtime.JavaIterator"
                         :dotnet.type/java-iterator]
   "java.util.PrimitiveIterator"
   ["global::System.Collections.IEnumerator"
    :dotnet.type/primitive-iterator]
   "java.util.PrimitiveIterator$OfInt"
   ["global::DripSharp.Runtime.JavaIterator<int>"
    :dotnet.type/java-int-iterator]
   "java.util.PrimitiveIterator$OfLong"
   ["global::DripSharp.Runtime.JavaIterator<long>"
    :dotnet.type/java-long-iterator]
   "java.util.IdentityHashMap" ["global::DripSharp.Runtime.JavaIdentityHashMap"
                                :dotnet.type/identity-map]
   "java.util.LinkedHashMap" ["global::DripSharp.Runtime.JavaLinkedHashMap"
                              :dotnet.type/linked-dictionary]
   "java.util.LinkedHashSet" ["global::System.Collections.Generic.HashSet"
                              :dotnet.type/linked-hash-set]
   "java.util.LinkedList" ["global::System.Collections.Generic.List"
                           :dotnet.type/list]
   "java.util.List" ["global::System.Collections.Generic.IList"
                     :dotnet.type/list-interface]
   "java.util.ListIterator" ["global::DripSharp.Runtime.JavaListIterator"
                             :dotnet.type/java-list-iterator]
   "java.util.NavigableSet" ["global::System.Collections.Generic.SortedSet"
                             :dotnet.type/navigable-set]
   "java.util.Map" ["global::System.Collections.Generic.IDictionary"
                    :dotnet.type/map-interface]
   "java.util.Map$Entry" ["global::DripSharp.Runtime.JavaMapEntry"
                          :dotnet.type/map-entry]
   "java.util.AbstractMap$SimpleEntry" ["global::DripSharp.Runtime.JavaSimpleEntry"
                                        :dotnet.type/simple-map-entry]
   "java.util.AbstractMap$SimpleImmutableEntry" ["global::DripSharp.Runtime.JavaSimpleImmutableEntry"
                                                 :dotnet.type/simple-immutable-map-entry]
   "java.util.NoSuchElementException" ["global::System.InvalidOperationException"
                                       :dotnet.type/invalid-operation]
   "java.util.Calendar" ["global::System.DateTimeOffset"
                         :dotnet.type/date-time-offset]
   "java.util.GregorianCalendar" ["global::System.DateTimeOffset"
                                  :dotnet.type/date-time-offset]
   "java.util.Date" ["global::System.DateTimeOffset?"
                     :dotnet.type/nullable-date-time-offset]
   "java.util.Locale" ["global::System.Globalization.CultureInfo"
                       :dotnet.type/culture-info]
   "java.util.Objects" ["global::DripSharp.Runtime.JavaCompat"
                        :dotnet.type/java-compat]
   "java.util.Optional" ["global::DripSharp.Runtime.JavaOptional"
                         :dotnet.type/optional]
   "java.util.OptionalInt" ["int?" :dotnet.type/nullable-int]
   "java.util.OptionalLong" ["long?" :dotnet.type/nullable-long]
   "java.util.PriorityQueue" ["global::DripSharp.Runtime.JavaPriorityQueue"
                              :dotnet.type/priority-queue]
   "java.util.Properties" ["global::DripSharp.Runtime.JavaProperties"
                           :dotnet.type/properties]
   "java.util.Queue" ["global::DripSharp.Runtime.JavaDeque"
                      :dotnet.type/queue]
   "java.util.Random" ["global::DripSharp.Runtime.JavaRandom"
                       :dotnet.type/random]
   "java.util.ServiceLoader" ["global::System.Collections.Generic.IEnumerable"
                              :dotnet.type/service-loader]
   "java.util.Set" ["global::System.Collections.Generic.ISet"
                    :dotnet.type/set-interface]
   "java.util.SortedMap" ["global::System.Collections.Generic.IDictionary"
                          :dotnet.type/map-interface]
   "java.util.SortedSet" ["global::System.Collections.Generic.ISet"
                          :dotnet.type/set-interface]
   "java.util.StringJoiner" ["global::DripSharp.Runtime.JavaStringJoiner"
                             :dotnet.type/string-joiner]
   "java.util.Stack" ["global::DripSharp.Runtime.JavaStack"
                      :dotnet.type/stack]
   "java.util.Vector" ["global::DripSharp.Runtime.JavaStack"
                       :dotnet.type/vector]
   "java.util.StringTokenizer" ["global::DripSharp.Runtime.JavaStringTokenizer"
                                :dotnet.type/string-tokenizer]
   "java.util.TimeZone" ["global::System.TimeZoneInfo"
                         :dotnet.type/time-zone-info]
   "java.util.SimpleTimeZone" ["global::System.TimeZoneInfo"
                               :dotnet.type/time-zone-info]
   "java.util.TreeMap" ["global::System.Collections.Generic.SortedDictionary"
                        :dotnet.type/sorted-dictionary]
   "java.util.WeakHashMap" ["global::DripSharp.Runtime.JavaWeakHashMap"
                            :dotnet.type/weak-map]
   "java.util.Base64" ["global::DripSharp.Runtime.JavaBase64"
                       :dotnet.type/base64]
   "java.util.Base64$Decoder" ["global::DripSharp.Runtime.JavaBase64Decoder"
                               :dotnet.type/base64-decoder]
   "java.util.Base64$Encoder" ["global::DripSharp.Runtime.JavaBase64Encoder"
                               :dotnet.type/base64-encoder]
   "java.util.TreeSet" ["global::System.Collections.Generic.SortedSet"
                        :dotnet.type/sorted-set]

   "java.util.concurrent.Callable" ["global::System.Func" :dotnet.type/func]
   "java.util.concurrent.ConcurrentHashMap" ["global::System.Collections.Concurrent.ConcurrentDictionary"
                                             :dotnet.type/concurrent-dictionary]
   "java.util.concurrent.ConcurrentMap" ["global::System.Collections.Concurrent.ConcurrentDictionary"
                                         :dotnet.type/concurrent-dictionary]
   "java.util.concurrent.Executor" ["global::DripSharp.Runtime.JavaExecutorService"
                                    :dotnet.type/executor]
   "java.util.concurrent.ExecutorService" ["global::DripSharp.Runtime.JavaExecutorService"
                                           :dotnet.type/executor-service]
   "java.util.concurrent.ExecutionException" ["global::System.AggregateException"
                                              :dotnet.type/execution-exception]
   "java.util.concurrent.Executors" ["global::DripSharp.Runtime.JavaCompat"
                                     :dotnet.type/java-compat]
   "java.util.concurrent.Future" ["global::DripSharp.Runtime.JavaFuture"
                                  :dotnet.type/future]
   "java.util.concurrent.CompletableFuture" ["global::DripSharp.Runtime.JavaFuture"
                                             :dotnet.type/completable-future]
   "java.util.concurrent.ThreadFactory" ["global::DripSharp.Runtime.JavaThreadFactory"
                                         :dotnet.type/thread-factory]
   "java.util.concurrent.TimeUnit" ["global::DripSharp.Runtime.JavaTimeUnit"
                                    :dotnet.type/time-unit]
   "java.util.concurrent.TimeoutException" ["global::System.TimeoutException"
                                            :dotnet.type/timeout-exception]
   "java.util.concurrent.atomic.AtomicBoolean" ["global::DripSharp.Runtime.JavaAtomicBoolean"
                                                :dotnet.type/atomic-boolean]
   "java.util.concurrent.atomic.AtomicInteger" ["global::DripSharp.Runtime.JavaAtomicInteger"
                                                :dotnet.type/atomic-integer]
   "java.util.concurrent.atomic.AtomicReference" ["global::DripSharp.Runtime.JavaAtomicReference"
                                                  :dotnet.type/atomic-reference]
   "java.util.function.BiConsumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.BiFunction" ["global::System.Func" :dotnet.type/func]
   "java.util.function.BinaryOperator" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Consumer" ["global::System.Action" :dotnet.type/action]
   "java.util.function.Function" ["global::System.Func" :dotnet.type/func]
   "java.util.function.LongConsumer" ["global::System.Action<long>"
                                      :dotnet.type/long-consumer]
   "java.util.function.Predicate" ["global::System.Func" :dotnet.type/func]
   "java.util.function.Supplier" ["global::System.Func" :dotnet.type/func]
   "java.util.function.ToLongFunction" ["global::DripSharp.Runtime.JavaToLongFunction"
                                        :dotnet.type/to-long-function]
   "java.util.regex.Pattern" ["global::System.Text.RegularExpressions.Regex"
                              :dotnet.type/regex]
   "java.util.regex.Matcher" ["global::DripSharp.Runtime.JavaRegexMatcher"
                              :dotnet.type/regex-matcher]
   "java.util.stream.Stream" ["global::DripSharp.Runtime.JavaStream"
                              :dotnet.type/java-stream]
   "java.util.stream.IntStream" ["global::System.Collections.Generic.IEnumerable<int>"
                                 :dotnet.type/int-stream]
   "java.util.stream.LongStream" ["global::System.Collections.Generic.IEnumerable<long>"
                                  :dotnet.type/long-stream]
   "java.util.stream.Collector" ["global::DripSharp.Runtime.JavaCollector"
                                 :dotnet.type/java-collector]
   "java.util.stream.Collectors" ["global::DripSharp.Runtime.JavaCompat"
                                  :dotnet.type/java-compat]

   "java.util.zip.GZIPInputStream" ["global::System.IO.Compression.GZipStream"
                                    :dotnet.type/gzip-stream]
   "java.util.zip.CRC32" ["global::DripSharp.Runtime.JavaCrc32"
                          :dotnet.type/crc32]
   "java.util.zip.InflaterOutputStream" ["global::DripSharp.Runtime.JavaInflaterOutputStream"
                                         :dotnet.type/inflater-output-stream]
   "java.util.zip.Inflater" ["global::DripSharp.Runtime.JavaInflater"
                             :dotnet.type/inflater]
   "java.util.zip.Deflater" ["global::DripSharp.Runtime.JavaDeflater"
                             :dotnet.type/deflater]
   "java.util.zip.DeflaterOutputStream"
   ["global::DripSharp.Runtime.JavaDeflaterOutputStream"
    :dotnet.type/deflater-output-stream]
   "java.util.zip.DataFormatException" ["global::System.IO.InvalidDataException"
                                        :dotnet.type/invalid-data-exception]

   "javax.annotation.Nullable" ["global::System.Diagnostics.CodeAnalysis.MaybeNullAttribute"
                                :dotnet.type/nullable-annotation]
   "javax.annotation.Nonnull" ["global::System.Diagnostics.CodeAnalysis.NotNullAttribute"
                               :dotnet.type/nonnullable-annotation]
   "javax.xml.XMLConstants" ["global::DripSharp.Runtime.JavaCompat"
                             :dotnet.type/xml-constants]
   "javax.xml.namespace.QName" ["global::System.Xml.XmlQualifiedName"
                                :dotnet.type/xml-qualified-name]
   "javax.xml.parsers.DocumentBuilder" ["global::System.Xml.XmlReaderSettings"
                                        :dotnet.type/xml-reader-settings]
   "javax.xml.parsers.DocumentBuilderFactory" ["global::System.Xml.XmlReaderSettings"
                                               :dotnet.type/xml-reader-settings]
   "javax.xml.parsers.ParserConfigurationException" ["global::System.Xml.XmlException"
                                                     :dotnet.type/xml-exception]
   "javax.xml.parsers.FactoryConfigurationError" ["global::System.Xml.XmlException"
                                                  :dotnet.type/xml-exception]
   "javax.xml.xpath.XPathFactory" ["global::DripSharp.Runtime.JavaXPathFactory"
                                   :dotnet.type/xpath-factory]
   "javax.xml.xpath.XPath" ["global::DripSharp.Runtime.JavaXPath"
                            :dotnet.type/xpath]
   "javax.xml.xpath.XPathConstants" ["global::DripSharp.Runtime.JavaXPathConstants"
                                     :dotnet.type/xpath-constants]
   "javax.xml.xpath.XPathExpressionException"
   ["global::System.Xml.XPath.XPathException" :dotnet.type/xpath-exception]
   "javax.xml.transform.OutputKeys" ["global::DripSharp.Runtime.JavaCompat"
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
   "org.w3c.dom.CharacterData" ["global::System.Xml.XmlCharacterData"
                                :dotnet.type/xml-character-data]
   "org.w3c.dom.CDATASection" ["global::System.Xml.XmlCDataSection"
                               :dotnet.type/xml-cdata-section]
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
   "javax.net.ServerSocketFactory" ["global::DripSharp.Runtime.JavaSslServerSocketFactory"
                                    :dotnet.type/server-socket-factory]
   "javax.net.SocketFactory" ["global::DripSharp.Runtime.JavaSocketFactory"
                              :dotnet.type/socket-factory]

   "java.security.KeyManagementException" ["global::System.Security.Cryptography.CryptographicException"
                                           :dotnet.type/cryptographic-exception]
   "java.security.AccessControlException" ["global::System.UnauthorizedAccessException"
                                           :dotnet.type/unauthorized-access-exception]
   "java.security.GeneralSecurityException" ["global::System.Security.Cryptography.CryptographicException"
                                             :dotnet.type/cryptographic-exception]
   "java.security.KeyStoreException" ["global::System.Security.Cryptography.CryptographicException"
                                      :dotnet.type/cryptographic-exception]
   "java.security.InvalidKeyException" ["global::System.Security.Cryptography.CryptographicException"
                                        :dotnet.type/cryptographic-exception]
   "java.security.NoSuchAlgorithmException" ["global::DripSharp.Runtime.JavaNoSuchAlgorithmException"
                                             :dotnet.type/no-such-algorithm-exception]
   "java.security.UnrecoverableKeyException" ["global::DripSharp.Runtime.JavaUnrecoverableKeyException"
                                              :dotnet.type/unrecoverable-key-exception]
   "java.security.KeyStore" ["global::DripSharp.Runtime.JavaKeyStore"
                             :dotnet.type/key-store]
   "java.security.Key" ["object" :dotnet.type/security-key]
   "java.security.PrivateKey" ["global::System.Security.Cryptography.AsymmetricAlgorithm"
                               :dotnet.type/private-key]
   "java.security.PublicKey" ["global::System.Security.Cryptography.AsymmetricAlgorithm"
                              :dotnet.type/public-key]
   "java.security.Provider" ["global::DripSharp.Runtime.JavaSecurityProvider"
                             :dotnet.type/security-provider]
   "java.security.AlgorithmParameterGenerator"
   ["global::DripSharp.Runtime.JavaAlgorithmParameterGenerator"
    :dotnet.type/algorithm-parameter-generator]
   "java.security.AlgorithmParameters"
   ["global::DripSharp.Runtime.JavaAlgorithmParameters"
    :dotnet.type/algorithm-parameters]
   "java.security.MessageDigest" ["global::DripSharp.Runtime.JavaMessageDigest"
                                  :dotnet.type/message-digest]
   "java.security.SecureRandom" ["global::DripSharp.Runtime.JavaRandom"
                                 :dotnet.type/secure-random]
   "java.security.spec.AlgorithmParameterSpec" ["global::DripSharp.Runtime.JavaIvParameterSpec"
                                                :dotnet.type/algorithm-parameter-spec]
   "java.security.cert.CertificateException" ["global::System.Security.Cryptography.CryptographicException"
                                              :dotnet.type/cryptographic-exception]
   "java.security.cert.CertificateEncodingException"
   ["global::System.Security.Cryptography.CryptographicException"
    :dotnet.type/cryptographic-exception]
   "java.security.cert.CertificateFactory"
   ["global::DripSharp.Runtime.JavaCertificateFactory"
    :dotnet.type/certificate-factory]
   "java.security.cert.Certificate"
   ["global::System.Security.Cryptography.X509Certificates.X509Certificate2"
    :dotnet.type/certificate]
   "java.security.cert.X509Certificate" ["global::System.Security.Cryptography.X509Certificates.X509Certificate2"
                                         :dotnet.type/x509-certificate]
   "java.awt.color.ProfileDataException" ["global::System.ArgumentException"
                                          :dotnet.type/invalid-profile-data]
   "java.awt.color.CMMException" ["global::System.InvalidOperationException"
                                  :dotnet.type/color-management-exception]

   "javax.crypto.BadPaddingException" ["global::System.Security.Cryptography.CryptographicException"
                                       :dotnet.type/cryptographic-exception]
   "javax.crypto.Cipher" ["global::DripSharp.Runtime.JavaCipher"
                          :dotnet.type/cipher]
   "javax.crypto.CipherInputStream" ["global::DripSharp.Runtime.JavaCipherInputStream"
                                     :dotnet.type/cipher-input-stream]
   "javax.crypto.IllegalBlockSizeException" ["global::System.Security.Cryptography.CryptographicException"
                                             :dotnet.type/cryptographic-exception]
   "javax.crypto.KeyGenerator" ["global::DripSharp.Runtime.JavaKeyGenerator"
                                :dotnet.type/key-generator]
   "javax.crypto.NoSuchPaddingException" ["global::DripSharp.Runtime.JavaNoSuchPaddingException"
                                          :dotnet.type/no-such-padding-exception]
   "javax.crypto.SecretKey" ["global::DripSharp.Runtime.JavaSecretKey"
                             :dotnet.type/secret-key]
   "javax.crypto.spec.IvParameterSpec" ["global::DripSharp.Runtime.JavaIvParameterSpec"
                                        :dotnet.type/iv-parameter-spec]
   "javax.crypto.spec.SecretKeySpec" ["global::DripSharp.Runtime.JavaSecretKeySpec"
                                      :dotnet.type/secret-key-spec]

   "javax.net.ssl.KeyManager" ["object" :dotnet.type/key-manager]
   "javax.net.ssl.KeyManagerFactory" ["global::DripSharp.Runtime.JavaKeyManagerFactory"
                                      :dotnet.type/key-manager-factory]
   "javax.net.ssl.SSLContext" ["global::DripSharp.Runtime.JavaSslContext"
                               :dotnet.type/ssl-context]
   "javax.net.ssl.SSLServerSocketFactory" ["global::DripSharp.Runtime.JavaSslServerSocketFactory"
                                           :dotnet.type/ssl-server-socket-factory]
   "javax.net.ssl.SSLSocket" ["global::System.Net.Sockets.Socket"
                              :dotnet.type/ssl-socket]
   "javax.net.ssl.SSLSocketFactory" ["global::DripSharp.Runtime.JavaSocketFactory"
                                     :dotnet.type/ssl-socket-factory]
   "javax.net.ssl.TrustManager" ["object" :dotnet.type/trust-manager]
   "javax.net.ssl.TrustManagerFactory" ["global::DripSharp.Runtime.JavaTrustManagerFactory"
                                        :dotnet.type/trust-manager-factory]
   "javax.net.ssl.X509TrustManager" ["global::DripSharp.Runtime.JavaX509TrustManager"
                                     :dotnet.type/x509-trust-manager]})

(def ^:private caveats-by-type
  {"java.lang.Throwable"
   #{:exception-hierarchy-collapse :usage-dependent-approximation}
   "java.lang.Exception"
   #{:exception-hierarchy-collapse :usage-dependent-approximation}
   "java.lang.RuntimeException"
   #{:exception-hierarchy-collapse :usage-dependent-approximation}
   "java.lang.Error"
   #{:exception-hierarchy-collapse :usage-dependent-approximation}

   "java.util.LinkedHashSet"
   #{:deterministic-iteration-order-loss :usage-dependent-approximation}
   "java.util.EnumMap"
   #{:ordering-difference :usage-dependent-approximation}

   "java.io.Writer"
   #{:writer-contract-difference :usage-dependent-approximation}
   "java.io.BufferedWriter"
   #{:writer-contract-difference :usage-dependent-approximation}
   "java.io.InputStream"
   #{:stream-contract-difference :usage-dependent-approximation}
   "java.io.OutputStream"
   #{:stream-contract-difference :usage-dependent-approximation}
   "java.io.BufferedInputStream"
   #{:stream-contract-difference :usage-dependent-approximation}
   "java.io.BufferedOutputStream"
   #{:stream-contract-difference :usage-dependent-approximation}
   "java.io.FileInputStream"
   #{:stream-contract-difference :usage-dependent-approximation}
   "java.io.FileOutputStream"
   #{:stream-contract-difference :usage-dependent-approximation}
   "java.io.SequenceInputStream"
   #{:stream-contract-difference :usage-dependent-approximation}
   "java.io.PrintStream"
   #{:stream-writer-contract-difference :usage-dependent-approximation}
   "java.io.PrintWriter"
   #{:writer-contract-difference :usage-dependent-approximation}

   "java.util.Calendar"
   #{:calendar-model-difference :usage-dependent-approximation}
   "java.util.GregorianCalendar"
   #{:calendar-model-difference :usage-dependent-approximation}})

(defn- registry-identity
  [qualified-name]
  (keyword "java.type" qualified-name))

(def entries
  "Declarative context-free Java type mappings.

  The private source specification retains the historical translation-rule
  identity returned by `mapping`; the validated registry identity is exact and
  unique per resolved Java type."
  (mapv
   (fn [[qualified-name [destination _mapping-rule]]]
     (let [caveats (get caveats-by-type qualified-name #{})]
       {:id (registry-identity qualified-name)
        :key (str "type:" qualified-name)
        :strategy :rename
        :destination destination
        :caveats caveats
        :introduced-by :rawhttp
        :evidence (cond-> #{:test/shared-java-library}
                    (seq caveats) (conj :review/java-type-approximations))}))
   (sort-by key mapping-specifications)))

(def registry
  "Validated, deterministic context-free Java type registry."
  (mapping-registry/compile-registry entries))

(defn mapping-entry
  "Returns the validated declarative entry for an exact Java qualified name."
  [qualified-name]
  (mapping-registry/registry-entry registry (str "type:" qualified-name)))

(defn mapping
  "Returns [destination-type mapping-rule] for an exact Java qualified name."
  [qualified-name]
  (when-let [entry (mapping-entry qualified-name)]
    [(:destination entry)
     (second (get mapping-specifications qualified-name))]))

(defn mapped-identities
  "Returns the stable sorted Java identities covered by the neutral registry."
  []
  (vec (sort (keys mapping-specifications))))

(defn product-neutral?
  "Checks that destination shapes contain no supplied product identity
  fragment. Provenance metadata may name the target that introduced an entry."
  [fragment]
  (let [fragment (str/lower-case (str fragment))]
    (not-any?
     #(str/includes? (str/lower-case (:destination %)) fragment)
     entries)))
