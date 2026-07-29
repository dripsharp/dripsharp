(ns dripsharp.java-compat-differential
  "Direct JVM-versus-.NET proof for the authored Java compatibility runtime."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.differential :as differential]
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
       (validate-contract! (edn/read-string (slurp (str file))))
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
         (map #(edn/read-string (slurp (str %))))
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

(defn- java-tools
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
                 (str oracle-source)]})
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
