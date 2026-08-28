(ns dripsharp.csharp
  "A small structured C# writer used by frontend translators.

  Nodes retain their nesting and precedence until rendering.  Source metadata is
  attached to nodes, so mappings are derived from the destination structure
  rather than reconstructed from emitted text."
  (:refer-clojure :exclude [cast])
  (:require [clojure.string :as str]
            [dripsharp.csharp-presentation :as presentation]))

(def ^:private atom-precedence 100)
(def ^:private postfix-precedence 90)
(def ^:private prefix-precedence 80)
(def ^:private assignment-precedence 10)
(def ^:private conditional-precedence 20)
(def ^:private binary-precedences
  {"||" 30
   "&&" 35
   "|" 40
   "^" 45
   "&" 50
   "==" 55
   "!=" 55
   "<" 60
   "<=" 60
   ">" 60
   ">=" 60
   "is" 60
   "is not" 60
   "<<" 65
   ">>" 65
   ">>>" 65
   "+" 70
   "-" 70
   "*" 75
   "/" 75
   "%" 75})
(def ^:private namespace-pattern
  #"@?[A-Za-z_][A-Za-z0-9_]*(?:[.]@?[A-Za-z_][A-Za-z0-9_]*)*")

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

(defn statement-list
  "Creates an ordered statement or declaration list. `separator` and `indent`
  are explicit formatting policy; indentation is applied to every rendered
  line before source ranges are composed into the parent node."
  ([statements] (statement-list statements "\n" ""))
  ([statements separator] (statement-list statements separator ""))
  ([statements separator indent]
   (when-not (and (string? separator) (string? indent))
     (throw (ex-info "C# statement-list formatting must be strings"
                     {:kind :invalid-csharp-statement-list-format
                      :separator separator
                      :indent indent})))
   {:kind :statement-list
    :statements (mapv node! statements)
    :separator separator
    :indent indent
    :precedence 0}))

(defn block
  "Creates a brace-delimited block from a statement list. A vector is promoted
  to a newline-separated, unindented statement list so existing deterministic
  formatting remains unchanged."
  [statements]
  (let [statements (if (and (map? statements)
                            (= :statement-list (:kind statements)))
                     (node! statements)
                     (statement-list statements))]
    {:kind :block
     :statements statements
     :precedence 0}))

(defn declaration
  "Creates a declaration with a structured header and optional block body.
  Body-less declarations render with a semicolon. Optional data identifies the
  declaration for destination transforms without inspecting rendered text."
  ([header] (declaration header nil nil))
  ([header body] (declaration header body nil))
  ([header body data]
   (when-not (or (nil? body)
                 (and (map? body) (= :block (:kind body))))
     (throw (ex-info "C# declaration body must be a structured block"
                     {:kind :invalid-csharp-declaration-body :body body})))
   (when-not (or (nil? data) (map? data))
     (throw (ex-info "C# declaration data must be a map"
                     {:kind :invalid-csharp-declaration-data :data data})))
   {:kind :declaration
    :header (node! header)
    :body (some-> body node!)
    :data (or data {})
    :precedence 0}))

(defn file-scoped-namespace
  "Creates a file-scoped namespace declaration whose qualified name remains
  available to namespace transforms until rendering."
  [namespace]
  (when-not (and (string? namespace)
                 (re-matches namespace-pattern namespace))
    (throw (ex-info "Invalid C# namespace"
                    {:kind :invalid-csharp-namespace :namespace namespace})))
  {:kind :file-scoped-namespace
   :namespace namespace
   :precedence 0})

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
  ([operator left right]
   (if-let [precedence (get binary-precedences operator)]
     (binary operator precedence left right)
     (throw (ex-info "C# binary operator has no presentation precedence"
                     {:kind :unknown-csharp-binary-precedence
                      :operator operator}))))
  ([operator precedence left right]
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
    :right (node! right)}))

(defn prefix
  [operator operand]
  (when-not (string? operator)
    (throw (ex-info "Invalid C# prefix operator"
                    {:kind :invalid-csharp-prefix :operator operator})))
  {:kind :prefix
   :operator operator
   :operand (node! operand)
   :precedence prefix-precedence})

(defn postfix
  "Creates a postfix unary expression."
  [operand operator]
  (when-not (and (string? operator) (not (str/blank? operator)))
    (throw (ex-info "Invalid C# postfix operator"
                    {:kind :invalid-csharp-postfix :operator operator})))
  {:kind :postfix
   :operator operator
   :operand (node! operand)
   :precedence postfix-precedence})

(defn conditional
  "Creates a right-associative conditional expression."
  [condition then-expression else-expression]
  {:kind :conditional
   :condition (node! condition)
   :then (node! then-expression)
   :else (node! else-expression)
   :precedence conditional-precedence})

(defn cast
  "Creates a destination cast whose operand is parenthesized only when its
  precedence requires it."
  [type operand]
  {:kind :cast
   :type (node! type)
   :operand (node! operand)
   :precedence prefix-precedence})

(defn assignment
  "Creates a right-associative assignment expression."
  ([target value] (assignment "=" target value))
  ([operator target value]
   (when-not (and (string? operator) (not (str/blank? operator)))
     (throw (ex-info "Invalid C# assignment operator"
                     {:kind :invalid-csharp-assignment
                      :operator operator})))
   {:kind :assignment
    :operator operator
    :target (node! target)
    :value (node! value)
    :precedence assignment-precedence}))

(defn parenthesized
  "Retains the frontend expression tree while allowing presentation to remove
  a redundant pair of parentheses. The ordinary precedence renderer restores
  parentheses whenever the surrounding destination context requires them."
  [expression]
  (let [expression (node! expression)]
    {:kind :parenthesized
     :expression expression
     :precedence (:precedence expression 0)}))

(defn member
  [target member-name]
  (when-not (and (string? member-name) (not (str/blank? member-name)))
    (throw (ex-info "Invalid C# member name"
                    {:kind :invalid-csharp-member :member member-name})))
  {:kind :member
   :target (node! target)
   :member member-name
   :precedence postfix-precedence})

(defn generic-name
  "Creates a source-mappable generic type or method name that remains an
  atomic target when used by member access or invocation."
  [target arguments]
  {:kind :generic-name
   :target (node! target)
   :arguments (mapv node! arguments)
   :precedence atom-precedence})

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
  (let [builder (StringBuilder.)
        mappings (transient [])]
    (doseq [[index next-rendered] (map-indexed vector rendered)]
      (when (pos? index) (.append builder separator))
      (let [text (:text next-rendered)
            offset (.length builder)
            next-mappings (:mappings next-rendered)]
        (.append builder ^String text)
        (doseq [mapping next-mappings]
          (conj! mappings
                 (-> mapping
                     (update-in [:destination :start] + offset)
                     (update-in [:destination :end] + offset))))))
    {:text (.toString builder)
     :mappings (persistent! mappings)}))

(defn- wrap-rendered
  [rendered prefix suffix]
  {:text (str prefix (:text rendered) suffix)
   :mappings (shifted-mappings (:mappings rendered) (count prefix))})

(defn- line-starts
  [text]
  (loop [offset 0 starts (if (empty? text) [] [0])]
    (let [newline (.indexOf ^String text "\n" (int offset))
          start (inc newline)]
      (if (or (neg? newline) (= start (count text)))
        starts
        (recur start (conj starts start))))))

(defn- shift-mapping-for-insertions
  [mapping positions width]
  (let [{:keys [start end]} (:destination mapping)
        start-shift (* width (count (filter #(<= % start) positions)))
        end-shift (* width (count (filter #(< % end) positions)))]
    (-> mapping
        (update-in [:destination :start] + start-shift)
        (update-in [:destination :end] + end-shift))))

(defn- indent-rendered
  [{:keys [text mappings] :as rendered} indent]
  (if (or (empty? indent) (empty? text))
    rendered
    (let [positions (line-starts text)
          position-set (set positions)
          indented
          (apply str
                 (map-indexed
                  (fn [index character]
                    (str (when (contains? position-set index) indent)
                         character))
                  text))]
      {:text indented
       :mappings
       (mapv #(shift-mapping-for-insertions
               % positions (count indent))
             mappings)})))

(declare render-node)

(defn- render-unwrapped
  [node]
  (case (:kind node)
    :raw
    {:text (:text node) :mappings []}

    :sequence
    (join-rendered (mapv #(render-node % 0) (:nodes node)) (:separator node))

    :statement-list
    (join-rendered
     (mapv #(indent-rendered (render-node % 0) (:indent node))
           (:statements node))
     (:separator node))

    :block
    (if (empty? (get-in node [:statements :statements]))
      {:text "{}" :mappings []}
      (-> {:text "{\n" :mappings []}
          (append-rendered (render-node (:statements node) 0))
          (append-rendered {:text "\n}" :mappings []})))

    :declaration
    (let [header (render-node (:header node) 0)]
      (if-let [body (:body node)]
        (-> header
            (append-rendered {:text " " :mappings []})
            (append-rendered (render-node body 0)))
        (append-rendered header {:text ";" :mappings []})))

    :file-scoped-namespace
    {:text (str "namespace " (:namespace node) ";")
     :mappings []}

    :binary
    (let [precedence (:precedence node)]
      (-> (render-node (:left node) precedence)
          (append-rendered {:text (str " " (:operator node) " ") :mappings []})
          (append-rendered (render-node (:right node) (inc precedence)))))

    :prefix
    (let [operand (render-node (:operand node) prefix-precedence)
          collision? (and (contains? #{"+" "-"} (:operator node))
                          (str/starts-with? (:text operand) (:operator node)))]
      (append-rendered {:text (:operator node) :mappings []}
                       (if collision?
                         (wrap-rendered operand "(" ")")
                         operand)))

    :postfix
    (append-rendered (render-node (:operand node) postfix-precedence)
                     {:text (:operator node) :mappings []})

    :conditional
    (-> (render-node (:condition node) (inc conditional-precedence))
        (append-rendered {:text " ? " :mappings []})
        (append-rendered (render-node (:then node) 0))
        (append-rendered {:text " : " :mappings []})
        (append-rendered (render-node (:else node) conditional-precedence)))

    :cast
    (-> (wrap-rendered (render-node (:type node) 0) "(" ")")
        (append-rendered (render-node (:operand node) prefix-precedence)))

    :assignment
    (-> (render-node (:target node) (inc assignment-precedence))
        (append-rendered {:text (str " " (:operator node) " ") :mappings []})
        (append-rendered (render-node (:value node) assignment-precedence)))

    :parenthesized
    (render-node (:expression node) 0)

    :member
    (-> (render-node (:target node) postfix-precedence)
        (append-rendered {:text (str "." (:member node)) :mappings []}))

    :generic-name
    (let [arguments (join-rendered
                     (mapv #(render-node % 0) (:arguments node)) ", ")]
      (-> (render-node (:target node) atom-precedence)
          (append-rendered {:text "<" :mappings []})
          (append-rendered arguments)
          (append-rendered {:text ">" :mappings []})))

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

(def presentation-policy
  "The universal product-neutral policy selected by every ordinary render."
  presentation/default-policy)

(defn render-raw
  "Renders a structured node without presentation for structural translator
  decisions. Emission paths must use `render` so mappings are finalized only
  after the universal presentation pass."
  [node]
  (render-node node 0))

(defn render
  "Renders and presents a structured node deterministically. Source ranges are
  finalized only after indentation, brace, blank-line, and wrapping edits."
  ([node]
   (presentation/present (render-raw node) presentation-policy))
  ([node policy]
   (presentation/present (render-raw node) policy)))

(defn present-text
  "Applies the shared policy to mechanically assembled C# text that surrounds
  translated nodes, such as generated adapted-test wrappers."
  ([text] (present-text text presentation-policy))
  ([text policy]
   (:text (presentation/present {:text text :mappings []} policy))))

(defn transform
  "Applies a deterministic post-order transform to a structured C# tree before
  rendering. The transform must return another structured node."
  [node transform-node]
  (when-not (ifn? transform-node)
    (throw (ex-info "C# node transform must be callable"
                    {:kind :invalid-csharp-transform
                     :transform transform-node})))
  (letfn [(visit [node]
            (let [node (node! node)
                  transformed
                  (case (:kind node)
                    :sequence
                    (update node :nodes #(mapv visit %))

                    :statement-list
                    (update node :statements #(mapv visit %))

                    :block
                    (update node :statements visit)

                    :declaration
                    (cond-> (update node :header visit)
                      (:body node) (update :body visit))

                    :binary
                    (-> node (update :left visit) (update :right visit))

                    :prefix
                    (update node :operand visit)

                    :postfix
                    (update node :operand visit)

                    :conditional
                    (-> node
                        (update :condition visit)
                        (update :then visit)
                        (update :else visit))

                    :cast
                    (-> node (update :type visit) (update :operand visit))

                    :assignment
                    (-> node (update :target visit) (update :value visit))

                    :parenthesized
                    (update node :expression visit)

                    :member
                    (update node :target visit)

                    :generic-name
                    (-> node
                        (update :target visit)
                        (update :arguments #(mapv visit %)))

                    :invocation
                    (-> node
                        (update :target visit)
                        (update :arguments #(mapv visit %)))

                    node)]
              (node! (transform-node transformed))))]
    (visit node)))

(defn transform-namespaces
  "Rewrites exact namespace identities on structured namespace nodes and
  globally qualified raw identifiers before rendering. Different-length
  replacements therefore flow through ordinary source-range composition."
  [node namespace-mappings]
  (when-not (and (map? namespace-mappings)
                 (every? (fn [[source destination]]
                           (and (string? source)
                                (string? destination)
                                (re-matches namespace-pattern source)
                                (re-matches namespace-pattern destination)))
                         namespace-mappings))
    (throw (ex-info "C# namespace transform requires valid namespace mappings"
                    {:kind :invalid-csharp-namespace-transform
                     :namespace-mappings namespace-mappings})))
  (let [ordered (sort-by (comp - count key) namespace-mappings)]
    (transform
     node
     (fn [current]
       (case (:kind current)
         :file-scoped-namespace
         (update current :namespace #(get namespace-mappings % %))

         :raw
         (update current :text
                 (fn [text]
                   (reduce
                    (fn [result [source destination]]
                      (str/replace
                       result
                       (re-pattern
                        (str "global::" (java.util.regex.Pattern/quote source)
                             "(?![A-Za-z0-9_])"))
                       (str "global::" destination)))
                    text
                    ordered)))

         current)))))

(defn replace-raw-text
  "Applies ordered literal replacements only within raw leaf nodes before
  rendering. This is intended for bounded token adaptations; declaration and
  block structure should be changed through their first-class nodes."
  [node replacements]
  (let [replacements (vec replacements)]
    (when-not
     (every? (fn [replacement]
               (and (sequential? replacement)
                    (= 2 (count replacement))
                    (every? string? replacement)))
             replacements)
      (throw (ex-info "Raw C# replacements must be string pairs"
                      {:kind :invalid-csharp-raw-replacements
                       :replacements replacements})))
    (transform
     node
     (fn [current]
       (if (= :raw (:kind current))
         (update current :text
                 (fn [text]
                   (reduce
                    (fn [result [source destination]]
                      (str/replace result source destination))
                    text
                    replacements)))
         current)))))
