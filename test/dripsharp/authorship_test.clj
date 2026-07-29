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
        (Files/writeString
         destination
         (str header
              "internal static class Destination { }\n"
              "// SPDX-License-Identifier: LicenseRef-Conflicting\n")
         (make-array OpenOption 0))
        (let [conflicting-header
              (caught
               #(authorship/verify-authored-spdx-headers!
                 root groups policy))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data conflicting-header))))
          (is (= "runtime/Destination.cs"
                 (:path (ex-data conflicting-header))))
          (is (= {:file-copyright-text 1
                  :license-identifier 2}
                 (:spdx-marker-counts
                  (ex-data conflicting-header)))))
        (let [missing-decision
              (caught
               #(authorship/verify-authored-spdx-headers!
                 root groups (assoc policy :decision "")))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data missing-decision))))
          (is (= "" (get-in (ex-data missing-decision)
                            [:policy :decision]))))
        (doseq [field
                [:decision :license-identifier :file-copyright-text]
                separator
                ["\u0000" "\u000B" "\u000C" "\r" "\n"
                 "\u0085" "\u2028" "\u2029"]
                :let [value (str "Fixture" separator "Value")]]
          (let [malformed-policy
                (caught
                 #(authorship/verify-authored-spdx-headers!
                   root groups (assoc policy field value)))]
            (is (= :invalid-authorship-ledger
                   (:kind (ex-data malformed-policy))))
            (is (= value
                   (get-in (ex-data malformed-policy)
                           [:policy field])))))
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

(deftest authored-spdx-policy-rejects-physical-source-aliases
  (let [root (Files/createTempDirectory
              "dripsharp-authored-spdx-hard-link-"
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
            authored
            (write-text! root "runtime/Shared.cs"
                         (str header "internal class Shared { }\n"))
            alias (.resolve root "vendor/Shared.cs")
            _ (Files/createDirectories (.getParent alias)
                                       (make-array FileAttribute 0))
            _ (Files/createLink alias authored)
            authored-groups
            (normalized-groups
             root :authored-destination-runtime :fixture
             [(source-group root :fixture/runtime
                            "runtime/Shared.cs" nil 4)])
            vendored-groups
            (normalized-groups
             root :vendored-third-party :fixture
             [(source-group root :fixture/vendor
                            "vendor/Shared.cs" nil 4)])
            conflict
            (caught
             #(authorship/verify-authored-spdx-headers!
               root
               (concat (vals authored-groups) (vals vendored-groups))
               policy))]
        (is (= :invalid-authorship-ledger
               (:kind (ex-data conflict))))
        (is (= :physical-source-alias
               (:reason (ex-data conflict))))
        (is (= [{:paths ["runtime/Shared.cs" "vendor/Shared.cs"]
                 :usages
                 [{:group :fixture/runtime
                   :class :authored-destination-runtime}
                  {:group :fixture/vendor
                   :class :vendored-third-party}]}]
               (:conflicts (ex-data conflict)))))
      (finally
        (delete-tree! root)))))

(deftest repository-authored-spdx-gate-loads-all-target-contracts
  (let [root (Files/createTempDirectory
              "dripsharp-authored-spdx-gate-"
              (make-array FileAttribute 0))]
    (try
      (let [policy
            {:schema-version authorship/spdx-policy-schema-version
             :decision authored-spdx/required-decision
             :license-identifier "LicenseRef-Fixture"
             :file-copyright-text "2026 Fixture Owner"
             :package-publisher "Fixture Publisher"
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
            _ (write-text! root "targets/missing/README.md" "unmanifested\n")
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
               :profiles
               (into
                (sorted-map)
                (map
                 (fn [[source _]]
                   [(str (namespace source) "-" (name source))
                    {:destination
                     {:configuration
                      {:package
                       {:id (str "Fixture." (namespace source) "."
                                 (name source))
                        :authors "Fixture Publisher"}}}}])
                 destination))
               :authorship
               {:compatibility {:sources compatibility}
                :destination {:sources destination}
                :third-party {:sources third-party}}})]
        (let [missing-manifest
              (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data missing-manifest))))
          (is (= :missing-target-manifest
                 (:reason (ex-data missing-manifest))))
          (is (= ["missing"]
                 (:targets (ex-data missing-manifest)))))
        (delete-tree! (.resolve root "targets/missing"))
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
            (is (= "Fixture Publisher"
                   (:package-publisher verification)))
            (is (= [{:target :one
                     :profile "one-runtime"
                     :package-id "Fixture.one.runtime"}
                    {:target :two
                     :profile "two-runtime"
                     :package-id "Fixture.two.runtime"}]
                   (:packages verification)))
            (is (= ["runtime/Compatibility.cs"
                    "runtime/One.cs"
                    "runtime/Two.cs"]
                   (:paths verification)))))
        (let [wrong-decision
              (caught
               #(authored-spdx/verify-targets!
                 root
                 [:one :two]
                 (assoc policy :decision "fixture-human-decision")))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data wrong-decision))))
          (is (= authored-spdx/required-decision
                 (:expected-decision (ex-data wrong-decision))))
          (is (= "fixture-human-decision"
                 (:actual-decision (ex-data wrong-decision)))))
        (let [missing-publisher
              (caught
               #(authored-spdx/verify-targets!
                 root [:one :two] (dissoc policy :package-publisher)))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data missing-publisher))))
          (is (contains? (:expected-keys (ex-data missing-publisher))
                         :package-publisher)))
        (doseq [separator
                ["\u0000" "\u000B" "\u000C" "\r" "\n"
                 "\u0085" "\u2028" "\u2029"]
                :let [publisher
                      (str "Fixture Publisher"
                           separator
                           "Different Publisher")]]
          (let [malformed-publisher
                (caught
                 #(authored-spdx/verify-targets!
                   root [:one :two]
                   (assoc policy :package-publisher publisher)))]
            (is (= :invalid-authored-spdx-gate
                   (:kind (ex-data malformed-publisher))))
            (is (= publisher
                   (get-in (ex-data malformed-publisher)
                           [:policy :package-publisher])))))
        (with-redefs
         [target-directory/read-target
          (fn [_ target]
            (let [contract
                  (case target
                    :one (target-contract :one destination-one)
                    :two (target-contract :two destination-two))]
              (if (= :two target)
                (assoc-in
                 contract
                 [:profiles "two-runtime" :destination :configuration
                  :package :authors]
                 "Different Publisher")
                contract)))]
          (let [wrong-publisher
                (caught
                 #(authored-spdx/verify-targets!
                   root [:one :two] policy))]
            (is (= :invalid-authored-spdx-gate
                   (:kind (ex-data wrong-publisher))))
            (is (= "Fixture Publisher"
                   (:expected-publisher (ex-data wrong-publisher))))
            (is (= [{:target :two
                     :profile "two-runtime"
                     :package-id "Fixture.two.runtime"
                     :publisher "Different Publisher"}]
                   (:mismatches (ex-data wrong-publisher))))))
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

(deftest repository-authored-spdx-gate-rejects-source-classification-conflicts
  (let [path "runtime/Shared.cs"
        target-contracts
        [{:target :one
          :authorship
          {:compatibility {:sources {}}
           :destination
           {:sources
            {:one/runtime
             {:id :one/runtime
              :class :authored-destination-runtime
              :paths [path]}}}
           :third-party {:sources {}}}}
         {:target :two
          :authorship
          {:compatibility {:sources {}}
           :destination {:sources {}}
           :third-party
           {:sources
            {:two/vendor
             {:id :two/vendor
              :class :vendored-third-party
              :paths [path]}}}}}]
        conflict
        (caught
         #(#'authored-spdx/consolidated-source-groups! target-contracts))]
    (is (= :invalid-authored-spdx-gate
           (:kind (ex-data conflict))))
    (is (= [{:path path
             :usages
             [{:group :one/runtime
               :class :authored-destination-runtime}
              {:group :two/vendor
               :class :vendored-third-party}]}]
           (:conflicts (ex-data conflict))))))

(deftest repository-authored-spdx-gate-rejects-missing-policy
  (let [root (Files/createTempDirectory
              "dripsharp-authored-spdx-missing-policy-"
              (make-array FileAttribute 0))]
    (try
      (let [missing-policy
            (caught
             #(authored-spdx/verify-policy-file!
               root "config/missing-authored-spdx.edn" [:one]))]
        (is (= :invalid-authored-spdx-gate
               (:kind (ex-data missing-policy))))
        (is (= :missing-policy
               (:reason (ex-data missing-policy))))
        (is (= "config/missing-authored-spdx.edn"
               (:path (ex-data missing-policy)))))
      (write-text! root authored-spdx/policy-path "")
      (let [empty-policy
            (caught
             #(authored-spdx/verify-policy-file!
               root authored-spdx/policy-path [:one]))]
        (is (= :invalid-authored-spdx-gate
               (:kind (ex-data empty-policy))))
        (is (= :empty-policy
               (:reason (ex-data empty-policy)))))
      (write-text! root authored-spdx/policy-path "{:first true}\n{:second true}\n")
      (let [trailing-policy
            (caught
             #(authored-spdx/verify-policy-file!
               root authored-spdx/policy-path [:one]))]
        (is (= :invalid-authored-spdx-gate
               (:kind (ex-data trailing-policy))))
        (is (= :trailing-data
               (:reason (ex-data trailing-policy))))
        (is (= authored-spdx/policy-path
               (:path (ex-data trailing-policy)))))
      (finally
        (delete-tree! root)))))

(deftest repository-authored-spdx-gate-rejects-target-discovery-symlink-escapes
  (let [root (Files/createTempDirectory
              "dripsharp-authored-spdx-target-root-"
              (make-array FileAttribute 0))
        outside (Files/createTempDirectory
                 "dripsharp-authored-spdx-target-outside-"
                 (make-array FileAttribute 0))
        outside-target
        (write-text! outside "escaped/target.edn" "{}")]
    (try
      (let [internal-target
            (write-text! root "internal-targets/one/target.edn" "{}")
            targets-link (.resolve root "targets")]
        (Files/createSymbolicLink
         targets-link (.getParent (.getParent internal-target))
         (make-array FileAttribute 0))
        (let [linked-root (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data linked-root))))
          (is (= :symbolic-link-target-root
                 (:reason (ex-data linked-root))))
          (is (= "targets"
                 (:path (ex-data linked-root)))))
        (Files/delete targets-link)
        (delete-tree! (.resolve root "internal-targets")))
      (let [targets-link (.resolve root "targets")]
        (Files/createSymbolicLink
         targets-link outside (make-array FileAttribute 0))
        (let [escaped-root (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data escaped-root))))
          (is (= :outside-workspace
                 (:reason (ex-data escaped-root))))
          (is (= "targets"
                 (:path (ex-data escaped-root)))))
        (Files/delete targets-link))
      (let [target-source
            (write-text! root "internal-target/linked/target.edn" "{}")
            targets-root (.resolve root "targets")
            target-link (.resolve targets-root "linked")]
        (Files/createDirectories targets-root (make-array FileAttribute 0))
        (Files/createSymbolicLink
         target-link (.getParent target-source)
         (make-array FileAttribute 0))
        (let [linked-target (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data linked-target))))
          (is (= :symbolic-link-target
                 (:reason (ex-data linked-target))))
          (is (= ["linked"]
                 (:targets (ex-data linked-target)))))
        (Files/delete target-link)
        (delete-tree! (.resolve root "internal-target")))
      (let [manifest-source
            (write-text! root "internal-manifest/target.edn" "{}")
            target-root (.resolve root "targets/linked")
            manifest-link (.resolve target-root "target.edn")]
        (Files/createDirectories target-root (make-array FileAttribute 0))
        (Files/createSymbolicLink
         manifest-link manifest-source (make-array FileAttribute 0))
        (let [linked-manifest
              (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data linked-manifest))))
          (is (= :symbolic-link-target-manifest
                 (:reason (ex-data linked-manifest))))
          (is (= ["linked"]
                 (:targets (ex-data linked-manifest)))))
        (Files/delete manifest-link)
        (Files/delete target-root)
        (delete-tree! (.resolve root "internal-manifest")))
      (let [targets-root (.resolve root "targets")
            target-link (.resolve targets-root "escaped")]
        (Files/createDirectories targets-root (make-array FileAttribute 0))
        (Files/createSymbolicLink
         target-link (.getParent outside-target)
         (make-array FileAttribute 0))
        (let [escaped-target (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data escaped-target))))
          (is (= :outside-workspace
                 (:reason (ex-data escaped-target))))
          (is (= ["escaped"]
                 (:targets (ex-data escaped-target)))))
        (Files/delete target-link))
      (let [target-root (.resolve root "targets/escaped")
            manifest-link (.resolve target-root "target.edn")]
        (Files/createDirectories target-root (make-array FileAttribute 0))
        (Files/createSymbolicLink
         manifest-link outside-target (make-array FileAttribute 0))
        (let [escaped-manifest
              (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data escaped-manifest))))
          (is (= :outside-workspace
                 (:reason (ex-data escaped-manifest))))
          (is (= ["escaped"]
                 (:targets (ex-data escaped-manifest))))))
      (Files/delete (.resolve root "targets/escaped/target.edn"))
      (Files/delete (.resolve root "targets/escaped"))
      (let [dangling-target (.resolve root "targets/dangling")]
        (Files/createSymbolicLink
         dangling-target (.resolve outside "missing-target")
         (make-array FileAttribute 0))
        (let [invalid-target (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data invalid-target))))
          (is (= :unresolved-symbolic-link
                 (:reason (ex-data invalid-target))))
          (is (= ["dangling"]
                 (:targets (ex-data invalid-target)))))
        (Files/delete dangling-target))
      (let [plain-file (write-text! outside "not-a-target.edn" "{}")
            non-directory-target (.resolve root "targets/not-a-target")]
        (Files/createSymbolicLink
         non-directory-target plain-file (make-array FileAttribute 0))
        (let [invalid-target
              (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data invalid-target))))
          (is (= :non-directory-symbolic-link
                 (:reason (ex-data invalid-target))))
          (is (= ["not-a-target"]
                 (:targets (ex-data invalid-target)))))
        (Files/delete non-directory-target))
      (let [non-directory-target
            (write-text! root "targets/not-a-directory" "{}")
            invalid-target
            (caught #(authored-spdx/active-targets root))]
        (is (= :invalid-authored-spdx-gate
               (:kind (ex-data invalid-target))))
        (is (= :non-directory-target-entry
               (:reason (ex-data invalid-target))))
        (is (= ["not-a-directory"]
               (:targets (ex-data invalid-target))))
        (Files/delete non-directory-target))
      (let [target-root (.resolve root "targets/dangling")
            manifest-link (.resolve target-root "target.edn")]
        (Files/createDirectories target-root (make-array FileAttribute 0))
        (Files/createSymbolicLink
         manifest-link (.resolve outside "missing-target.edn")
         (make-array FileAttribute 0))
        (let [invalid-manifest
              (caught #(authored-spdx/active-targets root))]
          (is (= :invalid-authored-spdx-gate
                 (:kind (ex-data invalid-manifest))))
          (is (= :invalid-target-manifest
                 (:reason (ex-data invalid-manifest))))
          (is (= ["dangling"]
                 (:targets (ex-data invalid-manifest))))))
      (finally
        (delete-tree! root)
        (delete-tree! outside)))))

(deftest authored-spdx-gate-rejects-symlink-escapes
  (let [root (Files/createTempDirectory
              "dripsharp-authored-spdx-symlink-root-"
              (make-array FileAttribute 0))
        outside (Files/createTempDirectory
                 "dripsharp-authored-spdx-symlink-outside-"
                 (make-array FileAttribute 0))]
    (try
      (let [policy
            {:schema-version authorship/spdx-policy-schema-version
             :decision authored-spdx/required-decision
             :license-identifier "LicenseRef-Fixture"
             :file-copyright-text "2026 Fixture Owner"
             :package-publisher "Fixture Publisher"
             :repository-notice
             {:path "LICENSE"
              :sha256
              "ec71479127126ba0b470229578465117e755e36c287e83c887bd0172aa04f0ce"}}
            header
            (str "// SPDX-FileCopyrightText: 2026 Fixture Owner\n"
                 "// SPDX-License-Identifier: LicenseRef-Fixture\n\n")
            outside-policy
            (write-text! outside "authored-spdx.edn" (pr-str policy))
            policy-link (.resolve root authored-spdx/policy-path)
            _ (Files/createDirectories (.getParent policy-link)
                                       (make-array FileAttribute 0))
            _ (Files/createSymbolicLink
               policy-link outside-policy (make-array FileAttribute 0))
            escaped-policy
            (caught
             #(authored-spdx/verify-policy-file!
               root authored-spdx/policy-path [:fixture]))]
        (is (= :invalid-authored-spdx-gate
               (:kind (ex-data escaped-policy))))
        (is (= :outside-workspace
               (:reason (ex-data escaped-policy))))
        (Files/delete policy-link)
        (let [internal-policy
              (write-text! root "internal-authored-spdx.edn" (pr-str policy))]
          (Files/createSymbolicLink
           policy-link internal-policy (make-array FileAttribute 0))
          (let [linked-policy
                (caught
                 #(authored-spdx/verify-policy-file!
                   root authored-spdx/policy-path [:fixture]))]
            (is (= :invalid-authored-spdx-gate
                   (:kind (ex-data linked-policy))))
            (is (= :symbolic-link-policy
                   (:reason (ex-data linked-policy))))
            (is (= authored-spdx/policy-path
                   (:path (ex-data linked-policy)))))
          (Files/delete policy-link))
        (Files/delete (.getParent policy-link))
        (let [internal-policy
              (write-text!
               root "internal-config/authored-spdx.edn" (pr-str policy))
              config-link (.getParent policy-link)]
          (Files/createSymbolicLink
           config-link (.getParent internal-policy)
           (make-array FileAttribute 0))
          (let [linked-policy-directory
                (caught
                 #(authored-spdx/verify-policy-file!
                   root authored-spdx/policy-path [:fixture]))]
            (is (= :invalid-authored-spdx-gate
                   (:kind (ex-data linked-policy-directory))))
            (is (= :symbolic-link-policy-directory
                   (:reason (ex-data linked-policy-directory))))
            (is (= ["config"]
                   (:paths (ex-data linked-policy-directory)))))
          (Files/delete config-link)
          (delete-tree! (.getParent internal-policy)))
        (write-text! root authored-spdx/policy-path (pr-str policy))
        (let [outside-notice
              (write-text! outside "LICENSE" "Fixture repository license.\n")
              notice-link (.resolve root "LICENSE")
              _ (Files/createSymbolicLink
                 notice-link outside-notice (make-array FileAttribute 0))
              escaped-notice
              (caught
               #(authorship/verify-repository-notice!
                 root (dissoc policy :package-publisher)))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data escaped-notice))))
          (is (= "LICENSE" (:path (ex-data escaped-notice))))
          (is (nil? (:actual (ex-data escaped-notice))))
          (Files/delete notice-link)
          (let [internal-notice
                (write-text!
                 root "INTERNAL-LICENSE" "Fixture repository license.\n")]
            (Files/createSymbolicLink
             notice-link internal-notice (make-array FileAttribute 0))
            (let [linked-notice
                  (caught
                   #(authorship/verify-repository-notice!
                     root (dissoc policy :package-publisher)))]
              (is (= :invalid-authorship-ledger
                     (:kind (ex-data linked-notice))))
              (is (= :symbolic-link-repository-notice
                     (:reason (ex-data linked-notice))))
              (is (= "LICENSE" (:path (ex-data linked-notice)))))
            (Files/delete notice-link))
          (write-text! root "LICENSE" "Fixture repository license.\n"))
        (let [outside-source
              (write-text!
               outside "Runtime.cs"
               (str header "internal static class Runtime { }\n"))
              source-link (.resolve root "runtime/Runtime.cs")
              _ (Files/createDirectories (.getParent source-link)
                                         (make-array FileAttribute 0))
              _ (Files/createSymbolicLink
                 source-link outside-source (make-array FileAttribute 0))
              escaped-source
              (caught
               #(normalized-groups
                 root :authored-destination-runtime :fixture
                 [(source-group root :fixture/runtime
                                "runtime/Runtime.cs" nil 4)]))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data escaped-source))))
          (is (= :outside-workspace
                 (:reason (ex-data escaped-source))))
          (is (= :fixture/runtime
                 (:source (ex-data escaped-source))))
          (is (= "runtime/Runtime.cs"
                 (:provenance (ex-data escaped-source))))
          (Files/delete source-link))
        (let [internal-source
              (write-text!
               root "internal-source/Runtime.cs"
               (str header "internal static class Runtime { }\n"))
              source-directory-link (.resolve root "source-alias")
              _ (Files/createSymbolicLink
                 source-directory-link (.getParent internal-source)
                 (make-array FileAttribute 0))
              linked-ancestor
              (caught
               #(authorship/source-observation
                 root
                 {:id :fixture/linked-ancestor
                  :kind :file
                  :provenance "source-alias/Runtime.cs"
                  :include-pattern nil}))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data linked-ancestor))))
          (is (= :symbolic-link-source-directory
                 (:reason (ex-data linked-ancestor))))
          (is (= ["source-alias"]
                 (:paths (ex-data linked-ancestor)))))
        (let [outside-tree
              (write-text!
               outside "tree/Runtime.cs"
               (str header "internal static class Runtime { }\n"))
              tree-link (.resolve root "runtime/tree")
              _ (Files/createSymbolicLink
                 tree-link (.getParent outside-tree)
                 (make-array FileAttribute 0))
              escaped-tree
              (caught
               #(authorship/source-observation
                 root
                 {:id :fixture/tree
                  :kind :tree
                  :provenance "runtime/tree"
                  :include-pattern #".*\.cs"}))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data escaped-tree))))
          (is (= :outside-workspace
                 (:reason (ex-data escaped-tree))))
          (is (= :fixture/tree
                 (:source (ex-data escaped-tree))))
          (is (= "runtime/tree"
                 (:provenance (ex-data escaped-tree)))))
        (let [dangling-file (.resolve root "runtime/Dangling.cs")
              _ (Files/createSymbolicLink
                 dangling-file (.resolve outside "missing-file.cs")
                 (make-array FileAttribute 0))
              unresolved-file
              (caught
               #(authorship/source-observation
                 root
                 {:id :fixture/dangling-file
                  :kind :file
                  :provenance "runtime/Dangling.cs"
                  :include-pattern nil}))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data unresolved-file))))
          (is (= :unresolved-symbolic-link
                 (:reason (ex-data unresolved-file))))
          (is (= ["runtime/Dangling.cs"]
                 (:paths (ex-data unresolved-file))))
          (Files/delete dangling-file))
        (let [linked-file-target
              (write-text!
               root "runtime/linked-file-target/Runtime.cs"
               (str header "internal static class Runtime { }\n"))
              linked-file (.resolve root "runtime/Linked.cs")
              _ (Files/createSymbolicLink
                 linked-file linked-file-target
                 (make-array FileAttribute 0))
              resolved-file
              (caught
               #(authorship/source-observation
                 root
                 {:id :fixture/linked-file
                  :kind :file
                  :provenance "runtime/Linked.cs"
                  :include-pattern nil}))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data resolved-file))))
          (is (= :resolved-symbolic-link
                 (:reason (ex-data resolved-file))))
          (is (= ["runtime/Linked.cs"]
                 (:paths (ex-data resolved-file))))
          (Files/delete linked-file))
        (let [tree-root (.resolve root "runtime/dangling-tree")
              dangling-source (.resolve tree-root "Dangling.cs")
              _ (Files/createDirectories tree-root
                                         (make-array FileAttribute 0))
              _ (Files/createSymbolicLink
                 dangling-source (.resolve outside "missing-tree.cs")
                 (make-array FileAttribute 0))
              unresolved-tree
              (caught
               #(authorship/source-observation
                 root
                 {:id :fixture/dangling-tree
                  :kind :tree
                  :provenance "runtime/dangling-tree"
                  :include-pattern #".*\.cs"}))]
          (is (= :invalid-authorship-ledger
                 (:kind (ex-data unresolved-tree))))
          (is (= :unresolved-symbolic-link
                 (:reason (ex-data unresolved-tree))))
          (is (= ["runtime/dangling-tree/Dangling.cs"]
                 (:paths (ex-data unresolved-tree))))
          (Files/delete dangling-source)
          (let [dangling-directory (.resolve tree-root "Pending")
                _ (Files/createSymbolicLink
                   dangling-directory (.resolve outside "missing-directory")
                   (make-array FileAttribute 0))
                unresolved-directory
                (caught
                 #(authorship/source-observation
                   root
                   {:id :fixture/dangling-directory
                    :kind :tree
                    :provenance "runtime/dangling-tree"
                    :include-pattern #".*\.cs"}))]
            (is (= :invalid-authorship-ledger
                   (:kind (ex-data unresolved-directory))))
            (is (= :unresolved-symbolic-link
                   (:reason (ex-data unresolved-directory))))
            (is (= ["runtime/dangling-tree/Pending"]
                   (:paths (ex-data unresolved-directory))))
            (Files/delete dangling-directory))
          (let [linked-file-target
                (write-text!
                 root "runtime/linked-tree-target/Runtime.cs"
                 (str header "internal static class Runtime { }\n"))
                linked-file (.resolve tree-root "Linked.cs")
                _ (Files/createSymbolicLink
                   linked-file linked-file-target
                   (make-array FileAttribute 0))
                resolved-file
                (caught
                 #(authorship/source-observation
                   root
                   {:id :fixture/linked-tree-file
                    :kind :tree
                    :provenance "runtime/dangling-tree"
                    :include-pattern #".*\.cs"}))]
            (is (= :invalid-authorship-ledger
                   (:kind (ex-data resolved-file))))
            (is (= :resolved-symbolic-link
                   (:reason (ex-data resolved-file))))
            (is (= ["runtime/dangling-tree/Linked.cs"]
                   (:paths (ex-data resolved-file))))
            (Files/delete linked-file))
          (let [linked-source-root (.resolve root "runtime/linked-source")
                _ (write-text!
                   root "runtime/linked-source/Runtime.cs"
                   (str header "internal static class Runtime { }\n"))
                linked-directory (.resolve tree-root "Linked")
                _ (Files/createSymbolicLink
                   linked-directory linked-source-root
                   (make-array FileAttribute 0))
                untraversed-directory
                (caught
                 #(authorship/source-observation
                   root
                   {:id :fixture/linked-directory
                    :kind :tree
                    :provenance "runtime/dangling-tree"
                    :include-pattern #".*\.cs"}))]
            (is (= :invalid-authorship-ledger
                   (:kind (ex-data untraversed-directory))))
            (is (= :untraversed-symbolic-link-directory
                   (:reason (ex-data untraversed-directory))))
            (is (= ["runtime/dangling-tree/Linked"]
                   (:paths (ex-data untraversed-directory)))))))
      (finally
        (delete-tree! root)
        (delete-tree! outside)))))

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
