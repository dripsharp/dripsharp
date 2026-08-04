(ns dripsharp.consumer-tests-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.consumer-tests :as consumer-tests]
            [dripsharp.pkl.brine-xunit :as brine-xunit]
            [dripsharp.target-directory :as target-directory])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory "dripsharp-consumer-tests-"
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

(defn- emission
  [workspace target]
  (let [contract (target-directory/read-target target)]
    {:contract contract
     :result
     (consumer-tests/emit!
      {:workspace-root workspace
       :target-contract contract})}))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(def probe-calls (atom []))

(defn probe-strategy!
  [{:keys [phase project-root strategy]}]
  (swap! probe-calls conj [phase (:id strategy)])
  (when (= :emit phase)
    (Files/writeString (.resolve ^Path project-root "Dispatched.cs")
                       "namespace Dispatch.Probe;\n"
                       (make-array OpenOption 0)))
  {:phase phase})

(deftest real-product-contracts-cover-every-published-assembly
  (doseq [[target expected-assembly expected-profiles]
          [[:pkl "DripSharp.Brine.Tests"
            #{"pkl-parser" "pkl-core-value-model"}]
           [:pdfcube "DripSharp.PdfCarton.Tests"
            #{"pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
              "pdfcube-pdfbox" "pdfcube-preflight"}]]]
    (let [contract (target-directory/read-target target)
          tests (get-in contract [:publication :test-suites])
          project (first (:projects tests))
          focused (first (filter #(= :focused-consumer (:kind %))
                                 (:strategies tests)))]
      (is (= ["src" "tests" "LICENSE" "NOTICE" "README.md"]
             (get-in contract [:publication :managed-paths])))
      (is (= expected-assembly
             (:assembly-name project)))
      (is (= expected-profiles
             (set (keys (:profile-tests focused)))))
      (is (= :shipped (:policy focused)))
      (is (every? #(= 64 (count (:sha256 %)))
                  (concat (vals (:profile-tests focused))
                          (:fixtures focused)))))))

(deftest emission-is-repository-local-inventoried-and-deterministic
  (doseq [target [:pkl :pdfcube]]
    (let [workspace (temp-directory)]
      (try
        (let [{:keys [contract result]} (emission workspace target)
              project-file (:project-file result)
              project-first (Files/readString project-file)
              inventory-first
              (Files/readString (:inventory-file result))
              second (:result (emission workspace target))
              project-second (Files/readString (:project-file second))
              inventory-second
              (Files/readString (:inventory-file second))
              project-reference-count
              (count (re-seq #"<ProjectReference " project-first))]
          (is (= (+ (count (get-in contract
                                   [:publication :profile-projects]))
                    (count (get-in contract
                                   [:publication :test-suites :projects 0
                                    :project-references])))
                 project-reference-count))
          (is (str/includes? project-first "../../src/"))
          (is (str/includes? project-first
                             "TargetPath=\"Fixtures/"))
          (is (not (str/includes? project-first
                                  (str (:target-directory contract)))))
          (is (= project-first project-second))
          (is (= inventory-first inventory-second))
          (is (str/includes? inventory-first "README.md"))
          (is (str/includes? inventory-first "NOTICE.md"))
          (is (str/includes? inventory-first "Fixtures/"))
          (when (= :pkl target)
            (is (str/includes? inventory-first
                               "Contracts/LanguageSnippetContract.tsv"))
            (is (str/includes? inventory-first
                               "Contracts/PklCoreTestContract.tsv"))
            (is (str/includes? inventory-first
                               "Contracts/PklParserTestContract.tsv"))
            (is (str/includes? inventory-first
                               "DripSharp.Brine.ParserTestRunner"))
            (is (str/includes? inventory-first "TEST-PROVENANCE.tsv"))
            (is (str/includes?
                 (Files/readString
                  (.resolve ^Path (:tests-root second) "README.md"))
                 "TEST-BOUNDARY.md")))
          (is (not (str/includes? inventory-first "SHA256SUMS")))
          (is (str/includes?
               (Files/readString
                (.resolve ^Path (:tests-root second) "README.md"))
               "dotnet test tests/")))
        (finally
          (delete-tree! workspace))))))

(deftest staged-verification-runs-documented-command-sequence
  (let [workspace (temp-directory)
        {:keys [contract]} (emission workspace :pkl)
        calls (atom [])
        result
        (consumer-tests/verify!
         {:workspace-root workspace
          :target-contract contract
          :run-command!
          (fn [request]
            (swap! calls conj request)
            {:exit 0 :output ""})})]
    (try
      (is (= ["restore" "build" "test"]
             (mapv #(second (:command %)) @calls)))
      (is (every? #(= 300000 (:timeout-ms %)) @calls))
      (is (every? #(= (.resolve workspace
                                "target/generated/brine")
                      (:directory %))
                  @calls))
      (is (str/ends-with?
           (str (:project-file result))
           "tests/DripSharp.Brine.Tests/DripSharp.Brine.Tests.csproj"))
      (is (= #{"DripSharp.Brine.Tests"}
             (set (keys (:project-files result)))))
      (finally
        (delete-tree! workspace)))))

(deftest staged-verification-honors-the-suite-timeout
  (let [workspace (temp-directory)
        staging (.resolve workspace "target/generated/timeout-probe")
        tests-root (.resolve staging "tests")
        project-root (.resolve staging "timeout-probe")
        project-file (.resolve project-root "Timeout.Probe.Tests.csproj")
        contract
        {:target :timeout-probe
         :publication
         {:staging-path "target/generated/timeout-probe"
          :test-suites
          {:timeout-ms 900000
           :projects
           [{:id "Timeout.Probe.Tests"
             :directory "timeout-probe"
             :assembly-name "Timeout.Probe.Tests"}]
           :strategies []}}}
        calls (atom [])]
    (try
      (Files/createDirectories tests-root (make-array FileAttribute 0))
      (Files/createDirectories project-root (make-array FileAttribute 0))
      (Files/writeString project-file "<Project />\n"
                         (make-array OpenOption 0))
      (Files/writeString (.resolve tests-root "Sentinel.txt") "probe\n"
                         (make-array OpenOption 0))
      (Files/writeString (.resolve tests-root "SHA256SUMS")
                         (str "25be323556dad377abb57fe7ec8c4b99a"
                              "6527f488dda28d0c9b686528659c909"
                              "  Sentinel.txt\n")
                         (make-array OpenOption 0))
      (consumer-tests/verify!
       {:workspace-root workspace
        :target-contract contract
        :run-command! (fn [request]
                        (swap! calls conj request)
                        {:exit 0 :output ""})})
      (is (= 3 (count @calls)))
      (is (every? #(= 900000 (:timeout-ms %)) @calls))
      (finally
        (delete-tree! workspace)))))

(deftest multiple-projects-dispatch-by-qualified-strategy-without-target-branches
  (let [workspace (temp-directory)
        contract (target-directory/read-target :pdfcube)
        base-project (get-in contract [:publication :test-suites :projects 0])
        probe-project
        (assoc base-project
               :id "DripSharp.PdfCarton.Validation.Tests"
               :assembly-name "DripSharp.PdfCarton.Validation.Tests"
               :directory "tests/validation/DripSharp.PdfCarton.Validation.Tests"
               :profile-references ["pdfcube-io"])
        probe-strategy
        {:id :dispatch-probe
         :kind :adapted-upstream
         :policy :validation-only
         :project "DripSharp.PdfCarton.Validation.Tests"
         :handler 'dripsharp.consumer-tests-test/probe-strategy!}
        contract
        (-> contract
            (update-in [:publication :test-suites :projects]
                       conj probe-project)
            (update-in [:publication :test-suites :strategies]
                       conj probe-strategy))]
    (try
      (reset! probe-calls [])
      (let [emitted (consumer-tests/emit!
                     {:workspace-root workspace
                      :target-contract contract})
            calls (atom [])
            verified
            (consumer-tests/verify!
             {:workspace-root workspace
              :target-contract contract
              :run-command! (fn [request]
                              (swap! calls conj request)
                              {:exit 0 :output ""})})]
        (is (= [[:emit :dispatch-probe] [:verify :dispatch-probe]]
               @probe-calls))
        (is (= #{"DripSharp.PdfCarton.Tests"
                 "DripSharp.PdfCarton.Validation.Tests"}
               (set (keys (:project-files emitted)))
               (set (keys (:project-files verified)))))
        (is (= 6 (count @calls)))
        (is (every? #(= (.resolve workspace "target/generated/pdfcarton")
                        (:directory %))
                    @calls)))
      (finally
        (delete-tree! workspace)))))

(deftest staged-verification-rejects-manual-test-changes
  (let [workspace (temp-directory)
        {:keys [contract result]} (emission workspace :pkl)
        readme (.resolve ^Path (:tests-root result) "README.md")]
    (try
      (Files/writeString
       readme
       (str (Files/readString readme) "\nmanual product fix\n")
       (make-array OpenOption 0))
      (let [called? (atom false)
            result
            (failure
             #(consumer-tests/verify!
               {:workspace-root workspace
                :target-contract contract
                :run-command! (fn [_] (reset! called? true))}))]
        (is (= :test-inventory-mismatch (:reason result)))
        (is (false? @called?)))
      (finally
        (delete-tree! workspace)))))

(deftest brine-upstream-suite-is-complete-and-false-mechanical-fails-closed
  (let [workspace (temp-directory)]
    (try
      (let [{:keys [result]} (emission workspace :pkl)
            tests-root (:tests-root result)
            ledger (.resolve ^Path tests-root "TEST-PROVENANCE.tsv")
            original (Files/readString ledger)
            proof (brine-xunit/verify-provenance! tests-root)]
        (is (= 1132 (count (:rows proof))))
        (is (= 4 (count (:counts proof))))
        (Files/writeString
         ledger
         (str/replace-first
          original
          "\tdripsharp-authored-test-infrastructure\t-\t"
          (str "\tmechanically-upstream-derived\t"
               "f7cac257ade5775c1dfc255f4fda2eacc296e9d0\t"))
         (make-array OpenOption 0))
        (is (= :invalid-mechanical-test-provenance
               (:reason (failure
                         #(brine-xunit/verify-provenance! tests-root))))))
      (finally
        (delete-tree! workspace)))))
