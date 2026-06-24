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

;; --- keyed children: pure reconciler planning (headless) ---------------------
;; Keyed reconciliation matches children by a stable :key instead of by position,
;; so list items can be added, removed or reordered without a handler capturing a
;; stale index. child-key extracts a key (:key in the element's props map, or
;; ^{:key k} metadata); keyed-plan turns old/new key lists into a reuse/reorder
;; plan the reconciler applies against GTK widgets. parse-native must also strip
;; :key so it never reaches a GTK setter as an unknown prop.

(deftest child-key-extracts-from-props-or-meta
  (testing "from the props map of a native element"
    (is (= "a" (ui/child-key [:label {:key "a" :label "x"}]))))
  (testing "from the props map of a component invocation"
    (is (= "b" (ui/child-key [some-fn {:key "b"} :arg]))))
  (testing "from ^{:key} metadata"
    (is (= "c" (ui/child-key ^{:key "c"} [:label {:label "x"}]))))
  (testing "nil when unkeyed"
    (is (nil? (ui/child-key [:label {:label "x"}])))
    (is (nil? (ui/child-key [some-fn :arg]))))
  (testing "nil for non-vector / nil"
    (is (nil? (ui/child-key nil)))
    (is (nil? (ui/child-key "hi")))))

(deftest keyed?-detects-uniformly-keyed-children
  (testing "true when every child carries a key"
    (is (true? (ui/keyed? [[:label {:key "a"}] [:label {:key "b"}]]))))
  (testing "false when any child is unkeyed (mixed mode falls back to positional)"
    (is (false? (ui/keyed? [[:label {:key "a"}] [:label {:label "x"}]]))))
  (testing "false when empty — no children, nothing to key"
    (is (false? (ui/keyed? [])))))

(deftest keyed-plan-fresh-list-is-all-create
  (let [plan (ui/keyed-plan [] ["a" "b" "c"])]
    (is (= [] (:destroy plan)))
    (is (= [{:key "a" :kind :create}
            {:key "b" :kind :create}
            {:key "c" :kind :create}]
           (:slots plan)))
    (is (false? (:reorder? plan)))))

(deftest keyed-plan-identical-list-reuses-without-reorder
  (let [plan (ui/keyed-plan ["a" "b" "c"] ["a" "b" "c"])]
    (is (= [] (:destroy plan)))
    (is (= [{:key "a" :kind :reuse :old-index 0}
            {:key "b" :kind :reuse :old-index 1}
            {:key "c" :kind :reuse :old-index 2}]
           (:slots plan)))
    (is (false? (:reorder? plan)))))

(deftest keyed-plan-removing-an-item-destroys-it-no-reorder
  (let [plan (ui/keyed-plan ["a" "b" "c"] ["a" "c"])]
    (is (= [1] (:destroy plan)))
    (is (= [{:key "a" :kind :reuse :old-index 0}
            {:key "c" :kind :reuse :old-index 2}]
           (:slots plan)))
    (is (false? (:reorder? plan)))))

(deftest keyed-plan-reordering-survivors-needs-reorder
  (let [plan (ui/keyed-plan ["a" "b" "c"] ["c" "a" "b"])]
    (is (= [] (:destroy plan)))
    (is (= [{:key "c" :kind :reuse :old-index 2}
            {:key "a" :kind :reuse :old-index 0}
            {:key "b" :kind :reuse :old-index 1}]
           (:slots plan)))
    (is (true? (:reorder? plan)))))

(deftest keyed-plan-inserting-in-the-middle-needs-reorder
  (let [plan (ui/keyed-plan ["a" "c"] ["a" "b" "c"])]
    (is (= [] (:destroy plan)))
    (is (= [{:key "a" :kind :reuse :old-index 0}
            {:key "b" :kind :create}
            {:key "c" :kind :reuse :old-index 1}]
           (:slots plan)))
    (is (true? (:reorder? plan)))))

(deftest keyed-plan-destroys-nil-keyed-legacy-children
  ;; A parent that rendered positionally (nil-keyed children) and then switches
  ;; to keyed children must destroy the old positional widgets, not orphan them.
  ;; nil old keys are absent from new-set, so they land in :destroy and are never
  ;; reused. This contract is what reconcile-keyed-children! relies on.
  (let [plan (ui/keyed-plan [nil "a"] ["a" "b"])]
    (is (= [0] (:destroy plan)))
    (is (= [{:key "a" :kind :reuse :old-index 1}
            {:key "b" :kind :create}]
           (:slots plan)))))

(deftest parse-native-strips-key-from-props
  (let [parse @#'ui/parse-native
        parsed (parse [:label {:key "x" :label "hi"}])]
    (is (= :label (:tag parsed)))
    (is (= "hi" (:label (:props parsed))))
    (is (not (contains? (:props parsed) :key)))))
