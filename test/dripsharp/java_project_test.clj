(ns dripsharp.java-project-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.complete-parser-fixture :as fixture]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.java-project :as project-emission]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.java-project :as pkl-project])
  (:import [java.nio.file FileVisitOption Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-java-project" (make-array FileAttribute 0)))

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
     #(project-emission/emit-project!
       {:workspace-root root
        :target target
        :project-input discovery
        :resolved-model first
        :configuration (pkl-project/read-configuration root)
        :rule-bundle (pkl-project/rule-bundle)}))))

(deftest destination-package-metadata-must-be-non-blank
  (let [configuration (pkl-project/read-configuration (paths/workspace-root))
        validate!
        (get-in (pkl-project/rule-bundle)
                [:rules :project-policy :validate-configuration!])
        error (try
                (validate! (assoc-in configuration [:package :title] " \t"))
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-destination-configuration (:kind (ex-data error))))
    (is (= :package (:section (ex-data error))))
    (is (= :title (:setting (ex-data error))))))

(deftest complete-parser-declarations-and-project-are-zero-skip-and-stable
  (let [first-emission (emit! (temp-directory) 1)
        second-emission (emit! (temp-directory) 4)
        first-root (:project-root first-emission)
        second-root (:project-root second-emission)
        summary (:summary first-emission)
        first-profile (:emission-profile first-emission)
        second-profile (:emission-profile second-emission)
        manifest (edn/read-string (slurp (str (:manifest-file first-emission))))]
    (is (= :pkl (:rule-bundle first-emission)
           (:rule-bundle second-emission)
           (:rule-bundle manifest)))
    (testing "the dominant root is partitioned across the bounded worker pool"
      (is (= {:name "org.pkl.parser.ParserImpl"
              :weight 29708
              :member-count 82}
             (:largest-root first-profile)
             (:largest-root second-profile)))
      (is (= 82 (get-in first-profile [:dominant-root :member-count])))
      (is (= 29699 (get-in first-profile [:dominant-root :member-weight])))
      (is (= 3445 (get-in first-profile [:dominant-root :largest-member-weight])))
      (is (= 1 (get-in first-profile [:dominant-root :worker-participation])))
      (is (< 1 (get-in second-profile [:dominant-root :worker-participation])))
      (is (every? #(str/starts-with? % "dripsharp-worker-")
                  (get-in second-profile [:dominant-root :worker-threads])))
      (is (= (dissoc (:dominant-root first-profile)
                     :worker-threads :worker-participation :elapsed-millis)
             (dissoc (:dominant-root second-profile)
                     :worker-threads :worker-participation :elapsed-millis))))

    (testing "all production inputs and source declarations are accounted for"
      (is (= 50 (:compilation-units summary)))
      (is (= 49 (:generated-files summary)))
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
      (is (= 984 (:executable-roots summary)))
      (is (= 0 (:hard-failures summary)))
      (is (empty? (:diagnostics first-emission)))
      (is (= {:semantic 38963
              :fallback 0
              :visited 89321
              :missing-mappings 0
              :unsupported-elements 0
              :missing-occurrences 0
              :structural 50358
              :blocked 0
              :covered 89321}
             (:executable-coverage summary)))
      (let [sources (map #(slurp (str (paths/resolve-path first-root (:file %))))
                         (:artifacts first-emission))]
        (is (not-any? #(re-find #"#error DRIPSHARP_|NotImplementedException|TODO" %)
                      sources))
        (is (some #(str/includes? %
                                  "GenericParserError(string msg, global::Pkl.Parser.Syntax.Generic.FullSpan span) : base(msg)")
                  sources))
        (is (some #(str/includes? % "global::DripSharp.Runtime.JavaCompat.CodePointAt")
                  sources))
        (is (some #(str/includes? % "global::DripSharp.Runtime.JavaCompat.SubList")
                  sources))
        (is (some #(str/includes? %
                                  "JavaCompat.CastList<global::Pkl.Parser.Syntax.Identifier>(base.children)")
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
        (is (some #(str/includes? % "if (!base.Equals(o))") sources))
        (is (not-any? #(str/includes? % "JavaCompat.Equals(base,") sources))
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
                                       "src/DripSharp/Runtime/JavaCompat.cs")
            helper-source (paths/resolve-path (paths/workspace-root)
                                              "runtime/DripSharp.JavaCompat.cs")
            unicode-helper (paths/resolve-path first-root
                                               "src/DripSharp/Runtime/JavaRegexUnicodeData.cs")
            unicode-helper-source
            (paths/resolve-path (paths/workspace-root)
                                "runtime/DripSharp.JavaRegexUnicodeData.cs")
            upstream
            (first (:production-resources (:discovery (fixture/models))))]
        (is (str/includes? project "<TargetFramework>net8.0</TargetFramework>"))
        (is (str/includes? project "<Nullable>enable</Nullable>"))
        (is (str/includes?
             project
             "<DefineConstants>$(DefineConstants);DRIPSHARP_INTERNAL_JAVA_COMPAT</DefineConstants>"))
        (is (str/includes? project "<Authors>Vibeformer</Authors>"))
        (is (str/includes? project "<Title>Pkl parser for .NET</Title>"))
        (is (str/includes? project "<PackageTags>pkl parser dotnet dripsharp</PackageTags>"))
        (is (str/includes? project
                           "<PackageProjectUrl>https://github.com/isaksky/pkl-net</PackageProjectUrl>"))
        (is (str/includes? project
                           "<RepositoryUrl>https://github.com/isaksky/pkl-net.git</RepositoryUrl>"))
        (is (str/includes? project "<RepositoryType>git</RepositoryType>"))
        (is (str/includes? project
                           "LogicalName=\"org.pkl.parser.errorMessages.properties\""))
        (is (= (vec (Files/readAllBytes ^Path upstream))
               (vec (Files/readAllBytes resource))))
        (is (= (vec (Files/readAllBytes helper-source))
               (vec (Files/readAllBytes helper))))
        (is (= (vec (Files/readAllBytes unicode-helper-source))
               (vec (Files/readAllBytes unicode-helper))))))

    (testing "two clean emissions are byte-for-byte identical"
      (is (= (directory-bytes first-root) (directory-bytes second-root))))))
