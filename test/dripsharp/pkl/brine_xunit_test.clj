(ns dripsharp.pkl.brine-xunit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.brine-xunit :as brine-xunit]
            [dripsharp.tree-cleanup :as tree-cleanup]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.time LocalDateTime]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:private revision "f7cac257ade5775c1dfc255f4fda2eacc296e9d0")

(def ^:private encoded-package-relative
  "pkl-commons-test/build/test-packages/encoded-assets@1.0.0")

(def ^:private encoded-sources
  {"hash#query?.pkl" "name = \"punctuation\"\n"
   "hello world.pkl" "name = \"space\"\n"
   "reserved/slash.pkl" "name = \"reserved\"\n"
   "雪.pkl" "name = \"unicode\"\n"})

(def ^:private provenance-columns
  ["path" "class" "upstream-revision" "source-path" "source-sha256"
   "transformation" "emitted-sha256" "license" "notice"
   "durable-source" "authored-lines" "review-evidence" "line-budget"])

(defn- temp-directory
  []
  (Files/createTempDirectory "dripsharp-brine-encoded-package-"
                             (make-array FileAttribute 0)))

(defn- write-text!
  [path text]
  (Files/createDirectories (.getParent (paths/path path))
                           (make-array FileAttribute 0))
  (Files/writeString (paths/path path) text StandardCharsets/UTF_8
                     (make-array OpenOption 0))
  path)

(defn- portable
  [value]
  (str/replace (str value) "\\" "/"))

(defn- relative
  [root file]
  (portable (.relativize (paths/path root) (paths/path file))))

(defn- regular-files
  [root]
  (if-not (paths/directory? root)
    []
    (with-open [entries (Files/walk (paths/path root)
                                    (make-array FileVisitOption 0))]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (sort-by #(relative root %))
           vec))))

(defn- write-zip!
  [archive]
  (Files/createDirectories (.getParent (paths/path archive))
                           (make-array FileAttribute 0))
  (with-open [output
              (ZipOutputStream.
               (Files/newOutputStream (paths/path archive)
                                      (make-array OpenOption 0)))]
    (doseq [[name contents] (sort-by key encoded-sources)]
      (let [entry (doto (ZipEntry. name)
                    (.setTimeLocal (LocalDateTime/of 1980 1 1 0 0)))]
        (.putNextEntry output entry)
        (.write output (.getBytes contents StandardCharsets/UTF_8))
        (.closeEntry output))))
  archive)

(defn- render-provenance
  [tests-root fixture-files]
  (str
   (str/join "\t" provenance-columns) "\n"
   (apply
    str
    (for [file fixture-files
          :let [output-path (relative tests-root file)
                source-path (subs output-path (count "Fixtures/pkl/"))
                sha256 (util/sha256-file file)
                row
                [output-path
                 "vendored-third-party"
                 revision
                 source-path
                 sha256
                 "materialized-byte-for-byte-fixture-copy"
                 sha256
                 "Apache-2.0"
                 (str "Copyright Apple Inc.; vendored from apple/pkl "
                      "under Apache-2.0.")
                 "-" "-" "-" "-"]]]
      (str (str/join "\t" row) "\n")))))

(defn- write-inventory!
  [tests-root]
  (let [inventory (paths/resolve-path tests-root "SHA256SUMS")]
    (write-text!
     inventory
     (apply
      str
      (for [file (regular-files tests-root)
            :when (not= (paths/absolute inventory) (paths/absolute file))]
        (str (util/sha256-file file) "  " (relative tests-root file) "\n"))))))

(defn- seed-governed-product!
  [root]
  (let [tests-root (paths/resolve-path root "products/brine/tests")
        directory
        (paths/resolve-path tests-root "Fixtures/pkl" encoded-package-relative)
        archive (paths/resolve-path directory "encoded-assets@1.0.0.zip")]
    (doseq [[name contents] encoded-sources]
      (write-text! (paths/resolve-path directory "encoded-source" name)
                   contents))
    (write-zip! archive)
    (write-text!
     (paths/resolve-path directory "encoded-assets@1.0.0.json")
     (str "{\n"
          "  \"schemaVersion\": 1,\n"
          "  \"name\": \"encoded-assets\",\n"
          "  \"packageZipChecksums\": {\"sha256\": \""
          (util/sha256-file archive) "\"}\n"
          "}\n"))
    (let [fixture-files (regular-files directory)]
      (write-text! (paths/resolve-path tests-root "TEST-PROVENANCE.tsv")
                   (render-provenance tests-root fixture-files))
      (write-inventory! tests-root))
    {:tests-root tests-root
     :product-directory directory
     :upstream-directory
     (paths/resolve-path root "research/pkl" encoded-package-relative)}))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest clean-encoded-package-is-materialized-repeatably
  (let [root (temp-directory)
        {:keys [product-directory upstream-directory]}
        (seed-governed-product! root)]
    (try
      (dotimes [_ 2]
        (tree-cleanup/delete-tree! upstream-directory)
        (is (not (paths/exists? upstream-directory)))
        (let [result
              (brine-xunit/materialize-encoded-package-fixture! root)]
          (is (= 6 (:fixtures result)))
          (is (= product-directory
                 (paths/resolve-path (:source result)
                                     "Fixtures/pkl"
                                     encoded-package-relative)))
          (is (= upstream-directory (:destination result)))
          (is (= (mapv #(relative product-directory %)
                       (regular-files product-directory))
                 (mapv #(relative upstream-directory %)
                       (regular-files upstream-directory))))
          (is (= (mapv util/sha256-file (regular-files product-directory))
                 (mapv util/sha256-file
                       (regular-files upstream-directory))))))
      (testing "changed materialized residue cannot affect later generation"
        (write-text!
         (paths/resolve-path upstream-directory
                             "encoded-source/hello world.pkl")
         "changed residue\n")
        (is (= :materialized-encoded-package-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-encoded-package-fixture! root))))))
      (finally
        (tree-cleanup/delete-tree! root)))))

(deftest encoded-package-governed-input-fails-closed
  (testing "a missing checksum-governed source is rejected"
    (let [root (temp-directory)
          {:keys [product-directory]} (seed-governed-product! root)]
      (try
        (Files/delete
         (paths/resolve-path product-directory "encoded-assets@1.0.0.zip"))
        (is (= :encoded-package-governed-source-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-encoded-package-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root)))))
  (testing "changed governed content is rejected"
    (let [root (temp-directory)
          {:keys [product-directory]} (seed-governed-product! root)]
      (try
        (write-text!
         (paths/resolve-path product-directory
                             "encoded-source/hello world.pkl")
         "changed governed source\n")
        (is (= :encoded-package-governed-source-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-encoded-package-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root)))))
  (testing "license and provenance governance are exact"
    (let [root (temp-directory)
          {:keys [tests-root]} (seed-governed-product! root)
          ledger (paths/resolve-path tests-root "TEST-PROVENANCE.tsv")]
      (try
        (write-text! ledger
                     (str/replace-first (Files/readString ledger)
                                        "\tApache-2.0\t"
                                        "\t\t"))
        (write-inventory! tests-root)
        (is (= :encoded-package-governance-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-encoded-package-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root))))))
