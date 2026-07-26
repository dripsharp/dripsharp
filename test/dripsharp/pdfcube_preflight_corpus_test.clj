(ns dripsharp.pdfcube-preflight-corpus-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.preflight-corpus :as corpus])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  []
  (Files/createTempDirectory
   "pdfcube-preflight-corpus-test"
   (make-array FileAttribute 0)))

(defn- write!
  [^Path path contents]
  (Files/createDirectories (.getParent path)
                           (make-array FileAttribute 0))
  (Files/writeString path contents StandardCharsets/UTF_8
                     (make-array OpenOption 0))
  path)

(defn- validated-manifest
  []
  (let [root (paths/workspace-root)]
    (corpus/validate-manifest!
     root
     (paths/resolve-path
      root "validation" "pdfcube-preflight-corpus" "corpus.edn"))))

(defn- conformant-rows
  [validated origin]
  (mapv
   (fn [{:keys [id format expected-outcome payload-sha256]}]
     (let [valid? (= :valid expected-outcome)]
       {:case-id id
        :origin origin
        :format (name format)
        :expected-outcome (name expected-outcome)
        :input-sha256 payload-sha256
        :status "PASS"
        :valid (str valid?)
        :error-count (if valid? "0" "1")
        :error-codes (if valid? "" "1.0")
        :warnings (if valid? "" "false")
        :pages (if valid? "" "null")
        :details (if valid? "" "normalized detail")
        :source-closed "true"
        :document-closed "true"
        :diagnostic ""}))
   (:cases validated)))

(deftest durable-corpus-covers-the-supported-preflight-contract
  (let [validated (validated-manifest)
        coverage (:coverage validated)
        staging (corpus/stage-corpus!
                 validated (.resolve (temp-dir) "corpus"))
        execution (corpus/write-execution-manifest!
                   validated (.resolve (temp-dir) "manifest.tsv"))]
    (is (= [:pdf-a1a :pdf-a1b] (:formats coverage)))
    (is (= [:invalid :malformed :valid] (:outcomes coverage)))
    (is (= 11 (:cases coverage)))
    (is (= 8 (:source-fixtures coverage)))
    (is (every? #(Files/isRegularFile
                  (.resolve staging (:staged-file %))
                  (make-array java.nio.file.LinkOption 0))
                (:cases validated)))
    (is (str/starts-with?
         (Files/readString execution StandardCharsets/UTF_8)
         "DRIPSHARP_PDFCUBE_PREFLIGHT_CORPUS_MANIFEST_V1\n"))
    (is (= "Apache-2.0"
           (get-in validated [:redistribution :license])))
    (is (not (str/blank?
              (get-in validated [:redistribution :constraint]))))))

(deftest corpus-comparator-and-failure-controls-fail-closed
  (let [validated (validated-manifest)
        root (temp-dir)
        upstream (write!
                  (.resolve root "upstream.tsv")
                  (corpus/render-results
                   (conformant-rows validated "upstream-java")))
        package (write!
                 (.resolve root "package.tsv")
                 (corpus/render-results
                  (conformant-rows validated "package-dotnet")))
        comparison (corpus/compare-results validated upstream package)
        controls (corpus/prove-fail-closed-controls!
                  validated upstream (.resolve root "controls"))]
    (is (= 11 (:total comparison)))
    (is (= 11 (:matched comparison)))
    (is (zero? (:mismatched comparison)))
    (is (= #{:perturbation :timeout :crash :leak :missing :nondeterminism}
           (set (keys controls))))
    (is (every? true? (vals controls)))))

(deftest corpus-provenance-checksums-are-enforced
  (let [root (paths/workspace-root)
        original
        (Files/readString
         (paths/resolve-path
          root "validation" "pdfcube-preflight-corpus" "corpus.edn")
         StandardCharsets/UTF_8)
        changed
        (str/replace-first
         original
         "640168ad8f5ec872ec082992664fd92a7df1c2e53e12af45f134c8be951ed342"
         (apply str (repeat 64 "0")))
        manifest (write! (.resolve (temp-dir) "corpus.edn") changed)
        error
        (try
          (corpus/validate-manifest! root manifest)
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :preflight-corpus-checksum-mismatch
           (:kind (ex-data error))))))

(deftest package-runner-declares-timeout-crash-leak-and-isolation-controls
  (let [root (paths/workspace-root)
        runner
        (Files/readString
         (paths/resolve-path
          root "validation" "pdfcube-preflight-corpus"
          "PdfCubePreflightCorpusRunner.cs")
         StandardCharsets/UTF_8)
        gate
        (Files/readString
         (paths/resolve-path
          root "src" "dripsharp" "pdfcube" "preflight_corpus.clj")
         StandardCharsets/UTF_8)]
    (doseq [required ["WaitForExitAsync" "entireProcessTree: true"
                      "\"TIMEOUT\"" "\"CRASH\"" "\"LEAK\""
                      "VerifyLoadedAssemblies"]]
      (is (str/includes? runner required)))
    (doseq [required ["--packages" "--no-cache" "--force-evaluate"
                      "NUGET_PACKAGES" "DOTNET_CLI_HOME"
                      "inspect-consumer-dependencies!"
                      "write-packed-assembly-manifest!"]]
      (is (str/includes? gate required)))
    (is (not (str/includes? runner "ProjectReference")))
    (is (not (str/includes? runner "target/generated")))))
