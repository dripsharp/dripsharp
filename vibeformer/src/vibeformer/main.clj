(ns vibeformer.main
  (:require [clojure.string :as str]
            [vibeformer.compiler :as compiler]
            [vibeformer.harness :as harness])
  (:import [clojure.lang ExceptionInfo]))

(defn- fail!
  [message exit]
  (binding [*out* *err*]
    (println message))
  (System/exit exit))

(defn -main
  [& args]
  (if-not (contains? #{["generate"] ["verify"]} (vec args))
    (fail! "Usage: clojure -M:run generate|verify" 2)
    (try
      (case (first args)
        "generate" (harness/generate!)
        "verify" (compiler/verify-clean-build!))
      (catch ExceptionInfo error
        (let [{:keys [output]} (ex-data error)]
          (fail! (str "Vibeformer generation failed: " (.getMessage error)
                      (when-not (str/blank? output)
                        (str "\n" (str/trim output))))
                 1)))
      (catch Throwable error
        (fail! (str "Vibeformer generation failed unexpectedly: " (.getMessage error)) 1)))))
