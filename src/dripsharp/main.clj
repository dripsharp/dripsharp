(ns dripsharp.main
  (:require [clojure.string :as str]
            [dripsharp.compiler :as compiler]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.language-snippet-contract :as language-snippet-contract]
            [dripsharp.language-snippet-runner :as language-snippet-runner]
            [dripsharp.packaging :as packaging]
            [dripsharp.pdfcube.fontbox-differential :as pdfcube-fontbox-differential]
            [dripsharp.pdfcube.io-differential :as pdfcube-io-differential]
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
                           ["pdfcube-xmpbox-metadata-differential"]
                           ["language-snippet-contract"] ["language-snippet-package"]
                           ["pkl-core-test-contract"] ["pkl-core-corpus"]}
                         (vec args))
              (and (= 2 (count args))
                   (contains? #{"generate" "verify" "pack" "package"} (first args))))
    (fail! "Usage: clojure -M:run generate|verify|pack|package [profile-name|profile.edn]|differential|pdfcube-io-differential|pdfcube-fontbox-differential|pdfcube-xmpbox-metadata-differential|language-snippet-contract|language-snippet-package|pkl-core-test-contract|pkl-core-corpus" 2)
    (try
      (case (first args)
        "generate" (harness/generate! {:profile (or (second args) "pkl-parser")})
        "verify" (compiler/verify-clean-build! {:profile (or (second args) "pkl-parser")})
        "pack" (packaging/pack-verified-profile!
                {:profile (or (second args) "pkl-parser")})
        "package" (packaging/verify-package-consumption!
                   {:profile (or (second args) "pkl-parser")})
        "differential" (differential/verify-differential!)
        "pdfcube-io-differential" (pdfcube-io-differential/verify!)
        "pdfcube-fontbox-differential" (pdfcube-fontbox-differential/verify!)
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
