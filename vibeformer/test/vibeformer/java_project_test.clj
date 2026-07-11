(ns vibeformer.java-project-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.complete-parser-fixture :as fixture]
            [vibeformer.concurrency :as concurrency]
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

(defn- emit! [target worker-count]
  (let [{:keys [root discovery first]} (fixture/models)]
    (concurrency/call-with-executor
     {:worker-count worker-count}
     #(java-project/emit-project!
       {:workspace-root root
        :target target
        :discovery discovery
        :resolved-model first
        :configuration (java-project/read-configuration root)}))))

(deftest complete-parser-declarations-and-project-are-zero-skip-and-stable
  (let [first-emission (emit! (temp-directory) 1)
        second-emission (emit! (temp-directory) 4)
        first-root (:project-root first-emission)
        second-root (:project-root second-emission)
        summary (:summary first-emission)
        manifest (edn/read-string (slurp (str (:manifest-file first-emission))))]
    (testing "all production inputs and source declarations are accounted for"
      (is (= 50 (:compilation-units summary)))
      (is (= 48 (:generated-files summary)))
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

    (testing "all executable roots pass accepted recursive coverage"
      (is (= 983 (:executable-roots summary)))
      (is (= 0 (:hard-failures summary)))
      (is (empty? (:diagnostics first-emission)))
      (is (= {:semantic 38938
              :fallback 0
              :visited 89271
              :missing-mappings 0
              :unsupported-elements 0
              :missing-occurrences 0
              :structural 50333
              :blocked 0
              :covered 89271}
             (:executable-coverage summary)))
      (let [sources (map #(slurp (str (paths/resolve-path first-root (:file %))))
                         (:artifacts first-emission))]
        (is (not-any? #(re-find #"#error VIBEFORMER_|NotImplementedException|TODO" %)
                      sources))
        (is (some #(str/includes? %
                                 "GenericParserError(string msg, global::Pkl.Parser.Syntax.Generic.FullSpan span) : base(msg)")
                  sources))
        (is (some #(str/includes? % "global::Vibeformer.Runtime.JavaCompat.CodePointAt")
                  sources))
        (is (some #(str/includes? % "global::Vibeformer.Runtime.JavaCompat.SubList")
                  sources))
        (is (some #(str/includes? %
                                 "public abstract T Accept<T>(global::Pkl.Parser.ParserVisitor<T> visitor);")
                  sources))
        (is (some #(str/includes? % "public virtual string Text(char[] source)")
                  sources))
        (is (some #(str/includes? %
                                 "public override global::System.Collections.Generic.IList<global::Pkl.Parser.Syntax.StringPart> GetParts()")
                  sources))
        (is (some #(str/includes? % "public override string ToString()")
                  sources))
        (is (some #(re-find #"case var __case_\d+_\d+_\d+ when __case_\d+_\d+_\d+ == '=':"
                            %)
                  sources))
        (is (some #(re-find #"when global::System.Object.Equals\(__case_" %)
                  sources))
        (doseq [resolved-family
                ["JavaCompat.ArrayCopy"             ; arrays
                 "JavaCompat.DequePush"             ; deque mutation/order
                 "JavaCompat.ListOf"                ; immutable list factory
                 "JavaCompat.Equals"                ; Objects/list structural equality
                 "JavaCompat.DeepEquals"            ; Objects/deep array equality
                 "JavaCompat.IsUnicodeIdentifierStart" ; Character/code points
                 "JavaCompat.Map"                   ; streams
                 "JavaCompat.Collect"               ; collectors
                 "JavaCompat.GetResourceBundle"     ; ResourceBundle
                 "JavaMessageFormat"                ; MessageFormat
                 "CultureInfo.CurrentCulture"       ; Locale
                 "parser()"]]                       ; Supplier.get
          (is (some #(str/includes? % resolved-family) sources)
              (str "missing resolved mapping family " resolved-family)))))

    (testing "the project and resource strategy come only from explicit configuration"
      (let [project (slurp (str (:project-file first-emission)))
            resource (paths/resolve-path first-root
                                         "resources/org/pkl/parser/errorMessages.properties")
            helper (paths/resolve-path first-root
                                       "src/Vibeformer/Runtime/JavaCompat.cs")
            helper-source (paths/resolve-path (paths/workspace-root)
                                              "vibeformer/runtime/Vibeformer.JavaCompat.cs")
            upstream (first (:resources (:discovery (fixture/models))))]
        (is (str/includes? project "<TargetFramework>net8.0</TargetFramework>"))
        (is (str/includes? project "<Nullable>enable</Nullable>"))
        (is (str/includes? project "<Authors>Vibeformer</Authors>"))
        (is (str/includes? project "<PackageTags>pkl parser vibeformer</PackageTags>"))
        (is (str/includes? project
                           "LogicalName=\"org.pkl.parser.errorMessages.properties\""))
        (is (= (vec (Files/readAllBytes ^Path upstream))
               (vec (Files/readAllBytes resource))))
        (is (= (vec (Files/readAllBytes helper-source))
               (vec (Files/readAllBytes helper))))))

    (testing "two clean emissions are byte-for-byte identical"
      (is (= (directory-bytes first-root) (directory-bytes second-root))))))
