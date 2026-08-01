(ns dripsharp.maven
  "Pinned Maven 3 reactor discovery adapted to neutral Java project inputs.

  A small Maven EventSpy observes Maven's effective reactor after the compile
  lifecycle has materialized generated production sources and reactor output
  directories. This namespace owns the checksum-pinned runner, validates the
  backend manifest, hashes resolved external artifacts, and exposes only the
  build-tool-neutral project-input model."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.project-input :as project-input]
            [dripsharp.util :as util])
  (:import [clojure.lang ExceptionInfo]
           [java.io BufferedInputStream File FileOutputStream]
           [java.net URI]
           [java.nio.file AtomicMoveNotSupportedException CopyOption
            FileVisitOption Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util.jar JarEntry JarOutputStream]
           [java.util.zip ZipInputStream]))

(def maven-version
  "The Maven 3 runner version used for every reactor discovery."
  "3.9.11")

(def maven-distribution-sha512
  "SHA-512 published with the pinned Maven binary ZIP."
  (str "03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d"
       "0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076"))

(def ^:private maven-distribution-uri
  (str "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/"
       maven-version "/apache-maven-" maven-version "-bin.zip"))

(def ^:private dependency-plugin-version "3.8.1")
(def ^:private manifest-header "DRIPSHARP_MAVEN_REACTOR_V1")
(def ^:private build-input-contract-keys
  #{:schema-version :project-id :maven-version :maven-distribution-sha512
    :lifecycle-phase :properties :source-inputs :generation-executions
    :artifact-count :artifacts-sha256 :required-artifacts})
(def ^:private source-input-keys #{:path :sha256})
(def ^:private generation-execution-keys #{:owner :goal})
(def ^:private build-artifact-pin-keys #{:owner :coordinate :sha256})
(def ^:private extension-source
  (paths/path "maven/src/dripsharp/maven/DiscoveryEventSpy.java"))
(def ^:private extension-components
  (paths/path "maven/resources/META-INF/plexus/components.xml"))

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(def ^:private sha256 util/sha256-file)
(def ^:private sha512 util/sha512-file)

(defn- cache-root
  []
  (let [configured (System/getenv "XDG_CACHE_HOME")
        base (if (str/blank? configured)
               (paths/resolve-path (System/getProperty "user.home") ".cache")
               (paths/path configured))]
    (paths/absolute (paths/resolve-path base "maven"))))

(defn- create-directories!
  [^Path directory]
  (Files/createDirectories directory (make-array FileAttribute 0))
  directory)

(defn- delete-tree!
  "Deletes only a caller-created cache temporary tree."
  [^Path root]
  (when (paths/exists? root)
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (doseq [^Path entry (reverse (sort-by #(.getNameCount ^Path %)
                                            (vec (.toArray entries))))]
        (Files/deleteIfExists entry)))))

(defn- atomic-move!
  [^Path source ^Path destination]
  (try
    (Files/move source destination
                (into-array CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move source destination
                  (into-array CopyOption
                              [StandardCopyOption/REPLACE_EXISTING]))))
  destination)

(defn- download!
  [^String uri ^Path destination]
  (let [parent (create-directories! (.getParent destination))
        temporary (Files/createTempFile parent ".maven-download-" ".tmp"
                                        (make-array FileAttribute 0))]
    (try
      (let [connection (doto (.openConnection (.toURL (URI/create uri)))
                         (.setConnectTimeout 30000)
                         (.setReadTimeout 120000))]
        (with-open [input (BufferedInputStream. (.getInputStream connection))]
          (Files/copy input temporary
                      (into-array CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (atomic-move! temporary destination)
      (catch Exception error
        (fail! (str "Could not download pinned Maven " maven-version
                    " from " uri ": " (.getMessage error))
               {:kind :maven-runner-download-failed
                :maven-version maven-version
                :uri uri
                :path (str destination)}))
      (finally
        (Files/deleteIfExists temporary)))))

(defn- validate-archive!
  [^Path archive]
  (let [actual (sha512 archive)]
    (when-not (= maven-distribution-sha512 actual)
      (fail! (str "Pinned Maven " maven-version
                  " archive checksum mismatch at " archive
                  "; remove the corrupt cache entry and retry")
             {:kind :maven-runner-checksum-mismatch
              :maven-version maven-version
              :path (str archive)
              :expected maven-distribution-sha512
              :actual actual})))
  archive)

(defn- extract-zip!
  [^Path archive ^Path destination]
  (create-directories! destination)
  (with-open [input (ZipInputStream.
                     (BufferedInputStream.
                      (Files/newInputStream archive
                                            (make-array OpenOption 0))))]
    (loop [entry (.getNextEntry input)]
      (when entry
        (let [target (-> destination
                         (.resolve (.getName entry))
                         .normalize)]
          (when-not (.startsWith target destination)
            (fail! "Pinned Maven archive contains a path outside its extraction root"
                   {:kind :invalid-maven-runner-archive
                    :entry (.getName entry)
                    :archive (str archive)}))
          (if (.isDirectory entry)
            (create-directories! target)
            (do
              (create-directories! (.getParent target))
              (Files/copy input target
                          (into-array CopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))))
          (.closeEntry input)
          (recur (.getNextEntry input))))))
  destination)

(def ^:private windows? util/windows?)

(defn- maven-launcher
  [^Path maven-home]
  (paths/resolve-path maven-home "bin" (if (windows?) "mvn.cmd" "mvn")))

(defn- validate-runner-home!
  [^Path home]
  (let [launcher (maven-launcher home)
        core (paths/resolve-path home "lib"
                                 (str "maven-core-" maven-version ".jar"))]
    (when-not (and (paths/regular-file? launcher)
                   (paths/regular-file? core))
      (fail! (str "Pinned Maven cache is incomplete at " home
                  "; remove that version directory and retry")
             {:kind :maven-runner-cache-incomplete
              :maven-version maven-version
              :path (str home)
              :launcher (str launcher)
              :core (str core)}))
    (when-not (windows?)
      (.setExecutable (.toFile launcher) true false))
    home))

(defn ensure-pinned-runner!
  "Downloads, verifies, and extracts the exact Maven 3 runner when absent.

  `runner-cache` is optional and primarily supports isolated verification. The
  archive hash, extracted home layout, and Maven core version are all checked
  before the runner is returned."
  ([]
   (ensure-pinned-runner! {}))
  ([{:keys [runner-cache]}]
   (let [root (paths/absolute (or runner-cache (cache-root)))
         home (paths/resolve-path root (str "apache-maven-" maven-version))
         archive (paths/resolve-path root "downloads"
                                     (str "apache-maven-" maven-version "-bin.zip"))]
     (if (paths/directory? home)
       (validate-runner-home! home)
       (do
         (create-directories! root)
         (when-not (paths/regular-file? archive)
           (download! maven-distribution-uri archive))
         (validate-archive! archive)
         (let [temporary
               (Files/createTempDirectory
                root ".maven-extract-" (make-array FileAttribute 0))]
           (try
             (extract-zip! archive temporary)
             (let [extracted
                   (paths/resolve-path temporary
                                       (str "apache-maven-" maven-version))]
               (validate-runner-home! extracted)
               (try
                 (Files/move extracted home (make-array CopyOption 0))
                 (catch java.nio.file.FileAlreadyExistsException _
                   nil))
               (validate-runner-home! home))
             (finally
               (delete-tree! temporary)))))))))

(defn- extension-input-hash
  [^Path workspace-root]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [^Path input [(paths/resolve-path workspace-root extension-source)
                         (paths/resolve-path workspace-root extension-components)]]
      (when-not (paths/regular-file? input)
        (fail! (str "Maven discovery extension input is missing: " input)
               {:kind :maven-discovery-tooling-missing
                :path (str input)}))
      (.update digest (.getBytes (str (.normalize input))
                                 java.nio.charset.StandardCharsets/UTF_8))
      (with-open [stream (Files/newInputStream input
                                               (make-array OpenOption 0))]
        (let [buffer (byte-array 8192)]
          (loop [read (.read stream buffer)]
            (when-not (neg? read)
              (when (pos? read)
                (.update digest buffer 0 read))
              (recur (.read stream buffer)))))))
    (.update digest (.getBytes maven-version
                               java.nio.charset.StandardCharsets/UTF_8))
    (util/hex (.digest digest))))

(defn- java-tool
  [tool]
  (let [home (paths/absolute (System/getProperty "java.home"))
        executable (paths/resolve-path home "bin"
                                       (str tool (when (windows?) ".exe")))]
    (when-not (paths/regular-file? executable)
      (fail! (str "JDK tool required for Maven discovery is missing: " executable)
             {:kind :maven-discovery-jdk-tool-missing
              :tool tool
              :path (str executable)}))
    executable))

(defn- maven-library-classpath
  [^Path maven-home]
  (let [library (paths/resolve-path maven-home "lib")]
    (with-open [entries (Files/list library)]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (filter #(str/ends-with? (str %) ".jar"))
           (sort-by str)
           (map str)
           (str/join File/pathSeparator)))))

(defn- add-jar-file!
  [^JarOutputStream output ^Path root ^Path input]
  (let [name (-> (str (.relativize root input))
                 (str/replace "\\" "/"))
        entry (doto (JarEntry. name) (.setTime 0))]
    (.putNextEntry output entry)
    (Files/copy input output)
    (.closeEntry output)))

(defn- write-extension-jar!
  [^Path classes ^Path components ^Path output]
  (with-open [stream (JarOutputStream.
                      (FileOutputStream. (.toFile output)))]
    (with-open [entries (Files/walk classes (make-array FileVisitOption 0))]
      (doseq [^Path input (->> (.toArray entries)
                               (map #(cast Path %))
                               (filter paths/regular-file?)
                               (sort-by str))]
        (add-jar-file! stream classes input)))
    (let [entry (doto (JarEntry. "META-INF/plexus/components.xml")
                  (.setTime 0))]
      (.putNextEntry stream entry)
      (Files/copy components stream)
      (.closeEntry stream)))
  output)

(defn- build-extension!
  [{:keys [workspace-root maven-home runner-cache run-command!]
    :or {run-command! process/run!}}]
  (let [root (paths/absolute (or runner-cache (cache-root)))
        extension-root (create-directories!
                        (paths/resolve-path root "extensions"))
        input-hash (extension-input-hash workspace-root)
        output (paths/resolve-path
                extension-root
                (str "dripsharp-maven-discovery-" input-hash ".jar"))]
    (if (paths/regular-file? output)
      output
      (let [temporary
            (Files/createTempDirectory
             extension-root ".extension-build-" (make-array FileAttribute 0))
            classes (create-directories!
                     (paths/resolve-path temporary "classes"))
            jar-output (paths/resolve-path temporary "extension.jar")
            source (paths/resolve-path workspace-root extension-source)
            components (paths/resolve-path workspace-root extension-components)]
        (try
          (try
            (run-command!
             {:command [(str (java-tool "javac"))
                        "--release" "8"
                        "-encoding" "UTF-8"
                        "-classpath" (maven-library-classpath maven-home)
                        "-d" (str classes)
                        (str source)]
              :directory workspace-root
              :timeout-ms 120000})
            (catch ExceptionInfo error
              (fail!
               (str "Could not compile the Maven reactor discovery extension: "
                    (.getMessage error))
               (merge {:kind :maven-discovery-extension-build-failed
                       :maven-version maven-version}
                      (select-keys (ex-data error)
                                   [:command :exit :output])))))
          (write-extension-jar! classes components jar-output)
          (try
            (Files/move jar-output output (make-array CopyOption 0))
            (catch java.nio.file.FileAlreadyExistsException _
              nil))
          (when-not (paths/regular-file? output)
            (fail! "Maven discovery extension build produced no JAR"
                   {:kind :maven-discovery-extension-missing
                    :path (str output)}))
          output
          (finally
            (delete-tree! temporary)))))))

(defn- parse-record
  [line]
  (let [fields (str/split line #"\t" -1)]
    (when (or (some str/blank? fields)
              (< (count fields) 2))
      (fail! (str "Invalid Maven discovery manifest record: " (pr-str line))
             {:kind :invalid-maven-discovery-manifest
              :line line}))
    (into [(keyword (first fields))] (rest fields))))

(def ^:private record-arities
  {:project 4
   :java-home 3
   :java-release 3
   :preview-features 3
   :source-root 4
   :test-source-root 3
   :source 3
   :generated-source 3
   :test-source 3
   :resource-root 3
   :test-resource-root 3
   :resource 3
   :test-resource 3
   :project-dependency 4
   :external-dependency 4
   :test-project-dependency 4
   :test-external-dependency 4
   :classpath-artifact 6
   :test-classpath 4
   :test-classpath-artifact 6
   :generation-execution 4
   :build-input-artifact 5
   :unresolved-artifact 6
   :unresolved-test-artifact 6})

(defn- exactly-one-record
  [records kind project-id]
  (let [matches (filter #(and (= kind (first %))
                              (= project-id (second %)))
                        records)]
    (when-not (= 1 (count matches))
      (fail! (str "Maven discovery must report exactly one " (name kind)
                  " for " project-id)
             {:kind :invalid-maven-discovery-manifest
              :record-kind kind
              :project-id project-id
              :records (vec matches)}))
    (first matches)))

(defn- parse-positive-int
  [project-id value]
  (try
    (let [parsed (Integer/parseInt value)]
      (when-not (pos? parsed)
        (throw (NumberFormatException.)))
      parsed)
    (catch NumberFormatException _
      (fail! (str "Maven discovery reported an invalid Java release for "
                  project-id ": " (pr-str value))
             {:kind :invalid-maven-discovery-manifest
              :record-kind :java-release
              :project-id project-id
              :value value}))))

(defn- parse-preview-setting
  [project-id value]
  (case value
    "true" true
    "false" false
    (fail! (str "Maven discovery reported an invalid preview setting for "
                project-id ": " (pr-str value))
           {:kind :invalid-maven-discovery-manifest
            :record-kind :preview-features
            :project-id project-id
            :value value})))

(defn- parse-scope
  [project-id value]
  (case value
    "compile" :compile
    "runtime" :runtime
    (fail! (str "Maven discovery reported an unsupported dependency scope for "
                project-id ": " (pr-str value))
           {:kind :invalid-maven-discovery-manifest
            :record-kind :dependency-scope
            :project-id project-id
            :scope value})))

(defn- records-for
  [records kind project-id]
  (filter #(and (= kind (first %))
                (= project-id (second %)))
          records))

(defn- path-values
  [records kind project-id index]
  (->> (records-for records kind project-id)
       (map #(paths/absolute (nth % index)))
       distinct
       (sort-by str)
       vec))

(defn- dependency-records
  [records kind project-id identity-key]
  (->> (records-for records kind project-id)
       (map (fn [[_ _ scope identity]]
              {:scope (parse-scope project-id scope)
               identity-key identity}))
       distinct
       (sort-by (juxt identity-key :scope))
       vec))

(defn- validate-production-test-separation!
  [records project-id production-kinds test-kinds subject]
  (let [production (set (map #(nth % 2)
                             (mapcat #(records-for records % project-id)
                                     production-kinds)))
        tests (set (map #(nth % 2)
                        (mapcat #(records-for records % project-id)
                                test-kinds)))
        overlap (sort (set/intersection production tests))]
    (when (seq overlap)
      (fail! (str "Maven discovery mixed production and test " subject
                  " for " project-id)
             {:kind :invalid-maven-discovery-manifest
              :project-id project-id
              :subject subject
              :overlap overlap}))))

(defn- classpath-artifacts
  [records project-id record-kind project-dependencies external-dependencies
   subject]
  (let [project-identities
        (set (map (juxt :scope :project-id) project-dependencies))
        external-identities
        (set (map (juxt :scope :coordinate) external-dependencies))
        artifacts
        (->> (records-for records record-kind project-id)
             (map
              (fn [[_ _ scope identity-kind identity path-value]]
                (let [scope (parse-scope project-id scope)
                      path (paths/absolute path-value)]
                  (case identity-kind
                    "project"
                    (do
                      (when-not (contains? project-identities
                                           [scope identity])
                        (fail!
                         (str "Maven " subject
                              " classpath artifact has no matching reactor dependency")
                         {:kind :invalid-maven-discovery-manifest
                          :project-id project-id
                          :scope scope
                          :dependency-project-id identity
                          :path (str path)}))
                      {:scope scope :project-id identity :path path})

                    "external"
                    (do
                      (when-not (contains? external-identities
                                           [scope identity])
                        (fail!
                         (str "Maven " subject
                              " classpath artifact has no matching external dependency")
                         {:kind :invalid-maven-discovery-manifest
                          :project-id project-id
                          :scope scope
                          :coordinate identity
                          :path (str path)}))
                      (when-not (paths/regular-file? path)
                        (fail!
                         (str "Maven-resolved " subject
                              " classpath artifact is missing: " path)
                         {:kind :maven-classpath-artifact-missing
                          :project-id project-id
                          :scope scope
                          :coordinate identity
                          :path (str path)}))
                      {:scope scope :coordinate identity :path path
                       :sha256 (sha256 path)})

                    (fail!
                     (str "Maven discovery reported an invalid artifact identity: "
                          (pr-str identity-kind))
                     {:kind :invalid-maven-discovery-manifest
                      :project-id project-id
                      :identity-kind identity-kind
                      :identity identity
                      :path (str path)})))))
             distinct
             (sort-by (juxt (comp str :path) :scope
                            #(or (:project-id %) "")
                            #(or (:coordinate %) "")))
             vec)
        collisions
        (->> artifacts
             (group-by (juxt :scope :path))
             (filter #(< 1 (count (val %))))
             (sort-by (comp pr-str key))
             vec)]
    (when (seq collisions)
      (fail! (str "Maven discovery assigns multiple identities to one "
                  subject " classpath artifact")
             {:kind :invalid-maven-discovery-manifest
              :project-id project-id
              :collisions collisions}))
    artifacts))

(defn- effective-test-classpath
  [records project-id identified-artifacts]
  (let [identified-by-scope-path
        (group-by (juxt :scope :path) identified-artifacts)
        effective
        (->> (records-for records :test-classpath project-id)
             (map (fn [[_ _ scope path-value]]
                    (let [scope (parse-scope project-id scope)
                          path (paths/absolute path-value)
                          identities (get identified-by-scope-path [scope path])]
                      (when (< 1 (count identities))
                        (fail! "Maven test classpath path has multiple identities"
                               {:kind :invalid-maven-discovery-manifest
                                :project-id project-id
                                :scope scope
                                :path (str path)
                                :records identities}))
                      (or (first identities) {:scope scope :path path}))))
             distinct
             (sort-by (juxt (comp str :path) :scope
                            #(or (:project-id %) "")
                            #(or (:coordinate %) "")))
             vec)
        effective-identities (set (map (juxt :scope :path) effective))]
    (->> (concat effective
                 (remove #(contains? effective-identities
                                     ((juxt :scope :path) %))
                         identified-artifacts))
         distinct
         (sort-by (juxt (comp str :path) :scope
                        #(or (:project-id %) "")
                        #(or (:coordinate %) "")))
         vec)))

(defn- generation-executions
  [records project-id]
  (->> (records-for records :generation-execution project-id)
       (map (fn [[_ _ owner goal]] {:owner owner :goal goal}))
       distinct
       (sort-by (juxt :owner :goal))
       vec))

(defn- build-input-artifacts
  [records project-id]
  (->> (records-for records :build-input-artifact project-id)
       (map
        (fn [[_ _ owner coordinate path-value]]
          (let [path (paths/absolute path-value)]
            (when-not (paths/regular-file? path)
              (fail! "Maven generation build-input artifact is missing"
                     {:kind :maven-build-input-artifact-missing
                      :project-id project-id :owner owner
                      :coordinate coordinate :path (str path)}))
            {:owner owner :coordinate coordinate :path path
             :sha256 (sha256 path)})))
       distinct
       (sort-by (juxt :owner :coordinate (comp str :path)))
       vec))

(defn- adapt-project
  [records project-id]
  (let [[_ _ project-root _packaging]
        (exactly-one-record records :project project-id)
        [_ _ java-home]
        (exactly-one-record records :java-home project-id)
        [_ _ java-release]
        (exactly-one-record records :java-release project-id)
        [_ _ preview]
        (exactly-one-record records :preview-features project-id)
        unresolved
        (vec (concat (records-for records :unresolved-artifact project-id)
                     (records-for records :unresolved-test-artifact project-id)))
        _ (when (seq unresolved)
            (fail!
             (str "Maven left unresolved production or test dependencies for "
                  project-id ": "
                  (str/join ", " (map #(nth % 4) unresolved)))
             {:kind :maven-unresolved-dependencies
              :project-id project-id
              :unresolved
              (mapv (fn [[_ _ scope identity-kind identity path]]
                      {:scope (parse-scope project-id scope)
                       :identity-kind (keyword identity-kind)
                       :identity identity
                       :path path})
                    unresolved)}))
        _ (validate-production-test-separation!
           records project-id [:source :generated-source] [:test-source]
           "source files")
        project-dependencies
        (dependency-records records :project-dependency project-id :project-id)
        external-dependencies
        (dependency-records records :external-dependency project-id :coordinate)
        test-project-dependencies
        (dependency-records records :test-project-dependency project-id :project-id)
        test-external-dependencies
        (dependency-records records :test-external-dependency project-id :coordinate)
        source-roots
        (->> (records-for records :source-root project-id)
             (map (fn [[_ _ source-kind path]]
                    (when-not (#{"ordinary" "generated"} source-kind)
                      (fail!
                       (str "Maven discovery reported an invalid source-root kind: "
                            (pr-str source-kind))
                       {:kind :invalid-maven-discovery-manifest
                        :project-id project-id
                        :source-kind source-kind
                        :path path}))
                    (paths/absolute path)))
             distinct
             (sort-by str)
             vec)
        identified-test-classpath
        (classpath-artifacts records project-id :test-classpath-artifact
                             test-project-dependencies
                             test-external-dependencies "test")
        input
        {:schema-version 1
         :project-id project-id
         :project-root (paths/absolute project-root)
         :source-roots source-roots
         :resource-roots (path-values records :resource-root project-id 2)
         :production-sources (path-values records :source project-id 2)
         :generated-production-sources
         (path-values records :generated-source project-id 2)
         :production-resources
         (path-values records :resource project-id 2)
         :test-source-roots
         (path-values records :test-source-root project-id 2)
         :test-resource-roots
         (path-values records :test-resource-root project-id 2)
         :test-sources (path-values records :test-source project-id 2)
         :test-resources (path-values records :test-resource project-id 2)
         :java-toolchain
         {:home (paths/absolute java-home)
          :release (parse-positive-int project-id java-release)
          :preview-features? (parse-preview-setting project-id preview)}
         :project-dependencies project-dependencies
         :external-dependencies external-dependencies
         :classpath-artifacts
         (classpath-artifacts records project-id :classpath-artifact
                              project-dependencies external-dependencies
                              "production")
         :test-project-dependencies test-project-dependencies
         :test-external-dependencies test-external-dependencies
         :test-classpath-artifacts
         (effective-test-classpath records project-id
                                   identified-test-classpath)
         :generation-executions (generation-executions records project-id)
         :build-input-artifacts (build-input-artifacts records project-id)}]
    (project-input/validate! input)))

(defn read-reactor-manifest
  "Validates a Maven backend manifest and returns canonical neutral inputs.

  Production and test roots, Java compilation units, resources, dependency
  roles, and effective classpaths remain separate first-class neutral inputs."
  [manifest]
  (let [manifest (paths/absolute manifest)]
    (when-not (paths/regular-file? manifest)
      (fail! (str "Maven did not create its reactor discovery manifest: "
                  manifest
                  "; verify that the pinned runner loaded the discovery extension")
             {:kind :maven-discovery-manifest-missing
              :path (str manifest)
              :maven-version maven-version}))
    (let [[header & lines] (str/split-lines (slurp (str manifest)))]
      (when-not (= manifest-header header)
        (fail! (str "Unsupported Maven discovery manifest header: "
                    (pr-str header))
               {:kind :invalid-maven-discovery-manifest
                :path (str manifest)
                :header header}))
      (let [records (mapv parse-record (remove str/blank? lines))
            duplicate-records
            (->> (frequencies records)
                 (filter #(< 1 (val %)))
                 (map key)
                 (sort-by pr-str)
                 vec)
            invalid-records
            (->> records
                 (filter
                  (fn [record]
                    (not= (get record-arities (first record))
                          (count record))))
                 vec)
            unknown-records
            (->> records
                 (remove #(contains? record-arities (first %)))
                 vec)
            _ (when (or (seq duplicate-records)
                        (seq invalid-records)
                        (seq unknown-records))
                (fail! "Maven discovery manifest has unknown, duplicate, or malformed records"
                       {:kind :invalid-maven-discovery-manifest
                        :path (str manifest)
                        :duplicate-records duplicate-records
                        :invalid-records invalid-records
                        :unknown-records unknown-records}))
            project-ids
            (->> records
                 (filter #(= :project (first %)))
                 (map second)
                 distinct
                 sort
                 vec)
            owners (->> records (map second) distinct set)
            unknown-owners (sort (set/difference owners (set project-ids)))]
        (when-not (seq project-ids)
          (fail! "Maven discovery manifest contains no reactor projects"
                 {:kind :invalid-maven-discovery-manifest
                  :path (str manifest)}))
        (when (seq unknown-owners)
          (fail! "Maven discovery records refer to unknown reactor projects"
                 {:kind :invalid-maven-discovery-manifest
                  :path (str manifest)
                  :unknown-project-ids unknown-owners}))
        (mapv #(adapt-project records %) project-ids)))))

(defn- resolve-project-root
  [workspace-root project-root]
  (let [configured (paths/path project-root)]
    (paths/absolute
     (if (.isAbsolute configured)
       configured
       (paths/resolve-path workspace-root configured)))))

(defn- validate-selectors!
  [selectors]
  (when-not (vector? selectors)
    (fail! "Maven selected-projects must be a vector"
           {:kind :invalid-maven-project-selection
            :selected-projects selectors}))
  (doseq [selector selectors]
    (when-not (and (string? selector)
                   (not (str/blank? selector))
                   (not (str/starts-with? selector "-"))
                   (not (str/includes? selector ",")))
      (fail! (str "Invalid Maven reactor project selector: "
                  (pr-str selector))
             {:kind :invalid-maven-project-selection
              :selector selector})))
  selectors)

(defn- validate-properties!
  [properties]
  (let [properties (or properties {})
        reserved
        #{"maven.ext.class.path" "dripsharp.discovery.manifest"
          "maven.repo.local"}]
    (when-not (map? properties)
      (fail! "Maven discovery properties must be a map"
             {:kind :invalid-maven-discovery-properties
              :properties properties}))
    (doseq [[key value] properties]
      (when-not (and (string? key)
                     (re-matches #"[A-Za-z][A-Za-z0-9_.-]*" key)
                     (not (contains? reserved key))
                     (string? value)
                     (not (str/blank? value))
                     (not (re-find #"[\u0000\r\n]" value)))
        (fail! "Maven discovery property is invalid or reserved"
               {:kind :invalid-maven-discovery-properties
                :property key :value value})))
    (into (sorted-map) properties)))

(defn- exact-contract-keys!
  [subject expected value]
  (let [actual (if (map? value) (set (keys value)) #{})]
    (when-not (= expected actual)
      (fail! (str subject " has missing or unknown fields")
             {:kind :invalid-maven-build-input-contract
              :subject subject
              :missing (vec (sort (remove actual expected)))
              :unknown (vec (sort (remove expected actual)))})))
  value)

(defn- sha256-value?
  [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn generation-artifacts-sha256
  "Hashes the exact sorted generation artifact identities and content hashes."
  [artifacts]
  (util/sha256-text
   (apply str
          (map (fn [{:keys [owner coordinate sha256]}]
                 (str owner "\t" coordinate "\t" sha256 "\n"))
               (sort-by (juxt :owner :coordinate :sha256) artifacts)))))

(defn- contained-source-input!
  [^Path project-root relative]
  (when-not (and (string? relative) (not (str/blank? relative)))
    (fail! "Maven build-input source path is blank"
           {:kind :invalid-maven-build-input-contract :path relative}))
  (let [configured (paths/path relative)]
    (when (or (.isAbsolute configured)
              (some #(= ".." (str %))
                    (iterator-seq (.iterator configured))))
      (fail! "Maven build-input source path escapes the project root"
             {:kind :invalid-maven-build-input-contract :path relative}))
    (let [source (paths/absolute (paths/resolve-path project-root configured))]
      (when-not (.startsWith source project-root)
        (fail! "Maven build-input source path escapes the project root"
               {:kind :invalid-maven-build-input-contract :path relative}))
      (when-not (paths/regular-file? source)
        (fail! "Maven build-input source is missing"
               {:kind :maven-build-input-source-missing
                :path relative :resolved (str source)}))
      source)))

(defn read-build-input-contract!
  "Reads and validates exact Maven generation pins without resolving artifacts."
  [workspace-root project-root contract-path lifecycle-phase properties]
  (let [configured (paths/path contract-path)
        file (paths/absolute
              (if (.isAbsolute configured)
                configured
                (paths/resolve-path workspace-root configured)))]
    (when-not (paths/regular-file? file)
      (fail! "Maven build-input contract is missing"
             {:kind :maven-build-input-contract-missing
              :path (str file)}))
    (let [contract
          (try
            (util/read-single-edn-string! (slurp (str file)))
            (catch RuntimeException error
              (throw
               (ex-info "Maven build-input contract is not exactly one EDN value"
                        {:kind :invalid-maven-build-input-contract
                         :path (str file)}
                        error))))]
      (exact-contract-keys! "Maven build-input contract"
                            build-input-contract-keys contract)
      (when-not (= 1 (:schema-version contract))
        (fail! "Maven build-input contract has an unsupported schema"
               {:kind :invalid-maven-build-input-contract
                :actual (:schema-version contract) :expected 1}))
      (when-not (and (string? (:project-id contract))
                     (not (str/blank? (:project-id contract))))
        (fail! "Maven build-input contract has an invalid project identity"
               {:kind :invalid-maven-build-input-contract
                :project-id (:project-id contract)}))
      (when-not (= maven-version (:maven-version contract))
        (fail! "Maven build-input contract pins a different runner version"
               {:kind :maven-build-input-runner-drift
                :expected (:maven-version contract) :actual maven-version}))
      (when-not (= maven-distribution-sha512
                   (:maven-distribution-sha512 contract))
        (fail! "Maven build-input contract pins a different runner archive"
               {:kind :maven-build-input-runner-drift
                :expected (:maven-distribution-sha512 contract)
                :actual maven-distribution-sha512}))
      (when-not (= lifecycle-phase (:lifecycle-phase contract))
        (fail! "Maven lifecycle phase differs from the build-input contract"
               {:kind :maven-build-input-lifecycle-drift
                :expected (:lifecycle-phase contract)
                :actual lifecycle-phase}))
      (let [expected-properties (validate-properties! (:properties contract))]
        (when-not (= expected-properties properties)
          (fail! "Maven discovery properties differ from the build-input contract"
                 {:kind :maven-build-input-property-drift
                  :expected expected-properties :actual properties})))
      (doseq [field [:source-inputs :generation-executions
                     :required-artifacts]]
        (when-not (and (vector? (get contract field))
                       (= (count (get contract field))
                          (count (distinct (get contract field)))))
          (fail! "Maven build-input contract collection is not a distinct vector"
                 {:kind :invalid-maven-build-input-contract
                  :field field :value (get contract field)})))
      (doseq [{:keys [path] expected-sha256 :sha256 :as source}
              (:source-inputs contract)]
        (exact-contract-keys! "Maven generation source input"
                              source-input-keys source)
        (when-not (sha256-value? expected-sha256)
          (fail! "Maven generation source input has an invalid SHA-256"
                 {:kind :invalid-maven-build-input-contract
                  :path path :sha256 expected-sha256}))
        (let [file (contained-source-input! project-root path)
              actual (sha256 file)]
          (when-not (= expected-sha256 actual)
            (fail! "Maven generation source input differs from its pin"
                   {:kind :maven-build-input-source-drift
                    :path path :expected expected-sha256 :actual actual}))))
      (doseq [{:keys [owner goal] :as execution}
              (:generation-executions contract)]
        (exact-contract-keys! "Maven generation execution"
                              generation-execution-keys execution)
        (when-not (every? #(and (string? %) (not (str/blank? %)))
                          [owner goal])
          (fail! "Maven generation execution has a blank identity"
                 {:kind :invalid-maven-build-input-contract
                  :execution execution})))
      (when-not (and (pos-int? (:artifact-count contract))
                     (sha256-value? (:artifacts-sha256 contract)))
        (fail! "Maven generation artifact-set pin is invalid"
               {:kind :invalid-maven-build-input-contract
                :artifact-count (:artifact-count contract)
                :artifacts-sha256 (:artifacts-sha256 contract)}))
      (doseq [{:keys [owner coordinate sha256] :as artifact}
              (:required-artifacts contract)]
        (exact-contract-keys! "Maven generation artifact"
                              build-artifact-pin-keys artifact)
        (when-not (and (every? #(and (string? %) (not (str/blank? %)))
                               [owner coordinate])
                       (sha256-value? sha256))
          (fail! "Maven generation artifact has an invalid identity or digest"
                 {:kind :invalid-maven-build-input-contract
                  :artifact artifact})))
      {:path file :contract contract})))

(defn verify-build-input-contract!
  "Fails when resolved generation executions or artifact bytes drift from pins."
  [reactor {:keys [path contract]}]
  (let [matches (filter #(= (:project-id contract) (:project-id %)) reactor)
        _ (when-not (= 1 (count matches))
            (fail! "Maven build-input contract project is missing or ambiguous"
                   {:kind :maven-project-selection-mismatch
                    :project-id (:project-id contract)
                    :discovered-projects (mapv :project-id reactor)}))
        input (first matches)
        actual-executions (:generation-executions input)
        expected-executions
        (vec (sort-by (juxt :owner :goal) (:generation-executions contract)))
        actual-artifacts
        (->> (:build-input-artifacts input)
             (map #(select-keys % [:owner :coordinate :sha256]))
             (sort-by (juxt :owner :coordinate :sha256))
             vec)
        required-artifacts
        (set (:required-artifacts contract))
        actual-set (set actual-artifacts)
        actual-digest (generation-artifacts-sha256 actual-artifacts)]
    (when-not (= expected-executions actual-executions)
      (fail! "Maven generation executions differ from their pins"
             {:kind :maven-generation-execution-drift
              :contract (str path)
              :expected expected-executions :actual actual-executions}))
    (when-not (and (= (:artifact-count contract) (count actual-artifacts))
                   (= (:artifacts-sha256 contract) actual-digest)
                   (set/subset? required-artifacts actual-set))
      (fail! "Maven generation artifacts differ from their pins"
             {:kind :maven-build-input-artifact-drift
              :contract (str path)
              :expected-count (:artifact-count contract)
              :actual-count (count actual-artifacts)
              :expected-sha256 (:artifacts-sha256 contract)
              :actual-sha256 actual-digest
              :missing-required
              (vec (sort-by (juxt :owner :coordinate :sha256)
                            (set/difference required-artifacts actual-set)))}))
    input))

(defn- diagnostic-tail
  [output]
  (let [lines (vec (remove str/blank?
                           (str/split-lines (or output ""))))
        error-lines (filter #(or (str/starts-with? % "[ERROR]")
                                 (re-find #"(?i)could not resolve|unresolved dependenc"
                                          %))
                            lines)]
    (->> (concat error-lines (take-last 20 lines))
         distinct
         (take-last 40)
         (str/join "\n"))))

(defn discover-reactor!
  "Resolves an effective Maven reactor through the checksum-pinned Maven 3
  runner and returns a vector of validated neutral project inputs.

  `project-root` is workspace-relative or absolute. `selected-projects` is a
  vector of Maven `-pl` selectors; Maven adds their effective reactor
  dependencies through `-am`. No source, resource, or classpath inventory is
  accepted from the caller."
  [{:keys [workspace-root project-root pom-file selected-projects manifest
           runner-cache local-repository offline? timeout-ms run-command!
           properties build-input-contract lifecycle-phase]
    :or {workspace-root (paths/workspace-root)
         pom-file "pom.xml"
         selected-projects []
         timeout-ms 1200000
         run-command! process/run!}}]
  (when-not project-root
    (fail! "Maven discovery requires an explicit project root"
           {:kind :maven-project-root-missing}))
  (let [properties (validate-properties! properties)
        lifecycle-phase (or lifecycle-phase "test-compile")
        _ (when-not (contains? #{"generate-sources" "test-compile"}
                               lifecycle-phase)
            (fail! "Maven discovery lifecycle phase is unsupported"
                   {:kind :invalid-maven-discovery-lifecycle
                    :lifecycle-phase lifecycle-phase}))
        workspace-root (paths/absolute workspace-root)
        project-root (resolve-project-root workspace-root project-root)
        _ (when-not (paths/directory? project-root)
            (fail! (str "Configured Maven project root is missing: "
                        project-root)
                   {:kind :maven-project-root-missing
                    :path (str project-root)}))
        pom (let [configured (paths/path pom-file)]
              (paths/absolute
               (if (.isAbsolute configured)
                 configured
                 (paths/resolve-path project-root configured))))
        _ (when-not (paths/regular-file? pom)
            (fail! (str "Configured Maven reactor POM is missing: " pom)
                   {:kind :maven-pom-missing
                    :path (str pom)}))
        build-input-contract
        (when build-input-contract
          (read-build-input-contract! workspace-root project-root
                                      build-input-contract lifecycle-phase
                                      properties))
        selectors (validate-selectors! selected-projects)
        runner (ensure-pinned-runner! {:runner-cache runner-cache})
        runner-root (paths/absolute (or runner-cache (cache-root)))
        extension
        (build-extension! {:workspace-root workspace-root
                           :maven-home runner
                           :runner-cache runner-cache
                           :run-command! run-command!})
        repository
        (paths/absolute
         (or local-repository
             (paths/resolve-path runner-root "repository")))
        _ (create-directories! repository)
        temporary-manifest?
        (nil? manifest)
        manifest
        (paths/absolute
         (or manifest
             (Files/createTempFile
              "dripsharp-maven-reactor-" ".tsv"
              (make-array FileAttribute 0))))
        launcher (maven-launcher runner)
        base-command
        (cond-> [(str launcher)
                 "--batch-mode"
                 "--no-transfer-progress"
                 "--strict-checksums"
                 "-f" (str pom)
                 (str "-Dmaven.ext.class.path=" extension)
                 (str "-Ddripsharp.discovery.manifest=" manifest)
                 (str "-Dmaven.repo.local=" repository)
                 "-DskipTests=true"
                 "-Dcheckstyle.skip=true"]
          (seq properties)
          (into (mapv (fn [[key value]] (str "-D" key "=" value))
                      properties))
          offline? (conj "--offline")
          (seq selectors) (into ["-pl" (str/join "," selectors) "-am"])
          true (into [lifecycle-phase
                      (str "org.apache.maven.plugins:maven-dependency-plugin:"
                           dependency-plugin-version ":resolve")
                      "-DincludeScope=test"]))
        command (if (windows?)
                  (into ["cmd.exe" "/d" "/c"] base-command)
                  base-command)]
    (try
      (Files/deleteIfExists manifest)
      (try
        (run-command!
         {:command command
          :directory project-root
          :timeout-ms timeout-ms
          :environment {"MAVEN_ARGS" ""
                        "JAVA_HOME" (System/getProperty "java.home")}})
        (catch ExceptionInfo error
          (let [data (ex-data error)
                tail (diagnostic-tail (:output data))]
            (fail!
             (str "Pinned Maven " maven-version
                  " reactor discovery failed for " project-root
                  (when (seq selectors)
                    (str " (selected " (str/join ", " selectors) ")"))
                  (when-not (str/blank? tail)
                    (str ":\n" tail)))
             (merge {:kind :maven-discovery-failed
                     :maven-version maven-version
                     :project-root (str project-root)
                     :pom (str pom)
                     :selected-projects selectors}
                    (select-keys data
                                 [:command :exit :output :timeout-ms]))))))
      (let [reactor (read-reactor-manifest manifest)]
        (when build-input-contract
          (verify-build-input-contract! reactor build-input-contract))
        reactor)
      (finally
        (when temporary-manifest?
          (Files/deleteIfExists manifest))))))

(defn project-by-id!
  "Returns exactly one neutral project input from a discovered reactor."
  [reactor project-id]
  (let [matches (filter #(= project-id (:project-id %)) reactor)]
    (when-not (= 1 (count matches))
      (fail! (str "Discovered Maven reactor does not contain exactly one project "
                  project-id)
             {:kind :maven-project-selection-mismatch
              :project-id project-id
              :discovered-projects (mapv :project-id reactor)}))
    (first matches)))
