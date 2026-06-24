(ns vibeformer.vibeformer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (spoon Launcher)
           (spoon.reflect.code CtInvocation)
           (spoon.reflect.declaration ModifierKind)
           (spoon.reflect.visitor.filter TypeFilter)))

(def java-sample
  "package com.acme.parser;

import java.util.Locale;

public final class Greeter {
  private final String name;

  public Greeter(String name) {
    this.name = name;
  }

  public String greeting(Locale locale) {
    String normalized = name.toUpperCase(locale);
    return \"Hello, \" + normalized.trim();
  }
}
")

(defn- write-java-sample! []
  (let [root (Files/createTempDirectory "vibeformer-spoon-" (make-array java.nio.file.attribute.FileAttribute 0))
        package-dir (.resolve root "com/acme/parser")
        source-file (.resolve package-dir "Greeter.java")]
    (Files/createDirectories package-dir (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString source-file java-sample StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    source-file))

(defn- parse-java-source [source-file]
  (let [launcher (Launcher.)]
    (doto (.getEnvironment launcher)
      (.setNoClasspath true)
      (.setComplianceLevel 17)
      (.setAutoImports true))
    (.addInputResource launcher (str source-file))
    (.buildModel launcher)
    (.get (.Type (.getFactory launcher)) "com.acme.parser.Greeter")))

(defn- valid-position? [element]
  (let [position (.getPosition element)]
    (and (.isValidPosition position)
         (= "Greeter.java" (.getName (io/file (.getFile position))))
         (pos? (.getLine position))
         (pos? (.getColumn position)))))

(deftest spoon-parses-java-source-into-useful-model
  (let [greeter (parse-java-source (write-java-sample!))
        name-field (.getField greeter "name")
        greeting-method (first (.getMethodsByName greeter "greeting"))
        greeting-params (.getParameters greeting-method)
        invocations (.getElements greeting-method (TypeFilter. CtInvocation))
        invocation-names (set (map #(.getSimpleName (.getExecutable %)) invocations))]
    (testing "package, class name, and class modifiers"
      (is (= "com.acme.parser" (.getQualifiedName (.getPackage greeter))))
      (is (= "Greeter" (.getSimpleName greeter)))
      (is (= "com.acme.parser.Greeter" (.getQualifiedName greeter)))
      (is (contains? (.getModifiers greeter) ModifierKind/PUBLIC))
      (is (contains? (.getModifiers greeter) ModifierKind/FINAL)))

    (testing "field facts include modifiers, type references, and positions"
      (is (= "name" (.getSimpleName name-field)))
      (is (= "java.lang.String" (.getQualifiedName (.getType name-field))))
      (is (contains? (.getModifiers name-field) ModifierKind/PRIVATE))
      (is (contains? (.getModifiers name-field) ModifierKind/FINAL))
      (is (valid-position? name-field)))

    (testing "method facts include signature, type references, calls, and positions"
      (is (= "greeting" (.getSimpleName greeting-method)))
      (is (= "java.lang.String" (.getQualifiedName (.getType greeting-method))))
      (is (= ["java.util.Locale"] (mapv #(.getQualifiedName (.getType %)) greeting-params)))
      (is (contains? invocation-names "toUpperCase"))
      (is (contains? invocation-names "trim"))
      (is (valid-position? greeting-method)))))
