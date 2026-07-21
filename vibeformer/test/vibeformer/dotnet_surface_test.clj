(ns vibeformer.dotnet-surface-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.dotnet-surface :as surface]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-dotnet-surface-control"
                             (make-array FileAttribute 0)))

(defn- caught [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo error error)))

(defn- row [overrides]
  (merge {:assembly "Correct.Library"
          :owner "Correct.Library.Api"
          :kind "method"
          :name "Echo"
          :parameter-count "1"
          :visibility "public"
          :metadata-flags "0x86"
          :signature "System.String Echo(System.String value)"
          :generic-constraints "-"
          :nullability "return=non-null;param0=non-null"}
         overrides))

(def ^:private expected
  [(row {:kind "type" :name "Api" :parameter-count "0"
         :signature "class Api" :nullability "type=oblivious"})
   (row {})])

(defn- compile-library! [root assembly namespace type return-type]
  (let [project (paths/resolve-path root (str assembly ".csproj"))
        source (paths/resolve-path root "Api.cs")]
    (Files/writeString
     project
     (str "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
          "<TargetFramework>net8.0</TargetFramework>"
          "<AssemblyName>" assembly "</AssemblyName>"
          "<Nullable>enable</Nullable><ImplicitUsings>enable</ImplicitUsings>"
          "</PropertyGroup></Project>\n")
     (make-array OpenOption 0))
    (Files/writeString
     source
     (str "namespace " namespace "; public class " type
          " { public " return-type " Echo(" return-type
          " value) => value; }\n")
     (make-array OpenOption 0))
    (process/run! {:command ["dotnet" "build" project "--configuration" "Release"
                             "--nologo" "--verbosity:quiet" "-warnaserror"]
                   :directory root})
    (paths/resolve-path root "bin" "Release" "net8.0" (str assembly ".dll"))))

(deftest exact-surface-comparator-rejects-every-public-metadata-drift-family
  (is (= {:matched 2} (surface/compare-surface expected expected)))
  (doseq [[label actual expected-kind]
          [[:extra-type
            (conj expected (row {:owner "Correct.Library.Extra" :kind "type"
                                 :name "Extra" :parameter-count "0"
                                 :signature "class Extra" :nullability "type=oblivious"}))
            :compiled-dotnet-surface-drift]
           [:extra-member
            (conj expected (row {:name "Extra" :parameter-count "0"
                                 :signature "System.Void Extra()"
                                 :nullability "return=non-null"}))
            :compiled-dotnet-surface-drift]
           [:missing-member (pop expected) :compiled-dotnet-surface-drift]
           [:duplicate-member (conj expected (second expected))
            :duplicate-compiled-reflection-rows]
           [:signature (assoc-in expected [1 :signature] "System.Int32 Echo(System.Int32 value)")
            :compiled-dotnet-surface-drift]
           [:visibility (assoc-in expected [1 :visibility] "protected")
            :compiled-dotnet-surface-drift]
           [:metadata-flags (assoc-in expected [1 :metadata-flags] "0x16")
            :compiled-dotnet-surface-drift]
           [:generic (assoc-in expected [1 :generic-constraints] "T:class")
            :compiled-dotnet-surface-drift]
           [:nullability (assoc-in expected [1 :nullability]
                                   "return=nullable;param0=non-null")
            :compiled-dotnet-surface-drift]
           [:ownership (assoc-in expected [1 :owner] "Correct.Library.Other")
            :compiled-dotnet-surface-drift]]]
    (testing (name label)
      (is (= expected-kind
             (get-in (surface/compare-surface expected actual)
                     [:mismatch :kind]))))))

(deftest wrong-real-assembly-cannot-pass-with-a-forged-required-row-count
  (let [workspace (paths/workspace-root)
        correct-root (temp-directory)
        wrong-root (temp-directory)
        correct (compile-library! correct-root "Correct.Library" "Correct.Library"
                                  "Api" "string")
        wrong (compile-library! wrong-root "Wrong.Library" "Wrong.Library"
                                "Other" "int")
        correct-rows (surface/reflect! workspace correct)
        wrong-rows (surface/reflect! workspace wrong)
        forged {:required-rows 510 :rows (vec (repeat 510 {}))}
        error (caught #(surface/verify-rows!
                        workspace {:rows correct-rows} wrong-rows forged))]
    (is (= 3 (count correct-rows)))
    (is (= 3 (count wrong-rows)))
    (is (= :compiled-dotnet-surface-mismatch (:kind (ex-data error))))))
