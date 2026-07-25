(ns dripsharp.complete-core-closure-fixture
  (:require [clojure.edn :as edn]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.project :as project]
            [dripsharp.public-api-contract :as public-api]
            [dripsharp.spoon :as spoon])
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
                                    root "config"
                                    "pkl-core-value-model.edn"))))
        destination (java-project/read-configuration
                     root (:destination-config configuration))
        status-before (research-status root)
        manifest (Files/createTempFile "dripsharp-pkl-core-inputs" ".tsv"
                                       (make-array FileAttribute 0))
        source-project
        (project/verify-checkout!
         {:workspace-root root
          :project-root (:project-root configuration)
          :revision (:revision configuration)})
        discovery (project/discover-main!
                   {:workspace-root root
                    :manifest manifest
                    :project-root (:project-root configuration)
                    :gradle-project (:gradle-project configuration)})
        surface (public-api/generation-surface!
                 root (dissoc (:public-surface destination) :strategy))
        seeds (harness/merge-seeds (:seeds configuration) (:seeds surface))
        frontend (spoon/build-frontend-model! root discovery)
        first-closure (concurrency/call-with-executor
                       {:worker-count 1}
                       #(spoon/select-resolved-closure!
                         frontend seeds))
        second-closure (concurrency/call-with-executor
                        {:worker-count 22}
                        #(spoon/select-resolved-closure!
                          frontend seeds))
        first-surface (public-api/validate-selected-surface! root surface first-closure)
        second-surface (public-api/validate-selected-surface! root surface second-closure)
        status-after (research-status root)]
    {:root root
     :configuration (assoc configuration :seeds seeds)
     :surface first-surface
     :second-surface second-surface
     :source-project source-project
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
