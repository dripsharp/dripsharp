(ns dripsharp.dotnet-surface-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.dotnet-surface :as surface]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-dotnet-surface-control"
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
           [:inheritance
            (assoc-in expected [0 :signature]
                      "class Api : Correct.Library.BaseApi")
            :compiled-dotnet-surface-drift]
           [:kind (assoc-in expected [1 :kind] "field")
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

(defn- generated-row [source-file identity kind name parameter-count]
  {:row {:identity identity}
   :generated
   {:destination {:assembly "Correct.Library"
                  :owner "Correct.Library.Api"
                  :kind kind
                  :name name
                  :parameter-count (str parameter-count)
                  :visibility "public"}
    :source {:location {:file source-file :line 1}}}})

(deftest generated-owner-escapes-reconcile-with-reflected-metadata-names
  (let [workspace (paths/workspace-root)
        source-file
        (paths/resolve-path workspace
                            "test/dripsharp/dotnet_surface_test.clj")
        reflected
        [(row {:owner "Correct.Library.Operator.Api"
               :kind "type" :name "Api" :parameter-count "0"
               :signature "class Api" :nullability "type=oblivious"})
         (row {:owner "Correct.Library.operator.Api"
               :kind "type" :name "Api" :parameter-count "0"
               :signature "class Api" :nullability "type=oblivious"})]
        generated
        [(assoc-in
          (generated-row source-file "type:Operator.Api" "type" "Api" 0)
          [:generated :destination :owner]
          "Correct.Library.@Operator.Api")
         (assoc-in
          (generated-row source-file "type:operator.Api" "type" "Api" 0)
          [:generated :destination :owner]
          "Correct.Library.@operator.Api")]
        result
        (surface/verify-generated-rows!
         workspace reflected {:required-rows 2 :rows generated})]
    (is (= {:rows 2 :types 2 :members 0 :contract-members 2}
           (select-keys result [:rows :types :members :contract-members])))))

(deftest spoon-derived-metadata-detects-every-missing-or-misplaced-shape
  (let [workspace (paths/workspace-root)
        source-file
        (paths/resolve-path workspace
                            "test/dripsharp/dotnet_surface_test.clj")
        rows
        [(row {:kind "type" :name "Api" :parameter-count "0"
               :signature "class Api" :nullability "type=oblivious"})
         (row {:kind "constructor" :name ".ctor" :parameter-count "0"
               :signature "Api .ctor()" :nullability "-"})
         (row {})
         (row {:parameter-count "2"
               :signature "System.String Echo(System.String left,System.String right)"
               :nullability
               "return=non-null;param0=non-null;param1=non-null"})]
        metadata
        {:required-rows 4
         :rows [(generated-row source-file "type:Api" "type" "Api" 0)
                (generated-row source-file "constructor:Api()" "constructor" ".ctor" 0)
                (generated-row source-file "method:Echo(String)" "method" "Echo" 1)
                (generated-row source-file "method:Echo(String,String)"
                               "method" "Echo" 2)]}
        success (surface/verify-generated-rows! workspace rows metadata)
        mutations
        [[:missing-type
          (vec (remove #(= "type" (:kind %)) rows))]
         [:missing-member
          (vec (remove #(and (= "method" (:kind %))
                             (= "1" (:parameter-count %)))
                       rows))]
         [:missing-constructor
          (vec (remove #(= "constructor" (:kind %)) rows))]
         [:missing-overload
          (vec (remove #(= "2" (:parameter-count %)) rows))]
         [:visibility
          (assoc-in rows [2 :visibility] "protected")]
         [:kind
          (assoc-in rows [2 :kind] "field")]
         [:project
          (assoc-in rows [2 :assembly] "Wrong.Library")]
         [:package
          (assoc-in rows [2 :owner] "Correct.Library.Other")]]]
    (is (= {:rows 4 :types 1 :members 3 :contract-members 4}
           (select-keys success [:rows :types :members :contract-members])))
    (doseq [[label actual] mutations]
      (testing (name label)
        (let [error
              (caught #(surface/verify-generated-rows!
                        workspace actual metadata))]
          (is (= :compiled-java-declaration-shape-mismatch
                 (:kind (ex-data error)))))))))

(deftest registered-compatibility-source-can-retain-its-vendored-namespace
  (let [workspace (paths/workspace-root)
        sources
        ["vendor/pdfcube/jpx/Color/ColorSpace.cs"
         "targets/pdfcube/runtime/DripSharp.PdfCarton.Fonts.Compat.cs"
         "vendor/pdfcube/jpx/Configuration/EncoderComponents.cs"
         "vendor/pdfcube/jpx/J2kImage.FastPath.cs"
         "vendor/pdfcube/jpx/J2kImage.cs"
         "vendor/pdfcube/jpx/j2k/wavelet/WaveletFilter.cs"]
        rows
        [(row {:owner "CoreJ2K.j2k.wavelet.WaveletFilter"
               :kind "type"
               :name "WaveletFilter"
               :parameter-count "0"
               :signature "interface WaveletFilter"
               :nullability "type=oblivious"})
         (row {:owner "CoreJ2K.j2k.wavelet.WaveletFilter"
               :kind "method"
               :name "GetFilterType"
               :parameter-count "0"
               :signature "System.Int32 GetFilterType()"
               :nullability "return=non-null"})
         (row {:owner "CoreJ2K.J2kImage"
               :kind "type"
               :name "J2kImage"
               :parameter-count "0"
               :signature "class J2kImage"
               :nullability "type=oblivious"})
         (row {:owner "CoreJ2K.Color.ColorSpace$MethodEnum"
               :kind "type"
               :name "MethodEnum"
               :parameter-count "0"
               :signature "enum MethodEnum"
               :nullability "type=oblivious"})
         (row {:owner "DripSharp.PdfCarton.Runtime.Fonts.JavaImageInputStream"
               :kind "type"
               :name "JavaImageInputStream"
               :parameter-count "0"
               :signature "class JavaImageInputStream"
               :nullability "type=oblivious"})]
        result
        (surface/verify-generated-rows!
         workspace rows
         {:required-rows 0
          :rows []
          :compatibility-namespace "DripSharp.PdfCarton.Runtime.Fonts"
          :compatibility-sources sources})]
    (is (= 5 (:rows result)))
    (is (= {"java-compatibility-type" 4
            "java-compatibility-member" 1}
           (:translation-rules result)))))

(deftest registered-compatibility-delegate-has-exact-source-provenance
  (let [workspace (paths/workspace-root)
        root (temp-directory)
        source (paths/resolve-path root "Compatibility.cs")
        _ (Files/writeString
           source
           (str "namespace DripSharp.Runtime;\n"
                "public delegate bool JavaBiPredicate<in TLeft, in TRight>("
                "TLeft left, TRight right);\n")
           (make-array OpenOption 0))
        reflected
        [(row {:assembly "Correct.Library"
               :owner "DripSharp.Runtime.JavaBiPredicate`2"
               :kind "type"
               :name "JavaBiPredicate"
               :parameter-count "0"
               :signature "delegate JavaBiPredicate<TLeft,TRight>"
               :nullability "type=oblivious"})]
        result
        (surface/verify-generated-rows!
         workspace reflected
         {:required-rows 0
          :rows []
          :compatibility-sources [(str source)]})]
    (is (= 1 (:rows result)))
    (is (= {"java-compatibility-type" 1}
           (:translation-rules result)))))

(deftest internal-namespace-policy-rejects-exported-types-and-signatures
  (let [workspace (paths/workspace-root)
        metadata
        {:required-rows 0
         :rows []
         :compatibility-sources []
         :internal-namespace-prefixes
         ["CoreJ2K" "JBig2Decoder.NETStandard"]}]
    (doseq [[label leaked-row]
            [[:exported-owner
              (row {:owner "CoreJ2K.J2kImage"
                    :kind "type"
                    :name "J2kImage"
                    :parameter-count "0"
                    :signature "class J2kImage"
                    :nullability "type=oblivious"})]
             [:exported-signature
              (row {:signature
                    "CoreJ2K.J2kImage Decode(System.Byte[] encoded)"})]
             [:nested-exported-owner
              (row {:owner
                    "JBig2Decoder.NETStandard.Decoders.HuffmanDecoder$Table"
                    :kind "type"
                    :name "Table"
                    :parameter-count "0"
                    :signature "class Table"
                    :nullability "type=oblivious"})]]]
      (testing (name label)
        (let [error
              (caught
               #(surface/verify-generated-rows!
                 workspace [leaked-row] metadata))]
          (is (= :internal-namespace-public-surface
                 (:kind (ex-data error))))
          (is (= 1 (:leak-count (ex-data error)))))))))
