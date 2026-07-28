(ns dripsharp.harness-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths]
            [dripsharp.project-input :as project-input]
            [dripsharp.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory "dripsharp-harness-test" (make-array FileAttribute 0)))

(defn- create-file!
  [^Path root relative]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file "test" (make-array OpenOption 0))
    file))

(defn- write-file!
  [^Path root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- fixture-discovery
  [root]
  (let [java-home (doto (paths/resolve-path root "toolchain")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source-a (create-file! root "research/pkl/pkl-parser/src/main/java/A.java")
        source-b (create-file! root "research/pkl/pkl-parser/src/main/java/B.java")
        resource (create-file! root "research/pkl/pkl-parser/src/main/resources/errorMessages.properties")
        resource-root (.getParent ^Path resource)
        classpath (create-file! root "cache/jspecify.jar")]
    {:schema-version 1
     :project-id ":pkl-parser"
     :project-root (paths/resolve-path root "research/pkl")
     :source-roots [(paths/resolve-path
                     root "research/pkl/pkl-parser/src/main/java")]
     :resource-roots [resource-root]
     :production-sources [source-b source-a]
     :generated-production-sources []
     :production-resources [resource]
     :java-toolchain {:home java-home :release 17
                      :preview-features? false}
     :project-dependencies []
     :external-dependencies []
     :classpath-artifacts [{:scope :compile :path classpath}]}))

(defn- without-live-baseline-input-gate
  [thunk]
  (with-redefs [baseline/validate-project-input!
                (fn [_workspace _target _profile input] input)]
    (thunk)))

(defn pkl-test-surface-strategy []
  {:schema-version 1 :id :pkl-test-surface :product-family :pkl
   :read! (fn [_ _] nil)
   :validate-selected! (fn [_ surface _] surface)
   :validate-generated! (fn [_ _] nil)
   :verify-compiled! (fn [& _] {})})

(defn java-library-test-surface-strategy []
  {:schema-version 1 :id :java-library-test-surface
   :product-family :java-library
   :read! (fn [_ _] nil)
   :validate-selected! (fn [_ surface _] surface)
   :validate-generated! (fn [_ _] nil)
   :verify-compiled! (fn [& _] {})})

(defn dag-test-surface-strategy []
  {:schema-version 1 :id :dag-test-surface
   :product-family :java-library
   :read! (fn [_ _] nil)
   :validate-selected! (fn [_ surface _] surface)
   :validate-generated! (fn [_ _] nil)
   :emission-boundary
   (fn [_ dependency-emissions]
     {:dependency-profiles (mapv :profile dependency-emissions)})
   :verify-compiled! (fn [& _] {})})

(defn- fake-destination [family bundle surface file]
  {:schema-version 1
   :product-family family
   :destination-bundle bundle
   :public-surface {:strategy surface}
   :file file})

(defn- preflight-error [profile destination]
  (let [root (temp-directory)
        stale (create-file! root "target/stale/output.cs")
        error (try
                (harness/generate!
                 {:workspace-root root
                  :profile "fixture"
                  :read-profile-fn (fn [_ _] profile)
                  :read-destination-fn (fn [_ _] destination)})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    {:error error :stale? (paths/regular-file? stale)}))

(defn- guarded-profile [bundle]
  {:schema-version 1 :profile "fixture" :product-family :java-library
   :project-root "." :gradle-project ":fixture"
   :destination-bundle bundle :destination-config "fixture.edn"
   :identity-guard {:forbidden-fragments ["pkl"]}})

(defn- guarded-destination [bundle]
  {:schema-version 1 :product-family :java-library
   :destination-bundle bundle
   :project {:assembly-name "Example.Library"
             :root-namespace "Example.Library"}
   :package {:id "Example.Library"}
   :output {:project-directory "generated/example"}
   :namespaces {"example" "Example.Library"}
   :public-surface
   {:strategy 'dripsharp.harness-test/java-library-test-surface-strategy}})

(deftest pkl-and-rawhttp-profiles-use-configured-revisions
  (let [root (paths/workspace-root)
        pkl-parser (harness/read-profile root "pkl-parser")
        pkl-core (harness/read-profile root "pkl-core-value-model")
        rawhttp (harness/read-profile root "config/rawhttp-core.edn")]
    (is (= "f7cac257ade5775c1dfc255f4fda2eacc296e9d0"
           (:revision pkl-parser)
           (:revision pkl-core)))
    (is (= "947cfdc619100a23f5e429ccb3c42ba6fedc8141"
           (:revision rawhttp)))
    (is (every? #(not (contains? % :source-verifier))
                [pkl-parser pkl-core rawhttp]))
    (is (true? (:require-clean-source rawhttp)))))

(deftest generation-cleans-output-and-writes-configuration
  (let [root (temp-directory)
        stale (create-file! root "target/stale/output.cs")
        discovery (fixture-discovery root)
        saw-clean-target? (atom false)
        config
        (without-live-baseline-input-gate
         #(harness/generate!
           {:workspace-root root
            :read-profile-fn
            (fn [_ profile-name]
              (harness/read-profile (paths/workspace-root) profile-name))
            :verify-checkout-fn
            (fn [_] {:path (paths/resolve-path root "research/pkl")
                     :revision "tracked-revision"})
            :discover-main-fn
            (fn [{:keys [manifest gradle-project]}]
              (reset! saw-clean-target?
                      (and (paths/directory? (.getParent ^Path manifest))
                           (not (paths/exists? stale))
                           (= ":pkl-parser" gradle-project)))
              (assoc discovery :project-id gradle-project))
            :read-destination-fn
            (fn [_ config-file]
              (assoc
               (java-project/read-configuration
                (paths/workspace-root) config-file)
               :public-surface
               {:strategy
                'dripsharp.harness-test/pkl-test-surface-strategy}
               :fixture true
               :config-file config-file))
            :build-resolved-model-fn
            (fn [_ _]
              (spoon/map->ResolvedJavaModel
               {:totals {:compilation-units 2
                         :project-types 0
                         :type-references 0
                         :executable-references 0
                         :constructor-references 0
                         :field-references 0
                         :annotations 0
                         :symbols 0
                         :project-occurrences 0
                         :jdk-occurrences 0
                         :dependency-occurrences 0
                         :intrinsic-occurrences 0
                         :type-parameter-occurrences 0}}))
            :emit-project-fn
            (fn [{:keys [target]}]
              {:project-file (paths/resolve-path target "fixture.csproj")
               :summary {:compilation-units 2}})}))]
    (is @saw-clean-target?)
    (is (paths/regular-file? (paths/resolve-path root "target/generation-config.edn")))
    (is (= 2 (count (get-in config [:project-input
                                    :production-sources]))))
    (is (= "config/pkl-parser.edn"
           (get-in config [:destination :config-file])))
    (is (= ["research/pkl/pkl-parser/src/main/java/A.java"
            "research/pkl/pkl-parser/src/main/java/B.java"]
           (get-in config [:project-input :production-sources])))))

(deftest checkout-verification-fails-before-output-cleanup-or-discovery
  (doseq [kind [:source-checkout-missing
                :source-checkout-uninitialized
                :source-revision-mismatch]]
    (let [root (temp-directory)
          stale (create-file! root "target/stale/output.cs")
          discovered? (atom false)
          bundle 'dripsharp.java-library/rule-bundle
          profile (assoc (guarded-profile bundle)
                         :project-root "research/example"
                         :revision (apply str (repeat 40 "a")))
          error
          (try
            (harness/generate!
             {:workspace-root root
              :profile "fixture"
              :read-profile-fn (fn [_ _] profile)
              :read-destination-fn (fn [_ _] (guarded-destination bundle))
              :verify-checkout-fn
              (fn [_]
                (throw (ex-info "configured checkout rejected" {:kind kind})))
              :discover-main-fn
              (fn [_]
                (reset! discovered? true)
                (throw (ex-info "discovery must not run" {})))})
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= kind (:kind (ex-data error))))
      (is (paths/regular-file? stale))
      (is (false? @discovered?)))))

(deftest dependency-checkouts-are-verified-before-cleanup-and-main-discovery
  (let [root (temp-directory)
        stale (create-file! root "target/stale/output.cs")
        bundle 'dripsharp.java-library/rule-bundle
        base (guarded-profile bundle)
        profiles {"main" (assoc base :profile "main"
                                :project-root "research/main"
                                :revision (apply str (repeat 40 "a"))
                                :dependency-profiles ["dependency"])
                  "dependency" (assoc base :profile "dependency"
                                      :project-root "research/dependency"
                                      :revision (apply str (repeat 40 "b")))}
        verified (atom [])
        discovered? (atom false)
        error
        (try
          (harness/generate!
           {:workspace-root root
            :profile "main"
            :read-profile-fn (fn [_ profile] (get profiles profile))
            :read-destination-fn (fn [_ _] (guarded-destination bundle))
            :verify-checkout-fn
            (fn [{:keys [project-root revision]}]
              (swap! verified conj (.getFileName ^Path project-root))
              (if (= "dependency" (str (.getFileName ^Path project-root)))
                (throw (ex-info "dependency checkout rejected"
                                {:kind :source-revision-mismatch}))
                {:path project-root :revision revision}))
            :discover-main-fn
            (fn [_]
              (reset! discovered? true)
              (throw (ex-info "discovery must not run" {})))})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :source-revision-mismatch (:kind (ex-data error))))
    (is (= ["main" "dependency"] (mapv str @verified)))
    (is (paths/regular-file? stale))
    (is (false? @discovered?))))

(deftest explicit-core-profile-selects-live-closure-path
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        captured (atom nil)
        result
        (without-live-baseline-input-gate
         #(harness/generate!
           {:workspace-root root
            :profile "pkl-core-value-model"
            :generate-dependencies? false
            :read-profile-fn (fn [_ profile-name]
                               (harness/read-profile (paths/workspace-root)
                                                     profile-name))
            :verify-checkout-fn
            (fn [_] {:path (paths/resolve-path root "research/pkl")
                     :revision "tracked-revision"})
            :discover-main-fn (fn [options]
                                (swap! captured assoc :discovery-options options)
                                (assoc discovery :project-id
                                       (:gradle-project options)))
            :read-destination-fn (fn [_ file]
                                   (swap! captured assoc :destination-file file)
                                   (-> (java-project/read-configuration
                                        (paths/workspace-root) file)
                                       (assoc
                                        :public-surface
                                        {:strategy
                                         'dripsharp.harness-test/pkl-test-surface-strategy}
                                        :fixture true)
                                       (assoc-in [:package :id]
                                                 "Pkl.Core.Fixture")))
            :build-resolved-closure-fn
            (fn [_ _ seeds]
              (swap! captured assoc :seeds seeds)
              (spoon/map->ResolvedJavaClosure
               {:totals {:declarations 1 :source-inputs 1
                         :type-references 0 :executable-references 0
                         :constructor-references 0 :field-references 0
                         :annotations 0 :symbols 0
                         :project-occurrences 0 :jdk-occurrences 0
                         :dependency-occurrences 0 :intrinsic-occurrences 0
                         :type-parameter-occurrences 0}}))
            :emit-project-fn
            (fn [{:keys [resolved-model target]}]
              (swap! captured assoc :resolved-model resolved-model)
              {:project-file (paths/resolve-path target "core.csproj")
               :summary {:compilation-units 1}})}))]
    (is (= ":pkl-core" (get-in @captured [:discovery-options :gradle-project])))
    (is (= "config/pkl-core-value-model-destination.edn"
           (:destination-file @captured)))
    (is (= 48 (count (:seeds @captured))))
    (is (instance? dripsharp.spoon.ResolvedJavaClosure (:resolved-model @captured)))
    (is (= "pkl-core-value-model" (get-in result [:generation-profile :profile])))
    (let [written (edn/read-string
                   (slurp (str (paths/resolve-path root "target"
                                                   "generation-config.edn"))))]
      (is (= (:generation-profile result) (:generation-profile written))))))

(deftest contract-and-behavior-seeds-merge-at-the-strongest-expansion
  (let [merged (harness/merge-seeds
                [{:key "type:Example" :expand :shell}
                 {:key "executable:Example#run()" :expand :body}]
                [{:key "type:Example" :expand :public-api
                  :members #{["method" "run" "0"]}}
                 {:key "type:Other" :expand :public-api
                  :members #{["field" "value" "0"]}}])]
    (is (= [{:key "executable:Example#run()" :expand :body}
            {:key "type:Example" :expand :public-api
             :members #{["method" "run" "0"]}}
            {:key "type:Other" :expand :public-api
             :members #{["field" "value" "0"]}}]
           merged))))

(deftest unknown-profile-fails-before-cleaning-output
  (let [root (temp-directory)
        stale (create-file! root "target/stale/output.cs")
        error (try
                (harness/generate! {:workspace-root root :profile "missing"})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :unknown-generation-profile (:kind (ex-data error))))
    (is (paths/regular-file? stale))))

(deftest missing-destination-selection-fails-before-cleaning-output
  (let [root (temp-directory)
        stale (create-file! root "target/stale/output.cs")
        profile (write-file!
                 root "profiles/missing-bundle.edn"
                 (str "{:schema-version 1\n"
                      " :profile \"missing-bundle\"\n"
                      " :product-family :java-library\n"
                      " :project-root \"example\"\n"
                      " :gradle-project \":library\"\n"
                      " :destination-config \"config/example.edn\"}\n"))
        error (try
                (harness/generate! {:workspace-root root :profile (str profile)})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-generation-profile (:kind (ex-data error))))
    (is (paths/regular-file? stale))))

(deftest destination-selection-fails-closed-before-cleaning-output
  (let [java-bundle 'dripsharp.java-library/rule-bundle
        pkl-bundle 'dripsharp.pkl.java-project/rule-bundle
        cases
        [[:unknown :unsupported-destination-rule-bundle
          (guarded-profile 'missing.destination/rule-bundle)
          (guarded-destination 'missing.destination/rule-bundle)]
         [:ambiguous :ambiguous-destination-rule-bundle
          (guarded-profile java-bundle) (guarded-destination pkl-bundle)]
         [:product-incompatible :product-incompatible-destination-rule-bundle
          (guarded-profile pkl-bundle) (guarded-destination pkl-bundle)]
         [:product-runtime :unsupported-product-runtime-assets
          (guarded-profile java-bundle)
          (assoc (guarded-destination java-bundle)
                 :runtime-sources ["runtime/product-only.cs"])]]]
    (doseq [[label expected profile destination] cases]
      (let [{:keys [error stale?]} (preflight-error profile destination)]
        (is (= expected (:kind (ex-data error))) (name label))
        (is stale? (str (name label) " must fail before output cleanup"))))))

(deftest non-product-identity-guard-covers-source-output-package-and-namespace
  (let [bundle 'dripsharp.java-library/rule-bundle
        profile (guarded-profile bundle)
        destination (guarded-destination bundle)
        cases
        [[:source (assoc profile :project-root "research/pkl-shadow") destination]
         [:output profile
          (assoc-in destination [:output :project-directory] "generated/pkl-shadow")]
         [:package profile (assoc-in destination [:package :id] "Pkl.Shadow")]
         [:namespace profile
          (assoc-in destination [:project :root-namespace] "Pkl.Shadow")]]]
    (doseq [[area profile destination] cases]
      (let [{:keys [error stale?]} (preflight-error profile destination)]
        (is (= :forbidden-product-identity (:kind (ex-data error))))
        (is (= area (:area (ex-data error))))
        (is stale?)))))

(deftest external-profile-configures-a-non-pkl-project
  (let [root (temp-directory)
        project-root (doto (paths/resolve-path root "examples/acme")
                       (Files/createDirectories (make-array FileAttribute 0)))
        _ (write-file!
           root "profiles/acme.edn"
           (str "{:schema-version 1\n"
                " :profile \"acme\"\n"
                " :product-family :java-library\n"
                " :project-root \"examples/acme\"\n"
                " :gradle-wrapper \"tools/gradlew\"\n"
                " :gradle-project \":library\"\n"
                " :destination-bundle dripsharp.java-library/rule-bundle\n"
                " :destination-config \"config/acme.edn\"\n"
                " :dependency-profiles [\"profiles/dependency.edn\"]}\n"))
        discovery (fixture-discovery root)
        captured (atom nil)
        result (harness/generate!
                {:workspace-root root
                 :profile "profiles/acme.edn"
                 :generate-dependencies? false
                 :discover-main-fn
                 (fn [options]
                   (reset! captured options)
                   (assoc discovery
                          :project-root project-root
                          :project-id (:gradle-project options)))
                 :read-destination-fn
                 (fn [_ file]
                   (fake-destination
                    :java-library 'dripsharp.java-library/rule-bundle
                    'dripsharp.harness-test/java-library-test-surface-strategy
                    file))
                 :build-resolved-model-fn
                 (fn [_ _]
                   (spoon/map->ResolvedJavaModel
                    {:totals {:compilation-units 2 :project-types 0
                              :type-references 0 :executable-references 0
                              :constructor-references 0 :field-references 0
                              :annotations 0 :symbols 0
                              :project-occurrences 0 :jdk-occurrences 0
                              :dependency-occurrences 0 :intrinsic-occurrences 0
                              :type-parameter-occurrences 0}}))
                 :emit-project-fn
                 (fn [{:keys [target]}]
                   {:project-file (paths/resolve-path target "acme.csproj")
                    :summary {:compilation-units 2}})})]
    (is (= project-root (paths/resolve-path root (:project-root @captured))))
    (is (= "tools/gradlew" (:gradle-wrapper @captured)))
    (is (= ":library" (:gradle-project @captured)))
    (is (= "acme" (get-in result [:generation-profile :profile])))
    (is (= {:path "examples/acme" :revision nil} (:source-project result)))
    (is (not (contains? result :submodule)))))

(deftest configuration-is-deterministic
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        reversed (-> discovery
                     (update :production-sources #(vec (reverse %)))
                     (update :production-resources #(vec (reverse %)))
                     (update :classpath-artifacts #(vec (reverse %))))]
    (is (= (harness/configuration root "revision"
                                  (project-input/validate! discovery))
           (harness/configuration root "revision"
                                  (project-input/validate! reversed))))))

(deftest independent-dependency-profiles-run-concurrently-and-collate-in-order
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        dependency-threads (atom #{})
        result
        (harness/generate!
         {:workspace-root root
          :worker-count 2
          :profile "main"
          :read-profile-fn
          (fn [_ profile-name]
            {:schema-version 1
             :profile profile-name
             :product-family :java-library
             :project-root "."
             :gradle-project (str ":" profile-name)
             :destination-bundle 'dripsharp.java-library/rule-bundle
             :destination-config (str profile-name ".edn")
             :dependency-profiles (when (= "main" profile-name) ["dependency-b" "dependency-a"])})
          :discover-main-fn
          (fn [{:keys [gradle-project]}]
            (when-not (= ":main" gradle-project)
              (swap! dependency-threads conj (.getName (Thread/currentThread)))
              (Thread/sleep 30))
            (assoc discovery :project-id gradle-project))
          :read-destination-fn
          (fn [_ file]
            (fake-destination
             :java-library 'dripsharp.java-library/rule-bundle
             'dripsharp.harness-test/java-library-test-surface-strategy
             file))
          :build-resolved-model-fn
          (fn [_ _]
            (spoon/map->ResolvedJavaModel
             {:totals {:compilation-units 2 :project-types 0 :type-references 0
                       :executable-references 0 :constructor-references 0
                       :field-references 0 :annotations 0 :symbols 0
                       :project-occurrences 0 :jdk-occurrences 0
                       :dependency-occurrences 0 :intrinsic-occurrences 0
                       :type-parameter-occurrences 0}}))
          :emit-project-fn
          (fn [{:keys [target configuration]}]
            {:project-file (paths/resolve-path target (str (:file configuration) ".csproj"))
             :summary {:compilation-units 2}})})]
    (is (= ["dependency-b" "dependency-a"]
           (mapv :profile (:dependency-emissions result))))
    (is (= ["dependency-b.edn" "dependency-a.edn"]
           (mapv #(get-in % [:destination :file]) (:dependency-emissions result))))
    (is (= 2 (count @dependency-threads)))))

(defn- dag-profile
  [profile dependencies]
  {:schema-version 1
   :profile profile
   :product-family :java-library
   :project-root "."
   :gradle-project (str ":" profile)
   :destination-bundle 'dripsharp.java-library/rule-bundle
   :destination-config (str profile ".edn")
   :dependency-profiles dependencies})

(defn- maven-dag-profile
  [profile dependencies]
  {:schema-version 1
   :profile profile
   :product-family :java-library
   :project-root "research/pdfbox"
   :revision "0123456789012345678901234567890123456789"
   :build-tool :maven
   :maven-project-id (str "org.example:" profile ":1.0.0")
   :maven-selected-projects [(str ":" profile)]
   :destination-bundle 'dripsharp.java-library/rule-bundle
   :destination-config (str profile ".edn")
   :dependency-profiles dependencies})

(defn- dag-destination
  [profile]
  {:schema-version 1
   :product-family :java-library
   :destination-bundle 'dripsharp.java-library/rule-bundle
   :project {:assembly-name (str "Package." profile)
             :root-namespace (str "Package." profile)}
   :package {:id (str "Package." profile)
             :version "1.0.0"}
   :output {:project-directory (str "generated/" profile)
            :project-file (str profile ".csproj")}
   :public-surface
   {:strategy 'dripsharp.harness-test/dag-test-surface-strategy}})

(defn- empty-model []
  (spoon/map->ResolvedJavaModel
   {:totals {:compilation-units 2 :project-types 0 :type-references 0
             :executable-references 0 :constructor-references 0
             :field-references 0 :annotations 0 :symbols 0
             :project-occurrences 0 :jdk-occurrences 0
             :dependency-occurrences 0 :intrinsic-occurrences 0
             :type-parameter-occurrences 0}}))

(deftest transitive-project-dag-generates-each-project-once-in-topological-order
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        dependencies
        {"io" []
         "fontbox" ["io"]
         "xmpbox" []
         "pdfbox" ["io" "fontbox"]
         "preflight" ["pdfbox" "xmpbox"]}
        prepared (atom [])
        destinations (atom [])
        checkouts (atom [])
        discoveries (atom [])
        models (atom [])
        emissions (atom {})
        result
        (harness/generate!
         {:workspace-root root
          :profile "preflight"
          :worker-count 3
          :read-profile-fn
          (fn [_ profile]
            (swap! prepared conj profile)
            (dag-profile profile (get dependencies profile)))
          :read-destination-fn
          (fn [_ file]
            (let [profile (subs file 0 (- (count file) 4))]
              (swap! destinations conj profile)
              (dag-destination profile)))
          :verify-checkout-fn
          (fn [{:keys [project-root revision]}]
            (swap! checkouts conj project-root)
            {:path project-root :revision revision})
          :discover-main-fn
          (fn [{:keys [gradle-project]}]
            (let [profile (subs gradle-project 1)]
              (swap! discoveries conj profile)
              (assoc discovery :project-id gradle-project)))
          :build-resolved-model-fn
          (fn [_ input]
            (swap! models conj (subs (:project-id input) 1))
            (empty-model))
          :emit-project-fn
          (fn [{:keys [target project-input configuration
                       public-api-boundary]}]
            (let [profile (subs (:project-id project-input) 1)
                  project-root
                  (paths/resolve-path
                   target (get-in configuration [:output :project-directory]))
                  emission
                  {:project-root project-root
                   :project-file
                   (paths/resolve-path
                    project-root
                    (get-in configuration [:output :project-file]))
                   :summary {:compilation-units 2}}]
              (swap! emissions assoc profile
                     {:configuration configuration
                      :boundary public-api-boundary})
              emission))})]
    (is (= ["io" "fontbox" "pdfbox" "xmpbox" "preflight"]
           (get-in result [:project-graph :topological-order])))
    (is (= ["io" "fontbox" "pdfbox" "xmpbox"]
           (mapv :profile (:dependency-emissions result))))
    (doseq [observed [@prepared @destinations @discoveries @models
                      (keys @emissions)]]
      (is (= (zipmap (keys dependencies) (repeat 1))
             (frequencies observed))))
    (is (= 5 (count @checkouts)))
    (is (= ["io"] (get-in @emissions ["fontbox" :boundary
                                      :dependency-profiles])))
    (is (= ["io" "fontbox"]
           (get-in @emissions ["pdfbox" :boundary :dependency-profiles])))
    (is (= ["io" "fontbox" "pdfbox" "xmpbox"]
           (get-in @emissions ["preflight" :boundary
                               :dependency-profiles])))
    (is (= ["../io/io.csproj" "../fontbox/fontbox.csproj"]
           (get-in @emissions ["pdfbox" :configuration
                               :project-references])))
    (is (= ["Package.io" "Package.fontbox"]
           (get-in @emissions ["pdfbox" :configuration
                               :package-dependencies])))
    (is (= ["../pdfbox/pdfbox.csproj" "../xmpbox/xmpbox.csproj"]
           (get-in @emissions ["preflight" :configuration
                               :project-references])))
    (is (= ["Package.pdfbox" "Package.xmpbox"]
           (get-in @emissions ["preflight" :configuration
                               :package-dependencies])))))

(deftest shared-maven-reactor-is-discovered-once-for-the-complete-project-dag
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        dependencies
        {"io" []
         "fontbox" ["io"]
         "xmpbox" []
         "pdfbox" ["io" "fontbox"]
         "preflight" ["pdfbox" "xmpbox"]}
        expected-order ["io" "fontbox" "pdfbox" "xmpbox" "preflight"]
        reactor-invocations (atom [])
        models (atom [])
        emissions (atom [])
        result
        (harness/generate!
         {:workspace-root root
          :profile "preflight"
          :worker-count 3
          :read-profile-fn
          (fn [_ profile]
            (maven-dag-profile profile (get dependencies profile)))
          :read-destination-fn
          (fn [_ file]
            (dag-destination (subs file 0 (- (count file) 4))))
          :verify-checkout-fn
          (fn [{:keys [project-root revision]}]
            {:path project-root :revision revision})
          :discover-main-fn
          (fn [_]
            (throw (ex-info "per-project discovery must not run" {})))
          :discover-reactor-fn
          (fn [options]
            (swap! reactor-invocations conj options)
            (mapv #(assoc discovery
                          :project-id (str "org.example:" % ":1.0.0"))
                  expected-order))
          :build-resolved-model-fn
          (fn [_ input]
            (swap! models conj (:project-id input))
            (empty-model))
          :emit-project-fn
          (fn [{:keys [target project-input configuration]}]
            (let [profile (second (str/split (:project-id project-input) #":"))
                  project-root
                  (paths/resolve-path
                   target (get-in configuration [:output :project-directory]))
                  emission
                  {:project-root project-root
                   :project-file
                   (paths/resolve-path
                    project-root
                    (get-in configuration [:output :project-file]))
                   :summary {:compilation-units 2}}]
              (swap! emissions conj profile)
              emission))})]
    (is (= 1 (count @reactor-invocations)))
    (is (= (mapv #(str ":" %) expected-order)
           (:selected-projects (first @reactor-invocations))))
    (is (= "research/pdfbox"
           (str (.relativize root
                             (:project-root (first @reactor-invocations))))))
    (is (= expected-order
           (get-in result [:project-graph :topological-order])))
    (is (= 1 (count (get-in result [:project-discovery :invocations]))))
    (is (= {:build-tool :maven
            :profiles expected-order
            :project-ids
            (mapv #(str "org.example:" % ":1.0.0") expected-order)
            :selected-projects (mapv #(str ":" %) expected-order)
            :manifest "target/maven-reactor-inputs-0.tsv"}
           (first (get-in result [:project-discovery :invocations]))))
    (is (= (zipmap (map #(str "org.example:" % ":1.0.0") expected-order)
                   (repeat 1))
           (frequencies @models)))
    (is (= (zipmap expected-order (repeat 1))
           (frequencies @emissions)))))

(deftest project-dependency-cycle-fails-before-checkout-or-output-cleanup
  (let [root (temp-directory)
        stale (create-file! root "target/stale/output.cs")
        dependencies {"a" ["b"] "b" ["c"] "c" ["a"]}
        prepared (atom [])
        checkout? (atom false)
        error
        (try
          (harness/generate!
           {:workspace-root root
            :profile "a"
            :read-profile-fn
            (fn [_ profile]
              (swap! prepared conj profile)
              (dag-profile profile (get dependencies profile)))
            :read-destination-fn
            (fn [_ file]
              (dag-destination (subs file 0 (- (count file) 4))))
            :verify-checkout-fn
            (fn [_]
              (reset! checkout? true)
              (throw (ex-info "checkout must not run" {})))})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :generation-profile-dependency-cycle (:kind (ex-data error))))
    (is (= ["a" "b" "c" "a"] (:cycle (ex-data error))))
    (is (= ["a" "b" "c"] @prepared))
    (is (re-find #"a -> b -> c -> a" (.getMessage error)))
    (is (false? @checkout?))
    (is (paths/regular-file? stale))))

(deftest declared-destination-references-must-match-the-resolved-graph
  (let [root (temp-directory)
        stale (create-file! root "target/stale/output.cs")
        checkout? (atom false)
        error
        (try
          (harness/generate!
           {:workspace-root root
            :profile "main"
            :read-profile-fn
            (fn [_ profile]
              (dag-profile profile (if (= "main" profile) ["dependency"] [])))
            :read-destination-fn
            (fn [_ file]
              (let [profile (subs file 0 (- (count file) 4))]
                (cond-> (dag-destination profile)
                  (= "main" profile)
                  (assoc :project-references ["../wrong/Wrong.csproj"]))))
            :verify-checkout-fn
            (fn [_]
              (reset! checkout? true)
              (throw (ex-info "checkout must not run" {})))})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :destination-dependency-graph-mismatch
           (:kind (ex-data error))))
    (is (= :project-references (:field (ex-data error))))
    (is (= ["../dependency/dependency.csproj"]
           (:expected (ex-data error))))
    (is (= ["../wrong/Wrong.csproj"] (:actual (ex-data error))))
    (is (false? @checkout?))
    (is (paths/regular-file? stale))))
