(ns dripsharp.util-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest shared-text-digest-and-path-helpers
  (let [root (Files/createTempDirectory
              "dripsharp-util" (make-array FileAttribute 0))
        file (.resolve root "nested/value.txt")]
    (is (= file (util/write-text! file "value")))
    (is (= "value" (Files/readString file StandardCharsets/UTF_8)))
    (is (= "cd42404d52ad55ccfa9aca4adc828aa5800ad9d385a0671fbcbf724118320619"
           (util/sha256-file file)
           (util/sha256-text "value")
           (util/sha256-bytes (.getBytes "value" StandardCharsets/UTF_8))))
    (is (= "nested/value.txt" (util/portable-path root file)))
    (is (= "../outside.txt"
           (util/portable-path root (.resolve (.getParent root) "outside.txt"))))
    (is (= (str (.resolve (.getParent root) "outside.txt"))
           (util/portable-or-absolute-path
            root (.resolve (.getParent root) "outside.txt"))))))

(deftest shared-xml-and-host-helpers
  (is (= "&amp;&lt;&gt;&quot;&apos;" (util/xml-escape "&<>\"'")))
  (let [{:keys [os architecture]} (util/current-host)]
    (testing "host values are normalized and non-blank"
      (is (not (str/blank? os)))
      (is (not (str/blank? architecture))))))
