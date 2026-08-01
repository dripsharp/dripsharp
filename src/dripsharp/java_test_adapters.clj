(ns dripsharp.java-test-adapters
  "Resolved Java-test assertion, matcher, mocking, and facility adaptation.

  Shared framework calls lower to the generated xUnit-side support library.
  Database, HTTP-server, filesystem, and other target facilities are routed to
  an explicit target strategy. Unknown calls owned by a known framework fail
  closed with their resolved executable identity."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-translate :as java]
            [dripsharp.spoon :as spoon])
  (:import [java.util IdentityHashMap]
           [spoon.reflect.code CtConstructorCall CtExecutableReferenceExpression
            CtFieldRead CtInvocation CtLambda CtTypeAccess]
           [spoon.reflect.declaration CtAnnotation CtElement CtField CtParameter
            CtType]
           [spoon.reflect.reference CtTypeReference CtWildcardReference]))

(def schema-version 1)

(def support-contract
  "Pinned generated-project requirements for the shared Java test support."
  {:source "JavaTestSupport.cs"
   :namespace "DripSharp.Testing"
   :packages [{:id "xunit" :version "2.9.3"}
              {:id "Castle.Core" :version "5.1.1"}]
   :is-test-support true
   :is-packable false})

(def framework-contracts
  "Operations owned by the reusable Java-to-xUnit adapter. Matching is by the
  resolved declaring type plus operation, never by source spelling."
  {:junit4
   {:targets #{:rawhttp}
    :source-languages #{:java}
    :java-adaptation :shared-resolved-xunit-adapter
    :used-operations
    #{"assertEquals" "assertFalse" "assertNotNull" "assertNull"
      "assertTrue" "fail"}
    :owners #{"org.junit.Assert"}
    :operations
    #{"assertArrayEquals" "assertEquals" "assertFalse" "assertNotEquals"
      "assertNotNull" "assertNotSame" "assertNull" "assertSame"
      "assertThat" "assertTrue" "fail"}}
   :jupiter
   {:targets #{:pkl :pdfcarton :rawhttp :sqltrellis}
    :source-languages #{:java :kotlin}
    :java-adaptation :shared-resolved-xunit-adapter
    :kotlin-policy :evidence-only-no-kotlin-frontend
    :used-operations
    #{"assertArrayEquals" "assertDoesNotThrow" "assertEquals" "assertFalse"
      "assertInstanceOf" "assertNotEquals" "assertNotNull" "assertNotSame"
      "assertNull" "assertSame" "assertThrows" "assertThrowsExactly"
      "assertTrue" "fail"}
    :owners #{"org.junit.jupiter.api.Assertions"}
    :operations
    #{"assertAll" "assertArrayEquals" "assertDoesNotThrow" "assertEquals"
      "assertFalse" "assertInstanceOf" "assertIterableEquals"
      "assertNotEquals" "assertNotNull" "assertNotSame"
      "assertNull" "assertSame" "assertThrows" "assertThrowsExactly"
      "assertTrue" "fail"}}
   :assertj
   {:targets #{:pkl :sqltrellis}
    :source-languages #{:java :kotlin}
    :java-adaptation :shared-resolved-xunit-adapter
    :kotlin-policy :evidence-only-no-kotlin-frontend
    :used-operations
    #{"allSatisfy" "as" "asString" "assertThat"
      "assertThatExceptionOfType" "assertThatThrownBy" "catchThrowable"
      "contains" "containsAllEntriesOf" "containsEntry" "containsExactly"
      "containsExactlyInAnyOrder" "containsOnly" "describedAs"
      "doesNotContain" "entry" "extracting" "hasCauseInstanceOf"
      "hasFieldOrPropertyWithValue" "hasMessage" "hasMessageContaining"
      "hasMessageStartingWith" "hasRootCauseInstanceOf" "hasSize" "isEmpty"
      "isEqualTo" "isFalse" "isInstanceOf" "isNotEqualTo" "isNotNull"
      "isNull" "isSameAs" "isThrownBy" "isTrue" "rootCause" "startsWith"}
    :owner-prefixes ["org.assertj.core.api."]
    :factory-operations
    #{"assertThat" "assertThatCode" "assertThatExceptionOfType"
      "assertThatList" "assertThatThrownBy" "catchThrowable" "entry" "fail"}
    :fluent-operations
    #{"allSatisfy" "as" "asString" "contains" "containsAllEntriesOf" "containsEntry"
      "containsExactly" "containsExactlyInAnyOrder" "containsOnly"
      "describedAs" "doesNotContain" "endsWith" "extracting"
      "hasCauseInstanceOf" "hasFieldOrPropertyWithValue" "hasMessage"
      "hasMessageContaining" "hasMessageStartingWith"
      "hasRootCauseInstanceOf" "hasSize" "isEmpty" "isEqualTo" "isFalse"
      "isInstanceOf" "isNotEmpty" "isNotEqualTo" "isNotNull" "isNull"
      "isSameAs" "isThrownBy" "isTrue" "rootCause" "startsWith"
      "withFailMessage"}}
   :hamcrest
   {:targets #{:sqltrellis}
    :source-languages #{:java}
    :java-adaptation :shared-resolved-xunit-adapter
    :used-operations #{"assertThat" "instanceOf" "startsWith"}
    :owner-prefixes ["org.hamcrest."]
    :assertion-operations #{"assertThat"}
    :matcher-operations
    #{"allOf" "anyOf" "anything" "closeTo" "contains" "containsInAnyOrder"
      "containsString" "empty" "emptyIterable" "endsWith" "equalTo"
      "greaterThan" "greaterThanOrEqualTo" "hasItem" "hasItems"
      "instanceOf" "is" "isA" "lessThan" "lessThanOrEqualTo" "matches"
      "not" "notNullValue" "nullValue" "sameInstance" "startsWith"}}
   :mockito
   {:targets #{:pdfcarton :sqltrellis}
    :source-languages #{:java}
    :java-adaptation :shared-resolved-xunit-adapter
    :used-operations
    #{"given" "mock" "should" "spy" "then" "will" "willReturn"}
    :owner-prefixes ["org.mockito."]
    :static-operations
    #{"after" "any" "anyBoolean" "anyByte" "anyChar" "anyDouble"
      "anyFloat" "anyInt" "anyLong" "anyShort" "anyString" "argThat"
      "atLeast" "atLeastOnce" "atMost" "atMostOnce" "clearInvocations"
      "eq" "given" "isNull" "mock" "never" "notNull" "only" "reset"
      "same" "spy" "then" "timeout" "times" "verify"
      "verifyNoInteractions" "verifyNoMoreInteractions" "when" "will"}
    :stubbing-operations
    #{"given" "should" "thenReturn" "thenThrow" "willReturn" "willThrow"}}})

(def target-facility-contracts
  "Facilities whose semantics stay target-owned. Kotlin-only evidence is
  classified here but never authorizes Kotlin translation."
  [{:facility :h2
    :owner-prefixes ["org.h2."]
    :source-languages #{:java}
    :targets #{:sqltrellis}
    :reuse-boundary :target-strategy
    :semantics :database-test-fixture}
   {:facility :wiremock
    :owner-prefixes ["com.github.tomakehurst.wiremock." "org.wiremock."]
    :source-languages #{:kotlin}
    :targets #{:pkl}
    :reuse-boundary :target-strategy
    :kotlin-policy :evidence-only-no-kotlin-frontend
    :semantics :http-server-fixture}
   {:facility :jimfs
    :owner-prefixes ["com.google.common.jimfs." "com.google.jimfs."]
    :source-languages #{:kotlin}
    :targets #{:pkl}
    :reuse-boundary :target-strategy
    :kotlin-policy :evidence-only-no-kotlin-frontend
    :semantics :filesystem-fixture}
   {:facility :kotest
    :owner-prefixes ["io.kotest."]
    :source-languages #{:kotlin}
    :targets #{:rawhttp}
    :reuse-boundary :evidence-only-no-kotlin-frontend
    :kotlin-policy :evidence-only-no-kotlin-frontend
    :semantics :kotlin-test-framework}])

(defn support-source
  "Returns the authored C# support source copied into generated xUnit suites."
  []
  (slurp (io/resource "dripsharp/java_test_support.cs")))

(defn read-pinned-inventory
  ([] (read-pinned-inventory
       "validation/java-test-frameworks/assertion-mocking-inventory.edn"))
  ([path] (edn/read-string (slurp path))))

(defn- fail!
  [message data]
  (throw (ex-info message
                  (assoc data :kind :java-test-adaptation-failed))))

(declare supported-operation?)

(defn validate-pinned-inventory!
  "Checks the durable source-language and reuse-boundary classification."
  [inventory]
  (let [facilities (into {} (map (juxt :facility identity))
                         (:target-facilities inventory))
        expected (into {} (map (juxt :facility identity))
                       target-facility-contracts)
        frameworks (into {} (map (juxt :framework identity))
                         (:frameworks inventory))]
    (when-not (and (= schema-version (:schema-version inventory))
                   (vector? (:frameworks inventory))
                   (seq (:frameworks inventory))
                   (= (count frameworks) (count (:frameworks inventory)))
                   (= (set (keys framework-contracts))
                      (set (keys frameworks)))
                   (vector? (:target-facilities inventory))
                   (= (count facilities)
                      (count (:target-facilities inventory)))
                   (= (set (keys expected)) (set (keys facilities))))
      (fail! "Pinned Java test-facility inventory has an invalid schema"
             {:reason :invalid-java-test-facility-inventory
              :inventory inventory}))
    (doseq [[facility contract] expected]
      (let [entry (get facilities facility)]
        (let [keys (cond-> [:source-languages :targets :reuse-boundary
                            :semantics]
                     (:kotlin-policy contract) (conj :kotlin-policy))]
          (when-not (= (select-keys contract keys)
                       (select-keys entry keys))
            (fail! "Pinned Java test-facility classification drifted"
                   {:reason :java-test-facility-classification-drift
                    :facility facility
                    :expected contract :actual entry})))))
    (doseq [[framework contract] framework-contracts]
      (let [entry (get frameworks framework)
            keys (cond-> [:targets :source-languages :java-adaptation
                          :used-operations]
                   (:kotlin-policy contract) (conj :kotlin-policy))]
        (when-not (= (select-keys contract keys) (select-keys entry keys))
          (fail! "Pinned Java test-framework classification drifted"
                 {:reason :java-test-framework-classification-drift
                  :framework framework
                  :expected (select-keys contract keys)
                  :actual (select-keys entry keys)}))
        (when-let [unsupported
                   (some #(when-not (supported-operation? framework %) %)
                         (:used-operations contract))]
          (fail! "Used Java test-framework operation has no adapter"
                 {:reason :used-java-test-operation-without-adapter
                  :framework framework
                  :operation unsupported}))))
    inventory))

(defn junit-plan-options
  "JUnit planner options supplied by the Mockito adapter. The class identity is
  explicit so @ExtendWith cannot be accepted by textual name."
  []
  {:extension-adapters
   #{{:class "org.mockito.junit.jupiter.MockitoExtension"}}})

(declare source-data)

(def ^:private mockito-annotation-contracts
  {"annotation:org.mockito.Mock" :mock
   "annotation:org.mockito.Captor" :unsupported-captor
   "annotation:org.mockito.InjectMocks" :unsupported-inject-mocks
   "annotation:org.mockito.Spy" :unsupported-spy-field})

(defn- mockito-extension?
  [class-record]
  (some
   (fn [annotation]
     (and (= :extension (:role annotation))
          (contains? (set (vals (:values annotation)))
                     {:class "org.mockito.junit.jupiter.MockitoExtension"})))
   (:annotations class-record)))

(defn- mockito-field-fixtures
  [^IdentityHashMap index class-name class-record]
  (let [^CtType type (:type-element class-record)
        extension? (mockito-extension? class-record)]
    (->>
     (.getFields type)
     (mapcat
      (fn [^CtField field]
        (keep
         (fn [^CtAnnotation annotation]
           (let [occurrence (.get index annotation)
                 role (get mockito-annotation-contracts (:key occurrence))]
             (when role
               (when-not (= :mock role)
                 (fail! "Mockito field annotation has no xUnit fixture adapter"
                        (merge {:reason :unsupported-java-test-mock-annotation
                                :resolved-key (:key occurrence)
                                :class class-name
                                :field (.getSimpleName field)}
                               (source-data annotation))))
               (when-not extension?
                 (fail! "Mockito @Mock field has no lifecycle adapter"
                        (merge {:reason :mockito-mock-without-extension
                                :resolved-key (:key occurrence)
                                :class class-name
                                :field (.getSimpleName field)
                                :required-extension
                                "org.mockito.junit.jupiter.MockitoExtension"}
                               (source-data annotation))))
               {:field-id (str class-name "#" (.getSimpleName field))
                :field (.getSimpleName field)
                :type (some-> field .getType .getQualifiedName)
                :initializer :java-mockito/mock
                :field-element field
                :source (spoon/source-location field)})))
         (.getAnnotations field))))
     (sort-by :field-id)
     vec)))

(defn augment-plan
  "Adds deterministic per-case Mockito field initialization while rejecting
  every unsupported Mockito field annotation."
  [resolved-model plan]
  (let [index (java/resolved-occurrence-index resolved-model)
        fixtures
        (into
         (sorted-map)
         (keep
          (fn [[class-name class-record]]
            (let [fields (mockito-field-fixtures
                          index class-name class-record)]
              (when (seq fields)
                [class-name {:extension
                             "org.mockito.junit.jupiter.MockitoExtension"
                             :fields fields}]))))
         (:classes plan))]
    (-> plan
        (update :classes
                (fn [classes]
                  (reduce-kv
                   (fn [result class-name fixture]
                     (assoc-in result [class-name :mockito-fixture] fixture))
                   classes fixtures)))
        (update :cases
                (fn [cases]
                  (mapv #(cond-> %
                           (get fixtures (:class %))
                           (assoc :mockito-fixture
                                  (get fixtures (:class %))))
                        cases))))))

(defn- executable-identity
  [key]
  (when-let [[_ owner operation parameters]
             (and (string? key)
                  (re-matches #"^executable:(.+)#([^#(]+)\((.*)\)$" key))]
    {:owner owner
     :operation operation
     :parameters (if (str/blank? parameters)
                   []
                   (str/split parameters #","))}))

(defn- starts-with-any?
  [value prefixes]
  (some #(str/starts-with? value %) prefixes))

(defn- framework-family
  [owner]
  (some
   (fn [[family {:keys [owners owner-prefixes]}]]
     (when (or (contains? (or owners #{}) owner)
               (starts-with-any? owner owner-prefixes))
       family))
   framework-contracts))

(defn- target-facility
  [owner]
  (some #(when (starts-with-any? owner (:owner-prefixes %)) %)
        target-facility-contracts))

(defn- source-data
  [element]
  {:source (spoon/source-location element)
   :frontend (spoon/frontend-diagnostic element)})

(defn- unsupported!
  [event family identity]
  (fail! "Resolved Java test-framework call has no xUnit adapter"
         (merge {:reason :unsupported-java-test-call
                 :framework family
                 :resolved-key (get-in event [:occurrence :key])}
                identity
                (source-data (:element event)))))

(defn- raw [text] (csharp/raw text))

(defn- static-call
  [type-name method arguments]
  (csharp/invocation (csharp/member (raw type-name) method) arguments))

(defn- member-call
  [target method arguments]
  (csharp/invocation (csharp/member target method) arguments))

(defn- bounded-reference
  [^CtTypeReference reference]
  (if (instance? CtWildcardReference reference)
    (or (.getBoundingType ^CtWildcardReference reference) reference)
    reference))

(defn- functional-input-reference
  [expression]
  (cond
    (instance? CtLambda expression)
    (some-> ^CtLambda expression .getParameters first
            ^CtParameter .getType bounded-reference)

    (instance? CtExecutableReferenceExpression expression)
    (some-> ^CtExecutableReferenceExpression expression .getType
            .getActualTypeArguments first bounded-reference)

    :else nil))

(defn- typed-functional-argument
  [destination-context event operation argument expression]
  (if-let [input (functional-input-reference expression)]
    (let [delegate
          (case operation
            "allSatisfy"
            (csharp/generic-name
             (raw "global::System.Action")
             [(java-library/type-node destination-context input)])

            "extracting"
            (csharp/generic-name
             (raw "global::System.Func")
             [(java-library/type-node destination-context input) (raw "object")]))]
      (csharp/sequence-node
       [(raw "((") delegate (raw ")") argument (raw ")")]))
    (unsupported! event :assertj
                  (executable-identity (get-in event [:occurrence :key])))))

(defn- class-literal-reference
  [expression]
  (cond
    (instance? CtFieldRead expression)
    (some-> ^CtFieldRead expression .getVariable .getDeclaringType)

    (instance? CtTypeAccess expression)
    (.getAccessedType ^CtTypeAccess expression)

    :else nil))

(defn- generic-static-call
  [destination-context type-name method ^CtTypeReference type arguments]
  (let [target (csharp/generic-name
                (raw (str type-name "." method))
                [(java-library/type-node destination-context type)])]
    (csharp/invocation target arguments)))

(defn- nil-node [] (raw "null"))

(defn- message-parameter?
  [parameter]
  (contains? #{"java.lang.String" "java.util.function.Supplier"}
             parameter))

(defn- split-junit-message
  [family parameters arguments]
  (cond
    (and (= :junit4 family) (message-parameter? (first parameters)))
    {:message (first arguments)
     :parameters (subvec (vec parameters) 1)
     :arguments (subvec (vec arguments) 1)}

    (and (= :jupiter family) (message-parameter? (last parameters)))
    {:message (last arguments)
     :parameters (pop (vec parameters))
     :arguments (pop (vec arguments))}

    :else
    {:message (nil-node)
     :parameters (vec parameters)
     :arguments (vec arguments)}))

(defn- floating-parameter?
  [parameter]
  (contains? #{"double" "float" "java.lang.Double" "java.lang.Float"}
             parameter))

(defn- junit-adaptation
  [{:keys [destination-context ^CtInvocation element arguments] :as event}
   family identity]
  (let [{:keys [operation parameters]} identity
        supported (get-in framework-contracts [family :operations])]
    (when-not (contains? supported operation)
      (unsupported! event family identity))
    (if (and (= :junit4 family) (= "assertThat" operation))
      (apply static-call "global::DripSharp.Testing.JavaHamcrest" "AssertThat"
             [arguments])
      (let [{:keys [message parameters arguments]}
            (split-junit-message family parameters arguments)
            call #(static-call "global::DripSharp.Testing.JavaAssertions" %1 %2)]
        (case operation
          ("assertEquals" "assertArrayEquals" "assertIterableEquals")
          (let [delta? (and (= 3 (count arguments))
                            (floating-parameter? (last parameters)))]
            (call "Equal"
                  (if delta?
                    [(nth arguments 0) (nth arguments 1) message
                     (nth arguments 2)]
                    [(nth arguments 0) (nth arguments 1) message])))

          "assertNotEquals"
          (let [delta? (and (= 3 (count arguments))
                            (floating-parameter? (last parameters)))]
            (call "NotEqual"
                  (if delta?
                    [(nth arguments 0) (nth arguments 1) message
                     (nth arguments 2)]
                    [(nth arguments 0) (nth arguments 1) message])))

          "assertTrue" (call "True" [(first arguments) message])
          "assertFalse" (call "False" [(first arguments) message])
          "assertNull" (call "Null" [(first arguments) message])
          "assertNotNull" (call "NotNull" [(first arguments) message])
          "assertSame" (call "Same" [(first arguments) (second arguments) message])
          "assertNotSame"
          (call "NotSame" [(first arguments) (second arguments) message])

          ("assertThrows" "assertThrowsExactly")
          (if-let [reference
                   (class-literal-reference (first (.getArguments element)))]
            (generic-static-call destination-context
                                 "global::DripSharp.Testing.JavaAssertions"
                                 (if (= "assertThrowsExactly" operation)
                                   "ThrowsExactly"
                                   "Throws")
                                 reference
                                 [(second arguments) message])
            (unsupported! event family identity))

          "assertInstanceOf"
          (if-let [reference
                   (class-literal-reference (first (.getArguments element)))]
            (generic-static-call destination-context
                                 "global::DripSharp.Testing.JavaAssertions"
                                 "InstanceOf" reference
                                 [(second arguments) message])
            (unsupported! event family identity))

          "assertDoesNotThrow"
          (call "DoesNotThrow" (conj (vec arguments) message))

          "assertAll"
          (call "All" (conj (vec arguments) message))

          "fail"
          (call "Fail" [(or (first arguments) (raw "\"Assertion failed.\""))])

          (unsupported! event family identity))))))

(defn- assertj-adaptation
  [{:keys [destination-context ^CtInvocation element target-node arguments]
    :as event} identity]
  (let [operation (:operation identity)
        factory? (contains? (get-in framework-contracts
                                    [:assertj :factory-operations])
                            operation)
        fluent? (contains? (get-in framework-contracts
                                   [:assertj :fluent-operations])
                           operation)]
    (cond
      (= "fail" operation)
      (static-call "global::DripSharp.Testing.JavaAssertions" "Fail" arguments)

      (= "assertThat" operation)
      (static-call "global::DripSharp.Testing.JavaAssertJ" "That" arguments)

      (= "assertThatList" operation)
      (static-call "global::DripSharp.Testing.JavaAssertJ" "That" arguments)

      (contains? #{"assertThatThrownBy" "assertThatCode"} operation)
      (static-call "global::DripSharp.Testing.JavaAssertJ" "ThrownBy" arguments)

      (= "assertThatExceptionOfType" operation)
      (static-call "global::DripSharp.Testing.JavaAssertJ" "ExceptionOfType"
                   arguments)

      (= "catchThrowable" operation)
      (static-call "global::DripSharp.Testing.JavaAssertJ" "CatchThrowable"
                   arguments)

      (= "entry" operation)
      (static-call "global::DripSharp.Testing.JavaAssertJ" "Entry" arguments)

      (and (contains? #{"allSatisfy" "extracting"} operation) target-node)
      (member-call
       target-node
       (java-library/pascal operation)
       [(typed-functional-argument
         destination-context event operation (first arguments)
         (first (.getArguments element)))])

      (and fluent? target-node)
      (member-call target-node (java-library/pascal operation) arguments)

      factory? (unsupported! event :assertj identity)
      :else (unsupported! event :assertj identity))))

(defn- hamcrest-adaptation
  [{:keys [target-node arguments] :as event} identity]
  (let [operation (:operation identity)]
    (cond
      (= "assertThat" operation)
      (static-call "global::DripSharp.Testing.JavaHamcrest" "AssertThat"
                   arguments)

      (= "matches" operation)
      (member-call target-node "Matches" arguments)

      (contains? (get-in framework-contracts [:hamcrest :matcher-operations])
                 operation)
      (static-call "global::DripSharp.Testing.JavaHamcrest"
                   (java-library/pascal operation) arguments)

      :else (unsupported! event :hamcrest identity))))

(def ^:private mockito-primitive-matchers
  {"anyBoolean" "AnyBoolean" "anyByte" "AnyByte" "anyChar" "AnyChar"
   "anyDouble" "AnyDouble" "anyFloat" "AnyFloat" "anyInt" "AnyInt"
   "anyLong" "AnyLong" "anyShort" "AnyShort" "anyString" "AnyString"})

(defn- invocation-type
  [^CtInvocation element]
  (.getType element))

(defn- mockito-adaptation
  [{:keys [destination-context ^CtInvocation element target-node arguments]
    :as event} identity]
  (let [owner (:owner identity)
        operation (:operation identity)
        static? (contains? (get-in framework-contracts
                                   [:mockito :static-operations]) operation)
        stubbing? (contains? (get-in framework-contracts
                                     [:mockito :stubbing-operations]) operation)]
    (cond
      (= "mock" operation)
      (if-let [reference (and (= 1 (count arguments))
                              (class-literal-reference
                               (first (.getArguments element))))]
        (generic-static-call destination-context
                             "global::DripSharp.Testing.JavaMockito"
                             "Mock" reference [])
        (unsupported! event :mockito identity))

      (= "spy" operation)
      (if-let [reference (invocation-type element)]
        (generic-static-call destination-context
                             "global::DripSharp.Testing.JavaMockito"
                             "Spy" reference arguments)
        (unsupported! event :mockito identity))

      (and (= "given" operation)
           target-node
           (not= "org.mockito.BDDMockito" owner))
      (member-call target-node "Given" arguments)

      (contains? #{"given" "when"} operation)
      (static-call "global::DripSharp.Testing.JavaMockito" "Given" arguments)

      (= "then" operation)
      (static-call "global::DripSharp.Testing.JavaMockito" "Then" arguments)

      (= "will" operation)
      (static-call "global::DripSharp.Testing.JavaMockito" "Will" arguments)

      (and (= "should" operation) target-node)
      (member-call target-node "Should" arguments)

      (= "verify" operation)
      (static-call "global::DripSharp.Testing.JavaMockito" "Verify" arguments)

      (contains? #{"times" "never" "only" "atLeast" "atLeastOnce"
                   "atMost" "atMostOnce" "after" "timeout"}
                 operation)
      (static-call "global::DripSharp.Testing.JavaMockito"
                   (java-library/pascal operation) arguments)

      (contains? #{"reset" "clearInvocations" "verifyNoInteractions"
                   "verifyNoMoreInteractions"} operation)
      (static-call "global::DripSharp.Testing.JavaMockito"
                   (java-library/pascal operation) arguments)

      (contains? mockito-primitive-matchers operation)
      (static-call "global::DripSharp.Testing.JavaMockito"
                   (get mockito-primitive-matchers operation) arguments)

      (contains? #{"any" "eq" "same" "isNull" "notNull" "argThat"}
                 operation)
      (if-let [reference (invocation-type element)]
        (generic-static-call destination-context
                             "global::DripSharp.Testing.JavaMockito"
                             (java-library/pascal operation) reference arguments)
        (unsupported! event :mockito identity))

      (and stubbing? target-node)
      (member-call target-node (java-library/pascal operation) arguments)

      static? (unsupported! event :mockito identity)
      :else (unsupported! event :mockito identity))))

(declare route-target-facility!)

(defn- supported-operation?
  [family operation]
  (case family
    (:junit4 :jupiter)
    (contains? (get-in framework-contracts [family :operations]) operation)

    :assertj
    (or (contains? (get-in framework-contracts
                           [:assertj :factory-operations]) operation)
        (contains? (get-in framework-contracts
                           [:assertj :fluent-operations]) operation))

    :hamcrest
    (or (= "assertThat" operation)
        (contains? (get-in framework-contracts
                           [:hamcrest :matcher-operations]) operation))

    :mockito
    (or (contains? (get-in framework-contracts
                           [:mockito :static-operations]) operation)
        (contains? (get-in framework-contracts
                           [:mockito :stubbing-operations]) operation))

    false))

(defn validate-test-body!
  "Preflights every resolved test-framework/facility call before the accepted
  translator aggregates structural diagnostics. This preserves the exact
  unsupported resolved identity as the generation failure."
  [resolved-model ^CtElement body destination-context]
  (let [index (java/resolved-occurrence-index resolved-model)
        target-strategy (:target-test-facility-strategy destination-context)]
    (doseq [^CtElement invocation
            (.getElements
             body
             (reify spoon.reflect.visitor.Filter
               (matches [_ element]
                 (or (instance? CtInvocation element)
                     (instance? CtConstructorCall element)))))]
      (when-let [occurrence
                 (.get ^IdentityHashMap index
                       (if (instance? CtInvocation invocation)
                         (.getExecutable ^CtInvocation invocation)
                         (.getExecutable ^CtConstructorCall invocation)))]
        (when-let [identity (executable-identity (:key occurrence))]
          (let [event {:element invocation :occurrence occurrence}
                owner (:owner identity)]
            (if-let [facility (target-facility owner)]
              (cond
                (= :evidence-only-no-kotlin-frontend
                   (:reuse-boundary facility))
                (route-target-facility! event facility nil)

                (nil? target-strategy)
                (route-target-facility! event facility nil)

                :else nil)
              (when-let [family (framework-family owner)]
                (when-not (supported-operation? family (:operation identity))
                  (unsupported! event family identity))))))))
    body))

(defn- framework-owned-type?
  [qualified]
  (starts-with-any?
   qualified
   ["org.junit." "org.assertj.core.api." "org.hamcrest."
    "org.mockito."]))

(defn- framework-type-destination
  [qualified]
  (case qualified
    "org.junit.jupiter.api.function.Executable" "global::System.Action"
    "org.junit.jupiter.api.function.ThrowingSupplier" "global::System.Delegate"
    "org.mockito.verification.VerificationMode"
    "global::DripSharp.Testing.JavaVerificationMode"
    "org.mockito.stubbing.Answer" "global::DripSharp.Testing.JavaAnswer"
    "object"))

(defn- discovered-type-mappings
  [^IdentityHashMap occurrence-index]
  (if occurrence-index
    (reduce
     (fn [mappings ^java.util.Map$Entry entry]
       (let [occurrence (.getValue entry)
             key (:key occurrence)
             qualified
             (when (= :type (:kind occurrence))
               (second (re-matches #"^type:(.+)$" (or key ""))))]
         (if (and qualified (framework-owned-type? qualified))
           (assoc mappings qualified
                  [(framework-type-destination qualified)
                   :dotnet.type/java-test-support])
           mappings)))
     {}
     (.entrySet occurrence-index))
    {}))

(defn- resolved-test-name
  [occurrence]
  (when-let [identity (executable-identity (:key occurrence))]
    (when (or (framework-family (:owner identity))
              (target-facility (:owner identity)))
      (java-library/pascal (:operation identity)))))

(defn- route-target-facility!
  [event facility target-strategy]
  (when (= :evidence-only-no-kotlin-frontend (:reuse-boundary facility))
    (fail! "Kotlin-only test facility reached the Java translation frontend"
           (merge {:reason :kotlin-test-facility-in-java
                   :facility (:facility facility)
                   :resolved-key (get-in event [:occurrence :key])}
                  (source-data (:element event)))))
  (if target-strategy
    (or (target-strategy
         (assoc event
                :facility (:facility facility)
                :facility-contract facility))
        (fail! "Target Java test-facility strategy declined a resolved call"
               (merge {:reason :unmapped-target-test-facility
                       :facility (:facility facility)
                       :resolved-key (get-in event [:occurrence :key])}
                      (source-data (:element event)))))
    (fail! "Resolved target Java test facility has no target strategy"
           (merge {:reason :unmapped-target-test-facility
                   :facility (:facility facility)
                   :resolved-key (get-in event [:occurrence :key])}
                  (source-data (:element event))))))

(defn adapt-invocation
  "Returns a C# node for a known Java test call, nil for unrelated calls, and
  fails for every unsupported call owned by a known framework or facility."
  ([event] (adapt-invocation event nil))
  ([event target-strategy]
   (when-let [identity (executable-identity (get-in event [:occurrence :key]))]
     (let [owner (:owner identity)]
       (if-let [facility (target-facility owner)]
         (route-target-facility! event facility target-strategy)
         (case (framework-family owner)
           :junit4 (junit-adaptation event :junit4 identity)
           :jupiter (junit-adaptation event :jupiter identity)
           :assertj (assertj-adaptation event identity)
           :hamcrest (hamcrest-adaptation event identity)
           :mockito (mockito-adaptation event identity)
           nil))))))

(defn adapt-constructor
  "Routes constructors owned by target facilities through the same explicit
  strategy boundary. Framework constructors are unsupported by default."
  ([event] (adapt-constructor event nil))
  ([event target-strategy]
   (let [key (get-in event [:occurrence :key])
         owner (second (re-matches #"^executable:(.+)#<init>\(.*\)$"
                                   (or key "")))]
     (when owner
       (cond
         (target-facility owner)
         (route-target-facility! event (target-facility owner) target-strategy)

         (framework-family owner)
         (unsupported! event (framework-family owner)
                       {:owner owner :operation "<init>" :parameters []})

         :else nil)))))

(defn compose-destination-context
  "Installs the shared adapter ahead of an existing target adapter. The target
  adapter remains authoritative for non-test calls; target test facilities are
  visible only through `target-test-facility-strategy`."
  [destination-context]
  (if (:java-test-adapters-composed? destination-context)
    destination-context
    (let [base-invocation (:destination-invocation-adapter destination-context)
          base-constructor (:destination-constructor-adapter destination-context)
          base-resolved-name (:destination-resolved-name destination-context)
          base-resolved-constructor?
          (:destination-resolved-constructor? destination-context)
          target-strategy (:target-test-facility-strategy destination-context)
          type-mappings
          (merge (discovered-type-mappings
                  (:occurrence-index destination-context))
                 (:destination-type-mappings destination-context))]
      (assoc destination-context
             :java-test-adapters-composed? true
             :destination-type-mappings type-mappings
             :destination-resolved-name
             (fn [context occurrence reference]
               (or (resolved-test-name occurrence)
                   (when base-resolved-name
                     (base-resolved-name context occurrence reference))))
             :destination-resolved-constructor?
             (fn [context occurrence reference]
               (let [identity (executable-identity (:key occurrence))
                     owner (:owner identity)]
                 (or (and (= "<init>" (:operation identity))
                          (or (framework-family owner)
                              (target-facility owner)))
                     (when base-resolved-constructor?
                       (base-resolved-constructor?
                        context occurrence reference)))))
             :destination-invocation-adapter
             (fn [event]
               (or (adapt-invocation event target-strategy)
                   (when base-invocation (base-invocation event))))
             :destination-constructor-adapter
             (fn [event]
               (or (adapt-constructor event target-strategy)
                   (when base-constructor (base-constructor event))))))))
