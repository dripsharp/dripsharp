(ns dripsharp.pdfcube.pdfbox-differential
  "Aggregate package-only proof for DripSharp.PdfCarton and its translated
  DripSharp.PdfCarton.IO and DripSharp.PdfCarton.Fonts dependency closure."
  (:require [dripsharp.baseline :as baseline]
            [clojure.set :as set]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.pdfbox-document-lifecycle-differential
             :as document-lifecycle]
            [dripsharp.pdfcube.pdfbox-font-text-differential
             :as font-text]
            [dripsharp.pdfcube.pdfbox-interaction-differential
             :as interaction]
            [dripsharp.pdfcube.pdfbox-low-level-differential
             :as low-level]
            [dripsharp.pdfcube.pdfbox-manipulation-differential
             :as manipulation]
            [dripsharp.pdfcube.pdfbox-printing-differential
             :as printing]
            [dripsharp.pdfcube.pdfbox-rendering-differential
             :as rendering]
            [dripsharp.pdfcube.pdfbox-security-differential
             :as security]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.file Files OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def ^:private io-contract (baseline/profile :pdfcube :io))
(def ^:private fontbox-contract (baseline/profile :pdfcube :fontbox))
(def ^:private pdfbox-contract (baseline/profile :pdfcube :pdfbox))

(def supported-hosts
  printing/supported-hosts)

(def required-workflows
  #{:create :load :parse :save :incremental-update :manipulate :extract
    :render :form :secure :sign :print-layout})

(def verification-slices
  [{:id :low-level
    :workflows #{:load :parse :save :incremental-update}
    :verify low-level/verify!}
   {:id :document-lifecycle
    :workflows #{:create :load :save}
    :verify document-lifecycle/verify!}
   {:id :manipulation
    :workflows #{:manipulate}
    :verify manipulation/verify!}
   {:id :font-text
    :workflows #{:extract}
    :verify font-text/verify!}
   {:id :rendering
    :workflows #{:render}
    :verify rendering/verify!}
   {:id :interaction
    :workflows #{:form}
    :verify interaction/verify!}
   {:id :security
    :workflows #{:secure :sign}
    :verify security/verify!}
   {:id :printing
    :workflows #{:print-layout}
    :verify printing/verify!}])

(def ^:private common-legal-files
  (baseline/package-legal-files :pdfcube [:upstream]))

(def expected-package-contract
  {"DripSharp.PdfCarton.IO"
   {:profile "pdfcube-io"
    :primary? false
    :project-id (:source-project-id io-contract)
    :revision pinned-revision
    :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.IO")
    :target-framework "netstandard2.0"
    :assembly
    {:name "DripSharp.PdfCarton.IO"
     :version (baseline/assembly-version :pdfcube "DripSharp.PdfCarton.IO")
     :dependency-assemblies []}
    :dependencies
    [{:id "Microsoft.CSharp" :version "4.7.0"}
     {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
     {:id "System.Memory" :version "4.6.3"}
     {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]
    :resources 0
    :package-files common-legal-files
    :contract-members (:public-contract-rows io-contract)}

   "DripSharp.PdfCarton.Fonts"
   {:profile "pdfcube-fontbox"
    :primary? false
    :project-id (:source-project-id fontbox-contract)
    :revision pinned-revision
    :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Fonts")
    :target-framework "netstandard2.0"
    :assembly
    {:name "DripSharp.PdfCarton.Fonts"
     :version (baseline/assembly-version :pdfcube "DripSharp.PdfCarton.Fonts")
     :dependency-assemblies ["DripSharp.PdfCarton.IO"]}
    :dependencies
    [{:id "DripSharp.PdfCarton.IO"
      :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.IO")}
     {:id "Microsoft.CSharp" :version "4.7.0"}
     {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
     {:id "SkiaSharp" :version "4.150.1"}
     {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
     {:id "System.Formats.Asn1" :version "10.0.0"}
     {:id "System.Memory" :version "4.6.3"}
     {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]
    :resources 93
    :package-files common-legal-files
    :contract-members (:public-contract-rows fontbox-contract)}

   "DripSharp.PdfCarton"
   {:profile "pdfcube-pdfbox"
    :primary? true
    :project-id (:source-project-id pdfbox-contract)
    :revision pinned-revision
    :version (baseline/package-version :pdfcube "DripSharp.PdfCarton")
    :target-framework "netstandard2.0"
    :assembly
    {:name "DripSharp.PdfCarton"
     :version (baseline/assembly-version :pdfcube "DripSharp.PdfCarton")
     :dependency-assemblies ["DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.IO"]}
    :dependencies
    [{:id "DripSharp.PdfCarton.Fonts"
      :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Fonts")}
     {:id "DripSharp.PdfCarton.IO"
      :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.IO")}
     {:id "Microsoft.CSharp" :version "4.7.0"}
     {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
     {:id "SkiaSharp" :version "4.150.1"}
     {:id "System.Memory" :version "4.6.3"}
     {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}
     {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]
    :resources 22
    :package-files (baseline/package-legal-files :pdfcube [:upstream :codecs])
    :contract-members (:public-contract-rows pdfbox-contract)}})

(def expected-restored-closure
  #{{:id "DripSharp.PdfCarton.IO"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.IO")}
    {:id "DripSharp.PdfCarton.Fonts"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Fonts")}
    {:id "DripSharp.PdfCarton"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton")}
    {:id "Microsoft.Extensions.DependencyInjection.Abstractions"
     :version "10.0.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "SkiaSharp" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.macOS" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Win32" :version "4.150.1"}
    {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}})

(defn- fail! [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-pdfbox-differential-failed))))

(def ^:private write-text! util/write-text!)

(defn workflow-coverage
  "Returns and validates the aggregate package-only workflow coverage."
  [slices]
  (let [covered (reduce set/union #{} (map :workflows slices))
        missing (sort (set/difference required-workflows covered))
        unexpected (sort (set/difference covered required-workflows))]
    (when (or (seq missing) (seq unexpected))
      (fail! "DripSharp.PdfCarton package proof has incomplete workflow coverage"
             {:missing missing :unexpected unexpected
              :covered (vec (sort covered))}))
    covered))

(defn- generation-emissions [generation]
  (conj
   (vec (:dependency-emissions generation))
   (assoc (:emission generation)
          :profile (get-in generation [:generation-profile :profile])
          :destination (:destination generation))))

(defn- actual-package-contract [package-proof]
  (let [generation (get-in package-proof [:verification :generation])
        emissions (generation-emissions generation)
        emissions-by-id
        (into {}
              (map (fn [emission]
                     [(get-in emission [:destination :package :id]) emission]))
              emissions)
        audits
        (into {}
              (map (juxt :assembly identity))
              (get-in package-proof
                      [:verification :public-surface :assemblies]))
        packages
        (into {}
              (map
               (fn [{:keys [profile primary? identity inspection resource-proof
                            resources]}]
                 (let [id (:id identity)
                       emission (get emissions-by-id id)
                       destination (:destination emission)
                       assembly (get-in destination [:project :assembly-name])
                       audit (get audits assembly)]
                   [id
                    {:profile profile
                     :primary? (boolean primary?)
                     :project-id (:source-project-id destination)
                     :revision (get-in destination [:mechanical-source :revision])
                     :version (:version identity)
                     :target-framework
                     (get-in destination [:project :target-framework])
                     :assembly (:assembly-identity resource-proof)
                     :dependencies (:dependencies inspection)
                     :resources (count resources)
                     :package-files
                     (differential/legal-package-files
                      (:package-files inspection))
                     :contract-members (:contract-members audit)}])))
              (:packages package-proof))
        public-stubs
        (->> emissions
             (mapcat #(get-in % [:public-metadata :rows]))
             (filter #(= :public-stub
                         (get-in % [:generated :implementation])))
             count)
        restored
        (->> (get-in package-proof [:dependency-proof :packages])
             (map #(select-keys % [:id :version]))
             set)]
    {:clean-builds (get-in package-proof [:packing-summary :clean-builds])
     :compiled-strategy
     (get-in package-proof [:verification :public-surface :strategy])
     :packages packages
     :restored-closure restored
     :public-stubs public-stubs}))

(defn validate-package-contract!
  "Requires exact identities, dependency closure, legal files, resources,
  target framework, compiled contract counts, and a zero-public-stub result."
  [package-proof]
  (let [expected
        {:clean-builds 2
         :compiled-strategy :complete-accessible-java-library
         :packages expected-package-contract
         :restored-closure expected-restored-closure
         :public-stubs 0}
        actual (actual-package-contract package-proof)]
    (when-not (= expected actual)
      (fail! "Packed DripSharp.PdfCarton dependency closure violates its exact contract"
             {:expected expected :actual actual}))
    actual))

(defn prove-mismatch-detection!
  "Copies a normalized oracle trace, deliberately changes it, and requires
  the shared differential comparator to report the mismatch."
  [oracle perturbed]
  (let [oracle (paths/path oracle)
        perturbed (paths/path perturbed)]
    (when-not (paths/regular-file? oracle)
      (fail! "Aggregate mismatch control is missing its oracle trace"
             {:oracle (str oracle)}))
    (Files/createDirectories (.getParent perturbed)
                             (make-array FileAttribute 0))
    (Files/copy oracle perturbed
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (Files/writeString
     perturbed
     "failure\taggregate-deliberate-mismatch\tchanged\n"
     (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [comparison (differential/compare-results oracle perturbed)]
      (when-not (:mismatch comparison)
        (fail! "DripSharp.PdfCarton aggregate comparator missed a deliberate mismatch"
               {:oracle (str oracle) :perturbed (str perturbed)}))
      comparison)))

(def ^:private current-host util/current-host)

(defn- run-slice!
  [^Path root package-proof run-command! {:keys [id workflows verify]}]
  (let [summary
        (verify {:workspace-root root
                 :package-fn (fn [_] package-proof)
                 :run-command! run-command!})
        identity (select-keys (:identity package-proof) [:id :version :sha256])]
    (when-not (= identity (:package summary))
      (fail! "A package workflow did not use the aggregate packed identity"
             {:slice id :expected identity :actual (:package summary)}))
    (when-not (= (:dependency-proof package-proof) (:consumer summary))
      (fail! "A package workflow escaped the aggregate isolated consumer"
             {:slice id}))
    {:id id
     :workflows workflows
     :summary summary
     :evidence
     (cond-> {:proof-root (str (:proof-root summary))
              :comparison
              (if (:trace summary)
                (:comparison summary)
                (get-in summary [:comparison :manifest]))}
       (:trace summary)
       (assoc :observations (get-in summary [:trace :observations])
              :families (get-in summary [:trace :families]))

       (= :rendering id)
       (assoc :image-ids
              (get-in summary [:comparison :manifest :image-ids])))}))

(defn- retain-host-canonical!
  [^Path proof-root slice-results]
  (let [printing-result
        (first (filter #(= :printing (:id %)) slice-results))
        source
        (some-> printing-result :summary :proof-root
                (paths/resolve-path "upstream-java.tsv"))
        destination
        (paths/resolve-path proof-root "host" "upstream-printing.tsv")]
    (when-not (and source (paths/regular-file? source))
      (fail! "Printing workflow did not retain its host-smoke canonical trace"
             {:source (some-> source str)}))
    (Files/createDirectories (.getParent destination)
                             (make-array FileAttribute 0))
    (Files/copy source destination
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    destination))

(defn verify!
  "Packs DripSharp.PdfCarton and its translated dependencies once, then reuses that
  exact fresh package-only consumer for every representative workflow proof."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command! slices]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!
          slices verification-slices}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         _ (workflow-coverage slices)
         package-proof
         (package-fn {:workspace-root root
                      :profile "pdfcube-pdfbox"
                      :run-command! run-command!})
         package-contract (validate-package-contract! package-proof)
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              "pdfcube-pdfbox-differential"))
         slice-results
         (mapv #(run-slice! root package-proof run-command! %) slices)
         low-level-result
         (first (filter #(= :low-level (:id %)) slice-results))
         oracle
         (some-> low-level-result :summary :proof-root
                 (paths/resolve-path "upstream-java.tsv"))
         perturbation
         (prove-mismatch-detection!
          oracle (paths/resolve-path proof-root "deliberate-mismatch.tsv"))
         host-canonical (retain-host-canonical! proof-root slice-results)
         rendering-result
         (first (filter #(= :rendering (:id %)) slice-results))
         native-assets
         (select-keys
          (get-in rendering-result [:summary :native-assets])
          [:version :hosts])
         summary
         {:profile "pdfcube-pdfbox"
          :source {:version (baseline/upstream-version :pdfcube) :revision pinned-revision}
          :package
          (merge
           (select-keys (:identity package-proof) [:id :version :sha256])
           {:contract package-contract})
          :consumer (:dependency-proof package-proof)
          :workflows
          (into (sorted-map)
                (map (fn [{:keys [id workflows evidence]}]
                       [id (assoc evidence
                                  :workflows (vec (sort workflows)))]))
                slice-results)
          :workflow-coverage (vec (sort required-workflows))
          :deliberate-mismatch
          {:line (get-in perturbation [:mismatch :line])
           :expected (get-in perturbation [:mismatch :expected])
           :actual (get-in perturbation [:mismatch :actual])}
          :native-assets native-assets
          :host (current-host)
          :supported-hosts supported-hosts
          :host-canonical (str host-canonical)}]
     (write-text! (paths/resolve-path proof-root "summary.edn")
                  (str (pr-str summary) "\n"))
     (println
      "Pinned Java/package DripSharp.PdfCarton aggregate differential passed:"
      (pr-str
       {:source (:source summary)
        :package (select-keys (:package summary) [:id :version :sha256])
        :workflows (:workflow-coverage summary)
        :host (:host summary)}))
     (assoc summary :proof-root proof-root))))
