(ns vibeformer.spoon-core-closure-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.complete-core-closure-fixture :as fixture]
            [vibeformer.java-translate :as java]
            [vibeformer.paths :as paths]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Path]
           [java.security MessageDigest]
           [spoon.reflect.declaration CtElement CtType]))

(defn- sha-256
  [values]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (str/join "\n" values) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 255)) bytes))))

(defn- relative-to-upstream
  [root value]
  (let [^Path upstream (-> (paths/resolve-path root "research" "pkl")
                           .toAbsolutePath)
        ^Path path (-> value paths/path .toAbsolutePath)]
    (str (.relativize upstream path))))

(defn- stable-occurrences
  [root closure]
  (mapv (fn [occurrence]
          (-> (select-keys occurrence [:kind :key :origin :resolution :location])
              (update-in [:location :file] #(relative-to-upstream root %))))
        (:occurrences closure)))

(deftest complete-pkl-core-production-frontend-is-loaded
  (let [{:keys [discovery frontend status-before status-after]}
        (fixture/models)
        sources (map str (:java-sources discovery))]
    (is (= ":pkl-core" (:gradle-project discovery)))
    (is (= 723 (count sources)))
    (is (= 140 (count (filter #(str/includes? % "/generated/truffle/") sources))))
    (is (= 28 (count (:resources discovery))))
    (is (= 13 (count (:classpath discovery))))
    (is (= {:compilation-units 723 :project-types 2250}
           (:totals frontend)))
    (is (= {:canonical-computations 723
            :cached-source-identities 723}
           (select-keys (spoon/source-location-cache-stats frontend)
                        [:canonical-computations :cached-source-identities])))
    (is (< 723 (:canonical-requests
                (spoon/source-location-cache-stats frontend))))
    (is (false? (-> frontend :launcher .getEnvironment .getNoClasspath)))
    (is (zero? (-> frontend :launcher .getEnvironment .getErrorCount)))
    (is (= status-before status-after))))

(deftest evaluator-value-model-seeds-and-closure-are-exact
  (let [{:keys [root configuration discovery surface first]} (fixture/models)
        source-paths (map #(relative-to-upstream root %)
                          (keys (:source-inputs first)))
        declaration-keys (keys (:declarations first))
        public-api-keys (keys (:public-api-declarations first))
        project-roots (java/project-roots first)
        discovery-sources (set (map #(str (.toFile ^Path %))
                                    (:java-sources discovery)))]
    (testing "the contract-derived entry paths are exact live declaration identities"
      (is (= 1200 (count (:required-rows surface))))
      (is (= 1200 (count (:selection-evidence surface))))
      (is (= 182 (count (:seeds configuration))))
      (is (= (sort-by :key (:seeds configuration))
             (mapv #(select-keys % [:key :expand :members]) (:seeds first))))
      (is (every? #(instance? CtElement (:declaration %)) (:seeds first))))

    (testing "the recursively resolved project declaration and source sets are exact"
      (is (= {:seeds 182 :declarations 17305 :source-inputs 657
              :public-api-declarations 7796 :shadow-symbols 0
              :unresolved-symbols 0 :ambiguous-symbols 0
              :fallback-symbols 0 :guessed-symbols 0}
             (select-keys (:totals first)
                          [:seeds :declarations :source-inputs
                           :public-api-declarations :shadow-symbols
                           :unresolved-symbols :ambiguous-symbols
                           :fallback-symbols :guessed-symbols])))
      (is (= "db3964fd60f39b34b354c3b7de7f539a52d24262f95b794360c0c5d6b09c1699"
             (sha-256 declaration-keys)))
      (is (= "d7e0f8f71a71a88392d290867b50459f4737307ed1c6de2882bc6241a6332cf0"
             (sha-256 source-paths)))
      (is (= "7356b7785001c556b9e7aa5b61b8c73f7343fbe9b5451e07cec4bd6ce2f9cfe6"
             (sha-256 public-api-keys)))
      (is (= discovery-sources (set (:compilation-units (:frontend first)))))
      (is (every? discovery-sources (keys (:source-inputs first))))
      (is (every? #(instance? CtElement (:declaration %))
                  (vals (:declarations first))))
      (is (= 657 (count project-roots)))
      (is (every? #(.isTopLevel ^CtType %) project-roots))
      (is (every? #(contains? (:declarations first)
                              (str "type:" (.getQualifiedName ^CtType %)))
                  project-roots)))

    (testing "public value contracts and exported representations are retained"
      (is (every? #(contains? (:public-api-declarations first) %)
                  ["type:org.pkl.core.Value"
                   "type:org.pkl.core.ValueVisitor"
                   "type:org.pkl.core.ValueConverter"
                   "type:org.pkl.core.PModule"
                   "type:org.pkl.core.PObject"
                   "type:org.pkl.core.PNull"
                   "type:org.pkl.core.Duration"
                   "type:org.pkl.core.DataSize"
                   "type:org.pkl.core.Pair"
                   "type:org.pkl.core.EvaluatorBuilder"
                   "type:org.pkl.core.ModuleSource"
                   "type:org.pkl.core.SecurityManager"
                   "type:org.pkl.core.SecurityManagerBuilder"
                   "type:org.pkl.core.SecurityManagers"
                   "executable:org.pkl.core.Duration#getValue()"
                   "executable:org.pkl.core.DataSize#getValue()"
                   "executable:org.pkl.core.Pair#getFirst()"
                   "executable:org.pkl.core.EvaluatorBuilder#setColor(boolean)"
                   "executable:org.pkl.core.ModuleSource#text(java.lang.String)"
                   "executable:org.pkl.core.SecurityManagers#standardBuilder()"
                   "field:org.pkl.core.OutputFormat#JSON"])))))

(deftest closure-occurrences-are-resolved-located-and-deterministic
  (let [{:keys [root surface second-surface first second]} (fixture/models)
        first-occurrences (stable-occurrences root first)
        second-occurrences (stable-occurrences root second)
        declarations (:declarations first)
        source-inputs (:source-inputs first)]
    (is (= (:totals first) (:totals second)))
    (is (= (keys declarations) (keys (:declarations second))))
    (is (= (keys source-inputs) (keys (:source-inputs second))))
    (is (= (keys (:public-api-declarations first))
           (keys (:public-api-declarations second))))
    (is (= (mapv #(select-keys % [:declaration-key :generated-declaration-key
                                  :representation])
                 (:selection-evidence surface))
           (mapv #(select-keys % [:declaration-key :generated-declaration-key
                                  :representation])
                 (:selection-evidence second-surface))))
    (is (= first-occurrences second-occurrences))
    (is (= "7895cff7ee6724dfe52a6336713f60b754476572a1f1e4c9dc700dcf137c617e"
           (sha-256 (map pr-str first-occurrences))))
    (is (every? #(and (string? (:key %))
                      (not (str/blank? (:key %)))
                      (pos? (get-in % [:location :line]))
                      (pos? (get-in % [:location :column]))
                      (contains? source-inputs (get-in % [:location :file])))
                (:occurrences first)))
    (is (every? #(or (not= :project (:origin %))
                     (contains? declarations
                                (spoon/declaration-key (:declaration %))))
                (:occurrences first)))))

(deftest missing-closure-seeds-fail-closed
  (let [{:keys [frontend]} (fixture/models)
        error (try
                (spoon/select-resolved-closure!
                 frontend [{:key "type:org.pkl.core.DoesNotExist"
                            :expand :public-api}])
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :missing-project-declaration (:kind (ex-data error))))
    (is (= :closure-seed (:context (ex-data error))))))
