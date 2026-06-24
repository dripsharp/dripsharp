(ns vibeformer.vibeformer-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (org.jetbrains.kotlin.com.intellij.openapi.util Disposer)
           (org.jetbrains.kotlin.cli.jvm.compiler EnvironmentConfigFiles KotlinCoreEnvironment)
           (org.jetbrains.kotlin.config CompilerConfiguration)
           (org.jetbrains.kotlin.psi KtCallExpression KtClass KtNamedFunction KtNullableType KtObjectDeclaration KtProperty KtPsiFactory KtSafeQualifiedExpression)
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

(def kotlin-sample
  "package com.acme.parser

import java.util.Locale

object Fixture {
  val defaultName: String? = \"world\"
}

class KotlinGreeter(private val initialName: String?) {
  val name: String? = initialName
  val salutation: String = \"Hello\"

  fun greeting(locale: Locale): String {
    val normalized: String? = name?.uppercase(locale)
    return \"$salutation, ${normalized?.trim() ?: Fixture.defaultName}\"
  }
}

fun topLevelMessage(value: String?): String = value?.trim() ?: \"empty\"
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

(defn- parse-kotlin-source [source]
  (let [disposable (Disposer/newDisposable)
        environment (KotlinCoreEnvironment/createForProduction
                     disposable
                     (CompilerConfiguration.)
                     EnvironmentConfigFiles/JVM_CONFIG_FILES)
        psi-factory (KtPsiFactory. (.getProject environment) false)]
    {:disposable disposable
     :file (.createFile psi-factory "Sample.kt" source)}))

(defn- find-declaration [declarations clazz name]
  (some #(when (and (instance? clazz %)
                    (= name (.getName %)))
           %)
        declarations))

(defn- collect-psi-elements [root clazz]
  (letfn [(walk [element]
            (concat (when (instance? clazz element) [element])
                    (mapcat walk (.getChildren element))))]
    (doall (walk root))))

(defn- offset->line-column [source offset]
  (let [lines-before-offset (str/split (subs source 0 offset) #"\n" -1)]
    {:line (count lines-before-offset)
     :column (inc (count (last lines-before-offset)))}))

(defn- element-start-offset [element]
  (.getStartOffset (.getTextRange element)))

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

(deftest kotlin-psi-parses-source-into-useful-model
  (let [{:keys [disposable file]} (parse-kotlin-source kotlin-sample)]
    (try
      (let [top-level-declarations (.getDeclarations file)
            fixture (find-declaration top-level-declarations KtObjectDeclaration "Fixture")
            greeter (find-declaration top-level-declarations KtClass "KotlinGreeter")
            top-level-message (find-declaration top-level-declarations KtNamedFunction "topLevelMessage")
            greeter-declarations (.getDeclarations greeter)
            name-property (find-declaration greeter-declarations KtProperty "name")
            greeting-function (find-declaration greeter-declarations KtNamedFunction "greeting")
            greeting-params (.getValueParameters greeting-function)
            call-names (set (keep #(some-> (.getCalleeExpression %) .getText)
                                  (collect-psi-elements greeting-function KtCallExpression)))]
        (testing "package, object, class, and top-level function facts"
          (is (= "com.acme.parser" (.asString (.getPackageFqName file))))
          (is (= "Fixture" (.getName fixture)))
          (is (= "KotlinGreeter" (.getName greeter)))
          (is (= "topLevelMessage" (.getName top-level-message))))

        (testing "property and function facts include type references and nullability"
          (is (= "name" (.getName name-property)))
          (is (= "String?" (.getText (.getTypeReference name-property))))
          (is (instance? KtNullableType (.getTypeElement (.getTypeReference name-property))))
          (is (= "greeting" (.getName greeting-function)))
          (is (= "String" (.getText (.getTypeReference greeting-function))))
          (is (= ["Locale"] (mapv #(.getText (.getTypeReference %)) greeting-params))))

        (testing "expression facts include calls and safe-call syntax"
          (is (contains? call-names "uppercase"))
          (is (contains? call-names "trim"))
          (is (seq (collect-psi-elements greeting-function KtSafeQualifiedExpression))))

        (testing "source positions can be recovered from PSI text offsets"
          (is (= {:line 9 :column 1} (offset->line-column kotlin-sample (element-start-offset greeter))))
          (is (= {:line 13 :column 3} (offset->line-column kotlin-sample (element-start-offset greeting-function))))
          (is (= "Sample.kt" (.getName (.getContainingFile greeter))))))
      (finally
        (Disposer/dispose disposable)))))
