;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns pdfcli.dotnet-test
  "Runs the contract tests of pdfcli.main-test against the PdfCarton
  implementation in validation/pdfcarton-cli/dotnet, one process per
  invocation, on the same PDFBox test-resource PDFs. Run from the repository
  root after generating PdfCarton: clojure -M:pdfcli-dotnet-test. The
  PDFCARTON_ROOT environment variable names the generated PdfCarton checkout
  to build against (default target/generated/pdfcarton)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests test-vars]]
            [pdfcli.main :as main]
            [pdfcli.main-test :as contract])
  (:import (org.apache.pdfbox.cos COSName)
           (org.apache.pdfbox.pdmodel PDDocument PDDocumentInformation PDPage)
           (org.apache.pdfbox.pdmodel.common PDRectangle)))

(def project "validation/pdfcarton-cli/dotnet")

(def dll (str project "/bin/Release/net10.0/DripSharp.PdfCarton.Tests.dll"))

(defn- pdfcarton-root []
  (.getAbsolutePath (io/file (or (System/getenv "PDFCARTON_ROOT") "target/generated/pdfcarton"))))

(defn- pdfcarton-version
  "The DripSharp.PdfCarton package version the CLI reports as libraryVersion."
  []
  (second (re-find #"<Version>([^<]+)</Version>"
                   (slurp (io/file (pdfcarton-root) "src/DripSharp.PdfCarton/DripSharp.PdfCarton.csproj")))))

(defn- build! []
  (let [{:keys [exit out]} (shell/sh "dotnet" "build" project "-c" "Release" "-warnaserror"
                                     "-p:NuGetAudit=false" (str "-p:PdfCartonRoot=" (pdfcarton-root)))]
    (when-not (zero? exit)
      (throw (ex-info (str "dotnet build failed:\n" (str/join "\n" (take-last 40 (str/split-lines out))))
                      {:exit exit})))))

(defn- dotnet-run
  "Runs the .NET CLI in its own process; same result shape as pdfcli.main/run."
  [args]
  (let [{:keys [exit out]} (apply shell/sh "dotnet" dll (concat args [:out-enc "UTF-8"]))]
    (is (not (str/starts-with? out "﻿")) "no byte-order mark")
    {:exit exit :out out}))

(defmacro ^:private on-dotnet [& body]
  `(binding [contract/*library-version* (pdfcarton-version)]
     (with-redefs [main/run dotnet-run]
       ~@body)))

(deftest contract-tests-pass-on-pdfcarton
  (on-dotnet
   (test-vars [#'contract/every-command-reports-its-contract-shape
               #'contract/output-is-deterministic-across-runs
               #'contract/errors-have-exit-codes-and-error-json
               #'contract/timing-mode-reports-warm-median-and-allocations
               #'contract/resource-walk-descends-forms-and-stops-cycles
               #'contract/encrypt-honours-passwords-and-permissions
               #'contract/numbered-file-names-use-ascii-digits-in-any-locale])))

(defn- without-implementation [^String out]
  (str/replace-first out #"\"implementation\":\{[^{}]*\}," ""))

(defn- canonical-json-pdf
  "A page whose boxes and /Info strings exercise the contract's number and
  string rules."
  [path]
  (with-open [doc (PDDocument.)]
    (let [box (fn [llx lly urx ury]
                (doto (PDRectangle.)
                  (.setLowerLeftX (float llx)) (.setLowerLeftY (float lly))
                  (.setUpperRightX (float urx)) (.setUpperRightY (float ury))))
          page (PDPage. ^PDRectangle (box -12.5 1.4e-45 1e10 595.276))
          info (PDDocumentInformation.)]
      (.setCropBox page (box 0.1 1.5e-5 1.4 300.7))
      (.setRotation page 90)
      (.setTitle info (str "q\"\\/" (char 1) "\n\té\u007f 😀"))
      (.setAuthor info "")
      (.setCustomMetadataValue info "b" "1")
      (.setCustomMetadataValue info "a" "2")
      (.setCustomMetadataValue info "B" "3")
      (.setItem (.getCOSObject info) (COSName/getPDFName "Trapped") (COSName/getPDFName "Unknown"))
      (.setDocumentInformation doc info)
      (.addPage doc page)
      (.save doc (io/file path)))))

(deftest canonical-json-matches-the-jvm-byte-for-byte
  (let [file (str (io/file (str @@#'contract/out-root) "canonical.pdf"))
        _ (canonical-json-pdf file)
        jvm (main/run ["info" file])
        dotnet (dotnet-run ["info" file])]
    (is (= 0 (:exit jvm) (:exit dotnet)))
    (is (= (without-implementation (:out jvm)) (without-implementation (:out dotnet))))))

(defn -main [& _]
  (build!)
  (let [{:keys [fail error]}
        (try (run-tests 'pdfcli.dotnet-test)
             (finally (when (realized? @#'contract/out-root)
                        (#'contract/delete-tree! (io/file @@#'contract/out-root)))))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
