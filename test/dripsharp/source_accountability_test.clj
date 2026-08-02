(ns dripsharp.source-accountability-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.source-accountability :as accountability])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- canonical
  [^Path path]
  (.getCanonicalPath (.toFile path)))

(deftest summaries-account-for-types-packages-and-hard-failures
  (let [root
        (Files/createTempDirectory
         "dripsharp-source-accountability-"
         (make-array FileAttribute 0))
        ordinary (.resolve root "Example.java")
        package-info (.resolve root "package-info.java")
        module-info (.resolve root "module-info.java")
        ordinary-file (canonical ordinary)
        declarations
        [{:kind :type
          :owner nil
          :name "Example"
          :source {:location {:file ordinary-file}}}
         {:kind :method
          :owner "Example"
          :name "value"
          :source {:location {:file ordinary-file}}}]
        diagnostics
        [{:kind :translation-rule-failed
          :source {:location {:file ordinary-file}}}]
        summaries
        (accountability/summarize
         root diagnostics declarations [module-info package-info ordinary])]
    (is (= [{:source "Example.java"
             :strategy :generated-csharp
             :top-level-declarations ["Example"]
             :hard-failures 1}
            {:source "module-info.java"
             :strategy :module-descriptor-assembly-contract
             :top-level-declarations []
             :hard-failures 0}
            {:source "package-info.java"
             :strategy :package-nullability-metadata
             :top-level-declarations []
             :hard-failures 0}]
           summaries))))

(deftest unaccounted-production-sources-fail-closed
  (let [root
        (Files/createTempDirectory
         "dripsharp-unaccounted-source-"
         (make-array FileAttribute 0))
        source (.resolve root "Missing.java")
        error
        (caught #(accountability/summarize root [] [] [source]))]
    (testing "the exact canonical source is preserved"
      (is (= :unaccounted-production-source (:kind (ex-data error))))
      (is (= (canonical source) (:path (ex-data error)))))))
