(ns vibeformer.concurrency
  "One bounded executor policy for Vibeformer CPU and subprocess work."
  (:import [java.util.concurrent Callable ExecutionException ExecutorService Executors
            ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicInteger]))

(def ^:private worker-property "vibeformer.workers")
(def ^:private worker-environment "VIBEFORMER_WORKERS")

(def ^:dynamic *executor* nil)
(def ^:dynamic *worker-count* nil)
(def ^:dynamic *worker-task?* false)

(defn- positive-worker-count [value source]
  (when value
    (let [parsed (try
                   (Long/parseLong (str value))
                   (catch NumberFormatException error
                     (throw (ex-info "Vibeformer worker count must be a positive integer"
                                     {:kind :invalid-worker-count
                                      :source source :value value}
                                     error))))]
      (when-not (pos? parsed)
        (throw (ex-info "Vibeformer worker count must be a positive integer"
                        {:kind :invalid-worker-count :source source :value value})))
      (int (min parsed Integer/MAX_VALUE)))))

(defn configured-worker-count
  "Returns the explicit option, JVM property, environment value, or multicore default."
  ([] (configured-worker-count nil))
  ([worker-count]
   (or (positive-worker-count worker-count :option)
       (positive-worker-count (System/getProperty worker-property) :system-property)
       (positive-worker-count (System/getenv worker-environment) :environment)
       (max 1 (.availableProcessors (Runtime/getRuntime))))))

(defn current-worker-count [] (or *worker-count* 1))

(defn- thread-factory [prefix]
  (let [sequence (AtomicInteger.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (.incrementAndGet sequence)))
          (.setDaemon false))))))

(defn call-with-executor
  "Calls f with a shared, bounded executor. Nested calls reuse the current policy."
  ([f] (call-with-executor {} f))
  ([{:keys [worker-count thread-prefix]
     :or {thread-prefix "vibeformer-worker"}}
    f]
   (if *executor*
     (f)
     (let [workers (configured-worker-count worker-count)
           ^ExecutorService executor
           (Executors/newFixedThreadPool workers (thread-factory thread-prefix))]
       (try
         (binding [*executor* executor
                   *worker-count* workers]
           (f))
         (finally
           (.shutdown executor)
           (when-not (.awaitTermination executor 30 TimeUnit/SECONDS)
             (.shutdownNow executor)
             (.awaitTermination executor 30 TimeUnit/SECONDS))))))))

(defn- cancel-all! [futures]
  (doseq [future futures]
    (.cancel ^java.util.concurrent.Future future true)))

(defn mapv-ordered
  "Applies f concurrently and returns results in input order. Work submitted
  from one of this executor's workers stays sequential, preventing nested-pool
  deadlocks and oversubscription. Failures are reported in deterministic input
  order with phase and item context, and remaining work is cancelled."
  [phase f inputs]
  (let [inputs (vec inputs)]
    (cond
      (empty? inputs) []
      (or (= 1 (current-worker-count)) *worker-task?*) (mapv f inputs)
      (nil? *executor*) (call-with-executor #(mapv-ordered phase f inputs))
      :else
      (let [chunk-size (max 1 (long (Math/ceil
                                     (/ (double (count inputs))
                                        (* 16.0 (current-worker-count))))))
            chunks (partition-all chunk-size (map-indexed vector inputs))
            tasks
            (mapv
             (fn [chunk]
               (.submit ^ExecutorService *executor*
                        ^Callable
                        (bound-fn []
                          (binding [*worker-task?* true]
                            (mapv
                             (fn [[index input]]
                               (try
                                 (f input)
                                 (catch Throwable error
                                   (throw (ex-info
                                           (str "Concurrent Vibeformer phase failed: " (name phase)
                                                ": " (.getMessage error))
                                           {:kind :concurrent-phase-failed
                                            :phase phase :item-index index}
                                           error)))))
                             chunk)))))
             chunks)]
        (try
          (vec
           (mapcat (fn [future]
                     (try
                       (.get ^java.util.concurrent.Future future)
                       (catch ExecutionException error
                         (throw (.getCause error)))))
                   tasks))
          (catch Throwable error
            (cancel-all! tasks)
            (throw error)))))))
