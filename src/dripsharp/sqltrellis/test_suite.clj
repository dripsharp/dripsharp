(ns dripsharp.sqltrellis.test-suite
  "Fail-closed handoff for the declared shipped adapted-upstream suite.")

(defn strategy!
  "Reserves the shared suite slot without emitting incomplete adapted tests."
  [{:keys [phase strategy project]}]
  (throw
   (ex-info
    "SqlTrellis adapted-upstream suite emission is pending a later authorized milestone"
    {:kind :sqltrellis-adapted-upstream-suite-pending
     :phase phase
     :strategy (:id strategy)
     :project (:id project)})))
