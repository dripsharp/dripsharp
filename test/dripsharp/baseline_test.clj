(ns dripsharp.baseline-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest baseline-files-require-exactly-one-edn-form
  (let [root
        (Files/createTempDirectory
         "dripsharp-baseline-test"
         (make-array FileAttribute 0))
        target :rawhttp
        file (baseline/baseline-path root target)
        record (baseline/read-baseline target)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (doseq [[text reason]
            [["" :empty-edn]
             ["{" :invalid-edn]
             [(str (pr-str record) "\n{:unreviewed :trailing-form}\n")
              :trailing-data]]]
      (Files/writeString file text (make-array java.nio.file.OpenOption 0))
      (let [error
            (try
              (baseline/read-baseline root target)
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :invalid-target-baseline (:kind (ex-data error))))
        (is (= target (:target (ex-data error))))
        (is (= (str file) (:path (ex-data error))))
        (is (= reason (:reason (ex-data error))))))))

(deftest target-records-own-the-reviewed-baseline-contract
  (doseq [target [:pkl :pdfcube :rawhttp]
          :let [record (baseline/read-baseline target)]]
    (testing (name target)
      (is (= target (:target record)))
      (is (string? (get-in record [:upstream :version])))
      (is (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}"
                      (get-in record [:upstream :revision])))
      (is (string? (get-in record [:upstream :license])))
      (is (pos-int? (get-in record [:upstream :java-language-version])))
      (is (map? (:artifacts record)))
      (is (seq (:legal-sets record)))
      (is (seq (:packages record)))
      (is (every? #(pos-int? (:public-contract-rows %))
                  (vals (:profiles record))))
      (is (every? #(= #{:ordinary :generated}
                      (set (keys (:source-counts %))))
                  (vals (:profiles record)))))))

(deftest baseline-contract-rejects-undeclared-fields
  (let [record (baseline/read-baseline :rawhttp)
        package-id (first (keys (:packages record)))
        profile-id (first (keys (:profiles record)))]
    (doseq [[subject changed]
            [["baseline"
              (assoc record :unreviewed true)]
             ["upstream identity"
              (assoc-in record [:upstream :unreviewed] true)]
             ["legal-file contract"
              (assoc-in record [:legal-sets :upstream 0 :unreviewed] true)]
             ["package contract"
              (assoc-in record [:packages package-id :unreviewed] true)]
             ["profile contract"
              (assoc-in record [:profiles profile-id :unreviewed] true)]
             ["source-count contract"
              (assoc-in record
                        [:profiles profile-id :source-counts :unreviewed]
                        0)]]]
      (testing subject
        (let [error
              (try
                (baseline/validate-record! :rawhttp changed)
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-target-baseline (:kind (ex-data error))))
          (is (= :rawhttp (:target (ex-data error)))))))))

(deftest baseline-upstream-attribution-metadata-must-be-single-line
  (let [record (baseline/read-baseline :rawhttp)]
    (doseq [field [:name :version :repository :license :notice-reference]
            separator
            ["\u0000" "\u000B" "\u000C" "\r" "\n"
             "\u0085" "\u2028" "\u2029"]]
      (testing (str (name field) " rejects " (pr-str separator))
        (let [error
              (try
                (baseline/validate-record!
                 :rawhttp
                 (assoc-in record [:upstream field]
                           (str "Upstream" separator "forged")))
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-target-baseline (:kind (ex-data error))))
          (is (= :rawhttp (:target (ex-data error)))))))))

(deftest baseline-legal-file-paths-must-be-normalized-portable-and-xml-safe
  (let [record (baseline/read-baseline :rawhttp)]
    (doseq [field [:source :destination :package-path]
            separator
            ["\u0000" "\u0001" "\t" "\u000B" "\u000C" "\r" "\n"
             "\u0085" "\u2028" "\u2029"]]
      (testing (str (name field) " rejects " (pr-str separator))
        (let [error
              (try
                (baseline/validate-record!
                 :rawhttp
                 (assoc-in record [:legal-sets :upstream 0 field]
                           (str "Legal/LICENSE.txt" separator "forged")))
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-target-baseline (:kind (ex-data error))))
          (is (= :rawhttp (:target (ex-data error)))))))
    (doseq [field [:source :destination :package-path]
            unsafe-path
            ["/LICENSE"
             "../LICENSE"
             "legal/../LICENSE"
             "./LICENSE"
             "legal//LICENSE"
             "legal/"
             "C:/LICENSE"
             "legal\\LICENSE"
             "legal/LIC:ENSE"
             "legal/LIC<ENSE"
             "legal/LIC>ENSE"
             "legal/LIC\"ENSE"
             "legal/LIC|ENSE"
             "legal/LIC?ENSE"
             "legal/LIC*ENSE"
             "legal/CON"
             "legal/con.txt"
             "legal/AuX.notice"
             "legal/COM1"
             "legal/lpt9.log"
             "legal/COM¹.txt"
             "legal/LICENSE."
             "legal/LICENSE "]]
      (testing (str (name field) " rejects " (pr-str unsafe-path))
        (let [error
              (try
                (baseline/validate-record!
                 :rawhttp
                 (assoc-in record [:legal-sets :upstream 0 field]
                           unsafe-path))
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-target-baseline (:kind (ex-data error))))
          (is (= :rawhttp (:target (ex-data error)))))))
    (let [supplementary (String. (Character/toChars 0x1F680))
          candidate
          (reduce (fn [result field]
                    (update-in result [:legal-sets :upstream 0 field]
                               str supplementary))
                  record
                  [:source :destination :package-path])]
      (is (= candidate
             (baseline/validate-record! :rawhttp candidate))))))

(deftest baseline-optional-source-digest-must-be-absent-or-valid
  (let [record (baseline/read-baseline :rawhttp)
        without-source-digest
        (update-in record [:legal-sets :upstream 0] dissoc :source-sha256)
        error
        (try
          (baseline/validate-record!
           :rawhttp
           (assoc-in record [:legal-sets :upstream 0 :source-sha256] nil))
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= record (baseline/validate-record! :rawhttp record)))
    (is (= without-source-digest
           (baseline/validate-record! :rawhttp without-source-digest)))
    (is (= :invalid-target-baseline (:kind (ex-data error))))
    (is (= :rawhttp (:target (ex-data error))))))

(deftest rawhttp-license-input-and-package-file-are-pinned
  (let [workspace (paths/workspace-root)
        destination
        (java-project/read-configuration
         workspace "targets/rawhttp/destinations/core.edn")
        assets-fn
        (get-in (java-library/rule-bundle)
                [:rules :destination-bridges :assets])
        assets (assets-fn {:workspace-root workspace
                           :configuration destination})
        legal-assets
        (filterv #(= "java-library.legal"
                     (namespace (:strategy %)))
                 assets)
        project-text (java-project/project-text destination [])
        expected-hash
        "f6bc359d3a4bf1e4851ead6019d8f33075e06ff2c16ed171ecb83ee90509c69c"
        mismatch
        (try
          (assets-fn
           {:workspace-root workspace
            :configuration
            (assoc-in destination
                      [:legal-files 0 :source-sha256]
                      (apply str (repeat 64 "0")))})
          nil
          (catch clojure.lang.ExceptionInfo error error))]
    (is (= (baseline/legal-files :rawhttp [:upstream])
           (:legal-files destination)))
    (is (= expected-hash
           (get-in destination [:legal-files 0 :source-sha256])))
    (is (= [{:source "research/rawhttp/LICENSE.txt"
             :destination "Legal/LICENSE.txt"
             :strategy :java-library.legal/license
             :missing-kind :missing-java-library-legal-input
             :missing-message
             "Configured Java-library legal input is missing"}]
           legal-assets))
    (is (str/includes?
         project-text
         "<PackageLicenseFile>LICENSE.txt</PackageLicenseFile>"))
    (is (str/includes?
         project-text
         "Pack=\"true\" PackagePath=\"LICENSE.txt\""))
    (is (not (str/includes? project-text "PackageLicenseExpression")))
    (is (= :java-library-legal-input-mismatch
           (:kind (ex-data mismatch))))
    (is (= expected-hash (:actual (ex-data mismatch))))))

(deftest profiles-and-destinations-resolve-their-pins-from-the-record
  (let [root (paths/workspace-root)
        pdf-record (baseline/read-baseline :pdfcube)
        profile
        (harness/read-profile root "targets/pdfcube/profiles/pdfbox.edn")
        destination
        (java-project/read-configuration
         root "targets/pdfcube/destinations/pdfbox.edn")]
    (is (= (get-in pdf-record [:upstream :revision]) (:revision profile)))
    (is (= (get-in pdf-record [:profiles :pdfbox :source-project-id])
           (:maven-project-id profile)))
    (is (= (get-in pdf-record [:packages "DripSharp.PdfCarton" :version])
           (get-in destination [:package :version])))
    (is (= (get-in pdf-record [:upstream :revision])
           (get-in destination [:package :repository-commit])))
    (is (= (baseline/legal-files :pdfcube [:upstream :codecs])
           (:legal-files destination)))
    (doseq [[coordinate dependency] (:external-dependencies destination)]
      (is (= (baseline/artifact-sha256 :pdfcube coordinate)
             (:artifact-sha256 dependency))))))

(deftest live-input-count-and-java-drift-fail-closed
  (let [contract (baseline/profile :pdfcube :io)
        counts (:source-counts contract)
        input {:production-sources (vec (repeat (:ordinary counts) :source))
               :generated-production-sources
               (vec (repeat (:generated counts) :generated))
               :java-toolchain
               {:release (baseline/java-language-version :pdfcube)}}]
    (is (= input
           (baseline/validate-project-input!
            (paths/workspace-root) :pdfcube "pdfcube-io" input)))
    (let [error
          (try
            (baseline/validate-project-input!
             (paths/workspace-root) :pdfcube "pdfcube-io"
             (update input :production-sources conj :drift))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :baseline-source-count-drift (:kind (ex-data error)))))
    (let [error
          (try
            (baseline/validate-project-input!
             (paths/workspace-root) :pdfcube "pdfcube-io"
             (assoc-in input [:java-toolchain :release] 99))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :baseline-java-language-version-drift
             (:kind (ex-data error)))))))

(deftest package-only-smokes-consume-msbuild-baseline-properties
  (let [root (paths/workspace-root)
        props (slurp (str (paths/resolve-path
                           root "validation" "Directory.Build.props")))
        family (slurp (str (paths/resolve-path
                            root "validation" "pdfcube-family"
                            "PdfCarton.Family.HostSmoke.csproj")))]
    (is (str/includes? props "targets\\pdfcube\\baseline.edn"))
    (is (str/includes? family "$(PdfCartonIOPackageVersion)"))
    (is (str/includes? family "$(PdfCartonPreflightPackageVersion)"))
    (is (not (str/includes? family "3.0.8-dripsharp.0")))))

(deftest configuration-files-contain-baseline-references-not-copied-pins
  (let [root (paths/workspace-root)
        files (concat
               ["targets/pkl/profiles/parser.edn"
                "targets/pkl/profiles/core.edn"
                "targets/pkl/destinations/parser.edn"
                "targets/pkl/destinations/core.edn"]
               (mapcat
                (fn [name]
                  [(str "targets/pdfcube/profiles/" name ".edn")
                   (str "targets/pdfcube/destinations/" name ".edn")])
                ["io" "fontbox" "xmpbox" "pdfbox" "preflight"]))]
    (doseq [relative files
            :let [data (edn/read-string
                        (slurp (str (paths/resolve-path root relative))))]]
      (is (contains? data :baseline-target) relative)
      (is (contains? data :baseline-profile) relative)
      (is (not (contains? data :revision)) relative)
      (is (not (contains? data :mechanical-source)) relative))))
