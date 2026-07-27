(ns dripsharp.pdfcube-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as project-emission]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.io-differential :as io-differential]
            [dripsharp.pdfcube.java-project :as pdfcube]
            [dripsharp.pdfcube.source-sync :as source-sync]
            [dripsharp.process :as process]
            [dripsharp.public-surface :as public-surface]
            [dripsharp.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [javax.tools ToolProvider]))

(def ^:private profile-names
  ["pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
   "pdfcube-pdfbox" "pdfcube-preflight"])

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-pdfcube"
                             (make-array FileAttribute 0)))

(deftest compatibility-namespace-transform-shifts-source-map-ranges
  (let [transform
        (get-in (pdfcube/rule-bundle)
                [:rules :project-policy :transform-rendered])
        source-token "global::DripSharp.Runtime"
        destination-token "global::PdfCube.FB.Runtime"
        text (str "a" source-token ".One b " source-token ".Two")
        first-start 1
        first-end (+ first-start (count source-token))
        second-start (.lastIndexOf text source-token)
        rendered
        {:text text
         :mappings
         [{:destination {:start 0 :end (count text)}}
          {:destination {:start first-start :end first-end}}
          {:destination {:start second-start :end (count text)}}]}
        transformed
        (transform {:compatibility-namespace "PdfCube.FB.Runtime"} rendered)]
    (is (= (str "a" destination-token ".One b " destination-token ".Two")
           (:text transformed)))
    (is (= [{:start 0 :end (+ 2 (count text))}
            {:start first-start :end (inc first-end)}
            {:start (inc second-start) :end (+ 2 (count text))}]
           (mapv :destination (:mappings transformed))))))

(deftest preflight-raw-font-generics-receive-erased-contracts
  (let [transform
        (get-in (pdfcube/rule-bundle)
                [:rules :project-policy :transform-rendered])
        configuration {:package {:id "PdfCube.Preflight"}}
        container
        "global::PdfCube.Preflight.Font.Container.FontContainer"
        font-like "global::PdfCube.PdfBox.Pdmodel.Font.PDFontLike"
        container-text
        (str "// org/apache/pdfbox/preflight/font/container/FontContainer.java\n"
             "public abstract class FontContainer<T> where T : " font-like " {\n"
             "private " container "<object> value;\n}")
        validator-text
        (str "// org/apache/pdfbox/preflight/font/FontValidator.java\n"
             "public abstract class FontValidator<T> where T : "
             container "<" font-like "> {\n"
             "private global::PdfCube.Preflight.Font.FontValidator<"
             container "<global::PdfCube.PdfBox.Pdmodel.Font.PDCIDFont>> value;\n}")
        container-result
        (transform configuration
                   {:text container-text
                    :mappings [{:destination
                                {:start 0 :end (count container-text)}}]})
        validator-result
        (transform configuration
                   {:text validator-text
                    :mappings [{:destination
                                {:start 0 :end (count validator-text)}}]})]
    (is (not (str/includes? (:text container-result)
                            "public interface IFontContainer")))
    (is (str/includes?
         (:text container-result)
         "public abstract class FontContainer<T> : IFontContainer"))
    (is (str/includes?
         (:text container-result)
         "private global::PdfCube.Preflight.Font.Container.IFontContainer value;"))
    (is (not (str/includes? (:text validator-result)
                            "public interface IFontValidator")))
    (is (str/includes?
         (:text validator-result)
         (str "public abstract class FontValidator<T> : IFontValidator where T : "
              "global::PdfCube.Preflight.Font.Container.IFontContainer")))
    (is (str/includes?
         (:text validator-result)
         "private global::PdfCube.Preflight.Font.IFontValidator value;"))))

(deftest preflight-type3-widths-preserve-nullable-boxed-floats
  (let [transform
        (get-in (pdfcube/rule-bundle)
                [:rules :project-policy :transform-rendered])
        configuration {:package {:id "PdfCube.Preflight"}}
        text
        (str "// org/apache/pdfbox/preflight/font/Type3FontValidator.java\n"
             "global::System.Collections.Generic.IList<float> widths = value;\n"
             "float width = global::DripSharp.Runtime.JavaCompat.ListGet(widths, i);\n"
             "return global::System.Array.Empty<float>();")
        result
        (transform configuration
                   {:text text
                    :mappings [{:destination
                                {:start 0 :end (count text)}}]})]
    (is (str/includes?
         (:text result)
         "global::System.Collections.Generic.IList<float?> widths"))
    (is (str/includes?
         (:text result)
         (str "float width = global::DripSharp.Runtime.JavaCompat.Unbox("
              "global::DripSharp.Runtime.JavaCompat.ListGet(widths, i));")))
    (is (str/includes?
         (:text result)
         "return global::System.Array.Empty<float?>();"))))

(deftest pdfbox-security-handler-erasure-preserves-specialized-handlers
  (let [transform
        (get-in (pdfcube/rule-bundle)
                [:rules :project-policy :transform-rendered])
        carrier
        (str "global::PdfCube.PdfBox.Pdmodel.Encryption.SecurityHandler"
             "<global::PdfCube.PdfBox.Pdmodel.Encryption.ProtectionPolicy>")
        erased "global::DripSharp.Runtime.PdfBoxSecurityHandler"
        carrier-rendered
        {:text (str "public " carrier " Create() => default!;")
         :mappings [{:destination
                     {:start 0
                      :end (+ 29 (count carrier))}}]}
        declaration-rendered
        {:text
         (str "// org/apache/pdfbox/pdmodel/encryption/SecurityHandler.java\n"
              "public abstract class SecurityHandler<TPOLICY> where "
              "TPOLICY : ProtectionPolicy {}")
         :mappings []}
        specialized
        (str "public sealed class StandardSecurityHandler : "
             "global::PdfCube.PdfBox.Pdmodel.Encryption.SecurityHandler"
             "<global::PdfCube.PdfBox.Pdmodel.Encryption.StandardProtectionPolicy> {}")
        configuration {:internal-capabilities #{:security-handler-erasure}}]
    (is (= (str "public " erased " Create() => default!;")
           (:text (transform configuration carrier-rendered))))
    (is (= (str "// org/apache/pdfbox/pdmodel/encryption/SecurityHandler.java\n"
                "public abstract class SecurityHandler<TPOLICY> : "
                erased " where TPOLICY : ProtectionPolicy {}")
           (:text (transform configuration declaration-rendered))))
    (is (= specialized
           (:text (transform configuration
                             {:text specialized :mappings []}))))))

(deftest pdfbox-calendar-value-semantics-preserve-date-time-zone-mutations
  (let [transform
        (get-in (pdfcube/rule-bundle)
                [:rules :project-policy :transform-rendered])
        rendered
        {:text
         (str
          "// org/apache/pdfbox/util/DateConverter.java\n"
          "private static void adjustTimeZoneNicely("
          "global::System.DateTimeOffset cal, global::System.TimeZoneInfo tz) {\n"
          "cal = global::DripSharp.Runtime.JavaCompat.CalendarAdd(cal, 12, -offset);\n"
          "}\n"
          "internal static bool parseTZoffset(string text, "
          "global::System.DateTimeOffset cal, Position initialWhere) {\n"
          "global::PdfCube.PdfBox.Util.DateConverter.adjustTimeZoneNicely(cal, tz);\n"
          "}\n"
          "return global::PdfCube.PdfBox.Util.DateConverter."
          "parseTZoffset(text, retCal, where);")
         :mappings []}
        transformed
        (:text
         (transform
          {:internal-capabilities #{:calendar-value-semantics}}
          rendered))]
    (is (str/includes?
         transformed
         "private static global::System.DateTimeOffset adjustTimeZoneNicely("))
    (is (str/includes?
         transformed
         "CalendarAdd(cal, 12, -offset);\nreturn cal;\n}"))
    (is (str/includes?
         transformed
         "parseTZoffset(string text, ref global::System.DateTimeOffset cal,"))
    (is (str/includes?
         transformed
         "cal = global::PdfCube.PdfBox.Util.DateConverter.adjustTimeZoneNicely(cal, tz);"))
    (is (str/includes?
         transformed
         "parseTZoffset(text, ref retCal, where)"))))

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
      (is (= revision (get-in destination [:package :repository-commit])))
      (is (= {:repository "https://github.com/apache/pdfbox.git"
              :revision revision
              :notice-reference "NOTICE.txt"}
             (:mechanical-source destination)))
      (is (str/ends-with? (:maven-project-id profile) (str ":" version)))
      (is (str/ends-with? (:source-project-id destination) (str ":" version)))
      (is (= (str version "-dripsharp.0")
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
    (is (= #{:java-bidi}
           (:bridge-capabilities
            (:destination (read-profile-and-destination "pdfcube-pdfbox")))))
    (is (= "PdfCube.XMP.Runtime"
           (:compatibility-namespace
            (:destination (read-profile-and-destination "pdfcube-xmpbox")))))
    (is (= #{}
           (:bridge-capabilities
            (:destination (read-profile-and-destination "pdfcube-preflight")))))
    (is (= #{:preflight-font-erasure}
           (:internal-capabilities
            (:destination (read-profile-and-destination "pdfcube-preflight")))))
    (is (= #{"PdfCube.PdfBox" "PdfCube.Preflight"}
           (:friend-assemblies
            (:destination (read-profile-and-destination "pdfcube-io")))))
    (is (= #{"PdfCube.Preflight"}
           (:friend-assemblies
            (:destination (read-profile-and-destination "pdfcube-pdfbox")))))
    (doseq [{:keys [profile destination]} prepared]
      (is (= :maven (:build-tool profile)))
      (is (= "net10.0" (get-in destination [:project :target-framework])))
      (is (= "disable" (get-in destination [:project :nullable])))
      (is (true? (get-in destination [:project :warnings-as-errors])))
      (is (= "Vibeformer" (get-in destination [:package :authors])))
      (is (= :snupkg (get-in destination [:package :symbols])))
      (is (= (str "Portions Copyright The Apache Software Foundation and "
                  "other upstream contributors; see NOTICE.txt.")
             (get-in destination [:package :copyright])))
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
               (str "src/PdfCube/PdfBox/Pdmodel/Graphics/Image/"
                    "ImageRegionFixture.cs"))))]
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
               "src/PdfCube/IO/FieldCaseFixture.cs")))
        fields (filter #(= :field (:kind %)) (:declarations emission))]
    (is (str/includes? source "public const string OFF = \"OFF\";"))
    (is (str/includes? source "public const string Off = \"Off\";"))
    (is (str/includes?
         source
         "return global::PdfCube.IO.FieldCaseFixture.OFF;"))
    (is (str/includes?
         source
         "return global::PdfCube.IO.FieldCaseFixture.Off;"))
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
               "src/PdfCube/FontBox/FontApi.cs")))]
    (is (str/includes?
         source
         (str "public global::PdfCube.IO.RandomAccessRead Echo("
              "global::PdfCube.IO.RandomAccessRead value)")))
    (is (not (str/includes? source "org.apache.pdfbox.io")))))

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
               "src/PdfCube/FontBox/Util/Autodetect/DiscoveryFixture.cs")))
        adapter-file
        (paths/resolve-path
         (:project-root emission)
         "src/DripSharp/Runtime/PdfCubeFontDiscovery.cs")
        adapter (slurp (str adapter-file))]
    (is (paths/regular-file? adapter-file))
    (doseq [member ["FileExists" "FileCanRead" "FileIsDirectory"
                    "FileIsHidden" "FileListFiles" "FileToUri"]]
      (is (str/includes?
           source
           (str "global::PdfCube.FB.Runtime.PdfCubeFontDiscovery."
                member "("))))
    (is (str/includes? adapter "namespace PdfCube.FB.Runtime;"))
    (is (str/includes? adapter
                       "internal static class PdfCubeFontDiscovery"))
    (is (not (str/includes? adapter
                            "public static class PdfCubeFontDiscovery")))
    (is (str/includes?
         source
         (str "global::PdfCube.FB.Runtime.JavaCompat."
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
