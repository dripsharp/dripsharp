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
   {:key "initializer:org.pkl.core.runtime.RefModule#static@24:10" :expand :body}
   {:key "initializer:org.pkl.core.runtime.MathModule#static@23:10" :expand :body}
   {:key "initializer:org.pkl.core.util.json.JsonEscaper#static@38:10" :expand :body}
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
   {:key "type:org.pkl.core.Pair" :expand :public-api}
   {:key "type:org.pkl.core.Analyzer" :expand :public-api}
   {:key "type:org.pkl.core.Closeables" :expand :public-api}
   {:key "type:org.pkl.core.OutputFormat" :expand :public-api}
   {:key "type:org.pkl.core.PklInfo" :expand :public-api}
   {:key "type:org.pkl.core.RendererException" :expand :public-api}
   {:key "type:org.pkl.core.ValueRenderers" :expand :shell}
   {:key "executable:org.pkl.core.ValueRenderers#pcf(java.io.Writer,java.lang.String,boolean,boolean)"
    :expand :body}
   {:key "executable:org.pkl.core.ValueRenderers#json(java.io.Writer,java.lang.String,boolean)"
    :expand :body}
   {:key "executable:org.pkl.core.ValueRenderers#plist(java.io.Writer,java.lang.String)"
    :expand :body}
   {:key "executable:org.pkl.core.ValueRenderers#properties(java.io.Writer,boolean,boolean)"
    :expand :body}
   {:key "type:org.pkl.core.ast.expression.primary.GetEnclosingOwnerNode" :expand :public-api}
   {:key "type:org.pkl.core.ast.expression.unary.ReadOrNullStdLibNode" :expand :public-api}
   {:key "executable:org.pkl.core.ast.expression.unary.ReadOrNullStdLibNode#<init>(com.oracle.truffle.api.source.SourceSection,org.pkl.core.module.ModuleKey)"
    :expand :body}
   {:key "type:org.pkl.core.ast.expression.unary.ReadOrNullStdLibNodeGen" :expand :public-api}
   {:key "executable:org.pkl.core.evaluatorSettings.Color#hasColor()" :expand :body}
   {:key "type:org.pkl.core.evaluatorSettings.PklEvaluatorSettings" :expand :public-api}
   {:key "type:org.pkl.core.project.Package" :expand :public-api}
   {:key "type:org.pkl.core.project.Project" :expand :public-api}
   {:key "type:org.pkl.core.project.ProjectDependenciesResolver" :expand :public-api}
   {:key "type:org.pkl.core.project.ProjectPackager" :expand :public-api}
   {:key "type:org.pkl.core.settings.PklSettings" :expand :public-api}
   {:key "type:org.pkl.core.runtime.VmFileDetector" :expand :public-api}
   {:key "type:org.pkl.core.stdlib.test.report.TestReporter" :expand :public-api}
   {:key "type:org.pkl.core.stdlib.test.report.BaseReporter" :expand :public-api}
   {:key "type:org.pkl.core.stdlib.test.report.JUnitReporter" :expand :public-api}
   {:key "type:org.pkl.core.stdlib.test.report.MinimalReporter" :expand :public-api}
   {:key "type:org.pkl.core.stdlib.test.report.SpecReporter" :expand :public-api}
   {:key "type:org.pkl.core.util.CodeGeneratorUtils" :expand :public-api}])

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
              :executable-references 30743
              :seeds 57
              :constructor-references 4686
              :shadow-symbols 0
              :public-api-declarations 7398
              :type-references 457953
              :fallback-symbols 0
              :guessed-symbols 0
              :source-inputs 633
              :intrinsic-occurrences 92434
              :dependency-occurrences 57641
              :type-parameter-occurrences 4812
              :annotations 10817
              :jdk-occurrences 104588
              :unresolved-symbols 0
              :declarations 16659
              :project-occurrences 261570
              :symbols 16330
              :field-references 16846}
             (:totals first)))
      (is (= "5eb727eb09907b147a86484145250cb8c58ea6fde51e3fd3a3b08dbe10c6f6de"
             (sha-256 declaration-keys)))
      (is (= "4d503bd65b81caf786dbf0c83f8bf321e741637bef1076c6350913ef4ea506ff"
             (sha-256 source-paths)))
      (is (= "a50f4b0f7ca3be1e51f8705302f1f4f38468cdbfa139ae98c0e65c80357239a0"
             (sha-256 public-api-keys)))
      (is (= discovery-sources (set (:compilation-units (:frontend first)))))
      (is (every? discovery-sources (keys (:source-inputs first))))
      (is (every? #(instance? CtElement (:declaration %))
                  (vals (:declarations first))))
      (is (= 633 (count project-roots)))
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
    (is (= "25dffa0419b22b318e209583c6336f5cb9a9555844369031afbf94b94b4ba3f4"
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
