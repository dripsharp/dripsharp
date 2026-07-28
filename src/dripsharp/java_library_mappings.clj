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

(def entries
  "All migrated reusable Java-library member mappings, in stable package-area
  order so diffs remain reviewable."
  (vec (concat lang-entries
               io-entries
               collection-entries
               stream-entries
               concurrency-entries)))

(defn compile-registry
  "Compiles the shared entries with the Java-library bundle's explicit custom
  handlers."
  [custom-handlers]
  (mapping-registry/compile-registry
   entries
   {:custom-handlers custom-handlers}))
