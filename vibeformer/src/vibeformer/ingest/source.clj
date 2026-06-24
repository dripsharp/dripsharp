(ns vibeformer.ingest.source
  (:require [clojure.string :as str]
            [datomic.client.api :as d])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path Paths)
           (java.security MessageDigest)))

(def ^:private package-pattern
  #"(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*;?")

(defn- path [value]
  (if (instance? Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- normalize-path [value]
  (.normalize (.toAbsolutePath (path value))))

(defn- slash-path [^Path value]
  (str/replace (str value) \\ \/))

(defn- relative-path [^Path root ^Path file]
  (slash-path (.relativize root file)))

(defn- lang [^Path file]
  (let [name (str/lower-case (str (.getFileName file)))]
    (cond
      (str/ends-with? name ".java") :lang/java
      (str/ends-with? name ".kt") :lang/kotlin)))

(defn- source-file? [^Path file]
  (and (Files/isRegularFile file (make-array java.nio.file.LinkOption 0))
       (some? (lang file))))

(defn- hex-bytes [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256 [bytes]
  (str "sha256:" (hex-bytes (.digest (doto (MessageDigest/getInstance "SHA-256")
                                       (.update bytes))))))

(defn- package-name [source]
  (second (re-find package-pattern (str/replace source #"^\uFEFF" ""))))

(defn- project-config [{:source/keys [root]
                        :project/keys [id name]}]
  (let [root (normalize-path root)
        inferred-id (some-> root .getFileName str)]
    {:project/id (or id inferred-id "source")
     :project/name (or name id inferred-id "source")
     :project/root (str root)
     :source/root root}))

(defn discover-source-paths
  "Return sorted Java and Kotlin source paths below source-root."
  [source-root]
  (let [root (normalize-path source-root)]
    (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator paths))
           (filter source-file?)
           (map normalize-path)
           (sort-by #(relative-path root %))
           vec))))

(defn source-file-facts
  "Discover source files and return project/file facts for Datomic ingestion."
  [opts]
  (let [{:project/keys [id]
         :source/keys [root]
         :as project} (project-config opts)]
    (mapv (fn [file]
            (let [bytes (Files/readAllBytes file)
                  package (package-name (String. bytes StandardCharsets/UTF_8))
                  rel-path (relative-path root file)
                  fact (cond-> {:file/id (str id ":" rel-path)
                                :file/path rel-path
                                :file/lang (lang file)
                                :file/hash (sha256 bytes)
                                :file/project [:project/id id]}
                         package (assoc :file/package package))]
              fact))
          (discover-source-paths root))))

(defn- project-fact [project]
  (select-keys project [:project/id :project/name :project/root]))

(defn- project-temp-fact [project]
  (assoc (project-fact project) :db/id "project"))

(defn- current-project [db project-id]
  (d/pull db [:project/id :project/name :project/root] [:project/id project-id]))

(defn- current-file [db file-id]
  (let [file (d/pull db [:file/id
                         :file/path
                         :file/lang
                         :file/hash
                         :file/package
                         {:file/project [:project/id]}]
                     [:file/id file-id])]
    (when (:file/id file)
      file)))

(defn- same-file-fact? [current fact]
  (= (select-keys current [:file/id :file/path :file/lang :file/hash :file/package])
     (select-keys fact [:file/id :file/path :file/lang :file/hash :file/package])))

(defn- package-retraction [current fact]
  (when (and (:file/package current)
             (not (:file/package fact)))
    [:db/retract [:file/id (:file/id fact)] :file/package (:file/package current)]))

(defn- changed-file-tx-data [db facts]
  (mapcat (fn [fact]
            (let [current (current-file db (:file/id fact))]
              (when-not (same-file-fact? current fact)
                (cond-> [fact]
                  (package-retraction current fact)
                  (conj (package-retraction current fact))))))
          facts))

(defn ingest!
  "Transact project and Java/Kotlin file facts into Datomic.

  Options:
  - :source/root root directory to scan
  - :project/id stable project identity, defaulting to the root directory name
  - :project/name display name, defaulting to :project/id"
  [conn opts]
  (let [{:project/keys [id]
         :as project} (project-config opts)
        files (source-file-facts (assoc opts :source/root (:source/root project)
                                             :project/id id
                                             :project/name (:project/name project)))
        db (d/db conn)
        project-changed? (not= (project-fact project)
                               (current-project db id))
        file-tx (vec (changed-file-tx-data db files))
        tx-data (cond-> []
                  (or project-changed? (seq file-tx))
                  (conj (project-temp-fact project))

                  true
                  (into (map #(if (map? %)
                                (assoc % :file/project "project")
                                %)
                             file-tx)))
        transacted-files (count (filter map? file-tx))]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    {:project (project-fact project)
     :discovered-files (count files)
     :transacted-files transacted-files
     :skipped-files (- (count files) transacted-files)}))
