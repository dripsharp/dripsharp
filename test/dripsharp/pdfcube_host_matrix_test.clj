(ns dripsharp.pdfcube-host-matrix-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.host-matrix :as host-matrix])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory
   "pdfcube-host-matrix-test-"
   (make-array FileAttribute 0)))

(defn- write-evidence!
  ([root host]
   (write-evidence!
    root host (host-matrix/expected-observations host)))
  ([root host observations]
   (let [file
         (paths/resolve-path root (host-matrix/evidence-file-name host))
         value
         (apply
          str
          (for [[[subject id] observation] (sort-by key observations)]
            (str subject "\t" id "\t" observation "\n")))]
     (Files/writeString file value (make-array OpenOption 0))
     file)))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch ExceptionInfo error error)))

(deftest exact-macos-matrix-is-required
  (let [root (temp-directory)]
    (doseq [host host-matrix/required-hosts]
      (write-evidence! root host))
    (let [summary (host-matrix/validate-matrix root)]
      (is (:complete summary))
      (is (= 6 (:supported-hosts summary)))
      (is (= 2 (:expected-hosts summary)))
      (is (= 2 (:passed-hosts summary)))
      (is (every? #(= :passed (:status %)) (:hosts summary))))))

(deftest missing-and-unexecuted-entries-are-not-passes
  (let [root (temp-directory)
        executed (first host-matrix/required-hosts)]
    (write-evidence! root executed)
    (let [summary (host-matrix/validate-matrix root)]
      (is (false? (:complete summary)))
      (is (= 1 (:passed-hosts summary)))
      (is (= 1 (count (:missing-hosts summary)))))
    (let [output (temp-directory)
          error
          (caught #(host-matrix/verify! root output))]
      (is (= :pdfcube-family-host-matrix-failed
             (:kind (ex-data error))))
      (is (paths/regular-file?
           (paths/resolve-path output "summary.edn")))
      (is (paths/regular-file?
           (paths/resolve-path output "summary.tsv"))))))

(deftest failures-and-invented-evidence-fail-closed
  (let [root (temp-directory)
        failed (first host-matrix/required-hosts)
        observations
        (-> (host-matrix/expected-observations failed)
            (assoc ["result" "status"] "failed")
            (assoc ["failure" "message"] "native load failed"))]
    (write-evidence! root failed observations)
    (Files/writeString
     (paths/resolve-path root "pdfcube-family-plan9-x64.tsv")
     "schema\tversion\tinvented\n"
     (make-array OpenOption 0))
    (let [summary (host-matrix/validate-matrix root)]
      (is (false? (:complete summary)))
      (is (= [failed] (:failed-hosts summary)))
      (is (= ["pdfcube-family-plan9-x64.tsv"]
             (:unexpected-files summary)))
      (is (= "native load failed"
             (:message (first (:hosts summary))))))))

(deftest nonrequired-supported-host-evidence-does-not-block-completion
  (let [root (temp-directory)
        nonrequired (first host-matrix/supported-hosts)]
    (doseq [host host-matrix/required-hosts]
      (write-evidence! root host))
    (write-evidence! root nonrequired)
    (let [summary (host-matrix/validate-matrix root)]
      (is (:complete summary))
      (is (= [(host-matrix/evidence-file-name nonrequired)]
             (:nonrequired-files summary)))
      (is (empty? (:unexpected-files summary))))))

(deftest successful-evidence-must-match-the-exact-host-contract
  (let [root (temp-directory)
        host (first host-matrix/required-hosts)
        observations
        (-> (host-matrix/expected-observations host)
            (dissoc ["capability" "cryptography"])
            (assoc ["host" "architecture"] "arm64"))]
    (write-evidence! root host observations)
    (let [summary (host-matrix/validate-matrix root)
          result (first (:hosts summary))]
      (is (= :invalid (:status result)))
      (is (= [["capability" "cryptography"]]
             (:missing result)))
      (is (= [{:identity ["host" "architecture"]
               :expected "x64"
               :actual "arm64"}]
             (:different result))))))
