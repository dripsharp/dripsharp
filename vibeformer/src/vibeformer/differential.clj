(ns vibeformer.differential
  "Independent upstream-JVM versus packaged-.NET parser and core behavior validation."
  (:require [clojure.string :as str]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.harness :as harness]
            [vibeformer.packaging :as packaging]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process]
            [vibeformer.project :as project])
  (:import [java.io BufferedReader File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(def ^:private inline-cases
  [["edge/lexer-single-backtick" "`"]
   ["edge/lexer-sentinel-between-tokens" "// Comment with \uFFFF character\nclass \uFFFF Bar"]
   ["edge/lexer-line-continuation-crlf" "x = \"\"\"\n  hello \\\r\n  world\r\n  \"\"\""]
   ["edge/lexer-line-continuation-cr" "x = \"\"\"\n  hello \\\r  world\n  \"\"\""]
   ["edge/lexer-line-continuation-whitespace-error" "x = \"\"\"\n  hello \\ \r\n  world\n  \"\"\""]
   ["edge/unicode-identifier" "जावास्क्रिप्ट = 1\n"]
   ["edge/string-interpolation-escapes" "name = \"Pigeon\"\nmessage = \"Hello, \\(name)\\n\"\n"]])

(def ^:private unicode-comment-codepoints
  [0x0000 0x0001 0x007f 0x0080 0x7ffe 0x7fff 0x8000 0xfffe 0xffff])

(def ^:private core-cases
  [["evaluation/module-export" "EVALUATE"
    (str "name = \"pigeon\"\n"
         "age = 10 + 20\n"
         "active = true\n"
         "duration = 90.s\n"
         "size = 2.kib\n"
         "pair = Pair(\"answer\", 42)\n"
         "nullValue = null\n"
         "nested = new Dynamic { message = \"hello\" }\n")
    ""]
   ["evaluation/expression-object" "EXPRESSION"
    "res1 = 1\nres2 { res3 = 3; res4 = 4 }\n" "res2"]
   ["evaluation/expression-path" "EXPRESSION"
    "foo { bar = 2 }\n" "foo.bar"]
   ["value-export/output-value" "OUTPUT_VALUE"
    "output { value = Pair(\"done\", 42) }\n" ""]
   ["error/expression-syntax" "EXPRESSION" "foo = 1\n" "<>!!!"]
   ["error/expression-type" "EXPRESSION" "foo = 1\n" "foo as String"]
   ["error/evaluation-missing-property" "EVALUATE" "result = missing\n" ""]
   ["output/default-pcf-text" "OUTPUT_TEXT"
    "name = \"Pigeon\"\nage = 3\n" ""]
   ["output/json-renderer-text" "OUTPUT_TEXT"
    (str "name = \"Pigeon\"\n"
         "age = 3\n"
         "output { renderer = new JsonRenderer {} }\n")
    ""]
   ["output/bytes" "OUTPUT_BYTES"
    "output { bytes = Bytes(0, 1, 127, 128, 255) }\n" ""]
   ["output/multiple-files" "OUTPUT_FILES"
    (str "output {\n"
         "  files {\n"
         "    [\"alpha.txt\"] { text = \"alpha\\n\" }\n"
         "    [\"nested/beta.txt\"] { text = \"βeta\" }\n"
         "  }\n"
         "}\n")
    ""]
   ["value-export/typed-string" "OUTPUT_VALUE_AS_STRING"
    "output { value = \"typed output\" }\n" ""]
   ["error/typed-output-mismatch" "OUTPUT_VALUE_AS_STRING"
    "output { value = 42 }\n" ""]
   ["evaluation/expression-string" "EXPRESSION_STRING"
    "value = 41\n" "value + 1"]
   ["loading/stdlib-import-expression" "EXPRESSION" ""
    "import(\"pkl:math\").gcd(54, 24)"]
   ["loading/local-module-import" "LOCAL_IMPORT"
    "imported = import(\"dependency.pkl\").answer\n"
    "answer = 40 + 2\n"]
   ["loading/local-file-resource" "FILE_RESOURCE"
    (str "resourceText = read(\"resource.txt\").text\n"
         "resourceBytes = read(\"resource.txt\").bytes\n")
    "resource payload\n"]
   ["security/denied-module" "SECURITY_DENIED" "value = 1\n" ""]
   ["runtime/collections-bytes-regex" "EVALUATE"
    (str "list = List(3, 1, 2)\n"
         "set = Set(\"b\", \"a\", \"b\")\n"
         "map = Map(\"two\", 2, \"one\", 1)\n"
         "bytes = Bytes(0, 127, 128, 255)\n"
         "regex = Regex(#\"a.+b\"#)\n"
         "computed = List(1, 2, 3).map((it) -> it * 2)\n")
    ""]])

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :differential-validation-failed))))

(defn- write-text! [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(defn- corpus-files [^Path corpus]
  (with-open [files (Files/walk corpus (make-array java.nio.file.FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (filter #(str/ends-with? (str %) ".pkl"))
         (sort-by #(str (.relativize corpus ^Path %)))
         vec)))

(defn- b64 [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- manifest-cases [^Path corpus]
  (let [upstream
        (mapv (fn [^Path file]
                [(str "corpus/" (str/replace (str (.relativize corpus file)) "\\" "/"))
                 (Files/readString file StandardCharsets/UTF_8)])
              (corpus-files corpus))
        unicode
        (mapv (fn [codepoint]
                [(format "edge/unicode-comment-u%04x" codepoint)
                 (str "// Test " (char codepoint) "\nmodule Test")])
              unicode-comment-codepoints)]
    {:upstream-count (count upstream)
     :edge-count (+ (count inline-cases) (count unicode))
     :cases (into upstream (concat inline-cases unicode))}))

(defn- write-manifest! [^Path manifest cases]
  (write-text! manifest
               (apply str (map (fn [[id source]] (str id "\t" (b64 source) "\n")) cases))))

(defn- write-core-manifest! [^Path manifest cases]
  (write-text!
   manifest
   (apply str
          (map (fn [[id operation source argument]]
                 (str id "\t" operation "\t" (b64 source) "\t" (b64 argument) "\n"))
               cases))))

(defn compare-results
  "Compares normalized line-oriented observations without loading large trees in memory."
  [expected actual]
  (with-open [^BufferedReader left (Files/newBufferedReader (paths/path expected) StandardCharsets/UTF_8)
              ^BufferedReader right (Files/newBufferedReader (paths/path actual) StandardCharsets/UTF_8)]
    (loop [line-number 1 matched 0]
      (let [expected-line (.readLine left)
            actual-line (.readLine right)]
        (cond
          (and (nil? expected-line) (nil? actual-line))
          {:matched matched}

          (= expected-line actual-line)
          (recur (inc line-number) (inc matched))

          :else
          {:matched matched
           :mismatch {:line line-number
                      :expected expected-line
                      :actual actual-line}})))))

(defn- assert-equal! [subject expected actual]
  (let [comparison (compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! (str "Packaged " subject " behavior differs from the upstream JVM oracle")
             {:expected (str expected) :actual (str actual) :comparison comparison
              :mismatch mismatch}))
    comparison))

(defn- prove-perturbation! [^Path oracle ^Path perturbed]
  (Files/copy oracle perturbed
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
  (Files/writeString perturbed "@perturbed\tPROOF\tWA==\n"
                     (into-array OpenOption [StandardOpenOption/APPEND]))
  (let [comparison (compare-results oracle perturbed)]
    (when-not (:mismatch comparison)
      (fail! "Differential comparator did not detect a deliberate perturbation"
             {:oracle (str oracle) :perturbed (str perturbed)}))
    comparison))

(defn- run-independent-probes!
  [run-command! probes]
  (concurrency/mapv-ordered
   :differential-probes
   (fn [{:keys [name command directory]}]
     (assoc (run-command! {:command command :directory directory}) :probe name))
   probes))

(defn- verify-parser-differential!
  "Retains the complete packaged parser differential proof."
  [{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof (package-fn {:workspace-root root :profile "pkl-parser"
                                    :run-command! run-command!})
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "vibeformer" "validation-output"
                                         "differential-proof"
                                         "parser"))
         corpus (paths/resolve-path root "research" "pkl" "pkl-core" "src" "test"
                                    "files" "LanguageSnippetTests" "input")
         {:keys [cases upstream-count edge-count]} (manifest-cases corpus)
         manifest (write-manifest! (paths/resolve-path proof-root "cases.tsv") cases)
         oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                          (Files/createDirectories (make-array FileAttribute 0)))
         upstream-root (paths/resolve-path root "research" "pkl")
         upstream-main (paths/resolve-path upstream-root "pkl-parser" "build" "classes"
                                           "java" "main")
         upstream-resources (paths/resolve-path upstream-root "pkl-parser" "build"
                                                "resources" "main")
         oracle-source (paths/resolve-path root "vibeformer" "validation" "differential"
                                           "UpstreamOracle.java")
         oracle-output (paths/resolve-path proof-root "upstream.tsv")
         package-output (paths/resolve-path proof-root "package.tsv")
         perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
         classpath (str/join File/pathSeparator
                             (map str [oracle-classes upstream-main upstream-resources]))
         consumer-root (:consumer-root package-proof)
         consumer-project (paths/resolve-path consumer-root "Pkl.Parser.PackageConsumer.csproj")
         consumer-source (paths/resolve-path consumer-root "Program.cs")
         probe-source (paths/resolve-path root "vibeformer" "validation" "differential"
                                          "PackageProbe.cs")]
     (when-not (= 940 upstream-count)
       (fail! "The pinned LanguageSnippetTests corpus count changed; review the oracle selection"
              {:expected 940 :actual upstream-count :corpus (str corpus)}))
     (run-command! {:command ["./gradlew" ":pkl-parser:classes" "--console=plain"]
                    :directory upstream-root})
     (run-command! {:command ["javac" "--release" "21" "-cp" (str upstream-main)
                              "-d" (str oracle-classes) (str oracle-source)]
                    :directory root})
     (Files/copy probe-source consumer-source
                 (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
     (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                              "--verbosity:minimal" "--no-restore" "--no-incremental"
                              "-warnaserror"]
                    :directory consumer-root})
     (run-independent-probes!
      run-command!
      [{:name :upstream-java-oracle
        :command ["java" "-cp" classpath "UpstreamOracle"
                  (str manifest) (str oracle-output)]
        :directory upstream-root}
       {:name :packaged-dotnet-probe
        :command ["dotnet" "run" "--project" (str consumer-project)
                  "--no-build" "--no-restore" "--" (str manifest)
                  (str package-output)]
        :directory consumer-root}])
     (let [comparison (assert-equal! "parser" oracle-output package-output)
           perturbation (prove-perturbation! oracle-output perturbed-output)
           revision (str/trim (:output (run-command! {:command ["git" "rev-parse" "HEAD"]
                                                       :directory upstream-root})))
           summary {:upstream-revision revision
                    :package (:identity package-proof)
                    :language-snippet-cases upstream-count
                    :lexer-span-edge-cases edge-count
                    :total-cases (count cases)
                    :observations (:matched comparison)
                    :perturbation-detected-at (get-in perturbation [:mismatch :line])}]
       (println "Independent upstream/package differential passed:" (pr-str summary))
       {:package-proof package-proof
        :summary summary
        :manifest manifest
        :oracle-output oracle-output
        :package-output package-output})))

(defn- core-classpath [root]
  (let [manifest (paths/resolve-path root "vibeformer" "target"
                                     "gradle-main-inputs.tsv")
        discovery (project/read-discovery-manifest manifest)
        upstream-root (paths/resolve-path root "research" "pkl")
        core-root (paths/resolve-path upstream-root "pkl-core")]
    {:java-release (:java-release discovery)
     :java-home (:java-home discovery)
     :entries (into [(paths/resolve-path core-root "build" "classes" "java" "main")
                     (paths/resolve-path core-root "build" "resources" "main")
                     (paths/resolve-path upstream-root "pkl-parser" "build" "resources" "main")]
                    (:classpath discovery))}))

(def ^:private required-contract-families
  #{"schema.modules-classes-inheritance"
    "schema.properties-docs-annotations"
    "schema.nullable-constrained-unions"
    "schema.collections-aliases-generics-functions"
    "schema.imports-identifiers-collisions-ordering"
    "binding.constructors-settable-members"
    "binding.nested-generics-nullability"
    "binding.custom-loaders"
    "binding.unknown-incompatible-cycles"
    "codegen.polymorphism-overrides"
    "codegen.overridden-properties"
    "binding.complete-conversion-matrix"
    "binding.value-model-conversions"
    "binding.collection-matrix"
    "binding.nullable-matrix"
    "schema.methods-generic-classes"
    "schema.user-defined-generic-class-rejection"
    "schema.user-defined-generic-method-rejection"
    "schema.amends-recursive-aliases"
    "schema.amended-module-relations"})

(defn- verify-contract-evidence!
  [root ^Path evidence]
  (when-not (paths/regular-file? evidence)
    (fail! "Schema/codegen/binding contract evidence is missing"
           {:path (str evidence)}))
  (let [entries
        (->> (str/split-lines (Files/readString evidence StandardCharsets/UTF_8))
             (map-indexed vector)
             (remove (fn [[_ line]]
                       (or (str/blank? line) (str/starts-with? line "#"))))
             (mapv
              (fn [[index line]]
                (let [fields (str/split line #"\t" -1)]
                  (when-not (= 4 (count fields))
                    (fail! "Contract evidence row must have four tab-separated fields"
                           {:path (str evidence) :line (inc index) :value line}))
                  (let [[status family source detail] fields]
                    (when-not (#{"selected" "pending-in-scope"} status)
                      (fail! "Contract evidence row has an unsupported status"
                             {:path (str evidence) :line (inc index) :status status}))
                    (when (some str/blank? [family source detail])
                      (fail! "Contract evidence row contains a blank required field"
                             {:path (str evidence) :line (inc index) :value line}))
                    (when-not (and (str/starts-with? source "research/pkl/")
                                   (str/includes? source "/src/test/"))
                      (fail! "Contract evidence must cite an upstream Pkl test or fixture"
                             {:path (str evidence) :line (inc index) :source source}))
                    (let [source-path (paths/resolve-path root source)]
                      (when-not (paths/regular-file? source-path)
                        (fail! "Contract evidence references a missing upstream source"
                               {:path (str evidence) :line (inc index)
                                :source (str source-path)})))
                    {:status status :family family :source source :detail detail})))))
        selected (filterv #(= "selected" (:status %)) entries)
        pending (filterv #(= "pending-in-scope" (:status %)) entries)
        missing (sort (remove (set (map :family selected)) required-contract-families))]
    (when (seq missing)
      (fail! "Contract evidence omits required selected behavior families"
             {:path (str evidence) :missing missing}))
    {:selected (count selected)
     :pending-in-scope (count pending)
     :families (mapv :family entries)}))

(def ^:private required-loading-contract-families
  #{"source.module-forms"
    "loading.local-file-resolution"
    "loading.local-relative-import"
    "loading.local-resource"
    "loading.directory-listing"
    "loading.directory-globbing"
    "loading.modulepath-directory"
    "loading.modulepath-archive"
    "loading.standard-library"
    "loading.http-modules-resources"
    "loading.https-modules-resources"
    "http.redirects-policy-order"
    "http.rewrites-headers"
    "http.proxy-settings"
    "package.assets"
    "package.directory-listing-globbing"
    "package.metadata"
    "package.checksums"
    "package.cache-offline"
    "package.transitive-dependencies"
    "project.declared-dependencies"
    "project.projectpackage-imports-resources"
    "readers.custom-module"
    "readers.custom-resource"
    "readers.configured-external-module"
    "readers.configured-external-resource"
    "resources.environment"
    "resources.external-property"
    "adaptation.assembly-modules"
    "adaptation.embedded-resources"
    "evaluator.builder-mutations-getters"
    "evaluator.builder-invalid-combinations"
    "settings.evaluator"
    "settings.project"
    "settings.user"
    "settings.apply-from-project"
    "security.root-confinement"
    "security.module-allowlist"
    "security.resource-resolve-read"
    "security.import-trust"
    "security.traversal-rejection"
    "security.scheme-policy"
    "adaptation.platform-path-uri"
    "errors.missing-element"
    "errors.invalid-uri-scheme"
    "errors.io"
    "errors.checksum"
    "errors.dependency-cycle"
    "errors.output-type"
    "lifecycle.evaluator-http-close"
    "lifecycle.custom-reader-ownership"
    "adaptation.disposal-ownership"})

(defn- contract-lines [^Path file]
  (->> (str/split-lines (Files/readString file StandardCharsets/UTF_8))
       (map-indexed vector)
       (remove (fn [[_ line]]
                 (or (str/blank? line) (str/starts-with? line "#"))))))

(defn- verify-loading-contract-evidence!
  [root ^Path evidence ^Path expectations]
  (doseq [[kind path] [[:evidence evidence] [:expectations expectations]]]
    (when-not (paths/regular-file? path)
      (fail! "Loading/policy/configuration contract input is missing"
             {:kind kind :path (str path)})))
  (let [expectation-entries
        (mapv
         (fn [[index line]]
           (let [fields (str/split line #"\t" -1)]
             (when-not (= 4 (count fields))
               (fail! "Loading contract expectation must have four tab-separated fields"
                      {:path (str expectations) :line (inc index) :value line}))
             (let [[comparison observation kind expectation] fields]
               (when-not (#{"jvm-shared" "dotnet-adaptation"} comparison)
                 (fail! "Loading contract expectation has an unsupported comparison class"
                        {:path (str expectations) :line (inc index)
                         :comparison comparison}))
               (when (some str/blank? [observation kind expectation])
                 (fail! "Loading contract expectation contains a blank required field"
                        {:path (str expectations) :line (inc index) :value line}))
               {:comparison comparison :observation observation
                :kind kind :expectation expectation})))
         (contract-lines expectations))
        duplicate-expectations
        (->> expectation-entries
             (group-by :observation)
             (keep (fn [[observation entries]]
                     (when (> (count entries) 1) observation)))
             sort vec)
        expectation-index (into {} (map (juxt :observation identity) expectation-entries))
        evidence-entries
        (mapv
         (fn [[index line]]
           (let [fields (str/split line #"\t" -1)]
             (when-not (= 7 (count fields))
               (fail! "Loading contract evidence must have seven tab-separated fields"
                      {:path (str evidence) :line (inc index) :value line}))
             (let [[implementation comparison family observation source fixture detail] fields]
               (when-not (#{"existing-evidence" "pending-in-scope"} implementation)
                 (fail! "Loading contract evidence has an unsupported implementation status"
                        {:path (str evidence) :line (inc index)
                         :implementation implementation}))
               (when-not (#{"jvm-shared" "dotnet-adaptation"} comparison)
                 (fail! "Loading contract evidence has an unsupported comparison class"
                        {:path (str evidence) :line (inc index)
                         :comparison comparison}))
               (when (some str/blank? [family observation source fixture detail])
                 (fail! "Loading contract evidence contains a blank required field"
                        {:path (str evidence) :line (inc index) :value line}))
               (when-not (and (str/starts-with? source "research/pkl/")
                              (str/includes? source "/src/test/"))
                 (fail! "Loading contract evidence must cite an upstream Pkl test or fixture"
                        {:path (str evidence) :line (inc index) :source source}))
               (doseq [[input-kind input] [[:source source] [:fixture fixture]]]
                 (let [input-path (paths/resolve-path root input)]
                   (when-not (paths/regular-file? input-path)
                     (fail! "Loading contract evidence references a missing input"
                            {:path (str evidence) :line (inc index)
                             :input-kind input-kind :input (str input-path)}))))
               (let [expected (get expectation-index observation)]
                 (when-not expected
                   (fail! "Loading contract evidence references an unknown expectation"
                          {:path (str evidence) :line (inc index)
                           :observation observation}))
                 (when-not (= comparison (:comparison expected))
                   (fail! "Loading contract evidence and expectation comparison classes differ"
                          {:path (str evidence) :line (inc index)
                           :observation observation :evidence comparison
                           :expectation (:comparison expected)})))
               {:implementation implementation :comparison comparison :family family
                :observation observation :source source :fixture fixture :detail detail})))
         (contract-lines evidence))
        missing-families
        (sort (remove (set (map :family evidence-entries))
                      required-loading-contract-families))
        uncited-expectations
        (sort (remove (set (map :observation evidence-entries))
                      (map :observation expectation-entries)))
        shared (filterv #(= "jvm-shared" (:comparison %)) expectation-entries)
        adaptations (filterv #(= "dotnet-adaptation" (:comparison %)) expectation-entries)]
    (when (seq duplicate-expectations)
      (fail! "Loading contract contains duplicate observation expectations"
             {:path (str expectations) :duplicates duplicate-expectations}))
    (when (seq missing-families)
      (fail! "Loading contract omits required in-scope behavior families"
             {:path (str evidence) :missing missing-families}))
    (when (seq uncited-expectations)
      (fail! "Loading contract contains expectations without source-backed evidence"
             {:path (str expectations) :uncited uncited-expectations}))
    {:evidence evidence-entries
     :expectations expectation-entries
     :shared shared
     :adaptations adaptations
     :summary {:families (count evidence-entries)
               :existing-evidence (count (filter #(= "existing-evidence"
                                                     (:implementation %))
                                                  evidence-entries))
               :pending-in-scope (count (filter #(= "pending-in-scope"
                                                     (:implementation %))
                                                  evidence-entries))
               :jvm-shared-families (count (filter #(= "jvm-shared" (:comparison %))
                                                   evidence-entries))
               :dotnet-adaptation-families (count (filter #(= "dotnet-adaptation"
                                                               (:comparison %))
                                                         evidence-entries))
               :jvm-shared-observations (count shared)
               :dotnet-adaptation-observations (count adaptations)}}))

(defn- write-loading-expectations! [^Path output entries]
  (write-text!
   output
   (apply str
          (map (fn [{:keys [observation kind expectation]}]
                 (str observation "\t" kind "\t" (b64 expectation) "\n"))
               entries))))

(declare package-only-project restore-package-only-project!)

(def ^:private dotnet-loading-observations
  #{"module-source/forms"
    "local/import-resource"
    "local/list-glob"
    "modulepath/directory-archive"
    "stdlib/import"
    "custom/module-resource-lifecycle"
    "resources/environment-property"
    "security/policy"
    "https/rewrite-redirect-headers"
    "package/assets-cache-integrity"
    "project/projectpackage-dependencies"
    "network/package-errors"
    "lifecycle/close"
    "assembly/module-loading"
    "embedded/resource-loading"
    "platform/path-uri-policy"
    "ownership/disposal"})

(defn- loading-package-project
  [package-id version target-framework]
  (str/replace
   (package-only-project package-id version target-framework)
   "</Project>"
   (str "  <ItemGroup>\n"
        "    <EmbeddedResource Include=\""
        "fixtures/modules/main.pkl"
        "\" LogicalName=\"Contract.Modules.main.pkl\" />\n"
        "    <EmbeddedResource Include=\""
        "fixtures/modules/dependency.pkl"
        "\" LogicalName=\"Contract.Modules.dependency.pkl\" />\n"
        "    <EmbeddedResource Include=\""
        "fixtures/resources/payload.txt"
        "\" LogicalName=\"Contract.Resources.payload.txt\" />\n"
        "    <EmbeddedResource Include=\""
        "fixtures/resources/second.txt"
        "\" LogicalName=\"Contract.Resources.second.txt\" />\n"
        "  </ItemGroup>\n"
        "</Project>")))

(defn- verify-loading-contract!
  [{:keys [root package-proof run-command! java-release java-home entries]}]
  (let [fixtures (paths/resolve-path root "vibeformer" "validation" "loading-contract")
        dotnet-fixtures (paths/resolve-path fixtures "fixtures" "dotnet")
        evidence (paths/resolve-path fixtures "ContractEvidence.tsv")
        expectations (paths/resolve-path fixtures "ContractExpectations.tsv")
        oracle-source (paths/resolve-path fixtures "LoadingContractUpstreamOracle.java")
        package-probe-source (paths/resolve-path fixtures "LoadingContractDotNetProbe.cs")
        contract (verify-loading-contract-evidence! root evidence expectations)
        proof-root (harness/clean-directory!
                    (paths/resolve-path root "vibeformer" "validation-output"
                                        "differential-proof" "loading-contract"))
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        oracle-output (paths/resolve-path proof-root "upstream.tsv")
        expected-output (write-loading-expectations!
                         (paths/resolve-path proof-root "expected.tsv")
                         (:shared contract))
        package-entries (filterv #(contains? dotnet-loading-observations
                                             (:observation %))
                                 (:expectations contract))
        package-expected-output (write-loading-expectations!
                                 (paths/resolve-path proof-root "package-expected.tsv")
                                 package-entries)
        package-output (paths/resolve-path proof-root "package.tsv")
        package-perturbed-output (paths/resolve-path proof-root "package-perturbed.tsv")
        perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
        work (doto (paths/resolve-path proof-root "upstream-work")
               (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str entries))
        classpath (str/join File/pathSeparator (map str (cons oracle-classes entries)))
        javac (paths/resolve-path java-home "bin" "javac")
        java (paths/resolve-path java-home "bin" "java")
        upstream-root (paths/resolve-path root "research" "pkl")
        package-build (paths/resolve-path upstream-root "pkl-commons-test" "build")
        package-root (doto (paths/resolve-path proof-root "package-consumer")
                       (Files/createDirectories (make-array FileAttribute 0)))
        package-work (paths/resolve-path package-root "work")
        package-project (paths/resolve-path package-root "LoadingContractConsumer.csproj")
        package-source (paths/resolve-path package-root "Program.cs")
        package-config (paths/resolve-path package-root "NuGet.Config")
        packages (doto (paths/resolve-path proof-root "package-cache")
                   (Files/createDirectories (make-array FileAttribute 0)))
        source-package-config (paths/resolve-path (:consumer-root package-proof) "NuGet.Config")
        installed-consumer-project
        (paths/resolve-path (:consumer-root package-proof) "Pkl.Core.PackageConsumer.csproj")
        target-match (re-find #"<TargetFramework>(net\d+\.\d+)</TargetFramework>"
                              (Files/readString installed-consumer-project))
        target-framework (second target-match)
        identities (get-in package-proof [:dependency-proof :packages])
        {:keys [id version]} (:identity package-proof)]
    (doseq [required [oracle-source package-probe-source source-package-config
                      (paths/resolve-path dotnet-fixtures "modules" "main.pkl")
                      (paths/resolve-path dotnet-fixtures "modules" "dependency.pkl")
                      (paths/resolve-path dotnet-fixtures "resources" "payload.txt")
                      (paths/resolve-path dotnet-fixtures "resources" "second.txt")]]
      (when-not (paths/regular-file? required)
        (fail! "Loading contract proof input is missing" {:path (str required)})))
    (when-not target-framework
      (fail! "Could not determine the loading consumer target framework"
             {:project (str installed-consumer-project)}))
    (when-not (= 17 (count package-entries))
      (fail! "The package-only loading observation selection changed"
             {:expected 17 :actual (count package-entries)
              :observations (mapv :observation package-entries)}))
    (run-command! {:command ["./gradlew" ":pkl-commons-test:processResources" "--console=plain"]
                   :directory upstream-root})
    (run-command! {:command [(str javac) "--release" (str java-release)
                             "-cp" compile-classpath "-d" (str oracle-classes)
                             (str oracle-source)]
                   :directory root})
    (run-command! {:command [(str java) "-cp" classpath "LoadingContractUpstreamOracle"
                             (str root) (str oracle-output) (str work)]
                   :directory root})
    (doseq [[source relative]
            [[(paths/resolve-path dotnet-fixtures "modules" "main.pkl")
              ["fixtures" "modules" "main.pkl"]]
             [(paths/resolve-path dotnet-fixtures "modules" "dependency.pkl")
              ["fixtures" "modules" "dependency.pkl"]]
             [(paths/resolve-path dotnet-fixtures "resources" "payload.txt")
              ["fixtures" "resources" "payload.txt"]]
             [(paths/resolve-path dotnet-fixtures "resources" "second.txt")
              ["fixtures" "resources" "second.txt"]]]]
      (let [destination (apply paths/resolve-path package-root relative)]
        (Files/createDirectories (.getParent destination) (make-array FileAttribute 0))
        (Files/copy source destination
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (write-text! package-project (loading-package-project id version target-framework))
    (Files/copy package-probe-source package-source
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (Files/copy source-package-config package-config
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (let [package-dependencies
          (restore-package-only-project! run-command! package-project package-config
                                         packages package-proof identities)]
      (run-command! {:command ["dotnet" "build" (str package-project) "--nologo"
                               "--verbosity:minimal" "--no-restore" "--no-incremental"
                               "-warnaserror"]
                     :directory package-root})
      (let [package-run
            (run-command! {:command ["dotnet" "run" "--project" (str package-project)
                                     "--no-build" "--no-restore" "--"
                                     (str (paths/resolve-path fixtures "fixtures"))
                                     (str package-output) (str package-work)
                                     (str package-build)]
                           :directory package-root})]
        (when-not (str/includes? (:output package-run)
                                 "Package-only loading, package, and policy validation passed.")
          (fail! "Package-only loading probe did not report successful validation"
                 {:output (:output package-run)}))
    (let [comparison (assert-equal! "Pkl loading/policy/configuration contract"
                                    expected-output oracle-output)
          package-comparison (assert-equal! "Pkl loading/package/policy contract"
                                            package-expected-output package-output)
          perturbation (prove-perturbation! expected-output perturbed-output)
          package-perturbation (prove-perturbation! package-expected-output
                                                    package-perturbed-output)]
      {:summary (assoc (:summary contract)
                       :observations (:matched comparison)
                       :package-observations (:matched package-comparison)
                       :package-perturbation-detected-at
                       (get-in package-perturbation [:mismatch :line])
                       :perturbation-detected-at (get-in perturbation [:mismatch :line]))
       :evidence evidence
       :expectations expectations
       :expected-output expected-output
       :oracle-output oracle-output
       :package-output package-output
       :package-dependencies package-dependencies})))))

(defn- package-only-project [package-id version target-framework]
  (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
       "  <PropertyGroup>\n"
       "    <OutputType>Exe</OutputType>\n"
       "    <TargetFramework>" target-framework "</TargetFramework>\n"
       "    <Nullable>enable</Nullable>\n"
       "    <ImplicitUsings>disable</ImplicitUsings>\n"
       "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
       "    <Deterministic>true</Deterministic>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       "    <PackageReference Include=\"" package-id "\" Version=\"" version "\" />\n"
       "  </ItemGroup>\n"
       "</Project>\n"))

(defn- restore-package-only-project!
  [run-command! ^Path project ^Path nuget-config ^Path packages package-proof identities]
  (run-command! {:command ["dotnet" "restore" (str project)
                           "--configfile" (str nuget-config)
                           "--packages" (str packages)
                           "--no-cache" "--force" "--force-evaluate"]
                 :directory (.getParent project)})
  (packaging/inspect-consumer-dependencies!
   project (paths/resolve-path (.getParent project) "obj" "project.assets.json")
   packages (:identity package-proof) identities))

(defn- verify-schema-codegen-binding!
  [{:keys [root package-proof run-command! java-release java-home entries]}]
  (let [proof-root (harness/clean-directory!
                    (paths/resolve-path root "vibeformer" "validation-output"
                                        "differential-proof" "schema-codegen-binding"))
        fixtures (paths/resolve-path root "vibeformer" "validation" "schema-codegen")
        contract-evidence (paths/resolve-path fixtures "ContractEvidence.tsv")
        evidence-summary (verify-contract-evidence! root contract-evidence)
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        oracle-source (paths/resolve-path fixtures "SchemaUpstreamOracle.java")
        oracle-output (paths/resolve-path proof-root "upstream.tsv")
        package-output (paths/resolve-path proof-root "package.tsv")
        perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
        config-manifest (paths/resolve-path proof-root "pkl-config-java-main-inputs.tsv")
        config-discovery (project/discover-main!
                          {:workspace-root root
                           :manifest config-manifest
                           :project-root "research/pkl"
                           :gradle-project ":pkl-config-java"
                           :run-command! run-command!})
        config-classes (paths/resolve-path root "research" "pkl" "pkl-config-java"
                                           "build" "classes" "java" "main")
        oracle-entries (vec (distinct (concat [config-classes] entries
                                              (:classpath config-discovery))))
        toolchain-check
        (when-not (and (= java-release (:java-release config-discovery))
                       (= (paths/absolute java-home)
                          (paths/absolute (:java-home config-discovery))))
          (fail! "Pkl.Core and pkl-config-java oracle toolchains differ"
                 {:core {:java-release java-release :java-home (str java-home)}
                  :config {:java-release (:java-release config-discovery)
                           :java-home (str (:java-home config-discovery))}}))
        compile-classpath (str/join File/pathSeparator (map str oracle-entries))
        classpath (str/join File/pathSeparator (map str (cons oracle-classes oracle-entries)))
        javac (paths/resolve-path java-home "bin" "javac")
        java (paths/resolve-path java-home "bin" "java")
        generator-root (doto (paths/resolve-path proof-root "package-generator")
                         (Files/createDirectories (make-array FileAttribute 0)))
        generated-root (doto (paths/resolve-path proof-root "emitted-csharp")
                         (Files/createDirectories (make-array FileAttribute 0)))
        consumer-root (doto (paths/resolve-path proof-root "generated-consumer")
                        (Files/createDirectories (make-array FileAttribute 0)))
        generator-packages (doto (paths/resolve-path proof-root "generator-packages")
                             (Files/createDirectories (make-array FileAttribute 0)))
        consumer-packages (doto (paths/resolve-path proof-root "consumer-packages")
                            (Files/createDirectories (make-array FileAttribute 0)))
        generator-project (paths/resolve-path generator-root "PackageSchemaGenerator.csproj")
        consumer-project (paths/resolve-path consumer-root "GeneratedPackageConsumer.csproj")
        generator-config (paths/resolve-path generator-root "NuGet.Config")
        consumer-config (paths/resolve-path consumer-root "NuGet.Config")
        package-config (paths/resolve-path (:consumer-root package-proof) "NuGet.Config")
        collision-diagnostics (paths/resolve-path proof-root "collision-diagnostics.txt")
        binding-diagnostics (paths/resolve-path proof-root "binding-diagnostics.txt")
        identities (get-in package-proof [:dependency-proof :packages])
        {:keys [id version]} (:identity package-proof)
        installed-consumer-project
        (paths/resolve-path (:consumer-root package-proof) "Pkl.Core.PackageConsumer.csproj")
        target-match (re-find #"<TargetFramework>(net\d+\.\d+)</TargetFramework>"
                              (Files/readString installed-consumer-project))
        target-framework (second target-match)
        target-framework-check
        (when-not target-framework
          (fail! "Could not determine the installed package-consumer target framework"
                 {:project (str installed-consumer-project)}))
        project-contents (package-only-project id version target-framework)]
    (doseq [required [oracle-source
                      contract-evidence
                      (paths/resolve-path fixtures "SchemaGeneratorProbe.cs")
                      (paths/resolve-path fixtures "GeneratedConsumer.cs")
                      package-config]]
      (when-not (paths/regular-file? required)
        (fail! "Schema/codegen/binding proof input is missing" {:path (str required)})))

    (run-command! {:command [(str javac) "--release" (str java-release)
                             "-cp" compile-classpath "-d" (str oracle-classes)
                             (str oracle-source)]
                   :directory root})
    (run-command! {:command [(str java) "-cp" classpath "SchemaUpstreamOracle"
                             (str fixtures) (str oracle-output)]
                   :directory root})

    (write-text! generator-project project-contents)
    (Files/copy (paths/resolve-path fixtures "SchemaGeneratorProbe.cs")
                (paths/resolve-path generator-root "Program.cs")
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (Files/copy package-config generator-config
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (let [generator-dependencies
          (restore-package-only-project! run-command! generator-project generator-config
                                         generator-packages package-proof identities)]
      (run-command! {:command ["dotnet" "build" (str generator-project) "--nologo"
                               "--verbosity:minimal" "--no-restore" "--no-incremental"
                               "-warnaserror"]
                     :directory generator-root})
      (let [generator-run
            (run-command! {:command ["dotnet" "run" "--project" (str generator-project)
                                     "--no-build" "--no-restore" "--"
                                     (str fixtures) (str generated-root) (str package-output)
                                     (str collision-diagnostics)]
                           :directory generator-root})]
        (when-not (str/includes? (:output generator-run)
                                 "Package-only schema traversal and deterministic C# generation passed.")
          (fail! "Package-only schema generator did not report successful validation"
                 {:output (:output generator-run)}))

        (write-text! consumer-project project-contents)
        (Files/copy (paths/resolve-path fixtures "GeneratedConsumer.cs")
                    (paths/resolve-path consumer-root "Program.cs")
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
        (Files/copy package-config consumer-config
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
        (let [generated-files
              (mapv #(paths/resolve-path generated-root %)
                    ["ContractBase.g.cs" "ContractImported.g.cs" "ContractMain.g.cs"
                     "PolymorphicLib.g.cs" "PolymorphicModuleTest.g.cs"
                     "OverriddenProperty.g.cs" "SchemaMethods.g.cs"])]
          (doseq [^Path source generated-files]
            (when-not (paths/regular-file? source)
              (fail! "Package generator did not emit an expected C# source" {:path (str source)}))
            (Files/copy source (paths/resolve-path consumer-root (str (.getFileName source)))
                        (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))
          (let [consumer-dependencies
                (restore-package-only-project! run-command! consumer-project consumer-config
                                               consumer-packages package-proof identities)]
            (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                                     "--verbosity:minimal" "--no-restore" "--no-incremental"
                                     "-warnaserror"]
                           :directory consumer-root})
            (let [consumer-run
                  (run-command! {:command ["dotnet" "run" "--project" (str consumer-project)
                                           "--no-build" "--no-restore" "--"
                                           (str fixtures) (str package-output)
                                           (str binding-diagnostics)]
                                 :directory consumer-root})]
              (when-not (str/includes? (:output consumer-run)
                                       "Independently compiled generated C# binding consumer passed.")
                (fail! "Generated package-only consumer did not report successful validation"
                       {:output (:output consumer-run)}))
              (let [comparison (assert-equal! "Pkl schema/codegen/binding" oracle-output package-output)
                    perturbation (prove-perturbation! oracle-output perturbed-output)
                    binding-report (Files/readString binding-diagnostics)]
                (when (str/blank? binding-report)
                  (fail! "Generated consumer did not retain focused binding diagnostics"
                         {:path (str binding-diagnostics)}))
                {:summary {:schemas 9
                           :generated-files (count generated-files)
                           :observations (:matched comparison)
                           :generated-contract-observations 1
                           :codegen-failure-observations 6
                           :binding-observations 2
                           :independent-binding-failure-observations 14
                           :binding-failure-cases 21
                           :contract-evidence evidence-summary
                           :perturbation-detected-at (get-in perturbation [:mismatch :line])}
                 :oracle-output oracle-output
                 :package-output package-output
                 :generated-root generated-root
                 :collision-diagnostics collision-diagnostics
                 :binding-diagnostics binding-diagnostics
                 :generator-dependencies generator-dependencies
                 :consumer-dependencies consumer-dependencies}))))))))

(defn- verify-core-differential!
  "Runs representative evaluator/value-model cases in isolated upstream and package processes."
  [{:keys [workspace-root core-package-fn run-command!]
     :or {core-package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
  (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
        package-proof (core-package-fn {:workspace-root root
                                        :profile "pkl-core-value-model"
                                        :run-command! run-command!})
        proof-root (harness/clean-directory!
                    (paths/resolve-path root "vibeformer" "validation-output"
                                        "differential-proof" "core"))
        manifest (write-core-manifest! (paths/resolve-path proof-root "cases.tsv") core-cases)
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        upstream-root (paths/resolve-path root "research" "pkl")
        oracle-source (paths/resolve-path root "vibeformer" "validation" "differential"
                                          "CoreUpstreamOracle.java")
        oracle-output (paths/resolve-path proof-root "upstream.tsv")
        package-output (paths/resolve-path proof-root "package.tsv")
        perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
        {:keys [java-release java-home entries]} (core-classpath root)
        classpath (str/join File/pathSeparator (map str (cons oracle-classes entries)))
        compile-classpath (str/join File/pathSeparator (map str entries))
        javac (paths/resolve-path java-home "bin" "javac")
        java (paths/resolve-path java-home "bin" "java")
        consumer-root (:consumer-root package-proof)
        consumer-project (paths/resolve-path consumer-root "Pkl.Core.PackageConsumer.csproj")
        consumer-source (paths/resolve-path consumer-root "Program.cs")
        probe-source (paths/resolve-path root "vibeformer" "validation" "differential"
                                         "CorePackageProbe.cs")]
    (when-not (= 19 (count core-cases))
      (fail! "The pinned Pkl.Core differential case count changed; review the oracle selection"
             {:expected 19 :actual (count core-cases)}))
    (run-command! {:command ["./gradlew" ":pkl-core:classes" "--console=plain"]
                   :directory upstream-root})
    (run-command! {:command [(str javac) "--release" (str java-release)
                             "-cp" compile-classpath "-d" (str oracle-classes)
                             (str oracle-source)]
                   :directory root})
    (Files/copy probe-source consumer-source
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                             "--verbosity:minimal" "--no-restore" "--no-incremental"
                             "-warnaserror"]
                   :directory consumer-root})
    (run-independent-probes!
     run-command!
     [{:name :upstream-core-java-oracle
       :command [(str java) "-cp" classpath "CoreUpstreamOracle"
                 (str manifest) (str oracle-output)]
       :directory upstream-root}
      {:name :packaged-core-dotnet-probe
       :command ["dotnet" "run" "--project" (str consumer-project)
                 "--no-build" "--no-restore" "--" (str manifest)
                 (str package-output)]
       :directory consumer-root}])
          (let [loading-contract (verify-loading-contract!
                            {:root root :package-proof package-proof
                             :run-command! run-command!
                             :java-release java-release :java-home java-home :entries entries})
          schema-proof (verify-schema-codegen-binding!
                        {:root root :package-proof package-proof :run-command! run-command!
                         :java-release java-release :java-home java-home :entries entries})
          comparison (assert-equal! "Pkl.Core" oracle-output package-output)
          perturbation (prove-perturbation! oracle-output perturbed-output)
          revision (str/trim (:output (run-command! {:command ["git" "rev-parse" "HEAD"]
                                                      :directory upstream-root})))
          summary {:upstream-revision revision
                   :package (:identity package-proof)
                   :cases (count core-cases)
                   :value-model-observations 6
                   :evaluation-cases 7
                   :output-cases 4
                   :value-export-cases 2
                   :loading-security-cases 3
                   :error-cases 4
                   :observations (:matched comparison)
                   :loading-policy-configuration-contract (:summary loading-contract)
                   :schema-codegen-binding (:summary schema-proof)
                   :perturbation-detected-at (get-in perturbation [:mismatch :line])}]
      (println "Independent upstream/package Pkl.Core differential passed:" (pr-str summary))
      {:package-proof package-proof
       :loading-policy-configuration-contract loading-contract
       :schema-codegen-binding schema-proof
       :summary summary
       :manifest manifest
       :oracle-output oracle-output
       :package-output package-output})))

(defn- verify-differential-with-executor!
  "Runs the complete parser proof, then the representative packaged Pkl.Core proof."
  [options]
  (let [parser (verify-parser-differential! options)
        core (verify-core-differential! options)
        summary {:parser (:summary parser) :core (:summary core)}]
    (println "Independent upstream/package differential suite passed:" (pr-str summary))
    {:parser parser :core core :summary summary}))

(defn verify-differential!
  "Regenerates, packs, consumes, and independently compares the complete parser
  corpus plus representative Pkl.Core evaluator/value-model behavior."
  ([] (verify-differential! {}))
  ([options]
   (concurrency/call-with-executor
    {:worker-count (:worker-count options)}
    #(verify-differential-with-executor! options))))
