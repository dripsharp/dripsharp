(ns dripsharp.nuget-release-preparation-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.nuget-release-preparation :as preparation]
            [dripsharp.paths :as paths]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- write-file!
  [^Path file contents]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file contents (make-array OpenOption 0))
  file)

(defn- target-commit
  [target]
  (apply str (repeat 40 (case target
                          :pdfcube "2"
                          :pkl "1"
                          :sqltrellis "3"))))

(defn- repository-proof
  [contract]
  {:target (:target contract)
   :repository-url (get-in contract [:publication :repository-url])
   :repository-commit (target-commit (:target contract))
   :source-sha256 (util/sha256-text (name (:target contract)))
   :inventory [{:path "src" :sha256 (util/sha256-text "stable")}]})

(defn- profile-dependencies
  [contract profile]
  (set (get-in contract [:profiles profile
                         :configuration :dependency-profiles])))

(defn- profile-closure
  [contract profile]
  (loop [pending [profile] result #{}]
    (if-let [current (peek pending)]
      (if (contains? result current)
        (recur (pop pending) result)
        (recur (into (pop pending)
                     (profile-dependencies contract current))
               (conj result current)))
      result)))

(defn- package-id
  [contract profile]
  (get-in contract [:profiles profile
                    :destination :configuration :package :id]))

(defn- package-version
  [contract profile]
  (get-in contract [:publication :nuget :packages
                    (package-id contract profile) :version]))

(defn- package-dependencies
  [contract profile]
  (let [destination
        (get-in contract [:profiles profile :destination :configuration])]
    (->> (concat
          (for [dependency-profile (profile-dependencies contract profile)]
            {:id (package-id contract dependency-profile)
             :version (package-version contract dependency-profile)})
          (:runtime-packages destination))
         (mapv #(select-keys % [:id :version])))))

(defn- fake-package
  [^Path inputs contract profile]
  (let [id (package-id contract profile)
        version (package-version contract profile)
        destination
        (assoc-in
         (get-in contract [:profiles profile :destination :configuration])
         [:package :version] version)
        symbols? (= :snupkg (get-in destination [:package :symbols]))
        package-file (str id "." version ".nupkg")
        symbol-file (str id "." version ".snupkg")
        artifact (write-file! (paths/resolve-path inputs package-file)
                              (str id "|" version "|package"))
        symbol-artifact
        (when symbols?
          (write-file! (paths/resolve-path inputs symbol-file)
                       (str id "|" version "|symbols")))
        package-sha256 (util/sha256-file artifact)
        symbol-sha256 (some-> symbol-artifact util/sha256-file)
        pdb-entry (str "lib/net10.0/" id ".pdb")
        pdb-sha256 (util/sha256-text (str id "|pdb"))]
    (cond->
     {:profile profile
      :artifact artifact
      :destination destination
      :identity {:id id :version version
                 :file package-file :sha256 package-sha256}
      :inspection {:dependencies (package-dependencies contract profile)}}
      symbols?
      (assoc
       :symbol-artifact symbol-artifact
       :symbol {:id id :version version
                :file symbol-file :sha256 symbol-sha256
                :pdb-sha256 pdb-sha256}
       :symbol-inspection {:pdb-entry pdb-entry :pdb-sha256 pdb-sha256}))))

(defn- fake-workflow
  [^Path root]
  (let [source-root (paths/workspace-root)
        contracts
        (into {}
              (for [target [:pdfcube :pkl :sqltrellis]]
                [target (target-directory/read-target source-root target)]))
        _
        (doseq [target [:pdfcube :pkl :sqltrellis]]
          (write-file!
           (paths/resolve-path root "targets" (name target) "target.edn")
           (str (pr-str {:target target
                         :publication {:kind :generated-repository}})
                "\n")))
        _
        (write-file!
         (paths/resolve-path root "targets" "rawhttp" "target.edn")
         (str (pr-str {:target :rawhttp
                       :publication {:kind :conformance-only}})
              "\n"))
        inputs (doto (paths/resolve-path root "target" "fixture-inputs")
                 (Files/createDirectories (make-array FileAttribute 0)))
        proof-calls (atom [])
        package-calls (atom [])
        repository-calls (atom [])
        proof-fn
        (fn [{:keys [target]}]
          (let [contract (get contracts (keyword target))]
            (swap! proof-calls conj target)
            (mapv (fn [{:keys [id]}] {:id id :result :passed})
                  (get-in contract [:proof :ladders]))))
        repository-proof-fn
        (fn [{:keys [target-contract]}]
          (swap! repository-calls conj (:target target-contract))
          (repository-proof target-contract))
        package-fn
        (fn [{:keys [target profile]}]
          (let [contract (get contracts (keyword target))
                closure (profile-closure contract profile)
                packages (mapv #(fake-package inputs contract %)
                               (sort closure))
                primary (first (filter #(= profile (:profile %)) packages))
                feed (doto (paths/resolve-path root "target" "fixture-feeds"
                                               target profile)
                       (Files/createDirectories
                        (make-array FileAttribute 0)))]
            (swap! package-calls conj [target profile])
            {:verification {:build-configuration "Release"}
             :packing-summary
             {:clean-builds 2
              :repository-commit (target-commit (keyword target))}
             :identity (:identity primary)
             :packages packages
             :feed feed
             :dependency-proof {:packages (mapv :identity packages)}
             :run {:exit 0}}))]
    {:proof-calls proof-calls
     :package-calls package-calls
     :repository-calls repository-calls
     :contracts contracts
     :options {:workspace-root root
               :selection "all"
               :read-target-fn
               (fn [_ target]
                 (get contracts (keyword target)))
               :proof-fn proof-fn
               :package-fn package-fn
               :repository-proof-fn repository-proof-fn}}))

(deftest discovery-selects-only-complete-production-package-catalogs
  (let [products (preparation/discover-products!)
        ids (mapv :target products)
        packages
        (into #{}
              (mapcat #(keys (get-in % [:publication :nuget :packages])))
              products)]
    (is (= [:pdfcube :pkl :sqltrellis] ids))
    (is (= #{"DripSharp.Brine.Parser"
             "DripSharp.Brine"
             "DripSharp.PdfCarton.IO"
             "DripSharp.PdfCarton.Fonts"
             "DripSharp.PdfCarton.Xmp"
             "DripSharp.PdfCarton"
             "DripSharp.PdfCarton.Preflight"
             "DripSharp.SqlTrellis"}
           packages))
    (is (not-any? #{:rawhttp} ids))))

(deftest aggregate-preparation-is-deterministic-and-credential-free
  (let [root (Files/createTempDirectory
              "dripsharp-nuget-release-preparation-test-"
              (make-array FileAttribute 0))
        fixture (fake-workflow root)
        options (:options fixture)
        first-result (preparation/prepare! options)
        first-manifest-bytes
        (Files/readAllBytes ^Path (:manifest-file first-result))
        first-artifact-hashes
        (into
         (sorted-map)
         (for [^Path file
               (with-open [entries
                           (Files/list ^Path (:artifact-directory first-result))]
                 (->> (.toArray entries)
                      (map #(cast Path %))
                      (filter paths/regular-file?)
                      vec))]
           [(str (.getFileName file)) (util/sha256-file file)]))
        expected-symbol-statuses
        (frequencies
         (for [[_ contract] (:contracts fixture)
               [_ profile] (:profiles contract)]
           (if (= :snupkg
                  (get-in profile
                          [:destination :configuration :package :symbols]))
             :paired
             :absent)))]
    (is (= ["pdfcube" "pkl" "sqltrellis"] @(:proof-calls fixture)))
    (is (= 8 (count @(:package-calls fixture))))
    (is (= 11 (count @(:repository-calls fixture))))
    (is (= 8 (get-in first-result [:manifest :package-count])))
    (is (= 3 (get-in first-result [:manifest :product-count])))
    (is (= [] (get-in first-result [:manifest :network-mutations])))
    (is (false? (get-in first-result
                        [:manifest :publication-credentials-accepted])))
    (is (= (:manifest first-result)
           (util/read-single-edn-string!
            (Files/readString ^Path (:manifest-file first-result)))))
    (is (= (+ 1 8 (get expected-symbol-statuses :paired 0))
           (count first-artifact-hashes)))
    (is (= expected-symbol-statuses
           (frequencies
            (map #(get-in % [:symbol-pairing :status])
                 (get-in first-result [:manifest :packages])))))
    (let [order (zipmap (get-in first-result [:manifest :publish-order])
                        (range))]
      (is (< (order "DripSharp.Brine.Parser")
             (order "DripSharp.Brine")))
      (is (< (order "DripSharp.PdfCarton.IO")
             (order "DripSharp.PdfCarton.Fonts")))
      (is (< (order "DripSharp.PdfCarton.Fonts")
             (order "DripSharp.PdfCarton")))
      (is (< (order "DripSharp.PdfCarton.Xmp")
             (order "DripSharp.PdfCarton.Preflight")))
      (is (< (order "DripSharp.PdfCarton")
             (order "DripSharp.PdfCarton.Preflight"))))
    (reset! (:proof-calls fixture) [])
    (reset! (:package-calls fixture) [])
    (reset! (:repository-calls fixture) [])
    (let [second-result (preparation/prepare! options)
          second-manifest-bytes
          (Files/readAllBytes ^Path (:manifest-file second-result))
          second-artifact-hashes
          (into
           (sorted-map)
           (for [^Path file
                 (with-open [entries
                             (Files/list
                              ^Path (:artifact-directory second-result))]
                   (->> (.toArray entries)
                        (map #(cast Path %))
                        (filter paths/regular-file?)
                        vec))]
             [(str (.getFileName file)) (util/sha256-file file)]))]
      (is (= (seq first-manifest-bytes) (seq second-manifest-bytes)))
      (is (= first-artifact-hashes second-artifact-hashes))
      (is (= (:manifest-sha256 first-result)
             (:manifest-sha256 second-result))))
    (testing "identical inputs reject mutation of a retained artifact"
      (let [package-file
            (get-in first-result [:manifest :packages 0 :files :package
                                  :filename])]
        (write-file!
         (paths/resolve-path (:artifact-directory first-result) package-file)
         "tampered retained package")
        (is (= :nuget-release-preparation-failed
               (:kind (failure-data #(preparation/prepare! options)))))))
    (testing "credential-shaped options fail before any proof"
      (reset! (:proof-calls fixture) [])
      (is (= #{:api-key}
             (set (:forbidden-options
                   (failure-data
                    #(preparation/prepare!
                      (assoc options :api-key "not-accepted")))))))
      (is (empty? @(:proof-calls fixture))))))
