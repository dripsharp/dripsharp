(ns vibeformer.diagnostics.unresolved-refs-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.diagnostics.unresolved-refs :as unresolved-refs]))

(def current-research-baseline-slice
  {:inventory
   {:unresolved-ref-detail-rankings
    [{:lang :lang/kotlin
      :kind :ref.kind/type-use
      :reason :resolve.reason/missing-classpath
      :owner ""
      :name "Action"
      :count 1278
      :file-count 537}
     {:lang :lang/java
      :kind :ref.kind/type-use
      :reason :resolve.reason/missing-classpath
      :owner ""
      :name "var"
      :count 1629
      :file-count 243}]}})

(deftest unresolved-reference-report-warns-by-default
  (let [report (unresolved-refs/report current-research-baseline-slice)]
    (is (= :warn (:status report)))
    (is (:ok? report))
    (is (= 2907 (:unresolved/total report)))
    (is (= 2 (:unresolved/groups report)))
    (is (= {:fail-over nil
            :warn-over 0}
           (:unresolved/thresholds report)))
    (is (= (:unresolved-ref-detail-rankings (:inventory current-research-baseline-slice))
           (:unresolved/rankings report)))))

(deftest unresolved-reference-report-fails-when-fail-threshold-is-exceeded
  (let [report (unresolved-refs/report current-research-baseline-slice
                                       {:unresolved/fail-over 0
                                        :unresolved/warn-over 0})]
    (is (= :failed (:status report)))
    (is (false? (:ok? report)))
    (is (= :semantic.unresolved-refs/failed-threshold (:reason report)))))

(deftest unresolved-reference-report-passes-when-thresholds-allow-current-count
  (testing "above warn and fail thresholds is required, so exact totals pass"
    (is (= :ok
           (:status
            (unresolved-refs/report current-research-baseline-slice
                                    {:unresolved/fail-over 2907
                                     :unresolved/warn-over 2907}))))))

(deftest unresolved-reference-stage-exposes-dry-run-stage-fields
  (is (= {:stage :semantic/unresolved-refs
          :status :failed
          :reason :semantic.unresolved-refs/failed-threshold
          :unresolved/total 2907
          :unresolved/groups 2
          :unresolved/thresholds {:fail-over 0
                                  :warn-over 0}}
         (unresolved-refs/stage current-research-baseline-slice
                                {:unresolved/fail-over 0
                                 :unresolved/warn-over 0}))))
