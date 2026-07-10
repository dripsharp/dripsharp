(ns vibeformer.java-project-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.complete-parser-fixture :as fixture]
            [vibeformer.java-project :as java-project]
            [vibeformer.paths :as paths])
  (:import [java.nio.file FileVisitOption Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-java-project" (make-array FileAttribute 0)))

(defn- directory-bytes [^Path root]
  (with-open [files (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (map (fn [^Path file]
                [(str (.relativize root file)) (vec (Files/readAllBytes file))]))
         (into (sorted-map)))))

(defn- emit! [target]
  (let [{:keys [root discovery first]} (fixture/models)]
    (java-project/emit-project!
     {:workspace-root root
      :target target
      :discovery discovery
      :resolved-model first
      :configuration (java-project/read-configuration root)})))

(deftest complete-parser-declarations-and-project-are-zero-skip-and-stable
  (let [first-emission (emit! (temp-directory))
        second-emission (emit! (temp-directory))
        first-root (:project-root first-emission)
        second-root (:project-root second-emission)
        summary (:summary first-emission)
        manifest (edn/read-string (slurp (str (:manifest-file first-emission))))]
    (testing "all production inputs and source declarations are accounted for"
      (is (= 50 (:compilation-units summary)))
      (is (= 47 (:generated-files summary)))
      (is (= 1 (:resources summary)))
      (is (= 0 (:skipped-source-units summary)))
      (is (= 0 (:collisions summary)))
      (is (= 0 (:missing-source-mappings summary)))
      (is (= 1947 (:declarations summary)))
      (is (= {:constructor 97
              :enum-value 274
              :field 79
              :method 675
              :parameter 603
              :record-component 26
              :type 114
              :type-parameter 79}
             (:declaration-kinds summary)))
      (is (= 50 (count (:sources manifest))))
      (is (every? #(contains? #{:generated-csharp :package-nullability-metadata}
                              (:strategy %))
                  (:sources manifest))))

    (testing "unsupported executable semantics remain hard failures"
      (is (= 983 (:hard-failures summary)))
      (is (= {:unsupported-enum-value-initializer 274
              :unsupported-executable-body 691
              :unsupported-field-initializer 18}
             (frequencies (map :kind (:diagnostics first-emission)))))
      (is (every? :blocking? (:diagnostics first-emission)))
      (is (some #(str/includes? % "#error VIBEFORMER_")
                (map #(slurp (str (paths/resolve-path first-root (:file %))))
                     (:artifacts first-emission)))))

    (testing "the project and resource strategy come only from explicit configuration"
      (let [project (slurp (str (:project-file first-emission)))
            resource (paths/resolve-path first-root
                                         "resources/org/pkl/parser/errorMessages.properties")
            upstream (first (:resources (:discovery (fixture/models))))]
        (is (str/includes? project "<TargetFramework>net8.0</TargetFramework>"))
        (is (str/includes? project "<Nullable>enable</Nullable>"))
        (is (str/includes? project
                           "LogicalName=\"org.pkl.parser.errorMessages.properties\""))
        (is (= (vec (Files/readAllBytes ^Path upstream))
               (vec (Files/readAllBytes resource))))))

    (testing "two clean emissions are byte-for-byte identical"
      (is (= (directory-bytes first-root) (directory-bytes second-root))))))
