(ns dripsharp.generation-provenance-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.generation-provenance :as provenance]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn- write-file! [root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- source-location [^Path file line]
  {:file (str file) :line line :column 3})

(defn- generated-id [kind ^Path file line frontend-class]
  (str kind ":" file ":" line ":3:" frontend-class))

(defn- checkout-artifacts [root]
  (let [source-root (paths/resolve-path root "research/jsqlparser/src/main/java")
        first-file (write-file! root
                                "research/jsqlparser/src/main/java/example/First.java"
                                "package example; public class First {}\n")
        second-file (write-file! root
                                 "research/jsqlparser/src/main/java/example/Second.java"
                                 "package example; public class Second {}\n")
        contract-file (write-file! root
                                   "targets/sqltrellis/public-surface.tsv"
                                   "fixture\n")
        first-location (source-location first-file 1)
        second-location (source-location second-file 1)]
    {:root root
     :source-roots [source-root]
     :annotation-decisions
     {:schema-version 1
      :decisions
      [{:resolved-key "annotation:example.First"
        :source {:location first-location :rule :fixture/first}}
       {:resolved-key "annotation:example.Second"
        :source {:location second-location :rule :fixture/second}}]}
     :generation-manifest
     {:schema-version 1
      :sources [{:source "research/jsqlparser/src/main/java/example/First.java"
                 :strategy :generated-csharp}
                {:source "research/jsqlparser/src/main/java/example/Second.java"
                 :strategy :generated-csharp}]
      :artifacts
      [{:file "src/Example/First.cs" :source first-location}
       {:file "src/Example/Second.cs" :source second-location}]
      :summary {:compilation-units 2 :generated-files 2 :declarations 2}}
     :public-metadata
     {:schema-version 1
      :compiled-contract-file (str contract-file)
      :required-rows 2
      :rows
      [{:row {:identity "type:example.First"}
        :generated
        {:id (generated-id "type" first-file 1 "CtClassImpl")
         :source {:location first-location}}}
       {:row {:identity "type:example.Second"}
        :generated
        {:id (generated-id "type" second-file 1 "CtClassImpl")
         :source {:location second-location}}}]}}))

(defn- normalize-artifacts
  [{:keys [root source-roots annotation-decisions generation-manifest
           public-metadata]}]
  {:annotation-decisions
   (provenance/portable-annotation-decisions!
    root source-roots annotation-decisions)
   :generation-manifest
   (provenance/portable-generation-manifest!
    root source-roots generation-manifest)
   :public-metadata
   (provenance/portable-public-metadata!
    root source-roots public-metadata)})

(defn- stable-digest [value]
  (util/sha256-text (pr-str value)))

(defn- failure [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest source-accounting-artifacts-are-checkout-path-independent
  (let [left (checkout-artifacts (temp-directory "sqltrellis-provenance-left-"))
        right (checkout-artifacts (temp-directory "sqltrellis-provenance-right-"))
        artifact-keys
        [:annotation-decisions :generation-manifest :public-metadata]
        normalized-left (normalize-artifacts left)
        normalized-right (normalize-artifacts right)
        manifest (:generation-manifest normalized-left)
        decisions (:annotation-decisions normalized-left)
        metadata (:public-metadata normalized-left)]
    (testing "all three persisted artifacts reproduce the absolute-checkout drift"
      (doseq [artifact artifact-keys]
        (is (not= (pr-str (get left artifact))
                  (pr-str (get right artifact)))
            (name artifact))))
    (testing "portable serialization preserves exact semantic contents and ordering"
      (is (= normalized-left normalized-right))
      (is (= ["annotation:example.First" "annotation:example.Second"]
             (mapv :resolved-key (:decisions decisions))))
      (is (= ["src/Example/First.cs" "src/Example/Second.cs"]
             (mapv :file (:artifacts manifest))))
      (is (= ["type:example.First" "type:example.Second"]
             (mapv #(get-in % [:row :identity]) (:rows metadata))))
      (is (= ["research/jsqlparser/src/main/java/example/First.java"
              "research/jsqlparser/src/main/java/example/Second.java"]
             (mapv #(get-in % [:source :file]) (:artifacts manifest))))
      (is (= "targets/sqltrellis/public-surface.tsv"
             (:compiled-contract-file metadata))))
    (testing "artifact inventories and accounting digests are root-independent"
      (is (= (mapv #(select-keys % [:file :source])
                   (get-in normalized-left [:generation-manifest :artifacts]))
             (mapv #(select-keys % [:file :source])
                   (get-in normalized-right [:generation-manifest :artifacts]))))
      (is (= (stable-digest normalized-left)
             (stable-digest normalized-right)))
      (is (= (stable-digest
              (select-keys manifest [:sources :artifacts :summary]))
             (stable-digest
              (select-keys (:generation-manifest normalized-right)
                           [:sources :artifacts :summary])))))
    (testing "no serialized artifact retains either checkout root"
      (doseq [artifact artifact-keys]
        (let [text (pr-str (get normalized-left artifact))]
          (is (not (str/includes? text (str (:root left)))) artifact)
          (is (not (str/includes? text (str (:root right)))) artifact))))))

(deftest source-accounting-provenance-fails-closed-outside-authorized-roots
  (let [workspace (temp-directory "sqltrellis-provenance-workspace-")
        source-root (paths/resolve-path workspace "research/jsqlparser/src/main/java")
        _ (Files/createDirectories source-root (make-array FileAttribute 0))
        outside (write-file! (temp-directory "sqltrellis-provenance-outside-")
                             "Escaped.java"
                             "public class Escaped {}\n")
        location (source-location outside 1)
        error
        (failure
         #(provenance/portable-annotation-decisions!
           workspace [source-root]
           {:decisions [{:source {:location location}}]}))]
    (is (= :invalid-generation-provenance (:kind (ex-data error))))
    (is (= :source-file-outside-authorized-roots
           (:reason (ex-data error))))))
