(ns dripsharp.pdfcube.java-project
  "PdfCube destination composition and fail-closed five-package policy.

  The reusable Java-library translator supplies structural Java translation.
  This namespace owns only PdfCube's approved product identities, namespace and
  public-name policy, source-to-destination dependency projections, legal
  inputs, resource policy, and deterministic project metadata."
  (:require [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as project-emission]
            [dripsharp.paths :as paths]
            [dripsharp.project-xml :as project-xml]
            [dripsharp.util :as util]))

(def ^:private source-revision
  (baseline/upstream-revision :pdfcube))

(def ^:private source-version
  (baseline/upstream-version :pdfcube))

(def ^:private mechanical-source
  (baseline/mechanical-source :pdfcube))

(def ^:private bundle-selector
  'dripsharp.pdfcube.java-project/rule-bundle)

(def ^:private surface-selector
  'dripsharp.pdfcube.java-project/public-surface-strategy)

(def ^:private commons-coordinate
  "commons-logging:commons-logging:jar:1.4.0")

(def ^:private commons-dependency
  {:source-scope :compile-runtime
   :artifact-sha256 (baseline/artifact-sha256 :pdfcube commons-coordinate)
   :runtime-package true
   :destination
   {:kind :microsoft-package
    :id "Microsoft.Extensions.Logging.Abstractions"
    :version "10.0.0"}})

(def ^:private commons-type-mappings
  {"org.apache.commons.logging.Log"
   ["global::Microsoft.Extensions.Logging.ILogger"
    :pdfcube.type/microsoft-logger]
   "org.apache.commons.logging.LogFactory"
   ["global::Microsoft.Extensions.Logging.Abstractions.NullLogger"
    :pdfcube.type/microsoft-null-logger]
   "org.bouncycastle.cert.X509CertificateHolder"
   ["global::DripSharp.Runtime.JavaX509CertificateHolder"
    :pdfcube.type/x509-certificate-holder]
   "org.bouncycastle.asn1.ASN1Primitive"
   ["global::DripSharp.Runtime.JavaAsn1Primitive"
    :pdfcube.type/asn1-primitive]
   "org.bouncycastle.asn1.ASN1Encodable"
   ["global::DripSharp.Runtime.JavaAsn1Primitive"
    :pdfcube.type/asn1-encodable]
   "org.bouncycastle.asn1.ASN1Encoding"
   ["global::DripSharp.Runtime.JavaAsn1Encoding"
    :pdfcube.type/asn1-encoding]
   "org.bouncycastle.asn1.ASN1Object"
   ["global::DripSharp.Runtime.JavaAsn1Primitive"
    :pdfcube.type/asn1-object]
   "org.bouncycastle.asn1.ASN1Integer"
   ["global::System.Numerics.BigInteger" :pdfcube.type/asn1-integer]
   "org.bouncycastle.asn1.ASN1InputStream"
   ["global::DripSharp.Runtime.JavaAsn1InputStream"
    :pdfcube.type/asn1-input-stream]
   "org.bouncycastle.asn1.ASN1ObjectIdentifier"
   ["global::DripSharp.Runtime.JavaAsn1ObjectIdentifier"
    :pdfcube.type/asn1-object-identifier]
   "org.bouncycastle.asn1.ASN1Set"
   ["global::DripSharp.Runtime.JavaAsn1Set"
    :pdfcube.type/asn1-set]
   "org.bouncycastle.asn1.ASN1OctetString"
   ["global::DripSharp.Runtime.JavaDerOctetString"
    :pdfcube.type/asn1-octet-string]
   "org.bouncycastle.asn1.DEROctetString"
   ["global::DripSharp.Runtime.JavaDerOctetString"
    :pdfcube.type/der-octet-string]
   "org.bouncycastle.asn1.DERSet"
   ["global::DripSharp.Runtime.JavaDerSet"
    :pdfcube.type/der-set]
   "org.bouncycastle.asn1.cms.ContentInfo"
   ["global::DripSharp.Runtime.JavaCmsContentInfo"
    :pdfcube.type/cms-content-info]
   "org.bouncycastle.asn1.cms.OriginatorInfo"
   ["object" :pdfcube.type/originator-info]
   "org.bouncycastle.asn1.cms.EncryptedContentInfo"
   ["global::DripSharp.Runtime.JavaEncryptedContentInfo"
    :pdfcube.type/encrypted-content-info]
   "org.bouncycastle.asn1.cms.EnvelopedData"
   ["global::DripSharp.Runtime.JavaEnvelopedData"
    :pdfcube.type/enveloped-data]
   "org.bouncycastle.asn1.cms.IssuerAndSerialNumber"
   ["global::DripSharp.Runtime.JavaIssuerAndSerialNumber"
    :pdfcube.type/issuer-and-serial-number]
   "org.bouncycastle.asn1.cms.KeyTransRecipientInfo"
   ["global::DripSharp.Runtime.JavaKeyTransRecipientInfo"
    :pdfcube.type/key-transport-recipient-info]
   "org.bouncycastle.asn1.cms.RecipientIdentifier"
   ["global::DripSharp.Runtime.JavaRecipientIdentifier"
    :pdfcube.type/recipient-identifier]
   "org.bouncycastle.asn1.cms.RecipientInfo"
   ["global::DripSharp.Runtime.JavaRecipientInfo"
    :pdfcube.type/recipient-info]
   "org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers"
   ["global::DripSharp.Runtime.JavaPkcsObjectIdentifiers"
    :pdfcube.type/pkcs-object-identifiers]
   "org.bouncycastle.asn1.x509.AlgorithmIdentifier"
   ["global::DripSharp.Runtime.JavaAlgorithmIdentifier"
    :pdfcube.type/algorithm-identifier]
   "org.bouncycastle.asn1.x509.SubjectPublicKeyInfo"
   ["global::DripSharp.Runtime.JavaSubjectPublicKeyInfo"
    :pdfcube.type/subject-public-key-info]
   "org.bouncycastle.asn1.x509.TBSCertificate"
   ["global::DripSharp.Runtime.JavaTbsCertificate"
    :pdfcube.type/tbs-certificate]
   "org.bouncycastle.asn1.x500.X500Name"
   ["string" :pdfcube.type/x500-name]
   "org.bouncycastle.cms.CMSEnvelopedData"
   ["global::DripSharp.Runtime.JavaCmsEnvelopedData"
    :pdfcube.type/cms-enveloped-data]
   "org.bouncycastle.cms.CMSException"
   ["global::System.Security.Cryptography.CryptographicException"
    :pdfcube.type/cms-exception]
   "org.bouncycastle.cms.RecipientId"
   ["global::DripSharp.Runtime.JavaRecipientId"
    :pdfcube.type/recipient-id]
   "org.bouncycastle.cms.PKIXRecipientId"
   ["global::DripSharp.Runtime.JavaKeyTransRecipientId"
    :pdfcube.type/pkix-recipient-id]
   "org.bouncycastle.cms.KeyTransRecipientId"
   ["global::DripSharp.Runtime.JavaKeyTransRecipientId"
    :pdfcube.type/key-transport-recipient-id]
   "org.bouncycastle.cms.RecipientInformation"
   ["global::DripSharp.Runtime.JavaRecipientInformation"
    :pdfcube.type/recipient-information]
   "org.bouncycastle.cms.RecipientInformationStore"
   ["global::DripSharp.Runtime.JavaRecipientInformationStore"
    :pdfcube.type/recipient-information-store]
   "org.bouncycastle.cms.Recipient"
   ["global::DripSharp.Runtime.JavaJceKeyTransEnvelopedRecipient"
    :pdfcube.type/recipient]
   "org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient"
   ["global::DripSharp.Runtime.JavaJceKeyTransEnvelopedRecipient"
    :pdfcube.type/key-transport-enveloped-recipient]
   "org.bouncycastle.util.Selector"
   ["global::DripSharp.Runtime.JavaRecipientId"
    :pdfcube.type/selector]
   "org.bouncycastle.util.Arrays"
   ["global::System.Array" :pdfcube.type/array-utilities]
   "org.bouncycastle.jce.provider.BouncyCastleProvider"
   ["object" :pdfcube.type/security-provider]
   "java.security.KeyStore"
   ["global::System.Security.Cryptography.X509Certificates.X509Certificate2Collection"
    :pdfcube.type/x509-certificate-collection]
   "java.security.Provider"
   ["object" :pdfcube.type/security-provider]
   "java.awt.geom.AffineTransform"
   ["global::SkiaSharp.SKMatrix" :pdfcube.type/skia-matrix]
   "java.awt.Color"
   ["global::DripSharp.Runtime.JavaColor" :pdfcube.type/color]
   "java.awt.Point"
   ["global::DripSharp.Runtime.JavaPoint" :pdfcube.type/point-int]
   "java.awt.Image"
   ["global::SkiaSharp.SKBitmap" :pdfcube.type/skia-bitmap]
   "java.awt.Graphics"
   ["global::DripSharp.Runtime.PdfCubeGraphics2D" :pdfcube.type/graphics]
   "java.awt.Graphics2D"
   ["global::DripSharp.Runtime.PdfCubeGraphics2D" :pdfcube.type/graphics]
   "java.awt.print.Printable"
   ["global::DripSharp.Runtime.JavaPrintable" :pdfcube.type/printable]
   "java.awt.print.Pageable"
   ["global::DripSharp.Runtime.JavaPageable" :pdfcube.type/pageable]
   "java.awt.print.Book"
   ["global::DripSharp.Runtime.JavaBook" :pdfcube.type/print-book]
   "java.awt.print.PageFormat"
   ["global::DripSharp.Runtime.JavaPageFormat" :pdfcube.type/page-format]
   "java.awt.print.Paper"
   ["global::DripSharp.Runtime.JavaPaper" :pdfcube.type/paper]
   "java.awt.print.PrinterException"
   ["global::System.IO.IOException" :pdfcube.type/printer-exception]
   "java.awt.print.PrinterIOException"
   ["global::System.IO.IOException" :pdfcube.type/printer-io-exception]
   "java.awt.Shape"
   ["object" :pdfcube.type/shape]
   "java.awt.Composite"
   ["global::DripSharp.Runtime.JavaComposite" :pdfcube.type/composite]
   "java.awt.CompositeContext"
   ["global::DripSharp.Runtime.JavaCompositeContext"
    :pdfcube.type/composite-context]
   "java.awt.AlphaComposite"
   ["global::DripSharp.Runtime.JavaAlphaComposite"
    :pdfcube.type/alpha-composite]
   "java.awt.Stroke"
   ["global::DripSharp.Runtime.JavaStroke" :pdfcube.type/stroke]
   "java.awt.BasicStroke"
   ["global::DripSharp.Runtime.JavaBasicStroke" :pdfcube.type/basic-stroke]
   "java.awt.Font"
   ["global::DripSharp.Runtime.JavaFont" :pdfcube.type/font]
   "java.awt.FontMetrics"
   ["global::DripSharp.Runtime.JavaFontMetrics" :pdfcube.type/font-metrics]
   "java.awt.GraphicsConfiguration"
   ["global::DripSharp.Runtime.JavaGraphicsConfiguration"
    :pdfcube.type/graphics-configuration]
   "java.awt.GraphicsDevice"
   ["global::DripSharp.Runtime.JavaGraphicsDevice"
    :pdfcube.type/graphics-device]
   "java.awt.DisplayMode"
   ["global::DripSharp.Runtime.JavaDisplayMode"
    :pdfcube.type/display-mode]
   "java.awt.RenderingHints"
   ["global::DripSharp.Runtime.PdfCubeRenderingHints" :pdfcube.type/rendering-hints]
   "java.awt.Transparency"
   ["global::DripSharp.Runtime.PdfCubeTransparency" :pdfcube.type/transparency]
   "java.awt.RenderingHints$Key"
   ["object" :pdfcube.type/rendering-hint-key]
   "java.awt.color.ColorSpace"
   ["global::DripSharp.Runtime.JavaColorSpace" :pdfcube.type/color-space]
   "java.awt.color.ICC_ColorSpace"
   ["global::DripSharp.Runtime.JavaIccColorSpace" :pdfcube.type/icc-color-space]
   "java.awt.color.ICC_Profile"
   ["global::DripSharp.Runtime.JavaIccProfile" :pdfcube.type/icc-profile]
   "java.awt.image.ImageObserver"
   ["object" :pdfcube.type/image-observer]
   "java.awt.image.BufferedImage"
   ["global::SkiaSharp.SKBitmap" :pdfcube.type/skia-bitmap]
   "java.awt.image.Raster"
   ["global::DripSharp.Runtime.JavaRaster" :pdfcube.type/raster]
   "java.awt.image.WritableRaster"
   ["global::DripSharp.Runtime.JavaRaster" :pdfcube.type/raster]
   "java.awt.image.DataBuffer"
   ["global::DripSharp.Runtime.JavaDataBuffer" :pdfcube.type/data-buffer]
   "java.awt.image.DataBufferInt"
   ["global::DripSharp.Runtime.JavaDataBufferInt" :pdfcube.type/int-data-buffer]
   "java.awt.image.DataBufferByte"
   ["global::DripSharp.Runtime.JavaDataBufferByte" :pdfcube.type/byte-data-buffer]
   "java.awt.image.DataBufferUShort"
   ["global::DripSharp.Runtime.JavaDataBufferUShort" :pdfcube.type/ushort-data-buffer]
   "java.awt.image.ColorModel"
   ["global::DripSharp.Runtime.JavaColorModel" :pdfcube.type/color-model]
   "java.awt.image.ComponentColorModel"
   ["global::DripSharp.Runtime.JavaColorModel"
    :pdfcube.type/component-color-model]
   "java.awt.image.IndexColorModel"
   ["global::DripSharp.Runtime.JavaColorModel"
    :pdfcube.type/index-color-model]
   "java.awt.image.AffineTransformOp"
   ["global::DripSharp.Runtime.PdfCubeAffineTransformOp" :pdfcube.type/affine-transform-op]
   "java.awt.image.ColorConvertOp"
   ["global::DripSharp.Runtime.JavaColorConvertOp" :pdfcube.type/color-convert-operation]
   "java.awt.image.LookupTable"
   ["global::DripSharp.Runtime.JavaLookupTable" :pdfcube.type/lookup-table]
   "java.awt.image.ByteLookupTable"
   ["global::DripSharp.Runtime.JavaLookupTable" :pdfcube.type/byte-lookup-table]
   "java.awt.image.LookupOp"
   ["global::DripSharp.Runtime.JavaLookupOp" :pdfcube.type/lookup-operation]
   "java.awt.image.SampleModel"
   ["global::DripSharp.Runtime.JavaSampleModel" :pdfcube.type/sample-model]
   "java.awt.image.MultiPixelPackedSampleModel"
   ["global::DripSharp.Runtime.JavaMultiPixelPackedSampleModel"
    :pdfcube.type/multi-pixel-packed-sample-model]
   "java.awt.image.ImagingOpException"
   ["global::System.InvalidOperationException" :pdfcube.type/imaging-operation-exception]
   "javax.imageio.ImageIO"
   ["global::DripSharp.Runtime.PdfCubeImageIO" :pdfcube.type/image-io]
   "javax.imageio.ImageReader"
   ["global::DripSharp.Runtime.JavaImageReader" :pdfcube.type/image-reader]
   "javax.imageio.ImageReadParam"
   ["global::DripSharp.Runtime.JavaImageReadParam" :pdfcube.type/image-read-parameters]
   "javax.imageio.IIOParam"
   ["global::DripSharp.Runtime.JavaImageReadParam" :pdfcube.type/image-parameters]
   "javax.imageio.ImageWriter"
   ["global::DripSharp.Runtime.JavaImageWriter" :pdfcube.type/image-writer]
   "javax.imageio.ImageWriteParam"
   ["global::DripSharp.Runtime.JavaImageWriteParam" :pdfcube.type/image-write-parameters]
   "javax.imageio.plugins.jpeg.JPEGImageWriteParam"
   ["global::DripSharp.Runtime.JavaImageWriteParam" :pdfcube.type/jpeg-write-parameters]
   "javax.imageio.IIOImage"
   ["global::DripSharp.Runtime.JavaIioImage" :pdfcube.type/iio-image]
   "javax.imageio.ImageTypeSpecifier"
   ["global::DripSharp.Runtime.JavaImageTypeSpecifier"
    :pdfcube.type/image-type-specifier]
   "javax.imageio.IIOException"
   ["global::System.IO.IOException" :pdfcube.type/image-io-exception]
   "javax.imageio.metadata.IIOMetadata"
   ["global::DripSharp.Runtime.JavaImageMetadata" :pdfcube.type/image-metadata]
   "javax.imageio.metadata.IIOMetadataNode"
   ["global::System.Xml.XmlElement" :pdfcube.type/image-metadata-node]
   "javax.imageio.stream.ImageInputStream"
   ["global::DripSharp.Runtime.JavaImageInputStream" :pdfcube.type/image-input-stream]
   "javax.imageio.stream.MemoryCacheImageInputStream"
   ["global::DripSharp.Runtime.JavaImageInputStream" :pdfcube.type/image-input-stream]
   "javax.imageio.stream.ImageInputStreamImpl"
   ["global::DripSharp.Runtime.JavaImageInputStream" :pdfcube.type/image-input-stream]
   "javax.imageio.stream.ImageOutputStream"
   ["global::DripSharp.Runtime.JavaImageOutputStream" :pdfcube.type/image-output-stream]
   "javax.imageio.stream.MemoryCacheImageOutputStream"
   ["global::DripSharp.Runtime.JavaImageOutputStream" :pdfcube.type/image-output-stream]
   "javax.imageio.stream.ImageOutputStreamImpl"
   ["global::DripSharp.Runtime.JavaImageOutputStream" :pdfcube.type/image-output-stream]
   "java.awt.Rectangle"
   ["global::SkiaSharp.SKRectI" :pdfcube.type/skia-rectangle-int]
   "java.awt.Paint"
   ["global::DripSharp.Runtime.JavaPaint" :pdfcube.type/paint]
   "java.awt.PaintContext"
   ["global::DripSharp.Runtime.JavaPaintContext" :pdfcube.type/paint-context]
   "java.awt.TexturePaint"
   ["global::DripSharp.Runtime.JavaTexturePaint" :pdfcube.type/texture-paint]
   "java.awt.font.FontRenderContext"
   ["global::DripSharp.Runtime.JavaFontRenderContext"
    :pdfcube.type/font-render-context]
   "java.awt.font.GlyphVector"
   ["global::DripSharp.Runtime.JavaGlyphVector" :pdfcube.type/glyph-vector]
   "java.awt.image.BufferedImageOp"
   ["global::DripSharp.Runtime.JavaBufferedImageOperation"
    :pdfcube.type/buffered-image-operation]
   "java.awt.image.RenderedImage"
   ["global::SkiaSharp.SKBitmap" :pdfcube.type/rendered-image]
   "java.awt.image.renderable.RenderableImage"
   ["global::SkiaSharp.SKBitmap" :pdfcube.type/renderable-image]
   "java.text.AttributedCharacterIterator"
   ["global::DripSharp.Runtime.JavaAttributedCharacterIterator"
    :pdfcube.type/attributed-character-iterator]
   "java.text.AttributedCharacterIterator$Attribute"
   ["global::DripSharp.Runtime.JavaAttributedCharacterAttribute"
    :pdfcube.type/attributed-character-attribute]
   "java.text.AttributedString"
   ["global::DripSharp.Runtime.JavaAttributedString"
    :pdfcube.type/attributed-string]
   "java.text.BreakIterator"
   ["global::DripSharp.Runtime.JavaLineBreakIterator"
    :pdfcube.type/line-break-iterator]
   "java.awt.geom.GeneralPath"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.Path2D"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.Path2D$Float"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.Path2D$Double"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.Area"
   ["global::DripSharp.Runtime.JavaArea" :pdfcube.type/area]
   "java.awt.geom.PathIterator"
   ["global::DripSharp.Runtime.JavaPathIterator" :pdfcube.type/path-iterator]
   "java.awt.geom.Ellipse2D"
   ["global::DripSharp.Runtime.JavaEllipse" :pdfcube.type/ellipse]
   "java.awt.geom.RectangularShape"
   ["global::DripSharp.Runtime.JavaEllipse" :pdfcube.type/ellipse]
   "java.awt.geom.Ellipse2D$Double"
   ["global::DripSharp.Runtime.JavaEllipse" :pdfcube.type/ellipse]
   "java.awt.geom.Point2D"
   ["global::DripSharp.Runtime.JavaPoint2D" :pdfcube.type/point]
   "java.awt.geom.Point2D$Float"
   ["global::DripSharp.Runtime.JavaPoint2D" :pdfcube.type/point]
   "java.awt.geom.Point2D$Double"
   ["global::DripSharp.Runtime.JavaPoint2D" :pdfcube.type/point]
   "java.awt.geom.Rectangle2D"
   ["global::SkiaSharp.SKRect" :pdfcube.type/skia-rectangle]
   "java.awt.geom.Rectangle2D$Float"
   ["global::SkiaSharp.SKRect" :pdfcube.type/skia-rectangle]
   "java.awt.geom.Rectangle2D$Double"
   ["global::SkiaSharp.SKRect" :pdfcube.type/skia-rectangle]})

(defn- raw [text]
  (csharp/raw text))

(defn- sequence-node [nodes]
  (csharp/sequence-node (vec (remove nil? nodes))))

(defn- call-node [target member arguments]
  (sequence-node
   [target (raw (str "." member "("))
    (csharp/sequence-node arguments ", ")
    (raw ")")]))

(defn- font-compat-call [member arguments]
  (sequence-node
   [(raw (str "global::DripSharp.Runtime.PdfCubeFontCompat." member "("))
    (csharp/sequence-node arguments ", ")
    (raw ")")]))

(defn- font-discovery-call [member arguments]
  (sequence-node
   [(raw (str "global::DripSharp.Runtime.PdfCubeFontDiscovery." member "("))
    (csharp/sequence-node arguments ", ")
    (raw ")")]))

(defn- crypto-call [member arguments]
  (sequence-node
   [(raw (str "global::DripSharp.Runtime.PdfCubeCrypto." member "("))
    (csharp/sequence-node arguments ", ")
    (raw ")")]))

(defn- logger-message-node [message]
  (sequence-node
   [(raw "global::DripSharp.Runtime.JavaCompat.StringValueOf(")
    message (raw ")")]))

(defn- logger-call-node [method target arguments exception?]
  (let [message (first arguments)
        exception (when exception? (second arguments))]
    (sequence-node
     [(raw (str "global::Microsoft.Extensions.Logging.LoggerExtensions."
                method "("))
      target
      (when exception
        (sequence-node [(raw ", (global::System.Exception)") exception]))
      (raw ", ")
      (logger-message-node message)
      (raw ")")])))

(defn- logger-enabled-node [target level]
  (sequence-node
   [target
    (raw (str ".IsEnabled(global::Microsoft.Extensions.Logging.LogLevel."
              level ")"))]))

(def ^:private commons-invocation-adaptations
  {"executable:org.apache.commons.logging.LogFactory#getLog(java.lang.Class)"
   (fn [_target _arguments]
     (raw "global::Microsoft.Extensions.Logging.Abstractions.NullLogger.Instance"))

   "executable:org.apache.commons.logging.Log#debug(java.lang.Object)"
   (fn [target arguments]
     (logger-call-node "LogDebug" target arguments false))

   "executable:org.apache.commons.logging.Log#debug(java.lang.Object,java.lang.Throwable)"
   (fn [target arguments]
     (logger-call-node "LogDebug" target arguments true))

   "executable:org.apache.commons.logging.Log#error(java.lang.Object)"
   (fn [target arguments]
     (logger-call-node "LogError" target arguments false))

   "executable:org.apache.commons.logging.Log#error(java.lang.Object,java.lang.Throwable)"
   (fn [target arguments]
     (logger-call-node "LogError" target arguments true))

   "executable:org.apache.commons.logging.Log#info(java.lang.Object)"
   (fn [target arguments]
     (logger-call-node "LogInformation" target arguments false))

   "executable:org.apache.commons.logging.Log#trace(java.lang.Object)"
   (fn [target arguments]
     (logger-call-node "LogTrace" target arguments false))

   "executable:org.apache.commons.logging.Log#warn(java.lang.Object)"
   (fn [target arguments]
     (logger-call-node "LogWarning" target arguments false))

   "executable:org.apache.commons.logging.Log#warn(java.lang.Object,java.lang.Throwable)"
   (fn [target arguments]
     (logger-call-node "LogWarning" target arguments true))

   "executable:org.apache.commons.logging.Log#isDebugEnabled()"
   (fn [target _arguments]
     (logger-enabled-node target "Debug"))

   "executable:org.apache.commons.logging.Log#isTraceEnabled()"
   (fn [target _arguments]
     (logger-enabled-node target "Trace"))

   "executable:org.apache.commons.logging.Log#isWarnEnabled()"
   (fn [target _arguments]
     (logger-enabled-node target "Warning"))

   "executable:java.security.cert.Certificate#getEncoded()"
   (fn [target _arguments]
     (crypto-call "GetEncoded" [target]))

   "executable:org.bouncycastle.cms.CMSEnvelopedData#getRecipientInfos()"
   (fn [target arguments]
     (call-node target "GetRecipientInfos" arguments))

   "executable:org.bouncycastle.cms.RecipientInformationStore#getRecipients()"
   (fn [target arguments]
     (call-node target "GetRecipients" arguments))

   "executable:org.bouncycastle.cms.RecipientInformation#getRID()"
   (fn [target arguments]
     (call-node target "GetRid" arguments))

   "executable:org.bouncycastle.util.Selector#match(java.lang.Object)"
   (fn [target arguments]
     (call-node target "Match" arguments))

   "executable:org.bouncycastle.cms.RecipientInformation#getContent(org.bouncycastle.cms.Recipient)"
   (fn [target arguments]
     (call-node target "GetContent" arguments))

   "executable:org.bouncycastle.cms.PKIXRecipientId#getSerialNumber()"
   (fn [target arguments]
     (call-node target "GetSerialNumber" arguments))

   "executable:org.bouncycastle.cms.PKIXRecipientId#getIssuer()"
   (fn [target arguments]
     (call-node target "GetIssuer" arguments))

   "executable:java.security.cert.X509Certificate#getSerialNumber()"
   (fn [target _arguments]
     (crypto-call "GetSerialNumber" [target]))

   "executable:org.bouncycastle.cert.X509CertificateHolder#getIssuer()"
   (fn [target arguments]
     (call-node target "GetIssuer" arguments))

   "executable:java.security.cert.X509Certificate#getTBSCertificate()"
   (fn [target _arguments]
     (crypto-call "GetTbsCertificate" [target]))

   "executable:java.security.cert.X509Certificate#getPublicKey()"
   (fn [target _arguments]
     (crypto-call "GetPublicKey" [target]))

   "executable:java.security.cert.Certificate#getPublicKey()"
   (fn [target _arguments]
     (crypto-call "GetPublicKey" [target]))

   "executable:java.security.KeyStore#getDefaultType()"
   (fn [_target _arguments]
     (crypto-call "GetDefaultKeyStoreType" []))

   "executable:java.security.KeyStore#getInstance(java.lang.String)"
   (fn [_target arguments]
     (crypto-call "CreateKeyStore" arguments))

   "executable:java.security.KeyStore#load(java.io.InputStream,char[])"
   (fn [target arguments]
     (crypto-call "LoadKeyStore" (into [target] arguments)))

   "executable:java.security.KeyStore#size()"
   (fn [target _arguments]
     (crypto-call "KeyStoreSize" [target]))

   "executable:java.security.KeyStore#aliases()"
   (fn [target _arguments]
     (crypto-call "KeyStoreAliases" [target]))

   "executable:java.security.KeyStore#containsAlias(java.lang.String)"
   (fn [target arguments]
     (crypto-call "KeyStoreContainsAlias" (into [target] arguments)))

   "executable:java.security.KeyStore#getCertificate(java.lang.String)"
   (fn [target arguments]
     (crypto-call "KeyStoreGetCertificate" (into [target] arguments)))

   "executable:java.security.KeyStore#getKey(java.lang.String,char[])"
   (fn [target arguments]
     (crypto-call "KeyStoreGetKey" (into [target] arguments)))

   "executable:java.text.DateFormat#setTimeZone(java.util.TimeZone)"
   (fn [target arguments]
     (call-node target "SetTimeZone" arguments))

   "executable:java.text.DateFormat#format(java.util.Date)"
   (fn [target arguments]
     (call-node target "Format" arguments))

   "executable:java.text.DateFormat#setCalendar(java.util.Calendar)"
   (fn [target arguments]
     (call-node target "SetCalendar" arguments))

   "executable:java.text.DateFormat#parse(java.lang.String,java.text.ParsePosition)"
   (fn [target arguments]
     (call-node target "Parse" arguments))

   "executable:java.text.SimpleDateFormat#parse(java.lang.String,java.text.ParsePosition)"
   (fn [target arguments]
     (call-node target "Parse" arguments))

   "executable:java.text.ParsePosition#getIndex()"
   (fn [target arguments]
     (call-node target "GetIndex" arguments))

   "executable:java.text.ParsePosition#setIndex(int)"
   (fn [target arguments]
     (call-node target "SetIndex" arguments))

   "executable:java.text.ParsePosition#getErrorIndex()"
   (fn [target arguments]
     (call-node target "GetErrorIndex" arguments))

   "executable:java.text.ParsePosition#setErrorIndex(int)"
   (fn [target arguments]
     (call-node target "SetErrorIndex" arguments))

   "executable:java.text.AttributedString#addAttribute(java.text.AttributedCharacterIterator$Attribute,java.lang.Object)"
   (fn [target arguments]
     (call-node target "AddAttribute" arguments))

   "executable:java.text.AttributedString#getIterator()"
   (fn [target arguments]
     (call-node target "GetIterator" arguments))

   "executable:java.text.AttributedCharacterIterator#getAttribute(java.text.AttributedCharacterIterator$Attribute)"
   (fn [target arguments]
     (call-node target "GetAttribute" arguments))

   "executable:java.text.BreakIterator#getLineInstance()"
   (fn [_target _arguments]
     (raw "new global::DripSharp.Runtime.JavaLineBreakIterator()"))

   "executable:java.text.BreakIterator#setText(java.lang.String)"
   (fn [target arguments]
     (call-node target "SetText" arguments))

   "executable:java.text.BreakIterator#first()"
   (fn [target arguments]
     (call-node target "First" arguments))

   "executable:java.text.BreakIterator#next()"
   (fn [target arguments]
     (call-node target "Next" arguments))

   "executable:javax.imageio.metadata.IIOMetadataNode#getElementsByTagName(java.lang.String)"
   (fn [target arguments]
     (call-node target "GetElementsByTagName" arguments))

   "executable:javax.crypto.KeyGenerator#getInstance(java.lang.String)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaKeyGenerator.GetInstance(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.crypto.KeyGenerator#getInstance(java.lang.String,java.security.Provider)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaKeyGenerator.GetInstance(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.crypto.KeyGenerator#init(int)"
   (fn [target arguments]
     (call-node target "Init" arguments))

   "executable:javax.crypto.KeyGenerator#init(int,java.security.SecureRandom)"
   (fn [target arguments]
     (call-node target "Init" arguments))

   "executable:javax.crypto.KeyGenerator#generateKey()"
   (fn [target arguments]
     (call-node target "GenerateKey" arguments))

   "executable:javax.crypto.SecretKey#getEncoded()"
   (fn [target arguments]
     (call-node target "GetEncoded" arguments))

   "executable:java.security.Key#getEncoded()"
   (fn [target arguments]
     (call-node target "GetEncoded" arguments))

   "executable:java.security.AlgorithmParameterGenerator#getInstance(java.lang.String,java.security.Provider)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaAlgorithmParameterGenerator.GetInstance(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.security.AlgorithmParameterGenerator#generateParameters()"
   (fn [target arguments]
     (call-node target "GenerateParameters" arguments))

   "executable:java.security.AlgorithmParameters#getEncoded(java.lang.String)"
   (fn [target arguments]
     (call-node target "GetEncoded" arguments))

   "executable:javax.crypto.Cipher#getInstance(java.lang.String,java.security.Provider)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaCipher.GetInstance(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.crypto.Cipher#init(int,java.security.Key,java.security.AlgorithmParameters)"
   (fn [target arguments]
     (call-node target "Init" arguments))

   "executable:org.bouncycastle.asn1.ASN1InputStream#readObject()"
   (fn [target arguments]
     (call-node target "ReadObject" arguments))

   "executable:org.bouncycastle.asn1.ASN1Primitive#encodeTo(java.io.OutputStream,java.lang.String)"
   (fn [target arguments]
     (call-node target "EncodeTo" arguments))

   "executable:org.bouncycastle.asn1.ASN1ObjectIdentifier#getId()"
   (fn [target arguments]
     (call-node target "GetId" arguments))

   "executable:org.bouncycastle.asn1.x509.TBSCertificate#getInstance(java.lang.Object)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaTbsCertificate.GetInstance(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.x509.TBSCertificate#getSubjectPublicKeyInfo()"
   (fn [target arguments]
     (call-node target "GetSubjectPublicKeyInfo" arguments))

   "executable:org.bouncycastle.asn1.x509.TBSCertificate#getIssuer()"
   (fn [target arguments]
     (call-node target "GetIssuer" arguments))

   "executable:org.bouncycastle.asn1.x509.TBSCertificate#getSerialNumber()"
   (fn [target arguments]
     (call-node target "GetSerialNumber" arguments))

   "executable:org.bouncycastle.asn1.x509.SubjectPublicKeyInfo#getAlgorithm()"
   (fn [target arguments]
     (call-node target "GetAlgorithm" arguments))

   "executable:org.bouncycastle.asn1.x509.AlgorithmIdentifier#getAlgorithm()"
   (fn [target arguments]
     (call-node target "GetAlgorithm" arguments))

   "executable:org.bouncycastle.asn1.ASN1Integer#getValue()"
   (fn [target _arguments]
     target)

   "executable:org.bouncycastle.asn1.cms.ContentInfo#toASN1Primitive()"
   (fn [target arguments]
     (call-node target "ToAsn1Primitive" arguments))

   "executable:org.bouncycastle.util.Arrays#copyOf(byte[],int)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaCompat.CopyOf(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.geom.AffineTransform#getTranslateInstance(double,double)"
   (fn [_target arguments]
     (font-compat-call "Translation" arguments))

   "executable:java.awt.geom.AffineTransform#getScaleInstance(double,double)"
   (fn [_target arguments]
     (font-compat-call "Scale" arguments))

   "executable:java.awt.geom.AffineTransform#getDeterminant()"
   (fn [target _arguments]
     (font-compat-call "Determinant" [target]))

   "executable:java.awt.geom.AffineTransform#createTransformedShape(java.awt.Shape)"
   (fn [target arguments]
     (font-compat-call "CreateTransformedShape"
                       (into [target] arguments)))

   "executable:java.awt.Shape#getBounds2D()"
   (fn [target _arguments]
     (font-compat-call "ShapeBounds" [target]))

   "executable:java.awt.geom.AffineTransform#getMatrix(double[])"
   (fn [target arguments]
     (font-compat-call "GetMatrix" (into [target] arguments)))

   "executable:java.awt.geom.AffineTransform#getType()"
   (fn [target _arguments]
     (font-compat-call "GetTransformType" [target]))

   "executable:java.awt.geom.AffineTransform#hashCode()"
   (fn [target _arguments]
     (call-node target "GetHashCode" []))

   "executable:java.awt.geom.AffineTransform#getScaleX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".ScaleX")]))

   "executable:java.awt.geom.AffineTransform#getScaleY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".ScaleY")]))

   "executable:java.awt.geom.AffineTransform#getShearX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".SkewX")]))

   "executable:java.awt.geom.AffineTransform#getShearY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".SkewY")]))

   "executable:java.awt.geom.AffineTransform#getTranslateX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".TransX")]))

   "executable:java.awt.geom.AffineTransform#getTranslateY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".TransY")]))

   "executable:java.awt.geom.AffineTransform#isIdentity()"
   (fn [target _arguments]
     (font-compat-call "IsIdentity" [target]))

   "executable:java.awt.geom.AffineTransform#scale(double,double)"
   (fn [target arguments]
     (font-compat-call
      "ScaleInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#translate(double,double)"
   (fn [target arguments]
     (font-compat-call
      "TranslateInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#quadrantRotate(int)"
   (fn [target arguments]
     (font-compat-call
      "QuadrantRotateInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#rotate(double)"
   (fn [target arguments]
     (font-compat-call
      "RotateInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#rotate(double,double,double)"
   (fn [target arguments]
     (font-compat-call
      "RotateInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#shear(double,double)"
   (fn [target arguments]
     (font-compat-call
      "ShearInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#concatenate(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call
      "ConcatenateInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#preConcatenate(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call
      "PreConcatenateInPlace"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.AffineTransform#createInverse()"
   (fn [target _arguments]
     (font-compat-call "CreateInverse" [target]))

   "executable:java.awt.geom.AffineTransform#clone()"
   (fn [target _arguments]
     target)

   "executable:java.awt.geom.AffineTransform#transform(float[],int,float[],int,int)"
   (fn [target arguments]
     (font-compat-call "TransformPoints" (into [target] arguments)))

   "executable:java.awt.geom.AffineTransform#transform(java.awt.geom.Point2D,java.awt.geom.Point2D)"
   (fn [target arguments]
     (font-compat-call "TransformPoint" (into [target] arguments)))

   "executable:java.awt.Color#getRed()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Red")]))

   "executable:java.awt.Color#getGreen()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Green")]))

   "executable:java.awt.Color#getBlue()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Blue")]))

   "executable:java.awt.Rectangle#getWidth()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Width")]))

   "executable:java.awt.Rectangle#getHeight()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Height")]))

   "executable:java.awt.Rectangle#getMinX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Left")]))

   "executable:java.awt.Rectangle#getMinY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Top")]))

   "executable:java.awt.Rectangle#getMaxX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Right")]))

   "executable:java.awt.Rectangle#getMaxY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Bottom")]))

   "executable:java.awt.Color#getRGBColorComponents(float[])"
   (fn [target arguments]
     (font-compat-call "GetRgbColorComponents" (into [target] arguments)))

   "executable:java.awt.Color#createContext(java.awt.image.ColorModel,java.awt.Rectangle,java.awt.geom.Rectangle2D,java.awt.geom.AffineTransform,java.awt.RenderingHints)"
   (fn [target arguments]
     (call-node target "CreateContext" arguments))

   "executable:java.awt.Color#getTransparency()"
   (fn [target arguments]
     (call-node target "GetTransparency" arguments))

   "executable:java.lang.Character#getDirectionality(int)"
   (fn [_target arguments]
     (font-compat-call "CharacterDirectionality" arguments))

   "executable:javax.imageio.ImageIO#read(java.io.File)"
   (fn [_target arguments]
     (font-compat-call "ReadImage" arguments))

   "executable:javax.imageio.ImageIO#read(java.io.InputStream)"
   (fn [_target arguments]
     (font-compat-call "ReadImage" arguments))

   "executable:javax.imageio.ImageIO#setUseCache(boolean)"
   (fn [_target arguments]
     (font-compat-call "SetImageIoUseCache" arguments))

   "executable:javax.imageio.ImageIO#createImageInputStream(java.lang.Object)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.PdfCubeImageIO.CreateImageInputStream(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.ImageIO#getImageReadersByFormatName(java.lang.String)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.PdfCubeImageIO.GetImageReadersByFormatName(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.ImageIO#createImageOutputStream(java.lang.Object)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.PdfCubeImageIO.CreateImageOutputStream(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.ImageIO#getImageWritersBySuffix(java.lang.String)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.PdfCubeImageIO.GetImageWritersBySuffix(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.ImageReader#canReadRaster()"
   (fn [target _arguments]
     (sequence-node [target (raw ".CanReadRaster")]))

   "executable:javax.imageio.ImageReader#setInput(java.lang.Object)"
   (fn [target arguments]
     (call-node target "SetInput" arguments))

   "executable:javax.imageio.ImageReader#setInput(java.lang.Object,boolean,boolean)"
   (fn [target arguments]
     (call-node target "SetInput" arguments))

   "executable:javax.imageio.ImageReader#getDefaultReadParam()"
   (fn [target _arguments]
     (call-node target "GetDefaultReadParam" []))

   "executable:javax.imageio.ImageReader#getWidth(int)"
   (fn [target arguments]
     (call-node target "GetWidth" arguments))

   "executable:javax.imageio.ImageReader#getHeight(int)"
   (fn [target arguments]
     (call-node target "GetHeight" arguments))

   "executable:javax.imageio.ImageReader#read(int,javax.imageio.ImageReadParam)"
   (fn [target arguments]
     (call-node target "Read" arguments))

   "executable:javax.imageio.ImageReader#readRaster(int,javax.imageio.ImageReadParam)"
   (fn [target arguments]
     (call-node target "ReadRaster" arguments))

   "executable:javax.imageio.ImageReader#getImageMetadata(int)"
   (fn [target arguments]
     (call-node target "GetImageMetadata" arguments))

   "executable:javax.imageio.ImageReader#dispose()"
   (fn [target _arguments]
     (call-node target "Dispose" []))

   "executable:javax.imageio.ImageWriter#getDefaultWriteParam()"
   (fn [target _arguments]
     (call-node target "GetDefaultWriteParam" []))

   "executable:javax.imageio.ImageWriter#setOutput(java.lang.Object)"
   (fn [target arguments]
     (call-node target "SetOutput" arguments))

   "executable:javax.imageio.ImageWriter#getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier,javax.imageio.ImageWriteParam)"
   (fn [target arguments]
     (call-node target "GetDefaultImageMetadata" arguments))

   "executable:javax.imageio.ImageWriter#write(javax.imageio.metadata.IIOMetadata,javax.imageio.IIOImage,javax.imageio.ImageWriteParam)"
   (fn [target arguments]
     (call-node target "Write" arguments))

   "executable:javax.imageio.ImageWriter#dispose()"
   (fn [target _arguments]
     (call-node target "Dispose" []))

   "executable:javax.imageio.ImageWriteParam#setCompressionMode(int)"
   (fn [target arguments]
     (call-node target "SetCompressionMode" arguments))

   "executable:javax.imageio.ImageWriteParam#setCompressionQuality(float)"
   (fn [target arguments]
     (call-node target "SetCompressionQuality" arguments))

   "executable:javax.imageio.ImageReadParam#setSourceSubsampling(int,int,int,int)"
   (fn [target arguments]
     (call-node target "SetSourceSubsampling" arguments))

   "executable:javax.imageio.ImageReadParam#setSourceRegion(java.awt.Rectangle)"
   (fn [target arguments]
     (call-node target "SetSourceRegion" arguments))

   "executable:javax.imageio.IIOParam#setSourceSubsampling(int,int,int,int)"
   (fn [target arguments]
     (call-node target "SetSourceSubsampling" arguments))

   "executable:javax.imageio.IIOParam#setSourceRegion(java.awt.Rectangle)"
   (fn [target arguments]
     (call-node target "SetSourceRegion" arguments))

   "executable:javax.imageio.metadata.IIOMetadata#getAsTree(java.lang.String)"
   (fn [target arguments]
     (call-node target "GetAsTree" arguments))

   "executable:java.awt.image.BufferedImage#getWidth()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Width")]))

   "executable:java.awt.image.BufferedImage#getHeight()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Height")]))

   "executable:java.awt.image.BufferedImage#getType()"
   (fn [target _arguments]
     (font-compat-call "GetImageType" [target]))

   "executable:java.awt.image.BufferedImage#getRaster()"
   (fn [target _arguments]
     (font-compat-call "GetRaster" [target]))

   "executable:java.awt.image.BufferedImage#getData()"
   (fn [target _arguments]
     (font-compat-call "GetImageData" [target]))

   "executable:java.awt.image.BufferedImage#getTransparency()"
   (fn [target _arguments]
     (font-compat-call "GetTransparency" [target]))

   "executable:java.awt.image.BufferedImage#getColorModel()"
   (fn [target _arguments]
     (font-compat-call "GetColorModel" [target]))

   "executable:java.awt.image.BufferedImage#setData(java.awt.image.Raster)"
   (fn [target arguments]
     (font-compat-call "SetImageData" (into [target] arguments)))

   "executable:java.awt.image.BufferedImage#getAlphaRaster()"
   (fn [target _arguments]
     (font-compat-call "GetAlphaRaster" [target]))

   "executable:java.awt.image.BufferedImage#getSampleModel()"
   (fn [target _arguments]
     (font-compat-call "GetSampleModel" [target]))

   "executable:java.awt.image.BufferedImage#getGraphics()"
   (fn [target _arguments]
     (font-compat-call "CreateGraphics" [target]))

   "executable:java.awt.image.BufferedImage#getRGB(int,int)"
   (fn [target arguments]
     (font-compat-call "GetRgb" (into [target] arguments)))

   "executable:java.awt.image.BufferedImage#getRGB(int,int,int,int,int[],int,int)"
   (fn [target arguments]
     (font-compat-call "GetRgb" (into [target] arguments)))

   "executable:java.awt.image.BufferedImage#setRGB(int,int,int)"
   (fn [target arguments]
     (font-compat-call "SetRgb" (into [target] arguments)))

   "executable:java.awt.Image#getScaledInstance(int,int,int)"
   (fn [target arguments]
     (font-compat-call "ScaleImage" (into [target] arguments)))

   "executable:java.awt.image.ColorModel#getPixelSize()"
   (fn [target _arguments]
     (sequence-node [target (raw ".PixelSize")]))

   "executable:java.awt.image.ColorModel#getNumComponents()"
   (fn [target _arguments]
     (sequence-node [target (raw ".NumberOfComponents")]))

   "executable:java.awt.image.ColorModel#createCompatibleWritableRaster(int,int)"
   (fn [target arguments]
     (call-node target "CreateCompatibleWritableRaster" arguments))

   "executable:java.awt.image.ColorModel#getNumColorComponents()"
   (fn [target _arguments]
     (sequence-node [target (raw ".NumberOfColorComponents")]))

   "executable:java.awt.image.ColorModel#hasAlpha()"
   (fn [target _arguments]
     (sequence-node [target (raw ".HasAlpha")]))

   "executable:java.awt.image.ColorModel#getColorSpace()"
   (fn [target _arguments]
     (sequence-node [target (raw ".ColorSpace")]))

   "executable:java.awt.image.ColorModel#getNormalizedComponents(java.lang.Object,float[],int)"
   (fn [target arguments]
     (call-node target "GetNormalizedComponents" arguments))

   "executable:java.awt.image.ColorModel#getDataElements(float[],int,java.lang.Object)"
   (fn [target arguments]
     (call-node target "GetDataElements" arguments))

   "executable:java.awt.image.ColorModel#getRed(java.lang.Object)"
   (fn [target arguments]
     (call-node target "GetRed" arguments))

   "executable:java.awt.image.ColorModel#getGreen(java.lang.Object)"
   (fn [target arguments]
     (call-node target "GetGreen" arguments))

   "executable:java.awt.image.ColorModel#getBlue(java.lang.Object)"
   (fn [target arguments]
     (call-node target "GetBlue" arguments))

   "executable:java.awt.image.ColorModel#getAlpha(java.lang.Object)"
   (fn [target arguments]
     (call-node target "GetAlpha" arguments))

   "executable:java.awt.image.Raster#getDataBuffer()"
   (fn [target _arguments]
     (call-node target "GetDataBuffer" []))

   "executable:java.awt.image.Raster#getTransferType()"
   (fn [target _arguments]
     (sequence-node [target (raw ".TransferType")]))

   "executable:java.awt.image.Raster#getWidth()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Width")]))

   "executable:java.awt.image.Raster#getHeight()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Height")]))

   "executable:java.awt.image.Raster#getMinX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".MinX")]))

   "executable:java.awt.image.Raster#getMinY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".MinY")]))

   "executable:java.awt.image.Raster#getNumBands()"
   (fn [target _arguments]
     (sequence-node [target (raw ".NumberOfBands")]))

   "executable:java.awt.image.Raster#getNumDataElements()"
   (fn [target _arguments]
     (sequence-node [target (raw ".NumberOfBands")]))

   "executable:java.awt.image.DataBuffer#getSize()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Size")]))

   "executable:java.awt.image.DataBuffer#getDataType()"
   (fn [target _arguments]
     (sequence-node [target (raw ".DataType")]))

   "executable:java.awt.image.DataBuffer#getElem(int)"
   (fn [target arguments]
     (call-node target "GetElement" arguments))

   "executable:java.awt.image.DataBuffer#setElem(int,int)"
   (fn [target arguments]
     (call-node target "SetElement" arguments))

   "executable:java.awt.image.DataBufferInt#getData()"
   (fn [target _arguments]
     (call-node target "GetData" []))

   "executable:java.awt.image.DataBufferByte#getData()"
   (fn [target _arguments]
     (call-node target "GetData" []))

   "executable:java.awt.image.DataBufferUShort#getData()"
   (fn [target _arguments]
     (call-node target "GetData" []))

   "executable:java.awt.image.Raster#getSamples(int,int,int,int,int,int[])"
   (fn [target arguments]
     (call-node target "GetSamples" arguments))

   "executable:java.awt.image.WritableRaster#setSamples(int,int,int,int,int,int[])"
   (fn [target arguments]
     (call-node target "SetSamples" arguments))

   "executable:java.awt.image.Raster#getPixels(int,int,int,int,int[])"
   (fn [target arguments]
     (call-node target "GetPixels" arguments))

   "executable:java.awt.image.Raster#getPixel(int,int,int[])"
   (fn [target arguments]
     (call-node target "GetPixel" arguments))

   "executable:java.awt.image.Raster#getPixel(int,int,float[])"
   (fn [target arguments]
     (call-node target "GetPixel" arguments))

   "executable:java.awt.image.Raster#getDataElements(int,int,int,int,java.lang.Object)"
   (fn [target arguments]
     (call-node target "GetDataElements" arguments))

   "executable:java.awt.image.Raster#getDataElements(int,int,java.lang.Object)"
   (fn [target arguments]
     (call-node target "GetDataElements" arguments))

   "executable:java.awt.image.WritableRaster#setDataElements(int,int,java.lang.Object)"
   (fn [target arguments]
     (call-node target "SetDataElements" arguments))

   "executable:java.awt.image.WritableRaster#setPixels(int,int,int,int,int[])"
   (fn [target arguments]
     (call-node target "SetPixels" arguments))

   "executable:java.awt.image.WritableRaster#setPixel(int,int,int[])"
   (fn [target arguments]
     (call-node target "SetPixel" arguments))

   "executable:java.awt.image.WritableRaster#setPixel(int,int,float[])"
   (fn [target arguments]
     (call-node target "SetPixel" arguments))

   "executable:java.awt.image.AffineTransformOp#filter(java.awt.image.BufferedImage,java.awt.image.BufferedImage)"
   (fn [target arguments]
     (call-node target "Filter" arguments))

   "executable:java.awt.image.ColorConvertOp#filter(java.awt.image.BufferedImage,java.awt.image.BufferedImage)"
   (fn [target arguments]
     (call-node target "Filter" arguments))

   "executable:java.awt.image.LookupOp#filter(java.awt.image.BufferedImage,java.awt.image.BufferedImage)"
   (fn [target arguments]
     (call-node target "Filter" arguments))

   "executable:java.awt.image.BufferedImage#createGraphics()"
   (fn [target _arguments]
     (font-compat-call "CreateGraphics" [target]))

   "executable:java.awt.Graphics2D#setRenderingHint(java.awt.RenderingHints$Key,java.lang.Object)"
   (fn [target arguments]
     (call-node target "SetRenderingHint" arguments))

   "executable:java.awt.PaintContext#getColorModel()"
   (fn [target _arguments]
     (call-node target "GetColorModel" []))

   "executable:java.awt.PaintContext#getRaster(int,int,int,int)"
   (fn [target arguments]
     (call-node target "GetRaster" arguments))

   "executable:java.awt.PaintContext#dispose()"
   (fn [target _arguments]
     (call-node target "Dispose" []))

   "executable:java.awt.Paint#createContext(java.awt.image.ColorModel,java.awt.Rectangle,java.awt.geom.Rectangle2D,java.awt.geom.AffineTransform,java.awt.RenderingHints)"
   (fn [target arguments]
     (call-node target "CreateContext" arguments))

   "executable:java.awt.Paint#getTransparency()"
   (fn [target arguments]
     (call-node target "GetTransparency" arguments))

   "executable:java.awt.RenderingHints#put(java.lang.Object,java.lang.Object)"
   (fn [target arguments]
     (call-node target "Put" arguments))

   "executable:java.awt.Graphics#drawImage(java.awt.Image,int,int,int,int,int,int,int,int,java.awt.image.ImageObserver)"
   (fn [target arguments]
     (call-node target "DrawImage" arguments))

   "executable:java.awt.Graphics#dispose()"
   (fn [target _arguments]
     (call-node target "Dispose" []))

   "executable:javax.imageio.stream.ImageInputStream#read()"
   (fn [target _arguments]
     (call-node target "Read" []))

   "executable:javax.imageio.stream.ImageInputStreamImpl#read()"
   (fn [target _arguments]
     (call-node target "Read" []))

   "executable:javax.imageio.stream.ImageInputStream#read(byte[])"
   (fn [target arguments]
     (call-node target "Read" arguments))

   "executable:javax.imageio.stream.ImageInputStream#readBits(int)"
   (fn [target arguments]
     (call-node target "ReadBits" arguments))

   "executable:javax.imageio.stream.ImageInputStreamImpl#readBits(int)"
   (fn [target arguments]
     (call-node target "ReadBits" arguments))

   "executable:javax.imageio.stream.ImageInputStream#readUnsignedShort()"
   (fn [target _arguments]
     (call-node target "ReadUnsignedShort" []))

   "executable:javax.imageio.stream.ImageInputStream#getBitOffset()"
   (fn [target _arguments]
     (sequence-node [target (raw ".BitOffset")]))

   "executable:javax.imageio.stream.ImageInputStreamImpl#getBitOffset()"
   (fn [target _arguments]
     (sequence-node [target (raw ".BitOffset")]))

   "executable:javax.imageio.stream.ImageInputStream#getStreamPosition()"
   (fn [target _arguments]
     (sequence-node [target (raw ".StreamPosition")]))

   "executable:javax.imageio.stream.ImageInputStream#seek(long)"
   (fn [target arguments]
     (call-node target "Seek" arguments))

   "executable:javax.imageio.stream.ImageOutputStream#writeBits(long,int)"
   (fn [target arguments]
     (call-node target "WriteBits" arguments))

   "executable:javax.imageio.stream.ImageOutputStreamImpl#writeBits(long,int)"
   (fn [target arguments]
     (call-node target "WriteBits" arguments))

   "executable:javax.imageio.stream.ImageOutputStream#getBitOffset()"
   (fn [target _arguments]
     (sequence-node [target (raw ".BitOffset")]))

   "executable:javax.imageio.stream.ImageOutputStream#flush()"
   (fn [target _arguments]
     (call-node target "Flush" []))

   "executable:javax.imageio.stream.ImageInputStreamImpl#flush()"
   (fn [target _arguments]
     (call-node target "Flush" []))

   "executable:java.awt.color.ICC_Profile#getInstance(int)"
   (fn [_target arguments]
     (font-compat-call "GetIccProfile" arguments))

   "executable:java.awt.color.ICC_Profile#getInstance(java.io.InputStream)"
   (fn [_target arguments]
     (font-compat-call "GetIccProfile" arguments))

   "executable:java.awt.color.ICC_Profile#getInstance(byte[])"
   (fn [_target arguments]
     (font-compat-call "GetIccProfile" arguments))

   "executable:java.awt.color.ICC_Profile#getData()"
   (fn [target _arguments]
     (call-node target "GetData" []))

   "executable:java.awt.color.ICC_Profile#getData(int)"
   (fn [target arguments]
     (call-node target "GetData" arguments))

   "executable:java.awt.color.ICC_Profile#getProfileClass()"
   (fn [target arguments]
     (call-node target "GetProfileClass" arguments))

   "executable:java.awt.color.ICC_Profile#getColorSpaceType()"
   (fn [target arguments]
     (call-node target "GetColorSpaceType" arguments))

   "executable:java.awt.color.ICC_Profile#getMajorVersion()"
   (fn [target arguments]
     (call-node target "GetMajorVersion" arguments))

   "executable:java.awt.color.ICC_Profile#getMinorVersion()"
   (fn [target arguments]
     (call-node target "GetMinorVersion" arguments))

   "executable:java.awt.color.ICC_Profile#getNumComponents()"
   (fn [target _arguments]
     (sequence-node [target (raw ".NumberOfComponents")]))

   "executable:java.awt.color.ColorSpace#getInstance(int)"
   (fn [_target arguments]
     (font-compat-call "GetColorSpace" arguments))

   "executable:java.awt.color.ColorSpace#getType()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Type")]))

   "executable:java.awt.color.ColorSpace#getNumComponents()"
   (fn [target _arguments]
     (sequence-node [target (raw ".NumberOfComponents")]))

   "executable:java.awt.color.ColorSpace#toRGB(float[])"
   (fn [target arguments]
     (call-node target "ToRgb" arguments))

   "executable:java.awt.color.ColorSpace#fromRGB(float[])"
   (fn [target arguments]
     (call-node target "FromRgb" arguments))

   "executable:java.awt.color.ColorSpace#toCIEXYZ(float[])"
   (fn [target arguments]
     (call-node target "ToCieXyz" arguments))

   "executable:java.awt.color.ColorSpace#fromCIEXYZ(float[])"
   (fn [target arguments]
     (call-node target "FromCieXyz" arguments))

   "executable:java.awt.color.ColorSpace#getMinValue(int)"
   (fn [target arguments]
     (call-node target "GetMinValue" arguments))

   "executable:java.awt.color.ColorSpace#getMaxValue(int)"
   (fn [target arguments]
     (call-node target "GetMaxValue" arguments))

   "executable:java.awt.color.ColorSpace#isCS_sRGB()"
   (fn [target _arguments]
     (sequence-node [target (raw ".IsSrgb")]))

   "executable:java.awt.color.ICC_ColorSpace#getProfile()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Profile")]))

   "executable:java.awt.color.ICC_ColorSpace#toRGB(float[])"
   (fn [target arguments]
     (call-node target "ToRgb" arguments))

   "executable:java.awt.color.ICC_ColorSpace#getMinValue(int)"
   (fn [target arguments]
     (call-node target "GetMinValue" arguments))

   "executable:java.awt.color.ICC_ColorSpace#getMaxValue(int)"
   (fn [target arguments]
     (call-node target "GetMaxValue" arguments))

   "executable:java.awt.image.Raster#createBandedRaster(int,int,int,int,java.awt.Point)"
   (fn [_target arguments]
     (font-compat-call "CreateBandedRaster" arguments))

   "executable:java.awt.image.Raster#createInterleavedRaster(int,int,int,int,java.awt.Point)"
   (fn [_target arguments]
     (font-compat-call "CreateInterleavedRaster" arguments))

   "executable:java.awt.image.Raster#createInterleavedRaster(java.awt.image.DataBuffer,int,int,int,int,int[],java.awt.Point)"
   (fn [_target arguments]
     (font-compat-call "CreateInterleavedRaster" arguments))

   "executable:java.awt.image.Raster#createCompatibleWritableRaster()"
   (fn [target arguments]
     (call-node target "CreateCompatibleWritableRaster" arguments))

   "executable:java.awt.geom.Path2D#closePath()"
   (fn [target _arguments]
     (font-compat-call "Close" [target]))

   "executable:java.awt.geom.Path2D#reset()"
   (fn [target _arguments]
     (call-node target "Reset" []))

   "executable:java.awt.geom.Path2D#setWindingRule(int)"
   (fn [target arguments]
     (font-compat-call "SetWindingRule" (into [target] arguments)))

   "executable:java.awt.geom.Path2D#transform(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call "TransformPath" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#transform(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call "TransformPath" (into [target] arguments)))

   "executable:java.awt.geom.GeneralPath#transform(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call "TransformPath" (into [target] arguments)))

   "executable:java.awt.geom.Area#intersect(java.awt.geom.Area)"
   (fn [target arguments]
     (call-node target "Intersect" arguments))

   "executable:java.awt.geom.Area#isEmpty()"
   (fn [target _arguments]
     (sequence-node [target (raw ".IsEmpty")]))

   "executable:java.awt.geom.Area#reset()"
   (fn [target _arguments]
     (call-node target "Reset" []))

   "executable:java.awt.geom.Area#getPathIterator(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (call-node target "GetPathIterator" arguments))

   "executable:java.awt.Shape#getPathIterator(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call "ShapePathIterator" (into [target] arguments)))

   "executable:java.awt.geom.Area#getBounds2D()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Bounds")]))

   "executable:java.awt.geom.Path2D#getBounds()"
   (fn [target _arguments]
     (font-compat-call "PathBounds" [target]))

   "executable:java.awt.geom.Path2D#clone()"
   (fn [target _arguments]
     (sequence-node [(raw "new global::SkiaSharp.SKPath(") target (raw ")")]))

   "executable:java.awt.geom.Path2D$Float#clone()"
   (fn [target _arguments]
     (sequence-node [(raw "new global::SkiaSharp.SKPath(") target (raw ")")]))

   "executable:java.awt.geom.Path2D#getCurrentPoint()"
   (fn [target _arguments]
     (font-compat-call "CurrentPoint" [target]))

   "executable:java.awt.geom.Path2D#append(java.awt.Shape,boolean)"
   (fn [target arguments]
     (font-compat-call "AppendPath" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#append(java.awt.geom.PathIterator,boolean)"
   (fn [target arguments]
     (font-compat-call "AppendPath" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#curveTo(float,float,float,float,float,float)"
   (fn [target arguments]
     (font-compat-call "CurveTo" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#getBounds2D()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Bounds")]))

   "executable:java.awt.geom.Path2D$Float#getPathIterator(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call "PathIterator" (into [target] arguments)))

   "executable:java.awt.geom.Ellipse2D$Double#getPathIterator(java.awt.geom.AffineTransform,double)"
   (fn [target arguments]
     (call-node target "GetPathIterator" arguments))

   "executable:java.awt.geom.RectangularShape#getPathIterator(java.awt.geom.AffineTransform,double)"
   (fn [target arguments]
     (call-node target "GetPathIterator" arguments))

   "executable:java.awt.geom.RectangularShape#getWidth()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Width")]))

   "executable:java.awt.geom.RectangularShape#getHeight()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Height")]))

   "executable:java.awt.geom.RectangularShape#getMinX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Left")]))

   "executable:java.awt.geom.RectangularShape#getMinY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Top")]))

   "executable:java.awt.geom.RectangularShape#getMaxX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Right")]))

   "executable:java.awt.geom.RectangularShape#getMaxY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Bottom")]))

   "executable:java.awt.geom.RectangularShape#getX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Left")]))

   "executable:java.awt.geom.RectangularShape#getY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Top")]))

   "executable:java.awt.geom.RectangularShape#getCenterX()"
   (fn [target _arguments]
     (font-compat-call "ShapeCenterX" [target]))

   "executable:java.awt.geom.RectangularShape#getCenterY()"
   (fn [target _arguments]
     (font-compat-call "ShapeCenterY" [target]))

   "executable:java.awt.geom.RectangularShape#isEmpty()"
   (fn [target _arguments]
     (sequence-node [target (raw ".IsEmpty")]))

   "executable:java.awt.geom.Rectangle2D#add(java.awt.geom.Point2D)"
   (fn [target arguments]
     (font-compat-call
      "AddPoint"
      (into [(sequence-node [(raw "ref ") target])] arguments)))

   "executable:java.awt.geom.Rectangle2D#contains(double,double)"
   (fn [target arguments]
     (font-compat-call "RectangleContains" (into [target] arguments)))

   "executable:java.awt.geom.Rectangle2D#intersect(java.awt.geom.Rectangle2D,java.awt.geom.Rectangle2D,java.awt.geom.Rectangle2D)"
   (fn [_target arguments]
     (font-compat-call
      "IntersectRectangles"
      [(first arguments)
       (second arguments)
       (sequence-node [(raw "ref ") (nth arguments 2)])]))

   "executable:java.awt.geom.PathIterator#isDone()"
   (fn [target _arguments]
     (call-node target "IsDone" []))

   "executable:java.awt.geom.PathIterator#next()"
   (fn [target _arguments]
     (call-node target "Next" []))

   "executable:java.awt.geom.PathIterator#currentSegment(double[])"
   (fn [target arguments]
     (call-node target "CurrentSegment" arguments))

   "executable:java.awt.geom.PathIterator#currentSegment(float[])"
   (fn [target arguments]
     (call-node target "CurrentSegment" arguments))

   "executable:java.awt.geom.PathIterator#getWindingRule()"
   (fn [target _arguments]
     (call-node target "GetWindingRule" []))

   "executable:java.awt.geom.Path2D$Float#lineTo(float,float)"
   (fn [target arguments]
     (font-compat-call "LineTo" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#lineTo(double,double)"
   (fn [target arguments]
     (font-compat-call "LineTo" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#moveTo(double,double)"
   (fn [target arguments]
     (font-compat-call "MoveTo" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#moveTo(float,float)"
   (fn [target arguments]
     (font-compat-call "MoveTo" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#quadTo(float,float,float,float)"
   (fn [target arguments]
     (font-compat-call "QuadTo" (into [target] arguments)))

   "executable:java.awt.geom.Point2D#setLocation(java.awt.geom.Point2D)"
   (fn [target arguments]
     (call-node target "SetLocation" arguments))

   "executable:java.awt.geom.Point2D#setLocation(double,double)"
   (fn [target arguments]
     (call-node target "SetLocation" arguments))

   "executable:java.awt.geom.Point2D#getX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".X")]))

   "executable:java.awt.geom.Point2D#getY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Y")]))

   "executable:java.awt.geom.Point2D#distance(java.awt.geom.Point2D)"
   (fn [target arguments]
     (call-node target "Distance" arguments))

   "executable:java.awt.geom.Point2D$Float#getX()"
   (fn [target _arguments]
     (sequence-node [target (raw ".X")]))

   "executable:java.awt.geom.Point2D$Float#getY()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Y")]))

   "executable:java.awt.geom.Point2D$Float#setLocation(double,double)"
   (fn [target arguments]
     (call-node target "SetLocation" arguments))

   "executable:java.awt.geom.Point2D$Float#setLocation(float,float)"
   (fn [target arguments]
     (call-node target "SetLocation" arguments))

   "executable:org.apache.pdfbox.io.RandomAccessRead#read(byte[])"
   (fn [target arguments]
     (sequence-node
      [(raw "((global::PdfCube.IO.RandomAccessRead)")
       target
       (raw ").Read(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))})

(defn- direct-instance-adaptation [member]
  (fn [target arguments]
    (call-node target member arguments)))

(def ^:private translated-project-invocation-adaptations
  {"executable:org.apache.fontbox.ttf.TrueTypeCollection#close()"
   (direct-instance-adaptation "Dispose")
   "executable:org.apache.fontbox.ttf.TrueTypeFont#close()"
   (direct-instance-adaptation "Dispose")
   "executable:org.apache.pdfbox.io.RandomAccessRead#close()"
   (direct-instance-adaptation "Dispose")
   "executable:org.apache.pdfbox.io.RandomAccessReadView#close()"
   (direct-instance-adaptation "Dispose")

   "executable:org.apache.pdfbox.util.DateConverter#adjustTimeZoneNicely(java.util.GregorianCalendar,java.util.TimeZone)"
   (fn [target arguments]
     (sequence-node
      [(first arguments)
       (raw " = ")
       (call-node target "adjustTimeZoneNicely" arguments)]))

   "executable:org.apache.pdfbox.util.DateConverter#parseTZoffset(java.lang.String,java.util.GregorianCalendar,java.text.ParsePosition)"
   (fn [target arguments]
     (call-node
      target
      "parseTZoffset"
      [(first arguments)
       (sequence-node [(raw "ref ") (second arguments)])
       (nth arguments 2)]))})

(def ^:private translated-project-boxed-covariant-executables
  #{"executable:org.apache.xmpbox.type.BooleanType#getValue()"})

(def ^:private graphics-invocation-adaptations
  {"executable:java.awt.Graphics#clearRect(int,int,int,int)"
   (direct-instance-adaptation "ClearRect")
   "executable:java.awt.Graphics#clipRect(int,int,int,int)"
   (direct-instance-adaptation "ClipRect")
   "executable:java.awt.Graphics#copyArea(int,int,int,int,int,int)"
   (direct-instance-adaptation "CopyArea")
   "executable:java.awt.Graphics#create()"
   (direct-instance-adaptation "Create")
   "executable:java.awt.Graphics#dispose()"
   (direct-instance-adaptation "Dispose")
   "executable:java.awt.Graphics#drawArc(int,int,int,int,int,int)"
   (direct-instance-adaptation "DrawArc")
   "executable:java.awt.Graphics#drawImage(java.awt.Image,int,int,java.awt.Color,java.awt.image.ImageObserver)"
   (direct-instance-adaptation "DrawImage")
   "executable:java.awt.Graphics#drawImage(java.awt.Image,int,int,java.awt.image.ImageObserver)"
   (direct-instance-adaptation "DrawImage")
   "executable:java.awt.Graphics#drawImage(java.awt.Image,int,int,int,int,java.awt.Color,java.awt.image.ImageObserver)"
   (direct-instance-adaptation "DrawImage")
   "executable:java.awt.Graphics#drawImage(java.awt.Image,int,int,int,int,java.awt.image.ImageObserver)"
   (direct-instance-adaptation "DrawImage")
   "executable:java.awt.Graphics#drawImage(java.awt.Image,int,int,int,int,int,int,int,int,java.awt.Color,java.awt.image.ImageObserver)"
   (direct-instance-adaptation "DrawImage")
   "executable:java.awt.Graphics#drawImage(java.awt.Image,int,int,int,int,int,int,int,int,java.awt.image.ImageObserver)"
   (direct-instance-adaptation "DrawImage")
   "executable:java.awt.Graphics#drawLine(int,int,int,int)"
   (direct-instance-adaptation "DrawLine")
   "executable:java.awt.Graphics#drawOval(int,int,int,int)"
   (direct-instance-adaptation "DrawOval")
   "executable:java.awt.Graphics#drawPolygon(int[],int[],int)"
   (direct-instance-adaptation "DrawPolygon")
   "executable:java.awt.Graphics#drawPolyline(int[],int[],int)"
   (direct-instance-adaptation "DrawPolyline")
   "executable:java.awt.Graphics#drawRoundRect(int,int,int,int,int,int)"
   (direct-instance-adaptation "DrawRoundRect")
   "executable:java.awt.Graphics#drawRect(int,int,int,int)"
   (direct-instance-adaptation "DrawRect")
   "executable:java.awt.Graphics#drawString(java.text.AttributedCharacterIterator,int,int)"
   (direct-instance-adaptation "DrawString")
   "executable:java.awt.Graphics#drawString(java.lang.String,int,int)"
   (direct-instance-adaptation "DrawString")
   "executable:java.awt.Graphics#fillArc(int,int,int,int,int,int)"
   (direct-instance-adaptation "FillArc")
   "executable:java.awt.Graphics#fillOval(int,int,int,int)"
   (direct-instance-adaptation "FillOval")
   "executable:java.awt.Graphics#fillPolygon(int[],int[],int)"
   (direct-instance-adaptation "FillPolygon")
   "executable:java.awt.Graphics#fillRect(int,int,int,int)"
   (direct-instance-adaptation "FillRect")
   "executable:java.awt.Graphics#fillRoundRect(int,int,int,int,int,int)"
   (direct-instance-adaptation "FillRoundRect")
   "executable:java.awt.Graphics#getClip()"
   (direct-instance-adaptation "GetClip")
   "executable:java.awt.Graphics#getClipBounds()"
   (direct-instance-adaptation "GetClipBounds")
   "executable:java.awt.Graphics#getColor()"
   (direct-instance-adaptation "GetColor")
   "executable:java.awt.Graphics#getFont()"
   (direct-instance-adaptation "GetFont")
   "executable:java.awt.Graphics#getFontMetrics(java.awt.Font)"
   (direct-instance-adaptation "GetFontMetrics")
   "executable:java.awt.Graphics#setClip(int,int,int,int)"
   (direct-instance-adaptation "SetClip")
   "executable:java.awt.Graphics#setClip(java.awt.Shape)"
   (direct-instance-adaptation "SetClip")
   "executable:java.awt.Graphics#setColor(java.awt.Color)"
   (direct-instance-adaptation "SetColor")
   "executable:java.awt.Graphics#setFont(java.awt.Font)"
   (direct-instance-adaptation "SetFont")
   "executable:java.awt.Graphics#setPaintMode()"
   (direct-instance-adaptation "SetPaintMode")
   "executable:java.awt.Graphics#setXORMode(java.awt.Color)"
   (direct-instance-adaptation "SetXorMode")
   "executable:java.awt.Graphics#translate(int,int)"
   (direct-instance-adaptation "Translate")

   "executable:java.awt.Graphics2D#addRenderingHints(java.util.Map)"
   (direct-instance-adaptation "AddRenderingHints")
   "executable:java.awt.Graphics2D#clip(java.awt.Shape)"
   (direct-instance-adaptation "Clip")
   "executable:java.awt.Graphics2D#draw(java.awt.Shape)"
   (direct-instance-adaptation "Draw")
   "executable:java.awt.Graphics2D#drawGlyphVector(java.awt.font.GlyphVector,float,float)"
   (direct-instance-adaptation "DrawGlyphVector")
   "executable:java.awt.Graphics2D#drawImage(java.awt.image.BufferedImage,java.awt.image.BufferedImageOp,int,int)"
   (direct-instance-adaptation "DrawBufferedImage")
   "executable:java.awt.Graphics2D#drawImage(java.awt.Image,java.awt.geom.AffineTransform,java.awt.image.ImageObserver)"
   (direct-instance-adaptation "DrawImage")
   "executable:java.awt.Graphics2D#drawRenderableImage(java.awt.image.renderable.RenderableImage,java.awt.geom.AffineTransform)"
   (direct-instance-adaptation "DrawRenderableImage")
   "executable:java.awt.Graphics2D#drawRenderedImage(java.awt.image.RenderedImage,java.awt.geom.AffineTransform)"
   (direct-instance-adaptation "DrawRenderedImage")
   "executable:java.awt.Graphics2D#drawString(java.text.AttributedCharacterIterator,float,float)"
   (direct-instance-adaptation "DrawString")
   "executable:java.awt.Graphics2D#drawString(java.text.AttributedCharacterIterator,int,int)"
   (direct-instance-adaptation "DrawString")
   "executable:java.awt.Graphics2D#drawString(java.lang.String,float,float)"
   (direct-instance-adaptation "DrawString")
   "executable:java.awt.Graphics2D#drawString(java.lang.String,int,int)"
   (direct-instance-adaptation "DrawString")
   "executable:java.awt.Graphics2D#fill(java.awt.Shape)"
   (direct-instance-adaptation "Fill")
   "executable:java.awt.Graphics2D#getBackground()"
   (direct-instance-adaptation "GetBackground")
   "executable:java.awt.Graphics2D#getComposite()"
   (direct-instance-adaptation "GetComposite")
   "executable:java.awt.Graphics2D#getDeviceConfiguration()"
   (direct-instance-adaptation "GetDeviceConfiguration")
   "executable:java.awt.GraphicsConfiguration#getDevice()"
   (direct-instance-adaptation "GetDevice")
   "executable:java.awt.GraphicsDevice#getDisplayMode()"
   (direct-instance-adaptation "GetDisplayMode")
   "executable:java.awt.GraphicsDevice#getType()"
   (direct-instance-adaptation "GetType")
   "executable:java.awt.DisplayMode#getBitDepth()"
   (direct-instance-adaptation "GetBitDepth")
   "executable:java.awt.Graphics2D#getFontRenderContext()"
   (direct-instance-adaptation "GetFontRenderContext")
   "executable:java.awt.Graphics2D#getPaint()"
   (direct-instance-adaptation "GetPaint")
   "executable:java.awt.Graphics2D#getRenderingHint(java.awt.RenderingHints$Key)"
   (direct-instance-adaptation "GetRenderingHint")
   "executable:java.awt.Graphics2D#getRenderingHints()"
   (direct-instance-adaptation "GetRenderingHints")
   "executable:java.awt.Graphics2D#getStroke()"
   (direct-instance-adaptation "GetStroke")
   "executable:java.awt.Graphics2D#getTransform()"
   (direct-instance-adaptation "GetTransform")
   "executable:java.awt.Graphics2D#hit(java.awt.Rectangle,java.awt.Shape,boolean)"
   (direct-instance-adaptation "Hit")
   "executable:java.awt.Graphics2D#rotate(double)"
   (direct-instance-adaptation "Rotate")
   "executable:java.awt.Graphics2D#rotate(double,double,double)"
   (direct-instance-adaptation "Rotate")
   "executable:java.awt.Graphics2D#scale(double,double)"
   (direct-instance-adaptation "Scale")
   "executable:java.awt.Graphics2D#setBackground(java.awt.Color)"
   (direct-instance-adaptation "SetBackground")
   "executable:java.awt.Graphics2D#setComposite(java.awt.Composite)"
   (direct-instance-adaptation "SetComposite")
   "executable:java.awt.Graphics2D#setPaint(java.awt.Paint)"
   (direct-instance-adaptation "SetPaint")
   "executable:java.awt.Graphics2D#setRenderingHints(java.util.Map)"
   (direct-instance-adaptation "SetRenderingHints")
   "executable:java.awt.Graphics2D#setStroke(java.awt.Stroke)"
   (direct-instance-adaptation "SetStroke")
   "executable:java.awt.Graphics2D#setTransform(java.awt.geom.AffineTransform)"
   (direct-instance-adaptation "SetTransform")
   "executable:java.awt.Graphics2D#shear(double,double)"
   (direct-instance-adaptation "Shear")
   "executable:java.awt.Graphics2D#transform(java.awt.geom.AffineTransform)"
   (direct-instance-adaptation "Transform")
   "executable:java.awt.Graphics2D#translate(double,double)"
   (direct-instance-adaptation "Translate")
   "executable:java.awt.Graphics2D#translate(int,int)"
   (direct-instance-adaptation "Translate")

   "executable:java.awt.print.Printable#print(java.awt.Graphics,java.awt.print.PageFormat,int)"
   (direct-instance-adaptation "Print")
   "executable:java.awt.print.Book#getNumberOfPages()"
   (direct-instance-adaptation "GetNumberOfPages")
   "executable:java.awt.print.Book#getPageFormat(int)"
   (direct-instance-adaptation "GetPageFormat")
   "executable:java.awt.print.Book#getPrintable(int)"
   (direct-instance-adaptation "GetPrintable")
   "executable:java.awt.print.Book#setPage(int,java.awt.print.Printable,java.awt.print.PageFormat)"
   (direct-instance-adaptation "SetPage")
   "executable:java.awt.print.Book#append(java.awt.print.Printable,java.awt.print.PageFormat)"
   (direct-instance-adaptation "Append")
   "executable:java.awt.print.Book#append(java.awt.print.Printable,java.awt.print.PageFormat,int)"
   (direct-instance-adaptation "Append")
   "executable:java.awt.print.Pageable#getNumberOfPages()"
   (direct-instance-adaptation "GetNumberOfPages")
   "executable:java.awt.print.Pageable#getPageFormat(int)"
   (direct-instance-adaptation "GetPageFormat")
   "executable:java.awt.print.Pageable#getPrintable(int)"
   (direct-instance-adaptation "GetPrintable")
   "executable:java.awt.print.PageFormat#getWidth()"
   (direct-instance-adaptation "GetWidth")
   "executable:java.awt.print.PageFormat#getHeight()"
   (direct-instance-adaptation "GetHeight")
   "executable:java.awt.print.PageFormat#getImageableWidth()"
   (direct-instance-adaptation "GetImageableWidth")
   "executable:java.awt.print.PageFormat#getImageableHeight()"
   (direct-instance-adaptation "GetImageableHeight")
   "executable:java.awt.print.PageFormat#getImageableX()"
   (direct-instance-adaptation "GetImageableX")
   "executable:java.awt.print.PageFormat#getImageableY()"
   (direct-instance-adaptation "GetImageableY")
   "executable:java.awt.print.PageFormat#getPaper()"
   (direct-instance-adaptation "GetPaper")
   "executable:java.awt.print.PageFormat#getOrientation()"
   (direct-instance-adaptation "GetOrientation")
   "executable:java.awt.print.PageFormat#getMatrix()"
   (direct-instance-adaptation "GetMatrix")
   "executable:java.awt.print.PageFormat#setPaper(java.awt.print.Paper)"
   (direct-instance-adaptation "SetPaper")
   "executable:java.awt.print.PageFormat#setOrientation(int)"
   (direct-instance-adaptation "SetOrientation")
   "executable:java.awt.print.Paper#setSize(double,double)"
   (direct-instance-adaptation "SetSize")
   "executable:java.awt.print.Paper#setImageableArea(double,double,double,double)"
   (direct-instance-adaptation "SetImageableArea")
   "executable:java.awt.print.Paper#getWidth()"
   (direct-instance-adaptation "GetWidth")
   "executable:java.awt.print.Paper#getHeight()"
   (direct-instance-adaptation "GetHeight")
   "executable:java.awt.print.Paper#getImageableX()"
   (direct-instance-adaptation "GetImageableX")
   "executable:java.awt.print.Paper#getImageableY()"
   (direct-instance-adaptation "GetImageableY")
   "executable:java.awt.print.Paper#getImageableWidth()"
   (direct-instance-adaptation "GetImageableWidth")
   "executable:java.awt.print.Paper#getImageableHeight()"
   (direct-instance-adaptation "GetImageableHeight")

   "executable:java.awt.AlphaComposite#getInstance(int,float)"
   (fn [_target arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaAlphaComposite.GetInstance(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.Composite#createContext(java.awt.image.ColorModel,java.awt.image.ColorModel,java.awt.RenderingHints)"
   (direct-instance-adaptation "CreateContext")

   "executable:java.awt.CompositeContext#compose(java.awt.image.Raster,java.awt.image.Raster,java.awt.image.WritableRaster)"
   (direct-instance-adaptation "Compose")

   "executable:java.awt.CompositeContext#dispose()"
   (direct-instance-adaptation "Dispose")})

(def ^:private commons-constructor-adaptations
  {"executable:java.awt.Graphics2D#<init>()"
   (fn [_arguments]
     (raw "new global::DripSharp.Runtime.PdfCubeGraphics2D()"))

   "executable:java.awt.print.Paper#<init>()"
   (fn [_arguments]
     (raw "new global::DripSharp.Runtime.JavaPaper()"))

   "executable:java.awt.print.PageFormat#<init>()"
   (fn [_arguments]
     (raw "new global::DripSharp.Runtime.JavaPageFormat()"))

   "executable:java.awt.print.Book#<init>()"
   (fn [_arguments]
     (raw "new global::DripSharp.Runtime.JavaBook()"))

   "executable:java.awt.print.PrinterIOException#<init>(java.io.IOException)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::System.IO.IOException(null, ")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.geom.AffineTransform#<init>()"
   (fn [_arguments]
     (font-compat-call "Identity" []))

   "executable:org.bouncycastle.cert.X509CertificateHolder#<init>(byte[])"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaX509CertificateHolder(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.cms.CMSEnvelopedData#<init>(byte[])"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaCmsEnvelopedData(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient#<init>(java.security.PrivateKey)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaJceKeyTransEnvelopedRecipient(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.text.SimpleDateFormat#<init>(java.lang.String,java.util.Locale)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaSimpleDateFormat(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.text.ParsePosition#<init>(int)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaParsePosition(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.text.AttributedString#<init>(java.lang.String)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaAttributedString(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.RenderingHints#<init>(java.util.Map)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.PdfCubeRenderingHints(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.BasicStroke#<init>(float)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaBasicStroke(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.BasicStroke#<init>(float,int,int,float,float[],float)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaBasicStroke(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.util.Date#<init>()"
   (fn [_arguments]
     (raw "(global::System.DateTimeOffset?)global::System.DateTimeOffset.Now"))

   "executable:java.util.GregorianCalendar#<init>(java.util.TimeZone,java.util.Locale)"
   (fn [arguments]
     (sequence-node
      [(raw "global::DripSharp.Runtime.JavaCompat.CalendarInstance(")
       (first arguments)
       (raw ")")]))

   "executable:java.awt.color.ICC_ColorSpace#<init>(java.awt.color.ICC_Profile)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaIccColorSpace(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.image.ComponentColorModel#<init>(java.awt.color.ColorSpace,boolean,boolean,int,int)"
   (fn [arguments]
     (font-compat-call "ComponentColorModel" arguments))

   "executable:java.awt.image.IndexColorModel#<init>(int,int,byte[],byte[],byte[])"
   (fn [arguments]
     (font-compat-call "IndexColorModel" arguments))

   "executable:java.awt.image.ColorConvertOp#<init>(java.awt.RenderingHints)"
   (fn [_arguments]
     (raw "new global::DripSharp.Runtime.JavaColorConvertOp()"))

   "executable:java.awt.image.ByteLookupTable#<init>(int,byte[])"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaLookupTable(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.image.LookupOp#<init>(java.awt.image.LookupTable,java.awt.RenderingHints)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaLookupOp(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.image.BufferedImage#<init>(java.awt.image.ColorModel,java.awt.image.WritableRaster,boolean,java.util.Hashtable)"
   (fn [arguments]
     (font-compat-call "CreateImage" arguments))

   "executable:javax.imageio.ImageTypeSpecifier#<init>(java.awt.image.RenderedImage)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaImageTypeSpecifier(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.IIOImage#<init>(java.awt.image.RenderedImage,java.util.List,javax.imageio.metadata.IIOMetadata)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaIioImage(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.IIOException#<init>(java.lang.String)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::System.IO.IOException(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.security.KeyStoreException#<init>(java.lang.String)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::System.Security.Cryptography.CryptographicException(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.security.KeyStoreException#<init>(java.lang.String,java.lang.Throwable)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::System.Security.Cryptography.CryptographicException(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.jce.provider.BouncyCastleProvider#<init>()"
   (fn [_arguments]
     (raw "new object()"))

   "executable:org.bouncycastle.asn1.ASN1InputStream#<init>(byte[])"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaAsn1InputStream(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.ASN1ObjectIdentifier#<init>(java.lang.String)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaAsn1ObjectIdentifier(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.DEROctetString#<init>(byte[])"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaDerOctetString(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.DERSet#<init>(org.bouncycastle.asn1.ASN1Encodable)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaDerSet(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.x509.AlgorithmIdentifier#<init>(org.bouncycastle.asn1.ASN1ObjectIdentifier,org.bouncycastle.asn1.ASN1Encodable)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaAlgorithmIdentifier(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.cms.EncryptedContentInfo#<init>(org.bouncycastle.asn1.ASN1ObjectIdentifier,org.bouncycastle.asn1.x509.AlgorithmIdentifier,org.bouncycastle.asn1.ASN1OctetString)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaEncryptedContentInfo(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.cms.EnvelopedData#<init>(org.bouncycastle.asn1.cms.OriginatorInfo,org.bouncycastle.asn1.ASN1Set,org.bouncycastle.asn1.cms.EncryptedContentInfo,org.bouncycastle.asn1.ASN1Set)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaEnvelopedData(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.cms.ContentInfo#<init>(org.bouncycastle.asn1.ASN1ObjectIdentifier,org.bouncycastle.asn1.ASN1Encodable)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaCmsContentInfo(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.cms.IssuerAndSerialNumber#<init>(org.bouncycastle.asn1.x500.X500Name,java.math.BigInteger)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaIssuerAndSerialNumber(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.cms.RecipientIdentifier#<init>(org.bouncycastle.asn1.cms.IssuerAndSerialNumber)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaRecipientIdentifier(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.cms.KeyTransRecipientInfo#<init>(org.bouncycastle.asn1.cms.RecipientIdentifier,org.bouncycastle.asn1.x509.AlgorithmIdentifier,org.bouncycastle.asn1.ASN1OctetString)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaKeyTransRecipientInfo(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:org.bouncycastle.asn1.cms.RecipientInfo#<init>(org.bouncycastle.asn1.cms.KeyTransRecipientInfo)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaRecipientInfo(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.geom.AffineTransform#<init>(java.awt.geom.AffineTransform)"
   (fn [arguments]
     (first arguments))

   "executable:java.awt.geom.AffineTransform#<init>(double,double,double,double,double,double)"
   (fn [arguments]
     (font-compat-call "AffineTransform" arguments))

   "executable:java.awt.geom.AffineTransform#<init>(float,float,float,float,float,float)"
   (fn [arguments]
     (font-compat-call "AffineTransform" arguments))

   "executable:java.awt.Color#<init>(int)"
   (fn [arguments]
     (font-compat-call "ColorFromRgb" arguments))

   "executable:java.awt.Color#<init>(int,int,int)"
   (fn [arguments]
     (font-compat-call "ColorFromComponents" arguments))

   "executable:java.awt.Color#<init>(int,int,int,int)"
   (fn [arguments]
     (font-compat-call "ColorFromComponents" arguments))

   "executable:java.awt.Color#<init>(float,float,float)"
   (fn [arguments]
     (font-compat-call "ColorFromFractions" arguments))

   "executable:java.awt.geom.Point2D$Double#<init>(double,double)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaPoint2D(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.geom.Point2D$Float#<init>(float,float)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaPoint2D(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.Point#<init>(int,int)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaPoint(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.Rectangle#<init>()"
   (fn [_arguments]
     (raw "new global::SkiaSharp.SKRectI()"))

   "executable:java.awt.Rectangle#<init>(int,int)"
   (fn [arguments]
     (font-compat-call "RectangleI" arguments))

   "executable:java.awt.Rectangle#<init>(int,int,int,int)"
   (fn [arguments]
     (font-compat-call "RectangleI" arguments))

   "executable:java.awt.geom.Ellipse2D$Double#<init>(double,double,double,double)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaEllipse(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.geom.Rectangle2D$Float#<init>()"
   (fn [_arguments]
     (raw "new global::SkiaSharp.SKRect()"))

   "executable:java.awt.geom.Rectangle2D$Float#<init>(float,float,float,float)"
   (fn [arguments]
     (font-compat-call "Rectangle" arguments))

   "executable:java.awt.geom.Rectangle2D$Double#<init>(double,double,double,double)"
   (fn [arguments]
     (font-compat-call "Rectangle" arguments))

   "executable:java.awt.geom.GeneralPath#<init>()"
   (fn [_arguments]
     (raw "new global::SkiaSharp.SKPath()"))

   "executable:java.awt.geom.GeneralPath#<init>(int,int)"
   (fn [arguments]
     (font-compat-call "CreatePath" arguments))

   "executable:java.awt.geom.GeneralPath#<init>(java.awt.Shape)"
   (fn [arguments]
     (font-compat-call "CreatePath" arguments))

   "executable:java.awt.geom.Path2D$Double#<init>(java.awt.Shape)"
   (fn [arguments]
     (font-compat-call "CreatePath" arguments))

   "executable:java.awt.geom.Area#<init>()"
   (fn [_arguments]
     (raw "new global::DripSharp.Runtime.JavaArea()"))

   "executable:java.awt.geom.Area#<init>(java.awt.Shape)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaArea(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.TexturePaint#<init>(java.awt.image.BufferedImage,java.awt.geom.Rectangle2D)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaTexturePaint(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.image.BufferedImage#<init>(int,int,int)"
   (fn [arguments]
     (font-compat-call "CreateBitmap" arguments))

   "executable:java.awt.image.DataBufferByte#<init>(int)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaDataBufferByte(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:java.awt.image.AffineTransformOp#<init>(java.awt.geom.AffineTransform,int)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.PdfCubeAffineTransformOp(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.stream.MemoryCacheImageInputStream#<init>(java.io.InputStream)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaImageInputStream(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))

   "executable:javax.imageio.stream.MemoryCacheImageOutputStream#<init>(java.io.OutputStream)"
   (fn [arguments]
     (sequence-node
      [(raw "new global::DripSharp.Runtime.JavaImageOutputStream(")
       (csharp/sequence-node arguments ", ")
       (raw ")")]))})

(def ^:private commons-field-adaptations
  {"field:org.bouncycastle.asn1.ASN1Encoding#DER"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaAsn1Encoding.DER"))

   "field:org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers#RC2_CBC"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPkcsObjectIdentifiers.RC2_CBC"))

   "field:org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers#data"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPkcsObjectIdentifiers.Data"))

   "field:org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers#envelopedData"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPkcsObjectIdentifiers.EnvelopedData"))

   "field:java.text.BreakIterator#DONE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaLineBreakIterator.DONE"))

   "field:java.awt.print.Printable#PAGE_EXISTS"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPrintable.PAGE_EXISTS"))

   "field:java.awt.print.Printable#NO_SUCH_PAGE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPrintable.NO_SUCH_PAGE"))

   "field:java.awt.print.Pageable#UNKNOWN_NUMBER_OF_PAGES"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPageable.UNKNOWN_NUMBER_OF_PAGES"))

   "field:java.awt.print.PageFormat#LANDSCAPE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPageFormat.LANDSCAPE"))

   "field:java.awt.print.PageFormat#PORTRAIT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPageFormat.PORTRAIT"))

   "field:java.awt.print.PageFormat#REVERSE_LANDSCAPE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPageFormat.REVERSE_LANDSCAPE"))

   "field:java.awt.AlphaComposite#SRC_OVER"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaAlphaComposite.SRC_OVER"))

   "field:java.awt.GraphicsDevice#TYPE_RASTER_SCREEN"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaGraphicsDevice.TYPE_RASTER_SCREEN"))

   "field:java.awt.GraphicsDevice#TYPE_PRINTER"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaGraphicsDevice.TYPE_PRINTER"))

   "field:java.awt.geom.AffineTransform#TYPE_TRANSLATION"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_TRANSLATION"))

   "field:java.awt.geom.AffineTransform#TYPE_FLIP"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_FLIP"))

   "field:java.awt.geom.AffineTransform#TYPE_MASK_SCALE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_MASK_SCALE"))

   "field:java.awt.color.ICC_Profile#CLASS_DISPLAY"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaIccProfile.CLASS_DISPLAY"))

   "field:java.awt.color.ICC_Profile#icSigDisplayClass"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaIccProfile.icSigDisplayClass"))

   "field:java.awt.color.ICC_Profile#icPerceptual"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaIccProfile.icPerceptual"))

   "field:java.awt.color.ICC_Profile#icSigHead"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaIccProfile.icSigHead"))

   "field:java.awt.color.ICC_Profile#icHdrDeviceClass"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaIccProfile.icHdrDeviceClass"))

   "field:java.awt.color.ICC_Profile#icHdrModel"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaIccProfile.icHdrModel"))

   "field:java.awt.color.ICC_Profile#icHdrRenderingIntent"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaIccProfile.icHdrRenderingIntent"))

   "field:java.awt.Rectangle#x"
   (fn [target]
     (sequence-node [target (raw ".Left")]))

   "field:java.awt.Rectangle#y"
   (fn [target]
     (sequence-node [target (raw ".Top")]))

   "field:java.awt.Rectangle#width"
   (fn [target]
     (sequence-node [target (raw ".Width")]))

   "field:java.awt.Rectangle#height"
   (fn [target]
     (sequence-node [target (raw ".Height")]))

   "field:java.awt.Point#x"
   (fn [target]
     (sequence-node [target (raw ".IntX")]))

   "field:java.awt.Point#y"
   (fn [target]
     (sequence-node [target (raw ".IntY")]))

   "field:java.awt.Color#WHITE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColor.White"))

   "field:java.awt.Image#SCALE_SMOOTH"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.SCALE_SMOOTH"))

   "field:java.awt.Color#GRAY"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColor.Gray"))

   "field:java.awt.image.BufferedImage#TYPE_CUSTOM"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_CUSTOM"))

   "field:java.awt.image.BufferedImage#TYPE_INT_RGB"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_INT_RGB"))

   "field:java.awt.image.BufferedImage#TYPE_INT_ARGB"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_INT_ARGB"))

   "field:java.awt.image.BufferedImage#TYPE_INT_BGR"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_INT_BGR"))

   "field:java.awt.image.BufferedImage#TYPE_3BYTE_BGR"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_3BYTE_BGR"))

   "field:java.awt.image.BufferedImage#TYPE_4BYTE_ABGR"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_4BYTE_ABGR"))

   "field:java.awt.image.BufferedImage#TYPE_BYTE_GRAY"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_BYTE_GRAY"))

   "field:java.awt.image.BufferedImage#TYPE_BYTE_BINARY"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.TYPE_BYTE_BINARY"))

   "field:java.awt.image.DataBuffer#TYPE_BYTE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE"))

   "field:java.awt.image.DataBuffer#TYPE_USHORT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT"))

   "field:java.awt.image.DataBuffer#TYPE_SHORT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.DATA_BUFFER_TYPE_SHORT"))

   "field:java.awt.image.DataBuffer#TYPE_INT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeFontCompat.DATA_BUFFER_TYPE_INT"))

   "field:java.awt.image.AffineTransformOp#TYPE_BILINEAR"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeAffineTransformOp.TYPE_BILINEAR"))

   "field:java.awt.image.AffineTransformOp#TYPE_BICUBIC"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeAffineTransformOp.TYPE_BICUBIC"))

   "field:java.awt.RenderingHints#KEY_INTERPOLATION"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.KEY_INTERPOLATION"))

   "field:java.awt.RenderingHints#VALUE_INTERPOLATION_BILINEAR"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.VALUE_INTERPOLATION_BILINEAR"))

   "field:java.awt.RenderingHints#VALUE_INTERPOLATION_BICUBIC"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.VALUE_INTERPOLATION_BICUBIC"))

   "field:java.awt.RenderingHints#VALUE_INTERPOLATION_NEAREST_NEIGHBOR"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR"))

   "field:java.awt.RenderingHints#KEY_RENDERING"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.KEY_RENDERING"))

   "field:java.awt.RenderingHints#VALUE_RENDER_DEFAULT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.VALUE_RENDER_DEFAULT"))

   "field:java.awt.RenderingHints#VALUE_RENDER_QUALITY"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.VALUE_RENDER_QUALITY"))

   "field:java.awt.RenderingHints#KEY_ANTIALIASING"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.KEY_ANTIALIASING"))

   "field:java.awt.RenderingHints#VALUE_ANTIALIAS_OFF"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.VALUE_ANTIALIAS_OFF"))

   "field:java.awt.RenderingHints#VALUE_ANTIALIAS_ON"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeRenderingHints.VALUE_ANTIALIAS_ON"))

   "field:java.awt.BasicStroke#CAP_BUTT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaBasicStroke.CAP_BUTT"))

   "field:java.awt.BasicStroke#JOIN_MITER"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaBasicStroke.JOIN_MITER"))

   "field:javax.imageio.ImageWriteParam#MODE_EXPLICIT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaImageWriteParam.MODE_EXPLICIT"))

   "field:java.awt.Transparency#OPAQUE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeTransparency.OPAQUE"))

   "field:java.awt.Transparency#BITMASK"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeTransparency.BITMASK"))

   "field:java.awt.Transparency#TRANSLUCENT"
   (fn [_target]
     (raw "global::DripSharp.Runtime.PdfCubeTransparency.TRANSLUCENT"))

   "field:java.lang.Character#DIRECTIONALITY_LEFT_TO_RIGHT"
   (fn [_target]
     (raw "0"))

   "field:java.lang.Character#DIRECTIONALITY_RIGHT_TO_LEFT"
   (fn [_target]
     (raw "1"))

   "field:java.lang.Character#DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC"
   (fn [_target]
     (raw "2"))

   "field:java.awt.color.ColorSpace#CS_sRGB"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColorSpace.CS_sRGB"))

   "field:java.awt.color.ColorSpace#CS_CIEXYZ"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColorSpace.CS_CIEXYZ"))

   "field:java.awt.color.ColorSpace#CS_GRAY"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColorSpace.CS_GRAY"))

   "field:java.awt.color.ColorSpace#TYPE_RGB"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColorSpace.TYPE_RGB"))

   "field:java.awt.color.ColorSpace#TYPE_GRAY"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColorSpace.TYPE_GRAY"))

   "field:java.awt.color.ColorSpace#TYPE_CMYK"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaColorSpace.TYPE_CMYK"))

   "field:java.awt.geom.Point2D$Double#x"
   (fn [target]
     (sequence-node [target (raw ".X")]))

   "field:java.awt.geom.Point2D$Double#y"
   (fn [target]
     (sequence-node [target (raw ".Y")]))

   "field:java.awt.geom.Point2D$Float#x"
   (fn [target]
     (sequence-node [(raw "(float)(") target (raw ".X)")]))

   "field:java.awt.geom.Point2D$Float#y"
   (fn [target]
     (sequence-node [(raw "(float)(") target (raw ".Y)")]))

   "field:java.awt.geom.PathIterator#WIND_EVEN_ODD"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.WIND_EVEN_ODD"))

   "field:java.awt.geom.PathIterator#WIND_NON_ZERO"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.WIND_NON_ZERO"))

   "field:java.awt.geom.Path2D#WIND_EVEN_ODD"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.WIND_EVEN_ODD"))

   "field:java.awt.geom.Path2D#WIND_NON_ZERO"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.WIND_NON_ZERO"))

   "field:java.awt.geom.PathIterator#SEG_MOVETO"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.SEG_MOVETO"))

   "field:java.awt.geom.PathIterator#SEG_LINETO"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.SEG_LINETO"))

   "field:java.awt.geom.PathIterator#SEG_QUADTO"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.SEG_QUADTO"))

   "field:java.awt.geom.PathIterator#SEG_CUBICTO"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.SEG_CUBICTO"))

   "field:java.awt.geom.PathIterator#SEG_CLOSE"
   (fn [_target]
     (raw "global::DripSharp.Runtime.JavaPathIterator.SEG_CLOSE"))})

(def ^:private font-discovery-invocation-adaptations
  {"executable:java.io.File#canRead()"
   (fn [target _arguments]
     (font-discovery-call "FileCanRead" [target]))

   "executable:java.io.File#exists()"
   (fn [target _arguments]
     (font-discovery-call "FileExists" [target]))

   "executable:java.io.File#isDirectory()"
   (fn [target _arguments]
     (font-discovery-call "FileIsDirectory" [target]))

   "executable:java.io.File#isHidden()"
   (fn [target _arguments]
     (font-discovery-call "FileIsHidden" [target]))

   "executable:java.io.File#listFiles()"
   (fn [target _arguments]
     (font-discovery-call "FileListFiles" [target]))

   "executable:java.io.File#toURI()"
   (fn [target _arguments]
     (font-discovery-call "FileToUri" [target]))})

(def ^:private bouncy-dependencies
  {"org.bouncycastle:bcpkix-jdk18on:jar:1.84"
   {:source-scope :compile-runtime
    :artifact-sha256
    (baseline/artifact-sha256
     :pdfcube "org.bouncycastle:bcpkix-jdk18on:jar:1.84")
    :runtime-package true
    :destination
    {:kind :microsoft-package
     :id "System.Security.Cryptography.Pkcs"
     :version "10.0.0"
     :capabilities
     ["System.Security.Cryptography.Pkcs"
      "System.Formats.Asn1"
      "System.Security.Cryptography.X509Certificates"]}}
   "org.bouncycastle:bcprov-jdk18on:jar:1.84"
   {:source-scope :compile-runtime
    :artifact-sha256
    (baseline/artifact-sha256
     :pdfcube "org.bouncycastle:bcprov-jdk18on:jar:1.84")
    :runtime-package false
    :destination
    {:kind :bcl
     :capabilities
     ["System.Security.Cryptography"
      "System.Security.Cryptography.X509Certificates"]}}
   "org.bouncycastle:bcutil-jdk18on:jar:1.84"
   {:source-scope :compile-runtime
    :artifact-sha256
    (baseline/artifact-sha256
     :pdfcube "org.bouncycastle:bcutil-jdk18on:jar:1.84")
    :runtime-package false
    :destination
    {:kind :bcl
     :capabilities
     ["System.Security.Cryptography"
      "System.Formats.Asn1"]}}})

(def ^:private logging-package
  {:id "Microsoft.Extensions.Logging.Abstractions"
   :version "10.0.0"
   :projection :microsoft-package})

(def ^:private pkcs-package
  {:id "System.Security.Cryptography.Pkcs"
   :version "10.0.0"
   :projection :microsoft-package})

(def ^:private skia-package
  {:id "SkiaSharp"
   :version "4.150.1"
   :projection :skia-sharp})

(def ^:private skia-linux-package
  {:id "SkiaSharp.NativeAssets.Linux"
   :version "4.150.1"
   :projection :skia-sharp-native-assets})

(def ^:private package-authors
  "Vibeformer")

(def ^:private package-copyright
  "Portions Copyright The Apache Software Foundation and other upstream contributors; see NOTICE.txt.")

(def ^:private legal-files
  (baseline/legal-files :pdfcube [:upstream]))

(def ^:private pdfbox-codec-legal-files
  (baseline/legal-files :pdfcube [:codecs]))

(def ^:private preflight-generic-erasure-mappings
  {"org.apache.pdfbox.preflight.font.container.FontContainer"
   "global::PdfCube.Preflight.Font.Container.IFontContainer"
   "org.apache.pdfbox.preflight.font.FontValidator"
   "global::PdfCube.Preflight.Font.IFontValidator"})

(defn- baseline-profile
  [profile-key]
  (baseline/profile :pdfcube profile-key))

(def ^:private products
  (array-map
   :io
   {:profile "pdfcube-io"
    :destination-config "destinations/io.edn"
    :maven-selector ":pdfbox-io"
    :source-project-id (:source-project-id (baseline-profile :io))
    :package-id "PdfCube.IO"
    :namespace-prefixes {"org.apache.pdfbox.io" "PdfCube.IO"}
    :external-namespace-prefixes {}
    :dependency-profiles []
    :source-project-dependencies []
    :package-dependencies []
    :project-references []
    :package-consumer
    {:strategy :source-file
     :project-file "PdfCube.IO.PackageConsumer.csproj"
     :source-path
     "targets/pdfcube/validation/probe/PdfCube.IO.FocusedConsumer.cs"
     :success-message "PdfCube.IO focused behavior passed."}
    :friend-assemblies #{"PdfCube.PdfBox" "PdfCube.Preflight"}
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package]
    :internal-capabilities #{:java-io :java-nio}
    :destination-capabilities #{:java-compat :java-regex-unicode}}

   :fontbox
   {:profile "pdfcube-fontbox"
    :destination-config "destinations/fontbox.edn"
    :maven-selector ":fontbox"
    :source-project-id (:source-project-id (baseline-profile :fontbox))
    :package-id "PdfCube.FontBox"
    :namespace-prefixes {"org.apache.fontbox" "PdfCube.FontBox"}
    :external-namespace-prefixes {"org.apache.pdfbox.io" "PdfCube.IO"}
    :dependency-profiles ["pdfcube-io"]
    :source-project-dependencies
    (:source-project-dependencies (baseline-profile :fontbox))
    :package-dependencies ["PdfCube.IO"]
    :project-references ["../pdfcube-io/PdfCube.IO.csproj"]
    :package-consumer
    {:strategy :source-file
     :project-file "PdfCube.FontBox.PackageConsumer.csproj"
     :source-path
     "targets/pdfcube/validation/probe/PdfCube.FontBox.FocusedConsumer.cs"
     :success-message "PdfCube.FontBox focused behavior passed."}
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package skia-package skia-linux-package]
    :internal-capabilities #{:font-discovery :icc :skia-geometry}
    :destination-capabilities #{:java-bidi :java-compat :java-regex-unicode}
    :compatibility-namespace "PdfCube.FB.Runtime"}

   :xmpbox
   {:profile "pdfcube-xmpbox"
    :destination-config "destinations/xmpbox.edn"
    :maven-selector ":xmpbox"
    :source-project-id (:source-project-id (baseline-profile :xmpbox))
    :package-id "PdfCube.XmpBox"
    :namespace-prefixes {"org.apache.xmpbox" "PdfCube.XmpBox"}
    :external-namespace-prefixes {}
    :dependency-profiles []
    :source-project-dependencies []
    :package-dependencies []
    :project-references []
    :package-consumer
    {:strategy :source-file
     :project-file "PdfCube.XmpBox.PackageConsumer.csproj"
     :source-path
     "targets/pdfcube/validation/probe/PdfCube.XmpBox.FocusedConsumer.cs"
     :success-message "PdfCube.XmpBox focused behavior passed."}
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package]
    :internal-capabilities #{:xml}
    :destination-capabilities #{:java-compat :java-regex-unicode}
    :compatibility-namespace "PdfCube.XMP.Runtime"}

   :pdfbox
   {:profile "pdfcube-pdfbox"
    :destination-config "destinations/pdfbox.edn"
    :maven-selector ":pdfbox"
    :source-project-id (:source-project-id (baseline-profile :pdfbox))
    :package-id "PdfCube.PdfBox"
    :namespace-prefixes {"org.apache.pdfbox" "PdfCube.PdfBox"}
    :external-namespace-prefixes
    {"org.apache.fontbox" "PdfCube.FontBox"
     "org.apache.pdfbox.io" "PdfCube.IO"}
    :dependency-profiles ["pdfcube-io" "pdfcube-fontbox"]
    :source-project-dependencies
    (:source-project-dependencies (baseline-profile :pdfbox))
    :package-dependencies ["PdfCube.IO" "PdfCube.FontBox"]
    :project-references
    ["../pdfcube-io/PdfCube.IO.csproj"
     "../pdfcube-fontbox/PdfCube.FontBox.csproj"]
    :friend-assemblies #{"PdfCube.Preflight"}
    :package-consumer
    {:strategy :compile-only
     :project-file "PdfCube.PdfBox.PackageConsumer.csproj"
     :compile-types
     ["PdfCube.PdfBox.Cos.COSDocument"
      "PdfCube.PdfBox.Pdmodel.PDDocument"]
     :success-message "PdfCube.PdfBox package boundary passed."}
    :external-dependencies
    (assoc bouncy-dependencies commons-coordinate commons-dependency)
    :runtime-packages [logging-package pkcs-package skia-package]
    :legal-files (into legal-files pdfbox-codec-legal-files)
    :internal-capabilities
    #{:calendar-value-semantics :icc :jbig2 :jpx :managed-raster :printing
      :security-handler-erasure :skia-graphics :unicode-bidi}
    :destination-capabilities #{:java-bidi :java-compat :java-regex-unicode}
    :bridge-capabilities #{:java-bidi}}

   :preflight
   {:profile "pdfcube-preflight"
    :destination-config "destinations/preflight.edn"
    :maven-selector ":preflight"
    :source-project-id (:source-project-id (baseline-profile :preflight))
    :package-id "PdfCube.Preflight"
    :namespace-prefixes {"org.apache.pdfbox.preflight" "PdfCube.Preflight"}
    :external-namespace-prefixes
    {"org.apache.fontbox" "PdfCube.FontBox"
     "org.apache.pdfbox.io" "PdfCube.IO"
     "org.apache.pdfbox" "PdfCube.PdfBox"
     "org.apache.xmpbox" "PdfCube.XmpBox"}
    :dependency-profiles ["pdfcube-pdfbox" "pdfcube-xmpbox"]
    :source-project-dependencies
    (:source-project-dependencies (baseline-profile :preflight))
    :package-dependencies ["PdfCube.PdfBox" "PdfCube.XmpBox"]
    :project-references
    ["../pdfcube-pdfbox/PdfCube.PdfBox.csproj"
     "../pdfcube-xmpbox/PdfCube.XmpBox.csproj"]
    :package-consumer
    {:strategy :source-file
     :project-file "PdfCube.Preflight.PackageConsumer.csproj"
     :source-path
     "targets/pdfcube/validation/probe/PdfCube.Preflight.FocusedConsumer.cs"
     :success-message "PdfCube.Preflight focused behavior passed."}
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package skia-package]
    :internal-capabilities #{:preflight-font-erasure}
    :generic-erasure-mappings preflight-generic-erasure-mappings
    :destination-capabilities #{:java-compat :java-regex-unicode}
    :bridge-capabilities #{}}))

(defn product-family
  "Returns the deterministic five-package PdfCube configuration contract."
  []
  {:schema-version 1
   :product-family :pdfcube
   :source-version source-version
   :source-revision source-revision
   :products products})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-pdfcube-configuration)))))

(defn- product! [configuration]
  (let [key (:pdfcube-product configuration)
        product (get products key)]
    (when-not product
      (fail! "PdfCube configuration does not select one of the five products"
             {:kind :unknown-pdfcube-product
              :pdfcube-product key
              :available (vec (keys products))}))
    product))

(defn- exact! [message field expected actual]
  (when-not (= expected actual)
    (fail! message {:field field :expected expected :actual actual})))

(def ^:private allowed-projection-kinds
  #{:bcl :internal-capability :microsoft-package :skia-sharp
    :translated-source})

(defn- validate-dependency-projections! [configuration]
  (let [external (:external-dependencies configuration)
        runtime-packages (:runtime-packages configuration)
        runtime-by-id (into {} (map (juxt :id identity)) runtime-packages)]
    (doseq [[coordinate {:keys [runtime-package destination]}] external]
      (when-not (and (map? destination)
                     (contains? allowed-projection-kinds (:kind destination)))
        (fail! "PdfCube source dependency has no approved destination projection"
               {:kind :unsupported-pdfcube-dependency-projection
                :coordinate coordinate :destination destination}))
      (if runtime-package
        (let [{:keys [id version]} destination
              runtime (get runtime-by-id id)]
          (when-not (and (contains? #{:microsoft-package :skia-sharp}
                                    (:kind destination))
                         (= version (:version runtime)))
            (fail! "PdfCube runtime dependency projection is missing or inconsistent"
                   {:kind :unapproved-pdfcube-runtime-dependency
                    :coordinate coordinate :destination destination
                    :runtime-package runtime})))
        (when (contains? #{:microsoft-package :skia-sharp} (:kind destination))
          (fail! "PdfCube package projection must be marked as a runtime package"
                 {:kind :invalid-pdfcube-runtime-projection
                  :coordinate coordinate :destination destination}))))
    (doseq [{:keys [id version projection] :as dependency} runtime-packages]
      (when-not
       (or (= logging-package dependency)
           (= pkcs-package dependency)
           (= skia-package dependency)
           (= skia-linux-package dependency))
        (fail! "PdfCube destination requested an unapproved runtime package"
               {:kind :unapproved-pdfcube-runtime-dependency
                :dependency dependency}))
      (when-not (case projection
                  :microsoft-package
                  (and (contains?
                        #{"Microsoft.Extensions.Logging.Abstractions"
                          "System.Security.Cryptography.Pkcs"}
                        id)
                       (= "10.0.0" version))
                  :skia-sharp
                  (and (= "SkiaSharp" id) (= "4.150.1" version))
                  :skia-sharp-native-assets
                  (and (= "SkiaSharp.NativeAssets.Linux" id)
                       (= "4.150.1" version))
                  false)
        (fail! "PdfCube runtime package version or provider is not approved"
               {:kind :unapproved-pdfcube-runtime-dependency
                :dependency dependency}))))
  configuration)

(defn validate-configuration!
  "Validates common project structure plus the exact selected PdfCube product."
  [configuration]
  (project-emission/validate-configuration! configuration)
  (let [product (product! configuration)
        package-id (:package-id product)]
    (validate-dependency-projections! configuration)
    (exact! "PdfCube destination must target net10.0"
            [:project :target-framework] "net10.0"
            (get-in configuration [:project :target-framework]))
    (exact! "PdfCube destination must disable nullable reference types"
            [:project :nullable] "disable"
            (get-in configuration [:project :nullable]))
    (exact! "PdfCube destination must treat warnings as errors"
            [:project :warnings-as-errors] true
            (get-in configuration [:project :warnings-as-errors]))
    (exact! "PdfCube destination must use explicit usings"
            [:project :implicit-usings] false
            (get-in configuration [:project :implicit-usings]))
    (exact! "PdfCube public names must use C# casing without changing member kinds"
            :name-policy
            {:public-identifiers :csharp
             :methods :methods
             :fields :fields
             :overloads :overloads}
            (:name-policy configuration))
    (doseq [[field actual]
            [[:product-family (:product-family configuration)]
             [:destination-bundle (:destination-bundle configuration)]
             [[:project :assembly-name]
              (get-in configuration [:project :assembly-name])]
             [[:project :root-namespace]
              (get-in configuration [:project :root-namespace])]
             [[:package :id] (get-in configuration [:package :id])]
             [[:package :version] (get-in configuration [:package :version])]
             [[:package :repository-commit]
              (get-in configuration [:package :repository-commit])]
             [[:package :authors]
              (get-in configuration [:package :authors])]
             [[:package :copyright]
              (get-in configuration [:package :copyright])]
             [[:package :symbols]
              (get-in configuration [:package :symbols])]
             [:mechanical-source (:mechanical-source configuration)]
             [:source-project-id (:source-project-id configuration)]
             [:namespaces (:namespaces configuration)]
             [:namespace-prefixes (:namespace-prefixes configuration)]
             [:external-namespace-prefixes
              (:external-namespace-prefixes configuration)]
             [:project-dependencies (:project-dependencies configuration)]
             [:package-dependencies (:package-dependencies configuration)]
             [:project-references (:project-references configuration)]
             [:package-consumer (:package-consumer configuration)]
             [:friend-assemblies (:friend-assemblies configuration)]
             [:external-dependencies (:external-dependencies configuration)]
             [:runtime-packages (:runtime-packages configuration)]
             [:internal-capabilities (:internal-capabilities configuration)]
             [:generic-erasure-mappings
              (:generic-erasure-mappings configuration)]
             [:destination-capabilities
              (:destination-capabilities configuration)]
             [:bridge-capabilities
              (:bridge-capabilities configuration)]
             [:compatibility-namespace
              (:compatibility-namespace configuration)]
             [:legal-files (:legal-files configuration)]
             [:resource-policy (:resource-policy configuration)]]]
      (let [expected
            (case field
              :product-family :pdfcube
              :destination-bundle bundle-selector
              [:project :assembly-name] package-id
              [:project :root-namespace] package-id
              [:package :id] package-id
              [:package :version]
              (:version (baseline/package :pdfcube package-id))
              [:package :repository-commit] source-revision
              [:package :authors] package-authors
              [:package :copyright] package-copyright
              [:package :symbols] :snupkg
              :mechanical-source mechanical-source
              :source-project-id (:source-project-id product)
              :namespaces {}
              :namespace-prefixes (:namespace-prefixes product)
              :external-namespace-prefixes
              (:external-namespace-prefixes product)
              :project-dependencies (:source-project-dependencies product)
              :package-dependencies (:package-dependencies product)
              :project-references (:project-references product)
              :package-consumer (:package-consumer product)
              :friend-assemblies (:friend-assemblies product)
              :external-dependencies (:external-dependencies product)
              :runtime-packages (:runtime-packages product)
              :internal-capabilities (:internal-capabilities product)
              :generic-erasure-mappings
              (:generic-erasure-mappings product)
              :destination-capabilities (:destination-capabilities product)
              :bridge-capabilities (:bridge-capabilities product)
              :compatibility-namespace (:compatibility-namespace product)
              :legal-files (or (:legal-files product) legal-files)
              :resource-policy {:strategy :embedded-resource-preserve-path})]
        (exact! "PdfCube destination differs from its approved product contract"
                field expected actual)))
    (exact! "PdfCube public surface must be derived from its resolved Spoon module"
            :public-surface {:strategy surface-selector}
            (:public-surface configuration))
    configuration))

(def ^:private digest-file util/sha256-file)

(defn- validate-legal-inputs! [workspace-root configuration]
  (doseq [{:keys [kind source sha256]} (:legal-files configuration)]
    (let [file (paths/resolve-path (paths/absolute workspace-root) source)]
      (when-not (paths/regular-file? file)
        (fail! "Configured PdfCube license or notice input is missing"
               {:kind :missing-pdfcube-legal-input
                :legal-kind kind :path (str file)}))
      (let [actual (digest-file file)]
        (when-not (= sha256 actual)
          (fail! "Configured PdfCube license or notice input changed"
                 {:kind :pdfcube-legal-input-mismatch
                  :legal-kind kind :path (str file)
                  :expected sha256 :actual actual})))))
  configuration)

(defn- validate-profile! [{:keys [workspace-root profile configuration]}]
  (let [configuration (validate-configuration! configuration)
        product (product! configuration)
        expected
        {:schema-version 1
         :profile (:profile product)
         :product-family :pdfcube
         :project-root "research/pdfbox"
         :revision source-revision
         :build-tool :maven
         :maven-project-id (:source-project-id product)
         :maven-selected-projects [(:maven-selector product)]
         :destination-bundle bundle-selector
         :destination-config (:destination-config product)
         :dependency-profiles (:dependency-profiles product)}
        actual
        (cond-> (select-keys profile (keys expected))
          (str/starts-with? (:destination-config profile)
                            "targets/pdfcube/")
          (update :destination-config
                  #(subs % (count "targets/pdfcube/"))))]
    (when-not (= expected actual)
      (fail! "PdfCube generation profile differs from the approved product contract"
             {:kind :invalid-pdfcube-profile
              :expected expected :actual actual}))
    (validate-legal-inputs! workspace-root configuration)))

(defn- validate-project-input!
  [{:keys [workspace-root profile project-input configuration] :as context}]
  (let [base-validator
        (get-in (java-library/rule-bundle)
                [:orchestration :validate-project-input!])]
    (base-validator context)
    (exact! "Maven selected the wrong PdfCube source project"
            :source-project-id (:source-project-id configuration)
            (:project-id project-input))
    (baseline/validate-project-input!
     workspace-root :pdfcube (:profile profile) project-input)
    project-input))

(defn- project-text [configuration resource-artifacts]
  (let [source-directory (get-in configuration [:output :source-directory])
        license (some #(when (= :license (:kind %)) %) (:legal-files configuration))
        properties
        (vec
         (remove
          nil?
          [(when
            (contains? (:internal-capabilities configuration)
                       :security-handler-erasure)
             (project-xml/element
              "DefineConstants"
              [(project-xml/text
                "$(DefineConstants);DRIPSHARP_PDFBOX_CRYPTO")]))
           (project-xml/element
            "PackageLicenseFile"
            [(project-xml/text (:package-path license))])
           (project-xml/element "DebugType"
                                [(project-xml/text "portable")])
           (project-xml/element "IncludeSymbols"
                                [(project-xml/text "true")])
           (project-xml/element "SymbolPackageFormat"
                                [(project-xml/text "snupkg")])]))
        items
        (vec
         (concat
          (for [{:keys [id version]}
                (sort-by :id (:runtime-packages configuration))]
            (project-xml/element
             "PackageReference"
             [["Include" id] ["Version" version]]
             []))
          (for [{:keys [destination package-path]}
                (sort-by :package-path (:legal-files configuration))]
            (project-xml/element
             "None"
             [["Include" (str source-directory "/" destination)]
              ["Pack" "true"]
              ["PackagePath" package-path]]
             []))))]
    (project-xml/render
     (project-emission/project-node
      (dissoc configuration :legal-files)
      resource-artifacts
      {:additional-properties properties
       :additional-items items}))))

(def ^:private base-compatibility-namespace "DripSharp.Runtime")

(defn- compatibility-namespace [configuration]
  (or (:compatibility-namespace configuration)
      base-compatibility-namespace))

(defn- insert-node
  [nodes index node]
  (vec (concat (subvec nodes 0 index)
               [node]
               (subvec nodes index))))

(defn- declaration?
  [node declaration-kind source-qualified-name]
  (and (= :declaration (:kind node))
       (= declaration-kind (get-in node [:data :declaration-kind]))
       (= source-qualified-name
          (get-in node [:data :source-qualified-name]))))

(defn- add-base-contract
  [declaration base-contract]
  (let [header (:header declaration)
        nodes (:nodes header)]
    (when-not (and (= :sequence (:kind header)) (vector? nodes))
      (fail! "Structured type declaration has no composable header"
             {:kind :invalid-pdfcube-structured-declaration
              :declaration-data (:data declaration)}))
    (let [insertion
          (if (get-in declaration [:data :has-constraints?])
            (dec (count nodes))
            (count nodes))
          separator
          (if (get-in declaration [:data :has-base-types?]) ", " " : ")]
      (-> declaration
          (assoc-in [:header :nodes]
                    (insert-node
                     nodes
                     insertion
                     (csharp/sequence-node
                      [(csharp/raw separator) (csharp/raw base-contract)])))
          (assoc-in [:data :has-base-types?] true)))))

(defn- update-declaration
  [node declaration-kind source-qualified-name update-node]
  (csharp/transform
   node
   (fn [current]
     (if (declaration? current declaration-kind source-qualified-name)
       (update-node current)
       current))))

(defn- prepend-member
  [declaration member]
  (when-not (= :statement-list
               (get-in declaration [:body :statements :kind]))
    (fail! "Structured type declaration has no member statement list"
           {:kind :invalid-pdfcube-structured-declaration
            :declaration-data (:data declaration)}))
  (update-in declaration [:body :statements :statements]
             #(vec (cons member %))))

(defn- append-statement
  [declaration statement]
  (when-not (= :statement-list
               (get-in declaration [:body :statements :kind]))
    (fail! "Structured method declaration has no statement list"
           {:kind :invalid-pdfcube-structured-declaration
            :declaration-data (:data declaration)}))
  (update-in declaration [:body :statements :statements] conj statement))

(def ^:private security-handler-carrier
  (str "global::PdfCube.PdfBox.Pdmodel.Encryption.SecurityHandler"
       "<global::PdfCube.PdfBox.Pdmodel.Encryption.ProtectionPolicy>"))

(def ^:private erased-security-handler
  "global::DripSharp.Runtime.PdfBoxSecurityHandler")

(defn- replace-security-handler-carrier
  [node]
  (-> node
      (csharp/transform
       (fn [current]
         (if (= security-handler-carrier
                (:text (csharp/render current)))
           (cond-> (csharp/raw erased-security-handler)
             (seq (:sources current))
             (assoc :sources (:sources current)))
           current)))
      (csharp/replace-raw-text
       [[security-handler-carrier erased-security-handler]])))

(defn- security-handler-declaration?
  [node]
  (let [found? (volatile! false)]
    (csharp/transform
     node
     (fn [current]
       (when
        (declaration?
         current :type "org.apache.pdfbox.pdmodel.encryption.SecurityHandler")
         (vreset! found? true))
       current))
    @found?))

(defn- erase-security-handler-carrier
  [configuration node]
  (if-not (contains? (:internal-capabilities configuration)
                     :security-handler-erasure)
    node
    (let [security-handler? (security-handler-declaration? node)]
      (cond-> node
        (not security-handler?)
        replace-security-handler-carrier

        security-handler?
        (update-declaration
         :type
         "org.apache.pdfbox.pdmodel.encryption.SecurityHandler"
         #(add-base-contract % erased-security-handler))))))

(defn- preserve-calendar-value-semantics
  [configuration node]
  (if-not
   (contains? (:internal-capabilities configuration)
              :calendar-value-semantics)
    node
    (-> node
        (update-declaration
         :method
         "org.apache.pdfbox.util.DateConverter"
         (fn [declaration]
           (case (get-in declaration [:data :source-name])
             "adjustTimeZoneNicely"
             (-> declaration
                 (update :header
                         csharp/replace-raw-text
                         [["void" "global::System.DateTimeOffset"]])
                 (append-statement (csharp/raw "return cal;")))

             "parseTZoffset"
             (update declaration :header
                     csharp/replace-raw-text
                     [["global::System.DateTimeOffset"
                       "ref global::System.DateTimeOffset"]])

             declaration))))))

(def ^:private preflight-font-container-contract
  "global::PdfCube.Preflight.Font.Container.IFontContainer")

(defn- replace-single-generic-argument
  [node targets source replacement]
  (csharp/transform
   node
   (fn [current]
     (if
      (and
       (= :generic-name (:kind current))
       (contains? targets (:text (csharp/render (:target current))))
       (= 1 (count (:arguments current)))
       (= source
          (:text (csharp/render (first (:arguments current))))))
       (update current :arguments
               (fn [[argument]]
                 [(csharp/sequence-node
                   [argument (csharp/raw replacement)])]))
       current))))

(defn- replace-rendered-node
  [node source replacement]
  (csharp/transform
   node
   (fn [current]
     (if (= source (:text (csharp/render current)))
       (cond-> replacement
         (seq (:sources current))
         (assoc :sources (:sources current)))
       current))))

(defn- preserve-preflight-generic-contracts
  [configuration node]
  (if-not (= "PdfCube.Preflight" (get-in configuration [:package :id]))
    node
    (-> node
        (update-declaration
         :type
         "org.apache.pdfbox.preflight.font.container.FontContainer"
         #(add-base-contract % "IFontContainer"))
        (update-declaration
         :type
         "org.apache.pdfbox.preflight.font.FontValidator"
         #(-> %
              (add-base-contract "IFontValidator")
              (prepend-member
               (csharp/raw
                (str preflight-font-container-contract
                     " IFontValidator.GetFontContainer() => "
                     "GetFontContainer();")))))
        (update-declaration
         :type
         "org.apache.pdfbox.preflight.font.Type3FontValidator"
         #(replace-single-generic-argument
           (replace-rendered-node
            %
            "global::System.Array.Empty<float>()"
            (csharp/invocation
             (csharp/generic-name
              (csharp/raw "global::System.Array.Empty")
              [(csharp/raw "float?")])
             []))
           #{"global::System.Collections.Generic.IList"}
           "float"
           "?")))))

(defn- transform-node [configuration node]
  (let [destination (compatibility-namespace configuration)
        node
        (if (= base-compatibility-namespace destination)
          node
          (csharp/transform-namespaces
           node
           {base-compatibility-namespace destination}))]
    (->> node
         (erase-security-handler-carrier configuration)
         (preserve-calendar-value-semantics configuration)
         (preserve-preflight-generic-contracts configuration))))

(defn- compatibility-asset [configuration asset]
  (if (= base-compatibility-namespace
         (compatibility-namespace configuration))
    asset
    (assoc asset
           :text-replacements
           {(str "namespace " base-compatibility-namespace)
            (str "namespace " (compatibility-namespace configuration))})))

(defn- legal-assets [{:keys [workspace-root configuration]}]
  (validate-legal-inputs! workspace-root configuration)
  (mapv (fn [{:keys [kind source destination]}]
          {:source source
           :destination destination
           :strategy (keyword "pdfcube.legal" (name kind))
           :missing-kind :missing-pdfcube-legal-input
           :missing-message "Configured PdfCube license or notice input is missing"})
        (:legal-files configuration)))

(defn- configured-runtime-source
  [configuration file-name]
  (or
   (some
    (fn [source]
      (when (= file-name (str (.getFileName (paths/path source))))
        source))
    (:runtime-sources configuration))
   (throw
    (ex-info "PdfCube capability has no selected target runtime asset"
             {:kind :missing-pdfcube-runtime-selection
              :asset file-name
              :selected (:runtime-sources configuration)}))))

(defn- internal-capability-assets [{:keys [configuration]}]
  (cond-> []
    (contains? (:internal-capabilities configuration) :font-discovery)
    (conj
     {:source
      (configured-runtime-source configuration
                                 "PdfCube.FontBox.Discovery.cs")
      :destination "DripSharp/Runtime/PdfCubeFontDiscovery.cs"
      :strategy :pdfcube.fontbox/font-discovery
      :missing-kind :missing-pdfcube-fontbox-discovery-source
      :missing-message "PdfCube FontBox discovery source is missing"})

    (or (contains? (:internal-capabilities configuration) :skia-geometry)
        (contains? (:internal-capabilities configuration) :skia-graphics))
    (conj
     {:source
      (configured-runtime-source configuration
                                 "PdfCube.FontBox.Compat.cs")
      :destination "DripSharp/Runtime/PdfCubeFontBoxCompat.cs"
      :strategy :pdfcube.fontbox/skia-geometry
      :missing-kind :missing-pdfcube-fontbox-compatibility-source
      :missing-message "PdfCube FontBox compatibility source is missing"})

    (and
     (contains? (:internal-capabilities configuration) :skia-geometry)
     (not (or (contains? (:internal-capabilities configuration) :jbig2)
              (contains? (:internal-capabilities configuration) :jpx))))
    (conj
     {:source
      (configured-runtime-source configuration
                                 "PdfCube.ImageCodecs.Unsupported.cs")
      :destination "DripSharp/Runtime/PdfCubeImageCodecs.cs"
      :strategy :pdfcube.fontbox/no-image-codecs
      :missing-kind :missing-pdfcube-fontbox-no-image-codecs-source
      :missing-message "PdfCube FontBox no-codec adapter source is missing"})

    (contains? (:internal-capabilities configuration) :icc)
    (conj
     {:source (configured-runtime-source configuration "PdfCube.Icc.cs")
      :destination "DripSharp/Runtime/PdfCubeIcc.cs"
      :strategy :pdfcube.pdfbox/icc
      :missing-kind :missing-pdfcube-icc-source
      :missing-message "PdfCube ICC source is missing"})

    (or (contains? (:internal-capabilities configuration) :jbig2)
        (contains? (:internal-capabilities configuration) :jpx))
    (conj
     {:source
      (configured-runtime-source configuration "PdfCube.ImageCodecs.cs")
      :destination "DripSharp/Runtime/PdfCubeImageCodecs.cs"
      :strategy :pdfcube.pdfbox/image-codec-adapters
      :missing-kind :missing-pdfcube-image-codec-adapter
      :missing-message "PdfCube image codec adapter source is missing"})

    (contains? (:internal-capabilities configuration) :jbig2)
    (conj
     {:source-tree "vendor/pdfcube/jbig2"
      :destination-tree "DripSharp/Runtime/Codecs/Jbig2"
      :include-pattern "\\.cs$"
      :text-prefix "#nullable disable\n#pragma warning disable\n"
      :strategy :pdfcube.pdfbox/jbig2-source
      :missing-kind :missing-pdfcube-jbig2-source
      :missing-message "Pinned PdfCube JBIG2 source is missing"})

    (contains? (:internal-capabilities configuration) :jpx)
    (conj
     {:source-tree "vendor/pdfcube/jpx"
      :destination-tree "DripSharp/Runtime/Codecs/Jpx"
      :include-pattern "\\.cs$"
      :text-charset-fallback "ISO-8859-1"
      :text-prefix "#nullable disable\n#pragma warning disable\n"
      :strategy :pdfcube.pdfbox/jpx-source
      :missing-kind :missing-pdfcube-jpx-source
      :missing-message "Pinned PdfCube JPEG 2000 source is missing"})

    (contains? (:internal-capabilities configuration)
               :security-handler-erasure)
    (conj
     {:source
      (configured-runtime-source configuration "PdfCube.PdfBox.Compat.cs")
      :destination "DripSharp/Runtime/PdfBoxSecurityHandler.cs"
      :strategy :pdfcube.pdfbox/security-handler-erasure
      :missing-kind :missing-pdfcube-pdfbox-compatibility-source
      :missing-message "PdfCube PDFBox compatibility source is missing"})

    (contains? (:internal-capabilities configuration)
               :preflight-font-erasure)
    (conj
     {:source
      (configured-runtime-source configuration
                                 "PdfCube.Preflight.Compat.cs")
      :destination "DripSharp/Runtime/PdfCubePreflightCompat.cs"
      :strategy :pdfcube.preflight/font-erasure
      :missing-kind :missing-pdfcube-preflight-compatibility-source
      :missing-message "PdfCube Preflight compatibility source is missing"})))

(defn rule-bundle
  "Returns the PdfCube rule bundle composed over reusable Java-library rules."
  []
  (let [base (java-library/rule-bundle)
        base-assets (get-in base [:rules :destination-bridges :assets])
        base-create-context
        (get-in base [:rules :structural-declarations :create-context])]
    (-> base
        (assoc :id :pdfcube
               :product-family :pdfcube
               :orchestration
               {:validate-profile! validate-profile!
                :validate-project-input! validate-project-input!})
        (assoc-in [:rules :project-policy]
                  {:validate-configuration! validate-configuration!
                   :project-text project-text
                   :transform-node transform-node})
        (assoc-in
         [:rules :resolved-mappings :declarative-mapping-required?]
         (fn [{:keys [configuration]} occurrence]
           (let [key (:key occurrence)
                 target-owned?
                 (case (:kind occurrence)
                   :type
                   (and (str/starts-with? key "type:")
                        (contains? commons-type-mappings
                                   (subs key (count "type:"))))

                   :executable
                   (or (contains? commons-invocation-adaptations key)
                       (contains? translated-project-invocation-adaptations key)
                       (contains? graphics-invocation-adaptations key)
                       (and
                        (contains? (:internal-capabilities configuration)
                                   :font-discovery)
                        (contains? font-discovery-invocation-adaptations key)))

                   :constructor
                   (contains? commons-constructor-adaptations key)

                   :field
                   (contains? commons-field-adaptations key)

                   false)]
             (boolean
              (and
               (java-library/jdk-mapping-candidate? occurrence)
               (not target-owned?))))))
        (assoc-in
         [:rules :structural-declarations :create-context]
         (fn [options]
           (let [configuration (:configuration options)
                 invocation-adaptations
                 (cond-> (merge commons-invocation-adaptations
                                translated-project-invocation-adaptations
                                graphics-invocation-adaptations)
                   (contains? (:internal-capabilities configuration)
                              :font-discovery)
                   (merge font-discovery-invocation-adaptations))]
             (base-create-context
              (assoc options
                     :destination-type-mappings commons-type-mappings
                     :destination-constructor-adaptations
                     commons-constructor-adaptations
                     :destination-field-adaptations
                     commons-field-adaptations
                     :destination-invocation-adaptations
                     invocation-adaptations
                     :destination-boxed-covariant-executables
                     translated-project-boxed-covariant-executables)))))
        (assoc-in [:rules :destination-bridges :assets]
                  (fn [context]
                    (let [configuration (:configuration context)
                          code-assets
                          (mapv #(compatibility-asset configuration %)
                                (concat (base-assets context)
                                        (internal-capability-assets context)))]
                      (into code-assets (legal-assets context))))))))

(defn public-surface-strategy
  "Provides the target-family wrapper used by the five configurations."
  []
  (assoc (java-library/public-surface-strategy)
         :id :pdfcube-complete-accessible-library
         :product-family :pdfcube))
