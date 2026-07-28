(ns dripsharp.project
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.project-input :as project-input]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files Path]))

(def ^:private manifest-versions
  {"DRIPSHARP_GRADLE_INPUTS_V3" 3
   "DRIPSHARP_GRADLE_INPUTS_V4" 4
   "DRIPSHARP_GRADLE_INPUTS_V5" 5})
(def ^:private gradle-project-pattern
  #"^:(?:[A-Za-z0-9][A-Za-z0-9_-]*(?::[A-Za-z0-9][A-Za-z0-9_-]*)*)?$")

(defn- checkout-path
  [workspace-root project-root]
  (let [path (paths/path project-root)]
    (paths/absolute
     (if (or (.isAbsolute path) (nil? workspace-root))
       path
       (paths/resolve-path workspace-root path)))))

(defn- checkout-reference
  [workspace-root project-root]
  (let [root (some-> workspace-root paths/absolute)
        project-root (checkout-path root project-root)]
    (if (and root (.startsWith project-root root))
      (-> (str (.relativize root project-root))
          (str/replace "\\" "/"))
      (str project-root))))

(defn- initialization-guidance
  [workspace-root project-root]
  (str "clone or initialize it"
       (when workspace-root
         (str " (for a submodule, run git submodule update --init "
              (checkout-reference workspace-root project-root) ")"))))

(defn verify-checkout!
  "Verifies an arbitrary configured source checkout and exact revision.
  Unpinned directories remain valid explicit local project inputs."
  [{:keys [workspace-root project-root revision require-clean? run-command!]
    :or {run-command! process/run!}}]
  (let [project-root (checkout-path workspace-root project-root)
        reference (checkout-reference workspace-root project-root)]
    (when-not (paths/directory? project-root)
      (throw (ex-info
              (str "Configured source checkout is missing at " reference "; "
                   (initialization-guidance workspace-root project-root))
              {:kind :source-checkout-missing :path (str project-root)})))
    (if-not revision
      {:path project-root :revision nil}
      (do
        (when-not (paths/exists? (paths/resolve-path project-root ".git"))
          (throw (ex-info
                  (str "Configured source checkout is not initialized as a Git checkout at "
                       reference "; "
                       (initialization-guidance workspace-root project-root))
                  {:kind :source-checkout-uninitialized
                   :path (str project-root)
                   :expected revision})))
        (let [actual (str/trim
                      (:output
                       (run-command! {:command ["git" "rev-parse" "HEAD"]
                                      :directory project-root})))]
          (when-not (= revision actual)
            (throw (ex-info
                    (str "Configured source checkout revision mismatch at " reference
                         ": expected " revision ", found " actual
                         "; check out the configured revision before generation")
                    {:kind :source-revision-mismatch
                     :path (str project-root)
                     :expected revision :actual actual})))
          (when require-clean?
            (let [status (str/trim
                          (:output
                           (run-command! {:command ["git" "status" "--porcelain"]
                                          :directory project-root})))]
              (when-not (str/blank? status)
                (throw (ex-info
                        (str "Configured source checkout contains local changes at "
                             reference "; commit, stash, or discard them before generation")
                        {:kind :source-checkout-dirty
                         :path (str project-root) :status status})))))
          {:path project-root :revision actual})))))

(defn- parse-record
  [line]
  (let [[kind & values] (str/split line #"\t" -1)]
    (when (or (str/blank? kind) (empty? values) (some str/blank? values))
      (throw (ex-info
              (str "Invalid Gradle backend discovery record: " (pr-str line))
              {:kind :invalid-discovery-manifest :line line})))
    (into [(keyword kind)] values)))

(defn- exactly-one
  [grouped kind message]
  (let [values (->> (get grouped kind) (map second) distinct vec)]
    (when-not (= 1 (count values))
      (throw (ex-info message {:kind :invalid-discovery-manifest
                               :record-kind kind
                               :values values})))
    (first values)))

(defn- parse-positive-int
  [kind value]
  (try
    (let [parsed (Integer/parseInt value)]
      (when-not (pos? parsed)
        (throw (NumberFormatException.)))
      parsed)
    (catch NumberFormatException _
      (throw (ex-info
              (str "Gradle reported an invalid " (name kind) ": " (pr-str value))
              {:kind :invalid-discovery-manifest
               :record-kind kind
               :value value})))))

(def ^:private sha256 util/sha256-file)

(defn- validate-gradle-project!
  [gradle-project]
  (when-not (and (string? gradle-project)
                 (re-matches gradle-project-pattern gradle-project))
    (throw (ex-info
            (str "Invalid Gradle project path: " (pr-str gradle-project))
            {:kind :invalid-gradle-project :gradle-project gradle-project})))
  gradle-project)

(defn- resolve-configured-path
  [^Path project-root value default]
  (let [value (or value default)
        path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path project-root path)))))

(def ^:private windows? util/windows?)

(defn- resolve-gradle-wrapper
  "Resolves the Gradle wrapper launcher for the host platform. The wrapper ships
  as a Unix script (`gradlew`) alongside a Windows batch launcher
  (`gradlew.bat`); ProcessBuilder cannot start the Unix script on Windows, so
  prefer the `.bat` sibling there when it is present."
  ^Path [^Path project-root gradle-wrapper]
  (let [base (resolve-configured-path project-root gradle-wrapper "gradlew")]
    (if (windows?)
      (let [file-name (str/lower-case (str (.getFileName base)))]
        (if (or (str/ends-with? file-name ".bat") (str/ends-with? file-name ".cmd"))
          base
          (let [batch (paths/path (str base ".bat"))]
            (if (paths/regular-file? batch) batch base))))
      base)))

(defn- resolve-project-root
  [^Path workspace-root project-root]
  (when-not project-root
    (throw (ex-info "Gradle discovery requires an explicit project root"
                    {:kind :missing-gradle-project-root})))
  (let [path (paths/path project-root)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path workspace-root path)))))

(def ^:private java-home-candidates
  ["/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
   "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
   "/usr/lib/jvm/java-17-openjdk"
   "/usr/lib/jvm/java-17-openjdk-amd64"])

(defn- java-home-major [^Path java-home]
  (let [release (paths/resolve-path java-home "release")]
    (when (paths/regular-file? release)
      (some->> (re-find #"(?m)^JAVA_VERSION=\"(\d+)" (Files/readString release))
               second parse-long))))

(defn- gradle-environment! [requested-major]
  (when requested-major
    (let [candidates (->> [(System/getenv "DRIPSHARP_JAVA_HOME")
                           (System/getProperty "java.home")]
                          (concat java-home-candidates)
                          (remove str/blank?)
                          (map paths/path)
                          (map paths/absolute)
                          distinct)
          selected (first (filter #(and (= requested-major (java-home-major %))
                                        (paths/regular-file?
                                         (paths/resolve-path % "bin" "java")))
                                  candidates))]
      (when-not selected
        (throw (ex-info "No installed JDK matches the profile's Gradle runtime"
                        {:kind :gradle-java-runtime-missing
                         :requested-major requested-major
                         :candidates (mapv str candidates)})))
      {"JAVA_HOME" (str selected)})))

(defn read-discovery-manifest
  "Adapts and validates a Gradle production-input manifest into the neutral
  Java project-input model."
  [manifest]
  (when-not (paths/regular-file? manifest)
    (throw (ex-info
            (str "Gradle did not create its discovery manifest: " manifest)
            {:kind :discovery-manifest-missing :path (str manifest)})))
  (let [[header & lines] (str/split-lines (slurp (str manifest)))]
    (when-not (contains? manifest-versions header)
      (throw (ex-info
              (str "Unsupported Gradle discovery manifest header: " (pr-str header))
              {:kind :invalid-discovery-manifest :header header})))
    (let [version (get manifest-versions header)
          records (mapv parse-record (remove str/blank? lines))
          grouped (group-by first records)
          duplicate-records
          (->> (frequencies records)
               (filter #(< 1 (val %)))
               (map key)
               (sort-by pr-str)
               vec)
          allowed #{:project-path :java-home :java-release :preview-features
                    :source-root :resource-root :source :generated-source
                    :resource :classpath
                    :project-dependency :external-dependency :external-artifact}
          unknown (sort (remove allowed (keys grouped)))
          invalid-arities
          (->> records
               (filter (fn [record]
                         (not= (count record)
                               (cond
                                 (contains? #{:project-dependency
                                              :external-dependency}
                                            (first record)) 3
                                 (= :external-artifact (first record))
                                 (if (= 5 version) 5 4)
                                 :else 2))))
               vec)
          _ (when (or (seq unknown) (seq invalid-arities)
                      (seq duplicate-records))
              (throw (ex-info "Gradle discovery manifest has unknown or malformed records"
                              {:kind :invalid-discovery-manifest
                               :unknown-record-kinds unknown
                               :invalid-records invalid-arities
                               :duplicate-records duplicate-records})))
          path-values #(->> (get grouped %)
                            (map (comp paths/absolute second))
                            distinct
                            (sort-by str)
                            vec)
          project-id
          (validate-gradle-project!
           (exactly-one grouped :project-path
                        "Gradle discovery must report exactly one project identity"))
          source-roots (if (= 5 version) (path-values :source-root) [])
          resource-roots
          (if (= 5 version)
            (path-values :resource-root)
            [(paths/absolute
              (exactly-one grouped :resource-root
                           "Legacy Gradle discovery must report exactly one resource root"))])
          java-home
          (paths/absolute
           (exactly-one grouped :java-home
                        "Gradle discovery must report exactly one Java toolchain"))
          java-release
          (parse-positive-int
           :java-release
           (exactly-one grouped :java-release
                        "Gradle discovery must report exactly one Java release"))
          preview-value (exactly-one
                         grouped :preview-features
                         "Gradle discovery must report exactly one preview-features setting")
          preview-features (case preview-value
                             "true" true
                             "false" false
                             (throw (ex-info
                                     (str "Gradle reported an invalid preview-features setting: "
                                          (pr-str preview-value))
                                     {:kind :invalid-discovery-manifest
                                      :record-kind :preview-features
                                      :value preview-value})))
          parse-scope
          (fn [value]
            (let [scope (keyword value)]
              (when-not (contains? #{:compile :runtime} scope)
                (throw (ex-info "Gradle discovery reported an invalid dependency scope"
                                {:kind :invalid-discovery-manifest
                                 :scope value})))
              scope))
          project-dependencies
          (->> (get grouped :project-dependency)
               (map (fn [[_ scope dependency-id]]
                      {:scope (parse-scope scope) :project-id dependency-id}))
               distinct (sort-by (juxt :project-id :scope)) vec)
          external-dependencies
          (->> (get grouped :external-dependency)
               (map (fn [[_ scope coordinate]]
                      {:scope (parse-scope scope) :coordinate coordinate}))
               distinct (sort-by (juxt :coordinate :scope)) vec)
          compile-paths (path-values :classpath)
          legacy-paths-by-hash
          (delay
            (->> compile-paths
                 (filter #(Files/isRegularFile ^Path % paths/no-links))
                 (map (juxt sha256 identity))
                 (into {})))
          external-artifacts
          (->> (get grouped :external-artifact)
               (map (fn [record]
                      (let [[_ scope coordinate path sha256]
                            (if (= 5 version)
                              record
                              (let [[kind legacy-scope legacy-coordinate
                                     legacy-sha256] record
                                    legacy-path
                                    (get @legacy-paths-by-hash legacy-sha256)]
                                (when-not legacy-path
                                  (throw
                                   (ex-info
                                    "Legacy Gradle artifact hash does not match a compile classpath file"
                                    {:kind :invalid-discovery-manifest
                                     :coordinate legacy-coordinate
                                     :sha256 legacy-sha256})))
                                [kind legacy-scope legacy-coordinate
                                 (str legacy-path) legacy-sha256]))]
                        (when-not (re-matches #"[0-9a-f]{64}" sha256)
                          (throw (ex-info "Gradle discovery reported an invalid artifact hash"
                                          {:kind :invalid-discovery-manifest
                                           :coordinate coordinate :sha256 sha256})))
                        {:scope (parse-scope scope)
                         :coordinate coordinate
                         :path (paths/absolute path)
                         :sha256 sha256})))
               distinct
               (sort-by (juxt (comp str :path) :coordinate :scope :sha256))
               vec)
          external-by-path-scope
          (group-by (juxt :scope :path) external-artifacts)
          _ (when-let [[identity collisions]
                       (first
                        (sort-by (comp pr-str key)
                                 (filter #(< 1 (count (val %)))
                                         external-by-path-scope)))]
              (throw
               (ex-info
                "Gradle discovery assigns multiple external identities to one classpath artifact"
                {:kind :invalid-discovery-manifest
                 :artifact identity :records collisions})))
          compile-artifacts
          (mapv (fn [path]
                  (or (first (get external-by-path-scope [:compile path]))
                      {:scope :compile :path path}))
                compile-paths)
          compile-identities (set (map (juxt :scope :path) compile-artifacts))
          classpath-artifacts
          (->> (concat compile-artifacts
                       (remove #(contains? compile-identities
                                           ((juxt :scope :path) %))
                               external-artifacts))
               distinct
               (sort-by (juxt (comp str :path) :scope
                              #(or (:coordinate %) "")))
               vec)
          ordinary-sources (path-values :source)
          generated-sources (path-values :generated-source)
          resources (path-values :resource)
          project-input
          {:schema-version 1
           :project-id project-id
           :source-roots source-roots
           :resource-roots resource-roots
           :production-sources ordinary-sources
           :generated-production-sources generated-sources
           :production-resources resources
           :java-toolchain {:home java-home
                            :release java-release
                            :preview-features? preview-features}
           :project-dependencies project-dependencies
           :external-dependencies external-dependencies
           :classpath-artifacts classpath-artifacts}]
      (when-not (paths/directory? java-home)
        (throw (ex-info
                (str "Gradle-reported Java toolchain is missing: " java-home)
                {:kind :toolchain-missing :path (str java-home)})))
      (doseq [^Path resource-root resource-roots]
        (when-not (paths/directory? resource-root)
          (throw (ex-info
                  (str "Gradle-reported resource root is missing: " resource-root)
                  {:kind :resource-root-missing :path (str resource-root)}))))
      (doseq [[kind inputs] [[:source-root source-roots]
                             [:source (into ordinary-sources generated-sources)]
                             [:resource resources]
                             [:classpath (mapv :path classpath-artifacts)]]
              ^Path input inputs]
        (when-not (case kind
                    :source-root (Files/isDirectory input paths/no-links)
                    :classpath (or (Files/isRegularFile input paths/no-links)
                                   (Files/isDirectory input paths/no-links))
                    (Files/isRegularFile input paths/no-links))
          (throw (ex-info
                  (str "Gradle-reported " (name kind) " input is missing: " input)
                  {:kind :input-missing :input-kind kind :path (str input)}))))
      (project-input/validate! project-input))))

(defn discover-main!
  "Asks a Gradle Java project for its resolved production inputs.

  `project-root` may be absolute or workspace-relative. `gradle-wrapper` is
  project-relative by default and may also be absolute. Both project root and
  Gradle project identity are explicit; product profiles own any defaults."
  [{:keys [workspace-root manifest project-root gradle-wrapper init-script
           gradle-project gradle-java-major run-command!]
    :or {run-command! process/run!}}]
  (let [gradle-project (validate-gradle-project! gradle-project)
        environment (gradle-environment! gradle-java-major)
        root (paths/absolute workspace-root)
        project-root (resolve-project-root root project-root)
        init-script (let [configured (some-> init-script paths/path)]
                      (paths/absolute
                       (if (and configured (.isAbsolute configured))
                         configured
                         (paths/resolve-path root
                                             (or configured
                                                 (paths/path "gradle/discover-main.gradle"))))))
        gradlew (resolve-gradle-wrapper project-root gradle-wrapper)]
    (when-not (paths/directory? project-root)
      (throw (ex-info
              (str "Configured Gradle project root is missing: " project-root)
              {:kind :project-root-missing :path (str project-root)})))
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
                  (str (when-not (= ":" gradle-project) gradle-project)
                       ":dripsharpDescribeMain")
                  (str "-Pdripsharp.project=" gradle-project)
                  (str "-Pdripsharp.output=" (paths/absolute manifest))]
        :directory project-root
        :environment environment})
      (catch ExceptionInfo error
        (throw (ex-info
                (str "Gradle source/classpath/toolchain discovery failed: " (.getMessage error))
                (merge {:kind :gradle-discovery-failed}
                       (select-keys (ex-data error) [:command :exit :output]))
                error))))
    (let [project-input (read-discovery-manifest manifest)]
      (when-not (= gradle-project (:project-id project-input))
        (throw (ex-info
                "Gradle discovery reported a different project than requested"
                {:kind :gradle-project-mismatch
                 :requested gradle-project
                 :reported (:project-id project-input)})))
      (project-input/validate!
       (assoc project-input :project-root project-root)))))
