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

(def ^:private keystore-relative
  "pkl-commons-test/build/keystore")

(def ^:private keystore-sha256
  {"localhost.p12"
   "cd43752faa963e7366440d98465c5efca8482df1a9fa39ec124b3854ad33fbb1"
   "localhost.pem"
   "f619edc6fc47477be15be4130c84df496856211cb97512a5ecd43f413143bb14"})

(def ^:private keystore-files
  (vec (sort (keys keystore-sha256))))

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

(defn- copy-file!
  [source destination]
  (Files/createDirectories (.getParent (paths/path destination))
                           (make-array FileAttribute 0))
  (Files/copy (paths/path source) (paths/path destination)
              (into-array java.nio.file.StandardCopyOption
                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
  destination)

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

(defn- rewrite-provenance-row!
  [ledger output-path transform]
  (let [prefix (str output-path "\t")
        lines (str/split-lines (Files/readString ledger))]
    (write-text!
     ledger
     (str (str/join "\n"
                    (map #(if (str/starts-with? % prefix)
                            (transform %)
                            %)
                         lines))
          "\n"))))

(defn- seed-governed-product!
  [root]
  (let [tests-root (paths/resolve-path root "products/brine/tests")
        directory
        (paths/resolve-path tests-root "Fixtures/pkl" encoded-package-relative)
        archive (paths/resolve-path directory "encoded-assets@1.0.0.zip")
        keystore-directory
        (paths/resolve-path tests-root "Fixtures/pkl" keystore-relative)
        governed-keystore
        (paths/resolve-path (paths/workspace-root)
                            "products/brine/tests/Fixtures/pkl"
                            keystore-relative)]
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
    (doseq [file keystore-files]
      (copy-file! (paths/resolve-path governed-keystore file)
                  (paths/resolve-path keystore-directory file)))
    (let [fixture-files
          (regular-files (paths/resolve-path tests-root "Fixtures/pkl"))]
      (write-text! (paths/resolve-path tests-root "TEST-PROVENANCE.tsv")
                   (render-provenance tests-root fixture-files))
      (write-inventory! tests-root))
    {:tests-root tests-root
     :product-directory directory
     :keystore-product-directory keystore-directory
     :upstream-directory
     (paths/resolve-path root "research/pkl" encoded-package-relative)
     :upstream-build-directory
     (paths/resolve-path root "research/pkl/pkl-commons-test/build")
     :upstream-keystore-directory
     (paths/resolve-path root "research/pkl" keystore-relative)}))

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
        (rewrite-provenance-row!
         ledger
         (str "Fixtures/pkl/" encoded-package-relative
              "/encoded-assets@1.0.0.json")
         #(str/replace-first % "\tApache-2.0\t" "\t\t"))
        (write-inventory! tests-root)
        (is (= :encoded-package-governance-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-encoded-package-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root))))))

(deftest cold-keystore-is-materialized-repeatably
  (let [root (temp-directory)
        {:keys [keystore-product-directory upstream-build-directory
                upstream-keystore-directory]}
        (seed-governed-product! root)]
    (try
      (dotimes [_ 2]
        (tree-cleanup/delete-tree! upstream-build-directory)
        (is (not (paths/exists? upstream-build-directory)))
        (let [result (brine-xunit/materialize-keystore-fixture! root)]
          (is (= 2 (:fixtures result)))
          (is (= upstream-keystore-directory (:destination result)))
          (is (= keystore-files
                 (mapv #(relative keystore-product-directory %)
                       (regular-files keystore-product-directory))))
          (is (= keystore-files
                 (mapv #(relative upstream-keystore-directory %)
                       (regular-files upstream-keystore-directory))))
          (is (= (mapv util/sha256-file
                       (regular-files keystore-product-directory))
                 (mapv util/sha256-file
                       (regular-files upstream-keystore-directory))))))
      (testing "freshly randomized Gradle output is replaced, not accepted"
        (write-text! (paths/resolve-path upstream-keystore-directory
                                         "localhost.p12")
                     "randomized Gradle keystore\n")
        (write-text! (paths/resolve-path upstream-keystore-directory
                                         "localhost.pem")
                     "randomized Gradle certificate\n")
        (brine-xunit/materialize-keystore-fixture! root)
        (is (= (mapv util/sha256-file
                     (regular-files keystore-product-directory))
               (mapv util/sha256-file
                     (regular-files upstream-keystore-directory)))))
      (finally
        (tree-cleanup/delete-tree! root)))))

(deftest keystore-governed-input-fails-closed
  (testing "a missing checksum-governed keystore source is rejected"
    (let [root (temp-directory)
          {:keys [keystore-product-directory]} (seed-governed-product! root)]
      (try
        (Files/delete
         (paths/resolve-path keystore-product-directory "localhost.p12"))
        (is (= :keystore-governed-source-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-keystore-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root)))))
  (testing "changed governed keystore content is rejected"
    (let [root (temp-directory)
          {:keys [keystore-product-directory]} (seed-governed-product! root)]
      (try
        (write-text! (paths/resolve-path keystore-product-directory
                                         "localhost.pem")
                     "changed governed certificate\n")
        (is (= :keystore-governed-source-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-keystore-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root)))))
  (testing "the pinned checksum inventory cannot bless different bytes"
    (let [root (temp-directory)
          {:keys [tests-root]} (seed-governed-product! root)
          inventory (paths/resolve-path tests-root "SHA256SUMS")]
      (try
        (write-text!
         inventory
         (str/replace-first
          (Files/readString inventory)
          (get keystore-sha256 "localhost.p12")
          (apply str (repeat 64 "0"))))
        (is (= :keystore-checksum-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-keystore-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root)))))
  (testing "pinned checksum, authorship class, license, and provenance are exact"
    (let [root (temp-directory)
          {:keys [tests-root]} (seed-governed-product! root)
          ledger (paths/resolve-path tests-root "TEST-PROVENANCE.tsv")]
      (try
        (rewrite-provenance-row!
         ledger
         (str "Fixtures/pkl/" keystore-relative "/localhost.p12")
         #(str/replace-first
           (str/replace-first
            %
            "\tvendored-third-party\t"
            "\tdripsharp-authored-test-infrastructure\t")
           "\tApache-2.0\t"
           "\t\t"))
        (write-inventory! tests-root)
        (is (= :keystore-governance-drift
               (:reason
                (failure-data
                 #(brine-xunit/materialize-keystore-fixture! root)))))
        (finally
          (tree-cleanup/delete-tree! root))))))
