(ns dripsharp.pdfcube-test-suite-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.test-suite :as test-suite]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.tree-cleanup :as tree-cleanup]
            [dripsharp.util :as util])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-pdfcarton-lifecycle-"
                             (make-array FileAttribute 0)))

(defn- write-file! [^Path path contents]
  (Files/createDirectories (.getParent path) (make-array FileAttribute 0))
  (Files/writeString path contents (make-array OpenOption 0))
  path)

(defn- failure-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest generated-pdfbox-tests-preserve-deterministic-junit-method-order
  (let [source (slurp "src/dripsharp/pdfcube/test_suite.clj")
        support (slurp "targets/pdfcube/adapted-tests/PdfCartonTestSupport.cs")]
    (is (str/includes? source "java-string-hash-code"))
    (is (str/includes? source "Xunit.TestCaseOrderer"))
    (is (str/includes? support "UpstreamTestCaseOrderer"))
    (is (str/includes? support "TestMethod?.MethodName"))))

(deftest generated-pdfbox-posix-support-uses-translated-permission-contract
  (let [generator (slurp "src/dripsharp/pdfcube/test_suite.clj")
        support (slurp "targets/pdfcube/adapted-tests/PdfCartonTestSupport.cs")
        permission-names ["UserRead" "UserWrite" "UserExecute"
                          "GroupRead" "GroupWrite" "GroupExecute"
                          "OtherRead" "OtherWrite" "OtherExecute"]]
    (is (str/includes? generator
                       "adapted-tests/PdfCartonTestSupport.cs"))
    (is (str/includes?
         support
         "global::DripSharp.Runtime.JavaUnixFileMode> GetPosixFilePermissions"))
    (is (not (str/includes?
              support
              "global::System.IO.UnixFileMode> GetPosixFilePermissions")))
    (is (str/includes? support "PosixPermissionsUseTranslatedJavaContract"))
    (doseq [permission permission-names]
      (is (str/includes?
           support
           (str "global::System.IO.UnixFileMode." permission)))
      (is (str/includes?
           support
           (str "global::DripSharp.Runtime.JavaUnixFileMode." permission))))))

(deftest generated-pdfbox-suite-isolates-mutable-artifact-cleanup
  (let [generator (slurp "src/dripsharp/pdfcube/test_suite.clj")
        support (slurp "targets/pdfcube/adapted-tests/PdfCartonTestSupport.cs")]
    (is (str/includes? support "MutableArtifactPath"))
    (is (str/includes? support "WritableFixtures"))
    (is (str/includes? support "TestOutputExternal"))
    (is (str/includes? support "TestInputExternal"))
    (is (str/includes?
         support
         "MutableArtifactCleanupPreservesGovernedFixturesAndBuildInputs"))
    (is (str/includes? support "ResetMutableTestArtifactsForContract"))
    (is (str/includes? support "ResetMutableArtifactRoot"))
    (is (str/includes? support "LifecycleContractArtifacts"))
    (is (str/includes? generator "VerifyGovernedFixtures"))
    (is (str/includes? generator "verify-built-lifecycle!"))
    (is (str/includes? generator "built-lifecycle-snapshots"))))

(deftest standalone-lifecycle-rejects-fixture-and-build-output-loss
  (let [root (temp-directory)
        project {:id "DripSharp.PdfCarton.Tests"
                 :assembly-name "DripSharp.PdfCarton.Tests"
                 :target-framework "net10.0"}
        contract {:publication {:test-suites {:strategies []}}}
        output (.resolve root "bin/Release/net10.0")
        fixture (.resolve output "Fixtures/probe.txt")
        fixture-sha (util/sha256-bytes (.getBytes "fixture" "UTF-8"))
        required ["DripSharp.PdfCarton.Tests.dll"
                  "DripSharp.PdfCarton.Tests.deps.json"
                  "DripSharp.PdfCarton.Tests.runtimeconfig.json"
                  "DripSharp.PdfCarton.IO.dll"
                  "DripSharp.PdfCarton.Fonts.dll"
                  "DripSharp.PdfCarton.Xmp.dll"
                  "DripSharp.PdfCarton.dll"
                  "DripSharp.PdfCarton.Preflight.dll"]
        options {:target-contract contract
                 :project project
                 :project-root root}]
    (try
      (write-file!
       (.resolve root "JAVA-TEST-INVENTORY.edn")
       (str (pr-str {:accounting
                     {:fixtures [{:destination "probe.txt"
                                  :sha256 fixture-sha}]}})
            "\n"))
      (write-file! fixture "fixture")
      (doseq [relative required]
        (write-file! (.resolve output relative) relative))
      (write-file! (.resolve root "obj/project.assets.json") "{}")

      (test-suite/strategy! (assoc options :phase :post-build))
      (Files/delete fixture)
      (is (= :pdfcarton-suite-fixture-lifecycle-drift
             (:reason
              (failure-data
               #(test-suite/strategy! (assoc options :phase :post-test))))))

      (write-file! fixture "fixture")
      (test-suite/strategy! (assoc options :phase :post-build))
      (Files/delete (.resolve output "DripSharp.PdfCarton.Tests.dll"))
      (is (= :pdfcarton-suite-build-output-lifecycle-drift
             (:reason
              (failure-data
               #(test-suite/strategy! (assoc options :phase :post-test))))))
      (finally
        (tree-cleanup/delete-tree! root)))))

(deftest complete-pinned-pdfbox-test-tree-is-losslessly-inventoried
  (let [inventory (test-suite/inventory!)
        contract (test-suite/read-contract! (paths/absolute "targets/pdfcube"))
        target-contract (target-directory/read-target :pdfcube)
        accounting (:accounting inventory)
        governed
        (test-suite/governed-fixtures!
         target-contract
         "DripSharp.PdfCarton.Tests"
         (:fixtures accounting))
        disabled (filter #(= :disabled (:state %))
                         (:enablement accounting))
        conditions (:platform-conditions accounting)]
    (is (= inventory (test-suite/verify-inventory! contract inventory)))
    (is (= #{:io :fontbox :xmpbox :pdfbox :preflight}
           (set (map :module (:sources accounting)))))
    (is (= 233 (count (:sources accounting))))
    (is (= 681 (count (:fixtures accounting))))
    (is (= 681 (count (distinct (map :destination
                                     (:fixtures accounting))))))
    (is (= 682 (count governed)))
    (is (= 682 (count (distinct (map :path governed)))))
    (is (= {:path "Fixtures/metadata.xmp"
            :sha256
            "e0e15a9c1ad91ba8389c1e23e3fd598e299aa28c73b45dee49b923e054464fc6"}
           (first (filter #(= "Fixtures/metadata.xmp" (:path %)) governed))))
    (is (= {:mechanically-upstream-derived 371
            :third-party-test-fixture 306
            :target-adapted-test-fixture 2
            :third-party-test-fixture-license 1
            :third-party-test-fixture-notice 1}
           (frequencies (map :authorship (:fixtures accounting)))))
    (is (every? #(and (not (str/blank? (:license %)))
                      (not (str/blank? (:attribution %))))
                (:fixtures accounting)))
    (is (= 1281 (count (:cases accounting))))
    (is (= 1281 (count (:enablement accounting))))
    (is (= 5 (count disabled)))
    (is (every? #(and (= :fontbox (:module %))
                      (not (str/blank? (:reason %))))
                disabled))
    (is (some #(str/starts-with?
                (:key %)
                "executable:org.junit.jupiter.api.Assumptions#")
              conditions))
    (is (some #(str/starts-with?
                (:key %)
                "executable:java.lang.System#getProperty(")
              conditions))
    (testing "the Maven module graph remains explicit in the accounting"
      (let [by-module (into {} (map (juxt :module identity))
                            (:dependencies accounting))]
        (is (= #{}
               (set (map :project-id
                         (get-in by-module [:io :production-projects])))))
        (is (= #{"org.apache.pdfbox:pdfbox-io:3.0.8"}
               (set (map :project-id
                         (get-in by-module
                                 [:fontbox :production-projects])))))
        (is (= #{"org.apache.pdfbox:fontbox:3.0.8"
                 "org.apache.pdfbox:pdfbox-io:3.0.8"}
               (set (map :project-id
                         (get-in by-module
                                 [:pdfbox :production-projects])))))
        (is (= #{"org.apache.pdfbox:fontbox:3.0.8"
                 "org.apache.pdfbox:pdfbox-io:3.0.8"
                 "org.apache.pdfbox:pdfbox:3.0.8"
                 "org.apache.pdfbox:xmpbox:3.0.8"}
               (set (map :project-id
                         (get-in by-module
                                 [:preflight :production-projects])))))))))
