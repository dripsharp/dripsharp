(ns dripsharp.pdfcube.family-packaging
  "Deterministic NuGet, legal, native-asset, symbol, and fresh isolated
  consumption gate for the complete five-package PdfCarton family."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files Path]
           [java.util.zip ZipFile]))

(def ^:private source-revision
  (baseline/upstream-revision :pdfcube))

(defn- package-version
  [package-id]
  (baseline/package-version :pdfcube package-id))

(defn- assembly-version
  [package-id]
  (baseline/assembly-version :pdfcube package-id))

(def ^:private target-framework
  "net10.0")

(def ^:private package-copyright
  "Portions Copyright The Apache Software Foundation and other upstream contributors; see NOTICE.txt.")

(def ^:private common-legal-files
  (baseline/package-legal-files :pdfcube [:upstream]))

(def ^:private codec-legal-files
  (baseline/package-legal-files :pdfcube [:codecs]))

(def ^:private package-contract
  {"DripSharp.PdfCarton.IO"
   {:profile "pdfcube-io"
    :primary? false
    :assembly-dependencies []
    :dependencies
    [{:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}]
    :resources 0
    :package-files common-legal-files}

   "DripSharp.PdfCarton.Fonts"
   {:profile "pdfcube-fontbox"
    :primary? false
    :assembly-dependencies ["DripSharp.PdfCarton.IO"]
    :dependencies
    [{:id "DripSharp.PdfCarton.IO" :version (package-version "DripSharp.PdfCarton.IO")}
     {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
     {:id "SkiaSharp" :version "4.150.1"}
     {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}]
    :resources 93
    :package-files common-legal-files}

   "DripSharp.PdfCarton.Xmp"
   {:profile "pdfcube-xmpbox"
    :primary? false
    :assembly-dependencies []
    :dependencies
    [{:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}]
    :resources 0
    :package-files common-legal-files}

   "DripSharp.PdfCarton"
   {:profile "pdfcube-pdfbox"
    :primary? false
    :assembly-dependencies ["DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.IO"]
    :dependencies
    [{:id "DripSharp.PdfCarton.Fonts" :version (package-version "DripSharp.PdfCarton.Fonts")}
     {:id "DripSharp.PdfCarton.IO" :version (package-version "DripSharp.PdfCarton.IO")}
     {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
     {:id "SkiaSharp" :version "4.150.1"}
     {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}]
    :resources 22
    :package-files (into common-legal-files codec-legal-files)}

   "DripSharp.PdfCarton.Preflight"
   {:profile "pdfcube-preflight"
    :primary? true
    :assembly-dependencies
    ["DripSharp.PdfCarton" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Xmp"]
    :dependencies
    [{:id "DripSharp.PdfCarton.Xmp" :version (package-version "DripSharp.PdfCarton.Xmp")}
     {:id "DripSharp.PdfCarton" :version (package-version "DripSharp.PdfCarton")}
     {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
     {:id "SkiaSharp" :version "4.150.1"}]
    :resources 0
    :package-files common-legal-files}})

(def ^:private external-package-contract
  #{{:id "Microsoft.Extensions.DependencyInjection.Abstractions"
     :version "10.0.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "SkiaSharp" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.macOS" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Win32" :version "4.150.1"}
    {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}})

(def ^:private restored-closures
  {"DripSharp.PdfCarton.IO"
   #{"DripSharp.PdfCarton.IO"
     "Microsoft.Extensions.DependencyInjection.Abstractions"
     "Microsoft.Extensions.Logging.Abstractions"}

   "DripSharp.PdfCarton.Fonts"
   #{"DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"
     "Microsoft.Extensions.DependencyInjection.Abstractions"
     "Microsoft.Extensions.Logging.Abstractions"
     "SkiaSharp" "SkiaSharp.NativeAssets.Linux"
     "SkiaSharp.NativeAssets.macOS" "SkiaSharp.NativeAssets.Win32"}

   "DripSharp.PdfCarton.Xmp"
   #{"DripSharp.PdfCarton.Xmp"
     "Microsoft.Extensions.DependencyInjection.Abstractions"
     "Microsoft.Extensions.Logging.Abstractions"}

   "DripSharp.PdfCarton"
   #{"DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton"
     "Microsoft.Extensions.DependencyInjection.Abstractions"
     "Microsoft.Extensions.Logging.Abstractions"
     "SkiaSharp" "SkiaSharp.NativeAssets.Linux"
     "SkiaSharp.NativeAssets.macOS" "SkiaSharp.NativeAssets.Win32"
     "System.Security.Cryptography.Pkcs"}

   "DripSharp.PdfCarton.Preflight"
   #{"DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.Xmp"
     "DripSharp.PdfCarton" "DripSharp.PdfCarton.Preflight"
     "Microsoft.Extensions.DependencyInjection.Abstractions"
     "Microsoft.Extensions.Logging.Abstractions"
     "SkiaSharp" "SkiaSharp.NativeAssets.Linux"
     "SkiaSharp.NativeAssets.macOS" "SkiaSharp.NativeAssets.Win32"
     "System.Security.Cryptography.Pkcs"}})

(def ^:private aggregate-consumer
  {:strategy :source-file
   :project-file "PdfCarton.Family.PackageConsumer.csproj"
   :source-path
   "validation/pdfcube-family/PdfCarton.Family.FocusedConsumer.cs"
   :success-message
   "Complete PdfCarton package family runtime workflow passed."})

(def ^:private native-assets
  {"SkiaSharp.NativeAssets.Linux"
   #{"runtimes/linux-x64/native/libSkiaSharp.so"
     "runtimes/linux-arm64/native/libSkiaSharp.so"}
   "SkiaSharp.NativeAssets.Win32"
   #{"runtimes/win-x64/native/libSkiaSharp.dll"
     "runtimes/win-arm64/native/libSkiaSharp.dll"}
   "SkiaSharp.NativeAssets.macOS"
   #{"runtimes/osx/native/libSkiaSharp.dylib"}})

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-family-packaging-failed))))

(def ^:private sha256-file util/sha256-file)

(defn- regular-files
  [^Path directory]
  (if-not (paths/directory? directory)
    []
    (with-open [entries (Files/list directory)]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (sort-by str)
           vec))))

(defn- zip-entry-names
  [artifact]
  (with-open [archive (ZipFile. (str artifact))]
    (->> (enumeration-seq (.entries archive))
         (remove #(.isDirectory ^java.util.zip.ZipEntry %))
         (map #(.getName ^java.util.zip.ZipEntry %))
         set)))

(defn- zip-entry-bytes
  [artifact entry-name]
  (with-open [archive (ZipFile. (str artifact))]
    (when-let [entry (.getEntry archive entry-name)]
      (with-open [input (.getInputStream archive entry)]
        (.readAllBytes input)))))

(defn- macos-architectures
  [bytes]
  (when (< (alength ^bytes bytes) 8)
    (fail! "SkiaSharp macOS native asset is truncated"
           {:bytes (alength ^bytes bytes)}))
  (let [buffer (doto (ByteBuffer/wrap bytes)
                 (.order ByteOrder/BIG_ENDIAN))
        magic (Integer/toUnsignedLong (.getInt buffer))
        count (Integer/toUnsignedLong (.getInt buffer))]
    (when-not (= 0xcafebabe magic)
      (fail! "SkiaSharp macOS native asset is not a universal Mach-O binary"
             {:expected "cafebabe" :actual (format "%08x" magic)}))
    (when (or (zero? count) (> count 32)
              (< (.remaining buffer) (* count 20)))
      (fail! "SkiaSharp macOS universal binary has an invalid architecture table"
             {:architectures count :remaining (.remaining buffer)}))
    (into
     #{}
     (for [_ (range count)]
       (let [cpu (Integer/toUnsignedLong (.getInt buffer))]
         (.position buffer (+ (.position buffer) 16))
         cpu)))))

(defn inspect-native-assets!
  "Requires the official SkiaSharp host packages and the x64/ARM64 payloads
  needed by PdfCarton's Windows, Linux, and macOS platform contract."
  [feed external-packages]
  (let [by-id (into {} (map (juxt :id identity)) external-packages)]
    (into
     (sorted-map)
     (for [[id required] native-assets
           :let [package (get by-id id)
                 _ (when-not (= "4.150.1" (:version package))
                     (fail! "Required SkiaSharp native-assets package is missing or wrong"
                            {:id id :expected "4.150.1"
                             :actual (some-> package :version)}))
                 artifact (paths/resolve-path feed (:file package))
                 entries (zip-entry-names artifact)
                 missing (set/difference required entries)
                 _ (when (seq missing)
                     (fail! "SkiaSharp native-assets package is missing a supported host payload"
                            {:id id :missing (vec (sort missing))}))
                 architectures
                 (when (= id "SkiaSharp.NativeAssets.macOS")
                   (macos-architectures
                    (zip-entry-bytes
                     artifact "runtimes/osx/native/libSkiaSharp.dylib")))
                 _ (when (and architectures
                              (not (set/subset?
                                    #{0x01000007 0x0100000c}
                                    architectures)))
                     (fail! "SkiaSharp macOS native asset lacks x64 or ARM64"
                            {:expected ["x64" "arm64"]
                             :actual (vec (sort architectures))}))]]
       [id {:required-entries (vec (sort required))
            :architectures
            (when architectures
              ["x64" "arm64"])}]))))

(defn- exact-feed-artifacts!
  [package-proof]
  (let [feed (:feed package-proof)
        packages (:packages package-proof)
        external (:external-packages package-proof)
        identities
        (concat
         (map :identity packages)
         (keep :symbol packages)
         external)
        expected-files (set (map :file identities))
        actual-files
        (set (map #(str (.getFileName ^Path %)) (regular-files feed)))]
    (when-not (= expected-files actual-files)
      (fail! "Fresh PdfCarton local feed contains missing, leaked, or stale artifacts"
             {:expected (vec (sort expected-files))
              :actual (vec (sort actual-files))
              :missing (vec (sort (set/difference expected-files actual-files)))
              :stale (vec (sort (set/difference actual-files expected-files)))}))
    (doseq [{:keys [file sha256]} identities]
      (let [artifact (paths/resolve-path feed file)
            actual (when (paths/regular-file? artifact)
                     (sha256-file artifact))]
        (when-not (= sha256 actual)
          (fail! "Fresh PdfCarton feed artifact differs from its inspected identity"
                 {:file file :expected sha256 :actual actual}))))
    {:artifacts (count identities)
     :files (vec (sort expected-files))}))

(defn- exact-package-contract
  [package]
  (let [id (get-in package [:identity :id])
        expected (get package-contract id)
        destination (:destination package)
        symbol (:symbol package)
        symbol-inspection (:symbol-inspection package)
        actual
        {:profile (:profile package)
         :primary? (boolean (:primary? package))
         :version (get-in package [:identity :version])
         :target-framework (get-in destination [:project :target-framework])
         :assembly
         (get-in package [:resource-proof :assembly-identity])
         :dependencies (get-in package [:inspection :dependencies])
         :resources (count (:resources package))
         :package-files (get-in package [:inspection :package-files])
         :metadata
         (select-keys (:package destination)
                      [:id :version :authors :copyright :symbols
                       :repository-url :repository-type :repository-commit])
         :symbol
         {:id (:id symbol)
          :version (:version symbol)
          :pdb-entry (:pdb-entry symbol-inspection)
          :pdb-sha256 (:pdb-sha256 symbol-inspection)
          :dependencies (:dependencies symbol-inspection)}}]
    (when-not expected
      (fail! "Packed an unapproved PdfCarton package"
             {:id id :approved (vec (sort (keys package-contract)))}))
    (doseq [[subject value]
            [[:package-sha256 (get-in package [:identity :sha256])]
             [:symbol-sha256 (:sha256 symbol)]
             [:pdb-sha256 (:pdb-sha256 symbol-inspection)]]]
      (when-not (re-matches #"[0-9a-f]{64}" (or value ""))
        (fail! "PdfCarton package proof contains an invalid artifact fingerprint"
               {:id id :subject subject :actual value})))
    (let [expected
          {:profile (:profile expected)
           :primary? (:primary? expected)
           :version (package-version id)
           :target-framework target-framework
           :assembly
           {:name id
            :version (assembly-version id)
            :dependency-assemblies (:assembly-dependencies expected)}
           :dependencies (:dependencies expected)
           :resources (:resources expected)
           :package-files (:package-files expected)
           :metadata
           {:id id
            :version (package-version id)
            :authors (get-in destination [:package :authors])
            :copyright package-copyright
            :symbols :snupkg
            :repository-url "https://github.com/apache/pdfbox.git"
            :repository-type "git"
            :repository-commit source-revision}
           :symbol
           {:id id
            :version (package-version id)
            :pdb-entry (str "lib/" target-framework "/" id ".pdb")
            :pdb-sha256 (get-in actual [:symbol :pdb-sha256])
            :dependencies (:dependencies expected)}}]
      (when-not (= expected actual)
        (fail! "Packed PdfCarton artifact violates its exact package contract"
               {:id id :expected expected :actual actual}))
      actual)))

(defn validate-package-family!
  "Requires exactly five deterministic inspected packages and symbol packages,
  their exact direct dependencies and legal/resource payloads, the approved
  external closure, host-native assets, and a fresh feed with no stale output."
  [package-proof]
  (let [packages (:packages package-proof)
        package-ids (mapv #(get-in % [:identity :id]) packages)
        duplicates (->> package-ids frequencies
                        (filter #(< 1 (val %))) (map key) sort vec)
        _ (when (seq duplicates)
            (fail! "PdfCarton family package proof contains duplicate identities"
                   {:duplicates duplicates}))
        actual-ids (set package-ids)
        expected-ids (set (keys package-contract))
        _ (when-not (= expected-ids actual-ids)
            (fail! "PdfCarton family package proof does not contain exactly five packages"
                   {:expected (vec (sort expected-ids))
                    :actual (vec (sort actual-ids))}))
        _ (when-not (= 2 (get-in package-proof [:summary :clean-builds]))
            (fail! "PdfCarton package family was not packed from two clean builds"
                   {:expected 2
                    :actual (get-in package-proof [:summary :clean-builds])}))
        _ (when-not (= source-revision
                       (get-in package-proof [:summary :repository-commit]))
            (fail! "PdfCarton packages do not identify the synchronized source revision"
                   {:expected source-revision
                    :actual (get-in package-proof
                                    [:summary :repository-commit])}))
        external
        (set (map #(select-keys % [:id :version])
                  (:external-packages package-proof)))
        _ (when-not (= external-package-contract external)
            (fail! "PdfCarton package family restored an unapproved external dependency closure"
                   {:expected (vec (sort-by :id external-package-contract))
                    :actual (vec (sort-by :id external))}))
        validated (mapv exact-package-contract packages)
        feed (exact-feed-artifacts! package-proof)
        native (inspect-native-assets!
                (:feed package-proof) (:external-packages package-proof))]
    {:clean-builds 2
     :packages (into (sorted-map)
                     (map (juxt #(get-in % [:metadata :id]) identity))
                     validated)
     :external-packages (vec (sort-by :id external))
     :feed feed
     :native-assets native}))

(defn- identity-contract
  [ids]
  (mapv
   (fn [id]
     {:id id
      :version
      (if (str/starts-with? id "DripSharp.PdfCarton")
        (package-version id)
        (:version
         (first (filter #(= id (:id %)) external-package-contract))))})
   (sort ids)))

(defn- validate-consumers!
  [consumer-proofs]
  (let [names (mapv :consumer-name consumer-proofs)
        expected-names
        (conj (set (keys package-contract)) "complete-family")
        actual-names (set names)
        roots (mapv #(str (:packages-root %)) consumer-proofs)]
    (when-not (= expected-names actual-names)
      (fail! "PdfCarton family consumption did not run every required fresh consumer"
             {:expected (vec (sort expected-names))
              :actual (vec (sort actual-names))}))
    (when-not (= (count roots) (count (set roots)))
      (fail! "PdfCarton package consumers reused an isolated package cache"
             {:package-caches roots}))
    (doseq [proof consumer-proofs
            :let [name (:consumer-name proof)
                  complete? (= "complete-family" name)
                  direct
                  (set (map first
                            (get-in proof
                                    [:dependency-proof :package-references])))
                  expected-direct (if complete?
                                    (set (keys package-contract))
                                    #{name})
                  restored
                  (set (map :id
                            (get-in proof
                                    [:dependency-proof :packages])))
                  expected-restored
                  (if complete?
                    (get restored-closures "DripSharp.PdfCarton.Preflight")
                    (get restored-closures name))]]
      (when-not (= expected-direct direct)
        (fail! "Fresh PdfCarton consumer has the wrong direct package references"
               {:consumer name :expected expected-direct :actual direct}))
      (when-not (= expected-restored restored)
        (fail! "Fresh PdfCarton consumer restored the wrong package closure"
               {:consumer name :expected expected-restored :actual restored})))
    {:consumers (count consumer-proofs)
     :package-caches roots
     :restored
     (into (sorted-map)
           (map (fn [proof]
                  [(:consumer-name proof)
                   (mapv #(select-keys % [:id :version])
                         (get-in proof [:dependency-proof :packages]))]))
           consumer-proofs)}))

(defn verify!
  "Packs the five PdfCarton projects twice, validates their exact artifact family,
  then runs five separate and one all-family fresh local-feed consumers."
  ([]
   (verify! {}))
  ([{:keys [workspace-root pack-fn consumer-fn run-command!]
     :or {pack-fn packaging/pack-verified-profile!
          consumer-fn packaging/verify-packed-consumer!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (pack-fn {:workspace-root root
                   :profile "pdfcube-preflight"
                   :run-command! run-command!})
         package-evidence (validate-package-family! package-proof)
         by-id
         (into {}
               (map (juxt #(get-in % [:identity :id]) identity))
               (:packages package-proof))
         product-consumers
         (mapv
          (fn [id]
            (let [package (get by-id id)]
              (consumer-fn
               {:workspace-root root
                :package-proof package-proof
                :consumer-name id
                :consumer-profile
                (get-in package [:destination :package-consumer])
                :selected-packages
                [{:id id :version (package-version id)}]
                :expected-packages
                (identity-contract (get restored-closures id))
                :target-framework target-framework
                :run-command! run-command!})))
          (sort (keys package-contract)))
         family-consumer
         (consumer-fn
          {:workspace-root root
           :package-proof package-proof
           :consumer-name "complete-family"
           :consumer-profile aggregate-consumer
           :selected-packages
           (identity-contract (set (keys package-contract)))
           :expected-packages
           (identity-contract (get restored-closures "DripSharp.PdfCarton.Preflight"))
           :target-framework target-framework
           :run-command! run-command!})
         consumption
         (validate-consumers! (conj product-consumers family-consumer))
         summary
         {:source {:version (baseline/upstream-version :pdfcube)
                   :revision source-revision}
          :clean-builds 2
          :packages (count (:packages package-evidence))
          :symbol-packages (count (:packages package-evidence))
          :external-packages (count (:external-packages package-evidence))
          :native-hosts 6
          :consumers (:consumers consumption)
          :feed-artifacts (get-in package-evidence [:feed :artifacts])}]
     (println "Deterministic isolated PdfCarton package-family proof passed:"
              (pr-str summary))
     {:summary summary
      :package-proof package-proof
      :packages package-evidence
      :consumption consumption
      :consumer-proofs (conj product-consumers family-consumer)})))
