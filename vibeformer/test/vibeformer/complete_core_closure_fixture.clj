(ns vibeformer.complete-core-closure-fixture
  (:require [clojure.edn :as edn]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process]
            [vibeformer.project :as project]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- research-status
  [root]
  (:output
   (process/run! {:command ["git" "status" "--porcelain" "--untracked-files=no"]
                  :directory (paths/resolve-path root "research" "pkl")})))

(defn- resolve-complete-core-closure
  []
  (let [root (paths/workspace-root)
        configuration (edn/read-string
                       (slurp (str (paths/resolve-path
                                    root "vibeformer" "config"
                                    "pkl-core-value-model.edn"))))
        status-before (research-status root)
        manifest (Files/createTempFile "vibeformer-pkl-core-inputs" ".tsv"
                                       (make-array FileAttribute 0))
        submodule (project/verify-submodule! {:workspace-root root})
        discovery (project/discover-main!
                   {:workspace-root root
                    :manifest manifest
                    :gradle-project (:gradle-project configuration)})
        frontend (spoon/build-frontend-model! root discovery)
        first-closure (spoon/select-resolved-closure!
                       frontend (:seeds configuration))
        second-closure (spoon/select-resolved-closure!
                        frontend (:seeds configuration))
        status-after (research-status root)]
    {:root root
     :configuration configuration
     :submodule submodule
     :discovery discovery
     :frontend frontend
     :first first-closure
     :second second-closure
     :status-before status-before
     :status-after status-after}))

(defonce ^:private complete-core-closure
  (delay (resolve-complete-core-closure)))

(defn models
  "Returns two independently selected closures over one complete live pkl-core
  Spoon frontend. The shared frontend avoids rebuilding 723 sources merely to
  prove deterministic closure traversal."
  []
  @complete-core-closure)
