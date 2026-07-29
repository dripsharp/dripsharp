(ns dripsharp.project-xml
  "Minimal deterministic XML construction for generated SDK projects.

  The model intentionally supports only element and text nodes. Project
  policies compose nodes before one final render, so target-specific properties
  and items never depend on literal positions in already-rendered XML."
  (:require [dripsharp.util :as util]))

(def ^:private xml-name-pattern
  #"[A-Za-z_][A-Za-z0-9_.:-]*")

(defn valid-text?
  "Returns true when value is a string containing only XML 1.0 characters."
  [value]
  (and
   (string? value)
   (loop [offset 0]
     (if (= offset (.length ^String value))
       true
       (let [code-point (.codePointAt ^String value offset)]
         (if (or (= 0x9 code-point)
                 (= 0xA code-point)
                 (= 0xD code-point)
                 (<= 0x20 code-point 0xD7FF)
                 (<= 0xE000 code-point 0xFFFD)
                 (<= 0x10000 code-point 0x10FFFF))
           (recur (+ offset (Character/charCount code-point)))
           false))))))

(defn- xml-name!
  [kind value]
  (when-not (and (string? value) (re-matches xml-name-pattern value))
    (throw (ex-info "Invalid project XML name"
                    {:kind :invalid-project-xml-name
                     :name-kind kind
                     :value value})))
  value)

(defn text
  "Creates an escaped XML text node."
  [value]
  (let [text (str value)]
    (when-not (valid-text? text)
      (throw (ex-info "Invalid project XML text"
                      {:kind :invalid-project-xml-text
                       :text text})))
    {:kind :text :text text}))

(defn- node!
  [node]
  (when-not (and (map? node) (contains? #{:element :text} (:kind node)))
    (throw (ex-info "Expected a structured project XML node"
                    {:kind :invalid-project-xml-node :node node})))
  node)

(defn- attributes!
  [attributes]
  (let [attributes
        (cond
          (nil? attributes) []
          (map? attributes) (sort-by key attributes)
          (sequential? attributes) (vec attributes)
          :else
          (throw (ex-info "Project XML attributes must be a map or pair sequence"
                          {:kind :invalid-project-xml-attributes
                           :attributes attributes})))
        normalized
        (mapv
         (fn [attribute]
           (when-not (and (sequential? attribute) (= 2 (count attribute)))
             (throw
              (ex-info "Project XML attribute must be a name/value pair"
                       {:kind :invalid-project-xml-attribute
                        :attribute attribute})))
           (let [[name value] attribute
                 value (str value)]
             (when-not (valid-text? value)
               (throw
                (ex-info "Invalid project XML attribute value"
                         {:kind :invalid-project-xml-attribute-value
                          :attribute name
                          :value value})))
             [(xml-name! :attribute name) value]))
         attributes)
        duplicates
        (->> normalized
             (map first)
             frequencies
             (keep (fn [[name count]] (when (< 1 count) name)))
             sort
             vec)]
    (when (seq duplicates)
      (throw (ex-info "Project XML element has duplicate attributes"
                      {:kind :duplicate-project-xml-attributes
                       :attributes duplicates})))
    normalized))

(defn element
  "Creates an XML element. Attribute pair order is preserved; map attributes
  are sorted by name. Children must be structured element or text nodes."
  ([name] (element name nil []))
  ([name children] (element name nil children))
  ([name attributes children]
   {:kind :element
    :name (xml-name! :element name)
    :attributes (attributes! attributes)
    :children (mapv node! children)}))

(defn- rendered-attributes
  [attributes]
  (apply str
         (map (fn [[name value]]
                (str " " name "=\"" (util/xml-escape value) "\""))
              attributes)))

(declare render-node)

(defn- render-element
  [{:keys [name attributes children]} depth]
  (let [indent (apply str (repeat depth "  "))
        open (str indent "<" name (rendered-attributes attributes))]
    (cond
      (empty? children)
      (str open " />\n")

      (every? #(= :text (:kind %)) children)
      (str open ">"
           (apply str (map #(util/xml-escape (:text %)) children))
           "</" name ">\n")

      (some #(= :text (:kind %)) children)
      (throw (ex-info "Mixed project XML content is not supported"
                      {:kind :unsupported-project-xml-mixed-content
                       :element name}))

      :else
      (str open ">\n"
           (apply str (map #(render-node % (inc depth)) children))
           indent "</" name ">\n"))))

(defn- render-node
  [node depth]
  (let [node (node! node)]
    (case (:kind node)
      :element (render-element node depth)
      :text (throw (ex-info "Project XML text must belong to an element"
                            {:kind :orphan-project-xml-text
                             :text (:text node)})))))

(defn render
  "Renders one structured project XML root with two-space indentation and a
  trailing newline."
  [root]
  (when-not (= :element (:kind (node! root)))
    (throw (ex-info "Project XML root must be an element"
                    {:kind :invalid-project-xml-root :root root})))
  (render-node root 0))
