(ns dripsharp.differential
  "Product-neutral comparison of normalized differential observations."
  (:require [dripsharp.paths :as paths])
  (:import [java.io BufferedReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn compare-results
  "Compares normalized line-oriented observations without loading large trees
  in memory."
  [expected actual]
  (with-open [^BufferedReader left
              (Files/newBufferedReader
               (paths/path expected) StandardCharsets/UTF_8)
              ^BufferedReader right
              (Files/newBufferedReader
               (paths/path actual) StandardCharsets/UTF_8)]
    (loop [line-number 1 matched 0]
      (let [expected-line (.readLine left)
            actual-line (.readLine right)]
        (cond
          (and (nil? expected-line) (nil? actual-line))
          {:matched matched}

          (= expected-line actual-line)
          (recur (inc line-number) (inc matched))

          :else
          {:matched matched
           :mismatch {:line line-number
                      :expected expected-line
                      :actual actual-line}})))))
