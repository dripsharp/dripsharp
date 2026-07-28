(ns dripsharp.pkl.language-snippet-contract
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileSystems FileVisitOption Files LinkOption OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(def ^:private manifest-magic "DRIPSHARP_LANGUAGE_SNIPPET_CONTRACT_V1")

(def pinned-upstream-revision
  "f7cac257ade5775c1dfc255f4fda2eacc296e9d0")

(def ^:private upstream-repository "https://github.com/apple/pkl.git")
(def ^:private expected-case-count 940)

(def manifest-columns
  ["case-id"
   "input-path"
   "input-sha256"
   "expected-outcome"
   "expected-path"
   "expected-sha256"
   "semantic-family"
   "source-family"
   "product-scope"
   "scope-basis"
   "input-dependencies"
   "helper-dependencies"
   "project-dependencies"
   "fixture-references"
   "execution-requirements"])

(def ^:private semantic-families
  #{"collections-generators"
    "diagnostics"
    "fundamental-language"
    "module-project-package"
    "standard-library-renderer"})

(def ^:private product-scopes
  #{"in-scope"
    "in-scope-mixed-excluded-surface"
    "outside-epic-approved-exclusion"})

(def ^:private expected-outcomes
  #{"success-output" "success-empty" "error-output"})

(def ^:private output-relative-root
  "pkl-core/src/test/files/LanguageSnippetTests/output")

(def ^:private engine-relative-path
  "pkl-core/src/test/kotlin/org/pkl/core/LanguageSnippetTestsEngine.kt")

(def ^:private discovery-relative-path
  "pkl-commons-test/src/main/kotlin/org/pkl/commons/test/InputOutputTestEngine.kt")

(def ^:private outcome-relative-path
  "pkl-commons-test/src/main/kotlin/org/pkl/commons/test/FileTestUtils.kt")

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-language-snippet-contract)))))

(def ^:private portable-path util/portable-path)

(defn- path-under?
  [^Path root ^Path path]
  (.startsWith (.normalize path) (.normalize root)))

(defn- walk-paths
  [^Path root predicate]
  (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray entries)
         (map #(cast Path %))
         (filter predicate)
         (sort-by #(portable-path root %))
         vec)))

(defn- regular-or-link?
  [^Path path]
  (or (Files/isRegularFile path (make-array LinkOption 0))
      (Files/isSymbolicLink path)))

(def ^:private sha256-file util/sha256-file)

(defn- read-text
  [^Path file]
  (when (and (Files/isRegularFile file (make-array LinkOption 0))
             (not (Files/isSymbolicLink file)))
    (Files/readString file StandardCharsets/UTF_8)))

(defn- upstream-layout
  [workspace-root]
  (let [root (paths/absolute workspace-root)
        upstream (paths/resolve-path root "research" "pkl")
        snippets (paths/resolve-path upstream "pkl-core" "src" "test" "files"
                                     "LanguageSnippetTests")]
    {:root root
     :upstream upstream
     :snippets snippets
     :input (paths/resolve-path snippets "input")
     :helper (paths/resolve-path snippets "input-helper")
     :output (paths/resolve-path snippets "output")
     :dependency-paths
     (delay (walk-paths snippets regular-or-link?))}))

(defn- command-output
  [request]
  (str/trim (:output (process/run! request))))

(defn- verify-pinned-revision!
  [{:keys [root upstream]}]
  (let [gitlink (command-output {:command ["git" "rev-parse" "HEAD:research/pkl"]
                                 :directory root})
        checkout (command-output {:command ["git" "rev-parse" "HEAD"]
                                  :directory upstream})]
    (doseq [[subject actual] [[:gitlink gitlink] [:checkout checkout]]]
      (when-not (= pinned-upstream-revision actual)
        (fail! "The language-snippet contract upstream revision drifted"
               {:kind :language-snippet-revision-drift
                :subject subject
                :expected pinned-upstream-revision
                :actual actual})))
    pinned-upstream-revision))

(defn- source-family
  [relative-input]
  (let [segments (str/split relative-input #"/")]
    (if (= 1 (count segments)) "root" (first segments))))

(defn- semantic-family
  [relative-input]
  (case (source-family relative-input)
    ("generators" "listings" "listings2" "mappings" "mappings2")
    "collections-generators"

    "errors" "diagnostics"

    ("modules" "packages" "projects") "module-project-package"

    ("api" "pklbinary") "standard-library-renderer"

    ("annotation" "basic" "classes" "implementation" "internal" "lambdas"
                  "methods" "objects" "parser" "root" "syntax" "types")
    "fundamental-language"

    (fail! "A language-snippet case has no semantic-family classification"
           {:kind :unclassified-language-snippet-family
            :input relative-input
            :source-family (source-family relative-input)})))

(defn- scope-classification
  [relative-input]
  (cond
    (re-matches #"api/yaml(?:Parser|Renderer).*[.]pkl" relative-input)
    {:product-scope "outside-epic-approved-exclusion"
     :scope-basis "product-goal+port-scope:user-approved-yaml-support-exclusion;sole-yaml-parser-or-renderer-behavior"}

    (or (str/starts-with? relative-input "pklbinary/")
        (= relative-input "pklbinaryTest.pkl")
        (= relative-input "api/pklbinary1.msgpack.yaml.pkl"))
    {:product-scope "in-scope-mixed-excluded-surface"
     :scope-basis "in-scope-evaluator+value-model-observation;excluded-pkl-binary-transport-is-not-a-case-exclusion"}

    (#{"api/renderDirective.pkl" "api/renderDirective2.pkl"} relative-input)
    {:product-scope "in-scope-mixed-excluded-surface"
     :scope-basis "in-scope-render-directive+non-yaml-renderers;excluded-yaml-branch-is-not-a-case-exclusion"}

    (= relative-input "basic/importGlob.pkl")
    {:product-scope "in-scope-mixed-excluded-surface"
     :scope-basis "in-scope-module-glob+dependency-resolution;incidental-yaml-renderer-is-not-a-case-exclusion"}

    :else
    {:product-scope "in-scope"
     :scope-basis "product-goal:core-parsing+evaluation+value-model+module-loading+runtime-behavior"}))

(defn- replace-last-extension
  [value replacement]
  (let [index (.lastIndexOf ^String value ".")]
    (when (neg? index)
      (fail! "Expected-output path has no extension"
             {:kind :invalid-expected-output-path :path value}))
    (str (subs value 0 (inc index)) replacement)))

(defn- expected-output-candidates
  [{:keys [output]} relative-input]
  (let [filename (last (str/split relative-input #"/"))
        hidden-extension? (boolean (re-find #"[.][^.]+[.]pkl$" filename))
        output-relative (if hidden-extension?
                          (subs relative-input 0 (- (count relative-input) 4))
                          (str (subs relative-input 0 (- (count relative-input) 3)) "pcf"))
        error-relative (replace-last-extension output-relative "err")]
    {:success-relative output-relative
     :success-path (paths/resolve-path output output-relative)
     :error-relative error-relative
     :error-path (paths/resolve-path output error-relative)}))

(defn- expected-output
  [layout relative-input]
  (let [{:keys [success-relative success-path error-relative error-path]}
        (expected-output-candidates layout relative-input)
        success? (paths/regular-file? success-path)
        error? (paths/regular-file? error-path)]
    (when (and success? error?)
      (fail! "A language-snippet case maps to both success and error output"
             {:kind :ambiguous-language-snippet-output
              :input relative-input
              :success success-relative
              :error error-relative}))
    (cond
      success? {:expected-outcome "success-output"
                :expected-path (str output-relative-root "/" success-relative)
                :expected-sha256 (sha256-file success-path)}
      error? {:expected-outcome "error-output"
              :expected-path (str output-relative-root "/" error-relative)
              :expected-sha256 (sha256-file error-path)}
      :else {:expected-outcome "success-empty"
             :expected-path "-"
             :expected-sha256 "-"})))

(def ^:private operation-reference-pattern
  #"(?m)(?:^\s*(?:amends|extends|import\*?)\s+|(?:import\*?|read\??\*?)\s*\(\s*)(?:#+)?\"([^\"\r\n]+)")

(defn- operation-references
  [text]
  (if text
    (mapv second (re-seq operation-reference-pattern text))
    []))

(defn- uri-like?
  [value]
  (or (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" value)
      (str/starts-with? value "@")))

(defn- path-without-fragment
  [value]
  (if-let [index (and (not (uri-like? value)) (str/index-of value "#"))]
    (subs value 0 index)
    value))

(defn- wildcard?
  [value]
  (boolean (re-find #"[*?\[{]" value)))

(defn- ancestors-through
  [^Path start ^Path boundary]
  (->> (iterate #(.getParent ^Path %) start)
       (take-while some?)
       (take-while #(path-under? boundary %))))

(defn- exact-local-candidates
  [{:keys [snippets]} ^Path source value]
  (let [value (path-without-fragment value)]
    (cond
      (or (str/blank? value)
          (uri-like? value)
          (str/starts-with? value "/")
          (str/includes? value "\\("))
      []

      (str/starts-with? value ".../")
      (let [suffix (subs value 4)]
        (mapv #(.normalize (.resolve ^Path % suffix))
              (ancestors-through (.getParent source) snippets)))

      :else
      [(.normalize (.resolve (.getParent source) value))])))

(defn- dependency-tree
  [^Path root]
  (when (paths/exists? root)
    (if (Files/isDirectory root (make-array LinkOption 0))
      (walk-paths root regular-or-link?)
      (when (regular-or-link? root) [root]))))

(defn- glob-matches?
  [pattern relative]
  (try
    (let [matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))]
      (.matches matcher (paths/path relative)))
    (catch RuntimeException _ false)))

(defn- resolve-local-glob
  [{:keys [snippets dependency-paths]} ^Path source value]
  (let [candidates @dependency-paths]
    (if (str/starts-with? value ".../")
      (let [pattern (subs value 4)
            ancestors (ancestors-through (.getParent source) snippets)]
        (->> candidates
             (filter
              (fn [candidate]
                (some #(glob-matches? pattern (portable-path % candidate)) ancestors)))
             set))
      (->> candidates
           (filter #(glob-matches? value (portable-path (.getParent source) %)))
           set))))

(declare implicit-project-dependencies)

(defn- resolve-project-reference
  [{:keys [input dependency-paths]} value]
  (let [[alias suffix] (str/split (subs value 1) #"/" 2)
        project-directory (paths/resolve-path input "projects" alias)
        project-file (paths/resolve-path project-directory "PklProject")]
    (if-not (paths/directory? project-directory)
      #{}
      (set/union
       (if (paths/regular-file? project-file)
         (implicit-project-dependencies project-file)
         #{})
       (cond
         (str/blank? suffix) (set (dependency-tree project-directory))
         (wildcard? suffix)
         (->> @dependency-paths
              (filter #(and (path-under? project-directory %)
                            (glob-matches? suffix (portable-path project-directory %))))
              set)
         :else
         (set (or (dependency-tree (paths/resolve-path project-directory suffix)) [])))))))

(defn- resolve-local-reference
  [layout ^Path source value]
  (cond
    (or (str/blank? value) (str/includes? value "\\(")) #{}
    (str/starts-with? value "@") (resolve-project-reference layout value)
    (uri-like? value) #{}
    (wildcard? value) (resolve-local-glob layout source value)
    :else
    (->> (exact-local-candidates layout source value)
         (mapcat #(or (dependency-tree %) []))
         (filter #(path-under? (:snippets layout) %))
         set)))

(defn- implicit-project-dependencies
  [^Path file]
  (if (= "PklProject" (str (.getFileName file)))
    (let [deps (.resolveSibling file "PklProject.deps.json")]
      (cond-> #{file}
        (paths/regular-file? deps) (conj deps)))
    #{}))

(defn- nearest-project-files
  [{:keys [input]} ^Path input-file]
  (loop [directory (.getParent input-file)]
    (if (or (nil? directory) (not (path-under? input directory)))
      #{}
      (let [project (.resolve directory "PklProject")]
        (if (paths/regular-file? project)
          (implicit-project-dependencies project)
          (recur (.getParent directory)))))))

(defn- direct-local-dependencies
  [layout ^Path file]
  (let [text (read-text file)]
    (->> (operation-references text)
         (mapcat #(resolve-local-reference layout file %))
         set)))

(defn- dependency-closure
  [layout direct-dependencies ^Path input-file]
  (let [initial (set/union (nearest-project-files layout input-file)
                           (direct-dependencies input-file))]
    (loop [pending (vec (sort-by str initial))
           seen #{input-file}]
      (if-let [file (peek pending)]
        (let [pending (pop pending)]
          (if (contains? seen file)
            (recur pending seen)
            (let [found (set/union (direct-dependencies file)
                                   (implicit-project-dependencies file))]
              (recur (into pending (remove seen (sort-by str found)))
                     (conj seen file)))))
        (disj seen input-file)))))

(defn- joined-list
  [values]
  (if (seq values) (str/join ";" (sort values)) "-"))

(defn- fixture-references
  [files]
  (->> files
       (mapcat #(operation-references (read-text %)))
       (remove str/blank?)
       distinct
       sort
       vec))

(defn- dependency-groups
  [{:keys [upstream input helper]} dependencies]
  (let [portable #(portable-path upstream %)
        project? #(or (= "PklProject" (str (.getFileName ^Path %)))
                      (= "PklProject.deps.json" (str (.getFileName ^Path %))))]
    {:input-dependencies (->> dependencies
                              (filter #(and (path-under? input %) (not (project? %))))
                              (map portable)
                              joined-list)
     :helper-dependencies (->> dependencies
                               (filter #(path-under? helper %))
                               (map portable)
                               joined-list)
     :project-dependencies (->> dependencies
                                (filter project?)
                                (map portable)
                                joined-list)}))

(defn- execution-requirements
  [{:keys [expected-outcome expected-path product-scope] :as case-data}
   dependency-files references source-text]
  (let [all-text (str source-text "\n"
                      (str/join "\n" (keep read-text dependency-files)))
        reference-text (str/join "\n" references)]
    (-> (cond-> #{"engine-baseline"}
          (= expected-outcome "error-output") (conj "expected-error")
          (= expected-outcome "success-empty") (conj "empty-output")
          (str/ends-with? expected-path ".yaml") (conj "messagepack-debug-decoding")
          (not= "-" (:project-dependencies case-data)) (conj "project-loading")
          (re-find #"(?:package|projectpackage)://|(?m)(?:^|[\"(])@[A-Za-z]" reference-text)
          (conj "package-service" "http-test-port" "test-certificate")
          (re-find #"https?://" reference-text) (conj "http-test-client" "test-certificate")
          (re-find #"(?m)^env:" reference-text) (conj "environment-variables")
          (re-find #"(?m)^prop:" reference-text) (conj "external-properties")
          (re-find #"\btrace\s*\(" all-text) (conj "logger-output")
          (re-find #"\bimport\*?\s*(?:\(|\s)" all-text) (conj "module-loading")
          (re-find #"\bread\??\*?\s*\(" all-text) (conj "resource-loading")
          (= product-scope "outside-epic-approved-exclusion")
          (conj "approved-excluded-surface-oracle")
          (= product-scope "in-scope-mixed-excluded-surface")
          (conj "mixed-scope-observation"))
        sort
        joined-list)))

(defn- build-case
  [layout direct-dependencies ^Path input-file]
  (let [{:keys [upstream input]} layout
        relative-input (portable-path input input-file)
        dependency-files (dependency-closure layout direct-dependencies input-file)
        grouped (dependency-groups layout dependency-files)
        references (fixture-references (cons input-file dependency-files))
        source-text (read-text input-file)
        scope (scope-classification relative-input)
        output (expected-output layout relative-input)
        base (merge
              {:case-id (str "language-snippet/" relative-input)
               :input-path (portable-path upstream input-file)
               :input-sha256 (sha256-file input-file)
               :semantic-family (semantic-family relative-input)
               :source-family (source-family relative-input)
               :fixture-references (joined-list references)}
              output scope grouped)]
    (assoc base :execution-requirements
           (execution-requirements base dependency-files references source-text))))

(defn- build-cases
  [layout]
  (let [inputs (walk-paths (:input layout)
                           #(and (Files/isRegularFile ^Path % (make-array LinkOption 0))
                                 (str/ends-with? (str (.getFileName ^Path %)) ".pkl")))
        direct-dependencies (memoize #(direct-local-dependencies layout %))]
    (mapv #(build-case layout direct-dependencies %) inputs)))

(defn- metadata
  [{:keys [upstream]}]
  (let [engine (paths/resolve-path upstream engine-relative-path)
        discovery (paths/resolve-path upstream discovery-relative-path)
        outcome (paths/resolve-path upstream outcome-relative-path)]
    [["source-repository" upstream-repository]
     ["source-gitlink" "research/pkl"]
     ["source-revision" pinned-upstream-revision]
     ["engine-source" engine-relative-path]
     ["engine-source-sha256" (sha256-file engine)]
     ["discovery-source" discovery-relative-path]
     ["discovery-source-sha256" (sha256-file discovery)]
     ["outcome-source" outcome-relative-path]
     ["outcome-source-sha256" (sha256-file outcome)]
     ["case-count" (str expected-case-count)]
     ["engine-environment"
      "NAME1=value1;NAME2=value2;/foo/bar=foobar;foo bar=foo bar;file:///foo/bar=file:///foo/bar"]
     ["engine-external-properties" "name1=value1;name2=value2;/foo/bar=foobar"]
     ["engine-baseline"
      "evaluate-output-bytes;logger-capture;stack-frame-transformer-empty;module-cache-disabled;power-assertions;http-test-port;test-certificate;package-service;project-application"]
     ["normalization"
      "line-endings-lf;line-prefix-digits-x-preserve-width;location-line-digits-X-preserve-width;reflected-line-digits-X-preserve-width;paths-$snippetsDir;website-https://$pklWebsite/;version-Pkl version is xxx;stdlib-https://github.com/apple/pkl/blob/$commitId/stdlib/;stdout-then-logger;errors"]]))

(defn contract-model
  ([] (contract-model (paths/workspace-root)))
  ([workspace-root]
   (let [layout (upstream-layout workspace-root)]
     (verify-pinned-revision! layout)
     {:metadata (metadata layout)
      :columns manifest-columns
      :cases (build-cases layout)
      :layout layout})))

(defn- case-field
  [case-data column]
  (let [value (get case-data (keyword column))]
    (when-not (and (string? value)
                   (not (str/blank? value))
                   (not (re-find #"[\t\r\n]" value)))
      (fail! "A language-snippet manifest field is not safely encodable"
             {:kind :invalid-language-snippet-field
              :column column :value value :case (:case-id case-data)}))
    value))

(defn render-manifest
  [{:keys [metadata columns cases]}]
  (str manifest-magic "\n"
       (apply str (for [[key value] metadata]
                    (str "meta\t" key "\t" value "\n")))
       "columns\t" (str/join "\t" columns) "\n"
       (apply str
              (for [case-data cases]
                (str "case\t"
                     (str/join "\t" (map #(case-field case-data %) columns))
                     "\n")))))

(defn generate-manifest!
  ([manifest] (generate-manifest! (paths/workspace-root) manifest))
  ([workspace-root manifest]
   (let [manifest (paths/absolute manifest)
         model (contract-model workspace-root)]
     (Files/createDirectories (.getParent manifest) (make-array FileAttribute 0))
     (Files/writeString manifest (render-manifest model) StandardCharsets/UTF_8
                        (make-array OpenOption 0))
     manifest)))

(defn read-manifest
  [manifest]
  (let [manifest (paths/absolute manifest)
        content (Files/readString manifest StandardCharsets/UTF_8)
        lines (str/split-lines content)]
    (when-not (= manifest-magic (first lines))
      (fail! "Language-snippet manifest has the wrong schema marker"
             {:kind :invalid-language-snippet-schema
              :manifest (str manifest)
              :actual (first lines)}))
    (loop [remaining (rest lines)
           metadata []
           columns nil
           cases []]
      (if-let [line (first remaining)]
        (let [fields (str/split line #"\t" -1)]
          (case (first fields)
            "meta"
            (do
              (when-not (= 3 (count fields))
                (fail! "Malformed language-snippet metadata row"
                       {:kind :malformed-language-snippet-manifest :line line}))
              (recur (rest remaining) (conj metadata [(second fields) (nth fields 2)])
                     columns cases))

            "columns"
            (do
              (when columns
                (fail! "Language-snippet manifest repeats its columns row"
                       {:kind :duplicate-language-snippet-columns}))
              (recur (rest remaining) metadata (vec (rest fields)) cases))

            "case"
            (do
              (when-not columns
                (fail! "Language-snippet case appears before the columns row"
                       {:kind :malformed-language-snippet-manifest :line line}))
              (when-not (= (count columns) (dec (count fields)))
                (fail! "Language-snippet case has the wrong field count"
                       {:kind :malformed-language-snippet-case
                        :expected (count columns) :actual (dec (count fields))
                        :line line}))
              (recur (rest remaining) metadata columns
                     (conj cases (zipmap (map keyword columns) (rest fields)))))

            (fail! "Language-snippet manifest contains an unknown row kind"
                   {:kind :malformed-language-snippet-manifest :line line})))
        {:manifest manifest
         :content content
         :metadata metadata
         :columns columns
         :cases cases}))))

(defn- duplicate-values
  [values]
  (->> values frequencies (keep (fn [[value n]] (when (> n 1) value))) sort vec))

(defn- split-list
  [value]
  (if (= "-" value) [] (str/split value #";" -1)))

(defn- validate-rows!
  [{:keys [cases columns] :as parsed}]
  (when-not (= manifest-columns columns)
    (fail! "Language-snippet manifest columns drifted"
           {:kind :language-snippet-columns-drift
            :expected manifest-columns :actual columns}))
  (when-not (= expected-case-count (count cases))
    (fail! "Language-snippet manifest does not contain exactly 940 cases"
           {:kind :language-snippet-case-count
            :expected expected-case-count :actual (count cases)}))
  (doseq [[field label] [[:case-id "case identities"] [:input-path "inputs"]]]
    (when-let [duplicates (seq (duplicate-values (map field cases)))]
      (fail! (str "Language-snippet manifest has duplicate " label)
             {:kind :duplicate-language-snippet-row
              :field field :duplicates duplicates})))
  (when-let [duplicates (seq (duplicate-values
                              (remove #{"-"} (map :expected-path cases))))]
    (fail! "Language-snippet manifest maps multiple cases to one expected output"
           {:kind :duplicate-language-snippet-output :duplicates duplicates}))
  (doseq [case-data cases]
    (when-not (semantic-families (:semantic-family case-data))
      (fail! "Language-snippet case has no recognized semantic family"
             {:kind :unclassified-language-snippet-family
              :case (:case-id case-data) :actual (:semantic-family case-data)}))
    (when-not (product-scopes (:product-scope case-data))
      (fail! "Language-snippet case has no recognized product-scope classification"
             {:kind :unclassified-language-snippet-scope
              :case (:case-id case-data) :actual (:product-scope case-data)}))
    (when-not (expected-outcomes (:expected-outcome case-data))
      (fail! "Language-snippet case has no recognized expected outcome"
             {:kind :unclassified-language-snippet-outcome
              :case (:case-id case-data) :actual (:expected-outcome case-data)}))
    (when-not (some #{"engine-baseline"}
                    (split-list (:execution-requirements case-data)))
      (fail! "Language-snippet case omits its executable engine baseline"
             {:kind :unexecutable-language-snippet-row
              :case (:case-id case-data)}))
    (when (and (= "outside-epic-approved-exclusion" (:product-scope case-data))
               (not (str/includes? (:scope-basis case-data)
                                   "user-approved-yaml-support-exclusion")))
      (fail! "Language-snippet case is outside the epic without an approved exclusion"
             {:kind :unapproved-language-snippet-exclusion
              :case (:case-id case-data)
              :basis (:scope-basis case-data)})))
  parsed)

(defn- expected-output-files
  [{:keys [output]}]
  (->> (walk-paths output #(Files/isRegularFile ^Path % (make-array LinkOption 0)))
       (map #(str output-relative-root "/" (portable-path output %)))
       set))

(defn validate-manifest!
  ([manifest] (validate-manifest! (paths/workspace-root) manifest))
  ([workspace-root manifest]
   (let [parsed (validate-rows! (read-manifest manifest))
         expected (contract-model workspace-root)
         layout (:layout expected)
         expected-content (render-manifest expected)
         actual-outputs (set (remove #{"-"} (map :expected-path (:cases parsed))))
         source-outputs (expected-output-files layout)]
     (when-not (= (metadata layout) (:metadata parsed))
       (fail! "Language-snippet manifest provenance or engine contract drifted"
              {:kind :language-snippet-provenance-drift
               :expected (metadata layout) :actual (:metadata parsed)}))
     (when-not (= source-outputs actual-outputs)
       (fail! "Language-snippet expected-output mapping is incomplete"
              {:kind :language-snippet-output-coverage
               :missing (vec (sort (set/difference source-outputs actual-outputs)))
               :orphaned (vec (sort (set/difference actual-outputs source-outputs)))}))
     (doseq [case-data (:cases parsed)
             dependency-field [:input-dependencies :helper-dependencies
                               :project-dependencies]
             dependency (split-list (dependency-field case-data))]
       (when-not (paths/exists? (paths/resolve-path (:upstream layout) dependency))
         (fail! "Language-snippet manifest names a missing dependency"
                {:kind :missing-language-snippet-dependency
                 :case (:case-id case-data)
                 :field dependency-field
                 :dependency dependency})))
     (when-not (= expected-content (:content parsed))
       (fail! "The source-controlled language-snippet manifest is stale"
              {:kind :stale-language-snippet-manifest
               :manifest (str (:manifest parsed))}))
     (assoc parsed
            :summary
            {:cases (count (:cases parsed))
             :families (frequencies (map :semantic-family (:cases parsed)))
             :scopes (frequencies (map :product-scope (:cases parsed)))
             :outcomes (frequencies (map :expected-outcome (:cases parsed)))
             :expected-output-files (count source-outputs)
             :upstream-revision pinned-upstream-revision}))))

(defn- result-status
  [case-data]
  (if (= "error-output" (:expected-outcome case-data)) "ERROR" "SUCCESS"))

(defn- base64
  [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn write-expected-results!
  [validated-manifest output]
  (let [;; Recover the checkout from the manifest path so callers need only
        ;; retain the durable parsed contract returned by validate-manifest!.
        workspace (paths/workspace-root (.getParent ^Path (:manifest validated-manifest)))
        upstream (paths/resolve-path workspace "research" "pkl")
        content
        (apply str
               (for [case-data (:cases validated-manifest)]
                 (let [bytes (if (= "-" (:expected-path case-data))
                               (byte-array 0)
                               (Files/readAllBytes
                                (paths/resolve-path upstream (:expected-path case-data))))]
                   (str (:case-id case-data) "\t" (result-status case-data) "\t"
                        (base64 bytes) "\n"))))
        output (paths/absolute output)]
    (Files/createDirectories (.getParent output) (make-array FileAttribute 0))
    (Files/writeString output content StandardCharsets/UTF_8 (make-array OpenOption 0))
    output))

(defn- read-results
  [result-file]
  (let [lines (str/split-lines (Files/readString (paths/path result-file)
                                                 StandardCharsets/UTF_8))]
    (mapv
     (fn [index line]
       (let [fields (str/split line #"\t" -1)]
         (when-not (= 3 (count fields))
           (fail! "Malformed language-snippet oracle result"
                  {:kind :malformed-language-snippet-result
                   :line (inc index) :actual line}))
         (let [[case-id status payload] fields]
           (when-not (#{"SUCCESS" "ERROR" "UNEXECUTABLE"} status)
             (fail! "Language-snippet oracle emitted an unknown status"
                    {:kind :malformed-language-snippet-result
                     :line (inc index) :status status}))
           (try
             (.decode (Base64/getDecoder) payload)
             (catch IllegalArgumentException error
               (fail! "Language-snippet oracle emitted invalid base64"
                      {:kind :malformed-language-snippet-result
                       :line (inc index) :case case-id})))
           {:case-id case-id :status status :payload payload :line line})))
     (range)
     lines)))

(defn compare-results
  [validated-manifest expected-file actual-file]
  (let [expected (read-results expected-file)
        actual (read-results actual-file)
        manifest-ids (mapv :case-id (:cases validated-manifest))
        expected-ids (mapv :case-id expected)
        actual-ids (mapv :case-id actual)]
    (cond
      (seq (duplicate-values expected-ids))
      {:mismatch {:kind :duplicate-expected-results
                  :cases (duplicate-values expected-ids)}}

      (seq (duplicate-values actual-ids))
      {:mismatch {:kind :duplicate-actual-results
                  :cases (duplicate-values actual-ids)}}

      (not= manifest-ids expected-ids)
      {:mismatch {:kind :expected-result-coverage
                  :expected manifest-ids :actual expected-ids}}

      (not= manifest-ids actual-ids)
      {:mismatch {:kind :actual-result-coverage
                  :expected manifest-ids :actual actual-ids}}

      :else
      (if-let [mismatch
               (first
                (keep-indexed
                 (fn [index [expected-row actual-row]]
                   (when-not (= (:line expected-row) (:line actual-row))
                     {:kind (if (= "UNEXECUTABLE" (:status actual-row))
                              :unexecutable-in-scope-row
                              :content-mismatch)
                      :line (inc index)
                      :case (:case-id expected-row)
                      :expected (:line expected-row)
                      :actual (:line actual-row)}))
                 (map vector expected actual)))]
        {:matched (dec (:line mismatch)) :mismatch mismatch}
        {:matched (count expected)}))))

(defn- assert-results!
  [validated expected actual subject]
  (let [comparison (compare-results validated expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! (str "Language-snippet " subject " differs from the pinned contract at "
                  (:case mismatch) " (" (name (:kind mismatch)) ")")
             {:kind :language-snippet-oracle-mismatch
              :expected (str expected) :actual (str actual)
              :mismatch mismatch}))
    comparison))

(defn- prove-perturbation!
  [validated expected perturbed]
  (let [lines (str/split-lines (Files/readString expected StandardCharsets/UTF_8))
        first-line (first lines)
        fields (str/split first-line #"\t" -1)
        replacement (str/join "\t" [(first fields) (second fields) "UFJPVkU="])
        content (str (str/join "\n" (cons replacement (rest lines))) "\n")]
    (Files/writeString perturbed content StandardCharsets/UTF_8 (make-array OpenOption 0))
    (let [comparison (compare-results validated expected perturbed)]
      (when-not (= :content-mismatch (get-in comparison [:mismatch :kind]))
        (fail! "Language-snippet comparator missed a deliberate perturbation"
               {:kind :language-snippet-perturbation-undetected
                :comparison comparison}))
      comparison)))

(defn verify-contract!
  ([] (verify-contract! {}))
  ([{:keys [workspace-root manifest run-command!]
     :or {run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         manifest (or manifest
                      (paths/resolve-path root "validation"
                                          "language-snippet-contract"
                                          "LanguageSnippetContract.tsv"))
         validated (validate-manifest! root manifest)
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "validation-output"
                                         "language-snippet-contract"))
         expected (write-expected-results! validated
                                           (paths/resolve-path proof-root "expected.tsv"))
         oracle-classes (doto (paths/resolve-path proof-root "oracle-classes")
                          (Files/createDirectories (make-array FileAttribute 0)))
         oracle-source (paths/resolve-path root "validation"
                                           "language-snippet-contract"
                                           "LanguageSnippetContractOracle.java")
         init-script (paths/resolve-path root "gradle"
                                         "language-snippet-contract.gradle")
         upstream (paths/resolve-path root "research" "pkl")
         first-output (paths/resolve-path proof-root "upstream-first.tsv")
         second-output (paths/resolve-path proof-root "upstream-second.tsv")
         perturbed (paths/resolve-path proof-root "perturbed.tsv")
         run-oracle!
         (fn [output]
           (run-command!
            {:command ["./gradlew" "-I" (str init-script)
                       ":pkl-core:dripsharpLanguageSnippetOracle" "--console=plain"
                       (str "-Pdripsharp.oracleClasses=" oracle-classes)
                       (str "-Pdripsharp.manifest=" (paths/absolute manifest))
                       (str "-Pdripsharp.output=" output)]
             :directory upstream}))]
     (run-command! {:command ["javac" "--release" "17" "-d" (str oracle-classes)
                              (str oracle-source)]
                    :directory root})
     (run-oracle! first-output)
     (run-oracle! second-output)
     (let [first-comparison (assert-results! validated expected first-output
                                             "first upstream JVM run")
           second-comparison (assert-results! validated expected second-output
                                              "second upstream JVM run")
           deterministic (assert-results! validated first-output second-output
                                          "repeated upstream JVM run")
           perturbation (prove-perturbation! validated expected perturbed)
           summary (merge (:summary validated)
                          {:first-run-observations (:matched first-comparison)
                           :second-run-observations (:matched second-comparison)
                           :deterministic-observations (:matched deterministic)
                           :perturbation-detected-at (get-in perturbation [:mismatch :line])})]
       (println "Pinned language-snippet evaluator contract passed:" (pr-str summary))
       {:summary summary
        :manifest (paths/absolute manifest)
        :expected expected
        :first-output first-output
        :second-output second-output}))))
