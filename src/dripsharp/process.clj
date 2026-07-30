(ns dripsharp.process
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str])
  (:import [java.io File IOException]
           [java.lang Process ProcessBuilder ProcessHandle]
           [java.util.concurrent CompletableFuture TimeUnit]
           [java.util.function Supplier]))

(defn- destroy-process-tree!
  [^Process process]
  (with-open [descendants (.descendants (.toHandle process))]
    (doseq [^ProcessHandle descendant
            (reverse (vec (iterator-seq (.iterator descendants))))]
      (.destroyForcibly descendant)))
  (.destroyForcibly process))

(defn without-java-tool-options-banner
  "Removes the JVM launcher's standard JAVA_TOOL_OPTIONS diagnostic from
  merged process output while preserving the child program's exact stream."
  [output]
  (str/replace output
               #"(?m)^Picked up JAVA_TOOL_OPTIONS: [^\r\n]*(?:\r?\n|$)"
               ""))

(defn run!
  "Runs a command and returns its merged output. Throws on start, timeout, or
  exit failure. `timeout-ms` is optional and must be a positive integer."
  [{:keys [command directory timeout-ms environment unset-environment]}]
  (when-not (seq command)
    (throw (ex-info "Cannot run an empty command" {:kind :empty-command})))
  (when (and (some? timeout-ms)
             (not (and (integer? timeout-ms) (pos? timeout-ms))))
    (throw (ex-info "Command timeout must be a positive integer"
                    {:kind :invalid-command-timeout :timeout-ms timeout-ms})))
  (let [command (mapv str command)
        builder (doto (ProcessBuilder. command)
                  (.directory (File. (str directory)))
                  (.redirectErrorStream true))]
    (when environment
      (when-not (and (map? environment)
                     (every? #(and (string? %) (string? %))
                             (mapcat identity environment)))
        (throw (ex-info "Process environment overrides must be string pairs"
                        {:kind :invalid-command-environment
                         :environment environment})))
      (doseq [[name value] environment]
        (.put (.environment builder) name value)))
    (when unset-environment
      (when-not (and (coll? unset-environment)
                     (every? string? unset-environment))
        (throw (ex-info "Process environment removals must be strings"
                        {:kind :invalid-command-environment-removals
                         :unset-environment unset-environment})))
      (doseq [name unset-environment]
        (.remove (.environment builder) name)))
    (try
      (let [process (.start builder)
            output-future
            (CompletableFuture/supplyAsync
             (reify Supplier
               (get [_] (slurp (.getInputStream process)))))
            finished? (if timeout-ms
                        (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
                        (do (.waitFor process) true))
            _ (when-not finished?
                (destroy-process-tree! process)
                (.waitFor process))
            output (.get output-future)
            _ (when-not finished?
                (throw (ex-info
                        (str "Command timed out after " timeout-ms " ms: "
                             (str/join " " command))
                        {:kind :command-timeout :command command
                         :timeout-ms timeout-ms :output output})))
            exit (.exitValue process)
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
