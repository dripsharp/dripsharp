(ns dripsharp.rebaseline
  "Review-first target baseline observation and approval.

  Observation may write disposable evidence below target/rebaseline, but only
  an exact approval token may replace a baseline record. Product-goal, scope,
  dependency, exclusion, and completion-contract documents are never writable
  through this workflow."
  (:refer-clojure :exclude [run!])
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.java-library :as java-library]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.public-api-contract :as pkl-public-api]
            [dripsharp.process :as process]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def protected-contract-files
  ["doc/targets/pkl/product-goal.md"
   "doc/targets/pkl/port-scope.md"
   "doc/targets/pdfbox/product-goal.md"
   "doc/targets/pdfbox/port-scope.md"
   "doc/targets/pdfbox/dependencies.md"])

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :rebaseline-failed)))))

(defn- canonicalize
  [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (map (fn [[key item]] [key (canonicalize item)]))
          value)

    (set? value) (mapv canonicalize (sort-by pr-str value))
    (sequential? value) (mapv canonicalize value)
    :else value))

(defn- edn-text
  [value]
  (with-out-str (pprint/pprint (canonicalize value))))

(defn- command-output
  [directory command]
  (str/trim (:output (process/run! {:directory directory :command command}))))

(defn- checkout-path
  [root target]
  (paths/resolve-path root "research" (case target :pkl "pkl" :pdfcube "pdfbox")))

(defn- clean-checkout!
  [checkout]
  (let [status (command-output checkout
                               ["git" "status" "--porcelain"
                                "--untracked-files=no"])]
    (when-not (str/blank? status)
      (fail! "Re-baseline observation requires a clean tracked upstream checkout"
             {:kind :dirty-rebaseline-upstream
              :checkout (str checkout)
              :status status})))
  checkout)

(defn- matching-value
  [file pattern subject]
  (let [text (Files/readString (paths/absolute file) StandardCharsets/UTF_8)
        match (re-find pattern text)
        value (second match)]
    (when (str/blank? value)
      (fail! (str "Could not observe " subject)
             {:kind :missing-rebaseline-observation
              :subject subject
              :file (str file)}))
    value))

(defn- observed-license
  [root record]
  (let [source
        (some
         (fn [entry]
           (when (= :license (:kind entry)) (:source entry)))
         (mapcat val (:legal-sets record)))
        text (when source
               (Files/readString (paths/resolve-path root source)
                                 StandardCharsets/UTF_8))]
    (cond
      (and text
           (re-find #"(?s)Apache License\s+Version 2[.]0" text))
      "Apache-2.0"

      text
      (str "LicenseRef-Review-Required-"
           (subs (util/sha256-text text) 0 12))

      :else
      (fail! "Target baseline has no observable upstream license source"
             {:kind :missing-rebaseline-license-source
              :target (:target record)}))))

(defn- observed-upstream
  [root target record]
  (let [checkout (clean-checkout! (checkout-path root target))
        revision (command-output checkout ["git" "rev-parse" "HEAD"])
        repository (command-output checkout
                                   ["git" "remote" "get-url" "origin"])
        version
        (case target
          :pkl
          (matching-value
           (paths/resolve-path checkout "gradle.properties")
           #"(?m)^version=([^\s]+)$"
           "Pkl upstream version")

          :pdfcube
          (matching-value
           (paths/resolve-path checkout "pom.xml")
           #"(?s)<artifactId>pdfbox-parent</artifactId>\s*<version>([^<]+)</version>"
           "PDFBox upstream version"))]
    (assoc (:upstream record)
           :version version
           :revision revision
           :repository repository
           :license (observed-license root record))))

(defn- gradle-discovery-options
  [root evidence profile]
  (merge
   {:workspace-root root
    :manifest (paths/resolve-path evidence
                                  (str (:profile profile) "-inputs.tsv"))}
   (select-keys profile
                [:project-root :gradle-wrapper :gradle-project
                 :gradle-java-major])))

(defn- observe-pkl-inputs!
  [root evidence record]
  (into
   (sorted-map)
   (for [[profile-key profile-contract] (:profiles record)
         :let [profile (harness/read-profile root (:profile profile-contract))
               input (harness/discover-project!
                      (gradle-discovery-options root evidence profile))]]
     [profile-key input])))

(defn- coordinate-with-version
  [coordinate version]
  (let [parts (str/split coordinate #":" -1)]
    (when-not (= 3 (count parts))
      (fail! "Baseline source project ID is not a Maven GAV"
             {:kind :invalid-rebaseline-project-id
              :project-id coordinate}))
    (str/join ":" (assoc (vec parts) 2 version))))

(defn- observe-pdfcube-inputs!
  [root evidence record version]
  (let [profiles (:profiles record)
        selectors (->> profiles vals (map :maven-selector) distinct sort vec)
        reactor
        (maven/discover-reactor!
         {:workspace-root root
          :project-root "research/pdfbox"
          :selected-projects selectors
          :manifest (paths/resolve-path evidence "pdfcube-reactor-inputs.tsv")})]
    (into
     (sorted-map)
     (for [[profile-key profile] profiles
           :let [project-id
                 (coordinate-with-version (:source-project-id profile) version)]]
       [profile-key (maven/project-by-id! reactor project-id)]))))

(defn- source-counts
  [input]
  {:ordinary (count (:production-sources input))
   :generated (count (:generated-production-sources input))})

(defn- one-java-language-version
  [target inputs]
  (let [versions (->> inputs vals (map #(get-in % [:java-toolchain :release]))
                      distinct sort vec)]
    (when-not (= 1 (count versions))
      (fail! "Target profiles disagree on their Java language version"
             {:kind :ambiguous-rebaseline-java-language-version
              :target target
              :versions versions}))
    (first versions)))

(defn- observe-pdfcube-public-counts!
  [root inputs]
  (let [strategy (java-library/public-surface-strategy)
        empty-surface ((:read! strategy) root {})]
    (into
     (sorted-map)
     (for [[profile-key input] inputs
           :let [model (spoon/build-resolved-model! root input)
                 surface ((:validate-selected! strategy)
                          root empty-surface model)]]
       [profile-key (count (:rows surface))]))))

(defn- observe-pkl-public-counts!
  [root evidence record]
  (let [by-module
        (pkl-public-api/observe-public-contract-counts!
         root (paths/resolve-path evidence "pkl-upstream-public-api.tsv"))]
    (into
     (sorted-map)
     (for [[profile-key profile] (:profiles record)]
       [profile-key (get by-module (:source-module profile))]))))

(defn- observed-artifact-hashes
  [target record inputs]
  (let [required (set (keys (:artifacts record)))
        observations
        (->> inputs
             vals
             (mapcat :classpath-artifacts)
             (filter #(contains? required (:coordinate %)))
             (group-by :coordinate))]
    (into
     (sorted-map)
     (for [coordinate (sort required)
           :let [hashes
                 (->> (get observations coordinate)
                      (map #(util/sha256-file (:path %)))
                      distinct
                      sort
                      vec)]]
       (do
         (when-not (= 1 (count hashes))
           (fail! "Could not observe exactly one hash for a baseline artifact"
                  {:kind :ambiguous-rebaseline-artifact
                   :target target
                   :coordinate coordinate
                   :hashes hashes}))
         [coordinate (first hashes)])))))

(defn- observed-legal-sets
  [root target record]
  (into
   (sorted-map)
   (for [[legal-set entries] (:legal-sets record)]
     [legal-set
      (mapv
       (fn [{:keys [kind source] :as entry}]
         (let [path (paths/resolve-path root source)
               _ (when-not (paths/regular-file? path)
                   (fail! "Baseline legal source is missing"
                          {:kind :missing-rebaseline-legal-source
                           :target target :source source}))
               source-sha256 (util/sha256-file path)
               packaged-sha256
               (if (and (= :pkl target) (= :notice kind))
                 (util/sha256-text
                  (str (Files/readString path StandardCharsets/UTF_8)
                       (or (:notice-appendix record) "")))
                 source-sha256)]
           (cond-> (assoc entry :sha256 packaged-sha256)
             (contains? entry :source-sha256)
             (assoc :source-sha256 source-sha256))))
       entries)])))

(defn- replace-version-prefix
  [value old-version new-version]
  (if (and (string? value) (str/starts-with? value old-version))
    (str new-version (subs value (count old-version)))
    value))

(defn- observed-packages
  [target record version]
  (if-not (= :pdfcube target)
    (:packages record)
    (let [old-version (get-in record [:upstream :version])]
      (into
       (sorted-map)
       (map
        (fn [[package-id package]]
          [package-id
           (-> package
               (update :version replace-version-prefix old-version version)
               (update :assembly-version
                       replace-version-prefix old-version version))]))
       (:packages record)))))

(defn- observed-profiles
  [target record inputs public-counts version]
  (let [old-version (get-in record [:upstream :version])]
    (into
     (sorted-map)
     (for [[profile-key profile] (:profiles record)
           :let [input (get inputs profile-key)]]
       [profile-key
        (cond->
         (assoc profile
                :source-counts (source-counts input)
                :public-contract-rows (get public-counts profile-key))
          (= :pdfcube target)
          (-> (update :source-project-id
                      replace-version-prefix old-version version)
              (update :source-project-dependencies
                      (fn [dependencies]
                        (mapv #(replace-version-prefix
                                % old-version version)
                              dependencies)))))]))))

(defn observe-baseline!
  "Computes a complete candidate record from the checked-out upstream target,
  resolved project inputs, public contracts, artifacts, and legal sources."
  [workspace-root target]
  (let [root (paths/absolute workspace-root)
        target (baseline/target-key target)
        record (baseline/read-baseline root target)
        evidence (doto (paths/resolve-path root "target" "rebaseline"
                                           (name target))
                   (Files/createDirectories (make-array FileAttribute 0)))
        upstream (observed-upstream root target record)
        version (:version upstream)
        inputs
        (case target
          :pkl (observe-pkl-inputs! root evidence record)
          :pdfcube (observe-pdfcube-inputs! root evidence record version))
        public-counts
        (case target
          :pkl (observe-pkl-public-counts! root evidence record)
          :pdfcube (observe-pdfcube-public-counts! root inputs))
        candidate
        (-> record
            (assoc :upstream
                   (assoc upstream :java-language-version
                          (one-java-language-version target inputs)))
            (assoc :artifacts (observed-artifact-hashes target record inputs))
            (assoc :legal-sets (observed-legal-sets root target record))
            (assoc :packages (observed-packages target record version))
            (assoc :profiles
                   (observed-profiles target record inputs
                                      public-counts version)))]
    (baseline/validate! target candidate)))

(def ^:private missing ::missing)

(defn- leaves
  ([value] (leaves [] value))
  ([path value]
   (cond
     (map? value)
     (if (seq value)
       (mapcat (fn [[key item]] (leaves (conj path key) item)) value)
       [[path value]])

     (vector? value)
     (if (seq value)
       (mapcat (fn [[index item]] (leaves (conj path index) item))
               (map-indexed vector value))
       [[path value]])

     :else [[path value]])))

(defn full-delta
  [current candidate]
  (let [before (into {} (leaves current))
        after (into {} (leaves candidate))
        paths (sort-by pr-str (into (set (keys before)) (keys after)))]
    (mapv
     (fn [path]
       {:path path
        :before (get before path missing)
        :after (get after path missing)})
     (filter #(not= (get before % missing) (get after % missing)) paths))))

(def ^:private legal-review-path-prefixes
  [[:upstream :license]
   [:legal-sets]
   [:notice-appendix]])

(defn- path-prefix?
  [prefix path]
  (and (<= (count prefix) (count path))
       (= prefix (subvec path 0 (count prefix)))))

(defn- legal-review
  [delta]
  (let [legal-delta
        (filterv
         (fn [{:keys [path]}]
           (some #(path-prefix? % path) legal-review-path-prefixes))
         delta)]
    {:required? (boolean (seq legal-delta))
     :changed-fields (count legal-delta)
     :delta legal-delta}))

(defn approval-token
  [target current candidate]
  (util/sha256-text
   (pr-str
    (canonicalize
     {:schema-version 1
      :operation :replace-reviewed-target-baseline
      :target (baseline/target-key target)
      :current current
      :candidate candidate}))))

(defn preview!
  ([workspace-root target]
   (preview! workspace-root target observe-baseline!))
  ([workspace-root target observe-fn]
   (let [root (paths/absolute workspace-root)
         target (baseline/target-key target)
         current (baseline/read-baseline root target)
         candidate (baseline/validate! target
                                       (observe-fn root target))
         delta (full-delta current candidate)
         legal-review (legal-review delta)
         token (approval-token target current candidate)]
     (cond->
      {:schema-version 1
       :operation :review-target-rebaseline
       :target target
       :baseline-file (get baseline/baseline-files target)
       :current current
       :candidate candidate
       :delta delta
       :changed-fields (count delta)
       :approval-required? (boolean (seq delta))
       :approval-token token
       :legal-review legal-review
       :protected-contract-files protected-contract-files
       :protected-contract-action :unchanged}
       (seq delta)
       (assoc :approval-command
              (str "clojure -M:run rebaseline " (name target)
                   " --approve " token))))))

(defn- protected-hashes
  [root]
  (into
   (sorted-map)
   (for [relative protected-contract-files
         :let [file (paths/resolve-path root relative)]]
     (do
       (when-not (paths/regular-file? file)
         (fail! "Protected product contract is missing"
                {:kind :missing-protected-product-contract
                 :path relative}))
       [relative (util/sha256-file file)]))))

(defn- write-baseline-atomically!
  [file candidate]
  (let [parent (.getParent ^Path file)
        temporary (Files/createTempFile
                   parent ".rebaseline-" ".edn"
                   (make-array FileAttribute 0))]
    (try
      (util/write-text! temporary (edn-text candidate))
      (try
        (Files/move
         temporary file
         (into-array CopyOption
                     [StandardCopyOption/ATOMIC_MOVE
                      StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move
           temporary file
           (into-array CopyOption
                       [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (Files/deleteIfExists temporary)))))

(defn approve!
  ([workspace-root target supplied-token]
   (approve! workspace-root target supplied-token observe-baseline!))
  ([workspace-root target supplied-token observe-fn]
   (let [root (paths/absolute workspace-root)
         target (baseline/target-key target)
         protected-before (protected-hashes root)
         preview (preview! root target observe-fn)
         expected-token (:approval-token preview)]
     (when (empty? (:delta preview))
       (fail! "The observed target baseline has no changes to approve"
              {:kind :empty-rebaseline-delta :target target}))
     (when-not (= expected-token supplied-token)
       (fail! "Re-baseline approval token does not match the current full delta"
              {:kind :rebaseline-approval-token-mismatch
               :target target
               :expected expected-token
               :supplied supplied-token}))
     (let [file (baseline/baseline-path root target)]
       (when-not (= protected-before (protected-hashes root))
         (fail! "A protected product contract changed during re-baseline observation"
                {:kind :protected-product-contract-changed-during-observation
                 :before protected-before
                 :after (protected-hashes root)}))
       (write-baseline-atomically! file (:candidate preview))
       (baseline/read-baseline root target)
       (let [protected-after (protected-hashes root)]
         (when-not (= protected-before protected-after)
           (fail! "A protected product contract changed during re-baseline"
                  {:kind :protected-product-contract-changed
                   :before protected-before
                   :after protected-after})))
       (assoc (select-keys preview
                           [:schema-version :target :baseline-file
                            :changed-fields :delta :legal-review])
              :operation :approved-target-rebaseline
              :approval-token supplied-token
              :protected-contract-action :unchanged)))))

(defn run!
  [workspace-root args]
  (let [[target flag token & extra] args
        result
        (cond
          (or (nil? target) (seq extra)
              (and flag (not= "--approve" flag))
              (and (= "--approve" flag) (str/blank? token)))
          (fail! "Usage: rebaseline <pkl|pdfcube> [--approve <token>]"
                 {:kind :invalid-rebaseline-command
                  :arguments (vec args)})

          flag (approve! workspace-root target token)
          :else (preview! workspace-root target))]
    (pprint/pprint (canonicalize result))
    result))
