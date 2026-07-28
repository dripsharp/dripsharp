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
    "global::DripSharp.Runtime.JavaCompat.CompareDouble")])

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
   "field:java.nio.charset.StandardCharsets#UTF_16"
   "global::DripSharp.Runtime.JavaStandardCharsets.UTF16"
   "field:java.nio.charset.StandardCharsets#UTF_16BE"
   "global::DripSharp.Runtime.JavaStandardCharsets.UTF16BE"
   "field:java.nio.charset.StandardCharsets#UTF_16LE"
   "global::DripSharp.Runtime.JavaStandardCharsets.UTF16LE"
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
   "field:java.util.Locale#US"
   "global::System.Globalization.CultureInfo.GetCultureInfo(\"en-US\")"
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

(def neutral-field-destinations
  {"field:java.net.http.HttpClient$Version#HTTP_2" "HTTP_2"
   "field:java.nio.file.StandardCopyOption#ATOMIC_MOVE" "ATOMIC_MOVE"
   "field:java.nio.file.StandardCopyOption#COPY_ATTRIBUTES" "COPY_ATTRIBUTES"
   "field:java.nio.file.StandardCopyOption#REPLACE_EXISTING" "REPLACE_EXISTING"
   "field:javax.crypto.Cipher#DECRYPT_MODE" "DECRYPT_MODE"
   "field:javax.crypto.Cipher#ENCRYPT_MODE" "ENCRYPT_MODE"})

(def field-entries
  (vec
   (concat
    (for [[key destination] (sort-by key field-constant-destinations)]
      (constant (field-id key) key destination (get field-caveats key #{})))
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
  "All migrated reusable Java-library member mappings, in stable package-area
  order so diffs remain reviewable."
  (vec (concat lang-entries
               io-entries
               collection-entries
               stream-entries
               concurrency-entries
               field-entries)))

(defn compile-registry
  "Compiles the shared entries with the Java-library bundle's explicit custom
  handlers."
  [custom-handlers]
  (mapping-registry/compile-registry
   entries
   {:custom-handlers custom-handlers}))
