(ns dripsharp.required-ci-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.target-directory :as target-directory])
  (:import [java.io File]))

(def ^:private required-workflow
  ".github/workflows/required-proof-ladders.yml")

(def ^:private proof-workflows
  {:rawhttp required-workflow
   :pkl required-workflow
   :pdfcube ".github/workflows/pdfcube-family-hosts.yml"})

(defn- target-ids
  []
  (->> (.listFiles (File. "targets"))
       (filter #(.isDirectory ^File %))
       (filter #(-> (File. ^File % "target.edn") .isFile))
       (map #(keyword (.getName ^File %)))
       set))

(deftest required-ci-covers-every-target-proof-contract
  (is (= (target-ids) (set (keys proof-workflows))))
  (doseq [[target workflow] proof-workflows]
    (testing (name target)
      (let [contract (target-directory/read-target target)
            contents (slurp workflow)
            resource-classes
            (set (map :resource-class
                      (get-in contract [:proof :ladders])))]
        (is (seq (get-in contract [:proof :ladders])))
        (is (str/includes? contents "pull_request:"))
        (is (str/includes? contents "push:"))
        (is (str/includes? contents
                           (str "-M:run proof " (name target))))
        (is (str/includes? contents "DRIPSHARP_WORKERS: 22"))
        (is (str/includes? contents "JAVA_TOOL_OPTIONS: -Xmx28g"))
        (is (str/includes? contents "/MemAvailable/"))
        (is (str/includes? contents "available_cpu < 22"))
        (is (str/includes? contents
                           "runs-on: [self-hosted, linux, x64]"))
        (is (= (if (= :rawhttp target)
                 #{:conformance}
                 #{:high-memory})
               resource-classes)))))
  (let [contents (slurp required-workflow)]
    (is (str/includes? contents
                       "-M:run java-compat-differential"))
    (is (not (str/includes? contents "continue-on-error")))))

(deftest supported-host-workflows-use-live-metadata-driven-commands
  (doseq [^File workflow
          (->> (.listFiles (File. ".github/workflows"))
               (filter #(.isFile ^File %))
               (filter #(str/starts-with? (.getName ^File %) "pdfcube-")))]
    (let [contents (slurp workflow)]
      (is (str/includes? contents "/MemAvailable/")
          (.getPath workflow))
      (is (not (re-find
                #"-M:run pdfcube-(?:family-workflows|io-differential|fontbox-differential|xmpbox-metadata-differential|pdfbox-differential|preflight-differential|pdfbox-printing-differential)"
                contents))
          (.getPath workflow)))))

(deftest proof-workflows-use-resource-gated-generic-self-hosted-runners
  (doseq [^File workflow
          (->> (.listFiles (File. ".github/workflows"))
               (filter #(.isFile ^File %))
               (filter #(or (= "required-proof-ladders.yml"
                               (.getName ^File %))
                            (str/starts-with?
                             (.getName ^File %)
                             "pdfcube-"))))]
    (let [contents (slurp workflow)]
      (is (str/includes? contents
                         "runs-on: [self-hosted, linux, x64]")
          (.getPath workflow))
      (is (not (str/includes? contents "dripsharp-proof"))
          (.getPath workflow)))))
