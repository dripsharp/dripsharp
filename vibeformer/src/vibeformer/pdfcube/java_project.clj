(ns vibeformer.pdfcube.java-project
  "PdfCube destination composition and fail-closed five-package policy.

  The reusable Java-library translator supplies structural Java translation.
  This namespace owns only PdfCube's approved product identities, namespace and
  public-name policy, source-to-destination dependency projections, legal
  inputs, resource policy, and deterministic project metadata."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-library :as java-library]
            [vibeformer.java-project :as project-emission]
            [vibeformer.paths :as paths])
  (:import [java.nio.file Files OpenOption Path]
           [java.security MessageDigest]))

(def ^:private source-revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

(def ^:private source-version "3.0.8")

(def ^:private bundle-selector
  'vibeformer.pdfcube.java-project/rule-bundle)

(def ^:private surface-selector
  'vibeformer.pdfcube.java-project/public-surface-strategy)

(def ^:private commons-coordinate
  "commons-logging:commons-logging:jar:1.4.0")

(def ^:private commons-dependency
  {:source-scope :compile-runtime
   :artifact-sha256
   "d175dbd751dd782a63bde28c7a039520e971f25e84b79c19b8435edc3603e0dc"
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
   "java.awt.geom.AffineTransform"
   ["global::SkiaSharp.SKMatrix" :pdfcube.type/skia-matrix]
   "java.awt.geom.GeneralPath"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.Path2D"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.Path2D$Float"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.PathIterator"
   ["global::SkiaSharp.SKPath" :pdfcube.type/skia-path]
   "java.awt.geom.Point2D"
   ["global::Vibeformer.Runtime.JavaPoint2D" :pdfcube.type/point]
   "java.awt.geom.Point2D$Float"
   ["global::Vibeformer.Runtime.JavaPoint2D" :pdfcube.type/point]
   "java.awt.geom.Rectangle2D"
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
   [(raw (str "global::Vibeformer.Runtime.PdfCubeFontCompat." member "("))
    (csharp/sequence-node arguments ", ")
    (raw ")")]))

(defn- logger-message-node [message]
  (sequence-node
   [(raw "global::Vibeformer.Runtime.JavaCompat.StringValueOf(")
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

   "executable:java.awt.geom.AffineTransform#getTranslateInstance(double,double)"
   (fn [_target arguments]
     (font-compat-call "Translation" arguments))

   "executable:java.awt.geom.Path2D#closePath()"
   (fn [target _arguments]
     (font-compat-call "Close" [target]))

   "executable:java.awt.geom.Path2D#getCurrentPoint()"
   (fn [target _arguments]
     (font-compat-call "CurrentPoint" [target]))

   "executable:java.awt.geom.Path2D$Float#append(java.awt.geom.PathIterator,boolean)"
   (fn [target arguments]
     (font-compat-call "AddPath" [target (first arguments)]))

   "executable:java.awt.geom.Path2D$Float#curveTo(float,float,float,float,float,float)"
   (fn [target arguments]
     (font-compat-call "CurveTo" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#getBounds2D()"
   (fn [target _arguments]
     (sequence-node [target (raw ".Bounds")]))

   "executable:java.awt.geom.Path2D$Float#getPathIterator(java.awt.geom.AffineTransform)"
   (fn [target arguments]
     (font-compat-call "PathIterator" (into [target] arguments)))

   "executable:java.awt.geom.Path2D$Float#lineTo(float,float)"
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

(def ^:private bouncy-dependencies
  {"org.bouncycastle:bcpkix-jdk18on:jar:1.84"
   {:source-scope :compile-runtime
    :artifact-sha256
    "c87f16ed9e5ec61bc94151e9f3646ac44e50cd448121ce84367fa4b7ec7ec1bb"
    :runtime-package false
    :destination
    {:kind :bcl
     :capabilities
     ["System.Security.Cryptography.Pkcs"
      "System.Formats.Asn1"
      "System.Security.Cryptography.X509Certificates"]}}
   "org.bouncycastle:bcprov-jdk18on:jar:1.84"
   {:source-scope :compile-runtime
    :artifact-sha256
    "64d6c5a6121fcd927152dd182cbed39afe0fda641a970d9bcc0c9cb1858b2731"
    :runtime-package false
    :destination
    {:kind :bcl
     :capabilities
     ["System.Security.Cryptography"
      "System.Security.Cryptography.X509Certificates"]}}
   "org.bouncycastle:bcutil-jdk18on:jar:1.84"
   {:source-scope :compile-runtime
    :artifact-sha256
    "b374e16963421fb9cfb01cc20d7ad8fd2f8b8188e3eef0ec0a8965e245f7619a"
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

(def ^:private skia-package
  {:id "SkiaSharp"
   :version "4.150.1"
   :projection :skia-sharp})

(def ^:private legal-files
  [{:kind :license
    :source "research/pdfbox/LICENSE.txt"
    :destination "Legal/LICENSE.txt"
    :package-path "LICENSE.txt"
    :sha256
    "1301d8415a4868d82aeeec594849cf7679f1ead4636a9603dc46875f5713157e"}
   {:kind :notice
    :source "research/pdfbox/NOTICE.txt"
    :destination "Legal/NOTICE.txt"
    :package-path "NOTICE.txt"
    :sha256
    "40741b4ab76d77ba4fbc5e8759277169fb0ce281859d273075de6fd3a3588458"}])

(def ^:private products
  (array-map
   :io
   {:profile "pdfcube-io"
    :destination-config "vibeformer/config/pdfcube-io-destination.edn"
    :maven-selector ":pdfbox-io"
    :source-project-id "org.apache.pdfbox:pdfbox-io:3.0.8"
    :package-id "PdfCube.IO"
    :namespace-prefixes {"org.apache.pdfbox.io" "PdfCube.IO"}
    :external-namespace-prefixes {}
    :dependency-profiles []
    :source-project-dependencies []
    :package-dependencies []
    :project-references []
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package]
    :internal-capabilities #{:java-io :java-nio}
    :destination-capabilities #{:java-compat :java-regex-unicode}}

   :fontbox
   {:profile "pdfcube-fontbox"
    :destination-config "vibeformer/config/pdfcube-fontbox-destination.edn"
    :maven-selector ":fontbox"
    :source-project-id "org.apache.pdfbox:fontbox:3.0.8"
    :package-id "PdfCube.FontBox"
    :namespace-prefixes {"org.apache.fontbox" "PdfCube.FontBox"}
    :external-namespace-prefixes {"org.apache.pdfbox.io" "PdfCube.IO"}
    :dependency-profiles ["pdfcube-io"]
    :source-project-dependencies ["org.apache.pdfbox:pdfbox-io:3.0.8"]
    :package-dependencies ["PdfCube.IO"]
    :project-references ["../pdfcube-io/PdfCube.IO.csproj"]
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package skia-package]
    :internal-capabilities #{:font-discovery :skia-geometry}
    :destination-capabilities #{:java-compat :java-regex-unicode}
    :compatibility-namespace "PdfCube.FB.Runtime"}

   :xmpbox
   {:profile "pdfcube-xmpbox"
    :destination-config "vibeformer/config/pdfcube-xmpbox-destination.edn"
    :maven-selector ":xmpbox"
    :source-project-id "org.apache.pdfbox:xmpbox:3.0.8"
    :package-id "PdfCube.XmpBox"
    :namespace-prefixes {"org.apache.xmpbox" "PdfCube.XmpBox"}
    :external-namespace-prefixes {}
    :dependency-profiles []
    :source-project-dependencies []
    :package-dependencies []
    :project-references []
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package]
    :internal-capabilities #{:xml}
    :destination-capabilities #{:java-compat :java-regex-unicode}}

   :pdfbox
   {:profile "pdfcube-pdfbox"
    :destination-config "vibeformer/config/pdfcube-pdfbox-destination.edn"
    :maven-selector ":pdfbox"
    :source-project-id "org.apache.pdfbox:pdfbox:3.0.8"
    :package-id "PdfCube.PdfBox"
    :namespace-prefixes {"org.apache.pdfbox" "PdfCube.PdfBox"}
    :external-namespace-prefixes
    {"org.apache.fontbox" "PdfCube.FontBox"
     "org.apache.pdfbox.io" "PdfCube.IO"}
    :dependency-profiles ["pdfcube-io" "pdfcube-fontbox"]
    :source-project-dependencies
    ["org.apache.pdfbox:fontbox:3.0.8"
     "org.apache.pdfbox:pdfbox-io:3.0.8"]
    :package-dependencies ["PdfCube.IO" "PdfCube.FontBox"]
    :project-references
    ["../pdfcube-io/PdfCube.IO.csproj"
     "../pdfcube-fontbox/PdfCube.FontBox.csproj"]
    :external-dependencies
    (assoc bouncy-dependencies commons-coordinate commons-dependency)
    :runtime-packages [logging-package skia-package]
    :internal-capabilities
    #{:icc :jbig2 :jpx :managed-raster :printing :skia-graphics :unicode-bidi}
    :destination-capabilities #{:java-compat :java-regex-unicode}}

   :preflight
   {:profile "pdfcube-preflight"
    :destination-config "vibeformer/config/pdfcube-preflight-destination.edn"
    :maven-selector ":preflight"
    :source-project-id "org.apache.pdfbox:preflight:3.0.8"
    :package-id "PdfCube.Preflight"
    :namespace-prefixes {"org.apache.pdfbox.preflight" "PdfCube.Preflight"}
    :external-namespace-prefixes
    {"org.apache.fontbox" "PdfCube.FontBox"
     "org.apache.pdfbox.io" "PdfCube.IO"
     "org.apache.pdfbox" "PdfCube.PdfBox"
     "org.apache.xmpbox" "PdfCube.XmpBox"}
    :dependency-profiles ["pdfcube-pdfbox" "pdfcube-xmpbox"]
    :source-project-dependencies
    ["org.apache.pdfbox:fontbox:3.0.8"
     "org.apache.pdfbox:pdfbox-io:3.0.8"
     "org.apache.pdfbox:pdfbox:3.0.8"
     "org.apache.pdfbox:xmpbox:3.0.8"]
    :package-dependencies ["PdfCube.PdfBox" "PdfCube.XmpBox"]
    :project-references
    ["../pdfcube-pdfbox/PdfCube.PdfBox.csproj"
     "../pdfcube-xmpbox/PdfCube.XmpBox.csproj"]
    :external-dependencies {commons-coordinate commons-dependency}
    :runtime-packages [logging-package skia-package]
    :internal-capabilities #{:icc :managed-raster :skia-graphics}
    :destination-capabilities #{:java-compat :java-regex-unicode}}))

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
           (= skia-package dependency))
        (fail! "PdfCube destination requested an unapproved runtime package"
               {:kind :unapproved-pdfcube-runtime-dependency
                :dependency dependency}))
      (when-not (case projection
                  :microsoft-package
                  (and (= "Microsoft.Extensions.Logging.Abstractions" id)
                       (= "10.0.0" version))
                  :skia-sharp
                  (and (= "SkiaSharp" id) (= "4.150.1" version))
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
             [:source-project-id (:source-project-id configuration)]
             [:namespaces (:namespaces configuration)]
             [:namespace-prefixes (:namespace-prefixes configuration)]
             [:external-namespace-prefixes
              (:external-namespace-prefixes configuration)]
             [:project-dependencies (:project-dependencies configuration)]
             [:package-dependencies (:package-dependencies configuration)]
             [:project-references (:project-references configuration)]
             [:external-dependencies (:external-dependencies configuration)]
             [:runtime-packages (:runtime-packages configuration)]
             [:internal-capabilities (:internal-capabilities configuration)]
             [:destination-capabilities
              (:destination-capabilities configuration)]
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
              [:package :version] "3.0.8-vibeformer.0"
              [:package :repository-commit] source-revision
              :source-project-id (:source-project-id product)
              :namespaces {}
              :namespace-prefixes (:namespace-prefixes product)
              :external-namespace-prefixes
              (:external-namespace-prefixes product)
              :project-dependencies (:source-project-dependencies product)
              :package-dependencies (:package-dependencies product)
              :project-references (:project-references product)
              :external-dependencies (:external-dependencies product)
              :runtime-packages (:runtime-packages product)
              :internal-capabilities (:internal-capabilities product)
              :destination-capabilities (:destination-capabilities product)
              :compatibility-namespace (:compatibility-namespace product)
              :legal-files legal-files
              :resource-policy {:strategy :embedded-resource-preserve-path})]
        (exact! "PdfCube destination differs from its approved product contract"
                field expected actual)))
    (exact! "PdfCube public surface must be derived from its resolved Spoon module"
            :public-surface {:strategy surface-selector}
            (:public-surface configuration))
    configuration))

(defn- digest-file [^Path input]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (Files/newInputStream input (make-array OpenOption 0))]
      (let [buffer (byte-array 16384)]
        (loop [read (.read stream buffer)]
          (when-not (neg? read)
            (when (pos? read)
              (.update digest buffer 0 read))
            (recur (.read stream buffer))))))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

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
        actual (select-keys profile (keys expected))]
    (when-not (= expected actual)
      (fail! "PdfCube generation profile differs from the approved product contract"
             {:kind :invalid-pdfcube-profile
              :expected expected :actual actual}))
    (validate-legal-inputs! workspace-root configuration)))

(defn- validate-project-input!
  [{:keys [project-input configuration] :as context}]
  (let [base-validator
        (get-in (java-library/rule-bundle)
                [:orchestration :validate-project-input!])]
    (base-validator context)
    (exact! "Maven selected the wrong PdfCube source project"
            :source-project-id (:source-project-id configuration)
            (:project-id project-input))
    project-input))

(defn- xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- project-text [configuration resource-artifacts]
  (let [base-text (project-emission/project-text configuration resource-artifacts)
        source-directory (get-in configuration [:output :source-directory])
        license (some #(when (= :license (:kind %)) %) (:legal-files configuration))
        properties
        (str "    <PackageLicenseFile>"
             (xml-escape (:package-path license))
             "</PackageLicenseFile>\n")
        items
        (str
         (apply str
                (for [{:keys [id version]} (sort-by :id (:runtime-packages configuration))]
                  (str "    <PackageReference Include=\"" (xml-escape id)
                       "\" Version=\"" (xml-escape version) "\" />\n")))
         (apply str
                (for [{:keys [destination package-path]}
                      (sort-by :package-path (:legal-files configuration))]
                  (str "    <None Include=\"" (xml-escape source-directory) "/"
                       (xml-escape destination)
                       "\" Pack=\"true\" PackagePath=\""
                       (xml-escape package-path) "\" />\n"))))]
    (-> base-text
        (str/replace "    <PackageRequireLicenseAcceptance>false</PackageRequireLicenseAcceptance>\n"
                     (str properties
                          "    <PackageRequireLicenseAcceptance>false</PackageRequireLicenseAcceptance>\n"))
        (str/replace "  </ItemGroup>\n</Project>\n"
                     (str items "  </ItemGroup>\n</Project>\n")))))

(def ^:private base-compatibility-namespace "Vibeformer.Runtime")

(defn- compatibility-namespace [configuration]
  (or (:compatibility-namespace configuration)
      base-compatibility-namespace))

(defn- transform-source-text [configuration text]
  (let [destination (compatibility-namespace configuration)]
    (when-not (= (count base-compatibility-namespace) (count destination))
      (fail! "PdfCube compatibility namespace must preserve source-map offsets"
             {:kind :invalid-pdfcube-compatibility-namespace
              :expected-length (count base-compatibility-namespace)
              :actual destination
              :actual-length (count destination)}))
    (str/replace text
                 (str "global::" base-compatibility-namespace)
                 (str "global::" destination))))

(defn- compatibility-asset [configuration asset]
  (if (= base-compatibility-namespace
         (compatibility-namespace configuration))
    asset
    (assoc asset
           :text-replacements
           {(str "namespace " base-compatibility-namespace ";")
            (str "namespace " (compatibility-namespace configuration) ";")})))

(defn- legal-assets [{:keys [workspace-root configuration]}]
  (validate-legal-inputs! workspace-root configuration)
  (mapv (fn [{:keys [kind source destination]}]
          {:source source
           :destination destination
           :strategy (keyword "pdfcube.legal" (name kind))
           :missing-kind :missing-pdfcube-legal-input
           :missing-message "Configured PdfCube license or notice input is missing"})
        (:legal-files configuration)))

(defn- internal-capability-assets [{:keys [configuration]}]
  (cond-> []
    (contains? (:internal-capabilities configuration) :skia-geometry)
    (conj
     {:source "vibeformer/runtime/PdfCube.FontBox.Compat.cs"
      :destination "Vibeformer/Runtime/PdfCubeFontBoxCompat.cs"
      :strategy :pdfcube.fontbox/skia-geometry
      :missing-kind :missing-pdfcube-fontbox-compatibility-source
      :missing-message "PdfCube FontBox compatibility source is missing"})))

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
                   :transform-source-text transform-source-text})
        (assoc-in
         [:rules :structural-declarations :create-context]
         (fn [options]
           (base-create-context
            (assoc options
                   :destination-type-mappings commons-type-mappings
                   :destination-invocation-adaptations
                   commons-invocation-adaptations))))
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
