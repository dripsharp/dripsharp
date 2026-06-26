(ns vibeformer.research-inventory-test
  (:require [clojure.test :refer [deftest is]]
            [vibeformer.research-inventory :as research-inventory]))

(deftest coverage-summary-ranks-namespaced-failures
  (let [summary (#'research-inventory/coverage-summary
                 {:ok? false
                  :failures
                  [{:coverage/reason :coverage.reason/missing-rule
                    :coverage/input :coverage.input/node
                    :source/lang :lang/java
                    :source/kind :java.node/native-method}
                   {:coverage/reason :coverage.reason/missing-rule
                    :coverage/input :coverage.input/node
                    :source/lang :lang/java
                    :source/kind :java.node/native-method}
                   {:coverage/reason :coverage.reason/unimplemented-rule
                    :coverage/input :coverage.input/feature
                    :source/lang :lang/java
                    :source/kind :java.feature/reflection
                    :source/status :feature.status/unsupported}]})]
    (is (= {:ok? false
            :failure-count 3
            :failure-rankings
            [{:reason :coverage.reason/missing-rule
              :input :coverage.input/node
              :lang :lang/java
              :kind :java.node/native-method
              :count 2}
             {:reason :coverage.reason/unimplemented-rule
              :input :coverage.input/feature
              :lang :lang/java
              :kind :java.feature/reflection
              :status :feature.status/unsupported
              :count 1}]}
           summary))))
