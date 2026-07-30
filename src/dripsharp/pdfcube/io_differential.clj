(ns dripsharp.pdfcube.io-differential
  "Versioned, data-driven PDFBox baseline versus DripSharp.PdfCarton.IO package proof."
  (:require [dripsharp.baseline :as baseline]
            [dripsharp.differential :as differential]
            [dripsharp.target-execution :as target-execution]))

(def ^:private contract
  (differential/read-contract "targets/pdfcube/validation/io.edn"))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def supported-hosts
  (get-in contract [:runner :supported-hosts]))

(def required-trace-families
  (set (get-in contract [:observation :required-families])))

(defn trace-summary
  "Validates one versioned DripSharp.PdfCarton.IO trace and returns its coverage."
  [trace]
  (differential/trace-summary contract trace))

(defn verify!
  "Runs clean deterministic packing, isolated consumption, and the complete
  pinned Java/package differential for DripSharp.PdfCarton.IO."
  ([] (verify! {}))
  ([options]
   (differential/run!
    (assoc options
           :contract
           (target-execution/execution-differential-contract
            :pdfcube contract)))))
