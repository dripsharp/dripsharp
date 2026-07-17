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

(deftest java-uri-component-mappings-retain-decoded-and-raw-api-pairs
  (let [body-rules (source "pkl/java_body")
        runtime (slurp "runtime/Vibeformer.JavaCompat.cs")]
    (doseq [[java-method helper]
            [["getAuthority" "UriAuthority"]
             ["getFragment" "UriFragment"]
             ["getPath" "UriPath"]
             ["getQuery" "UriQuery"]
             ["getSchemeSpecificPart" "UriSchemeSpecificPart"]
             ["getUserInfo" "UriUserInfo"]
             ["getRawAuthority" "UriRawAuthority"]
             ["getRawFragment" "UriRawFragment"]
             ["getRawPath" "UriRawPath"]
             ["getRawQuery" "UriRawQuery"]
             ["getRawSchemeSpecificPart" "UriRawSchemeSpecificPart"]
             ["getRawUserInfo" "UriRawUserInfo"]]]
      (is (str/includes?
           body-rules
           (str "executable:java.net.URI#" java-method "()\" (compat-call \""
                helper "\" [target])")))
      (is (str/includes? runtime (str " " helper "(Uri uri)"))))
    (is (str/includes?
         runtime
         "UriSchemeSpecificPart(Uri uri) =>\n        DecodeUriComponent(UriRawSchemeSpecificPart(uri))"))
    (is (str/includes?
         runtime
         "UriFragment(Uri uri) => DecodeUriComponent(UriRawFragment(uri))"))
    (is (str/includes?
         runtime
         "UriQuery(Uri uri) => DecodeUriComponent(UriRawQuery(uri))"))
    (is (str/includes?
         runtime
         "UriPath(Uri uri) => DecodeUriComponent(UriRawPath(uri))"))))
