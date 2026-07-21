(ns vibeformer.paths-test
  (:require [clojure.test :refer [deftest is]]
            [vibeformer.paths :as paths])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(deftest workspace-discovery-does-not-require-the-pkl-checkout
  (let [root (Files/createTempDirectory "vibeformer-non-pkl-workspace"
                                        (make-array FileAttribute 0))
        nested (paths/resolve-path root "vibeformer" "validation" "rawhttp-core")
        deps (paths/resolve-path root "vibeformer" "deps.edn")]
    (Files/createDirectories (paths/resolve-path root ".git")
                             (make-array FileAttribute 0))
    (Files/createDirectories nested (make-array FileAttribute 0))
    (Files/writeString deps "{}\n" (make-array OpenOption 0))
    (is (= root (paths/workspace-root nested)))
    (is (not (paths/exists? (paths/resolve-path root "research" "pkl"))))))
