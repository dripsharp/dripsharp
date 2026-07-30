(ns dripsharp.pkl.differential
  "Independent upstream-JVM versus packaged-.NET parser and core behavior validation."
  (:require [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.package-provenance :as package-provenance]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.project :as project]
            [dripsharp.project-input :as project-input]
            [dripsharp.pkl.public-api-contract :as public-api-contract]
            [dripsharp.util :as util])
  (:import [java.io ByteArrayInputStream File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]
           [java.util.zip GZIPInputStream]))

(def ^:private inline-cases
  [["edge/lexer-single-backtick" "`"]
   ["edge/lexer-sentinel-between-tokens" "// Comment with \uFFFF character\nclass \uFFFF Bar"]
   ["edge/lexer-line-continuation-crlf" "x = \"\"\"\n  hello \\\r\n  world\r\n  \"\"\""]
   ["edge/lexer-line-continuation-cr" "x = \"\"\"\n  hello \\\r  world\n  \"\"\""]
   ["edge/lexer-line-continuation-whitespace-error" "x = \"\"\"\n  hello \\ \r\n  world\n  \"\"\""]
   ["edge/unicode-identifier" "जावास्क्रिप्ट = 1\n"]
   ["edge/string-interpolation-escapes" "name = \"Pigeon\"\nmessage = \"Hello, \\(name)\\n\"\n"]])

(def ^:private unicode-comment-codepoints
  [0x0000 0x0001 0x007f 0x0080 0x7ffe 0x7fff 0x8000 0xfffe 0xffff])

(def ^:private float-fraction-digit-cases
  [{:id "to-fixed/fraction-00" :expression "1.2345678901234567.toFixed(0)" :expected "1" :digits 0}
   {:id "to-fixed/fraction-01" :expression "1.2345678901234567.toFixed(1)" :expected "1.2" :digits 1}
   {:id "to-fixed/fraction-02" :expression "1.2345678901234567.toFixed(2)" :expected "1.23" :digits 2}
   {:id "to-fixed/fraction-03" :expression "1.2345678901234567.toFixed(3)" :expected "1.235" :digits 3}
   {:id "to-fixed/fraction-04" :expression "1.2345678901234567.toFixed(4)" :expected "1.2346" :digits 4}
   {:id "to-fixed/fraction-05" :expression "1.2345678901234567.toFixed(5)" :expected "1.23457" :digits 5}
   {:id "to-fixed/fraction-06" :expression "1.2345678901234567.toFixed(6)" :expected "1.234568" :digits 6}
   {:id "to-fixed/fraction-07" :expression "1.2345678901234567.toFixed(7)" :expected "1.2345679" :digits 7}
   {:id "to-fixed/fraction-08" :expression "1.2345678901234567.toFixed(8)" :expected "1.23456789" :digits 8}
   {:id "to-fixed/fraction-09" :expression "1.2345678901234567.toFixed(9)" :expected "1.234567890" :digits 9}
   {:id "to-fixed/fraction-10" :expression "1.2345678901234567.toFixed(10)" :expected "1.2345678901" :digits 10}
   {:id "to-fixed/fraction-11" :expression "1.2345678901234567.toFixed(11)" :expected "1.23456789012" :digits 11}
   {:id "to-fixed/fraction-12" :expression "1.2345678901234567.toFixed(12)" :expected "1.234567890123" :digits 12}
   {:id "to-fixed/fraction-13" :expression "1.2345678901234567.toFixed(13)" :expected "1.2345678901235" :digits 13}
   {:id "to-fixed/fraction-14" :expression "1.2345678901234567.toFixed(14)" :expected "1.23456789012346" :digits 14}
   {:id "to-fixed/fraction-15" :expression "1.2345678901234567.toFixed(15)" :expected "1.234567890123457" :digits 15}
   {:id "to-fixed/fraction-16" :expression "1.2345678901234567.toFixed(16)" :expected "1.2345678901234567" :digits 16}
   {:id "to-fixed/fraction-17" :expression "1.2345678901234567.toFixed(17)" :expected "1.23456789012345670" :digits 17}
   {:id "to-fixed/fraction-18" :expression "1.2345678901234567.toFixed(18)" :expected "1.234567890123456700" :digits 18}
   {:id "to-fixed/fraction-19" :expression "1.2345678901234567.toFixed(19)" :expected "1.2345678901234567000" :digits 19}
   {:id "to-fixed/fraction-20" :expression "1.2345678901234567.toFixed(20)" :expected "1.23456789012345670000" :digits 20}])

(def ^:private integer-fraction-digit-cases
  (mapv (fn [digits]
          {:id (format "to-fixed/int-fraction-%02d" digits)
           :expression (format "42.toFixed(%d)" digits)
           :expected (if (zero? digits)
                       "42"
                       (str "42." (apply str (repeat digits "0"))))
           :digits digits})
        (range 21)))

(def ^:private maximum-double-fixed
  (str "17976931348623157" (apply str (repeat 292 "0"))))

(def ^:private to-fixed-edge-cases
  [{:id "to-fixed/decimal-below" :expression "2.6749999999999994.toFixed(2)" :expected "2.67" :digits 2}
   {:id "to-fixed/decimal-shortest-below" :expression "2.675.toFixed(2)" :expected "2.67" :digits 2}
   {:id "to-fixed/decimal-above" :expression "2.6750000000000003.toFixed(2)" :expected "2.68" :digits 2}
   {:id "to-fixed/negative-decimal-below" :expression "(-2.6749999999999994).toFixed(2)" :expected "-2.67" :digits 2}
   {:id "to-fixed/negative-decimal-shortest-below" :expression "(-2.675).toFixed(2)" :expected "-2.67" :digits 2}
   {:id "to-fixed/negative-decimal-above" :expression "(-2.6750000000000003).toFixed(2)" :expected "-2.68" :digits 2}
   {:id "to-fixed/binary-below-half" :expression "2.6249999999999996.toFixed(2)" :expected "2.62" :digits 2}
   {:id "to-fixed/binary-exact-half-even" :expression "2.625.toFixed(2)" :expected "2.62" :digits 2}
   {:id "to-fixed/binary-above-half" :expression "2.6250000000000004.toFixed(2)" :expected "2.63" :digits 2}
   {:id "to-fixed/negative-binary-below-half" :expression "(-2.6249999999999996).toFixed(2)" :expected "-2.62" :digits 2}
   {:id "to-fixed/negative-binary-exact-half-even" :expression "(-2.625).toFixed(2)" :expected "-2.62" :digits 2}
   {:id "to-fixed/negative-binary-above-half" :expression "(-2.6250000000000004).toFixed(2)" :expected "-2.63" :digits 2}
   {:id "to-fixed/one-point-zero-one-five" :expression "1.015.toFixed(2)" :expected "1.01" :digits 2}
   {:id "to-fixed/negative-one-point-zero-one-five" :expression "(-1.015).toFixed(2)" :expected "-1.01" :digits 2}
   {:id "to-fixed/positive-zero" :expression "0.0.toFixed(20)" :expected "0.00000000000000000000" :digits 20}
   {:id "to-fixed/negative-zero" :expression "(-0.0).toFixed(20)" :expected "-0.00000000000000000000" :digits 20}
   {:id "to-fixed/minimum-positive-double" :expression "4.9E-324.toFixed(20)" :expected "0.00000000000000000000" :digits 20}
   {:id "to-fixed/minimum-negative-double" :expression "(-4.9E-324).toFixed(20)" :expected "-0.00000000000000000000" :digits 20}
   {:id "to-fixed/maximum-positive-double" :expression "1.7976931348623157E308.toFixed(0)" :expected maximum-double-fixed :digits 0}
   {:id "to-fixed/maximum-negative-double" :expression "(-1.7976931348623157E308).toFixed(20)" :expected (str "-" maximum-double-fixed ".00000000000000000000") :digits 20}
   {:id "to-fixed/not-a-number" :expression "NaN.toFixed(7)" :expected "NaN" :digits 7}
   {:id "to-fixed/positive-infinity" :expression "Infinity.toFixed(8)" :expected "Infinity" :digits 8}
   {:id "to-fixed/negative-infinity" :expression "(-Infinity).toFixed(9)" :expected "-Infinity" :digits 9}
   {:id "to-fixed/negative-integer" :expression "(-123).toFixed(7)" :expected "-123.0000000" :digits 7}
   {:id "to-fixed/maximum-integer" :expression "9223372036854775807.toFixed(20)" :expected "9223372036854775807.00000000000000000000" :digits 20}
   {:id "to-fixed/minimum-integer" :expression "(-9223372036854775808).toFixed(20)" :expected "-9223372036854775808.00000000000000000000" :digits 20}])

(def ^:private to-fixed-cases
  (into [] (concat float-fraction-digit-cases
                   integer-fraction-digit-cases
                   to-fixed-edge-cases)))

(def ^:private regex-compat-cases
  [;; Pattern text, flags, quoting, literal escapes, and compile failures.
   ["regex/pattern/default-flags" "PATTERN" 0 "a(b)" "" ""]
   ["regex/pattern/unicode-implies-unicode-case" "PATTERN" 256 "a" "" ""]
   ["regex/pattern/all-flags" "PATTERN" 511 "a" "" ""]
   ["regex/pattern/unknown-flag" "PATTERN" 512 "a" "" ""]
   ["regex/quote/metacharacters" "QUOTE_PATTERN" 0 ".a[0]" ".a[0]" ""]
   ["regex/quote/embedded-end-marker" "QUOTE_PATTERN" 0 "a\\Eb" "a\\Eb" ""]
   ["regex/quote/direct-qe" "MATCHES" 0 "\\Q.a[0]\\E" ".a[0]" ""]
   ["regex/escape/octal" "MATCHES" 0 "\\0141" "a" ""]
   ["regex/escape/hex" "MATCHES" 0 "\\x61\\u0062" "ab" ""]
   ["regex/escape/codepoint" "MATCHES" 0 "\\x{1F600}" "😀" ""]
   ["regex/escape/unicode-name" "MATCHES" 0 "\\N{GREEK CAPITAL LETTER OMEGA}" "Ω" ""]
   ["regex/escape/unicode-name-table" "MATCHES" 0 "\\N{PILE OF POO}" "💩" ""]
   ["regex/escape/unicode-name-hangul" "MATCHES" 0 "\\N{HANGUL SYLLABLES AC00}" "가" ""]
   ["regex/escape/unicode-name-cjk" "MATCHES" 0 "\\N{CJK UNIFIED IDEOGRAPHS 4E00}" "一" ""]
   ["regex/escape/unicode-name-private" "MATCHES" 0 "\\N{PRIVATE USE AREA E000}" "" ""]
   ["regex/escape/control" "MATCHES" 0 "\\cJ" "\n" ""]
   ["regex/syntax/dangling" "MATCHES" 0 "*" "" ""]
   ["regex/syntax/unknown-escape" "MATCHES" 0 "\\y" "y" ""]

   ;; Compile flags and their inline equivalents.
   ["regex/flags/ascii-case" "MATCHES" 2 "Ä" "ä" ""]
   ["regex/flags/ascii-case-class" "MATCHES" 2 "[a-z]+" "ABC" ""]
   ["regex/flags/ascii-case-negated-class" "MATCHES" 2 "[^a]" "A" ""]
   ["regex/flags/ascii-case-literal" "MATCHES" 18 "a.+" "A.+" ""]
   ["regex/flags/unicode-case" "MATCHES" 66 "Ä" "ä" ""]
   ["regex/flags/unicode-case-class" "MATCHES" 66 "[α]+" "Α" ""]
   ["regex/flags/literal" "MATCHES" 16 ".+" ".+" ""]
   ["regex/flags/comments" "MATCHES" 4 "a # comment\n b" "ab" ""]
   ["regex/flags/comments-class" "MATCHES" 4 "[ a ]" "a" ""]
   ["regex/flags/unix-lines-dot" "MATCHES" 1 "." "\r" ""]
   ["regex/flags/dotall" "MATCHES" 32 ".+" "a\nb" ""]
   ["regex/flags/multiline-cr" "FIND" 8 "^b$" "a\rb" ""]
   ["regex/flags/unix-lines-cr" "FIND" 9 "^b$" "a\rb" ""]
   ["regex/flags/canonical-equivalence" "MATCHES" 128 "å" "å" ""]
   ["regex/flags/inline-enable-disable" "MATCHES" 0 "(?i:a)(?-i:b)" "Ab" ""]
   ["regex/flags/scoped-unicode-class" "MATCHES" 0 "(?U:\\w+)" "café" ""]

   ;; Character classes, properties, astral code points, and class algebra.
   ["regex/class/default-ascii-word" "MATCHES" 0 "\\w+" "café" ""]
   ["regex/class/unicode-word" "MATCHES" 256 "\\w+" "café" ""]
   ["regex/class/default-ascii-digit" "MATCHES" 0 "\\d" "٣" ""]
   ["regex/class/unicode-digit" "MATCHES" 256 "\\d" "٣" ""]
   ["regex/class/horizontal" "MATCHES" 0 "\\h+" " \t" ""]
   ["regex/class/vertical" "MATCHES" 0 "\\v+" "\n " ""]
   ["regex/class/union" "MATCHES" 0 "[a-d[m-p]]+" "camp" ""]
   ["regex/class/intersection" "MATCHES" 0 "[a-z&&[def]]+" "feed" ""]
   ["regex/class/subtraction" "MATCHES" 0 "[a-z&&[^m-p]]+" "lazy" ""]
   ["regex/class/nested-subtraction" "MATCHES" 0 "[[^/]&&[^\\p{Alnum}+.-]]" "_" ""]
   ["regex/class/astral-literal" "MATCHES" 0 "[😀😈]+" "😀😈" ""]
   ["regex/class/astral-negation" "MATCHES" 0 "[^😀]+" "😈" ""]
   ["regex/class/octal-escape" "MATCHES" 0 "[\\0141]" "a" ""]
   ["regex/class/unicode-escape-range" "MATCHES" 0 "[\\u0061-\\u0063]+" "abc" ""]
   ["regex/class/codepoint-escape" "MATCHES" 0 "[\\x{1F600}]" "😀" ""]
   ["regex/class/unicode-name" "MATCHES" 0 "[\\N{PILE OF POO}]" "💩" ""]
   ["regex/class/quoted" "MATCHES" 0 "[\\Q.+-\\E]+" ".+-" ""]
   ["regex/property/posix-ascii" "MATCHES" 0 "\\p{Lower}+" "abc" ""]
   ["regex/property/posix-unicode" "MATCHES" 256 "\\p{Lower}+" "é" ""]
   ["regex/property/posix-ascii-case" "MATCHES" 2 "\\p{Lower}+" "ABC" ""]
   ["regex/property/posix-unicode-case" "MATCHES" 258 "\\p{Lower}+" "É" ""]
   ["regex/property/java-whitespace" "MATCHES" 0 "\\p{javaWhitespace}" " " ""]
   ["regex/property/java-identifier" "MATCHES" 0 "\\p{javaJavaIdentifierStart}" "$" ""]
   ["regex/property/java-ideographic" "MATCHES" 0 "\\p{javaIdeographic}" "一" ""]
   ["regex/property/block" "MATCHES" 0 "\\p{InGreek}+" "Ω" ""]
   ["regex/property/block-equality" "MATCHES" 0 "\\p{blk=Emoticons}" "😀" ""]
   ["regex/property/script" "MATCHES" 0 "\\p{IsLatin}+" "é" ""]
   ["regex/property/script-equality" "MATCHES" 0 "\\p{script=Gothic}" "𐌰" ""]
   ["regex/property/script-iso-alias" "MATCHES" 0 "\\p{sc=Latn}+" "é" ""]
   ["regex/property/category" "MATCHES" 0 "\\p{Lu}+" "ΩA" ""]
   ["regex/property/category-equality" "MATCHES" 0 "\\p{gc=No}" "½" ""]
   ["regex/property/category-format" "MATCHES" 0 "\\p{Cf}" "‍" ""]
   ["regex/property/category-private" "MATCHES" 0 "\\p{Co}" "" ""]
   ["regex/property/category-quote" "MATCHES" 0 "\\p{Pi}" "“" ""]
   ["regex/property/category-symbol" "MATCHES" 0 "\\p{Sm}" "+" ""]
   ["regex/property/binary" "MATCHES" 0 "\\p{IsAlphabetic}+" "ΩA" ""]
   ["regex/property/binary-emoji" "MATCHES" 0 "\\p{IsEmoji}" "💩" ""]

   ;; Boundaries, line breaks, graphemes, grouping, and quantifier modes.
   ["regex/boundary/word" "FIND" 0 "\\bword\\b" "a word!" ""]
   ["regex/boundary/non-word" "FIND" 0 "\\Boo\\B" "zooom" ""]
   ["regex/boundary/input" "MATCHES" 0 "\\Aabc\\z" "abc" ""]
   ["regex/boundary/final-terminator" "FIND" 0 "abc\\Z" "abc\r\n" ""]
   ["regex/boundary/previous-match" "FIND" 0 "\\G." "abc" ""]
   ["regex/matcher/looking-at" "LOOKING_AT" 0 "ab" "abc" ""]
   ["regex/linebreak/unicode" "MATCHES" 0 "a\\Rb" "a\r\nb" ""]
   ["regex/grapheme/cluster" "FIND" 0 "\\X" "á" ""]
   ["regex/grapheme/regional-indicators" "FIND" 0 "\\X" "🇺🇸🇨🇦" ""]
   ["regex/grapheme/hangul" "MATCHES" 0 "\\X" "가" ""]
   ["regex/grapheme/prepend" "MATCHES" 0 "\\X" "؀A" ""]
   ["regex/grapheme/emoji-modifier" "MATCHES" 0 "\\X" "👍🏽" ""]
   ["regex/grapheme/emoji-zwj" "MATCHES" 0 "\\X" "👩‍❤️‍💋‍👨" ""]
   ["regex/grapheme/boundary" "FIND" 0 "a\\b{g}" "á" ""]
   ["regex/group/numeric-order" "FIND" 0 "(?<first>a)(b)(c)?" "ab" ""]
   ["regex/group/numeric-backref" "MATCHES" 0 "(?<first>a)(b)\\1\\2" "abab" ""]
   ["regex/group/named-backref" "MATCHES" 0 "(?<word>ab)-\\k<word>" "ab-ab" ""]
   ["regex/group/lookahead" "FIND" 0 "a(?=b)" "zab" ""]
   ["regex/group/lookbehind" "FIND" 0 "(?<=a)b" "zab" ""]
   ["regex/group/atomic" "MATCHES" 0 "(?>a|ab)c" "abc" ""]
   ["regex/quantifier/greedy" "FIND" 0 "a+" "aaaa" ""]
   ["regex/quantifier/reluctant" "FIND" 0 "a+?" "aaaa" ""]
   ["regex/quantifier/possessive" "MATCHES" 0 "a++a" "aaaa" ""]
   ["regex/quantifier/possessive-range" "MATCHES" 0 "a{2,3}+a" "aaaa" ""]

   ;; Matcher region/zero-width state, split, and replacement contracts.
   ["regex/matcher/zero-width-astral" "FIND" 0 "" "😀a" ""]
   ["regex/matcher/region-matches" "REGION" 0 "^b$" "abc" "1,2,matches"]
   ["regex/matcher/region-looking-at" "REGION" 0 "b" "abc" "1,3,lookingAt"]
   ["regex/matcher/region-find" "REGION" 0 "c" "abc" "1,3,find"]
   ["regex/split/positive-limit" "SPLIT" 0 ":" "boo:and:foo" "2"]
   ["regex/split/negative-limit" "SPLIT" 0 "o" "boo:and:foo" "-2"]
   ["regex/split/zero-limit" "SPLIT" 0 "o" "boo:and:foo" "0"]
   ["regex/split/positive-start" "SPLIT" 0 ":" ":a" "0"]
   ["regex/split/zero-width-start" "SPLIT" 0 "^" "abc" "0"]
   ["regex/split/captures-not-returned" "SPLIT" 0 "(,)" "a,b,c" "0"]
   ["regex/split/astral-zero-width" "SPLIT" 0 "" "😀a" "-1"]
   ["regex/replace/numeric" "REPLACE_ALL" 0 "(a)(b)" "abxab" "$2$1"]
   ["regex/replace/whole-match" "REPLACE_ALL" 0 "a" "aba" "<$0>"]
   ["regex/replace/unmatched-group" "REPLACE_ALL" 0 "(a)?b" "b" "<$1>"]
   ["regex/replace/zero-width-astral" "REPLACE_ALL" 0 "" "😀a" "-"]
   ["regex/replace/canonical-equivalence" "REPLACE_ALL" 128 "å" "åx" "X"]
   ["regex/replace/named" "REPLACE_FIRST" 0 "(?<left>a)(?<right>b)" "abxab" "${right}${left}"]
   ["regex/replace/escaped" "REPLACE_ALL" 0 "a" "a" "\\$\\\\"]
   ["regex/replace/append" "APPEND" 0 "(a)" "a-a" "<$1>"]
   ["regex/replace/missing-group" "REPLACE_ALL" 0 "a" "a" "$4"]
   ["regex/replace/quote" "QUOTE_REPLACEMENT" 0 "a$\\b" "" ""]])

(def ^:private astral-regex-capture-cases
  [["regex/astral-capture/literal-plus" "FIND" 0 "(😀+)😈" "😀😀😈" ""]
   ["regex/astral-capture/codepoint-plus" "FIND" 0 "(\\x{1F600}+)😈" "😀😀😈" ""]
   ["regex/astral-capture/name-plus" "FIND" 0 "(\\N{GRINNING FACE}+)😈" "😀😀😈" ""]
   ["regex/astral-capture/singleton-class-plus" "FIND" 0 "([😀]+)😈" "😀😀😈" ""]
   ["regex/astral-replace/all" "REPLACE_ALL" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "($0|$1)"]
   ["regex/astral-replace/first" "REPLACE_FIRST" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "($0|$1)"]
   ["regex/astral-replace/last" "REPLACE_LAST" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "($0|$1)"]
   ["regex/astral-replace/all-mapped" "REPLACE_ALL_MAPPED" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "├{}┤"]
   ["regex/astral-replace/first-mapped" "REPLACE_FIRST_MAPPED" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "├{}┤"]
   ["regex/astral-replace/last-mapped" "REPLACE_LAST_MAPPED" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "├{}┤"]])

(def ^:private base-core-cases
  [["evaluation/module-export" "EVALUATE"
    (str "name = \"pigeon\"\n"
         "age = 10 + 20\n"
         "active = true\n"
         "duration = 90.s\n"
         "size = 2.kib\n"
         "pair = Pair(\"answer\", 42)\n"
         "nullValue = null\n"
         "nested = new Dynamic { message = \"hello\" }\n")
    ""]
   ["evaluation/expression-object" "EXPRESSION"
    "res1 = 1\nres2 { res3 = 3; res4 = 4 }\n" "res2"]
   ["evaluation/expression-path" "EXPRESSION"
    "foo { bar = 2 }\n" "foo.bar"]
   ["value-export/output-value" "OUTPUT_VALUE"
    "output { value = Pair(\"done\", 42) }\n" ""]
   ["error/expression-syntax" "EXPRESSION" "foo = 1\n" "<>!!!"]
   ["error/expression-type" "EXPRESSION" "foo = 1\n" "foo as String"]
   ["error/evaluation-missing-property" "EVALUATE" "result = missing\n" ""]
   ["error/missing-member-text" "EVALUATE"
    "result = \"hello\".lenght\n" ""]
   ["error/missing-member-object" "EVALUATE"
    (str "result = (new Dynamic {\n"
         "  firstName = \"Ada\"\n"
         "  lastName = \"Lovelace\"\n"
         "}).fristName\n")
    ""]
   ["error/missing-member-module" "EVALUATE"
    "result = import(\"pkl:math\").getPropery(\"value\")\n" ""]
   ["error/missing-member-typed" "EVALUATE"
    (str "class Person {\n"
         "  firstName: String\n"
         "  lastName: String\n"
         "  function displayName(): String = firstName\n"
         "}\n"
         "person = new Person {\n"
         "  firstName = \"Ada\"\n"
         "  lastName = \"Lovelace\"\n"
         "}\n"
         "result = person.dispayName()\n")
    ""]
   ["output/default-pcf-text" "OUTPUT_TEXT"
    "name = \"Pigeon\"\nage = 3\n" ""]
   ["output/json-renderer-text" "OUTPUT_TEXT"
    (str "name = \"Pigeon\"\n"
         "age = 3\n"
         "output { renderer = new JsonRenderer {} }\n")
    ""]
   ["output/bytes" "OUTPUT_BYTES"
    "output { bytes = Bytes(0, 1, 127, 128, 255) }\n" ""]
   ["output/multiple-files" "OUTPUT_FILES"
    (str "output {\n"
         "  files {\n"
         "    [\"alpha.txt\"] { text = \"alpha\\n\" }\n"
         "    [\"nested/beta.txt\"] { text = \"βeta\" }\n"
         "  }\n"
         "}\n")
    ""]
   ["value-export/typed-string" "OUTPUT_VALUE_AS_STRING"
    "output { value = \"typed output\" }\n" ""]
   ["error/typed-output-mismatch" "OUTPUT_VALUE_AS_STRING"
    "output { value = 42 }\n" ""]
   ["evaluation/expression-string" "EXPRESSION_STRING"
    "value = 41\n" "value + 1"]
   ["loading/stdlib-import-expression" "EXPRESSION" ""
    "import(\"pkl:math\").gcd(54, 24)"]
   ["loading/local-module-import" "LOCAL_IMPORT"
    "imported = import(\"dependency.pkl\").answer\n"
    "answer = 40 + 2\n"]
   ["loading/local-file-resource" "FILE_RESOURCE"
    (str "resourceText = read(\"resource.txt\").text\n"
         "resourceBytes = read(\"resource.txt\").bytes\n")
    "resource payload\n"]
   ["security/denied-module" "SECURITY_DENIED" "value = 1\n" ""]
   ["runtime/collections-bytes-regex" "EVALUATE"
    (str "list = List(3, 1, 2)\n"
         "set = Set(\"b\", \"a\", \"b\")\n"
         "map = Map(\"two\", 2, \"one\", 1)\n"
         "bytes = Bytes(0, 127, 128, 255)\n"
         "regex = Regex(#\"a.+b\"#)\n"
         "computed = List(1, 2, 3).map((it) -> it * 2)\n")
    ""]
   ["regex/pkl-java-quotation" "EXPRESSION" ""
    "\".a[0]\".matches(Regex(#\"\\Q.a[0]\\E\"#))"]
   ["regex/pkl-unicode-word" "EXPRESSION" ""
    "\"café\".matches(Regex(#\"\\w+\"#))"]
   ["regex/pkl-zero-width-astral-split" "EXPRESSION" ""
    "\"😀a\".split(Regex(#\"\"#))"]
   ["regex/pkl-replacement-groups" "EXPRESSION" ""
    "\"abxab\".replaceAll(Regex(#\"(a)(b)\"#), \"$2$1\")"]])

(def ^:private core-cases
  (into base-core-cases
        (map (fn [{:keys [id expression]}]
               [id "EXPRESSION" "" expression]))
        to-fixed-cases))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :differential-validation-failed))))

(def ^:private write-text! util/write-text!)

(defn- corpus-files [^Path corpus]
  (with-open [files (Files/walk corpus (make-array java.nio.file.FileVisitOption 0))]
    (->> (.toArray files)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (filter #(str/ends-with? (str %) ".pkl"))
         (sort-by #(str (.relativize corpus ^Path %)))
         vec)))

(defn- b64 [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- manifest-cases [^Path corpus]
  (let [upstream
        (mapv (fn [^Path file]
                [(str "corpus/" (str/replace (str (.relativize corpus file)) "\\" "/"))
                 (Files/readString file StandardCharsets/UTF_8)])
              (corpus-files corpus))
        unicode
        (mapv (fn [codepoint]
                [(format "edge/unicode-comment-u%04x" codepoint)
                 (str "// Test " (char codepoint) "\nmodule Test")])
              unicode-comment-codepoints)]
    {:upstream-count (count upstream)
     :edge-count (+ (count inline-cases) (count unicode))
     :cases (into upstream (concat inline-cases unicode))}))

(defn- write-manifest! [^Path manifest cases]
  (write-text! manifest
               (apply str (map (fn [[id source]] (str id "\t" (b64 source) "\n")) cases))))

(defn- write-core-manifest! [^Path manifest cases]
  (write-text!
   manifest
   (apply str
          (map (fn [[id operation source argument]]
                 (str id "\t" operation "\t" (b64 source) "\t" (b64 argument) "\n"))
               cases))))

(defn- write-regex-compat-manifest! [^Path manifest cases]
  (write-text!
   manifest
   (apply str
          (map (fn [[id operation flags pattern input argument]]
                 (str id "\t" operation "\t" flags "\t" (b64 pattern) "\t"
                      (b64 input) "\t" (b64 argument) "\n"))
               cases))))

(defn- write-to-fixed-expectations! [^Path output cases]
  (write-text!
   output
   (apply str
          (map (fn [{:keys [id expected]}]
                 (str id "\tEXPRESSION\t"
                      (b64 (str "OK|string:" (b64 expected))) "\n"))
               cases))))

(defn- select-results! [^Path input ^Path output cases]
  (let [ids (mapv :id cases)
        selected-ids (set ids)
        lines (->> (str/split-lines (Files/readString input StandardCharsets/UTF_8))
                   (filterv (fn [line]
                              (contains? selected-ids (first (str/split line #"\t" 2))))))
        grouped (group-by #(first (str/split % #"\t" 2)) lines)
        missing (filterv #(not (contains? grouped %)) ids)
        duplicates (->> grouped
                        (keep (fn [[id values]] (when (> (count values) 1) id)))
                        sort
                        vec)]
    (when (or (seq missing) (seq duplicates))
      (fail! "Focused toFixed observations are missing or duplicated"
             {:input (str input) :missing missing :duplicates duplicates}))
    (write-text! output (apply str (map #(str (first (get grouped %)) "\n") ids)))))

(defn- assert-equal! [subject expected actual]
  (let [comparison (differential/compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! (str "Packaged " subject " behavior differs from the upstream JVM oracle")
             {:expected (str expected) :actual (str actual) :comparison comparison
              :mismatch mismatch}))
    comparison))

(defn- assert-pinned! [subject expected actual]
  (let [comparison (differential/compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! (str subject " behavior differs from the pinned upstream outcomes")
             {:expected (str expected) :actual (str actual) :comparison comparison
              :mismatch mismatch}))
    comparison))

(defn- prove-perturbation! [^Path oracle ^Path perturbed]
  (Files/copy oracle perturbed
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
  (Files/writeString perturbed "@perturbed\tPROOF\tWA==\n"
                     (into-array OpenOption [StandardOpenOption/APPEND]))
  (let [comparison (differential/compare-results oracle perturbed)]
    (when-not (:mismatch comparison)
      (fail! "Differential comparator did not detect a deliberate perturbation"
             {:oracle (str oracle) :perturbed (str perturbed)}))
    comparison))

(defn- prove-astral-capture-perturbation! [^Path oracle ^Path perturbed]
  (let [target-id "regex/astral-capture/literal-plus"
        expected-match (str "0,6," (b64 "😀😀😈") ";0,4," (b64 "😀😀"))
        expected-line (str target-id "\tOK\t" (b64 (str "1:" (b64 expected-match))))
        historical-match (str "2,6," (b64 "😀😈") ";2,4," (b64 "😀"))
        historical-line (str target-id "\tOK\t" (b64 (str "1:" (b64 historical-match))))
        lines (str/split-lines (Files/readString oracle StandardCharsets/UTF_8))
        target-index (.indexOf lines expected-line)]
    (when (neg? target-index)
      (fail! "Quantified astral capture oracle did not retain the pinned JVM boundaries"
             {:oracle (str oracle) :expected-line expected-line}))
    (write-text! perturbed
                 (str (str/join "\n" (assoc lines target-index historical-line)) "\n"))
    (let [comparison (differential/compare-results oracle perturbed)]
      (when-not (= (inc target-index) (get-in comparison [:mismatch :line]))
        (fail! "Differential comparator did not detect the historical astral capture mismatch"
               {:oracle (str oracle) :perturbed (str perturbed)
                :comparison comparison :historical-line historical-line}))
      comparison)))

(defn- run-independent-probes!
  [run-command! probes]
  (concurrency/mapv-ordered
   :differential-probes
   (fn [{:keys [name command directory]}]
     (assoc (run-command! {:command command :directory directory}) :probe name))
   probes))

(defn- verify-parser-differential!
  "Retains the complete packaged parser differential proof."
  [{:keys [workspace-root package-fn run-command!]
    :or {package-fn packaging/verify-package-consumption!
         run-command! process/run!}}]
  (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
        package-proof (package-fn {:workspace-root root :profile "pkl-parser"
                                   :run-command! run-command!})
        proof-root (harness/clean-directory!
                    (paths/resolve-path root "validation-output"
                                        "differential-proof"
                                        "parser"))
        corpus (paths/resolve-path root "research" "pkl" "pkl-core" "src" "test"
                                   "files" "LanguageSnippetTests" "input")
        {:keys [cases upstream-count edge-count]} (manifest-cases corpus)
        manifest (write-manifest! (paths/resolve-path proof-root "cases.tsv") cases)
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        upstream-root (paths/resolve-path root "research" "pkl")
        upstream-main (paths/resolve-path upstream-root "pkl-parser" "build" "classes"
                                          "java" "main")
        upstream-resources (paths/resolve-path upstream-root "pkl-parser" "build"
                                               "resources" "main")
        oracle-source (paths/resolve-path root "targets" "pkl" "validation" "oracle"
                                          "UpstreamOracle.java")
        oracle-output (paths/resolve-path proof-root "upstream.tsv")
        package-output (paths/resolve-path proof-root "package.tsv")
        perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
        classpath (str/join File/pathSeparator
                            (map str [oracle-classes upstream-main upstream-resources]))
        consumer-root (:consumer-root package-proof)
        consumer-project (paths/resolve-path consumer-root "DripSharp.Brine.Parser.PackageConsumer.csproj")
        consumer-source (paths/resolve-path consumer-root "Program.cs")
        probe-source (paths/resolve-path root "targets" "pkl" "validation" "probe"
                                         "PackageProbe.cs")]
    (let [expected-count
          (get-in (baseline/read-baseline :pkl)
                  [:contracts :language-snippets :cases])]
      (when-not (= expected-count upstream-count)
        (fail! "The pinned LanguageSnippetTests corpus count changed; review the oracle selection"
               {:expected expected-count :actual upstream-count :corpus (str corpus)})))
    (run-command! {:command ["./gradlew" ":pkl-parser:classes" "--console=plain"]
                   :directory upstream-root})
    (run-command! {:command ["javac" "--release"
                             (str (baseline/java-language-version :pkl))
                             "-cp" (str upstream-main)
                             "-d" (str oracle-classes) (str oracle-source)]
                   :directory root})
    (Files/copy probe-source consumer-source
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                             "--verbosity:minimal" "--no-restore" "--no-incremental"
                             "-warnaserror"]
                   :directory consumer-root})
    (run-independent-probes!
     run-command!
     [{:name :upstream-java-oracle
       :command ["java" "-cp" classpath "UpstreamOracle"
                 (str manifest) (str oracle-output)]
       :directory upstream-root}
      {:name :packaged-dotnet-probe
       :command ["dotnet" "run" "--project" (str consumer-project)
                 "--no-build" "--no-restore" "--" (str manifest)
                 (str package-output)]
       :directory consumer-root}])
    (let [comparison (assert-equal! "parser" oracle-output package-output)
          perturbation (prove-perturbation! oracle-output perturbed-output)
          revision (str/trim (:output (run-command! {:command ["git" "rev-parse" "HEAD"]
                                                     :directory upstream-root})))
          summary {:upstream-revision revision
                   :package (:identity package-proof)
                   :language-snippet-cases upstream-count
                   :lexer-span-edge-cases edge-count
                   :total-cases (count cases)
                   :observations (:matched comparison)
                   :perturbation-detected-at (get-in perturbation [:mismatch :line])}]
      (println "Independent upstream/package differential passed:" (pr-str summary))
      {:package-proof package-proof
       :summary summary
       :manifest manifest
       :oracle-output oracle-output
       :package-output package-output})))

(defn- core-classpath [root]
  (let [manifest (paths/resolve-path root "target"
                                     "gradle-main-inputs.tsv")
        input (project/read-discovery-manifest manifest)
        toolchain (:java-toolchain input)
        upstream-root (paths/resolve-path root "research" "pkl")
        core-root (paths/resolve-path upstream-root "pkl-core")]
    {:java-release (:release toolchain)
     :java-home (:home toolchain)
     :entries (into [(paths/resolve-path core-root "build" "classes" "java" "main")
                     (paths/resolve-path core-root "build" "resources" "main")
                     (paths/resolve-path upstream-root "pkl-parser" "build" "resources" "main")]
                    (project-input/compile-classpath input))}))

(def ^:private required-contract-families
  #{"schema.modules-classes-inheritance"
    "schema.properties-docs-annotations"
    "schema.nullable-constrained-unions"
    "schema.collections-aliases-generics-functions"
    "schema.imports-identifiers-collisions-ordering"
    "binding.constructors-settable-members"
    "binding.nested-generics-nullability"
    "binding.custom-loaders"
    "binding.unknown-incompatible-cycles"
    "codegen.polymorphism-overrides"
    "codegen.overridden-properties"
    "binding.complete-conversion-matrix"
    "binding.value-model-conversions"
    "binding.collection-matrix"
    "binding.nullable-matrix"
    "schema.methods-generic-classes"
    "schema.user-defined-generic-class-rejection"
    "schema.user-defined-generic-method-rejection"
    "schema.amends-recursive-aliases"
    "schema.amended-module-relations"})

(defn- verify-contract-evidence!
  [root ^Path evidence]
  (when-not (paths/regular-file? evidence)
    (fail! "Schema/codegen/binding contract evidence is missing"
           {:path (str evidence)}))
  (let [entries
        (->> (str/split-lines (Files/readString evidence StandardCharsets/UTF_8))
             (map-indexed vector)
             (remove (fn [[_ line]]
                       (or (str/blank? line) (str/starts-with? line "#"))))
             (mapv
              (fn [[index line]]
                (let [fields (str/split line #"\t" -1)]
                  (when-not (= 4 (count fields))
                    (fail! "Contract evidence row must have four tab-separated fields"
                           {:path (str evidence) :line (inc index) :value line}))
                  (let [[status family source detail] fields]
                    (when-not (#{"selected" "pending-in-scope"} status)
                      (fail! "Contract evidence row has an unsupported status"
                             {:path (str evidence) :line (inc index) :status status}))
                    (when (some str/blank? [family source detail])
                      (fail! "Contract evidence row contains a blank required field"
                             {:path (str evidence) :line (inc index) :value line}))
                    (when-not (and (str/starts-with? source "research/pkl/")
                                   (str/includes? source "/src/test/"))
                      (fail! "Contract evidence must cite an upstream Pkl test or fixture"
                             {:path (str evidence) :line (inc index) :source source}))
                    (let [source-path (paths/resolve-path root source)]
                      (when-not (paths/regular-file? source-path)
                        (fail! "Contract evidence references a missing upstream source"
                               {:path (str evidence) :line (inc index)
                                :source (str source-path)})))
                    {:status status :family family :source source :detail detail})))))
        selected (filterv #(= "selected" (:status %)) entries)
        pending (filterv #(= "pending-in-scope" (:status %)) entries)
        missing (sort (remove (set (map :family selected)) required-contract-families))]
    (when (seq missing)
      (fail! "Contract evidence omits required selected behavior families"
             {:path (str evidence) :missing missing}))
    {:selected (count selected)
     :pending-in-scope (count pending)
     :families (mapv :family entries)}))

(def ^:private required-loading-contract-families
  #{"source.module-forms"
    "loading.local-file-resolution"
    "loading.local-relative-import"
    "loading.local-resource"
    "loading.directory-listing"
    "loading.directory-globbing"
    "loading.modulepath-directory"
    "loading.modulepath-archive"
    "loading.standard-library"
    "loading.http-modules-resources"
    "loading.https-modules-resources"
    "http.redirects-policy-order"
    "http.rewrites-headers"
    "http.proxy-settings"
    "collections.map-entry-set"
    "uri.decoded-components-package-assets"
    "package.assets"
    "package.directory-listing-globbing"
    "package.metadata"
    "package.checksums"
    "package.cache-offline"
    "package.transitive-dependencies"
    "project.declared-dependencies"
    "project.projectpackage-imports-resources"
    "readers.custom-module"
    "readers.custom-resource"
    "readers.configured-external-module"
    "readers.configured-external-resource"
    "adaptation.configured-external-process"
    "adaptation.external-reader-failure-lifecycle"
    "resources.environment"
    "resources.external-property"
    "adaptation.assembly-modules"
    "adaptation.embedded-resources"
    "evaluator.builder-mutations-getters"
    "evaluator.builder-invalid-combinations"
    "analyzer.import-graph"
    "logging.public-api"
    "diagnostics.stack-transform"
    "diagnostics.exception-metadata"
    "runtime.platform-release"
    "evaluator.timeout-deadline"
    "evaluator.timeout-diagnostic"
    "evaluator.timeout-cancellation"
    "settings.evaluator"
    "settings.project"
    "settings.user"
    "settings.apply-from-project"
    "settings.timeout-application"
    "security.root-confinement"
    "security.module-allowlist"
    "security.resource-resolve-read"
    "security.import-trust"
    "security.traversal-rejection"
    "security.scheme-policy"
    "adaptation.platform-path-uri"
    "errors.missing-element"
    "errors.invalid-uri-scheme"
    "errors.io"
    "errors.checksum"
    "errors.dependency-cycle"
    "errors.output-type"
    "lifecycle.evaluator-http-close"
    "lifecycle.timeout-cleanup"
    "lifecycle.custom-reader-ownership"
    "adaptation.disposal-ownership"})

(defn- contract-lines [^Path file]
  (->> (str/split-lines (Files/readString file StandardCharsets/UTF_8))
       (map-indexed vector)
       (remove (fn [[_ line]]
                 (or (str/blank? line) (str/starts-with? line "#"))))))

(defn- verify-loading-contract-evidence!
  [root ^Path evidence ^Path expectations]
  (doseq [[kind path] [[:evidence evidence] [:expectations expectations]]]
    (when-not (paths/regular-file? path)
      (fail! "Loading/policy/configuration contract input is missing"
             {:kind kind :path (str path)})))
  (let [expectation-entries
        (mapv
         (fn [[index line]]
           (let [fields (str/split line #"\t" -1)]
             (when-not (= 4 (count fields))
               (fail! "Loading contract expectation must have four tab-separated fields"
                      {:path (str expectations) :line (inc index) :value line}))
             (let [[comparison observation kind expectation] fields]
               (when-not (#{"jvm-shared" "dotnet-adaptation"} comparison)
                 (fail! "Loading contract expectation has an unsupported comparison class"
                        {:path (str expectations) :line (inc index)
                         :comparison comparison}))
               (when (some str/blank? [observation kind expectation])
                 (fail! "Loading contract expectation contains a blank required field"
                        {:path (str expectations) :line (inc index) :value line}))
               {:comparison comparison :observation observation
                :kind kind :expectation expectation})))
         (contract-lines expectations))
        duplicate-expectations
        (->> expectation-entries
             (group-by :observation)
             (keep (fn [[observation entries]]
                     (when (> (count entries) 1) observation)))
             sort vec)
        expectation-index (into {} (map (juxt :observation identity) expectation-entries))
        evidence-entries
        (mapv
         (fn [[index line]]
           (let [fields (str/split line #"\t" -1)]
             (when-not (= 7 (count fields))
               (fail! "Loading contract evidence must have seven tab-separated fields"
                      {:path (str evidence) :line (inc index) :value line}))
             (let [[implementation comparison family observation source fixture detail] fields]
               (when-not (#{"existing-evidence" "pending-in-scope"} implementation)
                 (fail! "Loading contract evidence has an unsupported implementation status"
                        {:path (str evidence) :line (inc index)
                         :implementation implementation}))
               (when-not (#{"jvm-shared" "dotnet-adaptation"} comparison)
                 (fail! "Loading contract evidence has an unsupported comparison class"
                        {:path (str evidence) :line (inc index)
                         :comparison comparison}))
               (when (some str/blank? [family observation source fixture detail])
                 (fail! "Loading contract evidence contains a blank required field"
                        {:path (str evidence) :line (inc index) :value line}))
               (when-not (and (str/starts-with? source "research/pkl/")
                              (str/includes? source "/src/test/"))
                 (fail! "Loading contract evidence must cite an upstream Pkl test or fixture"
                        {:path (str evidence) :line (inc index) :source source}))
               (doseq [[input-kind input] [[:source source] [:fixture fixture]]]
                 (let [input-path (paths/resolve-path root input)]
                   (when-not (paths/regular-file? input-path)
                     (fail! "Loading contract evidence references a missing input"
                            {:path (str evidence) :line (inc index)
                             :input-kind input-kind :input (str input-path)}))))
               (let [expected (get expectation-index observation)]
                 (when-not expected
                   (fail! "Loading contract evidence references an unknown expectation"
                          {:path (str evidence) :line (inc index)
                           :observation observation}))
                 (when-not (= comparison (:comparison expected))
                   (fail! "Loading contract evidence and expectation comparison classes differ"
                          {:path (str evidence) :line (inc index)
                           :observation observation :evidence comparison
                           :expectation (:comparison expected)})))
               {:implementation implementation :comparison comparison :family family
                :observation observation :source source :fixture fixture :detail detail})))
         (contract-lines evidence))
        missing-families
        (sort (remove (set (map :family evidence-entries))
                      required-loading-contract-families))
        uncited-expectations
        (sort (remove (set (map :observation evidence-entries))
                      (map :observation expectation-entries)))
        shared (filterv #(= "jvm-shared" (:comparison %)) expectation-entries)
        adaptations (filterv #(= "dotnet-adaptation" (:comparison %)) expectation-entries)]
    (when (seq duplicate-expectations)
      (fail! "Loading contract contains duplicate observation expectations"
             {:path (str expectations) :duplicates duplicate-expectations}))
    (when (seq missing-families)
      (fail! "Loading contract omits required in-scope behavior families"
             {:path (str evidence) :missing missing-families}))
    (when (seq uncited-expectations)
      (fail! "Loading contract contains expectations without source-backed evidence"
             {:path (str expectations) :uncited uncited-expectations}))
    {:evidence evidence-entries
     :expectations expectation-entries
     :shared shared
     :adaptations adaptations
     :summary {:families (count evidence-entries)
               :existing-evidence (count (filter #(= "existing-evidence"
                                                     (:implementation %))
                                                 evidence-entries))
               :pending-in-scope (count (filter #(= "pending-in-scope"
                                                    (:implementation %))
                                                evidence-entries))
               :jvm-shared-families (count (filter #(= "jvm-shared" (:comparison %))
                                                   evidence-entries))
               :dotnet-adaptation-families (count (filter #(= "dotnet-adaptation"
                                                              (:comparison %))
                                                          evidence-entries))
               :jvm-shared-observations (count shared)
               :dotnet-adaptation-observations (count adaptations)}}))

(defn- write-loading-expectations! [^Path output entries]
  (write-text!
   output
   (apply str
          (map (fn [{:keys [observation kind expectation]}]
                 (str observation "\t" kind "\t" (b64 expectation) "\n"))
               entries))))

(defn- verify-package-probe-source-isolation!
  [^Path package-root ^Path project ^Path source]
  (let [root (.toRealPath package-root (make-array java.nio.file.LinkOption 0))
        source-path (.toRealPath source (make-array java.nio.file.LinkOption 0))
        project-text (Files/readString project StandardCharsets/UTF_8)
        source-text (Files/readString source StandardCharsets/UTF_8)
        forbidden (->> ["target/generated" "ProjectReference" "Compile Include"
                        "HintPath" "#line"]
                       (filter #(or (str/includes? project-text %)
                                    (str/includes? source-text %)))
                       vec)]
    (when-not (.startsWith source-path root)
      (fail! "Package-only loading probe source escapes its isolated project"
             {:root (str root) :source (str source-path)}))
    (when (seq forbidden)
      (fail! "Package-only loading probe contains a generated-source or reference escape hatch"
             {:project (str project) :source (str source) :forbidden forbidden}))
    {:project (str project) :source (str source) :forbidden []}))

(declare package-only-project restore-package-only-project!)

(def ^:private dotnet-loading-observations
  #{"module-source/forms"
    "local/import-resource"
    "local/list-glob"
    "modulepath/directory-archive"
    "stdlib/import"
    "custom/module-resource-lifecycle"
    "resources/environment-property"
    "evaluator/builder"
    "module-resource/public-api"
    "http/public-api"
    "package/public-api"
    "project-settings/public-api"
    "security-external/public-api"
    "analyzer/import-graph"
    "logging/public-api"
    "diagnostics/stack-transform"
    "diagnostics/exception-metadata"
    "runtime/platform-release"
    "security/policy"
    "https/rewrite-redirect-headers"
    "package/assets-cache-integrity"
    "uri/decoded-components-package-assets"
    "collections/map-entry-set"
    "project/projectpackage-dependencies"
    "network/package-errors"
    "project/evaluator-user-settings"
    "errors/missing-invalid-io-type"
    "project/dependency-cycles"
    "lifecycle/close"
    "evaluator/timeout"
    "evaluator/timeout-cleanup"
    "external/configured-process-loading"
    "external/failure-lifecycle"
    "assembly/module-loading"
    "embedded/resource-loading"
    "platform/path-uri-policy"
    "ownership/disposal"
    "idiomatic/loading-api-shapes"})

(defn- loading-package-project
  [package-id version target-framework]
  (str/replace
   (package-only-project package-id version target-framework)
   "</Project>"
   (str "  <ItemGroup>\n"
        "    <EmbeddedResource Include=\""
        "fixtures/modules/main.pkl"
        "\" LogicalName=\"Contract.Modules.main.pkl\" />\n"
        "    <EmbeddedResource Include=\""
        "fixtures/modules/dependency.pkl"
        "\" LogicalName=\"Contract.Modules.dependency.pkl\" />\n"
        "    <EmbeddedResource Include=\""
        "fixtures/resources/payload.txt"
        "\" LogicalName=\"Contract.Resources.payload.txt\" />\n"
        "    <EmbeddedResource Include=\""
        "fixtures/resources/second.txt"
        "\" LogicalName=\"Contract.Resources.second.txt\" />\n"
        "  </ItemGroup>\n"
        "</Project>")))

(defn- verify-loading-contract!
  [{:keys [root package-proof run-command! java-release java-home entries]}]
  (let [fixtures (paths/resolve-path root "validation" "loading-contract")
        dotnet-fixtures (paths/resolve-path fixtures "fixtures" "dotnet")
        evidence (paths/resolve-path fixtures "ContractEvidence.tsv")
        expectations (paths/resolve-path fixtures "ContractExpectations.tsv")
        oracle-source (paths/resolve-path fixtures "LoadingContractUpstreamOracle.java")
        package-probe-source (paths/resolve-path fixtures "LoadingContractDotNetProbe.cs")
        contract (verify-loading-contract-evidence! root evidence expectations)
        proof-root (harness/clean-directory!
                    (paths/resolve-path root "validation-output"
                                        "differential-proof" "loading-contract"))
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        oracle-output (paths/resolve-path proof-root "upstream.tsv")
        expected-output (write-loading-expectations!
                         (paths/resolve-path proof-root "expected.tsv")
                         (:shared contract))
        package-entries (filterv #(contains? dotnet-loading-observations
                                             (:observation %))
                                 (:expectations contract))
        package-expected-output (write-loading-expectations!
                                 (paths/resolve-path proof-root "package-expected.tsv")
                                 package-entries)
        package-output (paths/resolve-path proof-root "package.tsv")
        package-perturbed-output (paths/resolve-path proof-root "package-perturbed.tsv")
        perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
        assembly-manifest (paths/resolve-path proof-root "packed-assemblies.tsv")
        work (doto (paths/resolve-path proof-root "upstream-work")
               (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str entries))
        classpath (str/join File/pathSeparator (map str (cons oracle-classes entries)))
        javac (paths/resolve-path java-home "bin" "javac")
        java (paths/resolve-path java-home "bin" "java")
        upstream-root (paths/resolve-path root "research" "pkl")
        package-build (paths/resolve-path upstream-root "pkl-commons-test" "build")
        package-root (doto (paths/resolve-path proof-root "package-consumer")
                       (Files/createDirectories (make-array FileAttribute 0)))
        package-work (paths/resolve-path package-root "work")
        package-project (paths/resolve-path package-root "LoadingContractConsumer.csproj")
        package-source (paths/resolve-path package-root "Program.cs")
        package-config (paths/resolve-path package-root "NuGet.Config")
        packages (doto (paths/resolve-path proof-root "package-cache")
                   (Files/createDirectories (make-array FileAttribute 0)))
        source-package-config (paths/resolve-path (:consumer-root package-proof) "NuGet.Config")
        installed-consumer-project
        (paths/resolve-path (:consumer-root package-proof) "DripSharp.Brine.PackageConsumer.csproj")
        target-match (re-find #"<TargetFramework>(net\d+\.\d+)</TargetFramework>"
                              (Files/readString installed-consumer-project))
        target-framework (second target-match)
        identities (get-in package-proof [:dependency-proof :packages])
        {:keys [id version]} (:identity package-proof)]
    (doseq [required [oracle-source package-probe-source source-package-config
                      (paths/resolve-path dotnet-fixtures "modules" "main.pkl")
                      (paths/resolve-path dotnet-fixtures "modules" "dependency.pkl")
                      (paths/resolve-path dotnet-fixtures "resources" "payload.txt")
                      (paths/resolve-path dotnet-fixtures "resources" "second.txt")]]
      (when-not (paths/regular-file? required)
        (fail! "Loading contract proof input is missing" {:path (str required)})))
    (when-not target-framework
      (fail! "Could not determine the loading consumer target framework"
             {:project (str installed-consumer-project)}))
    (when-not (= 38 (count package-entries))
      (fail! "The package-only loading observation selection changed"
             {:expected 38 :actual (count package-entries)
              :observations (mapv :observation package-entries)}))
    (let [runtime-assemblies
          (package-provenance/write-packed-assembly-manifest!
           assembly-manifest (:packages package-proof))]
      (run-command! {:command ["./gradlew" ":pkl-commons-test:processResources" "--console=plain"]
                     :directory upstream-root})
      (run-command! {:command [(str javac) "--release" (str java-release)
                               "-cp" compile-classpath "-d" (str oracle-classes)
                               (str oracle-source)]
                     :directory root})
      (run-command! {:command [(str java) "-cp" classpath "LoadingContractUpstreamOracle"
                               (str root) (str oracle-output) (str work)]
                     :directory root})
      (doseq [[source relative]
              [[(paths/resolve-path dotnet-fixtures "modules" "main.pkl")
                ["fixtures" "modules" "main.pkl"]]
               [(paths/resolve-path dotnet-fixtures "modules" "dependency.pkl")
                ["fixtures" "modules" "dependency.pkl"]]
               [(paths/resolve-path dotnet-fixtures "resources" "payload.txt")
                ["fixtures" "resources" "payload.txt"]]
               [(paths/resolve-path dotnet-fixtures "resources" "second.txt")
                ["fixtures" "resources" "second.txt"]]]]
        (let [destination (apply paths/resolve-path package-root relative)]
          (Files/createDirectories (.getParent destination) (make-array FileAttribute 0))
          (Files/copy source destination
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (write-text! package-project (loading-package-project id version target-framework))
      (Files/copy package-probe-source package-source
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (Files/copy source-package-config package-config
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (let [source-isolation
            (verify-package-probe-source-isolation!
             package-root package-project package-source)
            package-dependencies
            (restore-package-only-project! run-command! package-project package-config
                                           packages package-proof identities)]
        (run-command! {:command ["dotnet" "build" (str package-project) "--nologo"
                                 "--verbosity:minimal" "--no-restore" "--no-incremental"
                                 "-warnaserror"]
                       :directory package-root})
        (let [package-run
              (run-command! {:command ["dotnet" "run" "--project" (str package-project)
                                       "--no-build" "--no-restore" "--"
                                       (str (paths/resolve-path fixtures "fixtures"))
                                       (str package-output) (str package-work)
                                       (str package-build) (str assembly-manifest)]
                             :directory package-root})]
          (when-not (str/includes? (:output package-run)
                                   "Package-only loading, package, and policy validation passed.")
            (fail! "Package-only loading probe did not report successful validation"
                   {:output (:output package-run)}))
          (let [comparison (assert-equal! "Pkl loading/policy/configuration contract"
                                          expected-output oracle-output)
                package-comparison (assert-equal! "Pkl loading/package/policy contract"
                                                  package-expected-output package-output)
                perturbation (prove-perturbation! expected-output perturbed-output)
                package-perturbation (prove-perturbation! package-expected-output
                                                          package-perturbed-output)]
            {:summary (assoc (:summary contract)
                             :observations (:matched comparison)
                             :package-observations (:matched package-comparison)
                             :package-perturbation-detected-at
                             (get-in package-perturbation [:mismatch :line])
                             :perturbation-detected-at (get-in perturbation [:mismatch :line]))
             :evidence evidence
             :expectations expectations
             :expected-output expected-output
             :oracle-output oracle-output
             :package-output package-output
             :package-dependencies package-dependencies
             :source-isolation source-isolation
             :runtime-assemblies runtime-assemblies}))))))

(defn- package-only-project [package-id version target-framework]
  (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
       "  <PropertyGroup>\n"
       "    <OutputType>Exe</OutputType>\n"
       "    <TargetFramework>" target-framework "</TargetFramework>\n"
       "    <Nullable>enable</Nullable>\n"
       "    <ImplicitUsings>disable</ImplicitUsings>\n"
       "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
       "    <Deterministic>true</Deterministic>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       "    <PackageReference Include=\"" package-id "\" Version=\"" version "\" />\n"
       "  </ItemGroup>\n"
       "</Project>\n"))

(defn- restore-package-only-project!
  [run-command! ^Path project ^Path nuget-config ^Path packages package-proof identities]
  (run-command! {:command ["dotnet" "restore" (str project)
                           "--configfile" (str nuget-config)
                           "--packages" (str packages)
                           "--no-cache" "--force" "--force-evaluate"]
                 :directory (.getParent project)})
  (packaging/inspect-consumer-dependencies!
   project (paths/resolve-path (.getParent project) "obj" "project.assets.json")
   packages (:identity package-proof) identities))

(defn- verify-public-contract-package!
  [{:keys [root package-proof run-command!]}]
  (let [proof-root (harness/clean-directory!
                    (paths/resolve-path root "validation-output"
                                        "differential-proof" "public-contract"))
        project-file (paths/resolve-path proof-root "PublicContractConsumer.csproj")
        source-file (paths/resolve-path proof-root "GeneratedSignatures.cs")
        strong-keys (paths/resolve-path proof-root "strong-keys.tsv")
        nuget-config (paths/resolve-path proof-root "NuGet.Config")
        package-cache (doto (paths/resolve-path proof-root "package-cache")
                        (Files/createDirectories (make-array FileAttribute 0)))
        source-package-config
        (paths/resolve-path (:consumer-root package-proof) "NuGet.Config")
        installed-consumer-project
        (paths/resolve-path (:consumer-root package-proof) "DripSharp.Brine.PackageConsumer.csproj")
        consumer-framework
        (second (re-find #"<TargetFramework>(net\d+[.]\d+)</TargetFramework>"
                         (Files/readString installed-consumer-project)))
        generation (get-in package-proof [:verification :generation])
        package-framework (get-in generation [:destination :project :target-framework])
        {:keys [id version]} (:identity package-proof)
        identities (get-in package-proof [:dependency-proof :packages])
        contract-tool (get-in (public-api-contract/contract-paths root)
                              [:contract-compiler])]
    (when-not consumer-framework
      (fail! "Could not determine the public contract consumer target framework"
             {:project (str installed-consumer-project)}))
    (write-text! project-file (package-only-project id version consumer-framework))
    (Files/copy source-package-config nuget-config
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (let [dependency-proof
          (restore-package-only-project! run-command! project-file nuget-config
                                         package-cache package-proof identities)
          assemblies
          (->> identities
               (mapv (fn [{:keys [id version]}]
                       (paths/resolve-path package-cache (str/lower-case id) version
                                           "lib" package-framework (str id ".dll"))))
               (sort-by str) vec)]
      (doseq [assembly assemblies]
        (when-not (paths/regular-file? assembly)
          (fail! "Isolated package cache is missing a public contract assembly"
                 {:assembly (str assembly)})))
      (let [strong-summary
            (public-api-contract/write-strong-contract-keys! root strong-keys)]
        (run-command! {:command ["dotnet" "build" (str contract-tool)
                                 "--configuration" "Release" "--nologo"
                                 "--no-incremental"]
                       :directory root})
        (let [generation-run
              (run-command!
               {:command (into ["dotnet" "run" "--project" (str contract-tool)
                                "--configuration" "Release" "--no-build" "--"
                                "generate" (str source-file) (str strong-keys)]
                               (map str assemblies))
                :directory root})
              source-isolation
              (verify-package-probe-source-isolation!
               proof-root project-file source-file)]
          (run-command! {:command ["dotnet" "build" (str project-file) "--nologo"
                                   "--verbosity:minimal" "--no-restore"
                                   "--no-incremental" "-warnaserror"]
                         :directory proof-root})
          (let [run (run-command! {:command ["dotnet" "run" "--project"
                                             (str project-file) "--no-build"
                                             "--no-restore"]
                                   :directory proof-root})
                expected-package
                (:rows (public-api-contract/read-tsv
                        (:package (public-api-contract/contract-paths root))
                        public-api-contract/package-columns))
                perturbed-package (assoc-in expected-package [0 :signature]
                                            "PERTURBED-CONTRACT-SIGNATURE")
                metadata-control
                (public-api-contract/compare-package-surface expected-package
                                                             perturbed-package)
                expected-bodies
                (:rows (public-api-contract/read-tsv
                        (:body-candidates (public-api-contract/contract-paths root))
                        public-api-contract/body-audit-columns))
                body-control
                (public-api-contract/compare-body-audit expected-bodies
                                                        (pop expected-bodies))
                whole-surface
                (public-api-contract/audit-public-surface!
                 root generation "Release")]
            (when-not (str/includes?
                       (:output run)
                       "Package-only public contract signature compilation passed:")
              (fail! "Package-only public contract compiler did not report success"
                     {:output (:output run)}))
            (when-not (= :package-public-surface-drift
                         (get-in metadata-control [:mismatch :kind]))
              (fail! "Package metadata perturbation was not detected"
                     {:control metadata-control}))
            (when-not (= :public-body-audit-drift
                         (get-in body-control [:mismatch :kind]))
              (fail! "Public body perturbation was not detected"
                     {:control body-control}))
            {:summary {:contract-rows (count expected-package)
                       :strong-rows (:rows strong-summary)
                       :strong-keys (:keys strong-summary)
                       :assemblies (count assemblies)
                       :source-files (:source-files whole-surface)
                       :body-candidates (:reviewed-body-candidates whole-surface)
                       :mapped-members (:mapped-generated-members whole-surface)
                       :source-mappings (:generated-source-mappings whole-surface)
                       :metadata-perturbation (get-in metadata-control
                                                      [:mismatch :kind])
                       :body-perturbation (get-in body-control [:mismatch :kind])}
             :dependency-proof dependency-proof
             :source-isolation source-isolation
             :generation generation-run
             :run run
             :whole-public-surface whole-surface}))))))

(defn- verify-schema-codegen-binding!
  [{:keys [root package-proof run-command! java-release java-home entries]}]
  (let [proof-root (harness/clean-directory!
                    (paths/resolve-path root "validation-output"
                                        "differential-proof" "schema-codegen-binding"))
        fixtures (paths/resolve-path root "validation" "schema-codegen")
        contract-evidence (paths/resolve-path fixtures "ContractEvidence.tsv")
        evidence-summary (verify-contract-evidence! root contract-evidence)
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        oracle-source (paths/resolve-path fixtures "SchemaUpstreamOracle.java")
        oracle-output (paths/resolve-path proof-root "upstream.tsv")
        package-output (paths/resolve-path proof-root "package.tsv")
        perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
        config-manifest (paths/resolve-path proof-root "pkl-config-java-main-inputs.tsv")
        config-input (project/discover-main!
                      {:workspace-root root
                       :manifest config-manifest
                       :project-root "research/pkl"
                       :gradle-project ":pkl-config-java"
                       :run-command! run-command!})
        config-classes (paths/resolve-path root "research" "pkl" "pkl-config-java"
                                           "build" "classes" "java" "main")
        oracle-entries (vec (distinct (concat [config-classes] entries
                                              (project-input/compile-classpath
                                               config-input))))
        config-toolchain (:java-toolchain config-input)
        toolchain-check
        (when-not (and (= java-release (:release config-toolchain))
                       (= (paths/absolute java-home)
                          (paths/absolute (:home config-toolchain))))
          (fail! "DripSharp.Brine and pkl-config-java oracle toolchains differ"
                 {:core {:java-release java-release :java-home (str java-home)}
                  :config {:java-release (:release config-toolchain)
                           :java-home (str (:home config-toolchain))}}))
        compile-classpath (str/join File/pathSeparator (map str oracle-entries))
        classpath (str/join File/pathSeparator (map str (cons oracle-classes oracle-entries)))
        javac (paths/resolve-path java-home "bin" "javac")
        java (paths/resolve-path java-home "bin" "java")
        generator-root (doto (paths/resolve-path proof-root "package-generator")
                         (Files/createDirectories (make-array FileAttribute 0)))
        generated-root (doto (paths/resolve-path proof-root "emitted-csharp")
                         (Files/createDirectories (make-array FileAttribute 0)))
        consumer-root (doto (paths/resolve-path proof-root "generated-consumer")
                        (Files/createDirectories (make-array FileAttribute 0)))
        generator-packages (doto (paths/resolve-path proof-root "generator-packages")
                             (Files/createDirectories (make-array FileAttribute 0)))
        consumer-packages (doto (paths/resolve-path proof-root "consumer-packages")
                            (Files/createDirectories (make-array FileAttribute 0)))
        generator-project (paths/resolve-path generator-root "PackageSchemaGenerator.csproj")
        consumer-project (paths/resolve-path consumer-root "GeneratedPackageConsumer.csproj")
        generator-config (paths/resolve-path generator-root "NuGet.Config")
        consumer-config (paths/resolve-path consumer-root "NuGet.Config")
        package-config (paths/resolve-path (:consumer-root package-proof) "NuGet.Config")
        collision-diagnostics (paths/resolve-path proof-root "collision-diagnostics.txt")
        binding-diagnostics (paths/resolve-path proof-root "binding-diagnostics.txt")
        identities (get-in package-proof [:dependency-proof :packages])
        {:keys [id version]} (:identity package-proof)
        installed-consumer-project
        (paths/resolve-path (:consumer-root package-proof) "DripSharp.Brine.PackageConsumer.csproj")
        target-match (re-find #"<TargetFramework>(net\d+\.\d+)</TargetFramework>"
                              (Files/readString installed-consumer-project))
        target-framework (second target-match)
        target-framework-check
        (when-not target-framework
          (fail! "Could not determine the installed package-consumer target framework"
                 {:project (str installed-consumer-project)}))
        project-contents (package-only-project id version target-framework)]
    (doseq [required [oracle-source
                      contract-evidence
                      (paths/resolve-path fixtures "SchemaGeneratorProbe.cs")
                      (paths/resolve-path fixtures "GeneratedConsumer.cs")
                      package-config]]
      (when-not (paths/regular-file? required)
        (fail! "Schema/codegen/binding proof input is missing" {:path (str required)})))

    (run-command! {:command [(str javac) "--release" (str java-release)
                             "-cp" compile-classpath "-d" (str oracle-classes)
                             (str oracle-source)]
                   :directory root})
    (run-command! {:command [(str java) "-cp" classpath "SchemaUpstreamOracle"
                             (str fixtures) (str oracle-output)]
                   :directory root})
    (do

      (write-text! generator-project project-contents)
      (Files/copy (paths/resolve-path fixtures "SchemaGeneratorProbe.cs")
                  (paths/resolve-path generator-root "Program.cs")
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (Files/copy package-config generator-config
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (let [generator-dependencies
            (restore-package-only-project! run-command! generator-project generator-config
                                           generator-packages package-proof identities)]
        (run-command! {:command ["dotnet" "build" (str generator-project) "--nologo"
                                 "--verbosity:minimal" "--no-restore" "--no-incremental"
                                 "-warnaserror"]
                       :directory generator-root})
        (let [generator-run
              (run-command! {:command ["dotnet" "run" "--project" (str generator-project)
                                       "--no-build" "--no-restore" "--"
                                       (str fixtures) (str generated-root) (str package-output)
                                       (str collision-diagnostics)]
                             :directory generator-root})]
          (when-not (str/includes? (:output generator-run)
                                   "Package-only schema traversal and deterministic C# generation passed.")
            (fail! "Package-only schema generator did not report successful validation"
                   {:output (:output generator-run)}))

          (write-text! consumer-project project-contents)
          (Files/copy (paths/resolve-path fixtures "GeneratedConsumer.cs")
                      (paths/resolve-path consumer-root "Program.cs")
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (Files/copy package-config consumer-config
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (let [generated-files
                (mapv #(paths/resolve-path generated-root %)
                      ["ContractBase.g.cs" "ContractImported.g.cs" "ContractMain.g.cs"
                       "PolymorphicLib.g.cs" "PolymorphicModuleTest.g.cs"
                       "OverriddenProperty.g.cs" "SchemaMethods.g.cs"])]
            (doseq [^Path source generated-files]
              (when-not (paths/regular-file? source)
                (fail! "Package generator did not emit an expected C# source" {:path (str source)}))
              (Files/copy source (paths/resolve-path consumer-root (str (.getFileName source)))
                          (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))
            (let [consumer-dependencies
                  (restore-package-only-project! run-command! consumer-project consumer-config
                                                 consumer-packages package-proof identities)]
              (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                                       "--verbosity:minimal" "--no-restore" "--no-incremental"
                                       "-warnaserror"]
                             :directory consumer-root})
              (let [consumer-run
                    (run-command! {:command ["dotnet" "run" "--project" (str consumer-project)
                                             "--no-build" "--no-restore" "--"
                                             (str fixtures) (str package-output)
                                             (str binding-diagnostics)]
                                   :directory consumer-root})]
                (when-not (str/includes? (:output consumer-run)
                                         "Independently compiled generated C# binding consumer passed.")
                  (fail! "Generated package-only consumer did not report successful validation"
                         {:output (:output consumer-run)}))
                (let [comparison (assert-equal! "Pkl schema/codegen/binding" oracle-output package-output)
                      perturbation (prove-perturbation! oracle-output perturbed-output)
                      binding-report (Files/readString binding-diagnostics)
                      expected-binding-report
                      (str "constructor-and-members=passed\n"
                           "metadata-options=passed\n"
                           "custom-loader=passed\n"
                           "custom-conversion=passed\n"
                           "explicit-polymorphism=passed\n"
                           "unknown=$\n"
                           "incompatible=$.count\n"
                           "missing=$\n"
                           "overflow=$.count\n"
                           "nullability=$.name\n"
                           "nested-list-nullability=$.values[1]\n"
                           "nested-map-nullability=$.mapping[\"bad\"]\n"
                           "nested-pair-nullability=$.pair.second\n"
                           "nullable-nested-generics=passed\n"
                           "numeric-exactness=passed\n"
                           "conversion-failures=passed\n"
                           "polymorphic-mismatch=$\n"
                           "cycle=$.next\n"
                           "config-evaluator=passed\n"
                           "config-navigation=passed\n"
                           "config-builder=passed\n"
                           "disposed=passed\n")]
                  (when-not (= expected-binding-report binding-report)
                    (fail! "Generated consumer focused binding diagnostics were missing, duplicated, or reordered"
                           {:path (str binding-diagnostics)
                            :expected expected-binding-report
                            :actual binding-report}))
                  {:summary {:schemas 9
                             :generated-files (count generated-files)
                             :observations (:matched comparison)
                             :generated-contract-observations 1
                             :codegen-failure-observations 6
                             :binding-observations 2
                             :independent-binding-failure-observations 14
                             :binding-failure-cases 21
                             :focused-contract-evidence evidence-summary
                             :perturbation-detected-at (get-in perturbation [:mismatch :line])}
                   :oracle-output oracle-output
                   :package-output package-output
                   :generated-root generated-root
                   :collision-diagnostics collision-diagnostics
                   :binding-diagnostics binding-diagnostics
                   :generator-dependencies generator-dependencies
                   :consumer-dependencies consumer-dependencies})))))))))

(defn- verify-astral-regex-captures!
  [{:keys [root run-command! java oracle-classes consumer-root consumer-project]}]
  (let [proof-root (harness/clean-directory!
                    (paths/resolve-path root "validation-output"
                                        "differential-proof" "astral-regex-captures"))
        manifest (write-regex-compat-manifest!
                  (paths/resolve-path proof-root "cases.tsv") astral-regex-capture-cases)
        oracle-first (paths/resolve-path proof-root "upstream-first.tsv")
        oracle-second (paths/resolve-path proof-root "upstream-second.tsv")
        package-first (paths/resolve-path proof-root "package-first.tsv")
        package-second (paths/resolve-path proof-root "package-second.tsv")
        perturbed (paths/resolve-path proof-root "historical-boundary-perturbed.tsv")
        ids (mapv first astral-regex-capture-cases)
        operations (set (map second astral-regex-capture-cases))]
    (when-not (and (= 10 (count ids))
                   (= (count ids) (count (set ids)))
                   (= #{"FIND" "REPLACE_ALL" "REPLACE_FIRST" "REPLACE_LAST"
                        "REPLACE_ALL_MAPPED" "REPLACE_FIRST_MAPPED" "REPLACE_LAST_MAPPED"}
                      operations))
      (fail! "The quantified astral capture/replacement fixture is incomplete or duplicated"
             {:cases (count ids) :unique-ids (count (set ids)) :operations operations}))
    (run-independent-probes!
     run-command!
     [{:name :upstream-astral-regex-oracle-first
       :command [(str java) "-cp" (str oracle-classes) "RegexCompatOracle"
                 (str manifest) (str oracle-first)]
       :directory root}
      {:name :upstream-astral-regex-oracle-second
       :command [(str java) "-cp" (str oracle-classes) "RegexCompatOracle"
                 (str manifest) (str oracle-second)]
       :directory root}
      {:name :packaged-astral-regex-probe-first
       :command ["dotnet" "run" "--project" (str consumer-project)
                 "--no-build" "--no-restore" "--" (str manifest) (str package-first)]
       :directory consumer-root}
      {:name :packaged-astral-regex-probe-second
       :command ["dotnet" "run" "--project" (str consumer-project)
                 "--no-build" "--no-restore" "--" (str manifest) (str package-second)]
       :directory consumer-root}])
    (assert-pinned! "Repeated upstream JVM quantified astral regex" oracle-first oracle-second)
    (assert-pinned! "Repeated package-only quantified astral regex" package-first package-second)
    (let [comparison (assert-equal! "quantified astral Java Pattern/Matcher"
                                    oracle-first package-first)
          perturbation (prove-astral-capture-perturbation! oracle-first perturbed)
          summary {:cases (count astral-regex-capture-cases)
                   :capture-span-cases 4
                   :replacement-operations 6
                   :observations (:matched comparison)
                   :historical-perturbation-detected-at
                   (get-in perturbation [:mismatch :line])}]
      (println "Independent quantified astral JVM/package regex differential passed:"
               (pr-str summary))
      {:summary summary :manifest manifest :oracle-output oracle-first
       :package-output package-first :perturbed-output perturbed})))

(defn- read-regex-unicode-source!
  [source]
  (let [source (paths/path source)
        chunks
        (mapv second
              (re-seq #"(?m)^\s*\"([A-Za-z0-9+/=]+)\",\s*$"
                      (Files/readString source StandardCharsets/UTF_8)))]
    (when-not (seq chunks)
      (fail! "Committed Java regex Unicode data has no encoded payload"
             {:source (str source)}))
    (try
      (with-open [input
                  (GZIPInputStream.
                   (ByteArrayInputStream.
                    (.decode (Base64/getDecoder) (str/join chunks))))]
        (slurp input :encoding "UTF-8"))
      (catch Exception error
        (throw
         (ex-info "Committed Java regex Unicode data payload is invalid"
                  {:kind :invalid-regex-unicode-data
                   :source (str source)}
                  error))))))

(defn- verify-regex-compatibility!
  [{:keys [root package-proof run-command! java-release java-home]}]
  (let [proof-root (harness/clean-directory!
                    (paths/resolve-path root "validation-output"
                                        "differential-proof" "regex-compat"))
        manifest (write-regex-compat-manifest!
                  (paths/resolve-path proof-root "cases.tsv") regex-compat-cases)
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        oracle-source (paths/resolve-path root "validation" "regex-compat"
                                          "RegexCompatOracle.java")
        unicode-generator-source
        (paths/resolve-path root "validation" "regex-compat"
                            "GenerateRegexUnicodeData.java")
        committed-unicode-source
        (paths/resolve-path root "runtime" "DripSharp.JavaRegexUnicodeData.cs")
        generated-unicode-tsv (paths/resolve-path proof-root "unicode-data.tsv")
        generated-unicode-source (paths/resolve-path proof-root "JavaRegexUnicodeData.cs")
        committed-unicode-tsv
        (paths/resolve-path proof-root "committed-unicode-data.tsv")
        generated-source-unicode-tsv
        (paths/resolve-path proof-root "generated-source-unicode-data.tsv")
        oracle-first (paths/resolve-path proof-root "upstream-first.tsv")
        oracle-second (paths/resolve-path proof-root "upstream-second.tsv")
        package-first (paths/resolve-path proof-root "package-first.tsv")
        package-second (paths/resolve-path proof-root "package-second.tsv")
        perturbed (paths/resolve-path proof-root "perturbed.tsv")
        javac (paths/resolve-path java-home "bin" "javac")
        java (paths/resolve-path java-home "bin" "java")
        consumer-root (:consumer-root package-proof)
        consumer-project (paths/resolve-path consumer-root "DripSharp.Brine.PackageConsumer.csproj")
        consumer-source (paths/resolve-path consumer-root "Program.cs")
        probe-source (paths/resolve-path root "validation" "regex-compat"
                                         "RegexCompatPackageProbe.cs")
        ids (mapv first regex-compat-cases)
        operations (set (map second regex-compat-cases))
        flags (set (map #(nth % 2) regex-compat-cases))]
    (when-not (and (= 116 (count ids))
                   (= (count ids) (count (set ids)))
                   (every? operations
                           #{"PATTERN" "QUOTE_PATTERN" "QUOTE_REPLACEMENT" "MATCHES"
                             "LOOKING_AT" "FIND" "REGION" "SPLIT" "REPLACE_ALL"
                             "REPLACE_FIRST" "APPEND"})
                   (every? flags [0 1 2 4 8 9 16 32 66 128 256 511 512]))
      (fail! "The Java Pattern compatibility inventory is incomplete or duplicated"
             {:cases (count ids) :unique-ids (count (set ids))
              :operations operations :flags flags}))
    (run-command! {:command [(str javac) "--release" (str java-release)
                             "-d" (str oracle-classes) (str oracle-source)
                             (str unicode-generator-source)]
                   :directory root})
    (run-command! {:command [(str java) "--add-opens" "java.base/java.lang=ALL-UNNAMED"
                             "--add-opens" "java.base/jdk.internal.util.regex=ALL-UNNAMED"
                             "-cp" (str oracle-classes) "GenerateRegexUnicodeData"
                             (str generated-unicode-tsv) (str generated-unicode-source)]
                   :directory root})
    (write-text! committed-unicode-tsv
                 (read-regex-unicode-source! committed-unicode-source))
    (write-text! generated-source-unicode-tsv
                 (read-regex-unicode-source! generated-unicode-source))
    (assert-pinned! "Generated Java regex Unicode C# payload"
                    generated-unicode-tsv generated-source-unicode-tsv)
    (assert-pinned! "JDK-derived Java regex Unicode data"
                    committed-unicode-tsv generated-unicode-tsv)
    (let [unicode-counts
          (->> (str/split-lines (Files/readString generated-unicode-tsv StandardCharsets/UTF_8))
               (map #(first (str/split % #"\t" 2)))
               frequencies)]
      (when-not (= {"V" 1 "A" 338 "B" 338 "S" 342 "P" 368 "F" 2933
                    "G" 15 "I" 3 "N" 38196}
                   unicode-counts)
        (fail! "The pinned JDK regex Unicode inventory changed"
               {:expected {"V" 1 "A" 338 "B" 338 "S" 342 "P" 368 "F" 2933
                           "G" 15 "I" 3 "N" 38196}
                :actual unicode-counts})))
    (Files/copy probe-source consumer-source
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                             "--verbosity:minimal" "--no-restore" "--no-incremental"
                             "-warnaserror"]
                   :directory consumer-root})
    (run-independent-probes!
     run-command!
     [{:name :upstream-regex-oracle-first
       :command [(str java) "-cp" (str oracle-classes) "RegexCompatOracle"
                 (str manifest) (str oracle-first)]
       :directory root}
      {:name :upstream-regex-oracle-second
       :command [(str java) "-cp" (str oracle-classes) "RegexCompatOracle"
                 (str manifest) (str oracle-second)]
       :directory root}
      {:name :packaged-regex-probe-first
       :command ["dotnet" "run" "--project" (str consumer-project)
                 "--no-build" "--no-restore" "--" (str manifest) (str package-first)]
       :directory consumer-root}
      {:name :packaged-regex-probe-second
       :command ["dotnet" "run" "--project" (str consumer-project)
                 "--no-build" "--no-restore" "--" (str manifest) (str package-second)]
       :directory consumer-root}])
    (assert-pinned! "Repeated upstream JVM regex" oracle-first oracle-second)
    (assert-pinned! "Repeated package-only regex" package-first package-second)
    (let [comparison (assert-equal! "Java Pattern/Matcher" oracle-first package-first)
          perturbation (prove-perturbation! oracle-first perturbed)
          astral-captures
          (verify-astral-regex-captures!
           {:root root :run-command! run-command! :java java
            :oracle-classes oracle-classes :consumer-root consumer-root
            :consumer-project consumer-project})
          summary {:cases (count regex-compat-cases)
                   :observations (:matched comparison)
                   :operations (count operations)
                   :compile-flags (sort flags)
                   :quantified-astral-captures (:summary astral-captures)
                   :unicode-data {:blocks 338 :block-names 338 :scripts-and-aliases 342
                                  :properties 368 :case-folds 2933 :character-names 38196
                                  :grapheme-types 15 :indic-conjunct-types 3}
                   :perturbation-detected-at (get-in perturbation [:mismatch :line])}]
      (println "Independent Java Pattern/package regex differential passed:" (pr-str summary))
      {:summary summary :manifest manifest :oracle-output oracle-first
       :package-output package-first :quantified-astral-captures astral-captures})))

(defn- verify-core-differential!
  "Runs representative evaluator/value-model cases in isolated upstream and package processes."
  [{:keys [workspace-root core-package-fn run-command!]
    :or {core-package-fn packaging/verify-package-consumption!
         run-command! process/run!}}]
  (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
        package-proof (core-package-fn {:workspace-root root
                                        :profile "pkl-core-value-model"
                                        :run-command! run-command!})
        public-contract (verify-public-contract-package!
                         {:root root :package-proof package-proof
                          :run-command! run-command!})
        proof-root (harness/clean-directory!
                    (paths/resolve-path root "validation-output"
                                        "differential-proof" "core"))
        manifest (write-core-manifest! (paths/resolve-path proof-root "cases.tsv") core-cases)
        oracle-classes (doto (paths/resolve-path proof-root "upstream-classes")
                         (Files/createDirectories (make-array FileAttribute 0)))
        upstream-root (paths/resolve-path root "research" "pkl")
        oracle-source (paths/resolve-path root "targets" "pkl" "validation" "oracle"
                                          "CoreUpstreamOracle.java")
        oracle-output (paths/resolve-path proof-root "upstream.tsv")
        package-output (paths/resolve-path proof-root "package.tsv")
        perturbed-output (paths/resolve-path proof-root "perturbed.tsv")
        to-fixed-expected (write-to-fixed-expectations!
                           (paths/resolve-path proof-root "to-fixed-expected.tsv")
                           to-fixed-cases)
        to-fixed-upstream (paths/resolve-path proof-root "to-fixed-upstream.tsv")
        to-fixed-package (paths/resolve-path proof-root "to-fixed-package.tsv")
        to-fixed-perturbed (paths/resolve-path proof-root "to-fixed-perturbed.tsv")
        {:keys [java-release java-home entries]} (core-classpath root)
        classpath (str/join File/pathSeparator (map str (cons oracle-classes entries)))
        compile-classpath (str/join File/pathSeparator (map str entries))
        javac (paths/resolve-path java-home "bin" "javac")
        java (paths/resolve-path java-home "bin" "java")
        consumer-root (:consumer-root package-proof)
        consumer-project (paths/resolve-path consumer-root "DripSharp.Brine.PackageConsumer.csproj")
        consumer-source (paths/resolve-path consumer-root "Program.cs")
        probe-source (paths/resolve-path root "targets" "pkl" "validation" "probe"
                                         "CorePackageProbe.cs")]
    (when-not (and (= 27 (count base-core-cases))
                   (= 68 (count to-fixed-cases))
                   (= 95 (count core-cases))
                   (= (set (range 21)) (set (map :digits float-fraction-digit-cases)))
                   (= (set (range 21)) (set (map :digits integer-fraction-digit-cases)))
                   (= (count to-fixed-cases) (count (set (map :id to-fixed-cases)))))
      (fail! "The pinned DripSharp.Brine or toFixed differential contract changed; review the oracle selection"
             {:base-core-cases (count base-core-cases)
              :to-fixed-cases (count to-fixed-cases)
              :core-cases (count core-cases)
              :float-fraction-digits (set (map :digits float-fraction-digit-cases))
              :integer-fraction-digits (set (map :digits integer-fraction-digit-cases))
              :unique-to-fixed-ids (count (set (map :id to-fixed-cases)))}))
    (run-command! {:command ["./gradlew" ":pkl-core:classes" "--console=plain"]
                   :directory upstream-root})
    (run-command! {:command [(str javac) "--release" (str java-release)
                             "-cp" compile-classpath "-d" (str oracle-classes)
                             (str oracle-source)]
                   :directory root})
    (Files/copy probe-source consumer-source
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str consumer-project) "--nologo"
                             "--verbosity:minimal" "--no-restore" "--no-incremental"
                             "-warnaserror"]
                   :directory consumer-root})
    (run-independent-probes!
     run-command!
     [{:name :upstream-core-java-oracle
       :command [(str java) "-cp" classpath "CoreUpstreamOracle"
                 (str manifest) (str oracle-output)]
       :directory upstream-root}
      {:name :packaged-core-dotnet-probe
       :command ["dotnet" "run" "--project" (str consumer-project)
                 "--no-build" "--no-restore" "--" (str manifest)
                 (str package-output)]
       :directory consumer-root}])
    (select-results! oracle-output to-fixed-upstream to-fixed-cases)
    (select-results! package-output to-fixed-package to-fixed-cases)
    (let [regex-compat (verify-regex-compatibility!
                        {:root root :package-proof package-proof :run-command! run-command!
                         :java-release java-release :java-home java-home})
          loading-contract (verify-loading-contract!
                            {:root root :package-proof package-proof
                             :run-command! run-command!
                             :java-release java-release :java-home java-home :entries entries})
          schema-proof (verify-schema-codegen-binding!
                        {:root root :package-proof package-proof :run-command! run-command!
                         :java-release java-release :java-home java-home :entries entries})
          comparison (assert-equal! "DripSharp.Brine" oracle-output package-output)
          perturbation (prove-perturbation! oracle-output perturbed-output)
          to-fixed-upstream-comparison
          (assert-pinned! "Upstream JVM toFixed" to-fixed-expected to-fixed-upstream)
          to-fixed-package-comparison
          (assert-pinned! "Fresh package-only DripSharp.Brine toFixed"
                          to-fixed-expected to-fixed-package)
          to-fixed-perturbation
          (prove-perturbation! to-fixed-expected to-fixed-perturbed)
          revision (str/trim (:output (run-command! {:command ["git" "rev-parse" "HEAD"]
                                                     :directory upstream-root})))
          summary {:upstream-revision revision
                   :package (:identity package-proof)
                   :cases (count core-cases)
                   :value-model-observations 12
                   :evaluation-cases 15
                   :output-cases 4
                   :value-export-cases 2
                   :loading-security-cases 3
                   :error-cases 8
                   :observations (:matched comparison)
                   :to-fixed {:cases (count to-fixed-cases)
                              :float-fraction-digits 21
                              :integer-fraction-digits 21
                              :upstream-observations (:matched to-fixed-upstream-comparison)
                              :package-observations (:matched to-fixed-package-comparison)
                              :perturbation-detected-at
                              (get-in to-fixed-perturbation [:mismatch :line])}
                   :java-pattern-regex (:summary regex-compat)
                   :public-contract (:summary public-contract)
                   :loading-policy-configuration-contract (:summary loading-contract)
                   :schema-codegen-binding (:summary schema-proof)
                   :perturbation-detected-at (get-in perturbation [:mismatch :line])}]
      (println "Independent upstream/package DripSharp.Brine differential passed:" (pr-str summary))
      {:package-proof package-proof
       :public-contract public-contract
       :java-pattern-regex regex-compat
       :loading-policy-configuration-contract loading-contract
       :schema-codegen-binding schema-proof
       :summary summary
       :manifest manifest
       :oracle-output oracle-output
       :package-output package-output
       :to-fixed-expected to-fixed-expected
       :to-fixed-upstream to-fixed-upstream
       :to-fixed-package to-fixed-package})))

(defn- verify-differential-with-executor!
  "Runs the complete parser proof, then the representative packaged DripSharp.Brine proof."
  [options]
  (let [command-timeout-ms (or (:command-timeout-ms options) 1200000)
        delegate (or (:run-command! options) process/run!)
        options (assoc options :run-command!
                       #(delegate (assoc % :timeout-ms command-timeout-ms)))
        parser (verify-parser-differential! options)
        core (verify-core-differential! options)
        summary {:parser (:summary parser) :core (:summary core)}]
    (println "Independent upstream/package differential suite passed:" (pr-str summary))
    {:parser parser :core core :summary summary}))

(defn verify-differential!
  "Regenerates, packs, consumes, and independently compares the complete parser
  corpus plus representative DripSharp.Brine evaluator/value-model behavior."
  ([] (verify-differential! {}))
  ([options]
   (concurrency/call-with-executor
    {:worker-count (:worker-count options)}
    #(verify-differential-with-executor! options))))
