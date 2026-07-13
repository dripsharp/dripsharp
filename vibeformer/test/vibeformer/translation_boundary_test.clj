(ns vibeformer.translation-boundary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- source [relative]
  (slurp (str "src/vibeformer/" relative ".clj")))

(deftest reusable-translation-kernel-is-product-neutral
  (let [kernel (source "java_translate")
        frontend (source "spoon")]
    (testing "the reusable kernel does not depend on the Pkl rule bundle"
      (is (not (str/includes? kernel "vibeformer.pkl"))))
    (testing "Pkl source identities and destinations are absent from reusable layers"
      (doseq [content [kernel frontend]]
        (is (not (re-find #"(?i)org\\.pkl|Pkl\\.Core|Pkl\\.Parser" content)))))))

(deftest pkl-rules-depend-inward-on-the-reusable-kernel
  (let [body-rules (source "pkl/java_body")
        project-rules (source "pkl/java_project")]
    (is (str/includes? body-rules "(ns vibeformer.pkl.java-body"))
    (is (str/includes? project-rules "(ns vibeformer.pkl.java-project"))
    (is (str/includes? body-rules "[vibeformer.java-translate :as java]"))
    (is (str/includes? project-rules "[vibeformer.java-translate :as java]"))))
