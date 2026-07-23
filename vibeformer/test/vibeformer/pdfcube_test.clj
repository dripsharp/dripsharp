(ns vibeformer.pdfcube-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.harness :as harness]
            [vibeformer.java-project :as project-emission]
            [vibeformer.paths :as paths]
            [vibeformer.pdfcube.java-project :as pdfcube]
            [vibeformer.process :as process]
            [vibeformer.public-surface :as public-surface]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [javax.tools ToolProvider]))

(def ^:private profile-names
  ["pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
   "pdfcube-pdfbox" "pdfcube-preflight"])

(defn- temp-directory []
  (Files/createTempDirectory "vibeformer-pdfcube"
                             (make-array FileAttribute 0)))

(defn- write-file! [^Path root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- read-profile-and-destination [profile-name]
  (let [workspace (paths/workspace-root)
        profile (harness/read-profile workspace profile-name)]
    {:profile profile
     :destination
     (project-emission/read-configuration
      workspace (:destination-config profile))}))

(defn- caught [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

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
                 (throw (ex-info "PdfCube fixture dependency compilation failed"
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

(deftest five-configurations-match-the-approved-pdfcube-family
  (let [workspace (paths/workspace-root)
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
    (is (= :pdfcube (:product-family family)))
    (is (= 5 (count (:products family)) (count destinations)))
    (is (= #{"PdfCube.IO" "PdfCube.FontBox" "PdfCube.XmpBox"
             "PdfCube.PdfBox" "PdfCube.Preflight"}
           (set packages)))
    (is (= {"PdfCube.IO" []
            "PdfCube.FontBox" ["PdfCube.IO"]
            "PdfCube.XmpBox" []
            "PdfCube.PdfBox" ["PdfCube.IO" "PdfCube.FontBox"]
            "PdfCube.Preflight" ["PdfCube.PdfBox" "PdfCube.XmpBox"]}
           dependency-graph))
    (doseq [{:keys [profile destination]} prepared]
      (is (= :maven (:build-tool profile)))
      (is (= "net10.0" (get-in destination [:project :target-framework])))
      (is (= "disable" (get-in destination [:project :nullable])))
      (is (true? (get-in destination [:project :warnings-as-errors])))
      (is (= {:public-identifiers :csharp
              :methods :methods :fields :fields :overloads :overloads}
             (:name-policy destination)))
      (is (= {:strategy :embedded-resource-preserve-path}
             (:resource-policy destination)))
      (is (= :pdfcube
             (:product-family
              (:contract
               (public-surface/resolve-strategy!
                :pdfcube (:public-surface destination))))))
      (is (= #{:strategy} (set (keys (:public-surface destination)))))
      (let [selection
            (public-surface/resolve-strategy!
             :pdfcube (:public-surface destination))]
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
        {pdfbox :destination}
        (read-profile-and-destination "pdfcube-pdfbox")
        validate! (get-in (pdfcube/rule-bundle)
                          [:rules :project-policy :validate-configuration!])
        project-text ((get-in (pdfcube/rule-bundle)
                              [:rules :project-policy :project-text])
                      pdfbox [])
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
    (is (str/includes? project-text
                       "<PackageReference Include=\"Microsoft.Extensions.Logging.Abstractions\" Version=\"10.0.0\" />"))
    (is (str/includes? project-text
                       "<PackageLicenseFile>LICENSE.txt</PackageLicenseFile>"))
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
                      "src/PdfCube/IO/NamingFixture.cs")))
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
               "src/PdfCube/FontBox/FontApi.cs")))]
    (is (str/includes?
         source
         (str "public global::PdfCube.IO.RandomAccessRead Echo("
              "global::PdfCube.IO.RandomAccessRead value)")))
    (is (not (str/includes? source "org.apache.pdfbox.io")))))

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
