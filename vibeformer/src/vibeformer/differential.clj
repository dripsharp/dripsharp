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
   ["error/evaluation-missing-property" "EVALUATE" "result = missing\n" ""]])

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
    (when-not (= 7 (count core-cases))
      (fail! "The pinned Pkl.Core differential case count changed; review the oracle selection"
             {:expected 7 :actual (count core-cases)}))
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
    (let [comparison (assert-equal! "Pkl.Core" oracle-output package-output)
          perturbation (prove-perturbation! oracle-output perturbed-output)
          revision (str/trim (:output (run-command! {:command ["git" "rev-parse" "HEAD"]
                                                      :directory upstream-root})))
          summary {:upstream-revision revision
                   :package (:identity package-proof)
                   :cases (count core-cases)
                   :value-model-observations 5
                   :evaluation-cases 3
                   :value-export-cases 1
                   :error-cases 3
                   :observations (:matched comparison)
                   :perturbation-detected-at (get-in perturbation [:mismatch :line])}]
      (println "Independent upstream/package Pkl.Core differential passed:" (pr-str summary))
      {:package-proof package-proof
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
