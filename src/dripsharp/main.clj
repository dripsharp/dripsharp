(ns dripsharp.main
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.rebaseline :as rebaseline]
            [dripsharp.target-execution :as target-execution])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private usage
  (str
   "Usage: clojure -M:run "
   "generate|verify|pack|package <target> <profile>"
   "|differential <target> [validation-id]"
   "|rebaseline <pkl|pdfcube> [--approve <token>]"))

(defn- fail!
  [message exit]
  (binding [*out* *err*]
    (println message))
  (System/exit exit))

(defn dispatch!
  "Dispatches the public CLI without a registry of products or validations.
  Target-directory metadata owns all target-specific selections."
  [args]
  (let [[command target selector & extra] args]
    (cond
      (and (contains? #{"generate" "verify" "pack" "package"} command)
           target selector (empty? extra))
      (target-execution/run! (keyword command)
                             {:target target :profile selector})

      (and (= "differential" command)
           target
           (empty? extra))
      (target-execution/differential!
       (cond-> {:target target}
         selector (assoc :validation selector)))

      (and (= "rebaseline" command)
           (or (= 2 (count args))
               (and (= 4 (count args))
                    (= "--approve" selector))))
      (rebaseline/run! (paths/workspace-root) (rest args))

      :else
      (throw (ex-info usage {:kind :invalid-command-line
                             :arguments (vec args)})))))

(defn -main
  [& args]
  (try
    (dispatch! args)
    (catch ExceptionInfo error
      (let [{:keys [output kind]} (ex-data error)]
        (fail! (str (if (= :invalid-command-line kind)
                      ""
                      "DripSharp command failed: ")
                    (.getMessage error)
                    (when-not (str/blank? output)
                      (str "\n" (str/trim output))))
               (if (= :invalid-command-line kind) 2 1))))
    (catch Throwable error
      (fail! (str "DripSharp command failed unexpectedly: "
                  (.getMessage error))
             1))))
