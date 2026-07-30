(ns dripsharp.java-project-test
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.complete-parser-fixture :as fixture]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as project-emission]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.java-project :as pkl-project])
  (:import [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-java-project" (make-array FileAttribute 0)))

(defn- write-file!
  [^Path root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- directory-bytes [^Path root]
  (with-open [files (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (map (fn [^Path file]
                [(str (.relativize root file)) (vec (Files/readAllBytes file))]))
         (into (sorted-map)))))

(def ^:private java-compat-areas
  ["Java.IO" "Java.Lang" "Java.Math" "Java.Net" "Java.Nio"
   "Java.Security" "Java.Text" "Java.Time" "Java.Util"
   "Java.Util.Concurrent" "Java.Util.Regex" "Java.Xml"])

(def ^:private pkl-non-affiliation-disclaimer
  "This package is an independent translation and is not affiliated with, endorsed by, or sponsored by Apple Inc.")

(def ^:private pdfbox-non-affiliation-disclaimer
  "This package is an independent translation and is not affiliated with, endorsed by, or sponsored by the Apache Software Foundation.")

(deftest generated-package-descriptions-carry-target-specific-disclaimers
  (let [workspace (paths/workspace-root)
        contracts
        {"targets/pkl/destinations/parser.edn"
         ["Pkl.Parser" pkl-non-affiliation-disclaimer]
         "targets/pkl/destinations/core.edn"
         ["Pkl.Core" pkl-non-affiliation-disclaimer]
         "targets/pdfcube/destinations/io.edn"
         ["PdfCube.IO" pdfbox-non-affiliation-disclaimer]
         "targets/pdfcube/destinations/fontbox.edn"
         ["PdfCube.FontBox" pdfbox-non-affiliation-disclaimer]
         "targets/pdfcube/destinations/xmpbox.edn"
         ["PdfCube.XmpBox" pdfbox-non-affiliation-disclaimer]
         "targets/pdfcube/destinations/pdfbox.edn"
         ["PdfCube.PdfBox" pdfbox-non-affiliation-disclaimer]
         "targets/pdfcube/destinations/preflight.edn"
         ["PdfCube.Preflight" pdfbox-non-affiliation-disclaimer]}]
    (is (= #{"Pkl.Parser" "Pkl.Core"
             "PdfCube.IO" "PdfCube.FontBox" "PdfCube.XmpBox"
             "PdfCube.PdfBox" "PdfCube.Preflight"}
           (set (map (comp first val) contracts))))
    (doseq [[path [package-id disclaimer]] contracts
            :let [configuration
                  (project-emission/read-configuration workspace path)
                  description (get-in configuration [:package :description])
                  project (project-emission/project-text configuration [])]]
      (testing package-id
        (is (= package-id (get-in configuration [:package :id])))
        (is (str/ends-with? description disclaimer))
        (is (str/includes?
             project
             (str "<Description>" description "</Description>")))))))

(defn- emit! [target worker-count]
  (let [{:keys [root discovery first]} (fixture/models)]
    (concurrency/call-with-executor
     {:worker-count worker-count}
     #(project-emission/emit-project!
       {:workspace-root root
        :target target
        :project-input discovery
        :resolved-model first
        :configuration (project-emission/read-configuration
                        root "targets/pkl/destinations/parser.edn")
        :rule-bundle (pkl-project/rule-bundle)}))))

(deftest destination-package-metadata-must-be-non-blank
  (let [configuration (project-emission/read-configuration
                       (paths/workspace-root)
                       "targets/pkl/destinations/parser.edn")
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

(deftest destination-package-metadata-must-be-valid-xml-text
  (let [configuration
        (project-emission/read-configuration
         (paths/workspace-root)
         "targets/pkl/destinations/parser.edn")]
    (doseq [setting [:authors :license-expression :copyright]]
      (testing (name setting)
        (let [error
              (try
                (project-emission/validate-configuration!
                 (assoc-in configuration [:package setting]
                           (str "Legal metadata" (char 1) "suffix")))
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-destination-configuration (:kind (ex-data error))))
          (is (= :package (:section (ex-data error))))
          (is (= setting (:setting (ex-data error)))))))))

(deftest destination-authors-must-be-a-single-line-publisher
  (let [configuration
        (project-emission/read-configuration
         (paths/workspace-root)
         "targets/pkl/destinations/parser.edn")]
    (doseq [separator
            ["\u0000" "\u000B" "\u000C" "\r" "\n"
             "\u0085" "\u2028" "\u2029"]]
      (let [authors (str "Publisher" separator "Other")
            error
            (try
              (project-emission/validate-configuration!
               (assoc-in configuration [:package :authors] authors))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :invalid-destination-configuration
               (:kind (ex-data error))))
        (is (= :package (:section (ex-data error))))
        (is (= :authors (:setting (ex-data error))))))
    (is (map?
         (project-emission/validate-configuration!
          (assoc-in configuration [:package :authors]
                    "Publisher \uD83D\uDE80"))))))

(deftest destination-legal-file-paths-must-be-case-insensitively-distinct
  (let [configuration
        (project-emission/read-configuration
         (paths/workspace-root)
         "targets/pkl/destinations/parser.edn")]
    (doseq [[field expected]
            [[:destination
              "entries with case-insensitively distinct :destination values"]
             [:package-path
              "entries with case-insensitively distinct :package-path values"]]]
      (testing (name field)
        (let [first-value (get-in configuration [:legal-files 0 field])
              candidate
              (assoc-in configuration [:legal-files 1 field]
                        (str/lower-case first-value))
              error
              (try
                (project-emission/validate-configuration! candidate)
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-destination-configuration
                 (:kind (ex-data error))))
          (is (= [:legal-files] (:path (ex-data error))))
          (is (= expected (:expected (ex-data error)))))))))

(deftest mechanical-source-attribution-metadata-must-be-single-line
  (let [configuration
        (project-emission/read-configuration
         (paths/workspace-root)
         "targets/rawhttp/destinations/core.edn")]
    (doseq [field [:repository :notice-reference]
            separator
            ["\u0000" "\u000B" "\u000C" "\r" "\n"
             "\u0085" "\u2028" "\u2029"]]
      (testing (str (name field) " rejects " (pr-str separator))
        (let [error
              (try
                (project-emission/validate-configuration!
                 (assoc-in configuration [:mechanical-source field]
                           (str "Upstream" separator "forged")))
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-destination-configuration
                 (:kind (ex-data error))))
          (is (= [:mechanical-source field]
                 (:path (ex-data error)))))))))

(deftest destination-files-require-exactly-one-edn-value
  (let [root (temp-directory)
        relative "targets/rawhttp/destinations/core.edn"
        live-destination
        (Files/readString
         (paths/resolve-path (paths/workspace-root) relative))]
    (doseq [[label content reason]
            [[:empty "" :empty-edn]
             [:invalid "{" :invalid-edn]
             [:trailing (str live-destination "\n{}") :trailing-data]]]
      (testing (name label)
        (write-file! root relative content)
        (let [error
              (try
                (project-emission/read-configuration root relative)
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
          (is (= :invalid-destination-configuration
                 (:kind (ex-data error))))
          (is (= reason (:reason (ex-data error))))
          (is (str/ends-with? (:path (ex-data error)) relative)))))))

(deftest destination-diagnostics-name-nested-fields-and-key-drift
  (let [configuration
        (project-emission/read-configuration
         (paths/workspace-root)
         "targets/rawhttp/destinations/core.edn")
        diagnostic
        (fn [candidate]
          (try
            (project-emission/validate-configuration! candidate)
            nil
            (catch clojure.lang.ExceptionInfo error
              (ex-data error))))
        unknown
        (diagnostic (assoc-in configuration [:project :opaque-setting] true))
        resource
        (diagnostic
         (assoc-in
          configuration
          [:resources
           "META-INF/services/rawhttp.core.body.encoding.HttpMessageDecoder"
           :logical-name]
          42))
        mechanical
        (diagnostic
         (update configuration :mechanical-source dissoc :revision))]
    (is (= :invalid-destination-configuration (:kind unknown)))
    (is (= [:project] (:path unknown)))
    (is (= [:opaque-setting] (:unknown-keys unknown)))
    (is (= [] (:missing-keys unknown)))
    (is (=
         [:resources
          "META-INF/services/rawhttp.core.body.encoding.HttpMessageDecoder"
          :logical-name]
         (:path resource)))
    (is (= 42 (:value resource)))
    (is (= "a string" (:expected resource)))
    (is (= [:mechanical-source] (:path mechanical)))
    (is (= [:revision] (:missing-keys mechanical)))
    (is (= [] (:unknown-keys mechanical)))))

(deftest pkl-bundle-composes-over-the-shared-java-library-contract
  (let [shared (java-library/rule-bundle)
        pkl (pkl-project/rule-bundle)]
    (is (= "@class" (java-library/identifier "class")))
    (is (= "@Class" (java-library/pascal "class")))
    (is (= (:schema-version shared) (:schema-version pkl)))
    (is (= #{:product-runtime-assets}
           (set/difference (set (keys (:rules pkl)))
                           (set (keys (:rules shared))))))
    (is (identical? (get-in shared [:rules :resource-policy :resource-mapping])
                    (get-in pkl [:rules :resource-policy :resource-mapping])))
    (is (identical? (get-in shared [:rules :project-policy :project-text])
                    (get-in pkl [:rules :project-policy :project-text])))
    (is (identical? (get-in shared [:rules :namespace-policy
                                    :destination-namespace])
                    (get-in pkl [:rules :namespace-policy
                                 :destination-namespace])))
    (is (identical? (get-in shared [:rules :namespace-policy
                                    :destination-file-name])
                    (get-in pkl [:rules :namespace-policy
                                 :destination-file-name])))
    (is (identical? (get-in shared [:rules :resolved-mappings :type-node])
                    (get-in pkl [:rules :resolved-mappings :type-node])))
    (is (fn? (get-in pkl [:rules :resolved-mappings
                          :type-policy :emit-shape])))
    (is (fn? (get-in pkl [:rules :resolved-mappings
                          :type-policy :decorate-node])))
    (is (= :pkl (:id pkl)))
    (is (= :pkl (:product-family pkl)))
    (is (identical?
         (get-in shared [:rules :structural-declarations :emit-root-node])
         (get-in pkl [:rules :structural-declarations :emit-root-node])))
    (is (identical?
         (get-in shared [:rules :structural-declarations :translate-member])
         (get-in pkl [:rules :structural-declarations :translate-member])))))

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
      (is (= 62 (:generated-files summary)))
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
                  (:sources manifest)))
      (let [mapping-report (:mapping-report first-emission)]
        (is (= mapping-report (:mapping-report manifest)))
        (is (= :pkl (:target mapping-report)))
        (is (empty? (:unmapped-symbols mapping-report)))
        (is (pos? (get-in mapping-report
                          [:summary :mapping-required-occurrences])))
        (is (pos? (get-in mapping-report [:summary :used-mappings])))
        (is (every?
             #(= #{:registry :identity :resolved-key :kind :strategy
                   :caveats :introduced-by :evidence :occurrences}
                 (set (keys %)))
             (:used-mappings mapping-report)))
        (is (every? seq (map :evidence (:used-mappings mapping-report))))
        (is (apply >= (map :occurrences (:used-mappings mapping-report))))))

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
        (is (some #(str/includes? % "if (!(base.Equals(o!)))") sources))
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
        (is (str/includes? project pkl-non-affiliation-disclaimer))
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
        (doseq [area java-compat-areas
                :let [helper
                      (paths/resolve-path
                       first-root "src/DripSharp/Runtime/JavaCompat"
                       (str area ".cs"))
                      helper-source
                      (paths/resolve-path
                       (paths/workspace-root) "runtime"
                       (str "DripSharp.JavaCompat." area ".cs"))]]
          (is (= (vec (Files/readAllBytes helper-source))
                 (vec (Files/readAllBytes helper)))))
        (is (= (vec (Files/readAllBytes unicode-helper-source))
               (vec (Files/readAllBytes unicode-helper))))))

    (testing "two clean emissions are byte-for-byte identical"
      (is (= (directory-bytes first-root) (directory-bytes second-root))))))
