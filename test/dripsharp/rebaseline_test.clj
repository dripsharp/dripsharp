(ns dripsharp.rebaseline-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.baseline :as baseline]
            [dripsharp.paths :as paths]
            [dripsharp.rebaseline :as rebaseline]
            [dripsharp.util :as util])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory
   "dripsharp-rebaseline-test"
   (make-array FileAttribute 0)))

(defn- copy-file!
  [^Path source ^Path destination]
  (Files/createDirectories (.getParent destination)
                           (make-array FileAttribute 0))
  (Files/copy source destination
              (into-array java.nio.file.CopyOption
                          [StandardCopyOption/REPLACE_EXISTING]))
  destination)

(defn- isolated-workspace
  []
  (let [source (paths/workspace-root)
        target (temp-directory)]
    (doseq [relative
            (concat (vals baseline/baseline-files)
                    rebaseline/protected-contract-files)]
      (copy-file! (paths/resolve-path source relative)
                  (paths/resolve-path target relative)))
    target))

(defn- observed-version
  [version]
  (fn [root target]
    (assoc-in (baseline/read-baseline root target)
              [:upstream :version] version)))

(deftest preview-presents-the-complete-candidate-and-exact-approval
  (let [root (isolated-workspace)
        preview (rebaseline/preview! root :pkl (observed-version "0.33.0"))]
    (is (= :review-target-rebaseline (:operation preview)))
    (is (= :pkl (:target preview)))
    (is (= "0.32.0" (get-in preview [:current :upstream :version])))
    (is (= "0.33.0" (get-in preview [:candidate :upstream :version])))
    (is (= [{:path [:upstream :version]
             :before "0.32.0"
             :after "0.33.0"}]
           (:delta preview)))
    (is (= 1 (:changed-fields preview)))
    (is (true? (:approval-required? preview)))
    (is (= {:required? false
            :changed-fields 0
            :delta []}
           (:legal-review preview)))
    (is (string? (:approval-command preview)))
    (is (re-matches #"[0-9a-f]{64}" (:approval-token preview)))
    (is (= :unchanged (:protected-contract-action preview)))
    (is (every? #(contains? (set (:protected-contract-files preview)) %)
                rebaseline/protected-contract-files))))

(deftest preview-surfaces-legal-changes-for-explicit-review
  (let [root (isolated-workspace)
        changed-hash (apply str (repeat 64 "a"))
        appendix "\nExplicitly reviewed replacement appendix.\n"
        observe
        (fn [workspace target]
          (-> (baseline/read-baseline workspace target)
              (assoc-in [:upstream :license] "LicenseRef-Review-Required")
              (assoc-in [:legal-sets :core 0 :source-sha256] changed-hash)
              (assoc-in [:legal-sets :core 0 :sha256] changed-hash)
              (assoc :notice-appendix appendix)))
        preview (rebaseline/preview! root :pkl observe)
        legal-review (:legal-review preview)]
    (is (true? (:required? legal-review)))
    (is (= 4 (:changed-fields legal-review)))
    (is (= (set (:delta legal-review))
           (set (filter #(contains?
                          #{[:upstream :license]
                            [:legal-sets :core 0 :source-sha256]
                            [:legal-sets :core 0 :sha256]
                            [:notice-appendix]}
                          (:path %))
                        (:delta preview)))))
    (is (= #{[:upstream :license]
             [:legal-sets :core 0 :source-sha256]
             [:legal-sets :core 0 :sha256]
             [:notice-appendix]}
           (set (map :path (:delta legal-review)))))
    (let [result
          (rebaseline/approve!
           root :pkl (:approval-token preview) observe)]
      (is (= legal-review (:legal-review result))))))

(deftest rawhttp-preview-reports-its-baseline-and-isolates-legal-review
  (let [root (isolated-workspace)
        changed-hash (apply str (repeat 64 "b"))
        observe
        (fn [workspace target]
          (-> (baseline/read-baseline workspace target)
              (assoc-in [:upstream :license]
                        "LicenseRef-RawHTTP-Review-Required")
              (assoc-in [:legal-sets :upstream 0 :source-sha256]
                        changed-hash)
              (assoc-in [:legal-sets :upstream 0 :sha256]
                        changed-hash)))
        preview (rebaseline/preview! root :rawhttp observe)
        legal-review (:legal-review preview)]
    (is (= :rawhttp (:target preview)))
    (is (= "targets/rawhttp/baseline.edn" (:baseline-file preview)))
    (is (= #{[:upstream :license]
             [:legal-sets :upstream 0 :source-sha256]
             [:legal-sets :upstream 0 :sha256]}
           (set (map :path (:delta legal-review)))))
    (is (true? (:required? legal-review)))
    (is (= 3 (:changed-fields legal-review)))
    (is (str/includes? (:approval-command preview)
                       "rebaseline rawhttp --approve"))))

(deftest rawhttp-package-rebasing-preserves-absent-optional-fields
  (let [root (isolated-workspace)
        record (baseline/read-baseline root :rawhttp)
        observe-packages
        (ns-resolve 'dripsharp.rebaseline 'observed-packages)
        packages (observe-packages :rawhttp record "2.6.0")]
    (is (= {"RawHttp.Core" {:version "2.6.0-dripsharp.0"}}
           packages))
    (is (not (contains? (get packages "RawHttp.Core")
                        :assembly-version)))))

(deftest approval-is-token-gated-and-writes-only-the-selected-record
  (let [root (isolated-workspace)
        observe (observed-version "0.33.0")
        preview (rebaseline/preview! root :pkl observe)
        pkl-file (baseline/baseline-path root :pkl)
        pdf-file (baseline/baseline-path root :pdfcube)
        pdf-before (util/sha256-file pdf-file)
        protected-before
        (into {}
              (map (fn [relative]
                     [relative
                      (util/sha256-file (paths/resolve-path root relative))]))
              rebaseline/protected-contract-files)]
    (testing "an unreviewed token cannot update the record"
      (let [before (util/sha256-file pkl-file)
            error
            (try
              (rebaseline/approve! root :pkl "wrong-token" observe)
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :rebaseline-approval-token-mismatch
               (:kind (ex-data error))))
        (is (= before (util/sha256-file pkl-file)))))
    (testing "the exact preview token approves exactly that candidate"
      (let [result
            (rebaseline/approve!
             root :pkl (:approval-token preview) observe)]
        (is (= :approved-target-rebaseline (:operation result)))
        (is (= "0.33.0"
               (get-in (baseline/read-baseline root :pkl)
                       [:upstream :version])))
        (is (= pdf-before (util/sha256-file pdf-file)))
        (is (= protected-before
               (into {}
                     (map (fn [relative]
                            [relative
                             (util/sha256-file
                              (paths/resolve-path root relative))]))
                     rebaseline/protected-contract-files)))))))

(deftest approval-refuses-an-empty-or-stale-delta
  (let [root (isolated-workspace)
        unchanged (fn [workspace target]
                    (baseline/read-baseline workspace target))
        preview (rebaseline/preview! root :pkl unchanged)
        error
        (try
          (rebaseline/approve! root :pkl
                               (:approval-token preview)
                               unchanged)
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (false? (:approval-required? preview)))
    (is (= {:required? false
            :changed-fields 0
            :delta []}
           (:legal-review preview)))
    (is (not (contains? preview :approval-command)))
    (is (= :empty-rebaseline-delta (:kind (ex-data error))))))
