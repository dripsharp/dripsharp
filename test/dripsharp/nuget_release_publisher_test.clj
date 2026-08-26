(ns dripsharp.nuget-release-publisher-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.nuget-framework :as nuget-framework]
            [dripsharp.nuget-release-publisher :as publisher]
            [dripsharp.paths :as paths]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- write-file!
  [^Path file contents]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file contents StandardCharsets/UTF_8
                     (make-array OpenOption 0))
  file)

(defn- nuspec
  [id version target-framework dependencies]
  (str
   "<package xmlns=\"http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd\">"
   "<metadata><id>" id "</id><version>" version "</version>"
   "<dependencies><group targetFramework=\"" target-framework "\">"
   (apply str
          (for [{:keys [id version]} dependencies]
            (str "<dependency id=\"" id "\" version=\"" version
                 "\" exclude=\"Build,Analyzers\" />")))
   "</group></dependencies></metadata></package>"))

(defn- zip-file!
  [^Path file entries]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (with-open [output
              (ZipOutputStream.
               (Files/newOutputStream file (make-array OpenOption 0)))]
    (doseq [[name value] (sort-by key entries)]
      (.putNextEntry output (ZipEntry. name))
      (.write output (.getBytes (str value) StandardCharsets/UTF_8))
      (.closeEntry output)))
  file)

(def ^:private dependency-group-pattern
  #"(?s)<group targetFramework=\"[^\"]+\">.*?</group>")

(def ^:dynamic *dependency-group-mutation* nil)

(defn- dependency-group-frameworks
  [^Path artifact package-id]
  (with-open [archive (ZipFile. (str artifact))]
    (let [entry (.getEntry archive (str package-id ".nuspec"))
          xml (with-open [input (.getInputStream archive entry)]
                (String. (.readAllBytes input) StandardCharsets/UTF_8))]
      (mapv second (re-seq #"targetFramework=\"([^\"]+)\"" xml)))))

(defn- topological-order
  [records id-fn dependencies-fn]
  (let [ids (set (map id-fn records))
        dependencies
        (into
         (sorted-map)
         (for [record records]
           [(id-fn record) (set (filter ids (dependencies-fn record)))]))]
    (loop [remaining dependencies result []]
      (if (empty? remaining)
        result
        (let [ready (->> remaining
                         (keep (fn [[id required]]
                                 (when (empty? required) id)))
                         sort
                         vec)
              ready-set (set ready)]
          (recur
           (into
            (sorted-map)
            (for [[id required] remaining
                  :when (not (contains? ready-set id))]
              [id (set/difference required ready-set)]))
           (into result ready)))))))

(defn- contract-package-specs
  [contract]
  (let [profiles (:profiles contract)
        bundle (get-in contract [:publication :nuget :bundle])
        selected (set (keys (get-in contract [:publication :profile-projects])))
        records
        (for [profile selected]
          {:dependency-profiles
           (get-in profiles [profile :configuration :dependency-profiles])
           :profile profile})
        profile-order
        (topological-order records :profile :dependency-profiles)
        components
        (mapv
         (fn [profile]
           (let [destination
                 (get-in profiles [profile :destination :configuration])
                 id (get-in destination [:package :id])
                 dependencies
                 (->> (concat
                       (for [dependency-profile
                             (get-in profiles
                                     [profile :configuration
                                      :dependency-profiles])]
                         (let [dependency-id
                               (get-in profiles
                                       [dependency-profile :destination
                                        :configuration :package :id])]
                           {:id dependency-id
                            :version
                            (get-in contract
                                    [:publication :nuget :packages dependency-id
                                     :version])}))
                       (map #(select-keys % [:id :version])
                            (:runtime-packages destination)))
                      (sort-by (juxt :id :version))
                      (mapv #(sorted-map :id (:id %) :version (:version %))))]
             {:dependencies dependencies
              :id id
              :pdb-entries
              [(str "lib/" (get-in destination [:project :target-framework])
                    "/" id ".pdb")]
              :product-family (:product-family contract)
              :profile profile
              :source-commit
              (get-in contract [:baseline :record :upstream :revision])
              :symbols? (= :snupkg (get-in destination [:package :symbols]))
              :target (:target contract)
              :target-framework (get-in destination [:project :target-framework])
              :version
              (get-in contract [:publication :nuget :packages id :version])}))
         profile-order)]
    (if bundle
      (let [component-ids (set (:component-package-ids bundle))
            package-id (:package-id bundle)
            base (first (filter #(= package-id (:id %)) components))
            dependencies
            (->> components
                 (mapcat :dependencies)
                 (remove #(contains? component-ids (:id %)))
                 distinct
                 (sort-by (juxt :id :version))
                 vec)]
        [(assoc base
                :dependencies dependencies
                :pdb-entries (vec (sort (mapcat :pdb-entries components)))
                :profile (:profile bundle))])
      components)))

(defn- contract-component-package-ids
  [contract]
  (let [profiles (:profiles contract)
        selected (set (keys (get-in contract [:publication :profile-projects])))
        records
        (for [profile selected]
          {:dependency-profiles
           (get-in profiles [profile :configuration :dependency-profiles])
           :profile profile})]
    (mapv
     #(get-in profiles [% :destination :configuration :package :id])
     (topological-order records :profile :dependency-profiles))))

(defn- product-commit
  [target]
  (apply str (repeat 40 (case target :pdfcube "b" :pkl "c" :sqltrellis "d"))))

(defn- package-record!
  [^Path directory publish-order
   {:keys [dependencies id pdb-entries product-family profile source-commit
           symbols? target target-framework version]}]
  (let [product-commit (product-commit target)
        package-filename (str id "." version ".nupkg")
        symbol-filename (str id "." version ".snupkg")
        nuspec-entry (str id ".nuspec")
        canonical-nuspec
        (nuspec id version
                (nuget-framework/canonical-dependency-framework
                 target-framework)
                dependencies)
        package-nuspec
        (if *dependency-group-mutation*
          (*dependency-group-mutation* canonical-nuspec)
          canonical-nuspec)
        package-path
        (zip-file!
         (paths/resolve-path directory package-filename)
         {nuspec-entry package-nuspec})
        pdbs
        (mapv (fn [entry]
                {:entry entry
                 :bytes (str entry "|portable-pdb")})
              pdb-entries)
        symbol-path
        (when symbols?
          (zip-file!
           (paths/resolve-path directory symbol-filename)
           (into
            {nuspec-entry package-nuspec}
            (map (juxt :entry :bytes))
            pdbs)))]
    (sorted-map
     :dependencies dependencies
     :files
     (cond->
      (sorted-map
       :package
       (sorted-map :filename package-filename
                   :sha256 (util/sha256-file package-path)))
       symbols?
       (assoc :symbols
              (sorted-map :filename symbol-filename
                          :sha256 (util/sha256-file symbol-path))))
     :id id
     :product-commit product-commit
     :product-family product-family
     :profile profile
     :publish-order publish-order
     :source-commit source-commit
     :symbol-pairing
     (if symbols?
       (sorted-map :package-filename package-filename
                   :pdbs
                   (mapv (fn [{:keys [entry bytes]}]
                           (sorted-map :entry entry
                                       :sha256 (util/sha256-text bytes)))
                         pdbs)
                   :status :paired
                   :symbol-filename symbol-filename)
       (sorted-map :status :absent))
     :target target
     :target-framework target-framework
     :version version)))

(defn- product-record
  [contract]
  (sorted-map
   :component-package-ids (contract-component-package-ids contract)
   :package-ids (mapv :id (contract-package-specs contract))
   :product-commit (product-commit (:target contract))
   :product-family (:product-family contract)
   :proof-ladders (mapv :id (get-in contract [:proof :ladders]))
   :repository-url (get-in contract [:publication :repository-url])
   :source-commit (get-in contract [:baseline :record :upstream :revision])
   :source-repository
   (get-in contract [:baseline :record :upstream :repository])
   :target (:target contract)))

(defn- fixture!
  []
  (let [root
        (Files/createTempDirectory
         "dripsharp-nuget-release-publisher-test-"
         (make-array FileAttribute 0))
        source-root (paths/workspace-root)
        contracts
        (mapv #(target-directory/read-target source-root %)
              [:pdfcube :pkl :sqltrellis])
        directory
        (doto (paths/resolve-path root "target" "nuget-release" "all")
          (Files/createDirectories (make-array FileAttribute 0)))
        specs (mapv identity (mapcat contract-package-specs contracts))
        publish-order
        (topological-order
         specs :id
         #(mapv :id (:dependencies %)))
        spec-by-id (into {} (map (juxt :id identity)) specs)
        packages
        (mapv (fn [position id]
                (package-record! directory position (get spec-by-id id)))
              (range) publish-order)
        remote-calls (atom [])
        remote-request-fn
        (fn [request]
          (swap! remote-calls conj request)
          (if (= "https://api.nuget.org/v3/index.json" (:uri request))
            {:body
             "{\"resources\":[{\"@id\":\"https://api.nuget.org/v3-flatcontainer/\",\"@type\":\"PackageBaseAddress/3.0.0\"}]}"
             :status 200}
            {:body "" :status 404}))
        manifest
        (sorted-map
         :configuration "Release"
         :kind :credential-free-nuget-release-preparation
         :network-mutations []
         :package-count 4
         :packages packages
         :product-count 3
         :products (mapv product-record contracts)
         :publication-credentials-accepted false
         :publish-order publish-order
         :remote-availability :not-checked
         :schema-version 4
         :selection "all"
         :test-verification :complete)
        manifest-file (paths/resolve-path directory "release-manifest.edn")
        write-manifest!
        (fn [value]
          (write-file! manifest-file (str (pr-str value) "\n")))
        options
        {:discover-products-fn (fn [_] contracts)
         :manifest manifest-file
         :read-target-fn
         (fn [_ target]
           (first (filter #(= (keyword target) (:target %)) contracts)))
         :remote-request-fn remote-request-fn
         :workspace-root root}]
    (write-manifest! manifest)
    {:contracts contracts
     :manifest manifest
     :manifest-file manifest-file
     :options options
     :remote-calls remote-calls
     :root root
     :write-manifest! write-manifest!}))

(defn- remote-fake
  [calls package-response]
  (fn [request]
    (swap! calls conj request)
    (if (= "https://api.nuget.org/v3/index.json" (:uri request))
      {:body
       "{\"resources\":[{\"@id\":\"https://api.nuget.org/v3-flatcontainer/\",\"@type\":\"PackageBaseAddress/3.0.0\"}]}"
       :status 200}
      (package-response request))))

(defn- update-package
  [manifest package-id f]
  (update manifest :packages
          (fn [packages]
            (mapv #(if (= package-id (:id %)) (f %) %) packages))))

(deftest dry-run-is-the-default-and-prints-the-exact-secret-free-plan
  (let [{:keys [options remote-calls]} (fixture!)
        push-calls (atom [])
        plan (atom nil)
        output
        (with-out-str
          (reset!
           plan
           (publisher/publish!
            (assoc options
                   :credential-fn
                   (fn [_]
                     (throw (ex-info "credential must not be read" {})))
                   :push-fn #(swap! push-calls conj %)))))]
    (is (= :dry-run (:mode @plan)))
    (is (= 4 (count (:steps @plan))))
    (is (= (set publisher/release-package-ids)
           (set (map :id (:steps @plan)))))
    (is (every? #(= :paired (get-in % [:symbols :status])) (:steps @plan)))
    (is (not-any? #(some #{"--no-symbols"} (:command %)) (:steps @plan)))
    (is (not-any? #{"--api-key" "-k"}
                  (mapcat :command (:steps @plan))))
    (is (= :not-checked
           (get-in @plan [:preflight :remote-availability :status])))
    (is (empty? @remote-calls))
    (is (empty? @push-calls))
    (is (str/includes? output "NuGet publication dry-run plan:"))
    (is (str/includes? output "DripSharp.Brine.Parser"))))

(deftest github-free-runner-test-skip-is-preserved-by-preflight
  (let [{:keys [manifest options write-manifest!]} (fixture!)
        _ (write-manifest!
           (assoc manifest
                  :test-verification :skipped-for-github-free-runner))
        report (publisher/preflight! options)]
    (is (= :skipped-for-github-free-runner
           (:test-verification report)))))

(deftest bounded-brine-test-mode-is-preserved-by-preflight
  (let [{:keys [manifest options write-manifest!]} (fixture!)
        _ (write-manifest!
           (assoc manifest :test-verification :reduced-brine-release))
        report (publisher/preflight! options)]
    (is (= :reduced-brine-release (:test-verification report)))))

(deftest bounded-pdfcarton-test-mode-is-preserved-by-preflight
  (let [{:keys [manifest options write-manifest!]} (fixture!)
        _ (write-manifest!
           (assoc manifest :test-verification :reduced-pdfcarton-release))
        report (publisher/preflight! options)]
    (is (= :reduced-pdfcarton-release (:test-verification report)))))

(deftest one-product-manifest-is-a-complete-selected-release
  (let [{:keys [contracts manifest]} (fixture!)
        contract (first (filter #(= :pdfcube (:target %)) contracts))
        package (first (filter #(= "DripSharp.PdfCarton" (:id %))
                               (:packages manifest)))
        selected (assoc manifest
                        :selection "pdfcube"
                        :package-count 1
                        :packages [package]
                        :publish-order ["DripSharp.PdfCarton"])]
    (is (= [package]
           (#'publisher/complete-release-set! selected [contract] [package])))
    (is (= :nuget-release-publish-failed
           (:kind
            (ex-data
             (failure
              #(#'publisher/complete-release-set!
                selected [contract] [(first (:packages manifest))]))))))))

(deftest every-manifest-boundary-is-revalidated-before-planning
  (let [{:keys [manifest options write-manifest!]} (fixture!)
        sensitive-value (str "fixture-" (UUID/randomUUID))
        mutations
        [(fn [value] (assoc value :kind :unproved))
         (fn [value]
           (assoc-in value [:packages 0 :files :package :filename]
                     "../escaped.nupkg"))
         (fn [value]
           (assoc-in value [:packages 0 :files :package :sha256]
                     (apply str (repeat 64 "0"))))
         (fn [value]
           (assoc-in value [:packages 0 :id] "DripSharp.Fixture.Changed"))
         (fn [value]
           (assoc-in value [:packages 0 :version] "2.0.0"))
         (fn [value]
           (update
            value :packages
            (fn [packages]
              (mapv #(if (= "DripSharp.Brine" (:id %))
                       (assoc % :dependencies [])
                       %)
                    packages))))
         (fn [value]
           (assoc value
                  :publish-order (vec (reverse (:publish-order value)))))]]
    (doseq [mutate mutations]
      (write-manifest! (mutate manifest))
      (is (= :nuget-release-publish-failed
             (:kind (ex-data (failure #(publisher/publish! options)))))))
    (write-manifest! (assoc manifest :selection sensitive-value))
    (let [error (failure #(publisher/publish! options))]
      (is (not (str/includes? (str (ex-message error) (ex-data error))
                              sensitive-value))))
    (write-manifest! manifest)))

(deftest raw-manifest-framework-requires-canonical-artifact-groups
  (let [{:keys [manifest options root]} (fixture!)
        directory (paths/resolve-path root "target" "nuget-release" "all")]
    (is (= (repeat 4 "netstandard2.0")
           (map :target-framework (:packages manifest))))
    (doseq [package (:packages manifest)
            artifact-kind [:package :symbols]
            :let [filename (get-in package [:files artifact-kind :filename])]
            :when filename]
      (is (= [".NETStandard2.0"]
             (dependency-group-frameworks
              (paths/resolve-path directory filename)
              (:id package)))))
    (is (= :nuget-release-preflight
           (:kind (publisher/preflight! options)))))
  (doseq [[label group-mutation]
          [["lowercase noncanonical group"
            #(str/replace % ".NETStandard2.0" "netstandard2.0")]
           ["wrong group"
            #(str/replace % ".NETStandard2.0" ".NETStandard2.1")]
           ["missing group"
            #(str/replace % dependency-group-pattern "")]
           ["duplicate group"
            #(str/replace % dependency-group-pattern
                          (fn [group] (str group group)))]
           ["extra group"
            #(str/replace
              % "</dependencies>"
              (str "<group targetFramework=\"net8.0\"></group>"
                   "</dependencies>"))]
           ["changed dependency edges"
            #(str/replace
              % "</group>"
              (str "<dependency id=\"Unexpected.Dependency\" "
                   "version=\"1.0.0\" exclude=\"Build,Analyzers\" />"
                   "</group>"))]]]
    (testing label
      (let [{:keys [options]}
            (binding [*dependency-group-mutation* group-mutation]
              (fixture!))
            error (failure #(publisher/preflight! options))]
        (is (= :nuget-release-publish-failed (:kind (ex-data error))))))))

(deftest complete-local-preflight-rejects-every-release-set-hazard
  (doseq [[label mutate]
          [["duplicate package IDs"
            (fn [manifest]
              (assoc-in manifest [:packages 1 :id]
                        (get-in manifest [:packages 0 :id])))]
           ["invalid versions"
            (fn [manifest]
              (assoc-in manifest [:packages 0 :version] "not-a-version"))]
           ["missing dependency packages"
            (fn [manifest]
              (let [missing-id "DripSharp.Brine.Parser"]
                (-> manifest
                    (update :packages
                            #(vec (remove (fn [package]
                                            (= missing-id (:id package)))
                                          %)))
                    (update :package-count dec)
                    (update :publish-order
                            #(vec (remove #{missing-id} %))))))]
           ["non-exact internal dependency versions"
            (fn [manifest]
              (update-package
               manifest "DripSharp.Brine"
               #(assoc-in % [:dependencies 0 :version]
                          (str "[" (get-in % [:dependencies 0 :version]) "]"))))]
           ["wrong topological order"
            (fn [manifest]
              (assoc manifest :publish-order
                     (vec (reverse (:publish-order manifest)))))]
           ["mismatched symbol pairing"
            (fn [manifest]
              (assoc-in manifest
                        [:packages 0 :symbol-pairing :symbol-filename]
                        "mismatched.snupkg"))]
           ["altered local hashes"
            (fn [manifest]
              (assoc-in manifest [:packages 0 :files :package :sha256]
                        (apply str (repeat 64 "0"))))]]]
    (testing label
      (let [{:keys [manifest options write-manifest!]} (fixture!)]
        (write-manifest! (mutate manifest))
        (is (= :nuget-release-publish-failed
               (:kind
                (ex-data (failure #(publisher/preflight! options)))))))))
  (testing "development-placeholder catalog versions"
    (let [{:keys [contracts options]} (fixture!)
          contracts
          (mapv
           #(if (= :pkl (:target %))
              (assoc-in %
                        [:publication :nuget :packages
                         "DripSharp.Brine.Parser" :version]
                        "0.0.0-development")
              %)
           contracts)
          options
          (assoc options
                 :discover-products-fn (fn [_] contracts)
                 :read-target-fn
                 (fn [_ target]
                   (first (filter #(= (keyword target) (:target %))
                                  contracts))))
          error (failure #(publisher/preflight! options))]
      (is (= :nuget-release-publish-failed (:kind (ex-data error))))
      (is (str/includes? (ex-message error) "development-placeholder"))))
  (testing "missing symbol artifact"
    (let [{:keys [manifest options root]} (fixture!)
          filename (get-in manifest [:packages 0 :files :symbols :filename])
          file (paths/resolve-path root "target" "nuget-release" "all"
                                   filename)]
      (Files/delete file)
      (is (= :nuget-release-publish-failed
             (:kind (ex-data (failure #(publisher/preflight! options))))))))
  (testing "configured package size ceiling"
    (let [{:keys [options]} (fixture!)
          error
          (failure #(publisher/preflight!
                     (assoc options :max-package-bytes 1)))]
      (is (= :nuget-release-publish-failed (:kind (ex-data error))))
      (is (= 1 (:size-limit-bytes (ex-data error)))))))

(deftest offline-preflight-is-deterministic-and-explicitly-not-checked
  (let [{:keys [options remote-calls]} (fixture!)
        first-report (atom nil)
        second-report (atom nil)]
    (with-out-str
      (reset! first-report (publisher/preflight! options))
      (reset! second-report (publisher/preflight! options)))
    (is (= @first-report @second-report))
    (is (= :not-checked
           (get-in @first-report [:remote-availability :status])))
    (is (= 4 (get-in @first-report
                     [:remote-availability :package-count])))
    (is (every? #(= :not-checked (:status %))
                (get-in @first-report [:remote-availability :packages])))
    (is (empty? @remote-calls))))

(deftest remote-preflight-reports-all-four-exact-id-version-states-with-get-only-fakes
  (let [{:keys [manifest options]} (fixture!)
        calls (atom [])
        first-version (get-in manifest [:packages 0 :version])
        request-fn
        (remote-fake
         calls
         (fn [request]
           (if (str/includes? (:uri request) "dripsharp.brine.parser")
             {:body (str "{\"versions\":[\"0.1.0\",\""
                         (str/lower-case first-version)
                         "-other\"]}")
              :status 200}
             {:body "" :status 404})))
        report
        (atom nil)]
    (with-out-str
      (reset!
       report
       (publisher/preflight!
        (assoc options
               :check-nuget-org? true
               :remote-request-fn request-fn
               :remote-timeout-seconds 1))))
    (is (= :checked (get-in @report [:remote-availability :status])))
    (is (= 4 (get-in @report [:remote-availability :package-count])))
    (is (= (mapv #(select-keys % [:id :version]) (:packages manifest))
           (mapv #(select-keys % [:id :version])
                 (get-in @report [:remote-availability :packages]))))
    (is (every? #(= :available (:status %))
                (get-in @report [:remote-availability :packages])))
    (is (= 5 (count @calls)))
    (is (every? #(= :get (:method %)) @calls))
    (is (every? #(= #{:headers :method :timeout-ms :uri}
                    (set (keys %)))
                @calls))
    (is (not-any? #(or (contains? % :body)
                       (str/includes? (str/lower-case (pr-str %)) "credential")
                       (str/includes? (str/lower-case (pr-str %)) "publish")
                       (str/includes? (str/lower-case (pr-str %)) "push"))
                  @calls))))

(deftest remote-version-collision-is-a-hard-conflict-before-credential-or-push
  (let [{:keys [manifest options]} (fixture!)
        calls (atom [])
        credential-reads (atom [])
        push-calls (atom [])
        first-package (first (:packages manifest))
        request-fn
        (remote-fake
         calls
         (fn [request]
           (if (str/includes? (:uri request)
                              (str/lower-case (:id first-package)))
             {:body (str "{\"versions\":[\""
                         (str/lower-case (:version first-package))
                         "\"]}")
              :status 200}
             {:body "" :status 404})))
        error
        (failure
         #(publisher/publish!
           (assoc options
                  :authorized? true
                  :credential-fn
                  (fn [name]
                    (swap! credential-reads conj name)
                    "must-not-be-read")
                  :live? true
                  :push-fn (fn [request]
                             (swap! push-calls conj request))
                  :remote-request-fn request-fn
                  :source "https://api.nuget.org/v3/index.json")))]
    (is (= :remote-version-conflict (:reason (ex-data error))))
    (is (= [{:id (:id first-package) :version (:version first-package)}]
           (:conflicts (ex-data error))))
    (is (= 4 (get-in (ex-data error)
                     [:remote-availability :package-count])))
    (is (= 5 (count @calls)))
    (is (empty? @credential-reads))
    (is (empty? @push-calls))))

(deftest remote-indeterminate-states-fail-closed-with-complete-fake-reports
  (doseq [[label package-response]
          [["HTTP failure" (constantly {:body "unavailable" :status 503})]
           ["timeout"
            (fn [_]
              (throw (ex-info "fixture timeout" {:kind :command-timeout})))]
           ["malformed JSON" (constantly {:body "not-json" :status 200})]]]
    (testing label
      (let [{:keys [options]} (fixture!)
            calls (atom [])
            error
            (failure
             #(publisher/preflight!
               (assoc options
                      :check-nuget-org? true
                      :remote-request-fn
                      (remote-fake calls package-response)
                      :remote-timeout-seconds 1)))]
        (is (= :remote-availability-indeterminate
               (:reason (ex-data error))))
        (is (= 4 (get-in (ex-data error)
                         [:remote-availability :package-count])))
        (is (every? #(= :indeterminate (:status %))
                    (get-in (ex-data error)
                            [:remote-availability :packages])))
        (is (= 5 (count @calls)))
        (is (every? #(= :get (:method %)) @calls))))))

(deftest live-mode-fails-closed-before-any-request
  (let [{:keys [options]} (fixture!)
        calls (atom [])
        sensitive-value (str "fixture-" (UUID/randomUUID))
        source "https://api.nuget.org/v3/index.json"
        invoke
        (fn [overrides]
          (failure
           #(publisher/publish!
             (merge options
                    {:live? true
                     :push-fn (fn [request]
                                (swap! calls conj request)
                                {:exit 0})}
                    overrides))))]
    (is (= :missing (:authorization (ex-data (invoke {:source source})))))
    (is (= :nuget-release-publish-failed
           (:kind (ex-data
                   (invoke {:authorized? true
                            :source (str "https://user:" sensitive-value
                                         "@api.nuget.org/v3/index.json")})))))
    (is (= publisher/credential-environment-variable
           (:credential-channel
            (ex-data
             (invoke {:authorized? true
                      :source source
                      :credential-fn (constantly nil)})))))
    (is (= publisher/credential-environment-variable
           (:credential-channel
            (ex-data
             (invoke {:authorized? true
                      :source source
                      :credential-fn (constantly "malformed\ncredential")})))))
    (is (= #{:api-key}
           (set
            (:forbidden-options
             (ex-data
              (invoke {:api-key "must-not-be-accepted"
                       :authorized? true
                       :source source}))))))
    (is (not (str/includes?
              (str (ex-data
                    (invoke {:authorized? true
                             :source (str "https://user:" sensitive-value
                                          "@api.nuget.org/v3/index.json")})))
              sensitive-value)))
    (is (empty? @calls))))

(deftest live-mode-reports-duplicate-conflict-and-partial-failure-without-a-leak
  (let [{:keys [manifest options]} (fixture!)
        credential (str "fixture-" (UUID/randomUUID))
        redacted "<redacted>"
        calls (atom [])
        caught (atom nil)
        output
        (with-out-str
          (reset!
           caught
           (failure
            #(publisher/publish!
              (assoc options
                     :authorized? true
                     :credential-fn (constantly credential)
                     :live? true
                     :push-fn
                     (fn [request]
                       (swap! calls conj request)
                       (if (= 1 (count @calls))
                         {:exit 0 :output "published"}
                         (throw
                          (ex-info (str "server echoed " credential)
                                   {:kind :command-failed
                                    :output (str "409 Conflict " credential)
                                    :request request}))))
                     :source
                     "https://api.nuget.org/v3/index.json")))))]
    (is (= :duplicate-version-conflict (:failure (ex-data @caught))))
    (is (= [{:id (get-in manifest [:packages 0 :id])
             :position 0
             :version (get-in manifest [:packages 0 :version])}]
           (:completed (ex-data @caught))))
    (is (= (get-in manifest [:packages 1 :id])
           (get-in (ex-data @caught) [:failed :id])))
    (is (= :unknown
           (get-in (ex-data @caught) [:failed :remote-state])))
    (is (= (mapv (fn [package]
                   {:id (:id package)
                    :position (:publish-order package)
                    :version (:version package)})
                 (drop 2 (:packages manifest)))
           (:remaining (ex-data @caught))))
    (is (= 2 (count @calls)))
    (is (every?
         #(and (= credential
                  (second (drop-while (complement #{"--api-key"})
                                      (:command %))))
               (= credential
                  (second (drop-while (complement #{"--symbol-api-key"})
                                      (:command %)))))
         @calls))
    (is (every?
         #(and (= redacted
                  (second (drop-while (complement #{"--api-key"})
                                      (:display-command %))))
               (= redacted
                  (second (drop-while (complement #{"--symbol-api-key"})
                                      (:display-command %)))))
         @calls))
    (is (every? #(= #{publisher/credential-environment-variable
                      publisher/symbol-credential-environment-variable}
                    (:unset-environment %))
                @calls))
    (is (not-any? #(some #{"--skip-duplicate"} (:command %)) @calls))
    (is (not (str/includes? (str (ex-message @caught) (ex-data @caught))
                            credential)))
    (is (not (str/includes? output credential)))
    (is (str/includes? (:output (ex-data @caught)) "409 Conflict"))
    (is (str/includes? (:output (ex-data @caught)) redacted))))

(deftest live-mode-classifies-timeout-and-generic-errors
  (doseq [[expected push-fn]
          [[:timeout
            (fn [_]
              (throw (ex-info "timed out"
                              {:kind :command-timeout :timeout-ms 10})))]
           [:error (fn [_] {:exit 17 :output "ordinary failure"})]]]
    (let [{:keys [manifest options]} (fixture!)
          error
          (failure
           #(publisher/publish!
             (assoc options
                    :authorized? true
                    :credential-fn
                    (constantly (str "fixture-" (UUID/randomUUID)))
                    :live? true
                    :push-fn push-fn
                    :source
                    "https://api.nuget.org/v3/index.json"
                    :timeout-seconds 1)))]
      (is (= expected (:failure (ex-data error))))
      (is (string? (:output (ex-data error))))
      (is (= [] (:completed (ex-data error))))
      (is (= (get-in manifest [:packages 0 :id])
             (get-in (ex-data error) [:failed :id])))
      (is (= (mapv :id (rest (:packages manifest)))
             (mapv :id (:remaining (ex-data error))))))))
