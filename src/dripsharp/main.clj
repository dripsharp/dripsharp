(ns dripsharp.main
  (:require [clojure.string :as str]
            [dripsharp.compiler :as compiler]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.language-snippet-contract :as language-snippet-contract]
            [dripsharp.language-snippet-runner :as language-snippet-runner]
            [dripsharp.packaging :as packaging]
            [dripsharp.pdfcube.fontbox-differential :as pdfcube-fontbox-differential]
            [dripsharp.pdfcube.family-build :as pdfcube-family-build]
            [dripsharp.pdfcube.family-packaging :as pdfcube-family-packaging]
            [dripsharp.pdfcube.family-workflows :as pdfcube-family-workflows]
            [dripsharp.pdfcube.io-differential :as pdfcube-io-differential]
            [dripsharp.pdfcube.pdfbox-differential
             :as pdfcube-pdfbox-differential]
            [dripsharp.pdfcube.pdfbox-document-lifecycle-differential
             :as pdfcube-pdfbox-document-lifecycle-differential]
            [dripsharp.pdfcube.pdfbox-bidi-differential
             :as pdfcube-pdfbox-bidi-differential]
            [dripsharp.pdfcube.pdfbox-font-text-differential
             :as pdfcube-pdfbox-font-text-differential]
            [dripsharp.pdfcube.pdfbox-graphics-differential
             :as pdfcube-pdfbox-graphics-differential]
            [dripsharp.pdfcube.pdfbox-image-differential
             :as pdfcube-pdfbox-image-differential]
            [dripsharp.pdfcube.pdfbox-interaction-differential
             :as pdfcube-pdfbox-interaction-differential]
            [dripsharp.pdfcube.pdfbox-interchange-differential
             :as pdfcube-pdfbox-interchange-differential]
            [dripsharp.pdfcube.pdfbox-low-level-differential
             :as pdfcube-pdfbox-low-level-differential]
            [dripsharp.pdfcube.pdfbox-manipulation-differential
             :as pdfcube-pdfbox-manipulation-differential]
            [dripsharp.pdfcube.pdfbox-printing-differential
             :as pdfcube-pdfbox-printing-differential]
            [dripsharp.pdfcube.pdfbox-rendering-differential
             :as pdfcube-pdfbox-rendering-differential]
            [dripsharp.pdfcube.pdfbox-security-differential
             :as pdfcube-pdfbox-security-differential]
            [dripsharp.pdfcube.preflight-differential
             :as pdfcube-preflight-differential]
            [dripsharp.pdfcube.preflight-corpus
             :as pdfcube-preflight-corpus]
            [dripsharp.pdfcube.xmpbox-metadata-differential
             :as pdfcube-xmpbox-metadata-differential]
            [dripsharp.pkl-core-corpus-runner :as pkl-core-corpus-runner]
            [dripsharp.pkl-core-test-contract :as pkl-core-test-contract])
  (:import [clojure.lang ExceptionInfo]))

(defn- fail!
  [message exit]
  (binding [*out* *err*]
    (println message))
  (System/exit exit))

(defn -main
  [& args]
  (if-not (or (contains? #{["generate"] ["verify"] ["pack"] ["package"] ["differential"]
                           ["pdfcube-io-differential"]
                           ["pdfcube-fontbox-differential"]
                           ["pdfcube-family-build"]
                           ["pdfcube-family-package"]
                           ["pdfcube-family-workflows"]
                           ["pdfcube-pdfbox-differential"]
                           ["pdfcube-pdfbox-bidi-differential"]
                           ["pdfcube-pdfbox-document-lifecycle-differential"]
                           ["pdfcube-pdfbox-font-text-differential"]
                           ["pdfcube-pdfbox-graphics-differential"]
                           ["pdfcube-pdfbox-image-differential"]
                           ["pdfcube-pdfbox-interchange-differential"]
                           ["pdfcube-pdfbox-interaction-differential"]
                           ["pdfcube-pdfbox-low-level-differential"]
                           ["pdfcube-pdfbox-manipulation-differential"]
                           ["pdfcube-pdfbox-printing-differential"]
                           ["pdfcube-pdfbox-rendering-differential"]
                           ["pdfcube-pdfbox-security-differential"]
                           ["pdfcube-preflight-corpus"]
                           ["pdfcube-preflight-differential"]
                           ["pdfcube-xmpbox-metadata-differential"]
                           ["language-snippet-contract"] ["language-snippet-package"]
                           ["pkl-core-test-contract"] ["pkl-core-corpus"]}
                         (vec args))
              (and (= 2 (count args))
                   (contains? #{"generate" "verify" "pack" "package"} (first args))))
    (fail! "Usage: clojure -M:run generate|verify|pack|package [profile-name|profile.edn]|differential|pdfcube-family-build|pdfcube-family-package|pdfcube-family-workflows|pdfcube-io-differential|pdfcube-fontbox-differential|pdfcube-pdfbox-differential|pdfcube-pdfbox-bidi-differential|pdfcube-pdfbox-document-lifecycle-differential|pdfcube-pdfbox-font-text-differential|pdfcube-pdfbox-graphics-differential|pdfcube-pdfbox-image-differential|pdfcube-pdfbox-interchange-differential|pdfcube-pdfbox-interaction-differential|pdfcube-pdfbox-low-level-differential|pdfcube-pdfbox-manipulation-differential|pdfcube-pdfbox-printing-differential|pdfcube-pdfbox-rendering-differential|pdfcube-pdfbox-security-differential|pdfcube-preflight-corpus|pdfcube-preflight-differential|pdfcube-xmpbox-metadata-differential|language-snippet-contract|language-snippet-package|pkl-core-test-contract|pkl-core-corpus" 2)
    (try
      (case (first args)
        "generate" (harness/generate! {:profile (or (second args) "pkl-parser")})
        "verify" (compiler/verify-clean-build! {:profile (or (second args) "pkl-parser")})
        "pack" (packaging/pack-verified-profile!
                {:profile (or (second args) "pkl-parser")})
        "package" (packaging/verify-package-consumption!
                   {:profile (or (second args) "pkl-parser")})
        "differential" (differential/verify-differential!)
        "pdfcube-family-build" (pdfcube-family-build/verify!)
        "pdfcube-family-package" (pdfcube-family-packaging/verify!)
        "pdfcube-family-workflows" (pdfcube-family-workflows/verify!)
        "pdfcube-io-differential" (pdfcube-io-differential/verify!)
        "pdfcube-fontbox-differential" (pdfcube-fontbox-differential/verify!)
        "pdfcube-pdfbox-differential" (pdfcube-pdfbox-differential/verify!)
        "pdfcube-pdfbox-bidi-differential"
        (pdfcube-pdfbox-bidi-differential/verify!)
        "pdfcube-pdfbox-document-lifecycle-differential"
        (pdfcube-pdfbox-document-lifecycle-differential/verify!)
        "pdfcube-pdfbox-font-text-differential"
        (pdfcube-pdfbox-font-text-differential/verify!)
        "pdfcube-pdfbox-graphics-differential"
        (pdfcube-pdfbox-graphics-differential/verify!)
        "pdfcube-pdfbox-image-differential"
        (pdfcube-pdfbox-image-differential/verify!)
        "pdfcube-pdfbox-interchange-differential"
        (pdfcube-pdfbox-interchange-differential/verify!)
        "pdfcube-pdfbox-interaction-differential"
        (pdfcube-pdfbox-interaction-differential/verify!)
        "pdfcube-pdfbox-low-level-differential"
        (pdfcube-pdfbox-low-level-differential/verify!)
        "pdfcube-pdfbox-manipulation-differential"
        (pdfcube-pdfbox-manipulation-differential/verify!)
        "pdfcube-pdfbox-printing-differential"
        (pdfcube-pdfbox-printing-differential/verify!)
        "pdfcube-pdfbox-rendering-differential"
        (pdfcube-pdfbox-rendering-differential/verify!)
        "pdfcube-pdfbox-security-differential"
        (pdfcube-pdfbox-security-differential/verify!)
        "pdfcube-preflight-corpus"
        (pdfcube-preflight-corpus/verify!)
        "pdfcube-preflight-differential"
        (pdfcube-preflight-differential/verify!)
        "pdfcube-xmpbox-metadata-differential"
        (pdfcube-xmpbox-metadata-differential/verify!)
        "language-snippet-contract" (language-snippet-contract/verify-contract!)
        "language-snippet-package" (language-snippet-runner/verify-package-runner!)
        "pkl-core-test-contract" (pkl-core-test-contract/verify-contract!)
        "pkl-core-corpus" (pkl-core-corpus-runner/verify-corpus-runner!))
      (catch ExceptionInfo error
        (let [{:keys [output]} (ex-data error)]
          (fail! (str "DripSharp command failed: " (.getMessage error)
                      (when-not (str/blank? output)
                        (str "\n" (str/trim output))))
                 1)))
      (catch Throwable error
        (fail! (str "DripSharp command failed unexpectedly: " (.getMessage error)) 1)))))
