(ns dripsharp.test-runner
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [dripsharp.paths :as paths])
  (:import [java.nio.file FileVisitOption Files Path]))

(def unit-test-namespaces
  "Process-free unit tier for the reusable translation and emission kernel.

  This explicit inventory complements the default all-test discovery. It must
  not include namespaces that launch Spoon models, build tools, dotnet, package
  consumers, or differential oracles."
  '[dripsharp.translation-kernel-test
    dripsharp.java-mapping-registry-test
    dripsharp.validation-test
    dripsharp.csharp-test
    dripsharp.project-xml-test
    dripsharp.source-accountability-test
    dripsharp.authorship-report-test
    dripsharp.bundle-contract-test
    dripsharp.test-runner-test])

(def test-tiers
  {:unit
   {:capabilities
    #{:translation-planning
      :mapping-registry
      :configuration-diagnostics
      :csharp-rendering
      :project-emission
      :source-accountability
      :bundle-contract}
    :namespaces unit-test-namespaces}})

(def ^:dynamic *test-tier* nil)

(defn- file->namespace
  [^Path test-root ^Path file]
  (-> (str (.relativize test-root file))
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace #"[/\\]" ".")
      symbol))

(defn- all-test-namespaces
  []
  (let [test-root (paths/absolute "test")]
    (with-open [files (Files/walk test-root (make-array FileVisitOption 0))]
      (->> (.toArray files)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (filter #(str/ends-with? (str %) "_test.clj"))
           (map #(file->namespace test-root %))
           sort
           vec))))

(defn- selected-namespaces
  [args]
  (cond
    (empty? args)
    (all-test-namespaces)

    (= "--tier" (first args))
    (let [[_ tier & unexpected] args
          tier (some-> tier keyword)
          namespaces (get-in test-tiers [tier :namespaces])]
      (when-not (and tier (empty? unexpected) namespaces)
        (throw
         (ex-info
          "Usage: clojure -M:test [--tier unit | --namespace namespace]..."
          {:kind :invalid-test-arguments
           :arguments args
           :available-tiers (vec (sort (keys test-tiers)))})))
      namespaces)

    :else
    (loop [remaining args selected []]
      (if (empty? remaining)
        selected
        (if (and (= "--namespace" (first remaining)) (second remaining))
          (recur (nnext remaining) (conj selected (symbol (second remaining))))
          (throw
           (ex-info
            "Usage: clojure -M:test [--tier unit | --namespace namespace]..."
            {:kind :invalid-test-arguments :arguments args})))))))

(defn -main
  [& args]
  (try
    (let [namespaces (selected-namespaces args)
          tier (when (= "--tier" (first args))
                 (keyword (second args)))]
      (when-not (seq namespaces)
        (throw (ex-info "No test namespaces were found" {:kind :no-tests})))
      (binding [*test-tier* tier]
        (doseq [test-ns namespaces] (require test-ns))
        (let [{:keys [fail error]} (apply test/run-tests namespaces)]
          (shutdown-agents)
          (System/exit (if (zero? (+ fail error)) 0 1)))))
    (catch Throwable error
      (binding [*out* *err*]
        (println "Test harness failed:" (.getMessage error)))
      (shutdown-agents)
      (System/exit 2))))
