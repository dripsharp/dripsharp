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
        first-emission (emit! (temp-directory) first 1)
        second-emission (emit! (temp-directory) second 4)
        summary (:summary first-emission)
        first-profile (:emission-profile first-emission)
        second-profile (:emission-profile second-emission)
        project-root (:project-root first-emission)
        manifest (edn/read-string (slurp (str (:manifest-file first-emission))))
        diagnostics (:diagnostics first-emission)]
    (testing "the dominant core root is split deterministically across workers"
      (is (= {:name "org.pkl.core.stdlib.base.ListNodesFactory"
              :weight 72524
              :member-count 96}
             (:largest-root first-profile)
             (:largest-root second-profile)))
      (is (some? (:dominant-root first-profile)))
      (is (= {:name "org.pkl.core.stdlib.base.ListNodesFactory"
              :weight 72524
              :member-count 95
              :member-weight 72486
              :largest-member-weight 1688}
             (dissoc (:dominant-root first-profile)
                     :worker-threads :worker-participation :elapsed-millis)))
      (is (= 1 (get-in first-profile [:dominant-root :worker-participation])))
      (is (< 1 (get-in second-profile [:dominant-root :worker-participation])))
      (is (= (dissoc (:dominant-root first-profile)
                     :worker-threads :worker-participation :elapsed-millis)
             (dissoc (:dominant-root second-profile)
                     :worker-threads :worker-participation :elapsed-millis))))

    (testing "the entire selected declaration and body closure is accounted for"
      (is (= 603 (:compilation-units summary)))
      (is (= 605 (:generated-files summary)))
      (is (= 28 (:resources summary)))
      (is (= 0 (:skipped-source-units summary)))
      (is (= 0 (:collisions summary)))
      (is (= 0 (:missing-source-mappings summary)))
      (is (= 29094 (:declarations summary)))
      (is (= {:constructor 1123
              :enum-value 77
              :field 3427
              :initializer 7
              :method 8666
              :parameter 13420
              :record-component 131
              :type 2098
              :type-parameter 145}
             (:declaration-kinds summary)))
      (is (= 603 (count (:sources manifest))))
      (is (= 28 (count (:resources manifest))))
      (is (empty? diagnostics)))

    (testing "every executable root has accepted recursive Spoon coverage"
      (is (= 10399 (:executable-roots summary)))
      (is (= 0 (:hard-failures summary)))
      (is (= {:semantic 425501
              :fallback 0
              :visited 986121
              :missing-mappings 0
              :unsupported-elements 0
              :missing-occurrences 0
              :structural 560620
              :blocked 0
              :covered 986121}
             (:executable-coverage summary)))
      (let [sources (->> (:artifacts manifest)
                         (filter #(nil? (:strategy %)))
                         (map :file)
                         (filter #(str/ends-with? % ".cs"))
                         (map #(slurp (str (paths/resolve-path project-root %)))))]
        (is (not-any? #(re-find #"#error VIBEFORMER_|NotImplementedException" %)
                      sources))
        (is (some #(str/includes? % "unchecked((sbyte)(") sources))
        (is (some #(str/includes? % "JavaCompat.OrganicPut") sources))
        (is (some #(str/includes? % "((string[])JsonEscaper.REPLACEMENTS.Clone())")
                  sources))
        (is (some #(str/includes? %
                                 "LoadModule(global::Vibeformer.Runtime.JavaCompat.CreateUri(\"pkl:math\")")
                  sources))))

    (testing "two independent closures emit byte-for-byte identical projects"
      (is (= (directory-bytes (:project-root first-emission))
             (directory-bytes (:project-root second-emission)))))))
