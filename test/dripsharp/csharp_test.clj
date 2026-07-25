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
