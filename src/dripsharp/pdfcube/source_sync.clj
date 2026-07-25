(ns dripsharp.pdfcube.source-sync
  "Monotonic Apache PDFBox stable-release selection for PdfCube source sync.

  Release discovery intentionally uses Git's remote tag refs. It does not infer
  stability from publication dates, branches, Maven metadata, or release-page
  ordering."
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.math BigInteger]))

(def ^:private stable-version-pattern
  #"^([0-9]+)\.([0-9]+)\.([0-9]+)$")

(def ^:private tag-prefix "refs/tags/")
(def ^:private dereference-suffix "^{}")

(defn- fail! [message data]
  (throw (ex-info message
                  (assoc data :kind
                         (or (:kind data) :invalid-pdfbox-release-discovery)))))

(defn stable-version
  "Parses one canonical numeric major/minor/patch stable version.

  Snapshots, alphas, betas, milestones, and release candidates return nil."
  [value]
  (when-let [[_ major minor patch]
             (and (string? value)
                  (re-matches stable-version-pattern value))]
    (let [components
          (mapv #(BigInteger. ^String %) [major minor patch])
          canonical (str/join "." (map str components))]
      (when (= canonical value)
        {:version value :components components}))))

(defn- release
  [version revision]
  (when-let [parsed (stable-version version)]
    (assoc parsed :revision revision)))

(defn parse-remote-tags
  "Parses `git ls-remote --tags` output into stable releases.

  Annotated tags use their dereferenced commit rather than the tag-object hash.
  Lightweight stable tags use their direct commit."
  [output]
  (let [refs
        (reduce
         (fn [result line]
           (let [[revision reference & extra]
                 (str/split (str/trim line) #"\s+")]
             (when (or (seq extra)
                       (str/blank? revision)
                       (str/blank? reference)
                       (not (re-matches #"[0-9a-f]{40}" revision)))
               (fail! "Git returned a malformed PDFBox tag reference"
                      {:line line}))
             (if-not (str/starts-with? reference tag-prefix)
               result
               (let [tag-reference (subs reference (count tag-prefix))
                     dereferenced?
                     (str/ends-with? tag-reference dereference-suffix)
                     tag (if dereferenced?
                           (subs tag-reference
                                 0
                                 (- (count tag-reference)
                                    (count dereference-suffix)))
                           tag-reference)]
                 (if-not (stable-version tag)
                   result
                   (let [field (if dereferenced? :dereferenced :direct)
                         previous (get-in result [tag field])]
                     (when (and previous (not= previous revision))
                       (fail! "Git returned conflicting PDFBox tag revisions"
                              {:tag tag
                               :field field
                               :revisions [previous revision]}))
                     (assoc-in result [tag field] revision)))))))
         {}
         (remove str/blank? (str/split-lines (or output ""))))]
    (->> refs
         (map
          (fn [[version {:keys [direct dereferenced]}]]
            (release version (or dereferenced direct))))
         (sort-by :components)
         vec)))

(defn select-stable-release
  "Selects the greatest stable numeric release not lower than `baseline`.

  The baseline must still exist at the same remote tag commit. This catches a
  missing or moved baseline tag instead of silently changing source identity."
  [baseline discovered]
  (let [{baseline-version :version
         baseline-components :components
         baseline-revision :revision
         :as parsed-baseline}
        (release (:version baseline) (:revision baseline))]
    (when-not (and parsed-baseline
                   (re-matches #"[0-9a-f]{40}" (or baseline-revision "")))
      (fail! "PdfCube's configured PDFBox baseline is not a stable tag commit"
             {:baseline baseline}))
    (let [releases
          (->> discovered
               (map #(release (:version %) (:revision %)))
               (remove nil?)
               (sort-by :components)
               vec)
          remote-baseline
          (some #(when (= baseline-version (:version %)) %) releases)]
      (when-not remote-baseline
        (fail! "The configured PdfCube baseline tag is missing from the PDFBox remote"
               {:kind :pdfbox-baseline-tag-missing
                :baseline baseline
                :discovered-versions (mapv :version releases)}))
      (when-not (= baseline-revision (:revision remote-baseline))
        (fail! "The configured PdfCube baseline tag no longer names its pinned commit"
               {:kind :pdfbox-baseline-tag-moved
                :baseline baseline
                :remote remote-baseline}))
      (or
       (last
        (filter #(not (neg? (compare (:components %)
                                    baseline-components)))
                releases))
       parsed-baseline))))

(defn remote-stable-releases!
  "Discovers stable PDFBox tag commits from the configured checkout's remote."
  ([checkout]
   (remote-stable-releases! checkout {}))
  ([checkout {:keys [remote run-command!]
              :or {remote "origin" run-command! process/run!}}]
   (let [checkout (paths/absolute checkout)
         result
         (run-command!
          {:command ["git" "ls-remote" "--tags" remote]
           :directory checkout
           :timeout-ms 120000})]
     (parse-remote-tags (:output result)))))

(defn select-remote-stable-release!
  "Discovers and monotonically selects the current PdfCube PDFBox release."
  ([checkout baseline]
   (select-remote-stable-release! checkout baseline {}))
  ([checkout baseline options]
   (select-stable-release
    baseline
    (remote-stable-releases! checkout options))))
