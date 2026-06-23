(ns glimmer.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.core :as ui]))

;; flatten-children is the pure core of the reconciler's child handling: it
;; normalizes a parent's child forms before positional diffing. Lists must render
;; as one widget per element, nil holes must vanish, and a bare vector must stay
;; a single child (standard hiccup semantics). Headless — no GTK needed.
(deftest flatten-children-splices-seqs-and-drops-nils
  (testing "keeps a single element"
    (is (= [[:label {:label "a"}]]
           (ui/flatten-children [[:label {:label "a"}]]))))
  (testing "splices a lazy seq (the `(for ...)` / list case)"
    (is (= [:a :b :c]
           (ui/flatten-children [(for [x [:a :b :c]] x)]))))
  (testing "drops nil holes (the `(when cond ...)` case)"
    (is (= [:a :c]
           (ui/flatten-children [:a nil :c]))))
  (testing "splices a seq that itself contains nils"
    (is (= [:a :c]
           (ui/flatten-children [(for [x [:a nil :c]] x)]))))
  (testing "nested seqs are flattened fully"
    (is (= [:a :b :c]
           (ui/flatten-children [(list :a (list :b :c))]))))
  (testing "does NOT splice vectors — a vector is one child element"
    (is (= [[:a :b]]
           (ui/flatten-children [[:a :b]]))))
  (testing "empty input"
    (is (= [] (ui/flatten-children []))))
  (testing "an empty seq child yields zero children"
    (is (= [] (ui/flatten-children [(for [x []] x)])))))
