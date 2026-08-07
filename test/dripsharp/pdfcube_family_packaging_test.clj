(ns dripsharp.pdfcube-family-packaging-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.family-packaging :as family-packaging])
  (:import [clojure.lang ExceptionInfo]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:private revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

(def ^:private version
  "3.0.8-alpha.1")

(def ^:private copyright
  "Portions Copyright The Apache Software Foundation and other upstream contributors; see NOTICE.txt.")

(defn- sha256
  [^Path file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (Files/readAllBytes file))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn- write-file!
  [^Path file content]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file content (make-array OpenOption 0))
  file)

(defn- zip-file!
  [^Path file entries]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (with-open [output
              (ZipOutputStream.
               (Files/newOutputStream file (make-array OpenOption 0)))]
    (doseq [[name bytes] entries]
      (.putNextEntry output (ZipEntry. name))
      (.write output ^bytes bytes)
      (.closeEntry output)))
  file)

(defn- macos-universal
  []
  (let [buffer (doto (ByteBuffer/allocate 48)
                 (.order ByteOrder/BIG_ENDIAN))]
    (.putInt buffer (unchecked-int 0xcafebabe))
    (.putInt buffer 2)
    (doseq [cpu [(unchecked-int 0x01000007)
                 (unchecked-int 0x0100000c)]]
      (.putInt buffer cpu)
      (.putInt buffer 0)
      (.putInt buffer 0)
      (.putInt buffer 0)
      (.putInt buffer 0))
    (.array buffer)))

(defn- utf8-bytes
  [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

(defn- external-artifact!
  [feed {:keys [id version]}]
  (let [file (str (.toLowerCase ^String id) "." version ".nupkg")
        artifact (paths/resolve-path feed file)
        entries
        (case id
          "SkiaSharp.NativeAssets.Linux"
          {"runtimes/linux-x64/native/libSkiaSharp.so" (utf8-bytes "linux-x64")
           "runtimes/linux-arm64/native/libSkiaSharp.so" (utf8-bytes "linux-arm64")}

          "SkiaSharp.NativeAssets.Win32"
          {"runtimes/win-x64/native/libSkiaSharp.dll" (utf8-bytes "win-x64")
           "runtimes/win-arm64/native/libSkiaSharp.dll" (utf8-bytes "win-arm64")}

          "SkiaSharp.NativeAssets.macOS"
          {"runtimes/osx/native/libSkiaSharp.dylib" (macos-universal)}

          nil)
        _ (if entries
            (zip-file! artifact entries)
            (write-file! artifact (str id "/" version)))]
    {:id id :version version :file file :sha256 (sha256 artifact)
     :external? true}))

(defn- package-proof
  []
  (let [root (Files/createTempDirectory
              "pdfcube-family-package-" (make-array FileAttribute 0))
        feed (doto (paths/resolve-path root "feed")
               (Files/createDirectories (make-array FileAttribute 0)))
        readme (write-file! (paths/resolve-path root "README.md")
                            "# PdfCarton\n")
        contracts @#'family-packaging/package-contract
        packages
        (mapv
         (fn [[id contract]]
           (let [package-file (str id "." version ".nupkg")
                 symbol-file (str id "." version ".snupkg")
                 package-artifact
                 (write-file! (paths/resolve-path feed package-file)
                              (str id " package"))
                 symbol-artifact
                 (write-file! (paths/resolve-path feed symbol-file)
                              (str id " symbols"))
                 pdb-hash (apply str (repeat 64 "a"))]
             {:profile (:profile contract)
              :primary? (:primary? contract)
              :emission {:project-root root}
              :destination
              {:package-readme-source "README.md"
               :project {:target-framework "net10.0"}
               :package
               {:id id
                :version version
                :authors "Fixture Publisher"
                :copyright copyright
                :symbols :snupkg
                :repository-url "https://github.com/dripsharp/pdfcarton.git"
                :repository-type "git"
                :repository-commit revision
                :readme "README.md"}}
              :artifact package-artifact
              :identity
              {:id id :version version :file package-file
               :sha256 (sha256 package-artifact)}
              :symbol-artifact symbol-artifact
              :symbol
              {:id id :version version :file symbol-file
               :sha256 (sha256 symbol-artifact)
               :pdb-sha256 pdb-hash}
              :inspection
              {:dependencies (:dependencies contract)
               :package-files
               (conj (:package-files contract)
                     {:kind :readme
                      :path "README.md"
                      :sha256 (sha256 readme)})}
              :resource-proof
              {:assembly-identity
               {:name id
                :version "3.0.8.0"
                :dependency-assemblies
                (:assembly-dependencies contract)}}
              :resources (vec (repeat (:resources contract) "resource"))
              :symbol-inspection
              {:pdb-entry (str "lib/net10.0/" id ".pdb")
               :pdb-sha256 pdb-hash
               :dependencies (:dependencies contract)
               :source-link
               {:document-pattern "/_/*"
                :documents 1
                :repository-commit revision
                :source-url
                (str "https://raw.githubusercontent.com/dripsharp/pdfcarton/"
                     revision "/src/" id "/*")}}}))
         (sort-by key contracts))
        external
        (mapv #(external-artifact! feed %)
              (sort-by :id
                       @#'family-packaging/external-package-contract))]
    {:proof-root root
     :feed feed
     :packages packages
     :external-packages external
     :summary {:clean-builds 2 :repository-commit revision}}))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch ExceptionInfo error error)))

(deftest exact-five-package-artifact-family-is-accepted
  (let [proof (package-proof)
        evidence (family-packaging/validate-package-family! proof)]
    (is (= 2 (:clean-builds evidence)))
    (is (= #{"DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.Xmp"
             "DripSharp.PdfCarton" "DripSharp.PdfCarton.Preflight"}
           (set (keys (:packages evidence)))))
    (is (= ["DripSharp.PdfCarton.Xmp" "DripSharp.PdfCarton"]
           (->> (get-in @#'family-packaging/package-contract
                        ["DripSharp.PdfCarton.Preflight" :dependencies])
                (map :id)
                (filter #(.startsWith ^String % "DripSharp.PdfCarton"))
                vec)))
    (is (= ["DripSharp.PdfCarton" "DripSharp.PdfCarton.Fonts"
            "DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Xmp"]
           (get-in @#'family-packaging/package-contract
                   ["DripSharp.PdfCarton.Preflight" :assembly-dependencies])))
    (is (= #{"Fixture Publisher"}
           (->> (:packages evidence)
                vals
                (map #(get-in % [:metadata :authors]))
                set)))
    (is (every?
         #(some (fn [file]
                  (= [:readme "README.md"]
                     ((juxt :kind :path) file)))
                (:package-files %))
         (vals (:packages evidence))))
    (is (= 17 (get-in evidence [:feed :artifacts])))
    (is (= ["x64" "arm64"]
           (get-in evidence
                   [:native-assets "SkiaSharp.NativeAssets.macOS"
                    :architectures])))))

(deftest family-package-gate-fails-on-leakage-stale-output-and-host-gaps
  (testing "an unapproved dependency is blocking"
    (let [proof (package-proof)
          artifact
          (write-file!
           (paths/resolve-path (:feed proof) "unapproved.runtime.1.0.0.nupkg")
           "unapproved")
          altered
          (update proof :external-packages conj
                  {:id "Unapproved.Runtime" :version "1.0.0"
                   :file "unapproved.runtime.1.0.0.nupkg"
                   :sha256 (sha256 artifact) :external? true})
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (contains? (ex-data error) :expected))
      (is (contains? (ex-data error) :actual))))
  (testing "a stale feed artifact is blocking"
    (let [proof (package-proof)
          _ (write-file! (paths/resolve-path (:feed proof) "stale.0.0.0.nupkg")
                         "stale")
          error (caught #(family-packaging/validate-package-family! proof))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (= ["stale.0.0.0.nupkg"] (:stale (ex-data error))))))
  (testing "missing packaged README evidence is blocking"
    (let [proof (package-proof)
          altered
          (update-in proof [:packages 0 :inspection :package-files]
                     (fn [files]
                       (filterv #(not= :readme (:kind %)) files)))
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (contains? (ex-data error) :expected))
      (is (contains? (ex-data error) :actual))))
  (testing "mismatched packaged README digest is blocking"
    (let [proof (package-proof)
          altered
          (update-in proof [:packages 0 :inspection :package-files]
                     (fn [files]
                       (mapv #(if (= :readme (:kind %))
                                (assoc % :sha256 (apply str (repeat 64 "0")))
                                %)
                             files)))
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (contains? (ex-data error) :expected))
      (is (contains? (ex-data error) :actual))))
  (testing "a missing supported-host native payload is blocking"
    (let [proof (package-proof)
          linux (first (filter #(= "SkiaSharp.NativeAssets.Linux" (:id %))
                               (:external-packages proof)))
          artifact (paths/resolve-path (:feed proof) (:file linux))
          _ (zip-file!
             artifact
             {"runtimes/linux-x64/native/libSkiaSharp.so"
              (utf8-bytes "linux-x64")})
          altered
          (update proof :external-packages
                  (fn [packages]
                    (mapv #(if (= (:id %) (:id linux))
                             (assoc % :sha256 (sha256 artifact))
                             %)
                          packages)))
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (= ["runtimes/linux-arm64/native/libSkiaSharp.so"]
             (:missing (ex-data error)))))))

(defn- consumer-proof
  [name direct restored index]
  {:consumer-name name
   :packages-root (paths/path (str "/isolated/packages-" index))
   :dependency-proof
   {:package-references
    (mapv (fn [id] [id version]) (sort direct))
    :packages
    (mapv (fn [id] {:id id :version version :sha256 "hash"})
          (sort restored))}})

(deftest separate-and-all-family-consumption-evidence-is-exact
  (let [closures @#'family-packaging/restored-closures
        ids (sort (keys @#'family-packaging/package-contract))
        proofs
        (into
         (mapv (fn [index id]
                 (consumer-proof id #{id} (get closures id) index))
               (range) ids)
         [(consumer-proof "complete-family" (set ids)
                          (get closures "DripSharp.PdfCarton.Preflight") 5)])
        evidence (#'family-packaging/validate-consumers! proofs)]
    (is (= 6 (:consumers evidence)))
    (is (= 6 (count (:package-caches evidence))))
    (let [reused (assoc-in proofs [5 :packages-root]
                           (:packages-root (first proofs)))
          error (caught #(#'family-packaging/validate-consumers! reused))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (= 6 (count (:package-caches (ex-data error))))))))

(deftest all-family-consumer-executes-public-runtime-workflows
  (let [consumer @#'family-packaging/aggregate-consumer]
    (is (= :source-file (:strategy consumer)))
    (is (= "validation/pdfcube-family/PdfCarton.Family.FocusedConsumer.cs"
           (:source-path consumer)))
    (is (= "Complete PdfCarton package family runtime workflow passed."
           (:success-message consumer)))))
