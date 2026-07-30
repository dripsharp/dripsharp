(ns dripsharp.pdfcube-identity-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.java-project :as java-project]
            [dripsharp.target-directory :as target-directory]))

(defn- read-edn
  [path]
  (edn/read-string (slurp path)))

(def ^:private target (delay (read-edn "targets/pdfcube/target.edn")))
(def ^:private baseline (delay (read-edn "targets/pdfcube/baseline.edn")))

(def ^:private destinations
  (delay
    (into
     {}
     (for [id [:io :fontbox :xmpbox :pdfbox :preflight]]
       [id (read-edn (str "targets/pdfcube/destinations/"
                          (name id) ".edn"))]))))

(def ^:private approved-identities
  {:io "DripSharp.PdfCarton.IO"
   :fontbox "DripSharp.PdfCarton.Fonts"
   :xmpbox "DripSharp.PdfCarton.Xmp"
   :pdfbox "DripSharp.PdfCarton"
   :preflight "DripSharp.PdfCarton.Preflight"})

(def ^:private expected-dependencies
  {:io []
   :fontbox ["DripSharp.PdfCarton.IO"]
   :xmpbox []
   :pdfbox ["DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"]
   :preflight ["DripSharp.PdfCarton" "DripSharp.PdfCarton.Xmp"]})

(def ^:private expected-project-references
  {:io []
   :fontbox
   ["../DripSharp.PdfCarton.IO/DripSharp.PdfCarton.IO.csproj"]
   :xmpbox []
   :pdfbox
   ["../DripSharp.PdfCarton.IO/DripSharp.PdfCarton.IO.csproj"
    "../DripSharp.PdfCarton.Fonts/DripSharp.PdfCarton.Fonts.csproj"]
   :preflight
   ["../DripSharp.PdfCarton/DripSharp.PdfCarton.csproj"
    "../DripSharp.PdfCarton.Xmp/DripSharp.PdfCarton.Xmp.csproj"]})

(def ^:private deferred-package-metadata
  {:authors "Vibeformer"
   :project-url "https://pdfbox.apache.org/"
   :repository-url "https://github.com/apache/pdfbox.git"})

(defn- identity-bearing-files
  []
  (->> (concat
        (file-seq (io/file "targets/pdfcube"))
        (mapcat file-seq
                (filter #(.isDirectory %)
                        (file-seq (io/file "validation"))))
        (file-seq (io/file "src/dripsharp/pdfcube")))
       (filter #(.isFile %))
       (filter #(re-find #"[.](?:clj|cs|csproj|edn|java|props|ps1|tsv|xml)$"
                         (.getName %)))
       distinct))

(deftest approved-pdfcarton-identities-govern-the-pdfbox-target
  (testing "the stable PDFBox source-target key emits the PdfCarton family"
    (is (= :pdfcube (:target @target)))
    (is (= :pdfcarton
           (:product-family @target)
           (:product-family (java-project/product-family))
           (:product-family (java-project/rule-bundle))
           (:product-family (java-project/public-surface-strategy))
           (:product-family (target-directory/read-target :pdfcube))))
    (is (= #{"pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
             "pdfcube-pdfbox" "pdfcube-preflight"}
           (set (map :id (:profiles @target)))))
    (is (every?
         #(= :pdfcarton (:product-family %))
         (for [profile ["io" "fontbox" "xmpbox" "pdfbox" "preflight"]]
           (read-edn (str "targets/pdfcube/profiles/" profile ".edn"))))))

  (testing "assembly, package, project, root namespace, and output identities are exact"
    (doseq [[id identity] approved-identities
            :let [destination (get @destinations id)]]
      (is (= {:assembly-name identity :root-namespace identity}
             (select-keys (:project destination)
                          [:assembly-name :root-namespace])))
      (is (= identity (get-in destination [:package :id])))
      (is (= (str "generated/pdfcarton/src/" identity)
             (get-in destination [:output :project-directory])))
      (is (= (str identity ".csproj")
             (get-in destination [:output :project-file])))
      (is (= (str identity ".PackageConsumer.csproj")
             (get-in destination [:package-consumer :project-file])))))

  (testing "namespace mappings and package boundaries use the approved family"
    (is (= "DripSharp.PdfCarton.IO"
           (get-in @destinations [:io :namespace-prefixes
                                  "org.apache.pdfbox.io"])))
    (is (= "DripSharp.PdfCarton.Fonts"
           (get-in @destinations [:fontbox :namespace-prefixes
                                  "org.apache.fontbox"])))
    (is (= "DripSharp.PdfCarton.Xmp"
           (get-in @destinations [:xmpbox :namespace-prefixes
                                  "org.apache.xmpbox"])))
    (is (= "DripSharp.PdfCarton"
           (get-in @destinations [:pdfbox :namespace-prefixes
                                  "org.apache.pdfbox"])))
    (is (= "DripSharp.PdfCarton.Preflight"
           (get-in @destinations [:preflight :namespace-prefixes
                                  "org.apache.pdfbox.preflight"])))
    (doseq [[id expected] expected-dependencies]
      (is (= expected (get-in @destinations [id :package-dependencies]))))
    (doseq [[id expected] expected-project-references]
      (is (= expected (get-in @destinations [id :project-references]))))
    (is (= #{"DripSharp.PdfCarton" "DripSharp.PdfCarton.Preflight"}
           (get-in @destinations [:io :friend-assemblies])))
    (is (= #{"DripSharp.PdfCarton.Preflight"}
           (get-in @destinations [:pdfbox :friend-assemblies])))
    (is (= "DripSharp.PdfCarton.Runtime.Fonts"
           (get-in @destinations [:fontbox :compatibility-namespace])))
    (is (= "DripSharp.PdfCarton.Runtime.Xmp"
           (get-in @destinations [:xmpbox :compatibility-namespace]))))

  (testing "baselines, consumers, runtime inputs, and validations agree"
    (is (= (set (vals approved-identities))
           (set (keys (:packages @baseline)))))
    (doseq [[id identity] approved-identities]
      (is (= identity (get-in @baseline [:profiles id :package-id]))))
    (is (every?
         #(str/starts-with? (:path %) "runtime/DripSharp.PdfCarton")
         (:runtime-assets @target)))
    (doseq [file (identity-bearing-files)]
      (is (not (str/includes? (slurp file) "PdfCube"))
          (str "stale public PdfCube identity in " file))))

  (testing "Apache source identities, attribution, and non-affiliation remain"
    (is (= {:name "Apache PDFBox"
            :repository "https://github.com/apache/pdfbox.git"}
           (select-keys (:upstream @baseline) [:name :repository])))
    (doseq [[id source-name]
            {:io "Apache PDFBox"
             :fontbox "Apache FontBox"
             :xmpbox "Apache XmpBox"
             :pdfbox "Apache PDFBox"
             :preflight "Apache Preflight"}
            :let [description (get-in @destinations [id :package :description])]]
      (is (str/includes? description source-name))
      (is (str/includes?
           description
           "not affiliated with, endorsed by, or sponsored by the Apache Software Foundation."))))

  (testing "deferred publisher and repository metadata is untouched"
    (doseq [destination (vals @destinations)]
      (is (= deferred-package-metadata
             (select-keys (:package destination)
                          (keys deferred-package-metadata)))))))
