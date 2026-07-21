(ns vibeformer.rawhttp-package-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.rawhttp-package :as rawhttp]
            [vibeformer.paths :as paths])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util Base64]))

(defn- encode [value]
  (.encodeToString (Base64/getEncoder) (.getBytes ^String value "UTF-8")))

(defn- observations [rows]
  (str "VIBEFORMER_RAWHTTP_OBSERVATIONS_V1\n"
       (str/join "\n" (map (fn [[id status payload]]
                             (str id "\t" status "\t" (encode payload)))
                           rows))
       "\n"))

(defn- caught [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo error error)))

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (Files/readAllBytes file))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(deftest rawhttp-comparator-requires-complete-exact-ordered-unique-rows
  (let [expected (observations [["a" "SUCCESS" "one"]
                                ["b" "FAILURE" "two"]])]
    (is (= {:matched 2} (rawhttp/compare-observations! expected expected)))
    (doseq [[label actual reason]
            [[:missing (observations [["a" "SUCCESS" "one"]])
              :observation-mismatch]
             [:result (observations [["a" "SUCCESS" "changed"]
                                     ["b" "FAILURE" "two"]])
              :observation-mismatch]
             [:duplicate (observations [["a" "SUCCESS" "one"]
                                        ["a" "SUCCESS" "one"]])
              :duplicate-observations]
             [:unstable (observations [["b" "FAILURE" "two"]
                                       ["a" "SUCCESS" "one"]])
              :unstable-observation-order]]]
      (testing (name label)
        (let [error (caught #(rawhttp/compare-observations! expected actual))]
          (is (= :rawhttp-package-equivalence-failed (:kind (ex-data error))))
          (is (= reason (:reason (ex-data error)))))))))

(deftest rawhttp-provenance-requires-the-exact-loaded-assembly
  (let [root (Files/createTempDirectory "rawhttp-provenance"
                                        (make-array FileAttribute 0))
        assembly (paths/resolve-path root "RawHttp.Core.dll")]
    (Files/writeString assembly "exact assembly" (make-array OpenOption 0))
    (let [expected {:name "RawHttp.Core" :version "2.5.2.0"
                    :sha256 (sha256 assembly)
                    :location (str (.toRealPath assembly
                                                (make-array java.nio.file.LinkOption 0)))}]
      (is (= expected (rawhttp/validate-provenance! expected expected)))
      (let [error (caught #(rawhttp/validate-provenance!
                            (assoc expected :sha256 (apply str (repeat 64 "0")))
                            expected))]
        (is (= :assembly-provenance-mismatch (:reason (ex-data error))))))))
