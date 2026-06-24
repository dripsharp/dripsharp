#!/usr/bin/env bb

(ns beads-codex-loop
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.lang ProcessBuilder$Redirect]))

(def work-prompt
  "Orient yourself with beads by running `br ready`, claim the next ready issue with `br update <id> --status=in_progress`, complete it, close it with `br close <id> --reason=\"Completed\"`, run `br sync --flush-only`, then commit all uncommitted changes to git.")

(def commit-prompt
  (str "There are still uncommitted changes after the previous Codex run. "
       "Commit all uncommitted changes to git now. Do not start a new beads task. "
       "If there is nothing to commit, explain why and exit."))

(def no-ready-prompt-prefix
  (str "There are no `br ready` issues, but bead work remains. "
       "Figure out why the queue is stuck and make concrete progress. "
       "Inspect `br list --status=in_progress`, `br list --status=open`, and `br show <id>` for relevant issues. "
       "If an in-progress issue is unfinished, continue and complete it. "
       "If an in-progress issue is already complete, close it. "
       "If open issues are blocked or deferred, inspect their dependencies and resolve, close, or update the appropriate bead state. "
       "Do not stop just because `br ready` is empty. "
       "Run `br sync --flush-only`, then commit all uncommitted changes to git."))

(def usage
  (str "Usage: scripts/beads-codex-loop.bb [--dry-run] [--help]\n\n"
       "Runs `codex exec` repeatedly while `br ready` reports actionable issues, "
       "or while non-closed beads remain with no ready issue.\n\n"
       "Environment:\n"
       "  CODEX_BIN                         Codex executable (default: codex)\n"
       "  CODEX_MODEL                       Optional model passed as `-m`\n"
       "  CODEX_PROFILE                     Optional profile passed as `-p`\n"
       "  CODEX_BYPASS_APPROVALS            Use danger-full-access automation (default: true)\n"
       "  BEADS_CODEX_MAX_ITERATIONS        Optional loop guard (unset/0/off/unlimited = no cap)\n"
       "  BEADS_CODEX_MAX_FAILURES          Consecutive Codex failure guard (default: 3)\n"
       "  BEADS_CODEX_MAX_STALLED_ITERATIONS No-progress guard (default: 2)\n"
       "  BEADS_CODEX_MAX_COMMIT_PROMPTS    Commit retry guard (default: 3)\n"))

(defn env-value
  [name default-value]
  (let [value (System/getenv name)]
    (if (str/blank? value)
      default-value
      value)))

(defn parse-int-env
  [name default-value]
  (let [raw-value (env-value name (str default-value))]
    (try
      (Integer/parseInt raw-value)
      (catch NumberFormatException exception
        (throw (ex-info (str name " must be an integer, got: " raw-value)
                        {:env name :value raw-value}
                        exception))))))

(defn parse-optional-int-env
  [name]
  (let [raw-value (System/getenv name)
        normalized (some-> raw-value str/trim str/lower-case)]
    (cond
      (str/blank? raw-value) nil
      (#{"0" "false" "no" "none" "off" "unlimited"} normalized) nil
      :else
      (try
        (let [parsed (Integer/parseInt raw-value)]
          (when-not (pos? parsed)
            (throw (ex-info (str name " must be positive, 0, off, or unlimited; got: " raw-value)
                            {:env name :value raw-value})))
          parsed)
        (catch NumberFormatException exception
          (throw (ex-info (str name " must be an integer, 0, off, or unlimited; got: " raw-value)
                          {:env name :value raw-value}
                          exception)))))))

(defn display-limit
  [value]
  (or value "unlimited"))

(defn truthy-env?
  [name default-value]
  (let [raw-value (str/lower-case (env-value name default-value))]
    (not (#{"0" "false" "no" "off"} raw-value))))

(defn run-git
  [repo-root & args]
  (let [result (apply shell/sh "git" (concat ["-C" repo-root] args))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "git " (str/join " " args) " failed")
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    (str/trim (:out result))))

(defn run-br
  [repo-root & args]
  (let [result (apply shell/sh (concat ["br" "--no-color"] args [:dir repo-root]))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "br " (str/join " " args) " failed")
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    (str/trim (:out result))))

(defn git-root
  []
  (let [result (shell/sh "git" "rev-parse" "--show-toplevel")]
    (when-not (zero? (:exit result))
      (throw (ex-info "This script must run inside a git repository."
                      {:exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    (str/trim (:out result))))

(defn git-head
  [repo-root]
  (run-git repo-root "rev-parse" "HEAD"))

(defn git-status
  [repo-root]
  (run-git repo-root "status" "--porcelain=v1"))

(defn git-clean?
  [repo-root]
  (str/blank? (git-status repo-root)))

(defn ready-issues
  [repo-root]
  (-> (run-br repo-root "ready" "--format" "json" "--limit" "0")
      (json/parse-string true)
      vec))

(defn issues-by-status
  [repo-root status]
  (-> (run-br repo-root "list" "--status" status "--format" "json" "--limit" "0")
      (json/parse-string true)
      :issues
      vec))

(defn no-ready-state
  [repo-root]
  {:in-progress (issues-by-status repo-root "in_progress")
   :open (issues-by-status repo-root "open")})

(defn remaining-no-ready-work?
  [state]
  (or (seq (:in-progress state))
      (seq (:open state))))

(defn shell-quote
  [value]
  (if (re-find #"\s" value)
    (str "'" (str/replace value #"'" "'\"'\"'") "'")
    value))

(defn codex-command
  [repo-root prompt]
  (let [codex-bin (env-value "CODEX_BIN" "codex")
        model (System/getenv "CODEX_MODEL")
        profile (System/getenv "CODEX_PROFILE")
        automation-flag (if (truthy-env? "CODEX_BYPASS_APPROVALS" "true")
                          "--dangerously-bypass-approvals-and-sandbox"
                          "--full-auto")]
    (cond-> [codex-bin "exec" automation-flag "-C" repo-root]
      (not (str/blank? model)) (into ["-m" model])
      (not (str/blank? profile)) (into ["-p" profile])
      true (conj prompt))))

(defn run-process!
  [repo-root args]
  (println)
  (println "$" (str/join " " (map shell-quote args)))
  (let [process-builder (doto (ProcessBuilder. ^java.util.List args)
                          (.directory (io/file repo-root))
                          (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null")))
                          (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                          (.redirectError ProcessBuilder$Redirect/INHERIT))
        process (.start process-builder)
        exit-code (.waitFor process)]
    (println "exit:" exit-code)
    exit-code))

(defn run-codex!
  [repo-root prompt]
  (run-process! repo-root (codex-command repo-root prompt)))

(defn sync-beads!
  [repo-root]
  (let [exit-code (run-process! repo-root ["br" "sync" "--flush-only"])]
    (when-not (zero? exit-code)
      (throw (ex-info "br sync --flush-only failed"
                      {:exit exit-code})))
    exit-code))

(defn ensure-committed!
  [repo-root max-commit-prompts]
  (loop [attempt 1]
    (if (git-clean? repo-root)
      true
      (if (> attempt max-commit-prompts)
        (throw (ex-info "Codex left uncommitted changes after commit prompts."
                        {:status (git-status repo-root)
                         :max-commit-prompts max-commit-prompts}))
        (do
          (println)
          (println "Git tree is dirty after Codex run; requesting commit"
                   (str "(" attempt "/" max-commit-prompts ")."))
          (println (git-status repo-root))
          (run-codex! repo-root commit-prompt)
          (recur (inc attempt)))))))

(defn summarize-ready-issues
  [issues]
  (if (seq issues)
    (let [labels (map #(str (:id %) " - " (:title %)) issues)]
      (str (count issues) " ready issue(s): " (str/join ", " labels)))
    "0 ready issues"))

(defn summarize-issues
  [issues]
  (if (seq issues)
    (str (count issues) " issue(s): "
         (str/join ", " (map #(str (:id %) " - " (:title %)) issues)))
    "0 issues"))

(defn print-no-ready-state!
  [state]
  (println "No ready beads issues remain.")
  (when (seq (:in-progress state))
    (println "In-progress beads remain:" (summarize-issues (:in-progress state))))
  (when (seq (:open state))
    (println "Open beads remain, but are blocked or deferred:"
             (summarize-issues (:open state)))))

(defn no-ready-prompt
  [state]
  (str no-ready-prompt-prefix
       "\n\nCurrent in-progress beads: " (summarize-issues (:in-progress state))
       "\nCurrent open blocked/deferred beads: " (summarize-issues (:open state))))

(defn run-loop!
  [options]
  (let [repo-root (git-root)
        max-iterations (parse-optional-int-env "BEADS_CODEX_MAX_ITERATIONS")
        max-codex-failures (parse-int-env "BEADS_CODEX_MAX_FAILURES" 3)
        max-stalled-iterations (parse-int-env "BEADS_CODEX_MAX_STALLED_ITERATIONS" 2)
        max-commit-prompts (parse-int-env "BEADS_CODEX_MAX_COMMIT_PROMPTS" 3)]
    (println "repo:" repo-root)
    (println "limits:"
             {:max-iterations (display-limit max-iterations)
              :max-codex-failures max-codex-failures
              :max-stalled-iterations max-stalled-iterations
              :max-commit-prompts max-commit-prompts})
    (when (:dry-run? options)
      (let [dry-run-issues (ready-issues repo-root)]
        (println "dry-run:" (summarize-ready-issues dry-run-issues))
        (when (empty? dry-run-issues)
          (let [state (no-ready-state repo-root)]
            (print-no-ready-state! state)
            (when (remaining-no-ready-work? state)
              (println "recovery command:"
                       (str/join " " (map shell-quote
                                            (codex-command repo-root
                                                           (no-ready-prompt state)))))))))
      (println "git-clean:" (git-clean? repo-root))
      (println "work command:"
               (str/join " " (map shell-quote (codex-command repo-root work-prompt))))
      (println "commit command:"
               (str/join " " (map shell-quote (codex-command repo-root commit-prompt))))
      (println "push command:"
               (str/join " " (map shell-quote ["git" "push"])))
      (System/exit 0))
    (loop [iteration 1
           consecutive-failures 0
           stalled-iterations 0]
      (let [issues-before (ready-issues repo-root)
            no-ready-before (when (empty? issues-before)
                              (no-ready-state repo-root))
            no-ready-work? (and no-ready-before
                                (remaining-no-ready-work? no-ready-before))]
        (cond
          (and (empty? issues-before) (not no-ready-work?))
          (print-no-ready-state! no-ready-before)

          (and max-iterations (> iteration max-iterations))
          (throw (ex-info "Reached iteration limit with beads work remaining."
                          {:max-iterations max-iterations
                           :ready-issues issues-before
                           :no-ready-state no-ready-before}))

          :else
          (let [head-before (git-head repo-root)
                prompt (if (seq issues-before)
                         work-prompt
                         (no-ready-prompt no-ready-before))
                state-before (if (seq issues-before)
                               issues-before
                               no-ready-before)
                _ (println)
                _ (println "Iteration" iteration "-"
                           (if (seq issues-before)
                             (summarize-ready-issues issues-before)
                             "0 ready issues; asking Codex to resolve stuck beads"))
                _ (when no-ready-work?
                    (print-no-ready-state! no-ready-before))
                exit-code (run-codex! repo-root prompt)
                _ (sync-beads! repo-root)
                _ (ensure-committed! repo-root max-commit-prompts)
                push-exit-code (run-process! repo-root ["git" "push"])
                _ (when-not (zero? push-exit-code)
                    (println "git push failed; continuing loop."))
                issues-after (ready-issues repo-root)
                no-ready-after (when (empty? issues-after)
                                 (no-ready-state repo-root))
                state-after (if (seq issues-after)
                              issues-after
                              no-ready-after)
                head-after (git-head repo-root)
                progressed? (or (not= head-before head-after)
                                (not= state-before state-after))
                next-failures (if (or (zero? exit-code) progressed?)
                                0
                                (inc consecutive-failures))
                next-stalled (if progressed?
                               0
                               (inc stalled-iterations))]
            (println "Post-run:" (summarize-ready-issues issues-after))
            (println "Progress:" (if progressed? "yes" "no"))
            (when (> next-failures max-codex-failures)
              (throw (ex-info "Codex failed too many times in a row."
                              {:max-codex-failures max-codex-failures
                               :last-exit exit-code
                               :ready-issues issues-after})))
            (when (> next-stalled max-stalled-iterations)
              (throw (ex-info "No git or beads progress detected for too many iterations."
                              {:max-stalled-iterations max-stalled-iterations
                               :ready-issues issues-after
                               :no-ready-state no-ready-after})))
            (recur (inc iteration) next-failures next-stalled)))))))

(defn parse-args
  [args]
  (loop [remaining args
         options {}]
    (if-let [arg (first remaining)]
      (case arg
        "--dry-run" (recur (rest remaining) (assoc options :dry-run? true))
        "--help" (assoc options :help? true)
        "-h" (assoc options :help? true)
        (throw (ex-info (str "Unknown argument: " arg) {:arg arg})))
      options)))

(try
  (let [options (parse-args *command-line-args*)]
    (if (:help? options)
      (println usage)
      (run-loop! options)))
  (catch Throwable throwable
    (binding [*out* *err*]
      (println "beads-codex-loop failed:" (ex-message throwable))
      (when-let [data (ex-data throwable)]
        (println data)))
    (System/exit 1)))
