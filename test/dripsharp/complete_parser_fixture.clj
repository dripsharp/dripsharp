(ns dripsharp.complete-parser-fixture
  (:require [dripsharp.concurrency :as concurrency]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.project :as project]
            [dripsharp.spoon :as spoon])
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
        manifest (Files/createTempFile "dripsharp-main-inputs" ".tsv"
                                       (make-array FileAttribute 0))
        source-project
        (project/verify-checkout!
         {:workspace-root root
          :project-root "research/pkl"
          :revision "f7cac257ade5775c1dfc255f4fda2eacc296e9d0"})
        discovery (project/discover-main! {:workspace-root root
                                           :project-root "research/pkl"
                                           :gradle-project ":pkl-parser"
                                           :manifest manifest})
        first-model (concurrency/call-with-executor
                     {:worker-count 1}
                     #(spoon/build-resolved-model! root discovery))
        second-model (concurrency/call-with-executor
                      {:worker-count 22}
                      #(spoon/build-resolved-model! root discovery))
        status-after (research-status root)]
    {:root root
     :source-project source-project
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
