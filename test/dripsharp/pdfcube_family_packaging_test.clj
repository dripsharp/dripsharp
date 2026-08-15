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

(def ^:private complete-external-package-contract
  #{{:id "Microsoft.Bcl.AsyncInterfaces" :version "10.0.0"}
    {:id "Microsoft.Bcl.Cryptography" :version "10.0.0"}
    {:id "Microsoft.CSharp" :version "4.7.0"}
    {:id "Microsoft.Extensions.DependencyInjection.Abstractions"
     :version "10.0.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "Microsoft.NETCore.Platforms" :version "1.1.0"}
    {:id "NETStandard.Library" :version "2.0.3"}
    {:id "SkiaSharp" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.macOS" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Win32" :version "4.150.1"}
    {:id "System.Buffers" :version "4.6.1"}
    {:id "System.Diagnostics.DiagnosticSource" :version "10.0.0"}
    {:id "System.Formats.Asn1" :version "10.0.0"}
    {:id "System.Memory" :version "4.6.3"}
    {:id "System.Numerics.Vectors" :version "4.6.1"}
    {:id "System.Runtime.CompilerServices.Unsafe" :version "6.1.2"}
    {:id "System.Security.Cryptography.Cng" :version "5.0.0"}
    {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}
    {:id "System.Text.Encoding.CodePages" :version "10.0.0"}
    {:id "System.Threading.Tasks.Extensions" :version "4.6.3"}})

(def ^:private package-dependency-contract
  {"DripSharp.PdfCarton.IO"
   [{:id "Microsoft.CSharp" :version "4.7.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "System.Memory" :version "4.6.3"}
    {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]

   "DripSharp.PdfCarton.Fonts"
   [{:id "DripSharp.PdfCarton.IO" :version version}
    {:id "Microsoft.CSharp" :version "4.7.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "SkiaSharp" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
    {:id "System.Formats.Asn1" :version "10.0.0"}
    {:id "System.Memory" :version "4.6.3"}
    {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]

   "DripSharp.PdfCarton.Xmp"
   [{:id "Microsoft.CSharp" :version "4.7.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "System.Memory" :version "4.6.3"}
    {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]

   "DripSharp.PdfCarton"
   [{:id "DripSharp.PdfCarton.Fonts" :version version}
    {:id "DripSharp.PdfCarton.IO" :version version}
    {:id "Microsoft.CSharp" :version "4.7.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "SkiaSharp" :version "4.150.1"}
    {:id "System.Memory" :version "4.6.3"}
    {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}
    {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]

   "DripSharp.PdfCarton.Preflight"
   [{:id "DripSharp.PdfCarton.Xmp" :version version}
    {:id "DripSharp.PdfCarton" :version version}
    {:id "Microsoft.CSharp" :version "4.7.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "SkiaSharp" :version "4.150.1"}
    {:id "System.Memory" :version "4.6.3"}
    {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]})

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
               :project {:target-framework "netstandard2.0"}
               :package
               {:id id
                :version version
                :authors "Fixture Publisher"
                :copyright copyright
                :symbols :snupkg
                :repository-url "https://github.com/dripsharp/pdfcarton.git"
                :repository-type "git"
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
              :expected-package-files
              (conj (:package-files contract)
                    {:kind :readme
                     :path "README.md"
                     :sha256 (sha256 readme)})
              :inspection
              {:dependencies (get package-dependency-contract id)
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
              {:pdb-entry (str "lib/netstandard2.0/" id ".pdb")
               :pdb-sha256 pdb-hash
               :dependencies (get package-dependency-contract id)
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
              (sort-by :id complete-external-package-contract))]
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
    (is (= package-dependency-contract
           (into {}
                 (map (fn [[id contract]] [id (:dependencies contract)]))
                 @#'family-packaging/package-contract)))
    (is (= #{"Fixture Publisher"}
           (->> (:packages evidence)
                vals
                (map #(get-in % [:metadata :authors]))
                set)))
    (is (= #{revision}
           (->> (:packages evidence)
                vals
                (map #(get-in % [:metadata :repository-commit]))
                set)))
    (is (every?
         #(some (fn [file]
                  (= [:readme "README.md"]
                     ((juxt :kind :path) file)))
                (:package-files %))
         (vals (:packages evidence))))
    (is (= complete-external-package-contract
           (set (:external-packages evidence))))
    (is (= 31 (get-in evidence [:feed :artifacts])))
    (is (= ["x64" "arm64"]
           (get-in evidence
                   [:native-assets "SkiaSharp.NativeAssets.macOS"
                    :architectures])))))

(deftest family-package-gate-retains-exact-fail-closed-checks
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
  (testing "a missing locked transitive dependency is blocking"
    (let [proof (package-proof)
          altered
          (update proof :external-packages
                  (fn [packages]
                    (filterv #(not= "Microsoft.Bcl.AsyncInterfaces" (:id %))
                             packages)))
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (= 21 (count (:expected (ex-data error)))))
      (is (= 20 (count (:actual (ex-data error)))))))
  (testing "a wrong locked transitive dependency version is blocking"
    (let [proof (package-proof)
          altered
          (update proof :external-packages
                  (fn [packages]
                    (mapv #(if (= "Microsoft.Bcl.AsyncInterfaces" (:id %))
                             (assoc % :version "9.0.0")
                             %)
                          packages)))
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (some #(= {:id "Microsoft.Bcl.AsyncInterfaces"
                     :version "9.0.0"}
                    %)
                (:actual (ex-data error))))))
  (testing "a changed direct package dependency is blocking"
    (let [proof (package-proof)
          altered
          (update-in proof [:packages 0 :inspection :dependencies]
                     conj {:id "Unapproved.Runtime" :version "1.0.0"})
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (contains? (ex-data error) :expected))
      (is (contains? (ex-data error) :actual))))
  (testing "a missing exact PdfCarton package is blocking"
    (let [proof (package-proof)
          altered (update proof :packages #(vec (rest %)))
          error (caught #(family-packaging/validate-package-family! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (= 5 (count (:expected (ex-data error)))))
      (is (= 4 (count (:actual (ex-data error)))))))
  (testing "a stale feed artifact is blocking"
    (let [proof (package-proof)
          _ (write-file! (paths/resolve-path (:feed proof) "stale.0.0.0.nupkg")
                         "stale")
          error (caught #(family-packaging/validate-package-family! proof))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (= ["stale.0.0.0.nupkg"] (:stale (ex-data error))))))
  (testing "a changed artifact hash is blocking"
    (let [proof (package-proof)
          artifact (get-in proof [:packages 0 :artifact])
          _ (write-file! artifact "tampered package")
          error (caught #(family-packaging/validate-package-family! proof))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (= (get-in proof [:packages 0 :identity :sha256])
             (:expected (ex-data error))))
      (is (= (sha256 artifact) (:actual (ex-data error))))))
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
          (sort restored))
    :expected-packages
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
      (is (= 6 (count (:package-caches (ex-data error))))))
    (let [altered
          (update-in proofs [0 :dependency-proof :expected-packages]
                     #(vec (rest %)))
          error (caught #(#'family-packaging/validate-consumers! altered))]
      (is (= :pdfcube-family-packaging-failed (:kind (ex-data error))))
      (is (not= (:expected (ex-data error)) (:actual (ex-data error)))))))

(deftest net10-framework-group-omission-contract-is-exact
  (let [closures @#'family-packaging/restored-closures
        actual
        (into {}
              (map
               (fn [[id closure]]
                 [id (set (#'family-packaging/framework-omitted-contract
                           closure))]))
              closures)
        async {:id "Microsoft.Bcl.AsyncInterfaces" :version "10.0.0"}
        crypto {:id "Microsoft.Bcl.Cryptography" :version "10.0.0"}]
    (is (= {"DripSharp.PdfCarton.IO" #{async}
            "DripSharp.PdfCarton.Fonts" #{async}
            "DripSharp.PdfCarton.Xmp" #{async}
            "DripSharp.PdfCarton" #{async crypto}
            "DripSharp.PdfCarton.Preflight" #{async crypto}}
           actual))))

(deftest all-family-consumer-executes-public-runtime-workflows
  (let [consumer @#'family-packaging/aggregate-consumer]
    (is (= :source-file (:strategy consumer)))
    (is (= "validation/pdfcube-family/PdfCarton.Family.FocusedConsumer.cs"
           (:source-path consumer)))
    (is (= "Complete PdfCarton package family runtime workflow passed."
           (:success-message consumer)))))
