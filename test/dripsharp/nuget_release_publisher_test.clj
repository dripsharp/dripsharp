(ns dripsharp.nuget-release-publisher-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.nuget-release-publisher :as publisher]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]
           [java.util.zip ZipEntry ZipOutputStream]))

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
    (doseq [[name value] entries]
      (.putNextEntry output (ZipEntry. name))
      (.write output (.getBytes (str value) StandardCharsets/UTF_8))
      (.closeEntry output)))
  file)

(defn- fixture-contract
  []
  {:baseline
   {:record
    {:upstream
     {:repository "https://example.invalid/upstream.git"
      :revision (apply str (repeat 40 "a"))}}}
   :product-family :fixture
   :profiles
   {"fixture-base"
    {:configuration {:dependency-profiles []}
     :destination
     {:configuration
      {:package {:id "DripSharp.Fixture.Base" :symbols :snupkg}
       :project {:target-framework "net10.0"}
       :runtime-packages [{:id "External.Dependency" :version "9.8.7"}]}}}
    "fixture-main"
    {:configuration {:dependency-profiles ["fixture-base"]}
     :destination
     {:configuration
      {:package {:id "DripSharp.Fixture.Main"}
       :project {:target-framework "net10.0"}}}}}
   :proof {:ladders [{:id :fixture-complete-proof}]}
   :publication
   {:kind :generated-repository
    :nuget
    {:packages
     {"DripSharp.Fixture.Base" {:version "1.2.3-alpha.1"}
      "DripSharp.Fixture.Main" {:version "1.2.3-alpha.1"}}
     :source "https://packages.example.invalid/v3/index.json"}
    :profile-projects
    {"fixture-base" "src/DripSharp.Fixture.Base"
     "fixture-main" "src/DripSharp.Fixture.Main"}
    :repository-url "https://example.invalid/fixture.git"}
   :target :fixture})

(defn- package-record!
  [^Path directory {:keys [dependencies id product-commit profile symbols?
                           version]}]
  (let [target-framework "net10.0"
        package-filename (str id "." version ".nupkg")
        symbol-filename (str id "." version ".snupkg")
        nuspec-entry (str id ".nuspec")
        package-path
        (zip-file!
         (paths/resolve-path directory package-filename)
         {nuspec-entry (nuspec id version target-framework dependencies)})
        pdb-entry (str "lib/" target-framework "/" id ".pdb")
        pdb-bytes (str id "|portable-pdb")
        symbol-path
        (when symbols?
          (zip-file!
           (paths/resolve-path directory symbol-filename)
           {nuspec-entry (nuspec id version target-framework dependencies)
            pdb-entry pdb-bytes}))]
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
     :product-family :fixture
     :profile profile
     :publish-order (if (= "fixture-base" profile) 0 1)
     :source-commit (apply str (repeat 40 "a"))
     :symbol-pairing
     (if symbols?
       (sorted-map :package-filename package-filename
                   :pdb-entry pdb-entry
                   :pdb-sha256 (util/sha256-text pdb-bytes)
                   :status :paired
                   :symbol-filename symbol-filename)
       (sorted-map :status :absent))
     :target :fixture
     :target-framework target-framework
     :version version)))

(defn- fixture!
  []
  (let [root
        (Files/createTempDirectory
         "dripsharp-nuget-release-publisher-test-"
         (make-array FileAttribute 0))
        contract (fixture-contract)
        directory
        (doto (paths/resolve-path root "target" "nuget-release" "fixture")
          (Files/createDirectories (make-array FileAttribute 0)))
        product-commit (apply str (repeat 40 "b"))
        base-dependencies
        [(sorted-map :id "External.Dependency" :version "9.8.7")]
        main-dependencies
        [(sorted-map :id "DripSharp.Fixture.Base"
                     :version "1.2.3-alpha.1")]
        packages
        [(package-record!
          directory
          {:dependencies base-dependencies
           :id "DripSharp.Fixture.Base"
           :product-commit product-commit
           :profile "fixture-base"
           :symbols? true
           :version "1.2.3-alpha.1"})
         (package-record!
          directory
          {:dependencies main-dependencies
           :id "DripSharp.Fixture.Main"
           :product-commit product-commit
           :profile "fixture-main"
           :symbols? false
           :version "1.2.3-alpha.1"})]
        manifest
        (sorted-map
         :configuration "Release"
         :kind :credential-free-nuget-release-preparation
         :network-mutations []
         :package-count 2
         :packages packages
         :product-count 1
         :products
         [(sorted-map
           :package-ids
           ["DripSharp.Fixture.Base" "DripSharp.Fixture.Main"]
           :product-commit product-commit
           :product-family :fixture
           :proof-ladders [:fixture-complete-proof]
           :repository-url "https://example.invalid/fixture.git"
           :source-commit (apply str (repeat 40 "a"))
           :source-repository "https://example.invalid/upstream.git"
           :target :fixture)]
         :publication-credentials-accepted false
         :publish-order
         ["DripSharp.Fixture.Base" "DripSharp.Fixture.Main"]
         :schema-version 1
         :selection "fixture")
        manifest-file (paths/resolve-path directory "release-manifest.edn")
        write-manifest!
        (fn [value]
          (write-file! manifest-file (str (pr-str value) "\n")))
        options
        {:discover-products-fn (fn [_] [contract])
         :manifest manifest-file
         :read-target-fn (fn [_ _] contract)
         :workspace-root root}]
    (write-manifest! manifest)
    {:contract contract
     :manifest manifest
     :manifest-file manifest-file
     :options options
     :root root
     :write-manifest! write-manifest!}))

(deftest dry-run-is-the-default-and-prints-the-exact-secret-free-plan
  (let [{:keys [options]} (fixture!)
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
    (is (= ["DripSharp.Fixture.Base" "DripSharp.Fixture.Main"]
           (mapv :id (:steps @plan))))
    (is (= :paired (get-in @plan [:steps 0 :symbols :status])))
    (is (= :absent (get-in @plan [:steps 1 :symbols :status])))
    (is (not (some #{"--no-symbols"}
                   (get-in @plan [:steps 0 :command]))))
    (is (some #{"--no-symbols"} (get-in @plan [:steps 1 :command])))
    (is (not-any? #{"--api-key" "-k"}
                  (mapcat :command (:steps @plan))))
    (is (empty? @push-calls))
    (is (str/includes? output "NuGet publication dry-run plan:"))
    (is (str/includes? output "DripSharp.Fixture.Base"))))

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
           (assoc-in value [:packages 1 :dependencies] []))
         (fn [value]
           (assoc value
                  :publish-order
                  ["DripSharp.Fixture.Main" "DripSharp.Fixture.Base"]))]]
    (doseq [mutate mutations]
      (write-manifest! (mutate manifest))
      (is (= :nuget-release-publish-failed
             (:kind (ex-data (failure #(publisher/publish! options)))))))
    (write-manifest! (assoc manifest :selection sensitive-value))
    (let [error (failure #(publisher/publish! options))]
      (is (not (str/includes? (str (ex-message error) (ex-data error))
                              sensitive-value))))
    (write-manifest! manifest)))

(deftest live-mode-fails-closed-before-any-request
  (let [{:keys [options]} (fixture!)
        calls (atom [])
        sensitive-value (str "fixture-" (UUID/randomUUID))
        source "https://packages.example.invalid/v3/index.json"
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
                                         "@packages.example.invalid")})))))
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
                                          "@packages.example.invalid")})))
              sensitive-value)))
    (is (empty? @calls))))

(deftest live-mode-reports-duplicate-conflict-and-partial-failure-without-a-leak
  (let [{:keys [options]} (fixture!)
        credential (str "fixture-" (UUID/randomUUID))
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
                     "https://packages.example.invalid/v3/index.json")))))]
    (is (= :duplicate-version-conflict (:failure (ex-data @caught))))
    (is (= [{:id "DripSharp.Fixture.Base"
             :position 0
             :version "1.2.3-alpha.1"}]
           (:completed (ex-data @caught))))
    (is (= "DripSharp.Fixture.Main"
           (get-in (ex-data @caught) [:failed :id])))
    (is (= :unknown
           (get-in (ex-data @caught) [:failed :remote-state])))
    (is (= [] (:remaining (ex-data @caught))))
    (is (= 2 (count @calls)))
    (is (every?
         #(= credential
             (get-in % [:environment
                        publisher/credential-environment-variable]))
         @calls))
    (is (not-any? #(some #{credential "--api-key" "-k"} (:command %))
                  @calls))
    (is (not-any? #(str/includes? (str/join " " (:command %)) "nuget.org")
                  @calls))
    (is (not (str/includes? (str (ex-message @caught) (ex-data @caught))
                            credential)))
    (is (not (str/includes? output credential)))))

(deftest live-mode-classifies-timeout-and-generic-errors
  (doseq [[expected push-fn]
          [[:timeout
            (fn [_]
              (throw (ex-info "timed out"
                              {:kind :command-timeout :timeout-ms 10})))]
           [:error (fn [_] {:exit 17 :output "ordinary failure"})]]]
    (let [{:keys [options]} (fixture!)
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
                    "https://packages.example.invalid/v3/index.json"
                    :timeout-seconds 1)))]
      (is (= expected (:failure (ex-data error))))
      (is (= [] (:completed (ex-data error))))
      (is (= "DripSharp.Fixture.Base"
             (get-in (ex-data error) [:failed :id])))
      (is (= ["DripSharp.Fixture.Main"]
             (mapv :id (:remaining (ex-data error))))))))
