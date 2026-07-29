(ns dripsharp.util
  "Small product-neutral helpers shared by translation and validation code."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(defn read-single-edn-string!
  "Reads exactly one EDN value from text and rejects empty or trailing input."
  [text]
  (try
    (let [eof (Object.)]
      (with-open [reader (PushbackReader. (StringReader. text))]
        (let [value (edn/read {:eof eof} reader)]
          (when (identical? eof value)
            (throw
             (ex-info "EDN input is empty"
                      {:reason :empty-edn})))
          (when-not (identical? eof (edn/read {:eof eof} reader))
            (throw
             (ex-info "EDN input has trailing data"
                      {:reason :trailing-data})))
          value)))
    (catch RuntimeException error
      (if (contains? #{:empty-edn :trailing-data}
                     (:reason (ex-data error)))
        (throw error)
        (throw
         (ex-info "EDN input is invalid"
                  {:reason :invalid-edn}
                  error))))))

(defn hex
  "Returns lowercase hexadecimal for a byte array or byte sequence."
  [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff (int %))) bytes)))

(defn digest-bytes
  "Hashes bytes with a named MessageDigest algorithm."
  [algorithm bytes]
  (let [digest (MessageDigest/getInstance algorithm)]
    (.update digest bytes)
    (hex (.digest digest))))

(defn digest-input
  "Hashes an open input stream without closing it."
  [algorithm input]
  (let [digest (MessageDigest/getInstance algorithm)
        buffer (byte-array 16384)]
    (loop [read (.read input buffer)]
      (when-not (neg? read)
        (when (pos? read)
          (.update digest buffer 0 read))
        (recur (.read input buffer))))
    (hex (.digest digest))))

(defn digest-file
  "Hashes a file with a named MessageDigest algorithm."
  [algorithm ^Path input]
  (with-open [stream (Files/newInputStream input (make-array OpenOption 0))]
    (digest-input algorithm stream)))

(defn sha256-bytes [bytes]
  (digest-bytes "SHA-256" bytes))

(defn sha256-text [value]
  (sha256-bytes (.getBytes (str value) StandardCharsets/UTF_8)))

(defn sha256-file [^Path input]
  (digest-file "SHA-256" input))

(defn sha512-file [^Path input]
  (digest-file "SHA-512" input))

(defn write-text!
  "Creates the parent directory and writes UTF-8 text."
  [^Path output value]
  (when-let [parent (.getParent output)]
    (Files/createDirectories parent (make-array FileAttribute 0)))
  (Files/writeString output (str value) StandardCharsets/UTF_8
                     (make-array OpenOption 0))
  output)

(defn xml-escape
  "Escapes text for XML content and attribute values."
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn portable-path
  "Renders a normalized path relative to root with slash separators."
  [^Path root ^Path input]
  (-> (str (.relativize (.normalize root) (.normalize input)))
      (str/replace "\\" "/")))

(defn portable-or-absolute-path
  "Renders a normalized slash-separated path, relative to root when contained
  by root and absolute otherwise."
  [^Path root ^Path input]
  (let [root (.normalize root)
        input (.normalize input)]
    (-> (if (.startsWith input root)
          (str (.relativize root input))
          (str input))
        (str/replace "\\" "/"))))

(defn current-host
  "Returns the normalized operating-system and architecture identity."
  []
  (let [os-name (str/lower-case (System/getProperty "os.name" ""))
        architecture (str/lower-case (System/getProperty "os.arch" ""))
        os (cond
             (str/includes? os-name "win") "windows"
             (str/includes? os-name "mac") "macos"
             (str/includes? os-name "linux") "linux"
             :else os-name)
        architecture (case architecture
                       "amd64" "x64"
                       "x86_64" "x64"
                       "aarch64" "arm64"
                       "arm64" "arm64"
                       architecture)]
    {:os os :architecture architecture}))

(defn windows?
  "Returns true when the current operating system is Windows."
  []
  (= "windows" (:os (current-host))))
