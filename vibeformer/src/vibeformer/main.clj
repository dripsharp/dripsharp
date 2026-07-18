(ns vibeformer.main
  (:require [clojure.string :as str]
            [vibeformer.compiler :as compiler]
            [vibeformer.differential :as differential]
            [vibeformer.harness :as harness]
            [vibeformer.language-snippet-contract :as language-snippet-contract]
            [vibeformer.language-snippet-runner :as language-snippet-runner]
            [vibeformer.packaging :as packaging])
  (:import [clojure.lang ExceptionInfo]))

(defn- fail!
  [message exit]
  (binding [*out* *err*]
    (println message))
  (System/exit exit))

(defn -main
  [& args]
  (if-not (or (contains? #{["generate"] ["verify"] ["pack"] ["package"] ["differential"]
                           ["language-snippet-contract"] ["language-snippet-package"]}
                         (vec args))
              (and (= 2 (count args))
                   (contains? #{"generate" "verify" "pack" "package"} (first args))))
    (fail! "Usage: clojure -M:run generate|verify|pack|package [profile-name|profile.edn]|differential|language-snippet-contract|language-snippet-package" 2)
    (try
      (case (first args)
        "generate" (harness/generate! {:profile (or (second args) "pkl-parser")})
        "verify" (compiler/verify-clean-build! {:profile (or (second args) "pkl-parser")})
        "pack" (packaging/pack-verified-profile!
                {:profile (or (second args) "pkl-parser")})
        "package" (packaging/verify-package-consumption!
                   {:profile (or (second args) "pkl-parser")})
        "differential" (differential/verify-differential!)
        "language-snippet-contract" (language-snippet-contract/verify-contract!)
        "language-snippet-package" (language-snippet-runner/verify-package-runner!))
      (catch ExceptionInfo error
        (let [{:keys [output]} (ex-data error)]
          (fail! (str "Vibeformer command failed: " (.getMessage error)
                      (when-not (str/blank? output)
                        (str "\n" (str/trim output))))
                 1)))
      (catch Throwable error
        (fail! (str "Vibeformer command failed unexpectedly: " (.getMessage error)) 1)))))
