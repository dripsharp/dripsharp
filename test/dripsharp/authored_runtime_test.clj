(ns dripsharp.authored-runtime-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- authored-runtime-sources
  "The authored C# runtimes: the shared runtime/ and each target's runtime."
  []
  (->> (cons (io/file "runtime")
             (keep #(let [runtime (io/file % "runtime")]
                      (when (.isDirectory runtime) runtime))
                   (.listFiles (io/file "targets"))))
       (mapcat file-seq)
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".cs")))
       (sort-by str)))

;; Calls that force or block on a collection. Memory pressure reporting
;; (GC.AddMemoryPressure) only informs the collector's own policy.
(def ^:private forced-collection
  #"\bGC\s*\.\s*(?:Collect|WaitForPendingFinalizers|TryStartNoGCRegion|RefreshMemoryLimit)\b")

(defn- forced-collections
  "The forced collections in C# source text, as line:call strings. Matches
  the whole text, so calls split across lines count too; // comments do not."
  [source]
  (let [code (str/replace source #"//[^\n]*" "")
        matcher (re-matcher forced-collection code)]
    (loop [found []]
      (if (.find matcher)
        (let [line (inc (count (filter #{\newline} (subs code 0 (.start matcher)))))]
          (recur (conj found (str line ": " (str/replace (.group matcher) #"\s+" "")))))
        found))))

;; Product code collects only where the upstream Java does (a translated
;; System.gc()); the authored runtimes add no collections of their own, so
;; memory stays a matter of the runtime's collector policy.
(deftest authored-runtimes-never-force-a-collection
  (let [sources (authored-runtime-sources)]
    (is (some #(= "DripSharp.PdfCarton.Fonts.Compat.cs" (.getName ^java.io.File %))
              sources))
    (is (empty? (mapcat (fn [file]
                          (map #(str file ":" %) (forced-collections (slurp file))))
                        sources)))))

(deftest forced-collection-pattern-matches-collection-calls
  (doseq [call ["GC.Collect();" "System.GC.Collect(2, GCCollectionMode.Forced);"
                "GC.WaitForPendingFinalizers();" "GC . TryStartNoGCRegion(1024);"
                "GC.RefreshMemoryLimit();"]]
    (is (re-find forced-collection call) call))
  (doseq [call ["GC.AddMemoryPressure(bytes);" "GC.KeepAlive(this);"
                "GC.CollectionCount(GC.MaxGeneration);" "GC.SuppressFinalize(this);"]]
    (is (nil? (re-find forced-collection call)) call)))

(deftest forced-collections-finds-calls-split-across-lines
  (is (= ["2: GC.Collect" "5: GC.WaitForPendingFinalizers"]
         (forced-collections
          (str "// GC.Collect() in a comment does not count\n"
               "GC\n    .Collect();\n"
               "GC.KeepAlive(this);\n"
               "GC.\nWaitForPendingFinalizers();\n")))))
