;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns pdfcli.main
  "pdfcli over Apache PDFBox 3.0.8, implementing
  validation/pdfcarton-cli/contract.md. A plain PDFBox client: its output is
  the oracle the PdfCarton implementation is compared against."
  (:require [clojure.string :as str]
            [pdfcli.json :as json :refer [obj]])
  (:import (com.fasterxml.jackson.core JsonParser$Feature)
           (com.fasterxml.jackson.databind JsonNode ObjectMapper)
           (java.awt.image BufferedImage)
           (java.io File OutputStreamWriter)
           (java.lang.management ManagementFactory)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.security MessageDigest)
           (java.util List)
           (javax.imageio ImageIO)
           (org.apache.pdfbox Loader)
           (org.apache.pdfbox.cos COSArray COSBase COSDictionary COSName COSStream COSString)
           (org.apache.pdfbox.io IOUtils)
           (org.apache.pdfbox.multipdf PDFMergerUtility Splitter)
           (org.apache.pdfbox.pdfwriter.compress CompressParameters)
           (org.apache.pdfbox.pdmodel PDDocument PDPage PDPageContentStream PDResources)
           (org.apache.pdfbox.pdmodel.common PDRectangle)
           (org.apache.pdfbox.pdmodel.common.filespecification PDComplexFileSpecification PDEmbeddedFile)
           (org.apache.pdfbox.pdmodel.encryption AccessPermission InvalidPasswordException
                                                 StandardProtectionPolicy)
           (org.apache.pdfbox.pdmodel.font PDFont PDSimpleFont PDType0Font PDType1Font
                                           Standard14Fonts$FontName)
           (org.apache.pdfbox.pdmodel.graphics.form PDFormXObject)
           (org.apache.pdfbox.pdmodel.graphics.image LosslessFactory PDImageXObject)
           (org.apache.pdfbox.pdmodel.interactive.annotation PDAnnotation)
           (org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline PDOutlineItem
                                                                             PDOutlineNode)
           (org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDButton PDCheckBox PDChoice
                                                       PDComboBox PDField PDListBox
                                                       PDNonTerminalField PDPushButton
                                                       PDRadioButton PDSignatureField PDTextField)
           (org.apache.pdfbox.rendering ImageType PDFRenderer)
           (org.apache.pdfbox.text PDFTextStripper)
           (org.apache.xmpbox.xml DomXmpParser)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Errors

(def exit-codes
  {"usage" 2
   "io" 3
   "invalid-password" 4
   "owner-password-required" 4
   "load-failed" 5})

(defn- fail!
  ([code message] (fail! code message nil))
  ([code message cause]
   (throw (ex-info message {::code code} cause))))

;; ---------------------------------------------------------------------------
;; Option parsing

(def ^:private range-pattern #"(\d+)?-(\d+)?|(\d+)")

(defn- parse-int [option ^String text]
  (when-not (re-matches #"\d+" text)
    (fail! "usage" (str option " needs a non-negative integer: " text)))
  (try (Integer/parseInt text)
       (catch NumberFormatException _
         (fail! "usage" (str option " is out of range: " text)))))

(defn- parse-range [option ^String text]
  (let [[_ from to single] (or (re-matches range-pattern text)
                               (fail! "usage" (str option " has an invalid page range: " text)))
        page (fn [s] (let [n (parse-int option s)]
                       (when (< n 1) (fail! "usage" (str option " pages start at 1: " text)))
                       n))]
    (cond
      single (let [n (page single)] [n n])
      (and (nil? from) (nil? to)) (fail! "usage" (str option " has an empty page range: " text))
      :else (let [a (if from (page from) 1)
                  b (if to (page to) Integer/MAX_VALUE)]
              (when (> a b) (fail! "usage" (str option " range ends before it starts: " text)))
              [a b]))))

(defn- parse-ranges [option ^String text]
  (mapv #(parse-range option %) (str/split text #"," -1)))

(defn- parse-dpi [option ^String text]
  (when-not (re-matches #"\d+(\.\d+)?" text)
    (fail! "usage" (str option " needs a positive decimal number: " text)))
  (let [v (Float/parseFloat text)]
    (when-not (and (pos? v) (Float/isFinite v))
      (fail! "usage" (str option " needs a positive decimal number: " text)))
    v))

(defn- parse-angle [option ^String text]
  (when-not (re-matches #"-?\d+" text)
    (fail! "usage" (str option " needs an integer: " text)))
  (let [v (try (Integer/parseInt text)
               (catch NumberFormatException _ (fail! "usage" (str option " is out of range: " text))))]
    (when-not (zero? (mod v 90))
      (fail! "usage" (str option " must be a multiple of 90: " text)))
    v))

(def ^:private image-types
  {"rgb" ImageType/RGB "argb" ImageType/ARGB "gray" ImageType/GRAY "binary" ImageType/BINARY})

(def ^:private permission-names
  ["print" "modify" "extract" "annotate" "fill-in" "extract-for-accessibility"
   "assemble" "print-faithful"])

(defn- parse-permissions [option ^String text]
  (let [names (if (= "" text) [] (str/split text #"," -1))]
    (doseq [n names]
      (when-not (some #{n} permission-names)
        (fail! "usage" (str option " has an unknown permission: " n))))
    (set names)))

(defn- choice-parser [allowed]
  (fn [option text]
    (or (allowed text)
        (fail! "usage" (str option " must be one of " (str/join ", " (sort (keys allowed)))
                            ": " text)))))

(def ^:private option-kinds
  {:flag nil
   :string (fn [_ text] text)
   :int parse-int
   :range parse-range
   :ranges parse-ranges
   :dpi parse-dpi
   :angle parse-angle
   :image-type (choice-parser image-types)
   :key-length (choice-parser {"40" 40 "128" 128 "256" 256})
   :permissions parse-permissions})

(def ^:private timing-options {"--warmup" :int "--repeat" :int})

(def commands
  "Positional arity, options and required options of every command."
  {"info" {:arity :one :options {"--password" :string}}
   "text" {:arity :one :options {"--password" :string "--pages" :range "--sort" :flag}}
   "render" {:arity :one
             :options {"--password" :string "--pages" :range "--dpi" :dpi
                       "--image-type" :image-type "--out-dir" :string "--no-write" :flag}}
   "images" {:arity :one :options {"--password" :string "--pages" :range "--out-dir" :string}}
   "fonts" {:arity :one :options {"--password" :string "--pages" :range}}
   "outline" {:arity :one :options {"--password" :string}}
   "annotations" {:arity :one :options {"--password" :string "--pages" :range}}
   "form-fields" {:arity :one :options {"--password" :string}}
   "form-fill" {:arity :one :options {"--password" :string "--values" :string "--out" :string}
                :required ["--values" "--out"]}
   "attachments" {:arity :one :options {"--password" :string "--out-dir" :string}}
   "merge" {:arity :many :options {"--out" :string} :required ["--out"]}
   "split" {:arity :one :options {"--password" :string "--ranges" :ranges "--out-dir" :string}
            :required ["--ranges" "--out-dir"]}
   "rotate" {:arity :one :options {"--password" :string "--angle" :angle "--pages" :range
                                   "--out" :string}
             :required ["--angle" "--out"]}
   "encrypt" {:arity :one
              :options {"--password" :string "--owner-password" :string "--user-password" :string
                        "--key-length" :key-length "--permissions" :permissions "--out" :string}
              :required ["--owner-password" "--out"]}
   "decrypt" {:arity :one :options {"--password" :string "--out" :string} :required ["--out"]}
   "create" {:arity :none :options {"--text" :string "--out" :string} :required ["--out"]}
   "save" {:arity :one :options {"--password" :string "--compress" :flag "--out" :string}
           :required ["--out"]}})

(defn parse-args
  "Parses argv after the command name into {:options {...} :positional [...]}."
  [command args]
  (let [{:keys [arity options required]} (commands command)
        allowed (merge options timing-options)]
    (loop [args (seq args) parsed {} positional [] only-positional false]
      (if-let [[arg & more] args]
        (cond
          only-positional (recur more parsed (conj positional arg) true)
          (= arg "--") (recur more parsed positional true)
          (str/starts-with? arg "--")
          (let [kind (if (contains? allowed arg)
                       (allowed arg)
                       (fail! "usage" (str "Unknown option for " command ": " arg)))]
            (when (contains? parsed arg) (fail! "usage" (str "Repeated option: " arg)))
            (if (= kind :flag)
              (recur more (assoc parsed arg true) positional false)
              (let [[value & rest-args] more]
                (when (nil? more) (fail! "usage" (str "Option needs a value: " arg)))
                (recur rest-args (assoc parsed arg ((option-kinds kind) arg value))
                       positional false))))
          :else (recur more parsed (conj positional arg) false))
        (do
          (case arity
            :one (when-not (= 1 (count positional))
                   (fail! "usage" (str command " needs exactly one input file")))
            :many (when (empty? positional)
                    (fail! "usage" (str command " needs at least one input file")))
            :none (when (seq positional)
                    (fail! "usage" (str command " takes no input file"))))
          (when (and (contains? parsed "--repeat") (< (long (parsed "--repeat")) 1))
            (fail! "usage" "--repeat must be at least 1"))
          (doseq [r required]
            (when-not (contains? parsed r)
              (fail! "usage" (str command " needs " r))))
          (when (and (= command "render") (not (parsed "--no-write")) (not (parsed "--out-dir")))
            (fail! "usage" "render needs --out-dir unless --no-write is given"))
          (when (and (= command "render") (parsed "--no-write") (parsed "--out-dir"))
            (fail! "usage" "render takes either --out-dir or --no-write"))
          {:options parsed :positional positional})))))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- sha256-hex [^bytes data]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") data)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- file-sha256 [^String path]
  (sha256-hex (Files/readAllBytes (.toPath (File. path)))))

(defn- pixel-sha256
  "SHA-256 of the image as row-major R,G,B,A bytes taken from getRGB."
  [^BufferedImage image]
  (let [w (.getWidth image)
        h (.getHeight image)
        md (MessageDigest/getInstance "SHA-256")
        row (int-array w)
        out (byte-array (* 4 w))]
    (dotimes [y h]
      (.getRGB image 0 (int y) w 1 row 0 w)
      (dotimes [x w]
        (let [argb (aget row x)
              o (* 4 x)]
          (aset out o (unchecked-byte (bit-shift-right argb 16)))
          (aset out (+ o 1) (unchecked-byte (bit-shift-right argb 8)))
          (aset out (+ o 2) (unchecked-byte argb))
          (aset out (+ o 3) (unchecked-byte (unsigned-bit-shift-right argb 24)))))
      (.update md out))
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) (.digest md)))))

(defn- child-file ^File [^String dir ^String name] (File. dir name))

(defn- file-length [^String path] (.length (File. path)))

(defn- check-input! [^String path]
  (let [f (File. path)]
    (when-not (and (.isFile f) (.canRead f))
      (fail! "io" (str "Cannot read input file: " path)))))

(defn- open-pdf
  "Reopens a written PDF for its summary."
  ^PDDocument [^String path ^String pw]
  (Loader/loadPDF (File. path) (if pw pw "")))

(defn- load-doc
  ^PDDocument [^String path ^String password]
  (try
    (Loader/loadPDF (File. path) (or password ""))
    (catch InvalidPasswordException e
      (fail! "invalid-password" (or (.getMessage e) "Invalid password") e))
    (catch Exception e
      (fail! "load-failed" (or (.getMessage e) (str (class e))) e))))

(defn- resolve-range
  "Clamps a parsed page range to [1, page-count]; may be empty (start > end)."
  [[a b] page-count]
  [(max 1 a) (min b page-count)])

(defn- selected-pages [options page-count]
  (let [[s e] (resolve-range (or (options "--pages") [1 Integer/MAX_VALUE]) page-count)]
    [s e (range s (inc e))]))

(defn- rect [^PDRectangle r]
  (when r
    [(.getLowerLeftX r) (.getLowerLeftY r) (.getUpperRightX r) (.getUpperRightY r)]))

(defn- text-or-name [^COSBase v]
  (cond
    (instance? COSString v) (.getString ^COSString v)
    (instance? COSName v) (.getName ^COSName v)
    :else nil))

(defn- dict-get
  "The resolved value of key k (a string) in COS dictionary d."
  ^COSBase [^COSDictionary d ^String k]
  (.getDictionaryObject d (COSName/getPDFName k)))

(defn- pdf-name ^COSName [^String k] (COSName/getPDFName k))

(defn- sorted-names [^COSDictionary d]
  (if d
    (sort (map #(.getName ^COSName %) (.keySet d)))
    []))

(defn- encryption-summary [^PDDocument doc]
  (when (.isEncrypted doc)
    (let [e (.getEncryption doc)]
      (obj "filter" (.getFilter e)
           "version" (.getVersion e)
           "revision" (.getRevision e)
           "keyLength" (.getLength e)
           "permissions" (.getPermissions e)))))

(defn- written
  "The common summary of a written PDF, from reopening it."
  [^String path ^String password & extra]
  (with-open [doc (open-pdf path password)]
    (apply obj "output" path "bytes" (file-length path) "pageCount" (.getNumberOfPages doc)
           (mapcat (fn [f] (f doc)) extra))))

;; ---------------------------------------------------------------------------
;; Commands: each defines execute (timed) and summarize (untimed).

(defmulti execute (fn [command _ctx] command))
(defmulti summarize (fn [command _ctx _value] command))
(defmethod summarize :default [_ _ value] value)

(defn- input [ctx] (first (:positional ctx)))
(defn- password ^String [ctx] (get-in ctx [:options "--password"]))

;; info ----------------------------------------------------------------------

(def ^:private info-keys
  ["Title" "Author" "Subject" "Keywords" "Creator" "Producer" "CreationDate" "ModDate" "Trapped"])

(defn- info-summary [^PDDocument doc]
  (let [d (.getCOSDictionary (.getTrailer (.getDocument doc)) COSName/INFO)]
    (when d
      (let [standard (set info-keys)]
        (apply obj
               (concat
                (mapcat (fn [k] [k (text-or-name (dict-get d k))])
                        info-keys)
                ["custom"
                 (json/sorted-obj
                  (into {}
                        (for [k (sorted-names d) :when (not (standard k))]
                          [k (text-or-name (dict-get d k))])))]))))))

(defn- xmp-summary [^PDDocument doc]
  (when-let [md (.getMetadata (.getDocumentCatalog doc))]
    (let [data (try (.toByteArray md) (catch Exception _ nil))
          ^org.apache.xmpbox.XMPMetadata meta (when data
                                                (try (.parse (doto (DomXmpParser.) (.setStrictParsing false)) ^bytes data)
                                                     (catch Exception _ nil)))
          safe (fn [f] (try (f) (catch Exception _ nil)))]
      (obj "length" (when data (alength ^bytes data))
           "sha256" (when data (sha256-hex data))
           "parsed" (some? meta)
           "schemas" (when meta
                       (vec (sort (distinct (keep #(.getNamespace ^org.apache.xmpbox.schema.XMPSchema %)
                                                  (.getAllSchemas meta))))))
           "dcTitle" (when meta (safe #(some-> (.getDublinCoreSchema meta) .getTitle)))
           "dcCreators" (when meta (safe #(some-> (.getDublinCoreSchema meta) .getCreators vec)))
           "pdfProducer" (when meta (safe #(some-> (.getAdobePDFSchema meta) .getProducer)))
           "xmpCreatorTool" (when meta (safe #(some-> (.getXMPBasicSchema meta) .getCreatorTool)))
           "pdfaPart" (when meta (safe #(some-> (.getPDFAIdentificationSchema meta) .getPart)))
           "pdfaConformance" (when meta
                               (safe #(some-> (.getPDFAIdentificationSchema meta) .getConformance)))))))

(defmethod execute "info" [_ ctx]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (obj "version" (.getVersion doc)
         "headerVersion" (.getVersion (.getDocument doc))
         "pageCount" (.getNumberOfPages doc)
         "encrypted" (.isEncrypted doc)
         "encryption" (when (.isEncrypted doc)
                        (apply obj (concat (apply concat (json/entries (encryption-summary doc)))
                                           ["ownerPermission"
                                            (.isOwnerPermission (.getCurrentAccessPermission doc))])))
         "info" (info-summary doc)
         "xmp" (xmp-summary doc)
         "pages" (vec (map-indexed
                       (fn [i ^PDPage p]
                         (obj "page" (inc i)
                              "mediaBox" (rect (.getMediaBox p))
                              "cropBox" (rect (.getCropBox p))
                              "rotation" (.getRotation p)))
                       (.getPages doc))))))

;; text ----------------------------------------------------------------------

(defmethod execute "text" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [[s e] (selected-pages options (.getNumberOfPages doc))
          sort? (boolean (options "--sort"))
          text (if (> s e)
                 ""
                 (.getText (doto (PDFTextStripper.)
                             (.setSortByPosition sort?)
                             (.setLineSeparator "\n")
                             (.setPageEnd "\n")
                             (.setStartPage s)
                             (.setEndPage e))
                           doc))]
      (obj "startPage" s "endPage" e "sortByPosition" sort? "text" text))))

;; render --------------------------------------------------------------------

(defmethod execute "render" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [[_ _ pages] (selected-pages options (.getNumberOfPages doc))
          dpi (float (or (options "--dpi") 72.0))
          type-name (or (some (fn [[k v]] (when (= v (options "--image-type")) k)) image-types) "rgb")
          ^ImageType type (image-types type-name)
          out-dir (options "--out-dir")
          renderer (PDFRenderer. doc)]
      {:dpi dpi
       :type type-name
       :out-dir out-dir
       :pages (vec (for [p pages]
                     (let [image (.renderImageWithDPI renderer (int (dec p)) dpi type)
                           name (format "page-%04d.png" p)]
                       (when out-dir
                         (ImageIO/write image "png" (child-file out-dir name)))
                       {:page p :width (.getWidth image) :height (.getHeight image)
                        :file (when out-dir name)})))})))

(defmethod summarize "render" [_ _ {:keys [dpi type out-dir pages]}]
  (obj "dpi" dpi
       "imageType" type
       "outDir" out-dir
       "pages" (mapv (fn [{:keys [page width height file]}]
                       (obj "page" page "width" width "height" height "file" file
                            "sha256" (when file (file-sha256 (str (child-file out-dir file))))))
                     pages)))

;; resource walks ------------------------------------------------------------

(defn- stream-subtype [^COSBase v]
  (when (instance? COSStream v)
    (.getNameAsString ^COSStream v COSName/SUBTYPE)))

(defn- xobject-dict ^COSDictionary [^PDResources resources]
  (.getCOSDictionary (.getCOSObject resources) COSName/XOBJECT))

(defn- walk-xobjects
  "The contract's resource walk: calls (on-resources prefix resources) on entry
  to each resources dictionary, then, in sorted XObject name order, (on-image
  path name resources) for each image and a descent into each form not already
  on the descent path."
  [^PDResources resources prefix stack {:keys [on-image on-resources]}]
  (when resources
    (when on-resources (on-resources prefix resources))
    (let [xobjects (xobject-dict resources)]
      (doseq [name (sorted-names xobjects)]
        (let [v (dict-get xobjects name)
              path (str prefix name)]
          (case (stream-subtype v)
            "Image" (when on-image (on-image path name resources))
            "Form" (when-not (some #(identical? % v) stack)
                     (walk-xobjects (.getResources (PDFormXObject. ^COSStream v))
                                    (str path "/") (conj stack v)
                                    {:on-image on-image :on-resources on-resources}))
            nil))))))

;; images --------------------------------------------------------------------

(defn- filter-names [^COSStream s]
  (let [f (.getFilters s)]
    (cond
      (instance? COSName f) [(.getName ^COSName f)]
      (instance? COSArray f) (vec (keep #(when (instance? COSName %) (.getName ^COSName %))
                                        (.toList ^COSArray f)))
      :else [])))

(defmethod execute "images" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [[_ _ pages] (selected-pages options (.getNumberOfPages doc))
          out-dir (options "--out-dir")
          result (transient [])]
      (doseq [p pages]
        (let [counter (volatile! 0)]
          (walk-xobjects
           (.getResources (.getPage doc (int (dec p)))) "" []
           {:on-image
            (fn [path name ^PDResources resources]
              (let [seq-no (vswap! counter inc)
                    safe (fn [f] (try (f) (catch Exception _ nil)))
                    ^PDImageXObject image (safe #(.getXObject resources (pdf-name name)))
                    s ^COSStream (dict-get (xobject-dict resources) name)
                    decoded (when image (safe #(.getImage image)))
                    file (when (and decoded out-dir) (format "page-%04d-%03d.png" p seq-no))]
                (when file (ImageIO/write ^BufferedImage decoded "png" (child-file out-dir file)))
                (conj! result
                       (obj "page" p
                            "name" path
                            "width" (.getInt s COSName/WIDTH -1)
                            "height" (.getInt s COSName/HEIGHT -1)
                            "colorSpace" (when image (safe #(.getName (.getColorSpace image))))
                            "bitsPerComponent" (when image (safe #(.getBitsPerComponent image)))
                            "filters" (filter-names s)
                            "stencil" (if image (.isStencil image) false)
                            "suffix" (when image (safe #(.getSuffix image)))
                            "decodedSha256" (when decoded (pixel-sha256 decoded))
                            "file" file
                            "error" (cond (nil? image) "load-failed"
                                          (nil? decoded) "decode-failed"
                                          :else nil)))))})))
      (obj "images" (persistent! result)))))

;; fonts ---------------------------------------------------------------------

(defn- font-encoding [^PDFont font]
  (cond
    (instance? PDType0Font font) (some-> (.getCMap ^PDType0Font font) .getName)
    (instance? PDSimpleFont font) (some-> (.getEncoding ^PDSimpleFont font) .getEncodingName)
    :else nil))

(defmethod execute "fonts" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [[_ _ pages] (selected-pages options (.getNumberOfPages doc))
          result (transient [])]
      (doseq [p pages]
        (walk-xobjects
         (.getResources (.getPage doc (int (dec p)))) "" []
         {:on-resources
          (fn [prefix ^PDResources resources]
            (let [fonts (.getCOSDictionary (.getCOSObject resources) COSName/FONT)]
              (doseq [name (sorted-names fonts)
                      :let [d (dict-get fonts name)]
                      :when (instance? COSDictionary d)]
                (let [^COSDictionary d d
                      font (try (.getFont resources (pdf-name name))
                                (catch Exception _ nil))]
                  (conj! result
                         (obj "page" p
                              "name" (str prefix name)
                              "baseFont" (.getNameAsString d COSName/BASE_FONT)
                              "subtype" (.getNameAsString d COSName/SUBTYPE)
                              "embedded" (when font (.isEmbedded ^PDFont font))
                              "encoding" (when font
                                           (try (font-encoding font) (catch Exception _ nil)))
                              "toUnicode" (.containsKey d COSName/TO_UNICODE)
                              "error" (when-not font "load-failed")))))))}))
      (obj "fonts" (persistent! result)))))

;; outline -------------------------------------------------------------------

(defn- outline-items [^PDDocument doc ^PDOutlineNode node seen]
  (loop [^PDOutlineItem item (.getFirstChild node) items []]
    (if (or (nil? item) (some #(identical? % (.getCOSObject item)) @seen))
      items
      (do
        (vswap! seen conj (.getCOSObject item))
        (let [page (try (let [pg (.findDestinationPage item doc)]
                          (when pg
                            (let [i (.indexOf (.getPages doc) pg)]
                              (when (>= i 0) (inc i)))))
                        (catch Exception _ nil))
              action (try (some-> (.getAction item) .getSubType) (catch Exception _ nil))
              entry (obj "title" (.getTitle item)
                         "page" page
                         "action" action
                         "open" (.isNodeOpen item)
                         "children" (outline-items doc item seen))]
          (recur (.getNextSibling item) (conj items entry)))))))

(defmethod execute "outline" [_ ctx]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [outline (.getDocumentOutline (.getDocumentCatalog doc))]
      (obj "hasOutline" (some? outline)
           "items" (if outline (outline-items doc outline (volatile! [])) [])))))

;; annotations ---------------------------------------------------------------

(defmethod execute "annotations" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [[_ _ pages] (selected-pages options (.getNumberOfPages doc))]
      (obj "annotations"
           (vec (for [p pages
                      ^PDAnnotation a (.getAnnotations (.getPage doc (int (dec p))))]
                  (obj "page" p
                       "subtype" (.getSubtype a)
                       "rect" (rect (.getRectangle a))
                       "contents" (.getContents a)
                       "name" (.getAnnotationName a)
                       "flags" (.getAnnotationFlags a))))))))

;; form fields ---------------------------------------------------------------

(defn- field-kind [^PDField f]
  (condp instance? f
    PDTextField "text"
    PDCheckBox "checkbox"
    PDRadioButton "radio"
    PDPushButton "pushbutton"
    PDComboBox "combo"
    PDListBox "list"
    PDSignatureField "signature"
    PDNonTerminalField "nonterminal"
    "other"))

(defn- field-value [^PDField f]
  (cond
    (instance? PDTextField f) (.getValue ^PDTextField f)
    (instance? PDPushButton f) nil
    (instance? PDButton f) (.getValue ^PDButton f)
    (instance? PDChoice f) (vec (.getValue ^PDChoice f))
    :else nil))

(defn- field-options [^PDField f]
  (cond
    (instance? PDPushButton f) nil
    (instance? PDButton f) (vec (sort (.getOnValues ^PDButton f)))
    (instance? PDChoice f) (vec (.getOptions ^PDChoice f))
    :else nil))

(defn- field-entries [fields]
  (mapcat (fn [^PDField f]
            (cons (obj "name" (.getFullyQualifiedName f)
                       "type" (.getFieldType f)
                       "kind" (field-kind f)
                       "value" (field-value f)
                       "options" (field-options f)
                       "flags" (.getFieldFlags f)
                       "readOnly" (.isReadOnly f)
                       "required" (.isRequired f))
                  (when (instance? PDNonTerminalField f)
                    (field-entries (.getChildren ^PDNonTerminalField f)))))
          fields))

(defmethod execute "form-fields" [_ ctx]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [form (.getAcroForm (.getDocumentCatalog doc))]
      (obj "hasAcroForm" (some? form)
           "needAppearances" (if form (.getNeedAppearances form) false)
           "fields" (if form (vec (field-entries (.getFields form))) [])))))

;; form fill -----------------------------------------------------------------

(defn- read-values [^String path]
  (let [f (File. path)]
    (when-not (and (.isFile f) (.canRead f))
      (fail! "io" (str "Cannot read values file: " path)))
    (let [mapper (doto (ObjectMapper.) (.configure JsonParser$Feature/STRICT_DUPLICATE_DETECTION true))
          ^JsonNode node (try (.readTree mapper f)
                              (catch Exception e (fail! "usage" (str "Invalid values JSON: " (.getMessage e)))))]
      (when-not (and node (.isObject node))
        (fail! "usage" "The values file must hold a JSON object"))
      (into (sorted-map)
            (for [^String k (iterator-seq (.fieldNames node))
                  :let [^JsonNode v (.get node k)]]
              [k (cond
                   (.isTextual v) (.asText v)
                   (and (.isArray v) (every? #(.isTextual ^JsonNode %) (iterator-seq (.elements v))))
                   (mapv #(.asText ^JsonNode %) (iterator-seq (.elements v)))
                   :else (fail! "usage" (str "Value of " k " must be a string or an array of strings")))])))))

(defmethod execute "form-fill" [_ {:keys [options values] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [^PDAcroForm form (or (.getAcroForm (.getDocumentCatalog doc))
                               (fail! "no-acroform" "The document has no AcroForm"))]
      (doseq [[name value] values]
        (let [field (or (.getField form ^String name)
                        (fail! "field-not-found" (str "No field named " name)))]
          (if (vector? value)
            (if (instance? PDChoice field)
              (.setValue ^PDChoice field ^List value)
              (fail! "invalid-value" (str "Field " name " does not take a list")))
            (.setValue ^PDField field ^String value))))
      (.save doc (File. ^String (options "--out"))))))

(defmethod summarize "form-fill" [_ {:keys [options values] :as ctx} _]
  (written (options "--out") (password ctx)
           (fn [^PDDocument doc]
             (let [^PDAcroForm form (.getAcroForm (.getDocumentCatalog doc))]
               ["fields" (mapv (fn [name]
                                 (let [f (.getField form ^String name)]
                                   (obj "name" name "kind" (field-kind f) "value" (field-value f))))
                               (keys values))]))))

;; attachments ---------------------------------------------------------------

(defn- name-tree-entries
  "[key value] pairs of a name tree: a node's /Names pairs, then its /Kids."
  [^COSDictionary node seen]
  (when (and node (not (some #(identical? % node) @seen)))
    (vswap! seen conj node)
    (concat
     (let [names (.getCOSArray node COSName/NAMES)]
       (when names
         (for [i (range 0 (dec (.size names)) 2)
               :let [k (.getObject names (int i))]
               :when (instance? COSString k)]
           [(.getString ^COSString k) (.getObject names (int (inc i)))])))
     (let [kids (.getCOSArray node COSName/KIDS)]
       (when kids
         (mapcat #(when (instance? COSDictionary %) (name-tree-entries % seen))
                 (doall (map #(.getObject kids (int %)) (range (.size kids))))))))))

(defn- sanitize-file-name [^String s]
  (let [sb (StringBuilder.)]
    (doseq [cp (iterator-seq (.iterator (.boxed (.codePoints s))))]
      (let [cp (int cp)]
        (if (or (<= (int \a) cp (int \z)) (<= (int \A) cp (int \Z)) (<= (int \0) cp (int \9))
                (#{(int \.) (int \_) (int \-)} cp))
          (.appendCodePoint sb cp)
          (.append sb \_))))
    (str sb)))

(defmethod execute "attachments" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [catalog (.getCOSObject (.getDocumentCatalog doc))
          root (some-> (.getCOSDictionary catalog COSName/NAMES)
                       (.getCOSDictionary COSName/EMBEDDED_FILES))
          out-dir (options "--out-dir")]
      (obj "attachments"
           (vec (map-indexed
                 (fn [i [k v]]
                   (let [spec (when (instance? COSDictionary v) (PDComplexFileSpecification. v))
                         file-name (cond spec (.getFilename spec)
                                         (instance? COSString v) (.getString ^COSString v))
                         ^PDEmbeddedFile ef (when spec
                                              (or (.getEmbeddedFileUnicode spec) (.getEmbeddedFile spec)
                                                  (.getEmbeddedFileDos spec) (.getEmbeddedFileMac spec)
                                                  (.getEmbeddedFileUnix spec)))
                         data (when ef (.toByteArray ef))
                         params (when ef (.getCOSDictionary (.getCOSObject ef) COSName/PARAMS))
                         file (when (and data out-dir)
                                (format "%03d-%s" (inc i)
                                        (sanitize-file-name (or file-name k "attachment"))))]
                     (when file
                       (Files/write (.toPath (child-file out-dir file)) ^bytes data
                                    ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0)))
                     (obj "name" k
                          "fileName" file-name
                          "description" (when spec (.getFileDescription spec))
                          "subtype" (when ef (.getSubtype ef))
                          "size" (when (and params (.containsKey params COSName/SIZE))
                                   (.getInt params COSName/SIZE))
                          "length" (when data (alength ^bytes data))
                          "sha256" (when data (sha256-hex data))
                          "file" file)))
                 (name-tree-entries root (volatile! []))))))))

;; merge ---------------------------------------------------------------------

(defmethod execute "merge" [_ {:keys [options positional]}]
  (let [merger (PDFMergerUtility.)]
    (doseq [^String p positional] (.addSource merger (File. p)))
    (.setDestinationFileName merger (options "--out"))
    (.mergeDocuments merger (IOUtils/createMemoryOnlyStreamCache))))

(defmethod summarize "merge" [_ {:keys [options positional]} _]
  (written (options "--out") nil (fn [_] ["inputCount" (count positional)])))

;; split ---------------------------------------------------------------------

(defmethod execute "split" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [n (.getNumberOfPages doc)
          ranges (mapv #(resolve-range % n) (options "--ranges"))]
      (doseq [[s e] ranges]
        (when (> s e) (fail! "page-range" (str "Range selects no page of " n))))
      (vec (for [[i [s e]] (map-indexed vector ranges)]
             (let [file (format "part-%03d.pdf" (inc i))
                   parts (.split (doto (Splitter.)
                                   (.setStartPage (int s))
                                   (.setEndPage (int e))
                                   (.setSplitAtPage (int (inc (- e s)))))
                                 doc)]
               (try
                 (.save ^PDDocument (first parts) (child-file (options "--out-dir") file))
                 (finally (doseq [^PDDocument d parts] (.close d))))
               {:range (str s "-" e) :file file}))))))

(defmethod summarize "split" [_ {:keys [options] :as ctx} parts]
  (let [dir (options "--out-dir")]
    (obj "outDir" dir
         "parts" (mapv (fn [{:keys [range file]}]
                         (let [path (str (child-file dir file))]
                           (with-open [d (open-pdf path (password ctx))]
                             (obj "range" range "file" file "bytes" (file-length path)
                                  "pageCount" (.getNumberOfPages d)))))
                       parts))))

;; rotate --------------------------------------------------------------------

(defmethod execute "rotate" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [[_ _ pages] (selected-pages options (.getNumberOfPages doc))
          angle (long (options "--angle"))]
      (doseq [p pages]
        (let [page (.getPage doc (int (dec p)))]
          (.setRotation page (int (mod (+ (.getRotation page) angle) 360)))))
      (.save doc (File. ^String (options "--out"))))))

(defmethod summarize "rotate" [_ {:keys [options] :as ctx} _]
  (written (options "--out") (password ctx)
           (fn [^PDDocument doc] ["rotations" (mapv #(.getRotation ^PDPage %) (.getPages doc))])))

;; encrypt / decrypt ---------------------------------------------------------

(defn- access-permission [granted]
  (let [ap (AccessPermission.)]
    (when granted
      (.setCanPrint ap (contains? granted "print"))
      (.setCanModify ap (contains? granted "modify"))
      (.setCanExtractContent ap (contains? granted "extract"))
      (.setCanModifyAnnotations ap (contains? granted "annotate"))
      (.setCanFillInForm ap (contains? granted "fill-in"))
      (.setCanExtractForAccessibility ap (contains? granted "extract-for-accessibility"))
      (.setCanAssembleDocument ap (contains? granted "assemble"))
      (.setCanPrintFaithful ap (contains? granted "print-faithful")))
    ap))

(defmethod execute "encrypt" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (let [policy (doto (StandardProtectionPolicy. ^String (options "--owner-password")
                                                  ^String (or (options "--user-password") "")
                                                  (access-permission (options "--permissions")))
                   (.setEncryptionKeyLength (int (or (options "--key-length") 256)))
                   (.setPreferAES true))]
      (.protect doc policy)
      (.save doc (File. ^String (options "--out"))))))

(defmethod summarize "encrypt" [_ {:keys [options]} _]
  (written (options "--out") (options "--owner-password")
           (fn [doc] ["encryption" (encryption-summary doc)])))

(defmethod execute "decrypt" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (when-not (.isEncrypted doc)
      (fail! "not-encrypted" "The document is not encrypted"))
    (when-not (.isOwnerPermission (.getCurrentAccessPermission doc))
      (fail! "owner-password-required" "Decryption needs the owner password"))
    (.setAllSecurityToBeRemoved doc true)
    (.save doc (File. ^String (options "--out")))))

(defmethod summarize "decrypt" [_ {:keys [options]} _]
  (written (options "--out") nil (fn [^PDDocument doc] ["encrypted" (.isEncrypted doc)])))

;; create --------------------------------------------------------------------

(def default-create-text "Hello from pdfcli")

(defn- create-image ^BufferedImage []
  (let [image (BufferedImage. 16 16 BufferedImage/TYPE_INT_RGB)]
    (dotimes [y 16]
      (dotimes [x 16]
        (.setRGB image x y (bit-or (bit-shift-left (* 16 x) 16) (bit-shift-left (* 16 y) 8) 128))))
    image))

(defmethod execute "create" [_ {:keys [options]}]
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)
          _ (.addPage doc page)
          image (LosslessFactory/createFromImage doc (create-image))]
      (with-open [cs (PDPageContentStream. doc page)]
        (.setNonStrokingColor cs (float 0.2) (float 0.4) (float 0.8))
        (.setStrokingColor cs (float 0) (float 0) (float 0))
        (.setLineWidth cs (float 2))
        (.addRect cs (float 72) (float 620) (float 200) (float 60))
        (.fillAndStroke cs)
        (.drawImage cs image (float 72) (float 520) (float 64) (float 64))
        (.beginText cs)
        (.setFont cs (PDType1Font. Standard14Fonts$FontName/HELVETICA) (float 12))
        (.newLineAtOffset cs (float 72) (float 720))
        (.showText cs (or (options "--text") default-create-text))
        (.endText cs))
      (.save doc (File. ^String (options "--out"))))))

(defmethod summarize "create" [_ {:keys [options]} _]
  (written (options "--out") nil))

;; save ----------------------------------------------------------------------

(defmethod execute "save" [_ {:keys [options] :as ctx}]
  (with-open [doc (load-doc (input ctx) (password ctx))]
    (.save doc (File. ^String (options "--out"))
           (if (options "--compress")
             CompressParameters/DEFAULT_COMPRESSION
             CompressParameters/NO_COMPRESSION))))

(defmethod summarize "save" [_ {:keys [options] :as ctx} _]
  (written (options "--out") (password ctx)
           (fn [_] ["compressed" (boolean (options "--compress"))])))

;; ---------------------------------------------------------------------------
;; Driver

(def implementation
  (obj "name" "pdfbox-jvm"
       "library" "Apache PDFBox"
       "libraryVersion" (or (some-> (Package/getPackage "org.apache.pdfbox.pdmodel")
                                    .getImplementationVersion)
                            (try (org.apache.pdfbox.util.Version/getVersion)
                                 (catch Throwable _ nil)))
       "runtime" (str "Java " (System/getProperty "java.version"))))

(defn- prepare!
  "Checks inputs and output locations before anything is timed."
  [command {:keys [options positional] :as ctx}]
  (doseq [p positional] (check-input! p))
  (when-let [dir (options "--out-dir")]
    (let [f (File. ^String dir)]
      (.mkdirs f)
      (when-not (.isDirectory f) (fail! "io" (str "Cannot create output directory: " dir)))))
  (when-let [out (options "--out")]
    (let [parent (.getAbsoluteFile (.getParentFile (.getAbsoluteFile (File. ^String out))))]
      (when-not (.isDirectory parent)
        (fail! "io" (str "Output directory does not exist: " parent)))))
  (cond-> ctx
    (= command "form-fill") (assoc :values (read-values (options "--values")))))

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v) m (quot n 2)]
    (if (odd? n) (nth v m) [(nth v (dec m)) (nth v m)])))

(defn- allocated-bytes ^long []
  (.getCurrentThreadAllocatedBytes
   ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)))

(defn- run-timed [command ctx warmup repeat]
  (dotimes [_ warmup] (execute command ctx))
  (let [runs (vec (for [_ (range repeat)]
                    (let [a0 (allocated-bytes)
                          t0 (System/nanoTime)
                          value (execute command ctx)
                          t1 (System/nanoTime)
                          a1 (allocated-bytes)]
                      {:value value :nanos (- t1 t0) :bytes (- a1 a0)})))
        nanos (map :nanos runs)
        bytes (map :bytes runs)
        mn (median nanos)
        mb (median bytes)]
    {:value (:value (peek runs))
     :timing (obj "warmup" warmup
                  "repeat" repeat
                  "iterationsMs" (mapv json/millis nanos)
                  "medianMs" (json/millis (if (vector? mn) (/ (+ (first mn) (second mn)) 2.0) mn))
                  "allocatedBytes" (vec bytes)
                  "medianAllocatedBytes" (if (vector? mb)
                                           (+ (long (first mb))
                                              (quot (- (long (second mb)) (long (first mb))) 2))
                                           mb))}))

(defn- error-envelope [command code ^Throwable e]
  (obj "ok" false
       "command" command
       "implementation" implementation
       "error" (obj "code" code
                    "message" (or (.getMessage e) (str (class e)))
                    "exception" (.getName (class (or (when (instance? clojure.lang.ExceptionInfo e)
                                                       (.getCause e))
                                                     e))))))

(defn run
  "Runs pdfcli argv and returns {:exit code :out json-line}."
  [args]
  (let [[command & more] args
        known (contains? commands command)
        result
        (try
          (when-not known
            (fail! "usage" (if command (str "Unknown command: " command) "No command given")))
          (let [{:keys [options] :as ctx} (parse-args command more)
                ctx (prepare! command ctx)
                timing? (or (contains? options "--warmup") (contains? options "--repeat"))
                {:keys [value timing]}
                (try
                  (if timing?
                    (run-timed command ctx (or (options "--warmup") 0) (or (options "--repeat") 1))
                    {:value (execute command ctx)})
                  (catch clojure.lang.ExceptionInfo e (throw e))
                  (catch Throwable e (fail! "operation-failed" (or (.getMessage e) (str (class e))) e)))
                summary (try (summarize command ctx value)
                             (catch clojure.lang.ExceptionInfo e (throw e))
                             (catch Throwable e
                               (fail! "operation-failed" (or (.getMessage e) (str (class e))) e)))]
            {:exit 0
             :doc (obj "ok" true
                       "command" command
                       "implementation" implementation
                       "result" summary
                       "timing" timing)})
          (catch clojure.lang.ExceptionInfo e
            (let [code (or (::code (ex-data e)) "operation-failed")]
              {:exit (get exit-codes code 1)
               :doc (error-envelope (when known command) code e)}))
          (catch Throwable e
            {:exit 1 :doc (error-envelope (when known command) "operation-failed" e)}))]
    {:exit (:exit result) :out (str (json/write-str (:doc result)) "\n")}))

(defn -main [& args]
  (let [{:keys [exit out]} (run args)
        w (OutputStreamWriter. System/out StandardCharsets/UTF_8)]
    (.write w ^String out)
    (.flush w)
    (shutdown-agents)
    (System/exit exit)))
