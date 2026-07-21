(ns vibeformer.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.compiler :as compiler]
            [vibeformer.paths :as paths])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(deftest historical-declaration-failures-map-to-spoon-rules
  (let [root (Files/createTempDirectory "vibeformer-compiler-map"
                                        (make-array FileAttribute 0))
        file (paths/resolve-path root "src/Pkl/Parser/Syntax/Expr.cs")
        text "public class SingleLineStringLiteralExpr\n{\n  public object GetParts() => null;\n}\n"
        _ (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
        _ (Files/writeString file text (make-array OpenOption 0))
        class-start (.indexOf text "SingleLineStringLiteralExpr")
        method-start (.indexOf text "GetParts")
        mappings
        [{:file "src/Pkl/Parser/Syntax/Expr.cs"
          :destination {:start class-start :end (+ class-start 27)}
          :source {:frontend-class "spoon.support.reflect.declaration.CtClassImpl"
                   :location {:file "research/pkl/pkl-parser/src/main/java/org/pkl/parser/syntax/Expr.java"
                              :line 123 :column 29}
                   :rule :java.declaration/class}}
         {:file "src/Pkl/Parser/Syntax/Expr.cs"
          :destination {:start method-start :end (+ method-start 8)}
          :source {:frontend-class "spoon.support.reflect.declaration.CtMethodImpl"
                   :location {:file "research/pkl/pkl-parser/src/main/java/org/pkl/parser/syntax/Expr.java"
                              :line 148 :column 29}
                   :rule :java.declaration/method}}]
        output (str file "(1,14): error CS0534: 'SingleLineStringLiteralExpr' does not implement inherited abstract member "
                    "[" root "/Pkl.Parser.csproj]\n"
                    file "(3,17): warning CS0114: 'GetParts()' hides inherited member "
                    "[" root "/Pkl.Parser.csproj]\n")
        diagnostics (compiler/parse-diagnostics output)
        mapped (compiler/map-diagnostics root {:mappings mappings} diagnostics)]
    (testing "historical Roslyn declaration diagnostics retain exact codes"
      (is (= ["CS0534" "CS0114"] (mapv :code mapped))))
    (testing "the narrowest generated range identifies the Spoon declaration rule"
      (is (= [:java.declaration/class :java.declaration/method]
             (mapv :translation-rule mapped)))
      (is (= ["spoon.support.reflect.declaration.CtClassImpl"
              "spoon.support.reflect.declaration.CtMethodImpl"]
             (mapv #(get-in % [:source :frontend-class]) mapped))))))

(deftest duplicate-msbuild-summary-diagnostics-are-collapsed
  (let [line "/tmp/A.cs(2,3): warning CS0114: member hides inherited member [/tmp/A.csproj]"
        diagnostics (compiler/parse-diagnostics (str line "\n" line "\n"))]
    (is (= 1 (count diagnostics)))
    (is (= :warning (:severity (first diagnostics))))))

(deftest explicit-profile-is-cleanly-generated-and-built-in-release
  (let [root (Files/createTempDirectory "vibeformer-compiler-profile"
                                        (make-array FileAttribute 0))
        project-file (paths/resolve-path root "Pkl.Core.csproj")
        source-map (paths/resolve-path root "source-map.edn")
        generated-options (atom nil)
        command (atom nil)
        verified (atom nil)
        _ (Files/writeString project-file "<Project />" (make-array OpenOption 0))
        _ (Files/writeString source-map "{:schema-version 1 :mappings []}\n"
                             (make-array OpenOption 0))
        result
        (compiler/verify-clean-build!
         {:workspace-root root
          :profile "pkl-core-value-model"
          :generate-fn
          (fn [options]
            (reset! generated-options options)
            {:destination {:package {:id "Pkl.Core"}
                           :output {:source-map-file "source-map.edn"}}
             :emission {:project-root root :project-file project-file}})
          :run-command!
          (fn [request]
            (reset! command (:command request))
            {:exit 0 :output ""})
          :verify-public-surface-fn
          (fn [workspace generation configuration]
            (reset! verified [workspace generation configuration])
            {:contract-members 2})})]
    (is (= {:workspace-root root :profile "pkl-core-value-model"}
           @generated-options))
    (is (= ["--configuration" "Release"]
           (->> @command (drop-while #(not= "--configuration" %)) (take 2) vec)))
    (is (= "Release" (nth @verified 2)))
    (is (= {:contract-members 2} (:public-surface result)))
    (is (= "Release" (:build-configuration result)))))
