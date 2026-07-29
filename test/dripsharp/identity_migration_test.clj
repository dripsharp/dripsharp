(ns dripsharp.identity-migration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths])
  (:import [java.nio.file FileVisitOption Files Path]))

(defn- regular-files
  [root]
  (with-open [files (Files/walk (paths/path root)
                                (make-array FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         vec)))

(deftest project-layout-and-launchers-are-rooted
  (let [root (paths/workspace-root)]
    (doseq [relative ["deps.edn" "README.md" "config" "doc" "gradle" "maven"
                      "runtime" "src" "test" "validation"]]
      (is (paths/exists? (paths/resolve-path root relative)) relative))
    (let [deps (slurp (str (paths/resolve-path root "deps.edn")))]
      (is (str/includes? deps "\"dripsharp.main\""))
      (is (str/includes? deps "\"dripsharp.test-runner\"")))))

(deftest clojure-java-and-csharp-identities-are-dripsharp
  (let [root (paths/workspace-root)
        clojure-files
        (mapcat #(filter (fn [^Path file]
                           (str/ends-with? (str file) ".clj"))
                         (regular-files (paths/resolve-path root %)))
                ["src/dripsharp" "test/dripsharp"])
        runtime-files
        (filter (fn [^Path file]
                  (let [name (str (.getFileName file))]
                    (and (str/starts-with? name "DripSharp.")
                         (str/ends-with? name ".cs"))))
                (regular-files (paths/resolve-path root "runtime")))]
    (is (seq clojure-files))
    (doseq [file clojure-files]
      (is (re-find #"(?m)^\(ns dripsharp(?:[.\s])" (slurp (str file)))
          (str file)))
    (is (str/includes?
         (slurp (str (paths/resolve-path
                      root "maven/src/dripsharp/maven/DiscoveryEventSpy.java")))
         "package dripsharp.maven;"))
    (is (str/includes?
         (slurp (str (paths/resolve-path
                      root "maven/resources/META-INF/plexus/components.xml")))
         "<implementation>dripsharp.maven.DiscoveryEventSpy</implementation>"))
    (is (seq runtime-files))
    (doseq [file runtime-files]
      (is (str/includes? (slurp (str file)) "namespace DripSharp.Runtime")
          (str file)))))

(deftest emitted-translator-identity-is-dripsharp
  (let [header
        (java-project/mechanical-source-header
         {:repository "https://example.invalid/upstream.git"
          :revision "0123456789abcdef0123456789abcdef01234567"
          :notice-reference "NOTICE.txt"}
         "src/main/java/example/Example.java")]
    (is (str/includes? header "// Translator: DripSharp "))
    (is (not (re-find #"(?i)vibeformer" header)))))

(deftest host-workflows-use-root-paths-and-product-neutral-runner-selection
  (let [root (paths/workspace-root)
        workflows
        (filter (fn [^Path file]
                  (str/ends-with? (str file) ".yml"))
                (regular-files (paths/resolve-path root ".github/workflows")))]
    (is (seq workflows))
    (doseq [file workflows]
      (let [content (slurp (str file))]
        (is (str/includes? content "DRIPSHARP_WORKERS") (str file))
        (is (not (str/includes? content "vibeformer-proof")) (str file))
        (is (not (str/includes? content "dripsharp-proof")) (str file))
        (is (not (str/includes? content "working-directory:")) (str file))
        (is (not (str/includes? content "vibeformer/")) (str file))))))
