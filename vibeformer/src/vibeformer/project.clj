(ns vibeformer.project
  (:require [clojure.string :as str]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files Path]))

(def ^:private manifest-header "VIBEFORMER_GRADLE_INPUTS_V1")
(def ^:private gitlink-pattern
  #"(?m)^160000 commit ([0-9a-f]{40})\s+research/pkl\s*$")

(defn verify-submodule!
  "Verifies that research/pkl is initialized at the revision recorded by HEAD."
  [{:keys [workspace-root run-command!] :or {run-command! process/run!}}]
  (let [root (paths/absolute workspace-root)
        submodule (paths/resolve-path root "research" "pkl")]
    (when-not (and (paths/exists? (paths/resolve-path submodule ".git"))
                   (paths/regular-file? (paths/resolve-path submodule "settings.gradle.kts")))
      (throw (ex-info
              "research/pkl is missing or not initialized; run git submodule update --init research/pkl"
              {:kind :submodule-missing :path (str submodule)})))
    (let [tree-output (:output (run-command!
                                {:command ["git" "ls-tree" "HEAD" "research/pkl"]
                                 :directory root}))
          expected (second (re-find gitlink-pattern tree-output))]
      (when-not expected
        (throw (ex-info
                "HEAD does not contain the expected research/pkl gitlink"
                {:kind :gitlink-missing :output tree-output})))
      (let [actual (str/trim
                    (:output (run-command!
                              {:command ["git" "-C" (str submodule) "rev-parse" "HEAD"]
                               :directory root})))]
        (when-not (= expected actual)
          (throw (ex-info
                  (str "research/pkl revision mismatch: expected " expected ", found " actual)
                  {:kind :submodule-revision-mismatch
                   :expected expected
                   :actual actual})))
        {:path submodule :revision expected}))))

(defn- parse-record
  [line]
  (let [[kind value extra] (str/split line #"\t" 3)]
    (when (or extra (str/blank? kind) (str/blank? value))
      (throw (ex-info
              (str "Invalid Gradle discovery record: " (pr-str line))
              {:kind :invalid-discovery-manifest :line line})))
    [(keyword kind) (paths/absolute value)]))

(defn read-discovery-manifest
  "Reads and validates the Gradle-derived production input manifest."
  [manifest]
  (when-not (paths/regular-file? manifest)
    (throw (ex-info
            (str "Gradle did not create its discovery manifest: " manifest)
            {:kind :discovery-manifest-missing :path (str manifest)})))
  (let [[header & lines] (str/split-lines (slurp (str manifest)))]
    (when-not (= manifest-header header)
      (throw (ex-info
              (str "Unsupported Gradle discovery manifest header: " (pr-str header))
              {:kind :invalid-discovery-manifest :header header})))
    (let [records (mapv parse-record (remove str/blank? lines))
          grouped (group-by first records)
          values #(->> (get grouped %)
                       (map second)
                       distinct
                       (sort-by str)
                       vec)
          discovery {:java-home (first (values :java-home))
                     :java-sources (values :source)
                     :resources (values :resource)
                     :classpath (values :classpath)}]
      (when-not (= 1 (count (values :java-home)))
        (throw (ex-info
                "Gradle discovery must report exactly one Java toolchain"
                {:kind :toolchain-missing})))
      (when-not (paths/directory? (:java-home discovery))
        (throw (ex-info
                (str "Gradle-reported Java toolchain is missing: " (:java-home discovery))
                {:kind :toolchain-missing :path (str (:java-home discovery))})))
      (when-not (seq (:java-sources discovery))
        (throw (ex-info
                "Gradle reported no pkl-parser production Java sources"
                {:kind :production-sources-missing})))
      (when-not (seq (:classpath discovery))
        (throw (ex-info
                "Gradle reported an empty pkl-parser compile classpath"
                {:kind :classpath-missing})))
      (doseq [[kind inputs] [[:source (:java-sources discovery)]
                             [:resource (:resources discovery)]
                             [:classpath (:classpath discovery)]]
              ^Path input inputs]
        (when-not (Files/isRegularFile input paths/no-links)
          (throw (ex-info
                  (str "Gradle-reported " (name kind) " input is missing: " input)
                  {:kind :input-missing :input-kind kind :path (str input)}))))
      discovery)))

(defn discover-main!
  "Asks the tracked Gradle project for its resolved production inputs."
  [{:keys [workspace-root manifest run-command!] :or {run-command! process/run!}}]
  (let [root (paths/absolute workspace-root)
        pkl-root (paths/resolve-path root "research" "pkl")
        init-script (paths/resolve-path root "vibeformer" "gradle" "discover-main.gradle")
        gradlew (paths/resolve-path pkl-root "gradlew")]
    (doseq [[kind input] [[:gradle-wrapper gradlew] [:init-script init-script]]]
      (when-not (paths/regular-file? input)
        (throw (ex-info
                (str "Required " (name kind) " is missing: " input)
                {:kind :tooling-missing :input-kind kind :path (str input)}))))
    (try
      (run-command!
       {:command [(str gradlew)
                  "--quiet"
                  "--console=plain"
                  "--no-daemon"
                  "-I" (str init-script)
                  ":pkl-parser:vibeformerDescribeMain"
                  (str "-Pvibeformer.output=" (paths/absolute manifest))]
        :directory pkl-root})
      (catch ExceptionInfo error
        (throw (ex-info
                (str "Gradle source/classpath/toolchain discovery failed: " (.getMessage error))
                (merge {:kind :gradle-discovery-failed}
                       (select-keys (ex-data error) [:command :exit :output]))
                error))))
    (read-discovery-manifest manifest)))
