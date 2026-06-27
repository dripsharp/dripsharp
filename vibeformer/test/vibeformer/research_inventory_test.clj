(ns vibeformer.research-inventory-test
  (:require [clojure.test :refer [deftest is]]
            [vibeformer.research-inventory :as research-inventory]
            [vibeformer.transform.rules :as rules]))

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

(deftest registered-rules-follow-discovered-source-languages
  (let [rule-ids (fn [source-files]
                   (set (map :rule/id (#'research-inventory/registered-rules source-files))))]
    (is (= (set (map :rule/id rules/initial-java-rules))
           (rule-ids [{:file/lang :lang/java}])))
    (is (= (set (map :rule/id rules/initial-kotlin-rules))
           (rule-ids [{:file/lang :lang/kotlin}])))
    (is (= (into (set (map :rule/id rules/initial-java-rules))
                 (map :rule/id rules/initial-kotlin-rules))
           (rule-ids [{:file/lang :lang/java}
                      {:file/lang :lang/kotlin}])))))

(deftest rule-summary-counts-all-discovered-language-rules
  (is (= (+ (count rules/initial-java-rules)
            (count rules/initial-kotlin-rules))
         (:rules/registered
          (#'research-inventory/rule-summary
           [{:file/lang :lang/java}
            {:file/lang :lang/kotlin}])))))
