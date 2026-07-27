(ns dripsharp.pdfcube-family-build-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.family-build :as family-build])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

(def ^:private order
  ["pdfcube-io" "pdfcube-fontbox" "pdfcube-pdfbox" "pdfcube-xmpbox"
   "pdfcube-preflight"])

(def ^:private products
  {"pdfcube-io"
   {:project-id "org.apache.pdfbox:pdfbox-io:3.0.8"
    :package-id "PdfCube.IO"
    :selector ":pdfbox-io"
    :dependencies []}
   "pdfcube-fontbox"
   {:project-id "org.apache.pdfbox:fontbox:3.0.8"
    :package-id "PdfCube.FontBox"
    :selector ":fontbox"
    :dependencies ["pdfcube-io"]}
   "pdfcube-xmpbox"
   {:project-id "org.apache.pdfbox:xmpbox:3.0.8"
    :package-id "PdfCube.XmpBox"
    :selector ":xmpbox"
    :dependencies []}
   "pdfcube-pdfbox"
   {:project-id "org.apache.pdfbox:pdfbox:3.0.8"
    :package-id "PdfCube.PdfBox"
    :selector ":pdfbox"
    :dependencies ["pdfcube-io" "pdfcube-fontbox"]}
   "pdfcube-preflight"
   {:project-id "org.apache.pdfbox:preflight:3.0.8"
    :package-id "PdfCube.Preflight"
    :selector ":preflight"
    :dependencies ["pdfcube-pdfbox" "pdfcube-xmpbox"]}})

(defn- write-file!
  [root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- model-totals
  []
  {:shadow-symbols 0
   :unresolved-symbols 0
   :ambiguous-symbols 0
   :fallback-symbols 0})

(defn- summary
  []
  {:compilation-units 1
   :resources 0
   :declarations 2
   :skipped-source-units 0
   :missing-source-mappings 0
   :hard-failures 0
   :collisions 0
   :executable-coverage
   {:semantic 1
    :structural 1
    :covered 2
    :visited 2
    :fallback 0
    :missing-mappings 0
    :unsupported-elements 0
    :missing-occurrences 0
    :blocked 0}})

(defn- emission
  [root profile]
  (let [{:keys [project-id package-id dependencies]} (get products profile)
        module (subs profile (count "pdfcube-"))
        source-relative
        (str "research/pdfbox/" module
             "/src/main/java/org/example/" module "/Fixture.java")
        source (write-file! root source-relative "public class Fixture {}")
        project-root (paths/resolve-path root "target" "generated" profile)
        manifest-file (write-file!
                       project-root
                       "generation-manifest.edn"
                       (str (pr-str
                             {:sources
                              [{:source source-relative
                                :strategy :generated-csharp
                                :hard-failures 0}]
                              :resources []
                              :summary (summary)})
                            "\n"))
        _ manifest-file
        identity (str "type:org.example." module ".Fixture")
        public-metadata
        {:required-rows 1
         :rows
         [{:source-mapping :one-to-one
           :generated {:implementation :translated-body}
           :row {:identity identity}}]}]
    {:profile profile
     :dependency-profiles dependencies
     :source-project {:path (paths/resolve-path root "research/pdfbox")
                      :revision revision}
     :project-input
     {:project-id project-id
      :production-sources [source]
      :generated-production-sources []
      :production-resources []}
     :model-totals (model-totals)
     :destination
     {:project {:target-framework "net10.0"
                :warnings-as-errors true}
      :package {:id package-id}
      :output {:manifest-file "generation-manifest.edn"}}
     :project-root project-root
     :project-file
     (paths/resolve-path project-root (str package-id ".csproj"))
     :summary (summary)
     :public-metadata public-metadata}))

(defn- graph
  []
  {:schema-version 1
   :primary-profile "pdfcube-preflight"
   :topological-order order
   :projects
   (mapv
    (fn [profile]
      {:profile profile
       :dependency-profiles (get-in products [profile :dependencies])
       :package-id (get-in products [profile :package-id])})
    order)})

(defn- clean-build
  [root]
  (let [emissions (mapv #(emission root %) order)
        by-profile (into {} (map (juxt :profile identity)) emissions)
        primary (get by-profile "pdfcube-preflight")
        dependencies (mapv by-profile (butlast order))]
    {:generation
     {:generation-profile {:profile "pdfcube-preflight"}
      :source-project {:path "research/pdfbox" :revision revision}
      :project-graph (graph)
      :project-discovery
      {:schema-version 1
       :invocations
       [{:build-tool :maven
         :profiles order
         :project-ids (mapv #(get-in products [% :project-id]) order)
         :selected-projects (mapv #(get-in products [% :selector]) order)
         :manifest "target/maven-reactor-inputs-0.tsv"}]}
      :dependency-emissions dependencies
      :emission primary
      :resolved-project-input (:project-input primary)
      :java-model {:totals (:model-totals primary)}
      :destination (:destination primary)
      :public-api-boundary {}
      :public-surface-strategy {}}
     :diagnostics []
     :public-surface
     {:assemblies
      (mapv (fn [profile]
              {:assembly (get-in products [profile :package-id])})
            order)}}))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch ExceptionInfo error error)))

(deftest complete-family-build-accounting-is-exact
  (let [root (Files/createTempDirectory
              "pdfcube-family-build-" (make-array FileAttribute 0))
        evidence (family-build/validate-build! root (clean-build root))]
    (is (= order (:profiles evidence)))
    (is (= 5 (count (:projects evidence))))
    (is (= {:ordinary-sources 5
            :generated-sources 0
            :resources 0
            :compilation-units 5
            :declarations 10
            :accessible-declarations 5
            :public-stubs 0}
           (:totals evidence)))
    (is (= (mapv #(get-in products [% :package-id]) order)
           (:compiled-assemblies evidence)))))

(deftest family-build-gate-fails-closed-on-translation-and-accounting-gaps
  (let [root (Files/createTempDirectory
              "pdfcube-family-gap-" (make-array FileAttribute 0))
        build (clean-build root)]
    (testing "resolved frontend failures are blocking"
      (let [error
            (caught
             #(family-build/validate-build!
               root
               (assoc-in build
                         [:generation :dependency-emissions 0
                          :model-totals :unresolved-symbols]
                         1)))]
        (is (= :pdfcube-family-build-failed (:kind (ex-data error))))
        (is (= ["pdfcube-io" :model :unresolved-symbols]
               (:field (ex-data error))))))
    (testing "public implementation stubs are blocking"
      (let [error
            (caught
             #(family-build/validate-build!
               root
               (assoc-in build
                         [:generation :dependency-emissions 0
                          :public-metadata :rows 0
                          :generated :implementation]
                         :public-stub)))]
        (is (= :pdfcube-family-build-failed (:kind (ex-data error))))
        (is (= ["pdfcube-io" :public-stubs]
               (:field (ex-data error))))))
    (testing "duplicate manifest coverage is blocking"
      (let [relative
            "target/generated/pdfcube-io/generation-manifest.edn"
            manifest (edn/read-string
                      (slurp (str (paths/resolve-path root relative))))
            duplicate (first (:sources manifest))
            _ (write-file! root relative
                           (str (pr-str
                                 (update manifest :sources conj duplicate))
                                "\n"))
            error
            (caught #(family-build/validate-build! root build))]
        (is (= :pdfcube-family-build-failed (:kind (ex-data error))))
        (is (= :manifest-sources (:subject (ex-data error))))))))

(deftest generated-snapshot-comparison-detects-every-file-change
  (let [first {"a.cs" "one" "b.cs" "two"}
        same {"a.cs" "one" "b.cs" "two"}
        changed {"a.cs" "different" "c.cs" "three"}
        error (caught #(family-build/assert-deterministic! first changed))]
    (is (= {:files 2
            :sha256
            "c10a4331857154573fa59be15d7f124d9108b34043564addf6d36609aebdbcbf"}
           (family-build/assert-deterministic! first same)))
    (is (= :pdfcube-family-build-failed (:kind (ex-data error))))
    (is (= ["b.cs"] (:removed (ex-data error))))
    (is (= ["c.cs"] (:added (ex-data error))))
    (is (= ["a.cs"] (:changed (ex-data error))))))
