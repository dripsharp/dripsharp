(ns vibeformer.test-runner
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [vibeformer.paths :as paths])
  (:import [java.nio.file FileVisitOption Files Path]))

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
  (if (empty? args)
    (all-test-namespaces)
    (loop [remaining args selected []]
      (if (empty? remaining)
        selected
        (if (and (= "--namespace" (first remaining)) (second remaining))
          (recur (nnext remaining) (conj selected (symbol (second remaining))))
          (throw (ex-info
                  "Usage: clojure -M:test [--namespace namespace]..."
                  {:kind :invalid-test-arguments :arguments args})))))))

(defn -main
  [& args]
  (try
    (let [namespaces (selected-namespaces args)]
      (when-not (seq namespaces)
        (throw (ex-info "No test namespaces were found" {:kind :no-tests})))
      (doseq [test-ns namespaces] (require test-ns))
      (let [{:keys [fail error]} (apply test/run-tests namespaces)]
        (shutdown-agents)
        (System/exit (if (zero? (+ fail error)) 0 1))))
    (catch Throwable error
      (binding [*out* *err*]
        (println "Test harness failed:" (.getMessage error)))
      (shutdown-agents)
      (System/exit 2))))
