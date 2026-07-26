(ns dripsharp.pdfcube-pdfbox-rendering-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-rendering-differential
             :as rendering-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- temporary-file [prefix suffix contents]
  (let [file (Files/createTempFile prefix suffix
                                   (make-array FileAttribute 0))]
    (Files/write file contents (make-array OpenOption 0))
    file))

(defn- complete-manifest []
  (apply
   str
   (for [id (sort rendering-differential/required-image-ids)
         row [(str "image\t" id "\t2\t2\t4\n")
              (if (= id "graphics2d")
                (str "structure\t" id "\t64\t64\tchild-dispose\tcpu\n")
                (str "structure\t" id "\t1\t0\t0\t0\t100.000\t200.000\n"))]]
     row)))

(deftest rendering-manifest-is-fail-closed
  (let [summary
        (rendering-differential/manifest-summary
         (temporary-file
          "pdfcube-rendering-manifest-"
          ".tsv"
          (.getBytes (complete-manifest))))]
    (is (= rendering-differential/required-image-ids
           (set (:image-ids summary))))
    (is (= (* 2 (count rendering-differential/required-image-ids))
           (:observations summary))))
  (testing "missing and duplicate outputs are rejected"
    (doseq [contents
            ["image\tgraphics2d\t2\t2\t4\n"
             (str (complete-manifest)
                  "image\tgraphics2d\t2\t2\t4\n")]]
      (let [error
            (try
              (rendering-differential/manifest-summary
               (temporary-file
                "pdfcube-rendering-invalid-"
                ".tsv"
                (.getBytes contents)))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-rendering-differential-failed
               (:kind (ex-data error))))))))

(deftest rgba-comparison-enforces-explicit-tolerances
  (is (= {:rgb-channel-outlier 48
          :alpha-channel-outlier 32
          :maximum-mean-absolute-error 10.0
          :maximum-outlier-fraction 0.18}
         rendering-differential/pixel-tolerances))
  (let [expected (byte-array [0 10 20 (unchecked-byte 255)
                              100 110 120 (unchecked-byte 200)])
        accepted (byte-array [5 15 25 (unchecked-byte 250)
                              110 100 125 (unchecked-byte 190)])
        rejected (byte-array [(unchecked-byte 255)
                              (unchecked-byte 255)
                              (unchecked-byte 255)
                              0
                              (unchecked-byte 255)
                              (unchecked-byte 255)
                              (unchecked-byte 255)
                              0])
        expected-file
        (temporary-file "pdfcube-rendering-expected-" ".rgba" expected)
        accepted-file
        (temporary-file "pdfcube-rendering-accepted-" ".rgba" accepted)
        rejected-file
        (temporary-file "pdfcube-rendering-rejected-" ".rgba" rejected)]
    (is (= 2
           (:pixels
            (rendering-differential/compare-rgba
             expected-file accepted-file))))
    (let [error
          (try
            (rendering-differential/compare-rgba
             expected-file rejected-file)
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-rendering-differential-failed
             (:kind (ex-data error)))))))
