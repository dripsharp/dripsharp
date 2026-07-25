(ns dripsharp.rawhttp-package
  "Independent RawHTTP Java-oracle and package-only .NET equivalence gate."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.dotnet-surface :as dotnet-surface]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files Path]
           [java.security MessageDigest]
           [java.util Base64]))

(def ^:private observation-header "DRIPSHARP_RAWHTTP_OBSERVATIONS_V1")
(def ^:private provenance-header "DRIPSHARP_RAWHTTP_PACKAGE_PROVENANCE_V1")
(def ^:private observation-count 28)
(def ^:private success-count 16)
(def ^:private failure-count 12)
(def ^:private command-timeout-ms (* 30 60 1000))
(def ^:private profile-file "config/rawhttp-core.edn")

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :rawhttp-package-equivalence-failed))))

(defn- bounded-run! [options]
  (process/run! (assoc options :timeout-ms (or (:timeout-ms options)
                                               command-timeout-ms))))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- sha256 [^Path file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (Files/readAllBytes file))
    (hex (.digest digest))))

(defn- decode-base64! [value context]
  (try
    (let [bytes (.decode (Base64/getDecoder) ^String value)
          decoded (String. bytes StandardCharsets/UTF_8)]
      (when-not (= value (.encodeToString (Base64/getEncoder) bytes))
        (fail! "RawHTTP observation uses non-canonical base64"
               {:reason :noncanonical-base64 :context context}))
      decoded)
    (catch IllegalArgumentException error
      (fail! "RawHTTP observation contains invalid base64"
             {:reason :invalid-base64 :context context :cause (.getMessage error)}))))

(defn parse-observations!
  "Parses a complete normalized observation stream and rejects malformed,
  duplicate, or unstable row identities."
  [text]
  (let [[header & raw-rows] (str/split-lines text)
        raw-rows (vec (remove str/blank? raw-rows))]
    (when-not (= observation-header header)
      (fail! "RawHTTP observation stream has the wrong header"
             {:reason :observation-header :expected observation-header :actual header}))
    (when-not (seq raw-rows)
      (fail! "RawHTTP observation stream is empty" {:reason :empty-observations}))
    (let [rows
          (mapv
           (fn [line]
             (let [[id status payload extra] (str/split line #"\t" -1)]
               (when-not (and (not (str/blank? id))
                              (contains? #{"SUCCESS" "FAILURE"} status)
                              (not (str/blank? payload))
                              (nil? extra))
                 (fail! "RawHTTP observation row is malformed"
                        {:reason :malformed-observation :line line}))
               {:id id :status status :payload (decode-base64! payload id)
                :encoded-payload payload :line line}))
           raw-rows)
          ids (mapv :id rows)
          duplicates (->> ids frequencies
                          (keep (fn [[id count]] (when (> count 1) id)))
                          sort vec)]
      (when (seq duplicates)
        (fail! "RawHTTP observation identities are duplicated"
               {:reason :duplicate-observations :duplicates duplicates}))
      (when-not (= ids (vec (sort ids)))
        (fail! "RawHTTP observations are not in stable identity order"
               {:reason :unstable-observation-order :identities ids}))
      rows)))

(defn- render-observations [rows]
  (str observation-header "\n" (str/join "\n" (map :line rows)) "\n"))

(defn compare-observations!
  "Requires exact ordered row identity, result status, and normalized payload."
  [expected-text actual-text]
  (let [expected (parse-observations! expected-text)
        actual (parse-observations! actual-text)]
    (when-not (= expected actual)
      (let [expected-by-id (into {} (map (juxt :id identity)) expected)
            actual-by-id (into {} (map (juxt :id identity)) actual)
            expected-ids (set (keys expected-by-id))
            actual-ids (set (keys actual-by-id))
            changed (->> (set/intersection expected-ids actual-ids)
                         (filter #(not= (get expected-by-id %) (get actual-by-id %)))
                         sort vec)]
        (fail! "RawHTTP Java and .NET observations differ"
               {:reason :observation-mismatch
                :missing (vec (sort (set/difference expected-ids actual-ids)))
                :unexpected (vec (sort (set/difference actual-ids expected-ids)))
                :changed changed
                :expected-count (count expected) :actual-count (count actual)})))
    {:matched (count expected)}))

(defn- validate-observation-family! [rows]
  (let [counts (frequencies (map :status rows))]
    (when-not (and (= observation-count (count rows))
                   (= success-count (get counts "SUCCESS" 0))
                   (= failure-count (get counts "FAILURE" 0)))
      (fail! "RawHTTP observation family is incomplete"
             {:reason :incomplete-observation-family
              :expected {:rows observation-count :success success-count
                         :failure failure-count}
              :actual {:rows (count rows) :success (get counts "SUCCESS" 0)
                       :failure (get counts "FAILURE" 0)}}))
    rows))

(defn- extract-probe-output! [output]
  (let [lines (vec (str/split-lines output))
        observation-indexes (keep-indexed #(when (= observation-header %2) %1) lines)
        provenance-indexes (keep-indexed #(when (= provenance-header %2) %1) lines)]
    (when-not (and (= 1 (count observation-indexes))
                   (= 1 (count provenance-indexes))
                   (< (first observation-indexes) (first provenance-indexes)))
      (fail! "RawHTTP package probe did not emit one ordered observation/provenance stream"
             {:reason :probe-envelope :output output}))
    (let [observation-index (first observation-indexes)
          provenance-index (first provenance-indexes)
          observation-lines (subvec lines observation-index provenance-index)
          provenance-lines (subvec lines (inc provenance-index))
          provenance-line (first provenance-lines)]
      (when-not provenance-line
        (fail! "RawHTTP package probe omitted assembly provenance"
               {:reason :missing-assembly-provenance}))
      {:observations (str (str/join "\n" observation-lines) "\n")
       :provenance-line provenance-line})))

(defn- parse-provenance! [line]
  (let [[record name version hash location extra] (str/split line #"\t" -1)]
    (when-not (and (= "assembly" record) (nil? extra)
                   (every? #(not (str/blank? %)) [name version hash location]))
      (fail! "RawHTTP package provenance row is malformed"
             {:reason :malformed-assembly-provenance :line line}))
    (let [result {:name (decode-base64! name :assembly-name)
                  :version (decode-base64! version :assembly-version)
                  :sha256 (decode-base64! hash :assembly-sha256)
                  :location (decode-base64! location :assembly-location)}]
      (when-not (re-matches #"[0-9a-f]{64}" (:sha256 result))
        (fail! "RawHTTP package provenance hash is malformed"
               {:reason :malformed-assembly-hash :actual (:sha256 result)}))
      result)))

(defn validate-provenance!
  "Requires the consumer to load the exact packed assembly from the isolated
  NuGet cache."
  [expected actual]
  (let [actual-path (.toRealPath (paths/path (:location actual))
                                 (make-array java.nio.file.LinkOption 0))
        expected-path (when-let [location (:location expected)]
                        (.toRealPath (paths/path location)
                                     (make-array java.nio.file.LinkOption 0)))
        expected-root (when-let [location-root (:location-root expected)]
                        (.toRealPath (paths/path location-root)
                                     (make-array java.nio.file.LinkOption 0)))
        actual-hash (sha256 actual-path)
        location-matches?
        (if expected-path
          (= expected-path actual-path)
          (and expected-root (.startsWith actual-path expected-root)
               (= (:file-name expected) (str (.getFileName actual-path)))))]
    (when-not (and (= (:name expected) (:name actual))
                   (= (:version expected) (:version actual))
                   (= (:sha256 expected) (:sha256 actual))
                   (= (:sha256 expected) actual-hash)
                   location-matches?)
      (fail! "RawHTTP consumer did not load the exact packed assembly"
             {:reason :assembly-provenance-mismatch
              :expected (cond-> expected
                          expected-path (assoc :location (str expected-path))
                          expected-root (assoc :location-root (str expected-root)))
              :actual (assoc actual :location (str actual-path)
                             :file-sha256 actual-hash)}))
    (assoc actual :location (str actual-path))))

(defn- expect-failure! [control thunk]
  (try
    (thunk)
    (fail! "RawHTTP negative control did not fail"
           {:reason :undetected-perturbation :control control})
    (catch clojure.lang.ExceptionInfo error
      (when (= :undetected-perturbation (:reason (ex-data error)))
        (throw error))
      {:control control :detected (:reason (ex-data error))})))

(defn- prove-observation-controls! [expected-text actual-text]
  (let [expected (parse-observations! expected-text)
        actual (parse-observations! actual-text)
        perturb (fn [rows]
                  (assoc rows 0
                         (assoc (first rows)
                                :line (str (:id (first rows)) "\t"
                                           (:status (first rows)) "\t"
                                           (.encodeToString
                                            (Base64/getEncoder)
                                            (.getBytes "deliberate-perturbation"
                                                       StandardCharsets/UTF_8))))))]
    [(expect-failure! :comparator
                      #(compare-observations! (render-observations (perturb expected))
                                              actual-text))
     (expect-failure! :missing-row
                      #(compare-observations! expected-text
                                              (render-observations (pop actual))))
     (expect-failure! :result
                      #(compare-observations! expected-text
                                              (render-observations (perturb actual))))
     (expect-failure! :duplicate-row
                      #(parse-observations!
                        (render-observations (conj actual (first actual)))))
     (expect-failure! :unstable-order
                      #(parse-observations!
                        (render-observations
                         (into [(second actual) (first actual)] (drop 2 actual)))))]))

(defn- read-contract! [^Path file]
  (let [[header & lines] (str/split-lines (Files/readString file StandardCharsets/UTF_8))
        rows (mapv #(str/split % #"\t" -1) (remove str/blank? lines))
        malformed (filter #(not= 2 (count %)) rows)
        grouped (group-by first rows)
        duplicates (->> grouped (keep (fn [[key values]] (when-not (= 1 (count values)) key)))
                        sort vec)]
    (when-not (= "DRIPSHARP_JAVA_LIBRARY_CONTRACT_V1" header)
      (fail! "RawHTTP project contract has the wrong header"
             {:reason :project-contract-header :actual header}))
    (when (or (seq malformed) (seq duplicates))
      (fail! "RawHTTP project contract has malformed or duplicate records"
             {:reason :project-contract-records :malformed malformed
              :duplicates duplicates}))
    (into {} rows)))

(defn- parse-tsv! [^Path file expected-header columns]
  (let [[header column-line & rows]
        (str/split-lines (Files/readString file StandardCharsets/UTF_8))]
    (when-not (and (= expected-header header)
                   (= columns (str/split column-line #"\t" -1)))
      (fail! "RawHTTP TSV contract header changed"
             {:reason :tsv-header :file (str file) :header header
              :columns column-line}))
    (mapv
     (fn [line]
       (let [values (str/split line #"\t" -1)]
         (when-not (= (count columns) (count values))
           (fail! "RawHTTP TSV row is malformed"
                  {:reason :malformed-tsv-row :file (str file) :line line}))
         (zipmap (map keyword columns) values)))
     (remove str/blank? rows))))

(defn- regular-files [^Path directory]
  (with-open [files (Files/walk directory (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (sort-by str) vec)))

(def ^:private forbidden-source-patterns
  [[:translation-error #"#error\s+DRIPSHARP_"]
   [:not-implemented #"\bNotImplementedException\b"]
   [:unsupported-java-placeholder #"\bUnsupportedOperationException\b"]
   [:todo #"(?i)\b(?:TODO|FIXME|HACK)\b"]])

(defn- body-key [row]
  [(-> (:owner row)
       (str/replace #"`\d+" "")
       (str/replace "$" ".")
       (str/replace ".internal." ".@internal."))
   (:member row)
   (parse-long (:parameter-count row))])

(defn- expected-body-key [row]
  [(get-in row [:generated :destination :owner])
   (get-in row [:generated :destination :name])
   (parse-long (get-in row [:generated :destination :parameter-count]))])

(defn- review-key [row]
  (mapv row [:assembly :owner :member :parameter-count :signature :finding]))

(defn- verify-review-evidence! [root row]
  (let [[_ relative line] (re-matches #"^(.*):(\d+)$" (:evidence row))
        file (when relative (paths/resolve-path root relative))]
    (when-not (and relative (= "authoritative-java-body" (:disposition row))
                   (paths/regular-file? file)
                   (= (:evidence-sha256 row) (sha256 file))
                   (pos? (parse-long line))
                   (<= (parse-long line)
                       (count (str/split-lines
                               (Files/readString file StandardCharsets/UTF_8)))))
      (fail! "RawHTTP body review lacks exact authoritative Java evidence"
             {:reason :invalid-body-review-evidence :row row}))))

(defn audit-selected-surface!
  "Audits every selected accessible declaration/source mapping and every
  compiled public/protected row and executable body from the exact consumed
  assembly."
  [root package-proof provenance contract]
  (let [generation (get-in package-proof [:verification :generation])
        emission (:emission generation)
        metadata (:public-metadata emission)
        selected (:rows metadata)
        expected-rows (parse-long (get contract "public-surface-row-count"))
        source-map-file (paths/resolve-path
                         (:project-root emission)
                         (get-in generation [:destination :output :source-map-file]))
        source-map (edn/read-string (Files/readString source-map-file StandardCharsets/UTF_8))
        mapped-ids (set (keep #(get-in % [:source :declaration-id])
                              (:mappings source-map)))
        missing-metadata
        (->> selected
             (filter #(or (str/blank? (:declaration-key %))
                          (str/blank? (get-in % [:generated :id]))
                          (str/blank? (get-in % [:generated :source :location :file]))
                          (not (pos? (or (get-in % [:generated :source :location :line]) 0)))
                          (not (contains? mapped-ids (get-in % [:generated :id])))))
             (take 20) vec)
        source-files (filter #(str/ends-with? (str %) ".cs")
                             (regular-files (paths/resolve-path (:project-root emission) "src")))
        source-findings
        (->> source-files
             (mapcat (fn [file]
                       (let [source (Files/readString file StandardCharsets/UTF_8)]
                         (keep (fn [[kind pattern]]
                                 (when (re-find pattern source)
                                   {:kind kind :file (str file)}))
                               forbidden-source-patterns))))
             vec)
        compiler (paths/resolve-path root "validation"
                                     "public-contract-compiler"
                                     "PublicContractCompiler.csproj")
        audit-file (paths/resolve-path (:proof-root package-proof)
                                       "rawhttp-accessible-body-audit.tsv")
        compiled-surface
        (dotnet-surface/verify!
         root (paths/resolve-path root (get contract "compiled-public-surface-file"))
         (:location provenance) metadata)
        expected-compiled-surface
        {:types (parse-long (get contract "compiled-public-surface-type-count"))
         :members (parse-long (get contract "compiled-public-surface-member-count"))
         :rows (parse-long (get contract "compiled-public-surface-row-count"))
         :surface-sha256 (get contract "compiled-public-surface-sha256")}]
    (when-not (= expected-compiled-surface
                 (select-keys compiled-surface (keys expected-compiled-surface)))
      (fail! "RawHTTP exact compiled-surface summary differs from its pinned contract"
             {:reason :compiled-surface-summary-drift
              :expected expected-compiled-surface :actual compiled-surface}))
    (when-not (and (= 1 (:schema-version source-map))
                   (= expected-rows (:required-rows metadata))
                   (= expected-rows (count selected))
                   (pos? (count (:mappings source-map)))
                   (pos? (get-in emission [:summary :source-mappings] 0))
                   (zero? (get-in emission [:summary :missing-source-mappings] 0))
                   (zero? (get-in emission [:summary :hard-failures] 0)))
      (fail! "RawHTTP selected surface or source-map accounting is incomplete"
             {:reason :incomplete-selected-surface
              :required expected-rows :selected (count selected)
              :source-mappings (count (:mappings source-map))
              :summary (:summary emission)}))
    (when (seq missing-metadata)
      (fail! "RawHTTP selected public declaration lost exact source-map evidence"
             {:reason :unmapped-selected-surface :rows missing-metadata}))
    (when (seq source-findings)
      (fail! "RawHTTP generated source contains implementation placeholders"
             {:reason :source-placeholders :findings source-findings}))
    (bounded-run! {:command ["dotnet" "build" (str compiler) "--configuration"
                             "Release" "--nologo" "--verbosity:quiet"
                             "--no-incremental" "-warnaserror"]
                   :directory root})
    (bounded-run! {:command ["dotnet" "run" "--project" (str compiler)
                             "--configuration" "Release" "--no-build" "--"
                             "audit-accessible" (str audit-file) (:location provenance)]
                   :directory root})
    (let [audit-rows
          (parse-tsv! audit-file "# DRIPSHARP_ACCESSIBLE_BODY_AUDIT_V1"
                      ["assembly" "owner" "member" "parameter-count"
                       "signature" "finding"])
          executable-rows (filter #(contains? #{"constructor" "method"}
                                              (get-in % [:row :kind]))
                                  selected)
          expected-frequencies (frequencies (map expected-body-key executable-rows))
          selected-audit (filter #(contains? expected-frequencies (body-key %)) audit-rows)
          actual-frequencies (frequencies (map body-key selected-audit))
          candidates (filter #(not (contains? #{"implemented" "abstract-contract"}
                                              (:finding %)))
                             selected-audit)
          review-file (paths/resolve-path root (get contract "body-review-file"))
          reviews
          (parse-tsv! review-file "DRIPSHARP_RAWHTTP_BODY_REVIEW_V1"
                      ["assembly" "owner" "member" "parameter-count" "signature"
                       "finding" "evidence" "evidence-sha256" "disposition"])]
      (when-not (= expected-frequencies actual-frequencies)
        (fail! "RawHTTP compiled accessible body inventory differs from the selected surface"
               {:reason :compiled-body-surface-mismatch
                :expected-count (count executable-rows)
                :actual-count (count selected-audit)
                :missing (vec (take 20 (remove (fn [[key count]]
                                                 (= count (get actual-frequencies key)))
                                               expected-frequencies)))
                :unexpected (vec (take 20 (remove (fn [[key count]]
                                                    (= count (get expected-frequencies key)))
                                                  actual-frequencies)))}))
      (doseq [review reviews] (verify-review-evidence! root review))
      (when-not (= (mapv review-key reviews) (mapv review-key candidates))
        (fail! "RawHTTP constant/default/no-op/unsupported body review drifted"
               {:reason :body-review-drift
                :expected (mapv review-key reviews)
                :actual (mapv review-key candidates)}))
      {:selected-rows (count selected)
       :compiled-surface compiled-surface
       :selected-executable-bodies (count executable-rows)
       :source-mappings (count (:mappings source-map))
       :source-files (count source-files)
       :reviewed-authoritative-bodies (count reviews)
       :body-findings (frequencies (map :finding selected-audit))})))

(defn- verify-non-pkl-boundary! [package-proof]
  (let [generation (get-in package-proof [:verification :generation])
        destination (:destination generation)
        emission (:emission generation)
        identities (mapv :identity (:packages package-proof))
        forbidden-namespaces
        (->> (all-ns) (map ns-name) (map str)
             (filter #(re-find #"(?i)(^|[.-])pkl($|[.-])" %)) sort vec)
        identity-values
        (concat [(get-in destination [:project :assembly-name])
                 (get-in destination [:project :root-namespace])
                 (get-in destination [:package :id])]
                (map :id identities)
                (map :logical-name (:resource-artifacts emission)))
        pkl-identities (filter #(re-find #"(?i)(^|[^a-z])pkl([^a-z]|$)" (str %))
                               identity-values)
        generated-findings
        (->> (regular-files (paths/resolve-path (:project-root emission) "src"))
             (filter #(str/ends-with? (str %) ".cs"))
             (keep (fn [file]
                     (when (re-find #"(?i)(^|[^a-z])pkl([^a-z]|$)"
                                    (Files/readString file StandardCharsets/UTF_8))
                       (str file))))
             vec)]
    (when (or (seq forbidden-namespaces) (seq pkl-identities) (seq generated-findings)
              (not= "research/rawhttp"
                    (get-in generation [:generation-profile :project-root])))
      (fail! "RawHTTP regression crossed the Pkl product boundary"
             {:reason :pkl-boundary-leak :loaded-namespaces forbidden-namespaces
              :identities (vec pkl-identities) :generated-files generated-findings
              :project-root (get-in generation [:generation-profile :project-root])}))
    {:loaded-pkl-namespaces 0 :pkl-identities 0 :pkl-generated-files 0
     :source-project "research/rawhttp"}))

(defn- primary-package [package-proof]
  (or (first (filter :primary? (:packages package-proof)))
      (fail! "RawHTTP package proof has no primary package"
             {:reason :missing-primary-package})))

(defn verify-package-equivalence!
  "Runs the Java oracle in a separate process, then performs two clean .NET
  packs and a fresh package-only behavior/provenance gate."
  ([] (verify-package-equivalence! {}))
  ([{:keys [workspace-root run-command! package-fn]
     :or {run-command! bounded-run!
          package-fn packaging/verify-package-consumption!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         contract-file (paths/resolve-path root "validation"
                                           "rawhttp-core" "ProjectContract.tsv")
         contract (read-contract! contract-file)
         java-proof
         (run-command! {:command ["sh" (str (paths/resolve-path root "validation" "rawhttp-core"
                                                                "verify.sh"))]
                        :directory root :timeout-ms command-timeout-ms})
         _ (when-not (str/includes? (:output java-proof) "RawHTTP contract verified:")
             (fail! "Independent Java oracle did not report successful verification"
                    {:reason :java-oracle-incomplete :output (:output java-proof)}))
         expected-file (paths/resolve-path root (get contract "observations-file"))
         expected-text (Files/readString expected-file StandardCharsets/UTF_8)
         expected-rows (validate-observation-family! (parse-observations! expected-text))
         package-proof
         (package-fn {:workspace-root root :profile profile-file
                      :run-command! run-command!})
         first-output (extract-probe-output! (get-in package-proof [:run :output]))
         consumer-root (:consumer-root package-proof)
         consumer-project (paths/resolve-path consumer-root
                                              (get-in package-proof
                                                      [:verification :generation :destination
                                                       :package-consumer :project-file]))
         second-run
         (run-command! {:command ["dotnet" "run" "--project" (str consumer-project)
                                  "--no-build" "--no-restore"]
                        :directory consumer-root})
         second-output (extract-probe-output! (:output second-run))
         first-rows (validate-observation-family!
                     (parse-observations! (:observations first-output)))
         second-rows (validate-observation-family!
                      (parse-observations! (:observations second-output)))
         java-comparison (compare-observations! expected-text (:observations first-output))
         repeat-comparison
         (compare-observations! (:observations first-output)
                                (:observations second-output))
         package (primary-package package-proof)
         assembly-name (get-in package-proof [:verification :generation :destination
                                              :project :assembly-name])
         assembly-proof (get-in package [:resource-proof :assembly-artifact])
         assembly-identity (get-in package [:resource-proof :assembly-identity])
         expected-provenance
         {:name assembly-name :version (:version assembly-identity)
          :sha256 (:sha256 assembly-proof)
          :location-root (str (paths/resolve-path consumer-root "bin"))
          :file-name (str assembly-name ".dll")}
         first-provenance
         (validate-provenance! expected-provenance
                               (parse-provenance! (:provenance-line first-output)))
         second-provenance
         (validate-provenance! expected-provenance
                               (parse-provenance! (:provenance-line second-output)))
         controls
         (conj (prove-observation-controls! expected-text (:observations first-output))
               (expect-failure!
                :assembly-provenance
                #(validate-provenance! (assoc expected-provenance
                                              :sha256 (apply str (repeat 64 "0")))
                                       first-provenance)))
         observations-file (paths/resolve-path (:proof-root package-proof)
                                               "rawhttp-dotnet-observations.tsv")
         _ (Files/writeString observations-file (:observations first-output)
                              (make-array java.nio.file.OpenOption 0))
         surface-audit (audit-selected-surface! root package-proof first-provenance contract)
         boundary (verify-non-pkl-boundary! package-proof)
         package-surface (:public-surface package)]
     (when-not (and (= expected-rows first-rows second-rows)
                    (= first-provenance second-provenance)
                    (pos? (:types package-surface))
                    (pos? (:members package-surface))
                    (re-matches #"[0-9a-f]{64}" (:sha256 package-surface))
                    (= {:types (parse-long (get contract
                                                "compiled-public-surface-type-count"))
                        :members (parse-long (get contract
                                                  "compiled-public-surface-member-count"))
                        :sha256 (get contract
                                     "package-inspector-public-surface-sha256")}
                       package-surface))
       (fail! "RawHTTP repeated package-only evidence is inconsistent"
              {:reason :inconsistent-package-proof}))
     (let [summary
           {:java-observations (:matched java-comparison)
            :repeated-dotnet-observations (:matched repeat-comparison)
            :success-observations success-count
            :failure-observations failure-count
            :clean-builds (get-in package-proof [:packing-summary :clean-builds])
            :package (:identity package)
            :assembly (select-keys first-provenance [:name :version :sha256])
            :package-public-surface package-surface
            :surface-audit surface-audit
            :perturbation-controls controls
            :non-pkl-boundary boundary}]
       (println "Independent RawHTTP Java/package-only .NET equivalence passed:"
                (pr-str summary))
       {:summary summary :java-run java-proof :package-proof package-proof
        :observations-file observations-file :second-run second-run}))))

(defn -main [& args]
  (when (seq args)
    (fail! "RawHTTP package equivalence command does not accept arguments"
           {:reason :unexpected-arguments :arguments (vec args)}))
  (verify-package-equivalence!))
