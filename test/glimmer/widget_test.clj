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

;; markup: hiccup data -> Pango string, validated against Pango's vocabulary.
;; See glimmer.widget/markup. Headless — no GTK needed.
(deftest markup-renders-pango-from-hiccup
  (testing "span with attributes"
    (is (= "<span foreground=\"#8e939d\">Nothing to do yet</span>"
           (w/markup [:span {:foreground "#8e939d"} "Nothing to do yet"]))))
  (testing "attribute map is optional"
    (is (= "<b>bold</b>" (w/markup [:b "bold"]))))
  (testing "nested elements"
    (is (= "<b><i>x</i></b>" (w/markup [:b [:i "x"]]))))
  (testing "a number child is stringified"
    (is (= "<b>3</b>" (w/markup [:b 3]))))
  (testing "mixed content inside a span"
    (is (= "<span><b>a</b> <i>b</i></span>" (w/markup [:span [:b "a"] " " [:i "b"]]))))
  (testing "link via <a>"
    (is (= "<a href=\"https://example.com\">link</a>"
           (w/markup [:a {:href "https://example.com"} "link"])))))

(deftest markup-escapes-content-and-attribute-values
  (testing "text nodes are escaped"
    (is (= "<b>a &amp; b &lt; c</b>" (w/markup [:b "a & b < c"]))))
  (testing "attribute values escape quotes so the attr can't break out"
    (is (= "<span foreground=\"a&quot;b\">x</span>"
           (w/markup [:span {:foreground "a\"b"} "x"])))))

;; :hbox/:vbox are GtkBox; orientation distinguishes them. with-orientation
;; injects it from the tag so a bare [:hbox ...] lays out horizontally.
(deftest with-orientation-injects-from-tag
  (testing ":hbox gets horizontal"
    (is (= {:spacing 8 :orientation :horizontal} (w/with-orientation :hbox {:spacing 8}))))
  (testing ":vbox gets vertical"
    (is (= {:orientation :vertical} (w/with-orientation :vbox {}))))
  (testing "an explicit :orientation in props always wins"
    (is (= {:orientation :vertical} (w/with-orientation :hbox {:orientation :vertical}))))
  (testing "non-box tags are untouched"
    (is (= {:label "x"} (w/with-orientation :button {:label "x"})))
    (is (= {} (w/with-orientation :box {})))))

(deftest markup-rejects-things-pango-cannot-parse
  (testing "unsupported tag (e.g. an HTML-only tag) throws"
    (is (thrown? Exception (w/markup [:div "x"])))
    (is (thrown? Exception (w/markup [:br]))))
  (testing "unknown span attribute (a typo) throws"
    (is (thrown? Exception (w/markup [:span {:forground "#fff"} "x"]))))
  (testing "attributes on an attribute-less tag like <b> throw"
    (is (thrown? Exception (w/markup [:b {:weight "bold"} "x"])))))
