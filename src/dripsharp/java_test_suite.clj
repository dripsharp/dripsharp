(ns dripsharp.java-test-suite
  "Deterministic, fail-closed emission of adapted Java tests as xUnit suites.

  A target-owned suite declaration pins the selected Java inputs, target
  provenance, adapted helper sources, fixtures, and accounting digests. Java
  and Spoon are used only while emitting disposable C#. Verification of a
  staged or shipped tree reads generated ledgers and never invokes Java."
  (:require [clojure.string :as str]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-test-adapters :as adapters]
            [dripsharp.java-translate :as java]
            [dripsharp.junit-xunit :as junit]
            [dripsharp.paths :as paths]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.file CopyOption Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util IdentityHashMap]
           [spoon.reflect.code CtInvocation]
           [spoon.reflect.declaration CtMethod CtParameter]))

(def schema-version 1)

(def governed-revisions
  "Pinned target baselines represented by the cross-target proof suite."
  {:pkl "f7cac257ade5775c1dfc255f4fda2eacc296e9d0"
   :pdfcarton "9286e47d89d6877005c9d2d0f2fd38793a62519a"
   :rawhttp "947cfdc619100a23f5e429ccb3c42ba6fedc8141"
   :sqltrellis "8a9479a05c75fcb73d0ed167a822b9b18ab7abaa"})

(def ^:private contract-keys
  #{:schema-version :suite-id :generated-namespace :sources
    :support-sources :fixtures :expected-accounting})

(def ^:private source-keys
  #{:target :path :sha256 :revision :upstream-path :selected-surface})

(def ^:private support-source-keys
  #{:path :destination :sha256 :kind :targets})

(def ^:private fixture-keys
  #{:path :destination :sha256 :license :attribution})

(def ^:private accounting-keys
  #{:tests :parameter-rows :helpers :fixtures :enablement :framework-calls})

(def ^:private digest-fields
  [:tests :parameter-rows :helpers :enablement :framework-calls])

(def ^:private framework-class-names
  ["org.junit.Test"
   "org.junit.jupiter.api.Test"
   "org.junit.jupiter.params.ParameterizedTest"
   "org.assertj.core.api.Assertions"
   "org.hamcrest.MatcherAssert"
   "org.mockito.Mockito"])

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :java-test-suite-generation-failed))))

(defn- exact-keys!
  [subject expected value]
  (let [actual (when (map? value) (set (keys value)))]
    (when-not (= expected actual)
      (fail! (str subject " has missing or unknown fields")
             {:reason :invalid-java-test-suite-contract
              :subject subject
              :missing (vec (sort (remove actual expected)))
              :unknown (vec (sort (remove expected actual)))})))
  value)

(defn- sha256?
  [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- relative-path!
  [subject value]
  (let [value (str value)
        path (paths/path value)]
    (when (or (str/blank? value) (.isAbsolute path)
              (some #(= ".." (str %)) (iterator-seq (.iterator path))))
      (fail! (str subject " must be a contained relative path")
             {:reason :java-test-suite-path-escape
              :subject subject :path value})))
  value)

(defn- contained-file!
  [root subject relative]
  (relative-path! subject relative)
  (let [root (paths/absolute root)
        file (paths/absolute (paths/resolve-path root relative))]
    (when-not (.startsWith ^Path file ^Path root)
      (fail! (str subject " escapes the suite root")
             {:reason :java-test-suite-path-escape
              :subject subject :root (str root) :path (str file)}))
    (when-not (paths/regular-file? file)
      (fail! (str subject " is missing")
             {:reason :missing-java-test-suite-input
              :subject subject :path (str file)}))
    file))

(defn- validate-file-entry!
  [root subject expected-keys entry]
  (exact-keys! subject expected-keys entry)
  (let [file (contained-file! root subject (:path entry))
        expected (:sha256 entry)
        actual (util/sha256-file file)]
    (when-not (sha256? expected)
      (fail! (str subject " has an invalid SHA-256")
             {:reason :invalid-java-test-suite-sha256
              :subject subject :sha256 expected}))
    (when-not (= expected actual)
      (fail! (str subject " checksum changed")
             {:reason :java-test-suite-input-checksum-mismatch
              :subject subject :path (str file)
              :expected expected :actual actual})))
  entry)

(defn validate-contract!
  "Validates a target-owned suite declaration and every checksum-pinned input."
  [suite-root contract]
  (exact-keys! "Java test-suite declaration" contract-keys contract)
  (when-not (= schema-version (:schema-version contract))
    (fail! "Java test-suite declaration has an unsupported schema"
           {:reason :unsupported-java-test-suite-schema
            :actual (:schema-version contract) :expected schema-version}))
  (when-not (and (string? (:suite-id contract))
                 (not (str/blank? (:suite-id contract)))
                 (string? (:generated-namespace contract))
                 (re-matches #"[A-Za-z_][A-Za-z0-9_.]*"
                             (:generated-namespace contract)))
    (fail! "Java test-suite declaration has an invalid identity"
           {:reason :invalid-java-test-suite-identity
            :suite-id (:suite-id contract)
            :generated-namespace (:generated-namespace contract)}))
  (when-not (and (vector? (:sources contract)) (seq (:sources contract))
                 (vector? (:support-sources contract))
                 (vector? (:fixtures contract)))
    (fail! "Java test-suite input collections must be vectors"
           {:reason :invalid-java-test-suite-collections}))
  (doseq [source (:sources contract)]
    (validate-file-entry! suite-root "Java test source" source-keys source)
    (when-not (= (get governed-revisions (:target source)) (:revision source))
      (fail! "Representative Java test source changed its governed revision"
             {:reason :java-test-suite-revision-drift
              :target (:target source)
              :expected (get governed-revisions (:target source))
              :actual (:revision source)})))
  (when-not (= (set (keys governed-revisions))
               (set (map :target (:sources contract))))
    (fail! "Cross-target proof does not represent every governed Java baseline"
           {:reason :incomplete-cross-target-java-test-proof
            :expected (vec (sort (keys governed-revisions)))
            :actual (vec (sort (set (map :target (:sources contract)))))}))
  (doseq [support (:support-sources contract)]
    (validate-file-entry! suite-root "Adapted Java test helper"
                          support-source-keys support)
    (relative-path! "Adapted Java test helper destination"
                    (:destination support)))
  (doseq [fixture (:fixtures contract)]
    (validate-file-entry! suite-root "Java test fixture" fixture-keys fixture)
    (relative-path! "Java test fixture destination" (:destination fixture))
    (when-not (every? #(and (string? %) (not (str/blank? %)))
                      [(:license fixture) (:attribution fixture)])
      (fail! "Java test fixture attribution is incomplete"
             {:reason :invalid-java-test-fixture-attribution
              :fixture (:destination fixture)})))
  (let [destinations (concat (map :destination (:support-sources contract))
                             (map :destination (:fixtures contract)))]
    (when-not (= (count destinations) (count (distinct destinations)))
      (fail! "Java test-suite output destinations collide"
             {:reason :duplicate-java-test-suite-destination
              :destinations (vec destinations)})))
  (exact-keys! "Expected Java test accounting"
               accounting-keys (:expected-accounting contract))
  (doseq [field digest-fields]
    (when-not (sha256? (get-in contract [:expected-accounting field]))
      (fail! "Expected Java test accounting has an invalid digest"
             {:reason :invalid-java-test-accounting-digest
              :field field
              :value (get-in contract [:expected-accounting field])})))
  (when-not (= (vec (sort (map :destination (:fixtures contract))))
               (get-in contract [:expected-accounting :fixtures]))
    (fail! "Expected Java test fixture accounting differs from declared fixtures"
           {:reason :java-test-fixture-accounting-drift
            :expected (vec (sort (map :destination (:fixtures contract))))
            :actual (get-in contract [:expected-accounting :fixtures])}))
  contract)

(defn read-contract!
  "Reads exactly one checksum-validated Java test-suite declaration."
  [suite-root contract-file]
  (let [file (contained-file! suite-root "Java test-suite declaration"
                              contract-file)
        contract (util/read-single-edn-string! (slurp (str file)))]
    (validate-contract! suite-root contract)))

(defn- classpath-location
  [class-name]
  (try
    (-> (Class/forName class-name)
        .getProtectionDomain .getCodeSource .getLocation .toURI
        java.nio.file.Paths/get
        paths/absolute)
    (catch ClassNotFoundException error
      (throw
       (ex-info "Required Java test framework is absent from the generation classpath"
                {:kind :java-test-suite-generation-failed
                 :reason :missing-java-test-framework-classpath
                 :class class-name}
                error)))))

(defn- resolved-model!
  [workspace-root suite-root contract]
  (let [source-files
        (->> (:sources contract)
             (map #(contained-file! suite-root "Java test source" (:path %)))
             (sort-by str)
             vec)
        source-root (paths/absolute (paths/resolve-path suite-root "java"))
        classpath (->> framework-class-names
                       (map classpath-location)
                       distinct
                       (sort-by str)
                       vec)
        input
        {:schema-version 1
         :project-id (:suite-id contract)
         :source-roots [source-root]
         :resource-roots []
         :production-sources source-files
         :generated-production-sources []
         :production-resources []
         :test-source-roots []
         :test-resource-roots []
         :test-sources []
         :test-resources []
         :java-toolchain
         {:home (paths/absolute (System/getProperty "java.home"))
          :release 17
          :preview-features? false}
         :project-dependencies []
         :external-dependencies []
         :classpath-artifacts
         (mapv (fn [path] {:scope :compile :path path}) classpath)
         :test-project-dependencies []
         :test-external-dependencies []
         :test-classpath-artifacts []}]
    (spoon/build-resolved-model! workspace-root input)))

(defn- canonicalize
  [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key child]] [key (canonicalize child)]))
                       value)
    (set? value) (mapv canonicalize (sort-by pr-str value))
    (vector? value) (mapv canonicalize value)
    (sequential? value) (mapv canonicalize value)
    :else value))

(defn- stable-text
  [value]
  (str (pr-str (canonicalize value)) "\n"))

(defn accounting-digests
  "Returns stable digests for every loss-sensitive accounting projection."
  [accounting]
  (into (sorted-map)
        (map (fn [field]
               [field (util/sha256-text
                       (stable-text (get accounting field)))]))
        digest-fields))

(defn verify-accounting!
  "Rejects dropped tests or rows, weakened framework calls, helper loss,
  fixture loss, and changed enablement against a pinned declaration."
  [expected accounting]
  (let [actual-digests (accounting-digests accounting)]
    (doseq [field digest-fields]
      (when-not (= (get expected field) (get actual-digests field))
        (fail! "Generated Java test accounting differs from its pinned contract"
               {:reason :java-test-accounting-perturbation
                :section field
                :expected (get expected field)
                :actual (get actual-digests field)})))
    (when-not (= (:fixtures expected) (:fixtures accounting))
      (fail! "Generated Java test fixtures differ from their pinned contract"
             {:reason :java-test-accounting-perturbation
              :section :fixtures
              :expected (:fixtures expected)
              :actual (:fixtures accounting)}))
    accounting))

(defn- portable-source
  [suite-root source]
  (util/portable-or-absolute-path
   (paths/absolute suite-root)
   (paths/absolute (paths/path (:file source)))))

(defn- source-index
  [suite-root contract]
  (into {}
        (map (fn [entry]
               [(str (.toRealPath
                      ^Path
                      (contained-file! suite-root "Java test source"
                                       (:path entry))
                      (make-array java.nio.file.LinkOption 0)))
                entry]))
        (:sources contract)))

(defn- case-source!
  [suite-root sources test-case]
  (let [path (str (.toRealPath
                   (paths/path (get-in test-case [:source :file]))
                   (make-array java.nio.file.LinkOption 0)))]
    (or (get sources path)
        (fail! "Discovered Java test has no pinned source provenance"
               {:reason :missing-java-test-source-provenance
                :case (:id test-case)
                :source (portable-source suite-root (:source test-case))}))))

(defn- parameter-row-ids
  [test-case]
  (let [parameters (:parameters test-case)]
    (cond
      (= :inline-rows (:kind parameters))
      (mapv #(str (:id test-case) "/" (:id %)) (:rows parameters))

      (= :composite-sources (:kind parameters))
      (vec
       (mapcat
        (fn [index source]
          (if (= :inline-rows (:kind source))
            (map #(str (:id test-case) "/source:" index "/" (:id %))
                 (:rows source))
            [(str (:id test-case) "/source:" index "/runtime")]))
        (range) (:sources parameters)))

      parameters [(str (:id test-case) "/runtime")]
      :else [])))

(defn- framework-call-keys
  [^IdentityHashMap occurrence-index ^CtMethod method]
  (->> (.getElements
        (.getBody method)
        (reify spoon.reflect.visitor.Filter
          (matches [_ element] (instance? CtInvocation element))))
       (keep (fn [^CtInvocation invocation]
               (some-> (.get occurrence-index (.getExecutable invocation)) :key)))
       (filter #(or (str/starts-with? % "executable:org.junit.")
                    (str/starts-with? % "executable:org.assertj.")
                    (str/starts-with? % "executable:org.hamcrest.")
                    (str/starts-with? % "executable:org.mockito.")))
       vec))

(defn- helper-accounting
  [plan]
  (let [test-methods (set (map :declaring-method (:cases plan)))
        test-classes (set (map :class (:cases plan)))]
    {:java-types
     (->> (keys (:classes plan))
          (remove test-classes)
          sort vec)
     :java-methods
     (->> (:classes plan)
          vals
          (mapcat :methods)
          (map :id)
          (remove test-methods)
          sort vec)}))

(defn build-accounting
  "Builds the complete stable accounting projection from a resolved plan."
  [suite-root contract resolved-model plan]
  (let [sources (source-index suite-root contract)
        occurrence-index (java/resolved-occurrence-index resolved-model)
        cases (sort-by :id (:cases plan))]
    {:tests
     (mapv (fn [test-case]
             (let [source (case-source! suite-root sources test-case)]
               {:case-id (:id test-case)
                :target (:target source)
                :revision (:revision source)
                :upstream-path (:upstream-path source)
                :source (:path source)}))
           cases)
     :parameter-rows (vec (mapcat parameter-row-ids cases))
     :helpers
     (assoc (helper-accounting plan)
            :adapted-support
            (mapv #(select-keys % [:destination :kind :targets :sha256])
                  (sort-by :destination (:support-sources contract))))
     :fixtures (vec (sort (map :destination (:fixtures contract))))
     :enablement
     (into (sorted-map)
           (map (fn [test-case]
                  [(:id test-case)
                   (if-let [disabled (:disabled test-case)]
                     {:state :disabled :reason (:reason disabled)}
                     {:state :enabled :reason nil})]))
           cases)
     :framework-calls
     (into (sorted-map)
           (map (fn [test-case]
                  [(:id test-case)
                   (framework-call-keys occurrence-index
                                        (:method-element test-case))]))
           cases)}))

(defn- destination-context
  [contract resolved-model]
  {:configuration
   {:namespaces {}
    :namespace-prefixes {"representative" (:generated-namespace contract)}
    :project {:nullable "enable"}
    :destination-capabilities #{}}
   :occurrence-index (java/resolved-occurrence-index resolved-model)
   :destination-type-mappings
   {"representative.pdfcarton.Clock"
    [(str "global::" (:generated-namespace contract) ".Clock")
     :test.target/pdfcarton-clock]
    "representative.pdfcarton.RealClock"
    [(str "global::" (:generated-namespace contract) ".RealClock")
     :test.target/pdfcarton-real-clock]}
   :runtime-capabilities
   {:labeled-control-flow
    {:exception-type
     "global::DripSharp.Runtime.JavaLabeledControlFlowException"}}})

(defn- indent
  [text spaces]
  (let [prefix (apply str (repeat spaces " "))]
    (->> (str/split-lines (str text))
         (map #(if (str/blank? %) "" (str prefix %)))
         (str/join "\n"))))

(defn- generated-identifier
  [value]
  (let [clean (str/replace (str value) #"[^A-Za-z0-9_]" "_")]
    (if (re-matches #"[0-9].*" clean) (str "_" clean) clean)))

(defn- generated-class-name
  [class-name]
  (str "Generated_" (generated-identifier class-name)))

(defn- generated-method-name
  [test-case]
  (let [method (.getSimpleName ^CtMethod (:method-element test-case))
        signature (util/sha256-text (:id test-case))]
    (str (java-library/pascal method) "_" (subs signature 0 10))))

(defn- type-text
  [context ^CtParameter parameter]
  (:text (csharp/render
          (java-library/type-node context (.getType parameter)))))

(defn- parameter-text
  [context ^CtMethod method]
  (->> (.getParameters method)
       (map (fn [^CtParameter parameter]
              (str (type-text context parameter) " "
                   (java-library/identifier (.getSimpleName parameter)))))
       (str/join ", ")))

(defn- method-record-index
  [plan]
  (into {}
        (map (juxt :id identity))
        (mapcat :methods (vals (:classes plan)))))

(defn- translated-method-body!
  [resolved-model context id record]
  (:text
   (junit/translate-test-body!
    resolved-model context
    {:id id
     :source (:source record)
     :method-element (:element record)})))

(defn- lifecycle-helper-name
  [method-id]
  (str "__Lifecycle_" (subs (util/sha256-text method-id) 0 12)))

(defn- validate-emittable-case!
  [test-case]
  (let [parameters (:parameters test-case)]
    (when-not (or (nil? parameters) (= :inline-rows (:kind parameters)))
      (fail! "Representative xUnit emission requires statically accounted rows"
             {:reason :unsupported-generated-parameter-source
              :case (:id test-case) :parameters parameters}))
    (when (seq (:temporary-resources test-case))
      (fail! "Representative xUnit emission has no selected temporary resource"
             {:reason :unsupported-generated-temporary-resource
              :case (:id test-case)}))
    (when (:timeout test-case)
      (fail! "Representative xUnit emission has no selected timeout"
             {:reason :unsupported-generated-timeout
              :case (:id test-case)}))
    (when (= :dynamic-factory (:kind test-case))
      (fail! "Representative xUnit emission requires statically discovered cases"
             {:reason :unsupported-generated-dynamic-case
              :case (:id test-case)})))
  test-case)

(defn- exception-type
  [expected]
  (case (get-in expected [:exception :class])
    "java.lang.IllegalArgumentException" "global::System.ArgumentException"
    "java.lang.IllegalStateException" "global::System.InvalidOperationException"
    (fail! "Expected Java exception has no generated xUnit type mapping"
           {:reason :unsupported-generated-expected-exception
            :expected expected})))

(defn- case-body
  [resolved-model context test-case]
  (let [body (:text (junit/translate-test-body!
                     resolved-model context test-case))]
    (if-let [expected (:expected-exception test-case)]
      (str "{\n"
           "    global::DripSharp.Testing.JavaAssertions.Throws<"
           (exception-type expected) ">(() => " body ", null);\n"
           "}")
      body)))

(defn- render-case
  [resolved-model context test-case]
  (validate-emittable-case! test-case)
  (let [attributes (str/join "\n" (junit/xunit-attributes test-case))
        ^CtMethod method (:method-element test-case)
        before (get-in test-case [:lifecycle :before-each])
        after (get-in test-case [:lifecycle :after-each])
        body (case-body resolved-model context test-case)]
    (str (indent attributes 4) "\n"
         "    public void " (generated-method-name test-case)
         "(" (parameter-text context method) ")\n"
         "    {\n"
         (apply str
                (map #(str "        " (lifecycle-helper-name %) "();\n")
                     before))
         "        try\n"
         (indent body 8) "\n"
         "        finally\n"
         "        {\n"
         (apply str
                (map #(str "            " (lifecycle-helper-name %) "();\n")
                     after))
         "        }\n"
         "    }")))

(defn- render-lifecycle-helper
  [resolved-model context methods method-id]
  (let [record
        (or (get methods method-id)
            (fail! "JUnit lifecycle method is absent from the resolved plan"
                   {:reason :missing-generated-lifecycle-method
                    :method method-id}))]
    (str "    private void " (lifecycle-helper-name method-id) "() "
         (translated-method-body! resolved-model context method-id record))))

(defn- render-test-source
  [contract resolved-model plan]
  (let [context (destination-context contract resolved-model)
        methods (method-record-index plan)
        grouped (group-by :class (:cases plan))]
    (str "// SPDX-FileCopyrightText: 2026 Isak Sky\n"
         "// SPDX-License-Identifier: Apache-2.0\n\n"
         "#nullable enable\n"
         "[assembly: Xunit.CollectionBehavior(DisableTestParallelization = true)]\n\n"
         "namespace " (:generated-namespace contract) ";\n\n"
         (str/join
          "\n\n"
          (for [[class-name cases] (sort-by key grouped)
                :let [cases (sort-by :id cases)
                      class-lifecycle (get-in plan [:classes class-name :lifecycle])
                      _ (when (or (seq (:before-all class-lifecycle))
                                  (seq (:after-all class-lifecycle)))
                          (fail! "Representative xUnit class lifecycle is not selected"
                                 {:reason :unsupported-generated-class-lifecycle
                                  :class class-name
                                  :before-all (:before-all class-lifecycle)
                                  :after-all (:after-all class-lifecycle)}))
                      lifecycle-ids
                      (->> cases
                           (mapcat #(concat (get-in % [:lifecycle :before-each])
                                            (get-in % [:lifecycle :after-each])))
                           distinct sort vec)]]
            (str "public sealed class " (generated-class-name class-name) "\n"
                 "{\n"
                 (when (seq lifecycle-ids)
                   (str (str/join
                         "\n\n"
                         (map #(render-lifecycle-helper
                                resolved-model context methods %)
                              lifecycle-ids))
                        "\n\n"))
                 (str/join "\n\n"
                           (map #(render-case resolved-model context %) cases))
                 "\n}")))
         "\n")))

(defn- write-text!
  [path text]
  (Files/createDirectories (.getParent (paths/path path))
                           (make-array FileAttribute 0))
  (Files/writeString (paths/path path) (str text) (make-array OpenOption 0))
  path)

(defn- copy-file!
  [source destination]
  (Files/createDirectories (.getParent (paths/path destination))
                           (make-array FileAttribute 0))
  (Files/copy (paths/path source) (paths/path destination)
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
  destination)

(defn- render-fixture-targets
  [fixtures]
  (str "<Project>\n  <ItemGroup>\n"
       (apply str
              (for [{:keys [destination]} (sort-by :destination fixtures)]
                (str "    <None Update=\"" (util/xml-escape destination) "\">\n"
                     "      <CopyToOutputDirectory>PreserveNewest"
                     "</CopyToOutputDirectory>\n"
                     "    </None>\n")))
       "  </ItemGroup>\n</Project>\n"))

(defn- render-integrity-source
  [contract]
  (str
   "// SPDX-FileCopyrightText: 2026 Isak Sky\n"
   "// SPDX-License-Identifier: Apache-2.0\n\n"
   "#nullable enable\n"
   "namespace " (:generated-namespace contract) ";\n\n"
   "public sealed class GeneratedSuiteIntegrityTests\n{\n"
   (apply
    str
    (map-indexed
     (fn [index {:keys [destination sha256]}]
       (str "    [Xunit.Fact]\n"
            "    public void Fixture_" index "_IsPresentAndPinned()\n"
            "    {\n"
            "        string path = global::System.IO.Path.Combine(\n"
            "            global::System.AppContext.BaseDirectory, \""
            (str/replace destination "\\" "/") "\");\n"
            "        Xunit.Assert.True(global::System.IO.File.Exists(path), path);\n"
            "        string actual = global::System.Convert.ToHexString(\n"
            "            global::System.Security.Cryptography.SHA256.HashData(\n"
            "                global::System.IO.File.ReadAllBytes(path))).ToLowerInvariant();\n"
            "        Xunit.Assert.Equal(\"" sha256 "\", actual);\n"
            "    }\n\n"))
     (sort-by :destination (:fixtures contract))))
   "}\n"))

(defn- provenance-rows
  [contract accounting]
  (let [enablement (:enablement accounting)]
    (vec
     (concat
      (for [{:keys [case-id target revision upstream-path source]}
            (:tests accounting)]
        ["test" (name target) case-id "-"
         (name (get-in enablement [case-id :state]))
         (or (get-in enablement [case-id :reason]) "-")
         revision upstream-path source "GeneratedJavaTests.cs"])
      (for [row (:parameter-rows accounting)
            :let [case-id (some #(when (str/starts-with? row (str (:case-id %) "/"))
                                   (:case-id %))
                                (:tests accounting))
                  test-entry (some #(when (= case-id (:case-id %)) %)
                                   (:tests accounting))]]
        ["parameter-row" (name (:target test-entry)) case-id row
         (name (get-in enablement [case-id :state]))
         (or (get-in enablement [case-id :reason]) "-")
         (:revision test-entry) (:upstream-path test-entry) (:source test-entry)
         "GeneratedJavaTests.cs"])
      (for [type (get-in accounting [:helpers :java-types])]
        ["java-helper-type" "shared" type "-" "support" "-" "-" "-" "-"
         "RepresentativeTypes.cs"])
      (for [method (get-in accounting [:helpers :java-methods])]
        ["java-helper-method" "shared" method "-" "support" "-" "-" "-" "-"
         "GeneratedJavaTests.cs"])
      (for [{:keys [destination kind targets sha256]}
            (get-in accounting [:helpers :adapted-support])]
        ["adapted-helper" (str/join "," (map name targets)) destination "-"
         (name kind) "-" "-" "-" sha256 destination])
      (for [{:keys [destination sha256 license attribution]}
            (:fixtures contract)]
        ["fixture" "shared" destination "-" "fixture" attribution "-" "-"
         (str license ":" sha256) destination])
      [["authored-integrity-test" "shared"
        "GeneratedSuiteIntegrityTests" "-" "enabled" "-" "-" "-" "-"
        "GeneratedSuiteIntegrityTests.cs"]]))))

(defn- render-provenance
  [rows]
  (str
   (str/join "\t"
             ["kind" "target" "identity" "row" "enablement" "reason"
              "revision" "upstream-path" "source" "generated-path"])
   "\n"
   (str/join "\n" (map #(str/join "\t" %) rows))
   "\n"))

(defn- required-package-map
  []
  (into {} (map (juxt :id :version))
        (concat [{:id "Microsoft.NET.Test.Sdk" :version "17.14.1"}
                 {:id "xunit.runner.visualstudio" :version "3.1.4"}]
                (:packages adapters/support-contract))))

(defn- verify-project-contract!
  [project]
  (let [actual (into {} (map (juxt :id :version)) (:packages project))
        required (required-package-map)]
    (doseq [[id version] required]
      (when-not (= version (get actual id))
        (fail! "Generated Java xUnit project omits a pinned support package"
               {:reason :invalid-generated-java-test-project
                :package id :expected version :actual (get actual id)})))
    (when-not (= false (:is-packable adapters/support-contract))
      (fail! "Java test support contract became packable"
             {:reason :packable-java-test-support}))
    project))

(defn generate!
  "Generates one complete adapted xUnit source set into an already-contained
  project directory. The caller owns the project file and clean staging root."
  [{:keys [workspace-root suite-root contract project-root project]}]
  (verify-project-contract! project)
  (let [contract (validate-contract! suite-root contract)
        resolved-model (resolved-model! workspace-root suite-root contract)
        plan (junit/plan-suite resolved-model (adapters/junit-plan-options))
        lowered (junit/lower-suite plan)
        accounting (build-accounting suite-root contract resolved-model plan)
        _ (verify-accounting! (:expected-accounting contract) accounting)
        project-root (paths/absolute project-root)
        generated-java-tests (paths/resolve-path project-root
                                                 "GeneratedJavaTests.cs")
        support (paths/resolve-path project-root "JavaTestSupport.cs")
        integrity (paths/resolve-path project-root
                                      "GeneratedSuiteIntegrityTests.cs")
        fixture-targets (paths/resolve-path project-root
                                            "Directory.Build.targets")
        provenance (paths/resolve-path project-root
                                       "JAVA-TEST-PROVENANCE.tsv")
        provenance-rows (provenance-rows contract accounting)]
    (write-text! generated-java-tests
                 (csharp/present-text
                  (render-test-source contract resolved-model plan)))
    (write-text! support (adapters/support-source))
    (write-text! integrity (render-integrity-source contract))
    (write-text! fixture-targets (render-fixture-targets (:fixtures contract)))
    (doseq [{:keys [path destination]} (:support-sources contract)]
      (copy-file! (contained-file! suite-root "Adapted Java test helper" path)
                  (paths/resolve-path project-root destination)))
    (doseq [{:keys [path destination]} (:fixtures contract)]
      (copy-file! (contained-file! suite-root "Java test fixture" path)
                  (paths/resolve-path project-root destination)))
    (write-text! (paths/resolve-path project-root "SUITE-CONTRACT.edn")
                 (stable-text contract))
    (write-text! provenance (render-provenance provenance-rows))
    (let [generated-files
          (vec
           (sort
            (concat
             ["Directory.Build.targets" "GeneratedJavaTests.cs"
              "GeneratedSuiteIntegrityTests.cs" "JAVA-TEST-PROVENANCE.tsv"
              "JavaTestSupport.cs" "SUITE-CONTRACT.edn"]
             (map :destination (:support-sources contract))
             (map :destination (:fixtures contract)))))
          inventory
          {:schema-version schema-version
           :suite-id (:suite-id contract)
           :generated-namespace (:generated-namespace contract)
           :accounting accounting
           :accounting-digests (accounting-digests accounting)
           :expected-accounting (:expected-accounting contract)
           :lowering
           {:schema-version (:schema-version lowered)
            :case-ids (mapv :case-id (:cases lowered))}
           :provenance-rows (count provenance-rows)
           :provenance-sha256 (util/sha256-file provenance)
           :generated-files generated-files}
          inventory-file (paths/resolve-path project-root
                                             "JAVA-TEST-INVENTORY.edn")]
      (write-text! inventory-file (stable-text inventory))
      {:plan plan
       :lowered lowered
       :accounting accounting
       :inventory inventory
       :inventory-file inventory-file
       :provenance-file provenance
       :generated-files generated-files})))

(defn verify-generated!
  "Verifies a generated/shipped suite using only its repository-local files."
  [project-root]
  (let [project-root (paths/absolute project-root)
        inventory-file (paths/resolve-path project-root
                                           "JAVA-TEST-INVENTORY.edn")]
    (when-not (paths/regular-file? inventory-file)
      (fail! "Generated Java test inventory is missing"
             {:reason :missing-generated-java-test-inventory
              :path (str inventory-file)}))
    (let [inventory (util/read-single-edn-string!
                     (slurp (str inventory-file)))
          provenance (paths/resolve-path project-root
                                         "JAVA-TEST-PROVENANCE.tsv")]
      (when-not (= schema-version (:schema-version inventory))
        (fail! "Generated Java test inventory has an unsupported schema"
               {:reason :unsupported-generated-java-test-inventory
                :actual (:schema-version inventory)}))
      (verify-accounting! (:expected-accounting inventory)
                          (:accounting inventory))
      (when-not (= (:accounting-digests inventory)
                   (accounting-digests (:accounting inventory)))
        (fail! "Generated Java test inventory digests are inconsistent"
               {:reason :generated-java-test-inventory-digest-mismatch}))
      (doseq [relative (:generated-files inventory)]
        (let [file (paths/resolve-path project-root relative)]
          (when-not (paths/regular-file? file)
            (fail! "Generated Java test file is missing"
                   {:reason :missing-generated-java-test-file
                    :path relative}))))
      (when-not (= (:provenance-sha256 inventory)
                   (util/sha256-file provenance))
        (fail! "Generated Java test provenance changed"
               {:reason :generated-java-test-provenance-mismatch
                :path (str provenance)}))
      {:inventory-file inventory-file
       :provenance-file provenance
       :tests (count (get-in inventory [:accounting :tests]))
       :parameter-rows
       (count (get-in inventory [:accounting :parameter-rows]))
       :helpers
       (+ (count (get-in inventory [:accounting :helpers :java-types]))
          (count (get-in inventory [:accounting :helpers :java-methods]))
          (count (get-in inventory [:accounting :helpers :adapted-support])))
       :fixtures (count (get-in inventory [:accounting :fixtures]))})))

(defn strategy!
  "Shared `:adapted-upstream` strategy handler for `test-suites.edn`.

  The strategy must contain `:suite {:source <relative EDN> :sha256 <digest>}`.
  Emission uses Java; verification reads only the generated project tree."
  [{:keys [phase workspace-root target-contract strategy project project-root]}]
  (let [suite (:suite strategy)
        suite-root (:target-directory target-contract)]
    (when-not (and (map? suite) (= #{:source :sha256} (set (keys suite))))
      (fail! "Shared adapted Java strategy has no exact suite declaration"
             {:reason :missing-adapted-java-suite-declaration
              :strategy (:id strategy)}))
    (let [contract-file
          (contained-file! suite-root "Java test-suite declaration"
                           (:source suite))]
      (when-not (= (:sha256 suite) (util/sha256-file contract-file))
        (fail! "Java test-suite declaration checksum changed"
               {:reason :java-test-suite-declaration-checksum-mismatch
                :path (str contract-file)
                :expected (:sha256 suite)
                :actual (util/sha256-file contract-file)}))
      (case phase
        :emit
        (generate! {:workspace-root workspace-root
                    :suite-root suite-root
                    :contract (read-contract! suite-root (:source suite))
                    :project-root project-root
                    :project project})

        :verify
        (verify-generated! project-root)

        (fail! "Adapted Java test strategy received an unsupported phase"
               {:reason :unsupported-test-suite-phase :phase phase})))))
