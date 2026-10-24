(ns dripsharp.pdfcube-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.csharp :as csharp]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as project-emission]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.io-differential :as io-differential]
            [dripsharp.pdfcube.java-project :as pdfcube]
            [dripsharp.pdfcube.source-sync :as source-sync]
            [dripsharp.process :as process]
            [dripsharp.public-surface :as public-surface]
            [dripsharp.spoon :as spoon]
            [dripsharp.target-directory :as target-directory])
  (:import [java.nio.file Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [javax.tools ToolProvider]))

(def ^:private profile-names
  ["pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
   "pdfcube-pdfbox" "pdfcube-preflight"])

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-pdfcube"
                             (make-array FileAttribute 0)))

(defn- transform-node
  [configuration node]
  ((get-in (pdfcube/rule-bundle)
           [:rules :project-policy :transform-node])
   configuration
   node))

(defn- render-transformed
  [configuration node]
  (csharp/render (transform-node configuration node)))

(deftest vendored-codec-assets-are-internalized-without-losing-authorship
  (let [assets
        ((ns-resolve 'dripsharp.pdfcube.java-project
                     'internal-capability-assets)
         {:configuration
          {:internal-capabilities #{:jbig2 :jpx}
           :runtime-sources
           ["targets/pdfcube/runtime/DripSharp.PdfCarton.ImageCodecs.cs"]}})
        codec-assets
        (filterv
         #(contains? #{:pdfcube.pdfbox/jbig2-source
                       :pdfcube.pdfbox/jpx-source}
                     (:strategy %))
         assets)
        source
        (str "namespace Vendor.Codec\n{\n"
             "    public sealed partial class Decoder\n"
             "    {\n"
             "        public Decoder Decode() => this;\n"
             "        public enum Mode { Default }\n"
             "    }\n"
             "}\n")
        expected
        (str "namespace Vendor.Codec\n{\n"
             "    internal sealed partial class Decoder\n"
             "    {\n"
             "        public Decoder Decode() => this;\n"
             "        internal enum Mode { Default }\n"
             "    }\n"
             "}\n")]
    (is (= 2 (count codec-assets)))
    (is (every? #(= :vendored-third-party (:authorship-class %))
                codec-assets))
    ;; The libjpeg-turbo port is kept with its notices, not as authored code.
    (is (= [{:source-tree "vendor/pdfcube/jpeg"
             :authorship-class :vendored-third-party}]
           (->> assets
                (filter #(= :pdfcube.pdfbox/jpeg-samples-source (:strategy %)))
                (mapv #(select-keys % [:source-tree :authorship-class])))))
    (is (every? ifn? (map :text-transform codec-assets)))
    (is (every? #(= expected ((:text-transform %) source))
                codec-assets))
    (is (every? #(= expected
                    ((:text-transform %)
                     ((:text-transform %) source)))
                codec-assets))))

(deftest compatibility-namespace-transform-shifts-source-map-ranges
  (let [source-token "global::DripSharp.Runtime"
        destination-token "global::DripSharp.PdfCarton.Runtime.Fonts"
        whole-source {:identity :whole}
        first-source {:identity :first}
        second-source {:identity :second}
        node
        (csharp/with-source
          (csharp/sequence-node
           [(csharp/raw "a")
            (csharp/with-source (csharp/raw source-token) first-source)
            (csharp/raw ".One b ")
            (csharp/with-source
              (csharp/sequence-node
               [(csharp/raw source-token) (csharp/raw ".Two")])
              second-source)])
          whole-source)
        transformed
        (render-transformed
         {:compatibility-namespace "DripSharp.PdfCarton.Runtime.Fonts"}
         node)
        by-source
        (into {} (map (juxt :source :destination) (:mappings transformed)))
        first-start 1
        second-start (.lastIndexOf ^String (:text transformed)
                                   destination-token)]
    (is (= (str "a" destination-token ".One b " destination-token ".Two")
           (:text transformed)))
    (is (= {:start 0 :end (count (:text transformed))}
           (get by-source whole-source)))
    (is (= {:start first-start
            :end (+ first-start (count destination-token))}
           (get by-source first-source)))
    (is (= {:start second-start :end (count (:text transformed))}
           (get by-source second-source)))))

(deftest annotation-matrix-and-word-normalization-are-emitted-unchanged
  (doseq [[type-name source-name statements]
          [["org.apache.pdfbox.contentstream.PDFStreamEngine" "processAnnotation"
            [(str "global::DripSharp.PdfCarton.Util.Matrix aa = "
                  "global::DripSharp.PdfCarton.Util.Matrix.Concatenate(a, matrix);")
             "this.GetGraphicsState().SetCurrentTransformationMatrix(aa);"]]
           ["org.apache.pdfbox.text.PDFTextStripper" "normalizeWord"
            ["global::System.Text.StringBuilder builder = null;"
             "return this.handleDirection(word);"]]]]
    (let [declaration (csharp/declaration
                       (csharp/raw (str "private object " source-name "()"))
                       (csharp/block (mapv csharp/raw statements))
                       {:declaration-kind :method
                        :source-name source-name
                        :source-qualified-name type-name})]
      (is (= (:text (csharp/render declaration))
             (:text (render-transformed {:package {:id "DripSharp.PdfCarton"}}
                                        declaration)))
          (str type-name "#" source-name)))))

(deftest decode-filters-and-colour-conversions-are-emitted-unchanged
  (doseq [[type-name source-name parameter-count statement]
          [["org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN" "toRGBWithAttributes" 1
            "return rgbImage;"]
           ["org.apache.pdfbox.filter.DCTFilter" "readImageRaster" 2
            "global::SkiaSharp.SKBitmap image = reader.Read(0, irp);"]
           ["org.apache.pdfbox.filter.JPXFilter" "decode" 5
            "return result;"]
           ["org.apache.pdfbox.filter.JPXFilter" "readJPX" 3
            "image = reader.Read(0, irp);"]
           ["org.apache.pdfbox.filter.JBIG2Filter" "decode" 5
            "image = reader.Read(0, irp);"]
           ["org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace" "toRGBImageAWT" 2
            (str "global::SkiaSharp.SKBitmap src = "
                 "global::DripSharp.Runtime.PdfCartonFontCompat.CreateImage("
                 "colorModel, raster, false, "
                 "(global::DripSharp.Runtime.JavaHashtable<object, object>)default!);")]
           ["org.apache.pdfbox.pdmodel.graphics.image.SampledImageReader" "from1Bit" 5
            "return bim!;"]]]
    (let [declaration (csharp/declaration
                       (csharp/raw (str "private object " source-name "()"))
                       (csharp/block [(csharp/raw statement)])
                       {:declaration-kind :method
                        :source-name source-name
                        :parameter-count parameter-count
                        :source-qualified-name type-name})]
      (is (= (:text (csharp/render declaration))
             (:text (render-transformed {:package {:id "DripSharp.PdfCarton"}}
                                        declaration)))
          (str type-name "#" source-name)))))

(deftest image-masking-and-drawing-are-emitted-unchanged
  (doseq [[type-name source-name parameter-count statement]
          [["org.apache.pdfbox.rendering.PageDrawer" "drawImage" 1
            (str "global::SkiaSharp.SKBitmap renderedPaint = "
                 "global::DripSharp.Runtime.PdfCartonFontCompat.CreateBitmap(w, h, "
                 "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_INT_ARGB);")]
           ["org.apache.pdfbox.rendering.PageDrawer" "drawBufferedImage" 3
            "image = this.applyTransferFunction(image, transfer);"]
           ["org.apache.pdfbox.rendering.PageDrawer" "applyTransferFunction" 2
            "return bim;"]
           ["org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject" "getImage" 2
            "return image;"]
           ["org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject" "applyMask" 5
            "return image;"]
           ["org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject" "scaleImage" 5
            "return image2;"]
           ["org.apache.pdfbox.pdmodel.graphics.image.SampledImageReader" "fromAny" 7
            "return rgbImage;"]
           ["org.apache.pdfbox.pdmodel.graphics.image.SampledImageReader" "applyColorKeyMask" 2
            "return masked;"]]]
    (let [declaration (csharp/declaration
                       (csharp/raw (str "private object " source-name "()"))
                       (csharp/block [(csharp/raw statement)])
                       {:declaration-kind :method
                        :source-name source-name
                        :parameter-count parameter-count
                        :source-qualified-name type-name})]
      (is (= (:text (csharp/render declaration))
             (:text (render-transformed {:package {:id "DripSharp.PdfCarton"}}
                                        declaration)))
          (str type-name "#" source-name)))))

(deftest soft-masks-groups-and-page-images-are-emitted-unchanged
  (let [config {:package {:id "DripSharp.PdfCarton"}}
        compat "global::DripSharp.Runtime.PdfCartonFontCompat."]
    (doseq [[kind type-name source-name parameter-count statement]
            [[:method "org.apache.pdfbox.rendering.PageDrawer" "applySoftMaskToPaint" 2
              "global::SkiaSharp.SKBitmap image = transparencyGroup.getImage();"]
             [:method "org.apache.pdfbox.rendering.PageDrawer" "adjustImage" 1
              (str "global::SkiaSharp.SKBitmap transformedGray = " compat "CreateBitmap("
                   "width, height, " compat "TYPE_BYTE_GRAY);")]
             [:method "org.apache.pdfbox.rendering.PageDrawer" "drawPage" 2
              "this.graphics = g;"]
             [:method "org.apache.pdfbox.rendering.PageDrawer" "showTransparencyGroupOnGraphics" 2
              "global::SkiaSharp.SKBitmap image = group.getImage();"]
             [:constructor "org.apache.pdfbox.rendering.PageDrawer$TransparencyGroup" nil 4
              "this.image = this.create2ByteGrayAlphaImage(this.width, this.height);"]
             [:constructor "org.apache.pdfbox.rendering.GroupGraphics" nil 2
              (str "this.alphaG2D = " compat "CreateGraphics(this.groupAlphaImage);")]
             [:method "org.apache.pdfbox.rendering.GroupGraphics" "dispose" 0
              "this.alphaG2D.Dispose();"]
             [:method "org.apache.pdfbox.rendering.PDFRenderer" "renderImage" 4
              "this.pageImage = image;"]
             [:method "org.apache.pdfbox.printing.PDFPrintable" "print" 3
              (str "graphics2D = " compat "CreateGraphics(image!);")]]]
      (let [declaration (csharp/declaration
                         (csharp/raw (str "private object " (or source-name "ctor") "()"))
                         (csharp/block [(csharp/raw statement)])
                         (cond-> {:declaration-kind kind
                                  :parameter-count parameter-count
                                  :source-qualified-name type-name}
                           source-name (assoc :source-name source-name)))]
        (is (= (:text (csharp/render declaration))
               (:text (render-transformed config declaration)))
            (str type-name "#" (or source-name "<init>")))))
    (testing "the page drawer and group graphics declare no members of their own"
      (doseq [type-name ["org.apache.pdfbox.rendering.PageDrawer"
                         "org.apache.pdfbox.rendering.GroupGraphics"]]
        (let [type (csharp/declaration
                    (csharp/raw "public class Translated")
                    (csharp/block [(csharp/raw "private int other;")])
                    {:declaration-kind :type
                     :source-qualified-name type-name})]
          (is (= (:text (csharp/render type))
                 (:text (render-transformed config type)))
              type-name))))))

(deftest rendering-intermediates-are-owned
  (let [config {:package {:id "DripSharp.PdfCarton"}}
        render (fn [node] (:text (render-transformed config node)))
        declaration (fn [kind type-name data statements]
                      (csharp/declaration
                       (csharp/raw (str "void " (or (:source-name data) "ctor") "()"))
                       (csharp/block (mapv csharp/raw statements))
                       (merge {:declaration-kind kind
                               :source-qualified-name type-name}
                              data)))]
    (testing "a pattern fill's area reads linePath in place and fills the clip copy"
      (let [area "global::DripSharp.Runtime.JavaArea"
            rendered (render (declaration
                              :method "org.apache.pdfbox.rendering.PageDrawer"
                              {:source-name "fillPath" :parameter-count 1}
                              [(str area " area = new " area "(this.linePath);")
                               "object clip = this.graphics.GetClip();"
                               (str "area.Intersect(new " area "(clip));")]))]
        (is (str/includes? rendered (str "__dripsharpOwned.Own(" area ".Lend(this.linePath))")))
        (is (str/includes? rendered "area.IntersectTemporary(__dripsharpClipPath);"))
        (is (not (str/includes? rendered (str "new " area "(this.linePath)"))))
        (is (= 1 (count (re-seq #"new global::DripSharp\.Runtime\.JavaArea\(" rendered))))))))

(defn- write-file! [^Path root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- read-profile-and-destination [profile-name]
  (let [workspace (paths/workspace-root)
        profile-file
        (str "targets/pdfcube/profiles/"
             (str/replace profile-name #"^pdfcube-" "")
             ".edn")
        profile (harness/read-profile workspace profile-file)]
    {:profile profile
     :destination
     (project-emission/read-configuration
      workspace (:destination-config profile))}))

(defn- caught [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest stable-pdfbox-release-selection-is-numeric-monotonic-and-fail-closed
  (let [baseline
        {:version "3.0.8"
         :revision "8888888888888888888888888888888888888888"}
        tags
        (source-sync/parse-remote-tags
         (str
          "2222222222222222222222222222222222222222\trefs/tags/2.0.37\n"
          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\trefs/tags/3.0.8\n"
          "8888888888888888888888888888888888888888\trefs/tags/3.0.8^{}\n"
          "9999999999999999999999999999999999999999\trefs/tags/3.0.9-RC1\n"
          "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\trefs/tags/3.0.9\n"
          "1010101010101010101010101010101010101010\trefs/tags/3.0.10\n"
          "cccccccccccccccccccccccccccccccccccccccc\trefs/tags/3.1.0-M1\n"))
        selected (source-sync/select-stable-release baseline tags)]
    (is (= ["2.0.37" "3.0.8" "3.0.9" "3.0.10"]
           (mapv :version tags)))
    (is (= {:version "3.0.10"
            :components [(biginteger 3) (biginteger 0) (biginteger 10)]
            :revision "1010101010101010101010101010101010101010"}
           selected))
    (testing "greater stable patch, minor, and major releases advance"
      (doseq [[version revision]
              [["3.0.9" "9999999999999999999999999999999999999999"]
               ["3.1.0" "1111111111111111111111111111111111111111"]
               ["4.0.0" "4444444444444444444444444444444444444444"]]]
        (is (= version
               (:version
                (source-sync/select-stable-release
                 baseline
                 [baseline {:version version :revision revision}]))))))
    (testing "lower maintained lines and prereleases never replace the baseline"
      (is (= baseline
             (dissoc
              (source-sync/select-stable-release
               baseline
               (source-sync/parse-remote-tags
                (str
                 "2222222222222222222222222222222222222222\trefs/tags/2.0.37\n"
                 "8888888888888888888888888888888888888888\trefs/tags/3.0.8\n"
                 "9999999999999999999999999999999999999999\trefs/tags/4.0.0-SNAPSHOT\n")))
              :components))))
    (testing "a moved baseline tag is rejected"
      (let [error
            (caught
             #(source-sync/select-stable-release
               baseline
               [{:version "3.0.8"
                 :revision "7777777777777777777777777777777777777777"}]))]
        (is (= :pdfbox-baseline-tag-moved (:kind (ex-data error))))))))

(deftest five-profiles-pin-the-selected-pdfbox-release-and-commit
  (let [workspace (paths/workspace-root)
        family (pdfcube/product-family)
        version (:source-version family)
        revision (:source-revision family)
        prepared (mapv read-profile-and-destination profile-names)]
    (is (= "3.0.8" version))
    (is (= "9286e47d89d6877005c9d2d0f2fd38793a62519a" revision))
    (is (= revision io-differential/pinned-revision))
    (doseq [{:keys [profile destination]} prepared]
      (is (= revision (:revision profile)))
      (is (= :exact-clean-generated-product-commit
             (get-in destination [:package :repository-commit-policy])))
      (is (not (contains? (:package destination) :repository-commit)))
      (is (= {:repository "https://github.com/apache/pdfbox.git"
              :revision revision
              :notice-reference "NOTICE.txt"}
             (:mechanical-source destination)))
      (is (str/ends-with? (:maven-project-id profile) (str ":" version)))
      (is (str/ends-with? (:source-project-id destination) (str ":" version)))
      (is (= (str version "-alpha.4")
             (get-in destination [:package :version]))))
    (is (= revision
           (str/trim
            (:output
             (process/run!
              {:command ["git" "rev-parse" "HEAD"]
               :directory (paths/resolve-path workspace "research/pdfbox")})))))))

(defn- model!
  ([source-relative source]
   (model! source-relative source {}))
  ([source-relative source dependency-sources]
   (let [root (temp-directory)
         source-root (paths/resolve-path root "src/main/java")
         source-file (write-file! source-root source-relative source)
         dependency-root (paths/resolve-path root "dependency-src")
         dependency-files
         (mapv (fn [[relative content]]
                 (write-file! dependency-root relative content))
               dependency-sources)
         classpath-root (paths/resolve-path root "dependency-classes")
         _ (when (seq dependency-files)
             (Files/createDirectories classpath-root
                                      (make-array FileAttribute 0))
             (let [exit (.run (ToolProvider/getSystemJavaCompiler)
                              nil nil nil
                              (into-array
                               String
                               (concat ["-d" (str classpath-root)]
                                       (map str dependency-files))))]
               (when-not (zero? exit)
                 (throw (ex-info "PdfCarton fixture dependency compilation failed"
                                 {:kind :fixture-compilation-failed
                                  :exit exit})))))
         input
         {:schema-version 1
          :project-id "fixture"
          :project-root root
          :source-roots [source-root]
          :resource-roots []
          :production-sources [source-file]
          :generated-production-sources []
          :production-resources []
          :java-toolchain
          {:home (paths/absolute (System/getProperty "java.home"))
           :release 17
           :preview-features? false}
          :project-dependencies []
          :external-dependencies []
          :classpath-artifacts
          (if (seq dependency-files)
            [{:scope :compile :path classpath-root}]
            [])}]
     {:root root
      :input input
      :model (spoon/build-resolved-model! root input)})))

(defn- emit!
  ([fixture destination]
   (emit! fixture destination nil))
  ([fixture destination public-api-boundary]
   (concurrency/call-with-executor
    {:worker-count 2}
    #(project-emission/emit-project!
      {:workspace-root (paths/workspace-root)
       :target (temp-directory)
       :project-input (:input fixture)
       :resolved-model (:model fixture)
       :public-api-boundary public-api-boundary
       :configuration destination
       :rule-bundle (pdfcube/rule-bundle)}))))

(deftest five-configurations-match-the-approved-pdfcarton-family
  (let [workspace (paths/workspace-root)
        target (target-directory/read-target workspace :pdfcube)
        family (pdfcube/product-family)
        prepared (mapv read-profile-and-destination profile-names)
        destinations (mapv :destination prepared)
        packages (mapv #(get-in % [:package :id]) destinations)
        dependency-graph
        (into {} (map (fn [destination]
                        [(get-in destination [:package :id])
                         (:package-dependencies destination)]))
              destinations)
        validate-profile!
        (get-in (pdfcube/rule-bundle)
                [:orchestration :validate-profile!])]
    (is (= 1 (:schema-version family)))
    (is (= :pdfcarton (:product-family family)))
    (is (= {:production "netstandard2.0"
            :execution "net10.0"
            :net48-compatibility :inferred-from-netstandard2.0
            :net48-runtime-tested? false}
           (:frameworks target)))
    (is (= 5 (count (:products family)) (count destinations)))
    (is (= #{"DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.Xmp"
             "DripSharp.PdfCarton" "DripSharp.PdfCarton.Preflight"}
           (set packages)))
    (is (= {"DripSharp.PdfCarton.IO" []
            "DripSharp.PdfCarton.Fonts" ["DripSharp.PdfCarton.IO"]
            "DripSharp.PdfCarton.Xmp" []
            "DripSharp.PdfCarton" ["DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"]
            "DripSharp.PdfCarton.Preflight" ["DripSharp.PdfCarton" "DripSharp.PdfCarton.Xmp"]}
           dependency-graph))
    (is (= #{:java-bidi}
           (:bridge-capabilities
            (:destination (read-profile-and-destination "pdfcube-pdfbox")))))
    (is (= "DripSharp.PdfCarton.Runtime.Xmp"
           (:compatibility-namespace
            (:destination (read-profile-and-destination "pdfcube-xmpbox")))))
    (is (= #{}
           (:bridge-capabilities
            (:destination (read-profile-and-destination "pdfcube-preflight")))))
    (is (= #{}
           (:internal-capabilities
            (:destination (read-profile-and-destination "pdfcube-preflight")))))
    (is (nil?
         (:generic-erasure-mappings
          (:destination
           (read-profile-and-destination "pdfcube-preflight")))))
    (is (= #{"DripSharp.PdfCarton" "DripSharp.PdfCarton.Preflight"
             "DripSharp.PdfCarton.Tests"}
           (:friend-assemblies
            (:destination (read-profile-and-destination "pdfcube-io")))))
    (is (= #{"DripSharp.PdfCarton.Preflight" "DripSharp.PdfCarton.Tests"}
           (:friend-assemblies
            (:destination (read-profile-and-destination "pdfcube-pdfbox")))))
    (doseq [profile ["pdfcube-fontbox" "pdfcube-xmpbox"
                     "pdfcube-preflight"]]
      (is (= #{"DripSharp.PdfCarton.Tests"}
             (:friend-assemblies
              (:destination (read-profile-and-destination profile))))))
    (doseq [{:keys [profile destination]} prepared]
      (is (= :maven (:build-tool profile)))
      (is (= "netstandard2.0"
             (get-in destination [:project :target-framework])))
      (is (= "disable" (get-in destination [:project :nullable])))
      (is (true? (get-in destination [:project :warnings-as-errors])))
      (is (= "Isak Sky" (get-in destination [:package :authors])))
      (is (= :snupkg (get-in destination [:package :symbols])))
      (is (= (str "Portions Copyright The Apache Software Foundation and "
                  "other upstream contributors; see NOTICE.txt.")
             (get-in destination [:package :copyright])))
      (is (= {:public-identifiers :csharp
              :methods :methods :fields :fields :overloads :overloads}
             (:name-policy destination)))
      (is (= {:strategy :embedded-resource-preserve-path}
             (:resource-policy destination)))
      (is (= :pdfcarton
             (:product-family
              (:contract
               (public-surface/resolve-strategy!
                :pdfcarton (:public-surface destination))))))
      (is (= #{:strategy} (set (keys (:public-surface destination)))))
      (let [selection
            (public-surface/resolve-strategy!
             :pdfcarton (:public-surface destination))]
        (is (= :pdfcube-complete-accessible-library
               (get-in selection [:contract :id])))
        (is (= :resolved-spoon-model
               (:derivation (public-surface/read! selection workspace)))))
      (is (= destination
             (validate-profile! {:workspace-root workspace
                                 :profile profile
                                 :configuration destination}))))))

(deftest dependency-and-legal-policies-are-deterministic-and-fail-closed
  (let [{io :destination io-profile :profile}
        (read-profile-and-destination "pdfcube-io")
        {fontbox :destination}
        (read-profile-and-destination "pdfcube-fontbox")
        {pdfbox :destination}
        (read-profile-and-destination "pdfcube-pdfbox")
        validate! (get-in (pdfcube/rule-bundle)
                          [:rules :project-policy :validate-configuration!])
        project-text ((get-in (pdfcube/rule-bundle)
                              [:rules :project-policy :project-text])
                      pdfbox [])
        fontbox-project-text
        ((get-in (pdfcube/rule-bundle)
                 [:rules :project-policy :project-text])
         fontbox [])
        unapproved
        (caught #(validate!
                  (update io :runtime-packages conj
                          {:id "Unapproved.Runtime"
                           :version "1.0.0"
                           :projection :microsoft-package})))
        unsupported-coordinate
        (caught #(validate!
                  (assoc-in
                   io
                   [:external-dependencies "example:unapproved:jar:1.0"]
                   {:source-scope :compile-runtime
                    :artifact-sha256
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    :runtime-package true
                    :destination
                    {:kind :nuget :id "Example" :version "1.0"}})))
        wrong-framework
        (caught #(validate!
                  (assoc-in io [:project :target-framework] "net9.0")))
        legal-root (temp-directory)
        _ (doseq [name ["LICENSE.txt" "NOTICE.txt"]]
            (let [source (paths/resolve-path (paths/workspace-root)
                                             "research/pdfbox" name)
                  target (paths/resolve-path legal-root
                                             "research/pdfbox" name)]
              (Files/createDirectories (.getParent target)
                                       (make-array FileAttribute 0))
              (Files/copy source target
                          (into-array java.nio.file.CopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))))
        _ (Files/writeString
           (paths/resolve-path legal-root "research/pdfbox/NOTICE.txt")
           "changed notice"
           (make-array OpenOption 0))
        legal-mismatch
        (caught #((get-in (pdfcube/rule-bundle)
                          [:orchestration :validate-profile!])
                  {:workspace-root legal-root
                   :profile io-profile
                   :configuration io}))]
    (is (= :unapproved-pdfcube-runtime-dependency
           (:kind (ex-data unapproved))))
    (is (= :unsupported-pdfcube-dependency-projection
           (:kind (ex-data unsupported-coordinate))))
    (is (= :invalid-pdfcube-configuration
           (:kind (ex-data wrong-framework))))
    (is (= :pdfcube-legal-input-mismatch
           (:kind (ex-data legal-mismatch))))
    (is (str/includes? project-text
                       "<PackageReference Include=\"SkiaSharp\" Version=\"4.150.1\" />"))
    (is (str/includes?
         fontbox-project-text
         (str "<PackageReference Include=\"SkiaSharp.NativeAssets.Linux\" "
              "Version=\"4.150.1\" />")))
    (is (str/includes? project-text
                       "<PackageReference Include=\"Microsoft.Extensions.Logging.Abstractions\" Version=\"10.0.0\" />"))
    (is (str/includes? project-text
                       "<PackageLicenseFile>LICENSE.txt</PackageLicenseFile>"))
    (is (str/includes? project-text
                       "<IncludeSymbols>true</IncludeSymbols>"))
    (is (str/includes? project-text
                       "<SymbolPackageFormat>snupkg</SymbolPackageFormat>"))
    (is (str/includes? project-text
                       "<DebugType>portable</DebugType>"))
    (is (str/includes?
         project-text
         (str "<Copyright>Portions Copyright The Apache Software Foundation "
              "and other upstream contributors; see NOTICE.txt.</Copyright>")))
    (is (str/includes? project-text
                       "Pack=\"true\" PackagePath=\"NOTICE.txt\""))
    (is (not (str/includes? project-text "BouncyCastle")))))

(deftest pdfcube-public-casing-preserves-member-kinds-and-overloads
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-io")
        fixture
        (model!
         "org/apache/pdfbox/io/NamingFixture.java"
         (str "package org.apache.pdfbox.io; "
              "public final class NamingFixture { "
              "public int PAGE_COUNT = 0; "
              "private int helperValue = 1; "
              "public int getNumberOfPages() { return helperValue; } "
              "public int read(int value) { return value; } "
              "public int read(byte[] value) { return value.length; } "
              "}"))
        emission (emit! fixture destination)
        source (slurp
                (str (paths/resolve-path
                      (:project-root emission)
                      "src/DripSharp/PdfCarton/IO/NamingFixture.cs")))
        declarations (:declarations emission)
        methods (filter #(= :method (:kind %)) declarations)
        fields (filter #(= :field (:kind %)) declarations)]
    (is (str/includes? source "#nullable disable"))
    (is (str/includes? source "public int PageCount = 0;"))
    (is (str/includes? source "private int helperValue = 1;"))
    (is (str/includes? source "public int GetNumberOfPages()"))
    (is (= 2 (count (filter #(= "Read" (:name %)) methods))))
    (is (= #{"PageCount" "helperValue"} (set (map :name fields))))
    (is (not (str/includes? source "GetNumberOfPages { get;")))
    (is (zero? (get-in emission [:summary :collisions])))
    (is
     (zero?
      (:exit
       (process/run! {:directory (:project-root emission)
                      :command ["dotnet" "build" (:project-file emission)
                                "--nologo" "--configuration" "Release"
                                "--verbosity:quiet" "-warnaserror"]}))))))

(defn- builds-without-warnings? [emission]
  (zero?
   (:exit
    (process/run! {:directory (:project-root emission)
                   :command ["dotnet" "build" (:project-file emission)
                             "--nologo" "--configuration" "Release"
                             "--verbosity:quiet" "-warnaserror"
                             "-p:NuGetAudit=false"]}))))

(deftest calendar-aliasing-follows-jdk-calendar-and-date-format-calls
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-io")
        fixture
        (model!
         "org/apache/pdfbox/io/CalendarAliasFixture.java"
         (str "package org.apache.pdfbox.io; "
              "import java.text.ParsePosition; "
              "import java.text.SimpleDateFormat; "
              "import java.util.Calendar; "
              "import java.util.GregorianCalendar; "
              "import java.util.Locale; "
              "import java.util.TimeZone; "
              "public final class CalendarAliasFixture { "
              "private static void shift(GregorianCalendar cal, TimeZone tz) { "
              "cal.setTimeZone(tz); cal.add(Calendar.MINUTE, -5); } "
              "static boolean shiftNamed(String text, GregorianCalendar cal) { "
              "shift(cal, TimeZone.getTimeZone(text)); return text.isEmpty(); } "
              "static int year(GregorianCalendar cal) { "
              "return cal.get(Calendar.YEAR); } "
              "public static void publicShift(GregorianCalendar cal) { "
              "cal.add(Calendar.MINUTE, 1); } "
              "static GregorianCalendar parse(String text, ParsePosition where) { "
              "SimpleDateFormat sdf = new SimpleDateFormat(\"yyyy\", Locale.ENGLISH); "
              "GregorianCalendar cal = new GregorianCalendar("
              "TimeZone.getTimeZone(\"UTC\"), Locale.ENGLISH); "
              "sdf.setCalendar(cal); "
              "if (sdf.parse(text, where) != null) { "
              "shiftNamed(\"UTC\", cal); return cal; } "
              "return null; } "
              "private static void nudge(GregorianCalendar cal) { "
              "cal.add(Calendar.MINUTE, 1); } "
              "static void viaReference(GregorianCalendar cal) { "
              "java.util.function.Consumer<GregorianCalendar> action = "
              "CalendarAliasFixture::nudge; action.accept(cal); } "
              "static GregorianCalendar conditional(String text, boolean other) { "
              "SimpleDateFormat first = new SimpleDateFormat(\"yyyy\", Locale.ENGLISH); "
              "GregorianCalendar a = new GregorianCalendar("
              "TimeZone.getTimeZone(\"UTC\"), Locale.ENGLISH); "
              "GregorianCalendar b = new GregorianCalendar("
              "TimeZone.getTimeZone(\"UTC\"), Locale.ENGLISH); "
              "first.setCalendar(a); "
              "if (other) { first.setCalendar(b); } "
              "first.parse(text, new ParsePosition(0)); return a; } "
              "static String scoped(java.util.Date date) { "
              "SimpleDateFormat second = new SimpleDateFormat(\"yyyy\", Locale.ENGLISH); "
              "if (date != null) { GregorianCalendar inner = new GregorianCalendar("
              "TimeZone.getTimeZone(\"UTC\"), Locale.ENGLISH); "
              "second.setCalendar(inner); } "
              "return second.format(date); } }"))
        emission (emit! fixture destination)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               "src/DripSharp/PdfCarton/IO/CalendarAliasFixture.cs")))
        calendar "global::System.DateTimeOffset?"]
    (testing "a Calendar parameter mutated directly or through a callee is by reference"
      (is (str/includes? source (str "void shift(ref " calendar " cal,")))
      (is (str/includes? source (str "bool shiftNamed(string text, ref " calendar " cal)")))
      (is (str/includes? source "shift(ref cal,"))
      (is (str/includes? source "shiftNamed(\"UTC\", ref cal)")))
    (testing "read-only and public Calendar parameters stay by value"
      (is (str/includes? source (str "int year(" calendar " cal)")))
      (is (str/includes? source (str "void PublicShift(" calendar " cal)"))))
    (testing "a mutating helper named by a method reference stays by value"
      (is (str/includes? source (str "void nudge(" calendar " cal)"))))
    (testing "parse copies the format's calendar back into the variable given to setCalendar"
      (is (str/includes? source "sdf.SetCalendar(cal);"))
      (is (str/includes? source "sdf.Parse(text, where, ref cal)")))
    (testing "no copy-back unless one setCalendar call has always run in scope"
      (is (str/includes?
           source "first.Parse(text, new global::DripSharp.Runtime.JavaParsePosition(0))"))
      (is (str/includes? source "second.Format(date)"))
      (is (not (str/includes? source "ref a)")))
      (is (not (str/includes? source "ref b)")))
      (is (not (str/includes? source "ref inner)"))))
    (testing "the lines the rule writes count as authored"
      (is (= 5 (some #(when (str/ends-with? (:file %) "CalendarAliasFixture.cs")
                        (:authored-rule-lines %))
                     (:artifacts emission)))))
    (is (builds-without-warnings? emission))))

(deftest overridden-default-interface-methods-dispatch-through-the-interface
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-io")
        fixture
        (model!
         "org/apache/pdfbox/io/DispatchFixture.java"
         (str "package org.apache.pdfbox.io; "
              "public final class DispatchFixture { "
              "public interface Lockable { "
              "default boolean isLocked() { return false; } } "
              "public static class Base implements Lockable {} "
              "public static final class Locked extends Base { "
              "@Override public boolean isLocked() { return true; } } "
              "public static boolean viaInterface() { "
              "Lockable value = new Locked(); return value.isLocked(); } }"))
        emission (emit! fixture destination)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               "src/DripSharp/PdfCarton/IO/DispatchFixture.cs")))]
    ;; The inherited default method is emitted as an implicit virtual interface
    ;; implementation, so the override is reached through the interface without
    ;; re-declaring it on the subclass.
    (is (str/includes? source "public virtual bool IsLocked()"))
    (is (str/includes? source "public override bool IsLocked()"))
    (is (not (re-find #"class Locked\s*:[^{]*Lockable" source)))
    (is (builds-without-warnings? emission))))

(deftest generic-classes-used-through-wildcards-get-a-derived-erasure-interface
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-io")
        fixture
        (model!
         "org/apache/pdfbox/io/ErasureFixture.java"
         (str "package org.apache.pdfbox.io; "
              "import java.util.ArrayList; import java.util.List; "
              "public final class ErasureFixture { "
              "public static class Policy {} "
              "public static final class StrictPolicy extends Policy {} "
              "public abstract static class Handler<P extends Policy> { "
              "private P policy; "
              "public abstract boolean isStrict(); "
              "public P getPolicy() { return policy; } "
              "public void setPolicy(P policy) { this.policy = policy; } "
              "protected int keyLength() { return 40; } "
              "public static int defaultKeyLength() { return 40; } } "
              "public static final class StrictHandler extends Handler<StrictPolicy> { "
              "@Override public boolean isStrict() { return true; } } "
              "public static final class LooseHandler extends Handler<Policy> { "
              "@Override public boolean isStrict() { return false; } } "
              "public static final class Plain<V> { public V value; } "
              "public static class Base { public int size() { return 0; } } "
              "public static final class Box<T> extends Base {} "
              "public static final class Mapper<T> { "
              "public <U> U identity(U value) { return value; } } "
              "public static final class Bag<T> { "
              "public List<T> values() { return new ArrayList<>(); } } "
              "static int kept(Box<?> box, Mapper<?> mapper, Bag<?> bag) { "
              "return box.size() + mapper.identity(\"x\").length() "
              "+ bag.values().size(); } "
              "private Handler<? extends Policy> current; "
              "private final List<Handler<?>> all = new ArrayList<>(); "
              "private final Plain<String> plain = new Plain<>(); "
              "static boolean known(Class<? extends Handler> type) { return type != null; } "
              "static boolean handler(Object value) { return value instanceof Handler; } "
              "@SuppressWarnings(\"unchecked\") "
              "static Handler<Policy> carrier(Object handler) { "
              "return (Handler<Policy>) handler; } "
              "public int use(boolean strict) { "
              "if (strict) { current = new StrictHandler(); } "
              "else { current = new LooseHandler(); } "
              "all.add(current); "
              "Handler<Policy> carrier = carrier(new StrictHandler()); "
              "carrier.setPolicy(new StrictPolicy()); "
              "return current.keyLength() + all.size() + Handler.defaultKeyLength() "
              "+ (carrier.getPolicy() == null ? 0 : 1) + plain.value.length(); } "
              "public static <T> int position(Class<T> type) { "
              "return type == null ? -1 : 0; } "
              "@SuppressWarnings(\"unchecked\") "
              "public static <T> T element(Object value, Class<T> type) { "
              "return (T) value; } "
              "public static <T> int count(Class<T> type) { "
              "List<T> values = new ArrayList<T>(); return values.size(); } "
              "public static int lookup() { return position(String.class) "
              "+ element(\"x\", String.class).length() + count(String.class); } } "
              "abstract class Checker<H extends ErasureFixture.Handler> { "
              "private final H handler; "
              "Checker(H handler) { this.handler = handler; } "
              "public H getHandler() { return handler; } "
              "abstract void check(); } "
              "final class StrictChecker extends Checker<ErasureFixture.StrictHandler> { "
              "StrictChecker() { super(new ErasureFixture.StrictHandler()); } "
              "@Override void check() {} "
              "static boolean strict(Checker<? extends ErasureFixture.Handler<?>> checker) { "
              "return checker.getHandler().isStrict(); } }"))
        emission (emit! fixture destination)
        read (fn [file]
               (slurp (str (paths/resolve-path
                            (:project-root emission)
                            (str "src/DripSharp/PdfCarton/IO/" file)))))
        source (read "ErasureFixture.cs")
        checker (read "Checker.cs")
        strict-checker (read "StrictChecker.cs")
        compact #(str/replace % #"\s+" " ")
        fixture-type "global::DripSharp.PdfCarton.IO.ErasureFixture"
        erased (str fixture-type ".IHandler")]
    (testing "the interface is derived from the class's instance methods at their erasure"
      (is (str/includes?
           (compact source)
           (str "public interface IHandler { bool IsStrict(); "
                fixture-type ".Policy GetPolicy(); "
                "void SetPolicy(" fixture-type ".Policy policy); "
                "int KeyLength(); }")))
      (is (re-find #"public abstract class Handler<P> : IHandler where P" source))
      (is (not (re-find #"interface IHandler[^}]*DefaultKeyLength" source))))
    (testing "methods naming a type parameter or not public are bridged explicitly"
      (is (str/includes? (compact source)
                         (str fixture-type ".Policy IHandler.GetPolicy() => "
                              "this.GetPolicy();")))
      (is (str/includes? (compact source)
                         (str "void IHandler.SetPolicy(" fixture-type
                              ".Policy policy) => this.SetPolicy((P)(object)policy);")))
      (is (str/includes? source "int IHandler.KeyLength() => this.KeyLength();"))
      (is (not (str/includes? source "IHandler.IsStrict()"))))
    (testing "wildcard, raw and bound-instantiation values use the interface"
      (is (str/includes? source (str "private " erased " current")))
      (is (str/includes? source
                         (str "global::System.Collections.Generic.IList<" erased "> all")))
      (is (str/includes? source (str "internal static " erased " carrier(object handler)")))
      (is (re-find (re-pattern (str "\\Q" erased "\\E carrier\\s+= ")) source))
      (is (str/includes? source (str "value is " erased))))
    (testing "created, statically accessed and inherited types keep the class"
      (is (str/includes? source (str "new " fixture-type ".StrictHandler()")))
      (is (str/includes? source (str fixture-type ".Handler<" fixture-type
                                     ".Policy>.DefaultKeyLength()")))
      (is (str/includes? (compact source)
                         (str "class StrictHandler : " fixture-type ".Handler<"
                              fixture-type ".StrictPolicy>")))
      (is (not (str/includes? source "IPlain"))))
    (testing "classes an erasure interface cannot represent keep the class"
      (doseq [name ["IBox" "IMapper" "IBag"]]
        (is (not (str/includes? source name)) name)))
    (testing "a raw type-parameter bound erases to the interface"
      (is (str/includes? (compact checker)
                         (str "internal abstract class Checker<H> : IChecker where H : "
                              erased)))
      (is (str/includes? (compact checker)
                         (str "internal interface IChecker { " erased
                              " GetHandler(); void check(); }")))
      (is (str/includes? (compact checker)
                         (str erased " IChecker.GetHandler() => this.GetHandler();")))
      (is (str/includes?
           strict-checker
           "internal static bool strict(global::DripSharp.PdfCarton.IO.IChecker checker)")))
    (testing "a method type parameter seen only through Class<T> is dropped"
      (is (str/includes? source "public static int Position(global::System.Type type)"))
      (is (str/includes? source (str fixture-type ".Position(typeof(string))")))
      (is (str/includes?
           source
           "public static T Element<T>(object value, global::System.Type type)"))
      (is (str/includes? source (str fixture-type ".Element<string>(\"x\", typeof(string))")))
      (is (str/includes?
           source
           "public static int Count<T>(global::System.Type type)")))
    (testing "the lines the rule writes count as authored"
      ;; The class header naming IHandler, the six interface lines, and the
      ;; three bridges, one of which the presentation pass wraps to two lines.
      (is (= 11 (some #(when (str/ends-with? (:file %) "ErasureFixture.cs")
                         (:authored-rule-lines %))
                      (:artifacts emission)))))
    (is (builds-without-warnings? emission))))

(deftest sibling-project-nullable-boxed-collections-keep-nullable-elements
  (let [numbers-source
        (str "package org.apache.pdfbox.cos; "
             "import java.util.ArrayList; import java.util.List; "
             "public final class NumberListFixture { "
             "public List<Float> withGaps() { "
             "List<Float> values = new ArrayList<>(); "
             "values.add(1f); values.add(null); return values; } "
             "public List<Float> dense() { "
             "List<Float> values = new ArrayList<>(); "
             "values.add(1f); return values; } }")
        {pdfbox :destination} (read-profile-and-destination "pdfcube-pdfbox")
        dependency
        (emit! (model! "org/apache/pdfbox/cos/NumberListFixture.java"
                       numbers-source)
               pdfbox)
        facts (:emission-facts dependency)
        {preflight :destination} (read-profile-and-destination "pdfcube-preflight")
        fixture
        (model!
         "org/apache/pdfbox/preflight/WidthsFixture.java"
         (str "package org.apache.pdfbox.preflight; "
              "import java.util.Collections; import java.util.List; "
              "import org.apache.pdfbox.cos.NumberListFixture; "
              "public final class WidthsFixture { "
              "public List<Float> widths(NumberListFixture numbers) { "
              "if (numbers != null) { return numbers.withGaps(); } "
              "return Collections.emptyList(); } "
              "public List<Float> dense(NumberListFixture numbers) { "
              "return numbers.dense(); } "
              "public boolean first(NumberListFixture numbers) { "
              "List<Float> widths = widths(numbers); "
              "return widths.get(0) == null; } }")
         {"org/apache/pdfbox/cos/NumberListFixture.java" numbers-source})
        emission
        (concurrency/call-with-executor
         {:worker-count 2}
         #(project-emission/emit-project!
           {:workspace-root (paths/workspace-root)
            :target (temp-directory)
            :project-input (:input fixture)
            :resolved-model (:model fixture)
            :dependency-facts facts
            :configuration preflight
            :rule-bundle (pdfcube/rule-bundle)}))
        source
        (slurp (str (paths/resolve-path
                     (:project-root emission)
                     "src/DripSharp/PdfCarton/Preflight/WidthsFixture.cs")))]
    (is (= #{"org.apache.pdfbox.cos.NumberListFixture#withGaps()"}
           (set (:nullable-boxed-returns facts))))
    (is (str/includes?
         source
         (str "public global::System.Collections.Generic.IList<float?> "
              "Widths(global::DripSharp.PdfCarton.Cos.NumberListFixture numbers)")))
    (is (str/includes?
         source
         (str "return global::DripSharp.Runtime.JavaCompat.CastList<float?>("
              "global::System.Array.Empty<float>());")))
    (is (str/includes? source "global::System.Collections.Generic.IList<float?> widths"))
    (is (str/includes?
         source
         (str "public global::System.Collections.Generic.IList<float> "
              "Dense(global::DripSharp.PdfCarton.Cos.NumberListFixture numbers)")))))

(deftest mapped-floating-rectangle-width-preserves-java-division
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-pdfbox")
        fixture
        (model!
         "org/apache/pdfbox/pdmodel/graphics/image/ImageRegionFixture.java"
         (str "package org.apache.pdfbox.pdmodel.graphics.image; "
              "import java.awt.Rectangle; "
              "public final class ImageRegionFixture { "
              "public static int sampledWidth(Rectangle region, int amount) { "
              "return (int) Math.ceil(region.getWidth() / amount); "
              "} "
              "public static float explicitlyNarrowed(double width) { "
              "return (float) width / 2; "
              "} }"))
        emission (emit! fixture destination)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               (str "src/DripSharp/PdfCarton/Pdmodel/Graphics/Image/"
                    "ImageRegionFixture.cs"))))]
    (is (= :pdfcube (get-in emission [:mapping-report :target])))
    (is (empty? (get-in emission [:mapping-report :unmapped-symbols])))
    (is (some
         #(= "executable:java.lang.Math#ceil(double)" (:resolved-key %))
         (get-in emission [:mapping-report :used-mappings])))
    (is (str/includes?
         source
         (str "global::System.Math.Ceiling((double)("
              "((double)(region.Width) / amount)))")))
    (is (not (str/includes?
              source
              "((region.Width / amount))")))
    (is (str/includes?
         source
         "return ((float)((float)(width)) / 2);"))))

(deftest pdfcube-public-field-case-collisions-preserve-both-source-identifiers
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-io")
        fixture
        (model!
         "org/apache/pdfbox/io/FieldCaseFixture.java"
         (str "package org.apache.pdfbox.io; "
              "public final class FieldCaseFixture { "
              "public static final String OFF = \"OFF\"; "
              "public static final String Off = \"Off\"; "
              "public static String upper() { return OFF; } "
              "public static String mixed() { return Off; } }"))
        emission (emit! fixture destination)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               "src/DripSharp/PdfCarton/IO/FieldCaseFixture.cs")))
        fields (filter #(= :field (:kind %)) (:declarations emission))]
    (is (str/includes? source "public const string OFF = \"OFF\";"))
    (is (str/includes? source "public const string Off = \"Off\";"))
    (is (str/includes?
         source
         "return global::DripSharp.PdfCarton.IO.FieldCaseFixture.OFF;"))
    (is (str/includes?
         source
         "return global::DripSharp.PdfCarton.IO.FieldCaseFixture.Off;"))
    (is (= #{"OFF" "Off"} (set (map :name fields))))
    (is (zero? (get-in emission [:summary :collisions])))
    (is
     (zero?
      (:exit
       (process/run! {:directory (:project-root emission)
                      :command ["dotnet" "build" (:project-file emission)
                                "--nologo" "--configuration" "Release"
                                "--verbosity:quiet" "-warnaserror"]}))))))

(deftest translated-module-types-use-approved-pdfcube-namespaces
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-fontbox")
        fixture
        (model!
         "org/apache/fontbox/FontApi.java"
         (str "package org.apache.fontbox; "
              "import org.apache.pdfbox.io.RandomAccessRead; "
              "public final class FontApi { "
              "public RandomAccessRead echo(RandomAccessRead value) { "
              "return value; } }")
         {"org/apache/pdfbox/io/RandomAccessRead.java"
          (str "package org.apache.pdfbox.io; "
               "public interface RandomAccessRead {}")})
        emission (emit! fixture destination)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               "src/DripSharp/PdfCarton/Fonts/FontApi.cs")))]
    (is (str/includes?
         source
         (str "public global::DripSharp.PdfCarton.IO.RandomAccessRead Echo("
              "global::DripSharp.PdfCarton.IO.RandomAccessRead value)")))
    (is (not (str/includes? source "org.apache.pdfbox.io")))))

(deftest translated-external-members-follow-their-java-language-shape
  ;; A sibling project's types reach this project only as compiled classes.
  ;; Their members must be named and dispatched as the sibling's declaration
  ;; side emits them, for any member of the same Java shape. The fixture
  ;; types are synthetic; only their package is the approved external one.
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-fontbox")
        fixture
        (model!
         "org/apache/fontbox/SharedConsumer.java"
         (str "package org.apache.fontbox; "
              "import org.apache.pdfbox.io.*; "
              "public final class SharedConsumer { "
              "public int use(FixtureSource source, FixtureHandle handle, FixtureFlag flag, byte[] b) "
              "throws java.io.IOException { "
              "Object empty = FixtureSource.EMPTY; "
              "Object viaInstance = source.EMPTY; "
              "int count = source.read(b); "
              "boolean cleared = !flag.getValue(); "
              "handle.close(); source.close(); "
              "java.io.Closeable lambda = () -> { }; lambda.close(); "
              "return empty != viaInstance && cleared ? count : 0; } "
              "public static final class Child extends FixtureBase { "
              "@Override public int value() { return super.value() + 1; } } }")
         {"org/apache/pdfbox/io/FixtureSource.java"
          (str "package org.apache.pdfbox.io; "
               "public interface FixtureSource extends java.io.Closeable { "
               "Object EMPTY = new Object(); "
               "int read(byte[] b, int offset, int length); "
               "default int read(byte[] b) { return read(b, 0, b.length); } }")
          "org/apache/pdfbox/io/FixtureDefaults.java"
          (str "package org.apache.pdfbox.io; "
               "public interface FixtureDefaults { default int value() { return 1; } }")
          "org/apache/pdfbox/io/FixtureBase.java"
          (str "package org.apache.pdfbox.io; "
               "public class FixtureBase implements FixtureDefaults { }")
          "org/apache/pdfbox/io/FixtureHandle.java"
          (str "package org.apache.pdfbox.io; "
               "public class FixtureHandle implements AutoCloseable { "
               "public void close() { } }")
          "org/apache/pdfbox/io/FixtureValue.java"
          (str "package org.apache.pdfbox.io; "
               "public abstract class FixtureValue { public abstract Object getValue(); }")
          "org/apache/pdfbox/io/FixtureFlag.java"
          (str "package org.apache.pdfbox.io; "
               "public class FixtureFlag extends FixtureValue { "
               "public Boolean getValue() { return Boolean.TRUE; } }")})
        emission (emit! fixture destination)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               "src/DripSharp/PdfCarton/Fonts/SharedConsumer.cs")))]
    (testing "interface constants live on the Statics companion"
      (is (str/includes?
           source
           "object empty = global::DripSharp.PdfCarton.IO.FixtureSourceStatics.Empty;"))
      (is (str/includes?
           source
           "object viaInstance = global::DripSharp.PdfCarton.IO.FixtureSourceStatics.Empty;")))
    (testing "default methods dispatch through their interface"
      (is (str/includes?
           source
           "((global::DripSharp.PdfCarton.IO.FixtureSource)(source)).Read(b)"))
      (is (str/includes? source "return unchecked((base.Value() + 1));")))
    (testing "covariant boxed overrides of an Object return are cast back"
      (is (str/includes? source "!((bool)(flag.GetValue()))")))
    (testing "Closeable and AutoCloseable close() is Dispose"
      (is (str/includes? source "handle.Dispose();"))
      (is (str/includes? source "source.Dispose();"))
      (is (not (str/includes? source ".Close()"))))
    (testing "a Closeable lambda becomes a disposable"
      ;; The product rewrites the shared runtime namespace per assembly.
      (is (re-find #"global::System\.IDisposable lambda\s+= new global::[\w.]+\.JavaCloseableAdapter\(\(\) => \{\}\);"
                   source)))))

(deftest generated-otf-parser-preserves-the-public-covariant-return-contract
  (let [workspace (paths/workspace-root)
        source
        (slurp
         (str
          (paths/resolve-path
           workspace
           "products/pdfcarton/src/DripSharp.PdfCarton.Fonts/src/DripSharp/PdfCarton/Fonts/Ttf/OTFParser.cs")))
        package-probe
        (slurp
         (str
          (paths/resolve-path
           workspace
           "targets/pdfcube/validation/probe/DripSharp.PdfCarton.Fonts.PackageProbe.cs")))]
    (is (str/includes?
         source
         (str "public new global::DripSharp.PdfCarton.Fonts.Ttf.OpenTypeFont "
              "Parse(global::DripSharp.PdfCarton.IO.RandomAccessRead randomAccessRead)")))
    (is (not (str/includes?
              source
              (str "public override global::DripSharp.PdfCarton.Fonts.Ttf.TrueTypeFont "
                   "Parse(global::DripSharp.PdfCarton.IO.RandomAccessRead randomAccessRead)"))))
    (doseq [hook
            [(str "internal override global::DripSharp.PdfCarton.Fonts.Ttf.TrueTypeFont "
                  "parse(global::DripSharp.PdfCarton.Fonts.Ttf.TTFDataStream raf)")
             (str "internal override global::DripSharp.PdfCarton.Fonts.Ttf.TrueTypeFont "
                  "newFont(global::DripSharp.PdfCarton.Fonts.Ttf.TTFDataStream raf)")]]
      (is (str/includes? source hook)))
    (doseq [ordinary-call
            ["liberation.IsPostScript()"
             "liberation.IsSupportedOTF()"
             "liberation.HasLayoutTables()"
             "sourceSans.GetCFF()"]]
      (is (str/includes? package-probe ordinary-call)))))

(deftest generated-cff-cid-font-preserves-abstract-base-covariance
  (let [workspace (paths/workspace-root)
        generated-root
        (paths/resolve-path
         workspace
         "products/pdfcarton/src/DripSharp.PdfCarton.Fonts/src/DripSharp/PdfCarton/Fonts/Cff")
        base-source
        (slurp (str (paths/resolve-path generated-root "CFFFont.cs")))
        cid-source
        (slurp (str (paths/resolve-path generated-root "CFFCIDFont.cs")))
        package-probe
        (slurp
         (str
          (paths/resolve-path
           workspace
           "targets/pdfcube/validation/probe/DripSharp.PdfCarton.Fonts.PackageProbe.cs")))]
    (is (str/includes?
         base-source
         (str "public virtual global::DripSharp.PdfCarton.Fonts.Cff.Type2CharString "
              "GetType2CharString(int cidOrGid)")))
    (is (str/includes?
         base-source
         (str "protected abstract global::DripSharp.PdfCarton.Fonts.Cff.Type2CharString "
              "__DripSharpCovariantBridgeGetType2CharString(int cidOrGid);")))
    (is (str/includes?
         cid-source
         (str "public new virtual global::DripSharp.PdfCarton.Fonts.Cff.CIDKeyedType2CharString "
              "GetType2CharString(int cid)")))
    (is (str/includes?
         cid-source
         (str "protected override global::DripSharp.PdfCarton.Fonts.Cff.Type2CharString "
              "__DripSharpCovariantBridgeGetType2CharString(int cid)")))
    (is (not (str/includes?
              cid-source
              (str "public override global::DripSharp.PdfCarton.Fonts.Cff.Type2CharString "
                   "GetType2CharString(int cid)"))))
    (doseq [contract
            ["BindingFlags.DeclaredOnly"
             "!.ReturnType.Name"
             "exactCharString.GetCID()"
             "CFFFont baseFont = cidFont"
             "ReferenceEquals(exactCharString, baseCharString)"]]
      (is (str/includes? package-probe contract)))))

(deftest fontbox-standard-charset-fields-have-complete-batch-mappings
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-fontbox")
        fixture
        (model!
         "org/apache/fontbox/StandardCharsetFixture.java"
         (str "package org.apache.fontbox; "
              "import java.nio.charset.StandardCharsets; "
              "public final class StandardCharsetFixture { "
              "public static byte[] ascii(String value) { "
              "return value.getBytes(StandardCharsets.US_ASCII); "
              "} "
              "public static String latin1(byte[] value) { "
              "return new String(value, StandardCharsets.ISO_8859_1); "
              "} }"))
        emission (emit! fixture destination)
        mapping-report (:mapping-report emission)
        charset-mappings
        (->> (:used-mappings mapping-report)
             (filter #(str/starts-with?
                       (:resolved-key %)
                       "field:java.nio.charset.StandardCharsets#"))
             (map (juxt :resolved-key identity))
             (into {}))
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               "src/DripSharp/PdfCarton/Fonts/StandardCharsetFixture.cs")))]
    (is (= :pdfcube (:target mapping-report)))
    (is (zero? (get-in mapping-report [:summary :unmapped-occurrences])))
    (is (empty? (:unmapped-symbols mapping-report)))
    (is (= #{"field:java.nio.charset.StandardCharsets#US_ASCII"
             "field:java.nio.charset.StandardCharsets#ISO_8859_1"}
           (set (keys charset-mappings))))
    (is (every?
         #(= {:registry :java-members
              :strategy :template
              :introduced-by :pdfcube
              :evidence [:differential/pdfcube-fontbox
                         :test/pdfcube-standard-charsets]
              :occurrences 1}
             (select-keys %
                          [:registry :strategy :introduced-by
                           :evidence :occurrences]))
         (vals charset-mappings)))
    (is (str/includes?
         source
         "global::DripSharp.PdfCarton.Runtime.Fonts.JavaStandardCharsets.USASCII"))
    (is (str/includes?
         source
         "global::DripSharp.PdfCarton.Runtime.Fonts.JavaStandardCharsets.ISO88591"))))

(deftest pdfbox-system-output-and-attributed-character-constructor-are-mapped
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-pdfbox")
        fixture
        (model!
         "org/apache/pdfbox/pdmodel/JdkSymbolFixture.java"
         (str
          "package org.apache.pdfbox.pdmodel; "
          "import java.text.AttributedCharacterIterator.Attribute; "
          "public final class JdkSymbolFixture { "
          "static class TextAttribute extends Attribute { "
          "TextAttribute(String name) { super(name); } } "
          "public static final Attribute WIDTH = new TextAttribute(\"width\"); "
          "public static void printWidth() { System.out.println(\"width\"); } }"))
        emission (emit! fixture destination)
        mapping-report (:mapping-report emission)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               "src/DripSharp/PdfCarton/Pdmodel/JdkSymbolFixture.cs")))]
    (is (zero? (get-in mapping-report [:summary :unmapped-occurrences])))
    (is (empty? (:unmapped-symbols mapping-report)))
    (is (some
         #(= "field:java.lang.System#out" (:resolved-key %))
         (:used-mappings mapping-report)))
    (is (str/includes?
         source
         "global::DripSharp.Runtime.JavaCompat.@out"))
    (is (str/includes?
         source
         "global::DripSharp.Runtime.JavaAttributedCharacterAttribute"))))

(deftest fontbox-discovery-uses-the-internal-platform-adapter
  (let [{destination :destination}
        (read-profile-and-destination "pdfcube-fontbox")
        fixture
        (model!
         "org/apache/fontbox/util/autodetect/DiscoveryFixture.java"
         (str
          "package org.apache.fontbox.util.autodetect; "
          "import java.io.File; "
          "import java.net.URI; "
          "import java.util.Map; "
          "import java.util.TreeMap; "
          "public final class DiscoveryFixture { "
          "public Map<String, byte[]> sortedTables() { "
          "return new TreeMap<>(); "
          "} "
          "public URI inspect(File file) { "
          "if (file.exists() && file.canRead() && file.isDirectory() "
          "&& !file.isHidden()) { "
          "File[] entries = file.listFiles(); "
          "if (entries != null && entries.length > 0) { "
          "return entries[0].toURI(); "
          "} } "
          "return file.toURI(); "
          "} }"))
        emission (emit! fixture destination)
        source
        (slurp
         (str (paths/resolve-path
               (:project-root emission)
               (str "src/DripSharp/PdfCarton/Fonts/Util/Autodetect/"
                    "DiscoveryFixture.cs"))))
        adapter-file
        (paths/resolve-path
         (:project-root emission)
         "src/DripSharp/Runtime/PdfCartonFontDiscovery.cs")
        adapter (slurp (str adapter-file))]
    (is (paths/regular-file? adapter-file))
    (doseq [member ["FileExists" "FileCanRead" "FileIsDirectory"
                    "FileIsHidden" "FileListFiles" "FileToUri"]]
      (is (str/includes?
           source
           (str "global::DripSharp.PdfCarton.Runtime.Fonts.PdfCartonFontDiscovery."
                member "("))))
    (is (str/includes? adapter "namespace DripSharp.PdfCarton.Runtime.Fonts;"))
    (is (str/includes? adapter
                       "internal static class PdfCartonFontDiscovery"))
    (is (not (str/includes? adapter
                            "public static class PdfCartonFontDiscovery")))
    (is (str/includes?
         source
         (str "new global::DripSharp.PdfCarton.Runtime.Fonts."
              "JavaTreeMap<string, sbyte[]>()")))))

(deftest resolved-module-surface-is-complete-and-blocks-stubs
  (let [workspace (paths/workspace-root)
        {destination :destination}
        (read-profile-and-destination "pdfcube-io")
        fixture
        (model!
         "org/apache/pdfbox/io/SurfaceFixture.java"
         (str "package org.apache.pdfbox.io; "
              "public class SurfaceFixture { "
              "protected int state = 0; "
              "public SurfaceFixture() {} "
              "protected SurfaceFixture(int value) { this.state = value; } "
              "public int read(int value) { return value; } "
              "public int read(byte[] value) { return value.length; } "
              "protected int hook() { return state; } "
              "public static class Nested { "
              "public int ping() { return 1; } } "
              "public enum Choice { A, B } "
              "private static class Hidden { "
              "public int leaked() { return 2; } } "
              "}"))
        strategy (pdfcube/public-surface-strategy)
        surface ((:read! strategy) workspace {})
        selected ((:validate-selected! strategy)
                  workspace surface (:model fixture))
        emission (emit! fixture destination selected)
        metadata ((:validate-generated! strategy) selected emission)
        hidden-rows
        (filter #(str/includes? (get-in % [:row :owner]) "Hidden")
                (:rows metadata))
        stub-declarations
        (loop [remaining (:declarations emission) changed? false result []]
          (if-let [declaration (first remaining)]
            (if (and (not changed?)
                     (= :method (:kind declaration))
                     (= "Read" (:name declaration)))
              (recur (next remaining) true
                     (conj result (assoc declaration
                                         :implementation :public-stub)))
              (recur (next remaining) changed? (conj result declaration)))
            result))
        stub-error
        (caught #((:validate-generated! strategy)
                  selected (assoc emission :declarations stub-declarations)))
        unsupported-error
        (caught #((:validate-generated! strategy)
                  selected
                  (assoc-in emission
                            [:summary :executable-coverage
                             :unsupported-elements]
                            1)))]
    (is (= :resolved-spoon-model (:derivation selected)))
    (is (= 16 (count (:rows selected))
           (:required-rows metadata)))
    (is (= 16 (count (distinct (map #(get-in % [:row :identity])
                                    (:rows metadata))))))
    (is (empty? hidden-rows))
    (is (every? #(contains? #{:one-to-one
                              :documented-systematic-adaptation}
                            (:source-mapping %))
                (:rows metadata)))
    (is (every? #(contains? (:systematic-adaptations metadata) %)
                [:java-enum-values :java-enum-value-of
                 :java-enum-name-to-string]))
    (is (= :public-java-library-stub (:kind (ex-data stub-error))))
    (is (= :incomplete-java-library-public-surface
           (:kind (ex-data unsupported-error))))
    (process/run! {:directory (:project-root emission)
                   :command ["dotnet" "build" (:project-file emission)
                             "--nologo" "--configuration" "Release"
                             "--verbosity:quiet" "-warnaserror"]})
    (let [audit
          ((:verify-compiled! strategy)
           workspace
           {:dependency-emissions []
            :destination destination
            :emission (assoc emission :public-metadata metadata)}
           "Release")]
      (is (= :complete-accessible-java-library (:strategy audit)))
      (is (= 16 (get-in audit [:assemblies 0 :contract-members])))
      (is (pos? (get-in audit [:assemblies 0 :rows]))))))
