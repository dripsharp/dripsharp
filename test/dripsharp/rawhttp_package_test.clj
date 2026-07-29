(ns dripsharp.rawhttp-package-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.rawhttp-package :as rawhttp]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util Base64]))

(defn- encode [value]
  (.encodeToString (Base64/getEncoder) (.getBytes ^String value "UTF-8")))

(defn- observations [rows]
  (str "DRIPSHARP_RAWHTTP_OBSERVATIONS_V1\n"
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

(deftest compiled-body-keys-reconcile-csharp-escaped-internal-namespaces
  (let [body-key (ns-resolve 'dripsharp.rawhttp-package 'body-key)]
    (is (= ["RawHttp.Core.@Internal.Bool" "getAndSet" 1]
           (body-key {:owner "RawHttp.Core.Internal.Bool"
                      :member "getAndSet"
                      :parameter-count "1"})))
    (is (= ["RawHttp.Core.@Internal.Parent.Child" ".ctor" 0]
           (body-key {:owner "RawHttp.Core.Internal.Parent$Child"
                      :member ".ctor"
                      :parameter-count "0"})))))

(deftest non-pkl-boundary-is-an-artifact-contract-not-a-loaded-namespace-check
  (let [root (Files/createTempDirectory "rawhttp-boundary"
                                        (make-array FileAttribute 0))
        source-root (paths/resolve-path root "generated" "src")
        verify-boundary
        (ns-resolve 'dripsharp.rawhttp-package 'verify-non-pkl-boundary!)
        package-proof
        {:packages [{:identity {:id "RawHttp.Core"}}]
         :verification
         {:generation
          {:destination
           {:project {:assembly-name "RawHttp.Core"
                      :root-namespace "RawHttp.Core"}
            :package {:id "RawHttp.Core"}}
           :generation-profile {:project-root "research/rawhttp"}
           :emission {:project-root (paths/resolve-path root "generated")}}}}]
    (Files/createDirectories source-root (make-array FileAttribute 0))
    (Files/writeString (paths/resolve-path source-root "RawHttp.cs")
                       "namespace RawHttp.Core;"
                       (make-array OpenOption 0))
    (create-ns 'dripsharp.pkl.loaded-for-boundary-test)
    (try
      (is (= {:pkl-identities 0
              :pkl-generated-files 0
              :source-project "research/rawhttp"}
             (verify-boundary package-proof)))
      (finally
        (remove-ns 'dripsharp.pkl.loaded-for-boundary-test)))))
