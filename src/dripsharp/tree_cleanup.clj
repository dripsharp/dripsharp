(ns dripsharp.tree-cleanup
  "Bounded, deterministic deletion of disposable generated directory trees."
  (:require [dripsharp.paths :as paths])
  (:import [java.nio.file DirectoryNotEmptyException FileSystemException
            FileVisitOption Files LinkOption NoSuchFileException Path]))

(def ^:private default-max-passes 16)
(def ^:private default-retry-delay-ms 25)

(def ^:private no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- existing-no-follow?
  [^Path path]
  (Files/exists path no-follow))

(defn- exception-path
  [error]
  (when (instance? FileSystemException error)
    (.getFile ^FileSystemException error)))

(defn- snapshot
  [^Path root]
  (try
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (->> (.toArray entries)
           (map #(cast Path %))
           (sort-by (fn [^Path entry]
                      [(- (.getNameCount entry)) (str entry)]))
           vec))
    (catch NoSuchFileException _
      [])))

(defn- checked-entry
  [^Path root ^Path entry]
  (let [entry (paths/absolute entry)]
    (when-not (.startsWith entry root)
      (throw
       (ex-info "Tree cleanup traversal escaped its requested root"
                {:kind :tree-cleanup-failed
                 :reason :cleanup-path-escape
                 :root (str root)
                 :path (str entry)})))
    entry))

(defn- delete-pass!
  [^Path root entries pass on-retry-fn]
  (reduce
   (fn [last-error ^Path raw-entry]
     (let [entry (checked-entry root raw-entry)]
       (try
         (Files/delete entry)
         last-error
         (catch NoSuchFileException _
           last-error)
         (catch DirectoryNotEmptyException error
           (when on-retry-fn
             (on-retry-fn {:root root
                           :pass pass
                           :path entry
                           :error error}))
           (or last-error error)))))
   nil
   entries))

(defn delete-tree!
  "Deletes root without following symbolic links.

  A filesystem producer can add a child after one traversal has been
  snapshotted. DirectoryNotEmptyException is therefore retried with a fresh,
  deterministically ordered traversal. Retries are bounded; all other I/O
  failures remain fail-closed. The options arity exposes synchronization hooks
  only for focused race regression tests."
  ([root]
   (delete-tree! root {}))
  ([root {:keys [max-passes retry-delay-ms after-snapshot-fn on-retry-fn]
          :or {max-passes default-max-passes
               retry-delay-ms default-retry-delay-ms}}]
   (when-not (pos-int? max-passes)
     (throw
      (IllegalArgumentException. "Tree cleanup max-passes must be positive")))
   (when-not (and (integer? retry-delay-ms) (not (neg? retry-delay-ms)))
     (throw
      (IllegalArgumentException.
       "Tree cleanup retry-delay-ms must be a nonnegative integer")))
   (let [root (paths/absolute root)]
     (loop [pass 1
            previous-error nil]
       (if-not (existing-no-follow? root)
         root
         (let [entries (snapshot root)
               _ (when after-snapshot-fn
                   (after-snapshot-fn {:root root
                                       :pass pass
                                       :entries entries}))
               pass-error (delete-pass! root entries pass on-retry-fn)
               last-error (or pass-error previous-error)]
           (cond
             (not (existing-no-follow? root))
             root

             (< pass max-passes)
             (do
               (when (pos? retry-delay-ms)
                 (Thread/sleep (long retry-delay-ms)))
               (recur (inc pass) last-error))

             :else
             (let [data
                   (cond->
                    {:kind :tree-cleanup-failed
                     :reason :cleanup-retries-exhausted
                     :root (str root)
                     :passes max-passes}
                     last-error
                     (assoc :exception-class (.getName (class last-error)))
                     (exception-path last-error)
                     (assoc :exception-path (exception-path last-error)))]
               (if last-error
                 (throw
                  (ex-info
                   (str
                    "Directory tree remained after bounded cleanup retries: "
                    (.getName (class last-error))
                    (when-let [path (exception-path last-error)]
                      (str " at " path)))
                   data last-error))
                 (throw
                  (ex-info
                   "Directory tree was recreated after bounded cleanup retries"
                   data)))))))))))
