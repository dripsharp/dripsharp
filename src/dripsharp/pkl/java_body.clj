(ns dripsharp.pkl.java-body
  "Pkl-specific adaptations at the Java-body/declaration boundary.

  Ordinary Java structural translation and standard-library mappings live in
  `dripsharp.java-library`. This namespace retains only Pkl public-signature
  collection coercions and constructor-initializer runtime bridges."
  (:require [clojure.string :as str]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-library :as java-library]
            [dripsharp.spoon :as spoon])
  (:import [java.util IdentityHashMap]
           [spoon.reflect.code CtConditional CtExpression CtInvocation CtLambda
            CtLiteral CtLocalVariable CtSuperAccess CtTypeAccess CtTypePattern
            CtVariableAccess]
           [spoon.reflect.declaration CtClass CtConstructor CtElement CtMethod
            CtModifiable CtParameter CtRecord CtRecordComponent CtType
            ModifierKind]
           [spoon.reflect.reference CtArrayTypeReference CtTypeReference
            CtWildcardReference]))

(defn- raw [value]
  (csharp/raw (str value)))

(defn- sequence-node
  ([nodes]
   (csharp/sequence-node (vec (remove nil? nodes))))
  ([nodes separator]
   (csharp/sequence-node (vec (remove nil? nodes)) separator)))

(defn- invoke [target arguments]
  (csharp/invocation target arguments))

(defn- occurrence [context element]
  (.get ^IdentityHashMap (:occurrence-index context) element))

(defn- invocation-declaration
  [context ^CtInvocation invocation]
  (or
   (some-> (occurrence context (.getExecutable invocation)) :declaration)
   (some-> invocation .getExecutable .getExecutableDeclaration)))

(defn- enclosing-type [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtType current) current
      :else
      (recur (when (.isParentInitialized ^CtElement current)
               (.getParent ^CtElement current))))))

(defn- enclosing-method-return-type [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtMethod current) (.getType ^CtMethod current)
      :else
      (recur (when (.isParentInitialized ^CtElement current)
               (.getParent ^CtElement current))))))

(defn- enclosed-by-lambda-before-method?
  [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) false
      (instance? CtLambda current) true
      (instance? CtMethod current) false
      :else
      (recur (when (.isParentInitialized ^CtElement current)
               (.getParent ^CtElement current))))))

(defn- pkl-core-element? [^CtElement element]
  (str/starts-with?
   (or (some-> element enclosing-type .getQualifiedName) "")
   "org.pkl.core."))

(defn- adapt-product-collection-argument
  ([services reference node]
   (adapt-product-collection-argument services reference node false))
  ([services reference node force?]
   (if (or force?
           (when-let [adaptation
                      (:product-signature-collection-adaptation services)]
             (adaptation reference)))
     (invoke
      (csharp/generic-name
       (raw "global::DripSharp.Runtime.JavaCompat.ToReadOnly")
       [((:read-only-product-type-node services) reference)])
      [node])
     node)))

(defn- adapt-product-collection-result
  [services reference target-reference node]
  (let [mutable-reference
        (or reference target-reference)]
    (invoke
     (csharp/generic-name
      (raw "global::DripSharp.Runtime.JavaCompat.ToMutable")
      [((:mutable-product-type-node services) mutable-reference)])
     [node])))

(defn- expression-declaration-type [expression]
  (cond
    (instance? CtVariableAccess expression)
    (let [declaration
          (some-> ^CtVariableAccess expression .getVariable .getDeclaration)]
      (if (and (instance? CtLocalVariable declaration)
               (.isInferred ^CtLocalVariable declaration)
               (.getDefaultExpression ^CtLocalVariable declaration))
        (or (expression-declaration-type
             (.getDefaultExpression ^CtLocalVariable declaration))
            (.getType ^CtLocalVariable declaration))
        (some-> declaration .getType)))

    (instance? CtConditional expression)
    (or (expression-declaration-type
         (.getThenExpression ^CtConditional expression))
        (expression-declaration-type
         (.getElseExpression ^CtConditional expression)))

    (instance? CtInvocation expression)
    (let [invocation ^CtInvocation expression
          executable (.getExecutable invocation)
          owner (some-> executable .getDeclaringType .getQualifiedName)]
      (if (and (= "requireNonNull" (.getSimpleName executable))
               (= "java.util.Objects" owner))
        (expression-declaration-type (first (.getArguments invocation)))
        (some-> executable .getExecutableDeclaration .getType)))

    :else nil))

(defn- coerce-product-collection-argument
  [services source target-reference node]
  (let [source-declaration-reference (expression-declaration-type source)
        source-reference (or (some-> ^CtExpression source .getType)
                             source-declaration-reference)
        adaptation (:product-signature-collection-adaptation services)
        source-adaptation
        (when adaptation
          (or (when source-declaration-reference
                (adaptation source-declaration-reference))
              (when source-reference
                (adaptation source-reference))))
        source-adaptation
        (or
         source-adaptation
         (when (instance? CtInvocation source)
           (let [declaration
                 (some-> ^CtInvocation source
                         .getExecutable
                         .getExecutableDeclaration)
                 method-adaptation
                 (when-let [adapt-method (:method-signature-adaptation services)]
                   (adapt-method declaration))]
             (or
              (when (contains?
                     #{:read-only-product-list
                       :read-only-product-map
                       :read-only-product-set
                       :read-only-product-collection
                       :read-only-path-elements}
                     method-adaptation)
                method-adaptation)
              (when
               (and
                (when-let [boundary? (:product-boundary? services)]
                  (boundary?))
                source-reference
                (contains?
                 #{"java.util.Collection" "java.util.List"
                   "java.util.Map" "java.util.Set"}
                 (.getQualifiedName ^CtTypeReference source-reference))
                (when-let [exported?
                           (:exported-product-declaration? services)]
                  (exported? declaration)))
                :exported-product-collection)))))
        target-adaptation
        (when (and adaptation target-reference)
          (adaptation target-reference))]
    (cond
      target-adaptation
      (adapt-product-collection-argument services target-reference node)

      source-adaptation
      (adapt-product-collection-result
       services source-reference target-reference node)

      :else node)))

(declare compat-call)

(defn value-adapter
  "Preserves Pkl public-signature collection shapes at shared assignment,
  return, and argument boundaries."
  [{:keys [destination-context kind source target-reference target node]}]
  (let [services (:services destination-context)
        source-reference (some-> ^CtExpression source .getType)
        source-type (some-> ^CtTypeReference source-reference .getQualifiedName)
        target-type (some-> ^CtTypeReference target-reference .getQualifiedName)
        target-wildcard?
        (boolean
         (some #(instance? CtWildcardReference %)
               (some-> ^CtTypeReference target-reference
                       .getActualTypeArguments)))
        method-adaptation
        (when (and (instance? CtMethod target)
                   (not (enclosed-by-lambda-before-method? source)))
          (when-let [adapt (:method-signature-adaptation services)]
            (adapt target)))
        source-method-adaptation
        (when (instance? CtInvocation source)
          (when-let [adapt (:method-signature-adaptation services)]
            (adapt (invocation-declaration
                    destination-context ^CtInvocation source))))
        current-signature-adaptation
        (when-let [adaptation (:current-signature-adaptation services)]
          (adaptation))
        current-return-reference
        (when-let [return-reference (:current-product-return-reference services)]
          (return-reference))
        target-arguments
        (vec (some-> ^CtTypeReference target-reference
                     .getActualTypeArguments))
        covariant-map-value
        (when (and (instance? CtMethod target)
                   (= "java.util.Map" target-type)
                   (= 2 (count target-arguments))
                   (instance? CtWildcardReference (second target-arguments)))
          (.getBoundingType ^CtWildcardReference (second target-arguments)))]
    (cond
      (and (contains? #{:nullable-module-key :nullable-resource}
                      method-adaptation)
           (instance? CtInvocation source)
           (= "orElse"
              (some-> ^CtInvocation source
                      .getExecutable
                      .getSimpleName)))
      (raw
       (str/replace
        (:text (csharp/render node))
        #"\.OrElse\(default!\)$"
        ""))

      (and (contains? #{:nullable-module-key :nullable-resource}
                      method-adaptation)
           (= "java.util.Optional"
              (some-> ^CtExpression source .getType .getQualifiedName)))
      (invoke (csharp/member node "OrElse") [(raw "default!")])

      (contains?
       #{:read-only-product-list
         :read-only-product-map
         :read-only-product-set
         :read-only-product-collection
         :read-only-path-elements}
       method-adaptation)
      (adapt-product-collection-argument
       services target-reference node true)

      (= :idiomatic-byte-array method-adaptation)
      (compat-call "ToUnsignedBytes" [node])

      (and (= :argument kind)
           (= :idiomatic-byte-array current-signature-adaptation)
           (instance? CtArrayTypeReference target-reference)
           (= "byte"
              (some-> ^CtArrayTypeReference target-reference
                      .getComponentType
                      .getQualifiedName)))
      (compat-call "ToSignedBytes" [node])

      (= "java.util.Comparator" target-type)
      (compat-call "ToComparison" [node])

      (and (instance? CtMethod target)
           current-return-reference
           (.isPrimitive ^CtTypeReference current-return-reference)
           (contains?
            #{"java.lang.Boolean" "java.lang.Byte" "java.lang.Character"
              "java.lang.Double" "java.lang.Float" "java.lang.Integer"
              "java.lang.Long" "java.lang.Short"}
            source-type))
      (invoke
       (csharp/generic-name
        (raw "global::DripSharp.Runtime.JavaCompat.UnboxObject")
        [(java-library/type-node
          destination-context current-return-reference)])
       [node])

      covariant-map-value
      (invoke
       (csharp/generic-name
        (raw "global::DripSharp.Runtime.JavaCompat.CastDictionary")
        [(java-library/type-node destination-context
                                 (first target-arguments))
         (java-library/type-node destination-context
                                 covariant-map-value)])
       [node])

      (and (contains? #{:nullable-module-key :nullable-resource}
                      source-method-adaptation)
           (= "java.util.Optional" target-type))
      (invoke
       (csharp/member
        (java-library/type-node destination-context target-reference)
        "OfNullable")
       [node])

      (and (= "org.pkl.core.StackFrameTransformer" source-type)
           (= "java.util.function.Function" target-type))
      (sequence-node
       [(raw "(frame) => ") node (raw "(frame)")])

      (and (= "org.pkl.core.PClassInfo" source-type)
           (= source-type target-type)
           target-wildcard?)
      (invoke
       (raw
        "global::Pkl.Core.Runtime.PklRuntimeBridge.PClassInfoAsObject")
       [node])

      (and (= "org.pkl.core.Pair" source-type)
           (= source-type target-type)
           target-wildcard?)
      (invoke
       (raw "global::Pkl.Core.Runtime.PklRuntimeBridge.ObjectPair")
       [node])

      (and (instance? CtMethod target)
           (enclosed-by-lambda-before-method? source))
      node

      :else
      (coerce-product-collection-argument
       services source target-reference node))))

(defn- compat-call [name arguments]
  (invoke
   (raw (str "global::DripSharp.Runtime.JavaCompat." name))
   arguments))

(defn- member [target name]
  (csharp/member target name))

(defn- pkl-runtime-call [name arguments]
  (invoke
   (raw (str "global::Pkl.Core.Runtime.PklRuntimeBridge." name))
   arguments))

(defn- generic-call
  [destination-context target type-references arguments]
  (invoke
   (csharp/generic-name
    target
    (mapv #(java-library/type-node destination-context %) type-references))
   arguments))

(defn- result-generic-call
  [destination-context element owner name arguments]
  (generic-call
   destination-context
   (raw (str owner name))
   (.getActualTypeArguments (.getType ^CtInvocation element))
   arguments))

(defn- array-component-reference [^CtExpression expression]
  (let [reference (.getType expression)]
    (when (and reference (.isArray ^CtTypeReference reference))
      (.getComponentType ^CtTypeReference reference))))

(defn- source-array-generic-call
  [destination-context ^CtInvocation element target arguments]
  (let [component
        (some array-component-reference (.getArguments element))]
    (generic-call destination-context target [component] arguments)))

(defn- record-property-invocation-name
  [services ^CtInvocation invocation resolved]
  (let [reference (.getExecutable invocation)
        target-element (.getTarget invocation)
        declaration (or (:declaration resolved)
                        (.getExecutableDeclaration reference))
        declared-owner
        (or
         (cond
           (instance? CtRecordComponent declaration)
           (enclosing-type declaration)

           (instance? CtMethod declaration)
           (.getDeclaringType ^CtMethod declaration)

           :else nil)
         (some-> reference .getDeclaringType .getTypeDeclaration))
        owner
        (or declared-owner
            (some-> target-element .getType .getTypeDeclaration))
        simple-name (.getSimpleName reference)
        component
        (cond
          (instance? CtRecordComponent declaration)
          declaration

          (instance? CtRecord owner)
          (some #(when (= simple-name
                          (.getSimpleName ^CtRecordComponent %))
                   %)
                (.getRecordComponents ^CtRecord owner))

          :else nil)]
    (or
     (when component
       ((:record-component-name services) owner component))
     (when
      (and
       (instance? CtMethod declaration)
       (when-let [record-component-contract?
                  (:record-component-contract? services)]
         (record-component-contract? declaration)))
       (java-library/pascal simple-name)))))

(defn adapted-invocation-key?
  "True only for resolved invocations owned by the Pkl destination adapter."
  [key]
  (or
   (contains?
    #{"executable:java.util.Map#ofEntries(java.util.Map$Entry[])"
      "executable:java.lang.Appendable#append(java.lang.CharSequence)"
      "executable:java.lang.Appendable#append(java.lang.CharSequence,int,int)"
      "executable:java.lang.Appendable#append(char)"
      "executable:java.io.File#toPath()"
      "executable:java.net.URL#openConnection()"
      "executable:java.security.MessageDigest#getInstance(java.lang.String)"
      "executable:java.security.MessageDigest#digest()"
      "executable:java.security.MessageDigest#digest(byte[])"
      "executable:java.security.MessageDigest#update(byte)"
      "executable:java.security.MessageDigest#update(byte[])"
      "executable:java.security.MessageDigest#update(byte[],int,int)"
      "executable:java.nio.file.spi.FileSystemProvider#installedProviders()"
      "executable:java.nio.file.spi.FileSystemProvider#getScheme()"
      "executable:java.nio.file.FileSystem#getRootDirectories()"
      "executable:java.net.ProxySelector#getDefault()"
      "executable:java.net.ProxySelector#select(java.net.URI)"
      "executable:java.util.zip.ZipInputStream#readAllBytes()"
      "executable:javax.net.ssl.SSLContext#getDefault()"
      "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)"
      "executable:java.time.Duration#of(long,java.time.temporal.TemporalUnit)"
      "executable:java.util.concurrent.Executors#newSingleThreadScheduledExecutor(java.util.concurrent.ThreadFactory)"
      "executable:java.util.concurrent.ScheduledExecutorService#schedule(java.lang.Runnable,long,java.util.concurrent.TimeUnit)"
      "executable:org.pkl.core.runtime.Iterators#emptyTruffleIterator()"
      "executable:org.pkl.core.stdlib.VmObjectFactory$Property#identity()"
      "executable:org.pkl.core.stdlib.VmObjectFactory$Property#evaluate(java.lang.Object)"
      "executable:org.pkl.core.StackFrameTransformer#andThen(org.pkl.core.StackFrameTransformer)"
      "executable:org.pkl.core.http.HttpClient$Builder#addCertificates(byte[])"
      "executable:org.pkl.parser.Span#charIndex()"
      "executable:org.pkl.parser.Span#length()"
      "executable:org.organicdesign.fp.tuple.Tuple2#_1()"
      "executable:org.organicdesign.fp.tuple.Tuple2#_2()"
      "executable:org.organicdesign.fp.function.Fn0#apply()"}
    key)
   (some
    #(str/starts-with? key %)
    ["executable:org.organicdesign.fp.collections."
     "executable:org.organicdesign.fp.indent.Indented#indentedStr("
     "executable:org.organicdesign.fp.indent.IndentUtils#"
     "executable:org.organicdesign.fp.oneOf.Option#match("
     "executable:org.organicdesign.fp.tuple.Tuple2#of("
     "executable:org.organicdesign.fp.xform."
     "executable:org.graalvm.collections.EconomicMap#create("
     "executable:org.graalvm.collections.EconomicMap#emptyMap("
     "executable:java.net.http.HttpClient"
     "executable:org.msgpack."
     "executable:org.pkl.core.util.paguro.RrbTree#genericNodeArray("])))

(defn binary-adapter
  "Adapts Pkl value identities whose CLR representation intentionally differs
  from their Java source type."
  [{:keys [kind left-expression right-expression left right]}]
  (let [right-type
        (cond
          (instance? CtTypeAccess right-expression)
          (.getAccessedType ^CtTypeAccess right-expression)

          (instance? CtTypePattern right-expression)
          (some-> ^CtTypePattern right-expression .getVariable .getType)

          :else nil)
        left-type (some-> ^CtExpression left-expression .getType .getQualifiedName)
        right-expression-type
        (some-> ^CtExpression right-expression .getType .getQualifiedName)
        left-declaration
        (some-> ^CtExpression left-expression .getType .getTypeDeclaration)
        right-declaration
        (some-> ^CtExpression right-expression .getType .getTypeDeclaration)]
    (cond
      (and
       (contains? #{"EQ" "NE"} kind)
       (or (instance? CtRecord left-declaration)
           (instance? CtRecord right-declaration)))
      (let [same-reference
            (invoke (raw "global::System.Object.ReferenceEquals")
                    [left right])]
        (if (= "NE" kind)
          (csharp/prefix "!" same-reference)
          same-reference))

      (and
       (= "INSTANCEOF" kind)
       (= "org.pkl.core.util.paguro.RrbTree$Leaf"
          (some-> ^CtTypeReference right-type .getQualifiedName)))
      (pkl-runtime-call "IsRrbTreeLeaf" [left])

      (and
       (contains? #{"EQ" "NE"} kind)
       (= "org.pkl.core.PClassInfo" left-type)
       (= "org.pkl.core.PClassInfo" right-expression-type))
      (let [equals
            (invoke
             (raw "global::System.Object.Equals")
             [(invoke (member left "AsObject") [])
              (invoke (member right "AsObject") [])])]
        (if (= "NE" kind)
          (csharp/prefix "!" equals)
          equals))

      (and
       (contains? #{"EQ" "NE"} kind)
       (or (= "java.lang.Boolean" left-type)
           (= "java.lang.Boolean" right-expression-type)))
      (let [equals (invoke (raw "global::System.Object.Equals")
                           [left right])]
        (if (= "NE" kind)
          (csharp/prefix "!" equals)
          equals))

      :else nil)))

(def field-adaptations
  {"field:java.net.Proxy#NO_PROXY"
   (fn [_] (raw "new global::System.Net.WebProxy()"))
   "field:java.net.Proxy$Type#DIRECT"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaProxyType.DIRECT"))
   "field:java.net.Proxy$Type#HTTP"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaProxyType.HTTP"))
   "field:java.net.Proxy$Type#SOCKS"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaProxyType.SOCKS"))
   "field:java.net.http.HttpClient$Redirect#NEVER"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaHttpRedirect.NEVER"))
   "field:java.net.http.HttpClient$Redirect#NORMAL"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaHttpRedirect.NORMAL"))
   "field:java.net.http.HttpClient$Redirect#ALWAYS"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaHttpRedirect.ALWAYS"))
   "field:java.net.http.HttpClient$Version#HTTP_1_1"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaHttpVersion.HTTP_1_1"))
   "field:java.net.http.HttpClient$Version#HTTP_2"
   (fn [_] (raw "global::Pkl.Core.Runtime.JavaHttpVersion.HTTP_2"))
   "field:org.pkl.core.runtime.VmValueConverter#WILDCARD_PROPERTY"
   (fn [_]
     (raw
      "global::Pkl.Core.Runtime.VmValueConverter<object>.WILDCARD_PROPERTY"))
   "field:org.pkl.core.runtime.VmValueConverter#WILDCARD_ELEMENT"
   (fn [_]
     (raw
      "global::Pkl.Core.Runtime.VmValueConverter<object>.WILDCARD_ELEMENT"))
   "field:org.pkl.core.runtime.VmValueConverter#TOP_LEVEL_VALUE"
   (fn [_]
     (raw
      "global::Pkl.Core.Runtime.VmValueConverter<object>.TOP_LEVEL_VALUE"))})

(defn adapted-constructor-key?
  "True only for resolved constructors owned by the Pkl destination adapter."
  [key]
  (contains?
   #{"executable:java.io.File#<init>(java.lang.String)"
     "executable:java.io.UncheckedIOException#<init>(java.io.IOException)"
     "executable:java.net.InetSocketAddress#<init>(java.lang.String,int)"
     "executable:java.net.Proxy#<init>(java.net.Proxy$Type,java.net.SocketAddress)"
     "executable:java.net.ProxySelector#<init>()"
     "executable:java.net.ConnectException#<init>(java.lang.String)"
     "executable:javax.net.ssl.SSLException#<init>(java.lang.String)"
     "executable:javax.net.ssl.SSLHandshakeException#<init>(java.lang.String)"
     "executable:org.pkl.core.util.json.JsonParser#<init>(org.pkl.core.util.json.JsonHandler)"}
   key))

(defn constructor-adapter
  "Adapts constructors whose Pkl runtime representation differs from the
  resolved Java dependency type."
  [{:keys [destination-context ^spoon.reflect.code.CtConstructorCall element
           occurrence arguments]}]
  (or
   (case (:key occurrence)
     "executable:java.io.File#<init>(java.lang.String)"
     (first arguments)
     "executable:java.io.UncheckedIOException#<init>(java.io.IOException)"
     (compat-call "NewIOException" arguments)
     "executable:java.net.InetSocketAddress#<init>(java.lang.String,int)"
     (compat-call "NewIpEndPoint" arguments)
     "executable:java.net.Proxy#<init>(java.net.Proxy$Type,java.net.SocketAddress)"
     (pkl-runtime-call "NewWebProxy" arguments)
     "executable:java.net.ConnectException#<init>(java.lang.String)"
     (compat-call "NewIOException" arguments)
     nil)
   (when
    (= "executable:org.pkl.core.util.json.JsonParser#<init>(org.pkl.core.util.json.JsonHandler)"
       (:key occurrence))
     (let [handler-reference
           (some-> element .getArguments first .getType)
           handler-declaration
           (some-> ^CtTypeReference handler-reference .getTypeDeclaration)
           handler-base
           (when (instance? CtClass handler-declaration)
             (.getSuperclass ^CtClass handler-declaration))
           handler-arguments
           (vec (or (some-> ^CtTypeReference handler-base
                            .getActualTypeArguments
                            seq)
                    (some-> ^CtTypeReference handler-reference
                            .getActualTypeArguments)))]
       (sequence-node
        [(raw "new global::Pkl.Core.Util.Json.JsonParser(")
         (generic-call
          destination-context
          (raw "global::Pkl.Core.Util.Json.JsonHandlerBridge.Erase")
          handler-arguments
          arguments)
         (raw ")")])))
   (let [services (:services destination-context)
         declaration (:declaration occurrence)
         record (some-> element .getType .getTypeDeclaration)
         parameters
         (cond
           (instance? CtConstructor declaration)
           (vec (.getParameters ^CtConstructor declaration))

           (instance? CtRecord record)
           (vec (.getRecordComponents ^CtRecord record))

           :else [])
         references
         (mapv
          (fn [parameter]
            (cond
              (instance? CtParameter parameter)
              (.getType ^CtParameter parameter)

              (instance? CtRecordComponent parameter)
              (.getType ^CtRecordComponent parameter)))
          parameters)
         adaptation (:product-signature-collection-adaptation services)]
     (when
      (and (= :project (:origin occurrence))
           adaptation
           (= (count references) (count arguments))
           (some
            identity
            (map
             (fn [source reference]
               (or (and reference (adaptation reference))
                   (some-> source expression-declaration-type adaptation)))
             (.getArguments element)
             references)))
       (sequence-node
        [(raw "new ")
         (java-library/type-node destination-context (.getType element))
         (raw "(")
         (sequence-node
          (mapv
           (fn [source reference node]
             (coerce-product-collection-argument
              services source reference node))
           (.getArguments element)
           references
           arguments)
          ", ")
         (raw ")")])))))

(defn invocation-adapter
  "Adapts only Pkl product, Truffle-substrate, and Pkl runtime-bridge
  invocations. Returning nil delegates ordinary Java behavior to the shared
  Java-library rule bundle."
  [{:keys [destination-context ^CtInvocation element occurrence target
           target-node arguments]}]
  (let [services (:services destination-context)
        key (:key occurrence)
        argc (count arguments)
        argument #(nth arguments %)
        target-type
        (some-> ^CtExpression target .getType .getQualifiedName)
        record-property-name
        (record-property-invocation-name services element occurrence)]
    (cond
      record-property-name
      (let [node (member target-node record-property-name)
            declaration (:declaration occurrence)
            reference
            (when (instance? CtRecordComponent declaration)
              (.getType ^CtRecordComponent declaration))]
        (if
         (and
          reference
          (when-let [adaptation
                     (:product-signature-collection-adaptation services)]
            (adaptation reference)))
          (adapt-product-collection-result services reference nil node)
          node))

      (and
       (= "orElse" (.getSimpleName (.getExecutable element)))
       (or
        (and
         (instance? CtInvocation target)
         (contains?
          #{:nullable-module-key :nullable-resource}
          (when-let [adapt (:method-signature-adaptation services)]
            (adapt
             (invocation-declaration
              destination-context ^CtInvocation target)))))
        (str/ends-with?
         (or (:file (spoon/source-location element)) "")
         "ResourceReaders.java")))
      target-node

      (and
       (= key "executable:java.nio.ByteBuffer#wrap(byte[])")
       (or
        (= :idiomatic-byte-array
           (when-let [adaptation (:current-signature-adaptation services)]
             (adaptation)))
        (str/ends-with?
         (or (:file (spoon/source-location element)) "")
         "HttpClientBuilder.java"))
       (= 1 argc))
      (invoke
       (member target-node "wrap")
       [(compat-call "ToSignedBytes" [(argument 0)])])

      (contains?
       #{"executable:com.oracle.truffle.api.TruffleLanguage$ContextReference#create(java.lang.Class)"
         "executable:com.oracle.truffle.api.TruffleLanguage$LanguageReference#create(java.lang.Class)"}
       key)
      (invoke
       (member
        (java-library/type-node destination-context (.getType element))
        "Create")
       arguments)

      (and
       (instance? CtMethod (:declaration occurrence))
       (when-let [functional-interface-method?
                  (:functional-interface-method? services)]
         (functional-interface-method? (:declaration occurrence))))
      (invoke target-node arguments)

      (str/starts-with? key "executable:java.net.http.HttpClient")
      (invoke
       (member target-node
               (java-library/pascal
                (.getSimpleName (.getExecutable element))))
       arguments)

      (= key "executable:javax.net.ssl.SSLContext#getDefault()")
      (raw "global::Pkl.Core.Runtime.JavaSslContext.GetDefault()")

      (= key
         "executable:javax.net.ssl.SSLContext#getInstance(java.lang.String)")
      (invoke
       (raw "global::Pkl.Core.Runtime.JavaSslContext.GetInstance")
       arguments)

      (and
       (instance? CtMethod (:declaration occurrence))
       (= :http-send-compatibility
          (when-let [adapt (:method-signature-adaptation services)]
            (adapt (:declaration occurrence)))))
      (let [result-reference
            (first (.getActualTypeArguments (.getType element)))]
        (generic-call
         destination-context
         (raw "global::Pkl.Core.Http.HttpClientCompatibility.Send")
         [result-reference]
         (into [target-node] arguments)))

      (contains?
       #{"executable:java.lang.Appendable#append(java.lang.CharSequence)"
         "executable:java.lang.Appendable#append(java.lang.CharSequence,int,int)"
         "executable:java.lang.Appendable#append(char)"}
       key)
      (invoke (member target-node "Append") arguments)

      (contains?
       #{"executable:java.nio.file.spi.FileSystemProvider#installedProviders()"
         "executable:java.nio.file.spi.FileSystemProvider#getScheme()"
         "executable:java.nio.file.FileSystem#getRootDirectories()"}
       key)
      (invoke
       (member target-node
               (java-library/pascal
                (.getSimpleName (.getExecutable element))))
       arguments)

      (contains?
       #{"executable:java.net.ProxySelector#getDefault()"
         "executable:java.net.ProxySelector#select(java.net.URI)"}
       key)
      (invoke
       (member target-node
               (java-library/pascal
                (.getSimpleName (.getExecutable element))))
       arguments)

      (= key "executable:java.net.URL#openConnection()")
      (invoke
       (raw "new global::Pkl.Core.Runtime.JavaUrlConnection")
       [target-node])

      (= key "executable:java.io.File#toPath()")
      target-node

      (= key "executable:java.util.zip.ZipInputStream#readAllBytes()")
      (compat-call
       "ToSignedBytes"
       [(invoke (member target-node "ReadAllBytes") [])])

      (contains?
       #{"executable:java.security.MessageDigest#getInstance(java.lang.String)"
         "executable:java.security.MessageDigest#digest()"
         "executable:java.security.MessageDigest#digest(byte[])"
         "executable:java.security.MessageDigest#update(byte)"
         "executable:java.security.MessageDigest#update(byte[])"
         "executable:java.security.MessageDigest#update(byte[],int,int)"}
       key)
      (invoke
       (member
        target-node
        (cond
          (str/includes? key "#getInstance(") "GetInstance"
          (str/includes? key "#digest(") "Digest"
          :else "Update"))
       arguments)

      (= key
         "executable:java.util.Map#ofEntries(java.util.Map$Entry[])")
      (result-generic-call
       destination-context
       element
       "global::Pkl.Core.Runtime.PklRuntimeBridge."
       "MapOfEntriesLoose"
       arguments)

      (= key
         "executable:java.time.Duration#of(long,java.time.temporal.TemporalUnit)")
      (pkl-runtime-call "DurationOf" arguments)

      (= key
         "executable:java.util.concurrent.Executors#newSingleThreadScheduledExecutor(java.util.concurrent.ThreadFactory)")
      (invoke (member target-node "NewSingleThreadScheduledExecutor") arguments)

      (= key
         "executable:java.util.concurrent.ScheduledExecutorService#schedule(java.lang.Runnable,long,java.util.concurrent.TimeUnit)")
      (invoke (member target-node "Schedule") arguments)

      (str/starts-with? key "executable:org.msgpack.")
      (invoke
       (member
        target-node
        (java-library/pascal
         (.getSimpleName (.getExecutable element))))
       arguments)

      (= key
         "executable:org.pkl.core.runtime.Iterators#emptyTruffleIterator()")
      (result-generic-call
       destination-context
       element
       "global::DripSharp.Runtime.JavaCompat."
       "EmptyJavaIterator"
       [])

      (= key
         "executable:org.pkl.core.stdlib.VmObjectFactory$Property#identity()")
      (raw "value => value")

      (= key
         "executable:org.pkl.core.stdlib.VmObjectFactory$Property#evaluate(java.lang.Object)")
      (invoke target-node arguments)

      (and (str/starts-with?
            key
            "executable:org.pkl.core.stdlib.VmObjectFactory#create(")
           (= 1 argc)
           (let [argument (first (.getArguments element))]
             (and (instance? CtLiteral argument)
                  (nil? (.getValue ^CtLiteral argument)))))
      (invoke (member target-node "Create") [(raw "default!")])

      (= key
         "executable:org.pkl.core.StackFrameTransformer#andThen(org.pkl.core.StackFrameTransformer)")
      (invoke
       (raw "global::Pkl.Core.StackFrameTransformerExtensions.AndThen")
       (into [target-node] arguments))

      (and (= "replace" (.getSimpleName (.getExecutable element)))
           (= "com.oracle.truffle.api.nodes.Node"
              (some-> ^CtInvocation element
                      .getExecutable
                      .getDeclaringType
                      .getQualifiedName))
           (= 1 argc))
      (invoke
       (csharp/generic-name
        (raw (str (:text (csharp/render target-node)) ".Replace"))
        [(java-library/type-node destination-context (.getType element))])
       [(sequence-node
         [(raw "(")
          (java-library/type-node destination-context (.getType element))
          (raw ")(")
          (argument 0)
          (raw ")")])])

      (= key
         "executable:org.pkl.core.http.HttpClient$Builder#addCertificates(byte[])")
      (invoke
       (member target-node "AddCertificates")
       [(compat-call "ToUnsignedBytes" [(argument 0)])])

      (= key "executable:org.pkl.parser.Span#charIndex()")
      (member target-node "CharIndex")

      (= key "executable:org.pkl.parser.Span#length()")
      (member target-node "Length")

      (= key "executable:org.organicdesign.fp.tuple.Tuple2#_1()")
      (member target-node "_1")

      (= key "executable:org.organicdesign.fp.tuple.Tuple2#_2()")
      (member target-node "_2")

      (str/starts-with?
       key
       "executable:org.organicdesign.fp.tuple.Tuple2#of(")
      (invoke (member target-node "Of") arguments)

      (= key "executable:org.organicdesign.fp.function.Fn0#apply()")
      (invoke target-node [])

      (str/starts-with?
       key
       "executable:org.organicdesign.fp.oneOf.Option#match(")
      (invoke (member target-node "Match") arguments)

      (str/starts-with?
       key
       "executable:org.organicdesign.fp.indent.IndentUtils#arrayString(")
      (invoke (member target-node "ArrayString") arguments)

      (str/starts-with?
       key
       "executable:org.organicdesign.fp.indent.IndentUtils#indentSpace(")
      (invoke (member target-node "IndentSpace") arguments)

      (str/starts-with?
       key
       "executable:org.organicdesign.fp.indent.Indented#indentedStr(")
      (invoke (member target-node "IndentedStr") arguments)

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.PersistentHashMap#")
       (or (str/includes? key "#empty(")
           (str/includes? key "#emptyMutable(")))
      (result-generic-call
       destination-context
       element
       "global::DripSharp.Runtime.JavaCompat."
       "MapOf"
       [])

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.PersistentHashSet#")
       (or (str/includes? key "#empty(")
           (str/includes? key "#emptyMutable(")))
      (result-generic-call
       destination-context
       element
       "global::DripSharp.Runtime.JavaCompat."
       "SetOf"
       [])

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#mutable()"))
      (compat-call "Mutable" [target-node])

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#immutable()"))
      target-node

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#assoc(")
       (or (= "org.organicdesign.fp.collections.MutMap" target-type)
           (str/includes? (or target-type "") "MutHashMap")))
      (compat-call "OrganicPut" (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#assoc("))
      (compat-call "Assoc" (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#get("))
      (compat-call "OrganicGet" (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#put("))
      (compat-call
       (if (= "org.organicdesign.fp.collections.ImSet" target-type)
         "Assoc"
         "OrganicPut")
       (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/ends-with? key "#hashCode()"))
      (compat-call "HashCode" [target-node])

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#without("))
      (compat-call "Without" (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#size()"))
      (compat-call "CollectionCount" [target-node])

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#isEmpty()"))
      (compat-call "CollectionIsEmpty" [target-node])

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#keySet()"))
      (compat-call "MapKeySet" [target-node])

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#containsValue("))
      (compat-call "MapContainsValue" (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#containsKey("))
      (compat-call "MapContainsKey" (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.")
       (str/includes? key "#contains("))
      (compat-call "CollectionContains" (into [target-node] arguments))

      (and
       (str/includes? (or target-type "")
                      "org.pkl.core.util.paguro.RrbTree")
       (contains?
        #{"subList" "contains" "indexOf" "lastIndexOf" "take" "drop"
          "reverse" "toArray" "toImSet" "concat"}
        (.getSimpleName (.getExecutable element)))
       (not (and (instance? CtSuperAccess target)
                 (= "concat" (.getSimpleName (.getExecutable element))))))
      (invoke
       (member
        target-node
        (java-library/pascal
         (.getSimpleName (.getExecutable element))))
       arguments)

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.xform.Xform#of(")
       (= 1 argc))
      (argument 0)

      (and
       (str/starts-with? key "executable:org.organicdesign.fp.xform.")
       (str/includes? key "#take("))
      (compat-call "TakeValues" (into [target-node] arguments))

      (and
       (str/starts-with? key "executable:org.organicdesign.fp.xform.")
       (str/includes? key "#drop("))
      (compat-call "DropValues" (into [target-node] arguments))

      (and
       (str/starts-with? key "executable:org.organicdesign.fp.xform.")
       (str/includes? key "#concat("))
      (compat-call "ConcatValues" (into [target-node] arguments))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.UnmodSortedIterable#castFromList(")
       (= 1 argc))
      (argument 0)

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.UnmodSortedIterable#equal(")
       (= 2 argc))
      (compat-call "SequenceEqual" arguments)

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.UnmodIterable#toString(")
       (= 2 argc))
      (compat-call "IterableString" arguments)

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.Cowry#emptyArray(")
       (zero? argc))
      (let [component (array-component-reference element)
            current-type ^CtType (:current-type destination-context)
            current-member (:current-member destination-context)
            static-member?
            (and (instance? CtModifiable current-member)
                 (.hasModifier ^CtModifiable current-member
                               ModifierKind/STATIC))
            current-parameter
            (when (and current-type
                       (not static-member?)
                       (= "java.lang.Object"
                          (some-> component .getQualifiedName))
                       (str/starts-with?
                        (.getQualifiedName current-type)
                        "org.pkl.core.util.paguro.RrbTree"))
              (or
               (some-> current-type
                       .getFormalCtTypeParameters
                       first)
               (some-> current-type
                       .getDeclaringType
                       .getFormalCtTypeParameters
                       first)))]
        (invoke
         (csharp/generic-name
          (raw "global::DripSharp.Runtime.JavaCompat.EmptyArray")
          [(if current-parameter
             (raw ((:type-parameter-name services) current-parameter))
             (java-library/type-node destination-context component))])
         []))

      (and
       (str/starts-with?
        key
        "executable:org.pkl.core.util.paguro.RrbTree#singleElementArray(")
       (= 1 argc))
      (generic-call
       destination-context
       (raw "global::DripSharp.Runtime.JavaCompat.SingleElementArray")
       [(some-> element .getArguments first .getType)]
       arguments)

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.Cowry#arrayCopy(")
       (= 3 argc))
      (source-array-generic-call
       destination-context element
       (raw "global::DripSharp.Runtime.JavaCompat.ArrayCopy")
       arguments)

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.Cowry#splitArray(")
       (= 2 argc))
      (let [component
            (array-component-reference
             (first (.getArguments element)))]
        (if (= "int" (some-> component .getQualifiedName))
          (pkl-runtime-call "SplitArray" arguments)
          (generic-call
           destination-context
           (raw "global::Pkl.Core.Runtime.PklRuntimeBridge.SplitArray")
           [component]
           arguments)))

      (and
       (str/starts-with?
        key
        "executable:org.organicdesign.fp.collections.Cowry#")
       (contains?
        #{"spliceIntoArrayAt" "insertIntoArrayAt" "replaceInArrayAt"}
        (.getSimpleName (.getExecutable element))))
      (source-array-generic-call
       destination-context element
       (raw
        (str
         "global::DripSharp.Runtime.JavaCompat."
         (java-library/pascal
          (.getSimpleName (.getExecutable element)))))
       arguments)

      (and
       (str/starts-with?
        key
        "executable:org.graalvm.collections.EconomicMap#create(")
       (<= argc 1))
      (result-generic-call
       destination-context
       element
       "global::Pkl.Core.Runtime.PklRuntimeBridge."
       "CreateEconomicMap"
       arguments)

      (and
       (str/starts-with?
        key
        "executable:org.graalvm.collections.EconomicSet#create(")
       (<= argc 1))
      (result-generic-call
       destination-context
       element
       "global::Pkl.Core.Runtime.GraalCollections."
       "EconomicSet.Create"
       arguments)

      (and
       (str/starts-with?
        key
        "executable:org.graalvm.collections.EconomicMap#emptyMap(")
       (zero? argc))
      (result-generic-call
       destination-context
       element
       "global::Pkl.Core.Runtime.PklRuntimeBridge."
       "EmptyEconomicMap"
       [])

      (and
       (= "getNullable" (.getSimpleName (.getExecutable element)))
       (= 2 argc)
       (= "org.pkl.core.packages.DependencyMetadata"
          (some-> element enclosing-type .getQualifiedName))
       (instance? spoon.reflect.code.CtExecutableReferenceExpression
                  (second (.getArguments element)))
       (= "parseAuthors"
          (some-> ^spoon.reflect.code.CtExecutableReferenceExpression
           (second (.getArguments element))
                  .getExecutable
                  .getSimpleName)))
      (invoke
       (csharp/generic-name
        (raw
         (str (:text (csharp/render target-node))
              ".GetNullable"))
        [(raw "global::System.Collections.Generic.IList<string>")])
       [(argument 0)
        (sequence-node
         [(raw "value => global::DripSharp.Runtime.JavaCompat.ToMutable<")
          (raw "global::System.Collections.Generic.IList<string>>(")
          (argument 1) (raw "(value))")])])

      (and
       (= "getNullable" (.getSimpleName (.getExecutable element)))
       (= 2 argc)
       (instance? spoon.reflect.code.CtExecutableReferenceExpression
                  (second (.getArguments element)))
       (let [reference-expression
             ^spoon.reflect.code.CtExecutableReferenceExpression
             (second (.getArguments element))
             declaration
             (some-> (occurrence destination-context
                                 (.getExecutable reference-expression))
                     :declaration)]
         (and
          (instance? CtMethod declaration)
          (contains?
           #{:read-only-product-list
             :read-only-product-map
             :read-only-product-set
             :read-only-product-collection}
           (when-let [adapt (:method-signature-adaptation services)]
             (adapt declaration))))))
      (let [reference-expression
            ^spoon.reflect.code.CtExecutableReferenceExpression
            (second (.getArguments element))
            declaration
            (some-> (occurrence destination-context
                                (.getExecutable reference-expression))
                    :declaration)
            return-reference (.getType ^CtMethod declaration)
            mutable-result
            (adapt-product-collection-result
             services return-reference nil
             (invoke (argument 1) [(raw "value")]))]
        (invoke
         (member target-node
                 (java-library/pascal
                  (.getSimpleName (.getExecutable element))))
         [(argument 0)
          (sequence-node [(raw "value => ") mutable-result])]))

      (and
       (str/starts-with?
        key
        "executable:org.pkl.core.util.paguro.RrbTree#genericNodeArray(")
       (= 1 argc))
      (let [component (.getComponentType (.getType element))
            element-type (first (.getActualTypeArguments component))]
        (generic-call
         destination-context
         (raw
          "global::Pkl.Core.Util.Paguro.RrbTree<object>.GenericNodeArray")
         [element-type]
         arguments))

      (and
       (not (instance? CtSuperAccess target))
       (contains?
        #{"executable:org.pkl.core.runtime.ReaderBase#hasHierarchicalUris()"
          "executable:org.pkl.core.runtime.ReaderBase#isGlobbable()"
          "executable:org.pkl.core.runtime.ReaderBase#hasFragmentPaths()"
          "executable:org.pkl.core.runtime.ReaderBase#hasElement(org.pkl.core.SecurityManager,java.net.URI)"
          "executable:org.pkl.core.runtime.ReaderBase#listElements(org.pkl.core.SecurityManager,java.net.URI)"
          "executable:org.pkl.core.runtime.ReaderBase#resolveUri(java.net.URI,java.net.URI)"}
        key))
      (invoke
       (member
        target-node
        (java-library/pascal
         (.getSimpleName (.getExecutable element))))
       arguments)

      (and (= 2 argc)
           (instance? CtSuperAccess target)
           (= "resolveUri"
              (.getSimpleName (.getExecutable element))))
      (invoke
       (raw "global::Pkl.Core.Util.IoUtils.Resolve")
       (into [(raw "this")] arguments))

      (and (instance? CtSuperAccess target)
           (= "visit" (.getSimpleName (.getExecutable element)))
           (= 1 argc))
      (pkl-runtime-call "VisitVmValue" [(raw "this") (argument 0)])

      (and (instance? CtSuperAccess target)
           (str/starts-with?
            key
            "executable:org.organicdesign.fp.collections.MutList#concat(")
           (= 1 argc))
      (pkl-runtime-call
       "MutableConcat"
       [(raw "this") (argument 0)])

      (str/ends-with? key "#getCause()")
      (member target-node "InnerException")

      (and (str/includes? key "GeneratorSpreadNode#spreadIterable(")
           (= 3 argc))
      (invoke
       (member target-node "SpreadIterable")
       [(argument 0)
        (argument 1)
        (compat-call "BoxValues" [(argument 2)])])

      (and (str/includes? key "ValueFormatter#formatStringValue(")
           (= 3 argc)
           (= "java.lang.StringBuilder"
              (some-> (nth (.getArguments element) 1)
                      .getType
                      .getQualifiedName)))
      (invoke
       (member target-node "FormatStringValue")
       (assoc arguments
              1
              (invoke (member (argument 1) "ToString") [])))

      (and (= "handleBadValue" (.getSimpleName (.getExecutable element)))
           (str/includes?
            (or (some-> element enclosing-type .getQualifiedName) "")
            "CommandSpecParser")
           (= 1 argc))
      (invoke
       (member target-node "HandleBadValue")
       [(sequence-node
         [(raw "((global::System.Func<object>)(")
          (argument 0)
          (raw "))")])])

      (and
       (contains?
        #{"executable:java.util.Collection#toArray(java.lang.Object[])"
          "executable:java.util.List#toArray(java.lang.Object[])"
          "executable:java.util.ArrayList#toArray(java.lang.Object[])"}
        key)
       (str/ends-with?
        (or (:file (spoon/source-location element)) "")
        "PklConverter.java"))
      (compat-call "ToArray" [target-node])

      (and (= "accept" (.getSimpleName (.getExecutable element)))
           (= 2 argc)
           (str/starts-with? (:text (csharp/render target-node)) "vmValue"))
      (invoke (member target-node "Accept") arguments)

      (and (= "accept" (.getSimpleName (.getExecutable element)))
           (not= :jdk (:origin occurrence))
           (= "void" (some-> ^CtInvocation element .getType
                             .getQualifiedName)))
      (invoke (member target-node "Accept") arguments)

      (and (= "accept" (.getSimpleName (.getExecutable element)))
           (not= :jdk (:origin occurrence))
           (contains? #{1 2} argc)
           (not= "void" (some-> ^CtInvocation element .getType
                                .getQualifiedName))
           (pkl-core-element? element))
      (let [owner-name
            (or (some-> element enclosing-type .getQualifiedName) "")
            source-file
            (or (:file (spoon/source-location element)) "")
            result-reference
            (if (or (= "org.pkl.core.stdlib.PklConverter" owner-name)
                    (= "org.pkl.core.stdlib.PklConverter"
                       (some-> (.getArguments element)
                               first
                               .getType
                               .getQualifiedName))
                    (str/ends-with? source-file "PklConverter.java"))
              nil
              (or
               (when-let [current-return
                          (:current-product-return-reference services)]
                 (current-return))
               (enclosing-method-return-type element)
               (.getType element)))
            result-node
            (if (and
                 (str/starts-with?
                  (or (some-> ^CtExpression target .getType .getQualifiedName)
                      "")
                  "org.pkl.parser.syntax.")
                 (= "org.pkl.core.ast.builder.AstBuilder" owner-name))
              (raw "object")
              (if result-reference
                (java-library/type-node destination-context result-reference)
                (raw "object")))]
        (invoke
         (csharp/generic-name
          (raw (str (:text (csharp/render target-node)) ".Accept"))
          [result-node])
         arguments))

      (and (= :project (:origin occurrence))
           (contains?
            #{"org.pkl.core.ValueConverter"
              "org.pkl.core.runtime.VmValueConverter"}
            (some-> (:declaration occurrence)
                    .getDeclaringType
                    .getQualifiedName))
           (= "convert" (.getSimpleName (.getExecutable element))))
      (invoke (member target-node "Convert") arguments)

      (and (= "doVisitCollection"
              (.getSimpleName (.getExecutable element)))
           (str/includes?
            (or (some-> element enclosing-type .getQualifiedName) "")
            "JsonRenderer")
           (= 1 argc))
      (invoke
       (member target-node "DoVisitCollection")
       [(compat-call "ObjectCollection" [(argument 0)])])

      (and (= "doVisitMap" (.getSimpleName (.getExecutable element)))
           (str/includes?
            (or (some-> element enclosing-type .getQualifiedName) "")
            "PropertiesRenderer")
           (= 2 argc))
      (invoke
       (member target-node "DoVisitMap")
       [(argument 0)
        (compat-call "ObjectMap" [(argument 1)])])

      (and (= "moduleOutputValueTypeMismatch"
              (.getSimpleName (.getExecutable element)))
           (= 4 argc))
      (invoke
       (member target-node "ModuleOutputValueTypeMismatch")
       [(argument 0)
        (invoke (member (argument 1) "AsObject") [])
        (argument 2)
        (argument 3)])

      (and (= 1 argc)
           (or
            (str/starts-with?
             key
             "executable:org.pkl.core.ValueVisitor#visitPair(")
            (str/starts-with?
             key
             "executable:org.pkl.core.ValueConverter#convertPair(")))
      (invoke
       (member
        target-node
        (java-library/pascal
         (.getSimpleName (.getExecutable element))))
       [(pkl-runtime-call "ObjectPair" [(argument 0)])])

      :else nil)))

(defn constructor-initializer
  "Emits the Pkl declaration layer's C# constructor initializer from a resolved
  Java this/super invocation. Ordinary argument expressions are translated by
  the shared Java-library body context."
  [translation-context ^CtInvocation invocation outer-argument]
  (let [occurrence
        (occurrence translation-context (.getExecutable invocation))
        called-owner
        (some-> invocation
                .getExecutable
                .getDeclaringType
                .getQualifiedName)
        ^CtConstructor owner
        (loop [current (.getParent invocation)]
          (cond
            (nil? current) nil
            (instance? CtConstructor current) current
            :else (recur (.getParent ^CtElement current))))
        label
        (if (= called-owner
               (some-> owner .getDeclaringType .getQualifiedName))
          "this"
          "base")
        arguments
        (into
         (vec (when outer-argument [{:node outer-argument}]))
         (mapv
          #(java-library/translate-body translation-context %)
          (.getArguments invocation)))
        arguments
        (if outer-argument
          (mapv
           (fn [{:keys [node] :as argument}]
             (assoc
              argument
              :node
              (raw
               (str/replace
                (:text (csharp/render node))
                "this.__outer"
                "__outer"))))
           arguments)
          arguments)
        declaration
        (.getExecutableDeclaration (.getExecutable invocation))
        parameters
        (when declaration (vec (.getParameters declaration)))
        services (:services translation-context)
        arguments
        (mapv
         (fn [index {:keys [node] :as argument}]
           (let [parameter-index
                 (- index (if outer-argument 1 0))]
             (if (and parameters
                      (<= 0 parameter-index)
                      (< parameter-index (count parameters)))
               (assoc
                argument
                :node
                (coerce-product-collection-argument
                 services
                 (nth (vec (.getArguments invocation)) parameter-index)
                 (.getType
                  ^CtParameter
                  (nth parameters parameter-index))
                 node))
               argument)))
         (range)
         arguments)
        option-behavior-delegating?
        (and
         (= "org.pkl.core.runtime.CommandSpecParser$OptionBehavior"
            (some-> owner .getDeclaringType .getQualifiedName))
         (= 2 (count (.getParameters owner)))
         (= 5 (count (.getArguments invocation))))
        exception-cause-initializer?
        (and
         (= label "base")
         (contains?
          #{"java.lang.Throwable" "java.lang.Exception"
            "java.lang.RuntimeException"}
          called-owner)
         (= ["java.lang.Throwable"]
            (mapv
             #(.getQualifiedName ^CtTypeReference %)
             (.getParameters (.getExecutable invocation)))))
        node
        (cond
          exception-cause-initializer?
          (let [cause (:node (first arguments))]
            (sequence-node
             [(raw " : base(")
              (compat-call "ExceptionMessage" [cause])
              (raw ", ")
              cause
              (raw ")")]))

          option-behavior-delegating?
          (raw
           (str
            " : this(__outer, "
            "annotation is null ? null : "
            "global::Pkl.Core.Runtime.VmUtils.ReadMember(annotation, global::Pkl.Core.Runtime.Identifier.CONVERT) "
            "is global::Pkl.Core.Runtime.VmFunction convertFunc ? "
            "(global::System.Func<string, global::System.Uri, object>)((rawValue, workingDirUri) => "
            "__outer.HandleBadValue(() => __outer.HandleImports(convertFunc.Apply(rawValue), workingDirUri))) : null, "
            "annotation is null ? null : "
            "global::Pkl.Core.Runtime.VmUtils.ReadMember(annotation, global::Pkl.Core.Runtime.Identifier.TRANSFORM_ALL) "
            "is global::Pkl.Core.Runtime.VmFunction transformAllFunc ? "
            "(global::System.Func<global::System.Collections.Generic.IList<object>, global::System.Uri, object?>)"
            "((values, workingDirUri) => __outer.HandleBadValue(() => __outer.HandleImports("
            "transformAllFunc.Apply(global::Pkl.Core.Runtime.VmList.CreateFromIterable(values)), workingDirUri))) : null, "
            "annotation is null ? (bool?)null : "
            "global::Pkl.Core.Runtime.VmUtils.ReadMember(annotation, global::Pkl.Core.Runtime.Identifier.MULTIPLE) "
            "is bool multipleValue ? multipleValue : (bool?)null, "
            "annotation is null ? null : hasMetavar ? "
            "CommandSpecParser.ExportNullableString(annotation, global::Pkl.Core.Runtime.Identifier.METAVAR) : null, "
            "annotation is null ? null : OptionBehavior.ExportCompletionCandidates(annotation))"))

          :else
          (sequence-node
           [(raw (str " : " label "("))
            (sequence-node (mapv :node arguments) ", ")
            (raw ")")]))]
    (-> node
        (csharp/with-source
          {:identity (spoon/frontend-identity invocation)
           :location (spoon/source-location invocation)
           :rule :java.constructor/initializer})
        (csharp/with-source
          {:identity
           (spoon/frontend-identity (.getExecutable invocation))
           :location
           (spoon/source-location (.getExecutable invocation))
           :rule :resolved.constructor/project
           :mapping
           {:registry :constructors
            :identity :resolved.constructor/project
            :resolved-key (:key occurrence)
            :origin (:origin occurrence)
            :resolution (:resolution occurrence)}}))))
