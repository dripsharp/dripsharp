(ns vibeformer.main
  (:require [clojure.string :as str]
            [vibeformer.compiler :as compiler]
            [vibeformer.differential :as differential]
            [vibeformer.harness :as harness]
            [vibeformer.packaging :as packaging])
  (:import [clojure.lang ExceptionInfo]))

(defn- fail!
  [message exit]
  (binding [*out* *err*]
    (println message))
  (System/exit exit))

(defn -main
  [& args]
  (if-not (contains? #{["generate"] ["verify"] ["package"] ["differential"]} (vec args))
    (fail! "Usage: clojure -M:run generate|verify|package|differential" 2)
    (try
      (case (first args)
        "generate" (harness/generate!)
        "verify" (compiler/verify-clean-build!)
        "package" (packaging/verify-package-consumption!)
        "differential" (differential/verify-differential!))
      (catch ExceptionInfo error
        (let [{:keys [output]} (ex-data error)]
          (fail! (str "Vibeformer command failed: " (.getMessage error)
                      (when-not (str/blank? output)
                        (str "\n" (str/trim output))))
                 1)))
      (catch Throwable error
        (fail! (str "Vibeformer command failed unexpectedly: " (.getMessage error)) 1)))))
