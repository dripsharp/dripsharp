(ns dripsharp.spoon-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.complete-parser-fixture :as fixture]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.paths :as paths]
            [dripsharp.project-input :as project-input]
            [dripsharp.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [spoon.reflect.declaration CtElement]))

(def ^:private vestigial-model-counters
  #{:shadow-symbols :unresolved-symbols :ambiguous-symbols :fallback-symbols
    :guessed-symbols})

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
            :symbols 1145}
           (select-keys (:totals first-model)
                        [:compilation-units :project-types :type-references
                         :executable-references :constructor-references
                         :field-references :annotations :symbols])))
    (is (not-any? #(contains? (:totals first-model) %)
                  vestigial-model-counters))
    (is (= (count (:occurrences first-model))
           (reduce
            +
            (map (:totals first-model)
                 [:project-occurrences :jdk-occurrences
                  :dependency-occurrences :intrinsic-occurrences
                  :type-parameter-occurrences]))))

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

(deftest summary-lines-render-observed-occurrence-origins
  (let [complete
        (spoon/map->ResolvedJavaModel
         {:totals
          {:compilation-units 2
           :project-types 3
           :type-references 4
           :executable-references 5
           :constructor-references 6
           :field-references 7
           :annotations 8
           :symbols 9
           :project-occurrences 10
           :jdk-occurrences 11
           :dependency-occurrences 12
           :intrinsic-occurrences 13
           :type-parameter-occurrences 14}})
        closure
        (spoon/map->ResolvedJavaClosure
         {:totals
          {:declarations 2
           :source-inputs 3
           :type-references 4
           :executable-references 5
           :constructor-references 6
           :field-references 7
           :annotations 8
           :symbols 9
           :project-occurrences 10
           :jdk-occurrences 11
           :dependency-occurrences 12
           :intrinsic-occurrences 13
           :type-parameter-occurrences 14}})
        complete-summary (spoon/summary-line complete)
        closure-summary (spoon/summary-line closure)]
    (is (= (str "2 units, 3 project types, 4 type uses, 5 calls, "
                "6 constructors, 7 fields, 8 annotations, 9 stable symbols; "
                "origins: project=10 jdk=11 dependency=12 intrinsic=13 "
                "type-parameter=14")
           complete-summary))
    (is (= (str "2 selected declarations in 3 source inputs, 4 type uses, "
                "5 calls, 6 constructors, 7 fields, 8 annotations, "
                "9 stable symbols; origins: project=10 jdk=11 dependency=12 "
                "intrinsic=13 type-parameter=14")
           closure-summary))
    (is (not-any? #(str/includes? complete-summary %)
                  ["shadow=" "unresolved=" "ambiguous=" "fallback="]))))

(deftest complete-parser-resolution-is-deterministic
  (let [{first-model :first second-model :second} (fixture/models)]
    (is (= (:totals first-model) (:totals second-model)))
    (is (= (keys (:symbols first-model)) (keys (:symbols second-model))))
    (is (= (mapv #(select-keys % [:kind :key :origin :resolution :location])
                 (:occurrences first-model))
           (mapv #(select-keys % [:kind :key :origin :resolution :location])
                 (:occurrences second-model))))))

(deftest inherited-runtime-interface-fields-use-the-declaring-symbol
  (let [root (Files/createTempDirectory "dripsharp-inherited-runtime-field-"
                                        (make-array FileAttribute 0))
        source (paths/resolve-path root "InheritedConstant.java")
        _ (Files/writeString
           source
           (str "import java.awt.image.ColorModel;\n"
                "final class InheritedConstant {\n"
                "  int value() { return ColorModel.OPAQUE; }\n"
                "}\n")
           (make-array OpenOption 0))
        model
        (spoon/build-resolved-model!
         root
         {:schema-version 1
          :project-id "inherited-runtime-field-fixture"
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
          :classpath-artifacts []})
        occurrences
        (get (:symbols model) "field:java.awt.Transparency#OPAQUE")]
    (is (= 1 (count occurrences)))
    (is (= :jdk (:origin (first occurrences))))
    (is (= :runtime-member (:resolution (first occurrences))))))

(deftest lazy-resolution-is-serialized-and-retains-translator-failure-details
  (let [{:keys [discovery] resolved :first} (fixture/models)
        source-files
        (set (map #(-> ^Path % .toFile .getCanonicalPath)
                  (project-input/production-source-files discovery)))
        frontend
        (spoon/map->JavaFrontendModel
         (assoc (select-keys resolved
                             [:launcher :model :compilation-units
                              :project-types :totals])
                :source-files source-files))
        project-type-declaration
        (ns-resolve 'dripsharp.spoon 'project-type-declaration)
        resolve-with-workers
        (fn [worker-count]
          (let [threads (atom #{})
                error
                (try
                  (concurrency/call-with-executor
                   {:worker-count worker-count}
                   #(with-redefs-fn
                      {project-type-declaration
                       (fn [& _]
                         (swap! threads conj (.getName (Thread/currentThread)))
                         (throw (AssertionError. "synthetic resolver defect")))}
                      (fn [] (spoon/resolve-complete-model! frontend))))
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
            {:error error :threads @threads}))
        serial (resolve-with-workers 1)
        parallel (resolve-with-workers 22)
        first-failure (-> serial :error ex-data :failures first)]
    (is (= (ex-data (:error serial)) (ex-data (:error parallel))))
    (is (= 1 (count (:threads serial)) (count (:threads parallel))))
    (is (= (:threads serial) (:threads parallel)))
    (is (= :semantic-resolution-failed
           (:kind (ex-data (:error serial)))))
    (is (= "java.lang.AssertionError" (:exception-class first-failure)))
    (is (seq (:stack-summary first-failure)))
    (is (<= (count (:stack-summary first-failure)) 8))))

(deftest unresolved-symbols-fail-with-frontend-location
  (let [root (Files/createTempDirectory "dripsharp-unresolved"
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
                       "spoon.compiler.ModelBuildingException"))
    (is (str/includes? (:exception-class failure)
                       "spoon.compiler.ModelBuildingException"))
    (is (seq (:stack-summary failure)))
    (is (<= (count (:stack-summary failure)) 8))))

(deftest generated-sources-share-the-project-module-and-retain-source-paths
  (let [root (Files/createTempDirectory "dripsharp-modular-sources"
                                        (make-array FileAttribute 0))
        ordinary-root (paths/resolve-path root "src/main/java")
        generated-root (paths/resolve-path root "target/generated-sources")
        module-info (paths/resolve-path ordinary-root "module-info.java")
        ordinary (paths/resolve-path
                  ordinary-root "example/shared/Ordinary.java")
        generated (paths/resolve-path
                   generated-root "example/shared/Generated.java")
        _ (doseq [file [module-info ordinary generated]]
            (Files/createDirectories (.getParent ^Path file)
                                     (make-array FileAttribute 0)))
        _ (Files/writeString
           module-info
           "module example.modular { exports example.shared; }"
           (make-array OpenOption 0))
        _ (Files/writeString
           ordinary
           (str "package example.shared; "
                "public final class Ordinary { "
                "public Generated value() { return new Generated(); } }")
           (make-array OpenOption 0))
        _ (Files/writeString
           generated
           "package example.shared; public final class Generated { }"
           (make-array OpenOption 0))
        input
        {:schema-version 1
         :project-id "example:modular:1.0.0"
         :source-roots [ordinary-root generated-root]
         :resource-roots []
         :production-sources [module-info ordinary]
         :generated-production-sources [generated]
         :production-resources []
         :java-toolchain
         {:home (paths/absolute (System/getProperty "java.home"))
          :release 17
          :preview-features? false}
         :project-dependencies []
         :external-dependencies []
         :classpath-artifacts []}
        model (spoon/build-resolved-model! root input)
        expected-files
        (set (map #(-> ^Path % .toFile .getCanonicalPath)
                  [module-info ordinary generated]))]
    (is (= 3 (get-in model [:totals :compilation-units])))
    (is (= 2 (get-in model [:totals :project-types])))
    (is (= expected-files (:compilation-units model)))
    (is (= 3 (:source-aliases (spoon/source-location-cache-stats model))))
    (is (= (.getCanonicalPath (.toFile generated))
           (get-in (spoon/source-location
                    (get-in model [:project-types "example.shared.Generated"]))
                   [:file])))))
