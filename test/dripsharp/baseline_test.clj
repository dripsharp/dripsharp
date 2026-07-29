(ns dripsharp.baseline-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths]))

(deftest target-records-own-the-reviewed-baseline-contract
  (doseq [target [:pkl :pdfcube]
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
    (is (= (get-in pdf-record [:packages "PdfCube.PdfBox" :version])
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
                            "PdfCube.Family.HostSmoke.csproj")))]
    (is (str/includes? props "targets\\pdfcube\\baseline.edn"))
    (is (str/includes? family "$(PdfCubeIOPackageVersion)"))
    (is (str/includes? family "$(PdfCubePreflightPackageVersion)"))
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
