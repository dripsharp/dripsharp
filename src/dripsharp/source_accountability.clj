(ns dripsharp.source-accountability
  "Pure source-to-emission accountability summaries."
  (:require [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Path]))

(defn summarize
  "Accounts for every selected production source.

  Each ordinary source must own at least one emitted top-level type.
  package-info.java is represented by its package-nullability metadata."
  [workspace-root diagnostics declarations files]
  (let [root (paths/absolute workspace-root)
        by-file (group-by #(get-in % [:source :location :file]) diagnostics)
        outputs-by-file
        (group-by #(get-in % [:source :location :file])
                  (filter #(and (= :type (:kind %)) (nil? (:owner %)))
                          declarations))]
    (mapv
     (fn [source]
       (let [canonical (.getCanonicalPath (.toFile ^Path source))
             types (get outputs-by-file canonical)
             package-info? (= "package-info.java"
                              (str (.getFileName ^Path source)))]
         (when-not (or (seq types) package-info?)
           (throw
            (ex-info
             "Production source has no emitted declaration or package mapping"
             {:kind :unaccounted-production-source :path canonical})))
         {:source (util/portable-or-absolute-path root (paths/absolute source))
          :strategy (if package-info?
                      :package-nullability-metadata
                      :generated-csharp)
          :top-level-declarations (mapv :name types)
          :hard-failures (count (get by-file canonical))}))
     (sort-by str files))))
