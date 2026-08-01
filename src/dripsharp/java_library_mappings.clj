(ns dripsharp.java-library-mappings
  "Small declarative registries for reusable Java-library member mappings.

  This namespace owns mapping data only.  The shared Java-library bundle
  supplies the explicitly named custom handlers when it compiles the combined
  registry."
  (:require [dripsharp.java-mapping-registry :as mapping-registry]))

(defn- entry
  [id key strategy fields caveats evidence]
  (merge {:id id
          :key key
          :strategy strategy
          :caveats caveats
          :introduced-by :rawhttp
          :evidence evidence}
         fields))

(defn- rename
  [id key destination]
  (entry id key :rename {:destination destination}
         #{} #{:test/shared-java-library}))

(defn- property
  [id key destination]
  (entry id key :property-access {:destination destination}
         #{} #{:test/shared-java-library}))

(defn- compat-call
  ([id key destination]
   (compat-call id key destination #{}))
  ([id key destination caveats]
   (entry id key :compat-call {:destination destination}
          caveats
          (cond-> #{:test/shared-java-library}
            (seq caveats) (conj :differential/shared-java-library)))))

(defn- static-call
  [id key destination]
  (entry id key :argument-reshape
         {:destination destination
          :call :static
          :arguments [:arguments]}
         #{} #{:test/shared-java-library}))

(defn- custom
  [id key handler caveats evidence]
  (entry id key :custom-handler {:handler handler}
         caveats evidence))

(defn- constant
  [id key destination caveats]
  (entry id key :template {:template [destination]}
         caveats
         (cond-> #{:test/shared-java-library}
           (seq caveats) (conj :differential/shared-java-library))))

(defn- field-id
  [key]
  (let [[owner member] (rest (re-matches #"^field:([^#]+)#(.+)$" key))]
    (keyword (str "java.field." owner) member)))

(def lang-entries
  [(compat-call
    :java.lang.boolean/string-value
    "executable:java.lang.Boolean#toString()"
    "global::DripSharp.Runtime.JavaCompat.StringValueOf")
   (static-call
    :java.lang.boolean/string-value-static
    "executable:java.lang.Boolean#toString(boolean)"
    "global::DripSharp.Runtime.JavaCompat.StringValueOf")
   (compat-call
    :java.lang.double/string-value
    "executable:java.lang.Double#toString()"
    "global::DripSharp.Runtime.JavaCompat.StringValueOf")
   (static-call
    :java.lang.integer/compare
    "executable:java.lang.Integer#compare(int,int)"
    "global::DripSharp.Runtime.JavaCompat.CompareInt")
   (static-call
    :java.lang.long/compare
    "executable:java.lang.Long#compare(long,long)"
    "global::DripSharp.Runtime.JavaCompat.CompareLong")
   (static-call
    :java.lang.float/compare
    "executable:java.lang.Float#compare(float,float)"
    "global::DripSharp.Runtime.JavaCompat.CompareFloat")
   (static-call
    :java.lang.double/compare
    "executable:java.lang.Double#compare(double,double)"
    "global::DripSharp.Runtime.JavaCompat.CompareDouble")
   (compat-call
    :java.lang.class/get-constructor
    "executable:java.lang.Class#getConstructor(java.lang.Class[])"
    "global::DripSharp.Runtime.JavaCompat.ClassGetConstructor")
   (custom
    :java.lang.string/index-of-from
    "executable:java.lang.String#indexOf(java.lang.String,int)"
    :java-library.mapping/string-index-of-from
    #{}
    #{:test/shared-java-library})
   (static-call
    :java.util.objects/to-string-default
    "executable:java.util.Objects#toString(java.lang.Object,java.lang.String)"
    "global::DripSharp.Runtime.JavaCompat.ObjectsToString")
   (custom
    :java.lang.enum/value-of
    "executable:java.lang.Enum#valueOf(java.lang.Class,java.lang.String)"
    :java-library.mapping/enum-value-of
    #{}
    #{:test/shared-java-library})
   (static-call
    :java.util.logging.logger/get-logger
    "executable:java.util.logging.Logger#getLogger(java.lang.String)"
    "global::DripSharp.Runtime.JavaLogger.GetLogger")
   (rename
    :java.util.logging.logger/log
    "executable:java.util.logging.Logger#log(java.util.logging.Level,java.lang.String)"
    "Log")
   (rename
    :java.util.logging.logger/log-with-error
    "executable:java.util.logging.Logger#log(java.util.logging.Level,java.lang.String,java.lang.Throwable)"
    "Log")])

(def sql-entries
  [(static-call
    :java.sql.date/value-of
    "executable:java.sql.Date#valueOf(java.lang.String)"
    "global::DripSharp.Runtime.JavaSqlDate.ValueOf")
   (rename
    :java.sql.date/to-string
    "executable:java.sql.Date#toString()"
    "ToString")
   (static-call
    :java.sql.time/value-of
    "executable:java.sql.Time#valueOf(java.lang.String)"
    "global::DripSharp.Runtime.JavaSqlTime.ValueOf")
   (rename
    :java.sql.time/to-string
    "executable:java.sql.Time#toString()"
    "ToString")
   (static-call
    :java.sql.timestamp/value-of
    "executable:java.sql.Timestamp#valueOf(java.lang.String)"
    "global::DripSharp.Runtime.JavaSqlTimestamp.ValueOf")
   (rename
    :java.sql.timestamp/to-string
    "executable:java.sql.Timestamp#toString()"
    "ToString")])

(def io-entries
  [(property
    :java.io.file/name
    "executable:java.io.File#getName()"
    "Name")
   (rename
    :java.io.buffered-reader/read-line
    "executable:java.io.BufferedReader#readLine()"
    "ReadLine")
   (rename
    :java.io.line-number-reader/read-line
    "executable:java.io.LineNumberReader#readLine()"
    "ReadLine")
   (rename
    :java.io.writer/write-string
    "executable:java.io.Writer#write(java.lang.String)"
    "Write")
   (rename
    :java.io.string-writer/write-string
    "executable:java.io.StringWriter#write(java.lang.String)"
    "Write")
   (rename
    :java.io.writer/flush
    "executable:java.io.Writer#flush()"
    "Flush")
   (rename
    :java.io.writer/close
    "executable:java.io.Writer#close()"
    "Dispose")
   (rename
    :java.io.print-writer/println-string
    "executable:java.io.PrintWriter#println(java.lang.String)"
    "WriteLine")
   (rename
    :java.io.print-writer/flush
    "executable:java.io.PrintWriter#flush()"
    "Flush")
   (rename
    :java.io.print-writer/close
    "executable:java.io.PrintWriter#close()"
    "Dispose")
   (rename
    :java.io.print-stream/print-string
    "executable:java.io.PrintStream#print(java.lang.String)"
    "Write")
   (rename
    :java.io.print-stream/println
    "executable:java.io.PrintStream#println()"
    "WriteLine")
   (rename
    :java.io.print-stream/flush
    "executable:java.io.PrintStream#flush()"
    "Flush")])

(def collection-entries
  [(compat-call
    :java.util.list/size
    "executable:java.util.List#size()"
    "global::DripSharp.Runtime.JavaCompat.CollectionCount")
   (compat-call
    :java.util.array-list/size
    "executable:java.util.ArrayList#size()"
    "global::DripSharp.Runtime.JavaCompat.CollectionCount")
   (compat-call
    :java.util.list/get
    "executable:java.util.List#get(int)"
    "global::DripSharp.Runtime.JavaCompat.ListGet")
   (compat-call
    :java.util.array-list/get
    "executable:java.util.ArrayList#get(int)"
    "global::DripSharp.Runtime.JavaCompat.ListGet")
   (custom
    :java.util.array-list/spliterator
    "executable:java.util.ArrayList#spliterator()"
    :java-library.mapping/array-list-spliterator
    #{}
    #{:test/shared-java-library})
   (custom
    :java.util.list/spliterator
    "executable:java.util.List#spliterator()"
    :java-library.mapping/array-list-spliterator
    #{}
    #{:test/shared-java-library})
   (compat-call
    :java.util.array-list/replace-all
    "executable:java.util.ArrayList#replaceAll(java.util.function.UnaryOperator)"
    "global::DripSharp.Runtime.JavaCompat.ReplaceAll")
   (compat-call
    :java.util.list/replace-all
    "executable:java.util.List#replaceAll(java.util.function.UnaryOperator)"
    "global::DripSharp.Runtime.JavaCompat.ReplaceAll")
   (compat-call
    :java.util.stream.base-stream/iterator
    "executable:java.util.stream.BaseStream#iterator()"
    "global::DripSharp.Runtime.JavaCompat.Iterator")
   (rename
    :java.util.array-list/trim-to-size
    "executable:java.util.ArrayList#trimToSize()"
    "TrimExcess")
   (compat-call
    :java.util.collection/to-array-generator
    "executable:java.util.Collection#toArray(java.util.function.IntFunction)"
    "global::DripSharp.Runtime.JavaCompat.CollectionToArray")
   (compat-call
    :java.util.linked-list/get
    "executable:java.util.LinkedList#get(int)"
    "global::DripSharp.Runtime.JavaCompat.ListGet")
   (compat-call
    :java.util.list/add
    "executable:java.util.List#add(java.lang.Object)"
    "global::DripSharp.Runtime.JavaCompat.Add")
   (compat-call
    :java.util.array-list/add
    "executable:java.util.ArrayList#add(java.lang.Object)"
    "global::DripSharp.Runtime.JavaCompat.Add")
   (compat-call
    :java.util.list/contains
    "executable:java.util.List#contains(java.lang.Object)"
    "global::DripSharp.Runtime.JavaCompat.CollectionContains")
   (static-call
    :java.util.collections/add-all
    "executable:java.util.Collections#addAll(java.util.Collection,java.lang.Object[])"
    "global::DripSharp.Runtime.JavaCompat.AddAll")
   (rename
    :java.util.list/clear
    "executable:java.util.List#clear()"
    "Clear")
   (rename
    :java.util.array-list/clear
    "executable:java.util.ArrayList#clear()"
    "Clear")
   (rename
    :java.util.iterator/next
    "executable:java.util.Iterator#next()"
    "Next")
   (rename
    :java.util.iterator/has-next
    "executable:java.util.Iterator#hasNext()"
    "HasNext")
   (rename
    :java.util.iterator/remove
    "executable:java.util.Iterator#remove()"
    "Remove")])

(def stream-entries
  [(compat-call
    :java.util.stream/stream-filter
    "executable:java.util.stream.Stream#filter(java.util.function.Predicate)"
    "global::DripSharp.Runtime.JavaCompat.StreamFilter"
    #{:stream-evaluation-difference})
   (compat-call
    :java.util.stream/stream-sorted
    "executable:java.util.stream.Stream#sorted()"
    "global::DripSharp.Runtime.JavaCompat.StreamSorted"
    #{:ordering-difference :stream-evaluation-difference})
   (compat-call
    :java.util.stream/stream-sorted-comparator
    "executable:java.util.stream.Stream#sorted(java.util.Comparator)"
    "global::DripSharp.Runtime.JavaCompat.StreamSorted"
    #{:ordering-difference :stream-evaluation-difference})
   (compat-call
    :java.util.stream/stream-for-each
    "executable:java.util.stream.Stream#forEach(java.util.function.Consumer)"
    "global::DripSharp.Runtime.JavaCompat.ForEach"
    #{:encounter-order-difference :stream-evaluation-difference})
   (compat-call
    :java.util.stream/stream-for-each-ordered
    "executable:java.util.stream.Stream#forEachOrdered(java.util.function.Consumer)"
    "global::DripSharp.Runtime.JavaCompat.ForEach"
    #{:stream-evaluation-difference})
   (custom
    :java.util.stream/stream-collect
    "executable:java.util.stream.Stream#collect(java.util.stream.Collector)"
    :java-library.mapping/stream-collect
    #{:collector-result-shape-dependent :stream-evaluation-difference}
    #{:differential/shared-java-library :test/shared-java-library})])

(def concurrency-entries
  [(custom
    :java.util.concurrent.atomic/atomic-reference-get
    "executable:java.util.concurrent.atomic.AtomicReference#get()"
    :java-library.mapping/atomic-reference-get
    #{:nullable-reference-projection}
    #{:differential/shared-java-library :test/shared-java-library})
   (rename
    :java.util.concurrent.atomic/atomic-boolean-get
    "executable:java.util.concurrent.atomic.AtomicBoolean#get()"
    "Get")
   (rename
    :java.util.concurrent.atomic/atomic-boolean-get-and-set
    "executable:java.util.concurrent.atomic.AtomicBoolean#getAndSet(boolean)"
    "GetAndSet")
   (rename
    :java.util.concurrent.atomic/atomic-boolean-compare-and-set
    "executable:java.util.concurrent.atomic.AtomicBoolean#compareAndSet(boolean,boolean)"
    "CompareAndSet")
   (rename
    :java.util.concurrent.atomic/atomic-integer-increment-and-get
    "executable:java.util.concurrent.atomic.AtomicInteger#incrementAndGet()"
    "IncrementAndGet")])

(defn-
  executable-id
  [kind key]
  (let
   [[_ owner member parameters] (re-matches #"^executable:([^#]+)#([^#]+)\((.*)\)$" key)]
    (keyword (str "java." (name kind) "." owner) (str member "(" parameters ")"))))

(def
  executable-handler-groups
  [[:java-library.mapping.executable/handler-0001
    ["executable:java.lang.ref.SoftReference#get()"
     "executable:java.lang.ref.WeakReference#get()"
     "executable:java.lang.ref.Reference#get()"]]
   [:java-library.mapping.executable/handler-0002 ["executable:java.lang.ref.Reference#clear()"]]
   [:java-library.mapping.executable/handler-0003
    ["executable:java.io.ByteArrayOutputStream#write(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0004
    ["executable:java.io.ByteArrayOutputStream#close()"]]
   [:java-library.mapping.executable/handler-0005
    ["executable:java.io.FilterOutputStream#write(byte[])"
     "executable:java.io.FilterOutputStream#write(byte[],int,int)"
     "executable:java.io.FilterOutputStream#write(int)"
     "executable:java.util.zip.DeflaterOutputStream#write(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0006
    ["executable:java.util.zip.DeflaterOutputStream#close()"]]
   [:java-library.mapping.executable/handler-0007 ["executable:java.util.zip.Deflater#end()"]]
   [:java-library.mapping.executable/handler-0008 ["executable:java.util.zip.Inflater#finished()"]]
   [:java-library.mapping.executable/handler-0009
    ["executable:java.util.zip.Inflater#needsInput()"]]
   [:java-library.mapping.executable/handler-0010
    ["executable:java.util.zip.Inflater#setInput(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0011
    ["executable:java.util.zip.Inflater#inflate(byte[])"]]
   [:java-library.mapping.executable/handler-0012 ["executable:java.util.zip.Inflater#end()"]]
   [:java-library.mapping.executable/handler-0013 ["executable:java.io.FilterInputStream#close()"]]
   [:java-library.mapping.executable/handler-0014
    ["executable:java.io.ByteArrayOutputStream#reset()"]]
   [:java-library.mapping.executable/handler-0015
    ["executable:java.io.ByteArrayOutputStream#toString(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0017 ["executable:java.io.BufferedReader#ready()"]]
   [:java-library.mapping.executable/handler-0018 ["executable:java.io.BufferedWriter#newLine()"]]
   [:java-library.mapping.executable/handler-0019
    ["executable:java.io.BufferedWriter#write(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0020
    ["executable:java.io.BufferedWriter#write(int)" "executable:java.io.Writer#write(int)"]]
   [:java-library.mapping.executable/handler-0021 ["executable:java.io.BufferedWriter#flush()"]]
   [:java-library.mapping.executable/handler-0022 ["executable:java.io.File#canRead()"]]
   [:java-library.mapping.executable/handler-0024 ["executable:java.io.File#getPath()"]]
   [:java-library.mapping.executable/handler-0025
    ["executable:java.io.File#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0026 ["executable:java.io.File#isHidden()"]]
   [:java-library.mapping.executable/handler-0027 ["executable:java.io.File#canWrite()"]]
   [:java-library.mapping.executable/handler-0028 ["executable:java.io.File#lastModified()"]]
   [:java-library.mapping.executable/handler-0029 ["executable:java.io.File#listFiles()"]]
   [:java-library.mapping.executable/handler-0030 ["executable:java.io.File#isFile()"]]
   [:java-library.mapping.executable/handler-0031 ["executable:java.io.File#toURI()"]]
   [:java-library.mapping.executable/handler-0032
    ["executable:java.io.InputStream#mark(int)" "executable:java.io.BufferedInputStream#mark(int)"]]
   [:java-library.mapping.executable/handler-0033
    ["executable:java.io.InputStream#markSupported()"
     "executable:java.io.BufferedInputStream#markSupported()"]]
   [:java-library.mapping.executable/handler-0034
    ["executable:java.io.InputStream#reset()"
     "executable:java.io.ByteArrayInputStream#reset()"
     "executable:java.io.BufferedInputStream#reset()"]]
   [:java-library.mapping.executable/handler-0035 ["executable:java.io.InputStream#skip(long)"]]
   [:java-library.mapping.executable/handler-0037
    ["executable:java.io.Reader#read(char[],int,int)"]]
   [:java-library.mapping.executable/handler-0038 ["executable:java.io.StringWriter#toString()"]]
   [:java-library.mapping.executable/handler-0040 ["executable:java.io.Writer#write(char[])"]]
   [:java-library.mapping.executable/handler-0041
    ["executable:java.io.Writer#append(java.lang.CharSequence)"
     "executable:java.io.Writer#append(char)"]]
   [:java-library.mapping.executable/handler-0050
    ["executable:java.lang.Process#isAlive()"
     "executable:java.lang.Process#getInputStream()"
     "executable:java.lang.Process#getOutputStream()"
     "executable:java.lang.Process#waitFor(long,java.util.concurrent.TimeUnit)"
     "executable:java.lang.Process#destroyForcibly()"
     "executable:java.lang.ProcessBuilder#directory(java.io.File)"
     "executable:java.lang.ProcessBuilder#redirectError(java.lang.ProcessBuilder$Redirect)"
     "executable:java.lang.ProcessBuilder#start()"]]
   [:java-library.mapping.executable/handler-0051
    ["executable:java.lang.Boolean#parseBoolean(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0052
    ["executable:java.lang.Boolean#getBoolean(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0054 ["executable:java.lang.Byte#toUnsignedInt(byte)"]]
   [:java-library.mapping.executable/handler-0055
    ["executable:java.lang.Byte#toUnsignedLong(byte)"]]
   [:java-library.mapping.executable/handler-0056
    ["executable:java.lang.Character#digit(char,int)"]]
   [:java-library.mapping.executable/handler-0057 ["executable:java.lang.Character#charCount(int)"]]
   [:java-library.mapping.executable/handler-0058 ["executable:java.lang.Character#getName(int)"]]
   [:java-library.mapping.executable/handler-0059 ["executable:java.lang.Character#isDefined(int)"]]
   [:java-library.mapping.executable/handler-0060
    ["executable:java.lang.Character#getType(int)" "executable:java.lang.Character#getType(char)"]]
   [:java-library.mapping.executable/handler-0061
    ["executable:java.lang.Character#isDigit(char)" "executable:java.lang.Character#isDigit(int)"]]
   [:java-library.mapping.executable/handler-0062
    ["executable:java.lang.Character#isBmpCodePoint(int)"]]
   [:java-library.mapping.executable/handler-0063
    ["executable:java.lang.Character#isValidCodePoint(int)"]]
   [:java-library.mapping.executable/handler-0064
    ["executable:java.lang.Character#isSurrogatePair(char,char)"]]
   [:java-library.mapping.executable/handler-0065
    ["executable:java.lang.Character#isMirrored(char)"
     "executable:java.lang.Character#isMirrored(int)"]]
   [:java-library.mapping.executable/handler-0066
    ["executable:java.lang.Character#isWhitespace(char)"]]
   [:java-library.mapping.executable/handler-0067 ["executable:java.lang.Character#toString(char)"]]
   [:java-library.mapping.executable/handler-0068 ["executable:java.lang.Character#toString()"]]
   [:java-library.mapping.executable/handler-0069
    ["executable:java.lang.Class#asSubclass(java.lang.Class)"]]
   [:java-library.mapping.executable/handler-0070
    ["executable:java.lang.Class#cast(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0072 ["executable:java.lang.Double#hashCode(double)"]]
   [:java-library.mapping.executable/handler-0073
    ["executable:java.lang.Class#getAnnotation(java.lang.Class)"]]
   [:java-library.mapping.executable/handler-0074
    ["executable:java.lang.Class#getDeclaredConstructor(java.lang.Class[])"]]
   [:java-library.mapping.executable/handler-0075 ["executable:java.lang.Class#getFields()"]]
   [:java-library.mapping.executable/handler-0076
    ["executable:java.lang.Class#getResourceAsStream(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0077
    ["executable:java.lang.Double#parseDouble(java.lang.String)"
     "executable:java.lang.Double#valueOf(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0078 ["executable:java.lang.Enum#toString()"]]
   [:java-library.mapping.executable/handler-0080
    ["executable:java.lang.Float#floatToIntBits(float)"
     "executable:java.lang.Float#hashCode(float)"]]
   [:java-library.mapping.executable/handler-0081 ["executable:java.lang.Long#hashCode(long)"]]
   [:java-library.mapping.executable/handler-0082 ["executable:java.lang.Float#isFinite(float)"]]
   [:java-library.mapping.executable/handler-0083 ["executable:java.lang.Double#isFinite(double)"]]
   [:java-library.mapping.executable/handler-0084 ["executable:java.lang.Float#isInfinite(float)"]]
   [:java-library.mapping.executable/handler-0085
    ["executable:java.lang.Double#isInfinite(double)"]]
   [:java-library.mapping.executable/handler-0086 ["executable:java.lang.Double#isInfinite()"]]
   [:java-library.mapping.executable/handler-0087 ["executable:java.lang.Float#isNaN(float)"]]
   [:java-library.mapping.executable/handler-0088 ["executable:java.lang.Double#isNaN(double)"]]
   [:java-library.mapping.executable/handler-0089 ["executable:java.lang.Double#isNaN()"]]
   [:java-library.mapping.executable/handler-0091 ["executable:java.lang.Double#toString(double)"]]
   [:java-library.mapping.executable/handler-0092
    ["executable:java.lang.Integer#compareTo(java.lang.Integer)"]]
   [:java-library.mapping.executable/handler-0093 ["executable:java.lang.Integer#signum(int)"]]
   [:java-library.mapping.executable/handler-0094
    ["executable:java.lang.Integer#numberOfLeadingZeros(int)"]]
   [:java-library.mapping.executable/handler-0095
    ["executable:java.lang.Long#numberOfLeadingZeros(long)"]]
   [:java-library.mapping.executable/handler-0096
    ["executable:java.lang.Long#numberOfTrailingZeros(long)"]]
   [:java-library.mapping.executable/handler-0097 ["executable:java.lang.Long#signum(long)"]]
   [:java-library.mapping.executable/handler-0098
    ["executable:java.lang.Float#parseFloat(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0099 ["executable:java.lang.Float#toString(float)"]]
   [:java-library.mapping.executable/handler-0101
    ["executable:java.lang.Integer#equals(java.lang.Object)"
     "executable:java.lang.Long#equals(java.lang.Object)"
     "executable:java.lang.Float#equals(java.lang.Object)"
     "executable:java.lang.Double#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0102
    ["executable:java.lang.Integer#highestOneBit(int)"]]
   [:java-library.mapping.executable/handler-0103
    ["executable:java.lang.Integer#toHexString(int)" "executable:java.lang.Long#toHexString(long)"]]
   [:java-library.mapping.executable/handler-0104
    ["executable:java.lang.Long#valueOf(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0105 ["executable:java.lang.Integer#valueOf(int)"]]
   [:java-library.mapping.executable/handler-0106
    ["executable:java.lang.Integer#valueOf(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0108 ["executable:java.lang.Math#ceil(double)"]]
   [:java-library.mapping.executable/handler-0109 ["executable:java.math.BigInteger#valueOf(long)"]]
   [:java-library.mapping.executable/handler-0110 ["executable:java.math.BigInteger#toByteArray()"]]
   [:java-library.mapping.executable/handler-0111
    ["executable:java.math.BigInteger#mod(java.math.BigInteger)"]]
   [:java-library.mapping.executable/handler-0112 ["executable:java.math.BigInteger#not()"]]
   [:java-library.mapping.executable/handler-0113
    ["executable:java.math.BigInteger#shiftRight(int)"]]
   [:java-library.mapping.executable/handler-0114
    ["executable:java.math.BigInteger#and(java.math.BigInteger)"]]
   [:java-library.mapping.executable/handler-0115
    ["executable:java.math.BigInteger#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0116 ["executable:java.math.BigInteger#intValue()"]]
   [:java-library.mapping.executable/handler-0117 ["executable:java.math.BigInteger#toString(int)"]]
   [:java-library.mapping.executable/handler-0118 ["executable:java.lang.Number#doubleValue()"]]
   [:java-library.mapping.executable/handler-0119 ["executable:java.lang.Number#floatValue()"]]
   [:java-library.mapping.executable/handler-0120 ["executable:java.lang.Number#intValue()"]]
   [:java-library.mapping.executable/handler-0121
    ["executable:java.lang.Float#floatValue()"
     "executable:java.lang.Integer#intValue()"
     "executable:java.lang.Long#longValue()"
     "executable:java.lang.Double#doubleValue()"
     "executable:java.lang.Boolean#booleanValue()"]]
   [:java-library.mapping.executable/handler-0122
    ["executable:java.lang.Integer#longValue()" "executable:java.lang.Number#longValue()"]]
   [:java-library.mapping.executable/handler-0123 ["executable:java.lang.Integer#shortValue()"]]
   [:java-library.mapping.executable/handler-0124 ["executable:java.lang.Object#clone()"]]
   [:java-library.mapping.executable/handler-0125
    ["executable:java.lang.Object#equals(java.lang.Object)"
     "executable:java.lang.Record#equals(java.lang.Object)"
     "executable:java.lang.Enum#equals(java.lang.Object)"
     "executable:java.lang.Boolean#equals(java.lang.Object)"
     "executable:java.nio.file.Path#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0126
    ["executable:java.lang.Object#hashCode()"
     "executable:java.lang.Record#hashCode()"
     "executable:java.lang.Enum#hashCode()"]]
   [:java-library.mapping.executable/handler-0127 ["executable:java.lang.Record#toString()"]]
   [:java-library.mapping.executable/handler-0128
    ["executable:java.lang.reflect.Constructor#newInstance(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0129
    ["executable:java.lang.reflect.Field#getAnnotation(java.lang.Class)"]]
   [:java-library.mapping.executable/handler-0130
    ["executable:java.lang.reflect.AccessibleObject#isAnnotationPresent(java.lang.Class)"
     "executable:java.lang.reflect.Field#isAnnotationPresent(java.lang.Class)"]]
   [:java-library.mapping.executable/handler-0131
    ["executable:java.lang.reflect.Field#getModifiers()"]]
   [:java-library.mapping.executable/handler-0132
    ["executable:java.lang.reflect.Modifier#isFinal(int)"]]
   [:java-library.mapping.executable/handler-0133 ["executable:java.lang.String#charAt(int)"]]
   [:java-library.mapping.executable/handler-0134 ["executable:java.lang.String#codePointAt(int)"]]
   [:java-library.mapping.executable/handler-0135
    ["executable:java.lang.String#codePointCount(int,int)"]]
   [:java-library.mapping.executable/handler-0136 ["executable:java.lang.String#codePoints()"]]
   [:java-library.mapping.executable/handler-0137
    ["executable:java.lang.String#compareTo(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0138
    ["executable:java.lang.String#format(java.util.Locale,java.lang.String,java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0139
    ["executable:java.lang.String#indexOf(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0140
    ["executable:java.lang.String#replace(char,char)"]]
   [:java-library.mapping.executable/handler-0141
    ["executable:java.lang.String#replace(java.lang.CharSequence,java.lang.CharSequence)"]]
   [:java-library.mapping.executable/handler-0142
    ["executable:java.lang.String#replaceAll(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0143
    ["executable:java.lang.String#replaceFirst(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0144
    ["executable:java.lang.String#toLowerCase(java.util.Locale)"]]
   [:java-library.mapping.executable/handler-0145
    ["executable:java.lang.String#toUpperCase(java.util.Locale)"]]
   [:java-library.mapping.executable/handler-0146
    ["executable:java.lang.String#valueOf(boolean)"
     "executable:java.lang.String#valueOf(char)"
     "executable:java.lang.String#valueOf(char[])"
     "executable:java.lang.String#valueOf(double)"
     "executable:java.lang.String#valueOf(float)"
     "executable:java.lang.String#valueOf(int)"
     "executable:java.lang.String#valueOf(java.lang.Object)"
     "executable:java.lang.String#valueOf(long)"]]
   [:java-library.mapping.executable/handler-0147
    ["executable:java.lang.StringBuilder#append(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0148
    ["executable:java.lang.StringBuilder#append(java.lang.CharSequence,int,int)"]]
   [:java-library.mapping.executable/handler-0149 ["executable:java.lang.StringBuilder#reverse()"]]
   [:java-library.mapping.executable/handler-0150
    ["executable:java.lang.StringBuilder#deleteCharAt(int)"]]
   [:java-library.mapping.executable/handler-0151
    ["executable:java.lang.StringBuilder#delete(int,int)"
     "executable:java.lang.AbstractStringBuilder#delete(int,int)"]]
   [:java-library.mapping.executable/handler-0152
    ["executable:java.lang.StringBuilder#setLength(int)"
     "executable:java.lang.AbstractStringBuilder#setLength(int)"]]
   [:java-library.mapping.executable/handler-0153
    ["executable:java.lang.StringBuilder#charAt(int)"
     "executable:java.lang.AbstractStringBuilder#charAt(int)"]]
   [:java-library.mapping.executable/handler-0154
    ["executable:java.lang.System#getProperty(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0155
    ["executable:java.lang.System#getProperty(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0156 ["executable:java.lang.System#getenv()"]]
   [:java-library.mapping.executable/handler-0157
    ["executable:java.lang.System#getenv(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0158 ["executable:java.lang.System#getProperties()"]]
   [:java-library.mapping.executable/handler-0159
    ["executable:java.lang.System#identityHashCode(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0160 ["executable:java.lang.System#lineSeparator()"]]
   [:java-library.mapping.executable/handler-0161 ["executable:java.lang.System#exit(int)"]]
   [:java-library.mapping.executable/handler-0162
    ["executable:java.security.SecureRandom#nextBytes(byte[])"]]
   [:java-library.mapping.executable/handler-0163
    ["executable:java.security.SecureRandom#nextInt()"]]
   [:java-library.mapping.executable/handler-0164 ["executable:java.nio.Buffer#hasRemaining()"]]
   [:java-library.mapping.executable/handler-0165
    ["executable:java.nio.charset.Charset#forName(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0166 ["executable:java.nio.charset.Charset#name()"]]
   [:java-library.mapping.executable/handler-0167
    ["executable:java.nio.charset.Charset#newDecoder()"]]
   [:java-library.mapping.executable/handler-0168
    ["executable:java.nio.charset.CharsetDecoder#onMalformedInput(java.nio.charset.CodingErrorAction)"
     "executable:java.nio.charset.CharsetDecoder#onUnmappableCharacter(java.nio.charset.CodingErrorAction)"]]
   [:java-library.mapping.executable/handler-0169
    ["executable:java.nio.charset.CharsetDecoder#decode(java.nio.ByteBuffer)"]]
   [:java-library.mapping.executable/handler-0170 ["executable:java.nio.CharBuffer#toString()"]]
   [:java-library.mapping.executable/handler-0171
    ["executable:java.nio.CharBuffer#wrap(char[],int,int)"]]
   [:java-library.mapping.executable/handler-0172
    ["executable:java.nio.file.Files#readAllBytes(java.nio.file.Path)"]]
   [:java-library.mapping.executable/handler-0173
    ["executable:java.nio.file.Files#find(java.nio.file.Path,int,java.util.function.BiPredicate,java.nio.file.FileVisitOption[])"]]
   [:java-library.mapping.executable/handler-0174
    ["executable:java.nio.file.attribute.BasicFileAttributes#isRegularFile()"]]
   [:java-library.mapping.executable/handler-0175
    ["executable:java.nio.file.Paths#get(java.lang.String,java.lang.String[])"]]
   [:java-library.mapping.executable/handler-0176
    ["executable:java.nio.file.Paths#get(java.net.URI)"]]
   [:java-library.mapping.executable/handler-0177
    ["executable:java.util.Arrays#binarySearch(int[],int)"
     "executable:java.util.Arrays#binarySearch(java.lang.Object[],java.lang.Object,java.util.Comparator)"]]
   [:java-library.mapping.executable/handler-0178
    ["executable:java.util.Arrays#copyOf(byte[],int)"
     "executable:java.util.Arrays#copyOf(float[],int)"
     "executable:java.util.Arrays#copyOf(int[],int)"
     "executable:java.util.Arrays#copyOf(java.lang.Object[],int)"]]
   [:java-library.mapping.executable/handler-0179
    ["executable:java.util.Arrays#copyOfRange(byte[],int,int)"
     "executable:java.util.Arrays#copyOfRange(java.lang.Object[],int,int)"]]
   [:java-library.mapping/supplemental-collection-factory
    ["executable:java.util.Map#of()"
     "executable:java.util.Map#of(java.lang.Object,java.lang.Object)"
     "executable:java.util.Map#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.Set#of(java.lang.Object)"
     "executable:java.util.Set#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.Set#of(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0180
    ["executable:java.util.Arrays#deepToString(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0181
    ["executable:java.util.Arrays#fill(int[],int)"
     "executable:java.util.Arrays#fill(byte[],byte)"
     "executable:java.util.Arrays#fill(byte[],int,int,byte)"
     "executable:java.util.Arrays#fill(float[],float)"
     "executable:java.util.Arrays#fill(double[],double)"]]
   [:java-library.mapping.executable/handler-0182
    ["executable:java.util.Arrays#toString(float[])"
     "executable:java.util.Arrays#toString(int[])"
     "executable:java.util.Arrays#toString(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0183
    ["executable:java.text.DecimalFormatSymbols#getInstance(java.util.Locale)"]]
   [:java-library.mapping.executable/handler-0184
    ["executable:java.text.DecimalFormat#setDecimalFormatSymbols(java.text.DecimalFormatSymbols)"]]
   [:java-library.mapping.executable/handler-0185
    ["executable:java.text.NumberFormat#format(long)"
     "executable:java.text.NumberFormat#format(double)"]]
   [:java-library.mapping.executable/handler-0186
    ["executable:java.text.Format#format(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0187
    ["executable:java.text.NumberFormat#getMaximumFractionDigits()"]]
   [:java-library.mapping.executable/handler-0188
    ["executable:java.text.NumberFormat#getNumberInstance(java.util.Locale)"]]
   [:java-library.mapping.executable/handler-0189
    ["executable:java.text.DecimalFormat#setMinimumFractionDigits(int)"
     "executable:java.text.NumberFormat#setMinimumFractionDigits(int)"]]
   [:java-library.mapping.executable/handler-0190
    ["executable:java.text.DecimalFormat#setMaximumFractionDigits(int)"
     "executable:java.text.NumberFormat#setMaximumFractionDigits(int)"]]
   [:java-library.mapping.executable/handler-0191
    ["executable:java.text.DecimalFormat#setGroupingUsed(boolean)"
     "executable:java.text.NumberFormat#setGroupingUsed(boolean)"]]
   [:java-library.mapping.executable/handler-0192
    ["executable:java.util.Calendar#getInstance(java.util.TimeZone)"]]
   [:java-library.mapping.executable/handler-0193 ["executable:java.util.Calendar#clear()"]]
   [:java-library.mapping.executable/handler-0194
    ["executable:java.util.Calendar#compareTo(java.util.Calendar)"]]
   [:java-library.mapping.executable/handler-0195
    ["executable:java.util.Calendar#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0196 ["executable:java.util.Calendar#get(int)"]]
   [:java-library.mapping.executable/handler-0197
    ["executable:java.util.Calendar#getTimeInMillis()"]]
   [:java-library.mapping.executable/handler-0198
    ["executable:java.util.Calendar#set(int,int)"
     "executable:java.util.Calendar#set(int,int,int,int,int,int)"]]
   [:java-library.mapping.executable/handler-0199
    ["executable:java.util.Calendar#setTimeInMillis(long)"]]
   [:java-library.mapping.executable/handler-0200
    ["executable:java.util.Calendar#setLenient(boolean)"]]
   [:java-library.mapping.executable/handler-0201
    ["executable:java.util.Calendar#setTimeZone(java.util.TimeZone)"
     "executable:java.util.GregorianCalendar#setTimeZone(java.util.TimeZone)"]]
   [:java-library.mapping.executable/handler-0202 ["executable:java.util.Calendar#getTimeZone()"]]
   [:java-library.mapping.executable/handler-0203
    ["executable:java.util.Calendar#add(int,int)"
     "executable:java.util.GregorianCalendar#add(int,int)"]]
   [:java-library.mapping.executable/handler-0204
    ["executable:java.util.GregorianCalendar#from(java.time.ZonedDateTime)"]]
   [:java-library.mapping.executable/handler-0205 ["executable:java.util.Deque#pop()"]]
   [:java-library.mapping.executable/handler-0206 ["executable:java.util.Deque#removeFirst()"]]
   [:java-library.mapping.executable/handler-0207
    ["executable:java.util.Deque#push(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0208
    ["executable:java.util.Deque#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0209
    ["executable:java.util.Deque#addAll(java.util.Collection)"
     "executable:java.util.LinkedList#addAll(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0210
    ["executable:java.util.Deque#contains(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0211 ["executable:java.util.Deque#isEmpty()"]]
   [:java-library.mapping.executable/handler-0212 ["executable:java.util.Deque#peek()"]]
   [:java-library.mapping.executable/handler-0213 ["executable:java.util.Deque#size()"]]
   [:java-library.mapping.executable/handler-0214 ["executable:java.util.Deque#clear()"]]
   [:java-library.mapping.executable/handler-0215
    ["executable:java.util.PriorityQueue#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0216 ["executable:java.util.PriorityQueue#isEmpty()"]]
   [:java-library.mapping.executable/handler-0217 ["executable:java.util.PriorityQueue#peek()"]]
   [:java-library.mapping.executable/handler-0218 ["executable:java.util.PriorityQueue#poll()"]]
   [:java-library.mapping.executable/handler-0219
    ["executable:java.util.Properties#getProperty(java.lang.String)"
     "executable:java.util.Properties#getProperty(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0220
    ["executable:java.util.Properties#load(java.io.InputStream)"]]
   [:java-library.mapping.executable/handler-0221
    ["executable:java.util.AbstractCollection#isEmpty()"]]
   [:java-library.mapping.executable/handler-0222
    ["executable:java.util.AbstractQueue#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0223
    ["executable:java.util.Queue#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0224 ["executable:java.util.Queue#peek()"]]
   [:java-library.mapping.executable/handler-0225 ["executable:java.util.Queue#poll()"]]
   [:java-library.mapping.executable/handler-0226
    ["executable:java.util.Collection#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0227 ["executable:java.util.Collection#isEmpty()"]]
   [:java-library.mapping.executable/handler-0228 ["executable:java.util.Collection#size()"]]
   [:java-library.mapping.executable/handler-0229
    ["executable:java.util.Collection#removeAll(java.util.Collection)"
     "executable:java.util.AbstractCollection#removeAll(java.util.Collection)"
     "executable:java.util.List#removeAll(java.util.Collection)"
     "executable:java.util.ArrayList#removeAll(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0230
    ["executable:java.util.Collection#retainAll(java.util.Collection)"
     "executable:java.util.AbstractCollection#retainAll(java.util.Collection)"
     "executable:java.util.List#retainAll(java.util.Collection)"
     "executable:java.util.ArrayList#retainAll(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0231
    ["executable:java.util.Collections#sort(java.util.List)"
     "executable:java.util.Collections#sort(java.util.List,java.util.Comparator)"]]
   [:java-library.mapping.executable/handler-0232
    ["executable:java.util.Collections#reverse(java.util.List)"]]
   [:java-library.mapping.executable/handler-0233 ["executable:java.util.Base64#getDecoder()"]]
   [:java-library.mapping.executable/handler-0234 ["executable:java.util.Base64#getEncoder()"]]
   [:java-library.mapping.executable/handler-0235
    ["executable:java.util.Base64$Decoder#decode(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0236
    ["executable:java.util.Base64$Encoder#encodeToString(byte[])"]]
   [:java-library.mapping.executable/handler-0237
    ["executable:java.util.Collections#max(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0238
    ["executable:java.util.Collections#min(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0239
    ["executable:java.util.Collections#newSetFromMap(java.util.Map)"]]
   [:java-library.mapping.executable/handler-0240
    ["executable:java.util.Collections#unmodifiableSet(java.util.Set)"]]
   [:java-library.mapping.executable/handler-0241
    ["executable:java.util.Comparator#naturalOrder()"]]
   [:java-library.mapping.executable/handler-0242
    ["executable:java.util.Comparator#comparingInt(java.util.function.ToIntFunction)"]]
   [:java-library.mapping.executable/handler-0243
    ["executable:java.util.Comparator#thenComparingInt(java.util.function.ToIntFunction)"]]
   [:java-library.mapping.executable/handler-0244
    ["executable:java.util.Comparator#thenComparing(java.util.Comparator)"]]
   [:java-library.mapping.executable/handler-0245
    ["executable:java.util.Comparator#comparing(java.util.function.Function)"]]
   [:java-library.mapping.executable/handler-0246
    ["executable:java.util.List#add(int,java.lang.Object)"
     "executable:java.util.ArrayList#add(int,java.lang.Object)"
     "executable:java.util.LinkedList#add(int,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0247
    ["executable:java.util.List#addAll(int,java.util.Collection)"
     "executable:java.util.ArrayList#addAll(int,java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0248
    ["executable:java.util.List#indexOf(java.lang.Object)"
     "executable:java.util.ArrayList#indexOf(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0249
    ["executable:java.util.List#lastIndexOf(java.lang.Object)"
     "executable:java.util.ArrayList#lastIndexOf(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0250 ["executable:java.util.List#remove(int)"]]
   [:java-library.mapping.executable/handler-0251
    ["executable:java.util.List#set(int,java.lang.Object)"
     "executable:java.util.ArrayList#set(int,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0252
    ["executable:java.util.List#sort(java.util.Comparator)"
     "executable:java.util.ArrayList#sort(java.util.Comparator)"]]
   [:java-library.mapping.executable/handler-0253
    ["executable:java.util.List#subList(int,int)"
     "executable:java.util.ArrayList#subList(int,int)"]]
   [:java-library.mapping.executable/handler-0254
    ["executable:java.util.Stack#push(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0255 ["executable:java.util.Stack#pop()"]]
   [:java-library.mapping.executable/handler-0256 ["executable:java.util.Stack#peek()"]]
   [:java-library.mapping.executable/handler-0257 ["executable:java.util.Vector#isEmpty()"]]
   [:java-library.mapping.executable/handler-0258 ["executable:java.util.Vector#size()"]]
   [:java-library.mapping.executable/handler-0259 ["executable:java.util.Vector#get(int)"]]
   [:java-library.mapping.executable/handler-0260
    ["executable:java.util.Vector#addAll(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0261 ["executable:java.util.Vector#clear()"]]
   [:java-library.mapping.executable/handler-0262 ["executable:java.util.Vector#subList(int,int)"]]
   [:java-library.mapping.executable/handler-0263
    ["executable:java.util.Map#isEmpty()"
     "executable:java.util.TreeMap#isEmpty()"
     "executable:java.util.HashMap#isEmpty()"]]
   [:java-library.mapping.executable/handler-0264
    ["executable:java.util.Set#addAll(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0265 ["executable:java.util.Set#isEmpty()"]]
   [:java-library.mapping.executable/handler-0266 ["executable:java.util.Set#iterator()"]]
   [:java-library.mapping.executable/handler-0267 ["executable:java.util.Set#size()"]]
   [:java-library.mapping.executable/handler-0268
    ["executable:java.util.SortedMap#entrySet()" "executable:java.util.TreeMap#entrySet()"]]
   [:java-library.mapping.executable/handler-0269
    ["executable:java.util.SortedMap#firstKey()" "executable:java.util.TreeMap#firstKey()"]]
   [:java-library.mapping.executable/handler-0270
    ["executable:java.util.SortedMap#lastKey()" "executable:java.util.TreeMap#lastKey()"]]
   [:java-library.mapping.executable/handler-0271
    ["executable:java.util.SortedMap#subMap(java.lang.Object,java.lang.Object)"
     "executable:java.util.TreeMap#subMap(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0272
    ["executable:java.util.SortedSet#headSet(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0273 ["executable:java.util.SortedSet#first()"]]
   [:java-library.mapping.executable/handler-0274 ["executable:java.util.SortedSet#last()"]]
   [:java-library.mapping.executable/handler-0275
    ["executable:java.util.SortedSet#subSet(java.lang.Object,java.lang.Object)"
     "executable:java.util.TreeSet#subSet(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0276 ["executable:java.util.TimeZone#clone()"]]
   [:java-library.mapping.executable/handler-0277
    ["executable:java.util.TimeZone#getTimeZone(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0278 ["executable:java.util.TimeZone#getRawOffset()"]]
   [:java-library.mapping.executable/handler-0279
    ["executable:java.util.TimeZone#setID(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0280 ["executable:java.util.TimeZone#getID()"]]
   [:java-library.mapping.executable/handler-0281 ["executable:java.util.TimeZone#getOffset(long)"]]
   [:java-library.mapping.executable/handler-0282
    ["executable:java.util.TimeZone#setRawOffset(int)"]]
   [:java-library.mapping.executable/handler-0283
    ["executable:javax.xml.namespace.QName#getLocalPart()"]]
   [:java-library.mapping.executable/handler-0284
    ["executable:javax.xml.namespace.QName#getNamespaceURI()"]]
   [:java-library.mapping.executable/handler-0285
    ["executable:javax.xml.namespace.QName#getPrefix()"]]
   [:java-library.mapping.executable/handler-0286
    ["executable:javax.xml.parsers.DocumentBuilderFactory#newInstance()"]]
   [:java-library.mapping.executable/handler-0287
    ["executable:javax.xml.parsers.DocumentBuilderFactory#newDocumentBuilder()"]]
   [:java-library.mapping.executable/handler-0288
    ["executable:javax.xml.parsers.DocumentBuilderFactory#setFeature(java.lang.String,boolean)"]]
   [:java-library.mapping.executable/handler-0289
    ["executable:javax.xml.parsers.DocumentBuilderFactory#setXIncludeAware(boolean)"]]
   [:java-library.mapping.executable/handler-0290
    ["executable:javax.xml.parsers.DocumentBuilderFactory#setExpandEntityReferences(boolean)"]]
   [:java-library.mapping.executable/handler-0291
    ["executable:javax.xml.parsers.DocumentBuilderFactory#setIgnoringComments(boolean)"]]
   [:java-library.mapping.executable/handler-0292
    ["executable:javax.xml.parsers.DocumentBuilderFactory#setNamespaceAware(boolean)"]]
   [:java-library.mapping.executable/handler-0293
    ["executable:javax.xml.parsers.DocumentBuilder#newDocument()"]]
   [:java-library.mapping.executable/handler-0294
    ["executable:javax.xml.parsers.DocumentBuilder#parse(java.io.InputStream)"]]
   [:java-library.mapping.executable/handler-0295
    ["executable:javax.xml.parsers.DocumentBuilder#setErrorHandler(org.xml.sax.ErrorHandler)"]]
   [:java-library.mapping.executable/handler-0296
    ["executable:javax.xml.transform.TransformerFactory#newInstance()"]]
   [:java-library.mapping.executable/handler-0297
    ["executable:javax.xml.transform.TransformerFactory#newTransformer()"]]
   [:java-library.mapping.executable/handler-0298
    ["executable:javax.xml.transform.Transformer#setOutputProperty(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0299
    ["executable:javax.xml.transform.Transformer#transform(javax.xml.transform.Source,javax.xml.transform.Result)"]]
   [:java-library.mapping.executable/handler-0300
    ["executable:org.w3c.dom.NamedNodeMap#getLength()"]]
   [:java-library.mapping.executable/handler-0301 ["executable:org.w3c.dom.NamedNodeMap#item(int)"]]
   [:java-library.mapping.executable/handler-0302
    ["executable:org.w3c.dom.NamedNodeMap#getNamedItem(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0303 ["executable:org.w3c.dom.Node#getAttributes()"]]
   [:java-library.mapping.executable/handler-0304 ["executable:org.w3c.dom.Node#getChildNodes()"]]
   [:java-library.mapping.executable/handler-0305 ["executable:org.w3c.dom.Node#getFirstChild()"]]
   [:java-library.mapping.executable/handler-0306 ["executable:org.w3c.dom.Node#getLocalName()"]]
   [:java-library.mapping.executable/handler-0307 ["executable:org.w3c.dom.Node#getNamespaceURI()"]]
   [:java-library.mapping.executable/handler-0308 ["executable:org.w3c.dom.Node#getNextSibling()"]]
   [:java-library.mapping.executable/handler-0309 ["executable:org.w3c.dom.Node#getNodeName()"]]
   [:java-library.mapping.executable/handler-0310 ["executable:org.w3c.dom.Node#getNodeValue()"]]
   [:java-library.mapping.executable/handler-0311
    ["executable:org.w3c.dom.Node#getOwnerDocument()"]]
   [:java-library.mapping.executable/handler-0312 ["executable:org.w3c.dom.Node#getPrefix()"]]
   [:java-library.mapping.executable/handler-0313 ["executable:org.w3c.dom.Node#getTextContent()"]]
   [:java-library.mapping.executable/handler-0314
    ["executable:org.w3c.dom.Node#appendChild(org.w3c.dom.Node)"]]
   [:java-library.mapping.executable/handler-0315
    ["executable:org.w3c.dom.Node#removeChild(org.w3c.dom.Node)"]]
   [:java-library.mapping.executable/handler-0316 ["executable:org.w3c.dom.Attr#getValue()"]]
   [:java-library.mapping.executable/handler-0317
    ["executable:org.w3c.dom.CharacterData#getData()"]]
   [:java-library.mapping.executable/handler-0318
    ["executable:org.w3c.dom.Document#createElementNS(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0319
    ["executable:org.w3c.dom.Document#createElement(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0320
    ["executable:org.w3c.dom.Document#createProcessingInstruction(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0321
    ["executable:org.w3c.dom.Document#getDocumentElement()"]]
   [:java-library.mapping.executable/handler-0322
    ["executable:org.w3c.dom.Document#getInputEncoding()"]]
   [:java-library.mapping.executable/handler-0323
    ["executable:org.w3c.dom.Document#getXmlEncoding()"]]
   [:java-library.mapping.executable/handler-0324
    ["executable:org.w3c.dom.Element#setAttribute(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0325
    ["executable:org.w3c.dom.Element#setAttributeNS(java.lang.String,java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0326
    ["executable:org.w3c.dom.Element#getAttribute(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0327
    ["executable:org.w3c.dom.Element#getElementsByTagName(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0328 ["executable:org.w3c.dom.Element#getTagName()"]]
   [:java-library.mapping.executable/handler-0329
    ["executable:org.w3c.dom.Element#getAttributeNodeNS(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0330
    ["executable:org.w3c.dom.Node#setTextContent(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0331 ["executable:org.w3c.dom.NodeList#getLength()"]]
   [:java-library.mapping.executable/handler-0332 ["executable:org.w3c.dom.NodeList#item(int)"]]
   [:java-library.mapping.executable/handler-0333
    ["executable:org.w3c.dom.ProcessingInstruction#getData()"]]
   [:java-library.mapping.executable/handler-0334
    ["executable:javax.xml.xpath.XPathFactory#newInstance()"]]
   [:java-library.mapping.executable/handler-0335
    ["executable:javax.xml.xpath.XPathFactory#newXPath()"]]
   [:java-library.mapping.executable/handler-0336
    ["executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object)"
     "executable:javax.xml.xpath.XPath#evaluate(java.lang.String,java.lang.Object,javax.xml.namespace.QName)"]]
   [:java-library.mapping.executable/handler-0337
    ["executable:java.util.zip.CRC32#update(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0338 ["executable:java.util.zip.CRC32#getValue()"]]
   [:java-library.mapping.executable/handler-0339 ["executable:java.util.Collections#emptyList()"]]
   [:java-library.mapping.executable/handler-0340
    ["executable:java.util.Collections#emptyIterator()"]]
   [:java-library.mapping.executable/handler-0341 ["executable:java.util.Collections#emptyMap()"]]
   [:java-library.mapping.executable/handler-0342 ["executable:java.util.Collections#emptySet()"]]
   [:java-library.mapping.executable/handler-0343
    ["executable:java.util.Collections#singletonList(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0344
    ["executable:java.util.Collections#nCopies(int,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0345
    ["executable:java.util.Collections#synchronizedMap(java.util.Map)"]]
   [:java-library.mapping.executable/handler-0346
    ["executable:java.util.Collections#synchronizedList(java.util.List)"]]
   [:java-library.mapping.executable/handler-0347
    ["executable:java.util.Collections#unmodifiableList(java.util.List)"]]
   [:java-library.mapping.executable/handler-0348
    ["executable:java.util.Collections#unmodifiableMap(java.util.Map)"]]
   [:java-library.mapping.executable/handler-0349 ["executable:java.net.URI#getHost()"]]
   [:java-library.mapping.executable/handler-0350 ["executable:java.net.URI#getPort()"]]
   [:java-library.mapping.executable/handler-0351 ["executable:java.net.URI#getScheme()"]]
   [:java-library.mapping.executable/handler-0352 ["executable:java.net.URI#getUserInfo()"]]
   [:java-library.mapping.executable/handler-0353 ["executable:java.net.URI#getRawPath()"]]
   [:java-library.mapping.executable/handler-0354 ["executable:java.net.URI#getPath()"]]
   [:java-library.mapping.executable/handler-0355 ["executable:java.net.URI#getRawQuery()"]]
   [:java-library.mapping.executable/handler-0356 ["executable:java.net.URI#getRawFragment()"]]
   [:java-library.mapping.executable/handler-0357
    ["executable:java.net.URI#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0358 ["executable:java.net.URI#hashCode()"]]
   [:java-library.mapping.executable/handler-0359 ["executable:java.net.URI#toString()"]]
   [:java-library.mapping.executable/handler-0360
    ["executable:java.net.URI#create(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0361 ["executable:java.lang.String#toUpperCase()"]]
   [:java-library.mapping.executable/handler-0362 ["executable:java.lang.String#toLowerCase()"]]
   [:java-library.mapping.executable/handler-0363 ["executable:java.lang.Object#toString()"]]
   [:java-library.mapping.executable/handler-0364 ["executable:java.lang.CharSequence#toString()"]]
   [:java-library.mapping.executable/handler-0365 ["executable:java.lang.CharSequence#length()"]]
   [:java-library.mapping.executable/handler-0366 ["executable:java.lang.CharSequence#charAt(int)"]]
   [:java-library.mapping.executable/handler-0367
    ["executable:java.lang.Throwable#getLocalizedMessage()"]]
   [:java-library.mapping.executable/handler-0368
    ["executable:java.lang.String#format(java.lang.String,java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0369 ["executable:java.lang.String#trim()"]]
   [:java-library.mapping.executable/handler-0370
    ["executable:java.lang.String#split(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0371
    ["executable:java.lang.String#split(java.lang.String,int)"]]
   [:java-library.mapping.executable/handler-0372 ["executable:java.lang.String#length()"]]
   [:java-library.mapping.executable/handler-0373 ["executable:java.lang.String#isEmpty()"]]
   [:java-library.mapping.executable/handler-0374
    ["executable:java.lang.String#startsWith(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0375
    ["executable:java.lang.String#startsWith(java.lang.String,int)"]]
   [:java-library.mapping.executable/handler-0376
    ["executable:java.lang.String#endsWith(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0377 ["executable:java.lang.String#substring(int)"]]
   [:java-library.mapping.executable/handler-0378
    ["executable:java.lang.String#substring(int,int)"]]
   [:java-library.mapping.executable/handler-0379 ["executable:java.lang.String#indexOf(int)"]]
   [:java-library.mapping.executable/handler-0380 ["executable:java.lang.String#indexOf(int,int)"]]
   [:java-library.mapping.executable/handler-0381 ["executable:java.lang.String#lastIndexOf(int)"]]
   [:java-library.mapping.executable/handler-0382
    ["executable:java.lang.String#contains(java.lang.CharSequence)"]]
   [:java-library.mapping.executable/handler-0383
    ["executable:java.lang.String#matches(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0384 ["executable:java.lang.String#hashCode()"]]
   [:java-library.mapping.executable/handler-0385
    ["executable:java.lang.String#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0386
    ["executable:java.lang.String#equalsIgnoreCase(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0387 ["executable:java.lang.String#toCharArray()"]]
   [:java-library.mapping.executable/handler-0388
    ["executable:java.lang.String#getBytes(java.nio.charset.Charset)"
     "executable:java.lang.String#getBytes(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0389 ["executable:java.lang.String#getBytes()"]]
   [:java-library.mapping.executable/handler-0390
    ["executable:java.lang.String#join(java.lang.CharSequence,java.lang.Iterable)"]]
   [:java-library.mapping.executable/handler-0391 ["executable:java.lang.Integer#toString()"]]
   [:java-library.mapping.executable/handler-0392
    ["executable:java.lang.Integer#toString(int,int)"]]
   [:java-library.mapping.executable/handler-0393 ["executable:java.lang.Integer#toString(int)"]]
   [:java-library.mapping.executable/handler-0394 ["executable:java.lang.Integer#sum(int,int)"]]
   [:java-library.mapping.executable/handler-0395
    ["executable:java.lang.Integer#parseInt(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0396
    ["executable:java.lang.Long#parseLong(java.lang.String)"
     "executable:java.lang.Long#parseLong(java.lang.String,int)"
     "executable:java.lang.Long#parseLong(java.lang.CharSequence,int,int,int)"]]
   [:java-library.mapping.executable/handler-0397 ["executable:java.lang.Long#toString()"]]
   [:java-library.mapping.executable/handler-0398 ["executable:java.lang.Long#toString(long)"]]
   [:java-library.mapping.executable/handler-0399 ["executable:java.lang.Long#toString(long,int)"]]
   [:java-library.mapping.executable/handler-0400
    ["executable:java.lang.Math#min(double,double)"
     "executable:java.lang.Math#min(float,float)"
     "executable:java.lang.Math#min(long,long)"
     "executable:java.lang.Math#min(int,int)"]]
   [:java-library.mapping.executable/handler-0401
    ["executable:java.lang.Math#max(double,double)"
     "executable:java.lang.Math#max(float,float)"
     "executable:java.lang.Math#max(long,long)"
     "executable:java.lang.Math#max(int,int)"]]
   [:java-library.mapping.executable/handler-0402 ["executable:java.lang.Math#toIntExact(long)"]]
   [:java-library.mapping.executable/handler-0403
    ["executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)"]]
   [:java-library.mapping.executable/handler-0404
    ["executable:java.lang.System#currentTimeMillis()"]]
   [:java-library.mapping.executable/handler-0405 ["executable:java.lang.System#nanoTime()"]]
   [:java-library.mapping.executable/handler-0406 ["executable:java.lang.System#console()"]]
   [:java-library.mapping.executable/handler-0407
    ["executable:java.lang.ThreadLocal#withInitial(java.util.function.Supplier)"]]
   [:java-library.mapping.executable/handler-0408 ["executable:java.lang.ThreadLocal#get()"]]
   [:java-library.mapping.executable/handler-0409
    ["executable:java.lang.ThreadLocal#set(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0410
    ["executable:java.util.Arrays#equals(byte[],byte[])"
     "executable:java.util.Arrays#equals(char[],char[])"
     "executable:java.util.Arrays#equals(int[],int[])"
     "executable:java.util.Arrays#equals(float[],float[])"
     "executable:java.util.Arrays#equals(double[],double[])"]]
   [:java-library.mapping.executable/handler-0411
    ["executable:java.util.Arrays#hashCode(byte[])"
     "executable:java.util.Arrays#hashCode(int[])"
     "executable:java.util.Arrays#hashCode(float[])"]]
   [:java-library.mapping.executable/handler-0412
    ["executable:java.util.Arrays#asList(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0413 ["executable:java.lang.Enum#name()"]]
   [:java-library.mapping.executable/handler-0414 ["executable:java.lang.Enum#ordinal()"]]
   [:java-library.mapping.executable/handler-0415
    ["executable:java.lang.Integer#parseInt(java.lang.String,int)"]]
   [:java-library.mapping.executable/handler-0416 ["executable:java.net.Socket#getInputStream()"]]
   [:java-library.mapping.executable/handler-0417 ["executable:java.net.Socket#getOutputStream()"]]
   [:java-library.mapping.executable/handler-0418
    ["executable:java.net.Socket#getRemoteSocketAddress()"]]
   [:java-library.mapping.executable/handler-0419 ["executable:java.net.Socket#close()"]]
   [:java-library.mapping.executable/handler-0420 ["executable:java.net.Socket#isClosed()"]]
   [:java-library.mapping.executable/handler-0421 ["executable:java.net.Socket#isConnected()"]]
   [:java-library.mapping.executable/handler-0422 ["executable:java.net.Socket#setSoTimeout(int)"]]
   [:java-library.mapping.executable/handler-0423 ["executable:java.net.ServerSocket#accept()"]]
   [:java-library.mapping.executable/handler-0424 ["executable:java.net.ServerSocket#close()"]]
   [:java-library.mapping.executable/handler-0425 ["executable:java.net.ServerSocket#isClosed()"]]
   [:java-library.mapping.executable/handler-0426
    ["executable:java.net.InetSocketAddress#getAddress()"]]
   [:java-library.mapping.executable/handler-0427 ["executable:java.net.URL#openStream()"]]
   [:java-library.mapping.executable/handler-0428
    ["executable:java.net.URLDecoder#decode(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0429
    ["executable:java.net.URLEncoder#encode(java.lang.String,java.lang.String)"
     "executable:java.net.URLEncoder#encode(java.lang.String,java.nio.charset.Charset)"]]
   [:java-library.mapping.executable/handler-0430
    ["executable:java.util.Enumeration#nextElement()"]]
   [:java-library.mapping.executable/handler-0431
    ["executable:java.util.Enumeration#hasMoreElements()"]]
   [:java-library.mapping.executable/handler-0432
    ["executable:java.security.MessageDigest#getInstance(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0433
    ["executable:java.security.MessageDigest#update(byte)"
     "executable:java.security.MessageDigest#update(byte[])"
     "executable:java.security.MessageDigest#update(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0434
    ["executable:java.security.MessageDigest#digest()"
     "executable:java.security.MessageDigest#digest(byte[])"]]
   [:java-library.mapping.executable/handler-0435
    ["executable:java.security.MessageDigest#isEqual(byte[],byte[])"]]
   [:java-library.mapping.executable/handler-0436 ["executable:java.util.Random#nextBytes(byte[])"]]
   [:java-library.mapping.executable/handler-0437 ["executable:java.util.Random#nextInt()"]]
   [:java-library.mapping.executable/handler-0438
    ["executable:javax.crypto.Cipher#getInstance(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0439
    ["executable:javax.crypto.Cipher#getMaxAllowedKeyLength(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0440
    ["executable:javax.crypto.Cipher#init(int,java.security.Key)"
     "executable:javax.crypto.Cipher#init(int,java.security.Key,java.security.spec.AlgorithmParameterSpec)"]]
   [:java-library.mapping.executable/handler-0441
    ["executable:javax.crypto.Cipher#update(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0442
    ["executable:javax.crypto.Cipher#doFinal()" "executable:javax.crypto.Cipher#doFinal(byte[])"]]
   [:java-library.mapping.executable/handler-0443
    ["executable:javax.net.ServerSocketFactory#createServerSocket(int)"]]
   [:java-library.mapping.executable/handler-0444
    ["executable:javax.net.SocketFactory#createSocket()"]]
   [:java-library.mapping.executable/handler-0445
    ["executable:javax.net.SocketFactory#createSocket(java.lang.String,int)"]]
   [:java-library.mapping.executable/handler-0446 ["executable:java.io.OutputStream#flush()"]]
   [:java-library.mapping.executable/handler-0447 ["executable:java.io.FilterOutputStream#flush()"]]
   [:java-library.mapping.executable/handler-0448
    ["executable:java.io.OutputStream#close()" "executable:java.io.FilterOutputStream#close()"]]
   [:java-library.mapping.executable/handler-0449
    ["executable:java.io.Closeable#close()" "executable:java.lang.AutoCloseable#close()"]]
   [:java-library.mapping.executable/handler-0450 ["executable:java.io.InputStream#close()"]]
   [:java-library.mapping.executable/handler-0451
    ["executable:java.io.InputStream#available()"
     "executable:java.io.ByteArrayInputStream#available()"]]
   [:java-library.mapping.executable/handler-0452 ["executable:java.io.File#toPath()"]]
   [:java-library.mapping.executable/handler-0453 ["executable:java.io.File#length()"]]
   [:java-library.mapping.executable/handler-0454 ["executable:java.io.File#delete()"]]
   [:java-library.mapping.executable/handler-0455 ["executable:java.io.File#exists()"]]
   [:java-library.mapping.executable/handler-0456 ["executable:java.io.File#getAbsolutePath()"]]
   [:java-library.mapping.executable/handler-0457 ["executable:java.io.File#isDirectory()"]]
   [:java-library.mapping.executable/handler-0458
    ["executable:java.io.File#setReadable(boolean,boolean)"]]
   [:java-library.mapping.executable/handler-0459
    ["executable:java.io.File#setWritable(boolean,boolean)"]]
   [:java-library.mapping.executable/handler-0460
    ["executable:java.io.File#setExecutable(boolean,boolean)"]]
   [:java-library.mapping.executable/handler-0461 ["executable:java.io.RandomAccessFile#close()"]]
   [:java-library.mapping.executable/handler-0462
    ["executable:java.nio.file.Files#newInputStream(java.nio.file.Path,java.nio.file.OpenOption[])"]]
   [:java-library.mapping.executable/handler-0463 ["executable:java.nio.file.Path#toFile()"]]
   [:java-library.mapping.executable/handler-0464
    ["executable:java.io.ByteArrayOutputStream#writeTo(java.io.OutputStream)"]]
   [:java-library.mapping.executable/handler-0465
    ["executable:java.io.ByteArrayOutputStream#toByteArray()"]]
   [:java-library.mapping.executable/handler-0466
    ["executable:java.io.ByteArrayOutputStream#size()"]]
   [:java-library.mapping.executable/handler-0467
    ["executable:java.io.ByteArrayOutputStream#write(int)"]]
   [:java-library.mapping.executable/handler-0468 ["executable:java.io.OutputStream#write(byte[])"]]
   [:java-library.mapping.executable/handler-0469
    ["executable:java.io.OutputStream#write(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0470 ["executable:java.io.OutputStream#write(int)"]]
   [:java-library.mapping.executable/handler-0471
    ["executable:java.io.PrintStream#println(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0472
    ["executable:java.io.PipedOutputStream#connect(java.io.PipedInputStream)"]]
   [:java-library.mapping.executable/handler-0473
    ["executable:java.io.PipedOutputStream#write(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0474 ["executable:java.io.PipedOutputStream#close()"]]
   [:java-library.mapping.executable/handler-0475 ["executable:java.io.PipedOutputStream#flush()"]]
   [:java-library.mapping.executable/handler-0476 ["executable:java.io.InputStream#read()"]]
   [:java-library.mapping.executable/handler-0477
    ["executable:java.io.InputStream#read(byte[])"
     "executable:java.io.BufferedInputStream#read(byte[])"]]
   [:java-library.mapping.executable/handler-0478
    ["executable:java.io.InputStream#read(byte[],int,int)"
     "executable:java.io.BufferedInputStream#read(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0479
    ["executable:java.io.InputStream#readAllBytes()"
     "executable:java.security.DigestInputStream#readAllBytes()"]]
   [:java-library.mapping.executable/handler-0480
    ["executable:java.io.InputStream#readNBytes(int)"]]
   [:java-library.mapping.executable/handler-0481
    ["executable:java.security.DigestInputStream#getMessageDigest()"
     "executable:java.security.DigestOutputStream#getMessageDigest()"]]
   [:java-library.mapping.executable/handler-0482
    ["executable:java.io.FilterInputStream#read()"
     "executable:java.io.FilterInputStream#read(byte[])"
     "executable:java.io.FilterInputStream#read(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0483
    ["executable:java.io.FilterInputStream#skip(long)"]]
   [:java-library.mapping.executable/handler-0484
    ["executable:java.io.ByteArrayInputStream#read()"]]
   [:java-library.mapping.executable/handler-0485 ["executable:java.io.PushbackInputStream#read()"]]
   [:java-library.mapping.executable/handler-0486
    ["executable:java.io.PushbackInputStream#unread(int)"]]
   [:java-library.mapping.executable/handler-0487
    ["executable:java.io.PushbackInputStream#unread(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0488
    ["executable:java.io.PushbackInputStream#close()"]]
   [:java-library.mapping.executable/handler-0489
    ["executable:java.util.zip.GZIPInputStream#read(byte[],int,int)"]]
   [:java-library.mapping.executable/handler-0490
    ["executable:java.lang.StringBuilder#append(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0491
    ["executable:java.lang.StringBuilder#append(char)"]]
   [:java-library.mapping.executable/handler-0492
    ["executable:java.lang.StringBuilder#append(int)"]]
   [:java-library.mapping.executable/handler-0493
    ["executable:java.lang.StringBuilder#append(long)"
     "executable:java.lang.StringBuilder#append(float)"
     "executable:java.lang.StringBuilder#append(double)"
     "executable:java.lang.StringBuilder#append(boolean)"]]
   [:java-library.mapping.executable/handler-0494
    ["executable:java.lang.StringBuilder#insert(int,char)"
     "executable:java.lang.StringBuilder#insert(int,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0495
    ["executable:java.lang.StringBuilder#appendCodePoint(int)"]]
   [:java-library.mapping.executable/handler-0496 ["executable:java.lang.StringBuilder#length()"]]
   [:java-library.mapping.executable/handler-0497
    ["executable:java.lang.AbstractStringBuilder#length()"]]
   [:java-library.mapping.executable/handler-0498
    ["executable:java.lang.StringBuilder#substring(int,int)"
     "executable:java.lang.AbstractStringBuilder#substring(int,int)"]]
   [:java-library.mapping.executable/handler-0499 ["executable:java.lang.StringBuilder#toString()"]]
   [:java-library.mapping.executable/handler-0500
    ["executable:java.util.Collection#stream()"
     "executable:java.util.Collection#parallelStream()"]]
   [:java-library.mapping.executable/handler-0501 ["executable:java.lang.Object#getClass()"]]
   [:java-library.mapping.executable/handler-0502
    ["executable:java.lang.Class#forName(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0503
    ["executable:java.lang.Class#getDeclaredField(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0504
    ["executable:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])"]]
   [:java-library.mapping.executable/handler-0505
    ["executable:java.lang.Class#getName()" "executable:java.lang.Class#getTypeName()"]]
   [:java-library.mapping.executable/handler-0506 ["executable:java.lang.Class#getSimpleName()"]]
   [:java-library.mapping.executable/handler-0507
    ["executable:java.lang.Class#isInstance(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0508
    ["executable:java.lang.Class#isAssignableFrom(java.lang.Class)"]]
   [:java-library.mapping.executable/handler-0509 ["executable:java.lang.Class#getClassLoader()"]]
   [:java-library.mapping.executable/handler-0510
    ["executable:java.lang.ClassLoader#getResource(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0511
    ["executable:java.lang.ClassLoader#getResourceAsStream(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0512 ["executable:java.lang.Throwable#getCause()"]]
   [:java-library.mapping.executable/handler-0513
    ["executable:java.lang.Throwable#initCause(java.lang.Throwable)"]]
   [:java-library.mapping.executable/handler-0514 ["executable:java.lang.Throwable#getMessage()"]]
   [:java-library.mapping.executable/handler-0515 ["executable:java.lang.Throwable#toString()"]]
   [:java-library.mapping.executable/handler-0516
    ["executable:java.lang.Throwable#getStackTrace()"]]
   [:java-library.mapping.executable/handler-0517
    ["executable:java.lang.Throwable#setStackTrace(java.lang.StackTraceElement[])"]]
   [:java-library.mapping.executable/handler-0518
    ["executable:java.net.URISyntaxException#getMessage()"
     "executable:java.util.regex.PatternSyntaxException#getMessage()"]]
   [:java-library.mapping.executable/handler-0519
    ["executable:java.net.URISyntaxException#getInput()"]]
   [:java-library.mapping.executable/handler-0520
    ["executable:java.net.URISyntaxException#getReason()"]]
   [:java-library.mapping.executable/handler-0521
    ["executable:java.net.URISyntaxException#getIndex()"]]
   [:java-library.mapping.executable/handler-0522
    ["executable:java.lang.Throwable#printStackTrace()"
     "executable:java.lang.Throwable#printStackTrace(java.io.PrintWriter)"]]
   [:java-library.mapping.executable/handler-0523
    ["executable:java.time.Duration#ofSeconds(long)"
     "executable:java.time.Duration#ofSeconds(long,long)"]]
   [:java-library.mapping.executable/handler-0524 ["executable:java.time.Duration#toMillis()"]]
   [:java-library.mapping.executable/handler-0525 ["executable:java.time.Duration#getSeconds()"]]
   [:java-library.mapping.executable/handler-0526 ["executable:java.time.Duration#getNano()"]]
   [:java-library.mapping.executable/handler-0527 ["executable:java.time.Instant#now()"]]
   [:java-library.mapping.executable/handler-0528
    ["executable:java.time.Instant#plus(java.time.temporal.TemporalAmount)"]]
   [:java-library.mapping.executable/handler-0529
    ["executable:java.time.Instant#isBefore(java.time.Instant)"]]
   [:java-library.mapping.executable/handler-0530
    ["executable:java.time.ZonedDateTime#now(java.time.ZoneId)"]]
   [:java-library.mapping.executable/handler-0531
    ["executable:java.time.ZonedDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)"]]
   [:java-library.mapping.executable/handler-0532
    ["executable:java.time.LocalDateTime#parse(java.lang.CharSequence,java.time.format.DateTimeFormatter)"]]
   [:java-library.mapping.executable/handler-0533
    ["executable:java.time.LocalDateTime#of(int,java.time.Month,int,int,int)"]]
   [:java-library.mapping.executable/handler-0534
    ["executable:java.time.LocalDateTime#atZone(java.time.ZoneId)"]]
   [:java-library.mapping.executable/handler-0535
    ["executable:java.time.ZoneId#of(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0536
    ["executable:java.time.format.DateTimeFormatter#format(java.time.temporal.TemporalAccessor)"]]
   [:java-library.mapping.executable/handler-0537
    ["executable:java.time.format.DateTimeFormatterBuilder#parseCaseInsensitive()"]]
   [:java-library.mapping.executable/handler-0538
    ["executable:java.time.format.DateTimeFormatterBuilder#append(java.time.format.DateTimeFormatter)"]]
   [:java-library.mapping.executable/handler-0539
    ["executable:java.time.format.DateTimeFormatterBuilder#parseLenient()"]]
   [:java-library.mapping.executable/handler-0540
    ["executable:java.time.format.DateTimeFormatterBuilder#appendOffset(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0541
    ["executable:java.time.format.DateTimeFormatterBuilder#parseStrict()"]]
   [:java-library.mapping.executable/handler-0542
    ["executable:java.time.format.DateTimeFormatterBuilder#toFormatter()"]]
   [:java-library.mapping.executable/handler-0543
    ["executable:java.net.InetAddress#getLoopbackAddress()"]]
   [:java-library.mapping.executable/handler-0544
    ["executable:java.net.InetAddress#getByName(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0545 ["executable:java.net.InetAddress#getAddress()"]]
   [:java-library.mapping.executable/handler-0546
    ["executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0547
    ["executable:java.util.Objects#hashCode(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0548
    ["executable:java.util.Objects#hash(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0549
    ["executable:java.util.Objects#requireNonNull(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0550
    ["executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)"]]
   [:java-library.mapping.executable/handler-0551
    ["executable:java.util.Map#entry(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0552
    ["executable:java.util.Map#entrySet()"
     "executable:java.util.HashMap#entrySet()"
     "executable:java.util.LinkedHashMap#entrySet()"]]
   [:java-library.mapping.executable/handler-0553
    ["executable:java.util.Map#containsKey(java.lang.Object)"
     "executable:java.util.TreeMap#containsKey(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0554
    ["executable:java.util.Map#containsValue(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0555
    ["executable:java.util.Map#computeIfAbsent(java.lang.Object,java.util.function.Function)"]]
   [:java-library.mapping.executable/handler-0556
    ["executable:java.util.HashMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"]]
   [:java-library.mapping.executable/handler-0557
    ["executable:java.util.TreeMap#computeIfAbsent(java.lang.Object,java.util.function.Function)"]]
   [:java-library.mapping.executable/handler-0558
    ["executable:java.util.Map#forEach(java.util.function.BiConsumer)"]]
   [:java-library.mapping.executable/handler-0559
    ["executable:java.util.Map#getOrDefault(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0560
    ["executable:java.util.Map#merge(java.lang.Object,java.lang.Object,java.util.function.BiFunction)"]]
   [:java-library.mapping.executable/handler-0561
    ["executable:java.util.Map#putIfAbsent(java.lang.Object,java.lang.Object)"
     "executable:java.util.concurrent.ConcurrentMap#putIfAbsent(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0562
    ["executable:java.util.HashMap#putIfAbsent(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0563
    ["executable:java.util.LinkedHashMap#getOrDefault(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0564
    ["executable:java.util.Map#keySet()" "executable:java.util.TreeMap#keySet()"]]
   [:java-library.mapping.executable/handler-0565 ["executable:java.util.LinkedHashMap#keySet()"]]
   [:java-library.mapping.executable/handler-0566
    ["executable:java.util.Map#values()"
     "executable:java.util.SortedMap#values()"
     "executable:java.util.TreeMap#values()"
     "executable:java.util.LinkedHashMap#values()"]]
   [:java-library.mapping.executable/handler-0567
    ["executable:java.util.Map#clear()"
     "executable:java.util.HashMap#clear()"
     "executable:java.util.TreeMap#clear()"]]
   [:java-library.mapping.executable/handler-0568
    ["executable:java.util.Map#put(java.lang.Object,java.lang.Object)"
     "executable:java.util.TreeMap#put(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0569
    ["executable:java.util.Map#putAll(java.util.Map)"]]
   [:java-library.mapping.executable/handler-0570
    ["executable:java.util.HashMap#put(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0571
    ["executable:java.util.HashMap#putAll(java.util.Map)"]]
   [:java-library.mapping.executable/handler-0572
    ["executable:java.util.LinkedHashMap#put(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0573
    ["executable:java.util.Map#size()" "executable:java.util.TreeMap#size()"]]
   [:java-library.mapping.executable/handler-0574 ["executable:java.util.HashMap#size()"]]
   [:java-library.mapping.executable/handler-0575
    ["executable:java.util.Map#get(java.lang.Object)"
     "executable:java.util.TreeMap#get(java.lang.Object)"
     "executable:java.util.LinkedHashMap#get(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0576
    ["executable:java.util.Map#remove(java.lang.Object)"
     "executable:java.util.TreeMap#remove(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0577
    ["executable:java.util.HashMap#remove(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0578
    ["executable:java.util.LinkedHashMap#remove(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0579 ["executable:java.util.Map#hashCode()"]]
   [:java-library.mapping.executable/handler-0580 ["executable:java.util.Map$Entry#getKey()"]]
   [:java-library.mapping.executable/handler-0581
    ["executable:java.lang.Iterable#forEach(java.util.function.Consumer)"
     "executable:java.util.ArrayList#forEach(java.util.function.Consumer)"]]
   [:java-library.mapping.executable/handler-0582 ["executable:java.util.Map$Entry#getValue()"]]
   [:java-library.mapping.executable/handler-0583
    ["executable:java.util.Map$Entry#setValue(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0584
    ["executable:java.util.Map$Entry#comparingByValue()"]]
   [:java-library.mapping.executable/handler-0585 ["executable:java.util.List#isEmpty()"]]
   [:java-library.mapping.executable/handler-0586 ["executable:java.util.ArrayList#isEmpty()"]]
   [:java-library.mapping.executable/handler-0587
    ["executable:java.util.LinkedList#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0588
    ["executable:java.util.LinkedList#addFirst(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0589
    ["executable:java.util.Deque#addFirst(java.lang.Object)"
     "executable:java.util.ArrayDeque#addFirst(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0590
    ["executable:java.util.Collection#clear()"
     "executable:java.util.Set#clear()"
     "executable:java.util.HashSet#clear()"
     "executable:java.util.LinkedList#clear()"]]
   [:java-library.mapping.executable/handler-0591
    ["executable:java.util.ArrayList#ensureCapacity(int)"]]
   [:java-library.mapping.executable/handler-0592
    ["executable:java.util.List#remove(java.lang.Object)"
     "executable:java.util.ArrayList#remove(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0593
    ["executable:java.util.List#removeIf(java.util.function.Predicate)"
     "executable:java.util.ArrayList#removeIf(java.util.function.Predicate)"]]
   [:java-library.mapping.executable/handler-0594
    ["executable:java.util.Collection#removeIf(java.util.function.Predicate)"]]
   [:java-library.mapping.executable/handler-0595
    ["executable:java.util.List#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0596 ["executable:java.util.List#hashCode()"]]
   [:java-library.mapping.executable/handler-0602 ["executable:java.util.ArrayList#remove(int)"]]
   [:java-library.mapping.executable/handler-0603
    ["executable:java.util.ArrayList#iterator()"
     "executable:java.util.List#iterator()"
     "executable:java.util.AbstractSequentialList#iterator()"]]
   [:java-library.mapping.executable/handler-0604
    ["executable:java.util.List#listIterator()"
     "executable:java.util.ArrayList#listIterator()"]]
   [:java-library.mapping.executable/handler-0605
    ["executable:java.util.List#listIterator(int)"
     "executable:java.util.ArrayList#listIterator(int)"]]
   [:java-library.mapping.executable/handler-0606
    ["executable:java.util.List#containsAll(java.util.Collection)"
     "executable:java.util.Collection#containsAll(java.util.Collection)"
     "executable:java.util.AbstractCollection#containsAll(java.util.Collection)"
     "executable:java.util.Set#containsAll(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0607
    ["executable:java.util.ListIterator#set(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0608
    ["executable:java.util.ListIterator#hasPrevious()"]]
   [:java-library.mapping.executable/handler-0609 ["executable:java.util.ListIterator#previous()"]]
   [:java-library.mapping.executable/handler-0610 ["executable:java.util.ListIterator#nextIndex()"]]
   [:java-library.mapping.executable/handler-0611
    ["executable:java.util.ListIterator#previousIndex()"]]
   [:java-library.mapping.executable/handler-0612
    ["executable:java.util.ListIterator#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0613
    ["executable:java.util.Comparator#compare(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0614
    ["executable:java.util.ListIterator#next()"
     "executable:java.util.PrimitiveIterator$OfInt#nextInt()"
     "executable:java.util.PrimitiveIterator$OfLong#nextLong()"]]
   [:java-library.mapping.executable/handler-0615
    ["executable:java.util.ListIterator#hasNext()"
     "executable:java.util.PrimitiveIterator$OfInt#hasNext()"
     "executable:java.util.PrimitiveIterator$OfLong#hasNext()"]]
   [:java-library.mapping.executable/handler-0616
    ["executable:java.util.Iterator#forEachRemaining(java.util.function.Consumer)"]]
   [:java-library.mapping.executable/handler-0617 ["executable:java.util.ListIterator#remove()"]]
   [:java-library.mapping.executable/handler-0618
    ["executable:java.util.Collection#remove(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0619
    ["executable:java.util.Collection#toArray()"
     "executable:java.util.ArrayList#toArray()"
     "executable:java.util.List#toArray()"]]
   [:java-library.mapping.executable/handler-0620
    ["executable:java.util.Collection#toArray(java.lang.Object[])"
     "executable:java.util.ArrayList#toArray(java.lang.Object[])"
     "executable:java.util.List#toArray(java.lang.Object[])"
     "executable:java.util.Set#toArray(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0621
    ["executable:java.util.function.BiConsumer#accept(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0622
    ["executable:java.util.function.BiFunction#apply(java.lang.Object,java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0623
    ["executable:java.util.function.Consumer#accept(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0624 ["executable:java.util.function.Supplier#get()"]]
   [:java-library.mapping.executable/handler-0625
    ["executable:java.util.Comparator#reverseOrder()"]]
   [:java-library.mapping.executable/handler-0626
    ["executable:java.util.EnumSet#of(java.lang.Enum)"]]
   [:java-library.mapping.executable/handler-0627
    ["executable:java.util.Collection#contains(java.lang.Object)"
     "executable:java.util.AbstractCollection#contains(java.lang.Object)"
     "executable:java.util.ArrayList#contains(java.lang.Object)"
     "executable:java.util.Set#contains(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0628
    ["executable:java.util.HashSet#contains(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0629
    ["executable:java.util.Set#equals(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0630 ["executable:java.util.Set#hashCode()"]]
   [:java-library.mapping.executable/handler-0631
    ["executable:java.util.Set#add(java.lang.Object)"
     "executable:java.util.AbstractCollection#add(java.lang.Object)"
     "executable:java.util.HashSet#add(java.lang.Object)"
     "executable:java.util.TreeSet#add(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0632
    ["executable:java.util.Set#removeAll(java.util.Collection)"
     "executable:java.util.AbstractSet#removeAll(java.util.Collection)"]]
   [:java-library.mapping.executable/handler-0633
    ["executable:java.util.Set#remove(java.lang.Object)"
     "executable:java.util.HashSet#remove(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0634
    ["executable:java.util.regex.Pattern#compile(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0635
    ["executable:java.util.regex.Pattern#matcher(java.lang.CharSequence)"]]
   [:java-library.mapping.executable/handler-0636
    ["executable:java.util.regex.Pattern#split(java.lang.CharSequence)"]]
   [:java-library.mapping.executable/handler-0637 ["executable:java.util.regex.Matcher#matches()"]]
   [:java-library.mapping.executable/handler-0638
    ["executable:java.util.stream.Stream#of(java.lang.Object[])"]]
   [:java-library.mapping.executable/handler-0642
    ["executable:java.util.stream.Stream#toArray(java.util.function.IntFunction)"]]
   [:java-library.mapping.executable/handler-0643
    ["executable:java.util.ServiceLoader#load(java.lang.Class)"
     "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)"]]
   [:java-library.mapping.executable/handler-0644
    ["executable:java.util.stream.Stream#flatMap(java.util.function.Function)"]]
   [:java-library.mapping.executable/handler-0645
    ["executable:java.util.stream.Stream#map(java.util.function.Function)"]]
   [:java-library.mapping.executable/handler-0646
    ["executable:java.util.stream.Stream#mapToInt(java.util.function.ToIntFunction)"]]
   [:java-library.mapping.executable/handler-0647
    ["executable:java.util.stream.Stream#mapToLong(java.util.function.ToLongFunction)"]]
   [:java-library.mapping.executable/handler-0648 ["executable:java.util.stream.LongStream#sum()"]]
   [:java-library.mapping.executable/handler-0649
    ["executable:java.util.stream.Collectors#toList()"]]
   [:java-library.mapping.executable/handler-0650
    ["executable:java.util.stream.Collectors#toSet()"]]
   [:java-library.mapping.executable/handler-0651
    ["executable:java.util.stream.Collectors#toCollection(java.util.function.Supplier)"]]
   [:java-library.mapping.executable/handler-0653
    ["executable:java.util.concurrent.ExecutorService#submit(java.lang.Runnable)"]]
   [:java-library.mapping.executable/handler-0654
    ["executable:java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)"]]
   [:java-library.mapping.executable/handler-0655
    ["executable:java.util.concurrent.ExecutorService#shutdown()"]]
   [:java-library.mapping.executable/handler-0656
    ["executable:java.util.concurrent.ExecutorService#shutdownNow()"]]
   [:java-library.mapping.executable/handler-0657
    ["executable:java.util.concurrent.ExecutorService#awaitTermination(long,java.util.concurrent.TimeUnit)"]]
   [:java-library.mapping.executable/handler-0658
    ["executable:java.util.concurrent.Executors#newFixedThreadPool(int,java.util.concurrent.ThreadFactory)"]]
   [:java-library.mapping.executable/handler-0659
    ["executable:java.util.concurrent.Executors#newSingleThreadExecutor()"]]
   [:java-library.mapping.executable/handler-0660
    ["executable:java.util.concurrent.Future#get(long,java.util.concurrent.TimeUnit)"]]
   [:java-library.mapping.executable/handler-0666
    ["executable:java.util.concurrent.atomic.AtomicReference#getAndSet(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0667
    ["executable:java.util.concurrent.atomic.AtomicReference#set(java.lang.Object)"]]
   [:java-library.mapping.executable/handler-0668
    ["executable:java.lang.Thread#setDaemon(boolean)"]]
   [:java-library.mapping.executable/handler-0669
    ["executable:java.lang.Thread#setName(java.lang.String)"]]
   [:java-library.mapping.executable/handler-0670 ["executable:java.lang.Thread#start()"]]
   [:java-library.mapping.executable/handler-0671 ["executable:java.lang.Thread#currentThread()"]]
   [:java-library.mapping.executable/handler-0672 ["executable:java.lang.Thread#interrupt()"]]
   [:java-library.mapping.executable/handler-0673 ["executable:java.lang.Thread#sleep(long)"]]
   [:java-library.mapping/executable-default
    ["executable:java.io.DataOutputStream#flush()"
     "executable:java.io.DataOutputStream#write(byte[],int,int)"
     "executable:java.io.DataOutputStream#writeByte(int)"
     "executable:java.io.DataOutputStream#writeInt(int)"
     "executable:java.io.DataOutputStream#writeLong(long)"
     "executable:java.io.DataOutputStream#writeShort(int)"
     "executable:java.io.RandomAccessFile#length()"
     "executable:java.io.RandomAccessFile#readFully(byte[])"
     "executable:java.io.RandomAccessFile#seek(long)"
     "executable:java.io.RandomAccessFile#setLength(long)"
     "executable:java.io.RandomAccessFile#write(byte[])"
     "executable:java.lang.Byte#parseByte(java.lang.String,int)"
     "executable:java.lang.CharSequence#isEmpty()"
     "executable:java.lang.Character#isHighSurrogate(char)"
     "executable:java.lang.Character#isLetterOrDigit(int)"
     "executable:java.lang.Character#isLowSurrogate(char)"
     "executable:java.lang.Character#isTitleCase(int)"
     "executable:java.lang.Character#isUnicodeIdentifierPart(int)"
     "executable:java.lang.Character#isUnicodeIdentifierStart(int)"
     "executable:java.lang.Character#isUpperCase(char)"
     "executable:java.lang.Character#isUpperCase(int)"
     "executable:java.lang.Character#toString(int)"
     "executable:java.lang.Character#toTitleCase(int)"
     "executable:java.lang.Character#toUpperCase(int)"
     "executable:java.lang.Double#doubleToRawLongBits(double)"
     "executable:java.lang.Float#doubleValue()"
     "executable:java.lang.Iterable#iterator()"
     "executable:java.lang.Iterable#spliterator()"
     "executable:java.lang.Long#byteValue()"
     "executable:java.lang.Long#intValue()"
     "executable:java.lang.Long#max(long,long)"
     "executable:java.lang.Long#shortValue()"
     "executable:java.lang.Math#abs(double)"
     "executable:java.lang.Math#abs(float)"
     "executable:java.lang.Math#abs(int)"
     "executable:java.lang.Math#abs(long)"
     "executable:java.lang.Math#acos(double)"
     "executable:java.lang.Math#addExact(int,int)"
     "executable:java.lang.Math#addExact(long,long)"
     "executable:java.lang.Math#atan2(double,double)"
     "executable:java.lang.Math#cos(double)"
     "executable:java.lang.Math#decrementExact(int)"
     "executable:java.lang.Math#decrementExact(long)"
     "executable:java.lang.Math#floor(double)"
     "executable:java.lang.Math#floorDiv(int,int)"
     "executable:java.lang.Math#getExponent(double)"
     "executable:java.lang.Math#incrementExact(int)"
     "executable:java.lang.Math#incrementExact(long)"
     "executable:java.lang.Math#log(double)"
     "executable:java.lang.Math#log10(double)"
     "executable:java.lang.Math#multiplyExact(int,int)"
     "executable:java.lang.Math#multiplyExact(long,int)"
     "executable:java.lang.Math#multiplyExact(long,long)"
     "executable:java.lang.Math#negateExact(int)"
     "executable:java.lang.Math#negateExact(long)"
     "executable:java.lang.Math#pow(double,double)"
     "executable:java.lang.Math#round(double)"
     "executable:java.lang.Math#round(float)"
     "executable:java.lang.Math#signum(double)"
     "executable:java.lang.Math#signum(float)"
     "executable:java.lang.Math#sin(double)"
     "executable:java.lang.Math#sqrt(double)"
     "executable:java.lang.Math#subtractExact(long,long)"
     "executable:java.lang.Math#toDegrees(double)"
     "executable:java.lang.Math#toRadians(double)"
     "executable:java.lang.Runnable#run()"
     "executable:java.lang.Runtime#addShutdownHook(java.lang.Thread)"
     "executable:java.lang.Runtime#getRuntime()"
     "executable:java.lang.StrictMath#abs(double)"
     "executable:java.lang.StrictMath#abs(long)"
     "executable:java.lang.StrictMath#acos(double)"
     "executable:java.lang.StrictMath#addExact(int,int)"
     "executable:java.lang.StrictMath#addExact(long,long)"
     "executable:java.lang.StrictMath#asin(double)"
     "executable:java.lang.StrictMath#atan(double)"
     "executable:java.lang.StrictMath#atan2(double,double)"
     "executable:java.lang.StrictMath#cbrt(double)"
     "executable:java.lang.StrictMath#ceil(double)"
     "executable:java.lang.StrictMath#copySign(double,double)"
     "executable:java.lang.StrictMath#cos(double)"
     "executable:java.lang.StrictMath#decrementExact(int)"
     "executable:java.lang.StrictMath#decrementExact(long)"
     "executable:java.lang.StrictMath#exp(double)"
     "executable:java.lang.StrictMath#floor(double)"
     "executable:java.lang.StrictMath#getExponent(double)"
     "executable:java.lang.StrictMath#incrementExact(int)"
     "executable:java.lang.StrictMath#incrementExact(long)"
     "executable:java.lang.StrictMath#log(double)"
     "executable:java.lang.StrictMath#log10(double)"
     "executable:java.lang.StrictMath#max(double,double)"
     "executable:java.lang.StrictMath#max(long,long)"
     "executable:java.lang.StrictMath#min(double,double)"
     "executable:java.lang.StrictMath#min(long,long)"
     "executable:java.lang.StrictMath#multiplyExact(int,int)"
     "executable:java.lang.StrictMath#multiplyExact(long,int)"
     "executable:java.lang.StrictMath#multiplyExact(long,long)"
     "executable:java.lang.StrictMath#negateExact(int)"
     "executable:java.lang.StrictMath#negateExact(long)"
     "executable:java.lang.StrictMath#pow(double,double)"
     "executable:java.lang.StrictMath#rint(double)"
     "executable:java.lang.StrictMath#signum(double)"
     "executable:java.lang.StrictMath#sin(double)"
     "executable:java.lang.StrictMath#sqrt(double)"
     "executable:java.lang.StrictMath#subtractExact(long,long)"
     "executable:java.lang.StrictMath#tan(double)"
     "executable:java.lang.StrictMath#toIntExact(long)"
     "executable:java.lang.String#formatted(java.lang.Object[])"
     "executable:java.lang.String#isBlank()"
     "executable:java.lang.String#lastIndexOf(int,int)"
     "executable:java.lang.String#lastIndexOf(java.lang.String)"
     "executable:java.lang.String#lines()"
     "executable:java.lang.String#regionMatches(boolean,int,java.lang.String,int,int)"
     "executable:java.lang.String#regionMatches(int,java.lang.String,int,int)"
     "executable:java.lang.String#repeat(int)"
     "executable:java.lang.String#strip()"
     "executable:java.lang.StringBuilder#append(char[])"
     "executable:java.lang.StringBuilder#append(char[],int,int)"
     "executable:java.lang.StringBuilder#append(java.lang.CharSequence)"
     "executable:java.lang.Thread#getId()"
     "executable:java.lang.invoke.MethodHandle#asType(java.lang.invoke.MethodType)"
     "executable:java.lang.invoke.MethodHandle#bindTo(java.lang.Object)"
     "executable:java.lang.invoke.MethodHandle#invoke(java.lang.Object[])"
     "executable:java.lang.invoke.MethodHandle#invokeExact(java.lang.Object[])"
     "executable:java.lang.invoke.MethodHandle#type()"
     "executable:java.lang.invoke.MethodHandles#constant(java.lang.Class,java.lang.Object)"
     "executable:java.lang.invoke.MethodHandles#dropArguments(java.lang.invoke.MethodHandle,int,java.lang.Class[])"
     "executable:java.lang.invoke.MethodHandles#filterReturnValue(java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle)"
     "executable:java.lang.invoke.MethodHandles#guardWithTest(java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle,java.lang.invoke.MethodHandle)"
     "executable:java.lang.invoke.MethodHandles#lookup()"
     "executable:java.lang.invoke.MethodHandles$Lookup#findStatic(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)"
     "executable:java.lang.invoke.MethodHandles$Lookup#findVirtual(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)"
     "executable:java.lang.invoke.MethodHandles$Lookup#unreflect(java.lang.reflect.Method)"
     "executable:java.lang.invoke.MethodType#methodType(java.lang.Class)"
     "executable:java.lang.invoke.MethodType#methodType(java.lang.Class,java.lang.Class)"
     "executable:java.lang.invoke.MethodType#returnType()"
     "executable:java.lang.invoke.VarHandle#storeStoreFence()"
     "executable:java.lang.reflect.Field#get(java.lang.Object)"
     "executable:java.lang.reflect.Field#setAccessible(boolean)"
     "executable:java.lang.reflect.Method#setAccessible(boolean)"
     "executable:java.math.BigDecimal#divide(java.math.BigDecimal,int,java.math.RoundingMode)"
     "executable:java.math.BigDecimal#intValue()"
     "executable:java.math.BigDecimal#multiply(java.math.BigDecimal)"
     "executable:java.math.BigDecimal#setScale(int,java.math.RoundingMode)"
     "executable:java.math.BigDecimal#stripTrailingZeros()"
     "executable:java.math.BigDecimal#toPlainString()"
     "executable:java.math.BigDecimal#toString()"
     "executable:java.math.BigDecimal#valueOf(double)"
     "executable:java.net.URI#compareTo(java.net.URI)"
     "executable:java.net.URI#getAuthority()"
     "executable:java.net.URI#getFragment()"
     "executable:java.net.URI#getQuery()"
     "executable:java.net.URI#getRawAuthority()"
     "executable:java.net.URI#getRawSchemeSpecificPart()"
     "executable:java.net.URI#getRawUserInfo()"
     "executable:java.net.URI#getSchemeSpecificPart()"
     "executable:java.net.URI#isAbsolute()"
     "executable:java.net.URI#isOpaque()"
     "executable:java.net.URI#normalize()"
     "executable:java.net.URI#relativize(java.net.URI)"
     "executable:java.net.URI#resolve(java.lang.String)"
     "executable:java.net.URI#resolve(java.net.URI)"
     "executable:java.net.URI#toASCIIString()"
     "executable:java.net.URI#toURL()"
     "executable:java.net.URL#getProtocol()"
     "executable:java.net.URL#openConnection()"
     "executable:java.net.URL#toURI()"
     "executable:java.net.URLConnection#connect()"
     "executable:java.net.URLConnection#getInputStream()"
     "executable:java.net.URLConnection#getURL()"
     "executable:java.net.URLConnection#setUseCaches(boolean)"
     "executable:java.net.http.HttpHeaders#firstValue(java.lang.String)"
     "executable:java.net.http.HttpHeaders#map()"
     "executable:java.net.http.HttpRequest#bodyPublisher()"
     "executable:java.net.http.HttpRequest#expectContinue()"
     "executable:java.net.http.HttpRequest#headers()"
     "executable:java.net.http.HttpRequest#method()"
     "executable:java.net.http.HttpRequest#newBuilder()"
     "executable:java.net.http.HttpRequest#newBuilder(java.net.URI)"
     "executable:java.net.http.HttpRequest#timeout()"
     "executable:java.net.http.HttpRequest#uri()"
     "executable:java.net.http.HttpRequest#version()"
     "executable:java.net.http.HttpRequest$BodyPublishers#noBody()"
     "executable:java.net.http.HttpRequest$Builder#DELETE()"
     "executable:java.net.http.HttpRequest$Builder#GET()"
     "executable:java.net.http.HttpRequest$Builder#build()"
     "executable:java.net.http.HttpRequest$Builder#expectContinue(boolean)"
     "executable:java.net.http.HttpRequest$Builder#header(java.lang.String,java.lang.String)"
     "executable:java.net.http.HttpRequest$Builder#method(java.lang.String,java.net.http.HttpRequest$BodyPublisher)"
     "executable:java.net.http.HttpRequest$Builder#setHeader(java.lang.String,java.lang.String)"
     "executable:java.net.http.HttpRequest$Builder#timeout(java.time.Duration)"
     "executable:java.net.http.HttpRequest$Builder#uri(java.net.URI)"
     "executable:java.net.http.HttpRequest$Builder#version(java.net.http.HttpClient$Version)"
     "executable:java.net.http.HttpResponse#body()"
     "executable:java.net.http.HttpResponse#headers()"
     "executable:java.net.http.HttpResponse#previousResponse()"
     "executable:java.net.http.HttpResponse#request()"
     "executable:java.net.http.HttpResponse#statusCode()"
     "executable:java.net.http.HttpResponse#uri()"
     "executable:java.net.http.HttpResponse#version()"
     "executable:java.net.http.HttpResponse$BodyHandlers#ofByteArray()"
     "executable:java.net.http.HttpResponse$BodyHandlers#ofInputStream()"
     "executable:java.nio.Buffer#limit()"
     "executable:java.nio.Buffer#position()"
     "executable:java.nio.ByteBuffer#allocate(int)"
     "executable:java.nio.ByteBuffer#array()"
     "executable:java.nio.ByteBuffer#clear()"
     "executable:java.nio.ByteBuffer#duplicate()"
     "executable:java.nio.ByteBuffer#get()"
     "executable:java.nio.ByteBuffer#get(byte[])"
     "executable:java.nio.ByteBuffer#get(byte[],int,int)"
     "executable:java.nio.ByteBuffer#get(int)"
     "executable:java.nio.ByteBuffer#getInt()"
     "executable:java.nio.ByteBuffer#isDirect()"
     "executable:java.nio.ByteBuffer#limit(int)"
     "executable:java.nio.ByteBuffer#mark()"
     "executable:java.nio.ByteBuffer#position(int)"
     "executable:java.nio.ByteBuffer#put(byte)"
     "executable:java.nio.ByteBuffer#put(byte[])"
     "executable:java.nio.ByteBuffer#put(byte[],int,int)"
     "executable:java.nio.ByteBuffer#putLong(long)"
     "executable:java.nio.ByteBuffer#reset()"
     "executable:java.nio.ByteBuffer#rewind()"
     "executable:java.nio.ByteBuffer#wrap(byte[])"
     "executable:java.nio.channels.FileChannel#map(java.nio.channels.FileChannel$MapMode,long,long)"
     "executable:java.nio.channels.FileChannel#open(java.nio.file.Path,java.nio.file.OpenOption[])"
     "executable:java.nio.channels.FileChannel#open(java.nio.file.Path,java.util.Set,java.nio.file.attribute.FileAttribute[])"
     "executable:java.nio.channels.FileChannel#position(long)"
     "executable:java.nio.channels.FileChannel#read(java.nio.ByteBuffer)"
     "executable:java.nio.channels.FileChannel#size()"
     "executable:java.nio.channels.spi.AbstractInterruptibleChannel#close()"
     "executable:java.nio.file.FileSystem#close()"
     "executable:java.nio.file.FileSystem#getFileStores()"
     "executable:java.nio.file.FileSystem#getPath(java.lang.String,java.lang.String[])"
     "executable:java.nio.file.FileSystem#getPathMatcher(java.lang.String)"
     "executable:java.nio.file.FileSystem#getSeparator()"
     "executable:java.nio.file.FileSystem#getUserPrincipalLookupService()"
     "executable:java.nio.file.FileSystem#isOpen()"
     "executable:java.nio.file.FileSystem#isReadOnly()"
     "executable:java.nio.file.FileSystem#newWatchService()"
     "executable:java.nio.file.FileSystem#provider()"
     "executable:java.nio.file.FileSystem#supportedFileAttributeViews()"
     "executable:java.nio.file.FileSystems#getDefault()"
     "executable:java.nio.file.FileSystems#getFileSystem(java.net.URI)"
     "executable:java.nio.file.FileSystems#newFileSystem(java.net.URI,java.util.Map)"
     "executable:java.nio.file.Files#copy(java.io.InputStream,java.nio.file.Path,java.nio.file.CopyOption[])"
     "executable:java.nio.file.Files#copy(java.nio.file.Path,java.io.OutputStream)"
     "executable:java.nio.file.Files#copy(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])"
     "executable:java.nio.file.Files#createDirectories(java.nio.file.Path,java.nio.file.attribute.FileAttribute[])"
     "executable:java.nio.file.Files#createTempDirectory(java.lang.String,java.nio.file.attribute.FileAttribute[])"
     "executable:java.nio.file.Files#createTempFile(java.lang.String,java.lang.String,java.nio.file.attribute.FileAttribute[])"
     "executable:java.nio.file.Files#createTempFile(java.nio.file.Path,java.lang.String,java.lang.String,java.nio.file.attribute.FileAttribute[])"
     "executable:java.nio.file.Files#deleteIfExists(java.nio.file.Path)"
     "executable:java.nio.file.Files#exists(java.nio.file.Path,java.nio.file.LinkOption[])"
     "executable:java.nio.file.Files#getFileAttributeView(java.nio.file.Path,java.lang.Class,java.nio.file.LinkOption[])"
     "executable:java.nio.file.Files#isDirectory(java.nio.file.Path,java.nio.file.LinkOption[])"
     "executable:java.nio.file.Files#isRegularFile(java.nio.file.Path,java.nio.file.LinkOption[])"
     "executable:java.nio.file.Files#isSymbolicLink(java.nio.file.Path)"
     "executable:java.nio.file.Files#list(java.nio.file.Path)"
     "executable:java.nio.file.Files#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption[])"
     "executable:java.nio.file.Files#newDirectoryStream(java.nio.file.Path)"
     "executable:java.nio.file.Files#newOutputStream(java.nio.file.Path,java.nio.file.OpenOption[])"
     "executable:java.nio.file.Files#readString(java.nio.file.Path)"
     "executable:java.nio.file.Files#readString(java.nio.file.Path,java.nio.charset.Charset)"
     "executable:java.nio.file.Files#setPosixFilePermissions(java.nio.file.Path,java.util.Set)"
     "executable:java.nio.file.Files#walk(java.nio.file.Path,java.nio.file.FileVisitOption[])"
     "executable:java.nio.file.Files#writeString(java.nio.file.Path,java.lang.CharSequence,java.nio.file.OpenOption[])"
     "executable:java.nio.file.Path#endsWith(java.lang.String)"
     "executable:java.nio.file.Path#endsWith(java.nio.file.Path)"
     "executable:java.nio.file.Path#getFileName()"
     "executable:java.nio.file.Path#getName(int)"
     "executable:java.nio.file.Path#getNameCount()"
     "executable:java.nio.file.Path#getParent()"
     "executable:java.nio.file.Path#getRoot()"
     "executable:java.nio.file.Path#isAbsolute()"
     "executable:java.nio.file.Path#normalize()"
     "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])"
     "executable:java.nio.file.Path#of(java.net.URI)"
     "executable:java.nio.file.Path#relativize(java.nio.file.Path)"
     "executable:java.nio.file.Path#resolve(java.lang.String)"
     "executable:java.nio.file.Path#resolve(java.nio.file.Path)"
     "executable:java.nio.file.Path#resolveSibling(java.lang.String)"
     "executable:java.nio.file.Path#startsWith(java.nio.file.Path)"
     "executable:java.nio.file.Path#toAbsolutePath()"
     "executable:java.nio.file.Path#toRealPath(java.nio.file.LinkOption[])"
     "executable:java.nio.file.Path#toString()"
     "executable:java.nio.file.Path#toUri()"
     "executable:java.nio.file.attribute.AclEntry#newBuilder()"
     "executable:java.nio.file.attribute.AclEntry$Builder#build()"
     "executable:java.nio.file.attribute.AclEntry$Builder#setPermissions(java.util.Set)"
     "executable:java.nio.file.attribute.AclEntry$Builder#setPrincipal(java.nio.file.attribute.UserPrincipal)"
     "executable:java.nio.file.attribute.AclEntry$Builder#setType(java.nio.file.attribute.AclEntryType)"
     "executable:java.nio.file.attribute.AclFileAttributeView#setAcl(java.util.List)"
     "executable:java.nio.file.attribute.FileOwnerAttributeView#getOwner()"
     "executable:java.nio.file.attribute.PosixFilePermissions#asFileAttribute(java.util.Set)"
     "executable:java.nio.file.attribute.PosixFilePermissions#fromString(java.lang.String)"
     "executable:java.security.AccessController#doPrivileged(java.security.PrivilegedAction)"
     "executable:java.security.KeyStore#aliases()"
     "executable:java.security.KeyStore#containsAlias(java.lang.String)"
     "executable:java.security.KeyStore#getCertificate(java.lang.String)"
     "executable:java.security.KeyStore#getDefaultType()"
     "executable:java.security.KeyStore#getInstance(java.lang.String)"
     "executable:java.security.KeyStore#getKey(java.lang.String,char[])"
     "executable:java.security.KeyStore#load(java.io.InputStream,char[])"
     "executable:java.security.KeyStore#load(java.security.KeyStore$LoadStoreParameter)"
     "executable:java.security.KeyStore#setCertificateEntry(java.lang.String,java.security.cert.Certificate)"
     "executable:java.security.KeyStore#size()"
     "executable:java.security.cert.CertificateFactory#generateCertificates(java.io.InputStream)"
     "executable:java.security.cert.CertificateFactory#getInstance(java.lang.String)"
     "executable:java.text.Bidi#getBaseLevel()"
     "executable:java.text.Bidi#getRunCount()"
     "executable:java.text.Bidi#getRunLevel(int)"
     "executable:java.text.Bidi#getRunLimit(int)"
     "executable:java.text.Bidi#getRunStart(int)"
     "executable:java.text.Bidi#isMixed()"
     "executable:java.text.Bidi#reorderVisually(byte[],int,java.lang.Object[],int,int)"
     "executable:java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)"
     "executable:java.util.ArrayList#addAll(java.util.Collection)"
     "executable:java.util.Arrays#stream(java.lang.Object[])"
     "executable:java.util.BitSet#clear()"
     "executable:java.util.BitSet#clear(int)"
     "executable:java.util.BitSet#get(int)"
     "executable:java.util.BitSet#nextSetBit(int)"
     "executable:java.util.BitSet#set(int)"
     "executable:java.util.BitSet#set(int,int)"
     "executable:java.util.Collection#spliterator()"
     "executable:java.util.Collections#singleton(java.lang.Object)"
     "executable:java.util.Deque#descendingIterator()"
     "executable:java.util.Deque#getFirst()"
     "executable:java.util.EnumSet#allOf(java.lang.Class)"
     "executable:java.util.EnumSet#copyOf(java.util.Collection)"
     "executable:java.util.EnumSet#copyOf(java.util.EnumSet)"
     "executable:java.util.EnumSet#noneOf(java.lang.Class)"
     "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum)"
     "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum)"
     "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)"
     "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum,java.lang.Enum)"
     "executable:java.util.EnumSet#of(java.lang.Enum,java.lang.Enum[])"
     "executable:java.util.List#addAll(java.util.Collection)"
     "executable:java.util.List#copyOf(java.util.Collection)"
     "executable:java.util.List#of()"
     "executable:java.util.List#of(java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"
     "executable:java.util.List#of(java.lang.Object[])"
     "executable:java.util.Locale#getDefault()"
     "executable:java.util.Map#equals(java.lang.Object)"
     "executable:java.util.Objects#deepEquals(java.lang.Object,java.lang.Object)"
     "executable:java.util.Objects#nonNull(java.lang.Object)"
     "executable:java.util.Objects#requireNonNullElseGet(java.lang.Object,java.util.function.Supplier)"
     "executable:java.util.Optional#empty()"
     "executable:java.util.Optional#equals(java.lang.Object)"
     "executable:java.util.Optional#get()"
     "executable:java.util.Optional#ifPresent(java.util.function.Consumer)"
     "executable:java.util.Optional#ifPresentOrElse(java.util.function.Consumer,java.lang.Runnable)"
     "executable:java.util.Optional#isEmpty()"
     "executable:java.util.Optional#isPresent()"
     "executable:java.util.Optional#map(java.util.function.Function)"
     "executable:java.util.Optional#of(java.lang.Object)"
     "executable:java.util.Optional#ofNullable(java.lang.Object)"
     "executable:java.util.Optional#orElse(java.lang.Object)"
     "executable:java.util.Optional#orElseGet(java.util.function.Supplier)"
     "executable:java.util.Optional#orElseThrow()"
     "executable:java.util.Optional#orElseThrow(java.util.function.Supplier)"
     "executable:java.util.OptionalInt#empty()"
     "executable:java.util.OptionalInt#getAsInt()"
     "executable:java.util.OptionalInt#isPresent()"
     "executable:java.util.OptionalInt#of(int)"
     "executable:java.util.OptionalInt#orElse(int)"
     "executable:java.util.OptionalLong#empty()"
     "executable:java.util.OptionalLong#ifPresent(java.util.function.LongConsumer)"
     "executable:java.util.OptionalLong#of(long)"
     "executable:java.util.Random#nextLong()"
     "executable:java.util.ResourceBundle#getBundle(java.lang.String,java.util.Locale)"
     "executable:java.util.ResourceBundle#getString(java.lang.String)"
     "executable:java.util.ServiceLoader#spliterator()"
     "executable:java.util.StringJoiner#add(java.lang.CharSequence)"
     "executable:java.util.StringJoiner#toString()"
     "executable:java.util.StringTokenizer#countTokens()"
     "executable:java.util.StringTokenizer#hasMoreTokens()"
     "executable:java.util.StringTokenizer#nextToken()"
     "executable:java.util.concurrent.CompletableFuture#complete(java.lang.Object)"
     "executable:java.util.concurrent.CompletableFuture#completeExceptionally(java.lang.Throwable)"
     "executable:java.util.concurrent.Future#get()"
     "executable:java.util.function.Function#andThen(java.util.function.Function)"
     "executable:java.util.function.Function#apply(java.lang.Object)"
     "executable:java.util.function.Function#identity()"
     "executable:java.util.function.LongFunction#apply(long)"
     "executable:java.util.regex.MatchResult#end()"
     "executable:java.util.regex.MatchResult#end(int)"
     "executable:java.util.regex.MatchResult#group()"
     "executable:java.util.regex.MatchResult#group(int)"
     "executable:java.util.regex.MatchResult#groupCount()"
     "executable:java.util.regex.MatchResult#start()"
     "executable:java.util.regex.MatchResult#start(int)"
     "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuffer,java.lang.String)"
     "executable:java.util.regex.Matcher#appendReplacement(java.lang.StringBuilder,java.lang.String)"
     "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuffer)"
     "executable:java.util.regex.Matcher#appendTail(java.lang.StringBuilder)"
     "executable:java.util.regex.Matcher#end()"
     "executable:java.util.regex.Matcher#end(int)"
     "executable:java.util.regex.Matcher#find()"
     "executable:java.util.regex.Matcher#find(int)"
     "executable:java.util.regex.Matcher#group()"
     "executable:java.util.regex.Matcher#group(int)"
     "executable:java.util.regex.Matcher#group(java.lang.String)"
     "executable:java.util.regex.Matcher#groupCount()"
     "executable:java.util.regex.Matcher#lookingAt()"
     "executable:java.util.regex.Matcher#quoteReplacement(java.lang.String)"
     "executable:java.util.regex.Matcher#region(int,int)"
     "executable:java.util.regex.Matcher#replaceAll(java.lang.String)"
     "executable:java.util.regex.Matcher#replaceFirst(java.lang.String)"
     "executable:java.util.regex.Matcher#start()"
     "executable:java.util.regex.Matcher#start(int)"
     "executable:java.util.regex.Matcher#toMatchResult()"
     "executable:java.util.regex.Pattern#compile(java.lang.String,int)"
     "executable:java.util.regex.Pattern#flags()"
     "executable:java.util.regex.Pattern#matches(java.lang.String,java.lang.CharSequence)"
     "executable:java.util.regex.Pattern#pattern()"
     "executable:java.util.regex.Pattern#quote(java.lang.String)"
     "executable:java.util.regex.Pattern#split(java.lang.CharSequence,int)"
     "executable:java.util.regex.Pattern#toString()"
     "executable:java.util.stream.Collectors#joining(java.lang.CharSequence)"
     "executable:java.util.stream.Collectors#toMap(java.util.function.Function,java.util.function.Function)"
     "executable:java.util.stream.IntStream#allMatch(java.util.function.IntPredicate)"
     "executable:java.util.stream.IntStream#forEach(java.util.function.IntConsumer)"
     "executable:java.util.stream.IntStream#iterator()"
     "executable:java.util.stream.IntStream#max()"
     "executable:java.util.stream.IntStream#skip(long)"
     "executable:java.util.stream.IntStream#toArray()"
     "executable:java.util.stream.LongStream#iterator()"
     "executable:java.util.stream.Stream#allMatch(java.util.function.Predicate)"
     "executable:java.util.stream.Stream#anyMatch(java.util.function.Predicate)"
     "executable:java.util.stream.Stream#count()"
     "executable:java.util.stream.Stream#distinct()"
     "executable:java.util.stream.Stream#findFirst()"
     "executable:java.util.stream.Stream#noneMatch(java.util.function.Predicate)"
     "executable:java.util.stream.Stream#reduce(java.util.function.BinaryOperator)"
     "executable:java.util.stream.Stream#skip(long)"
     "executable:java.util.stream.Stream#spliterator()"
     "executable:java.util.stream.Stream#toList()"
     "executable:java.util.stream.StreamSupport#stream(java.util.Spliterator,boolean)"
     "executable:java.util.zip.ZipEntry#getName()"
     "executable:java.util.zip.ZipEntry#isDirectory()"
     "executable:java.util.zip.ZipEntry#setTimeLocal(java.time.LocalDateTime)"
     "executable:java.util.zip.ZipInputStream#closeEntry()"
     "executable:java.util.zip.ZipInputStream#getNextEntry()"
     "executable:java.util.zip.ZipInputStream#readAllBytes()"
     "executable:java.util.zip.ZipOutputStream#closeEntry()"
     "executable:java.util.zip.ZipOutputStream#putNextEntry(java.util.zip.ZipEntry)"
     "executable:javax.net.ssl.KeyManagerFactory#getDefaultAlgorithm()"
     "executable:javax.net.ssl.KeyManagerFactory#getInstance(java.lang.String)"
     "executable:javax.net.ssl.KeyManagerFactory#getKeyManagers()"
     "executable:javax.net.ssl.KeyManagerFactory#init(java.security.KeyStore,char[])"
     "executable:javax.net.ssl.SSLContext#getDefault()"
     "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)"
     "executable:javax.net.ssl.SSLContext#getServerSocketFactory()"
     "executable:javax.net.ssl.SSLContext#getSocketFactory()"
     "executable:javax.net.ssl.SSLContext#init(javax.net.ssl.KeyManager[],javax.net.ssl.TrustManager[],java.security.SecureRandom)"
     "executable:javax.net.ssl.SSLSocketFactory#getDefault()"
     "executable:javax.net.ssl.TrustManagerFactory#getDefaultAlgorithm()"
     "executable:javax.net.ssl.TrustManagerFactory#getInstance(java.lang.String)"
     "executable:javax.net.ssl.TrustManagerFactory#getTrustManagers()"
     "executable:javax.net.ssl.TrustManagerFactory#init(java.security.KeyStore)"]]])

(def
  constructor-handler-groups
  [[:java-library.mapping.constructor/handler-0001
    ["executable:java.lang.Throwable#<init>()" "executable:java.lang.Exception#<init>()"]]
   [:java-library.mapping.constructor/handler-0002
    ["executable:java.lang.Exception#<init>(java.lang.Throwable)"
     "executable:java.lang.RuntimeException#<init>(java.lang.Throwable)"]]
   [:java-library.mapping.constructor/handler-0003
    ["executable:java.lang.ExceptionInInitializerError#<init>(java.lang.Throwable)"]]
   [:java-library.mapping.constructor/handler-0004
    ["executable:java.lang.IllegalArgumentException#<init>(java.lang.Throwable)"]]
   [:java-library.mapping.constructor/handler-0005
    ["executable:java.io.IOException#<init>(java.lang.Throwable)"]]
   [:java-library.mapping.constructor/handler-0006
    ["executable:java.io.FileNotFoundException#<init>()"]]
   [:java-library.mapping.constructor/handler-0007
    ["executable:java.awt.print.PrinterIOException#<init>(java.io.IOException)"]]
   [:java-library.mapping.constructor/handler-0008
    ["executable:java.net.Socket#<init>(java.lang.String,int)"]]
   [:java-library.mapping.constructor/handler-0009
    ["executable:java.net.Socket#<init>(java.net.InetAddress,int)"]]
   [:java-library.mapping.constructor/handler-0010
    ["executable:java.net.URISyntaxException#<init>(java.lang.String,java.lang.String)"
     "executable:java.net.URISyntaxException#<init>(java.lang.String,java.lang.String,int)"]]
   [:java-library.mapping.constructor/handler-0011
    ["executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0012
    ["executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0013
    ["executable:java.net.URI#<init>(java.lang.String,java.lang.String,java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0014
    ["executable:java.net.URI#<init>(java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0015 ["executable:java.io.File#<init>(java.net.URI)"]]
   [:java-library.mapping.constructor/handler-0016
    ["executable:java.io.File#<init>(java.lang.String,java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0017
    ["executable:java.io.ByteArrayInputStream#<init>(byte[])"]]
   [:java-library.mapping.constructor/handler-0018
    ["executable:java.io.ByteArrayInputStream#<init>(byte[],int,int)"]]
   [:java-library.mapping.constructor/handler-0019
    ["executable:java.io.FileInputStream#<init>(java.io.File)"
     "executable:java.io.FileInputStream#<init>(java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0020
    ["executable:java.io.FileReader#<init>(java.io.File)"]]
   [:java-library.mapping.constructor/handler-0021
    ["executable:java.io.FileOutputStream#<init>(java.io.File)"
     "executable:java.io.FileOutputStream#<init>(java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0022
    ["executable:java.io.FileWriter#<init>(java.io.File,java.nio.charset.Charset)"]]
   [:java-library.mapping.constructor/handler-0023
    ["executable:java.io.BufferedReader#<init>(java.io.Reader)"
     "executable:java.io.BufferedWriter#<init>(java.io.Writer)"
     "executable:java.io.PrintWriter#<init>(java.io.Writer)"]]
   [:java-library.mapping.constructor/handler-0024
    ["executable:java.io.SequenceInputStream#<init>(java.io.InputStream,java.io.InputStream)"]]
   [:java-library.mapping.constructor/handler-0025
    ["executable:java.math.BigInteger#<init>(int,byte[])"]]
   [:java-library.mapping.constructor/handler-0046
    ["executable:java.math.BigInteger#<init>(java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0026 ["executable:java.math.BigDecimal#<init>(int)"]]
   [:java-library.mapping.constructor/handler-0027
    ["executable:java.math.BigDecimal#<init>(java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0028
    ["executable:java.util.zip.GZIPInputStream#<init>(java.io.InputStream)"]]
   [:java-library.mapping.constructor/handler-0029
    ["executable:java.lang.String#<init>(byte[],java.nio.charset.Charset)"
     "executable:java.lang.String#<init>(byte[],int,int,java.nio.charset.Charset)"]]
   [:java-library.mapping.constructor/handler-0030
    ["executable:java.lang.String#<init>(int[],int,int)"]]
   [:java-library.mapping.constructor/handler-0031
    ["executable:java.util.GregorianCalendar#<init>()"]]
   [:java-library.mapping.constructor/handler-0032
    ["executable:java.util.GregorianCalendar#<init>(java.util.TimeZone)"]]
   [:java-library.mapping.constructor/handler-0033 ["executable:java.text.DecimalFormat#<init>()"]]
   [:java-library.mapping.constructor/handler-0034
    ["executable:java.text.DecimalFormat#<init>(java.lang.String,java.text.DecimalFormatSymbols)"]]
   [:java-library.mapping.constructor/handler-0035
    ["executable:java.util.SimpleTimeZone#<init>(int,java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0036
    ["executable:javax.xml.namespace.QName#<init>(java.lang.String)"
     "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String)"
     "executable:javax.xml.namespace.QName#<init>(java.lang.String,java.lang.String,java.lang.String)"]]
   [:java-library.mapping.constructor/handler-0037
    ["executable:javax.xml.transform.dom.DOMSource#<init>(org.w3c.dom.Node)"
     "executable:javax.xml.transform.stream.StreamResult#<init>(java.io.OutputStream)"]]
   [:java-library.mapping.constructor/handler-0038
    ["executable:javax.xml.transform.stream.StreamResult#<init>(java.io.File)"]]
   [:java-library.mapping.constructor/handler-0039
    ["executable:java.util.EnumMap#<init>(java.lang.Class)"]]
   [:java-library.mapping.constructor/handler-0040
    ["executable:java.util.HashMap#<init>()"
     "executable:java.util.HashMap#<init>(int)"
     "executable:java.util.HashMap#<init>(int,float)"
     "executable:java.util.HashMap#<init>(java.util.Map)"]]
   [:java-library.mapping.constructor/handler-0041
    ["executable:java.util.concurrent.ConcurrentHashMap#<init>(int)"]]
   [:java-library.mapping.constructor/handler-0042
    ["executable:java.util.HashSet#<init>(int,float)"
     "executable:java.util.LinkedHashSet#<init>(int,float)"
     "executable:java.util.LinkedHashMap#<init>(int,float)"
     "executable:java.util.concurrent.ConcurrentHashMap#<init>(int,float)"]]
   [:java-library.mapping.constructor/handler-0043 ["executable:java.util.TreeMap#<init>()"]]
   [:java-library.mapping.constructor/handler-0044 ["executable:java.util.TreeSet#<init>()"]]
   [:java-library.mapping.constructor/handler-0045
    ["executable:java.util.TreeMap#<init>(java.util.Comparator)"
     "executable:java.util.TreeSet#<init>(java.util.Comparator)"]]
   [:java-library.mapping/constructor-default
    ["executable:java.awt.geom.GeneralPath#<init>()"
     "executable:java.awt.geom.Point2D$Float#<init>(float,float)"
     "executable:java.io.BufferedInputStream#<init>(java.io.InputStream)"
     "executable:java.io.BufferedOutputStream#<init>(java.io.OutputStream)"
     "executable:java.io.ByteArrayOutputStream#<init>()"
     "executable:java.io.ByteArrayOutputStream#<init>(int)"
     "executable:java.io.DataOutputStream#<init>(java.io.OutputStream)"
     "executable:java.io.EOFException#<init>()"
     "executable:java.io.EOFException#<init>(java.lang.String)"
     "executable:java.io.File#<init>(java.lang.String)"
     "executable:java.io.FilterInputStream#<init>(java.io.InputStream)"
     "executable:java.io.FilterOutputStream#<init>(java.io.OutputStream)"
     "executable:java.io.IOException#<init>()"
     "executable:java.io.IOException#<init>(java.lang.String)"
     "executable:java.io.IOException#<init>(java.lang.String,java.lang.Throwable)"
     "executable:java.io.InputStream#<init>()"
     "executable:java.io.InputStreamReader#<init>(java.io.InputStream,java.nio.charset.Charset)"
     "executable:java.io.LineNumberReader#<init>(java.io.Reader)"
     "executable:java.io.OutputStream#<init>()"
     "executable:java.io.OutputStreamWriter#<init>(java.io.OutputStream,java.nio.charset.Charset)"
     "executable:java.io.PipedInputStream#<init>()"
     "executable:java.io.PipedOutputStream#<init>()"
     "executable:java.io.PushbackInputStream#<init>(java.io.InputStream)"
     "executable:java.io.PushbackInputStream#<init>(java.io.InputStream,int)"
     "executable:java.io.RandomAccessFile#<init>(java.io.File,java.lang.String)"
     "executable:java.io.StringReader#<init>(java.lang.String)"
     "executable:java.io.StringWriter#<init>()"
     "executable:java.io.Writer#<init>()"
     "executable:java.lang.ArithmeticException#<init>()"
     "executable:java.lang.ArithmeticException#<init>(java.lang.String)"
     "executable:java.lang.AssertionError#<init>()"
     "executable:java.lang.AssertionError#<init>(java.lang.Object)"
     "executable:java.lang.AssertionError#<init>(java.lang.String,java.lang.Throwable)"
     "executable:java.lang.ClassCastException#<init>(java.lang.String)"
     "executable:java.lang.Enum#<init>(java.lang.String,int)"
     "executable:java.lang.Exception#<init>(java.lang.String)"
     "executable:java.lang.Exception#<init>(java.lang.String,java.lang.Throwable)"
     "executable:java.lang.IllegalArgumentException#<init>()"
     "executable:java.lang.IllegalArgumentException#<init>(java.lang.String)"
     "executable:java.lang.IllegalArgumentException#<init>(java.lang.String,java.lang.Throwable)"
     "executable:java.lang.IllegalStateException#<init>()"
     "executable:java.lang.IllegalStateException#<init>(java.lang.String)"
     "executable:java.lang.IndexOutOfBoundsException#<init>(java.lang.String)"
     "executable:java.lang.NullPointerException#<init>(java.lang.String)"
     "executable:java.lang.Object#<init>()"
     "executable:java.lang.ProcessBuilder#<init>(java.util.List)"
     "executable:java.lang.Record#<init>()"
     "executable:java.lang.RuntimeException#<init>()"
     "executable:java.lang.RuntimeException#<init>(java.lang.String)"
     "executable:java.lang.RuntimeException#<init>(java.lang.String,java.lang.Throwable)"
     "executable:java.lang.String#<init>(char[])"
     "executable:java.lang.String#<init>(char[],int,int)"
     "executable:java.lang.StringBuilder#<init>()"
     "executable:java.lang.StringBuilder#<init>(int)"
     "executable:java.lang.StringBuilder#<init>(java.lang.String)"
     "executable:java.lang.Thread#<init>(java.lang.Runnable)"
     "executable:java.lang.Thread#<init>(java.lang.Runnable,java.lang.String)"
     "executable:java.lang.UnsupportedOperationException#<init>()"
     "executable:java.lang.UnsupportedOperationException#<init>(java.lang.String)"
     "executable:java.lang.ref.SoftReference#<init>(java.lang.Object)"
     "executable:java.lang.ref.WeakReference#<init>(java.lang.Object)"
     "executable:java.net.ServerSocket#<init>(int)"
     "executable:java.nio.file.FileSystem#<init>()"
     "executable:java.security.DigestInputStream#<init>(java.io.InputStream,java.security.MessageDigest)"
     "executable:java.security.DigestOutputStream#<init>(java.io.OutputStream,java.security.MessageDigest)"
     "executable:java.security.SecureRandom#<init>()"
     "executable:java.text.Bidi#<init>(java.lang.String,int)"
     "executable:java.text.MessageFormat#<init>(java.lang.String)"
     "executable:java.text.MessageFormat#<init>(java.lang.String,java.util.Locale)"
     "executable:java.time.format.DateTimeFormatterBuilder#<init>()"
     "executable:java.util.AbstractMap$SimpleEntry#<init>(java.lang.Object,java.lang.Object)"
     "executable:java.util.AbstractMap$SimpleImmutableEntry#<init>(java.lang.Object,java.lang.Object)"
     "executable:java.util.ArrayDeque#<init>()"
     "executable:java.util.ArrayDeque#<init>(int)"
     "executable:java.util.ArrayList#<init>()"
     "executable:java.util.ArrayList#<init>(int)"
     "executable:java.util.ArrayList#<init>(java.util.Collection)"
     "executable:java.util.BitSet#<init>()"
     "executable:java.util.HashSet#<init>()"
     "executable:java.util.HashSet#<init>(int)"
     "executable:java.util.HashSet#<init>(java.util.Collection)"
     "executable:java.util.Hashtable#<init>()"
     "executable:java.util.IdentityHashMap#<init>()"
     "executable:java.util.Iterator#<init>()"
     "executable:java.util.LinkedHashMap#<init>()"
     "executable:java.util.LinkedHashMap#<init>(int)"
     "executable:java.util.LinkedHashMap#<init>(int,float,boolean)"
     "executable:java.util.LinkedHashMap#<init>(java.util.Map)"
     "executable:java.util.LinkedHashSet#<init>()"
     "executable:java.util.LinkedHashSet#<init>(int)"
     "executable:java.util.LinkedHashSet#<init>(java.util.Collection)"
     "executable:java.util.LinkedList#<init>()"
     "executable:java.util.NoSuchElementException#<init>()"
     "executable:java.util.NoSuchElementException#<init>(java.lang.String)"
     "executable:java.util.PriorityQueue#<init>()"
     "executable:java.util.PriorityQueue#<init>(int)"
     "executable:java.util.Properties#<init>()"
     "executable:java.util.Random#<init>()"
     "executable:java.util.Stack#<init>()"
     "executable:java.util.StringJoiner#<init>(java.lang.CharSequence,java.lang.CharSequence,java.lang.CharSequence)"
     "executable:java.util.StringTokenizer#<init>(java.lang.String)"
     "executable:java.util.StringTokenizer#<init>(java.lang.String,java.lang.String)"
     "executable:java.util.TreeSet#<init>(java.util.Collection)"
     "executable:java.util.WeakHashMap#<init>()"
     "executable:java.util.concurrent.CompletableFuture#<init>()"
     "executable:java.util.concurrent.ConcurrentHashMap#<init>()"
     "executable:java.util.concurrent.TimeoutException#<init>(java.lang.String)"
     "executable:java.util.concurrent.atomic.AtomicBoolean#<init>()"
     "executable:java.util.concurrent.atomic.AtomicBoolean#<init>(boolean)"
     "executable:java.util.concurrent.atomic.AtomicInteger#<init>(int)"
     "executable:java.util.concurrent.atomic.AtomicReference#<init>()"
     "executable:java.util.zip.CRC32#<init>()"
     "executable:java.util.zip.Deflater#<init>(int)"
     "executable:java.util.zip.DeflaterOutputStream#<init>(java.io.OutputStream,java.util.zip.Deflater)"
     "executable:java.util.zip.Inflater#<init>(boolean)"
     "executable:java.util.zip.InflaterOutputStream#<init>(java.io.OutputStream)"
     "executable:java.util.zip.ZipEntry#<init>(java.lang.String)"
     "executable:java.util.zip.ZipInputStream#<init>(java.io.InputStream)"
     "executable:java.util.zip.ZipOutputStream#<init>(java.io.OutputStream)"
     "executable:javax.crypto.CipherInputStream#<init>(java.io.InputStream,javax.crypto.Cipher)"
     "executable:javax.crypto.spec.IvParameterSpec#<init>(byte[])"
     "executable:javax.crypto.spec.SecretKeySpec#<init>(byte[],java.lang.String)"]]])

(defn-
  handler-entries
  [kind groups]
  (vec
   (for
    [[handler keys] groups key keys]
     (custom (executable-id kind key) key handler #{} #{:test/shared-java-library}))))

(def executable-entries (handler-entries :executable executable-handler-groups))

(def constructor-entries (handler-entries :constructor constructor-handler-groups))

(def
  executable-keys
  (set
   (map
    :key
    (concat
     lang-entries
     sql-entries
     io-entries
     collection-entries
     stream-entries
     concurrency-entries
     executable-entries))))

(def constructor-keys (set (map :key constructor-entries)))

(def field-constant-destinations
  {"field:java.io.File#separator"
   "global::System.IO.Path.DirectorySeparatorChar.ToString()"
   "field:java.io.File#separatorChar"
   "global::System.IO.Path.DirectorySeparatorChar"
   "field:java.io.FilterInputStream#in" "@in"
   "field:java.io.FilterOutputStream#out" "@out"
   "field:java.lang.Boolean#FALSE" "false"
   "field:java.lang.Boolean#TRUE" "true"
   "field:java.lang.Byte#SIZE" "8"
   "field:java.lang.Byte#MAX_VALUE" "sbyte.MaxValue"
   "field:java.lang.Byte#MIN_VALUE" "sbyte.MinValue"
   "field:java.lang.Character#CONNECTOR_PUNCTUATION" "23"
   "field:java.lang.Character#CURRENCY_SYMBOL" "26"
   "field:java.lang.Character#DASH_PUNCTUATION" "20"
   "field:java.lang.Character#DECIMAL_DIGIT_NUMBER" "9"
   "field:java.lang.Character#END_PUNCTUATION" "22"
   "field:java.lang.Character#FINAL_QUOTE_PUNCTUATION" "30"
   "field:java.lang.Character#INITIAL_QUOTE_PUNCTUATION" "29"
   "field:java.lang.Character#LETTER_NUMBER" "10"
   "field:java.lang.Character#LINE_SEPARATOR" "13"
   "field:java.lang.Character#LOWERCASE_LETTER" "2"
   "field:java.lang.Character#MAX_CODE_POINT" "0x10ffff"
   "field:java.lang.Character#MAX_VALUE" "char.MaxValue"
   "field:java.lang.Character#MIN_CODE_POINT" "0"
   "field:java.lang.Character#MIN_SURROGATE" "'\\uD800'"
   "field:java.lang.Character#MIN_VALUE" "char.MinValue"
   "field:java.lang.Character#MODIFIER_LETTER" "4"
   "field:java.lang.Character#MODIFIER_SYMBOL" "27"
   "field:java.lang.Character#NON_SPACING_MARK" "6"
   "field:java.lang.Character#OTHER_LETTER" "5"
   "field:java.lang.Character#OTHER_PUNCTUATION" "24"
   "field:java.lang.Character#PARAGRAPH_SEPARATOR" "14"
   "field:java.lang.Character#SPACE_SEPARATOR" "12"
   "field:java.lang.Character#START_PUNCTUATION" "21"
   "field:java.lang.Character#TITLECASE_LETTER" "3"
   "field:java.lang.Character#UNASSIGNED" "0"
   "field:java.lang.Character#UPPERCASE_LETTER" "1"
   "field:java.lang.Double#MAX_EXPONENT" "1023"
   "field:java.lang.Double#MAX_VALUE" "double.MaxValue"
   "field:java.lang.Double#MIN_EXPONENT" "-1022"
   "field:java.lang.Double#MIN_VALUE" "double.Epsilon"
   "field:java.lang.Double#NEGATIVE_INFINITY" "double.NegativeInfinity"
   "field:java.lang.Double#NaN" "double.NaN"
   "field:java.lang.Double#POSITIVE_INFINITY" "double.PositiveInfinity"
   "field:java.lang.Float#MAX_VALUE" "float.MaxValue"
   "field:java.lang.Float#MIN_NORMAL" "1.17549435E-38f"
   "field:java.lang.Float#MIN_VALUE" "float.Epsilon"
   "field:java.lang.Float#NEGATIVE_INFINITY" "float.NegativeInfinity"
   "field:java.lang.Float#POSITIVE_INFINITY" "float.PositiveInfinity"
   "field:java.lang.Integer#MIN_VALUE" "int.MinValue"
   "field:java.lang.Integer#MAX_VALUE" "int.MaxValue"
   "field:java.lang.Integer#SIZE" "32"
   "field:java.lang.Long#MAX_VALUE" "long.MaxValue"
   "field:java.lang.Long#MIN_VALUE" "long.MinValue"
   "field:java.lang.Long#SIZE" "64"
   "field:java.lang.Math#E" "global::System.Math.E"
   "field:java.lang.Math#PI" "global::System.Math.PI"
   "field:java.lang.Short#MAX_VALUE" "short.MaxValue"
   "field:java.lang.Short#MIN_VALUE" "short.MinValue"
   "field:java.lang.Short#SIZE" "16"
   "field:java.lang.StrictMath#E" "global::System.Math.E"
   "field:java.lang.StrictMath#PI" "global::System.Math.PI"
   "field:java.lang.System#out" "global::DripSharp.Runtime.JavaCompat.@out"
   "field:java.lang.System#err" "global::DripSharp.Runtime.JavaCompat.err"
   "field:java.math.RoundingMode#CEILING"
   "global::DripSharp.Runtime.JavaRoundingMode.Ceiling"
   "field:java.math.RoundingMode#DOWN"
   "global::DripSharp.Runtime.JavaRoundingMode.Down"
   "field:java.math.RoundingMode#FLOOR"
   "global::DripSharp.Runtime.JavaRoundingMode.Floor"
   "field:java.math.RoundingMode#HALF_DOWN"
   "global::DripSharp.Runtime.JavaRoundingMode.HalfDown"
   "field:java.math.RoundingMode#HALF_EVEN"
   "global::DripSharp.Runtime.JavaRoundingMode.HalfEven"
   "field:java.math.RoundingMode#HALF_UP"
   "global::DripSharp.Runtime.JavaRoundingMode.HalfUp"
   "field:java.math.RoundingMode#UNNECESSARY"
   "global::DripSharp.Runtime.JavaRoundingMode.Unnecessary"
   "field:java.math.RoundingMode#UP"
   "global::DripSharp.Runtime.JavaRoundingMode.Up"
   "field:java.nio.charset.CodingErrorAction#REPORT"
   "global::DripSharp.Runtime.JavaCodingErrorAction.Report"
   "field:java.nio.charset.StandardCharsets#ISO_8859_1"
   "global::DripSharp.Runtime.JavaStandardCharsets.ISO88591"
   "field:java.nio.charset.StandardCharsets#US_ASCII"
   "global::DripSharp.Runtime.JavaStandardCharsets.USASCII"
   "field:java.nio.charset.StandardCharsets#UTF_16"
   "global::DripSharp.Runtime.JavaStandardCharsets.UTF16"
   "field:java.nio.charset.StandardCharsets#UTF_16BE"
   "global::DripSharp.Runtime.JavaStandardCharsets.UTF16BE"
   "field:java.nio.charset.StandardCharsets#UTF_16LE"
   "global::DripSharp.Runtime.JavaStandardCharsets.UTF16LE"
   "field:java.nio.charset.StandardCharsets#UTF_8"
   "global::DripSharp.Runtime.JavaStandardCharsets.UTF8"
   "field:java.nio.file.LinkOption#NOFOLLOW_LINKS" "new object()"
   "field:java.nio.file.attribute.PosixFilePermission#GROUP_EXECUTE"
   "global::System.IO.UnixFileMode.GroupExecute"
   "field:java.nio.file.attribute.PosixFilePermission#GROUP_READ"
   "global::System.IO.UnixFileMode.GroupRead"
   "field:java.nio.file.attribute.PosixFilePermission#GROUP_WRITE"
   "global::System.IO.UnixFileMode.GroupWrite"
   "field:java.nio.file.attribute.PosixFilePermission#OTHERS_EXECUTE"
   "global::System.IO.UnixFileMode.OtherExecute"
   "field:java.nio.file.attribute.PosixFilePermission#OTHERS_READ"
   "global::System.IO.UnixFileMode.OtherRead"
   "field:java.nio.file.attribute.PosixFilePermission#OTHERS_WRITE"
   "global::System.IO.UnixFileMode.OtherWrite"
   "field:java.nio.file.attribute.PosixFilePermission#OWNER_EXECUTE"
   "global::System.IO.UnixFileMode.UserExecute"
   "field:java.nio.file.attribute.PosixFilePermission#OWNER_READ"
   "global::System.IO.UnixFileMode.UserRead"
   "field:java.nio.file.attribute.PosixFilePermission#OWNER_WRITE"
   "global::System.IO.UnixFileMode.UserWrite"
   "field:java.text.Bidi#DIRECTION_DEFAULT_LEFT_TO_RIGHT"
   "global::DripSharp.Runtime.JavaBidi.DirectionDefaultLeftToRight"
   "field:java.text.Bidi#DIRECTION_DEFAULT_RIGHT_TO_LEFT"
   "global::DripSharp.Runtime.JavaBidi.DirectionDefaultRightToLeft"
   "field:java.text.Bidi#DIRECTION_LEFT_TO_RIGHT"
   "global::DripSharp.Runtime.JavaBidi.DirectionLeftToRight"
   "field:java.text.Bidi#DIRECTION_RIGHT_TO_LEFT"
   "global::DripSharp.Runtime.JavaBidi.DirectionRightToLeft"
   "field:java.text.Normalizer$Form#NFC"
   "global::System.Text.NormalizationForm.FormC"
   "field:java.text.Normalizer$Form#NFD"
   "global::System.Text.NormalizationForm.FormD"
   "field:java.text.Normalizer$Form#NFKC"
   "global::System.Text.NormalizationForm.FormKC"
   "field:java.text.Normalizer$Form#NFKD"
   "global::System.Text.NormalizationForm.FormKD"
   "field:java.time.Month#FEBRUARY" "2"
   "field:java.time.format.DateTimeFormatter#ISO_LOCAL_DATE_TIME"
   "global::DripSharp.Runtime.JavaDateTimeFormatter.IsoLocalDateTime"
   "field:java.util.Calendar#DAY_OF_MONTH" "5"
   "field:java.util.Calendar#DST_OFFSET" "16"
   "field:java.util.Calendar#HOUR_OF_DAY" "11"
   "field:java.util.Calendar#MILLISECOND" "14"
   "field:java.util.Calendar#MINUTE" "12"
   "field:java.util.Calendar#MONTH" "2"
   "field:java.util.Calendar#SECOND" "13"
   "field:java.util.Calendar#YEAR" "1"
   "field:java.util.Calendar#ZONE_OFFSET" "15"
   "field:java.util.Locale#ENGLISH"
   "global::System.Globalization.CultureInfo.GetCultureInfo(\"en\")"
   "field:java.util.Locale#ROOT"
   "global::System.Globalization.CultureInfo.InvariantCulture"
   "field:java.util.Locale#US"
   "global::System.Globalization.CultureInfo.GetCultureInfo(\"en-US\")"
   "field:java.util.logging.Level#FINE"
   "global::DripSharp.Runtime.JavaLogLevel.Fine"
   "field:java.util.regex.Pattern#CANON_EQ" "128"
   "field:java.util.regex.Pattern#CASE_INSENSITIVE" "2"
   "field:java.util.regex.Pattern#COMMENTS" "4"
   "field:java.util.regex.Pattern#DOTALL" "32"
   "field:java.util.regex.Pattern#LITERAL" "16"
   "field:java.util.regex.Pattern#MULTILINE" "8"
   "field:java.util.regex.Pattern#UNICODE_CASE" "64"
   "field:java.util.regex.Pattern#UNICODE_CHARACTER_CLASS" "256"
   "field:java.util.regex.Pattern#UNIX_LINES" "1"
   "field:java.util.zip.Deflater#BEST_COMPRESSION"
   "global::DripSharp.Runtime.JavaDeflater.BEST_COMPRESSION"
   "field:java.util.zip.Deflater#DEFAULT_COMPRESSION"
   "global::DripSharp.Runtime.JavaDeflater.DEFAULT_COMPRESSION"
   "field:javax.xml.XMLConstants#XMLNS_ATTRIBUTE" "\"xmlns\""
   "field:javax.xml.XMLConstants#XMLNS_ATTRIBUTE_NS_URI"
   "\"http://www.w3.org/2000/xmlns/\""
   "field:javax.xml.XMLConstants#XML_NS_PREFIX" "\"xml\""
   "field:javax.xml.XMLConstants#XML_NS_URI"
   "\"http://www.w3.org/XML/1998/namespace\""
   "field:javax.xml.transform.OutputKeys#ENCODING" "\"encoding\""
   "field:javax.xml.transform.OutputKeys#INDENT" "\"indent\""
   "field:javax.xml.transform.OutputKeys#OMIT_XML_DECLARATION"
   "\"omit-xml-declaration\""
   "field:javax.xml.xpath.XPathConstants#NODE"
   "global::DripSharp.Runtime.JavaXPathConstants.NODE"
   "field:javax.xml.xpath.XPathConstants#NODESET"
   "global::DripSharp.Runtime.JavaXPathConstants.NODESET"
   "field:org.w3c.dom.Node#COMMENT_NODE"
   "global::System.Xml.XmlNodeType.Comment"
   "field:org.w3c.dom.Node#TEXT_NODE"
   "global::System.Xml.XmlNodeType.Text"})

(def field-caveats
  {"field:java.nio.file.LinkOption#NOFOLLOW_LINKS"
   #{:opaque-option-token :usage-dependent-approximation}})

(def field-metadata-overrides
  {"field:java.nio.charset.StandardCharsets#ISO_8859_1"
   {:introduced-by :pdfcube
    :evidence #{:differential/pdfcube-fontbox
                :test/pdfcube-standard-charsets}}
   "field:java.nio.charset.StandardCharsets#US_ASCII"
   {:introduced-by :pdfcube
    :evidence #{:differential/pdfcube-fontbox
                :test/pdfcube-standard-charsets}}})

(def neutral-field-destinations
  {"field:java.net.http.HttpClient$Version#HTTP_2" "HTTP_2"
   "field:java.nio.channels.FileChannel$MapMode#READ_ONLY" "READ_ONLY"
   "field:java.nio.file.StandardOpenOption#READ" "READ"
   "field:java.nio.file.StandardCopyOption#ATOMIC_MOVE" "ATOMIC_MOVE"
   "field:java.nio.file.StandardCopyOption#COPY_ATTRIBUTES" "COPY_ATTRIBUTES"
   "field:java.nio.file.StandardCopyOption#REPLACE_EXISTING" "REPLACE_EXISTING"
   "field:java.nio.file.attribute.AclEntryPermission#APPEND_DATA" "APPEND_DATA"
   "field:java.nio.file.attribute.AclEntryPermission#DELETE" "DELETE"
   "field:java.nio.file.attribute.AclEntryPermission#DELETE_CHILD" "DELETE_CHILD"
   "field:java.nio.file.attribute.AclEntryPermission#EXECUTE" "EXECUTE"
   "field:java.nio.file.attribute.AclEntryPermission#READ_ACL" "READ_ACL"
   "field:java.nio.file.attribute.AclEntryPermission#READ_ATTRIBUTES" "READ_ATTRIBUTES"
   "field:java.nio.file.attribute.AclEntryPermission#READ_DATA" "READ_DATA"
   "field:java.nio.file.attribute.AclEntryPermission#READ_NAMED_ATTRS" "READ_NAMED_ATTRS"
   "field:java.nio.file.attribute.AclEntryPermission#SYNCHRONIZE" "SYNCHRONIZE"
   "field:java.nio.file.attribute.AclEntryPermission#WRITE_ACL" "WRITE_ACL"
   "field:java.nio.file.attribute.AclEntryPermission#WRITE_ATTRIBUTES" "WRITE_ATTRIBUTES"
   "field:java.nio.file.attribute.AclEntryPermission#WRITE_DATA" "WRITE_DATA"
   "field:java.nio.file.attribute.AclEntryPermission#WRITE_NAMED_ATTRS" "WRITE_NAMED_ATTRS"
   "field:java.nio.file.attribute.AclEntryType#ALLOW" "ALLOW"
   "field:javax.crypto.Cipher#DECRYPT_MODE" "DECRYPT_MODE"
   "field:javax.crypto.Cipher#ENCRYPT_MODE" "ENCRYPT_MODE"})

(def field-entries
  (vec
   (concat
    (for [[key destination] (sort-by key field-constant-destinations)]
      (merge
       (constant (field-id key) key destination (get field-caveats key #{}))
       (get field-metadata-overrides key)))
    [(custom
      :java.io.byte-array-output-stream/buffer
      "field:java.io.ByteArrayOutputStream#buf"
      :java-library.mapping/byte-array-output-stream-buffer
      #{:signed-byte-array-projection}
      #{:differential/shared-java-library :test/shared-java-library})]
    (for [[key destination] (sort-by key neutral-field-destinations)]
      (rename (field-id key) key destination)))))

(def field-keys
  (set (map :key field-entries)))

(def entries
  "All reusable Java-library member mappings, including every shared
  executable, constructor, and field identity."
  (vec (concat lang-entries
               sql-entries
               io-entries
               collection-entries
               stream-entries
               concurrency-entries
               executable-entries
               constructor-entries
               field-entries)))

(def custom-handler-ids
  (set (keep #(when (= :custom-handler (:strategy %)) (:handler %))
             entries)))

(defn compile-registry
  "Compiles the shared entries with the Java-library bundle's explicit custom
  handlers."
  [custom-handlers]
  (mapping-registry/compile-registry
   entries
   {:custom-handlers custom-handlers}))
