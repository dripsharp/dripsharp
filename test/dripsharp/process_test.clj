(ns dripsharp.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.process :as process]))

(deftest command-failure-is-explicit
  (testing "nonzero commands preserve their exit and merged output"
    (let [error (try
                  (process/run! {:command ["sh" "-c" "printf harness-failure >&2; exit 17"]
                                 :directory "."})
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (some? error))
      (is (= :command-failed (:kind (ex-data error))))
      (is (= 17 (:exit (ex-data error))))
      (is (= "harness-failure" (:output (ex-data error)))))))

(deftest command-timeout-is-explicit-and-terminates-the-process
  (let [error (try
                (process/run! {:command ["sh" "-c" "printf started; while :; do :; done"]
                               :directory "."
                               :timeout-ms 50})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (some? error))
    (is (= :command-timeout (:kind (ex-data error))))
    (is (= 50 (:timeout-ms (ex-data error))))
    (is (= "started" (:output (ex-data error))))))
