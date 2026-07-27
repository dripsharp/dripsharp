(ns dripsharp.pdfcube.family-build
  "Clean, deterministic generation and compilation gate for the five-project
  PdfCube family."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.compiler :as compiler]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.java-project :as pdfcube])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files OpenOption Path]
           [java.security MessageDigest]))

(def ^:private zero-model-counters
  [:shadow-symbols :unresolved-symbols :ambiguous-symbols :fallback-symbols])

(def ^:private zero-emission-counters
  [:skipped-source-units :missing-source-mappings :hard-failures :collisions])

(def ^:private zero-coverage-counters
  [:fallback :missing-mappings :unsupported-elements :missing-occurrences
   :blocked])

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

(defn- portable-path
  [^Path root ^Path input]
  (let [input (.normalize input)]
    (-> (if (.startsWith input root)
          (str (.relativize root input))
          (str input))
        (str/replace "\\" "/"))))

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
               :model-totals (get-in generation [:java-model :totals])
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
  [profile model-totals summary]
  (doseq [counter zero-model-counters]
    (exact! "PdfCube resolved model contains a nonzero failure counter"
            [profile :model counter] 0 (get model-totals counter)))
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
  [profile metadata]
  (let [rows (:rows metadata)
        identities (mapv #(get-in % [:row :identity]) rows)
        public-stubs
        (filter #(= :public-stub (get-in % [:generated :implementation])) rows)
        invalid-mappings
        (remove #(contains? #{:one-to-one
                              :documented-systematic-adaptation}
                            (:source-mapping %))
                rows)]
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
    (exact! "PdfCube generated public implementation stubs"
            [profile :public-stubs] 0 (count public-stubs))
    {:accessible-declarations (count rows)
     :public-stubs (count public-stubs)
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
        public (validate-public-rows! profile (:public-metadata emission))]
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
    (validate-zero-counters! profile (:model-totals emission) summary)
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
        projects
        (mapv #(validate-project! root % contract)
              (ordered-emissions generation))
        all-sources (mapcat :source-paths projects)
        all-resources (mapcat :resource-paths projects)
        all-declarations (mapcat :declaration-identities projects)
        assemblies (get-in build [:public-surface :assemblies])
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
    (exact! "PdfCube clean build retained compiler diagnostics"
            :compiler-diagnostics [] (:diagnostics build))
    {:profiles order
     :discovery discovery
     :projects
     (mapv #(dissoc % :source-paths :resource-paths
                    :declaration-identities)
           projects)
     :compiled-assemblies (mapv :assembly assemblies)
     :totals
     {:ordinary-sources (reduce + (map :ordinary-sources projects))
      :generated-sources (reduce + (map :generated-sources projects))
      :resources (reduce + (map :resources projects))
      :compilation-units (reduce + (map :compilation-units projects))
      :declarations (reduce + (map :declarations projects))
      :accessible-declarations
      (reduce + (map :accessible-declarations projects))
      :public-stubs (reduce + (map :public-stubs projects))}}))

(defn- sha256-file
  [^Path file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (Files/newInputStream file (make-array OpenOption 0))]
      (let [buffer (byte-array 16384)]
        (loop [read (.read input buffer)]
          (when-not (neg? read)
            (when (pos? read)
              (.update digest buffer 0 read))
            (recur (.read input buffer))))))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

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
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [[path hash] snapshot]
      (.update digest (.getBytes (str path "\t" hash "\n")
                                 StandardCharsets/UTF_8)))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

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
         first-evidence (validate-build! root first-build)
         first-snapshot (generated-snapshot root)
         second-build (run!)
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
           " accessible declarations, 0 public stubs, "
           (:files deterministic) " deterministic generated files."))
     (assoc second-evidence
            :clean-generations 2
            :generated-output deterministic))))
