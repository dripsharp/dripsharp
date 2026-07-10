(ns vibeformer.java-core-project-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.complete-core-closure-fixture :as fixture]
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

(defn- emit! [target resolved-model]
  (let [{:keys [root discovery]} (fixture/models)]
    (java-project/emit-project!
     {:workspace-root root
      :target target
      :discovery discovery
      :resolved-model resolved-model
      :configuration
      (java-project/read-configuration
       root "vibeformer/config/pkl-core-value-model-destination.edn")})))

(deftest complete-core-value-model-emission-is-zero-failure-and-stable
  (let [{:keys [first second]} (fixture/models)
        first-emission (emit! (temp-directory) first)
        second-emission (emit! (temp-directory) second)
        summary (:summary first-emission)
        project-root (:project-root first-emission)
        manifest (edn/read-string (slurp (str (:manifest-file first-emission))))
        diagnostics (:diagnostics first-emission)]
    (testing "the entire selected declaration and body closure is accounted for"
      (is (= 344 (:compilation-units summary)))
      (is (= 345 (:generated-files summary)))
      (is (= 28 (:resources summary)))
      (is (= 0 (:skipped-source-units summary)))
      (is (= 0 (:collisions summary)))
      (is (= 0 (:missing-source-mappings summary)))
      (is (= 8158 (:declarations summary)))
      (is (= {:constructor 695
              :enum-value 41
              :field 1405
              :method 1290
              :parameter 3148
              :record-component 26
              :type 1499
              :type-parameter 54}
             (:declaration-kinds summary)))
      (is (= 344 (count (:sources manifest))))
      (is (= 28 (count (:resources manifest))))
      (is (empty? diagnostics)))

    (testing "every executable root has accepted recursive Spoon coverage"
      (is (= 2155 (:executable-roots summary)))
      (is (= 0 (:hard-failures summary)))
      (is (= {:semantic 64012
              :fallback 0
              :visited 141811
              :missing-mappings 0
              :unsupported-elements 0
              :missing-occurrences 0
              :structural 77799
              :blocked 0
              :covered 141811}
             (:executable-coverage summary)))
      (let [sources (->> (:artifacts manifest)
                         (map :file)
                         (filter #(str/ends-with? % ".cs"))
                         (map #(slurp (str (paths/resolve-path project-root %)))))]
        (is (not-any? #(re-find #"#error VIBEFORMER_|NotImplementedException|TODO" %)
                      sources))))

    (testing "two independent closures emit byte-for-byte identical projects"
      (is (= (directory-bytes (:project-root first-emission))
             (directory-bytes (:project-root second-emission)))))))
