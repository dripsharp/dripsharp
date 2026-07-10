(ns vibeformer.process
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str])
  (:import [java.io File IOException]
           [java.lang ProcessBuilder]))

(defn run!
  "Runs a command and returns its merged output. Throws on start or exit failure."
  [{:keys [command directory]}]
  (when-not (seq command)
    (throw (ex-info "Cannot run an empty command" {:kind :empty-command})))
  (let [command (mapv str command)
        builder (doto (ProcessBuilder. command)
                  (.directory (File. (str directory)))
                  (.redirectErrorStream true))]
    (try
      (let [process (.start builder)
            output (slurp (.getInputStream process))
            exit (.waitFor process)
            result {:command command :exit exit :output output}]
        (when-not (zero? exit)
          (throw (ex-info
                  (str "Command failed with exit " exit ": " (str/join " " command))
                  (assoc result :kind :command-failed))))
        result)
      (catch IOException error
        (throw (ex-info
                (str "Could not start command: " (str/join " " command))
                {:kind :command-start-failed :command command}
                error))))))
