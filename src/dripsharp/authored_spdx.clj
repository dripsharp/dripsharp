(ns dripsharp.authored-spdx
  "Repository-wide SPDX gate for DripSharp-authored runtime sources."
  (:require [clojure.edn :as edn]
            [dripsharp.authorship :as authorship]
            [dripsharp.paths :as paths]
            [dripsharp.target-directory :as target-directory])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :invalid-authored-spdx-gate))))

(def policy-path "config/authored-spdx.edn")
(def required-decision "pkl-c8t.2")

(defn active-targets
  "Discovers every checked-in target manifest so the repository gate cannot
  silently omit a newly added product target."
  [workspace-root]
  (let [targets-root
        (paths/absolute (paths/resolve-path workspace-root "targets"))
        targets
        (when (paths/directory? targets-root)
          (with-open [entries (Files/list targets-root)]
            (->> (.toArray entries)
                 (map #(cast Path %))
                 (filter paths/directory?)
                 (filter
                  #(paths/regular-file? (paths/resolve-path % "target.edn")))
                 (map #(keyword (str (.getFileName ^Path %))))
                 sort
                 vec)))]
    (when-not (seq targets)
      (fail! "No target manifests are available for the authored SPDX gate"
             {:targets-root (str targets-root)}))
    targets))

(defn- target-source-groups
  [target-contract]
  (mapcat
   #(vals (get-in target-contract [:authorship % :sources]))
   [:compatibility :destination :third-party]))

(defn- consolidated-source-groups!
  [target-contracts]
  (->>
   target-contracts
   (reduce
    (fn [groups target-contract]
      (reduce
       (fn [groups {:keys [id] :as group}]
         (if-let [existing (get groups id)]
           (if (= existing group)
             groups
             (fail! "Target authorship contracts disagree on a source group"
                    {:source id
                     :targets (mapv :target target-contracts)
                     :existing existing
                     :conflicting group}))
           (assoc groups id group)))
       groups
       (target-source-groups target-contract)))
    (sorted-map))
   vals
   vec))

(defn verify-targets!
  "Loads target contracts and verifies one approved SPDX policy across their
  complete authored source inventory. Identical shared compatibility groups
  are consolidated; conflicting definitions fail closed."
  [workspace-root targets policy]
  (let [workspace-root (paths/absolute workspace-root)
        targets (vec targets)]
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
      (assoc
       (authorship/verify-authored-spdx-headers!
        workspace-root
        (consolidated-source-groups! target-contracts)
        policy)
       :targets targets))))

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
    {:file policy-file
     :policy
     (try
       (edn/read-string
        (Files/readString policy-file StandardCharsets/UTF_8))
       (catch RuntimeException error
         (throw
          (ex-info "Approved SPDX policy file is not valid EDN"
                   {:kind :invalid-authored-spdx-gate
                    :path (str policy-path)}
                   error))))}))

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
