(ns dripsharp.repository-integrity
  (:require [clojure.string :as str]
            [dripsharp.process :as process]))

(defn- tracked-paths
  [workspace-root run-command!]
  (let [output
        (:output
         (run-command!
          {:command ["git" "ls-files" "-z" "--" "targets"]
           :directory workspace-root}))]
    (->> (str/split output #"\u0000" -1)
         (remove str/blank?)
         vec)))

(defn- transient-build-path?
  [relative]
  (some #{"bin" "obj"}
        (map str/lower-case (str/split relative #"[/\\]"))))

(defn verify-target-inputs!
  "Fails when the superproject index tracks a bin or obj path below targets/.
  The index audit sees force-added ignored content and fails if Git inspection
  itself cannot run."
  ([workspace-root]
   (verify-target-inputs! workspace-root process/run!))
  ([workspace-root run-command!]
   (let [artifacts (->> (tracked-paths workspace-root run-command!)
                        (filter transient-build-path?)
                        sort
                        vec)]
     (when (seq artifacts)
       (throw
        (ex-info
         "Superproject target inputs track transient build outputs"
         {:reason :tracked-target-build-artifacts
          :paths artifacts})))
     {:tracked-target-build-artifacts []})))
