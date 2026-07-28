(ns dripsharp.java-library-mappings-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-library-mappings :as library-mappings]
            [dripsharp.java-mapping-registry :as mapping-registry]
            [dripsharp.java-types :as java-types]))

(def ^:private custom-handlers
  {:java-library.mapping/stream-collect
   (fn [{:keys [target]}] {:node target})
   :java-library.mapping/atomic-reference-get
   (fn [{:keys [target]}] {:node target})})

(deftest context-free-type-table-is-a-validated-declarative-registry
  (is (mapping-registry/compiled-registry? java-types/registry))
  (is (= 386 (count java-types/entries)))
  (is (= (count java-types/entries)
         (count (set (map :id java-types/entries)))))
  (is (= ["global::System.Net.Sockets.Socket" :dotnet.type/socket]
         (java-types/mapping "java.net.Socket")))
  (is (= {:strategy :rename
          :destination "global::System.Net.Sockets.Socket"
          :caveats #{}
          :introduced-by :rawhttp
          :evidence #{:test/shared-java-library}}
         (select-keys
          (java-types/mapping-entry "java.net.Socket")
          [:strategy :destination :caveats :introduced-by :evidence]))))

(deftest known-context-free-type-approximations-record-their-caveats
  (let [caveats #(get (java-types/mapping-entry %) :caveats)]
    (testing "deterministic collection iteration and ordering"
      (is (= #{:deterministic-iteration-order-loss
               :usage-dependent-approximation}
             (caveats "java.util.LinkedHashSet")))
      (is (= #{:ordering-difference :usage-dependent-approximation}
             (caveats "java.util.EnumMap"))))
    (testing "collapsed Java exception discrimination"
      (doseq [type ["java.lang.Throwable"
                    "java.lang.Exception"
                    "java.lang.RuntimeException"
                    "java.lang.Error"]]
        (is (contains? (caveats type) :exception-hierarchy-collapse))))
    (testing "stream, writer, and calendar projections"
      (is (contains? (caveats "java.io.PrintStream")
                     :stream-writer-contract-difference))
      (is (contains? (caveats "java.io.InputStream")
                     :stream-contract-difference))
      (is (contains? (caveats "java.io.Writer")
                     :writer-contract-difference))
      (is (contains? (caveats "java.util.Calendar")
                     :calendar-model-difference)))
    (is (every? seq
                (map :evidence
                     (filter (comp seq :caveats) java-types/entries))))))

(deftest shared-member-registry-uses-common-strategies-and-explicit-handlers
  (let [registry (library-mappings/compile-registry custom-handlers)
        list-size
        (mapping-registry/registry-entry
         registry
         "executable:java.util.List#size()")
        stream-collect
        (mapping-registry/registry-entry
         registry
         "executable:java.util.stream.Stream#collect(java.util.stream.Collector)")
        atomic-get
        (mapping-registry/registry-entry
         registry
         "executable:java.util.concurrent.atomic.AtomicReference#get()")]
    (is (mapping-registry/compiled-registry? registry))
    (is (= :compat-call (:strategy list-size)))
    (is (= :custom-handler (:strategy stream-collect)))
    (is (= :java-library.mapping/stream-collect (:handler stream-collect)))
    (is (= :custom-handler (:strategy atomic-get)))
    (is (= :java-library.mapping/atomic-reference-get (:handler atomic-get)))
    (is (= "global::DripSharp.Runtime.JavaCompat.CollectionCount(values)"
           (-> (mapping-registry/interpret
                registry
                "executable:java.util.List#size()"
                {:target (csharp/raw "values")})
               :node
               csharp/render
               :text)))
    (is (= "global::DripSharp.Runtime.JavaCompat.CompareInt(left, right)"
           (-> (mapping-registry/interpret
                registry
                "executable:java.lang.Integer#compare(int,int)"
                {:target (csharp/raw "int")
                 :arguments [(csharp/raw "left") (csharp/raw "right")]})
               :node
               csharp/render
               :text)))))

(deftest every-shared-member-entry-carries-reportable-metadata
  (is (= (count library-mappings/entries)
         (count (set (map :id library-mappings/entries)))))
  (is (= (count library-mappings/entries)
         (count (set (map :key library-mappings/entries)))))
  (is (every? #(= :rawhttp (:introduced-by %))
              library-mappings/entries))
  (is (every? set? (map :caveats library-mappings/entries)))
  (is (every? seq (map :evidence library-mappings/entries))))
