(ns dripsharp.pdfcube.family-build
  "Clean, deterministic generation, compilation, and complete accessible
  public-surface gate for the five-project PdfCube family."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.compiler :as compiler]
            [dripsharp.dotnet-surface :as dotnet-surface]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.java-project :as pdfcube]
            [dripsharp.util :as util])
  (:import [java.nio.file FileVisitOption Files Path]))

(def ^:private zero-emission-counters
  [:skipped-source-units :missing-source-mappings :hard-failures :collisions])

(def ^:private zero-coverage-counters
  [:fallback :missing-mappings :unsupported-elements :missing-occurrences
   :blocked])

(def ^:private source-implementations
  #{:abstract-contract :declaration :public-stub :systematic-adaptation
    :translated-body})

(def ^:private source-member-kinds
  #{"constructor" "field" "method" "type"})

(def ^:private compiled-member-kinds
  #{"constructor" "event" "field" "method" "property" "type"})

(def ^:private java-translation-rules
  #{"java-declaration" "java-implicit-constructor" "java-synthetic-member"})

(def ^:private compiled-translation-rules
  (into java-translation-rules
        #{"clr-special-accessor"
          "java-closeable-disposable"
          "java-compatibility-member"
          "java-compatibility-type"
          "java-functional-adapter-member"
          "java-functional-adapter-type"}))

(defn- fail!
  [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-family-build-failed))))

(defn- exact!
  [message field expected actual]
  (when-not (= expected actual)
    (fail! message {:field field :expected expected :actual actual}))
  actual)

(defn- duplicate-values
  [values]
  (->> values
       frequencies
       (filter #(< 1 (val %)))
       (map key)
       (sort-by str)
       vec))

(def ^:private portable-path util/portable-or-absolute-path)

(defn- family-contract
  []
  (let [family (pdfcube/product-family)
        products (vals (:products family))]
    {:revision (:source-revision family)
     :profiles (set (map :profile products))
     :source-projects
     (into {} (map (juxt :profile :source-project-id)) products)
     :packages (into {} (map (juxt :profile :package-id)) products)
     :selectors (into {} (map (juxt :profile :maven-selector)) products)
     :dependencies
     (into {} (map (juxt :profile :dependency-profiles)) products)}))

(defn- ordered-emissions
  [generation]
  (let [order (get-in generation [:project-graph :topological-order])
        primary (get-in generation [:generation-profile :profile])
        main
        (assoc (:emission generation)
               :profile primary
               :source-project (:source-project generation)
               :project-input (:resolved-project-input generation)
               :destination (:destination generation)
               :public-api-boundary (:public-api-boundary generation)
               :public-surface-strategy (:public-surface-strategy generation))
        emissions (conj (vec (:dependency-emissions generation)) main)
        duplicates (duplicate-values (map :profile emissions))
        by-profile (into {} (map (juxt :profile identity)) emissions)]
    (when (seq duplicates)
      (fail! "PdfCube generated a project more than once"
             {:duplicates duplicates}))
    (exact! "PdfCube generation emissions differ from the destination graph"
            :emission-profiles (set order) (set (keys by-profile)))
    (mapv by-profile order)))

(defn- validate-graph!
  [generation contract]
  (let [graph (:project-graph generation)
        order (:topological-order graph)
        projects (:projects graph)
        project-profiles (mapv :profile projects)
        dependencies (into {} (map (juxt :profile :dependency-profiles)) projects)
        positions (zipmap order (range))]
    (exact! "PdfCube destination graph must contain exactly five profiles"
            :profiles (:profiles contract) (set project-profiles))
    (exact! "PdfCube destination graph order and project records differ"
            :topological-project-order order project-profiles)
    (exact! "PdfCube destination dependencies differ from the product contract"
            :dependencies (:dependencies contract) dependencies)
    (doseq [[profile required] dependencies
            dependency required]
      (when-not (< (get positions dependency -1)
                   (get positions profile -1))
        (fail! "PdfCube destination graph is not topologically ordered"
               {:profile profile
                :dependency dependency
                :topological-order order})))
    graph))

(defn- validate-discovery!
  [generation contract order]
  (let [invocations (get-in generation [:project-discovery :invocations])
        invocation (first invocations)
        profiles (:profiles invocation)
        expected-project-ids
        (mapv (:source-projects contract) order)
        expected-selectors
        (mapv (:selectors contract) order)]
    (exact! "PdfCube must resolve its shared Maven reactor exactly once"
            :maven-discovery-invocations 1 (count invocations))
    (exact! "PdfCube discovery must use the Maven backend"
            :discovery-build-tool :maven (:build-tool invocation))
    (exact! "PdfCube Maven discovery profile order differs from the destination DAG"
            :discovery-profiles order profiles)
    (exact! "PdfCube Maven discovery selected the wrong reactor projects"
            :discovery-project-ids expected-project-ids
            (:project-ids invocation))
    (exact! "PdfCube Maven discovery selected the wrong Maven modules"
            :maven-selectors expected-selectors
            (:selected-projects invocation))
    invocation))

(defn- manifest!
  [emission]
  (let [file
        (paths/resolve-path
         (:project-root emission)
         (get-in emission [:destination :output :manifest-file]))]
    (when-not (paths/regular-file? file)
      (fail! "PdfCube generation manifest is missing"
             {:profile (:profile emission) :path (str file)}))
    (edn/read-string (slurp (str file)))))

(defn- validate-zero-counters!
  [profile summary]
  (doseq [counter zero-emission-counters]
    (exact! "PdfCube emission contains a nonzero failure counter"
            [profile :emission counter] 0 (get summary counter)))
  (doseq [counter zero-coverage-counters]
    (exact! "PdfCube executable coverage contains a nonzero failure counter"
            [profile :coverage counter] 0
            (get-in summary [:executable-coverage counter])))
  (let [{:keys [semantic structural covered visited]}
        (:executable-coverage summary)]
    (exact! "PdfCube executable coverage omitted visited elements"
            [profile :coverage :visited] visited covered)
    (exact! "PdfCube executable coverage totals are inconsistent"
            [profile :coverage :partition] covered (+ semantic structural))))

(defn- validate-public-rows!
  [profile assembly metadata]
  (let [rows (:rows metadata)
        identities (mapv #(get-in % [:row :identity]) rows)
        public-stubs
        (filter #(= :public-stub (get-in % [:generated :implementation])) rows)
        invalid-mappings
        (remove #(contains? #{:one-to-one
                              :documented-systematic-adaptation}
                            (:source-mapping %))
                rows)
        invalid-implementations
        (remove #(contains? source-implementations
                            (get-in % [:generated :implementation]))
                rows)
        adaptations (set (keep :systematic-adaptation rows))
        adaptation-docs (:systematic-adaptations metadata)]
    (exact! "PdfCube public surface must use the complete reusable strategy"
            [profile :public-surface-strategy]
            :complete-accessible-java-library (:strategy metadata))
    (exact! "PdfCube public surface must derive from the resolved Spoon model"
            [profile :surface-derivation]
            :resolved-spoon-model (:surface-derivation metadata))
    (when (contains? metadata :compiled-contract-file)
      (fail! "PdfCube public surface must not use a retained API inventory"
             {:profile profile
              :compiled-contract-file (:compiled-contract-file metadata)}))
    (exact! "PdfCube accessible declaration accounting is incomplete"
            [profile :accessible-declarations]
            (:required-rows metadata) (count rows))
    (when-let [duplicates (seq (duplicate-values identities))]
      (fail! "PdfCube accessible declarations were covered more than once"
             {:profile profile :duplicates (vec duplicates)}))
    (when (seq invalid-mappings)
      (fail! "PdfCube accessible declarations contain unsupported mappings"
             {:profile profile
              :declarations
              (mapv #(get-in % [:row :identity]) invalid-mappings)}))
    (when (seq invalid-implementations)
      (fail! "PdfCube accessible declarations have unsupported implementation states"
             {:profile profile
              :implementations
              (mapv #(get-in % [:generated :implementation])
                    invalid-implementations)}))
    (exact! "PdfCube generated public implementation stubs"
            [profile :public-stubs] 0 (count public-stubs))
    (exact! "PdfCube systematic adaptations are not documented exactly"
            [profile :systematic-adaptations]
            adaptations (set (keys adaptation-docs)))
    (doseq [[adaptation explanation] adaptation-docs]
      (when-not (and (keyword? adaptation)
                     (string? explanation)
                     (not (str/blank? explanation)))
        (fail! "PdfCube systematic adaptation documentation is invalid"
               {:profile profile :adaptation adaptation
                :explanation explanation})))
    (doseq [row rows]
      (let [identity (get-in row [:row :identity])
            mapping (:source-mapping row)
            adaptation (:systematic-adaptation row)
            generated (:generated row)
            destination (:destination generated)
            source-location (get-in generated [:source :location])
            namespace (:namespace destination)
            owner (:owner destination)]
        (when-not (and (string? identity) (not (str/blank? identity)))
          (fail! "PdfCube accessible declaration lacks a stable Spoon identity"
                 {:profile profile :row row}))
        (when-not (and (= assembly (:assembly destination))
                       (contains? source-member-kinds (:kind destination))
                       (string? (:name destination))
                       (re-matches #"\d+" (:parameter-count destination))
                       (contains? #{"protected" "protected-internal" "public"}
                                  (:visibility destination))
                       (string? namespace) (not (str/blank? namespace))
                       (string? owner)
                       (str/starts-with? owner (str namespace ".")))
          (fail! "PdfCube generated declaration has an invalid assembly, namespace, owner, kind, overload, or visibility boundary"
                 {:profile profile :source-declaration identity
                  :destination destination}))
        (when-not (and (string? (:file source-location))
                       (not (str/blank? (:file source-location)))
                       (pos-int? (:line source-location)))
          (fail! "PdfCube generated declaration lacks exact Spoon source provenance"
                 {:profile profile :source-declaration identity
                  :source-location source-location}))
        (case mapping
          :one-to-one
          (when adaptation
            (fail! "One-to-one PdfCube source mapping carries an adaptation"
                   {:profile profile :source-declaration identity
                    :adaptation adaptation}))

          :documented-systematic-adaptation
          (when-not (and (keyword? adaptation)
                         (contains? adaptation-docs adaptation))
            (fail! "PdfCube systematic source mapping lacks documented adaptation evidence"
                   {:profile profile :source-declaration identity
                    :adaptation adaptation}))

          nil)))
    {:accessible-declarations (count rows)
     :public-stubs (count public-stubs)
     :source-mappings (into (sorted-map)
                            (frequencies (map :source-mapping rows)))
     :systematic-adaptations (into (sorted-map)
                                   (frequencies
                                    (keep :systematic-adaptation rows)))
     :source-kinds
     (into (sorted-map)
           (frequencies (map #(get-in % [:generated :destination :kind])
                             rows)))
     :identities identities}))

(defn- validate-project!
  [workspace-root emission contract]
  (let [profile (:profile emission)
        input (:project-input emission)
        destination (:destination emission)
        summary (:summary emission)
        manifest (manifest! emission)
        ordinary (mapv #(portable-path workspace-root %)
                       (:production-sources input))
        generated (mapv #(portable-path workspace-root %)
                        (:generated-production-sources input))
        sources (into ordinary generated)
        resources (mapv #(portable-path workspace-root %)
                        (:production-resources input))
        manifest-sources (mapv :source (:sources manifest))
        manifest-resources (mapv :source (:resources manifest))
        assembly (get-in destination [:project :assembly-name])
        public (validate-public-rows! profile assembly
                                      (:public-metadata emission))]
    (exact! "PdfCube project input came from the wrong Maven reactor project"
            [profile :source-project-id]
            (get-in contract [:source-projects profile])
            (:project-id input))
    (exact! "PdfCube project source revision differs from the synchronized family"
            [profile :source-revision]
            (:revision contract) (get-in emission [:source-project :revision]))
    (exact! "PdfCube project targets the wrong framework"
            [profile :target-framework] "net10.0"
            (get-in destination [:project :target-framework]))
    (exact! "PdfCube project must compile with warnings as errors"
            [profile :warnings-as-errors] true
            (get-in destination [:project :warnings-as-errors]))
    (exact! "PdfCube package identity differs from the family contract"
            [profile :package-id] (get-in contract [:packages profile])
            (get-in destination [:package :id]))
    (exact! "PdfCube assembly identity differs from its package boundary"
            [profile :assembly-name] (get-in contract [:packages profile])
            assembly)
    (doseq [[subject values]
            [[:ordinary-sources ordinary]
             [:generated-sources generated]
             [:resources resources]
             [:manifest-sources manifest-sources]
             [:manifest-resources manifest-resources]]]
      (when-let [duplicates (seq (duplicate-values values))]
        (fail! "PdfCube project contains duplicate production entries"
               {:profile profile
                :subject subject
                :duplicates (vec duplicates)})))
    (exact! "PdfCube generation did not cover every production source once"
            [profile :production-sources]
            (set sources) (set manifest-sources))
    (exact! "PdfCube production source count differs from compilation units"
            [profile :compilation-units]
            (count sources) (:compilation-units summary))
    (exact! "PdfCube generation did not cover every production resource once"
            [profile :production-resources]
            (set resources) (set manifest-resources))
    (exact! "PdfCube production resource count differs from emission"
            [profile :resources]
            (count resources) (:resources summary))
    (validate-zero-counters! profile summary)
    (merge
     {:profile profile
      :package-id (get-in destination [:package :id])
      :project-id (:project-id input)
      :ordinary-sources (count ordinary)
      :generated-sources (count generated)
      :resources (count resources)
      :declarations (:declarations summary)
      :compilation-units (:compilation-units summary)}
     (dissoc public :identities)
     {:source-paths sources
      :resource-paths resources
      :declaration-identities (:identities public)})))

(defn- valid-compiled-visibility?
  [visibility]
  (or (contains? #{"protected" "protected-internal" "public"} visibility)
      (boolean
       (re-matches
        #"(?:get|set|add|remove|raise)=(?:protected|protected-internal|public)(?:;(?:get|set|add|remove|raise)=(?:protected|protected-internal|public))*"
        visibility))))

(defn- validate-compiled-audit!
  [root build-configuration emission project audit]
  (let [profile (:profile emission)
        assembly (:package-id project)
        framework (get-in emission [:destination :project :target-framework])
        expected-file
        (paths/resolve-path (:project-root emission) "bin" build-configuration
                            framework (str assembly ".dll"))
        rows (:rows audit)
        types (:types audit)
        members (:members audit)
        contract-members (:contract-members audit)
        kind-counts (:kind-counts audit)
        visibility-counts (:visibility-counts audit)
        translation-rules (:translation-rules audit)
        java-rows (reduce + 0 (map #(get translation-rules % 0)
                                   java-translation-rules))]
    (exact! "PdfCube compiled surface audit has the wrong assembly"
            [profile :compiled :assembly] assembly (:assembly audit))
    (exact! "PdfCube compiled surface audit points at the wrong assembly file"
            [profile :compiled :file] (str (paths/absolute expected-file))
            (str (paths/absolute (:file audit))))
    (when-not (paths/regular-file? expected-file)
      (fail! "PdfCube compiled surface assembly is missing"
             {:profile profile :file (str expected-file)}))
    (exact! "PdfCube compiled surface did not inspect every metadata dimension"
            [profile :compiled :metadata-columns]
            dotnet-surface/surface-columns (:metadata-columns audit))
    (doseq [[field value]
            [[:rows rows]
             [:types types]
             [:members members]
             [:contract-members contract-members]
             [:metadata-complete-rows (:metadata-complete-rows audit)]
             [:owners (:owners audit)]
             [:inheritance-rows (:inheritance-rows audit)]
             [:generic-rows (:generic-rows audit)]
             [:overload-families (:overload-families audit)]]]
      (when-not (and (integer? value) (not (neg? value)))
        (fail! "PdfCube compiled surface audit has an invalid count"
               {:profile profile :field field :actual value})))
    (when-not (and (pos? rows) (pos? types) (pos? members)
                   (pos? contract-members) (pos? (:owners audit)))
      (fail! "PdfCube compiled surface audit is empty"
             {:profile profile
              :counts (select-keys audit
                                   [:rows :types :members :contract-members
                                    :owners])}))
    (exact! "PdfCube compiled surface type/member accounting is inconsistent"
            [profile :compiled :row-partition] rows (+ types members))
    (exact! "PdfCube compiled surface contains incomplete metadata rows"
            [profile :compiled :metadata-complete-rows]
            rows (:metadata-complete-rows audit))
    (exact! "PdfCube compiled surface does not reconcile every Spoon declaration"
            [profile :compiled :contract-members]
            (:accessible-declarations project) contract-members)
    (when (< rows contract-members)
      (fail! "PdfCube compiled surface has fewer CLR rows than Spoon declarations"
             {:profile profile :compiled-rows rows
              :contract-members contract-members}))
    (when-not (and (map? kind-counts)
                   (= rows (reduce + 0 (vals kind-counts)))
                   (every? compiled-member-kinds (keys kind-counts)))
      (fail! "PdfCube compiled surface contains invalid or unaccounted member kinds"
             {:profile profile :rows rows :kind-counts kind-counts}))
    (when-not (and (map? visibility-counts)
                   (= rows (reduce + 0 (vals visibility-counts)))
                   (every? valid-compiled-visibility?
                           (keys visibility-counts)))
      (fail! "PdfCube compiled surface contains invalid or unaccounted visibility"
             {:profile profile :rows rows
              :visibility-counts visibility-counts}))
    (exact! "PdfCube compiled surface crossed its assembly/package boundary"
            [profile :compiled :assembly-row-counts]
            (sorted-map assembly rows) (:assembly-row-counts audit))
    (when-not (and (map? translation-rules)
                   (= rows (reduce + 0 (vals translation-rules)))
                   (every? compiled-translation-rules
                           (keys translation-rules)))
      (fail! "PdfCube compiled surface contains an unsupported adaptation rule"
             {:profile profile :rows rows
              :translation-rules translation-rules}))
    (exact! "PdfCube compiled surface did not map every Spoon declaration once"
            [profile :compiled :java-translation-rows]
            contract-members java-rows)
    (when-not (re-matches #"[0-9a-f]{64}" (:surface-sha256 audit))
      (fail! "PdfCube compiled surface fingerprint is invalid"
             {:profile profile :surface-sha256 (:surface-sha256 audit)}))
    (assoc (select-keys audit
                        [:assembly :rows :types :members :contract-members
                         :metadata-columns :metadata-complete-rows :kind-counts
                         :visibility-counts :assembly-row-counts :owners
                         :inheritance-rows :generic-rows :overload-families
                         :surface-sha256 :translation-rules])
           :profile profile
           :file (portable-path root expected-file))))

(defn- validate-compiled-surface!
  [root build emissions projects]
  (let [surface (:public-surface build)
        audits (:assemblies surface)
        build-configuration (:build-configuration build)]
    (exact! "PdfCube clean build used the wrong public-surface strategy"
            :compiled-public-surface-strategy
            :complete-accessible-java-library (:strategy surface))
    (when-not (and (string? build-configuration)
                   (not (str/blank? build-configuration)))
      (fail! "PdfCube clean build lacks its build configuration"
             {:build-configuration build-configuration}))
    (exact! "PdfCube clean build did not audit exactly five assemblies"
            :compiled-surface-assembly-count
            (count emissions) (count audits))
    (let [validated
          (mapv #(validate-compiled-audit!
                  root build-configuration %1 %2 %3)
                emissions projects audits)
          totals
          {:rows (reduce + (map :rows validated))
           :types (reduce + (map :types validated))
           :members (reduce + (map :members validated))
           :contract-members (reduce + (map :contract-members validated))
           :inheritance-rows (reduce + (map :inheritance-rows validated))
           :generic-rows (reduce + (map :generic-rows validated))
           :overload-families (reduce + (map :overload-families validated))}]
      (doseq [dimension [:inheritance-rows :generic-rows :overload-families]]
        (when-not (pos? (get totals dimension))
          (fail! "PdfCube family compiled surface did not exercise a required metadata dimension"
                 {:dimension dimension :totals totals})))
      {:strategy (:strategy surface)
       :assemblies validated
       :totals totals})))

(defn validate-build!
  "Validates one clean PdfCube compiler result and returns bounded family
  accounting evidence."
  [workspace-root build]
  (let [root (paths/absolute workspace-root)
        generation (:generation build)
        contract (family-contract)
        graph (validate-graph! generation contract)
        order (:topological-order graph)
        discovery (validate-discovery! generation contract order)
        emissions (ordered-emissions generation)
        projects
        (mapv #(validate-project! root % contract)
              emissions)
        all-sources (mapcat :source-paths projects)
        all-resources (mapcat :resource-paths projects)
        all-declarations (mapcat :declaration-identities projects)
        source-kinds (apply merge-with + (map :source-kinds projects))
        source-mappings (apply merge-with + (map :source-mappings projects))
        compiled-surface
        (validate-compiled-surface! root build emissions projects)
        assemblies (:assemblies compiled-surface)
        assembly-names (set (map :assembly assemblies))
        expected-assemblies (set (vals (:packages contract)))]
    (doseq [[subject values]
            [[:production-sources all-sources]
             [:production-resources all-resources]
             [:accessible-declarations all-declarations]]]
      (when-let [duplicates (seq (duplicate-values values))]
        (fail! "PdfCube family covered selected production input more than once"
               {:subject subject :duplicates (vec duplicates)})))
    (exact! "PdfCube clean build did not compile exactly five assemblies"
            :compiled-assemblies expected-assemblies assembly-names)
    (when-not (and (= source-member-kinds (set (keys source-kinds)))
                   (every? pos? (vals source-kinds)))
      (fail! "PdfCube family source surface did not prove every declaration kind"
             {:source-kinds source-kinds
              :required source-member-kinds}))
    (when-not (and (pos? (get source-mappings :one-to-one 0))
                   (pos? (get source-mappings
                              :documented-systematic-adaptation 0)))
      (fail! "PdfCube family source surface did not prove both exact and adapted mappings"
             {:source-mappings source-mappings}))
    (exact! "PdfCube clean build retained compiler diagnostics"
            :compiler-diagnostics [] (:diagnostics build))
    {:profiles order
     :discovery discovery
     :projects
     (mapv #(dissoc % :source-paths :resource-paths
                    :declaration-identities)
           projects)
     :compiled-assemblies (mapv :assembly assemblies)
     :public-surface compiled-surface
     :totals
     {:ordinary-sources (reduce + (map :ordinary-sources projects))
      :generated-sources (reduce + (map :generated-sources projects))
      :resources (reduce + (map :resources projects))
      :compilation-units (reduce + (map :compilation-units projects))
      :declarations (reduce + (map :declarations projects))
      :accessible-declarations
      (reduce + (map :accessible-declarations projects))
      :compiled-surface-rows (get-in compiled-surface [:totals :rows])
      :compiled-types (get-in compiled-surface [:totals :types])
      :compiled-members (get-in compiled-surface [:totals :members])
      :public-stubs (reduce + (map :public-stubs projects))}}))

(defn validate-baseline-public-counts!
  "Checks live Spoon-derived source rows against the reviewed PdfCube target
  record. Kept at the concrete family gate so synthetic structural validators
  can still exercise small fixtures."
  [workspace-root build]
  (let [root (paths/absolute workspace-root)]
    (doseq [emission (ordered-emissions (:generation build))
            :let [profile (:profile emission)
                  actual (count (get-in emission [:public-metadata :rows]))
                  expected
                  (:public-contract-rows
                   (baseline/profile-by-name root :pdfcube profile))]]
      (exact! "PdfCube public-contract row count differs from the reviewed target baseline"
              [profile :baseline-public-contract-rows]
              expected actual)))
  build)

(def ^:private sha256-file util/sha256-file)

(defn- generated-output?
  [^Path target ^Path file]
  (let [segments (map str (iterator-seq (.iterator (.relativize target file))))]
    (and (paths/regular-file? file)
         (not-any? #{"bin" "obj"} segments))))

(defn generated-snapshot
  "Returns the deterministic file/hash snapshot for disposable generation
  output, excluding only compiler and restore intermediates."
  [workspace-root]
  (let [target (paths/resolve-path (paths/absolute workspace-root) "target")]
    (when-not (paths/directory? target)
      (fail! "PdfCube clean generation output is missing"
             {:path (str target)}))
    (with-open [entries (Files/walk target (make-array FileVisitOption 0))]
      (into
       (sorted-map)
       (map (fn [^Path file]
              [(portable-path target file) (sha256-file file)]))
       (filter #(generated-output? target %)
               (map #(cast Path %) (.toArray entries)))))))

(defn- snapshot-sha256
  [snapshot]
  (util/sha256-text
   (apply str
          (for [[path hash] snapshot]
            (str path "\t" hash "\n")))))

(defn assert-deterministic!
  "Fails with an exact added/removed/changed file report when two clean
  generation snapshots differ."
  [first-snapshot second-snapshot]
  (when-not (= first-snapshot second-snapshot)
    (let [first-paths (set (keys first-snapshot))
          second-paths (set (keys second-snapshot))
          shared (set/intersection first-paths second-paths)]
      (fail! "Repeated clean PdfCube generation was not deterministic"
             {:removed (vec (sort (set/difference first-paths second-paths)))
              :added (vec (sort (set/difference second-paths first-paths)))
              :changed
              (->> shared
                   (filter #(not= (get first-snapshot %)
                                  (get second-snapshot %)))
                   sort
                   vec)})))
  {:files (count first-snapshot)
   :sha256 (snapshot-sha256 first-snapshot)})

(defn verify!
  "Runs two independent clean five-project generations and warnings-as-errors
  builds, then proves exact destination-tree determinism."
  ([]
   (verify! {}))
  ([{:keys [workspace-root verify-clean-build-fn]
     :or {workspace-root (paths/workspace-root)
          verify-clean-build-fn compiler/verify-clean-build!}}]
   (let [root (paths/absolute workspace-root)
         run! #(verify-clean-build-fn
                {:workspace-root root :profile "pdfcube-preflight"})
         first-build (run!)
         _first-baseline (validate-baseline-public-counts! root first-build)
         first-evidence (validate-build! root first-build)
         first-snapshot (generated-snapshot root)
         second-build (run!)
         _second-baseline (validate-baseline-public-counts! root second-build)
         second-evidence (validate-build! root second-build)
         second-snapshot (generated-snapshot root)
         deterministic (assert-deterministic! first-snapshot second-snapshot)]
     (exact! "Repeated clean PdfCube family accounting changed"
             :family-accounting first-evidence second-evidence)
     (println
      (str "Clean deterministic PdfCube family build passed: "
           (count (:projects second-evidence)) " projects, "
           (get-in second-evidence [:totals :compilation-units])
           " production sources, "
           (get-in second-evidence [:totals :resources]) " resources, "
           (get-in second-evidence [:totals :accessible-declarations])
           " accessible declarations, "
           (get-in second-evidence [:totals :compiled-surface-rows])
           " compiled metadata rows, 0 public stubs, "
           (:files deterministic) " deterministic generated files."))
     (assoc second-evidence
            :clean-generations 2
            :generated-output deterministic))))
