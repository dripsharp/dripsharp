(ns vibeformer.spoon-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.complete-parser-fixture :as fixture]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.paths :as paths]
            [vibeformer.project-input :as project-input]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtElement]))

(deftest complete-gradle-production-inputs-are-modeled
  (let [{:keys [discovery status-before status-after]
         first-model :first} (fixture/models)
        sources (map str (project-input/production-source-files discovery))
        classpath (map str (project-input/compile-classpath discovery))]
    (is (= 17 (get-in discovery [:java-toolchain :release])))
    (is (false? (get-in discovery [:java-toolchain :preview-features?])))
    (is (= 50 (count sources)))
    (is (every? #(str/includes? % "/pkl-parser/src/main/java/") sources))
    (is (not-any? #(str/includes? % "/src/test/") sources))
    (is (= 1 (count classpath)))
    (is (str/ends-with? (clojure.core/first classpath) "/jspecify-1.0.0.jar"))
    (is (= (set (map #(-> ^Path % .toFile .getCanonicalPath)
                     (project-input/production-source-files discovery)))
           (:compilation-units first-model)))
    (is (= {:canonical-computations 50
            :cached-source-identities 50}
           (select-keys (spoon/source-location-cache-stats first-model)
                        [:canonical-computations :cached-source-identities])))
    (is (= status-before status-after))))

(deftest source-location-cache-is-shared-by-multicore-callers
  (let [{first-model :first} (fixture/models)
        element (-> first-model :occurrences first :reference)
        expected (spoon/source-location element)
        before (spoon/source-location-cache-stats first-model)
        locations (concurrency/call-with-executor
                   {:worker-count 8}
                   #(concurrency/mapv-ordered
                     :source-location-cache-test
                     (fn [_] (spoon/source-location element))
                     (range 4096)))
        after (spoon/source-location-cache-stats first-model)]
    (is (every? #(= expected %) locations))
    (is (= (:canonical-computations before)
           (:canonical-computations after)))
    (is (= 4096 (- (:canonical-requests after)
                   (:canonical-requests before))))
    (is (= 4096 (- (:source-location-calls after)
                   (:source-location-calls before))))))

(deftest implicit-reference-location-still-uses-closest-positioned-parent
  (let [{first-model :first} (fixture/models)
        implicit (some (fn [{:keys [^CtElement reference]}]
                         (let [position (.getPosition reference)]
                           (when-not (and position (.isValidPosition position))
                             reference)))
                       (:occurrences first-model))
        positioned-parent
        (loop [^CtElement current implicit]
          (when (and current (.isParentInitialized current))
            (let [^CtElement parent (.getParent current)
                  position (.getPosition parent)]
              (if (and position (.isValidPosition position))
                parent
                (recur parent)))))]
    (is (some? implicit))
    (is (some? positioned-parent))
    (is (= (spoon/source-location positioned-parent)
           (spoon/source-location implicit)))))

(deftest complete-parser-resolution-is-exact-and-live
  (let [{first-model :first} (fixture/models)
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
  (let [{first-model :first second-model :second} (fixture/models)]
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
        discovery {:schema-version 1
                   :project-id "unresolved-fixture"
                   :source-roots [root]
                   :resource-roots []
                   :production-sources [source]
                   :generated-production-sources []
                   :production-resources []
                   :java-toolchain
                   {:home (paths/absolute (System/getProperty "java.home"))
                    :release 17
                    :preview-features? false}
                   :project-dependencies []
                   :external-dependencies []
                   :classpath-artifacts []}
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
