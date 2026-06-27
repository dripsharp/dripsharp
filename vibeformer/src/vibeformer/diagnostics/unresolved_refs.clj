(ns vibeformer.diagnostics.unresolved-refs)

(def default-warn-over 0)

(defn- unresolved-refs [inventory-report]
  (or (get-in inventory-report [:inventory :unresolved-ref-detail-rankings])
      []))

(defn- unresolved-count [rankings]
  (reduce + 0 (map #(long (or (:count %) 0)) rankings)))

(defn- threshold-exceeded? [total threshold]
  (and (some? threshold)
       (> total threshold)))

(defn report
  "Build an unresolved-reference readiness report from a research inventory report.

  Thresholds are counts that must be exceeded to trigger:
  - :unresolved/fail-over marks the report failed when total unresolved refs exceed it.
  - :unresolved/warn-over marks the report warning when total unresolved refs exceed it.

  If neither threshold is supplied, unresolved refs warn above zero but do not fail."
  ([inventory-report]
   (report inventory-report {}))
  ([inventory-report opts]
   (let [rankings (vec (unresolved-refs inventory-report))
         total (unresolved-count rankings)
         fail-over (:unresolved/fail-over opts)
         warn-over (if (contains? opts :unresolved/warn-over)
                     (:unresolved/warn-over opts)
                     default-warn-over)
         status (cond
                  (threshold-exceeded? total fail-over) :failed
                  (threshold-exceeded? total warn-over) :warn
                  :else :ok)]
     (cond-> {:report/type :vibeformer.report/unresolved-reference-gate
              :status status
              :ok? (not= :failed status)
              :unresolved/total total
              :unresolved/groups (count rankings)
              :unresolved/rankings rankings
              :unresolved/thresholds {:fail-over fail-over
                                      :warn-over warn-over}}
       (= :failed status)
       (assoc :reason :semantic.unresolved-refs/failed-threshold)

       (= :warn status)
       (assoc :reason :semantic.unresolved-refs/warn-threshold)))))

(defn stage
  "Return a dry-run stage map for the unresolved-reference gate."
  ([inventory-report]
   (stage inventory-report {}))
  ([inventory-report opts]
   (let [{:unresolved/keys [total groups thresholds]
          :keys [status reason] :as gate-report}
         (report inventory-report opts)]
     (cond-> {:stage :semantic/unresolved-refs
              :status status
              :unresolved/total total
              :unresolved/groups groups
              :unresolved/thresholds thresholds}
       reason (assoc :reason reason)
       (:report/file gate-report) (assoc :report/file (:report/file gate-report))))))
