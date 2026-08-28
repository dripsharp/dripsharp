(ns dripsharp.csharp-presentation
  "Product-neutral presentation for mechanically generated C#.

  The pass changes whitespace only.  Every edit is applied to rendered source
  ranges before they are persisted, so compiler diagnostics continue to map to
  the structured node and Spoon rule that produced the affected token."
  (:require [clojure.string :as str]))

(def default-policy
  "The presentation policy shared by production and adapted-test emission."
  {:width 100
   :indent "  "
   :brace-style :same-line
   :max-blank-lines 1})

(defn- valid-policy?
  [{:keys [width indent brace-style max-blank-lines]}]
  (and (integer? width)
       (<= 40 width)
       (string? indent)
       (not (empty? indent))
       (every? #{\space \tab} indent)
       (= :same-line brace-style)
       (integer? max-blank-lines)
       (<= 0 max-blank-lines 2)))

(defn- policy!
  [policy]
  (let [policy (merge default-policy policy)]
    (when-not (valid-policy? policy)
      (throw (ex-info "Invalid C# presentation policy"
                      {:kind :invalid-csharp-presentation-policy
                       :policy policy})))
    policy))

(defn- edit-index
  [edits]
  (loop [remaining edits
         delta 0
         indexed []]
    (if-let [{:keys [start end replacement] :as edit} (first remaining)]
      (let [replacement-width (count replacement)
            removed-width (- end start)]
        (recur (rest remaining)
               (+ delta (- replacement-width removed-width))
               (conj indexed (assoc edit :delta-before delta))))
      {:edits indexed :total-delta delta})))

(defn- first-edit-ending-at-or-after
  [edits position]
  (loop [low 0
         high (count edits)]
    (if (< low high)
      (let [middle (quot (+ low high) 2)]
        (if (< (:end (nth edits middle)) position)
          (recur (inc middle) high)
          (recur low middle)))
      low)))

(defn- remap-boundary
  [position side {:keys [edits total-delta]}]
  (let [index (first-edit-ending-at-or-after edits position)]
    (if (= index (count edits))
      (+ position total-delta)
      (let [{:keys [start end replacement delta-before]} (nth edits index)
            replacement-width (count replacement)
            insertion? (= start end)
            new-start (+ start delta-before)
            new-end (+ new-start replacement-width)]
        (cond
          (< position start)
          (+ position delta-before)

          (and insertion? (= position start))
          (if (= :start side) new-end new-start)

          (= position start)
          new-start

          (< position end)
          (if (= :start side) new-start new-end)

          (= position end)
          new-end

          :else
          (throw (ex-info "C# presentation edit index is inconsistent"
                          {:kind :invalid-csharp-presentation-edit-index
                           :position position
                           :edit (nth edits index)})))))))

(defn- validate-edits!
  [text edits]
  (loop [cursor 0
         remaining edits]
    (when-let [{:keys [start end replacement] :as edit} (first remaining)]
      (when-not (and (integer? start)
                     (integer? end)
                     (<= cursor start end (count text))
                     (string? replacement))
        (throw (ex-info "Invalid or overlapping C# presentation edits"
                        {:kind :invalid-csharp-presentation-edits
                         :cursor cursor
                         :edit edit
                         :text-width (count text)})))
      (recur end (rest remaining))))
  edits)

(defn- apply-edits
  [{:keys [text mappings] :as rendered} edits]
  (let [edits (->> edits
                   (remove (fn [{:keys [start end replacement]}]
                             (= replacement (subs text start end))))
                   (sort-by (juxt :start :end))
                   vec)]
    (if (empty? edits)
      rendered
      (let [_ (validate-edits! text edits)
            index (edit-index edits)
            builder (StringBuilder.)]
        (loop [cursor 0
               remaining edits]
          (if-let [{:keys [start end replacement]} (first remaining)]
            (do
              (.append builder ^String (subs text cursor start))
              (.append builder ^String replacement)
              (recur end (rest remaining)))
            (.append builder ^String (subs text cursor))))
        {:text (.toString builder)
         :mappings
         (mapv
          (fn [mapping]
            (let [{:keys [start end]} (:destination mapping)]
              (assoc mapping :destination
                     {:start (remap-boundary start :start index)
                      :end (remap-boundary end :end index)})))
          mappings)}))))

(defn- lines
  [text]
  (loop [start 0
         result []]
    (let [newline (.indexOf ^String text "\n" (int start))]
      (if (neg? newline)
        (conj result {:start start :end (count text)
                      :text (subs text start)})
        (recur (inc newline)
               (conj result {:start start :end newline
                             :text (subs text start newline)}))))))

(defn- code-view
  "Returns a same-width view with literals and comments replaced by spaces.
  Block comments and verbatim/raw strings retain state between physical lines."
  [line initial-mode]
  (let [length (count line)
        builder (StringBuilder. length)]
    (loop [index 0
           mode initial-mode
           escaped? false]
      (if (>= index length)
        {:code (.toString builder)
         :mode (if (contains? #{:line-comment :string :character} mode)
                 :code
                 mode)}
        (let [character (.charAt ^String line index)
              next-character (when (< (inc index) length)
                               (.charAt ^String line (inc index)))
              third-character (when (< (+ index 2) length)
                                (.charAt ^String line (+ index 2)))]
          (case mode
            :code
            (cond
              (and (= character \/) (= next-character \/))
              (do (.append builder "  ")
                  (recur (+ index 2) :line-comment false))

              (and (= character \/) (= next-character \*))
              (do (.append builder "  ")
                  (recur (+ index 2) :block-comment false))

              (and (= character \")
                   (= next-character \")
                   (= third-character \"))
              (do (.append builder "   ")
                  (recur (+ index 3) :raw-string false))

              (= character \")
              (do (.append builder \space)
                  (recur (inc index)
                         (if (and (pos? index)
                                  (= \@ (.charAt ^String line (dec index))))
                           :verbatim-string
                           :string)
                         false))

              (= character \')
              (do (.append builder \space)
                  (recur (inc index) :character false))

              :else
              (do (.append builder character)
                  (recur (inc index) :code false)))

            :line-comment
            (do (.append builder \space)
                (recur (inc index) :line-comment false))

            :block-comment
            (if (and (= character \*) (= next-character \/))
              (do (.append builder "  ")
                  (recur (+ index 2) :code false))
              (do (.append builder \space)
                  (recur (inc index) :block-comment false)))

            :raw-string
            (if (and (= character \")
                     (= next-character \")
                     (= third-character \"))
              (do (.append builder "   ")
                  (recur (+ index 3) :code false))
              (do (.append builder \space)
                  (recur (inc index) :raw-string false)))

            :verbatim-string
            (cond
              (and (= character \") (= next-character \"))
              (do (.append builder "  ")
                  (recur (+ index 2) :verbatim-string false))

              (= character \")
              (do (.append builder \space)
                  (recur (inc index) :code false))

              :else
              (do (.append builder \space)
                  (recur (inc index) :verbatim-string false)))

            (:string :character)
            (cond
              escaped?
              (do (.append builder \space)
                  (recur (inc index) mode false))

              (= character \\)
              (do (.append builder \space)
                  (recur (inc index) mode true))

              (or (and (= mode :string) (= character \"))
                  (and (= mode :character) (= character \')))
              (do (.append builder \space)
                  (recur (inc index) :code false))

              :else
              (do (.append builder \space)
                  (recur (inc index) mode false)))))))))

(def ^:private literal-line-modes
  #{:raw-string :verbatim-string})

(def ^:private multiline-line-modes
  (conj literal-line-modes :block-comment))

(defn- crosses-mode?
  [modes initial-mode final-mode]
  (or (contains? modes initial-mode)
      (contains? modes final-mode)))

(defn- analyzed-lines
  [text]
  (loop [remaining (lines text)
         mode :code
         result []]
    (if-let [line (first remaining)]
      (let [initial-mode mode
            view (code-view (:text line) initial-mode)
            final-mode (:mode view)]
        (recur (rest remaining)
               final-mode
               (conj result
                     (assoc line
                            :code (:code view)
                            :initial-mode initial-mode
                            :final-mode final-mode
                            :literal-line?
                            (crosses-mode? literal-line-modes
                                           initial-mode
                                           final-mode)
                            :multiline-line?
                            (crosses-mode? multiline-line-modes
                                           initial-mode
                                           final-mode)))))
      result)))

(defn- leading-closes
  [code]
  (count (or (second (re-find #"^\s*(}*)" code)) "")))

(defn- case-label?
  [code]
  (boolean (re-find #"^\s*(?:case\b.*|default\s*):" code)))

(defn- switch-opening?
  [code brace-index]
  (boolean
   (re-find #"\bswitch\s*\([^{}]*\)\s*$"
            (subs code 0 brace-index))))

(defn- brace-events
  [code]
  (keep-indexed
   (fn [index character]
     (case character
       \{ {:kind :open
           :block-kind (if (switch-opening? code index) :switch :block)}
       \} {:kind :close}
       nil))
   code))

(defn- update-brace-stack
  [stack events]
  (reduce
   (fn [current {:keys [kind block-kind]}]
     (if (= :close kind)
       (if (seq current) (pop current) current)
       (conj current {:kind block-kind :case-active? false})))
   stack
   events))

(defn- angle-depth-after
  [code initial-depth]
  (loop [index 0
         depth initial-depth]
    (if (>= index (count code))
      depth
      (let [character (.charAt ^String code index)
            previous (when (pos? index) (.charAt ^String code (dec index)))
            next-character (when (< (inc index) (count code))
                             (.charAt ^String code (inc index)))
            generic-open?
            (and (= character \<)
                 previous next-character
                 (or (Character/isLetterOrDigit previous)
                     (contains? #{\_ \> \] \?} previous))
                 (not (Character/isWhitespace next-character)))]
        (recur (inc index)
               (cond
                 generic-open? (inc depth)
                 (and (= character \>) (pos? depth)) (dec depth)
                 :else depth))))))

(defn- delimiter-delta
  [code open close]
  (- (count (filter #(= open %) code))
     (count (filter #(= close %) code))))

(defn- continuation-line?
  [trimmed]
  (boolean (re-find #"^(?:[.&|?+*/=-]|::)" trimmed)))

(defn- indentation-edits
  [text {:keys [indent]}]
  (loop [remaining (lines text)
         mode :code
         stack []
         paren-depth 0
         bracket-depth 0
         angle-depth 0
         previous-trimmed nil
         edits []]
    (if-let [{:keys [start end text]} (first remaining)]
      (let [initial-mode mode
            view (code-view text initial-mode)
            code (:code view)
            final-mode (:mode view)
            protected-leading? (contains? literal-line-modes initial-mode)
            protected-trailing? (crosses-mode? literal-line-modes
                                               initial-mode
                                               final-mode)
            leading-width (count (or (re-find #"^[ \t]*" text) ""))
            trailing-start (or (some-> (re-find #"[ \t]+$" text)
                                       count (-) (+ (count text)))
                               (count text))
            trimmed (str/trim code)
            closes (min (leading-closes code) (count stack))
            stack-after-closes (subvec stack 0 (- (count stack) closes))
            case? (case-label? code)
            case-stack
            (if (and case?
                     (= :switch (:kind (peek stack-after-closes))))
              (assoc stack-after-closes
                     (dec (count stack-after-closes))
                     (assoc (peek stack-after-closes) :case-active? true))
              stack-after-closes)
            active-cases (count (filter :case-active? case-stack))
            active-cases (if (and case?
                                  (:case-active? (peek case-stack)))
                           (dec active-cases)
                           active-cases)
            directive? (str/starts-with? trimmed "#")
            continuation? (or (pos? paren-depth)
                              (pos? bracket-depth)
                              (pos? angle-depth)
                              (and (continuation-line? trimmed)
                                   (seq previous-trimmed)
                                   (not (re-find #"[;{}:]$"
                                                 previous-trimmed))))
            level (if (or (str/blank? trimmed) directive?)
                    0
                    (+ (count case-stack)
                       active-cases
                       (if continuation? 1 0)))
            desired-leading (if (or (str/blank? trimmed) directive?)
                              ""
                              (apply str (repeat level indent)))
            edits (cond-> edits
                    (and (not protected-leading?)
                         (not= desired-leading
                               (subs text 0 leading-width)))
                    (conj {:start start
                           :end (+ start leading-width)
                           :replacement desired-leading})

                    (and (not protected-trailing?)
                         (not (str/blank? text))
                         (< trailing-start (count text)))
                    (conj {:start (+ start trailing-start)
                           :end end
                           :replacement ""}))
            starting-stack
            (if (and case?
                     (= :switch (:kind (peek stack))))
              (assoc stack (dec (count stack))
                     (assoc (peek stack) :case-active? true))
              stack)
            stack (update-brace-stack starting-stack (brace-events code))
            paren-depth (max 0 (+ paren-depth
                                  (delimiter-delta code \( \))))
            bracket-depth (max 0 (+ bracket-depth
                                    (delimiter-delta code \[ \])))
            angle-depth (angle-depth-after code angle-depth)]
        (recur (rest remaining) final-mode stack paren-depth bracket-depth angle-depth
               (when-not (str/blank? trimmed) trimmed)
               edits))
      edits)))

(defn- blank-line-edits
  [source-text {:keys [max-blank-lines]}]
  (loop [remaining (lines source-text)
         mode :code
         blank-count 0
         edits []]
    (if-let [{:keys [start end text]} (first remaining)]
      (let [initial-mode mode
            final-mode (:mode (code-view text initial-mode))
            literal-line? (crosses-mode? literal-line-modes
                                         initial-mode
                                         final-mode)
            blank? (and (not literal-line?) (str/blank? text))
            remove? (and blank? (>= blank-count max-blank-lines))
            edit-end (if (< end (count source-text)) (inc end) end)]
        (recur (rest remaining)
               final-mode
               (if blank? (inc blank-count) 0)
               (cond-> edits
                 remove? (conj {:start start :end edit-end :replacement ""}))))
      edits)))

(defn- previous-nonblank-line
  [records index]
  (loop [candidate (dec index)]
    (when (>= candidate 0)
      (let [record (nth records candidate)]
        (cond
          (:multiline-line? record) nil
          (str/blank? (:code record)) (recur (dec candidate))
          :else record)))))

(defn- brace-policy-edits
  [text]
  (let [records (analyzed-lines text)]
    (->> records
         (map-indexed
          (fn [index {:keys [start text code multiline-line?]}]
            (let [trimmed (str/trim code)
                  leading (count (or (re-find #"^[ \t]*" text) ""))
                  previous (previous-nonblank-line records index)
                  previous-trimmed (some-> previous :code str/trim)]
              (cond
                (and (not multiline-line?)
                     (= "{" trimmed)
                     previous
                     (not (str/starts-with? previous-trimmed "#"))
                     (not (str/starts-with? previous-trimmed "//")))
                {:start (:end previous)
                 :end (+ start leading)
                 :replacement " "}

                (and (not multiline-line?)
                     previous
                     (= "}" previous-trimmed)
                     (re-find #"^(?:else|catch|finally)\b" trimmed))
                {:start (:end previous)
                 :end (+ start leading)
                 :replacement " "}

                :else nil))))
         (remove nil?)
         vec)))

(defn- whitespace-end
  [text start]
  (loop [index start]
    (if (and (< index (count text))
             (contains? #{\space \tab} (.charAt ^String text index)))
      (recur (inc index))
      index)))

(defn- comma-candidates
  [code text]
  (keep-indexed
   (fn [index character]
     (when (= \, character)
       (let [start (inc index)]
         (when (< start (count code))
           {:start start :end (whitespace-end text start)}))))
   code))

(def ^:private wrap-operator-pattern
  #"([ \t]+)(?:&&|\|\||\?\?|==|!=|<=|>=|=>|[?+:*/=-])")

(defn- operator-candidates
  [code text]
  (let [matcher (re-matcher wrap-operator-pattern code)]
    (loop [result []]
      (if (.find matcher)
        (let [start (.start matcher 1)
              end (.end matcher 1)]
          (recur (cond-> result
                   (every? #{\space \tab} (subs text start end))
                   (conj {:start start :end end}))))
        result))))

(defn- semicolon-candidates
  [code text]
  (keep-indexed
   (fn [index character]
     (when (= \; character)
       (let [start (inc index)
             end (whitespace-end text start)]
         (when (< start (count code))
           {:start start :end end}))))
   code))

(defn- line-wrap-edits
  [{:keys [start text]} mode {:keys [width indent]}]
  (let [{:keys [code mode]} (code-view text mode)
        trimmed (str/triml code)
        skip? (or (str/blank? trimmed)
                  (str/starts-with? trimmed "#")
                  (str/starts-with? trimmed "//"))
        leading (count (or (re-find #"^[ \t]*" text) ""))
        continuation (str (subs text 0 leading) indent)
        candidates (if skip?
                     []
                     (->> (concat (comma-candidates code text)
                                  (operator-candidates code text)
                                  (semicolon-candidates code text))
                          distinct
                          (sort-by (juxt :start :end))
                          vec))]
    (loop [remaining candidates
           segment-start 0
           first-segment? true
           edits []]
      (let [prefix-width (if first-segment? 0 (count continuation))
            remaining-width (+ prefix-width (- (count text) segment-start))]
        (if (or (<= remaining-width width) (empty? remaining))
          {:mode mode :edits edits}
          (let [available (- width prefix-width)
                eligible (filter #(> (:start %) segment-start) remaining)
                fitting (filter #(<= (- (:start %) segment-start) available)
                                eligible)
                selected (or (last fitting) (first eligible))]
            (if-not selected
              {:mode mode :edits edits}
              (recur (vec (drop-while #(<= (:start %) (:start selected))
                                      remaining))
                     (:end selected)
                     false
                     (conj edits
                           {:start (+ start (:start selected))
                            :end (+ start (:end selected))
                            :replacement (str "\n" continuation)})))))))))

(defn- wrapping-edits
  [text policy]
  (loop [remaining (lines text)
         mode :code
         edits []]
    (if-let [line (first remaining)]
      (let [result (line-wrap-edits line mode policy)]
        (recur (rest remaining) (:mode result) (into edits (:edits result))))
      edits)))

(defn present
  "Applies the configured deterministic whitespace policy to rendered C# and
  shifts every zero-based, end-exclusive source range through the same edits."
  ([rendered] (present rendered default-policy))
  ([{:keys [text mappings] :as rendered} policy]
   (when-not (and (map? rendered) (string? text) (vector? mappings))
     (throw (ex-info "C# presentation requires rendered text and mappings"
                     {:kind :invalid-rendered-csharp
                      :rendered rendered})))
   (let [policy (policy! policy)]
     (-> rendered
         (apply-edits (indentation-edits text policy))
         (#(apply-edits % (blank-line-edits (:text %) policy)))
         (#(apply-edits % (brace-policy-edits (:text %))))
         (#(apply-edits % (indentation-edits (:text %) policy)))
         (#(apply-edits % (wrapping-edits (:text %) policy)))
         (#(apply-edits % (indentation-edits (:text %) policy)))
         (#(apply-edits % (blank-line-edits (:text %) policy)))))))
