#!/usr/bin/env bb

(ns serve-assessment
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import [java.net URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.time Instant]))

(def allowed-question-types #{:single :multi :text :boolean})
(def max-request-bytes (* 1024 1024))

(defn fail! [message data]
  (throw (ex-info message data)))

(defn nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn parse-positive-int [label value allow-zero?]
  (try
    (let [parsed (Long/parseLong value)]
      (when-not (if allow-zero? (<= 0 parsed) (< 0 parsed))
        (fail! (str label " must be " (if allow-zero? "non-negative" "positive"))
               {:value value}))
      parsed)
    (catch NumberFormatException _
      (fail! (str label " must be an integer") {:value value}))))

(defn parse-args [args]
  (loop [remaining args options {:port 0 :timeout-minutes 30}]
    (if (empty? remaining)
      (do
        (doseq [required [:input :output]]
          (when-not (nonblank? (get options required))
            (fail! (str "Missing required option --" (name required)) {})))
        options)
      (let [[flag value & tail] remaining]
        (when-not value
          (fail! (str "Missing value for " flag) {}))
        (case flag
          "--input" (recur tail (assoc options :input value))
          "--output" (recur tail (assoc options :output value))
          "--port" (recur tail (assoc options :port (parse-positive-int "port" value true)))
          "--timeout-minutes"
          (recur tail (assoc options :timeout-minutes
                             (parse-positive-int "timeout-minutes" value false)))
          (fail! (str "Unknown option " flag) {}))))))

(defn exact-keys! [subject value required optional]
  (when-not (map? value)
    (fail! (str subject " must be a map") {:value value}))
  (let [actual (set (keys value))
        allowed (into required optional)]
    (when-let [missing (seq (remove actual required))]
      (fail! (str subject " is missing fields") {:missing (vec missing)}))
    (when-let [unknown (seq (remove allowed actual))]
      (fail! (str subject " has unknown fields") {:unknown (vec unknown)})))
  value)

(defn string-field! [subject value]
  (when-not (nonblank? value)
    (fail! (str subject " must be nonblank text") {:value value}))
  value)

(defn validate-option! [question-id option]
  (exact-keys! (str "Option in " question-id) option
               #{:value :label} #{:description :recommended?})
  (string-field! "Option value" (:value option))
  (string-field! "Option label" (:label option))
  (when (contains? option :description)
    (string-field! "Option description" (:description option)))
  (when-not (or (nil? (:recommended? option))
                (boolean? (:recommended? option)))
    (fail! "Option recommended? must be boolean" {:question question-id}))
  option)

(defn validate-question! [question]
  (exact-keys! "Question" question #{:id :type :prompt}
               #{:description :required? :options :placeholder})
  (let [id (string-field! "Question id" (:id question))
        type (:type question)
        required? (:required? question false)]
    (when-not (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" id)
      (fail! "Question id must be lowercase kebab-case" {:id id}))
    (when-not (contains? allowed-question-types type)
      (fail! "Question type is unsupported" {:id id :type type}))
    (string-field! "Question prompt" (:prompt question))
    (when-not (boolean? required?)
      (fail! "Question required? must be boolean" {:id id}))
    (when (contains? question :description)
      (string-field! "Question description" (:description question)))
    (when (contains? question :placeholder)
      (string-field! "Question placeholder" (:placeholder question)))
    (if (contains? #{:single :multi} type)
      (let [options (:options question)]
        (when-not (and (vector? options) (seq options))
          (fail! "Choice question requires a nonempty option vector" {:id id}))
        (doseq [option options] (validate-option! id option))
        (let [values (map :value options)]
          (when-not (= (count values) (count (distinct values)))
            (fail! "Question option values must be unique" {:id id})))
        (when (< 1 (count (filter :recommended? options)))
          (fail! "Question may recommend at most one option" {:id id})))
      (when (contains? question :options)
        (fail! "Text and boolean questions cannot declare options" {:id id})))
    question))

(defn validate-section! [section]
  (exact-keys! "Section" section #{:id :title :questions}
               #{:description :evidence})
  (let [id (string-field! "Section id" (:id section))]
    (when-not (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" id)
      (fail! "Section id must be lowercase kebab-case" {:id id})))
  (string-field! "Section title" (:title section))
  (when (contains? section :description)
    (string-field! "Section description" (:description section)))
  (when-let [evidence (:evidence section)]
    (when-not (and (vector? evidence) (every? nonblank? evidence))
      (fail! "Section evidence must be a vector of nonblank strings"
             {:section (:id section)})))
  (when-not (and (vector? (:questions section)) (seq (:questions section)))
    (fail! "Section questions must be a nonempty vector" {:section (:id section)}))
  (doseq [question (:questions section)] (validate-question! question))
  section)

(defn validate-assessment! [assessment]
  (exact-keys! "Assessment" assessment
               #{:schema-version :assessment-id :title :candidate :summary
                 :sections}
               #{:eyebrow :recommendation :facts :findings :sources})
  (when-not (= 1 (:schema-version assessment))
    (fail! "Assessment schema-version must be 1"
           {:schema-version (:schema-version assessment)}))
  (string-field! "Assessment id" (:assessment-id assessment))
  (string-field! "Assessment title" (:title assessment))
  (string-field! "Assessment summary" (:summary assessment))
  (exact-keys! "Candidate" (:candidate assessment) #{:name}
               #{:version :revision :source-url :license})
  (string-field! "Candidate name" (get-in assessment [:candidate :name]))
  (doseq [[field value] (:candidate assessment) :when (not= field :name)]
    (string-field! (str "Candidate " (name field)) value))
  (when-let [recommendation (:recommendation assessment)]
    (exact-keys! "Recommendation" recommendation #{:title :body}
                 #{:tone})
    (string-field! "Recommendation title" (:title recommendation))
    (string-field! "Recommendation body" (:body recommendation))
    (when-not (contains? #{nil :positive :caution :critical :neutral}
                         (:tone recommendation))
      (fail! "Recommendation tone is unsupported" {:tone (:tone recommendation)})))
  (doseq [fact (:facts assessment)]
    (exact-keys! "Fact" fact #{:label :value} #{:note})
    (string-field! "Fact label" (:label fact))
    (string-field! "Fact value" (:value fact))
    (when (contains? fact :note) (string-field! "Fact note" (:note fact))))
  (doseq [finding (:findings assessment)]
    (exact-keys! "Finding" finding #{:title :body} #{:tone})
    (string-field! "Finding title" (:title finding))
    (string-field! "Finding body" (:body finding))
    (when-not (contains? #{nil :positive :caution :critical :neutral}
                         (:tone finding))
      (fail! "Finding tone is unsupported" {:tone (:tone finding)})))
  (doseq [source (:sources assessment)]
    (exact-keys! "Source" source #{:label :url} #{})
    (string-field! "Source label" (:label source))
    (string-field! "Source URL" (:url source)))
  (when-not (and (vector? (:sections assessment)) (seq (:sections assessment)))
    (fail! "Assessment sections must be a nonempty vector" {}))
  (doseq [section (:sections assessment)] (validate-section! section))
  (let [section-ids (map :id (:sections assessment))
        question-ids (map :id (mapcat :questions (:sections assessment)))]
    (when-not (= (count section-ids) (count (distinct section-ids)))
      (fail! "Section ids must be unique" {}))
    (when-not (= (count question-ids) (count (distinct question-ids)))
      (fail! "Question ids must be unique across the assessment" {})))
  assessment)

(defn read-assessment [path]
  (when-not (fs/regular-file? path)
    (fail! "Assessment input is missing" {:path path}))
  (try
    (validate-assessment! (edn/read-string (slurp path)))
    (catch Exception error
      (throw (ex-info (str "Invalid assessment input: " (.getMessage error))
                      {:path path} error)))))

(defn asset-root []
  (-> *file* fs/parent fs/parent (fs/path "assets") str))

(defn content-type [path]
  (cond
    (str/ends-with? path ".css") "text/css; charset=utf-8"
    (str/ends-with? path ".js") "text/javascript; charset=utf-8"
    :else "text/html; charset=utf-8"))

(defn response [status type body]
  {:status status
   :headers {"Content-Type" type
             "Cache-Control" "no-store"
             "X-Content-Type-Options" "nosniff"
             "Content-Security-Policy"
             "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"}
   :body (str body)})

(defn json-response [status value]
  (response status "application/json; charset=utf-8"
            (json/generate-string value)))

(defn query-params [raw-query]
  (if (str/blank? raw-query)
    {}
    (into {}
          (map (fn [part]
                 (let [[key value] (str/split part #"=" 2)]
                   [(URLDecoder/decode key "UTF-8")
                    (URLDecoder/decode (or value "") "UTF-8")]))
               (str/split raw-query #"&")))))

(defn authorized? [request token]
  (= token (get (query-params (:query-string request)) "token")))

(defn read-request-json [request]
  (let [bytes (with-open [input (:body request)] (.readAllBytes input))]
    (when (< max-request-bytes (count bytes))
      (fail! "Request body is too large" {:bytes (count bytes)}))
    (json/parse-string (String. bytes StandardCharsets/UTF_8))))

(defn question-index [assessment]
  (into {} (map (juxt :id identity)) (mapcat :questions (:sections assessment))))

(defn validate-answer! [question value]
  (let [id (:id question)
        type (:type question)
        required? (:required? question false)
        allowed (set (map :value (:options question)))]
    (case type
      :single
      (when-not (and (string? value)
                     (or (not required?) (nonblank? value))
                     (or (str/blank? value) (contains? allowed value)))
        (fail! "Invalid single-choice answer" {:question id :value value}))

      :multi
      (when-not (and (vector? value)
                     (= (count value) (count (distinct value)))
                     (every? allowed value)
                     (or (not required?) (seq value)))
        (fail! "Invalid multi-choice answer" {:question id :value value}))

      :text
      (when-not (and (string? value)
                     (<= (count value) 20000)
                     (or (not required?) (nonblank? value)))
        (fail! "Invalid text answer" {:question id}))

      :boolean
      (when-not (or (boolean? value) (and (not required?) (nil? value)))
        (fail! "Invalid boolean answer" {:question id :value value})))
    value))

(defn validate-submission! [assessment submission]
  (when-not (map? submission)
    (fail! "Submission must be an object" {}))
  (let [answers (get submission "answers")
        notes (get submission "notes" "")
        questions (question-index assessment)]
    (when-not (map? answers)
      (fail! "Submission answers must be an object" {}))
    (when-let [unknown (seq (remove (set (keys questions)) (keys answers)))]
      (fail! "Submission contains unknown question ids" {:unknown (vec unknown)}))
    (doseq [[id question] questions]
      (let [present? (contains? answers id)
            value (get answers id)]
        (when (and (:required? question false) (not present?))
          (fail! "Submission is missing a required answer" {:question id}))
        (when present? (validate-answer! question value))))
    (when-not (and (string? notes) (<= (count notes) 20000))
      (fail! "Submission notes must be text no longer than 20000 characters" {}))
    {:answers (into (sorted-map) answers) :notes notes}))

(defn atomic-write! [path value]
  (let [destination (fs/absolutize path)
        parent (or (fs/parent destination) (fs/path "."))]
    (fs/create-dirs parent)
    (let [temporary (Files/createTempFile (.toPath (fs/file parent))
                                          ".port-candidate-" ".edn"
                                          (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (str temporary) (str (pr-str value) "\n"))
        (try
          (Files/move temporary (.toPath (fs/file destination))
                      (into-array CopyOption
                                  [StandardCopyOption/ATOMIC_MOVE
                                   StandardCopyOption/REPLACE_EXISTING]))
          (catch Exception _
            (Files/move temporary (.toPath (fs/file destination))
                        (into-array CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING]))))
        (finally
          (Files/deleteIfExists temporary))))
    (str destination)))

(defn completion-record [assessment status fields]
  (merge {:schema-version 1
          :status status
          :assessment-id (:assessment-id assessment)
          :candidate (:candidate assessment)
          :completed-at (str (Instant/now))}
         fields))

(defn daemon-thread [runnable]
  (doto (Thread. runnable "port-candidate-server-worker")
    (.setDaemon true)))

(defn stop-soon! [stop-server completion value]
  (.start
   (daemon-thread
    (fn []
      (Thread/sleep 180)
      (stop-server :timeout 100)
      (deliver completion value)))))

(defn handler [f]
  (fn [request]
    (try
      (f request)
      (catch clojure.lang.ExceptionInfo error
        (json-response 400
                       {:ok false :error (.getMessage error)
                        :details (ex-data error)}))
      (catch Exception error
        (json-response 500
                       {:ok false :error "Internal server error"
                        :details {:message (.getMessage error)}})))))

(defn start-server! [{:keys [port timeout-minutes output]} assessment]
  (let [completion (promise)
        token (str (random-uuid))
        state (atom :running)
        assets (asset-root)
        serve-asset!
        (fn [file]
          (let [path (fs/path assets file)]
            (if (fs/regular-file? path)
              (response 200 (content-type file) (slurp (str path)))
              (response 404 "text/plain; charset=utf-8" "Not found"))))
        stop-server (atom nil)
        finish!
        (fn [status fields]
          (if (compare-and-set! state :running status)
            (let [path (atomic-write!
                        output (completion-record assessment status fields))]
              (println (str "PORT_CANDIDATE_" (str/upper-case (name status))
                            " " path))
              (flush)
              (stop-soon! @stop-server completion status)
              (json-response 200 {:ok true :status (name status)}))
            (json-response 409 {:ok false :error "Assessment is already closed"})))
        route
        (handler
         (fn [request]
           (let [method (:request-method request)
                 path (:uri request)]
             (cond
               (and (= method :get) (= path "/"))
               (if (authorized? request token)
                 (serve-asset! "app.html")
                 (response 403 "text/plain; charset=utf-8" "Forbidden"))

               (and (= method :get) (= path "/assets/style.css"))
               (serve-asset! "style.css")

               (and (= method :get) (= path "/assets/app.js"))
               (serve-asset! "app.js")

               (and (= method :get) (= path "/api/assessment"))
               (if (authorized? request token)
                 (json-response 200 assessment)
                 (json-response 403 {:ok false :error "Forbidden"}))

               (and (= method :post) (= path "/api/submit"))
               (if (authorized? request token)
                 (finish! :submitted
                          (validate-submission! assessment
                                                (read-request-json request)))
                 (json-response 403 {:ok false :error "Forbidden"}))

               (and (= method :post) (= path "/api/cancel"))
               (if (authorized? request token)
                 (finish! :cancelled {})
                 (json-response 403 {:ok false :error "Forbidden"}))

               :else
               (response 404 "text/plain; charset=utf-8" "Not found")))))
        server (http/run-server route {:ip "127.0.0.1" :port port})]
    (reset! stop-server server)
    (let [actual-port (:local-port (meta server))]
      (.start
       (daemon-thread
        (fn []
          (Thread/sleep (* timeout-minutes 60 1000))
          (when (compare-and-set! state :running :timed-out)
            (let [path (atomic-write!
                        output (completion-record assessment :timed-out {}))]
              (println (str "PORT_CANDIDATE_TIMED_OUT " path))
              (flush)
              (server :timeout 100)
              (deliver completion :timed-out))))))
      (println (str "PORT_CANDIDATE_UI_READY http://127.0.0.1:"
                    actual-port "/?token=" token))
      (println (str "PORT_CANDIDATE_OUTPUT " (fs/absolutize output)))
      (flush)
      @completion)))

(defn -main [& args]
  (try
    (let [options (parse-args args)
          assessment (read-assessment (:input options))]
      (start-server! options assessment))
    (catch Exception error
      (binding [*out* *err*]
        (println (str "PORT_CANDIDATE_ERROR " (.getMessage error)))
        (when-let [data (ex-data error)] (prn data)))
      (System/exit 1))))

(apply -main *command-line-args*)
