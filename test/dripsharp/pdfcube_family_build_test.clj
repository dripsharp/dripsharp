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
    :package-id "DripSharp.PdfCarton.IO"
    :selector ":pdfbox-io"
    :dependencies []}
   "pdfcube-fontbox"
   {:project-id "org.apache.pdfbox:fontbox:3.0.8"
    :package-id "DripSharp.PdfCarton.Fonts"
    :selector ":fontbox"
    :dependencies ["pdfcube-io"]}
   "pdfcube-xmpbox"
   {:project-id "org.apache.pdfbox:xmpbox:3.0.8"
    :package-id "DripSharp.PdfCarton.Xmp"
    :selector ":xmpbox"
    :dependencies []}
   "pdfcube-pdfbox"
   {:project-id "org.apache.pdfbox:pdfbox:3.0.8"
    :package-id "DripSharp.PdfCarton"
    :selector ":pdfbox"
    :dependencies ["pdfcube-io" "pdfcube-fontbox"]}
   "pdfcube-preflight"
   {:project-id "org.apache.pdfbox:preflight:3.0.8"
    :package-id "DripSharp.PdfCarton.Preflight"
    :selector ":preflight"
    :dependencies ["pdfcube-pdfbox" "pdfcube-xmpbox"]}})

(defn- write-file!
  [root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

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
        project-root
        (paths/resolve-path
         root "target" "generated" "pdfcarton" "src" package-id)
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
        owner (str package-id ".Fixture")
        source-location {:file (str source) :line 1 :column 1}
        destinations
        [{:kind "type" :name "Fixture" :parameter-count "0"
          :implementation :declaration :source-mapping :one-to-one}
         {:kind "constructor" :name ".ctor" :parameter-count "0"
          :implementation :systematic-adaptation
          :source-mapping :documented-systematic-adaptation
          :systematic-adaptation :java-implicit-default-constructor}
         {:kind "field" :name "Value" :parameter-count "0"
          :implementation :declaration :source-mapping :one-to-one}
         {:kind "method" :name "GetValue" :parameter-count "0"
          :implementation :translated-body :source-mapping :one-to-one}]
        public-metadata
        {:schema-version 1
         :strategy :complete-accessible-java-library
         :surface-derivation :resolved-spoon-model
         :systematic-adaptations
         {:java-implicit-default-constructor
          "A Java implicit default constructor is represented by the CLR default constructor."}
         :required-rows (count destinations)
         :rows
         (mapv
          (fn [{:keys [kind name parameter-count implementation
                       source-mapping systematic-adaptation]}]
            (cond->
             {:source-mapping source-mapping
              :generated
              {:implementation implementation
               :source {:location source-location}
               :destination
               {:assembly package-id
                :namespace package-id
                :owner owner
                :kind kind
                :name name
                :parameter-count parameter-count
                :visibility "public"}}
              :row
              {:identity (str kind ":org.example." module ".Fixture#" name)}}
              systematic-adaptation
              (assoc :systematic-adaptation systematic-adaptation)))
          destinations)}]
    {:profile profile
     :dependency-profiles dependencies
     :source-project {:path (paths/resolve-path root "research/pdfbox")
                      :revision revision}
     :project-input
     {:project-id project-id
      :production-sources [source]
      :generated-production-sources []
      :production-resources []}
     :destination
     {:project {:assembly-name package-id
                :target-framework "net10.0"
                :warnings-as-errors true}
      :package {:id package-id}
      :output {:manifest-file "generation-manifest.edn"}}
     :project-root project-root
     :project-file
     (paths/resolve-path project-root (str package-id ".csproj"))
     :summary (summary)
     :public-metadata public-metadata}))

(defn- compiled-audit
  [emission]
  (let [assembly (get-in emission [:destination :package :id])
        file (write-file!
              (:project-root emission)
              (str "bin/Release/net10.0/" assembly ".dll")
              "compiled fixture")]
    {:assembly assembly
     :file (str file)
     :rows 8
     :types 2
     :members 6
     :contract-members 4
     :metadata-columns
     ["assembly" "owner" "kind" "name" "parameter-count" "visibility"
      "metadata-flags" "signature" "generic-constraints" "nullability"]
     :metadata-complete-rows 8
     :kind-counts
     (sorted-map "constructor" 1 "field" 1 "method" 4 "type" 2)
     :visibility-counts (sorted-map "public" 8)
     :assembly-row-counts (sorted-map assembly 8)
     :owners 2
     :inheritance-rows 1
     :generic-rows 1
     :overload-families 1
     :surface-sha256 (apply str (repeat 64 "a"))
     :translation-rules
     (sorted-map
      "java-compatibility-member" 3
      "java-compatibility-type" 1
      "java-declaration" 3
      "java-implicit-constructor" 1)}))

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
      :java-model {:totals {}}
      :destination (:destination primary)
      :public-api-boundary {}
      :public-surface-strategy {}}
     :build-configuration "Release"
     :diagnostics []
     :public-surface
     {:strategy :complete-accessible-java-library
      :assemblies (mapv compiled-audit emissions)}}))

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
            :accessible-declarations 20
            :compiled-surface-rows 40
            :compiled-types 10
            :compiled-members 30
            :public-stubs 0}
           (:totals evidence)))
    (is (= (mapv #(get-in products [% :package-id]) order)
           (:compiled-assemblies evidence)))))

(deftest family-build-gate-fails-closed-on-translation-and-accounting-gaps
  (let [root (Files/createTempDirectory
              "pdfcube-family-gap-" (make-array FileAttribute 0))
        build (clean-build root)]
    (testing "observed translation coverage failures are blocking"
      (let [error
            (caught
             #(family-build/validate-build!
               root
               (assoc-in build
                         [:generation :dependency-emissions 0
                          :summary :executable-coverage :missing-mappings]
                         1)))]
        (is (= :pdfcube-family-build-failed (:kind (ex-data error))))
        (is (= ["pdfcube-io" :coverage :missing-mappings]
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
            "target/generated/pdfcarton/src/DripSharp.PdfCarton.IO/generation-manifest.edn"
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

(deftest family-build-gate-rejects-malformed-generation-manifests
  (doseq [[label contents reason]
          [["empty" "" :empty-edn]
           ["invalid" "{" :invalid-edn]
           ["trailing" ::append-trailing :trailing-data]]]
    (let [root
          (Files/createTempDirectory
           (str "pdfcube-family-" label "-manifest-")
           (make-array FileAttribute 0))
          build (clean-build root)
          relative "target/generated/pdfcarton/src/DripSharp.PdfCarton.IO/generation-manifest.edn"
          file (paths/resolve-path root relative)
          contents (if (= ::append-trailing contents)
                     (str (slurp (str file)) "\n{}")
                     contents)
          _ (write-file! root relative contents)
          error (caught #(family-build/validate-build! root build))]
      (is (= :pdfcube-family-build-failed (:kind (ex-data error))) label)
      (is (= "pdfcube-io" (:profile (ex-data error))) label)
      (is (= reason (:reason (ex-data error))) label))))

(deftest family-public-surface-gate-rejects-source-and-compiled-defects
  (let [root (Files/createTempDirectory
              "pdfcube-family-surface-gap-"
              (make-array FileAttribute 0))
        build (clean-build root)
        validate
        (fn [candidate]
          (caught #(family-build/validate-build! root candidate)))]
    (testing "a retained API inventory cannot replace live Spoon derivation"
      (let [error
            (validate
             (-> build
                 (assoc-in
                  [:generation :dependency-emissions 0
                   :public-metadata :surface-derivation]
                  :retained-contract)
                 (assoc-in
                  [:generation :dependency-emissions 0
                   :public-metadata :compiled-contract-file]
                  "PublicSurface.tsv")))]
        (is (= :pdfcube-family-build-failed (:kind (ex-data error))))
        (is (= ["pdfcube-io" :surface-derivation]
               (:field (ex-data error))))))
    (testing "duplicate source declarations fail closed"
      (let [row
            (get-in build
                    [:generation :dependency-emissions 0
                     :public-metadata :rows 0])
            error
            (validate
             (-> build
                 (update-in
                  [:generation :dependency-emissions 0
                   :public-metadata :required-rows]
                  inc)
                 (update-in
                  [:generation :dependency-emissions 0
                   :public-metadata :rows]
                  conj row)))]
        (is (= :pdfcube-family-build-failed (:kind (ex-data error))))
        (is (= "pdfcube-io" (:profile (ex-data error))))
        (is (seq (:duplicates (ex-data error))))))
    (doseq [[label path value]
            [[:kind-change
              [:generation :dependency-emissions 0 :public-metadata :rows 0
               :generated :destination :kind]
              "property"]
             [:assembly-boundary
              [:generation :dependency-emissions 0 :public-metadata :rows 0
               :generated :destination :assembly]
              "Wrong.Package"]
             [:owner-placement
              [:generation :dependency-emissions 0 :public-metadata :rows 0
               :generated :destination :owner]
              "Wrong.Namespace.Fixture"]
             [:unsupported-implementation
              [:generation :dependency-emissions 0 :public-metadata :rows 0
               :generated :implementation]
              :unknown]
             [:unsupported-construct
              [:generation :dependency-emissions 0 :summary
               :executable-coverage :unsupported-elements]
              1]
             [:emission-collision
              [:generation :dependency-emissions 0 :summary :collisions]
              1]
             [:compiled-missing-member
              [:public-surface :assemblies 0 :contract-members]
              3]
             [:compiled-metadata-dimension
              [:public-surface :assemblies 0 :metadata-columns]
              ["assembly" "owner"]]
             [:compiled-package-boundary
              [:public-surface :assemblies 0 :assembly-row-counts]
              (sorted-map "Wrong.Package" 8)]
             [:compiled-fingerprint
              [:public-surface :assemblies 0 :surface-sha256]
              "not-a-sha256"]]]
      (testing (name label)
        (let [error (validate (assoc-in build path value))]
          (is (= :pdfcube-family-build-failed
                 (:kind (ex-data error)))))))))

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
