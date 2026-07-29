(ns dripsharp.pdfcube.xmpbox-metadata-differential
  "Versioned, data-driven PDFBox baseline versus PdfCube.XmpBox package proof."
  (:require [dripsharp.baseline :as baseline]
            [dripsharp.differential :as differential]
            [dripsharp.target-execution :as target-execution]))

(def ^:private contract
  (differential/read-contract
   "targets/pdfcube/validation/xmpbox.edn"))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def supported-hosts
  (get-in contract [:runner :supported-hosts]))

(def required-trace-families
  (set (get-in contract [:observation :required-families])))

(defn trace-summary
  "Validates one versioned XmpBox metadata trace and returns its coverage."
  [trace]
  (differential/trace-summary contract trace))

(defn verify!
  "Runs clean deterministic packing, isolated consumption, complete public
  surface and zero-public-stub gates, and the pinned Java/package XmpBox
  differential."
  ([] (verify! {}))
  ([options]
   (differential/run!
    (assoc options
           :contract
           (target-execution/execution-differential-contract
            :pdfcube contract)))))
