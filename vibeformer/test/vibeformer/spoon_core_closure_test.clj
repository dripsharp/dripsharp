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
   {:key "initializer:org.pkl.core.runtime.BaseModule#static@26:10" :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateExpression(org.pkl.core.ModuleSource,java.lang.String)"
    :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateExpressionPklBinary(org.pkl.core.ModuleSource,java.lang.String)"
    :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateOutputValue(org.pkl.core.ModuleSource)"
    :expand :body}
   {:key "executable:org.pkl.core.EvaluatorImpl#evaluateOutputValueAs(org.pkl.core.ModuleSource,org.pkl.core.PClassInfo)"
    :expand :body}
   {:key "initializer:org.pkl.core.Release#static@52:10" :expand :body}
   {:key "initializer:org.pkl.core.module.ModuleKeyFactories$FromServiceProviders#static@255:12"
    :expand :body}
   {:key "initializer:org.pkl.core.resource.ResourceReaders$FromServiceProviders#static@651:12"
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
              :executable-references 29201
              :seeds 26
              :constructor-references 4457
              :shadow-symbols 0
              :public-api-declarations 7134
              :type-references 441227
              :fallback-symbols 0
              :guessed-symbols 0
              :source-inputs 603
              :intrinsic-occurrences 90410
              :dependency-occurrences 57147
              :type-parameter-occurrences 4391
              :annotations 10561
              :jdk-occurrences 97473
              :unresolved-symbols 0
              :declarations 16117
              :project-occurrences 252193
              :symbols 15776
              :field-references 16168}
             (:totals first)))
      (is (= "03b0f4d7dc35f9241bbfb7a8fe4ce58271dff3413d0de030b58ed09ce8ce00e8"
             (sha-256 declaration-keys)))
      (is (= "49b2a9892cce1436adf2d6aadc086fc97501e677bbbd4b222d1b6cef8a50647f"
             (sha-256 source-paths)))
      (is (= "fd434343e2cbc249ed340e1f4c4f09be0e29a4f54e5eb50e4053e0684c8a88ef"
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
    (is (= "e3a912d3b0f7423653ae7254c51c9cdb5dab819e5b00f7360604a96203a020a4"
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
