(ns dripsharp.consumer-tests-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.consumer-tests :as consumer-tests]
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

(deftest real-product-contracts-cover-every-published-assembly
  (doseq [[target expected-assembly expected-profiles]
          [[:pkl "DripSharp.Brine.Tests"
            #{"pkl-parser" "pkl-core-value-model"}]
           [:pdfcube "DripSharp.PdfCarton.Tests"
            #{"pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
              "pdfcube-pdfbox" "pdfcube-preflight"}]]]
    (let [contract (target-directory/read-target target)
          tests (get-in contract [:publication :consumer-tests])]
      (is (= ["src" "tests" "LICENSE" "NOTICE" "README.md"]
             (get-in contract [:publication :managed-paths])))
      (is (= expected-assembly
             (get-in tests [:project :assembly-name])))
      (is (= expected-profiles
             (set (keys (:assembly-tests tests)))))
      (is (every? #(= 64 (count (:sha256 %)))
                  (concat (vals (:assembly-tests tests))
                          (:fixtures tests)))))))

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
          (is (= (count (get-in contract
                                [:publication :profile-projects]))
                 project-reference-count))
          (is (str/includes? project-first "../../src/"))
          (is (not (str/includes? project-first
                                  (str (:target-directory contract)))))
          (is (= project-first project-second))
          (is (= inventory-first inventory-second))
          (is (str/includes? inventory-first "README.md"))
          (is (str/includes? inventory-first "NOTICE.md"))
          (is (str/includes? inventory-first "Fixtures/"))
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
      (is (every? #(= (.resolve workspace
                                "target/generated/brine")
                      (:directory %))
                  @calls))
      (is (str/ends-with?
           (str (:project-file result))
           "tests/DripSharp.Brine.Tests/DripSharp.Brine.Tests.csproj"))
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
