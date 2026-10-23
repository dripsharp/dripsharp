;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns pdfcli.main-test
  "Runs every pdfcli command of validation/pdfcarton-cli/contract.md against
  PDFBox test-resource PDFs from the pinned research/pdfbox checkout. Run from
  the repository root: clojure -M:pdfcli-test"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [pdfcli.json :as json]
            [pdfcli.main :as main])
  (:import (com.fasterxml.jackson.databind ObjectMapper)
           (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util LinkedHashMap List Locale Locale$Category Map)
           (org.apache.pdfbox.cos COSName)
           (org.apache.pdfbox.pdmodel PDDocument PDPage PDResources)
           (org.apache.pdfbox.pdmodel.common PDRectangle)
           (org.apache.pdfbox.pdmodel.encryption AccessPermission)
           (org.apache.pdfbox.pdmodel.font PDType1Font Standard14Fonts$FontName)
           (org.apache.pdfbox.pdmodel.graphics.form PDFormXObject)
           (org.apache.pdfbox.pdmodel.graphics.image LosslessFactory)))

(def resources "research/pdfbox/pdfbox/src/test/resources/")

(defn- fixture [relative]
  (let [f (io/file resources relative)]
    (when-not (.isFile f)
      (throw (ex-info "Missing PDFBox test resource; check out research/pdfbox at its gitlink"
                      {:file (str f)})))
    (str f)))

(def cweb (delay (fixture "input/cweb.pdf")))
(def hello (delay (fixture "input/hello3.pdf")))
(def jpeg (delay (fixture "input/merge/jpegrgb.pdf")))
(def xmp-doc (delay (fixture "input/FC60_Times.pdf")))
(def outline-doc (delay (fixture "org/apache/pdfbox/pdmodel/with_outline.pdf")))
(def annotation-doc
  (delay (fixture "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf")))
(def form-doc
  (delay (fixture "org/apache/pdfbox/pdmodel/interactive/form/AcroFormsBasicFields.pdf")))
(def attachment-doc
  (delay (fixture "org/apache/pdfbox/pdmodel/common/testPDF_multiFormatEmbFiles.pdf")))
(def encrypted-doc (delay (fixture "org/apache/pdfbox/encryption/PasswordSample-256bit.pdf")))
(def owner-password "owner")

(def ^:private out-root
  (delay (str (Files/createTempDirectory "pdfcli-test" (make-array FileAttribute 0)))))

(defn- out [name] (str (io/file @out-root name)))

(defn- parse ^Map [^String text]
  (.readValue (ObjectMapper.) text LinkedHashMap))

(defn- run! [& args]
  (let [{:keys [exit out]} (main/run (vec args))]
    (is (str/ends-with? out "\n"))
    (is (= 1 (count (str/split-lines out))) "one JSON line")
    {:exit exit :json (parse out) :text out}))

(defn- key-order [^Map m] (vec (keys m)))

(def envelope-keys ["ok" "command" "implementation" "result" "timing"])
(def error-keys ["ok" "command" "implementation" "error"])
(def written-keys ["output" "bytes" "pageCount"])

(def ^:dynamic *library-version*
  "implementation.libraryVersion the implementation under test reports."
  "3.0.8")

(defn- ok! [expected-result-keys & args]
  (let [{:keys [exit json] :as r} (apply run! args)]
    (is (= 0 exit) (str args " -> " (get json "error")))
    (is (= envelope-keys (key-order json)))
    (is (= true (get json "ok")))
    (is (= (first args) (get json "command")))
    (is (= ["name" "library" "libraryVersion" "runtime"] (key-order (get json "implementation"))))
    (is (= *library-version* (get-in json ["implementation" "libraryVersion"])))
    (is (nil? (get json "timing")))
    (is (= expected-result-keys (key-order (get json "result"))))
    (assoc r :result (get json "result"))))

(defn- error! [exit code & args]
  (let [{:keys [json] :as r} (apply run! args)]
    (is (= exit (:exit r)) (str args))
    (is (= error-keys (key-order json)))
    (is (= false (get json "ok")))
    (is (= ["code" "message" "exception"] (key-order (get json "error"))))
    (is (= code (get-in json ["error" "code"])) (str args))
    r))

(defn- first-entry ^Map [result k]
  (let [^List xs (get result k)]
    (is (seq xs) (str k " is not empty"))
    (first xs)))

;; ---------------------------------------------------------------------------
;; One invocation per command, run twice for determinism.

(defn- scenarios []
  [["info" #(let [{:keys [result]}
                  (ok! ["version" "headerVersion" "pageCount" "encrypted" "encryption" "info" "xmp"
                        "pages"]
                       "info" @xmp-doc)]
              (is (= ["Title" "Author" "Subject" "Keywords" "Creator" "Producer" "CreationDate"
                      "ModDate" "Trapped" "custom"]
                     (key-order (get result "info"))))
              (is (= ["length" "sha256" "parsed" "schemas" "dcTitle" "dcCreators" "pdfProducer"
                      "xmpCreatorTool" "pdfaPart" "pdfaConformance"]
                     (key-order (get result "xmp"))))
              (is (= true (get-in result ["xmp" "parsed"])))
              (is (= ["page" "mediaBox" "cropBox" "rotation"]
                     (key-order (first-entry result "pages"))))
              (is (= (get result "pageCount") (count (get result "pages")))))]
   ["info-encrypted"
    #(let [{:keys [result]} (ok! ["version" "headerVersion" "pageCount" "encrypted" "encryption"
                                  "info" "xmp" "pages"]
                                 "info" "--password" owner-password @encrypted-doc)]
       (is (= true (get result "encrypted")))
       (is (= ["filter" "version" "revision" "keyLength" "permissions" "ownerPermission"]
              (key-order (get result "encryption"))))
       (is (= 256 (get-in result ["encryption" "keyLength"])))
       (is (= true (get-in result ["encryption" "ownerPermission"]))))]
   ["text" #(let [{:keys [result]} (ok! ["startPage" "endPage" "sortByPosition" "text"]
                                        "text" "--pages" "1-2" "--sort" @cweb)]
              (is (= [1 2 true] (mapv (fn [k] (get result k)) ["startPage" "endPage" "sortByPosition"])))
              (is (str/includes? (get result "text") "The CWEB System of Structured Documentation"))
              (is (not (str/includes? (get result "text") "\r"))))]
   ["text-clamped" #(let [{:keys [result]} (ok! ["startPage" "endPage" "sortByPosition" "text"]
                                                "text" "--pages" "40-" @cweb)]
                      (is (= [40 28 ""] (mapv (fn [k] (get result k)) ["startPage" "endPage" "text"]))))]
   ["render" #(let [{:keys [result]} (ok! ["dpi" "imageType" "outDir" "pages"]
                                          "render" "--pages" "1-2" "--dpi" "36" "--image-type" "gray"
                                          "--out-dir" (out "render") @cweb)
                    page (first-entry result "pages")]
                (is (= ["page" "width" "height" "file" "sha256"] (key-order page)))
                (is (= [1 "page-0001.png" 297 420]
                       [(get page "page") (get page "file") (get page "width") (get page "height")]))
                (is (= "gray" (get result "imageType")))
                (is (= 2 (count (get result "pages"))))
                (is (.isFile (io/file (out "render") "page-0002.png"))))]
   ["render-no-write" #(let [{:keys [result]} (ok! ["dpi" "imageType" "outDir" "pages"]
                                                   "render" "--pages" "3" "--no-write" @cweb)
                             page (first-entry result "pages")]
                         (is (nil? (get result "outDir")))
                         (is (= [3 nil nil] [(get page "page") (get page "file") (get page "sha256")])))]
   ["images" #(let [{:keys [result]} (ok! ["images"] "images" "--out-dir" (out "images") @jpeg)
                    image (first-entry result "images")]
                (is (= ["page" "name" "width" "height" "colorSpace" "bitsPerComponent" "filters"
                        "stencil" "suffix" "decodedSha256" "file" "error"]
                       (key-order image)))
                (is (= ["DCTDecode"] (get image "filters")))
                (is (re-matches #"[0-9a-f]{64}" (get image "decodedSha256")))
                (is (nil? (get image "error")))
                (is (.isFile (io/file (out "images") (get image "file")))))]
   ["fonts" #(let [{:keys [result]} (ok! ["fonts"] "fonts" "--pages" "1" @cweb)
                   font (first-entry result "fonts")]
               (is (= ["page" "name" "baseFont" "subtype" "embedded" "encoding" "toUnicode" "error"]
                      (key-order font)))
               (is (= ["F1" "Type1" true] [(get font "name") (get font "subtype") (get font "embedded")])))]
   ["outline" #(let [{:keys [result]} (ok! ["hasOutline" "items"] "outline" @outline-doc)
                     item (first-entry result "items")]
                 (is (= ["title" "page" "action" "open" "children"] (key-order item)))
                 (is (= ["First level 1" 1] [(get item "title") (get item "page")]))
                 (is (seq (get item "children"))))]
   ["annotations" #(let [{:keys [result]} (ok! ["annotations"] "annotations" @annotation-doc)
                         annotation (first-entry result "annotations")]
                     (is (= ["page" "subtype" "rect" "contents" "name" "flags"] (key-order annotation)))
                     (is (= 4 (count (get annotation "rect")))))]
   ["form-fields" #(let [{:keys [result]} (ok! ["hasAcroForm" "needAppearances" "fields"]
                                               "form-fields" @form-doc)
                         field (first-entry result "fields")]
                     (is (= ["name" "type" "kind" "value" "options" "flags" "readOnly" "required"]
                            (key-order field)))
                     (is (= #{"text" "checkbox" "radio" "pushbutton" "combo" "list" "signature"}
                            (set (map (fn [^Map f] (get f "kind")) (get result "fields"))))))]
   ["form-fill" #(do (spit (out "values.json")
                           (str "{\"TextField\":\"Filled by pdfcli\",\"Checkbox\":\"Yes\","
                                "\"RadioButtonGroup\":\"RadioButton02\","
                                "\"ComboBox-DefaultValue\":\"Opt02\",\"ListBox-DefaultValue\":\"Opt03\"}"))
                     (let [{:keys [result]} (ok! (conj written-keys "fields")
                                                 "form-fill" "--values" (out "values.json")
                                                 "--out" (out "filled.pdf") @form-doc)]
                       (is (= [["Checkbox" "checkbox" "Yes"]
                               ["ComboBox-DefaultValue" "combo" ["Opt02"]]
                               ["ListBox-DefaultValue" "list" ["Opt03"]]
                               ["RadioButtonGroup" "radio" "RadioButton02"]
                               ["TextField" "text" "Filled by pdfcli"]]
                              (mapv (fn [^Map f] [(get f "name") (get f "kind") (get f "value")])
                                    (get result "fields"))))))]
   ["attachments" #(let [{:keys [result]} (ok! ["attachments"] "attachments" "--out-dir"
                                               (out "attachments") @attachment-doc)
                         a (first-entry result "attachments")]
                     (is (= ["name" "fileName" "description" "subtype" "size" "length" "sha256" "file"]
                            (key-order a)))
                     (is (= (get a "length")
                            (.length (io/file (out "attachments") (get a "file"))))))]
   ["merge" #(let [{:keys [result]} (ok! (conj written-keys "inputCount")
                                         "merge" "--out" (out "merged.pdf") @cweb @hello)]
               (is (= [29 2] [(get result "pageCount") (get result "inputCount")])))]
   ["split" #(let [{:keys [result]} (ok! ["outDir" "parts"] "split" "--ranges" "1-2,5,27-"
                                         "--out-dir" (out "split") @cweb)]
               (is (= [["1-2" "part-001.pdf" 2] ["5-5" "part-002.pdf" 1] ["27-28" "part-003.pdf" 2]]
                      (mapv (fn [^Map p] [(get p "range") (get p "file") (get p "pageCount")])
                            (get result "parts"))))
               (is (= ["range" "file" "bytes" "pageCount"] (key-order (first-entry result "parts")))))]
   ["rotate" #(let [{:keys [result]} (ok! (conj written-keys "rotations")
                                          "rotate" "--angle" "-90" "--pages" "2-3"
                                          "--out" (out "rotated.pdf") @cweb)]
                (is (= [0 270 270 0] (take 4 (get result "rotations")))))]
   ["encrypt" #(let [{:keys [result]} (ok! (conj written-keys "encryption")
                                           "encrypt" "--owner-password" "own" "--user-password" "usr"
                                           "--key-length" "128" "--permissions" "print,extract"
                                           "--out" (out "encrypted.pdf") @hello)]
                 (is (= ["Standard" 4 4 128]
                        (mapv (fn [k] (get-in result ["encryption" k]))
                              ["filter" "version" "revision" "keyLength"]))))]
   ["decrypt" #(let [{:keys [result]} (ok! (conj written-keys "encrypted")
                                           "decrypt" "--password" owner-password
                                           "--out" (out "decrypted.pdf") @encrypted-doc)]
                 (is (= false (get result "encrypted"))))]
   ["create" #(do (ok! written-keys "create" "--out" (out "created.pdf"))
                  (let [{:keys [result]} (ok! ["startPage" "endPage" "sortByPosition" "text"]
                                              "text" (out "created.pdf"))]
                    (is (= "Hello from pdfcli\n" (get result "text"))))
                  (let [{:keys [result]} (ok! ["images"] "images" (out "created.pdf"))]
                    (is (= [16 16 "DeviceRGB" ["FlateDecode"]]
                           (mapv (fn [k] (get (first-entry result "images") k))
                                 ["width" "height" "colorSpace" "filters"])))))]
   ["save" #(do (let [{:keys [result]} (ok! (conj written-keys "compressed")
                                            "save" "--out" (out "saved.pdf") @cweb)]
                  (is (= [28 false] [(get result "pageCount") (get result "compressed")])))
                (let [{:keys [result]} (ok! (conj written-keys "compressed")
                                            "save" "--compress" "--out" (out "saved-compressed.pdf") @cweb)]
                  (is (= true (get result "compressed")))))]])

(defn- normalize
  "Drops the fields the contract marks as nondeterministic."
  [^Map json]
  (let [m (LinkedHashMap. json)]
    (.remove m "implementation")
    (when-let [^Map t (get m "timing")]
      (.put m "timing" (select-keys t ["warmup" "repeat"])))
    (into {} m)))

(deftest every-command-reports-its-contract-shape
  (doseq [[name f] (scenarios)]
    (testing name (f))))

(deftest output-is-deterministic-across-runs
  (let [capture (fn []
                  (let [lines (atom [])]
                    (with-redefs [main/run (let [run main/run]
                                             (fn [args]
                                               (let [r (run args)]
                                                 (swap! lines conj (normalize (parse (:out r))))
                                                 r)))]
                      (doseq [[_ f] (scenarios)] (f)))
                    @lines))
        first-run (capture)
        second-run (capture)]
    (is (= (count first-run) (count second-run)))
    (doseq [[a b] (map vector first-run second-run)]
      (is (= a b) (str (get a "command"))))))

(deftest errors-have-exit-codes-and-error-json
  (error! 2 "usage")
  (error! 2 "usage" "no-such-command" @cweb)
  (error! 2 "usage" "text" "--dpi" "72" @cweb)
  (error! 2 "usage" "text" "--pages" "3-1" @cweb)
  (error! 2 "usage" "text" "--pages" "1" "--pages" "2" @cweb)
  (error! 2 "usage" "render" @cweb)
  (error! 2 "usage" "render" "--no-write" "--out-dir" (out "r") @cweb)
  (error! 2 "usage" "info" "--repeat" "0" (out "missing.pdf"))
  (error! 2 "usage" "merge" "--password" "x" "--out" (out "x.pdf") @cweb)
  (error! 2 "usage" "create" "--out" (out "x.pdf") @cweb)
  (error! 2 "usage" "rotate" "--angle" "45" "--out" (out "x.pdf") @cweb)
  (error! 2 "usage" "info" @cweb @hello)
  (error! 3 "io" "info" (out "missing.pdf"))
  (error! 4 "invalid-password" "info" "--password" "wrong" @encrypted-doc)
  (error! 4 "invalid-password" "info" @encrypted-doc)
  (error! 4 "owner-password-required" "decrypt" "--password" "user" "--out" (out "x.pdf") @encrypted-doc)
  (error! 5 "load-failed" "info" (fixture "input/cweb.pdf.txt"))
  (error! 1 "page-range" "split" "--ranges" "30-" "--out-dir" (out "split-empty") @cweb)
  (error! 1 "not-encrypted" "decrypt" "--out" (out "x.pdf") @cweb)
  (spit (out "bad-values.json") "{\"NoSuchField\":\"x\"}")
  (error! 1 "field-not-found" "form-fill" "--values" (out "bad-values.json") "--out" (out "x.pdf") @form-doc)
  (spit (out "list-values.json") "{\"TextField\":[\"x\"]}")
  (error! 1 "invalid-value" "form-fill" "--values" (out "list-values.json") "--out" (out "x.pdf") @form-doc)
  (spit (out "multi-values.json") "{\"ListBox-DefaultValue\":[\"Opt01\"]}")
  (error! 1 "operation-failed" "form-fill" "--values" (out "multi-values.json") "--out" (out "x.pdf") @form-doc)
  (spit (out "malformed-values.json") "{\"TextField\":1}")
  (error! 2 "usage" "form-fill" "--values" (out "malformed-values.json") "--out" (out "x.pdf") @form-doc)
  (spit (out "trailing-values.json") "{\"TextField\":\"x\"} {}")
  (error! 2 "usage" "form-fill" "--values" (out "trailing-values.json") "--out" (out "trailing.pdf") @form-doc)
  (spit (out "garbage-values.json") "{\"TextField\":\"x\"} garbage")
  (error! 2 "usage" "form-fill" "--values" (out "garbage-values.json") "--out" (out "trailing.pdf") @form-doc)
  (is (not (.exists (io/file (out "trailing.pdf")))))
  (error! 1 "no-acroform" "form-fill" "--values" (out "bad-values.json") "--out" (out "x.pdf") @cweb))

(deftest timing-mode-reports-warm-median-and-allocations
  (let [{:keys [exit json]} (run! "text" "--warmup" "2" "--repeat" "3" @cweb)
        ^Map timing (get json "timing")]
    (is (= 0 exit))
    (is (= ["warmup" "repeat" "iterationsMs" "medianMs" "allocatedBytes" "medianAllocatedBytes"]
           (key-order timing)))
    (is (= [2 3] [(get timing "warmup") (get timing "repeat")]))
    (is (= 3 (count (get timing "iterationsMs")) (count (get timing "allocatedBytes"))))
    (is (pos? (double (get timing "medianMs"))))
    (is (= (double (nth (sort (map double (get timing "iterationsMs"))) 1))
           (double (get timing "medianMs"))))
    (is (every? #(pos? (long %)) (get timing "allocatedBytes")))
    (is (= (nth (sort (get timing "allocatedBytes")) 1) (get timing "medianAllocatedBytes")))
    (is (str/includes? (get-in json ["result" "text"]) "CWEB")))
  (let [{:keys [json]} (run! "render" "--repeat" "2" "--pages" "1" "--no-write" @cweb)
        ^Map timing (get json "timing")]
    (is (= [0 2] [(get timing "warmup") (get timing "repeat")]))
    (is (= (/ (reduce + (get timing "allocatedBytes")) 2)
           (get timing "medianAllocatedBytes")))))

(deftest process-exit-codes-and-stdout-match-the-contract
  (let [cli (fn [& args]
              (apply shell/sh "clojure" "-M:pdfcli" args))
        ok (cli "info" @hello)
        again (cli "info" @hello)
        bad (cli "info" "--password" "wrong" @encrypted-doc)]
    (is (= 0 (:exit ok)))
    (is (= envelope-keys (key-order (parse (:out ok)))))
    (is (= (normalize (parse (:out ok))) (normalize (parse (:out again)))))
    (is (= 4 (:exit bad)))
    (is (= "invalid-password" (get-in (parse (:out bad)) ["error" "code"])))))

;; ---------------------------------------------------------------------------
;; Focused checks of the contract's finer rules.

(deftest canonical-json-encoding-follows-the-contract
  (is (= (str "{\"s\":\"q\\\"\\\\/\\u0001\\n\\té\u007f\","
              "\"f\":[612,595.276,0.1,0,null,null,10000000000,0.000015,1.4,-12.5],"
              "\"m\":[1.235,0.5,0],"
              "\"k\":{\"B\":3,\"a\":2,\"b\":1},"
              "\"n\":null,\"t\":true,\"i\":[-7,4294967296],"
              "\"u\":\"" (char 0xFFFD) "x😀" (char 0xFFFD) "\"}")
         (json/write-str
          (json/obj "s" (str "q\"\\/" (char 1) "\n\té\u007f")
                    "f" [(float 612) (float 595.276) (float 0.1) (float -0.0) Float/NaN
                         Float/POSITIVE_INFINITY (float 1e10) (float 1.5e-5) (float 1.4) (float -12.5)]
                    "m" [(json/millis 1234567) (json/millis 500000) (json/millis 0)]
                    "k" (json/sorted-obj {"b" 1 "a" 2 "B" 3})
                    "n" nil
                    "t" true
                    "i" [(int -7) 4294967296]
                    "u" (str (char 0xD800) "x😀" (char 0xDC00)))))))

(defn- nested-resources-pdf
  "A page whose forms nest, share a stream and refer back to their parent."
  [path]
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)
          image (LosslessFactory/createFromImage doc (BufferedImage. 2 2 BufferedImage/TYPE_INT_RGB))
          font (PDType1Font. Standard14Fonts$FontName/HELVETICA)
          form (fn [] (doto (PDFormXObject. doc)
                        (.setBBox (PDRectangle. 10 10))
                        (.setResources (PDResources.))))
          ^PDFormXObject fm1 (form)
          ^PDFormXObject fm2 (form)
          page-resources (PDResources.)]
      (doto (.getResources fm1)
        (.put (COSName/getPDFName "F2") font)
        (.put (COSName/getPDFName "ImA") image)
        (.put (COSName/getPDFName "Fm2") fm2))
      (doto (.getResources fm2)
        (.put (COSName/getPDFName "ImB") image)
        (.put (COSName/getPDFName "Back") fm1))
      (doto page-resources
        (.put (COSName/getPDFName "F1") font)
        (.put (COSName/getPDFName "Im9") image)
        (.put (COSName/getPDFName "Fm1") fm1)
        (.put (COSName/getPDFName "Fm3") fm2))
      (.setResources page page-resources)
      (.addPage doc page)
      (.save doc (io/file path)))))

(deftest resource-walk-descends-forms-and-stops-cycles
  (nested-resources-pdf (out "nested.pdf"))
  (let [{images :result} (ok! ["images"] "images" (out "nested.pdf"))
        {fonts :result} (ok! ["fonts"] "fonts" (out "nested.pdf"))]
    (is (= ["Fm1/Fm2/ImB" "Fm1/ImA" "Fm3/Back/ImA" "Fm3/ImB" "Im9"]
           (mapv (fn [^Map i] (get i "name")) (get images "images"))))
    (is (= ["F1" "Fm1/F2" "Fm3/Back/F2"]
           (mapv (fn [^Map f] (get f "name")) (get fonts "fonts"))))))

(defn- permission-bytes [granted]
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
    (.getPermissionBytes ap)))

(def ^:private info-keys
  ["version" "headerVersion" "pageCount" "encrypted" "encryption" "info" "xmp" "pages"])

(deftest encrypt-honours-passwords-and-permissions
  (doseq [[key-length permissions] [["40" nil] ["128" ""] ["256" "print,extract,assemble"]]]
    (testing (str key-length " " (pr-str permissions))
      (let [file (out (str "encrypted-" key-length ".pdf"))
            expected (permission-bytes
                      (when permissions (set (remove str/blank? (str/split permissions #",")))))]
        (apply ok! (conj written-keys "encryption")
               (concat ["encrypt" "--owner-password" "own" "--user-password" "usr"
                        "--key-length" key-length]
                       (when permissions ["--permissions" permissions])
                       ["--out" file @hello]))
        (let [{user :result} (ok! info-keys "info" "--password" "usr" file)
              {owner :result} (ok! info-keys "info" "--password" "own" file)]
          ;; AccessPermission.isOwnerPermission() is true whenever every permission is granted.
          (is (= [(Integer/parseInt key-length) expected (= expected (permission-bytes nil))]
                 (mapv (fn [k] (get-in user ["encryption" k]))
                       ["keyLength" "permissions" "ownerPermission"])))
          (is (= true (get-in owner ["encryption" "ownerPermission"]))))
        (error! 4 "invalid-password" "info" "--password" "wrong" file)))))

(deftest numbered-file-names-use-ascii-digits-in-any-locale
  (let [saved (Locale/getDefault Locale$Category/FORMAT)]
    (try
      (Locale/setDefault Locale$Category/FORMAT (Locale/forLanguageTag "ar-EG-u-nu-arab"))
      (let [{:keys [result]} (ok! ["outDir" "parts"] "split" "--ranges" "1" "--out-dir"
                                  (out "split-locale") @hello)]
        (is (= "part-001.pdf" (get (first (get result "parts")) "file"))))
      (let [{:keys [result]} (ok! ["dpi" "imageType" "outDir" "pages"] "render" "--dpi" "9"
                                  "--out-dir" (out "render-locale") @hello)]
        (is (= "page-0001.png" (get (first (get result "pages")) "file"))))
      (finally (Locale/setDefault Locale$Category/FORMAT saved)))))

(deftest jpx-images-decode-through-the-oracle
  ;; The JVM classpath carries jai-imageio-jpeg2000, as PDFBox documents.
  (when (= "3.0.8" *library-version*)
    (doseq [name ["JPXTestRGB.pdf" "JPXTestGrey.pdf" "JPXTestCMYK.pdf"]]
      (let [doc (str "research/pdfbox/tools/src/test/resources/input/ImageIOUtil/" name)
            dir (out (str "jpx-" name))
            {:keys [result]} (ok! ["images"] "images" "--out-dir" dir doc)
            image (first-entry result "images")]
        (is (= ["JPXDecode"] (vec (get image "filters"))) name)
        (is (nil? (get image "error")) name)
        (is (string? (get image "decodedSha256")) name)
        (is (some? (javax.imageio.ImageIO/read (io/file dir (get image "file")))) name)))))

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f) (doseq [child (.listFiles f)] (delete-tree! child)))
  (.delete f))

(defn -main [& _]
  (let [{:keys [fail error]} (try (run-tests 'pdfcli.main-test)
                                  (finally (when (realized? out-root)
                                             (delete-tree! (io/file @out-root)))))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
