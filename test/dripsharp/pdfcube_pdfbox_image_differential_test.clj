(ns dripsharp.pdfcube-pdfbox-image-differential-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-image-differential
             :as image-differential]
            [dripsharp.maven :as maven]
            [dripsharp.target-execution :as target-execution])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-image-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort image-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-image-contract
  (let [summary
        (image-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count image-differential/required-trace-families)
           (:observations summary)))
    (is (= image-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (image-differential/trace-summary
             (trace-file "codec-metadata\tjpx\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-image-differential-failed
             (:kind (ex-data error))))
      (is (some #{"full-pixels"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "codec-metadata\tcase-0\tvalue\n")
             "codec-metadata\tmissing-value\n"]]
      (let [error
            (try
              (image-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-image-differential-failed
               (:kind (ex-data error))))))))

(defn- workspace-with-fixtures []
  (let [root (Files/createTempDirectory "pdfcube-pdfbox-image-root-"
                                        (make-array FileAttribute 0))
        resources
        (.resolve root "research/pdfbox/tools/src/test/resources/input/ImageIOUtil")]
    (Files/createDirectories resources (make-array FileAttribute 0))
    (doseq [fixture image-differential/required-fixtures]
      (Files/writeString (.resolve resources ^String fixture) "%PDF"
                         (make-array OpenOption 0)))
    root))

(deftest standalone-package-mode-packs-through-the-pdfcarton-target
  (let [root (workspace-with-fixtures)
        selected (atom nil)
        error
        (with-redefs [target-execution/package!
                      (fn [options]
                        (reset! selected options)
                        (throw (ex-info "stop" {:kind ::stopped})))]
          (try
            (image-differential/verify! {:workspace-root root})
            nil
            (catch clojure.lang.ExceptionInfo caught caught)))]
    (is (= ::stopped (:kind (ex-data error))))
    (is (= {:target :pdfcube :profile "pdfcube-pdfbox"}
           (select-keys @selected [:target :profile])))
    (is (= (str root) (str (:workspace-root @selected))))))

(deftest generated-project-mode-requires-an-existing-generation
  (let [root (workspace-with-fixtures)
        error
        (with-redefs [target-execution/package!
                      (fn [_] (throw (ex-info "package mode used" {})))]
          (try
            (image-differential/verify! {:workspace-root root
                                         :generated-project? true})
            nil
            (catch clojure.lang.ExceptionInfo caught caught)))]
    (is (= :pdfcube-pdfbox-image-differential-failed (:kind (ex-data error))))
    (is (= (str/join " " image-differential/generate-command)
           (:command (ex-data error))))))

(deftest generated-project-mode-rejects-a-non-pinned-pdfbox-checkout
  (let [root (workspace-with-fixtures)
        project (.resolve root "target/generated/pdfcarton/src/DripSharp.PdfCarton/DripSharp.PdfCarton.csproj")
        manifest (.resolve root "target/maven-reactor-inputs-0.tsv")
        commands (atom [])]
    (Files/createDirectories (.getParent project) (make-array FileAttribute 0))
    (Files/writeString project "<Project />" (make-array OpenOption 0))
    (Files/writeString manifest "" (make-array OpenOption 0))
    (let [error
          (with-redefs [maven/read-reactor-manifest (fn [_] [])
                        maven/project-by-id! (fn [_ _] {})]
            (try
              (image-differential/verify!
               {:workspace-root root
                :generated-project? true
                :run-command!
                (fn [{:keys [command] :as options}]
                  (swap! commands conj options)
                  (if (= ["git" "rev-parse" "HEAD"] command)
                    {:output "0000000000000000000000000000000000000000\n"}
                    (throw (ex-info "probe or oracle executed" {}))))})
              nil
              (catch clojure.lang.ExceptionInfo caught caught)))]
      (is (= :pdfcube-pdfbox-image-differential-failed (:kind (ex-data error))))
      (is (= image-differential/pinned-revision (:expected (ex-data error))))
      (is (= "0000000000000000000000000000000000000000"
             (:actual (ex-data error))))
      (is (= [["git" "rev-parse" "HEAD"]] (mapv :command @commands))))))

(deftest required-fixtures-cover-both-managed-codecs
  (is (= #{"JBIG2Image.pdf"
           "JPXTestCMYK.pdf"
           "JPXTestGrey.pdf"
           "JPXTestRGB.pdf"}
         (set image-differential/required-fixtures))))
