(ns vibeformer.csharp
  "A small structured C# writer used by frontend translators.

  Nodes retain their nesting and precedence until rendering.  Source metadata is
  attached to nodes, so mappings are derived from the destination structure
  rather than reconstructed from emitted text."
  (:require [clojure.string :as str]))

(def ^:private atom-precedence 100)
(def ^:private postfix-precedence 90)
(def ^:private prefix-precedence 80)

(defn- node!
  [node]
  (when-not (and (map? node) (keyword? (:kind node)))
    (throw (ex-info "Expected a structured C# node"
                    {:kind :invalid-csharp-node :node node})))
  node)

(defn raw
  "Creates an indivisible destination fragment.  Callers, rather than the
  writer, own the semantic decision represented by the text."
  [text]
  (when-not (string? text)
    (throw (ex-info "Raw C# text must be a string"
                    {:kind :invalid-csharp-text :text text})))
  {:kind :raw :text text :precedence atom-precedence})

(defn sequence-node
  ([nodes] (sequence-node nodes ""))
  ([nodes separator]
   (when-not (string? separator)
     (throw (ex-info "C# sequence separator must be a string"
                     {:kind :invalid-csharp-separator :separator separator})))
   {:kind :sequence
    :nodes (mapv node! nodes)
    :separator separator
    :precedence 0}))

(defn binary
  "Creates a binary expression with an explicit precedence.  Equal-precedence
  right children are parenthesized, preserving the frontend tree even for
  non-associative operators."
  [operator precedence left right]
  (when-not (and (string? operator) (not (str/blank? operator))
                 (integer? precedence) (pos? precedence))
    (throw (ex-info "Invalid C# binary operator"
                    {:kind :invalid-csharp-binary
                     :operator operator
                     :precedence precedence})))
  {:kind :binary
   :operator operator
   :precedence precedence
   :left (node! left)
   :right (node! right)})

(defn prefix
  [operator operand]
  (when-not (string? operator)
    (throw (ex-info "Invalid C# prefix operator"
                    {:kind :invalid-csharp-prefix :operator operator})))
  {:kind :prefix
   :operator operator
   :operand (node! operand)
   :precedence prefix-precedence})

(defn member
  [target member-name]
  (when-not (and (string? member-name) (not (str/blank? member-name)))
    (throw (ex-info "Invalid C# member name"
                    {:kind :invalid-csharp-member :member member-name})))
  {:kind :member
   :target (node! target)
   :member member-name
   :precedence postfix-precedence})

(defn invocation
  [target arguments]
  {:kind :invocation
   :target (node! target)
   :arguments (mapv node! arguments)
   :precedence postfix-precedence})

(defn with-source
  "Attaches translator-owned source/rule metadata to a destination node."
  [node source]
  (update (node! node) :sources (fnil conj []) source))

(defn- shifted-mappings
  [mappings offset]
  (mapv (fn [mapping]
          (-> mapping
              (update-in [:destination :start] + offset)
              (update-in [:destination :end] + offset)))
        mappings))

(defn- append-rendered
  [left right]
  (let [offset (count (:text left))]
    {:text (str (:text left) (:text right))
     :mappings (into (:mappings left)
                     (shifted-mappings (:mappings right) offset))}))

(defn- join-rendered
  [rendered separator]
  (reduce-kv (fn [combined index next-rendered]
               (append-rendered
                (if (zero? index)
                  combined
                  (append-rendered combined {:text separator :mappings []}))
                next-rendered))
             {:text "" :mappings []}
             (vec rendered)))

(defn- wrap-rendered
  [rendered prefix suffix]
  {:text (str prefix (:text rendered) suffix)
   :mappings (shifted-mappings (:mappings rendered) (count prefix))})

(declare render-node)

(defn- render-unwrapped
  [node]
  (case (:kind node)
    :raw
    {:text (:text node) :mappings []}

    :sequence
    (join-rendered (mapv #(render-node % 0) (:nodes node)) (:separator node))

    :binary
    (let [precedence (:precedence node)]
      (-> (render-node (:left node) precedence)
          (append-rendered {:text (str " " (:operator node) " ") :mappings []})
          (append-rendered (render-node (:right node) (inc precedence)))))

    :prefix
    (append-rendered {:text (:operator node) :mappings []}
                     (render-node (:operand node) prefix-precedence))

    :member
    (-> (render-node (:target node) postfix-precedence)
        (append-rendered {:text (str "." (:member node)) :mappings []}))

    :invocation
    (let [arguments (join-rendered
                     (mapv #(render-node % 0) (:arguments node)) ", ")]
      (-> (render-node (:target node) postfix-precedence)
          (append-rendered {:text "(" :mappings []})
          (append-rendered arguments)
          (append-rendered {:text ")" :mappings []})))

    (throw (ex-info (str "Unsupported structured C# node " (:kind node))
                    {:kind :unsupported-csharp-node :node node}))))

(defn- render-node
  [node minimum-precedence]
  (let [node (node! node)
        unwrapped (render-unwrapped node)
        rendered (if (< (:precedence node 0) minimum-precedence)
                   (wrap-rendered unwrapped "(" ")")
                   unwrapped)]
    (update rendered :mappings into
            (mapv (fn [source]
                    {:source source
                     :destination {:start 0 :end (count (:text rendered))}})
                  (:sources node)))))

(defn render
  "Renders a structured node deterministically and returns exact zero-based,
  end-exclusive destination ranges for all attached source metadata."
  [node]
  (render-node node 0))
