(ns dripsharp.pdfcube.fontbox-differential
  "Versioned, data-driven PDFBox baseline versus DripSharp.PdfCarton.Fonts package proof."
  (:require [dripsharp.baseline :as baseline]
            [dripsharp.differential :as differential]
            [dripsharp.paths :as paths]
            [dripsharp.target-execution :as target-execution]
            [dripsharp.util :as util])
  (:import [java.net URI]
           [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private contract
  (differential/read-contract
   "targets/pdfcube/validation/fontbox-common.edn"))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def supported-hosts
  (get-in contract [:runner :supported-hosts]))

(def required-trace-families
  (set (get-in contract [:observation :required-families])))

(def font-fixtures
  [{:file "SourceSansProBold.otf"
    :url "https://issues.apache.org/jira/secure/attachment/12684264/SourceSansProBold.otf"
    :sha512
    "28a044a2685fbc8da7810d9ac7b6b93a95542d504d7d8e671f009b8ebb2f5b70c974be7ea78974b188d8e6ab17d65b08f276c054927857315d5aad26f6fe36fc"}
   {:file "OpenSans-Regular.pfb"
    :url "https://mirror.math.princeton.edu/pub/CTAN/fonts/opensans/type1/OpenSans-Regular.pfb"
    :sha512
    "2787fcecc0feb1c9e6ff0d8de6193658413863e44eaab572751ca7e6c3b369c0a9731f4952cb0821f307760f0422f77c5f0d3fe7df6b054643fb39423e8d70ee"}
   {:file "DejaVuSerifCondensed.pfb"
    :url "https://issues.apache.org/jira/secure/attachment/13064282/DejaVuSerifCondensed.pfb"
    :sha512
    "6ef13c3497862dc8e4c2a4261bc3a7ef3e2dd75e00ae2af4912b236b387225541db76c72854fbb2323d1064311ffdda9e64ed7065afc3a7d13f5b71b7df2f2ef"}])

(defn- fail! [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-fontbox-differential-failed))))

(defn- ensure-font-fixtures! [^Path root]
  (let [directory
        (doto (paths/resolve-path root "research" "pdfbox" "fontbox"
                                  "target" "fonts")
          (Files/createDirectories (make-array FileAttribute 0)))]
    (doseq [{:keys [file url] expected :sha512} font-fixtures]
      (let [destination (paths/resolve-path directory file)]
        (if (paths/regular-file? destination)
          (when-not (= expected (util/sha512-file destination))
            (fail! "Existing authoritative FontBox fixture has the wrong checksum"
                   {:file (str destination)
                    :expected expected
                    :actual (util/sha512-file destination)}))
          (let [temporary
                (Files/createTempFile directory (str file ".") ".download"
                                      (make-array FileAttribute 0))]
            (try
              (with-open [input (.openStream (.toURL (URI/create url)))]
                (Files/copy input temporary
                            (into-array StandardCopyOption
                                        [StandardCopyOption/REPLACE_EXISTING])))
              (let [actual (util/sha512-file temporary)]
                (when-not (= expected actual)
                  (fail! "Downloaded authoritative FontBox fixture has the wrong checksum"
                         {:file file :url url :expected expected :actual actual})))
              (Files/move temporary destination
                          (into-array StandardCopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))
              (finally
                (Files/deleteIfExists temporary)))))))
    directory))

(defn trace-summary
  "Validates one versioned FontBox trace and returns its coverage."
  [trace]
  (differential/trace-summary contract trace))

(defn verify!
  "Runs clean deterministic packing, isolated consumption, and the pinned
  Java/package FontBox differential."
  ([] (verify! {}))
  ([options]
   (differential/run!
    (assoc options
           :contract
           (target-execution/execution-differential-contract
            :pdfcube contract)
           :prepare-context
           (fn [{:keys [workspace-root]}]
             {:fonts (ensure-font-fixtures! workspace-root)})
           :summary-extension
           (fn [_]
             {:fixtures
              (mapv #(select-keys % [:file :sha512]) font-fixtures)})))))
