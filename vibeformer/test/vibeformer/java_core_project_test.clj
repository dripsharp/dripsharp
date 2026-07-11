(ns vibeformer.java-core-project-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.complete-core-closure-fixture :as fixture]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.java-project :as java-project]
            [vibeformer.paths :as paths])
  (:import [java.nio.file FileVisitOption Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-java-core-project"
                             (make-array FileAttribute 0)))

(defn- directory-bytes [^Path root]
  (with-open [files (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (map (fn [^Path file]
                [(str (.relativize root file)) (vec (Files/readAllBytes file))]))
         (into (sorted-map)))))

(defn- emit! [target resolved-model worker-count]
  (let [{:keys [root discovery]} (fixture/models)]
    (concurrency/call-with-executor
     {:worker-count worker-count}
     #(java-project/emit-project!
       {:workspace-root root
        :target target
        :discovery discovery
        :resolved-model resolved-model
        :configuration
        (java-project/read-configuration
         root "vibeformer/config/pkl-core-value-model-destination.edn")}))))

(deftest complete-core-value-model-emission-is-zero-failure-and-stable
  (let [{:keys [first second]} (fixture/models)
        first-emission (emit! (temp-directory) first 4)
        second-emission (emit! (temp-directory) second 4)
        summary (:summary first-emission)
        project-root (:project-root first-emission)
        manifest (edn/read-string (slurp (str (:manifest-file first-emission))))
        diagnostics (:diagnostics first-emission)]
    (testing "the entire selected declaration and body closure is accounted for"
      (is (= 603 (:compilation-units summary)))
      (is (= 605 (:generated-files summary)))
      (is (= 28 (:resources summary)))
      (is (= 0 (:skipped-source-units summary)))
      (is (= 0 (:collisions summary)))
      (is (= 0 (:missing-source-mappings summary)))
      (is (= 29080 (:declarations summary)))
      (is (= {:constructor 1123
              :enum-value 77
              :field 3424
              :method 8664
              :parameter 13418
              :record-component 131
              :type 2098
              :type-parameter 145}
             (:declaration-kinds summary)))
      (is (= 603 (count (:sources manifest))))
      (is (= 28 (count (:resources manifest))))
      (is (empty? diagnostics)))

    (testing "every executable root has accepted recursive Spoon coverage"
      (is (= 10387 (:executable-roots summary)))
      (is (= 0 (:hard-failures summary)))
      (is (= {:semantic 424735
              :fallback 0
              :visited 984360
              :missing-mappings 0
              :unsupported-elements 0
              :missing-occurrences 0
              :structural 559625
              :blocked 0
              :covered 984360}
             (:executable-coverage summary)))
      (let [sources (->> (:artifacts manifest)
                         (filter #(nil? (:strategy %)))
                         (map :file)
                         (filter #(str/ends-with? % ".cs"))
                         (map #(slurp (str (paths/resolve-path project-root %)))))]
        (is (not-any? #(re-find #"#error VIBEFORMER_|NotImplementedException" %)
                      sources))))

    (testing "two independent closures emit byte-for-byte identical projects"
      (is (= (directory-bytes (:project-root first-emission))
             (directory-bytes (:project-root second-emission)))))))
