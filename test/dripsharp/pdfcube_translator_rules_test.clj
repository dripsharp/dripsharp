(ns dripsharp.pdfcube-translator-rules-test
  "PdfCarton is PDFBox translated mechanically, then overlaid with the
  authored JDK/AWT/ImageIO compatibility runtime. No translator rule may be
  keyed on a PDFBox, FontBox or XmpBox class or member: such a rule patches
  translated PDFBox code instead of providing Java semantics."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File PushbackReader StringReader]
           [java.util.regex Pattern]))

(def ^:private upstream-name
  "A PDFBox, FontBox or XmpBox class or member, alone or inside a mapping key
  such as `executable:org.apache.pdfbox.X#m()` or `field:...`."
  #"org\.apache\.(?:pdfbox|fontbox|xmpbox)\.")

(def ^:private package-map-keys
  "Settings whose value maps upstream packages to .NET namespaces."
  #{:namespace-prefixes :external-namespace-prefixes})

(def ^:private upstream-package
  #"org\.apache\.(?:pdfbox|fontbox|xmpbox)(?:\.[a-z0-9_]+)*")

(def ^:private package-coordinate
  "A Maven group:artifact:version coordinate."
  #"[\w.-]+:[\w.-]+:[\w.-]+")

(defn- package-map-violations
  "Entries of a literal package map that name more than an upstream package."
  [value]
  (for [package (keys value)
        :when (not (and (string? package)
                        (or (re-matches upstream-package package)
                            (not (re-find upstream-name package)))))]
    package))

(defn- package-map? [key value]
  (and (contains? package-map-keys key) (map? value)))

(defn- upstream-names
  "Strings and regex literals in `form` that name an upstream class or member,
  except package coordinates and the package maps of `package-map-keys`."
  [form]
  (letfn [(walk-pairs [pairs]
            (mapcat (fn [[key value]]
                      (if (package-map? key value)
                        (package-map-violations value)
                        (concat (walk key) (walk value))))
                    pairs))
          (walk-sequence [items]
            (loop [items (seq items) found []]
              (if-not items
                found
                (let [[item value & more] items]
                  (if (and (next items) (package-map? item value))
                    (recur more (into found (package-map-violations value)))
                    (recur (next items) (into found (walk item))))))))
          (walk [form]
            (cond
              (or (string? form) (instance? Pattern form))
              (let [text (cond-> (str form)
                           ;; a regex names `org\.apache\.pdfbox\.X` escaped
                           (instance? Pattern form) (str/replace "\\." "."))]
                (if (and (re-find upstream-name text)
                         (not (re-matches package-coordinate text)))
                  [text]
                  []))

              (map? form) (walk-pairs form)
              (or (sequential? form) (set? form)) (walk-sequence form)
              :else []))]
    (walk form)))

(defn- read-clojure-forms
  "Every top-level form of a Clojure source file, read without loading or
  evaluating it (the unit tier keeps the translator unloaded)."
  [^File file]
  (let [reader (PushbackReader. (StringReader. (slurp file)))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof ::eof :read-cond :allow} reader)]
          (if (= ::eof form) forms (recur (conj forms form))))))))

(defn- read-edn-forms [^File file]
  (let [reader (PushbackReader. (StringReader. (slurp file)))]
    (loop [forms []]
      (let [form (edn/read {:eof ::eof :default (fn [_ value] value)} reader)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn- files-under [directory extension]
  (->> (file-seq (io/file directory))
       (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) extension)))
       (sort-by str)))

(def ^:private translator-sources
  "The PdfCarton rule bundle and its test-suite strategy. The shared Java
  translator (src/dripsharp/*.clj) must not name any product at all."
  (concat [(io/file "src/dripsharp/pdfcube/java_project.clj")
           (io/file "src/dripsharp/pdfcube/test_suite.clj")]
          (filter #(.isFile ^File %) (.listFiles (io/file "src/dripsharp")))))

(def ^:private target-configuration
  "The PdfCarton target, profile, destination and test-suite EDN."
  (files-under "targets/pdfcube" ".edn"))

(defn- violations []
  (into (sorted-map)
        (remove (comp empty? second))
        (concat
         (for [^File file translator-sources
               :when (str/ends-with? (.getName file) ".clj")]
           [(str file) (vec (mapcat upstream-names (read-clojure-forms file)))])
         (for [^File file target-configuration]
           [(str file) (vec (mapcat upstream-names (read-edn-forms file)))]))))

(deftest translator-rules-name-no-pdfbox-fontbox-or-xmpbox-class-or-member
  (is (seq (filter #(str/ends-with? (str %) "java_project.clj") translator-sources)))
  (is (seq target-configuration))
  (testing "the scan sees rule keys and leaves only package coordinates and package maps"
    (is (= ["executable:org.apache.pdfbox.rendering.PageDrawer#drawImage(int)"
            "field:org.apache.fontbox.ttf.model.GsubData#NO_DATA_FOUND"
            "org.apache.xmpbox.type.BooleanType"
            "org.apache.pdfbox.util.DateConverter"
            "org.apache.pdfbox.rendering.PageDrawer"]
           (upstream-names
            '[{"executable:org.apache.pdfbox.rendering.PageDrawer#drawImage(int)" x}
              #{"field:org.apache.fontbox.ttf.model.GsubData#NO_DATA_FOUND"}
              (assoc config :namespace-prefixes {"org.apache.xmpbox.type.BooleanType" "X"})
              {:external-namespace-prefixes {"org.apache.pdfbox" "DripSharp.PdfCarton"}
               :rules (= "org.apache.pdfbox.util.DateConverter" type)}
              {:namespace-prefixes {"org.apache.pdfbox.io" "DripSharp.PdfCarton.IO"}
               :source-project-id "org.apache.pdfbox:pdfbox:3.0.8"
               :type #"org\.apache\.pdfbox\.rendering\.PageDrawer"}]))))
  (is (= {} (violations))))
