;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns pdfcli.json
  "Canonical JSON writer of validation/pdfcarton-cli/contract.md: objects keep
  their insertion order, reals follow the contract's number rules, and the
  document is one line."
  (:import (java.math BigDecimal BigInteger RoundingMode)))

(set! *warn-on-reflection* true)

(deftype Obj [entries])

(defn obj
  "An ordered JSON object from alternating keys and values."
  [& kvs]
  (Obj. (vec (partition 2 kvs))))

(defn sorted-obj
  "A JSON object whose keys are sorted in ordinal UTF-16 order."
  [m]
  (Obj. (vec (sort-by first compare (map vec m)))))

(defn entries [^Obj o] (.-entries o))

(deftype Millis [^double value])

(defn millis [nanos] (Millis. (/ (double nanos) 1.0e6)))

(defn- float-text [^Float f]
  (let [v (.floatValue f)]
    (if (or (Float/isNaN v) (Float/isInfinite v))
      "null"
      (.toPlainString (.stripTrailingZeros (BigDecimal. (Float/toString v)))))))

(defn- millis-text [^Millis m]
  (let [v (.-value m)]
    (if (or (Double/isNaN v) (Double/isInfinite v))
      "null"
      (.toPlainString
       (.stripTrailingZeros (.setScale (BigDecimal. v) 3 RoundingMode/HALF_EVEN))))))

(defn- write-string [^StringBuilder sb ^String s]
  (.append sb \")
  (let [n (.length s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charAt s i)]
          (cond
            (= c \") (.append sb "\\\"")
            (= c \\) (.append sb "\\\\")
            (= c \backspace) (.append sb "\\b")
            (= c \tab) (.append sb "\\t")
            (= c \newline) (.append sb "\\n")
            (= c \formfeed) (.append sb "\\f")
            (= c \return) (.append sb "\\r")
            (< (int c) 0x20) (.append sb (format "\\u%04x" (int c)))
            (Character/isHighSurrogate c)
            (if (and (< (inc i) n) (Character/isLowSurrogate (.charAt s (inc i))))
              (do (.append sb c) (.append sb (.charAt s (inc i))))
              (.append sb (char 0xFFFD)))
            (Character/isLowSurrogate c) (.append sb (char 0xFFFD))
            :else (.append sb c))
          (recur (if (and (Character/isHighSurrogate c)
                          (< (inc i) n)
                          (Character/isLowSurrogate (.charAt s (inc i))))
                   (+ i 2)
                   (inc i)))))))
  (.append sb \"))

(defn- write-value [^StringBuilder sb v]
  (cond
    (nil? v) (.append sb "null")
    (instance? Obj v)
    (do (.append sb \{)
        (doseq [[i [k value]] (map-indexed vector (entries v))]
          (when (pos? i) (.append sb \,))
          (write-string sb (str k))
          (.append sb \:)
          (write-value sb value))
        (.append sb \}))
    (string? v) (write-string sb v)
    (boolean? v) (.append sb (if v "true" "false"))
    (instance? Float v) (.append sb (float-text v))
    (instance? Millis v) (.append sb (millis-text v))
    (or (instance? Long v) (instance? Integer v) (instance? Short v)
        (instance? Byte v) (instance? BigInteger v) (instance? clojure.lang.BigInt v))
    (.append sb (str v))
    (sequential? v)
    (do (.append sb \[)
        (doseq [[i item] (map-indexed vector v)]
          (when (pos? i) (.append sb \,))
          (write-value sb item))
        (.append sb \]))
    :else (throw (IllegalArgumentException.
                  (str "No canonical JSON form for " (class v))))))

(defn write-str
  "The canonical one-line JSON text of v, without the trailing newline."
  [v]
  (let [sb (StringBuilder.)]
    (write-value sb v)
    (str sb)))
