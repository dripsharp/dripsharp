(ns dripsharp.pdfcube-generated-project-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.generated-project :as generated-project]
            [dripsharp.pdfcube.pdfbox-bidi-differential :as bidi]
            [dripsharp.pdfcube.pdfbox-differential :as aggregate]
            [dripsharp.pdfcube.pdfbox-document-lifecycle-differential
             :as document-lifecycle]
            [dripsharp.pdfcube.pdfbox-font-text-differential :as font-text]
            [dripsharp.pdfcube.pdfbox-graphics-differential :as graphics]
            [dripsharp.pdfcube.pdfbox-interaction-differential :as interaction]
            [dripsharp.pdfcube.pdfbox-interchange-differential :as interchange]
            [dripsharp.pdfcube.pdfbox-low-level-differential :as low-level]
            [dripsharp.pdfcube.pdfbox-manipulation-differential
             :as manipulation]
            [dripsharp.pdfcube.pdfbox-printing-differential :as printing]
            [dripsharp.pdfcube.pdfbox-rendering-differential :as rendering]
            [dripsharp.pdfcube.pdfbox-security-differential :as security]
            [dripsharp.maven :as maven]
            [dripsharp.target-execution :as target-execution])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private differentials
  {"bidi" bidi/verify!
   "document-lifecycle" document-lifecycle/verify!
   "font-text" font-text/verify!
   "graphics" graphics/verify!
   "interaction" interaction/verify!
   "interchange" interchange/verify!
   "low-level" low-level/verify!
   "manipulation" manipulation/verify!
   "printing" printing/verify!
   "rendering" rendering/verify!
   "security" security/verify!
   "aggregate" aggregate/verify!})

(defn- caught [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error error)))

(deftest package-mode-packs-through-the-pdfcarton-target
  (let [root (paths/workspace-root)]
    (doseq [[id verify!] differentials]
      (testing id
        (let [selected (atom nil)
              error
              (with-redefs [target-execution/package!
                            (fn [options]
                              (reset! selected options)
                              (throw (ex-info "stop" {:kind ::stopped})))]
                (caught #(verify! {:workspace-root root})))]
          (is (= ::stopped (:kind (ex-data error))))
          (is (= {:target :pdfcube :profile "pdfcube-pdfbox"}
                 (select-keys @selected [:target :profile])))
          (is (= (str root) (str (:workspace-root @selected)))))))))

(deftest generated-project-mode-requires-an-existing-generation
  (let [root (paths/workspace-root)
        missing "target/no-such-maven-reactor-inputs.tsv"]
    (doseq [[id verify!] differentials]
      (testing id
        (let [error
              (with-redefs [target-execution/package!
                            (fn [_] (throw (ex-info "package mode used" {})))]
                (caught #(verify! {:workspace-root root
                                   :generated-project? true
                                   :maven-manifest missing
                                   :run-command!
                                   (fn [_]
                                     (throw (ex-info "process ran" {})))})))]
          (is (re-find #"generate it first" (ex-message error)))
          (is (= (str/join " " generated-project/generate-command)
                 (:command (ex-data error))))
          (is (str/ends-with? (:path (ex-data error)) missing)))))))

(defn- temp-root []
  (Files/createTempDirectory "pdfcube-generated-project-"
                             (make-array FileAttribute 0)))

(deftest generated-project-proof-rejects-a-non-pinned-pdfbox-checkout
  (let [root (temp-root)
        project (.resolve root "target/generated/pdfcarton/src/DripSharp.PdfCarton/DripSharp.PdfCarton.csproj")
        manifest (.resolve root "target/maven-reactor-inputs-0.tsv")
        commands (atom [])]
    (Files/createDirectories (.getParent project) (make-array FileAttribute 0))
    (Files/writeString project "<Project />" (make-array OpenOption 0))
    (Files/writeString manifest "" (make-array OpenOption 0))
    (let [error
          (with-redefs [maven/read-reactor-manifest (fn [_] [])
                        maven/project-by-id! (fn [_ _] {})]
            (caught
             #(generated-project/proof
               {:workspace-root root
                :fail! (fn [message data]
                         (throw (ex-info message (assoc data :kind ::failed))))
                :run-command!
                (fn [options]
                  (swap! commands conj (:command options))
                  {:output "0000000000000000000000000000000000000000\n"})})))]
      (is (= ::failed (:kind (ex-data error))))
      (is (= "0000000000000000000000000000000000000000"
             (:actual (ex-data error))))
      (is (= [["git" "rev-parse" "HEAD"]] @commands)))))

(deftest summaries-name-the-boundary-each-mode-used
  (let [package {:identity {:id "DripSharp.PdfCarton" :version "1" :sha256 "abc"}
                 :dependency-proof {:packages []}}
        local {:mode :generated-project
               :project "target/generated/pdfcarton/src/DripSharp.PdfCarton/DripSharp.PdfCarton.csproj"
               :manifest "target/maven-reactor-inputs-0.tsv"
               :probe "validation/pdfcube-pdfbox-graphics/DripSharp.PdfCarton.GraphicsProbe.csproj"}]
    (is (= {:id "DripSharp.PdfCarton" :version "1" :sha256 "abc"}
           (generated-project/package-summary package)))
    (is (= {:packages []} (generated-project/consumer-summary package)))
    (is (= :generated-project
           (:boundary (generated-project/package-summary local))))
    (is (= {:strategy :public-project-reference
            :project (:probe local)
            :target-framework "net10.0"}
           (generated-project/consumer-summary local)))))

(deftest every-pdfbox-probe-project-references-the-generated-staging
  (let [root (paths/workspace-root)]
    (doseq [[directory project]
            [["pdfcube-pdfbox" "DripSharp.PdfCarton.LowLevelProbe.csproj"]
             ["pdfcube-pdfbox-bidi"
              "extraction/DripSharp.PdfCarton.BidiExtractionProbe.csproj"]
             ["pdfcube-pdfbox-document-lifecycle"
              "DripSharp.PdfCarton.DocumentLifecycleProbe.csproj"]
             ["pdfcube-pdfbox-font-text" "DripSharp.PdfCarton.FontTextProbe.csproj"]
             ["pdfcube-pdfbox-graphics" "DripSharp.PdfCarton.GraphicsProbe.csproj"]
             ["pdfcube-pdfbox-image" "DripSharp.PdfCarton.ImageProbe.csproj"]
             ["pdfcube-pdfbox-interaction"
              "DripSharp.PdfCarton.InteractionProbe.csproj"]
             ["pdfcube-pdfbox-interchange"
              "DripSharp.PdfCarton.InterchangeProbe.csproj"]
             ["pdfcube-pdfbox-manipulation"
              "DripSharp.PdfCarton.ManipulationProbe.csproj"]
             ["pdfcube-pdfbox-printing"
              "DripSharp.PdfCarton.PrintingProjectProbe.csproj"]
             ["pdfcube-pdfbox-rendering" "DripSharp.PdfCarton.RenderingProbe.csproj"]
             ["pdfcube-pdfbox-security" "DripSharp.PdfCarton.SecurityProbe.csproj"]]]
      (testing project
        (let [path (generated-project/probe-project root directory project)
              text (slurp (str path))]
          (is (str/includes?
               text
               "target/generated/pdfcarton/src/DripSharp.PdfCarton/DripSharp.PdfCarton.csproj"))
          (is (not (str/includes? text "PackageReference"))))))))

(defn- fake-slice [id workflows ^java.nio.file.Path proof-root calls]
  {:id id
   :workflows workflows
   :verify
   (fn [options]
     (swap! calls conj [id (select-keys options [:generated-project?
                                                 :maven-manifest
                                                 :package-fn])])
     (Files/createDirectories proof-root (make-array FileAttribute 0))
     (Files/writeString (.resolve proof-root "upstream-java.tsv")
                        "failure\tcase\tvalue\n"
                        (make-array OpenOption 0))
     {:package {:boundary :generated-project :project "staging"}
      :proof-root proof-root
      :trace {:observations 1 :families ["failure"]}
      :comparison {:lines 1}})})

(deftest aggregate-generated-project-mode-runs-every-workflow-locally
  (let [root (temp-root)
        calls (atom [])
        slices
        [(fake-slice :low-level
                     (disj aggregate/required-workflows :print-layout)
                     (.resolve root "low-level") calls)
         (fake-slice :printing #{:print-layout}
                     (.resolve root "printing") calls)]
        summary
        (with-redefs [target-execution/package!
                      (fn [_] (throw (ex-info "package mode used" {})))]
          (aggregate/verify! {:workspace-root root
                              :generated-project? true
                              :maven-manifest "manifest.tsv"
                              :slices slices}))]
    (is (= [[:low-level {:generated-project? true
                         :maven-manifest "manifest.tsv"}]
            [:printing {:generated-project? true
                        :maven-manifest "manifest.tsv"}]]
           @calls))
    (is (= {:boundary :generated-project :project "staging"}
           (:package summary)))
    (is (= #{:low-level :printing} (set (keys (:workflows summary)))))
    (is (get-in summary [:deliberate-mismatch :line]))))
