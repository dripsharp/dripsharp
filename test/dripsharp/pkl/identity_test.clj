(ns dripsharp.pkl.identity-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.pkl.java-project :as java-project]
            [dripsharp.pkl.public-api-contract :as public-api]
            [dripsharp.target-directory :as target-directory]))

(defn- read-edn
  [path]
  (edn/read-string (slurp path)))

(def ^:private target (delay (read-edn "targets/pkl/target.edn")))
(def ^:private baseline (delay (read-edn "targets/pkl/baseline.edn")))
(def ^:private core (delay (read-edn "targets/pkl/destinations/core.edn")))
(def ^:private parser (delay (read-edn "targets/pkl/destinations/parser.edn")))

(def ^:private deferred-package-metadata
  {:authors "Vibeformer"
   :project-url "https://github.com/isaksky/pkl-net"
   :repository-url "https://github.com/isaksky/pkl-net.git"})

(deftest approved-brine-identities-govern-generated-dotnet-output
  (testing "the Pkl source target emits the Brine product family"
    (is (= :pkl (:target @target)))
    (is (= :brine (:product-family @target)
           (:product-family @core)
           (:product-family @parser)
           (:product-family (java-project/rule-bundle))
           (:product-family (public-api/strategy))
           (:product-family (target-directory/read-target :pkl))))
    (is (= #{"pkl-parser" "pkl-core-value-model"}
           (set (map :id (:profiles @target)))))
    (is (every? #(= :brine (:product-family %))
                [(read-edn "targets/pkl/profiles/parser.edn")
                 (read-edn "targets/pkl/profiles/core.edn")])))

  (testing "main assembly, package, project, root namespace, and mapping are Brine"
    (is (= {:assembly-name "DripSharp.Brine"
            :root-namespace "DripSharp.Brine"}
           (select-keys (:project @core) [:assembly-name :root-namespace])))
    (is (= "DripSharp.Brine" (get-in @core [:package :id])))
    (is (= "generated/brine/src/DripSharp.Brine"
           (get-in @core [:output :project-directory])))
    (is (= "DripSharp.Brine.csproj"
           (get-in @core [:output :project-file])))
    (is (= {"org.pkl.core" "DripSharp.Brine"}
           (:namespaces @core)
           (:namespace-prefixes @core))))

  (testing "parser assembly, package, project, and syntax mappings are Brine"
    (is (= {:assembly-name "DripSharp.Brine.Parser"
            :root-namespace "DripSharp.Brine.Parser"}
           (select-keys (:project @parser) [:assembly-name :root-namespace])))
    (is (= "DripSharp.Brine.Parser" (get-in @parser [:package :id])))
    (is (= "generated/brine/src/DripSharp.Brine.Parser"
           (get-in @parser [:output :project-directory])))
    (is (= "DripSharp.Brine.Parser.csproj"
           (get-in @parser [:output :project-file])))
    (is (= "DripSharp.Brine.Parser"
           (get-in @parser [:namespaces "org.pkl.parser"])))
    (is (= "DripSharp.Brine.Parser.Syntax"
           (get-in @parser [:namespaces "org.pkl.parser.syntax"])))
    (is (= "DripSharp.Brine.Parser.Syntax.Generic"
           (get-in @parser [:namespaces "org.pkl.parser.syntax.generic"])))
    (is (= ["../DripSharp.Brine.Parser/DripSharp.Brine.Parser.csproj"]
           (:project-references @core))))

  (testing "baselines, consumers, and reflected public metadata use Brine"
    (is (= #{"DripSharp.Brine" "DripSharp.Brine.Parser"}
           (set (keys (:packages @baseline)))))
    (is (= "DripSharp.Brine"
           (get-in @baseline [:profiles :core :package-id])))
    (is (= "DripSharp.Brine.Parser"
           (get-in @baseline [:profiles :parser :package-id])))
    (is (= "DripSharp.Brine.PackageConsumer.csproj"
           (get-in @core [:package-consumer :project-file])))
    (is (= "DripSharp.Brine.Parser.PackageConsumer.csproj"
           (get-in @parser [:package-consumer :project-file])))
    (let [package-surface (slurp "validation/public-api-contract/PackageSurface.tsv")
          body-candidates (slurp "validation/public-api-contract/BodyCandidates.tsv")]
      (is (str/includes? package-surface
                         "\nDripSharp.Brine\tDripSharp.Brine."))
      (is (str/includes? package-surface
                         "\nDripSharp.Brine.Parser\tDripSharp.Brine.Parser."))
      (is (not (re-find #"(?m)^Pkl[.](?:Core|Parser)\t" package-surface)))
      (is (not (re-find #"(?m)^Pkl[.](?:Core|Parser)\t" body-candidates)))))

  (testing "Pkl remains the upstream and language identity"
    (is (= "Pkl" (get-in @baseline [:upstream :name])))
    (is (= "https://github.com/apple/pkl.git"
           (get-in @baseline [:upstream :repository])))
    (is (= #{"pkl-core" "pkl-parser"}
           (set (map :source-module (vals (:profiles @baseline))))))
    (doseq [destination [@core @parser]]
      (is (str/includes? (get-in destination [:package :title]) "Pkl"))
      (is (str/includes? (get-in destination [:package :description]) "Pkl"))
      (is (str/includes? (get-in destination [:package :description])
                         "not affiliated with, endorsed by, or sponsored by Apple Inc."))))

  (testing "deferred publisher and repository metadata is untouched"
    (is (= deferred-package-metadata
           (select-keys (:package @core) (keys deferred-package-metadata))
           (select-keys (:package @parser) (keys deferred-package-metadata))))))
