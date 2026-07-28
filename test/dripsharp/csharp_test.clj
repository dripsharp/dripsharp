(ns dripsharp.csharp-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.csharp :as csharp]))

(deftest structured-writer-preserves-precedence-and-nesting
  (testing "a lower-precedence child is parenthesized"
    (is (= "(a + b) * c"
           (:text
            (csharp/render
             (csharp/binary "*" 70
                            (csharp/binary "+" 60
                                           (csharp/raw "a")
                                           (csharp/raw "b"))
                            (csharp/raw "c")))))))
  (testing "an equal-precedence right child retains frontend nesting"
    (is (= "a - (b - c)"
           (:text
            (csharp/render
             (csharp/binary "-" 60
                            (csharp/raw "a")
                            (csharp/binary "-" 60
                                           (csharp/raw "b")
                                           (csharp/raw "c"))))))))
  (testing "postfix targets and arguments render deterministically"
    (is (= "items.Get(index + 1)"
           (:text
            (csharp/render
             (csharp/invocation
              (csharp/member (csharp/raw "items") "Get")
              [(csharp/binary "+" 60
                              (csharp/raw "index")
                              (csharp/raw "1"))]))))))
  (testing "generic method names remain atomic invocation targets"
    (is (= "Factory.Create<string>(value)"
           (:text
            (csharp/render
             (csharp/invocation
              (csharp/generic-name (csharp/raw "Factory.Create")
                                   [(csharp/raw "string")])
              [(csharp/raw "value")])))))))

(deftest structured-writer-derives-exact-source-ranges
  (let [left-source {:identity :left}
        whole-source {:identity :whole}
        rendered (csharp/render
                  (csharp/with-source
                    (csharp/binary "*" 70
                                   (csharp/with-source
                                     (csharp/binary "+" 60
                                                    (csharp/raw "a")
                                                    (csharp/raw "b"))
                                     left-source)
                                   (csharp/raw "c"))
                    whole-source))
        by-source (into {} (map (juxt :source identity) (:mappings rendered)))]
    (is (= "(a + b) * c" (:text rendered)))
    (is (= {:start 0 :end 7}
           (:destination (get by-source left-source))))
    (is (= {:start 0 :end 11}
           (:destination (get by-source whole-source))))))

(deftest structural-declarations-blocks-and-statement-lists-compose-mappings
  (let [statement-source {:identity :second-statement}
        node
        (csharp/declaration
         (csharp/raw "public class Example")
         (csharp/block
          (csharp/statement-list
           [(csharp/raw "first;")
            (csharp/with-source (csharp/raw "second;") statement-source)]
           "\n"
           "  "))
         {:declaration-kind :class :name "Example"})
        rendered (csharp/render node)
        second-start (.indexOf ^String (:text rendered) "second;")
        mapping (some #(when (= statement-source (:source %)) %)
                      (:mappings rendered))]
    (is (= (str "public class Example {\n"
                "  first;\n"
                "  second;\n"
                "}")
           (:text rendered)))
    (is (= {:start second-start
            :end (+ second-start (count "second;"))}
           (:destination mapping)))
    (is (= "public abstract void Run;"
           (:text
            (csharp/render
             (csharp/declaration
              (csharp/raw "public abstract void Run"))))))))

(deftest namespace-transforms-run-before-render-without-offset-constraints
  (let [source {:identity :compatibility-reference}
        node
        (csharp/sequence-node
         [(csharp/file-scoped-namespace "Example.Source")
          (csharp/raw "\n")
          (csharp/with-source
            (csharp/raw "global::DripSharp.Runtime.JavaCompat.Value")
            source)])
        transformed
        (csharp/transform-namespaces
         node
         {"Example.Source" "Example.Destination.With.More.Segments"
          "DripSharp.Runtime" "PdfCube.FontBox.Compatibility"})
        rendered (csharp/render transformed)
        reference "global::PdfCube.FontBox.Compatibility.JavaCompat.Value"
        reference-start (.indexOf ^String (:text rendered) reference)
        mapping (some #(when (= source (:source %)) %)
                      (:mappings rendered))]
    (is (= (str "namespace Example.Destination.With.More.Segments;\n"
                reference)
           (:text rendered)))
    (is (= {:start reference-start
            :end (+ reference-start (count reference))}
           (:destination mapping)))
    (is (= "namespace PdfCube.PdfBox.Contentstream.@Operator;"
           (:text
            (csharp/render
             (csharp/file-scoped-namespace
              "PdfCube.PdfBox.Contentstream.@Operator")))))))
