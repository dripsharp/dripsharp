(ns dripsharp.authorship-portfolio-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.authorship-portfolio :as portfolio]
            [dripsharp.paths :as paths]
            [dripsharp.target-directory :as target-directory])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- write-file!
  [^Path file contents]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file contents (make-array OpenOption 0))
  file)

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- target-commit
  [target]
  (apply str (repeat 40 (case target
                          :pdfcube "2"
                          :pkl "1"
                          :sqltrellis "3"))))

(defn- profile-closure
  [contract root-profile]
  (loop [pending [root-profile] result #{}]
    (if-let [profile (peek pending)]
      (if (contains? result profile)
        (recur (pop pending) result)
        (recur
         (into (pop pending)
               (get-in contract
                       [:profiles profile :configuration
                        :dependency-profiles]))
         (conj result profile)))
      result)))

(defn- fixture
  [^Path root]
  (let [source-root (paths/workspace-root)
        contracts
        (into {}
              (for [target [:pdfcube :pkl :sqltrellis]]
                [target (target-directory/read-target source-root target)]))]
    (doseq [target [:pdfcube :pkl :sqltrellis]]
      (write-file!
       (paths/resolve-path root "targets" (name target) "target.edn")
       (str (pr-str {:target target
                     :publication {:kind :generated-repository}})
            "\n")))
    (write-file!
     (paths/resolve-path root "targets" "rawhttp" "target.edn")
     (str (pr-str {:target :rawhttp
                   :publication {:kind :conformance-only}})
          "\n"))
    {:contracts contracts
     :read-target-fn
     (fn [_ target]
       (get contracts (keyword target)))}))

(deftest portfolio-uses-complete-proofs-and-minimal-package-closures
  (let [root (Files/createTempDirectory
              "dripsharp-authorship-portfolio-test-"
              (make-array FileAttribute 0))
        {:keys [contracts read-target-fn]} (fixture root)
        proof-calls (atom [])
        package-calls (atom [])
        suite-calls (atom [])
        report-options (atom nil)
        stale (write-file!
               (paths/resolve-path root "target" "authorship-report" "all"
                                   "stale.txt")
               "stale")
        result
        (portfolio/write!
         {:workspace-root root
          :selection "all"
          :read-target-fn read-target-fn
          :proof-fn
          (fn [{:keys [target]}]
            (swap! proof-calls conj target)
            :complete)
          :package-fn
          (fn [{:keys [target profile]}]
            (let [target-id (keyword target)
                  contract (get contracts target-id)
                  profiles (sort (profile-closure contract profile))]
              (swap! package-calls conj [target profile])
              {:packing-summary
               {:repository-commit (target-commit target-id)}
               :packages
               (mapv
                (fn [package-profile]
                  {:profile package-profile
                   :identity
                   {:id
                    (get-in contract
                            [:profiles package-profile :destination
                             :configuration :package :id])}
                   :authorship {:fixture package-profile}
                   :inspection {:authorship {:fixture package-profile}}})
                profiles)}))
          :test-suite-report-fn
          (fn [_ contract commit]
            (swap! suite-calls conj [(:target contract) commit])
            {:target (:target contract)})
          :write-report-fn
          (fn [options]
            (reset! report-options options)
            {:written true})})]
    (is (= ["pdfcube" "pkl" "sqltrellis"] @proof-calls))
    (is (= [["pdfcube" "pdfcube-preflight"]
            ["pkl" "pkl-core-value-model"]
            ["sqltrellis" "sqltrellis"]]
           @package-calls))
    (is (= [[:pdfcube (target-commit :pdfcube)]
            [:pkl (target-commit :pkl)]
            [:sqltrellis (target-commit :sqltrellis)]]
           @suite-calls))
    (is (= {:output
            (str (paths/resolve-path root "target" "authorship-report" "all"))
            :products 3
            :packages 8}
           (:summary result)))
    (is (= [5 2 1]
           (mapv #(count (:packages %)) (:products @report-options))))
    (is (false? (paths/exists? stale)))
    (is (false? (paths/exists?
                 (paths/resolve-path root "target" "nuget-release"))))))

(deftest portfolio-selection-is-explicit-and-does-no-work-when-invalid
  (let [root (Files/createTempDirectory
              "dripsharp-authorship-portfolio-selection-test-"
              (make-array FileAttribute 0))
        {:keys [read-target-fn]} (fixture root)
        calls (atom [])
        options
        {:workspace-root root
         :selection "rawhttp"
         :read-target-fn read-target-fn
         :proof-fn #(swap! calls conj [:proof %])
         :package-fn #(swap! calls conj [:package %])
         :test-suite-report-fn #(swap! calls conj [:test-suite %1 %2 %3])
         :write-report-fn #(swap! calls conj [:write %])}
        failure (failure-data #(portfolio/write! options))]
    (is (= :authorship-portfolio-failed (:kind failure)))
    (is (empty? @calls))))

(deftest portfolio-rejects-incomplete-package-evidence
  (let [root (Files/createTempDirectory
              "dripsharp-authorship-portfolio-packages-test-"
              (make-array FileAttribute 0))
        {:keys [read-target-fn]} (fixture root)
        failure
        (failure-data
         #(portfolio/write!
           {:workspace-root root
            :selection "pkl"
            :read-target-fn read-target-fn
            :proof-fn (constantly :complete)
            :package-fn
            (fn [_]
              {:packing-summary {:repository-commit (target-commit :pkl)}
               :packages []})
            :test-suite-report-fn (constantly {})
            :write-report-fn (constantly {})}))]
    (testing "the report cannot silently omit a production package"
      (is (= :authorship-portfolio-failed (:kind failure)))
      (is (= :pkl (:target failure))))))
