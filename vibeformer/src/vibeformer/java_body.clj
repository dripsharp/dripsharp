(ns vibeformer.java-body
  "Resolved, fail-closed translation of Java executable Spoon trees.

  Structural rules consume only live Spoon objects and their already translated
  live children.  Semantic rules are installed for each exact resolved symbol
  identity in the model; project symbols and the deliberately supported JDK
  surface are never recovered from rendered Java text."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-translate :as java]
            [vibeformer.spoon :as spoon])
  (:import [java.util IdentityHashMap]
           [spoon.reflect.code BinaryOperatorKind CtArrayRead CtArrayWrite CtAssert
            CtAssignment CtBinaryOperator CtBlock CtBreak CtCase CtCatch CtContinue
            CtCatchVariable CtComment CtConditional CtConstructorCall CtDo
            CtExecutableReferenceExpression CtExpression CtFieldRead CtFieldWrite
            CtFor CtForEach CtIf CtInvocation CtLambda CtLiteral CtLocalVariable
            CtNewArray CtOperatorAssignment CtReturn CtStatement CtSuperAccess
            CtSwitch CtSwitchExpression CtSynchronized CtThisAccess CtThrow CtTry CtTypeAccess
            CtTypePattern CtUnaryOperator CtVariableRead CtVariableWrite CtWhile
            CtYieldStatement UnaryOperatorKind]
           [spoon.reflect.declaration CtAnnotation CtClass CtConstructor CtElement CtField CtMethod
            CtParameter CtRecordComponent]
           [spoon.reflect.reference CtCatchVariableReference CtExecutableReference
            CtFieldReference CtLocalVariableReference CtPackageReference
            CtParameterReference CtTypeReference]
           [spoon.reflect.visitor.filter TypeFilter]))

(defn- raw [value] (csharp/raw (str value)))
(defn- sequence-node
  ([nodes] (csharp/sequence-node (vec (remove nil? nodes))))
  ([nodes separator]
   (csharp/sequence-node (vec (remove nil? nodes)) separator)))

(defn- child-node [children element]
  (:node (java/child-result children element)))

(defn- children-nodes [children elements]
  (mapv #(child-node children %) elements))

(defn- role [^CtElement element]
  (when (.isParentInitialized element) (str (.getRoleInParent element))))

(defn- statement-expression? [^CtElement element]
  (and (instance? CtStatement element)
       (let [element-role (role element)
             parent (when (.isParentInitialized element) (.getParent element))]
         (or (= "statement" element-role)
             (and (contains? #{"then" "else"} element-role)
                  (instance? CtIf parent))
             (and (= "body" element-role)
                  (or (instance? CtFor parent) (instance? CtForEach parent)
                      (instance? CtWhile parent) (instance? CtDo parent)))))))

(defn- identifier [services value]
  ((:identifier services) (str value)))

(defn- local-name [services element]
  (if-let [namer (:local-name services)]
    (namer element)
    (identifier services (.getSimpleName element))))

(defn- enclosing-type [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? spoon.reflect.declaration.CtType current) current
      :else (recur (when (.isParentInitialized ^CtElement current)
                     (.getParent ^CtElement current))))))

(defn- nullable-annotation? [^CtElement element]
  (boolean
   (some #(= "org.jspecify.annotations.Nullable"
             (some-> ^CtAnnotation % .getAnnotationType .getQualifiedName))
         (.getAnnotations element))))

(defn- nullable-type? [^CtTypeReference reference]
  (and reference
       (not (.isPrimitive reference))
       (or (nullable-annotation? reference)
           (some nullable-type? (.getActualTypeArguments reference)))))

(defn- nullable-expression? [expression]
  (cond
    (and (instance? CtLiteral expression)
         (nil? (.getValue ^CtLiteral expression))) true
    (nullable-annotation? expression) true
    (nullable-type? (.getType ^CtExpression expression)) true
    (instance? CtVariableRead expression)
    (let [declaration (some-> ^CtVariableRead expression .getVariable .getDeclaration)]
      (boolean (and declaration
                    (or (nullable-annotation? declaration)
                        (nullable-type? (.getType declaration))))))
    (instance? CtInvocation expression)
    (let [declaration (some-> ^CtInvocation expression .getExecutable .getExecutableDeclaration)]
      (boolean (and declaration
                    (or (nullable-annotation? declaration)
                        (nullable-type? (.getType ^CtMethod declaration))))))
    (instance? CtNewArray expression)
    (boolean (some nullable-expression? (.getElements ^CtNewArray expression)))
    :else false))

(defn- member [target name]
  (if target
    (csharp/member target name)
    (raw name)))

(defn- invoke [target arguments]
  (csharp/invocation target arguments))

(defn- wrap-casts [services children ^CtExpression expression node]
  (reduce (fn [value ^CtTypeReference cast]
            (if (.isPrimitive cast)
              (sequence-node [(raw "(") (child-node children cast) (raw ")(") value (raw ")")])
              (sequence-node [(raw "((") (child-node children cast) (raw ")(") value (raw "!))!")])))
          node
          (reverse (vec (.getTypeCasts expression)))))

(defn- finish-expression [services children ^CtExpression expression node]
  (let [node (wrap-casts services children expression node)]
    (if (statement-expression? expression)
      (sequence-node [node (raw ";")])
      node)))

(defn- escape-string [value]
  (str "\""
       (apply str
              (map (fn [ch]
                     (case ch
                       \" "\\\""
                       \\ "\\\\"
                       \newline "\\n"
                       \return "\\r"
                       \tab "\\t"
                       \backspace "\\b"
                       \formfeed "\\f"
                       (if (or (< (int ch) 32) (= (int ch) 127))
                         (format "\\u%04X" (int ch))
                         (str ch))))
                   value))
       "\""))

(defn- escape-char [value]
  (let [ch (char value)]
    (str "'"
         (case ch
           \' "\\'"
           \\ "\\\\"
           \newline "\\n"
           \return "\\r"
           \tab "\\t"
           \backspace "\\b"
           \formfeed "\\f"
           (if (or (< (int ch) 32) (> (int ch) 126))
             (format "\\u%04X" (int ch))
             (str ch)))
         "'")))

(defn- literal-text [value]
  (cond
    (nil? value) "null"
    (string? value) (escape-string value)
    (instance? Character value) (escape-char value)
    (instance? Boolean value) (if value "true" "false")
    (instance? Long value) (str value "L")
    (instance? Float value) (str value "F")
    (instance? Double value)
    (cond
      (Double/isNaN value) "double.NaN"
      (= Double/POSITIVE_INFINITY value) "double.PositiveInfinity"
      (= Double/NEGATIVE_INFINITY value) "double.NegativeInfinity"
      :else (str value "D"))
    :else (str value)))

(def ^:private binary-operators
  {"AND" ["&&" 30]
   "OR" ["||" 20]
   "EQ" ["==" 40]
   "NE" ["!=" 40]
   "LT" ["<" 50]
   "LE" ["<=" 50]
   "GT" [">" 50]
   "GE" [">=" 50]
   "PLUS" ["+" 60]
   "MINUS" ["-" 60]
   "MUL" ["*" 70]
   "DIV" ["/" 70]
   "MOD" ["%" 70]
   "BITAND" ["&" 37]
   "BITXOR" ["^" 36]
   "BITOR" ["|" 35]
   "SL" ["<<" 55]
   "SR" [">>" 55]
   "USR" [">>>" 55]})

(defn- primitive-expression? [^CtExpression expression]
  (boolean (some-> expression .getType .isPrimitive)))

(defn- string-expression? [^CtExpression expression]
  (= "java.lang.String" (some-> expression .getType .getQualifiedName)))

(defn- binary-node [services ^CtBinaryOperator element children]
  (let [kind (str (.getKind element))
        left-expression (.getLeftHandOperand element)
        right-expression (.getRightHandOperand element)
        left (child-node children left-expression)
        right (child-node children right-expression)]
    (cond
      (= kind "INSTANCEOF")
      (csharp/binary "is" 40 left right)

      (and (contains? #{"EQ" "NE"} kind)
           (not (or (primitive-expression? left-expression)
                    (primitive-expression? right-expression))))
      (let [equals (invoke (raw "global::System.Object.ReferenceEquals") [left right])]
        (if (= kind "NE") (csharp/prefix "!" equals) equals))

      (and (= kind "PLUS") (string-expression? element))
      (invoke (raw "global::Vibeformer.Runtime.JavaCompat.Concat") [left right])

      :else
      (if-let [[operator precedence] (get binary-operators kind)]
        (csharp/binary operator precedence left right)
        (throw (ex-info "Unsupported Java binary operator"
                        {:kind :unsupported-java-binary-operator :operator kind
                         :source (spoon/source-location element)}))))))

(defn- unary-node [^CtUnaryOperator element operand]
  (case (str (.getKind element))
    "NEG" (csharp/prefix "-" operand)
    "POS" (csharp/prefix "+" operand)
    "COMPL" (csharp/prefix "~" operand)
    "NOT" (csharp/prefix "!" operand)
    "PREINC" (csharp/prefix "++" operand)
    "PREDEC" (csharp/prefix "--" operand)
    "POSTINC" (sequence-node [operand (raw "++")])
    "POSTDEC" (sequence-node [operand (raw "--")])
    (throw (ex-info "Unsupported Java unary operator"
                    {:kind :unsupported-java-unary-operator
                     :operator (str (.getKind element))
                     :source (spoon/source-location element)}))))

(defn- occurrence [context element]
  (.get ^IdentityHashMap (:occurrence-index context) element))

(defn- executable-key [context ^CtInvocation invocation]
  (:key (occurrence context (.getExecutable invocation))))

(defn- normal-invocation [element children]
  (let [target-element (.getTarget ^CtInvocation element)
        target (when target-element
                 (let [node (child-node children target-element)]
                   (if (nullable-expression? target-element)
                     (sequence-node [node (raw "!")])
                     node)))
        executable (child-node children (.getExecutable ^CtInvocation element))
        callable (if target (member target (:text (csharp/render executable))) executable)]
    (invoke callable (children-nodes children (.getArguments ^CtInvocation element)))))

(defn- class-literal? [^CtFieldRead element]
  (and (= "class" (some-> element .getVariable .getSimpleName))
       (instance? CtTypeAccess (.getTarget element))))

(defn- compat-call [name arguments]
  (invoke (raw (str "global::Vibeformer.Runtime.JavaCompat." name)) arguments))

(defn- expected-nullable-collection? [^CtInvocation invocation]
  (let [parent (when (.isParentInitialized invocation) (.getParent invocation))]
    (when (or (instance? CtConstructorCall parent) (instance? CtInvocation parent))
      (let [arguments (vec (.getArguments parent))
            index (first (keep-indexed #(when (identical? invocation %2) %1) arguments))
            executable (.getExecutable parent)
            declaration (.getExecutableDeclaration executable)
            parameters (when declaration (vec (.getParameters declaration)))
            parameter (when (and index (< index (count parameters))) (nth parameters index))]
        (let [actuals (when parameter
                        (vec (.getActualTypeArguments (.getType ^CtParameter parameter))))]
          (boolean (and (= 1 (count actuals)) (nullable-type? (first actuals)))))))))

(defn- collection-element-type-node [services ^CtInvocation invocation]
  (let [arguments (vec (.getActualTypeArguments (.getType invocation)))]
    (when-not (= 1 (count arguments))
      (throw (ex-info "Resolved collection factory has no single element type"
                      {:kind :unsupported-collection-factory-type
                       :source (spoon/source-location invocation)
                       :type (some-> invocation .getType .getQualifiedName)})))
    (let [type-node ((:type-node services) (first arguments))
          nullable-element? (or (some nullable-expression? (.getArguments invocation))
                                (expected-nullable-collection? invocation))]
      (if (and nullable-element? (not (.isPrimitive ^CtTypeReference (first arguments))))
        (sequence-node [type-node (raw "?")])
        type-node))))

(defn- generic-compat-call [services invocation name arguments]
  (let [element-type (collection-element-type-node services invocation)
        source-arguments (vec (.getArguments ^CtInvocation invocation))
        arguments (mapv (fn [source argument]
                          (if (and (instance? CtLiteral source)
                                   (nil? (.getValue ^CtLiteral source)))
                            (sequence-node [(raw "(") element-type (raw ")") argument])
                            argument))
                        source-arguments arguments)]
    (invoke (csharp/generic-name
             (raw (str "global::Vibeformer.Runtime.JavaCompat." name))
             [element-type])
            arguments)))

(defn- invocation-node [context services ^CtInvocation element children]
  (let [key (executable-key context element)
        target-element (.getTarget element)
        target (when target-element (child-node children target-element))
        args (children-nodes children (.getArguments element))
        argc (count args)
        arg #(nth args %)
        call-member #(invoke (member target %) args)
        node
        (case key
          "executable:java.lang.CharSequence#isEmpty()" (csharp/binary "==" 40 (member target "Length") (raw "0"))
          "executable:java.lang.Character#isDigit(int)" (compat-call "IsDigit" args)
          "executable:java.lang.Character#isLetterOrDigit(int)" (compat-call "IsLetterOrDigit" args)
          "executable:java.lang.Character#isUnicodeIdentifierPart(int)" (compat-call "IsUnicodeIdentifierPart" args)
          "executable:java.lang.Character#isUnicodeIdentifierStart(int)" (compat-call "IsUnicodeIdentifierStart" args)
          "executable:java.lang.Character#toString(int)" (compat-call "CodePointToString" args)
          "executable:java.lang.Class#getSimpleName()" (member target "Name")
          "executable:java.lang.Class#getClassLoader()" (member target "Assembly")
          "executable:java.lang.Enum#name()" (compat-call "EnumName" [target])
          "executable:java.lang.Integer#parseInt(java.lang.String,int)" (compat-call "ParseInt" args)
          "executable:java.lang.invoke.MethodHandles#lookup()" (raw "new object()")
          "executable:java.lang.Object#getClass()" (call-member "GetType")
          "executable:java.lang.String#charAt(int)" (sequence-node [target (raw "[") (arg 0) (raw "]")])
          "executable:java.lang.String#codePointAt(int)" (compat-call "CodePointAt" (into [target] args))
          "executable:java.lang.String#codePoints()" (compat-call "CodePoints" [target])
          "executable:java.lang.String#formatted(java.lang.Object[])" (compat-call "Formatted" (into [target] args))
          "executable:java.lang.String#indexOf(int,int)" (compat-call "IndexOfCodePoint" (into [target] args))
          "executable:java.lang.String#isBlank()" (invoke (raw "global::System.String.IsNullOrWhiteSpace") [target])
          "executable:java.lang.String#isEmpty()" (csharp/binary "==" 40 (member target "Length") (raw "0"))
          "executable:java.lang.String#length()" (member target "Length")
          "executable:java.lang.String#repeat(int)" (compat-call "Repeat" (into [target] args))
          "executable:java.lang.String#startsWith(java.lang.String)" (compat-call "StartsWith" (into [target] args))
          "executable:java.lang.String#substring(int,int)" (compat-call "Substring" (into [target] args))
          "executable:java.lang.String#toCharArray()" (invoke (member target "ToCharArray") [])
          "executable:java.lang.String#toLowerCase(java.util.Locale)" (invoke (member target "ToLowerInvariant") [])
          "executable:java.lang.StringBuilder#append(char)" (call-member "Append")
          "executable:java.lang.StringBuilder#append(java.lang.CharSequence,int,int)" (compat-call "AppendRange" (into [target] args))
          "executable:java.lang.StringBuilder#append(java.lang.String)" (call-member "Append")
          "executable:java.lang.StringBuilder#reverse()" (compat-call "Reverse" [target])
          "executable:java.lang.AbstractStringBuilder#setLength(int)" (csharp/binary "=" 10 (member target "Length") (arg 0))
          "executable:java.lang.StringBuilder#setLength(int)" (csharp/binary "=" 10 (member target "Length") (arg 0))
          "executable:java.lang.StringBuilder#toString()" (call-member "ToString")
          "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)" (compat-call "ArrayCopy" args)
          "executable:java.lang.System#getenv()" (compat-call "GetEnvironment" [])
          "executable:java.lang.System#getProperties()" (compat-call "GetProperties" [])
          "executable:java.lang.System#getProperty(java.lang.String)" (compat-call "GetProperty" args)
          "executable:java.lang.System#setProperty(java.lang.String,java.lang.String)" (compat-call "SetProperty" args)
          "executable:java.lang.System#identityHashCode(java.lang.Object)" (compat-call "IdentityHashCode" args)
          "executable:java.lang.System#nanoTime()" (compat-call "NanoTime" [])
          "executable:java.lang.System#console()" (raw "(global::System.Console.IsInputRedirected ? null : new object())")
          "executable:java.nio.charset.Charset#forName(java.lang.String)" (invoke (raw "global::System.Text.Encoding.GetEncoding") args)
          "executable:java.nio.charset.Charset#newDecoder()" (invoke (raw "new global::Pkl.Core.Runtime.JavaCharsetDecoder") [target])
          "executable:java.nio.charset.Charset#newEncoder()" (invoke (raw "new global::Pkl.Core.Runtime.JavaCharsetEncoder") [target])
          "executable:java.lang.Throwable#getMessage()" (member target "Message")
          "executable:java.text.Format#format(java.lang.Object)" (compat-call "Format" (into [target] args))
          "executable:java.util.ArrayList#add(java.lang.Object)" (compat-call "Add" (into [target] args))
          "executable:java.util.ArrayList#addAll(java.util.Collection)" (compat-call "AddAll" (into [target] args))
          "executable:java.util.ArrayList#clear()" (call-member "Clear")
          "executable:java.util.ArrayList#get(int)" (sequence-node [target (raw "[") (arg 0) (raw "]")])
          "executable:java.util.ArrayList#isEmpty()" (csharp/binary "==" 40 (member target "Count") (raw "0"))
          "executable:java.util.ArrayList#size()" (member target "Count")
          "executable:java.util.Arrays#asList(java.lang.Object[])" (generic-compat-call services element "AsList" args)
          "executable:java.util.Collection#stream()" target
          "executable:java.util.Collections#emptyList()" (generic-compat-call services element "ListOf" [])
          "executable:java.util.Collections#singletonList(java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.Collections#unmodifiableList(java.util.List)" (compat-call "UnmodifiableList" args)
          "executable:java.util.Deque#getFirst()" (compat-call "DequeGetFirst" [target])
          "executable:java.util.Deque#peek()" (compat-call "DequePeek" [target])
          "executable:java.util.Deque#pop()" (compat-call "DequePop" [target])
          "executable:java.util.Deque#push(java.lang.Object)" (compat-call "DequePush" (into [target] args))
          "executable:java.util.List#add(java.lang.Object)" (compat-call "Add" (into [target] args))
          "executable:java.util.List#get(int)" (compat-call "ListGet" (into [target] args))
          "executable:java.util.List#isEmpty()" (compat-call "ListIsEmpty" [target])
          "executable:java.util.List#of()" (generic-compat-call services element "ListOf" [])
          "executable:java.util.List#of(java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.List#of(java.lang.Object,java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.List#of(java.lang.Object,java.lang.Object,java.lang.Object)" (generic-compat-call services element "ListOf" args)
          "executable:java.util.List#size()" (compat-call "ListCount" [target])
          "executable:java.util.List#subList(int,int)" (compat-call "SubList" (into [target] args))
          "executable:java.util.Locale#getDefault()" (raw "global::System.Globalization.CultureInfo.CurrentCulture")
          "executable:java.util.Objects#deepEquals(java.lang.Object,java.lang.Object)" (compat-call "DeepEquals" args)
          "executable:java.util.Objects#equals(java.lang.Object,java.lang.Object)" (compat-call "Equals" args)
          "executable:java.util.Objects#hash(java.lang.Object[])" (compat-call "Hash" args)
          "executable:java.util.Objects#requireNonNull(java.lang.Object)" (compat-call "RequireNonNull" args)
          "executable:java.util.Objects#requireNonNull(java.lang.Object,java.lang.String)" (compat-call "RequireNonNull" args)
          "executable:java.util.ResourceBundle#getBundle(java.lang.String,java.util.Locale)" (compat-call "GetResourceBundle" args)
          "executable:java.util.ResourceBundle#getString(java.lang.String)" (compat-call "GetResourceString" (into [target] args))
          "executable:java.util.ServiceLoader#load(java.lang.Class)" (generic-compat-call services element "LoadServices" args)
          "executable:java.util.ServiceLoader#load(java.lang.Class,java.lang.ClassLoader)" (generic-compat-call services element "LoadServices" args)
          "executable:java.util.ServiceLoader#spliterator()" target
          "executable:java.util.function.Supplier#get()" (invoke target [])
          "executable:java.util.stream.Collectors#joining(java.lang.CharSequence)" (compat-call "Joining" args)
          "executable:java.util.stream.IntStream#allMatch(java.util.function.IntPredicate)" (compat-call "All" (into [target] args))
          "executable:java.util.stream.IntStream#skip(long)" (compat-call "Skip" (into [target] args))
          "executable:java.util.stream.Stream#collect(java.util.stream.Collector)" (compat-call "Collect" (into [target] args))
          "executable:java.util.stream.Stream#map(java.util.function.Function)" (compat-call "Map" (into [target] args))
          "executable:java.util.stream.StreamSupport#stream(java.util.Spliterator,boolean)" (arg 0)
          ;; Project-local calls are mapped by exact declaration identity.  A
          ;; constructor invocation is handled separately below.
          (let [resolved (occurrence context (.getExecutable element))]
            (cond
              (= :constructor (:kind resolved))
              ;; Explicit this/super constructor calls are emitted on the C#
              ;; constructor signature by constructor-initializer.
              (raw "")
              (= :record-component-accessor (:resolution resolved))
              (member target ((:pascal services) (.getSimpleName (.getExecutable element))))
              (= :project (:origin resolved)) (normal-invocation element children)
              (some #(str/starts-with? key (str "executable:" %))
                    ["org.pkl.parser."
                     "java."
                     "javax."
                     "com.oracle.truffle."
                     "org.graalvm.collections."
                     "org.graalvm.polyglot."
                     "org.organicdesign.fp."
                     "org.msgpack."])
              (normal-invocation element children)
              :else
              (throw (ex-info "Unsupported resolved executable mapping"
                              {:kind :unsupported-resolved-executable
                               :key key :origin (:origin resolved)
                               :source (spoon/source-location element)})))))]
    (if (= :constructor (:kind (occurrence context (.getExecutable element))))
      node
      (finish-expression services children element node))))

(defn- constructor-node [services ^CtConstructorCall element children]
  (let [type ((:type-node services) (.getType element))
        args (children-nodes children (.getArguments element))
        anonymous-body (some (fn [child]
                               (when (instance? CtClass (:source-element child))
                                 (:node child)))
                             children)]
    (finish-expression services children element
                       (sequence-node [(raw "new ") type (raw "(")
                                       (sequence-node args ", ") (raw ")")
                                       (when anonymous-body
                                         (sequence-node [(raw " ") anonymous-body]))]))))

(defn- block-node [children ^CtBlock element]
  (sequence-node [(raw "{")
                  (when (seq (.getStatements element)) (raw "\n"))
                  (sequence-node (children-nodes children (.getStatements element)) "\n")
                  (when (seq (.getStatements element)) (raw "\n"))
                  (raw "}")]))

(defn- switch-expression-yield? [^CtYieldStatement statement]
  (loop [current (.getParent statement)]
    (cond
      (instance? CtSwitchExpression current) true
      (instance? CtSwitch current) false
      (nil? current) false
      :else (recur (.getParent ^CtElement current)))))

(defn- terminating-statement? [statement]
  (cond
    (or (instance? CtReturn statement)
        (instance? CtThrow statement)
        (instance? CtBreak statement)) true
    (instance? CtYieldStatement statement)
    (switch-expression-yield? statement)
    (instance? CtBlock statement)
    (boolean (some-> ^CtBlock statement .getStatements last terminating-statement?))
    (instance? CtIf statement)
    (let [else-statement (.getElseStatement ^CtIf statement)]
      (and else-statement
           (terminating-statement? (.getThenStatement ^CtIf statement))
           (terminating-statement? else-statement)))
    :else false))

(defn- case-labels [children ^CtCase element]
  (let [expressions (.getCaseExpressions element)
        parent (.getParent element)
        selector (cond
                   (instance? CtSwitch parent) (.getSelector ^CtSwitch parent)
                   (instance? CtSwitchExpression parent) (.getSelector ^CtSwitchExpression parent)
                   :else nil)]
    (if (seq expressions)
      (sequence-node
       (map-indexed
        (fn [index expression]
          (let [{:keys [line column]} (spoon/source-location expression)
                binding (str "__case_" (or line 0) "_" (or column 0) "_" index)
                primitive? (and selector
                                (primitive-expression? selector)
                                (primitive-expression? expression))]
            (if primitive?
              (sequence-node [(raw (str "case var " binding " when " binding " == "))
                              (child-node children expression) (raw ":")])
              (sequence-node [(raw (str "case var " binding
                                        " when global::System.Object.Equals(" binding ", "))
                              (child-node children expression) (raw "):")]))))
        expressions)
       "\n")
      (raw "default:"))))

(defn- structural-rules [services]
  (java/structural-rules
   [{:id :java.expression/array-read :class CtArrayRead
     :emit (fn [{:keys [^CtArrayRead element children]}]
             {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getTarget element)) (raw "[")
                                       (child-node children (.getIndexExpression element)) (raw "]")]))})}
    {:id :java.expression/array-write :class CtArrayWrite
     :emit (fn [{:keys [^CtArrayWrite element children]}]
             {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getTarget element)) (raw "[")
                                       (child-node children (.getIndexExpression element)) (raw "]")]))})}
    {:id :java.statement/assert :class CtAssert
     :emit (fn [{:keys [^CtAssert element children]}]
             (let [condition (child-node children (.getAssertExpression element))
                   message (.getExpression element)]
               {:node (sequence-node [(raw "if (!(") condition (raw ")) throw new global::System.Exception(")
                                      (if message (child-node children message) (raw (escape-string "Assertion failed")))
                                      (raw ");")])}))}
    {:id :java.expression/operator-assignment :class CtOperatorAssignment
     :emit (fn [{:keys [^CtOperatorAssignment element children]}]
             (let [operator (case (str (.getKind element))
                              "PLUS" "+=" "MINUS" "-=" "MUL" "*=" "DIV" "/=" "MOD" "%="
                              "BITAND" "&=" "BITOR" "|=" "BITXOR" "^="
                              "SL" "<<=" "SR" ">>=" "USR" ">>>="
                              (throw (ex-info "Unsupported operator assignment" {:operator (.getKind element)})))]
               {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getAssigned element)) (raw (str " " operator " "))
                                       (child-node children (.getAssignment element))]))}))}
    {:id :java.expression/assignment :class CtAssignment
     :emit (fn [{:keys [^CtAssignment element children]}]
             {:node (finish-expression services children element
                       (sequence-node [(child-node children (.getAssigned element)) (raw " = ")
                                       (child-node children (.getAssignment element))]))})}
    {:id :java.expression/binary :class CtBinaryOperator
     :emit (fn [{:keys [^CtBinaryOperator element children]}]
             {:node (finish-expression services children element (binary-node services element children))})}
    {:id :java.statement/block :class CtBlock
     :emit (fn [{:keys [^CtBlock element children]}] {:node (block-node children element)})}
    {:id :java.statement/break :class CtBreak
     :emit (fn [_] {:node (raw "break;")})}
    {:id :java.statement/continue :class CtContinue
     :emit (fn [_] {:node (raw "continue;")})}
    {:id :java.statement/case :class CtCase
     :emit (fn [{:keys [^CtCase element children]}]
             (let [source-statements (vec (.getStatements element))
                   statements (children-nodes children source-statements)
                   last-semantic (last (remove #(instance? CtComment %) source-statements))]
               {:node (sequence-node [(case-labels children element) (raw "\n")
                                      (sequence-node statements "\n")
                                      (when (and last-semantic
                                                 (not (terminating-statement?
                                                       last-semantic)))
                                        (raw "\nbreak;"))])}))}
    {:id :java.statement/catch :class CtCatch
     :emit (fn [{:keys [^CtCatch element children]}]
             (let [parameter (.getParameter element)
                   multi-types (vec (.getMultiTypes ^CtCatchVariable parameter))
                   used? (some (fn [^CtVariableRead read]
                                 (identical? parameter (some-> read .getVariable .getDeclaration)))
                               (.getElements (.getBody element) (TypeFilter. CtVariableRead)))
                   parameter-name (identifier services (.getSimpleName parameter))
                   multi? (> (count multi-types) 1)]
               {:node (sequence-node [(raw "catch (")
                                      (if multi?
                                        (sequence-node [(raw "global::System.Exception ")
                                                        (raw parameter-name)])
                                        (if used?
                                          (child-node children parameter)
                                          ((:type-node services)
                                           (or (first multi-types) (.getType parameter)))))
                                      (raw ")")
                                      (when multi?
                                        (sequence-node
                                         [(raw (str " when (" parameter-name " is "))
                                          (sequence-node
                                           (mapv #((:type-node services) %) multi-types)
                                           " or ")
                                          (raw ")")]))
                                      (raw " ") (child-node children (.getBody element))])}))}
    {:id :java.declaration/catch-variable :class CtCatchVariable
     :emit (fn [{:keys [^CtCatchVariable element children]}]
             (let [type (or (first (.getMultiTypes element)) (.getType element))]
               {:node (sequence-node [((:type-node services) type) (raw " ")
                                      (raw (identifier services (.getSimpleName element)))])}))}
    {:id :java.declaration/anonymous-class :class CtClass
     :emit (fn [{:keys [^CtClass element children]}]
             {:node (sequence-node
                     [(raw "{")
                      (when (seq (.getTypeMembers element)) (raw "\n"))
                      (sequence-node (children-nodes children (.getTypeMembers element)) "\n")
                      (when (seq (.getTypeMembers element)) (raw "\n"))
                      (raw "}")])})}
    {:id :java.declaration/anonymous-field :class CtField
     :emit (fn [{:keys [^CtField element children]}]
             {:node (sequence-node
                     [((:type-node services) (.getType element)) (raw " ")
                      (raw (identifier services (.getSimpleName element)))
                      (when-let [value (.getDefaultExpression element)]
                        (sequence-node [(raw " = ") (child-node children value)]))
                      (raw ";")])})}
    {:id :java.declaration/anonymous-method :class CtMethod
     :emit (fn [{:keys [^CtMethod element children]}]
             {:node (sequence-node
                     [((:type-node services) (.getType element)) (raw " ")
                      (raw ((:method-name services) element)) (raw "(")
                      (sequence-node (children-nodes children (.getParameters element)) ", ")
                      (raw ") ")
                      (when-let [body (.getBody element)] (child-node children body))])})}
    {:id :java.declaration/anonymous-constructor :class CtConstructor
     :emit (fn [{:keys [^CtConstructor element children]}]
             {:node (or (when-let [body (.getBody element)] (child-node children body))
                        (raw "{}"))})}
    {:id :java.trivia/comment :class CtComment :emit (fn [_] {:node (raw "")})}
    {:id :java.expression/conditional :class CtConditional
     :emit (fn [{:keys [^CtConditional element children]}]
             {:node (finish-expression services children element
                       (sequence-node [(raw "(") (child-node children (.getCondition element))
                                       (raw " ? ") (child-node children (.getThenExpression element))
                                       (raw " : ") (child-node children (.getElseExpression element)) (raw ")")]))})}
    {:id :java.expression/constructor-call :class CtConstructorCall
     :emit (fn [{:keys [^CtConstructorCall element children]}]
             {:node (constructor-node services element children)})}
    {:id :java.statement/do :class CtDo
     :emit (fn [{:keys [^CtDo element children]}]
             {:node (sequence-node [(raw "do ") (child-node children (.getBody element))
                                    (raw " while (") (child-node children (.getLoopingExpression element))
                                    (raw ");")])})}
    {:id :java.expression/method-reference :class CtExecutableReferenceExpression
     :emit (fn [{:keys [context ^CtExecutableReferenceExpression element children]}]
             (let [target-element (.getTarget element)
                   target (child-node children target-element)
                   executable (child-node children (.getExecutable element))
                   method-name (:text (csharp/render executable))
                   resolved (occurrence context (.getExecutable element))
                   constructor? (= :constructor (:kind resolved))
                   target-type (when (instance? CtTypeAccess target-element)
                                 (.getAccessedType ^CtTypeAccess target-element))
                   node (cond
                          (and constructor? target-type (.isArray target-type))
                          (sequence-node [(raw "value => new ")
                                          ((:type-node services) (.getComponentType target-type))
                                          (raw "[value]")])

                          (and constructor? (instance? CtTypeAccess target-element))
                          (sequence-node [(raw "value => new ") target (raw "(value)")])

                          (instance? CtTypeAccess target-element)
                          (sequence-node [(raw "value => value.") (raw method-name) (raw "()")])

                          :else (member target method-name))]
               {:node (finish-expression services children element node)}))}
    {:id :java.expression/field-read :class CtFieldRead
     :emit (fn [{:keys [^CtFieldRead element children]}]
             (let [target-element (.getTarget element)
                   target (when target-element (child-node children target-element))
                   field (:text (csharp/render (child-node children (.getVariable element))))]
               {:node (finish-expression
                       services children element
                       (if (class-literal? element)
                         (sequence-node [(raw "typeof(") target (raw ")")])
                         (member target field)))}))}
    {:id :java.expression/field-write :class CtFieldWrite
     :emit (fn [{:keys [^CtFieldWrite element children]}]
             (let [target-element (.getTarget element)
                   target (when target-element (child-node children target-element))
                   field (:text (csharp/render (child-node children (.getVariable element))))]
               {:node (finish-expression services children element (member target field))}))}
    {:id :java.statement/foreach :class CtForEach
     :emit (fn [{:keys [^CtForEach element children]}]
             {:node (sequence-node [(raw "foreach (") (child-node children (.getVariable element))
                                    (raw " in ") (child-node children (.getExpression element)) (raw ") ")
                                    (child-node children (.getBody element))])})}
    {:id :java.statement/for :class CtFor
     :emit (fn [{:keys [^CtFor element children]}]
             {:node (sequence-node [(raw "for (")
                                    (sequence-node (children-nodes children (.getForInit element)) ", ")
                                    (raw "; ")
                                    (when-let [expression (.getExpression element)] (child-node children expression))
                                    (raw "; ")
                                    (sequence-node (children-nodes children (.getForUpdate element)) ", ")
                                    (raw ") ") (child-node children (.getBody element))])})}
    {:id :java.statement/if :class CtIf
     :emit (fn [{:keys [^CtIf element children]}]
             {:node (sequence-node [(raw "if (") (child-node children (.getCondition element))
                                    (raw ") ") (child-node children (.getThenStatement element))
                                    (when-let [else (.getElseStatement element)]
                                      (sequence-node [(raw " else ") (child-node children else)]))])})}
    {:id :java.expression/invocation :class CtInvocation
     :emit (fn [{:keys [context ^CtInvocation element children]}]
             {:node (invocation-node context services element children)})}
    {:id :java.expression/lambda :class CtLambda
     :emit (fn [{:keys [^CtLambda element children]}]
             (let [parameters (mapv #(raw (identifier services (.getSimpleName ^CtParameter %)))
                                    (.getParameters element))
                   body (if-let [expression (.getExpression element)]
                          (child-node children expression)
                          (child-node children (.getBody element)))]
               {:node (finish-expression services children element
                       (sequence-node [(raw "(") (sequence-node parameters ", ") (raw ") => ") body]))}))}
    {:id :java.expression/literal :class CtLiteral
     :emit (fn [{:keys [^CtLiteral element children]}]
             {:node (finish-expression services children element (raw (literal-text (.getValue element))))})}
    {:id :java.declaration/local-variable :class CtLocalVariable
     :emit (fn [{:keys [^CtLocalVariable element children]}]
             (let [default (.getDefaultExpression element)
                   type-reference (.getType element)
                   parent (when (.isParentInitialized element) (.getParent element))
                   later-for-initializer?
                   (and (instance? CtFor parent)
                        (not (identical? element (first (.getForInit ^CtFor parent)))))
                   type (when-not later-for-initializer?
                          (if (.isInferred element)
                            (raw "var")
                            (sequence-node
                             [(child-node children type-reference)
                              (when (and (not (.isPrimitive type-reference))
                                         (not (nullable-type? type-reference)))
                                (raw "?"))])))
                   declaration (sequence-node [type (when type (raw " "))
                                               (raw (local-name services element))
                                               (when default (sequence-node [(raw " = ") (child-node children default)]))])]
               {:node (if (= "statement" (role element))
                        (sequence-node [declaration (raw ";")]) declaration)}))}
    {:id :java.expression/new-array :class CtNewArray
     :emit (fn [{:keys [^CtNewArray element children]}]
             (let [type-reference (.getType element)
                   component (if (.isArray type-reference) (.getComponentType type-reference) type-reference)
                   dimensions (.getDimensionExpressions element)
                   elements (.getElements element)
                   node (if (seq elements)
                          (sequence-node [(raw "new[] { ") (sequence-node (children-nodes children elements) ", ") (raw " }")])
                          (sequence-node [(raw "new ") ((:type-node services) component) (raw "[")
                                          (sequence-node (children-nodes children dimensions) ", ") (raw "]")]))]
               {:node (finish-expression services children element node)}))}
    {:id :java.statement/return :class CtReturn
     :emit (fn [{:keys [^CtReturn element children]}]
             {:node (sequence-node [(raw "return")
                                    (when-let [expression (.getReturnedExpression element)]
                                      (sequence-node [(raw " ") (child-node children expression)]))
                                    (raw ";")])})}
    {:id :java.expression/super :class CtSuperAccess
     :emit (fn [{:keys [^CtSuperAccess element children]}]
             {:node (finish-expression services children element (raw "base"))})}
    {:id :java.expression/switch-expression :class CtSwitchExpression
     :emit (fn [{:keys [^CtSwitchExpression element children]}]
             (let [selector (child-node children (.getSelector element))
                   cases (children-nodes children (.getCases element))
                   enclosing-method (loop [current (.getParent element)]
                                      (cond
                                        (instance? CtMethod current) current
                                        (nil? current) nil
                                        :else (recur (.getParent ^CtElement current))))
                   nullable-result? (and enclosing-method
                                         (or (nullable-annotation? enclosing-method)
                                             (nullable-type? (.getType ^CtMethod enclosing-method)))
                                         (not (.isPrimitive (.getType element))))
                   result-type (sequence-node [(child-node children (.getType element))
                                               (when nullable-result? (raw "?"))])
                   default? (some #(empty? (.getCaseExpressions ^CtCase %)) (.getCases element))]
               {:node (finish-expression services children element
                       (sequence-node [(raw "((global::System.Func<") result-type
                                       (raw ">)(() => { switch (") selector (raw ") {\n")
                                       (sequence-node cases "\n")
                                       (raw (if default?
                                              "\n} }))()"
                                              "\n} throw new global::System.InvalidOperationException(); }))()"))]))}))}
    {:id :java.statement/switch :class CtSwitch
     :emit (fn [{:keys [^CtSwitch element children]}]
             {:node (sequence-node [(raw "switch (") (child-node children (.getSelector element))
                                    (raw ") {\n") (sequence-node (children-nodes children (.getCases element)) "\n")
                                    (raw "\n}")])})}
    {:id :java.statement/synchronized :class CtSynchronized
     :emit (fn [{:keys [^CtSynchronized element children]}]
             {:node (sequence-node [(raw "lock (")
                                    (child-node children (.getExpression element))
                                    (raw ") ")
                                    (child-node children (.getBlock element))])})}
    {:id :java.expression/this :class CtThisAccess
     :emit (fn [{:keys [^CtThisAccess element children]}]
             {:node (finish-expression services children element (raw "this"))})}
    {:id :java.statement/throw :class CtThrow
     :emit (fn [{:keys [^CtThrow element children]}]
             {:node (sequence-node [(raw "throw ") (child-node children (.getThrownExpression element)) (raw ";")])})}
    {:id :java.statement/try :class CtTry
     :emit (fn [{:keys [^CtTry element children]}]
             {:node (sequence-node [(raw "try ") (child-node children (.getBody element))
                                    (when (seq (.getCatchers element)) (raw " "))
                                    (sequence-node (children-nodes children (.getCatchers element)) " ")
                                    (when-let [finalizer (.getFinalizer element)]
                                      (sequence-node [(raw " finally ") (child-node children finalizer)]))])})}
    {:id :java.expression/type-access :class CtTypeAccess
     :emit (fn [{:keys [^CtTypeAccess element children]}]
             {:node (finish-expression services children element
                       (child-node children (.getAccessedType element)))})}
    {:id :java.expression/type-pattern :class CtTypePattern
     :emit (fn [{:keys [^CtTypePattern element children]}]
             (let [variable (.getVariable element)]
               {:node (sequence-node [((:type-node services) (.getType variable)) (raw " ")
                                      (raw (local-name services variable))])}))}
    {:id :java.expression/unary :class CtUnaryOperator
     :emit (fn [{:keys [^CtUnaryOperator element children]}]
             {:node (finish-expression services children element
                       (unary-node element (child-node children (.getOperand element))))})}
    {:id :java.expression/variable-read :class CtVariableRead
     :emit (fn [{:keys [^CtVariableRead element children]}]
             {:node (finish-expression services children element
                       (child-node children (.getVariable element)))})}
    {:id :java.expression/variable-write :class CtVariableWrite
     :emit (fn [{:keys [^CtVariableWrite element children]}]
             {:node (finish-expression services children element
                       (child-node children (.getVariable element)))})}
    {:id :java.statement/while :class CtWhile
     :emit (fn [{:keys [^CtWhile element children]}]
             {:node (sequence-node [(raw "while (") (child-node children (.getLoopingExpression element))
                                    (raw ") ") (child-node children (.getBody element))])})}
    {:id :java.statement/yield :class CtYieldStatement
     :emit (fn [{:keys [^CtYieldStatement element children]}]
             (let [switch-expression? (switch-expression-yield? element)]
               {:node (sequence-node [(when switch-expression? (raw "return "))
                                      (child-node children (.getExpression element)) (raw ";")])}))}
    {:id :java.declaration/lambda-parameter :class CtParameter
     :emit (fn [{:keys [^CtParameter element]}]
             {:node (raw (identifier services (.getSimpleName element)))})}
    {:id :java.reference/catch-variable :class CtCatchVariableReference
     :emit (fn [{:keys [^CtCatchVariableReference element]}]
             {:node (raw (identifier services (.getSimpleName element)))})}
    {:id :java.reference/local-variable :class CtLocalVariableReference
     :emit (fn [{:keys [^CtLocalVariableReference element]}]
             (let [declaration (.getDeclaration element)]
               {:node (raw (if declaration
                             (local-name services declaration)
                             (identifier services (.getSimpleName element))))}))}
    {:id :java.reference/parameter :class CtParameterReference
     :emit (fn [{:keys [^CtParameterReference element]}]
             {:node (raw (identifier services (.getSimpleName element)))})}
    {:id :java.reference/package :class CtPackageReference
     :emit (fn [_] {:node (raw "")})}]))

(defn- project-executable-name [services occurrence ^CtExecutableReference reference]
  (if (= :record-component-accessor (:resolution occurrence))
    (if-let [component (when (instance? CtRecordComponent (:declaration occurrence))
                         (:declaration occurrence))]
      ((:record-component-name services) (enclosing-type component) component)
      (if-let [owner (some-> reference .getDeclaringType .getTypeDeclaration)]
        ((:record-component-name services) owner reference)
        ((:pascal services) (.getSimpleName reference))))
    (if-let [declaration (:declaration occurrence)]
    (if (instance? CtMethod declaration)
      ((:method-name services) declaration)
      (identifier services (.getSimpleName reference)))
    (identifier services (.getSimpleName reference)))))

(defn- executable-name [services occurrence ^CtExecutableReference reference]
  (if (= :project (:origin occurrence))
    (project-executable-name services occurrence reference)
    ;; Whole-invocation JDK rules own semantic rewrites.  This token remains
    ;; mapped so every resolved reference is independently covered.
    ((:pascal services) (.getSimpleName reference))))

(defn- field-name [services occurrence ^CtFieldReference reference]
  (cond
    (= "field:<array>#length" (:key occurrence)) "Length"
    (= "field:java.util.Locale#ROOT" (:key occurrence)) "InvariantCulture"
    (or (= :record-component (:resolution occurrence))
        (instance? CtRecordComponent (:declaration occurrence)))
    (if-let [component (when (instance? CtRecordComponent (:declaration occurrence))
                         (:declaration occurrence))]
      ((:record-component-name services) (enclosing-type component) component)
      ((:pascal services) (.getSimpleName reference)))
    :else (identifier services (.getSimpleName reference))))

(defn- registry-entry [id emit]
  {:id id :emit emit})

(defn- semantic-mappings [resolved-model services]
  (reduce
   (fn [registries occurrence]
     (let [key (:key occurrence)
           category (case (:kind occurrence)
                      :type :types :executable :executables :constructor :constructors
                      :field :fields :annotation :annotations nil)]
       (if (or (nil? category) (get-in registries [category key]))
         registries
         (assoc-in
          registries [category key]
          (case category
            :types (registry-entry
                    (keyword "resolved.type" (name (:origin occurrence)))
                    (fn [{:keys [element]}] {:node ((:type-node services) element)}))
            :executables (registry-entry
                          (keyword "resolved.executable" (name (:origin occurrence)))
                          (fn [{:keys [element occurrence]}]
                            {:node (raw (executable-name services occurrence element))}))
            :constructors (registry-entry
                           (keyword "resolved.constructor" (name (:origin occurrence)))
                           (fn [_] {:node (raw "<init>")}))
            :fields (registry-entry
                     (keyword "resolved.field" (name (:origin occurrence)))
                     (fn [{:keys [element occurrence]}]
                       {:node (raw (field-name services occurrence element))}))
            :annotations (registry-entry
                          (keyword "resolved.annotation" (name (:origin occurrence)))
                          (fn [_] {:node (raw "")})))))))
   {:types {} :executables {} :constructors {} :fields {} :annotations {}}
   (:occurrences resolved-model)))

(defn context
  "Creates the accepted executable translator for one live resolved model.
  Services provide destination declaration naming/type mappings without
  introducing a second frontend representation."
  [resolved-model services]
  (java/context resolved-model
                {:mode :accepted
                 :rules (structural-rules services)
                 :mappings (semantic-mappings resolved-model services)}))

(defn translate
  "Translates and gates one live executable body or initializer."
  [translation-context element]
  (java/coverage-gate! (java/translate-element translation-context element)))

(defn explicit-constructor-invocation
  "Returns the first live this/super constructor invocation when Java requires
  it to precede all body statements."
  [translation-context ^CtBlock body]
  (when-let [first-statement (first (.getStatements body))]
    (when (and (instance? CtInvocation first-statement)
               (not (.isImplicit ^CtElement first-statement)))
      (let [resolved (occurrence translation-context (.getExecutable ^CtInvocation first-statement))]
        (when (= :constructor (:kind resolved)) first-statement)))))

(defn constructor-initializer
  "Emits a C# constructor initializer directly from a resolved Java this/super
  invocation.  Its body traversal still visits the invocation, but the
  invocation rule deliberately emits no body statement."
  [translation-context ^CtInvocation invocation]
  (let [occurrence (occurrence translation-context (.getExecutable invocation))
        called-owner (some-> invocation .getExecutable .getDeclaringType .getQualifiedName)
        ^CtConstructor owner (loop [current (.getParent invocation)]
                               (cond
                                 (nil? current) nil
                                 (instance? CtConstructor current) current
                                 :else (recur (.getParent ^CtElement current))))
        label (if (= called-owner (some-> owner .getDeclaringType .getQualifiedName))
                "this" "base")
        arguments (mapv #(translate translation-context %) (.getArguments invocation))
        node (sequence-node [(raw (str " : " label "("))
                             (sequence-node (mapv :node arguments) ", ") (raw ")")])]
    (-> node
        (csharp/with-source {:identity (spoon/frontend-identity invocation)
                             :location (spoon/source-location invocation)
                             :rule :java.constructor/initializer})
        (csharp/with-source {:identity (spoon/frontend-identity (.getExecutable invocation))
                             :location (spoon/source-location (.getExecutable invocation))
                             :rule :resolved.constructor/project
                             :mapping {:registry :constructors
                                       :identity :resolved.constructor/project
                                       :resolved-key (:key occurrence)
                                       :origin (:origin occurrence)
                                       :resolution (:resolution occurrence)}}))))
