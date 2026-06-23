(ns glimmer.widget-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.widget :as w]))

(deftest escape-markup-escapes-pango-significant-chars
  (testing "leaves plain text untouched"
    (is (= "no special chars" (w/escape-markup "no special chars"))))
  (testing "escapes ampersand first so later escapes don't double-encode"
    (is (= "a &amp; b" (w/escape-markup "a & b"))))
  (testing "escapes angle brackets"
    (is (= "&lt;tag&gt;" (w/escape-markup "<tag>"))))
  (testing "all three together"
    (is (= "&lt;b&gt;a &amp; b&lt;/b&gt;" (w/escape-markup "<b>a & b</b>")))))
