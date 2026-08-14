(ns dripsharp.product-repository-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.product-repository :as product-repository]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.target-execution :as target-execution])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory "dripsharp-product-repository-"
                             (make-array FileAttribute 0)))

(defn- delete-tree!
  [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry
              (->> (.toArray entries)
                   (map #(cast Path %))
                   (sort-by #(.getNameCount ^Path %) >))]
        (Files/delete entry)))))

(defn- write!
  [^Path root relative content]
  (let [file (.resolve root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- git!
  [directory & command]
  (process/run! {:command (into ["git"] command)
                 :directory directory}))

(defn- git-output
  [directory & command]
  (str/trim (:output (apply git! directory command))))

(defn- configure-git!
  [directory]
  (git! directory "config" "user.name" "DripSharp Test")
  (git! directory "config" "user.email" "dripsharp@example.invalid"))

(defn- init-repository!
  [directory remote initial-files]
  (Files/createDirectories directory (make-array FileAttribute 0))
  (git! directory "init" "-b" "master")
  (configure-git! directory)
  (git! directory "remote" "add" "origin" remote)
  (doseq [[path content] initial-files]
    (write! directory path content))
  (git! directory "add" "--all")
  (git! directory "commit" "-m" "Initial product state")
  (git-output directory "rev-parse" "HEAD"))

(defn- publication
  []
  {:kind :generated-repository
   :repository-slug "dripsharp/brine"
   :repository-url "https://github.com/dripsharp/brine.git"
   :default-branch "master"
   :submodule-path "products/brine"
   :staging-path "target/generated/brine"
   :profile-projects {"pkl-core" "src/DripSharp.Brine"}
   :managed-paths ["src" "tests" "LICENSE" "NOTICE" "README.md"]
   :excluded-paths ["src/DripSharp.Brine/source-map.edn"]
   :test-suites {:schema-version 2}
   :nuget {:fixture true}
   :publication-mode :pull-request})

(defn- target-contract
  []
  {:target :pkl
   :product-family :brine
   :publication (publication)})

(defn- add-gitlink!
  [workspace path remote commit]
  (spit (str (.resolve ^Path workspace ".gitmodules"))
        (str (when (Files/exists (.resolve ^Path workspace ".gitmodules")
                                 (make-array java.nio.file.LinkOption 0))
               (slurp (str (.resolve ^Path workspace ".gitmodules"))))
             "[submodule \"" path "\"]\n"
             "\tpath = " path "\n"
             "\turl = " remote "\n"))
  (git! workspace "add" ".gitmodules")
  (git! workspace "update-index" "--add" "--cacheinfo"
        (str "160000," commit "," path)))

(defn- fixture!
  []
  (let [workspace (temp-directory)
        brine (.resolve workspace "products/brine")
        pdfcarton (.resolve workspace "products/pdfcarton")
        _ (git! workspace "init" "-b" "master")
        _ (configure-git! workspace)
        _ (write! workspace ".gitignore" "target/\n")
        brine-commit
        (init-repository!
         brine "https://github.com/dripsharp/brine.git"
         [[".gitignore" "[Bb]in/\n[Oo]bj/\n"]
          ["README.md" "old readme\n"]
          ["src/DripSharp.Brine/source-map.edn" "old map\n"]
          ["CONTRIBUTING.md" "preserve me\n"]])
        pdfcarton-commit
        (init-repository!
         pdfcarton "https://github.com/dripsharp/pdfcarton.git"
         [["README.md" "other product\n"]])
        _ (add-gitlink! workspace "products/brine"
                        "https://github.com/dripsharp/brine.git"
                        brine-commit)
        _ (add-gitlink! workspace "products/pdfcarton"
                        "https://github.com/dripsharp/pdfcarton.git"
                        pdfcarton-commit)
        _ (git! workspace "add" ".gitignore")
        _ (git! workspace "commit" "-m" "Adopt product submodules")
        _ (write! workspace
                  "target/generated/brine/src/DripSharp.Brine/Generated.cs"
                  "namespace DripSharp.Brine;\n")
        _ (write! workspace
                  "target/generated/brine/src/DripSharp.Brine/source-map.edn"
                  "{:schema-version 1 :mappings []}\n")
        _ (write! workspace "target/generated/brine/LICENSE"
                  "Apache License\n")
        _ (write! workspace "target/generated/brine/NOTICE"
                  "Brine notice\n")
        _ (write! workspace "target/generated/brine/README.md"
                  "# Brine\n")
        _ (write! workspace
                  "target/generated/brine/tests/DripSharp.Brine.Tests/ConsumerTests.cs"
                  "namespace DripSharp.Brine.Tests;\n")
        _ (write! workspace "target/generated/brine/proof-only.txt"
                  "not published\n")]
    {:workspace workspace
     :brine brine
     :pdfcarton pdfcarton
     :contract (target-contract)}))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- commit-synchronization!
  [{:keys [workspace brine]}]
  (git! brine "add" "--all")
  (git! brine "commit" "-m" "Publish generated state")
  (git! workspace "add" "products/brine")
  (git! workspace "commit" "-m" "Advance Brine gitlink"))

(deftest target-publication-variants-map-products-exactly
  (let [brine (:publication (target-directory/read-target :pkl))
        pdfcarton (:publication (target-directory/read-target :pdfcube))
        rawhttp (:publication (target-directory/read-target :rawhttp))]
    (is (= ["src" "tests" "LICENSE" "NOTICE" "README.md"]
           (:managed-paths brine)))
    (is (= ["src/DripSharp.Brine.Parser/source-map.edn"
            "src/DripSharp.Brine/source-map.edn"]
           (:excluded-paths brine)))
    (is (= "DripSharp.Brine.Tests"
           (get-in brine [:test-suites :projects 0 :assembly-name])))
    (is (= #{"pkl-parser" "pkl-core-value-model"}
           (set (get-in brine
                        [:test-suites :projects 0 :profile-references]))))
    (is (= "target/generated/pdfcarton" (:staging-path pdfcarton)))
    (is (= "products/pdfcarton" (:submodule-path pdfcarton)))
    (is (= #{"src/DripSharp.PdfCarton.IO/source-map.edn"
             "src/DripSharp.PdfCarton.Fonts/source-map.edn"
             "src/DripSharp.PdfCarton.Xmp/source-map.edn"
             "src/DripSharp.PdfCarton/source-map.edn"
             "src/DripSharp.PdfCarton.Preflight/source-map.edn"}
           (set (:excluded-paths pdfcarton))))
    (is (= #{"src/DripSharp.PdfCarton"
             "src/DripSharp.PdfCarton.IO"
             "src/DripSharp.PdfCarton.Fonts"
             "src/DripSharp.PdfCarton.Xmp"
             "src/DripSharp.PdfCarton.Preflight"}
           (set (vals (:profile-projects pdfcarton)))))
    (is (= {:kind :conformance-only} rawhttp))))

(deftest synchronization-copies-only-managed-paths-and-repeats-deterministically
  (let [{:keys [workspace brine pdfcarton contract] :as fixture} (fixture!)]
    (try
      (let [other-head (git-output pdfcarton "rev-parse" "HEAD")
            first
            (product-repository/synchronize!
             {:workspace-root workspace :target-contract contract})]
        (is (= ["LICENSE" "NOTICE" "README.md"
                "src/DripSharp.Brine/Generated.cs"
                "src/DripSharp.Brine/source-map.edn"
                "tests/DripSharp.Brine.Tests/ConsumerTests.cs"]
               (:changes first)))
        (is (= "namespace DripSharp.Brine;\n"
               (slurp (str (.resolve brine
                                     "src/DripSharp.Brine/Generated.cs")))))
        (is (= "preserve me\n"
               (slurp (str (.resolve brine "CONTRIBUTING.md")))))
        (is (not (Files/exists (.resolve brine "proof-only.txt")
                               (make-array java.nio.file.LinkOption 0))))
        (is (not (Files/exists
                  (.resolve brine "src/DripSharp.Brine/source-map.edn")
                  (make-array java.nio.file.LinkOption 0))))
        (is (Files/isRegularFile
             (.resolve workspace
                       "target/generated/brine/src/DripSharp.Brine/source-map.edn")
             (make-array java.nio.file.LinkOption 0)))
        (is (= other-head (git-output pdfcarton "rev-parse" "HEAD")))
        (is (= [] (:external-actions first)))
        (commit-synchronization! fixture)
        (let [second
              (product-repository/synchronize!
               {:workspace-root workspace :target-contract contract})]
          (is (empty? (:changes second)))
          (is (= (:source-sha256 first) (:source-sha256 second)))
          (is (= (:inventory first) (:inventory second)))))
      (finally
        (delete-tree! workspace)))))

(deftest synchronized-product-head-is-the-only-accepted-package-commit
  (let [{:keys [workspace brine contract] :as fixture} (fixture!)]
    (try
      (product-repository/synchronize!
       {:workspace-root workspace :target-contract contract})
      (commit-synchronization! fixture)
      (write! workspace
              "target/generated/brine/src/DripSharp.Brine/bin/Release/netstandard2.0/DripSharp.Brine.dll"
              "verified build output\n")
      (let [proof
            (product-repository/verify-synchronized!
             {:workspace-root workspace :target-contract contract})]
        (is (= "https://github.com/dripsharp/brine.git"
               (:repository-url proof)))
        (is (= (git-output brine "rev-parse" "HEAD")
               (:repository-commit proof)))
        (is (not-any? #(= "src/DripSharp.Brine/source-map.edn" (second %))
                      (:inventory proof))))
      (write! workspace "target/generated/brine/README.md"
              "# stale after committed product\n")
      (let [result
            (failure
             #(product-repository/verify-synchronized!
               {:workspace-root workspace :target-contract contract}))]
        (is (= :stale-generated-product-commit (:reason result)))
        (is (= "https://github.com/dripsharp/brine.git"
               (:repository result))))
      (finally
        (delete-tree! workspace)))))

(deftest synchronized-proof-allows-only-declared-unstaged-profile-projects
  (let [{:keys [workspace brine contract] :as fixture} (fixture!)
        contract
        (assoc-in contract [:publication :profile-projects]
                  {"pkl-core" "src/DripSharp.Brine"
                   "pkl-parser" "src/DripSharp.Brine.Parser"})]
    (try
      (product-repository/synchronize!
       {:workspace-root workspace :target-contract contract})
      (commit-synchronization! fixture)
      (write! brine "src/DripSharp.Brine.Parser/Parser.cs"
              "namespace DripSharp.Brine.Parser;\n")
      (git! brine "add" "--all")
      (git! brine "commit" "-m" "Add declared sibling profile")
      (git! workspace "add" "products/brine")
      (git! workspace "commit" "-m" "Advance sibling profile")
      (let [proof
            (product-repository/verify-synchronized!
             {:workspace-root workspace :target-contract contract})]
        (is (= (git-output brine "rev-parse" "HEAD")
               (:repository-commit proof)))
        (is (not-any? #(str/starts-with?
                        (second %) "src/DripSharp.Brine.Parser")
                      (:inventory proof))))
      (write! workspace
              "target/generated/brine/src/DripSharp.Brine/Generated.cs"
              "namespace Stale.Core;\n")
      (let [result
            (failure
             #(product-repository/verify-synchronized!
               {:workspace-root workspace :target-contract contract}))]
        (is (= :stale-generated-product-commit (:reason result)))
        (is (= ["src/DripSharp.Brine.Parser"]
               (:unstaged-profile-projects result))))
      (finally
        (delete-tree! workspace)))))

(deftest tracked-build-artifacts-are-rejected-even-when-ignored
  (let [{:keys [workspace brine contract] :as fixture} (fixture!)]
    (try
      (product-repository/synchronize!
       {:workspace-root workspace :target-contract contract})
      (commit-synchronization! fixture)
      (let [artifact
            "src/DripSharp.Brine/bin/Release/netstandard2.0/DripSharp.Brine.dll"]
        (write! brine artifact "compiled output\n")
        (git! brine "add" "--force" "--" artifact)
        (let [result
              (failure
               #(product-repository/synchronize!
                 {:workspace-root workspace :target-contract contract}))]
          (is (= :tracked-build-artifacts (:reason result)))
          (is (= [artifact] (:paths result))))
        (git! brine "commit" "-m" "Accidentally track build output")
        (git! workspace "add" "products/brine")
        (git! workspace "commit" "-m" "Advance to contaminated product")
        (doseq [operation [product-repository/synchronize!
                           product-repository/verify-synchronized!]]
          (let [result
                (failure
                 #(operation {:workspace-root workspace
                              :target-contract contract}))]
            (is (= :tracked-build-artifacts (:reason result)))
            (is (= [artifact] (:paths result))))))
      (finally
        (delete-tree! workspace)))))

(deftest synchronization-rejects-dirty-and-cross-product-checkouts-before-copy
  (testing "unrelated changes in the intended product are rejected"
    (let [{:keys [workspace brine contract]} (fixture!)]
      (try
        (write! brine "manual.txt" "manual\n")
        (let [result
              (failure
               #(product-repository/synchronize!
                 {:workspace-root workspace :target-contract contract}))]
          (is (= :dirty-checkout (:reason result)))
          (is (= "old readme\n"
                 (slurp (str (.resolve brine "README.md"))))))
        (finally
          (delete-tree! workspace)))))
  (testing "manual changes under managed tests are rejected"
    (let [{:keys [workspace brine contract] :as fixture} (fixture!)]
      (try
        (product-repository/synchronize!
         {:workspace-root workspace :target-contract contract})
        (commit-synchronization! fixture)
        (write! brine
                "tests/DripSharp.Brine.Tests/ConsumerTests.cs"
                "namespace Manual.Product.Fix;\n")
        (let [result
              (failure
               #(product-repository/synchronize!
                 {:workspace-root workspace :target-contract contract}))]
          (is (= :dirty-checkout (:reason result)))
          (is (str/includes?
               (:status result)
               "tests/DripSharp.Brine.Tests/ConsumerTests.cs")))
        (finally
          (delete-tree! workspace)))))
  (testing "changes in another generated product are rejected"
    (let [{:keys [workspace brine pdfcarton contract]} (fixture!)]
      (try
        (write! pdfcarton "manual.txt" "cross-product\n")
        (let [result
              (failure
               #(product-repository/synchronize!
                 {:workspace-root workspace :target-contract contract}))]
          (is (= :cross-product-changes (:reason result)))
          (is (= "old readme\n"
                 (slurp (str (.resolve brine "README.md"))))))
        (finally
          (delete-tree! workspace)))))
  (testing "transient build outputs in managed staging are rejected"
    (let [{:keys [workspace contract]} (fixture!)]
      (try
        (write! workspace
                "target/generated/brine/src/DripSharp.Brine/obj/cache.bin"
                "derived\n")
        (let [result
              (failure
               #(product-repository/synchronize!
                 {:workspace-root workspace
                  :target-contract contract}))]
          (is (= :transient-build-artifacts (:reason result)))
          (is (= ["src/DripSharp.Brine/obj"] (:paths result))))
        (finally
          (delete-tree! workspace))))))

(deftest synchronization-rejects-path-escapes-and-conformance-only-targets
  (let [{:keys [workspace contract]} (fixture!)]
    (try
      (let [escaped
            (assoc-in contract [:publication :managed-paths]
                      ["../outside"])
            result
            (failure
             #(product-repository/synchronize!
               {:workspace-root workspace :target-contract escaped}))]
        (is (= :invalid-relative-path (:reason result))))
      (let [result
            (failure
             #(product-repository/synchronize!
               {:workspace-root workspace
                :target-contract
                {:target :rawhttp
                 :product-family :java-library
                 :publication {:kind :conformance-only}}}))]
        (is (= :conformance-only-target (:reason result))))
      (finally
        (delete-tree! workspace)))))

(deftest staging-cleanup-never-removes-product-content
  (let [{:keys [workspace brine contract]} (fixture!)]
    (try
      (product-repository/clean-staging!
       {:workspace-root workspace :target-contract contract})
      (is (Files/isDirectory
           (.resolve workspace "target/generated/brine")
           (make-array java.nio.file.LinkOption 0)))
      (is (empty?
           (with-open [entries
                       (Files/list
                        (.resolve workspace "target/generated/brine"))]
             (vec (.toArray entries)))))
      (is (= "preserve me\n"
             (slurp (str (.resolve brine "CONTRIBUTING.md")))))
      (is (= "old readme\n"
             (slurp (str (.resolve brine "README.md")))))
      (finally
        (delete-tree! workspace)))))

(deftest preparation-is-local-and-leaves-external-actions-explicit
  (let [{:keys [workspace brine contract]} (fixture!)
        commands (atom [])
        run-command!
        (fn [request]
          (swap! commands conj (:command request))
          (process/run! request))]
    (try
      (let [prepared
            (product-repository/prepare!
             {:workspace-root workspace
              :target-contract contract
              :branch "generated/brine-test"
              :commit-message "Publish generated Brine"
              :run-command! run-command!})]
        (is (= "generated/brine-test"
               (git-output brine "branch" "--show-current")))
        (is (= (:product-commit prepared)
               (git-output brine "rev-parse" "HEAD")))
        (is (= {:repository "dripsharp/brine"
                :base "master"
                :head "generated/brine-test"
                :title "Publish generated Brine"
                :body
                (str "Prepared from proved DripSharp staging "
                     (:source-sha256 prepared) ".")
                :requires-push true
                :requires-creation true}
               (:pull-request prepared)))
        (is (= [:push-required :pull-request-creation-required]
               (:external-actions prepared)))
        (is (= ["products/brine"]
               (-> (git! workspace "diff" "--cached" "--name-only" "-z")
                   :output
                   (str/split #"\u0000")
                   vec)))
        (is (not-any? #(or (some #{"push"} %)
                           (= "gh" (first %))
                           (some #{"repo" "create" "pr"} %))
                      @commands)))
      (finally
        (delete-tree! workspace)))))

(deftest target-workflow-supports-the-two-pass-commit-boundary
  (testing "a stale product receives every pre-commit check before managed copy"
    (let [calls (atom [])
          result
          (target-execution/synchronize!
           {:target :pkl
            :verify-fn
            (fn [{:keys [profile]}]
              (swap! calls conj [:verify profile])
              {:profile profile})
            :proof-fn
            (fn [_]
              (swap! calls conj [:unexpected-full-proof])
              :unexpected)
            :test-suites-fn
            (fn [_]
              (swap! calls conj [:test-suites])
              :generated-tests-passed)
            :staging-cleanup-fn
            (fn [_]
              (swap! calls conj [:staging-cleanup])
              :staging-cleaned)
            :repository-proof-fn
            (fn [_]
              (swap! calls conj [:repository-proof])
              (throw
               (ex-info "stale product"
                        {:reason :stale-generated-product-commit})))
            :synchronize-fn
            (fn [_]
              (swap! calls conj [:sync])
              :synchronized)})]
      (is (= [[:verify "pkl-parser"]
              [:verify "pkl-core-value-model"]
              [:test-suites]
              [:staging-cleanup]
              [:repository-proof]
              [:sync]]
             @calls))
      (is (= :pre-commit-synchronization (:mode result)))
      (is (= ["pkl-parser" "pkl-core-value-model"]
             (mapv :profile (get-in result [:precommit-proof :profiles]))))
      (is (= {:production "netstandard2.0"
              :execution "net10.0"
              :net48-compatibility :inferred-from-netstandard2.0
              :net48-runtime-tested? false}
             (get-in result [:precommit-proof :frameworks])))
      (is (nil? (:repository-proof result)))
      (is (= :generated-tests-passed (:test-suites result)))
      (is (= :staging-cleaned (:staging-cleanup result)))
      (is (= :synchronized (:synchronization result)))))
  (testing "an exact product receives the complete proof and a no-op sync"
    (let [calls (atom [])
          exact-proof {:repository-commit (apply str (repeat 40 "a"))}
          result
          (target-execution/synchronize!
           {:target :pkl
            :verify-fn
            (fn [{:keys [profile]}]
              (swap! calls conj [:verify profile])
              {:profile profile})
            :proof-fn
            (fn [options]
              (swap! calls conj [:full-proof options])
              :proved)
            :test-suites-fn
            (fn [_]
              (swap! calls conj [:test-suites])
              :generated-tests-passed)
            :staging-cleanup-fn
            (fn [_]
              (swap! calls conj [:staging-cleanup])
              :staging-cleaned)
            :repository-proof-fn
            (fn [_]
              (swap! calls conj [:repository-proof])
              exact-proof)
            :synchronize-fn
            (fn [_]
              (swap! calls conj [:sync])
              {:changes []})})]
      (is (= [[:verify "pkl-parser"]
              [:verify "pkl-core-value-model"]
              [:test-suites]
              [:staging-cleanup]
              [:repository-proof]
              [:full-proof {:workspace-root (paths/workspace-root)
                            :target :pkl}]
              [:test-suites]
              [:staging-cleanup]
              [:repository-proof]
              [:sync]]
             @calls))
      (is (= :exact-commit-proof (:mode result)))
      (is (= :proved (:proof result)))
      (is (= exact-proof (:repository-proof result)))
      (is (empty? (get-in result [:synchronization :changes])))))
  (let [called? (atom false)
        result
        (failure
         #(target-execution/synchronize!
           {:target :rawhttp
            :proof-fn (fn [_] (reset! called? true))
            :synchronize-fn (fn [_] (reset! called? true))}))]
    (is (= :conformance-only-target (:reason result)))
    (is (false? @called?))))

(deftest pack-and-package-entry-points-still-reject-stale-product-commits
  (let [stale!
        (fn [_]
          (throw
           (ex-info "stale product"
                    {:reason :stale-generated-product-commit})))
        pack-fn
        (fn [{:keys [repository-proof-fn]}]
          (repository-proof-fn))]
    (with-redefs [product-repository/verify-synchronized! stale!]
      (is (= :stale-generated-product-commit
             (:reason
              (failure
               #(target-execution/pack!
                 {:target :pkl
                  :profile "pkl-parser"
                  :pack-fn pack-fn})))))
      (is (= :stale-generated-product-commit
             (:reason
              (failure
               #(target-execution/package!
                 {:target :pkl
                  :profile "pkl-parser"
                  :pack-fn pack-fn
                  :package-fn
                  (fn [{:keys [pack-fn profile]}]
                    (pack-fn {:profile profile}))}))))))))
