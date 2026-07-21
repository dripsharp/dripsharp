(ns vibeformer.complete-parser-fixture
  (:require [vibeformer.concurrency :as concurrency]
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

(defn- resolve-complete-parser-twice
  []
  (let [root (paths/workspace-root)
        status-before (research-status root)
        manifest (Files/createTempFile "vibeformer-main-inputs" ".tsv"
                                       (make-array FileAttribute 0))
        submodule (project/verify-submodule! {:workspace-root root})
        discovery (project/discover-main! {:workspace-root root
                                           :project-root "research/pkl"
                                           :gradle-project ":pkl-parser"
                                           :manifest manifest})
        first-model (concurrency/call-with-executor
                     {:worker-count 1}
                     #(spoon/build-resolved-model! root discovery))
        second-model (concurrency/call-with-executor
                      {:worker-count 4}
                      #(spoon/build-resolved-model! root discovery))
        status-after (research-status root)]
    {:root root
     :submodule submodule
     :discovery discovery
     :first first-model
     :second second-model
     :status-before status-before
     :status-after status-after}))

(defonce ^:private complete-parser
  (delay (resolve-complete-parser-twice)))

(defn models
  "Returns deterministic live models for the complete resolved pkl-parser test
  fixture.  Test namespaces share this delay so a full test run resolves the
  expensive upstream model only twice."
  []
  @complete-parser)
