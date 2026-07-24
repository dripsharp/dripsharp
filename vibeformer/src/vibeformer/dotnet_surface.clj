(ns vibeformer.dotnet-surface
  "Product-neutral exact compiled .NET public/protected surface contracts."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(def surface-columns
  ["assembly" "owner" "kind" "name" "parameter-count" "visibility"
   "metadata-flags" "signature" "generic-constraints" "nullability"])

(def contract-columns
  (into surface-columns
        ["source-provenance" "source-declaration" "translation-rule"]))

(def ^:private reflected-magic "VIBEFORMER_DOTNET_ACCESSIBLE_SURFACE_V1")
(def ^:private contract-magic "VIBEFORMER_DOTNET_ACCESSIBLE_CONTRACT_V1")

(def ^:private translation-rules
  #{"java-declaration"
    "java-implicit-constructor"
    "java-synthetic-member"
    "java-closeable-disposable"
    "java-compatibility-type"
    "java-compatibility-member"
    "clr-special-accessor"})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-dotnet-surface-contract)))))

(defn- parse-tsv! [file magic columns]
  (let [file (paths/path file)]
    (when-not (paths/regular-file? file)
      (fail! "Compiled .NET surface fixture is missing"
             {:kind :missing-dotnet-surface-fixture :file (str file)}))
    (let [lines (str/split-lines (Files/readString file StandardCharsets/UTF_8))
          expected-magic (str "# " magic)
          content (remove #(or (str/blank? %) (str/starts-with? % "#")) lines)
          actual-columns (some-> (first content) (str/split #"\t" -1) vec)]
      (when-not (= expected-magic (first lines))
        (fail! "Compiled .NET surface fixture has the wrong identity"
               {:kind :dotnet-surface-magic :file (str file)
                :expected expected-magic :actual (first lines)}))
      (when-not (= columns actual-columns)
        (fail! "Compiled .NET surface fixture has the wrong columns"
               {:kind :dotnet-surface-columns :file (str file)
                :expected columns :actual actual-columns}))
      {:file file
       :comments (vec (filter #(str/starts-with? % "#") lines))
       :rows
       (mapv
        (fn [line-number line]
          (let [values (str/split line #"\t" -1)]
            (when-not (= (count columns) (count values))
              (fail! "Compiled .NET surface row has the wrong field count"
                     {:kind :dotnet-surface-field-count :file (str file)
                      :line line-number :expected (count columns)
                      :actual (count values)}))
            (zipmap (map keyword columns) values)))
        (iterate inc 2)
        (rest content))})))

(defn- row-values [columns row]
  (mapv row (map keyword columns)))

(defn- surface-identity [row]
  (row-values surface-columns row))

(defn- member-identity [row]
  (mapv row [:assembly :owner :kind :name :signature]))

(defn- duplicates [key-fn rows]
  (->> rows
       (group-by key-fn)
       (keep (fn [[key values]] (when (< 1 (count values)) key)))
       sort vec))

(defn compare-surface
  "Compares complete reflected rows. Exact duplicates and any metadata drift
  are failures; no unexpected public/protected row is filtered away."
  [expected actual]
  (let [expected-duplicates (duplicates member-identity expected)
        actual-duplicates (duplicates member-identity actual)]
    (cond
      (seq expected-duplicates)
      {:mismatch {:kind :duplicate-compiled-contract-rows
                  :rows (vec (take 20 expected-duplicates))}}

      (seq actual-duplicates)
      {:mismatch {:kind :duplicate-compiled-reflection-rows
                  :rows (vec (take 20 actual-duplicates))}}

      :else
      (let [expected-frequencies (frequencies (map surface-identity expected))
            actual-frequencies (frequencies (map surface-identity actual))
            missing (->> (set/difference (set (keys expected-frequencies))
                                         (set (keys actual-frequencies)))
                         sort vec)
            unexpected (->> (set/difference (set (keys actual-frequencies))
                                            (set (keys expected-frequencies)))
                            sort vec)
            changed-counts
            (->> (set/intersection (set (keys expected-frequencies))
                                   (set (keys actual-frequencies)))
                 (keep (fn [identity]
                         (let [expected-count (get expected-frequencies identity)
                               actual-count (get actual-frequencies identity)]
                           (when-not (= expected-count actual-count)
                             {:row identity :expected expected-count
                              :actual actual-count}))))
                 (sort-by :row) vec)]
        (if (and (empty? missing) (empty? unexpected) (empty? changed-counts))
          {:matched (count expected)}
          {:mismatch {:kind :compiled-dotnet-surface-drift
                      :missing (vec (take 20 missing))
                      :unexpected (vec (take 20 unexpected))
                      :changed-counts (vec (take 20 changed-counts))
                      :missing-count (count missing)
                      :unexpected-count (count unexpected)
                      :changed-count (count changed-counts)}})))))

(defn surface-fingerprint
  "Returns SHA-256 over the exact normalized reflection rows."
  [rows]
  (let [text (str/join "\n" (map #(str/join "\t" (surface-identity %))
                                 (sort-by surface-identity rows)))
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff (int %))) digest))))

(defn- nuget-global-packages! [workspace]
  (let [output
        (:output
         (process/run! {:command ["dotnet" "nuget" "locals"
                                  "global-packages" "--list"]
                        :directory workspace}))
        path (some->> (re-find #"(?m)^global-packages:\s*(.+?)\s*$" output)
                      second)]
    (when-not (seq path)
      (fail! "The .NET global package directory could not be discovered"
             {:kind :missing-dotnet-global-packages :output output}))
    path))

(defn reflect!
  "Runs the independent product-neutral reflection probe on one exact assembly."
  [workspace assembly]
  (let [workspace (paths/absolute workspace)
        assembly (paths/absolute assembly)
        probe-directory (paths/resolve-path workspace "vibeformer" "validation"
                                            "dotnet-surface-probe")
        probe (paths/resolve-path probe-directory "DotNetSurfaceProbe.csproj")
        probe-output (paths/resolve-path probe-directory "bin" "Release" "net10.0")
        probe-assembly (paths/resolve-path probe-output "DotNetSurfaceProbe.dll")
        probe-runtimeconfig
        (paths/resolve-path probe-output "DotNetSurfaceProbe.runtimeconfig.json")
        assembly-name (str (.getFileName ^Path assembly))
        dependency-manifest
        (paths/resolve-path
         (.getParent ^Path assembly)
         (str (subs assembly-name 0 (- (count assembly-name) 4)) ".deps.json"))
        directory (Files/createTempDirectory
                   "vibeformer-dotnet-surface"
                   (make-array FileAttribute 0))
        output (paths/resolve-path directory "surface.tsv")]
    (try
      (when-not (paths/regular-file? assembly)
        (fail! "Compiled .NET surface assembly is missing"
               {:kind :missing-dotnet-surface-assembly :assembly (str assembly)}))
      (process/run! {:command ["dotnet" "build" probe "--configuration" "Release"
                               "--nologo" "--verbosity:quiet" "-warnaserror"]
                     :directory workspace})
      (process/run! {:command
                     (if (paths/regular-file? dependency-manifest)
                       ["dotnet" "exec"
                        "--additionalprobingpath"
                        (nuget-global-packages! workspace)
                        "--runtimeconfig" probe-runtimeconfig
                        "--depsfile" dependency-manifest
                        probe-assembly output assembly]
                       ["dotnet" "run" "--project" probe
                        "--configuration" "Release" "--no-build" "--"
                        output assembly])
                     :directory workspace})
      (:rows (parse-tsv! output reflected-magic surface-columns))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists directory)))))

(defn- normalize-owner [value]
  (-> value
      (str/replace #"`\d+" "")
      (str/replace "$" ".")
      (str/replace ".internal." ".@internal.")))

(defn- reflected-shape [row]
  [(:assembly row) (normalize-owner (:owner row)) (:kind row) (:name row)
   (:parameter-count row) (:visibility row)])

(defn- generated-shape [row]
  (let [destination (get-in row [:generated :destination])]
    [(:assembly destination) (:owner destination) (:kind destination)
     (:name destination) (:parameter-count destination)
     (:visibility destination)]))

(defn- portable-provenance [workspace location]
  (let [workspace (paths/absolute workspace)
        file (paths/absolute (:file location))
        file (if (paths/regular-file? file)
               (.toRealPath ^Path file (make-array java.nio.file.LinkOption 0))
               file)
        line (:line location)]
    (when-not (and (integer? line) (pos? line))
      (fail! "Generated public declaration lacks exact source provenance"
             {:kind :invalid-generated-surface-provenance :location location}))
    (str (str/replace (str (if (.startsWith ^Path file workspace)
                             (.relativize ^Path workspace file)
                             file))
                      "\\" "/")
         ":" line)))

(defn- java-rule [metadata]
  (case (get-in metadata [:generated :representation])
    :implicit-default-constructor "java-implicit-constructor"
    :java-synthetic-public-member "java-synthetic-member"
    "java-declaration"))

(def ^:private compatibility-source
  "vibeformer/runtime/Vibeformer.JavaCompat.cs")

(def ^:private compatibility-types
  #{"IJavaOptional" "JavaByteBuffer" "JavaExecutorService"
    "JavaFilterOutputStream" "JavaInputStream" "JavaIterator" "JavaOptional"
    "JavaOutputStream" "JavaPath" "JavaServerSocket" "JavaSslContext"
    "JavaStream"})

(defn- compatibility-type-name [owner]
  (let [simple (last (str/split owner #"[$.]"))]
    (str/replace simple #"`\d+$" "")))

(defn- compatibility-provenance [workspace owner]
  (let [name (compatibility-type-name owner)
        file (paths/resolve-path workspace compatibility-source)
        lines (str/split-lines (Files/readString file StandardCharsets/UTF_8))
        pattern (re-pattern (str "^(?:sealed |abstract )?(?:class|interface) "
                                 (java.util.regex.Pattern/quote name)
                                 "(?:<|[ :{]|$)"))
        matches (keep-indexed (fn [index line]
                                (when (re-find pattern (str/trim line)) (inc index)))
                              lines)]
    (when-not (and (contains? compatibility-types name) (= 1 (count matches)))
      (fail! "Externally visible compatibility type lacks one exact source declaration"
             {:kind :unowned-java-compatibility-surface
              :owner owner :type name :matches (vec matches)}))
    (str compatibility-source ":" (first matches))))

(defn- compatibility-rule [row]
  (cond
    (= "type" (:kind row)) "java-compatibility-type"
    (and (= "method" (:kind row))
         (re-matches #"(?:get|set|add|remove)_.*" (:name row)))
    "clr-special-accessor"
    :else "java-compatibility-member"))

(defn- closeable-disposable-row
  [workspace row type-metadata]
  (when (and (= "method" (:kind row))
             (= "Dispose" (:name row))
             (= "0" (:parameter-count row))
             (= "System.Void Dispose()" (:signature row)))
    (when-let [metadata (get type-metadata (normalize-owner (:owner row)))]
      (assoc row
             :source-provenance
             (portable-provenance workspace
                                  (get-in metadata [:generated :source :location]))
             :source-declaration (get-in metadata [:row :identity])
             :translation-rule "java-closeable-disposable"))))

(defn annotate-contract-rows!
  "Joins every reflected row either to one exact selected Java declaration or
  to an explicitly public reusable compatibility declaration/CLR accessor."
  [workspace reflected-rows public-metadata]
  (let [metadata-rows (:rows public-metadata)
        compatibility-namespace
        (or (:compatibility-namespace public-metadata) "Vibeformer.Runtime")
        metadata-by-shape (group-by generated-shape metadata-rows)
        reflected-by-shape (group-by reflected-shape reflected-rows)
        type-metadata
        (into {}
              (keep (fn [row]
                      (when (= "type" (get-in row [:row :kind]))
                        [(get-in row [:generated :destination :owner]) row])))
              metadata-rows)
        shape-mismatches
        (->> metadata-by-shape
             (keep (fn [[shape rows]]
                     (let [actual (get reflected-by-shape shape)]
                       (when-not (= (count rows) (count actual))
                         {:shape shape :expected (count rows)
                          :actual (count actual)}))))
             (sort-by :shape) vec)
        java-shapes (set (keys metadata-by-shape))]
    (when (seq shape-mismatches)
      (fail! "Generated Java declarations do not map one-for-one to compiled metadata"
             {:kind :compiled-java-declaration-shape-mismatch
              :mismatches (vec (take 30 shape-mismatches))
              :mismatch-count (count shape-mismatches)}))
    (->>
     reflected-by-shape
     (mapcat
      (fn [[shape rows]]
        (let [rows (sort-by surface-identity rows)]
          (if (contains? java-shapes shape)
            (map
             (fn [row metadata]
               (assoc row
                      :source-provenance
                      (portable-provenance workspace
                                           (get-in metadata [:generated :source :location]))
                      :source-declaration (get-in metadata [:row :identity])
                      :translation-rule (java-rule metadata)))
             rows
             (sort-by #(get-in % [:row :identity])
                      (get metadata-by-shape shape)))
            (map
             (fn [row]
               (or
                (closeable-disposable-row workspace row type-metadata)
                (when (str/starts-with?
                       (:owner row)
                       (str compatibility-namespace "."))
                  (assoc row
                         :source-provenance
                         (compatibility-provenance workspace (:owner row))
                         :source-declaration
                         (str "clr|" (str/join "|" (surface-identity row)))
                         :translation-rule (compatibility-rule row)))
                (fail! "Compiled surface row has no selected Java or compatibility owner"
                       {:kind :unowned-compiled-surface-row :row row})))
             rows)))))
     (sort-by surface-identity)
     vec)))

(defn read-contract!
  "Reads and validates a retained exact compiled-surface contract."
  [workspace file]
  (let [{:keys [rows] :as contract}
        (parse-tsv! file contract-magic contract-columns)
        identities (mapv surface-identity rows)
        invalid-counts (filter #(not (re-matches #"\d+" (:parameter-count %))) rows)
        invalid-rules (remove #(contains? translation-rules (:translation-rule %)) rows)]
    (when-not (and (seq rows)
                   (= identities (vec (sort identities)))
                   (empty? invalid-counts)
                   (empty? invalid-rules))
      (fail! "Compiled .NET surface contract is empty, unsorted, or invalid"
             {:kind :invalid-compiled-dotnet-surface-contract
              :rows (count rows) :invalid-counts (count invalid-counts)
              :invalid-rules (mapv :translation-rule (take 20 invalid-rules))}))
    (doseq [row rows]
      (let [[_ relative line] (re-matches #"^(.+):(\d+)$" (:source-provenance row))
            source (when relative (paths/resolve-path workspace relative))]
        (when-not (and relative (paths/regular-file? source)
                       (pos? (parse-long line))
                       (<= (parse-long line)
                           (count (str/split-lines
                                   (Files/readString source StandardCharsets/UTF_8))))
                       (not (str/blank? (:source-declaration row))))
          (fail! "Compiled .NET surface row has invalid source provenance"
                 {:kind :invalid-compiled-surface-provenance :row row}))))
    contract))

(defn verify-rows!
  "Verifies already-reflected rows against a retained contract and its exact
  generated-source provenance. This seam retains deliberate mutation controls."
  [workspace contract actual-rows public-metadata]
  (let [expected (:rows contract)
        comparison (compare-surface expected actual-rows)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! "Compiled .NET public/protected surface drifted"
             (assoc mismatch :kind :compiled-dotnet-surface-mismatch)))
    (let [annotated (annotate-contract-rows! workspace
                                             (mapv #(select-keys %
                                                                 (map keyword surface-columns))
                                                   expected)
                                             public-metadata)
          expected-contract (mapv #(select-keys % (map keyword contract-columns)) expected)
          generated-rules #{"java-declaration"
                            "java-implicit-constructor"
                            "java-synthetic-member"}
          java-rows (filter #(contains? generated-rules (:translation-rule %))
                            annotated)
          selected-rows (:rows public-metadata)
          selected-identities
          (frequencies (map #(get-in % [:row :identity]) selected-rows))
          compiled-identities
          (frequencies (map :source-declaration java-rows))]
      (when-not (= expected-contract annotated)
        (fail! "Compiled .NET contract source provenance or translation rules drifted"
               {:kind :compiled-dotnet-surface-provenance-drift
                :expected-count (count expected-contract)
                :actual-count (count annotated)}))
      (when-not (and (= (:required-rows public-metadata) (count selected-rows))
                     (= selected-identities compiled-identities))
        (fail! "Compiled .NET surface does not reconcile exact generated metadata"
               {:kind :forged-compiled-surface-metadata
                :required-rows (:required-rows public-metadata)
                :metadata-rows (count selected-rows)
                :compiled-java-rows (count java-rows)
                :selected-identities selected-identities
                :compiled-identities compiled-identities}))
      {:rows (:matched comparison)
       :types (count (filter #(= "type" (:kind %)) expected))
       :members (count (remove #(= "type" (:kind %)) expected))
       :contract-members (count selected-rows)
       :surface-sha256 (surface-fingerprint expected)
       :translation-rules (frequencies (map :translation-rule expected))})))

(defn verify-generated-rows!
  "Verifies reflected rows directly against generated source mappings. This
  build-tool-neutral mode derives the required Java surface from Spoon and
  permits only the systematic compatibility rows classified by the annotator."
  [workspace actual-rows public-metadata]
  (let [annotated (annotate-contract-rows! workspace actual-rows public-metadata)
        generated-rules #{"java-declaration"
                          "java-implicit-constructor"
                          "java-synthetic-member"}
        java-rows (filter #(contains? generated-rules (:translation-rule %))
                          annotated)
        selected-rows (:rows public-metadata)
        selected-identities
        (frequencies (map #(get-in % [:row :identity]) selected-rows))
        compiled-identities
        (frequencies (map :source-declaration java-rows))]
    (when-not (and (= (:required-rows public-metadata) (count selected-rows))
                   (= selected-identities compiled-identities))
      (fail! "Compiled .NET surface does not reconcile exact generated metadata"
             {:kind :forged-compiled-surface-metadata
              :required-rows (:required-rows public-metadata)
              :metadata-rows (count selected-rows)
              :compiled-java-rows (count java-rows)
              :selected-identities selected-identities
              :compiled-identities compiled-identities}))
    {:rows (count actual-rows)
     :types (count (filter #(= "type" (:kind %)) actual-rows))
     :members (count (remove #(= "type" (:kind %)) actual-rows))
     :contract-members (count selected-rows)
     :surface-sha256 (surface-fingerprint actual-rows)
     :translation-rules (frequencies (map :translation-rule annotated))}))

(defn verify!
  "Reflects and exactly verifies one assembly against its retained contract."
  [workspace contract-file assembly public-metadata]
  (let [contract (read-contract! workspace contract-file)
        actual (reflect! workspace assembly)]
    (assoc (verify-rows! workspace contract actual public-metadata)
           :assembly (str (paths/absolute assembly)))))

(defn verify-generated!
  "Reflects one assembly and verifies it against its Spoon-derived generated
  source mappings without a retained manual API inventory."
  [workspace assembly public-metadata]
  (let [actual (reflect! workspace assembly)]
    (assoc (verify-generated-rows! workspace actual public-metadata)
           :assembly (str (paths/absolute assembly)))))

(defn write-contract!
  "Writes a deterministic retained contract from reflected rows and generated
  metadata. Used only to intentionally refresh a reviewed snapshot."
  [workspace output reflected-rows public-metadata]
  (let [rows (annotate-contract-rows! workspace reflected-rows public-metadata)
        text
        (str "# " contract-magic "\n"
             "# java-declaration: one selected Java declaration emits one CLR row.\n"
             "# java-implicit-constructor: a Java implicit default constructor emits .ctor.\n"
             "# java-synthetic-member: Java enum values/valueOf expansion emits a CLR row.\n"
             "# java-closeable-disposable: a public Java Closeable type emits its required IDisposable.Dispose member.\n"
             "# java-compatibility-type/member: reusable Java compatibility shape is public because a selected public signature or base type requires it.\n"
             "# clr-special-accessor: CLR property/event metadata expands one compatibility declaration into its externally accessible special method.\n"
             (str/join "\t" contract-columns) "\n"
             (str/join "\n" (map #(str/join "\t" (row-values contract-columns %)) rows))
             "\n")]
    (Files/writeString (paths/path output) text StandardCharsets/UTF_8
                       (make-array OpenOption 0))
    {:rows (count rows)
     :types (count (filter #(= "type" (:kind %)) rows))
     :members (count (remove #(= "type" (:kind %)) rows))
     :surface-sha256 (surface-fingerprint rows)
     :translation-rules (frequencies (map :translation-rule rows))}))
