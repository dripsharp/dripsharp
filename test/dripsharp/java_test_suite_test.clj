(ns dripsharp.java-test-suite-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.consumer-tests :as consumer-tests]
            [dripsharp.java-test-adapters :as adapters]
            [dripsharp.java-test-suite :as java-test-suite]
            [dripsharp.junit-xunit :as junit]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file CopyOption FileVisitOption Files OpenOption Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private suite-root
  (paths/absolute "validation/java-test-suite"))

(defn- temp-directory
  [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- write-text!
  [path text]
  (Files/createDirectories (.getParent (paths/path path))
                           (make-array FileAttribute 0))
  (Files/writeString (paths/path path) text (make-array OpenOption 0)))

(defn- copy-tree!
  [^Path source ^Path destination]
  (with-open [entries (Files/walk source (make-array FileVisitOption 0))]
    (doseq [^Path entry (sort-by str (map #(cast Path %) (.toArray entries)))]
      (let [relative (.relativize source entry)
            target (.resolve destination relative)]
        (if (Files/isDirectory entry (make-array java.nio.file.LinkOption 0))
          (Files/createDirectories target (make-array FileAttribute 0))
          (do
            (Files/createDirectories (.getParent target)
                                     (make-array FileAttribute 0))
            (Files/copy entry target
                        (into-array CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING])))))))
  destination)

(defn- regular-files
  [root]
  (with-open [entries (Files/walk (paths/path root)
                                  (make-array FileVisitOption 0))]
    (->> (.toArray entries)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (sort-by str)
         vec)))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (ex-data error))))

(defn- suite-contract-entry
  []
  {:source "contract.edn"
   :sha256 (util/sha256-file
            (paths/resolve-path suite-root "contract.edn"))})

(defn- project
  []
  {:id "DripSharp.CrossTarget.Generated.Tests"
   :directory "tests/DripSharp.CrossTarget.Generated.Tests"
   :assembly-name "DripSharp.CrossTarget.Generated.Tests"
   :target-framework "net10.0"
   :profile-references ["representative-product"]
   :project-references []
   :packages
   [{:id "Microsoft.NET.Test.Sdk" :version "17.14.1"}
    {:id "xunit" :version "2.9.3"}
    {:id "xunit.runner.visualstudio" :version "3.1.4"}
    {:id "Castle.Core" :version "5.1.1"}]})

(defn- target-contract
  [root]
  {:target :cross-target-java-proof
   :product-family :cross-target-java-proof
   :target-directory suite-root
   :profiles
   {"representative-product"
    {:destination
     {:configuration
      {:output {:project-file "RepresentativeProduct.csproj"}}}}}
   :publication
   {:kind :generated-repository
    :staging-path "product"
    :managed-paths ["src" "tests"]
    :profile-projects
    {"representative-product" "src/RepresentativeProduct"}
    :test-suites
    {:schema-version 2
     :projects [(project)]
     :strategies
     [{:id :cross-target-adapted-java
       :kind :adapted-upstream
       :policy :shipped
       :project "DripSharp.CrossTarget.Generated.Tests"
       :handler 'dripsharp.java-test-suite/strategy!
       :suite (suite-contract-entry)}]}}})

(defn- create-product-project!
  [root]
  (let [product-root (paths/resolve-path root "product/src/RepresentativeProduct")]
    (write-text!
     (paths/resolve-path product-root "RepresentativeProduct.csproj")
     (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
          "  <PropertyGroup>\n"
          "    <TargetFramework>net10.0</TargetFramework>\n"
          "    <Nullable>enable</Nullable>\n"
          "  </PropertyGroup>\n"
          "</Project>\n"))
    (write-text! (paths/resolve-path product-root "Marker.cs")
                 "namespace RepresentativeProduct; public sealed class Marker {}\n")))

(defn- emit!
  [root]
  (create-product-project! root)
  (let [contract (target-contract root)]
    {:contract contract
     :result (consumer-tests/emit!
              {:workspace-root root :target-contract contract})}))

(defn- inventory
  [emission]
  (util/read-single-edn-string!
   (slurp (str (paths/resolve-path
                (get-in emission [:result :tests-root])
                "DripSharp.CrossTarget.Generated.Tests/JAVA-TEST-INVENTORY.edn")))))

(deftest representative-suite-is-deterministic-and-fully-accounted
  (let [root (temp-directory "dripsharp-java-suite-")]
    (try
      (let [first-emission (emit! root)
            first-sums (slurp (str (get-in first-emission
                                           [:result :inventory-file])))
            first-inventory (inventory first-emission)
            second-emission (emit! root)
            second-sums (slurp (str (get-in second-emission
                                            [:result :inventory-file])))
            second-inventory (inventory second-emission)
            accounting (:accounting second-inventory)]
        (is (= first-sums second-sums))
        (is (= first-inventory second-inventory))
        (is (= 6 (count (:tests accounting))))
        (is (= 7 (count (:parameter-rows accounting))))
        (is (= 2 (count (get-in accounting [:helpers :java-types]))))
        (is (= 3 (count (get-in accounting [:helpers :java-methods]))))
        (is (= 1 (count (get-in accounting [:helpers :adapted-support]))))
        (is (= ["Fixtures/selected-case.txt"] (:fixtures accounting)))
        (is (= {:state :disabled
                :reason "upstream-disabled representative row"}
               (get-in accounting
                       [:enablement
                        (str "representative.sqltrellis."
                             "SqlTrellisRepresentativeTest#upstreamDisabled()")])))
        (is (= (get second-inventory :expected-accounting)
               (merge (:accounting-digests second-inventory)
                      {:fixtures (:fixtures accounting)})))
        (is (= 21 (:provenance-rows second-inventory)))
        (is (= 8 (count (:generated-files second-inventory))))
        (is (= {:tests 6 :parameter-rows 7 :helpers 6 :fixtures 1}
               (select-keys
                (java-test-suite/verify-generated!
                 (paths/resolve-path
                  (get-in second-emission [:result :tests-root])
                  "DripSharp.CrossTarget.Generated.Tests"))
                [:tests :parameter-rows :helpers :fixtures]))))
      (finally
        (delete-tree! root)))))

(deftest shipped-suite-restores-builds-and-runs-without-java-or-dripsharp
  (let [generation-root (temp-directory "dripsharp-java-suite-generation-")
        shipped-root (temp-directory "dripsharp-java-suite-shipped-")]
    (try
      (let [{:keys [result]} (emit! generation-root)
            source-product (.getParent ^Path (:tests-root result))]
        (copy-tree! source-product shipped-root)
        (is (not-any? #(str/ends-with? (str %) ".java")
                      (regular-files shipped-root)))
        (is (not-any?
             #(str/includes? (slurp (str %))
                             (str (paths/workspace-root)))
             (filter #(some (fn [suffix] (str/ends-with? (str %) suffix))
                            [".cs" ".csproj" ".edn" ".md" ".tsv"
                             ".targets"])
                     (regular-files shipped-root))))
        (let [project-path
              "tests/DripSharp.CrossTarget.Generated.Tests/DripSharp.CrossTarget.Generated.Tests.csproj"
              run
              (fn [command]
                (process/run!
                 {:command command
                  :directory shipped-root
                  :timeout-ms 180000
                  :unset-environment ["JAVA_HOME" "JDK_HOME" "CLASSPATH"]}))]
          (run ["dotnet" "restore" project-path])
          (run ["dotnet" "build" project-path "--configuration" "Release"
                "--no-restore"])
          (let [tested
                (run ["dotnet" "test" project-path "--configuration" "Release"
                      "--no-restore" "--no-build"])]
            (is (zero? (:exit tested)))
            (is (re-find #"Total:\s+13" (:output tested))))))
      (finally
        (delete-tree! generation-root)
        (delete-tree! shipped-root)))))

(deftest accounting-and-unsupported-symbol-perturbations-fail-closed
  (let [root (temp-directory "dripsharp-java-suite-perturb-")]
    (try
      (let [emission (emit! root)
            generated-inventory (inventory emission)
            expected (:expected-accounting generated-inventory)
            accounting (:accounting generated-inventory)]
        (doseq [[section perturb]
                [[:tests #(update % :tests pop)]
                 [:parameter-rows #(update % :parameter-rows pop)]
                 [:helpers #(update-in % [:helpers :java-methods] pop)]
                 [:fixtures #(assoc % :fixtures [])]
                 [:enablement #(assoc-in % [:enablement
                                            (-> % :enablement first key)
                                            :state]
                                         :disabled)]
                 [:framework-calls
                  #(update-in % [:framework-calls
                                 (-> % :framework-calls first key)] pop)]]]
          (let [error (failure #(java-test-suite/verify-accounting!
                                 expected (perturb accounting)))]
            (is (= :java-test-accounting-perturbation (:reason error))
                (name section))
            (is (= section (:section error)) (name section)))))
      (testing "a newly resolved unsupported assertion stops generation"
        (let [perturbed-root (.resolve root "perturbed-suite")]
          (copy-tree! suite-root perturbed-root)
          (let [source (.resolve perturbed-root
                                 "java/representative/pkl/PklRepresentativeTest.java")
                original (Files/readString source)]
            (Files/writeString
             source
             (str/replace original ".isEqualTo(2)"
                          ".hasSameHashCodeAs(2)")
             (make-array OpenOption 0))
            (let [contract-file (.resolve perturbed-root "contract.edn")
                  contract (util/read-single-edn-string!
                            (Files/readString contract-file))
                  contract (assoc-in contract [:sources 0 :sha256]
                                     (util/sha256-file source))
                  model (#'java-test-suite/resolved-model!
                         (paths/workspace-root) perturbed-root contract)
                  plan (junit/plan-suite model (adapters/junit-plan-options))
                  accounting (java-test-suite/build-accounting
                              perturbed-root contract model plan)
                  contract (assoc contract :expected-accounting
                                  (merge
                                   (java-test-suite/accounting-digests accounting)
                                   {:fixtures (:fixtures accounting)}))
                  error
                  (failure
                   #(java-test-suite/generate!
                     {:workspace-root (paths/workspace-root)
                      :suite-root perturbed-root
                      :contract contract
                      :project-root (.resolve root "unsupported-output")
                      :project (project)}))]
              (is (= :unsupported-java-test-call (:reason error)))
              (is (= :assertj (:framework error)))
              (is (= "hasSameHashCodeAs" (:operation error)))
              (is (str/starts-with? (:resolved-key error)
                                    "executable:org.assertj.core.api."))))))
      (finally
        (delete-tree! root)))))

(deftest existing-brine-and-pdfcarton-consumer-strategies-remain-intact
  (doseq [target [:pkl :pdfcube]]
    (let [root (temp-directory "dripsharp-existing-consumer-suite-")]
      (try
        (let [contract (target-directory/read-target target)
              emitted (consumer-tests/emit!
                       {:workspace-root root :target-contract contract})
              focused (first (filter #(= :focused-consumer (:kind %))
                                     (get-in contract
                                             [:publication :test-suites
                                              :strategies])))]
          (consumer-tests/verify-inventory! (:tests-root emitted))
          (doseq [[_ {:keys [source destination]}] (:profile-tests focused)]
            (is (= (slurp (str (paths/resolve-path
                                (:target-directory contract) source)))
                   (slurp (str (paths/resolve-path
                                (.getParent ^Path (:project-file emitted))
                                destination))))))
          (is (= :shipped (:policy focused))))
        (finally
          (delete-tree! root))))))
