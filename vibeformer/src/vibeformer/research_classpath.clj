(ns vibeformer.research-classpath
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.file Files Path Paths)))

(def default-project-id "research-pkl")

(def gradle-kotlin-classpath-types
  {"Action" "org.gradle.api.Action"
   "DependencyConstraint" "org.gradle.api.artifacts.DependencyConstraint"
   "ExternalModuleDependency" "org.gradle.api.artifacts.ExternalModuleDependency"
   "ProviderConvertible" "org.gradle.api.provider.ProviderConvertible"
   "PublishArtifact" "org.gradle.api.artifacts.PublishArtifact"})

(def java-package-root-aliases
  {"org.graalvm.truffle" ["com.oracle.truffle.api"]})

(def dependency-configurations
  #{"api"
    "implementation"
    "compileOnly"
    "runtimeOnly"
    "testImplementation"
    "testCompileOnly"
    "testRuntimeOnly"
    "annotationProcessor"
    "kapt"
    "ksp"})

(defn- path [value]
  (if (instance? Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- normalize-path [value]
  (.normalize (.toAbsolutePath (path value))))

(defn- slash-path [value]
  (str/replace (str value) \\ \/))

(defn- relative-slash-path [^Path root value]
  (slash-path (.relativize (.normalize root) (.normalize (path value)))))

(defn- directory? [value]
  (Files/isDirectory (path value) (make-array java.nio.file.LinkOption 0)))

(defn- regular-file? [value]
  (Files/isRegularFile (path value) (make-array java.nio.file.LinkOption 0)))

(defn- ensure-dir! [^Path dir]
  (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
  dir)

(defn- write-edn! [file value]
  (ensure-dir! (.getParent (path file)))
  (spit (str file) (str (pr-str value) "\n"))
  (slash-path (normalize-path file)))

(defn- default-research-root [project-root]
  (.resolve (.getParent (normalize-path project-root)) "research/pkl"))

(defn- default-output-file [project-root]
  (.resolve (normalize-path project-root) "target/research-pkl/classpath.edn"))

(defn- report-file [project-root opts]
  (normalize-path (or (:out opts)
                      (:classpath/out opts)
                      (default-output-file project-root))))

(defn- slurp-if-file [file]
  (when (regular-file? file)
    (slurp (str file))))

(defn- lines [text]
  (str/split-lines (or text "")))

(defn- strip-line-comment [line]
  (str/replace line #"//.*$" ""))

(defn- quoted-values [value]
  (mapv second (re-seq #"\"([^\"]+)\"" value)))

(defn- parse-settings [settings-file]
  (let [text (slurp-if-file settings-file)
        include-values (->> (re-seq #"(?s)include\s*\((.*?)\)" (or text ""))
                            (mapcat (comp quoted-values second))
                            (remove str/blank?)
                            vec)
        include-builds (->> (re-seq #"(?s)includeBuild\s*\((.*?)\)" (or text ""))
                            (mapcat (comp quoted-values second))
                            (remove str/blank?)
                            vec)]
    {:settings/file (when text (slash-path (normalize-path settings-file)))
     :settings/includes include-values
     :settings/include-builds include-builds}))

(defn- project-name [include-value]
  (let [name (-> include-value
                 (str/replace #"^:+" "")
                 (str/split #":")
                 last)]
    (if (str/blank? name)
      "root"
      name)))

(defn- project-path [include-value]
  (let [parts (-> include-value
                  (str/replace #"^:+" "")
                  (str/split #":"))]
    (str ":" (str/join ":" parts))))

(defn- project-dir [research-root include-value]
  (let [parts (-> include-value
                  (str/replace #"^:+" "")
                  (str/split #":"))]
    (reduce (fn [^Path dir part] (.resolve dir part))
            research-root
            parts)))

(defn- build-file-candidates [research-root project-dir project-name]
  (if (= research-root project-dir)
    [(.resolve project-dir "build.gradle.kts")
     (.resolve project-dir "build.gradle")]
    [(.resolve project-dir (str project-name ".gradle.kts"))
     (.resolve project-dir (str project-name ".gradle"))
     (.resolve project-dir "build.gradle.kts")
     (.resolve project-dir "build.gradle")]))

(defn- first-file [files]
  (some #(when (regular-file? %) %) files))

(defn- source-root-kind [lang-dir]
  (case lang-dir
    "kotlin" :source.kind/kotlin
    "java" :source.kind/java
    "resources" :source.kind/resources
    :source.kind/other))

(defn- conventional-source-roots [project-dir]
  (let [src-dir (.resolve project-dir "src")]
    (if-not (directory? src-dir)
      []
      (with-open [paths (Files/walk src-dir (make-array java.nio.file.FileVisitOption 0))]
        (->> (iterator-seq (.iterator paths))
             (filter directory?)
             (keep (fn [dir]
                     (let [name (str (.getFileName dir))
                           parent (some-> dir .getParent .getFileName str)]
                       (when (contains? #{"kotlin" "java" "resources"} name)
                         {:source/root (slash-path (normalize-path dir))
                          :source/relative-path (slash-path (.relativize project-dir dir))
                          :source/source-set parent
                          :source/kind (source-root-kind name)}))))
             (sort-by (juxt :source/source-set :source/kind :source/relative-path))
             vec)))))

(defn- accessor->project-name [accessor]
  (-> accessor
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case))

(defn- library-alias->catalog-key [alias]
  (str/replace alias "." ""))

(defn- dependency-kind [expression]
  (cond
    (re-find #"projects\.[A-Za-z0-9_.]+" expression)
    :dependency.kind/project-accessor

    (re-find #"project\s*\(" expression)
    :dependency.kind/project

    (re-find #"libs\.[A-Za-z0-9_.]+" expression)
    :dependency.kind/version-catalog

    (re-find #"\"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^\"\s]+\"" expression)
    :dependency.kind/coordinate

    :else
    :dependency.kind/expression))

(defn- project-target [expression]
  (or (some-> (second (re-find #"project\s*\(\s*\"([^\"]+)\"" expression))
              project-path)
      (some-> (second (re-find #"projects\.([A-Za-z0-9_.]+)" expression))
              (str/split #"\.")
              (->> (map accessor->project-name)
                   (str/join ":")
                   (str ":")))))

(defn- catalog-alias [expression]
  (some-> (second (re-find #"libs\.([A-Za-z0-9_.]+)" expression))
          library-alias->catalog-key))

(defn- coordinate [expression]
  (second (re-find #"\"([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^\"\s]+)\"" expression)))

(defn- dependency-line [build-file line-number line]
  (let [line (str/trim (strip-line-comment line))]
    (when-not (str/blank? line)
      (let [[_ add-config add-expression] (re-find #"^add\s*\(\s*\"([^\"]+)\"\s*,\s*(.+?)\s*\)\s*$" line)
            [_ call-config call-expression] (re-find #"^([A-Za-z][A-Za-z0-9_]*)\s*\((.+?)\)\s*(?:\{.*)?$" line)
            config (or add-config call-config)
            expression (or add-expression call-expression)]
        (when (and config
                   expression
                   (or (contains? dependency-configurations config)
                       add-config))
          (let [expression (str/trim expression)]
            (cond-> {:dependency/configuration config
                     :dependency/expression expression
                     :dependency/kind (dependency-kind expression)
                     :dependency/build-file build-file
                     :dependency/line line-number}
              (project-target expression)
              (assoc :dependency/project (project-target expression))

              (catalog-alias expression)
              (assoc :dependency/catalog-alias (catalog-alias expression))

              (coordinate expression)
              (assoc :dependency/coordinate (coordinate expression)))))))))

(defn- dependencies-from-build-file [build-file]
  (let [build-file (normalize-path build-file)
        build-file-string (slash-path build-file)]
    (if-not (regular-file? build-file)
      []
      (->> (lines (slurp (str build-file)))
           (keep-indexed (fn [index line]
                           (dependency-line build-file-string (inc index) line)))
           vec))))

(defn- parse-version-ref [body]
  (second (re-find #"version\.ref\s*=\s*\"([^\"]+)\"" body)))

(defn- parse-version-value [body]
  (second (re-find #"version\s*=\s*\"([^\"]+)\"" body)))

(defn- parse-library-line [versions line]
  (when-let [[_ alias body] (re-find #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\{(.+)\}\s*$" line)]
    (let [version-ref (parse-version-ref body)
          version (or (parse-version-value body)
                      (get versions version-ref))]
      (cond-> {:catalog/alias alias
               :catalog/group (second (re-find #"group\s*=\s*\"([^\"]+)\"" body))
               :catalog/name (second (re-find #"name\s*=\s*\"([^\"]+)\"" body))}
        version-ref (assoc :catalog/version-ref version-ref)
        version (assoc :catalog/version version)))))

(defn- parse-plugin-line [versions line]
  (when-let [[_ alias body] (re-find #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\{(.+)\}\s*$" line)]
    (let [version-ref (parse-version-ref body)
          version (or (parse-version-value body)
                      (get versions version-ref))]
      (cond-> {:plugin/alias alias
               :plugin/id (second (re-find #"id\s*=\s*\"([^\"]+)\"" body))}
        version-ref (assoc :plugin/version-ref version-ref)
        version (assoc :plugin/version version)))))

(defn- sectioned-lines [text]
  (loop [remaining (lines text)
         section nil
         out []]
    (if-let [line (first remaining)]
      (if-let [[_ next-section] (re-find #"^\s*\[([^\]]+)\]" line)]
        (recur (next remaining) next-section out)
        (recur (next remaining) section (conj out [section line])))
      out)))

(defn- parse-version-catalog [catalog-file]
  (if-not (regular-file? catalog-file)
    {:catalog/file nil
     :catalog/versions {}
     :catalog/libraries []
     :catalog/plugins []}
    (let [section-lines (sectioned-lines (slurp (str catalog-file)))
          versions (->> section-lines
                        (keep (fn [[section line]]
                                (when (= "versions" section)
                                  (when-let [[_ alias value] (re-find #"^\s*([A-Za-z0-9_.-]+)\s*=\s*\"([^\"]+)\"" line)]
                                    [alias value]))))
                        (into {}))]
      {:catalog/file (slash-path (normalize-path catalog-file))
       :catalog/versions versions
       :catalog/libraries (->> section-lines
                               (keep (fn [[section line]]
                                       (when (= "libraries" section)
                                         (parse-library-line versions line))))
                               (sort-by :catalog/alias)
                               vec)
       :catalog/plugins (->> section-lines
                             (keep (fn [[section line]]
                                     (when (= "plugins" section)
                                       (parse-plugin-line versions line))))
                             (sort-by :plugin/alias)
                             vec)})))

(defn- project-record [research-root include-value]
  (let [name (project-name include-value)
        dir (normalize-path (project-dir research-root include-value))
        build-file (first-file (build-file-candidates research-root dir name))
        dependencies (if build-file
                       (dependencies-from-build-file build-file)
                       [])]
    {:project/path (project-path include-value)
     :project/name name
     :project/dir (slash-path dir)
     :project/build-file (some-> build-file normalize-path slash-path)
     :source/roots (conventional-source-roots dir)
     :dependencies dependencies}))

(defn- dependency-summary [projects]
  (let [dependencies (mapcat :dependencies projects)]
    {:dependencies/count (count dependencies)
     :dependencies/by-kind (->> dependencies
                                (group-by :dependency/kind)
                                (map (fn [[kind rows]]
                                       {:kind kind
                                        :count (count rows)}))
                                (sort-by :kind)
                                vec)
     :dependencies/by-configuration (->> dependencies
                                         (group-by :dependency/configuration)
                                         (map (fn [[configuration rows]]
                                                {:configuration configuration
                                                 :count (count rows)}))
                                         (sort-by :configuration)
                                         vec)}))

(defn- coordinate-group [coordinate]
  (second (re-find #"^([^:]+):" (or coordinate ""))))

(defn java-classpath-package-roots
  "Return deterministic Java package roots implied by dependency coordinates."
  [classpath-report]
  (let [catalog-groups (keep :catalog/group (get-in classpath-report [:version-catalog :catalog/libraries]))
        dependency-groups (->> (:projects classpath-report)
                               (mapcat :dependencies)
                               (keep (comp coordinate-group :dependency/coordinate)))
        groups (concat catalog-groups dependency-groups)
        aliases (mapcat #(get java-package-root-aliases % []) groups)]
    (->> (concat groups aliases)
         (remove str/blank?)
         set
         sort
         vec)))

(defn kotlin-classpath-types
  "Return deterministic Kotlin type-name to type-id seeds for implicit Gradle APIs."
  [_classpath-report]
  (into (sorted-map) gradle-kotlin-classpath-types))

(defn run-classpath-inventory
  "Discover Gradle/Kotlin classpath inputs for the research Pkl checkout."
  ([] (run-classpath-inventory {}))
  ([opts]
   (let [project-root (normalize-path (or (:project-root opts)
                                          (System/getProperty "user.dir")))
         research-root (normalize-path (or (:research/root opts)
                                           (:research-root opts)
                                           (default-research-root project-root)))
         output-file (report-file project-root opts)
         settings (parse-settings (.resolve research-root "settings.gradle.kts"))
         root-project (project-record research-root ":")
         child-projects (mapv #(project-record research-root %)
                              (:settings/includes settings))
         projects (into [root-project] child-projects)
         catalog (parse-version-catalog (.resolve research-root "gradle/libs.versions.toml"))
         source-roots (mapcat :source/roots projects)
         classpath-package-roots (java-classpath-package-roots {:projects projects
                                                                :version-catalog catalog})
         classpath-types (kotlin-classpath-types {:projects projects
                                                  :version-catalog catalog})
         report (merge
                 {:report/type :vibeformer.report/research-classpath
                  :project/id (or (:project/id opts) default-project-id)
                  :project/root (slash-path project-root)
                  :research/root (slash-path research-root)
                  :settings settings
                  :version-catalog catalog
                  :projects projects
                  :projects/count (count projects)
                  :java/classpath-package-roots classpath-package-roots
                  :java/classpath-package-roots/count (count classpath-package-roots)
                  :kotlin/classpath-types classpath-types
                  :kotlin/classpath-types/count (count classpath-types)
                  :source-roots/count (count source-roots)
                  :source-roots/by-kind (->> source-roots
                                             (group-by :source/kind)
                                             (map (fn [[kind rows]]
                                                    {:kind kind
                                                     :count (count rows)}))
                                             (sort-by :kind)
                                             vec)}
                 (dependency-summary projects))
         output (slash-path (normalize-path output-file))
         report (assoc report :report/file output)]
     (write-edn! output-file report)
     report)))

(defn- parse-cli-opts [value]
  (if (nil? value)
    {}
    (let [opts (edn/read-string value)]
      (when-not (map? opts)
        (throw (ex-info "Research classpath options must be an EDN map."
                        {:value value
                         :parsed opts})))
      opts)))

(defn -main [& args]
  (let [[opts-edn & extra] args]
    (when (seq extra)
      (throw (ex-info "Unexpected research classpath arguments."
                      {:args args
                       :expected "optional EDN options map"})))
    (let [result (run-classpath-inventory (parse-cli-opts opts-edn))]
      (println (str "Research classpath -> " (:report/file result)))
      (println (format "projects: %s, source roots: %s, dependencies: %s"
                       (:projects/count result)
                       (:source-roots/count result)
                       (:dependencies/count result)))
      (shutdown-agents))))
