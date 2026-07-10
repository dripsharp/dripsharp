(ns vibeformer.differential
  "Independent upstream-JVM versus packaged-.NET parser behavior validation."
  (:require [clojure.string :as str]
            [vibeformer.harness :as harness]
            [vibeformer.packaging :as packaging]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
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

(defn- assert-equal! [expected actual]
  (let [comparison (compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! "Packaged parser behavior differs from the upstream JVM oracle"
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

(defn verify-differential!
  "Regenerates, packs, consumes, runs the upstream oracle, and compares observations."
  ([] (verify-differential! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof (package-fn)
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "vibeformer" "target" "differential-proof"))
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
     (run-command! {:command ["java" "-cp" classpath "UpstreamOracle"
                              (str manifest) (str oracle-output)]
                    :directory upstream-root})
     (Files/copy probe-source consumer-source
                 (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
     (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                              "--verbosity:minimal" "--no-restore" "--no-incremental"
                              "-warnaserror"]
                    :directory consumer-root})
     (run-command! {:command ["dotnet" "run" "--project" (str consumer-project)
                              "--no-build" "--no-restore" "--" (str manifest)
                              (str package-output)]
                    :directory consumer-root})
     (let [comparison (assert-equal! oracle-output package-output)
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
        :package-output package-output}))))
