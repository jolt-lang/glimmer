(ns glimmer.placement-test
  "A widget a slot gains after its siblings have theirs is placed where the slot
  sits among them, not at the end of the container."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer.mock-backend :as mock]))

(use-fixtures :each
  (fn [test]
    (mock/install!)
    (reset! b/loop-running? false)
    (try (test) (finally (reset! b/loop-running? false)))))

;; A component that re-renders on its own and contributes a widget where it had
;; none appends to the parent container, so without positioning it would land
;; after the siblings that should follow it.
(defn- labels-of [w] (mapv #(get-in @% [:props :label]) (mock/children w)))

(deftest a-component-toggled-off-and-on-keeps-its-place-before-its-sibling
  (let [show (r/atom true) win (mock/widget :window {})
        first-comp (fn [] (when @show [:label {:label "first"}]))]
    (ui/mount win :window [:vbox [first-comp] [:label {:label "tail"}]])
    (let [vbox (first (mock/children win))
          tail-id (second (mock/ids vbox))]
      (is (= ["first" "tail"] (labels-of vbox)))
      (reset! show false)
      (is (= ["tail"] (labels-of vbox)))
      (reset! show true)
      (is (= ["first" "tail"] (labels-of vbox)))
      (is (= tail-id (second (mock/ids vbox))) "the sibling is moved around, not recreated"))))

(deftest a-component-that-starts-hidden-appears-before-its-sibling
  (let [show (r/atom false) win (mock/widget :window {})
        first-comp (fn [] (when @show [:label {:label "first"}]))]
    (ui/mount win :window [:vbox [first-comp] [:label {:label "tail"}]])
    (let [vbox (first (mock/children win))]
      (is (= ["tail"] (labels-of vbox)))
      (reset! show true)
      (is (= ["first" "tail"] (labels-of vbox))))))

(deftest a-reappearing-component-goes-after-its-nearest-present-sibling
  (let [show (r/atom false) win (mock/widget :window {})
        never (fn [] (when false [:label {:label "never"}]))
        middle (fn [] (when @show [:label {:label "middle"}]))]
    (ui/mount win :window [:vbox [:label {:label "head"}] [never] [middle] [:label {:label "tail"}]])
    (let [vbox (first (mock/children win))]
      (is (= ["head" "tail"] (labels-of vbox)))
      (reset! show true)
      (is (= ["head" "middle" "tail"] (labels-of vbox)) "the hidden slot before it is skipped")
      (reset! show false)
      (reset! show true)
      (is (= ["head" "middle" "tail"] (labels-of vbox))))))

(deftest a-keyed-component-that-appears-is-placed-before-its-keyed-sibling
  (let [show (r/atom false) win (mock/widget :window {})
        first-comp (fn [] (when @show [:label {:label "first"}]))]
    (ui/mount win :window [:vbox [first-comp {:key :first}] [:label {:key :tail :label "tail"}]])
    (let [vbox (first (mock/children win))]
      (is (= ["tail"] (labels-of vbox)))
      (reset! show true)
      (is (= ["first" "tail"] (labels-of vbox)))
      (reset! show false)
      (is (= ["tail"] (labels-of vbox)))
      (reset! show true)
      (is (= ["first" "tail"] (labels-of vbox))))))

;; A keyed pass that reorders its children leaves a hidden component's slot
;; without a widget, so when the component reappears it must be placed by the
;; slot order the reorder left behind, not the order it was mounted in.
(deftest a-hidden-keyed-component-reappears-in-its-reordered-slot
  (let [show-b? (r/atom true) order (r/atom [:a :b :c]) win (mock/widget :window {})
        row (fn [k] [:label {:label (name k)}])
        comps {:a (fn [] (row :a))
               :b (fn [] (when @show-b? (row :b)))
               :c (fn [] (row :c))}
        app (fn [] (into [:vbox {}] (for [k @order] [(comps k) {:key k}])))]
    (ui/mount win :window [app])
    (let [vbox (first (mock/children win))
          [a-id _ c-id] (mock/ids vbox)]
      (is (= ["a" "b" "c"] (labels-of vbox)))
      (reset! show-b? false)
      (is (= ["a" "c"] (labels-of vbox)))
      (reset! order [:b :c :a])
      (is (= ["c" "a"] (labels-of vbox)))
      (reset! show-b? true)
      (is (= ["b" "c" "a"] (labels-of vbox)) "placed by the reordered slots, not the mounted ones")
      (is (= [c-id a-id] (subvec (mock/ids vbox) 1)) "the siblings are moved around, not recreated"))))

(deftest a-slot-turning-from-a-component-into-an-element-keeps-its-place
  (let [native? (r/atom false) win (mock/widget :window {})
        comp (fn [] [:label {:label "comp"}])
        app (fn [] [:vbox (if @native? [:button {:label "button"}] [comp]) [:label {:label "tail"}]])]
    (ui/mount win :window [app])
    (let [vbox (first (mock/children win))]
      (is (= ["comp" "tail"] (labels-of vbox)))
      (reset! native? true)
      (is (= ["button" "tail"] (labels-of vbox))))))

(deftest a-component-nested-in-a-wrapper-is-placed-in-the-wrapper-slot
  (let [show (r/atom false) win (mock/widget :window {})
        conditional (fn [] (when @show [:label {:label "first"}]))
        wrapper (fn [] [conditional])]
    (ui/mount win :window [:vbox [wrapper] [:label {:label "tail"}]])
    (let [vbox (first (mock/children win))
          tail-id (first (mock/ids vbox))]
      (is (= ["tail"] (labels-of vbox)))
      (reset! show true)
      (is (= ["first" "tail"] (labels-of vbox)))
      (reset! show false)
      (reset! show true)
      (is (= ["first" "tail"] (labels-of vbox)))
      (is (= tail-id (second (mock/ids vbox))) "the sibling is moved around, not recreated"))))

(deftest a-backend-without-reorder-keeps-appending-new-widgets
  (let [show (r/atom false) win (mock/widget :window {})
        first-comp (fn [] (when @show [:label {:label "first"}]))]
    (b/register! (dissoc mock/backend :reorder-child!))
    (ui/mount win :window [:vbox [first-comp] [:label {:label "tail"}]])
    (reset! show true)
    (is (= ["tail" "first"] (labels-of (first (mock/children win)))))))

;; Placement also runs inside a parent's children pass: a reused slot whose
;; component is shown by a prop the parent passes appends in the same way as a
;; component re-rendering on its own.
(deftest a-component-shown-by-its-parent-is-placed-before-its-sibling
  (let [show (r/atom false) win (mock/widget :window {})
        conditional (fn [show?] (when show? [:label {:label "first"}]))
        app (fn [] [:vbox [conditional @show] [:label {:label "tail"}]])]
    (ui/mount win :window [app])
    (let [vbox (first (mock/children win))
          tail-id (first (mock/ids vbox))]
      (is (= ["tail"] (labels-of vbox)))
      (reset! show true)
      (is (= ["first" "tail"] (labels-of vbox)))
      (is (= tail-id (second (mock/ids vbox))) "the sibling is moved around, not recreated"))))

;; --- the order state on the container ---------------------------------------
;; The tests below look at the container's instance, where the reconciler keeps
;; each slot's rank (:ranks, absent while ranks are implicit), the high-water
;; mark :tail and the lazily built :shown index.

(defn- labels-in [win] (labels-of (first (mock/children win))))

(defn- container-of
  "The instance atom of the native container an [app] root rendered."
  [root] (first (:children @root)))

(defn- in-order!
  "Assert that the container `c` in `win` shows `expected`, that its ranks rise
  along :children, and that each index entry names a listed slot at its own rank,
  which allows at most one entry per slot."
  [win c expected msg]
  (is (= expected (labels-in win)) msg)
  (let [{:keys [children ranks shown]} @c
        rs (map-indexed (fn [i s] (if ranks (get ranks s) (* 1024 (inc i)))) children)
        rank-of (zipmap children rs)]
    (is (and (every? some? rs) (or (empty? rs) (apply < rs))) (str msg ": ranks rise"))
    (is (every? (fn [[r s]] (= r (rank-of s))) shown) (str msg ": index entries at their slots' ranks"))))

(defn- count-ops!
  "Install `base` with its container operations counted into the map `counts`.
  The default base does O(1) work and keeps no order, so a test can count or time
  a large render without paying the mock's vector rebuilds."
  ([counts]
   (count-ops! counts (merge mock/backend (zipmap [:append-child! :remove-child! :replace-child! :reorder-child!]
                                                  (repeat (fn [& _] nil))))))
  ([counts base]
   (b/register! (reduce-kv (fn [be op k]
                             (update be op (fn [f] (fn [& args] (swap! counts update k (fnil inc 0)) (apply f args)))))
                           base {:append-child! :appends :remove-child! :removes
                                 :replace-child! :replaces :reorder-child! :moves}))))

(defn- move-row
  "`v` with the element at index `from` moved to index `to` of the result."
  [v from to]
  (let [x (nth v from) w (vec (concat (subvec v 0 from) (subvec v (inc from))))]
    (vec (concat (subvec w 0 to) [x] (subvec w to)))))

;; --- performance shape, by counting ------------------------------------------
(deftest a-mount-moves-nothing-and-builds-no-index
  (let [counts (atom {}) win (mock/widget :window {})
        app (fn [] (into [:vbox] (for [i (range 1000)] [:label {:label (str i)}])))]
    (count-ops! counts)
    (let [vbox (container-of (ui/mount win :window [app]))]
      (is (= {:appends 1001} @counts) "appends only")
      (is (nil? (:shown @vbox)) "no index")
      (is (nil? (:ranks @vbox)) "and no explicit ranks"))))

(deftest tail-appends-and-in-order-reveals-build-no-index
  (testing "a positional and a keyed tail append"
    (doseq [keyed? [false true]]
      (let [n (r/atom 3) win (mock/widget :window {})
            app (fn [] (into [:vbox] (for [i (range @n)]
                                       (if keyed? [:label {:key i :label (str i)}] [:label {:label (str i)}]))))
            vbox (container-of (ui/mount win :window [app]))]
        (reset! n 6)
        (is (= ["0" "1" "2" "3" "4" "5"] (labels-in win)))
        (is (nil? (:shown @vbox)))
        (is (nil? (:ranks @vbox))))))
  (testing "a reveal in which nothing later has a widget, by each row and by the parent"
    (let [cells (vec (repeatedly 4 #(r/atom false))) win (mock/widget :window {})
          row (fn [i] (when @(nth cells i) [:label {:label (str i)}]))
          app (fn [] (into [:vbox] (for [i (range 4)] [row i])))
          vbox (container-of (ui/mount win :window [app]))
          counts (atom {})]
      (doseq [c cells] (reset! c true))
      (is (= ["0" "1" "2" "3"] (labels-in win)))
      (is (nil? (:shown @vbox)))
      (count-ops! counts mock/backend)
      (testing "and a row revealed before a hidden last row, which leaves :tail high"
        (reset! (nth cells 2) false)
        (reset! (nth cells 3) false)
        (reset! (nth cells 2) true)
        (is (= ["0" "1" "2"] (labels-in win)))
        (is (nil? (:moves @counts)))
        (is (nil? (:shown @vbox)))
        (reset! (nth cells 3) true))
      (testing "and the last row hidden and shown again, at the rank :tail still names"
        (reset! (peek cells) false)
        (reset! (peek cells) true)
        (is (= ["0" "1" "2" "3"] (labels-in win)))
        (is (nil? (:moves @counts)))
        (is (nil? (:shown @vbox)))))
    (let [show (r/atom false) win (mock/widget :window {})
          row (fn [s i] (when s [:label {:label (str i)}]))
          app (fn [] (let [s @show] (into [:vbox] (for [i (range 4)] [row s i]))))
          vbox (container-of (ui/mount win :window [app]))]
      (reset! show true)
      (is (= ["0" "1" "2" "3"] (labels-in win)))
      (is (nil? (:shown @vbox))))))

(deftest moving-one-keyed-row-moves-one-widget
  (let [counts (atom {}) order (r/atom (vec (range 1000))) win (mock/widget :window {})
        app (fn [] (into [:vbox] (for [k @order] [:label {:key k :label (str k)}])))]
    (count-ops! counts)
    (ui/mount win :window [app])
    (doseq [[from to] [[250 750] [999 0] [0 999] [500 501]]]
      (reset! counts {})
      (swap! order move-row from to)
      (is (= {:moves 1} @counts) (str "moving row " from " to " to)))
    (reset! counts {})
    (swap! order #(vec (cons :new %)))
    (is (= {:appends 1 :moves 1} @counts) "a row created ahead of the others moves once")))

;; Inserting into the same gap halves it each time, so after about ten inserts
;; the midpoint has no integer left and the pass renumbers the container. The
;; widgets move by the pass's own move step, so that costs no extra moves.
(deftest moving-one-keyed-row-across-a-rebalance-still-moves-one-widget
  (let [counts (atom {}) order (r/atom (vec (range 20))) win (mock/widget :window {})
        app (fn [] (into [:vbox] (for [k @order] [:label {:key k :label (str k)}])))
        _ (count-ops! counts mock/backend)
        vbox (container-of (ui/mount win :window [app]))
        rebalanced (atom 0)]
    (dotimes [_ 15]
      (reset! counts {})
      ;; the last row goes right after row 0, into the gap the last insert halved
      (swap! order #(move-row % (dec (count %)) 1))
      (is (= {:moves 1} @counts))
      (in-order! win vbox (mapv str @order) "after a move")
      (when (nil? (:ranks @vbox)) (swap! rebalanced inc)))
    (is (pos? @rebalanced) "the gap ran out and the container was renumbered")))

(deftest the-index-stays-consistent-under-repeated-moves-and-toggles
  (let [order (r/atom (vec (range 30))) shows (vec (repeatedly 30 #(r/atom true)))
        win (mock/widget :window {}) rnd (java.util.Random. 7)
        row (fn [k] (when @(nth shows k) [:label {:label (str k)}]))
        app (fn [] (into [:vbox] (for [k @order] [row {:key k} k])))
        vbox (container-of (ui/mount win :window [app]))]
    (reset! (nth shows 3) false)
    (reset! (nth shows 3) true)
    (is (some? (:shown @vbox)) "a late placement built the index")
    (dotimes [_ 200]
      (let [n (count @order)]
        (swap! order move-row (.nextInt rnd n) (.nextInt rnd n))
        (let [k (.nextInt rnd 30)] (swap! (nth shows k) not)))
      (in-order! win vbox (vec (for [k @order :when @(nth shows k)] (str k))) "after a move and a toggle"))))

;; Each widget a reveal places costs a couple of index lookups, so revealing every
;; row grows near linearly; a sibling scan per placement would make it quadratic,
;; 64 times the work for 8 times the rows. Counted moves cannot tell the two
;; apart, so this times them, on the counting backend, with a bound far from both.
(defn- reveal-ms
  "Mount `n` rows, then time revealing them `how`: :reverse or :random, each row
  through its own cell; :shared, every row reading one cell; :parent, the parent
  passing every other row its visibility."
  [n how]
  (let [cells (vec (repeatedly n #(r/atom false))) shared (r/atom false) win (mock/widget :window {})
        row (case how
              (:reverse :random) (fn [i] (when @(nth cells i) [:label {:label (str i)}]))
              :shared (fn [i] (when @shared [:label {:label (str i)}]))
              :parent (fn [s i] (when (or s (even? i)) [:label {:label (str i)}])))
        app (fn [] (let [s (when (= how :parent) @shared)]
                     (into [:vbox] (for [i (range n)] (if (= how :parent) [row s i] [row i])))))
        rnd (java.util.Random. 11)
        reveal! (case how
                  :reverse #(doseq [i (range (dec n) -1 -1)] (reset! (nth cells i) true))
                  :random #(doseq [i (sort-by (fn [_] (.nextInt rnd)) (range n))] (reset! (nth cells i) true))
                  (:shared :parent) #(reset! shared true))]
    (ui/mount win :window [app])
    (let [t0 (System/nanoTime)]
      (reveal!)
      (/ (- (System/nanoTime) t0) 1e6))))

(deftest revealing-rows-scales-near-linearly
  (count-ops! (atom {}))
  (reveal-ms 500 :random)
  (doseq [how [:reverse :random :shared :parent]]
    (let [small (reveal-ms 500 how) large (reveal-ms 4000 how)]
      (is (< (/ large small) 25) (str how ": 500 rows " small " ms, 4000 rows " large " ms")))))

;; --- correctness -------------------------------------------------------------
(deftest a-hidden-row-reappears-between-the-rows-a-later-pass-ranked-it
  (let [order (r/atom [:a :b :c :d]) show-c? (r/atom true) win (mock/widget :window {})
        comps {:a (fn [] [:label {:label "a"}]) :b (fn [] [:label {:label "b"}])
               :c (fn [] (when @show-c? [:label {:label "c"}])) :d (fn [] [:label {:label "d"}])}
        app (fn [] (into [:vbox] (for [k @order] [(comps k) {:key k}])))
        vbox (container-of (ui/mount win :window [app]))]
    (reset! order [:d :a :b :c])
    (is (some? (:ranks @vbox)) "the move made the ranks explicit")
    (reset! show-c? false)
    (reset! order [:d :a :c :b])
    (is (= ["d" "a" "b"] (labels-in win)))
    (reset! show-c? true)
    (is (= ["d" "a" "c" "b"] (labels-in win)) "at the midpoint between a and b")))

;; A keyed pass moves widgets only after its children render, so a widget it
;; creates, or one a kept row gains, must not be placed by where the moved rows
;; still sit while it renders.
(deftest widgets-gained-during-a-reordering-pass-land-in-the-new-order
  (testing "a row created after a row moved ahead of it"
    (let [order (r/atom [:a :b :c]) win (mock/widget :window {})
          app (fn [] (into [:vbox] (for [k @order] [:label {:key k :label (name k)}])))]
      (ui/mount win :window [app])
      (reset! order [:c :x :a :b])
      (is (= ["c" "x" "a" "b"] (labels-in win)))))
  (testing "a kept row the same pass reveals, after a row moved ahead of it"
    (let [state (r/atom {:order [:m :a :k :b] :show-k? false}) win (mock/widget :window {})
          row (fn [k s] (when (or (not= k :k) s) [:label {:label (name k)}]))
          app (fn [] (let [{:keys [order show-k?]} @state]
                       (into [:vbox] (for [k order] [row {:key k} k show-k?]))))]
      (ui/mount win :window [app])
      (is (= ["m" "a" "b"] (labels-in win)))
      (reset! state {:order [:a :m :k :b] :show-k? true})
      (is (= ["a" "m" "k" "b"] (labels-in win))))))

;; A render error skips the move step, so the widgets no longer follow the ranks
;; the failed pass published. The next pass cannot trust its kept rows to sit in
;; order, and moves every widget, as a full reorder did before; so does a pass
;; that moves nothing itself.
(deftest a-pass-after-a-failed-reorder-repairs-widget-order
  (doseq [[what next! expect] [["a further reorder" #(reset! (:order %) [:b :a :d :c]) ["b" "a" "d" "c"]]
                               ["a pass with no reorder" #(swap! (:tick %) inc) ["b" "a" "c" "d"]]]]
    (let [order (r/atom [:a :b :c :d]) tick (r/atom 0) fail-b? (atom false) win (mock/widget :window {})
          row (fn [k] (when (and (= k :b) @fail-b?) (throw (ex-info "b failed" {})))
                [:label {:label (name k)}])
          app (fn [] @tick (into [:vbox] (for [k @order] [row {:key k} k])))
          vbox (container-of (ui/mount win :window [app]))]
      (reset! fail-b? true)
      (is (thrown? Exception (reset! order [:b :a :c :d])))
      (is (:unordered @vbox))
      (reset! fail-b? false)
      (next! {:order order :tick tick})
      (is (= expect (labels-in win)) what)
      (is (not (:unordered @vbox)) what))))

(deftest a-pass-after-a-failed-late-placement-repairs-widget-order
  (doseq [keyed? [true false]]
    (let [fail? (atom false) show-a (r/atom false) tick (r/atom 0) win (mock/widget :window {})
          a (fn [] (when @show-a [:label {:label "a"}]))
          app (fn [] @tick (if keyed?
                             [:vbox [a {:key :a}] [:label {:key :b :label "b"}]]
                             [:vbox [a] [:label {:label "b"}]]))]
      (b/register! (assoc mock/backend :reorder-child!
                          (fn [& args]
                            (when @fail? (throw (ex-info "reorder failed" {})))
                            (apply (:reorder-child! mock/backend) args))))
      (let [vbox (container-of (ui/mount win :window [app]))]
        (reset! fail? true)
        (is (thrown? Exception (reset! show-a true)))
        (is (= ["b" "a"] (labels-in win)))
        (is (:unordered @vbox))
        (reset! fail? false)
        (swap! tick inc)
        (is (= ["a" "b"] (labels-in win)) (if keyed? "keyed" "positional"))))))

(deftest positional-keyed-positional-round-trips-keep-order-and-unique-ranks
  (let [keyed? (r/atom false) order (r/atom [0 1 2 3]) shows (vec (repeatedly 8 #(r/atom true)))
        win (mock/widget :window {})
        row (fn [i] (when @(nth shows i) [:label {:label (str i)}]))
        app (fn [] (into [:vbox] (for [i @order] (if @keyed? [row {:key i} i] [row i]))))
        vbox (container-of (ui/mount win :window [app]))
        expect! #(in-order! win vbox (vec (for [i @order :when @(nth shows i)] (str i))) %)]
    (reset! (nth shows 1) false)
    (reset! (nth shows 1) true)
    (expect! "positional, with an index")
    (reset! keyed? true)
    (expect! "keyed")
    (reset! order [3 0 1 2])
    (reset! (nth shows 0) false)
    (reset! order [3 1 0 2 4])
    (reset! (nth shows 0) true)
    (expect! "keyed, after moves")
    (reset! keyed? false)
    (expect! "positional again")
    (is (nil? (:ranks @vbox)) "the positional pass dropped the explicit ranks")
    (swap! order conj 5 6)
    (reset! (nth shows 5) false)
    (reset! (nth shows 5) true)
    (reset! (nth shows 2) false)
    (reset! (nth shows 2) true)
    (expect! "positional tail slots placed by their own ranks")
    (reset! keyed? true)
    (reset! order [6 5 4 3 2 1 0])
    (reset! (nth shows 4) false)
    (reset! (nth shows 4) true)
    (expect! "keyed once more")))

;; --- lifecycle ---------------------------------------------------------------
(deftest a-slot-whose-comp-to-native-append-failed-resolves-to-no-widget
  (let [fail? (atom false) order (r/atom [:b :a]) win (mock/widget :window {})
        comp-b (fn [] [:label {:label "b-old"}])
        el (fn [k] (case k
                     :a [:label {:key :a :label "a"}]
                     :b (if (= @order [:b :a]) [comp-b {:key :b}] [:button {:key :b :label "b-new"}])))
        app (fn [] (into [:vbox] (map el @order)))]
    (b/register! (assoc mock/backend :append-child!
                        (fn [pt p c]
                          (when (and @fail? (= :button (:tag @c))) (throw (ex-info "append failed" {})))
                          ((:append-child! mock/backend) pt p c))))
    (let [vbox (container-of (ui/mount win :window [app]))]
      (reset! fail? true)
      (is (thrown? Exception (reset! order [:a :b])))
      (is (= ["a"] (labels-in win)))
      (let [b-slot (some #(when (= :b (:key @%)) %) (:children @vbox))]
        (is (nil? (:widget @b-slot)) "the slot holds no widget")
        (is (empty? (:children @b-slot)) "nor the component that rendered the removed one")))))

(deftest a-row-a-nested-pass-drops-stays-dropped
  ;; keyed [a b c] -> [b a c]: b's render makes the owner re-render inline to
  ;; [b c], whose nested pass destroys a. The outer pass must not render a again,
  ;; which would throw (a-fails is a plain atom, so a's own watcher never fires
  ;; for it) or leave a live watcher on `cell`, nor let its move step reattach a.
  (doseq [a-throws? [true false]]
    (let [order (r/atom [:a :b :c]) drop-a (r/atom false) arm (atom false) a-fails (atom false)
          cell (r/atom 0) renders (atom 0) win (mock/widget :window {})
          row (fn [{:keys [k]}]
                (when (= k :a)
                  @cell
                  (swap! renders inc)
                  (when @a-fails (throw (ex-info "a render failed" {}))))
                (when (and (= k :b) @arm)
                  (reset! arm false) (reset! a-fails a-throws?) (reset! drop-a true))
                [:label {:label (name k)}])
          app (fn [] (let [o (if @drop-a (vec (remove #{:a} @order)) @order)]
                       (into [:vbox] (for [k o] [row {:key k :k k}]))))
          root (ui/mount win :window [app])
          what (str "a " (if a-throws? "throwing" "rendering") " dropped row")]
      (reset! arm true)
      (reset! renders 0)
      (reset! order [:b :a :c])
      (is (= ["b" "c"] (labels-in win)) (str what " stays out"))
      (swap! cell inc)
      (ui/unmount! root win :window)
      (swap! cell inc)
      (is (zero? @renders) (str what " never renders again")))))

(deftest a-component-in-a-dropped-slot-attaches-nothing
  ;; [head comp tail]: showing comp, its render empties the owner, whose nested
  ;; pass drops every slot while comp's render is still under way.
  (let [show (r/atom false) empty (r/atom false) arm (atom true) win (mock/widget :window {})
        comp (fn [s] (when s (when @arm (reset! arm false) (reset! empty true)) [:label {:label "mid"}]))
        app (fn [] (let [s @show e @empty]
                     (if e [:vbox] [:vbox [:label {:label "head"}] [comp s] [:label {:label "tail"}]])))
        vbox (container-of (ui/mount win :window [app]))]
    (reset! show true)
    (is (= [] (labels-in win)) "and the empty nested pass does not throw")
    (is (= -1 (:tail @vbox)))))

(deftest a-render-that-re-renders-its-owner-during-mount-still-mounts-in-order
  ;; init's first render changes what the owner reads, so the owner re-renders
  ;; inline and runs a second children pass on the same vbox; the outer pass then
  ;; turns slot 1 from the inner pass's label into a component.
  (let [x (r/atom 0) win (mock/widget :window {})
        init (fn [] (when (zero? @x) (reset! x 1)) [:label {:label "init"}])
        wrap (fn [] [:label {:label "wrapped"}])
        owner (fn [] (let [v @x] [:vbox [init] (if (zero? v) [wrap] [:label {:label "plain"}])
                                  [:label {:label "z"}]]))]
    (ui/mount win :window [owner])
    (is (= ["init" "wrapped" "z"] (labels-in win)))))
