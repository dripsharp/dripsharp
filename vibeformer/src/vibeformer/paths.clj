(ns vibeformer.paths
  (:import [java.nio.file Files LinkOption Path Paths]))

(def no-links (make-array LinkOption 0))

(defn path
  ^Path [value]
  (if (instance? Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn absolute
  ^Path [value]
  (-> value path .toAbsolutePath .normalize))

(defn exists?
  [value]
  (Files/exists (path value) no-links))

(defn regular-file?
  [value]
  (Files/isRegularFile (path value) no-links))

(defn directory?
  [value]
  (Files/isDirectory (path value) no-links))

(defn resolve-path
  ^Path [root & children]
  (reduce #(.resolve ^Path %1 (str %2)) (path root) children))

(defn workspace-root
  "Finds the checkout root without embedding a developer-local absolute path."
  ([] (workspace-root (System/getProperty "user.dir")))
  ([start]
   (or
    (some (fn [^Path candidate]
            (when (and (exists? (resolve-path candidate ".git"))
                       (regular-file? (resolve-path candidate "vibeformer" "deps.edn")))
              candidate))
          (take-while some? (iterate #(.getParent ^Path %) (absolute start))))
    (throw (ex-info
            "Could not find the checkout root containing vibeformer"
            {:kind :workspace-not-found :start (str (absolute start))})))))
