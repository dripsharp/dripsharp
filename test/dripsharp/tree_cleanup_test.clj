(ns dripsharp.tree-cleanup-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.tree-cleanup :as tree-cleanup])
  (:import [java.nio.file DirectoryNotEmptyException Files LinkOption
            OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:private no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- temp-directory
  [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn- write!
  [^Path root relative content]
  (let [file (.resolve root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(deftest late-entry-failure-is-observed-and-retried-with-its-actual-path
  (let [root (temp-directory "dripsharp-tree-cleanup-late-entry-")
        child (.resolve root "child")
        late (.resolve child "late.txt")
        retries (atom [])]
    (Files/createDirectories child (make-array FileAttribute 0))
    (tree-cleanup/delete-tree!
     root
     {:retry-delay-ms 0
      :after-snapshot-fn
      (fn [{:keys [pass]}]
        (when (= 1 pass)
          (Files/writeString late "late\n" (make-array OpenOption 0))))
      :on-retry-fn #(swap! retries conj %)})
    (let [failure
          (some #(when (= (str child)
                          (.getFile ^DirectoryNotEmptyException (:error %)))
                   (:error %))
                @retries)]
      (is (instance? DirectoryNotEmptyException failure))
      (is (= "java.nio.file.DirectoryNotEmptyException"
             (.getName (class failure))))
      (is (= (str child)
             (.getFile ^DirectoryNotEmptyException failure))))
    (is (not (Files/exists root no-follow)))))

(deftest cleanup-retries-are-bounded-and-preserve-actual-final-failure
  (let [root (temp-directory "dripsharp-tree-cleanup-bounded-")
        child (.resolve root "child")
        error
        (try
          (Files/createDirectories child (make-array FileAttribute 0))
          (tree-cleanup/delete-tree!
           root
           {:max-passes 3
            :retry-delay-ms 0
            :after-snapshot-fn
            (fn [{:keys [pass]}]
              (write! child (str "late-" pass ".txt") "late\n"))})
          nil
          (catch clojure.lang.ExceptionInfo failure
            failure))]
    (try
      (is (= :cleanup-retries-exhausted (:reason (ex-data error))))
      (is (= 3 (:passes (ex-data error))))
      (is (= "java.nio.file.DirectoryNotEmptyException"
             (:exception-class (ex-data error))))
      (is (= (str child) (:exception-path (ex-data error))))
      (is (instance? DirectoryNotEmptyException (.getCause error)))
      (finally
        (tree-cleanup/delete-tree! root {:retry-delay-ms 0})))))

(deftest cleanup-never-follows-symbolic-links
  (let [root (temp-directory "dripsharp-tree-cleanup-root-")
        outside (temp-directory "dripsharp-tree-cleanup-outside-")
        sentinel (write! outside "sentinel.txt" "preserve\n")
        link (.resolve root "outside-link")]
    (try
      (Files/createSymbolicLink link outside (make-array FileAttribute 0))
      (tree-cleanup/delete-tree! root {:retry-delay-ms 0})
      (is (Files/exists sentinel no-follow))
      (is (= "preserve\n" (Files/readString sentinel)))
      (finally
        (tree-cleanup/delete-tree! root {:retry-delay-ms 0})
        (tree-cleanup/delete-tree! outside {:retry-delay-ms 0})))))
