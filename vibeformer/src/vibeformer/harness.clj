(ns vibeformer.harness
  (:require [clojure.string :as str]
            [vibeformer.paths :as paths]
            [vibeformer.project :as project]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file FileVisitOption Files Path]))

(defn clean-directory!
  "Deletes a directory tree, then recreates the empty directory."
  [directory]
  (let [directory (paths/absolute directory)]
    (when (paths/exists? directory)
      (with-open [entries (Files/walk directory (make-array FileVisitOption 0))]
        (doseq [^Path entry (->> (.toArray entries)
                                 (map #(cast Path %))
                                 (sort-by #(.getNameCount ^Path %) >))]
          (Files/delete entry))))
    (Files/createDirectories directory (make-array java.nio.file.attribute.FileAttribute 0))
    directory))

(defn- portable-path
  [^Path root ^Path input]
  (let [input (.normalize input)]
    (-> (if (.startsWith input root)
          (str (.relativize root input))
          (str input))
        (str/replace "\\" "/"))))

(defn configuration
  "Builds the deterministic, serializable configuration used by later stages."
  [workspace-root revision discovery]
  (let [root (paths/absolute workspace-root)
        render-many #(->> % (map (partial portable-path root)) sort vec)]
    {:schema-version 1
     :project :pkl-parser
     :submodule {:path "research/pkl" :revision revision}
     :toolchain {:java-home (portable-path root (:java-home discovery))
                 :java-release (:java-release discovery)
                 :preview-features (:preview-features discovery)}
     :production {:java-sources (render-many (:java-sources discovery))
                  :resources (render-many (:resources discovery))
                  :classpath (render-many (:classpath discovery))}}))

(defn generate!
  "Cleans disposable output and resolves the complete pkl-parser production inputs."
  ([] (generate! {}))
  ([{:keys [workspace-root verify-submodule-fn discover-main-fn
            build-resolved-model-fn]
     :or {verify-submodule-fn project/verify-submodule!
          discover-main-fn project/discover-main!
          build-resolved-model-fn spoon/build-resolved-model!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         target (clean-directory! (paths/resolve-path root "vibeformer" "target"))
         submodule (verify-submodule-fn {:workspace-root root})
         manifest (paths/resolve-path target "gradle-main-inputs.tsv")
         discovery (discover-main-fn {:workspace-root root :manifest manifest})
         config (configuration root (:revision submodule) discovery)
         java-model (build-resolved-model-fn root discovery)
         config-file (paths/resolve-path target "generation-config.edn")
         source-count (count (get-in config [:production :java-sources]))
         resources (get-in config [:production :resources])]
     (spit (str config-file) (str (pr-str config) "\n"))
     (println (format "Prepared pkl-parser: %d production Java files, %d production resource%s, %d classpath entries."
                      source-count
                      (count resources)
                      (if (= 1 (count resources)) "" "s")
                      (count (get-in config [:production :classpath]))))
     (println "Production resources:" (if (seq resources) (str/join ", " resources) "none"))
     (println "Resolved Spoon model:" (spoon/summary-line java-model))
     (println "Disposable configuration:" (portable-path root config-file))
     (assoc config :java-model java-model))))
