(ns dripsharp.target-directory-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.java-mapping-registry :as mapping-registry]
            [dripsharp.target-directory :as target-directory])
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
  {:schema-version 1
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
   :profiles
   [{:id "acme-core"
     :path "profiles/core.edn"
     :destination :core
     :mapping-overlays [:acme/core]
     :runtime-assets [:acme/runtime]
     :validation-contracts [:acme-core]
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
     :path "validation/contract.edn"}]})

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
     (let [target (target-directory/read-target root :acme)
           runner (get-in target
                          [:validation-contracts :acme-core :runner])]
       (is (= :validated (runner {})))))))
