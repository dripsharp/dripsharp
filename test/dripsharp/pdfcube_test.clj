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
    (is (every? ifn? (map :text-transform codec-assets)))
    (is (every? #(= expected ((:text-transform %) source))
                codec-assets))
    (is (every? #(= expected
                    ((:text-transform %)
                     ((:text-transform %) source)))
                codec-assets))))

(defn- structured-type
  [qualified-name header-nodes members constraints?]
  (csharp/declaration
   (csharp/sequence-node header-nodes)
   (csharp/block (csharp/statement-list members "\n\n"))
   {:declaration-kind :type
    :name (last (str/split qualified-name #"[.]"))
    :source-qualified-name qualified-name
    :has-base-types? false
    :has-constraints? constraints?}))

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

(deftest preflight-font-types-implement-erased-runtime-contracts
  (let [configuration {:package {:id "DripSharp.PdfCarton.Preflight"}}
        container
        "global::DripSharp.PdfCarton.Preflight.Font.Container.FontContainer"
        font-like "global::DripSharp.PdfCarton.Pdmodel.Font.PDFontLike"
        container-result
        (render-transformed
         configuration
         (structured-type
          "org.apache.pdfbox.preflight.font.container.FontContainer"
          [(csharp/raw "public abstract class FontContainer<T>")
           (csharp/raw (str " where T : " font-like))]
          [(csharp/raw (str "private " container "<object> value;"))]
          true))
        validator-result
        (render-transformed
         configuration
         (structured-type
          "org.apache.pdfbox.preflight.font.FontValidator"
          [(csharp/raw "public abstract class FontValidator<T>")
           (csharp/raw
            (str " where T : "
                 "global::DripSharp.PdfCarton.Preflight.Font.Container.IFontContainer"))]
          [(csharp/raw
            (str "private global::DripSharp.PdfCarton.Preflight.Font.FontValidator<"
                 container
                 "<global::DripSharp.PdfCarton.Pdmodel.Font.PDCIDFont>> value;"))]
          true))]
    (is (not (str/includes? (:text container-result)
                            "public interface IFontContainer")))
    (is (str/includes?
         (:text container-result)
         "public abstract class FontContainer<T> : IFontContainer"))
    (is (not (str/includes? (:text validator-result)
                            "public interface IFontValidator")))
    (is (str/includes?
         (:text validator-result)
         (str "public abstract class FontValidator<T> : IFontValidator where T : "
              "global::DripSharp.PdfCarton.Preflight.Font.Container.IFontContainer")))
    (is (str/includes?
         (:text validator-result)
         (str "global::DripSharp.PdfCarton.Preflight.Font.Container.IFontContainer "
              "IFontValidator.GetFontContainer() => GetFontContainer();")))))

(deftest preflight-position-lookup-erases-unused-method-type-parameter
  (let [position-method
        (csharp/declaration
         (csharp/sequence-node
          [(csharp/raw "public int ")
           (csharp/raw "GetClosestTypePosition")
           (csharp/raw "<T>")
           (csharp/raw "(global::System.Type type)")])
         (csharp/block
          (csharp/statement-list [(csharp/raw "return -1;")] "\n"))
         {:declaration-kind :method
          :source-qualified-name
          "org.apache.pdfbox.preflight.PreflightPath"
          :source-name "getClosestTypePosition"})
        path-method
        (csharp/declaration
         (csharp/raw
          "public T GetClosestPathElement<T>(global::System.Type type)")
         (csharp/block
          (csharp/statement-list
           [(csharp/sequence-node
             [(csharp/raw "return ")
              (csharp/raw "GetPathElement")
              (csharp/raw "(")
              (csharp/invocation
               (csharp/generic-name
                (csharp/sequence-node
                 [(csharp/raw "vPath.")
                  (csharp/raw "GetClosestTypePosition")])
                [(csharp/raw "object")])
               [(csharp/raw "type")])
              (csharp/raw ", type);")])]
           "\n"))
         {:declaration-kind :method
          :source-qualified-name
          "org.apache.pdfbox.preflight.PreflightPath"
          :source-name "getClosestPathElement"})
        transformed
        (render-transformed
         {:package {:id "DripSharp.PdfCarton.Preflight"}}
         (csharp/sequence-node [position-method path-method] "\n"))
        transformed-twice
        (csharp/render
         (transform-node
          {:package {:id "DripSharp.PdfCarton.Preflight"}}
          (transform-node
           {:package {:id "DripSharp.PdfCarton.Preflight"}}
           (csharp/sequence-node [position-method path-method] "\n"))))]
    (is (str/includes?
         (:text transformed)
         "public int GetClosestTypePosition(global::System.Type type)"))
    (is (not (str/includes? (:text transformed)
                            "GetClosestTypePosition<T>")))
    (is (str/includes? (:text transformed)
                       "GetPathElement<T>("))
    (is (not (str/includes? (:text transformed-twice)
                            "GetPathElement<T><T>")))
    (is (not (str/includes? (:text transformed)
                            "GetClosestTypePosition<object>")))))

(deftest preflight-null-wildcard-validator-local-uses-erased-contract
  (let [method
        (csharp/declaration
         (csharp/raw
          (str "protected global::DripSharp.PdfCarton.Preflight.Font.IFontValidator "
               "CreateDescendantValidator(object font)"))
         (csharp/block
          (csharp/statement-list
           [(csharp/raw "var cidFontValidator = default!;")
            (csharp/raw "return cidFontValidator!;")]
           "\n"))
         {:declaration-kind :method
          :source-qualified-name
          "org.apache.pdfbox.preflight.font.Type0FontValidator"
          :source-name "createDescendantValidator"})
        result
        (render-transformed
         {:package {:id "DripSharp.PdfCarton.Preflight"}}
         method)]
    (is (str/includes?
         (:text result)
         (str "global::DripSharp.PdfCarton.Preflight.Font.IFontValidator "
              "cidFontValidator = default!;")))
    (is (not (str/includes? (:text result)
                            "var cidFontValidator = default!;")))))

(deftest preflight-type3-widths-preserve-nullable-boxed-floats
  (let [configuration {:package {:id "DripSharp.PdfCarton.Preflight"}}
        result
        (render-transformed
         configuration
         (structured-type
          "org.apache.pdfbox.preflight.font.Type3FontValidator"
          [(csharp/raw "public class Type3FontValidator")]
          [(csharp/sequence-node
            [(csharp/generic-name
              (csharp/raw "global::System.Collections.Generic.IList")
              [(csharp/raw "float")])
             (csharp/raw " widths = value;\nfloat width = ")
             (csharp/invocation
              (csharp/generic-name
               (csharp/raw
                "global::DripSharp.Runtime.JavaCompat.UnboxObject")
               [(csharp/raw "float")])
              [(csharp/raw "value")])
             (csharp/raw ";\nreturn ")
             (csharp/sequence-node
              [(csharp/raw "global::System.Array.Empty<")
               (csharp/raw "float")
               (csharp/raw ">()")])
             (csharp/raw ";")])]
          false))]
    (is (str/includes?
         (:text result)
         "global::System.Collections.Generic.IList<float?> widths"))
    (is (str/includes?
         (:text result)
         (str "float width = global::DripSharp.Runtime.JavaCompat."
              "UnboxObject<float>(value);")))
    (is (str/includes?
         (:text result)
         "return global::System.Array.Empty<float?>();"))))

(deftest pdfbox-security-handler-erasure-preserves-specialized-handlers
  (let [carrier
        (str "global::DripSharp.PdfCarton.Pdmodel.Encryption.SecurityHandler"
             "<global::DripSharp.PdfCarton.Pdmodel.Encryption.ProtectionPolicy>")
        erased "global::DripSharp.Runtime.PdfBoxSecurityHandler"
        specialized
        (str "public sealed class StandardSecurityHandler : "
             "global::DripSharp.PdfCarton.Pdmodel.Encryption.SecurityHandler"
             "<global::DripSharp.PdfCarton.Pdmodel.Encryption.StandardProtectionPolicy> {}")
        structured-carrier
        (csharp/declaration
         (csharp/sequence-node
          [(csharp/raw "public ")
           (csharp/generic-name
            (csharp/raw
             "global::DripSharp.PdfCarton.Pdmodel.Encryption.SecurityHandler")
            [(csharp/raw
              "global::DripSharp.PdfCarton.Pdmodel.Encryption.ProtectionPolicy")])
           (csharp/raw " Create() => default!")]))
        configuration {:internal-capabilities #{:security-handler-erasure}}]
    (is (= (str "public " erased " Create() => default!;")
           (:text
            (render-transformed
             configuration
             (csharp/raw
              (str "public " carrier " Create() => default!;"))))))
    (is (= (str "public " erased " Create() => default!;")
           (:text
            (render-transformed configuration structured-carrier))))
    (is (= (str "#nullable disable\n"
                "public abstract class SecurityHandler<TPOLICY> : "
                erased " where TPOLICY : ProtectionPolicy {\n"
                "private int value = " carrier ".DEFAULT_KEY_LENGTH;\n}")
           (:text
            (render-transformed
             configuration
             (csharp/sequence-node
              [(csharp/raw "#nullable disable\n")
               (csharp/sequence-node
                [(structured-type
                  "org.apache.pdfbox.pdmodel.encryption.SecurityHandler"
                  [(csharp/raw
                    "public abstract class SecurityHandler<TPOLICY>")
                   (csharp/raw " where TPOLICY : ProtectionPolicy")]
                  [(csharp/raw
                    (str "private int value = " carrier
                         ".DEFAULT_KEY_LENGTH;"))]
                  true)])])))))
    (is (= specialized
           (:text
            (render-transformed configuration (csharp/raw specialized)))))))

(deftest pdfbox-calendar-value-semantics-preserve-date-time-zone-mutations
  (let [adjust
        (csharp/declaration
         (csharp/sequence-node
          [(csharp/raw "private static ")
           (csharp/raw "void")
           (csharp/raw
            (str " adjustTimeZoneNicely("
                 "global::System.DateTimeOffset? cal, "
                 "global::System.TimeZoneInfo tz)"))])
         (csharp/block
          [(csharp/raw
            (str "cal = global::DripSharp.Runtime.JavaCompat."
                 "CalendarAdd(cal, 12, -offset);"))])
         {:declaration-kind :method
          :source-name "adjustTimeZoneNicely"
          :source-qualified-name "org.apache.pdfbox.util.DateConverter"})
        parse-offset
        (csharp/declaration
         (csharp/raw
          (str "internal static bool parseTZoffset(string text, "
               "global::System.DateTimeOffset? cal, Position initialWhere)"))
         (csharp/block [])
         {:declaration-kind :method
          :source-name "parseTZoffset"
          :source-qualified-name "org.apache.pdfbox.util.DateConverter"})
        parse-simple
        (csharp/declaration
         (csharp/raw
          (str "private static global::System.DateTimeOffset? parseSimpleDate("
               "string text, string[] fmts, Position initialWhere)"))
         (csharp/block [(csharp/raw "return retCal;")])
         {:declaration-kind :method
          :source-name "parseSimpleDate"
          :source-qualified-name "org.apache.pdfbox.util.DateConverter"})
        transformed
        (:text
         (render-transformed
          {:internal-capabilities #{:calendar-value-semantics}}
          (csharp/sequence-node
           [adjust (csharp/raw "\n") parse-offset
            (csharp/raw "\n") parse-simple])))]
    (is (str/includes?
         transformed
         "private static global::System.DateTimeOffset? adjustTimeZoneNicely("))
    (is (str/includes?
         transformed
         "CalendarAdd(cal, 12, -offset);\nreturn cal;\n}"))
    (is (str/includes?
         transformed
         "parseTZoffset(string text, ref global::System.DateTimeOffset? cal,"))
    (is (str/includes? transformed "parsedDate = sdf.Parse(text, where);"))
    (is (str/includes? transformed "return parsedDate;"))
    (is (not (str/includes? transformed "return retCal;")))
    (is (not (str/includes? transformed "private static void")))))

(deftest pdfbox-unmodifiable-dictionary-reimplements-update-interface
  (let [declaration
        (csharp/declaration
         (csharp/sequence-node
          [(csharp/raw "internal sealed class UnmodifiableCOSDictionary")
           (csharp/raw " : global::DripSharp.PdfCarton.Cos.COSDictionary")])
         (csharp/block (csharp/statement-list [] "\n\n"))
         {:declaration-kind :type
          :name "UnmodifiableCOSDictionary"
          :source-qualified-name
          "org.apache.pdfbox.cos.UnmodifiableCOSDictionary"
          :has-base-types? true
          :has-constraints? false})
        transformed
        (:text
         (render-transformed
          {:package {:id "DripSharp.PdfCarton"}}
          declaration))]
    (is (str/includes?
         transformed
         (str "COSDictionary, "
              "global::DripSharp.PdfCarton.Cos.COSUpdateInfo")))))

(deftest pdfbox-annotation-rendering-matches-content-stream-number-precision
  (let [assignment
        (str "global::DripSharp.PdfCarton.Util.Matrix aa = "
             "global::DripSharp.PdfCarton.Util.Matrix.Concatenate(a, matrix);")
        declaration
        (csharp/declaration
         (csharp/raw "protected internal void processAnnotation()")
         (csharp/block [(csharp/raw assignment)])
         {:declaration-kind :method
          :source-name "processAnnotation"
          :source-qualified-name
          "org.apache.pdfbox.contentstream.PDFStreamEngine"})
        transformed
        (:text
         (render-transformed
          {:package {:id "DripSharp.PdfCarton"}}
          declaration))]
    (is (str/includes? transformed assignment))
    (is (str/includes? transformed "for (int row = 0; row < 3; row++)"))
    (is (str/includes? transformed "aa.GetValue(row, column), 5"))
    (is (str/includes?
         transformed
         "global::System.MidpointRounding.AwayFromZero"))))

(deftest sampled-one-bit-images-synchronize-managed-raster-before-return
  (let [declaration
        (csharp/declaration
         (csharp/raw "private static object from1Bit()")
         (csharp/block [(csharp/raw "return bim!;")])
         {:declaration-kind :method
          :source-name "from1Bit"
          :source-qualified-name
          "org.apache.pdfbox.pdmodel.graphics.image.SampledImageReader"})
        transformed
        (:text
         (render-transformed
          {:package {:id "DripSharp.PdfCarton"}}
          declaration))]
    (is (str/includes?
         transformed
         "PdfCartonFontCompat.SetImageData(bim!, raster);"))
    (is (< (.indexOf transformed "SetImageData(bim!, raster)")
           (.indexOf transformed "return bim!;")))))

(deftest color-space-conversion-owns-its-intermediate-bitmap
  (let [source-declaration
        (str "global::SkiaSharp.SKBitmap src = "
             "global::DripSharp.Runtime.PdfCartonFontCompat.CreateImage("
             "colorModel, raster, false, "
             "(global::DripSharp.Runtime.JavaHashtable<object, object>)default!);")
        filter-call "this.colorConvertOp.Filter(src, dest);"
        draw-call "g2d.DrawImage(src, 0, 0, (object)default!);"
        declaration
        (csharp/declaration
         (csharp/raw "protected internal virtual object ToRGBImageAWT()")
         (csharp/block [(csharp/raw source-declaration)
                        (csharp/raw draw-call)
                        (csharp/raw "g2d.Dispose();")
                        (csharp/raw filter-call)
                        (csharp/raw "return dest;")])
         {:declaration-kind :method
          :source-name "toRGBImageAWT"
          :source-qualified-name
          "org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace"})
        transformed
        (:text
         (render-transformed
          {:package {:id "DripSharp.PdfCarton"}}
          declaration))]
    (is (str/includes? transformed "using global::SkiaSharp.SKBitmap src"))
    (is (str/includes? transformed "PdfCartonFontCompat.CreateDeferredImage("))
    (is (not (str/includes? transformed "PdfCartonFontCompat.CreateImage(")))
    (testing "the one-pixel drawing branch disposes its owned objects on failure"
      (let [draw (.indexOf transformed draw-call)
            cleanup (.indexOf transformed "g2d.Dispose();\n" draw)]
        (is (< draw cleanup (.indexOf transformed "dest.Dispose();" cleanup)))))
    (testing "the conversion branch disposes the owned result on failure"
      (let [filter (.indexOf transformed filter-call)]
        (is (< filter
               (.indexOf transformed "dest.Dispose();" filter)
               (.indexOf transformed "return dest;")))))
    (testing "an upstream change to the rewritten statements fails generation"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"missing the expected statement"
           (render-transformed
            {:package {:id "DripSharp.PdfCarton"}}
            (csharp/declaration
             (csharp/raw "protected internal virtual object ToRGBImageAWT()")
             (csharp/block [(csharp/raw filter-call)])
             {:declaration-kind :method
              :source-name "toRGBImageAWT"
              :source-qualified-name
              "org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace"})))))))

(deftest decode-filters-own-their-intermediate-bitmaps
  (let [method (fn [type-name source-name parameter-count statements]
                 (csharp/declaration
                  (csharp/raw (str "private object " source-name "()"))
                  (csharp/block (mapv csharp/raw statements))
                  {:declaration-kind :method
                   :source-name source-name
                   :parameter-count parameter-count
                   :source-qualified-name type-name}))
        render (fn [declaration]
                 (:text (render-transformed
                         {:package {:id "DripSharp.PdfCarton"}}
                         declaration)))
        read-statement "image = reader.Read(0, irp);"
        jbig2 (render
               (method
                "org.apache.pdfbox.filter.JBIG2Filter" "decode" 5
                ["global::SkiaSharp.SKBitmap image;"
                 read-statement
                 (str "global::SkiaSharp.SKBitmap packedImage = "
                      "global::DripSharp.Runtime.PdfCartonFontCompat.CreateBitmap("
                      "image.Width, image.Height, "
                      "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_BYTE_BINARY);")
                 (str "global::DripSharp.Runtime.PdfCartonGraphics2D graphics = "
                      "global::DripSharp.Runtime.PdfCartonFontCompat.CreateGraphics(packedImage);")
                 "image = packedImage;"]))
        jpx (render
             (method
              "org.apache.pdfbox.filter.JPXFilter" "readJPX" 3
              ["global::SkiaSharp.SKBitmap image;"
               read-statement
               (str "global::SkiaSharp.SKBitmap smask = "
                    "global::DripSharp.Runtime.PdfCartonFontCompat.CreateBitmap("
                    "image.Width, image.Height, "
                    "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_BYTE_GRAY);")
               "result.setJPXSMask(smask);"
               (str "global::SkiaSharp.SKBitmap bim = "
                    "global::DripSharp.Runtime.PdfCartonFontCompat.CreateBitmap("
                    "image.Width, image.Height, "
                    "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_INT_RGB);")
               (str "global::DripSharp.Runtime.PdfCartonGraphics2D g2d = "
                    "(global::DripSharp.Runtime.PdfCartonGraphics2D)("
                    "global::DripSharp.Runtime.PdfCartonFontCompat.CreateGraphics(bim)!);")
               "image = bim;"
               "return image;"]))]
    (testing "the JBIG2 image, its repacking and graphics are owned by one scope"
      (is (str/includes? jbig2 "using global::DripSharp.Runtime.PdfCartonDisposalScope __dripsharpOwned"))
      (is (= 3 (count (re-seq #"__dripsharpOwned\.Own\(" jbig2))))
      (is (str/includes? jbig2 "__dripsharpOwned.Own(reader.ReadDeferred(0, irp))")))
    (testing "readJPX releases only the image it returns"
      (is (str/includes? jpx "__dripsharpOwned.Own(reader.ReadDeferred(0, irp))"))
      (is (= 4 (count (re-seq #"__dripsharpOwned\.Own\(" jpx))))
      (is (< (.indexOf jpx "__dripsharpOwned.Release(result.GetJPXSMask());")
             (.indexOf jpx "return __dripsharpOwned.Release(image);"))))
    (testing "DCT keeps an array copy of the raster and disposes the image"
      (let [dct (render
                 (method
                  "org.apache.pdfbox.filter.DCTFilter" "readImageRaster" 2
                  ["global::SkiaSharp.SKBitmap image = reader.Read(0, irp);"
                   "raster = global::DripSharp.Runtime.PdfCartonFontCompat.GetRaster(image);"]))]
        (is (str/includes? dct "using global::SkiaSharp.SKBitmap image = reader.Read(0, irp);"))
        (is (str/includes? dct "PdfCartonFontCompat.GetImageData(image);"))))
    (testing "only the JPX decode overload with the readJPX call is rewritten"
      (let [overload (method "org.apache.pdfbox.filter.JPXFilter" "decode" 4
                             ["return this.Decode(encoded, decoded, parameters, index, options);"])]
        (is (= (render overload)
               (:text (csharp/render overload)))))
      (let [decode (render (method "org.apache.pdfbox.filter.JPXFilter" "decode" 5
                                   [(str "global::SkiaSharp.SKBitmap image = "
                                         "this.readJPX(encoded, options, result);")
                                    "return result;"
                                    "return result;"]))]
        (is (str/includes? decode "using global::SkiaSharp.SKBitmap image = this.readJPX("))
        (is (str/includes? decode "__dripsharpOwned.Own(result.GetJPXSMask());"))
        (testing "every successful exit hands the soft mask to the caller"
          (is (= 2 (count (re-seq #"__dripsharpOwned\.Release\(result\.GetJPXSMask\(\)\);\s*return result;"
                                  decode)))))))
    (testing "an upstream change to a rewritten statement fails generation"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"missing the expected statement"
           (render (method "org.apache.pdfbox.filter.JBIG2Filter" "decode" 5
                           ["global::SkiaSharp.SKBitmap image;"])))))))

(deftest rendering-intermediates-are-owned
  (let [config {:package {:id "DripSharp.PdfCarton"}}
        render (fn [node] (:text (render-transformed config node)))
        declaration (fn [kind type-name data statements]
                      (csharp/declaration
                       (csharp/raw (str "void " (or (:source-name data) "ctor") "()"))
                       (csharp/block (mapv csharp/raw statements))
                       (merge {:declaration-kind kind
                               :source-qualified-name type-name}
                              data)))
        group-image "this.image = this.create2ByteGrayAlphaImage(this.width, this.height);"
        group-ctor (fn [parameter-count]
                     (declaration
                      :constructor "org.apache.pdfbox.rendering.PageDrawer$TransparencyGroup"
                      {:parameter-count parameter-count}
                      [(str "global::DripSharp.PdfCarton.Pdmodel.Graphics.Form."
                            "PDTransparencyGroupAttributes group = form.GetGroup();")
                       group-image
                       (str "this.image = global::DripSharp.Runtime.PdfCartonFontCompat."
                            "CreateBitmap(this.width, this.height, "
                            "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_INT_ARGB);")
                       (str "global::DripSharp.Runtime.PdfCartonGraphics2D g = "
                            "global::DripSharp.Runtime.PdfCartonFontCompat.CreateGraphics(this.image);")
                       (str "g = new global::DripSharp.PdfCarton.Rendering.GroupGraphics("
                            "this.image, g);")
                       (str "global::DripSharp.Runtime.JavaCompat.DequePush("
                            "this.__outer.transparencyGroupStack, this);")
                       "try { } finally { }"]))]
    (testing "a constructor key rewrites only constructors with that parameter count"
      (let [rendered (render (group-ctor 4))]
        (is (= 5 (count (re-seq #"__dripsharpOwned\.Own\(" rendered))))
        (is (str/includes? rendered "this.__outer.transparencyGroupStack.Pop();"))
        (is (str/ends-with? (str/trim (subs rendered 0 (str/last-index-of rendered "}")))
                            "__dripsharpOwned.Release(this.image);")))
      (let [other (group-ctor 3)]
        (is (= (:text (csharp/render other)) (render other)))))
    (testing "parameter types select one of two overloads with the same arity"
      (let [device-n (fn [types statements]
                       (declaration :method
                                    "org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN"
                                    {:source-name "toRGBWithAttributes"
                                     :parameter-count 1
                                     :parameter-types types}
                                    statements))
            values (device-n ["float[]"] ["return value;"])]
        (is (= (:text (csharp/render values)) (render values)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"missing the expected statement"
             (render (device-n ["java.awt.image.WritableRaster"] ["return value;"]))))))
    (testing "applyMask declares its scope first and releases every returned image"
      (let [rendered (render (declaration
                              :method "org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject"
                              {:source-name "applyMask" :parameter-count 5}
                              ["return image;"
                               (str "mask = global::DripSharp.PdfCarton.Pdmodel.Graphics.Image."
                                    "PDImageXObject.scaleImage(mask, width, height, "
                                    "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_BYTE_GRAY, "
                                    "interpolateMask);")
                               (str "mask = global::DripSharp.PdfCarton.Pdmodel.Graphics.Image."
                                    "PDImageXObject.scaleImage(mask, width, height, "
                                    "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_BYTE_GRAY, false);")
                               (str "image = global::DripSharp.PdfCarton.Pdmodel.Graphics.Image."
                                    "PDImageXObject.scaleImage(image, width, height, "
                                    "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_INT_ARGB, "
                                    "this.GetInterpolate());")
                               (str "image = global::DripSharp.PdfCarton.Pdmodel.Graphics.Image."
                                    "PDImageXObject.scaleImage(image, width, height, "
                                    "global::DripSharp.Runtime.PdfCartonFontCompat.TYPE_INT_ARGB, false);")
                               "return image;"]))]
        (is (< (.indexOf rendered "PdfCartonDisposalScope __dripsharpOwned")
               (.indexOf rendered "return __dripsharpOwned.Release(image);")))
        (is (= 2 (count (re-seq #"return __dripsharpOwned\.Release\(image\);" rendered))))))
    (testing "the page drawer keeps one soft-mask image in a field it declares"
      (let [drawer (csharp/declaration
                    (csharp/raw "public class PageDrawer")
                    (csharp/block [(csharp/raw "private int other;")])
                    {:declaration-kind :type
                     :source-qualified-name "org.apache.pdfbox.rendering.PageDrawer"})
            rendered (render drawer)]
        (is (< (.indexOf rendered "__dripsharpSoftMaskImage;")
               (.indexOf rendered "__dripsharpReplaceSoftMaskImage(")
               (.indexOf rendered "private int other;")))))))

(deftest pdfbox-text-normalization-preserves-upstream-arabic-mark-order
  (let [declaration
        (csharp/declaration
         (csharp/raw "private string normalizeWord(string word)")
         (csharp/block [(csharp/raw "return word;")])
         {:declaration-kind :method
          :source-name "normalizeWord"
          :source-qualified-name
          "org.apache.pdfbox.text.PDFTextStripper"})
        transformed
        (:text
         (render-transformed
          {:package {:id "DripSharp.PdfCarton"}}
          declaration))]
    (is (str/includes?
         transformed
         (str "PdfBoxTextCompatibility.NormalizeVisualWord("
              "word, this.sortByPosition)")))
    (is (str/includes?
         (slurp "targets/pdfcube/runtime/DripSharp.PdfCarton.Compat.cs")
         "normalized = ReverseUtf16(normalized)"))
    (is (str/includes?
         (slurp "targets/pdfcube/runtime/DripSharp.PdfCarton.Compat.cs")
         "return normalizedWord.ToString()"))))

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
    (is (= #{:preflight-font-erasure}
           (:internal-capabilities
            (:destination (read-profile-and-destination "pdfcube-preflight")))))
    (is (=
         {"org.apache.pdfbox.preflight.font.container.FontContainer"
          "global::DripSharp.PdfCarton.Preflight.Font.Container.IFontContainer"
          "org.apache.pdfbox.preflight.font.FontValidator"
          "global::DripSharp.PdfCarton.Preflight.Font.IFontValidator"}
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
         (str "global::DripSharp.PdfCarton.Runtime.Fonts.JavaCompat."
              "NewSortedDictionary<string, sbyte[]>()")))))

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
