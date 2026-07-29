(ns dripsharp.authorship-report-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.authorship-report :as report]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private commit
  "0123456789abcdef0123456789abcdef01234567")

(defn- fixture-package
  []
  (let [files
        [{:path "src/Mechanical.cs"
          :class :mechanical
          :source {:file "upstream/Mechanical.java"
                   :revision commit}
          :lines 90}
         {:path "src/Compatibility.cs"
          :class :authored-compat
          :provenance "runtime/Compatibility.cs"
          :sha256 (apply str (repeat 64 "a"))
          :lines 7}
         {:path "src/ProductRuntime.cs"
          :class :authored-destination-runtime
          :provenance "targets/acme/runtime/ProductRuntime.cs"
          :sha256 (apply str (repeat 64 "b"))
          :lines 3}]
        paths (mapv :path files)
        totals
        {:files 3
         :mechanical-lines 90
         :authored-compat-lines 7
         :authored-destination-runtime-lines 3
         :authored-lines 10
         :total-lines 100
         :authored-fraction 0.1}
        policy
        {:schema-version 1
         :target :acme
         :profile "acme-core"
         :package-id "Acme.Core"
         :review "review-42"
         :evidence [:acme-complete-proof]
         :budget {:authored-lines 10
                  :total-lines 100
                  :authored-fraction 0.1}
         :guarded-compatibility-sources 1
         :sources []}]
    {:profile "acme-core"
     :identity {:id "Acme.Core"
                :version "1.2.3"
                :sha256 (apply str (repeat 64 "c"))
                :file "Acme.Core.1.2.3.nupkg"}
     :ledger {:schema-version 2
              :files files
              :totals totals
              :policy policy}
     :verification
     {:schema-version 2
      :verified-files 3
      :source-paths paths
      :source-inventory-sha256
      (util/sha256-text (str/join "\n" paths))
      :totals totals
      :policy policy
      :assembly-input
      {:include "src/**/*.cs"
       :source-inventory-sha256
       (util/sha256-text (str/join "\n" paths))}}}))

(deftest report-is-derived-from-verified-ledgers-and-links-proof-contracts
  (let [root
        (Files/createTempDirectory
         "dripsharp-authorship-report-"
         (make-array FileAttribute 0))
        output (.resolve root "target/package-proof/release-evidence")
        written
        (report/write-report!
         {:workspace-root root
          :output-root output
          :repository-commit commit
          :packages [(fixture-package)]})
        data (edn/read-string (Files/readString (:edn written)))
        markdown (Files/readString (:markdown written))]
    (is (= {:mechanical-lines 90
            :authored-compat-lines 7
            :authored-destination-runtime-lines 3
            :authored-lines 10
            :total-lines 100}
           (get-in data [:packages 0 :lines])))
    (is (= [{:path "src/Compatibility.cs"
             :class :authored-compat
             :provenance "runtime/Compatibility.cs"
             :sha256 (apply str (repeat 64 "a"))
             :lines 7}
            {:path "src/ProductRuntime.cs"
             :class :authored-destination-runtime
             :provenance "targets/acme/runtime/ProductRuntime.cs"
             :sha256 (apply str (repeat 64 "b"))
             :lines 3}]
           (get-in data [:packages 0 :authored-files])))
    (is (= [:acme-complete-proof]
           (get-in data [:packages 0 :linked-proofs])))
    (is (= "targets/acme/target.edn"
           (get-in data [:proof-index 0 :contract])))
    (is (str/includes? markdown "| Mechanical | 90 |"))
    (is (str/includes? markdown "`runtime/Compatibility.cs`"))
    (is (str/includes? markdown
                       "[`:acme-complete-proof`](#proof-acme-acme-complete-proof)"))
    (is (str/includes? markdown
                       "../../../targets/acme/target.edn"))))

(deftest report-rejects-a-ledger-not-bound-to-its-verification
  (let [package (fixture-package)
        error
        (try
          (report/build-report
           commit
           [(assoc-in package
                      [:verification :totals :mechanical-lines]
                      89)])
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-authorship-boundary-report
           (:kind (ex-data error))))))
