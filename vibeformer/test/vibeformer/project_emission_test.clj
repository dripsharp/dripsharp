(ns vibeformer.project-emission-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-project :as project-emission]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtElement CtType]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-project-emission"
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
   :destination-bundle 'vibeformer.project-emission-test/minimal-rule-bundle
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
             :authors "Vibeformer"
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
   :public-surface {:strategy 'vibeformer.harness-test/java-library-test-surface-strategy}
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
     :annotation-decisions (constantly [])}
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

(deftest minimal-non-product-destination-uses-the-reusable-emitter
  (let [fixture (minimal-model)
        first (emit! fixture (temp-directory) 1 (minimal-rule-bundle))
        second (emit! fixture (temp-directory) 3 (minimal-rule-bundle))
        source (paths/resolve-path (:project-root first)
                                   "src/Example/Library/Greeting.cs")]
    (is (= :minimal-java-library (:rule-bundle first) (:rule-bundle second)))
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
                "// Translator: Vibeformer 0.1.0\n"
                "// IMPORTANT: This mechanically translated derivative has been changed "
                "from the upstream source.\n"
                "// Applicable upstream notices: see NOTICE.txt.\n"
                "#nullable enable\nnamespace Example.Library;\n\n"
                "public sealed class Greeting {}\n")
           (slurp (str source))))
    (is (= {:schema-version 1
            :translator "Vibeformer"
            :translator-version "0.1.0"
            :verified-files 1}
           (:mechanical-source-header-proof first)
           (:mechanical-source-header-proof second)))
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
        _ (Files/createDirectories (.getParent authored-source)
                                   (make-array FileAttribute 0))
        _ (Files/writeString authored-source
                             "// authored runtime\nnamespace Example.Runtime;\n"
                             (make-array OpenOption 0))
        bundle
        (assoc-in
         (minimal-rule-bundle)
         [:rules :destination-bridges :assets]
         (fn [_]
           [{:source (str authored-source)
             :destination "Example/Runtime/Runtime.cs"
             :strategy :reviewable-authored-runtime
             :missing-kind :missing-authored-runtime
             :missing-message "Authored runtime fixture is missing"}]))
        emission (emit! fixture (temp-directory) 2 bundle)
        authored-output
        (paths/resolve-path (:project-root emission)
                            "src/Example/Runtime/Runtime.cs")
        mechanical-output
        (paths/resolve-path (:project-root emission)
                            "src/Example/Library/Greeting.cs")]
    (is (= "// authored runtime\nnamespace Example.Runtime;\n"
           (slurp (str authored-output))))
    (is (str/starts-with? (slurp (str mechanical-output))
                          "// <auto-generated />\n"))
    (is (= 1 (get-in emission
                     [:mechanical-source-header-proof :verified-files])))
    (is (= #{:reviewable-authored-runtime}
           (set (keep :strategy (:artifacts emission)))))))

(deftest missing-and-unsupported-rules-fail-with-live-spoon-evidence
  (let [fixture (minimal-model)
        root-type (first (java/project-roots (:model fixture)))
        missing-bundle (update-in (minimal-rule-bundle)
                                  [:rules :resolved-mappings]
                                  dissoc :annotation-decisions)
        missing (caught #(emit! fixture (temp-directory) 1 missing-bundle))
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
    (testing "unsupported bundle selection fails closed at the same live root"
      (is (= :unsupported-destination-rule-bundle
             (:kind (ex-data unsupported))))
      (is (identical? root-type (:source-element (ex-data unsupported))))
      (is (= "type:example.Greeting" (:source-identity (ex-data unsupported))))
      (is (pos? (get-in (ex-data unsupported) [:source-location :line]))))))

(deftest reusable-emitter-loads-without-the-product-rule-bundle
  (let [source (slurp "src/vibeformer/java_project.clj")
        contract (project-emission/rule-contract)]
    (is (= #{:structural-declarations :resolved-mappings :namespace-policy
             :project-policy :resource-policy :destination-bridges}
           (set (keys (:required-components contract)))))
    (is (= {:product-runtime-assets #{:assets}
            :orchestration #{:validate-profile! :validate-project-input!}}
           (:optional-components contract)))
    (is (not (re-find #"(?i)vibeformer\\.pkl|org\\.pkl|Pkl\\.(?:Core|Parser)|research/pkl"
                      source)))))
