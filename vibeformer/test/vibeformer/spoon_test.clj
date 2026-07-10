(ns vibeformer.spoon-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process]
            [vibeformer.project :as project]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtElement]))

(defn- research-status
  [root]
  (:output
   (process/run! {:command ["git" "status" "--porcelain" "--untracked-files=no"]
                  :directory (paths/resolve-path root "research" "pkl")})))

(defn- resolve-complete-parser-twice
  []
  (let [root (paths/workspace-root)
        status-before (research-status root)
        manifest (Files/createTempFile "vibeformer-main-inputs" ".tsv"
                                       (make-array java.nio.file.attribute.FileAttribute 0))
        submodule (project/verify-submodule! {:workspace-root root})
        discovery (project/discover-main! {:workspace-root root :manifest manifest})
        first-model (spoon/build-resolved-model! root discovery)
        second-model (spoon/build-resolved-model! root discovery)
        status-after (research-status root)]
    {:root root
     :submodule submodule
     :discovery discovery
     :first first-model
     :second second-model
     :status-before status-before
     :status-after status-after}))

(defonce ^:private complete-parser (delay (resolve-complete-parser-twice)))

(deftest complete-gradle-production-inputs-are-modeled
  (let [{:keys [discovery status-before status-after]
         first-model :first} @complete-parser
        sources (map str (:java-sources discovery))
        classpath (map str (:classpath discovery))]
    (is (= 17 (:java-release discovery)))
    (is (false? (:preview-features discovery)))
    (is (= 50 (count sources)))
    (is (every? #(str/includes? % "/pkl-parser/src/main/java/") sources))
    (is (not-any? #(str/includes? % "/src/test/") sources))
    (is (= 1 (count classpath)))
    (is (str/ends-with? (clojure.core/first classpath) "/jspecify-1.0.0.jar"))
    (is (= (set (map #(-> ^Path % .toFile .getCanonicalPath)
                    (:java-sources discovery)))
           (:compilation-units first-model)))
    (is (= status-before status-after))))

(deftest complete-parser-resolution-is-exact-and-live
  (let [{first-model :first} @complete-parser
        symbols (:symbols first-model)]
    (is (= {:compilation-units 50
            :project-types 114
            :type-references 36174
            :executable-references 2600
            :constructor-references 855
            :field-references 2555
            :annotations 372
            :symbols 1145
            :shadow-symbols 0
            :unresolved-symbols 0
            :ambiguous-symbols 0
            :fallback-symbols 0}
           (select-keys (:totals first-model)
                        [:compilation-units :project-types :type-references
                         :executable-references :constructor-references
                         :field-references :annotations :symbols
                         :shadow-symbols :unresolved-symbols
                         :ambiguous-symbols :fallback-symbols])))

    (testing "project-local nested, generic, overload, constructor, and field identities"
      (let [type-roles (->> (:occurrences first-model)
                            (filter #(= :type (:kind %)))
                            (map #(str (.getRoleInParent ^CtElement (:reference %))))
                            set)]
        (is (every? type-roles
                    ["superType" "interface" "boundingType" "typeArgument"])))
      (is (contains? symbols "type:org.pkl.parser.syntax.Expr$IfExpr"))
      (is (contains? symbols
                     "type-parameter:org.pkl.parser.BaseParserVisitor#T"))
      (is (every? #(contains? symbols %)
                  ["executable:org.pkl.parser.GenericParserImpl#parseExpr()"
                   "executable:org.pkl.parser.GenericParserImpl#parseExpr(java.lang.String)"
                   "executable:org.pkl.parser.GenericParserImpl#parseExpr(java.lang.String,int)"]))
      (is (some #(= :record-canonical-constructor (:resolution %))
                (get symbols
                     (str "executable:org.pkl.parser.GenericParserImpl$FullToken"
                          "#<init>(org.pkl.parser.Token,"
                          "org.pkl.parser.syntax.generic.FullSpan,int)"))))
      (is (some #(= :record-component (:resolution %))
                (get symbols
                     "field:org.pkl.parser.GenericParserImpl$FullToken#token"))))

    (testing "resolved dependency and representative JDK identities"
      (is (every? #(= :dependency (:origin %))
                  (get symbols "annotation:org.jspecify.annotations.Nullable")))
      (is (every? #(= :jdk (:origin %))
                  (get symbols "executable:java.lang.String#substring(int,int)")))
      (is (every? #(contains? symbols %)
                  ["executable:java.util.List#of()"
                   "executable:java.util.List#of(java.lang.Object)"
                   "executable:java.util.ArrayList#<init>(java.util.Collection)"
                   "type-parameter:java.util.Collections#unmodifiableList(java.util.List)#T"])))

    (testing "later translation receives frontend objects rather than facts"
      (let [occurrence (clojure.core/first
                        (get symbols "type:org.pkl.parser.syntax.Expr$IfExpr"))]
        (is (instance? CtElement (:reference occurrence)))
        (is (instance? CtElement (:declaration occurrence)))
        (is (pos? (get-in occurrence [:location :line])))))))

(deftest complete-parser-resolution-is-deterministic
  (let [{first-model :first second-model :second} @complete-parser]
    (is (= (:totals first-model) (:totals second-model)))
    (is (= (keys (:symbols first-model)) (keys (:symbols second-model))))
    (is (= (mapv #(select-keys % [:kind :key :origin :resolution :location])
                 (:occurrences first-model))
           (mapv #(select-keys % [:kind :key :origin :resolution :location])
                 (:occurrences second-model))))))

(deftest unresolved-symbols-fail-with-frontend-location
  (let [root (Files/createTempDirectory "vibeformer-unresolved"
                                        (make-array FileAttribute 0))
        source (paths/resolve-path root "Broken.java")
        _ (Files/writeString
           source
           "final class Broken { missing.Dependency value; }"
           (make-array OpenOption 0))
        discovery {:java-home (paths/absolute (System/getProperty "java.home"))
                   :java-release 17
                   :preview-features false
                   :java-sources [source]
                   :resources []
                   :classpath []}
        error (try
                (spoon/build-resolved-model! root discovery)
                nil
                (catch clojure.lang.ExceptionInfo caught caught))
        failure (:failure (ex-data error))]
    (is (= :spoon-model-build-failed (:kind (ex-data error))))
    (is (= :frontend-compilation-failed (:kind failure)))
    (is (= (.getCanonicalPath (.toFile source))
           (get-in failure [:location :file])))
    (is (= 1 (get-in failure [:location :line])))
    (is (str/includes? (get-in failure [:frontend :frontend-class])
                       "spoon.compiler.ModelBuildingException"))))
