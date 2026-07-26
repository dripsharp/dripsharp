(ns dripsharp.pdfcube.pdfbox-rendering-differential
  "Pinned PDFBox 3.0.8 versus package-only PdfCube.PdfBox CPU rendering proof."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.io File]
           [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipFile]))

(def pinned-revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

(def required-image-ids
  #{"annotations" "form-xobject" "graphics2d" "image" "soft-mask"
    "survey-1" "survey-5" "transparency-group" "type3"})

(def pixel-tolerances
  {:rgb-channel-outlier 48
   :alpha-channel-outlier 32
   :maximum-mean-absolute-error 10.0
   :maximum-outlier-fraction 0.18})

(def supported-hosts
  [{:os "windows" :architecture "x64" :native-entry
    "runtimes/win-x64/native/libSkiaSharp.dll"}
   {:os "windows" :architecture "arm64" :native-entry
    "runtimes/win-arm64/native/libSkiaSharp.dll"}
   {:os "linux" :architecture "x64" :native-entry
    "runtimes/linux-x64/native/libSkiaSharp.so"}
   {:os "linux" :architecture "arm64" :native-entry
    "runtimes/linux-arm64/native/libSkiaSharp.so"}
   {:os "macos" :architecture "x64" :native-entry
    "runtimes/osx/native/libSkiaSharp.dylib"}
   {:os "macos" :architecture "arm64" :native-entry
    "runtimes/osx/native/libSkiaSharp.dylib"}])

(defn- fail! [message data]
  (throw
   (ex-info
    message
    (assoc data :kind :pdfcube-pdfbox-rendering-differential-failed))))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn manifest-summary
  "Validates a rendering manifest and returns its normalized coverage."
  [manifest]
  (let [manifest (paths/path manifest)
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)
                 kind (first fields)
                 expected-count (case kind "image" 5 "structure" nil nil)]
             (when (or (some str/blank? fields)
                       (and expected-count
                            (not= expected-count (count fields)))
                       (and (= kind "structure")
                            (not (#{6 8} (count fields))))
                       (nil? (#{"image" "structure"} kind)))
               (fail! "Rendering manifest contains a malformed observation"
                      {:manifest (str manifest)
                       :line (inc index)
                       :value line}))
             {:kind kind
              :id (second fields)
              :fields (vec (drop 2 fields))}))
         (range)
         (Files/readAllLines manifest))
        identities (mapv (juxt :kind :id) rows)
        duplicates
        (->> identities
             frequencies
             (keep (fn [[identity count]]
                     (when (< 1 count) identity)))
             sort
             vec)
        images (filterv #(= "image" (:kind %)) rows)
        structures (filterv #(= "structure" (:kind %)) rows)
        image-ids (set (map :id images))
        structure-ids (set (map :id structures))
        missing-images (sort (set/difference required-image-ids image-ids))
        missing-structures
        (sort (set/difference required-image-ids structure-ids))]
    (when (seq duplicates)
      (fail! "Rendering manifest contains duplicate observation identities"
             {:manifest (str manifest) :duplicates duplicates}))
    (when (or (seq missing-images) (seq missing-structures))
      (fail! "Rendering manifest misses required representative outputs"
             {:manifest (str manifest)
              :missing-images missing-images
              :missing-structures missing-structures}))
    {:rows rows
     :images (into (sorted-map)
                   (map (juxt :id :fields))
                   images)
     :structures (into (sorted-map)
                       (map (juxt :id :fields))
                       structures)
     :image-ids (vec (sort image-ids))
     :observations (count rows)}))

(defn- unsigned-byte [value]
  (bit-and 0xff (int value)))

(defn compare-rgba
  "Compares two normalized RGBA rasters using the task's explicit tolerances."
  [expected actual]
  (let [expected (Files/readAllBytes (paths/path expected))
        actual (Files/readAllBytes (paths/path actual))]
    (when-not (= (alength expected) (alength actual))
      (fail! "Normalized renderer byte lengths differ"
             {:expected-bytes (alength expected)
              :actual-bytes (alength actual)}))
    (when-not (zero? (mod (alength expected) 4))
      (fail! "Normalized renderer output is not RGBA-aligned"
             {:bytes (alength expected)}))
    (let [pixels (quot (alength expected) 4)
          {:keys [sum maximum outliers]}
          (loop [offset 0
                 sum 0
                 maximum 0
                 outliers 0]
            (if (= offset (alength expected))
              {:sum sum :maximum maximum :outliers outliers}
              (let [differences
                    (mapv
                     (fn [channel]
                       (Math/abs
                        (long
                         (- (unsigned-byte (aget expected (+ offset channel)))
                            (unsigned-byte (aget actual (+ offset channel)))))))
                     (range 4))
                    rgb-outlier?
                    (some #(> % (:rgb-channel-outlier pixel-tolerances))
                          (subvec differences 0 3))
                    alpha-outlier?
                    (> (nth differences 3)
                       (:alpha-channel-outlier pixel-tolerances))]
                (recur (+ offset 4)
                       (+ sum (reduce + differences))
                       (max maximum (reduce max differences))
                       (if (or rgb-outlier? alpha-outlier?)
                         (inc outliers)
                         outliers)))))
          mean-error (/ (double sum) (* pixels 4))
          outlier-fraction (/ (double outliers) pixels)
          summary
          {:pixels pixels
           :mean-absolute-error mean-error
           :maximum-channel-error maximum
           :outlier-pixels outliers
           :outlier-fraction outlier-fraction
           :tolerances pixel-tolerances}]
      (when (or (> mean-error
                   (:maximum-mean-absolute-error pixel-tolerances))
                (> outlier-fraction
                   (:maximum-outlier-fraction pixel-tolerances)))
        (fail! "CPU renderer output exceeds explicit pixel tolerances"
               summary))
      summary)))

(defn compare-renderings!
  "Validates manifests, exact dimensions/structure, and tolerant RGBA pixels."
  [java-manifest dotnet-manifest java-root dotnet-root]
  (let [java-summary (manifest-summary java-manifest)
        dotnet-summary (manifest-summary dotnet-manifest)]
    (when-not (= (:images java-summary) (:images dotnet-summary))
      (fail! "Rendered image dimensions differ between Java and .NET"
             {:java (:images java-summary)
              :dotnet (:images dotnet-summary)}))
    (when-not (= (:structures java-summary) (:structures dotnet-summary))
      (fail! "Selected rendering structure differs between Java and .NET"
             {:java (:structures java-summary)
              :dotnet (:structures dotnet-summary)}))
    {:manifest
     (select-keys java-summary [:image-ids :observations])
     :images
     (into
      (sorted-map)
      (for [id required-image-ids]
        [id
         (compare-rgba
          (paths/resolve-path java-root (str id ".rgba"))
          (paths/resolve-path dotnet-root (str id ".rgba")))]))}))

(defn- java-tools [^Path root generation]
  (let [toolchain (get-in generation [:project-input :java-toolchain])
        home (configured-path root (:home toolchain))
        suffix
        (if (str/starts-with?
             (str/lower-case (System/getProperty "os.name" ""))
             "windows")
          ".exe"
          "")]
    {:release (:release toolchain)
     :java (paths/resolve-path home "bin" (str "java" suffix))
     :javac (paths/resolve-path home "bin" (str "javac" suffix))}))

(defn- compile-and-run-oracle!
  [run-command! ^Path root generation ^Path proof-root ^Path output
   ^Path resources]
  (let [{:keys [release java javac]} (java-tools root generation)
        project-input (:project-input generation)
        sources
        (mapv #(configured-path root %)
              (concat (:production-sources project-input)
                      (:generated-production-sources project-input)))
        dependencies
        (->> (:classpath-artifacts project-input)
             (map #(configured-path root (:path %)))
             distinct
             vec)
        resource-roots
        (mapv #(configured-path root %) (:resource-roots project-input))
        oracle-source
        (paths/resolve-path root "validation" "pdfcube-pdfbox-rendering"
                            "PdfBoxRenderingOracle.java")
        classes
        (doto (paths/resolve-path proof-root "oracle-classes")
          (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str dependencies))
        run-classpath
        (str/join File/pathSeparator
                  (map str
                       (into [classes]
                             (concat dependencies resource-roots))))
        compile-command
        (cond-> [(str javac) "--release" (str release) "-encoding" "UTF-8"]
          (seq dependencies) (into ["-classpath" compile-classpath])
          true (into ["-d" (str classes)])
          true (into (map str sources))
          true (conj (str oracle-source)))]
    (doseq [tool [java javac]]
      (when-not (paths/regular-file? tool)
        (fail! "Pinned Java rendering oracle toolchain is missing"
               {:tool (str tool) :release release})))
    (run-command! {:command compile-command
                   :directory root
                   :timeout-ms 600000})
    (run-command! {:command [(str java) "-Djava.awt.headless=true"
                             "-classpath" run-classpath
                             "PdfBoxRenderingOracle"
                             (str output) (str resources)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output ^Path resources]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project (paths/resolve-path consumer-root
                                    (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation" "pdfcube-pdfbox-rendering"
                            "Program.cs")]
    (Files/copy probe source
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str project)
                             "--nologo" "--verbosity:minimal"
                             "--no-restore" "--no-incremental" "-warnaserror"]
                   :directory consumer-root
                   :timeout-ms 300000})
    (run-command! {:command ["dotnet" "run" "--project" (str project)
                             "--no-build" "--no-restore" "--"
                             (str output) (str resources)]
                   :directory consumer-root
                   :timeout-ms 300000})))

(defn- nuget-package [id version]
  (let [lower-id (str/lower-case id)]
    (paths/resolve-path
     (System/getProperty "user.home")
     ".nuget" "packages" lower-id version
     (str lower-id "." version ".nupkg"))))

(defn native-asset-summary
  "Verifies official SkiaSharp native package entries for every supported RID."
  []
  (let [version "4.150.1"
        packages
        {"windows" (nuget-package "SkiaSharp.NativeAssets.Win32" version)
         "linux" (nuget-package "SkiaSharp.NativeAssets.Linux" version)
         "macos" (nuget-package "SkiaSharp.NativeAssets.macOS" version)}
        entries-by-os
        (into
         {}
         (for [[os package] packages]
           (do
             (when-not (paths/regular-file? package)
               (fail! "Required official SkiaSharp native package is missing"
                      {:os os :package (str package)}))
             [os
              (with-open [archive (ZipFile. (.toFile package))]
                (into #{} (map #(.getName %))
                      (enumeration-seq (.entries archive))))])))
        missing
        (->> supported-hosts
             (remove
              (fn [{:keys [os native-entry]}]
                (contains? (get entries-by-os os) native-entry)))
             vec)]
    (when (seq missing)
      (fail! "Official SkiaSharp native packages miss supported RIDs"
             {:missing missing}))
    {:version version
     :packages (into (sorted-map)
                     (map (fn [[os package]] [os (str package)]))
                     packages)
     :hosts supported-hosts}))

(defn verify!
  "Runs clean package consumption and normalized CPU rendering comparisons."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (package-fn {:workspace-root root
                      :profile "pdfcube-pdfbox"
                      :run-command! run-command!})
         generation (get-in package-proof [:verification :generation])
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              "pdfcube-pdfbox-rendering-differential"))
         resources
         (paths/resolve-path root "research" "pdfbox" "pdfbox"
                             "src" "test" "resources")
         java-manifest
         (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-manifest
         (paths/resolve-path proof-root "package-dotnet.tsv")]
     (when-not (paths/directory? resources)
       (fail! "Authoritative PDFBox rendering resources are missing"
              {:resources (str resources)}))
     (compile-and-run-oracle!
      run-command! root generation proof-root java-manifest resources)
     (run-package-probe!
      run-command! root package-proof dotnet-manifest resources)
     (let [comparison
           (compare-renderings!
            java-manifest
            dotnet-manifest
            (paths/resolve-path proof-root "java-raw")
            (paths/resolve-path proof-root "dotnet-raw"))
           native-assets (native-asset-summary)
           summary
           {:profile "pdfcube-pdfbox"
            :source {:version "3.0.8" :revision pinned-revision}
            :package
            {:id (get-in package-proof [:identity :id])
             :version (get-in package-proof [:identity :version])
             :sha256 (get-in package-proof [:identity :sha256])}
            :consumer (:dependency-proof package-proof)
            :backend :cpu-raster
            :comparison comparison
            :native-assets native-assets
            :proof-root proof-root}]
       (spit (str (paths/resolve-path proof-root "summary.edn"))
             (str (pr-str (dissoc summary :proof-root)) "\n"))
       (println
        "Pinned Java/package PdfCube.PdfBox rendering differential passed:"
        (pr-str
         (select-keys summary
                      [:source :package :backend :comparison])))
       summary))))
