(ns dripsharp.pkl-legal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as project-emission]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.java-project :as pkl-project])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-pkl-legal"
                             (make-array FileAttribute 0)))

(defn- sha256-bytes [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest bytes)
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- sha256-text [value]
  (sha256-bytes (.getBytes value StandardCharsets/UTF_8)))

(defn- copy-legal-inputs! [^Path source-root ^Path target-root]
  (doseq [name ["LICENSE.txt" "NOTICE.txt"]]
    (let [source (paths/resolve-path source-root "research/pkl" name)
          target (paths/resolve-path target-root "research/pkl" name)]
      (Files/createDirectories (.getParent target)
                               (make-array FileAttribute 0))
      (Files/copy source target
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING])))))

(defn- caught [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest pkl-core-legal-inputs-and-package-metadata-are-pinned
  (let [workspace (paths/workspace-root)
        profile
        (harness/read-profile workspace "targets/pkl/profiles/core.edn")
        destination
        (project-emission/read-configuration
         workspace "targets/pkl/destinations/core.edn")
        bundle (pkl-project/rule-bundle)
        validate-profile! (get-in bundle [:orchestration :validate-profile!])
        project-text
        ((get-in bundle [:rules :project-policy :project-text])
         destination [])
        assets
        ((get-in bundle [:rules :product-runtime-assets :assets])
         {:workspace-root workspace :configuration destination})
        legal-assets
        (filterv #(= "pkl-core.legal" (namespace (:strategy %))) assets)
        notice-asset
        (first (filter #(= :pkl-core.legal/notice (:strategy %))
                       legal-assets))
        upstream-notice
        (Files/readString
         (paths/resolve-path workspace "research/pkl/NOTICE.txt"))
        packaged-notice
        (get (:text-replacements notice-asset) upstream-notice)
        package-files
        (into {} (map (juxt :kind identity) (:legal-files destination)))]
    (is (= destination
           (validate-profile! {:workspace-root workspace
                               :profile profile
                               :configuration destination})))
    (is (= {:repository "https://github.com/apple/pkl.git"
            :revision "f7cac257ade5775c1dfc255f4fda2eacc296e9d0"
            :notice-reference "NOTICE.txt"}
           (:mechanical-source destination)))
    (is (= #{:license :notice} (set (keys package-files))))
    (is (= 2 (count legal-assets)))
    (is (= (:source-sha256 (:license package-files))
           (:sha256 (:license package-files))))
    (is (= (:source-sha256 (:notice package-files))
           (sha256-bytes
            (Files/readAllBytes
             (paths/resolve-path workspace "research/pkl/NOTICE.txt")))))
    (is (str/starts-with? packaged-notice upstream-notice))
    (is (= (:notice-appendix destination)
           (subs packaged-notice (count upstream-notice))))
    (is (str/includes? packaged-notice
                       "\n---\nDripSharp translation appendix\n\n"))
    (is (= (:sha256 (:notice package-files))
           (sha256-text packaged-notice)))
    (testing "NuGet uses the packed license file and packs both legal payloads"
      (is (str/includes? project-text
                         "<PackageLicenseFile>LICENSE.txt</PackageLicenseFile>"))
      (is (str/includes? project-text
                         "Pack=\"true\" PackagePath=\"LICENSE.txt\""))
      (is (str/includes? project-text
                         "Pack=\"true\" PackagePath=\"NOTICE.txt\""))
      (is (not (str/includes? project-text "PackageLicenseExpression"))))))

(deftest pkl-core-generation-rejects-changed-legal-inputs
  (let [workspace (paths/workspace-root)
        profile
        (harness/read-profile workspace "targets/pkl/profiles/core.edn")
        destination
        (project-emission/read-configuration
         workspace "targets/pkl/destinations/core.edn")
        root (temp-directory)
        _ (copy-legal-inputs! workspace root)
        changed-notice (paths/resolve-path root "research/pkl/NOTICE.txt")
        _ (Files/writeString changed-notice "changed notice"
                             (make-array OpenOption 0))
        error
        (caught
         #((get-in (pkl-project/rule-bundle)
                   [:orchestration :validate-profile!])
           {:workspace-root root
            :profile profile
            :configuration destination}))]
    (is (= :pkl-core-legal-input-mismatch
           (:kind (ex-data error))))
    (is (= :notice (:legal-kind (ex-data error))))
    (is (= (:source-sha256
            (first (filter #(= :notice (:kind %))
                           (:legal-files destination))))
           (:expected (ex-data error))))))
