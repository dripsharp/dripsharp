(ns dripsharp.nuget-package-bundle
  "Deterministically consolidates a proved component package closure into one
  public NuGet package without changing the generated assembly boundaries."
  (:require [clojure.string :as str]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Locale]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :nuget-package-bundle-failed))))

(defn- lower
  [value]
  (.toLowerCase (str value) Locale/ROOT))

(defn- safe-entry-names!
  [stage names]
  (let [unsafe
        (filterv
         (fn [name]
           (let [segments (str/split name #"/" -1)]
             (or (str/blank? name)
                 (str/starts-with? name "/")
                 (str/includes? name "\\")
                 (some #{"." ".."} segments))))
         names)
        duplicates
        (->> names frequencies
             (keep (fn [[name count]] (when (< 1 count) name)))
             sort vec)
        case-collisions
        (->> names
             (group-by lower)
             vals
             (map #(vec (sort (distinct %))))
             (filter #(< 1 (count %)))
             (sort-by first)
             vec)]
    (when (or (seq unsafe) (seq duplicates) (seq case-collisions))
      (fail! "NuGet bundle contains ambiguous or unsafe archive paths"
             {:stage stage
              :unsafe unsafe
              :duplicates duplicates
              :case-collisions case-collisions}))
    names))

(defn- archive-entries!
  [^Path artifact]
  (when-not (paths/regular-file? artifact)
    (fail! "NuGet bundle component artifact is missing"
           {:artifact (str artifact)}))
  (with-open [archive (ZipFile. (str artifact))]
    (let [entries (->> (enumeration-seq (.entries archive))
                       (remove #(.isDirectory ^ZipEntry %))
                       vec)
          names (mapv #(.getName ^ZipEntry %) entries)]
      (safe-entry-names! :component names)
      (into
       (sorted-map)
       (map
        (fn [^ZipEntry entry]
          [(.getName entry)
           (with-open [input (.getInputStream archive entry)]
             (.readAllBytes input))]))
       entries))))

(defn- add-entry!
  [entries stage name bytes]
  (if-let [existing (get entries name)]
    (if (= (seq existing) (seq bytes))
      entries
      (fail! "NuGet bundle component payloads disagree on one archive entry"
             {:stage stage :entry name}))
    (assoc entries name bytes)))

(defn- dependency-block
  [target-framework dependencies]
  (str "<dependencies>\n"
       "      <group targetFramework=\""
       (util/xml-escape target-framework)
       "\">\n"
       (apply
        str
        (for [{:keys [id version]} dependencies]
          (str "        <dependency id=\"" (util/xml-escape id)
               "\" version=\"" (util/xml-escape version)
               "\" exclude=\"Build,Analyzers\" />\n")))
       "      </group>\n"
       "    </dependencies>"))

(defn- rewrite-dependencies!
  [artifact-kind nuspec target-framework dependencies]
  (let [xml (String. ^bytes nuspec StandardCharsets/UTF_8)
        pattern #"(?s)<dependencies>.*?</dependencies>"
        matches (re-seq pattern xml)]
    (when-not (= 1 (count matches))
      (fail! "NuGet bundle base nuspec has no exact dependency section"
             {:artifact-kind artifact-kind
              :dependency-section-count (count matches)}))
    (.getBytes
     (str/replace xml pattern (dependency-block target-framework dependencies))
     StandardCharsets/UTF_8)))

(defn- write-archive!
  [^Path destination entries]
  (safe-entry-names! :bundle (vec (keys entries)))
  (Files/createDirectories (.getParent destination)
                           (make-array FileAttribute 0))
  (with-open [output
              (ZipOutputStream.
               (Files/newOutputStream destination (make-array OpenOption 0)))]
    (doseq [[name bytes] (sort-by key entries)]
      (let [entry (doto (ZipEntry. name) (.setTime 0))]
        (.putNextEntry output entry)
        (.write output ^bytes bytes)
        (.closeEntry output))))
  destination)

(defn- external-dependencies!
  [component-package-ids packages]
  (let [component-ids (set (map lower component-package-ids))
        dependencies
        (->> packages
             (mapcat #(get-in % [:inspection :dependencies]))
             (remove #(contains? component-ids (lower (:id %))))
             (map #(select-keys % [:id :version]))
             vec)
        conflicts
        (->> dependencies
             (group-by (comp lower :id))
             (keep
              (fn [[id records]]
                (let [versions (set (map :version records))]
                  (when (< 1 (count versions))
                    {:id id :versions (vec (sort versions))}))))
             vec)]
    (when (seq conflicts)
      (fail! "NuGet bundle components require conflicting external versions"
             {:conflicts conflicts}))
    (->> dependencies
         (reduce (fn [result dependency]
                   (assoc result [(lower (:id dependency)) (:version dependency)]
                          dependency))
                 (sorted-map))
         vals
         (sort-by (juxt (comp lower :id) :version))
         (mapv #(sorted-map :id (:id %) :version (:version %))))))

(defn- exact-component-packages!
  [bundle package-result]
  (let [expected (:component-package-ids bundle)
        packages (:packages package-result)
        by-id (into {} (map (juxt #(get-in % [:identity :id]) identity)) packages)
        actual (set (keys by-id))]
    (when-not (and (= (set expected) actual)
                   (= (count expected) (count packages)))
      (fail! "NuGet bundle input is not the exact component package closure"
             {:expected expected :actual (vec (sort actual))}))
    (mapv by-id expected)))

(defn- exact-external-packages!
  [dependencies package-result]
  (let [packages (vec (:external-packages package-result))
        identities (mapv (fn [{:keys [id version]}]
                           [(lower id) version])
                         packages)
        _
        (when-not (= (count identities) (count (distinct identities)))
          (fail! "NuGet bundle feed contains duplicate external package identities"
                 {:identities identities}))
        available
        (into {}
              (map (juxt (fn [{:keys [id version]}] [(lower id) version])
                         identity))
              packages)]
    (doseq [{:keys [id version]} dependencies]
      (when-not (get available [(lower id) version])
        (fail! "NuGet bundle feed is missing an external dependency artifact"
               {:dependency [id version]})))
    (vec (sort-by (juxt (comp lower :id) :version) packages))))

(defn bundle!
  "Builds and independently consumes the one-package public surface declared by
  a target NuGet `:bundle` contract. The component package proof remains the
  authorship and reproducibility boundary."
  [{:keys [workspace-root plan package-result run-command! consumer-fn]
    :or {consumer-fn packaging/verify-packed-consumer!}}]
  (let [contract (:contract plan)
        bundle (get-in contract [:publication :nuget :bundle])
        component-package-ids (:component-package-ids bundle)
        packages (exact-component-packages! bundle package-result)
        package-id (:package-id bundle)
        profile (:profile bundle)
        base (first (filter #(= package-id (get-in % [:identity :id])) packages))
        version (get-in base [:identity :version])
        versions (set (map #(get-in % [:identity :version]) packages))
        target-frameworks
        (set (map #(get-in % [:destination :project :target-framework]) packages))
        target-framework (first target-frameworks)
        dependencies (external-dependencies! component-package-ids packages)
        external-packages (exact-external-packages! dependencies package-result)
        nuspec-entry (str package-id ".nuspec")
        base-package-entries (archive-entries! (:artifact base))
        base-symbol-entries (archive-entries! (:symbol-artifact base))
        package-entries
        (reduce
         (fn [entries package]
           (let [artifact-entries (archive-entries! (:artifact package))
                 assembly-entry (get-in package [:inspection :assembly-entry])]
             (when-not (and (string? assembly-entry)
                            (str/ends-with? (lower assembly-entry) ".dll")
                            (contains? artifact-entries assembly-entry))
               (fail! "NuGet bundle component has no proved assembly payload"
                      {:component (get-in package [:identity :id])
                       :assembly-entry assembly-entry}))
             (add-entry! entries :assembly assembly-entry
                         (get artifact-entries assembly-entry))))
         base-package-entries
         packages)
        pdbs-and-entries
        (reduce
         (fn [{:keys [entries pdbs]} package]
           (let [symbol-entries (archive-entries! (:symbol-artifact package))
                 pdb-entry (get-in package [:symbol-inspection :pdb-entry])
                 pdb-bytes (get symbol-entries pdb-entry)]
             (when-not (and (string? pdb-entry)
                            (str/ends-with? (lower pdb-entry) ".pdb")
                            pdb-bytes)
               (fail! "NuGet bundle component has no proved portable PDB payload"
                      {:component (get-in package [:identity :id])
                       :pdb-entry pdb-entry}))
             {:entries (add-entry! entries :symbols pdb-entry pdb-bytes)
              :pdbs (conj pdbs
                          (sorted-map :entry pdb-entry
                                      :sha256 (util/sha256-bytes pdb-bytes)))}))
         {:entries base-symbol-entries :pdbs []}
         packages)
        package-entries
        (assoc package-entries nuspec-entry
               (rewrite-dependencies!
                :package (get package-entries nuspec-entry)
                target-framework dependencies))
        symbol-entries
        (assoc (:entries pdbs-and-entries) nuspec-entry
               (rewrite-dependencies!
                :symbols (get (:entries pdbs-and-entries) nuspec-entry)
                target-framework dependencies))
        proof-root (:proof-root package-result)
        bundle-root (paths/resolve-path proof-root "bundle")
        first-root (paths/resolve-path bundle-root "first")
        second-root (paths/resolve-path bundle-root "second")
        package-filename (str package-id "." version ".nupkg")
        symbol-filename (str package-id "." version ".snupkg")
        first-package (write-archive! (paths/resolve-path first-root package-filename)
                                      package-entries)
        second-package (write-archive! (paths/resolve-path second-root package-filename)
                                       package-entries)
        first-symbol (write-archive! (paths/resolve-path first-root symbol-filename)
                                     symbol-entries)
        second-symbol (write-archive! (paths/resolve-path second-root symbol-filename)
                                      symbol-entries)
        package-sha256 (util/sha256-file first-package)
        symbol-sha256 (util/sha256-file first-symbol)
        _
        (when-not (and (= #{version} versions)
                       (= 1 (count target-frameworks))
                       (= package-sha256 (util/sha256-file second-package))
                       (= symbol-sha256 (util/sha256-file second-symbol)))
          (fail! "NuGet bundle inputs or deterministic outputs disagree"
                 {:versions (vec (sort versions))
                  :target-frameworks (vec (sort target-frameworks))
                  :package-sha256 [package-sha256
                                   (util/sha256-file second-package)]
                  :symbol-sha256 [symbol-sha256
                                  (util/sha256-file second-symbol)]}))
        feed (:feed package-result)
        artifact (paths/resolve-path feed package-filename)
        symbol-artifact (paths/resolve-path feed symbol-filename)
        _ (Files/copy first-package artifact
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))
        _ (Files/copy first-symbol symbol-artifact
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))
        profile-destination
        (get-in plan [:contract :profiles profile :destination :configuration])
        bundle-package
        (-> base
            (assoc :profile profile
                   :artifact artifact
                   :destination profile-destination
                   :identity
                   {:id package-id
                    :version version
                    :sha256 package-sha256
                    :file package-filename}
                   :symbol-artifact symbol-artifact
                   :symbol
                   {:id package-id
                    :version version
                    :sha256 symbol-sha256
                    :file symbol-filename}
                   :inspection
                   (assoc (:inspection base)
                          :dependencies dependencies
                          :entries (vec (keys package-entries))
                          :assembly-entries
                          (mapv #(get-in % [:inspection :assembly-entry]) packages))
                   :symbol-inspection
                   {:pdbs (:pdbs pdbs-and-entries)}))
        package-proof
        {:packages [bundle-package]
         :external-packages external-packages
         :feed feed
         :proof-root proof-root}
        consumer-proof
        (consumer-fn
         (cond->
          {:workspace-root workspace-root
           :package-proof package-proof
           :consumer-name "public-bundle"
           :consumer-profile (:package-consumer profile-destination)
           :selected-packages [{:id package-id :version version}]
           :expected-packages
           (into [{:id package-id :version version}]
                 (map #(select-keys % [:id :version]) external-packages))
           :target-framework target-framework}
           run-command! (assoc :run-command! run-command!)))]
    (println "Single-package NuGet bundle consumption passed:"
             (pr-str {:id package-id
                      :version version
                      :assemblies (count packages)
                      :pdbs (count (:pdbs pdbs-and-entries))}))
    {:packages [bundle-package]
     :consumer-proof consumer-proof}))
