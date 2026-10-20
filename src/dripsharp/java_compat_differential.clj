(ns dripsharp.java-compat-differential
  "Direct JVM-versus-.NET proof for the authored Java compatibility runtime."
  (:refer-clojure :exclude [run!])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.collection-cases :as collection]
            [dripsharp.differential :as differential]
            [dripsharp.differential-cases :as cases]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private contract-schema-version 1)
(def ^:private contract-file "validation/java-compat/contract.edn")
(def ^:private provenance-header
  "compat-type\tjdk-contract\ttargets\tproof-rows")
(def ^:private java-home-candidates
  ["/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
   "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
   "/usr/lib/jvm/java-17-openjdk"
   "/usr/lib/jvm/java-17-openjdk-amd64"])

(defn- fail!
  [contract message data]
  (throw
   (ex-info message
            (assoc data :kind (or (:failure-kind contract)
                                  :java-compat-differential-failed)))))

(defn- exact-keys!
  [contract subject expected value]
  (when-not (= expected (set (keys value)))
    (fail! contract (str subject " has missing or unknown keys")
           {:subject subject
            :expected (vec (sort expected))
            :actual (vec (sort (keys value)))
            :missing (vec (sort (set/difference expected
                                                (set (keys value)))))
            :unknown (vec (sort (set/difference (set (keys value))
                                                expected)))})))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn validate-contract!
  "Validates the exact direct JavaCompat proof contract."
  [contract]
  (exact-keys!
   contract "JavaCompat differential contract"
   #{:schema-version :id :failure-kind :java-release :output-directory
     :provenance :runtime :observation :oracle :probe}
   contract)
  (when-not (and (= contract-schema-version (:schema-version contract))
                 (= :java-compat-direct (:id contract))
                 (keyword? (:failure-kind contract))
                 (pos-int? (:java-release contract))
                 (every? #(non-blank-string? (get contract %))
                         [:output-directory :provenance]))
    (fail! contract "JavaCompat differential identities are invalid"
           {:contract (select-keys contract
                                   [:schema-version :id :failure-kind
                                    :java-release :output-directory
                                    :provenance])}))
  (let [runtime (:runtime contract)]
    (exact-keys! contract "JavaCompat runtime contract"
                 #{:namespace :visibility-symbol :sources} runtime)
    (when-not
     (and (= "DripSharp.Runtime" (:namespace runtime))
          (= "DRIPSHARP_INTERNAL_JAVA_COMPAT"
             (:visibility-symbol runtime))
          (vector? (:sources runtime))
          (seq (:sources runtime))
          (= (:sources runtime) (vec (sort (:sources runtime))))
          (= (count (:sources runtime)) (count (set (:sources runtime))))
          (every? non-blank-string? (:sources runtime)))
      (fail! contract "JavaCompat runtime contract is invalid"
             {:runtime runtime})))
  (doseq [[subject value required string-keys timeout-keys]
          [["JavaCompat oracle contract" (:oracle contract)
            #{:source :main-class :compile-timeout-ms :run-timeout-ms}
            [:source :main-class]
            [:compile-timeout-ms :run-timeout-ms]]
           ["JavaCompat .NET probe contract" (:probe contract)
            #{:project :build-timeout-ms :run-timeout-ms}
            [:project]
            [:build-timeout-ms :run-timeout-ms]]]]
    (exact-keys! contract subject required value)
    (when-not (and (every? #(non-blank-string? (get value %)) string-keys)
                   (every? #(pos-int? (get value %)) timeout-keys))
      (fail! contract (str subject " is invalid") {:contract value})))
  (differential/validate-observation-contract! contract)
  contract)

(defn read-contract
  "Reads and validates the direct JavaCompat differential contract."
  ([]
   (read-contract (paths/workspace-root)))
  ([workspace-root]
   (let [file (paths/resolve-path (paths/absolute workspace-root)
                                  contract-file)]
     (when-not (paths/regular-file? file)
       (fail! {} "JavaCompat differential contract is missing"
              {:path (str file)}))
     (try
       (validate-contract! (util/read-single-edn-string! (slurp (str file))))
       (catch RuntimeException error
         (if (ex-data error)
           (throw error)
           (throw
            (ex-info "JavaCompat differential contract is not valid EDN"
                     {:kind :java-compat-differential-failed
                      :path (str file)}
                     error))))))))

(defn- java-compat-targets
  [workspace-root]
  (let [directory (paths/resolve-path (paths/absolute workspace-root)
                                      "targets")]
    (->> (.listFiles (.toFile directory))
         (filter #(.isDirectory %))
         (map #(.toPath %))
         (map #(paths/resolve-path % "target.edn"))
         (filter paths/regular-file?)
         (map #(util/read-single-edn-string! (slurp (str %))))
         (filter #(contains? (:capabilities %) :java-compat))
         (filter #(some (fn [profile]
                          (contains? (:required-capabilities profile)
                                     :java-compat))
                        (:profiles %)))
         (map (comp name :target))
         sort
         vec)))

(defn read-provenance
  "Reads and validates the exact per-type JavaCompat provenance ledger."
  [contract workspace-root]
  (let [root (paths/absolute workspace-root)
        file (paths/resolve-path root (:provenance contract))
        demanding-targets (java-compat-targets root)]
    (when-not (paths/regular-file? file)
      (fail! contract "JavaCompat provenance file is missing"
             {:path (str file)}))
    (let [[header & lines]
          (Files/readAllLines file StandardCharsets/UTF_8)]
      (when-not (= provenance-header header)
        (fail! contract "JavaCompat provenance has the wrong header"
               {:path (str file)
                :expected provenance-header
                :actual header}))
      (let [rows
            (mapv
             (fn [index line]
               (let [[compat-type jdk-contract targets proof-rows :as fields]
                     (str/split line #"\t" -1)
                     targets (str/split targets #"," -1)
                     proof-rows (str/split proof-rows #"," -1)
                     required-proof (str "type-contract/" compat-type)]
                 (when-not
                  (and
                   (= 4 (count fields))
                   (re-matches #"[A-Za-z_][A-Za-z0-9_]*" compat-type)
                   (re-matches
                    #"[A-Za-z_$][A-Za-z0-9_$]*(?:[.][A-Za-z_$][A-Za-z0-9_$]*)+"
                    jdk-contract)
                   (seq targets)
                   (= targets demanding-targets)
                   (= (count targets) (count (set targets)))
                   (seq proof-rows)
                   (= proof-rows (vec (sort proof-rows)))
                   (= (count proof-rows) (count (set proof-rows)))
                   (every?
                    #(re-matches
                      #"[a-z][a-z0-9-]*/[A-Za-z_][A-Za-z0-9_-]*"
                      %)
                    proof-rows)
                   (some #{required-proof} proof-rows))
                   (fail! contract "JavaCompat provenance row is invalid"
                          {:path (str file)
                           :line (+ index 2)
                           :fields fields
                           :demanding-targets demanding-targets
                           :required-proof required-proof}))
                 {:compat-type compat-type
                  :jdk-contract jdk-contract
                  :targets targets
                  :proof-rows proof-rows}))
             (range)
             lines)
            types (mapv :compat-type rows)
            duplicates
            (->> types
                 frequencies
                 (keep (fn [[type count]] (when (< 1 count) type)))
                 sort
                 vec)]
        (when-not (seq rows)
          (fail! contract "JavaCompat provenance contains no types"
                 {:path (str file)}))
        (when-not (= types (vec (sort types)))
          (fail! contract "JavaCompat provenance types are not sorted"
                 {:path (str file)}))
        (when (seq duplicates)
          (fail! contract "JavaCompat provenance contains duplicate types"
                 {:path (str file) :duplicates duplicates}))
        rows))))

(defn- configured-path
  [root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn- runtime-source-files
  [root]
  (let [directory (paths/resolve-path root "runtime")]
    (->> (.listFiles (.toFile directory))
         (filter #(.isFile %))
         (map #(.getName %))
         (filter #(or (str/starts-with? %
                                        "DripSharp.JavaCompat.")
                      (= "DripSharp.JavaRegexUnicodeData.cs" %)))
         (map #(str "runtime/" %))
         sort
         vec)))

(defn- validate-runtime!
  [contract root]
  (let [expected (get-in contract [:runtime :sources])
        actual (runtime-source-files root)]
    (when-not (= expected actual)
      (fail! contract
             "JavaCompat proof sources differ from the authored runtime"
             {:expected expected :actual actual}))
    (doseq [source expected]
      (when-not (paths/regular-file? (configured-path root source))
        (fail! contract "JavaCompat runtime source is missing"
               {:source source})))
    (let [project (slurp
                   (str (configured-path root
                                         (get-in contract
                                                 [:probe :project]))))
          symbol (get-in contract [:runtime :visibility-symbol])]
      (when-not (and (str/includes? project symbol)
                     (str/includes?
                      project
                      "../../runtime/DripSharp.JavaCompat.*.cs")
                     (str/includes?
                      project
                      "../../runtime/DripSharp.JavaRegexUnicodeData.cs")
                     (not (str/includes? project "<PackageId>"))
                     (not (str/includes? project "<PackageReference")))
        (fail! contract
               "JavaCompat direct probe does not retain internal source-only policy"
               {:project (get-in contract [:probe :project])
                :visibility-symbol symbol})))
    actual))

(defn- java-home-major
  [java-home]
  (let [release (paths/resolve-path java-home "release")]
    (when (paths/regular-file? release)
      (some->> (re-find #"(?m)^JAVA_VERSION=\"(\d+)"
                        (Files/readString release))
               second
               parse-long))))

(defn java-tools
  "Resolves installed Java tools matching the contract's exact Java release."
  [contract]
  (let [requested-major (:java-release contract)
        candidates (->> [(System/getenv "DRIPSHARP_JAVA_HOME")
                         (System/getenv "JAVA_HOME")
                         (System/getProperty "java.home")]
                        (concat java-home-candidates)
                        (remove str/blank?)
                        (map paths/path)
                        (map paths/absolute)
                        distinct)
        home (first (filter #(= requested-major (java-home-major %))
                            candidates))]
    (when-not home
      (fail! contract "No installed JDK matches the JavaCompat oracle runtime"
             {:requested-major requested-major
              :candidates
              (mapv (fn [candidate]
                      {:home (str candidate)
                       :major (java-home-major candidate)})
                    candidates)}))
    (let [java (paths/resolve-path home "bin"
                                   (if (util/windows?) "java.exe" "java"))
          javac (paths/resolve-path home "bin"
                                    (if (util/windows?) "javac.exe" "javac"))]
      (doseq [tool [java javac]]
        (when-not (paths/regular-file? tool)
          (fail! contract "JavaCompat oracle JDK tool is missing"
                 {:tool (str tool)
                  :java-home (str home)})))
      {:home home :java java :javac javac})))

(defn verify!
  "Runs the direct JVM/.NET JavaCompat differential and returns its evidence."
  ([]
   (verify! {}))
  ([{:keys [workspace-root run-command!]
     :or {run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root
                                  (paths/workspace-root)))
         contract (read-contract root)
         provenance (read-provenance contract root)
         demanding-targets (java-compat-targets root)
         runtime-sources (validate-runtime! contract root)
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              (:output-directory contract)))
         classes (doto (paths/resolve-path proof-root "oracle-classes")
                   (Files/createDirectories
                    (make-array FileAttribute 0)))
         oracle-output (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-output (paths/resolve-path proof-root "direct-dotnet.tsv")
         perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
         provenance-file (configured-path root (:provenance contract))
         oracle-source
         (configured-path root (get-in contract [:oracle :source]))
         probe-project
         (configured-path root (get-in contract [:probe :project]))
         {:keys [home java javac]} (java-tools contract)]
     (run-command!
      {:directory root
       :timeout-ms (get-in contract [:oracle :compile-timeout-ms])
       :command [(str javac)
                 "--release" (str (:java-release contract))
                 "-encoding" "UTF-8"
                 "-d" (str classes)
                 (str oracle-source)
                 (str (paths/resolve-path root "validation/microprograms/collections/subjects/micro/CollectionMachine.java"))
                 (str (paths/resolve-path root "validation/microprograms/charset/subjects/micro/CharsetMachine.java"))
                 (str (paths/resolve-path root "validation/microprograms/floating/subjects/micro/FloatingCases.java"))]})
     (run-command!
      {:directory root
       :timeout-ms (get-in contract [:oracle :run-timeout-ms])
       :command [(str java)
                 "-cp" (str classes)
                 (get-in contract [:oracle :main-class])
                 (str oracle-output)
                 (str provenance-file)]})
     (run-command!
      {:directory root
       :timeout-ms (get-in contract [:probe :build-timeout-ms])
       :command ["dotnet" "build" (str probe-project)
                 "--nologo" "--configuration" "Release"
                 "--verbosity:minimal" "--no-incremental"
                 "-warnaserror"]})
     (run-command!
      {:directory root
       :timeout-ms (get-in contract [:probe :run-timeout-ms])
       :command ["dotnet" "run" "--project" (str probe-project)
                 "--configuration" "Release"
                 "--no-build" "--no-restore" "--"
                 (str dotnet-output)
                 (str provenance-file)]})
     (let [comparison
           (differential/assert-observation-match!
            contract "Direct JavaCompat .NET behavior"
            oracle-output dotnet-output)
           perturbation
           (differential/prove-observation-perturbation!
            contract oracle-output perturbed-output)
           trace
           (differential/observation-trace-summary
            contract oracle-output)
           summary
           {:contract {:schema-version (:schema-version contract)
                       :id (:id contract)
                       :observation-schema-version
                       (get-in contract
                               [:observation :schema-version])
                       :observation-header
                       (get-in contract [:observation :header])}
            :java {:release (:java-release contract)
                   :home (str home)}
            :runtime {:namespace (get-in contract
                                         [:runtime :namespace])
                      :visibility :internal
                      :sources (count runtime-sources)
                      :types (count provenance)
                      :targets demanding-targets}
            :trace trace
            :comparison comparison
            :perturbation-line
            (get-in perturbation [:mismatch :line])
            :host (util/current-host)}]
       (util/write-text! (paths/resolve-path proof-root "summary.edn")
                         (str (pr-str summary) "\n"))
       (println "Direct JVM/.NET JavaCompat differential passed:"
                (pr-str
                 (-> (select-keys summary
                                  [:contract :runtime :trace :host])
                     (update :trace select-keys
                             [:schema-version :header
                              :observations :families]))))
       (assoc summary :proof-root proof-root)))))

(def case-families
  "Local-only concrete operation contracts. Registration launches no processes."
  {"floating" {"run" {:arguments [#{"u"} #{"u"} #{"u"}] :exceptions #{"none" "illegal-argument"}}}
   "collections" {"run" {:arguments [#{"i"} #{"u"}] :exceptions #{"none"}}}
   "charset" {"decode" {:arguments [#{"u"} #{"i"} #{"u"}]
                          :exceptions #{"none"}}
              "stream" {:arguments [#{"u"} #{"u"} #{"u"}]
                          :exceptions #{"none"}}
              "string-ops" {:arguments [#{"u"} #{"i"} #{"i"}]
                              :exceptions #{"none"}}}
   "byte-buffer"
   (merge
    {"wrap" {:arguments [#{"b" "n"}] :exceptions #{"none" "null-pointer"}}
     "select" {:arguments [#{"u"}] :exceptions #{"none" "null-pointer" "illegal-argument"}}
     "order" {:arguments [#{"u"}] :exceptions #{"none" "null-pointer"}}
     "set-position" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "illegal-argument"}}
     "set-limit" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "illegal-argument"}}
     "get-at" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "index-out-of-bounds"}}
     "get-short-at" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "index-out-of-bounds"}}
     "get-int-at" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "index-out-of-bounds"}}
     "get-long-at" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "index-out-of-bounds"}}
     "put" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "buffer-overflow" "read-only"}}
     "put-short" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "buffer-overflow" "read-only"}}
     "put-int" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "buffer-overflow" "read-only"}}
     "put-long" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "buffer-overflow" "read-only"}}}
    (into {} (for [operation ["capacity" "position" "limit" "remaining" "is-read-only"
                              "mark" "clear" "flip" "rewind" "duplicate" "slice" "read-only"]]
               [operation {:arguments [] :exceptions #{"none" "null-pointer"}}]))
    {"reset" {:arguments [] :exceptions #{"none" "null-pointer" "invalid-mark"}}}
    (into {} (for [operation ["get" "get-short" "get-int" "get-long"]]
               [operation {:arguments [] :exceptions #{"none" "null-pointer" "buffer-underflow"}}]))
    (into {} (for [operation ["put-at" "put-short-at" "put-int-at" "put-long-at"]]
               [operation {:arguments [#{"i"} #{"i"}]
                           :exceptions #{"none" "null-pointer" "index-out-of-bounds" "read-only"}}])))
   "binary-data"
   (merge
    {"open" {:arguments [#{"b"}] :exceptions #{"none"}}
     "read-fully" {:arguments [#{"b"} #{"i"} #{"i"}]
                   :exceptions #{"none" "null-pointer" "index-out-of-bounds" "eof"}}
     "write-buffer" {:arguments [#{"b"} #{"i"} #{"i"}]
                     :exceptions #{"none" "null-pointer" "index-out-of-bounds" "io"}}
     "flush" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}}
    (into {} (for [operation ["read-byte" "read-unsigned-byte" "read-short"
                              "read-unsigned-short" "read-int" "read-long"]]
               [operation {:arguments [] :exceptions #{"none" "null-pointer" "eof"}}]))
    (into {} (for [operation ["write-byte" "write-short" "write-int" "write-long"]]
               [operation {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}])))
   "compression"
   {"roundtrip" {:arguments [#{"b"} #{"i"} #{"i"} #{"i"} #{"u"}]
                 :exceptions #{"none" "illegal-argument" "io"}}
    "decode" {:arguments [#{"b"} #{"i"} #{"u"}]
              :exceptions #{"none" "illegal-argument" "io"}}
    "inflate" {:arguments [#{"b"} #{"i"} #{"i"} #{"u"}]
               :exceptions #{"none" "illegal-argument"}}}
   "string" {"hash" {:arguments [#{"u" "n"}]
                     :exceptions #{"none" "null-pointer"}}}
   "long" {"hash" {:arguments [#{"i"}] :exceptions #{"none"}}}
   "numeric" (into {} (for [operation ["int-div" "int-rem" "long-div" "long-rem"]]
                        [operation {:arguments [#{"i"} #{"i"}]
                                    :exceptions #{"none" "arithmetic"}}]))
   "byte-array-input"
   {"open" {:arguments [#{"b"} #{"i"} #{"i"}]
            :exceptions #{"none" "null-pointer" "index-out-of-bounds"}}
    "read-one" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "read-buffer" {:arguments [#{"b"} #{"i"} #{"i"}]
                   :exceptions #{"none" "null-pointer" "index-out-of-bounds" "io"}}
    "skip" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}
    "available" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "mark" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer"}}
    "reset" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "mark-supported" {:arguments [] :exceptions #{"none" "null-pointer"}}
    "close" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}}
   "filter-input"
   {"open" {:arguments [#{"b"} #{"i"} #{"i"}]
            :exceptions #{"none" "null-pointer" "index-out-of-bounds"}}
    "read-one" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "read-buffer" {:arguments [#{"b"} #{"i"} #{"i"}]
                   :exceptions #{"none" "null-pointer" "index-out-of-bounds" "io"}}
    "skip" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}
    "available" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "mark" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer"}}
    "reset" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "mark-supported" {:arguments [] :exceptions #{"none" "null-pointer"}}
    "close" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}}
   "byte-array-output"
   {"open" {:arguments [#{"i"}] :exceptions #{"none" "index-out-of-bounds"}}
    "write-one" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}
    "write-buffer" {:arguments [#{"b"} #{"i"} #{"i"}]
                    :exceptions #{"none" "null-pointer" "index-out-of-bounds" "io"}}
    "size" {:arguments [] :exceptions #{"none" "null-pointer"}}
    "reset" {:arguments [] :exceptions #{"none" "null-pointer"}}
    "close" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}}
   "filter-output"
   {"open" {:arguments [#{"i"}] :exceptions #{"none" "index-out-of-bounds"}}
    "write-one" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}
    "write-buffer" {:arguments [#{"b"} #{"i"} #{"i"}]
                    :exceptions #{"none" "null-pointer" "index-out-of-bounds" "io"}}
    "flush" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "close" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}}
   "random-access"
   {"open" {:arguments [#{"b"} #{"u"}]
            :exceptions #{"none" "null-pointer" "illegal-argument" "file-not-found" "io"}}
    "read-one" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "read-buffer" {:arguments [#{"b"} #{"i"} #{"i"}]
                   :exceptions #{"none" "null-pointer" "index-out-of-bounds" "io"}}
    "read-fully" {:arguments [#{"b"}]
                  :exceptions #{"none" "null-pointer" "eof" "io"}}
    "write-buffer" {:arguments [#{"b"}] :exceptions #{"none" "null-pointer" "io"}}
    "seek" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}
    "skip" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}
    "length" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "position" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}
    "set-length" {:arguments [#{"i"}] :exceptions #{"none" "null-pointer" "io"}}
    "close" {:arguments [] :exceptions #{"none" "null-pointer" "io"}}}
   "file" {"inspect" {:arguments [#{"u" "n"}]
                      :exceptions #{"none" "null-pointer"}}
           "absolute" {:arguments [#{"u" "n"}]
                       :exceptions #{"none" "null-pointer"}}
           "parent-child" {:arguments [#{"u" "n"} #{"u" "n"}]
                           :exceptions #{"none" "null-pointer"}}
           "file-parent-child" {:arguments [#{"u" "n"} #{"u" "n"}]
                                :exceptions #{"none" "null-pointer"}}
           "uri" {:arguments [#{"u" "n"}]
                  :exceptions #{"none" "null-pointer" "illegal-argument"}}
           "equals" {:arguments [#{"u" "n"} #{"u" "n"}]
                     :exceptions #{"none" "null-pointer"}}
           "to-path" {:arguments [#{"u" "n"}]
                      :exceptions #{"none" "null-pointer" "invalid-path"}}
           "create" {:arguments [#{"u" "n"}]
                     :exceptions #{"none" "null-pointer" "io"}}
           "delete" {:arguments [#{"u" "n"}]
                     :exceptions #{"none" "null-pointer"}}
           "open" {:arguments [#{"u" "n"}]
                   :exceptions #{"none" "null-pointer" "file-not-found"}}
           "list-count" {:arguments [#{"u" "n"}]
                         :exceptions #{"none" "null-pointer"}}}})

(defn select-cases
  "Validates all input operations before selecting complete cases. Unknown
  families/operations and out-of-domain integers are input errors, never skips."
  [text {:keys [family case-id]}]
  (let [rows (cases/read-cases text)]
    (when (> (count rows) 10000)
      (throw (ex-info "Case batch exceeds 10000 operations" {:kind :invalid-case})))
    (doseq [{:keys [family operation arguments expected-exception] :as row} rows]
      (let [spec (get-in case-families [family operation])]
        (when-not (and spec (= (count arguments) (count (:arguments spec)))
                       (every? true? (map (fn [types value]
                                            (contains? types (subs value 0 1)))
                                          (:arguments spec) arguments))
                       (contains? (:exceptions spec) expected-exception)
                       (or (not= family "collections") (collection/valid-arguments? arguments))
                       (or (not (contains? #{"long" "numeric"} family))
                           (every? #(<= Long/MIN_VALUE (bigint (subs % 2)) Long/MAX_VALUE)
                                   arguments)))
          (throw (ex-info "Unknown operation or invalid operation contract"
                          {:kind :invalid-case :row row})))))
    (let [selected (filterv #(and (or (nil? family) (= family (:family %)))
                                  (or (nil? case-id) (= case-id (:case %)))) rows)]
      (when (empty? selected)
        (throw (ex-info "No cases match selection" {:kind :invalid-case
                                                    :family family :case case-id})))
      selected)))

(defn- case-command! [run-command! phase command]
  (let [execution (cases/process-outcome run-command! phase command)]
    (when-not (= :ok (:status execution))
      (throw (ex-info "Concrete-case process failed"
                      {:kind :case-process-failure :execution execution})))
    (get-in execution [:process :output])))

(defn- shell-quote [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn prepare-case-runner!
  "Builds one fixed runner pair for a local session. The returned executor
  reuses only these binaries, never a previous session's build. Every execution
  writes its own input and observations. Callers may wrap run-command! to enforce
  a cumulative process/deadline budget (including preparation)."
  [{:keys [root contract build-root versions run-command!]}]
  (let [{:keys [java javac home]} (java-tools contract)
        project (configured-path root (get-in contract [:probe :project]))
        framework (second (re-find #"<TargetFramework>([^<]+)</TargetFramework>"
                                   (slurp (str project))))
        _ (when-not (= "net10.0" framework)
            (throw (ex-info "Concrete probe must target configured net10.0 contract"
                            {:kind :runtime-contract :framework framework})))
        command! (fn [phase timeout command]
                   (case-command! run-command! phase
                                  {:directory root :timeout-ms timeout
                                   :command (mapv str command)}))
        classes (paths/resolve-path build-root "classes")
        binaries (paths/resolve-path build-root "dotnet")
        _ (Files/createDirectories classes (make-array FileAttribute 0))
        _ (swap! versions assoc :java-contract (:java-release contract)
                 :java-home (str home) :dotnet-contract framework)
        _ (swap! versions assoc :javac
                 (str/trim (process/without-java-tool-options-banner
                            (command! :java-version 10000 [javac "-version"])))
                 :dotnet-sdk
                 (str/trim (command! :dotnet-version 10000 ["dotnet" "--version"])))
        _ (command! :java-compile (get-in contract [:oracle :compile-timeout-ms])
                    [javac "--release" (:java-release contract) "-encoding" "UTF-8"
                     "-d" classes (configured-path root (get-in contract [:oracle :source]))
                     (paths/resolve-path root "validation/microprograms/collections/subjects/micro/CollectionMachine.java")
                     (paths/resolve-path root "validation/microprograms/charset/subjects/micro/CharsetMachine.java")
                     (paths/resolve-path root "validation/microprograms/floating/subjects/micro/FloatingCases.java")])
        _ (command! :dotnet-build (get-in contract [:probe :build-timeout-ms])
                    ["dotnet" "build" project "--nologo" "--configuration" "Release"
                     "--verbosity:minimal" "-warnaserror" "--output" binaries])
        java-command [java "-cp" classes (get-in contract [:oracle :main-class])]
        dotnet-command ["dotnet" "--roll-forward" "LatestPatch"
                        (paths/resolve-path binaries "DripSharp.JavaCompat.DirectProbe.dll")]
        java-version (str/trim (process/without-java-tool-options-banner
                                (command! :java-runtime 10000
                                          (conj java-command "--runtime-version"))))
        dotnet-version (str/trim (command! :dotnet-runtime 10000
                                           (conj dotnet-command "--runtime-version")))
        _ (swap! versions assoc :java-runtime java-version :dotnet-runtime dotnet-version)
        _ (when-not (and (str/starts-with? java-version (str (:java-release contract) "."))
                         (str/starts-with? dotnet-version ".NET 10."))
            (throw (ex-info "Executed runtime differs from configured contract"
                            {:kind :runtime-contract :versions @versions})))]
    {:versions @versions
     :execute!
     (fn [rows proof-root]
       (let [input (paths/resolve-path proof-root "input.tsv")
             _ (util/write-text! input (cases/write-cases rows))
             execute (fn [side command timeout]
                       (let [output (paths/resolve-path proof-root (if (= side :oracle) "java.tsv" "dotnet.tsv"))
                             execution (cases/process-outcome
                                        run-command! side
                                        {:directory root :timeout-ms timeout
                                         :command (mapv str (into command ["--cases" input output]))})]
                         (assoc execution :observations
                                (if (paths/regular-file? output) (slurp (str output)) ""))))
             oracle (execute :oracle java-command (get-in contract [:oracle :run-timeout-ms]))
             probe (execute :probe dotnet-command (get-in contract [:probe :run-timeout-ms]))]
         (assoc (cases/compare-executions rows oracle probe)
                :executions {:oracle (dissoc oracle :observations)
                             :probe (dissoc probe :observations)})))}))

(defn verify-cases!
  "Explicit local entry point. Compiles each fixed runner once for the entire
  selected batch; both runners then consume the same saved input file.
  Returns and saves non-success evidence for build/start/exit/timeout failures."
  ([] (verify-cases! {}))
  ([{:keys [workspace-root input-file output-directory run-command!]
     :or {input-file "validation/java-compat/cases.tsv"
          output-directory "validation-output/java-compat-cases"
          run-command! process/run!}
     :as options}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         contract (read-contract root)
         source (configured-path root input-file)
         _ (when (> (Files/size source) (* 1024 1024))
             (throw (ex-info "Case input exceeds 1 MiB" {:kind :invalid-case})))
         rows (select-cases (slurp (str source)) options)
         parent (configured-path root output-directory)
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         proof-root (Files/createTempDirectory parent "run-" (make-array FileAttribute 0))
         input (paths/resolve-path proof-root "input.tsv")
         versions (atom {})
         evidence {:input (str input)
                   :input-kind :recorded
                   :operations (count rows)
                   :cases (count (set (map (juxt :family :case) rows)))
                   :families (vec (sort (set (map :family rows))))
                   :type-availability :not-run
                   :host (util/current-host)
                   :replay (str "DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -m dripsharp.java-compat-differential --cases "
                                (shell-quote input))}
         _ (util/write-text! input (cases/write-cases rows))
         result
         (try
           (let [runner (prepare-case-runner!
                         {:root root :contract contract :build-root proof-root
                          :versions versions :run-command! run-command!})]
             ((:execute! runner) rows proof-root))
           (catch clojure.lang.ExceptionInfo error
             {:status :failed :first-difference (ex-data error)}))
         summary (merge evidence result {:versions @versions})]
     (util/write-text! (paths/resolve-path proof-root "summary.edn")
                       (str (pr-str summary) "\n"))
     summary)))

(defn -main [& arguments]
  (try
    (let [options
          (loop [remaining arguments options {}]
            (if (empty? remaining)
              options
              (let [[flag value & more] remaining
                    key (get {"--cases" :input-file "--family" :family
                              "--case" :case-id "--output" :output-directory} flag)]
                (when-not (and key value (not (contains? options key)))
                  (throw (ex-info
                          "Usage: --cases FILE [--family NAME] [--case ID] [--output DIRECTORY]"
                          {:kind :invalid-arguments})))
                (recur more (assoc options key value)))))
          summary (verify-cases! options)]
      (prn summary)
      (shutdown-agents)
      (System/exit (if (= :passed (:status summary)) 0 1)))
    (catch Exception error
      (prn {:status :failed :message (.getMessage error) :error (ex-data error)})
      (shutdown-agents)
      (System/exit 2))))
