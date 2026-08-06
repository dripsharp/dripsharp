(ns dripsharp.nuget-release-publisher
  "Fail-closed local publication of a proved NuGet release manifest.

  Dry-run is the default. Live publication requires independent authorization,
  a target-approved HTTPS source, and an API key supplied through the process
  environment. Neither plans nor results contain credential values."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.nuget-release-preparation :as preparation]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.io ByteArrayInputStream]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.util.zip ZipEntry ZipFile]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Element Node]))

(def credential-environment-variable "NUGET_API_KEY")
(def symbol-credential-environment-variable "NUGET_SYMBOL_API_KEY")
(def default-timeout-seconds 300)

(def ^:private manifest-keys
  #{:configuration :kind :network-mutations :package-count :packages
    :product-count :products :publication-credentials-accepted :publish-order
    :schema-version :selection})
(def ^:private product-keys
  #{:package-ids :product-commit :product-family :proof-ladders
    :repository-url :source-commit :source-repository :target})
(def ^:private package-keys
  #{:dependencies :files :id :product-commit :product-family :profile
    :publish-order :source-commit :symbol-pairing :target :target-framework
    :version})
(def ^:private dependency-keys #{:id :version})
(def ^:private artifact-keys #{:filename :sha256})
(def ^:private absent-symbol-keys #{:status})
(def ^:private paired-symbol-keys
  #{:package-filename :pdb-entry :pdb-sha256 :status :symbol-filename})
(def ^:private forbidden-option-keys
  #{:api-key :credential :credentials :nuget-api-key :password :secret :token})
(def ^:private sha256-pattern #"[0-9a-f]{64}")
(def ^:private commit-pattern #"[0-9a-f]{40}|[0-9a-f]{64}")
(def ^:private package-id-pattern
  #"(?=.{1,100}$)[A-Za-z0-9_](?:[A-Za-z0-9._-]*[A-Za-z0-9_])?")
(def ^:private version-pattern
  #"(?:0|[1-9][0-9]*)(?:[.](?:0|[1-9][0-9]*)){2}(?:-[0-9A-Za-z-]+(?:[.][0-9A-Za-z-]+)*)?(?:[+][0-9A-Za-z-]+(?:[.][0-9A-Za-z-]+)*)?")

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :nuget-release-publish-failed))))

(defn- exact-keys!
  [label expected value]
  (when-not (and (map? value) (= expected (set (keys value))))
    (fail! (str label " has an inexact schema")
           {:label label
            :expected-keys (vec (sort expected))
            :actual-key-count (when (map? value) (count value))}))
  value)

(defn- valid-package-id?
  [value]
  (and (string? value)
       (boolean (re-matches package-id-pattern value))
       (not (re-find #"[.-]{2}" value))))

(defn- valid-version?
  [value]
  (and (string? value)
       (boolean (re-matches version-pattern value))))

(defn- exact-dependencies!
  [package-id dependencies]
  (when-not (vector? dependencies)
    (fail! "NuGet manifest dependencies are not a vector"
           {:package package-id}))
  (doseq [dependency dependencies]
    (exact-keys! "NuGet dependency" dependency-keys dependency)
    (when-not (and (valid-package-id? (:id dependency))
                   (valid-version? (:version dependency)))
      (fail! "NuGet manifest contains a malformed dependency edge"
             {:package package-id})))
  (let [ids (mapv (comp str/lower-case :id) dependencies)]
    (when-not (= (count ids) (count (distinct ids)))
      (fail! "NuGet manifest contains duplicate dependency edges"
             {:package package-id})))
  (let [canonical (->> dependencies
                       (sort-by (juxt :id :version))
                       (mapv #(sorted-map :id (:id %) :version (:version %))))]
    (when-not (= canonical dependencies)
      (fail! "NuGet manifest dependency edges are not canonical"
             {:package package-id}))
    canonical))

(defn- topological-profile-order!
  [contract]
  (let [profiles (:profiles contract)
        selected (set (keys (get-in contract [:publication :profile-projects])))
        dependencies
        (into
         (sorted-map)
         (for [profile-id selected]
           [profile-id
            (set (get-in profiles
                         [profile-id :configuration :dependency-profiles]))]))]
    (doseq [[profile-id required] dependencies]
      (when-not (set/subset? required selected)
        (fail! "Target publication profile has an unavailable dependency"
               {:target (:target contract)
                :profile profile-id
                :missing (vec (sort (set/difference required selected)))})))
    (loop [remaining dependencies result []]
      (if (empty? remaining)
        result
        (let [ready (->> remaining
                         (keep (fn [[profile-id required]]
                                 (when (empty? required) profile-id)))
                         sort
                         vec)]
          (when (empty? ready)
            (fail! "Target publication profile dependency graph contains a cycle"
                   {:target (:target contract)}))
          (let [ready-set (set ready)]
            (recur
             (into
              (sorted-map)
              (for [[profile-id required] remaining
                    :when (not (contains? ready-set profile-id))]
                [profile-id (set/difference required ready-set)]))
             (into result ready))))))))

(defn- expected-profile-records!
  [contract]
  (let [profiles (:profiles contract)
        catalog (get-in contract [:publication :nuget :packages])
        profile-order (topological-profile-order! contract)
        records
        (mapv
         (fn [profile-id]
           (let [profile (get profiles profile-id)
                 destination (get-in profile [:destination :configuration])
                 package-id (get-in destination [:package :id])
                 version (get-in catalog [package-id :version])
                 dependency-profiles
                 (get-in profile [:configuration :dependency-profiles])
                 internal
                 (for [dependency-profile dependency-profiles]
                   (let [dependency-id
                         (get-in profiles
                                 [dependency-profile :destination
                                  :configuration :package :id])]
                     {:id dependency-id
                      :version (get-in catalog [dependency-id :version])}))
                 external
                 (map #(select-keys % [:id :version])
                      (:runtime-packages destination))
                 dependencies
                 (->> (concat internal external)
                      (sort-by (juxt :id :version))
                      (mapv #(sorted-map :id (:id %) :version (:version %))))]
             (when-not (and (valid-package-id? package-id)
                            (valid-version? version))
               (fail! "Target publication catalog has a malformed identity"
                      {:target (:target contract)
                       :profile profile-id
                       :package package-id
                       :version version}))
             (exact-dependencies! package-id dependencies)
             {:dependencies dependencies
              :id package-id
              :product-family (:product-family contract)
              :profile profile-id
              :source-commit
              (get-in contract [:baseline :record :upstream :revision])
              :symbols? (= :snupkg (get-in destination [:package :symbols]))
              :target (:target contract)
              :target-framework (get-in destination [:project :target-framework])
              :version version}))
         profile-order)
        ids (mapv (comp str/lower-case :id) records)]
    (when-not (= (count ids) (count (distinct ids)))
      (fail! "Selected target catalogs contain duplicate NuGet identities"
             {:targets (mapv :target records)}))
    records))

(defn- selected-contracts!
  [root selection discover-products-fn read-target-fn]
  (let [available
        (discover-products-fn {:workspace-root root
                               :read-target-fn read-target-fn})
        by-id (into {} (map (juxt (comp name :target) identity)) available)
        selected
        (if (= "all" selection)
          (mapv by-id (sort (keys by-id)))
          (if-let [contract (get by-id selection)]
            [contract]
            (fail! "NuGet release manifest selects an unavailable product"
                   {:available (into ["all"] (sort (keys by-id)))})))]
    (when-not (seq selected)
      (fail! "NuGet release manifest selects no product contracts" {}))
    selected))

(defn- validate-product-records!
  [manifest contracts]
  (let [products (:products manifest)
        targets (mapv :target contracts)]
    (when-not (and (vector? products)
                   (= (:product-count manifest) (count products))
                   (= targets (mapv :target products)))
      (fail! "NuGet release manifest product inventory is incomplete or unordered"
             {:expected-targets targets
              :actual-product-count (when (vector? products) (count products))
              :declared-count (:product-count manifest)}))
    (doseq [[product contract] (map vector products contracts)]
      (exact-keys! "NuGet release product" product-keys product)
      (let [profile-records (expected-profile-records! contract)
            expected
            {:package-ids (mapv :id profile-records)
             :product-family (:product-family contract)
             :proof-ladders (mapv :id (get-in contract [:proof :ladders]))
             :repository-url (get-in contract [:publication :repository-url])
             :source-commit
             (get-in contract [:baseline :record :upstream :revision])
             :source-repository
             (get-in contract [:baseline :record :upstream :repository])
             :target (:target contract)}]
        (when-not (and (= expected (dissoc product :product-commit))
                       (re-matches commit-pattern
                                   (or (:product-commit product) "")))
          (fail! "NuGet release product does not match its proved target contract"
                 {:target (:target contract)}))))
    products))

(defn- manifest-path!
  [root manifest]
  (when-not manifest
    (fail! "NuGet release publication requires a manifest path" {}))
  (let [root (paths/absolute root)
        base (paths/absolute (paths/resolve-path root "target" "nuget-release"))
        manifest (paths/absolute manifest)]
    (when-not (and (.startsWith manifest base)
                   (paths/regular-file? manifest)
                   (= "release-manifest.edn" (str (.getFileName manifest)))
                   (paths/real-contained? base manifest))
      (fail! "NuGet release manifest is outside the proved artifact boundary"
             {:reason :outside-proved-artifact-boundary}))
    (doseq [^Path candidate
            (take-while #(and % (.startsWith ^Path % root))
                        (iterate #(.getParent ^Path %) manifest))]
      (when (Files/isSymbolicLink candidate)
        (fail! "NuGet release manifest path contains a symbolic link"
               {:reason :symbolic-link})))
    manifest))

(defn- read-manifest!
  [^Path manifest]
  (try
    (util/read-single-edn-string!
     (Files/readString manifest StandardCharsets/UTF_8))
    (catch RuntimeException _
      (fail! "NuGet release manifest is not exact EDN"
             {:reason :invalid-edn}))))

(defn- validate-manifest-header!
  [manifest]
  (exact-keys! "NuGet release manifest" manifest-keys manifest)
  (when-not (and (= preparation/schema-version (:schema-version manifest))
                 (= :credential-free-nuget-release-preparation
                    (:kind manifest))
                 (= "Release" (:configuration manifest))
                 (= [] (:network-mutations manifest))
                 (false? (:publication-credentials-accepted manifest))
                 (string? (:selection manifest))
                 (or (= "all" (:selection manifest))
                     (boolean (re-matches #"[a-z][a-z0-9-]*"
                                          (:selection manifest)))))
    (fail! "NuGet release manifest does not record a successful credential-free proof"
           {:reason :invalid-proof-marker}))
  manifest)

(defn- expected-manifest-path!
  [root selection ^Path manifest]
  (let [expected
        (paths/absolute
         (paths/resolve-path root "target" "nuget-release" selection
                             "release-manifest.edn"))]
    (when-not (= expected manifest)
      (fail! "NuGet release manifest is not at its deterministic proved path"
             {:reason :nondeterministic-manifest-path}))))

(defn- directory-entry-names!
  [^Path directory]
  (with-open [entries (Files/list directory)]
    (let [paths (mapv #(cast Path %) (.toArray entries))]
      (doseq [^Path path paths]
        (when-not (and (paths/regular-file? path)
                       (not (Files/isSymbolicLink path)))
          (fail! "NuGet release artifact directory contains a non-regular entry"
                 {:reason :non-regular-entry})))
      (mapv #(str (.getFileName ^Path %)) paths))))

(defn- artifact-path!
  [^Path directory package-id kind {:keys [filename sha256] :as artifact}]
  (exact-keys! "NuGet release artifact" artifact-keys artifact)
  (let [extension (case kind :package ".nupkg" :symbols ".snupkg")
        path (paths/absolute (paths/resolve-path directory filename))]
    (when-not (and (string? filename)
                   (= filename (str (.getFileName (paths/path filename))))
                   (= filename (str (paths/path filename)))
                   (str/ends-with? (str/lower-case filename) extension)
                   (re-matches sha256-pattern (or sha256 ""))
                   (= (.getParent path) (paths/absolute directory))
                   (paths/regular-file? path)
                   (not (Files/isSymbolicLink path))
                   (paths/real-contained? directory path))
      (fail! "NuGet release artifact path or digest is invalid"
             {:package package-id :kind kind}))
    (let [actual (util/sha256-file path)]
      (when-not (= sha256 actual)
        (fail! "NuGet release artifact SHA-256 does not match the proved manifest"
               {:package package-id :kind kind
                :expected sha256 :actual actual})))
    path))

(defn- validate-zip-entry-layout!
  [package-id entries]
  (let [names (mapv #(.getName ^ZipEntry %) entries)
        unsafe
        (filterv
         (fn [name]
           (let [segments (str/split name #"/" -1)]
             (or (str/blank? name)
                 (str/starts-with? name "/")
                 (str/includes? name "\\")
                 (some #{"." ".."} segments))))
         names)
        duplicates (->> names frequencies
                        (keep (fn [[name count]] (when (< 1 count) name)))
                        vec)
        case-collisions
        (->> names
             (group-by str/lower-case)
             vals
             (filter #(< 1 (count (distinct %))))
             vec)]
    (when (or (seq unsafe) (seq duplicates) (seq case-collisions))
      (fail! "NuGet release artifact contains unsafe or ambiguous ZIP entries"
             {:package package-id
              :unsafe-count (count unsafe)
              :duplicate-count (count duplicates)
              :case-collision-count (count case-collisions)}))
    names))

(defn- parse-xml!
  [package-id xml]
  (try
    (let [factory (doto (DocumentBuilderFactory/newInstance)
                    (.setNamespaceAware true)
                    (.setXIncludeAware false)
                    (.setExpandEntityReferences false))]
      (.setFeature factory
                   "http://apache.org/xml/features/disallow-doctype-decl" true)
      (.setFeature factory
                   "http://xml.org/sax/features/external-general-entities" false)
      (.setFeature factory
                   "http://xml.org/sax/features/external-parameter-entities" false)
      (with-open [input
                  (ByteArrayInputStream.
                   (.getBytes ^String xml StandardCharsets/UTF_8))]
        (.parse (.newDocumentBuilder factory) input)))
    (catch Exception _
      (fail! "NuGet release artifact contains invalid nuspec XML"
             {:package package-id}))))

(defn- element-name
  [^Element element]
  (or (.getLocalName element) (.getNodeName element)))

(defn- child-elements
  [^Node parent]
  (let [children (.getChildNodes parent)]
    (->> (range (.getLength children))
         (map #(.item children %))
         (filter #(instance? Element %))
         (map #(cast Element %))
         vec)))

(defn- exactly-one-child!
  [package-id ^Node parent name namespace]
  (let [children (child-elements parent)
        matches (filterv #(= name (element-name %)) children)]
    (when-not (and (= 1 (count matches))
                   (= namespace (.getNamespaceURI ^Element (first matches))))
      (fail! "NuGet release artifact has inexact nuspec metadata"
             {:package package-id :element name}))
    (first matches)))

(defn- zip-entry-text!
  [package-id ^ZipFile archive entry-name]
  (let [entry (.getEntry archive entry-name)]
    (when-not entry
      (fail! "NuGet release artifact is missing its exact nuspec"
             {:package package-id :entry entry-name}))
    (with-open [input (.getInputStream archive entry)]
      (String. (.readAllBytes input) StandardCharsets/UTF_8))))

(defn- inspect-nuspec!
  [package-id ^ZipFile archive entry-name expected]
  (let [document (parse-xml! package-id
                             (zip-entry-text! package-id archive entry-name))
        root (.getDocumentElement document)
        namespace (.getNamespaceURI root)]
    (when-not (and (= "package" (element-name root))
                   (contains?
                    #{"http://schemas.microsoft.com/packaging/2012/06/nuspec.xsd"
                      "http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd"}
                    namespace))
      (fail! "NuGet release artifact has an invalid nuspec root"
             {:package package-id}))
    (let [metadata
          (exactly-one-child! package-id root "metadata" namespace)
          id-element
          (exactly-one-child! package-id metadata "id" namespace)
          version-element
          (exactly-one-child! package-id metadata "version" namespace)
          dependencies-element
          (exactly-one-child! package-id metadata "dependencies" namespace)
          groups (filterv #(= "group" (element-name %))
                          (child-elements dependencies-element))]
      (when-not (and (= (:id expected) (.getTextContent id-element))
                     (= (:version expected) (.getTextContent version-element))
                     (= 1 (count groups)))
        (fail! "NuGet release artifact identity differs from the proved manifest"
               {:package package-id}))
      (let [group (first groups)
            dependencies
            (->> (child-elements group)
                 (mapv
                  (fn [^Element dependency]
                    (when-not (and (= "dependency" (element-name dependency))
                                   (= namespace (.getNamespaceURI dependency)))
                      (fail! "NuGet release artifact has malformed dependency metadata"
                             {:package package-id}))
                    (sorted-map :id (.getAttribute dependency "id")
                                :version (.getAttribute dependency "version"))))
                 (sort-by (juxt :id :version))
                 vec)]
        (when-not (and (= namespace (.getNamespaceURI ^Element group))
                       (= (:target-framework expected)
                          (.getAttribute ^Element group "targetFramework"))
                       (= (:dependencies expected) dependencies))
          (fail! "NuGet release artifact dependency edges differ from the proved manifest"
                 {:package package-id}))
        dependencies))))

(defn- inspect-artifact-metadata!
  [package package-path symbol-path]
  (let [package-id (:id package)
        nuspec-entry (str package-id ".nuspec")]
    (try
      (with-open [archive (ZipFile. (str package-path))]
        (let [entries (->> (enumeration-seq (.entries archive))
                           (remove #(.isDirectory ^ZipEntry %))
                           vec)
              names (validate-zip-entry-layout! package-id entries)
              nuspecs (filterv #(str/ends-with? (str/lower-case %) ".nuspec")
                               names)]
          (when-not (= [nuspec-entry] nuspecs)
            (fail! "NuGet release package does not contain one exact nuspec"
                   {:package package-id :nuspec-count (count nuspecs)}))
          (inspect-nuspec! package-id archive nuspec-entry package)))
      (catch clojure.lang.ExceptionInfo error
        (throw error))
      (catch Exception _
        (fail! "NuGet release package cannot be inspected"
               {:package package-id})))
    (when symbol-path
      (try
        (with-open [archive (ZipFile. (str symbol-path))]
          (let [entries (->> (enumeration-seq (.entries archive))
                             (remove #(.isDirectory ^ZipEntry %))
                             vec)
                names (validate-zip-entry-layout! package-id entries)
                nuspecs
                (filterv #(str/ends-with? (str/lower-case %) ".nuspec") names)
                pdb-entry (get-in package [:symbol-pairing :pdb-entry])
                pdb-entries
                (filterv #(str/ends-with? (str/lower-case %) ".pdb") names)]
            (when-not (and (= [nuspec-entry] nuspecs)
                           (= [pdb-entry] pdb-entries))
              (fail! "NuGet symbol package has an inexact nuspec or PDB pairing"
                     {:package package-id
                      :nuspec-count (count nuspecs)
                      :pdb-count (count pdb-entries)}))
            (inspect-nuspec! package-id archive nuspec-entry package)
            (let [entry (.getEntry archive pdb-entry)
                  actual
                  (with-open [input (.getInputStream archive entry)]
                    (util/digest-input "SHA-256" input))]
              (when-not (= (get-in package [:symbol-pairing :pdb-sha256]) actual)
                (fail! "NuGet symbol PDB differs from the proved manifest"
                       {:package package-id
                        :pdb-entry pdb-entry
                        :expected
                        (get-in package [:symbol-pairing :pdb-sha256])
                        :actual actual})))))
        (catch clojure.lang.ExceptionInfo error
          (throw error))
        (catch Exception _
          (fail! "NuGet symbol package cannot be inspected"
                 {:package package-id}))))))

(defn- validate-package-record!
  [^Path directory expected product-commit package]
  (exact-keys! "NuGet release package" package-keys package)
  (let [package-id (:id expected)
        package-file (get-in package [:files :package])
        symbols? (:symbols? expected)
        symbol-file (get-in package [:files :symbols])
        expected-file-keys (cond-> #{:package} symbols? (conj :symbols))]
    (exact-keys! "NuGet release package files" expected-file-keys
                 (:files package))
    (when-not (and (= (dissoc expected :symbols? :product-commit)
                      (select-keys package
                                   [:dependencies :id :product-family :profile
                                    :source-commit :target :target-framework
                                    :version]))
                   (= product-commit (:product-commit package))
                   (integer? (:publish-order package))
                   (not (neg? (:publish-order package))))
      (fail! "NuGet release package does not match its target-owned catalog"
             {:package package-id}))
    (exact-dependencies! package-id (:dependencies package))
    (let [expected-package-filename
          (str package-id "." (:version expected) ".nupkg")
          package-path (artifact-path! directory package-id :package package-file)
          expected-symbol-filename
          (str package-id "." (:version expected) ".snupkg")
          symbol-path
          (when symbols?
            (artifact-path! directory package-id :symbols symbol-file))]
      (when-not (= expected-package-filename (:filename package-file))
        (fail! "NuGet release package filename does not match its identity"
               {:package package-id}))
      (if symbols?
        (do
          (exact-keys! "NuGet symbol pairing" paired-symbol-keys
                       (:symbol-pairing package))
          (when-not
           (and (= expected-symbol-filename (:filename symbol-file))
                (= :paired (get-in package [:symbol-pairing :status]))
                (= expected-package-filename
                   (get-in package [:symbol-pairing :package-filename]))
                (= expected-symbol-filename
                   (get-in package [:symbol-pairing :symbol-filename]))
                (= (str "lib/" (:target-framework expected) "/"
                        package-id ".pdb")
                   (get-in package [:symbol-pairing :pdb-entry]))
                (re-matches
                 sha256-pattern
                 (or (get-in package [:symbol-pairing :pdb-sha256]) "")))
            (fail! "NuGet release symbol pairing is inconsistent"
                   {:package package-id})))
        (do
          (exact-keys! "NuGet absent symbol record" absent-symbol-keys
                       (:symbol-pairing package))
          (when-not (= {:status :absent} (:symbol-pairing package))
            (fail! "NuGet release package has an invalid absent-symbol record"
                   {:package package-id}))))
      (inspect-artifact-metadata! package package-path symbol-path)
      (assoc package :package-path package-path :symbol-path symbol-path))))

(defn- topological-publish-order!
  [packages]
  (let [package-ids (set (map :id packages))
        versions (into {} (map (juxt :id :version)) packages)
        dependencies
        (into
         (sorted-map)
         (for [package packages]
           [(:id package)
            (into
             #{}
             (keep
              (fn [{:keys [id version]}]
                (when (contains? package-ids id)
                  (when-not (= (get versions id) version)
                    (fail! "Internal NuGet dependency version is not exact"
                           {:package (:id package) :dependency id
                            :expected (get versions id) :actual version}))
                  id)))
             (:dependencies package))]))]
    (loop [remaining dependencies result []]
      (if (empty? remaining)
        result
        (let [ready (->> remaining
                         (keep (fn [[package-id required]]
                                 (when (empty? required) package-id)))
                         sort
                         vec)]
          (when (empty? ready)
            (fail! "NuGet release dependency graph contains a cycle" {}))
          (let [ready-set (set ready)]
            (recur
             (into
              (sorted-map)
              (for [[package-id required] remaining
                    :when (not (contains? ready-set package-id))]
                [package-id (set/difference required ready-set)]))
             (into result ready))))))))

(defn- validate-packages!
  [^Path directory manifest products contracts]
  (let [product-by-target (into {} (map (juxt :target identity)) products)
        expected
        (->> contracts
             (mapcat expected-profile-records!)
             (map (fn [record]
                    [(:id record)
                     (assoc record
                            :product-commit
                            (:product-commit
                             (get product-by-target (:target record))))]))
             (into {}))
        packages (:packages manifest)
        ids (when (vector? packages) (mapv :id packages))]
    (when-not (and (vector? packages)
                   (= (:package-count manifest) (count packages))
                   (= (set (keys expected)) (set ids))
                   (= (count ids) (count (distinct (map str/lower-case ids)))))
      (fail! "NuGet release manifest package inventory is incomplete"
             {:expected (vec (sort (keys expected)))
              :actual-package-count (count ids)
              :declared-count (:package-count manifest)}))
    (let [validated
          (mapv
           (fn [package]
             (let [record (get expected (:id package))]
               (validate-package-record! directory record
                                         (:product-commit record) package)))
           packages)
          order (topological-publish-order! validated)]
      (when-not (and (= order (:publish-order manifest))
                     (= order (mapv :id validated))
                     (= (vec (range (count validated)))
                        (mapv :publish-order validated)))
        (fail! "NuGet release manifest publish order is not the exact dependency order"
               {:expected order}))
      validated)))

(defn- validate-directory-inventory!
  [^Path directory packages]
  (let [expected
        (->> packages
             (mapcat
              (fn [package]
                (cond-> [(get-in package [:files :package :filename])]
                  (:symbol-path package)
                  (conj (get-in package [:files :symbols :filename])))))
             (into #{"release-manifest.edn"}))
        actual (set (directory-entry-names! directory))]
    (when-not (= expected actual)
      (fail! "NuGet release artifact directory inventory differs from its manifest"
             {:expected-count (count expected)
              :actual-count (count actual)}))))

(defn- https-source!
  [source allowed-sources]
  (let [uri
        (try
          (URI. (str source))
          (catch Exception _ nil))]
    (when-not (and (string? source)
                   uri
                   (= "https" (some-> (.getScheme uri) str/lower-case))
                   (not (str/blank? (.getHost uri)))
                   (nil? (.getUserInfo uri))
                   (nil? (.getQuery uri))
                   (nil? (.getFragment uri))
                   (contains? allowed-sources source))
      (fail! "Live NuGet publication requires a target-allowlisted HTTPS source"
             {:allowed-sources (vec (sort allowed-sources))}))
    source))

(defn- source-contract!
  [contracts]
  (let [sources (set (map #(get-in % [:publication :nuget :source]) contracts))]
    (when-not (and (= 1 (count sources))
                   (string? (first sources)))
      (fail! "Selected target contracts do not agree on one NuGet source"
             {:source-count (count sources)}))
    (https-source! (first sources) sources)))

(defn- push-command
  [package source timeout-seconds]
  (cond-> ["dotnet" "nuget" "push" (str (:package-path package))
           "--source" source
           "--timeout" (str timeout-seconds)
           "--force-english-output"]
    (nil? (:symbol-path package)) (conj "--no-symbols")))

(defn- publish-plan
  [manifest-file manifest-sha256 packages source timeout-seconds]
  {:credential-channel credential-environment-variable
   :duplicate-version-policy :fail-closed
   :manifest (str manifest-file)
   :manifest-sha256 manifest-sha256
   :mode :dry-run
   :source source
   :steps
   (mapv
    (fn [position package]
      {:command (push-command package source timeout-seconds)
       :dependencies (:dependencies package)
       :id (:id package)
       :package
       {:path (str (:package-path package))
        :sha256 (get-in package [:files :package :sha256])}
       :position position
       :symbols
       (if-let [symbol-path (:symbol-path package)]
         {:path (str symbol-path)
          :pdb-entry (get-in package [:symbol-pairing :pdb-entry])
          :pdb-sha256 (get-in package [:symbol-pairing :pdb-sha256])
          :sha256 (get-in package [:files :symbols :sha256])
          :status :paired}
         {:status :absent})
       :version (:version package)})
    (range)
    packages)
   :timeout-seconds timeout-seconds})

(defn- credential!
  [credential-fn]
  (let [credential (credential-fn credential-environment-variable)]
    (when-not (and (string? credential)
                   (not (str/blank? credential))
                   (<= (count credential) 4096)
                   (not-any? #(or (Character/isISOControl ^char %)
                                  (= \newline %)
                                  (= \return %))
                             credential))
      (fail! "Live NuGet publication requires a valid non-logging credential channel"
             {:credential-channel credential-environment-variable}))
    credential))

(defn- duplicate-conflict?
  [error]
  (let [data (ex-data error)
        output (str (:output data) " " (ex-message error))]
    (boolean
     (or (= 409 (:status data))
         (= 409 (:status-code data))
         (re-find #"(?i)(?:\b409\b|conflict|already exists|already been pushed)"
                  output)))))

(defn- push-outcome
  [push-fn request]
  (try
    (let [result (push-fn request)]
      (if (and (map? result) (zero? (:exit result)))
        {:status :published}
        {:status (if (re-find #"(?i)(?:\b409\b|conflict|already exists|already been pushed)"
                              (str (:output result)))
                   :duplicate-version-conflict
                   :error)}))
    (catch Throwable error
      {:status
       (cond
         (= :command-timeout (:kind (ex-data error))) :timeout
         (duplicate-conflict? error) :duplicate-version-conflict
         :else :error)})))

(defn- step-summary
  [step]
  (select-keys step [:id :position :version]))

(defn- publish-live!
  [plan credential push-fn directory]
  (loop [remaining (:steps plan) completed []]
    (if-let [step (first remaining)]
      (let [request
            {:command (:command step)
             :directory directory
             :environment
             {credential-environment-variable credential
              symbol-credential-environment-variable credential}
             :timeout-ms (* 1000 (+ 30 (:timeout-seconds plan)))}
            outcome (push-outcome push-fn request)]
        (if (= :published (:status outcome))
          (recur (next remaining) (conj completed (step-summary step)))
          (fail! "NuGet publication stopped after a package push failure"
                 {:completed completed
                  :failed (assoc (step-summary step) :remote-state :unknown)
                  :failure (:status outcome)
                  :remaining (mapv step-summary (next remaining))
                  :source (:source plan)})))
      (let [result
            {:completed completed
             :manifest (:manifest plan)
             :manifest-sha256 (:manifest-sha256 plan)
             :mode :live
             :source (:source plan)}]
        (println "NuGet publication completed:" (pr-str result))
        result))))

(defn publish!
  "Validates and plans a proved release manifest. Dry-run is the default.

  Live mode additionally requires `:live? true`, `:authorized? true`, one
  explicit target-approved `:source`, and the API key in `NUGET_API_KEY`.
  Dependency-injection hooks exist only for offline tests."
  ([] (publish! {}))
  ([{:keys [authorized? credential-fn discover-products-fn live? manifest
            push-fn read-target-fn source timeout-seconds workspace-root]
     :or {credential-fn (fn [name] (System/getenv name))
          discover-products-fn preparation/discover-products!
          push-fn process/run!
          read-target-fn target-directory/read-target
          timeout-seconds default-timeout-seconds}
     :as options}]
   (let [credential-options
         (set/intersection forbidden-option-keys (set (keys options)))]
     (when (seq credential-options)
       (fail! "NuGet publication credentials are accepted only through the environment"
              {:forbidden-options (vec (sort credential-options))})))
   (when-not (and (integer? timeout-seconds)
                  (<= 1 timeout-seconds 3600))
     (fail! "NuGet publication timeout must be between 1 and 3600 seconds"
            {:timeout-seconds timeout-seconds}))
   (when-not (or (nil? live?) (true? live?) (false? live?))
     (fail! "NuGet publication mode is malformed" {}))
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         manifest-file (manifest-path! root manifest)
         manifest-data (validate-manifest-header! (read-manifest! manifest-file))
         selection (:selection manifest-data)
         _ (expected-manifest-path! root selection manifest-file)
         contracts
         (selected-contracts! root selection discover-products-fn read-target-fn)
         products (validate-product-records! manifest-data contracts)
         directory (.getParent manifest-file)
         packages
         (validate-packages! directory manifest-data products contracts)
         _ (validate-directory-inventory! directory packages)
         approved-source (source-contract! contracts)
         effective-source
         (if live?
           (do
             (when-not (true? authorized?)
               (fail! "Live NuGet publication requires --authorize-publish"
                      {:authorization :missing}))
             (when-not source
               (fail! "Live NuGet publication requires an explicit source"
                      {:authorization :missing-source}))
             (https-source! source #{approved-source}))
           (do
             (when (or authorized? source)
               (fail! "Dry-run publication does not accept live authorization options"
                      {:mode :dry-run}))
             approved-source))
         manifest-sha256 (util/sha256-file manifest-file)
         plan (publish-plan manifest-file manifest-sha256 packages
                            effective-source timeout-seconds)]
     (if live?
       (let [credential (credential! credential-fn)]
         (publish-live! (assoc plan :mode :live) credential push-fn directory))
       (do
         (println "NuGet publication dry-run plan:" (pr-str plan))
         plan)))))
