(ns dripsharp.process-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest display-command-redacts-secret-arguments-from-failures
  (let [secret "process-test-secret"
        display-command ["sh" "-c" "exit 17" "<redacted>"]
        error
        (try
          (process/run! {:command ["sh" "-c" "exit 17" secret]
                         :display-command display-command
                         :directory "."})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :command-failed (:kind (ex-data error))))
    (is (= display-command (:command (ex-data error))))
    (is (not (str/includes? (str (ex-message error) (ex-data error))
                            secret)))))

(deftest invalid-display-command-is-explicit
  (let [error
        (try
          (process/run! {:command ["true"]
                         :display-command ["true" "extra"]
                         :directory "."})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-display-command (:kind (ex-data error))))))

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

(deftest command-environment-can-be-removed-explicitly
  (let [name "DRIPSHARP_PROCESS_TEST_REMOVED"
        result
        (process/run!
         {:command
          ["sh" "-c"
           (str "if printenv " name " >/dev/null; "
                "then printf present; else printf absent; fi")]
          :directory "."
          :environment {name "host-controlled"}
          :unset-environment #{name}})]
    (is (= "absent" (:output result)))))

(deftest invalid-command-environment-removals-are-explicit
  (let [error
        (try
          (process/run! {:command ["true"]
                         :directory "."
                         :unset-environment #{"VALID" nil}})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-command-environment-removals
           (:kind (ex-data error))))
    (is (= #{"VALID" nil}
           (:unset-environment (ex-data error))))))

(deftest java-tool-options-launcher-diagnostic-is-not-program-output
  (is (= "# contract\nrow\n"
         (process/without-java-tool-options-banner
          (str "Picked up JAVA_TOOL_OPTIONS: -Xmx28g\n"
               "# contract\nrow\n"))))
  (is (= "ordinary output"
         (process/without-java-tool-options-banner "ordinary output"))))
