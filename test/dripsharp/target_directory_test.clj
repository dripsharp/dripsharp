(ns dripsharp.target-directory-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.java-mapping-registry :as mapping-registry]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.target-execution :as target-execution]
            [dripsharp.util :as util])
  (:import [java.nio.file FileVisitOption Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn custom-validation-runner
  [_]
  :validated)

(defn- write-text!
  [^Path root relative text]
  (let [file (.resolve root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (spit (str file) text)
    file))

(defn- write-edn!
  [root relative value]
  (write-text! root relative (str (pr-str value) "\n")))

(defn- read-edn
  [root relative]
  (edn/read-string (slurp (str (.resolve ^Path root relative)))))

(defn- update-edn!
  [root relative f & args]
  (write-edn! root relative
              (apply f (read-edn root relative) args)))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- in-target-workspace
  [f]
  (let [root
        (Files/createTempDirectory
         "dripsharp-target-directory-"
         (make-array FileAttribute 0))]
    (try
      (f root)
      (finally
        (delete-tree! root)))))

(defn- baseline-record
  []
  {:schema-version 1
   :target :acme
   :upstream
   {:name "Acme"
    :version "1.0.0"
    :repository "https://example.invalid/acme.git"
    :revision "0123456789abcdef0123456789abcdef01234567"
    :license "Apache-2.0"
    :java-language-version 17
    :notice-reference "NOTICE.txt"}
   :artifacts {}
   :legal-sets
   {:upstream
    [{:kind :license
      :source "upstream/acme/LICENSE.txt"
      :destination "Legal/LICENSE.txt"
      :package-path "LICENSE.txt"
      :sha256
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
   :packages
   {"Acme.Core" {:version "1.0.0" :assembly-version "1.0.0.0"}}
   :profiles
   {:core
    {:profile "acme-core"
     :source-module "core"
     :package-id "Acme.Core"
     :source-counts {:ordinary 1 :generated 0}
     :public-contract-rows 1}}
   :contracts {}})

(defn- generation-profile
  []
  {:schema-version 1
   :profile "acme-core"
   :product-family :acme
   :baseline-target :acme
   :baseline-profile :core
   :project-root "upstream/acme"
   :gradle-project ":core"
   :gradle-java-major 17
   :destination-bundle 'acme.java-project/rule-bundle
   :destination-config "destinations/core.edn"
   :dependency-profiles []})

(defn- destination
  []
  {:schema-version 1
   :product-family :acme
   :baseline-target :acme
   :baseline-profile :core
   :baseline-legal-sets [:upstream]
   :destination-bundle 'acme.java-project/rule-bundle
   :project
   {:assembly-name "Acme.Core"
    :root-namespace "Acme.Core"
    :target-framework "net10.0"}
   :package {:id "Acme.Core"}
   :output {:project-directory "generated/acme-core"
            :project-file "Acme.Core.csproj"}
   :destination-capabilities #{:java-compat}
   :runtime-sources ["runtime/Acme.Core.Runtime.cs"]})

(defn- mapping-overlay
  []
  {:schema-version 1
   :target :acme
   :product-family :acme
   :id :acme/core
   :capabilities #{:acme/mapping}
   :custom-handlers {}
   :entries
   [{:id :acme/string-length
     :key "executable:java.lang.String#length()"
     :strategy :property-access
     :destination "Length"
     :caveats #{}
     :introduced-by :acme
     :evidence #{:acme/differential}}]})

(defn- differential-contract
  []
  {:schema-version 1
   :id :acme-core
   :target :acme
   :baseline-profile :core
   :failure-kind :acme-differential-failed
   :observation
   {:schema-version 1
    :header "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1"
    :columns [:family :id :value]
    :required-families ["basic"]
    :expected-count 1}
   :runner
   {:profile "acme-core"
    :output-directory "acme-core-differential"
    :context {:canonical "validation/canonical.tsv"}
    :required-files [:canonical]
    :required-directories []
    :canonical :canonical
    :oracle
    {:source "validation/oracle/AcmeOracle.java"
     :main-class "AcmeOracle"
     :arguments [:oracle]
     :include-resource-roots? false
     :compile-timeout-ms 1000
     :run-timeout-ms 1000}
    :probe
    {:source "validation/probe/AcmeProbe.cs"
     :arguments [:packaged]
     :build-timeout-ms 1000
     :run-timeout-ms 1000}
    :supported-hosts
    [{:os "linux" :architecture "x64" :runner "ubuntu-24.04"}]}
   :package-contract
   {:target-framework "net10.0"
    :assembly-name "Acme.Core"
    :assembly-dependencies []
    :dependencies []
    :legal-sets [:upstream]
    :resource-count 0
    :clean-builds 2}
   :summary {}})

(defn- target-manifest
  []
  {:schema-version 3
   :target :acme
   :product-family :acme
   :contracts
   {:product-goal "doc/targets/acme/product-goal.md"
    :port-scope "doc/targets/acme/port-scope.md"
    :dependencies ["doc/targets/acme/dependencies.md"]}
   :baseline "baseline.edn"
   :legal-policy "legal/policy.edn"
   :java
   {:source-language-version 17
    :runtime-major 17
    :preview-features? false}
   :capabilities #{:java-compat :acme/mapping :acme/runtime}
   :authorship
   {:compatibility "config/authored-compat.edn"
    :destination "authorship.edn"}
   :profiles
   [{:id "acme-core"
     :path "profiles/core.edn"
     :destination :core
     :mapping-overlays [:acme/core]
     :runtime-assets [:acme/runtime]
     :validation-contracts [:acme-core]
     :authorship
     {:sources [:acme/runtime]
      :evidence [:acme-required-proof]
      :review "acme-authorship-review"
      :budget {:authored-lines 2 :total-lines 2}}
     :required-capabilities
     #{:java-compat :acme/mapping :acme/runtime}}]
   :destinations [{:id :core :path "destinations/core.edn"}]
   :mapping-overlays [{:id :acme/core :path "mappings/core.edn"}]
   :runtime-assets
   [{:id :acme/runtime
     :path "runtime/Acme.Core.Runtime.cs"
     :capabilities #{:acme/runtime}}]
   :validation-contracts
   [{:id :acme-core
     :kind :differential
     :path "validation/contract.edn"}]
   :proof
   {:role :product
    :ladders
    [{:id :acme-required-proof
      :kind :target-validations
      :profiles ["acme-core"]
      :validation-contracts [:acme-core]
      :resource-class :high-memory}]}})

(defn- public-type-proof
  []
  {:count 0
   :sha256
   "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"})

(defn- shared-authorship
  []
  {:schema-version 1
   :scope :shared-compatibility
   :class :authored-compat
   :sources
   [{:id :shared/java-compat
     :kind :file
     :provenance "runtime/Shared.JavaCompat.cs"
     :include-pattern nil
     :charset nil
     :capability :java-compat
     :source-files 1
     :max-source-lines 1
     :max-emitted-lines 1
     :source-inventory-sha256
     (util/sha256-text "runtime/Shared.JavaCompat.cs")
     :public-types (public-type-proof)}]})

(defn- target-authorship
  []
  {:schema-version 1
   :scope :acme
   :class :authored-destination-runtime
   :sources
   [{:id :acme/runtime
     :kind :file
     :provenance "targets/acme/runtime/Acme.Core.Runtime.cs"
     :include-pattern nil
     :charset nil
     :capability nil
     :source-files 1
     :max-source-lines 1
     :max-emitted-lines 1
     :source-inventory-sha256
     (util/sha256-text "targets/acme/runtime/Acme.Core.Runtime.cs")
     :public-types (public-type-proof)}]})

(defn- legal-policy
  []
  {:schema-version 1
   :target :acme
   :upstream-license "Apache-2.0"
   :allowed-upstream-licenses #{"Apache-2.0"}
   :legal-sets #{:upstream}
   :profile-legal-sets {"acme-core" [:upstream]}})

(defn- create-target-workspace!
  [root]
  (doseq [[path text]
          [["doc/targets/acme/product-goal.md" "# Acme product goal\n"]
           ["doc/targets/acme/port-scope.md" "# Acme port scope\n"]
           ["doc/targets/acme/dependencies.md" "# Acme dependencies\n"]
           ["upstream/acme/LICENSE.txt" "Apache License\n"]
           ["upstream/acme/NOTICE.txt" "Acme notice\n"]
           ["upstream/acme/src/Acme.java" "class Acme {}\n"]
           ["runtime/Shared.JavaCompat.cs" "namespace Shared.Java;\n"]
           ["targets/acme/runtime/Acme.Core.Runtime.cs"
            "namespace Acme.Core;\n"]
           ["targets/acme/validation/oracle/AcmeOracle.java"
            "final class AcmeOracle {}\n"]
           ["targets/acme/validation/probe/AcmeProbe.cs"
            "namespace Acme.Probe;\n"]
           ["targets/acme/validation/canonical.tsv"
            (str "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1\n"
                 "basic\tidentity\tok\n")]]]
    (write-text! root path text))
  (doseq [[path value]
          [["targets/acme/target.edn" (target-manifest)]
           ["config/authored-compat.edn" (shared-authorship)]
           ["targets/acme/authorship.edn" (target-authorship)]
           ["targets/acme/baseline.edn" (baseline-record)]
           ["targets/acme/legal/policy.edn" (legal-policy)]
           ["targets/acme/profiles/core.edn" (generation-profile)]
           ["targets/acme/destinations/core.edn" (destination)]
           ["targets/acme/mappings/core.edn" (mapping-overlay)]
           ["targets/acme/validation/contract.edn"
            (differential-contract)]]]
    (write-edn! root path value))
  root)

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest conforming-target-directory-is-fully-validated
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (let [target (target-directory/read-target root :acme)
           registry (get-in target
                            [:mapping-overlays :acme/core :registry])]
       (is (= :acme (:target target)))
       (is (= :acme (:product-family target)))
       (is (= :product (get-in target [:proof :role])))
       (is (= [:acme-required-proof]
              (mapv :id (get-in target [:proof :ladders]))))
       (is (= #{"acme-core"} (set (keys (:profiles target)))))
       (is (= #{:java-compat :acme/mapping :acme/runtime}
              (:capabilities target)))
       (is (= :acme/string-length
              (:id
               (mapping-registry/registry-entry
                registry "executable:java.lang.String#length()"))))))))

(deftest manifest-schema-and-owned-paths-fail-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "unknown manifest keys are rejected"
       (update-edn! root "targets/acme/target.edn" assoc :unknown true)
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:unknown] (:unknown failure)))))
     (testing "target-owned paths cannot escape their area"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn!
        root "targets/acme/target.edn"
        update :runtime-assets
        (fn [assets]
          (mapv #(assoc % :path "../Acme.Core.Runtime.cs") assets)))
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= "../Acme.Core.Runtime.cs" (:path failure))))))))

(deftest identity-java-capability-and-legal-agreement-fail-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "baseline and manifest Java versions must agree"
       (update-edn! root "targets/acme/target.edn"
                    assoc-in [:java :source-language-version] 16)
       (is (= :invalid-target-directory
              (:kind
               (failure-data
                #(target-directory/read-target root :acme))))))
     (testing "every provided capability must be declared"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn! root "targets/acme/target.edn"
                    update :capabilities disj :acme/runtime)
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:acme/runtime] (:undeclared failure)))))
     (testing "destinations and legal policy must select the same legal sets"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn! root "targets/acme/destinations/core.edn"
                    assoc :baseline-legal-sets [])
       (is (= :invalid-target-directory
              (:kind
               (failure-data
                #(target-directory/read-target root :acme)))))))))

(deftest profile-destination-and-validation-identities-fail-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "profile and destination rule bundles must agree"
       (update-edn! root "targets/acme/profiles/core.edn"
                    assoc :destination-bundle 'wrong.bundle/rule-bundle)
       (is (= :invalid-target-directory
              (:kind
               (failure-data
                #(target-directory/read-target root :acme))))))
     (testing "oracle sources must be present in the oracle area"
       (write-edn! root "targets/acme/profiles/core.edn"
                   (generation-profile))
       (update-edn! root "targets/acme/validation/contract.edn"
                    assoc-in [:runner :oracle :source]
                    "validation/oracle/Missing.java")
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= "Validation oracle" (:subject failure))))))))

(deftest required-proof-ladders-cover-target-contracts-fail-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "every target profile remains in a required proof ladder"
       (update-edn! root "targets/acme/target.edn"
                    assoc-in [:proof :ladders 0 :profiles] [])
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :acme-required-proof (:ladder failure)))))
     (testing "every target validation remains in a required proof ladder"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn! root "targets/acme/target.edn"
                    assoc-in [:proof :ladders 0 :validation-contracts] [])
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :acme-required-proof (:ladder failure)))))
     (testing "the reusable conformance role is target- and resource-specific"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn! root "targets/acme/target.edn"
                    assoc-in [:proof :role]
                    :reusable-translator-conformance)
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :acme (:target failure)))
         (is (= :acme (:product-family failure))))))))

(deftest authored-source-contracts-and-evidence-fail-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "authored package budgets equal their reviewed source ceilings"
       (update-edn! root "targets/acme/target.edn"
                    assoc-in
                    [:profiles 0 :authorship :budget :authored-lines]
                    3)
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= 3
                (get-in failure
                        [:contract :budget :authored-lines])))))
     (testing "evidence must be a required ladder covering the profile"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn! root "targets/acme/target.edn"
                    assoc-in [:profiles 0 :authorship :evidence]
                    [:acme/missing-proof])
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :acme/missing-proof (:evidence failure)))))
     (testing "all shared groups stay neutral even when a profile omits them"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (write-text! root "runtime/Leaky.cs" "namespace Acme.Leak;\n")
       (update-edn!
        root "config/authored-compat.edn" update :sources conj
        {:id :shared/unselected
         :kind :file
         :provenance "runtime/Leaky.cs"
         :include-pattern nil
         :charset nil
         :capability :unused/compat
         :source-files 1
         :max-source-lines 1
         :max-emitted-lines 1
         :source-inventory-sha256
         (util/sha256-text "runtime/Leaky.cs")
         :public-types (public-type-proof)})
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :shared/unselected (:source failure)))
         (is (= "acme" (:fragment failure))))))))

(deftest custom-oracle-and-probe-contracts-remain-supported
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (update-edn!
      root "targets/acme/target.edn"
      assoc :validation-contracts
      [{:id :acme-core
        :kind :custom
        :path "validation/contract.edn"}])
     (write-edn!
      root "targets/acme/validation/contract.edn"
      {:schema-version 1
       :id :acme-core
       :target :acme
       :profile "acme-core"
       :baseline-profile :core
       :runner
       'dripsharp.target-directory-test/custom-validation-runner
       :oracle-sources ["validation/oracle/AcmeOracle.java"]
       :probe-sources ["validation/probe/AcmeProbe.cs"]
       :legal-sets [:upstream]})
     (update-edn!
      root "targets/acme/target.edn"
      update-in [:proof :ladders 0]
      assoc
      :kind :custom
      :runner
      'dripsharp.target-directory-test/custom-validation-runner)
     (let [target (target-directory/read-target root :acme)
           runner (get-in target
                          [:validation-contracts :acme-core :runner])]
       (is (= :validated (runner {})))
       (is (= [{:id :acme-required-proof
                :resource-class :high-memory
                :result :validated}]
              (target-execution/proof!
               {:workspace-root root :target :acme})))))))

(deftest conforming-unregistered-target-drives-every-generic-stage
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (let [generated (atom [])
           emitted-bundle (atom nil)
           base-rule-bundle
           {:rules
            {:resolved-mappings
             {:declarative-mapping-registries
              (fn [_] {:base :base-registry})}
             :structural-declarations
             {:create-template
              (fn [_ _] {:base-template true})}}}
           generate-fn
           (fn [{:keys [profile read-profile-fn read-destination-fn
                        emit-project-fn]
                 :as options}]
             (let [profile-record (read-profile-fn root profile)
                   destination
                   (read-destination-fn
                    root (:destination-config profile-record))]
               (emit-project-fn {:rule-bundle base-rule-bundle})
               (swap! generated conj
                      {:profile profile
                       :runtime-sources (:runtime-sources destination)})
               {:stage :generate :options options}))
           verify-fn
           (fn [{:keys [generate-fn] :as options}]
             {:stage :verify
              :generation (generate-fn options)})
           pack-fn
           (fn [{:keys [verify-fn] :as options}]
             {:stage :pack
              :verification (verify-fn options)})
           package-fn
           (fn [{:keys [pack-fn] :as options}]
             {:stage :package
              :packing (pack-fn options)})
           options
           {:workspace-root root
            :target :acme
            :profile "acme-core"
            :generate-fn generate-fn
            :verify-fn verify-fn
            :pack-fn pack-fn
            :package-fn package-fn
            :emit-project-fn
            (fn [{:keys [rule-bundle]}]
              (reset! emitted-bundle rule-bundle))}]
       (is (= :generate (:stage (target-execution/generate! options))))
       (is (= :verify (:stage (target-execution/verify! options))))
       (is (= :pack (:stage (target-execution/pack! options))))
       (is (= :package (:stage (target-execution/package! options))))
       (is (every?
            #(= ["targets/acme/runtime/Acme.Core.Runtime.cs"]
                (:runtime-sources %))
            @generated))
       (let [registries
             ((get-in @emitted-bundle
                      [:rules :resolved-mappings
                       :declarative-mapping-registries])
              {})
             template
             ((get-in @emitted-bundle
                      [:rules :structural-declarations
                       :create-template])
              {} {})]
         (is (= #{:base :acme/core} (set (keys registries))))
         (is (= :acme/string-length
                (:id
                 (mapping-registry/registry-entry
                  (:acme/core registries)
                  "executable:java.lang.String#length()"))))
         (is (= 1 (count (:target-mapping-registries template)))))
       (is (= {:target :acme
               :profile "acme-core"
               :contract-id :acme-core
               :oracle
               "targets/acme/validation/oracle/AcmeOracle.java"
               :probe
               "targets/acme/validation/probe/AcmeProbe.cs"}
              (first
               (target-execution/differential!
                (assoc options
                       :validation :acme-core
                       :differential-fn
                       (fn [{:keys [contract]}]
                         {:target (:target contract)
                          :profile (get-in contract [:runner :profile])
                          :contract-id (:id contract)
                          :oracle (get-in contract
                                          [:runner :oracle :source])
                          :probe (get-in contract
                                         [:runner :probe :source])}))))))
       (is (= [{:id :acme-required-proof
                :resource-class :high-memory
                :result
                [{:target :acme
                  :profile "acme-core"
                  :contract-id :acme-core}]}]
              (target-execution/proof!
               (assoc options
                      :differential-fn
                      (fn [{:keys [contract]}]
                        {:target (:target contract)
                         :profile (get-in contract [:runner :profile])
                         :contract-id (:id contract)})))))))))

(deftest target-execution-requires-explicit-selections
  (is (= :invalid-target-execution
         (:kind
          (failure-data
           #(target-execution/generate!
             {:target :acme :generate-fn identity})))))
  (is (= :invalid-target-execution
         (:kind
          (failure-data
           #(target-execution/generate!
             {:profile "acme-core" :generate-fn identity}))))))
