(ns dripsharp.package-provenance-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.package-provenance :as provenance])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- package [name hash]
  {:resource-proof
   {:assembly-identity {:name name}
    :assembly-artifact {:sha256 hash}}})

(deftest packed-assembly-manifests-are-neutral-and-deterministic
  (let [directory (Files/createTempDirectory
                   "dripsharp-package-provenance"
                   (make-array FileAttribute 0))
        output (.resolve directory "assemblies.tsv")
        a-hash (apply str (repeat 64 "a"))
        b-hash (apply str (repeat 64 "b"))
        proof (provenance/write-packed-assembly-manifest!
               output [(package "B" b-hash) (package "A" a-hash)])]
    (is (= [{:name "A" :sha256 a-hash}
            {:name "B" :sha256 b-hash}]
           (:assemblies proof)
           (provenance/read-packed-assembly-manifest output)))))
