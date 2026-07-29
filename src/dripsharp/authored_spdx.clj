(ns dripsharp.authored-spdx
  "Decision-backed repository gate for authored SPDX and package publishers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dripsharp.authorship :as authorship]
            [dripsharp.paths :as paths]
            [dripsharp.target-directory :as target-directory])
  (:import [java.io PushbackReader StringReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :invalid-authored-spdx-gate))))

(def policy-path "config/authored-spdx.edn")
(def required-decision "pkl-c8t.2")
(def ^:private decision-policy-keys
  #{:schema-version :decision :license-identifier :file-copyright-text
    :repository-notice :package-publisher})

(defn- read-single-edn!
  [path text]
  (let [eof (Object.)
        [value trailing]
        (try
          (with-open [reader (PushbackReader. (StringReader. text))]
            [(edn/read {:eof eof} reader)
             (edn/read {:eof eof} reader)])
          (catch RuntimeException error
            (throw
             (ex-info "Approved SPDX policy file is not valid EDN"
                      {:kind :invalid-authored-spdx-gate
                       :path (str path)}
                      error))))]
    (when (identical? eof value)
      (fail! "Approved SPDX policy file is empty"
             {:path (str path)
              :reason :empty-policy}))
    (when-not (identical? eof trailing)
      (fail! "Approved SPDX policy file contains trailing EDN data"
             {:path (str path)
              :reason :trailing-data}))
    value))

(defn active-targets
  "Discovers every checked-in target manifest so the repository gate cannot
  silently omit a newly added product target."
  [workspace-root]
  (let [workspace-root (paths/absolute workspace-root)
        targets-root
        (paths/absolute (paths/resolve-path workspace-root "targets"))
        _ (when (and (paths/directory? targets-root)
                     (not (paths/real-contained?
                           workspace-root targets-root)))
            (fail! "Target inventory resolves outside the workspace"
                   {:path "targets"
                    :reason :outside-workspace}))
        candidates
        (when (paths/directory? targets-root)
          (with-open [entries (Files/list targets-root)]
            (let [entries
                  (->> (.toArray entries)
                       (map #(cast Path %))
                       vec)
                  invalid-entries
                  (->> entries
                       (filter
                        #(and (Files/isSymbolicLink ^Path %)
                              (not (paths/exists? %))))
                       (map #(str (.getFileName ^Path %)))
                       sort
                       vec)
                  _ (when (seq invalid-entries)
                      (fail! "Target inventory contains unresolved symbolic links"
                             {:targets invalid-entries
                              :reason :unresolved-symbolic-link}))
                  directories
                  (->> entries
                       (filter paths/directory?)
                       vec)
                  missing-manifests
                  (->> directories
                       (filter
                        (fn [^Path target-root]
                          (let [manifest
                                (paths/resolve-path target-root "target.edn")]
                            (not (or (paths/exists? manifest)
                                     (Files/isSymbolicLink manifest))))))
                       (map #(str (.getFileName ^Path %)))
                       sort
                       vec)
                  _ (when (seq missing-manifests)
                      (fail! "Target directories are missing target manifests"
                             {:targets missing-manifests
                              :reason :missing-target-manifest}))
                  candidates directories
                  invalid-manifests
                  (->> candidates
                       (remove
                        #(paths/regular-file?
                          (paths/resolve-path % "target.edn")))
                       (map #(str (.getFileName ^Path %)))
                       sort
                       vec)]
              (when (seq invalid-manifests)
                (fail! "Target manifests are not readable regular files"
                       {:targets invalid-manifests
                        :reason :invalid-target-manifest}))
              candidates)))
        escaped
        (->> candidates
             (keep
              (fn [^Path target-root]
                (let [manifest (paths/resolve-path target-root "target.edn")]
                  (when-not
                   (and (paths/real-contained? workspace-root target-root)
                        (paths/real-contained? workspace-root manifest))
                    (str (.getFileName target-root))))))
             sort
             vec)
        _ (when (seq escaped)
            (fail! "Target manifests resolve outside the workspace"
                   {:targets escaped
                    :reason :outside-workspace}))
        targets
        (->> candidates
             (map #(keyword (str (.getFileName ^Path %))))
             sort
             vec)]
    (when-not (seq targets)
      (fail! "No target manifests are available for the authored SPDX gate"
             {:targets-root (str targets-root)}))
    targets))

(defn- target-source-groups
  [target-contract]
  (mapcat
   #(vals (get-in target-contract [:authorship % :sources]))
   [:compatibility :destination :third-party]))

(defn- validate-decision-policy!
  [policy]
  (when-not (and (map? policy)
                 (= decision-policy-keys (set (keys policy)))
                 (string? (:package-publisher policy))
                 (not (str/blank? (:package-publisher policy)))
                 (not (re-find #"[\r\n]" (:package-publisher policy))))
    (fail! "Approved legal and publisher policy is invalid"
           {:expected-keys decision-policy-keys
            :policy policy}))
  policy)

(defn- package-publisher-records
  [target-contracts]
  (->>
   target-contracts
   (mapcat
    (fn [{:keys [target profiles]}]
      (map
       (fn [[profile {:keys [destination]}]]
         {:target target
          :profile profile
          :package-id (get-in destination [:configuration :package :id])
          :publisher (get-in destination [:configuration :package :authors])})
       profiles)))
   (sort-by (juxt (comp name :target) :profile))
   vec))

(defn- verify-package-publisher!
  [target-contracts expected-publisher]
  (let [packages (package-publisher-records target-contracts)
        mismatches
        (filterv #(not= expected-publisher (:publisher %)) packages)]
    (when-not (seq packages)
      (fail! "No distributable target packages are available for publisher verification"
             {}))
    (when (seq mismatches)
      (fail! "Target package publishers differ from the approved human decision"
             {:expected-publisher expected-publisher
              :mismatches mismatches}))
    (mapv #(dissoc % :publisher) packages)))

(defn- consolidated-source-groups!
  [target-contracts]
  (let [groups
        (->>
         target-contracts
         (reduce
          (fn [groups target-contract]
            (reduce
             (fn [groups {:keys [id] :as group}]
               (if-let [existing (get groups id)]
                 (if (= existing group)
                   groups
                   (fail!
                    "Target authorship contracts disagree on a source group"
                    {:source id
                     :targets (mapv :target target-contracts)
                     :existing existing
                     :conflicting group}))
                 (assoc groups id group)))
             groups
             (target-source-groups target-contract)))
          (sorted-map))
         vals
         vec)
        conflicts
        (->> groups
             (mapcat
              (fn [{:keys [id class paths]}]
                (map
                 (fn [path]
                   {:path path :group id :class class})
                 paths)))
             (group-by :path)
             (keep
              (fn [[path usages]]
                (when (< 1 (count usages))
                  {:path path
                   :usages (mapv #(dissoc % :path) usages)})))
             (sort-by :path)
             vec)]
    (when (seq conflicts)
      (fail! "Source paths have conflicting authorship classifications"
             {:conflicts conflicts}))
    groups))

(defn verify-targets!
  "Loads target contracts and verifies one approved legal/publisher policy
  across their complete authored source and distributable package inventories.
  Identical shared compatibility groups are consolidated; conflicts fail
  closed."
  [workspace-root targets policy]
  (let [workspace-root (paths/absolute workspace-root)
        targets (vec targets)
        policy (validate-decision-policy! policy)]
    (when-not (= required-decision (:decision policy))
      (fail! "SPDX policy does not cite the required human decision"
             {:expected-decision required-decision
              :actual-decision (:decision policy)}))
    (when-not (and (seq targets)
                   (every? keyword? targets)
                   (= (count targets) (count (distinct targets))))
      (fail! "SPDX gate targets must be distinct keywords"
             {:targets targets}))
    (let [target-contracts
          (mapv #(target-directory/read-target workspace-root %) targets)
          actual-targets (mapv :target target-contracts)]
      (when-not (= targets actual-targets)
        (fail! "Loaded target contracts do not match the requested targets"
               {:targets targets :actual actual-targets}))
      (let [packages
            (verify-package-publisher!
             target-contracts (:package-publisher policy))]
        (assoc
         (authorship/verify-authored-spdx-headers!
          workspace-root
          (consolidated-source-groups! target-contracts)
          (dissoc policy :package-publisher))
         :targets targets
         :package-publisher (:package-publisher policy)
         :packages packages)))))

(defn- read-policy!
  [workspace-root policy-path]
  (let [workspace-root (paths/absolute workspace-root)
        ^Path policy-file
        (paths/absolute (paths/resolve-path workspace-root policy-path))]
    (when-not (.startsWith policy-file workspace-root)
      (fail! "Approved SPDX policy file is outside the workspace"
             {:path (str policy-path)
              :reason :outside-workspace}))
    (when-not (paths/regular-file? policy-file)
      (fail! "Approved SPDX policy file is missing"
             {:path (str policy-path)
              :reason :missing-policy}))
    (when-not (paths/real-contained? workspace-root policy-file)
      (fail! "Approved SPDX policy file resolves outside the workspace"
             {:path (str policy-path)
              :reason :outside-workspace}))
    {:file policy-file
     :policy
     (read-single-edn!
      policy-path
      (Files/readString policy-file StandardCharsets/UTF_8))}))

(defn verify-policy-file!
  "Loads an approved policy file from the workspace and runs the target gate."
  [workspace-root policy-path targets]
  (let [workspace-root (paths/absolute workspace-root)
        {:keys [file policy]} (read-policy! workspace-root policy-path)]
    (assoc
     (verify-targets! workspace-root targets policy)
     :policy-file (str (.relativize workspace-root file)))))

(defn -main
  [& args]
  (when (seq args)
    (fail! "Usage: clojure -M:verify-authored-spdx"
           {:arguments (vec args)}))
  (let [workspace-root (paths/workspace-root)]
    (prn
     (verify-policy-file!
      workspace-root policy-path (active-targets workspace-root)))))
