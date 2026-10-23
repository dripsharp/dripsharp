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
  (:import (java.io ByteArrayInputStream)
           (java.util.concurrent TimeUnit)
           (javax.imageio ImageIO)
           (org.apache.pdfbox.cos COSName)
           (org.apache.pdfbox.pdmodel PDDocument PDDocumentInformation PDPage PDResources)
           (org.apache.pdfbox.pdmodel.common PDRectangle)
           (org.apache.pdfbox.pdmodel.graphics.color PDDeviceGray)
           (org.apache.pdfbox.pdmodel.graphics.image PDImageXObject)))

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

(defn- out [name] (str (io/file (str @@#'contract/out-root) name)))

(defn- envelope
  "Checks that out is exactly one JSON line and returns it parsed."
  [^String out]
  (is (str/ends-with? out "\n"))
  (is (= 1 (count (str/split-lines out))) (str "one JSON line: " out))
  (#'contract/parse out))

(defn- jbig2-pdf
  "A page whose image stream is a JBIG2 file header with reserved flag bits
  set, which the JBIG2 decoder warns about on Console.Out."
  [path]
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)
          data (byte-array (map unchecked-byte [0x97 0x4a 0x42 0x32 0x0d 0x0a 0x1a 0x0a 0x07 0 0 0 1]))
          image (PDImageXObject. doc (ByteArrayInputStream. data) COSName/JBIG2_DECODE 1 1 1
                                 PDDeviceGray/INSTANCE)]
      (.setResources page (PDResources.))
      (.add (.getResources page) image)
      (.addPage doc page)
      (.save doc (io/file path)))))

(deftest library-console-output-stays-off-stdout
  (jbig2-pdf (out "jbig2.pdf"))
  (let [{:keys [exit out]} (dotnet-run ["images" (out "jbig2.pdf")])
        json (envelope out)]
    (is (= 0 exit))
    (is (= "decode-failed" (get (first (get-in json ["result" "images"])) "error")))))

(defn- run-with-timeout
  "Runs the .NET CLI, killing it after 60 s; returns {:exit :out}, :exit nil on timeout."
  [args]
  (let [p (.start (ProcessBuilder. ^java.util.List (into ["dotnet" dll] args)))]
    (.close (.getOutputStream p))
    (if (.waitFor p 60 TimeUnit/SECONDS)
      {:exit (.exitValue p) :out (slurp (.getInputStream p) :encoding "UTF-8")}
      (do (.destroyForcibly p) {:exit nil :out ""}))))

(deftest non-regular-inputs-are-io-errors
  (let [fifo (out "input.fifo")]
    (is (= 0 (:exit (shell/sh "mkfifo" fifo))))
    (doseq [path ["/dev/null" fifo (str @@#'contract/out-root)]]
      (let [{:keys [exit out]} (run-with-timeout ["info" path])]
        (is (= 3 exit) path)
        (when exit (is (= "io" (get-in (envelope out) ["error" "code"])) path))))))

(defn- utf8 [^String s] (seq (.getBytes s "UTF-8")))

(deftest invalid-utf8-values-are-usage-errors
  (doseq [[name bytes] [["value" (concat (utf8 "{\"TextField\":\"") [0xff] (utf8 "\"}"))]
                        ["key" (concat (utf8 "{\"") [0xff] (utf8 "\":\"x\"}"))]]]
    (let [values (out (str "utf8-" name ".json"))
          output (out (str "utf8-" name ".pdf"))]
      (with-open [o (io/output-stream values)] (.write o (byte-array (map unchecked-byte bytes))))
      (let [{:keys [exit out]} (dotnet-run ["form-fill" "--values" values "--out" output @contract/form-doc])]
        (is (= 2 exit) name)
        (is (= "usage" (get-in (envelope out) ["error" "code"])) name)
        (is (not (.exists (io/file output))) name)))))

(deftest binary-rendering-writes-a-bilevel-png
  (let [dir (out "render-binary")
        {:keys [exit out]} (dotnet-run ["render" "--image-type" "binary" "--out-dir" dir @contract/jpeg])
        page (first (get-in (envelope out) ["result" "pages"]))
        image (ImageIO/read (io/file dir "page-0001.png"))
        pixels (set (for [y (range (.getHeight image)) x (range (.getWidth image))] (.getRGB image x y)))]
    (is (= 0 exit))
    (is (= [(get page "width") (get page "height")] [(.getWidth image) (.getHeight image)]))
    (is (every? #{(unchecked-int 0xff000000) (unchecked-int 0xffffffff)} pixels))
    (is (= 2 (count pixels)) "both black and white pixels")))

(deftest timing-milliseconds-have-at-most-three-fraction-digits
  (let [{:keys [exit out]} (dotnet-run ["info" "--warmup" "1" "--repeat" "4" @contract/hello])
        timing (second (re-find #"\"timing\":(\{.*\})\}$" (str/trim out)))
        numbers (re-seq #"[0-9.]+" (second (re-find #"\"iterationsMs\":\[([^\]]*)\]" timing)))
        median (second (re-find #"\"medianMs\":([^,]*)," timing))]
    (is (= 0 exit))
    (is (= 4 (count numbers)))
    (doseq [n (cons median numbers)]
      (is (re-matches #"(0|[1-9][0-9]*)(\.[0-9]{0,2}[1-9])?" n) n))))

(defn -main [& _]
  (build!)
  (let [{:keys [fail error]}
        (try (run-tests 'pdfcli.dotnet-test)
             (finally (when (realized? @#'contract/out-root)
                        (#'contract/delete-tree! (io/file @@#'contract/out-root)))))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
