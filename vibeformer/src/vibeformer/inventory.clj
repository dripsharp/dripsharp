(ns vibeformer.inventory
  (:require [datomic.client.api :as d]))

(def unsupported-status :feature.status/unsupported)

(defn- lang-matches? [langs lang]
  (or (nil? langs)
      (contains? langs lang)))

(defn- normalize-opts [opts]
  (cond
    (nil? opts) {}
    (map? opts) opts
    :else (throw (ex-info "Inventory options must be a map." {:opts opts}))))

(defn feature-counts
  "Returns feature counts grouped by source language, feature kind, and status."
  ([db]
   (feature-counts db {}))
  ([db opts]
   (let [{:keys [langs]} (normalize-opts opts)]
     (->> (d/q '[:find ?lang ?kind ?status (count ?feature)
                 :where
                 [?feature :feature/lang ?lang]
                 [?feature :feature/kind ?kind]
                 [?feature :feature/status ?status]]
               db)
          (keep (fn [[lang kind status count]]
                  (when (lang-matches? langs lang)
                    {:lang lang
                     :kind kind
                     :status status
                     :count count})))
          (sort-by (juxt :lang :kind :status))
          vec))))

(defn unsupported-rankings
  "Ranks unsupported features by occurrence count and affected file count."
  ([db]
   (unsupported-rankings db {}))
  ([db opts]
   (let [{:keys [langs]} (normalize-opts opts)]
     (->> (d/q '[:find ?lang ?kind (count ?feature) (count-distinct ?file)
                 :where
                 [?feature :feature/status :feature.status/unsupported]
                 [?feature :feature/lang ?lang]
                 [?feature :feature/kind ?kind]
                 [?feature :feature/node ?node]
                 [?node :node/file ?file]]
               db)
          (keep (fn [[lang kind count file-count]]
                  (when (lang-matches? langs lang)
                    {:lang lang
                     :kind kind
                     :count count
                     :file-count file-count})))
          (sort-by (juxt (comp - :count)
                         (comp - :file-count)
                         :lang
                         :kind))
          vec))))

(defn unsupported-by-file
  "Returns unsupported feature counts grouped by source file."
  ([db]
   (unsupported-by-file db {}))
  ([db opts]
   (let [{:keys [langs]} (normalize-opts opts)]
     (->> (d/q '[:find ?file-id ?path ?lang ?kind (count ?feature)
                 :where
                 [?feature :feature/status :feature.status/unsupported]
                 [?feature :feature/kind ?kind]
                 [?feature :feature/node ?node]
                 [?node :node/file ?file]
                 [?file :file/id ?file-id]
                 [?file :file/path ?path]
                 [?file :file/lang ?lang]]
               db)
          (keep (fn [[file-id path lang kind count]]
                  (when (lang-matches? langs lang)
                    {:file/id file-id
                     :file/path path
                     :file/lang lang
                     :kind kind
                     :count count})))
          (group-by (juxt :file/id :file/path :file/lang))
          (map (fn [[[_file-id _path _lang] rows]]
                 (let [{:file/keys [id path lang]} (first rows)
                       features (->> rows
                                     (map #(select-keys % [:kind :count]))
                                     (sort-by :kind)
                                     vec)]
                   {:file/id id
                    :file/path path
                    :file/lang lang
                    :unsupported-count (reduce + (map :count features))
                    :features features})))
          (sort-by (juxt :file/path :file/id))
          vec))))

(defn files-without-unsupported
  "Returns source files that have no unsupported feature facts."
  ([db]
   (files-without-unsupported db {}))
  ([db opts]
   (let [{:keys [langs]} (normalize-opts opts)]
     (->> (d/q '[:find ?file-id ?path ?lang
                 :where
                 [?file :file/id ?file-id]
                 [?file :file/path ?path]
                 [?file :file/lang ?lang]
                 (not-join [?file]
                   [?node :node/file ?file]
                   [?feature :feature/node ?node]
                   [?feature :feature/status :feature.status/unsupported])]
               db)
          (keep (fn [[file-id path lang]]
                  (when (lang-matches? langs lang)
                    {:file/id file-id
                     :file/path path
                     :file/lang lang})))
          (sort-by (juxt :file/path :file/id))
          vec))))

(defn summary
  "Builds a task-friendly feature inventory report map for a Datomic db value."
  ([db]
   (summary db {}))
  ([db opts]
   {:feature-counts (feature-counts db opts)
    :unsupported-rankings (unsupported-rankings db opts)
    :unsupported-by-file (unsupported-by-file db opts)
    :files-without-unsupported (files-without-unsupported db opts)}))
