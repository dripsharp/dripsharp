(ns vibeformer.diagnostics
  "Stable diagnostic metadata for unexpected translator failures."
  (:import [java.lang StackTraceElement]))

(def ^:private max-stack-summary-frames 8)

(defn throwable-summary
  "Returns deterministic, bounded metadata for a Throwable that is being
  converted into a diagnostic. Structured frames avoid host-specific rendered
  stack strings while retaining the translator call site."
  [^Throwable error]
  (let [stack (vec (.getStackTrace error))]
    {:exception-class (.getName (class error))
     :stack-summary
     (mapv (fn [^StackTraceElement frame]
             {:class-name (.getClassName frame)
              :method-name (.getMethodName frame)
              :file-name (.getFileName frame)
              :line-number (.getLineNumber frame)})
           (take max-stack-summary-frames stack))}))
