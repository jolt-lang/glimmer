(ns glimmer.reconcile-test
  "End-to-end reconciler tests against the in-memory backend (glimmer.mock-backend).

  These cover what used to be provable only by running a real GTK app: that
  mounting produces the right tree, that a re-render patches widgets in place
  instead of rebuilding them, that keyed children follow their key, and that
  unmounting tears the subscriptions down. Headless, so they run in CI without a
  display."
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.core :as ui]
            [glimmer.mock-backend :as mock]
            [glimmer.ratom :as r]))

(defn- container
  "A fresh root container, with the mock backend installed."
  []
  (mock/install!)
  (mock/widget :box {}))

(defn- labels
  "The :label prop of each child of `w`, in order."
  [w]
  (mapv (fn [c] (:label (:props @c))) (mock/children w)))

(defn- only-child [w] (first (mock/children w)))

;; --- mounting ----------------------------------------------------------------
(deftest mounts-hiccup-into-the-container
  (let [c (container)]
    (ui/mount c :box [:vbox {:spacing 4}
                      [:label {:label "hi"}]
                      [:button {:label "go"}]])
    (is (= [:vbox] (mock/tags c)))
    (let [vbox (only-child c)]
      (is (= 4 (:spacing (:props @vbox))))
      (is (= [:label :button] (mock/tags vbox)))
      (is (= ["hi" "go"] (labels vbox))))))

(deftest strings-and-numbers-become-labels
  (let [c (container)]
    (ui/mount c :box [:vbox {} "hello" 42])
    (let [vbox (only-child c)]
      (is (= [:label :label] (mock/tags vbox)))
      (is (= ["hello" "42"] (labels vbox))))))

(deftest nil-children-and-seqs-are-normalized
  (let [c (container)]
    (ui/mount c :box (into [:vbox {}]
                           [(when false [:label {:label "hidden"}])
                            (for [x ["a" "b"]] [:label {:label x}])]))
    (is (= ["a" "b"] (labels (only-child c))))))

;; --- re-render ---------------------------------------------------------------
(deftest re-render-patches-the-same-widget
  (let [c (container)
        n (r/atom 0)
        app (fn [] [:vbox {} [:label {:label (str "n=" @n)}]])]
    (ui/mount c :box [app])
    (let [vbox (only-child c)
          before (mock/ids vbox)]
      (is (= ["n=0"] (labels vbox)))
      (reset! n 1)
      (is (= ["n=1"] (labels vbox)) "prop was re-applied")
      (is (= before (mock/ids vbox)) "and the widget was reused, not recreated"))))

(deftest changing-a-tag-replaces-the-widget-in-place
  (let [c (container)
        text? (r/atom true)
        app (fn [] [:vbox {} (if @text? [:label {:label "a"}] [:button {:label "b"}])])]
    (ui/mount c :box [app])
    (let [vbox (only-child c)
          before (mock/ids vbox)]
      (reset! text? false)
      (is (= [:button] (mock/tags vbox)))
      (is (= 1 (count (mock/children vbox))) "replaced, not appended alongside")
      (is (not= before (mock/ids vbox)) "a different tag means a different widget"))))

(deftest surplus-children-are-removed
  (let [c (container)
        items (r/atom [1 2 3])
        app (fn [] (into [:vbox {}] (for [i @items] [:label {:label (str i)}])))]
    (ui/mount c :box [app])
    (let [vbox (only-child c)]
      (is (= ["1" "2" "3"] (labels vbox)))
      (reset! items [1])
      (is (= ["1"] (labels vbox)))
      (reset! items [1 2])
      (is (= ["1" "2"] (labels vbox))))))

;; --- components --------------------------------------------------------------
(deftest form-2-outer-fn-runs-once-and-keeps-its-state
  (let [c (container)
        mounts (atom 0)
        counter (fn []
                  (swap! mounts inc)
                  (let [n (r/atom 0)]
                    (fn []
                      [:button {:label (str "count " @n)
                                :on-click (fn [] (swap! n inc))}])))
        tick (r/atom 0)
        app (fn [] [:vbox {} [:label {:label (str "tick " @tick)}] [counter]])]
    (ui/mount c :box [app])
    (let [vbox (only-child c)
          btn (second (mock/children vbox))
          click (fn [] ((:on-click (:props @btn))))]
      (is (= 1 @mounts))
      (click)
      (is (= "count 1" (:label (:props @btn))) "the component re-rendered itself")
      ;; the parent re-renders; the child component keeps its identity and state
      (reset! tick 1)
      (is (= 1 @mounts) "the Form-2 outer fn does not run again")
      (is (= "count 1" (:label (:props @btn))) "local state survived the parent render"))))

(deftest a-component-only-re-renders-itself
  (let [c (container)
        parent-renders (atom 0)
        child-renders (atom 0)
        n (r/atom 0)
        child (fn [] (swap! child-renders inc) [:label {:label (str @n)}])
        app (fn [] (swap! parent-renders inc) [:vbox {} [child]])]
    (ui/mount c :box [app])
    (is (= [1 1] [@parent-renders @child-renders]))
    (reset! n 1)
    (is (= 1 @parent-renders) "the parent never read the cell")
    (is (= 2 @child-renders))))

;; --- keyed children ----------------------------------------------------------
(defn- by-label
  "label -> widget id, for the children of `w`. Keyed reconciliation is supposed
  to keep a row's widget across reorders, so comparing these maps across renders
  is the reuse assertion."
  [w]
  (into {} (map (fn [c] [(:label (:props @c)) (:id @c)]) (mock/children w))))

(deftest keyed-children-follow-their-key
  (let [c (container)
        items (r/atom [:a :b :c])
        app (fn [] (into [:vbox {}]
                         (for [k @items] [:label {:key k :label (name k)}])))]
    (ui/mount c :box [app])
    (let [vbox (only-child c)
          before (by-label vbox)]
      (testing "reorder keeps every widget, in the new order"
        (reset! items [:c :a :b])
        (is (= ["c" "a" "b"] (labels vbox)))
        (is (= before (by-label vbox))))
      (testing "removing an item drops only that widget"
        (reset! items [:c :a])
        (is (= ["c" "a"] (labels vbox)))
        (is (= (select-keys before ["a" "c"]) (by-label vbox))))
      (testing "inserting at the front reuses the survivors"
        (reset! items [:d :c :a])
        (is (= ["d" "c" "a"] (labels vbox)))
        (let [now (by-label vbox)]
          (is (= (select-keys before ["a" "c"]) (select-keys now ["a" "c"])))
          (is (not (contains? (set (vals before)) (get now "d")))
              "the inserted row is a fresh widget"))))))

(deftest keys-never-reach-the-backend-as-props
  (let [c (container)]
    (ui/mount c :box [:vbox {} [:label {:key "a" :label "x"}]])
    (let [lbl (only-child (only-child c))]
      (is (= "x" (:label (:props @lbl))))
      (is (not (contains? (:props @lbl) :key))))))

;; --- unmount -----------------------------------------------------------------
(deftest unmount-removes-the-tree-and-stops-the-watchers
  (let [c (container)
        n (r/atom 0)
        renders (atom 0)
        app (fn [] (swap! renders inc) [:label {:label (str @n)}])
        root (ui/mount c :box [app])]
    (is (= 1 @renders))
    (reset! n 1)
    (is (= 2 @renders))
    (ui/unmount! root c :box)
    (is (= [] (mock/children c)) "the widget is gone from the container")
    (reset! n 2)
    (is (= 2 @renders) "a disposed watcher never renders again")))
