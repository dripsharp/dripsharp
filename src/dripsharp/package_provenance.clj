(ns dripsharp.package-provenance
  "Product-neutral package assembly provenance shared by isolated consumers."
  (:require [clojure.string :as str]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(defn write-packed-assembly-manifest!
  "Writes exact assembly identities and payload hashes from a deterministic
  package proof."
  [^Path output packages]
  (let [assemblies
        (mapv (fn [package]
                {:name (get-in package [:resource-proof :assembly-identity :name])
                 :sha256 (get-in package [:resource-proof :assembly-artifact :sha256])})
              packages)
        invalid (filterv #(or (str/blank? (:name %))
                              (not (re-matches #"[0-9a-f]{64}"
                                               (or (:sha256 %) ""))))
                         assemblies)
        duplicate-names (->> assemblies
                             (map :name)
                             frequencies
                             (keep (fn [[name count]]
                                     (when (> count 1) name)))
                             sort
                             vec)]
    (when (or (seq invalid) (seq duplicate-names))
      (throw
       (ex-info "Packed assembly provenance is incomplete or ambiguous"
                {:kind :invalid-packed-assembly-provenance
                 :invalid invalid
                 :duplicate-names duplicate-names})))
    (let [assemblies (vec (sort-by :name assemblies))]
      (util/write-text!
       output
       (apply str
              (for [{:keys [name sha256]} assemblies]
                (str name "\t" sha256 "\n"))))
      {:path output :assemblies assemblies})))

(defn read-packed-assembly-manifest
  "Reads an assembly manifest emitted from a deterministic package proof."
  [manifest]
  (mapv (fn [line]
          (let [[name sha256] (str/split line #"\t" -1)]
            {:name name :sha256 sha256}))
        (str/split-lines
         (Files/readString manifest StandardCharsets/UTF_8))))
