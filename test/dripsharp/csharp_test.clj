(ns dripsharp.csharp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest large-generated-sequences-render-in-one-pass-with-exact-ranges
  (let [item-count 20000
        fragments (mapv #(str "statement" % ";") (range item-count))
        rendered
        (csharp/render
         (csharp/sequence-node
          (mapv (fn [index fragment]
                  (csharp/with-source
                    (csharp/raw fragment)
                    {:identity index}))
                (range item-count)
                fragments)
          "\n"))
        mappings (:mappings rendered)
        last-fragment (peek fragments)
        last-start (.lastIndexOf ^String (:text rendered) last-fragment)]
    (is (= (str/join "\n" fragments) (:text rendered)))
    (is (= item-count (count mappings)))
    (is (= {:start 0 :end (count (first fragments))}
           (:destination (first mappings))))
    (is (= {:start last-start :end (+ last-start (count last-fragment))}
           (:destination (peek mappings))))))

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
          "DripSharp.Runtime" "DripSharp.PdfCarton.Fonts.Compatibility"})
        rendered (csharp/render transformed)
        reference "global::DripSharp.PdfCarton.Fonts.Compatibility.JavaCompat.Value"
        reference-start (.indexOf ^String (:text rendered) reference)
        mapping (some #(when (= source (:source %)) %)
                      (:mappings rendered))]
    (is (= (str "namespace Example.Destination.With.More.Segments;\n"
                reference)
           (:text rendered)))
    (is (= {:start reference-start
            :end (+ reference-start (count reference))}
           (:destination mapping)))
    (is (= "namespace DripSharp.PdfCarton.Contentstream.@Operator;"
           (:text
            (csharp/render
             (csharp/file-scoped-namespace
              "DripSharp.PdfCarton.Contentstream.@Operator")))))))

(deftest universal-presentation-golden-covers-nested-csharp-constructs
  (let [unpresented
        (str "public class Outer\n"
             "{\n"
             "public class Inner\n"
             "{\n"
             "public void Run(bool ready)\n"
             "{\n"
             "if (ready)\n"
             "{\n"
             "for (var index = 0; index < 2; index++)\n"
             "{\n"
             "while (ready)\n"
             "{\n"
             "switch (index)\n"
             "{\n"
             "case 0:\n"
             "try\n"
             "{\n"
             "var factory = () => new int[] { 1, 2, 3 };\n"
             "}\n"
             "catch (System.Exception error)\n"
             "{\n"
             "Handle(error);\n"
             "}\n"
             "finally\n"
             "{\n"
             "Finish();\n"
             "}\n"
             "break;\n"
             "default:\n"
             "break;\n"
             "}\n"
             "}\n"
             "}\n"
             "}\n"
             "}\n"
             "}\n"
             "}")
        expected
        (str "public class Outer {\n"
             "  public class Inner {\n"
             "    public void Run(bool ready) {\n"
             "      if (ready) {\n"
             "        for (var index = 0; index < 2; index++) {\n"
             "          while (ready) {\n"
             "            switch (index) {\n"
             "              case 0:\n"
             "                try {\n"
             "                  var factory = () => new int[] { 1, 2, 3 };\n"
             "                } catch (System.Exception error) {\n"
             "                  Handle(error);\n"
             "                } finally {\n"
             "                  Finish();\n"
             "                }\n"
             "                break;\n"
             "              default:\n"
             "                break;\n"
             "            }\n"
             "          }\n"
             "        }\n"
             "      }\n"
             "    }\n"
             "  }\n"
             "}")]
    (is (= expected (csharp/present-text unpresented)))
    (is (= expected (csharp/present-text expected)))))

(deftest renderable-constructs-wrap-at-one-deterministic-width
  (let [literal
        "\"this-is-one-indivisible-literal-that-is-longer-than-the-width\""
        node
        (csharp/declaration
         (csharp/raw "public class Data")
         (csharp/block
          [(csharp/invocation
            (csharp/generic-name
             (csharp/raw "Call")
             [(csharp/generic-name
               (csharp/raw "Dictionary")
               [(csharp/raw "string")
                (csharp/generic-name (csharp/raw "List")
                                     [(csharp/raw "int")])])])
            [(csharp/raw "firstArgument")
             (csharp/raw "secondArgument")
             (csharp/raw "thirdArgument")
             (csharp/raw literal)])]))
        rendered (csharp/render node {:width 50})
        rendered-again (csharp/render node {:width 50})
        ordinary-lines (remove #(str/includes? % literal)
                               (str/split-lines (:text rendered)))]
    (is (= rendered rendered-again))
    (is (every? #(<= (count %) 50) ordinary-lines))
    (is (some #(str/includes? % literal) (str/split-lines (:text rendered))))
    (is (str/includes? (:text rendered) "Dictionary<string,"))
    (is (str/includes? (:text rendered) "\n    List<int>"))))

(deftest structural-rendering-remains-unpresented-for-translator-rewrites
  (let [source
        "global::Example.Matrix value = global::Example.Matrix.Concatenate(left, right);"
        node (csharp/raw source)]
    (is (= source (:text (csharp/render-raw node))))
    (is (str/includes? (:text (csharp/render node {:width 40})) "\n"))))

(deftest large-generated-data-tables-wrap-without-changing-tokens
  (let [values (mapv str (range 500))
        source (str "class Table {\nint[] Values = new int[] { "
                    (str/join ", " values)
                    " };\n}")
        presented (csharp/present-text source {:width 60})
        lines (str/split-lines presented)]
    (is (= presented (csharp/present-text presented {:width 60})))
    (is (every? #(<= (count %) 60) lines))
    (is (= 500 (count (re-seq #"\b\d+\b" presented))))))

(deftest large-mapping-tables-remap-each-token-exactly
  (let [values (range 2000)
        entries
        (mapcat
         (fn [value]
           [(when (pos? value) (csharp/raw ", "))
            (csharp/with-source (csharp/raw (str value))
              {:identity value :rule :fixture/data-value})])
         values)
        node
        (csharp/sequence-node
         (into [(csharp/raw "int[] Values = new int[] { ")]
               (concat (remove nil? entries) [(csharp/raw " };")])))
        rendered (csharp/render node {:width 60})
        mappings (filter #(= :fixture/data-value
                             (get-in % [:source :rule]))
                         (:mappings rendered))]
    (is (= 2000 (count mappings)))
    (doseq [value [0 1 99 999 1999]
            :let [mapping (some #(when (= value
                                          (get-in % [:source :identity]))
                                   %)
                                mappings)
                  {:keys [start end]} (:destination mapping)]]
      (is (= (str value) (subs (:text rendered) start end))))))

(deftest repeated-presentation-does-not-wrap-an-end-of-line-comma
  (let [source
        (str "internal static readonly Factory<Language> value = "
             "new Factory<Language>(GetLanguageClass)"
             ".AddStringProperty(\"version\", (item) => item.Version);")
        once (csharp/present-text source {:width 60})
        twice (csharp/present-text once {:width 60})]
    (is (= once twice))
    (is (not (re-find #"(?m)^[ \t]+$" twice)))
    (is (not (str/includes? twice ",\n\n")))
    (is (= "value;\nnext;"
           (csharp/present-text "value;   \nnext;\t" {:width 60})))
    (is (= "value;\n\nnext;"
           (csharp/present-text "value;\n  \nnext;" {:width 60})))))

(deftest multiline-literal-contents-survive-presentation-byte-for-byte
  (let [raw-literal (str "\"\"\"\n"
                         "  raw content  \n"
                         "   \n"
                         "{\n"
                         "}\n"
                         "\"\"\"")
        verbatim-literal (str "@\"verbatim content\n"
                              "  indented content  \n"
                              "   \n"
                              "{\n"
                              "}\n"
                              "last\"")
        source (str "class Literals\n"
                    "{\n"
                    "string Raw = " raw-literal ";\n"
                    "string Verbatim = " verbatim-literal ";\n"
                    "}\n")
        presented (csharp/present-text source)]
    (is (str/includes? presented raw-literal))
    (is (str/includes? presented verbatim-literal))
    (is (= presented (csharp/present-text presented)))))

(deftest redundant-parentheses-are-removed-only-through-precedence
  (let [text (comp :text csharp/render)]
    (testing "unary and binary contexts"
      (is (= "!flag"
             (text (csharp/parenthesized
                    (csharp/prefix "!" (csharp/raw "flag"))))))
      (is (= "-(-value)"
             (text (csharp/prefix
                    "-"
                    (csharp/parenthesized
                     (csharp/prefix "-" (csharp/raw "value")))))))
      (is (= "(a + b) * c"
             (text
              (csharp/binary
               "*"
               (csharp/parenthesized
                (csharp/binary "+" (csharp/raw "a") (csharp/raw "b")))
               (csharp/raw "c"))))))
    (testing "conditional, cast, and assignment contexts"
      (is (= "ok ? yes : no"
             (text
              (csharp/parenthesized
               (csharp/conditional (csharp/raw "ok")
                                   (csharp/raw "yes")
                                   (csharp/raw "no"))))))
      (is (= "(int)value"
             (text
              (csharp/parenthesized
               (csharp/cast (csharp/raw "int") (csharp/raw "value"))))))
      (is (= "a = b"
             (text
              (csharp/parenthesized
               (csharp/assignment (csharp/raw "a") (csharp/raw "b")))))))
    (testing "invocation and member-access contexts"
      (is (= "service.Run(value)"
             (text
              (csharp/invocation
               (csharp/parenthesized
                (csharp/member (csharp/raw "service") "Run"))
               [(csharp/raw "value")]))))
      (is (= "(ok ? left : right).Length"
             (text
              (csharp/member
               (csharp/parenthesized
                (csharp/conditional (csharp/raw "ok")
                                    (csharp/raw "left")
                                    (csharp/raw "right")))
               "Length")))))))

(deftest source-ranges-are-finalized-after-wrapping
  (let [source {:identity :wrapped-invocation :rule :fixture/invocation}
        node
        (csharp/declaration
         (csharp/raw "class Example")
         (csharp/block
          [(csharp/with-source
             (csharp/invocation
              (csharp/raw "Call")
              [(csharp/raw "firstArgument")
               (csharp/raw "secondArgument")
               (csharp/raw "thirdArgument")])
             source)]))
        rendered (csharp/render node {:width 40})
        mapping (some #(when (= source (:source %)) %) (:mappings rendered))
        {:keys [start end]} (:destination mapping)]
    (is (= (str "Call(firstArgument, secondArgument,\n"
                "    thirdArgument)")
           (subs (:text rendered) start end)))
    (is (= mapping
           (some #(when (= source (:source %)) %)
                 (:mappings (csharp/render node {:width 40})))))))
