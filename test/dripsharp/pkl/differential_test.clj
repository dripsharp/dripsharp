(ns dripsharp.pkl.differential-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.package-provenance :as provenance]
            [dripsharp.pkl.differential :as differential]
            [dripsharp.paths :as paths])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]
           [java.util.zip GZIPOutputStream]))

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
