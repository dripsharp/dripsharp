(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.vibeformer/vibeformer)
(def version "0.1.0-SNAPSHOT")
#_ ; alternatively, use MAJOR.MINOR.COMMITS:
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def base-test-java-opts ["-Dslf4j.internal.verbosity=WARN"])
(def default-sample "java-word-count")

(def default-sample-runner-opts
  {"kotlin-basic-declarations" {:kotlin/emit? true}
   "kotlin-api-calls" {:kotlin/emit? true}
   "kotlin-object-overrides" {:kotlin/emit? true}
   "kotlin-top-level" {:kotlin/emit? true}})

(def sample-runner-option-keys
  [:coverage/allow-stubs?
   :coverage/allow-unsupported?
   :allow-stubs?
   :allow-unsupported?
   :csharp/allow-diagnostics?
   :allow-csharp-diagnostics?
   :java/classpath-types
   :java/classpath-package-roots
   :kotlin/classpath-types
   :kotlin/classpath-roots
   :kotlin/analysis-api?
   :kotlin/emit?])

(defn sample-runner-opts
  "Return the options forwarded from the build sample task to sample-runner."
  [sample-name opts]
  (merge (get default-sample-runner-opts (str sample-name))
         (select-keys opts sample-runner-option-keys)))

(defn sample-runner-main-args
  "Return clojure.main args for invoking vibeformer.sample-runner."
  [sample-name opts]
  (let [runner-opts (sample-runner-opts sample-name opts)]
    (cond-> ["-m" "vibeformer.sample-runner" (str sample-name)]
      (seq runner-opts) (conj (pr-str runner-opts)))))

(defn- test-java-opts []
  (cond-> base-test-java-opts
    (>= (.feature (Runtime/version)) 24)
    (conj "--sun-misc-unsafe-memory-access=allow")))

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test]})
        cmds     (b/java-command
                  {:basis      basis
                   :java-opts  (test-java-opts)
                   :main       'clojure.main
                   :main-args  ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn sample
  "Run a committed sample project through the supported pipeline stages.

  Defaults to the java-word-count smoke sample:
    clojure -T:build sample

  Select another sample with:
    clojure -T:build sample :name my-sample

  Pass explicit coverage allow modes through to the sample runner with:
    clojure -T:build sample :name my-sample ':coverage/allow-unsupported?' true
    clojure -T:build sample :name my-sample ':coverage/allow-stubs?' true"
  [opts]
  (let [basis (b/create-basis {:aliases [:sample-runner]})
        sample-name (or (:name opts) default-sample)
        main-args (sample-runner-main-args sample-name opts)
        cmds (b/java-command
              {:basis basis
               :java-opts (test-java-opts)
               :main 'clojure.main
               :main-args main-args})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info "Sample run failed." {:sample/name sample-name
                                            :exit exit})))
    opts))

(def research-inventory-option-keys
  [:research/root
   :research-root
   :out])

(defn research-inventory-main-args [opts]
  (let [runner-opts (select-keys opts research-inventory-option-keys)]
    (cond-> ["-m" "vibeformer.research-inventory"]
      (seq runner-opts) (conj (pr-str runner-opts)))))

(defn research-inventory
  "Run a read-only inventory over ../research/pkl and write target/research-pkl/inventory.edn.

  Override the source or output path with:
    clojure -T:build research-inventory ':research/root' ../research/pkl ':out' target/research-pkl/inventory.edn"
  [opts]
  (let [basis (b/create-basis {:aliases [:sample-runner]})
        cmds (b/java-command
              {:basis basis
               :java-opts (test-java-opts)
               :main 'clojure.main
               :main-args (research-inventory-main-args opts)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info "Research inventory failed." {:exit exit})))
    opts))

(def research-dry-run-option-keys
  [:research/root
   :research-root
   :project/id
   :dry-run/mode
   :mode
   :dry-run/out
   :out-dir])

(defn research-dry-run-main-args [opts]
  (let [runner-opts (select-keys opts research-dry-run-option-keys)]
    (cond-> ["-m" "vibeformer.research-dry-run"]
      (seq runner-opts) (conj (pr-str runner-opts)))))

(defn research-dry-run
  "Run a read-only staged dry-run over ../research/pkl under target/research-pkl.

  Defaults to facts-only mode:
    clojure -T:build research-dry-run

  Make the intended boundary explicit with:
    clojure -T:build research-dry-run ':dry-run/mode' :facts-only
    clojure -T:build research-dry-run ':dry-run/mode' :emit-only
    clojure -T:build research-dry-run ':dry-run/mode' :compile-capable"
  [opts]
  (let [basis (b/create-basis {:aliases [:sample-runner]})
        cmds (b/java-command
              {:basis basis
               :java-opts (test-java-opts)
               :main 'clojure.main
               :main-args (research-dry-run-main-args opts)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info "Research dry-run failed." {:exit exit})))
    opts))

(def research-classpath-option-keys
  [:research/root
   :research-root
   :project/id
   :classpath/out
   :out])

(defn research-classpath-main-args [opts]
  (let [runner-opts (select-keys opts research-classpath-option-keys)]
    (cond-> ["-m" "vibeformer.research-classpath"]
      (seq runner-opts) (conj (pr-str runner-opts)))))

(defn research-classpath
  "Discover read-only Gradle/Kotlin classpath inputs for ../research/pkl.

  Writes target/research-pkl/classpath.edn by default."
  [opts]
  (let [basis (b/create-basis {:aliases [:sample-runner]})
        cmds (b/java-command
              {:basis basis
               :java-opts (test-java-opts)
               :main 'clojure.main
               :main-args (research-classpath-main-args opts)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info "Research classpath inventory failed." {:exit exit})))
    opts))

(def research-sample-report-option-keys
  [:project/id
   :inventory
   :inventory/file
   :dry-run
   :dry-run/file
   :samples/root
   :samples-root
   :sample-report/out
   :out
   :top])

(defn research-sample-report-main-args [opts]
  (let [runner-opts (select-keys opts research-sample-report-option-keys)]
    (cond-> ["-m" "vibeformer.research-sample-report"]
      (seq runner-opts) (conj (pr-str runner-opts)))))

(defn research-sample-report
  "Generate sample/task candidates from research inventory, sample diagnostics, and provenance.

  Writes target/research-pkl/sample-selection.edn by default."
  [opts]
  (let [basis (b/create-basis {:aliases [:sample-runner]})
        cmds (b/java-command
              {:basis basis
               :java-opts (test-java-opts)
               :main 'clojure.main
               :main-args (research-sample-report-main-args opts)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info "Research sample selection report failed." {:exit exit})))
    opts))

(defn- pom-template [version]
  [[:description "FIXME: my new library."]
   [:url "https://github.com/vibeformer/vibeformer"]
   [:licenses
    [:license
     [:name "Eclipse Public License 2.0"]
     [:url "https://www.eclipse.org/legal/epl-2.0"]]]
   [:developers
    [:developer
     [:name "Admin"]]]
   [:scm
    [:url "https://github.com/vibeformer/vibeformer"]
    [:connection "scm:git:https://github.com/vibeformer/vibeformer.git"]
    [:developerConnection "scm:git:ssh:git@github.com:vibeformer/vibeformer.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
          :lib lib   :version version
          :jar-file  (format "target/%s-%s.jar" lib version)
          :basis     (b/create-basis {})
          :class-dir class-dir
          :target    "target"
          :src-dirs  ["src"]
          :pom-data  (pom-template version)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
