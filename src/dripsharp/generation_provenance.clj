(ns dripsharp.generation-provenance
  "Fail-closed portable source provenance for generated accounting artifacts."
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Path]))

(defn- fail! [message data]
  (throw
   (ex-info message
            (assoc data :kind :invalid-generation-provenance))))

(defn- real-directory! [subject value]
  (let [path (paths/absolute value)]
    (when-not (paths/directory? path)
      (fail! (str subject " is missing or is not a directory")
             {:reason :invalid-authorized-source-root
              :subject subject
              :path (str path)}))
    (.toRealPath ^Path path paths/no-links)))

(defn- context [workspace-root source-roots]
  {:workspace (real-directory! "Workspace root" workspace-root)
   :source-roots
   (mapv (fn [index root]
           {:index index
            :path (real-directory! (str "Source root " index) root)})
         (range)
         source-roots)})

(defn- normalized-portable-relative-path? [value]
  (let [path (paths/path value)]
    (and (string? value)
         (not (str/blank? value))
         (not (.isAbsolute path))
         (= value (str/replace (str (.normalize path)) "\\" "/"))
         (every? #(not (#{"" "." ".."} (str %)))
                 (iterator-seq (.iterator path))))))

(defn- portable-file* [{:keys [^Path workspace source-roots]} value]
  (when-not (or (string? value) (instance? Path value))
    (fail! "Generated source provenance is not a path"
           {:reason :invalid-source-file :value value}))
  (let [configured (paths/path value)
        file (paths/absolute
              (if (.isAbsolute configured)
                configured
                (paths/resolve-path workspace configured)))]
    (when-not (paths/regular-file? file)
      (fail! "Generated source provenance does not name a regular file"
             {:reason :missing-source-file :path (str file)}))
    (let [file (.toRealPath ^Path file paths/no-links)
          workspace-relative
          (when (.startsWith file workspace)
            (util/portable-path workspace file))
          matching-roots
          (->> source-roots
               (filter #(.startsWith file ^Path (:path %)))
               (sort-by #(.getNameCount ^Path (:path %)) >)
               vec)
          deepest-count
          (when-let [^Path path (:path (first matching-roots))]
            (.getNameCount path))
          deepest-roots
          (when deepest-count
            (filterv #(= deepest-count (.getNameCount ^Path (:path %)))
                     matching-roots))
          portable
          (or workspace-relative
              (when (= 1 (count deepest-roots))
                (let [{:keys [index] :as entry} (first deepest-roots)
                      ^Path source-root (:path entry)]
                  (str "source-roots/" index "/"
                       (util/portable-path source-root file)))))]
      (when-not portable
        (fail! "Generated source provenance is outside the authorized roots"
               {:reason (if (< 1 (count deepest-roots))
                          :ambiguous-source-root
                          :source-file-outside-authorized-roots)
                :workspace (str workspace)
                :source-roots (mapv (comp str :path) source-roots)
                :path (str file)}))
      (when-not (normalized-portable-relative-path? portable)
        (fail! "Generated source provenance is not a normalized portable path"
               {:reason :invalid-portable-source-file
                :path portable
                :source (str file)}))
      portable)))

(defn- portable-location* [ctx location]
  (when-not (and (map? location) (contains? location :file))
    (fail! "Generated source provenance lacks an exact file location"
           {:reason :missing-source-location :location location}))
  (update location :file (partial portable-file* ctx)))

(defn portable-annotation-decisions!
  "Normalizes every serialized annotation-decision source location."
  [workspace-root source-roots artifact]
  (let [ctx (context workspace-root source-roots)]
    (update artifact :decisions
            (fn [decisions]
              (mapv #(update-in % [:source :location]
                                (partial portable-location* ctx))
                    decisions)))))

(defn portable-generation-manifest!
  "Normalizes every mechanically generated artifact source location."
  [workspace-root source-roots artifact]
  (let [ctx (context workspace-root source-roots)]
    (update artifact :artifacts
            (fn [artifacts]
              (mapv #(update-in % [:source :file]
                                (partial portable-file* ctx))
                    artifacts)))))

(defn- portable-generated-row* [ctx row]
  (let [location (get-in row [:generated :source :location])
        source-file (:file location)
        portable-location (portable-location* ctx location)
        portable-file (:file portable-location)
        id (get-in row [:generated :id])
        id-occurrences
        (when (and (string? id) (string? source-file))
          (dec
           (count
            (str/split id
                       (re-pattern
                        (java.util.regex.Pattern/quote source-file))
                       -1))))]
    (when-not (and (string? id) (= 1 id-occurrences))
      (fail! "Generated declaration identity does not contain its exact source file once"
             {:reason :invalid-generated-declaration-id
              :id id
              :source-file source-file}))
    (-> row
        (assoc-in [:generated :source :location] portable-location)
        (assoc-in [:generated :id]
                  (str/replace id source-file portable-file)))))

(defn portable-public-metadata!
  "Normalizes public-surface source locations, source-derived ids, and the
  optional retained compiled-surface contract path."
  [workspace-root source-roots artifact]
  (let [ctx (context workspace-root source-roots)]
    (cond->
     (update artifact :rows
             (fn [rows]
               (mapv (partial portable-generated-row* ctx) rows)))
      (:compiled-contract-file artifact)
      (update :compiled-contract-file (partial portable-file* ctx)))))
