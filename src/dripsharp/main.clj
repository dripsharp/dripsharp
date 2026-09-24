(ns dripsharp.main
  (:require [clojure.string :as str]
            [dripsharp.authorship-portfolio :as authorship-portfolio]
            [dripsharp.differential-registry :as differential-registry]
            [dripsharp.java-compat-differential :as java-compat-differential]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.host-matrix :as pdfcube-host-matrix]
            [dripsharp.rebaseline :as rebaseline]
            [dripsharp.target-execution :as target-execution])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private usage
  (str
   "Usage: clojure -M:run "
   "generate|verify|pack|package <target> <profile>"
   "|differential <target> [validation-id]"
   "|proof <target> [--resume <run-id>]"
   "|product-sync <target> [--post-commit (PdfCarton only)]"
   "|product-preflight <target> [--live]"
   "|product-prepare <target> <branch> <commit-message>"
   "|authorship-report <pkl|pdfcube|sqltrellis|all>"
   "|java-compat-differential"
   "|differential-families [family]"
   "|pdfcube-family-host-matrix <evidence-root> <output-root>"
   "|rebaseline <pkl|pdfcube|rawhttp> [--approve <token>]"))

(defn- fail!
  [message exit]
  (binding [*out* *err*]
    (println message))
  (System/exit exit))

(defn dispatch!
  "Dispatches the public CLI without a registry of products or validations.
  Target-directory metadata owns all target-specific selections."
  [args]
  (let [[command target selector & extra] args]
    (cond
      (and (contains? #{"generate" "verify" "pack" "package"} command)
           target selector (empty? extra))
      (target-execution/run! (keyword command)
                             {:target target :profile selector})

      (and (= "differential" command)
           target
           (empty? extra))
      (target-execution/differential!
       (cond-> {:target target}
         selector (assoc :validation selector)))

      (and (= "proof" command) target
           (or (and (nil? selector) (empty? extra))
               (and (= "--resume" selector) (= 1 (count extra)))))
      (target-execution/proof!
       (cond-> {:target target} selector (assoc :resume-run (first extra))))

      (and (= "product-preflight" command)
           target (or (nil? selector) (= "--live" selector)) (empty? extra))
      (target-execution/prerequisites!
       (cond-> {:target target} selector (assoc :live-release? true)))

      (and (= "product-sync" command)
           target
           (or (nil? selector) (= "--post-commit" selector))
           (empty? extra))
      (target-execution/synchronize!
       (cond-> {:target target} selector (assoc :post-commit? true)))

      (and (= "product-prepare" command)
           target
           selector
           (= 1 (count extra)))
      (target-execution/prepare-publication!
       {:target target
        :branch selector
        :commit-message (first extra)})

      (and (= "authorship-report" command)
           target
           (nil? selector)
           (empty? extra))
      (authorship-portfolio/write! {:selection target})

      (and (= "java-compat-differential" command)
           (nil? target)
           (nil? selector)
           (empty? extra))
      (java-compat-differential/verify!)

      (and (= "differential-families" command)
           (nil? selector)
           (empty? extra))
      (differential-registry/print-registry! target)

      (and (= "pdfcube-family-host-matrix" command)
           target
           selector
           (empty? extra))
      (pdfcube-host-matrix/verify! target selector)

      (and (= "rebaseline" command)
           (or (= 2 (count args))
               (and (= 4 (count args))
                    (= "--approve" selector))))
      (rebaseline/run! (paths/workspace-root) (rest args))

      :else
      (throw (ex-info usage {:kind :invalid-command-line
                             :arguments (vec args)})))))

(defn prerequisite-details
  "Only actionable prerequisite fields are exposed; never dump arbitrary error
  data, environment values or command options into release diagnostics."
  [data]
  (when (contains? #{:release-prerequisite-failed
                     :product-repository-publication-failed} (:kind data))
    (let [details (select-keys data [:reason :path :subject :status :submodule-path
                                     :staged-paths :disposable-build-paths :correction
                                     :package :version :http-status :property])]
      (when (seq details) (str "\n" (pr-str details))))))

(defn -main
  [& args]
  (try
    (dispatch! args)
    (catch ExceptionInfo error
      (let [{:keys [output kind]} (ex-data error)]
        (fail! (str (if (= :invalid-command-line kind)
                      ""
                      "DripSharp command failed: ")
                    (.getMessage error)
                    (prerequisite-details (ex-data error))
                    (when-not (str/blank? output)
                      (str "\n" (str/trim output))))
               (if (= :invalid-command-line kind) 2 1))))
    (catch Throwable error
      (fail! (str "DripSharp command failed unexpectedly: "
                  (.getMessage error))
             1))))
