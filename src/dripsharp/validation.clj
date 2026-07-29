(ns dripsharp.validation
  "Small product-neutral helpers for actionable configuration diagnostics."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn- path-text
  [path]
  (if (seq path)
    (str/join
     ""
     (map-indexed
      (fn [index component]
        (cond
          (integer? component) (str "[" component "]")
          (zero? index) (pr-str component)
          :else (str " " (pr-str component))))
      path))
    "<root>"))

(defn- ordered
  [values]
  (vec (sort-by pr-str values)))

(defn fail!
  "Throws an ExceptionInfo for one invalid value.

  Context requires `:kind` and `:subject`; `:data` is merged into the
  exception data. The diagnostic always identifies the exact data path,
  offending value, and expected predicate or shape."
  [{:keys [kind subject data]} path value expected]
  (throw
   (ex-info
    (str subject " at " (path-text path) " must be " expected
         "; got " (pr-str value))
    (merge data
           {:kind kind
            :subject subject
            :path (vec path)
            :field-path (vec path)
            :value value
            :expected expected}))))

(defn check!
  "Returns value when predicate accepts it; otherwise throws a field-specific
  diagnostic through `fail!`."
  [context path value expected predicate]
  (when-not (predicate value)
    (fail! context path value expected))
  value)

(defn exact-keys!
  "Validates a map's required and allowed keys.

  Missing and unknown keys are reported separately. `:missing` and `:unknown`
  aliases are retained for callers of older validation boundaries."
  [context path value required allowed]
  (check! context path value "a map" map?)
  (let [actual (set (keys value))
        missing (ordered (set/difference required actual))
        unknown (ordered (set/difference actual allowed))]
    (when (or (seq missing) (seq unknown))
      (throw
       (ex-info
        (str (:subject context) " at " (path-text path)
             " has missing or unknown keys")
        (merge (:data context)
               {:kind (:kind context)
                :subject (:subject context)
                :path (vec path)
                :field-path (vec path)
                :value value
                :expected {:required-keys (ordered required)
                           :allowed-keys (ordered allowed)}
                :missing-keys missing
                :unknown-keys unknown
                :missing missing
                :unknown unknown})))))
  value)

(defn agree!
  "Requires two contract fields to agree and reports both exact paths."
  [context expected-path expected-value actual-path actual-value]
  (when-not (= expected-value actual-value)
    (throw
     (ex-info
      (str (:subject context) " at " (path-text actual-path)
           " must agree with " (path-text expected-path)
           "; got " (pr-str actual-value)
           ", expected " (pr-str expected-value))
      (merge (:data context)
             {:kind (:kind context)
              :subject (:subject context)
              :path (vec actual-path)
              :field-path (vec actual-path)
              :value actual-value
              :expected {:path (vec expected-path)
                         :value expected-value}
              :expected-path (vec expected-path)
              :expected-value expected-value
              :actual-path (vec actual-path)
              :actual-value actual-value}))))
  actual-value)
