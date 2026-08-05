(ns dripsharp.pdfcube.test-suite
  "Pinned inventory and eventual target-owned emission for PdfCarton's complete
  adapted Apache PDFBox test suite.

  Maven remains the authority for module test source, resource, dependency, and
  classpath discovery. JUnit planning is reusable and resolved-symbol based;
  this namespace owns only the five PdfCarton modules, PDFBox provenance, and
  the classification of PDFBox test conditions."
  (:require [clojure.string :as str]
            [dripsharp.csharp :as csharp]
            [dripsharp.harness :as harness]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as java-project]
            [dripsharp.java-test-adapters :as adapters]
            [dripsharp.java-translate :as java]
            [dripsharp.junit-xunit :as junit]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.java-project :as pdfcube]
            [dripsharp.project :as project]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files OpenOption Path StandardCopyOption]
           [spoon.reflect.declaration CtClass CtMethod CtModifiable CtNamedElement
            CtParameter CtType ModifierKind]))

(def ^:private revision "9286e47d89d6877005c9d2d0f2fd38793a62519a")
(def ^:private suite-contract-file "adapted-tests/suite-contract.edn")

(def ^:private module-specs
  [{:id :io
    :source-directory "io"
    :profile "targets/pdfcube/profiles/io.edn"
    :expected-sources 10
    :expected-fixtures 2}
   {:id :fontbox
    :source-directory "fontbox"
    :profile "targets/pdfcube/profiles/fontbox.edn"
    :expected-sources 44
    :expected-fixtures 27}
   {:id :xmpbox
    :source-directory "xmpbox"
    :profile "targets/pdfcube/profiles/xmpbox.edn"
    :expected-sources 28
    :expected-fixtures 34}
   {:id :pdfbox
    :source-directory "pdfbox"
    :profile "targets/pdfcube/profiles/pdfbox.edn"
    :expected-sources 128
    :expected-fixtures 300}
   {:id :preflight
    :source-directory "preflight"
    :profile "targets/pdfcube/profiles/preflight.edn"
    :expected-sources 23
    :expected-fixtures 8}])

(def ^:private digest-fields
  [:sources :fixtures :cases :parameter-rows :helpers :enablement
   :platform-conditions :framework-calls :dependencies])

(defn- fail! [message data]
  (throw
   (ex-info message (assoc data :kind :pdfcarton-test-suite-generation-failed))))

(def ^:private additional-fixture-specs
  {:io
   [{:source
     "io/src/test/java/org/apache/pdfbox/io/NonSeekableRandomAccessReadInputStreamTest.java"
     :destination
     "src/test/java/org/apache/pdfbox/io/NonSeekableRandomAccessReadInputStreamTest.java"
     :license "Apache-2.0"
     :authorship :mechanically-upstream-derived
     :attribution
     (str "Apache PDFBox 3.0.8 test source used as fixture at " revision ".")}]
   :fontbox
   [{:source "fontbox/target/fonts/SourceSansProBold.otf"
     :destination "target/fonts/SourceSansProBold.otf"
     :license "OFL-1.1"
     :authorship :third-party-test-fixture
     :attribution "Adobe Source Sans Pro; PDFBOX-4038 Maven download."
     :upstream-sha512
     "28a044a2685fbc8da7810d9ac7b6b93a95542d504d7d8e671f009b8ebb2f5b70c974be7ea78974b188d8e6ab17d65b08f276c054927857315d5aad26f6fe36fc"}
    {:source "fontbox/target/fonts/NotoEmoji-Regular.ttf"
     :destination "target/fonts/NotoEmoji-Regular.ttf"
     :license "OFL-1.1"
     :authorship :third-party-test-fixture
     :attribution "Google Noto Emoji; PDFBOX-3997 Maven download."
     :upstream-sha512
     "51b01ab0794be9f92c59679f6d56d4ce09ed959daeb9ec945bb837eb15a82ab302e83b29aab1972ac9cb648f7196a5f5ff4488a4622b36bedbc9cd0cab6dc3de"}
    {:source "fontbox/target/fonts/DejaVuSansMono.ttf"
     :destination "target/fonts/DejaVuSansMono.ttf"
     :license "Bitstream-Vera"
     :authorship :third-party-test-fixture
     :attribution "Bitstream Vera and public-domain DejaVu changes; PDFBOX-3379 Maven download."
     :upstream-sha512
     "1af1ce3e6d34a0b89c93072d8646e92cceb45b276389d2dd0d84457ec1193394d2bcc49bf3ce99c9c6b2658cd1337fc40ee5c61957f74cd45dbc3d51b6aef417"}
    {:source "fontbox/target/fonts/NotoSansSC-Regular.otf"
     :destination "target/fonts/NotoSansSC-Regular.otf"
     :license "OFL-1.1"
     :authorship :third-party-test-fixture
     :attribution "Adobe and Google Noto Sans SC; PDFBOX-5328 Maven download."
     :upstream-sha512
     "cbdd317d16099d24736457eef631353c7830a1a3c132b01f2cdc1e6a0c21a78e3b1fe8479b3f40179e7630a15cc23a093775bb22d521dba39376bb367d497b21"}
    {:source "fontbox/target/fonts/OpenSans-Regular.pfb"
     :destination "target/fonts/OpenSans-Regular.pfb"
     :license "Apache-2.0"
     :authorship :third-party-test-fixture
     :attribution "Google Open Sans Type 1 conversion from CTAN; PDFBOX-5356 Maven download."
     :upstream-sha512
     "2787fcecc0feb1c9e6ff0d8de6193658413863e44eaab572751ca7e6c3b369c0a9731f4952cb0821f307760f0422f77c5f0d3fe7df6b054643fb39423e8d70ee"}
    {:source "fontbox/target/fonts/DejaVuSerifCondensed.pfb"
     :destination "target/fonts/DejaVuSerifCondensed.pfb"
     :license "Bitstream-Vera"
     :authorship :third-party-test-fixture
     :attribution "Bitstream Vera and public-domain DejaVu changes; PDFBOX-5713 Maven download."
     :upstream-sha512
     "6ef13c3497862dc8e4c2a4261bc3a7ef3e2dd75e00ae2af4912b236b387225541db76c72854fbb2323d1064311ffdda9e64ed7065afc3a7d13f5b71b7df2f2ef"}
    {:source "fontbox/target/fonts/NotoMono-Regular.ttf"
     :destination "target/fonts/NotoMono-Regular.ttf"
     :license "OFL-1.1"
     :authorship :third-party-test-fixture
     :attribution "Google Noto Mono; PDFBOX-5728 Maven download."
     :upstream-sha512
     "a5f3a12a02d096337cefd82a352a9d4f43555283873211c4ed0ac63eb1e722514dbd97dc959208e38643784b007ef27a96280f57ef01355fdbd8884b84d13d4c"}
    {:source "fontbox/target/fonts/ipag00303/ipag.ttf"
     :destination "target/fonts/ipag00303/ipag.ttf"
     :license "IPA"
     :authorship :third-party-test-fixture
     :attribution "IPA Gothic 003.03; PDFBOX-4106 Maven download archive."
     :upstream-archive-sha512
     "59535137c649a2f8bdbb463cd716426811a6003a65883ca6e45bb0af1d526b3889af0fba3a353e90bc8d373cd32b90a27ff9ff6916ecbccb42e922c09e9b046a"}
    {:source
     "fontbox/target/fonts/ipag00303/IPA_Font_License_Agreement_v1.0.txt"
     :destination
     "target/fonts/ipag00303/IPA_Font_License_Agreement_v1.0.txt"
     :license "IPA"
     :authorship :third-party-test-fixture-license
     :attribution "License distributed with IPA Gothic 003.03."}
    {:source "fontbox/target/fonts/ipag00303/Readme_ipag00303.txt"
     :destination "target/fonts/ipag00303/Readme_ipag00303.txt"
     :license "IPA"
     :authorship :third-party-test-fixture-notice
     :attribution "Readme distributed with IPA Gothic 003.03."}
    {:source "fontbox/target/fonts/NotoMono-Regular.ttf"
     :destination "target/fonts/PdfCartonCmap01.ttf"
     :license "OFL-1.1"
     :authorship :target-adapted-test-fixture
     :generator :pdfcarton-cmap-01
     :upstream-reference
     "https://issues.apache.org/jira/secure/attachment/13076859/Keyboard.ttf"
     :attribution
     (str "Renamed derivative of Google Noto Mono with a deterministic "
          "platform-0/encoding-1 cmap. Preserves the PDFBOX-6015 assertions "
          "without redistributing the Apple all-rights-reserved attachment.")} ]})

(def ^:private remote-fixture-urls
  ["https://issues.apache.org/jira/secure/attachment/12558110/Wing.tif"
   "https://issues.apache.org/jira/secure/attachment/12682897/FormI-9-English.pdf"
   "https://issues.apache.org/jira/secure/attachment/12689788/test.pdf"
   "https://issues.apache.org/jira/secure/attachment/12792007/hidden_fields.pdf"
   "https://issues.apache.org/jira/secure/attachment/12816014/Signed-Document-1.pdf"
   "https://issues.apache.org/jira/secure/attachment/12816016/Signed-Document-2.pdf"
   "https://issues.apache.org/jira/secure/attachment/12821307/Signed-Document-3.pdf"
   "https://issues.apache.org/jira/secure/attachment/12821308/Signed-Document-4.pdf"
   "https://issues.apache.org/jira/secure/attachment/12866226/D1790B.PDF"
   "https://issues.apache.org/jira/secure/attachment/12881055/merge-test.pdf"
   "https://issues.apache.org/jira/secure/attachment/12891316/YTW2VWJQTDAE67PGJT6GS7QSKW3GNUQR.pdf"
   "https://issues.apache.org/jira/secure/attachment/12908175/AML1.PDF"
   "https://issues.apache.org/jira/secure/attachment/12968302/KYF%20211%20Best%C3%A4llning%202014.pdf"
   "https://issues.apache.org/jira/secure/attachment/12986337/stenotypeTest-3_rotate_no_flatten.pdf"
   "https://issues.apache.org/jira/secure/attachment/12994791/flatten.pdf"
   "https://issues.apache.org/jira/secure/attachment/13005793/f1040sb%20test.pdf"
   "https://issues.apache.org/jira/secure/attachment/13011410/PDFBOX-4955.pdf"
   "https://issues.apache.org/jira/secure/attachment/13013354/POPPLER-806.pdf"
   "https://issues.apache.org/jira/secure/attachment/13013384/POPPLER-806-acrobat.pdf"
   "https://issues.apache.org/jira/secure/attachment/13014447/merge-test-na-acrobat.pdf"
   "https://issues.apache.org/jira/secure/attachment/13016941/REDHAT-1301016-0.pdf"
   "https://issues.apache.org/jira/secure/attachment/13016992/PDFBOX-3891-5.pdf"
   "https://issues.apache.org/jira/secure/attachment/13016993/poppler-14433-0.pdf"
   "https://issues.apache.org/jira/secure/attachment/13016994/PDFBOX-4131-0.pdf"
   "https://issues.apache.org/jira/secure/attachment/13017227/stringwidth.pdf"
   "https://issues.apache.org/jira/secure/attachment/13027311/SourceFailure.pdf"
   "https://issues.apache.org/jira/secure/attachment/13066015/empty.pdf"
   "https://issues.apache.org/jira/secure/attachment/13066016/roboto-14.pdf"
   "https://issues.apache.org/jira/secure/attachment/13069137/AU_Erklaerung_final.pdf"])

(def ^:private remote-fixture-specs
  (group-by
   :module
   (mapv
    (fn [url]
      (let [[_ attachment] (re-find #"/attachment/([0-9]+)/" url)]
        {:module (if (= "13017227" attachment) :io :pdfbox)
         :workspace-source true
         :source (str "targets/pdfcube/adapted-tests/remote-fixtures/"
                      attachment)
         :destination (str "remote/" attachment)
         :upstream-url url
         :license "Fixture-specific upstream terms"
         :authorship :third-party-test-fixture
         :attribution
         (str "Fixture used by the Apache PDFBox 3.0.8 test suite at "
              revision "; pinned from its Apache JIRA attachment URL.")}))
    remote-fixture-urls)))

(def ^:private discovered-target-fixture-roots
  {:pdfbox ["target/fonts" "target/imgs" "target/pdfs"]
   :preflight ["target/pdfs"]})

(defn- unsigned-byte [^bytes bytes offset]
  (bit-and 0xff (int (aget bytes offset))))

(defn- read-u16 [^bytes bytes offset]
  (bit-or (bit-shift-left (unsigned-byte bytes offset) 8)
          (unsigned-byte bytes (inc offset))))

(defn- read-u32 [^bytes bytes offset]
  (reduce (fn [value index]
            (bit-or (bit-shift-left value 8)
                    (unsigned-byte bytes (+ offset index))))
          0
          (range 4)))

(defn- put-u16! [^bytes bytes offset value]
  (aset-byte bytes offset (unchecked-byte (bit-shift-right value 8)))
  (aset-byte bytes (inc offset) (unchecked-byte value)))

(defn- put-u32! [^bytes bytes offset value]
  (doseq [index (range 4)]
    (aset-byte bytes (+ offset index)
               (unchecked-byte
                (bit-shift-right (long value) (* 8 (- 3 index)))))))

(defn- align-four [value]
  (bit-and (+ value 3) -4))

(defn- font-table-record-offset [^bytes bytes tag]
  (let [tag-bytes (.getBytes ^String tag StandardCharsets/ISO_8859_1)]
    (some
     (fn [index]
       (let [offset (+ 12 (* 16 index))]
         (when (every? #(= (unsigned-byte bytes (+ offset %))
                           (unsigned-byte tag-bytes %))
                       (range 4))
           offset)))
     (range (read-u16 bytes 4)))))

(defn- table-checksum [^bytes bytes offset length]
  (loop [index 0
         checksum 0]
    (if (>= index length)
      (bit-and checksum 0xffffffff)
      (let [word
            (reduce
             (fn [value byte-index]
               (let [source-index (+ index byte-index)]
                 (bit-or
                  value
                  (if (< source-index length)
                    (bit-shift-left
                     (unsigned-byte bytes (+ offset source-index))
                     (* 8 (- 3 byte-index)))
                    0))))
             0
             (range 4))]
        (recur (+ index 4)
               (bit-and (+ checksum (long word)) 0xffffffff))))))

(defn- cmap-01-table []
  (let [bytes (byte-array 274)]
    (put-u16! bytes 0 0)
    (put-u16! bytes 2 1)
    (put-u16! bytes 4 0)
    (put-u16! bytes 6 1)
    (put-u32! bytes 8 12)
    (put-u16! bytes 12 0)
    (put-u16! bytes 14 262)
    (put-u16! bytes 16 0)
    (doseq [[character glyph]
            [[\a 185] [\z 210] [\A 159] [\Z 184] [\0 49] [\9 58]]]
      (aset-byte bytes (+ 18 (int character)) (unchecked-byte glyph)))
    bytes))

(defn- name-table []
  (let [family (.getBytes "PdfCarton Cmap 01" StandardCharsets/UTF_16BE)
        postscript (.getBytes "PdfCartonCmap01" StandardCharsets/UTF_16BE)
        storage-offset 30
        bytes (byte-array (+ storage-offset (alength family)
                             (alength postscript)))]
    (put-u16! bytes 0 0)
    (put-u16! bytes 2 2)
    (put-u16! bytes 4 storage-offset)
    (doseq [[record-offset name-id value-offset value]
            [[6 1 0 family]
             [18 6 (alength family) postscript]]]
      (put-u16! bytes record-offset 3)
      (put-u16! bytes (+ record-offset 2) 1)
      (put-u16! bytes (+ record-offset 4) 0x0409)
      (put-u16! bytes (+ record-offset 6) name-id)
      (put-u16! bytes (+ record-offset 8) (alength ^bytes value))
      (put-u16! bytes (+ record-offset 10) value-offset)
      (System/arraycopy value 0 bytes (+ storage-offset value-offset)
                        (alength ^bytes value)))
    bytes))

(defn- pdfcarton-cmap-01-bytes [^Path source]
  (let [original (Files/readAllBytes source)
        cmap (cmap-01-table)
        name (name-table)
        cmap-record (font-table-record-offset original "cmap")
        name-record (font-table-record-offset original "name")
        head-record (font-table-record-offset original "head")]
    (when-not (every? some? [cmap-record name-record head-record])
      (fail! "PdfCarton synthetic cmap fixture source is missing required tables"
             {:reason :pdfcarton-cmap-source-table-missing
              :source (str source)}))
    (let [cmap-offset (align-four (alength original))
          name-offset (align-four (+ cmap-offset (alength cmap)))
          bytes (java.util.Arrays/copyOf
                 original (align-four (+ name-offset (alength name))))
          head-offset (read-u32 bytes (+ head-record 8))]
      (System/arraycopy cmap 0 bytes cmap-offset (alength cmap))
      (System/arraycopy name 0 bytes name-offset (alength name))
      (doseq [[record offset payload]
              [[cmap-record cmap-offset cmap]
               [name-record name-offset name]]]
        (put-u32! bytes (+ record 4)
                  (table-checksum bytes offset (alength ^bytes payload)))
        (put-u32! bytes (+ record 8) offset)
        (put-u32! bytes (+ record 12) (alength ^bytes payload)))
      (put-u32! bytes (+ head-offset 8) 0)
      (put-u32! bytes (+ head-record 4)
                (table-checksum bytes head-offset
                                (read-u32 bytes (+ head-record 12))))
      (put-u32! bytes (+ head-offset 8)
                (bit-and (- 0xB1B0AFBA
                            (table-checksum bytes 0 (alength bytes)))
                         0xffffffff))
      bytes)))

(defn- canonicalize [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key child]] [key (canonicalize child)]))
                       value)
    (set? value) (mapv canonicalize (sort-by pr-str value))
    (sequential? value) (mapv canonicalize value)
    :else value))

(defn- stable-text [value]
  (str (pr-str (canonicalize value)) "\n"))

(defn- relative-path [root path]
  (-> (paths/absolute root)
      (.relativize (paths/absolute path))
      str
      (str/replace "\\" "/")))

(defn- source-file [element]
  (some-> (spoon/source-location element) :file paths/absolute str))

(defn- containing-root [roots ^Path file]
  (last (sort-by #(.getNameCount ^Path %)
                 (filter #(.startsWith file ^Path %) roots))))

(defn- discover-inputs! [workspace-root]
  (let [profiles (mapv #(assoc % :configuration
                               (harness/read-profile workspace-root
                                                     (:profile %)))
                       module-specs)
        source-root (paths/resolve-path workspace-root "research/pdfbox")
        _ (project/verify-checkout!
           {:workspace-root workspace-root
            :project-root source-root
            :revision revision})
        reactor
        (maven/discover-reactor!
         {:workspace-root workspace-root
          :project-root source-root
          :selected-projects
          (mapv (comp first :maven-selected-projects :configuration) profiles)})]
    {:source-root source-root
     :modules
     (mapv
      (fn [{:keys [configuration] :as specification}]
        (assoc specification
               :input (maven/project-by-id!
                       reactor (:maven-project-id configuration))))
      profiles)}))

(defn- validate-selected-counts! [{:keys [id input expected-sources
                                          expected-fixtures]}]
  (let [source-count (count (:test-sources input))
        fixture-count (count (:test-resources input))]
    (when-not (= expected-sources source-count)
      (fail! "PdfCarton upstream test-source inventory changed"
             {:reason :pdfcarton-test-source-count-drift
              :module id :expected expected-sources :actual source-count}))
    (when-not (= expected-fixtures fixture-count)
      (fail! "PdfCarton upstream test-resource inventory changed"
             {:reason :pdfcarton-test-resource-count-drift
              :module id :expected expected-fixtures :actual fixture-count}))))

(defn- resolved-input [{:keys [id input]}]
  {:schema-version 1
   :project-id (str "pdfcarton-complete-adapted-tests-" (name id))
   :source-roots (vec (distinct (concat (:source-roots input)
                                        (:generated-source-roots input)
                                        (:test-source-roots input))))
   :resource-roots []
   :production-sources
   (vec (distinct (concat (:production-sources input)
                          (:generated-production-sources input)
                          (:test-sources input))))
   :generated-production-sources []
   :production-resources []
   :test-source-roots []
   :test-resource-roots []
   :test-sources []
   :test-resources []
   :java-toolchain (:java-toolchain input)
   :project-dependencies []
   :external-dependencies (:test-external-dependencies input)
   :classpath-artifacts
   (vec (filter #(= :compile (:scope %))
                (:test-classpath-artifacts input)))
   :test-project-dependencies []
   :test-external-dependencies []
   :test-classpath-artifacts []})

(defn- source-entry [source-root module plan ^Path source]
  (let [canonical (str (paths/absolute source))
        cases (filter #(= canonical
                          (some-> % :source :file paths/absolute str))
                      (:cases plan))
        class-plans
        (filter #(= canonical (source-file (:type-element %)))
                (vals (:classes plan)))]
    {:module module
     :source (relative-path source-root source)
     :sha256 (util/sha256-file source)
     :license "Apache-2.0"
     :attribution (str "Apache PDFBox 3.0.8 at " revision ".")
     :classification (if (seq cases) :ordinary-test :test-helper)
     :case-count (count cases)
     :type-count (count class-plans)}))

(defn- current-fc60-sorted-bytes [^Path resource]
  (let [source (String. (Files/readAllBytes resource) StandardCharsets/UTF_8)
        updated (str/replace source
                             "\u064f\u0622\u0651\u064e"
                             "\u0622\u064f\u064e\u0651")]
    (when (= source updated)
      (fail! "PdfCarton FC60 sorted fixture no longer has the pinned stale order"
             {:reason :pdfcarton-fc60-sorted-fixture-drift
              :resource (str resource)}))
    (.getBytes updated StandardCharsets/UTF_8)))

(defn- fixture-entry [source-root module input ^Path resource]
  (let [root (containing-root (:test-resource-roots input) resource)]
    (when-not root
      (fail! "PdfCarton test resource has no Maven resource root"
             {:reason :pdfcarton-test-resource-root-missing
              :module module :resource (str resource)}))
    (let [destination (-> (.relativize ^Path root resource) str
                          (str/replace "\\" "/"))
          adapt-fc60? (and (= module :pdfbox)
                           (= destination "input/FC60_Times.pdf-sorted.txt"))
          payload (when adapt-fc60? (current-fc60-sorted-bytes resource))]
      (cond->
       {:module module
        :source (relative-path source-root resource)
        :destination destination
        :sha256 (if payload
                  (util/sha256-bytes payload)
                  (util/sha256-file resource))
        :license "Apache-2.0"
        :authorship (if adapt-fc60?
                      :target-adapted-test-fixture
                      :mechanically-upstream-derived)
        :attribution
        (if adapt-fc60?
          (str "Apache PDFBox 3.0.8 test resource at " revision
               "; Arabic combining-mark order refreshed from the pinned "
               "Java runtime behavior.")
          (str "Apache PDFBox 3.0.8 test resource at " revision "."))}
        adapt-fc60? (assoc :generator :pdfcarton-current-fc60-sorted
                           :source-sha256 (util/sha256-file resource))))))

(defn- additional-fixture-entry
  [workspace-root source-root module specification]
  (let [fixture-root (if (:workspace-source specification)
                       workspace-root
                       source-root)
        source (paths/resolve-path fixture-root (:source specification))
        _ (when-not (paths/regular-file? source)
            (fail! "PdfCarton additional test fixture is missing"
                   {:reason :pdfcarton-additional-fixture-missing
                    :module module
                    :source (:source specification)}))
        source-sha256 (util/sha256-file source)
        expected-sha512 (:upstream-sha512 specification)
        actual-sha512 (when expected-sha512 (util/sha512-file source))
        _ (when (and expected-sha512 (not= expected-sha512 actual-sha512))
            (fail! "PdfCarton downloaded test fixture checksum changed"
                   {:reason :pdfcarton-downloaded-fixture-checksum-drift
                    :module module
                    :source (:source specification)
                    :expected expected-sha512
                    :actual actual-sha512}))
        payload
        (case (:generator specification)
          :pdfcarton-cmap-01 (pdfcarton-cmap-01-bytes source)
          nil nil
          (fail! "PdfCarton additional test fixture generator is unknown"
                 {:reason :unknown-pdfcarton-fixture-generator
                  :module module
                  :generator (:generator specification)}))]
    (cond-> (assoc specification
                   :module module
                   :destination
                   (if (or
                        (str/starts-with? (:destination specification) "target/")
                        (str/starts-with? (:destination specification)
                                          "src/test/java/"))
                     (str "modules/" (name module) "/"
                          (:destination specification))
                     (:destination specification))
                   :source-sha256 source-sha256
                   :sha256 (if payload
                             (util/sha256-bytes payload)
                             source-sha256))
      (nil? (:generator specification)) (dissoc :generator))))

(defn- discovered-target-fixture-entries [source-root module]
  (->> (get discovered-target-fixture-roots module [])
       (mapcat
        (fn [relative-root]
          (let [root (paths/resolve-path source-root (name module) relative-root)]
            (when-not (paths/directory? root)
              (fail! "PdfCarton Maven target fixture directory is missing"
                     {:reason :pdfcarton-target-fixture-directory-missing
                      :module module :source (str root)}))
            (with-open [entries
                        (Files/walk root
                                    (make-array java.nio.file.FileVisitOption 0))]
              (->> (iterator-seq (.iterator entries))
                   (filter paths/regular-file?)
                   (mapv
                    (fn [file]
                      (let [source (relative-path source-root file)
                            destination
                            (str "modules/" (name module) "/"
                                 (relative-path
                                  (paths/resolve-path source-root (name module))
                                  file))]
                        {:module module
                         :source source
                         :destination destination
                         :sha256 (util/sha256-file file)
                         :license "Fixture-specific upstream terms"
                         :authorship :third-party-test-fixture
                         :attribution
                         (str "Fixture fetched by the Apache PDFBox 3.0.8 "
                              "Maven test lifecycle at " revision ".")}))))))))
       (sort-by :destination)
       vec))

(defn- selected-file? [selected-files location]
  (contains? selected-files
             (some-> location :file paths/absolute str)))

(defn- occurrence-rows [source-root selected-files resolved-model predicate]
  (->> (:occurrences resolved-model)
       (filter #(selected-file? selected-files (:location %)))
       (filter predicate)
       (map (fn [occurrence]
              {:key (:key occurrence)
               :source (relative-path source-root
                                      (get-in occurrence [:location :file]))
               :line (get-in occurrence [:location :line])
               :column (get-in occurrence [:location :column])}))
       (sort-by (juxt :source :line :column :key))
       vec))

(defn- framework-call? [{:keys [key]}]
  (or (str/starts-with? key "executable:org.junit.")
      (str/starts-with? key "executable:org.mockito.")))

(defn- platform-condition? [{:keys [key]}]
  (or (str/starts-with? key
                        "executable:org.junit.jupiter.api.Assumptions#")
      (str/starts-with? key "executable:java.lang.System#getProperty(")
      (str/starts-with? key "executable:java.lang.System#getenv(")
      (str/starts-with? key "executable:java.io.File#exists(")
      (str/starts-with? key
                        "executable:java.awt.GraphicsEnvironment#")
      (contains?
       #{"executable:java.util.Locale#getDefault()"
         "executable:java.util.Locale#setDefault(java.util.Locale)"
         "executable:java.util.TimeZone#getDefault()"
         "executable:java.util.TimeZone#setDefault(java.util.TimeZone)"}
       key)))

(defn- helper-rows [source-root selected-files plan]
  (let [test-methods (set (map :declaring-method (:cases plan)))]
    (->> (:classes plan)
         vals
         (filter #(contains? selected-files
                             (source-file (:type-element %))))
         (mapcat
          (fn [class-plan]
            (let [source (relative-path source-root
                                        (source-file (:type-element class-plan)))]
              (concat
               [{:kind :type :id (:name class-plan) :source source}]
               (map (fn [method]
                      {:kind (if (contains? test-methods (:id method))
                               :test-method :helper-method)
                       :id (:id method)
                       :source source})
                    (:methods class-plan))
               (map (fn [field]
                      {:kind :field
                       :id (or (:id field) (:name field))
                       :source source})
                    (:fields class-plan))))))
         (sort-by (juxt :source :kind :id))
         vec)))

(defn- parameter-row-records [module cases]
  (->> cases
       (filter :parameters)
       (mapcat
        (fn [test-case]
          (map (fn [row]
                 {:module module
                  :case-id (:id test-case)
                  :row-id row
                  :accounting
                  (if (str/ends-with? row "/runtime")
                    :runtime-member-data
                    :static-row)})
               (junit/row-identities test-case))))
       vec))

(defn- module-inventory [workspace-root source-root specification]
  (validate-selected-counts! specification)
  (let [{:keys [id input]} specification
        sources (vec (sort-by str (:test-sources input)))
        resources (vec (sort-by str (:test-resources input)))
        additional-fixtures
        (into
         (mapv #(additional-fixture-entry workspace-root source-root id %)
               (concat (get additional-fixture-specs id [])
                       (get remote-fixture-specs id [])))
         (discovered-target-fixture-entries source-root id))
        selected-files (set (map (comp str paths/absolute) sources))
        resolved-model
        (spoon/build-resolved-model! workspace-root
                                     (resolved-input specification))
        plan (junit/plan-suite resolved-model
                               (adapters/junit-plan-options))
        cases (->> (:cases plan)
                   (filter #(selected-file? selected-files (:source %)))
                   (sort-by :id)
                   vec)
        unexpected
        (->> (:cases plan)
             (remove #(selected-file? selected-files (:source %)))
             (mapv :id))
        _ (when (seq unexpected)
            (fail! "PdfCarton JUnit plan includes cases outside test sources"
                   {:reason :pdfcarton-test-case-source-drift
                    :module id :cases unexpected}))
        serializable-cases (:cases (junit/serializable-plan
                                    (assoc plan :cases cases)))
        framework-calls
        (occurrence-rows source-root selected-files resolved-model
                         framework-call?)
        platform-conditions
        (occurrence-rows source-root selected-files resolved-model
                         platform-condition?)]
    {:module id
     :project-id (:project-id input)
     :sources (mapv #(source-entry source-root id plan %) sources)
     :fixtures
     (vec
      (sort-by :destination
               (concat (map #(fixture-entry source-root id input %) resources)
                       additional-fixtures)))
     :cases (mapv #(assoc % :module id) serializable-cases)
     :parameter-rows (parameter-row-records id cases)
     :helpers (mapv #(assoc % :module id)
                    (helper-rows source-root selected-files plan))
     :enablement
     (mapv (fn [test-case]
             {:module id
              :case-id (:id test-case)
              :state (if (:disabled test-case) :disabled :enabled)
              :reason (get-in test-case [:disabled :reason])})
           cases)
     :platform-conditions (mapv #(assoc % :module id)
                                platform-conditions)
     :framework-calls (mapv #(assoc % :module id) framework-calls)
     :dependencies
     {:module id
      :production-projects (vec (sort-by pr-str
                                         (:project-dependencies input)))
      :test-projects (vec (sort-by pr-str
                                   (:test-project-dependencies input)))
      :test-external (vec (sort-by pr-str
                                   (:test-external-dependencies input)))}}))

(defn accounting-digests
  "Returns independent hashes for every loss-sensitive PdfCarton suite
  accounting projection."
  [accounting]
  (into (sorted-map)
        (map (fn [field]
               [field (util/sha256-text (stable-text (get accounting field)))])
             digest-fields)))

(defn inventory!
  "Discovers and inventories the complete pinned ordinary Java test inputs for
  all five selected PdfCarton modules. The returned value contains no live
  Spoon objects and is safe to persist in generated provenance ledgers."
  ([] (inventory! (paths/workspace-root)))
  ([workspace-root]
   (let [workspace-root (paths/absolute workspace-root)
         {:keys [source-root modules]} (discover-inputs! workspace-root)
         inventories
         (mapv #(module-inventory workspace-root source-root %) modules)
         accounting
         (into (sorted-map)
               (map (fn [field]
                      [field (vec (mapcat field inventories))])
                    (remove #{:dependencies} digest-fields)))
         accounting (assoc accounting :dependencies
                           (mapv :dependencies inventories))
         module-counts
         (into
          (sorted-map)
          (map (fn [module]
                 [(:module module)
                  {:source-count (count (:sources module))
                   :fixture-count (count (:fixtures module))
                   :case-count (count (:cases module))
                   :parameter-row-count (count (:parameter-rows module))
                   :helper-count (count (:helpers module))
                   :disabled-count
                   (count (filter #(= :disabled (:state %))
                                  (:enablement module)))
                   :platform-condition-count
                   (count (:platform-conditions module))}])
               inventories))
         totals
         {:source-count (count (:sources accounting))
          :fixture-count (count (:fixtures accounting))
          :case-count (count (:cases accounting))
          :parameter-row-count (count (:parameter-rows accounting))
          :helper-count (count (:helpers accounting))
          :disabled-count
          (count (filter #(= :disabled (:state %))
                         (:enablement accounting)))
          :platform-condition-count (count (:platform-conditions accounting))}]
     {:schema-version 1
      :target :pdfcube
      :revision revision
      :modules module-counts
      :totals totals
      :accounting accounting
      :digests (accounting-digests accounting)})))

(defn read-contract!
  "Reads the target-owned expected accounting for the shipped PdfCarton suite."
  [target-root]
  (let [file (paths/resolve-path target-root suite-contract-file)]
    (when-not (paths/regular-file? file)
      (fail! "PdfCarton adapted-suite contract is missing"
             {:reason :missing-pdfcarton-test-suite-contract
              :path (str file)}))
    (let [contract (util/read-single-edn-string! (slurp (str file)))]
      (when-not (= #{:schema-version :target :revision :modules :totals
                     :digests}
                   (set (keys contract)))
        (fail! "PdfCarton adapted-suite contract has missing or unknown fields"
               {:reason :invalid-pdfcarton-test-suite-contract
                :contract contract}))
      (when-not (and (= 1 (:schema-version contract))
                     (= :pdfcube (:target contract))
                     (= revision (:revision contract)))
        (fail! "PdfCarton adapted-suite contract identity changed"
               {:reason :invalid-pdfcarton-test-suite-contract
                :contract contract}))
      contract)))

(defn verify-inventory!
  "Fails closed when any selected source, fixture, case, helper, enablement,
  condition, framework call, or dependency differs from the pinned contract."
  [contract inventory]
  (doseq [field [:modules :totals :digests]]
    (when-not (= (get contract field) (get inventory field))
      (fail! "PdfCarton adapted-suite accounting changed"
             {:reason :pdfcarton-test-accounting-drift
              :section field
              :expected (get contract field)
              :actual (get inventory field)})))
  inventory)

(defn- selected-root-types [resolved-model selected]
  (let [selected-files (set (map (comp str paths/absolute) selected))
        roots (->> (java/project-roots resolved-model)
                   (filter #(contains? selected-files (source-file %)))
                   (sort-by #(.getQualifiedName ^CtType %))
                   vec)
        represented (set (keep source-file roots))
        missing (->> (remove represented selected-files)
                     (remove #(str/ends-with? % "/package-info.java"))
                     sort)]
    (when (seq missing)
      (fail! "Selected PdfCarton Java tests have no declaration root"
             {:reason :pdfcarton-test-source-without-root
              :missing missing}))
    roots))

(defn- method-index [plan]
  (into {}
        (map (juxt :id identity))
        (mapcat :methods (vals (:classes plan)))))

(defn- emitted-method-name [^CtMethod method]
  (if (or (.hasModifier method ModifierKind/PUBLIC)
          (.hasModifier method ModifierKind/PROTECTED))
    (java-library/pascal (.getSimpleName method))
    (java-library/identifier (.getSimpleName method))))

(defn- method-name! [methods id]
  (or (some-> (get methods id) :element ^CtMethod emitted-method-name)
      (fail! "PdfCarton xUnit wrapper references an absent Java method"
             {:reason :missing-pdfcarton-test-method :method id})))

(defn- csharp-string [value]
  (str "\""
       (-> (str value)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\r" "\\r")
           (str/replace "\n" "\\n"))
       "\""))

(defn- rendered-type [context ^CtParameter parameter]
  (:text (csharp/render
          (java-library/type-node context (.getType parameter)))))

(defn- java-string-hash-code [value]
  (reduce (fn [hash character]
            (unchecked-int (+ (unchecked-multiply-int 31 hash)
                              (int character))))
          0
          value))

(defn- wrapper-name [test-case]
  (let [method-name (.getSimpleName ^CtMethod (:method-element test-case))
        signed-hash (java-string-hash-code method-name)
        sortable-hash (+ 2147483648 (long signed-hash))]
    (str "__Upstream_"
         (format "%010d" sortable-hash)
         "_"
         (subs (util/sha256-text (:id test-case)) 0 16))))

(defn- provider-wrapper-name [test-case index]
  (str "__Data_" (subs (util/sha256-text
                        (str (:id test-case) ":" index))
                       0 16)))

(defn- parameter-source-seq [parameters]
  (cond
    (= :composite-sources (:kind parameters)) (:sources parameters)
    parameters [parameters]
    :else []))

(defn- wrapper-attributes [test-case]
  (let [parameters (:parameters test-case)]
    (if (= :member-data (:kind parameters))
      (let [options (cond-> []
                      (:display-name test-case)
                      (conj (str "DisplayName = "
                                 (csharp-string (:display-name test-case))))
                      (:disabled test-case)
                      (conj (str "Skip = "
                                 (csharp-string
                                  (get-in test-case [:disabled :reason])))))]
        [(str "[Xunit.Theory"
              (when (seq options) (str "(" (str/join ", " options) ")"))
              "]")
         (str "[Xunit.MemberData(nameof("
              (provider-wrapper-name test-case 0) "))]")])
      (junit/xunit-attributes test-case))))

(defn- timeout-milliseconds [timeout]
  (when timeout
    (let [value (:value timeout)
          unit (:unit timeout)
          multiplier
          (case unit
            (:milliseconds
             {:field "field:java.util.concurrent.TimeUnit#MILLISECONDS"}) 1
            (:seconds
             {:field "field:java.util.concurrent.TimeUnit#SECONDS"}) 1000
            (:minutes
             {:field "field:java.util.concurrent.TimeUnit#MINUTES"}) 60000
            (:hours
             {:field "field:java.util.concurrent.TimeUnit#HOURS"}) 3600000
            (fail! "PdfCarton JUnit timeout unit has no xUnit lowering"
                   {:reason :unsupported-pdfcarton-timeout-unit
                    :timeout timeout}))]
      (* value multiplier))))

(defn- mock-initializers [context test-case]
  (for [{:keys [field] :as fixture}
        (get-in test-case [:mockito-fixture :fields])]
    (let [field-element (:field-element fixture)
          destination-type
          (:text (csharp/render
                  (java-library/type-node context (.getType field-element))))]
      (str "        this." field
           " = global::DripSharp.Testing.JavaMockito.Mock<"
           destination-type ">();\n"))))

(defn- render-wrapper [context methods class-plan test-case]
  (let [^CtMethod method (:method-element test-case)
        parameters (vec (.getParameters method))
        inline-row-width
        (letfn [(width [source]
                  (case (:kind source)
                    :inline-rows
                    (reduce max 0 (map (comp count :arguments) (:rows source)))
                    :composite-sources
                    (reduce max 0 (map width (:sources source)))
                    0))]
          (width (:parameters test-case)))
        synthetic-count (max 0 (- inline-row-width (count parameters)))
        declarations
        (into
         (mapv (fn [^CtParameter parameter]
                 (str (rendered-type context parameter) " "
                      (java-library/identifier (.getSimpleName parameter))))
               parameters)
         (map #(str "object __upstreamArgument" %) (range synthetic-count)))
        arguments (mapv #(java-library/identifier
                          (.getSimpleName ^CtParameter %))
                        parameters)
        before (get-in test-case [:lifecycle :before-each])
        after (get-in test-case [:lifecycle :after-each])
        before-all (get-in class-plan [:lifecycle :before-all])
        after-all (get-in class-plan [:lifecycle :after-all])
        paired-class-lifecycle? (seq after-all)
        method-call (str "this." (.getSimpleName method)
                         "(" (str/join ", " arguments) ")")
        invocation
        (if-let [milliseconds (timeout-milliseconds (:timeout test-case))]
          (str "global::DripSharp.PdfCarton.Tests.Support.RunWithTimeout(() => "
               method-call ", " milliseconds ");")
          (str method-call ";"))]
    (str (str/join "\n" (wrapper-attributes test-case)) "\n"
         "public void " (wrapper-name test-case)
         "(" (str/join ", " declarations) ")\n{\n"
         (when (and (seq before-all) (empty? after-all))
           (str "        if (!__UpstreamBeforeAll)\n"
                "            throw new global::System.InvalidOperationException("
                "\"Upstream @BeforeAll initialization failed.\");\n"))
         (apply str (mock-initializers context test-case))
         (when paired-class-lifecycle?
           (apply str
                  (map #(str "        " (method-name! methods %)
                             "();\n")
                       before-all)))
         (apply str
                (map #(str "        this." (method-name! methods %)
                           "();\n")
                     before))
         "        try\n        {\n"
         "            " invocation "\n"
         "        }\n        finally\n        {\n"
         (apply str
                (map #(str "            this." (method-name! methods %)
                           "();\n")
                     after))
         (when paired-class-lifecycle?
           (apply str
                  (map #(str "            " (method-name! methods %)
                             "();\n")
                       after-all)))
         "        }\n}")))

(defn- render-provider-wrapper [context class-plan test-case index source]
  (let [providers (:providers source)]
    (when-not (= 1 (count providers))
      (fail! "PdfCarton @MethodSource must resolve to one provider"
             {:reason :unsupported-pdfcarton-method-source
              :case (:id test-case) :source source}))
    (let [provider (first providers)
          provider-method
          (some (fn [{:keys [element]}]
                  (when (= provider (.getSimpleName ^CtMethod element))
                    element))
                (:methods class-plan))
          provider (if provider-method
                     (emitted-method-name provider-method)
                     provider)
          parameters (vec (.getParameters ^CtMethod (:method-element test-case)))
          adapted-arguments
          (map-indexed
           (fn [argument-index ^CtParameter parameter]
             (str "global::DripSharp.PdfCarton.Tests.Support.TheoryArgument<"
                  (rendered-type context parameter)
                  ">(row[" argument-index "])"))
           parameters)]
      (str "public static global::System.Collections.Generic.IEnumerable<object[]> "
           (provider-wrapper-name test-case index) "()\n{\n"
           "    foreach (var value in " provider "())\n    {\n"
           "        object[] row = ((object?)value is object[] values)\n"
           "            ? values : new object[] { value! };\n"
           "        yield return new object[] { "
           (str/join ", " adapted-arguments) " };\n"
           "    }\n}"))))

(defn- render-class-lifecycle [methods class-plan]
  (let [before-all (get-in class-plan [:lifecycle :before-all])
        after-all (get-in class-plan [:lifecycle :after-all])]
    (when (and (seq before-all) (empty? after-all))
      (str "private static readonly bool __UpstreamBeforeAll = "
           "__RunUpstreamBeforeAll();\n\n"
           "private static bool __RunUpstreamBeforeAll()\n{\n"
           (apply str
                  (map #(str "    " (method-name! methods %) "();\n")
                       before-all))
           "    return true;\n}"))))

(defn- emitted-members [base-translate-member plan semantic-errors]
  (let [methods (method-index plan)
        cases-by-class (group-by :class (:cases plan))]
    (fn [context ^CtType owner members]
      (let [class-name (.getQualifiedName owner)
            cases (sort-by :id (get cases-by-class class-name))
            class-plan (get-in plan [:classes class-name])
            ordinary
            (mapv
             (fn [member]
               (try
                 (base-translate-member context owner member)
                 (catch Throwable exception
                   (let [data (ex-data exception)
                         diagnostic (:diagnostic data)]
                     (swap!
                      semantic-errors conj
                      {:source-file (source-file owner)
                       :type (.getQualifiedName owner)
                       :member (.getSimpleName ^CtNamedElement member)
                       :message (.getMessage exception)
                       :kind (:kind data)
                       :reason (:reason data)
                       :resolved (:resolved diagnostic)
                       :location (:location diagnostic)
                       :diagnostic-message (:message diagnostic)})
                     (csharp/raw "")))))
             members)
            providers
            (mapcat
             (fn [test-case]
               (keep-indexed
                (fn [index source]
                  (when (= :member-data (:kind source))
                    (csharp/raw
                     (render-provider-wrapper context class-plan test-case index
                                              source))))
                (parameter-source-seq (:parameters test-case))))
             cases)
            wrappers
            (mapv #(csharp/raw (render-wrapper context methods class-plan %))
                  cases)
            lifecycle (some-> (render-class-lifecycle methods class-plan)
                              csharp/raw)]
        (vec (concat ordinary providers wrappers (when lifecycle [lifecycle])))))))

(defn- mark-test-classes-public! [plan]
  (doseq [class-name (distinct (map :class (:cases plan)))
          :let [^CtType type (get-in plan [:classes class-name :type-element])]
          :when (and type (.isTopLevel type))]
    (.addModifier ^CtModifiable type ModifierKind/PUBLIC)
    (when (instance? CtClass type)
      (let [constructors (vec (.getConstructors ^CtClass type))]
        (when (= 1 (count constructors))
          (.addModifier ^CtModifiable (first constructors) ModifierKind/PUBLIC)))))
  plan)

(def ^:private accepted-test-constructors
  #{"executable:java.io.File#<init>(java.lang.String)"
    "executable:java.io.File#<init>(java.io.File,java.lang.String)"
    "executable:java.awt.image.ColorConvertOp#<init>(java.awt.color.ColorSpace,java.awt.color.ColorSpace,java.awt.RenderingHints)"
    "executable:java.awt.image.ColorConvertOp#<init>(java.awt.color.ColorSpace,java.awt.RenderingHints)"
    "executable:java.awt.image.ComponentColorModel#<init>(java.awt.color.ColorSpace,int[],boolean,boolean,int,int)"
    "executable:java.io.OutputStreamWriter#<init>(java.io.OutputStream,java.lang.String)"
    "executable:java.io.PrintStream#<init>(java.io.File,java.lang.String)"
    "executable:java.io.PrintWriter#<init>(java.io.OutputStream)"
    "executable:java.awt.image.DirectColorModel#<init>(java.awt.color.ColorSpace,int,int,int,int,int,boolean,int)"
    "executable:java.lang.IllegalStateException#<init>(java.lang.Throwable)"
    "executable:java.math.BigDecimal#<init>(double)"
    "executable:java.lang.String#<init>(byte[])"
    "executable:java.lang.String#<init>(byte[],int,int)"
    "executable:java.lang.String#<init>(byte[],java.lang.String)"
    "executable:java.lang.StringBuffer#<init>()"
    "executable:java.text.SimpleDateFormat#<init>(java.lang.String)"
    "executable:java.util.GregorianCalendar#<init>(int,int,int)"
    "executable:java.util.GregorianCalendar#<init>(int,int,int,int,int,int)"
    "executable:java.util.Date#<init>(long)"
    "executable:java.util.Locale$Builder#<init>()"
    "executable:java.util.Random#<init>(long)"
    "executable:java.util.concurrent.CountDownLatch#<init>(int)"})

(def ^:private test-resolved-names
  {"executable:java.io.ByteArrayOutputStream#toString()" "ToString"
   "executable:org.junit.jupiter.params.provider.Arguments#of(java.lang.Object[])"
   "Of"
   "executable:java.lang.Class#getResource(java.lang.String)" "GetResource"
   "executable:java.lang.Class#getResourceAsStream(java.lang.String)"
   "GetResourceAsStream"
   "executable:java.lang.Class#getDeclaredFields()" "GetDeclaredFields"
   "executable:java.lang.reflect.Field#getType()" "GetType"
   "executable:java.lang.reflect.Field#getName()" "GetName"
   "executable:java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[])"
   "Invoke"
   "executable:java.util.List#clear()" "Clear"
   "executable:java.lang.String#equals(java.lang.Object)" "Equals"
   "executable:org.apache.pdfbox.pdmodel.PDDocument#close()" "Dispose"
   "executable:java.nio.charset.Charset#displayName()" "DisplayName"
   "executable:java.math.BigInteger#toString()" "ToString"
   "executable:java.math.BigDecimal#pow(int,java.math.MathContext)" "Pow"
   "executable:java.math.BigDecimal#compareTo(java.math.BigDecimal)" "CompareTo"
   "executable:java.math.BigDecimal#add(java.math.BigDecimal)" "Add"
   "executable:java.math.BigDecimal#subtract(java.math.BigDecimal)" "Subtract"
   "executable:java.math.BigDecimal#abs()" "Abs"
   "executable:java.math.BigDecimal#floatValue()" "FloatValue"
   "executable:java.lang.Float#intBitsToFloat(int)" "IntBitsToFloat"
   "executable:java.lang.Character#isWhitespace(int)" "IsWhitespace"
   "executable:java.lang.Character#toChars(int)" "ToChars"
   "executable:java.lang.Character#isISOControl(int)" "IsISOControl"
   "executable:java.lang.StringBuffer#append(java.lang.String)" "Append"
   "executable:java.lang.StringBuffer#toString()" "ToString"
   "executable:java.io.File#mkdirs()" "Mkdirs"
   "executable:java.io.File#getParentFile()" "GetParentFile"
   "executable:java.io.File#listFiles(java.io.FilenameFilter)" "ListFiles"
   "executable:java.io.File#deleteOnExit()" "DeleteOnExit"
   "executable:java.io.FileInputStream#read(byte[])" "Read"
   "executable:java.io.FileInputStream#read(byte[],int,int)" "Read"
   "executable:java.io.FileInputStream#close()" "Close"
   "executable:java.io.FileOutputStream#close()" "Close"
   "executable:java.io.FileOutputStream#write(byte[])" "Write"
   "executable:java.io.LineNumberReader#getLineNumber()" "GetLineNumber"
   "executable:java.io.PrintStream#println(java.lang.Object)" "Println"
   "executable:java.io.PrintWriter#write(java.lang.String)" "Write"
   "executable:java.nio.file.Files#delete(java.nio.file.Path)" "Delete"
   "executable:java.nio.file.Files#getFileStore(java.nio.file.Path)" "GetFileStore"
   "executable:java.nio.file.FileStore#supportsFileAttributeView(java.lang.String)"
   "SupportsFileAttributeView"
   "executable:java.nio.file.Files#getPosixFilePermissions(java.nio.file.Path,java.nio.file.LinkOption[])"
   "GetPosixFilePermissions"
   "executable:java.nio.file.Files#newBufferedWriter(java.nio.file.Path,java.nio.file.OpenOption[])"
   "NewBufferedWriter"
   "executable:java.util.Random#nextBoolean()" "NextBoolean"
   "executable:java.util.Random#nextFloat()" "NextFloat"
   "executable:java.util.Random#nextDouble()" "NextDouble"
   "executable:java.util.Random#nextInt(int)" "NextInt"
   "executable:java.util.Random#setSeed(long)" "SetSeed"
   "executable:java.util.Arrays#sort(java.lang.Object[])" "Sort"
   "executable:java.util.Arrays#equals(java.lang.Object[],java.lang.Object[])"
   "Equals"
   "executable:java.util.Arrays#binarySearch(java.lang.Object[],java.lang.Object)"
   "BinarySearch"
   "executable:java.util.TreeSet#isEmpty()" "IsEmpty"
   "executable:java.util.TreeSet#last()" "Last"
   "executable:java.util.Hashtable#put(java.lang.Object,java.lang.Object)" "Put"
   "executable:java.util.Locale#setDefault(java.util.Locale)" "SetDefault"
   "executable:java.util.OptionalLong#getAsLong()" "GetAsLong"
   "executable:java.lang.Comparable#compareTo(java.lang.Object)" "CompareTo"
   "executable:java.util.Calendar#getInstance()" "GetInstance"
   "executable:java.util.Calendar#getTime()" "GetTime"
   "executable:java.util.Calendar#toInstant()" "ToInstant"
   "executable:java.util.TimeZone#getDefault()" "GetDefault"
   "executable:java.util.TimeZone#setDefault(java.util.TimeZone)" "SetDefault"
   "executable:java.util.concurrent.atomic.AtomicBoolean#set(boolean)" "Set"
   "executable:java.lang.Thread#setUncaughtExceptionHandler(java.lang.Thread$UncaughtExceptionHandler)"
   "SetUncaughtExceptionHandler"
   "executable:java.util.concurrent.CountDownLatch#await()" "Await"
   "executable:java.util.concurrent.CountDownLatch#countDown()" "CountDown"
   "executable:java.util.stream.LongStream#max()" "Max"
   "executable:java.util.stream.Stream#reduce(java.lang.Object,java.util.function.BinaryOperator)"
   "Reduce"
   "executable:java.util.stream.Stream#of(java.lang.Object)" "Of"
   "executable:java.util.stream.Stream#limit(long)" "Limit"
   "executable:java.time.format.DateTimeFormatter#ofPattern(java.lang.String)"
   "OfPattern"
   "executable:java.time.chrono.ChronoZonedDateTime#toInstant()" "ToInstant"
   "executable:java.security.cert.CertificateFactory#generateCertificate(java.io.InputStream)"
   "GenerateCertificate"
   "executable:java.awt.Color#getRGB()" "GetRGB"
   "executable:java.awt.GraphicsConfiguration#createCompatibleImage(int,int,int)"
   "CreateCompatibleImage"
   "executable:java.awt.geom.Area#equals(java.awt.geom.Area)" "Equals"
   "executable:java.awt.image.BufferedImage#getPropertyNames()" "GetPropertyNames"
   "executable:java.awt.image.BufferedImage#getProperty(java.lang.String)"
   "GetProperty"
   "executable:java.awt.image.BufferedImage#isAlphaPremultiplied()"
   "IsAlphaPremultiplied"
   "executable:java.awt.image.BufferedImage#getSubimage(int,int,int,int)"
   "GetSubimage"
   "executable:java.awt.image.BufferedImage#copyData(java.awt.image.WritableRaster)"
   "CopyData"
   "executable:java.awt.image.ColorModel#isAlphaPremultiplied()"
   "IsAlphaPremultiplied"
   "executable:java.awt.image.ColorModel#getComponentSize()"
   "GetComponentSize"
   "executable:java.awt.image.WritableRaster#setDataElements(int,int,int,int,java.lang.Object)"
   "SetDataElements"
   "executable:java.awt.image.ColorModel#getTransparency()" "GetTransparency"
   "executable:javax.imageio.ImageIO#read(java.net.URL)" "Read"
   "executable:javax.imageio.ImageIO#write(java.awt.image.RenderedImage,java.lang.String,java.io.File)"
   "Write"
   "executable:javax.imageio.ImageIO#write(java.awt.image.RenderedImage,java.lang.String,java.io.OutputStream)"
   "Write"
   "executable:javax.imageio.ImageIO#getImageReaders(java.lang.Object)"
   "GetImageReaders"
   "executable:javax.imageio.ImageWriter#getOriginatingProvider()"
   "GetOriginatingProvider"
   "executable:javax.imageio.ImageReader#getNumImages(boolean)"
   "GetNumImages"
   "executable:javax.imageio.ImageReader#read(int)" "Read"
   "executable:javax.imageio.spi.ImageWriterSpi#canEncodeImage(java.awt.image.RenderedImage)"
   "CanEncodeImage"
   "executable:java.awt.image.DirectColorModel#getMasks()" "GetMasks"
   "executable:java.awt.image.PackedColorModel#getMasks()" "GetMasks"
   "executable:java.awt.image.Raster#createPackedRaster(int,int,int,int[],java.awt.Point)"
   "CreatePackedRaster"
   "executable:java.util.Locale$Builder#setLanguageTag(java.lang.String)"
   "SetLanguageTag"
   "executable:java.util.Locale$Builder#build()" "Build"
   "executable:difflib.DiffUtils#diff(java.util.List,java.util.List)" "Diff"
   "executable:difflib.Patch#getDeltas()" "GetDeltas"
   "executable:difflib.Delta#getOriginal()" "GetOriginal"
   "executable:difflib.Delta#getRevised()" "GetRevised"
   "executable:org.apache.commons.io.FileUtils#listFiles(java.io.File,java.lang.String[],boolean)"
   "ListFiles"
   "executable:org.apache.commons.logging.LogFactory#getLog(java.lang.String)"
   "GetLog"})

(defn- enclosing-stream-return-argument [element]
  (loop [current element]
    (cond
      (nil? current) nil
      (instance? CtMethod current)
      (let [reference (.getType ^CtMethod current)]
        (when (= "java.util.stream.Stream" (.getQualifiedName reference))
          (first (.getActualTypeArguments reference))))
      (.isParentInitialized current) (recur (.getParent current))
      :else nil)))

(defn- stream-of-node [destination-context element arguments]
  (let [invocation-argument
        (some-> element .getType .getActualTypeArguments first)
        reference
        (or (when-not
             (instance? spoon.reflect.reference.CtTypeParameterReference
                        invocation-argument)
              invocation-argument)
            (enclosing-stream-return-argument element))]
    (if reference
      (let [type-node (java-library/type-node destination-context reference)]
        (csharp/sequence-node
         [(csharp/raw "global::DripSharp.Runtime.JavaCompat.Stream<")
          type-node
          (csharp/raw ">(global::DripSharp.Runtime.JavaCompat.StreamOf<")
          type-node (csharp/raw ">(")
          (csharp/sequence-node arguments ", ")
          (csharp/raw "))")]))
      (csharp/sequence-node
       [(csharp/raw "global::DripSharp.Runtime.JavaCompat.Stream(")
        (csharp/raw "global::DripSharp.Runtime.JavaCompat.StreamOf(")
        (csharp/sequence-node arguments ", ")
        (csharp/raw "))")]))))

(defn- test-invocation
  [{:keys [destination-context element occurrence target-node arguments]}]
  (let [key (:key occurrence)
        module-name (name (:pdfcarton-test-module destination-context))]
    (cond
      (contains?
       #{"executable:java.nio.file.Paths#get(java.lang.String,java.lang.String[])"
         "executable:java.nio.file.Path#of(java.lang.String,java.lang.String[])"}
       key)
      (csharp/invocation
       (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.TestPath")
       [(csharp/raw (pr-str module-name))
        (csharp/invocation
         (csharp/raw "global::DripSharp.Runtime.JavaCompat.PathOf")
         arguments)])

      (str/starts-with?
       (or key "")
       "executable:org.apache.pdfbox.io.IOUtils#closeQuietly(")
      (csharp/invocation
       (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.CloseQuietly")
       arguments)

      (str/starts-with?
       (or key "")
       "executable:org.apache.pdfbox.io.IOUtils#closeAndLogException(")
      (csharp/invocation
       (csharp/raw
        "global::DripSharp.PdfCarton.Tests.Support.CloseAndLogException")
       arguments)

      (= key
         "executable:org.apache.commons.io.FileUtils#listFiles(java.io.File,java.lang.String[],boolean)")
      (csharp/invocation
       (csharp/raw
        (if (str/ends-with? (or (source-file element) "") "/Benchmark.java")
          "global::DripSharp.PdfCarton.Tests.Support.ListFiles"
          "global::DripSharp.PdfCarton.Tests.Support.ListFilesObjects"))
       arguments)

      (= key "executable:java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[])")
      (csharp/sequence-node
       [target-node (csharp/raw ".Invoke(") (first arguments)
        (csharp/raw ", new object?[] { ")
        (csharp/sequence-node (vec (rest arguments)) ", ")
        (csharp/raw " })")])

      (= key "executable:java.util.Arrays#equals(java.lang.Object[],java.lang.Object[])")
      (csharp/invocation
       (csharp/raw "global::DripSharp.Testing.JavaAssertions.DeepEqual")
       arguments)

      (= key "executable:java.io.File#exists()")
      (csharp/invocation
       (csharp/raw "global::System.IO.File.Exists")
       [(csharp/member target-node "FullName")])

      (str/starts-with? (or key "")
                        "executable:java.nio.file.Files#write(")
      (csharp/invocation
       (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.WriteAllBytes")
      (vec (take 2 arguments)))

      :else
      (case key
    "executable:org.junit.jupiter.params.provider.Arguments#of(java.lang.Object[])"
    (csharp/sequence-node
     [(csharp/raw "new object[] { ")
      (csharp/sequence-node arguments ", ")
      (csharp/raw " }")])

    "executable:javax.xml.xpath.XPathFactory#newInstance()"
    (csharp/raw
     "global::DripSharp.PdfCarton.Tests.JavaTestXPathFactory.Instance")

    "executable:java.io.ByteArrayOutputStream#toString()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.OutputText")
     [target-node])

    "executable:java.lang.Class#getResource(java.lang.String)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ResourceUri")
     (into [target-node] arguments))

    "executable:java.lang.Class#getResourceAsStream(java.lang.String)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ResourceStream")
     (into [target-node] arguments))

    "executable:java.lang.Class#getDeclaredFields()"
    (csharp/invocation
     (csharp/member target-node "GetFields")
     [(csharp/raw
       (str "global::System.Reflection.BindingFlags.Instance | "
            "global::System.Reflection.BindingFlags.Static | "
            "global::System.Reflection.BindingFlags.Public | "
            "global::System.Reflection.BindingFlags.NonPublic | "
            "global::System.Reflection.BindingFlags.DeclaredOnly"))])

    "executable:java.lang.reflect.Field#getType()"
    (csharp/member target-node "FieldType")

    "executable:java.lang.reflect.Field#getName()"
    (csharp/member target-node "Name")

    "executable:java.nio.charset.Charset#displayName()"
    (csharp/member target-node "WebName")

    "executable:java.math.BigInteger#toString()"
    (csharp/invocation (csharp/member target-node "ToString") [])

    "executable:java.math.BigDecimal#pow(int,java.math.MathContext)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalPow")
     [target-node (first arguments)])

    "executable:java.math.BigDecimal#compareTo(java.math.BigDecimal)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalCompare")
     (into [target-node] arguments))

    "executable:java.math.BigDecimal#add(java.math.BigDecimal)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalAdd")
     (into [target-node] arguments))

    "executable:java.math.BigDecimal#subtract(java.math.BigDecimal)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalSubtract")
     (into [target-node] arguments))

    "executable:java.math.BigDecimal#abs()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalAbs")
     [target-node])

    "executable:java.math.BigDecimal#floatValue()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalFloatValue")
     [target-node])

    "executable:java.nio.file.Files#delete(java.nio.file.Path)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.DeleteIfExists")
     arguments)

    "executable:java.nio.file.Files#getFileStore(java.nio.file.Path)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.FileStore")
     arguments)

    "executable:java.nio.file.FileStore#supportsFileAttributeView(java.lang.String)"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.PdfCarton.Tests.Support.SupportsFileAttributeView")
     (into [target-node] arguments))

    "executable:java.nio.file.Files#getPosixFilePermissions(java.nio.file.Path,java.nio.file.LinkOption[])"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.PdfCarton.Tests.Support.GetPosixFilePermissions")
     [(first arguments)])

    "executable:java.nio.file.Files#newBufferedWriter(java.nio.file.Path,java.nio.file.OpenOption[])"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.NewBufferedWriter")
     arguments)

    "executable:java.util.Random#nextBoolean()"
    (csharp/invocation (csharp/member target-node "NextBoolean") arguments)

    "executable:java.util.Random#nextFloat()"
    (csharp/invocation (csharp/member target-node "NextFloat") arguments)

    "executable:java.util.Random#nextDouble()"
    (csharp/invocation (csharp/member target-node "NextDouble") arguments)

    "executable:java.util.Random#nextInt(int)"
    (csharp/invocation (csharp/member target-node "NextInt") arguments)

    "executable:java.util.Arrays#sort(java.lang.Object[])"
    (csharp/invocation (csharp/raw "global::System.Array.Sort") arguments)

    "executable:java.util.Arrays#binarySearch(java.lang.Object[],java.lang.Object)"
    (csharp/invocation
     (csharp/raw "global::System.Array.BinarySearch")
     arguments)

    "executable:java.util.Calendar#getInstance()"
    (csharp/raw "global::System.DateTimeOffset.Now")

    "executable:java.util.Calendar#setTimeInMillis(long)"
    (csharp/sequence-node
     [target-node
      (csharp/raw " = ")
      (csharp/invocation
       (csharp/raw
        "global::DripSharp.PdfCarton.Tests.Support.CalendarFromUnixTimeMilliseconds")
       arguments)])

    "executable:java.util.Calendar#getTime()"
    target-node

    "executable:java.util.Calendar#toInstant()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.ToInstant")
     [target-node])

    "executable:java.util.TimeZone#getDefault()"
    (csharp/raw "global::System.TimeZoneInfo.Local")

    "executable:java.util.TimeZone#setDefault(java.util.TimeZone)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.SetDefaultTimeZone")
     arguments)

    "executable:java.util.concurrent.atomic.AtomicBoolean#set(boolean)"
    (csharp/invocation (csharp/member target-node "Set") arguments)

    "executable:java.lang.Thread#setUncaughtExceptionHandler(java.lang.Thread$UncaughtExceptionHandler)"
    (csharp/invocation
     (csharp/member target-node "SetUncaughtExceptionHandler")
     arguments)

    "executable:java.util.concurrent.CountDownLatch#await()"
    (csharp/invocation (csharp/member target-node "Wait") [])

    "executable:java.util.concurrent.CountDownLatch#countDown()"
    (csharp/invocation (csharp/member target-node "Signal") [])

    "executable:java.util.stream.Stream#of(java.lang.Object)"
    (stream-of-node destination-context element arguments)

    "executable:java.time.format.DateTimeFormatter#ofPattern(java.lang.String)"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.Runtime.JavaDateTimeFormatter.IsoLocalDateTimeOffset")
     [])

    "executable:java.time.chrono.ChronoZonedDateTime#toInstant()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.ToInstant")
     [target-node])

    "executable:java.lang.Float#intBitsToFloat(int)"
    (csharp/invocation
     (csharp/raw "global::System.BitConverter.Int32BitsToSingle")
     arguments)

    "executable:java.lang.Character#isWhitespace(int)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.IsWhitespace")
     arguments)

    "executable:java.lang.Character#toChars(int)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ToChars")
     arguments)

    "executable:java.lang.Character#isISOControl(int)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.IsISOControl")
     arguments)

    "executable:java.io.File#mkdirs()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.Mkdirs")
     [target-node])

    "executable:java.io.File#getParentFile()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ParentFile")
     [target-node])

    "executable:java.io.File#listFiles(java.io.FilenameFilter)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ListFiles")
     (into [target-node] arguments))

    "executable:java.io.File#deleteOnExit()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.DeleteOnExit")
     [target-node])

    "executable:java.io.FileInputStream#read(byte[])"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.InputStreamRead")
     (into [target-node] arguments))

    "executable:java.io.FileInputStream#read(byte[],int,int)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.InputStreamRead")
     (into [target-node] arguments))

    "executable:java.io.FileInputStream#close()"
    (csharp/invocation (csharp/member target-node "Dispose") [])

    "executable:java.io.FileOutputStream#close()"
    (csharp/invocation (csharp/member target-node "Dispose") [])

    "executable:java.io.FileOutputStream#write(byte[])"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.OutputStreamWrite")
     (into [target-node] arguments))

    "executable:java.io.LineNumberReader#getLineNumber()"
    (csharp/raw "0")

    "executable:java.io.PrintStream#println(java.lang.Object)"
    (csharp/invocation (csharp/member target-node "WriteLine") arguments)

    "executable:java.io.PrintWriter#write(java.lang.String)"
    (csharp/invocation (csharp/member target-node "Write") arguments)

    "executable:java.lang.StringBuffer#append(java.lang.String)"
    (csharp/invocation (csharp/member target-node "Append") arguments)

    "executable:java.lang.StringBuffer#toString()"
    (csharp/invocation (csharp/member target-node "ToString") [])

    "executable:java.util.TreeSet#isEmpty()"
    (csharp/binary "==" 40 (csharp/member target-node "Count")
                   (csharp/raw "0"))

    "executable:java.util.TreeSet#last()"
    (csharp/invocation
     (csharp/raw "global::System.Linq.Enumerable.Last")
     [target-node])

    "executable:java.util.Hashtable#put(java.lang.Object,java.lang.Object)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.Runtime.JavaCompat.MapPut")
     (into [target-node] arguments))

    "executable:java.util.Locale#setDefault(java.util.Locale)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.SetDefaultCulture")
     arguments)

    "executable:java.util.OptionalLong#getAsLong()"
    (csharp/member target-node "Value")

    "executable:java.lang.Comparable#compareTo(java.lang.Object)"
    (csharp/invocation (csharp/member target-node "CompareTo") arguments)

    "executable:java.util.Random#setSeed(long)"
    (csharp/invocation (csharp/member target-node "SetSeed") arguments)

    "executable:java.util.Arrays#equals(java.lang.Object[],java.lang.Object[])"
    (csharp/invocation
     (csharp/raw "global::System.Linq.Enumerable.SequenceEqual")
     arguments)

    "executable:java.util.stream.LongStream#max()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.MaxLong")
     [target-node])

    "executable:java.util.stream.Stream#reduce(java.lang.Object,java.util.function.BinaryOperator)"
    (csharp/invocation
     (csharp/raw "global::System.Linq.Enumerable.Aggregate")
     (into [target-node] arguments))

    "executable:java.util.stream.Stream#limit(long)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.Limit")
     (into [target-node] arguments))

    "executable:java.security.cert.CertificateFactory#generateCertificate(java.io.InputStream)"
    (csharp/invocation
     (csharp/member target-node "GenerateCertificate")
     arguments)

    "executable:java.awt.Color#getRGB()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ColorRgb")
     [target-node])

    "executable:java.awt.GraphicsConfiguration#createCompatibleImage(int,int,int)"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.PdfCarton.Tests.Support.CreateCompatibleImage")
     arguments)

    "executable:java.awt.geom.Area#equals(java.awt.geom.Area)"
    (csharp/invocation (csharp/member target-node "Equals") arguments)

    "executable:java.awt.image.BufferedImage#getPropertyNames()"
    (csharp/raw "global::System.Array.Empty<string>()")

    "executable:java.awt.image.BufferedImage#getProperty(java.lang.String)"
    (csharp/raw "null")

    "executable:java.awt.image.BufferedImage#isAlphaPremultiplied()"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.PdfCarton.Tests.Support.IsAlphaPremultiplied")
     [target-node])

    "executable:java.awt.image.BufferedImage#getSubimage(int,int,int,int)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.Subimage")
     (into [target-node] arguments))

    "executable:java.awt.image.BufferedImage#copyData(java.awt.image.WritableRaster)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.CopyImageData")
     (into [target-node] arguments))

    "executable:java.awt.image.ColorModel#isAlphaPremultiplied()"
    (csharp/raw "false")

    "executable:java.awt.image.ColorModel#getComponentSize()"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ComponentSizes")
     [target-node])

    "executable:java.awt.image.WritableRaster#setDataElements(int,int,int,int,java.lang.Object)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.SetDataElements")
     (into [target-node] arguments))

    "executable:java.awt.image.ColorModel#getTransparency()"
    (csharp/invocation
     (csharp/raw
      "global::DripSharp.PdfCarton.Tests.Support.ColorModelTransparency")
     [target-node])

    "executable:javax.imageio.ImageIO#read(java.net.URL)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ReadImage")
     arguments)

    "executable:javax.imageio.ImageIO#write(java.awt.image.RenderedImage,java.lang.String,java.io.File)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.WriteImage")
     arguments)

    "executable:javax.imageio.ImageIO#write(java.awt.image.RenderedImage,java.lang.String,java.io.OutputStream)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.WriteImage")
     arguments)

    "executable:javax.imageio.ImageIO#getImageReaders(java.lang.Object)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.GetImageReaders")
     arguments)

    "executable:javax.imageio.ImageWriter#getOriginatingProvider()"
    target-node

    "executable:javax.imageio.ImageReader#getNumImages(boolean)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.ImageCount")
     [target-node])

    "executable:javax.imageio.ImageReader#read(int)"
    (csharp/invocation
     (csharp/member target-node "Read")
     [(first arguments) (csharp/raw "null")])

    "executable:java.awt.image.Raster#createPackedRaster(int,int,int,int[],java.awt.Point)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.CreatePackedRaster")
     arguments)

    "executable:javax.imageio.spi.ImageWriterSpi#canEncodeImage(java.awt.image.RenderedImage)"
    (csharp/raw "true")

    "executable:java.awt.image.DirectColorModel#getMasks()"
    (csharp/invocation (csharp/member target-node "GetMasks") [])

    "executable:java.awt.image.PackedColorModel#getMasks()"
    (csharp/invocation (csharp/member target-node "GetMasks") [])

    "executable:java.util.Locale$Builder#setLanguageTag(java.lang.String)"
    (csharp/invocation (csharp/member target-node "SetLanguageTag") arguments)

    "executable:java.util.Locale$Builder#build()"
    (csharp/invocation (csharp/member target-node "Build") [])

    "executable:difflib.DiffUtils#diff(java.util.List,java.util.List)"
    (csharp/invocation
     (csharp/raw "global::DripSharp.PdfCarton.Tests.JavaDiffUtils.Diff")
     arguments)

    "executable:difflib.Patch#getDeltas()"
    (csharp/invocation (csharp/member target-node "GetDeltas") [])

    "executable:difflib.Delta#getOriginal()"
    (csharp/invocation (csharp/member target-node "GetOriginal") [])

    "executable:difflib.Delta#getRevised()"
    (csharp/invocation (csharp/member target-node "GetRevised") [])

    "executable:org.apache.commons.logging.LogFactory#getLog(java.lang.String)"
    (csharp/raw
     "global::Microsoft.Extensions.Logging.Abstractions.NullLogger.Instance")

    nil))))

(defn- target-test-context [context]
  (let [base-name (:destination-resolved-name context)
        module-name (name (:pdfcarton-test-module context))
        base-invocation (:destination-invocation-adapter context)
        base-constructor? (:destination-resolved-constructor? context)
        base-constructor (:destination-constructor-adapter context)
        base-method-reference? (:destination-method-reference? context)
        base-value (:destination-value-adapter context)]
    (assoc
     context
     :destination-external-override-protected? true
     :destination-type-mappings
     (merge (:destination-type-mappings context)
            {"java.lang.Thread"
             ["global::DripSharp.PdfCarton.Tests.JavaTestThread"
              :pdfcarton.test/thread]
             "java.lang.Thread$UncaughtExceptionHandler"
             [(str "global::System.Action<"
                   "global::DripSharp.PdfCarton.Tests.JavaTestThread, "
                   "global::System.Exception>")
              :pdfcarton.test/uncaught-exception-handler]
             "java.time.chrono.ChronoZonedDateTime"
             ["global::System.DateTimeOffset"
              :pdfcarton.test/chrono-zoned-date-time]
             "javax.xml.xpath.XPath"
             ["global::DripSharp.PdfCarton.Tests.JavaTestXPath"
              :pdfcarton.test/xpath]
             "javax.xml.xpath.XPathFactory"
             ["global::DripSharp.PdfCarton.Tests.JavaTestXPathFactory"
              :pdfcarton.test/xpath-factory]
             "javax.xml.xpath.XPathConstants"
             ["global::DripSharp.PdfCarton.Tests.JavaTestXPathConstants"
              :pdfcarton.test/xpath-constants]
             "java.lang.StringBuffer"
             ["global::System.Text.StringBuilder"
              :pdfcarton.test/string-buffer]
             "java.math.MathContext"
             ["object" :pdfcarton.test/math-context]
             "java.nio.file.StandardCopyOption"
             ["object" :pdfcarton.test/standard-copy-option]
             "java.awt.image.DirectColorModel"
             ["global::DripSharp.PdfCarton.Tests.JavaDirectColorModel"
              :pdfcarton.test/direct-color-model]
             "java.awt.image.PackedColorModel"
             ["global::DripSharp.PdfCarton.Tests.JavaDirectColorModel"
              :pdfcarton.test/packed-color-model]
             "java.io.FilenameFilter"
             [(str "global::System.Func<global::System.IO.FileInfo, "
                   "string, bool>")
              :pdfcarton.test/filename-filter]
             "java.nio.file.CopyOption"
             ["object" :pdfcarton.test/copy-option]
             "java.io.Closeable"
             ["global::System.Action" :pdfcarton.test/closeable]
             "java.util.stream.Stream"
             ["global::System.Collections.Generic.IEnumerable"
              :pdfcarton.test/stream]
             "org.junit.jupiter.params.provider.Arguments"
             ["object[]" :pdfcarton.test/arguments]
             "java.util.Locale$Builder"
             ["global::DripSharp.PdfCarton.Tests.JavaLocaleBuilder"
              :pdfcarton.test/locale-builder]
             "javax.imageio.spi.ImageWriterSpi"
             ["global::DripSharp.Runtime.JavaImageWriter"
              :pdfcarton.test/image-writer-provider]
             "difflib.DiffUtils"
             ["global::DripSharp.PdfCarton.Tests.JavaDiffUtils"
              :pdfcarton.test/diff-utils]
             "difflib.Patch"
             ["global::DripSharp.PdfCarton.Tests.JavaPatch"
              :pdfcarton.test/diff-patch]
             "difflib.Delta"
             ["global::DripSharp.PdfCarton.Tests.JavaDelta"
              :pdfcarton.test/diff-delta]
             "difflib.ChangeDelta"
             ["global::DripSharp.PdfCarton.Tests.JavaChangeDelta"
              :pdfcarton.test/diff-change]
             "difflib.DeleteDelta"
             ["global::DripSharp.PdfCarton.Tests.JavaDeleteDelta"
              :pdfcarton.test/diff-delete]
             "difflib.InsertDelta"
             ["global::DripSharp.PdfCarton.Tests.JavaInsertDelta"
              :pdfcarton.test/diff-insert]
             "difflib.Chunk"
             ["global::DripSharp.PdfCarton.Tests.JavaChunk"
              :pdfcarton.test/diff-chunk]
             "org.apache.commons.io.FileUtils"
             ["global::DripSharp.PdfCarton.Tests.JavaFileUtils"
              :pdfcarton.test/file-utils]
             "java.util.Random"
             ["global::DripSharp.PdfCarton.Tests.JavaRandom"
              :pdfcarton.test/random]
             "java.util.concurrent.CountDownLatch"
             ["global::System.Threading.CountdownEvent"
              :pdfcarton.test/countdown-event]})
     :destination-field-adaptations
     (merge
      {"field:java.awt.Color#BLACK"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Black"))
       "field:java.awt.Color#black"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Black"))
       "field:java.awt.Color#BLUE"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Blue"))
       "field:java.awt.Color#blue"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Blue"))
       "field:java.awt.Color#yellow"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Yellow"))
       "field:java.awt.Color#GREEN"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Green"))
       "field:java.awt.Color#LIGHT_GRAY"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.LightGray"))
       "field:java.awt.Color#PINK"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Pink"))
       "field:java.awt.Color#RED"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Red"))
       "field:java.awt.Color#red"
       (fn [_]
         (csharp/raw
          "(global::DripSharp.Runtime.JavaColor)global::SkiaSharp.SKColors.Red"))
       "field:java.awt.AlphaComposite#SrcOver"
       (fn [_]
         (csharp/raw
          (str "global::DripSharp.Runtime.JavaAlphaComposite.GetInstance("
               "global::DripSharp.Runtime.JavaAlphaComposite.SRC_OVER, 1f)")))
       "field:java.awt.image.BufferedImage#TYPE_USHORT_555_RGB"
       (fn [_]
         (csharp/raw
          "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_INT_RGB"))
       "field:java.lang.Float#NaN"
       (fn [_] (csharp/raw "float.NaN"))
       "field:java.math.BigDecimal#ZERO"
       (fn [_]
         (csharp/raw
          "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalZero()"))
       "field:java.math.MathContext#DECIMAL128"
       (fn [_] (csharp/raw "new object()"))
       "field:java.nio.file.StandardCopyOption#REPLACE_EXISTING"
       (fn [_] (csharp/raw "new object()"))
       "field:java.nio.file.StandardOpenOption#WRITE"
       (fn [_] (csharp/raw "new object()"))
       "field:java.lang.System#err"
       (fn [_]
         (csharp/raw
          "global::DripSharp.PdfCarton.Tests.Support.ErrorStream"))
       "field:javax.xml.xpath.XPathConstants#NODE"
       (fn [_]
         (csharp/raw
          "global::DripSharp.PdfCarton.Tests.JavaTestXPathConstants.NODE"))
       "field:javax.xml.xpath.XPathConstants#NODESET"
       (fn [_]
         (csharp/raw
          "global::DripSharp.PdfCarton.Tests.JavaTestXPathConstants.NODESET"))}
      (:destination-field-adaptations context))
     :destination-anonymous-delegate-methods
     (merge {"java.io.FilenameFilter" "accept"}
            (:destination-anonymous-delegate-methods context))
     :destination-resolved-name
     (fn [destination-context occurrence reference]
       (or (get test-resolved-names (:key occurrence))
           (when base-name
             (base-name destination-context occurrence reference))))
     :destination-invocation-adapter
     (fn [event]
       (or (test-invocation event)
           (when base-invocation (base-invocation event))))
     :destination-method-reference?
     (fn [{:keys [occurrence reference] :as event}]
       (or (contains?
            #{"executable:java.util.List#clear()"
              "executable:java.lang.Comparable#compareTo(java.lang.Object)"
              "executable:java.lang.String#equals(java.lang.Object)"}
            (:key occurrence))
           (nil? (:key occurrence))
           (str/starts-with?
            (or (:key occurrence) "")
            "executable:org.apache.pdfbox.io.IOUtils#closeQuietly(")
           (when base-method-reference? (base-method-reference? event))))
     :destination-value-adapter
     (fn [{:keys [kind source target-reference node] :as event}]
       (or
        (when (and (= :argument kind)
                   (= "java.lang.String"
                      (some-> target-reference .getQualifiedName))
                   (= "java.lang.String" (some-> source .getType .getQualifiedName)))
          (csharp/invocation
           (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.TestPath")
           [(csharp/raw (pr-str module-name)) node]))
        (when base-value (base-value event))))
     :destination-resolved-constructor?
     (fn [destination-context occurrence reference]
       (or (contains? accepted-test-constructors (:key occurrence))
           (when base-constructor?
             (base-constructor? destination-context occurrence reference))))
     :destination-constructor-adapter
     (fn [{:keys [occurrence arguments] :as event}]
       (or
        (if
         (str/starts-with?
          (or (:key occurrence) "")
          "executable:java.io.InputStreamReader#<init>(")
          (csharp/invocation
           (csharp/raw
            "global::DripSharp.PdfCarton.Tests.Support.NewInputStreamReader")
           arguments)
          (if
           (and
            (str/starts-with?
             (or (:key occurrence) "")
             "executable:java.io.FileWriter#<init>(")
            (= 1 (count arguments)))
            (csharp/invocation
             (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.NewFileWriter")
             arguments)
            (case (:key occurrence)
          "executable:java.io.File#<init>(java.lang.String)"
          (csharp/invocation
           (csharp/raw "global::DripSharp.PdfCarton.Tests.Support.TestFile")
           arguments)

          "executable:java.awt.image.ColorConvertOp#<init>(java.awt.color.ColorSpace,java.awt.color.ColorSpace,java.awt.RenderingHints)"
          (csharp/invocation
           (csharp/raw "new global::DripSharp.Runtime.JavaColorConvertOp")
           [(first arguments) (second arguments)])

          "executable:java.awt.image.ColorConvertOp#<init>(java.awt.color.ColorSpace,java.awt.RenderingHints)"
          (csharp/invocation
           (csharp/raw "new global::DripSharp.Runtime.JavaColorConvertOp")
           [(first arguments)])

          "executable:java.awt.image.ComponentColorModel#<init>(java.awt.color.ColorSpace,int[],boolean,boolean,int,int)"
          (csharp/invocation
           (csharp/raw "new global::DripSharp.Runtime.JavaColorModel")
           [(first arguments)
            (nth arguments 2)
            (nth arguments 5)
            (second arguments)])

          "executable:java.io.File#<init>(java.io.File,java.lang.String)"
          (csharp/invocation
           (csharp/raw "new global::System.IO.FileInfo")
           [(csharp/invocation
             (csharp/raw "global::System.IO.Path.Combine")
             [(csharp/member (first arguments) "FullName")
              (second arguments)])])

          "executable:java.io.OutputStreamWriter#<init>(java.io.OutputStream,java.lang.String)"
          (csharp/invocation
           (csharp/raw "new global::System.IO.StreamWriter")
           [(first arguments)
            (csharp/invocation
             (csharp/raw
              "global::DripSharp.PdfCarton.Tests.Support.EncodingByName")
             [(second arguments)])
            (csharp/raw "1024") (csharp/raw "false")])

          "executable:java.io.PrintStream#<init>(java.io.File,java.lang.String)"
          (csharp/invocation
           (csharp/raw "new global::System.IO.StreamWriter")
           [(csharp/member (first arguments) "FullName")
            (csharp/raw "false")
            (csharp/invocation
             (csharp/raw
              "global::DripSharp.PdfCarton.Tests.Support.EncodingByName")
             [(second arguments)])])

          "executable:java.io.PrintWriter#<init>(java.io.OutputStream)"
          (csharp/invocation
           (csharp/raw "new global::System.IO.StreamWriter")
           [(first arguments)
            (csharp/raw "global::System.Text.Encoding.UTF8")
            (csharp/raw "1024") (csharp/raw "false")])

          "executable:java.awt.image.DirectColorModel#<init>(java.awt.color.ColorSpace,int,int,int,int,int,boolean,int)"
          (csharp/invocation
           (csharp/raw
            "new global::DripSharp.PdfCarton.Tests.JavaDirectColorModel")
           arguments)

          "executable:java.lang.IllegalStateException#<init>(java.lang.Throwable)"
          (csharp/invocation
           (csharp/raw "new global::System.InvalidOperationException")
           [(csharp/raw "null") (first arguments)])

          "executable:java.math.BigDecimal#<init>(double)"
          (csharp/invocation
           (csharp/raw
            "global::DripSharp.Runtime.JavaCompat.JavaBigDecimalFromDouble")
           arguments)

          "executable:java.lang.String#<init>(byte[])"
          (csharp/invocation
           (csharp/raw "global::DripSharp.Runtime.JavaCompat.NewString")
           (conj (vec arguments)
                 (csharp/raw "global::System.Text.Encoding.UTF8")))

          "executable:java.lang.String#<init>(byte[],int,int)"
          (csharp/invocation
           (csharp/raw "global::DripSharp.Runtime.JavaCompat.NewString")
           (conj (vec arguments)
                 (csharp/raw "global::System.Text.Encoding.UTF8")))

          "executable:java.lang.String#<init>(byte[],java.lang.String)"
          (csharp/invocation
           (csharp/raw "global::DripSharp.Runtime.JavaCompat.NewString")
           [(first arguments)
            (csharp/invocation
             (csharp/raw
              "global::DripSharp.PdfCarton.Tests.Support.EncodingByName")
             [(second arguments)])])

          "executable:java.lang.StringBuffer#<init>()"
          (csharp/raw "new global::System.Text.StringBuilder()")

          "executable:java.text.SimpleDateFormat#<init>(java.lang.String)"
          (csharp/invocation
           (csharp/raw "new global::DripSharp.Runtime.JavaSimpleDateFormat")
           [(first arguments)
            (csharp/raw "global::System.Globalization.CultureInfo.InvariantCulture")])

          "executable:java.util.GregorianCalendar#<init>(int,int,int)"
          (csharp/invocation
           (csharp/raw
            "global::DripSharp.PdfCarton.Tests.Support.GregorianCalendar")
           arguments)

          "executable:java.util.GregorianCalendar#<init>(int,int,int,int,int,int)"
          (csharp/invocation
           (csharp/raw
            "global::DripSharp.PdfCarton.Tests.Support.GregorianCalendar")
           arguments)

          "executable:java.util.Date#<init>(long)"
          (csharp/invocation
           (csharp/raw "global::System.DateTimeOffset.FromUnixTimeMilliseconds")
           arguments)

          "executable:java.util.Locale$Builder#<init>()"
          (csharp/raw
           "new global::DripSharp.PdfCarton.Tests.JavaLocaleBuilder()")

          "executable:java.util.Random#<init>(long)"
          (csharp/invocation
           (csharp/raw "new global::DripSharp.PdfCarton.Tests.JavaRandom")
           arguments)

          "executable:java.util.concurrent.CountDownLatch#<init>(int)"
          (csharp/invocation
           (csharp/raw "new global::System.Threading.CountdownEvent")
           arguments)
          nil)))
        (when base-constructor (base-constructor event)))))))

(defn- create-destination-context
  [workspace-root specification resolved-model plan]
  (let [bundle (pdfcube/rule-bundle)
        profile (:configuration specification)
        configuration (java-project/read-configuration
                       workspace-root (:destination-config profile))
        validate-project-input!
        (get-in bundle [:orchestration :validate-project-input!])
        _ (when validate-project-input!
            (validate-project-input!
             {:workspace-root workspace-root
              :profile profile
              :configuration configuration
              :project-input (:input specification)}))
        create-template
        (get-in bundle [:rules :structural-declarations :create-template])
        create-context
        (get-in bundle [:rules :structural-declarations :create-context])
        base-translate-member
        (get-in bundle [:rules :structural-declarations :translate-member])
        template (create-template
                  resolved-model
                  {:runtime-capabilities (:runtime-capabilities bundle)})
        semantic-errors (atom [])
        context
        (create-context
         {:template template
          :configuration configuration
          :resolved-model resolved-model
          :occurrence-index (java/resolved-occurrence-index resolved-model)
          :selected-declarations nil
          :public-api-type-keys #{}
          :public-api-declaration-keys #{}
          :runtime-capabilities (:runtime-capabilities bundle)
          :blocker-start 0
          :emit-members
          (emitted-members base-translate-member plan semantic-errors)})
        context (-> context
                    (assoc :pdfcarton-test-module (:id specification))
                    target-test-context
                    adapters/compose-destination-context)
        holder (:ctx-holder template)
        _ (reset! holder context)
        context (assoc context
                       :body-context
                       (java-library/create-body-context
                        resolved-model holder (:runtime-capabilities bundle)))]
    (reset! holder context)
    {:bundle bundle
     :configuration configuration
     :context context
     :semantic-errors semantic-errors}))

(defn- emitted-relative [module ^CtType type]
  (str "Adapted/" (name module) "/"
       (str/replace (.getQualifiedName type) "." "/") ".cs"))

(defn- emit-java-tests!
  [generated-root module roots bundle context semantic-errors]
  (reset! semantic-errors [])
  (let [emit-root (get-in bundle [:rules :structural-declarations
                                  :emit-root-node])
        namespace-policy (get-in bundle [:rules :namespace-policy
                                         :destination-namespace])
        results
        (mapv
         (fn [^CtType type]
           (try
             (let [relative (emitted-relative module type)
                   output (paths/resolve-path generated-root relative)
                   node (emit-root context type)
                   text (:text (csharp/render node))
                   namespace (namespace-policy context type)]
               (util/write-text!
                output
                (str "// SPDX-FileCopyrightText: Apache PDFBox contributors\n"
                     "// SPDX-License-Identifier: Apache-2.0\n\n"
                     "#nullable disable\n"
                     "namespace " namespace ";\n\n" text "\n"))
               {:emission
                {:module module
                 :source-file (source-file type)
                 :type (.getQualifiedName type)
                 :generated relative}})
             (catch Throwable exception
               (let [data (ex-data exception)
                     diagnostic (:diagnostic data)]
                 {:error
                  {:module module
                   :source-file (source-file type)
                   :type (.getQualifiedName type)
                   :message (.getMessage exception)
                   :kind (:kind data)
                   :reason (:reason data)
                   :resolved (:resolved diagnostic)
                   :location (:location diagnostic)
                   :diagnostic-message (:message diagnostic)}}))))
         roots)
        errors (vec (concat @semantic-errors (keep :error results)))]
    (when (seq errors)
      (fail! "PdfCarton adapted test sources contain unsupported semantics"
             {:reason :unsupported-pdfcarton-test-semantics
              :error-count (count errors)
              :errors errors}))
    (mapv :emission results)))

(defn- copy-file! [source destination]
  (Files/createDirectories (.getParent (paths/path destination))
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (Files/copy (paths/path source)
              (paths/path destination)
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
  destination)

(defn- write-bytes! [destination bytes]
  (let [destination (paths/path destination)]
    (Files/createDirectories
     (.getParent destination)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/write destination bytes (make-array OpenOption 0))
    destination))

(defn- emit-fixture! [workspace-root source-root generated-root fixture]
  (let [destination
        (paths/resolve-path generated-root
                            (str "Fixtures/" (:destination fixture)))
        source (paths/resolve-path (if (:workspace-source fixture)
                                    workspace-root
                                    source-root)
                                  (:source fixture))]
    (case (:generator fixture)
      :pdfcarton-cmap-01
      (write-bytes! destination (pdfcarton-cmap-01-bytes source))

      :pdfcarton-current-fc60-sorted
      (write-bytes! destination (current-fc60-sorted-bytes source))

      nil
      (copy-file! source destination)

      (fail! "PdfCarton generated fixture uses an unknown generator"
             {:reason :unknown-pdfcarton-fixture-generator
              :source (:source fixture)
              :destination (:destination fixture)
              :generator (:generator fixture)}))))

(defn- fixture-targets []
  (str "<Project>\n"
       "  <PropertyGroup>\n"
       "    <OutputType>Exe</OutputType>\n"
       "    <StartupObject>DripSharp.PdfCarton.Tests.AutoGenerated.XunitAutoGeneratedEntryPoint</StartupObject>\n"
       "    <GenerateProgramFile>false</GenerateProgramFile>\n"
       "    <CopyLocalLockFileAssemblies>true</CopyLocalLockFileAssemblies>\n"
       "    <NoWarn>$(NoWarn);CS0168;CS0219;CS8632;CA1416;xUnit1013</NoWarn>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       "    <None Update=\"Fixtures/**/*\">\n"
       "      <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>\n"
       "    </None>\n"
       "  </ItemGroup>\n</Project>\n"))

(defn- render-integrity-test [fixtures]
  (str
   "// SPDX-FileCopyrightText: 2026 Isak Sky\n"
   "// SPDX-License-Identifier: Apache-2.0\n\n"
   "namespace DripSharp.PdfCarton.Tests;\n\n"
   "public sealed class GeneratedSuiteIntegrityTests\n{\n"
   "    [Xunit.Fact]\n"
   "    public void EveryUpstreamFixtureIsPresentAndPinned()\n    {\n"
   "        (string Path, string Sha256)[] fixtures = new[]\n        {\n"
   (apply str
          (for [{:keys [destination sha256]} fixtures]
            (str "            (" (csharp-string (str "Fixtures/" destination))
                 ", " (csharp-string sha256) "),\n")))
   "        };\n"
   "        Xunit.Assert.Equal(" (count fixtures) ", fixtures.Length);\n"
   "        foreach ((string relative, string expected) in fixtures)\n        {\n"
   "            string path = global::System.IO.Path.Combine(\n"
   "                global::System.AppContext.BaseDirectory, relative);\n"
   "            Xunit.Assert.True(global::System.IO.File.Exists(path), path);\n"
   "            string actual = global::System.Convert.ToHexString(\n"
   "                global::System.Security.Cryptography.SHA256.HashData(\n"
   "                    global::System.IO.File.ReadAllBytes(path))).ToLowerInvariant();\n"
   "            Xunit.Assert.Equal(expected, actual);\n"
   "        }\n    }\n}\n"))

(defn- render-provenance [source-root source-entries emission]
  (let [by-source (group-by :source-file emission)]
    (str
     "kind\tmodule\tupstream-path\tsha256\tidentity\tgenerated-path\n"
     (str/join
      "\n"
      (for [{:keys [module source sha256]} source-entries
            :let [canonical (str (paths/resolve-path source-root source))]
            generated (get by-source canonical)]
        (str "java-source\t" (name module) "\t" source "\t" sha256 "\t"
             (:type generated) "\t" (:generated generated))))
     "\n")))

(defn- module-emission!
  [workspace-root generated-root specification]
  (validate-selected-counts! specification)
  (let [{:keys [id input]} specification
        selected (vec (sort-by str (:test-sources input)))
        resolved-model (spoon/build-resolved-model!
                        workspace-root (resolved-input specification))
        plan (-> (junit/plan-suite resolved-model
                                   (adapters/junit-plan-options))
                 mark-test-classes-public!)
        roots (selected-root-types resolved-model selected)
        {:keys [bundle context semantic-errors]}
        (create-destination-context workspace-root specification
                                    resolved-model plan)
        emission (emit-java-tests! generated-root id roots bundle context
                                   semantic-errors)]
    {:module id
     :selected selected
     :case-count (count (:cases plan))
     :root-count (count roots)
     :emission emission}))

(defn- emit! [{:keys [workspace-root target-contract project-root]}]
  (let [workspace-root (paths/absolute workspace-root)
        generated-root project-root
        {:keys [source-root modules]} (discover-inputs! workspace-root)
        inventory (inventory! workspace-root)
        contract (read-contract! (:target-directory target-contract))
        _ (verify-inventory! contract inventory)
        module-results
        (mapv #(module-emission! workspace-root generated-root %) modules)
        emission (vec (mapcat :emission module-results))
        fixtures (get-in inventory [:accounting :fixtures])
        sources (get-in inventory [:accounting :sources])]
    (util/write-text!
     (paths/resolve-path generated-root "GeneratedSuiteAssembly.cs")
     (str "[assembly: Xunit.CollectionBehavior("
          "DisableTestParallelization = true)]\n"
          "[assembly: Xunit.TestCaseOrderer(typeof("
          "DripSharp.PdfCarton.Tests.UpstreamTestCaseOrderer))]\n"))
    (util/write-text! (paths/resolve-path generated-root "JavaTestSupport.cs")
                      (adapters/support-source))
    (copy-file! (paths/resolve-path
                 (:target-directory target-contract)
                 "adapted-tests/PdfCartonTestSupport.cs")
                (paths/resolve-path generated-root "PdfCartonTestSupport.cs"))
    (copy-file! (paths/resolve-path
                 (:target-directory target-contract)
                 "adapted-tests/PdfCartonTestPlatformSupport.cs")
                (paths/resolve-path generated-root
                                    "PdfCartonTestPlatformSupport.cs"))
    (doseq [fixture fixtures]
      (emit-fixture! workspace-root source-root generated-root fixture))
    (util/write-text! (paths/resolve-path generated-root
                                          "Directory.Build.targets")
                      (fixture-targets))
    (util/write-text! (paths/resolve-path generated-root
                                          "GeneratedSuiteIntegrityTests.cs")
                      (render-integrity-test fixtures))
    (util/write-text! (paths/resolve-path generated-root
                                          "JAVA-TEST-INVENTORY.edn")
                      (stable-text
                       (assoc inventory
                              :emission
                              (mapv #(dissoc % :source-file) emission))))
    (util/write-text! (paths/resolve-path generated-root
                                          "JAVA-TEST-PROVENANCE.tsv")
                      (render-provenance source-root sources emission))
    (util/write-text! (paths/resolve-path generated-root "SUITE-CONTRACT.edn")
                      (stable-text contract))
    {:sources (count sources)
     :roots (reduce + (map :root-count module-results))
     :cases (reduce + (map :case-count module-results))
     :fixtures (count fixtures)
     :accounting (:digests inventory)}))

(defn- verify-generated! [project-root]
  (let [inventory-file (paths/resolve-path project-root
                                           "JAVA-TEST-INVENTORY.edn")
        contract-file (paths/resolve-path project-root "SUITE-CONTRACT.edn")]
    (when-not (and (paths/regular-file? inventory-file)
                   (paths/regular-file? contract-file))
      (fail! "Generated PdfCarton suite ledgers are missing"
             {:reason :missing-generated-pdfcarton-test-ledger
              :inventory (str inventory-file)
              :contract (str contract-file)}))
    (let [inventory (util/read-single-edn-string!
                     (slurp (str inventory-file)))
          contract (util/read-single-edn-string!
                    (slurp (str contract-file)))]
      (verify-inventory! contract inventory)
      (doseq [{:keys [destination sha256]}
              (get-in inventory [:accounting :fixtures])]
        (let [file (paths/resolve-path project-root
                                       (str "Fixtures/" destination))
              actual (when (paths/regular-file? file)
                       (util/sha256-file file))]
          (when-not (= sha256 actual)
            (fail! "Generated PdfCarton test fixture is missing or changed"
                   {:reason :generated-pdfcarton-test-fixture-drift
                    :path destination :expected sha256 :actual actual}))))
      {:sources (get-in inventory [:totals :source-count])
       :cases (get-in inventory [:totals :case-count])
       :fixtures (get-in inventory [:totals :fixture-count])
       :accounting (:digests inventory)})))

(defn strategy!
  "Target-owned complete adapted-upstream PdfCarton strategy."
  [{:keys [phase] :as options}]
  (case phase
    :emit (emit! options)
    :verify (verify-generated! (:project-root options))
    (fail! "PdfCarton adapted suite received an unsupported phase"
           {:reason :unsupported-pdfcarton-test-suite-phase
            :phase phase})))
