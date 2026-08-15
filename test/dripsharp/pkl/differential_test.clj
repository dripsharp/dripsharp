(ns dripsharp.pkl.differential-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.package-provenance :as provenance]
            [dripsharp.pkl.differential :as differential]
            [dripsharp.paths :as paths]
            [dripsharp.target-execution :as target-execution])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]
           [java.util.zip GZIPOutputStream]))

(deftest parser-and-core-differentials-use-target-owned-package-execution
  (let [root (paths/workspace-root)
        calls (atom [])
        supplied-options (atom nil)
        stop-kind ::package-profile-selected
        package-fn
        (fn [{:keys [profile target-contract]}]
          (swap! calls conj
                 {:profile profile
                  :target (:target target-contract)
                  :available-profiles
                  (set (map :id (get-in target-contract
                                        [:manifest :profiles])))})
          (throw (ex-info "Focused package selection completed"
                          {:kind stop-kind :profile profile})))
        invoke
        (fn [stage verify-fn expected-profile options]
          (let [error
                (try
                  (verify-fn options)
                  nil
                  (catch clojure.lang.ExceptionInfo value value))]
            {:stage stage
             :kind (:kind (ex-data error))
             :profile (:profile (ex-data error))
             :expected-profile expected-profile}))]
    (with-redefs-fn
      {#'differential/verify-differential!
       (fn [options]
         (reset! supplied-options options)
         [(invoke :parser #'differential/verify-parser-differential!
                  "pkl-parser" options)
          (invoke :core #'differential/verify-core-differential!
                  "pkl-core-value-model" options)])}
      #(is (= [[{:stage :parser
                 :kind stop-kind
                 :profile "pkl-parser"
                 :expected-profile "pkl-parser"}
                {:stage :core
                 :kind stop-kind
                 :profile "pkl-core-value-model"
                 :expected-profile "pkl-core-value-model"}]]
              (target-execution/differential!
               {:workspace-root root
                :target :pkl
                :validation :pkl-differential
                :package-fn package-fn}))))
    (is (fn? (:package-fn @supplied-options)))
    (is (not (contains? @supplied-options :core-package-fn)))
    (is (= [{:profile "pkl-parser"
             :target :pkl
             :available-profiles #{"pkl-parser" "pkl-core-value-model"}}
            {:profile "pkl-core-value-model"
             :target :pkl
             :available-profiles #{"pkl-parser" "pkl-core-value-model"}}]
           @calls))))

(deftest independent-probes-overlap-and-retain-command-context
  (let [threads (atom #{})
        results
        (concurrency/call-with-executor
         {:worker-count 2 :thread-prefix "differential-test"}
         #(#'differential/run-independent-probes!
           (fn [{:keys [command]}]
             (swap! threads conj (.getName (Thread/currentThread)))
             (Thread/sleep 30)
             {:command command :exit 0 :output (first command)})
           [{:name :java :command ["java" "oracle"] :directory "."}
            {:name :dotnet :command ["dotnet" "probe"] :directory "."}]))]
    (is (= [:java :dotnet] (mapv :probe results)))
    (is (= [["java" "oracle"] ["dotnet" "probe"]] (mapv :command results)))
    (is (= 2 (count @threads)))))

(deftest schema-contract-evidence-is-source-backed-and-retains-product-scope
  (let [root (paths/workspace-root)
        evidence (paths/resolve-path root "validation" "schema-codegen"
                                     "ContractEvidence.tsv")
        summary (#'differential/verify-contract-evidence! root evidence)]
    (is (= 20 (:selected summary)))
    (is (zero? (:pending-in-scope summary)))
    (is (some #{"schema.collections-aliases-generics-functions"} (:families summary)))
    (is (some #{"codegen.polymorphism-overrides"} (:families summary)))
    (is (some #{"binding.complete-conversion-matrix"} (:families summary)))
    (is (some #{"schema.methods-generic-classes"} (:families summary)))
    (is (some #{"schema.amends-recursive-aliases"} (:families summary)))))

(deftest schema-generator-read-only-set-contract-is-focused-and-fail-closed
  (let [root (paths/workspace-root)
        runtime (Files/readString
                 (paths/resolve-path root "targets" "pkl" "runtime"
                                     "DripSharp.Brine.DotNet.cs"))
        probe (Files/readString
               (paths/resolve-path root "validation" "schema-codegen"
                                   "SchemaGeneratorProbe.cs"))
        consumer (Files/readString
                  (paths/resolve-path root "validation" "schema-codegen"
                                      "GeneratedConsumer.cs"))
        oracle (Files/readString
                (paths/resolve-path root "validation" "schema-codegen"
                                    "SchemaUpstreamOracle.java"))
        expected (Files/createTempFile "generated-set-contract-expected" ".tsv"
                                       (make-array FileAttribute 0))
        actual (Files/createTempFile "generated-set-contract-actual" ".tsv"
                                     (make-array FileAttribute 0))
        read-only-contract
        "contract.main#Service.names(property=System.Collections.Generic.IReadOnlySet<System.String>;constructor=System.Collections.Generic.IReadOnlySet<System.String>)"
        mutable-contract
        "contract.main#Service.names(property=System.Collections.Generic.ISet<System.String>;constructor=System.Collections.Generic.ISet<System.String>)"
        row (fn [value]
              (str "generated/set-contract\tGENERATED_SET_CONTRACT\t"
                   (#'differential/b64 value) "\n"))]
    (testing "the packed generator emits read-only Set APIs and read-only set semantics"
      (doseq [[source required]
              [[runtime
                "\"pkl.base#Set\" => $\"global::System.Collections.Generic.IReadOnlySet<"]
               [runtime
                "definition == typeof(global::System.Collections.Generic.IReadOnlySet<>)"]
               [probe
                "public global::System.Collections.Generic.IReadOnlySet<string> Names { get; }"]
               [probe
                "generated Set property or constructor regressed to ISet<string>"]
               [consumer "sealed class ReadOnlySetOnly<T> : IReadOnlySet<T>"]
               [consumer "generated models treat IReadOnlySet values as order-independent sets"]
               [oracle "generated/set-contract"]]]
        (is (str/includes? source required) required)))
    (testing "the focused oracle row detects an IReadOnlySet-to-ISet recurrence"
      (Files/writeString expected (row read-only-contract) (make-array OpenOption 0))
      (Files/writeString actual (row read-only-contract) (make-array OpenOption 0))
      (is (= 1 (:matched (#'differential/assert-equal!
                          "generated set contract" expected actual))))
      (Files/writeString actual (row mutable-contract) (make-array OpenOption 0))
      (let [error (try
                    (#'differential/assert-equal!
                     "generated set contract" expected actual)
                    nil
                    (catch clojure.lang.ExceptionInfo value value))]
        (is (= :differential-validation-failed (:kind (ex-data error))))
        (is (= 1 (get-in (ex-data error) [:mismatch :line])))))))

(deftest loading-contract-is-source-backed-executable-and-retains-pending-scope
  (let [root (paths/workspace-root)
        fixtures (paths/resolve-path root "validation" "loading-contract")
        contract (#'differential/verify-loading-contract-evidence!
                  root
                  (paths/resolve-path fixtures "ContractEvidence.tsv")
                  (paths/resolve-path fixtures "ContractExpectations.tsv"))
        summary (:summary contract)]
    (is (= 73 (:families summary)))
    (is (= 73 (:existing-evidence summary)))
    (is (zero? (:pending-in-scope summary)))
    (is (= 64 (:jvm-shared-families summary)))
    (is (= 9 (:dotnet-adaptation-families summary)))
    (is (= 30 (:jvm-shared-observations summary)))
    (is (= 8 (:dotnet-adaptation-observations summary)))
    (is (some #(= "package.cache-offline" (:family %)) (:evidence contract)))
    (is (some #(= "adaptation.assembly-modules" (:family %)) (:evidence contract)))
    (is (some #(= "evaluator.timeout-cancellation" (:family %)) (:evidence contract)))
    (is (some #(= "collections.map-entry-set" (:family %)) (:evidence contract)))
    (is (every? #(#{"existing-evidence" "pending-in-scope"}
                  (:implementation %))
                (:evidence contract)))))

(deftest loading-probe-test-doubles-track-current-public-contract
  (let [root (paths/workspace-root)
        probe (Files/readString
               (paths/resolve-path root "validation" "loading-contract"
                                   "LoadingContractDotNetProbe.cs"))
        loading (Files/readString
                 (paths/resolve-path root "targets" "pkl" "runtime"
                                     "DripSharp.Brine.Loading.cs"))]
    (testing "the target-owned public contract retains the extension surface"
      (doseq [required ["public Uri Uri { get; }"
                        "public bool Cached { get; }"
                        "public bool Local { get; }"
                        "public string? FileCachePath { get; }"
                        "public ModuleKey Original { get; }"
                        "public string Source { get; }"
                        "public abstract bool HasHierarchicalUris();"
                        "public abstract bool IsGlobbable();"]]
        (is (.contains loading required) required)))
    (testing "the package-only loading doubles implement required and inherited members"
      (doseq [[required occurrences]
              [["public override ModuleKey? Create(Uri uri)" 3]
               ["public override string GetUriScheme()" 2]
               ["public override bool HasHierarchicalUris()" 2]
               ["public override bool IsGlobbable()" 2]
               ["public override object? Read(Uri uri)" 2]
               ["public override void Close()" 5]
               ["public ModuleKey Original => GetOriginal();" 2]
               ["public Uri Uri => GetUri();" 4]
               ["public bool Cached => IsCached();" 2]
               ["public bool Local => IsLocal();" 2]
               ["public string? FileCachePath => GetFileCacheLocation();" 2]
               ["public string Source => LoadSource();" 2]
               ["public bool IsCached() => true;" 2]
               ["public bool IsLocal() => false;" 2]
               ["public string? GetFileCacheLocation() => null;" 2]
               ["public bool HasElement(SecurityManager securityManager, Uri elementUri)" 2]
               ["public IReadOnlyList<PathElement> ListElements(" 2]
               ["public bool HasFragmentPaths() => false;" 2]
               ["public Uri ResolveUri(Uri value)" 2]
               ["public Uri ResolveUri(Uri baseUri, Uri value)" 2]]]
        (is (= occurrences
               (count (re-seq (re-pattern (java.util.regex.Pattern/quote required))
                              probe)))
            required)))))

(deftest core-package-probe-value-adapters-track-generated-contract
  (let [root (paths/workspace-root)
        stale-root (Files/createTempDirectory "stale-core-package-probe"
                                              (make-array FileAttribute 0))
        relative-paths
        [["products" "brine" "src" "DripSharp.Brine" "src" "DripSharp" "Brine"
          "ValueVisitor.cs"]
         ["products" "brine" "src" "DripSharp.Brine" "src" "DripSharp" "Brine"
          "ValueConverter.cs"]
         ["targets" "pkl" "validation" "probe" "CorePackageProbe.cs"]]
        probe-path (apply paths/resolve-path stale-root (last relative-paths))]
    (doseq [relative relative-paths]
      (let [source (apply paths/resolve-path root relative)
            target (apply paths/resolve-path stale-root relative)]
        (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
        (Files/writeString target (Files/readString source) (make-array OpenOption 0))))
    (is (= {:visitor-members 20 :converter-members 19}
           (#'differential/verify-core-package-probe-adapters! stale-root)))
    (letfn [(thrown-kind [f]
              (try
                (f)
                nil
                (catch clojure.lang.ExceptionInfo error
                  (:kind (ex-data error)))))]
      (let [valid-probe (Files/readString probe-path)]
        (Files/writeString
         probe-path
         (str/replace-first valid-probe
                            "public void VisitDefault(object? value)"
                            "public void VisitDefault(object value)")
         (make-array OpenOption 0))
        (is (= :differential-validation-failed
               (thrown-kind
                #(#'differential/verify-core-package-probe-adapters! stale-root))))
        (Files/writeString
         probe-path
         (str/replace-first valid-probe
                            "public void Visit(object value)"
                            "public void VisitUnexpected(object value)")
         (make-array OpenOption 0))
        (is (= :differential-validation-failed
               (thrown-kind
                #(#'differential/verify-core-package-probe-adapters! stale-root))))
        (Files/writeString
         probe-path
         (str/replace-first valid-probe
                            "public string Convert(object value)"
                            "public string ConvertUnexpected(object value)")
         (make-array OpenOption 0))
        (is (= :differential-validation-failed
               (thrown-kind
                #(#'differential/verify-core-package-probe-adapters! stale-root))))))))

(deftest core-package-probe-isolates-the-member-modifiers-facade
  (let [root (paths/workspace-root)
        runtime (Files/readString
                 (paths/resolve-path root "targets" "pkl" "runtime"
                                     "DripSharp.Brine.ValueModel.DotNet.cs"))
        probe (Files/readString
               (paths/resolve-path root "targets" "pkl" "validation" "probe"
                                   "CorePackageProbe.cs"))
        oracle (Files/readString
                (paths/resolve-path root "targets" "pkl" "validation" "oracle"
                                    "CoreUpstreamOracle.java"))
        expected (Files/createTempFile "idiomatic-facade-expected" ".tsv"
                                       (make-array FileAttribute 0))
        actual (Files/createTempFile "idiomatic-facade-actual" ".tsv"
                                     (make-array FileAttribute 0))
        row (fn [id value]
              (str id "\tDOTNET\t" (#'differential/b64 value) "\n"))
        focused-row (row "@member-modifiers-facade"
                         "declared=true|iset=false|mutation-rejected=true")
        composite-row (row "@idiomatic-data-api"
                           "bytes=true|facades=true|nullable=true")]
    (testing "the netstandard facade is declared and implemented as read-only"
      (is (str/includes? runtime
                         "internal static IReadOnlyCollection<T> ReadOnly<T>(ISet<T> values)"))
      (is (str/includes? runtime
                         "private sealed class ReadOnlySet<T> : IReadOnlyCollection<T>"))
      (is (str/includes? runtime
                         "public IReadOnlyCollection<Modifier> Modifiers =>"))
      (is (not (str/includes? runtime "public ISet<Modifier> Modifiers =>"))))
    (testing "the focused observation names declaration, runtime shape, and mutation behavior"
      (doseq [required ["@member-modifiers-facade"
                        "typeof(IReadOnlyCollection<Modifier>)"
                        "bool isSet = modifiers is ISet<Modifier>;"
                        "bool mutationRejected = RejectsMutation"
                        "declared=true|iset=false|mutation-rejected=true"]]
        (is (or (str/includes? probe required)
                (str/includes? oracle required))
            required)))
    (testing "the complete composite retains byte and nullable facade coverage"
      (is (str/includes? probe
                         "bytes={Lower(bytes)}|facades={Lower(facades)}|nullable={Lower(nullable)}"))
      (is (str/includes? oracle "bytes=true|facades=true|nullable=true")))
    (testing "a focused facade recurrence fails closed even when the composite stays green"
      (Files/writeString expected (str focused-row composite-row)
                         (make-array OpenOption 0))
      (Files/writeString actual (str focused-row composite-row)
                         (make-array OpenOption 0))
      (is (= 2 (:matched (#'differential/assert-equal!
                          "focused facade regression" expected actual))))
      (Files/writeString
       actual
       (str (row "@member-modifiers-facade"
                 "declared=false|iset=true|mutation-rejected=true")
            composite-row)
       (make-array OpenOption 0))
      (let [error (try
                    (#'differential/assert-equal!
                     "focused facade regression" expected actual)
                    nil
                    (catch clojure.lang.ExceptionInfo value value))]
        (is (= :differential-validation-failed (:kind (ex-data error))))
        (is (= 1 (get-in (ex-data error) [:mismatch :line])))))))

(deftest packed-assembly-manifest-pins-exact-runtime-hashes
  (let [output (Files/createTempFile "dripsharp-packed-assemblies" ".tsv"
                                     (make-array FileAttribute 0))
        hash-a (apply str (repeat 64 "a"))
        hash-b (apply str (repeat 64 "b"))
        packages [{:resource-proof
                   {:assembly-identity {:name "DripSharp.Brine.Parser"}
                    :assembly-artifact {:sha256 hash-a}}}
                  {:resource-proof
                   {:assembly-identity {:name "DripSharp.Brine"}
                    :assembly-artifact {:sha256 hash-b}}}]
        proof (provenance/write-packed-assembly-manifest! output packages)]
    (is (= [{:name "DripSharp.Brine" :sha256 hash-b}
            {:name "DripSharp.Brine.Parser" :sha256 hash-a}]
           (:assemblies proof)))
    (is (= (str "DripSharp.Brine\t" hash-b "\nDripSharp.Brine.Parser\t" hash-a "\n")
           (Files/readString output)))))

(deftest to-fixed-contract-pins-binary-rounding-and-the-complete-digit-range
  (let [float-cases (var-get #'differential/float-fraction-digit-cases)
        integer-cases (var-get #'differential/integer-fraction-digit-cases)
        cases (var-get #'differential/to-fixed-cases)
        by-id (into {} (map (juxt :id identity) cases))]
    (is (= 68 (count cases)))
    (is (= (set (range 21)) (set (map :digits float-cases))))
    (is (= (set (range 21)) (set (map :digits integer-cases))))
    (is (= "2.67" (get-in by-id ["to-fixed/decimal-shortest-below" :expected])))
    (is (= "2.68" (get-in by-id ["to-fixed/decimal-above" :expected])))
    (is (= "2.62" (get-in by-id ["to-fixed/binary-exact-half-even" :expected])))
    (is (= "-0.00000000000000000000"
           (get-in by-id ["to-fixed/negative-zero" :expected])))
    (is (= "NaN" (get-in by-id ["to-fixed/not-a-number" :expected])))
    (is (= 309 (count (get-in by-id ["to-fixed/maximum-positive-double"
                                     :expected]))))
    (is (= "-9223372036854775808.00000000000000000000"
           (get-in by-id ["to-fixed/minimum-integer" :expected])))))

(deftest regex-compat-contract-inventories-java-pattern-and-matcher-behavior
  (let [cases (var-get #'differential/regex-compat-cases)
        ids (mapv first cases)
        operations (set (map second cases))
        flags (set (map #(nth % 2) cases))]
    (is (= 116 (count cases)))
    (is (= (count ids) (count (set ids))))
    (is (= #{"PATTERN" "QUOTE_PATTERN" "QUOTE_REPLACEMENT" "MATCHES"
             "LOOKING_AT" "FIND" "REGION" "SPLIT" "REPLACE_ALL"
             "REPLACE_FIRST" "APPEND"}
           operations))
    (is (every? flags [0 1 2 4 8 9 16 32 66 128 256 511 512]))
    (doseq [family ["regex/quote/direct-qe"
                    "regex/flags/canonical-equivalence"
                    "regex/class/intersection"
                    "regex/class/quoted"
                    "regex/property/script"
                    "regex/property/script-iso-alias"
                    "regex/property/binary-emoji"
                    "regex/escape/unicode-name-hangul"
                    "regex/grapheme/cluster"
                    "regex/group/numeric-order"
                    "regex/quantifier/possessive"
                    "regex/matcher/zero-width-astral"
                    "regex/split/captures-not-returned"
                    "regex/replace/missing-group"]]
      (is (some #{family} ids)))))

(defn- encoded-unicode-source
  [text marker]
  (let [bytes
        (with-open [output (ByteArrayOutputStream.)
                    gzip (GZIPOutputStream. output)]
          (.write gzip (.getBytes text StandardCharsets/UTF_8))
          (.finish gzip)
          (.toByteArray output))]
    (aset-byte bytes 4 (byte marker))
    (str "internal static readonly string GzipBase64 = "
         "string.Concat(new string[]\n{\n\""
         (.encodeToString (Base64/getEncoder) bytes)
         "\",\n});\n")))

(deftest regex-unicode-audit-compares-decompressed-data
  (let [data "V\t25.0.2+10-LTS\nB\tbasiclatin\t0-7f\n"
        first-source
        (Files/createTempFile "dripsharp-regex-unicode-first" ".cs"
                              (make-array FileAttribute 0))
        second-source
        (Files/createTempFile "dripsharp-regex-unicode-second" ".cs"
                              (make-array FileAttribute 0))]
    (Files/writeString first-source (encoded-unicode-source data 0)
                       (make-array OpenOption 0))
    (Files/writeString second-source (encoded-unicode-source data 1)
                       (make-array OpenOption 0))
    (is (not= (Files/readString first-source)
              (Files/readString second-source)))
    (is (= data (#'differential/read-regex-unicode-source! first-source)))
    (is (= data (#'differential/read-regex-unicode-source! second-source)))))

(deftest quantified-astral-regex-contract-pins-captures-and-every-replacement-mode
  (let [cases (var-get #'differential/astral-regex-capture-cases)
        ids (mapv first cases)
        operations (set (map second cases))]
    (is (= 10 (count cases)))
    (is (= (count ids) (count (set ids))))
    (is (= 4 (count (filter #(= "FIND" (second %)) cases))))
    (is (= #{"FIND" "REPLACE_ALL" "REPLACE_FIRST" "REPLACE_LAST"
             "REPLACE_ALL_MAPPED" "REPLACE_FIRST_MAPPED" "REPLACE_LAST_MAPPED"}
           operations))
    (doseq [family ["regex/astral-capture/literal-plus"
                    "regex/astral-capture/codepoint-plus"
                    "regex/astral-capture/name-plus"
                    "regex/astral-capture/singleton-class-plus"
                    "regex/astral-replace/all"
                    "regex/astral-replace/first"
                    "regex/astral-replace/last"
                    "regex/astral-replace/all-mapped"
                    "regex/astral-replace/first-mapped"
                    "regex/astral-replace/last-mapped"]]
      (is (some #{family} ids)))))
