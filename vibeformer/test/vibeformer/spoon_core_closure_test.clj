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

(def ^:private expected-seeds
  [{:key "executable:org.pkl.core.EvaluatorImpl#evaluate(org.pkl.core.ModuleSource)"
    :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateExpression(org.pkl.core.ModuleSource,java.lang.String)"
    :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateExpressionPklBinary(org.pkl.core.ModuleSource,java.lang.String)"
    :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateOutputValue(org.pkl.core.ModuleSource)"
    :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateOutputValueAs(org.pkl.core.ModuleSource,org.pkl.core.PClassInfo)"
    :expand :body}
   {:key "executable:org.pkl.core.runtime.VmValue#export()" :expand :body}
   {:key "executable:org.pkl.core.runtime.VmValue#export(java.lang.Object)" :expand :body}
   {:key "executable:org.pkl.core.runtime.VmValue#exportNullable(java.lang.Object)" :expand :body}
   {:key "executable:org.pkl.core.runtime.VmTyped#export()" :expand :body}
   {:key "executable:org.pkl.core.runtime.VmNull#export()" :expand :body}
   {:key "executable:org.pkl.core.runtime.VmDuration#export()" :expand :body}
   {:key "executable:org.pkl.core.runtime.VmDataSize#export()" :expand :body}
   {:key "executable:org.pkl.core.runtime.VmPair#export()" :expand :body}
   {:key "type:org.pkl.core.Value" :expand :public-api}
   {:key "type:org.pkl.core.ValueVisitor" :expand :public-api}
   {:key "type:org.pkl.core.ValueConverter" :expand :public-api}
   {:key "type:org.pkl.core.PModule" :expand :public-api}
   {:key "type:org.pkl.core.PObject" :expand :public-api}
   {:key "type:org.pkl.core.PNull" :expand :public-api}
   {:key "type:org.pkl.core.Duration" :expand :public-api}
   {:key "type:org.pkl.core.DataSize" :expand :public-api}
   {:key "type:org.pkl.core.Pair" :expand :public-api}])

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
    (is (false? (-> frontend :launcher .getEnvironment .getNoClasspath)))
    (is (zero? (-> frontend :launcher .getEnvironment .getErrorCount)))
    (is (= status-before status-after))))

(deftest evaluator-value-model-seeds-and-closure-are-exact
  (let [{:keys [root configuration discovery first]} (fixture/models)
        source-paths (map #(relative-to-upstream root %)
                          (keys (:source-inputs first)))
        declaration-keys (keys (:declarations first))
        public-api-keys (keys (:public-api-declarations first))
        project-roots (java/project-roots first)
        discovery-sources (set (map #(str (.toFile ^Path %))
                                    (:java-sources discovery)))]
    (testing "the bounded entry paths are exact live declaration identities"
      (is (= (sort-by :key expected-seeds)
             (mapv #(select-keys % [:key :expand]) (:seeds first))))
      (is (= (set expected-seeds) (set (:seeds configuration))))
      (is (every? #(instance? CtElement (:declaration %)) (:seeds first))))

    (testing "the recursively resolved project declaration and source sets are exact"
      (is (= {:ambiguous-symbols 0
              :executable-references 29159
              :seeds 22
              :constructor-references 4443
              :shadow-symbols 0
              :public-api-declarations 7132
              :type-references 440716
              :fallback-symbols 0
              :guessed-symbols 0
              :source-inputs 603
              :intrinsic-occurrences 90370
              :dependency-occurrences 57129
              :type-parameter-occurrences 4377
              :annotations 10560
              :jdk-occurrences 97199
              :unresolved-symbols 0
              :declarations 16108
              :project-occurrences 251950
              :symbols 15752
              :field-references 16147}
             (:totals first)))
      (is (= "933fa26a8ad6c3687532f54820afa5fa2a6ee8cba4f13bec05b6d117f049f770"
             (sha-256 declaration-keys)))
      (is (= "49b2a9892cce1436adf2d6aadc086fc97501e677bbbd4b222d1b6cef8a50647f"
             (sha-256 source-paths)))
      (is (= "f5cad9d57d54218b8cedf162948c75f1329034e028610a76b90465271332e3cc"
             (sha-256 public-api-keys)))
      (is (= discovery-sources (set (:compilation-units (:frontend first)))))
      (is (every? discovery-sources (keys (:source-inputs first))))
      (is (every? #(instance? CtElement (:declaration %))
                  (vals (:declarations first))))
      (is (= 603 (count project-roots)))
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
                   "executable:org.pkl.core.Duration#getValue()"
                   "executable:org.pkl.core.DataSize#getValue()"
                   "executable:org.pkl.core.Pair#getFirst()"])))))

(deftest closure-occurrences-are-resolved-located-and-deterministic
  (let [{:keys [root first second]} (fixture/models)
        first-occurrences (stable-occurrences root first)
        second-occurrences (stable-occurrences root second)
        declarations (:declarations first)
        source-inputs (:source-inputs first)]
    (is (= (:totals first) (:totals second)))
    (is (= (keys declarations) (keys (:declarations second))))
    (is (= (keys source-inputs) (keys (:source-inputs second))))
    (is (= (keys (:public-api-declarations first))
           (keys (:public-api-declarations second))))
    (is (= first-occurrences second-occurrences))
    (is (= "1b8be3a09a542c03fa18b949fe48ba555731d72a67e350ec3740b6902ff032fe"
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
