(ns dripsharp.pdfcube-preflight-corpus-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(def ^:private exact-preflight-assembly-dependencies
  [{:assembly-name "DripSharp.PdfCarton"
    :package-id "DripSharp.PdfCarton"
    :version "3.0.8-alpha.1"
    :target-framework "netstandard2.0"}
   {:assembly-name "DripSharp.PdfCarton.Fonts"
    :package-id "DripSharp.PdfCarton.Fonts"
    :version "3.0.8-alpha.1"
    :target-framework "netstandard2.0"}
   {:assembly-name "DripSharp.PdfCarton.IO"
    :package-id "DripSharp.PdfCarton.IO"
    :version "3.0.8-alpha.1"
    :target-framework "netstandard2.0"}
   {:assembly-name "DripSharp.PdfCarton.Xmp"
    :package-id "DripSharp.PdfCarton.Xmp"
    :version "3.0.8-alpha.1"
    :target-framework "netstandard2.0"}])

(defn- preflight-dependencies
  [expected]
  (#'corpus/preflight-assembly-dependencies
   "DripSharp.PdfCarton.Preflight" expected))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest packed-preflight-dependency-entries-are-exactly-netstandard
  (let [actual (preflight-dependencies exact-preflight-assembly-dependencies)]
    (is (= exact-preflight-assembly-dependencies actual))
    (is (= ["lib/netstandard2.0/DripSharp.PdfCarton.dll"
            "lib/netstandard2.0/DripSharp.PdfCarton.Fonts.dll"
            "lib/netstandard2.0/DripSharp.PdfCarton.IO.dll"
            "lib/netstandard2.0/DripSharp.PdfCarton.Xmp.dll"]
           (mapv
            (fn [{:keys [assembly-name target-framework]}]
              (str "lib/" target-framework "/" assembly-name ".dll"))
            actual)))))

(deftest packed-preflight-dependency-contract-fails-closed
  (testing "the former IO and Fonts net10.0 package paths are rejected"
    (doseq [assembly-name
            ["DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"]]
      (let [stale
            (mapv
             (fn [dependency]
               (if (= assembly-name (:assembly-name dependency))
                 (assoc dependency :target-framework "net10.0")
                 dependency))
             exact-preflight-assembly-dependencies)
            error (caught #(preflight-dependencies stale))]
        (is (= :invalid-preflight-assembly-dependencies
               (:kind (ex-data error)))
            assembly-name)
        (is (= stale (:actual (ex-data error))) assembly-name))))
  (testing "missing, extra, and otherwise incorrect dependency evidence is rejected"
    (doseq [[label changed]
            [["missing" (pop exact-preflight-assembly-dependencies)]
             ["extra"
              (conj exact-preflight-assembly-dependencies
                    {:assembly-name "DripSharp.PdfCarton.Unexpected"
                     :package-id "DripSharp.PdfCarton.Unexpected"
                     :version "3.0.8-alpha.1"
                     :target-framework "netstandard2.0"})]
             ["framework"
              (assoc-in exact-preflight-assembly-dependencies
                        [0 :target-framework] "net9.0")]
             ["package"
              (assoc-in exact-preflight-assembly-dependencies
                        [0 :package-id] "DripSharp.PdfCarton.Unexpected")]]]
      (let [error (caught #(preflight-dependencies changed))]
        (is (= :invalid-preflight-assembly-dependencies
               (:kind (ex-data error)))
            label)
        (is (= exact-preflight-assembly-dependencies
               (:expected (ex-data error)))
            label)))))

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
         "DRIPSHARP_PDFCARTON_PREFLIGHT_CORPUS_MANIFEST_V1\n"))
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

(deftest corpus-manifest-requires-exactly-one-edn-value
  (let [root (paths/workspace-root)
        original
        (Files/readString
         (paths/resolve-path
          root "validation" "pdfcube-preflight-corpus" "corpus.edn")
         StandardCharsets/UTF_8)]
    (doseq [[label contents reason]
            [["empty" "" :empty-edn]
             ["invalid" "{" :invalid-edn]
             ["trailing" (str original "\n{}") :trailing-data]]]
      (let [manifest
            (write! (.resolve (temp-dir) (str label "-corpus.edn")) contents)
            error
            (try
              (corpus/validate-manifest! root manifest)
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :invalid-preflight-corpus-manifest
               (:kind (ex-data error)))
            label)
        (is (= reason (:reason (ex-data error))) label)))))

(deftest package-runner-declares-timeout-crash-leak-and-isolation-controls
  (let [root (paths/workspace-root)
        runner
        (Files/readString
         (paths/resolve-path
          root "validation" "pdfcube-preflight-corpus"
          "PdfCartonPreflightCorpusRunner.cs")
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
