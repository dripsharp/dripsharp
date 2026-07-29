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

(defn custom-package-proof-runner
  [{:keys [pack-fn]}]
  (pack-fn {:profile "acme-core"}))

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
      :sha256 (util/sha256-text "Apache License\n")}
     {:kind :notice
      :source "upstream/acme/NOTICE.txt"
      :destination "Legal/NOTICE.txt"
      :package-path "NOTICE.txt"
      :sha256 (util/sha256-text "Acme notice\n")}]}
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
   :package
   {:id "Acme.Core"
    :title "Acme Core"
    :description
    "Acme Core for .NET. This package is an independent translation and is not affiliated with UpstreamCo."
    :authors "DripSharp"}
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
  {:schema-version 4
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
    :destination "authorship.edn"
    :third-party "third-party.edn"}
   :profiles
   [{:id "acme-core"
     :path "profiles/core.edn"
     :destination :core
     :mapping-overlays [:acme/core]
     :runtime-assets [:acme/runtime]
     :validation-contracts [:acme-core]
     :authorship
     {:sources [:acme/runtime :acme/vendor]
      :evidence [:acme-required-proof]
      :review "acme-authorship-review"
      :budget {:authored-lines 2 :total-lines 3}}
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

(defn- third-party-authorship
  []
  {:schema-version 1
   :scope :acme
   :class :vendored-third-party
   :sources
   [{:id :acme/vendor
     :kind :file
     :provenance "vendor/acme/Vendor.cs"
     :include-pattern nil
     :charset nil
     :capability nil
     :source-files 1
     :max-source-lines 1
     :max-emitted-lines 1
     :source-inventory-sha256
     (util/sha256-text "vendor/acme/Vendor.cs")
     :public-types (public-type-proof)}]})

(defn- legal-policy
  []
  {:schema-version 4
   :target :acme
   :upstream-license "Apache-2.0"
   :allowed-upstream-licenses #{"Apache-2.0"}
   :legal-sets #{:upstream}
   :notice-appendix-sha256 nil
   :profile-legal-sets {"acme-core" [:upstream]}
   :resource-notice-legal-sets {"acme-core" [:upstream]}
   :package-metadata
   {"acme-core"
    {:required-description-fragments
     ["independent translation" "not affiliated with UpstreamCo"]
     :forbidden-identity-marks ["UpstreamCo"]}}})

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
           ["vendor/acme/Vendor.cs" "namespace Vendor.Acme;\n"]
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
           ["targets/acme/third-party.edn" (third-party-authorship)]
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
       (is (= :vendored-third-party
              (get-in target
                      [:profiles "acme-core" :authorship
                       :third-party-sources :acme/vendor :class])))
       (is (= [:upstream]
              (get-in target
                      [:profiles "acme-core"
                       :resource-notice-legal-sets])))
       (is (= #{:java-compat :acme/mapping :acme/runtime}
              (:capabilities target)))
       (is (= :acme/string-length
              (:id
               (mapping-registry/registry-entry
                registry "executable:java.lang.String#length()"))))))))

(deftest target-contract-edn-files-require-exactly-one-form
  (in-target-workspace
   (fn [root]
     (doseq [[path subject text reason]
             [["targets/acme/target.edn"
               "Target manifest"
               ""
               :empty-edn]
              ["targets/acme/target.edn"
               "Target manifest"
               "{"
               :invalid-edn]
              ["targets/acme/target.edn"
               "Target manifest"
               (str (pr-str (target-manifest)) "\n{:ignored true}\n")
               :trailing-data]
              ["targets/acme/destinations/core.edn"
               "Destination configuration"
               (str (pr-str (destination)) "\n{:ignored true}\n")
               :trailing-data]]]
       (create-target-workspace! root)
       (write-text! root path text)
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= subject (:subject failure)))
         (is (= reason (:reason failure)))
         (is (= (str (.resolve ^Path root path))
                (:path failure))))))))

(deftest target-contract-paths-reject-symlink-escapes
  (let [outside
        (Files/createTempDirectory
         "dripsharp-target-directory-outside-"
         (make-array FileAttribute 0))]
    (try
      (in-target-workspace
       (fn [root]
         (create-target-workspace! root)
         (let [target-root (.resolve ^Path root "targets/acme")
               outside-target-root (.resolve outside "escaped-target")]
           (delete-tree! target-root)
           (write-edn! outside-target-root "target.edn"
                       (target-manifest))
           (Files/createSymbolicLink
            target-root outside-target-root (make-array FileAttribute 0))
           (let [failure
                 (failure-data
                  #(target-directory/read-target root :acme))]
             (is (= :invalid-target-directory (:kind failure)))
             (is (= :outside-workspace (:reason failure)))
             (is (= :acme (:target failure)))))))
      (in-target-workspace
       (fn [root]
         (create-target-workspace! root)
         (let [manifest (.resolve ^Path root "targets/acme/target.edn")
               outside-manifest
               (write-edn! outside "target.edn" (target-manifest))]
           (Files/delete manifest)
           (Files/createSymbolicLink
            manifest outside-manifest (make-array FileAttribute 0))
           (let [failure
                 (failure-data
                  #(target-directory/read-target root :acme))]
             (is (= :invalid-target-directory (:kind failure)))
             (is (= :outside-workspace (:reason failure)))
             (is (= :acme (:target failure)))))))
      (in-target-workspace
       (fn [root]
         (create-target-workspace! root)
         (let [policy (.resolve ^Path root "targets/acme/legal/policy.edn")
               outside-policy
               (write-edn! outside "policy.edn" (legal-policy))]
           (Files/delete policy)
           (Files/createSymbolicLink
            policy outside-policy (make-array FileAttribute 0))
           (let [failure
                 (failure-data
                  #(target-directory/read-target root :acme))]
             (is (= :invalid-target-directory (:kind failure)))
             (is (= :outside-target-root (:reason failure)))
             (is (= "Legal policy" (:subject failure)))
             (is (= "legal/policy.edn" (:path failure)))))))
      (in-target-workspace
       (fn [root]
         (create-target-workspace! root)
         (let [goal
               (.resolve ^Path root "doc/targets/acme/product-goal.md")
               outside-goal
               (write-text! outside "product-goal.md"
                            "# External product goal\n")]
           (Files/delete goal)
           (Files/createSymbolicLink
            goal outside-goal (make-array FileAttribute 0))
           (let [failure
                 (failure-data
                  #(target-directory/read-target root :acme))]
             (is (= :invalid-target-directory (:kind failure)))
             (is (= :outside-workspace (:reason failure)))
             (is (= "Product goal" (:subject failure)))
             (is (= "doc/targets/acme/product-goal.md"
                    (:path failure)))))))
      (in-target-workspace
       (fn [root]
         (create-target-workspace! root)
         (let [source
               (.resolve ^Path root
                         "targets/acme/runtime/Acme.Core.Runtime.cs")
               outside-source
               (write-text! outside "Acme.Core.Runtime.cs"
                            "namespace External.Acme.Core;\n")]
           (Files/delete source)
           (Files/createSymbolicLink
            source outside-source (make-array FileAttribute 0))
           (let [failure
                 (failure-data
                  #(target-directory/read-target root :acme))]
             (is (= :invalid-target-directory (:kind failure)))
             (is (= :outside-workspace (:reason failure)))
             (is (= :acme/runtime (:source failure)))
             (is (= "targets/acme/runtime/Acme.Core.Runtime.cs"
                    (:provenance failure)))))))
      (finally
        (delete-tree! outside)))))

(deftest manifest-schema-and-owned-paths-fail-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "unknown manifest keys are rejected"
       (update-edn! root "targets/acme/target.edn" assoc :unknown true)
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [] (:path failure)))
         (is (= [:unknown] (:unknown failure)))
         (is (= [:unknown] (:unknown-keys failure)))
         (is (= [] (:missing-keys failure)))))
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

(deftest package-attribution-policy-fails-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "every profile has an exact package-metadata policy"
       (update-edn! root "targets/acme/legal/policy.edn"
                    assoc :package-metadata {})
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:legal-policy :package-metadata] (:path failure)))
         (is (= ["acme-core"] (:missing failure)))))
     (testing "configured package descriptions contain every required fragment"
       (write-edn! root "targets/acme/legal/policy.edn" (legal-policy))
       (update-edn! root "targets/acme/destinations/core.edn"
                    assoc-in [:package :description]
                    "Acme Core for .NET.")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:destinations :core :package :description]
                (:path failure)))
         (is (= "independent translation" (:fragment failure)))))
     (testing "publisher metadata must be a non-blank single-line identity"
       (doseq [authors
               (concat
                [nil ""]
                (map #(str "Publisher" % "Other")
                     ["\u0000" "\u0001" "\u000B" "\u000C" "\r" "\n"
                      "\u0085" "\u2028" "\u2029" "\uFFFE"]))]
         (write-edn! root "targets/acme/destinations/core.edn" (destination))
         (update-edn! root "targets/acme/destinations/core.edn"
                      assoc-in [:package :authors]
                      authors)
         (let [failure
               (failure-data #(target-directory/read-target root :acme))]
           (is (= :invalid-target-directory (:kind failure)))
           (is (= [:destinations :core :package :authors]
                  (:path failure))))))
     (testing "package descriptions must be non-blank text"
       (doseq [description [nil " " "Description\u0000suffix"]]
         (write-edn! root "targets/acme/destinations/core.edn" (destination))
         (update-edn! root "targets/acme/destinations/core.edn"
                      assoc-in [:package :description]
                      description)
         (let [failure
               (failure-data #(target-directory/read-target root :acme))]
           (is (= :invalid-target-directory (:kind failure)))
           (is (= [:destinations :core :package :description]
                  (:path failure))))))
     (testing "upstream-owner marks cannot become publisher identities"
       (write-edn! root "targets/acme/destinations/core.edn" (destination))
       (update-edn! root "targets/acme/destinations/core.edn"
                    assoc-in [:package :authors]
                    "UpstreamCo")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:destinations :core :package :authors]
                (:path failure)))
         (is (= "UpstreamCo" (:mark failure)))
         (is (= "UpstreamCo" (:actual failure))))))))

(deftest pinned-legal-input-content-fails-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "a changed legal source fails its direct package digest"
       (write-text! root "upstream/acme/LICENSE.txt" "changed license\n")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :acme (:target failure)))
         (is (= :upstream (:legal-set failure)))
         (is (= :license (:legal-kind failure)))
         (is (= (util/sha256-text "Apache License\n") (:expected failure)))
         (is (= (util/sha256-text "changed license\n") (:actual failure)))))
     (testing "a distinct source digest takes precedence over the package digest"
       (create-target-workspace! root)
       (update-edn!
        root "targets/acme/baseline.edn"
        (fn [baseline]
          (-> baseline
              (assoc-in [:legal-sets :upstream 1 :source-sha256]
                        (util/sha256-text "Acme notice\n"))
              (assoc-in [:legal-sets :upstream 1 :sha256]
                        (util/sha256-text
                         "Acme notice\n---\ntranslation appendix\n")))))
       (write-text! root "upstream/acme/NOTICE.txt" "changed notice\n")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :notice (:legal-kind failure)))
         (is (= (util/sha256-text "Acme notice\n") (:expected failure)))
         (is (= (util/sha256-text "changed notice\n") (:actual failure))))))))

(deftest resource-notice-attribution-policy-fails-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "every profile has an exact resource NOTICE policy"
       (update-edn! root "targets/acme/legal/policy.edn"
                    assoc :resource-notice-legal-sets {})
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:legal-policy :resource-notice-legal-sets]
                (:path failure)))
         (is (= ["acme-core"] (:missing failure)))))
     (testing "resource NOTICE sets must be selected by the profile"
       (write-edn! root "targets/acme/legal/policy.edn" (legal-policy))
       (update-edn! root "targets/acme/legal/policy.edn"
                    assoc-in
                    [:resource-notice-legal-sets "acme-core"]
                    [:unselected])
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:legal-policy :resource-notice-legal-sets
                 "acme-core"]
                (:path failure)))))
     (testing "resource NOTICE sets must contain pinned NOTICE input"
       (write-edn! root "targets/acme/legal/policy.edn" (legal-policy))
       (update-edn! root "targets/acme/baseline.edn"
                    update-in [:legal-sets :upstream]
                    #(vec (remove (fn [entry]
                                    (= :notice (:kind entry)))
                                  %)))
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :upstream (:legal-set failure)))
         (is (= [:legal-policy :resource-notice-legal-sets
                 "acme-core"]
                (:path failure))))))))

(deftest notice-translation-appendix-policy-fails-closed
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "every legal policy explicitly selects an appendix digest"
       (update-edn! root "targets/acme/legal/policy.edn"
                    dissoc :notice-appendix-sha256)
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:legal-policy] (:path failure)))
         (is (= [:notice-appendix-sha256] (:missing failure)))))
     (testing "appendix digests must use exact lowercase SHA-256 syntax"
       (write-edn!
        root "targets/acme/legal/policy.edn"
        (assoc (legal-policy) :notice-appendix-sha256 "ABC123"))
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:legal-policy :notice-appendix-sha256]
                (:path failure)))))
     (testing "an unapproved baseline appendix is rejected"
       (write-edn! root "targets/acme/legal/policy.edn" (legal-policy))
       (update-edn! root "targets/acme/baseline.edn"
                    assoc :notice-appendix "\nAcme translation appendix\n")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= [:legal-policy :notice-appendix-sha256]
                (:actual-path failure)))
         (is (nil? (:actual-value failure)))))
     (testing "a changed appendix fails its exact policy digest"
       (let [appendix "\nAcme translation appendix\n"]
         (write-edn! root "targets/acme/baseline.edn"
                     (assoc (baseline-record) :notice-appendix appendix))
         (write-edn!
          root "targets/acme/legal/policy.edn"
          (assoc (legal-policy)
                 :notice-appendix-sha256
                 "0000000000000000000000000000000000000000000000000000000000000000"))
         (let [failure
               (failure-data #(target-directory/read-target root :acme))]
           (is (= :invalid-target-directory (:kind failure)))
           (is (= (util/sha256-text appendix) (:expected-value failure)))
           (is (= [:legal-policy :notice-appendix-sha256]
                  (:actual-path failure))))))
     (testing "a pinned appendix requires a pinned NOTICE input"
       (let [appendix "\nAcme translation appendix\n"]
         (write-edn!
          root "targets/acme/baseline.edn"
          (-> (baseline-record)
              (assoc :notice-appendix appendix)
              (update-in
               [:legal-sets :upstream]
               #(vec (remove (fn [entry] (= :notice (:kind entry))) %)))))
         (write-edn!
          root "targets/acme/legal/policy.edn"
          (assoc (legal-policy)
                 :notice-appendix-sha256 (util/sha256-text appendix)))
         (let [failure
               (failure-data #(target-directory/read-target root :acme))]
           (is (= :invalid-target-directory (:kind failure)))
           (is (= [:baseline :legal-sets] (:path failure))))))
     (testing "an exact appendix digest passes preflight"
       (let [appendix "\nAcme translation appendix\n"]
         (write-edn! root "targets/acme/baseline.edn"
                     (assoc (baseline-record) :notice-appendix appendix))
         (write-edn!
          root "targets/acme/legal/policy.edn"
          (assoc (legal-policy)
                 :notice-appendix-sha256 (util/sha256-text appendix)))
         (is (= (util/sha256-text appendix)
                (get-in
                 (target-directory/read-target root :acme)
                 [:legal-policy :contract
                  :notice-appendix-sha256]))))))))

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

(deftest target-metadata-and-cross-contract-diagnostics-name-exact-paths
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (testing "target Java fields report their offending values and predicate"
       (update-edn! root "targets/acme/target.edn"
                    assoc-in [:java :runtime-major] "17")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= [:java :runtime-major] (:path failure)))
         (is (= "17" (:value failure)))
         (is (= "a positive integer" (:expected failure)))))
     (testing "profile and destination bundle disagreement names both contracts"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn! root "targets/acme/profiles/core.edn"
                    assoc :destination-bundle 'wrong.bundle/rule-bundle)
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= [:profiles "acme-core" :destination-bundle]
                (:path failure)))
         (is (= 'wrong.bundle/rule-bundle (:value failure)))
         (is (= [:destinations :core :destination-bundle]
                (:expected-path failure)))
         (is (= 'acme.java-project/rule-bundle
                (:expected-value failure)))))
     (testing "destination and baseline package disagreement names package id"
       (write-edn! root "targets/acme/profiles/core.edn"
                   (generation-profile))
       (update-edn! root "targets/acme/destinations/core.edn"
                    assoc-in [:package :id] "Acme.Other")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (= [:destinations :core :package :id] (:path failure)))
         (is (= "Acme.Other" (:value failure)))
         (is (= [:baseline :profiles :core :package-id]
                (:expected-path failure)))
         (is (= "Acme.Core" (:expected-value failure)))))
     (testing "validation and destination agreement names validation field"
       (write-edn! root "targets/acme/destinations/core.edn"
                   (destination))
       (update-edn! root "targets/acme/validation/contract.edn"
                    assoc-in [:package-contract :assembly-name]
                    "Acme.Other")
       (let [failure
             (failure-data #(target-directory/read-target root :acme))]
         (is (=
              [:validation-contracts :acme-core
               :package-contract :assembly-name]
              (:path failure)))
         (is (= "Acme.Other" (:value failure)))
         (is (= [:destinations :core :project :assembly-name]
                (:expected-path failure)))
         (is (= "Acme.Core" (:expected-value failure))))))))

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
         (is (= :acme-required-proof (:ladder failure)))
         (is (= [:proof :ladders 0 :profiles] (:path failure)))
         (is (= [] (:value failure)))))
     (testing "every target validation remains in a required proof ladder"
       (write-edn! root "targets/acme/target.edn" (target-manifest))
       (update-edn! root "targets/acme/target.edn"
                    assoc-in [:proof :ladders 0 :validation-contracts] [])
       (let [failure (failure-data
                      #(target-directory/read-target root :acme))]
         (is (= :invalid-target-directory (:kind failure)))
         (is (= :acme-required-proof (:ladder failure)))
         (is (= [:proof :ladders 0 :validation-contracts]
                (:path failure)))
         (is (= [] (:value failure)))))
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
         (is (= :acme/missing-proof (:evidence failure)))
         (is (= [:profiles "acme-core" :authorship :evidence 0]
                (:path failure)))
         (is (= :acme/missing-proof (:value failure)))))
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

(deftest custom-proof-runners-use-target-directory-stage-execution
  (in-target-workspace
   (fn [root]
     (create-target-workspace! root)
     (update-edn!
      root "targets/acme/target.edn"
      update-in [:proof :ladders 0]
      assoc
      :kind :custom
      :runner
      'dripsharp.target-directory-test/custom-package-proof-runner)
     (let [generate-fn
           (fn [{:keys [profile read-profile-fn]}]
             {:stage :generate
              :profile (:profile (read-profile-fn root profile))})
           verify-fn
           (fn [{:keys [generate-fn] :as options}]
             {:stage :verify
              :generation (generate-fn options)})
           pack-fn
           (fn [{:keys [verify-fn] :as options}]
             {:stage :pack
              :verification (verify-fn options)})]
       (is
        (=
         [{:id :acme-required-proof
           :resource-class :high-memory
           :result
           {:stage :pack
            :verification
            {:stage :verify
             :generation {:stage :generate :profile "acme-core"}}}}]
         (target-execution/proof!
          {:workspace-root root
           :target :acme
           :generate-fn generate-fn
           :verify-fn verify-fn
           :pack-fn pack-fn})))))))

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
                       :runtime-sources (:runtime-sources destination)
                       :resource-notice-attribution
                       (:resource-notice-attribution destination)})
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
       (is (every?
            #(= {:legal-sets [:upstream]
                 :package-paths ["NOTICE.txt"]}
                (:resource-notice-attribution %))
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
