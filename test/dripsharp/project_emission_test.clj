(ns dripsharp.project-emission-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.authorship :as authorship]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-project :as project-emission]
            [dripsharp.java-translate :as java]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtElement CtType]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-project-emission"
                             (make-array FileAttribute 0)))

(defn- minimal-model []
  (let [root (temp-directory)
        source (paths/resolve-path root "src/main/java/example/Greeting.java")
        _ (Files/createDirectories (.getParent source) (make-array FileAttribute 0))
        _ (Files/writeString source
                             "package example; public final class Greeting {}\n"
                             (make-array OpenOption 0))
        discovery {:schema-version 1
                   :project-id "project-emission-fixture"
                   :source-roots [(.getParent (.getParent (.getParent source)))]
                   :resource-roots []
                   :production-sources [source]
                   :generated-production-sources []
                   :production-resources []
                   :java-toolchain
                   {:home (paths/absolute (System/getProperty "java.home"))
                    :release 17
                    :preview-features? false}
                   :project-dependencies []
                   :external-dependencies []
                   :classpath-artifacts []}
        model (spoon/build-resolved-model! root discovery)]
    {:root root :source source :discovery discovery :model model}))

(defn- configuration []
  {:schema-version 1
   :product-family :java-library
   :destination-bundle 'dripsharp.project-emission-test/minimal-rule-bundle
   :mechanical-source
   {:repository "https://example.invalid/upstream/library.git"
    :revision "1111111111111111111111111111111111111111"
    :notice-reference "NOTICE.txt"}
   :project {:assembly-name "Example.Library"
             :root-namespace "Example.Library"
             :target-framework "net8.0"
             :nullable "enable"
             :implicit-usings false
             :warnings-as-errors true}
   :package {:id "Example.Library"
             :version "1.0.0"
             :title "Example library"
             :description "Minimal non-product Java destination."
             :authors "DripSharp"
             :tags "example"
             :project-url "https://example.invalid/library"
             :repository-url "https://example.invalid/library.git"
             :repository-type "git"}
   :output {:project-directory "generated/example"
            :source-directory "src"
            :resource-directory "resources"
            :project-file "Example.Library.csproj"
            :source-map-file "source-map.edn"
            :diagnostics-file "diagnostics.edn"
            :manifest-file "generation-manifest.edn"
            :public-metadata-file "public-metadata.edn"
            :annotation-decisions-file "annotation-decisions.edn"}
   :namespaces {"example" "Example.Library"}
   :public-surface {:strategy 'dripsharp.harness-test/java-library-test-surface-strategy}
   :resources {}})

(defn- source-ref [^CtElement element rule extra]
  (merge {:frontend-class (.getName (class element))
          :role (when (.isParentInitialized element)
                  (str (.getRoleInParent element)))
          :location (spoon/source-location element)
          :rule rule}
         extra))

(defn- minimal-rule-bundle []
  {:schema-version 1
   :id :minimal-java-library
   :product-family :java-library
   :runtime-capabilities
   {:labeled-control-flow
    {:exception-type "global::Example.Runtime.LabeledControlFlowSignal"}}
   :rules
   {:structural-declarations
    {:create-template (fn [_ _] {})
     :create-context
     (fn [options]
       (assoc options
              :declarations (atom [])
              :diagnostics (atom [])
              :body-translations (atom [])))
     :emit-root-node
     (fn [ctx ^CtType type]
       (let [qualified (.getQualifiedName type)
             name (.getSimpleName type)
             id (str "type:" qualified)
             source (source-ref type :java.declaration/class
                                {:declaration-id id :declaration-kind :type})]
         (swap! (:declarations ctx)
                conj {:id id :kind :type :owner nil :name name
                      :signature qualified
                      :destination {:assembly "Example.Library"
                                    :namespace "Example.Library"
                                    :owner "Example.Library.Greeting"
                                    :name name}
                      :source (source-ref type :java.declaration/class nil)})
         (csharp/with-source
           (csharp/raw "public sealed class Greeting {}")
           source)))
     :translate-member
     (fn [_ _ ^CtElement member]
       (throw (ex-info "Minimal destination received an unsupported declaration"
                       {:kind :unsupported-destination-rule
                        :source-element member
                        :source-identity (spoon/declaration-key member)
                        :source-location (spoon/source-location member)})))
     :merge-context!
     (fn [target source]
       (swap! (:declarations target) into @(:declarations source))
       (swap! (:diagnostics target) into @(:diagnostics source))
       (swap! (:body-translations target) into @(:body-translations source)))
     :context-results
     (fn [ctx]
       {:declarations @(:declarations ctx)
        :diagnostics @(:diagnostics ctx)
        :body-translations @(:body-translations ctx)})}
    :resolved-mappings
    {:type-node
     (fn [_ ^CtElement reference]
       (throw (ex-info "Minimal destination received an unsupported resolved type"
                       {:kind :unsupported-destination-rule
                        :source-element reference
                        :source-identity (spoon/declaration-key reference)
                        :source-location (spoon/source-location reference)})))
     :create-body-context (fn [_ _] nil)
     :annotation-decisions (constantly [])
     :declarative-mapping-registries (constantly {})
     :declarative-mapping-required? (fn [_ _] false)}
    :namespace-policy
    {:destination-namespace
     (fn [ctx ^CtType type]
       (or (get-in ctx [:configuration :namespaces
                        (some-> type .getPackage .getQualifiedName)])
           (throw (ex-info "Minimal destination has no namespace rule"
                           {:kind :unsupported-destination-rule
                            :source-element type
                            :source-identity (spoon/declaration-key type)
                            :source-location (spoon/source-location type)}))))
     :destination-file-name
     (fn [_ ^CtType type] (str (.getSimpleName type) ".cs"))}
    :project-policy project-emission/common-project-policy
    :resource-policy project-emission/common-resource-policy
    :destination-bridges {:assets (constantly [])}}})

(defn- directory-bytes [^Path root]
  (with-open [files (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (map (fn [^Path file]
                [(str (.relativize root file)) (vec (Files/readAllBytes file))]))
         (into (sorted-map)))))

(defn- emit! [{:keys [root discovery model]} target workers rule-bundle]
  (concurrency/call-with-executor
   {:worker-count workers}
   #(project-emission/emit-project!
     {:workspace-root root
      :target target
      :project-input discovery
      :resolved-model model
      :configuration (configuration)
      :rule-bundle rule-bundle})))

(defn- caught [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- verify-authorship!
  ([emission]
   (verify-authorship! emission (:authorship emission)))
  ([emission ledger]
   (authorship/verify-ledger!
    {:workspace-root (:workspace-root emission)
     :project-root (:project-root emission)
     :source-root (:source-root emission)
     :mechanical-source
     (get-in emission [:configuration :mechanical-source])
     :mechanical-header project-emission/mechanical-source-header
     :ledger ledger})))

(deftest minimal-non-product-destination-uses-the-reusable-emitter
  (let [fixture (minimal-model)
        first (emit! fixture (temp-directory) 1 (minimal-rule-bundle))
        second (emit! fixture (temp-directory) 22 (minimal-rule-bundle))
        source (paths/resolve-path (:project-root first)
                                   "src/Example/Library/Greeting.cs")]
    (is (= :minimal-java-library (:rule-bundle first) (:rule-bundle second)))
    (is (= {:schema-version 1
            :summary
            {:total-occurrences 3
             :mapping-required-occurrences 0
             :mapped-occurrences 0
             :unmapped-occurrences 0
             :used-mappings 0
             :unmapped-symbols 0}
            :used-mappings []
            :unmapped-symbols []
            :target :minimal-java-library}
           (:mapping-report first)
           (:mapping-report second)))
    (is (= {:compilation-units 1
            :generated-files 1
            :resources 0
            :declarations 1
            :declaration-kinds {:type 1}
            :source-mappings 1
            :missing-source-mappings 0
            :hard-failures 0
            :executable-roots 0
            :executable-coverage {:visited 0 :covered 0 :blocked 0
                                  :structural 0 :semantic 0
                                  :unsupported-elements 0 :missing-mappings 0
                                  :missing-occurrences 0 :fallback 0}
            :collisions 0
            :skipped-source-units 0}
           (:summary first)
           (:summary second)))
    (is (= (str "// <auto-generated />\n"
                "// Mechanically translated from: java/example/Greeting.java\n"
                "// Upstream repository: https://example.invalid/upstream/library.git\n"
                "// Upstream revision: 1111111111111111111111111111111111111111\n"
                "// Translator: DripSharp 0.1.0\n"
                "// IMPORTANT: This mechanically translated derivative has been changed "
                "from the upstream source.\n"
                "// Applicable upstream notices: see NOTICE.txt.\n"
                "#nullable enable\nnamespace Example.Library;\n\n"
                "public sealed class Greeting {}\n")
           (slurp (str source))))
    (is (= {:schema-version 1
            :translator "DripSharp"
            :translator-version "0.1.0"
            :verified-files 1}
           (:mechanical-source-header-proof first)
           (:mechanical-source-header-proof second)))
    (is (= {:schema-version 3
            :files
            [{:path "src/Example/Library/Greeting.cs"
              :class :mechanical
              :source
              {:file "java/example/Greeting.java"
               :revision "1111111111111111111111111111111111111111"}
              :lines 11}]
            :totals
            {:files 1
             :mechanical-lines 11
             :authored-compat-lines 0
             :authored-destination-runtime-lines 0
             :vendored-third-party-lines 0
             :authored-lines 0
             :total-lines 11
             :authored-fraction 0.0}
            :policy nil}
           (:authorship first)
           (:authorship second)))
    (is (= {:schema-version 3
            :verified-files 1
            :source-paths ["src/Example/Library/Greeting.cs"]
            :source-inventory-sha256
            (util/sha256-text "src/Example/Library/Greeting.cs")
            :totals (:totals (:authorship first))
            :policy nil}
           (verify-authorship! first)))
    (let [text (slurp (str source))
          artifact (clojure.core/first (:artifacts first))
          mapping (clojure.core/first (:mappings artifact))]
      (is (= "java/example/Greeting.java" (:upstream-source artifact)))
      (is (= (.indexOf text "public sealed class Greeting")
             (get-in mapping [:destination :start]))))
    (is (= (directory-bytes (:project-root first))
           (directory-bytes (:project-root second))))
    (is (zero? (:exit
                (process/run! {:directory (:project-root first)
                               :command ["dotnet" "build" (:project-file first)
                                         "--nologo" "--configuration" "Release"
                                         "--verbosity:quiet" "-warnaserror"]}))))
    (Files/writeString source
                       (str/replace-first (slurp (str source))
                                          "Upstream repository:"
                                          "Changed repository:")
                       (make-array OpenOption 0))
    (let [error (caught
                 #(project-emission/verify-mechanical-source-headers! first))]
      (is (= :mechanical-source-header-mismatch
             (:kind (ex-data error)))))))

(deftest batch-mapping-preflight-fails-before-translation
  (let [fixture (minimal-model)
        missing-key "type:java.example.Missing"
        model (update (:model fixture) :occurrences
                      conj
                      {:kind :type
                       :key missing-key
                       :origin :jdk
                       :resolution :runtime-class})
        translated? (atom false)
        bundle
        (-> (minimal-rule-bundle)
            (assoc-in
             [:rules :structural-declarations :create-template]
             (fn [& _]
               (reset! translated? true)
               {}))
            (assoc-in
             [:rules :resolved-mappings :declarative-mapping-required?]
             (fn [_ occurrence]
               (= missing-key (:key occurrence)))))
        target (temp-directory)
        error
        (caught
         #(emit! (assoc fixture :model model) target 1 bundle))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= :unmapped-resolved-symbols (:reason (ex-data error))))
    (is (= missing-key
           (get-in (ex-data error) [:diagnostic :resolved :key])))
    (is (= [{:resolved-key missing-key
             :kinds [:type]
             :origins [:jdk]
             :occurrences 1}]
           (:unmapped-symbols (ex-data error))))
    (is (false? @translated?))
    (is (empty? (seq (.listFiles (.toFile target)))))))

(deftest mechanical-header-contract-is-fail-closed-and-excludes-copied-assets
  (let [missing
        (caught
         #(project-emission/validate-configuration!
           (dissoc (configuration) :mechanical-source)))]
    (is (= :invalid-destination-configuration
           (:kind (ex-data missing))))
    (is (str/includes?
         (project-emission/mechanical-source-header
          {:repository "https://example.invalid/upstream/no-notice.git"
           :revision "5555555555555555555555555555555555555555"
           :notice-reference nil}
          "example/NoNotice.java")
         "// Applicable upstream notices: upstream supplies no NOTICE file.\n")))
  (let [fixture (minimal-model)
        authored-source (paths/resolve-path (:root fixture) "authored/Runtime.cs")
        destination-source
        (paths/resolve-path (:root fixture) "authored/Product.cs")
        third-party-source
        (paths/resolve-path (:root fixture) "vendor/Vendor.cs")
        _ (Files/createDirectories (.getParent authored-source)
                                   (make-array FileAttribute 0))
        _ (Files/writeString authored-source
                             "// authored compatibility\nnamespace Example.Runtime;\n"
                             (make-array OpenOption 0))
        _ (Files/writeString destination-source
                             "// destination runtime\nnamespace Example.Product;\n"
                             (make-array OpenOption 0))
        _ (Files/createDirectories (.getParent third-party-source)
                                   (make-array FileAttribute 0))
        _ (Files/writeString third-party-source
                             "// vendored source\nnamespace Vendor.Library;\n"
                             (make-array OpenOption 0))
        bundle
        (-> (minimal-rule-bundle)
            (assoc-in
             [:rules :destination-bridges :assets]
             (fn [_]
               [{:source (str authored-source)
                 :destination "Example/Runtime/Runtime.cs"
                 :strategy :reviewable-authored-runtime
                 :missing-kind :missing-authored-runtime
                 :missing-message "Authored runtime fixture is missing"}]))
            (assoc-in
             [:rules :product-runtime-assets :assets]
             (fn [_]
               [{:source (str destination-source)
                 :destination "Example/Product/Product.cs"
                 :strategy :reviewable-product-runtime
                 :missing-kind :missing-product-runtime
                 :missing-message "Product runtime fixture is missing"}
                {:source (str third-party-source)
                 :destination "Example/Vendor/Vendor.cs"
                 :authorship-class :vendored-third-party
                 :strategy :reviewable-third-party-source
                 :missing-kind :missing-third-party-source
                 :missing-message "Third-party source fixture is missing"}])))
        emission (emit! fixture (temp-directory) 2 bundle)
        authored-output
        (paths/resolve-path (:project-root emission)
                            "src/Example/Runtime/Runtime.cs")
        destination-output
        (paths/resolve-path (:project-root emission)
                            "src/Example/Product/Product.cs")
        mechanical-output
        (paths/resolve-path (:project-root emission)
                            "src/Example/Library/Greeting.cs")]
    (is (= "// authored compatibility\nnamespace Example.Runtime;\n"
           (slurp (str authored-output))))
    (is (= "// destination runtime\nnamespace Example.Product;\n"
           (slurp (str destination-output))))
    (is (str/starts-with? (slurp (str mechanical-output))
                          "// <auto-generated />\n"))
    (is (= 1 (get-in emission
                     [:mechanical-source-header-proof :verified-files])))
    (is (= #{:reviewable-authored-runtime :reviewable-product-runtime
             :reviewable-third-party-source}
           (set (keep :strategy (:artifacts emission)))))
    (is (= [:mechanical :authored-destination-runtime :authored-compat
            :vendored-third-party]
           (mapv :class (get-in emission [:authorship :files]))))
    (is (= {:files 4
            :mechanical-lines 11
            :authored-compat-lines 2
            :authored-destination-runtime-lines 2
            :vendored-third-party-lines 2
            :authored-lines 4
            :total-lines 17
            :authored-fraction (/ 4.0 17.0)}
           (get-in emission [:authorship :totals])))
    (let [authored-entry
          (first
           (filter #(= :authored-compat (:class %))
                   (get-in emission [:authorship :files])))]
      (is (= "authored/Runtime.cs" (:provenance authored-entry)))
      (is (= (util/sha256-file authored-output)
             (:sha256 authored-entry))))
    (let [authored-index
          (first
           (keep-indexed
            (fn [index file]
              (when (= :authored-compat (:class file)) index))
            (get-in emission [:authorship :files])))
          wrong-hash
          (assoc-in (:authorship emission) [:files authored-index :sha256]
                    (apply str (repeat 64 "0")))
          error (caught #(verify-authorship! emission wrong-hash))]
      (is (= :invalid-authorship-ledger (:kind (ex-data error)))))
    (let [unlisted
          (paths/resolve-path (:source-root emission)
                              "Example/Runtime/Unlisted.cs")
          _ (Files/writeString unlisted
                               "namespace Example.Runtime;\n"
                               (make-array OpenOption 0))
          error (caught #(verify-authorship! emission))]
      (is (= :invalid-authorship-ledger (:kind (ex-data error))))
      (is (= ["src/Example/Runtime/Unlisted.cs"]
             (:missing (ex-data error)))))))

(deftest missing-and-unsupported-rules-fail-with-live-spoon-evidence
  (let [fixture (minimal-model)
        root-type (first (java/project-roots (:model fixture)))
        missing-bundle (update-in (minimal-rule-bundle)
                                  [:rules :resolved-mappings]
                                  dissoc :annotation-decisions)
        missing (caught #(emit! fixture (temp-directory) 1 missing-bundle))
        missing-runtime
        (caught
         #(emit! fixture (temp-directory) 1
                 (dissoc (minimal-rule-bundle) :runtime-capabilities)))
        invalid-runtime
        (caught
         #(emit! fixture (temp-directory) 1
                 (assoc-in
                  (minimal-rule-bundle)
                  [:runtime-capabilities :labeled-control-flow :exception-type]
                  "Example.Runtime.UnqualifiedSignal")))
        unsupported-configuration
        (assoc (configuration)
               :destination-bundle 'missing.destination/rule-bundle)
        unsupported
        (caught
         #(concurrency/call-with-executor
           {:worker-count 1}
           (fn []
             (project-emission/emit-project!
              {:workspace-root (:root fixture)
               :target (temp-directory)
               :project-input (:discovery fixture)
               :resolved-model (:model fixture)
               :configuration unsupported-configuration}))))]
    (testing "missing composed capabilities fail before emitting output"
      (is (= :missing-destination-capability (:kind (ex-data missing))))
      (is (= :resolved-mappings (:component (ex-data missing))))
      (is (= :annotation-decisions (:capability (ex-data missing))))
      (is (identical? root-type (:source-element (ex-data missing))))
      (is (= "type:example.Greeting" (:source-identity (ex-data missing))))
      (is (pos? (get-in (ex-data missing) [:source-location :line]))))
    (testing "runtime type identities are a validated bundle capability"
      (is (= :missing-destination-capability
             (:kind (ex-data missing-runtime))))
      (is (= :runtime-capabilities
             (:component (ex-data missing-runtime))))
      (is (= :labeled-control-flow
             (:capability (ex-data missing-runtime))))
      (is (= :missing-destination-capability
             (:kind (ex-data invalid-runtime))))
      (is (= :invalid-translation-runtime-capability
             (get-in (ex-data invalid-runtime)
                     [:validation :kind]))))
    (testing "unsupported bundle selection fails closed at the same live root"
      (is (= :unsupported-destination-rule-bundle
             (:kind (ex-data unsupported))))
      (is (identical? root-type (:source-element (ex-data unsupported))))
      (is (= "type:example.Greeting" (:source-identity (ex-data unsupported))))
      (is (pos? (get-in (ex-data unsupported) [:source-location :line]))))))

(deftest reusable-emitter-loads-without-the-product-rule-bundle
  (let [source (slurp "src/dripsharp/java_project.clj")
        contract (project-emission/rule-contract)]
    (is (= #{:structural-declarations :resolved-mappings :namespace-policy
             :project-policy :resource-policy :destination-bridges}
           (set (keys (:required-components contract)))))
    (is (= #{:type-node :create-body-context :annotation-decisions
             :declarative-mapping-registries
             :declarative-mapping-required?}
           (get-in contract [:required-components :resolved-mappings])))
    (is (= {:labeled-control-flow #{:exception-type}}
           (:required-runtime-capabilities contract)))
    (is (= {:product-runtime-assets #{:assets}
            :orchestration #{:validate-profile! :validate-project-input!}}
           (:optional-components contract)))
    (is (not (re-find #"(?i)dripsharp\\.pkl|org\\.pkl|Pkl\\.(?:Core|Parser)|research/pkl"
                      source)))))
