(ns dripsharp.authorship-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.authored-spdx :as authored-spdx]
            [dripsharp.authorship :as authorship]
            [dripsharp.java-project :as java-project]
            [dripsharp.target-directory :as target-directory])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- write-text!
  [^Path root relative text]
  (let [file (.resolve root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file text (make-array OpenOption 0))
    file))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- source-group
  [root id provenance capability max-emitted-lines]
  (let [base
        {:id id
         :kind :file
         :provenance provenance
         :include-pattern nil
         :charset nil
         :capability capability}
        observation (authorship/source-observation root base)]
    (merge
     base
     {:source-files (:files observation)
      :max-source-lines (:source-lines observation)
      :max-emitted-lines max-emitted-lines
      :source-inventory-sha256 (:source-inventory-sha256 observation)
      :public-types (:public-types observation)})))

(defn- normalized-groups
  [root class scope groups]
  (:sources
   (authorship/validate-source-contract!
    root scope class
    {:schema-version 1
     :scope scope
     :class class
     :sources groups})))

(defn- policy
  [compatibility-sources budget]
  {:schema-version authorship/policy-schema-version
   :target :fixture
   :profile "fixture"
   :package-id "Fixture.Package"
   :review "pkl-c8t.20"
   :evidence [:fixture/behavior-proof]
   :budget budget
   :forbidden-identities ["fixture"]
   :compatibility-sources compatibility-sources
   :destination-sources (sorted-map)
   :third-party-sources (sorted-map)})

(defn- create-ledger!
  [root artifacts contract]
  (let [project-root (.resolve ^Path root "generated")
        source-root (.resolve project-root "src")
        configuration
        {:package {:id "Fixture.Package"}
         :mechanical-source
         {:repository "https://example.invalid/upstream.git"
          :revision "1111111111111111111111111111111111111111"
          :notice-reference nil}}]
    (authorship/create-ledger!
     {:workspace-root root
      :project-root project-root
      :source-root source-root
      :artifacts artifacts
      :mechanical-source (:mechanical-source configuration)
      :mechanical-header java-project/mechanical-source-header
      :configuration configuration
      :contract contract})))

(deftest authored-spdx-policy-fails-closed-without-claiming-vendored-sources
  (let [root (Files/createTempDirectory
              "dripsharp-authored-spdx-"
              (make-array FileAttribute 0))]
    (try
      (let [policy
            {:schema-version authorship/spdx-policy-schema-version
             :decision "fixture-human-decision"
             :license-identifier "LicenseRef-Fixture"
             :file-copyright-text "2026 Fixture Owner"
             :repository-notice
             {:path "LICENSE"
              :sha256
              "ec71479127126ba0b470229578465117e755e36c287e83c887bd0172aa04f0ce"}}
            header
            (str "// SPDX-FileCopyrightText: 2026 Fixture Owner\n"
                 "// SPDX-License-Identifier: LicenseRef-Fixture\n\n")
            _ (write-text! root "LICENSE" "Fixture repository license.\n")
            _ (write-text! root "runtime/Compatibility.cs"
                           (str header
                                "internal static class Compatibility { }\n"))
            destination
            (write-text! root "runtime/Destination.cs"
                         (str header
                              "internal static class Destination { }\n"))
            _ (write-text! root "vendor/Library.cs"
                           "public sealed class VendorType { }\n")
            compatibility-groups
            (normalized-groups
             root :authored-compat :shared-compatibility
             [(source-group root :shared/compatibility
                            "runtime/Compatibility.cs" :java-compat 4)])
            destination-groups
            (normalized-groups
             root :authored-destination-runtime :fixture
             [(source-group root :fixture/destination
                            "runtime/Destination.cs" nil 4)])
            third-party-groups
            (normalized-groups
             root :vendored-third-party :fixture
             [(source-group root :fixture/vendor
                            "vendor/Library.cs" nil 1)])
            groups
            (concat (vals compatibility-groups)
                    (vals destination-groups)
                    (vals third-party-groups))]
        (let [verification
              (authorship/verify-authored-spdx-headers!
               root groups policy)]
          (is (= ["runtime/Compatibility.cs" "runtime/Destination.cs"]
                 (:paths verification)))
          (is (= (:repository-notice policy)
                 (:repository-notice verification))))
        (Files/writeString
         destination
         (str "// SPDX-FileCopyrightText: 2026 Different Owner\n"
              "// SPDX-License-Identifier: LicenseRef-Fixture\n\n"
              "internal static class Destination { }\n")
         (make-array OpenOption 0))
        (let [wrong-header
              (caught
               #(authorship/verify-authored-spdx-headers!
                 root groups policy))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data wrong-header))))
          (is (= "runtime/Destination.cs"
                 (:path (ex-data wrong-header))))
          (is (= "2026 Fixture Owner"
                 (:file-copyright-text (ex-data wrong-header)))))
        (let [missing-decision
              (caught
               #(authorship/verify-authored-spdx-headers!
                 root groups (assoc policy :decision "")))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data missing-decision))))
          (is (= "" (get-in (ex-data missing-decision)
                            [:policy :decision]))))
        (write-text! root "LICENSE" "Changed fixture repository license.\n")
        (let [changed-notice
              (caught
               #(authorship/verify-authored-spdx-headers!
                 root groups policy))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data changed-notice))))
          (is (= "LICENSE" (:path (ex-data changed-notice))))
          (is (= (get-in policy [:repository-notice :sha256])
                 (:expected (ex-data changed-notice))))
          (is (not= (:expected (ex-data changed-notice))
                    (:actual (ex-data changed-notice)))))
        (let [nested-notice
              (caught
               #(authorship/verify-authored-spdx-headers!
                 root groups
                 (assoc-in policy [:repository-notice :path]
                           "legal/LICENSE")))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data nested-notice))))
          (is (= "legal/LICENSE"
                 (get-in (ex-data nested-notice)
                         [:policy :repository-notice :path])))))
      (finally
        (delete-tree! root)))))

(deftest repository-authored-spdx-gate-loads-all-target-contracts
  (let [root (Files/createTempDirectory
              "dripsharp-authored-spdx-gate-"
              (make-array FileAttribute 0))]
    (try
      (let [policy
            {:schema-version authorship/spdx-policy-schema-version
             :decision "fixture-human-decision"
             :license-identifier "LicenseRef-Fixture"
             :file-copyright-text "2026 Fixture Owner"
             :repository-notice
             {:path "LICENSE"
              :sha256
              "ec71479127126ba0b470229578465117e755e36c287e83c887bd0172aa04f0ce"}}
            header
            (str "// SPDX-FileCopyrightText: 2026 Fixture Owner\n"
                 "// SPDX-License-Identifier: LicenseRef-Fixture\n\n")
            _ (write-text! root "LICENSE" "Fixture repository license.\n")
            _ (write-text! root authored-spdx/policy-path (pr-str policy))
            _ (write-text! root "targets/one/target.edn" "{}")
            _ (write-text! root "targets/two/target.edn" "{}")
            _ (write-text! root "targets/not-a-target/README.md" "ignored\n")
            _ (write-text! root "runtime/Compatibility.cs"
                           (str header "internal class Compatibility { }\n"))
            _ (write-text! root "runtime/One.cs"
                           (str header "internal class One { }\n"))
            _ (write-text! root "runtime/Two.cs"
                           (str header "internal class Two { }\n"))
            _ (write-text! root "vendor/Library.cs"
                           "public class VendorType { }\n")
            compatibility
            (normalized-groups
             root :authored-compat :shared-compatibility
             [(source-group root :shared/compatibility
                            "runtime/Compatibility.cs" :java-compat 4)])
            destination-one
            (normalized-groups
             root :authored-destination-runtime :one
             [(source-group root :one/runtime "runtime/One.cs" nil 4)])
            destination-two
            (normalized-groups
             root :authored-destination-runtime :two
             [(source-group root :two/runtime "runtime/Two.cs" nil 4)])
            third-party
            (normalized-groups
             root :vendored-third-party :fixture
             [(source-group root :fixture/vendor
                            "vendor/Library.cs" nil 1)])
            target-contract
            (fn [target destination]
              {:target target
               :authorship
               {:compatibility {:sources compatibility}
                :destination {:sources destination}
                :third-party {:sources third-party}}})]
        (with-redefs
         [target-directory/read-target
          (fn [_ target]
            (case target
              :one (target-contract :one destination-one)
              :two (target-contract :two destination-two)))]
          (is (= [:one :two] (authored-spdx/active-targets root)))
          (let [verification
                (authored-spdx/verify-policy-file!
                 root authored-spdx/policy-path [:one :two])]
            (is (= [:one :two] (:targets verification)))
            (is (= authored-spdx/policy-path
                   (:policy-file verification)))
            (is (= ["runtime/Compatibility.cs"
                    "runtime/One.cs"
                    "runtime/Two.cs"]
                   (:paths verification)))))
        (with-redefs
         [target-directory/read-target
          (fn [_ target]
            (case target
              :one (target-contract :one destination-one)
              :two
              (target-contract
               :two
               (assoc destination-one
                      :one/runtime
                      (assoc (:one/runtime destination-one)
                             :paths ["runtime/Two.cs"])))))]
          (let [conflict
                (caught
                 #(authored-spdx/verify-targets!
                   root [:one :two] policy))]
            (is (= :invalid-authored-spdx-gate
                   (:kind (ex-data conflict))))
            (is (= :one/runtime (:source (ex-data conflict)))))))
      (finally
        (delete-tree! root)))))

(deftest authored-policy-rejects-unlisted-and-unevidenced-package-code
  (let [root (Files/createTempDirectory
              "dripsharp-authorship-inventory-"
              (make-array FileAttribute 0))]
    (try
      (write-text! root "runtime/Neutral.cs"
                   "internal static class Neutral { }\n")
      (write-text! root "runtime/Extra.cs"
                   "internal static class Extra { }\n")
      (write-text! root "generated/src/Neutral.cs"
                   "internal static class Neutral { }\n")
      (write-text! root "generated/src/Extra.cs"
                   "internal static class Extra { }\n")
      (let [groups
            (normalized-groups
             root :authored-compat :shared-compatibility
             [(source-group root :shared/neutral "runtime/Neutral.cs"
                            :java-compat 1)])
            contract (policy groups {:authored-lines 1 :total-lines 1})
            artifacts
            [{:file "src/Neutral.cs"
              :source {:file "runtime/Neutral.cs"}
              :authorship-class :authored-compat}
             {:file "src/Extra.cs"
              :source {:file "runtime/Extra.cs"}
              :authorship-class :authored-compat}]
            unlisted (caught #(create-ledger! root artifacts contract))
            unevidenced
            (caught
             #(create-ledger!
               root
               [(first artifacts)]
               (assoc contract :evidence [])))]
        (is (= :invalid-authorship-ledger
               (:kind (ex-data unlisted))))
        (is (= ["runtime/Extra.cs"]
               (:unexpected (ex-data unlisted))))
        (is (= :invalid-authorship-ledger
               (:kind (ex-data unevidenced))))
        (is (= []
               (get-in (ex-data unevidenced) [:contract :evidence]))))
      (finally
        (delete-tree! root)))))

(deftest authored-policy-freezes-growth-public-types-and-package-fraction
  (let [root (Files/createTempDirectory
              "dripsharp-authorship-budget-"
              (make-array FileAttribute 0))]
    (try
      (let [source-text "public sealed class Neutral { }\n"
            _ (write-text! root "runtime/Neutral.cs" source-text)
            output (write-text! root "generated/src/Neutral.cs" source-text)
            groups
            (normalized-groups
             root :authored-compat :shared-compatibility
             [(source-group root :shared/neutral "runtime/Neutral.cs"
                            :java-compat 1)])
            exact-contract
            (policy groups {:authored-lines 1 :total-lines 1})
            authored-artifact
            {:file "src/Neutral.cs"
             :source {:file "runtime/Neutral.cs"}
             :authorship-class :authored-compat}]
        (is (= 1
               (:count
                (authorship/public-type-proof
                 [(str "public\n#if EXAMPLE\n#endif\n"
                       "sealed class SplitDeclaration\n{\n}\n")]))))
        (is (= [:fixture/behavior-proof]
               (get-in
                (create-ledger! root [authored-artifact] exact-contract)
                [:policy :sources 0 :evidence])))
        (Files/writeString
         output
         (str source-text "// unreviewed authored growth\n")
         (make-array OpenOption 0))
        (let [growth
              (caught
               #(create-ledger! root [authored-artifact] exact-contract))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data growth))))
          (is (= 1 (:expected-at-most (ex-data growth))))
          (is (= 2 (:actual (ex-data growth)))))
        (Files/writeString output
                           "public sealed class Changed { }\n"
                           (make-array OpenOption 0))
        (let [public-type
              (caught
               #(create-ledger! root [authored-artifact] exact-contract))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data public-type))))
          (is (not=
               (get-in (ex-data public-type) [:expected :sha256])
               (get-in (ex-data public-type) [:actual :sha256]))))
        (Files/writeString output source-text (make-array OpenOption 0))
        (let [configuration
              {:repository "https://example.invalid/upstream.git"
               :revision "1111111111111111111111111111111111111111"
               :notice-reference nil}
              upstream "example/Mechanical.java"
              header
              (java-project/mechanical-source-header configuration upstream)
              mechanical-text (str header "internal class Mechanical { }\n")
              mechanical-lines
              (count (str/split-lines mechanical-text))
              _ (write-text! root "generated/src/Mechanical.cs"
                             mechanical-text)
              mechanical-artifact
              {:file "src/Mechanical.cs"
               :upstream-source upstream
               :mechanical-source-header header}
              over-fraction-contract
              (assoc exact-contract :budget
                     {:authored-lines 1
                      :total-lines (+ mechanical-lines 2)})
              fraction
              (caught
               #(create-ledger!
                 root [authored-artifact mechanical-artifact]
                 over-fraction-contract))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data fraction))))
          (is (= {:numerator 1
                  :denominator (+ mechanical-lines 1)}
                 (:actual (ex-data fraction))))))
      (finally
        (delete-tree! root)))))

(deftest shared-authored-compatibility-is-product-neutral
  (let [root (Files/createTempDirectory
              "dripsharp-authorship-identity-"
              (make-array FileAttribute 0))]
    (try
      (let [text "namespace Fixture.Compatibility;\n"
            _ (write-text! root "runtime/Compatibility.cs" text)
            _ (write-text! root "generated/src/Compatibility.cs" text)
            groups
            (normalized-groups
             root :authored-compat :shared-compatibility
             [(source-group root :shared/compatibility
                            "runtime/Compatibility.cs" :java-compat 1)])
            contract (policy groups {:authored-lines 1 :total-lines 1})
            identity-error
            (caught
             #(create-ledger!
               root
               [{:file "src/Compatibility.cs"
                 :source {:file "runtime/Compatibility.cs"}
                 :authorship-class :authored-compat}]
               contract))]
        (is (= :invalid-authorship-ledger
               (:kind (ex-data identity-error))))
        (is (= "fixture" (:fragment (ex-data identity-error))))
        (is (= "runtime/Compatibility.cs"
               (:path (ex-data identity-error)))))
      (finally
        (delete-tree! root)))))

(deftest vendored-third-party-sources-are-contracted-but-not-authored
  (let [root (Files/createTempDirectory
              "dripsharp-third-party-inventory-"
              (make-array FileAttribute 0))]
    (try
      (let [source-text "public sealed class VendorType { }\n"
            _ (write-text! root "vendor/Library.cs" source-text)
            _ (write-text! root "generated/src/Library.cs" source-text)
            third-party-sources
            (normalized-groups
             root :vendored-third-party :fixture
             [(source-group root :fixture/vendor "vendor/Library.cs" nil 1)])
            contract
            (assoc
             (policy (sorted-map)
                     {:authored-lines 0 :total-lines 1})
             :third-party-sources third-party-sources)
            ledger
            (create-ledger!
             root
             [{:file "src/Library.cs"
               :source {:file "vendor/Library.cs"}
               :authorship-class :vendored-third-party}]
             contract)
            misclassified
            (caught
             #(create-ledger!
               root
               [{:file "src/Library.cs"
                 :source {:file "vendor/Library.cs"}
                 :authorship-class :authored-destination-runtime}]
               contract))]
        (is (= {:files 1
                :mechanical-lines 0
                :authored-compat-lines 0
                :authored-destination-runtime-lines 0
                :vendored-third-party-lines 1
                :authored-lines 0
                :total-lines 1
                :authored-fraction 0.0}
               (:totals ledger)))
        (is (= :vendored-third-party
               (get-in ledger [:files 0 :class])))
        (is (= :vendored-third-party
               (get-in ledger [:policy :sources 0 :class])))
        (is (= :invalid-authorship-ledger
               (:kind (ex-data misclassified))))
        (is (= :vendored-third-party
               (:expected (ex-data misclassified))))
        (is (= [:authored-destination-runtime]
               (:actual (ex-data misclassified)))))
      (finally
        (delete-tree! root)))))
