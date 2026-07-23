(ns vibeformer.harness-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [vibeformer.harness :as harness]
            [vibeformer.paths :as paths]
            [vibeformer.project-input :as project-input]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory "vibeformer-harness-test" (make-array FileAttribute 0)))

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

(defn- fake-destination [family bundle surface file]
  {:schema-version 1
   :product-family family
   :destination-bundle bundle
   :public-surface {:strategy surface}
   :file file})

(defn- preflight-error [profile destination]
  (let [root (temp-directory)
        stale (create-file! root "vibeformer/target/stale/output.cs")
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
   {:strategy 'vibeformer.harness-test/java-library-test-surface-strategy}})

(deftest pkl-and-rawhttp-profiles-use-configured-revisions
  (let [root (paths/workspace-root)
        pkl-parser (harness/read-profile root "pkl-parser")
        pkl-core (harness/read-profile root "pkl-core-value-model")
        rawhttp (harness/read-profile root "vibeformer/config/rawhttp-core.edn")]
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
        stale (create-file! root "vibeformer/target/stale/output.cs")
        discovery (fixture-discovery root)
        saw-clean-target? (atom false)
        config (harness/generate!
                {:workspace-root root
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
                   (assoc (fake-destination
                           :pkl 'vibeformer.pkl.java-project/rule-bundle
                           'vibeformer.harness-test/pkl-test-surface-strategy
                           config-file)
                          :fixture true :config-file config-file))
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
                              :shadow-symbols 0
                              :unresolved-symbols 0
                              :ambiguous-symbols 0
                              :fallback-symbols 0}}))
                 :emit-project-fn
                 (fn [{:keys [target]}]
                   {:project-file (paths/resolve-path target "fixture.csproj")
                    :summary {:compilation-units 2}})})]
    (is @saw-clean-target?)
    (is (paths/regular-file? (paths/resolve-path root "vibeformer/target/generation-config.edn")))
    (is (= 2 (count (get-in config [:project-input
                                    :production-sources]))))
    (is (= "vibeformer/config/pkl-parser.edn"
           (get-in config [:destination :config-file])))
    (is (= ["research/pkl/pkl-parser/src/main/java/A.java"
            "research/pkl/pkl-parser/src/main/java/B.java"]
           (get-in config [:project-input :production-sources])))))

(deftest checkout-verification-fails-before-output-cleanup-or-discovery
  (doseq [kind [:source-checkout-missing
                :source-checkout-uninitialized
                :source-revision-mismatch]]
    (let [root (temp-directory)
          stale (create-file! root "vibeformer/target/stale/output.cs")
          discovered? (atom false)
          bundle 'vibeformer.java-library/rule-bundle
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
        stale (create-file! root "vibeformer/target/stale/output.cs")
        bundle 'vibeformer.java-library/rule-bundle
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
        result (harness/generate!
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
                                        (assoc (fake-destination
                                                :pkl
                                                'vibeformer.pkl.java-project/rule-bundle
                                                'vibeformer.harness-test/pkl-test-surface-strategy
                                                file)
                                               :fixture true))
                 :build-resolved-closure-fn
                 (fn [_ _ seeds]
                   (swap! captured assoc :seeds seeds)
                   (spoon/map->ResolvedJavaClosure
                    {:totals {:declarations 1 :source-inputs 1
                              :type-references 0 :executable-references 0
                              :constructor-references 0 :field-references 0
                              :annotations 0 :symbols 0 :shadow-symbols 0
                              :unresolved-symbols 0 :ambiguous-symbols 0
                              :fallback-symbols 0}}))
                 :emit-project-fn
                 (fn [{:keys [resolved-model target]}]
                   (swap! captured assoc :resolved-model resolved-model)
                   {:project-file (paths/resolve-path target "core.csproj")
                    :summary {:compilation-units 1}})})]
    (is (= ":pkl-core" (get-in @captured [:discovery-options :gradle-project])))
    (is (= "vibeformer/config/pkl-core-value-model-destination.edn"
           (:destination-file @captured)))
    (is (= 48 (count (:seeds @captured))))
    (is (instance? vibeformer.spoon.ResolvedJavaClosure (:resolved-model @captured)))
    (is (= "pkl-core-value-model" (get-in result [:generation-profile :profile])))
    (let [written (edn/read-string
                   (slurp (str (paths/resolve-path root "vibeformer" "target"
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
        stale (create-file! root "vibeformer/target/stale/output.cs")
        error (try
                (harness/generate! {:workspace-root root :profile "missing"})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :unknown-generation-profile (:kind (ex-data error))))
    (is (paths/regular-file? stale))))

(deftest missing-destination-selection-fails-before-cleaning-output
  (let [root (temp-directory)
        stale (create-file! root "vibeformer/target/stale/output.cs")
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
  (let [java-bundle 'vibeformer.java-library/rule-bundle
        pkl-bundle 'vibeformer.pkl.java-project/rule-bundle
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
                 :runtime-sources ["vibeformer/runtime/product-only.cs"])]]]
    (doseq [[label expected profile destination] cases]
      (let [{:keys [error stale?]} (preflight-error profile destination)]
        (is (= expected (:kind (ex-data error))) (name label))
        (is stale? (str (name label) " must fail before output cleanup"))))))

(deftest non-product-identity-guard-covers-source-output-package-and-namespace
  (let [bundle 'vibeformer.java-library/rule-bundle
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
                " :destination-bundle vibeformer.java-library/rule-bundle\n"
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
                    :java-library 'vibeformer.java-library/rule-bundle
                    'vibeformer.harness-test/java-library-test-surface-strategy
                    file))
                 :build-resolved-model-fn
                 (fn [_ _]
                   (spoon/map->ResolvedJavaModel
                    {:totals {:compilation-units 2 :project-types 0
                              :type-references 0 :executable-references 0
                              :constructor-references 0 :field-references 0
                              :annotations 0 :symbols 0 :shadow-symbols 0
                              :unresolved-symbols 0 :ambiguous-symbols 0
                              :fallback-symbols 0}}))
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
             :destination-bundle 'vibeformer.java-library/rule-bundle
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
             :java-library 'vibeformer.java-library/rule-bundle
             'vibeformer.harness-test/java-library-test-surface-strategy
             file))
          :build-resolved-model-fn
          (fn [_ _]
            (spoon/map->ResolvedJavaModel
             {:totals {:compilation-units 2 :project-types 0 :type-references 0
                       :executable-references 0 :constructor-references 0
                       :field-references 0 :annotations 0 :symbols 0
                       :shadow-symbols 0 :unresolved-symbols 0
                       :ambiguous-symbols 0 :fallback-symbols 0}}))
          :emit-project-fn
          (fn [{:keys [target configuration]}]
            {:project-file (paths/resolve-path target (str (:file configuration) ".csproj"))
             :summary {:compilation-units 2}})})]
    (is (= ["dependency-b" "dependency-a"]
           (mapv :profile (:dependency-emissions result))))
    (is (= ["dependency-b.edn" "dependency-a.edn"]
           (mapv #(get-in % [:destination :file]) (:dependency-emissions result))))
    (is (= 2 (count @dependency-threads)))))
