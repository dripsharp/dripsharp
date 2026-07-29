(ns dripsharp.differential
  "Product-neutral execution and comparison of versioned differential proofs."
  (:refer-clojure :exclude [run!])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.io BufferedReader File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption
            StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def observation-header
  "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1")

(def ^:private contract-schema-version 1)
(def ^:private observation-schema-version 1)
(def ^:private observation-columns [:family :id :value])
(def ^:private runner-summary-keys
  #{:canonical-comparison :consumer :contract :host :package
    :package-comparison :perturbation-line :profile :source
    :supported-hosts :trace})

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- failure-kind [contract]
  (or (:failure-kind contract) :differential-failed))

(defn- fail!
  ([contract message data]
   (throw (ex-info message (assoc data :kind (failure-kind contract)))))
  ([message data]
   (throw (ex-info message (assoc data :kind :differential-failed)))))

(defn- exact-keys! [contract subject expected value]
  (when-not (= expected (set (keys value)))
    (fail! contract (str subject " has missing or unknown keys")
           {:subject subject
            :expected (vec (sort expected))
            :actual (vec (sort (keys value)))
            :missing (vec (sort (set/difference expected (set (keys value)))))
            :unknown (vec (sort (set/difference (set (keys value)) expected)))})))

(defn validate-observation-contract!
  "Validates the reusable versioned observation-stream portion of a proof
  contract. Focused proofs that do not package a translated target can use
  this contract without inventing package-runner metadata."
  [contract]
  (let [observation (:observation contract)]
    (when-not (map? observation)
      (fail! contract "Observation contract is missing or invalid"
             {:observation observation}))
    (exact-keys!
     contract "Observation contract"
     #{:schema-version :header :columns :required-families :expected-count}
     observation)
    (when-not
     (and (= observation-schema-version (:schema-version observation))
          (= observation-header (:header observation))
          (= observation-columns (:columns observation))
          (vector? (:required-families observation))
          (seq (:required-families observation))
          (= (:required-families observation)
             (vec (sort (:required-families observation))))
          (= (count (:required-families observation))
             (count (set (:required-families observation))))
          (every? non-blank-string? (:required-families observation))
          (pos-int? (:expected-count observation)))
      (fail! contract "Observation contract is invalid"
             {:observation observation
              :required-header observation-header
              :required-columns observation-columns}))
    contract))

(defn validate-contract!
  "Validates the exact schema for one data-driven differential contract."
  [contract]
  (exact-keys!
   contract "Differential contract"
   #{:schema-version :id :target :baseline-profile :failure-kind
     :observation :runner :package-contract :summary}
   contract)
  (let [{:keys [schema-version id target baseline-profile failure-kind
                observation runner package-contract summary]}
        contract]
    (when-not (= contract-schema-version schema-version)
      (fail! contract "Differential contract has an unsupported schema version"
             {:expected contract-schema-version :actual schema-version}))
    (when-not (and (keyword? id) (keyword? target) (keyword? baseline-profile)
                   (keyword? failure-kind))
      (fail! contract "Differential contract identities are invalid"
             {:id id :target target :baseline-profile baseline-profile
              :failure-kind failure-kind}))
    (validate-observation-contract! contract)
    (exact-keys!
     contract "Differential runner contract"
     #{:profile :output-directory :context :required-files
       :required-directories :canonical :oracle :probe :supported-hosts}
     runner)
    (when-not
     (and (every? #(non-blank-string? (get runner %))
                  [:profile :output-directory])
          (map? (:context runner))
          (every? keyword? (keys (:context runner)))
          (every? non-blank-string? (vals (:context runner)))
          (vector? (:required-files runner))
          (= (count (:required-files runner))
             (count (set (:required-files runner))))
          (every? keyword? (:required-files runner))
          (vector? (:required-directories runner))
          (= (count (:required-directories runner))
             (count (set (:required-directories runner))))
          (every? keyword? (:required-directories runner))
          (empty? (set/intersection (set (:required-files runner))
                                    (set (:required-directories runner))))
          (or (nil? (:canonical runner)) (keyword? (:canonical runner)))
          (vector? (:supported-hosts runner))
          (seq (:supported-hosts runner)))
      (fail! contract "Differential runner contract is invalid"
             {:runner runner}))
    (doseq [[subject spec required timeout-keys required-output]
            [["Java oracle contract" (:oracle runner)
              #{:source :main-class :arguments :include-resource-roots?
                :compile-timeout-ms :run-timeout-ms}
              [:compile-timeout-ms :run-timeout-ms]
              :oracle]
             ["Package probe contract" (:probe runner)
              #{:source :arguments :build-timeout-ms :run-timeout-ms}
              [:build-timeout-ms :run-timeout-ms]
              :packaged]]]
      (exact-keys! contract subject required spec)
      (when-not
       (and (non-blank-string? (:source spec))
            (vector? (:arguments spec))
            (every? keyword? (:arguments spec))
            (contains? (set (:arguments spec)) required-output)
            (every? pos-int? (map spec timeout-keys)))
        (fail! contract (str subject " is invalid") {:contract spec})))
    (when-not (and (boolean? (get-in runner [:oracle
                                             :include-resource-roots?]))
                   (non-blank-string? (get-in runner [:oracle :main-class])))
      (fail! contract "Java oracle contract is invalid"
             {:oracle (:oracle runner)}))
    (doseq [host (:supported-hosts runner)]
      (exact-keys! contract "Supported host contract"
                   #{:os :architecture :runner} host)
      (when-not (every? #(non-blank-string? (get host %))
                        [:os :architecture :runner])
        (fail! contract "Supported host contract is invalid" {:host host})))
    (when-not
     (= (count (:supported-hosts runner))
        (count (set (map (juxt :os :architecture)
                         (:supported-hosts runner)))))
      (fail! contract "Supported host identities are duplicated"
             {:supported-hosts (:supported-hosts runner)}))
    (when-let [canonical (:canonical runner)]
      (when-not (and (contains? (:context runner) canonical)
                     (contains? (set (:required-files runner)) canonical))
        (fail! contract
               "Canonical trace must be a required configured file"
               {:canonical canonical
                :context (vec (sort (keys (:context runner))))
                :required-files (:required-files runner)})))
    (exact-keys!
     contract "Expected package contract"
     #{:target-framework :assembly-name :assembly-dependencies
       :dependencies :legal-sets :resource-count :clean-builds}
     package-contract)
    (when-not
     (and (every? #(non-blank-string? (get package-contract %))
                  [:target-framework :assembly-name])
          (vector? (:assembly-dependencies package-contract))
          (every? non-blank-string? (:assembly-dependencies package-contract))
          (vector? (:dependencies package-contract))
          (= (count (:dependencies package-contract))
             (count (set (map :id (:dependencies package-contract)))))
          (every?
           (fn [dependency]
             (and (= #{:id :version} (set (keys dependency)))
                  (non-blank-string? (:id dependency))
                  (or (non-blank-string? (:version dependency))
                      (and (map? (:version dependency))
                           (= #{:baseline-package}
                              (set (keys (:version dependency))))
                           (non-blank-string?
                            (get-in dependency
                                    [:version :baseline-package]))))))
           (:dependencies package-contract))
          (vector? (:legal-sets package-contract))
          (every? keyword? (:legal-sets package-contract))
          (and (integer? (:resource-count package-contract))
               (not (neg? (:resource-count package-contract))))
          (pos-int? (:clean-builds package-contract)))
      (fail! contract "Expected package contract is invalid"
             {:package-contract package-contract}))
    (when-not (map? summary)
      (fail! contract "Differential summary data must be a map"
             {:summary summary}))
    (when-let [reserved (seq (set/intersection runner-summary-keys
                                               (set (keys summary))))]
      (fail! contract "Target summary data overrides runner-owned evidence"
             {:reserved (vec (sort reserved))}))
    contract))

(defn read-contract
  "Reads and validates one versioned differential contract."
  ([contract-file]
   (read-contract (paths/workspace-root) contract-file))
  ([workspace-root contract-file]
   (let [root (paths/absolute workspace-root)
         file (let [candidate (paths/path contract-file)]
                (if (.isAbsolute candidate)
                  candidate
                  (paths/resolve-path root candidate)))]
     (when-not (paths/regular-file? file)
       (fail! "Differential contract file is missing"
              {:path (str file)}))
     (try
       (validate-contract! (util/read-single-edn-string! (slurp (str file))))
       (catch RuntimeException error
         (if (ex-data error)
           (throw error)
           (throw
            (ex-info "Differential contract file is not valid EDN"
                     {:kind :invalid-differential-contract
                      :path (str file)}
                     error))))))))

(defn compare-results
  "Compares normalized line-oriented observations without loading large trees
  in memory."
  [expected actual]
  (with-open [^BufferedReader left
              (Files/newBufferedReader
               (paths/path expected) StandardCharsets/UTF_8)
              ^BufferedReader right
              (Files/newBufferedReader
               (paths/path actual) StandardCharsets/UTF_8)]
    (loop [line-number 1 matched 0]
      (let [expected-line (.readLine left)
            actual-line (.readLine right)]
        (cond
          (and (nil? expected-line) (nil? actual-line))
          {:matched matched}

          (= expected-line actual-line)
          (recur (inc line-number) (inc matched))

          :else
          {:matched matched
           :mismatch {:line line-number
                      :expected expected-line
                      :actual actual-line}})))))

(defn observation-trace-summary
  "Validates one versioned family/id/value observation stream using the
  reusable observation portion of a proof contract."
  [contract trace]
  (let [contract (validate-observation-contract! contract)
        trace (paths/path trace)
        [header & lines]
        (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        observation (:observation contract)]
    (when-not (= (:header observation) header)
      (fail! contract "Differential trace has the wrong observation header"
             {:trace (str trace)
              :expected (:header observation)
              :actual header}))
    (let [rows
          (mapv
           (fn [index line]
             (let [fields (str/split line #"\t" -1)]
               (when-not (and (= (count observation-columns) (count fields))
                              (every? (complement str/blank?) fields))
                 (fail! contract
                        "Differential trace contains a malformed observation"
                        {:trace (str trace)
                         :line (+ 2 index)
                         :value line}))
               (zipmap observation-columns fields)))
           (range)
           lines)
          identities (mapv (juxt :family :id) rows)
          duplicates
          (->> identities
               frequencies
               (keep (fn [[identity count]]
                       (when (< 1 count) identity)))
               sort
               vec)
          families (set (map :family rows))
          required (set (:required-families observation))
          missing (vec (sort (set/difference required families)))]
      (when-not (seq rows)
        (fail! contract "Differential trace contains no observations"
               {:trace (str trace)}))
      (when (seq duplicates)
        (fail! contract
               "Differential trace contains duplicate observation identities"
               {:trace (str trace) :duplicates duplicates}))
      (when (seq missing)
        (fail! contract
               "Differential trace misses required behavior families"
               {:trace (str trace)
                :missing missing
                :families (vec (sort families))}))
      (when-not (= (:expected-count observation) (count rows))
        (fail! contract "Differential trace has the wrong observation count"
               {:trace (str trace)
                :expected (:expected-count observation)
                :actual (count rows)}))
      {:schema-version (:schema-version observation)
       :header header
       :observations (count rows)
       :families (vec (sort families))
       :identities identities})))

(defn trace-summary
  "Validates one versioned family/id/value observation stream from a complete
  package differential contract."
  [contract trace]
  (validate-contract! contract)
  (observation-trace-summary contract trace))

(defn assert-observation-match!
  "Requires two independently produced versioned traces to have identical
  normalized observations and coverage without requiring package metadata."
  [contract subject expected actual]
  (let [expected-summary (observation-trace-summary contract expected)
        actual-summary (observation-trace-summary contract actual)
        comparison (compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! contract
             (str subject " differs from the pinned upstream oracle")
             {:expected (str expected)
              :actual (str actual)
              :comparison comparison
              :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! contract (str subject " trace coverage differs from the oracle")
             {:expected expected-summary :actual actual-summary}))
    comparison))

(defn assert-match!
  "Requires two independently produced package-differential traces to have
  identical normalized observations and coverage."
  [contract subject expected actual]
  (validate-contract! contract)
  (assert-observation-match! contract subject expected actual))

(defn prove-observation-perturbation!
  "Appends a valid extra observation and requires the reusable comparator to
  reject it."
  [contract ^Path oracle ^Path perturbed]
  (validate-observation-contract! contract)
  (Files/copy oracle perturbed
              (into-array StandardCopyOption
                          [StandardCopyOption/REPLACE_EXISTING]))
  (Files/writeString perturbed
                     "failure\tperturbed-comparator\tvalue\n"
                     (into-array OpenOption [StandardOpenOption/APPEND]))
  (let [comparison (compare-results oracle perturbed)]
    (when-not (:mismatch comparison)
      (fail! contract
             "Differential comparator missed a deliberate perturbation"
             {:oracle (str oracle) :perturbed (str perturbed)}))
    comparison))

(defn prove-perturbation!
  "Appends a valid extra package observation and requires the comparator to
  reject it."
  [contract ^Path oracle ^Path perturbed]
  (validate-contract! contract)
  (prove-observation-perturbation! contract oracle perturbed))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn- java-tools [contract ^Path root generation]
  (let [toolchain (get-in generation [:project-input :java-toolchain])
        home (configured-path root (:home toolchain))
        suffix (if (str/starts-with?
                    (str/lower-case (System/getProperty "os.name" ""))
                    "windows")
                 ".exe"
                 "")
        result
        {:release (:release toolchain)
         :java (paths/resolve-path home "bin" (str "java" suffix))
         :javac (paths/resolve-path home "bin" (str "javac" suffix))}]
    (doseq [tool [(:java result) (:javac result)]]
      (when-not (paths/regular-file? tool)
        (fail! contract "Pinned Java oracle toolchain is missing"
               {:tool (str tool) :release (:release result)})))
    result))

(defn- argument-paths [contract context arguments]
  (mapv
   (fn [argument]
     (or (get context argument)
         (fail! contract "Differential command references unknown context"
                {:argument argument
                 :available (vec (sort (keys context)))})))
   arguments))

(defn compile-and-run-oracle!
  "Compiles a contract-selected JVM oracle against the live pinned project
  input and runs it with contract-selected paths."
  [contract run-command! ^Path root generation ^Path proof-root context]
  (let [{:keys [release java javac]} (java-tools contract root generation)
        project-input (:project-input generation)
        oracle (get-in contract [:runner :oracle])
        sources
        (mapv #(configured-path root %)
              (concat (:production-sources project-input)
                      (:generated-production-sources project-input)))
        dependencies
        (->> (:classpath-artifacts project-input)
             (map #(configured-path root (:path %)))
             distinct
             vec)
        resource-roots
        (when (:include-resource-roots? oracle)
          (mapv #(configured-path root %) (:resource-roots project-input)))
        oracle-source (configured-path root (:source oracle))
        classes
        (doto (paths/resolve-path proof-root "oracle-classes")
          (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str dependencies))
        run-classpath
        (str/join File/pathSeparator
                  (map str (into [classes]
                                 (concat dependencies resource-roots))))
        compile-command
        (cond-> [(str javac) "--release" (str release) "-encoding" "UTF-8"]
          (seq dependencies) (into ["-classpath" compile-classpath])
          true (into ["-d" (str classes)])
          true (into (map str sources))
          true (conj (str oracle-source)))
        arguments (argument-paths contract context (:arguments oracle))]
    (when-not (paths/regular-file? oracle-source)
      (fail! contract "Differential Java oracle source is missing"
             {:source (str oracle-source)}))
    (run-command! {:command compile-command
                   :directory root
                   :timeout-ms (:compile-timeout-ms oracle)})
    (run-command! {:command
                   (into [(str java) "-classpath" run-classpath
                          (:main-class oracle)]
                         (map str arguments))
                   :directory root
                   :timeout-ms (:run-timeout-ms oracle)})))

(defn run-package-probe!
  "Builds and executes the contract-selected C# probe only inside the isolated
  package consumer created by the packaging proof."
  [contract run-command! ^Path root package-proof context]
  (let [probe (get-in contract [:runner :probe])
        generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project
        (paths/resolve-path consumer-root (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe-source (configured-path root (:source probe))
        arguments (argument-paths contract context (:arguments probe))]
    (when-not (paths/regular-file? probe-source)
      (fail! contract "Differential package probe source is missing"
             {:source (str probe-source)}))
    (Files/copy probe-source source
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str project)
                             "--nologo" "--verbosity:minimal"
                             "--no-restore" "--no-incremental" "-warnaserror"]
                   :directory consumer-root
                   :timeout-ms (:build-timeout-ms probe)})
    (run-command! {:command
                   (into ["dotnet" "run" "--project" (str project)
                          "--no-build" "--no-restore" "--"]
                         (map str arguments))
                   :directory consumer-root
                   :timeout-ms (:run-timeout-ms probe)})))

(defn- dependency-version [workspace-root target value]
  (if (string? value)
    value
    (:version
     (baseline/package workspace-root target (:baseline-package value)))))

(defn- expected-package-contract [contract workspace-root]
  (let [target (:target contract)
        profile
        (baseline/profile workspace-root target (:baseline-profile contract))
        upstream (baseline/upstream workspace-root target)
        package-id (:package-id profile)
        package-record (baseline/package workspace-root target package-id)
        expected (:package-contract contract)]
    {:project-id (:source-project-id profile)
     :revision (:revision upstream)
     :production-sources (get-in profile [:source-counts :ordinary])
     :generated-production-sources (get-in profile [:source-counts :generated])
     :clean-builds (:clean-builds expected)
     :package-id package-id
     :version (:version package-record)
     :target-framework (:target-framework expected)
     :assembly
     {:name (:assembly-name expected)
      :version (:assembly-version package-record)
      :dependency-assemblies (:assembly-dependencies expected)}
     :dependencies
     (mapv
      (fn [{:keys [id version]}]
        {:id id :version (dependency-version workspace-root target version)})
      (:dependencies expected))
     :resource-count (:resource-count expected)
     :package-files
     (mapv
      (fn [{:keys [kind package-path sha256]}]
        {:kind kind :path package-path :sha256 sha256})
      (baseline/legal-files workspace-root target (:legal-sets expected)))
     :public-contract
     {:strategy :complete-accessible-java-library
      :required-rows (:public-contract-rows profile)
      :compiled-contract-members (:public-contract-rows profile)
      :public-stubs 0}}))

(defn- actual-package-contract [contract package-proof]
  (let [generation (get-in package-proof [:verification :generation])
        project-input (:project-input generation)
        destination (:destination generation)
        identity (:identity package-proof)
        inspection (:inspection package-proof)
        primary (first (filter :primary? (:packages package-proof)))
        resource-proof (:resource-proof primary)
        compiled-surface (get-in package-proof [:verification :public-surface])
        public-metadata (get-in generation [:emission :public-metadata])
        assembly-name (get-in contract [:package-contract :assembly-name])
        compiled-assembly
        (first (filter #(= assembly-name (:assembly %))
                       (:assemblies compiled-surface)))
        public-stubs
        (count
         (filter #(= :public-stub
                     (get-in % [:generated :implementation]))
                 (:rows public-metadata)))]
    {:project-id (:project-id project-input)
     :revision (get-in generation [:source-project :revision])
     :production-sources (count (:production-sources project-input))
     :generated-production-sources
     (count (:generated-production-sources project-input))
     :clean-builds (get-in package-proof [:packing-summary :clean-builds])
     :package-id (:id identity)
     :version (:version identity)
     :target-framework (get-in destination [:project :target-framework])
     :assembly (:assembly-identity resource-proof)
     :dependencies (:dependencies inspection)
     :resource-count (:resources resource-proof)
     :package-files (:package-files inspection)
     :public-contract
     {:strategy (:strategy compiled-surface)
      :required-rows (:required-rows public-metadata)
      :compiled-contract-members (:contract-members compiled-assembly)
      :public-stubs public-stubs}}))

(defn validate-package-contract!
  "Validates the packed package against the authoritative target baseline and
  the versioned differential package contract."
  [contract workspace-root package-proof]
  (let [expected (expected-package-contract contract workspace-root)
        actual (actual-package-contract contract package-proof)]
    (when-not (= expected actual)
      (fail! contract "Packed package identity or target contract is incorrect"
             {:expected expected :actual actual}))
    actual))

(defn- resolve-context [contract ^Path root ^Path proof-root]
  (reduce-kv
   (fn [context key value]
     (assoc context key (configured-path root value)))
   {:oracle (paths/resolve-path proof-root "upstream-java.tsv")
    :packaged (paths/resolve-path proof-root "package-dotnet.tsv")
    :perturbed (paths/resolve-path proof-root "perturbed.tsv")}
   (get-in contract [:runner :context])))

(defn- validate-context! [contract context]
  (doseq [key (get-in contract [:runner :required-files])
          :let [value (get context key)]]
    (when-not (and value (paths/regular-file? value))
      (fail! contract "Required differential file is missing"
             {:context key :path (some-> value str)})))
  (doseq [key (get-in contract [:runner :required-directories])
          :let [value (get context key)]]
    (when-not (and value (paths/directory? value))
      (fail! contract "Required differential directory is missing"
             {:context key :path (some-> value str)})))
  context)

(defn- primary-package-summary [package-proof package-contract]
  (let [primary (first (filter :primary? (:packages package-proof)))]
    (merge
     package-contract
     {:sha256 (get-in package-proof [:identity :sha256])
      :assembly (get-in primary [:resource-proof :assembly-identity])
      :public-surface (:public-surface primary)
      :resources (:resources primary)
      :external-packages (:external-packages package-proof)})))

(defn- validate-summary-extension! [contract extension]
  (when-not (map? extension)
    (fail! contract "Differential summary extension must return a map"
           {:actual extension}))
  (when-let [reserved
             (seq (set/intersection runner-summary-keys
                                    (set (keys extension))))]
    (fail! contract "Differential summary extension overrides runner evidence"
           {:reserved (vec (sort reserved))}))
  extension)

(defn run!
  "Runs the common JVM-oracle/package-only-.NET differential ladder.

  `prepare-context` is the bounded extension seam for unusual fixture setup.
  It returns additional keyword-to-path entries. `summary-extension` returns
  durable target-specific evidence to merge into the summary."
  [{:keys [contract workspace-root package-fn run-command!
           prepare-context summary-extension]
    :or {package-fn packaging/verify-package-consumption!
         run-command! process/run!
         prepare-context (constantly {})
         summary-extension (constantly {})}}]
  (let [contract (validate-contract! contract)
        root (paths/absolute (or workspace-root (paths/workspace-root)))
        profile (get-in contract [:runner :profile])
        package-proof
        (package-fn {:workspace-root root
                     :profile profile
                     :run-command! run-command!})
        package-contract
        (validate-package-contract! contract root package-proof)
        generation (get-in package-proof [:verification :generation])
        proof-root
        (harness/clean-directory!
         (paths/resolve-path root "validation-output"
                             (get-in contract [:runner :output-directory])))
        base-context (resolve-context contract root proof-root)
        extension-context
        (prepare-context {:contract contract
                          :workspace-root root
                          :proof-root proof-root
                          :package-proof package-proof
                          :context base-context})
        context (validate-context! contract (merge base-context extension-context))
        canonical-key (get-in contract [:runner :canonical])
        canonical (get context canonical-key)
        _ (compile-and-run-oracle!
           contract run-command! root generation proof-root context)
        canonical-comparison
        (when canonical
          (assert-match! contract "Live upstream Java behavior"
                         canonical (:oracle context)))
        _ (run-package-probe!
           contract run-command! root package-proof context)
        package-comparison
        (assert-match! contract "Package-only .NET behavior"
                       (:oracle context) (:packaged context))
        perturbation
        (prove-perturbation! contract (:oracle context) (:perturbed context))
        trace (trace-summary contract (:oracle context))
        source (baseline/upstream root (:target contract))
        extension-summary
        (validate-summary-extension!
         contract
         (summary-extension
          {:contract contract
           :workspace-root root
           :proof-root proof-root
           :package-proof package-proof
           :context context
           :summary trace}))
        summary
        (merge
         {:contract {:schema-version (:schema-version contract)
                     :id (:id contract)
                     :observation-schema-version
                     (get-in contract [:observation :schema-version])
                     :observation-header
                     (get-in contract [:observation :header])}
          :profile profile
          :source (select-keys source [:version :revision])
          :package (primary-package-summary package-proof package-contract)
          :consumer (:dependency-proof package-proof)
          :trace trace
          :package-comparison package-comparison
          :perturbation-line (get-in perturbation [:mismatch :line])
          :host (util/current-host)
          :supported-hosts (get-in contract [:runner :supported-hosts])}
         (when canonical-comparison
           {:canonical-comparison canonical-comparison})
         (:summary contract)
         extension-summary)]
    (util/write-text! (paths/resolve-path proof-root "summary.edn")
                      (str (pr-str summary) "\n"))
    (println "Pinned JVM/package-only .NET differential passed:"
             (pr-str (select-keys summary
                                  [:contract :source :trace :host])))
    (assoc summary :proof-root proof-root)))
