;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns pdfcli.compare
  "Runs the PDFBox (JVM) and PdfCarton (.NET) pdfcli implementations over a
  corpus and compares their output and performance as the
  pdfcarton-cli-parity group defines. Run from the repository root after
  generating PdfCarton: clojure -M:pdfcli-compare [--subset] [options].
  Writes results.json and summary.md to validation-output/pdfcarton-cli/.
  See validation/pdfcarton-cli/contract.md, section Comparison."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import (com.fasterxml.jackson.databind ObjectMapper SerializationFeature)
           (java.awt.image BufferedImage)
           (java.io File)
           (java.lang ProcessBuilder$Redirect)
           (java.time Instant)
           (java.util List Map)
           (java.util.concurrent Executors Future TimeUnit)
           (javax.imageio ImageIO)))

;; ---------------------------------------------------------------- settings

(def default-options
  {:out "validation-output/pdfcarton-cli"
   :jobs 12
   :timeout-s 120
   :perf-timeout-s 300
   :warmup 3
   :repeat 10
   :cold-runs 3
   :subset false
   :perf true
   :pdfcarton-root "target/generated/pdfcarton"})

(def tolerance
  "The group's \"fairly close\" rendering tolerance."
  {:mean-abs 2.0 :fraction-over-32 0.01})

(def known-passwords
  "Passwords upstream tests use for encrypted corpus files."
  {"PasswordSample-40bit.pdf" "owner"
   "PasswordSample-128bit.pdf" "owner"
   "PasswordSample-256bit.pdf" "owner"
   "sign_me_protected.pdf" " "})

(def render-pages "1-10")

(def reopen-render-pages "1-3")

(def dotnet-dll "validation/pdfcarton-cli/dotnet/bin/Release/net10.0/DripSharp.PdfCarton.Tests.dll")

(def exit-codes
  {"usage" 2 "io" 3 "invalid-password" 4 "owner-password-required" 4 "load-failed" 5
   "page-range" 1 "not-encrypted" 1 "no-acroform" 1 "field-not-found" 1 "invalid-value" 1
   "operation-failed" 1})

;; ---------------------------------------------------------------- utilities

(def ^:private ^ObjectMapper mapper (ObjectMapper.))

(defn- java->clj [x]
  (cond (instance? Map x) (into {} (map (fn [[k v]] [k (java->clj v)])) x)
        (instance? List x) (mapv java->clj x)
        :else x))

(defn parse-json [^String s]
  (try (java->clj (.readValue mapper s Object)) (catch Exception _ nil)))

(defn- jsonable [x]
  (cond (keyword? x) (name x)
        (map? x) (into (sorted-map) (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (jsonable v)])) x)
        (or (sequential? x) (set? x)) (mapv jsonable x)
        :else x))

(defn- write-json [file data]
  (.writeValue (.enable (ObjectMapper.) SerializationFeature/INDENT_OUTPUT) (io/file file) (jsonable data)))

(defn- delete-tree! [^File f]
  (when (.isDirectory f) (run! delete-tree! (.listFiles f)))
  (.delete f))

(defn- clip [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "...") s)))

(defn- round3 [x] (when x (/ (Math/round (* 1000.0 (double x))) 1000.0)))

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (when (pos? n)
      (if (odd? n) (v (quot n 2)) (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0)))))

(defn- ratio [a b] (when (and a b (pos? (double a))) (round3 (/ (double b) (double a)))))

(defn- shell-quote [^String s]
  (if (re-matches #"[A-Za-z0-9_./:=,+-]+" s) s (str "'" (str/replace s "'" "'\\''") "'")))

;; ---------------------------------------------------------------- processes

(defn run-process
  "Runs cmd with a timeout. Returns {:exit :timed-out :wall-ms :out :err}."
  [cmd {:keys [timeout-s dir env]}]
  (.mkdirs (io/file dir))
  (let [out-file (io/file dir "stdout.txt")
        err-file (io/file dir "stderr.txt")
        pb (doto (ProcessBuilder. ^List (vec cmd))
             (.redirectOutput out-file)
             (.redirectError err-file)
             (.redirectInput (ProcessBuilder$Redirect/from (io/file "/dev/null"))))
        _ (doseq [[k v] env] (.put (.environment pb) k v))
        start (System/nanoTime)
        p (.start pb)
        finished (.waitFor p (long timeout-s) TimeUnit/SECONDS)
        wall (/ (- (System/nanoTime) start) 1.0e6)]
    (when-not finished
      (.forEach (.descendants p) (reify java.util.function.Consumer (accept [_ h] (.destroyForcibly ^java.lang.ProcessHandle h))))
      (.destroyForcibly p)
      (.waitFor p 10 TimeUnit/SECONDS))
    (let [result {:exit (when finished (.exitValue p))
                  :timed-out (not finished)
                  :wall-ms wall
                  :out (slurp out-file :encoding "UTF-8")
                  :err (let [e (slurp err-file)] (clip (str/trim e) 600))}]
      (.delete out-file) (.delete err-file)
      result)))

(defn- run-status
  "Classifies one invocation: :ok, :error (contract error envelope), :crash or :timeout."
  [{:keys [exit timed-out out]}]
  (let [env (when-not timed-out (parse-json (str/trim out)))]
    (cond timed-out {:status :timeout :env nil}
          (and (= 0 exit) (map? env) (true? (env "ok"))) {:status :ok :env env}
          (and (map? env) (false? (env "ok"))
               (= exit (exit-codes (get-in env ["error" "code"]))))
          {:status :error :env env :code (get-in env ["error" "code"])}
          :else {:status :crash :env env})))

(defn invoke
  "Runs implementation impl with args (placeholders already resolved)."
  [impl args opts dir]
  (let [r (run-process (into (:cmd impl) args) (assoc opts :dir dir :env (:env impl)))
        s (run-status r)]
    (merge (select-keys r [:exit :timed-out :wall-ms :err]) s)))

;; ---------------------------------------------------------------- normalisation and diffs

(def write-commands #{"form-fill" "merge" "split" "rotate" "encrypt" "decrypt" "create" "save"})

(defn- replace-strings [x from to]
  (cond (string? x) (str/replace x from to)
        (map? x) (into {} (map (fn [[k v]] [k (replace-strings v from to)])) x)
        (vector? x) (mapv #(replace-strings % from to) x)
        :else x))

(defn normalize
  "Drops the fields the contract marks as nondeterministic; replaces the
  invocation's own output directory with <out>."
  [env command out-dir]
  (let [result (env "result")
        result (cond-> result
                 (and (map? result) (write-commands command)) (dissoc "bytes")
                 (and (map? result) (= command "split"))
                 (update "parts" (fn [ps] (mapv #(dissoc % "bytes") ps)))
                 (and (map? result) (= command "render"))
                 (update "pages" (fn [ps] (mapv #(dissoc % "sha256") ps))))]
    (cond-> (dissoc env "implementation")
      (contains? env "result") (assoc "result" result)
      (env "timing") (update "timing" select-keys ["warmup" "repeat"])
      (env "error") (update "error" dissoc "message" "exception")
      out-dir (replace-strings out-dir "<out>"))))

(defn diffs
  "Leaf differences between a and b as [path a-value b-value]."
  ([a b] (diffs a b []))
  ([a b path]
   (cond (and (map? a) (map? b))
         (mapcat #(diffs (get a % ::absent) (get b % ::absent) (conj path %))
                 (distinct (concat (keys a) (keys b))))
         (and (vector? a) (vector? b))
         (concat (mapcat #(diffs (a %) (b %) (conj path %)) (range (min (count a) (count b))))
                 (when (not= (count a) (count b)) [[(conj path "length") (count a) (count b)]]))
         (= a b) nil
         :else [[path a b]])))

(defn- diff-excerpt [ds]
  (vec (for [[p a b] (take 8 ds)]
         {:path (str/join "." p) :pdfbox (clip (pr-str a) 160) :pdfcarton (clip (pr-str b) 160)})))

;; ---------------------------------------------------------------- pixel metrics

(defn image-metrics
  "Mean absolute channel difference (0-255) and the fraction of pixels with
  any channel differing by more than 32. Channels are R, G, B, plus A when
  either image has alpha. nil sizes when the dimensions differ."
  [^BufferedImage a ^BufferedImage b]
  (let [w (.getWidth a) h (.getHeight a)]
    (if (or (not= w (.getWidth b)) (not= h (.getHeight b)))
      {:size-match false :sizes [[w h] [(.getWidth b) (.getHeight b)]]}
      (let [alpha (or (.hasAlpha (.getColorModel a)) (.hasAlpha (.getColorModel b)))
            ^ints pa (.getRGB a 0 0 w h nil 0 w)
            ^ints pb (.getRGB b 0 0 w h nil 0 w)
            n (alength pa)
            shifts (if alpha [24 16 8 0] [16 8 0])
            channels (count shifts)]
        (loop [i 0 sum 0 over 0]
          (if (< i n)
            (let [x (aget pa i) y (aget pb i)
                  dr (Math/abs (- (bit-and (bit-shift-right x 16) 255) (bit-and (bit-shift-right y 16) 255)))
                  dg (Math/abs (- (bit-and (bit-shift-right x 8) 255) (bit-and (bit-shift-right y 8) 255)))
                  db (Math/abs (- (bit-and x 255) (bit-and y 255)))
                  da (if alpha
                       (Math/abs (- (bit-and (unsigned-bit-shift-right x 24) 255)
                                    (bit-and (unsigned-bit-shift-right y 24) 255)))
                       0)
                  m (max dr dg db da)]
              (recur (inc i) (+ sum dr dg db da) (if (> m 32) (inc over) over)))
            {:size-match true
             :identical (zero? sum)
             :mean-abs (if (pos? n) (/ (double sum) (* n channels)) 0.0)
             :fraction-over-32 (if (pos? n) (/ (double over) n) 0.0)}))))))

(defn fairly-close? [{:keys [size-match mean-abs fraction-over-32]}]
  (and size-match (<= mean-abs (:mean-abs tolerance))
       (<= fraction-over-32 (:fraction-over-32 tolerance))))

(defn- png-metrics [^File fa ^File fb]
  (let [a (when (.isFile fa) (ImageIO/read fa))
        b (when (.isFile fb) (ImageIO/read fb))]
    (if (and a b) (image-metrics a b) {:size-match false :missing [(some? a) (some? b)]})))

(defn- worst-metrics [ms]
  {:pages (count ms)
   :max-mean-abs (round3 (reduce max 0.0 (keep :mean-abs ms)))
   :max-fraction-over-32 (some-> (reduce max 0.0 (keep :fraction-over-32 ms)) (* 1e6) Math/round (/ 1e6))
   :size-mismatches (count (remove :size-match ms))
   :pages-not-fairly-close (count (remove fairly-close? ms))})

(defn- metrics-class [ms]
  (cond (every? :identical ms) :equal
        (every? fairly-close? ms) :fairly-close
        :else :different))

;; ---------------------------------------------------------------- comparisons of two successful runs

(def class-rank {:equal 0 :fairly-close 1 :different 2})

(defn- worse [a b] (if (> (class-rank b 0) (class-rank a 0)) b a))

(defn- ws-normalize [^String s] (str/trim (str/replace s #"\s+" " ")))

(defn- line-diff-count [^String a ^String b]
  (let [fa (frequencies (str/split-lines a)) fb (frequencies (str/split-lines b))]
    (reduce + (for [k (distinct (concat (keys fa) (keys fb)))]
                (Math/abs (long (- (get fa k 0) (get fb k 0))))))))

(defn- first-difference [^String a ^String b]
  (let [n (min (count a) (count b))
        i (loop [i 0] (if (and (< i n) (= (.charAt a i) (.charAt b i))) (recur (inc i)) i))
        from (max 0 (- i 40))]
    {:offset i
     :pdfbox (pr-str (subs a from (min (count a) (+ i 80))))
     :pdfcarton (pr-str (subs b from (min (count b) (+ i 80))))}))

(defn- compare-text [na nb]
  (let [ta (or (get-in na ["result" "text"]) "")
        tb (or (get-in nb ["result" "text"]) "")
        rest-diffs (diffs (update na "result" dissoc "text") (update nb "result" dissoc "text"))
        exact (= ta tb)
        ws (= (ws-normalize ta) (ws-normalize tb))]
    {:class (cond (seq rest-diffs) :different exact :equal ws :fairly-close :else :different)
     :detail (cond-> {:text-exact exact :text-whitespace-normalised ws
                      :text-chars [(count ta) (count tb)]}
               (not exact) (assoc :line-differences (line-diff-count ta tb)
                                  :first-difference (first-difference ta tb))
               (seq rest-diffs) (assoc :diff-count (count rest-diffs) :diffs (diff-excerpt rest-diffs)))
     :severity (cond (seq rest-diffs) (+ 1000000 (count rest-diffs))
                     (not ws) (line-diff-count ta tb)
                     :else 0)}))

(defn- compare-render [na nb dir-a dir-b]
  (let [ds (diffs na nb)]
    (if (seq ds)
      {:class :different :detail {:diff-count (count ds) :diffs (diff-excerpt ds)}
       :severity (+ 1000000 (count ds))}
      (let [ms (vec (for [p (get-in na ["result" "pages"]) :let [f (p "file")] :when f]
                      (png-metrics (io/file dir-a f) (io/file dir-b f))))
            w (worst-metrics ms)]
        {:class (metrics-class ms)
         :detail {:render w}
         :severity (max (/ (or (:max-mean-abs w) 0.0) (:mean-abs tolerance))
                        (/ (or (:max-fraction-over-32 w) 0.0) (:fraction-over-32 tolerance))
                        (* 1000.0 (:size-mismatches w)))}))))

(defn- compare-images [na nb dir-a dir-b]
  (let [strip (fn [n] (update-in n ["result" "images"] (fn [xs] (mapv #(dissoc % "decodedSha256") xs))))
        ds (diffs (strip na) (strip nb))]
    (if (seq ds)
      {:class :different :detail {:diff-count (count ds) :diffs (diff-excerpt ds)}
       :severity (+ 1000000 (count ds))}
      (let [pairs (map vector (get-in na ["result" "images"]) (get-in nb ["result" "images"]))
            differing (filter (fn [[a b]] (not= (a "decodedSha256") (b "decodedSha256"))) pairs)
            ms (vec (for [[a _] differing]
                      (if-let [f (a "file")]
                        (assoc (png-metrics (io/file dir-a f) (io/file dir-b f)) :name (a "name"))
                        {:size-match false :name (a "name")})))
            w (worst-metrics ms)]
        {:class (if (empty? differing) :equal (if (every? fairly-close? ms) :fairly-close :different))
         :detail (cond-> {:images (count pairs) :hash-differences (count differing)}
                   (seq ms) (assoc :pixels w :names (vec (take 5 (map :name ms)))))
         :severity (if (empty? ms) 0
                       (max (/ (or (:max-mean-abs w) 0.0) (:mean-abs tolerance))
                            (/ (or (:max-fraction-over-32 w) 0.0) (:fraction-over-32 tolerance))
                            (* 1000.0 (:size-mismatches w))))}))))

(defn compare-ok
  "Compares two successful envelopes of command. dir-a/dir-b are the
  invocations' output directories (for PNGs)."
  [command env-a env-b dir-a dir-b]
  (let [na (normalize env-a command dir-a)
        nb (normalize env-b command dir-b)]
    (case command
      "text" (compare-text na nb)
      "render" (compare-render na nb dir-a dir-b)
      "images" (compare-images na nb dir-a dir-b)
      (let [ds (diffs na nb)]
        (if (seq ds)
          {:class :different :detail {:diff-count (count ds) :diffs (diff-excerpt ds)}
           :severity (+ 1000000 (count ds))}
          {:class :equal :detail nil :severity 0})))))

(defn- run-summary [r]
  (cond-> {:status (:status r) :exit (:exit r) :wallMs (round3 (:wall-ms r))}
    (:code r) (assoc :code (:code r))
    (#{:crash :timeout} (:status r)) (assoc :stderr (clip (:err r) 300))
    (= :error (:status r)) (assoc :message (clip (get-in r [:env "error" "message"]) 200))))

(defn classify-pair
  "Classifies one pair of invocations a (PDFBox) and b (PdfCarton)."
  [command a b dir-a dir-b]
  (let [ok-a (= :ok (:status a)) ok-b (= :ok (:status b))]
    (cond (and ok-a ok-b) (compare-ok command (:env a) (:env b) dir-a dir-b)
          ok-a {:class :pdfcarton-fails :severity 0
                :detail {:pdfcarton (:status b) :code (:code b)
                         :message (clip (or (get-in b [:env "error" "message"]) (:err b)) 300)}}
          ok-b {:class :pdfbox-fails :severity 0
                :detail {:pdfbox (:status a) :code (:code a)}}
          :else {:class :both-fail :severity 0
                 :detail {:codes [(or (:code a) (:status a)) (or (:code b) (:status b))]
                          :same (and (= :error (:status a) (:status b)) (= (:code a) (:code b)))}})))

;; ---------------------------------------------------------------- corpus

(defn- pdf-files [^File dir]
  (->> (file-seq dir)
       (filter #(and (.isFile ^File %) (str/ends-with? (str/lower-case (.getName ^File %)) ".pdf")))
       (map #(.getPath ^File %))))

(defn upstream-corpus
  "Every PDF under research/pdfbox/*/src/test/resources (the PDFBox test
  resources, which also hold the pdfcube differential and preflight corpus
  files), sorted."
  []
  (let [modules (.listFiles (io/file "research/pdfbox"))]
    (->> modules
         (mapcat (fn [^File m]
                   (let [r (io/file m "src/test/resources")] (when (.isDirectory r) (pdf-files r)))))
         sort vec)))

(def r "research/pdfbox/pdfbox/src/test/resources/")

(def subset-docs
  [(str r "input/hello3.pdf")
   (str r "input/merge/jpegrgb.pdf")
   (str r "org/apache/pdfbox/pdmodel/interactive/form/AcroFormsBasicFields.pdf")])

(def large-docs
  "Large documents the harness builds with the PDFBox CLI's merge before the run."
  {"large-cweb-x12.pdf" (vec (repeat 12 (str r "input/cweb.pdf")))
   "large-mixed.pdf" (vec (concat (repeat 3 (str r "input/cweb.pdf"))
                                  [(str r "input/PDFBOX-3042-003177-p2.pdf")
                                   (str r "input/eu-001.pdf")
                                   (str r "input/sample_fonts_solidconvertor.pdf")
                                   (str r "input/merge/jpegrgb.pdf")
                                   (str r "input/FC60_Times.pdf")
                                   (str r "org/apache/pdfbox/pdmodel/interactive/form/PDFBOX-5784.pdf")]
                                  (repeat 4 (str r "input/PDFBOX-3042-003177-p2.pdf"))))})

(defn- password-for [doc]
  (known-passwords (.getName (io/file doc))))

(defn- pw-args [doc] (if-let [p (password-for doc)] ["--password" p] []))

(defn- doc-label [doc]
  (str/replace doc #"^research/pdfbox/|^validation-output/pdfcarton-cli/" ""))

;; ---------------------------------------------------------------- cases

(defn read-cases
  "Read-command cases for one document. :out-dir in args is replaced per invocation."
  [doc render-variants?]
  (let [pw (pw-args doc)
        c (fn [command variant args] {:command command :variant variant :docs [doc]
                                      :args (into [command] (concat args pw [doc]))})]
    (concat
     [(c "info" "default" [])
      (c "text" "default" [])
      (c "text" "sorted" ["--sort"])
      (c "render" "rgb-72dpi" ["--pages" render-pages "--out-dir" :out-dir])
      (c "images" "default" ["--pages" render-pages "--out-dir" :out-dir])
      (c "fonts" "default" [])
      (c "outline" "default" [])
      (c "annotations" "default" [])
      (c "form-fields" "default" [])
      (c "attachments" "default" [])]
     (when render-variants?
       [(c "render" "argb-50dpi" ["--pages" "1-2" "--dpi" "50" "--image-type" "argb" "--out-dir" :out-dir])
        (c "render" "gray-50dpi" ["--pages" "1-2" "--dpi" "50" "--image-type" "gray" "--out-dir" :out-dir])
        (c "render" "binary-50dpi" ["--pages" "1-2" "--dpi" "50" "--image-type" "binary" "--out-dir" :out-dir])
        (c "render" "rgb-150dpi" ["--pages" "1" "--dpi" "150" "--out-dir" :out-dir])]))))

(def render-variant-docs
  (set (concat subset-docs
               [(str r "input/cweb.pdf")
                (str r "input/PDFBOX-3042-003177-p2.pdf")
                (str r "input/sample_fonts_solidconvertor.pdf")
                (str r "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf")
                (str r "org/apache/pdfbox/pdmodel/graphics/image/ccittg4.pdf")
                "research/pdfbox/tools/src/test/resources/input/ImageIOUtil/JPXTestRGB.pdf"])))

(defn write-cases
  "A representative subset of write-command cases. :out (file) and :out-dir
  are replaced per invocation; :values names a values file the harness
  writes from the PDFBox CLI's form-fields output."
  [subset?]
  (let [hello (str r "input/hello3.pdf")
        cweb (str r "input/cweb.pdf")
        jpeg (str r "input/merge/jpegrgb.pdf")
        form (str r "org/apache/pdfbox/pdmodel/interactive/form/AcroFormsBasicFields.pdf")
        form2 (str r "org/apache/pdfbox/pdmodel/interactive/form/PDFBOX-5784.pdf")
        enc (str r "org/apache/pdfbox/encryption/PasswordSample-")
        annot (str r "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf")
        rotations (str r "input/PDFBOX-2984-rotations.pdf")
        survey (str r "input/rendering/survey.pdf")
        w (fn [command variant docs args & {:keys [reopen-password]}]
            {:command command :variant variant :docs docs :args (into [command] args)
             :reopen-password reopen-password :write true})
        all [(w "create" "default" [] ["--out" :out])
             (w "create" "unicode-text" [] ["--text" "Grüße – ½ ©" "--out" :out])
             (w "save" "uncompressed" [hello] ["--out" :out hello])
             (w "save" "compressed" [cweb] ["--compress" "--out" :out cweb])
             (w "save" "uncompressed" [jpeg] ["--out" :out jpeg])
             (w "save" "compressed" [form] ["--compress" "--out" :out form])
             (w "save" "uncompressed" [annot] ["--out" :out annot])
             (w "rotate" "90" [cweb] ["--angle" "90" "--out" :out cweb])
             (w "rotate" "-270-page1" [rotations] ["--angle" "-270" "--pages" "1" "--out" :out rotations])
             (w "split" "1,2-" [cweb] ["--ranges" "1,2-" "--out-dir" :out-dir cweb])
             (w "split" "overlap" [survey] ["--ranges" "1-3,2-" "--out-dir" :out-dir survey])
             (w "merge" "three" [hello cweb jpeg] ["--out" :out hello cweb jpeg])
             (w "merge" "forms" [form annot] ["--out" :out form annot])
             (w "encrypt" "aes256-perms" [hello]
                ["--owner-password" "own" "--permissions" "print,extract" "--out" :out hello]
                :reopen-password "own")
             (w "encrypt" "aes128-user" [cweb]
                ["--owner-password" "own" "--user-password" "usr" "--key-length" "128" "--out" :out cweb]
                :reopen-password "own")
             (w "encrypt" "rc4-40" [jpeg]
                ["--owner-password" "own" "--key-length" "40" "--permissions" "" "--out" :out jpeg]
                :reopen-password "own")
             (w "decrypt" "256bit" [(str enc "256bit.pdf")] ["--password" "owner" "--out" :out (str enc "256bit.pdf")])
             (w "decrypt" "128bit" [(str enc "128bit.pdf")] ["--password" "owner" "--out" :out (str enc "128bit.pdf")])
             (w "decrypt" "40bit" [(str enc "40bit.pdf")] ["--password" "owner" "--out" :out (str enc "40bit.pdf")])
             (w "form-fill" "text-fields" [form] ["--values" :values "--out" :out form])
             (w "form-fill" "text-fields" [form2] ["--values" :values "--out" :out form2])]]
    (if subset?
      (filterv #(#{["create" "default"] ["save" "uncompressed"] ["form-fill" "text-fields"]}
                 [(:command %) (:variant %)])
               (remove #(and (= "save" (:command %)) (not= [hello] (:docs %)))
                       (remove #(and (= "form-fill" (:command %)) (not= [form] (:docs %))) all)))
      all)))

(defn- resolve-args [args {:keys [out out-dir values]}]
  (mapv #(case % :out out :out-dir out-dir :values values %) args))

(defn- repro [impl args dir]
  (let [outs {:out (str dir "/out.pdf") :out-dir (str dir "/out") :values (str dir "/values.json")}
        resolved (resolve-args args outs)]
    (str "mkdir -p " dir " && " (:repro impl) " " (str/join " " (map shell-quote resolved)))))

;; ---------------------------------------------------------------- running cases

(defn- impl-dir [case-dir impl] (str case-dir "/" (:name impl)))

(defn- invoke-case [impl c opts case-dir extra-args]
  (let [dir (impl-dir case-dir impl)
        out-dir (str dir "/out")
        args (resolve-args (into (:args c) extra-args)
                           {:out (str dir "/out.pdf") :out-dir out-dir :values (:values-file c)})]
    (assoc (invoke impl args opts dir) :dir dir :out-dir out-dir)))

(defn- form-values!
  "Writes a values file for form-fill from the PDFBox CLI's form-fields: every
  editable text field gets a fixed value."
  [jvm doc opts dir]
  (let [r (invoke jvm ["form-fields" doc] opts dir)
        fields (get-in r [:env "result" "fields"])
        texts (->> fields
                   (filter #(and (= "text" (% "kind")) (not (% "readOnly"))))
                   (map #(% "name")) distinct (take 6))
        file (io/file dir "values.json")]
    (.mkdirs (io/file dir))
    (.writeValue mapper file (into (sorted-map) (map (fn [n] [n "Filled by pdfcli"])) texts))
    (str file)))

(defn- outputs-of
  "Written PDFs of a successful write envelope, as files relative to its invocation dir."
  [command env dir]
  (if (= command "split")
    (vec (for [p (get-in env ["result" "parts"])] (str dir "/out/" (p "file"))))
    [(str dir "/out.pdf")]))

(defn- reopen-checks
  "Each tool reopens the other's output: compares PDFBox reading PdfCarton's
  output, and PdfCarton reading PDFBox's output, against PDFBox reading its
  own output, for info, text and render."
  [{:keys [jvm dotnet]} c a b opts case-dir]
  (let [files-a (outputs-of (:command c) (:env a) (:dir a))
        files-b (outputs-of (:command c) (:env b) (:dir b))
        pw (when-let [p (:reopen-password c)] ["--password" p])]
    (vec
     (for [[k fa fb] (map vector (range) files-a files-b)
           [command extra] [["info" []] ["text" []]
                            ["render" ["--pages" reopen-render-pages "--out-dir" :out-dir]]]
           :let [run (fn [impl file tag]
                       (let [dir (str case-dir "/reopen-" k "-" command "-" tag)
                             args (resolve-args (into [command] (concat extra pw [file]))
                                                {:out-dir (str dir "/out")})]
                         (assoc (invoke impl args opts dir) :out-dir (str dir "/out"))))
                 ref (run jvm fa "pdfbox-reads-pdfbox")
                 jb (run jvm fb "pdfbox-reads-pdfcarton")
                 ba (run dotnet fa "pdfcarton-reads-pdfbox")
                 cmp (fn [x what]
                       (let [res (classify-pair command ref x (:out-dir ref) (:out-dir x))]
                         (assoc res :check what
                                :class (case (:class res)
                                         :pdfcarton-fails (if (= what "pdfbox-reads-pdfcarton")
                                                            :different :pdfcarton-fails)
                                         :pdfbox-fails :different
                                         (:class res)))))]]
       {:file k :command command
        :pdfbox-reads-pdfcarton (cmp jb "pdfbox-reads-pdfcarton")
        :pdfcarton-reads-pdfbox (cmp ba "pdfcarton-reads-pdfbox")}))))

(def class-order [:equal :fairly-close :different :pdfcarton-fails :pdfbox-fails :both-fail])

(defn- worst-class [classes]
  (let [rank (zipmap [:equal :fairly-close :different :pdfcarton-fails] (range))]
    (reduce (fn [a b] (if (> (rank b -1) (rank a -1)) b a)) :equal classes)))

(defn run-case
  "Runs one case on both implementations and classifies it."
  [{:keys [jvm dotnet] :as impls} c opts case-dir]
  (let [c (if (some #{:values} (:args c))
            (assoc c :values-file (form-values! jvm (first (:docs c)) opts (str case-dir "/values")))
            c)
        a (invoke-case jvm c opts case-dir [])
        b (invoke-case dotnet c opts case-dir [])
        base (classify-pair (:command c) a b (:out-dir a) (:out-dir b))
        reopen (when (and (:write c) (= :ok (:status a) (:status b)))
                 (reopen-checks impls c a b opts case-dir))
        reopen-classes (mapcat (juxt (comp :class :pdfbox-reads-pdfcarton)
                                     (comp :class :pdfcarton-reads-pdfbox)) reopen)
        klass (if (#{:equal :fairly-close :different} (:class base))
                (worst-class (cons (:class base) reopen-classes))
                (:class base))
        bad-reopen (for [ro reopen
                         side [:pdfbox-reads-pdfcarton :pdfcarton-reads-pdfbox]
                         :let [x (side ro)] :when (not= :equal (:class x))]
                     {:check (name side) :file (:file ro) :command (:command ro)
                      :class (:class x) :detail (:detail x)})
        values (when-let [f (:values-file c)] (slurp f))]
    (delete-tree! (io/file case-dir))
    {:id (:id c)
     :command (:command c)
     :variant (:variant c)
     :docs (mapv doc-label (:docs c))
     :class klass
     :severity (+ (or (:severity base) 0) (* 10 (count bad-reopen)))
     :detail (cond-> (or (:detail base) {})
               (seq bad-reopen) (assoc :reopen (vec (take 6 bad-reopen)))
               values (assoc :values (clip values 300)))
     :pdfbox (run-summary a)
     :pdfcarton (run-summary b)
     :repro {:pdfbox (repro jvm (:args c) "/tmp/pdfcli-repro/pdfbox")
             :pdfcarton (repro dotnet (:args c) "/tmp/pdfcli-repro/pdfcarton")}}))

;; ---------------------------------------------------------------- performance

(defn perf-docs [subset? docs]
  (let [want (if subset?
               subset-docs
               (concat subset-docs
                       [(str r "input/cweb.pdf")
                        (str r "input/sample_fonts_solidconvertor.pdf")
                        (str r "input/PDFBOX-3042-003177-p2.pdf")
                        (str r "org/apache/pdfbox/encryption/PasswordSample-256bit.pdf")
                        (str r "org/apache/pdfbox/pdmodel/with_outline.pdf")
                        (str r "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf")
                        (str r "org/apache/pdfbox/pdmodel/common/testPDF_multiFormatEmbFiles.pdf")
                        (str r "org/apache/pdfbox/pdmodel/interactive/form/PDFBOX-5784.pdf")
                        (str r "org/apache/pdfbox/pdmodel/graphics/image/ccittg4.pdf")
                        "research/pdfbox/tools/src/test/resources/input/ImageIOUtil/JPXTestRGB.pdf"
                        "validation-output/pdfcarton-cli/corpus/large-cweb-x12.pdf"
                        "validation-output/pdfcarton-cli/corpus/large-mixed.pdf"]))]
    (filterv (set docs) want)))

(defn perf-cases [subset? docs writes]
  (concat
   (for [doc (perf-docs subset? docs)
         :let [pw (pw-args doc)
               c (fn [command variant args] {:command command :variant variant :docs [doc]
                                             :args (into [command] (concat args pw [doc]))})]
         x [(c "info" "default" [])
            (c "text" "default" [])
            (c "render" "rgb-72dpi-no-write" ["--pages" "1-3" "--no-write"])
            (c "images" "default" ["--pages" "1-3"])
            (c "fonts" "default" [])
            (c "outline" "default" [])
            (c "annotations" "default" [])
            (c "form-fields" "default" [])
            (c "attachments" "default" [])]]
     x)
   (->> writes (group-by :command) vals (map first))))

(defn- timing-of [r]
  (when (= :ok (:status r))
    {:median-ms (get-in r [:env "timing" "medianMs"])
     :median-alloc (get-in r [:env "timing" "medianAllocatedBytes"])}))

(defn run-perf-case
  "Cold: median process wall time of plain invocations. Warm: one timing-mode
  invocation's median. Implementations alternate; nothing else runs."
  [{:keys [jvm dotnet]} c {:keys [warmup repeat cold-runs perf-timeout-s] :as opts} case-dir]
  (let [c (if (some #{:values} (:args c))
            (assoc c :values-file (form-values! jvm (first (:docs c)) opts (str case-dir "/values")))
            c)
        popts (assoc opts :timeout-s perf-timeout-s)
        colds (for [_ (range cold-runs) impl [jvm dotnet]]
                [(:name impl) (invoke-case impl c popts case-dir [])])
        cold (fn [impl] (let [rs (map second (filter #(= (:name impl) (first %)) colds))]
                          (when (every? #(= :ok (:status %)) rs) (median (map :wall-ms rs)))))
        cold-a (cold jvm) cold-b (cold dotnet)
        warm-args ["--warmup" (str warmup) "--repeat" (str repeat)]
        wa (timing-of (invoke-case jvm c popts case-dir warm-args))
        wb (timing-of (invoke-case dotnet c popts case-dir warm-args))
        result {:id (:id c) :command (:command c) :variant (:variant c) :docs (mapv doc-label (:docs c))
                :cold-ms [(round3 cold-a) (round3 cold-b)] :cold-ratio (ratio cold-a cold-b)
                :warm-ms [(:median-ms wa) (:median-ms wb)] :warm-ratio (ratio (:median-ms wa) (:median-ms wb))
                :alloc-bytes [(:median-alloc wa) (:median-alloc wb)]
                :alloc-ratio (ratio (:median-alloc wa) (:median-alloc wb))
                :both-succeed (boolean (and cold-a cold-b wa wb))
                :repro {:pdfbox (repro jvm (into (:args c) warm-args) "/tmp/pdfcli-repro/pdfbox")
                        :pdfcarton (repro dotnet (into (:args c) warm-args) "/tmp/pdfcli-repro/pdfcarton")}}]
    (delete-tree! (io/file case-dir))
    (assoc result :important (boolean (and (:both-succeed result)
                                           (some #(and % (>= % 5.0)) [(:cold-ratio result) (:warm-ratio result)]))))))

;; ---------------------------------------------------------------- setup

(defn- sh! [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " (str/join " " (take 6 args)) "\n"
                           (clip (str out "\n" err) 3000)) {:exit exit})))
    out))

(defn- font-cache-dir [out impl-name]
  (let [d (io/file out "font-cache" impl-name)]
    (.mkdirs d)
    (.getAbsolutePath d)))

(defn jvm-impl!
  "The PDFBox CLI, AOT-compiled once so that cold time measures PDFBox, not
  Clojure source loading. Same classpath as clojure -M:pdfcli. Its font cache
  lives in its own directory (pdfbox.fontcache), apart from PdfCarton's."
  [out]
  (let [cp (str/trim (sh! "clojure" "-Spath" "-M:pdfcli"))
        classes (io/file out "classes")
        _ (delete-tree! classes)
        _ (.mkdirs classes)
        full-cp (str (.getPath classes) File/pathSeparator cp)]
    (sh! "java" "-Djava.awt.headless=true" (str "-Dclojure.compile.path=" (.getPath classes))
         "-cp" full-cp "clojure.main" "-e" "(compile 'pdfcli.main)")
    {:name "pdfbox-jvm"
     :cmd ["java" "-Djava.awt.headless=true" "-Xmx4g"
           (str "-Dpdfbox.fontcache=" (font-cache-dir out "pdfbox-jvm"))
           "-cp" full-cp "clojure.main" "-m" "pdfcli.main"]
     :repro "clojure -M:pdfcli"}))

(defn dotnet-impl!
  "The PdfCarton CLI, built against pdfcarton-root. PdfCarton reads user.home
  from HOME, so HOME points at its own font cache directory: the two
  implementations rewrite each other's ~/.pdfbox.cache otherwise."
  [{:keys [pdfcarton-root out]}]
  (sh! "dotnet" "build" "validation/pdfcarton-cli/dotnet" "-c" "Release" "-warnaserror"
       "-p:NuGetAudit=false" (str "-p:PdfCartonRoot=" (.getAbsolutePath (io/file pdfcarton-root))))
  {:name "pdfcarton-dotnet"
   :cmd ["dotnet" dotnet-dll]
   :env {"HOME" (font-cache-dir out "pdfcarton-dotnet")}
   :repro (str "dotnet " dotnet-dll)})

(def font-cache-primer
  "A document whose text extraction makes both libraries scan the system fonts."
  (str r "org/apache/pdfbox/pdmodel/interactive/form/AcroFormsBasicFields.pdf"))

(defn prime-font-caches!
  "Builds each implementation's font cache from empty, serially, and returns
  the cold run as a performance row (the cache build dominates it)."
  [{:keys [jvm dotnet]} out opts]
  (let [run (fn [impl]
              (doseq [^File f (.listFiles (io/file (font-cache-dir out (:name impl))))] (.delete f))
              (invoke impl ["text" font-cache-primer] (assoc opts :timeout-s 900)
                      (str out "/work/prime-" (:name impl))))
        a (run jvm) b (run dotnet)
        ok (= :ok (:status a) (:status b))
        cold-ratio (ratio (:wall-ms a) (:wall-ms b))]
    {:id (str "text/font-cache-build/" (doc-label font-cache-primer))
     :command "text" :variant "font-cache-build" :docs [(doc-label font-cache-primer)]
     :cold-ms [(round3 (:wall-ms a)) (round3 (:wall-ms b))] :cold-ratio cold-ratio
     :warm-ms [nil nil] :warm-ratio nil :alloc-bytes [nil nil] :alloc-ratio nil
     :both-succeed ok
     :important (boolean (and ok cold-ratio (>= cold-ratio 5.0)))
     :repro {:pdfbox (str "rm -f ~/.pdfbox.cache && clojure -M:pdfcli text " font-cache-primer)
             :pdfcarton (str "rm -f ~/.pdfbox.cache && dotnet " dotnet-dll " text " font-cache-primer)}}))

(defn- build-large-docs! [jvm out opts]
  (let [dir (io/file out "corpus")]
    (.mkdirs dir)
    (vec (for [[name inputs] (sort large-docs)
               :let [file (str (io/file dir name))
                     r (invoke jvm (into ["merge" "--out" file] inputs) opts (str out "/work/build-" name))]]
           (if (= :ok (:status r))
             {:path file :generated (str "PDFBox merge of " (count inputs) " corpus files")
              :pages (get-in r [:env "result" "pageCount"])}
             (do (println "Could not build" name (:status r) (:code r)) nil))))))

(defn- git-head []
  (str/trim (:out (shell/sh "git" "rev-parse" "HEAD"))))

;; ---------------------------------------------------------------- summary

(defn- totals [cases]
  (let [by (group-by :command cases)]
    (into (sorted-map)
          (for [[command cs] by]
            [command (merge (zipmap class-order (repeat 0)) (frequencies (map :class cs)))]))))

(def problem-rank {:pdfcarton-fails 0 :different 1 :both-fail 2})

(defn- error-code-mismatch? [c]
  (and (= :both-fail (:class c)) (not (get-in c [:detail :same]))))

(defn correctness-problems
  "PdfCarton-fails, different (which includes renders that are not fairly
  close), then both-fail cases whose error codes differ, ranked: failures
  first (timeouts and crashes before error envelopes), then by severity."
  [cases]
  (->> cases
       (filter #(or (#{:pdfcarton-fails :different} (:class %)) (error-code-mismatch? %)))
       (sort-by (fn [c] [(problem-rank (:class c))
                         (if (#{:timeout :crash} (get-in c [:pdfcarton :status])) 0 1)
                         (- (double (:severity c 0)))
                         (:id c)]))))

(defn important-regressions [perf]
  (->> perf (filter :important)
       (sort-by #(- (double (max (or (:cold-ratio %) 0) (or (:warm-ratio %) 0)))))))

(defn- md-cell [s] (str/replace (str s) "|" "\\|"))

(defn- problem-line [c]
  (let [d (:detail c)]
    (cond
      (= :pdfcarton-fails (:class c))
      (str "PdfCarton " (name (or (:pdfcarton d) "fails")) (when (:code d) (str " " (:code d)))
           (when (:message d) (str ": " (clip (:message d) 160))))
      (= :both-fail (:class c))
      (str "both fail with different error codes: PDFBox " (name (first (:codes d)))
           ", PdfCarton " (name (second (:codes d))))
      (:render d) (let [m (:render d)]
                    (format "render: worst page mean %.3f/255, %.4f%% px >32; %d of %d pages not fairly close%s"
                            (double (:max-mean-abs m)) (* 100.0 (:max-fraction-over-32 m))
                            (:pages-not-fairly-close m) (:pages m)
                            (if (pos? (:size-mismatches m)) (str ", " (:size-mismatches m) " size mismatches") "")))
      (:pixels d) (let [m (:pixels d)]
                    (format "images: %d of %d decoded hashes differ; worst mean %.3f/255, %.4f%% px >32 (%s)"
                            (:hash-differences d) (:images d) (double (:max-mean-abs m))
                            (* 100.0 (:max-fraction-over-32 m)) (str/join ", " (:names d))))
      (contains? d :text-exact)
      (str "text: " (:line-differences d 0) " differing lines, chars " (:text-chars d)
           (when-let [f (:first-difference d)] (str "; first at " (:offset f) ": " (clip (:pdfbox f) 70) " vs " (clip (:pdfcarton f) 70)))
           (when (:diff-count d) (str "; " (:diff-count d) " JSON differences")))
      (:diffs d) (str (:diff-count d) " JSON differences, e.g. "
                      (str/join "; " (for [x (take 2 (:diffs d))]
                                       (str (:path x) ": " (clip (:pdfbox x) 60) " vs " (clip (:pdfcarton x) 60)))))
      (:reopen d) (str "reopen: "
                       (str/join "; " (for [x (take 2 (:reopen d))]
                                        (str (:check x) " " (:command x) " " (name (:class x))
                                             (when-let [m (get-in x [:detail :render])]
                                               (format " (mean %.3f, %.4f%% >32)" (double (:max-mean-abs m))
                                                       (* 100.0 (:max-fraction-over-32 m))))
                                             (when-let [ds (get-in x [:detail :diffs])]
                                               (str " (" (:path (first ds)) ": " (clip (:pdfbox (first ds)) 50)
                                                    " vs " (clip (:pdfcarton (first ds)) 50) ")"))))))
      :else (clip (pr-str d) 200))))

(defn summary-markdown [{:keys [meta corpus cases perf]}]
  (let [t (totals cases)
        regs (important-regressions perf)
        probs (correctness-problems cases)
        fmt-ms (fn [x] (if x (format "%.1f" (double x)) "-"))
        fmt-r (fn [x] (if x (format "%.2f" (double x)) "-"))]
    (str/join
     "\n"
     (concat
      ["# pdfcli comparison: PdfCarton vs PDFBox 3.0.8" ""
       (str "- Commit `" (:commit meta) "`, started " (:started meta) ", " (format "%.1f" (/ (:elapsed-s meta) 60.0)) " min"
            (when (:subset meta) " (**--subset**)"))
       (str "- Corpus: " (count corpus) " documents, " (reduce + (map :bytes corpus)) " bytes ("
            (count (filter :generated corpus)) " generated large documents)")
       (str "- Cases: " (count cases) " correctness, " (count perf) " performance (warm: --warmup "
            (:warmup meta) " --repeat " (:repeat meta) "; cold: median of " (:cold-runs meta) " process runs)")
       (str "- Tolerance: render fairly close = mean abs channel diff <= 2.0/255 and <= 1% of pixels >32; "
            "important regression = warm or cold ratio (PdfCarton / PDFBox) >= 5 where both succeed")
       "" "## Totals per command" ""
       (str "| command | " (str/join " | " (map name class-order)) " | total |")
       (str "|---|" (str/join "|" (repeat (inc (count class-order)) "---:")) "|")]
      (for [[command m] t]
        (str "| " command " | " (str/join " | " (map m class-order)) " | " (reduce + (vals m)) " |"))
      [(str "| **all** | " (str/join " | " (for [k class-order] (reduce + (map #(get % k 0) (vals t)))))
            " | " (count cases) " |")
       ""
       (let [ws (filter #(and (not= :both-fail (:class %)) (= :fairly-close (:class %)) (= "text" (:command %))) cases)]
         (str "Text cases equal only after whitespace normalisation (counted as fairly-close): " (count ws)
              ". Both-fail cases with different error codes: "
              (count (filter #(and (= :both-fail (:class %)) (not (get-in % [:detail :same]))) cases)) "."))
       "" (str "## Important performance regressions (" (count regs) ")") ""
       "| # | command | variant | document | warm ms PDFBox | warm ms PdfCarton | warm ratio | cold ms PDFBox | cold ms PdfCarton | cold ratio | alloc ratio |"
       "|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|"]
      (map-indexed (fn [i p]
                     (str "| " (inc i) " | " (:command p) " | " (:variant p) " | " (md-cell (str/join " + " (:docs p)))
                          " | " (fmt-ms (first (:warm-ms p))) " | " (fmt-ms (second (:warm-ms p))) " | " (fmt-r (:warm-ratio p))
                          " | " (fmt-ms (first (:cold-ms p))) " | " (fmt-ms (second (:cold-ms p))) " | " (fmt-r (:cold-ratio p))
                          " | " (fmt-r (:alloc-ratio p)) " |"))
                   regs)
      ["" "Reproduction (both CLIs, timing mode):" ""]
      (map-indexed (fn [i p] (str (inc i) ". `" (get-in p [:repro :pdfbox]) "`<br>`" (get-in p [:repro :pdfcarton]) "`"))
                   regs)
      ["" (str "## Correctness problems (" (count probs) ")") ""
       "| # | class | command | variant | document | finding |" "|---:|---|---|---|---|---|"]
      (map-indexed (fn [i c] (str "| " (inc i) " | " (name (:class c)) " | " (:command c) " | " (:variant c) " | "
                                  (md-cell (str/join " + " (:docs c))) " | " (md-cell (problem-line c)) " |"))
                   probs)
      ["" "Reproduction (both CLIs):" ""]
      (map-indexed (fn [i c] (str (inc i) ". `" (get-in c [:repro :pdfbox]) "`<br>`" (get-in c [:repro :pdfcarton]) "`"))
                   probs)
      ["" "## All performance cases" ""
       "| command | variant | document | warm ms PDFBox | warm ms PdfCarton | warm ratio | cold ratio | alloc ratio | both ok |"
       "|---|---|---|---:|---:|---:|---:|---:|---|"]
      (for [p (sort-by #(- (double (max (or (:cold-ratio %) 0) (or (:warm-ratio %) 0)))) perf)]
        (str "| " (:command p) " | " (:variant p) " | " (md-cell (str/join " + " (:docs p))) " | "
             (fmt-ms (first (:warm-ms p))) " | " (fmt-ms (second (:warm-ms p))) " | " (fmt-r (:warm-ratio p)) " | "
             (fmt-r (:cold-ratio p)) " | " (fmt-r (:alloc-ratio p)) " | " (:both-succeed p) " |"))
      [""]))))

;; ---------------------------------------------------------------- driver

(defn- parse-cli [args]
  (loop [opts default-options [a & more :as args] args]
    (cond (empty? args) opts
          (= a "--subset") (recur (assoc opts :subset true) more)
          (= a "--no-perf") (recur (assoc opts :perf false) more)
          (#{"--jobs" "--timeout" "--perf-timeout" "--warmup" "--repeat" "--cold-runs"} a)
          (recur (assoc opts ({"--jobs" :jobs "--timeout" :timeout-s "--perf-timeout" :perf-timeout-s
                               "--warmup" :warmup "--repeat" :repeat "--cold-runs" :cold-runs} a)
                        (Long/parseLong (first more)))
                 (rest more))
          (#{"--out" "--pdfcarton-root"} a)
          (recur (assoc opts ({"--out" :out "--pdfcarton-root" :pdfcarton-root} a) (first more)) (rest more))
          :else (throw (ex-info (str "Unknown option " a) {})))))

(defn- slug [s] (str/replace s #"[^A-Za-z0-9._-]+" "_"))

(defn- with-ids [prefix cases]
  (vec (map-indexed (fn [i c]
                      (let [id (str (:command c) "/" (:variant c) "/" (str/join "+" (map doc-label (:docs c))))]
                        (assoc c :id id :id-slug (clip (slug id) 120) :n (format "%s%05d" prefix i))))
                    cases)))

(defn- parallel-map [jobs f xs]
  (let [pool (Executors/newFixedThreadPool (int jobs))]
    (try (let [futures (mapv (fn [x] (.submit pool ^Callable (fn [] (f x)))) xs)]
           (mapv #(.get ^Future %) futures))
         (finally (.shutdownNow pool)))))

(defn run-comparison
  "Runs the whole comparison and returns the results map. impls overrides
  {:jvm :dotnet} (the self-check passes PDFBox for both); docs overrides the
  corpus."
  [opts & {:keys [impls docs]}]
  (let [{:keys [out subset jobs perf] :as opts} (merge default-options opts)
        started (Instant/now)
        t0 (System/nanoTime)
        work (str out "/work")
        _ (delete-tree! (io/file work))
        _ (.mkdirs (io/file work))
        impls (or impls {:jvm (jvm-impl! out) :dotnet (dotnet-impl! opts)})
        primer (prime-font-caches! impls out opts)
        large (when-not (or subset docs) (filterv some? (build-large-docs! (:jvm impls) out opts)))
        doc-list (vec (or docs (concat (if subset subset-docs (upstream-corpus)) (map :path large))))
        corpus (vec (for [d doc-list]
                      (merge {:path (doc-label d) :bytes (.length (io/file d))}
                             (when-let [l (first (filter #(= d (:path %)) large))] (dissoc l :path)))))
        reads (mapcat #(read-cases % (contains? render-variant-docs %)) doc-list)
        writes (write-cases (boolean (or subset docs)))
        cases (with-ids "c" (concat reads writes))
        _ (println (format "pdfcli compare: %d documents, %d correctness cases, %d jobs" (count doc-list) (count cases) jobs))
        progress (atom 0)
        results (parallel-map jobs
                              (fn [c]
                                (let [res (try (run-case impls c opts (str work "/" (:n c)))
                                               (catch Throwable e
                                                 {:id (:id c) :command (:command c) :variant (:variant c)
                                                  :docs (mapv doc-label (:docs c)) :class :harness-error
                                                  :detail {:exception (clip (str e) 500)}}))
                                      n (swap! progress inc)]
                                  (when (zero? (mod n 100)) (println (format "  %d/%d cases" n (count cases))))
                                  res))
                              cases)
        pcases (when perf (with-ids "p" (perf-cases subset doc-list (if docs writes (write-cases subset)))))
        _ (when perf (println (format "pdfcli compare: %d performance cases, serial" (count pcases))))
        perf-results (vec (for [c pcases]
                            (try (run-perf-case impls c opts (str work "/" (:n c)))
                                 (catch Throwable e {:id (:id c) :command (:command c) :variant (:variant c)
                                                     :docs (mapv doc-label (:docs c)) :error (clip (str e) 500)}))))]
    (delete-tree! (io/file work))
    {:meta {:commit (git-head) :started (str started)
            :elapsed-s (round3 (/ (- (System/nanoTime) t0) 1.0e9))
            :subset subset :jobs jobs :timeout-s (:timeout-s opts) :perf-timeout-s (:perf-timeout-s opts)
            :warmup (:warmup opts) :repeat (:repeat opts) :cold-runs (:cold-runs opts)
            :implementations (into {} (for [[k v] impls] [k (select-keys v [:name :cmd])]))
            :tolerance tolerance}
     :corpus corpus
     :cases results
     :perf (cond-> perf-results perf (conj primer))}))

(defn write-results! [out results]
  (.mkdirs (io/file out))
  (write-json (io/file out "results.json") results)
  (spit (io/file out "summary.md") (summary-markdown results)))

(defn -main [& args]
  (let [opts (parse-cli args)
        results (run-comparison opts)]
    (write-results! (:out opts) results)
    (println (str "Wrote " (:out opts) "/results.json and " (:out opts) "/summary.md"))
    (shutdown-agents)
    (System/exit 0)))
